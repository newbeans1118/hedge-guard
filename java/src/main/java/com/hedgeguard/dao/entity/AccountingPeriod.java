package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-6 t_accounting_period} 会计期间（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>全局期间定义（例外 E2，无子账户维度）。关账后不得在该期间产生新凭证（须下期调整）。
 */
public final class AccountingPeriod extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 期间键（如 {@code 2026-09}）。 */
    public String periodKey;

    /** 期间类型：{@code MONTH} / {@code QUARTER} / {@code YEAR}。 */
    public String periodType;

    /** 期间起。 */
    public LocalDate periodStart;

    /** 期间止。 */
    public LocalDate periodEnd;

    /** 状态：{@code OPEN}（开放）/ {@code CLOSED}（已关账）。 */
    public String status;

    /** 关账人。 */
    public Long closedBy;

    /** 关账时间。 */
    public LocalDateTime closedTime;

    /** 关账复核人（≠ closedBy，R-ACCT-8）。 */
    public Long closeReviewerId;

    /** 撤销关账操作人（须双人复核并留痕，FR-ACCT-12 验收 4）。 */
    public Long reopenedBy;

    /** 撤销关账时间。 */
    public LocalDateTime reopenedTime;

    /** 撤销关账原因。 */
    public String reopenReason;
}
