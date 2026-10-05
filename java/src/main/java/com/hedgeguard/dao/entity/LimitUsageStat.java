package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-RISK-9 t_limit_usage_stat} 限额使用统计（{@code FR-RISK-18}；{@code DATA_MODEL.md} §2.8）。
 *
 * <p>按<b>子账户 × 品种</b>展示限额使用率并标注接近红线者：使用率 {@code >= 80%} 提示、
 * {@code >= 95%} 警告（阈值经 {@code CFG-21b risk.limit_usage_hint/_warn} 配置）。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class LimitUsageStat extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（{@code NOT NULL}）。 */
    public Integer subAccountId;

    /** 品种。 */
    public Long productId;

    /** 统计日期。 */
    public LocalDate statDate;

    /** 限额类型：{@code PRODUCT_QUOTA} / {@code POSITION_LIMIT} / {@code SCALE_REDLINE} / {@code FUND_QUOTA} / {@code AUTH_QUOTA}。 */
    public String limitType;

    /** 限额值。 */
    public BigDecimal limitValue;

    /** 已使用值。 */
    public BigDecimal usedValue;

    /** 使用率（{@code usedValue / limitValue}）。 */
    public BigDecimal usageRatio;

    /** 提示级别：{@code NORMAL} / {@code HINT}（{@code >=80%}）/ {@code WARNING}（{@code >=95%}）/ {@code EXCEEDED}（{@code >=100%}）。 */
    public String hintLevel;

    /** 提示线快照（{@code >=} 该值标 {@code HINT}）。 */
    public BigDecimal hintThreshold1;

    /** 警告线快照（{@code >=} 该值标 {@code WARNING}）。 */
    public BigDecimal hintThreshold2;
}
