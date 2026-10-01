package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hgcp.v1.Hgcp;

/**
 * {@code T-167} —— {@code encode()} 补 {@code case CMD_UPDATE_PERMISSION}（消除软失败）。
 *
 * <p><b>缺陷本体</b>（编排者 + {@code te-verify-t042} 实测）：
 * {@link ParamChangeEncoder#encode} 的 {@code switch} 无
 * {@code case CMD_UPDATE_PERMISSION} ⇒ 落 {@code default} 抛
 * {@link IllegalArgumentException}；{@code HgcpClient.dispatch} 捕获该异常返回
 * {@code acknowledged=false} ⇒ <b>授权变更下发发不出去且不 fail-fast</b>（软失败空转），
 * {@code FR-AUTH-10} 验收 4「授权变更后 C++ 内存中的权限快照同步更新」静默不成立。
 *
 * <p><b>断言形态（承重要求）</b>：断言<b>实际下发到线上的字节</b>重解出的
 * {@code CmdUpdatePermission} 逐字段正确（含 {@code CH-019} 第 9 字段
 * {@code has_trading_post}，闭环复核席点名的存量盲区 {@code TE-CH019-001}）——
 * 把 {@code case} 删掉（回 {@code default}）⇒ 全部用例必红；
 * 把 {@code setHasTradingPost(...)} 改成常量 {@code false} ⇒ 含准入位断言必红。
 *
 * <p><b>诚实披露</b>：经公开接缝（{@link HgcpClient#dispatch} / 记录型
 * {@link HgcpTransport}）驱动，复用 {@code HgcpClientGuardTest} 的注入手法；
 * {@code HgcpClient.dispatch} 吞 {@link IllegalArgumentException} 转软失败是既有设计，
 * 本单只消除「进不去 encode」这一层，不改 {@code HgcpClient}。
 */
class ParamChangeEncoderPermissionRouteTest {

    // ==================== 接缝（复用 HgcpClientGuardTest 手法） ====================

    /** 记录出站整帧的内存传输桩。 */
    private static final class RecordingTransport implements HgcpTransport {
        final java.util.List<byte[]> sent = new java.util.ArrayList<>();

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
        public int read(byte[] buf) {
            return -1;
        }

        @Override
        public void close() {
        }
    }

    private static HgcpClient authenticatedClient(RecordingTransport t) throws Exception {
        HgcpClient c = new HgcpClient("java-test", "token-not-logged", "boot-1", 1, () -> t);
        java.lang.reflect.Field f = HgcpClient.class.getDeclaredField("transport");
        f.setAccessible(true);
        f.set(c, t);
        Hgcp.MsgHelloAck ack = Hgcp.MsgHelloAck.newBuilder()
                .setAccepted(true)
                .setServerProtoVersion(HgcpFrame.WIRE_VERSION)
                .setSessionId("sess-1")
                .setServerState(Hgcp.ServerState.SERVER_STATE_READY)
                .setParamVersion(3L)
                .build();
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = HgcpConstants.MSG_HELLO_ACK;
        h.flags = HgcpFrame.FLAG_IS_REPLY;
        h.seq = 1L;
        h.msgId = 1L;
        byte[] frame = HgcpFrame.encode(h, ack.toByteArray());
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        c.onFrame(d);
        assertEquals(HgcpClient.AuthState.AUTHENTICATED, c.authState(),
                "夹具：须为 AUTHENTICATED（否则命令无法下发，本类无守卫力）");
        return c;
    }

    private static HgcpFrame.Decoded decode(byte[] frame) {
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "出站帧须为可解完整 wire 帧");
        return d;
    }

    /** §3.2.1 冻结 9 键的两条目 payload（值两两可区分，防串行化错位自愈）。 */
    private static ParamChange permissionChange() {
        return new ParamChange(ParamChange.Type.CMD_UPDATE_PERMISSION, 41L, 9001L)
                .with("entries[0].operator_id", "1001")
                .with("entries[0].sub_account_id", "7")
                .with("entries[0].product_ids", "11,22")
                .with("entries[0].quota_limit", "500.0000")
                .with("entries[0].is_enterprise_leader", "true")
                .with("entries[0].is_management_role", "false")
                .with("entries[0].has_risk_role", "true")
                .with("entries[0].has_settle_role", "false")
                .with("entries[0].has_trading_post", "true")
                .with("entries[1].operator_id", "1002")
                .with("entries[1].sub_account_id", "8")
                .with("entries[1].product_ids", "33")
                .with("entries[1].is_enterprise_leader", "false")
                .with("entries[1].is_management_role", "true")
                .with("entries[1].has_risk_role", "false")
                .with("entries[1].has_settle_role", "true")
                .with("entries[1].has_trading_post", "false");
    }

    private static Hgcp.CmdUpdatePermission dispatchAndParse(ParamChange change,
            RecordingTransport t) throws Exception {
        HgcpClient client = authenticatedClient(t);
        client.dispatch(change);
        assertEquals(1, t.sent.size(),
                "dispatch 须恰好下发一帧命令（缺 case 时落 default 抛异常被吞 ⇒ 0 帧，缺陷本体）");
        HgcpFrame.Decoded d = decode(t.sent.get(0));
        assertEquals(HgcpConstants.CMD_UPDATE_PERMISSION, d.header.type,
                "帧类型须为 CMD_UPDATE_PERMISSION（0x0116）");
        try {
            return Hgcp.CmdUpdatePermission.parseFrom(d.payload);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError("出站 payload 须为合法 CmdUpdatePermission", e);
        }
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("T-167：dispatch(CMD_UPDATE_PERMISSION) 不再软失败，线上帧类型为 0x0116")
    void dispatchRoutesPermissionChangeToTheWire() throws Exception {
        RecordingTransport t = new RecordingTransport();
        Hgcp.CmdUpdatePermission cmd = dispatchAndParse(permissionChange(), t);
        assertEquals(41L, cmd.getParamVersion(), "param_version 须取 ParamChange.paramVersion");
        assertEquals(2, cmd.getEntriesCount(), "两条 entries[0]/entries[1] 须逐条编码");
    }

    @Test
    @DisplayName("T-167：9 个协议字段逐字段正确（含 CH-019 第 9 字段 has_trading_post，闭环 TE-CH019-001）")
    void wireCarriesAllNineProtocolFields() throws Exception {
        RecordingTransport t = new RecordingTransport();
        Hgcp.CmdUpdatePermission cmd = dispatchAndParse(permissionChange(), t);

        // 条目 0：准入类 true + 三禁止类混排
        Hgcp.PermissionEntry e0 = cmd.getEntries(0);
        assertEquals(1001L, e0.getOperatorId(), "operator_id 须逐字承载");
        assertEquals(7, e0.getSubAccountId(), "sub_account_id 须逐字承载");
        assertEquals(2, e0.getProductIdsCount(), "product_ids 逗号分隔须展开为列表");
        assertEquals(11, e0.getProductIds(0));
        assertEquals(22, e0.getProductIds(1));
        assertEquals("500.0000", e0.getQuotaLimit(), "quota_limit（选填）须承载");
        assertTrue(e0.getIsEnterpriseLeader(), "is_enterprise_leader=true 须承载");
        assertFalse(e0.getIsManagementRole(), "is_management_role=false 须承载（不得静默翻转）");
        assertTrue(e0.getHasRiskRole(), "has_risk_role=true 须承载");
        assertFalse(e0.getHasSettleRole(), "has_settle_role=false 须承载（不得静默翻转）");
        assertTrue(e0.getHasTradingPost(),
                "has_trading_post（CH-019 字段 9，准入类）=true 须承载（TE-CH019-001 闭环："
                        + "改前该字段永不落线）");

        // 条目 1：方向反转（防「恒 true/恒 false」假守卫）
        Hgcp.PermissionEntry e1 = cmd.getEntries(1);
        assertEquals(1002L, e1.getOperatorId());
        assertEquals(8, e1.getSubAccountId());
        assertEquals(1, e1.getProductIdsCount());
        assertEquals(33, e1.getProductIds(0));
        assertTrue(e1.getQuotaLimit().isEmpty(), "未给 quota_limit 的条目不得凭空造值");
        assertFalse(e1.getIsEnterpriseLeader());
        assertTrue(e1.getIsManagementRole());
        assertFalse(e1.getHasRiskRole());
        assertTrue(e1.getHasSettleRole());
        assertFalse(e1.getHasTradingPost(),
                "has_trading_post=false 与条目 0 的 true 构成双向证伪对（准入位方向敏感）");
        assertNotEquals(e0.getHasTradingPost(), e1.getHasTradingPost(),
                "两准入位取值须可区分（防串行化错位后写覆盖自愈）");
        assertNotEquals(e0.getOperatorId(), e1.getOperatorId(),
                "两 operator_id 须可区分（引擎侧按 operator_id 单键覆盖）");
    }

    @Test
    @DisplayName("T-167：缺必填键（operator_id）⇒ IllegalArgumentException，dispatch 回软失败且不下发")
    void missingRequiredKeyIsRejected() {
        ParamChange bad = new ParamChange(ParamChange.Type.CMD_UPDATE_PERMISSION, 42L, 9001L)
                .with("entries[0].sub_account_id", "7")
                .with("entries[0].product_ids", "11")
                .with("entries[0].is_enterprise_leader", "false")
                .with("entries[0].is_management_role", "false")
                .with("entries[0].has_risk_role", "false")
                .with("entries[0].has_settle_role", "false")
                .with("entries[0].has_trading_post", "false");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ParamChangeEncoder.encode(bad),
                "缺必填键 operator_id 须在编码层拒绝（HGC-1.6：不得静默缺省）");
        assertTrue(ex.getMessage().contains("operator_id"),
                "错误信息须点名缺失键：" + ex.getMessage());
    }

    @Test
    @DisplayName("T-167：布尔取值非 true/false ⇒ 拒绝（防静默当 false 扭转准入/禁止方向）")
    void nonBooleanValueIsRejected() {
        ParamChange bad = permissionChange();
        bad.payload.put("entries[0].has_trading_post", "yes");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ParamChangeEncoder.encode(bad));
        assertTrue(ex.getMessage().contains("true/false"),
                "错误信息须说明合法取值：" + ex.getMessage());
    }

    @Test
    @DisplayName("T-167：协议外键名（HGC-1.6）⇒ 拒绝，不得静默丢弃")
    void unknownKeyIsRejected() {
        ParamChange bad = permissionChange();
        bad.payload.put("entries[0].role_code", "TRADER");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ParamChangeEncoder.encode(bad));
        assertTrue(ex.getMessage().contains("role_code"),
                "错误信息须点名协议外键：" + ex.getMessage());
    }

    @Test
    @DisplayName("T-167：encodePermission(直喂 DTO) 与 payload 路由对同一取值编码结果逐字节一致")
    void payloadRouteAgreesWithDirectDtoEncoding() {
        ParamChangeEncoder.PermissionInput in = new ParamChangeEncoder.PermissionInput();
        in.operatorId = 1001L;
        in.subAccountId = 7;
        in.productIds = java.util.Arrays.asList(11, 22);
        in.quotaLimit = "500.0000";
        in.enterpriseLeader = true;
        in.managementRole = false;
        in.riskRole = true;
        in.settleRole = false;
        in.tradingPost = true;

        byte[] direct = ParamChangeEncoder.encodePermission(41L,
                java.util.Collections.singletonList(in));
        ParamChange single = new ParamChange(ParamChange.Type.CMD_UPDATE_PERMISSION, 41L, 9001L)
                .with("entries[0].operator_id", "1001")
                .with("entries[0].sub_account_id", "7")
                .with("entries[0].product_ids", "11,22")
                .with("entries[0].quota_limit", "500.0000")
                .with("entries[0].is_enterprise_leader", "true")
                .with("entries[0].is_management_role", "false")
                .with("entries[0].has_risk_role", "true")
                .with("entries[0].has_settle_role", "false")
                .with("entries[0].has_trading_post", "true");
        byte[] viaPayload = ParamChangeEncoder.encode(single);

        assertTrue(java.util.Arrays.equals(direct, viaPayload),
                "payload 反解析路径与 DTO 直喂路径须产出逐字节相同的 wire（同一协议唯一事实）");
    }
}
