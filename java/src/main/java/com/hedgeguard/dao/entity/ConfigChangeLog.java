package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-CONFIG-3 t_config_change_log} 配置变更留痕（{@code DATA_MODEL.md} §2.1）。
 *
 * <p><b>只增不改不删</b>：本类型<b>不含</b> {@code updatedBy} / {@code updatedTime} / {@code rowVersion}
 * ——其表定义显式排除这三列（{@code §2.1 TB-CONFIG-3 备注}），故不继承 {@link AuditedEntity}。
 */
public final class ConfigChangeLog {

    /** 主键。 */
    public long id;

    /** 配置项 Key。 */
    public String configKey;

    /** 冗余分类。 */
    public String configCategory;

    /** 变更前值；敏感字段恒为 {@code null}。 */
    public String beforeValue;

    /** 变更后值；敏感字段恒为 {@code null}。 */
    public String afterValue;

    /** 是否敏感字段变更。 */
    public String isSensitiveChange;

    /** 敏感字段变更前状态。 */
    public String sensitiveStateBefore;

    /** 敏感字段变更后状态。 */
    public String sensitiveStateAfter;

    /** 操作人。 */
    public long operatorId;

    /** 复核人（须双人复核项必填且 {@code != operatorId}）。 */
    public Long reviewerId;

    /** 变更原因。 */
    public String changeReason;

    /** 下发结果（{@code SUCCESS}/{@code FAIL}/{@code UNCONFIRMED}/{@code NOT_REQUIRED}）。 */
    public String dispatchResult;

    /** 下发所用 {@code param_version}。 */
    public Long dispatchParamVersion;

    /** 变更时间。 */
    public LocalDateTime changeTime;

    /** 创建人（{@code §0.3} 通用审计字段）。 */
    public long createdBy;

    /** 创建时间（{@code §0.3} 通用审计字段）。 */
    public LocalDateTime createdTime;
}
