package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.RiskMetricSnapshotDao;
import com.hedgeguard.dao.entity.RiskMetricSnapshot;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_risk_metric_snapshot} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-10；{@code FR-RISK-12}/{@code FR-RISK-13}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmRiskMetricSnapshotDao extends AbstractDmDao<RiskMetricSnapshot, Long>
        implements RiskMetricSnapshotDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "snapshot_time", "snapshot_date", "position_qty_net", "float_pnl",
            "margin_occupied", "margin_ratio", "risk_ratio", "available_fund", "equity",
            "mirror_seq", "snapshot_source"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmRiskMetricSnapshotDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_risk_metric_snapshot";
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
    protected void bindBusiness(PreparedStatement ps, RiskMetricSnapshot e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.snapshotTime);
        bind(ps, i++, e.snapshotDate);
        bind(ps, i++, e.positionQtyNet);
        bind(ps, i++, e.floatPnl);
        bind(ps, i++, e.marginOccupied);
        bind(ps, i++, e.marginRatio);
        bind(ps, i++, e.riskRatio);
        bind(ps, i++, e.availableFund);
        bind(ps, i++, e.equity);
        ps.setLong(i++, e.mirrorSeq);
        bind(ps, i, e.snapshotSource);
    }

    @Override
    protected void readBusiness(ResultSet rs, RiskMetricSnapshot e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.snapshotTime = localDateTime(rs, "snapshot_time");
        e.snapshotDate = localDate(rs, "snapshot_date");
        e.positionQtyNet = decimal(rs, "position_qty_net");
        e.floatPnl = decimal(rs, "float_pnl");
        e.marginOccupied = decimal(rs, "margin_occupied");
        e.marginRatio = decimal(rs, "margin_ratio");
        e.riskRatio = decimal(rs, "risk_ratio");
        e.availableFund = decimal(rs, "available_fund");
        e.equity = decimal(rs, "equity");
        e.mirrorSeq = rs.getLong("mirror_seq");
        e.snapshotSource = string(rs, "snapshot_source");
    }

    @Override
    protected Long idOf(RiskMetricSnapshot entity) {
        return entity.id;
    }

    @Override
    protected RiskMetricSnapshot newEntity() {
        return new RiskMetricSnapshot();
    }

    @Override
    protected void applyGeneratedId(RiskMetricSnapshot entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<RiskMetricSnapshot> findBySubAccount(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? ORDER BY snapshot_time DESC, id DESC";
        return queryList(sql, Collections.singletonList((Object) subAccountId), this::mapRow);
    }
}
