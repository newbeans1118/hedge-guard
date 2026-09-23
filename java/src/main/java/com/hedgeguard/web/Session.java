package com.hedgeguard.web;

import java.time.LocalDateTime;

/**
 * 登录会话（{@code TB-AUTH-9 t_login_session} 的内存形态）。
 *
 * <p>敏感字段零落盘（{@code NFR-SEC-1}）：会话只持有 {@code sessionId} 与权限版本，
 * <b>不</b>持有口令、AuthCode、资金密码。
 */
public final class Session {

    /** 服务端生成的 opaque 会话 ID（Cookie {@code HGSESSION}）。 */
    public final String sessionId;

    /** 用户 ID（{@code t_user.id}）。 */
    public final long userId;

    /** 登录名（仅用于审计与界面展示）。 */
    public final String username;

    /** 会话建立时的权限版本快照；权限变更后须使相关会话立即失效（{@code R-AUTH-9}）。 */
    public final long permissionVersion;

    /**
     * 「首次登录 / 口令被重置后须强制修改口令」标记（{@code FR-AUTH-16} 处理规则）。
     *
     * <p>来源 = 登录时 {@code t_user.must_change_password='Y'} 的快照。为 {@code true} 时，
     * {@link SessionAuthFilter} 在分发前仅放行「本人改口令」与「登出」两个端点，其余业务端点一律拒绝
     * （服务端强制，非前端提示）；改密成功后由 {@link com.hedgeguard.service.AuthService} 清除。
     */
    public final boolean mustChangePassword;

    /** 绝对过期时间。 */
    public final LocalDateTime expireTime;

    /**
     * 构造（默认不要求强制改口令；保留既有 5 参调用点语义）。
     *
     * @param sessionId         会话 ID
     * @param userId            用户 ID
     * @param username          登录名
     * @param permissionVersion 权限版本
     * @param expireTime        绝对过期时间
     */
    public Session(String sessionId, long userId, String username, long permissionVersion,
                   LocalDateTime expireTime) {
        this(sessionId, userId, username, permissionVersion, false, expireTime);
    }

    /**
     * 构造（含强制改口令标记）。
     *
     * @param sessionId          会话 ID
     * @param userId             用户 ID
     * @param username           登录名
     * @param permissionVersion  权限版本
     * @param mustChangePassword 是否须强制修改口令（{@code FR-AUTH-16}）
     * @param expireTime         绝对过期时间
     */
    public Session(String sessionId, long userId, String username, long permissionVersion,
                   boolean mustChangePassword, LocalDateTime expireTime) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.username = username;
        this.permissionVersion = permissionVersion;
        this.mustChangePassword = mustChangePassword;
        this.expireTime = expireTime;
    }

    /**
     * 复制并替换「强制修改口令」标记（会话不可变；续期与改密成功后使用）。
     *
     * @param value 新标记值
     * @return 新的会话实例（其余字段不变）
     */
    public Session withMustChangePassword(boolean value) {
        return new Session(sessionId, userId, username, permissionVersion, value, expireTime);
    }
}
