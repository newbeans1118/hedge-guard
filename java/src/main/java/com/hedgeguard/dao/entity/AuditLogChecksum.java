package com.hedgeguard.dao.entity;

/**
 * {@code TB-AUDIT-3 t_audit_log_checksum} 审计日志校验值（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-10}）。
 *
 * <p>按批次的链头/链尾校验值，支持增量校验（避免每次全校验全表）。<b>不可改删</b>。
 * 批次生成时间即通用审计字段 {@code created_time}（§0.3），故本实体不含独立业务字段。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class AuditLogChecksum extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 批次号（单调递增）。 */
    public long batchNo;

    /** 批次覆盖的链序号区间（首）。 */
    public long batchStartSeq;

    /** 批次覆盖的链序号区间（末）。 */
    public long batchEndSeq;

    /** 批次首行哈希。 */
    public String batchHeadHash;

    /** 批次末行哈希。 */
    public String batchTailHash;

    /** 批次校验和 = {@code SHA-256(链式累加)}。 */
    public String batchChecksum;

    /** 批次记录数。 */
    public int recordCount;
}
