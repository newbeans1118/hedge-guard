package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-PLAN-1 t_hedge_plan} 套保计划（经审批的套期保值方案）（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class HedgePlan extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 计划所属子账户（必填，不可更改），R-PLAN-7。 */
    public Integer subAccountId;

    /** 计划编号，全系统唯一，一经分配不得修改（被交易指令绑定）。 */
    public String planNo;

    /** COMMODITY（商品类套保 → 90% 红线）/ TRADE（贸易类套保 → 80% 红线）。 */
    public String planType;

    /** 品种。 */
    public Long productId;

    /** BUY_HEDGE（买入套保，对冲卖出敞口）/ SELL_HEDGE（卖出套保，对冲买入敞口）；必须与绑定敞口方向相反。 */
    public String hedgeDirection;

    /** 计划数量（与敞口同口径）；必须 > 0 且 ≤ 绑定敞口的未覆盖数量（应用层校验）。 */
    public BigDecimal planQty;

    /** 单位。 */
    public String unit;

    /** 计划期限起。 */
    public LocalDate termStartDate;

    /** 计划期限止；term_end_date ≤ term_start_date + 12 个月（或 ≤ 实货合同期限，R-RISK-6、CFG-03）。 */
    public LocalDate termEndDate;

    /** 套保比率（plan_qty / 敞口数量）；必须 ∈ (0, 1]（ck_hedge_plan_ratio）。 */
    public BigDecimal hedgeRatio;

    /** SIMPLE（简单比率法）/ MIN_VARIANCE（最小方差法）/ CUSTOM_RULE（自定义规则法）。 */
    public String ratioMethod;

    /** 比率计算基准日。 */
    public LocalDate calcBaseDate;

    /** DRAFT / PENDING_APPROVAL / APPROVED / FROZEN / COMPLETED / TERMINATED。 */
    public String status;

    /** 已成交数量（由成交回报自动回写，不得手工填写，FR-PLAN-20）。 */
    public BigDecimal executedQty;

    /** 已下达指令量（剩余可下单量 = plan_qty − ordered_qty）。 */
    public BigDecimal orderedQty;

    /** 发起人（不得为审批人，R-PLAN-2）。 */
    public Long applicantId;

    /** 当前书面文件版本号（指向 TB-PLAN-4）。 */
    public Integer currentDocVersion;

    /** 冻结原因（冻结时必填）。 */
    public String freezeReason;

    /** 终止原因（终止时必填）。 */
    public String terminateReason;

    /** 展期依据（实货合同期限依据；R-PLAN-6、FR-PLAN-18）。 */
    public String extendBasis;

    /** 是否正在占用额度（仅 APPROVED/FROZEN 为 'Y'；R-PLAN-5）。 */
    public String quotaOccupied;
}
