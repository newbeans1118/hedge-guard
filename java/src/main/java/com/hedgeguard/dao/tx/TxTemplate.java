package com.hedgeguard.dao.tx;

import com.hedgeguard.dao.pool.DataSourcePool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 事务模板（{@code ARCHITECTURE.md} §3.9 「{@code TxTemplate.java}  事务模板（Connection 生命周期、隔离级别）」）。
 *
 * <p>职责：连接借还、事务开启/提交/回滚、隔离级别、<b>线程内事务传递</b>。
 *
 * <p>事务边界纪律（{@code §3.8}「事务边界在此（L3）」）：
 * <pre>
 *   // L3 只写这一层；DAO 方法内部自动加入当前事务，无需传连接
 *   txTemplate.inTransaction(() -&gt; {
 *       planDao.insert(plan, operatorId);
 *       planStateLogDao.insert(log, operatorId);
 *       return null;
 *   });
 * </pre>
 *
 * <p>嵌套语义：已在事务中再次调用 {@link #inTransaction} 时<b>加入</b>外层事务（不新开、不提前提交）；
 * 任一层抛异常则整个外层事务回滚。这与"跨模块编排在同一事务内完成"的需求一致。
 */
public final class TxTemplate {

    private static final Logger LOG = Logger.getLogger(TxTemplate.class.getName());

    private final DataSourcePool pool;
    private final ThreadLocal<Holder> current = new ThreadLocal<>();

    /**
     * 构造。
     *
     * @param pool 连接池
     */
    public TxTemplate(DataSourcePool pool) {
        this.pool = pool;
    }

    /**
     * 在事务中执行（提交或回滚，自动借还连接）。
     *
     * @param callback 业务动作
     * @param <T>      返回类型
     * @return 回调返回值
     */
    public <T> T inTransaction(TxCallback<T> callback) {
        Holder holder = current.get();
        if (holder != null) {
            holder.depth++;
            try {
                return callback.apply(holder.connection);
            } catch (SQLException e) {
                throw wrap(e, "事务内执行失败（已标记整笔回滚）");
            } finally {
                holder.depth--;
            }
        }

        Connection connection = pool.borrow();
        Holder created = new Holder(connection);
        current.set(created);
        try {
            connection.setAutoCommit(false);
            T result = callback.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(connection, e);
            throw wrap(e, "事务提交失败或语句执行失败（已回滚）");
        } catch (RuntimeException | Error e) {
            rollbackQuietly(connection, e);
            throw e;
        } finally {
            current.remove();
            restoreAutoCommit(connection);
            pool.release(connection);
        }
    }

    /**
     * 只读 / 单语句执行（自动提交；若已在事务中则加入当前事务）。
     *
     * @param callback 业务动作
     * @param <T>      返回类型
     * @return 回调返回值
     */
    public <T> T execute(TxCallback<T> callback) {
        Holder holder = current.get();
        if (holder != null) {
            try {
                return callback.apply(holder.connection);
            } catch (SQLException e) {
                throw wrap(e, "事务内查询失败");
            }
        }
        Connection connection = pool.borrow();
        try {
            return callback.apply(connection);
        } catch (SQLException e) {
            throw wrap(e, "数据库访问失败（自动提交模式，单语句）");
        } finally {
            pool.release(connection);
        }
    }

    /**
     * 当前线程是否处于事务中。
     *
     * @return 在事务中返回 {@code true}
     */
    public boolean inTransaction() {
        return current.get() != null;
    }

    /**
     * 当前线程绑定的连接（<b>仅供 DAO 实现调用</b>）。
     *
     * @return 连接；未开启事务时返回 {@code null}
     */
    public Connection boundConnection() {
        Holder holder = current.get();
        return holder == null ? null : holder.connection;
    }

    private static void rollbackQuietly(Connection connection, Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "回滚失败：原始异常=" + cause.getClass().getName()
                    + "；回滚异常=" + e.getMessage(), e);
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "恢复自动提交失败（归还时池会丢弃该连接）：" + e.getMessage());
        }
    }

    private static IllegalStateException wrap(SQLException e, String what) {
        return new IllegalStateException(what + "：SQLState=" + e.getSQLState()
                + "；错误码=" + e.getErrorCode() + "；原因=" + e.getMessage()
                + "；复现=核对语句与该表约束（DATA_MODEL.md §2）", e);
    }

    /** 线程内事务持有者。 */
    private static final class Holder {

        private final Connection connection;
        private int depth = 1;

        Holder(Connection connection) {
            this.connection = connection;
        }
    }
}
