package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.entity.TradePlan;
import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.PlanService;
import com.hedgeguard.service.SubAccountVisibility;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DmTradePlanDao} 的<b>真实 SQL 形状</b>断言（{@code NFR-SEC-7} / {@code FR-PLAN-15} 验收 4）。
 *
 * <p><b>为何要这组用例</b>：服务层用例（{@code TradePlanSubAccountIsolationTest}）用<b>假 DAO</b>
 * 覆盖权限<b>语义</b>，但"假 DAO 忠实模拟 SQL"这一前提本身未被真实 SQL 层证实 —— 若
 * {@code DmTradePlanDao.findPageScoped} 把 scope 漏拼 / 拼错列，假 DAO 的用例仍会绿。
 * 本用例以记录型假 JDBC（{@code NFR-TEST-2}：不引 Mockito）驱动<b>真实</b> {@code DmTradePlanDao}。
 *
 * <p>{@code T-103} 分页化后，本端点取数路径为<b>分页</b>的 {@code findPageScoped}（{@code FR-PLAN-15}
 * 「输入」列含<b>分页</b>），故真实 DAO 产生<b>两条 SQL</b>：<b>独立 {@code COUNT}</b> 与 <b>SELECT</b>。
 * 本用例对<b>两条</b>均断言 scope 条件，并断言 SELECT 的 {@code LIMIT ? OFFSET ?} 与绑定值，
 * 以及 {@code total} 确实取自 COUNT 子查询（{@code total} 必须是<b>过滤后</b>总数）：
 * <ul>
 *   <li><b>空 scope</b> ⇒ 两 SQL 的 WHERE 均含恒假条件 {@code sub_account_id IN (-1)}；</li>
 *   <li><b>非空 scope</b> ⇒ 两 SQL 均含 {@code sub_account_id IN (?,?)} 且按序绑定子账户 ID；</li>
 *   <li><b>服务层 → 真实 DAO</b>：{@code PlanService.listTradePlans} 的过滤确实落到<b>两条</b> SQL；</li>
 *   <li><b>哨兵 0</b>（跨账户只读）⇒ 两 SQL <b>不含</b> {@code sub_account_id} 条件（不加过滤）。</li>
 * </ul>
 */
class DmTradePlanDaoScopedSqlTest {

    /** 默认分页（缺省页 1 / 每页 20 / 默认字段 {@code exec_deadline}）。 */
    private static final PageQuery DEFAULT_QUERY =
            PageQuery.of(null, null, null, PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);

    /** 假 COUNT(*) 的返回值：用于证明 {@code PageResult.total} 取自 COUNT 查询。 */
    private static final long COUNT_VALUE = 7L;

    @Test
    @DisplayName("真实 DAO：空 scope ⇒ COUNT 与 SELECT 两条 SQL 的 WHERE 均含恒假条件 sub_account_id IN (-1)")
    void emptyScopeEmitsNeverTrueConditionInBothSql() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmTradePlanDao dao = new DmTradePlanDao(newTx(jdbc));

        PageResult<TradePlan> page = dao.findPageScoped(DEFAULT_QUERY,
                SubAccountScope.of("sub_account_id", Collections.emptyList()),
                "hedge_plan_id = ?", Collections.singletonList((Object) 1L));

        assertEquals(2, jdbc.records().size(), "分页路径应产生两条 SQL（COUNT + SELECT）");
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("hedge_plan_id = ?"), "仍须按计划过滤：" + rec.sql);
            assertTrue(rec.sql.contains("sub_account_id IN (-1)"),
                    "空 scope 须在 COUNT 与 SELECT 均产出恒假条件（返回空），不得退化为不过滤：" + rec.sql);
        }
        RecordingJdbc.Rec count = jdbc.records().get(0);
        RecordingJdbc.Rec select = jdbc.records().get(1);
        assertTrue(count.sql.contains("COUNT(*)"), "第一条须为 COUNT：" + count.sql);
        assertEquals(Collections.singletonList((Object) 1L), count.args,
                "COUNT 仅绑定计划 ID；恒假条件无参数：" + count.sql);
        assertTrue(select.sql.contains("ORDER BY exec_deadline ASC"),
                "默认排序须为执行期限升序：" + select.sql);
        assertTrue(select.sql.contains("LIMIT ? OFFSET ?"), "SELECT 须分页：" + select.sql);
        assertEquals(Arrays.asList((Object) 1L, 20, 0), select.args,
                "SELECT 绑定：计划 ID + LIMIT(20) + OFFSET(0)：" + select.sql);
        assertEquals(0L, page.items().size(), "假 JDBC 无行 ⇒ items 空");
    }

    @Test
    @DisplayName("真实 DAO：非空 scope ⇒ COUNT 与 SELECT 两条 SQL 均含 sub_account_id IN (?,?)，按序绑定")
    void nonEmptyScopeEmitsInClauseInBothSql() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmTradePlanDao dao = new DmTradePlanDao(newTx(jdbc));

        dao.findPageScoped(DEFAULT_QUERY, SubAccountScope.of("sub_account_id", Arrays.asList(11, 22)),
                "hedge_plan_id = ?", Collections.singletonList((Object) 7L));

        assertEquals(2, jdbc.records().size());
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("(hedge_plan_id = ?) AND sub_account_id IN (?,?)"),
                    "条件形态应为 (计划) AND (子账户 IN)，两条 SQL 皆然：实际=" + rec.sql);
        }
        assertEquals(Arrays.asList((Object) 7L, 11, 22), jdbc.records().get(0).args,
                "COUNT 参数顺序：先 hedge_plan_id，后子账户 ID");
        assertEquals(Arrays.asList((Object) 7L, 11, 22, 20, 0), jdbc.records().get(1).args,
                "SELECT 参数：where 参数 + LIMIT + OFFSET；与占位符一一对应");
    }

    @Test
    @DisplayName("服务层→真实 DAO：受限身份（可见 [7]）的过滤落到 COUNT 与 SELECT 两条 SQL；total 取自 COUNT")
    void serviceLayerForcesScopedSqlInPagination() {
        RecordingJdbc jdbc = new RecordingJdbc();
        TxTemplate tx = newTx(jdbc);
        TradePlanDao dao = new DmTradePlanDao(tx);
        PlanService service = new PlanService(tx, null, null, null, null, null, null, null, dao,
                null, null, uid -> Arrays.asList(7), null, null, null, null, null, null);

        PageResult<TradePlan> page = service.listTradePlans(1L, 42L, DEFAULT_QUERY, null);

        assertEquals(2, jdbc.records().size(), "分页 + 隔离 ⇒ COUNT 与 SELECT 两条 SQL");
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("sub_account_id IN (?)"),
                    "受限身份须把子账户过滤落到该条 SQL：" + rec.sql);
            assertTrue(rec.sql.contains("hedge_plan_id = ?"), "仍按计划取数：" + rec.sql);
        }
        assertEquals(Arrays.asList((Object) 1L, 7), jdbc.records().get(0).args, "COUNT 绑定计划 + 可见子账户");
        assertEquals(Arrays.asList((Object) 1L, 7, 20, 0), jdbc.records().get(1).args,
                "SELECT 绑定计划 + 可见子账户 + LIMIT + OFFSET");
        assertEquals(COUNT_VALUE, page.total(), "total 须取自带 scope 的 COUNT 子查询（过滤后总数）");
    }

    @Test
    @DisplayName("服务层→真实 DAO：跨账户只读（哨兵 0）⇒ 两条 SQL 均不含 sub_account_id 过滤")
    void crossReadonlySkipsScopeInSqlForPagination() {
        RecordingJdbc jdbc = new RecordingJdbc();
        TxTemplate tx = newTx(jdbc);
        TradePlanDao dao = new DmTradePlanDao(tx);
        PlanService service = new PlanService(tx, null, null, null, null, null, null, null, dao,
                null, null, uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL),
                null, null, null, null, null, null);

        service.listTradePlans(1L, 42L, DEFAULT_QUERY, null);

        assertEquals(2, jdbc.records().size());
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("hedge_plan_id = ?"), "仍按计划取数：" + rec.sql);
            assertFalse(rec.sql.contains("sub_account_id IN"),
                    "跨账户只读不得加子账户 IN 过滤（哨兵 0 不可进 IN 列表）：" + rec.sql);
        }
        assertEquals(Collections.singletonList((Object) 1L), jdbc.records().get(0).args, "COUNT 仅绑计划 ID");
        assertEquals(Arrays.asList((Object) 1L, 20, 0), jdbc.records().get(1).args,
                "SELECT 绑定：计划 ID + LIMIT + OFFSET");
        assertTrue(jdbc.records().get(1).sql.contains("LIMIT ? OFFSET ?"), "SELECT 须分页");
    }

    // ------------------------------------------------------------ 工具

    private static TxTemplate newTx(RecordingJdbc jdbc) {
        return new TxTemplate(new DataSourcePool(jdbc, new PoolConfig(0, 2, 500, 2)));
    }

    /**
     * 记录型假 JDBC：捕获<b>每一条</b> PreparedStatement 的 SQL 与按序参数；含 {@code COUNT(*)}
     * 的查询返回一行固定值 {@link #COUNT_VALUE}，其余查询返回空结果集。
     *
     * <p>只覆盖<b>读</b>路径（{@code prepareStatement}/{@code setInt}/{@code setLong}/{@code setObject}/
     * {@code setNull}/{@code executeQuery}）。
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
