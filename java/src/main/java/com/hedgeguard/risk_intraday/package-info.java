/**
 * L3 事中风控与镜像状态（{@code ARCHITECTURE.md} §3.11，Java 模块 J5）。
 *
 * <p>职责：基于 {@link com.hedgeguard.bridge.TradingBridge#mirror()} 实时监控 6 类指标
 * （实时持仓 / 浮动盈亏 / 保证金占用率 / 风险度 / 资金可用量 / 异常下单行为），超阈值告警与处置。
 *
 * <p>纪律：
 * <ul>
 *   <li>指标在内存中计算，<b>先算后异步落库</b>，不因落库阻塞事件消费（{@code R-RISK-10}、{@code FR-RISK-13}）；</li>
 *   <li>处置动作<b>只提示不自动下单</b>（{@code R-RISK-13}）；限制开仓经
 *       {@code CMD_SET_TRADING_SUSPEND} 且 {@code scope=OPEN_ONLY}——<b>平仓一律放行</b>（{@code R-RISK-5}）；</li>
 *   <li>本包<b>不得出现 SQL</b>（附录 B 红线 2），落库经 {@code service}/{@code dao} 接口完成。</li>
 * </ul>
 */
package com.hedgeguard.risk_intraday;
