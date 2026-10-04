package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradeCommandFlow;
import java.util.List;

/**
 * {@code TB-TRADE-7 t_command_flow} 指令流水镜像数据访问（{@code DATA_MODEL.md} §2.6）。
 *
 * <p><b>只增不改</b>（镜像按事件逐条追加，幂等键 {@code uk_command_flow_idem}）：{@code updateById}
 * 显式抛 {@link UnsupportedOperationException}（{@code java/CONTRACTS.md} §2.3）。
 */
public interface TradeCommandFlowDao extends BaseDao<TradeCommandFlow, Long> {

    /**
     * 按指令号取完整轨迹（{@code idx_command_flow_order}，{@code FR-TRADE-16} 验收 1）。
     *
     * @param orderId C++ 指令号
     * @return 列表（{@code flow_ts_ms} 升序，{@code id} 升序收官）
     */
    List<TradeCommandFlow> findByOrderId(long orderId);

    /**
     * 按幂等键（未受理指令无 {@code order_id}）取轨迹（{@code idx_command_flow_ref}）。
     *
     * @param clientOrderRef 幂等键
     * @return 列表（{@code flow_ts_ms} 升序，{@code id} 升序收官）
     */
    List<TradeCommandFlow> findByClientOrderRef(String clientOrderRef);
}
