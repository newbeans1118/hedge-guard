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

    /**
     * 分页 + 子账户可见范围过滤（{@code FR-RISK-18} 验收 2、{@code NFR-SEC-7} 读取面强制点）。
     *
     * <p>scope 的 {@code sub_account_id} 谓词直接落在本表 {@code sub_account_id} 列上；
     * <b>空 scope ⇒ 恒假条件（返回空），绝不退化为不过滤</b>（{@code T-066} 纯增量，已登记
     * {@code java/CONTRACTS.md} §2.2.1）。
     *
     * @param query      分页 / 排序
     * @param scope      子账户过滤（列名取本表 {@code sub_account_id}）
     * @param extraWhere 附加条件（可为 {@code null}）
     * @param extraArgs  附加条件参数（可为 {@code null}）
     * @return 分页结果（{@code total} 为叠加 scope 与 {@code extraWhere} 后的总行数）
     */
    PageResult<LimitUsageStat> findPageScoped(PageQuery query, SubAccountScope scope,
                                              String extraWhere, List<Object> extraArgs);
}
