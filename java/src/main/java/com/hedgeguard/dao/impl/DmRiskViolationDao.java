package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.RiskViolationDao;
import com.hedgeguard.dao.entity.RiskViolation;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_risk_violation} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-8；{@code FR-RISK-18}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmRiskViolationDao extends AbstractDmDao<RiskViolation, Long>
        implements RiskViolationDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "violation_date", "violation_type", "severity", "rule_id",
            "trade_order_id", "trade_fill_id", "hedge_plan_id", "violation_desc",
            "trade_reason_text", "source", "occur_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmRiskViolationDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_risk_violation";
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
    protected void bindBusiness(PreparedStatement ps, RiskViolation e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.violationDate);
        bind(ps, i++, e.violationType);
        bind(ps, i++, e.severity);
        bind(ps, i++, e.ruleId);
        bind(ps, i++, e.tradeOrderId);
        bind(ps, i++, e.tradeFillId);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.violationDesc);
        bind(ps, i++, e.tradeReasonText);
        bind(ps, i++, e.source);
        bind(ps, i++, e.occurTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, RiskViolation e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.violationDate = localDate(rs, "violation_date");
        e.violationType = string(rs, "violation_type");
        e.severity = string(rs, "severity");
        e.ruleId = string(rs, "rule_id");
        e.tradeOrderId = nullableLong(rs, "trade_order_id");
        e.tradeFillId = nullableLong(rs, "trade_fill_id");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.violationDesc = string(rs, "violation_desc");
        e.tradeReasonText = string(rs, "trade_reason_text");
        e.source = string(rs, "source");
        e.occurTime = localDateTime(rs, "occur_time");
    }

    @Override
    protected Long idOf(RiskViolation entity) {
        return entity.id;
    }

    @Override
    protected RiskViolation newEntity() {
        return new RiskViolation();
    }

    @Override
    protected void applyGeneratedId(RiskViolation entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<RiskViolation> findByDate(LocalDate violationDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE violation_date = ? ORDER BY id ASC";
        return queryList(sql, Collections.singletonList((Object) violationDate), this::mapRow);
    }

    @Override
    public long countByNaturalKey(int subAccountId, LocalDate violationDate, String violationType,
                                  String ruleId, String source) {
        StringBuilder where = new StringBuilder(
                "sub_account_id = ? AND violation_date = ? AND violation_type = ? AND source = ?");
        List<Object> args = new ArrayList<>(Arrays.asList(subAccountId, violationDate, violationType, source));
        if (ruleId == null) {
            where.append(" AND rule_id IS NULL");
        } else {
            where.append(" AND rule_id = ?");
            args.add(ruleId);
        }
        return count(where.toString(), args);
    }
}
