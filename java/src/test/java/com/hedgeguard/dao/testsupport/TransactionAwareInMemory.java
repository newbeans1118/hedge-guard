package com.hedgeguard.dao.testsupport;

import com.hedgeguard.dao.pool.ConnectionFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 等价内存引擎的<b>事务感知包装</b>（{@code T-077-FIX / TE-T077-001} 回归专用；仅 {@code src/test}）。
 *
 * <p><b>为何存在</b>：{@link InMemoryDmEngine} 的连接代理对 {@code setAutoCommit(false)} /
 * {@code commit()} / {@code rollback()} 为 no-op（该引擎 javadoc 已披露「事务隔离/回滚不还原行集」），
 * 而 {@code TE-T077-001} 的缺陷形态恰是「回滚语义缺失 ⇒ 拒绝路径残留半笔写」——用该引擎直测会
 * 把真缺陷放行为假绿（修复前探针已实证：摘除事务时内存行集仍残留 {@code is_corrected='Y'}）。
 * 判例先例：{@code SettleCheckT048bFixTest.Store}（连接级 commit/rollback 真实生效的内存库模型，
 * 修复 T-048 「回滚为 no-op 的假事务掩盖缺陷」同类问题）。
 *
 * <p><b>形态</b>：包装 {@link InMemoryDmEngine} 的连接代理——SQL 求值（prepareStatement /
 * createStatement 及其全部语句/结果集方法）原样转发给真实引擎；仅拦截<b>事务控制方法</b>：
 * <ul>
 *   <li>{@code setAutoCommit(false)} ⇒ 对引擎<b>全部表</b>的行集 + 自增游标拍快照；</li>
 *   <li>{@code commit()} ⇒ 丢弃快照（当前状态即最终状态）；</li>
 *   <li>{@code rollback()} ⇒ 用快照还原全部表行集与自增游标（丢弃事务内全部写）。</li>
 * </ul>
 * 约束登记（列/唯一键/CHECK 等 DDL 结构）不在快照范围——回滚不撤销建表（与 DM 语义一致：
 * DDL 隐式提交）。
 *
 * <p><b>诚实披露</b>：本包装只支撑「单连接串行」测试形态（{@code TxTemplate} 借还 + 单线程用例）；
 * 不模拟隔离级别、死锁与并发交错。拒绝路径断言（TE-T077-001 回归用例）依赖的恰是本包装补齐的
 * 回滚语义。
 */
public final class TransactionAwareInMemory implements ConnectionFactory {

    private final InMemoryDmEngine engine;

    /** 每条连接的未决快照（begin 时放入；commit 移除；rollback 用后还原并移除）。 */
    private final Map<Connection, Snapshot> pending = new IdentityHashMap<>();

    public TransactionAwareInMemory(InMemoryDmEngine engine) {
        this.engine = engine;
    }

    /** 已开启未决事务的连接数（自证快照生命周期：commit/rollback 后须归零）。 */
    public int pendingCount() {
        return pending.size();
    }

    @Override
    public Connection create() {
        final Connection real = engine.create();
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setAutoCommit":
                    if (args != null && args.length == 1 && Boolean.FALSE.equals(args[0])) {
                        pending.put((Connection) proxy, Snapshot.of(engine));
                    }
                    return null;
                case "commit":
                    pending.remove(proxy);
                    return null;
                case "rollback":
                    Snapshot snap = pending.remove(proxy);
                    if (snap != null) {
                        snap.restore(engine);
                    }
                    return null;
                case "prepareStatement":
                    return psProxy(real, (String) args[0]);
                case "createStatement":
                    return stProxy(real);
                case "toString":
                    return "TransactionAwareInMemoryConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return forward(real, method.getName(), args, method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /** 语句代理：全部方法原样转发真实语句（本包装不改写 SQL 语义）。 */
    private static Statement stProxy(final Connection real) throws SQLException {
        final Statement target = real.createStatement();
        InvocationHandler handler = (proxy, method, args) ->
                forward(target, method.getName(), args, method.getReturnType());
        return (Statement) Proxy.newProxyInstance(TransactionAwareInMemory.class.getClassLoader(),
                new Class<?>[]{Statement.class}, handler);
    }

    /** 预编译语句代理：全部方法原样转发真实语句。 */
    private static PreparedStatement psProxy(final Connection real, String sql) throws SQLException {
        final PreparedStatement target = real.prepareStatement(sql);
        InvocationHandler handler = (proxy, method, args) ->
                forward(target, method.getName(), args, method.getReturnType());
        return (PreparedStatement) Proxy.newProxyInstance(TransactionAwareInMemory.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, handler);
    }

    /** 反射转发（本包装不改写 SQL 语义；代理统一拦截 hashCode/equals 防身份漂移）。 */
    private static Object forward(Object target, String name, Object[] args, Class<?> returnType)
            throws SQLException {
        if ("hashCode".equals(name)) {
            return System.identityHashCode(target);
        }
        if ("equals".equals(name)) {
            return target == args[0];
        }
        if ("toString".equals(name)) {
            return "InMemoryDmStatement(事务感知包装)";
        }
        try {
            return target.getClass().getMethod(name, parameterTypes(target, name, args)).invoke(target, args);
        } catch (NoSuchMethodException e) {
            // 未实现的方法（如引擎代理默认返回 defaultValue 的分支）：按类型给默认值
            return defaultValue(returnType);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException) {
                throw (SQLException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("转发失败：" + name, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("转发失败（不可访问）：" + name, e);
        }
    }

    private static Class<?>[] parameterTypes(Object target, String name, Object[] args)
            throws NoSuchMethodException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == (args == null ? 0 : args.length)) {
                    return m.getParameterTypes();
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    /** 引擎快照：全部表的行集深拷贝 + 自增游标。 */
    private static final class Snapshot {

        private final Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        private final Map<String, Long> nextIds = new LinkedHashMap<>();

        static Snapshot of(InMemoryDmEngine engine) {
            Snapshot s = new Snapshot();
            for (InMemoryDmEngine.Table t : engine.tables().values()) {
                s.rows.put(t.name, deepCopy(t.rows));
                s.nextIds.put(t.name, t.nextId);
            }
            return s;
        }

        void restore(InMemoryDmEngine engine) {
            for (InMemoryDmEngine.Table t : engine.tables().values()) {
                List<Map<String, Object>> saved = rows.get(t.name);
                t.rows.clear();
                if (saved != null) {
                    t.rows.addAll(deepCopy(saved));
                }
                Long nid = nextIds.get(t.name);
                if (nid != null) {
                    t.nextId = nid;
                }
            }
        }

        private static List<Map<String, Object>> deepCopy(List<Map<String, Object>> src) {
            List<Map<String, Object>> out = new ArrayList<>(src.size());
            for (Map<String, Object> row : src) {
                out.add(new LinkedHashMap<>(row));
            }
            return out;
        }
    }
}
