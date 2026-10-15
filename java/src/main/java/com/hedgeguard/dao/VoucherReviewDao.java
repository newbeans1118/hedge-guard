package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.VoucherReview;

import java.util.List;

/**
 * {@code TB-ACCT-10 t_voucher_review} 数据访问（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface VoucherReviewDao extends BaseDao<VoucherReview, Long> {

    /**
     * 取凭证审核链（{@code idx_voucher_review_voucher_seq}；FR-ACCT-7 验收 4）。
     *
     * @param voucherId 凭证
     * @return 列表（{@code node_seq} 升序，{@code id} 升序收官）
     */
    List<VoucherReview> findByVoucher(long voucherId);

    /**
     * 按审核人查历史（{@code idx_voucher_review_reviewer}；"待我审核"集合与历史留痕）。
     *
     * @param reviewerId 审核人
     * @return 列表（{@code action_time} 升序，{@code id} 升序收官）
     */
    List<VoucherReview> findByReviewer(long reviewerId);

    /**
     * 取凭证审核链的最大节点序号（{@code idx_voucher_review_voucher_seq}；下一节点
     * {@code nodeSeq = max + 1} 的取数入口，首节点为 {@code 1}）。
     *
     * @param voucherId 凭证
     * @return 最大 {@code node_seq}；无审核记录返回 {@code 0}
     */
    int findMaxNodeSeq(long voucherId);
}
