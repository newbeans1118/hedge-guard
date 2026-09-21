package com.hedgeguard.bridge;

import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TradingBridge} 契约与内存桩测试（{@code ARCHITECTURE.md} §3.10 / §8.4 {@code NFR-TEST-4}）。
 *
 * <p>同时校验"接口面"：{@code TradingBridge} <b>只有</b>
 * {@code placeOrder/confirmOrder/cancelOrder/dispatch/isReady/replayProgress/mirror} 七项，
 * <b>不得</b>出现 {@code preCheck}（{@code §3.0.3} 越界诱因消除）。
 */
class TradingBridgeContractTest {

    @Test
    @DisplayName("接口面冻结：方法集合恰为 §3.10 所列七项，不含 preCheck")
    void interfaceSurfaceMatchesSpec() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (java.lang.reflect.Method m : TradingBridge.class.getDeclaredMethods()) {
            names.add(m.getName());
        }
        assertEquals(new java.util.TreeSet<>(java.util.Arrays.asList(
                "placeOrder", "confirmOrder", "cancelOrder", "dispatch", "isReady",
                "replayProgress", "mirror")), names);
        assertFalse(names.contains("preCheck"), "Java 侧不得提供事前校验接口（§3.0.3）");
    }

    @Test
    @DisplayName("下单返回受理结果与单调递增指令号；幂等键原样回显")
    void placeOrderAcceptsAndReturnsOrderId() throws Exception {
        InMemoryTradingBridge bridge = new InMemoryTradingBridge();
        PlaceOrderCmd cmd = cmd("HG20260920000042");

        PlaceOrderResult r1 = bridge.placeOrder(cmd).get();
        PlaceOrderResult r2 = bridge.placeOrder(cmd("HG20260920000043")).get();

        assertTrue(r1.accepted);
        assertEquals("HG20260920000042", r1.clientOrderRef);
        assertEquals(0, r1.componentCode);
        assertTrue(r2.orderId > r1.orderId, "指令号须单调递增");
        assertEquals(2, bridge.placedOrders().size());
    }

    @Test
    @DisplayName("组件未就绪时拒绝下单并回 0x0401（R-TRADE-13）")
    void rejectsWhenNotReady() throws Exception {
        InMemoryTradingBridge bridge = new InMemoryTradingBridge();
        bridge.setReady(false);

        PlaceOrderResult r = bridge.placeOrder(cmd("HG1")).get();
        assertFalse(r.accepted);
        assertEquals(0x0401, r.componentCode);
        assertTrue(r.systemNotReady);
        assertEquals("R-TRADE-13", r.ruleId);
        assertFalse(bridge.isReady());
        assertEquals(ReplayProgress.State.NOT_READY, bridge.replayProgress().state);
    }

    @Test
    @DisplayName("脚本化拒绝：可注入组件错误码与四项（用于验证阻断类错误链路）")
    void scriptedRejectionCarriesComponentCodeAndFields() throws Exception {
        InMemoryTradingBridge bridge = new InMemoryTradingBridge();
        PlaceOrderResult rejection = new PlaceOrderResult();
        rejection.accepted = false;
        rejection.componentCode = 0x1004;
        rejection.limitLayer = "SUB_PRODUCT";
        rejection.ruleId = "R-RISK-2";
        rejection.current = "120.0000";
        rejection.threshold = "100.0000";
        rejection.nextStep = "请申请额度调整或减少数量";
        rejection.message = "限额层① 子账户+品种额度不足";
        bridge.rejectWith(rejection);

        PlaceOrderResult r = bridge.placeOrder(cmd("HG2")).get();
        assertFalse(r.accepted);
        assertEquals(0x1004, r.componentCode);
        assertEquals("SUB_PRODUCT", r.limitLayer);
        assertEquals("R-RISK-2", r.ruleId);
        assertEquals("120.0000", r.current);
        assertEquals("100.0000", r.threshold);
        assertEquals(0, bridge.placedOrders().size(), "被拒指令不得进入受理列表");
        assertTrue(bridge.placeOrder(cmd("HG3")).get().accepted, "一次性脚本拒绝后恢复正常");
    }

    @Test
    @DisplayName("参数下发：回执携带 param_version；未回执时 acknowledged=false（10014 判据）")
    void dispatchAckSemantics() throws Exception {
        InMemoryTradingBridge bridge = new InMemoryTradingBridge();
        ParamChange change = new ParamChange(ParamChange.Type.CMD_UPDATE_QUOTA, 7L, 9001L)
                .with("limit_layer", "SUB_PRODUCT")
                .with("sub_account_id", "1");

        AckResult ok = bridge.dispatch(change).get();
        assertTrue(ok.acknowledged);
        assertEquals(7L, ok.paramVersion);
        assertEquals(1, bridge.dispatchedChanges().size());
        assertEquals(2, change.payloadView().size());
        assertEquals(ParamChange.Type.CMD_UPDATE_QUOTA, bridge.dispatchedChanges().get(0).type);

        bridge.setDispatchAcknowledged(false);
        AckResult fail = bridge.dispatch(change).get();
        assertFalse(fail.acknowledged, "无回执不得视为已生效（R-CONFIG-3）");
        assertEquals(0x0401, fail.componentCode);
    }

    @Test
    @DisplayName("镜像状态：默认空快照，可原子替换；快照不可变")
    void mirrorIsImmutableSnapshot() {
        InMemoryTradingBridge bridge = new InMemoryTradingBridge();
        assertEquals(ReplayProgress.State.NOT_READY, bridge.mirror().readyState);
        assertTrue(bridge.mirror().positions().isEmpty());

        PositionView p = new PositionView();
        p.subAccountId = 1;
        p.instrumentId = "ru2601";
        p.positionQty = "10";
        FundView f = new FundView();
        f.subAccountId = 1;
        f.available = "500000.0000";
        MirrorSnapshot snap = new MirrorSnapshot(ReplayProgress.State.READY, 12345L, 9L,
                "2026-09-20T10:00:00", java.util.Collections.singletonList(p),
                java.util.Collections.singletonList(f));
        bridge.setMirror(snap);

        assertEquals(12345L, bridge.mirror().watermarkEventSeq);
        assertEquals("ru2601", bridge.mirror().positions().get(0).instrumentId);
        assertEquals("500000.0000", bridge.mirror().funds().get(0).available);
        assertEquals(9L, bridge.mirror().paramVersion);
    }

    @Test
    @DisplayName("空快照工厂不返回 null 集合（避免调用方 NPE）")
    void emptySnapshotIsSafe() {
        MirrorSnapshot empty = MirrorSnapshot.empty();
        assertTrue(empty.positions().isEmpty());
        assertTrue(empty.funds().isEmpty());
        assertEquals(0L, empty.watermarkEventSeq);
    }

    @Test
    @DisplayName("确认/撤单返回回执（含 ackTime）")
    void confirmAndCancelReturnAck() throws Exception {
        InMemoryTradingBridge bridge = new InMemoryTradingBridge();
        CompletableFuture<AckResult> c = bridge.confirmOrder(100001L, "zhangsan");
        assertTrue(c.get().acknowledged);
        assertEquals(0, c.get().componentCode);
        assertEquals(19, c.get().ackTime.length(), "ackTime 应为 yyyy-MM-ddTHH:mm:ss（§4.1.1 时间格式）");
        assertTrue(bridge.cancelOrder(100001L, "客户撤单").get().acknowledged);
    }

    private static PlaceOrderCmd cmd(String ref) {
        PlaceOrderCmd cmd = new PlaceOrderCmd();
        cmd.subAccountId = 1;
        cmd.tradePlanId = 100L;
        cmd.productId = 7L;
        cmd.instrumentId = "ru2601";
        cmd.side = "BUY";
        cmd.offsetFlag = "OPEN";
        cmd.priceType = "LIMIT";
        cmd.price = "14500.000000";
        cmd.volume = "5";
        cmd.clientOrderRef = ref;
        cmd.tradeReasonId = 33L;
        cmd.operatorId = 9001L;
        return cmd;
    }
}
