package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-FUND-8 t_fund_quota_alert} 资金额度告警（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class FundQuotaAlert extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（账户级告警为 NULL）。 */
    public Integer subAccountId;

    /** SUB_ACCOUNT / ACCOUNT。 */
    public String quotaScope;

    /** 额度上限。 */
    public BigDecimal quotaValue;

    /** 当前占用。 */
    public BigDecimal occupiedValue;

    /** INFO / WARNING / CRITICAL（按 CFG-12）。 */
    public String alertLevel;

    /** 告警时间。 */
    public LocalDateTime alertTime;

    /** 恒为 NONE（告警不阻断）。 */
    public String blockedOperation;

    /** 是否已处置。 */
    public String disposed;

}
