package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.LimitUsageStat;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code TB-RISK-9 t_limit_usage_stat} 数据访问（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-18}）。
 *
 * <p>通用增删改查经 {@link BaseDao}；按统计日期归集供事后风控汇总。本接口为 {@code T-180} 纯增量，
 * 已登记于 {@code java/CONTRACTS.md} §2.2.1。
 */
public interface LimitUsageStatDao extends BaseDao<LimitUsageStat, Long> {

    /**
     * 按统计日期取当日全部限额使用行（{@code id} 升序）。
     *
     * @param statDate 统计日期
     * @return 列表（无命中返回空列表）
     */
    List<LimitUsageStat> findByDate(LocalDate statDate);
}
