package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SettleCycle;

/**
 * {@code TB-SETTLE-5 t_settle_cycle} 数据访问（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link com.hedgeguard.dao.SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SettleCycleDao extends BaseDao<SettleCycle, Long> {

    /**
     * 按子账户 + 交易日取结算周期（{@code FR-AUDIT-1/2} 追溯链「结算」段；
     * 「已结算未出凭证」在途判定的判定基准）。
     *
     * <p>本方法为跨账户只读路径（{@code T-067}；子账户可见性由调用方 {@code AuditService}
     * 统一经 {@code AuthService.visibleSubAccountIds} 校验，DAO 不再叠加 scope）。
     *
     * @param subAccountId 子账户
     * @param tradeDate    交易日
     * @return 结算周期列表（{@code id} 升序）；无则空列表
     */
    java.util.List<SettleCycle> findBySubAccountAndDate(int subAccountId, java.time.LocalDate tradeDate);
}
