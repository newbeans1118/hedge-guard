package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ReportPeriodConfig;

import java.util.List;

/** {@code TB-CONFIG-6 t_report_period_config} 数据访问（{@code DATA_MODEL.md} §2.1）。 */
public interface ReportPeriodConfigDao extends BaseDao<ReportPeriodConfig, Long> {

    /**
     * 按报告类型取配置。
     *
     * @param reportType 报告类型
     * @return 配置；不存在返回 {@code null}
     */
    ReportPeriodConfig findByType(String reportType);

    /**
     * 取启用中的报告周期（调度线程启动时加载）。
     *
     * @return 列表（{@code is_enabled = 'Y'}）
     */
    List<ReportPeriodConfig> findEnabled();
}
