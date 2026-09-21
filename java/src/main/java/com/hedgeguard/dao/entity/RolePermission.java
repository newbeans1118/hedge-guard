package com.hedgeguard.dao.entity;

/** {@code TB-AUTH-3 t_role_permission} 角色功能权限（{@code DATA_MODEL.md} §2.2）。 */
public final class RolePermission extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 角色。 */
    public long roleId;

    /** 权限编码（如 {@code PRODUCT:EDIT}）。 */
    public String permCode;

    /** {@code FUNCTION} / {@code DATA} / {@code QUOTA}。 */
    public String permCategory;
}
