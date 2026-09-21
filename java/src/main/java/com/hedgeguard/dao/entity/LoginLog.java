package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUTH-10 t_login_log} 登录日志（{@code DATA_MODEL.md} §2.2）。
 *
 * <p><b>只增不改不删</b>：{@code updatedBy} / {@code updatedTime} 恒为 {@code null}，
 * {@code rowVersion} 不使用（{@code §0.3}，故仍继承 {@link AuditedEntity} 以保持列齐备）。
 * 本类型<b>无口令字段</b>。
 */
public final class LoginLog extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 用户（用户不存在时为 {@code null}，但该场景不向用户暴露，防枚举）。 */
    public Long userId;

    /** 输入的用户名（不含口令）。 */
    public String usernameInput;

    /** {@code LOGIN_SUCCESS}/{@code LOGIN_FAIL}/{@code LOCKED}/{@code UNLOCKED}/{@code LOGOUT}/{@code KICKED}。 */
    public String eventType;

    /** 事件后的失败计数。 */
    public Integer failCountAfter;

    /** 客户端 IP。 */
    public String clientIp;

    /** UA。 */
    public String userAgent;

    /** 失败原因内部码（对外统一为同一提示）。 */
    public String failReasonCode;

    /** 事件时间。 */
    public LocalDateTime eventTime;
}
