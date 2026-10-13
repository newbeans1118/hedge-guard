package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.DualPnlReport;

/**
 * {@code TB-ACCT-9 t_dual_pnl_report} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface DualPnlReportDao extends BaseDao<DualPnlReport, Long> {

    /**
     * 按编号取上报记录（{@code uk_dual_pnl_report_no}）。
     *
     * @param reportNo 上报编号
     * @return 命中的记录；不存在返回 {@code null}
     */
    DualPnlReport findByReportNo(String reportNo);

    /**
     * 按报告期间 + 类型查上报记录（{@code idx_dual_pnl_period_type}；FR-ACCT-10 验收 3）。
     *
     * @param reportPeriod 报告期间
     * @param reportType   报告类型（{@code MONTHLY} / {@code QUARTERLY} / {@code ANNUAL}）
     * @return 列表（{@code submitted_time} 升序，{@code id} 升序收官）
     */
    java.util.List<DualPnlReport> findByPeriodAndType(String reportPeriod, String reportType);
}
