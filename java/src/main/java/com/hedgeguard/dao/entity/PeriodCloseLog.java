package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-7 t_period_close_log} 关账留痕（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>只增不改不删（TB-ACCT-7 用途注记）。全局期间级操作（例外 E2，无子账户维度）。
 */
public final class PeriodCloseLog extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 期间。 */
    public String periodKey;

    /** 动作：{@code CLOSE}（关账）/ {@code REOPEN}（撤销关账）。 */
    public String action;

    /** 关账前置校验结果（① 全部凭证已审核/已冲销 ② 结算已完成 ③ 有效性评估已完成）。 */
    public String precheckResult;

    /** 操作人。 */
    public Long operatorId;

    /** 复核人（≠ operatorId，R-ACCT-8）。 */
    public Long reviewerId;

    /** 原因（撤销关账时必填）。 */
    public String reason;

    /** 操作时间。 */
    public LocalDateTime actionTime;
}
