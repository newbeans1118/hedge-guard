package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradeL3Reject;

/**
 * {@code TB-TRADE-10 t_trade_l3_reject} L3 护栏/紧急停止拒绝事件数据访问
 * （{@code DATA_MODEL.md} §2.6；v1.3.10，{@code DC-047}）。
 *
 * <p><b>只增不改不删</b>（合规证据，{@code R-TRADE-14} 同族）：{@code updateById} 显式抛
 * {@link UnsupportedOperationException}（{@code java/CONTRACTS.md} §2.3）。
 *
 * <p><b>写入方唯一</b>：{@code TradeService} L3 拒绝路径（{@code cr-t088} {@code R4-002} 承接，
 * 任务 {@code T-206}）；提交受理成功路径零写（{@code FR-TRADE-2} 验收 5 不变）。
 * 读取面（审计回溯查询）属后续审计查询任务，本接口纯增量不预置。
 */
public interface TradeL3RejectDao extends BaseDao<TradeL3Reject, Long> {
}
