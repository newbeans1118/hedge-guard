package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-PLAN-6 t_trade_plan} 交易计划（可执行下单任务；每笔指令必须绑定交易计划）（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class TradePlan extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（必须与套保计划一致），R-PLAN-7。 */
    public Integer subAccountId;

    /** 交易计划编号（全系统唯一）。 */
    public String tradePlanNo;

    /** 所属套保计划（追溯链：交易 → 计划，必填）。 */
    public Long hedgePlanId;

    /** 实货敞口 ID（随计划携带，供指令侧回溯）；FR-TRADE-1。 */
    public Long exposureId;

    /** 品种。 */
    public Long productId;

    /** 合约代码（必须属于该品种）。 */
    public String instrumentId;

    /** FUTURES / OPTION（期权与期货走同一机制，R-PLAN-11）。 */
    public String instrumentType;

    /** BUY / SELL（必须与套保计划方向一致）。 */
    public String direction;

    /** 该交易计划的数量；所有交易计划合计 ≤ 套保计划数量（FR-PLAN-14 验收 2）。 */
    public BigDecimal planQty;

    /** 已成交数量。 */
    public BigDecimal executedQty;

    /** 已下达指令量。 */
    public BigDecimal orderedQty;

    /** 执行期限。 */
    public LocalDate execDeadline;

    /** PENDING（待执行）/ EXECUTING（执行中）/ COMPLETED / CANCELLED。 */
    public String status;

    /** 期权策略（多腿共用一个策略 ID）。 */
    public Long strategyId;
}
