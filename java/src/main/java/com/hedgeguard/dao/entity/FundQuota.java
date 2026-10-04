package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-FUND-7 t_fund_quota} 资金额度（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class FundQuota extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（quota_scope=ACCOUNT 时为 NULL）。 */
    public Integer subAccountId;

    /** SUB_ACCOUNT / ACCOUNT。 */
    public String quotaScope;

    /** 额度值；必须 ≥ 0。 */
    public BigDecimal quotaValue;

    /** 生效起始日。 */
    public LocalDate effectiveFrom;

    /** 生效截止日。 */
    public LocalDate effectiveTo;

    /** 设置人。 */
    public Long operatorId;

    /** 变更原因。 */
    public String changeReason;

}
