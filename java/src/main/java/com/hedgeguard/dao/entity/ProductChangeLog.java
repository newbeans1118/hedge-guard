package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-PRODUCT-7 t_product_change_log} 品种变更历史（只增不改不删）（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class ProductChangeLog extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 品种。 */
    public Long productId;

    /** CREATE / UPDATE / ENABLE / DISABLE。 */
    public String changeType;

    /** 变更字段名（UPDATE 时非空）。 */
    public String changedField;

    /** 变更前值。 */
    public String beforeValue;

    /** 变更后值。 */
    public String afterValue;

    /** 涉及子账户（白名单/额度变更时非空；品种档案本体变更时为 NULL）。 */
    public Integer subAccountId;

    /** 操作人。 */
    public Long operatorId;

    /** 变更时间。 */
    public LocalDateTime changeTime;

    /** 变更/停用原因。 */
    public String changeReason;
}
