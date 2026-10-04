package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradeReplayLog;
import java.util.List;

/**
 * {@code TB-TRADE-9 t_replay_log} 重放记录数据访问（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>例外 E3：组件级重放日志，无 {@code sub_account_id} 列；重放过程与结果可审计。
 */
public interface TradeReplayLogDao extends BaseDao<TradeReplayLog, Long> {

    /**
     * 按组件取重放历史（{@code idx_replay_log_time} 前缀；{@code FR-TRADE-15} 验收 3）。
     *
     * @param componentId 组件标识（hg-trading）
     * @return 列表（{@code replay_seq} 升序）
     */
    List<TradeReplayLog> findByComponent(String componentId);
}
