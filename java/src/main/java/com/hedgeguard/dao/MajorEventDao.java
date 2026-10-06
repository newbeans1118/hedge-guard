package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.MajorEvent;

import java.util.List;

/**
 * {@code TB-AUDIT-8 t_major_event} 数据访问（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-7}）。
 */
public interface MajorEventDao extends BaseDao<MajorEvent, Long> {

    /**
     * 按类型 + 状态筛选（{@code idx_major_event_type_status}；如筛「已触发待生成」提醒生成专项报告）。
     *
     * @param eventType 事项类型
     * @param status    事项状态
     * @return 行
     */
    List<MajorEvent> findByTypeAndStatus(String eventType, String status);
}
