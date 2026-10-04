package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradeFill;
import java.util.List;

/**
 * {@code TB-TRADE-3 t_trade_fill} 成交记录数据访问（{@code DATA_MODEL.md} §2.6）。
 *
 * <p><b>只增不改不删</b>（{@code R-TRADE-15}）：{@code updateById} 显式抛
 * {@link UnsupportedOperationException}（{@code java/CONTRACTS.md} §2.3）。
 */
public interface TradeFillDao extends BaseDao<TradeFill, Long> {

    /**
     * 按 CTP 成交编号查成交（{@code uk_trade_fill_id} 幂等键，{@code FR-TRADE-5} 验收 4）。
     *
     * @param tradeId CTP 成交编号
     * @return 命中的实体；不存在返回 {@code null}
     */
    TradeFill findByTradeId(String tradeId);

    /**
     * 按指令取成交明细（{@code idx_trade_fill_order}）。
     *
     * @param orderId 关联指令
     * @return 列表（{@code trade_ts_ms} 升序，{@code id} 升序收官）
     */
    List<TradeFill> findByOrder(long orderId);
}
