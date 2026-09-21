package com.hedgeguard.dao.entity;

/**
 * {@code TB-CONFIG-6 t_report_period_config} 报告周期配置（{@code DATA_MODEL.md} §2.1）。
 *
 * <p>不变量：{@code isMandatory = 'Y'} 时 {@code isEnabled} 必须为 {@code 'Y'}——合规必需报告不得被关闭。
 */
public final class ReportPeriodConfig extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 报告类型。 */
    public String reportType;

    /** 周期单位：{@code DAY}/{@code MONTH}/{@code QUARTER}/{@code YEAR}。 */
    public String periodUnit;

    /** 周期数量。 */
    public int periodValue;

    /** 提前提醒天数。 */
    public int remindAdvanceDays;

    /** 提醒接收角色编码（逗号分隔）。 */
    public String remindRoleCodes;

    /** 是否合规必需。 */
    public String isMandatory;

    /** 是否启用。 */
    public String isEnabled;
}
