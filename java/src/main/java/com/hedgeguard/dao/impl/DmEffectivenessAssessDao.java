package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.EffectivenessAssessDao;
import com.hedgeguard.dao.entity.EffectivenessAssess;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_effectiveness_assess} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-2）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 *
 * <p><b>不得事后修改</b>（R-ACCT-2：评估记录不存在编辑入口，更正须新增记录并留痕）：
 * {@link #updateById} 显式拒绝。
 */
public final class DmEffectivenessAssessDao extends AbstractDmDao<EffectivenessAssess, Long>
        implements EffectivenessAssessDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "hedge_relation_id", "period_key", "assess_method",
            "observer_linkage", "linkage_note", "observer_direction", "observer_term",
            "exposure_expected_date", "term_deviation_days", "monitor_deviation_ratio",
            "monitor_review_flag", "observer_detail", "conclusion", "need_action_flag",
            "action_note", "assessor_id", "assess_time", "corrected_from_id", "is_corrected"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmEffectivenessAssessDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_effectiveness_assess";
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
    protected void bindBusiness(PreparedStatement ps, EffectivenessAssess e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.hedgeRelationId);
        bind(ps, i++, e.periodKey);
        bind(ps, i++, e.assessMethod);
        bind(ps, i++, e.observerLinkage);
        bind(ps, i++, e.linkageNote);
        bind(ps, i++, e.observerDirection);
        bind(ps, i++, e.observerTerm);
        bind(ps, i++, e.exposureExpectedDate);
        bind(ps, i++, e.termDeviationDays);
        bind(ps, i++, e.monitorDeviationRatio);
        bind(ps, i++, e.monitorReviewFlag);
        bindClob(ps, i++, e.observerDetail);
        bind(ps, i++, e.conclusion);
        bind(ps, i++, e.needActionFlag);
        bind(ps, i++, e.actionNote);
        bind(ps, i++, e.assessorId);
        bind(ps, i++, e.assessTime);
        bind(ps, i++, e.correctedFromId);
        bind(ps, i, e.isCorrected);
    }

    @Override
    protected void readBusiness(ResultSet rs, EffectivenessAssess e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.hedgeRelationId = nullableLong(rs, "hedge_relation_id");
        e.periodKey = string(rs, "period_key");
        e.assessMethod = string(rs, "assess_method");
        e.observerLinkage = string(rs, "observer_linkage");
        e.linkageNote = string(rs, "linkage_note");
        e.observerDirection = string(rs, "observer_direction");
        e.observerTerm = string(rs, "observer_term");
        e.exposureExpectedDate = localDate(rs, "exposure_expected_date");
        e.termDeviationDays = nullableInt(rs, "term_deviation_days");
        e.monitorDeviationRatio = decimal(rs, "monitor_deviation_ratio");
        e.monitorReviewFlag = string(rs, "monitor_review_flag");
        e.observerDetail = clob(rs, "observer_detail");
        e.conclusion = string(rs, "conclusion");
        e.needActionFlag = string(rs, "need_action_flag");
        e.actionNote = string(rs, "action_note");
        e.assessorId = nullableLong(rs, "assessor_id");
        e.assessTime = localDateTime(rs, "assess_time");
        e.correctedFromId = nullableLong(rs, "corrected_from_id");
        e.isCorrected = string(rs, "is_corrected");
    }

    @Override
    protected Long idOf(EffectivenessAssess entity) {
        return entity.id;
    }

    @Override
    protected EffectivenessAssess newEntity() {
        return new EffectivenessAssess();
    }

    @Override
    protected void applyGeneratedId(EffectivenessAssess entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(EffectivenessAssess entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_effectiveness_assess 评估记录不得事后修改（R-ACCT-2/FR-ACCT-2 验收 3：更正须新增记录并留痕）；"
                        + "复现=对已落库评估记录调用 updateById");
    }

    @Override
    public EffectivenessAssess findActiveByRelationAndPeriod(long hedgeRelationId, String periodKey) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE hedge_relation_id = ? AND period_key = ? AND is_corrected = ?";
        return queryOne(sql, Arrays.asList(hedgeRelationId, periodKey, "N"), this::mapRow);
    }

    @Override
    public List<EffectivenessAssess> findBySubAccountAndPeriod(int subAccountId, String periodKey) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND period_key = ? ORDER BY assess_time ASC, id ASC";
        return queryList(sql, Arrays.asList(subAccountId, periodKey), this::mapRow);
    }

    @Override
    public List<EffectivenessAssess> findByPeriodAndConclusion(String periodKey, String conclusion) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE period_key = ? AND conclusion = ? ORDER BY assess_time ASC, id ASC";
        return queryList(sql, Arrays.asList(periodKey, conclusion), this::mapRow);
    }

    @Override
    public List<EffectivenessAssess> findMonitorFlaggedByPeriod(String periodKey) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE period_key = ? AND monitor_review_flag = ? ORDER BY assess_time ASC, id ASC";
        return queryList(sql, Arrays.asList(periodKey, "Y"), this::mapRow);
    }
}
