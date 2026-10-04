package com.hedgeguard.risk_intraday;

/**
 * 「限制新开仓」下发端口（{@code FR-RISK-15}）。
 *
 * <p>生产实现经既有 {@code ParamDispatchService} 下发 {@code CMD_SET_TRADING_SUSPEND(0x0118)}，
 * 携带 {@code scan_scope=OPEN_ONLY}：<b>只限开仓、平仓一律放行</b>（{@code R-RISK-5}）。
 * 本模块<b>不自行扩协议</b>——端口形态使 {@code risk_intraday} 不依赖 {@code bridge} 协议细节，
 * 由装配层（{@code Bootstrap}）把既有下发能力适配为本端口。
 *
 * <p><b>系统不自动交易</b>（{@code R-RISK-13}）：本端口只做「限制开仓」，<b>不含</b>任何下单能力。
 */
@FunctionalInterface
public interface TradingSuspender {

    /**
     * 限制该子账户的新开仓（平仓不受限）。
     *
     * @param subAccountId 子账户
     * @param reason       原因（审计）
     * @param operatorId   操作人（自动化处置取系统操作人）
     */
    void suspendOpenOnly(int subAccountId, String reason, long operatorId);
}
