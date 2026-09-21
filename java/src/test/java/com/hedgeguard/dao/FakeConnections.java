package com.hedgeguard.dao;

import com.hedgeguard.dao.pool.ConnectionFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假 JDBC 连接（{@code NFR-TEST-2}：DM 依赖用内存假对象替代；{@code §7.2.2}：测试打桩不引 Mockito）。
 *
 * <p>用 {@link Proxy} 动态实现 {@link Connection}，只拦截连接池与事务模板真正用到的方法
 * （{@code close}/{@code isClosed}/{@code isValid}/{@code getAutoCommit}/{@code setAutoCommit}/
 * {@code commit}/{@code rollback}），其余返回类型默认值。
 */
final class FakeConnections {

    private FakeConnections() {
    }

    /** 单条假连接的状态。 */
    static final class Fake implements ConnectionFactory {

        private final List<ConnState> created = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Connection create() {
            ConnState st = new ConnState(seq.incrementAndGet());
            created.add(st);
            return proxy(st);
        }

        /** 已创建的连接状态（按创建顺序）。 */
        List<ConnState> created() {
            return created;
        }

        /** 当前未关闭的连接数。 */
        long openCount() {
            return created.stream().filter(s -> !s.closed).count();
        }

        /** 建连总次数。 */
        int createCount() {
            return created.size();
        }

        /** 把第 n 条（0 起）连接标记为失效（模拟连接被服务端断开）。 */
        void breakConnection(int index) {
            created.get(index).broken = true;
        }
    }

    /** 假连接状态。 */
    static final class ConnState {

        final int id;
        volatile boolean closed;
        volatile boolean broken;
        volatile boolean autoCommit = true;
        volatile int commits;
        volatile int rollbacks;

        ConnState(int id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "FakeConn#" + id;
        }
    }

    private static Connection proxy(final ConnState st) {
        InvocationHandler handler = (proxyObj, method, args) -> {
            switch (method.getName()) {
                case "close":
                    st.closed = true;
                    return null;
                case "isClosed":
                    return st.closed;
                case "isValid":
                    return !st.closed && !st.broken;
                case "getAutoCommit":
                    return st.autoCommit;
                case "setAutoCommit":
                    st.autoCommit = (Boolean) args[0];
                    return null;
                case "commit":
                    st.commits++;
                    return null;
                case "rollback":
                    st.rollbacks++;
                    return null;
                case "toString":
                    return st.toString();
                case "hashCode":
                    return st.id;
                case "equals":
                    return proxyObj == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(FakeConnections.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private static Object defaultValue(Class<?> type) throws SQLException {
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

    /** 建连即失败的工厂（验证失败诊断信息）。 */
    static ConnectionFactory failing(String message) {
        return () -> {
            throw new SQLException(message);
        };
    }
}
