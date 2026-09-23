package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.ScopedPredicateGuard;
import com.hedgeguard.dao.tx.TxTemplate;
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
 * <b>「其余 scoped SQL」的语义级守卫</b>（{@code T-152}；{@code NFR-SEC-7} 隔离须真生效 / {@code NFR-MAINT-1}）。
 *
 * <p><b>为何要这组用例</b>：既有 scoped SQL 用例（{@code DmProductDaoScopedSqlTest}、
 * {@code DmExposureDaoScopedSqlTest}、{@code DmTradePlanDaoScopedSqlTest}）用 {@code contains(...)}
 * <b>形状断言</b> —— 只证「谓词写了 + 值绑了」，<b>不证「谓词生效」</b>。复核席 {@code MUT-5} / {@code MUT-C2}
 * 已独立复现：在 scope 子句尾附 {@code OR 1=1}（「谓词在、语义中和」）⇒ 形状断言<b>全绿</b>，隔离已被旁路。
 * {@code T-151} 已为 {@code DmProductDao.findByIdScoped} <b>一条</b> SQL 补了语义级守卫；本用例<b>穷举</b>
 * 其余全部 scoped SQL 落点，逐处施加同一语义级判据（见 {@link ScopedPredicateGuard}）。
 *
 * <p><b>穷举范围 = {@code AbstractDmDao} 系 scoped 方法 + 各 {@code Dm*Dao} 覆写的 scoped SQL</b>
 * （10 个落点；每个落点每产生一条 SQL 即断言一次）：
 * <ol>
 *   <li>{@code DmProductDao.findByIdScoped}（白名单子查询；{@code T-151} 已覆盖，此处仅<b>并存</b>不重复守卫）；</li>
 *   <li>{@code DmProductDao.findPageScoped}（COUNT + SELECT 两条；全局唯一自建分页 scoped SQL）；</li>
 *   <li>{@code DmProductDao.findAllScoped}（此前<b>无任何真实 DAO 用例</b>）；</li>
 *   <li>{@code DmExposureDao.findByIdScoped}；</li>
 *   <li>{@code DmExposureDao.findPageScoped}（{@code super}，{@code AbstractDmDao} 落点）；</li>
 *   <li>{@code DmTradePlanDao.findByHedgePlanScoped}（此前<b>无任何真实 DAO 用例</b>）；</li>
 *   <li>{@code DmTradePlanDao.findPageScoped}（{@code super}）；</li>
 *   <li>{@code DmHedgePlanDao.findByIdScoped}（此前<b>无任何真实 DAO 用例</b>）；</li>
 *   <li>{@code DmHedgePlanDao.findPageScoped}（{@code super}，此前<b>无任何真实 DAO 用例</b>）。</li>
 * </ol>
 *
 * <p><b>判据（三点，见 {@link ScopedPredicateGuard}）</b>：① 剔注释 / 字面量后 SQL <b>不含</b> {@code OR}
 * 记号（任意括号深度 —— 白名单子查询载体的 scope 谓词在子查询内，仅断言顶层会漏检，理由见守卫类注释）；
 * ② {@code ?} 占位符数 == 绑定参数数；③ scope 谓词片段仍在（防整体删除）。
 * 三点<b>任一被注入击落即红</b>：谓词中和（尾附 {@code OR 1=1}）⇒ ①红；加谓词不绑参 / 多绑少写 ⇒ ②红；
 * 删除 scope ⇒ ③红。
 *
 * <p>本用例以记录型假 JDBC 驱动<b>真实</b> DAO（{@code NFR-TEST-2}：不引 Mockito）。完全离线。
 * <b>不得改写</b>{@code T-151} 已交付的 2 条守卫与其 5 条断言（本文件为纯增量）。
 */
class ScopedSqlPredicateEffectTest {

    /** 受限身份（可见子账户 11、22）。 */
    private static final SubAccountScope SCOPE = SubAccountScope.of("sub_account_id", Arrays.asList(11, 22));

    // ============================================================ DmProductDao 三落点（子查询载体）

    @Test
    @DisplayName("T-152 S4：DmProductDao.findByIdScoped（白名单子查询）谓词生效 —— 无 OR / 占位符==参数 / scope 在")
    void productFindByIdScopedPredicateEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new DmProductDao(newTx(jdbc)).findByIdScoped(5L, SCOPE);

        assertEquals(1, jdbc.records().size(), "详情面单条 SELECT");
        assertPredicateEffective(jdbc.records(),
                "id IN (SELECT product_id FROM t_sub_product_whitelist");
    }

    @Test
    @DisplayName("T-152 S2：DmProductDao.findPageScoped（COUNT + SELECT 两条）谓词生效")
    void productFindPageScopedPredicateEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new DmProductDao(newTx(jdbc)).findPageScoped(DEFAULT_PRODUCT_QUERY, SCOPE, null, null);

        assertEquals(2, jdbc.records().size(), "分页路径 COUNT + SELECT 两条 SQL");
        assertPredicateEffective(jdbc.records(),
                "id IN (SELECT product_id FROM t_sub_product_whitelist");
    }

    @Test
    @DisplayName("T-152 S3：DmProductDao.findAllScoped（此前无真实 DAO 用例）谓词生效")
    void productFindAllScopedPredicateEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new DmProductDao(newTx(jdbc)).findAllScoped(SCOPE);

        assertEquals(1, jdbc.records().size(), "导出面单条 SELECT");
        assertPredicateEffective(jdbc.records(),
                "id IN (SELECT product_id FROM t_sub_product_whitelist");
    }

    // ============================================================ DmExposureDao 两落点（本表列载体）

    @Test
    @DisplayName("T-152 S5：DmExposureDao.findByIdScoped 谓词生效")
    void exposureFindByIdScopedPredicateEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new DmExposureDao(newTx(jdbc)).findByIdScoped(9L, SCOPE);

        assertEquals(1, jdbc.records().size(), "详情面单条 SELECT");
        assertPredicateEffective(jdbc.records(), "sub_account_id IN (?,?)");
    }

    @Test
    @DisplayName("T-152 S1a：DmExposureDao.findPageScoped（super 落点，COUNT + SELECT）谓词生效")
    void exposureFindPageScopedPredicateEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new DmExposureDao(newTx(jdbc)).findPageScoped(DEFAULT_EXPOSURE_QUERY, SCOPE,
                "status = ?", Collections.singletonList((Object) "PENDING_HEDGE"));

        assertEquals(2, jdbc.records().size());
        assertPredicateEffective(jdbc.records(), "sub_account_id IN (?,?)");
    }

    // ============================================================ DmTradePlanDao 两落点

    @Test
    @DisplayName("T-152 S6：DmTradePlanDao.findByHedgePlanScoped（此前无真实 DAO 用例）谓词生效")
    void tradePlanFindByHedgePlanScopedPredicateEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new DmTradePlanDao(newTx(jdbc)).findByHedgePlanScoped(1L, SCOPE);

        assertEquals(1, jdbc.records().size(), "计划内交易计划单条 SELECT");
        assertPredicateEffective(jdbc.records(), "sub_account_id IN (?,?)");
    }

    @Test
    @DisplayName("T-152 S1b：DmTradePlanDao.findPageScoped（super 落点，COUNT + SELECT）谓词生效")
    void tradePlanFindPageScopedPredicateEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new DmTradePlanDao(newTx(jdbc)).findPageScoped(DEFAULT_TRADE_PLAN_QUERY, SCOPE,
                "hedge_plan_id = ?", Collections.singletonList((Object) 1L));

        assertEquals(2, jdbc.records().size());
        assertPredicateEffective(jdbc.records(), "sub_account_id IN (?,?)");
    }

    // ============================================================ DmHedgePlanDao 两落点（此前无真实 DAO 用例）

    @Test
    @DisplayName("T-152 S7：DmHedgePlanDao.findByIdScoped（此前无真实 DAO 用例）谓词生效")
    void hedgePlanFindByIdScopedPredicateEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new DmHedgePlanDao(newTx(jdbc)).findByIdScoped(3L, SCOPE);

        assertEquals(1, jdbc.records().size(), "详情面单条 SELECT");
        assertPredicateEffective(jdbc.records(), "sub_account_id IN (?,?)");
    }

    @Test
    @DisplayName("T-152 S1c：DmHedgePlanDao.findPageScoped（super 落点，COUNT + SELECT；此前无真实 DAO 用例）谓词生效")
    void hedgePlanFindPageScopedPredicateEffective() {
        RecordingJdbc jdbc = new RecordingJdbc();
        new DmHedgePlanDao(newTx(jdbc)).findPageScoped(DEFAULT_HEDGE_PLAN_QUERY, SCOPE, null, null);

        assertEquals(2, jdbc.records().size());
        assertPredicateEffective(jdbc.records(), "sub_account_id IN (?,?)");
    }

    // ============================================================ 判据非空转自测 + 反中和自证

    @Test
    @DisplayName("T-152 自测：守卫非空转 —— 合成「被中和」的 SQL 必被检出；良性 SQL / ORDER BY 不误报")
    void guardDetectorIsNotVacuous() {
        // 反例：scope 子句尾附 OR 1=1 ⇒ 必被检出
        String neutralizedTop = "SELECT id FROM t_x WHERE id = ? AND sub_account_id IN (?,?) OR 1=1";
        String neutralizedSub = "SELECT id FROM t_x WHERE id IN (SELECT product_id FROM w WHERE sub_account_id IN (?,?) OR 1=1)";
        assertTrue(ScopedPredicateGuard.containsNeutralizingOr(neutralizedTop),
                "顶层尾附 OR 1=1 须被检出：" + neutralizedTop);
        assertTrue(ScopedPredicateGuard.containsNeutralizingOr(neutralizedSub),
                "★子查询内尾附 OR 1=1（白名单载体形态）须被检出（顶层判据会漏此例）：" + neutralizedSub);
        assertTrue(ScopedPredicateGuard.containsNeutralizingOr("SELECT id FROM t_x WHERE a = 1 or 2 = 2"),
                "小写 or 亦须被检出（大小写不敏感）");

        // 正例：良性 SQL 不得误报
        assertFalse(ScopedPredicateGuard.containsNeutralizingOr(
                        "SELECT id FROM t_x WHERE id = ? AND sub_account_id IN (?,?) ORDER BY exec_deadline ASC"),
                "良性 scoped SQL 不得误报");
        assertFalse(ScopedPredicateGuard.containsNeutralizingOr(
                        "SELECT id FROM t_order WHERE order_no = ? ORDER BY created_time DESC"),
                "★ORDER BY 含子串 or，不得误报（词边界）：");
        assertFalse(ScopedPredicateGuard.containsNeutralizingOr(
                        "SELECT id FROM t_x WHERE note = 'A OR B' AND sub_account_id IN (?)"),
                "字面量内的 OR 不得误报（须先剔字面量）");

        // 占位符计数须剔字面量 / 注释
        assertEquals(3, ScopedPredicateGuard.placeholderCount(
                        "SELECT id FROM t_x WHERE id = ? AND sub_account_id IN (?,?) /* ? */ AND c = '?'"),
                "占位符计数须忽略注释与字面量内的 ?");
    }

    // ------------------------------------------------------------ 工具

    /** 默认分页查询（product 按创建时间倒序）。 */
    private static final PageQuery DEFAULT_PRODUCT_QUERY =
            PageQuery.of(null, null, "created_time,desc", "created_time");
    /** 默认分页查询（exposure 按敞口数量倒序）。 */
    private static final PageQuery DEFAULT_EXPOSURE_QUERY =
            PageQuery.of(null, null, "exposure_qty,desc", "exposure_qty");
    /** 默认分页查询（trade plan 按执行期限升序）。 */
    private static final PageQuery DEFAULT_TRADE_PLAN_QUERY =
            PageQuery.of(null, null, null, "exec_deadline");
    /** 默认分页查询（hedge plan 按计划号）。 */
    private static final PageQuery DEFAULT_HEDGE_PLAN_QUERY =
            PageQuery.of(null, null, null, "plan_no");

    /** 对每条 recorded SQL 施加三点语义级判据（{@link ScopedPredicateGuard}）。 */
    private static void assertPredicateEffective(List<RecordingJdbc.Rec> recs, String scopeFragment) {
        assertFalse(recs.isEmpty(), "须捕获到 scoped SQL（否则守卫空转）");
        for (RecordingJdbc.Rec rec : recs) {
            assertFalse(ScopedPredicateGuard.containsNeutralizingOr(rec.sql),
                    "★scoped SQL 被「谓词中和」（含 OR 记号），隔离可能被旁路：" + rec.sql);
            assertEquals(ScopedPredicateGuard.placeholderCount(rec.sql), rec.args.size(),
                    "? 占位符数须等于绑定参数数（防谓词中和 / 漏绑）：" + rec.sql);
            assertTrue(rec.sql.contains(scopeFragment),
                    "scope 谓词须存在（防整体删除）；期望含 [" + scopeFragment + "]，实际=" + rec.sql);
        }
    }

    private static TxTemplate newTx(RecordingJdbc jdbc) {
        return new TxTemplate(new DataSourcePool(jdbc, new PoolConfig(0, 2, 500, 2)));
    }

    /**
     * 记录型假 JDBC：捕获<b>每一条</b> PreparedStatement 的 SQL 与按序参数（凡 {@code setXxx(int,·)}
     * 形态的绑定都记录）；查询统一返回空结果集（本用例只断言 SQL，不求值）。
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
                        return "T152RecordingConnection";
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
                String name = method.getName();
                if (name.startsWith("set") && methodArgs != null && methodArgs.length == 2
                        && methodArgs[0] instanceof Integer) {
                    rec.record((Integer) methodArgs[0], methodArgs[1]);
                    return null;
                }
                switch (name) {
                    case "executeQuery":
                        return emptyResultSet();
                    case "close":
                        return null;
                    case "toString":
                        return "T152RecordingStatement";
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

        private static ResultSet emptyResultSet() {
            InvocationHandler handler = (rs, method, methodArgs) -> {
                switch (method.getName()) {
                    case "next":
                        return false;
                    case "close":
                        return null;
                    case "toString":
                        return "T152EmptyResultSet";
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
