package com.hedgeguard.dao.entity;

/**
 * {@code TB-PRODUCT-5 t_sub_product_whitelist} 子账户品种白名单（下单前置校验）（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SubProductWhitelist extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 品种。 */
    public Long productId;

    /** 品种级分类覆盖（Q-2 已确认允许）：COMMODITY / TRADE；NULL = 继承子账户分类（t_sub_account.category）。 */
    public String categoryOverride;

    /** ENABLED / DISABLED（移出白名单 = 置 DISABLED，保留历史）。 */
    public String status;

    /** 下发 C++ 状态。 */
    public String dispatchStatus;

    /** 下发所用 param_version。 */
    public Long dispatchVersion;

    /** 移出原因。 */
    public String disabledReason;
}
