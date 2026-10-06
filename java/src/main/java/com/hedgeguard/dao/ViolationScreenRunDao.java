package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ViolationScreenRun;

/**
 * {@code TB-AUDIT-4 t_violation_screen_run} 数据访问（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-4}）。
 */
public interface ViolationScreenRunDao extends BaseDao<ViolationScreenRun, Long> {

    /**
     * 按筛查批次号取唯一一行（{@code uk_violation_screen_no}）。
     *
     * @param screenNo 筛查批次号
     * @return 行；无则 {@code null}
     */
    ViolationScreenRun findByScreenNo(String screenNo);
}
