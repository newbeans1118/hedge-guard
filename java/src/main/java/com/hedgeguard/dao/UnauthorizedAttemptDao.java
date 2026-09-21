package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.UnauthorizedAttempt;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code TB-AUTH-13 t_unauthorized_attempt} 数据访问（{@code DATA_MODEL.md} §2.2）。
 *
 * <p><b>只增不改不删</b>；与 {@link PermissionChangeLogDao} 的 {@code rejected = 'Y'} 行互补。
 */
public interface UnauthorizedAttemptDao extends BaseDao<UnauthorizedAttempt, Long> {

    /**
     * 按操作人查越权历史（审计重点）。
     *
     * @param operatorId 操作人 ID
     * @param from       起始时间（含）
     * @return 列表（按尝试时间倒序）
     */
    List<UnauthorizedAttempt> findByOperatorSince(long operatorId, LocalDateTime from);

    /**
     * 按类型统计（合规自查报告）。
     *
     * @param attemptType 尝试类型
     * @param from        起始时间（含）
     * @param to          结束时间（含）
     * @return 次数
     */
    long countByTypeBetween(String attemptType, LocalDateTime from, LocalDateTime to);
}
