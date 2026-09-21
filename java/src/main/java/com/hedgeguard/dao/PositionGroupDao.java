package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.PositionGroup;

import java.util.List;

/** {@code TB-AUTH-5 t_position_group} 数据访问（{@code DATA_MODEL.md} §2.2）。 */
public interface PositionGroupDao extends BaseDao<PositionGroup, Integer> {

    /**
     * 按编码取岗位组。
     *
     * @param groupCode 岗位组编码
     * @return 岗位组；不存在返回 {@code null}
     */
    PositionGroup findByCode(String groupCode);

    /**
     * 取启用岗位组。
     *
     * @return 列表
     */
    List<PositionGroup> findEnabled();
}
