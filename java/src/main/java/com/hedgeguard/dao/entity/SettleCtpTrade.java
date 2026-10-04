package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-SETTLE-4 t_ctp_settle_trade} CTP 成交明细（成交核对基准，只读）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleCtpTrade extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 所属批次。 */
    public Long settleBatchId;

    /** CTP 成交编号。 */
    public String tradeId;

    /** 合约。 */
    public String instrumentId;

    /** 方向。 */
    public String direction;

    /** 开平。 */
    public String offsetFlag;

    /** 成交价。 */
    public BigDecimal fillPrice;

    /** 成交量。 */
    public Integer fillVolume;

    /** 成交时间戳。 */
    public Long tradeTsMs;

    /** 手续费。 */
    public BigDecimal commission;
}
