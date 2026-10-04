package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.DepositWithdrawApply;
import java.util.List;

/**
 * {@code TB-FUND-3 t_deposit_withdraw_apply} 出入金申请数据访问（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface DepositWithdrawApplyDao extends BaseDao<DepositWithdrawApply, Long> {
    /**
     * 按幂等标识查申请（{@code uk_dw_apply_idem}；重复提交不得产生第二笔划转，FR-FUND-5 验收 3）。
     *
     * @param idempotencyKey 幂等标识
     * @return 命中的申请；不存在返回 {@code null}
     */
    DepositWithdrawApply findByIdempotencyKey(String idempotencyKey);

    /**
     * 按申请单号查申请（{@code uk_dw_apply_no}）。
     *
     * @param applyNo 申请单号
     * @return 命中的申请；不存在返回 {@code null}
     */
    DepositWithdrawApply findByApplyNo(String applyNo);

    /**
     * 按子账户 + 状态取申请（{@code idx_dw_apply_sub_status}；筛「超时待查」查询确认队列）。
     *
     * @param subAccountId 子账户
     * @param status       状态
     * @return 列表（{@code id} 升序）
     */
    List<DepositWithdrawApply> findBySubAccountAndStatus(int subAccountId, String status);

    /**
     * 取当日前缀区间内字典序最大的申请单号（{@code DATA_MODEL.md} §3.5「当日最大序号 + 1」取数；
     * {@code T-049} 退回修复，先例 {@code HedgePlanDao.findMaxPlanNoByPrefix} / {@code T-137}）。
     *
     * <p>{@code t_deposit_withdraw_apply} 无独立业务日列，业务日隐含于 {@code apply_no} 前缀
     * （{@code DW<yyyyMMdd>}）；通用 WHERE 原子表达不了前缀匹配 ⇒ 专用 SQL
     * {@code apply_no LIKE ?}，走 {@code uk_dw_apply_no} 唯一索引区间；
     * <b>不使用 {@code SELECT MAX(...) FOR UPDATE}</b>（{@code §3.5} 明令禁止）。
     *
     * @param applyNoPrefix 当日前缀（如 {@code DW20260920}）
     * @return 字典序最大的单号；当日无既有编号返回 {@code null}
     */
    String findMaxApplyNoByPrefix(String applyNoPrefix);
}
