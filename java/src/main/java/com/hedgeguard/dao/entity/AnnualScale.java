package com.hedgeguard.dao.entity;

import java.math.BigDecimal;

/**
 * {@code TB-RISK-11 t_annual_scale} 年度规模基数（品种 × 年度）（{@code DATA_MODEL.md} §2.8；{@code Q-1}）。
 *
 * <p><b>规模红线的分子与分母</b>：基数 = 年度现货敞口累计量（实物量口径），不用营业收入。
 * 混合表：{@code SUB_ACCOUNT} 行为 A 类（{@code subAccountId > 0}），{@code TOTAL_ACCOUNT} 行为 B 类（哨兵 {@code 0}），
 * 由 §4.3 具名 CHECK {@code ck_annual_scale_scope_sentinel} 在 DB 级分派。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class AnnualScale extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（{@code SUB_ACCOUNT} 时 {@code > 0}；{@code TOTAL_ACCOUNT} 时哨兵 {@code 0}）。 */
    public Integer subAccountId;

    /** 作用域类型：{@code SUB_ACCOUNT} / {@code TOTAL_ACCOUNT}。 */
    public String scopeType;

    /** 品种。 */
    public Long productId;

    /** 年度（如 2026）。 */
    public Integer scaleYear;

    /** 分母：该品种年度预计实物经营量（实物量口径）；缺失须阻断，不得默认 0。 */
    public BigDecimal expectedSpotBusinessQty;

    /** 分子：年度累计新开仓保值数量（权威源为 C++ 内存，本列为投影）。 */
    public BigDecimal accumulatedHedgeQty;

    /** 该品种生效分类：{@code COMMODITY}(90%) / {@code TRADE}(80%)。 */
    public String category;

    /** 适用红线比例（{@code 0.900000} / {@code 0.800000}）。 */
    public BigDecimal scaleRatioLimit;

    /** 使用率（{@code accumulatedHedgeQty / expectedSpotBusinessQty}，双算取严者）。 */
    public BigDecimal usageRatio;

    /** 下发 C++ 状态：{@code PENDING} / {@code SUCCESS} / {@code FAIL} / {@code UNCONFIRMED}。 */
    public String dispatchStatus;

    /** 下发 {@code param_version}。 */
    public Long dispatchVersion;
}
