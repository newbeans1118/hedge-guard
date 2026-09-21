package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUTH-12 t_permission_change_log} 权限变更留痕（{@code DATA_MODEL.md} §2.2）。
 *
 * <p><b>只增不改不删</b>：{@code updatedBy} / {@code updatedTime} 恒为 {@code null}，
 * {@code rowVersion} 不使用（{@code §0.3}）。被拒绝的尝试也须留痕（{@code R-AUTH-8}）。
 */
public final class PermissionChangeLog extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** {@code USER_ROLE}/{@code AUTHORIZATION}/{@code SUB_ACCOUNT_PERM}/{@code USER_ATTR}/{@code ROLE_DEF}/{@code POSITION_CONFLICT}。 */
    public String changeTarget;

    /** 变更对象 ID。 */
    public Long targetId;

    /** 受影响的用户。 */
    public Long targetUserId;

    /** 涉及的子账户（数据权限变更时非空）。 */
    public Integer subAccountId;

    /** 变更前值（JSON 快照）。 */
    public String beforeValue;

    /** 变更后值（JSON 快照）。 */
    public String afterValue;

    /** 操作人。 */
    public long operatorId;

    /** 复核人（{@code != operatorId}）。 */
    public Long reviewerId;

    /** 变更原因。 */
    public String changeReason;

    /** 是否被拒绝。 */
    public String rejected;

    /** 拒绝原因。 */
    public String rejectReason;

    /** 变更时间。 */
    public LocalDateTime changeTime;

    /** 是否已下发 C++。 */
    public String dispatchStatus;
}
