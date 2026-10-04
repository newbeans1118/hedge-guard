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
}
