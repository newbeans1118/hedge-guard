package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.RebalanceRecordDao;
import com.hedgeguard.dao.entity.RebalanceRecord;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_rebalance_record} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-3）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 *
 * <p><b>不得事后修改</b>（R-ACCT-3：再平衡记录不得事后修改）：
 * {@link #updateById} 显式拒绝。
 */
public final class DmRebalanceRecordDao extends AbstractDmDao<RebalanceRecord, Long>
        implements RebalanceRecordDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "hedge_relation_id", "rebalance_no", "trigger_type", "trigger_side",
            "trigger_reason", "trigger_exposure_delta", "trigger_ratio_deviation", "trigger_threshold",
            "ratio_before", "ratio_after", "qty_before", "qty_after", "new_hedge_doc_id",
            "new_hedge_relation_id", "executor_id", "rebalance_time", "plan_feedback_flag"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmRebalanceRecordDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_rebalance_record";
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
    protected void bindBusiness(PreparedStatement ps, RebalanceRecord e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.hedgeRelationId);
        bind(ps, i++, e.rebalanceNo);
        bind(ps, i++, e.triggerType);
        bind(ps, i++, e.triggerSide);
        bind(ps, i++, e.triggerReason);
        bind(ps, i++, e.triggerExposureDelta);
        bind(ps, i++, e.triggerRatioDeviation);
        bind(ps, i++, e.triggerThreshold);
        bind(ps, i++, e.ratioBefore);
        bind(ps, i++, e.ratioAfter);
        bind(ps, i++, e.qtyBefore);
        bind(ps, i++, e.qtyAfter);
        bind(ps, i++, e.newHedgeDocId);
        bind(ps, i++, e.newHedgeRelationId);
        bind(ps, i++, e.executorId);
        bind(ps, i++, e.rebalanceTime);
        bind(ps, i, e.planFeedbackFlag);
    }

    @Override
    protected void readBusiness(ResultSet rs, RebalanceRecord e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.hedgeRelationId = nullableLong(rs, "hedge_relation_id");
        e.rebalanceNo = string(rs, "rebalance_no");
        e.triggerType = string(rs, "trigger_type");
        e.triggerSide = string(rs, "trigger_side");
        e.triggerReason = string(rs, "trigger_reason");
        e.triggerExposureDelta = decimal(rs, "trigger_exposure_delta");
        e.triggerRatioDeviation = decimal(rs, "trigger_ratio_deviation");
        e.triggerThreshold = decimal(rs, "trigger_threshold");
        e.ratioBefore = decimal(rs, "ratio_before");
        e.ratioAfter = decimal(rs, "ratio_after");
        e.qtyBefore = decimal(rs, "qty_before");
        e.qtyAfter = decimal(rs, "qty_after");
        e.newHedgeDocId = nullableLong(rs, "new_hedge_doc_id");
        e.newHedgeRelationId = nullableLong(rs, "new_hedge_relation_id");
        e.executorId = nullableLong(rs, "executor_id");
        e.rebalanceTime = localDateTime(rs, "rebalance_time");
        e.planFeedbackFlag = string(rs, "plan_feedback_flag");
    }

    @Override
    protected Long idOf(RebalanceRecord entity) {
        return entity.id;
    }

    @Override
    protected RebalanceRecord newEntity() {
        return new RebalanceRecord();
    }

    @Override
    protected void applyGeneratedId(RebalanceRecord entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(RebalanceRecord entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_rebalance_record 再平衡记录不得事后修改（R-ACCT-3/ACCT.md §2 数据字典）；"
                        + "复现=对已落库再平衡记录调用 updateById");
    }

    @Override
    public RebalanceRecord findByRebalanceNo(String rebalanceNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE rebalance_no = ?";
        return queryOne(sql, Arrays.asList(rebalanceNo), this::mapRow);
    }

    @Override
    public List<RebalanceRecord> findByHedgeRelation(long hedgeRelationId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE hedge_relation_id = ? ORDER BY rebalance_time ASC, id ASC";
        return queryList(sql, Arrays.asList(hedgeRelationId), this::mapRow);
    }

    @Override
    public List<RebalanceRecord> findByTrigger(String triggerType, String triggerSide) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE trigger_type = ? AND trigger_side = ? ORDER BY rebalance_time ASC, id ASC";
        return queryList(sql, Arrays.asList(triggerType, triggerSide), this::mapRow);
    }
}
