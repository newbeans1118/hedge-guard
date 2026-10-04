package com.hedgeguard.dao.entity;

import java.math.BigDecimal;

/**
 * {@code TB-SETTLE-16 t_agg_diff_candidate} 加总差异候选归属（机判唯一取数来源，Q-11；只增不改）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleAggDiffCandidate extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 候选归属子账户（恒 > 0，不得为哨兵值 0）。 */
    public Integer subAccountId;

    /** 所属加总对账记录。 */
    public Long aggregationCheckId;

    /** 候选排序（1 = 最可能）。 */
    public Integer candidateRank;

    /** 该子账户差额贡献量。 */
    public BigDecimal diffAmount;

    /** 定位状态。 */
    public String resolutionStatus;

    /** 关联的差异记录（闭环）。 */
    public Long reconcileDifferenceId;

    /** 归属判据说明。 */
    public String evidenceDesc;
}
