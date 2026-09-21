package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-CONFIG-1 t_sub_account} 子账户（{@code DATA_MODEL.md} §2.1）。
 *
 * <p>全系统的根维度；不可物理删除，仅可停用（{@code R-CONFIG-4}）。
 * <b>保留系统行 {@code id = 0}</b> 是账户级口径的作用域哨兵值载体（{@code DC-020}），非业务子账户。
 */
public final class SubAccount extends AuditedEntity {

    /** 主键（{@code INT IDENTITY}，业务主键——被 40+ 张表引用，故用小整型）。 */
    public int id;

    /** 子账户编码，全系统唯一且不可修改。 */
    public String subAccountCode;

    /** 子账户名称（同一真实账户内不得重复）。 */
    public String subAccountName;

    /** 业务线说明。 */
    public String businessLineDesc;

    /** {@code ENABLED} / {@code DISABLED}（无 {@code DELETED}）。 */
    public String status;

    /** 子账户分类：{@code COMMODITY}（90% 红线）/ {@code TRADE}（80% 红线）。 */
    public String category;

    /** 分类变更的财务负责人审批人。 */
    public Long categoryApprovedBy;

    /** 是否默认子账户（{@code 'Y'} / {@code 'N'}）。 */
    public String isDefault;

    /** 初始资金。 */
    public BigDecimal initialFund;

    /** 资金记账起始日。 */
    public LocalDate ledgerStartDate;

    /** 备注。 */
    public String remark;

    /** 停用原因（停用时必填）。 */
    public String disabledReason;

    /** 停用条件校验快照（JSON）。 */
    public String disablePrecheckJson;

    /** 导出归档批次标识。 */
    public String archiveExportId;
}
