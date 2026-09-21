package com.hedgeguard.dao.pool;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 自实现极简连接池（{@code ARCHITECTURE.md} §3.9 {@code dao/pool/DataSourcePool.java}、§7.2.2）。
 *
 * <p><b>为何自实现</b>：JDK 无内置连接池；本系统并发为"内部岗位级"，非高频交易路径。
 * 按 {@code §7.2.2} 的决定先自实现（预建 N 连接 + 借还队列 + 健康检查），
 * <b>不引入 HikariCP</b>；若压测证明不足（{@code NFR-PERF-3}/{@code NFR-PERF-4} 不达标）
 * 再走 {@code CH-xxx} 引入。
 *
 * <p>实现要点：
 * <ul>
 *   <li>空闲连接在 {@code Deque} 中；借出/归还由 {@link ReentrantLock} + {@link Condition} 串行化，
 *       保证 {@code 物理连接数 <= maxSize}；</li>
 *   <li>借出时健康检查（{@code Connection.isValid}），失效连接被丢弃并按需补建；</li>
 *   <li>借出超时抛 {@link PoolExhaustedException}（<b>拒绝，不阻塞</b>）；</li>
 *   <li>归还时若处于事务中（{@code autoCommit=false}）先 {@code rollback} 再复位——防御性，
 *       正常路径由 {@code TxTemplate} 收尾。</li>
 * </ul>
 *
 * <p><b>刻意的取舍</b>：建连在持锁状态下进行（建连较慢），对本系统的并发量级可接受；
 * 换取的是"连接数上限"这一不变量无需任何原子技巧即可成立。若该取舍被压测否定，按 §7.2.2 提变更。
 */
public final class DataSourcePool implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DataSourcePool.class.getName());

    private final ConnectionFactory factory;
    private final PoolConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Deque<Connection> idle = new ArrayDeque<>();

    private int created;
    private boolean closed;
    private long borrowCount;
    private long timeoutCount;

    /**
     * 构造并预建 {@link PoolConfig#initialSize()} 条连接。
     *
     * @param factory 物理连接工厂
     * @param config  池参数
     */
    public DataSourcePool(ConnectionFactory factory, PoolConfig config) {
        this.factory = factory;
        this.config = config;
        lock.lock();
        try {
            for (int i = 0; i < config.initialSize(); i++) {
                idle.addLast(createPhysical());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 借出一条连接。
     *
     * @return 连接（用完必须 {@link #release(Connection)}）
     */
    public Connection borrow() {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(config.borrowTimeoutMillis());
        lock.lock();
        try {
            while (true) {
                if (closed) {
                    throw new IllegalStateException("连接池已关闭，拒绝借出；"
                            + "复现=确认调用发生在 shutdown 之后（ARCHITECTURE.md §3.14 启动顺序）");
                }
                Connection reused = idle.pollFirst();
                if (reused != null) {
                    if (isUsable(reused)) {
                        borrowCount++;
                        return reused;
                    }
                    discardPhysical(reused);
                }
                if (created < config.maxSize()) {
                    created++;
                    try {
                        Connection fresh = factory.create();
                        borrowCount++;
                        return fresh;
                    } catch (SQLException e) {
                        created--;
                        throw new IllegalStateException(
                                "建立数据库连接失败：已用 " + created + "/" + config.maxSize()
                                        + "；原因=" + e.getMessage()
                                        + "；复现=核对 HG.DB.* 配置与 DM8 实例连通性（NFR-DEPLOY-5）", e);
                    }
                }
                long remainNanos = deadline - System.nanoTime();
                if (remainNanos <= 0L) {
                    timeoutCount++;
                    throw new PoolExhaustedException(
                            "连接池借出超时：maxSize=" + config.maxSize()
                                    + " 全部被占用，等待 " + config.borrowTimeoutMillis() + " ms 未获连接"
                                    + "；复现=排查长事务未归还（DAO 不得自行 close，须经 TxTemplate 借还）");
                }
                try {
                    available.await(remainNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "借出连接被中断：在等待连接池可用时线程被 interrupt；复现=确认停机流程", e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 归还连接。
     *
     * @param connection 借出的连接（{@code null} 被忽略）
     */
    public void release(Connection connection) {
        if (connection == null) {
            return;
        }
        lock.lock();
        try {
            if (closed || !resetAndValidate(connection)) {
                discardPhysical(connection);
            } else {
                idle.addLast(connection);
            }
            available.signal();
        } finally {
            lock.unlock();
        }
    }

    /** 池状态快照（供运维与压测取证）。 */
    public PoolStats stats() {
        lock.lock();
        try {
            return new PoolStats(idle.size(), created, config.maxSize(), borrowCount, timeoutCount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            while (!idle.isEmpty()) {
                closeQuietly(idle.pollFirst());
                created--;
            }
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** 在持锁状态下建连并计数。 */
    private Connection createPhysical() {
        created++;
        try {
            return factory.create();
        } catch (SQLException e) {
            created--;
            throw new IllegalStateException(
                    "连接池预建连接失败：initialSize=" + config.initialSize()
                            + "；原因=" + e.getMessage()
                            + "；复现=核对 HG.DB.* 配置与 DM8 实例连通性", e);
        }
    }

    /** 健康检查。 */
    private boolean isUsable(Connection c) {
        try {
            return !c.isClosed() && c.isValid(config.validationTimeoutSeconds());
        } catch (SQLException e) {
            LOG.log(Level.FINE, "连接健康检查失败，将丢弃该连接：" + e.getMessage());
            return false;
        }
    }

    /** 归还前的复位：若仍在事务中先回滚，再恢复自动提交。 */
    private boolean resetAndValidate(Connection c) {
        try {
            if (!c.getAutoCommit()) {
                c.rollback();
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "归还连接时复位失败，将丢弃该连接：" + e.getMessage());
            return false;
        }
        return isUsable(c);
    }

    private void discardPhysical(Connection c) {
        closeQuietly(c);
        created--;
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            LOG.log(Level.FINE, "关闭失效连接时异常（已忽略）：" + e.getMessage());
        }
    }

    /** 池状态快照。 */
    public static final class PoolStats {

        private final int idleCount;
        private final int created;
        private final int maxSize;
        private final long borrowCount;
        private final long timeoutCount;

        PoolStats(int idleCount, int created, int maxSize, long borrowCount, long timeoutCount) {
            this.idleCount = idleCount;
            this.created = created;
            this.maxSize = maxSize;
            this.borrowCount = borrowCount;
            this.timeoutCount = timeoutCount;
        }

        /** 当前空闲连接数。 */
        public int idleCount() {
            return idleCount;
        }

        /** 当前物理连接总数。 */
        public int created() {
            return created;
        }

        /** 上限。 */
        public int maxSize() {
            return maxSize;
        }

        /** 累计借出次数。 */
        public long borrowCount() {
            return borrowCount;
        }

        /** 累计借出超时次数。 */
        public long timeoutCount() {
            return timeoutCount;
        }

        @Override
        public String toString() {
            return "PoolStats{idle=" + idleCount + ", created=" + created + "/" + maxSize
                    + ", borrows=" + borrowCount + ", timeouts=" + timeoutCount + '}';
        }
    }
}
