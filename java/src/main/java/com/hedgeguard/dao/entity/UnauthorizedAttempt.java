package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUTH-13 t_unauthorized_attempt} 越权尝试记录（{@code DATA_MODEL.md} §2.2）。
 *
 * <p><b>只增不改不删</b>；与 {@link PermissionChangeLog} 中 {@code rejected='Y'} 的行互补
 * （前者记录越权<b>操作</b>，后者记录越权<b>配置</b>）。
 */
public final class UnauthorizedAttempt extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 操作人。 */
    public long operatorId;

    /** {@code POSITION_CONFLICT}/{@code LEADER_TRADE}/{@code CROSS_SUB_WRITE}/{@code SYSADMIN_BIZ_OP}/{@code DUAL_REVIEW_SELF}/{@code PERMISSION_DENIED}/{@code SELF_APPROVE}。 */
    public String attemptType;

    /** 尝试的操作描述。 */
    public String attemptDesc;

    /** 目标资源（API 路径 / 对象标识）。 */
    public String targetResource;

    /** 涉及的子账户（跨子账户类越权必有值）。 */
    public Integer subAccountId;

    /** 拒绝原因。 */
    public String rejectReason;

    /** 拒绝错误码。 */
    public String rejectCode;

    /** 尝试时间。 */
    public LocalDateTime attemptTime;

    /** 客户端 IP。 */
    public String clientIp;
}
