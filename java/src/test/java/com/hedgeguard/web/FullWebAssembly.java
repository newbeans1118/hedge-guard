package com.hedgeguard.web;

import com.hedgeguard.web.handler.audit.AuditLogRoutes;
import com.hedgeguard.web.handler.audit.AuditReportRoutes;
import com.hedgeguard.web.handler.audit.AuditTraceRoutes;
import com.hedgeguard.web.handler.audit.CheckTaskQueryRoutes;
import com.hedgeguard.web.handler.audit.EvidenceExportRoutes;
import com.hedgeguard.web.handler.audit.ViolationScreenRoutes;
import com.hedgeguard.web.handler.acct.AcctRoutes;
import com.hedgeguard.web.handler.auth.AuthRoutes;
import com.hedgeguard.web.handler.config.ConfigRoutes;
import com.hedgeguard.web.handler.fund.FundRoutes;
import com.hedgeguard.web.handler.plan.PlanRoutes;
import com.hedgeguard.web.handler.product.ProductRoutes;
import com.hedgeguard.risk_intraday.RiskRoutes;
import com.hedgeguard.web.handler.risk.RiskAlertRoutes;
import com.hedgeguard.web.handler.risk.RiskLimitUsageRoutes;
import com.hedgeguard.web.handler.risk.RiskRuleRoutes;
import com.hedgeguard.web.handler.settle.SettleCheckRoutes;
import com.hedgeguard.web.handler.settle.SettleRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;
import com.hedgeguard.web.handler.trade.TradeRoutes;

/**
 * 六模块（{@code AUTH}/{@code CONFIG}/{@code PRODUCT}/{@code SPOT}/{@code PLAN}/{@code RISK}）的**生产装配**，
 * 供路由级判据共用。
 *
 * <p>为什么必须共用：{@code Router.match} 是「**注册序首次命中**」，因此**注册序本身就是被判对象**
 * （{@code BUG-004} 的成因）。若各测试各写一份装配，两份注册序会各自漂移，判据便失去一致性。
 *
 * <p>服务入参传 {@code null}：装配期只调用 {@code router.register(...)} 构造 Handler，
 * 不驱动任何业务逻辑（与 {@code ModuleRoutesConsistencyTest} 同法）。
 *
 * <h2>「单一来源」的适用范围（{@code T-156} 据 {@code T-154} 复核观察 {@code O-4} 收口）</h2>
 * <p>本类**仅对「可达性系」测试**构成单一来源。当前实际共用它的测试**仅**以下三者：
 * <ul>
 *   <li>{@link RouteReachabilityExhaustiveTest}（全量穷举实例回查）；</li>
 *   <li>{@link RouteReachabilityBug004Test}（{@code BUG-004} 的 5 条回归留痕）；</li>
 *   <li>{@link RouteReachabilityProductionParityTest}（{@code T-157} 装配面集合一致性）。</li>
 * </ul>
 * <p><b>「全仓装配已统一」不成立</b>：仍有 <b>8</b> 个测试类**各自调用模块注册器**（自测口径：在
 * {@code java/src/test} 下检索形如 {@code AuthRoutes.register} / {@code ConfigRoutes.register} … 的调用，
 * 按文件去重后去掉本类）：{@code AUTHEndpointRegistrationTest}、{@code BindExposureHandlerTest}、
 * {@code PlanListRouteTest}、{@code ModuleRoutesConsistencyTest}、{@code ProductRouteDispatchTest}、
 * {@code ProductReadScopeResidualTest}、{@code T109EndpointRegistrationTest}、
 * {@code M1EndToEndAcceptanceTest}。它们各自的服务入参 / 装配范围不同（故未强求统一），
 * 但其**注册序不受本类约束**。
 *
 * <h2>不覆盖面</h2>
 * <ol>
 *   <li><b>(a) {@code Bootstrap} 直接注册的路由不在本装配面内</b>：{@code Bootstrap.assembleWithResources}
 *       在六模块注册器**之前**先注册平台级 {@code GET|HEAD /api/v1/ping}（3 段、最先、全字面），
 *       本类**不含**这 2 条 ⇒ **生产装配面 = 本装配面 + 2**。该差 2 由
 *       {@link RouteReachabilityProductionParityTest}（{@code T-157}）以集合断言显式对齐；
 *       在它落地前，此类漂移只被 {@code BootstrapAssemblyTest} 的 {@code routeCount()==84} 间接兜底。</li>
 *   <li><b>(b) 「镜像方向」断言非穷举</b>：{@link RouteReachabilityExhaustiveTest#unregisteredPathsAreNotMatched()}
 *       只**手选 4 条**未注册路径做反向验证（段数同而末段不匹配 / 未注册模块 / 未注册方法 / 不存在前缀），
 *       并**非**对「全部未注册路径」的穷举。</li>
 * </ol>
 *
 * <p>归属：测试装配面（{@code T-154}）；不改变任何 {@code main} 侧注册序，仅**按原序重放**。
 */
final class FullWebAssembly {

    /**
     * 生产装配下已注册路由条数（六模块完整装配）。
     *
     * <p>{@code T-050} 据「事中风控 REST 面」新增 {@code GET /api/v1/risk/mirror-status} 一条
     * ⇒ {@code 81 → 82}（非为过测试而放宽：{@code ApiRoutes} 已声明该路径，本任务只做<b>最小注册</b>）。
     *
     * <p>【{@code T-049}】{@code FUND} 资金管理最小注册面 {@code 15} 个 {@code (方法,路径)} 对
     * （ledger[GET,POST] / flow / deposit / withdraw / {id}/review / receipt / transfer[GET,POST] /
     * transfer/{id}/approve / quota[GET,POST] / margin / reconcile / export）
     * ⇒ {@code 96 → 111}（非为过测试而放宽：{@code ApiRoutes} 已声明 FUND 12 条路径，本任务只做
     * <b>最小注册</b>，路径逐字取自冻结清单）。
     *
     * <p>【{@code T-048}】核对任务与电子签署（{@code FR-SETTLE-13/14/17}）在 {@code SETTLE} 模块的
     * 已声明路径 {@code /settle/monthly-check}、{@code /settle/quarterly-report} 上各注册
     * {@code GET}/{@code POST}（只读查询 + 生成/签署）⇒ {@code 111 → 115}（非为过测试而放宽：
     * 未新增路径，路径逐字取自 {@code ApiRoutes}）。
     *
     * <p>【{@code T-063}】风控阈值配置面 {@code GET|POST /api/v1/risk/rule}（{@code ApiRoutes} 已声明、
     * {@code §4.1.1} 行 639 方法 GET/POST）⇒ {@code 115 → 117}（非为过测试而放宽：路径逐字取自冻结清单，
     * 本任务只做<b>最小注册</b>）。
     *
     * <p>【{@code T-067}】审计全链路追溯与操作留痕面 —— 正向/反向/完整性/操作留痕 4 条已声明路径
     * 各注册 {@code GET}/{@code POST}（{@code 4 × 2 = 8} 对）⇒ {@code 117 → 125}（非为过测试而放宽：
     * 未新增路径，路径逐字取自 {@code ApiRoutes} 的 {@code AUDIT} 清单，本任务只做<b>最小注册</b>）。
     *
     * <p>【{@code T-066}】红线复算与限额使用统计面 —— 已声明路径 {@code /risk/limit-usage} 注册
     * {@code GET}/{@code POST}（2 对）⇒ {@code 129 → 131}（非为过测试而放宽：路径逐字取自
     * {@code ApiRoutes} 的 {@code RISK} 清单，本任务只做<b>最小注册</b>）。
     *
     * <p>【{@code T-069}】审计报表/证据链导出/审计日志三组面（{@code TE-T069-001} 修复后已注册生产装配）
     * —— {@code /audit/report/{quarterly,annual,major-event}} 各 {@code GET}/{@code POST}（6 对）、
     * {@code /audit/evidence/export}（2 对）、{@code /audit/log(/verify)}（4 对）⇒ {@code 131 → 143}
     * （非为过测试而放宽：未新增路径，路径逐字取自 {@code ApiRoutes} 的 {@code AUDIT} 清单；
     * 生产装配含平台级 {@code ping} 2 条后为 {@code 145}，与 {@code BootstrapAssemblyTest} 断言同锚）。
     *
     * <p>【{@code T-194}】核对任务查询面（{@code te-verify-t071} 观察 {@code O-1} 承接）——
     * 新增声明路径 {@code /audit/check-tasks}、{@code /audit/check-tasks/overdue} 各 {@code GET}（2 对）
     * ⇒ {@code 143 → 145}（非为过测试而放宽：{@code FR-AUDIT-13} 验收 1/2/5 的独立 REST 端点缺口，
     * 服务侧 {@code CheckTaskQueryService.query/overdue} 实存但此前 {@code web/} 零 handler 调用；
     * {@code ApiRoutes} 模块表 + 方法注册表已同步；生产装配含平台级 {@code ping} 2 条后为 {@code 147}，
     * 与 {@code BootstrapAssemblyTest} 断言同锚）。
     *
     * <p>【{@code T-195}】出入金申请列表查询面（{@code FR-FUND-8} 验收 3「查询接口确认」；
     * {@code te-verify-t055} 披露①承接）——新增声明路径 {@code /fund/apply-list} 注册 {@code GET}
     * （1 对、+1 路径）⇒ {@code 145 → 146}（非为过测试而放宽：超时态资金岗此前无法定位申请 ID，
     * 服务侧 {@code FundService.listDepositWithdrawApplies} 系本单最小增量只读查询；
     * {@code ApiRoutes} 模块表 + 方法注册表已同步；生产装配含平台级 {@code ping} 2 条后为 {@code 148}，
     * 与 {@code BootstrapAssemblyTest} 断言同锚）。同单 {@code /fund/export} 响应形态 JSON→CSV 直返
     * （{@code FR-FUND-4}「输出=CSV 对账表」，{@code te-verify-t055} 观察 {@code O-3}）不增减路由条数。
     *
     * <p>【{@code T-077}】ACCT 模块首批注册面（套期关系 + 有效性评估；ACCT 方法注册表从零建）——
     * 已声明路径 {@code /acct/hedge-relation}、{@code /acct/effectiveness} 各注册 {@code GET}/{@code POST}
     * （2 路径 × 2 方法 = 4 对）⇒ {@code 146 → 150}（非为过测试而放宽：路径逐字取自
     * {@code ApiRoutes} 的 {@code ACCT} 清单；{@code ARCHITECTURE.md} §6.10 + §4.1.1 行 641）。
     * {@code ACCT} 方法注册表键由 0 对增为 4 对；其余 10 条已声明路径归 T-078/T-079/T-080。
     * 算术分解：业务路由 146 → 150（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18/RISK7/TRADE3/
     * SETTLE15/FUND16/AUDIT24 不变 + ACCT4）；生产装配含平台级 {@code ping} 2 条后为 {@code 152}，
     * 与 {@code BootstrapAssemblyTest} 断言同锚。
     */
    static final int ROUTE_COUNT = 150;

    private FullWebAssembly() {
    }

    /** 构造六模块生产装配的路由器（注册序与生产一致）。 */
    static Router assemble() {
        Router router = new Router();
        AuthRoutes.register(router, null, null);
        ConfigRoutes.register(router, null);
        ProductRoutes.register(router, null);
        SpotRoutes.register(router, null);
        PlanRoutes.register(router, null);
        // T-050：事中风控 REST 面（GET /api/v1/risk/mirror-status）。服务入参 null：
        // 装配期只调用 router.register(...) 构造 Handler，不驱动任何业务逻辑。
        RiskRoutes.register(router, null, null);
        // T-063：风控阈值配置面（GET|POST /api/v1/risk/rule）。服务入参 null：
        // 装配期只调用 router.register(...) 构造 Handler，不驱动任何业务逻辑。
        RiskRuleRoutes.register(router, null);
        // T-046：TRADE 交易执行主链最小注册面（3 条 POST：order / confirm / cancel）。
        // 服务入参 null：装配期只调用 router.register(...) 构造 Handler，不驱动任何业务逻辑。
        TradeRoutes.register(router, null);
        // T-047：SETTLE 结算最小注册面（11 个 (方法,路径) 对：ctp-data / cycle(GET,POST) /
        // cycle/{id}/{reconcile,aggregate,close} / difference / report / margin / stress-test / export）。
        // 服务入参 null：装配期只调用 router.register(...) 构造 Handler，不驱动任何业务逻辑。
        SettleRoutes.register(router, null);
        // T-048：核对任务与电子签署（4 个 (方法,路径) 对：monthly-check/quarterly-report 各 GET/POST）。
        // 服务入参 null：装配期只调用 router.register(...) 构造 Handler，不驱动任何业务逻辑。
        SettleCheckRoutes.register(router, null);
        // T-049：FUND 资金管理最小注册面（15 个 (方法,路径) 对）。服务入参 null：装配期只调用
        // router.register(...) 构造 Handler，不驱动任何业务逻辑。
        FundRoutes.register(router, null);
        // T-067：AUDIT 审计追溯与留痕面（8 个 (方法,路径) 对：trace/{forward,backward,integrity}
        // 与 operation-log 各 GET/POST）。服务入参 null：装配期只调用 router.register(...) 构造 Handler，
        // 不驱动任何业务逻辑。
        AuditTraceRoutes.register(router, null);
        // T-068：违规事项筛查面（2 个 (方法,路径) 对：GET|POST /audit/violation-screen）。
        // 服务入参 null：装配期只调用 router.register(...)。
        ViolationScreenRoutes.register(router, null);
        // T-069（TE-T069-001 修复）：审计日志面（4 对：/audit/log、/audit/log/verify 各 GET|POST）。
        // 服务入参 null：装配期只调用 router.register(...)。注册序与生产装配（Bootstrap）一致。
        AuditLogRoutes.register(router, null);
        // T-069：审计报表面（6 对：/audit/report/{quarterly,annual,major-event} 各 GET|POST）。
        AuditReportRoutes.register(router, null);
        // T-069：证据链导出面（2 对：GET|POST /audit/evidence/export）。
        EvidenceExportRoutes.register(router, null);
        // T-194（te-verify-t071 O-1 承接）：核对任务查询面（2 对：GET /audit/check-tasks、
        // GET /audit/check-tasks/overdue；FR-AUDIT-13 验收 1/2/5）。服务入参 null：
        // 装配期只调用 router.register(...)。注册序与生产装配（Bootstrap）一致。
        CheckTaskQueryRoutes.register(router, null);
        // T-077：ACCT 模块首批注册面（4 对：GET|POST /acct/hedge-relation、GET|POST
        // /acct/effectiveness；三要素门禁 R-ACCT-1 + 三项定性观察点 R-ACCT-2/Q-7）。
        // 服务入参 null：装配期只调用 router.register(...)。注册序与生产装配（Bootstrap）一致。
        AcctRoutes.register(router, null);
        // T-065：风控告警查询与处置面（2 个 (方法,路径) 对：GET /risk/alert、
        // POST /risk/alert/{id}/dispose）。服务入参 null：装配期只调用 router.register(...)。
        RiskAlertRoutes.register(router, null);
        // T-066：红线复算与限额使用统计面（2 个 (方法,路径) 对：GET|POST /risk/limit-usage）。
        // 服务入参 null：装配期只调用 router.register(...)。
        RiskLimitUsageRoutes.register(router, null);
        return router;
    }
}
