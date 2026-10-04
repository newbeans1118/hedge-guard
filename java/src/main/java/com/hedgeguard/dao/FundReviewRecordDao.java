package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.FundReviewRecord;
import java.util.List;

/**
 * {@code TB-FUND-6 t_fund_review_record} 资金复核记录数据访问（{@code DATA_MODEL.md} §2.11）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface FundReviewRecordDao extends BaseDao<FundReviewRecord, Long> {
    /**
     * 取某业务对象的复核记录（{@code idx_fund_review_biz}；证明双人复核，FR-FUND-16 验收 2）。
     *
     * @param bizType 业务类型
     * @param bizId   业务对象 ID
     * @return 列表（{@code id} 升序）
     */
    List<FundReviewRecord> findByBiz(String bizType, long bizId);

    /**
     * 筛「发起人=复核人」的越权尝试（{@code idx_fund_review_self_rejected}；审计重点）。
     *
     * @param selfReviewRejected 是否因自我复核被拒（Y/N）
     * @return 列表（{@code id} 升序）
     */
    List<FundReviewRecord> findBySelfReviewRejected(String selfReviewRejected);
}
