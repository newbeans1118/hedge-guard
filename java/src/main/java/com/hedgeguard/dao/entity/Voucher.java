package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-4 t_voucher} 会计凭证（头）（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>凭证必须按子账户出账；子账户标识缺失时拒绝生成凭证（{@code R-ACCT-5}）——由
 * {@code sub_account_id NOT NULL} 在数据库级保证。正式凭证不得修改，只能冲销（{@code voucher_no}
 * 不变 + {@code status} 流转）。凭证编号规则 {@code AV<期间><seq(5)>}（§3.5，应用层生成）。
 */
public final class Voucher extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（凭证类贯穿字段；NOT NULL 使缺标识拒绝生成凭证在结构上成立）。 */
    public Integer subAccountId;

    /** 凭证编号（全系统唯一，一经分配不得修改）。 */
    public String voucherNo;

    /** 会计期间。 */
    public String periodKey;

    /** 凭证日期。 */
    public LocalDate voucherDate;

    /** 凭证类型（含 {@code EXERCISE} / {@code EXPIRED_WORTHLESS}，v1.2 DC-031）。 */
    public String voucherType;

    /** 关联套保计划。 */
    public Long hedgePlanId;

    /** 关联套期关系。 */
    public Long hedgeRelationId;

    /** 关联结算单（追溯链：凭证 → 结算）。 */
    public Long settleCycleId;

    /** 关联实货敞口（追溯链终点/起点）。 */
    public Long exposureId;

    /** 状态：{@code DRAFT} / {@code PENDING_REVIEW} / {@code POSTED} / {@code REVERSED}。 */
    public String status;

    /** 借贷合计金额（借贷须平衡；跨行聚合由服务层校验，§4.4）。 */
    public BigDecimal totalAmount;

    /** 凭证生成人（不得为审核人，R-ACCT-6）。 */
    public Long generatedBy;

    /** 生成时间。 */
    public LocalDateTime generatedTime;

    /** 原凭证（冲销凭证时非空）。 */
    public Long reversalOfId;

    /** 被哪张凭证冲销。 */
    public Long reversedById;

    /** 冲销原因（必填，FR-ACCT-13）。 */
    public String reversalReason;

    /** 待外部复核标注（恒为 'Y'，R-ACCT-10）。 */
    public String pendingExternalReview;
}
