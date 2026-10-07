package com.hedgeguard.risk_intraday;

/**
 * 「限制 / 解除新开仓」下发端口（{@code FR-RISK-15}；{@code T-065} 处置闭环）。
 *
 * <p>生产实现经既有 {@code ParamDispatchServiceImpl} 下发
 * {@code CMD_SET_TRADING_SUSPEND(0x0118)}（{@code scan_scope=OPEN_ONLY}：<b>只限开仓、平仓放行</b>，
 * {@code R-RISK-5}）；本模块只持有端口，不依赖协议细节（{@code bridge} 不被本任务改动）。
 *
 * <p><b>系统不自动交易</b>（{@code R-RISK-13}）：端口只做「限制/解除开仓」，<b>不含</b>下单能力。
 *
 * <p><b>限制</b>：{@code t_risk_disposal} 无 {@code suspend_id} 列（{@code DATA_MODEL.md} 冻结）
 * ⇒ 解除按「该子账户最近一次未撤销挂起」配对（既有 3 参 {@code releaseOpenOnly} 语义），
 * 「按标识精确解除」在数据模型上不可达（已登记残留）。
 */
public interface TradingSuspendControl {

    /**
     * 限制该子账户新开仓（平仓不受限）。
     *
     * @param subAccountId 子账户（须 &gt; 0）
     * @param reason       原因（审计）
     * @param operatorId   操作人
     * @return 下发结果
     */
    DispatchOutcome suspendOpen(int subAccountId, String reason, long operatorId);

    /**
     * 解除该子账户新开仓限制。
     *
     * @param subAccountId 子账户（须 &gt; 0）
     * @param reason       原因（审计）
     * @param operatorId   操作人
     * @return 下发结果
     */
    DispatchOutcome releaseOpen(int subAccountId, String reason, long operatorId);

    /** 下发结果（回执语义与 {@code §4.3.1}「无回执不得视为已生效」一致）。 */
    final class DispatchOutcome {

        /** 是否真正发出（仍有其它挂起时解除不下发 ⇒ {@code false}）。 */
        public final boolean sent;

        /** 组件是否确认生效。 */
        public final boolean acknowledged;

        /** {@code param_version}（未知为 {@code null}）。 */
        public final Long paramVersion;

        /** 处置下发状态：{@code SUCCESS} / {@code FAIL} / {@code UNCONFIRMED}。 */
        public final String status;

        /** 说明。 */
        public final String message;

        /**
         * 构造。
         *
         * @param sent         是否发出
         * @param acknowledged 是否确认生效
         * @param paramVersion 参数版本
         * @param status       状态
         * @param message      说明
         */
        public DispatchOutcome(boolean sent, boolean acknowledged, Long paramVersion,
                               String status, String message) {
            this.sent = sent;
            this.acknowledged = acknowledged;
            this.paramVersion = paramVersion;
            this.status = status;
            this.message = message;
        }
    }
}
