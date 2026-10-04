package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.HgcpConstants;
import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.bridge.ParamChangeEncoder;
import hgcp.v1.Hgcp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-050} 验收 4：桥接层已支持 {@code CMD_SET_TRADING_SUSPEND(0x0118)} 与
 * {@code scan_scope=OPEN_ONLY}（跨栈「只限开仓、不限平仓」的 Java 侧承载）。
 *
 * <p>本用例<b>只验证既有 bridge 能力，不改协议</b>——证明 {@code risk_intraday} 经
 * {@code TradingSuspender} 端口下发的限制，可被编码为 C++ 侧
 * {@code CmdSetTradingSuspend(scan_scope=OPEN_ONLY)}（{@code R-RISK-5}）。
 */
class BridgeOpenOnlySupportTest {

    @Test
    @DisplayName("CMD_SET_TRADING_SUSPEND == 0x0118，且可编码为 scan_scope=OPEN_ONLY")
    void encoderSupportsOpenOnlySuspend() throws Exception {
        assertEquals(0x0118, HgcpConstants.CMD_SET_TRADING_SUSPEND);

        ParamChange change = new ParamChange(ParamChange.Type.CMD_SET_TRADING_SUSPEND, 7L, 42L)
                .with("sub_account_id", "3")
                .with("scan_scope", "OPEN_ONLY")
                .with("suspended", "true")
                .with("reason", "事中风控：日内开平合计超严重阈值")
                .with("operator_id", "42");

        Hgcp.CmdSetTradingSuspend msg =
                Hgcp.CmdSetTradingSuspend.parseFrom(ParamChangeEncoder.encode(change));

        assertEquals(7L, msg.getParamVersion());
        assertEquals(3, msg.getSubAccountId());
        assertEquals(Hgcp.ScanScope.SCAN_SCOPE_OPEN_ONLY, msg.getScanScope(),
                "须编码为 OPEN_ONLY（平仓/减仓方向由 C++ 侧跳过该规则，R-RISK-5）");
        assertTrue(msg.getSuspended());
        assertEquals(42L, msg.getOperatorId());
    }
}
