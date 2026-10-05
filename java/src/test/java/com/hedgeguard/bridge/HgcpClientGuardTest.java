package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hgcp.v1.Hgcp;

/**
 * {@code T-166} —— 为 {@code T-042} 独立复核（席 {@code te-verify-t042}）查出的
 * <b>两处「核心机制零承重」</b>补齐守卫（缺陷 {@code TE-T042-001} / {@code TE-T042-003}）。
 *
 * <p><b>缺陷本体</b>：两处机制「关掉它，全量测试仍全绿」——
 * <ol>
 *   <li>{@code TE-T042-001}：{@link HgcpClient#cancelOrder} 的
 *       {@code setOperatorId(operatorId)} 改为 {@code setOperatorId(0L)}
 *       （= 退回 {@code CH-018} 修复前的静默鉴权降级），既有用例 <b>27/27 仍全绿</b>；</li>
 *   <li>{@code TE-T042-003}：{@link IdempotencyCache#first(String, long)} 重复命中分支
 *       的 {@code return false} 改为 {@code return true}（去重被击穿），既有用例 <b>15/15 仍全绿</b>。</li>
 * </ol>
 *
 * <p><b>断言形态（本类立意的关键）</b>：两处均须是
 * <b>行为断言</b>，而非「能解析 / 桩返回 acknowledged」这类结构性断言：
 * <ul>
 *   <li>操作人路径 —— 抓出 <b>实际下发到线上的字节</b>（重解 {@code CMD_CANCEL_ORDER} 出站帧），
 *       断言 {@code CmdCancelOrder.operator_id} <b>逐字等于入参</b>；</li>
 *   <li>幂等去重 —— 断言「<b>第二次的副作用未被施加</b>」，而非「最终值正确」。</li>
 * </ul>
 *
 * <p><b>⚠️ 故意规避的假守卫</b>：{@code HgcpResumeDataReplayTest.positionQty} 为
 * <b>覆盖式读最后一条</b> ⇒ 「跨批重复喂」会自愈（后写覆盖前写、结果一样），对去重
 * <b>零守卫力</b>，<b>不可</b>作验收 3 的替代证据（复核席明确判定）。本类因此令
 * 重复事件携带<b>可区分的载荷</b>：若去重生效 ⇒ 第二份被整体丢弃、镜像保持首次载荷；
 * 若去重被击穿 ⇒ 第二份覆盖首次载荷 ⇒ 断言必红。
 *
 * <p><b>诚实披露</b>：本类经<b>公开接缝</b>（{@link HgcpClient#onFrame} / 记录型
 * {@link HgcpTransport}）驱动，<b>未改生产码一行</b>。跨进程与 C++ 侧联调不在本类范围（{@code T-057}）。
 */
class HgcpClientGuardTest {

    // ==================== 接缝 ====================

    /** 记录出站整帧的内存传输桩（只收不发；不启动读线程 ⇒ 无后台线程）。 */
    private static final class RecordingTransport implements HgcpTransport {
        final List<byte[]> sent = new ArrayList<>();

        @Override
        public void connect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            sent.add(frame.clone());
        }

        @Override
        public int read(byte[] buf) throws IOException {
            throw new IOException("记录桩不读取");
        }

        @Override
        public void close() {
        }

        byte[] last() {
            return sent.get(sent.size() - 1);
        }
    }

    private static HgcpClient newClient(HgcpTransport transport) {
        return new HgcpClient("java-test", "token-not-logged", "boot-1", 1, () -> transport);
    }

    /**
     * 注入传输接缝：生产路径由 {@code connectAndHandshake()}（私有）装配 {@code transport}，
     * 本类<b>不建连</b>（不启读/心跳线程）⇒ 以反射写入该私有字段，作为确定性测试接缝。
     *
     * <p>这是本类唯一依赖内部字段名的点；反射取字段仅在测试内使用（同 {@code T099ScopeOfDeprecationTest} 先例）。
     */
    private static void injectTransport(HgcpClient client, HgcpTransport transport) {
        try {
            java.lang.reflect.Field f = HgcpClient.class.getDeclaredField("transport");
            f.setAccessible(true);
            f.set(client, transport);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入传输接缝 transport（字段被改名？）", e);
        }
    }

    /** 构造一个<b>已鉴权</b>（可直接下发命令）的客户端：经公开接缝喂一条成功 {@code MSG_HELLO_ACK}。 */
    private static HgcpClient authenticatedClient(RecordingTransport t) {
        HgcpClient c = newClient(t);
        injectTransport(c, t);
        Hgcp.MsgHelloAck ack = Hgcp.MsgHelloAck.newBuilder()
                .setAccepted(true)
                .setServerProtoVersion(HgcpFrame.WIRE_VERSION)
                .setSessionId("sess-1")
                .setServerState(Hgcp.ServerState.SERVER_STATE_READY)
                .setParamVersion(3L)
                .build();
        feed(c, HgcpConstants.MSG_HELLO_ACK, HgcpFrame.FLAG_IS_REPLY, 1L, 1L, ack.toByteArray());
        assertEquals(HgcpClient.AuthState.AUTHENTICATED, c.authState(),
                "夹具：成功 HELLO_ACK 后须为 AUTHENTICATED（否则命令无法下发，本类无守卫力）");
        return c;
    }

    private static HgcpFrame.Decoded feed(HgcpClient client, int type, int flags, long seq,
                                          long msgId, byte[] payload) {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = type;
        h.flags = flags;
        h.seq = seq;
        h.msgId = msgId;
        byte[] frame = HgcpFrame.encode(h, payload);
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        client.onFrame(d);
        return d;
    }

    private static HgcpFrame.Decoded decode(byte[] frame) {
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "出站帧须为可解完整 wire 帧");
        return d;
    }

    // ==================== TE-T042-001：cancelOrder 操作人路径 ====================

    @Test
    @DisplayName("T-166/TE-T042-001：cancelOrder 下发的 CmdCancelOrder.operator_id 须逐字等于入参（不得退化为 0）")
    void cancelOrderCarriesOperatorIdOnTheWire() {
        RecordingTransport transport = new RecordingTransport();
        HgcpClient client = authenticatedClient(transport);

        long operatorId = 987654321L;
        client.cancelOrder(4242L, "客户撤单", operatorId);

        assertEquals(1, transport.sent.size(), "cancelOrder 须恰好下发一帧命令");
        HgcpFrame.Decoded d = decode(transport.last());
        assertEquals(HgcpConstants.CMD_CANCEL_ORDER, d.header.type,
                "帧类型须为 CMD_CANCEL_ORDER（0x0103）");

        Hgcp.CmdCancelOrder cmd;
        try {
            cmd = Hgcp.CmdCancelOrder.parseFrom(d.payload);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError("出站 payload 须为合法 CmdCancelOrder", e);
        }

        assertEquals(operatorId, cmd.getOperatorId(),
                "线格式必填 operator_id 须逐字承载入参（FR-TRADE-6 验收4 / CH-018）；"
                        + "改为 0L 即退回『0=系统』的静默鉴权降级");
        assertNotEquals(0L, cmd.getOperatorId(),
                "operator_id 不得为 0（0 语义 = 系统，属 CH-018 所指的鉴权降级形态）");
        assertEquals(4242L, cmd.getOrderId(), "order_id 须逐字承载");
        assertEquals("客户撤单", cmd.getReason(), "reason 须逐字承载");
    }

    @Test
    @DisplayName("T-166/CH-018：confirmOrder 的 operator_id 与 confirm_token 在线上分列承载（不得挤入同一槽位）")
    void confirmOrderCarriesOperatorAndTokenOnTheWire() {
        RecordingTransport transport = new RecordingTransport();
        HgcpClient client = authenticatedClient(transport);

        long operatorId = 123456789L;
        client.confirmOrder(555L, operatorId, "tok-xyz");

        assertEquals(1, transport.sent.size(), "confirmOrder 须恰好下发一帧命令");
        HgcpFrame.Decoded d = decode(transport.last());
        assertEquals(HgcpConstants.CMD_CONFIRM_ORDER, d.header.type,
                "帧类型须为 CMD_CONFIRM_ORDER（0x0102）");

        Hgcp.CmdConfirmOrder cmd;
        try {
            cmd = Hgcp.CmdConfirmOrder.parseFrom(d.payload);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError("出站 payload 须为合法 CmdConfirmOrder", e);
        }

        assertEquals(operatorId, cmd.getOperatorId(),
                "operator_id 须逐字承载入参（CH-018 消除『非数字操作人串静默退化为 0』）");
        assertNotEquals(0L, cmd.getOperatorId());
        assertEquals("tok-xyz", cmd.getConfirmToken(),
                "confirm_token 须独立承载（不得与操作人共用一个 String 槽位）");
        assertEquals(555L, cmd.getOrderId());
    }

    // ==================== T-173：0x1019 承载链（proto CmdPlaceOrder 3/16/17/18/19/20）====================

    @Test
    @DisplayName("T-173/CH-020：placeOrder 的 CmdPlaceOrder 六字段（exposure_id/trade_reason_*/related_*）须逐字上 wire")
    void placeOrderCarriesRelatedObjectAndReasonFieldsOnTheWire() {
        RecordingTransport transport = new RecordingTransport();
        HgcpClient client = authenticatedClient(transport);
        assertTrue(client.isReady(), "夹具：鉴权成功且镜像 READY ⇒ placeOrder 可下发");

        PlaceOrderCmd cmd = new PlaceOrderCmd();
        cmd.clientOrderRef = "HG20261004000007";
        cmd.tradePlanId = 9001L;
        cmd.exposureId = 7001L;
        cmd.subAccountId = 101;
        cmd.productId = 55L;
        cmd.instrumentId = "cu2612";
        cmd.side = "BUY";
        cmd.offsetFlag = "OPEN";
        cmd.priceType = "LIMIT";
        cmd.price = "70000";
        cmd.volume = "10";
        cmd.operatorId = 1L;
        cmd.tradeReasonCode = "PLAN_EXECUTION";
        cmd.tradeReasonTimeDesc = "CONTRACT_SIGNED";
        cmd.tradeReasonText = "月度调仓";
        cmd.relatedPlanId = 9002L;
        cmd.relatedExposureId = 7002L;
        client.placeOrder(cmd);

        assertEquals(1, transport.sent.size(), "placeOrder 须恰好下发一帧命令");
        HgcpFrame.Decoded d = decode(transport.last());
        assertEquals(HgcpConstants.CMD_PLACE_ORDER, d.header.type,
                "帧类型须为 CMD_PLACE_ORDER（0x0101）");

        Hgcp.CmdPlaceOrder wire;
        try {
            wire = Hgcp.CmdPlaceOrder.parseFrom(d.payload);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError("出站 payload 须为合法 CmdPlaceOrder", e);
        }

        assertEquals(7001L, wire.getExposureId(), "字段 3 exposure_id 须逐字承载");
        assertEquals(9002L, wire.getRelatedPlanId(), "字段 19 related_plan_id 须逐字承载");
        assertEquals(7002L, wire.getRelatedExposureId(), "字段 20 related_exposure_id 须逐字承载");
        assertEquals(Hgcp.TradeReasonCode.TRADE_REASON_CODE_PLAN_EXECUTION, wire.getTradeReasonCode(),
                "字段 16 trade_reason_code 须映射为线格式枚举");
        assertEquals(Hgcp.TradeReasonTimeDesc.TRADE_REASON_TIME_DESC_CONTRACT_SIGNED,
                wire.getTradeReasonTimeDesc(), "字段 17 trade_reason_time_desc 须映射为线格式枚举");
        assertEquals("月度调仓", wire.getTradeReasonText(), "字段 18 trade_reason_text 须逐字承载");
        // 非空正面守卫：缺任一项即第②层结构性不可判（TE-T046-002 缺口本体）。
        assertNotEquals(0L, wire.getExposureId());
        assertNotEquals(0L, wire.getRelatedPlanId());
        assertNotEquals(0L, wire.getRelatedExposureId());
        assertNotEquals(Hgcp.TradeReasonCode.TRADE_REASON_CODE_UNSET, wire.getTradeReasonCode());
        assertNotEquals(Hgcp.TradeReasonTimeDesc.TRADE_REASON_TIME_DESC_UNSET,
                wire.getTradeReasonTimeDesc());
    }

    @Test
    @DisplayName("T-173：trade_reason_text 为 null ⇒ 线格式空串（proto3 标量不得 null）")
    void placeOrderNullReasonTextBecomesEmptyOnTheWire() {
        RecordingTransport transport = new RecordingTransport();
        HgcpClient client = authenticatedClient(transport);

        PlaceOrderCmd cmd = new PlaceOrderCmd();
        cmd.clientOrderRef = "HG20261004000008";
        cmd.tradePlanId = 9001L;
        cmd.subAccountId = 101;
        cmd.productId = 55L;
        cmd.instrumentId = "cu2612";
        cmd.side = "BUY";
        cmd.offsetFlag = "OPEN";
        cmd.priceType = "LIMIT";
        cmd.price = "70000";
        cmd.volume = "10";
        cmd.operatorId = 1L;
        cmd.relatedPlanId = 9002L;
        cmd.relatedExposureId = 7002L;
        client.placeOrder(cmd);

        Hgcp.CmdPlaceOrder wire;
        try {
            wire = Hgcp.CmdPlaceOrder.parseFrom(decode(transport.last()).payload);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError("出站 payload 须为合法 CmdPlaceOrder", e);
        }
        assertEquals("", wire.getTradeReasonText(), "null ⇒ 空串（不得触发 NPE）");
    }

    // ==================== TE-T042-003：事件幂等去重 ====================

    @Test
    @DisplayName("T-166/TE-T042-003：同一 (instrument#subAccount, event_seq) 重复事件 ⇒ 第二次被丢弃")
    void duplicatePositionChangeEventIsDiscarded() {
        HgcpClient client = newClient(new RecordingTransport());
        long seq = 100L;

        feed(client, HgcpConstants.EVT_POSITION_CHANGE, 0, seq, seq, positionChangePayload(7, "ru2601", 10));
        assertEquals(10, positionQty(client, 7, "ru2601"), "首条事件须已生效");
        assertEquals(seq, client.mirrorState().watermarkEventSeq(), "首条事件须推进水位");

        // 重传：业务键（instrument#subAccount = ru2601#7）与 event_seq 均相同 ⇒ 须整体丢弃
        feed(client, HgcpConstants.EVT_POSITION_CHANGE, 0, seq, seq, positionChangePayload(7, "ru2601", 20));

        assertEquals(10, positionQty(client, 7, "ru2601"),
                "重复事件须被幂等去重丢弃 ⇒ 镜像保持首次载荷"
                        + "（若 first(...) 重复分支返回 true，则第二次覆盖为 20 ⇒ 本断言必红）");
        assertEquals(seq, client.mirrorState().watermarkEventSeq(),
                "水位不得因被丢弃的重复事件而改变");
    }

    @Test
    @DisplayName("T-166/TE-T042-003：同一 (apply_id, event_seq) 的 EVT_FUND_RECEIPT 重复 ⇒ 第二次被丢弃")
    void duplicateFundReceiptEventIsDiscarded() {
        HgcpClient client = newClient(new RecordingTransport());
        long seq = 200L;

        feed(client, HgcpConstants.EVT_FUND_RECEIPT, 0, seq, seq, fundReceiptPayload("APPLY-1", "1000.0000"));
        assertEquals(1, client.mirrorState().snapshot().funds().size(), "首条资金事件须已生效");
        assertEquals("1000.0000", client.mirrorState().snapshot().funds().get(0).available);
        assertEquals(seq, client.mirrorState().watermarkEventSeq());

        // 重传：同一 apply_id + 同一 event_seq（载荷可区分，使「是否被丢弃」可观测）
        feed(client, HgcpConstants.EVT_FUND_RECEIPT, 0, seq, seq, fundReceiptPayload("APPLY-1", "9999.0000"));

        assertEquals(1, client.mirrorState().snapshot().funds().size(),
                "重复资金事件不得产生新的资金条目");
        assertEquals("1000.0000", client.mirrorState().snapshot().funds().get(0).available,
                "重复事件须被幂等去重丢弃 ⇒ 可用资金保持首次值"
                        + "（若去重被击穿，第二次覆盖为 9999.0000 ⇒ 本断言必红）");
        assertEquals(seq, client.mirrorState().watermarkEventSeq(),
                "水位不得因被丢弃的重复事件而改变");
    }

    @Test
    @DisplayName("T-166/TE-T042-003 正控：幂等键含 business_id ⇒ 同 event_seq 的不同业务主键各自生效（证明「丢弃」确由业务键匹配所致）")
    void distinctBusinessKeysWithSameSeqAreBothConsumed() {
        HgcpClient client = newClient(new RecordingTransport());
        long seq = 300L;

        feed(client, HgcpConstants.EVT_POSITION_CHANGE, 0, seq, seq, positionChangePayload(7, "ru2601", 10));
        feed(client, HgcpConstants.EVT_POSITION_CHANGE, 0, seq, seq, positionChangePayload(7, "ag2601", 20));

        assertEquals(10, positionQty(client, 7, "ru2601"), "业务键 A 须生效");
        assertEquals(20, positionQty(client, 7, "ag2601"), "业务键 B 须生效");
        assertEquals(2, client.mirrorState().snapshot().positions().size(),
                "不同 business_id + 相同 event_seq 不得互相去重（幂等键 = business_id + '|' + event_seq）");
        assertNotNull(client.mirrorState().snapshot());
    }

    // ==================== 夹具 ====================

    private static byte[] positionChangePayload(int subAccountId, String instrumentId, int qty) {
        Hgcp.EvtPositionChange evt = Hgcp.EvtPositionChange.newBuilder()
                .setSubAccountId(subAccountId)
                .setInstrumentId(instrumentId)
                .setDirection(Hgcp.Direction.DIRECTION_BUY)
                .setPositionType(Hgcp.PositionType.POSITION_TYPE_TODAY)
                .setPositionQty(qty)
                .setAvailableQty(qty)
                .setFrozenQty(0)
                .setAsOfTsMs(1_700_000_000_000L)
                .build();
        return evt.toByteArray();
    }

    private static byte[] fundReceiptPayload(String applyId, String amount) {
        Hgcp.EvtFundReceipt evt = Hgcp.EvtFundReceipt.newBuilder()
                .setIdempotencyKey(applyId)
                .setApplyId(applyId)
                .setResult(Hgcp.FundResult.FUND_RESULT_SUCCESS)
                .setAmount(amount)
                .setReceiptTsMs(1_700_000_000_000L)
                .build();
        return evt.toByteArray();
    }

    private static int positionQty(HgcpClient client, int subAccountId, String instrumentId) {
        for (PositionView v : client.mirrorState().snapshot().positions()) {
            if (v.subAccountId == subAccountId && instrumentId.equals(v.instrumentId)) {
                return Integer.parseInt(v.positionQty);
            }
        }
        return -1;
    }
}
