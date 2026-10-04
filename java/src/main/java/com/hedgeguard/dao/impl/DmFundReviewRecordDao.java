package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.FundReviewRecordDao;
import com.hedgeguard.dao.entity.FundReviewRecord;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_fund_review_record} 的达梦实现（{@code DATA_MODEL.md} §2.11 TB-FUND-6）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmFundReviewRecordDao extends AbstractDmDao<FundReviewRecord, Long> implements FundReviewRecordDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "biz_type", "biz_id", "applicant_id", "reviewer_id", "review_result",
            "review_comment", "reject_reason", "self_review_rejected", "review_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmFundReviewRecordDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_fund_review_record";
    }

    @Override
    protected String idColumn() {
        return "id";
    }

    @Override
    protected String[] businessColumns() {
        return COLUMNS.clone();
    }

    @Override
    protected void bindBusiness(PreparedStatement ps, FundReviewRecord e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.bizType);
        bind(ps, i++, e.bizId);
        bind(ps, i++, e.applicantId);
        bind(ps, i++, e.reviewerId);
        bind(ps, i++, e.reviewResult);
        bind(ps, i++, e.reviewComment);
        bind(ps, i++, e.rejectReason);
        bind(ps, i++, e.selfReviewRejected);
        bind(ps, i, e.reviewTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, FundReviewRecord e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.bizType = string(rs, "biz_type");
        e.bizId = nullableLong(rs, "biz_id");
        e.applicantId = nullableLong(rs, "applicant_id");
        e.reviewerId = nullableLong(rs, "reviewer_id");
        e.reviewResult = string(rs, "review_result");
        e.reviewComment = string(rs, "review_comment");
        e.rejectReason = string(rs, "reject_reason");
        e.selfReviewRejected = string(rs, "self_review_rejected");
        e.reviewTime = localDateTime(rs, "review_time");
    }

    @Override
    protected Long idOf(FundReviewRecord entity) {
        return entity.id;
    }

    @Override
    protected FundReviewRecord newEntity() {
        return new FundReviewRecord();
    }

    @Override
    protected void applyGeneratedId(FundReviewRecord entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<FundReviewRecord> findByBiz(String bizType, long bizId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE biz_type = ? AND biz_id = ? ORDER BY id ASC";
        return queryList(sql, Arrays.asList(bizType, bizId), this::mapRow);
    }

    @Override
    public List<FundReviewRecord> findBySelfReviewRejected(String selfReviewRejected) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE self_review_rejected = ? ORDER BY id ASC";
        return queryList(sql, Arrays.asList(selfReviewRejected), this::mapRow);
    }
}
