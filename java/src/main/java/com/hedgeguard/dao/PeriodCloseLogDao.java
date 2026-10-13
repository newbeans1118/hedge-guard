package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.PeriodCloseLog;

import java.util.List;

/**
 * {@code TB-ACCT-7 t_period_close_log} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>只增不改不删 ⇒ 实现侧 {@code updateById} 显式拒绝（{@code CONTRACTS.md} §2.3）。
 * 全局期间级操作（例外 E2，无子账户维度）。
 */
public interface PeriodCloseLogDao extends BaseDao<PeriodCloseLog, Long> {

    /**
     * 按期间取关账历史（{@code idx_period_close_log_key_time}；FR-ACCT-12 审计）。
     *
     * @param periodKey 期间键
     * @return 列表（{@code action_time} 升序，{@code id} 升序收官）
     */
    List<PeriodCloseLog> findByPeriod(String periodKey);
}
