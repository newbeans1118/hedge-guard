package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SettleAggDiffCandidate;

/**
 * {@code TB-SETTLE-16 t_agg_diff_candidate} 数据访问（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link com.hedgeguard.dao.SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SettleAggDiffCandidateDao extends BaseDao<SettleAggDiffCandidate, Long> {

    java.util.List<SettleAggDiffCandidate> findBlockingCandidates(long aggregationCheckId);
}
