package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.UserSubAccountPermDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `T-099` 契约锁（**防复活守卫**）：`scopeOf` 必须**保持删除**，防止失效入口重新冒充取数权威。
 *
 * <p>背景（`ARCHITECTURE.md` §8.2②，v1.5.8 机制订正）：`scopeOf` **不读 {@code perm_type}**，无法兑现
 * `FR-AUTH-13` 的「`CROSS_READONLY`（跨账户只读）⇒ 不加过滤」语义 —— 该授权行取真实子账户
 * ⇒ 跨账户可见性失效；取哨兵 {@code 0} ⇒ 对 `t_hedge_plan` 行级归属列**恒空** ⇒ 两种取值下
 * `FR-AUTH-13` 均静默失效。故其**唯一正确取数入口**为
 * {@code AuthService.visibleSubAccountIds(long)}（含 {@code perm_type} 语义）。
 *
 * <p><b>处置 = 删除</b>（`T-099`，2026-09-21 编排队更正）：原「仅弃用实现」形态经 `javac` 探针**证伪** ——
 * 当方法<b>经接口引用</b>调用（本仓取数场景：注入 `UserSubAccountPermDao` 接口类型 → 调 `scopeOf`）时，
 * `@Deprecated(since=…, forRemoval=true)` 对 `javac -Xlint:deprecation` **零告警** ⇒ 失效入口对接口
 * 调用无任何防护（「控制看起来有、实际没有」）。故删除接口声明 + 实现 + 5 处测试假件 override。
 *
 * <p><b>类名说明</b>：保留历史名 `…DeprecationTest` 以便追溯 `T-099` 原单；本类现已为「防复活守卫」。
 *
 * <p>本用例以反射断言接口与实现**均不再声明** `scopeOf` —— 若后人「顺手」把它加回，本用例立刻变红
 * （**反退化**）。
 */
class T099ScopeOfDeprecationTest {

    @Test
    @DisplayName("🔴 T-099：scopeOf 不得在接口或实现上被重新声明（失效入口不得复活）")
    void scopeOfMustNotBeRedeclared() {
        assertFalse(declaresScopeOf(UserSubAccountPermDao.class),
                "UserSubAccountPermDao 不得重新声明 scopeOf（T-099 处置 = 删除；"
                        + "正确入口 = AuthService.visibleSubAccountIds）");
        assertFalse(declaresScopeOf(DmUserSubAccountPermDao.class),
                "DmUserSubAccountPermDao 不得重新声明 scopeOf（T-099 处置 = 删除；"
                        + "正确入口 = AuthService.visibleSubAccountIds）");
    }

    @Test
    @DisplayName("T-099：取数入口契约仍在接口上（findByUser 原始行读取），保证处置未误删")
    void interfaceContractIntact() {
        assertTrue(declaresMethod(UserSubAccountPermDao.class, "findByUser", long.class),
                "findByUser 为原始行读取入口，必须保留");
    }

    /** 该类是否**声明**（{@code getDeclaredMethods()}）了 `scopeOf(long, String)`。 */
    private static boolean declaresScopeOf(Class<?> type) {
        return declaresMethod(type, "scopeOf", long.class, String.class);
    }

    private static boolean declaresMethod(Class<?> type, String name, Class<?>... params) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && Arrays.equals(m.getParameterTypes(), params)) {
                return true;
            }
        }
        return false;
    }
}
