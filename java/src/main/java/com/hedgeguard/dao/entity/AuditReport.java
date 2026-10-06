package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-AUDIT-6 t_audit_report} 审计报表（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-5~7}/{@code FR-AUDIT-12}）。
 *
 * <p>季度报表 / 年度专项报告 / 重大事项专项报告。{@code contentJson} / {@code breakList} 为 {@code CLOB}
 * （{@code DATA_MODEL.md} §6.1 D7，标准 {@code getCharacterStream}/{@code setCharacterStream}）。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class AuditReport extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（按子账户报表时非空；汇总为 {@code null}）。 */
    public Integer subAccountId;

    /** 报表编号。 */
    public String reportNo;

    /** 报表类型：{@code QUARTERLY} / {@code ANNUAL_SPECIAL} / {@code MAJOR_EVENT}。 */
    public String reportType;

    /** 期间键（{@code 2026-Q3} / {@code 2026} / 事项编号）。 */
    public String periodKey;

    /** 报表内容（{@code CLOB}）。 */
    public String contentJson;

    /** 是否含双侧盈亏（须恒为 {@code 'Y'}，{@code R-ACCT-4}）。 */
    public String dualPnlIncluded;

    /** 链条完整性标注：{@code COMPLETE} / {@code HAS_BREAK} / {@code IN_FLIGHT} / {@code UNKNOWN}。 */
    public String chainIntegrity;

    /** 断点清单（{@code CLOB}；{@code chainIntegrity≠'COMPLETE'} 时非空）。 */
    public String breakList;

    /** 数据缺口说明。 */
    public String dataGapDesc;

    /** 生成人。 */
    public long generatedBy;

    /** 生成时间。 */
    public LocalDateTime generatedTime;

    /** 是否已提交（重大事项专项报告）：{@code Y} / {@code N}。 */
    public String submitted;

    /** 提交对象。 */
    public String submittedTo;

    /** 提交时间。 */
    public LocalDateTime submittedTime;
}
