package com.hedgeguard.dao.entity;

import java.time.LocalDate;

/**
 * {@code TB-AUTH-8 t_user_sub_account_perm} 子账户数据权限（{@code DATA_MODEL.md} §2.2）。
 *
 * <p><b>安全默认</b>：本表无记录 = 该用户看不到任何子账户数据。公共数据权限过滤器在查不到授权行时
 * 注入恒假条件（返回空集），<b>不得</b>跳过过滤（跳过 = 全可见，正是 {@code NFR-SEC-7} 严禁的默认全可见）。
 */
public final class UserSubAccountPerm extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 用户。 */
    public long userId;

    /** 被授权的子账户（语义为"被授权的子账户"，非数据归属）。 */
    public int subAccountId;

    /** {@code LIMITED}（限定子账户，可读写）/ {@code CROSS_READONLY}（跨子账户只读）。 */
    public String permType;

    /** 授予人。 */
    public long grantedBy;

    /** 复核人（须 {@code != grantedBy}）。 */
    public Long reviewerId;

    /** 生效起始日。 */
    public LocalDate effectiveFrom;

    /** 生效截止日。 */
    public LocalDate effectiveTo;
}
