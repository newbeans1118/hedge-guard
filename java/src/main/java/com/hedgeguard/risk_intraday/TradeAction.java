package com.hedgeguard.risk_intraday;

/** 成交方向（用于异常下单行为监控的开/平仓识别）。 */
public enum TradeAction {

    /** 开仓（增加净持仓方向）。 */
    OPEN,

    /** 平仓 / 减仓。 */
    CLOSE
}
