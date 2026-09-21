package com.hedgeguard.service;

/**
 * 「是否具备交易岗权限」取数入口（{@code FR-PLAN-10} ⑦ / {@code R-PLAN-2}③；{@code ARCHITECTURE.md} §6.4）。
 *
 * <p><b>为何单独抽接口</b>（与 {@link SubAccountVisibility} 同法）：{@code PlanService.submitForApproval}
 * 需在服务端强制「**发起人不得同时具备交易岗权限**」（前中后台岗位分离），但直接依赖整个
 * {@code AuthService} 会把「计划写路径」与「认证 / 会话 / 授权」耦合在一起，且令单测难以注入。
 * 本接口只暴露一个纯取数方法，由 {@link AuthService#hasTradingRole(long)} 实现，亦可由测试以 lambda 注入。
 *
 * <p><b>fail-closed</b>：取数入口缺位或返回 {@code true} 时，{@code PlanService} 一律按「具备交易岗」
 * 处理并阻断提交——绝不退化为「不校验 = 放行」。
 */
@FunctionalInterface
public interface TradingRoleChecker {

    /**
     * 该用户是否具备交易岗权限（{@code t_role.has_trading_operation = 'Y'} 且已授予）。
     *
     * @param userId 用户 ID
     * @return {@code true} = 具备交易岗权限
     */
    boolean hasTradingRole(long userId);
}
