package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-PLAN-3 t_hedge_ratio_calc} 套保比率计算留痕（可被第三方复算）（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class HedgeRatioCalc extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 关联计划（计算时计划可能尚未创建，故可空）。 */
    public Long hedgePlanId;

    /** 关联敞口。 */
    public Long exposureId;

    /** SIMPLE / MIN_VARIANCE / CUSTOM_RULE。 */
    public String ratioMethod;

    /** 输入参数快照（拟套保数量、敞口数量、样本区间、自定义规则内容等）。 */
    public String inputJson;

    /** 输出比率。 */
    public BigDecimal outputRatio;

    /** 相关系数 ρ（最小方差法）。 */
    public BigDecimal rho;

    /** 现货价标准差 σ_S。 */
    public BigDecimal sigmaSpot;

    /** 期货价标准差 σ_F。 */
    public BigDecimal sigmaFutures;

    /** 样本数（交易日）；最小方差法须 ≥ HG.PLAN.MINVAR_MIN_SAMPLES（默认 30）。 */
    public Integer sampleCount;

    /** 样本区间。 */
    public LocalDate sampleStartDate;

    /** 样本区间。 */
    public LocalDate sampleEndDate;

    /** 命中的自定义规则（ratio_method='CUSTOM_RULE' 时必填，指向 TB-PLAN-8；Q-6/FR-PLAN-8，v1.2 DC-025）。 */
    public Long customRuleId;

    /** 自定义规则内容与来源说明（FR-PLAN-8 验收 2）；v1.2 起规则权威源为 TB-PLAN-8，本列仅作计算留痕的自由说明。 */
    public String customRuleDesc;

    /** 本次实际采用的比例上限（Q-6；v1.2 DC-025）：ratio_method='CUSTOM_RULE' 时非空，取自命中规则的 ratio_cap，用于复算（"当时用的上限是多少"）。 */
    public BigDecimal appliedRatioCap;

    /** 多套规则叠加时最终采用的那一条规则（v1.2 DC-025）：按 TB-PLAN-8 备注的确定性取一算法（ratio_cap 升序（取最严者）→ priority 升序 → id 升序）产生。 */
    public Long appliedRuleId;

    /** 命中规则条数（v1.2 DC-025）：>1 表示发生了多套规则叠加；与 applied_rule_id 共同构成可复算、可审计的留痕。 */
    public Integer hitRuleCount;

    /** SUCCESS / INSUFFICIENT_SAMPLE / SIGMA_FUTURES_ZERO / MISSING_PRICE / RATIO_OUT_OF_RANGE / RULE_MISSING / EXCEEDS_CUSTOM_CAP。 */
    public String resultCode;

    /** 失败详情（须列出缺失的品种与日期，不得静默跳过，FR-PLAN-7 验收 4）。 */
    public String failDetail;

    /** 操作人。 */
    public Long operatorId;

    /** 计算时点。 */
    public LocalDateTime calcTime;
}
