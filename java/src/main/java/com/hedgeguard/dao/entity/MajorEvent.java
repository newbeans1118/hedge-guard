package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUDIT-8 t_major_event} 重大事项（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-7}/{@code R-AUDIT-5}）。
 *
 * <p>重大事项的触发与专项报告关联。{@code status} 支持
 * {@code TRIGGERED} → {@code REPORT_GENERATED} → {@code SUBMITTED} 流转。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class MajorEvent extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 涉及子账户。 */
    public Integer subAccountId;

    /** 事项编号。 */
    public String eventNo;

    /** 事项类型：{@code AGG_INCONSISTENT_TIMEOUT} / {@code SCALE_REDLINE_HIT} / {@code EXCHANGE_INQUIRY} / {@code FORCE_CLOSE_HINT_UNDISPOSED} / {@code LOSS_THRESHOLD}。 */
    public String eventType;

    /** 触发来源（关联的对象标识）。 */
    public String triggerSource;

    /** 事项描述。 */
    public String eventDesc;

    /** 影响范围。 */
    public String impactScope;

    /** 处置措施。 */
    public String handlingMeasures;

    /** 整改计划。 */
    public String rectificationPlan;

    /** 状态：{@code TRIGGERED} / {@code REPORT_GENERATED} / {@code SUBMITTED}。 */
    public String status;

    /** 关联的专项报告。 */
    public Long auditReportId;

    /** 触发时间。 */
    public LocalDateTime triggeredTime;
}
