package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleAggDiffCandidateDao;
import com.hedgeguard.dao.entity.SettleAggDiffCandidate;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_agg_diff_candidate} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-16）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleAggDiffCandidateDao extends AbstractDmDao<SettleAggDiffCandidate, Long> implements SettleAggDiffCandidateDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "aggregation_check_id", "candidate_rank", "diff_amount", "resolution_status", "reconcile_difference_id", "evidence_desc"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleAggDiffCandidateDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_agg_diff_candidate";
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
    protected void bindBusiness(PreparedStatement ps, SettleAggDiffCandidate e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.aggregationCheckId);
        bind(ps, i++, e.candidateRank);
        bind(ps, i++, e.diffAmount);
        bind(ps, i++, e.resolutionStatus);
        bind(ps, i++, e.reconcileDifferenceId);
        bind(ps, i++, e.evidenceDesc);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleAggDiffCandidate e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.aggregationCheckId = nullableLong(rs, "aggregation_check_id");
        e.candidateRank = nullableInt(rs, "candidate_rank");
        e.diffAmount = decimal(rs, "diff_amount");
        e.resolutionStatus = string(rs, "resolution_status");
        e.reconcileDifferenceId = nullableLong(rs, "reconcile_difference_id");
        e.evidenceDesc = string(rs, "evidence_desc");
    }

    @Override
    protected Long idOf(SettleAggDiffCandidate entity) {
        return entity.id;
    }

    @Override
    protected SettleAggDiffCandidate newEntity() {
        return new SettleAggDiffCandidate();
    }

    @Override
    protected void applyGeneratedId(SettleAggDiffCandidate entity, long generated) {
        entity.id = generated;
    }

    @Override
    public java.util.List<SettleAggDiffCandidate> findBlockingCandidates(long aggregationCheckId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE aggregation_check_id = ? AND resolution_status IN (?, ?)"
                + " ORDER BY candidate_rank ASC, id ASC";
        return queryList(sql, Arrays.asList(aggregationCheckId, "PENDING", "CONFIRMED"), this::mapRow);
    }
}
