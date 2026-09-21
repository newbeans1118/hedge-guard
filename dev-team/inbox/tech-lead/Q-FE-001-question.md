---
ticket: Q-FE-001
type: question
from: frontend-engineer
to: tech-lead
priority: P0
created: 2026-09-20T23:58:00+08:00
refs: [docs/ARCHITECTURE.md#3.15, docs/ARCHITECTURE.md#4.1.1, docs/ARCHITECTURE.md#8.8, java/CONTRACTS.md#1, java/src/main/java/com/hedgeguard/web/handler/spot/SpotExposureHandler.java, java/src/main/java/com/hedgeguard/web/handler/plan/PlanRoutes.java, java/src/main/java/com/hedgeguard/web/handler/config/SubAccountHandler.java, java/src/main/java/com/hedgeguard/web/RequestDispatcher.java, docs/TASKS.md#T-017, docs/TASKS.md#T-019, docs/TASKS.md#T-020]
---

## 事由

前端（`T-017` 基座 / `T-019` 现货与敞口 / `T-020` 套保计划）按 `T-ARCH-004` 交底施工时，
以「**已注册的 Java Handler**」为线上契约依据逐字对齐（`§5.2` 契约逐字对齐纪律）。

对齐过程中发现 **2 项阻塞**（前端无法自行消解）与 **若干字段级/端点级落差**。
按 `frontend-engineer.md` §8「不猜测、不先改后报」，一律以本工单请示，**不改后端、不改文档**。

> ⚠️ 交底邮件称「Java 侧装配尚未接线、接口调不通」，但实测 `handler/**` 下
> **AUTH/CONFIG/PRODUCT/SPOT/PLAN 的 Router 注册已就绪**（`…Routes.register(...)`），
> 且 `java/CONTRACTS.md` `:656`（2026-09-20 23:51）已记「HTTP/REST+DB 已接线」。
> 故前端**按已实现 Handler 对齐**（而非按未接线的假设）。

## 问题清单

| # | 级别 | 问题 | 证据 | 请求裁定 |
|---|------|------|------|---------|
| `Q-1` | **阻塞** `FR-SPOT-14` | `POST /spot/exposure` 的请求体要求提交方自备 `exposureQty` 与三分量（`contractUnexecutedQty`/`inventoryQty`/`productionPlanQty`），即**计算责任在调用方**。若由前端计算实货敞口，即违反 `T-ARCH-004` 硬约束「不得在前端做业务判定」及 `FR-SPOT-14`「**系统根据已录入的现货数据自动计算**」。 | `SpotExposureHandler.java:87-113`（`exposure.exposureQty = request.exposureQty`；三分量直接取请求值） | 前端应调用**哪个端点**触发「由已有现货数据（合同/库存/生产计划）自动计算敞口」？或确认 `POST /spot/exposure` 仅供内部/服务层调用、前端**不调用**？ |
| `Q-2` | **阻塞** `FR-PLAN-4` | `GET /plan`（列表）**未注册**（`PlanRoutes` 仅注册 `POST /plan` 与 `GET /plan/{id}`）。按 `RequestDispatcher` 规则，`GET /plan` 命中 `pathExists` → **405**。套保计划**列表页不可用**。 | `PlanRoutes.java:50-60`（无 `GET PlanHandler.PATH`）；`RequestDispatcher.java:83-89` | 补注册 `GET /plan`（含子账户/状态筛选与分页）？或给出替代列表口径？ |
| `Q-3` | 口径确认（**重要**） | 已实现契约的**线上字段名为 camelCase**（`subAccountId`/`productId`/`spotCategoryCode`/`exposureQty`…），与 `DATA_MODEL.md` 的 snake_case **列名**不同。§4.1.1 未给逐端点 DTO 字段清单。 | `SpotContractHandler.PurchaseRequest`、`PlanHandler.CreateRequest`、`SpotExposureHandler.ExposureView` 等 | 确认**前端 wire 以 camelCase 为准**（我已据此对齐）；若有「REST DTO 字段冻结清单」请公布，我据此复核。 |
| `Q-4` | 数据权限 `NFR-SEC-7` | 子账户切换器（`NFR-USE-5`）的数据源：`/auth/sub-account-perm` **未注册**；`GET /config/sub-account` 返回**全部启用**子账户（非按用户授权集）。软隔离要求「安全默认 = 空集」，切换器不应展示越权子账户。 | `SubAccountHandler.java:42-48`（`configService.listEnabled()`）；`AuthRoutes.java:32-36`（仅 login/logout） | 切换器应显示的子账户集合来自**哪个端点**（按用户授权过滤）？ |
| `Q-5` | 契约缺口 `FR-SPOT-15` | `GET /spot/exposure/{id}` 的 `ExposureView` **不含构成明细**（`t_exposure_detail`）；`FR-SPOT-15` 验收 1 要求「列出贡献该敞口的全部原始单据及数量，合计 = 敞口总量」。 | `SpotExposureHandler.java:224-311`（`ExposureView` 无明细字段） | 构成明细的查询端点/字段？ |
| `Q-6` | 契约缺口 `FR-PLAN-5` | `PlanView` **不含**套保比率计算过程（`t_hedge_ratio_calc`）、审批链、书面文件正文；`FR-PLAN-5` 要求详情展示上述内容。 | `PlanHandler.java:146-227` | 详情端点扩展或独立端点？ |
| `Q-7` | 结构登记 | 我在 §3.15 枚举之外**新增**了 3 个 additive 文件：`web/js/core/routes.js`（前端路径只读镜像，防拼串漂移）、`web/js/pages/login.js`（登录页逻辑；`auth.js` 保留为 AUTH 管理页）、`web/js/pages/_stub.js`（未开工模块的共享骨架）。 | — | 确认可接受（均为 additive、不改 §3.15 既有文件语义）？ |

## 证据 / 复现

```bash
# Q-1：计算责任在调用方
sed -n '87,113p' java/src/main/java/com/hedgeguard/web/handler/spot/SpotExposureHandler.java

# Q-2：GET /plan 未注册
grep -n 'router.register' java/src/main/java/com/hedgeguard/web/handler/plan/PlanRoutes.java

# Q-3：camelCase 字段名（示例）
grep -n 'public ' java/src/main/java/com/hedgeguard/web/handler/spot/SpotContractHandler.java

# Q-4：子账户列表 = 全部启用
sed -n '42,48p' java/src/main/java/com/hedgeguard/web/handler/config/SubAccountHandler.java
```

- 前端已按 camelCase 对齐并通过运行时冒烟（32 项核心 + 23 项页面渲染，0 失败；见 T-ARCH-004 处理记录）。

## 变更评估

- `Q-1`/`Q-2`：属**后端补端点**（新增路由，不改既有签名/路径）→ 对前端为兼容性增强；无需 `CH-xxx`（非破坏）。
  若裁定「前端不调用 `POST /spot/exposure`」，则前端**零改动**（我已不调用）。
- `Q-3`：若确认 camelCase 为线上口径，属**澄清**（前端已按此实现）；若另有冻结清单，我据其**返工**。
- `Q-4`/`Q-5`/`Q-6`：均属**新增端点/视图字段**（兼容性增强）。
- `Q-7`：结构登记，无代码风险。

## 回复要求

1. 请对 `Q-1`…`Q-7` 逐条给出结论（可引用 `CH-xxx` 或直接裁定）。
2. 答复到达前，我按如下**暂定口径**继续：`Q-1` = **前端不调用** `POST /spot/exposure`（页面显式说明 + 待裁定）；
   `Q-2` = 列表页**显式登记受阻**并提供 `GET /plan` 探测按钮留证；`Q-3` = 以 **camelCase 为准**；
   `Q-4` = 暂用 `GET /config/sub-account`（并在界面标注"可能含未授权子账户，越权由后端 10004 拒绝"）；
   `Q-5`/`Q-6` = 页面显式标注"待补"。任何裁定与暂定口径不符，我立即返工。
3. 我不修改 `java/**`、`docs/**`（除回写本人工单）与 `java/CONTRACTS.md`。

## 答复（技术负责人，2026-09-21）

> 本答复全部依据**当前工作区实测**（未采信工单自述）。取证时点锚：`ApiRoutes.java` `3dec526b…`、`PlanRoutes.java` `0a33c947…`、`PlanHandler.java` `5f591bc9…`、`AuthRoutes.java` `e4fd608e…`、`ARCHITECTURE.md` `b4108abf…`（v1.5.9）——**引用一律以实时 `md5sum` 为准**。

| # | 结论 | 依据 / 取证（`文件:行号` + 命令 → 输出） | 承载 / 待落笔 |
|---|------|------------------------------------------|----------------|
| `Q-1` | **确认为服务端缺口**（非前端责任）；你「不调用 `POST /spot/exposure`」的暂定口径**正确** | `sed -n '100,111p' java/src/main/java/com/hedgeguard/web/handler/spot/SpotExposureHandler.java` → 三分量（`contractUnexecutedQty`/`inventoryQty`/`productionPlanQty`）与 `exposureQty` **全部取自 `request`**；`SpotService.java:545 computeExposure` 仅校验 `Σ分量 = 合计`（`:578-585`，不等即拒写），**不读合同/库存/生产数据**；`grep -n 'public …(' SpotService.java` 无「由已录入现货数据推导分量」入口 ⇒ `FR-SPOT-14`「系统自动计算」未落实 | 承载 **`T-014`**（`FR-SPOT-13~18`，状态「已完成，待独立复核」）。**待落笔**：`SpotService` 增「由已录入现货数据推导三分量」入口 + 端点语义 → 属主 `#java2`（`T-014`） |
| `Q-2` | **已解决** | `grep -n 'router.register("GET", PlanHandler.PATH' java/…/plan/PlanRoutes.java` → **`:51`**；`BootstrapAssemblyTest.java:136` → `assertEquals(48, assembly.routeCount(), "…46（AUTH2/CONFIG4/PRODUCT13/SPOT10/PLAN17）+ ping 2")`；现行路由口径 = **48 个 `(方法,路径)` 对 / 37 路径** | 承载 = **`GET /plan` 注册**（随 `Q-JAVA-001` 补入）+ **`T-100`**（DONE，隔离）。**待落笔（前端侧）**：更新 `web/js/core/routes.js`（`:14` 仍写「47 对」、`:33`/`:75` 仍把 `GET /plan` 列为「未注册」）→ 属主 `frontend-engineer` |
| `Q-3` | **技术负责人裁定：wire 字段名以 camelCase 为准** | 依据 `java/CONTRACTS.md:70`（§1.1 只冻结**信封/分页/幂等**，**无**逐端点 DTO 字段清单）；已实现 Handler 即线上契约（§5.2 契约逐字对齐）；`SpotExposureHandler.ExposureView:224-311`、`PlanHandler.CreateRequest:201-238` 全 camelCase。`DATA_MODEL.md` 的 snake_case 是**列名**，不决定 wire | **无待落笔**（纯澄清）。若需「逐端点 REST DTO 字段冻结清单」，属新增契约，**不在本票范围** |
| `Q-4` | **未解决**（= `T-010` 缺陷）；并裁定**切换器数据源** | `sed -n '71,74p' java/…/web/ApiRoutes.java` → AUTH 声明 **10** 条；`sed -n '32,36p' java/…/handler/auth/AuthRoutes.java` → 仅注册 **2**（login/logout）；**差集 8 条**含 `/auth/sub-account-perm`；`SubAccountHandler.java:44` → `configService.listEnabled()`（**全部启用**，非按授权）。**裁定**：切换器集合 = 调用者可见子账户集，取 `AuthService.visibleSubAccountIds(userId)`（`AuthService.java:528`，**含哨兵 `0` ⇒ 不加过滤**语义）；**不得**用 `DmUserSubAccountPermDao.scopeOf`（不读 `perm_type`）。**当前无端点暴露该集合** | 承载 **`T-010`**（IN_PROGRESS；`BOARD.md:145` 明列 8 端点、含本端点、「不拆号」）。**过渡口径**：暂用 `GET /config/sub-account` 并显式标注（越权由 `10004` 兜底）**可接受**，`T-010` 落地后切回。**待落笔**：`AuthRoutes.register` 增 `GET /auth/sub-account-perm`（handler 走 `visibleSubAccountIds`）→ 属主 `#java2`（`T-010`） |
| `Q-5` | **未解决** | `sed -n '224,311p' SpotExposureHandler.java` → `ExposureView` **无明细字段**；handler `:69-71` → `spotService.getExposure(parseId(id))`；`SpotService.getExposure(long):515` **无 operator 过滤** | 承载 **`T-014`**（`FR-SPOT-15`）。**待落笔**：`ExposureView` 扩构成明细（`t_exposure_detail`）+ `GET /spot/exposure/{id}` 接隔离 → 属主 `#java2`（`T-014`）。**附带（本席另发现，已另报）**：SPOT 读取面整体无子账户隔离（`getExposure`/`listPendingHedge(int):525` 无 operator）⇒ **无任务承载**，立项由编排者裁定 |
| `Q-6` | **未解决** | `sed -n '251,332p' java/…/plan/PlanHandler.java` → `PlanView` 仅计划字段 + `bindings`，**无**套保比率计算过程 / 审批链 / 书面文件正文 | 承载 **`T-016`**（IN_PROGRESS，`FR-PLAN-5`）。**待落笔**：`PlanView` 扩字段或独立端点 → 属主 `#java2`（`T-016`） |
| `Q-7` | **技术负责人裁定：接受** | `ls web/js/core/routes.js web/js/pages/login.js web/js/pages/_stub.js` → 三件**实存**；`ARCHITECTURE.md:557-559`（§3.15 结构清单）**未列**；`tools/arch-check/check_frontend.sh` 判据只查 `package.json`/`node_modules`/`dist`/`*.bundle.js`/`*.min.js` + `jdbc`/`127.0.0.1:端口`/`dsn`，**不校验文件清单** ⇒ 不破坏 `E6` | **接受**（additive、不改 §3.15 既有文件语义）。**待落笔**：将 3 文件补登进 `docs/ARCHITECTURE.md` §3.15 结构清单（`:557-559`）→ 属主 `tech-lead` |

**总结**：7 项中 **1 项已解决**（`Q-2`）、**3 项口径裁定/澄清**（`Q-3` wire=camelCase、`Q-7` 接受 additive）、**4 项为已承载的后续缺口**（`Q-1`/`Q-5`→`T-014`、`Q-4`→`T-010`、`Q-6`→`T-016`），**无一项需前端自行消解**。前端在答复前的暂定口径**均无需返工**。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 23:58 | frontend-engineer | 投递 7 项（2 阻塞 + 1 口径确认 + 3 契约缺口 + 1 结构登记） | 待技术负责人裁定 |
| 2026-09-21T08:15:15+08:00 | tech-lead（tl-triage） | 答复并关闭（7 项逐条） | `Q-2` 已解决；`Q-3`/`Q-7` 技术负责人裁定；`Q-1`/`Q-5`→`T-014`、`Q-4`→`T-010`、`Q-6`→`T-016`（均已承载）；前端暂定口径无需返工 |

## 状态

- **当前状态**：CLOSED
- **终态确认人**：tech-lead（tl-triage）
- **终态时间**：2026-09-21T08:15:15+08:00
