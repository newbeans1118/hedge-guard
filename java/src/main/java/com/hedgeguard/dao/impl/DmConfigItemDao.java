package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ConfigItemDao;
import com.hedgeguard.dao.entity.ConfigItem;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_config_item} 的达梦实现（{@code DATA_MODEL.md} §2.1 TB-CONFIG-2）。
 *
 * <p>敏感字段纪律：本实现允许写入任何取值，但表上的 {@code ck_config_item_sensitive}
 * （{@code is_sensitive='Y' → config_value IS NULL}）在数据库层兜底——即使服务层漏判，
 * 敏感取值也无法落库（{@code NFR-SEC-1} 的结构性保证）。
 */
public final class DmConfigItemDao extends AbstractDmDao<ConfigItem, Long> implements ConfigItemDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "config_key", "config_category", "config_value", "value_type", "enum_scope",
            "min_value", "max_value", "default_value", "is_sensitive", "sensitive_state",
            "persist_scope", "dispatch_to_ctp", "double_review_required", "related_question_no",
            "effective_version", "description"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmConfigItemDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_config_item";
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
    protected void bindBusiness(PreparedStatement ps, ConfigItem e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.configKey);
        bind(ps, i++, e.configCategory);
        bind(ps, i++, e.configValue);
        bind(ps, i++, e.valueType);
        bind(ps, i++, e.enumScope);
        bind(ps, i++, e.minValue);
        bind(ps, i++, e.maxValue);
        bind(ps, i++, e.defaultValue);
        bind(ps, i++, e.isSensitive);
        bind(ps, i++, e.sensitiveState);
        bind(ps, i++, e.persistScope);
        bind(ps, i++, e.dispatchToCtp);
        bind(ps, i++, e.doubleReviewRequired);
        bind(ps, i++, e.relatedQuestionNo);
        ps.setLong(i++, e.effectiveVersion);
        bind(ps, i, e.description);
    }

    @Override
    protected void readBusiness(ResultSet rs, ConfigItem e) throws SQLException {
        e.id = rs.getLong("id");
        e.configKey = string(rs, "config_key");
        e.configCategory = string(rs, "config_category");
        e.configValue = string(rs, "config_value");
        e.valueType = string(rs, "value_type");
        e.enumScope = string(rs, "enum_scope");
        e.minValue = decimal(rs, "min_value");
        e.maxValue = decimal(rs, "max_value");
        e.defaultValue = string(rs, "default_value");
        e.isSensitive = string(rs, "is_sensitive");
        e.sensitiveState = string(rs, "sensitive_state");
        e.persistScope = string(rs, "persist_scope");
        e.dispatchToCtp = string(rs, "dispatch_to_ctp");
        e.doubleReviewRequired = string(rs, "double_review_required");
        e.relatedQuestionNo = string(rs, "related_question_no");
        e.effectiveVersion = rs.getLong("effective_version");
        e.description = string(rs, "description");
    }

    @Override
    protected ConfigItem newEntity() {
        return new ConfigItem();
    }

    @Override
    protected Long idOf(ConfigItem entity) {
        return entity.id;
    }

    @Override
    protected void applyGeneratedId(ConfigItem entity, long generated) {
        entity.id = generated;
    }

    @Override
    public ConfigItem findByKey(String configKey) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE config_key = ?";
        return queryOne(sql, Collections.singletonList(configKey), this::mapRow);
    }

    @Override
    public List<ConfigItem> findByCategory(String category) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE config_category = ? ORDER BY config_key ASC";
        return queryList(sql, Collections.singletonList(category), this::mapRow);
    }

    @Override
    public List<ConfigItem> findDispatchable() {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE dispatch_to_ctp = ? ORDER BY config_key ASC";
        return queryList(sql, Collections.singletonList("Y"), this::mapRow);
    }

    @Override
    public int bumpEffectiveVersion(String configKey) {
        String sql = "UPDATE " + tableName()
                + " SET effective_version = effective_version + 1, updated_time = ?, row_version = row_version + 1"
                + " WHERE config_key = ?";
        return executeUpdateSql(sql, Arrays.asList(
                java.sql.Timestamp.valueOf(com.hedgeguard.common.Clocks.now()), configKey));
    }
}
