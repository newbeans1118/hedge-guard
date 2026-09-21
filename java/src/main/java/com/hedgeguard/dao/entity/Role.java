package com.hedgeguard.dao.entity;

/** {@code TB-AUTH-2 t_role} 角色（{@code DATA_MODEL.md} §2.2）。预置角色不可删除。 */
public final class Role extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 角色编码（{@code FIN_RISK}/{@code TRADER}/{@code SETTLER}/{@code CASHIER}/{@code APPROVER}/{@code AUDITOR}/{@code SYS_ADMIN}）。 */
    public String roleCode;

    /** 角色名称。 */
    public String roleName;

    /** 所属岗位组（互斥判定的单位）。 */
    public int positionGroupId;

    /** 是否预置角色（{@code 'Y'} 则不可删除）。 */
    public String isPreset;

    /** 是否业务角色；系统管理员为 {@code 'N'}。 */
    public String isBusinessRole;

    /** 是否含交易下单能力。 */
    public String hasTradingOperation;

    /** {@code ENABLED} / {@code DISABLED}。 */
    public String status;
}
