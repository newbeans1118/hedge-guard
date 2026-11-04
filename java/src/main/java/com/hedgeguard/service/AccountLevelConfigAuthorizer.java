package com.hedgeguard.service;

/**
 * 「账户级（层③）配置权」断言入口（{@code CH-012} / {@code FR-PRODUCT-11} 验收 5；
 * {@code ARCHITECTURE.md} §8.2③「账户级写面授权点」；{@code T-119}）。
 *
 * <p><b>为何单独抽接口</b>（与 {@link SubAccountVisibility} / {@link TradingRoleChecker} 同法）：
 * {@link VarietyService#putQuota} 的<b>层③</b>（{@code ACCOUNT_PRODUCT}，行级落账户哨兵 {@code 0}）
 * 须做写面授权门禁，但直接依赖整个 {@code AuthService} 会把「品种额度写路径」与
 * 「认证 / 会话 / 角色」耦合在一起，且令单测难以注入。本接口只暴露一个纯判定方法，由
 * {@link AuthService#assertCanConfigureAccountLevel(long)} 实现，亦可由测试以 lambda 注入。
 *
 * <p><b>为何不复用子账户可见性</b>：层③ 的有效目标是账户哨兵 {@code 0}、<b>非子账户行级归属</b>
 * ⇒ 属<b>功能权限</b>维度（{@code FR-AUTH-12}「数据权限与功能权限独立」），
 * <b>不得</b>套用 {@link SubAccountVisibility#assertSubAccountWritable}——后者放行判据
 * 「{@code id ≠ 哨兵} <b>且</b> {@code id == 目标}」与 {@code target = 0} <b>结构性互斥</b> ⇒
 * <b>必然误拒</b>（详见 {@code ARCHITECTURE.md} §8.2③）。
 *
 * <p><b>fail-closed</b>：实现方在<b>无权</b>时须抛 {@code BizException}（业务码 {@code 10003}）；
 * 取数入口缺位（{@code null}）时，{@link VarietyService#putQuota} 同一律按<b>拒绝</b>处理——
 * 绝不退化为「不校验 = 放行」。
 */
@FunctionalInterface
public interface AccountLevelConfigAuthorizer {

    /**
     * 断言调用者具账户级（层③）额度配置权。
     *
     * @param userId 调用者用户 ID
     * @throws com.hedgeguard.web.BizException 不具配置权时（业务码 {@code 10003}）
     */
    void assertCanConfigureAccountLevel(long userId);
}
