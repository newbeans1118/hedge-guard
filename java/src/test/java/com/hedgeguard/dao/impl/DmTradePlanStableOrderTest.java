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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code T-096} 稳定排序在 <b>DAO 级</b> 的「<b>两页并集基数</b>」判据（<b>真实 DAO + 假引擎</b>）。
 *
 * <p><b>为何要这组用例</b>：{@code PageQueryTest} 的稳定排序用例只覆盖公共件自身的
 * {@code orderBy()} 语义；但生产症状（{@code T-103} 独立复核：分页后 {@code ORDER BY} 仅单列、
 * 同 {@code exec_deadline} 值跨页重复 / 漏行）出在 <b>{@code AbstractDmDao.findPage} 的 ORDER BY 装配点</b>。
 * 故此处用<b>真实 {@code DmTradePlanDao}</b>（经 {@code findPageScoped}）产生<b>真实 SQL</b>，
 * 再以<b>假引擎</b>按该 SQL 的 {@code ORDER BY} 子句求值 {@code LIMIT}/{@code OFFSET}，
 * 判「两页并集基数」。
 *
 * <p><b>假引擎模型</b>：{@code ORDER BY} 未完全区分的等值行，其相对次序交由每次请求的
 * {@code tieOrder}（真实 DB 对「{@code ORDER BY} 未覆盖的键」不保证次序；{@code T-103} 复核即以此复现）。
 * 全序（如末键为主键 {@code id}）时 {@code tieOrder} 不被使用。
 *
 * <p>数据集刻意取 {@code T-103} 场景：<b>15 条同 {@code exec_deadline}</b>、{@code size=10}。
 * <b>正判据</b>：装配层在排序键不含唯一列时自动补主键 {@code id} 收官（{@code T-096} 边界修订），
 * 使默认路径（不传 {@code sort}）的 {@code ORDER BY} 亦为全序 ⇒ 两页并集基数 = 15、无重复、无漏行。
 * 反例判据（单键无 tie-break ⇒ 重复 {@code [1..5]}、漏行 {@code [11..15]}）已随该修订改为正判据，
 * 详见 {@link #defaultPathIsStableAcrossPages}。
 */
class DmTradePlanStableOrderTest {

    /** 假 COUNT(*) 返回值（= 数据集规模，证明 total 取自带 scope 的 COUNT）。 */
    private static final long COUNT_VALUE = 15L;

    @Test
    @DisplayName("DAO 级：多键 orderBy（末键唯一 id）⇒ 真实 SQL 含 tie-break，两页并集基数 = 总数（无重复/漏行）")
    void multiKeyOrderByIsStableAcrossPages() {
        // size=10 且 15 条同 exec_deadline ⇒ 单列必跨页重复/漏行；多键应稳定。
        PageQuery p1 = PageQuery.of("1", "10", "exec_deadline,asc,id,asc",
                PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);
        PageQuery p2 = PageQuery.of("2", "10", "exec_deadline,asc,id,asc",
                PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);

        String orderBy1 = orderByOf(selectSqlOf(p1));
        String orderBy2 = orderByOf(selectSqlOf(p2));
        assertEquals("exec_deadline ASC, id ASC", orderBy1,
                "真实 DAO 须按多键拼 ORDER BY（末键唯一 id 即 tie-break）：" + orderBy1);
        assertEquals(orderBy1, orderBy2, "两页 ORDER BY 须一致（同一排序规范）");

        // 两次请求的等值组内序取相反方向（模拟 DB 对等值行次序不作保证）
        List<Long> union = new ArrayList<>();
        union.addAll(ids(evaluate(orderBy1, p1, Row.BY_ID_ASC)));
        union.addAll(ids(evaluate(orderBy2, p2, Row.BY_ID_DESC)));

        assertEquals(15, union.size(), "两页各 10 / 5 行 ⇒ 并集 15 行：" + union);
        assertEquals(15, new HashSet<>(union).size(), "全序 ⇒ 无重复、无漏行：" + union);
    }

    @Test
    @DisplayName("DAO 级正判据（默认路径）：不传 sort ⇒ 真实 SQL 自动补主键 tie-break，两页并集基数 = 总数")
    void defaultPathIsStableAcrossPages() {
        // 默认路径：不传 sort（与 TradePlanHandler:94 现状一致），仅给默认字段 exec_deadline。
        // 装配层（AbstractDmDao.findPage）须以本表主键 id 兜底 ⇒ 全序。
        PageQuery p1 = PageQuery.of("1", "10", null, PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);
        PageQuery p2 = PageQuery.of("2", "10", null, PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);

        String orderBy1 = orderByOf(selectSqlOf(p1));
        String orderBy2 = orderByOf(selectSqlOf(p2));
        assertEquals("exec_deadline ASC, id ASC", orderBy1,
                "默认路径（不传 sort）须由装配层自动补主键 tie-break：" + orderBy1);
        assertEquals(orderBy1, orderBy2, "两页 ORDER BY 须一致（同一排序规范）");

        // 两次请求的等值组内序取相反方向（模拟 DB 对等值行次序不作保证）
        List<Long> union = new ArrayList<>();
        union.addAll(ids(evaluate(orderBy1, p1, Row.BY_ID_ASC)));
        union.addAll(ids(evaluate(orderBy2, p2, Row.BY_ID_DESC)));

        assertEquals(15, union.size(), "两页各 10 / 5 行 ⇒ 并集 15 行：" + union);
        assertEquals(15, new HashSet<>(union).size(), "全序 ⇒ 无重复、无漏行：" + union);
    }

    @Test
    @DisplayName("DAO 级：默认排序为 exec_deadline 升序 + 主键升序收官（T-096 边界修订后的形状）")
    void defaultOrderByAddsPrimaryKeyTieBreak() {
        PageQuery def = PageQuery.of(null, null, null, PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);
        assertEquals("exec_deadline ASC, id ASC", orderByOf(selectSqlOf(def)),
                "默认路径 ORDER BY = 主排序键 + 主键 tie-break");
    }

    @Test
    @DisplayName("DAO 级：排序键已含唯一列 ⇒ 不重复追加 tie-break")
    void noDuplicateTieBreakWhenIdAlreadyPresent() {
        // 唯一列即便非末键，亦视为已含（id 唯一 ⇒ 仍是全序），不得重复追加。
        PageQuery q = PageQuery.of(null, null, "id,desc", PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);
        assertEquals("id DESC", orderByOf(selectSqlOf(q)),
                "排序键已含 id ⇒ 不得再追加：" + orderByOf(selectSqlOf(q)));
    }

    // ------------------------------------------------------------ 工具

    /** 用真实 {@link DmTradePlanDao} 产生 SQL 并捕获；返回该次 SELECT 的 SQL。 */
    private static String selectSqlOf(PageQuery query) {
        RecordingJdbc jdbc = new RecordingJdbc();
        TradePlanDao dao = new DmTradePlanDao(new TxTemplate(
                new DataSourcePool(jdbc, new PoolConfig(0, 2, 500, 2))));
        dao.findPageScoped(query,
                SubAccountScope.of("sub_account_id", Collections.singletonList(7)),
                "hedge_plan_id = ?", Collections.singletonList((Object) 1L));
        for (RecordingJdbc.Rec rec : jdbc.records()) {
            if (rec.sql.contains("LIMIT ? OFFSET ?")) {
                return rec.sql;
            }
        }
        throw new IllegalStateException("未捕获到分页 SELECT；复现=检查 DmTradePlanDao.findPageScoped 路径");
    }

    /** 从 SQL 中取出 {@code ORDER BY} 子句（到 {@code LIMIT} 前）。 */
    private static String orderByOf(String sql) {
        int at = sql.indexOf(" ORDER BY ");
        int lim = sql.indexOf(" LIMIT", at);
        if (at < 0 || lim < 0) {
            throw new IllegalArgumentException("SQL 缺 ORDER BY / LIMIT：" + sql);
        }
        return sql.substring(at + " ORDER BY ".length(), lim).trim();
    }

    /**
     * 假引擎：按 {@code orderBy} 子句逐键求值，再套 {@code LIMIT}/{@code OFFSET}（取自 {@link PageQuery}）。
     * 等值行次序交由 {@code tieOrder}；构成全序时其不被使用。
     */
    private static List<Row> evaluate(String orderBy, PageQuery q, Comparator<Row> tieOrder) {
        List<Comparator<Row>> keys = parseKeys(orderBy);
        List<Row> ordered = new ArrayList<>(rows());
        ordered.sort((a, b) -> {
            for (Comparator<Row> k : keys) {
                int c = k.compare(a, b);
                if (c != 0) {
                    return c;
                }
            }
            return tieOrder.compare(a, b);
        });
        int from = q.offset();
        if (from >= ordered.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(ordered.subList(from,
                Math.min(from + q.size(), ordered.size())));
    }

    /** 解析 {@code "f1 ASC, f2 DESC"} 为逐键比较器（有序字段 exec_deadline 与唯一键 id）。 */
    private static List<Comparator<Row>> parseKeys(String orderBy) {
        List<Comparator<Row>> keys = new ArrayList<>();
        for (String part : orderBy.split(",")) {
            String[] tok = part.trim().split("\\s+");
            boolean asc = "ASC".equalsIgnoreCase(tok[1]);
            Comparator<Row> base = "id".equals(tok[0])
                    ? (a, b) -> Long.compare(a.id, b.id)
                    : (a, b) -> Integer.compare(a.key, b.key);
            keys.add(asc ? base : base.reversed());
        }
        return keys;
    }

    /** 数据集：15 条同 exec_deadline（key 恒定）、主键 id=1..15。 */
    private static List<Row> rows() {
        List<Row> data = new ArrayList<>();
        for (long id = 1; id <= 15; id++) {
            data.add(new Row(7, id));
        }
        return data;
    }

    private static List<Long> ids(List<Row> rows) {
        List<Long> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(r.id);
        }
        return out;
    }

    /** 一行：主排序值（exec_deadline，本用例恒定）+ 唯一主键 id。 */
    private static final class Row {

        static final Comparator<Row> BY_ID_ASC = (a, b) -> Long.compare(a.id, b.id);
        static final Comparator<Row> BY_ID_DESC = (a, b) -> Long.compare(b.id, a.id);

        private final int key;
        private final long id;

        Row(int key, long id) {
            this.key = key;
            this.id = id;
        }
    }

    /**
     * 记录型假 JDBC：捕获每条 PreparedStatement 的 SQL 与按序参数；{@code COUNT(*)} 返回固定值，
     * 其余（SELECT）返回空结果集（行集由假引擎另行求值；本用例只消费 DAO 产出的 SQL）。
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
                        return "StableRecordingConnection";
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
                        return sql.contains("COUNT(*)") ? countResultSet() : emptyResultSet();
                    case "close":
                        return null;
                    case "toString":
                        return "StableRecordingStatement";
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

        private static ResultSet countResultSet() {
            InvocationHandler handler = (rs, method, methodArgs) -> {
                switch (method.getName()) {
                    case "next":
                        return true;
                    case "getLong":
                        return COUNT_VALUE;
                    case "close":
                        return null;
                    case "toString":
                        return "StableCountResultSet";
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
                        return "StableEmptyResultSet";
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
