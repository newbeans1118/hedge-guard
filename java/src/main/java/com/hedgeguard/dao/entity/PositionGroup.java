package com.hedgeguard.dao.entity;

/**
 * {@code TB-AUTH-5 t_position_group} 岗位组（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>不变量：{@code isConflictEnforced} 恒为 {@code 'Y'}——"不得提供关闭互斥的配置开关"
 * （{@code FR-AUTH-7} 验收 2）在数据库层落地（{@code ck_position_group_conflict_enforced}）。
 */
public final class PositionGroup extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 岗位组编码。 */
    public String groupCode;

    /** 岗位组名称。 */
    public String groupName;

    /** {@code FRONT} / {@code MID} / {@code BACK} / {@code MANAGE} / {@code SYSTEM}。 */
    public String frontMidBack;

    /** 是否参与强制互斥（恒 {@code 'Y'}）。 */
    public String isConflictEnforced;

    /** {@code ENABLED} / {@code DISABLED}。 */
    public String status;
}
