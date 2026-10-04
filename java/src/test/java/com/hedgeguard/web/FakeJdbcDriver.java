package com.hedgeguard.web;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * **测试用**假 JDBC 驱动（仅 {@code src/test}；零依赖，不违背禁引名单）。
 *
 * <p><b>为何存在</b>：{@code Bootstrap} 的 DB 装配分支里，{@code DataSourcePool} 构造会**预建连接**
 * （{@code factory.create()}）。在无 DM8 驱动的环境里，这一步必然抛异常，导致
 * {@code registerBusinessRoutes(...)} **永远不被执行**——于是"配了库时业务路由被注册"这条关键路径
 * **无法用真实驱动断言**（原用例只能靠异常反证"分支被走到"，见 {@code D-19} 复核结论）。
 *
 * <p>本驱动以 {@link Proxy} 返回一个"永远健康"的 {@link Connection}，使预建连接成功，
 * 从而把装配**跑到底**，让「业务路由数 = 82（+ ping 2 = 84）」成为**可被直接断言**的事实——
 * 一旦 {@code registerBusinessRoutes(...)} 被删除或短路，该断言**必然变红**（防关键路径静默退化）。
 *
 * <p>注册方式：类静态块内 {@link DriverManager#registerDriver}。由于 {@code JdbcConnectionFactory.create()}
 * 会先 {@code Class.forName(driverClass)}，把测试配置 {@code hg.db.driver} 指向本类即可自动注册。
 */
public final class FakeJdbcDriver implements Driver {

    /** 接受的 URL 前缀（与真实 DM 相同，故测试配置无需特判）。 */
    public static final String PREFIX = "jdbc:dm://";

    static {
        try {
            DriverManager.registerDriver(new FakeJdbcDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        return (Connection) Proxy.newProxyInstance(
                FakeJdbcDriver.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new FakeConnectionHandler());
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(FakeJdbcDriver.class.getName());
    }

    /** 只实现连接池生命周期用到的方法，其余按返回类型给默认值。 */
    private static final class FakeConnectionHandler implements InvocationHandler {

        private boolean autoCommit = true;
        private boolean closed;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "isClosed":
                    return closed;
                case "isValid":
                    return !closed;
                case "getAutoCommit":
                    return autoCommit;
                case "setAutoCommit":
                    autoCommit = (Boolean) args[0];
                    return null;
                case "close":
                    closed = true;
                    return null;
                case "commit":
                case "rollback":
                    return null;
                case "toString":
                    return "FakeConnection(autoCommit=" + autoCommit + ", closed=" + closed + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return Boolean.FALSE;
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
            if (type == char.class) {
                return (char) 0;
            }
            if (type == float.class) {
                return 0f;
            }
            if (type == double.class) {
                return 0d;
            }
            return null;
        }
    }
}
