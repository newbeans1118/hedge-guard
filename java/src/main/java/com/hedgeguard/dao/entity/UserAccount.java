package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUTH-1 t_user} 用户（{@code DATA_MODEL.md} §2.2）。不可物理删除，只能停用。
 *
 * <p>口令字段的<b>唯一形态</b>是 {@link #passwordHash}（PBKDF2-HMAC-SHA256），本类型<b>无明文口令字段</b>
 * —— 从数据模型层杜绝明文落库（{@code NFR-SEC-2}）。敏感字段零落盘、零日志。
 */
public final class UserAccount extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 登录名，不可修改。 */
    public String username;

    /** 姓名。 */
    public String realName;

    /** 自然人唯一键（身份证号哈希或工号）——"一个自然人一个账号"。 */
    public String naturalPersonKey;

    /** 所属部门。 */
    public String department;

    /** 企业负责人标记；{@code 'Y'} 则禁授交易岗 + 禁下单。 */
    public String isEnterpriseLeader;

    /** 负责人标记变更的复核人。 */
    public Long leaderFlagReviewerId;

    /** {@code ENABLED} / {@code DISABLED}。 */
    public String status;

    /** 加盐哈希，格式 {@code pbkdf2$<iter>$<salt_b64>$<hash_b64>}。 */
    public String passwordHash;

    /** 首次登录 / 重置后强制改口令。 */
    public String mustChangePassword;

    /** 最近一次口令修改时间。 */
    public LocalDateTime passwordUpdatedTime;

    /** 连续登录失败次数。 */
    public int failCount;

    /** 锁定至（{@code null} = 未锁定）。 */
    public LocalDateTime lockedUntil;

    /** 最后登录时间。 */
    public LocalDateTime lastLoginTime;

    /** 停用原因。 */
    public String disabledReason;
}
