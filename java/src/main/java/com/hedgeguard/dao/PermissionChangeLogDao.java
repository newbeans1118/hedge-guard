package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.PermissionChangeLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code TB-AUTH-12 t_permission_change_log} 数据访问（{@code DATA_MODEL.md} §2.2）。
 *
 * <p><b>只增不改不删</b>：被拒绝的尝试也须留痕（{@code R-AUTH-8}）。
 */
public interface PermissionChangeLogDao extends BaseDao<PermissionChangeLog, Long> {

    /**
     * 按受影响用户查权限变更历史（{@code FR-AUTH-11}、{@code FR-AUTH-18}）。
     *
     * @param targetUserId 受影响用户 ID
     * @param from         起始时间（含）
     * @param to           结束时间（含）
     * @return 列表（按变更时间倒序）
     */
    List<PermissionChangeLog> findByTargetUserBetween(long targetUserId, LocalDateTime from,
                                                      LocalDateTime to);

    /**
     * 筛选被拒绝的变更（与越权尝试记录互补）。
     *
     * @param from 起始时间（含）
     * @return 列表（{@code rejected = 'Y'}）
     */
    List<PermissionChangeLog> findRejectedSince(LocalDateTime from);
}
