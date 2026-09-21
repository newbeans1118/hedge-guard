package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code TB-AUTH-7 t_authorization} 授权（{@code DATA_MODEL.md} §2.2）。
 *
 * <p><b>人员名单 + 品种 + 额度</b>三者齐备（8 号文 四(二)）；事前风控权限校验的输入。
 * 不变量：{@code quotaLimit > 0}——"仅配置人员与品种、未配置额度时保存被拒绝"下沉到数据结构
 * （{@code ck_authorization_quota_positive}）。
 */
public final class Authorization extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 被授权人（人员名单）。 */
    public long userId;

    /** 授权适用的子账户（行级归属列，恒 {@code > 0}）。 */
    public int subAccountId;

    /** 授权品种（每品种一行，便于精确校验）。 */
    public long productId;

    /** 授权额度（该用户该子账户该品种的个人上限；须 {@code > 0}）。 */
    public BigDecimal quotaLimit;

    /** 生效起始日。 */
    public LocalDate effectiveFrom;

    /** 生效截止日（{@code null} = 长期）。 */
    public LocalDate effectiveTo;

    /** {@code DRAFT} / {@code PENDING_REVIEW} / {@code EFFECTIVE} / {@code EXPIRED} / {@code REJECTED}。 */
    public String status;

    /** 申请人。 */
    public long applicantId;

    /** 复核人（须 {@code != applicantId}）。 */
    public Long reviewerId;

    /** 下发 C++ 状态。 */
    public String dispatchStatus;

    /** 下发所用 {@code param_version}。 */
    public Long dispatchVersion;

    /** 驳回原因。 */
    public String rejectReason;
}
