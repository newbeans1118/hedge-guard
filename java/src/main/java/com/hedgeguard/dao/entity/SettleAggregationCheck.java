package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SETTLE-11 t_aggregation_check} 加总对账记录（σ 子账户 = 真实账户；阻断状态唯一载体）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleAggregationCheck extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 对账类型。 */
    public String checkType;

    /** σ 子账户。 */
    public BigDecimal sumSubAccount;

    /** 真实期货账户对应值。 */
    public BigDecimal realAccountValue;

    /** 差额。 */
    public BigDecimal difference;

    /** 对账结论。 */
    public String result;

    /** 可能来源子账户清单（展示缓存，禁作判定来源）。 */
    public String possibleSubAccounts;

    /** 阻断范围（CFG-18）。 */
    public String blockScope;

    /** 阻断生效时间。 */
    public LocalDateTime blockEffectiveTime;

    /** 告警可见时间。 */
    public LocalDateTime alertTime;

    /** 校验执行时间。 */
    public LocalDateTime checkTime;

    /** 阻断解除时间。 */
    public LocalDateTime closedTime;
}
