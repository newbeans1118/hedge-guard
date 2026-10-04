package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.TradePosition;
import java.util.List;

/**
 * {@code TB-TRADE-5 t_position} 持仓明细数据访问（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）；子账户权限由服务层注入 scope。
 */
public interface TradePositionDao extends BaseDao<TradePosition, Long> {

    /**
     * 按四元键查持仓（{@code uk_position_key}：每个子账户每合约每方向每仓型只有一行）。
     *
     * @param subAccountId 子账户
     * @param instrumentId 合约
     * @param direction    LONG / SHORT
     * @param positionType TODAY / YESTERDAY
     * @return 命中的实体；不存在返回 {@code null}
     */
    TradePosition findByKey(int subAccountId, String instrumentId, String direction, String positionType);

    /**
     * 按子账户取持仓（{@code idx_position_sub_instrument}；可用持仓校验、平仓量校验）。
     *
     * @param subAccountId 子账户
     * @return 列表（{@code instrument_id} 升序，{@code id} 升序收官）
     */
    List<TradePosition> findBySubAccount(int subAccountId);
}
