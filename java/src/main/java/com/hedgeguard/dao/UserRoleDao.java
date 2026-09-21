package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.UserRole;

import java.util.List;

/** {@code TB-AUTH-4 t_user_role} 数据访问（{@code DATA_MODEL.md} §2.2）。 */
public interface UserRoleDao extends BaseDao<UserRole, Long> {

    /**
     * 取用户全部角色（岗位互斥判定与权限聚合的输入）。
     *
     * @param userId 用户 ID
     * @return 列表
     */
    List<UserRole> findByUser(long userId);

    /**
     * 按角色统计在岗人员（岗位分离核对）。
     *
     * @param roleId 角色 ID
     * @return 列表
     */
    List<UserRole> findByRole(long roleId);
}
