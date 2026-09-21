package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUTH-11 t_password_history} 口令历史（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>只存加盐哈希；保留 {@code HG.AUTH.PASSWORD_HISTORY_SIZE} 条（默认 5），不得与最近 5 次相同。
 */
public final class PasswordHistory extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 用户。 */
    public long userId;

    /** 历史口令的加盐哈希。 */
    public String passwordHash;

    /** 修改时间。 */
    public LocalDateTime changedTime;

    /** 序号（1 = 最近）。 */
    public int seqNo;
}
