package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.SpotService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DmExposureDao} 的<b>真实 SQL 形状</b>断言（{@code T-107} 验收 5/6 / {@code NFR-SEC-7} /
 * {@code FR-SPOT-15}）。
 *
 * <p><b>为何要这组用例</b>：服务层用例（{@code SpotExposureIsolationTest}）用<b>假 DAO</b> 覆盖权限
 * <b>语义</b>，但"假 DAO 忠实模拟 SQL"这一前提本身未被真实 SQL 层证实 —— 若
 * {@code DmExposureDao.findPageScoped} 把 scope 漏拼 / 拼错列，假 DAO 的用例仍会绿。本用例以记录型假
 * JDBC（{@code NFR-TEST-2}：不引 Mockito）驱动<b>真实</b> {@code DmExposureDao}。
 *
 * <p>{@code t_exposure} 有 {@code sub_account_id} 列，scoped 过滤<b>直接落在本表列</b>上
 * （{@code sub_account_id IN (...)}）。分页路径产生<b>两条 SQL</b>（独立 {@code COUNT} 与 {@code SELECT}），
 * 本用例对<b>两条</b>均断言 scope 条件：
 * <ul>
 *   <li><b>空 scope</b> ⇒ 两 SQL 的 WHERE 均含恒假条件 {@code sub_account_id IN (-1)}；</li>
 *   <li><b>非空 scope</b> ⇒ 两 SQL 均含 {@code sub_account_id IN (?,?)} 且按序绑定子账户 ID；</li>
 *   <li><b>服务层 → 真实 DAO</b>：{@code SpotService.listPendingHedge} 的过滤确实落到<b>两条</b> SQL；</li>
 *   <li><b>哨兵 0</b>（跨账户只读）⇒ 两 SQL <b>不含</b> {@code sub_account_id IN} 条件（不加过滤）；</li>
 *   <li>SELECT 排序 {@code ORDER BY exposure_qty DESC, id ASC}（{@code FR-SPOT-15}「默认按敞口金额或数量倒序」）。</li>
 * </ul>
 */
class DmExposureDaoScopedSqlTest {

    /** 默认分页（缺省页 1 / 每页 20 / 字段 {@code exposure_qty} + 显式 {@code desc}）。 */
    private static final PageQuery DEFAULT_QUERY =
            PageQuery.of(null, null, "exposure_qty,desc", "exposure_qty");

    /** 列表服务固定的状态谓词（{@code SpotService.listPendingHedge} 附加）。 */
    private static final List<Object> STATUS_ARG = Collections.singletonList((Object) "PENDING_HEDGE");

    /** 假 COUNT(*) 的返回值：用于证明 {@code PageResult.total} 取自 COUNT 查询。 */
    private static final long COUNT_VALUE = 7L;

    @Test
    @DisplayName("真实 DAO：空 scope ⇒ COUNT 与 SELECT 两条 SQL 的 WHERE 均含恒假条件 sub_account_id IN (-1)")
    void emptyScopeEmitsNeverTrueConditionInBothSql() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmExposureDao dao = new DmExposureDao(newTx(jdbc));

        PageResult<Exposure> page = dao.findPageScoped(DEFAULT_QUERY,
                SubAccountScope.of("sub_account_id", Collections.emptyList()), "status = ?", STATUS_ARG);

        assertEquals(2, jdbc.records().size(), "分页路径应产生两条 SQL（COUNT + SELECT）");
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("sub_account_id IN (-1)"),
                    "空 scope 须在 COUNT 与 SELECT 均产出恒假条件（返回空），不得退化为不过滤：" + rec.sql);
        }
        RecordingJdbc.Rec count = jdbc.records().get(0);
        RecordingJdbc.Rec select = jdbc.records().get(1);
        assertTrue(count.sql.contains("COUNT(*)"), "第一条须为 COUNT：" + count.sql);
        assertEquals(STATUS_ARG, count.args, "COUNT 绑定 status 参数：" + count.sql);
        assertTrue(select.sql.contains("ORDER BY exposure_qty DESC, id ASC"),
                "默认排序须为敞口数量倒序 + id 兜底（FR-SPOT-15）：" + select.sql);
        assertTrue(select.sql.contains("LIMIT ? OFFSET ?"), "SELECT 须分页：" + select.sql);
        assertEquals(Arrays.asList((Object) "PENDING_HEDGE", 20, 0), select.args,
                "SELECT 绑定：status + LIMIT(20) + OFFSET(0)：" + select.sql);
        assertEquals(0L, page.items().size(), "假 JDBC 无行 ⇒ items 空");
    }

    @Test
    @DisplayName("真实 DAO：非空 scope ⇒ COUNT 与 SELECT 两条 SQL 均含 sub_account_id IN (?,?)，按序绑定")
    void nonEmptyScopeEmitsInClauseInBothSql() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmExposureDao dao = new DmExposureDao(newTx(jdbc));

        dao.findPageScoped(DEFAULT_QUERY,
                SubAccountScope.of("sub_account_id", Arrays.asList(11, 22)), "status = ?", STATUS_ARG);

        assertEquals(2, jdbc.records().size());
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("sub_account_id IN (?,?)"),
                    "两条 SQL 皆须含子账户 IN 子句：实际=" + rec.sql);
        }
        assertEquals(Arrays.asList((Object) "PENDING_HEDGE", 11, 22), jdbc.records().get(0).args,
                "COUNT 参数顺序：status + 子账户 ID");
        assertEquals(Arrays.asList((Object) "PENDING_HEDGE", 11, 22, 20, 0), jdbc.records().get(1).args,
                "SELECT 参数：status + scope 参数 + LIMIT + OFFSET；与占位符一一对应");
    }

    @Test
    @DisplayName("真实 DAO：详情 findByIdScoped ⇒ WHERE id = ? AND sub_account_id IN (?)，按序绑定")
    void findByIdScopedEmitsIdAndScope() {
        RecordingJdbc jdbc = new RecordingJdbc();
        DmExposureDao dao = new DmExposureDao(newTx(jdbc));

        Exposure found = dao.findByIdScoped(9L, SubAccountScope.of("sub_account_id", Arrays.asList(7)));

        assertEquals(1, jdbc.records().size(), "详情路径只产生一条 SELECT");
        RecordingJdbc.Rec select = jdbc.records().get(0);
        assertTrue(select.sql.startsWith("SELECT "), select.sql);
        assertTrue(select.sql.contains("WHERE id = ? AND sub_account_id IN (?)"),
                "详情须同时带主键与 scope 条件（不可见 ≡ 不存在）：" + select.sql);
        assertEquals(Arrays.asList((Object) 9L, 7), select.args, "绑定：主键 + 可见子账户");
        assertNull(found, "假 JDBC 无行 ⇒ 详情 null");
    }

    @Test
    @DisplayName("服务层→真实 DAO：受限身份（可见 [7]）的过滤落到 COUNT 与 SELECT 两条 SQL；total 取自 COUNT")
    void serviceLayerForcesScopedSqlInPagination() {
        RecordingJdbc jdbc = new RecordingJdbc();
        TxTemplate tx = newTx(jdbc);
        ExposureDao dao = new DmExposureDao(tx);
        SpotService service = service(tx, dao, uid -> Arrays.asList(7));

        PageResult<Exposure> page = service.listPendingHedge(42L, DEFAULT_QUERY, null);

        assertEquals(2, jdbc.records().size(), "分页 + 隔离 ⇒ COUNT 与 SELECT 两条 SQL");
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("sub_account_id IN (?)"),
                    "受限身份须把子账户过滤落到该条 SQL：" + rec.sql);
        }
        assertEquals(Arrays.asList((Object) "PENDING_HEDGE", 7), jdbc.records().get(0).args,
                "COUNT 绑定 status + 可见子账户");
        assertEquals(Arrays.asList((Object) "PENDING_HEDGE", 7, 20, 0), jdbc.records().get(1).args,
                "SELECT 绑定 status + 可见子账户 + LIMIT + OFFSET");
        assertEquals(COUNT_VALUE, page.total(), "total 须取自带 scope 的 COUNT 子查询（过滤后总数）");
    }

    @Test
    @DisplayName("服务层→真实 DAO：跨账户只读（哨兵 0）⇒ 两条 SQL 均不含 sub_account_id IN 过滤")
    void crossReadonlySkipsScopeInSqlForPagination() {
        RecordingJdbc jdbc = new RecordingJdbc();
        TxTemplate tx = newTx(jdbc);
        ExposureDao dao = new DmExposureDao(tx);
        SpotService service = service(tx, dao,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL));

        service.listPendingHedge(42L, DEFAULT_QUERY, null);

        assertEquals(2, jdbc.records().size());
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertFalse(rec.sql.contains("sub_account_id IN"),
                    "跨账户只读不得加子账户 IN 过滤（哨兵 0 不可进 IN 列表）：" + rec.sql);
        }
        assertEquals(STATUS_ARG, jdbc.records().get(0).args, "COUNT 仅绑定 status");
        assertEquals(Arrays.asList((Object) "PENDING_HEDGE", 20, 0), jdbc.records().get(1).args,
                "SELECT 绑定：status + LIMIT + OFFSET");
        assertTrue(jdbc.records().get(1).sql.contains("ORDER BY exposure_qty DESC, id ASC"),
                "跨账户只读仍按敞口数量倒序：" + jdbc.records().get(1).sql);
    }

    @Test
    @DisplayName("服务层→真实 DAO：收窄过滤 subAccountId ⇒ WHERE 增 sub_account_id = ? 且与 scope 取交集")
    void serviceLayerNarrowingFilterCoexistsWithScope() {
        RecordingJdbc jdbc = new RecordingJdbc();
        TxTemplate tx = newTx(jdbc);
        ExposureDao dao = new DmExposureDao(tx);
        SpotService service = service(tx, dao, uid -> Arrays.asList(7, 8));

        service.listPendingHedge(42L, DEFAULT_QUERY, 8);

        for (RecordingJdbc.Rec rec : jdbc.records()) {
            assertTrue(rec.sql.contains("status = ? AND sub_account_id = ?"),
                    "收窄条件须出现：" + rec.sql);
            assertTrue(rec.sql.contains("sub_account_id IN (?,?)"),
                    "scope 条件须并存（收窄不得替代隔离）：" + rec.sql);
        }
        assertEquals(Arrays.asList((Object) "PENDING_HEDGE", 8, 7, 8), jdbc.records().get(0).args,
                "COUNT 参数：status + 收窄子账户 + scope 子账户");
    }

    // ------------------------------------------------------------ 工具

    private static SpotService service(TxTemplate tx, ExposureDao dao, SubAccountVisibility visibility) {
        return new SpotService(tx, null, null, null, null, null, null, dao, null, null,
                null, null, null, null, visibility, null);
    }

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
                    case "setString":
                    case "setDate":
                    case "setBigDecimal":
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
