package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ConfigChangeLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code TB-CONFIG-3 t_config_change_log} 数据访问（{@code DATA_MODEL.md} §2.1）。
 *
 * <p><b>只增不改不删</b>：{@link #updateById} 的实现必须显式拒绝（抛
 * {@link UnsupportedOperationException}），本接口不提供任何删除方法。
 */
public interface ConfigChangeLogDao extends BaseDao<ConfigChangeLog, Long> {

    /**
     * 按配置项查变更历史。
     *
     * @param configKey 配置键
     * @param from      起始时间（含）
     * @param to        结束时间（含）
     * @return 列表（按变更时间倒序）
     */
    List<ConfigChangeLog> findByKeyBetween(String configKey, LocalDateTime from, LocalDateTime to);

    /**
     * 列出下发失败 / 未确认的记录（运维巡检）。
     *
     * @return 列表（{@code dispatch_result IN ('FAIL','UNCONFIRMED')}）
     */
    List<ConfigChangeLog> findDispatchProblems();
}
