---
ticket: Q-JAVA-001
type: question
from: backend-engineer#java
to: tech-lead
priority: P0
created: 2026-09-21T00:22:00+08:00
refs: [docs/REQUIREMENTS/PLAN.md#FR-PLAN-4, docs/ARCHITECTURE.md#4.1.1, docs/DATA_MODEL.md#2.5, java/CONTRACTS.md#1.1, java/CONTRACTS.md#7, java/src/main/java/com/hedgeguard/web/handler/plan/PlanRoutes.java, java/src/main/java/com/hedgeguard/web/handler/plan/PlanHandler.java, java/src/main/java/com/hedgeguard/service/PlanService.java, java/src/main/java/com/hedgeguard/web/handler/product/ProductHandler.java]
---

## 事由

team-lead 派我补 `GET /plan`（契约缺口：`PlanRoutes` 原只注册 `POST /plan` 与 `GET /plan/{id}`，
前端实测 405，`FR-PLAN-4` 列表页不可用）。这也是前端 `Q-FE-001` 的 `Q-2`。

**已完成（无歧义部分，已交隔离副本全量验证）**：
- 注册 `GET /api/v1/plan`（`PlanRoutes.java:51`）；
- `PlanHandler` 增加列表分支（`id` 路径参数缺省即走列表）；`PlanService.listPlans(PageQuery, PlanFilter)`
  以 `hedgePlanDao.findPage` 实现筛选 + 分页 + 排序（默认 `created_time` 倒序、排序字段白名单、
  `size ∈ {10,20,50,100}` 默认 20）；响应 `data = {items,total,page,size}`，每行字段与 `GET /plan/{id}` 的
  `PlanView` **逐字一致**（含 `executedQty`/`orderedQty`/`quotaOccupied`）。

**以下 4 项以本工单请示，未自行选定**（按 `backend-engineer.md` §8 与 team-lead 指令）：

## 问题清单

| # | 级别 | 问题 | 证据 | 请求裁定 |
|---|------|------|------|---------|
| `Q-1` | **P0 阻塞** | `FR-PLAN-4` 处理规则要求「**仅返回有权限的子账户范围**」（验收 4：子账户 A 用户看不到 B 的计划）。实现它需 `PlanService` 取得「调用者可见子账户集」（即注入 `UserSubAccountPermDao`）⇒ **改 `PlanService` 构造签名** ⇒ 必同步改 `Bootstrap` 装配。**而本轮任务的允许改动边界不含 `web/Bootstrap.java`**（仅 `web/handler/plan/**`、`service/PlanService.java`、`dao/**`、`test/**`）。 | `CONTRACTS.md` §7「列表查询：注入 `SubAccountScope`（`userSubAccountPermDao.scopeOf(userId,"sub_account_id")`）」；`PlanService` 现无该依赖；`Bootstrap.java:140` 为唯一装配点 | 二选一：**(a)** 批准扩边界：由我向 `PlanService` 注入 `UserSubAccountPermDao`（+ 同步 `Bootstrap`），本端点随之完成越权过滤；**(b)** 维持边界，把「PLAN 读面用户级 scope」列为**模块级治理项**（现状：`getPlan` 亦无 scope，故 `list` 与之一致），并在治理落地前**限制该端点不得对外暴露**。**请裁定 (a)/(b) 及若选 (a) 的装配归属人。** |
| `Q-2` | 口径确认 | 分页信封：`CONTRACTS.md` §1.1 **冻结**「`?page&size&sort`；响应 `data.items/total/page/size`」；但**已实现的列表端点**（如 `GET /product`）返回**裸数组**、**无分页**（`ProductHandler.java:53-57`）。两者不一致。 | `CONTRACTS.md:70`；`ProductHandler.java:53-57` | `GET /plan` 我按 **§1.1 冻结口径**实现为 `PageData`（`items/total/page/size`）。请确认：**(a)** 以 §1.1 为准（则 `GET /product` 等裸数组端点为**既有偏差**，需另立整改）；或 **(b)** 以既有实现为准（则我把 `GET /plan` 改为裸数组）。 |
| `Q-3` | 口径确认 | `FR-PLAN-4` 验收 3「以**审批人身份**登录，列表**默认过滤出待其审批**的计划」——需判定"审批人角色/待我审批"的语义（`t_plan_approval` 未决审批 + 角色），当前 `PlanService` 无相关能力。 | `PLAN.md` FR-PLAN-4 处理规则与验收 3 | 本轮**未实现**该默认过滤（保持显式筛选）。请裁定：**(a)** 本轮实现（需指定判定来源：角色表 / `t_plan_approval` 未决行）；**(b)** 列为后续任务。 |
| `Q-4` | 口径确认（次要） | `sort` 字段命名：我按 **DB 列名（snake_case）** 设白名单（`created_time`/`plan_no`/…），因 DAO 将 `sortField` 直拼 `ORDER BY`。`§1.1` 只说 `field,asc` 未定大小写。 | `PageQuery.java:61-82`；`AbstractDmDao.findPage` | 请确认 `sort` 取**列名 snake_case**（我的实现）还是 camelCase（若是后者，需在 L4 映射后再入 DAO，请指定映射责任方）。 |

## 我的处置

- `Q-1`：**未做越权过滤**，并在 `PlanService.listPlans` javadoc 显式标注为待裁定项；
  在裁定前**不建议对外暴露该端点**（团队已知悉）。
- `Q-2`：按 §1.1 冻结口径实现（`PageData`）；如裁定 (b) 我改回裸数组（一行改动）。
- `Q-3`：未实现审批人默认过滤。
- `Q-4`：按 snake_case 白名单实现。

> 全量用例目前 **290/0/0/0**（隔离副本，2026-09-21T00:20:54+08:00），含本端点的注册守卫与筛选/白名单用例。

## 答复（技术负责人，2026-09-21）

> 本答复全部依据**当前工作区实测**（未采信工单自述）。取证时点锚：`PlanHandler.java` `5f591bc9…`、`PlanRoutes.java` `0a33c947…`、`Bootstrap.java`、`CONTRACTS.md` `da27778a…`、`ARCHITECTURE.md` `b4108abf…`（v1.5.9）——**引用一律以实时 `md5sum` 为准**。

| # | 结论 | 依据 / 取证（`文件:行号` + 命令 → 输出） | 承载 / 待落笔 |
|---|------|------------------------------------------|----------------|
| `Q-1` | **已解决（选 (a)，且已落地）** | `PlanHandler.java:151` → `planService.listPlans(ctx.currentUserId(), query, filter)`（**调用者身份已入参**）；`PlanService.listPlans` 经 `visibleSubAccountIds` + `findPageScoped`（`App` 装配见下）；`Bootstrap.java:216-217` → `authService` **同时作 `SubAccountVisibility` 注入** | 承载 **`T-100`（DONE）**。**无需**再批复改动边界，**无需**再指定装配归属人（`Bootstrap` 已由 `#java` 实例同步落） |
| `Q-2` | **已解决（选 (a)）** | `PlanHandler.java:156` → `new PageData<>(items, total, page, size)`；`java/CONTRACTS.md:70`（§1.1）**冻结**「响应 `data.items/total/page/size`」 | `GET /plan` **保持 `PageData`**（正确）。`GET /product` 等**裸数组端点为既有偏差**（`ProductHandler.java:53-57`）⇒ 另行登记（**无任务承载**，立项由编排者裁定），**不在本端点返工** |
| `Q-3` | **本轮确认不实现，维持显式筛选** | `sed -n '131,158p' java/…/plan/PlanHandler.java` → 列表分支**无**审批人默认过滤；`PlanService.PlanFilter` 无「待我审批」维度 | 语义（「审批人角色」取**角色表** vs `t_plan_approval` **未决审批行**）**须先由技术负责人/产品经理裁定**，实现归 **`T-016`**（`FR-PLAN-4` 验收 3 属其 `FR-PLAN-1~5` 范围）。**待落笔**：`PlanService` 增该维度 + `PlanHandler` 默认过滤 → 属主 `#java2`（`T-016`） |
| `Q-4` | **技术负责人裁定：`sort` 的 `field` 取 DB 列名（snake_case），且须在该端点白名单内** | 依据 `AbstractDmDao.findPage:161` → `" ORDER BY " + query.sortField() + " " + query.direction()`（**`sortField` 直拼 SQL**）；`PlanService.LIST_SORT_FIELDS:93-95`（snake_case 白名单）+ `DEFAULT_SORT_FIELD:98`；`PageQuery.java:66` 正则 `[A-Za-z_][A-Za-z0-9_]*`（**camelCase 亦放行** ⇒ 必须靠白名单 fail-closed）；`ARCHITECTURE.md:582`（§4.1.1 `sort=field,asc` 未定大小写） | **你的 snake_case 实现正确**。camelCase 传入会命中白名单校验（`PlanService.listPlans`）返回 `10001`，**不会静默拼错列**。**待落笔**：把「`sort` 值 = DB 列名 snake_case、须在白名单内」口径与各端点白名单补进契约 → 属主 `#java`（`java/CONTRACTS.md` §1.1）/ `tech-lead`（`docs/ARCHITECTURE.md` §4.1.1） |

**总结**：4 项中 **2 项已解决**（`Q-1`/`Q-2`，均有既有任务承载）、**1 项口径裁定**（`Q-4`）、**1 项归口**（`Q-3`→`T-016`）。你的实现（越权过滤 + `PageData` + snake_case 白名单 + 缺省 `created_time,desc`）**与裁定一致，无需返工**。全量 290/0/0/0 自评证据有效；其中 `Q-1` 隔离已由 **`T-100` 独立复核**覆盖，无需你自证。

## 处理记录

| 时间 | 处理人 | 动作 | 结论摘要 |
|------|--------|------|----------|
| 2026-09-21 00:22 | backend-engineer#java | 投递 4 项（`Q-1` P0 阻塞 + 2 口径确认 + 1 次要口径） | 待技术负责人裁定 |
| 2026-09-21T08:15:15+08:00 | tech-lead（tl-triage） | 答复并关闭（4 项逐条） | `Q-1`/`Q-2` **已解决**（`T-100`/`T-103` 承载）；`Q-4` 技术负责人裁定 snake_case；`Q-3` 归 `T-016`；实现与裁定一致、无需返工 |

## 状态

- **当前状态**：CLOSED
- **终态确认人**：tech-lead（tl-triage）
- **终态时间**：2026-09-21T08:15:15+08:00
