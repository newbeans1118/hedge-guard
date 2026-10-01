package com.hedgeguard.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 镜像状态构建器（{@code ARCHITECTURE.md} §3.10「镜像状态」/ §3.11 数据来源）。
 *
 * <p><b>事件驱动、不轮询</b>（{@code PROJECT.md} §4.1 ⑥-d）：只由事件流的增量更新驱动，
 * <b>不主动查库、不主动查 C++</b>。
 *
 * <p>写侧（事件消费线程）单线程更新本对象；读侧经 {@link #snapshot()} 取<b>不可变快照</b>
 * （{@link MirrorSnapshot}），无需加锁（§2.4.4）。
 *
 * <p><b>镜像的权威源是 C++ 内存</b>，本对象<b>只是缓存</b>，不得作为记账依据（§2.5 铁律 4）。
 */
public final class MirrorState {

    private final Map<String, PositionView> positions = new LinkedHashMap<>();
    private final Map<Integer, FundView> funds = new LinkedHashMap<>();

    private volatile ReplayProgress.State readyState = ReplayProgress.State.NOT_READY;
    private volatile long watermarkEventSeq;
    private volatile long paramVersion;
    private volatile String takenAt;

    /**
     * 持仓表的键（子账户 + 合约）。
     *
     * @param subAccountId 子账户
     * @param instrumentId 合约
     * @return 键
     */
    public static String positionKey(int subAccountId, String instrumentId) {
        return subAccountId + "|" + instrumentId;
    }

    /**
     * 应用一条持仓变化事件（{@code EVT_POSITION_CHANGE}，覆盖式）。
     *
     * @param v 持仓视图（按子账户 + 合约覆盖）
     */
    public void applyPosition(PositionView v) {
        positions.put(positionKey(v.subAccountId, v.instrumentId), v);
        takenAt = java.time.Instant.now().toString();
    }

    /**
     * 应用一条资金事件（{@code EVT_FUND_RECEIPT}，覆盖式）。
     *
     * @param v 资金视图（按子账户覆盖）
     */
    public void applyFund(FundView v) {
        funds.put(v.subAccountId, v);
        takenAt = java.time.Instant.now().toString();
    }

    /**
     * 更新事件流水位（补数判据）。
     *
     * @param seq 事件序号
     */
    public void advanceWatermark(long seq) {
        if (seq > watermarkEventSeq) {
            watermarkEventSeq = seq;
        }
    }

    /**
     * 更新组件就绪状态。
     *
     * @param state 状态
     */
    public void setReadyState(ReplayProgress.State state) {
        this.readyState = state;
    }

    /**
     * 更新已生效参数版本。
     *
     * @param version 参数版本
     */
    public void setParamVersion(long version) {
        this.paramVersion = version;
    }

    /** 当前就绪状态。 */
    public ReplayProgress.State readyState() {
        return readyState;
    }

    /** 当前事件流水位。 */
    public long watermarkEventSeq() {
        return watermarkEventSeq;
    }

    /** 当前参数版本。 */
    public long paramVersion() {
        return paramVersion;
    }

    /**
     * 取不可变快照。
     *
     * @return 快照
     */
    public MirrorSnapshot snapshot() {
        return new MirrorSnapshot(readyState, watermarkEventSeq, paramVersion, takenAt,
                new java.util.ArrayList<>(positions.values()),
                new java.util.ArrayList<>(funds.values()));
    }

    /** 清空（重连后重建时调用）。 */
    public void reset() {
        positions.clear();
        funds.clear();
        watermarkEventSeq = 0L;
        paramVersion = 0L;
        readyState = ReplayProgress.State.NOT_READY;
        takenAt = null;
    }
}
