package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.CtpFrontConfigDao;
import com.hedgeguard.dao.entity.CtpFrontConfig;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_ctp_front_config} 的达梦实现（{@code DATA_MODEL.md} §2.1 {@code TB-CONFIG-7}；{@code T-109} 补位）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。表结构<b>无任何凭据列</b>（{@code NFR-SEC-1}）。
 *
 * <p>列清单与建表脚本 {@code docs/DEPLOY/schema/m1_config_auth.sql} 逐列一致
 * （由 {@code DaoColumnAlignmentTest} 离线校验）。
 */
public final class DmCtpFrontConfigDao extends AbstractDmDao<CtpFrontConfig, Long>
        implements CtpFrontConfigDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "env_type", "front_type", "front_addr", "priority", "broker_id", "investor_id",
            "connect_timeout_ms", "reconnect_interval_ms", "flow_path", "is_enabled"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmCtpFrontConfigDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_ctp_front_config";
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
    protected void bindBusiness(PreparedStatement ps, CtpFrontConfig e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.envType);
        bind(ps, i++, e.frontType);
        bind(ps, i++, e.frontAddr);
        ps.setInt(i++, e.priority);
        bind(ps, i++, e.brokerId);
        bind(ps, i++, e.investorId);
        ps.setInt(i++, e.connectTimeoutMs);
        ps.setInt(i++, e.reconnectIntervalMs);
        bind(ps, i++, e.flowPath);
        bind(ps, i, e.isEnabled);
    }

    @Override
    protected void readBusiness(ResultSet rs, CtpFrontConfig e) throws SQLException {
        e.id = rs.getLong("id");
        e.envType = string(rs, "env_type");
        e.frontType = string(rs, "front_type");
        e.frontAddr = string(rs, "front_addr");
        e.priority = rs.getInt("priority");
        e.brokerId = string(rs, "broker_id");
        e.investorId = string(rs, "investor_id");
        e.connectTimeoutMs = rs.getInt("connect_timeout_ms");
        e.reconnectIntervalMs = rs.getInt("reconnect_interval_ms");
        e.flowPath = string(rs, "flow_path");
        e.isEnabled = string(rs, "is_enabled");
    }

    @Override
    protected Long idOf(CtpFrontConfig entity) {
        return entity.id;
    }

    @Override
    protected CtpFrontConfig newEntity() {
        return new CtpFrontConfig();
    }

    @Override
    protected void applyGeneratedId(CtpFrontConfig entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<CtpFrontConfig> findEnabledByPriority(String envType, String frontType) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE env_type = ? AND front_type = ? AND is_enabled = ?"
                + " ORDER BY priority ASC, id ASC";
        return queryList(sql, Arrays.asList(envType, frontType, "Y"), this::mapRow);
    }

    @Override
    public boolean existsEnabledProduction() {
        return count("env_type = ? AND is_enabled = ?", Arrays.asList("PRODUCTION", "Y")) > 0;
    }
}
