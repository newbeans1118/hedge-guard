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

    /**
     * 幂等取数：同子账户 + 同期间 + 同凭证类型的既有凭证（{@code FR-ACCT-6} 验收 4
     * 「同一期间重复生成不产生重复凭证（幂等或明确提示）」的判定入口）。
     *
     * @param subAccountId 子账户
     * @param periodKey    会计期间
     * @param voucherType  凭证类型
     * @return 列表（{@code voucher_no} 升序，{@code id} 升序收官）；无则空列表
     */
    java.util.List<Voucher> findBySubPeriodType(int subAccountId, String periodKey, String voucherType);

    /**
     * 按套保计划取凭证（{@code idx_voucher_plan}；{@code AuditService.VoucherLookup.findByHedgePlan}
     * 的数据承载，追溯链凭证段；{@code T-076 O-3}「两索引无 DAO 方法」承接）。
     *
     * @param hedgePlanId 套保计划
     * @return 列表（{@code voucher_no} 升序，{@code id} 升序收官）
     */
    java.util.List<Voucher> findByHedgePlan(long hedgePlanId);

    /**
     * 按期间 + 状态集合取凭证（关账前置校验①的「列出未审核凭证」入口，{@code R-ACCT-8} /
     * {@code FR-ACCT-12} 验收 2「关账被拒绝并列出该凭证」；状态集合取值域 =
     * {@code ck_t_voucher_status}）。
     *
     * @param periodKey 会计期间
     * @param statuses  状态集合（非空）
     * @return 列表（{@code voucher_no} 升序，{@code id} 升序收官）
     */
    java.util.List<Voucher> findByPeriodAndStatusIn(String periodKey, java.util.List<String> statuses);

    /**
     * 取指定编号前缀内的字典序最大凭证编号（{@code uk_voucher_no}；O-4 全系统前缀 max 取号
     * 取数入口——{@code voucher_no} 全系统唯一、一经分配不得修改，T-078 RB 同法）。
     *
     * @param voucherNoPrefix 编号前缀（如 {@code AV202610}）
     * @return 最大编号；无命中返回 {@code null}
     */
    String findMaxVoucherNoByPrefix(String voucherNoPrefix);
}
