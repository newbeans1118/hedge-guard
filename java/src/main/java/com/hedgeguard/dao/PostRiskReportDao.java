package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.PostRiskReport;

import java.time.LocalDate;

/**
 * {@code TB-RISK-7 t_post_risk_report} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-17}）。
 *
 * <p>通用增删改查经 {@link BaseDao}；报表按 {@code uk_post_risk_report}
 * （{@code report_date, report_type, sub_account_id}）幂等定位。本接口为 {@code T-180} 纯增量，
 * 已登记于 {@code java/CONTRACTS.md} §2.2.1。
 */
public interface PostRiskReportDao extends BaseDao<PostRiskReport, Long> {

    /**
     * 按幂等键（交易日 + 报表类型 + 子账户）取报表行。
     *
     * @param reportDate   报表日期
     * @param reportType   报表类型
     * @param subAccountId 子账户（全账户汇总传 {@code null}，SQL 以 {@code IS NULL} 匹配）
     * @return 命中的实体；不存在返回 {@code null}
     */
    PostRiskReport findByKey(LocalDate reportDate, String reportType, Integer subAccountId);
}
