package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SETTLE-9 t_reconcile_record} 核对记录（成交/仓位/资金三类；记录本身即合规证据）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleReconcileRecord extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 涉及子账户（差异可定位时非空）。 */
    public Integer subAccountId;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 核对类型。 */
    public String reconcileType;

    /** 比对粒度。 */
    public String compareGranularity;

    /** 系统侧条数。 */
    public Integer systemCount;

    /** 期货公司侧条数。 */
    public Integer ctpCount;

    /** 核对结论。 */
    public String result;

    /** 差异条数。 */
    public Integer differenceCount;

    /** 核对人。 */
    public Long operatorId;

    /** 核对时间。 */
    public LocalDateTime reconcileTime;

    /** 备注。 */
    public String remark;
}
