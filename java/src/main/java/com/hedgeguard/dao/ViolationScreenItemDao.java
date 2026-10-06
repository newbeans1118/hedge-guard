package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ViolationScreenItem;

import java.util.List;

/**
 * {@code TB-AUDIT-5 t_violation_screen_item} 数据访问（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-4}）。
 */
public interface ViolationScreenItemDao extends BaseDao<ViolationScreenItem, Long> {

    /**
     * 取某批次的命中明细（{@code idx_screen_item_run_type}）。
     *
     * @param screenRunId   筛查批次（{@code t_violation_screen_run.id}）
     * @param violationType 违规类型
     * @return 命中明细行
     */
    List<ViolationScreenItem> findByScreenRunAndType(long screenRunId, String violationType);
}
