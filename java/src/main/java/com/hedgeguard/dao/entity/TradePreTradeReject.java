package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-TRADE-6 t_pre_trade_reject} 事前风控拒绝记录（合规证据，只增不改不删）（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class TradePreTradeReject extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 被拒指令引用（幂等键）。 */
    public String clientOrderRef;

    /** 指令要素摘要。 */
    public String orderDigest;

    /** 品种。 */
    public Long productId;

    /** 合约。 */
    public String instrumentId;

    /** 方向。 */
    public String direction;

    /** 数量。 */
    public Integer volume;

    /** 规则编号。 */
    public String ruleId;

    /** 错误码（0x10xx）。 */
    public String rejectCode;

    /** 命中的限额层级（5 值，可空）。 */
    public String limitLayer;

    /** 判定所用实货敞口快照 ID。 */
    public Long exposureSnapshotId;

    /** 判定所用实货敞口快照时点。 */
    public LocalDateTime exposureSnapshotTime;

    /** 当前值（无适用值填 "-"）。 */
    public String currentValue;

    /** 阈值（无适用值填 "-"）。 */
    public String thresholdValue;

    /** 判定所用的基准价快照。 */
    public BigDecimal basePriceUsed;

    /** 基准价来源（PREV_SETTLEMENT / NOT_APPLICABLE）。 */
    public String basePriceSource;

    /** 拒绝原因。 */
    public String rejectReason;

    /** 操作人。 */
    public Long operatorId;

    /** 拒绝毫秒时间戳（C++ 侧）。 */
    public Long rejectTsMs;

    /** 落库时间（Java 侧）。 */
    public LocalDateTime rejectTime;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 事件流序号。 */
    public Long eventSeq;

    /** 上报重试次数。 */
    public Integer reportedRetryCount;
}
