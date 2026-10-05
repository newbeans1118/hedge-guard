package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-RISK-7 t_post_risk_report} 事后风险报表（{@code FR-RISK-17}；{@code DATA_MODEL.md} §2.8）。
 *
 * <p>交易日结束后生成并<b>必须落库</b>（{@code R-RISK-12 @ REQUIREMENTS/RISK.md §4}）；
 * {@code reportType} 三选一，分别承载<b>风险指标统计</b> / <b>违规事项汇总</b> / <b>限额使用情况</b>
 * 三部分（{@code FR-RISK-17} 验收 2）。{@code settleReconcileKey} / {@code settleCycleId} 承载与
 * {@code SETTLE} 结算数据的勾稽（验收 3）。全账户汇总口径 {@code subAccountId = null}。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code DATA_MODEL.md} §0.3）经 {@link AuditedEntity} 复用。
 */
public final class PostRiskReport extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（按子账户出报表；全账户汇总为 {@code null}）。 */
    public Integer subAccountId;

    /** 报表日期（交易日）。 */
    public LocalDate reportDate;

    /** 报表类型：{@code RISK_METRIC} / {@code VIOLATION_SUMMARY} / {@code LIMIT_USAGE}。 */
    public String reportType;

    /** 报表内容（结构化 JSON，{@code CLOB}）。 */
    public String contentJson;

    /** 与结算数据勾稽的键（交易日 + 结算单号，{@code FR-RISK-17} 验收 3）。 */
    public String settleReconcileKey;

    /** 生成人（自动生成为系统账号）。 */
    public Long generatedBy;

    /** 生成时间。 */
    public LocalDateTime generatedTime;

    /** 关联的结算单主键（勾稽；全账户汇总可为 {@code null}）。 */
    public Long settleCycleId;
}
