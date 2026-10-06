package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-AUDIT-7 t_evidence_export} 证据链导出记录（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-8}）。
 *
 * <p>证据链导出的记录；导出动作进入审计日志；存在断点须显式标注。{@code breakList} 为 {@code CLOB}
 * （{@code DATA_MODEL.md} §6.1 D7）。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class EvidenceExport extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 导出批次号。 */
    public String exportNo;

    /** 追溯起点类型：{@code EXPOSURE} / {@code TRADE} / {@code VOUCHER}。 */
    public String startPointType;

    /** 追溯起点标识集合。 */
    public String startPointIds;

    /** 时间范围（起）。 */
    public LocalDate dateFrom;

    /** 时间范围（止）。 */
    public LocalDate dateTo;

    /** 子账户范围。 */
    public String subAccountScope;

    /** 是否含原始单据明细：{@code Y} / {@code N}。 */
    public String includeSourceDetail;

    /** 链条完整性：{@code COMPLETE} / {@code HAS_BREAK} / {@code IN_FLIGHT} / {@code UNKNOWN}。 */
    public String chainIntegrity;

    /** 断点清单（{@code CLOB}）。 */
    public String breakList;

    /** 是否含双侧盈亏（须恒为 {@code 'Y'}）。 */
    public String dualPnlIncluded;

    /** 是否含交易理由（须恒为 {@code 'Y'}）。 */
    public String reasonIncluded;

    /** 导出文件标识。 */
    public String fileId;

    /** 文件路径。 */
    public String filePath;

    /** 导出人。 */
    public long exportedBy;

    /** 导出时间。 */
    public LocalDateTime exportedTime;

    /** 关联的审计日志（先写日志后导出；写失败则中断导出）。 */
    public Long auditLogId;
}
