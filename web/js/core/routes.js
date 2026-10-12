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
  /** 未注册：仅登记路径，**禁止调用**（调用即 404；handler/ 下无 risk/trade 等模块目录）。 */
  NOT_REGISTERED: {
    RISK_DASHBOARD: '/risk/dashboard',
    TRADE_ORDER: '/trade/order'
  }
};

HG.routes = ROUTES;
export default ROUTES;
