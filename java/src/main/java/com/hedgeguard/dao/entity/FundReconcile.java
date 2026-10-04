package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-FUND-9 t_fund_reconcile} 资金对账（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class FundReconcile extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（子账户级对账非空；账户级为 NULL）。 */
    public Integer subAccountId;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** SUB_ACCOUNT / ACCOUNT。 */
    public String reconcileLevel;

    /** 期初余额。 */
    public BigDecimal beginBalance;

    /** 当日发生额合计。 */
    public BigDecimal occurrenceTotal;

    /** 期末余额。 */
    public BigDecimal endBalance;

    /** 期初 + 发生额 = 期末 是否成立。 */
    public String balanceEquationOk;

    /** 加总比对值（账户级 = Σ 子账户；子账户级为 NULL）。 */
    public BigDecimal aggCompareValue;

    /** 真实账户对应值（账户级非空）。 */
    public BigDecimal realAccountValue;

    /** 加总是否一致。 */
    public String aggConsistent;

    /** CONSISTENT / INCONSISTENT / HAS_OPEN_DIFFERENCE。 */
    public String result;

    /** 与 SETTLE 的加总对账记录关联。 */
    public Long aggCheckId;

    /** 差异清单（不一致时非空，CLOB）。 */
    public String differenceList;

    /** 对账时间。 */
    public LocalDateTime reconcileTime;

    /** 对账人。 */
    public Long operatorId;

}
