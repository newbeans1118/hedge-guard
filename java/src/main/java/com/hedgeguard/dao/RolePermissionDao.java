package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.RolePermission;

import java.util.List;

/** {@code TB-AUTH-3 t_role_permission} 数据访问（{@code DATA_MODEL.md} §2.2）。 */
public interface RolePermissionDao extends BaseDao<RolePermission, Long> {

    /**
     * 取角色的全部功能权限（与会话权限快照比对）。
     *
     * @param roleId 角色 ID
     * @return 列表
     */
    List<RolePermission> findByRole(long roleId);

    /**
     * 反向查"哪些角色有该权限"（越权排查）。
     *
     * @param permCode 权限编码
     * @return 列表
     */
    List<RolePermission> findByPermCode(String permCode);
}
