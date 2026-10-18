package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.Cas24Selfcheck;

/**
 * {@code TB-ACCT-11 t_cas24_selfcheck} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface Cas24SelfcheckDao extends BaseDao<Cas24Selfcheck, Long> {

    /**
     * 按批次号取自检运行（{@code uk_cas24_selfcheck_no}）。
     *
     * @param selfcheckNo 自检批次号
     * @return 命中的运行；不存在返回 {@code null}
     */
    Cas24Selfcheck findBySelfcheckNo(String selfcheckNo);

    /**
     * 按套期关系/期间查自检结论（{@code idx_cas24_selfcheck_scope}；FR-ACCT-14）。
     *
     * @param scopeType 范围类型（{@code HEDGE_RELATION} / {@code PERIOD}）
     * @param scopeKey  范围键（关系编号 / 期间键）
     * @return 列表（{@code selfcheck_time} 升序，{@code id} 升序收官）
     */
    java.util.    List<Cas24Selfcheck> findByScope(String scopeType, String scopeKey);

    /**
     * 取指定编号前缀下<b>字典序最大</b>的自检批次号（{@code T-197} 取号）。
     *
     * <p>{@code selfcheck_no} 为<b>全系统唯一</b>（{@code uk_cas24_selfcheck_no}），形态
     * {@code SC<yyyyMMdd><seq(4)>}（{@code DATA_MODEL.md} §3.5 未列，{@code Cas24SelfcheckService}
     * 定义）——编号含日期段、按业务日重置 ⇒ 服务层按「当日前缀内字典序最大 + 1」取号
     * （T-137/T-141 判例前提成立）；并发兜底靠唯一约束 + 服务层最多 3 次重试。
     *
     * @param selfcheckNoPrefix 编号前缀（须为 {@code SC<yyyyMMdd>}，由服务层常量拼装；
     *                          本层按占位符绑定，禁止拼接取值）
     * @return 命中的最大编号；无任何自检批次返回 {@code null}
     */
    String findMaxSelfcheckNoByPrefix(String selfcheckNoPrefix);
}
