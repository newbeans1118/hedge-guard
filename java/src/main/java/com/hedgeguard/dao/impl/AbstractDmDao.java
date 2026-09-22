package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.AuditFields;
import com.hedgeguard.dao.BaseDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.RowMapper;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.dialect.Dialect;
import com.hedgeguard.dao.dialect.DmDialect;
import com.hedgeguard.dao.entity.AuditedEntity;
import com.hedgeguard.dao.tx.TxCallback;
import com.hedgeguard.dao.tx.TxTemplate;

import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * DAO 基类实现（{@code ARCHITECTURE.md} §3.9「{@code dao/impl/Dm<Entity>Dao.java} —— 唯一出现 SQL 文件的位置」）。
 *
 * <p>提供通用能力，使各表 DAO 只需声明<b>表名 / 列 / 绑定 / 读取</b>四件事：
 * <ul>
 *   <li>通用审计字段自动填充（{@code §0.3}）：插入写 {@code created_by/created_time}，
 *       更新写 {@code updated_by/updated_time} 并推进 {@code row_version}；</li>
 *   <li>乐观锁更新（{@code WHERE id = ? AND row_version = ?}）；</li>
 *   <li>分页与计数经 {@link com.hedgeguard.dao.dialect.Dialect} 生成（DM 特性不外泄）；</li>
 *   <li>连接借还一律经 {@link TxTemplate}——<b>本类与子类不得调用 {@code Connection.close()}</b>。</li>
 * </ul>
 *
 * @param <T>  实体类型
 * @param <ID> 主键类型
 */
public abstract class AbstractDmDao<T extends AuditedEntity, ID> implements BaseDao<T, ID> {

    /** 事务模板（L3 开启事务后，本层自动加入）。 */
    protected final TxTemplate tx;

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    protected AbstractDmDao(TxTemplate tx) {
        this.tx = tx;
    }

    /** 表名（如 {@code t_sub_account}）。 */
    protected abstract String tableName();

    /** 主键列名（如 {@code id}）。 */
    protected abstract String idColumn();

    /** 业务列（不含主键与通用审计字段），顺序即 SQL 列顺序。 */
    protected abstract String[] businessColumns();

    /**
     * 绑定业务列取值。
     *
     * @param ps    预处理语句
     * @param entity 实体
     * @param start 起始参数下标（从 1 起）
     * @throws SQLException 绑定失败
     */
    protected abstract void bindBusiness(PreparedStatement ps, T entity, int start) throws SQLException;

    /**
     * 读取业务列取值（<b>还须读取主键</b>并写入实体自身的 id 字段）。
     *
     * <p>为何主键也在此读取：本类刻意不假设主键在实体中的存放位置与类型
     * （{@code t_sub_account.id} 为 {@code INT}，其余 110 张表为 {@code BIGINT}），
     * 由各表 DAO 一次读全，避免为类型差异引入反射或泛型技巧。
     *
     * @param rs     结果集
     * @param entity 待填充实体
     * @throws SQLException 读取失败
     */
    protected abstract void readBusiness(ResultSet rs, T entity) throws SQLException;

    /** 取实体主键。 */
    protected abstract ID idOf(T entity);

    /** 新建空实体。 */
    protected abstract T newEntity();

    /**
     * 回填自增主键。
     *
     * @param entity    实体
     * @param generated 数据库生成的主键
     */
    protected void applyGeneratedId(T entity, long generated) {
        throw new UnsupportedOperationException(
                "表 " + tableName() + " 未实现自增主键回填；复现=在该 DAO 中覆写 applyGeneratedId");
    }

    @Override
    public int insert(T entity, long operatorId) {
        AuditFields.applyInsert(entity, operatorId);
        String[] biz = businessColumns();
        String sql = buildInsertSql(biz);
        return tx.execute((TxCallback<Integer>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bindBusiness(ps, entity, 1);
                int idx = 1 + biz.length;
                ps.setLong(idx++, entity.createdBy);
                ps.setTimestamp(idx++, Timestamp.valueOf(entity.createdTime));
                ps.setInt(idx, entity.rowVersion);
                int affected = ps.executeUpdate();
                readGeneratedKey(ps, entity);
                return affected;
            }
        });
    }

    @Override
    public int updateById(T entity, long operatorId) {
        AuditFields.applyUpdate(entity, operatorId);
        String[] biz = businessColumns();
        String sql = buildUpdateSql(biz);
        int affected = tx.execute((TxCallback<Integer>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindBusiness(ps, entity, 1);
                int idx = 1 + biz.length;
                ps.setLong(idx++, entity.updatedBy);
                ps.setTimestamp(idx++, Timestamp.valueOf(entity.updatedTime));
                ps.setObject(idx++, idOf(entity));
                ps.setInt(idx, entity.rowVersion);
                return ps.executeUpdate();
            }
        });
        if (affected == 1) {
            AuditFields.advanceVersion(entity);
        }
        return affected;
    }

    @Override
    public T findById(ID id) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE " + idColumn() + " = ?";
        List<T> rows = queryList(sql, Collections.singletonList(id), this::mapRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public PageResult<T> findPage(PageQuery query, String where, List<Object> whereArgs) {
        // ORDER BY 装配点：查询未含本表唯一列（主键 idColumn()）时，自动追加其升序收官，
        // 保证全序 ⇒ 同排序值跨页不重复 / 不漏行（T-096 边界修订；见 PageQuery.orderByEnsuringUnique）。
        String base = "SELECT " + selectList() + " FROM " + tableName()
                + (where == null || where.isEmpty() ? "" : " WHERE " + where)
                + " ORDER BY " + query.orderByEnsuringUnique(idColumn());
        long total = count(where, whereArgs);
        List<Object> args = new ArrayList<>(whereArgs == null ? Collections.emptyList() : whereArgs);
        // 分页占位符顺序与 DmDialect.paginate 的追加顺序一致：LIMIT（size）在前、OFFSET 在后
        args.add(query.size());
        args.add(query.offset());
        List<T> items = queryList(dialect().paginate(base, query), args, this::mapRow);
        return new PageResult<>(items, total, query.page(), query.size());
    }

    @Override
    public long count(String where, List<Object> whereArgs) {
        String base = "SELECT " + idColumn() + " FROM " + tableName()
                + (where == null || where.isEmpty() ? "" : " WHERE " + where);
        String sql = dialect().countSql(base);
        Long n = tx.execute((TxCallback<Long>) connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindArgs(ps, whereArgs);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
        return n == null ? 0L : n;
    }

    /**
     * 执行自定义查询并映射多行（子类实现专用查询时使用）。
     *
     * @param sql    语句
     * @param args   参数
     * @param mapper 行映射
     * @param <R>    结果类型
     * @return 结果列表
     */
    protected <R> List<R> queryList(String sql, List<Object> args, RowMapper<R> mapper) {
        return tx.execute(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindArgs(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<R> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                    return out;
                }
            }
        });
    }

    /**
     * 执行自定义查询并映射单行。
     *
     * @param sql    语句
     * @param args   参数
     * @param mapper 行映射
     * @param <R>    结果类型
     * @return 结果；无行返回 {@code null}
     */
    protected <R> R queryOne(String sql, List<Object> args, RowMapper<R> mapper) {
        List<R> rows = queryList(sql, args, mapper);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 执行写语句（子类实现专用动作时使用）。
     *
     * @param sql  语句
     * @param args 参数
     * @return 影响行数
     */
    protected int executeUpdateSql(String sql, List<Object> args) {
        return tx.execute(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindArgs(ps, args);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * 按子账户权限追加过滤条件后的分页查询（{@code NFR-SEC-7}「DAO 统一注入」）。
     *
     * @param query 分页
     * @param scope 子账户过滤（{@link com.hedgeguard.dao.SubAccountScope}）
     * @param extraWhere 附加条件（可为 {@code null}）
     * @param extraArgs 附加条件参数
     * @return 分页结果
     */
    protected PageResult<T> findPageScoped(PageQuery query, SubAccountScope scope,
                                           String extraWhere, List<Object> extraArgs) {
        List<Object> args = new ArrayList<>();
        String where = scope.sql();
        args.addAll(scope.params());
        if (extraWhere != null && !extraWhere.isEmpty()) {
            where = "(" + extraWhere + ") AND " + where;
            args.addAll(0, extraArgs == null ? Collections.emptyList() : extraArgs);
        }
        return findPage(query, where, args);
    }

    /** 完整列清单（主键 + 业务列 + 审计列）。 */
    protected String selectList() {
        return idColumn() + ", " + String.join(", ", businessColumns())
                + ", created_by, created_time, updated_by, updated_time, row_version";
    }

    /** 方言（默认 DM8；换库时替换实现即可）。 */
    protected Dialect dialect() {
        return DIALECT;
    }

    private static final Dialect DIALECT = new DmDialect();

    private String buildInsertSql(String[] biz) {
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (String c : biz) {
            cols.append(c).append(", ");
            marks.append("?, ");
        }
        return "INSERT INTO " + tableName() + " (" + cols
                + "created_by, created_time, row_version) VALUES (" + marks + "?, ?, ?)";
    }

    private String buildUpdateSql(String[] biz) {
        StringBuilder sets = new StringBuilder();
        for (String c : biz) {
            sets.append(c).append(" = ?, ");
        }
        sets.append("updated_by = ?, updated_time = ?, row_version = row_version + 1");
        return "UPDATE " + tableName() + " SET " + sets + " WHERE " + idColumn()
                + " = ? AND row_version = ?";
    }

    private void readGeneratedKey(PreparedStatement ps, T entity) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys != null && keys.next()) {
                applyGeneratedId(entity, keys.getLong(1));
            }
        }
    }

    /**
     * 通用行映射（主键 + 业务列 + 审计列）。
     *
     * @param rs 结果集
     * @return 实体
     * @throws SQLException 读取失败
     */
    protected T mapRow(ResultSet rs) throws SQLException {
        T entity = newEntity();
        readBusiness(rs, entity);
        entity.createdBy = rs.getLong("created_by");
        entity.createdTime = localDateTime(rs.getTimestamp("created_time"));
        long updatedBy = rs.getLong("updated_by");
        entity.updatedBy = rs.wasNull() ? null : updatedBy;
        entity.updatedTime = localDateTime(rs.getTimestamp("updated_time"));
        entity.rowVersion = rs.getInt("row_version");
        return entity;
    }

    /**
     * 绑定参数列表（{@code null} 以 {@link Types#NULL} 处理）。
     *
     * @param ps   语句
     * @param args 参数
     * @throws SQLException 绑定失败
     */
    protected static void bindArgs(PreparedStatement ps, List<Object> args) throws SQLException {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.size(); i++) {
            Object v = args.get(i);
            int idx = i + 1;
            if (v == null) {
                ps.setNull(idx, Types.NULL);
            } else if (v instanceof String) {
                ps.setString(idx, (String) v);
            } else if (v instanceof Integer) {
                ps.setInt(idx, (Integer) v);
            } else if (v instanceof Long) {
                ps.setLong(idx, (Long) v);
            } else if (v instanceof BigDecimal) {
                ps.setBigDecimal(idx, (BigDecimal) v);
            } else if (v instanceof LocalDate) {
                ps.setDate(idx, java.sql.Date.valueOf((LocalDate) v));
            } else if (v instanceof LocalDateTime) {
                ps.setTimestamp(idx, Timestamp.valueOf((LocalDateTime) v));
            } else {
                ps.setObject(idx, v);
            }
        }
    }

    // ---------- 取值/赋值工具（统一 null 语义，避免各 DAO 各写一套） ----------

    /**
     * 读可空字符串。
     *
     * @param rs  结果集
     * @param col 列名
     * @return 值或 {@code null}
     * @throws SQLException 读取失败
     */
    protected static String string(ResultSet rs, String col) throws SQLException {
        return rs.getString(col);
    }

    /**
     * 读可空 {@code BIGINT}。
     *
     * @param rs  结果集
     * @param col 列名
     * @return 值或 {@code null}
     * @throws SQLException 读取失败
     */
    protected static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    /**
     * 读可空 {@code INT}。
     *
     * @param rs  结果集
     * @param col 列名
     * @return 值或 {@code null}
     * @throws SQLException 读取失败
     */
    protected static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    /**
     * 读 {@code DECIMAL}。
     *
     * @param rs  结果集
     * @param col 列名
     * @return 值或 {@code null}
     * @throws SQLException 读取失败
     */
    protected static BigDecimal decimal(ResultSet rs, String col) throws SQLException {
        return rs.getBigDecimal(col);
    }

    /**
     * 读 {@code DATE}。
     *
     * @param rs  结果集
     * @param col 列名
     * @return 值或 {@code null}
     * @throws SQLException 读取失败
     */
    protected static LocalDate localDate(ResultSet rs, String col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    /**
     * 读 {@code TIMESTAMP}。
     *
     * @param rs  结果集
     * @param col 列名
     * @return 值或 {@code null}
     * @throws SQLException 读取失败
     */
    protected static LocalDateTime localDateTime(ResultSet rs, String col) throws SQLException {
        return localDateTime(rs.getTimestamp(col));
    }

    private static LocalDateTime localDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * 读 {@code CLOB}（使用标准 {@code getCharacterStream}，{@code DATA_MODEL.md} §6.1 D7）。
     *
     * @param rs  结果集
     * @param col 列名
     * @return 文本或 {@code null}
     * @throws SQLException 读取失败
     */
    protected static String clob(ResultSet rs, String col) throws SQLException {
        Reader reader = rs.getCharacterStream(col);
        if (reader == null) {
            return null;
        }
        try (Reader r = reader) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (java.io.IOException e) {
            throw new SQLException("读取 CLOB 失败：列=" + col + "；原因=" + e.getMessage(), e);
        }
    }

    /**
     * 绑定可空字符串。
     *
     * @param ps    语句
     * @param idx   参数下标
     * @param value 值
     * @throws SQLException 绑定失败
     */
    protected static void bind(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.VARCHAR);
        } else {
            ps.setString(idx, value);
        }
    }

    /**
     * 绑定可空整数。
     *
     * @param ps    语句
     * @param idx   参数下标
     * @param value 值
     * @throws SQLException 绑定失败
     */
    protected static void bind(PreparedStatement ps, int idx, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.INTEGER);
        } else {
            ps.setInt(idx, value);
        }
    }

    /**
     * 绑定可空长整型。
     *
     * @param ps    语句
     * @param idx   参数下标
     * @param value 值
     * @throws SQLException 绑定失败
     */
    protected static void bind(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }

    /**
     * 绑定可空 {@code DECIMAL}。
     *
     * @param ps    语句
     * @param idx   参数下标
     * @param value 值
     * @throws SQLException 绑定失败
     */
    protected static void bind(PreparedStatement ps, int idx, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.DECIMAL);
        } else {
            ps.setBigDecimal(idx, value);
        }
    }

    /**
     * 绑定可空日期。
     *
     * @param ps    语句
     * @param idx   参数下标
     * @param value 值
     * @throws SQLException 绑定失败
     */
    protected static void bind(PreparedStatement ps, int idx, LocalDate value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.DATE);
        } else {
            ps.setDate(idx, java.sql.Date.valueOf(value));
        }
    }

    /**
     * 绑定可空时间戳。
     *
     * @param ps    语句
     * @param idx   参数下标
     * @param value 值
     * @throws SQLException 绑定失败
     */
    protected static void bind(PreparedStatement ps, int idx, LocalDateTime value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(idx, Timestamp.valueOf(value));
        }
    }

    /**
     * 绑定 {@code CLOB}（使用标准 {@code setCharacterStream}，{@code DATA_MODEL.md} §6.1 D7）。
     *
     * @param ps    语句
     * @param idx   参数下标
     * @param value 文本
     * @throws SQLException 绑定失败
     */
    protected static void bindClob(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.CLOB);
        } else {
            ps.setCharacterStream(idx, new java.io.StringReader(value), value.length());
        }
    }

    /**
     * 生成 {@code IN (? , ?)} 占位符（列名与数量由调用方控制，禁止拼接取值）。
     *
     * @param count 数量
     * @return 占位符清单
     */
    protected static String placeholders(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("IN 子句占位符数量须 >= 1：值=" + count);
        }
        String[] marks = new String[count];
        Arrays.fill(marks, "?");
        return String.join(",", marks);
    }

    /**
     * 校验列名（防标识符注入；排序字段等来自外部的标识符一律经此校验）。
     *
     * @param column 列名
     * @return 校验通过的列名
     */
    protected static String requireIdentifier(String column) {
        if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法标识符：" + column
                    + "；复现=传入字母/数字/下划线组成的列名");
        }
        return column;
    }

    /** 供子类构造 {@code IN} 参数列表的便捷方法。 */
    protected static List<Object> args(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    /**
     * 当前连接（如需在子类中执行非常规语句）。
     *
     * @return 当前事务连接；未开启事务时返回 {@code null}
     */
    protected Connection boundConnection() {
        return tx.boundConnection();
    }
}
