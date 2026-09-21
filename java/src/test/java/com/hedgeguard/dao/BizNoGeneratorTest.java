package com.hedgeguard.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BizNoGenerator} 单元测试（{@code DATA_MODEL.md} §3.5 编号生成规则，逐条对照）。
 */
class BizNoGeneratorTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 20);

    @Test
    @DisplayName("§3.5 九类编号格式逐条一致")
    void formatsMatchSpec() {
        assertEquals("HP202609200001", BizNoGenerator.hedgePlan(D, 1));
        assertEquals("HDHP202609200001-V2", BizNoGenerator.hedgeDoc("HP202609200001", 2));
        assertEquals("TP2026092000001", BizNoGenerator.tradePlan(D, 1));
        assertEquals("HG20260920000042", BizNoGenerator.orderRef(D, 42));
        assertEquals("ST20260920-SA-A", BizNoGenerator.settleCycle(D, "SA-A"));
        assertEquals("FV2026092000012", BizNoGenerator.fundVoucher(D, 12));
        assertEquals("AV20260900031", BizNoGenerator.acctVoucher(YearMonth.of(2026, 9), 31));
        assertEquals("TF202609200003", BizNoGenerator.transferApply(D, 3));
        assertEquals("VS202609200001", BizNoGenerator.batch("VS", D, 1));
        assertEquals("EXP20260920-01-0007-B-01", BizNoGenerator.exposure(D, 1, 7, "b", 1));
    }

    @Test
    @DisplayName("序号溢出位数即明确报错（不静默截断）")
    void rejectsSeqOverflow() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BizNoGenerator.hedgePlan(D, 10000));
        assertTrue(e.getMessage().contains("超出 4 位"));
        assertThrows(IllegalArgumentException.class, () -> BizNoGenerator.tradePlan(D, 100000));
        assertThrows(IllegalArgumentException.class, () -> BizNoGenerator.orderRef(D, 1000000));
        assertThrows(IllegalArgumentException.class, () -> BizNoGenerator.exposure(D, 100, 7, "B", 1));
    }

    @Test
    @DisplayName("敞口编号要求子账户 ID > 0（行级归属列不得用哨兵值）")
    void exposureRequiresRealSubAccount() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BizNoGenerator.exposure(D, 0, 7, "B", 1));
        assertTrue(e.getMessage().contains("哨兵值"));
    }

    @Test
    @DisplayName("空入参（计划编号 / 子账户编码 / 方向 / 前缀）被拒绝")
    void rejectsBlankInputs() {
        assertThrows(IllegalArgumentException.class, () -> BizNoGenerator.hedgeDoc("", 1));
        assertThrows(IllegalArgumentException.class, () -> BizNoGenerator.settleCycle(D, ""));
        assertThrows(IllegalArgumentException.class, () -> BizNoGenerator.exposure(D, 1, 7, "", 1));
        assertThrows(IllegalArgumentException.class, () -> BizNoGenerator.batch("", D, 1));
        assertThrows(IllegalArgumentException.class, () -> BizNoGenerator.hedgeDoc("HP1", 0));
    }

    @Test
    @DisplayName("唯一冲突重试：第 3 次成功则整体成功；仅 23xxx 触发重试")
    void retriesOnlyOnUniqueViolation() {
        AtomicInteger attempts = new AtomicInteger();
        boolean ok = BizNoGenerator.retryOnUniqueConflict(3, () -> attempts.incrementAndGet() == 3);
        assertTrue(ok);
        assertEquals(3, attempts.get());

        AtomicInteger calls = new AtomicInteger();
        boolean first = BizNoGenerator.retryOnUniqueConflict(3, () -> {
            calls.incrementAndGet();
            return true;
        });
        assertTrue(first);
        assertEquals(1, calls.get());

        AtomicInteger nonUnique = new AtomicInteger();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BizNoGenerator.retryOnUniqueConflict(3, () -> {
                    nonUnique.incrementAndGet();
                    throw new SQLException("连接中断", "08006", 1);
                }));
        assertTrue(e.getMessage().contains("非唯一冲突"));
        assertEquals(1, nonUnique.get(), "非唯一冲突不得重试");

        AtomicInteger alwaysConflict = new AtomicInteger();
        IllegalStateException exhausted = assertThrows(IllegalStateException.class,
                () -> BizNoGenerator.retryOnUniqueConflict(3, () -> {
                    alwaysConflict.incrementAndGet();
                    throw new SQLException("唯一约束冲突", "23505", 1);
                }));
        assertTrue(exhausted.getMessage().contains("3 次尝试内仍未成功"));
        assertEquals(3, alwaysConflict.get());
    }

    @Test
    @DisplayName("SQLState 判定：23 前缀为唯一/完整性冲突，其余不重试")
    void classifiesSqlState() {
        assertTrue(BizNoGenerator.isUniqueViolation(new SQLException("x", "23505")));
        assertTrue(BizNoGenerator.isUniqueViolation(new SQLException("x", "23000")));
        assertFalse(BizNoGenerator.isUniqueViolation(new SQLException("x", "08006")));
        assertFalse(BizNoGenerator.isUniqueViolation(new SQLException("x", (String) null)));
        assertThrows(IllegalArgumentException.class,
                () -> BizNoGenerator.retryOnUniqueConflict(0, () -> true));
    }

    @Test
    @DisplayName("today() 取自可注入时钟（NFR-TEST-4）")
    void todayUsesInjectableClock() {
        java.time.Clock fixed = java.time.Clock.fixed(
                java.time.Instant.parse("2026-09-20T01:00:00Z"), com.hedgeguard.common.Clocks.ZONE);
        com.hedgeguard.common.Clocks.set(fixed);
        try {
            assertEquals(LocalDate.of(2026, 9, 20), BizNoGenerator.today());
        } finally {
            com.hedgeguard.common.Clocks.reset();
        }
    }
}
