package com.hedgeguard.bridge;

import java.util.Collections;
import java.util.List;

/**
 * 只读镜像状态快照（{@code ARCHITECTURE.md} §3.10「镜像状态」/ §3.11 数据来源）。
 *
 * <p><b>不可变</b>：写侧单线程构建新实例并原子替换，读侧（HTTP 线程）取快照，
 * 无需重锁（{@code §2.4.4}）。{@code NFR-PERF}：不查库、不查 C++（{@code R-RISK-10}）。
 *
 * <p>镜像的权威源是 C++ 内存；本快照<b>只是缓存</b>，不得作为记账依据（{@code §2.5 铁律 4}）。
 */
public final class MirrorSnapshot {

    /** 组件就绪状态。 */
    public final ReplayProgress.State readyState;

    /** 已消费事件流的最后序号（水位；补数判据）。 */
    public final long watermarkEventSeq;

    /** 已生效的风控参数版本。 */
    public final long paramVersion;

    /** 快照构建时间（ISO-8601）。 */
    public final String takenAt;

    private final List<PositionView> positions;
    private final List<FundView> funds;

    /**
     * 构造。
     *
     * @param readyState        组件状态
     * @param watermarkEventSeq 事件流水位
     * @param paramVersion      参数版本
     * @param takenAt           快照时间
     * @param positions         持仓视图（按子账户 + 合约）
     * @param funds             资金视图（按子账户）
     */
    public MirrorSnapshot(ReplayProgress.State readyState, long watermarkEventSeq, long paramVersion,
                          String takenAt, List<PositionView> positions, List<FundView> funds) {
        this.readyState = readyState;
        this.watermarkEventSeq = watermarkEventSeq;
        this.paramVersion = paramVersion;
        this.takenAt = takenAt;
        this.positions = Collections.unmodifiableList(new java.util.ArrayList<>(positions));
        this.funds = Collections.unmodifiableList(new java.util.ArrayList<>(funds));
    }

    /** 空快照（未就绪态）。 */
    public static MirrorSnapshot empty() {
        return new MirrorSnapshot(ReplayProgress.State.NOT_READY, 0L, 0L, null,
                Collections.emptyList(), Collections.emptyList());
    }

    /** 持仓视图（只读）。 */
    public List<PositionView> positions() {
        return positions;
    }

    /** 资金视图（只读）。 */
    public List<FundView> funds() {
        return funds;
    }
}
