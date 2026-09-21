package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUTH-9 t_login_session} 登录会话（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>本表<b>允许物理删除</b>（清理历史会话，{@code §0.3} 例外），故对应 DAO 实现
 * {@link com.hedgeguard.dao.DeletableDao}；状态流转留存于 {@code t_login_log}。
 */
public final class LoginSession extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 服务端生成的 opaque 会话 ID（Cookie {@code HGSESSION}）。 */
    public String sessionId;

    /** 用户。 */
    public long userId;

    /** 登录时间。 */
    public LocalDateTime loginTime;

    /** 最后活动时间。 */
    public LocalDateTime lastActiveTime;

    /** 该会话的空闲超时（登录时从配置快照）。 */
    public int idleTimeoutMinutes;

    /** 绝对过期时间。 */
    public LocalDateTime expireTime;

    /** 客户端 IP。 */
    public String clientIp;

    /** UA。 */
    public String userAgent;

    /** {@code ACTIVE} / {@code LOGGED_OUT} / {@code EXPIRED} / {@code KICKED}。 */
    public String status;

    /** 强制下线的管理员。 */
    public Long kickedBy;

    /** 会话建立时的权限版本快照。 */
    public long permissionVersionSnapshot;
}
