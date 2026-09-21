package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUTH-4 t_user_role} 用户角色（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>岗位互斥校验的落点：互斥是跨行跨表语义，{@code CHECK} 无法表达，
 * 由服务层在插入前查 {@code t_position_conflict} 矩阵（{@code R-AUTH-3}）。
 */
public final class UserRole extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 用户。 */
    public long userId;

    /** 角色。 */
    public long roleId;

    /** 授予人。 */
    public long grantedBy;

    /** 授予时间。 */
    public LocalDateTime grantedTime;

    /** 复核人。 */
    public Long reviewerId;
}
