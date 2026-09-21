/**
 * L3 调度（{@code ARCHITECTURE.md} §3.12，Java 模块 J6）。
 *
 * <p>职责：日终结算、事后风控、报表生成、对账定时任务、到期提醒、报告周期提醒、事件流水位巡检。
 *
 * <p>纪律：
 * <ul>
 *   <li>调度任务<b>不得</b>绕过 L3 直接调 DAO（{@code §3.12}）；</li>
 *   <li>触发时刻<b>可注入时钟</b>（{@code NFR-TEST-4}）——一律经 {@link com.hedgeguard.common.Clocks}，禁止直接取系统时间；</li>
 *   <li>本包<b>不得出现 SQL</b>（附录 B 红线 2）。</li>
 * </ul>
 */
package com.hedgeguard.job;
