package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.UserSubAccountPerm;

import java.util.List;

/**
 * {@code TB-AUTH-8 t_user_sub_account_perm} 数据访问（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>本接口只提供 {@code t_user_sub_account_perm} 的<b>原始行</b>读取（{@link #findByUser}）；
 * <b>带 {@code perm_type} 语义的取数入口不在本接口</b> —— 而是
 * {@link com.hedgeguard.service.AuthService#visibleSubAccountIds(long)}
 * （{@code ARCHITECTURE.md} §8.2②，v1.5.8 机制订正）：由调用方判定返回集合是否含哨兵
 * {@link SubAccountScope#ALL_ACCOUNT_SENTINEL}（{@code perm_type='CROSS_READONLY'} /
 * 跨账户只读 ⇒ <b>不构造 scope、不加过滤</b>）；返回<b>空集 ⇒ 恒假条件</b>
 * （{@code NFR-SEC-7} 安全默认，<b>不得</b>退化为"不过滤 = 全可见"）。
 *
 * <p>⚠️ <b>历史入口 {@code scopeOf} 已删除</b>（{@code T-099}，2026-09-21）：其<b>不读</b>
 * {@code perm_type}，无法兑现 {@code FR-AUTH-13} 的 {@code CROSS_READONLY ⇒ 不加过滤} 语义
 * （该授权行取真实子账户 ⇒ 跨账户可见性失效；取哨兵 {@code 0} ⇒ 对行级归属列恒空）⇒ 两种取值下
 * {@code FR-AUTH-13} 均静默失效。因「以接口类型注入调用」对 {@code @Deprecated} 零防护
 * （javac 探针证伪），故<b>删除</b>而非弃用。防复活守卫见
 * {@code com.hedgeguard.dao.impl.T099ScopeOfDeprecationTest#scopeOfMustNotBeRedeclared}。
 */
public interface UserSubAccountPermDao extends BaseDao<UserSubAccountPerm, Long> {

    /**
     * 取用户全部子账户授权。
     *
     * @param userId 用户 ID
     * @return 列表
     */
    List<UserSubAccountPerm> findByUser(long userId);

    /**
     * 取对指定子账户有数据权限的用户 ID 集（{@code FR-AUTH-5} 验收 1「按子账户筛选用户列表」；{@code T-135}）。
     *
     * <p>与 {@link #findByUser} 呈<b>反向</b>索引；供用户列表筛选下推 {@code id IN (...)} 使用。
     * <b>不是</b>取数入口（不带 {@code perm_type} 语义），仅用于枚举授权行主体。
     *
     * @param subAccountId 子账户 ID
     * @return 用户 ID 列表（去重后升序；无授权返回空集）
     */
    List<Long> findUserIdsBySubAccount(int subAccountId);
}
