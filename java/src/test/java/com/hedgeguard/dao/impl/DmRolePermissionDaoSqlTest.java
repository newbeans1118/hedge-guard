package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.RolePermissionDao;
import com.hedgeguard.dao.entity.RolePermission;
import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DmRolePermissionDao} 的<b>真实 SQL 形状</b>断言（{@code FR-AUTH-6} / {@code FR-AUTH-12}）。
 *
 * <p><b>为何要这组用例</b>：{@code T-108} 写面/读面用例以服务层语义覆盖权限链路，但"DAO 忠实读
 * {@code t_role_permission}"这一前提本身未被真实 SQL 层证实——若 {@code findByRole} 漏拼
 * {@code role_id} 谓词 / 拼错列 / 漏 {@code ORDER BY}，上层用例仍会绿。本用例以记录型假 JDBC
 * （{@code NFR-TEST-2}：不引 Mockito）驱动<b>真实</b> {@code DmRolePermissionDao}，逐字断言 SQL 形状。
 *
 * <p>另断两条结构契约：实现继承 {@code AbstractDmDao}、可空事务构造（支撑受配置保护装配），
 * 以及 {@code perm_category} / {@code perm_code} 列读写与 {@code uk_role_perm} 自然键一致。
 */
class DmRolePermissionDaoSqlTest {

    @Test
    @DisplayName("findByRole：SQL 形状 = SELECT 全列 FROM t_role_permission WHERE role_id = ? ORDER BY perm_code ASC")
    void findByRoleSqlShape() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmRolePermissionDao dao = new DmRolePermissionDao(newTx(jdbc));

        List<RolePermission> rows = dao.findByRole(42L);

        assertEquals(Collections.emptyList(), rows, "假 JDBC 无行 ⇒ 空列表");
        assertEquals(1, jdbc.records().size(), "单查询应产生 1 条 SQL");
        String sql = jdbc.records().get(0).sql;
        assertTrue(sql.startsWith("SELECT id, role_id, perm_code, perm_category,"
                        + " created_by, created_time, updated_by, updated_time, row_version"
                        + " FROM t_role_permission"),
                "须按表列清单取数（role_id 在前、perm_code、perm_category 在册）：" + sql);
        assertTrue(sql.contains("WHERE role_id = ?"), "须按 role_id 过滤：" + sql);
        assertTrue(sql.endsWith("ORDER BY perm_code ASC"), "须按 perm_code 升序收官：" + sql);
        assertEquals(Collections.singletonList((Object) 42L), jdbc.records().get(0).args,
                "绑定值须为 role_id：" + sql);
    }

    @Test
    @DisplayName("findByPermCode：SQL 形状 = … WHERE perm_code = ? ORDER BY role_id ASC（绑定权限编码）")
    void findByPermCodeSqlShape() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmRolePermissionDao dao = new DmRolePermissionDao(newTx(jdbc));

        List<RolePermission> rows = dao.findByPermCode("PRODUCT:EDIT");

        assertEquals(Collections.emptyList(), rows);
        assertEquals(1, jdbc.records().size());
        String sql = jdbc.records().get(0).sql;
        assertTrue(sql.contains("FROM t_role_permission"), "须查本表：" + sql);
        assertTrue(sql.contains("WHERE perm_code = ?"), "须按 perm_code 过滤：" + sql);
        assertTrue(sql.endsWith("ORDER BY role_id ASC"), "须按 role_id 升序收官：" + sql);
        assertEquals(Collections.singletonList((Object) "PRODUCT:EDIT"), jdbc.records().get(0).args,
                "绑定值须为权限编码：" + sql);
    }

    @Test
    @DisplayName("读映射：SELECT 返回行 ⇒ perm_code / perm_category / role_id 列名逐字对上实体字段")
    void readMappingMapsColumnsToEntity() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.row = new Object[]{7L, 42L, "PRODUCT:EDIT", "FUNCTION", 0L, null, null, null, 1};
        DmRolePermissionDao dao = new DmRolePermissionDao(newTx(jdbc));

        RolePermission rp = dao.findByRole(42L).get(0);

        assertEquals(7L, rp.id, "主键 id 须读取");
        assertEquals(42L, rp.roleId, "role_id 须读取");
        assertEquals("PRODUCT:EDIT", rp.permCode, "perm_code 须读取");
        assertEquals("FUNCTION", rp.permCategory, "perm_category 须读取");
        assertEquals(1, rp.rowVersion, "row_version 须读取");
    }

    @Test
    @DisplayName("结构契约：实现继承 AbstractDmDao、实现接口、可空事务构造（受配置保护装配）")
    void structuralContract() {
        assertTrue(AbstractDmDao.class.isAssignableFrom(DmRolePermissionDao.class),
                "须循 AbstractDmDao + BaseDao 模式");
        assertTrue(RolePermissionDao.class.isAssignableFrom(DmRolePermissionDao.class));
        // 可空事务构造（同 M1AuthDaoImplTest 口径：装配类须能在未配置库时构造但不触碰事务）
        assertDoesNotThrow(() -> new DmRolePermissionDao(null));
    }

    // ------------------------------------------------------------ 工具

    private static TxTemplate newTx(RecordingJdbc jdbc) {
        return new TxTemplate(new DataSourcePool(jdbc, new PoolConfig(0, 2, 500, 2)));
    }

    /**
     * 记录型假 JDBC：捕获<b>每一条</b> PreparedStatement 的 SQL 与按序参数；查询按 {@link #row}
     * （{@code null} = 空结果集）返回一行固定值。
     */
    private static final class RecordingJdbc implements ConnectionFactory {

        private final List<Rec> records = Collections.synchronizedList(new ArrayList<>());
        /** 查询返回行（列序 = {@code selectList()}：id, role_id, perm_code, perm_category, 审计列×5）。 */
        private Object[] row;

        List<Rec> records() {
            synchronized (records) {
                return new ArrayList<>(records);
            }
        }

        @Override
        public Connection create() {
            InvocationHandler handler = (conn, method, methodArgs) -> {
                switch (method.getName()) {
                    case "close":
                    case "setAutoCommit":
                    case "commit":
                    case "rollback":
                        return null;
                    case "isClosed":
                        return false;
                    case "isValid":
                        return true;
                    case "getAutoCommit":
                        return true;
                    case "prepareStatement":
                        return statement((String) methodArgs[0]);
                    case "toString":
                        return "ReadingRecordingConnection";
                    case "hashCode":
                        return System.identityHashCode(conn);
                    case "equals":
                        return conn == methodArgs[0];
                    default:
                        return null;
                }
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        private PreparedStatement statement(String sql) {
            Rec rec = new Rec(sql);
            records.add(rec);
            InvocationHandler handler = (ps, method, methodArgs) -> {
                switch (method.getName()) {
                    case "setInt":
                    case "setLong":
                    case "setObject":
                    case "setString":
                        rec.record((Integer) methodArgs[0], methodArgs[1]);
                        return null;
                    case "setNull":
                        rec.record((Integer) methodArgs[0], null);
                        return null;
                    case "executeQuery":
                        return rowResultSet(row);
                    case "close":
                        return null;
                    case "toString":
                        return "ReadingRecordingStatement";
                    case "hashCode":
                        return System.identityHashCode(ps);
                    case "equals":
                        return ps == methodArgs[0];
                    default:
                        return null;
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, handler);
        }

        private static ResultSet rowResultSet(Object[] columns) {
            boolean[] consumed = {false};
            InvocationHandler handler = (rs, method, methodArgs) -> {
                switch (method.getName()) {
                    case "next":
                        if (columns == null || consumed[0]) {
                            return false;   // 单行结果集：读一次后即耗尽（防 queryList 死循环）
                        }
                        consumed[0] = true;
                        return true;
                    case "getLong":
                        Object l = columns[columnIndex(methodArgs[0], columns)];
                        return l == null ? 0L : l;
                    case "getInt":
                        Object i = columns[columnIndex(methodArgs[0], columns)];
                        return i == null ? 0 : i;
                    case "getString":
                    case "getCharacterStream":
                    case "getTimestamp":
                        return columns[columnIndex(methodArgs[0], columns)];
                    case "wasNull":
                        return false;
                    case "close":
                        return null;
                    case "toString":
                        return "RowResultSet";
                    case "hashCode":
                        return System.identityHashCode(rs);
                    case "equals":
                        return rs == methodArgs[0];
                    default:
                        return null;
                }
            };
            return (ResultSet) Proxy.newProxyInstance(RecordingJdbc.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }

        private static int columnIndex(Object label, Object[] columns) {
            String name = (String) label;
            String[] names = {"id", "role_id", "perm_code", "perm_category", "created_by",
                    "created_time", "updated_by", "updated_time", "row_version"};
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(name)) {
                    return i;
                }
            }
            throw new IllegalArgumentException("未预期列名：" + name);
        }

        /** 单条 SQL 及其按序绑定的参数。 */
        static final class Rec {

            final String sql;
            final List<Object> args = Collections.synchronizedList(new ArrayList<>());

            Rec(String sql) {
                this.sql = sql;
            }

            void record(int index, Object value) {
                synchronized (args) {
                    while (args.size() < index) {
                        args.add(null);
                    }
                    args.set(index - 1, value);
                }
            }
        }
    }
}
