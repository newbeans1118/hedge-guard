package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SettlePnl;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code TB-SETTLE-8 t_settle_pnl} 数据访问（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>通用增删改查经 {@link BaseDao}；子账户数据权限由服务层构造
 * {@link com.hedgeguard.dao.SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SettlePnlDao extends BaseDao<SettlePnl, Long> {

    // 本表仅需基类通用增删改查（{@link BaseDao}）+ 按交易日取行（T-188 亏损阈值触发取数；
    // 系统级触发评估，非用户读取面，不注入 SubAccountScope）。

    /**
     * 按交易日取全部盈亏行（{@code T-188}：按子账户求和判亏损阈值）。
     *
     * @param tradeDate 交易日
     * @return 行（可能为空）
     */
    List<SettlePnl> findByTradeDate(LocalDate tradeDate);
}
