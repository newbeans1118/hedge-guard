package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.LoginLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code TB-AUTH-10 t_login_log} 数据访问（{@code DATA_MODEL.md} §2.2）。
 *
 * <p><b>只增不改不删</b>：不提供删除方法；{@link #updateById} 实现须显式拒绝。
 * 本表无口令字段；{@code fail_reason_code} 为内部定位用，对外响应统一（{@code R-AUTH-7}）。
 */
public interface LoginLogDao extends BaseDao<LoginLog, Long> {

    /**
     * 按用户查登录历史（审计）。
     *
     * @param userId 用户 ID
     * @param from   起始时间（含）
     * @param to     结束时间（含）
     * @return 列表（按事件时间倒序）
     */
    List<LoginLog> findByUserBetween(Long userId, LocalDateTime from, LocalDateTime to);

    /**
     * 按时间窗统计失败次数（异常检测）。
     *
     * @param clientIp 客户端 IP
     * @param from     起始时间（含）
     * @return 失败次数
     */
    long countFailByIpSince(String clientIp, LocalDateTime from);
}
