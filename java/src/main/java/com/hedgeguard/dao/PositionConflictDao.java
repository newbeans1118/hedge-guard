package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.PositionConflict;

import java.util.List;

/**
 * {@code TB-AUTH-6 t_position_conflict} 数据访问（{@code DATA_MODEL.md} §2.2）。
 *
 * <p>矩阵按单向存储（仅存 {@code a < b}），{@link #findConflictsOf} 须<b>双向匹配</b>。
 */
public interface PositionConflictDao extends BaseDao<PositionConflict, Long> {

    /**
     * 取某岗位组的全部互斥组（授予前校验，{@code R-AUTH-3}）。
     *
     * @param groupId 岗位组 ID
     * @return 列表（{@code group_a_id = ? OR group_b_id = ?}）
     */
    List<PositionConflict> findConflictsOf(int groupId);
}
