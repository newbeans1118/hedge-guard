package com.hedgeguard.dao.impl;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ConfigChangeLogDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.dialect.DmDialect;
import com.hedgeguard.dao.entity.ConfigChangeLog;
import com.hedgeguard.dao.tx.TxCallback;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_config_change_log} 的达梦实现（{@code DATA_MODEL.md} §2.1 {@code TB-CONFIG-3}）。
 *
 * <p><b>为何不继承 {@link AbstractDmDao}（本类是全仓唯一的 DAO 例外）</b>：该基类的泛型上界是
 * {@code AbstractDmDao<T extends AuditedEntity, ID>}，且 {@code insert()/updateById()} **硬写**
 * {@code updated_by/updated_time/row_version}；而本表按 {@code §0.3} **表级备注显式排除**了这三列
 * ⇒ 套模板会**写出不存在的列**，实机必然失败。故此处**定制实现**：只写
 * {@code created_by/created_time}（{@code §0.3} 的 3 项审计字段），并**不提供**任何更新路径。
 *
 * <p><b>只增不改不删</b>：{@link #updateById} 显式拒绝；本类**不含**删除方法（接口亦未声明）。
 * {@link #findPage} 由 {@code T-109} 按 {@code PageQuery} + {@link DmDialect#paginate} 补实现
 * （供 {@code GET /config/change-log} 的分类 / 时间范围筛选分页）；本表另有两个用途专用检索
 * （{@link #findByKeyBetween} / {@link #findDispatchProblems}）。
 */
public final class DmConfigChangeLogDao implements ConfigChangeLogDao {

    /** 业务列（不含主键与审计字段；本表审计字段仅 {@code created_by}/{@code created_time}）。 */
    private static final String[] COLUMNS = {
            "config_key", "config_category", "before_value", "after_value", "is_sensitive_change",
            "sensitive_state_before", "sensitive_state_after", "operator_id", "reviewer_id",
            "change_reason", "dispatch_result", "dispatch_param_version", "change_time"
    };

    private static final String TABLE = "t_config_change_log";

    /** 方言（默认 DM8；分页与计数经此生成，DM 特性不外泄）。 */
    private static final DmDialect DIALECT = new DmDialect();

    private final TxTemplate tx;

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmConfigChangeLogDao(TxTemplate tx) {
        this.tx = tx;
    }

    @Override
    public int insert(ConfigChangeLog e, long operatorId) {
        // 审计：本表只有 created_by / created_time（无 updated_*、无 row_version）
        e.createdBy = operatorId;
        e.createdTime = Clocks.now();
        StringBuilder cols = new StringBuilder();
        for (String c : COLUMNS) {
            cols.append(c).append(", ");
        }
        cols.append("created_by, created_time");
        int paramCount = COLUMNS.length + 2;
        String sql = "INSERT INTO " + TABLE + " (" + cols + ") VALUES ("
                + placeholders(paramCount) + ")";
        return tx.execute((TxCallback<Integer>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql,
                    Statement.RETURN_GENERATED_KEYS)) {
                int i = 1;
                ps.setString(i++, e.configKey);
                ps.setString(i++, e.configCategory);
                ps.setString(i++, e.beforeValue);
                ps.setString(i++, e.afterValue);
                ps.setString(i++, e.isSensitiveChange);
                ps.setString(i++, e.sensitiveStateBefore);
                ps.setString(i++, e.sensitiveStateAfter);
                ps.setLong(i++, e.operatorId);
                setNullableLong(ps, i++, e.reviewerId);
                ps.setString(i++, e.changeReason);
                ps.setString(i++, e.dispatchResult);
                setNullableLong(ps, i++, e.dispatchParamVersion);
                ps.setTimestamp(i++, Timestamp.valueOf(e.changeTime));
                ps.setLong(i++, e.createdBy);
                ps.setTimestamp(i, Timestamp.valueOf(e.createdTime));
                int affected = ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        e.id = keys.getLong(1);
                    }
                }
                return affected;
            }
        });
    }

    @Override
    public int updateById(ConfigChangeLog entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_config_change_log 只增不改不删（配置变更留痕不可篡改，R-AUDIT-2）；"
                        + "复现=检查调用点 ConfigChangeLogDao.updateById");
    }

    @Override
    public ConfigChangeLog findById(Long id) {
        String sql = "SELECT " + selectList() + " FROM " + TABLE + " WHERE id = ?";
        return tx.execute((TxCallback<ConfigChangeLog>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapRow(rs) : null;
                }
            }
        });
    }

    @Override
    public PageResult<ConfigChangeLog> findPage(PageQuery query, String where,
                                                List<Object> whereArgs) {
        // 通用分页实现（T-109 补位）：本表按 §0.3 排除 updated_* / row_version，
        // 故不复用 AbstractDmDao（其 selectList/mapRow 假设存在这三列），此处按需自实现。
        // 分页占位符顺序与 DmDialect.paginate 追加顺序一致：LIMIT（size）在前、OFFSET 在后。
        String base = "SELECT " + selectList() + " FROM " + TABLE
                + (where == null || where.isEmpty() ? "" : " WHERE " + where)
                + " ORDER BY " + query.sortField() + " " + query.direction();
        long total = count(where, whereArgs);
        List<Object> args = new ArrayList<>(whereArgs == null ? Collections.emptyList() : whereArgs);
        args.add(query.size());
        args.add(query.offset());
        List<ConfigChangeLog> items = queryList(DIALECT.paginate(base, query), args);
        return new PageResult<>(items, total, query.page(), query.size());
    }

    @Override
    public long count(String where, List<Object> whereArgs) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(TABLE);
        List<Object> args = new ArrayList<>();
        if (where != null && !where.isEmpty()) {
            sql.append(" WHERE ").append(where);
            if (whereArgs != null) {
                args.addAll(whereArgs);
            }
        }
        Long n = tx.execute((TxCallback<Long>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                bindArgs(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
        return n == null ? 0L : n;
    }

    @Override
    public List<ConfigChangeLog> findByKeyBetween(String configKey, LocalDateTime from,
                                                  LocalDateTime to) {
        String sql = "SELECT " + selectList() + " FROM " + TABLE
                + " WHERE config_key = ? AND change_time BETWEEN ? AND ?"
                + " ORDER BY change_time DESC, id DESC";
        return queryList(sql, Arrays.asList(configKey, from, to));
    }

    @Override
    public List<ConfigChangeLog> findDispatchProblems() {
        String sql = "SELECT " + selectList() + " FROM " + TABLE
                + " WHERE dispatch_result IN (?, ?) ORDER BY change_time DESC, id DESC";
        return queryList(sql, Arrays.asList("FAIL", "UNCONFIRMED"));
    }

    // ------------------------------------------------------------------ 内部工具（本类不继承基类，故自带最小集）

    private String selectList() {
        StringBuilder sb = new StringBuilder("id");
        for (String c : COLUMNS) {
            sb.append(", ").append(c);
        }
        sb.append(", created_by, created_time");
        return sb.toString();
    }

    private List<ConfigChangeLog> queryList(String sql, List<Object> args) {
        return tx.execute((TxCallback<List<ConfigChangeLog>>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindArgs(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ConfigChangeLog> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapRow(rs));
                    }
                    return out;
                }
            }
        });
    }

    private ConfigChangeLog mapRow(ResultSet rs) throws SQLException {
        ConfigChangeLog e = new ConfigChangeLog();
        e.id = rs.getLong("id");
        e.configKey = rs.getString("config_key");
        e.configCategory = rs.getString("config_category");
        e.beforeValue = rs.getString("before_value");
        e.afterValue = rs.getString("after_value");
        e.isSensitiveChange = rs.getString("is_sensitive_change");
        e.sensitiveStateBefore = rs.getString("sensitive_state_before");
        e.sensitiveStateAfter = rs.getString("sensitive_state_after");
        e.operatorId = rs.getLong("operator_id");
        e.reviewerId = nullableLong(rs, "reviewer_id");
        e.changeReason = rs.getString("change_reason");
        e.dispatchResult = rs.getString("dispatch_result");
        e.dispatchParamVersion = nullableLong(rs, "dispatch_param_version");
        e.changeTime = nullableDateTime(rs, "change_time");
        e.createdBy = rs.getLong("created_by");
        e.createdTime = nullableDateTime(rs, "created_time");
        return e;
    }

    private static void bindArgs(PreparedStatement ps, List<Object> args) throws SQLException {
        if (args == null) {
            return;
        }
        int i = 1;
        for (Object a : args) {
            if (a == null) {
                ps.setNull(i++, Types.NULL);
            } else if (a instanceof String) {
                ps.setString(i++, (String) a);
            } else if (a instanceof Integer) {
                ps.setInt(i++, (Integer) a);
            } else if (a instanceof Long) {
                ps.setLong(i++, (Long) a);
            } else if (a instanceof LocalDateTime) {
                ps.setTimestamp(i++, Timestamp.valueOf((LocalDateTime) a));
            } else {
                ps.setObject(i++, a);
            }
        }
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long value)
            throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDateTime nullableDateTime(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.toString();
    }

    /** 供单测断言"未写出不存在的列"（本表无 {@code updated_*}/{@code row_version}）。 */
    static List<String> writtenColumns() {
        List<String> cols = new ArrayList<>(Arrays.asList(COLUMNS));
        cols.add("created_by");
        cols.add("created_time");
        return Collections.unmodifiableList(cols);
    }
}
