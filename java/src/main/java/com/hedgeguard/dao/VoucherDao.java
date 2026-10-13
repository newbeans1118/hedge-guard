package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.Voucher;

/**
 * {@code TB-ACCT-4 t_voucher} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}（状态流转经 {@code updateById} + 乐观锁，
 * 由服务层经业务动作触发——§3.3 统一纪律③）；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface VoucherDao extends BaseDao<Voucher, Long> {

    /**
     * 按凭证编号取凭证（{@code uk_voucher_no}；FR-ACCT-6 唯一性、FR-ACCT-8 按编号查询）。
     *
     * @param voucherNo 凭证编号
     * @return 命中的凭证；不存在返回 {@code null}
     */
    Voucher findByVoucherNo(String voucherNo);

    /**
     * 从结算反查凭证（{@code idx_voucher_settle_cycle}；FR-AUDIT-2 反向追溯）。
     *
     * @param settleCycleId 结算单
     * @return 列表（{@code voucher_no} 升序，{@code id} 升序收官）
     */
    java.util.List<Voucher> findBySettleCycle(long settleCycleId);

    /**
     * 从敞口反查凭证（{@code idx_voucher_exposure}；FR-AUDIT-1 正向追溯终点）。
     *
     * @param exposureId 实货敞口
     * @return 列表（{@code voucher_no} 升序，{@code id} 升序收官）
     */
    java.util.List<Voucher> findByExposure(long exposureId);

    /**
     * 关账门禁取数：按子账户 + 期间 + 状态取凭证（{@code idx_voucher_sub_period_status}；
     * 关账前置校验①「全部凭证已审核/已冲销」的筛未审核入口，R-ACCT-8；FR-ACCT-8 列表筛选）。
     *
     * @param subAccountId 子账户
     * @param periodKey    会计期间
     * @param status       状态
     * @return 列表（{@code voucher_no} 升序，{@code id} 升序收官）
     */
    java.util.List<Voucher> findBySubPeriodStatus(int subAccountId, String periodKey, String status);
}
