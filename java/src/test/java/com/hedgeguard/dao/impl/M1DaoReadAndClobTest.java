package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1（26 张表）DAO 的读路径与 CLOB 往返回归用例 —— 补充
 * {@link M1DaoMappingAndWriteTest}（列清单一致性 + 写路径）未覆盖的两类风险：
 *
 * <ol>
 *   <li><b>{@code readBusiness} 的列名真实性</b>：以"未知列名即抛
 *       {@code SQLException}"的假 {@code ResultSet} 驱动全部 26 个 DAO 的 {@code findById}，
 *       并断言生成的 {@code SELECT} 列清单与建表脚本逐序一致 ——
 *       列名拼错、错位、漏读（导致实体字段恒空）都会在此暴露。</li>
 *   <li><b>CLOB 往返</b>（{@code DATA_MODEL.md §6.1 D7}：读写走标准
 *       {@code setCharacterStream}/{@code getCharacterStream}）：对 {@code t_hedge_doc}
 *       的三要素 {@code CLOB} 列做"写入 → 读出"闭环验证，并覆盖
 *       {@code NOT NULL} 三要素的绑定顺序。</li>
 * </ol>
 */
class M1DaoReadAndClobTest {

    private static final List<String> AUDIT = Arrays.asList(
            "id", "created_by", "created_time", "updated_by", "updated_time", "row_version");

    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql");

    /** 26 个 DAO 实现类。 */
    private static final List<String> DAO_CLASSES = Arrays.asList(
            "DmProductDao", "DmProductUnitDao", "DmProductContractDao", "DmProductSpotCategoryDao",
            "DmSubProductWhitelistDao", "DmSubInstrumentQuotaDao", "DmProductQuotaDao",
            "DmProductChangeLogDao",
            "DmPurchaseContractDao", "DmSalesContractDao", "DmProductionPlanDao",
            "DmProductionPlanLineDao", "DmWipRecordDao", "DmInventoryLedgerDao", "DmSpotPriceDao",
            "DmExposureDao", "DmExposureDetailDao", "DmSpotChangeLogDao",
            "DmHedgePlanDao", "DmHedgePlanExposureDao", "DmHedgeRatioCalcDao", "DmHedgeDocDao",
            "DmPlanApprovalDao", "DmTradePlanDao", "DmPlanStateLogDao", "DmCustomHedgeRuleDao");

    private static PoolConfig lazyPool() {
        return new PoolConfig(0, 2, 500, 2);
    }

    @Test
    @DisplayName("26 个 DAO 的 findById：SELECT 列清单与脚本逐序一致，readBusiness 无错列名/漏读")
    void findByIdSelectsAndReadsAllColumns() throws Exception {
        Map<String, List<String>> ddl = parseDdlColumns();
        try (DataSourcePool pool = new DataSourcePool(unusedFactory(), lazyPool())) {
            TxTemplate tx = new TxTemplate(pool);
            List<String> problems = new ArrayList<>();
            for (String name : DAO_CLASSES) {
                Class<?> daoClass = Class.forName("com.hedgeguard.dao.impl." + name);
                AbstractDmDao<?, ?> dao = (AbstractDmDao<?, ?>) daoClass
                        .getConstructor(TxTemplate.class).newInstance(tx);
                String table = dao.tableName();
                List<String> columns = ddl.get(table);
                assertNotNull(columns, name + " 表未在脚本中：" + table);

                // 假结果集：仅接受该表的真实列名，未知列名 → SQLException（模拟真实 JDBC 行为）
                RowJdbc jdbc = new RowJdbc(table, columns, Collections.emptyMap());
                // 用裸类型调用 findById：主键类型随表而异（本模块均为 BIGINT → Long）
                @SuppressWarnings("rawtypes")
                AbstractDmDao bound = (AbstractDmDao) daoClass
                        .getConstructor(TxTemplate.class).newInstance(newTx(jdbc));

                Object entity;
                try {
                    entity = bound.findById(1L);
                } catch (RuntimeException e) {
                    problems.add(name + "（" + table + "）findById 失败："
                            + rootMessage(e));
                    continue;
                }
                assertNotNull(entity, name + " findById 应返回一行");

                List<String> selected = jdbc.lastSelectColumns();
                List<String> expected = new ArrayList<>();
                expected.add("id");
                expected.addAll(columns);
                expected.addAll(Arrays.asList("created_by", "created_time", "updated_by",
                        "updated_time", "row_version"));
                if (!expected.equals(selected)) {
                    problems.add(name + "（" + table + "）SELECT 列清单不符：脚本顺序=" + expected
                            + " 实际=" + selected);
                }
            }
            assertTrue(problems.isEmpty(), "findById 读路径与脚本不一致（" + problems.size() + " 项）：\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    @Test
    @DisplayName("CLOB 往返：t_hedge_doc 三要素走 setCharacterStream/getCharacterStream，NOT NULL 列绑定非空")
    void clobRoundTripOnHedgeDoc() throws Exception {
        Map<String, List<String>> ddl = parseDdlColumns();
        List<String> columns = ddl.get("t_hedge_doc");
        assertNotNull(columns, "缺少表 t_hedge_doc");

        String designation = "被套期项目=敞口 EXP-2026-001（子账户 3）；套期工具=cu2612 期货多头";
        String method = "比率分析法：|ΔV_hedged| / |ΔV_tool| ∈ [0.8, 1.25]（月度评估）";
        String rebalance = "偏离 >20% 触发再平衡：调整数量或更换合约，记录再平衡原因与影响";

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("sub_account_id", 3);
        row.put("hedge_plan_id", 11L);
        row.put("doc_no", "HD-20260920-0001");
        row.put("doc_version", 1);
        row.put("is_current", "Y");
        row.put("elem_formal_designation", designation);
        row.put("elem_effectiveness_method", method);
        row.put("elem_rebalance_mechanism", rebalance);
        row.put("hedged_item_exposure_id", 21L);
        row.put("hedging_instrument", "cu2612");
        row.put("pending_external_review", "Y");
        row.put("generated_by", 7L);
        row.put("generated_time", LocalDateTime.of(2026, 9, 20, 9, 30, 0));

        RowJdbc jdbc = new RowJdbc("t_hedge_doc", columns, row);
        DmHedgeDocDao dao = new DmHedgeDocDao(newTx(jdbc));
        HedgeDoc doc = dao.findById(1L);

        assertNotNull(doc, "应读到一行");
        assertEquals(designation, doc.elemFormalDesignation, "CLOB 读：要素①正式指定");
        assertEquals(method, doc.elemEffectivenessMethod, "CLOB 读：要素②有效性评估方法");
        assertEquals(rebalance, doc.elemRebalanceMechanism, "CLOB 读：要素③再平衡机制");
        assertEquals(3, doc.subAccountId);
        assertEquals("HD-20260920-0001", doc.docNo);
        assertEquals(1, doc.docVersion);
        assertEquals("Y", doc.pendingExternalReview, "恒为 Y（R-ACCT-10）");
        assertEquals(1L, doc.id, "主键读出");

        // 写路径：CLOB 必须经 setCharacterStream 绑定，且绑定顺序与列序一致
        WriteJdbc write = new WriteJdbc(1);
        DmHedgeDocDao writeDao = new DmHedgeDocDao(newTx(write));
        HedgeDoc toInsert = new HedgeDoc();
        toInsert.subAccountId = 3;
        toInsert.hedgePlanId = 11L;
        toInsert.docNo = "HD-20260920-0001";
        toInsert.docVersion = 1;
        toInsert.isCurrent = "Y";
        toInsert.elemFormalDesignation = designation;
        toInsert.elemEffectivenessMethod = method;
        toInsert.elemRebalanceMechanism = rebalance;
        toInsert.hedgedItemExposureId = 21L;
        toInsert.hedgingInstrument = "cu2612";
        toInsert.pendingExternalReview = "Y";
        toInsert.generatedBy = 7L;
        toInsert.generatedTime = LocalDateTime.of(2026, 9, 20, 9, 30, 0);
        assertEquals(1, writeDao.insert(toInsert, 7L), "insert 影响行数");

        List<Object> args = write.lastArgs();
        assertEquals(columns.size() + 3, args.size(), "绑定参数数 = 业务列 " + columns.size() + " + 审计 3");
        int idxDesignation = columns.indexOf("elem_formal_designation");
        int idxMethod = columns.indexOf("elem_effectiveness_method");
        int idxRebalance = columns.indexOf("elem_rebalance_mechanism");
        assertTrue(idxDesignation >= 0 && idxMethod >= 0 && idxRebalance >= 0,
                "三要素列应在字段表中：" + columns);
        assertEquals(designation, args.get(idxDesignation), "CLOB 写：要素①经字符流绑定");
        assertEquals(method, args.get(idxMethod), "CLOB 写：要素②经字符流绑定");
        assertEquals(rebalance, args.get(idxRebalance), "CLOB 写：要素③经字符流绑定");
    }

    // ------------------------------------------------------------ 工具

    private static TxTemplate newTx(ConnectionFactory factory) {
        return new TxTemplate(new DataSourcePool(factory, lazyPool()));
    }

    private static ConnectionFactory unusedFactory() {
        return () -> {
            throw new SQLException("未预期的建连；复现=检查用例是否漏注入假连接");
        };
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return t.getClass().getSimpleName() + " → " + cur.getMessage();
    }

    /** 解析三个脚本的列清单（排除审计字段；含该表全部物理列名，供假结果集校验）。 */
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
     * 只读假 JDBC：返回单行结果集，<b>未知列名即抛 {@code SQLException}</b>，
     * 用于验证 {@code readBusiness} 中引用的列名真实存在。同时记录最后一条 SELECT 的列清单。
     */
    private static final class RowJdbc implements ConnectionFactory {

        private final String table;
        private final Set<String> validColumns;
        private final Map<String, Object> row;
        private volatile String lastSelect;
        private volatile boolean consumed;

        RowJdbc(String table, List<String> businessColumns, Map<String, Object> row) {
            this.table = table;
            Set<String> cols = new LinkedHashSet<>(businessColumns);
            cols.addAll(AUDIT);
            this.validColumns = cols;
            this.row = row;
        }

        /** 最后一条 SELECT 的列清单（去空白）。 */
        List<String> lastSelectColumns() {
            String sql = lastSelect;
            int from = sql.indexOf(" FROM ");
            String list = sql.substring(sql.indexOf("SELECT ") + "SELECT ".length(), from);
            List<String> out = new ArrayList<>();
            for (String part : list.split(",")) {
                out.add(part.trim());
            }
            return out;
        }

        @Override
        public Connection create() {
            InvocationHandler handler = (conn, method, args) -> {
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
                        lastSelect = (String) args[0];
                        consumed = false;
                        return statement();
                    case "toString":
                        return "RowConnection";
                    case "hashCode":
                        return System.identityHashCode(conn);
                    case "equals":
                        return conn == args[0];
                    default:
                        return null;
                }
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        }

        private PreparedStatement statement() {
            InvocationHandler handler = (ps, method, args) -> {
                switch (method.getName()) {
                    case "setLong":
                    case "setInt":
                    case "setString":
                    case "setBigDecimal":
                    case "setNull":
                    case "setObject":
                    case "setDate":
                    case "setTimestamp":
                    case "setCharacterStream":
                        return null;
                    case "executeQuery":
                        return resultSet();
                    case "executeUpdate":
                        return 0;
                    case "close":
                        return null;
                    case "toString":
                        return "RowStatement";
                    case "hashCode":
                        return System.identityHashCode(ps);
                    case "equals":
                        return ps == args[0];
                    default:
                        return null;
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, handler);
        }

        private ResultSet resultSet() {
            InvocationHandler handler = (rs, method, args) -> {
                String name = method.getName();
                switch (name) {
                    case "next":
                        if (consumed) {
                            return false;
                        }
                        consumed = true;
                        return true;
                    case "wasNull":
                        return lastWasNull;
                    case "getInt": {
                        String col = requireColumn((String) args[0]);
                        Object v = row.get(col);
                        lastWasNull = v == null;
                        return v instanceof Number ? ((Number) v).intValue() : 0;
                    }
                    case "getLong": {
                        String col = requireColumn((String) args[0]);
                        Object v = row.get(col);
                        lastWasNull = v == null;
                        return v instanceof Number ? ((Number) v).longValue() : 0L;
                    }
                    case "getString": {
                        String col = requireColumn((String) args[0]);
                        Object v = row.get(col);
                        lastWasNull = v == null;
                        return v == null ? null : String.valueOf(v);
                    }
                    case "getBigDecimal": {
                        String col = requireColumn((String) args[0]);
                        Object v = row.get(col);
                        lastWasNull = v == null;
                        return v == null ? null : new BigDecimal(String.valueOf(v));
                    }
                    case "getDate": {
                        String col = requireColumn((String) args[0]);
                        Object v = row.get(col);
                        lastWasNull = v == null;
                        return v == null ? null : java.sql.Date.valueOf(String.valueOf(v));
                    }
                    case "getTimestamp": {
                        String col = requireColumn((String) args[0]);
                        Object v = row.get(col);
                        lastWasNull = v == null;
                        return v == null ? null
                                : Timestamp.valueOf((LocalDateTime) v);
                    }
                    case "getCharacterStream": {
                        String col = requireColumn((String) args[0]);
                        Object v = row.get(col);
                        lastWasNull = v == null;
                        return v == null ? null : new StringReader(String.valueOf(v));
                    }
                    case "close":
                        return null;
                    case "toString":
                        return "RowResultSet";
                    case "hashCode":
                        return System.identityHashCode(rs);
                    case "equals":
                        return rs == args[0];
                    default:
                        return null;
                }
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, handler);
        }

        private boolean lastWasNull;

        private String requireColumn(String column) throws SQLException {
            String col = column.toLowerCase(Locale.ROOT);
            if (!validColumns.contains(col)) {
                throw new SQLException("列不存在：" + col + "（表 " + table
                        + "）；复现=核对 DAO 的 readBusiness 与建表脚本列名");
            }
            return col;
        }
    }

    /** 只写假 JDBC：记录最后一条语句的按序参数（{@code Reader} 收敛为字符串）。 */
    private static final class WriteJdbc implements ConnectionFactory {

        private final int affected;
        private final List<Object> args = Collections.synchronizedList(new ArrayList<>());

        WriteJdbc(int affected) {
            this.affected = affected;
        }

        List<Object> lastArgs() {
            return args;
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
                        args.clear();
                        return statement();
                    case "toString":
                        return "WriteConnection";
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

        private PreparedStatement statement() {
            InvocationHandler handler = (ps, method, methodArgs) -> {
                switch (method.getName()) {
                    case "setLong":
                    case "setInt":
                    case "setString":
                    case "setBigDecimal":
                    case "setObject":
                        record((Integer) methodArgs[0], methodArgs[1]);
                        return null;
                    case "setNull":
                        record((Integer) methodArgs[0], null);
                        return null;
                    case "setDate":
                        record((Integer) methodArgs[0], String.valueOf(methodArgs[1]));
                        return null;
                    case "setTimestamp":
                        record((Integer) methodArgs[0], String.valueOf(methodArgs[1]));
                        return null;
                    case "setCharacterStream":
                        record((Integer) methodArgs[0], readReader((Reader) methodArgs[1]));
                        return null;
                    case "executeUpdate":
                        return affected;
                    case "getGeneratedKeys":
                        return null;
                    case "close":
                        return null;
                    case "toString":
                        return "WriteStatement";
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
    }
}
