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
    java.util.List<Cas24Selfcheck> findByScope(String scopeType, String scopeKey);
}
