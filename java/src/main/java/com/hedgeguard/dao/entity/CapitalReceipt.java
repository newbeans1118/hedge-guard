package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-FUND-5 t_capital_receipt} 资金回执（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class CapitalReceipt extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 记账归属子账户。 */
    public Integer subAccountId;

    /** 幂等标识（重复回执须幂等处理）。 */
    public String idempotencyKey;

    /** 关联申请。 */
    public Long applyId;

    /** SUCCESS / FAIL / PROCESSING / UNKNOWN（超时待查）。 */
    public String result;

    /** 回执金额。 */
    public BigDecimal amount;

    /** CTP 错误号。 */
    public Integer ctpErrorId;

    /** 失败原因（须记录，且失败不得记账）。 */
    public String ctpErrorMsg;

    /** 回执毫秒时间戳（C++ 侧）。 */
    public Long receiptTsMs;

    /** 落库时间。 */
    public LocalDateTime receiptTime;

    /** 是否已据此记账（SUCCESS 且 booked=N → 待记账；FAIL 时恒为 N）。 */
    public String booked;

    /** 是否为主动查询确认得到的结果。 */
    public String queryConfirmed;

}
