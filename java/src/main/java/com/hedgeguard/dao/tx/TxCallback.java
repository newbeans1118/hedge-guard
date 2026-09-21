package com.hedgeguard.dao.tx;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 事务回调（{@code ARCHITECTURE.md} §3.9 {@code dao/tx/TxTemplate.java}）。
 *
 * <p>回调形参是 {@link Connection} —— 它<b>只在 DAO 包内可见</b>：
 * L3 通过 {@link TxTemplate#inTransaction(TxCallback)} 开启事务边界，但不会拿到该形参
 * （L3 只调用 DAO 接口方法，{@code §2.3}「L3 不得依赖 JDBC 类型」）。
 *
 * @param <T> 返回类型
 */
@FunctionalInterface
public interface TxCallback<T> {

    /**
     * 在连接上执行。
     *
     * @param connection 当前事务连接（DAO 包内使用）
     * @return 结果
     * @throws SQLException 数据库异常（触发回滚）
     */
    T apply(Connection connection) throws SQLException;
}
