package com.hedgeguard.dao;

import com.hedgeguard.dao.dialect.DmDialect;
import com.hedgeguard.dao.dialect.Dialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DmDialect} 单元测试（{@code DATA_MODEL.md} §6.1 {@code D3}/{@code D4} 等 DM 特性）。
 *
 * <p>验证"DM 特有语法只出现在方言类"这一纪律的可换库回报（{@code §6.2}）。
 */
class DmDialectTest {

    private final Dialect dialect = new DmDialect();

    @Test
    @DisplayName("分页：追加 LIMIT ? OFFSET ? 且参数顺序固定")
    void paginates() {
        PageQuery q = PageQuery.of("3", "50", null, "id");
        assertEquals("SELECT * FROM t_user WHERE status = ? ORDER BY id ASC LIMIT ? OFFSET ?",
                dialect.paginate("SELECT * FROM t_user WHERE status = ? ORDER BY id ASC", q));
        assertEquals(50, q.size());
        assertEquals(100, q.offset());
        assertEquals("ASC", q.direction());
    }

    @Test
    @DisplayName("计数：包裹为子查询，别名不与业务别名冲突")
    void buildsCountSql() {
        assertEquals("SELECT COUNT(*) AS cnt FROM (SELECT id FROM t_user) hg_cnt",
                dialect.countSql("SELECT id FROM t_user"));
    }

    @Test
    @DisplayName("日期函数与时间函数集中于方言")
    void exposesDateFunctions() {
        assertEquals("CURRENT_TIMESTAMP", dialect.currentTimestamp());
        assertEquals("SYSDATE", dialect.sysDate());
        assertEquals("TO_DATE(?, 'yyyy-MM-dd')", dialect.toDate("?", "yyyy-MM-dd"));
        assertEquals("TRUNC(trade_date)", dialect.truncDay("trade_date"));
        assertEquals("ADD_MONTHS(open_date, 12)", dialect.addMonths("open_date", 12));
        assertEquals("MONTHS_BETWEEN(?, open_date)", dialect.monthsBetween("open_date", "?"));
    }

    @Test
    @DisplayName("字符串拼接用标准 || 运算（D5 标准项）")
    void concatenates() {
        assertEquals("a || b", dialect.concat(Arrays.asList("a", "b")));
        assertEquals("a || b || c", dialect.concat(Arrays.asList("a", "b", "c")));
        assertThrows(IllegalArgumentException.class, () -> dialect.concat(Collections.emptyList()));
    }

    @Test
    @DisplayName("显式 IDENTITY 插入语句（保留系统行 t_sub_account(id=0) 需要）")
    void identityInsertStatements() {
        assertEquals("SET IDENTITY_INSERT t_sub_account ON", dialect.identityInsertOn("t_sub_account"));
        assertEquals("SET IDENTITY_INSERT t_sub_account OFF", dialect.identityInsertOff("t_sub_account"));
    }

    @Test
    @DisplayName("方言名可读（日志与自检输出）")
    void hasName() {
        assertEquals("DM8", dialect.name());
        assertTrue(dialect instanceof DmDialect);
    }
}
