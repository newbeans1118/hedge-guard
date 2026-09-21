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

    /** 绝对过期时间。 */
    public final LocalDateTime expireTime;

    /**
     * 构造。
     *
     * @param sessionId         会话 ID
     * @param userId            用户 ID
     * @param username          登录名
     * @param permissionVersion 权限版本
     * @param expireTime        绝对过期时间
     */
    public Session(String sessionId, long userId, String username, long permissionVersion,
                   LocalDateTime expireTime) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.username = username;
        this.permissionVersion = permissionVersion;
        this.expireTime = expireTime;
    }
}
