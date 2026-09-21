/**
 * L3 应用服务层（{@code ARCHITECTURE.md} §3.8，Java 模块 J2）。
 *
 * <p><b>分层纪律（违反即返工）</b>：
 * <ul>
 *   <li><b>不得出现 SQL</b>——SQL 只在 {@code com.hedgeguard.dao} 内（附录 B 红线 2，CI 脚本 {@code E5} 拦住）；</li>
 *   <li><b>不得依赖 HTTP 类型</b>（{@code HttpExchange} 等），保证业务逻辑可在无 HTTP 环境下单测（{@code NFR-TEST-2}）；</li>
 *   <li>只经 {@code dao} 的<b>接口</b>访问数据，不得依赖数据库驱动类型（{@code Connection}/{@code ResultSet}）；</li>
 *   <li>只经 {@link com.hedgeguard.bridge.TradingBridge} 与 C++ 交互（便于打桩，{@code NFR-TEST-4}）；</li>
 *   <li><b>事务边界在本层</b>（跨模块编排的事务由本层开启，经 {@code dao.tx.TxTemplate} 执行）。</li>
 * </ul>
 *
 * <p><b>11 个模块 → 服务映射</b>（{@code §3.8}）：
 * {@code PRODUCT}→{@code VarietyService}、{@code SPOT}→{@code SpotService}、
 * {@code PLAN}→{@code PlanService}、{@code TRADE}→{@code TradeService}、
 * {@code SETTLE}→{@code SettleService}、{@code RISK}→{@code RiskService}、
 * {@code AUDIT}→{@code AuditService}、{@code ACCT}→{@code AcctService}、
 * {@code FUND}→{@code FundService}、{@code AUTH}→{@code AuthService}、
 * {@code CONFIG}→{@code ConfigService}。
 *
 * <p>跨模块共用能力 {@link com.hedgeguard.service.ParamDispatchService} 承载"改 Java 侧 → 下发 C++ →
 * 等回执 → 留痕"的统一编排。
 */
package com.hedgeguard.service;
