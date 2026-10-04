package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradeOrderReport;
import java.util.List;

/**
 * {@code TB-TRADE-2 t_trade_order_report} 委托（报单）回报数据访问（{@code DATA_MODEL.md} §2.6）。
 *
 * <p><b>只增不改</b>（回报逐条追加，幂等键 {@code uk_order_report_idem}）：{@code updateById}
 * 显式抛 {@link UnsupportedOperationException}（{@code java/CONTRACTS.md} §2.3）。
 */
public interface TradeOrderReportDao extends BaseDao<TradeOrderReport, Long> {

    /**
     * 按指令取回报轨迹（{@code idx_order_report_order}；状态追溯）。
     *
     * @param orderId 关联指令
     * @return 列表（{@code report_ts_ms} 升序，{@code id} 升序收官）
     */
    List<TradeOrderReport> findByOrder(long orderId);

    /**
     * 按幂等键查回报（{@code uk_order_report_idem}；重复推送不重复计入，{@code FR-TRADE-4} 验收 2）。
     *
     * @param orderSysId   CTP 报单编号
     * @param orderStatus  CTP 委托状态
     * @param volumeTraded 已成交量
     * @return 命中的实体；不存在返回 {@code null}
     */
    TradeOrderReport findByIdem(String orderSysId, String orderStatus, int volumeTraded);
}
