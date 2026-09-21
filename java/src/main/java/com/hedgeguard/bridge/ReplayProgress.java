package com.hedgeguard.bridge;

/**
 * C++ 组件启动重放进度（{@code ARCHITECTURE.md} §4.5 / §6.2）。
 *
 * <p>重放未完成期间 {@link TradingBridge#isReady()} 为 {@code false}，
 * 业务侧须拒绝一切新指令（{@code 0x0401}，{@code R-TRADE-13}）。
 */
public final class ReplayProgress {

    /** 组件状态。 */
    public enum State {

        /** 启动中：尚未开始重放。 */
        NOT_READY,

        /** 重放中。 */
        REPLAYING,

        /** 就绪：重放完成且风控参数已加载。 */
        READY,

        /** 正在停机。 */
        STOPPING
    }

    /** 当前状态。 */
    public State state = State.NOT_READY;

    /** 已重放流水条数。 */
    public long replayedCount;

    /** 待重放总数（未知时为 {@code -1}）。 */
    public long totalCount = -1L;

    /** 重放/事件流最后序号（补数判据）。 */
    public long lastSeq;
}
