package com.hedgeguard.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SubAccountScope} 单元测试（{@code NFR-SEC-7} 子账户软隔离的 DAO 层落地）。
 *
 * <p>关键用例是<b>安全默认</b>：无任何授权时必须产生"返回空集"的恒假条件，
 * 而不是"不过滤"（后者等于全可见，是 {@code NFR-SEC-7} 明确禁止的）。
 */
class SubAccountScopeTest {

    @Test
    @DisplayName("无授权时生成恒假条件（返回空集），不退化为不过滤")
    void emptyScopeIsNeverTrue() {
        SubAccountScope scope = SubAccountScope.of("sub_account_id", Collections.emptyList());
        assertTrue(scope.isEmpty());
        assertEquals("sub_account_id IN (-1)", scope.sql());
        assertTrue(scope.params().isEmpty());
        String where = scope.appendTo(null);
        assertEquals("sub_account_id IN (-1)", where);
        assertFalse(where.contains("1=1"), "不得出现恒真条件");
    }

    @Test
    @DisplayName("有授权时按数量生成占位符，参数与占位符一一对应")
    void buildsInClauseWithPlaceholders() {
        SubAccountScope scope = SubAccountScope.of("sub_account_id", Arrays.asList(1, 2, 3));
        assertEquals("sub_account_id IN (?,?,?)", scope.sql());
        assertEquals(Arrays.asList(1, 2, 3), scope.params());
        assertFalse(scope.isEmpty());
    }

    @Test
    @DisplayName("账户级口径可包含哨兵值 0（作用域列例外，DC-020）")
    void supportsSentinelZero() {
        SubAccountScope scope = SubAccountScope.of("sub_account_id",
                Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL, 5));
        assertEquals("sub_account_id IN (?,?)", scope.sql());
        assertEquals(Integer.valueOf(0), scope.params().get(0));
        assertEquals(0, SubAccountScope.ALL_ACCOUNT_SENTINEL);
    }

    @Test
    @DisplayName("追加到既有条件时保留原语义（AND 连接，原条件加括号）")
    void appendsToExistingWhere() {
        SubAccountScope scope = SubAccountScope.of("sub_account_id", Arrays.asList(7));
        assertEquals("sub_account_id IN (?)", scope.appendTo(null));
        assertEquals("sub_account_id IN (?)", scope.appendTo(""));
        assertEquals("(status = ?) AND sub_account_id IN (?)", scope.appendTo("status = ?"));
    }

    @Test
    @DisplayName("列名须为合法标识符（防标识符注入）")
    void validatesColumnName() {
        assertThrows(IllegalArgumentException.class, () -> SubAccountScope.of("sub_account_id; drop", null));
        assertThrows(IllegalArgumentException.class, () -> SubAccountScope.of("1bad", null));
        assertThrows(IllegalArgumentException.class, () -> SubAccountScope.of(null, null));
        assertEquals("t.sub_account_id IN (?)",
                SubAccountScope.of("t.sub_account_id", Arrays.asList(1)).sql());
    }

    @Test
    @DisplayName("null 授权集合按空集合处理（安全默认）")
    void nullMeansEmpty() {
        assertTrue(SubAccountScope.of("sub_account_id", null).isEmpty());
    }
}
