package com.hedgeguard.service;

import com.hedgeguard.dao.pool.ConnectionFactory;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 服务层测试用的最小假连接（{@code NFR-TEST-2}：不引 Mockito；DM 依赖以内存假对象替代）。
 *
 * <p>本类只支撑 {@link TxTemplate} 与 {@link DataSourcePool} 的连接借还 / 提交 / 回滚语义——
 * 服务层单测以<b>内存假 DAO</b>替代真实 SQL，故 {@code prepareStatement} 一律抛异常：
 * 一旦被测代码绕过假 DAO 直连 JDBC，用例立即失败（这正是我们要防的"业务逻辑写进网络/DB 流程"）。
 */
public final class FakeTx {

    private FakeTx() {
    }

    /** 新建可用的 {@link TxTemplate}（不预建连接，借时惰性创建）。 */
    public static TxTemplate newTemplate() {
        return new TxTemplate(new DataSourcePool(connectionFactory(), new PoolConfig(0, 4, 500, 2)));
    }

    private static ConnectionFactory connectionFactory() {
        return () -> {
            InvocationHandler handler = (conn, method, args) -> {
                switch (method.getName()) {
                    case "prepareStatement":
                    case "prepareCall":
                    case "createStatement":
                        throw new SQLException("服务层单测不应直接访问 JDBC：" + method.getName()
                                + "；复现=检查被测代码是否绕过 DAO（E5：SQL 只允许出现在 dao 层）");
                    case "close":
                    case "setAutoCommit":
                    case "commit":
                    case "rollback":
                    case "setReadOnly":
                    case "setTransactionIsolation":
                        return null;
                    case "isClosed":
                        return false;
                    case "isValid":
                        return true;
                    case "getAutoCommit":
                        return true;
                    case "isReadOnly":
                        return false;
                    case "getTransactionIsolation":
                        return Connection.TRANSACTION_READ_COMMITTED;
                    case "toString":
                        return "FakeConnection(服务层单测)";
                    case "hashCode":
                        return System.identityHashCode(conn);
                    case "equals":
                        return conn == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            };
            return (Connection) Proxy.newProxyInstance(FakeTx.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, handler);
        };
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
}
