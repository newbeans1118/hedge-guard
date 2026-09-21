package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SPOT-1 t_purchase_contract} 采购合同（买入方向实货敞口来源）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class PurchaseContract extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户归属（创建时必填，不可静默变更），R-SPOT-2。 */
    public Integer subAccountId;

    /** 合同编号（同一子账户内唯一，uk_purchase_contract_no）。 */
    public String contractNo;

    /** 现货品类（经 TB-PRODUCT-4 映射到品种；无映射时允许存草稿但不得提交为有效）。 */
    public String spotCategoryCode;

    /** 映射后的品种（无映射时为 NULL，并列入"未映射清单"）。 */
    public Long productId;

    /** 供应商名称（仅文本说明，不做供应商管理）。 */
    public String supplierName;

    /** 合同总量；必须 > 0。 */
    public BigDecimal totalQty;

    /** 已执行/已到货量。 */
    public BigDecimal executedQty;

    /** 计价方式（文本）。 */
    public String pricingMode;

    /** 合同起止日期（期限红线"实货合同期限"依据）。 */
    public LocalDate contractStartDate;

    /** —。 */
    public LocalDate contractEndDate;

    /** 敞口方向（采购侧默认 BUY）。 */
    public String exposureDirection;

    /** DRAFT / ACTIVE / CLOSED / VOID。 */
    public String status;

    /** 作废原因（作废时必填）。 */
    public String voidReason;

    /** 执行完毕时间。 */
    public LocalDateTime closedTime;
}
