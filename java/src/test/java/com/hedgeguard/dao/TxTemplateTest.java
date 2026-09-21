package com.hedgeguard.dao;

import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TxTemplate} 单元测试（{@code ARCHITECTURE.md} §3.8「事务边界在 L3」/ §3.9 事务模板）。
 */
class TxTemplateTest {

    private static PoolConfig pool() {
        return new PoolConfig(1, 4, 500, 2);
    }

    @Test
    @DisplayName("inTransaction：提交成功、归还连接、复位自动提交")
    void commitsAndReleases() throws Exception {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool p = new DataSourcePool(factory, pool())) {
            TxTemplate tx = new TxTemplate(p);
            String result = tx.inTransaction(conn -> {
                assertFalse(conn.getAutoCommit(), "事务内 autoCommit 必须为 false");
                return "done";
            });
            assertEquals("done", result);
            assertEquals(1, factory.created().get(0).commits);
            assertEquals(0, factory.created().get(0).rollbacks);
            assertEquals(true, factory.created().get(0).autoCommit, "归还前须复位 autoCommit");
            assertFalse(tx.inTransaction());
            assertEquals(1, p.stats().idleCount(), "连接须已归还");
        }
    }

    @Test
    @DisplayName("inTransaction：SQLException 回滚并抛出可诊断异常")
    void rollsBackOnSqlException() {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool p = new DataSourcePool(factory, pool())) {
            TxTemplate tx = new TxTemplate(p);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> tx.inTransaction(conn -> {
                        throw new SQLException("约束违背", "23505", 1);
                    }));
            assertTrue(e.getMessage().contains("23505"));
            assertTrue(e.getMessage().contains("回滚"));
            assertEquals(1, factory.created().get(0).rollbacks);
            assertEquals(0, factory.created().get(0).commits);
            assertEquals(1, p.stats().idleCount());
        }
    }

    @Test
    @DisplayName("inTransaction：运行期异常同样回滚并原样抛出")
    void rollsBackOnRuntimeException() {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool p = new DataSourcePool(factory, pool())) {
            TxTemplate tx = new TxTemplate(p);
            assertThrows(IllegalArgumentException.class,
                    () -> tx.inTransaction(conn -> {
                        throw new IllegalArgumentException("业务校验失败");
                    }));
            assertEquals(1, factory.created().get(0).rollbacks);
            assertEquals(1, p.stats().idleCount());
        }
    }

    @Test
    @DisplayName("嵌套 inTransaction 加入外层事务：只提交一次，内层异常使整笔回滚")
    void nestedJoinsOuterTransaction() throws Exception {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool p = new DataSourcePool(factory, pool())) {
            TxTemplate tx = new TxTemplate(p);
            Integer v = tx.inTransaction(outer -> {
                assertTrue(tx.inTransaction());
                return tx.inTransaction(inner -> {
                    assertTrue(inner == outer, "嵌套调用必须复用同一连接");
                    return 42;
                });
            });
            assertEquals(42, v);
            assertEquals(1, factory.created().get(0).commits, "嵌套只提交一次");
        }
    }

    @Test
    @DisplayName("嵌套内层抛异常时外层整体回滚")
    void nestedFailureRollsBackWholeTransaction() {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool p = new DataSourcePool(factory, pool())) {
            TxTemplate tx = new TxTemplate(p);
            AtomicBoolean outerCommitted = new AtomicBoolean(false);
            assertThrows(IllegalStateException.class, () -> tx.inTransaction(outer -> {
                tx.inTransaction(inner -> {
                    throw new IllegalStateException("内层失败");
                });
                outerCommitted.set(true);
                return null;
            }));
            assertFalse(outerCommitted.get());
            assertEquals(1, factory.created().get(0).rollbacks);
            assertEquals(0, factory.created().get(0).commits);
        }
    }

    @Test
    @DisplayName("execute：自动提交、不进事务；事务中调用则加入当前事务")
    void executeJoinsOrUsesAutoCommit() throws Exception {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool p = new DataSourcePool(factory, pool())) {
            TxTemplate tx = new TxTemplate(p);
            String plain = tx.execute(conn -> {
                assertTrue(conn.getAutoCommit(), "非事务执行须保持 autoCommit=true");
                return "read";
            });
            assertEquals("read", plain);
            assertEquals(0, factory.created().get(0).commits);
            assertEquals(1, p.stats().idleCount());

            tx.inTransaction(conn -> {
                assertEquals("joined", tx.execute(inner -> "joined"));
                return null;
            });
        }
    }

    @Test
    @DisplayName("boundConnection：仅事务中非空；事务结束即清除（不跨线程泄漏）")
    void boundConnectionScopedToTransaction() throws Exception {
        FakeConnections.Fake factory = new FakeConnections.Fake();
        try (DataSourcePool p = new DataSourcePool(factory, pool())) {
            TxTemplate tx = new TxTemplate(p);
            assertNull(tx.boundConnection());
            tx.inTransaction(conn -> {
                assertNotNull(tx.boundConnection());
                return null;
            });
            assertNull(tx.boundConnection(), "事务结束后线程绑定必须清除");
        }
    }

    @Test
    @DisplayName("连接池借出失败时 execute 直接抛出诊断异常")
    void surfacesPoolFailure() {
        DataSourcePool p = new DataSourcePool(FakeConnections.failing("invalid url"),
                new PoolConfig(0, 1, 200, 2));
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new TxTemplate(p).execute(conn -> "x"));
            assertTrue(ex.getMessage().contains("建立数据库连接失败"));
            assertTrue(ex.getMessage().contains("invalid url"));
        } finally {
            p.close();
        }
    }
}
