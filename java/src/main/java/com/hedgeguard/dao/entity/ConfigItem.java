package com.hedgeguard.dao.entity;

import java.math.BigDecimal;

/**
 * {@code TB-CONFIG-2 t_config_item} 配置项（{@code DATA_MODEL.md} §2.1）。
 *
 * <p>{@code ARCHITECTURE.md} §11 的 {@code CFG-*} 全部落在此类型的行；<b>敏感字段不入本表</b>：
 * {@code isSensitive = 'Y'} 时 {@code configValue} 必须为 {@code null}。
 */
public final class ConfigItem extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 配置项 Key（如 {@code HG.RISK.SCALE_BASE_MODE}）。 */
    public String configKey;

    /** 配置分类。 */
    public String configCategory;

    /** 配置值（敏感项必须为 {@code null}）。 */
    public String configValue;

    /** 值类型。 */
    public String valueType;

    /** {@code ENUM}/{@code LIST} 时的取值域（逗号分隔）。 */
    public String enumScope;

    /** 数值下界。 */
    public BigDecimal minValue;

    /** 数值上界。 */
    public BigDecimal maxValue;

    /** 默认值（须与需求文件明示口径一致）。 */
    public String defaultValue;

    /** 是否敏感字段（{@code 'Y'} / {@code 'N'}）。 */
    public String isSensitive;

    /** 敏感字段状态（{@code UNSET} / {@code SET}）。 */
    public String sensitiveState;

    /** 持久化属性（{@code LOCAL_ADMIN} / {@code API_DISPATCH} / {@code NEVER}）。 */
    public String persistScope;

    /** 是否需下发 C++ 组件。 */
    public String dispatchToCtp;

    /** 变更是否须双人复核。 */
    public String doubleReviewRequired;

    /** 关联待澄清编号。 */
    public String relatedQuestionNo;

    /** 生效版本（下发用 {@code param_version}）。 */
    public long effectiveVersion;

    /** 配置项说明。 */
    public String description;
}
