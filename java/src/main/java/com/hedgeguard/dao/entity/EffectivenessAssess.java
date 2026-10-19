package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-2 t_effectiveness_assess} 有效性评估记录（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>评估记录<b>不得事后修改</b>，更正须新增记录并引用原记录（{@code corrected_from_id}，R-ACCT-2）。
 * {@code observer_*} 三列拆分存储使「三个观察点是否满足」可被索引与统计（TB-ACCT-2 备注）。
 */
public final class EffectivenessAssess extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 套期关系。 */
    public Long hedgeRelationId;

    /** 会计期间（如 {@code 2026-09}）。 */
    public String periodKey;

    /** 评估方法（定性经济关系，本期唯一方法）。 */
    public String assessMethod;

    /** 观察点①品种相关性（Q-7：Y=同品种强 / N=近相关，N 须填 linkage_note）。 */
    public String observerLinkage;

    /** 近相关时的书面经济关系说明（Q-7 要求）。 */
    public String linkageNote;

    /** 观察点②方向一致性（Q-7：Y=满足 / N=不满足）。 */
    public String observerDirection;

    /** 观察点③期限对应性（Q-7：Y=满足 / N=不满足；±3 个月）。 */
    public String observerTerm;

    /** 敞口预计实现日（期限对应性的判定基准）。 */
    public LocalDate exposureExpectedDate;

    /** 期限偏离天数（绝对值须 ≤ 90 天 = ±3 个月）。 */
    public Integer termDeviationDays;

    /** 量化监测指标（偏离 1 超 ±50% 提示复核；仅监测不判定）。 */
    public BigDecimal monitorDeviationRatio;

    /** 监测值超阈值提示复核标记。 */
    public String monitorReviewFlag;

    /** 各观察点判定数据与过程（结论须可复现，FR-ACCT-4 验收 3）。 */
    public String observerDetail;

    /** 结论：{@code EFFECTIVE} / {@code INEFFECTIVE} / {@code NEED_REBALANCE}。 */
    public String conclusion;

    /** 是否需终止或再平衡（结论为无效/需再平衡时为 'Y'）。 */
    public String needActionFlag;

    /** 明确标注"需终止或再平衡"（不得自动修改会计处理）。 */
    public String actionNote;

    /** 评估人。 */
    public Long assessorId;

    /** 评估时间。 */
    public LocalDateTime assessTime;

    /** 更正来源（评估记录不得事后修改，更正须新增记录并引用原记录）。 */
    public Long correctedFromId;

    /** 是否已被更正。 */
    public String isCorrected;

    /**
     * 待外部复核标注（恒 {@code 'Y'}，R-ACCT-10；v1.3.8/BUG-005/T-201 增列，
     * DB CHECK {@code ck_t_effectiveness_assess_pending_review} 结构保证不可移除）。
     */
    public String pendingExternalReview;
}
