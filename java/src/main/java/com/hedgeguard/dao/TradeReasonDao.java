package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradeReason;

/**
 * {@code TB-TRADE-4 t_trade_reason} 交易理由数据访问（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）；子账户权限由服务层注入 scope。
 */
public interface TradeReasonDao extends BaseDao<TradeReason, Long> {

    /**
     * 按指令查理由（{@code uk_trade_reason_order}：一条指令一条理由）。
     *
     * @param tradeOrderId 关联指令
     * @return 命中的实体；不存在返回 {@code null}
     */
    TradeReason findByTradeOrderId(long tradeOrderId);
}
