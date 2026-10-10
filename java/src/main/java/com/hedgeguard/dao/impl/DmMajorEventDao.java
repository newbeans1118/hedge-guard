package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.MajorEventDao;
import com.hedgeguard.dao.entity.MajorEvent;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_major_event} 的达梦实现（{@code DATA_MODEL.md} §2.9 TB-AUDIT-8；{@code FR-AUDIT-7}）。
 *
 * <p>本类是唯一出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmMajorEventDao extends AbstractDmDao<MajorEvent, Long> implements MajorEventDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "event_no", "event_type", "trigger_source", "event_desc",
            "impact_scope", "handling_measures", "rectification_plan", "status",
            "audit_report_id", "triggered_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmMajorEventDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_major_event";
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
    protected void bindBusiness(PreparedStatement ps, MajorEvent e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.eventNo);
        bind(ps, i++, e.eventType);
        bind(ps, i++, e.triggerSource);
        bind(ps, i++, e.eventDesc);
        bind(ps, i++, e.impactScope);
        bind(ps, i++, e.handlingMeasures);
        bind(ps, i++, e.rectificationPlan);
        bind(ps, i++, e.status);
        bind(ps, i++, e.auditReportId);
        bind(ps, i, e.triggeredTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, MajorEvent e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.eventNo = string(rs, "event_no");
        e.eventType = string(rs, "event_type");
        e.triggerSource = string(rs, "trigger_source");
        e.eventDesc = string(rs, "event_desc");
        e.impactScope = string(rs, "impact_scope");
        e.handlingMeasures = string(rs, "handling_measures");
        e.rectificationPlan = string(rs, "rectification_plan");
        e.status = string(rs, "status");
        e.auditReportId = nullableLong(rs, "audit_report_id");
        e.triggeredTime = localDateTime(rs, "triggered_time");
    }

    @Override
    protected Long idOf(MajorEvent entity) {
        return entity.id;
    }

    @Override
    protected MajorEvent newEntity() {
        return new MajorEvent();
    }

    @Override
    protected void applyGeneratedId(MajorEvent entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<MajorEvent> findByTypeAndStatus(String eventType, String status) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE event_type = ? AND status = ?"
                + " ORDER BY triggered_time ASC, id ASC";
        return queryList(sql, Arrays.<Object>asList(eventType, status), this::mapRow);
    }

    @Override
    public MajorEvent findByEventNo(String eventNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE event_no = ?";
        return queryOne(sql, java.util.Collections.singletonList(eventNo), this::mapRow);
    }
}
