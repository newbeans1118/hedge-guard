package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SPOT-2 t_sales_contract} 销售合同/订单（卖出方向实货敞口来源）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SalesContract extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户归属（必填）。 */
    public Integer subAccountId;

    /** 合同编号（同一子账户内唯一）。 */
    public String contractNo;

    /** 现货品类。 */
    public String spotCategoryCode;

    /** 映射后的品种。 */
    public Long productId;

    /** 客户名称（文本说明，不做客户管理）。 */
    public String customerName;

    /** 合同总量；必须 > 0。 */
    public BigDecimal totalQty;

    /** 已发货量。 */
    public BigDecimal deliveredQty;

    /** 计价方式。 */
    public String pricingMode;

    /** 合同起止日期。 */
    public LocalDate contractStartDate;

    /** —。 */
    public LocalDate contractEndDate;

    /** 敞口方向（销售侧默认 SELL）。 */
    public String exposureDirection;

    /** 是否存在双向敞口（如定价未定部分）；'Y' 时须在 t_exposure 生成两条方向不同的敞口（FR-SPOT-5 验收 3）。 */
    public String hasDualDirection;

    /** 双向敞口下的买入方向数量。 */
    public BigDecimal buyDirectionQty;

    /** DRAFT / ACTIVE / CLOSED / VOID。 */
    public String status;

    /** 作废原因。 */
    public String voidReason;

    /** 执行完毕时间。 */
    public LocalDateTime closedTime;
}
