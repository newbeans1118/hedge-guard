/**
 * L3 调度（{@code ARCHITECTURE.md} §3.12，Java 模块 J6；{@code T-051}）。
 *
 * <p>职责：<b>7 类定时任务</b>——日终结算、事后风控、报表生成、对账、到期提醒
 * （{@code CFG-24 plan.expiry_remind_days}）、报告周期提醒、事件流水位巡检。
 *
 * <p>核心结构：
 * <ul>
 *   <li>{@link com.hedgeguard.job.JobType} ——7 类任务的唯一清单与稳定 id（幂等键前缀）；</li>
 *   <li>{@link com.hedgeguard.job.JobTrigger} / {@link com.hedgeguard.job.JobSchedules}
 *       ——触发时点与单一装配清单（盘前提醒 → 收盘后结算链 → 高频巡检）；</li>
 *   <li>{@link com.hedgeguard.job.JobScheduler} ——到点判定 / 幂等守卫 / 失败告警 / 可重入；</li>
 *   <li>{@link com.hedgeguard.job.JobClock} ——<b>可注入时钟</b>（{@code NFR-TEST-4}）；
 *       {@link com.hedgeguard.job.JobConfig} / {@link com.hedgeguard.job.JobAlarm}
 *       ——配置读取与运维告警端口；</li>
 *   <li>{@link com.hedgeguard.job.JobIdempotencyGuard}
 *       ——{@code ABSENT→IN_PROGRESS→SUCCEEDED/FAILED} 状态守卫：成功终态不重跑、失败释放可重入；
 *       每键连续失败计数达上限（{@link com.hedgeguard.job.JobIdempotencyGuard#DEFAULT_MAX_ATTEMPTS}）
 *       即停止自动重试（不再调 L3 端口），失败告警仅在首次失败与达上限时各一次
 *       （60s 节拍下不出现告警风暴）；</li>
 *   <li>{@link com.hedgeguard.job.JobPorts} ——<b>调度层 → L3 服务层的唯一边界</b>；
 *       生产实现由 {@code Bootstrap} 用真实 L3 服务方法引用装配。</li>
 * </ul>
 *
 * <p>纪律：
 * <ul>
 *   <li>调度任务<b>不得</b>绕过 L3 直接调 DAO（{@code §3.12}）——本包零
 *       {@code com.hedgeguard.dao} 引用，由 {@code JobLayeringTest} 静态守卫；</li>
 *   <li>业务时间<b>只</b>取自注入的 {@link com.hedgeguard.job.JobClock}；
 *       生产 {@link com.hedgeguard.job.JobClock#system()} 一律经
 *       {@link com.hedgeguard.common.Clocks}，禁止直接取系统时间。</li>
 *   <li>本包<b>不得出现 SQL</b>（附录 B 红线 2，由 {@code E5} 门禁与静态守卫双重约束）。</li>
 *   <li>高频任务（事件流水位巡检）按时间片对齐节流，告警<b>由状态变化驱动</b>，
 *       禁止无条件循环重放（{@code §5.3} / {@code R-RISK-10}）。</li>
 * </ul>
 */
package com.hedgeguard.job;
