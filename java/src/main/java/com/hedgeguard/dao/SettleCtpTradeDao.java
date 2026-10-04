package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SettleCtpTrade;

/**
 * {@code TB-SETTLE-4 t_ctp_settle_trade} 数据访问（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link com.hedgeguard.dao.SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SettleCtpTradeDao extends BaseDao<SettleCtpTrade, Long> {

    // 本表仅需基类通用增删改查（{@link BaseDao}）。
}
