package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.LoginSession;

import java.time.LocalDateTime;

/**
 * {@code TB-AUTH-9 t_login_session} 数据访问（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>本表是 {@code §0.3} 显式列出的<b>允许物理删除</b>的例外表（清理历史会话），
 * 故实现 {@link DeletableDao}。
 */
public interface LoginSessionDao extends DeletableDao<LoginSession, Long> {

    /**
     * 按会话 ID 查会话（每个请求的会话校验，最高频查询）。
     *
     * @param sessionId 会话 ID
     * @return 会话；不存在返回 {@code null}
     */
    LoginSession findBySessionId(String sessionId);

    /**
     * 使某用户全部会话立即失效（停用用户 / 权限变更，{@code FR-AUTH-3} 验收 1）。
     *
     * @param userId       用户 ID
     * @param status       置为 {@code LOGGED_OUT} / {@code KICKED}
     * @param kickedBy     强制下线的管理员（可 {@code null}）
     * @param permissionVersion 新的权限版本
     * @return 影响行数
     */
    int invalidateByUser(long userId, String status, Long kickedBy, long permissionVersion);

    /**
     * 刷新最后活动时间（空闲超时判定）。
     *
     * @param sessionId        会话 ID
     * @param lastActiveTime   最后活动时间
     * @return 影响行数
     */
    int touch(String sessionId, LocalDateTime lastActiveTime);

    /**
     * 清理已过期会话（物理删除）。
     *
     * @param before 截止时刻
     * @return 删除行数
     */
    int deleteExpired(LocalDateTime before);
}
