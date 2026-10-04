package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TransferApply;
import java.util.List;

/**
 * {@code TB-FUND-4 t_transfer_apply} 子账户间划拨申请数据访问（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface TransferApplyDao extends BaseDao<TransferApply, Long> {
    /**
     * 按划拨单号查申请（{@code uk_transfer_apply_no}）。
     *
     * @param applyNo 划拨单号
     * @return 命中的申请；不存在返回 {@code null}
     */
    TransferApply findByApplyNo(String applyNo);

    /**
     * 按划出子账户 + 状态取申请（{@code idx_transfer_apply_from}；划出方历史与加总核对）。
     *
     * @param fromSubAccountId 划出子账户
     * @param status           状态
     * @return 列表（{@code id} 升序）
     */
    List<TransferApply> findByFromSubAccountAndStatus(int fromSubAccountId, String status);

    /**
     * 按划入子账户 + 状态取申请（{@code idx_transfer_apply_to}）。
     *
     * @param toSubAccountId 划入子账户
     * @param status         状态
     * @return 列表（{@code id} 升序）
     */
    List<TransferApply> findByToSubAccountAndStatus(int toSubAccountId, String status);

    /**
     * 取当日前缀区间内字典序最大的划拨单号（{@code DATA_MODEL.md} §3.5「当日最大序号 + 1」取数；
     * {@code T-049} 退回修复，先例 {@code HedgePlanDao.findMaxPlanNoByPrefix} / {@code T-137}）。
     *
     * <p>{@code t_transfer_apply} 无独立业务日列，业务日隐含于 {@code apply_no} 前缀
     * （{@code TF<yyyyMMdd>}）；专用 SQL {@code apply_no LIKE ?}，走 {@code uk_transfer_apply_no}
     * 唯一索引区间；<b>不使用 {@code SELECT MAX(...) FOR UPDATE}</b>（{@code §3.5} 明令禁止）。
     *
     * @param applyNoPrefix 当日前缀（如 {@code TF20260920}）
     * @return 字典序最大的单号；当日无既有编号返回 {@code null}
     */
    String findMaxApplyNoByPrefix(String applyNoPrefix);
}
