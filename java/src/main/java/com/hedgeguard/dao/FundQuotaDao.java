package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.FundQuota;
import java.util.List;

/**
 * {@code TB-FUND-7 t_fund_quota} 资金额度数据访问（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface FundQuotaDao extends BaseDao<FundQuota, Long> {
    /**
     * 按子账户取额度（{@code idx_fund_quota_sub}；额度告警判定）。
     *
     * @param subAccountId 子账户
     * @return 列表（{@code effective_from} 升序，{@code id} 升序收官）
     */
    List<FundQuota> findBySubAccount(int subAccountId);

    /**
     * 按范围取额度（{@code idx_fund_quota_scope}；取账户级额度）。
     *
     * @param quotaScope 范围（SUB_ACCOUNT / ACCOUNT）
     * @return 列表（{@code effective_from} 升序，{@code id} 升序收官）
     */
    List<FundQuota> findByScope(String quotaScope);
}
