/*
 * 前端侧「冻结/已实现路径镜像」——以 **已注册的 Java Handler** 为施工依据。
 *
 * 事实源优先级（T-ARCH-004 §要求 3 + §5.2「契约逐字对齐」）：
 *   ① 实际已注册路由：java/src/main/java/com/hedgeguard/web/handler 下的各 …Routes.java（router.register 调用）
 *   ② ARCHITECTURE.md §4.1.1（路径清单与信封）
 *   ③ java/CONTRACTS.md §1（信封 / 分页 / 幂等冻结）
 * 路径本身三处一致；**字段名以已实现 Handler 的请求/视图类为准（camelCase）**。
 *
 * ⚠️ 本文件是**前端只读镜像**，仅为消除各页面自行拼字符串的漂移；新增/改名须先改 §4.1.1 → ApiRoutes.java → 本文件。
 *
 * ## 口径（**勿用任何总数核对** —— 本项目已因口径不一浪费排查轮次）
 * 接口面的**唯一权威是 `java/src/main/java/com/hedgeguard/web/ApiRoutes.java`**（`byModule()` 逐模块列出的路径）。
 * 铺页面只按**逐条路径**，不按总数。若确须引用规模，统一写：**「83 个 (方法, 路径) 对 / 60 个路径」**并标明口径：
 *   判据 = `java/src/test/java/com/hedgeguard/web/BootstrapAssemblyTest.java:145`
 *     `assertEquals(83, assembly.routeCount(), "业务路由必须全部注册：81（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18）+ ping 2")`
 *   分模块（按"对"）：AUTH 17 / CONFIG 18 / PRODUCT 13 / SPOT 15 / PLAN 18 = 81，另加 `ping` 的 `GET`+`HEAD` 2 对 ⇒ 83。
 *   （`PLAN` 的 18 对含 2 处循环注册：`PlanActionHandler.Action.values()` 8 条 + `PlanRatioHandler.Method.values()` 3 条。）
 * ⚠️ **PRODUCT 的路径数为 9、按"对"为 13**（旧口径"6"是 `ModuleRoutesConsistencyTest` 的**覆盖下限清单**，非全量，勿用）。
 * ⚠️ **不要写 47 / 38 / 29**（均已作废；47 系 `T-109` 补齐「已声明未注册」端点之前的旧值）。
 *
 * ## 已注册（M1，实测 `…Routes.register(...)`；逐条见下方 ROUTES）
 *   AUTH    POST /auth/login、POST /auth/logout、GET|POST|PUT /auth/user、GET|POST /auth/role、
 *           GET|POST /auth/position-group、GET|POST /auth/authorization、GET|POST /auth/sub-account-perm、
 *           GET /auth/change-log、GET /auth/unauthorized-attempt、POST|PUT /auth/password
 *   CONFIG  GET|PUT /config/item、GET|POST|PUT /config/sub-account、GET|POST /config/ctp、POST /config/ctp/test、
 *           GET|POST /config/dict、GET|POST /config/subject、GET|POST /config/report-period、
 *           GET /config/dispatch/{id}、GET /config/change-log、POST /config/import、GET /config/export
 *   PRODUCT GET|POST /product、GET /product/{id}、POST /product/whitelist、GET|POST /product/quota、
 *           GET|POST /product/{id}/contracts、GET|POST /product/mapping、GET /product/change-log、
 *           POST /product/import、GET /product/export
 *   SPOT    POST /spot/purchase、POST /spot/sales、POST /spot/production、POST /spot/inventory、
 *           POST /spot/wip、POST|GET /spot/price、POST /spot/price/import、GET /spot/price/export、
 *           POST|GET /spot/exposure、GET /spot/exposure/{id}、POST /spot/exposure/recalc、
 *           GET /spot/exposure/export、POST /spot/doc/sub-account
 *   PLAN    GET|POST /plan、GET /plan/{id}、POST /plan/{id}/doc、POST /plan/{id}/bind-exposure、
 *           POST /plan/{id}/{submit|approve|reject|withdraw|freeze|unfreeze|terminate|extend}、
 *           GET|POST /plan/{id}/trade-plans、POST /plan/ratio/{simple|minvar|custom}
 *   PING    GET|HEAD /ping
 * ## 未注册（前端**不得**调用；handler/ 下无该模块目录，见 Q-FE-001）
 *   `/risk/dashboard`、`/trade/order`、其余模块（risk/trade/settle/acct/fund/audit）
 *
 * 边界：只暴露路径常量；不拼业务参数、不做业务判定。
 */
import { HG } from './ns.js';

const enc = encodeURIComponent;

export const ROUTES = {
  BASE: '/api/v1',
  PING: '/ping',

  AUTH: {
    LOGIN: '/auth/login',
    LOGOUT: '/auth/logout',
    USER: '/auth/user',
    ROLE: '/auth/role',
    POSITION_GROUP: '/auth/position-group',
    AUTHORIZATION: '/auth/authorization',
    SUB_ACCOUNT_PERM: '/auth/sub-account-perm',
    CHANGE_LOG: '/auth/change-log',
    UNAUTHORIZED_ATTEMPT: '/auth/unauthorized-attempt',
    PASSWORD: '/auth/password'
  },
  CONFIG: {
    ITEM: '/config/item',
    SUB_ACCOUNT: '/config/sub-account',
    CTP: '/config/ctp',
    CTP_TEST: '/config/ctp/test',
    DICT: '/config/dict',
    SUBJECT: '/config/subject',
    REPORT_PERIOD: '/config/report-period',
    CHANGE_LOG: '/config/change-log',
    IMPORT: '/config/import',
    EXPORT: '/config/export',
    dispatch: (id) => '/config/dispatch/' + enc(id)
  },
  PRODUCT: {
    LIST: '/product',
    detail: (id) => '/product/' + enc(id),
    WHITELIST: '/product/whitelist',
    QUOTA: '/product/quota',
    CONTRACTS: (id) => '/product/' + enc(id) + '/contracts',
    MAPPING: '/product/mapping',
    CHANGE_LOG: '/product/change-log',
    IMPORT: '/product/import',
    EXPORT: '/product/export'
  },
  SPOT: {
    PURCHASE: '/spot/purchase',
    SALES: '/spot/sales',
    PRODUCTION: '/spot/production',
    INVENTORY: '/spot/inventory',
    WIP: '/spot/wip',
    PRICE: '/spot/price',
    PRICE_IMPORT: '/spot/price/import',
    PRICE_EXPORT: '/spot/price/export',
    EXPOSURE: '/spot/exposure',
    EXPOSURE_RECALC: '/spot/exposure/recalc',
    EXPOSURE_EXPORT: '/spot/exposure/export',
    DOC_SUB_ACCOUNT: '/spot/doc/sub-account',
    exposureDetail: (id) => '/spot/exposure/' + enc(id)
  },
  PLAN: {
    LIST: '/plan',                                    // GET（列表，FR-PLAN-4；PlanRoutes.java:52 已注册）
    CREATE: '/plan',                                  // POST（编制计划；与 LIST 同路径不同方法）
    detail: (id) => '/plan/' + enc(id),
    doc: (id) => '/plan/' + enc(id) + '/doc',
    bindExposure: (id) => '/plan/' + enc(id) + '/bind-exposure', // FR-PLAN-19（BindExposureHandler.PATH）
    tradePlans: (id) => '/plan/' + enc(id) + '/trade-plans',
    action: (id, action) => '/plan/' + enc(id) + '/' + enc(action),
    ratio: (method) => '/plan/ratio/' + enc(method)   // simple | minvar | custom
  },
  AUDIT: {
    TRACE_FORWARD: '/audit/trace/forward',
    TRACE_BACKWARD: '/audit/trace/backward',
    TRACE_INTEGRITY: '/audit/trace/integrity',
    VIOLATION_SCREEN: '/audit/violation-screen',
    REPORT_QUARTERLY: '/audit/report/quarterly',
    REPORT_ANNUAL: '/audit/report/annual',
    REPORT_MAJOR_EVENT: '/audit/report/major-event',
    EVIDENCE_EXPORT: '/audit/evidence/export',
    LOG: '/audit/log',
    LOG_VERIFY: '/audit/log/verify',
    OPERATION_LOG: '/audit/operation-log',
    // T-194：核对任务查询（FR-AUDIT-13 验收 1/2/5；CheckTaskQueryHandler.PATH 逐字一致）
    CHECK_TASKS: '/audit/check-tasks',
    CHECK_TASKS_OVERDUE: '/audit/check-tasks/overdue'
  },
  // T-055：资金管理（FR-FUND-1~17）。路径逐字取自 ApiRoutes.byModule().get("FUND")（13 路径，
  // T-195 起新增 /fund/apply-list）；方法对取自 handler/fund/FundRoutes.java（GET|POST ledger /
  // GET flow / POST deposit|withdraw / POST {id}/review / GET|POST transfer /
  // POST transfer/{id}/approve / POST receipt / GET|POST quota / GET margin / POST reconcile /
  // GET export / GET apply-list）。
  FUND: {
    LEDGER: '/fund/ledger',                                        // GET 台账分页；POST 建立台账（双人复核）
    FLOW: '/fund/flow',                                            // GET 资金流水
    DEPOSIT: '/fund/deposit',                                      // POST 入金申请（经 CTP，幂等）
    WITHDRAW: '/fund/withdraw',                                    // POST 出金申请（经 CTP，幂等）
    review: (id) => '/fund/' + enc(id) + '/review',                // POST 出入金双人复核（参数化，FundReviewHandler.PATH）
    TRANSFER: '/fund/transfer',                                    // GET 划拨记录分页；POST 发起划拨
    transferApprove: (id) => '/fund/transfer/' + enc(id) + '/approve', // POST 划拨审批 + 记账 + 加总校验
    RECEIPT: '/fund/receipt',                                      // POST 处理 CTP 回执（幂等）
    QUOTA: '/fund/quota',                                          // GET 额度查询；POST 额度设定
    MARGIN: '/fund/margin',                                        // GET 保证金占用视图（只读）
    RECONCILE: '/fund/reconcile',                                  // POST 每日资金对账
    EXPORT: '/fund/export',                                        // GET 资金对账导出（T-195 起 CSV 直返下载）
    APPLY_LIST: '/fund/apply-list'                                 // GET 出入金申请列表分页（FR-FUND-8 验收3；T-195）
  },
  // T-070：风险控制（FR-RISK-1~19）。**端点注册面不对称**——ApiRoutes 路径表登记 10 路径，
  // 但方法注册表（BY_MODULE_ROUTES）仅 6 对、handler/ 下仅三组 Routes（Alert/LimitUsage/Rule）
  // + risk_intraday 事中面（MirrorStatus）。本键**只放可接 5 端点**（不得臆造调用）；
  // 未注册 5 路径留在 NOT_REGISTERED（见下），页面保留框架 + 「未注册」诚实提示（T-070）。
  RISK: {
    ALERT: '/risk/alert',                                   // GET 告警历史查询（FR-RISK-19；筛选/分页/缺省 alert_time desc/since 补拉）
    dispose: (id) => '/risk/alert/' + enc(id) + '/dispose', // POST 告警处置（FR-RISK-15/16；参数化函数键）
    LIMIT_USAGE: '/risk/limit-usage',                       // GET 限额使用统计 / POST 红线复算落库（T-066；FR-RISK-4/5/6/18）
    MIRROR_STATUS: '/risk/mirror-status',                   // GET 事中镜像状态（T-065；只读，非权威源）
    RULE: '/risk/rule'                                      // GET 阈值清单 / POST 变更下发（T-063；FR-CONFIG-5/6）
  },
  /** 未注册：仅登记路径，**禁止调用**（调用即 404；handler/ 下无 risk/trade 等模块目录）。 */
  NOT_REGISTERED: {
    // T-070 补登记：ApiRoutes RISK 路径表 10 路径中未注册方法对的 5 个（页面保留 Tab 框架 +
    // 「未注册」诚实提示，缺口只报不改——属 T-108/T-118/T-119 层③链或后续立号承接）。
    RISK_DASHBOARD: '/risk/dashboard',
    RISK_LIMIT_OPEN: '/risk/limit-open',
    RISK_FORCE_CLOSE_HINT: '/risk/force-close-hint',
    RISK_VIOLATION: '/risk/violation',
    RISK_POST_REPORT: '/risk/post-report',
    TRADE_ORDER: '/trade/order',
    // T-083 补登记：ACCT 路径表 12 路径中未注册方法对的 2 个（FR-ACCT-14 自检清单 /
    // FR-ACCT-9 凭证导出载点；handler/acct/ 下无对应 Handler——acct.js 保留 Tab 框架 +
    // 「未注册」诚实提示、零 API 调用，T-070 risk.js 同形态）。
    ACCT_SELFCHECK: '/acct/selfcheck',
    ACCT_EXPORT: '/acct/export'
  },
  // T-077/T-078：套期会计首批键（hedge-relation / effectiveness / rebalance 各 GET|POST）。
  // T-079/T-080/T-083：凭证与期间面 + 效果与上报面全量接齐——ACCT 键现覆盖 **10 路径 /
  // 17 (方法,路径) 对**（= BY_MODULE_ROUTES ACCT 全量）：静态 7（HEDGE_RELATION /
  // EFFECTIVENESS / REBALANCE / VOUCHER / PERIOD / EFFECT / DUAL_PNL_REPORT）
  // + 参数化函数 3（voucherReview / voucherReverse / periodClose）。
  // ⚠️ 订正（T-083，闭环 T-079 O-2）：旧注称「其余 8 条已声明路径未注册」系 T-079
  // 键先行时点的陈旧表述——voucher/period/effect/dual-pnl 均已随 T-079/T-080 注册，
  // 实际未注册的仅 **2 条**：selfcheck/export（FR-ACCT-14/FR-ACCT-9 载点，见 NOT_REGISTERED）。
  // 路径逐字取自 ApiRoutes.byModule().get("ACCT")；方法对取自 handler/acct/AcctRoutes.java。
  ACCT: {
    HEDGE_RELATION: '/acct/hedge-relation',   // GET 分页；POST 生成（三要素门禁 R-ACCT-1，缺任一要素 10012 阻断并列缺失项）
    EFFECTIVENESS: '/acct/effectiveness',     // GET 分页/监测复核清单(monitorOnly=true)/未评估提醒(unassessedPeriod=…)；POST 提交评估（三项定性观察点，Q-7）
    REBALANCE: '/acct/rebalance',             // GET 再平衡史(?hedgeRelationId=)；POST 执行再平衡（四类触发，Q-8；偏离值系统复核不采信客户端）
    VOUCHER: '/acct/voucher',                 // GET 分页列表 / 详情(?id=)；POST 草稿生成 / 草稿删除(action=DELETE_DRAFT)（R-ACCT-5；FR-ACCT-6/8/13）
    PERIOD: '/acct/period',                   // GET 期间分页 / 关账历史(?periodKey=)；POST 创建开放期间（FR-ACCT-12）
    EFFECT: '/acct/effect',                   // GET 套保效果视图(summary+details 同屏双栏)；POST 计算（FR-ACCT-5；R-ACCT-12 口径单一）
    DUAL_PNL_REPORT: '/acct/report/dual-pnl', // GET 上报记录查询(reportPeriod 必填)；POST 提交上报（两栏恒在 R-ACCT-4；FR-ACCT-10）
    voucherReview: (id) => '/acct/voucher/' + enc(id) + '/review',     // POST 凭证审核（R-ACCT-6 岗位分离；FR-ACCT-7）
    voucherReverse: (id) => '/acct/voucher/' + enc(id) + '/reverse',   // POST 凭证冲销（反向凭证 + 双人复核 DC-032；FR-ACCT-13）
    periodClose: (id) => '/acct/period/' + enc(id) + '/close'          // POST 关账/撤销关账（三前置 + 双人复核 R-ACCT-8；FR-ACCT-12）
  }
};

HG.routes = ROUTES;
export default ROUTES;
