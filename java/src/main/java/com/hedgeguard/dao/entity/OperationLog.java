package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUDIT-1 t_operation_log} 操作留痕（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-11}/{@code R-AUDIT-3}）。
 *
 * <p>系统内关键操作的记录（计划提交/审批/终止、指令提交/人工确认、风控拒绝、资金转账与划拨、
 * 权限与授权变更、系统配置变更、结算确认与差异处理、会计凭证审核）。<b>只增不改不删</b>。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class OperationLog extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户维度（追溯链须含子账户；账户级操作为 {@code null}）。 */
    public Integer subAccountId;

    /** 操作人。 */
    public long operatorId;

    /** 操作类型（如 {@code PLAN_SUBMIT} / {@code PLAN_APPROVE} / {@code ORDER_CONFIRM} 等，非封闭枚举）。 */
    public String operationType;

    /** 对象类型（表名或实体名）。 */
    public String objectType;

    /** 对象标识。 */
    public Long objectId;

    /** 对象业务编号（可读，便于审计检索）。 */
    public String objectNo;

    /** 操作结果：{@code SUCCESS} / {@code FAIL} / {@code REJECTED}。 */
    public String actionResult;

    /** 结果详情（失败原因、拒绝规则编号等）。 */
    public String resultDetail;

    /** 操作时间。 */
    public LocalDateTime operationTime;

    /** 客户端 IP。 */
    public String clientIp;

    /** 请求追踪 ID（与日志关联）。 */
    public String traceId;
}
