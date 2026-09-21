package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-SPOT-10 t_spot_change_log} 现货单据与归属变更留痕（含分量重算）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SpotChangeLog extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** PURCHASE_CONTRACT / SALES_CONTRACT / PRODUCTION_PLAN / WIP_RECORD / INVENTORY_LEDGER。 */
    public String sourceType;

    /** 单据 ID。 */
    public Long sourceId;

    /** CREATE / UPDATE / VOID / CLOSE / SUB_ACCOUNT_CHANGE / PRICE_OVERWRITE / COMPONENT_RECALC（分量重算留痕：#17 展示侧对敞口三分量重算、告警但不回写落库值时写入；须与 §3.1 COMPONENT_RECALC 行、§4.4 敞口三分量行、ARCHITECTURE.md §6.10 / CFG-17b 一致；DC-033）。 */
    public String changeType;

    /** 变更前子账户（归属变更时非空）。 */
    public Integer subAccountBefore;

    /** 变更后子账户。 */
    public Integer subAccountAfter;

    /** 变更字段名。 */
    public String changedField;

    /** 变更前值。 */
    public String beforeValue;

    /** 变更后值。 */
    public String afterValue;

    /** 当前子账户（便于按子账户检索；归属变更时取 sub_account_after）。可空：品种档案本体变更时为 NULL（S-5 补外键；可空外键在 NULL 时不参与引用完整性校验，见 §0.4）。 */
    public Integer subAccountId;

    /** 操作人。 */
    public Long operatorId;

    /** 变更/作废原因。 */
    public String changeReason;

    /** 变更时间。 */
    public LocalDateTime changeTime;

    /** 是否触发敞口重算（FR-SPOT-16 验收 1）。 */
    public String recalcTriggered;
}
