package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-PRODUCT-8 t_sub_instrument_quota} 限额层②（子账户 + 合约）（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SubInstrumentQuota extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 合约（层②的细分维度）。 */
    public String instrumentId;

    /** 品种（冗余，便于按品种聚合与展示）。 */
    public Long productId;

    /** 额度上限；必须 ≥ 0（ck_sub_instr_quota_nonneg）。 */
    public BigDecimal quotaLimit;

    /** 额度单位（LOT / TON）。 */
    public String quotaUnit;

    /** 已占用（非权威源，权威源为 C++ 内存）。 */
    public BigDecimal usedQty;

    /** 已冻结（下单未成交）。 */
    public BigDecimal frozenQty;

    /** 生效起始日。 */
    public LocalDate effectiveFrom;

    /** 生效截止日。 */
    public LocalDate effectiveTo;

    /** 下发 C++ 状态。 */
    public String dispatchStatus;

    /** 下发所用 param_version。 */
    public Long dispatchVersion;
}
