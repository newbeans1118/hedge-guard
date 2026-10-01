package com.hedgeguard.bridge.stub;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.MirrorSnapshot;
import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.bridge.PlaceOrderCmd;
import com.hedgeguard.bridge.PlaceOrderResult;
import com.hedgeguard.bridge.ReplayProgress;
import com.hedgeguard.bridge.TradingBridge;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存桩（{@code ARCHITECTURE.md} §8.4 {@code NFR-TEST-2} / {@code §7.2.2}「测试打桩不引 Mockito」）。
 *
 * <p>用于：① L3 服务层单测（无需 C++ 与 DM8）；② 本里程碑的端到端自测。
 * 行为可脚本化：{@link #rejectWith} 让下一次下单按指定组件错误码被拒，用于验证阻断类错误链路。
 */
public final class InMemoryTradingBridge implements TradingBridge {

    private final AtomicLong orderSeq = new AtomicLong(100000L);
    private final List<PlaceOrderCmd> placedOrders = Collections.synchronizedList(new ArrayList<>());
    private final List<ParamChange> dispatched = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean ready = true;
    private volatile ReplayProgress progress = new ReplayProgress();
    private volatile MirrorSnapshot mirror = MirrorSnapshot.empty();
    private volatile boolean ackDispatch = true;
    private volatile PlaceOrderResult nextRejection;

    /** 构造（默认 READY，可下单）。 */
    public InMemoryTradingBridge() {
        progress.state = ReplayProgress.State.READY;
    }

    /**
     * 设置就绪状态。
     *
     * @param ready 是否就绪
     */
    public void setReady(boolean ready) {
        this.ready = ready;
        this.progress.state = ready ? ReplayProgress.State.READY : ReplayProgress.State.NOT_READY;
    }

    /**
     * 设置镜像快照。
     *
     * @param snapshot 快照
     */
    public void setMirror(MirrorSnapshot snapshot) {
        this.mirror = snapshot;
    }

    /**
     * 设置 {@code dispatch} 的回执结果（{@code false} 用于验证"下发未生效"链路）。
     *
     * @param acknowledged 是否回执成功
     */
    public void setDispatchAcknowledged(boolean acknowledged) {
        this.ackDispatch = acknowledged;
    }

    /**
     * 令下一次下单按下述结果被拒（验证阻断类错误映射）。
     *
     * @param rejection 拒绝结果
     */
    public void rejectWith(PlaceOrderResult rejection) {
        this.nextRejection = rejection;
    }

    /** 已受理的下单指令（测试断言用）。 */
    public List<PlaceOrderCmd> placedOrders() {
        return Collections.unmodifiableList(placedOrders);
    }

    /** 已下发的参数变更（测试断言用）。 */
    public List<ParamChange> dispatchedChanges() {
        return Collections.unmodifiableList(dispatched);
    }

    @Override
    public CompletableFuture<PlaceOrderResult> placeOrder(PlaceOrderCmd cmd) {
        PlaceOrderResult rejection = nextRejection;
        if (rejection != null) {
            nextRejection = null;
            return CompletableFuture.completedFuture(rejection);
        }
        PlaceOrderResult r = new PlaceOrderResult();
        if (!ready) {
            r.accepted = false;
            r.componentCode = 0x0401;
            r.systemNotReady = true;
            r.message = "系统未就绪：组件重放中（0x0401）";
            r.nextStep = "等待交易组件就绪后重试";
            r.ruleId = "R-TRADE-13";
            r.current = "NOT_READY";
            r.threshold = "READY";
            return CompletableFuture.completedFuture(r);
        }
        placedOrders.add(cmd);
        r.accepted = true;
        r.orderId = orderSeq.incrementAndGet();
        r.clientOrderRef = cmd.clientOrderRef;
        r.componentCode = 0;
        r.message = "OK";
        return CompletableFuture.completedFuture(r);
    }

    @Override
    public CompletableFuture<AckResult> confirmOrder(long orderId, long operatorId, String confirmToken) {
        return CompletableFuture.completedFuture(okAck());
    }

    @Override
    public CompletableFuture<AckResult> cancelOrder(long orderId, String reason, long operatorId) {
        return CompletableFuture.completedFuture(okAck());
    }

    @Override
    public CompletableFuture<AckResult> dispatch(ParamChange change) {
        dispatched.add(change);
        AckResult ack = new AckResult();
        ack.acknowledged = ackDispatch;
        ack.componentCode = ackDispatch ? 0 : 0x0401;
        ack.paramVersion = change.paramVersion;
        ack.message = ackDispatch ? "OK" : "无回执（组件未就绪）";
        ack.ackTime = com.hedgeguard.common.Clocks.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        return CompletableFuture.completedFuture(ack);
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public ReplayProgress replayProgress() {
        return progress;
    }

    @Override
    public MirrorSnapshot mirror() {
        return mirror;
    }

    private static AckResult okAck() {
        AckResult ack = new AckResult();
        ack.acknowledged = true;
        ack.componentCode = 0;
        ack.message = "OK";
        ack.ackTime = com.hedgeguard.common.Clocks.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        return ack;
    }
}
