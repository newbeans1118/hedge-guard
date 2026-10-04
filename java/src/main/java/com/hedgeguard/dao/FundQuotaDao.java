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

    // ------------------------------------------------------------ T-049：读取面「§8.2 子账户隔离」载体

    /**
     * 按范围 + 子账户可见范围取额度（{@code FR-FUND-13} 验收4 / {@code NFR-SEC-7} 读取面强制点；
     * {@code T-049} 退回修复）。
     *
     * <p>scope 的 {@code sub_account_id} 谓词直接落在本表列上（{@code t_fund_quota} 有
     * {@code sub_account_id} 列，同为「直接落本表列」载体，先例 {@code ExposureDao.findPageScoped}）。
     * <b>空 scope ⇒ 恒假条件（返回空），绝不退化为不过滤</b>。
     *
     * <p><b>仅用于 {@code quota_scope = SUB_ACCOUNT} 行</b>：账户级（{@code quota_scope = ACCOUNT}）
     * 行 {@code sub_account_id} 为哨兵 {@code NULL}，非子账户维度，不适用本过滤（误加会漏掉账户级额度；
     * 同 {@code T-112} 层③口径）——账户级仍走 {@link #findByScope(String)}。
     *
     * @param quotaScope 额度范围（{@code SUB_ACCOUNT}）
     * @param scope      子账户过滤
     * @return 可见范围内的额度列表（{@code effective_from} 升序，{@code id} 升序收官）
     */
    List<FundQuota> findScoped(String quotaScope, SubAccountScope scope);
}
