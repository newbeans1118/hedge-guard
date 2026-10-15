package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.VoucherReviewDao;
import com.hedgeguard.dao.entity.VoucherReview;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_voucher_review} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-10）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。生成人≠审核人、co_reviewer_id 三人互异均由服务层校验。
 */
public final class DmVoucherReviewDao extends AbstractDmDao<VoucherReview, Long>
        implements VoucherReviewDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "voucher_id", "node_seq", "action", "generator_id",
            "reviewer_id", "co_reviewer_id", "review_result", "review_comment",
            "reject_reason", "action_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmVoucherReviewDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_voucher_review";
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
    protected void bindBusiness(PreparedStatement ps, VoucherReview e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.voucherId);
        bind(ps, i++, e.nodeSeq);
        bind(ps, i++, e.action);
        bind(ps, i++, e.generatorId);
        bind(ps, i++, e.reviewerId);
        bind(ps, i++, e.coReviewerId);
        bind(ps, i++, e.reviewResult);
        bind(ps, i++, e.reviewComment);
        bind(ps, i++, e.rejectReason);
        bind(ps, i, e.actionTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, VoucherReview e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.voucherId = nullableLong(rs, "voucher_id");
        e.nodeSeq = nullableInt(rs, "node_seq");
        e.action = string(rs, "action");
        e.generatorId = nullableLong(rs, "generator_id");
        e.reviewerId = nullableLong(rs, "reviewer_id");
        e.coReviewerId = nullableLong(rs, "co_reviewer_id");
        e.reviewResult = string(rs, "review_result");
        e.reviewComment = string(rs, "review_comment");
        e.rejectReason = string(rs, "reject_reason");
        e.actionTime = localDateTime(rs, "action_time");
    }

    @Override
    protected Long idOf(VoucherReview entity) {
        return entity.id;
    }

    @Override
    protected VoucherReview newEntity() {
        return new VoucherReview();
    }

    @Override
    protected void applyGeneratedId(VoucherReview entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<VoucherReview> findByVoucher(long voucherId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE voucher_id = ? ORDER BY node_seq ASC, id ASC";
        return queryList(sql, Arrays.asList(voucherId), this::mapRow);
    }

    @Override
    public List<VoucherReview> findByReviewer(long reviewerId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE reviewer_id = ? ORDER BY action_time ASC, id ASC";
        return queryList(sql, Arrays.asList(reviewerId), this::mapRow);
    }

    /**
     * {@inheritDoc}：直查专属 SQL（{@code idx_voucher_review_voucher_seq} 前列取数后
     * 应用侧取最大，避免依赖行序）；下一审核节点序号 = max + 1 的取数入口。
     */
    @Override
    public int findMaxNodeSeq(long voucherId) {
        List<VoucherReview> chain = findByVoucher(voucherId);
        int max = 0;
        for (VoucherReview r : chain) {
            if (r.nodeSeq != null && r.nodeSeq > max) {
                max = r.nodeSeq;
            }
        }
        return max;
    }
}
