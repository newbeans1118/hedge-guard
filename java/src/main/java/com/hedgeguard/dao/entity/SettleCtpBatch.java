package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SETTLE-1 t_ctp_settle_batch} CTP 日终结算数据批次（幂等；数据未到达不得用推算替代）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleCtpBatch extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 来源标识（文件名/接口批次号）。 */
    public String sourceIdentifier;

    /** 接收时间。 */
    public LocalDateTime receivedTime;

    /** 持仓明细接收条数。 */
    public Integer positionCount;

    /** 资金明细接收条数。 */
    public Integer fundCount;

    /** 成交明细接收条数。 */
    public Integer tradeCount;

    /** 解析状态。 */
    public String parseStatus;

    /** 解析错误说明。 */
    public String parseError;

    /** 重试次数。 */
    public Integer retryCount;

    /** 最近重试时间。 */
    public LocalDateTime lastRetryTime;

    /** 是否已确认。 */
    public String isConfirmed;
}
