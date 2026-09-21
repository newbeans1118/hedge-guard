package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.UserSubAccountPerm;

import java.util.List;

/**
 * {@code TB-AUTH-8 t_user_sub_account_perm} 数据访问（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>本接口是 {@link SubAccountScope} 的<b>数据来源</b>：过滤器据此构造
 * {@code sub_account_id IN (...)}；查不到任何记录时须注入恒假条件（返回空集），
 * <b>不得</b>跳过过滤（{@code NFR-SEC-7} 安全默认）。
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
     * 构造该用户的数据权限过滤器（**推荐入口**，避免各模块自行拼条件）。
     *
     * @param userId 用户 ID
     * @param column 目标表中的子账户列名（如 {@code sub_account_id}）
     * @return 过滤器（无授权时为恒假条件）
     */
    SubAccountScope scopeOf(long userId, String column);
}
