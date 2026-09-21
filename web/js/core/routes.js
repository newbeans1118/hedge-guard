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
 * 铺页面只按**逐条路径**，不按总数。若确须引用规模，统一写：**「47 个 (方法, 路径) 对 / 37 个路径」**并标明口径：
 *   分模块（按"对"）AUTH 2 / CONFIG 4 / **PRODUCT 13** / SPOT 10 / PLAN 16 = 45，另加 `ping` 的 `GET`+`HEAD` 2 对 ⇒ 47。
 *   （`PLAN` 的 16 对含 2 处循环注册：`PlanActionHandler.Action.values()` 8 条 + `PlanRatioHandler.Method.values()` 3 条。）
 * ⚠️ **PRODUCT 的路径数为 9、按"对"为 13**（旧口径"6"是 `ModuleRoutesConsistencyTest` 的**覆盖下限清单**，非全量，勿用）。
 * ⚠️ **不要写 38 / 29**（已作废）。
 *
 * ## 已注册（M1，实测 `…Routes.register(...)`）
 *   AUTH    POST /auth/login、POST /auth/logout
 *   CONFIG  GET|PUT /config/item、GET|POST /config/sub-account
 *   PRODUCT GET|POST /product、GET /product/{id}、POST /product/whitelist、GET|POST /product/quota、
 *           GET|POST /product/{id}/contracts、GET|POST /product/mapping、GET /product/change-log、
 *           POST /product/import、GET /product/export
 *   SPOT    POST /spot/purchase、POST /spot/sales、POST /spot/inventory、POST /spot/wip、
 *           POST|GET /spot/price、POST /spot/exposure、GET /spot/exposure、GET /spot/exposure/{id}、
 *           POST /spot/exposure/recalc
 *   PLAN    POST /plan、GET /plan/{id}、POST /plan/{id}/doc、
 *           POST /plan/{id}/{submit|approve|reject|withdraw|freeze|unfreeze|terminate|extend}、
 *           GET|POST /plan/{id}/trade-plans、POST /plan/ratio/{simple|minvar|custom}
 * ## 未注册（前端**不得**调用；见 Q-FE-001）
 *   GET /plan（列表）、GET /auth/user、GET /auth/sub-account-perm、/risk/dashboard、/trade/**、其余模块
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
    LOGOUT: '/auth/logout'
  },
  CONFIG: {
    ITEM: '/config/item',
    SUB_ACCOUNT: '/config/sub-account'
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
    INVENTORY: '/spot/inventory',
    WIP: '/spot/wip',
    PRICE: '/spot/price',
    EXPOSURE: '/spot/exposure',
    EXPOSURE_RECALC: '/spot/exposure/recalc',
    exposureDetail: (id) => '/spot/exposure/' + enc(id)
  },
  PLAN: {
    CREATE: '/plan',                              // POST only（GET 列表未注册）
    detail: (id) => '/plan/' + enc(id),
    doc: (id) => '/plan/' + enc(id) + '/doc',
    tradePlans: (id) => '/plan/' + enc(id) + '/trade-plans',
    action: (id, action) => '/plan/' + enc(id) + '/' + enc(action),
    ratio: (method) => '/plan/ratio/' + enc(method)   // simple | minvar | custom
  },
  /** 未注册：仅登记路径，**禁止调用**（调用即 404/405）。 */
  NOT_REGISTERED: {
    PLAN_LIST: '/plan',
    USER: '/auth/user',
    SUB_ACCOUNT_PERM: '/auth/sub-account-perm',
    RISK_DASHBOARD: '/risk/dashboard',
    TRADE_ORDER: '/trade/order'
  }
};

HG.routes = ROUTES;
export default ROUTES;
