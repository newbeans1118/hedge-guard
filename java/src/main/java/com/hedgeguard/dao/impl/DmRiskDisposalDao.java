package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.RiskDisposalDao;
import com.hedgeguard.dao.entity.RiskDisposal;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_risk_disposal} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-5；{@code FR-RISK-15}/{@code FR-RISK-16}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmRiskDisposalDao extends AbstractDmDao<RiskDisposal, Long>
        implements RiskDisposalDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "is_global", "disposal_type", "scan_scope", "reason",
            "hint_instrument_id", "hint_direction", "hint_volume", "hint_accepted", "reject_reason",
            "operator_id", "disposal_time", "dispatch_status", "dispatch_version", "released_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmRiskDisposalDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_risk_disposal";
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
    protected void bindBusiness(PreparedStatement ps, RiskDisposal e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.isGlobal);
        bind(ps, i++, e.disposalType);
        bind(ps, i++, e.scanScope);
        bind(ps, i++, e.reason);
        bind(ps, i++, e.hintInstrumentId);
        bind(ps, i++, e.hintDirection);
        bind(ps, i++, e.hintVolume);
        bind(ps, i++, e.hintAccepted);
        bind(ps, i++, e.rejectReason);
        ps.setLong(i++, e.operatorId);
        bind(ps, i++, e.disposalTime);
        bind(ps, i++, e.dispatchStatus);
        bind(ps, i++, e.dispatchVersion);
        bind(ps, i, e.releasedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, RiskDisposal e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.isGlobal = string(rs, "is_global");
        e.disposalType = string(rs, "disposal_type");
        e.scanScope = string(rs, "scan_scope");
        e.reason = string(rs, "reason");
        e.hintInstrumentId = string(rs, "hint_instrument_id");
        e.hintDirection = string(rs, "hint_direction");
        e.hintVolume = nullableInt(rs, "hint_volume");
        e.hintAccepted = string(rs, "hint_accepted");
        e.rejectReason = string(rs, "reject_reason");
        e.operatorId = rs.getLong("operator_id");
        e.disposalTime = localDateTime(rs, "disposal_time");
        e.dispatchStatus = string(rs, "dispatch_status");
        e.dispatchVersion = nullableLong(rs, "dispatch_version");
        e.releasedTime = localDateTime(rs, "released_time");
    }

    @Override
    protected Long idOf(RiskDisposal entity) {
        return entity.id;
    }

    @Override
    protected RiskDisposal newEntity() {
        return new RiskDisposal();
    }

    @Override
    protected void applyGeneratedId(RiskDisposal entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<RiskDisposal> findActiveBySubAccount(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? AND released_time IS NULL ORDER BY id DESC";
        return queryList(sql, Collections.singletonList((Object) subAccountId), this::mapRow);
    }
}
