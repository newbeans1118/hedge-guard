package com.hedgeguard.dao.entity;

/**
 * {@code TB-AUTH-6 t_position_conflict} 岗位互斥关系（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>矩阵按单向存储（仅存 {@code a < b} 的组合），服务层查询时双向匹配（{@code OR} 查询）。
 */
public final class PositionConflict extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 岗位组 A。 */
    public int groupAId;

    /** 岗位组 B。 */
    public int groupBId;

    /** 互斥依据（如"8 号文 四(一) 前中后台分离"）。 */
    public String conflictReason;

    /** 是否强制互斥；默认互斥集恒为 {@code 'Y'}。 */
    public String isMandatory;

    /** 变更复核人。 */
    public Long reviewerId;
}
