package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.Role;

import java.util.List;

/** {@code TB-AUTH-2 t_role} 数据访问（{@code DATA_MODEL.md} §2.2）。 */
public interface RoleDao extends BaseDao<Role, Long> {

    /**
     * 按编码取角色。
     *
     * @param roleCode 角色编码
     * @return 角色；不存在返回 {@code null}
     */
    Role findByCode(String roleCode);

    /**
     * 按岗位组取角色集合（互斥校验用）。
     *
     * @param positionGroupId 岗位组 ID
     * @return 列表
     */
    List<Role> findByPositionGroup(int positionGroupId);

    /**
     * 取具交易下单能力的启用角色（负责人禁授交易岗校验用）。
     *
     * @return 列表
     */
    List<Role> findTradingRoles();
}
