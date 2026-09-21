package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1（PRODUCT / SPOT / PLAN，26 张表）DAO 的两类机器可复现证据。
 *
 * <ol>
 *   <li><b>列映射一致性</b>：每个 DAO 的 {@code businessColumns()} 与建表脚本的列清单
 *       <b>逐序一致</b>，且不含审计字段 —— 该性质同时保证 {@code bindBusiness}/{@code readBusiness}
 *       不会错位；覆盖 {@code T-005/006/007} 验收标准"增删改查 DAO 覆盖 26 张表"。</li>
 *   <li><b>写入行为</b>：以记录型假 JDBC（{@code NFR-TEST-2}：测试打桩不引 Mockito）驱动
 *       {@code insert}/{@code updateById}，验证 SQL 形状、参数绑定顺序、审计字段自动填充、
 *       乐观锁语义与自增主键回填；其中 {@code t_product_change_log} 的前后值留痕绑定被逐参断言。</li>
 * </ol>
 *
 * <p>本用例与 DAO 实现同包（{@code dao.impl}），故可访问 {@code protected} 的
 * {@code businessColumns()} / {@code tableName()}（同包可见，无需反射）。
 */
class M1DaoMappingAndWriteTest {

    /** 通用审计字段（不属于业务列）。 */
    private static final List<String> AUDIT = Arrays.asList(
            "id", "created_by", "created_time", "updated_by", "updated_time", "row_version");

    /** 26 个 DAO 实现类（与三个建表脚本一一对应）。 */
    private static final List<String> DAO_CLASSES = Arrays.asList(
            "DmProductDao", "DmProductUnitDao", "DmProductContractDao", "DmProductSpotCategoryDao",
            "DmSubProductWhitelistDao", "DmSubInstrumentQuotaDao", "DmProductQuotaDao",
            "DmProductChangeLogDao",
            "DmPurchaseContractDao", "DmSalesContractDao", "DmProductionPlanDao",
            "DmProductionPlanLineDao", "DmWipRecordDao", "DmInventoryLedgerDao", "DmSpotPriceDao",
            "DmExposureDao", "DmExposureDetailDao", "DmSpotChangeLogDao",
            "DmHedgePlanDao", "DmHedgePlanExposureDao", "DmHedgeRatioCalcDao", "DmHedgeDocDao",
            "DmPlanApprovalDao", "DmTradePlanDao", "DmPlanStateLogDao", "DmCustomHedgeRuleDao");

    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql");

    /** 不预建连接的池参数（本用例不实际建连，仅提供事务模板装配用）。 */
    private static PoolConfig lazyPool() {
        return new PoolConfig(0, 2, 500, 2);
    }

    @Test
    @DisplayName("26 个 DAO 的 businessColumns() 与建表脚本列清单逐序一致，且不含审计字段")
    void businessColumnsMatchDdl() throws Exception {
        Map<String, List<String>> ddl = parseDdlColumns();
        assertEquals(26, ddl.size(), "应解析到 26 张表，实际 " + ddl.size() + "：" + ddl.keySet());

        try (DataSourcePool pool = new DataSourcePool(jdbcUnused(), lazyPool())) {
            TxTemplate tx = new TxTemplate(pool);
            List<String> problems = new ArrayList<>();
            int checked = 0;
            for (String name : DAO_CLASSES) {
                Class<?> daoClass = Class.forName("com.hedgeguard.dao.impl." + name);
                AbstractDmDao<?, ?> dao = (AbstractDmDao<?, ?>) daoClass
                        .getConstructor(TxTemplate.class).newInstance(tx);
                String table = dao.tableName();
                List<String> expected = ddl.get(table);
                assertNotNull(expected, name + " 对应的表不在建表脚本中：" + table);
                List<String> actual = Arrays.asList(dao.businessColumns());
                for (String col : actual) {
                    if (AUDIT.contains(col)) {
                        problems.add(name + " 的业务列误含审计字段：" + col);
                    }
                }
                if (!expected.equals(actual)) {
                    problems.add(name + "（" + table + "）列清单不一致：脚本=" + expected + " DAO=" + actual);
                }
                checked++;
            }
            assertEquals(26, checked, "应核对 26 个 DAO");
            assertTrue(problems.isEmpty(), "DAO 列映射与建表脚本不一致（" + problems.size() + " 项）：\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    @Test
    @DisplayName("insert：SQL 列序与参数绑定一致、审计字段自动填充、自增主键回填、前后值留痕落库")
    void insertBindsBusinessColumnsAndAuditFields() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc(1);
        DmProductChangeLogDao dao = new DmProductChangeLogDao(newTx(jdbc));

        ProductChangeLog log = new ProductChangeLog();
        log.productId = 77L;
        log.changeType = "UPDATE";
        log.changedField = "category";
        log.beforeValue = "COMMODITY";
        log.afterValue = "TRADE";
        log.subAccountId = 3;
        log.operatorId = 9L;
        log.changeTime = LocalDateTime.of(2026, 9, 20, 10, 15, 30);
        log.changeReason = "分类订正（DC-001）";

        int affected = dao.insert(log, 9L);

        assertEquals(1, affected, "insert 影响行数");
        String sql = jdbc.lastSql();
        assertTrue(sql.startsWith("INSERT INTO t_product_change_log ("), "INSERT 目标表：" + sql);
        assertTrue(sql.contains("created_by, created_time, row_version"), "审计字段进入 INSERT 列清单：" + sql);
        assertEquals(12, jdbc.placeholderCount(), "占位符数 = 业务列 9 + 审计 3");

        List<Object> args = jdbc.lastArgs();
        assertEquals(77L, args.get(0), "第 1 个参数应为 product_id");
        assertEquals("UPDATE", args.get(1), "第 2 个参数应为 change_type");
        assertEquals("category", args.get(2), "第 3 个参数应为 changed_field");
        assertEquals("COMMODITY", args.get(3), "第 4 个参数应为 before_value（留痕）");
        assertEquals("TRADE", args.get(4), "第 5 个参数应为 after_value（留痕）");
        assertEquals(3, args.get(5), "第 6 个参数应为 sub_account_id");
        assertEquals(9L, args.get(6), "第 7 个参数应为 operator_id");
        assertEquals(log.changeTime, args.get(7), "第 8 个参数应为 change_time");
        assertEquals("分类订正（DC-001）", args.get(8), "第 9 个参数应为 change_reason");
        assertEquals(9L, args.get(9), "第 10 个参数应为 created_by = operatorId");

        assertEquals(9L, log.createdBy, "审计：created_by 由 operatorId 填充");
        assertNotNull(log.createdTime, "审计：created_time 由时钟填充");
        assertEquals(1, log.rowVersion, "审计：insert 后 row_version = 1");
        assertEquals(42L, log.id, "自增主键应回填实体");
    }

    @Test
    @DisplayName("updateById：乐观锁 WHERE 含 row_version；命中推进版本，冲突返回 0 且不推进")
    void updateByIdUsesOptimisticLock() throws Exception {
        RecordingJdbc ok = new RecordingJdbc(1);
        DmProductDao dao = new DmProductDao(newTx(ok));
        Product product = new Product();
        product.id = 5L;
        product.productCode = "CU";
        product.status = "ENABLED";
        product.rowVersion = 3;

        assertEquals(1, dao.updateById(product, 2L), "乐观锁命中 → 影响 1 行");
        assertTrue(ok.lastSql().contains("WHERE id = ? AND row_version = ?"),
                "更新语句须带乐观锁条件：" + ok.lastSql());
        assertEquals(4, product.rowVersion, "成功后版本推进：3 → 4");
        assertNotNull(product.updatedTime, "审计：updated_time 填充");

        RecordingJdbc conflict = new RecordingJdbc(0);
        DmProductDao dao2 = new DmProductDao(newTx(conflict));
        Product stale = new Product();
        stale.id = 5L;
        stale.status = "ENABLED";
        stale.rowVersion = 3;
        assertEquals(0, dao2.updateById(stale, 2L), "版本冲突 → 影响 0 行");
        assertEquals(3, stale.rowVersion, "冲突时版本不得推进");
    }

    @Test
    @DisplayName("只增不改表：updateById 显式拒绝（CONTRACTS.md §2.3），擦改留痕不可静默发生")
    void appendOnlyTablesRejectUpdate() throws Exception {
        DmProductChangeLogDao dao = new DmProductChangeLogDao(newTx(new RecordingJdbc(1)));
        ProductChangeLog log = new ProductChangeLog();
        log.id = 1L;
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> dao.updateById(log, 1L));
        assertTrue(e.getMessage().contains("只增不改"), e.getMessage());
    }

    @Test
    @DisplayName("草稿类例外表：deleteById 生成物理 DELETE（仅服务层限 DRAFT 态调用）")
    void deletableDraftTablesProvidePhysicalDelete() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc(1);
        DmPurchaseContractDao dao = new DmPurchaseContractDao(newTx(jdbc));
        assertEquals(1, dao.deleteById(7L));
        assertTrue(jdbc.lastSql().startsWith("DELETE FROM t_purchase_contract WHERE id = ?"),
                "物理删除语句：" + jdbc.lastSql());
    }

    // ------------------------------------------------------------ 工具

    private static TxTemplate newTx(RecordingJdbc jdbc) {
        return new TxTemplate(new DataSourcePool(jdbc, lazyPool()));
    }

    private static ConnectionFactory jdbcUnused() {
        return () -> {
            throw new SQLException("本用例不建连；复现=检查用例是否误触真实连接路径");
        };
    }

    /** 解析三个建表脚本的列清单（排除审计字段，保持声明顺序）。 */
    private static Map<String, List<String>> parseDdlColumns() throws IOException {
        Path repo = repoRoot();
        Map<String, List<String>> out = new LinkedHashMap<>();
        Pattern create = Pattern.compile("(?s)CREATE TABLE\\s+([a-z0-9_]+)\\s*\\((.*?)\\n\\);");
        Pattern column = Pattern.compile("^([a-z][a-z0-9_]*)\\s+[A-Za-z]");
        for (String rel : SCRIPTS) {
            String text = new String(Files.readAllBytes(repo.resolve(rel)), StandardCharsets.UTF_8);
            Matcher m = create.matcher(text);
            while (m.find()) {
                List<String> cols = new ArrayList<>();
                for (String raw : m.group(2).split("\n")) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("--")) {
                        continue;
                    }
                    String head = line.split("\\s+")[0].toUpperCase(Locale.ROOT);
                    if (head.equals("CONSTRAINT") || head.equals("PRIMARY") || head.equals("FOREIGN")
                            || head.equals("UNIQUE") || head.equals("CHECK")) {
                        continue;
                    }
                    Matcher c = column.matcher(line);
                    if (c.find() && !AUDIT.contains(c.group(1))) {
                        cols.add(c.group(1));
                    }
                }
                out.put(m.group(1), cols);
            }
        }
        return out;
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test，当前="
                + Paths.get("").toAbsolutePath());
    }

    /**
     * 记录型假 JDBC：捕获最后一条 PreparedStatement 的 SQL 与按序参数，
     * 并回放自增主键（42）与影响行数。
     */
    private static final class RecordingJdbc implements ConnectionFactory {

        private final int affected;
        private volatile String lastSql;
        private final List<Object> args = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean keyRead;

        RecordingJdbc(int affected) {
            this.affected = affected;
        }

        String lastSql() {
            return lastSql;
        }

        List<Object> lastArgs() {
            return args;
        }

        int placeholderCount() {
            String sql = lastSql;
            return sql == null ? 0 : sql.length() - sql.replace("?", "").length();
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
                        return "RecordingConnection";
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
            lastSql = sql;
            args.clear();
            InvocationHandler handler = (ps, method, methodArgs) -> {
                switch (method.getName()) {
                    case "setString":
                    case "setLong":
                    case "setInt":
                    case "setBigDecimal":
                    case "setObject":
                        record((Integer) methodArgs[0], methodArgs[1]);
                        return null;
                    case "setNull":
                        record((Integer) methodArgs[0], null);
                        return null;
                    case "setDate":
                        record((Integer) methodArgs[0], ((java.sql.Date) methodArgs[1]).toLocalDate());
                        return null;
                    case "setTimestamp":
                        record((Integer) methodArgs[0], ((Timestamp) methodArgs[1]).toLocalDateTime());
                        return null;
                    case "setCharacterStream":
                        record((Integer) methodArgs[0], readReader((Reader) methodArgs[1]));
                        return null;
                    case "executeUpdate":
                        return affected;
                    case "executeQuery":
                        return resultSet(false);
                    case "getGeneratedKeys":
                        keyRead = false;
                        return resultSet(true);
                    case "close":
                        return null;
                    case "toString":
                        return "RecordingStatement";
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

        private void record(int index, Object value) {
            while (args.size() < index) {
                args.add(null);
            }
            args.set(index - 1, value);
        }

        private static String readReader(Reader reader) {
            try (Reader r = reader) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            } catch (IOException e) {
                throw new IllegalStateException("读取字符流失败：" + e.getMessage(), e);
            }
        }

        /** 自增主键结果集（仅一行一列）或空结果集。 */
        private ResultSet resultSet(boolean withKey) {
            InvocationHandler handler = (rs, method, methodArgs) -> {
                switch (method.getName()) {
                    case "next":
                        if (!withKey) {
                            return false;
                        }
                        if (keyRead) {
                            return false;
                        }
                        keyRead = true;
                        return true;
                    case "getLong":
                        return 42L;
                    case "getInt":
                        return 42;
                    case "close":
                        return null;
                    case "toString":
                        return "RecordingResultSet";
                    case "hashCode":
                        return System.identityHashCode(rs);
                    case "equals":
                        return rs == methodArgs[0];
                    default:
                        return null;
                }
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }
    }
}
