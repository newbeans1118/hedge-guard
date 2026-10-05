package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.LimitUsageStatDao;
import com.hedgeguard.dao.entity.LimitUsageStat;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_limit_usage_stat} 的达梦实现（{@code DATA_MODEL.md} §2.8 TB-RISK-9；{@code FR-RISK-18}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmLimitUsageStatDao extends AbstractDmDao<LimitUsageStat, Long>
        implements LimitUsageStatDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "product_id", "stat_date", "limit_type", "limit_value", "used_value",
            "usage_ratio", "hint_level", "hint_threshold_1", "hint_threshold_2"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmLimitUsageStatDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_limit_usage_stat";
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
    protected void bindBusiness(PreparedStatement ps, LimitUsageStat e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.statDate);
        bind(ps, i++, e.limitType);
        bind(ps, i++, e.limitValue);
        bind(ps, i++, e.usedValue);
        bind(ps, i++, e.usageRatio);
        bind(ps, i++, e.hintLevel);
        bind(ps, i++, e.hintThreshold1);
        bind(ps, i++, e.hintThreshold2);
    }

    @Override
    protected void readBusiness(ResultSet rs, LimitUsageStat e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.productId = nullableLong(rs, "product_id");
        e.statDate = localDate(rs, "stat_date");
        e.limitType = string(rs, "limit_type");
        e.limitValue = decimal(rs, "limit_value");
        e.usedValue = decimal(rs, "used_value");
        e.usageRatio = decimal(rs, "usage_ratio");
        e.hintLevel = string(rs, "hint_level");
        e.hintThreshold1 = decimal(rs, "hint_threshold_1");
        e.hintThreshold2 = decimal(rs, "hint_threshold_2");
    }

    @Override
    protected Long idOf(LimitUsageStat entity) {
        return entity.id;
    }

    @Override
    protected LimitUsageStat newEntity() {
        return new LimitUsageStat();
    }

    @Override
    protected void applyGeneratedId(LimitUsageStat entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<LimitUsageStat> findByDate(LocalDate statDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE stat_date = ? ORDER BY id ASC";
        return queryList(sql, Collections.singletonList((Object) statDate), this::mapRow);
    }
}
