package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-FUND-2 t_fund_flow} 资金流水（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class FundFlow extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（每条流水自带，可直接求和）。 */
    public Integer subAccountId;

    /** 资金凭证编号，全系统唯一，一经分配不得修改。 */
    public String voucherNo;

    /** 记账日。 */
    public LocalDate ledgerDate;

    /** 流水类型（DEPOSIT/WITHDRAW/TRANSFER_OUT/TRANSFER_IN/PNL_CARRYOVER/COMMISSION/MARGIN_CHANGE/DIFFERENCE_ADJUST）。 */
    public String flowType;

    /** 金额（正数；方向由 flow_type 决定）。 */
    public BigDecimal amount;

    /** IN / OUT（该流水对子账户余额的影响方向）。 */
    public String direction;

    /** 该笔之后的子账户余额（逐笔验证期初+发生额=期末）。 */
    public BigDecimal balanceAfter;

    /** 子账户划拨单号（划拨两条流水共用同一单号）。 */
    public Long transferApplyId;

    /** 关联的出入金申请。 */
    public Long depositWithdrawId;

    /** 关联结算单（盈亏结转与手续费来源）。 */
    public Long settleCycleId;

    /** 关联成交（手续费）。 */
    public Long tradeFillId;

    /** 对方子账户（划拨流水）。 */
    public Integer counterpartSubAccountId;

    /** 操作人。 */
    public Long operatorId;

    /** 发生时间。 */
    public LocalDateTime flowTime;

    /** 摘要。 */
    public String remark;

    /** 是否须双人复核（DIFFERENCE_ADJUST 恒为 Y）。 */
    public String doubleReviewRequired;

}
