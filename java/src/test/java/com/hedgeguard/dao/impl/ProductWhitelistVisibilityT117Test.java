package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.ScopedPredicateGuard;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.VarietyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-117} PRODUCT 白名单<b>可见性 over-inclusive</b>修正（{@code FR-PRODUCT-5} 处理规则 /
 * {@code FR-AUTH-13} / {@code NFR-SEC-7}）：可见子查询补 {@code status = 'ENABLED'}，
 * 已移出（{@code DISABLED}，历史保留）的白名单行<b>不再</b>把其品种算入可见集。
 *
 * <p><b>缺陷形态（修正前）</b>：{@code DmProductDao} 的可见主键谓词为
 * {@code id IN (SELECT product_id FROM t_sub_product_whitelist WHERE <scope>)}，<b>不带</b> {@code status}；
 * 而 {@code TB-PRODUCT-5} 的「移出白名单」是<b>置 {@code DISABLED} 保留历史</b>
 * （{@code docs/DEPLOY/schema/m1_product.sql} {@code ck_sub_product_wl_status}）⇒ 已移出品种仍算「可见」。
 *
 * <p><b>为何分两层断言</b>：
 * <ol>
 *   <li><b>SQL 层（形状 + 同口径）</b>：以记录型假 JDBC（{@code NFR-TEST-2}：不引 Mockito）驱动<b>真实</b>
 *       {@code DmProductDao} / {@code VarietyService}，逐条捕获 {@code GET /product}（列表）与
 *       {@code GET /product/export}（导出）产生的 SQL，断言白名单子查询<b>整体</b>等于带 {@code status}
 *       守卫的期望形态（整体判等而非 {@code contains} 片段，避免「加/改谓词仍绿」的静默失守）。</li>
 *   <li><b>行级语义（真执行）</b>：{@code InMemoryDmEngine} <b>不解析</b> {@code id IN (SELECT …)}
 *       嵌套子查询（{@code T-152} 实测：裸列名形态<b>静默错值</b>），故不能靠「跑整条外层 SQL 看行集」。
 *       本用例改为把 DAO <b>真实产出</b>的可见子查询语句整段取出，对该子查询在等价引擎上<b>真执行</b>并断言
 *       返回的主键集合 —— 可见集的唯一决定者就是这段子查询，故该层与端到端等价（限制已如实披露）。</li>
 * </ol>
 *
 * <p><b>反退化自证（内置对照片）</b>：{@link #disabledWhitelistRowIsNotVisible()} 同时执行
 * 「摘掉 {@code status} 守卫」的同一子查询，断言此时 {@code DISABLED} 行对应的品种<b>确实会</b>出现
 * ⇒ 证明夹具具备抓取该缺陷的能力（若实现回退到不带 {@code status} 的形态，主断言必红）。
 *
 * <p><b>引擎形态适配（如实披露）</b>：{@code InMemoryDmEngine} 的等值原子仅支持 {@code col = ?}
 * （{@code parseWhere} 的 {@code cmp} 分支），不支持 {@code col = 'LITERAL'}；故执行前把子查询里的
 * 等值字面量<b>就地</b>转成占位符并在<b>同一位置</b>绑定其实参（见 {@link #renderForEngine}），
 * 语义等价，不改写生产 SQL。
 */
class ProductWhitelistVisibilityT117Test {

    /** 建表脚本（相对仓库根）：{@code t_sub_account} + {@code t_product} / {@code t_sub_product_whitelist}。 */
    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql");

    /** 白名单可见子查询的起始标记（{@code T-105} 起的唯一载体形态）。 */
    private static final String WHITELIST_SELECT = "SELECT product_id FROM t_sub_product_whitelist";

    /** 单可见子账户时的<b>期望</b>可见子查询（整体判等；{@code T-117} 验收 1 的谓词形态）。 */
    private static final String EXPECTED_SUBQUERY_ONE = WHITELIST_SELECT
            + " WHERE sub_account_id IN (?) AND status = 'ENABLED'";

    /** {@code status} 守卫片段（对照片用于「摘掉守卫」）。 */
    private static final String STATUS_GUARD = " AND status = 'ENABLED'";

    /** 默认分页（缺省页 1 / 每页 20 / {@code created_time} 显式倒序，同 handler 口径）。 */
    private static final PageQuery PAGE =
            PageQuery.of(null, null, "created_time,desc", VarietyService.DEFAULT_SORT_FIELD);

    private InMemoryDmEngine engine;
    private DmProductDao productDao;
    private DmSubProductWhitelistDao whitelistDao;
    private DmSubAccountDao subAccountDao;

    private int sa1;
    private int sa2;
    private long cuId;
    private long alId;
    private long rbId;
    private long dxId;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        productDao = new DmProductDao(tx);
        whitelistDao = new DmSubProductWhitelistDao(tx);
        subAccountDao = new DmSubAccountDao(tx);

        sa1 = seedSubAccount(1);
        sa2 = seedSubAccount(2);
        cuId = insertProduct("CU", "沪铜");
        alId = insertProduct("AL", "沪铝");
        rbId = insertProduct("RB", "螺纹钢");
        dxId = insertProduct("DX", "沪锌");
        // 子账户 1：CU 在册（ENABLED）；AL 已移出（DISABLED 行 = 历史保留，不算在册）；DX 从未入白名单
        insertWhitelist(sa1, cuId, "ENABLED");
        insertWhitelist(sa1, alId, "DISABLED");
        // 子账户 2：RB 在册（用于证明「仍启用品种不受影响」不是只对单一样本成立）
        insertWhitelist(sa2, rbId, "ENABLED");
    }

    // ============================================================ 验收 2：列表 / 导出同口径

    @Test
    @DisplayName("T-117 验收 2：列表（GET /product）与导出（GET /product/export）每条 SQL 的白名单子查询同口径且带 status 守卫")
    void listAndExportShareSameWhitelistPredicate() {
        RecordingJdbc jdbc = new RecordingJdbc();
        VarietyService service = serviceOver(jdbc, Collections.singletonList(sa1));

        service.listProducts(42L, PAGE);
        service.exportProducts(42L, null);

        List<RecordingJdbc.Rec> recs = jdbc.records();
        assertEquals(4, recs.size(), "列表与导出各产生 COUNT + SELECT 两条 SQL：实际=" + sqls(recs));
        List<String> subqueries = new ArrayList<>();
        for (RecordingJdbc.Rec rec : recs) {
            String sub = whitelistSubqueryOf(rec.sql);
            assertEquals(EXPECTED_SUBQUERY_ONE, sub,
                    "可见子查询须带 {scope} 与 AND status = 'ENABLED'（移出 = 置 DISABLED 保留历史 ⇒ 不得算可见）："
                            + rec.sql);
            subqueries.add(sub);
        }
        for (String sub : subqueries) {
            assertEquals(subqueries.get(0), sub,
                    "列表与导出须消费同一可见谓词（同口径），不得两处各自拼：实际=" + subqueries);
        }
    }

    // ============================================================ 验收 1：三入口（分页 / 全集 / 详情）均带 status 守卫

    @Test
    @DisplayName("T-117 验收 1：findPageScoped（COUNT+SELECT）/ findAllScoped / findByIdScoped 三条入口的白名单子查询均带 status 守卫")
    void everyScopedEntryPointCarriesEnabledGuard() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmProductDao dao = new DmProductDao(newTx(jdbc));
        SubAccountScope scope = SubAccountScope.of("sub_account_id", Collections.singletonList(sa1));

        dao.findPageScoped(PAGE, scope, null, null);
        dao.findAllScoped(scope);
        dao.findByIdScoped(cuId, scope);

        List<RecordingJdbc.Rec> recs = jdbc.records();
        assertEquals(4, recs.size(), "分页两条 + 全集一条 + 详情一条：实际=" + sqls(recs));
        for (RecordingJdbc.Rec rec : recs) {
            assertEquals(EXPECTED_SUBQUERY_ONE, whitelistSubqueryOf(rec.sql),
                    "每个入口的可见子查询都须带 status 守卫（漏一处 = 该面 over-inclusive）：" + rec.sql);
            // T-151/T-152 判据沿用：谓词不得被中和（任意括号深度无 OR）、占位符数须与绑定参数数一致
            assertFalse(ScopedPredicateGuard.containsNeutralizingOr(rec.sql), "谓词不得被中和：" + rec.sql);
            assertEquals(ScopedPredicateGuard.placeholderCount(rec.sql), rec.args.size(),
                    "占位符个数须与绑定参数个数一致（status 守卫为字面量，不得引入未绑定占位符）：" + rec.sql);
        }
    }

    // ============================================================ 验收 3：行级语义（DISABLED 行不再算可见）

    @Test
    @DisplayName("T-117 验收 3：真实 DAO 产出的可见子查询真执行 ⇒ 已移出（DISABLED）品种不在可见集，仍启用品种不受影响")
    void disabledWhitelistRowIsNotVisible() throws Exception {
        SubAccountScope scope1 = SubAccountScope.of("sub_account_id", Collections.singletonList(sa1));
        SubAccountScope scopeBoth = SubAccountScope.of("sub_account_id", Arrays.asList(sa1, sa2));

        RecordingJdbc oneAccount = new RecordingJdbc();
        DmProductDao dao = new DmProductDao(newTx(oneAccount));
        dao.findPageScoped(PAGE, scope1, null, null);
        dao.findAllScoped(scope1);
        dao.findByIdScoped(cuId, scope1);

        List<RecordingJdbc.Rec> recs = oneAccount.records();
        assertEquals(4, recs.size(), "四条 SQL（分页两条 + 全集 + 详情）：实际=" + sqls(recs));
        for (RecordingJdbc.Rec rec : recs) {
            assertEquals(Collections.singletonList(cuId), execute(whitelistSubqueryOf(rec.sql), scope1.params()),
                    "验收 3①③：子账户 1 的可见集只含在册品种 CU —— 已移出（DISABLED）的 AL 与从未入册的 DX 均不得出现：SQL="
                            + rec.sql);
        }

        RecordingJdbc twoAccounts = new RecordingJdbc();
        new DmProductDao(newTx(twoAccounts)).findPageScoped(PAGE, scopeBoth, null, null);
        List<RecordingJdbc.Rec> bothRecs = twoAccounts.records();
        assertEquals(2, bothRecs.size(), "分页两条 SQL：实际=" + sqls(bothRecs));
        for (RecordingJdbc.Rec rec : bothRecs) {
            assertEquals(Arrays.asList(cuId, rbId), execute(whitelistSubqueryOf(rec.sql), scopeBoth.params()),
                    "验收 3②：仍启用（ENABLED）的在册品种不受影响（CU / RB 各自可见）：SQL=" + rec.sql);
        }

        // 反退化自证（内置对照片）：摘掉 status 守卫后同一子查询会把已移出的 AL 算进来
        String sub = whitelistSubqueryOf(recs.get(0).sql);
        String withoutGuard = sub.replace(STATUS_GUARD, "");
        assertNotEquals(sub, withoutGuard, "对照片前提不成立：子查询中未找到 status 守卫片段，实际=" + sub);
        assertEquals(Arrays.asList(cuId, alId), execute(withoutGuard, scope1.params()),
                "对照片：无 status 守卫时可见集会包含已移出（DISABLED）的 AL ⇒ 守卫一旦被移除，上面的主断言必红");
    }

    // ============================================================ 工具

    /**
     * 取出 SQL 中的白名单可见子查询语句（{@code id IN (} 之后到配对 {@code )} 之前，含两端外不含括号）。
     *
     * @param sql 真实 DAO 产出的 SQL
     * @return 子查询正文（如 {@code SELECT product_id FROM t_sub_product_whitelist WHERE …}）
     */
    private static String whitelistSubqueryOf(String sql) {
        int at = sql.indexOf(WHITELIST_SELECT);
        assertTrue(at > 0, "SQL 未含白名单可见子查询（可见性载体）：" + sql);
        int open = sql.lastIndexOf('(', at);
        assertTrue(open >= 0 && sql.substring(open + 1, at).trim().isEmpty(),
                "白名单子查询须紧接在 '(' 之后：" + sql);
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(open + 1, i).trim();
                }
            }
        }
        throw new IllegalStateException("白名单子查询括号不闭合：" + sql);
    }

    /**
     * 在等价引擎上<b>真执行</b>子查询，返回按升序排列的主键集合。
     *
     * @param subquery 子查询正文（可含 {@code ?} 占位符）
     * @param args     绑定实参（{@code ?} 之一为子账户 ID，等值字面量在同位置转占位符后追加）
     * @return 命中的 {@code product_id}（升序）
     */
    private List<Long> execute(String subquery, List<Integer> args) throws Exception {
        Rendered rendered = renderForEngine(subquery, args);
        List<Long> ids = new ArrayList<>();
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(rendered.sql)) {
            for (int i = 0; i < rendered.args.size(); i++) {
                ps.setObject(i + 1, rendered.args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        Collections.sort(ids);
        return ids;
    }

    /**
     * 把子查询渲染成等价引擎可执行形态：{@code ?} 依序取 {@code scopeArgs}，等值字面量
     * {@code = 'LITERAL'} 就地转成 {@code = ?} 并<b>在同一位置</b>绑定字面量实参。
     *
     * <p>必要性：{@code InMemoryDmEngine} 的 {@code parseWhere} 仅支持 {@code col = ?}（等值原子带占位符），
     * 遇到字面量会抛「无法求值的 WHERE 原子」；生产 SQL 用字面量 {@code status = 'ENABLED'} 是定值常量
     * （无注入面、不占绑定槽），故只在执行前做等价改写，不改写生产代码。
     *
     * @param subquery 子查询正文
     * @param scopeArgs 占位符实参（子账户 ID 序列）
     * @return 可执行 SQL + 顺序一致的实参
     */
    private static Rendered renderForEngine(String subquery, List<Integer> scopeArgs) {
        StringBuilder out = new StringBuilder(subquery.length());
        List<Object> args = new ArrayList<>();
        int nextScopeArg = 0;
        int i = 0;
        while (i < subquery.length()) {
            char c = subquery.charAt(i);
            if (c == '?') {
                assertTrue(nextScopeArg < scopeArgs.size(),
                        "子查询占位符多于 scope 实参：SQL=" + subquery + "，实参=" + scopeArgs);
                out.append('?');
                args.add(scopeArgs.get(nextScopeArg++));
                i++;
            } else if (c == '\'') {
                int close = subquery.indexOf('\'', i + 1);
                assertTrue(close > i, "SQL 字面量未闭合：" + subquery);
                String literal = subquery.substring(i + 1, close);
                String head = out.length() == 0 ? "" : out.substring(Math.max(0, out.length() - 3));
                assertTrue(head.trim().endsWith("="),
                        "仅支持等值字面量（形如 col = 'X'）的渲染，实际前导=" + head + "，SQL=" + subquery);
                out.append('?');
                args.add(literal);
                i = close + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        assertEquals(scopeArgs.size(), nextScopeArg,
                "子查询占位符个数须等于 scope 实参个数（status 为字面量，不得占用绑定槽）：SQL=" + subquery);
        return new Rendered(out.toString(), args);
    }

    /** 渲染结果：可执行 SQL + 顺序一致的绑定实参。 */
    private static final class Rendered {

        final String sql;
        final List<Object> args;

        Rendered(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }
    }

    /** 以给定连接工厂装配真实 {@code VarietyService}（仅读取面所需 DAO，其余 {@code null}）。 */
    private static VarietyService serviceOver(ConnectionFactory factory, List<Integer> visible) {
        TxTemplate tx = new TxTemplate(new DataSourcePool(factory, new PoolConfig(0, 2, 500, 2)));
        return new VarietyService(tx, new DmProductDao(tx), null, null, null, null, null, null, null,
                uid -> visible);
    }

    private static TxTemplate newTx(ConnectionFactory factory) {
        return new TxTemplate(new DataSourcePool(factory, new PoolConfig(0, 2, 500, 2)));
    }

    private static String sqls(List<RecordingJdbc.Rec> recs) {
        List<String> out = new ArrayList<>();
        for (RecordingJdbc.Rec rec : recs) {
            out.add(rec.sql);
        }
        return out.toString();
    }

    private long insertProduct(String code, String name) {
        Product product = new Product();
        product.productCode = code;
        product.productName = name;
        product.category = code.equals("RB") ? "TRADE" : "COMMODITY";
        product.categoryManaged = "Y";
        product.status = "ENABLED";
        product.isDomesticExchange = "Y";
        product.currency = "CNY";
        assertEquals(1, productDao.insert(product, 1L), "品种写入失败：" + code);
        return product.id;
    }

    private void insertWhitelist(int subAccountId, long productId, String status) {
        SubProductWhitelist row = new SubProductWhitelist();
        row.subAccountId = subAccountId;
        row.productId = productId;
        row.status = status;
        row.dispatchStatus = "PENDING";
        if ("DISABLED".equals(status)) {
            row.disabledReason = "T-117 夹具：移出白名单（历史保留）";
        }
        assertEquals(1, whitelistDao.insert(row, 1L),
                "白名单写入失败：" + subAccountId + "/" + productId);
    }

    private int seedSubAccount(int seq) {
        SubAccount row = new SubAccount();
        row.subAccountCode = "SA-T117-" + seq;
        row.subAccountName = "T-117 子账户" + seq;
        row.status = "ENABLED";
        row.category = "COMMODITY";
        row.isDefault = "N";
        assertEquals(1, subAccountDao.insert(row, 1L), "子账户写入失败：" + seq);
        return row.id;
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
     * 记录型假 JDBC：捕获<b>每一条</b> PreparedStatement 的 SQL 与按序参数（{@code NFR-TEST-2}：不引 Mockito）。
     * 含 {@code COUNT(*)} 的查询返回一行固定值，其余查询返回空结果集（本用例只关心 SQL 文本与绑定值）。
     */
    private static final class RecordingJdbc implements ConnectionFactory {

        /** 假 COUNT(*) 返回值（让导出分页遍历只走一页，便于逐条核对 SQL）。 */
        private static final long COUNT_VALUE = 3L;

        private final List<Rec> records = Collections.synchronizedList(new ArrayList<>());

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
                        return "T117RecordingConnection";
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
                        rec.record((Integer) methodArgs[0], methodArgs[1]);
                        return null;
                    case "setNull":
                        rec.record((Integer) methodArgs[0], null);
                        return null;
                    case "executeQuery":
                        return sql.contains("COUNT(*)") ? countResultSet(COUNT_VALUE) : emptyResultSet();
                    case "close":
                        return null;
                    case "toString":
                        return "T117RecordingStatement";
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

        private static ResultSet countResultSet(long value) {
            InvocationHandler handler = (rs, method, methodArgs) -> {
                switch (method.getName()) {
                    case "next":
                        return true;
                    case "getLong":
                        return value;
                    case "close":
                        return null;
                    case "toString":
                        return "T117CountResultSet";
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

        private static ResultSet emptyResultSet() {
            InvocationHandler handler = (rs, method, methodArgs) -> {
                switch (method.getName()) {
                    case "next":
                        return false;
                    case "close":
                        return null;
                    case "toString":
                        return "T117EmptyResultSet";
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

        /** 单条 SQL 及其按序绑定的参数。 */
        private static final class Rec {

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
