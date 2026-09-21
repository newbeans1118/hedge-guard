package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-PRODUCT-6 t_product_quota} 品种额度指标（层① 子账户×品种 / 层③ 账户×品种；严禁跨品种跨子账户串用）（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class ProductQuota extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（额度不可跨子账户挪用）；limit_layer='ACCOUNT_PRODUCT'（层③）时取哨兵值 0（= 账户级）（不再使用 NULL，CHECK 联动见 §4.3 DC-020）。 */
    public Integer subAccountId;

    /** 限额层级（Q-16 已确认三层，本表承载层①与层③）：SUB_PRODUCT（层①：子账户 + 品种，主维度）/ ACCOUNT_PRODUCT（层③：账户 + 品种，总账户防串用）；层②（子账户 + 合约）见 TB-PRODUCT-8；与 sub_account_id 双向联动见 §4.3 具名 CHECK ck_product_quota_layer_sentinel（ACCOUNT_PRODUCT ⇔ 0；SUB_PRODUCT ⇔ >0）。 */
    public String limitLayer;

    /** 品种（额度不可跨品种挪用）。 */
    public Long productId;

    /** 额度上限；必须 ≥ 0（ck_product_quota_nonneg；负数被拒，FR-PRODUCT-11 验收 4）。 */
    public BigDecimal quotaLimit;

    /** 额度单位（LOT / TON）。 */
    public String quotaUnit;

    /** 已占用（汇总口径；权威源为 C++ 内存，本列由事件流回写，供展示与事后核对）。 */
    public BigDecimal usedQty;

    /** 已冻结（下单未成交）。 */
    public BigDecimal frozenQty;

    /** 生效起始日。 */
    public LocalDate effectiveFrom;

    /** 生效截止日。 */
    public LocalDate effectiveTo;

    /** 下发 C++ 状态。 */
    public String dispatchStatus;

    /** 下发所用 param_version。 */
    public Long dispatchVersion;
}
