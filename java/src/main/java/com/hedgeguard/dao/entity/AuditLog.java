package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUDIT-2 t_audit_log} 审计日志（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-9}/{@code R-AUDIT-4}）。
 *
 * <p>审计操作自身的记录（查询与导出）。<b>不可篡改</b>；写入失败须中断被记录的操作。
 * {@code prevHash} / {@code rowHash} / {@code seqInChain} 是防篡改机制（{@code FR-AUDIT-10}）。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class AuditLog extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 涉及的子账户范围（跨子账户查询为 {@code null}）。 */
    public Integer subAccountId;

    /** 查询人。 */
    public long auditUserId;

    /** 动作类型：{@code QUERY} / {@code EXPORT} / {@code VERIFY} / {@code TRACE_FORWARD} / {@code TRACE_BACKWARD} / {@code SCREEN}。 */
    public String actionType;

    /** 查询条件。 */
    public String queryCondition;

    /** 结果条数。 */
    public int resultCount;

    /** 导出文件标识（{@code actionType='EXPORT'} 时非空）。 */
    public String exportFileId;

    /** 链条完整性标注：{@code COMPLETE} / {@code HAS_BREAK} / {@code IN_FLIGHT} / {@code UNKNOWN}。 */
    public String chainIntegrity;

    /** 操作时间。 */
    public LocalDateTime actionTime;

    /** 前一行的行哈希（链式校验）。 */
    public String prevHash;

    /** 本行哈希 = {@code SHA-256(prev_hash ∥ 关键字段)}。 */
    public String rowHash;

    /** 链内序号（单调递增）。 */
    public long seqInChain;
}
