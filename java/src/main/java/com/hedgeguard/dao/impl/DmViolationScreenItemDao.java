package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ViolationScreenItemDao;
import com.hedgeguard.dao.entity.ViolationScreenItem;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_violation_screen_item} 的达梦实现（{@code DATA_MODEL.md} §2.9 TB-AUDIT-5；{@code FR-AUDIT-4}）。
 *
 * <p>本类是唯一出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmViolationScreenItemDao extends AbstractDmDao<ViolationScreenItem, Long>
        implements ViolationScreenItemDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "screen_run_id", "violation_type", "severity", "rule_id",
            "object_type", "object_id", "occur_time", "violation_desc", "trade_reason_id",
            "related_object_info"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmViolationScreenItemDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_violation_screen_item";
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
    protected void bindBusiness(PreparedStatement ps, ViolationScreenItem e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.screenRunId);
        bind(ps, i++, e.violationType);
        bind(ps, i++, e.severity);
        bind(ps, i++, e.ruleId);
        bind(ps, i++, e.objectType);
        bind(ps, i++, e.objectId);
        bind(ps, i++, e.occurTime);
        bind(ps, i++, e.violationDesc);
        bind(ps, i++, e.tradeReasonId);
        bind(ps, i, e.relatedObjectInfo);
    }

    @Override
    protected void readBusiness(ResultSet rs, ViolationScreenItem e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = rs.getInt("sub_account_id");
        e.screenRunId = rs.getLong("screen_run_id");
        e.violationType = string(rs, "violation_type");
        e.severity = string(rs, "severity");
        e.ruleId = string(rs, "rule_id");
        e.objectType = string(rs, "object_type");
        e.objectId = rs.getLong("object_id");
        e.occurTime = localDateTime(rs, "occur_time");
        e.violationDesc = string(rs, "violation_desc");
        e.tradeReasonId = nullableLong(rs, "trade_reason_id");
        e.relatedObjectInfo = string(rs, "related_object_info");
    }

    @Override
    protected Long idOf(ViolationScreenItem entity) {
        return entity.id;
    }

    @Override
    protected ViolationScreenItem newEntity() {
        return new ViolationScreenItem();
    }

    @Override
    protected void applyGeneratedId(ViolationScreenItem entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<ViolationScreenItem> findByScreenRunAndType(long screenRunId, String violationType) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE screen_run_id = ? AND violation_type = ?"
                + " ORDER BY id ASC";
        return queryList(sql, Arrays.<Object>asList(screenRunId, violationType), this::mapRow);
    }
}
