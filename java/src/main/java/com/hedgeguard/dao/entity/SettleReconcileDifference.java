package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SETTLE-10 t_reconcile_difference} 核对差异（逐条定位与闭环；未闭环不得标记结算完成）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleReconcileDifference extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 差异涉及子账户（可定位时非空）。 */
    public Integer subAccountId;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 所属核对记录。 */
    public Long reconcileRecordId;

    /** 差异类型。 */
    public String differenceType;

    /** 差异种类。 */
    public String differenceKind;

    /** 定位键。 */
    public String bizKey;

    /** 不一致字段名。 */
    public String fieldName;

    /** 系统侧取值。 */
    public String systemValue;

    /** 期货公司侧取值。 */
    public String ctpValue;

    /** 数量差。 */
    public BigDecimal diffQty;

    /** 金额差。 */
    public BigDecimal diffAmount;

    /** 处理状态。 */
    public String handleStatus;

    /** 处理方式。 */
    public String handleMethod;

    /** 处理说明。 */
    public String handleDesc;

    /** 责任人。 */
    public Long ownerId;

    /** 处理人。 */
    public Long handlerId;

    /** 复核人。 */
    public Long reviewerId;

    /** 发现时间。 */
    public LocalDateTime foundTime;

    /** 闭环时间。 */
    public LocalDateTime closedTime;
}
