package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradePreTradeReject;
import java.util.List;

/**
 * {@code TB-TRADE-6 t_pre_trade_reject} 事前风控拒绝记录数据访问（{@code DATA_MODEL.md} §2.6）。
 *
 * <p><b>只增不改不删</b>（合规证据，{@code R-TRADE-14}）：{@code updateById} 显式抛
 * {@link UnsupportedOperationException}（{@code java/CONTRACTS.md} §2.3）。
 */
public interface TradePreTradeRejectDao extends BaseDao<TradePreTradeReject, Long> {

    /**
     * 按被拒指令引用取拒绝记录（{@code idx_reject_sub_time} 等的前缀；合规审计）。
     *
     * @param clientOrderRef 被拒指令引用（幂等键之一）
     * @return 列表（{@code reject_time} 升序，{@code id} 升序收官）
     */
    List<TradePreTradeReject> findByClientOrderRef(String clientOrderRef);

    /**
     * 按幂等键查拒绝记录（{@code uk_pre_trade_reject_idem}：同指令同规则只记一条）。
     *
     * @param clientOrderRef 被拒指令引用
     * @param ruleId         规则编号
     * @return 命中的实体；不存在返回 {@code null}
     */
    TradePreTradeReject findByIdem(String clientOrderRef, String ruleId);
}
