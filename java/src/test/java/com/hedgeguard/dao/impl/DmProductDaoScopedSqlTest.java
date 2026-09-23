package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.VarietyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DmProductDao} 的<b>真实 SQL 形状</b>断言（{@code T-105} 验收 6 / {@code NFR-SEC-7} /
 * {@code FR-PRODUCT-5}）。
 *
 * <p><b>为何要这组用例</b>：服务层用例（{@code ProductListIsolationTest}）用<b>假 DAO</b> 覆盖权限
 * <b>语义</b>，但"假 DAO 忠实模拟 SQL"这一前提本身未被真实 SQL 层证实 —— 若
 * {@code DmProductDao.findPageScoped} 把 scope 漏拼 / 拼错列，假 DAO 的用例仍会绿。本用例以记录型假
 * JDBC（{@code NFR-TEST-2}：不引 Mockito）驱动<b>真实</b> {@code DmProductDao}。
 *
 * <p>{@code t_product} 无 {@code sub_account_id} 列，scoped 过滤落在
 * {@code t_sub_product_whitelist} 子查询上（{@code id IN (SELECT product_id FROM
 * t_sub_product_whitelist WHERE <scope>)}）。分页路径产生<b>两条 SQL</b>（独立 {@code COUNT} 与
 * {@code SELECT}），本用例对<b>两条</b>均断言 scope 条件：
 * <ul>
 *   <li><b>空 scope</b> ⇒ 两 SQL 的 WHERE 均含恒假条件 {@code sub_account_id IN (-1)}；</li>
 *   <li><b>非空 scope</b> ⇒ 两 SQL 均含 {@code sub_account_id IN (?,?)} 且按序绑定子账户 ID；</li>
 *   <li><b>服务层 → 真实 DAO</b>：{@code VarietyService.listProducts} 的过滤确实落到<b>两条</b> SQL；</li>
 *   <li><b>哨兵 0</b>（跨账户只读）⇒ 两 SQL <b>不含</b> {@code sub_account_id} 条件（不加过滤）；</li>
 *   <li>SELECT 排序 {@code ORDER BY created_time DESC}（{@code FR-PRODUCT-5}「默认按创建时间倒序」）。</li>
 * </ul>
 */
class DmProductDaoScopedSqlTest {

    /** 默认分页（缺省页 1 / 每页 20 / 字段 {@code created_time} + 显式 {@code desc}）。 */
    private static final PageQuery DEFAULT_QUERY =
            PageQuery.of(null, null, "created_time,desc", VarietyService.DEFAULT_SORT_FIELD);

    /** 假 COUNT(*) 的返回值：用于证明 {@code PageResult.total} 取自 COUNT 查询。 */
    private static final long COUNT_VALUE = 7L;

    @Test
    @DisplayName("真实 DAO：空 scope ⇒ COUNT 与 SELECT 两条 SQL 的 WHERE 均含恒假条件 sub_account_id IN (-1)")
    void emptyScopeEmitsNeverTrueConditionInBothSql() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmProductDao dao = new DmProductDao(newTx(jdbc));

        PageResult<Product> page = dao.findPageScoped(DEFAULT_QUERY,
                SubAccountScope.of("sub_account_id", Collections.emptyList()), null, null);

        assertEquals(2, jdbc.records().size(), "分页路径应产生两条 SQL（COUNT + SELECT）");
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("id IN (SELECT product_id FROM t_sub_product_whitelist"), rec.sql);
            assertTrue(rec.sql.contains("sub_account_id IN (-1)"),
                    "空 scope 须在 COUNT 与 SELECT 均产出恒假条件（返回空），不得退化为不过滤：" + rec.sql);
        }
        RecordingJdbc.Rec count = jdbc.records().get(0);
        RecordingJdbc.Rec select = jdbc.records().get(1);
        assertTrue(count.sql.contains("COUNT(*)"), "第一条须为 COUNT：" + count.sql);
        assertTrue(count.args.isEmpty(), "空 scope 无绑定参数：" + count.sql);
        assertTrue(select.sql.contains("ORDER BY created_time DESC"),
                "默认排序须为创建时间倒序：" + select.sql);
        assertTrue(select.sql.contains("LIMIT ? OFFSET ?"), "SELECT 须分页：" + select.sql);
        assertEquals(Arrays.asList((Object) 20, 0), select.args,
                "SELECT 绑定：LIMIT(20) + OFFSET(0)：" + select.sql);
        assertEquals(0L, page.items().size(), "假 JDBC 无行 ⇒ items 空");
    }

    @Test
    @DisplayName("真实 DAO：非空 scope ⇒ COUNT 与 SELECT 两条 SQL 均含 sub_account_id IN (?,?)，按序绑定")
    void nonEmptyScopeEmitsInClauseInBothSql() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmProductDao dao = new DmProductDao(newTx(jdbc));

        dao.findPageScoped(DEFAULT_QUERY, SubAccountScope.of("sub_account_id", Arrays.asList(11, 22)),
                null, null);

        assertEquals(2, jdbc.records().size());
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("sub_account_id IN (?,?)"),
                    "两条 SQL 皆须含子账户 IN 子句：实际=" + rec.sql);
        }
        assertEquals(Arrays.asList((Object) 11, 22), jdbc.records().get(0).args,
                "COUNT 参数顺序：子账户 ID");
        assertEquals(Arrays.asList((Object) 11, 22, 20, 0), jdbc.records().get(1).args,
                "SELECT 参数：scope 参数 + LIMIT + OFFSET；与占位符一一对应");
    }

    @Test
    @DisplayName("服务层→真实 DAO：受限身份（可见 [7]）的过滤落到 COUNT 与 SELECT 两条 SQL；total 取自 COUNT")
    void serviceLayerForcesScopedSqlInPagination() {
        RecordingJdbc jdbc = new RecordingJdbc();
        TxTemplate tx = newTx(jdbc);
        ProductDao dao = new DmProductDao(tx);
        VarietyService service = new VarietyService(tx, dao, null, null, null, null, null, null, null,
                uid -> Arrays.asList(7));

        PageResult<Product> page = service.listProducts(42L, DEFAULT_QUERY);

        assertEquals(2, jdbc.records().size(), "分页 + 隔离 ⇒ COUNT 与 SELECT 两条 SQL");
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("sub_account_id IN (?)"),
                    "受限身份须把子账户过滤落到该条 SQL：" + rec.sql);
        }
        assertEquals(Collections.singletonList((Object) 7), jdbc.records().get(0).args, "COUNT 绑定可见子账户");
        assertEquals(Arrays.asList((Object) 7, 20, 0), jdbc.records().get(1).args,
                "SELECT 绑定可见子账户 + LIMIT + OFFSET");
        assertEquals(COUNT_VALUE, page.total(), "total 须取自带 scope 的 COUNT 子查询（过滤后总数）");
    }

    @Test
    @DisplayName("服务层→真实 DAO：跨账户只读（哨兵 0）⇒ 两条 SQL 均不含 sub_account_id 过滤")
    void crossReadonlySkipsScopeInSqlForPagination() {
        RecordingJdbc jdbc = new RecordingJdbc();
        TxTemplate tx = newTx(jdbc);
        ProductDao dao = new DmProductDao(tx);
        VarietyService service = new VarietyService(tx, dao, null, null, null, null, null, null, null,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL));

        service.listProducts(42L, DEFAULT_QUERY);

        assertEquals(2, jdbc.records().size());
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertFalse(rec.sql.contains("sub_account_id IN"),
                    "跨账户只读不得加子账户 IN 过滤（哨兵 0 不可进 IN 列表）：" + rec.sql);
        }
        assertTrue(jdbc.records().get(0).args.isEmpty(), "COUNT 无绑定参数");
        assertEquals(Arrays.asList((Object) 20, 0), jdbc.records().get(1).args,
                "SELECT 绑定：LIMIT + OFFSET");
        assertTrue(jdbc.records().get(1).sql.contains("ORDER BY created_time DESC"),
                "跨账户只读仍按创建时间倒序：" + jdbc.records().get(1).sql);
    }

    // ------------------------------------------------------------ T-106：详情面（findByIdScoped）

    @Test
    @DisplayName("T-106 详情(真实 DAO)：非空 scope ⇒ WHERE 含 id = ? AND 白名单子查询 sub_account_id IN (?)，按序绑定")
    void findByIdScopedEmitsIdAndScopePredicate() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmProductDao dao = new DmProductDao(newTx(jdbc));

        Product product = dao.findByIdScoped(5L,
                SubAccountScope.of("sub_account_id", Arrays.asList(7)));

        assertEquals(1, jdbc.records().size(), "详情面单条 SELECT");
        RecordingJdbc.Rec rec = jdbc.records().get(0);
        assertTrue(rec.sql.contains("id = ? AND id IN (SELECT product_id FROM t_sub_product_whitelist"),
                "详情面须 {id=?} 与白名单子查询并列（载体差异：t_product 无 sub_account_id 列）：" + rec.sql);
        assertTrue(rec.sql.contains("sub_account_id IN (?)"), "须含 scope 子句：" + rec.sql);
        assertEquals(Arrays.asList((Object) 5L, 7), rec.args, "参数：主键 + scope；与占位符一一对应");
        assertNull(product, "假 JDBC 无行 ⇒ 无命中（不可见 ≡ 不存在，同一返回 null）");
    }

    @Test
    @DisplayName("T-106 详情(真实 DAO)：空 scope ⇒ 白名单子查询恒假 sub_account_id IN (-1)，不可见即无命中")
    void findByIdScopedEmptyScopeNeverTrue() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmProductDao dao = new DmProductDao(newTx(jdbc));

        Product product = dao.findByIdScoped(5L,
                SubAccountScope.of("sub_account_id", Collections.emptyList()));

        assertEquals(1, jdbc.records().size());
        RecordingJdbc.Rec rec = jdbc.records().get(0);
        assertTrue(rec.sql.contains("id IN (SELECT product_id FROM t_sub_product_whitelist"), rec.sql);
        assertTrue(rec.sql.contains("sub_account_id IN (-1)"),
                "空 scope 须产出恒假条件（无命中），不得退化为不过滤：" + rec.sql);
        assertEquals(Collections.singletonList((Object) 5L), rec.args, "空 scope 仅绑定主键");
        assertNull(product, "恒假 ⇒ 无命中");
    }

    @Test
    @DisplayName("T-106 详情(服务层→真实 DAO)：受限身份 getProduct 过滤落到详情 SQL；哨兵 0 ⇒ 不加过滤")
    void serviceGetProductScopedSql() {
        // 受限身份（可见 [7]）：过滤落详情 SQL
        RecordingJdbc limited = new RecordingJdbc();
        TxTemplate limitedTx = newTx(limited);
        VarietyService limitedService = new VarietyService(limitedTx, new DmProductDao(limitedTx),
                null, null, null, null, null, null, null, uid -> Arrays.asList(7));

        limitedService.getProduct(5L, 42L);

        assertEquals(1, limited.records().size());
        assertTrue(limited.records().get(0).sql.contains("sub_account_id IN (?)"),
                "受限身份须把子账户过滤落到详情 SQL：" + limited.records().get(0).sql);
        assertEquals(Arrays.asList((Object) 5L, 7), limited.records().get(0).args);

        // 跨账户只读（哨兵 0）：不加过滤
        RecordingJdbc cross = new RecordingJdbc();
        TxTemplate crossTx = newTx(cross);
        VarietyService crossService = new VarietyService(crossTx, new DmProductDao(crossTx),
                null, null, null, null, null, null, null,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL));

        crossService.getProduct(5L, 42L);

        assertEquals(1, cross.records().size());
        assertFalse(cross.records().get(0).sql.contains("sub_account_id IN"),
                "哨兵 0 不得加子账户 IN 过滤：" + cross.records().get(0).sql);
        assertEquals(Collections.singletonList((Object) 5L), cross.records().get(0).args,
                "哨兵 0 详情仅按主键");
    }

    // ------------------------------------------------------------ T-151：顶层谓词「仍生效」（防谓词中和）

    /**
     * {@code T-151} 负向守卫：{@code findByIdScoped} 的 SQL <b>顶层不得含 {@code OR}</b>，
     * 且占位符个数须与绑定参数个数**一致**。
     *
     * <p><b>为何要这条</b>：既有形状断言用 {@code contains("id = ? AND id IN (SELECT …")} ——
     * 它能挡「把 {@code AND} 改成 {@code OR}」，却<b>挡不住</b>「在 SQL 尾部追加 {@code OR 1=1}」这类
     * <b>谓词中和</b>（SQL 里「谓词还在、绑定也对」，语义却被旁路）。故此处补两条<b>语义级</b>断言：
     * ① 顶层<b>不存在</b> {@code OR}；② 占位符数 == 参数数（防"多写谓词少绑参 / 多绑少写"）。
     * 任一被中和即红（见交付报告变异台账 MUT-2）。
     */
    @Test
    @DisplayName("T-151 顶层谓词仍生效：findByIdScoped 的 SQL 顶层无 OR，且 占位符数==绑定参数数")
    void findByIdScopedTopLevelPredicateStillEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmProductDao dao = new DmProductDao(newTx(jdbc));

        dao.findByIdScoped(5L, SubAccountScope.of("sub_account_id", Arrays.asList(7, 8)));

        RecordingJdbc.Rec rec = jdbc.records().get(0);
        assertFalse(rec.sql.contains(" OR "),
                "顶层谓词不得被中和（SQL 不得含顶层 OR）：" + rec.sql);
        assertEquals(placeholderCount(rec.sql), rec.args.size(),
                "占位符个数须与绑定参数个数一致（防谓词中和/漏绑）：" + rec.sql);
        assertEquals(3, rec.args.size(), "id + 两个子账户 = 3 个绑定：");
        assertTrue(rec.sql.contains("WHERE id = ? AND id IN (SELECT product_id FROM t_sub_product_whitelist"),
                "顶层须为 {id=?} AND 白名单子查询：" + rec.sql);
    }

    @Test
    @DisplayName("T-151 顶层谓词仍生效：服务层受限身份 getProduct 的详情 SQL 顶层无 OR，占位符==参数")
    void serviceGetProductTopLevelPredicateStillEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        TxTemplate tx = newTx(jdbc);
        VarietyService service = new VarietyService(tx, new DmProductDao(tx), null, null, null, null,
                null, null, null, uid -> Arrays.asList(7));

        service.getProduct(5L, 42L);

        RecordingJdbc.Rec rec = jdbc.records().get(0);
        assertFalse(rec.sql.contains(" OR "), "顶层谓词不得被中和：" + rec.sql);
        assertEquals(placeholderCount(rec.sql), rec.args.size(),
                "占位符个数须与绑定参数个数一致：" + rec.sql);
        assertEquals(Arrays.asList((Object) 5L, 7), rec.args,
                "参数 = 主键 + 可见子账户，与占位符一一对应");
    }

    /** 统计 SQL 中 {@code ?} 占位符个数（{@code ??}/{@code ?} 皆为占位符；本 SQL 无字面问号）。 */
    private static int placeholderCount(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------ 工具

    private static TxTemplate newTx(RecordingJdbc jdbc) {
        return new TxTemplate(new DataSourcePool(jdbc, new PoolConfig(0, 2, 500, 2)));
    }
    /**
     * 记录型假 JDBC：捕获<b>每一条</b> PreparedStatement 的 SQL 与按序参数；含 {@code COUNT(*)}
     * 的查询返回一行固定值 {@link #COUNT_VALUE}，其余查询返回空结果集。
     */
    private static final class RecordingJdbc implements ConnectionFactory {

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
                        return "CountResultSet";
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
                        return "EmptyResultSet";
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
