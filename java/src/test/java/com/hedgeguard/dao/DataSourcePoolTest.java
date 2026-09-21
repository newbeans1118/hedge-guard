package com.hedgeguard.dao;

import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.pool.PoolExhaustedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DataSourcePool} 单元测试（{@code ARCHITECTURE.md} §7.2.2 自实现连接池）。
 *
 * <p>无需 DM8：用 {@link FakeConnections} 假工厂验证借还、上限、超时、失效剔除与关闭语义。
 */
class DataSourcePoolTest {

    private static PoolConfig config(int initial, int max, int timeoutMs) {
        return new PoolConfig(initial, max, timeoutMs, 2);
    }

    @Test
    @DisplayName("预建 initialSize 条连接；借出复用同一物理连接，归还后可再次借出")
    void preCreatesAndReusesConnections() {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool pool = new DataSourcePool(factory, config(2, 8, 200))) {
            assertEquals(2, factory.createCount());
            assertEquals(2, pool.stats().idleCount());

            Connection first = pool.borrow();
            assertNotNull(first);
            assertEquals(1, pool.stats().idleCount());
            pool.release(first);
            assertEquals(2, pool.stats().idleCount());
            assertEquals(2, factory.createCount(), "归还后再次借出不应新建物理连接");
            assertEquals(1L, pool.stats().borrowCount());
        }
    }

    @Test
    @DisplayName("物理连接数不超过 maxSize：并发借用达到上限时新请求超时拒绝")
    void respectsMaxSizeAndTimesOut() {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool pool = new DataSourcePool(factory, config(0, 2, 150))) {
            Connection c1 = pool.borrow();
            Connection c2 = pool.borrow();
            assertEquals(2, factory.createCount());

            PoolExhaustedException e = assertThrows(PoolExhaustedException.class, pool::borrow);
            assertTrue(e.getMessage().contains("maxSize=2"));
            assertTrue(e.getMessage().contains("复现"));
            assertEquals(1L, pool.stats().timeoutCount());

            pool.release(c1);
            Connection c3 = pool.borrow();
            assertNotNull(c3);
            pool.release(c2);
            pool.release(c3);
            assertEquals(2, factory.createCount(), "归还并复用后不应超过 maxSize 条物理连接");
            assertEquals(2, pool.stats().idleCount());
        }
    }

    @Test
    @DisplayName("借出的连接失效（isValid=false）时被丢弃并补建")
    void discardsBrokenConnections() {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool pool = new DataSourcePool(factory, config(1, 4, 200))) {
            Connection c = pool.borrow();
            pool.release(c);
            factory.breakConnection(0);

            Connection fresh = pool.borrow();
            assertNotNull(fresh);
            assertEquals(2, factory.createCount(), "失效连接应被丢弃并补建新连接");
            assertEquals(1L, factory.openCount(), "失效连接应已关闭，池内只保留可用连接");
            pool.release(fresh);
        }
    }

    @Test
    @DisplayName("归还时若仍在事务中则回滚并复位自动提交（防御性）")
    void rollsBackOnReleaseWhenAutoCommitFalse() throws Exception {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool pool = new DataSourcePool(factory, config(1, 4, 200))) {
            Connection c = pool.borrow();
            c.setAutoCommit(false);
            pool.release(c);
            assertEquals(1, factory.created().get(0).rollbacks);
            assertEquals(true, factory.created().get(0).autoCommit);
        }
    }

    @Test
    @DisplayName("关闭后借出被拒绝；空闲连接被关闭")
    void closeReleasesIdleConnections() {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        DataSourcePool pool = new DataSourcePool(factory, config(2, 4, 100));
        pool.close();
        assertEquals(0L, factory.openCount());
        IllegalStateException e = assertThrows(IllegalStateException.class, pool::borrow);
        assertTrue(e.getMessage().contains("连接池已关闭"));
    }

    @Test
    @DisplayName("预建失败时给出可诊断的错误（含 initialSize 与连通性复现指引）")
    void reportsCreationFailure() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DataSourcePool(FakeConnections.failing("no suitable driver"), config(1, 4, 100)));
        assertTrue(e.getMessage().contains("no suitable driver"));
        assertTrue(e.getMessage().contains("HG.DB"));
    }

    @Test
    @DisplayName("池参数非法时构造即失败")
    void validatesPoolConfig() {
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(0, 0, 100, 2));
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(5, 2, 100, 2));
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(0, 2, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new PoolConfig(0, 2, 100, 0));
        assertEquals(8, PoolConfig.defaults().maxSize());
    }

    @Test
    @DisplayName("归还释放连接后，等待中的借用线程被唤醒（有界等待而非拒绝）")
    void awaitingThreadIsWokenOnRelease() throws Exception {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool pool = new DataSourcePool(factory, config(0, 1, 2000))) {
            Connection held = pool.borrow();
            AtomicReference<Connection> got = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                got.set(pool.borrow());
                done.countDown();
            }, "pool-waiter");
            t.setDaemon(true);
            t.start();

            Thread.sleep(100L);
            pool.release(held);
            assertTrue(done.await(2, TimeUnit.SECONDS), "归还后等待线程应被唤醒");
            assertNotNull(got.get());
            pool.release(got.get());
        }
    }
}
