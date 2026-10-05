package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-RISK-6 t_abnormal_order_stat} 异常下单行为统计（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-14}）。
 *
 * <p>按时间窗统计开平仓频次与持仓周期，用于识别频繁开平仓（交易所红线）。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class AbnormalOrderStat extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 品种。 */
    public Long productId;

    /** 合约（可选维度）。 */
    public String instrumentId;

    /** 统计日期。 */
    public LocalDate statDate;

    /** 时间窗（分钟；{@code CFG-05}，默认 1440=1 日，7 日滚动=10080）。 */
    public Integer windowMinutes;

    /** 时间窗内开平仓次数。 */
    public int openCloseCount;

    /** 最短持仓周期（分钟）。 */
    public Integer minHoldMinutes;

    /** 平均持仓周期（分钟）。 */
    public BigDecimal avgHoldMinutes;

    /** 回合数（一次开+平记 1；计数类无未知语义，无回合即为 0）。 */
    public int roundTripCount;

    /** 最近下单时间。 */
    public LocalDateTime lastOrderTime;

    /** 是否判定为异常：{@code Y} / {@code N}。 */
    public String isAbnormal;

    /** 触发的动作：{@code ALERT_ONLY} / {@code ALERT_AND_SUSPEND_OPEN}。 */
    public String actionTaken;

    /** 涉嫌频繁交易的指令 ID 清单（含交易理由字段供自证）。 */
    public String orderIds;
}
