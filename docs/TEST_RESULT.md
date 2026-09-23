# 测试执行记录

> 负责人：测试工程师 ｜ 阶段：S4
> 说明：本记录为 **`D-19`「`Bootstrap` 受配置保护装配」的专项独立复核**（非 `TEST_PLAN` 模块用例驱动）。
> 用例编号一栏用 `V-<n>` 表示本次独立验证项——`D-19` 未登记在 `TEST_PLAN/*.md` 的模块用例清单内，
> 故不给 `TC-*` 编号（避免与测试经理的用例清单撞号）。

## 1. 执行概况

| 项 | 内容 |
|----|------|
| 代码版本 | 仓库**无 git**（`git rev-parse` → `fatal: not a git repository`）。以文件 md5 + mtime 锚定：`Bootstrap.java` md5 `db105f667c018167b48c96f6e3ef5bd9`（mtime 23:44:17）；`*Routes.java` mtime 均 ≤ 23:11:39（早于产出方取样时刻 23:48） |
| 构建时间 | 隔离副本创建 2026-09-20 23:50:13 +0800 → `mvn` 结束 **2026-09-20T23:51:40+08:00** |
| 测试环境 | Linux / JDK 11 / `maven-surefire-plugin` 3.2.5 / 离线（`-o`）/ **无 DM8 实例、无 DM 驱动** |
| 执行人 | 测试工程师（`te-verify-d19`） |
| 执行时间 | 2026-09-20 23:50–23:54 +0800 |
| 执行范围 | 隔离副本 `/tmp/v19/java`（源 `/root/myspace/hedge-guard/java`，含 `docs` 符号链接）；`D-19` 装配专项 |
| 复检（`GAP-1` 修复确认） | 2026-09-21 00:04–00:09 +0800；基线副本 `/tmp/v19b/java` + 反退化变异副本 `/tmp/v19c/java`；`Bootstrap.java` md5 全程 `db105f667c018167b48c96f6e3ef5bd9`（**未改动共享源码**） |

## 2. 用例执行表

| 用例编号 | 验证项 | 优先级 | 状态 | 实际结果 | 关联缺陷 |
|---------|--------|--------|------|---------|---------|
| V-1 | 基线复取（隔离副本 `mvn -B -o clean test`） | P0 | 通过 | `Tests run: 283, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`，**无排除** | — |
| V-2 | 装配接线确在 `databaseConfigured()` 真分支内 | P0 | 通过 | `Bootstrap.java:218–222` 五行在 `registerBusinessRoutes`（`:188`）内，该方法在 `:140` 被调用，落在 `if (config.databaseConfigured())`（`:127–147`）内 | — |
| V-3 | 正分支**真实**注册业务路由（直接证据） | P0 | 通过 | 自建假 JDBC 驱动探针：`databaseConfigured=true` → `routeCount=47`、`pool!=null`、`factory!=null`；`Bootstrap` 自身运行期日志打印「路由总数=47」 | — |
| V-4 | 口令零落盘（`HG_DB_PASSWORD` 未设 → `null`；含 `password` 键被拒） | P0 | 通过 | 环境确无 `HG_DB_*`；`Config.databasePassword()` 走 `secret()` 返回 `null`；`rejectSecrets` 命中 `password` 抛异常（用例已跑，0 skipped） | — |
| V-5 | 路由数与分布三口径 + 契约一致性 | P0 | 通过（**口径与产出方自述不符**） | 实测 47 条（含 ping）/ 37 路径；45 条业务路由 100% ∈ `ApiRoutes`、0 重复；见 §4 | — |
| V-6 | `WsServer` 未接线、无编造默认端口 | P1 | 通过 | `Bootstrap` 无 `WsServer` 引用；`Config` 无 WS 端口常量/默认值（仅注释提到键名） | — |
| V-7 | `GAP-1` 修复独立确认（基线/断言强度/反退化/`E5`） | P0 | 通过 | 基线 **284/0/0/0**；`BootstrapAssemblyTest:136` 为精确等值断言；**独立变异 `Bootstrap:140` → 用例红（`expected:<47> but was:<2>`）**；`E5` 扫描 0 命中、无新增 | — |

> 状态取值：**通过 / 失败 / 阻塞 / 未执行**。

## 3. 缺陷清单

**无真实缺陷（代码 / 契约错误 0）。** 发现的唯一不符属「产出方自述数字不实」（证据层），
且**未落入任何定版制品**（`TASKS.md` / `BOARD.md` / `CONTRACTS.md` 均无该数字），故不构成代码/契约缺陷，不开 `BUG` 单。

| 缺陷编号 | 标题 | 级别 | 责任人 | 状态 | 提交时间 | 复检结果 |
|---------|------|------|--------|------|---------|---------|
| —（无） | — | — | — | — | — | — |

## 4. 探索性测试发现

| 编号 | 发现内容 | 是否建缺陷单 |
|------|---------|-------------|
| EXP-1 | **产出方自述「路由总数 38 = 2 + 36（…PLAN 7）」不实**：实测 **47**（2 ping + 45 业务），`PLAN` 实为 **16**（5 定长 + 8 动作 + 3 比率），非 7。根因：产出方把 `router.register` **调用行数**当成了**路由数**——`PlanRoutes` 的 2 处循环调用（`:53–55` 8 个动作、`:58–60` 3 个比率）由 2 个调用点展开出 11 条路由（净 +9）。`grep` 口径 36 调用行 ↔ 45 业务路由的差即此 +9。**代码本身正确**（`Bootstrap` 运行期日志自动打印真实值 47）。<br>**证据等级**：此为**自述值与实测值的差值**（沟通/口径问题），**非代码缺陷**——代码、契约、定版制品均正确。 | 否（经编排者裁定：无物可修；已在 `TEST_PLAN/INDEX.md:136` 记为更正后口径 47 对 / 37 路径） |
| EXP-2 | 产出方 6 个 `BootstrapAssemblyTest` 中，「已配 DB」正分支仅被间接覆盖（异常反证），**未触达路由注册** → **已升级为可跟踪测试缺口 `GAP-1`**，详见下节。 | 否（转 `GAP-1`） |
| EXP-3 | `Bootstrap` 口令 `char[]` 清零（`NFR-SEC-1`）：`:128–137` 逻辑为「取到即交工厂、`finally` 内 `Arrays.fill(password,'\0')`」，工厂在 `:43` 自持一份 `Arrays.copyOf`。**此点无法被单测证明**（`databasePassword()` 只读进程环境变量，测试进程无法在运行期设置 `HG_DB_PASSWORD`；未设时返回 `null` 根本无数组可清）——**属静态审阅结论，非实测结论**。 | 否 |

### 测试缺口（TEST GAP）

| 编号 | 缺口 | 后果（可具体验证） | 证据 | 状态 |
|------|------|------------------|------|------|
| **GAP-1** | **`D-19`「已配 `hg.db.url`」正分支无仓库内直接断言**：产出方用例 `BootstrapAssemblyTest#configuredDatabaseEntersAssemblyBranch` 只断言「抛 `IllegalStateException`」，而该异常在 `Bootstrap.java:138`（`DataSourcePool` 构造预建连接）即抛出，**早于 `:140` 的 `registerBusinessRoutes`** —— 用例在此返回，**从不触达路由注册**。 | **删除或短路 `Bootstrap.java:140` 的 `registerBusinessRoutes(router, tx, sessionStore)` 调用后，`BootstrapAssemblyTest` 全部 6 用例仍然全绿**（用例 ④ 只验「抛异常」，异常在 `:138` 已产生；用例 ①②③⑤⑥ 均走无 DB 路径或仅验配置映射/`close`）→ **受配置保护装配失效、业务端点静默消失，CI 无告警**。 | 本人独立探针（假 JDBC Driver，`/tmp`，不改源码）实测：`databaseConfigured=true → databaseAssembled=true / routeCount=47 / pool!=null / factory!=null`，且 `Bootstrap` 自身运行期日志打印「路由总数=47」（证明正分支本会注册 45 业务路由 + 2 ping）。 | ✅ **已关闭（2026-09-21 00:09）**：`backend-engineer#java` 已补仓库内用例 `BootstrapAssemblyTest#configuredDatabaseRegistersAllBusinessRoutes`（`:120–155`；`:136` = `assertEquals(47, assembly.routeCount(), …)` 精确等值）+ `FakeJdbcDriver.java`（假驱动，`src/test` 零依赖）；本人**独立确认通过**（见下「`GAP-1` 关闭依据」） |

> 缺口与缺陷的区别：`GAP-1` **不是**代码错误（代码行为正确，经 V-3 直接实测），而是**回归保护缺失**——现有测试无法在该关键路径被破坏时报警。

#### `GAP-1` 关闭依据（`te-verify-d19` 独立验证，2026-09-21 00:04–00:09 +0800）

**① 基线复取**：隔离副本 `/tmp/v19b/java`，`mvn -B -o clean test` → **`Tests run: 284, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`**（`Finished at 2026-09-21T00:06:27+08:00`）。**无排除**（`pom.xml` surefire 段仅 `argLine`）。`BootstrapAssemblyTest` 由 6 → **7** 用例（新用例已跑、通过）。
**② 断言强度核验**：读 `BootstrapAssemblyTest.java` 原文，`:136` = `assertEquals(47, assembly.routeCount(), "业务路由必须全部注册：45（AUTH2/CONFIG4/PRODUCT13/SPOT10/PLAN16）+ ping 2")` —— **精确等值断言，非下界**（无 `>=`/`assertTrue(>N)`）。断言对象 `assembly.routeCount()` 是**真算值**（`Bootstrap.java:150` `router.routes().size()`），非恒真表达式；另含 `assertTrue(databaseAssembled())`、`assertNotNull(pool/factory)`、及两条 `assertNotEquals(404, …)` 实路径抽查。
**③ 反退化独立验证（关键）**：我在**独立副本** `/tmp/v19c/java` 把 `Bootstrap.java:140` 的 `registerBusinessRoutes(router, tx, sessionStore);` **短路**（`md5 db105f66… → 3ed3e1c7…`），跑 `mvn -B -o test -Dtest=BootstrapAssemblyTest#configuredDatabaseRegistersAllBusinessRoutes` → **变红**：
```
org.opentest4j.AssertionFailedError: 业务路由必须全部注册：45（…）+ ping 2 ==> expected: <47> but was: <2>
    at BootstrapAssemblyTest.configuredDatabaseRegistersAllBusinessRoutes:136
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0   BUILD FAILURE
```
→ 证明该用例确实**挡住了「删/短路 `registerBusinessRoutes`」的回归**。随后恢复：副本与**共享源码** `Bootstrap.java` md5 均回到基线 **`db105f667c018167b48c96f6e3ef5bd9`**。
> ⚠️ 说明：为遵守「测试席不改产品代码」与单写者纪律，**变异与恢复全程在隔离副本进行，共享源码从未被改动**（前后 md5 相同即证）。此做法比"改共享文件再还原"更强——共享制品零风险。
**④ `E5` 影响面**：`bash tools/arch-check/scan_java_sql.sh` → **0 命中 / 退出码 0**（`E5 通过`）。新增 `FakeJdbcDriver.java` / `BootstrapAssemblyTest.java` **无新增命中**：其 `DriverManager.registerDriver(`（`:36`）与 `jdbc:dm://`（`:32`、`:121`）均落在 `src/test/**`，由判据 ②a2 / ②b「仅适用非 src/test 生产代码」豁免；`①/②a/②c`（适用全仓）不匹配它们。
> 注：扫描脚本本轮已被修订至 **v1.5.6**（`scan_java_sql.sh` mtime `09-21 00:01:00`，头部记 `FakeJdbcDriver` 为 ②a2 分域动因），故"修复前 5 处"的**旧宽口径**（`jdbc:`/裸 `drivermanager` 适用全仓）在当前脚本下已不可复现；按旧宽口径，新文件贡献 3 行（`FakeJdbcDriver:32/36`、`BootstrapAssemblyTest:121`），**全部已被 v1.5.6 明文豁免**，属判据收紧、非代码新增外泄。

## 5. 度量数据

| 指标 | 数值 |
|------|------|
| 计划验证项数 | 6（V-1 ~ V-6） |
| 已执行验证项数 | 6 |
| 执行率 | 100% |
| 通过数 / 通过率 | 6 / 100%（V-5 结论为「通过 + 输出口径与自述不符」，见 §4 EXP-1） |
| 失败数 | 0 |
| 阻塞数 | 0 |
| 未执行数 | 0 |
| 缺陷总数 | 0（真实缺陷）；证据不实 1（EXP-1，经裁定未开单） |
| P0 / P1 / P2 / P3 | 0 / 0 / 0 / 0 |
| 测试缺口（GAP） | **1 条：已关闭 1 / 开放 0**（`GAP-1` 于 2026-09-21 00:09 独立确认后关闭） |

补充（基线量）：首轮隔离副本（`D-19` 交付时）全量 `mvn -B -o clean test` = **283 用例全绿**（`Tests run: 283, Failures: 0, Errors: 0, Skipped: 0`）；`GAP-1` 修复后（2026-09-21 00:06）复取 = **284 用例全绿**。
`D-19` 相关四类：`BootstrapAssemblyTest` **6 → 7**（`GAP-1` 修复新增 1 条）/ `AuthRoleConflictDaoContractTest` 3 / `SensitiveLoggingAndConfigTest` 12（含 `D-19` 新增 2）/ `ModuleRoutesConsistencyTest` 2，均 0 失败 0 跳过。

### 缺陷按模块分布

| 模块代号 | 缺陷数 |
|---------|--------|
| —（无） | 0 |

## 6. 覆盖核对

`D-19` 不作为 `TEST_PLAN` 模块用例登记，故此处核对本次复核对 `D-19` 自身断言与相关合规项的覆盖：

| 断言 / 需求项 | 验证项 | 实际执行 | 覆盖判定 |
|--------------|--------|---------|---------|
| 产出方：隔离副本 283/0/0/0 无排除 | V-1 | ✅ | 已覆盖（复取一致） |
| 产出方：`Bootstrap:218–222` 在 `databaseConfigured()` 真分支内 | V-2 | ✅ | 已覆盖 |
| `D-19` 正分支接线有效 | V-3 / V-7 | ✅ | 已覆盖（本次探针直接实测；`GAP-1` 修复后仓库内已有等价用例 `BootstrapAssemblyTest#configuredDatabaseRegistersAllBusinessRoutes`，经 `V-7` 独立确认） |
| `NFR-SEC-1` / 附录 B 红线 8（口令零落盘） | V-4 / EXP-3 | ✅ / ⚠️ | 已覆盖（配置层实测；`char[]` 清零为静态审阅） |
| `CFG-31 hg.db.url` 开关口径 | V-2 / V-3 | ✅ | 已覆盖 |
| 冻结路径清单 `ApiRoutes` ⊆ 校验 + 无重复注册 | V-5 | ✅ | 已覆盖（独立复算） |
| `CFG-32` WS 端口无实现值 → 不接线、不编造 | V-6 | ✅ | 已覆盖 |

**未覆盖项**：

| 需求编号 | 未覆盖原因 | 处理建议 |
|---------|-----------|---------|
| `WsServer` 实机接线（`T-040` / `A-2`） | 非 `D-19` 范围（`CFG-32 hg.web.ws.port` 无实现值） | 归 `T-040`，`D-19` 无需处理 |
| 真实 DM8 端到端（`NFR-DEPLOY-5` 实机连通） | 环境无 DM8 实例、无 DM 驱动 | 环境限制；已在 `CONTRACTS.md` `D-1` 登记 |

## 7. 结论

- **是否达到准出标准**：就 `D-19` 代码与契约而言——**是**（`GAP-1` 已关闭）。
- **依据**：
  1. 正分支接线经**直接实测**：`databaseConfigured=true` → `routeCount=47`、连接池/工厂均已装配（假驱动探针 + `Bootstrap` 运行期日志自证）。
  2. 负分支接线：未配 `hg.db.url` 时 `routeCount=2`、业务路由 404（`BootstrapAssemblyTest` 2 用例）。
  3. 45 条业务路由 **0 条越出 `ApiRoutes` 冻结清单、0 条重复注册**；口令零落盘守卫生效。
  4. `WsServer` 未接线且无编造默认端口，符合 `CFG-32`/`A-2` 处置。
- **风险提示 / 未决项**：
  - **GAP-1（✅ 已关闭，2026-09-21 00:09）**：原缺口为 `D-19` 正分支**无仓库内直接断言**（产出方用例 ④ 在 `Bootstrap.java:138` 抛异常即返回，未触达 `:140` 的 `registerBusinessRoutes`；删除该调用后原 6 用例仍全绿、45 业务路由静默消失）。`backend-engineer#java` 已补 `BootstrapAssemblyTest#configuredDatabaseRegistersAllBusinessRoutes`（`:136` 精确断言 `47`）+ `FakeJdbcDriver`；本人**独立反退化验证**：短路 `:140` → 该用例红（`expected:<47> but was:<2>` at `:136`），恢复后副本与共享源码 md5 均回基线 `db105f66…`。缺口关闭、回归保护到位。
  - **EXP-1（证据不实，非缺陷）**：`D-19` 自述「路由总数 38 / PLAN 7」与实测「**47 / PLAN 16**」的**差值**（口径：`(方法,路径)` 对 = 47、不同路径 = 37）。此为沟通/口径问题，**代码与契约均正确、无定版制品受影响**（`grep -rn '38 条路由\|29 个路径' docs/ java/ web/` → 0 命中），经编排者裁定**不开缺陷单**，保留于本披露栏。
  - **EXP-3（证据等级提示）**：`char[]` 清零属**静态审阅结论**，未获实测背书（单测无法在运行期设置 `HG_DB_PASSWORD`）。
- **建议**：`D-19` 可判**通过**（`GAP-1` 已关闭；附两处证据等级披露：EXP-1 自述差值、EXP-3 静态审阅结论）。是否发布由编排器/用户决定，测试席不出发布结论。

---

# T-100 专项独立复核（`te-verify-t100`，2026-09-21）

> 性质：对 **`T-100`**（装配 `GET /plan` 读取面子账户隔离）的**第三方独立复核**；**不采信产出方任何结论**，一切以本人读到/跑出的为准。
> 用例编号用 **`V-T100-<n>`**（本项不在 `TEST_PLAN/*.md` 模块用例清单内，避免与测试经理用例撞号）。
> **纪律**：反退化/变异全程在 `/tmp` 隔离副本内做，**共享树零探针、零改动**；共享树另跑一次 clean 全量自证绿灯。

## T-100.1 执行概况

| 项 | 内容 |
|----|------|
| 代码版本 | 仓库**无 git**（`git rev-parse --short HEAD` → `fatal: not a git repository`）。以 md5 锚定（取样时刻 **2026-09-21T06:07:45+08:00**）：`PlanService.java` `ff274b858047c4c2e759632893ffcad1`、`SubAccountVisibility.java` `4d3990ac0c18ff7ca725189f84c26015`、`AuthService.java` `80787eb63c831b238a6f7d72a4485c07`、`HedgePlanDao.java` `85fea2f7b1a2fe515975d80b40b87ad3`、`DmHedgePlanDao.java` `eaa62056541067669462d2e849d992a9`、`AbstractDmDao.java` `d32fd08951c8ecf7fc8791d1c03df9f5`、`Bootstrap.java` `f6ad893c6f0532bae5bbc9a2e001757b`、`PlanHandler.java` `5f591bc98ce39c166bf4ee25ca3cd09e`、`TradePlanHandler.java` `fca937079bc878269cc34f37dc1f9a7e`、`ApiRoutes.java` `3dec526b47b5b444d57de8898a07e030`、`PlanSubAccountIsolationTest.java` `ca1c0d6f9de4ca6268fdb6f37d9f5d2b` |
| 构建时间 | 隔离副本基线 `mvn` 起 `2026-09-21T06:08:01+08:00`，止 **`2026-09-21T06:09:24+08:00`**；共享树自证跑 起 06:16:2x，止 **`2026-09-21T06:17:46+08:00`** |
| 测试环境 | Linux / JDK `11.0.32`（Ubuntu）/ Maven `3.9.12` / `maven-surefire-plugin 3.2.5` / 离线（`-o`）/ **无 DM8 实例、无 DM 驱动** |
| 执行人 | 测试工程师（`te-verify-t100`） |
| 执行时间 | 2026-09-21 06:07–06:18 +08:00 |
| 执行范围 | `/tmp/t100_base`（基线）、`/tmp/t100_probe`（独立探针）、`/tmp/t100_mut`·`/tmp/t100_mutb`（反退化变异）；**均按 `docs` 符号链接指向真实 `docs`（规避路径型测试假红陷阱）** |

## T-100.2 基线复取（`V-T100-1`）——四要素

1. **取样时刻**：`2026-09-21T06:09:24+08:00`（maven `Finished at`）
2. **结果**：`Tests run: 300, Failures: 0, Errors: 0, Skipped: 0`
3. **是否排除**：**无排除**（`pom.xml` surefire 段仅 `<argLine>-Dfile.encoding=UTF-8</argLine>`，无 `<excludes>`/`skipTests`）
4. **是否 `clean`**：**是**（命令 `mvn -B -o clean test`）

**布局陷阱已规避**：`/tmp/t100_base/java`（`cp -r` 自真实 `java`，已删 `target`）+ `/tmp/t100_base/docs -> /root/myspace/hedge-guard/docs`。路径型测试（`DaoColumnAlignmentTest` / `ConfigAuthSchemaTest` / `M1ProductSpotPlanSchemaTest` / `RiskParamDispatchSchemaTest`）经 `repoRoot()`（向上找含 `docs/DATA_MODEL.md` ／ `docs/DEPLOY/schema` 的目录）解析到 `/tmp/t100_base`，**无 4 例假红**。

量级核验：`PlanSubAccountIsolationTest` 实跑 **8** 例（日志 `:448-449`）；全仓 `@Test` 计数 = **300**（`grep -rn "@Test" java/src/test --include=*.java | wc -l`）＝ 292 基线 + 8 新增，與自述一致。

**共享树自证（绿灯）**：`cd /root/myspace/hedge-guard/java && mvn -B -o clean test` → **`Tests run: 300, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`**（止 `2026-09-21T06:17:46+08:00`）。跑前跑后 `java/src/**/*.java`＋`java/pom.xml` 清单 md5 **均为 `a8437a02bba7ab309976c86571803dfe`**（282 文件）⇒ **共享树源零改动**。

## T-100.3 隔离是否真的生效（`V-T100-2` / `V-T100-7`）——独立探针（非产出方用例）

产出方 `PlanSubAccountIsolationTest` 只用**假 DAO** 验证服务层分派，**未验证真实 DAO 是否把 scope 注入 SQL**。本席自建探针（`/tmp/t100_probe`，**不进共享树**）：
- `com.hedgeguard.verify.TeT100IsolationProbeTest`（10 例）
- `com.hedgeguard.web.TeT100HandlerLeakProbeTest`（1 例）

运行：`mvn -B -o test -Dtest='TeT100IsolationProbeTest,TeT100HandlerLeakProbeTest'` → **`Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`**。

**已实际执行**的分支与断言：

| 分支 | 探针用例 | 断言 |
|---|---|---|
| 有权限（LIMITED） | `emptySetNeverTakesUnfilteredPath` + 产出方用例 | 只见 `sub_account_id ∈ {7}`；走 scoped |
| **无授权（空集）⇒ 必须空** | `emptySetNeverTakesUnfilteredPath` / `emptySetDetailNeverTakesUnfilteredPath` | `findPageCalls==0` 且 `findByIdCalls==0`（**绝不走不过滤路径**），必走 scoped（恒假） |
| 取数入口 `null` ⇒ fail-closed | `nullEntryFailsClosed` | 列表空 + 详情 `null` + 不走不过滤路径 |
| 跨账户只读（哨兵 0）并存 | `sentinelAlongsideIdsMeansUnfiltered` | `findPageCalls==1`、`findPageScopedCalls==0` |
| **真实 DAO SQL 注入面** | `realDaoFindByIdScopedEmptyScopeIsNeverTrue` / `…BindsIds` / `realDaoFindPageScopedEmptyScopeIsNeverTrue` / `…AndsExtraWhere` / `limitedScopeNotWidenedByFilter` | 空 scope ⇒ SQL 含 `sub_account_id IN (-1)`；非空 ⇒ `sub_account_id IN (?,?)` 且绑定 ID；`count`+`select` 两条 SQL **均**含；extraWhere 与 scope **AND** |

**实现源码行号（静态阅读）**：

| 环节 | 位置 | 语义 |
|---|---|---|
| 取数入口（生产） | `AuthService.visibleSubAccountIds` `:506-524`（`PERM_CROSS_READONLY`→哨兵 `:513`→`:521`） | 跨账户只读 ⇒ 追加哨兵 `0` |
| 服务层构造器 | `PlanService` 构造 `:131-150`（依赖 `subAccountVisibility` `:113/:149`） | 新增可见性依赖 |
| fail-closed 归一 | `PlanService.visibleSubAccountIds` `:1058-1062`（`null` ⇒ `Collections.emptyList()`） | 空/`null` ⇒ 空集 |
| 列表强制点 | `PlanService.listPlans` `:962`…`:992-999`（哨兵分支 `:993-995`；`findPageScoped` `:998-999`） | 哨兵 ⇒ 不过滤；否则恒假/IN |
| 详情强制点 | `PlanService.getPlan` `:922-930`（哨兵 `:924-926`；`findByIdScoped` `:928-929`） | 同上 |
| DAO 注入 | `AbstractDmDao.findPageScoped` `:250-260`（`where = scope.sql()` `:253`） | DAO 统一注入 |
| DAO 单条 | `DmHedgePlanDao.findByIdScoped` `:153-160`（`WHERE id = ? AND scope.sql()`） | 不可见＝不存在 |
| 恒假构造 | `SubAccountScope.sql` `:62-71`（空集 ⇒ `%s IN (-1)`） | **恒假、非恒真** |
| handler 传身份 | `PlanHandler.detail` `:104-110`（`getPlan(planId, ctx.currentUserId())`）；`list` `:151` | 身份取自会话 |
| handler 统一错误 | `PlanHandler.detail` `:107-110`（`null` ⇒ `PARAM_INVALID`「计划不存在」） | 与 `FR-PLAN-5` 一致 |
| 装配 | `Bootstrap.registerBusinessRoutes` `:213-217`（末参 `authService`） | `AuthService` 即 `SubAccountVisibility` |

**仅静态阅读、未实际执行**：
- `AuthService.visibleSubAccountIds` 的 `perm_type → 集合` 映射（`:506-524`）：仓库内仅 `AuthServiceTest.subAccountSoftIsolation:306-308` 执行了「CROSS_READONLY ⇒ 含哨兵」一支；**LIMITED-only 与空集映射未经执行**（环境无 DM8，DAO 为假对象）。
- `PLAN.md` / `ARCHITECTURE.md` 规范的解读（文本，非可执行）。

## T-100.4 反退化独立验证（`V-T100-4`）——不采信自述

**变异全程在 `/tmp` 副本**，共享源零改动。

**变异 A（删/短路 `scope` 分支**：`getPlan`→恒 `findById`、`listPlans`→恒 `findPage`；`PlanService.java` md5 `ff274b85…` → `dc1ef54bec250d6fc82e8d9e350f454a`）：`mvn -B -o test -Dtest=PlanSubAccountIsolationTest` → **`Tests run: 8, Failures: 6, Errors: 0, Skipped: 0`**。
6 红 = `limitedUserSeesOnlyOwnSubAccount` / `userWithNoGrantSeesNothingNotAll` / `detailRejectedForInvisibleSubAccount` / `detailDeniedWhenNoGrant` / `nullVisibilityFailsClosed` / `handlerThreadsSessionIdentity`。
2 绿 = `crossReadonlySeesAllWithoutScope` / `detailAllowedForCrossReadonly`。

**产出方"另 2 例仍绿属预期"是否成立？——成立（本席独立复现）**：这 2 例走的是**跨账户只读（哨兵 0）路径**，其预期行为**本就是"不加过滤、可见全部"**（`listPlans`→`findPage`、`getPlan`→`findById`）。变异 A 恰把"其它身份"也变成"不过滤"，故这 2 例的观察值与预期值**仍然一致**。它们是**专门覆盖"不过滤分支"的正向用例**，检测该变异是**另 6 例的职责**。⇒ **6/8 命中，覆盖充分，不构成漏检**。

**变异 B（危险的"默认全可见"**：把**空集也当"不过滤"**；md5 `ff274b85…` → `9c5fe0765b8a54230383c10bbbc6a71b`）：→ **`Tests run: 8, Failures: 3`** = `userWithNoGrantSeesNothingNotAll` / `nullVisibilityFailsClosed` / `detailDeniedWhenNoGrant`。⇒ **`NFR-SEC-7` 严禁的失效模式（空集退化为全可见）被独立守住**。

## T-100.5 存在性泄露（`V-T100-3`）

`TeT100HandlerLeakProbeTest.invisibleAndNonexistentYieldSameError`（**已执行，通过**）：
- 受限身份（可见 `[7]`）请求 `GET /plan/2`（**存在**、属子账户 8）与 `GET /plan/999`（**不存在**）⇒ 两者抛**同一 `ErrorCode`**（`PARAM_INVALID` = 10001），把请求方已知的 id 归一化后**文案逐字相同**（`计划不存在：id=#；复现=按 plan_no 查询确认 ID`），且文案**不含**"权限/无权"线索 ⇒ **不可用错误码或文案区分「存在但无权限」与「不存在」**。
- service 层 `getPlan(2,1)` 与 `getPlan(999,1)` **同为 `null`**（`assertEquals` 相等）。

## T-100.6 残余缺口复核（`V-T100-5`）——独立证实「无隔离」

**源码**：`PlanService.listTradePlans(long planId)`（`PlanService.java:851-853`）→ `tradePlanDao.findByHedgePlan(planId)`，**签名无调用者身份、无 scope**；`TradePlanDao.findByHedgePlan(long hedgePlanId)`（`TradePlanDao.java:20`）无 scope 参数；`DmTradePlanDao.findByHedgePlan`（`:115-119`）SQL 仅 `WHERE hedge_plan_id = ?`；`TradePlanHandler.handle`（`:44-46`）GET 分支直接 `planService.listTradePlans(planId)`，**不传 `ctx.currentUserId()`**；路由 `GET /plan/{id}/trade-plans` 已注册（`PlanRoutes.java:58`）。

**运行时（已执行，探针通过）**：`TeT100TradePlanResidualProbeTest`：受限身份（可见 `[7]`）请求 `GET /plan/2/trade-plans`（计划 2 属子账户 8）→ **HTTP 200**，响应体含**他人子账户**交易计划编号 `TP-SECRET-8` ⇒ **跨子账户可读，缺口确凿（证实产出方自报，非推翻）**。

> 依据：`FR-PLAN-15`「处理规则」明文「**仅返回有权限的子账户范围**」（`REQUIREMENTS/PLAN.md:322`）+ `NFR-SEC-7`（`NONFUNCTIONAL.md:121`，适用范围含"全部业务模块的查询类 FR"）。
> **该缺口已被授权任务 `T-101`（P0 / `backend-engineer#java` / `TODO` / 3 人时）独立承载**（登记见 `TASKS.md:145`、`BOARD.md:135`；`TASKS.md` 于本复核期间被并发写入，任务数 100 → 101）⇒ 依「一单一事 / 不重复建号」**不重复开 BUG 单**。

## T-100.7 边界与判据（`V-T100-6`）

| 项 | 命令/口径 | 实测 | 基线 | 判定 |
|---|---|---|---|---|
| `ApiRoutes.java` md5 | `md5sum java/src/main/java/com/hedgeguard/web/ApiRoutes.java` | `3dec526b47b5b444d57de8898a07e030` | 同 | 符合 |
| 探针残留 | `grep -r 'TEMP-' java/src --include=*.java \| wc -l`（口径：`java/src` 下 `.java` 全量，无排除） | `0` | 0 | 符合 |
| `E5` 判据 | `bash tools/arch-check/scan_java_sql.sh` | 待扫描文件 **113**、判据命中 **0**、`E5 通过：dao 之外未发现 SQL（退出码 0）` | 113 / 0 / 退出码 0 | 符合 |

## T-100.8 交叉核对表（产出方断言 → 本席实测 → 判定）

| # | 产出方断言 | 本席实测（命令/证据） | 判定 |
|---|---|---|---|
| 1 | 隔离副本 clean 全量 `300/0/0/0`，**无排除** | 副本与共享树各跑一次 `mvn -B -o clean test` → 均 `300/0/0/0`；`pom.xml` 无 excludes | **符合** |
| 2 | `300 = 292 基线 + 8 新增`（`PlanSubAccountIsolationTest`） | `@Test` 计数 300；该文件实跑 8 例 | **符合** |
| 3 | 新增 `SubAccountVisibility`（`@FunctionalInterface`）；`AuthService implements`；`PlanService` 构造器新增依赖；`listPlans`/`getPlan` 为强制点；哨兵 0⇒不过滤，否则 `SubAccountScope.of`；空集恒假；`null`⇒fail-closed；DAO `findPageScoped`/`findByIdScoped`；handler 传 `ctx.currentUserId()` | 逐条读源码（见 §T-100.3 行号表）+ 探针实测 | **符合** |
| 4 | 反退化：删 scope 分支 ⇒ **6/8 红**；另 2 例（跨账户只读）仍绿**属预期** | 变异 A：`8, Failures: 6`，2 绿正是两例 cross-readonly；解释成立 | **符合** |
| 5 | `getPlan` 对"不可见"与"不存在"返回**同一 `null`**；handler 统一「计划不存在」（`FR-PLAN-5`），避免泄露存在性 | 探针：service 两者同 `null`；handler 同 `ErrorCode`+同文案（归一化 id 后逐字相同） | **符合** |
| 6 | 残余缺口：`GET /plan/{id}/trade-plans`（`TradePlanHandler`→`listTradePlans`）**仍无隔离**，可跨子账户读 | 源码 `:851-853`/`:44-46` 无身份；运行时探针 `GET /plan/2/trade-plans` 受限身份得 200 + 他人 `TP-SECRET-8` | **符合（缺口真实存在）** |
| 7 | `web/ApiRoutes.java` md5 `3dec526b…`（未变） | `md5sum` = `3dec526b47b5b444d57de8898a07e030` | **符合** |
| 8 | `grep -rc 'TEMP-'` = 0 | 全量计数 = 0 | **符合** |
| 9 | `E5`：113 文件 / 0 命中 / 退出码 0 | 脚本输出 113 / 0 / 退出码 0 | **符合** |

**无法判定项：0**（环境限制导致的静态阅读项已在 §T-100.3 显式标注）。

## T-100.9 缺陷清单

**新建 BUG：0 条。** 扫描口径：`ls docs/BUGS/` → 仅 `BUG-001.md`（既有）；本次**未新建**。

| 类别 | 数量 | 说明 |
|---|---|---|
| **真实缺陷（代码/契约违背）** | **1** | `GET /plan/{id}/trade-plans` 跨子账户可读（违背 `FR-PLAN-15` `PLAN.md:322` / `NFR-SEC-7`）——**已由授权任务 `T-101`（P0/TODO）承载**，依「一单一事 / 不重复建号」**不再新建 BUG 单** |
| **证据不足** | **0** | — |
| **环境限制** | **1** | 无 DM8 实例/驱动 ⇒ 未做真实 SQL 端到端；隔离语义改用**假 JDBC 记录 SQL** + 假 DAO 记录调用路径验证（见 §T-100.3） |

## T-100.10 探索性发现（超出 `T-100` 读取面范围，报请编排者核归属）

| 编号 | 发现 | 证据 | 处理 |
|---|---|---|---|
| `EXP-T100-1` | **写面未见子账户可见性强制**：`AuthService.assertSubAccountVisible`（`:479`）在 `java/src/main/java` 内 **0 处调用**（`grep -rn assertSubAccountVisible java/src/main/java`）；全部计划写动作经 `requirePlan`（`PlanService.java:1064`）→ `hedgePlanDao.findById`（**不过滤**）。例：`withdraw`（`:639-655`）javadoc 称"操作人（须为发起人）"，实现**仅校验状态**、**未校验申请人**。 | 上列行号 + grep 结果 | **超出 `T-100`（读取面）**。属缺陷还是既定分工（写面归 `T-098`）请**编排者裁定**；本次**未开单**（避免越界/重复建号） |

## T-100.11 度量数据

| 指标 | 数值 |
|---|---|
| 复核验证项（`V-T100-*`） | 7 |
| 已执行 | 7（执行率 100%） |
| 独立探针用例 | 12（`10 + 1 + 1`，全绿） |
| 反退化变异 | 2（A：`6红/2绿`；B：`3红`） |
| 基线（隔离副本 / 共享树） | `300/0/0/0` 各一次 |
| 新建缺陷 | 0（真实缺陷 1，已由 `T-101` 承载） |
| 环境限制 | 1（无 DM8 实机） |

## T-100.12 结论

- **一行结论**：**`T-100` 通过** —— 其 7 条验收均经**独立实测**满足（三条隔离分支 + 空集 fail-closed + 详情越权拒绝 + 无存在性泄露 + 反退化 6/8 命中），基线 `300/0/0/0` 且**共享树源零改动**；**附一处真实缺陷**（`GET /plan/{id}/trade-plans` 无隔离），该缺陷已被授权任务 **`T-101`（P0）** 承载，故不重复建号。
- **依据**：§T-100.2（基线四要素）、§T-100.3（探针 11 例全绿 + 真实 DAO SQL 注入验证）、§T-100.4（变异 A/B 独立复现）、§T-100.5（无泄露）、§T-100.7（边界三判据）。
- **风险提示**：① `T-101` 缺口为 **P0 安全性质**（跨子账户读交易计划），须尽快实施；② `EXP-T100-1`（写面 `assertSubAccountVisible` 0 处调用）请编排者核定归属。
- **建议**：`T-100` 可判**通过**；是否置 `DONE` 与是否发布由编排器/用户决定，测试席不出发布结论（本席提供事实与数据）。

---

# `T-100` 复核的探索性发现（`EXP-T100-1`，超出 `T-100` 读取面范围）

> 本节的 `EXP-T100-1` 内容**原仅存在于复核者（`te-verify-t100`）的回报**，未落制品。由**新席位 `te-record-exp1` 独立复跑后补录**（本项目硬教训："只在对话里说过的发现会丢失"）。
> **本席为独立席位，未采信原回报措辞**：下列每条均附**本席自己执行的命令与原始输出**，并逐条给出「复核者主张 → 我的复现 → 符合 / 不符 / 无法判定」。
> 用例编号用 `E-EXP1-<n>`（本组发现不在 `TEST_PLAN/*.md` 模块用例清单内，避免与测试经理用例撞号）。

## 补录.1 版本锚点与取样时刻（本席实测）

| 项 | 内容 |
|----|------|
| 代码版本 | 仓库**无 git**（`git rev-parse --short HEAD` → `fatal: not a git repository`，退出码 `128`）。一律以 **md5** 锚定 |
| 取样时刻 | **2026-09-21T06:21:26+08:00**（连续两次复采样一致，见下） |
| `AuthService.java` | md5 `80787eb63c831b238a6f7d72a4485c07`（与 §T-100.1 所载一致，**未漂移**） |
| `PlanService.java` | md5 **`a18d23b767cbd8e5d1a7433e72f239d6`** / **1237 行**（⚠️ **与会话开始时的 `ff274b858047c4c2e759632893ffcad1` 不同** —— **该文件正在被并发写入**，详见「补录.5 纠正与补充」） |
| `AbstractDmDao.java` | md5 `d32fd08951c8ecf7fc8791d1c03df9f5` |
| `HedgePlanDao.java` | md5 `85fea2f7b1a2fe515975d80b40b87ad3`；`DmHedgePlanDao.java` md5 `eaa62056541067669462d2e849d992a9` |

## 补录.2 观测 1（`E-EXP1-1`）——`assertSubAccountVisible` 已实现但未接线

**复核者主张**：`AuthService.assertSubAccountVisible`（`:479`）在 `java/src/main` 下 **0 处调用**。

**本席复现 —— 扫描命令、范围与排除项**：

```
$ grep -rn 'assertSubAccountVisible' java/src/      # 范围=java/src/ 递归全类型文件，无 --include 过滤，无排除
java/src/test/java/com/hedgeguard/service/AuthServiceTest.java:285:        assertThrows(BizException.class, () -> service.assertSubAccountVisible(1L, 7));
java/src/test/java/com/hedgeguard/service/AuthServiceTest.java:289:        service.assertSubAccountVisible(1L, 7);
java/src/test/java/com/hedgeguard/service/AuthServiceTest.java:291:                assertThrows(BizException.class, () -> service.assertSubAccountVisible(1L, 8))
java/src/test/java/com/hedgeguard/service/AuthServiceTest.java:299:                assertThrows(BizException.class, () -> service.assertSubAccountVisible(1L, 7))
java/src/test/java/com/hedgeguard/service/AuthServiceTest.java:305:        service.assertSubAccountVisible(2L, 8);
java/src/main/java/com/hedgeguard/service/AuthService.java:479:    public void assertSubAccountVisible(long userId, int subAccountId) {
```

```
$ grep -rn 'assertSubAccountVisible' java/src/main | wc -l   # → 1（即 :479 方法定义本身）
$ grep -rn 'assertSubAccountVisible' java/src/test | wc -l   # → 5
```

| 口径 | 命中数 | 明细 |
|---|---|---|
| `java/src/main`（生产） | **1** | 仅 `AuthService.java:479` —— **方法定义行本身**，非调用 |
| `java/src/main` **调用点**（剔除定义行） | **0** | 无 |
| `java/src/test` | **5** | `AuthServiceTest.java:285/289/291/299/305` |
| 全 `java/src/` | **6** | 1 定义 + 5 测试调用 |

> 排除项说明：`java/src/main` 的 1 处为方法声明（`public void assertSubAccountVisible(...) {`），**不计为调用**；`src/test` 5 处均为 `service.assertSubAccountVisible(...)` 的真实调用。全仓另 2 处命中在 `docs/TEST_RESULT.md`（§T-100.10 与 §T-100.12 的文本引用），**非代码**。

**回答「这个方法是不是"存在但没人调"？」**：**是。** 在生产代码（`java/src/main`）中该方法**存在且仅有定义、0 处调用**；唯一调用者位于测试（`AuthServiceTest`）。即 `NFR-SEC-7` 的子账户可见性守卫**已实现但未接入任何生产路径**。

**逐条判定**：复核者主张（`:479`，`src/main` 0 处调用） → 本席复现 → **符合**。

## 补录.3 观测 2（`E-EXP1-2`）——写面无子账户可见性强制

**复核者主张**：写动作经 `requirePlan`（约 `PlanService.java:1064`）→ `findById`（不做子账户过滤）。

**本席复现 —— 调用链与行号（锚定 `PlanService.java` md5 `a18d23b7…` / 1237 行）**：

```
PlanService.java:1083   private HedgePlan requirePlan(long planId) {           // 签名无 operatorId / 无身份参数
PlanService.java:1084       HedgePlan plan = hedgePlanDao.findById(planId);   // → 不过滤
PlanService.java:1085-1088  if (plan == null) throw PARAM_INVALID("套保计划不存在：id=…")
PlanService.java:1089       return plan;
```

`requirePlan` 的下游 DAO（`HedgePlanDao` 接口未重写单条 `findById`，落到泛型基类 `AbstractDmDao`）：

```
AbstractDmDao.java:151-155
    public T findById(ID id) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE " + idColumn() + " = ?";
        List<T> rows = queryList(sql, Collections.singletonList(id), this::mapRow);
        return rows.isEmpty() ? null : rows.get(0);
    }
```

⇒ SQL 仅 `WHERE <idColumn> = ?`，**无 `sub_account_id` 条件** ⇒ **写路径上不存在按子账户的可见性断言**。

**全部 `requirePlan` 调用点（= 计划写动作，本席实测 10 处，均未向 `requirePlan` 传身份）**：

| 调用行 | 所属方法（定义行） | 语义 |
|---|---|---|
| `:469` | `generateHedgeDoc(long,String,String,long)`（`:457`） | 生成套保文档 |
| `:533` | `submitForApproval(long,String,long)`（`:532`） | 提交审批 |
| `:574` | `approve(long,long,String)`（`:573`） | 审批通过 |
| `:610` | `reject(long,long,String)`（`:608`） | 审批驳回 |
| `:641` | `withdraw(long,long,String)`（`:639`） | 撤销 |
| `:666` | `freeze(long,long,String)`（`:664`） | 冻结 |
| `:688` | `unfreeze(long,long)`（`:687`） | 解冻 |
| `:712` | `terminate(long,long,String)`（`:710`） | 终止 |
| `:740` | `extend(long,LocalDate,String,long)`（`:738`） | 展期 |
| `:776` | `decomposeTradePlans(long,List,long)`（`:775`） | 拆解交易计划 |

**佐证「写面无按调用者的可见性断言」**：
- `assertSubAccountVisible` 全仓调用点 = 0（见 §补录.2）⇒ **无任一写动作调用它**。
- `visibleSubAccountIds`（读取面取数入口）在 `PlanService` 内仅出现于 **`:864`（`listTradePlans`，读取）、`:942`（`getPlan`，读取）、`:1011`（`listPlans`，读取）、`:1077`（私有定义）** —— **无一处落在上述 10 个写方法内**（写方法区间 `:457–:872` 之外，且 `:864` 属读方法）。⇒ **写路径不经过任何可见性取数/过滤**。
- `SUB_ACCOUNT_PERMISSION_DENIED` 在 `PlanService` 内仅于 **`:1099`（`requireExposure`）** 出现，其语义是"敞口子账户与请求子账户不一致（`R-PLAN-7` 三者一致）"，**非调用者可见性校验**。

**逐条判定**：复核者主张（写动作经 `requirePlan` → `findById`，不过滤） → 本席复现 → **符合**（内容一致；**行号由并发写入自 `:1064` 漂移至 `:1083`**，方法体语义未变）。

## 补录.4 观测 3（`E-EXP1-3`）——`withdraw` 的 javadoc 承诺与实现不符

**复核者主张**：`PlanService.withdraw`（约 `:639-655`）javadoc 称"须为发起人"，实现仅校验状态。

**本席复现 —— javadoc 原文（`PlanService.java:632-638`）**：

```
632→    /**
633→     * 撤销（{@code FR-PLAN-17}）：待审批 → 草稿。
634→     *
635→     * @param planId     计划
636→     * @param operatorId 操作人（须为发起人）      ← 承诺：operatorId 必须是发起人
637→     * @param reason     撤销原因
638→     */
```

**实现体关键行（`PlanService.java:639-655`）**：

```
639→    public void withdraw(long planId, long operatorId, String reason) {
640→        requireText(reason, "reason");                       // 只校验 reason 非空
641→        HedgePlan plan = requirePlan(planId);                // 只校验存在（无子账户过滤）
642→        if (!"PENDING_APPROVAL".equals(plan.status)) {       // 只校验状态
643→            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED, "仅待审批态可撤销：…");
646→        }
647→        tx.inTransaction(connection -> {
649→            plan.status = "DRAFT";
650→            hedgePlanDao.updateById(plan, operatorId);       // operatorId 仅作审计字段（updateById 第2参）
651→            approval(plan, plan.applicantId, null, "WITHDRAW", "WITHDRAWN", reason, null);
652→            writeStateLog(plan, before, "DRAFT", "NONE", null, operatorId, reason);
653→            return null;
654→        });
655→    }
```

**差在哪**：javadoc `:636` 将 `operatorId` 定义为"操作人（**须为发起人**）"，但实现体 **`operatorId` 全程只作审计字段**（`:650` 传给 `updateById` 的第二参、`:652` 记入状态日志），**从未与 `plan.applicantId` 做任何相等比较**。⇒ 任何能到达该方法、且已知 `planId` 的调用者（对被撤销计划无所有权时亦然）在状态为 `PENDING_APPROVAL` 时**均可撤销他人计划**，与 javadoc 承诺不符。
（对照：同文件 `approve :580/:583`、`reject :616` 确有 `approverId == plan.applicantId` 的比较，但其语义是**"禁止自审"**，**不是**发起人可见性校验；`withdraw` 连此类比较都没有。）

**逐条判定**：复核者主张（javadoc 称"须为发起人"、实现仅校验状态） → 本席复现 → **符合**。

## 补录.5 纠正与补充（本席独立复跑所得）

1. **行号漂移（重要）**：复核者回报中 `requirePlan` 记作 `:1064`、`listTradePlans` 记作 `:851-853`，其锚点为 `PlanService.java` md5 `ff274b85…`（§T-100.1 取样 06:07:45）。本席复跑时该文件已变为 **`a18d23b7…` / 1237 行**：`requirePlan` 移至 **`:1083`**、`visibleSubAccountIds` 使用点变为 `:864/:942/:1011`、`listTradePlans` 已改签名为 `(long planId, long operatorId)` 并**新增子账户隔离**（`:863-872`）——即 **`T-101` 正被并发实施**。故本节的**行号一律锚定我实测的 `a18d23b7…` @ 06:21:26**；复核者原回报的行号系**旧锚点**。
2. **无 git**：仓库无版本控制（`git rev-parse` 退出码 `128`），一切版本以 md5 + 取样时刻为准（与 §T-100.1 同口径）。
3. **同族未接线守卫（本席扫描，超出原回报）**：`AuthService` 的 `public` 方法在 `java/src/main` 内的**调用点数**经实测为——`login`=1、`logout`=1、`visibleSubAccountIds`=1（`PlanService:1079`，经接口）；而 **`assertSubAccountVisible`=0、`assertCanTrade`=0、`touchSession`=0、`changePassword`=0、`resetPassword`=0、`grantRole`=0**。即 `assertCanTrade`（同为 `NFR-SEC-7`/交易权限守卫）**与 `assertSubAccountVisible` 同族、同样存在但生产 0 调用**（`src/test`=3）；`touchSession`/`changePassword`/`resetPassword`/`grantRole` 亦生产 0 调用（仅测试覆盖），与 `AuthRoutes` 仅注册 `/auth/login`、`/auth/logout` 两条路由一致。**登记归属由编排者裁定，本席不越界定性。**
   - 扫描口径：`grep -rn "<method>(" java/src/main --include=*.java | grep -v "public .* <method>("`（剔除定义行）；`assertCanTrade` 另有 1 处 `{@link #assertCanTrade}` javadoc 引用（`AuthService.java:53`），**不算调用**。
4. **补充**：观测 2 的"全部计划写动作经 `requirePlan`"对本席实测的 **10 个写方法**成立；`createPlan(long,List,long)`（`:332`）**不经** `requirePlan`（其语义为新建、无需按 id 回查），不属该链条。

## 补录.6 归属与制品处置

- **本组发现无 `BUGS/` 单**。原因：已按本项目口径「**缺陷若已有授权任务承载，则以任务号为准、不重复建号**」（同 `T-100` 复核对 `T-101` 的处理）处理。
- **已由 `T-102` 承载**：`docs/TASKS.md:146` —— `T-102 | 写面子账户可见性强制补齐（assertSubAccountVisible 未接线 + withdraw 发起人校验缺失）| FR-AUTH-13, NFR-SEC-7, R-AUTH-8 | backend-engineer#java | P0 | 依赖 T-100 | 3 人时 | 新增（T-100 专项复核 EXP-T100-1 同族发现；编排者 2026-09-21 裁定立项）| TODO`。
  - `TASKS.md` 本席实测 md5 **`57d3760268aee313866b749cf561c0b8`** / **2095 行**（取样 2026-09-21T06:21:26+08:00）；**引用以实时 `md5sum` 为准**。
- **本席未开 `BUGS/` 单、未改 `TASKS.md`/`BOARD.md`/任何 `java/**`**；本节为 `docs/TEST_RESULT.md` 的唯一改动。

## 补录.7 本组度量

| 指标 | 数值 |
|---|---|
| 补录观测 | 3（`E-EXP1-1` ~ `E-EXP1-3`） |
| 逐条判定 | 符合 3 / 不符 0 / 无法判定 0 |
| 新建缺陷单 | 0（由 `T-102` 承载） |
| 独立纠正 | 1（`PlanService.java` 行号漂移 + 并发写入告警） |
| 同族未接线守卫（补充） | `assertCanTrade` 1 项（另 4 个非守卫方法生产 0 调用） |

---

# `T-101` 专项独立复核（`te-verify-t101`，2026-09-21）

> 性质：对 **`T-101`**（`GET /plan/{id}/trade-plans` 读取面子账户隔离）的**第三方独立复核**；**不采信产出方任何结论**，一切以本人读到/跑出的为准。
> 用例编号用 **`V-T101-<n>`**（本项不在 `TEST_PLAN/*.md` 模块用例清单内，避免与测试经理用例撞号）。
> **纪律**：探针/变异全程在 `/tmp` 隔离副本内做，**共享树零探针、零改动**（源清单 md5 前后一致），共享树另跑一次 clean 全量自证绿灯。

## T-101.1 执行概况

| 项 | 内容 |
|----|------|
| 代码版本 | 仓库**无 git**（`git rev-parse --short HEAD` → `fatal: not a git repository`，退出码 128）。以 md5 + 取样时刻锚定；取样 **2026-09-21T06:42:40+08:00** |
| 关键 md5（本席实测） | `PlanService.java` **`a18d23b767cbd8e5d1a7433e72f239d6`**（1237 行）· `TradePlanDao.java` `c1194054b1f7b7191972a0b75956ae44` · `DmTradePlanDao.java` `01be0cee250db3f6a387a0ae9a380774` · `SubAccountScope.java` `04d64808ec70cdebb52c6eb4e7a4232f` · `AbstractDmDao.java` `d32fd08951c8ecf7fc8791d1c03df9f5` · `TradePlanHandler.java` `4065d87e01be34e55c49fb4db497085e` · `ApiRoutes.java` `3dec526b47b5b444d57de8898a07e030` · `AuthService.java` `80787eb63c831b238a6f7d72a4485c07` · `SubAccountVisibility.java` `4d3990ac0c18ff7ca725189f84c26015` · `TradePlanSubAccountIsolationTest.java` `a870a1892b9f8e178fada0a33f6d2722` · `DmTradePlanDaoScopedSqlTest.java` `d03773d206ecef0f5e49efb70e4f308d` |
| 共享树源清单 md5 | **`113cb6479cf15da879248c1e90407c5e`**（284 个 `.java`）+ `pom.xml` `26920ad85c07cf8aa35b0e6df59504a3`——**复核前后两次实测全等** ⇒ **共享树源零改动** |
| 构建时间 | 隔离副本基线止 **`2026-09-21T06:32:23+08:00`**；共享树自证止 **`2026-09-21T06:42:37+08:00`** |
| 测试环境 | Linux / JDK `11.0.32`（Ubuntu）/ Maven `3.9.12` / `maven-surefire-plugin 3.2.5` / 离线（`-o`）/ **无 DM8 实例、无 DM 驱动** |
| 执行人 | 测试工程师（`te-verify-t101`） |
| 执行范围 | `/tmp/t101v`（基线+独立探针）、`/tmp/t101m`（反退化变异 A/B/C）；**均按 `docs` 符号链接指向真实 `docs`（规避路径型测试 4 例假红陷阱）** |

## T-101.2 基线复取（`V-T101-1`）——四要素

1. **取样时刻**：`2026-09-21T06:32:23+08:00`（maven `Finished at`）
2. **结果**：`Tests run: 310, Failures: 0, Errors: 0, Skipped: 0`
3. **是否排除**：**无排除**（`pom.xml:108-113` surefire 段仅 `<argLine>-Dfile.encoding=UTF-8</argLine>`；`grep -n 'excludes\|skipTests\|<exclude' java/pom.xml` → 无）
4. **是否 `clean`**：**是**（命令 `mvn -B -o clean test`）

**量级核验**：全仓 `@Test` 计数 = **310**（`grep -rn '@Test' java/src/test --include=*.java | wc -l`）= T-100 时点 300 + `TradePlanSubAccountIsolationTest` **6** + `DmTradePlanDaoScopedSqlTest` **4**；两新文件独立定向跑 = **6 + 4 = 10 全绿**。

**共享树自证（绿灯）**：`cd /root/myspace/hedge-guard/java && mvn -B -o clean test` → **`Tests run: 310, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`**（止 `2026-09-21T06:42:37+08:00`）。源清单 md5 前后均 `113cb647…`（284 文件）⇒ 零改动。

**布局陷阱已规避**：`/tmp/t101v/java`（`cp -r` 自真实 `java`，已删 `target`）+ `/tmp/t101v/docs -> /root/myspace/hedge-guard/docs`；路径型测试（`DaoColumnAlignmentTest` / `ConfigAuthSchemaTest` / `M1ProductSpotPlanSchemaTest` / `RiskParamDispatchSchemaTest`）经 `repoRoot()` 解析到 `/tmp/t101v`，**无假红**。

## T-101.3 隔离是否真的生效（`V-T101-2`）——独立端到端探针

产出方用例（`TradePlanSubAccountIsolationTest` 用**假 DAO**、`DmTradePlanDaoScopedSqlTest` 用**假 JDBC 只断言 SQL 字符串**）均**未端到端断言"真实 DAO 生成的 SQL 作用到内存数据后返回哪些行"**。本席自建探针（`/tmp/t101v`，**不进共享树**）：`com.hedgeguard.verify.TeT101IsolationProbeTest`（**11 例**），以**真实** `PlanService` + **真实** `DmTradePlanDao` 驱动一个**会按生成 SQL 语义求值**的内存假 JDBC：
- 支持 `hedge_plan_id = ?` 与 `sub_account_id IN (...)` 的 AND 组合求值（含恒假 `IN (-1)`、字面量/占位符混合）；
- 内存表：`id=101(计划1,子7,TP-OWN-7)`、`id=102(计划1,子8,TP-SECRET-8)`、`id=103(计划2,子8,TP-SECRET-8B)`。

运行：`mvn -B -o test -Dtest=TeT101IsolationProbeTest` → **`Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`**。

**已实际执行**的分支与断言（返回的行 = 真实 SQL 求值结果）：

| 分支 | 探针用例 | 断言（实测） |
|---|---|---|
| 有权限（LIMITED [7]） | `a1_limitedSeesOnlyOwnRow` | 只返回 `id=101`（子7）；SQL 含 `sub_account_id IN (?)`；**prepareStatement 计数=1（无 COUNT）** |
| **越权读他子账户计划 id** | `a2_foreignPlanYieldsEmpty` | 计划2（属子8）对[7]⇒**空**；对照：同 DAO 不过滤路径计划2 确 1 行 |
| **无授权（空集）⇒ 必须空** | `a3_emptyGrantNeverUnfiltered` | **空**；SQL 含恒假 `sub_account_id IN (-1)`；对照：不过滤时计划1 有 2 行 |
| **取数入口 `null` ⇒ fail-closed** | `a4_nullEntryFailsClosed` | **空**；SQL 含恒假 |
| 跨账户只读（哨兵 0） | `a5_sentinelYieldsUnfiltered` | 返回 2 行（全可见）；SQL **不含** `sub_account_id IN` |
| 哨兵混入 [0,7] | `a6_sentinelMixedYieldsUnfiltered` | 仍不过滤（2 行） |
| 多授权 [7,8] | `a7_multiGrantFiltersByOrderedIn` | SQL `sub_account_id IN (?,?)`；args `[1,7,8]` 顺序正确 |
| 真实 DAO SQL 形状（空 scope） | `b1_emptyScopeSqlShape` | SQL `(hedge_plan_id = ?) AND sub_account_id IN (-1)`；占位符 1；args `[1L]` |
| 真实 DAO SQL 形状（非空 scope） | `b2_nonEmptyScopeSqlShape` | SQL `(hedge_plan_id = ?) AND sub_account_id IN (?,?)`；占位符 3；args `[7L,11,22]` |
| 端点级（会话身份，真实 DAO） | `c1_handlerThreadsSessionIdentityRealDao` | `GET /plan/1/trade-plans`（user42⇒[7]）⇒ 200；body 含 `TP-OWN-7`、**不含** `TP-SECRET-8` |

**实现源码行号（本人静态阅读，`PlanService.java` md5 `a18d23b7…`）**：

| 环节 | 位置 | 语义 |
|---|---|---|
| 取数入口（生产） | `AuthService.visibleSubAccountIds` `:506-524`（`PERM_CROSS_READONLY`→哨兵 `:519-522`） | 跨账户只读 ⇒ 追加哨兵 0 |
| 读面强制点（本任务） | `PlanService.listTradePlans` `:863-872`（哨兵分支 `:865-868`；scoped `:870-871`） | 哨兵⇒不过滤；否则 `sub_account_id IN (...)` |
| fail-closed | `PlanService.visibleSubAccountIds` `:1077-1081`（`null⇒emptyList` `:1080`） | 入口缺位 ⇒ 空集 |
| 恒假构造 | `SubAccountScope.sql` `:62-71`（空集⇒`%s IN (-1)` `:63-65`） | **恒假、非恒真** |
| AND 结合 | `SubAccountScope.appendTo` `:84-89`（`"("+where+") AND "+sql`） | 括号/顺序正确 |
| DAO 注入 | `DmTradePlanDao.findByHedgePlanScoped` `:130-139`（`scope.appendTo("hedge_plan_id = ?")` `:133`；args `[plan]+scope.params()` `:135-137`） | 先计划后子账户 |
| handler 传身份 | `TradePlanHandler.handle` `:46`（`listTradePlans(planId, ctx.currentUserId())`） | 身份取自会话 |
| 装配 | `PlanService` 构造器 `:131-150`（新增 `subAccountVisibility` `:137/:149`） | 可见性依赖 |

**仅静态阅读、未执行**：`AuthService.visibleSubAccountIds` 的 `perm_type→集合` 映射（环境无 DM8，DAO 为假对象；仓库内仅 `AuthServiceTest` 覆盖）；`PLAN.md`/`ARCHITECTURE.md` 文本解读。

## T-101.4 真实 DAO SQL 层验证（`V-T101-3`）——独立于产出方用例

1. 独立记录型假 JDBC（非产出方 `RecordingJdbc`）驱动真实 `DmTradePlanDao`：空 scope ⇒ `(hedge_plan_id = ?) AND sub_account_id IN (-1)`、1 占位符、args `[1L]`、结果空；非空 `[11,22]` ⇒ `(hedge_plan_id = ?) AND sub_account_id IN (?,?)`、3 占位符、args `[7L,11,22]`（`b1`/`b2`）。
2. **哨兵 0** ⇒ 走 `findByHedgePlan`，SQL `WHERE hedge_plan_id = ?`、**无 `sub_account_id IN`**（`a5`/`a6`）。
3. **AND 结合正确**：`appendTo` 生成 `(<既有>) AND <scope>`，占位符先 `hedge_plan_id` 后子账户，args 与之一一对应（`a7`/`b2` 实测）。

**产出方第 2 项披露（"本端点只有一条 SELECT、无 COUNT"）核验**：**确认成立**。① 运行期：`a1` 断言 `listTradePlans` 全程 `prepareStatement` 计数 = **1**；② 静态：`DmTradePlanDao.findByHedgePlanScoped`（`:131-138`）仅一次 `queryList`（`AbstractDmDao:196-209`，单条 `prepareStatement`+`executeQuery`），**无 `count` 调用**；分页方法 `findPageScoped`/`findPage`（`:158-169`）才有 `:162 count`。⇒ 本端点取数面**确无独立 COUNT**，产出方"只能验 select"的披露**属实**，**非覆盖缺口**。

## T-101.5 反退化独立验证（`V-T101-4`）——不采信自述，三向变异

**全程 `/tmp/t101m` 副本，共享源零改动**。

| 变异 | 内容（md5 变化） | 产出方用例结果 | 本席探针结果 |
|---|---|---|---|
| **A** | `listTradePlans` 退回**无条件** `findByHedgePlan`（`PlanService.java` `a18d23b7…→d46c12b6…`） | **6/10 红**（`TradePlanSubAccountIsolationTest` 6→**5 红**：limited / noGrant / nullEntry / foreignPlan / handlerThreads；`DmTradePlanDaoScopedSqlTest` 4→**1 红**：`serviceLayerForcesScopedSql`） | **6/11 红**（a1,a2,a3,a4,a7,c1） |
| **B** | **空集也当"不过滤"**（危险默认：全可见）（`a18d23b7…→977d42af…`） | **2 红**（`userWithNoGrantSeesNothingNotAll` / `nullVisibilityFailsClosed`） | **2 红**（a3,a4） |
| **C** | 空 scope 生成**恒真** `1=1`（`SubAccountScope.java` `04d64808…→…`） | **2 红**（`SubAccountScopeTest.emptyScopeIsNeverTrue` / `DmTradePlanDaoScopedSqlTest.emptyScopeEmitsNeverTrueCondition`） | 3 例（本席求值器对该 SQL 抛 `IllegalState` ⇒ 判红） |

**回答"③ 跨账户只读保持绿是否成立且属预期"**：**成立且属预期**。变异 A 下仍绿者 = `crossReadonlySeesAllWithoutScope`（服务层）与 `crossReadonlySkipsScopeInSql`（SQL 层）——二者**专门覆盖"哨兵 0 ⇒ 不过滤"的正向路径**，其观察值与预期本就一致；检测变异 A 是其余 6 例的职责。另 2 例仍绿（`DmTradePlanDaoScopedSqlTest` 的 `emptyScope…`/`nonEmptyScope…`）系**直连 DAO、不经服务层**，本不受该变异影响。⇒ **6/10 命中构成充分覆盖，无漏检**。

**危险的"空集退化为全可见"（`NFR-SEC-7` 严禁）** 经**变异 B 独立守住**（`userWithNoGrantSeesNothingNotAll` + `nullVisibilityFailsClosed` 红）。**恒假/恒真不变式**经**变异 C 独立守住**（scope 单测 + SQL 单测双红）。

## T-101.6 残余缺口独立证实（`V-T101-5`）——`decomposeTradePlans`

**源码（`PlanService.java` md5 `a18d23b7…` / 1237 行）**：
- `decomposeTradePlans(long,List,long)` 定义 `:775`；**`:786`** 调 `tradePlanDao.findByHedgePlan(planId)`（**未 scoped**）——签名无 scope、无可见性取数；
- 调用链 `TradePlanHandler.handle` `:67`（POST 分支）→ `decomposeTradePlans(planId, rows, ctx.currentUserId())`；`requirePlan` `:1083-1090` → `hedgePlanDao.findById`（`AbstractDmDao:151-155`，SQL 仅 `WHERE id = ?`，**无子账户过滤**）；
- **无任何可见性守卫**：`decomposeTradePlans` 内无 `visibleSubAccountIds` 调用（`grep` 实测：`visibleSubAccountIds` 使用点为 `:864`/`:942`/`:1011`/`:1077`，均在读方法或私有定义，**无一在写方法内**）。

**运行时（探针 `d1`，已执行、通过）**：受限身份（只可见 `[7]`，`operatorId=999`）对**属子账户 8 的计划 2** 调 `decomposeTradePlans(2, [10 吨], 999L)`：
- **未被权限拒绝**（当前实现无写面可见性守卫）；
- 计算"既有交易计划量"所执行的 SELECT = `SELECT … FROM t_trade_plan WHERE hedge_plan_id = ? ORDER BY …`，**WHERE 子句不含 `sub_account_id`** ⇒ **读到他子账户（8）的交易计划行**（`d1` 断言该行 `subAccountId==8`）。

⇒ **证实产出方披露 ①**：写路径 `decomposeTradePlans` 的读取**未做子账户过滤**，且**可由无权限调用者触发**——构成**跨子账户写/读风险**。
**归属（不重复建号）**：该缺口**已被授权任务承载**——`docs/TASKS.md:146` 的 **`T-102`** 明列`…+ PlanService.decomposeTradePlans（:786）写路径读取未 scope`（标注"来源 = `T-101` 交付披露"），且 `TASKS.md:1916` 为其登记裁定条款。依「缺陷若已有授权任务承载则以任务号为准、不重复建号」，**不开新 BUG 单**。

## T-101.7 边界与判据（`V-T101-6`）

| 项 | 命令/口径 | 实测 | 判定 |
|---|---|---|---|
| `ApiRoutes.java` md5 | `md5sum java/src/main/java/com/hedgeguard/web/ApiRoutes.java` | `3dec526b47b5b444d57de8898a07e030` | 符合（产出方一致） |
| 探针残留 | `grep -rc 'TEMP-' java/src --include=*.java`（范围=java/src 下 `.java` 全量，无排除） | 命中 **0** | 符合 |
| `E5` 判据 | `bash tools/arch-check/scan_java_sql.sh` | 待扫描 **114 文件** / 判据命中 **0** / `E5 通过：dao 之外未发现 SQL（退出码 0）` | 符合（产出方一致） |

> 114 = 113（T-100 时点）+ 1（新测试 `TradePlanSubAccountIsolationTest`，在 `service/` 下计入）；另一新文件 `DmTradePlanDaoScopedSqlTest` 位于 `dao/impl/`，被 `**/dao/**` 排除项豁免，故不增计数。

## T-101.8 交叉核对表（产出方断言 → 本席实测 → 判定）

| # | 产出方断言 | 本席实测 | 判定 |
|---|---|---|---|
| 1 | 隔离副本 clean 全量 `310/0/0/0`，**无排除** | 复取 `310/0/0/0`，`clean`，pom 无 excludes | **符合** |
| 2 | 共享树 clean 全量 `310/0/0/0` | 复取 `310/0/0/0`，源清单 md5 前后全等 | **符合** |
| 3 | `PlanService.listTradePlans(planId,operatorId)` 为服务端强制点；哨兵 0⇒不过滤；空集⇒恒假；null⇒fail-closed | 逐条读源码（`:863-872`/`:1077-1081`）+ 探针 a1-a7 端到端实测 | **符合** |
| 4 | `findByHedgePlanScoped` 消费 `scope.appendTo("hedge_plan_id = ?")`、保持 `List` 非分页 | 读 `:130-139`；探针 b1/b2 实测 SQL 形状与 args | **符合** |
| 5 | `TradePlanHandler` 传 `ctx.currentUserId()` | 读 `:46`；探针 c1 实测会话身份生效 | **符合** |
| 6 | 反退化：退回无条件 `findByHedgePlan` ⇒ `10, Failures: 6`；跨账户只读 2 例仍绿属预期 | 变异 A 独立复现 **6/10 红**；2 绿经解释成立（见 §T-101.5） | **符合** |
| 7 | 真实 DAO SQL 断言（空 scope 恒假 / 非空 IN / 1 与 3 占位符 / args `[1L]`、`[7,11,22]`） | 本人独立记录型 JDBC 复现（b1/b2/a7） | **符合** |
| 8 | `ApiRoutes.java` md5 `3dec526b…` 未变 | `md5sum` = `3dec526b47b5b444d57de8898a07e030` | **符合** |
| 9 | `grep -rc 'TEMP-' java/src --include=*.java` = 0 | 全量计数 = 0 | **符合** |
| 10 | `E5`：114 文件 / 0 命中 / 退出码 0 | 脚本输出一致 | **符合** |
| 11 | **披露 ①**：`decomposeTradePlans`（`:786`）仍用未 scoped `findByHedgePlan` | 静态行号 + 运行时探针 `d1` 证实（跨子账户读，无权限者可触发） | **符合（缺口真实；归 `T-102`）** |
| 12 | **披露 ②**：本端点非分页 ⇒ 只产生一条 SELECT、无独立 COUNT | 运行期 `prepareStatement` 计数=1；静态无 `count` 调用 | **符合（属实、非覆盖缺口）** |
| 13 | **披露 ③**：`FR-PLAN-15` 其余验收（剩余可下单量/已达上限标记）非本任务范围 | 读 `REQUIREMENTS/PLAN.md:318-331`：验收 1/2/3 确未在本端点实现；属 `T-101`（隔离）之外 | **符合** |
| 14 | 升级条件：`FR-PLAN-15「处理规则」不含分页 ⇒ (b) 保持非分页成立` | **部分不符（口径）**：`处理规则`（`PLAN.md:322`）确不含分页，但**同节 `输入`（`PLAN.md:321`）明列「分页」（及筛选/排序）**；见 §T-101.10 `EXP-T101-1` | **不符（见 §T-101.10）** |

## T-101.9 缺陷清单

**新建 BUG：0 条。** 扫描口径：`ls docs/BUGS/` → 仅既有 `BUG-001.md`；本次**未新建**。

| 类别 | 数量 | 说明 |
|---|---|---|
| **真实缺陷（本任务读取面）** | **0** | `GET /plan/{id}/trade-plans` 四类隔离分支均经独立端到端实测通过 |
| **真实缺口（写面，非本任务）** | **1** | `decomposeTradePlans` 写路径读取未 scope（§T-101.6）——**已由 `TASKS.md:146` `T-102` ④ 承载**，不重复建号 |
| **证据不足** | **0** | — |
| **口径/证据披露不符** | **1** | 产出方对 (b) 的依据引"处理规则"而遗漏"输入列有分页"（§T-101.10 `EXP-T101-1`）——非代码缺陷 |
| **环境限制** | **1** | 无 DM8 实例/驱动 ⇒ 真实 SQL 端到端改以**内存求值假 JDBC** 端到端替代（比仅断言 SQL 字符串更强） |

## T-101.10 探索性发现

| 编号 | 发现 | 证据 | 处理 |
|---|---|---|---|
| `EXP-T101-1` | **产出方对"保持非分页"的依据不完整**：其升级条件答"读 `FR-PLAN-15「处理规则」不含分页 ⇒ (b) 成立"。实测 `REQUIREMENTS/PLAN.md`：`:321` **输入** = 「筛选条件（子账户、套保计划、合约、状态、执行期限）、排序、**分页**」；`:322` **处理规则** 确不含分页。⇒ 原判据只读了半节。**但**：现状端点 `listTradePlans(planId,operatorId)` 仅接收 planId，既无筛选/排序/分页，且 `PlanRoutes.java:32` 的 `GET|POST /plan/{id}/trade-plans` 为**冻结路径**（`ApiRoutes.java` md5 `3dec526b…` 未变）；补分页/筛选属**接口契约变更**，**不在 `T-101`（隔离）范围**。 | `PLAN.md:321`、`:322`；`PlanRoutes.java:32`；`ApiRoutes.java` md5 `3dec526b…` | **非 `T-101` 代码缺陷**。属 **`FR-PLAN-15` 端点完整度** 议题（与验收 1/2/3 同族），**请编排者/测试经理核定归属**；本席**未开单**（避免越界/重复）。 |
| `EXP-T101-2` | **探针可比产出方用例更强**：产出方 SQL 层用例仅断言 SQL 字符串；本席以**按 SQL 语义求值的内存假 JDBC** 端到端断言**实际返回行**（如"越权读⇒空"、"哨兵⇒2 行"），可在"SQL 拼对但语义错"（如错误列/错误 AND/求值即全真）时**额外兜底**。 | `TeT101IsolationProbeTest` 11 例全绿；变异 A/B/C 下按预期变红 | 建议后续隔离类复核沿用；**不构成缺陷**。 |

## T-101.11 度量数据

| 指标 | 数值 |
|---|---|
| 复核验证项（`V-T101-*`） | 6 |
| 已执行 | 6（执行率 100%） |
| 独立探针用例 | 11（全绿；另在 3 个变异态下按预期变红） |
| 反退化变异 | 3（A：producer 6/10 红、本席 6/11 红；B：2 红；C：2 红） |
| 基线（隔离副本 / 共享树） | `310/0/0/0` 各一次 |
| 新建缺陷 | 0（真实缺口 1，已由 `T-102` 承载） |
| 环境限制 | 1（无 DM8 实机） |

## T-101.12 结论

- **一行结论**：**`T-101` 通过** —— `GET /plan/{id}/trade-plans` 的**四类子账户隔离分支**（LIMITED 只见本子账户 / 空集与 `null` 入口 fail-closed / 哨兵 0 不过滤 / 越权读 ⇒ 空）均经**独立端到端实测**满足，反退化三向变异全部变红（含"空集退化为全可见"这一最危险默认），基线 `310/0/0/0` 且**共享树源零改动**；**附 1 处真实缺口**（`decomposeTradePlans` 写路径读取未 scope，跨子账户写/读），已被授权任务 **`T-102`（P0）** 承载，不重复建号。
- **依据**：§T-101.2（基线四要素）、§T-101.3（探针 11 例端到端）、§T-101.4（真实 DAO SQL 层 + COUNT 披露核验）、§T-101.5（变异 A/B/C 独立复现）、§T-101.6（残余缺口运行时证实）、§T-101.7（三项边界判据）。
- **风险提示**：① `decomposeTradePlans` 跨子账户写/读为 **P0 安全性质**，已归 `T-102`，须尽快实施；② `EXP-T101-1`（`FR-PLAN-15` 端点分页/筛选完整度）请编排者核定归属。
- **建议**：`T-101` 可判**通过**；是否置 `DONE` 与是否发布由编排器/用户决定（测试席不出发布结论）。

---

# T-010 独立复核（`te-verify-t010`，2026-09-21）

> **复核对象**：`T-010`「实现用户与权限服务（岗位分离 + 子账户软隔离）」（`docs/TASKS.md:54` / `:310-325`）
> **声明状态**：「**已完成，待独立复核**」——**声明非证据**；本席**不采信产出方任何结论**，一切以**独立读取/执行**为准（变异/探针**仅在 `/tmp` 隔离副本内**做）。
> **制品锚点（本席实测，`2026-09-21T06:49:06+0800`）**：
> - `docs/TASKS.md` = `a2a8652e…`（2121 行）〔⚠️ **取证期被写**：本席 `06:46` 首读为 `6347c36e…`，见 `EXP-T010-3`〕
> - `docs/ARCHITECTURE.md` = `ebed97110c675a26d64e8f0899bd20e5`（2729 行）
> - `docs/REQUIREMENTS/AUTH.md` = `98138f8cab41f65c58f1b038729e3c8d`（394 行）
> - `java/src/main/.../web/ApiRoutes.java` = `3dec526b…`；`.../web/handler/auth/AuthRoutes.java` = `e4fd608e…`；`.../service/AuthService.java` = `80787eb6…`
> - `docs/TEST_RESULT.md` 复核前 = `c4b9fa814c35c4384daf2cfa3d99e349`（627 行）——**与派单给定值一致**
> **复核项**：`V-T010-1`~`V-T010-6`（派单 §3.1~§3.5 五必查项 + 探索）

## T-010.2 基线复取（四要素 + 共享树零改动自证）

| 项 | 值 |
|---|---|
| ① 取样时刻 | `2026-09-21T06:45:33+0800`（起）→ `06:47:00+0800`（止） |
| ② 结果 | **`Tests run: 310, Failures: 0, Errors: 0, Skipped: 0`** |
| ③ 是否排除 | **无任何排除**（无 `-Dtest`/`-Dsurefire.excludes`/skip；全量） |
| ④ 是否 `clean` | **是**（`mvn -B -o clean test`） |

- **命令**：`cd java && mvn -B -o clean test`（共享树内一次性 `clean` 全量；构建产物落 `java/target/`，**未在共享树内做任何探针**）。
- **布局陷阱规避**：探针副本按 `<name>/{java,docs}` 布局（`/tmp/te010probe/java` + `/tmp/te010probe/docs`），**未用** `cp -r java /tmp/x` 的畸形布局。
- **共享树零改动自证**：`find java/src -type f | sort | xargs md5sum` 运行**前后**各存一份 → `diff` **退出码 0、无输出**，**284 个源文件 md5 全等**。
- 与派单给定值（`310`，`2026-09-21T06:32:23` 独立复核）**一致**。

## T-010.3 REST 面：`ApiRoutes` 声明 vs `AuthRoutes` 实际注册

- **声明来源**：`web/ApiRoutes.java:71-74`（`BY_MODULE.put("AUTH", Arrays.asList(...))`）
- **注册来源**：`web/handler/auth/AuthRoutes.java:33-35`（`router.register(...)`）
- **扫描命令**（全 `java/src/main`）：`grep -rn "router.register(" java/src/main --include=*.java` —— AUTH 前缀注册**仅** `AuthRoutes.java:33/35` 两处，**无其它注册点**；辅以程序化提取（正则解析 `ApiRoutes` AUTH 块 + `AuthRoutes` 注册对）：

| 维度 | 结果 |
|---|---|
| `ApiRoutes` 声明 AUTH 路径 | **10** 条（**仅路径，无方法维度**，`ApiRoutes.java` 现值只冻结路径） |
| `AuthRoutes` 实际注册 | **2** 条（**含方法维度**）：`POST /auth/login`、`POST /auth/logout` |
| **差集（声明 − 注册）** | **8** 条（对**任意方法**均 0 注册）：`/auth/user`、`/auth/role`、`/auth/position-group`、`/auth/authorization`、`/auth/sub-account-perm`、`/auth/change-log`、`/auth/unauthorized-attempt`、`/auth/password` |
| 反向（注册 − 声明） | **∅**（无"臆造路径"问题；问题是**少注册**） |

- **是否含方法维度**：`ApiRoutes` **不含**方法（升级为 `(方法,路径)` 对是 **`T-097`** 的目标形态，`ARCHITECTURE.md:604-605` 已登记）；`AuthRoutes.register` **含**方法。故本差集是**路径维度**差集，且这 8 条**在任何方法下均未注册**。
- **范围判定（核心）**：`T-010` 的**需求追溯 = `FR-AUTH-1~18`**、**产出 = `AuthService.java` + REST `/auth/**`**（`TASKS.md:312`/`:324`）——即**整个 AUTH 模块 REST 面（10 条）**。⇒ **这 8 条差集落在 `T-010` 范围内**。
- **结论**：`T-010` 的 REST 面**完成度 = 2/10 = 20%**；**8 条未注册属 `T-010` 的完成度问题**（非新缺陷 ⇒ **不建号**，但**显著标出**）。**外部佐证**：`TASKS.md:2119`（项目经理裁定）已把同类"端点未实现（进度）"（`touchSession`/`changePassword`/`resetPassword`/`grantRole` 生产 0 调用）明确归 `T-010` 覆盖、**不新建号**；本席的 8 条差集与 4 个未接线方法同属该口径。
- **另注（不在 `T-010` 范围）**：`TASKS.md:506` 显示 `T-021`（前端）**期望对接** `/auth/user`、`/auth/role`、`/auth/authorization`、`/auth/sub-account-perm`——即前端任务依赖 `T-010` 交付这些端点。

## T-010.4 `AuthService` 方法在生产代码的调用点计数

- **范围**：`java/src/main`（**生产代码**，**不含** `java/src/test`）
- **排除项**：方法定义行（`public … <name>(…) {`）；`{@link #<name>}` / `{@link AuthService#<name>}` 形式的 **javadoc 引用**
- **命令**：`grep -rnE "\.(login|logout|assertSubAccountVisible|assertCanTrade|touchSession|changePassword|resetPassword|grantRole|visibleSubAccountIds)\(" java/src/main --include=*.java`（限定"实参调用形态 `<recv>.<method>(`"）

| 方法 | 生产调用点 | 证据 |
|---|---|---|
| `login` | **1** | `LoginHandler.java:63` `authService.login(...)` |
| `logout` | **1** | `LogoutHandler.java:36` `authService.logout(...)` |
| `assertSubAccountVisible` | **0** | 全 `src/main` 仅 `AuthService.java:479` 定义 |
| `assertCanTrade` | **0** | 仅 `:417` 定义；`:53` 为 `{@link #assertCanTrade}` javadoc 引用（**非调用，已排除**） |
| `touchSession` | **0** | 仅 `:249` 定义；`SessionAuthFilter` 未调用（见 §T-010.5 `FR-AUTH-15`） |
| `changePassword` | **0** | 仅 `:284` 定义 |
| `resetPassword` | **0** | 仅 `:330` 定义 |
| `grantRole` | **0** | 仅 `:361` 定义 |
| `visibleSubAccountIds` | **1** | `PlanService.java:1079` 经接口 `subAccountVisibility.visibleSubAccountIds(...)` 调用（**实现落 `AuthService.java:506`**）；`PlanService.java:864/942/1011` 调的是**其自身私有包装** `PlanService.java:1077`，**不计** |

> **否定性结论（"0 调用"）口径**：限定"实参调用形态"，已排除定义行与 `{@link}` javadoc 引用；`visibleSubAccountIds` 经接口多态调用**计入**（接口方法由 `AuthService` 实现）。
> **合计**：9 个方法中 **3 个有调用**（`login`/`logout`/`visibleSubAccountIds`）、**6 个 0 调用**（`assertSubAccountVisible`/`assertCanTrade`/`touchSession`/`changePassword`/`resetPassword`/`grantRole`）。其中 `assertSubAccountVisible`/`assertCanTrade` 属"已实现未接线的**强制点**"（已由 `T-102` 承载）；其余 4 个属"**端点未实现（进度）**"（已由 `T-010` 覆盖，`TASKS.md:2119`）。

## T-010.5 实质验证（逐 FR，含**实测/静态**标注）

> 基准：`docs/REQUIREMENTS/AUTH.md` md5 `98138f8cab41f65c58f1b038729e3c8d`（394 行）

**`FR-AUTH-14 用户登录与身份认证`（`AUTH.md:291-304`）**
- 验收原文（`:300-304`）：① 正确账号口令登录成功；② 口令错误返回**统一错误信息**（与"用户不存在"**完全一致**）；③ 连续 5 次失败锁定 5 分钟；④ 日志**不出现**明文口令。
- 落点：`AuthService.login`（`:160`）；统一文案 `authFailed()`（`:593`）；REST = `LoginHandler.handle`（`:56`）+ `POST /auth/login`（`AuthRoutes.java:33`）。
- **实测**：`AuthServiceTest` 的 `loginSucceeds`、`loginFailuresAreIndistinguishable`（断言 `unknown.getMessage()==wrong.getMessage()`）、`fiveFailuresLockTheAccount`、`disabledAccountIsRejected` **全绿**。
- **判定**：**验收①~④ 满足**（service 层 + REST 端到端均有）。

**`FR-AUTH-17 登录失败锁定`（`AUTH.md:336-349`）**
- 验收原文（`:345-349`）：① 连续 5 次失败后第 6 次即使口令正确也拒；② 锁定 5 分钟后可正常登录、计数清零；③ 锁定与解锁事件均可审计查询；④ 阈值与时长可经 `CONFIG` 调整。
- 落点：`AuthService.login`（`:166-193`；`LOGIN_FAIL_LIMIT=5`/`LOGIN_LOCK_MINUTES=5`）；留痕 `writeLoginLog`（`:575`）。
- **实测**：`fiveFailuresLockTheAccount` 覆盖 ①（第 5 次 `10008`；锁定期内正确口令仍 `10008`）与 ②（解锁后计数清零）。
- **判定**：**①/② 满足（实测）**；**③ 部分（**写 `t_login_log` 成功/失败已实测；"锁/解锁事件可经审计查询"**无端点**）；**④ 不满足（静态）**：阈值/时长**硬编码为常量**，**未接 `CONFIG`**（`CFG-23` 配置化未落地）。

**`FR-AUTH-8 校验岗位分离约束`（`AUTH.md:200-213`）**
- 验收原文（`:209-213`）：① 给已有交易岗角色的用户分配风控岗角色被拒绝并列出冲突角色；② 分配结算岗被拒；③ 系统内不存在绕过互斥校验的入口；④ 拒绝事件在越权尝试记录中可查询。
- 落点：`AuthService.grantRole`（`:361`）+ 私有 `conflicts`（`:543`）/`positionGroupIdsOf`（`:532`）；留痕 `recordAttempt`（`:378`）。
- **实测**：`grantRoleRejectsPositionConflict` 覆盖 ①（`10005` + `t_unauthorized_attempt`）与"非互斥岗位组可授予"。
- **判定**：**service 逻辑 ①②④ 满足（实测）**；**但 `grantRole` 生产 0 调用、无 REST 端点** ⇒ 经 REST **不可达** —— 交付面上**未交付**（属 §T-010.3 端点缺口）。

**`FR-AUTH-9 禁止企业负责人直接操盘`（`AUTH.md:215-229`）**
- 验收原文（`:224-229`）：① 给企业负责人分配交易岗角色被拒；② 企业负责人下单事前风控拒；③ 负责人可被授审批与跨子账户查看；④ **具有管理层（审批人）角色的账号尝试下单被拒绝**；⑤ 标记变更与违规尝试留痕。
- 落点：`AuthService.assertCanTrade`（`:417`）+ `isManagementRole`（`:446`）+ `hasTradingRole`（`:462`）；留痕 `recordAttempt`。
- **实测**：`leaderWithManagementRoleCannotTrade` 覆盖"`is_enterprise_leader='Y'` 且持 `APPROVER`"⇒ `10005` 且留痕。**本席另加探针**（`/tmp/te010probe`，`AuthServiceTest#probeManagementWithTradingRole`）实测：**`is_enterprise_leader='N'` 但持 `APPROVER`+`TRADER` 的账号调用 `assertCanTrade` ⇒ `NO_THROW`（被放行）**；探针输出 `PROBE isManagementRole=true / hasTradingRole=true / PROBE_RESULT=NO_THROW`。⇒ 验收 ④"**管理层账号**尝试下单被拒"在该组合下**不满足**（详见 §T-010.7 `EXP-T010-1`）。
- **口径冲突（须裁定）**：`T-010` **验收标准 3**（`TASKS.md:322`）写"`is_enterprise_leader='Y'` **且** `is_management_role='Y'`"（**合取**，与实现一致）；而 `FR-AUTH-9` 验收 4（`AUTH.md:228`）写"具有**管理层角色**的账号"（**单条件**）。**实现贴合任务验收 3，与 FR 验收 4 存在口径差**。
- **判定**：**部分**（② 由事前风控承担、不在本类；④ 见上；`assertCanTrade` 生产 0 调用 ⇒ REST 不可达）。

**`FR-AUTH-13 授予跨子账户查看权限`（`AUTH.md:276-289`）**
- 验收原文（`:285-289`）：① 为审计岗授予跨子账户查看后可见全部子账户；② 为交易岗尝试授予被拒；③ 跨子账户权限下尝试下单/审批被拒（只读）；④ 授予须双人复核并留痕。
- 落点（**读面**）：`AuthService.visibleSubAccountIds`（`:506`，`PERM_CROSS_READONLY`→哨兵 `0`）**已由 `PlanService`（`:1079`）接入读取面**（`T-100`/`T-101` 交付）；校验 `assertSubAccountVisible`（`:479`）。
- **实测**：`subAccountSoftIsolation` 覆盖"无授权拒 / `LIMITED` 放行本账户 / 过期不生效 / `CROSS_READONLY` 返回哨兵"。
- **判定**：**部分** —— ①（可见全部）**读面已接线**（计划模块，实测）；**②/③/④ 未交付**（无"授予跨子账户查看"端点、无写面只读强制；`assertSubAccountVisible` 生产 0 调用）。

**`FR-AUTH-15 用户登出与登录会话管理`（`AUTH.md:306-319`）**
- 验收原文（`:315-319`）：① 主动登出后会话失效、回退需重新登录；② **空闲**超过配置时长后会话失效并要求重新认证；③ 不存在"记住我/长期免登录"；④ 管理员强制下线操作留痕。
- 落点：`AuthService.logout`（`:228`）+ `LogoutHandler` + `POST /auth/logout`；`touchSession`（`:249`，滑动过期）。
- **实测**：`logoutInvalidatesBoth` 覆盖 ①（内存 + 库内会话均作废、未知会话幂等）⇒ **① 已交付**。
- **② 口径问题（静态）**：`SessionAuthFilter.authenticate`（`web/SessionAuthFilter.java:53-65`）**只查会话、不调用 `touchSession`**；`SessionStore.InMemory.find`（`web/SessionStore.java:61`）按 `expireTime` **绝对**判定——而 `expireTime` 在**登录时**固定为 `登录时刻+30min`（`AuthService.login:197`），`touchSession` 生产 0 调用 ⇒ **会话实为"登录后 30 分钟绝对过期"，而非"空闲 30 分钟"**（活跃**不续期**），与验收 ②"空闲超时"及 `AuthService` 自述"滑动过期"**不符**。
- ③（静态）：无"记住我"/长期免登录字段。④（静态+实测）：`logout` 只记 `LOGGED_OUT`，**无管理员强制下线方法/留痕**。
- **判定**：**部分**（① 已交付并实测；② 未达；③ 满足；④ 未交付）。

> **标注汇总**：**实测** = `FR-AUTH-14`（全）、`FR-AUTH-17`①②、`FR-AUTH-8`（逻辑）、`FR-AUTH-9`（`leader+management` 分支 + **探针反例**）、`FR-AUTH-13`（读面）、`FR-AUTH-15`①；**静态** = `FR-AUTH-17`④、`FR-AUTH-15`②③④、`FR-AUTH-13`②③④。

## T-010.6 交付面与 `FR-AUTH-1~18` 覆盖差

> 判定口径：**已交付** = 有实现落点 **且** 经 REST 可达；**部分** = 有实现落点但 REST 不可达 / 仅覆盖部分验收；**未交付** = **无实现落点**。

| FR | 实现落点（符号名） | REST 端点（`/auth/**`） | 判定 |
|---|---|---|---|
| FR-AUTH-1 新增用户 | **无**（`UserAccountDao` 有 insert 基座，`AuthService` 无 create 方法） | `/auth/user`（**未注册**） | **未交付** |
| FR-AUTH-2 修改用户 | **无**（无 updateUser；`UserAccountDao.updateById` 仅供 `resetPassword`） | `/auth/user`（**未注册**） | **未交付** |
| FR-AUTH-3 停用用户 | **无**（无 disableUser；`SessionStore.invalidateByUser` 存在但无编排） | `/auth/user`（**未注册**） | **未交付** |
| FR-AUTH-4 启用用户 | **无** | `/auth/user`（**未注册**） | **未交付** |
| FR-AUTH-5 查询用户列表 | **无**（无 listUsers） | `/auth/user`（**未注册**） | **未交付** |
| FR-AUTH-6 定义角色 | **无**（`RoleDao.findById/findByCode` 仅读） | `/auth/role`（**未注册**） | **未交付** |
| FR-AUTH-7 配置岗位互斥 | **无**（`PositionConflictDao.findConflictsOf` 仅被 `conflicts` 读用） | `/auth/position-group`（**未注册**） | **未交付** |
| FR-AUTH-8 校验岗位分离 | `grantRole`（`:361`）+ `conflicts`（`:543`） | **无**（`grantRole` 生产 0 调用） | **部分** |
| FR-AUTH-9 禁负责人操盘 | `assertCanTrade`（`:417`）+ `isManagementRole`（`:446`）+ `hasTradingRole`（`:462`） | **无**（`assertCanTrade` 生产 0 调用） | **部分**（另见 `EXP-T010-1` 口径差） |
| FR-AUTH-10 配置人员授权 | **无**（无 grantAuthorization） | `/auth/authorization`（**未注册**） | **未交付** |
| FR-AUTH-11 授权变更留痕 | **无**（无查询方法） | `/auth/change-log`（**未注册**） | **未交付** |
| FR-AUTH-12 配置子账户数据权限 | **无**（无 assignSubAccountPerm） | `/auth/sub-account-perm`（**未注册**） | **未交付** |
| FR-AUTH-13 跨子账户查看 | `visibleSubAccountIds`（`:506`，读面已接线 `PlanService:1079`）+ `assertSubAccountVisible`（`:479`，0 调用） | `/auth/sub-account-perm`（**未注册**） | **部分**（读面已交付；授予/只读强制未交付） |
| FR-AUTH-14 登录 | `login`（`:160`）+ `LoginHandler` | `POST /auth/login`（**已注册**） | **已交付** |
| FR-AUTH-15 登出/会话 | `logout`（`:228`）+ `LogoutHandler`；`touchSession`（`:249`，0 调用） | `POST /auth/logout`（**已注册**） | **部分**（登出已交付；空闲超时 ② 未达，见 §T-010.5） |
| FR-AUTH-16 口令管理 | `changePassword`（`:284`）+ `resetPassword`（`:330`）（均 0 调用）；`PasswordHasher` | `/auth/password`（**未注册**） | **部分** |
| FR-AUTH-17 登录失败锁定 | `login` 内 `:166-193` | `POST /auth/login`（**已注册**） | **已交付**（①②；③④ 见 §T-010.5） |
| FR-AUTH-18 查询变更/越权记录 | **无** | `/auth/change-log`、`/auth/unauthorized-attempt`（**均未注册**） | **未交付** |

> **汇总**：18 条 FR —— **已交付 2**（14、17）、**部分 5**（8、9、13、15、16）、**未交付 11**（1~7、10~12、18）。
> **交付面（REST `/auth/**`）**：声明 **10** / 注册 **2** / **差 8**。
> 不确定项：**无**（每条均有"落点符号名 + 端点注册状态"证据；**未用猜测**）。

## T-010.7 探索性发现

| 编号 | 发现 | 证据 | 处理 |
|---|---|---|---|
| `EXP-T010-1` | **`assertCanTrade` 对"管理者却非企业负责人"不拒绝**：探针实测 `isEnterpriseLeader='N'` 且持 `APPROVER`+`TRADER` 的账号 `assertCanTrade` **`NO_THROW`**。与 `FR-AUTH-9` 验收 4（"管理层账号尝试下单被拒"）不合；与 `T-010` 验收 3（"leader **且** management"）一致。**可达性**：互斥判据取自 `t_position_conflict`，而该表在 `docs/DEPLOY/schema/*.sql` **无任何 `INSERT` 种子**（全部种子仅 `t_sub_account`/`t_product`，`data-init.sql:26/59`）⇒ 互斥矩阵为空 ⇒ `APPROVER+TRADER` 组合**可经 `grantRole` 达成**。 | 探针输出 `PROBE_RESULT=NO_THROW`；`AuthService.java:425`（`leader && management`）；`data-init.sql` 种子扫描 | **口径冲突 / 潜在缺口**（非 `T-010` 单方可判：FR 验收 4 vs 任务验收 3 措辞冲突）。`assertCanTrade` 的接线/语义**已由 `T-102` 承载**（`TASKS.md:146`/`:2119`）⇒ **本席不重复建号**；建议编排者裁定是否把 `T-102` 验收面扩至"管理层判定单条件"。 |
| `EXP-T010-2` | **路由判据现为"路径级·子集"，对"少注册"结构性失明**：`ModuleRoutesConsistencyTest.registeredPathsAreContractual` 仅断言"已注册 **⊆** `ApiRoutes`"，且**覆盖下限硬编码 AUTH=2**（`ModuleRoutesConsistencyTest.java:51-53`）；`noOrphanHandlers`（`:124`）只能发现"声明了 `PATH` 却未被引用"的 handler，**无法**发现"`ApiRoutes` 声明了却未注册、且未写 handler"的 8 条 ⇒ **8 端点缺口逃过全部现有判据**。 | `ModuleRoutesConsistencyTest.java:51-53,94-106,124-156`；`ApiRoutes` 10 vs `AuthRoutes` 2 | **非缺陷**（架构已登记该判据为**目标形态**，落点 **`T-097`**，`ARCHITECTURE.md:604-605`）。**但说明：`T-010` 的 8 端点缺口不会被 CI 兜住**，须靠人工/复核。 |
| `EXP-T010-3` | **取证期 `TASKS.md` 被其他席位写入**：本席 `06:46` 首读 = `6347c36e…`；`06:49` 复测 = `a2a8652e…`（`pm-t103` 所为：`T-101`→`DONE`、登记 `T-103` 等）。**`T-010` 条目（`:310-325`）内容未变**（已复读核对）。 | `md5sum docs/TASKS.md`（两次） | 按要求：**取证锚定"时点 + md5"**（见头部）；`T-010` 判定**不受影响**。 |
| `EXP-T010-4` | **`touchSession` 接线跨写域**：`AuthService`（`#java2` 产出）提供 `touchSession`，但唯一合理接线点 `SessionAuthFilter`（`web/`）属 **`#java` 写权**。⇒ `FR-AUTH-15②`"空闲滑动超时"的**接线本身跨写域**，非 `#java2` 单方可在"不改 `web/` 框架类"前提下完成。 | `SessionAuthFilter.java:53-65`（无 `touchSession` 调用）；`AuthService.java:249` | 记录以说明 `FR-AUTH-15②` 未达的**结构性原因**；归属请编排者裁定。 |

## T-010.8 缺陷清单

**新建 BUG：0 条。** 扫描口径：`ls docs/BUGS/` → 仅既有 `BUG-001.md`（本次**未新建**）。

| 类别 | 数量 | 说明 |
|---|---|---|
| **完成度缺口（本任务范围，不建号）** | **1 类** | REST `/auth/**` 声明 10 / 注册 2 / **差 8**（§T-010.3）；`FR-AUTH-1~18` 中 **11 条未交付 + 5 条部分**（§T-010.6）。按 `TASKS.md:2119` 口径，端点未实现归 `T-010` 覆盖 ⇒ **不重复建号** |
| **口径冲突 / 潜在缺口（归属待裁定）** | **1** | `EXP-T010-1`（`assertCanTrade` 管理层单条件）——`assertCanTrade` 已由 **`T-102`** 承载 ⇒ **不重复建号** |
| **真实缺陷（本任务单项）** | **0** | — |
| **证据不足** | **0** | — |
| **环境限制** | **1** | **无 DM8 实机**：全部验证基于隔离副本的假 DAO/JUnit（`310` 全绿），**未**在 DM8 实例上做真实端到端（尤其 `FR-AUTH-8` 的 `t_position_conflict` 空表可达性无法在真机复算） |

> 说明：若编排者认为 `EXP-T010-1` 应单列，按现有最大号 +1 应为 **`BUG-002`**（`docs/BUGS/` 现仅 `BUG-001.md`）。**本席依"不重复建号"纪律默认归 `T-102` 并在此显著标注，未开单。**

## T-010.9 度量数据

| 指标 | 数值 |
|---|---|
| 复核必查项 | 5（派单 §3.1~§3.5）+ 探索 |
| 已执行 | **5/5（执行率 100%）** |
| 基线（共享树 `clean` 全量） | **`310 / 0 / 0 / 0`**（`06:45:33→06:47:00+0800`；无排除；`mvn -B -o clean test`） |
| 共享树源改动 | **0**（284 文件 md5 前后全等，`diff` 无输出） |
| 独立探针 | **1**（`AuthServiceTest#probeManagementWithTradingRole`，`/tmp` 隔离副本内） |
| REST 端点（AUTH） | 声明 **10** / 注册 **2** / 差 **8** |
| `AuthService` 生产调用点 | 9 方法中 **3 有调用**、**6 为 0** |
| FR-AUTH 交付判定 | 已交付 **2** / 部分 **5** / 未交付 **11**（共 18） |
| 新建缺陷 | **0**（完成度缺口 1 类 + 口径冲突 1，均归既有任务） |
| 环境限制 | **1**（无 DM8 实机） |

## T-010.10 结论

- **一行结论**：**`T-010` 不通过** —— 其声明产出含 **REST `/auth/**`**（AUTH 模块 **10** 条路径），实际**仅注册 2 条**（`login`/`logout`，**差 8**）；需求追溯 `FR-AUTH-1~18` 中**仅 2 条已交付**、5 条部分、**11 条未交付**（无实现落点）；`AuthService` 9 个方法中 **6 个生产 0 调用**（含 `assertSubAccountVisible`/`assertCanTrade` 两个未接线强制点、`touchSession` 致"空闲超时"实为"绝对过期"）。**其余受测项**（登录/锁定/岗位互斥/负责人禁操盘/子账户读面隔离）在 **service 层逻辑均实测正确**，但**多数经 REST 不可达**。
- **依据**：§T-010.2（基线四要素 + 零改动自证）、§T-010.3（REST 差集 8 + 范围判定）、§T-010.4（调用点计数）、§T-010.5（逐 FR 实测/静态）、§T-010.6（覆盖表）、§T-010.7（探索 4 项）。
- **风险提示**：① `EXP-T010-1`（管理层单条件禁操盘）与 `FR-AUTH-9` 验收 4 冲突，`assertCanTrade` 接线在 `T-102`，建议同步裁定语义；② `FR-AUTH-15②`"空闲超时"未达（`touchSession` 未接线，接线点属 `#java` 框架类 `SessionAuthFilter`），须编排者协调写域；③ `t_position_conflict`/`t_role`/`t_position_group` **无种子数据**，`FR-AUTH-7`/`FR-AUTH-8` 的默认互斥集在全新部署下为空——**归属待定**（DDL/初始化任务）。
- **建议**：`T-010` 应**退回「未完成」**（REST `/auth/**` 完成度 **20%**、`FR-AUTH-1~18` 交付 **2/18**）。是否退回、及"完成度缺口"是否需拆细分派（如把 `/auth/user`、`/auth/role`、`/auth/sub-account-perm` 等另立号或并入 `T-021` 依赖链），由**编排器/用户**决定（**测试席不出发布结论**）。

---

# `T-103` 专项独立复核（`te-verify-t103`，2026-09-21）

> 范围：`GET /plan/{id}/trade-plans` 补**分页/筛选/排序**（依 `FR-PLAN-15`「输入」列，`REQUIREMENTS/PLAN.md:321`）。
> **不采信产出方结论**；后端 + 前端两侧均独立取证。**所有变异 / 探针一律在 `/tmp` 隔离副本内做**。

## T-103.1 执行概况

| 项 | 内容 |
|----|------|
| 代码版本 | 仓库**无 git**（`git rev-parse` → `fatal: not a git repository`）。以**源码清单 md5 + 文件 md5 + mtime** 锚定 |
| 源码锚点 | `java/src/**/*.java`（285 文件）清单 md5 = **`9ce6e9112a5783bc962f1710b094d98a`**（冻结于 **2026-09-21T07:27+0800**；共享树 × 隔离副本**逐字节一致**，见 §T-103.2） |
| 关键文件 md5 | `web/ApiRoutes.java` = `3dec526b47b5b444d57de8898a07e030`；`pages/plan.js` mtime `07:23:47`；`core/format.js` mtime `07:23:31` |
| 测试环境 | Linux / JDK **11.0.32** / Maven **3.9.12** / `maven-surefire-plugin` **3.2.5** / 离线（`-o`）/ **无 DM8 实例、无 DM 驱动**；前端 Node **v22.22.1** + 自建最小 DOM 垫片 |
| 执行人 | 测试工程师（`te-verify-t103`） |
| 执行时间 | 2026-09-21 **07:27–07:38 +0800** |
| 写入边界 | **本席仅写** `docs/TEST_RESULT.md`（本节）与 `docs/BUGS/**`（本节**未开单**）；未改 `java/**` `web/**` `docs/` 其他文件。**共享树 `mvn -B -o clean test` 的写入范围仅 `java/target/`（构建产物）**，源码清单前后 285/285 `OK`（见 §T-103.2） |

## T-103.2 基线复取（`V-T103-1`）—— 四要素 + 共享树零改动自证

**布局陷阱规避**：隔离副本布局 = `/tmp/v103/java` + `/tmp/v103/docs → 真实 docs`（`M1ProductSpotPlanSchemaTest` 等按「向上找到含 `docs/DATA_MODEL.md` 的根」解析路径；`cp -r java /tmp/x` 会拼出 `/tmp/x/java/...` 而**假红 4 例**——本席按正确布局）。隔离副本与共享树**源清单 md5 相同**：`9ce6e9112a5783bc962f1710b094d98a`（285 文件，`diff` 文件清单**完全一致**）。

| 口径 | ① 取样时刻（+0800） | ② Tests run | ③ 排除 | ④ clean |
|------|--------------------|-------------|--------|---------|
| **隔离副本** `/tmp/v103/java` | `07:28:05` → `07:29:29` | **`Tests run: 319, Failures: 0, Errors: 0, Skipped: 0`**，`BUILD SUCCESS` | **无**（`pom.xml` surefire 段仅 `argLine=-Dfile.encoding=UTF-8`；无 `<excludes>`） | **是**（`mvn -B -o clean test`） |
| **共享树** `/root/myspace/hedge-guard/java` | `07:36:06` → `07:37:27` | **`Tests run: 319, Failures: 0, Errors: 0, Skipped: 0`**，`BUILD SUCCESS` | **无** | **是** |

> **产出方自述**：隔离副本 `319/0/0/0`（07:18:40–07:20:00）+ 共享树 `319/0/0/0`（07:16:43），基线 310（+9）。**本席实测总数 319 与之一致**；**「基线 310」为产出方历史值，本席无仓库历史可比对（无 git），记为未独立复现**。

**共享树零改动自证（本席全程唯一写入 = `docs/TEST_RESULT.md` 本节）**：
- 冻结清单 `/tmp/v103-shared-src-manifest-before.txt`（285 行，07:27 生成）→ 会话末 `md5sum -c`：**`OK=285 / FAILED=0 / MISSING=0`**。
- 聚合 md5 前（`9ce6e911…`）= 后（`9ce6e911…`）；`ApiRoutes.java` md5 前=后=`3dec526b…`；`web/` 文件数前=后=**27**。
- 命令：`find java/src -name '*.java' | sort | xargs md5sum | md5sum`（`cd java` 时用 `find src`，两口径均得 `9ce6e911…`）。

## T-103.3 隔离 × 分页（`V-T103-2`）—— 独立探针（**非产出方用例**）

自建 `com.hedgeguard.verify.V103ProbeTest`（以**真实 `DmTradePlanDao` + 记录型假 JDBC** 驱动），`mvn -B -o test -Dtest=V103ProbeTest` → **9/9 通过**。各分支：

| ID | 分支 | 源码符号（实测/静态） | 实测结果 |
|----|------|----------------------|---------|
| P-1 | **空 scope** ⇒ **COUNT 与 SELECT 两条 SQL** 均含恒假 `sub_account_id IN (-1)` | `PlanService.listTradePlans:917` → `SubAccountScope.of` → `AbstractDmDao.findPageScoped:250` → `findPage:158` + `count:172`（**实测**） | 两 SQL 均含 `(hedge_plan_id = ?) AND sub_account_id IN (-1)` |
| P-2 | **非空 scope [11,22]** ⇒ 两条 SQL 均 `sub_account_id IN (?,?)` 且按序绑定 | 同上（**实测**） | COUNT 参数 `[7L,11,22]`；SELECT `[7L,11,22,20,0]` |
| P-3 | **哨兵 0**（跨账户只读）⇒ 两条 SQL **均不含** `sub_account_id IN` | `PlanService:912` `visible.contains(ALL_ACCOUNT_SENTINEL)` → 走未 scoped `findPage`（**实测**） | 两 SQL 均无 `sub_account_id IN` |
| P-4 | **可见集合 `null`** ⇒ fail-closed（恒假） | `PlanService.visibleSubAccountIds:1147` `ids==null ⇒ emptyList`（**实测**） | 两 SQL 均 `IN (-1)` |
| P-5 | **取数入口对象 `null`** ⇒ fail-closed（恒假） | `:1148` `subAccountVisibility==null ⇒ emptyList`（**实测**） | 两 SQL 均 `IN (-1)` |

> **结论**：**受限身份 ⇒ items 与 total 均为过滤后值**（`total` 取自**带 scope 的 COUNT 子查询**，结构上保证）；**空集 / null ⇒ 恒假、不退化为全可见**；**哨兵 0 ⇒ 不过滤**。T-101 建立的隔离不变量在 `T-103` 分页化后**未被削弱**。**未做**：DM8 实机执行（无实例）。

## T-103.4 真实 DAO 两条 SQL（`V-T103-3`）—— 生产 SQL 文本实测

`DmTradePlanDao.findPageScoped` 产出的**实际两条 SQL**（P-1 打印，逐字）：

- **COUNT**：`SELECT COUNT(*) AS cnt FROM (SELECT id FROM t_trade_plan WHERE (hedge_plan_id = ?) AND sub_account_id IN (-1)) hg_cnt`
- **SELECT**：`SELECT id, sub_account_id, trade_plan_no, hedge_plan_id, exposure_id, product_id, instrument_id, instrument_type, direction, plan_qty, executed_qty, ordered_qty, exec_deadline, status, strategy_id, created_by, created_time, updated_by, updated_time, row_version FROM t_trade_plan WHERE (hedge_plan_id = ?) AND sub_account_id IN (-1) ORDER BY exec_deadline ASC LIMIT ? OFFSET ?`

**两条均带 scope**（`AbstractDmDao.findPageScoped:253` 把 `scope.sql()` 并进 `where`，`findPage:162` 的 count 与 `:167` 的 SELECT 共用同一 `where`/`args`）—— 即 `total` 与 `items` **同源过滤**，无"总数含越权行"的可能。

## T-103.5 【最重要】分页排序稳定性（`V-T103-4`）—— 独立复现产出方自披露 (b)

**代码事实（P-6 实测）**：真实 SELECT 的排序子句**逐字**为 `ORDER BY exec_deadline ASC`（**单列、无唯一 tie-break**），随后 `LIMIT ? OFFSET ?`。对照：**旧非分页版** `DmTradePlanDao.findByHedgePlan:121` / `findByHedgePlanScoped:136` 为 `ORDER BY exec_deadline ASC, id ASC`（**带 tie-break**）—— 即分页化后**丢了唯一键**，属**同模块内的排序回退**。

**可复现的失败模式（B-2 实测，驱动真实 `DmTradePlanDao`，假引擎按 SQL 语义求值 ORDER BY / LIMIT / OFFSET）**：15 条记录 `exec_deadline` **全相同**、`size=10`：

```
[B-2] page1 ids = [1,2,3,4,5,6,7,8,9,10] ; page2 ids = [5,4,3,2,1]
[B-2] 跨页重复 id = [1,2,3,4,5] ; 跨页漏掉 id = [11,12,13,14,15]
```

- **B-1（对照）**：引擎对并列键返回**一致**顺序 ⇒ 第 1/2 页 = `[1..10]` / `[11..15]`，并集 15、**无重复无遗漏**（绿）。
- **B-3（判据验证）**：给排序**补唯一 tie-break（`id`）** ⇒ 无论物理顺序如何，两页均 `[1..10]` / `[11..15]`，**无重复无遗漏**。**这正是 `T-096` 验收 5 所要求的反退化判据。**

### 结论：**是"真实缺陷（代码级）"，还是"理论风险"？**

- **代码层：已成立的真实缺陷** —— 生产 SELECT 的 `ORDER BY` **无唯一 tie-break**（可逐字复现），使**用 `LIMIT/OFFSET` 的分页在并列键上不保证跨页一致**；且**旧版本带 `id ASC` tie-break、分页版丢失**，属回退。**失败模式（重复 + 漏行）已用真实 DAO 复现**（B-2）。**非"看起来可能"式猜测**。
- **运行时：未在目标库显形（环境限制）** —— 是否被用户观测到，取决于 DM8 执行计划对**并列键返回顺序是否在两次分页请求间保持一致**；**本环境无 DM8 实例**，**未在真库复现重复/漏行**。
- **可判定判据（照做即可判定）**：在 DM8（或任一 SQL 引擎）上，插入 **≥ `size+1` 条 `exec_deadline` 相同**、其余不同的记录；以**同一条查询**取 `page=1` 与 `page=2`（同 `size`/`sort`）；断言**两页 `id` 并集基数 = 两页条目数之和**（无重复）**且**等于**该条件下的过滤后总数**（无遗漏）。若任一不成立 ⇒ 缺陷显形。**静态等价判据**：分页 SELECT 的 `ORDER BY` 末键必须**唯一**（含主键）；当前 `ORDER BY exec_deadline ASC` **不满足**。
- **归属**：**已由授权任务 `T-096` 承载**（`TASKS.md:140`/`:1815` 明确其验收 5 = 「构造两个同排序值元素，断言翻页前后无重复/无遗漏；当前单列 ORDER BY 下该用例必红」）。**故本席不另开 BUG 号**（依派单 §4）。

## T-103.6 反退化独立验证（`V-T103-5`）—— 不采信自述，两向变异

在**一次性副本** `/tmp/v103-mut`（含本席探针）内做两向变异，**不在共享树内**：

| 变异 | 施加点 | 结果 | 判定 |
|------|--------|------|------|
| **MUT-1 移除子账户过滤** | `PlanService.listTradePlans` 恒走未 scoped `findPage` | `TradePlanSubAccountIsolationTest` **7 跑 6 红**；`DmTradePlanDaoScopedSqlTest` **4 跑 1 红**；本席 `V103ProbeTest` **9 跑 2 红**（P-4/P-5 fail-closed 红） | **隔离反退化成立** |
| **MUT-2 移除分页** | `DmDialect.paginate` 去 `LIMIT ? OFFSET ?` | `DmTradePlanDaoScopedSqlTest` **4 跑 2 红**（`SELECT 须分页` 断言红）；本席 `V103ProbeTest` **9 跑 1 红**（P-6 ORDER BY+LIMIT 红） | **分页反退化成立** |

> **注**：产出方用例 `TradePlanListQueryTest`（假 DAO）在 MUT-2 下**不红**——它只断言交给 DAO 的 `PageQuery`，不触 SQL；**分页的反退化实际由 `DmTradePlanDaoScopedSqlTest`（真 DAO）承担**。两向变异**均有测试变红**，产出方「两向均变红」**成立**。

## T-103.7 前端侧独立复核（`V-T103-6`）

方法：`/tmp/v103-fe` 复制 `web/` + `{"type":"module"}`，自建最小 DOM 垫片，**加载真实 `core/*` 与 `pages/plan.js`**，桩 `HG.api.get`。

- **运行时断言：`PASS=22 / FAIL=0`**（自建 `run.mjs`，**独立于产出方**）：
  - **S-1 末页按 `items.length` 渲染**：`total=57 / size=20 / 末页回 5 条` ⇒ **渲染 5 行（非 `57-40=17`）**；分页信息「共 57 条，第 3 / 3 页」；**末页「下一页」禁用、「上一页」可用**。
  - **S-1b 第 1 页**：`total=57/size=20/回 20 条` ⇒ 渲染 20 行；「共 57 条，第 1 / 3 页」；「上一页」禁用、「下一页」可用。
  - **S-2 空态**：`items` 空 ⇒ 出现「暂无数据」，**未误报「加载失败」**。
  - **S-3 阻断类错误四项齐全**：`ruleId/current/threshold/nextStep` 四行**均渲染**且值正确（经 `errmsg.render`）。
  - **S-4 旧 bare 数组回退**：仍渲染 3 行 + 「共 3 条，第 1 / 1 页」。
  - **S-5 `size` 必须显式发送**：实取数查询串含 **`page`、`size=20`、`sort=exec_deadline,asc`**（不依赖服务端默认 20）。
- **`decSub`（字符串化十进制减法）独立正确性**：`PASS=24 / FAIL=0`（`decsub.mjs`）——含**负数、不定小数位、进位/借位、`>2^53` 大数**，并与 **BigInt 参考实现随机对照 5000 组 0 失败**；**证伪对照**：`Number("9007199254740993")-1 = ...991`（错），`decSub = ...992`（对）。**本席未能证伪 `decSub`**（判定：正确）。
- **`:29` 判定复核**：读 `web/js/pages/plan.js`，**`:29` = `function loadProductsOnce() {`**，其取数入口 **`:32` `HG.api.get(HG.routes.PRODUCT.LIST)` = `/product`** ⇒ **与 `trade-plans` 无关**。**产出方"`:29` 与 trade-plans 无关"的判定成立**（`:179` 位于详情页「待补区块」banner，亦与 trade-plans 无直接关系）。
- **仅静态/未实测（据实登记）**：真实 `fetch` 联调、CSS 视觉、键盘可达性。

## T-103.8 陈旧注释与契约核对（`V-T103-7`）

- **陈旧契约陈述扫描**：`grep -rn 'bare' web/js/pages/plan.js` → **2 处，均在 :186 / :193**，内容为「**`T-103` 起，此前为 bare 数组**」「**兼容旧「bare 数组」（服务端若回退，界面不崩）**」——**均为正确现状描述（历史/回退说明），非陈旧契约**。`:9` 注释已更新为「**PageData 分页信封**」。**陈旧残留 = 0**。
- **`ApiRoutes.java` md5**：`3dec526b47b5b444d57de8898a07e030`（与产出方自述**一致**，未变）。
- **`TEMP-` 扫描**：`grep -rc 'TEMP-' java/src --include=*.java` → **命中总数 = 0**（`grep -rn` 亦无输出）。
- **零构建链**：`grep -rn 'require(|node_modules|webpack|rollup|vite|babel|from http' web/` → **1 处**，系 `web/index.html:4` 的**说明性注释**（文字里出现「无 package.json / node_modules / 打包产物」）；**实际构建链产物**：`web/` 下 `package.json`/`node_modules`/`*.min.js` = **0**；`web/index.html` 仅 `<script type="module" src="/web/js/app.js">`（本地）。**实质 0 命中成立**。
- **`E5` 判据**：`bash tools/arch-check/scan_java_sql.sh` → **`E5 通过：dao 之外未发现 SQL（退出码 0）`**（扫 115 文件，排除 `**/dao/**`）。
- **`web/` 文件数**：**27**（与产出方自述一致）。
- **(c) 项**：`grep -rn '.findByHedgePlanScoped(' java/src/main` → **0 处调用**（仅 `TradePlanDao.java:34` 声明 + `DmTradePlanDao.java:133` 实现）⇒ **属实**。
- **(a) 项**：服务端检索 `remainingQty|limitReached|已达上限` → 在**交易计划视图**无落点（命中项均在 `ParamDispatchServiceImpl`/`WsServer`，与本 FR 无关）⇒ **服务端"剩余可下单量 / 已达上限标记"未实现，属实**（前端有客户端展示，但服务端字段缺位，归 `T-104`）。
- **(e) 项**：`PlanService:909-910` 实现为 `exec_deadline >= ?` **且** `exec_deadline <= ?` ⇒ **闭区间**（同值即精确匹配），**属实**。

## T-103.9 交叉核对表（产出方断言 → 本席实测 → 判定）

### 后端（`backend-engineer`）

| # | 产出方断言 | 本席实测 | 判定 |
|---|-----------|---------|------|
| 1 | 隔离副本 clean 全量 **319/0/0/0**（无排除） | `/tmp/v103`：**319/0/0/0**，无排除（07:28:05–07:29:29） | **符合** |
| 2 | 共享树 clean **319/0/0/0** | 共享树：**319/0/0/0**（07:36:06–07:37:27） | **符合** |
| 3 | 基线 310，+9 | 现测 **319**；**「310」无仓库历史可比对（无 git）** | **无法判定（仅 319 符合）** |
| 4 | 信封 `List → PageData{items,total,page,size}` | H-1：`data` 含 `items/total/page/size`，`items` 长 = DAO 条数 | **符合** |
| 5 | 新增 `page/size/sort` + 筛选 `subAccountId/instrumentId/status/execDeadlineFrom/To` | H-4/H-5：逐项透传，5 类筛选落占位符 where + 按序 args | **符合** |
| 6 | `size ∈ {10,20,50,100}` 默认 20 | H-3：10/20/50/100 通过；15/0/101 → 400+10001；H-2 默认 20 | **符合** |
| 7 | 默认排序 `exec_deadline` 升序 | H-2 + P-6：`sortField=exec_deadline`、`ascending=true`、SQL `ORDER BY exec_deadline ASC` | **符合** |
| 8 | 反退化两方向（移除过滤 / 移除分页）均变红 | MUT-1 红（6+1+2 例）；MUT-2 红（2+1 例） | **符合** |
| 9 | 分页后**独立 COUNT**；COUNT 与 SELECT **均带 scope**；空 scope ⇒ 两条 `IN(-1)`；哨兵 0 ⇒ 两条不含 | P-1/P-2/P-3 逐条实测（见 §T-103.4） | **符合** |
| 10 | `total` = 过滤后总数（假 + 真 DAO 双证） | COUNT 子查询与 SELECT **共用同一 `where`+`scope`**（结构保证）+ P-1 实测 | **符合** |
| 11 | `ApiRoutes.java` md5 `3dec526b…` 未变 | `md5sum` = `3dec526b47b5b444d57de8898a07e030` | **符合** |
| 12 | `E5` 退出码 0 | `scan_java_sql.sh` → 退出码 **0** | **符合** |
| **自披露 (a)** | 剩余可下单量 / 已达上限标记**未实现** | 服务端视图无 `remainingQty`/`limitReached` 落点（§T-103.8） | **符合（属实，归 `T-104`）** |
| **自披露 (b)** | 分页 `ORDER BY` **仅单列、无 tie-break** ⇒ 跨页可能重复/漏行 | P-6 逐字证实单列；B-2 **真实 DAO 复现**重复 `[1..5]` + 漏行 `[11..15]`（§T-103.5） | **符合（属实，归 `T-096`）** |
| **自披露 (c)** | `findByHedgePlanScoped` 生产侧**无调用者** | `src/main` 内 `0` 处调用（仅声明+实现） | **符合（属实）** |
| **自披露 (e)** | 执行期限实现为**闭区间** `execDeadlineFrom/To` | `:909-910` `>= ?` 且 `<= ?` | **符合（属实）** |

### 前端（`frontend-engineer`）

| # | 产出方断言 | 本席实测 | 判定 |
|---|-----------|---------|------|
| F1 | 改 **2 文件**：`pages/plan.js` + `core/format.js` | 二者 mtime `07:23:*`（T-103 窗口）显著新于 `spot.js`(23:55)/`api.js`(23:50)；内容含新增分页/`decSub`。**无 git，不做逐文件 diff** | **符合（内容+mtime）** |
| F2 | `web/` 仍 **27** 文件 | `find web -type f | wc -l` = **27** | **符合** |
| F3 | 运行时 `PASS=48 / FAIL=0` | 自建 `run.mjs`：**PASS=22 / FAIL=0**（断言粒度不同） | **符合（本席 22 项全绿）** |
| F4 | 大总数第 1 页渲染 20 行 / 「共 57 条，第 1/3 页」/ 首末页按钮禁用 | S-1b：20 行 / 「共 57 条，第 1 / 3 页」/ 上一页禁用 | **符合** |
| F5 | 翻页带 `page` + **显式 `size`** + `sort=exec_deadline,asc` | S-5：查询串含 `page`、`size=20`、`sort=exec_deadline,asc` | **符合** |
| F6 | 末页按 `items.length` 渲染（第 3 页回 5 ⇒ 渲染 5） | S-1：渲染 **5** 行 | **符合** |
| F7 | 空态「暂无数据」未误报失败 | S-2：有「暂无数据」、无「加载失败」 | **符合** |
| F8 | 错误态阻断四项齐全 | S-3：四项 + 值均渲染 | **符合** |
| F9 | 旧 bare 数组仍可渲染 | S-4：3 行 + 「共 3 条，第 1 / 1 页」 | **符合** |
| F10 | 零构建链两条命令 **0 命中** | 实质 0（1 处为说明性注释，见 §T-103.8） | **符合（实质 0）** |
| F11 | 仅静态/未实测：真实 `fetch`、CSS 视觉、键盘可达性 | 本席同样**未能实测**（无浏览器/后端联调） | **符合（均为未实测）** |
| F12 | `decSub` 独立正确（含负数/大数等） | `decsub.mjs` **PASS=24 / FAIL=0**（含 BigInt 参考 5000 组随机对照 0 失败） | **符合** |
| F13 | 对后端 `:29` 的判定：与 trade-plans 无关 | `:29`=`loadProductsOnce()`，`:32` 读 `/product` ⇒ **无关** | **符合（判定成立）** |

## T-103.10 缺陷清单

**新建缺陷单：0 条。**

| 缺陷编号 | 标题 | 级别 | 责任人 | 状态 | 归属说明 |
|---------|------|------|--------|------|---------|
| —（无） | — | — | — | — | T-103 自身验收项无缺陷；发现的两项未完成/风险**均已有授权任务承载**，依派单 §4 **不重复建号** |

**扫描/判定口径（否定性结论必附）**：
- 「无新缺陷」= 后端 12 项断言 + 前端 13 项断言 + 4 项自披露**逐条独立实测/静态核对**后，无「不符」项；唯一「无法判定」为「基线 310」（无 git 历史）。
- **不计入缺陷、但登记风险**：① (b) 分页排序稳定性——**归 `T-096`**；② (a) 剩余可下单量 / 已达上限——**归 `T-104`**。
- **`docs/BUGS/` 现存最大编号 = `BUG-001`**（`ls docs/BUGS/` → 仅 `BUG-001.md`）；本次**不占号**。

## T-103.11 探索性发现

| 编号 | 发现内容 | 处置 |
|------|---------|------|
| `EXP-T103-1` | **归一化容错边界**：`normalizeTradePlanPage`（`plan.js:196`）对「`data` 为真值但 `items` 非数组」（如 `{total:5}`）会 fallback 到 `items:[]`，界面走**空态**而非错误态——**可能把「服务端契约异常」显示为「无数据」**。当前服务端恒回 `PageData`，**未构成实际缺陷**；建议 `T-096`/后续前端任务评估是否需区分。 | 仅登记，**不建单**（无现行触发路径） |
| `EXP-T103-2` | **前端客户端计算「已达上限」**（`plan.js:304-307`，`remaining===0/-0…`）与 `FR-PLAN-15`「处理规则」的**服务端标记**存在**双实现风险**：`T-104` 落地服务端 `limitReached` 后，前端应改为**读服务端字段**而非自算，否则口径可能漂移。 | 提示 `T-104`，**不建单** |
| `EXP-T103-3` | **产出方「48 项」运行时断言未被完整复现**（本席以 22 项覆盖核心路径）；其 harness 未随交付落入仓库（`web/` 内无测试文件），**不可复跑**。 | 记为**证据可复现性缺口**（非产品缺陷）；**不建单** |

## T-103.12 度量数据

| 指标 | 数值 |
|------|------|
| 独立探针（后端，自建） | **14** 项（`V103ProbeTest` 9 + `V103HttpProbeTest` 5），全绿 |
| 独立探针（前端，自建） | **22** 项运行时 + **24** 项 `decSub`，全绿 |
| 复跑产出方用例（隔离副本全量） | **319 / 0 / 0 / 0** |
| 反退化变异 | 2 向（MUT-1 红 9 例 / MUT-2 红 3 例） |
| 交叉核对断言 | 后端 **16**（12 断言 + 4 自披露）+ 前端 **13**；**符合 28 / 不符 0 / 无法判定 1** |
| 新建缺陷 | **0**（风险 2 项均归既有任务 `T-096`/`T-104`） |
| 环境限制 | **1**（无 DM8 实机 ⇒ (b) 未在真库显形） |

## T-103.13 结论

- **一行结论**：**`T-103` 有条件通过** —— 其 **8 项验收标准逐条独立实测通过**（分页/筛选/排序参数、`PageData` 信封、真实 DAO 两条 SQL 均带 scope、隔离不变量未削弱、默认 `exec_deadline` 升序、两向反退化均变红、隔离副本与共享树 `mvn -B -o clean test` 各 **319/0/0/0** 无排除）；前端 22 项运行时 + `decSub` 24 项亦全绿。**条件**：其自披露的未完成项须由既有任务收口 —— **(1) 分页排序稳定性归 `T-096`**（当前 `ORDER BY exec_deadline ASC` 单列无 tie-break，**失败模式已用真实 DAO 复现**：并列键顺序不确定时跨页重复 `[1..5]`、漏行 `[11..15]`；**已由 `T-096` 授权承载，不另开单**）；**(2) 剩余可下单量 / 已达上限标记归 `T-104`**。
- **依据**：§T-103.2（基线四要素 + 共享树 285/285 `OK` 零改动自证）、§T-103.3/§T-103.4（隔离×分页 + 真实 DAO 两 SQL 实测）、§T-103.5（(b) 独立复现 + 可判定判据）、§T-103.6（两向变异反退化）、§T-103.7（前端 22+24）、§T-103.9（交叉核对表）。
- **风险提示**：① (b) 为**代码级真实缺陷、运行时条件性显形**——`T-096` 未收口前，**同 `exec_deadline` 且分页时**存在重复/漏行的用户可见风险，**`T-096` 的跨页稳定性单测（其验收 5）当前应红**；② 无 DM8 ⇒ **无实机执行证据**，(b) 的"实际发生"未在目标库观测；③ `EXP-T103-2`：`T-104` 落地后前端应改用服务端 `limitReached`，避免双实现口径漂移。
- **建议**：① 接受 `T-103` 完成度（8/8 验收通过），**待 `T-096` 收口后**该端点分页方为稳定；② `T-104` 落地时同步消除 `EXP-T103-2` 的前端自算；③ 是否有条件通过的最终判定，由**编排器/用户**裁量（**测试席不出发布结论**）。

---

# `T-090`~`T-093` 架构判据脚本独立复核（`te-verify-archcheck`，2026-09-21）

> 复核对象：`CR-006`（S7 首审）收口的四个 CI 判据脚本 —— `tools/arch-check/` 下 `check_no_db_links.sh`（`T-090`/`E1`）、`scan_cpp11.sh`（`T-091`/`E3`）、`scan_lock_free.sh`（`T-092`/`E4`）、`check_frontend.sh`（`T-093`/`E6`）。
> 复核性质：**独立证伪**（不采信产出方自述数字，**不复用**产出方 `/tmp/e1e3e4e6fx/` 夹具）。
> 复核者：测试工程师（`te-verify-archcheck`）｜日期：2026-09-21｜取样时刻：**2026-09-21T08:02+08:00**。
> 夹具：**本席自建**于 `/tmp/verify-archcheck/fx/`（41 文件，`/tmp/verify-archcheck/neutral/` 为中立 CWD）；**未向仓库树（`cpp/`、`web/`、仓库根、`tools/`、`docs/`）写入任何文件**。
> 代码版本：仓库**无 git**（`git rev-parse` → `fatal: not a git repository`），以**文件 md5 + mtime** 锚定。

## T090.1 指纹核对（自测值 vs 产出方自报值）

| 制品 | 本席实测 md5 | 实测行数 | 权限 | `bash -n` | 产出方自报 | 差异 |
|---|---|---|---|---|---|---|
| `tools/arch-check/check_no_db_links.sh` | `fedd522a3e239f290eb39df1da917583` | 145 | `-rwxr-xr-x` | OK | `fedd522a…` / 145 | **无** |
| `tools/arch-check/scan_cpp11.sh` | `8381011bd2df908c27665e50b2dfa34d` | 188 | `-rwxr-xr-x` | OK | `8381011b…` / 188 | **无** |
| `tools/arch-check/scan_lock_free.sh` | `bd5c2e9ec70a445ab49bb1257c23add0` | 157 | `-rwxr-xr-x` | OK | `bd5c2e9e…` / 157 | **无** |
| `tools/arch-check/check_frontend.sh` | `9f1dd5f1a48b34229bfa1747ef8342ef` | 113 | `-rwxr-xr-x` | OK | `9f1dd5f1…` / 113 | **无** |
| `docs/ARCHITECTURE.md` | `b4108abf823d8d7daea011322b01328a` | 2730 | — | — | `b4108abf…` / 2730 | **无** |

命令：`md5sum tools/arch-check/{check_no_db_links,scan_cpp11,scan_lock_free,check_frontend}.sh docs/ARCHITECTURE.md`；`wc -l`；`stat -c '%y %n'`；`bash -n <script>`。**四脚本 mtime 07:50–07:51（产出方），复核期间未变。**

## T090.2 E1 `check_no_db_links.sh` —— 真阳 / 真阴 / 边界

| # | 类别 | 夹具（关键内容） | 命令（CWD=`/tmp/verify-archcheck/neutral`） | 退出码 | 判定 |
|---|---|---|---|---|---|
| 1 | 真阳 | `link.txt`: `… -o hg-trading -L/usr/lib -lmysqlclient -lpthread` | `check_no_db_links.sh --link-root …/e1_tp_mysql` | **1** | 通过：**指名** `…/link.txt:1: …-lmysqlclient…` |
| 2 | 真阳 | `link.txt`: `… -ldmjdbc` | `--link-root …/e1_tp_dmjdbc` | **1** | 通过：指名 `link.txt:1` |
| 3 | 真阳(ldd) | 真 ELF `hg-trading`（`readelf -d` 含 `NEEDED libmysqlfake.so`，`-Wl,--no-as-needed` 强制） | `--binary …/e1_ldd/prog/hg-trading --link-root …/e1_skip` | **1** | 通过：`…/hg-trading (ldd): libmysqlfake.so => …` |
| 4 | 真阴 | `link.txt`: `… -lpthread -lprotobuf -lstdc++` | `--link-root …/e1_clean` | **0** | 通过 |
| 5 | SKIP | 空目录（无 binary、无 link.txt） | `--link-root …/e1_skip` | **0** | 通过：打印 `SKIP: 未发现…` |
| 6 | 非 ELF | 脚本伪产物 `script-hg-trading` | `--binary …/script-hg-trading` | **0** | 通过：`NOTE: …非动态可执行文件…跳过 ldd，不报红` |
| 7 | 词形态 | `-lpq` / `-loci` | `--link-root …/e1_pq_word`、`…/e1_oci_word` | **1** / **1** | 通过：均判 |
| 8 | 词形态 | 路径含 `pq_9f3`（`--dependency-file=CMakeFiles/pq_9f3/link.d`） | `--link-root …/e1_pq_hash` | **0** | 通过：**不误报**（`\bpq\b` 生效） |
| 9 | **边界·误报** | `link.txt`: `… -I/opt/mysql/include -lpthread`（**纯头文件路径，不链接**） | `--link-root …/e1_fp_include` | **1** | **误报**（见缺陷 TE-AC-005 建议项） |
| 10 | **边界·误报** | `link.txt`: `… -L/usr/lib/x86_64-linux-gnu/mysql`（库搜索路径） | `--link-root …/e1_Lpath` | **1** | 误报（同上；此形态**更像**强信号） |
| 11 | **边界·误报** | `… -lpq2`（非 DB 库）、`… -lodbcplus` | `--link-root …/e1_fp_lpq2`、`…/e1_fp_odbcplus` | **1** / **1** | 误报/可接受（见 T090.9） |
| 12 | **边界·漏判** | `… -lmariadb`（MySQL 兼容客户端，架构未列） | `--link-root …/e1_mariadb` | **0** | 漏判（架构未列 ⇒ 与架构一致，见建议） |
| 13 | 参数错 | `--link-root /no/such/dir` | 同左 | **2** | 通过：报错 `目录不存在` |

**「pq/oci 形态约束不对称」判定（派发专问）**：**不引入漏判**（`-l(pq|oci)`/`lib(pq|oci)`/`\bpq\b`/`\boci\b` 覆盖真实链接形态，`-lpq`/`-loci`/`libpq`/`liboci` 均判）；**引入误报面于未加约束的名字**（`mysql`/`sqlite`/`odbc`/`dmjdbc`/`dmdpi` 为**纯子串**，在**非链接上下文**（`-I` 头文件路径、`-L` 库路径、输出路径、rpath）中亦命中，见 #9/#10）。

## T090.3 E3 `scan_cpp11.sh` —— 真阳 / 真阴 / 边界

| # | 类别 | 夹具（关键内容） | 命令（`--root <fx>`） | 退出码 | 判定 |
|---|---|---|---|---|---|
| 1 | 真阳 | `int f(){ auto p = std::make_unique<int>(1); …}` | `--root …/e3_tp_src` | **1** | 通过：指名 `x.cpp:2` |
| 2 | 真阳 | `std::optional<int> g;` + `auto [a,b] = …;` | `--root …/e3_tpl` | **1** | 通过：指名 2 行（模板形态 + 结构化绑定） |
| 3 | 真阴 | 干净源码（`std::pair<int,bool>`） | `--root …/e3_clean` | **0** | 通过 |
| 4 | 注释只现 | `// 禁用 std::make_unique` + `/* … std::optional … */`（单行） | `--root …/e3_comment_only` | **0** | 通过：**注释已剔，不命中** |
| 5 | 注释只现 | **多行**块注释 `/*\nstd::make_unique\nstd::optional\n*/` | `--root …/e3_multiblock` | **0** | 通过：**跨行块注释已剔，不命中** |
| 6 | **边界·误报** | `const char* a = "std::optional";`（**字符串字面量只现**） | `--root …/e3_strlit` | **1** | **命中**（派发预期：**不命中**）→ 缺陷 TE-AC-001（需裁定） |
| 7 | **边界·误报** | `const char* a = R"(std::optional)";`（**原始字符串**） | `--root …/e3_rawstr` | **1** | **命中**（同上；原始串与普通串处理**同**） |
| 8 | **边界·漏判** | `std::make_\`⏎`unique<int>(…` （行尾 `\` 续行拼接） | `--root …/e3_cont_split` | **0** | **漏判**（架构与脚本头均声明「未覆盖」） |
| 9 | **边界·漏判** | `if \`⏎`constexpr (N>0) …`（续行） | `--root …/e3_cont_ifconstexpr` | **0** | **漏判**（同上） |
| 10 | 标识符压力 | `optional_count` / `my_string_viewable` / `make_unique_impl` / `variant_tag` | `--root …/e3_ident` | **0** | 通过：**词边界生效，不误报** |
| 11 | ① 缺 `-Werror` | CMake 仅 `-Wall -Wextra` | `--root …/e3_no_werror` | **1** | 通过：`-Werror=0` → ① 失败 |
| 12 | ① 缺标准 | CMake 无 `c++11`/`CMAKE_CXX_STANDARD 11` | `--root …/e3_nostd` | **1** | 通过：`标准声明…缺失` |
| 13 | ① 冲突标准 | CMake `set(CMAKE_CXX_STANDARD 14)` | `--root …/e3_conflict` | **1** | 通过：`冲突标准…存在` |
| 14 | SKIP | 空目录 | `--root …/e3_skip` | **0** | 通过：打印 `SKIP` |
| 15 | 参数错 | `--root /no/such/dir` | 同左 | **2** | 通过：报错（**注意：与 E4/E6 不同**，见 TE-AC-003） |

**真实仓库 `E3` ① 取数来源自证**：`sed -E 's/#.*$//' cpp/CMakeLists.txt | grep -nE 'CMAKE_CXX_STANDARD 11|-Wall|-Wextra|-Werror'` → 仅命中 `:27 set(CMAKE_CXX_STANDARD 11)`、`:30 add_compile_options(-Wall -Wextra -Werror)`；`-std=c++11` 剔注释后**不存在** ⇒ **真值来自 `set(CMAKE_CXX_STANDARD 11)`，非注释**（证实产出方说法）。

## T090.4 E4 `scan_lock_free.sh` —— 真阳 / 真阴 / 边界

| # | 类别 | 夹具（关键内容） | 命令（`--root <fx>`） | 退出码 | 判定 |
|---|---|---|---|---|---|
| 1 | 真阳 | `std::mutex m; std::lock_guard<std::mutex> g(m);` | `--root …/e4_tp_mutex` | **1** | 通过：指名 3 行（含 `:1 #include <mutex>`） |
| 2 | 真阳 | `return ::send(fd,b,8,0);` | `--root …/e4_tp_send` | **1** | 通过：指名 `x.cpp:2`（`::send(` 可判） |
| 3 | 真阳 | `std::shared_mutex sm; std::unique_lock<std::shared_mutex> l(sm);` | `--root …/e4_shared_mutex` | **1** | 通过：前缀变体生效 |
| 4 | 真阴 | `int f(){ return 0; }` | `--root …/e4_clean` | **0** | 通过 |
| 5 | 注释只现 | `// 本路径无 mutex` + `/* 亦不调用 write( / send( */` | `--root …/e4_comment_only` | **0** | 通过：注释已剔 |
| 6 | 标识符压力 | `mutexCount` / `writeToString(` / `readonly` | `--root …/e4_ident` | **0** | 通过：**不误报**（`name(` 形态 + 词边界生效） |
| 7 | **边界·误报** | `struct S { int read(int n){…} };` + `s.read(3)`（**方法名**） | `--root …/e4_fp_readmethod` | **1** | **误报** → 缺陷 TE-AC-002 |
| 8 | **边界·漏判** | `std::this_thread::sleep_for(std::chrono::seconds(1));` | `--root …/e4_fp_sleep_for` | **0** | **漏判**（架构仅列 C 函数 `sleep`/`usleep`/`nanosleep`，与架构一致，见建议） |
| 9 | WARN C | `new int(1)` / `malloc(4)` / `throw std::runtime_error("x")` | `--root …/e4_warn` | **0** | 通过：**仅 WARN，不判红**（证实产出方声明） |
| 10 | SKIP | `--root /no/such/dir` | 同左 | **0** | 通过(SKIP)：**但与 usage 声明不一致** → TE-AC-003 |
| 11 | 真实仓库 | `cpp/src/trading` 不存在 | 默认（`cwd=repo`） | **0** | 通过：`SKIP: 目录不存在…`（证实产出方「走 SKIP」） |

## T090.5 E6 `check_frontend.sh` —— 真阳 / 真阴 / 边界

| # | 类别 | 夹具（关键内容） | 命令（`--root <fx>`） | 退出码 | 判定 |
|---|---|---|---|---|---|
| 1 | 真阳① | `package.json` 存在 | `--root …/e6_tp_pkgjson` | **1** | 通过：`BUILD-CHAIN: …/package.json` |
| 2 | 真阳① | `app.min.js` 存在 | `--root …/e6_tp_minjs` | **1** | 通过：`BUILD-CHAIN: …/app.min.js` |
| 3 | 真阳② | `var u="jdbc:dm://127.0.0.1:5236";` | `--root …/e6_tp_jdbc` | **1** | 通过：指名 `x.js:1` |
| 4 | 真阳② | `var u="http://127.0.0.1:27402/api";` | `--root …/e6_tp_ip` | **1** | 通过：指名 `x.js:1` |
| 5 | 真阴 | `var u = window.location.host + "/api";` | `--root …/e6_clean` | **0** | 通过 |
| 6 | **边界·误报** | `// 这里不连 jdbc，也不含 dsn`（**仅注释**） | `--root …/e6_fp_comment` | **1** | **判红**（架构 E6 行明文「判 ② 按原文、**未剔注释**」⇒ 与架构一致，但为 latent 恒红源）→ TE-AC-004/建议 |
| 7 | 边界 | `var u="http://localhost:8080/api";` | `--root …/e6_fp_localhost` | **0** | 通过（声明未覆盖 `localhost`） |
| 8 | 边界 | 注释提及 `dist` | `--root …/e6_fp_dist_note` | **0** | 通过（① 为路径名判据，注释不触发） |
| 9 | SKIP | `--root /no/such/dir` | 同左 | **0** | 与 usage 声明不一致 → TE-AC-003 |
| 10 | 真实仓库 | `cwd=repo`、默认 `--root web` | `check_frontend.sh` | **0** | 通过：`grep -rnEi 'jdbc\|127\.0\.0\.1:[0-9]+\|\bdsn\b' web/` → **0 命中**（实测），故当前**无恒红** |

**派发专问「E6 注释里 jdbc 是否误报红 / 是否会让 CI 恒红」**：**会误报红**（#6，`exit 1`）；但 **当前真实 `web/`（27 文件）实测 0 命中 ⇒ CI 当前不红**。该行为**与架构 E6 行正文一致**（架构明文未剔注释、并以「此三词在前端无合法用途，纯提及亦属应清理项」为取舍理由），故**非实现违规**，属**已声明的 latent 风险**。

## T090.6 架构正文 ↔ 脚本行为一致性对照

对照源：`docs/ARCHITECTURE.md` §3.0.2 判据表（`:318`/`:320`/`:321`/`:323`，本席实时读取；`:325–330` 落点状态约定）。

| 项 | 架构声明的行为 | 脚本实测行为 | 判定 |
|---|---|---|---|
| E1 检查对象 | `ldd(hg-trading)` + `link.txt` 检索 DB 库名 | ldd 分支（#3）+ link.txt 分支（#1/#2）均生效 | **符合** |
| E1 无产物 | 无 binary 且无 `link.txt` → `SKIP` 退 0 | #5 实证 | **符合** |
| E1 未覆盖 | 静态库**间接**引入、非 ELF 产物（跳过不报红） | #6 实证非 ELF 跳过不报红；间接引入未覆盖（声明项） | **符合** |
| E1 判据词表 | `libdmjdbc`/`libdmdpi`/`mysql`/`sqlite`/`pq`/`odbc`/`oci` | `DB_RE` 含全部；`pq`/`oci` **加形态约束**（架构未写形态，脚本更**严**于字面） | **符合（细化）** |
| E3 ① | 编译参数固定 `-std=c++11 … -Wall -Wextra -Werror` + 无冲突标准 | #11/#12/#13 实证 | **符合** |
| E3 ② | 禁用特性「**词边界 + 先剔 C++ 注释**后判定」 | 词边界 #10、注释剔除 #4/#5 实证 | **符合** |
| E3 未覆盖 | 原始字符串 `R"(…)"` 与行尾 `\` 续行未特殊处理 | #7/#8/#9 实证「未特殊处理」确属实（原始串与普通串同判；续行漏判） | **符合** |
| E3 SKIP | 无源码且无 `CMakeLists.txt` → `SKIP` 退 0 | #14 实证 | **符合** |
| E3 字符串字面量 | **架构未声明需剔除字符串**（仅要求剔注释） | 字符串内禁用字样 → 判红（#6） | **符合架构**（但**不符派发预期**，见 TE-AC-001） |
| E4 落点/覆盖 | 扫 `cpp/src/trading/**`；覆盖锁原语 + 同步 I/O（枚举项） | 真阳 #1–#3、`read`/`send` 覆盖；枚举项逐一可判 | **符合** |
| E4 未覆盖 | 无动态分配、`compare_exchange` 循环、异常路径 **仅 WARN 不判红** | #9 实证 `new`/`malloc`/`throw` → **WARN 且退 0** | **符合** |
| E4 SKIP | 无目录 → `SKIP` 退 0 | #10/#11 实证 | **符合** |
| E6 落点/判据 | 扫 `web/`；① 零构建链、② 无直连（`jdbc`/`127.0.0.1:端口`/`dsn`） | #1–#5 实证 | **符合** |
| E6 仅前端目录 | **严格限定前端目录、不做全仓裸扫** | 扫描根 = `--root`；真实 `web/` 实测 0 命中 | **符合** |
| E6 判② | 按原文、**未剔注释** | #6 实证注释亦判 | **符合** |
| E6 未覆盖 | `localhost:<端口>`、`ws://`、间接直连 | #7 实证 `localhost` 不判 | **符合** |
| 落点状态 | E1/E3/E4/E6 = `✅ 已实现：T-090~T-093`；「落点待建 = 无」 | 四脚本实存且 `chmod +x`（T090.1） | **符合** |

> **无法判定项**：无（上表各行均有实测/实时读取证据）。

## T090.7 「落点待建」零残留

| 检查 | 命令 | 结果 |
|---|---|---|
| 指名 `T-09x` 的落点待建 | `grep -c '落点待建：`T-09' docs/ARCHITECTURE.md` | **0** |
| 全部 `落点待建` 出现处 | `grep -n '落点待建' docs/ARCHITECTURE.md` | **5 处，全部为合法留痕**：`:7`（编写时间历史，描述约定）、`:327`（约定定义）、`:330`（「落点待建 = 无」）、`:2682`（v1.5.9 历史）、`:2684`（v1.5.7 历史） |
| §3.0.2 表内 E1/E3/E4/E6 状态 | `sed -n '318,323p'` | 四行均为 `（✅ **已实现：`T-090`~`T-093`）`，**无 `🔴`** |

## T090.8 交叉核对表（产出方断言 → 本席实测 → 判定）

| # | 产出方断言 | 本席实测（版本+命令+输出） | 判定 |
|---|---|---|---|
| 1 | 四脚本 md5/行数（见 T090.1 表） | `md5sum`/`wc -l`（08:02）逐项一致 | **符合** |
| 2 | 架构锚点 `b4108abf…`/2730 | `md5sum docs/ARCHITECTURE.md` → `b4108abf…`/2730 | **符合** |
| 3 | E1 默认**实扫 6 个 link.txt**（非 SKIP）、0 命中退 0 | 实测 `待检 link: 6 个`、`E1 通过…退出码 0` | **符合** |
| 4 | E4 因 `cpp/src/trading` 不存在 → **SKIP** | 实测 `SKIP: 目录不存在…退出码 0` | **符合** |
| 5 | E3 ①：`-std=c++11` 只在注释；真值 `set(CMAKE_CXX_STANDARD 11)` | 剔注释后 `-std=c++11` 不存在；`:27`/`:30` 命中 | **符合** |
| 6 | E4「无动态分配…仅 WARN 不判红」 | 实测 `new`/`malloc`/`throw` → `[WARN…]` + **退 0** | **符合** |
| 7 | 四脚本「双向自测通过」（隔离夹具退 1 / 真实仓库退 0 或 SKIP） | 本席独立夹具：**真阳全部退 1**、**真阴全部退 0**、**真实仓库全退 0** | **符合**（但边界见 T090.9） |
| 8 | E3「原始串/续行未覆盖…**不产生漏报**」 | 原始串 → **命中**（误报面）；续行 → **漏判**（`make_\`+`unique`、`if \`+`constexpr` 均退 0） | **部分不符**：「未覆盖」属实；「**不产生漏报**」**不成立** → TE-AC-004 |
| 9 | 落点状态「✅ 已实现」、落点待建 = 无 | T090.7 实证 | **符合** |

## T090.9 缺陷清单（可复现：夹具 + 命令 + 实际输出 + 期望输出）

> 严重度：严重 / 中 / 低。**E1/E3/E4/E6 主体判据（真阳/真阴/SKIP/WARN）全部正确**，下列均为**边界/误报/声明口径**类。

| 编号 | 严重度 | 脚本 | 现象（夹具 + 命令 + 实际 → 期望） | 归属 |
|---|---|---|---|---|
| **TE-AC-001** | **中 · 需裁定** | E3 | 夹具 `e3_strlit/x.cpp` = `const char* a = "std::optional";`（#6）/ `e3_rawstr/x.cpp` = `R"(std::optional)"`（#7）→ `scan_cpp11.sh --root …` → **exit 1 命中**（`x.cpp:1: … "std::optional"`）。**派发预期：字符串字面量内禁用字样应不命中**。**但** `ARCHITECTURE.md:320` E3 行只写「**词边界 + 先剔 C++ 注释**」，**未要求剔字符串** ⇒ **脚本与架构正文一致**。属「**派发预期 vs 架构**」冲突，**请编排者裁定**（若裁定应剔字符串，则 `ARCHITECTURE.md:320` 需同步补「字符串字面量内不判」并改脚本内联 awk）。 | `T-091`（若裁定） |
| **TE-AC-002** | **中** | E4 | 夹具 `e4_fp_readmethod/x.cpp` = `struct S { int read(int n){ return n; } }; int f(S s){ return s.read(3); }`（#7）→ `scan_lock_free.sh --root …` → **exit 1**，命中 `x.cpp:1…int read(int n)`、`x.cpp:2…s.read(3)`。**期望**：`read`/`write`/`send` 作**方法名**（非同步 I/O 语义）不应判红。根因：判据为 `\b(name)[[:space:]]*\(` **调用形态**，未区分「系统同步 I/O 调用」与「同名成员函数」。**注**：`writeToString(`（#6）**不误报**，仅 `read(`/`write(`/`send(` 方法名命中。 | `T-092` |
| **TE-AC-003** | **低** | E4 / E6 | `scan_lock_free.sh --root /no/such/dir`、`check_frontend.sh --root /no/such/dir` → 均 **退出码 0（SKIP）**；而两脚本 usage 均声明「**2 = 参数或路径错误**」，且 `scan_cpp11.sh --root /no/such/dir` → **退出码 2**。**期望**：误传/拼错的 `--root` 应报错（退 2）而非静默当「组件未铺开」放行；四脚本对「路径错」应统一。 | `T-092`/`T-093` |
| **TE-AC-004** | **低 · 表述** | E3 | 脚本头 `:39–40` 声明「原始字符串 `R"(…)"` 与行尾 `\` 续行未特殊处理；…故**不产生漏报**」。实测可构造漏判：`std::make_\`⏎`unique<int>(1)`（#8）、`if \`⏎`constexpr (N>0)`（#9）→ **均退 0（漏判）**。**期望**：声明改为「**未覆盖；极端续行/原始串场景存在漏判可能，M2 业务源码不承载此类字样**」以与事实相符（**属声明口径，非实现缺陷**；架构 `:320` 亦已声明「未覆盖」）。 | `T-091` |

## T090.10 建议清单（非缺陷）

| 编号 | 脚本 | 建议 | 依据 |
|---|---|---|---|
| S-1 | E1 | `mysql`/`sqlite`/`odbc` 目前为**纯子串**判据，在**非链接上下文**（`-I/opt/mysql/include`、`-L…/mysql`、输出路径、rpath）会误报（实测 #9/#10）。可仿 `pq`/`oci` 加 `-l`/`lib` 形态约束，或至少对 `-I` 段做排除。**权衡**：加约束会收窄覆盖面（架构 E1 字面为「检索 DB 库名」，未约束形态）。 | 本席实测 #9/#10 |
| S-2 | E1 | `-lpq2` / `-lodbcplus` 因前缀/子串命中（#11）。是否为真 DB 库由人工判断；若追求精确，可对 `-l(pq\|oci)` 补尾边界。 | 本席实测 #11 |
| S-3 | E1 | `-lmariadb`（MySQL 兼容客户端，**架构未列**）漏判（#12）。建议评估是否补入判据词表。 | 本席实测 #12 |
| S-4 | E4 | `std::this_thread::sleep_for` / `sleep_until`（C++11 chrono，**现代阻塞休眠写法**）漏判（#8）。架构 E4 仅列 C 函数 `sleep`/`usleep`/`nanosleep`，故与架构一致；建议评估补入 `sleep_for`/`sleep_until`。 | 本席实测 #8 |
| S-5 | E4 | `#include <mutex>` / `<shared_mutex>` 亦被计入命中（`e4_tp_mutex` 3 处含 `:1`）。include 非「锁使用」，但在「无锁路径」中为强信号，**建议保留**并知悉。 | 本席实测 T090.4 #1 |
| S-6 | E6 | 前端**注释**提及 `jdbc`/`dsn` 会判红（#6）—— 与架构一致（架构明文未剔注释），**当前真实 `web/` 0 命中故无恒红**；建议后续仿 `E5` 的 `strip_java_comments.awk` 加同族预处理，消除 latent 风险。 | 本席实测 #6 + 真实 `web/` 0 命中 |
| S-7 | 四脚本 | 仓库内**无任何调用点**（无 `Makefile`、无 `.ci/.github/.gitlab/jenkins` 配置、无脚本互调；`grep -rn` 仅命中 `docs/**` 与 `dev-team/inbox/**` 的描述）。架构 `:326` 仅声明「**可直接被 CI / make 调用**」（可调用性，非「已接线」）。建议后续补 CI/make 目标以真正生效。 | `grep -rniE 'check_no_db_links\|scan_cpp11\|scan_lock_free\|check_frontend'` 排除 `tools`/`docs` 后无命中 |

## T090.11 未能验证 / 未覆盖项（不沉默略过）

| # | 项 | 原因 | 性质 |
|---|---|---|---|
| 1 | E1 对**静态库内间接引入** DB 代码的检测 | 架构明示为**未覆盖**，需符号级工具；本席未做符号级验证 | 声明项（非缺陷） |
| 2 | E3/E4 对**真实 M2 业务源码**的判定 | `cpp/src/trading/` **不存在**、业务源码未铺开 ⇒ 仅在隔离夹具上验证 | 组件未铺开（非缺陷） |
| 3 | 四脚本在 **CI 流水线中的实际执行** | 仓库内**无 CI/make 调用点**（S-7）⇒ 仅验证「可调用」，未验证「已接线执行」 | 未接线（非缺陷） |
| 4 | E4 `compare_exchange` / `std::function` 的 WARN 输出 | 本席仅用 `new`/`malloc`/`throw` 触发 WARN（#9）；`compare_exchange`/`std::function` 未单独构造 | 未逐一构造（同一 WARN 通道，风险低） |
| 5 | 真实 DM8 / 目标库相关行为 | 本复核不涉及数据库运行时 | 不适用 |
| 6 | 共享树零改动**全量**自证 | 仓库非 git，无法 `git status`；已用 `find . -type f -newermt '2026-09-21 07:55'` **排除本席写入**（本席仅在 `/tmp/verify-archcheck/` 落盘；该命令命中项全为**其他并行席位**的 `java/target/**`、`docs/TASKS.md`、`docs/BOARD.md` 写入，非本席） | 已尽力自证 |

## T090.12 度量数据

| 指标 | 数值 |
|---|---|
| 指纹核对 | 4 脚本 + 1 架构 = **5** 制品，**差异 0** |
| E1 用例 | **13**（真阳 3 / 真阴 1 / SKIP 2 / 边界 5 / 参数错 1 / 词形态 2 合并计）→ 退 1 **6**、退 0 **5**、退 2 **1** |
| E3 用例 | **15**（真阳 2 / 真阴 1 / 注释 2 / 边界 3 / 标识符 1 / ① 3 / SKIP 1 / 参数错 1 …）→ 退 1 **6**、退 0 **7**、退 2 **1** |
| E4 用例 | **11** → 退 1 **3**、退 0 **8** |
| E6 用例 | **10** → 退 1 **5**、退 0 **5** |
| 合计独立执行 | **49** 次脚本运行（全部本席自建夹具 / 真实仓库） |
| 架构一致性对照行 | **19**：符合 **19** / 不符 **0** / 无法判定 **0** |
| 交叉核对断言 | **9**：符合 **8** / 部分不符 **1**（#8）/ 无法判定 **0** |
| 新建缺陷 | **4**（TE-AC-001 中·需裁定 / TE-AC-002 中 / TE-AC-003 低 / TE-AC-004 低·表述） |
| 建议 | **7**（S-1~S-7） |
| 未覆盖/未能验证 | **6** |

## T090.13 结论

- **一行结论**：**`T-090`~`T-093` 四脚本 = 有条件通过（独立复核）** —— 指纹逐项相符、`bash -n` 全过；**真阳 / 真阴 / SKIP / WARN 四类主体行为全部独立实测正确**；架构 §3.0.2 正文 ↔ 脚本行为 **19/19 符合**；「落点待建」**零残留**（`grep -c` = 0）。**条件 = 4 项边界发现待处置**，其中 **TE-AC-001 需编排者裁定**（架构未要求剔字符串、派发预期要求剔，二者冲突）。
- **依据**：§T090.1（指纹 5/5 相符）、§T090.2–T090.5（49 次独立运行）、§T090.6（架构一致性 19 行）、§T090.7（零残留）、§T090.8（交叉核对 8 符合 / 1 部分不符）。
- **风险提示**：① **TE-AC-001** 若被裁定为缺陷，则 `ARCHITECTURE.md:320` 与脚本内联 awk 需同步改（当前「架构一致但派发预期不符」，**本席不擅自认定**）；② **TE-AC-002**（E4 方法名 `read(` 误报）在 M2 交易路径铺开后可能产生**假红**，建议随 `T-092` 收口；③ **S-7**：四脚本**尚未接线到任何 CI/make**，当前仅「可调用」；④ **TE-AC-003**：E4/E6 对拼错的 `--root` 静默 SKIP 退 0，与 usage 声明（退 2）不符。
- **建议**：① 接受四脚本**主体判据**（`T-090`~`T-093`）完成度；② TE-AC-001 请示编排者/架构师裁定口径后，如需订正则归 `T-091`；③ TE-AC-002/003 随 `T-092`/`T-093` 一并评估；④ S-1~S-7 作为后续收敛项；⑤ 是否「通过」的最终判定由**编排器/用户**裁量（**测试席不出发布结论**）。

> **复核性质声明**：本次为**独立证伪**，全部结论附「版本 + 命令 + 输出」；**未采信**产出方自述 md5/锚点/0 命中/SKIP 行为，均自测复现；**未修改**任何交付物（`tools/arch-check/**`、`docs/ARCHITECTURE.md` 一字未动），夹具全部位于 `/tmp/verify-archcheck/`。

---

# `T-095` 二轮独立复核（六脚本一致性收口 / 4 缺陷闭合验证）（`te-verify-archcheck`，2026-09-21）

> 复核对象：`T-095`（依一环 `docs/TEST_RESULT.md` §`T-090`~`T-093` 的 4 项缺陷 `TE-AC-001`~`004` 收口）。
> 复核性质：**独立证伪**，**复用本席一环夹具**（`/tmp/verify-archcheck/fx/`）+ 二轮新增夹具（`/tmp/verify-archcheck/fx2/`）；**不复用**产出方 `/tmp/tl-t095-fx/`。
> 复核者：测试工程师（`te-verify-archcheck`）｜日期：2026-09-21｜取样时刻：**2026-09-21T08:23–08:30+08:00**。
> **未向共享树写任何文件**（`find . -newermt '2026-09-21 08:20'` 命中项全为其他并行席位的 `docs/BOARD.md`、`docs/TASKS.md`、`java/**` 写入）。

## T095.1 基线指纹（本席自测）

| 制品 | 本席实测 md5 | 行数 | 字节 | 与派发自报差异 |
|---|---|---|---|---|
| `tools/arch-check/check_no_db_links.sh` | `12561778eaf5140d2f75d655870bfce3` | 157 | 7971 | 无 |
| `tools/arch-check/scan_cpp_sql.sh` | `fe056fad2f98e1d817aa6f1943d3ac65` | 192 | 9757 | 无 |
| `tools/arch-check/scan_cpp11.sh` | `205c7a06192f799f46410770faad6120` | 200 | 11088 | 无 |
| `tools/arch-check/scan_lock_free.sh` | `e105c649d651966ee77f4b78f9e940d9` | 227 | 11500 | 无 |
| `tools/arch-check/scan_java_sql.sh` | `aa7b7fb6358625dfbf2ccf45b3bc220c` | 202 | **11655** | 无（但一环为 11172 B，见 §T095.10 R2-3） |
| `tools/arch-check/check_frontend.sh` | `cce417129d5f0e5e0fa4bb2d8fd05e35` | 120 | 5995 | 无 |
| `docs/ARCHITECTURE.md` | `731e5515d430fb23337a3d69375f4c8e` | 2732 | — | 无（v1.5.10） |

## T095.2 `TE-AC-002` 收口 —— E4 限「调用形态」

命令：`bash tools/arch-check/scan_lock_free.sh --root <fx>`（CWD=`/tmp/verify-archcheck/neutral2`）。

| 输入 | 期望 | 实际退出码 | 判定 |
|---|---|---|---|
| `::send(fd,b,8,0)` | 1 | **1**（指名 `x.cpp:1`） | ✅ |
| `int n = read(fd,b,8)`（裸调用） | 1 | **1** | ✅ |
| `p->send(1)` | 1 | **1** | ✅ |
| `q.recv(1)` | 1 | **1** | ✅ |
| `std::mutex m; std::lock_guard<…>` | 1 | **1**（3 处） | ✅ |
| `struct S{int read(int n){return n;}}; s.read(3)` | **0** | **0** | ✅ **缺陷已闭合** |
| `struct Sock{int read(int);}; s.read(3)`（成员 read，**已披露未覆盖**） | 0 | **0** | ✅ 披露属实 |
| `sock.write(1)`（成员 write，已披露未覆盖） | 0 | **0** | ✅ 披露属实 |
| `const char* s="mutex";`（字符串字面量） | 0 | **0** | ✅（一并闭合 `TE-AC-001` 同根因） |
| **`int* read(int){}` / `int& write(int){}` / `char *read(int){}`（指针/引用返回的**定义**）** | 0 | **1**（3/9 行命中） | ❌ **残留边界缺陷 R2-1（低）** |

> **定义形态矩阵**（9 行）：`int read(` / `int write(` / `void read(` / `void write(` / `static int read(` / `size_t read(` ⇒ **不判**（✅）；仅 `int* read(` / `int& write(` / `char *read(` ⇒ **判**（✗）。即脚本头 `:29` 声明的「`<标识符><空白>name(` ⇒ 判为定义不判」**未覆盖带 `*`/`&` 前置的返回类型**。
> **「通用名成员调用不判」取舍判定**：产出方定解（网络专用名 `send`/`sendto`/`recv`/`recvfrom` 判成员调用；通用名 `read`/`write` 只在全局/裸形态判）**可接受** —— 与 `s.read(3)` 形状重合是不可静态区分的事实；且架构 `:321` 与脚本头 `:33-36` **均如实披露**该未覆盖面。

## T095.3 `TE-AC-003` 收口 —— 退出码语义六脚本统一

| 场景 | check_no_db_links | scan_cpp_sql | scan_cpp11 | scan_lock_free | scan_java_sql | check_frontend |
|---|---|---|---|---|---|---|
| **显式**传入不存在路径（`--link-root`/`--root /no/such/dir`） | **2** | **2** | **2** | **2** | **2** | **2** |
| **默认路径**不存在（空 CWD 运行） | 0+SKIP | 0+SKIP | 0+SKIP | 0+SKIP | 0+SKIP | 0+SKIP |
| E1 显式 `--binary /no/such/bin` | **2** | — | — | — | — | — |

```text
$ for s in {check_no_db_links,scan_cpp_sql,scan_cpp11,scan_lock_free,scan_java_sql,check_frontend}; do (cd neutral2 && bash $s.sh <显式不存在路径>); done
check_no_db_links.sh -> EXIT=2 | 错误：--link-root 目录不存在（显式路径）：/no/such/dir
scan_cpp_sql.sh      -> EXIT=2 | 错误：--root 目录不存在（显式路径）：/no/such/dir
scan_cpp11.sh        -> EXIT=2 | 错误：--root 目录不存在（显式路径）：/no/such/dir
scan_lock_free.sh    -> EXIT=2 | 错误：--root 目录不存在（显式路径）：/no/such/dir
scan_java_sql.sh     -> EXIT=2 | 错误：--root 目录不存在（显式路径）：/no/such/dir
check_frontend.sh    -> EXIT=2 | 错误：--root 目录不存在（显式路径）：/no/such/dir
```

- **E1 正例不被误判**（派发专问）：真实仓库（`CWD=repo`、6 个 `link.txt` 实存、默认 `--link-root .`）⇒ **`EXIT=0`**（`待检 link : 6 个`、`E1 通过…`），**未**走 `2`。✅
- **两者可区分**：靠 `ROOT_EXPLICIT`/`LINK_ROOT_EXPLICIT` 标志（是否显式传参），**非**「路径是否存在」——与架构 `:331` 约定一致。✅ **缺陷已闭合**。

## T095.4 `TE-AC-004` 收口 —— 表述如实

- `grep -c '不产生漏报' tools/arch-check/scan_cpp11.sh` ⇒ **0**；`docs/ARCHITECTURE.md` ⇒ **0**。✅
- 仅存的「零漏报」出现在**否定式订正**中（`scan_cpp11.sh:48/54`「**不得**再作「零漏报」表述」；`ARCHITECTURE.md:320` 同）——属合法留痕。
- **披露属实核对**（实测）：行尾 `\` 续行 `std::make_`⏎`unique<int>()`、`if `⏎`constexpr` ⇒ **仍漏判（退出码 0）**；同行自定义定界原始串 `R"xx(std::optional)xx"` ⇒ **0**（被当普通串剔除）；**多行**原始串 `R"delim(`⏎`std::optional`⏎`)delim"` ⇒ **1（命中）**——即多行原始串**未覆盖且方向为过报**（非漏报），与 `:320`「多行原始字符串未特殊处理」披露一致。✅

## T095.5 `TE-AC-001` 同根因（E3/E4 剔字符串字面量）+ **E5 反向检查**

| 用例 | 脚本 | 期望 | 实际 | 判定 |
|---|---|---|---|---|
| `const char* a="std::optional";` | E3 | 0 | **0** | ✅ |
| `const char* a = R"(std::optional)";` | E3 | 0 | **0** | ✅ |
| `char c='('; char d='"';` | E3 | 0 | **0** | ✅ |
| 真码 `std::optional<int> o;` | E3 | 1 | **1** | ✅ |
| `const char* s="mutex";` | E4 | 0 | **0** | ✅ |
| **【反向】生产** `String u="jdbc:dm://127.0.0.1:5236/HG";` | **E5** | **1** | **1**（指名行） | ✅ **E5 未跟着改** |
| 【反向】生产 `String s="SELECT id FROM t_user";` | E5 | 1 | **1** | ✅ |
| 【反向】仅注释内 `jdbc:dm://…` | E5 | 0 | **0** | ✅ |
| 干净 java | E5 | 0 | **0** | ✅ |

- 架构 `:320`（E3）已改为「**词边界 + 先剔 C++ 注释与字符串/字符字面量**」；`:321`（E4）已改为「先剔 C++ 注释与字符串/字符字面量再判定」。✅
- **不对称理由已写明**：架构 `:339`（E5 脚注）「**为何只剔注释、不剔字符串字面量**：`String sql = "SELECT id FROM t_user";` 是真外泄，必须命中」；E3/E4 脚本头 + E2 脚本头亦各自载明「与 E5 故意不对称」。✅

## T095.6 `E2` 同构化复核（§3.0.2 五行事之一，新增验证）

| 用例 | 期望 | 实际 | 判定 |
|---|---|---|---|
| `const char* s="SELECT id FROM t_user";` | 1 | **1** | ✅ |
| 裸标识符 `void insert(int); int update=1;` | 0 | **0** | ✅ |
| 注释内 `// 例如 SELECT id FROM t_user` | 0 | **0** | ✅ |
| 字符串内 `"jdbc:dm://…"`（保留字符串） | 1 | **1** | ✅ |
| `mysql_real_connect(…)`（调用形态） | 1 | **1** | ✅ |
| `build/` 内 `"DELETE FROM t"` | 0 | **0**（排除生效） | ✅ |
| 干净 | 0 | **0** | ✅ |

## T095.7 反退化（一轮夹具复跑，`T-090` 全量）

复跑一环 40 项夹具，**逐项比对退出码**。差异**仅 3 类、全部为预期**：

| 夹具 | 一环 | 二轮 | 归因 |
|---|---|---|---|
| E3 `e3_strlit`（字符串字面量） | 1 | **0** | `TE-AC-001` 修复 |
| E3 `e3_rawstr`（原始字符串） | 1 | **0** | `TE-AC-001` 修复 |
| E4 `e4_fp_readmethod`（方法名 `s.read(3)`） | 1 | **0** | `TE-AC-002` 修复 |
| E4 `check_frontend`/`scan_lock_free` **显式**不存在 `--root` | 0 | **2** | `TE-AC-003` 修复 |

**其余 36 项零变化**：E1 全部真阳仍 **1**（`-lmysqlclient`/`-ldmjdbc`/`ldd` 真 ELF/`-lpq`/`-loci` 等）、真阴/SKIP 仍 **0**；E3 `e3_tp_src`/`e3_tpl`/`e3_no_werror`/`e3_nostd`/`e3_conflict` 仍 **1**；E4 `e4_tp_mutex`/`e4_tp_send`/`e4_shared_mutex` 仍 **1**、`e4_warn` 仍 **0**；E6 四真阳仍 **1**、真阴/边界仍 **0**。**无任何非预期退化**。

## T095.8 架构正文 ↔ 脚本行为一致性（§3.0.2）

| 项 | 架构声明（`:318`~`:323` + `:331`） | 实测 | 判定 |
|---|---|---|---|
| E1 退出码 | 显式 `--link-root`/`--binary` 不存在 ⇒ 2；无产物 ⇒ SKIP 0 | §T095.3 | 符合 |
| E2 同构化 + 退出码 | 注释预处理（保留字符串）+ ② 分域；显式不存在 ⇒ 2、默认 ⇒ 0 | §T095.3/§T095.6 | 符合 |
| E3 判定面 + 退出码 + 未覆盖 | 剔注释与字符串/字符字面量；显式不存在 ⇒ 2；如实声明漏判面 | §T095.4/§T095.5 | 符合 |
| E4 判定面 + 退出码 + 未覆盖 | 剔注释与字符串/字符字面量；按调用形态；通用名成员调用未覆盖；显式 ⇒ 2 | §T095.2/§T095.3 | 符合 |
| E5 行**未改动** | 与 `v1.5.9` 文本逐字一致（行内 `T-095` 出现 **0** 次） | `sed -n 322p` | 符合（披露属实） |
| E6 退出码 | 显式 `--root` 不存在 ⇒ 2；默认 ⇒ SKIP 0 | §T095.3 | 符合 |
| 退出码通用约定行 `:331` | 六脚本统一：显式不存在 ⇒ 2；默认 ⇒ 0+SKIP；靠「是否显式传参」区分 | §T095.3 | 符合 |
| 「落点待建」零残留 | `:330`「落点待建 = 无」 | `grep -c '落点待建：\`T-09'` = **0**（5 处出现均合法留痕） | 符合 |
| E3/E4 与 E5 不对称理由 | 需写明 | `:339` + E2/E3/E4/E5 脚本头均写明 | 符合 |

**无法判定项：无。**

## T095.9 二轮缺陷清单

| 编号 | 严重度 | 现象（可复现） | 归属 |
|---|---|---|---|
| **R2-1** | **低** | E4：`scan_lock_free.sh --root <fx>` 对**指针/引用返回类型的定义**误报 —— 夹具 `int* read(int n){return 0;}` / `int& write(int n){…}` / `char *read(int n){…}` ⇒ **退出码 1（3/9 行命中）**；而 `int read(`/`void read(`/`size_t read(`/`static int read(` ⇒ **0**。**期望**：定义形态（含 `*`/`&` 前置返回类型）不判。根因：`sync_hit()` 仅识别「`<标识符><空白>name(`」为定义，未处理前导 `*`/`&`（`pre` 非词字符 ⇒ 归为调用）。**架构 `:321` 与脚本头均未披露该边界**。 | `T-092`（或并入后续） |

> **`T-095` 四项目标缺陷（`TE-AC-001`~`004`）全部闭合，`E5` 未误改，反退化无退化，架构一致性 9/9。** 二轮**未**再发现中/高severity缺陷。

## T095.10 二轮建议 / 发现（非缺陷）

| 编号 | 内容 | 依据 |
|---|---|---|
| R2-2 | E3 **多行原始字符串** `R"delim(…)delim"` 内含禁用字样 ⇒ **判红（过报）**。方向与「漏判面」相反，但同属「未覆盖」——建议在 `:320` 披露中把「多行/自定义定界原始串 ⇒ 可能**误报**」与「续行 ⇒ **漏判**」并列，便于读者分辨方向。 | 本席实测 |
| R2-3 | **披露口径偏差（低）**：产出方称「因派发未列 `E5`，只改了 `E5` 脚本头」——但 `scan_java_sql.sh` 由一环 **11172 B → 11655 B（+483 B）**，新增 `ROOT_EXPLICIT` + 退出码分支（属**约定所需的代码变更**、行为正确、无副作用），**非「仅脚本头」**。建议披露口径订正为「脚本头 + 退出码逻辑」。 | 本席实测字节/行为 |
| R2-4 | E4 锁原语仍按**词边界**判：`#include <mutex>` 亦计入命中（`e4_tp_mutex` 3 处含 `:1`）。属一环既有行为、非本轮回归；「无锁路径」下 include 为强信号，可保留，建议知悉。 | 本席实测 |

## T095.11 未能验证项（不沉默略过）

| # | 项 | 原因 |
|---|---|---|
| 1 | 一环 `scan_java_sql.sh` 逐字 diff | 一环未留存该脚本副本（非一环复核范围）；仅能以**字节数变化 + 现行为**旁证其体部有变更（R2-3） |
| 2 | E4/E3 对**真实 M2 业务源码** | `cpp/src/trading/` 仍不存在、业务源码未铺开 ⇒ 仅隔离夹具 |
| 3 | 四脚本在 CI 流水线实际执行 | 仓库内仍无 CI/make 调用点（一环 `S-7` 未变） |
| 4 | E3 续行/多行原始串的**目标库**行为 | 属静态扫描，无目标库；本项本即不适用 |

## T095.12 二轮度量

| 指标 | 数值 |
|---|---|
| 指纹核对 | 6 脚本 + 1 架构 = **7**，差异 **0** |
| 二轮独立执行 | 六脚本退出码 12 + E4 矩阵 10 + E3 字面量 4 + E5 反向 4 + E2 7 + 原始串/续行 4 ≈ **41** 次 |
| 反退化复跑 | 一环夹具 **40** 项，差异 **4** 项（全为预期） |
| 架构一致性行 | **9**：符合 9 / 不符 0 / 无法判定 0 |
| 二轮缺陷 | **1**（R2-1 低） |
| 建议/发现 | **3**（R2-2/3/4） |
| 未能验证 | **4** |

## T095.13 结论

- **一行结论**：**`T-095` 二轮独立复核 = 有条件通过（范围=4 项缺陷收口）**—— `TE-AC-001`/`002`/`003`/`004` **逐项独立实测闭合**；`E5` **未误改**（字符串内 `jdbc:` 仍判红）；一环 40 项真阳**零回归**；架构一致性 **9/9**；「落点待建」**零残留**。**条件** = 新发现 **`R2-1`（低）**：E4 对 `*`/`&` 返回类型的**定义**形态误报（架构与脚本头均未披露），建议随 `T-092`（或 `T-095` 追加）收口。
- **依据**：§T095.1（指纹 7/7 相符）、§T095.2~T095.6（逐条实测）、§T095.7（反退化 40 项）、§T095.8（一致性 9 行）。
- **对派发专问的答复**：**「唯一差异 = E4 显式不存在路径 0→2」不能完全复现** —— 我实测到的差异为 **4 项**：① `TE-AC-003` 的退出码变更影响 **`E4` 与 `E6` 两个脚本**（非仅 `E4`）均 0→2；② 另有 `TE-AC-001`/`002` 修复带来的 `e3_strlit`/`e3_rawstr`/`e4_fp_readmethod` 由 1→0（这三项是**修复目标本身**，非「非预期退化」）。**除上述外无其它差异**。
- **风险提示**：`R2-1` 在 M2 交易路径铺开后，若出现名为 `read`/`write` 且返回 `*`/`&` 的**定义**，会**假红**；`R2-2` 多行原始串会**过报**（方向与漏判相反）。
- **建议**：① 接受 `T-095` 的 4 项闭合；② `R2-1` 请示是否随 `T-092` 收口（或将 `*`/`&` 定义形态补入脚本头/架构 `:321` 的未覆盖披露）；③ `R2-3` 披露口径订正。是否「通过」的最终判定由**编排器/用户**裁量（**测试席不出发布结论**）。

> **复核性质声明（二轮）**：独立证伪，结论附「版本 + 命令 + 输出」；**未采信**派发自报指纹/退出码；**未修改**任何交付物（`tools/arch-check/**`、`docs/ARCHITECTURE.md`）；夹具全部位于 `/tmp/verify-archcheck/fx/`（一环）与 `/tmp/verify-archcheck/fx2/`（二轮）。

---

# `T-095` **三卷**（限定范围）独立复核（`R2-1` 收口 / `R2-2`/`R2-3` 披露）（`te-verify-archcheck`，2026-09-21）

> 复核范围：**仅**复 `R2-1`（E4 指针/引用返回类型定义误报）收口、`R2-2`（架构 `:320`/`:321` 两面披露）、`R2-3`（`E5` 回执更正）、以及限定回归。**不重跑全量**（其余沿用二轮结论）。
> 复核者：测试工程师（`te-verify-archcheck`）｜日期：2026-09-21｜取样时刻：**2026-09-21T08:40–08:45+08:00**。
> 夹具：**本席自建**于 `/tmp/verify-archcheck/fx3/`（复用一环 `fx/`、二轮 `fx2/`）；**未复用**产出方 `/tmp/tl-t095-fx/`；**未向共享树写任何文件**（`find -newermt '2026-09-21 08:37'` 仅命中他人 `docs/BOARD.md`）。

## T095B.1 基线指纹（本席自测）

| 制品 | 本席实测 md5 | 行数 | 与派发自报差异 |
|---|---|---|---|
| `tools/arch-check/scan_lock_free.sh` | `e62d7f41bb9cac9b80cf346c8e1a27b7` | **233** | 无 |
| `docs/ARCHITECTURE.md` | `8148b3b9d3da44fb21da38592f59163a` | 2732 | 无（行数不变） |
| `scan_cpp_sql.sh` / `scan_java_sql.sh` / `check_no_db_links.sh` / `scan_cpp11.sh` / `check_frontend.sh` | `fe056fad…`/192、`aa7b7fb6…`/202、`12561778…`/157、`205c7a06…`/200、`cce41712…`/120 | — | **均未改** |

> `scan_java_sql.sh` 仍为 `aa7b7fb6…`（**与二轮同值**）⇒ 自二轮起未再变。

## T095B.2 ① `R2-1` 收口 —— 定义形态矩阵

**夹具**（`/tmp/verify-archcheck/fx3/e4_defs/x.cpp`，逐行）：`int* read(` / `int& write(` / `char *read(` / `const int* read(` / `int **read(` / `static inline int* read(` / `int * read(`（星号两侧空格）/ `unsigned long long read(` / `auto read(int) -> int*`（尾置）/ `int read(int);` / `int* read(int);`
**命令**：`bash tools/arch-check/scan_lock_free.sh --root /tmp/verify-archcheck/fx3/e4_defs`
**实际**：`EXIT=0`，**11 行全不命中** ✅ —— **派发点名的 3 个（`int*`/`int&`/`char *`）+ 我扩展的 8 个（含 `const int*`、`int **`、`static inline int*`、`int * read`、多词返回类型、尾置返回类型、指针声明）全部判为「定义 ⇒ 不判」**。

**真阳回归**（`fx3/e4_tp/x.cpp`）：`#include <mutex>` / `::send(` / `n = read(fd,…)` / `p->send(` / `q.recv(` / `std::mutex` / `std::lock_guard<std::mutex>` ⇒ **`EXIT=1`，7 行全命中** ✅
**真阴回归**（`fx3/e4_tn/x.cpp`）：`s.read(3)` / 注释-only / `const char* s="mutex"` ⇒ **`EXIT=0`** ✅

## T095B.3 ① 追加边界 —— **新的定义形态误报（逐条列出）**

**未**出现「未发现新的定义形态误报」——本席发现 **2 类新的定义形态误报 + 1 类修复代价的漏判**：

| 编号 | 夹具 | 命令 | 实际 | 期望 |
|---|---|---|---|---|
| **R3-1（低·新）** | `int*`⏎`read(int n){…}`（**返回类型跨行**，名字在行首）/ 亦含 `int`⏎`read(int n){…}` | `scan_lock_free.sh --root fx3/e4_def_multiline`（及 `fx3/e4_explore2` 行 2） | **EXIT=1**（命中 `x.cpp:2`） | 0（定义） |
| **R3-2（低·新，**真阳被放松**）** | `size_t total = count * read(fd,buf,1);` 与 `int r = n & read(fd);` | `scan_lock_free.sh --root fx3/e4_operators`（行 5/6 于 `fx3/e4_explore2`） | **未命中（`EXIT` 由这两行贡献 0）** | 1（真实调用） |
| **R3-3（低·既存）** | `int Foo::read(int){…}` / `int A::write(int){…}`（**限定名定义**） | `scan_lock_free.sh --root fx3/e4_explore2` | **命中（行 3/4）** | 0（定义） |

**`R3-2` 定性（关键）**：`R2-1` 的修法（跳过空白后再跳过 `[*&]`——见脚本 `:164-168`）**无法区分**「`T* name(` 指针定义」与「`expr * name(` 乘法后调用」——二者 token 形态完全相同。代价：`count * read(fd)` / `n & read(fd)` 这类**真实调用**在**二轮为命中（1）**、**三卷被放松为 0**，即派发 ④ 所禁的「**真阳被放松**」确有发生（**新增形状**，非既有夹具变化；旧夹具 `e4_ptr_def` 的 1→0 是 `R2-1` 目标本身）。
**`R3-2` 复现命令**（可直接再跑）：
```bash
mkdir -p /tmp/v3/e4op && printf 'int y = a * read(fd);\nint z = 2 * send(fd);\n' > /tmp/v3/e4op/x.cpp
bash tools/arch-check/scan_lock_free.sh --root /tmp/v3/e4op; echo "EXIT=$?"   # 期望(调用) 1；实际 0
```
**`R3-1` 复现命令**：
```bash
mkdir -p /tmp/v3/e4ml && printf 'int*\nread(int n){ return 0; }\n' > /tmp/v3/e4ml/x.cpp
bash tools/arch-check/scan_lock_free.sh --root /tmp/v3/e4ml; echo "EXIT=$?"   # 期望(定义) 0；实际 1
```

## T095B.4 ② `R2-2` 披露两面（架构 `:320`/`:321`）—— 实测复现，**披露与行为一致**

| 面 | 架构 `:320`（E3）声明 | 本席实测 | 判定 |
|---|---|---|---|
| E3 漏判面 | 行尾 `\` 续行拼接（`std::make_`＋换行＋`unique<int>()`）**会漏判** | `scan_cpp11.sh --root fx/e3_cont_split` ⇒ **`EXIT=0`** | 一致 ✅ |
| E3 过报面 | 多行/自定义定界符原始串内含禁用字样**会被判红**（`R"delim(`⏎`std::optional`⏎`)delim"`） | `--root fx2/e3_raw_multi` ⇒ **`EXIT=1`**（命中 `x.cpp:2`） | 一致 ✅ |
| E3 同行原始串 | 同行 `R"(…)"` 形如普通串 ⇒ **退 0** | `--root fx2/e3_rawstr` ⇒ **`EXIT=0`** | 一致 ✅ |
| E4 过报面 | `:321`：锁原语按**词边界** ⇒ `#include <mutex>` 亦计命中（定解接受、非回归） | `scan_lock_free.sh --root fx3/e4_tp` ⇒ **`EXIT=1`**，命中 `x.cpp:1: #include <mutex>` | 一致 ✅ |

> 结论：**`R2-2` 披露补齐与实际行为一致，无出入。**

## T095B.5 ③ `R2-3` 更正是否落在制品里

| 检查 | 命令 | 结果 | 判定 |
|---|---|---|---|
| `E5` 架构行（`:322`）未被改 | `sed -n '322p' docs/ARCHITECTURE.md \| grep -c 'T-095'` | **0** | 未改 ✅ |
| `E5` 检测判据未改 | `grep -nE '^STMT=\|^DRV_EXEC=\|^DRV_EXEC_PROD=\|^DRV_EMBED=\|^DRV_KW=\|^DAO_CALL=' scan_java_sql.sh` | ① 语句形态 / ②a / ②a2 / ②b / ②c / ③ 全部**与二轮同** | 未改 ✅ |
| `E5` 脚本自二轮起未变 | `md5sum scan_java_sql.sh` | `aa7b7fb6…`（=二轮） | 未改 ✅ |
| `ROOT_EXPLICIT` 退出码分支**已载明** | `grep -nE '退出码语义\|ROOT_EXPLICIT' scan_java_sql.sh` | 脚本头 `:12` 载明「退出码语义（六脚本统一，`T-095`）：… 显式传入的 `--root` 不存在…⇒ 2」；体部 `:97/114/115/122` 为 `ROOT_EXPLICIT` 实现 | **载明** ✅ |
| 架构 `:331` 载明六脚本统一语义 | `sed -n '331p'` | 「**显式传入**的路径不存在亦归 `2`…靠「是否显式传参」区分」 | 载明 ✅ |

> **`R2-3` 结论**：更正与制品一致 —— `E5` **检测判据与架构行确未改**，`ROOT_EXPLICIT` 退出码分支**已在脚本头 + 架构 `:331` 载明**，**非披露缺口**。
> **唯一观察（非缺口）**：`E5` 架构行 `:322` **未内联**「退出码」字样，而 `E1`/`E2`/`E3`/`E4`/`E6` 五行**均已内联**（实测 `退出码` 计数 `:318/:319/:320/:321/:323` 各为 1、`:322` 为 **0**）——`E5` 依赖 `:331` 通用约定行覆盖，属**形态不对称（cosmetic）**，不构成披露缺口。

## T095B.6 ④ 回归（限定）

- **真实仓库六脚本**：`check_no_db_links`/`scan_cpp_sql`/`scan_cpp11`/`scan_lock_free`/`scan_java_sql`/`check_frontend` ⇒ **全 `EXIT=0`** ✅
- **E4 相关夹具复跑**（一轮 + 二轮，19 项）：**唯一变化 = 二轮 `fx2/e4_ptr_def`（`int* read(...)`）`1→0`**（即 `R2-1` 目标本身）；**其余 18 项零变化**（真阳 `::send(`/`read(fd`/`p->send(`/`q.recv(`/`std::mutex`/`shared_mutex` 全 1；真阴 `s.read(3)`/`sock.read(`/`sock.write(`/注释/字面量/warn/sleep_for 全 0）。
- **真阳未被删/未被放松（就既有夹具而言）** —— 但须注意：**新形状 `R3-2`**（`expr * name(`）是**既有夹具未覆盖**之处，其「真阳被放松」系本席**新增探测**发现，非旧夹具变化。

## T095B.7 三卷缺陷清单

| 编号 | 严重度 | 现象（可复现） | 归属 |
|---|---|---|---|
| **R3-1** | **低** | E4：**返回类型跨行**（名字落行首）的定义 ⇒ 误报 —— `int*`⏎`read(int){}` / `int`⏎`read(int){}` → `EXIT=1`。根因：行首名字 `k<1` ⇒ 归为调用。**架构 `:321` 与脚本头均未披露该边界**。 | `T-092`/后续 |
| **R3-2** | **低**（**真阳被放松**） | E4：`count * read(fd,…)` / `n & read(fd)` ⇒ 漏判（`EXIT=0`，**二轮为 1**）。根因：`R2-1` 修法跳过 `[*&]` 后无法区分 `T* name(` 定义与 `expr * name(` 调用。**架构/脚本头未披露该代价**。 | `T-092`/后续 |
| **R3-3** | **低**（既存） | E4：**限定名定义** `int Foo::read(` / `int A::write(` ⇒ 误报（`EXIT=1`，二轮同）。`R2-1` 未涉及。 | 观察/后续 |

> **`R2-1` 本身（枚举 11 形态）已收口**；上述均为其**相邻边界**。三卷**未**发现中/高severity缺陷。

## T095B.8 三卷建议 / 观察

| 编号 | 内容 |
|---|---|
| S3-1 | 建议在架构 `:321` E4 行「过报/未覆盖」披露中**补记 `R3-1`（跨行定义）与 `R3-2`（`expr * name(` 漏判）**，或在脚本头 `:33-36` 未覆盖段并列，使「定解接受」的边界完整。 |
| S3-2 | `R3-2` 的取舍方向（牺牲 `expr * name(` 真阳、保 `T* name(` 定义不误报）**可接受**（M2 交易路径本应无 `read`/`write`）；但应**显式声明**，避免后来者误以为「所有调用都判」。 |
| S3-3 | `E5` 架构行 `:322` 未内联退出码（`E1`~`E4`/`E6` 已内联）；建议为**一致性**补一句，或确认为 `:331` 通用行覆盖之既定形态。 |

## T095B.9 未能验证项

| # | 项 | 原因 |
|---|---|---|
| 1 | `R3-1`/`R3-3` 类定义形态在**真实 M2 业务源码**中的出现率 | `cpp/src/trading/` 仍不存在，业务源码未铺开 |
| 2 | 六脚本在 CI 的**实际接线** | 仓库内仍无 Makefile/CI 调用点 |
| 3 | `scan_lock_free.sh` 除 `sync_hit` 外的其它段是否随 `e62d7f41…` 一并变动 | 仅逐行审阅了 `:99-186`（判定逻辑）；未逐字节 diff 二轮副本（二轮未留 `scan_lock_free` 旧副本，仅记 md5 `e105c649…`/227 与现 `e62d7f41…`/233 的 +6 行） |

## T095B.10 三卷度量

| 指标 | 数值 |
|---|---|
| 指纹核对 | 1 脚本 + 1 架构 + 5 未改脚本 = 7，差异 **0** |
| 三卷独立执行 | ≈ **20** 次（定义矩阵/真阳/真阴/explore2 ×2/披露面 4/回归 19/真实仓库 6） |
| 定义形态覆盖 | **11** 枚举形态（全 0）+ **2** 新发现跨行/限定名（全 1） |
| 二轮夹具回归 | E4 相关 **19** 项：变化 **1**（`e4_ptr_def` 1→0） |
| 三卷缺陷 | **3**（R3-1/R3-2/R3-3，均低） |
| 建议 | **3**（S3-1/2/3） |

## T095B.11 结论

- **一行结论**：**`T-095` 三卷（限定范围）独立复核 = 有条件通过** —— **`R2-1` 枚举的 11 种定义形态全部收口（退 0）**、真阳 7 项仍红、真阴 3 项仍 0、真实仓库六脚本全 0；**`R2-2` 两面披露与行为一致**；**`R2-3` 更正与制品一致（`E5` 判据/架构行未改、退出码分支已载明）**。**条件** = 3 项低severity相邻边界尚**未披露**：**`R3-1`**（跨行定义误报）、**`R3-2`**（`expr * name(` 真阳被放松，二轮为红）、**`R3-3`**（限定名定义误报）。
- **对派发 ① 的直答**：**未能写「未发现新的定义形态误报」** —— 我发现了 **`R3-1`**（返回类型跨行）与 **`R3-3`**（`Foo::read(` 限定名）两类定义形态误报；其中 `R3-1` 是派发点名的「若判红请判为新发现」情形，**已判为新发现**。
- **对派发 ④ 的直答**：既有 19 项 E4 夹具中**唯一变化 = `e4_ptr_def` 1→0**（`R2-1` 目标）；但**新形状 `R3-2`（`expr * name(`）由真阳放松为 0**，属「真阳被放松」，**逐条已列**。
- **风险提示**：`R3-1`/`R3-2`/`R3-3` 在 M2 交易路径铺开后分别会**假红**（跨行/限定名定义）、**漏检**（乘法/按位与后的真实调用）；三者概率均低。
- **建议**：① 接受 `R2-1` 收口（枚举形态达成）；② 将 `R3-1`/`R3-3`（误报）与 `R3-2`（漏判）补入架构 `:321` 披露或脚本头未覆盖段；③ 是否「通过」由**编排器/用户**裁量（**测试席不出发布结论**）。

> **复核性质声明（三卷）**：限定范围独立证伪，结论附「命令 + 输出 + 退出码」；**未采信**派发自报（含所列指纹）；**未修改**任何交付物；夹具位于 `/tmp/verify-archcheck/fx3/`（及复用 `fx/`、`fx2/`）。

---

# `T-016` / `CR-002`（严重）服务端阻断式合规预校验 · **独立复核**（`te-verify-t016`，2026-09-21）

> **复核性质**：**独立证伪**（非复述产出方结论）。**未采信**产出方/编排者任何自报值（`346/0/0/0`、8 探针红数、`max` 口径判定）——**一律自测**。
> **未修改任何交付物**（`java/**` 无写权）；夹具与变异一律在 `/tmp/` 隔离副本内（**共享树零污染**，实测 `find` 无探针文件）。
> 依据：`docs/TASKS.md` `### T-016` 验收5（按锚点定位）＋ `java/CONTRACTS.md` §1.5 ＋ `docs/ARCHITECTURE.md` §6.4「过渡口径」＋ `docs/REQUIREMENTS/PLAN.md` `FR-PLAN-10`/`R-PLAN-3`。

## T16.1 基线指纹（本席自测）

| 项 | 值 |
|----|----|
| 仓库 VCS | **无 git**（`find /root/myspace -name .git` → 空）⇒ 以 **md5 + mtime** 锚定 |
| 隔离副本 | `/tmp/te016iso/{java, docs→共享 docs}`（`rsync -a --exclude target`）；探针文件仅存于此 |
| **被验版本** `PlanService.java` | md5 **`ee574b102c755a4eb2ff474882f298e5`**（1787 行；3×10s 采样稳定） |
| 新增件 | `TradingRoleChecker.java` `22f30733fd695fa7be729f5d68a8f205`（24 行）/ `DmAuthorizationDao.java` `8c592de6b72c49d87fed8201470b7d43`（131 行）/ `PlanCompliancePrecheckTest.java` `8840df5242bb1bd9207e83f39c698362`（800 行） |
| 修改件 | `PlanActionHandler.java` `24a4f0922cbf0338da873b5d543a64c0` / `PlanHandler.java` `eb0961a95a60939b4c992ef65a407b4f` / `Bootstrap.java` `58d97bb1b84107c9664e04c57d22ce18` / `PlanServiceTest.java` `cbbafd2e01ec1f94d5b9fb494d4093ad` |
| 公共件（应未改） | `ApiRoutes.java` `3dec526b47b5b444d57de8898a07e030`（**mtime 2026-09-20 19:10**，早于本交付 ⇒ 未动）/ `PlanDocHandler.java`（09-20 22:28）/ `PlanRoutes.java`（09-21 00:28，属 T-100/103 轮，非本单） |
| 构建（隔离副本） | `mvn -B -o clean test` ⇒ **`Tests run: 346, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS**；结束 **2026-09-21T09:40:20+08:00**（独立复现产出方 `346/0/0/0`） |
| 环境 | Linux / JDK 11 / surefire 3.2.5 / 离线（`-o`）/ **无 DM 实例、无 DM 驱动** |

> ⚠️ **并发写者（实测）**：共享树 `PlanService.java` 于 **09:40:10** 被再次写入；与我 09:37 取样相比**仅 3 处 javadoc 行**差异（`:482` 补 `FR-PLAN-19`、`:601` 补 `FR-PLAN-9`、`:986` 补 `FR-PLAN-11`），**功能零差异、行数均 1787**。本复核以 **`ee574b10…`** 为准（本单验证期间该 md5 未再变动）。

## T16.2 判据逐条对齐（T-016 验收5 / CONTRACTS §1.5 / ARCH §6.4）

| # | 判据 | 实测证据 | 判定 |
|---|------|---------|------|
| ① | 服务端自行重算 **6 项**（①②④⑤⑥⑦） | `submitForApproval` 内依次调 `requireDocElements/requirePositionSeparation/requireAuthorization/requireWhitelist/requireTermRedline/requireQuantity`；6 项各自独立阻断探针全绿 | **符合** |
| ② | 任一不通过即**阻断**提交 | 6 项各自：抛 `BizException` 且 `planDao` 状态保持 `DRAFT`、`approvalDao.rows` 空 | **符合** |
| ③ | **短路顺序**（status→书面→岗位→授权→白名单→期限→数量） | 5 组「多项同时不满足」探针：⑥>⑦、⑦>①>②、①>②、②>④、④>⑤ 均只报最前者；非草稿 ⇒ `10009` | **符合** |
| ④ | 四项载荷 `ruleId`/`current`/`threshold`/`nextStep` | 6 项各测：`ruleId` **精确等于** `R-PLAN-3/①…/⑦`，其余三项非空 | **符合** |
| ⑤ | `ruleId` 语义基准 = `REQUIREMENTS/PLAN.md` 校验项编号 | 常量 `RULE_AUTH=①`（授权）/`RULE_WHITELIST=②`/`RULE_SCALE=③`/`RULE_TERM=④`/`RULE_QTY=⑤`/`RULE_DOC=⑥`（书面文件）/`RULE_POSITION_SEPARATION=⑦`（岗位分离）——**与 PLAN.md「校验项清单」逐字一致**，未按执行位号取号 | **符合** |
| ⑥ | **禁止**以客户端 `precheckResult` 作为通过依据（忽略内容；缺失/为空不拒单） | 参数 `clientPrecheckResult` 在 `submitForApproval` **方法体内零引用**；伪造/空串三例实测见 T16.3 | **符合** |
| ⑦ | ③ 规模红线**不在序内**，M1 不得因 ③ 单项拒单 | 6 项中无 ③ 判定；③ 恒列 `unverified`；六项全满足即 `PENDING_APPROVAL` | **符合** |
| ⑧(a) | 提交响应体含 `unverified` 且含 `"R-PLAN-3/③"` | 经**完整 `PlanRoutes` 路由** `POST /plan/{id}/submit` ⇒ `data.unverified=["R-PLAN-3/③"]`、`data.precheckResult` 存在 | **符合** |
| ⑧(b) | 落库 `t_plan_approval.precheck_result.unverified` 含之 | 审批行 `precheckResult` 实测含 `"unverified":["R-PLAN-3/③"]` + **6 项** `items`、无 `FAIL` | **符合** |
| ⑧(c) | **审批接口响应**含之 | `GET /plan/{id}` 详情 `data.precheckResult.unverified` 含 `"R-PLAN-3/③"` | **符合** |
| ⑧(c) | **审批页面**展示之 | `web/js/pages/plan.js` **0 处** `precheck/unverified`；全 `web/` 亦 0 处 | **❌ 不符**（见缺陷 `TE-016-001`） |

> 错误码映射实测：⑥/④/⑤→`10012`（`COMPLIANCE_GATE_FAILED`）、⑦→`10005`、①子账户→`10004`、①品种/②→`10003`、非草稿→`10009`。**与 ARCH §6.4/验收5 逐一相符。**

## T16.3 最关键反例 —— 客户端串不可信（`CR-002` 本体，**独立构造**）

| 输入 | 实际 | 期望 | 判定 |
|------|------|------|------|
| `precheckResult` = `null` / `""` / `"   "` / `"{}"` / `"null"` / `"not-json"` / `"{\"items\":[]}"`（7 例） | 全部 `PENDING_APPROVAL`，服务端快照 `unverified=["R-PLAN-3/③"]` | **不得拒单** | **符合** |
| 伪造**全 PASS** + `"unverified":[]`，而实际 ② 白名单不满足 | 抛 `10003` + `ruleId=R-PLAN-3/②`；状态保持 `DRAFT`；**无审批留痕** | 仍须阻断 | **符合**（修复前可伪造绕过的核心反例已闭合） |
| 伪造**全 FAIL**，而实际六项全满足 | `PENDING_APPROVAL`；服务端 `unverified=["R-PLAN-3/③"]`（**不取客户端值**） | 应通过 | **符合** |

## T16.4 ⑥ 期限红线多合同取值（高危点①，**决定性探针**）

产出方口径：「绑定敞口所涉 `CONTRACT_UNEXECUTED` 明细来源合同的 `contract_end_date` **最大值** + 30 天」，再与「建仓日 + 12 个月」取 `min`。

独立探针（绑定敞口 1 挂**两条**采购合同：`77→2026-10-01`、`88→2026-12-01`；`term_start=2026-09-20`）：

| 用例 | max 口径预期 | min 口径预期 | 实测 | 判定 |
|------|-------------|-------------|------|------|
| `termEnd=2026-11-30` | 上限 `max+30=2026-12-31` ⇒ **通过** | 上限 `2026-10-31` ⇒ **误拒** | **通过**（`PENDING_APPROVAL`） | **实现确为 max** |
| `termEnd=2027-01-15` | 阻断且阈值=`2026-12-31` | 阻断且阈值=`2026-10-31` | 阻断，`threshold` 含 **`2026-12-31`** | **实现确为 max** |

⇒ 产出方**实现的确实是 `max`**（非 `min`），且该自定口径**已在代码注释写明**（`PlanService.java:894-895`「…`contract_end_date` **最大值**…」）。与编排者裁定（采纳 `max`）一致。**代码注释已单列口径来源**。

> **口径来源复核**：编排者称 `FR-PLAN-18`/`DATA_MODEL.md:1668`/`PLAN.md:44` 三处均用「实货合同到期日」单数、未规定多合同取值 ⇒ 属**需求口径缺失**。本席**未据此阻断**（编排者已裁定采纳 `max`）；仅提示这是**需求缺口**而非实现缺陷，建议由产品经理在 `PLAN.md` 补一句多合同取值规则。

## T16.5 ⑤ 数量「加回本计划自身」口径（高危点②，**独立证伪**）

**实现**：`uncovered = exposureQty − coveredQty + bind.coveredQty`（`PlanService.java:936-937`）。

**独立推理链（读编制路径 + 校验路径）**：
1. 绑定写入时机在 **编制**（`createPlan`：`:586` `exposure.coveredQty += bind.coveredQty`，随即 `:589` `updateById`）——即**提交前**该敞口 `covered_qty` **已含本计划自身占用**；
2. 全 `PlanService` 内 `exposure.coveredQty` **只有这一处 `+=`**，`reject`/`withdraw`/`terminate` **均不递减**（`:1027`/`:1065`/`:1136` 仅改状态与留痕）⇒ **单调不减**；
3. `uk_plan_exposure(hedge_plan_id, exposure_id)`（`DATA_MODEL.md:1723`）唯一 ⇒ 同一计划对同一敞口**至多一行**，`coveredQty_e − bindQty_e` 恰为「他人占用」。

⇒ `available = Σ_e (exposureQty_e − coveredByOthers_e)`，**语义正确**。

**实测边界（独立探针）**：

| 场景 | 期望可用量 | 实测 `threshold` | 判定 |
|------|-----------|-----------------|------|
| 敞口 100 / covered 80（**全部为本计划自身**）/ bind 80 | **100**（**不加回则 =20 ⇒ 会误拒**） | `planQty=100` **通过**；`planQty=101` 阻断，阈值含 **`100`** | **加回正确，消除误拒** |
| 敞口 100 / covered **100**（他计划 20 + 本计划 80）/ bind 80 | **80** | `planQty=81` 阻断，阈值含 **`80`** | **无漏拒** |

**结论**：加回**必要且正确**——消除「自我重复计数误拒」，且**未观察到漏拒**。`PlanService.java:926-927` 注释已写明理由。

**残留观察（非缺陷）**：`requireQuantity` 对**负**贡献（`uncovered ≤ 0`）**跳过不累加**（`:938`）；仅当「他人占用 > 敞口总量」（数据不一致）时才可达，M1 正常数据下不显形。

## T16.6 反退化变异（**8 探针，自建**，`/tmp` 隔离副本；**不复用产出方探针**）

方法：对 `PlanService.java` 逐项注入变异，跑 `-Dtest=Te016IndepProbeTest,PlanCompliancePrecheckTest`（41 例），记红数。

| 变异 | 内容 | 红数（Failures/Errors） | 判定 |
|------|------|------------------------|------|
| BASELINE | 未变异（**clean**） | **0/0**（41 例全绿） | — |
| M1 | 移除 ⑥ 书面文件重算 | **6**/0 | 必红 ✓ |
| M2 | 移除 ⑦ 岗位分离重算 | **6**/0 | 必红 ✓ |
| M3 | 移除 ① 操作授权重算 | **8**/0 | 必红 ✓ |
| M4 | 移除 ② 白名单重算 | **8**/0 | 必红 ✓ |
| M5 | 移除 ④ 期限重算 | **7**/0 | 必红 ✓ |
| M6 | 移除 ⑤ 数量重算 | **6**/0 | 必红 ✓ |
| M7 | `M1_UNVERIFIED` 置空（去 `unverified` ③） | **9**/0 | 必红 ✓ |
| M8 | 恢复「空串即拒单」 | **30/7** | 必红 ✓ |

**方法学校验**：对 M1 另以 `clean` 复跑，红数**同为 6**（一致）⇒ 增量编译结果可信。
> ⚠️ **踩坑记录（诚实披露）**：首轮变异脚本因**两实例并发启动**（我先误起两个后台脚本）致共享的**隔离副本** `PlanService.java` 被交错改写，并使一次 `BASELINE` 误显 **12 红**（**陈旧 class 假红**）。已 **kill 两脚本 + 从共享树恢复**（md5 复位 `ee574b10…`）后重跑；**上表为洁净重跑结果**。此为**我的操作事故**，非产品缺陷。

## T16.7 回归与公共件

| 项 | 命令 | 结果 |
|----|------|------|
| 全量（隔离副本，clean，无排除） | `mvn -B -o clean test` | **346/0/0/0**，BUILD SUCCESS（09:40:20） |
| SQL 外泄审计 | `bash tools/arch-check/scan_java_sql.sh` | **退出码 0**（「dao 之外未发现 SQL」） |
| `t_authorization` SQL 唯一位置 | `grep -rln t_authorization java/src/main/java` | 仅 `dao/impl/DmAuthorizationDao.java` + 实体/接口/javadoc（`PlanService:832` 为 `source` 字串，非 SQL） |
| 公共件未动 | md5 + mtime | `ApiRoutes.java`/`PlanDocHandler.java` mtime **早于本交付**，未改 |
| 无旁路 | `grep "PENDING_APPROVAL"` | 仅 `submitForApproval:728` 一处赋值；唯一调用点 `PlanActionHandler` SUBMIT |
| 共享树污染 | `find /root/myspace -name Te016IndepProbeTest*` | **空**（探针仅存 `/tmp`） |

## T16.8 缺陷清单（与建议分开；可复现）

### `TE-016-001`（中，**范围/归属待裁**）③ 可见性之 (c)「审批页面展示」缺失

- **对应用例**：无 `TC-*`（`CR-002` 专项复核；对 `T-016` 验收5、(c)）
- **对应需求**：`FR-PLAN-10` 验收2（`REQUIREMENTS/PLAN.md:253`）、`T-016` 验收5、`CONTRACTS §1.5` —— 三处**均**要求「(c) 审批接口响应**与审批页面均展示**该标记，**任一缺失即判未通过/缺陷**」
- **复现**：`grep -rn "precheck\|unverified" web/js/pages/plan.js web/` ⇒ **0 命中**；`plan.js`（18403B，mtime 09-21 07:23）未渲染 `unverified`
- **实际**：审批页面**不展示** `R-PLAN-3/③` 未校验标记（API 侧已具备）
- **期望**：审批页面展示该标记
- **归属裁定建议**：`T-016` 产出面 = `java/**` + REST `/plan/**`；**页面属 `T-020`（frontend，`TODO`）**。⇒ 需编排者裁定：由 `T-020` 承接，或视为 `T-016` 验收5 的**未达标项**。**本席不擅自判 T-016 通过/不通过**，只报事实。
- **严重度**：中（不阻断提交正确性，但**逐字命中**验收判据的「缺一即缺陷」）

> 其余判据（①②③④⑤⑥⑦⑧(a)(b)(c-API)）**未发现缺陷**。

## T16.9 建议（非缺陷）

| # | 建议 |
|---|------|
| S-1 | `PlanServiceTest.java:281` `@DisplayName` 字样「ruleId=R-PLAN-8」已过时（现报 `R-PLAN-3/⑥`）；断言未校验 `ruleId` 故不红——建议改为 `R-PLAN-3/⑥` 并补 `ruleId` 断言 |
| S-2 | `requireQuantity:938` 对负贡献跳过不累加；正常数据不可达，建议注释说明「假设 covered_qty ≤ exposure_qty」或改为显式告警 |
| S-3 | 需求缺口：多合同期限取值（`max`）**未在 `REQUIREMENTS/PLAN.md` 规定**——建议产品经理补一句，避免下游按 `min` 理解 |
| S-4 | **并发写者纪律**：共享树 `PlanService.java` 在验证期间被跨实例写入（09:40:10）；该文件为 `T-016`/`T-100`/`T-102`/`T-104` 共同写入面，建议严格串行 |

## T16.10 未能验证 / 未覆盖项（不沉默略过）

| # | 项 | 原因 |
|---|----|------|
| 1 | (c) **审批页面渲染** | `T-020` 未实现（`web/js/pages/plan.js` 无相关代码）⇒ 无法执行 |
| 2 | 反退变的**全量 346 影响面** | 变异仅跑 2 个 precheck 测试类（41 例），未对每个变异跑全量（时间成本） |
| 3 | **DM8 真库**端到端 | 无 DM 实例/驱动；全部为离线单测（内存假 DAO），`DmAuthorizationDao.findEffective` 仅经 `DaoColumnAlignmentTest` 间接核 |
| 4 | 8 处「构造点」**逐一执行** | 仅经「全量 compile + 346 全绿」间接覆盖 |
| 5 | 历史行（`CR-002` 前客户端自由串）降级路径 | 仅审读 `latestPrecheckSnapshot` `catch→null`；未构造历史落库行实测 |

## T16.11 度量

| 指标 | 数值 |
|------|------|
| 独立探针 | `Te016IndepProbeTest` **22 例**（独立构造；仅假 DAO 样板复用） |
| 产出方用例（对照） | `PlanCompliancePrecheckTest` **19 例** |
| 基线 | 隔离副本全量 **346/0/0/0**；两 precheck 类 **41/0/0/0** |
| 反退化变异 | **8** 个，**全部必红**（红数 6/6/8/8/7/6/9/30-7） |
| 判据对齐 | **13/13**（含 (c) 拆 API/页面两判）——12 符合、1 不符 |
| 缺陷 | **1**（TE-016-001，中，归属待裁） |
| 建议 | **4** |

## T16.12 结论

- **一行结论**：**`T-016`/`CR-002` 服务端阻断式合规预校验 = 有条件通过** —— **`CR-002` 本体（客户端串不可信：空/伪造全 PASS/伪造全 FAIL）三例全部实测符合**；**服务端自行重算 6 项 + 短路顺序 + 四项载荷 + `ruleId` 语义基准 + ③ 不拒单** 逐条符合；**⑥ 期限多合同实测确为 `max`**、**⑤ 数量「加回自身」独立证伪为必要且正确**；全量 `346/0/0/0`、`scan_java_sql.sh` 退 0、反退化 8 探针全红。
- **条件**：**③ 可见性之 (c)「审批页面展示」缺失**（`TE-016-001`，中）——需编排者裁定归属（页面属 `T-020`）。
- **与产出方/编排者说法**的每一处差异：
  1. **编排者派发摘要**把 (c) 简化为「`GET /plan/{id}` 详情响应含之」；**权威判据**（T-016 验收5 / `CONTRACTS §1.5` / `FR-PLAN-10` 验收2）实为「审批接口响应**与审批页面均须展示**」——**页面侧未落地**，据实列为缺陷。
  2. 产出方称「共享树 `346/0/0/0`」：**已独立复现**（隔离副本 346/0/0/0），一致。
  3. 产出方称「反退化 8 探针全红」：**已独立复现**（自建 8 探针全红），一致（红数因探针集不同而与产出方数值不同，属**探针不同**而非分歧）。
  4. **共享树 `PlanService.java` 在验证期间被再次写入**（09:40:10，javadoc-only）——产出方未提及；本席据实记录，功能零影响。
- **风险提示**：`TE-016-001` 若不在 `T-020` 交付前收口，则 `FR-PLAN-10` 验收2 按字面**不可通过**。
- **建议**：① 接受 `CR-002` 核心修复（服务端强制与不可信客户端串已闭合）；② 将 `TE-016-001` 指派 `T-020` 或回写 `T-016` 验收5 的范围；③ 是否「通过/准出」由**编排器/用户**裁量（**测试席不出发布结论**）。

> **复核性质声明**：独立证伪，结论附「命令 + 输出 + 退出码 + 红数」；**未采信**任何派发自报值；**未修改**任何交付物（共享树 `java/**` 零改动，`PlanService.java` md5 全程 `ee574b10…`）；夹具/变异位于 `/tmp/te016iso/`、`/tmp/te016_mut.sh`、`/tmp/PlanService.orig`（已清理）。

## T16.13 复核后补记（**裁定注记** + 基线 delta 核验 + 环境窗口）

**（一）裁定注记（编排者 2026-09-21 裁定，本席如实登记）**：
- 缺陷 `TE-016-001`（③ 可见性之 (c)「审批页面展示」缺失）**归属裁定 = `T-020`**（套保计划前端页面；页面不在 `T-016` 产出面内）。
- **`T-016` 结论 = `DONE`（附条件）**；**条件 = (c) 接口响应侧由 `T-016` 交付（已符合）、页面侧由 `T-020` 承载**。
- **口径依据**：**「产出面 ≥ 验收面」**——判据不应要求产出面之外的交付物 ⇒ 义务**移至正确承载任务，且不减**（PM 将在 `T-020` 验收补「审批页面展示 `precheckResult.unverified`（含 `"R-PLAN-3/③"`）」；`T-016` 验收5 注明接口/页面范围划分）。
- ⚠️ **本席如实保留的分歧事实**：**若按 `T-016` 验收5 字面从严，「审批页面」项未达标**；编排者判该条属**判据越界**（要求产出面外）而非实现缺失。**是否改判由用户裁量**（测试席不出发布结论）。

**（二）基线 delta 核验（本席自测；delta = `9cd768a2…` → `ee574b10…`）**：
- 共享树现值自测：`PlanService.java` = **`ee574b102c755a4eb2ff474882f298e5` / 1787 行**；`java/CONTRACTS.md` = **`0297ff76e4ac1463014985d4b19288bb` / 787 行**（**与编排者更正后基线一致**）。
- **证明 delta 为注释级（可复现）**：将现值中 3 处 javadoc（`:482` `+{@code FR-PLAN-19}`、`:601` `+{@code FR-PLAN-9}`、`:986` `+{@code FR-PLAN-11}`）回退后，md5 **逐字节 = `9cd768a289c3e1b2ffc949f4788c6053`**（⇒ delta **恰为这 3 行**）；
- **剔注释后比对**（`strip_java_comments.awk`）：old/new 均 **`027c252f3e510438207f456cb2cdc75c`**、`diff` 空 ⇒ **代码零差异**；`grep -c "FR-PLAN-19\|FR-PLAN-9\|FR-PLAN-11"`（剔注释）= **0**。
- ⇒ 结论：**注释级 delta，功能零影响**；本席 T16.1–T16.12 的功能结论**不受影响**。

**（三）环境窗口留痕（**非缺陷**；编排者提示）**：另一席位于 **2026-09-21T09:51:07–09:54:47** 在**共享树**跑 `mvn -B -o clean test`（`clean` 清空共享 `target/`）。本席**全部 maven 仅在 `/tmp/te016iso`**（日志中 **0 处**共享树路径 `grep` 为空）⇒ **主结论不受该窗口影响**；为彻底排除疑义，已在窗口**之后**复跑 clean 基线（**10:07:48** = 两 precheck 类 `41/0/0/0`）与 M1 clean（**10:08:56** = 6 红），结论**稳健**。本席**未在共享树执行任何构建**。

> 本补记由测试工程师（`te-verify-t016`）追加，仅新增本节；T16.1–T16.12 正文未改（其定稿 md5 = `7365f4957613a459dc5ccfc5f6662068` / 1689 行）。

---

# `T-109` 三轮独立复核证据（**事后补录**，2026-09-21）

> **补录声明**：本节为 **`T-109`**（补齐 15 条「已声明未注册」端点 → **19 个 `(方法,路径)` 对**）**三轮独立复核**结论的**事后补录**。
> `T-109` 已走完完整闭环并转为 **`DONE（附条件）`**，**但其复核证据此前仅存在于编排者与复核者的消息往来中、从未落入任何制品**——编排者实测：本节写就前 `docs/TEST_RESULT.md` **全篇 0 处提及 `T-109`**（其它任务如 `T-090`~`T-095`、`T-016` 均在本文件有证据章）。
> ⇒ 本节**如实声明为「事后补录」**（**非当场记录**）：三轮结论**先经消息往来确认、其后补录于此**。
> **数字口径**：章内引用的各轮数字**均为各轮复核者的实测值并附时点**；`md5` / 行数一律**以实时 `md5sum` 为准**。
> **三轮复核由不同实例执行**（`te-verify-t109` / `te-verify-t109b` / `te-verify-t109c`），结论**各自独立取证**（不采信产出方结论）。
> **本节为 `docs/TEST_RESULT.md` 的唯一改动**，其余章节一字未动。

## T109.0 冻结信息与基线

| 项 | 内容 |
|----|------|
| 复核对象 | `java/**`（`T-109` 交付：**CONFIG 9 / SPOT 5 / PLAN 1** = **15 条路径** / **19 个 `(方法,路径)` 对**） |
| 路由计数 | **48 → 67**（业务 **46 → 65**，**+19**） |
| `ApiRoutes.java` | **全程未改** = `3dec526b47b5b444d57de8898a07e030`（三轮复核及补录者实测一致） |
| 最终冻结点构建 | 隔离副本 `mvn -B -o clean test` = **`375 / 0 / 0 / 0`**（`2026-09-21T12:32:23+08:00`，**`clean`、无排除**） |
| 环境 | 无 DM8 实例、无 DM 驱动（离线 `-o`）；一切版本以 **md5 + 取样时刻**锚定（仓库**无 git**） |
| 执行人 | 三轮三实例：`te-verify-t109`（首轮）/ `te-verify-t109b`（复检）/ `te-verify-t109c`（增量复核） |
| 补录人 | 测试经理席（`tm-t109-evidence`，2026-09-21） |

> 说明：**各轮数字均为该轮复核者的实测值并附时点**；`md5` / 行数一律以实时为准。

## T109.1 第一轮 `te-verify-t109` —— 结论：**有条件通过**（缺陷未清零 ⇒ 退回）

**独立实测**：隔离副本 `348/0/0/0`；**19 对全注册**、路由 **48→67**；`ApiRoutes.java` **未改**；**自设 2 条反退化变异**（其中一条把 `POST` 改 `PUT`，**验证判据对 `(方法,路径)` 精确区分**）；复位后与共享树 `diff` **为空**。

**7 条缺陷**（严重度 / 摘要 / 违反 FR）：

| 编号 | 严重度 | 摘要 | 违反 FR |
|------|--------|------|---------|
| `TE-T109-001` | **严重** | `/spot/price/import` **未实现输入契约**（无 CSV / 粘贴解析、无表头列完整性校验、无行号定位；取 `productId` 而非「**品种编码**」） | `FR-SPOT-11` / `FR-SPOT-12` |
| `TE-T109-002` | **严重·安全** | `/spot/exposure/export` **无可见性过滤** ⇒ 任意已认证用户传任意 `subAccountId` 可**越权导出** | `FR-SPOT-18` |
| `TE-T109-003` | 一般 | `/spot/production` 算了 `overlapFlag` **却不回传** | `FR-SPOT-8` 验收 3 |
| `TE-T109-004` | 一般 | `/config/import` 缺**取值范围预校验** | `FR-CONFIG-13` 验收 2 |
| `TE-T109-005` | 一般 | `/spot/doc/sub-account` **不重算敞口**（仅回 `recomputeRequired=true`） | `FR-SPOT-16` 处理规则 / 验收 1 |
| `TE-T109-006` | 建议 | `FR-SPOT-16` 验收 2 拒因列 `exposureIds` 而非「**计划**」 | `FR-SPOT-16` 验收 2 |
| `TE-T109-007` | 建议·待裁定 | `/spot/price/export` **确无对应 FR**（产出面 > 需求面） | — |

**另披露**：`ModuleRoutesConsistencyTest` 仍是「**路径级·子集 + 硬编码下限**」，对「**少注册**」**结构性失明**（`T-097` 目标形态）；并实测 **`T-097` 升级为 `(方法,路径)` 等值断言后会红 8 条、全部是 AUTH**（指向 `T-010`）。

## T109.2 第二轮 `te-verify-t109b` —— 结论：**有条件通过**（首轮 7 条复核）

**独立复现**：**`368 / 0 / 0 / 0`**（基线 348 ⇒ **+20** = `SpotT109FixTest` 15 + `ConfigImportRangeTest` 5）。

**逐条判定**：`001` / `002` / `003` / `004` / `006` **真修好**；`005` **诚实阻塞**；`007` 待裁定。

**`TE-T109-002` 安全项——以 10 例探针证伪（未可绕过）**：换 `subAccountId` → `10004`；**不传** → `10002`；传哨兵 `0` 作 target → `10002`（而「调用者授权集含哨兵 `0`（`CROSS_READONLY`）」→ **放行**，符合 `FR-AUTH-13`）；无授权 / 过期 / 未生效 → `10004` + 留痕（`R-AUTH-8`）。

**`TE-T109-005` 阻塞核实成立**：`java/src/main` 与 `cpp` 内**无任何敞口推导实现**；`computeExposure` 的三分量与合计**由调用方传入**（服务仅校验 + 落库）；故「无引擎」说辞**诚实**。

**latent 缺陷（首轮未列）**：`hasOverlappingPlan` 原用 `PageQuery.of(..., "1", ...)`，而 `PageQuery.ALLOWED_SIZES = {10,20,50,100}` ⇒ `POST /spot/production` **原会运行期 500**，且**无用例覆盖而逃过 348 全绿**；修复后**全仓同类清零**（44 处 `PageQuery.of(` 尺寸全合法）。

**向后兼容成立**：`SpotService` 旧 **10 参 / 13 参**构造保留并**逐层委派**；`SubAccountVisibility` 新增的是 **`default`** 方法（非抽象）⇒ **未重演「给接口加抽象方法打断 test 侧替身」事故**。

**探索性新发现 4 条**（越出本单 7 条）：

| 编号 | 级别 | 发现 | 处置 |
|------|------|------|------|
| (A) | **中·安全同族** | `T-013` **SPOT 写面**（`createPurchaseContract` / `createSalesContract` / `createInventory` / `createWip` / `putSpotPrice` / `computeExposure` + 4 handler）取客户端 `subAccountId` **且无可见性**，且**原任务表无承载** | 立 **`T-110`** 承接 |
| (B) | 低·待裁定 | `assertSubAccountVisible` 对「含哨兵 `0`」一律放行 ⇒ `CROSS_READONLY` 用户**可写**；与 `T-102` 语义同源 | 登记为 **`T-102` 口径待裁定** |
| (C) | 低·防御缺口 | `importSpotPrices` 在 `CSV_IMPORT` 下取首行**未判 `null`** ⇒ 传 `lines:[null,…]` **NPE → 500** | 转第三轮复核 |
| (D) | 建议 | `SpotService` 注释把 `006` 误标为 `004` | 转第三轮复核 |

## T109.3 第三轮 `te-verify-t109c` —— 结论：**有条件通过**（仅核 `(C)` / `(D)` 两处残差的**有界增量**）

**独立复现**：**`375 / 0 / 0 / 0`**（`2026-09-21T12:24:04+08:00`，基线 368 ⇒ **+7**）。

**`(C)` 行为实测**：`lines` 取 `null` / `[]` / `[null,…]` / 空表头 / 全空行 ⇒ **一律 `10019` / HTTP 422，无 500**、不产生记录；**退化态坐实改前确为 500**（`code 10022 内部错误`）。

**口径评断**：「首行为空 / 缺表头 ⇒ 整体拒」**站得住**（依 `FR-SPOT-12`「校验表头列完整性」）；**但**「`FR-SPOT-12` 规定首行是表头」属**推断、非原文**。

**纠正三处**：①「仅此一处」不完整（另有 2 处 `005` / `006` 混淆 + javadoc 自相矛盾）；②「首行为空行 ⇒ NPE / 500」**表述过宽**（空串表头改前**并非** 500，已按缺列走 `10019`；真正 NPE→500 的只有 `null` 行）；③「编译 WARNING=0」未复现（实测 1 条，属**既有测试**、非本单引入）。

**无回归抽查 5 项全在**：`CSV_HEADER` 列校验、`requireSubAccountVisible`（**4 调用点 / 3 方法**〔第三轮时点值〕）、`overlapHint`、`/config/import` 取值域、`plans=[…]` 拒因。

**另注**：`TE-T109-005` 在测试中**无任何对应断言**（`recomputeRequired` 字样仅出现在 `src`、无测试引用）⇒ **覆盖缺口**，已记入 **`T-111`**。

## T109.4 收口结论

- **`T-109` → `DONE（附条件）`**；**唯一附条件** = `TE-T109-005` 验收 1（变更后重算敞口）未达成（**诚实阻塞**）⇒ **`T-111`** 承接（先造敞口推导引擎，再回填验收 1）。
- 由本单衍生的新任务：**`T-110`**（SPOT 写面可见性强制，P0）、**`T-111`**（敞口推导引擎，M2，P1）。`TE-T109-007`（`/spot/price/export` 无对应 FR）**待产品经理裁定**（补 `FR` 或删路由，**两者均须走 `CH-xxx`**）。
- **两处严重缺陷的最终处置**：`TE-T109-001` 按同仓范式（`ProductImportHandler` + `VarietyService.CSV_HEADER` + `ProductDao.findByCode`）实现输入契约；`TE-T109-002` 接入既有 `SubAccountVisibility` 并**证伪**绕过。

## T109.5 度量

| 指标 | 数值 |
|------|------|
| 三轮结论 | 首轮「有条件通过」（7 缺陷未清零 ⇒ 退回）→ 复检「有条件通过」→ 增量复核「有条件通过」 |
| 基线（各轮隔离副本） | `348` → `368` → `375 / 0 / 0 / 0` |
| 最终冻结点 | `375/0/0/0`（`2026-09-21T12:32:23+08:00`，`clean`、无排除） |
| 缺陷 | **7**（严重 2 / 一般 3 / 建议 2）；其中 `005` **诚实阻塞**、`007` **待裁定** |
| 反退化变异（首轮自设） | 2 条（含 `POST`→`PUT`，验证 `(方法,路径)` **精确区分**） |
| 路由计数 | **48 → 67**（业务 46 → 65） |
| `ApiRoutes.java` | 全程未改 `3dec526b…` |
| 衍生新任务 | **`T-110`** / **`T-111`**；`TE-T109-007` 待产品经理裁定 |

## T109.6 补录者抽验（本席独立执行，**不采信转述**）

> 依本项目「转述须抽验」纪律，补录人对可低成本复现的断言做了独立实测（**事后补录，非当场取证**）：

| # | 抽验项 | 命令 | 实测 | 与转述 |
|---|--------|------|------|--------|
| 1 | `ApiRoutes.java` md5 | `md5sum …/web/ApiRoutes.java` | `3dec526b47b5b444d57de8898a07e030` | **一致** |
| 2 | 缺陷编号在 `docs/TASKS.md` 有登记 | `grep -n 'TE-T109' docs/TASKS.md` | `TE-T109-001` ~ `007` **7 条全在**（`docs/TASKS.md` §`T-109` 缺陷登记表） | **一致** |
| 3 | `T-109` / `T-110` / `T-111` 任务登记 | `grep -n 'T-109' docs/TASKS.md` | `T-109` = **`DONE（附条件）`**；`T-110` / `T-111` **均 `TODO`**（§1 任务表） | **一致** |
| 4 | 冻结点 `375/0/0/0` + 时点 | `grep -n '375/0/0/0' docs/TASKS.md` | §`T-109` 备注载 `375/0/0/0`〔`2026-09-21T12:32:23+08:00`〕 | **一致** |
| 5 | 路由 `48→67` | `grep -n '48→67' docs/TASKS.md` | §`T-109` 备注载「路由 **48→67**」 | **一致** |

⚠️ **一处时点差异（非 `T-109` 问题，据实披露）**：补录时（`2026-09-21T12:39+08:00` 之后）实测 `SpotService.java` = `6cd9cf5987e048fa4c3ee5b207990933`，其 `requireSubAccountVisible` **调用点已多于**第三轮记录的「4 调用点 / 3 方法」（实测 **12 处**），并新增 `java/src/test/java/com/hedgeguard/service/SpotT110VisibilityTest.java` ⇒ 系**并行任务 `T-110` 正在共享树实施**（`SpotService.java` mtime `12:39:30`，**晚于** `T-109` 冻结点 `12:32:23`）。**本节各轮数字均为各轮时点值，以实时 `md5sum` 为准。**

> **本节由测试经理席（`tm-t109-evidence`）补录**，仅新增本节；`T-109` 原始三轮结论先经消息往来确认、其后补录于此（**事后补录，非当场记录**）。本节各轮数字为各轮实测值并附时点，`md5` / 行数**以实时为准**。

---

# `T-110` 独立复核证据（**事后补录**，2026-09-21）

> **补录声明**：本节为 **`T-110`**（补齐 SPOT 写面〔`T-013` 范围〕**子账户可见性强制**，**8 处新增 `requireSubAccountVisible`**）**独立复核**（执行人 `te-verify-t110`）结论的**事后补录**。
> `T-110` 已转为 **`DONE`**（判据 = **授权 + 独立验证**）、独立复核判定**通过**，**但其复核证据此前仅存在于编排者与复核者的消息往来中、从未落入本文件**——补录前 `docs/TEST_RESULT.md` **全篇无 `T-110` 节**（末节为 `## T109.6`）。项目经理实测时点：全仓 `grep -rln 'te-verify-t110'` = **0 命中**（其**结论摘要**后续由项目经理回写入 `TASKS.md` / `BOARD.md`，系**状态注记、非证据**；补录者实测现为 **2 命中**——见 T110.9）。
> ⇒ 本节**如实声明为「事后补录」**（**非当场记录**）：结论**先经消息往来确认、其后补录于此**。
> **数字口径**：章内引用数字**均为复核者（`te-verify-t110`）的实测值并附时点**；`md5` / 行数一律**以实时 `md5sum` 为准**（仓库**无 git** ⇒ 以**内容级 md5**〔`find … | sort | xargs md5sum | md5sum`〕代替 VCS 基线锚点）。
> **本节为 `docs/TEST_RESULT.md` 的唯一改动**，其余章节一字未动。

## T110.0 冻结信息与基线

| 项 | 内容 |
|----|------|
| 复核对象 | `java/**`（`T-110` 交付：SPOT 写面 **8 处新增** `requireSubAccountVisible`） |
| 冻结点构建 | 隔离副本 `/tmp/te-t110`，`mvn -B -o clean test` = **`394 / 0 / 0 / 0`**（`2026-09-21T12:58:58+08:00` 起 → **`13:00:29+08:00`** 止；**`clean`、无排除**） |
| 逐类核对 | `SpotT110VisibilityTest` = **19**、`SpotServiceTest` = **15**、`SpotT109FixTest` = **22** |
| 基线增量 | 基线 **375**（`T-109` 冻结点）⇒ **+19** |
| `SpotService.java` | `6cd9cf5987e048fa4c3ee5b207990933` / **1606** 行 |
| `SpotServiceTest.java` | `cea066ddf43959fc6463ff420332c1c8` / 896 行 |
| `SpotT110VisibilityTest.java` | `297d09569637474fe07fe548c05d6376` / 815 行 |
| `ApiRoutes.java` | **全程未改** = `3dec526b47b5b444d57de8898a07e030` / 117 行 |
| 整树（内容级 md5） | 复核者述 `a8988c240832c6916697721d753f96e7`（⚠️ **补录者独立实测未能复现**，见 T110.9） |
| 环境 | 无 DM8 实例、无 DM 驱动（离线 `-o`）；**无 VCS 基线**（整仓无 git）⇒ 以**内容级 md5** 代替 VCS 基线锚点 |
| 复核人 | `te-verify-t110` |
| 补录人 | 测试经理席（`tm-t110-evidence`，2026-09-21） |

> 说明：**各数字均为复核者的实测值并附时点**；`md5` / 行数一律以实时为准。

## T110.1 总结论：**通过**

一句话依据：**8 个新增调用点逐一越权实测均 `10004` / HTTP 403 且业务库零写入；分类表经双向核实**（**无**「该防没防」、**无**「误拒合法写入」）；**4/4 自设变异全红、复位全绿**。附 **3 项非阻断观察**（其中 **2 项建议另立任务**），**均非 `T-110` 引入、均非安全绕过**。

## T110.2 分类表**双向**复核（本单最关键）

**回读基线**：`REQUIREMENTS/SPOT.md` 的 `R-SPOT-2`（含注）、`CH-003`（`INDEX.md` / `SPOT.md` §5 变更记录）、`SPOT.md` §2 数据字典、`FR-SPOT-1/2/5/8/9/14` 正文、`NFR-SEC-7`。

**① 业务归属 ⇒ 强制（共 8 处）**：

| 方法 | 依据 |
|------|------|
| `createPurchaseContract` | `R-SPOT-2` / `FR-SPOT-1` 验收 2·3 |
| `createSalesContract` | `FR-SPOT-5` |
| `createInventory` | `FR-SPOT-9` |
| `createWip` | `FR-SPOT-8` |
| `computeExposure` | `FR-SPOT-14` + 验收 5 |
| `activatePurchaseContract`（按 ID） | `FR-SPOT-2` |
| `voidPurchaseContract`（按 ID） | `FR-SPOT-3` |
| `deleteDraftPurchaseContract`（按 ID） | `FR-SPOT-1/3` + `R-SPOT-9` |

> **3 处「按 ID 写同一业务单据行」经核实确实写子账户归属的业务数据**（均经 `purchaseContractDao.findById` 取回同一 `PurchaseContract` 后 `updateById` / `deleteById`）；且既有 `changeDocSubAccount` 对「按 ID 单据」断言 DB 归属属**先例**。

**② 录入归属 ⇒ 不加（共 2 处）**：`putSpotPrice`、`importSpotPrices` —— 依 `CH-003` / `R-SPOT-2` 注「**现货价…子账户标识仅为「录入归属」，不作为业务归属维度，也不参与加总**」⇒ **加了会误拒共享价正常录入**；两处 javadoc 已**显式锁定**该口径。

**③ 两个方向都查过**：**无**「该防没防」；**无**「误拒合法写入」（越权现货价录入实测**放行**）。

**④ 调用点规模**：`SpotService` 内 `requireSubAccountVisible` 由 **4 处 / 3 方法 → 12 处 / 11 方法**（`T-109` 遗留 **3 方法**〔`createProductionPlan` / `exportExposuresCsv` / `changeDocSubAccount`，后者 2 处〕+ 本单 **8 处**）。

## T110.3 安全「证伪」结果（自建 6 例探针全绿）

| 尝试 | 结果 |
|---|---|
| 传**他人** `subAccountId`（限可见 {7}、目标 {8}）调 8 方法 | 5 create + 3 by-ID **逐一 `10004`**（HTTP 403）；purchase/sales/inventory/wip/exposure/changeLog **全零写入**；草稿未被删、状态未变 |
| **不传身份**（`subAccountVisibility == null`） | `createInventory` ⇒ **`10004`**，零写入 ⇒ **fail-closed** |
| **可见集为空** | `createWip` ⇒ **`10004`** |
| 真实 `AuthService`、无身份 `uid=0` | `createPurchaseContract` ⇒ **`10004`**，零写入 |
| **哨兵 `0` / `CROSS_READONLY`** | 对任意子账户**放行写**（`attemptDao` 无痕）⇒ **符合既定口径，不报为缺陷** |
| 负值 / 哨兵 `0` 目标 | `requireSubAccount(>0)` 先抛 **`10001`** |
| 超大值 `Integer.MAX_VALUE`（限可见 {7}） | **`10004`** |
| **`Integer` 拆箱边界：5 个 create 传 `null`** | **`NullPointerException`**（非 `10004`）⇒ 见下 |

**`Integer` 改造边界的准确结论**：私有助手 `requireSubAccountVisible(long, Integer)` 的 **`null → 10004` 分支仅对「按 ID」3 方法 + `changeDocSubAccount` 可达**（实测 3/3 ⇒ `10004`、状态不变）；**5 个 create 方法该分支不可达**——它们在可见性断言**之前**先调 `requireSubAccount(int)`，对 `null` **自动拆箱 → NPE → `10022` / HTTP 500**。
**性质**：① **拒写、未放行**（**无安全绕过**）；② `requireSubAccount(int)` 系 **`T-013` 既有**（`T-110` 未改其签名 / 顺序）；③ **真实可达**（`SpotContractHandler` 仅校验 `contractNo`）；④ 与 `FR-SPOT-1` 验收 2「提示**子账户必填**」不符；⑤ 全仓**无任何测试**断言「子账户必填」⇒ **非 `T-110` 阻断项**，已另立 **`T-113`** 承接。

## T110.4 域外同类清单**复核**（逐条）

| # | 对象 | 判定 | 处置 |
|---|------|------|------|
| ① | `/product/whitelist` → `VarietyService.putWhitelist` / `removeWhitelist` | **确实未强制**且**在线**（`WhitelistHandler` 已传身份；服务层无断言；`ProductRoutes` 已注册） | **立 `T-112`** 承接 |
| ② | `/product/quota` → `putQuota` / `putInstrumentQuota` | **确实未强制**且在线 | **`T-112`** |
| ③ | `/plan/ratio` → `PlanService.calcSimple` / `calcMinVariance` / `calcCustomRule` | **确实未强制**（`PlanRatioHandler` 已传身份；服务层仅 `newCalc` / `persist`） | **`T-112`** |
| ④ | `ConfigService.disableSubAccount` / `changeCategory` | **无 web 调用点（未接线）** | 登记为 **M2 接线义务**（**不立号**） |
| ⑤ | `ParamDispatchServiceImpl.suspend` / `release` / `decreaseQuotaTwoPhase` | **无 web 调用点（未接线）** | **M2 接线义务**（**不立号**） |
| ⑥ | ~~`PlanService.decomposeTradePlans` 读路径未 scope~~ | ❌ **过报并作废**：实测**不实** —— 该处**已**调 `requireWritablePlan(planId, operatorId)`，既有量读走 `existingTradePlansScoped`（`findByHedgePlanScoped`，仅哨兵 `0` 时不过滤） | **该项作废** |
| ⑦ | SPOT 读面 `GET /spot/exposure` → `listPendingHedge`、`GET /spot/exposure/{id}` → `getExposure` | **确认未强制** | 归 **`T-107`** |
| ⑧ | `POST /spot/exposure/recalc` → `recalcForDisplay` | **确认未强制**（属**写路径**：按 `exposureId` 直读他人敞口 + 不一致时写 `t_spot_change_log` 留痕） | 复核方建议**另立 `T-114`**（⚠️ 见下） |
| ⑨ | `POST /plan` → `PlanService.createPlan`（**复核方新发现**） | 未对 `plan.subAccountId` 施加操作者可见性（现仅 `requireSubAccount(>0)` + `requireExposure` 的**同子账户一致性**）；其**读面已 scoped**，需同时掌握目标子账户**真实 `exposureId`** ⇒ **纵深防御**缺口，**非直接可利用** | 已并入 **`T-112`** |

> ⚠️ **`T-114` 登记状态（补录者实测，据实披露）**：复核方建议另立 `T-114` 承接 §4 ⑧（`POST /spot/exposure/recalc` 写路径越权面），**但补录者实测 `docs/TASKS.md` / `docs/BOARD.md` 全仓尚无 `T-114` 编号登记**（`grep -rn 'T-114' .` = **0 命中**，见 T110.9）⇒ 该编号**截至补录时点仍为「建议」、尚未立项**，**以实时为准**。

## T110.5 反退化（**自设** 4 条，未照抄产出方 8 条）+ 无回归

| 变异 | 内容 | 结果 |
|------|------|------|
| Mut-A | 注释 `createPurchaseContract` 调用 | 红 **2** |
| Mut-B | 注释 `computeExposure` 调用 | 红 **1** |
| Mut-C | 弱化 null 分支（`if (false)`） | 红 **1**（`nullSubAccountOnByIdRejected`：期望 `BizException`、实抛 NPE） |
| Mut-D | 注释 `activatePurchaseContract` 调用 | 红 **2** |

- 复位后 `SpotT110VisibilityTest` **19 / 0 / 0 / 0** 绿；`diff -rq` 副本 vs 共享 `java/src` **完全一致（零残留）**；共享整树 md5 仍 `a8988c24…`（**复核者述值**）。
- **无回归抽查（逐项完好）**：`CSV_HEADER` 三列、`importSpotPrices` 表头行判空、`T-109` 已落可见性 3+4 处、`overlapHint`、`/config/import` 取值域（`ConfigImportRangeTest` 5/0/0）、`plans=[…]` 拒因、`SpotT109FixTest` 22/0/0。

## T110.6 与产出方自述的不符之处

- **属实**：`394/0/0/0`；全部 `md5` / 行数逐一对上；8 处调用点实存；「越权 ⇒ `10004` / 403 + 零写入」**8/8 复现**；「handler 已传身份」属实（复核称 **6 个** spot handler 均已传，其措辞「4 个」**偏保守**）。
- **部分成立**：「fail-closed null」——**仅「按 ID」3 方法成立**；5 个 create 该分支**不可达（先 NPE）**，自述未覆盖。
- **未独立复现**：产出方自述「反退化 8/8 每处变红」——复核**未逐一复跑其 8 条**（**不作背书**），改以**自设 4 条**作为测试有效性证据。
- **不实 / 过报**：产出方同类扫描中「`decomposeTradePlans` 读路径未 scope」⇒ **已作废**（见 T110.4 ⑥）。

## T110.7 无法判定 / 待裁定

1. `createPlan` 可见性归属（**已裁定并入 `T-112`**）；
2. 5 个 create 的 `null` ⇒ NPE/500（**已另立 `T-113`**，归 `T-013`）；
3. **无 VCS 基线** ⇒ 「仅改 3 文件」只能以 `md5` + 测试计数**间接佐证**。

## T110.8 收口结论

- **`T-110` → `DONE`（无附条件）**。
- 由其复核衍生的新任务：**`T-112`**（写路径可见性全域收口，P0）、**`T-113`**（`FR-SPOT-1` 验收 2 错误呈现，归 `T-013`）、**`T-114`**（`POST /spot/exposure/recalc` 写路径越权面；⚠️ **截至补录时点尚未登记**，见 T110.4 注）；并登记 **M2 接线义务**（不立号）。
- **两处严重级无**：本单**无安全绕过**、**无误拒**。

## T110.9 补录者抽验（本席独立执行，**不采信转述**）

> 依本项目「转述须抽验」「以实测为准」纪律，补录人对可低成本复现的断言做了独立实测（**事后补录，非当场取证**）：

| # | 抽验项 | 命令 | 实测 | 与转述 |
|---|--------|------|------|--------|
| 1 | 改前基线 | `md5sum docs/TEST_RESULT.md` + `wc -l` | `70ddff2048d8141fd535564ef7070129` / **1822 行** | **一致** |
| 2 | `ApiRoutes.java` md5（未改） | `md5sum java/src/main/java/com/hedgeguard/web/ApiRoutes.java` | `3dec526b47b5b444d57de8898a07e030` | **一致** |
| 3 | `SpotService.java` | `md5sum` + `wc -l` | `6cd9cf5987e048fa4c3ee5b207990933` / **1606** | **一致** |
| 4 | `SpotServiceTest.java` | `md5sum` + `wc -l` | `cea066ddf43959fc6463ff420332c1c8` / **896** | **一致** |
| 5 | `SpotT110VisibilityTest.java` | `md5sum` + `wc -l` | `297d09569637474fe07fe548c05d6376` / **815** | **一致** |
| 6 | 三类测试方法数 | `grep -c '@Test'` | `SpotT110VisibilityTest=19` / `SpotServiceTest=15` / `SpotT109FixTest=22` | **一致** |
| 7 | `SpotService` 调用点规模 | `grep -n 'requireSubAccountVisible' java/src/main/.../SpotService.java` + 逐行定位所属方法 | **12 调用点 / 11 方法**（T-110 新增 = L263/297/340/381/425/448/491/671；T-109 遗留 = `createProductionPlan`/`exportExposuresCsv`/`changeDocSubAccount`〔2 处〕） | **一致** |
| 8 | `T-110` 状态 | `grep -n 'T-110' docs/TASKS.md` | `docs/TASKS.md:155` = **`DONE`**（判据「授权 + 独立验证」） | **一致** |
| 9 | `T-112` / `T-113` 登记 | `grep -n 'T-112\|T-113' docs/TASKS.md` | `T-112`（P0，依赖 `T-110`）、`T-113`（P1，归 `T-013`）**均 `TODO`** | **一致** |
| 10 | 分类表需求锚点 | `grep -n` 于 `REQUIREMENTS/` | `R-SPOT-2`（`SPOT.md:379` 含「现货价仅为录入归属」注）、`CH-003`（`INDEX.md:659`）、`FR-SPOT-1` 验收 2「子账户必填」（`SPOT.md:112`）、`FR-SPOT-14`、`NFR-SEC-7` **均实存** | **一致** |
| 11 | `docs/TEST_RESULT.md` 末尾节 | `grep -n '^# \|^## '` | 末节 = `## T109.6`（**无 `T-110` 节**） | **一致** |

**⚠️ 三处与转述不符 / 需澄清（据实披露）**：

1. **`grep -rln 'te-verify-t110'` ≠ 0**：补录者实测 **2 命中**（`docs/TASKS.md`、`docs/BOARD.md`）——系项目经理在**第 22 轮**将复核**结论摘要**回写入状态栏（「独立复核 `te-verify-t110` 判定**通过**」）所致，**属状态注记、非证据章**；项目经理所述「0 命中」为其**更早时点值**。本节所补的**证据本体**此前确未落入任何制品。
2. **`T-114` 全仓 0 登记**：转述称「另立 `T-114`」，但 `grep -rn 'T-114' .` = **0 命中**（`TASKS.md` / `BOARD.md` 均无）⇒ 该编号**尚未立项**，据实按「建议 / 待登记」记录（见 T110.4 注）。
3. **整树内容级 md5 `a8988c24…` 不可复现**：补录者以 **8 种口径**实测（共享树 `find java -type f`〔含 / 不含 `target/`〕、`cd java && find . -type f`、`find java/src`、`sort` 前置 / 后置、隔离副本 `/tmp/te-t110` 及 `/tmp/hg-t110`）均**无一致值**（共享树 `java/` 实测 = `5f9111d88c0105b6be4544927c8998cd`）。该值**依赖 `target/` 与路径前缀**，应为**隔离副本冻结点时点值**（`target/` 内容随构建变动）⇒ **转述值仅作复核者自述留存，不作独立背书**；如需可复现锚点，宜改用 **`java/src` 内容级 md5**（补录者实测 = `8cc66a89a9d4e24c2135de860a5e14ef`，命令：`find java/src -type f | sort | xargs md5sum | md5sum`）。

## T110.10 度量

| 指标 | 数值 |
|------|------|
| 结论 | **通过** |
| 冻结点（隔离副本） | **`394 / 0 / 0 / 0`**（`2026-09-21T13:00:29+08:00`，`clean`、无排除；基线 375 ⇒ **+19**） |
| 新增强制调用点 | **8**（5 create + 3 by-ID；`SpotService` 内合计 **12 处 / 11 方法**） |
| 分类表方向 | **双向已查**：无「该防没防」、无「误拒合法写入」 |
| 安全证伪探针 | **6 例**（全绿）；「越权 ⇒ `10004` / 403 + 零写入」**8/8**；fail-closed（不传身份 / 空可见集）**成立** |
| 反退化变异（复核**自设**） | **4 条**（全红；复位全绿）；**未背书**产出方自述 8 条 |
| 严重级缺陷 | **0**（**无安全绕过、无误拒**） |
| 非阻断观察 | **3**；衍生新任务 **`T-112`**（P0）/ **`T-113`**（P1，归 `T-013`）/ `T-114`（**建议、未登记**） |
| `ApiRoutes.java` | 全程未改 `3dec526b…` |

> **本节由测试经理席（`tm-t110-evidence`）补录**，仅新增本节；`T-110` 复核结论先经消息往来确认、其后补录于此（**事后补录，非当场记录**）。本节各数字为复核者的实测值并附时点，`md5` / 行数**以实时为准**；T110.9 三项不符已据实披露。

---

# `T-098` / `T-112` / `T-115` 独立复核证据（**事后补录**，2026-09-21）

> **补录声明**：本节为 **`T-098`**（写面拒绝 `CROSS_READONLY` 跨账户只读的**真绕过**修正）/ **`T-112`**（写路径子账户可见性**全域收口**：PRODUCT 写面 4 + PLAN 写面 4）/ **`T-115`**（**`REJECTED`**：重复立项）三项**独立复核**结论的**事后补录**。
> 三项的复核证据此前**只存在于编排者与复核者的消息往来、从未落入制品**；补录前 `docs/TEST_RESULT.md` **全篇无 `T-098` / `T-112` / `T-115` 节**（末节为 `## T110.9`）⇒ 本节**如实声明为「事后补录」（非当场记录）**：结论**先经消息往来确认、其后补录于此**，**不得伪装为当场记录**。
> **两类分列（不得混写）**：§`T098-115.1`~`.3` 的结论与数字**均为复核者（`te-verify-t115` / `te-verify-t112`）的实测值、由编排者转述**，正文逐处标「**（复核者实测，引述）**」；§`T098-115.4` 系**补录者自测**，标「**（补录者自测）**」。**两类不得混写。**
> **`md5` / 行数一律以实时 `md5sum` 为准**（仓库**无 git** ⇒ 以**内容级 md5**〔`find java/src -type f | sort | xargs md5sum | md5sum`，cwd=仓库根〕代替 VCS 基线锚点）。
> **本节为 `docs/TEST_RESULT.md` 的唯一改动**，其余章节一字未动（**纯追加**，证明见 `T098-115.5`）。

## T098-115.0 冻结信息与基线

| 项 | 内容 |
|----|------|
| 复核对象 | `java/**`（`T-098` 写面断言；`T-112` 写路径可见性 8 点；`T-115` = 与 `T-098` 重复） |
| `T-112` 复核锚 | `7e316b27be4644378c0525a1f4417063`（**314 文件**），`te-verify-t112` 独立构建 **`420 / 0 / 0 / 0`**（**引述；不可重跑**，见 `.4` 未做项①） |
| `T-115` delta 复验锚（现行） | `1cfc6a3af985f006eb97fc4803875539`（**314 文件**），`te-verify-t115` 构建 **`421 / 0 / 0 / 0`**（引述） |
| 补录者实测锚（现行，**自测**） | `1cfc6a3a…`（**314 文件**，**与 `T-115` delta 锚同值**）；`mvn -B -o clean test` = **`421 / 0 / 0 / 0`**（隔离副本，见 `.4`） |
| `ApiRoutes.java` | **全程未改** = `3dec526b47b5b444d57de8898a07e030` |
| 环境 | 无 DM8 实例、无 DM 驱动（离线 `-o`）；**无 VCS 基线**（整仓无 git）⇒ 以**内容级 md5** 代替 VCS 锚点 |
| 复核人 | `te-verify-t115`（承担 `T-098` 与 `T-115` delta 复验）、`te-verify-t112`（承担 `T-112`） |
| 补录人 | 测试经理席（`tm-t098-evidence`，2026-09-21） |

> 说明：`T-112` / `T-115` 锚点行均为**复核者实测值（引述）**；补录者实测锚见 `.4`，`md5` / 行数**以实时为准**。

## T098-115.1 `T-098` —— 写面拒绝 `CROSS_READONLY` 真绕过的修正

> **来源：复核者 `te-verify-t115` 实测，引述**（补录者未逐条复现）。

**裁决**：`T-098` 系**既有任务**（`docs/TASKS.md` §1 任务表：`T-098`「实现 `CROSS_READONLY` 跨账户只读强制点（写操作拒绝 + 留痕）」，`#java`，P0，3 人时）；其范围**与编排者另立的 `T-115` 逐条重合** ⇒ **`T-115` 属重复立项、已 `REJECTED`**（留编号、不计人时）；**成果归 `T-098`**。

**实现（复核者实测，引述）**：
- `SubAccountVisibility` **新增 `default assertSubAccountWritable(long,int)`**：**仅含真实目标子账户**（`id != ALL_ACCOUNT_SENTINEL && id == subAccountId`）才放行；仅哨兵 `0`〔`CROSS_READONLY`〕/ 空集 / 无授权 ⇒ **拒 `10004`**（**fail-closed**）。
- `AuthService` **新增同名覆盖**：逐行查 `t_user_sub_account_perm`；**生效**（`inEffect`）且 `permType != CROSS_READONLY` 且 `subAccountId == 目标` ⇒ 放行；否则 `recordAttempt(SUB_ACCOUNT_DENIED)` + **`10004`**。
- `PlanService` / `SpotService` / `VarietyService` **写面调用点改向** `assertSubAccountWritable`。
- **`assertSubAccountVisible`（读面，含哨兵即放行）语义逐字节未变**。

**`te-verify-t115` 结论 = 通过（无条件）**（原判「有条件通过」，其**唯一条件**为某测试文件去向不明；**该条件已消解**——该文件系另一实例 `14:00` 前后加入、`14:10` 前自行撤回，树**回到与增前逐字节相同**的 `7e316b27be4644378c0525a1f4417063`（**314 文件**），属**已登记的树演进 / 瞬态**）。

**安全证伪（引述）**：真实 `AuthService` + 假 DAO，共 **11 例探针**——
- `createPlan` / `createPurchaseContract` / `putWhitelist` × `CROSS_READONLY` ⇒ **均 `10004`（先于任何 DAO）**；
- 目标取 `{真实他人, 0, -1, MAX, 1e6}` ⇒ **6/6 `10004` + 6 条留痕**；
- `LIMITED` 写自己放行、写他人 `10004`；无授权 / 过期 ⇒ `10004`（**fail-closed**）；
- **读面反向**：`assertSubAccountVisible` 与 `exportExposuresCsv` × `CROSS_READONLY` ⇒ **均放行**（**无误拒**）；
- `default` 的 lambda 替身写面亦 `10004`（**替身不假绿**）。

**被改写用例 4 条（引述）**：旧断言编码的是**错误口径**——`PlanT112VisibilityTest` / `VarietyT112VisibilityTest` / `SpotT110VisibilityTest` 的 `crossReadonlySentinelAllows` → `…Rejected`；`PlanWritePathPermissionTest.decomposeCrossReadonlyCountsAllExisting` → `decomposeCrossReadonlyRejectedAtEntry`。
**反向证据**：4 个**读面**用例（`crossReadonlySeesAllWithoutScope`、`detailAllowedForCrossReadonly`、`DmTradePlanDaoScopedSqlTest.crossReadonlySkipsScopeInSql…` 等）**未被改写、仍在绿内** ⇒ 证明**读面放行未被一并砍掉**。

**替身改动 4 处（引述）**：`PlanServiceTest` / `PlanCompliancePrecheckTest` / `VarietyServiceTest` / `SpotServiceTest` 的注入值由 `[SENTINEL]` 改为**具体子账户**；判定 = **纯语义订正、非掩盖误拒**（它们是业务规则测试，旧用「哨兵 `0` = 万能放行」作脚手架）。

**反退化（复核者自设 4 组；引述）**：M1 放宽接口 `default` ⇒ 替身路径 3+1+1 红而 `AuthServiceTest` 绿；M2 放宽 `AuthService` 覆盖 ⇒ 反之 ⇒ **两条路径各自承重**；M3 删 `createPlan` 守卫 ⇒ 红；M4 读面反向误拒 ⇒ 读面用例红。

**接口影响面（引述）**：`implements SubAccountVisibility` ⇒ **main 仅 `AuthService`**、**test 0 个类**；替身**全为 lambda**（只实现 `visibleSubAccountIds`），**无一覆盖** `assertSubAccountWritable` ⇒ **无「一律放行」替身**。新增的是 **`default` 方法** ⇒ 不打断实现者 / 替身。

## T098-115.2 `T-112` —— 写路径可见性全域收口（PRODUCT 4 + PLAN 4）

> **来源：复核者 `te-verify-t112` 实测，引述**（补录者未逐条复现）。

**`te-verify-t112` 结论 = 有条件通过**（锚 `7e316b27…`）：**分类表 8 行全对、双向无缺陷**（既无**漏防**、也无**误拒**；`putQuota` 层③ `ACCOUNT_PRODUCT`〔目标为**账户哨兵 `0`**〕**正确地不加**——以**反向变异**锁定）；独立构建 **`420/0/0/0`**；`394` 基线独立证实；`grep -c @Test` == surefire（**13/13 两卷**）。

**两条件**：
1. 【高 · 真绕过】`CROSS_READONLY` 写面放行 ⇒ **已由 `T-098` 的实现关闭**；
2. 【低 · 建议级】`VarietyService.removeWhitelist` 未调 `requireSubAccount`（缺省 `0` 报 `10004` 而非 `10001`）⇒ **已落**；复核者独立判定其**无越权**（`0` 非合法子账户、即便哨兵放行 `findWhitelist(0,pid)` 必空 ⇒ `10001`、**零写入**）、**无误拒**、**无 500/NPE**、**不影响任何 FR 验收** ⇒ **建议级**。

**delta 复验（`te-verify-t115` 在现行锚 `1cfc6a3a…`；引述）**：`T-112` 的 **8 个写面点全部改走 `assertSubAccountWritable` 且仍拒 `CROSS_READONLY`**；`removeWhitelist` 已含 `requireSubAccount` + `requireSubAccountWritable`；**fail-closed 保持**。

**审计诚实项**：旧锚 `7e316b27…` **不可重跑**（仓库无 VCS）⇒ 其 `420/0/0/0` 系**引述**，非补录者复算。

## T098-115.3 `T-115` —— 重复立项（`REJECTED`）

> **来源：`TASKS.md` §1 任务表裁定（引述）+ 补录者独立核对（自测）。**

**记明**：`T-115`「修正 `CROSS_READONLY` 写面放行（写路径拒绝跨账户只读身份；读面语义不变）」**与既有 `T-098` 范围逐条重合**（连错误码 `10004`、留痕表 `t_unauthorized_attempt` / `R-AUTH-8`、正/反双向测试要求均一致）⇒ **重复立项**；**编号留痕、不计人时**（依 `T-029` 先例）；其实施成果由 **`T-098` 收受**。**动因**：编排者立号时**未扫整张既有任务表**（只查了 `T-102` / `T-110` / `T-112`）。

**登记现状（补录者自测，引述对照）**：`docs/TASKS.md:160` 载 `T-115` = **`REJECTED`**（「**重复立项**：与既有 `T-098` 范围逐条重合；成果归 `T-098`；编号留痕、不计人时；2026-09-21」），人时列 = `0 人时（原 6，已扣除）`。

## T098-115.4 补录者独立抽验（本席自测，**不采信转述**）

> 依本项目「转述须抽验」「以实测为准」纪律，补录人对可低成本复现的断言做独立实测（**事后补录，非当场取证**）。**本节全部为「补录者自测」**，与 §`T098-115.1`~`.3` 的「引述」严格分列。

| # | 抽验项 | 命令 | 实测 | 与转述 |
|---|--------|------|------|--------|
| 1 | `ApiRoutes.java` md5（未改） | `md5sum java/src/main/java/com/hedgeguard/web/ApiRoutes.java` | `3dec526b47b5b444d57de8898a07e030` | **一致** |
| 2 | 现行整树内容级 md5 + 文件数 | `find java/src -type f \| sort \| xargs md5sum \| md5sum`（cwd=仓库根） | **`1cfc6a3af985f006eb97fc4803875539` / 314 文件**（`2026-09-21T14:59:42+08:00`） | **一致**（＝`T-115` delta 锚） |
| 3 | 隔离副本 `mvn -B -o clean test` | `/tmp/tm-t098-iso`（布局 `<root>/{docs,java}`；其 `java/src` 内容级 md5 与共享树**同值**） | **`Tests run: 421, Failures: 0, Errors: 0, Skipped: 0`**；**BUILD SUCCESS**；`15:00:04 → 15:01:36+08:00` | **一致** |
| 4 | `@Test` 计数 == surefire | `grep -rho '@Test' java/src/test \| wc -l` | **421** == surefire `tests=421` | **一致** |
| 5 | `implements SubAccountVisibility` | `grep -rn "implements SubAccountVisibility" java/src` | 代码层 **main 1**（`AuthService.java:61`）+ **test 0**（另一命中 `SubAccountVisibility.java:40` 系 javadoc 转述，非 `implements`） | **一致** |
| 6 | `assertSubAccountWritable` 调用点（main） | `grep -rn "\.assertSubAccountWritable(" java/src/main` | **4 处**：`PlanService`×2（`:1751` / `:1783`）、`VarietyService:994`、`SpotService:1574`；另 `AuthService:543`（override 声明）+ `SubAccountVisibility:100`（default 声明） | **一致** |
| 7 | 替身形态 | `grep -rn "new SubAccountVisibility" java/src/test` | **0 命中**（注入**全为 lambda** `uid -> …`） | **一致**（无「一律放行」替身） |
| 8 | 读面反向用例在全绿内 | 从 `mvn` 日志取各类 `Tests run` | `SpotT109FixTest` **22/0/0/0**（含 `exposureExportAllowsVisibleSubAccount`）、`DmTradePlanDaoScopedSqlTest` **4/0/0/0**（含 `crossReadonlySkipsScopeInSqlForPagination`）、`PlanSubAccountIsolationTest` **8/0/0/0**、`TradePlanSubAccountIsolationTest` **7/0/0/0** | **一致** |
| 9 | `T-098` / `T-112` / `T-115` 登记 | `grep -n 'T-098\|T-112\|T-115' docs/TASKS.md` | `T-098` = **`DONE`**（复核通过）；`T-112` = **`DONE`**（复核通过 + 两条件闭环 + delta 复验）；`T-115` = **`REJECTED`**（重复立项）；`T-114` = `TODO` | **一致** |
| 10 | 被改写用例名实存 | `grep -rn "crossReadonlySentinelRejected\|decomposeCrossReadonlyRejectedAtEntry" java/src/test` | **4 条实存**（`PlanT112VisibilityTest:200` / `VarietyT112VisibilityTest:154` / `SpotT110VisibilityTest:250` / `PlanWritePathPermissionTest:146`） | **一致** |
| 11 | 关键测试类通过数（全绿内） | 从 `mvn` 日志 | `AuthServiceTest` **14/0**、`PlanT112VisibilityTest` **13/0**、`VarietyT112VisibilityTest` **13/0**、`SpotT110VisibilityTest` **19/0**、`PlanWritePathPermissionTest` **7/0**；`PlanServiceTest` 11、`VarietyServiceTest` 13、`SpotServiceTest` 15、`PlanCompliancePrecheckTest` 19（**均 0 失败**） | **一致** |

**⚠️ 与转述的差异 / 需澄清（据实披露）**：

1. **读面反向用例的`确切`方法名**：转述称 `DmTradePlanDaoScopedSqlTest.crossReadonlySkipsScopeInSql`；**实测全名为 `crossReadonlySkipsScopeInSqlForPagination`**（`:133`）⇒ 结论不变（读面反向用例实存且全绿）。
2. **`T-114` 已登记**（与**上一补录章** `T110.9` 结论相反）：`T110.9` 曾记「`T-114` 全仓 0 登记（**截至其补录时点**）」；补录者本次实测 `docs/TASKS.md:159` **已载 `T-114` = `TODO`**（`POST /spot/exposure/recalc` 可见性强制的补齐，P1，2 人时，依赖 `T-110`）⇒ 该编号**已立项**；`T110.9` 的「0 命中」系其**更早时点值**（**以实时为准**）。
3. **`T-098` 的复核执行人署名**：`docs/TASKS.md:143` 记 `T-098` 复核结论为「**通过（无条件）**〔`te-verify-t115`，2026-09-21〕」——即**署名 `te-verify-t115`**（因 `T-115` 成果并入 `T-098`），与转述一致；**非独立第三人复核**。

**未做 / 不能做（不沉默略过）**：
1. **未重跑 `7e316b27…` 锚**（**仓库无 VCS**，该树态已不可得）⇒ `T-112` 的 `420/0/0/0` **仅作引述、不作独立背书**；
2. **未复跑**复核者自设的反退化变异（M1–M4 等）⇒ 其「红/绿」结果**仅作引述**；
3. **未自建 11 例 `AuthService` 探针**（补录者以**全绿 `421/0/0/0`** + 源码级断言核验代替）。

## T098-115.5 度量与「纯追加」证明

| 指标 | 数值（**引述** / **自测** 分列） |
|------|------|
| `T-098` 复核结论（**引述**） | **通过（无条件）**（`te-verify-t115`） |
| `T-112` 复核结论（**引述**） | **有条件通过**（`te-verify-t112`，锚 `7e316b27…`）；两条件均闭环 |
| `T-115` 处置（引述 + **自测核**） | **`REJECTED`**（重复立项，成果归 `T-098`，编号留痕、不计人时） |
| 冻结点构建（**引述**） | `T-112` 锚 `7e316b27…` = **`420/0/0/0`**；`T-115` delta 锚 `1cfc6a3a…` = **`421/0/0/0`** |
| 现行构建（**补录者自测**） | **`421/0/0/0`**（`2026-09-21T15:01:36+08:00`，`clean`、无排除；`@Test` 421 == surefire） |
| 现行整树内容级 md5（**补录者自测**） | **`1cfc6a3af985f006eb97fc4803875539` / 314 文件** |
| `ApiRoutes.java` | 全程未改 `3dec526b47b5b444d57de8898a07e030` |
| 严重级缺陷 | `T-098` / `T-115`：**0**（真绕过已闭）；`T-112`：1 高（**已由 `T-098` 关闭**）+ 1 建议级（**已落**） |

**「纯追加」证明（补录者自测）**：
- **改前基线**：`docs/TEST_RESULT.md` = **`e482424db8a87d12c2caa4afbd98925d` / `1983` 行**（**3×10s 静默取样稳定**：`14:59:16` / `14:59:26` / `14:59:36` 三次同值）；
- **纯追加校验**：`head -1983 docs/TEST_RESULT.md | md5sum` = **`e482424db8a87d12c2caa4afbd98925d`**（**等于改前基线** ⇒ 既有 `1983` 行**逐字节未改**）；
- **改后** `md5` / 行数**以实时为准**（**本文件不在正文内嵌自身落笔后 md5**，以免自引用失效；落笔时点值见补录回报）。

**⚠️ 落笔窗口内的树演进（据实披露）**：补录者取样锚 `1cfc6a3a…`（`14:59:42`）之后，共享 `java/src` 于本节落笔期间**再变**（补录者实测 `2cf7f368a9da80bd93452fc2f551a1fa` / **314 文件**；`diff -rq` 显示与取样锚仅 **3 个文件**差异：`dao/impl/DmProductDao.java`、`dao/ProductDao.java`、`service/VarietyService.java`）⇒ 系**并行任务 `T-105`（`be-t105`）正在共享树实施**。**本节之构建（`421/0/0/0`）锚定的是冻结副本 `1cfc6a3a…`**（其 `java/src` 内容级 md5 已逐字节校验与取样锚**同值**），**非**变动后的实时树；`md5` **以实时为准**。

> **本节由测试经理席（`tm-t098-evidence`）补录**，**仅新增本节**；`T-098` / `T-112` / `T-115` 复核结论先经消息往来确认、其后补录于此（**事后补录，非当场记录**）。§`T098-115.1`~`.3` 数字为**复核者实测值并附时点（引述）**，§`T098-115.4` / `.5` 为**补录者自测**，**两类分列不混写**；`md5` / 行数**以实时为准**；`.4` 三项差异已据实披露。

---

# `T-031` / `T-120` 独立复核证据（事后补录，2026-09-21）

> 本章由测试工程师席（`te-verify-t120`）补录。**对象** = `T-120`（`E4` 判据接线落地）与 `T-031` 交付的 `tools/arch-check/README.md` / `arch-check.sh` 的**独立 delta 复核**。章内严格分列「**复核者/补录者实测**（独立重跑，附时点）」与「**引述**（他人自述 / 派单转述，未经我重跑者）」；`md5` / 行数**以实时为准**。**本单写权仅本文件一个**，其余制品全程只读；临时探针建于 `/root/` 下、结束已删净。

## T031-120.0 冻结信息与基线

**两轮复核的制品锚点**（`cwd` = 仓库根；内容级锚统一用 `LC_ALL=C sort`）：

| 制品 | 首轮（`2026-09-21 18:25–18:27`） | delta（`2026-09-21 18:34:26`；3 取样 `18:34:26/36/46` 不变） |
|---|---|---|
| `tools/arch-check/scan_lock_free.sh` | `277` 行 / `64fb6c1a99583ce73c0e4a1c54758a69` | **同**（0 行改动） |
| `tools/arch-check/arch-check.sh` | `126` 行 / `306daf0aca2e625243ad16e562a1641a` | **同**（0 行改动） |
| `tools/arch-check/README.md` | `211` 行 / `8a68c9ab4b40198db8c39f06b7e7646c` | **`220` 行 / `04523ee239b22da61a49c5241762fc01`** |
| `docs/ARCHITECTURE.md`（`v1.5.13`） | `2774` 行 / `4b7d5602f6be68f7243c450a5e2d9158` | **`2780` 行 / `3c7e2e1ac202698cb453bbf2e1b302e5`** |

- **VCS 状态**：`README.md` / `arch-check.sh` 在首提交 `59b6f70` 中**未跟踪**（`git status` 显 `??`）⇒ 无 git 基线，只能对 **T-031 期第三方快照** `/root/te-t031/repo/tools/arch-check/{README.md(192 行 / 306aa1507bd8df3b9b9ffe2eba9c7e69), arch-check.sh(122 行 / 81ee4c56bcbc4926412fcaec389c21c7)}` 做 diff；`scan_lock_free.sh`（旧 `42c76604936cb9f69d621e1c1135961a` / 243 行）与 `ARCHITECTURE.md` 已跟踪（`git show 59b6f70:…` / `git diff` 可用）。
- **同版本时效**：`docs/ARCHITECTURE.md` 两轮同为 `v1.5.13`（**同轮追加、未升 `v1.5.14`**）⇒ **`v1.5.13` 现行 md5 = `3c7e2e1a…`；首轮 `4b7d5602…` / 2774 已作废、勿再引用**。

## T031-120.1 `T-120` 首轮复核 = **有条件通过**（＝依本项目惯例**退回**）

**逐项证实（复核者实测；均自建证据、不采信产出方自报表）**：

1. **锚点**：四文件 md5 + 行数逐格与自报一致（见 §0）。
2. **`E4` 实扫 vs `SKIP`**：`bash tools/arch-check/arch-check.sh` ⇒ 聚合退 `0`、`E1..E6 = 0/0/0/0/0/0`、**`E4` `待扫描文件: 6 个`**（非 `SKIP`）。
3. **`N=6` 自洽**：`find cpp/src/{order_engine,risk_engine,risk_state} -type f \( -name '*.cpp' -o -name '*.cxx' -o -name '*.cc' -o -name '*.c' -o -name '*.hpp' -o -name '*.h' -o -name '*.hh' \) ! -path '*/build*' ! -path '*/_deps/*' ! -path '*/third_party/*' | LC_ALL=C sort -u` 列出 **6** 个 = 每模块 `{include/hg/<m>/module.h, src/module.cc}`；`CMakeLists.txt` 计 **0**（不在 `-name` 白名单）。
4. **判定段逐字节未变**：见 §4(a) 原始 `cmp` 输出。
5. **`SKIP` / `--root` 四态（隔离副本 `<root>/cpp/src/...` 构造）**：(a) 三目录全存在⇒实扫退 `0`；(b) **仅 `order_engine` 存在⇒实扫该一个、退 `0`**（缺席目录向 stderr 打印「注意：默认扫描根子目录不存在，跳过：…」）；(c) 三目录全不存在⇒`SKIP` 退 `0`；(d) `--root <存在单目录>`⇒退 `0`；`--root /no/such`⇒**`2`**；`--root=/no/such`⇒**`2`**；未知参数⇒**`2`**。
6. **漏防方向 30/30**：6 文件（三模块各 `include/.../module.h` + `src/module.cc`）× 5 注入（`std::mutex m;` / `std::lock_guard<std::mutex> g(m);` / `std::unique_lock<std::mutex> u(m);` / `std::condition_variable cv;` / `::fsync(fd);`）**全部退 `1`**。
7. **误拒方向**：三模块 6 源文件仅 `module_name()` 声明 / namespace / include guard，**无 `read`/`write`/`send` 任何形态**；另 12 例最小边界探针与脚本头未覆盖段**逐条一致**（`R3-1` 跨行返回类型定义⇒误报 `1`；`R3-2` `expr * name(`⇒漏判 `0`；`R3-3` 限定名定义⇒误报 `1`；字面量 / 注释 / 通用名成员调用⇒`0`）。
8. **排除面非空转**：`flow_store`/`hgcp`/`ctp_adapter` 注入合法 `::fsync`/`::send`/`::recv` ⇒ 默认根退 **`0`**（不进扫描面、`待扫描文件: 6 个` 不变）；同批探针 `--root cpp/src` ⇒ 命中 **3** 处、退 **`1`**。
9. **`ARCHITECTURE.md` 三处回写**：§3.0.2 `E4` 表行（`:321`）/ 脚注 ④（`:377`）/ §13 `v1.5.13`（`:2722`〔首轮时点〕）均表述「已接线 / 已生效」，与实测一致。
10. **`FR=184`**：五处落点一致（抬头上游锚 / §1.1 / §10 覆盖行 / §10.1 标题 / 覆盖统计）。
11. **升版非过度**：与先例 `v1.5.9`（`E1`/`E3`/`E4`/`E6` 四脚本落地即升版）同构。

**缺陷 `TE-T120-001`（一般）**：`tools/arch-check/README.md`（首轮 `:197`）表述「`FetchContent` 取回 CTP ⇒ `rsk02` 同样注册并通过」**与事实不符**，且把**条件性** `6/6` 写成确定性 ⇒ **过度断言**。
- **复现（复核者实测）**：`cpp/ctp_smoke/CMakeLists.txt:129`〔**注：实测为 `:128`，见 §3〕= `if(CTP_FOUND)`；`CTP_FOUND=ON` 仅当 `:87` `if(CTP_SDK_ROOT AND EXISTS "${CTP_SDK_ROOT}/${HGCP_CTP_TRADER_HEADER}")` 成立。三轨 `CMakeCache.txt`：`build-ctp`=`/home/ctpmini/ctpmini_admin/ctp_sdk`、`build-stub`=**空**、`build-fetch`=**同 ctp**；`ls cpp/build-fetch/_deps/` ⇒ **仅 protobuf**（`protobuf-build` / `protobuf-src` / `protobuf-subbuild`）、**无 CTP**；两轨 `hgcp_ctp_probe` 的 `ldd` 均链 `/home/ctpmini/.../libthost{user,md}userapi.so`。
- **影响**：文档事实错误（已致下游沿用错误理由）；**不影响门禁行为**。

## T031-120.2 两处矛盾点裁定

### （甲）`build-fetch` `5/5` vs `6/6` —— **配置差异；双方数字均非测错、双方「解释」均错**
- 复核者实测三轨 `ctest`：`build-ctp` **`6/6`**、`build-stub` **`5/5`**、`build-fetch` **`6/6`**（注册清单 = `arch_check` / `rsk01`×3 / **`rsk02.ctp_header_cxx11_compat`** / `hg.skeleton_modules`；`build-stub/ctp_smoke/CTestTestfile.cmake` **空**）。
- **真实自变量 = `CTP_SDK_ROOT`（→`CTP_FOUND`）**，与 `CTP_USE_FETCH_DEPS`（只供 protobuf）无关。
- 产出方 `tl-t120` 原「`FetchContent` 取回 CTP」与 `te-verify-t031` 记忆里的「`fetch` `5/5`」**两处解释均误**；`te-verify-t031` 的 `5/5` 系**当时空 root 态**，其快照未留 `CMakeCache.txt` ⇒ **不可逐字节复原**（判定为「配置差异」，但时点 cache 不可复现）。

### （乙）「部分缺席静默只扫现存者」= **假通过面**
- 规则「任一存在即实扫」⇒ 三中只剩一时**只扫现存者**（实测：隔离副本仅留 `order_engine` ⇒ `N=2`、退 `0`；仅留单文件 ⇒ `N=1`、退 `0`；stderr 有「跳过」提示但**退出码 `0`**）⇒ 未来模块改名 / 迁移可致**少扫而不红**。
- **编排者裁定**：**不改**脚本行为 / 退出码，改采「**显式披露 + 登记为已接受风险**（`ARCHITECTURE.md` §3.0.2 `E4` 脚注 ⑥）**+ 验收面兜底**」。

## T031-120.3 delta 复检（补修后）= **通过（0 缺陷）**

- **`TE-T120-001` 已订正**：`README.md` 删去错误理由、替换为「**真实自变量 = `CTP_SDK_ROOT`（不是 `FetchContent`）**」（`:194–199` 注册条件块），三轨表**增 `CTP_SDK_ROOT` 列**并标明 `6/6` 为**条件性**（`:203` / `:206–207`）。全仓 grep：`FetchContent` 在 README 仅 **`:194` / `:203` 两处、均否定式**，无残留错误理由。
- **`:128` 笔误订正（我方）**：复核者首轮写「注册守卫 `:129`」**有误**；产出方 / 编排者实测 **`cpp/ctp_smoke/CMakeLists.txt:128` = `if(CTP_FOUND)`**（`:87` 判定、`:88` `set(CTP_FOUND ON)`、`:171` `add_test(rsk02…)`）⇒ **我复核确认 `:128` 正确、认领该笔误**（属复核精度问题，非产出方缺陷）。
- **脚注 ⑥ 兜底判定复核成立**（读码实证见 §4(d)）：「**部分兜底；对『已同步的完整迁移』无自动化兜底**」。
- **「无 CTP SDK ⇒ 三轨 `5/5`」= 已由复核者补硬证据闭环**（见 §4(c)）——该句原系产出方**推断**，现升为**实测成立**。
- **同轮追加 `v1.5.13`（未升 `v1.5.14`）可接受**：与文档既有「同一修订轮不另升版」先例（`v1.5.7` 追加四项 / `v1.5.11` 同轮追加两笔）同构；改动属**同一交付循环内的复核订正**，升版会即时作废 PM 刚登记的锚点 ⇒ 保持 `v1.5.13`。
- 其余：`FR` 仍 `184`；聚合退 `0`、`E4` `N=6`；`cpp/src` / `java/src` / `java/pom.xml` / `cpp/CMakeLists.txt` 零改动。

## T031-120.4 补录者抽验（本席独立执行，**不采信转述**）

> **全部为「补录者自测」**，与 §1 的「引述（他人自述）」严格分列。

**(a) 判定段四段 `cmp`（原始输出）**：
```
$ git show 59b6f70:tools/arch-check/scan_lock_free.sh > old.sh ; cp tools/arch-check/scan_lock_free.sh new.sh
# 各按 <<'AWKEOF' … AWKEOF 定界抽段
$ cmp old_s1.txt new_s1.txt      # STRIP_AWK  → 无输出（逐字节相同）
$ md5sum old_s1.txt new_s1.txt
2d45b42d1d84302a4f2f2c83e726a9d5  old_s1.txt
2d45b42d1d84302a4f2f2c83e726a9d5  new_s1.txt
$ cmp old_s2.txt new_s2.txt      # DETECT_AWK → 无输出（逐字节相同）
$ md5sum old_s2.txt new_s2.txt
932573376c20cc05ef1fdf80eb8f6f27  old_s2.txt
932573376c20cc05ef1fdf80eb8f6f27  new_s2.txt
# WARN_RE 整行相同；printf '%s\n' "$WARN_RE行" | md5sum → a676da63c8f4f3f784c760f818f76a3a
# 末行 { if (lock_hit($0) || sync_hit($0)) print FNR }（含尾换行）→ 52c0dd25d51475380d7c622f4c67d477
```
⇒ **产出方自报的末行 `md5 52c0dd25…` 经复核者独立复算、证实**。另：`scan_lock_free.sh` **整文件 `md5 64fb6c1a…` 与首轮逐字节相同** ⇒ 判定段未变（此证明**强于**抽段比对）。

**(b) 改后聚合（原始输出节选）**：
```
E1 通过：C++ 产物未链接 DB 客户端库（退出码 0）
待扫描文件: 15 个
E2 通过：C++ 侧未发现 SQL / DB 驱动痕迹（退出码 0）
E3 通过：编译参数与源码均符合 C++11（退出码 0）
待扫描文件: 6 个
E4 通过：交易路径未发现锁 / 同步 I/O（覆盖 A+B；WARN 0 处…）（退出码 0）
待扫描文件: 142 个
E5 通过：dao 之外未发现 SQL（退出码 0）
E6 通过：前端零构建链、无直连字样（退出码 0）
#   通过(0)=6  命中(1)=0  用法/前置错误(2)=0
#   E1 退出码 0 / E2 退出码 0 / E3 退出码 0 / E4 退出码 0 / E5 退出码 0 / E6 退出码 0
arch-check 通过：六个判据脚本全部通过（退出码 0）
$ echo $?  →  0
```
`E4` 的 `待扫描文件: N 个` = **`6`**。

**(c) 「无 CTP SDK ⇒ 三轨 `5/5`」硬证据（复核者自建，全新 out-of-source，不进仓库）**：
```
$ cmake -S cpp -B /root/… -DCTP_SDK_ROOT= -DCTP_USE_FETCH_DEPS=OFF   # 退 0
$ cmake -S cpp -B /root/… -DCTP_SDK_ROOT= -DCTP_USE_FETCH_DEPS=ON    # 退 0（6.0s）
# 两个配置下 ctp_smoke/CTestTestfile.cmake 的 add_test 数均为 0（无 rsk02） ⇒ 总数 5
```
**代码链**：`CTP_USE_FETCH_DEPS` 仅影响 protobuf 供给（`cpp/cmake/HgDependencies.cmake:16–17,27,67,154`）；`CTP_FOUND` 由 `cpp/ctp_smoke/CMakeLists.txt:81` / `:88` 独立置位；`cpp/CMakeLists.txt` 顺序 **`:54 add_subdirectory(ctp_smoke)` 早于 `:61 include(cmake/HgDependencies.cmake)`** ⇒ fetch 逻辑**结构上不可能**影响 `rsk02` 注册。（**仅 configure、未 build / 未 ctest**，见 §6。）

**(d) 脚注 ⑥ 兜底依据（复核者读码实证）**：
- `cpp/src/CMakeLists.txt:14–19` = `add_subdirectory(ctp_adapter … hgcp)` ×6；`:25–31` = `target_link_libraries(hg-trading …6 模块)`；`cpp/src/main.cc:15–20` = `#include "hg/<mod>/module.h"` ×6 ⇒ 「删 / 改名**未同步**」⇒ **构建失败（CI 红）**（有效兜底）。
- `hg.skeleton_modules`（`cpp/src/CMakeLists.txt:35` = `hg-trading --self-test`）断言 = `cpp/src/main.cc:53–58` 以**字面量**比对六模块 `module_name()`、`:61` 打印 `SELFTEST: PASS` ⇒ **仅**保证六模块在且可调用，**不**把 `E4` 扫描根集合与实际目录绑定。
- `E1` / `E2` / `E3` 按**内容**扫描（不钉目录名）；`E6` 只扫 `web` ⇒ §3.1–§3.6 **无其它目录名强制点**。
⇒ 「**部分兜底；对『已同步的完整迁移』无自动化兜底**」**成立**。

**(e) 零改动与锚点口径**：
```
$ find cpp/src -type f | sort | xargs md5sum | md5sum            → 40d8b797a46a7b1f4c76411e21118ca0  （=自报）
$ find java/src -type f | LC_ALL=C sort | xargs md5sum | md5sum  → 152fb4447ef9a6cc55e17b774c04eca9  （=自报；318 文件）
$ find java/src -type f | sort | xargs md5sum | md5sum           → 6bfe9cc3227be5c6b8c1deb1763642ce  （裸 sort；同 318 文件、内容相同）
```
`java/pom.xml`（`4cefba7502d2c877183972e96d4497ca`）、`cpp/CMakeLists.txt`（`29e158820b753d136bd2e25be653d428`）对 T-031 期快照**未变**。⇒ **`java/src` 内容级锚须写 `LC_ALL=C sort`**，否则跨人比对出现**假差异**（裸 `sort` 与 `LC_ALL=C sort` 对含特殊字符路径排序不同）。

## T031-120.5 度量与「纯追加」证明

| 指标 | 数值 |
|---|---|
| 本文件改前基线（**补录者自测**） | `fac8b8da4a50bc6796fe80de7bcefdef` / `2111` 行（3×10s 静默：`18:38:52` / `18:39:02` / `18:39:12` 三次同值） |
| **纯追加校验** | `head -2111 docs/TEST_RESULT.md \| md5sum` = **`fac8b8da4a50bc6796fe80de7bcefdef`**（**等于改前基线** ⇒ 既有 `2111` 行**逐字节未改**） |
| 本文件改后 `md5` / 行数 / 时刻 | **见补录回报（以实时为准）** |
| `T-120` 严重级缺陷 | **`0`**（`TE-T120-001` 一般级，**已订正并复检闭合**） |
| `T-120` delta 复检结论 | **通过（0 缺陷）** |

> 本文件**不在正文内嵌自身落笔后 md5**，以免自引用失效（与既有章同例）。

## T031-120.6 未决 / 不可判定（显式列出）

1. **空 SDK 下仅 configure、未 build / 未 ctest**：「无 CTP SDK ⇒ 三轨 `5/5`」我已实测**「`rsk02` 不注册 ⇒ 总数 5」**（全新 out-of-source，`CTP_USE_FETCH_DEPS` OFF/ON 双测）；但**未在新的空-root 构建目录跑完整 `ctest`**（需完整构建 + 外部 protobuf）⇒ 「`5/5` 全通过」部分依赖既有 `build-stub` 现场 `ctest = 5/5`（机制同源）。
2. **`te-verify-t031` 的 `fetch 5/5` 时点态不可逐字节复原**（其快照未留 `build-fetch/CMakeCache.txt`）。
3. **`build-fetch` `6/6` 依赖外部 `/home/ctpmini/ctpmini_admin/ctp_sdk`**（不在本仓）⇒ 无 SDK 环境不可复现（即 `TE-T120-001` 的「过度断言」点）。
4. **`ctp_adapter` T4 可达面（如 `CtAdapter::sendOrder`）不在 `E4` 扫描面** —— `ARCH §3.0.2` 脚注 ⑤ 已诚实披露；**我未评估**其是否有现实锁 / 同步 I/O（超出本单）。
5. **`T-031` 期快照非本仓产物**：`README.md` / `arch-check.sh` 无 git 基线，仅能与第三方复核者快照 `/root/te-t031/repo/…` 比对 ⇒ 若该快照本身非 T-031 交付原件，则「只加不改」类判定受此限制。

---

> **本章由测试工程师席（`te-verify-t120`）补录**（**事后补录，非当场记录**），**仅新增本章**、未改写任何既有章。§`T031-120.1` / `.2` 为**复核者实测值并附时点**；§`T031-120.4` 为**补录者自测原始输出**；`md5` / 行数**以实时为准**。`T-120` 结论：首轮 **有条件通过（退回）** → `TE-T120-001` 订正后 delta **通过（0 缺陷）**。

---

## T031-120.7 收口状态（补记，2026-09-21）

> 📌 **本节由编排者补记**（**非 `te-verify-t120` 所写**）。上文 `.0`~`.6` 系该席于 `2026-09-21 18:40:12` 落笔时的记录，本节为其后的**收口事实**。
> **补记动因**：`te-record-t120` 独立抽验时指出 `.6` 只含「未决 / 不可判定」，**缺「收口状态」半边**（派单规格 = 「收口状态**与**未决项」）。该间隙属**编排者 / 项目经理职权**（`DONE` 授权与守恒计数**不属验证方自评**），故不由验证席补写。

**① `T-120` = `DONE`**（判据 = **授权 + 独立验证**，本项目恒定口径）
- **授权** = **编排者 2026-09-21**（`T-120` 系编排者新立，并由编排者授权其转 `DONE`）。
- **独立验证链** = ① 首轮 `te-verify-t120` = **有条件通过**（＝依本项目惯例**退回**；缺陷 `TE-T120-001`）→ ② `tl-t120` **补修**（`README` §5 订正 + `docs/ARCHITECTURE.md` §3.0.2 `E4` 脚注 ⑥）→ ③ **delta 复检 = 通过、0 缺陷**。
- **非自评**：产出方 `tl-t120` 全程「**不自评、不置 `DONE`**」，并**主动声明**「无 SDK 三轨 `5/5`」系**推断**；该缺口后由复核方以**全新 out-of-source 配置**（`-DCTP_SDK_ROOT=`）补硬证据闭环。

**② 收口落点四处**（行号为**登记时点**实测；一律**以实时为准**）

| 落点 | 行（登记时点） | 内容要点 |
|---|---|---|
| `docs/TASKS.md` §1 表（`T-120` 行） | `:165` | 状态格 = `DONE`（判据 + 复核链） |
| `docs/TASKS.md` §2 详情块状态行 | `:2399` | `DONE（授权 + 独立验证）〔第 34 轮〕` + 验收证据 + 补修后锚点 + 证据章落点 |
| `docs/BOARD.md` §2 `DONE` 区 | `:198` | `T-120` 行（复核链、`E4` 实扫 `N=6`、补修后锚点） |
| `docs/BOARD.md` §5 变更记录 | `:353` | **第 34 轮**行（增量复核通过 → 转 `DONE`；含双实例冲突与去重留痕） |

**③ 分区守恒（编排者实测）** = `TODO 67 + IN_PROGRESS 1 + 已完成待复核 14 + DONE 21 + REJECTED 2 + BLOCKED 15 = 120` ✔
任务数 **`120`** / 总人时 **`1791`**（`TASKS.md` §5 九角色行合计）—— 本轮为**纯状态迁移 + 去重**，**无任务增删、无人时变化**。

**④ 立项与风险承接（链式留痕）**：`T-120` 由编排者**新立**（`tech-lead`，P0，3 人时，依赖 `T-031`），依据 = `ARCHITECTURE.md` §3.0.2 `E4` 脚注④「诚实披露」+ 独立复核 `te-verify-t031` 第 2 点（**判为独立任务、非 `T-031` 缺陷**）；新立前**已扫全表确认无既有承载**。**残留风险**（默认扫描根**部分缺席** ⇒ 少扫而不红）的**承接点 = `TASKS.md` `T-060` 验收标准第 6 条**（`code-reviewer` 的「E1~E6 全量审查」任务）。

**⑤ 过程事故留痕（供追溯，不隐去）**：`T-120` 收口期间发生**同席位多实例并行写** —— 根因为**编排者对同一席位连发多条序贯指令（＝重复派单）**；后果 = `TASKS.md` 曾并存**两条同题**「`T-120` 补修 delta 登记」段（已去重，`2635 → 2634` 行）。**处置** = 该席位主动停手报冲突、未与对方互覆盖；随后编排者**将 `TASKS.md` / `BOARD.md` 写权收归本身**以终止竞态。**终态经编排者独立复核：内容完整、无丢失、守恒成立。**

**⑥ 时点口径**：本节系**事后补记**（`T-120` 转 `DONE` **之后**）；上文 `.0`~`.6` 为 `18:40:12` 落笔时记录。任何锚点引用**一律以实时 `md5sum` 为准**。

# `T-102` 独立复核证据（事后补录，2026-09-21）

> 本章由测试工程师席（`te-rec-t102`）补录。**对象** = `T-102`（写面**子账户可见性 / 交易权限**强制补齐）的 **delta 复核** —— 前一复核方 `te-verify-t102` 判「**通过、0 阻断缺陷**」但诚实指出 1 处与**验收 ⑤ 原文**的冲突（②b 未独立成例），**编排者裁定「须补」**；实施方 `be-t102-fix` 已补修，本章核验**补修后冻结态**。章内严格分列「**补录者实测**（独立重跑，附时点）」与「**引述**（首轮复核 / 派单转述，未经我重跑者）」；`md5` / 行数**以实时为准**。**本单写权仅本文件一个**，其余制品全程只读；临时探针建于 `/hgt102`（隔离副本，含 `tools/`）、结束已删净并给复位证明。

## T102.0 冻结信息与基线

（`cwd` = 仓库根；内容级锚统一用 `LC_ALL=C sort`）

| 制品 | 补修前（`git show HEAD:`） | 补修后（冻结态，2026-09-21 19:12–19:17） |
|---|---|---|
| `java/src/test/java/com/hedgeguard/service/AuthServiceTest.java` | `846` 行 / `2b9a9e077f40d0d8e9002c21dfa587d6` | **`858` 行 / `615beb7d72f9e79f77e6dd0b9898ade0`** |
| `java/src/main/java/com/hedgeguard/service/AuthService.java` | `e5a384cca8eb5122b3716fb4b14e1c13` | **同**（逐字节未动） |
| `java/src/main/java/com/hedgeguard/service/PlanService.java` | `df9085fe999cf277ca4fb17f14bef26e` | **同**（逐字节未动） |
| `java/src/main` 内容级树锚 | — | **`2f6c135fde909d8cf786a50e69e11abb`** / **258** 文件 |

- **VCS**：`git rev-parse --short HEAD` = **`60ea187`**；`git status --porcelain` 仅两行 ` M docs/TASKS.md` / ` M java/src/test/java/com/hedgeguard/service/AuthServiceTest.java`；`git diff HEAD -- java/src/main` **空**（退出 0）。
- **`java/src` 全树内容级锚**（含 test）：`c4e1804316c5411b799c8c42e522a7ff` / **318** 文件。
- **⚠️ 「`src/main` 树锚两种形式不一致」的裁定（补录者实测）**：派单转述「实施方自报树锚 `c0981cfa…` / 258 **≠** 编排者实测 `2f6c135f…` / 258」。**经我实测，差异纯属「`cwd` / 路径前缀」不同**（`md5sum` 摘要含文件名，前缀变则哈希变），**两值文件数、内容一致**：
  - `cwd=仓库根` + `find java/src/main …` ⇒ **`2f6c135fde909d8cf786a50e69e11abb`**；
  - `cwd=java/` + `find src/main …` ⇒ **`c0981cfa4bed799826fa1c96ff8031b4`**（＝实施方口径）；
  - `cwd=仓库根` + 绝对路径 `find /root/myspace/hedge-guard/java/src/main …` ⇒ `35f8e0888a886558e7fe54d7eec23624`；`./` 前缀 ⇒ `cb42c831d5c31ddc1ffca108b97d36f2`。
  ⇒ **裁定：两自报值均「对」，属同一冻结态的不同取锚形式**；**本席采用「仓库根 + `find java/src/main` + `LC_ALL=C sort`」= `2f6c135f…` / 258 为规范化锚**，并**建议后续锚点一律注明 `cwd` 与相对前缀**。
  - **另证 `LC_ALL=C` 必写**：同树 `find java/src/main -type f | sort | xargs md5sum | md5sum`（裸 `sort`）⇒ **`4e7143aa372fe93872ad1538c2b75d31`**（因 locale 致假差异）—— 派单环境事实②属实。
- **隔离副本布局** = `/hgt102/{docs,java,tools}`（`java/` 仅 `pom.xml` + `src/`；`TMPDIR=/hgt102_tmp`，均在 `/` 非 `/tmp`）；`mvn` 在 `/hgt102/java` 下串行执行。副本 `src/main` 树锚复核 = `2f6c135f…` / 258（与源同）。**副本含 `tools/` 为必需**（`mvn validate` 的 `arch-check` exec 需 `tools/arch-check/arch-check.sh`；缺之则 `127 → BUILD FAILURE` 假失败）。

## T102.1 六条验收逐条判定（首轮复核结论，标注来源）

> ⚠️ **来源标注**：本节 ①③④ 的**首轮判定**系**引述** `te-verify-t102` 回执（**该回执未落入仓库** —— 全仓 `grep -rl te-verify-t102` 命中 **0**）；① ② ③ ④ ⑥ 中**标「补录者实测」者**为我在本单独立确认。

1. **① 写面强制点（引述 + 补录者实测）**：`requireWritablePlan`（`PlanService.java:1739`–`:1753`）→ 调**写面** `assertSubAccountWritable`（`:1751`，**补录者实测行号属实**）；覆盖 **15 写入口** = **11** 经 `requireWritablePlan`（`:624/:739/:822/:1106/:1142/:1180/:1205/:1227/:1251/:1279/:1330`）+ **4** 经 `requireSubAccountWritable`（`:257/:293/:360/:499`，定义 `:1772`）——**补录者实测计数一致**。`assertSubAccountVisible` **非悬空**：读面调用点 `SpotService.java:1544`（补录者实测属实）。
2. **② 判据 = 析取（补录者实测）**：`AuthService.java:440` = `if (leader || management) {`（`leader` ↔ `is_enterprise_leader='Y'`，`management` ↔ `isManagementRole`）——**析取属实**；M1 不接线有据（`FR-PLAN-14` 主体含财务/风控岗，接 `decompose` 会误拒）；**外依赖 `T-046`（`BLOCKED`）= M2 下单路径强制点**。
3. **③ 走「修正 javadoc」支（引述 + 补录者实测）**：`PlanService.java:1164`–`:1176` javadoc 明写「`FR-PLAN-13` 四节均未限制操作人身份 ⇒ 本 javadoc 据此订正、与实现一致」；`withdraw`（`:1178`）仅校验原因非空 + 状态，**不比较发起人** —— 补录者实测属实。
4. **④ `existingTradePlansScoped`（引述 + 补录者实测）**：定义 `PlanService.java:1704`、调用 `:1341` —— 补录者实测属实。
5. **⑤ 用例映射（补录者实测 + 本单 ②b 补正）**：① 越权写 ⇒ 拒（`PlanWritePathPermissionTest` 7 例）；②a/②b **两条单条件各独立成例** ⇒ **本轮补修落成**（见 §T102.4）；③ javadoc 一致性；④ 跨子账户不计入 `used`；**反退化自证**（见 §T102.3）。
6. **⑥ 冻结点全量（补录者实测）**：`mvn -B -o clean test` = **`444/0/0/0`**（原始输出见 §T102.5）；`@Test` grep **444** == surefire 名单合计 **444**；`AuthServiceTest` 本类 **15** == surefire `15`。

## T102.2 双向正确性（真实 `AuthService` 探针）

> 本节 1–3 系**引述首轮复核 `te-verify-t102`**（**补录者本单未重建探针、未自测**，据实标注）；4–5 为补录者据**读码**独立确认。

1. **（引述）11 写入口全拒 `10004` + 11 条留痕**：首轮复核以真实 `AuthService` 探针实测。
2. **（引述）误拒证伪**：财务/风控主体对**可见**子账户 `decomposeTradePlans` 放行 ⇒ 反证 `assertCanTrade` 未被误接。
3. **（引述）`CROSS_READONLY` 读放行 / 写拒**（`T-098` 口径，**未报缺陷**）。
4. **（补录者读码独立确认）读/写分离**：`SubAccountVisibility.java:69`/`:100` 两 default 断言 —— **读面**（`:73`）含哨兵 `0` 或含目标子账户 ⇒ 放行；**写面**（`:104`）**仅**含目标子账户（`!= 哨兵 0`）⇒ 放行；两方法在 `AuthService` 同名覆盖（`:507`/`:543`）**均追加 `t_unauthorized_attempt` 留痕**（`:517`/`:552`，`ATTEMPT_SUB_ACCOUNT_DENIED`、`10004`）。⇒ 与首轮结论一致。
5. **（补录者读码独立确认）`assertSubAccountVisible` 非悬空**：读面调用点 `SpotService.java:1544`；`AuthService.java:53` 的 `{@link #assertCanTrade}` 系 javadoc 引用非调用（与首轮「② 接线点 = M2」一致）。

## T102.3 反退化

**（引述）首轮 `te-verify-t102` 4 向变异（M1~M4）**：结论「全部被杀」—— **引述，补录者未重跑**。

**（补录者本单自建变异 · 独立复现）**：位点 = `AuthService.java:440` `if (leader || management)` → `if (leader && management)`（隔离副本 `/hgt102/java`，仅此一行；先备份 `.orig`）：
- **杀灭数 = 2 红**：`AuthServiceTest.enterpriseLeaderAloneCannotTrade:304`（②a）**+ `AuthServiceTest.managementRoleAloneCannotTrade:287`（新 ②b）**；`leaderWithManagementRoleCannotTrade` **存活**（合取下两条件同真仍拒）—— 与预期逐条相符。
- 变异态合计 = **444 测试 / 2 失败 / 0 错误 / 0 跳过**（`target/surefire-reports` 逐文件求和）。
- **复位证明**：`mv *.orig AuthService.java` 后 `diff` 与共享树同文件**空**、`md5 = e5a384cca8eb5122b3716fb4b14e1c13`（＝源）、无 `.orig` 残留；随后 **clean 重编译** 重跑 ⇒ **`444/0/0/0` / BUILD SUCCESS**（陈旧 class 假红已排除）。

## T102.4 缺陷与 delta 收口

- `TE-T102-001`（②b 未独立成例）：首轮复核指出 —— 旧实现中 ②b 系 `leaderWithManagementRoleCannotTrade` **方法内断言块**，靠前断言一失败则 ②b **不执行**（「控制看起来有、实际没有」）。**编排者裁定「须补」**，依据 = `TASKS.md` §2 `T-102` 验收 ⑤ 原文「② `assertCanTrade` 判据形态（依 `CH-010` **析取**）**两条单条件各须独立成例**……②a/②b **均不得以对方同时成立为前提**；**合取实现下此两例必红**」。
- **`be-t102-fix` 补修内容（补录者 `git diff` 实测）**：① 新增独立 `@Test` `managementRoleAloneCannotTrade`（`:275`/`:277`）—— **独立构造**（`addUser(2L,"approver")`；`roleDao.put(APPROVER)` + `userRoleDao.put`；**刻意不设** `is_enterprise_leader`）**+ 独立断言**（`assertFalse("Y".equals(...isEnterpriseLeader))`、`assertThrows(assertCanTrade(2L,3))` ⇒ `POSITION_SEPARATION_CONFLICT`、`attemptDao.rows.size()==1` + 类型 `ATTEMPT_MANAGEMENT_TRADE_BLOCKED`）；② **移除**原方法内的 ②b 内联块（无空断言残留）；③ `leaderWithManagementRoleCannotTrade` **保留并强化**：display 名订正为「两条件同满足」、补 `assertEquals("Y", …isEnterpriseLeader)`。
- **补录者 delta 复检结论 = `T-102` delta 通过（0 阻断）**：
  - ②b **确为真独立**：`@BeforeEach setUp()`（`AuthServiceTest.java:68`–`:83`）**每例新建全部 fake（含 `attemptDao`）** ⇒ `attemptDao.rows.size()==1` 系本方法专属、不依赖前置断言；新方法**不经**旧方法任何状态。
  - **原方法未被削弱**：仍覆盖「两条件同满足」并**强化**了 `isEnterpriseLeader=='Y'` 前置断言；旧 ②b 位置**无空断言留存**（`git diff` 已全删）。
  - **`src/main` 逐字节未动**：`git diff HEAD -- java/src/main` **空**；`AuthService.java` / `PlanService.java` md5 与 HEAD 同。
  - **既有 443 条未被改弱**：`git diff HEAD -- java/src` **仅** `AuthServiceTest.java`（`21 insertions / 9 deletions`）；承重类 `PlanWritePathPermissionTest` **7/7 绿**、`AuthServiceTest` 其余 **13** 条仍绿（冻结点全绿）。
  - **计数一致**：`@Test` grep **444** == surefire **444**；surefire **实载**三方法 `enterpriseLeaderAloneCannotTrade` / `leaderWithManagementRoleCannotTrade` / `managementRoleAloneCannotTrade`。
  - **反退化**：新 ②b 在合取实现下**必红**（实测 `:287` 变红），复位后 clean 全绿（§T102.3）。
  - **冻结点全量 `444/0/0/0`**（无排除，§T102.5）。
- **对编排者「须补」裁定的意见**：**认可**。验收 ⑤ 原文「②a/②b **均不得以对方同时成立为前提**；**合取实现下此两例必红**」对「独立成例」有**明文要求**，旧内联块在靠前断言失败时会**静默跳过** ②b ⇒ 确属覆盖缺口，**非过严**。**无反驳、无新缺陷。**

## T102.5 补录者抽验（不采信转述）

> **全部为「补录者自测」原始输出**，与 §T102.2 的「引述」严格分列。

**(a) 冻结态全量（`cwd=java/`）**：

```
$ mvn -B -o clean test
[INFO] Results:
[INFO] Tests run: 444, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:14 min
[INFO] Finished at: 2026-09-21T19:13:24+08:00
```

**(b) 计数一致**：

```
$ grep -rn "@Test" java/src/test --include=*.java | wc -l   → 444
$ grep -c "@Test" .../AuthServiceTest.java                   → 15
$ 各 surefire *.xml tests= 求和                              → 444
$ AuthServiceTest surefire testcase 计数                      → 15
```

**(c) 变异态（隔离副本 `/hgt102/java`，`:440` `||`→`&&`）**：

```
[ERROR] Tests run: 15, Failures: 2, Errors: 0, Skipped: 0 <<< FAILURE! -- in com.hedgeguard.service.AuthServiceTest
[ERROR] com.hedgeguard.service.AuthServiceTest.enterpriseLeaderAloneCannotTrade ... <<< FAILURE!
         at com.hedgeguard.service.AuthServiceTest.enterpriseLeaderAloneCannotTrade(AuthServiceTest.java:304)
[ERROR] com.hedgeguard.service.AuthServiceTest.managementRoleAloneCannotTrade ... <<< FAILURE!
         at com.hedgeguard.service.AuthServiceTest.managementRoleAloneCannotTrade(AuthServiceTest.java:287)
（全量合计 444 / 2 / 0 / 0；leaderWithManagementRoleCannotTrade 无 failure 子节点）
```

**(d) 复位后 clean 重跑**：`[INFO] Tests run: 444, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`。

**(e) `src/main` 零改动**：`git diff HEAD -- java/src/main` ⇒ 空（退出 0）。

**(f) 与转述不符者**：**0 处** —— 派单表内各值与补录者实测**逐项一致**（含 `AuthServiceTest` `615beb7d…`/858、`AuthService.java` `e5a384cc…`、`PlanService.java` `df9085fe…`、`@Test` **444**、surefire 三方法、变异 **2 红**）。**唯一「不一致」为 `src/main` 树锚**（`c0981cfa…` vs `2f6c135f…`），已裁定为**路径前缀差异**（§T102.0），**非事实错误**。

## T102.6 度量与「纯追加」证明

- 追加前：`docs/TEST_RESULT.md` = **`a803f846e56beadcfce4041f35a854ac` / 2286 行**（`19:16:46` / `19:16:56` / `19:17:06` 三次取样不变 ⇒ 已静默）。
- **纯追加口径**：追加后 `head -2286 docs/TEST_RESULT.md | md5sum` = **`a803f846e56beadcfce4041f35a854ac`**（与追加前逐字节相同 ⇒ 前 2286 行未被改写）；**本单仅追加、未改写任何既有章**。
- 追加后：`2408` 行；**首次追加（未回填本节数字）后全文 `md5` = `bf2161c09d69273325e50b81141ca092`（`19:17:59`）**；因本行数字回填会再次改变全文 `md5`，故**全文 `md5` 一律以实时 `md5sum` 为准**；新章落点 = 第 **2288** 行起（第 2287 行为章节间空行），末行止于 `2408`。**落笔前已 3×10s 静默取样不变**（`19:16:46/56/19:17:06`）。

## T102.7 未决 / 不可判定 / 不适用项

- **（沿用首轮 5 条不适用项）**：① `scopeOf` 不读 `perm_type` ⇒ 归 **`T-099`**（读面取数入口，非本单）；② SPOT 写面（`T-110`）/ PRODUCT+PLAN 写面（`T-112`）/ `CROSS_READONLY` 写面拒绝（`T-098`，原 `T-115`）**非本单**；③ `CROSS_READONLY` **读面放行**系既定口径（`FR-AUTH-13` 验收 1），**未报缺陷**；④ 分页 / 排序、路由等值断言、合规预校验**非本单**；⑤ `latestPrecheckSnapshot` / `listBindings` 无 `operatorId` **非缺口**（`PlanHandler.detail` 前置已 scoped `getPlan`）。
- **外依赖**：`assertCanTrade` 的 **M2 真强制点 = `T-046`（`BLOCKED`）**；M1 不接线为**有意**（`FR-PLAN-14` 主体含财务/风控岗）。
- **（本单新增观察，非缺陷）** `T-102` 首轮复核回执**未落入仓库**（全仓 `grep -rl te-verify-t102` 命中 **0**）⇒ 本章对首轮结论一律**标「引述」**；如需权威存档，建议编排者补录首轮回执。

# `T-099` 独立复核证据（事后补录，2026-09-21）

> 本章由测试工程师席（`te-verify-t099`）补录。**对象** = `T-099`（处置 `DmUserSubAccountPermDao.scopeOf` **不读 `perm_type`** 的机制缺陷）的**独立复核**。**处置形态经一次改判**：阶段一（`be-t099`）「仅给实现加 `@Deprecated`」→ 阶段二（`be-t099b`，编排队裁定）「**删除**」。章内严格分列「**补录者实测**（独立重跑，附时点）」与「**引述**（派单/产出方自述，未经我重跑者）」；`md5` / 行数**以实时为准**。**本单写权仅 `docs/TEST_RESULT.md` 一个**，其余制品全程**只读**；临时副本 `/hg099_*`（均在 `/`、非 `/tmp`，含 `tools/`）结束已删净并给复位证明。
>
> **结论 = 通过（0 阻断缺陷）**。删除**完整且不过头**；防复活守卫**双向承重**；**零新增编译告警**；`CONTRACTS.md` 勘误**边界合规、体例合规**；`mvn -B -o clean test` = **`446/0/0/0`**。**改判（删除 vs 只弃用实现）经我独立复现探针后，判「正当」**（详见 §T099.1）。

## T099.0 冻结信息与基线

（`cwd` = 仓库根；内容级锚统一用 `LC_ALL=C sort`）

| 制品 | 值（复核期，实时） |
|---|---|
| `java/src` 全树内容级锚（`cwd=root`、前缀 `java/src`） | **`4826aaa849521b74543b00b13e18e961` / 319 文件** |
| `java/src/main` 内容级锚（`cwd=root`、前缀 `java/src/main`） | `d3f2e0d8f46603a15a646fedff1ebc48` / 258 文件 |
| `java/CONTRACTS.md` | **`51526c45a9833dcfff6cc59dcd564a20` / 805 行** |
| `docs/TEST_RESULT.md`（追加前） | **`53535408039f8caeff84b18b1d199967` / 2408 行** |
| `java/src` 单文件 `md5`（10 件） | 见 §T099.2 |

- **VCS**：`git rev-parse --short HEAD` = **`d0d76c8`**；`git status --porcelain` = **9 `M` + 1 `??`**（`??` = 新增守卫 `T099ScopeOfDeprecationTest.java`）。**「改动 10 个文件」自报属实**（9 改 + 1 新）。
- **基线法（`git archive HEAD`）**：**基线 = HEAD `d0d76c8`**（**含** `scopeOf`，即 T-099 前态）；以**只读** `git archive HEAD | tar -x -C /hg099_base` 取出，**不动工作树**。**现行** = 工作树（`cp -a {docs,java,tools}` → `/hg099_cur`）。两副本布局均 `<root>/{docs,java,tools}`（含 `tools/`，否则 `mvn validate` 的 `arch-check` exec 报 `127` 假失败）；`TMPDIR=/hg099_tmp`（在 `/`）。基线 `java/src` = **318 文件**（现行 319 = +守卫类 1）。
- **基线对照量**：`mvn -B -o test`（基线）= **`444/0/0/0`**；现行 = **`446/0/0/0`** ⇒ 净增 **+2** = 守卫类 2 个 `@Test`（`scopeOfMustNotBeRedeclared` / `interfaceContractIntact`），**未删任何既有用例**。
- ⚠️ **复核期并发写者（据实披露）**：复核期间 `docs/ARCHITECTURE.md` **被另一席位改动**（起点实测 `3c7e2e1ac202698cb453bbf2e1b302e5` / **2780** 行 → 现 `e9a61b3134fe1fa04cdfe2d7bfdd7845` / **2786** 行，新增 **v1.5.14**「§8.2② 订正 `scopeOf` 相关失效表述（`T-099` 落地）」；即派单称「由架构席另行订正」的**那处已同日落地**）。`java/src` / `java/CONTRACTS.md` / `docs/TEST_RESULT.md` 三锚**复核期内各自不变**（详见 §T099.5、§T099.7）——**我未改任何共享制品**。

## T099.1 处置形态的两阶段与改判依据

- **阶段一（`be-t099`；引述产出方自报）**：形态 = **只给实现加 `@Deprecated`**（接口不加）⇒ `446/0/0/0`。
- **阶段二（`be-t099b`；编排队裁定改判）**：改为**删除**（接口声明 + 实现 + 5 测试假件 `override`）。
- **改判依据 = `javac` 探针（补录者独立复现，非引述）**：探针原置于 `/root/depprobe`（`I.java` / `C.java` / `M.java`，**接口方法 `m()` 未弃用** + 实现 `C.m()` 标 `@Deprecated(since="x", forRemoval=true)` + `M` 分别**经接口引用**（`viaIface`）与**经具体类型**（`viaImpl`）调用）。我在 `/hg099_probe`（隔离副本）**独立重跑**：

```
$ javac -Xlint:deprecation I.java C.java M.java
M.java:3: warning: [removal] m() in C has been deprecated and marked for removal
  static void viaImpl(C c) { c.m(); }
                              ^
1 warning
```

  ⇒ **经接口引用调用（`M.java:2` `viaIface`）零告警；仅经具体类型调用（`M.java:3`）告警**。**本仓真实场景 = 「注入 `UserSubAccountPermDao`（接口类型）→ 调 `scopeOf`」** ⇒ 阶段一形态**对该场景零防护**（「控制看起来有、实际没有」）。**探针结论成立，改判依据属实**。
- **补录者于「改判」的意见 = 认可，且认为未删过头**，理由三条：① 探针实证「弃用实现」对本仓入口无防护（上）；② 该方法的签名 `SubAccountScope scopeOf(long, String)` **无法表达「无 scope」**——`SubAccountScope` 只能 `of(column, allowed)`（空集 ⇒ 恒假 `column IN (-1)`），**无「不过滤 / 无 scope」表达**，故选项 (a)「补 `perm_type` 分支」缺可承载的返回形态（架构 v1.5.14 §8.2②(b) 亦载此独立理由）；③ **删除安全**——`scopeOf` 在 `src/main` **0 处调用**（见 §T099.5），删除对生产**零行为影响**。

## T099.2 删除完整性 + 防复活守卫双向承重

**(a) `java/src` 全树 `scopeOf` 残留（补录者实测）**：`grep -rn "scopeOf" java/src` 现命中**仅 javadoc / 注释 / 守卫类的字符串字面量与测试方法名**——**无声明、无实现、无调用**：

```
$ grep -rn "scopeOf" java/src
java/src/test/java/com/hedgeguard/dao/impl/M1AuthDaoImplTest.java:45/47   ...javadoc（已删除注记）...
java/src/test/java/com/hedgeguard/dao/impl/T099ScopeOfDeprecationTest.java:14/16/23/29  ...javadoc...
java/src/test/java/com/hedgeguard/dao/impl/T099ScopeOfDeprecationTest.java:35/36/38/41  ...@DisplayName/方法名/断言消息...
java/src/main/java/com/hedgeguard/dao/impl/DmUserSubAccountPermDao.java:24   ...javadoc...
java/src/main/java/com/hedgeguard/dao/UserSubAccountPermDao.java:18          ...javadoc...
```

**(b) 编译层证据（`javap`，最稳）**：接口类型**确实不再声明** `scopeOf`：

```
$ javap -p -classpath target/classes com.hedgeguard.dao.UserSubAccountPermDao
public interface com.hedgeguard.dao.UserSubAccountPermDao extends com.hedgeguard.dao.BaseDao<...> {
  public abstract java.util.List<...UserSubAccountPerm> findByUser(long);
}
$ javap -p -classpath target/classes com.hedgeguard.dao.impl.DmUserSubAccountPermDao
public final class com.hedgeguard.dao.impl.DmUserSubAccountPermDao ... implements ...UserSubAccountPermDao {
  ... public java.util.List<...UserSubAccountPerm> findByUser(long); ...   （全表无 scopeOf）
}
```

**(c) 字节码级无残留（`grep -ra`）**：`target/classes`（`src/main`）对 `scopeOf` **零命中**（无 `MethodRef`、无字符串）；`target/test-classes` 仅守卫类 1 命中（其字符串字面量）。

**(d) 删除面清点（补录者实测，基线侧）**：基线恰 **2 个 `src/main` 文件**（接口 `UserSubAccountPermDao:31` 声明 + 实现 `DmUserSubAccountPermDao:108`）+ **5 个测试假件 `override`**（`AuthServiceTest` / `PlanT112VisibilityTest` / `SpotT110VisibilityTest` / `VarietyT112VisibilityTest` / `WsAuthorizerTest`）= **全删、无遗漏**。5 假件的 override 均为 `throw unsupported`（删后语义**无削弱**，仅因接口方法消失而必须删）。

**(e) 防复活守卫 = 反射断言**：`T099ScopeOfDeprecationTest`（类名保留历史名，**保留理由已在类 javadoc 披露**）以 `getDeclaredMethods()` 断言**接口与实现均不再声明** `scopeOf(long, String)`；另断言 `findByUser(long)` **仍在**（防误删）。

**(f) 双向承重（补录者自建变异 · 隔离副本 `/hg099_cur`，**两向均 `clean` 重编译**，避陈旧 class 假红）**：

| 变异 | 位点 | 结果 | 原始输出 |
|---|---|---|---|
| **(i) impl-only 加回** | 仅给实现追加 `public SubAccountScope scopeOf(long,String)`（**接口不加**） | **守卫红** | `scopeOfMustNotBeRedeclared` `:40` `AssertionFailedError: DmUserSubAccountPermDao 不得重新声明 scopeOf ==> expected: <false> but was: <true>`；`Tests run: 2, Failures: 1` |
| **(ii) 全量加回** | 自基线 `HEAD` `cp`：接口 + 实现 + 5 假件 | **守卫红** | `scopeOfMustNotBeRedeclared` `:37` `AssertionFailedError: UserSubAccountPermDao 不得重新声明 scopeOf ==> expected: <false> but was: <true>`；`Tests run: 2, Failures: 1` |

  ⚠️ **（我的一处方法论失误，据实留痕）**：首轮跑变异 (ii) 时**未 `clean`**，`cp -a` 保留的源 mtime（`19:20:40`）**早于**变异 (i) 留下的 `.class`（`19:50/19:51`）⇒ Maven 判「源未变、不重编译」⇒ **读到变异 (i) 的陈旧 class**（报 `:40` 而非 `:37`）。**改以 `clean` 重跑（上表值）后**，`:37`（接口）如预期命中。**固化纪律**：变异 / 复位**一律 `clean` 重编译**再断言。
- **复位证明**：`.orig` 备份回位后，隔离副本 `java/src` 内容级锚复位为 **`d2a919d0733ff045bff5aac578206806` / 319**（`cwd=/hg099_cur/java`、前缀 `src`；与初拷一致），`DmUserSubAccountPermDao.java` `md5` = **`2ca615c5a2a204193261f71d479eadd3`**（＝源）；随后 **clean 重跑全量 = `446/0/0/0`**（§T099.6）。**共享树全程零改动**。

## T099.3 零新增告警（基线对照法 + 逐条 diff）

- **方法（补录者自选，与派单建议一致）**：`git archive HEAD` 取基线 → 基线 `/hg099_base` 与现行 `/hg099_cur` **各跑一次 `mvn -o -B clean test-compile`**（`java/pom.xml` 已设 `<showWarnings>true</showWarnings>` + `<arg>-Xlint:all</arg>`）→ 抽 `[WARNING]` 逐条 diff（路径前缀归一）。
- **结果：基线 1 条 == 现行 1 条，逐字节相同（`diff` 为空）**：

```
[WARNING] <ISO>/java/src/test/java/com/hedgeguard/dao/impl/M1DaoReadAndClobTest.java:[107,44] unchecked call to findById(ID) as a member of the raw type com.hedgeguard.dao.impl.AbstractDmDao
```

  ⇒ **零新增告警**。**删除 `scopeOf` 亦未引入任何 `deprecation` 告警**（`@Deprecated` 随删除一并消失，接口与实现均无残留弃用标记）。
- **注**：日志中另有 `警告参数 -Wall=1 -Wextra=1 -Werror=1` 一行，系 **`tools/arch-check` 的 `E3`（C++）脚本输出**（非 `javac` 编译告警），不在对照面内。

## T099.4 CONTRACTS 勘误边界与体例合规

`git diff HEAD -- java/CONTRACTS.md` = **恰 3 个 hunk**（+3 / −2）：`:312`、§5.1 编辑登记行（新 `:735`）、`:798`→`:799`。**未触及任何契约实质**（字段 / 方法签名 / 错误码 / 信封 / 路径）：

| 项 | 实测 |
|---|---|
| ① diff 边界 | 仅 `:312`、`:735`（登记行）、`:799` **3 处**；**无第 4 处** |
| ② 新指引一致性 | `:799` =「取数入口 = `AuthService.visibleSubAccountIds(userId)` → `SubAccountScope.of(...)`」——与 **`ARCHITECTURE.md` §8.2②**（`:2073`–`:2075`）**同构**；与**代码现实**一致（`AuthService.visibleSubAccountIds(long)` 实测在 `AuthService.java:571`，返回 `List<Integer>`、`CROSS_READONLY` 行显式并入哨兵 `0`；`SubAccountScope.of(String,List)` 实存）|
| ③ 第三处同类扫描 | 全文件 `grep -n scopeOf` = **仅 `:312` / `:735` / `:799`**（`:735` 为登记行内叙述）；**无遗漏** |
| ④ 版本/署名 | 登记行 =「时间 / 编辑人 / 内容摘要 / 版本是否升版」四元，**合该文件 `§0`（`:41`）「署名要求」**；编辑人 `backend-engineer#java`（实例 `be-t099b`）= 该文件**唯一写者**（`:38`）；**「未升版（`HGC-1.6` 不变）」判定合规**——勘误只动**警告行 + 检查清单行**，无签名 / 字段 / 错误码 / 路径变更，与相邻两行（`precheckResult` / `java2` 位号澄清）**同体例** |
| ⑤ `T-105` 行完好 | `:734` = `T-105` 交付行（`findPageScoped` / `findAllScoped` / `findAllOrderByCreatedDesc`）**完整**；`:735` = 本登记行；`git diff` 逐 hunk 判**无其它行被误改**（产出方自纠的「曾误覆盖 `T-105` 行」**已恢复且当前无残留**）|

## T099.5 镜像 / 外范围扫描

- **全仓（`docs/**`、`tools/**`、`cpp/**`、`web/**`、`dev-team/**`）`scopeOf` 位置**：均为**历史留痕 / 任务描述 / Q&A 工单 / 纠正性警告**——**无任何一处把 `scopeOf` 当「现行权威取数入口」**。
- **唯一「已过时」指针 = `docs/ARCHITECTURE.md` §8.2② 的旧句**（`:2078`「若要恢复以 `scopeOf` 为入口…」+ `:2079`「`visibleSubAccountIds` 与 `scopeOf` 全仓均 0 处调用」）。
  - 🔴 **据派单要求：仅列出、不报为本单缺陷、不改**（归**架构席**）。
  - **但**：复核期间该处**已由架构席同日订正** —— `docs/ARCHITECTURE.md` 现为 **v1.5.14**（`:2733` 版行列 + §8.2② `:2080`–`:2084` 📌 块），**旧句保留、按现况追加订正**：`scopeOf` = **不存在**（非「0 处调用」）、恢复路径**已排除**（两条理由）、`visibleSubAccountIds` 路径**已接线**（`T-100`/`T-101`/`T-103`）。⇒ **本项前置缺陷已清零**，无需另行处置。
- **生产行为影响 = 无（补录者实测）**：基线 `src/main` 对 `scopeOf` **0 处调用**（`grep -rn "\.scopeOf(" /hg099_base/java/src/main` ⇒ 空）；`visibleSubAccountIds` 路径仍在且被消费；`mvn -B -o clean test` 全绿（含 `arch-check` E1–E6 各退 `0`）。⇒ **删除为惰性变更**。

## T099.6 补录者抽验（不采信转述）

> 全部为**补录者自测**原始输出；与 §T099.1 的「引述」严格分列。

**(a) 现行全量（隔离副本，`cwd=/hg099_cur/java`，`clean` 无排除、复位后）**：

```
$ mvn -B -o clean test
[INFO] Tests run: 446, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**(b) 基线全量（`/hg099_base/java`）**：`Tests run: 444, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`。

**(c) 计数口径（现行）**：

```
$ grep -rn "@Test" src/test --include=*.java | wc -l        → 446
$ surefire *.xml 逐文件 tests= 求和                          → 446
$ surefire 文件数                                            → 55
$ T099ScopeOfDeprecationTest.xml: testcase = {scopeOfMustNotBeRedeclared, interfaceContractIntact}
                                   tests="2" errors="0" skipped="0" failures="0"
```

  ⇒ `@Test` grep **==** surefire 求和 **==** `446`；守卫类**在 surefire 名单内实载且通过**。

**(d) 架构判据（随 `mvn validate` 执行）**：`arch-check` 聚合 = **E1/E2/E3/E4/E5/E6 各退 `0`**、聚合退 `0`（`E5` 扫描 142 文件）。

**(e) 与转述不符者**：
  - **0 处「事实错误」**（自报的删除面 / 锚点 / `446` / 计数 / `CONTRACTS` `:312`+`:799`+登记行，**逐项复现一致**）。
  - **1 处需补记**：派单「背景」未提 `ARCHITECTURE.md` 已随复核推进到 **v1.5.14**（我按实时实测补记，见 §T099.5）。
  - **1 处程序性观察（非缺陷）**：`docs/TASKS.md` `T-099` **验收标准原文仍是「二者择一：(a) 补分支 / (b) 弃用指向」**，而实际交付 = **(c) 删除**——**改判由编排队裁定、超出任务原文**。**建议**：项目经理将该验收标准**回写**为「删除」并登记改判授权链（否则 S5 / 门禁按字面判会误判「未按验收交付」）。**（我未改 `TASKS.md`——非我写权。）**

## T099.7 度量与「纯追加」证明

- 追加前：`docs/TEST_RESULT.md` = **`53535408039f8caeff84b18b1d199967` / 2408 行**（`19:57:19` / `19:57:29` / `19:57:39` 三次取样不变 ⇒ 已静默）。
- **纯追加口径**：追加后 `head -2408 docs/TEST_RESULT.md | md5sum` = **`53535408039f8caeff84b18b1d199967`**（与追加前逐字节相同 ⇒ 前 2408 行未被改写）；**本单仅追加、未改写任何既有章**。
- 追加后：**2569 行**；新章落点 = 第 **2410** 行起（第 2409 行为章节间空行），末行止于本章末。**因本行数字回填会再次改变全文 `md5`，故全文 `md5` 一律以实时 `md5sum` 为准**。
- **写权核对**：本单**仅**写 `docs/TEST_RESULT.md`；`java/src` / `java/CONTRACTS.md` / `docs/ARCHITECTURE.md` / `docs/TASKS.md` 等**共享制品全程只读**（复核期内 `java/src` 锚 `4826aaa8…`/319、`CONTRACTS` `51526c45…`/805 各自不变；`ARCHITECTURE.md` 的变动系**另一席位**所为，非本席）。

## T099.8 未决 / 不可判定 / 不适用项

- **不适用（非本单）**：① `scopeOf` 的**历史缺陷本体**（不读 `perm_type`）已记于 `ARCHITECTURE.md` `v1.5.8`，本单只核**处置形态**；② 写面可见性（`T-098` / `T-110` / `T-112`）**非本单**；③ 分页 / 排序（`T-096`）、路由判据升级（`T-097`）**非本单**。
- **不可判定（据实）**：**阶段一（`be-t099`）的原始产物**（「只给实现加 `@Deprecated`」的中间态）**未落入仓库**（已被阶段二取代）⇒ §T099.1 中阶段一细节为**引述**；但**其改判依据（探针）已由我独立复现**，故改判结论**可判定**。
- **待回写（非缺陷，承 `TASKS.md` 写权）**：`T-099` 验收标准仍作「(a)/(b) 二者择一」，实际交付为「(c) 删除」⇒ 建议项目经理回写并登记改判授权链（见 §T099.6(e)）。
- **守卫的已知边界（观察，非缺陷）**：守卫只断言 `(long, String)` 形参签名；**异形参**的同名方法（或加在别的类型上）不在断言面内 —— 属**可接受**范围（历史签名即 `(long, String)`）。

# `T-032`（首批 + 装配下沉返工）独立复核证据（事后补录，2026-09-22）

> 复核人：`test-engineer` / 实例 `te-verify-t032`。**只读取证** —— 除本章外未改共享树任何制品；构建与变异**一律在 `/` 下隔离副本**内完成。

## T032.0 冻结与基线

- 树级锚（cwd = 仓库根；命令 `find cpp/src -type f | LC_ALL=C sort | xargs md5sum | md5sum`）= **`e29a4979512503a3ae213e2f3a0250af` / 52 文件**；`09:50:56` / `09:51:06` / `09:51:17` 三次取样不变（**3×10s 静默确认**）；复核结束 `09:56:30` 复测同值。
- 隔离副本布局 **`/t032iso/{docs,java,tools,web,cpp}`**：**必须含 `tools/`**（否则 `arch-check`/`mvn` 侧假失败）、**必须含 `docs/`**（否则路径型测试假红）；**副本与 `TMPDIR` 均置 `/` 下**（`/tmp` 为 804MB tmpfs，源码构建必 `No space left on device`）。
- 构建配置：`cmake -S cpp -B cpp/build-iso -DCTP_USE_FETCH_DEPS=OFF`（`CTP_FOUND=OFF`，系统 protobuf 3.21.12）。
- 逐文件 md5 / 行数与自报**逐一命中**：`server_loop.h` `94a4db948cce3e1d30ecd4bd3c881ea5`/112、`server_loop.cc` `9a0247cdfadf81c3ccf3a935a9e8792d`/233、`transport_test.cc` `75f6bd9f2e6eba35eda0fafb0f6c5bf5`/542、`tcp.h` `51fb5d4547c4085941340b0062c7c68f`/74、`hgcp/CMakeLists.txt` `5efc7c47ef02ee8f60ec46710bc091b1`/70、`T032_GAP_ANALYSIS.md` `c456411aecf9f8ad4fecba0afe957b26`/190。

## T032.1 装配归属复核（核心）

装配原语的**装配调用点唯一落在 `src/server_loop.cc`**：

| 原语 | 定义 | **装配调用** |
|---|---|---|
| `accept_one` | `tcp.cc:173` | `server_loop.cc:75` |
| `on_new_connection` | `endpoint.cc:44` / `session.cc:127` | `server_loop.cc:80` |
| `on_connected` | `session.cc:34` | `server_loop.cc:87` |
| `on_tick` | `session.cc:94` | `server_loop.cc:136` |
| `build_resume` | `session.cc:121` | `server_loop.cc:161` |

- `grep -rn "TestServer\|ServerLoop\|class .*Server" cpp/src/hgcp/tests/` → **无 `TestServer`/`ServerLoop` 类定义**（仅命中对生产类 `HgcpServerLoop` 的**使用**）。
- `send_all` **单一定义**（`tcp.cc:43`）+ 单一声明（`tcp.h:37`）；`server*` / `socket_util*` 死代码已删（`find` 只剩 `server_loop.{h,cc}`）。
- `CMakeLists` 源列表 ↔ 磁盘 `.cc` **12 = 12**（程序化比对 `MATCH: True`）；无 `.orig` / `.bak` / `.rej`。

## T032.2 `transport_test` 驱动器化 + E2E 覆盖逐项落点

`./src/hgcp/hgcp_transport_test` → **`transport_test: 742 checks, 0 failures -> PASS`**。

| 覆盖项 | 落点 | 备注 |
|---|---|---|
| 握手回环（body 逐字节、通道 SEQ） | `transport_test.cc:234-278` | |
| 半包 / 粘包 | `:319-333` / `:335-346` | |
| **逐字节喂** | **`session_test.cc:51-59`** | **更正：不在 `transport_test`**（session 级最坏分片） |
| 重复连接拒 `0x0302`（既有连接不受影响） | `:351-389` | |
| 心跳判死（`0x0301`） | `:391-430` | |
| 退避序列 500→8000 | `:432-462` | |
| 补数批 ≤500 | `:511-527` | |
| 端口 0 内核分配 / 非回环拒启动 | `:222-231` / `:216-220` | |
| 握手前业务帧拒 | `:280-304` | **仅帧级**：ERROR body 为空、线上无 `0x0107` code（见 §T032.7 O-1） |

驱动器化证据：文件仅含 `TestClient`（`:100-148`）、`connect_client`（`:150-165`）、driver glue（`:186-209`）、`TestPolicy`（body 接缝测试双）；**无服务端 accept / 握手门禁 / 心跳定时器装配**。

## T032.3 门禁原始输出

```
cmake --build cpp/build-iso -j$(nproc)   → exit 0 ; grep -ci warning = 0 ; grep -ci error = 0
ctest                                     → 100% tests passed, 0 tests failed out of 13 (5.73s)
bash tools/arch-check/arch-check.sh       → 聚合 exit 0
  #   通过(0)=6  命中(1)=0  用法/前置错误(2)=0
  #   E1 0 / E2 0 / E3 0 / E4 0 / E5 0 / E6 0
```

`-Wall -Wextra -Werror` **零告警**（Werror 下构建成功 + `grep -ci warning` 复核）。**E4 实扫**（非 SKIP）：`扫描根 cpp/src/{order_engine,risk_engine,risk_state} 并集`、**`待扫描文件: 6 个`**。
13 项 = `arch_check` + `rsk01×3` + `hg.skeleton_modules` + `hgcp.{frame,envelope,crypto_auth,timing,resume_buffer,endpoint,session,transport}_test`。

## T032.4 自设变异（4/4 全杀）+ 复位证明

| 变异 | 位点 | 内容 | 结果 | 承重断言（原始输出） |
|---|---|---|---|---|
| M1 | `server_loop.cc:181` | 握手门禁 `==` → `!=` | 红 | `48 checks, 11 failures`（`:301/302/317/362/402/443/475`） |
| M2 | `server_loop.cc:140` | 心跳判死后不再 `close_conn()` | 红 | `742 checks, 1 failure`（`:420 !srv.has_connection()`） |
| M3 | `server_loop.cc:80` | 重复连接判据 `kRejectDuplicate` → `kAccept` | 红 | `35 checks, 9 failures` |
| M4 | `session.cc:124` | 批次上限 `kResumeMaxBatchFrames`×2 | 红 | `737 checks, 1 failure`（`:522 bs.size()==2u`） |

**M1–M3 均打在新增生产文件 `src/server_loop.cc`** ⇒ 直接证「新装配承重 + 被 `transport_test` 真实驱动（非影子副本）」。
复位证明：`cp *.orig` 回原文件、`diff` 空；`md5sum` 复原 `server_loop.cc=9a0247cdfadf81c3ccf3a935a9e8792d`、`session.cc=05c6e418daf6c5dad15f5216a5768d66`；**全量 clean 重编译**（`rm -rf build-mut` 重配）→ **13/13、0 warning**；`grep -rn "MUTATION\|MUT-M" cpp/src` = **(none)**；副本锚点复原 `e29a4979…`/52。
**共享源树零瞬时变异态**：`grep -rn "MUTATION\|MUT-M" cpp/` 仅命中**既有第三方** `cpp/build-fetch/_deps/protobuf-src/**`（protobuf 自带 PDDM 宏，先前既存，与本任务无关）。

## T032.5 `0x0104` / `0x0308` 标注复核

显式标注「待 `CH-013` 裁定」的全部位点：

- `include/hg/hgcp/frame.h:41-42`（`kFrameTooLarge` 注释）+ `:49-50`（独立⚠️块）；
- `src/frame.cc:106-108`（取更具体者 `0x0308`、定案前不得视为权威）；
- `T032_GAP_ANALYSIS.md` 表项 `I-2`；
- 另 `server_loop.h:17`、`server_loop.cc:137/167/179`、`payload_codec.h:31`、`hgcp/CMakeLists.txt:13` 均指向 `CH-013`。

## T032.6 零污染 + 非本任务改动归属

- **零改动**：`tools/**`、`java/**`、`cpp/proto_smoke/**`、`cpp/ctp_smoke/**`；`proto/hgcp.proto` **未产出**（符合 blocked 纪律）。
- `git status --short`（受跟踪）：`cpp/src/hgcp/CMakeLists.txt`（T-032）+ 下列四 docs（**非本任务**）：

| 文件 | 真实作者（编排者裁定） |
|---|---|
| `docs/ARCHITECTURE.md`（+206/-0 **纯追加**） | 架构席 `tl-proto-gap`（附录 C `CH-013` 提案，**未生效 / 未升版**） |
| `docs/BOARD.md` / `docs/TASKS.md` | 编排者（`T-032` 状态推进、看板轮次、`CH-012` 登记） |
| `docs/REQUIREMENTS/INDEX.md` | 编排者（`CH-012` 确认 + `CH-013` 编号登记） |

## T032.7 三条观察与处置裁定

| # | 观察 | 处置 |
|---|---|---|
| O-1 | 「拒 `0x0107`」**仅帧级**、线上 ERROR body 为空 | **非缺陷**（源于字段级 schema 未定，`GAP §5 S-3` 已披露）⇒ 记为「**已知限制**」，挂 `CH-013` 验收延伸：`PbPayloadCodec` 接入后须补线上 code + `test_not_handshaken_rejected` 增断言 |
| O-2 | `wait_readable` 在 `server_loop.cc:18-31` 与 `transport_test.cc:85-98` **各一份** | **接受、不返工**（消费者不同；强去重会使测试依赖生产内部实现细节） |
| O-3 | `test_resume_batches` B 段（`:511-527`）用独立 `HgcpSession` 做结构断言 | **接受**（driver 级 session 契约断言，符合边界口径） |

## T032.8 度量与「纯追加」证明

- 追加前：`docs/TEST_RESULT.md` = **`c5d6f38f992c9d9eae3017c8abe25bc1` / 2569 行**（`09:57:26` / `09:57:36` / `09:57:46` 三次取样不变 ⇒ 已静默）。
- **纯追加口径**：追加后 `head -2569 docs/TEST_RESULT.md | md5sum` = **`c5d6f38f992c9d9eae3017c8abe25bc1`**（与追加前逐字节相同 ⇒ 前 2569 行未被改写）；**本单仅追加、未改写任何既有章**。
- 追加后**行数 / 新章落点 / 全文 `md5` 一律以实时 `md5sum` 为准**（本行数字回填会再次改变全文 `md5`）。
- **写权核对**：本单**仅**写 `docs/TEST_RESULT.md`；其余 `docs/**`、`tools/**`、`java/**`、`cpp/**` 全程只读。

## T032.9 未决 / 不适用项

- **不适用（非本单）**：`NONFUNCTIONAL.md` / `BOUNDARY.md` 用例执行；Java/前端门禁实扫 —— 本单为 `#cpp` 返工**增量复核**，仅核 `arch-check` 聚合入口 E5 退出码 `0`（未跑 Java 单测）。
- **阻塞（非本单）**：`proto/hgcp.proto` 与元协议 payload 编解码受 `CH-013` 阻塞（`T032_GAP_ANALYSIS.md §5` 已单列）。
- **无阻断未决**；待裁定项：`docs/*` 四文件改动归属（**非 T-032**，见 §T032.6）。
- **已知限制（承接 `CH-013`）**：`0x0107` 线上码缺失（§T032.7 O-1）。

# `T-032` 转录批（`proto/hgcp.proto` vs `§4.2.8`）独立复核证据（事后补录，2026-09-22）

> 复核人：`test-engineer` / 实例 `te-verify-proto2`。原复核席 `te-verify-proto` 已完成首轮（其结论经编排者转述），**但其实例不可续用** ⇒ 本席**独立复跑 + 增量复检 + 落盘**。
> **只读取证** —— 除本章外未改共享树任何制品（`cpp/**`、`proto/**`、`docs/**`、`tools/**`、`java/**` 全程只读）；构建与变异**一律在 `/` 下隔离副本**（`/vproto2/iso/**`）；`/tmp` 为 804MB tmpfs、**未用**（`TMPDIR=/vproto2/tmp`）。
> **体例**：凡标「**据 `te-verify-proto` 回执（引述）**」者 = 前序席结论（本席未复跑其原始脚本）；**未标注者 = 本席实测**（给命令与原始输出）。

## T032PROTO.0 冻结与基线（含并发写实测）

**本席实测锚点**（`cwd = 仓库根`）：

- `cpp/src` 树锚：`find cpp/src -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`97524bde8c4dbebcfa2c67c8b941b855` / 54 文件**（`12:08`~`12:25` 多次取样**恒此值**，零变动）。
- `proto/hgcp.proto` = **`860e401d01225f7532bbf7ec7acf291f` / 801 行**。
- `docs/ARCHITECTURE.md`（取样终态）= **`8d1d6830c61e4953a885482b7f4996da` / 3243 行**（抬标仍书 `v1.5.16`，见下「并发写」）。

**两版 ARCH 对照 + `§4.2.8.2–.7` 逐字节（本席实测）**：

| ARCH 版本 | 全文 md5 / 行数 | `§4.2.8.2–.7` 区段（`awk '/^##### 4\.2\.8\.2 /{p=1} /^##### 4\.2\.8\.8 /{p=0} p'`+`md5sum`） |
|---|---|---|
| `v1.5.15` | `c38fca066eb351c08aa990e854cd763d` / 3216 | `9d640c30f491236ec723d8040c1a42b2` / **139 行** |
| `v1.5.16` | `0c12832cf07c8e0f7dabd29da7353fbe` / 3237 | `9d640c30f491236ec723d8040c1a42b2` / **139 行** |

⇒ **关键事实（本席实测复证）**：`§4.2.8.2–.7`（= 承载「消息集 / 字段三元组 / 必填性 / 枚举」的**全部转录依据表**）在两版**逐字节相同** ⇒ **转录结论的适用面不受该轮并发写影响**。`v1.5.15` 取自 `git show e64e945:docs/ARCHITECTURE.md`（`md5sum` = `c38fca06…`，与 `/vproto/iso/docs/ARCHITECTURE.md` 一致）。

**⚠️ 并发写实测（本席新发现，与派单快照不符，以实测为准）**：本席作业期间 `docs/ARCHITECTURE.md` **正被架构席持续写入** —— 12:11:00~12:12:47 连续取样每 10s 皆变（`0c12832c…`→`3b16c45d…`→`44f8df5c…`→`e9218d75…`/3239→…→`2b0d4f12…`/3239），直至 **12:15:46 起稳定于 `8d1d6830…`/3243**（12:15:46 ~ 12:25:04 恒值）。此时 `§4.2.8.2–.7` **区段已变为 `08a51256bc9d92144ca2605d9850be96` / 141 行** —— 经 `diff` 定位，增量 = **`§4.2.8.7` 内新增 2 行散文注记**（`ComponentRole` 值名约定「**维持现状、不统一加前缀**」的例外裁定），**非 schema 变更**（无字段号 / 类型 / 枚举取值改动）。

- **对结论的影响（本席实测）**：以**现行** ARCH（`8d1d6830…`/3243）重跑 T032PROTO.1 机械差集 ⇒ **仍 0 差异**（见 `.1`）。故 `§4.2.8.2–.7` 的「逐字节相同」结论**限定于上文两版**；**对现行版，逐字相同的是「schema 载体（表体）」，散文注记有 +2 行**。锚点一律**以实时 `md5sum` 为准**。

## T032PROTO.1 机械化差集（**本席独立复跑**，非引述）

**本席自建脚本**（不依赖前序席 `/vproto/*.py`）：`/vproto2/parse_proto_indep.py`（proto → 枚举 / 消息 / 字段三元组 / 尾注释）、`/vproto2/diff_all_indep.py`（抽 `§4.2.8.2/.3/.4/.6/.7` + `§4.2.3` 类型码，做四类集合差）。**对「`v1.5.16` 快照」与「现行 `8d1d6830…`」各跑一次，结果同**：

```
[ARCH msg counts] meta=9 ctrl=17 evt=14 comp=10
[ARCH field sums] meta=38 ctrl=77 evt=99  top-level=214  composite=66
1) MESSAGE SET : MISSING=[] EXTRA=[] top-level order identical=True composite order identical=True
2) FIELD(name<->num<->type<->repeated) TRIAD : NO DIFFS
3) REQUIRED annotation (§4.2.8 必/可/条件 vs proto 尾注释) : req diffs: 0
4) CONTIGUITY : non-contiguous=NONE; 214=38+77+99 True; composite=66 True; grand total=280 True
5) ENUMS : set MISSING=[] EXTRA=[]; MsgType(4.2.3)+UNSET=41 == proto 41; TOTAL ENUM VALUE DIFFS: 0
6) §4.2.8.5 conditional rows : 15
==================== SUMMARY: TOTAL BLOCKING DIFFS: 0 ====================
```

**四类差集全空**（消息集 / 字段三元组〔名↔号↔类型↔repeated〕/ 必填性 / 枚举逐值）；`214 = 38+77+99`、复合 **66**、**28 枚举**（`MsgType` 41 = `UNSET` + 40 冻结码）、字段号 `1..n` **连续无跳号**。**据 `te-verify-proto` 回执（引述）**：其独立脚本得同一结论（消息集 40+10 / 三元组 214+66=280 / 枚举 28 逐值 / 必填性 15 条件），本席**独立复跑一致**。

## T032PROTO.2 `PbPayloadCodec` 元协议 9 条往返

- **本席实测**（`/vproto2/probe_meta.cc`，真实链接 `libhg_hgcp.a`）：元协议 **9 条**消息逐字段往返 = **18 检查 / 0 失败**（`MsgHello` 7 字段〔含 `token_version`〕、`MsgHelloAck` 6、`MsgAuthFail` 2、`MsgHeartbeat` 3、`MsgHeartbeatAck` 2、`MsgAck` 5、`MsgError` 6、`MsgResumeReq` 3、`MsgResumeData` 4〔含 `repeated bytes frames` 2 元素〕）。
- **据 `te-verify-proto` 回执（引述）**：前序自建探针 **84 检查 / 0 失败** 证元协议 9 条往返（本席未复跑该 84 检查脚本；以本席 18 检查独立复证其**方向**）。

## T032PROTO.3 `O-1` 延伸：线上 `MSG_ERROR.err_code`（**解码线上 body 实测**）

两条**均解码线上 wire body**（`PbPayloadCodec::decode(err.body)`，非看注释）：

- **`0x0107` ERR_NOT_HANDSHAKEN**（握手前发 `CMD_PLACE_ORDER`）：`probe_meta.cc` ⇒ 线上收到 `MSG_ERROR`，`MsgError.err_code = 0x0107`、连接随后关闭。**6 检查 / 0 失败**。
- **`0x0308` ERR_FRAME_TOO_LARGE**（`LEN > 1 MiB`）：`probe_i2.cc` ⇒ 线上 `MsgError.err_code = 0x0308`、`err_msg = "frame too large (LEN > 1 MiB); use fragmentation (ARCHITECTURE.md 4.2.2)"`（**非空**）、连接随后关闭。

⇒ `O-1`「拒 `0x0107` 仅帧级、线上 body 为空」的**旧限制已闭合**（与 `TASKS.md` `T-032` 登记一致）。**据 `te-verify-proto` 回执（引述）**：其判 `0x0107` 线上码已补齐（`test_not_handshaken_rejected` 增断言）。

## T032PROTO.4 门禁（**本席在隔离副本实跑**）

- 构建 `-Wall -Wextra -Werror`：**零告警 / 零错误**（两轨皆然；`grep -ci warning`=`0`）。
- `ctest`：**13/13（无 CTP）** · **14/14（有 CTP）**〔`-DCTP_SDK_ROOT=/home/ctpmini/ctpmini_admin/ctp_sdk` → `CTP_FOUND=ON`；多出者 = `rsk02.ctp_header_cxx11_compat`〕。**两态按 O-1 口径并列**。
- `bash tools/arch-check/arch-check.sh`：**聚合退出码 0**；`E1`/`E2`/`E3`/`E4`/`E5`/`E6` **各 0**。
- **`E4` 实扫 `N=6` 非 SKIP**：扫描根 = `cpp/src/{order_engine,risk_engine,risk_state}` 并集，`待扫描文件: 6 个`。
- 注：`E6` 需 `web/` 在场方为实扫（本席已 `rsync` `web/` 入副本 ⇒ `E6` 实扫通过；缺 `web/` 时按设计 `SKIP`=0）。

## T032PROTO.5 验收 6：`cpp/proto_smoke` **真的从 `proto/hgcp.proto` 生成**

- `cpp/proto_smoke/CMakeLists.txt §3b`：`add_custom_command` 以 `protoc --proto_path=<repo>/proto --cpp_out=generated_real <repo>/proto/hgcp.proto`；`hgcp_proto_smoke` 目标**直接编入** `generated_real/hgcp.pb.{h,cc}`（`add_dependencies ... hgcp_real_proto_gen`）⇒ 真实 schema 生成码**受同一 `-Wall -Wextra -Werror` 约束**。
- **本席实测**：`generated_real/hgcp.pb.h` 含真实消息类（`class MsgHelloAck`/`CmdPlaceOrder`/`EvtTradingAlert`…）；`nm -C hgcp_proto_smoke | grep -c "hgcp::v1::MsgHello"` = **56**；产物 md5 `hgcp.pb.h`=`559b63c3dca6d14c9667a7b8bca54e3c`、`hgcp.pb.cc`=`33876934dc516477795224a1252e1fb9`。
- 三模式输出：`--print-config` → `RESULT: PASS`（`__cplusplus=201103`/`is_cxx11=YES`/`-std=c++11 -Wall -Wextra -Werror`）；`--empty-roundtrip` → `RESULT: PASS`；`--fields-roundtrip` → `RESULT: PASS`（payload 51 字节）。

## T032PROTO.6 自设变异 + 复位

**（甲）本席新增 3 例（隔离副本内，**打 `I-2` 收口**，每例后复位并重跑）**：

| 变异 | 位点 | 内容 | 结果 | 承重输出 |
|---|---|---|---|---|
| `P2-M1` | `server_loop.cc` | 帧超限分支 `if (st == kFrameTooLarge)` → `if (false && …)`（**删回码**） | **红** | `[FAIL] A: got a reply frame on the wire`（probe fail=1） |
| `P2-M2` | `pb_transport_policy.cc` | `set_err_code(0x0308u)` → `0x0104u`（**回错码**） | **红** | 线上 `err_code = 0x0104` ⇒ `[FAIL] A: err_code == 0x0308` |
| `P2-M3` | `server_loop.cc` | 帧超限分支 `if (st == kFrameTooLarge)` → `if (true)`（**顺手都回码**） | **红** | `[FAIL] B/C/D/E: no error frame`（坏 MAGIC/坏 VER/`RESERVED`≠0/坏 CRC 均被误回码，fail=4） |

**（乙）本席独立复现 `M5` 特征化观察（**关键覆盖缺口**）**：在隔离副本把 `proto/hgcp.proto` 的 `MsgError.rule_id` 字段号 **`4 → 7`**（**合法 proto、但不符 `§4.2.8`**）⇒ 重编译后 **`ctest` 仍 13/13 全绿**；而**本席机械差集立捕**：`DIFF: MsgError.rule_id: NUM arch=4 proto=7` + `non-contiguous: ['MsgError']`（**2 项差异**）。⇒ **测试套件完全不锚定字段号**；字段号不可回收 ⇒ 当前**唯一**保证字段号正确的机制是「这一次的人工机械化差集」，**非持续性**。**据 `te-verify-proto` 回执（引述）**：其亦以 `rule_id 4→7 ⇒ 14 用例全绿` 报同一缺口（`O-2`）。

**（丙）复位证明**：`cp *.orig` 回原文件 ⇒ `md5sum` 逐字节复原（`server_loop.cc`=`28ffa4d7748bcfc8189a7a364b795629`、`pb_transport_policy.cc`=`f3e82a1a73ec64a82d93116de5c1e73c`、`proto/hgcp.proto`=`860e401d…`）；残余扫描 `grep -rn "false && st == FrameStatus|if (true) {|set_err_code(0x0104u)|rule_id *= *7;" cpp/src proto` = **(none)**；**全量 clean 重编译**（`rm -rf build-iso` 重配）⇒ **13/13、0 告警**，`probe_i2` **ALL PASS**。**共享源树零瞬时变异态**（变异一律在 `/vproto2/iso` 内）。**据 `te-verify-proto` 回执（引述）**：其 5 例变异（**含 M1/M2 打在 `.proto` → 生成码链路**）全杀 + 复位证明。

## T032PROTO.7 `I-2` 行为面 + `MSG_RESUME_DATA` 残余（**只核事实、不判缺陷**）

**`I-2` 行为面（本席线上实测，见 `.3`）**：帧超限（声明 `LEN > 1 MiB`）⇒ **回 `MSG_ERROR{err_code=0x0308, err_msg 非空}` 且随后关闭连接**（**成立**）；**其余帧内错误**（坏 MAGIC / 坏 VER / `RESERVED`≠0 / 坏 CRC）⇒ **直接关闭、不回任何帧**（**成立**，与 `§4.3.2` 各码处置一致，**未顺手都回码** —— 由 `P2-M3` 反向承重）。**`CH-014` 相关（「不中断连接」例外化）归架构席，本席不判**；`docs/ARCHITECTURE.md` 现行已含 `§4.2.8.8` `I-2` 行为面订正注记 + 已获用户批准登记。

**「废弃表述」实测（与派单不符，以实测为准）**：派单称全仓 `grep「无法知道该跳过多少字节」类废弃表述 = 0`。**本席实测**：**literal 命中 3 处文档行**（`docs/TASKS.md:742`、`docs/ARCHITECTURE.md:1245`、`docs/REQUIREMENTS/INDEX.md:668`；`cpp/**`、`proto/**`、`tools/**`、`java/**` = **0**）。**但 3 处性质一致 = 均为「引述该旧表述并随即废弃/更正」的留痕**（如 `INDEX.md:668` 原文「⚠️ 前序曾以『无法知道该跳过多少字节』为论据 —— 该表述不成立、已废弃」；`ARCHITECTURE.md:1245` 为「前提更正（供评审…）：结论成立、表述不精确」）。⇒ **作为「活论据」= 0（成立）；作为「literal 串」= 3（文档自述「全仓 `grep` = 0」字面不确、宜作「无活论据」解）**。**本席不判缺陷**（`CH-014` 项归架构席），仅记事实。

**`MSG_RESUME_DATA` 残余（不变）**：`server_loop.cc` `send_resume(...)`（`:185–189`）仍为「批内原始帧字节**顺序拼接**」，**未**按 `MsgResumeData{from_seq,to_seq,is_last,frames}` 编码（该编码 `PbPayloadCodec` 已支持）⇒ **跨侧（`T-042`）格式须对齐**，属残余、待续做。**本席不判缺陷**（跨侧归属）。

## T032PROTO.8 并发写者时序事实 + 结论适用面声明

- **ARCH 时序**（本席实测）：`v1.5.15` `c38fca06…`/3216 → `v1.5.16` `0c12832c…`/3237（12:10 快照）→ **作业期连写**（12:11~12:12 每 10s 变，3237→3239）→ **稳定** `8d1d6830…`/3243（12:15:46 起）。
- **`cpp/src` 时序**（引述 + 本席复测）：派单述前值 `5b0d6b21…`（旧快照，已废）；**本席全程实测恒 `97524bde…`/54**，未再变动。
- **结论适用面声明**：`§4.2.8` 的**转录依据表（`.2–.7` 表体）**在 `v1.5.15`/`v1.5.16` 逐字节相同（`9d640c30…`/139）；现行 `8d1d6830…` 仅增 `§4.2.8.7` **散文注记 +2 行**（`ComponentRole` 例外），**schema 四类差集仍全空** ⇒ **本批转录结论对「现行 ARCH」依旧成立**。**锚点以实时 `md5sum` 为准**。

## T032PROTO.9 度量与「纯追加」证明

- **追加前基线**：`docs/TEST_RESULT.md` = **`a13f3aa36aed20cc189722771e91d9c2` / 2682 行**（`12:24:54`~`12:25:04` 三次取样不变 ⇒ 已静默）。
- **纯追加口径**：追加后 `head -2682 docs/TEST_RESULT.md | md5sum` = **`a13f3aa36aed20cc189722771e91d9c2`**（与追加前**逐字节相同** ⇒ 前 2682 行未被改写）；本单**仅追加、未改写任何既有章**。
- 追加后**行数 / 新章落点 / 全文 `md5` 一律以实时 `md5sum` 为准**（本行数字回填会再次改变全文 `md5`）。
- **写权核对**：本单**仅**写 `docs/TEST_RESULT.md`；其余 `docs/**`、`tools/**`、`java/**`、`cpp/**`、`proto/**` 全程只读。

## T032PROTO.10 未决 / 不适用项

- **未决（不阻断本批转录结论）**：① `MSG_RESUME_DATA` 未按 `MsgResumeData` 编码（跨侧 `T-042`）；② `O-2` 字段号漂移**测试测不出**（唯一机制 = 人工机械差集，**非持续**）—— 编排者拟立 `T-121`（`§4.2.8` ↔ `proto/hgcp.proto` 一致性判据脚本，CI 可跑）；③ 「全仓 `grep`=0」字面与实测（3 处留痕）之差（见 `.7`）。
- **不适用（非本单）**：`TEST_PLAN/NONFUNCTIONAL.md` / `BOUNDARY.md` 用例执行；Java / 前端单测实扫（本单为 `#cpp` 转录批独立复核，仅跑 `arch-check` 聚合 + C++ ctest）。
- **不判**：`CH-014`（「不中断连接」例外化）归架构席；`MSG_RESUME_DATA` 格式归属跨侧。
- **无阻断缺陷**（本批**0 阻断**；`§4.2.8` 转录「逐字一致」结论：**通过**）。

---

# `T-121` 独立复核证据（2026-09-22）

> **复核人**：`te-verify-t121`（测试工程师，独立复核者）
> **结论**：**通过**（**0 阻断缺陷**；4 项建议级观察，见 `T121.10`）
> **写权**：**仅** `docs/TEST_RESULT.md`（本章纯追加）；`tools/**`、`docs/**`（除本章）、`cpp/**`、`java/**`、`proto/**` 全程**只读**。**所有变异一律在隔离副本 `/hgt121iso` 内做**，**共享源树零变异、零残留**。

## T121.0 快照 / 两态锚点比对

**被复核制品（`tools/arch-check/**` @ `2026-09-22 13:49` 实测；活跃期多次变动，一律「以实时 `md5sum` 为准」）**：

| 制品 | 行 | md5 |
|---|---|---|
| `tools/arch-check/scan_hgcp_schema.py`（`E7` 本体） | 807 | `8c15eb4144489bd57a7ad4b3c5e891fa` |
| `tools/arch-check/arch-check.sh`（接入） | 138 | `5b691dc2202a7b6164ff2ecae46ddd13` |
| `tools/arch-check/README.md` | 321 | `dd55255c87a5b44120c2b1aeb1ab474d` |
| 目录内容级（`cd tools && find arch-check -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum`） | — | `863054bdfa54568bebf102c15b6f379b` |
| 被判制品 `docs/ARCHITECTURE.md` | 3245 | `de1c08ca8963e674cb9e63bdacbd8d90` |
| 被判制品 `proto/hgcp.proto` | 801 | `860e401d01225f7532bbf7ec7acf291f` |

**两态（提交态 vs live）——产出方自报值 = HEAD `772bd27` 提交态**（`git show HEAD:<path>` 逐字节复算，连自报目录锚 `e733dc2e…` 亦精确复现）；工作树另有**未提交润色**：

| 制品 | 自报（=HEAD `772bd27`） | 本席实测 live（复核期） | 一致? |
|---|---|---|---|
| `scan_hgcp_schema.py` | 792 / `fba92acd0f8abd920fd553a15fd202f9` | 807 / `8c15eb41…`（复核中经 `7191f64c…`/803 过渡） | ❌（提交态≠live） |
| `arch-check.sh` | 138 / `5b691dc2202a7b6164ff2ecae46ddd13` | 同左 | ✅ |
| `README.md` | 289 / `33e5d3e2914e0e61c6943520bfdf7d76` | 321 / `dd55255c…`（复核中经 `f294daef…`/299、`ae887597…`/318 过渡） | ❌ |
| 目录内容级锚 | `e733dc2ee401a73ad31c41b58fa8da1b`（=HEAD 态） | `863054bd…` | ❌（因上两文件被改） |

> **本条判为「有效发现、由编排者处置」（`O-1`）**：自报值未写错，只是**报的是提交态**；根因在编排侧给出提交态锚点。**两态行为已各测、逐字节等价**（`fba92acd…`/792 正例 `rc 0`、`O-2` 漂移 `rc 1`）。

## T121.1 正例（实扫）

`python3 tools/arch-check/scan_hgcp_schema.py` ⇒ **`rc 0`**，**确为实扫（非 SKIP）**：

```
E7 通过：§4.2.8 与 proto 逐条一致（消息 40+10、字段 214+66、枚举 28）（退出码 0）
  · ARCH 顶层消息 40 条 / 复合 10 条 / 枚举 28 个；proto message 50 个 / enum 28 个
  · 字段合计：顶层 214 + 复合 66 = 280
  · §4.2.8.5 `条件` 字段 15 项（清单行 15 行）
```

**关键数字独立复算（不采信脚本自报）**：40 顶层 = `§4.2.8.2`/`.3`/`.4` 表体 9+17+14 行；10 复合 = `§4.2.8.6` 十行；28 枚举 = `§4.2.8.7` 二十八行；字段 `38+77+99=214`、复合 `7+4+8+10+16+5+2+4+4+6=66`；`§4.2.8.5` 条件 15 行；proto `grep -c '^message '`=50 / `'^enum '`=28。**全部吻合**。

## T121.2 六类判据 × 自设反例（**未复用产出方任一例**）

| 类 | 反例（自设） | rc | 原始输出（节选） |
|---|---|---|---|
| ②号 | `MsgHeartbeatAck` 号 `1↔2` 互换（**合法且仍 `1..n`** ⇒ 孤立 ②） | 1 | ``[②] `MsgHeartbeatAck.echo_ts_ms` 字段号：ARCH 1 ≠ proto 2`` |
| ②号 | **`O-2` 原型**：proto `MsgError.rule_id` `4→7` | 1 | ``[②] `MsgError.rule_id` 字段号：ARCH 4 ≠ proto 7`` |
| ②型 | `MsgHeartbeatAck.echo_ts_ms` `int64→uint32` | 1 | ``[②] … 类型：ARCH `int64` ≠ proto `uint32``` |
| ②repeated | `CmdLoadRiskParams.varieties` 去 `repeated` | 1 | ``[②] … 类型：ARCH `repeated VarietySpec` ≠ proto `VarietySpec``` |
| ②名 | `MsgHelloAck.reason_code→reason_code_x` | 1 | ``[②] `MsgHelloAck` 第 6 字段名：ARCH `reason_code` ≠ proto `reason_code_x``` |
| ③ | `MsgHeartbeatAck.server_ts_ms` `必→可` | 1 | ``[③] … 必填性：ARCH `必` ≠ proto `可``` |
| ③ | `MsgHelloAck.accepted` 删必填性尾注释 | 1 | ``[③] … proto 字段缺必填性尾注释（无法与 §4.2.8 `必` 比对）`` |
| ④集合 | `enum Direction`→`enum DirectionX` | 1 | ``[④] 枚举集合不一致：仅 ARCH 有 ['Direction']；仅 proto 有 ['DirectionX']`` |
| ④逐值 | `ServerState.REPLAYING` 值 `2→5` | 1 | ``[④] 枚举 `ServerState` **取值**不一致：ARCH [0,1,2,3,4] ≠ proto [0,1,5,3,4]`` |
| ④前缀剥离 | `SERVER_STATE_NOT_READY→SERVER_STATE_NOTREADY` | 1 | ``[④] … 逐值不一致：… ('SERVER_STATE_NOT_READY',1) ≠ ('SERVER_STATE_NOTREADY',1)`` |
| ⑤小计 | `§4.2.8.2` 小计 `38→39` | 1 | ``[⑤] 4.2.8.2：小计行 39 ≠ 实算 38`` |
| ⑤声明 | `§4.2.8.1`「顶层字段号总量」`214→215` | 1 | ``[⑤] §4.2.8.1 声明顶层总量 215 ≠ 214`` |
| ⑤标题条数 | `§4.2.8.2` 标题「9 条」→「10 条」 | 1 | ``[⑤] 4.2.8.2：标题声明 10 条 ≠ 实际消息行 9 条`` |
| ⑤连续性 | ARCH 侧 `MsgError.rule_id` `4→7` | 1 | ``[⑤] 顶层 MsgError：字段号非 `1..n` 连续 ⇒ [1,2,3,5,6,7]``（+②同报） |
| ①集合 | proto `message MsgAck→MsgAckRenamed` | 1 | ``[①] 消息集不一致：仅 ARCH 有 ['MsgAck']；仅 proto 有 ['MsgAckRenamed']`` |
| ①顺序 | proto 交换 `MsgHeartbeatAck`/`MsgAck` 两块（集合不变） | 1 | ``[①] 消息**声明顺序**不一致：ARCH [… 'MsgHeartbeatAck'] / proto [… 'MsgAck']`` |
| ⑥派生 | proto `message MsgHello→MsgHelloZ` | 1 | ``[⑥] §4.2.3 `MSG_HELLO` 派生 `MsgHello`，proto 无此 message`` |

**六类逐类均「真的会判红」——无一为空转。**

## T121.3 未解析行（须 fail、非静默通过）—— 6 形态全 `rc 2`

| # | 注入形态 | rc | 输出 |
|---|---|---|---|
| U1 | proto 体内 `optional string unknown_x = 30;` | 2 | `proto 行 348（message MsgHeartbeatAck）：字段形态未解析` |
| U2 | proto 体内 `map<string,int32> m = 9;` | 2 | 同上 |
| U3 | proto 体内 `oneof sel { string a = 9; }` | 2 | 同上 |
| U4 | proto 顶层 `option java_package = "x";` | 2 | `proto 行 33：顶层结构未解析` |
| U5 | ARCH `§4.2.8.2` 首列非序号（`3x`） | 2 | `4.2.8.2 行 1094：表体行形态未解析（首列非序号）⇒ '3x'` |
| U6 | ARCH 字段条目缺字段号（`` `echo_ts_ms`(int64·必) ``） | 2 | `4.2.8.2 行 1096：字段条目形态未解析` |

**退出码语义**：显式 `--proto /no/such` ⇒ `2`；显式 `--arch /no/such` ⇒ `2`；未知参数 ⇒ `2`；**默认路径不存在（空目录执行）⇒ `0` + `SKIP`**。（「未解析行 ⇒ `2`」= 编排者裁定维持：语义为「判据未能对该行执行」的工具/前置错误，与「已解析但不符」的 `1` 分层正确。）

## T121.4 判据设计两处易错点 —— 专门证伪

1. **锚定口径（真按 `^##### 4.2.8.x` 章节锚点）**：在副本内把 `§4.2.8.1` 标题**前插 5 行**、把 `§4.2.3` 标题**前插 5 行**（含行号位移）⇒ **仍 `rc 0`**（`A1`/`A2`）。⇒ 不因行号位移误报，口径成立。
2. **`ComponentRole` 白名单非过宽**：白名单 = `ENUM_PREFIX_EXCEPTIONS = {"ComponentRole": ("ROLE_JAVA","ROLE_CPP")}`（按枚举名索引，结构上只放一例）。**双向实证**：`ROLE_JAVA→COMPONENT_ROLE_ROLE_JAVA` ⇒ `rc 1`（承重）；**另一个**枚举 `Direction` 去前缀值 `BUY` ⇒ `rc 1`（**未放过别枚举**）。

## T121.5 参与面 / 不参与面 —— 逐面注入探针（**13 项，含 `count` 唯一性纪律**）

> 纪律：每条探针先测目标串 `count`；`count != 1` 即禁用文本定位（改用 line-indexed）。`§4.2.8.2` 标题等串在**附录 C 同形副本**里会二次命中 ⇒ 已用带 `##### ` 前缀/长上下文确保 `count=1`（下表 `count` 列全部 = 1）。

**参与面（声称覆盖 ⇒ 须红 `rc 1`）7 项全 OK**：

| 面 | 锚点/行 | 注入 | rc |
|---|---|---|---|
| `P1` `§4.2.8.1` 顶层字段号总量行 | `:1078` | `= 214`→`= 215` | 1 |
| `P2` `§4.2.8.2` 表体字段号 | `:1096` | `echo_ts_ms` `1→2` | 1 |
| `P3` `§4.2.8.3` 标题「`N` 条」 | `:1103` | `17 条→16 条` | 1 |
| `P5` `§4.2.8.5` 表体消息名 | `:1154` | `MsgHelloAck`→`MsgHelloAckX` | 1 |
| `P6` `§4.2.8.6` 表体字段数列 | `:1176` | `7→6` | 1 |
| `P7` `§4.2.8.7` 表体枚举值 | `:1205` | `ScopeType.TOTAL_ACCOUNT` `2→3` | 1 |
| `P8` `§4.2.3` 表体类型码 | `:796` | `MSG_HELLO` `0x0001→0x000A` | 1 |

**不参与面（声称不覆盖 ⇒ 须不红 `rc 0`）6 项全 OK**：

| 面 | 锚点/行 | 注入 | rc |
|---|---|---|---|
| `Q1` `§4.2.8.1` 其余行 | `:1081` | 「必填性语义」行改文本 | 0 |
| `Q2` `>` 引用块 | `:1088` | `> 记法：` 改文本 | 0 |
| `Q4` `§4.2.8.9` 定稿表 | `:1262` | `G-3` 行改文本 | 0 |
| `Q5` 附录 C | `:3061` | `C.3.1` `9 条→8 条` | 0 |
| `Q6` `§4.2.4` | `:850` | 标题改文本 | 0 |
| `Q7/N1` `§4.2.8.5` 条件列语义 | `:1154` | `true→FALSE` | 0 |

⇒ **未发现「声称覆盖却不红」（空转）或「声称不覆盖却红」（强度虚高）的情形**。（唯一相关的 `N5`/`option` 措辞问题见 `T121.10` `O-4`，**已由 `tl-t121` 于复核期内补修**。）

## T121.6 不覆盖项抽查（4 项）+ `N8` 影响面

改之**均仍 `rc 0`**（证其确实不判）：`N1` `§4.2.8.5` 条件语义文本；`N2` `§4.2.8.8` 裁定表文本；`N7` `§4.2.4`；`N8` **附录 C `C.3.1` 标题条数**。

> **`N8` 是已披露缺口（如实披露 = 属实），非缺陷；此处记其影响面**：附录 C（`C.3.1`–`C.3.5`）确含与 `§4.2.8` **近乎同形的表格副本**（实证：`§4.2.8.2` 的 `**小计** | **38**` 与 `C.3.1` 同串、`MsgAuthFail` 行同形——本席按裸串定位探针时即被「count=2」撞到）。`E7` **只锚 `^##### 4.2.8.x`、不扫附录 C**，故「`§4.2.8` 已改、附录 C 未同步」**不被拦下**。**影响面**：附录 C 系 `CH-013` **提案留痕、两侧均标「非权威」**⇒ 无门禁价值；**唯一风险** = 未来若有人误将 `C.3.x` 当权威 schema 读取，会读到**落后于 `§4.2.8` 的值**（附录 C 行仍停在无 `·必` 的提案态）。架构席已在 `C.3` 头注补「可能滞后」指针。

## T121.7 外围件（聚合 / 接线 / README）

1. **聚合入口**：`bash tools/arch-check/arch-check.sh` ⇒ **`rc 0`；`通过(0)=7 命中(1)=0 用法/前置错误(2)=0`；E1..E7 逐条 `0`**（`E7` 实扫；`E4` 实扫 `待扫描文件: 20 个`）。**`E1`–`E6` 六脚本相对 HEAD 逐字节未动**（`git diff --stat <7 脚本>` ⇒ 空）；`arch-check.sh` 相对 HEAD 亦空（=`5b691dc2…`）。
2. **接线**：`cpp/CMakeLists.txt:75-78`（`add_test(NAME arch_check COMMAND bash "${HG_REPO_ROOT}/tools/arch-check/arch-check.sh")` + `FIXTURES_SETUP hg_arch_check`）与 `java/pom.xml:138-151`（`exec-maven-plugin` `exec:exec` 绑 `validate`，唯一 `<argument>tools/arch-check/arch-check.sh</argument>`）**均只调聚合入口、不枚举脚本** ⇒ **新增 `E7` 两侧零改动**。
3. **README**：`E7` 节 + **`N1`–`N8` 显式** + 「参与面（精确）」+ `ComponentRole` 白名单 **均显式**，无含糊带过。

## T121.8 聚合失败传播（M0–M3）

| 场景 | 注入（隔离副本） | 整体退出码 |
|---|---|---|
| M0 基线 | 无 | **0**（`通过(0)=7`） |
| M1 `E7` 内容漂移 | proto `MsgError.rule_id` `4→7` | **1**（`命中(1)=1`；`E7 退出码 1`） |
| M2 `E7` 形态未解析 | proto 体内 `map<string,int32> m = 9;` | **2**（`用法/前置错误(2)=1`；`E7 退出码 2`） |
| M3 复位复跑 | — | **0** |

## T121.9 零污染

- **`T-121` 提交改动面 = `tools/arch-check/{scan_hgcp_schema.py, arch-check.sh, README.md}` 3 文件**（`git show --stat 772bd27`）⇒ `cpp/** java/** proto/** docs/**` 无一由 `T-121` 改动。
- `proto/` 工作树零改动；共享树**无变异残留**（`find tools proto java cpp docs -name '*.orig' -o -name '*MUTATION*'` 空）。
- **非本任务并发改动**（复核期观测，供归因）：`cpp/src/risk_state/**`（`be-t034`）、`java/**/dao/PageQuery*.java`（`be-t096`）、`docs/ARCHITECTURE.md`（`tl-arch-e7`，`§3.0.2` `六大→七大` + `E7` 行）、`docs/BOARD.md`/`docs/TASKS.md`（编排/PM）。

## T121.10 未执行项 / 不适用项（据实）

- **未执行 `mvn validate` / `ctest` 端到端实跑**：本单只读 + 本机并发 C++/Java 构建（`uptime` load 2–3 > 1.5）。替代证据 = 接线**文本核到**（见 `T121.7`）+ **直接实跑聚合入口**（`T121.1`/`T121.8`）覆盖其全部行为。⇒ 属「未执行」，非「通过」。
- **未对 `E1`–`E6` 判据自身做变异**：非 `T-121` 范围；已核其脚本逐字节未动。
- `N4`（冻结不变量）系「另立任务」（现载 `T-122`）；`N6`（线格式编码）与本脚本无交集。

## T121.11 缺陷 / 观察

**无阻断缺陷**（0 项达缺陷门槛：无 FR/架构违背、无功能影响、无契约不符）。**4 项建议级观察**：

| 编号 | 观察 | 处置（编排者裁定） |
|---|---|---|
| `O-1` | 自报锚点 = HEAD 提交态 ≠ live（见 `T121.0`） | 有效发现，由编排者统一提交承接 |
| `O-2` | `cpp/CMakeLists.txt:70` / `java/pom.xml:129` 注释仍写「聚合 E1–E6」 | 不判缺陷；立 `T-123` 承接 |
| `O-3` | `README.md` 「2026-09-22 实测」块写 `E4 待扫描文件: 6 个`，实测 `20 个` | 须修，转 `tl-t121` |
| `O-4` | 脚本头 `N5` 曾把 `option` 归入「不判（仅登记）」，实测 `option java_package=…;` ⇒ 退 `2`（与「已知取舍」自相矛盾） | 须修；**复核期内 `tl-t121` 已补修**（现行 `N5` 已明载 `option`/`import` ⇒ 退 `2`，见 `8c15eb41…`/807） |

**总评**：`E7` 六类判据**全量机械比对、六类均非空转**；退出码 `0/1/2` 分层与自报一致；未解析行**必 fail、不静默**（正是立项要消灭的失效模式）；聚合 `7/7`、`E1`–`E6` 零改动、接线两侧零改动；零污染。⇒ **`通过`**（0 阻断缺陷）。

# `T-034` 独立复核证据（2026-09-22）

**复核方**：`test-engineer` 席位（`te-verify-t034`） · **立场**：独立取证，**不复用产出方结论/数字/用例**（产出方一切数值均视为待证命题）。
**被复核对象**：`T-034`「实现风控内存状态 `risk_state`」 · 产出方 `be-t034`。
**方法**：隔离副本（`/root/t034v`，布局 `<root>/{docs,tools,proto,java,cpp,web}`）+ 自建探针 A–E + ASAN/UBSAN + TSAN + 承重变异；**共享源树只读、零改动、开局扫描无 `MUTATION`/`*.orig`/`*.bak`/`*.rej` 残留**。

## T034.0 快照与时点

**冻结锚点**（内容级口径：`find cpp/src -type f | LC_ALL=C sort | xargs md5sum | md5sum`）：

| 项 | 独立实测（2026-09-22T05:56:11Z） | 产出方自述 | 判定 |
|---|---|---|---|
| `cpp/src` 树锚 md5 | `22dc22656fad5cdf5b6872bfcd0c8641` | `22dc22656fad5cdf5b6872bfcd0c8641` | 符合 |
| `cpp/src` 文件数 | 68 | 68 | 符合 |
| `risk_state` 文件数 | 17（= 3 骨架 + 14 新） | 14 新 + `CMakeLists.txt` 改 | 符合 |
| 最大 mtime | 2026-09-22 13:29:15 +0800 = **05:29:15Z** | ≤ 2026-09-22T05:29:15Z | 符合 |

> 说明：`risk_state` 的 `include/hg/risk_state/module.h`、`src/module.cc`（mtime `2026-09-21 17:30`）为 `T-031` 骨架；`CMakeLists.txt` 为**改**（`git status` 记 ` M`），其余 14 文件为**新**（`??`）。故「14 新」成立。

`risk_state` 逐文件（`md5 / 行 / 相对路径`，cwd = 仓库根）：

| md5 | 行 | 文件 |
|---|---|---|
| `83f8e477add632e6dbddc35415e0a62d` | 52 | `CMakeLists.txt` |
| `b4bbc4015b1d104153d308f8232fb7ff` | 146 | `include/hg/risk_state/load_batch.h` |
| `2fbaa101ff918807c391363fa80b9300` | 284 | `include/hg/risk_state/model.h` |
| `c12f0ed46a5952ee513ef37026e356e2` | 15 | `include/hg/risk_state/module.h` |
| `32f45c693e4337acfdc54d05ae517bd7` | 185 | `include/hg/risk_state/risk_snapshot.h` |
| `a167ca2942d89cfe5e930db7888e5cec` | 78 | `include/hg/risk_state/risk_state.h` |
| `44734057758cb8b109fd7a6b94d5709c` | 149 | `include/hg/risk_state/snapshot_manager.h` |
| `cf63bf19da532c851a62bedfdafa8622` | 131 | `src/load_batch.cc` |
| `fed66ff8e6e336e5ca3f7edfed869b98` | 10 | `src/module.cc` |
| `3510c50f4ec4c953010dac3d09ef646e` | 343 | `src/risk_snapshot.cc` |
| `a420aaa2c18670c69cc2cb8200e2c741` | 74 | `src/risk_state.cc` |
| `2282bb98b821095c580ad7608f2f833a` | 80 | `src/snapshot_manager.cc` |
| `374b2edfe7fc1d7be93ab37e15886c40` | 303 | `tests/data_model_test.cc` |
| `07b9f84c7136bfcecc425d7c94020af1` | 179 | `tests/risk_state_test.cc` |
| `3292af3105cece4afeb828e9819963a2` | 49 | `tests/risk_state_test_util.h` |
| `6e3f624ad5f2398add882cee2d63f2c9` | 136 | `tests/rsk04_conservative_test.cc` |
| `e925dfc2e918f5cd11c5a59385cd2cb6` | 143 | `tests/snapshot_manager_test.cc` |

## T034.1 验收标准 1 — 常驻内存数据集齐备

**权威源**：`docs/ARCHITECTURE.md` **§4.4.1**（按 `^#+` 锚点求段，未用固定行号——活跃制品行号会漂移）。

**独立实测**：§4.4.1 表列 **13 类**，逐类核 `RiskSnapshot` 载体（`include/hg/risk_state/risk_snapshot.h`）与访问器：

| # | §4.4.1 类 | 载体字段 | 访问器 | 有/无 |
|---|---|---|---|---|
| 1 | `VarietyMeta` | `varieties_` | `findVariety` / `varieties()` | 有 |
| 2 | `InstrumentMeta` | `instruments_` | `findInstrument` | 有 |
| 3 | `SubVarietyWhitelist` | `whitelist_` + `whitelist_category_override_` | `isWhitelisted` / `whitelistCategoryOverride` | 有 |
| 4 | `QuotaState`（三层） | `quota_sub_product_` / `quota_sub_instrument_` / `quota_account_product_` | `findQuota(layer,…)` | 有 |
| 5 | `PositionState` | `positions_` | `findPosition` | 有 |
| 6 | `PositionLimit` | `position_limits_` | `findPositionLimit` | 有 |
| 7 | `ExposureState`（三分量 + `snapshot_time` + `snapshot_id`） | `exposures_`（`ExposureEntry` 含 `contract_unexecuted_qty`/`inventory_qty`/`production_plan_qty`/`exposure_qty`/`unit_convert_factor`/`snapshot_time_ms`/`snapshot_id`） | `findExposure` | 有 |
| 8 | `AnnualScaleFactor` | `annual_scales_` | `findAnnualScale` | 有 |
| 9 | `SubAccountCategory`（含品种级覆盖） | `sub_account_category_` + `sub_product_category_` | `effectiveCategory` | 有 |
| 10 | `PermissionSnapshot` | `permissions_` | `findPermission` / `canTrade` | 有 |
| 11 | `Thresholds` | `thresholds_` | `threshold*` | 有 |
| 12 | `TotalLimits` | `total_limit_by_product_` + `account_total_limit_` | `findTotalLimitByProduct` / `accountTotalLimit` | 有 |
| 13 | `PlanState` | `plans_` | `plans()` | 有 |

**13/13 全有载体，无缺类**；验收点名的 7 项（白名单/三层额度/持仓上限/敞口三分量 `+snapshot_time`/权限/阈值/年度规模因子）亦逐项命中。另核 `LoadBatch::applyTo`（`src/load_batch.cc`）对 13 类**逐类覆盖**，且 `RiskSnapshot::countRecords()` 的类集合与 `applyTo` 一致。

**产出方自述**：承载 13 类（另 `data_model_test.cc` 全 13 类满载）。**独立实测一致**（该单测 70 checks / 0 failures，见 `T034.5`）。

**判定**：✅ **通过**。

## T034.2 验收标准 2 — 不可变快照 + 原子指针替换（RCU）

**独立实测（代码级）**：
- **不可变**：读取接口全 `const`；写入接口（`varieties()` 等非 `const`）仅供构建期；拷贝构造/赋值 `= delete`（`risk_snapshot.h:178–179`）。
- **原子读**：`snapshot()` → `mgr_.peek()` → `published_.load(acquire)`（`snapshot_manager.h:58–60`）。
- **原子替换**：`rebuild()` 构建 `next`（全量 `new` / 增量 `peek().clone()`）→ `checkInvariants()` → `publish(next)`；`publish()` 用 `published_.exchange(seq_cst)` **单指针原子替换**（`snapshot_manager.cc:37`）；校验失败/版本倒退 ⇒ `delete next` 且**不发布**（`risk_state.cc:32–38,53–60`）。
- **回收**：`active_` 原子读者计数 + `retired_` 纪元宽限（`epoch < cur` 且 `active_==0`）。

**自建探针 E（`probe_publish_gate`）**：5 类校验失败（版本倒退 / 负额度 / 持仓不自洽 / 红线越界 / 缺换算系数）均**未发布**——快照指针 `&st.snapshot()` 与版本保持 v5 不变；同版本幂等重放允许；增量语义保留旧数据。**failures=0 → PASS**。
**自建探针 B（`probe_tearing`，注册式 `ReadGuard`）**：写者发布 2000 版，读者 **71289 次** 读取，`param_version` / `threshold["marker"]` / `variety.convert_factor` 三者**零撕裂（mismatches=0）**、`badptr=0`，终态版本 2000。

**产出方自述**：`snapshot() 原子读`、`rebuild() 原子替换`、`snapshot_manager_test` 418 checks。**独立实测一致**。

**判定**：✅ **通过**。

## T034.3 验收标准 3 — 交易路径零 DB I/O / 零阻塞 / 零锁（E4）

**独立实测**（`tools/arch-check/scan_lock_free.sh`，隔离副本与共享树**各跑一次，结果同**）：

| 命令 | 待扫描文件 N | 命中 | WARN | rc |
|---|---|---|---|---|
| `scan_lock_free.sh --root cpp/src/risk_state` | **16** | 0 | 3 | **0** |
| `scan_lock_free.sh`（默认并集根 `order_engine+risk_engine+risk_state`） | **20** | 0 | 3 | **0** |

**3 处 WARN 逐条核实**（全为 `new`，属**未覆盖判据 C「无动态分配」**的信息性提示，**不判红**）：
- `src/risk_snapshot.cc:231` `new RiskSnapshot()`（`clone()`，仅重建线程调用）
- `src/risk_state.cc:22` `mgr_(new RiskSnapshot())`（构造函数）
- `src/risk_state.cc:41` `new RiskSnapshot()`（`rebuild` 构建新快照）

> 口径澄清：产出方自述 `N=20` 系**默认并集根**（我实测 N=20 rc=0）；单模块 `--root cpp/src/risk_state` 为 **16**。两口径**不可混引**。E4 覆盖 A（锁原语）+ B（同步 I/O 调用形态），**不覆盖** C（动态分配）/ D（`compare_exchange` 循环 / 异常路径）——由代码审查兜底（脚本头已声明）。

**聚合门禁** `arch-check.sh`：**E1–E7 全 7 判据 rc=0**（E4 rc=0）。

**判定**：✅ **通过**。

## T034.4 验收标准 4 — 快照替换期保守方向（§9 `RSK-04`）+ 反向误拒

**权威源**：`ARCHITECTURE.md` §9 `RSK-04`（方向：① 上限**调低** ⇒ 更易拒、**不会超限放行**；② 上限**调高** ⇒ **不立即生效**）。

**独立实测 —— 自建探针 A（`probe_rsk04_scan`，36917 组穷扫描）**：
- ① **误拒反例扫描**：对 `used/req/limit` 三网格（步长 7/11/3），凡 `used+req <= limit`（未超限）者，**任何** limit 取值均**未**判「超限」⇒ `viol_false=0`。即 `exceedsLimit` 关于 limit **单调不增**，**不存在误拒**。
- ② **单调不增**：`limit1 <= limit2` 且 `exceeds(L2)` ⇒ 必 `exceeds(L1)`，`viol_mono=0`。
- ③ **调低更易拒反例对**：`rej_pairs_ok=551`（旧上限放行 / 新上限拒绝成对成立）。
- **边界**：`exceedsLimit` 用严格 `>`（`model.h:259–261`）⇒ `used+req == limit` **不放行**、`< limit` 放行（卡线组已含）。

**独立实测 —— 探针 E 补充**：校验失败不发布 ⇒ 「上限调高」在发布前不生效（旧快照按旧上限判定），**不提前放行**。

**产出方自述**：`rsk04_conservative_test` 24 checks（调低/调高/单调）。**独立实测一致**，且本席扫描面（36917 组 / 三网格）**大于**产出方 6 值枚举。

**判定**：✅ **通过**（双向均查：既不超限放行、也无误拒）。

## T034.5 构建与测试门禁

**独立实测**（隔离副本 `/root/t034v` → 构建 `/root/t034-build`，`Release`）：
- 配置 `cmake -S cpp -B /root/t034-build -DCMAKE_BUILD_TYPE=Release` → rc=0（系统 protobuf 3.21.12；无 CTP SDK ⇒ `CTP_FOUND=OFF`）。
- 构建 `cmake --build … -j2` → **rc=0**；`grep -icE 'warning:'` = **0**，`grep -icE 'error:'` = **0**。
- **编译参数**（`compile_commands.json`；5 个 `src/*.cc` + 4 个 `tests/*.cc` **全部**）：`-std=c++11 -Wall -Wextra -Werror` ✅。
- **`ctest`**：**17/17 通过**（名单以 `ctest -N` 为准，非 `grep -c` 数宏）：

| # | 测试 | # | 测试 |
|---|---|---|---|
| 1 | `arch_check` | 10 | `hgcp.frame_test` |
| 2 | `rsk01.cxx11_standard_in_effect` | 11 | `hgcp.envelope_test` |
| 3 | `rsk01.empty_message_roundtrip` | 12 | `hgcp.crypto_auth_test` |
| 4 | `rsk01.fields_message_roundtrip` | 13 | `hgcp.timing_test` |
| 5 | `hg.skeleton_modules` | 14 | `hgcp.resume_buffer_test` |
| 6 | `risk_state.data_model_test` | 15 | `hgcp.endpoint_test` |
| 7 | `risk_state.snapshot_manager_test` | 16 | `hgcp.session_test` |
| 8 | `risk_state.risk_state_test` | 17 | `hgcp.transport_test` |
| 9 | `risk_state.rsk04_conservative_test` | | |

- **`risk_state` 四单测实际 checks（以可执行输出为准）**：`data_model_test` 70 / `snapshot_manager_test` 418 / `risk_state_test` 39 / `rsk04_conservative_test` 24 = **551 checks, 0 failures**。

**产出方自述**：`ctest 17/17`、`551 checks`、构建 0 告警。**独立实测逐项一致**。

**判定**：✅ **通过**。

## T034.6 ASAN/UBSAN 与承重变异

**独立实测基线**（`/root/t034-asan-src` → `/root/t034-asan-build`，`-fsanitize=address,undefined`）：
- `snapshot_manager_test` **418/0 → PASS**、`risk_state_test` **39/0 → PASS**，**ASAN/UBSAN 零报错**。
- 自建探针 C（`probe_peek_uaf`）：未注册读跨发布边界 ⇒ ASAN `heap-use-after-free`（见 `T034.7 O-2`）。

**变异表**（先测目标串 `count`=1，唯一；按**行号**注入 `awk` line-indexed）：

| 变异 | 目标 | 注入前 `count` | 变体后结果 | 复位后 | 隔离路径 |
|---|---|---|---|---|---|
| `MUT1` 破坏读者守卫 | `snapshot_manager.cc:26` `active_.fetch_add(1,seq_cst);` → `;` | 1 | **ASAN heap-use-after-free**（`risk_snapshot.h:35 in param_version()`）rc=1 | clean 重建后 418/0 PASS | `/root/t034-asan-src` → `/root/t034-asan-build` |
| `MUT2` 去纪元宽限 | `snapshot_manager.cc:58` `epoch < cur` → `<= cur` | 1 | **断言失败** `snapshot_manager_test.cc:117`（`reclaimed_count==0`）rc=1 | clean 重建后 418/0 PASS | 同上 |

> **纪律留痕（作废一次）**：`MUT2` 前两次注入因 `awk` 语法错误致 `build rc=2`（**未编译成功**），其后 `run` 用的是**陈旧二进制**——该次「红」**作废**；改用合法语法重跑、`build rc=0` 方计入。两变异复位后 `snapshot_manager.cc` 的 `md5` 均与原始一致（`2282bb98…`）并 **clean 重编译** 再报基线（418/0 PASS）。

**结论**：`T-034` 的两道 RCU 保护（① 读者登记 ② 纪元宽限）**均承重**——移除后 ASAN 或断言立即转红。

## T034.7 探索性发现

**O-2（观察·低）未注册读 `snapshot()` 的 UAF 窗口**：`RiskState::snapshot()` = `peek()` 返回引用且**不登记读者**；若调用方**跨越发布边界**使用该引用，写者第二轮 `publish()` 即可回收其对象 ⇒ **ASAN 复现 heap-use-after-free**（`probe_peek_uaf`，`risk_snapshot.h:35`）。
- **判定**：此为 **RCU 设计的固有约束**且**已文档化**（`snapshot_manager.h:56–57`「须在下一个消息边界内用完」；`ARCHITECTURE.md` §4.4.3「用纪元计数或'下一个消息边界'作为宽限点」）。**非缺陷**；§3.4 的 T4 读路径按消息边界消费，跨重建场景有安全替代 `acquire()`。登记为**建议**（审查清单显式约束 `snapshot()` 的使用窗口）。

**O-1（观察·低）`last_result()` 跨线程读的数据竞争**（产出方已披露「写者静止后读取」）：
- **独立实测核实**：TSAN（探针置于 `-O0` 以阻优化器提升）报 **data race** —— `RiskState::last_result()` 读 `risk_state.h:63` vs `rebuild()` 写 `risk_state.cc:36`（`last_` 非原子）。**产出方披露属实**。
- **判定**：属**已文档化的使用约束**（`risk_state.h:15–16`），不在交易热路径（热路径为 `snapshot()` / `acquire()`），**非阻断缺陷**。

**O-3（观察·低）`stats()` 跨线程读的同类竞争（产出方未披露）**：
- **独立实测**：TSAN 报 data race —— `stats()` 读 `retired_.size()`（`snapshot_manager.cc:74`）vs `publish()` 的 `retired_.push_back`（`:44`）（3 个 WARNING 块同一位置）；`retired_` 头注释声明「**写者独占**」。
- **判定**：与 `O-1` 同类（可观测性接口，非热路径、非判定），**非阻断**；建议 `retired_pending` 改原子计数或声明「仅写者线程可读」。

**O-5（观察·信息）E4 的 N 两口径**：单模块 `--root cpp/src/risk_state`=16 vs 默认并集根=20，非缺陷，引用时须注明根。

## T034.8 缺陷 / 观察汇总

**无阻断缺陷**（0 项达缺陷门槛：无 FR/架构违背、无功能影响、无契约不符）。

| 编号 | 级别 | 内容 | 处置建议 |
|---|---|---|---|
| `O-1` | 建议 | `last_result()` 跨线程读竞争（**产出方已披露**） | 维持文档约束，或 `last_` 原子化 |
| `O-2` | 建议 | `snapshot()`(peek) 跨发布边界 UAF（**已文档化**） | 维持设计；审查清单显式约束 |
| `O-3` | 建议 | `stats()`/`retired_` 跨线程读竞争（**产出方未披露**） | 建议 `retired_pending` 原子化 |
| `O-5` | 信息 | E4 N 两口径（16 / 20） | 引用时注明根 |

## T034.9 覆盖核对与结论

- **验收 1**（13 类数据集）：13/13 有载体 ✅
- **验收 2**（不可变 + RCU 原子替换）：✅（不可变 + 原子读写 + 校验失败不发布）
- **验收 3**（E4 零锁 / 零 I/O）：✅ rc=0（N=16 单模块 / 20 默认根，WARN 3=`new`）
- **验收 4**（`RSK-04` 双向 + 误拒）：✅（36917 组扫描 0 误拒 / 0 单调反例）
- **门禁**：构建 0 告警、`ctest` 17/17、551 checks、`arch-check` E1–E7 全 0
- **承重**：读者守卫 / 纪元宽限移除后均转红（ASAN / 断言）
- **共识核对**：产出方自述的每一项（锚 md5 / 68 文件 / 14 新 / mtime / ctest 17/17 / 551 checks / E4 N=20 命中 0 WARN 3=new / ASAN UAF）**均独立复现一致**，**无「自述与实测不符」项**。

**结论**：**`通过`**（0 阻断缺陷；3 项建议级观察，其中 `O-3` 系产出方未披露项）。

**纯追加证明**：
- 追加前基线：`md5 8e914198993fc679b4a6adb67f936a4b` / 2959 行
- 校验：`head -2959 docs/TEST_RESULT.md | md5sum` = `8e914198993fc679b4a6adb67f936a4b`（**与追加前基线一致 ⇒ 纯追加**）
- 追加后全文 md5 / 行数：**以实时 `md5sum docs/TEST_RESULT.md` / `wc -l` 为准**（自指值不可写死）

# `T-096` 独立复核证据（2026-09-22）

复核方：`te-verify-t096`（独立复核，**非产出方**）。对象 = `T-096` **追加授权交付**（分页装配层保证 `ORDER BY` 全序 / 默认路径稳定 + 多键逐键白名单）。**产出方自报一律视为待证命题**；本报告分「**独立实测**」与「**产出方自述（我未复现）**」两栏。

## T096.0 快照 / 锚点与自报逐项比对

| 项 | 产出方自报 | 独立实测 | 结论 |
|---|---|---|---|
| 仓库版本 | —（未给） | `git rev-parse HEAD` = `64711b551af48aeec1f0de4b3fd8ab9fc70a6c2d`（master，提交=`feat(java): T-096 补充…`），`git status` 干净 | ✅ |
| 冻结点锚 | `find java/src -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` = `d71105d257ca82a297f0450ca470ea46` / 320 文件 | **实测一致**（3 次取样均 `d71105d257ca82a297f0450ca470ea46` / 320） | ✅ |
| 共享树变异残留 | `MUTATION`/`.orig` 残留 0 | `find .`（除 `.git`）`*.orig`/`*.bak`/`*.rej`/`*MUTATION*` **0 命中** | ✅ |
| 门禁计数 | 464/0/0/0 | **实测 464/0/0/0** + `BUILD SUCCESS`（隔离副本） | ✅ |
| `@Test` vs surefire | 464 == 464 | **实测 464 == 464**（裸 grep 与「行首注解」两法各 464） | ✅ |
| `arch-check` 聚合 | rc 0（E1–E7 全 0） | 共享树实测 **rc 0**（`通过(0)=7 命中(0)=0 用法/前置(2)=0`） | ✅ |

隔离副本 `/t096iso/{docs,java,tools,proto}`（缺 `cpp/`、`web/` ⇒ 副本内 E1–E4/E6 走 SKIP；**共享树**（含 `cpp/`、`web/`）实测 E1–E7 全 0，非 SKIP）。`TMPDIR=/root/t096-tmp`；`-T1`。

## T096.1 断言① 默认路径现在是全序

**独立实测**（自建探针 `T096DaoProbeTest`，**未复用产出方用例**；真实 `DmTradePlanDao` + 自建记录型假 JDBC + 自建假排序引擎）：
- 不传 `sort`（`PageQuery.of("1","10",null,"exec_deadline")`）经 `findPageScoped` 产出真实 SQL 的 `ORDER BY` 子句 = **`exec_deadline ASC, id ASC`**。
- 15 条同 `exec_deadline`、`size=10`、两页等值组内序取**相反方向**（模型化 DB 对未覆盖键不保证次序）⇒ 两页并集 = **15**、去重 = **15**（无重复 / 漏行）。
- 对照（自建）：与「修复前装配点」等价的单键子句 `exec_deadline ASC` ⇒ 同数据集两页并集仅 **10**（重复 `[1..5]`、漏 `[11..15]`），复现被修缺陷形状。

**产出方自述（我未复现）**：默认路径 SQL = `exec_deadline ASC, id ASC`、并集 15 / 去重 15（与其 `DmTradePlanStableOrderTest` 一致）。

结论：✅ **成立**。

## T096.2 断言② tie-break 真的是「唯一列」（正确性前提，重点）

- **独立穷举**：`grep -rln "extends AbstractDmDao"` = **43 个子类**；`grep -rn "protected String idColumn()"` 亦 **43**，逐类核 `MISSING` = **空**（**无未实现**）。
- 43 子类 `idColumn()` **全部 `return "id"`**；非子类 `DmConfigChangeLogDao` 用自带 `ID_COLUMN="id"`。DAO 表集合 = **44**。
- **唯一性核验（DDL 层）**：`docs/DEPLOY/schema/*.sql` 共 **47 张 `CREATE TABLE`**，**47 张全部 `PRIMARY KEY (id…)`**；44 张 DAO 表**全部包含**（`comm` 差集：DAO 表 ∉ schema = **0**）。
- 三处 `findPageScoped` 覆写（`DmProductDao` / `DmHedgePlanDao` / `DmTradePlanDao`）**均委托** `abstract.findPage` / `super.findPageScoped` ⇒ **无绕开 tie-break 的私有装配点**。
- **无** 子类返回非唯一列 / 无 `id` 列的表 ⇒ 追加后**不产生非法 SQL**，且**确为全序**。

结论：✅ **成立**（产出方「43 子类全实现、均返 `id`」独立证实）。

## T096.3 断言③ 不重复追加

**独立实测**（探针 `noDoubleAppend`）：`id,desc` → `id DESC`；`exec_deadline,asc,id,asc` → `exec_deadline ASC, id ASC`；`id,asc,exec_deadline,desc` → `id ASC, exec_deadline DESC`（唯一列非末键亦视为已含，不追加）。✅

**观察（低）**：「已含唯一列」判定为**大小写敏感** `sortFields.contains(uniqueColumn)`。经白名单服务路径不可达（白名单键均小写，大写先被拒）；非白名单读取路径（如 `ConfigService` 各列表直通 DAO）若客户端传大写 `ID`，会产冗余键 `ID ASC, id ASC`（若 DB 视 `ID`/`id` 同列则冗余但合法，**无功能影响**）。见 `O-4`。

## T096.4 断言④ 边界

**独立实测**（探针 `boundarySortInputs`）：
- `sort` = `null` / `""` ⇒ 走默认字段：`exec_deadline ASC, id ASC`；
- 仅字段名无方向 ⇒ 用缺省方向（4 参 `ASC`；5 参 `desc` ⇒ `exec_deadline DESC, id ASC`）；
- 畸形串（`exec_deadline,,id` / `a,b,c` / `exec_deadline,evil` / `,asc`）⇒ **确定性抛 `IllegalArgumentException`**；边界层 `RequestDispatcher` 捕获、`ErrorMapper` 映射为 `PARAM_INVALID`（`10001`）⇒ **4xx，非 500**。✅

## T096.5 断言⑤ 多键逐键白名单 + 归属

**独立实测**（探针 `T096WhitelistProbeTest`，3 个调用点 `listTradePlans` / `listPlans` / `listProducts`）：
- **第 2 键**越域（`…,asc,evil_column,asc`）⇒ `10001` 且 `dao.calls==0`（不触达 DAO）✅（3 点皆然）；
- **第 3 键**越域（`…,asc,id,asc,evil_column,desc`）⇒ `10001` 且 `dao.calls==0` ✅（3 点皆然）；
- **反向**：全部键合法（如 `product_code,asc,status,desc,category,asc`）⇒ **放行并触达 DAO**（`calls==1`）✅（不误拒）。
- 穷举：`src/main` 中 `query.sortField()` / `query.direction()` 单键白名单检查**残留 0**；三处白名单（`PlanService:1431` / `:1586`、`VarietyService:212`）**全部**改走 `requireSortFieldsAllowed`。

**归属判定独立复核**（「多键支持是否本批引入」）：
- `git show 772bd27:…/PageQuery.java` **确为单键**（仅 `parts[0]`/`parts[1]`，第 3+ 片段静默忽略）✅（产出方依据**属实**）。
- **加强证据**：`git log -- …/PageQuery.java` 仅两笔 —— 基线快照 `59b6f70` 与本次提交 `64711b5`；`59b6f70`、`461d39a`（本次提交**父**）**均单键**，multi-key **首现于本次提交 `64711b5`**。
- **补给前快照**（`T-034` 作业期留存，非本次提交）：`/root/t034v`（13:23）、`/root/t034-iso`（13:30）、`/root/t034-asan-src`（13:48）—— `PageQuery` **已是 multi-key**，而 `PlanService:1431`/`:1590`、`VarietyService:212` **仍为 `contains(query.sortField())`（仅首键）** ⇒ 「multi-key 能力」与「仅校验首键缺口」**同源于 `T-096`**。
- 结论：**「本批引入 ⇒ 本批闭环」成立** ✅。⚠️ 唯「前一交付」措辞无法由 git 单独证实（multi-key 首现即在受审提交内）——不影响结论，记 `O-6`。

## T096.6 断言⑥ 既有断言订正核对

**独立实测**：
- `PageQueryTest`：补给前快照 `/root/t034v/…/PageQueryTest.java` **确含旧用例** `singleKeyOrderByCanDuplicateAcrossPages`（断言 `union.size() > set.size()` 且 `set.size() < data.size()` = **锁定缺陷形状**）；现行树为 `singleKeyCompletedToTotalOrder`（断言补全后全序并集 = 20）。**旧→新 已独立复现** ✅。
- `DmTradePlanDaoScopedSqlTest:83`：断言**确为 `contains`（子串）** `select.sql.contains("ORDER BY exec_deadline ASC")` ✅。**守卫强度事实**：加 tie-break 后 `…, id ASC` 仍「包含」该子串 ⇒ 仍绿；反之**移除 tie-break 亦不会变红** ⇒ 该断言**对 `ORDER BY` 形状已无守卫能力**（产出方已披露）。
- **穷举 test 目录 ORDER BY 相关断言**（`grep -rn "ORDER BY" java/src/test/java`），全量 5 类 ——
  1. `DmTradePlanStableOrderTest`（**新文件**，精确串 `"exec_deadline ASC, id ASC"`，**强守卫**）；
  2. `DmTradePlanDaoScopedSqlTest:83`（**子串，弱**，已披露）；
  3. `DmProductDaoScopedSqlTest:79`（**子串，弱**，**产出方未披露**）；
  4. `DmProductDaoScopedSqlTest:148`（同上，**弱**，**产出方未披露**）；
  5. `DmDialectTest:28–29`（`dialect.paginate` 输入串，**与本机制无关** ✅ 证实）。
  ⇒ **会因加 tie-break 而需订正的「精确串」恰 3 处**（产出方口径成立）；但**失去 tie-break 守卫能力的子串断言实为 3 处**（产出方仅披露 1 处）⇒ 见 `O-2`。
- `DmTradePlanStableOrderTest` 旧两例（`singleKeyOrderByDuplicatesAndMissesRows` / `defaultOrderByUnchangedAsSingleKey`）：**无法独立复现** —— 该文件在 git 内为**新增**（`461d39a` 无此文件），且**不在** 13:23–13:48 三份补给前快照中；旧名仅见编排者文档（`TASKS.md` / `BOARD.md`）。记 `O-3`。

## T096.7 反退化（自设变异，隔离副本内；**禁止共享树就地变异**）

| 变异 | 目标（唯一命中） | 变体后 | 复位后 | 副本路径 |
|---|---|---|---|---|
| A：`query.orderByEnsuringUnique(idColumn())` → `query.orderBy()`（`AbstractDmDao:163`） | 默认路径判据 | 探针 **2 红**（`defaultPathProducesTotalOrderClause`、`twoPageUnionCompleteUnderTieBreak`：`expected <exec_deadline ASC, id ASC> but was <exec_deadline ASC>`；并集 10≠15） | **14/0/0 绿** | `/t096iso/…` |
| B：白名单循环退化首键 `query.sortFields()` → `query.sortFields().subList(0,1)`（`PlanService:1644`、`VarietyService:941`） | 逐键白名单判据 | 探针 **6 红**（`tradePlans`/`plans`/`products` 的第 2、第 3 键越域用例：`Expected BizException … nothing was thrown`） | **14/0/0 绿** | `/t096iso/…` |

- 变异前均实测目标串 `count==1`（防「未注入」假绿）；变异仅施加于隔离副本；两轮复位后均 `diff -q` 与共享树**逐字节一致**，且副本 `find java/src … md5sum` 回到冻结锚 `d71105d…`/320。
- **复位后全量 clean 重编译**：`mvn -B -o clean test` = **464/0/0/0** + `BUILD SUCCESS`（证明无残留）。
- 自建探针（14 用例）存档 `/root/t096-probes-saved/`；14/0/0 于冻结源。

## T096.8 误伤面（反向验证）

- **主排序语义不变**：tie-break 仅**末尾追加**（`exec_deadline DESC` → `exec_deadline DESC, id ASC`；首键与方向不变）；`sortField()` / `direction()` / `orderBy()` 仍取或等价首键（探针 `tieBreakOnlyAppendsAndDialectIntact`）。✅
- **方言分页**：`DmDialect.paginate("… ORDER BY exec_deadline ASC, id ASC", q)` 仍正确追加 ` LIMIT ? OFFSET ?`；`DmDialectTest` 全绿。✅
- **判据**：`arch-check` 聚合 **rc 0**；`E5` 单跑 **rc 0**（`dao 之外未发现 SQL`）。✅
- 全量 464/0/0/0 为弱证据补充。

## T096.9 缺陷 / 观察汇总

**无阻断缺陷**（0 项达缺陷门槛：无 FR / 架构违背、无功能影响、无契约不符）。

| 编号 | 级别 | 内容 | 处置建议 |
|---|---|---|---|
| `O-1` | 信息 | `DmTradePlanDaoScopedSqlTest:83` 子串断言对 `ORDER BY` 形状失去守卫能力（**产出方已披露**） | 维持现状；形状守卫由 `DmTradePlanStableOrderTest` 承接 |
| `O-2` | 建议 | **同类未披露**：`DmProductDaoScopedSqlTest:79` / `:148` 亦为子串断言 ⇒ 同样失去 tie-break 守卫能力 | 请编排者裁定是否登记 |
| `O-3` | 信息 | `DmTradePlanStableOrderTest` 旧两例「反转」无法经 VCS / 补给前快照独立复现（git 内新增、快照中缺失、旧名仅见文档） | 属「自述未独立复现」项，非缺陷 |
| `O-4` | 建议·低 | `orderByEnsuringUnique`「已含唯一列」判定大小写敏感；非白名单路径传大写 `ID` 会产冗余键 | 如需可改 `equalsIgnoreCase`（不阻断） |
| `O-5` | 信息 | 副本缺 `cpp/` / `web/` 致 E1–E4/E6 SKIP；共享树实测 E1–E7 全 0（非 SKIP） | 引用时注明环境 |
| `O-6` | 信息 | 「前一交付」措辞无法由 git 单独证实（multi-key 首现即在受审提交内）；「本批引入」结论不受影响 | 措辞层面留痕 |

## T096.10 结论

- **独立实测基线**：`mvn -B -o clean test` = **464/0/0/0**；`@Test` grep **464** == surefire **464**；`arch-check` 聚合 **rc 0**（`E1`–`E7` 全 0）；`E5` 单跑 **rc 0**。
- 断言 ①②③④⑤⑥ 及反向 / 误伤 / 反退化**全部独立成立**；产出方**核心自报逐项复现一致**，**无「自述与实测不符」的阻断项**。
- 归属判定「多键本批引入」**成立**（快照 + git 双重独立证据）。

**结论**：**`通过`**（0 阻断缺陷；4 项信息 / 建议级观察，其中 `O-2` 系产出方未披露项，`O-4` 为低 severity 边界建议）。**「产出方自述未独立复现」仅 `O-3`（订正溯源），不影响交付正确性。** 门禁处置由编排者裁定；本席**不自评 `DONE`**。

**纯追加证明**（自指值不写死）：
- 追加前基线：`md5 1978bbbe93a1ed9a0cd3b50f1fc10202` / **3165 行**
- 校验：`head -3165 docs/TEST_RESULT.md | md5sum` = `1978bbbe93a1ed9a0cd3b50f1fc10202`（与追加前基线一致 ⇒ **纯追加**）
- 追加后全文 md5 / 行数：**以实时 `md5sum docs/TEST_RESULT.md` / `wc -l` 为准**（自指值不可写死）

---

# `T-096` 独立复核 —— 测试工程师**二次独立复算**（2026-09-22）

> **性质**：上文 `T-096` 证据章已由复核方 `te-verify-t096` 落盘。本席（`test-engineer`）**不采信其结论**，在**隔离副本**中**逐条独立重跑承重判据**并与产出方核心自报比对；独立复现**一致**。本节**纯追加，不改上文一字**。
> **纪律**：隔离副本内施加自设变异（**共享树禁止就地变异**）；复位后逐字节核回。

## TE96.0 复核基线（本席实测）

- 仓库 `HEAD` = `6192064`；`git status` 除本文件 `TEST_RESULT.md` 外干净。
- 冻结锚：`find java/src -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`d71105d257ca82a297f0450ca470ea46` / 320 文件**（与产出方自报一致）。
- 隔离副本 `/root/t096-verify-iso`（`java` / `tools` / `proto` / `docs` / `web`；无 `cpp/`；`target/` 已清）；`TMPDIR=/root/t096-verify-tmp`。

## TE96.1 承重判据逐条独立重跑

| # | 判据 | 命令（实际执行） | 实测输出 | 结论 |
|---|------|-----------------|---------|------|
| 1 | 全量回归 | 隔离副本 `mvn -B -o clean test` | `Tests run: 464, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（EXIT=0） | ✅ |
| 2 | `@Test` == surefire | `grep -rn '@Test' java/src/test --include=*.java \| wc -l` | **464** == 464 | ✅ |
| 3 | 架构聚合 | 共享树 `bash tools/arch-check/arch-check.sh` | `通过(0)=7 命中(0)=0 用法/前置(2)=0`，**rc 0**（`E1`–`E7` 全 0，非 SKIP） | ✅ |
| 4 | **默认路径全序**（承重） | 隔离副本 `mvn -B -o test -Dtest=DmTradePlanStableOrderTest` | 4/0/0/0；`defaultPathIsStableAcrossPages` / `defaultOrderByAddsPrimaryKeyTieBreak` 断言 **`exec_deadline ASC, id ASC`** 通过 | ✅ |
| 5 | tie-break 接线穷举 | `grep -rn '" ORDER BY "' java/src/main` | **恰 2 处**：`AbstractDmDao:163`（`orderByEnsuringUnique(idColumn())`）、`DmConfigChangeLogDao:138`（`orderByEnsuringUnique(ID_COLUMN)`）；`\.orderBy()` 残留 **0** | ✅ |
| 6 | PK 可取得（未写死） | `grep -rn "protected String idColumn()" java/src/main -A1` | **43** 子类 / **43** 覆写，**全部 `return "id"`** | ✅ |
| 7 | 多键逐键白名单 | `grep -n sortFields() PlanService.java VarietyService.java` | `PlanService:1431/1586`、`VarietyService:212` 均 `for (field : query.sortFields())`（逐键）；首键单校验残留 0 | ✅ |
| 8 | 断言订正 | `grep -n` 于 `PageQueryTest` / `DmTradePlan*Test` | `singleKeyCompletedToTotalOrder`（原锁定缺陷形状的 `singleKeyOrderByCanDuplicateAcrossPages` 已反转）；`DmTradePlanDaoScopedSqlTest:83` 子串断言**已加注**（`:80–81`） | ✅ |

## TE96.2 反退化（本席自设变异，仅施加于隔离副本）

| 变异 | 目标（唯一命中） | 变体后 | 复位后 |
|---|---|---|---|
| **A**：`AbstractDmDao:163` `query.orderByEnsuringUnique(idColumn())` → `query.orderBy()` | 默认路径全序 | `DmTradePlanStableOrderTest` **2 红**：`defaultPathIsStableAcrossPages` / `defaultOrderByAddsPrimaryKeyTieBreak` —— `expected: <exec_deadline ASC, id ASC> but was: <exec_deadline ASC>` | `diff -rq src <共享树>java/src` **逐字节一致**；clean 全量回 **464/0/0/0** |
| **B**：`PlanService:1644` / `VarietyService:941` `sortFields()` → `sortFields().subList(0,1)` | 多键逐键白名单 | **3 红**：`ProductListIsolationTest.rejectsIllegalSecondSortKey`、`PlanListQueryTest.rejectsIllegalSecondSortKey`、`TradePlanSubAccountIsolationTest.rejectsIllegalSecondSortKeyForTradePlans` —— `nothing was thrown` | 同 A（复位后全量回绿） |

> 变异前均实测目标串唯一（防「未注入」假绿）；两轮复位后副本 `src` 与共享树 `diff -rq` **零差异**。

## TE96.3 归属判定（多键是否本批引入）

- `git log -- java/src/main/java/com/hedgeguard/dao/PageQuery.java` **仅 2 笔**：基线 `59b6f70` + 本批 `64711b5`。
- `git show 772bd27:…/PageQuery.java` 与 `git show 461d39a:…/PageQuery.java` **均单键**（仅解析 `parts[0]` / `parts[1]`，第 3+ 片段无处理）⇒ **多键能力首现于 `64711b5`（= `T-096`）**。
- 结论：**「多键本批引入 ⇒ 本批闭环」成立** ✅。

## TE96.4 本席结论

- **`通过`**：**0 阻断缺陷**。`te-verify-t096` 报告的承重判据（默认路径全序 / 逐键白名单 / 反退化必红 / 归属）与断言订正，**经本席独立重跑逐条复现一致**；未发现「自述与实测不符」的阻断项。
- **承接观察 `O-2`**（产出方未披露项）：`DmProductDaoScopedSqlTest:79` / `:148` 同为**子串断言**、同样失去 tie-break 守卫能力 —— 本席核实**属实**；级别 = **「建议」**（无 `FR` / 架构违背、无功能影响）⇒ **不构成缺陷**，是否登记由编排者裁定。
- **环境说明**：本复核隔离副本缺 `cpp/` / `web/`，副本内 `E1`–`E4`/`E6` 会 `SKIP`；故 `E1`–`E7` 全 0 的结论取自**共享树**（含 `cpp/`、`web/`，实测非 SKIP）。
- 门禁处置与 `DONE` 判定由编排者裁定；本席**不自评 `DONE`**。

**纯追加证明**（自指值不写死）：
- 追加前基线：`md5 582125f8136d727d3e31358b130e1d21` / **3288 行**
- 校验：`head -3288 docs/TEST_RESULT.md | md5sum` = `582125f8136d727d3e31358b130e1d21`（与追加前基线一致 ⇒ **纯追加**）
- 追加后全文 md5 / 行数：**以实时 `md5sum docs/TEST_RESULT.md` / `wc -l` 为准**（自指值不可写死）

---

# `BUG-001` 复检记录（`GET /api/v1/plan` 缺省排序方向）

- **复检人**：测试工程师（`test-engineer`） ｜ **复检时间**：2026-09-22T14:50+08:00
- **复检版本**：`HEAD` = `6192064`；`PlanHandler.java` md5 `eb0961a95a60939b4c992ef65a407b4f`；`PageQuery.java` md5 `1c1d2b8da324f4e1ac7e7045c117b0ef`
- **对应需求 / 用例**：`FR-PLAN-4 @ REQUIREMENTS/PLAN.md:155`；用例 `PlanListDefaultSortTest.defaultSortIsCreatedTimeDesc` / `explicitSortIsRespected`

| 项 | 结果 |
|---|---|
| 原单复现命令 `mvn -B -o test -Dtest=PlanListDefaultSortTest` | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` |
| 缺省路径（不传 `sort`） | `sortField = "created_time"`、**`direction = "DESC"`、`ascending = false`** ✅（原缺陷态 `ASC`/`true` 已消除） |
| 显式 `sort=plan_no,asc` | `plan_no` + `ASC`，缺省方向**不覆盖**显式值 ✅ |
| 反退化（隔离副本内把兜底改回裸字段名） | `defaultSortIsCreatedTimeDesc` **必红**：`FR-PLAN-4 要求默认倒序（BUG-001） ==> expected: <DESC> but was: <ASC>`（与原单修复前实测逐字吻合）⇒ 守卫有效 |
| 关联回归 | `PlanListDefaultSortTest,PlanListQueryTest,TradePlanListQueryTest,PageQueryTest,ProductListDefaultSortTest` = **34/0/0/0** |
| 全量回归 | `mvn -B -o clean test` = **464/0/0/0** + `BUILD SUCCESS`；`arch-check` 聚合 **rc 0** |
| **复检结论** | ✅ **通过 → `CLOSED`**（详见 `docs/BUGS/BUG-001.md`「复检结论」） |
| 遗留问题 | 无（相邻缺口 `Q-1`/`Q-2`/`Q-3` 另派，非本单；根因②由 `T-096` 收口） |

> ⚠️ **版本差异（非回归）**：现行 `PlanHandler.java` md5 `eb0961a9…` 异于原修复记录 `c3e890d6…`，因修复后工作树又经**已验收变更**（如 `T-100` 装配）；`DEFAULT_SORT = "created_time,desc"` 兜底（`:51`/`:139`）仍在位且有效。
> **工单闭环**：`dev-team/inbox/` 与 `outbox/` 内**无 `BUG-001` 工单文件**（本单由开发自测直接建档）⇒ 无移单动作，闭环凭证以缺陷档案为准。

**纯追加证明**（自指值不写死）：
- 追加前基线：`md5 5c60c8b25909dd225d57c3fa6e644400` / **3341 行**
- 校验：`head -3341 docs/TEST_RESULT.md | md5sum` = `5c60c8b25909dd225d57c3fa6e644400`（与追加前基线一致 ⇒ **纯追加**）
- 追加后全文 md5 / 行数：**以实时 `md5sum docs/TEST_RESULT.md` / `wc -l` 为准**（自指值不可写死）

---

# `T-033` 独立复核证据（2026-09-22）

> 复核人：`test-engineer` 席位（实例 `te-verify-t033`），**独立复核方**。
> 立场：产出方（`be-t033`）自报一律视为**待证命题**；本席**独立实测**取证，**未复用**其用例与数字。
> 纪律：变异仅在**隔离副本**内做；未改任何交付制品；未改 `TASKS.md`/`BOARD.md`/`ARCHITECTURE.md`/`DATA_MODEL.md`；未 `git commit`。
> 报告分栏：**「独立实测」**（本席亲手跑出的原始输出） vs **「产出方自述（我未复现）」**。

## T033.0 快照 / 锚点与自报逐项比对

| 项 | 独立实测 | 产出方自述 | 一致 |
|---|---|---|---|
| cwd | `/root/myspace/hedge-guard` | 同 | ✔ |
| HEAD（开工时） | `9221262` | —（未自报） | — |
| HEAD（复核期间被推进） | `5e1bb87`（编排者 ledger 提交，`docs(ledger): 第 46/47 轮 …`） | — | — |
| 冻结点锚 `find cpp/src/ctp_adapter -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` | **`4ab77612bc964a3bcdebc11291cbbfab` / 20 文件 / 2845 行**（开工时与提交后**两次**实测均一致） | `4ab77612bc964a3bcdebc11291cbbfab` / 20 文件 / 2845 行 | ✔ |
| 共享树残留（`*.orig`/`*.bak`/`*.rej`/`*MUTATION*`/`*~`） | **0** | 0 | ✔ |
| 产出面 | `cpp/src/ctp_adapter/**`（20 文件：头 6 + 源 5 + 测 6 + `CMakeLists.txt` + 未改的 `module.h`/`module.cc`） | 同 | ✔ |
| `module.h`/`module.cc` 是否改动 | 未改（`git status` 中二者**不出现**；tracked & clean） | 未动 | ✔ |
| 交付制品是否已提交 | **已提交**（HEAD `5e1bb87` 中 `git ls-files cpp/src/ctp_adapter` = 20 文件，tracked） | 交付时「未 commit」 | ⚠️ 见下注 |

> ⚠️ **注释（树已被推进，非本席改动）**：开工时 `git status` 显示 `ctp_adapter/**` 全为 `??`（untracked），HEAD=`9221262`；本席复核期间 HEAD 推进至 `5e1bb87`（**编排者 ledger 提交**，含 `T-033` 交付 + `T-096` 归档 + 并行席位 `be-t035` 的 `risk_engine` 在制品）。**`ctp_adapter` 内容锚逐字节未变**（`4ab77612…`），故本席两轨构建/测试对其**冻结内容**有效。⇒ 自报「未 commit」在**交付时刻**成立，其后由编排者提交；**非产出方陈述不实**。
> ⚠️ **移动靶**：`cpp/src` 现含并行席位 `be-t035` 的 `risk_engine`（在制品，`ctest` 用例 +12）⇒ **自报的绝对数 `CTP 22/22` / `stub 19/19` 无法在当前树上原样复现**（现为 34/31）；本席以 **`ctp_adapter` 自身用例**为单位核验（见 T033.5）。

### 自报门禁逐项比对

| 自报项 | 自报值 | 独立实测 | 一致 |
|---|---|---|---|
| 两轨构建 rc | 0 / 0 | **CTP rc 0**；**stub rc 0** | ✔ |
| 两轨 `warning:`/`error:` | 0/0 | **0/0**（两轨） | ✔ |
| `ctp_adapter` TU 数（CTP/stub） | 9 / 5 | **9 / 5**（`Building CXX object src/ctp_adapter/…`） | ✔ |
| 编译参数 | `-std=c++11 -Wall -Wextra -Werror` | 逐 TU 实测均带（见 `compile_commands.json`） | ✔ |
| CTP 轨 `-isystem` | `-isystem /home/ctpmini/ctpmini_admin/ctp_sdk` | 实测命中（且**证明必需**，见 T033.6） | ✔ |
| `ctest` 计数 | CTP 22/22（基线 18，+4）/ stub 19/19（基线 17，+2） | 现树 **CTP 34/34**、**stub 31/31**；`ctp_adapter` 注册 **4 / 2** 且全过 | ◑ 结构一致、绝对数因移动靶漂移 |
| 聚合 `arch-check.sh` | `rc 0`，`E1`–`E7` 各 0 | **`rc 0`，`E1`–`E7` 各 0** | ✔ |
| `E4` 默认三目录扫 | 45 文件，`ctp_adapter` 不在其内 | **45 文件**；扫描根 = `cpp/src/{order_engine,risk_engine,risk_state}`，**不含 `ctp_adapter`** | ✔ |
| 隔离副本变异 | 4 例全杀（`/root/t033probe`） | 本席**自设** 3 例（不复用其 M1–M4）：2 杀 + 1 守卫强度（见 T033.7） | ◑ 见 T033.7 |
| SDK 实测类名 | `CThostFtdcUserSpi`/`CThostFtdcUserApi`；`CThostFtdcMdSpi`/`CThostFtdcMdApi`；无 `TraderApi` | **逐条核实一致**（见 T033.1） | ✔ |

## T033.1 验收 1（封装 CTP 合并版 UserApi；登录/登出/连接状态可用）

**独立实测**（读 CTP 头文件 + 源码实现 + 探针驱动）：
- SDK 头文件实测（`/home/ctpmini/ctpmini_admin/ctp_sdk/`）：
  - `ThostFtdcUserApi.h`：`class CThostFtdcUserSpi`@`:42`；`class USER_API_DLL_EXPORT CThostFtdcUserApi`@`:533`；`CreateFtdcUserApi`@`:539`；`GetApiVersion`@`:543`。
  - `ThostFtdcMdApi.h`：`class CThostFtdcMdSpi`@`:25`；`class MD_API_DLL_EXPORT CThostFtdcMdApi`@`:75`；`CreateFtdcMdApi`@`:84`；`GetApiVersion`@`:88`。
  - `libthostuserapi.so` / `libthostmduserapi.so` 存在；`grep -rl 'CThostFtdcTraderSpi\|ThostFtdcTraderApi'` ⇒ **无命中（rc 1）** ⇒ `ThostFtdcTraderApi.h`/`CThostFtdcTraderSpi` **确不存在**。
- 实现：`CtSpiImpl : CThostFtdcUserSpi` / `CtMdSpiImpl : CThostFtdcMdSpi`（`ct_spi_impl.h:33`/`:68`）；真实网关 `CtpTradeGateway`/`CtpMdGateway`（`ct_gateway_ctp.cc:36`/`:128`）；`CreateFtdcUserApi`@`ct_gateway_ctp.cc:44`、`CreateFtdcMdApi`@`:136`。
- `login`/`logout`/`isConnected`（`ct_adapter.cc:91`–`:132`）为**真实实现**（非仅声明）：`login` 先 `trade_->connect`，失败回 `0x2004`；`isConnected()==trade_connected_`，由 `onFrontConnected(false)` 置位。
- 探针实测状态机：`login→{code=OK, isConnected()==false}`（异步）⇒ `onFrontConnected/onLoginReply` ⇒ `isConnected()&&isLoggedIn()` 为真；`logout()` ⇒ 均复位。**40/40 checks 通过**（见 T033.4）。

**产出方自述（我未复现）**：同上口径。

## T033.2 验收 2（四接口可用；异步 SPI 回调只做序列化并投递队列）

**独立实测**：
- 四接口**定义存在**（`ct_adapter.cc`）：`sendOrder`@`:134`、`cancelOrder`@`:159`、`subscribeMarket`@`:176`、`reqFundTransfer`@`:192`；**且被测试触达**（`ct_adapter_test.cc` + 探针）。
- **回调只序列化+入队**——自建证据（探针，独立于产出方夹具）：
  - `onOrderEvent(ev)` ⇒ `orderEventQueue()->size()` **0→1**、`pollOrderEvent` 取回 `trade_id=="T1"`；`onQuoteEvent(q)` ⇒ `pollQuoteEvent` 取回 `instrument_id=="rb2601"`；
  - 回调前后 `trade.inserts` **不变**、`transfers==0` ⇒ **回调未触发任何 CTP 请求**（未把业务逻辑塞进回调）。
  - 另：`ct_spi_test.cc`（真实 SPI 轨）对 `OnRtnTrade`/`OnRtnOrder`/`OnErrRtnOrderInsert`/`OnRspFromBankToFutureByFuture`/`OnRtnDepthMarketData` 逐一断言「队列 +1 且字段一致且无 CTP 请求」。
- **反例（回调里做重活/阻塞）会怎样** ⇒ 见 T033.7 MUT-C：**现有判据拦不下**（单测仍绿、`E4` 默认根不扫 `ctp_adapter`）⇒ 报为**守卫强度事实**（非缺陷）。

**产出方自述（我未复现）**：同上；其 4 例变异含「`OnRtnTrade` 不入队 ⇒ `spi_test` 红」。

## T033.3 验收 3（期货与期权各一条用例）

**独立实测**（两处各一对，且探针复核）：
- `ct_adapter_test.cc`：`futures_and_options_are_both_covered` —— 期货 `rb2601`（`kFutures`）/ 期权 `rb2601C3600`（`kOption`），断言两条均 `kCtOk` 转发、`last_order.market==kOption`、`instrument_id=="rb2601C3600"`、`offset==kCloseToday`。
- `ct_mapping_test.cc`：`futures_order_mapping`（`rb2601`）/ `option_order_mapping_shares_same_structure`（`rb2601C3600`，断言**共用** `CThostFtdcInputOrderField`，类别由 `InstrumentID` 区分）。
- 探针：期货与期权**均转发成功**（`inserts` 1→2），期权 `market/instrument` 原样传递。
- 佐证（真实报单结构）：`ct_mapping.cc:35`–`:73` `toInputOrder` 期货/期权走**同一**映射函数，无类别分支。

**产出方自述（我未复现）**：同。

## T033.4 验收 4（承重）：未连接 ⇒ `CMD_CONFIRM_ORDER` 回 `0x2004` 且指令退回保留、不静默丢弃

**独立实测**（自撰探针 `/root/t033v/probe/probe_te.cc` + 反退化变异）：
- 未连接 ⇒ `sendOrder(合法报单)`：`ack.code == 0x2004`、`ack.retained == true`、`retainedOrderCount()==1`、`trade.inserts==0`（**未发往 CTP**）；`takeRetainedOrder()` 取回**同一条**（`client_order_ref=="REF-A"`、`instrument_id=="rb2601"`），取回后计数归 0 ⇒ **有可取回入口、非静默丢弃**。
- 同口径：`reqFundTransfer` 未连接 ⇒ `0x2004` + `retainedFundCount()==1`（幂等键原样保留）；`cancelOrder` 未连接 ⇒ `0x2004`。
- 加固：已连接但 CTP 发送失败（`rc<0`）⇒ 亦 `0x2004` + `retained`（`ct_adapter.cc:152`–`:155`），探针 `REF-NET` 用例通过。
- **反退化**（本席自设 MUT-A，**非**复用产出方 M1）：删 `ct_adapter.cc` 未连接分支的 `retainOrder(req);` ⇒ `ct_adapter_test` **必红**（4 处 FAIL，`retainedOrderCount()==1`、`takeRetainedOrder` 等），复位后回绿 —— 见 T033.7。
- 码值基准：`0x2004 CTP_NOT_CONNECTED`（`ARCHITECTURE.md` §4.3.2 指令与 CTP 段）；`FR-TRADE-2` 验收 4「CTP 不可用时指令**不被静默丢弃**」。

**产出方自述（我未复现）**：同（含其 M1「去退回保留 ⇒ adapter_test 红」，本席以**独立 MUT-A** 等效复现）。

## T033.5 两轨真实性（有 SDK / 无 SDK 降级）

| 维度 | 独立实测 CTP 轨（`CTP_FOUND=ON`） | 独立实测 stub 轨（`CTP_FOUND=OFF`） |
|---|---|---|
| 构建 rc / `warning:` / `error:` | **0 / 0 / 0** | **0 / 0 / 0** |
| `ctp_adapter` TU | **9**（`module.cc` / `ct_adapter.cc` / `ct_gateway_ctp.cc` / `ct_mapping.cc` / `ct_spi_impl.cc` + 4 测） | **5**（`module.cc` / `ct_adapter.cc` / `ct_gateway_stub.cc` + 2 测） |
| 宏 / include | `-DHG_CTP_FOUND=1`、`-isystem <SDK>` | `-DHG_CTP_FOUND=0`、**无** `-isystem` |
| `ctest` 总数（现树） | **34/34 过** | **31/31 过** |
| `ctp_adapter` 注册用例（`ctest -N`） | **4**：`ct_queues_test`/`ct_adapter_test`/`ct_mapping_test`/`ct_spi_test` | **2**：`ct_queues_test`/`ct_adapter_test`（`ct_mapping_test`/`ct_spi_test` **不注册**——依赖真实 CTP 头/库） |
| `ctp_adapter` 测试二进制 checks | **46+81+59+45 = 231 / 0 fail** | **46+81 = 127 / 0 fail** |
| 差异解释 | 多 `rsk02.ctp_header_cxx11_compat`（`ctp_smoke`）+ `ct_mapping_test` + `ct_spi_test` | 基线少上述三项 |

- **「真降级」核验（重点）**：stub 轨**确实编译并运行被测代码**——`hg_ctp_adapter` 静态库含 `ct_adapter.cc`（SDK 无关适配逻辑）与 `ct_gateway_stub.cc`；`ct_adapter_test` 在 stub 轨**实跑 81 checks**（非跳过）。stub 网关为**真实现**：`connect()` 恒返回 `false` 并给错误串、`req*` 恒 `-1`（`ct_gateway_stub.cc:23`–`:53`）——**未伪造 SDK**、**未把断言跳过**。
- **等价结构**：`ctp_adapter` 在 CTP 轨注册 4 用例、stub 轨 2 用例；两轨差 = `rsk02` + `ct_mapping_test` + `ct_spi_test` = **3** ⇒ 与自报「CTP 比 stub 多 3」结构一致（绝对数 22/19 因移动靶不可原样复现）。

## T033.6 `-isystem` 是否真的生效 / 为何需要

**独立实测**：以 `-I` 替代 `-isystem` 编译 `ct_gateway_ctp.cc`（其余参数相同）⇒ **rc 2**，报 `ThostFtdcMdApi.h:38/-Werror=unused-parameter` 等**第三方头告警放大为错误**；以 `-isystem` 编译 ⇒ **rc 0**。⇒ `-isystem` **既生效且必需**（防 CTP 头告警撞 `-Werror`），口径与 `TASKS.md` §`T-033` 派单硬约束③一致。

## T033.7 变异表（自设，隔离副本 `/root/t033m`；**未复用产出方 M1–M4**）

| 变异 | 目标 | 变体后 rc | 复位后 rc | 副本路径 |
|---|---|---|---|---|
| **MUT-A**：删 `sendOrder` 未连接分支 `retainOrder(req);`（`ct_adapter.cc`） | 验收 4 反退化（退回保留不可静默去掉） | `ct_adapter_test` **FAIL**（`81 checks, 4 failures`，退 **1**） | **PASS**（`81 checks, 0 failures`，退 **0**） | `/root/t033m` |
| **MUT-B**：`QuoteQueue::pushDropOldest` 的 `pop_front()`→`pop_back()`（`ct_queues.h`） | 「满则丢弃最旧」语义 | `ct_queues_test` **FAIL**（`46 checks, 2 failures`，退 **1**） | **PASS**（退 **0**） | `/root/t033m` |
| **MUT-C**（守卫强度探针，**非缺陷**）：`onOrderEvent` 内加 `std::mutex` + `sleep_for(2ms)`（回调做重活/阻塞） | 「回调只序列化+入队」纪律 | 单测 **仍 PASS**（`81 checks, 0 failures`）；`E4` **默认根 rc 0（未拦下）**；仅 `scan_lock_free.sh --root cpp/src/ctp_adapter` **rc 1**（命中 `ct_adapter.cc:248/249` + `ct_queues.h` 既有 6 处 = 9 处） | —（探针后已复位，锚复归 `4ab77612…`） | `/root/t033m` |

- 复位核验：每次变异后 `cp` 回原件，`md5sum` 对共享树**逐字节一致**；三例全复位后 `/root/t033m` 的 `ctp_adapter` 锚 = **`4ab77612bc964a3bcdebc11291cbbfab`**（= 共享树锚）。
- **共享树零污染**：所有变异**仅在 `/root/t033m`**（隔离副本）；共享树残留扫描 **0**。

## T033.8 判据侧：`arch-check` 与 `E4` 扫描根

**独立实测**（隔离副本内 `bash tools/arch-check/arch-check.sh`）：
- 聚合 **`rc 0`**；逐条：`E1=0`、`E2=0`、`E3=0`、`E4=0`、`E5=0`、`E6=0`（`SKIP`，`web/` 未铺开）、`E7=0`。
- **`E4` 扫描根确认不含 `ctp_adapter`**（三重证据）：① 读 `scan_lock_free.sh`：`DEFAULT_ROOTS=(cpp/src/order_engine cpp/src/risk_engine cpp/src/risk_state)`；② 读 `arch-check.sh`：调用判据脚本**不传 `--root`**（用默认根）；③ 实跑输出「扫描根: `cpp/src/order_engine, cpp/src/risk_engine, cpp/src/risk_state`」，**待扫描文件 45 个**。
- 交叉核（**若把 `ctp_adapter` 纳入则立即红**）：`scan_lock_free.sh --root cpp/src/ctp_adapter` ⇒ **rc 1（6 处命中，全在 `ct_queues.h`）** —— 印证架构 `§3.0.2` `E4` 脚注 ⑤「`ctp_adapter` 含 `sendOrder`(T4) 与 T2/T3 回调混合，整目录纳入有误报风险 ⇒ 显式排除」的**披露属实**，且排除面**非空转**。

## T033.9 第 8 项裁定：`quoteQueue` 用互斥体 ——「T4 可达面（`sendOrder`/退回存储）无锁」是否成立

**本席明确判据（定义「T4」与「可达面」）**：`T4` = `ARCHITECTURE.md` §2.4.2 的 `trading`（**单线程交易线程**，交易内存状态唯一写者）；「T4 可达面」= 从 `sendOrder`/`cancelOrder`/`reqFundTransfer`/`takeRetainedOrder`/`retainedOrderCount`/`takeRetainedFund`/`retainedFundCount` 出发**可达**的代码路径。判据 = 该可达面内是否出现 `mutex`/`lock_guard`/`unique_lock`/`condition_variable`。

**独立实测结论：该陈述成立（无锁）**。证据：
- 全模块锁仅出现在 **`ct_queues.h` 的 `QuoteQueue`**（`:103`/`:115`/`:123`/`:130`/`:138`，`#include <mutex>`@`:23`）。
- `ct_adapter.cc`（含上述 T4 可达面全部实现）**零锁**（`grep -nE 'mutex|lock_guard|unique_lock|condition_variable|atomic' ct_adapter.cc` ⇒ 无命中）。
- `quote_events_`（`QuoteQueue` 实例）**仅**由 `onQuoteEvent`（T3）/`pollQuoteEvent`/`quoteQueue()`（T8）访问（`ct_adapter.cc:252`/`:262`/`:266`），**不在** T4 可达面。
- `sendOrder` 路径 = `validOrder` → `retainOrder`（`std::deque::push_back`）→ `trade_->reqOrderInsert`，**无锁原语**。
- 与架构一致：§3.0.2 `E4` 脚注 ⑤ 明载「`ctp_adapter` 中仍执行在 T4 上的少数面（如 `CtAdapter::sendOrder`）不在 `E4` 扫描面内」；§2.4.3「禁止在交易路径使用 `std::mutex`」。
- **提示（非缺陷）**：头注（`ct_adapter.h:26`–`:28`）写「队列为 SPSC 无锁」（对 `orderEventQueue`/`RingBuffer` 成立），紧邻子句已澄清 `quoteQueue` 为 T3/T8、不在 T4 路径 —— 措辞略宽，但同句即勘误，不构成误导。

## T033.10 第 9 项（接口形态风险，报编排者裁定，**本席不判缺陷**）

`sendOrder` 返回形态：**实现 vs 冻结文档并列**如下 ——
- **冻结文档原文**（`ARCHITECTURE.md` §「3.1 C++ 模块 M1：CTP 适配层（`ctp_adapter`）」的「**对外接口**」列表项，按章节锚点定位，非固定行号）：
  > `CtAdapter::sendOrder(const CtOrderReq&)` → `OrderLocalId`
- **实现形态**（`ct_types.h:154`–`:160` + `ct_adapter.cc:134`）：
  > `struct CtOrderAck { CtCode code; std::string client_order_ref; std::string order_local_id; bool retained; };` ⇒ `sendOrder(...) → CtOrderAck`

**是否构成对冻结接口的偏离 = 待裁定**。并列事实：① 字面上，实现**加宽**了 §3.1 的裸 `OrderLocalId`；② 而验收 4（`TASKS.md` §`T-033` 验收 4）+ §4.3.2 `0x2004` 要求「回 `0x2004` 且退回保留」，**裸 `OrderLocalId` 无法承载「错误码 + 退回标志」**（`CtOrderAck.code`/`.retained` 正是为承载之）。⇒ 二者存在张力，**归属技术席**（产出方亦已在 `TASKS.md` 未决项①单列提请）。本席**不自行判缺陷**。

## T033.11 缺陷表

| 编号 | 严重度 | 摘要 | 证据 | 复现步骤 |
|---|---|---|---|---|
| **TE-T033-001** | **中（待裁定）** | `sendOrder` 返回 `CtOrderAck` vs `ARCHITECTURE.md` §3.1 裸 `OrderLocalId` —— **接口形态偏离待技术席裁定**（非本席判缺陷） | `ARCHITECTURE.md` §3.1 原文；`ct_types.h:154`；`ct_adapter.cc:134` | 见 T033.10 |
| **TE-T033-002** | **信息（守卫强度）** | 「回调只做序列化+入队」**无自动判据拦截**：回调内注入 `mutex`+`sleep` 后单测仍绿、`E4` 默认根不扫 `ctp_adapter` ⇒ 仅代码审查兜底 | MUT-C（T033.7） | 在 `onOrderEvent` 加锁+睡眠 → `ct_adapter_test` 仍 PASS、`scan_lock_free.sh`（默认）`rc 0` |
| **TE-T033-003** | **信息** | 若将 `ctp_adapter` 纳入 `E4` 扫描根，则**立即 rc 1**（`QuoteQueue` 互斥体 6 处命中）—— 佐证架构 §3.0.2 ⑤「排除」的必要性 | `scan_lock_free.sh --root cpp/src/ctp_adapter` ⇒ rc 1 | 见 T033.8 末 |
| **TE-T033-004** | **提示（措辞）** | 头注「队列为 SPSC 无锁」对 `quoteQueue` 不成立，但同句已澄清（T3/T8、不在 T4 路径）⇒ 不误导 | `ct_adapter.h:26`–`:28` | 读头注 |

> **阻断级缺陷：0**。以上均为**信息/提示级**或**待裁定级**（`TE-T033-001` 归属技术席）。

## T033.12 结论行

**结论：`有条件通过`。**
- **唯一条件**（归属=**技术席/编排者**）：对 **`TE-T033-001`** 裁定 —— `sendOrder` 返回由 §3.1 裸 `OrderLocalId` 加宽为 `CtOrderAck{code, client_order_ref, order_local_id, retained}` 是否**追认**（若追认，建议同步 `§3.1` 表述；若不追认，须回改实现或补变更单）。
- **两向验证结论**：**该防的已防**（验收 4 承重经独立探针 + 独立反退化 MUT-A 双向证实）；**未见「误伤」**（合法流：在线期货/期权/订阅/撤单/资金划转均正常转发；两轨均真编真跑；未把降级轨伪装成通过）。
- **独立实测基线**：两轨构建 `rc 0`、告警/错误 `0/0`；`ctp_adapter` 注册用例 CTP **4/4**、stub **2/2** 全过（现树总 34/34、31/31）；聚合 `arch-check` **rc 0**（`E1`–`E7` 全 0），`E4` **不含 `ctp_adapter`**；自撰探针 **40 checks / 0 fail（两轨）**；隔离变异 2 杀 + 1 守卫强度；共享树残留 **0**。
- **本席不置 `DONE`**（门禁结论由编排者落）。

**纯追加证明**（自指值不写死）：
- 追加前基线：`md5 dbad2d39078001a8cc1d6705a919d818` / **3368 行**（全文 md5，追加前实测）
- 校验：`head -3368 docs/TEST_RESULT.md | md5sum` = `dbad2d39078001a8cc1d6705a919d818`（与追加前基线一致 ⇒ **纯追加**）
- 追加后全文 md5 / 行数：**以实时 `md5sum docs/TEST_RESULT.md` / `wc -l` 为准**（自指值不可写死）

## T033.13 基线限定勘误（编排者 2026-09-22 追加口径；`CH-015` 待用户裁定）

> 编排者通知：本机在用 SDK 基线**已被推翻**——实为**上期技术（SFIT）`CTP Mini` API V1.7.7**（**非官方 CTP**）。本席据此**独立复核**并为本章相关判定**加限定语**。

**独立实测（核实该基线）**：
- `GetApiVersion()` 实测：`CThostFtdcUserApi::GetApiVersion()` = **`V1.7.7`**、`CThostFtdcMdApi::GetApiVersion()` = **`V1.7.7`**（本席自撰 `version_probe.cc` 链接真实 `.so` 运行）。
- `.so` 标识串：`libthostuserapi.so` / `libthostmduserapi.so` 含 **`V1.7.7`**、**`_sfit_enH`**（SFIT 标记）、`_ZN17CThostFtdcUserApi13GetApiVersionEv` / `_ZN15CThostFtdcMdApi13GetApiVersionEv`。
- `FindCtpMini.cmake` 实测存在：**`/home/ctpmini/ctpmini_admin/cmake/FindCtpMini.cmake`**（位于 `ctpmini_admin` 项目内；`hedge-guard` 仓内未找到 `FindCtp*.cmake`）。
- 目录形态（本机 SDK 的事实）：仅 `ThostFtdcUserApi.h`/`ThostFtdcMdApi.h` + `libthostuserapi.so`/`libthostmduserapi.so`；**无**官方分离式 `ThostFtdcTraderApi.h`/`CThostFtdcTraderSpi`/`libthosttraderapi.so`。

**限定语（覆盖本章相关判定）**：
- 本席在 **T033.1**（封装合并版 UserApi）、**T033.9**（T4 无锁措辞）、**T033.10**（`CtOrderAck` 形态）中凡涉「**与 `ARCHITECTURE.md` §3.1 口径一致**」的判断，**一律以「现行（CTP Mini）基线」为准**，并**显式标注：该基线已被提请变更（`CH-015`，待用户裁定）**。**不**作「符合项目 CTP 口径」的无条件表述 —— 官方 CTP 下 `CThostFtdcUserSpi`/`ThostFtdcUserApi.h` 的形态会变。
- 本席先前引用的「`ThostFtdcUserApi.h` 存在、`CThostFtdcTraderApi.h` 不存在 ⇒ 合并版」论据，即 **T033.1 / T033.0 自报比对表** 中的 SDK 类名实测，**改为「本机 SDK（CTP Mini）的事实」**，**不再**作为「官方 CTP 的事实」引用。
- **T033.10 第 9 项（`CtOrderAck` 形态是否偏离 §3.1）判定照旧**，但同样**附基线限定**（以 CTP Mini 基线为准；`CH-015` 待裁定）。

**对本席结论的影响**：本席对**结构 / 行为 / 边界 / 反退化 / 零污染**的验证（T033.2–T033.8、T033.11）**与所用 SDK 无关**，**全部仍然有效**；仅「SDK 相关」项（T033.1、T033.10 的 §3.1 一致性）须挂上述限定语。⇒ 复核**继续成立**，无需更动 T033.12 结论形态（仍为 `有条件通过`）。

**结论行补充（追加于 T033.12 之上，不改原文）**：
> **本结论的 SDK 基线 = CTP Mini；基线切换后 SDK 相关项须重验。**

---

# `T-035` 独立复核证据（2026-09-22）

> 复核方：**测试工程师（独立复核，非产出方）** ｜ 实例 `te-verify-t035` ｜ 时刻 `2026-09-22T19:41+08:00`
> 立场：`DONE` 判据 = **授权 + 独立验证**；产出方一切自报视为**待证命题**，本席不使用其用例与数字；
> 全文分栏「**独立实测**」与「**产出方自述（我未复现者单独标注）**」。**未修改任何交付制品**；`TASKS.md`/`BOARD.md`/`ARCHITECTURE.md` **零改动**；**不自评 `DONE`**。

## T035.0 快照 / 锚点与自报逐项比对

| 项 | 产出方自述 | 本席独立实测 | 一致 |
|---|---|---|---|
| 冻结点锚（`cwd=仓库根`）`find cpp/src/risk_engine -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` | `fc086003667fe0a890a69358c3a51c56` / 28 文件 | **`fc086003667fe0a890a69358c3a51c56` / 28 文件** | ✅ |
| `git` 版本 | （未给） | `HEAD = 1249ba9`（工作树含多模块未提交改动；`risk_engine/**` 属未提交态） | — |
| 构建 0 warning / 0 error | 是（18 TU 带三开关） | **是**（新构建目录 `/root/b-t035v`；`grep -icE 'warning:'`=0、`'error:'`=0） | ✅ |
| `ctest -R risk_engine` | 13/13 | **13/13**（`rc=0`；名单以 `ctest -N` 为准） | ✅ |
| 聚合 `rc` | 0（`E1`–`E7` 全 0） | **0**（`E1`–`E7` 逐条 0） | ✅ |
| `E4` 实扫 | 45 文件、命中 0 | **45 文件、命中 0**（3 处 `WARN` 全在 `risk_state` 的 `new`，非本模块） | ✅ |
| P99 | 33–83 µs | **33.6–40.5 µs**（5 次独立采样） | ✅ |
| 4 例变异全杀 | 是 | **自设 4 例（不复用其 M1–M4）全部杀**（见 T035.7） | ✅ |

**构建环境（独立）**：`/root/b-t035v`（`cmake 4.2.3` / `g++ 15.2.0` / `RelWithDebInfo` / `-DCTP_USE_FETCH_DEPS=OFF`，系统 protobuf `3.21.12`）；构建目录与 `TMPDIR` 置 `/`；`-j1` 串行（构建前 `uptime` 负载 ≤ 2.1）。隔离副本 `/root/iso-t035v`（含 `docs/cpp/tools/proto/java/web`）。

## T035.1 门禁自证（原始输出摘要）

1. **零告警**：`grep -icE 'warning:' build.log = 0`、`grep -icE 'error:' = 0`（`rc=0`）。
2. **三开关（`compile_commands.json`）**：本模块 **18 个 TU**，逐条断言 `-std=c++11` ∧ `-Wall` ∧ `-Wextra` ∧ `-Werror` —— **缺任一者为 0**。TU 明细：`clock/module/order_request/quota_selection/risk_engine/risk_result`（6 库）+ `bench_risk_check`（1）+ 11 个单测。
3. **`ctest -N -R risk_engine` = 13**：`arch_check`(fixture, #1) + 11 单测（#8–#18）+ `bench_risk_check`(#19)。**`rc=0`，13/13 Passed**（100%）。
4. **聚合**：`bash tools/arch-check/arch-check.sh` ⇒ **`rc 0`**；逐条 `E1=0 E2=0 E3=0 E4=0 E5=0 E6=0 E7=0`；`E4` **「待扫描文件: 45 个」**、命中 0（WARN 3 = `risk_state` 三处 `new`）。
5. **单测断言规模（独立复跑）**：whitelist 16 / quota_layers 31 / selector 14 / position_limit 14 / scale_redline 16 / net_position 14 / term 18 / permission 20 / price_deviation 22 / double_layer 13 / purity 21（**checks 合计 199，全 0 failures**）。
6. **本席未新增 ctest 用例**（复核方不改交付制品）；自撰**只读探针** `/root/probe_t035.cc`（链接共享树静态库；不属仓库）。

## T035.2 验收 1（8 条规则全内存判定）—— 两栏

| 规则 | 独立实测 | 产出方自述 | 判定 |
|---|---|---|---|
| `R-RISK-1` 白名单/合约归属/境内场内 | `evalWhitelist`（`risk_engine.cc:179`）三态齐备；`whitelist_test` 全过 | 同 | ✅ |
| `R-RISK-2` 三层限额 | 见 T035.3；**但平仓未豁免（TE-T035-001）** | 称达标 | ❌（部分） |
| `R-RISK-3` 持仓上限（**平仓不受限**） | `is_open` 豁免在位（`:262`）；探针 `P4` 平仓放行 | 同 | ✅ |
| `R-RISK-4` 规模红线 90%/80%，基数=年度敞口累计实物量 | `evalScale`：`ratio` 按 `effectiveCategory`（商品 90/贸易 80）；分子 `accumulated+inc`，分母 `expected_spot_business_qty`；**子账户 + 全账户双算取严**（`:297–313`）；平仓豁免 | 同 | ✅ |
| `R-RISK-5` 时点净持仓 ≤ 敞口 | `evalNetPosition`（`:319`）同口径折算、标注快照时点、平仓豁免；**缺敞口 fail-open（EXP-T035-003）** | 自列未决⑤ | ✅（含观察） |
| `R-RISK-6` 期限红线三级处置 | `evalTerm`：`allowed=min(建仓+12M, 合同+30d)`；超期未审批拒绝、剩余≤30d 告警、已审批放行、平仓豁免 | 同 | ✅ |
| `R-RISK-7` 权限与授权 | `evalPermission`：未授权/超品种/超额度/风控岗/负责人；**结算岗缺失 = EXP-T035-002** | 称「已核实非缺陷」⑥ | ❌（部分） |
| `R-RISK-8` 价格偏离（昨结算价基准，2%/5%，市价不适用） | `evalPrice`：`prev_settlement_price` 基准；`>5%` 阻断、`(2%,5%]` 告警、≤2% 通过、市价 `price_deviation_na` | 同 | ✅ |
| 全内存 / 零 DB | `E1`=0（不链接 DB 客户端库）、`E2`=0（无 SQL/驱动）；本模块 `ldd` 仅 `libstdc++/gcc_s/c/m` | 同 | ✅ |

**备注「平仓/减仓方向按规则豁免」**：`R-RISK-3/4/5/6` **四规则逐条实测豁免**；**唯 `R-RISK-2` 未豁免** ⇒ 与备注及 `R-RISK-5` 冲突（缺陷 TE-T035-001）。

## T035.3 验收 2（三层限额全序 / 并列取一 / `entries[]` 全命中）

- **全序（层② > 层① > 层③，层③不参与「最细」）**：**引擎端到端独立实测** —— 探针 `P5`（三层同时超）⇒ `code=0x1017 / limit_layer=SUB_INSTRUMENT / entries=3`；`quota_layers_test` 用例④/⑤（三层超→层②；层①+层③→层①）全过。**承重**：变异 M1（交换层序）⇒ 两条端到端断言 + 选择器断言 **必红**（T035.7）。 ✅
- **同层并列取一（占用率最高 → `id` 升序）**：**本席独立证实前提** —— 三层容器类型（`risk_snapshot.h:163–165`）分别为 `unordered_map<uint64_t,QuotaEntry>`（层①键 `packKey2(sub,product)` / 层②键 `packKey2(sub,instrument)`）与 `unordered_map<uint32_t,QuotaEntry>`（层③键 `product`）；`findQuota`（`risk_snapshot.cc:81–98`）每层**单键查找**；`evalQuota`（`risk_engine.cc:215–234`）**每层只查一次** ⇒ `check()` 每层**至多 1 条命中**、`h.id` 恒写 0 ⇒ **「同层并列」分支在引擎端不可达**。该规则**仅由选择器单测**（`risk_engine_selector_test`：占用率高者/交叉相乘/id 升序/frozen 计入）覆盖。
  **判定：防御性代码（合规），不需改判据** —— 判据的并列分支在当前数据模型下**空满足**；`selectFinestQuotaHit`/`quotaHitBetter` 提供了确定性契约，供未来「单层多候选」情形使用。**建议**：在判据/文档处加注「引擎端不可达、由选择器单测承载」，避免后续误判为漏测。**非缺陷**。
- **`entries[]` 携带全部命中项**：探针 `P5` `entries=3`；变体 M4（仅记首条）⇒ `entry_count==3/2` 断言 **必红**。 ✅

## T035.4 验收 3（双层校验 + 纯函数式）

- **双层（子账户 + 总账户均生效）**：`quota` 层①/②=子账户、层③=账户（探针：`sub=2` 仅层③命中 → `0x1005`）；`scale` 子账户 + 总账户（哨兵 `sub=0`）双算取严。`double_layer_test` 5 例全过。 ✅
- **`RiskEngine::check` 纯函数式**：`const` 成员、只读 `const` 快照 + 入参；`const RiskEngine&` 可调用（编译期）；同入参重复求值逐字节一致（`purity_test`）。**承重**：变异 M3（`check` 内经 `const_cast` 改 `thresholds_`）⇒ `purity_test` 的「无可观测状态改变」断言 **必红**（T035.7）。 ✅

## T035.5 验收 4（P99 ≤ 5 ms + 超限未发往 CTP）

- **P99 ≤ 5 ms** —— **测量方法**：`bench_risk_check`（规模 §4.4.1：10 子账户/200 品种/2000 合约；**预热 1000 次**；`steady_clock` **单次调用**计时；100000 次；排序后取 `P99=ns[(n*99)/100]`；断言 `p99 > 5ms ⇒ 退出 1`）。
  **独立实测（5 次采样）**：`P50 ≈ 10.2 µs`、**`P99 = 33.6 / 34.5 / 34.8 / 36.5 / 40.5 µs`**、`P99.9 = 78–174 µs`、`max = 2.25–6.75 ms`（2 核调度抖动，非判定路径）。**结论：P99 ≪ 5 ms，满足**（余量 > 100×）。环境受限说明：`max` 偶超 1 ms 系本机共享调度，非算法复杂度；`P99` 稳定在 40 µs 内。
- **超限被拒且「未发往 CTP」**：**本模块只能证到「判定 = `kReject`」**（探针 `P1a/P5`）。**本模块无法独立判定「未发送」** —— 链接面仅 `libhg_risk_engine.a + libhg_risk_state.a`（`link.txt`）、`ldd` 无 CTP/网络库，`CTP_FOUND=OFF`（本机无 SDK）。「不发送」由调用方 `order_engine`（`T-036`）承接 ⇒ **记为范围划分，非缺陷**（与产出方未决②一致）。

## T035.6 §3 七项未决 —— 逐项独立判定

| # | 产出方主张 | 本席独立复判 | 结论 |
|---|---|---|---|
| ① | 「同层并列」引擎端不可达，仅选择器单测生效 | **前提成立**（容器/键/查找三重取证，见 T035.3） | **防御性代码（合规）**；建议加注；**非缺陷** |
| ② | 「未发往 CTP」本模块不可独立判定 | **属实**（无 CTP 链接；`link.txt`/`ldd`） | 范围划分（归 `T-036`）；**非缺陷** |
| ③ | `R-RISK-8` 品种级覆盖未承载 | **属实**（`RiskThresholds` 仅全局键；`evalPrice` 无 product 入参；`thresholds()` 无品种级键约定） | 真实承载缺口 → `EXP-T035-005`（低） |
| ④ | `CFG-16` `risk.limit.layers` 未显式解析 | **属实**（`CFG-16` 实体见 `ARCHITECTURE.md:2799`；引擎无该键读取；三层恒参与） | 承载在加载侧 → `EXP-T035-004`（低） |
| ⑤ | `R-RISK-5` 无敞口记录时静默通过 | **属实**（`evalNetPosition`：`if (ex != nullptr)`）；**同族 fail-open**：`evalScale` 无记录 `continue`、`evalPositionLimit` 无记录 `return` | 须产品澄清 → `EXP-T035-003`（中） |
| ⑥ | 已核实非缺陷（`0x0202` 无「基数」槽位；`is_management_role`→`is_responsible`） | **部分否定**：(a) 「无基数槽位」**成立**，但 `FR-RISK-4` 输出「与 §4.2.4 一致」**待澄清**（规模 vs 使用率，`EXP-T035-006`）；(b) `is_management_role` **不可判为非缺陷**（引擎无字段）；**反向遗漏**：**结算岗亦无字段** ⇒ `EXP-T035-002`（中） | **不能维持「非缺陷」**（见下） |
| ⑦ | 裸 `applySnapshot(const RiskSnapshot&)` 无读句柄保护，已文档化 | **属实**（`risk_engine.h:49–57` 注明 + 推荐 `applySnapshot(const RiskState&)`） | 已文档化取舍；**非缺陷** |

**⑥ 的详证（`R-RISK-7` 岗位/角色维度）**：`ARCHITECTURE.md §3.3`（`:446`）明列 `R-RISK-7` 需 `permissionSnapshot`、`conflictFlags`、**`isManagementRole`**；`§4.3.2` `0x100E`（`:1352`）「岗位冲突：操作人具备**风控岗/结算岗**权限」；`REQUIREMENTS/RISK.md:194`（`FR-RISK-7`）「具备风控或结算岗位权限的账号不得下单」。而引擎消费的 `PermissionEntry`（`risk_state/model.h:220–233`）字段仅 `has_risk_post`（+ `is_responsible`）——**无 `is_management_role`、无 `has_settle_role`**；实测全仓 `cpp/src` 对 `has_settle_role` **0 命中**。故：① 产出方「加载侧把 `is_management_role` 折叠为 `is_responsible`」之说**无文档依据**（`§4.4.1` 仅笼统写「岗位标记 / 负责人标记」）；② **仅具结算岗的账号当前可放行操盘**（漏判）。判为**跨任务承载缺口**（模型 `T-034` 冻结 / 加载侧 `T-038`），**须裁定**，**不宜归为 `T-035` 单方面缺陷**。

## T035.7 反退化变异（本席自设，不复用产出方 M1–M4）

> 全部在隔离副本 `/root/iso-t035v` 内进行；逐例「注入 → 构建 `rc` → 断言红 → 复位（`.orig`）→ clean 重编译 → 回绿」；**共享树与隔离树 `*.orig/*.bak/*MUTATION*` 残留均为 0**（复核后实测）；复位后隔离树 `risk_engine` 锚 = `fc086003…`（逐字节回原）。

| 变异 | 施加点 | 期望 | 实测结果 | 承重 |
|---|---|---|---|---|
| **MUT-A** 层序反转（层① 排层② 前） | `quota_selection.h::quotaLayerRank` | 全序断言红 | `quota_layers_test` **2 红**（用例④ `code==0x1017`、`layer==SUB_INSTRUMENT`）+ `selector_test` **2 红** | ✅ 三层限额全序（**含引擎端到端**） |
| **MUT-B** 撤平仓豁免 | `risk_engine.cc::evalPositionLimit`（`if(!is_open)return;`→`(void)is_open;`） | 平仓豁免断言红 | `position_limit_test` **1 红**（`test_close_is_exempt`） | ✅ 平仓豁免 |
| **MUT-C** 破纯函数式 | `risk_engine.cc::check` 内 `const_cast` 改 `thresholds_.term_max_months` | 无副作用断言红 | `purity_test` **1 红**（`test_no_observable_state_change`） | ✅ 纯函数式 |
| **MUT-D**（附加）`entries[]` 仅记首条 | `risk_engine.cc::evalQuota` 循环上界 | 全命中项断言红 | `quota_layers_test` **2 红**（`entry_count==3`、`==2`） | ✅ `entries[]` 全命中项 |

**过程事故（如实披露）**：MUT-B 首版（直接删 `if(!is_open)return;`）触发 `-Werror=unused-parameter` ⇒ **构建 `rc=2`**，此时旧二进制未更新、单独运行显示 `PASS`（**假绿**）—— 本席据「构建 `rc≠0`」判该次运行无效，改为 `(void)is_open;` 后重测得真红。

## T035.8 误伤方向（自建合法指令探针）

**样本**：6 类合法/边界指令（开仓恰好等于限额、市价单、平仓（持仓上限+敞口双超时）、三层全超的开仓（应拒）、未就绪、三类非法入参）+ 确定性复算。

| 探针 | 期望 | 实测 |
|---|---|---|
| `P2` 开仓 `used+req == limit`（边界） | 放行 | **PASS** ✅ |
| `P3` 市价单（偏离不适用） | 放行 + `na=1` | **PASS / na=1** ✅ |
| `P4` 平仓（`R-RISK-3`+`R-RISK-5` 双超） | 放行 | **PASS** ✅ |
| `P5` 开仓三层全超 | 拒，`0x1017` | **REJECT/0x1017/entries=3** ✅ |
| `P6a` 未就绪（无快照） | 拒 `0x0401` | **REJECT/0x0401** ✅ |
| `P6b/c/d` 非法 `sub_account_id=0` / `volume=0` / `direction=99` | 拒 `0x3003` | **REJECT/0x3003** ✅ |
| `P7` 同入参连续求值 | 逐字节一致 | **一致** ✅ |
| **`P1b` 平仓（额度耗尽）** | **放行（`R-RISK-5`）** | **REJECT/0x1004** ❌ ⇒ **TE-T035-001** |

⇒ **发现 1 处误伤**（额度类对平仓）；其余合法方向**未见过度拒绝**（含边界恰好值、市价、平仓在其它规则超限时）。

## T035.9 缺陷 / 观察清单

| 编号 | 类型 | 级别 | 标题 | 状态 |
|---|---|---|---|---|
| **TE-T035-001** | **缺陷** | **P1** | 额度校验（`R-RISK-2`）未对平仓豁免 ⇒ 额度耗尽时合法平仓被误拒 `0x1004`（违反 `R-RISK-5`、`T-035` 备注） | **已建 `BUG-002` + 投 `backend-engineer#cpp`**（`docs/BUGS/BUG-002.md`、`dev-team/inbox/backend-engineer#cpp/BUG-002-bug.md`） |
| `EXP-T035-002` | 观察（待裁定） | 中 | `R-RISK-7` 岗位/角色维度承载不完整：引擎 `PermissionEntry` 无 `has_settle_role`（`0x100E` 要求「风控岗/**结算岗**」）与 `is_management_role`（`§3.3`/`0x100F` 要求）；仅具结算岗者当前可操盘。依赖未文档化的加载侧折叠。 | 待技术负责人裁定归属（`T-034` 模型 / `T-038` 加载侧） |
| `EXP-T035-003` | 观察（待澄清） | 中 | `R-RISK-5` 无敞口记录时 **fail-open**（`evalNetPosition` 仅 `!=nullptr` 才判）；`evalScale`/`evalPositionLimit` 同类。需求未明确「缺数据是否拒绝」。 | 须产品侧澄清（与 `R-RISK-9`「不得以不完整数据对外服务」相关） |
| `EXP-T035-004` | 观察 | 低 | `CFG-16` `risk.limit.layers` 未被引擎解析（三层恒参与）；承载应在加载侧。 | 归 `T-038` 观察 |
| `EXP-T035-005` | 观察 | 低 | `R-RISK-8`「品种级可覆盖」未承载（`RiskThresholds` 仅全局键、`evalPrice` 无 product 入参）。 | 须架构定键（疑随 `T-038`） |
| `EXP-T035-006` | 观察（待澄清） | 低 | `R-RISK-4` 输出 `current_value` = 规模（qty）而非「使用率」（`§4.2.4` 示例 `0.93` / `FR-RISK-4` 验收 4）——语义轴待澄清（无「基数」槽位为真）。 | 须产品/架构澄清 |
| `EXP-T035-007` | 观察 | 低 | `OffsetFlag` 仅 `kOpen/kClose`（无「展期置换」表示）；`FR-RISK-6` 三级处置含「展期置换不受阻断」，映射由 `T-036` 承接。 | 归 `T-036` 观察 |

## T035.10 结论

> **结论：不通过（退回修复）。**
>
> - **阻断项（1）**：`TE-T035-001`（P1，已建 `BUG-002`）—— 事前风控**额度校验未对平仓豁免**，使额度耗尽时**合法平仓被误拒**（`0x1004`），违反 `R-RISK-5` 与 `T-035` 备注「平仓/减仓方向按规则豁免」；且与同引擎 `R-RISK-3/4/5/6` 的平仓豁免**口径不一致**。修复并经本席复检通过前，`T-035` **不满足**验收 1 备注，**不置 `DONE`**。
> - **两向验证**：**「该防的已防」** —— 三层全序（含引擎端到端）、`entries[]` 全命中、双层校验、纯函数式、8 规则主体、P99 预算、平仓豁免（除 `R-RISK-2`）均**独立实测通过**，且经本席 4 例自设变异**双向承重**；**「误伤」方向** —— 捕获 1 处（额度×平仓），其余合法/边界方向未见过度拒绝。
> - **独立实测基线**：构建 `rc 0`、告警/错误 `0/0`；`ctest -R risk_engine` **13/13**；聚合 `arch-check` **`rc 0`**（`E1`–`E7` 全 0）；`E4` 实扫 **45 文件、命中 0**；`P99 = 33.6–40.5 µs`（5 采样）；自撰探针 8 例（7 通过 1 失败）；隔离变异 4 杀 + 1 假绿事故已披露；共享树/隔离树残留 **0**。
> - **不置 `DONE`**（门禁结论由编排者落）；`TASKS.md`/`BOARD.md`/`ARCHITECTURE.md` **零改动**。

---

# `T-010` 独立复核证据（2026-09-22）

> 复核人：`test-engineer`（独立复核方，席位 `te-verify-t010b`）。被复核对象：`T-010`「用户与权限服务（岗位分离 + 子账户软隔离）」**退回重做交付**（产出方实例 `be-t010`）。
> 立场：产出方一切自报**视为待证命题**；本章分「**独立实测**」与「**产出方自述（本席未复现）**」两栏。**本席不置 `DONE`**（门禁结论由编排者落）。

## T010b.0 快照与锚点比对

- **共享树锚**（`cwd = 仓库根`）：`find java/src -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`fba1f886911eed4df00d2598c6fce2ec` / 335 文件** —— 与产出方自报**一致**（本席独立重测，2026-09-22）。
- **被复核交付位于工作区**（未提交）：`git status` 显示 `AuthService.java`/`Bootstrap.java`/`SessionAuthFilter.java`/`AuthRoutes.java`/`Config.java`/`data-init.sql` 等为已改，且新增 11 个 `web/handler/auth/**` + `SessionRenewer` + 2 个 `dao/impl` + 4 个测试类。
- **隔离副本**：`/hgt010iso`（含 `docs/`+`tools/`+`java/`，防路径型测试假红与 `mvn validate` 的 `arch-check` exec `127`）。**所有变异/探针仅在副本内执行**；开局扫共享树 **`*.orig`/`*.bak`/`*.rej`/`*MUTATION*` = 0**，复核结束共享树锚**仍为** `fba1f886…`/335、残留 **0**。

## T010b.1 独立实测基线

| 项 | 独立实测 | 产出方自述（本席未复现） |
|----|---------|------------------------|
| `mvn -B -o -T1 clean test`（副本） | **484 / 0 / 0 / 0** | 484/0/0/0（一致） |
| `@Test` grep vs surefire | **484 == 484**（`@ParameterizedTest` = 0） | 484 == 484（一致） |
| 聚合 `arch-check.sh` | **`rc 0`**；`E1`–`E7` 全 0；`E4` 实扫 45 文件、命中 0 | `rc 0`（一致） |
| 自撰独立探针（`TeVerifyT010bProbeTest`，10 例） | **10/10 通过** | ——（产出方无此探针） |

## T010b.2 缺口 (a) REST 端点补齐 —— **独立枚举双向差集**

**独立实测**（自撰探针 `routeDeclaredVsRegisteredBothDirections` / `routeCountCorroboratedIndependently`，**非复用产出方用例**）：

- `ApiRoutes.byModule().get("AUTH")` 声明 **10** 条路径。
- `AuthRoutes.register(new Router(), null, null)` 实测注册 **17** 个 `(方法,路径)` 对：`login/logout`（各 1）+ `user`（GET/POST/PUT）+ `role`、`position-group`、`authorization`、`sub-account-perm`（各 GET/POST）+ `change-log`、`unauthorized-attempt`（各 GET）+ `password`（POST/PUT）。
- **正向差集**：声明而**未注册** = **∅**。
- **反向差集**：注册而**声明外**（超范围新增）= **∅**。
- **路由总数**：自枚举各注册器 —— `AUTH 17 / CONFIG 17 / PRODUCT 13 / SPOT 15 / PLAN 18` + `ping 2` = **82**，与 `Bootstrap.assembleWithResources(...).routeCount()` 实测 **82** **相等**。
- **前后值核对**：`git show HEAD:.../AuthRoutes.java` 实测注册 **2** 对；`git show HEAD:.../BootstrapAssemblyTest.java` 原断言 **67** ⇒ Δ+15 对 = 67+15 = **82**，**自洽**。
- **断言订正纪律**：`BootstrapAssemblyTest.java:137-141` 实测含 `【T-010 订正（加注）】` 注释，写明「订正理由 = 需求面（`ApiRoutes` 声明 AUTH 10 条路径、『声明面 ⊇ 注册面』缺口被补齐）… **非为过测试而放宽断言**」；断言由**下界式**改为**精确等值**且**数值增大** ⇒ 属「补需求面」而非放宽。

**产出方自述（本席未复现）**：AUTH 注册 2/10 → 10/10（17 对）、路由计数 67 → 82。 —— 与上列独立实测**一致**。

## T010b.3 缺口 (b) `FR-AUTH-1~18` 覆盖 —— 逐条实现位置 + 接线

**独立实测**（读源码 + `src/main` 调用点枚举，非采信自述）：

| FR | 实现位置（`AuthService`） | 用例 |
|----|--------------------------|------|
| 1 新增用户 | `createUser:714` | `AuthAdminServiceTest.createUserHashesAndSeeds` |
| 2 修改用户 | `updateUser:756` | `updateUserLeaderFlagRequiresReview` |
| 3 停用 | `disableUser:796` | `disableAndEnable` |
| 4 启用 | `enableUser:822` | `disableAndEnable` |
| 5 用户列表 | `listUsers:859` | `listUsersPaged`（路由 `GET /auth/user`） |
| 6 定义角色 | `defineRole:899`/`listRoles:884` | `defineRoleValidations` |
| 7 岗位互斥配置 | `definePositionConflict:961`/`listPositionConflicts:948` | `definePositionConflict` |
| 8 岗位分离校验 | `grantRole:437-493`（`conflicts`） | `grantRoleRejectsPositionConflict` |
| 9 负责人禁操盘 | `assertCanTrade:538` / `grantRole:471` | `leaderWithManagementRoleCannotTrade`、`managementRoleAloneCannotTrade`、`enterpriseLeaderAloneCannotTrade` |
| 10 人员授权 | `grantAuthorization:1020` | `grantAuthorization` |
| 11 授权变更留痕 | `recordChange:1260`/`listPermissionChangeLogs:1143` | `changeLogAndAttemptQueries` |
| 12 子账户数据权限 | `assignSubAccountPerm:1091`/`listSubAccountPerms:1073` | `assignSubAccountPerm` |
| 13 跨子账户查看 | `assignSubAccountPerm`（`CROSS_READONLY` 门禁）/`assertSubAccountVisible:613` | `subAccountSoftIsolation`、`crossReadonlyIsReadOnlyOnWriteFace` |
| 14 登录 | `login:229` | `loginSucceeds`、`loginFailuresAreIndistinguishable` |
| 15 登出/会话 | `logout:304`/`touchSession:325`/`forceLogout:1173` | `sessionIdleTimeout`、`logoutInvalidatesBoth`、`AuthSessionIdleTimeoutTest` |
| 16 口令管理 | `changePassword:360`/`resetPassword:406` | `changePasswordEnforcesStrengthAndHistory`、`resetPasswordReturnsOneTimeSecret` |
| 17 登录失败锁定 | `login` 锁定分支（`loginFailLimit/loginLockMinutes`） | `fiveFailuresLockTheAccount`、`AuthAdminServiceTest.loginLockThresholdFromConfig` |
| 18 变更记录查询 | `listPermissionChangeLogs:1143`/`listUnauthorizedAttempts:1157` | `changeLogAndAttemptQueries` |

- **独立结论**：**18/18** 均至少有实现 + 至少一个 `@Test`。**未见**「交付了但未接线」的**阻断级**项——8 个新增 handler **逐个实测**均调用 `authService.*`（`UserHandler`→`listUsers/createUser/updateUser/disableUser/enableUser`；`RoleHandler`→`listRoles/defineRole/grantRole`；…）。
- **接线核准**：`touchSession` 经方法引用 `authService::touchSession` 在 `Bootstrap.java:153` 注入（`src/main` 中 **1** 处），非 0 调用——本席初查 `.touchSession(` 曾漏判，已复核更正。
- **观察（非缺陷，单列）**：
  - `assertCanTrade` 在 `src/main` **0 调用** —— 依 `T-102` 裁定其服务端强制点为 **M2 下单写路径（`T-046`）**，M1 不接线属**有意**（`PlanService:1321` 有同源说明）。
  - `forceLogout` 在 `src/main` **0 调用**、`ApiRoutes` AUTH 声明面**无**强制下线路径 ⇒ `FR-AUTH-15` 验收 4（管理员强制下线）为**服务级交付 + 单测**，**未接 REST**。属「需求面 vs 冻结端点面」问题（AUTH 声明面冻结为 10 路径），**本席不判为 T-010 退回重做的四项硬缺口回归**。

## T010b.4 缺口 (c) 两处逻辑缺口

**① 锁定阈值/时长接 `Config`（改配置实测生效）** —— 独立探针 `loginLockFromConfig`：`System.setProperty(hg.auth.login_fail_limit,"2")` + `hg.auth.login_lock_minutes,"11"`，随后 `login` 实测 **第 2 次即 `ACCOUNT_LOCKED`**、`lockedUntil = T0+11min`（**非硬编码 5**）。机制：`Config.getInt` 取 `System property > 属性文件 > 默认值`（`Config.java:148-175`）。**独立实测通过**。

**② 会话滑动续期（空闲超时而非绝对过期）** —— 独立探针 `slidingIdleTimeout`（**自建**，非复用 `AuthSessionIdleTimeoutTest`）：
- T0+20min 一次有效请求后 `expireTime` 顺延至 `now+30min`；
- T0+40min（**已过原绝对过期点 T0+30**）再次请求**仍有效**并继续顺延；
- **反证**：改用 2 参构造（不注入 `SessionRenewer`）时，同一时刻（T0+40min）**必 `10006`**。**独立实测通过**。

**产出方自述（本席未复现）**：`AuthSessionIdleTimeoutTest` 证「+20min 请求后 +40min 仍有效」 —— 与独立探针语义**一致**。

## T010b.5 缺口 (d) AUTH 预设数据幂等播种

**独立实测**（自撰 SQLite 探针 `probe_seed_idempotency.py`，**真实执行**而非文本断言）：
- 从 `docs/DEPLOY/schema/data-init.sql` 逐字取出 **§③ 三条 `INSERT`**，在 SQLite 按 DDL 等价建表（DM 专有 `IDENTITY(1,1)` 译为 `INTEGER PRIMARY KEY AUTOINCREMENT`），**执行两遍**。
- 第一遍：`t_position_group 7` / `t_role 7` / `t_position_conflict 5`；**第二遍：行数不变（幂等）**。
- 关键字段：岗位组 7 个互异编码；互斥对**全部 `a<b` 单向存储**且集合 = `{(TRADER,FIN_RISK),(TRADER,SETTLER),(FIN_RISK,SETTLER),(TRADER,CASHIER),(TRADER,APPROVER)}`；**仅 `TRADER` 具交易能力**；`SYS_ADMIN.is_business_role='N'`。
- **幂等由 `WHERE NOT EXISTS` 承重**：见 T010b.7 `MUT-D`（去之，第二遍即 `UNIQUE constraint failed: t_position_group.group_code`）。
- **产出面**：第 ③ 段**确在** `docs/DEPLOY/schema/data-init.sql`（实测 md5 `f64cb2fc1bbb9a1d46ae0695cd5f6222` / 176 行）；§② 承接表 3 项（岗位组/角色/默认互斥）已标「✅ 已交付：见第 ③ 段（T-010）」。

> ⚠️ **环境限制（据实标注）**：本机**无 DM8 实机**。上述为 **SQLite 等价执行**（DM 专有 `SET IDENTITY_INSERT` 仅用于 §①/①-bis，不涉 §③），**`INSERT…SELECT…UNION ALL…WHERE NOT EXISTS` 为标准 SQL**；故该项证据等级 = **替代证据级（文本/等价引擎级）**，**不得**表述为「已在 DM8 验证」。

## T010b.6 重点独立探针（岗位互斥 / 析取 / 子账户隔离）

- **岗位互斥**：已具 `TRADER`（岗位组 1）再授 `FIN_RISK`（岗位组 2，矩阵有对）⇒ **拒 `POSITION_SEPARATION_CONFLICT` 且记 `t_unauthorized_attempt`（`POSITION_CONFLICT`）**；**反向**：再授合法 `AUDITOR` **放行**（防误拒）。**独立实测通过**。
- **负责人禁操盘 = 析取（`CH-010`）**：本席构造**两个各自独立**的账号 —— ①仅 `is_enterprise_leader='Y'`（**无任何角色**）⇒ 拒；②仅持 `APPROVER` 角色（**非负责人**）⇒ 拒。二者**互不依赖**，`命中=` 文案亦分别显示「`is_enterprise_leader=Y`」「持 `APPROVER` 角色」。**独立实测通过**（合取变异见 `MUT-A`）。
- **子账户软隔离（两向）**：①无授权 ⇒ `assertSubAccountVisible` 拒（`10004`）、`visibleSubAccountIds` = **空集**（fail-closed）；②`LIMITED` 到子账户 7 ⇒ 本账户放行、**跨账户 8 拒**，可见集 = `[7]`；③`CROSS_READONLY` ⇒ **读面放行任意账户、写面拒**（`assertSubAccountWritable` `10004`），可见集含哨兵 `0`。**独立实测通过**。

## T010b.7 反退化变异（隔离副本内，后复位 + clean 重编译）

| 变异 | 目标 | 变体后 | 复位后 | 副本路径 |
|------|------|--------|--------|---------|
| `MUT-A` `\|\|`→`&&`（合取） | `AuthService:546 assertCanTrade` | **FAIL**：自撰 2 例（`leaderAloneRejected`/`managementAloneRejected`）+ 产出方 2 例（`enterpriseLeaderAloneCannotTrade`/`managementRoleAloneCannotTrade`）红 | 绿 | `/hgt010iso/java/.../AuthService.java` |
| `MUT-B` 短路 `conflicts()` | `AuthService.conflicts` | **FAIL**：自撰 1 例 + `AuthServiceTest.grantRoleRejectsPositionConflict` 红 | 绿 | 同上 |
| `MUT-C` 去续期器（忽略 `renewer`） | `SessionAuthFilter.authenticate` | **FAIL**：自撰 1 例 + `AuthSessionIdleTimeoutTest.activeRequestsKeepSessionAliveSlicing` 红（`10:50` vs `10:30`） | 绿 | `/hgt010iso/java/.../SessionAuthFilter.java` |
| `MUT-D` 去 `WHERE NOT EXISTS`（3 处） | `data-init.sql §③` | **FAIL**：自撰 SQLite 探针第二遍 `IntegrityError`；`AuthSeedDataTest.seedsAreIdempotent` 红 | 绿 | `/hgt010iso/docs/DEPLOY/schema/data-init.sql` |

**复位验证**：三文件 md5 逐字节复原（`AuthService` `45eafcdca2ecf2abbd586f9e3a2c2b77`、`SessionAuthFilter` `4d7b67e339ad054d988dfc6a29980a17`、`data-init.sql` `f64cb2fc1bbb9a1d46ae0695cd5f6222`），**clean 重编译 = 484/0/0/0**，副本锚回 `fba1f886…`/335。**4 例变异全杀**（各自双向承重）。

## T010b.8 缺陷 / 观察

| 编号 | 严重度 | 类别 | 证据（独立实测） | 复现 | 归属建议 |
|------|--------|------|-----------------|------|---------|
| **TE-T010b-001** | **低**（守卫空转，非阻断） | 测试强度退化 | `T109EndpointRegistrationTest` **段②** 主断言经**装配分发器**（含 `SessionRenewer`）调用已认证请求；本席自撰探针实测：经装配分发 —— 已注册 `GET /api/v1/product` = **500**、未注册 `GET /api/v1/config/NOT-A-ROUTE` = **500**（假驱动下 `touchSession` 写库 NPE）；**直连分发**（无续期器）未注册 = **404**。⇒ `problems.isEmpty()`（只收 404/405）**恒真、不再守卫注册** | 见左（探针输出） | `T-010` 引入续期器的**副作用**；产出方已把**反向对照**改直连分发（理由确**成立**），但**未察觉主断言被架空**。建议将段② 一并改**直连分发**或改断言 `routeCount`。注册面另由 `BootstrapAssemblyTest` `routeCount==82` 守卫（本席已独立复算一致），故全局覆盖未失 |

**观察（非缺陷，不计入缺陷数）**：`assertCanTrade`（`T-102` 裁定归 M2/`T-046`）、`forceLogout`（`FR-AUTH-15`④，无 REST 端点）在 `src/main` 均 0 调用 —— 均属**已裁定/端点面冻结**的既知事项，非本次四项硬缺口回归。

## T010b.9 结论

**结论：`有条件通过`。**

- **四项硬缺口独立取证结果**：**(a) 通过**（声明 10 路径 / 注册 17 对 / 双向差集 = ∅ / 路由数 82 独立复算一致 / 断言订正合规）；**(b) 通过**（`FR-AUTH-1~18` 逐条有实现 + 用例，handler 接线实测，无阻断级未接线项）；**(c) 通过**（配置化锁定 + 滑动续期均**改配置/自建探针实测生效**，含绝对过期反证）；**(d) 通过（替代证据级）**（§③ 幂等**真实执行**两遍验证，无 DM8 实机已据实标注）。
- **唯一条件**（归属=**编排者/开发**）：对 **`TE-T010b-001`**（低·守卫空转）裁定处置 —— 建议将 `T109EndpointRegistrationTest` 段② 改直连分发或改断言 `routeCount`（**不阻断** `T-010` 的四项硬缺口结论）。
- **两向验证结论**：**「该防的已防」** —— 互斥拒+留痕、**析取**（两条件各自独立）、子账户 **fail-closed**、跨账户只读**写面拒**、滑动续期，均经独立探针 + 4 例自设变异**双向承重**；**「误伤」方向** —— 合法交易岗操盘、合法角色授予、跨账户只读**读面**均**放行**，未见过度拒绝。
- **独立实测基线**：`mvn -B -o -T1 clean test` **484/0/0/0**；`@Test` **484** == surefire **484**；聚合 `arch-check` **`rc 0`**（`E1`–`E7` 全 0）；自撰探针 **10/10**；隔离变异 **4 杀**；共享树锚 `fba1f886…`/335 不变、残留 **0**。
- **本席不置 `DONE`**（门禁结论由编排者落）；`docs/TASKS.md`/`docs/BOARD.md`/`docs/ARCHITECTURE.md` **零改动**。

**纯追加证明**（自指值不写死）：
- 追加前基线：`md5 1f219666f28098cae25484acac6eb697` / **3688 行**（本席落笔前实测；此前 `926a499c…`/3557 已被 `T-035` 复核章追加推进，落笔前三次×10s 取样不变 ⇒ 已静默）
- 校验：`head -3688 docs/TEST_RESULT.md | md5sum` = `1f219666f28098cae25484acac6eb697`（与追加前基线一致 ⇒ **纯追加**）
- 追加后全文 md5 / 行数：**以实时 `md5sum docs/TEST_RESULT.md` / `wc -l` 为准**（自指值不可写死）

---

## T-010 复核缺陷 `TE-T010b-001`（守卫空转）· 修复证据（`be-t010fix`）

- **修复人 / 时间**：后端开发工程师（`be-t010fix`）／ 2026-09-22
- **缺陷**（`te-verify-t010b` 认定）：`T109EndpointRegistrationTest` 段② 经 `Bootstrap` **装配分发器**（含 `SessionRenewer`）调用**已认证**请求；假驱动下 `touchSession` 写库 NPE ⇒ **注册 / 未注册路径一律 500** ⇒ 只收 404/405 的 `problems.isEmpty()` **恒真、已不再守卫端点注册**。

### 修1. 独立复现（隔离副本 `/hgt010iso`，未采信交底）
- 基线（未改）：`mvn -B -o -T1 clean test -Dtest=T109EndpointRegistrationTest` ⇒ **2/2 绿**。
- **空转实证**：在副本内**摘掉** `ConfigRoutes` 的 `GET /config/ctp` 注册，**仅跑段②** ⇒ **仍绿**（`1/1`）——全量经装配分发器的已认证请求 500、永不触及 404/405 ⇒ 证原断言已不守卫注册。（对照：直连分发时未注册路径 = 404。）

### 修2. 处置（选 **(i)** 直连分发；不选 (ii) 的理由）
- 选 **(i)**：段② 走**直连分发**（2 参 `SessionAuthFilter` = 不续期），认证通过后才进入路由匹配 ⇒ **恢复 404/500 区分力**；与产出方已改的**反向对照**同形，并与 **`AUTHEndpointRegistrationTest` 段② 同形**。
- **未选 (ii)**（改断言 `routeCount`）：装配面注册完整性**已由 `BootstrapAssemblyTest` 的 `routeCount == 82`（`BootstrapAssemblyTest:140`）守卫**，(ii) 将与本文件段① 及该用例**三重重复**、且不增分发层区分力。

### 修3. 改动清单（共享树，仅 1 文件）
- `java/src/test/java/com/hedgeguard/web/T109EndpointRegistrationTest.java`：段② `assembly.dispatcher()` → 直连 `plain` 分发；反向对照复用同一 `plain`；类级 javadoc + 段② 内**加注**原空转成因与判据。diff = **+29 / −5**。
- **`src/main` 零改动**；未改 `docs/TASKS.md`/`docs/BOARD.md`/`docs/ARCHITECTURE.md`/`cpp/**`/`proto/**`/`tools/**`。

### 修4. 守卫强度反转（**双向**，隔离副本内，复位字节复原 md5 `aa92832f…`）
| 变异 | 手段 | 结果 |
|------|------|------|
| **M1**（摘端点） | 副本内删 `ConfigRoutes` 的 `GET /config/ctp` 注册 | 新段② **红**：`T-109 端点仍返回 404/405：[GET /api/v1/config/ctp → 405]` |
| **复位** | 从 `.orig` 字节复原 | 新段② **回绿** |

> 实测 19 条 DISPATCH 状态 = 500（GET）/400（POST），**无一是 404/405**；反向对照（不存在路径）= **404**。

### 修5. 全量回归与计数（共享树隔离副本 `/hgt010final`）
- `mvn -B -o -T1 clean test` ⇒ **484 / 0 / 0 / 0**，BUILD SUCCESS。
- `@Test`（行首锚 `^\s*@Test\b`）**484** == 裸 `grep -c @Test` **484** == surefire **484**。

### 修6. 架构聚合
- `bash tools/arch-check/arch-check.sh` ⇒ 聚合 **rc 0**；`E1`–`E7` 逐条 **0**（`E5` = 0；`E4` 实扫、`E7` 通过）。

### 修7. 冻结点锚（cwd = 仓库根，`LC_ALL=C`）
- `find java/src -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`8bf0e1d6c2e83a52e62d511616324c4b`** / **335 文件**（3×≥9s 不变；实测 2026-09-22 20:07，UTC+8）。前态（复核时）= `fba1f886…`/335（文件数不变、内容变更）。

### 修8. §3 只读项结论（不处置，仅报断）
- **`assertCanTrade`（`src/main` 0 调用）**：**已有生效裁定**（`docs/TASKS.md:147` `T-102`=`DONE`；`:1057` `T-046` 备注）——服务端强制点 = **M2 下单写路径**（归 `T-046`），M1 **有意不接线**（接 `decompose` 会误拒 `FR-PLAN-14` 的财务/风控岗）⇒ **非缺陷**。
- **`forceLogout`（`FR-AUTH-15④`）**：原文（`docs/REQUIREMENTS/AUTH.md:306–319`）**无**「REST / 端点 / 接口 / HTTP / URL / 路由」任何字样，仅要求「管理员可**强制下线**（须留痕）」+ 验收4「强制下线操作留痕」；能力已在服务层实现（`AuthService.forceLogout:1173`，`AuthAdminServiceTest.forceLogout` 有用例），`ApiRoutes` AUTH 冻结清单（`:72–74`）与 `ARCHITECTURE §4.1.1` 均无该路径 ⇒ **按原文判 = 非缺口**（属服务层能力）；「触发入口是否须入 REST 面」属**需求阐释**、非字面，**留编排者裁定，本席不动**。

### 修9. 纯追加证明（自指值不写死）
- 追加前基线：`md5 04a87a2d5631a68c127b89e05cad4e9c` / **3817 行**（落笔前 3×≥9s 取样不变）。
- 校验：`head -3817 docs/TEST_RESULT.md | md5sum` 应与上式一致（⇒ 纯追加）。
- 追加后全文 md5 / 行数：**以实时 `md5sum docs/TEST_RESULT.md` / `wc -l` 为准**（自指值不可写死）。

---

# `T-010` 修复 delta 复检证据（`TE-T010b-001`）（2026-09-22）

> 复检人：`test-engineer`（独立复核方，席位 `te-rec-t010`）。被复检对象：`be-t010fix` 对 **`TE-T010b-001`**（`T109EndpointRegistrationTest` 段② 守卫空转）的修复。
> 立场：产出方一切自报**视为待证命题**；本章分「**独立实测**」与「**产出方自述（本席未复现/仅转述）**」两栏；**不复用产出方变异手法**（另设等价变异）。**本席不置 `DONE`**，不改 `TASKS.md`/`BOARD.md`/`ARCHITECTURE.md`。

## T010rec.0 快照与锚点比对

- **共享树锚**（`cwd=仓库根`，`LC_ALL=C`）：`find java/src -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`8bf0e1d6c2e83a52e62d511616324c4b` / 335 文件**（本席独立重测，3×≥9s 不变；实测 2026-09-22 20:22 CST）—— 与产出方自报**一致**。
- **被修复文件**：`java/src/test/java/com/hedgeguard/web/T109EndpointRegistrationTest.java` md5 **`2a48eb17f6399d131ff0756fd509d7b2` / 182 行**；`git diff`(HEAD→工作区) = **+29 / −5**（与产出方自报逐字一致），**仅此一文件**。
- **`ConfigRoutes.java`** md5 **`aa92832f8299801066ffd4515972eb65`**（= 产出方自报的复位值，一致）。
- **隔离副本** `/root/hgt010rec`（含 `docs/`+`java/`+`tools/`，防路径型测试假红与 `mvn validate` 的 `arch-check` exec `127`）。**所有变异仅在副本内**；副本 `java/src` 锚 = `8bf0e1d6…`/335（= 交付冻结点 ⇒ 变异已完全复位）。
- **共享树残留**：开局与收尾扫 `*.orig`/`*.rej`/`*MUTATION*`/`TeRecT010*` = **0**；收尾锚仍 `8bf0e1d6…`/335。

## T010rec.1 基线（独立实测）

| 项 | 独立实测 | 产出方自述（我未复现） |
|----|---------|----------------------|
| `mvn -B -o -T1 clean test`（副本，`TMPDIR=/`）| **484 / 0 / 0 / 0**，BUILD SUCCESS | 484/0/0/0（一致）|
| surefire XML 合计（60 文件）| tests **484** failures **0** errors **0** skipped **0** | 484（一致）|
| `@Test` 行首锚 `^\s*@Test\b` vs 裸 `grep -c` | **484 == 484**（无注释吞并）| 484==484（一致）|
| 聚合 `arch-check.sh` | **rc 0**；`E1`–`E7` 逐条 0（`E5`=0；`E4` 实扫、WARN 3 为信息项）| rc 0（一致）|
| `T109EndpointRegistrationTest` surefire | tests=**2** failures=**0** | 2/2 绿（一致）|

## T010rec.2 §2.1 原守卫确为空转（复现）—— **缺陷真实存在**

机制取证（自撰探针 `TeRecT010PreFixGuardTest#vacuityProbe`，**未复用产出方手法**；M1 变异态下）：

```
[vacuity] GET  /api/v1/config/ctp                 assembly=500 plain=405   ← 被摘掉的端点
[vacuity] POST /api/v1/config/ctp                 assembly=500 plain=400
...（其余 18 条 DISPATCH 条目）...                  assembly=500 plain=500/400
[vacuity] UNREGISTERED /api/v1/config/definitely-not-a-route  assembly=500 plain=404
```

- **经装配分发器（含 `SessionRenewer`）**：19 条 DISPATCH + 1 条根本不存在路径，**一律 `500`**（假驱动下 `touchSession` 写库 NPE）。
- ⇒ 原段② 只收集 `404/405` 的 `problems.isEmpty()` **恒真**（既非注册面、也非反面）。
- **等价还原「修复前形态」**（段② 主断言经 `assembly.dispatcher()`、反向对照用 2 参直连 `plain`；与 `te-verify-t010b` 记录的工作区形态一致）：**M1 变异下 `preFixMainViaAssembly` = 绿（PASS）** —— 摘掉一个**真实注册**而断言不红 ⇒ **空转证实**。

## T010rec.3 §2.2 新守卫确会咬（双向）

同一 M1 变异（删 `ConfigRoutes` 的 `GET /config/ctp` 注册）下：

- **修复后段② → 红**：`T109EndpointRegistrationTest.t109EndpointsNot404Not405:167` FAILURE，`T-109 端点仍返回 404/405：[GET /api/v1/config/ctp → 405]`。
- **复位（字节复原 + clean 重编译）→ 绿**：`ConfigRoutes.java` md5 逐字节回 `aa92832f…`；`mvn -o clean test -Dtest=…` ⇒ 段② 2/2、还原态 1/1，BUILD SUCCESS。

## T010rec.4 §2.3 锚「行为」不锚实现；无关变更不误红

- 断言只检**路由结果**：未注册 ⇒ `404`；路径在而方法缺 ⇒ `405`；已注册 ⇒ **非** `404/405`（handler 因无真实库可 4xx/5xx）。不依赖类名/字段/调用形态等实现细节。
- **无关变更旁证**（M2：在 `ConfigRoutes` 另注册一条**与 T-109 无关**的新端点 `GET /api/v1/config/zzz-rec-unrelated-probe`）⇒ 段② **仍绿（2/2）**，段① 亦绿 ⇒ **未见误红**。

## T010rec.5 §2.4 未为过测试而放宽（改动清单）

`T109EndpointRegistrationTest.java`（行号为**现行文件**）：

| 行 | 修复前 | 修复后 | 性质 |
|----|-------|-------|------|
| 32–36（javadoc）| 「经 `RequestDispatcher` 进程内分发」| 增「**直连分发**（2 参 `SessionAuthFilter`＝不续期）」+ ⚠️ 说明原段② 经装配分发器致恒 500 | **文档** |
| 138–148 | （无）| 新增 `// 【T-010 修正 · TE-T010b-001（守卫空转）】` 加注：原文写明「假驱动下**已认证请求一律 500** ⇒ `problems.isEmpty()` **恒真、不再守卫端点注册**」 | **加注（已写明成因）** |
| 149–157 | （无）| 新增 `plainRouter` + `ConfigRoutes/SpotRoutes/PlanRoutes` 直连注册 + `RequestDispatcher plain`（2 参过滤器）| **测试装配** |
| 161 | `assembly.dispatcher().dispatch(` | `plain.dispatch(` | **分发路径** |
| 169–170 | `// …应 404…` + `assembly.dispatcher()` | `// …应 404/405…` + `plain.dispatch(` | **分发路径 + 注释** |

- **断言集合前后逐条一致**，**无一条**被改成「只查长度 / 非空 / 不抛异常」：
  - `assertTrue(problems.isEmpty(), …)`（L167）**未变**；
  - `assertNotEquals(200, unknown.status)`（L173）**未变**；
  - `assertTrue(unknown.status == 404 || unknown.status == 405, …)`（L174–175）**未变**；
  - 装配冒烟 `assertDoesNotThrow(() -> Bootstrap.assembleWithResources(…))`（L131–132）**保留**。
- 唯一行为变化 = **分发器由装配改为直连** ⇒ 守卫由**空转**变**承重**（强度**升**，非降）。

## T010rec.6 §2.5 `src/main` 零改动（独立核）

- `git diff --stat HEAD -- java/src/main` = 5 文件（`Config.java`/`AuthService.java`/`Bootstrap.java`/`SessionAuthFilter.java`/`AuthRoutes.java`），**全部属 `T-010` 退回重做的既有改动**（内容与本修复无关；修复自述范围仅测试）。
- **mtime 佐证**：`java/src/main` 全部文件 mtime ≤ **17:04:49**；被修复的测试文件 mtime = **20:02:37** ⇒ 修复写入晚于最后一次 `src/main` 写入约 3 小时，其间 `src/main` 零写入。
- ⚠️ **证据强度限制（据实标注）**：修复前工作树**不可重建**（无 `be-t010fix` 落笔前的整树快照），故此项为「**diff 范围 + mtime 次序**」佐证，**非**与修复前树逐字节 diff。

## T010rec.7 §2.6 / §2.7 回归、门禁与「方案 (ii) 三重重复」理由

- **回归 / 门禁**：见 §T010rec.1（484/0/0/0、`@Test`==surefire、聚合 rc 0、`E1`–`E7` 全 0）。
- **§2.7 理由核查 = 成立**：`BootstrapAssemblyTest.java:140` **确有** `assertEquals(82, assembly.routeCount(), …)`；M1 变异下**实测红**：`expected: <82> but was: <81>` ⇒ 该守卫**确能拦住「少注册端点」**。选 (i) 合理。
- **措辞订正（观察级）**：产出方「(ii) 系**三重**重复」略宽 —— 严格说 (ii) 与 `BootstrapAssemblyTest:140` 为**完全重复**，与段① 为**部分重叠**（段① 更强：逐条点名 19 对）。**不影响**选 (i) 的正确性。
- ⚠️ **值得下游知悉的覆盖移交**：修复后段② 只验「直连注册表」（不经 `Bootstrap`），故「`Bootstrap` 是否调用 `ConfigRoutes.register`」不再由段② 承担 —— 已由 `BootstrapAssemblyTest:140` 兜住（本席实测其在 M1 下红），**全局覆盖未失**。

## T010rec.8 变异表（隔离副本 `/root/hgt010rec`）

| 变异 | 目标 | 修复前形态 | 修复后 | 复位后 | 副本路径 |
|------|------|-----------|--------|--------|---------|
| **M1**（摘端点）| 删 `ConfigRoutes:46` 的 `GET /config/ctp` 注册 | `preFixMainViaAssembly` = **绿（PASS，空转证实）** | `t109EndpointsNot404Not405` = **红**（`GET /api/v1/config/ctp → 405`）| **绿**（clean 重编译后 2/2、还原态 1/1）| `java/src/main/java/com/hedgeguard/web/handler/config/ConfigRoutes.java` |
| **M2**（无关新增）| 另注册 `GET /api/v1/config/zzz-rec-unrelated-probe` | —— | **绿**（2/2，无误红）| —— | 同上 |
| **M1 下附加判定** | `T109EndpointRegistrationTest#allT109RoutesRegistered`（段①）| —— | **红**（`[GET /api/v1/config/ctp]` 未注册）| 绿 | 同上 |
| **M1 下附加判定** | `BootstrapAssemblyTest#configuredDatabaseRegistersAllBusinessRoutes:140` | —— | **红**（`82` vs `81`）| 绿 | 同上 |

- 修复前形态还原源：`/root/hgt010rec/mut/TeRecT010PreFixGuardTest.java`（**仅副本，非交付制品**）。
- 「**修复前主+反向皆装配**」变体（更早提交形态）在 M1 下**亦红**，但其红点在**反向对照**（`不存在的路径应 404/405，实际=500`），**非**主断言 —— 说明产出方「修复前 2/2 绿」只在**中间态**（反向已直连）成立；此为历史事实澄清，**不构成缺陷**。

## T010rec.9 缺陷 / 观察清单

| 编号 | 严重度 | 类别 | 结论 |
|------|--------|------|------|
| —— | —— | 缺陷 | **无**（未发现新增缺陷；原 `TE-T010b-001` 已被**独立复现并证实修复有效**）|
| `OBS-T010rec-1` | 观察 | 覆盖移交 | 段② 不再经 `Bootstrap`；装配面注册完整性由 `BootstrapAssemblyTest:140` 承接（**已实测在 M1 下红**）⇒ 覆盖未失 |
| `OBS-T010rec-2` | 观察 | 措辞 | 「三重重复」宜作「1 完全重复 + 1 部分重叠」；不影响结论 |
| `OBS-T010rec-3` | 观察 | 证据强度 | `src/main` 零改动为 mtime + diff 范围佐证（修复前树不可重建），非逐字节 diff |

## T010rec.10 结论

**结论：`通过`。**

- **§2.1（原守卫空转）**：**复现成功** —— 等价还原的修复前段② 在 M1 下**仍绿**；装配分发器对 19+1 条请求**一律 500**。
- **§2.2（新守卫咬合）**：**双向成立** —— M1 下修复后段② **红**（405）、复位后**绿**。
- **§2.3 / 2.4 / 2.5 / 2.6 / 2.7**：行为锚定 ✅；无关变更不误红 ✅；断言未放宽 ✅；`src/main` 零改动 ✅（附强度限制）；回归 484/0/0/0 + 聚合 rc 0 ✅；(ii) 理由成立 ✅。
- **独立实测基线**：`mvn -B -o -T1 clean test` **484/0/0/0**；surefire **484** == `@Test` **484**；聚合 `arch-check` **rc 0**（`E1`–`E7` 全 0）；副本 `java/src` 锚 = `8bf0e1d6…`/335；共享树锚不变、残留 0。
- **本席不置 `DONE`**；不改 `TASKS.md`/`BOARD.md`/`ARCHITECTURE.md`。

**纯追加证明**（自指值不写死）：
- 追加前基线：`md5 1243a62b49cc508b72f1ab6b075aec50` / **3863 行**（落笔前 3×≥9s 取样不变 ⇒ 已静默）。
- 校验：`head -3863 docs/TEST_RESULT.md | md5sum` 应与上式一致（⇒ 纯追加）。
- 追加后全文 md5 / 行数：**以实时 `md5sum docs/TEST_RESULT.md` / `wc -l` 为准**（自指值不可写死）。

---

# `T-035` 修复复检证据（`TE-T035-001`）（2026-09-22）

> 独立复核方：**测试工程师**（席位 `te-verify-t035b`）｜ 时刻 `2026-09-22T20:2x+08:00`
> 对象：`be-t035fix` 对 P1 缺陷 `TE-T035-001` / `BUG-002` 的修复（**退回修复后的 delta 复检**，非重新全量复核）。
> 立场：**不复用产出方用例、不采信其自报数字**；变异/探针一律在隔离副本；**不修改任何交付制品**；`TASKS.md`/`BOARD.md`/`ARCHITECTURE.md` **零改动**；**不自评 `DONE`**。分栏「**独立实测**」vs「**产出方自述（本席未复现者标注）**」。

## T035vb.1 快照 / 锚点（独立实测）

| 项 | 独立实测值 | 与产出方自报 |
|---|---|---|
| 修复后 `cpp/src/risk_engine` 锚（`cwd=仓库根`，`find cpp/src/risk_engine -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum`） | **`35cf08187b4354df81aeed6cf1213342` / 29 文件** | ✅ **逐字节吻合** |
| 修复前 同口径锚（**本席独立重建**：隔离副本内回退本次三处改动 + 删新用例后重算） | **`fc086003667fe0a890a69358c3a51c56` / 28 文件** | ✅ 与产出方「改前」**逐字节吻合**，亦与上一轮复核章 `T035.0` 所述一致 |
| `risk_engine.cc` mtime | `2026-09-22 19:45:53`（开工/收工两次实测**未变**⇒复核期无并发写者） | —— |
| 构建环境（独立） | `cmake 4.2.3` / `g++ 15.2.0` / `RelWithDebInfo` / 系统 protobuf `3.21.12` / `CTP_FOUND=OFF` | —— |

- 本席工作区：共享树**只读**；基线构建 `/root/b-t035vb`；隔离副本 `/root/iso-t035vb` + 构建 `/root/iso-t035vb-build`；工作区 `/root/vb-probe-t035.cc`（自撰）。
- 共享树残留扫描（`*.orig` / `*.bak` / `*MUTATION*` / `*.rej`）：**0**（`find` 实测）。

## T035vb.2 独立实测基线（`/root/b-t035vb`，共享树源码；`-j1`）

| 项 | 独立实测 | 产出方自报 | 判 |
|---|---|---|---|
| 构建 | rc=0，`warning:`=**0**、`error:`=**0**（`-Wall -Wextra -Werror`） | rc 0 / 0 / 0 | ✅ 一致 |
| `ctest`（名单以 `ctest -N` 为准，Test #1..#32） | **32/32 通过**（含 #19 `close_exemption`） | 32/32 | ✅ 一致 |
| 新用例直接运行 | `risk_engine_close_exemption_test: 72 checks, 0 failures -> PASS` | 9 用例 / 72 断言 | ✅ 一致 |
| 聚合 `bash tools/arch-check/arch-check.sh` | rc=**0**；`E1`=0 `E2`=0 `E3`=0 `E4`=0 `E5`=0 `E6`=0 `E7`=0 | rc 0 / 全 0 | ✅ 一致 |
| `E4`（实扫） | 扫描根 = `order_engine,risk_engine,risk_state` **三目录并集**；**待扫描 46 文件、命中 0**；WARN 3（未覆盖判据 C/D 信息性） | 46 / 0 | ✅ 一致 |

## T035vb.3 缺陷确已消除 + 反向（防误伤）—— 本席自建探针

探针 `/root/vb-probe-t035.cc`（**不复用**产出方 `risk_engine_close_exemption_test.cc`；独立编译链接 `libhg_risk_engine.a` + `libhg_risk_state.a`）⇒ **94 checks, 0 failures -> PASS**。

| 场景（同一快照，仅改 `offset`） | 开仓 `kOpen` 实测 | 平仓 `kClose` 实测 | 判 |
|---|---|---|---|
| 层① `SUB_PRODUCT` 耗尽 | REJECT rule=2 `0x1004` layer=1 entries=1 | **PASS rule=0 entries=0** | ✅ 修复生效 |
| 层② `SUB_INSTRUMENT` 耗尽 | REJECT rule=2 `0x1017` layer=2 entries=1 | **PASS rule=0 entries=0** | ✅ |
| 层③ `ACCOUNT_PRODUCT` 耗尽 | REJECT rule=2 `0x1005` layer=3 entries=1 | **PASS rule=0 entries=0** | ✅ |
| 三层全耗尽 | REJECT rule=2 `0x1017` layer=2 entries=**3** | **PASS rule=0 entries=0** | ✅ 全序/`entries[]` 逻辑未动 |
| 层① + 方向=卖出（减仓） | REJECT rule=2 `0x1004` layer=1 | **PASS rule=0 entries=0** | ✅ |
| 其余四条需豁免规则（上限/规模/净持仓/期限） | 各自 REJECT + 正确 code | 各自 **PASS entries=0** | ✅ 未回归 |

**反向方向（防误伤 / 防多豁免）**：① 上表「开仓」列即证**开仓未被 `is_open` 短路放行**（三层耗尽、各层均仍拒）；② 「不豁免」三条在同等条件下**平仓也拒**：白名单（移出后）→ open&close **均** REJECT rule=1 `0x1001`；权限（无权限记录）→ 均 REJECT rule=7 `0x100B`；价格偏离（偏离 100%）→ 均 REJECT rule=8 `0x1010` ⇒ **无多豁免**。
> 补：`orderRequestInvalidReason`（`src/order_request.cc:13–17`）**先**校验 `offset ∈ {kOpen,kClose}` ⇒ **不存在「非法 offset 被当平仓豁免」的旁路**。

## T035vb.4 ★ 产出方「实测订正」独立复判（重要）

**待判命题**：编排者原派单转述「撤平仓豁免的变异**只在持仓上限/时点净持仓上红**」；产出方称该转述**不准**——`scale`（规模红线）与 `term`（期限红线）的**既有用例也会红（各 1）**，**真正无既有守卫的只有 `quota`**。

**本席做法**：隔离副本 `/root/iso-t035vb` 内**逐处**把 5 条豁免改为 `(void)is_open;`（撤该规则平仓豁免），**逐处**重建后跑**全量 `ctest`**，观察**既有用例**与**新用例**各自红/绿。

| 变异（`risk_engine.cc`） | 新用例 #19 | **既有用例红** | 既有守卫 |
|---|---|---|---|
| `:214` 撤 `quota` 豁免 | 红（15 断言） | **无** | **仅新用例** |
| `:266` 撤 `positionLimit` 豁免 | 红（3） | `#11 position_limit`、`#13 net_position` | 有 |
| `:291` 撤 `scale` 豁免 | 红（3） | **`#12 scale_redline`** | 有 |
| `:325` 撤 `netPosition` 豁免 | 红（3） | `#13 net_position` | 有 |
| `:368` 撤 `term` 豁免 | 红（3） | **`#14 term_test`** | 有 |

**结论：产出方的订正【正确】，编排者原转述【错误】（就 `scale`/`term` 而言）。**
- `scale` 与 `term` 的既有用例**确实各红 1 个**（`scale_redline_test` / `term_test`）⇒ 二者**本就有平仓豁免守卫**（上一轮复核章 `T035.7` 的 `MUT-B` **只**做了 `evalPositionLimit` 一处，未覆盖 `scale`/`term`，恐为原转述之来源）。
- **真正无既有守卫的只有 `quota`**：撤其豁免时**唯一变红的是新用例**（既有 11 个单测全绿 —— 与 `BUG-002` §「绿测为何未挡住」所述一致）。
- ⇒ 「原缺陷为何逃逸」应表述为：**`quota` 是 5 条需豁免规则中唯一缺既有反退化用例者**；而非「所有豁免都无守卫」或「只有上限/净持仓有守卫」。
- 辨析（录 `OBS-T035vb-2`）：`#13 net_position` 在撤 **`positionLimit`** 豁免时**也红** —— 因其 `test_close_is_exempt` 的持仓 `1e6` 恰等于 `happyBatch` 的持仓上限 `1e6`，平仓被 `R-RISK-3` 拦下 ⇒ 该既有用例的闭包**同时依赖两条豁免**（特异度问题，**非本修复缺陷**）。

## T035vb.5 新用例守卫强度（逐处移除变异 + 双向反证）

| 变异 | 目标 | 新用例 rc | 红点数 | 守的是行为？ |
|---|---|---|---|---|
| M1–M5 | 撤 5 条豁免（见 T035vb.4） | 红 | 15 / 3 / 3 / 3 / 3 | ✅ 断言 `decision==kPass` ∧ `rule==kNone` ∧ `entry_count==0`（**放行 + 无命中项**），纯行为 |
| **M6**（反向） | `:214` 改 `if (is_open) return;`（**开仓豁免、平仓受查**） | **红（35）** | 额外红 `#9 quota_layers`、`#17 double_layer` | ✅ 开仓被要求**必拒** ⇒ 新用例**双向承重** |
| **M8**（反向） | 调用点 `:552` 传常量 `false`（**开仓也豁免**） | **红（20）** | 额外红 `#9 quota_layers`、`#17 double_layer` | ✅ 既有用例**亦**守住「开仓不得被豁免」 |

- 复位（`cp orig → 源`）后**删除 `risk_engine` 目标文件树强制重编译** ⇒ 锚点回 `35cf08…`、`ctest` **32/32**、零告警（见 T035vb.8）。
- ⇒ 新用例**守行为而非实现细节**，且**两向**有效（既咬「平仓被误拒」，也咬「开仓被误放」）。

## T035vb.6 `8 规则「需否平仓豁免」`表逐条核（含「不豁免」3 条）

| 规则（`ARCH §3.3` 规则集 / 代码 `RuleId` 口径） | 产出方判 | 本席据需求复核（现址锚点） | 判 |
|---|---|---|---|
| `R-RISK-2` 三层限额 | 需豁免 | `R-RISK-5`（`RISK.md:397`）明列「**额度阻断**」属限制类处置、**一律不得阻断平仓** | ✅ |
| `R-RISK-3` 持仓上限 | 需豁免 | `FR-RISK-3`（`:133`「平仓指令**不受持仓上限限制**」/`:140` 验收2） | ✅ |
| `R-RISK-4` 规模红线 | 需豁免 | `R-RISK-4`（`:396`「阻断新开仓（平仓与风险处置**不受限**）」）/`FR-RISK-4`（`:148`） | ✅ |
| `R-RISK-5` 时点净持仓 | 需豁免 | `FR-RISK-5`（`:163`/`:171` 验收3「**平仓方向不受限**」）；`ARCH §3.3:444`「减仓方向不受限」 | ✅ |
| `R-RISK-6` 期限红线 | 需豁免 | `FR-RISK-6`（`:178`/`:186`「**不阻断平仓与展期置换**」）；`ARCH §3.3:445` | ✅ |
| `R-RISK-1` 白名单 | **不需** | `FR-RISK-1`（`:102`/`:107–111`）**无平仓例外**；`R-RISK-5` 枚举的三类处置**不含**白名单；`FR-PRODUCT-12` 验收2（`PRODUCT.md:258`）「移除白名单时有未平仓持仓 ⇒ 系统拒绝」**实测确如产出方所述** ⇒ 场景被前置阻断 | ✅ 不需 |
| `R-RISK-7` 权限与授权 | **不需** | `FR-RISK-7`（`:194`）「**未授权、超品种、超额度一律拒绝**」**无平仓例外**；`ARCH §3.11:562` 的 `scope=OPEN_ONLY` 确指**事中「限制新开仓」处置**（`CMD_SET_TRADING_SUSPEND`），**非身份/授权校验** | ✅ 不需 |
| `R-RISK-8` 价格偏离 | **不需** | `FR-RISK-8`（`:209`）唯一不适用情形 =「**市价单不适用**」；`R-RISK-8`（`:400`）目的 =「防止误报与异常报单」⇒ 报单合理性校验、非限制类处置 | ✅ 不需 |

**核结论**：「**5 需豁免 / 3 不需**」**分类正确**；实现与分类**双向一致**（需豁免者逐条实测放行；不需者逐条实测平仓仍拒 —— 见 T035vb.3）。
> 两点**措辞 / 登记级**观察（非缺陷）：
> - `OBS-T035vb-1`：产出方表头用 `R-RISK-1~8`（**`ARCH §3.3` / 代码 `RuleId` 口径**，`R-RISK-1`=白名单）；但 `RISK.md §4` 的 `R-RISK-n` **另有编号**（`R-RISK-1`=六机制映射、`R-RISK-3`=全内存零 DB、`R-RISK-5`=只限开仓原则）⇒ 同一编号在两制品指不同事物；建议以 FR 号或注明口径消歧（**既有**制品约定分歧，非本修复引入）。
> - `OBS-T035vb-3`：产出方「同类扫描」表中 `evalPermission`/`evalPrice` 行区间（`:391–438`/`:443–467`）与实测现址（`:395–442`/`:447–471`）**偏移 4 行**（`evalNetPosition`/`evalTerm` 差 1）；`evalQuota`/`positionLimit`/`scale`/`whitelist` 区间吻合。仅定位精度，不影响结论。

## T035vb.7 既有断言未削弱核查

- **本轮（修复轮，`mtime ≥ 19:45:53`）实际改动**（`mtime` + 反向 diff 实测）：`src/risk_engine.cc`（核心修复）、`include/.../order_request.h`（**仅注释**：反向 diff 逐行均为 `//` 行）、`CMakeLists.txt`（**仅新增** 1 行用例注册）、`tests/risk_engine_close_exemption_test.cc`（**新增**）。**无任何既有测试文件的断言被改动 / 放宽**（既有 11 个 `risk_engine_*_test.cc` 的 `mtime` 均 ≤ 15:49，早于修复轮）。
- **`git diff HEAD` 的额外条目归属**：另含 `risk_engine.h`（`applySnapshot(const RiskState&)` 重载 + `guard_`）、`risk_engine_purity_test.cc`、`bench_risk_check.cc`、`risk_engine_test_util.h`（`st.snapshot()`→`st.acquire().get()` 等）—— **实测 `mtime` 15:43–15:49，属 `T-035` 前一轮交付（早于本修复约 4 小时）**，非本轮改动；其改动为 RCU 强保护接线（**断言未放宽**）。录 `OBS-T035vb-4`（提示：直接 `git diff HEAD` 会看到比修复单「涉及文件」更多的条目，勿误归本修复）。
- **无签名 / ABI 破坏**：`evalQuota` 属**匿名命名空间**内部函数，参数变更不触头文件 ABI；`risk_engine.h` 本轮未改。

## T035vb.8 回归 / 门禁（独立实测）

- 构建：`cmake -S cpp -B /root/b-t035vb && cmake --build /root/b-t035vb -j1` ⇒ rc=0、`warning:`=0、`error:`=0。
- `ctest`（名单以 `ctest -N` 为准，**非** grep 计数）：**32/32**，含 #19 新用例。
- 聚合 `arch-check.sh`：rc=0；逐条 `E1`=0 `E2`=0 `E3`=0 `E4`=0（**实扫 46 文件 / 命中 0**）`E5`=0 `E6`=0 `E7`=0。
- 隔离副本复位后（强制重编译 `risk_engine`）：锚 `35cf08187b4354df81aeed6cf1213342` / 29 文件；`ctest` **32/32**；`warning:`=0。
- 共享树残留扫描：0。

## T035vb.9 缺陷 / 观察清单

| 编号 | 严重度 | 类别 | 结论 |
|---|---|---|---|
| —— | —— | 缺陷 | **无**（修复有效；未发现新增缺陷；**未见多豁免 / 误差伤**） |
| `OBS-T035vb-1` | 低 | 登记口径 | `R-RISK-n` 在 `RISK.md §4` 与 `ARCH §3.3`/代码**含义不同** ⇒ 建议消歧（既有分歧） |
| `OBS-T035vb-2` | 低 | 既有用例特异度 | `net_position_test::test_close_is_exempt` 的闭包**同时依赖** `R-RISK-3` 与 `R-RISK-5`（持仓恰等于上限）⇒ 非最小对偶；本修复新增 `close_exemption_test` 已给**逐条最小对偶**，覆盖未失 |
| `OBS-T035vb-3` | 低 | 定位精度 | 产出方「同类扫描」表 `evalPermission`/`evalPrice`（及 `evalNetPosition`/`evalTerm`）行区间与现址偏移 ≤4 行 |
| `OBS-T035vb-4` | 低 | 归属提示 | `git diff HEAD` 另含前一轮交付的 `risk_engine.h`/`purity`/`bench`/`test_util` 改动，勿误归本修复轮 |
| `OBS-T035vb-5` | 低 | 文档一致性 | `ARCHITECTURE.md §3.3` 的 `R-RISK-2` 行**未**如 `R-RISK-3/4/5` 标注「平仓不受限」；修复依据为 `R-RISK-5`（需求）**已足够**，但架构规则表可就该点补齐（属架构席维护域，本席未改） |

## T035vb.10 结论

**结论：`通过`。**

- **缺陷已消除**：额度三层耗尽时合法平仓**逐层放行**（开仓仍拒）—— 本席自建探针 94/94 独立证实。
- **无多豁免 / 无误伤**：白名单 / 权限 / 价格偏离三条平仓**仍拒**；开仓在三层耗尽下**仍拒**。
- **§2.4 归因订正**：产出方**成立** —— `scale`/`term` 既有用例**亦红**、唯一无既有守卫者为 `quota`；编排者原转述**不准**（见 T035vb.4）。
- **8 规则豁免表**：分类（5/3）与依据**逐条正确**；实现双向一致。
- **守卫强度**：新用例**双向承重**（M1–M8 逐处 / 反向变异全红）；**既有断言未被削弱**。
- **回归**：`ctest` 32/32、聚合 rc 0、`E1`–`E7` 全 0、`E4` 实扫 46/0、构建 0 warning / 0 error。
- **锚点**：修复后 `35cf08…`/29（与自报逐字节吻合）；修复前同口径 `fc086003…`/28（**本席独立重建复现**）。
- **本席不置 `DONE`**（门禁结论由编排者落）；`TASKS.md`/`BOARD.md`/`ARCHITECTURE.md` 零改动；未修改任何交付制品。

**纯追加证明**（自指值不写死）：
- 追加前基线：`md5 b4b47f6a9c72a56e5a0f68f6a2137732` / **3983 行**（连续 3 次 ×10s 取样不变）。
- 追加后：`head -3983 docs/TEST_RESULT.md | md5sum` = `b4b47f6a9c72a56e5a0f68f6a2137732`（⇒ 前 3983 行**逐字节未变**）；`wc -l` 与 `md5sum docs/TEST_RESULT.md` 以**实时**为准。

### T035vb.11 追加：`R-RISK-7` 的 `individual_quota` 子项平仓行为（应编排者点询）

探针 `/root/vb-probe-t035-r7.cc`（自撰）：`PermissionEntry{allow_all_sub_accounts=true, allow_all_products=true, has_trading_post=true, individual_quota=1}`，`volume=10000`。
- 开仓：`REJECT rule=7 code=0x100D layer=4(kAuthQuota) entries=1`
- 平仓（仅改 `offset=kClose`）：**同 `REJECT`（**未**豁免）**

⇒ **现状：`R-RISK-7`（含 `individual_quota` 子项）平仓不豁免** —— 与产出方「不需豁免」一致；依据 `FR-RISK-7`（`RISK.md:194`）「**未授权、超品种、超额度一律拒绝**」（**无**平仓例外）。
> `OBS-T035vb-6`（低，建议产品确认）：「单笔授权额度」（`0x100D` / `kAuthQuota`）字面含「额度」，**若**产品视其为 `R-RISK-5` 的「额度阻断」，则须豁免平仓；但 `FR-RISK-7` 明文「一律拒绝」无例外、且 `R-RISK-5` 枚举的三类处置指向三层限额（`FR-RISK-2`）⇒ 现实现**忠实于 `FR-RISK-7`**，判**非缺陷**；是否需产品口径澄清由产品/架构席裁定。

> **纯追加（第二次）**：追加前 `md5 4d1716284eae5a2310771d339ba0e063` / 4120 行；`head -4120 | md5sum` = `4d171628…`（前 4120 行未变）；追加后 `wc -l` 与 `md5sum` 以实时为准。

# `T-124` 独立复核证据（2026-09-22）

> 复核方：**测试工程师（独立复核，非产出方）** ｜ 实例 `te-verify-t124` ｜ 时刻 `2026-09-22T21:17+08:00`
> 立场：本项目 `DONE` 判据 = **授权 + 独立验证**；产出方一切自报视为**待证命题**。本席**未复用产出方用例/探针**做竞争判据（自撰探针 `/root/t124v/probe/t124_probe.cc`）；全文分栏「**独立实测**」与「**产出方自述（本席未复现者单列）**」。
> 未修改任何交付制品；**共享源树只读**（`cpp/src/risk_state` `git status` 空）；变异/回退**一律在隔离副本** `/root/t124v`；**不自评 `DONE`**。工具链：`g++ 15.2.0` / `cmake 4.2.3` / TSAN `libtsan.so.2`。

## T124.0 结论

**结论：`通过`（0 阻断缺陷；4 项信息/低观察，见 T124.9）。**

`T-124` 交付物经**独立取证**证实：(1) 改前两处竞争**确实存在**（TSAN 复现，方位与 `te-verify-t034` 的 `O-1`/`O-3` 逐位吻合）；(2) 改后**TSAN 干净**；(3) **反退化三态各自承重**（1/2/3 条告警，与「两处各自被守卫」一致）；**附加变异**证实 seqlock 逻辑守卫亦承重（去复检 ⇒ TSAN 空转但 `torn>0`）；(4) `E4` 两口径 `rc 0`、**无新增锁/互斥量/分配/同步 I/O**；(5) `ctest` 名单与新增用例**确在跑**；(6) **既有断言未被削弱**。验收标准 1(a)（无锁发布）+ 2（TSAN 干净判据）+ 3（`E4` 复跑 / `ctest` 不回归）+ 4（交付面 `cpp/src/risk_state/**`）**全部满足**。

**产出方自报逐项比对**（本席独立实测）：

| 项 | 产出方自述 | 本席独立实测 | 一致 |
|---|---|---|---|
| 冻结点锚（`cwd=仓库根`；`find cpp/src/risk_state -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum`） | `d89387cb27c9c81f9218dbdf85587e5f` / 18 文件 | **`d89387cb27c9c81f9218dbdf85587e5f` / 18 文件**（12/12 次取样一致） | ✅ |
| 改前同口径 | `2a6eea7a…` / 17 文件 | **`2a6eea7afcf0f63bc440fa9c5f5844a2` / 17 文件**（`git 0b83114` = `T-034` 交付态） | ✅ |
| TSAN 干净（`2003 checks, 0 failures`，WARNING 0） | 是 | **是**（自撰探针 6 跑全 0 告警；另跑产出方用例 `2003/0/PASS`、WARNING 0） | ✅ |
| 反退化三态（全回退 3 / 仅 `last_result` 1 / 仅 `stats` 2） | 是 | **是**（各跑 3 次稳定：3 / 1 / 2，见 T124.4） | ✅ |
| `ctest` 33/33 | 是 | **33/33 全过**（隔离全量构建，`-j1`；含 `arch_check` fixture + #25 `risk_state.diagnostic_accessor_test`；见 T124.6） | ✅ |
| 构建 0 warning / 0 error（全项目） | 是 | **是**（`grep -icE 'warning:' build.log = 0`、`'error:' = 0`，`rc 0`） | ✅ |
| risk_state 五单测合计 checks | 2554 / 0 fail | **`70+418+39+24+2003 = 2554` / 0 fail** | ✅ |
| `E4` 两口径 | `N=17` 单根 / `N=47` 默认并集；命中 0；WARN 3 | **17 / 47；命中 0；WARN 3（与改前逐条相同）**；聚合 `arch-check rc 0` | ✅ |
| 锚点离群 `82b2e677…` | 一次性取样、判为测量假象 | **不可复现**（见 T124.1）；现锚点稳定；**无并发写入证据** | ✅（成因不可判定） |

## T124.1 锚点独立确认（≥10 次采样）＋离群值查清

**★冻结点独立确认**（`cwd=仓库根`，同口径命令）：

```
sample,timestamp,anchor,files
1,2026-09-22T13:02:26Z,d89387cb27c9c81f9218dbdf85587e5f,18
...
12,2026-09-22T13:03:23Z,d89387cb27c9c81f9218dbdf85587e5f,18
```

**12/12 次采样（间隔 5s、跨 58s）** 全部 `d89387cb27c9c81f9218dbdf85587e5f` / **18 文件**。改前同口径 = `2a6eea7afcf0f63bc440fa9c5f5844a2` / 17（+1 = 新增 `tests/diagnostic_accessor_test.cc`）。`git HEAD = b4cc2f5`。

**离群值 `82b2e677…` 查清（结论：不可复现的一次性测量假象，非并发写入）**：

- **构造法证伪**：对 4 个实现文件（`risk_state.h`/`snapshot_manager.h`/`risk_state.cc`/`snapshot_manager.cc`）做 **16 种回退子集** 扫描，**无一**得 `82b2e677`；再叠加 `CMakeLists` / 新增测试在否 → **64 种组合**，仍 **0 命中**（`da741de5`/`8883093c`/`82b2e677` 均无）；另测 3 种「瞬时备份文件」态（`*.orig`）= `c04bf648…`/`c500dd92…`/`58c27470…`，亦无。
- **测量口径法证伪**：`cwd=cpp`（路径 `src/risk_state/…`）= `9212793b…`（18 文件）、`cwd=cpp/src/risk_state`（路径 `./…`）= `82014ff1…`；`82b2e677` 不对应任何口径。
- **静息证据**：18 个文件 `mtime == ctime`，最后一次改动 `2026-09-22 20:35:34 +0800`（`CMakeLists.txt`）；采样期（21:0x）**无任何进程在写 `risk_state`**；共享树残留扫描（`*.orig`/`*.bak`/`*MUTATION*`）= **0**。
- **结论**：`82b2e677…` **不对应任何本席可构造的树状态**，且冻结点自 `20:35:34` 起静息 ⇒ 判**一次性测量假象**（最可能为产出方清理期自身的瞬时文件态/非标准取样口径），**非外部并发写入**。**具体成因本席不可判定**（不臆测）。

## T124.2 改前「确实是坏的」——TSAN 原文（探针自撰）

- **被测源**：`/root/t124pre/cpp/src/risk_state/**`（**严格等于改前锚 `2a6eea7a…`/17**，取自 `git 0b83114`）。
- **编译**：`g++ -std=c++11 -fsanitize=thread -g -O0 -Wall -Wextra -I<inc> probe/t124_probe.cc src/*.cc -o probe_pre -pthread` → `rc 0`、0 告警。
- **运行**：`TSAN_OPTIONS='halt_on_error=0' ./out/probe_pre 3000 3` ⇒ **`rc=66`、WARNING = 3**（**4 次运行均 3**）。

原始输出（节选，两条方位）：

```
WARNING: ThreadSanitizer: data race (pid=118854)
  Write of size 8 at 0x7fffffffd7a0 by main thread:
    #1 std::vector<...Retired...>::push_back(...)
    #2 hg::risk_state::SnapshotManager::publish(...) snapshot_manager.cc:44      ← 写（O-3）
  Previous read of size 8 at 0x7fffffffd7a0 by thread T1:
    #0 std::vector<...Retired...>::size() const
    #1 hg::risk_state::SnapshotManager::stats() const snapshot_manager.cc:74     ← 读（O-3）
SUMMARY: ThreadSanitizer: data race snapshot_manager.cc:44 in ...publish(...)
---
WARNING: ThreadSanitizer: data race (pid=118854)
  Write of size 8 at 0x7fffffffd7d0 by main thread:
    #0 hg::risk_state::RiskState::rebuild(...) risk_state.cc:69                  ← 写 `last_ = r`（O-1）
  Previous read of size 8 at 0x7fffffffd7d0 by thread T1:
    #0 hg::risk_state::RiskState::last_result() const risk_state.h:63            ← 读 `last_`（O-1）
SUMMARY: ThreadSanitizer: data race risk_state.cc:69 in ...rebuild(...)
```

⇒ **方位与 `te-verify-t034` 的 `O-1`（`risk_state.h:63` vs `risk_state.cc:36`/`:69`）与 `O-3`（`snapshot_manager.cc:44` vs `:74`）逐位吻合**；竞争确为真（**非用例/探针不承重**）。

## T124.3 改后「确实是好的」——TSAN 干净

- **被测源**：共享树 `/root/myspace/hedge-guard/cpp/src/risk_state/**`（= 冻结点 `d89387cb…`/18）。
- **命令**：`TSAN_OPTIONS='halt_on_error=0' ./out/probe_fixed 3000 3`（同探针）。
- **完整输出**（6 次运行，逐次）：

| run | rc | WARNING | 探针摘要 |
|---|---|---|---|
| 1 | 0 | **0** | `iters=3000 readers=3 reads=95466 torn=0 publish_regress=0` |
| 2 | 0 | **0** | `reads≈…` |
| 3 | 0 | **0** | `…` |
| 4 | 0 | **0** | `reads=142680 torn=0 publish_regress=0` |
| 5 | 0 | **0** | `reads=4190 torn=0 publish_regress=0` |
| 6 | 0 | **0** | `reads=5283 torn=0 publish_regress=0` |

⇒ **WARNING = 0、rc = 0**（无一行 `ThreadSanitizer`）。另**独立执行产出方用例**（TSAN 构建）`diagnostic_accessor_test: 2003 checks, 0 failures -> PASS`、WARNING 0（**佐证**，非本席主证据）。

## T124.4 ★反退化双向（三态各自承重）——隔离副本

**方法**：在 `/root/t124v/variants/{lastonly,statsonly,all4}` 复制冻结点，**逐项回退** 4 个实现文件至改前内容（其余保持冻结点，含新增测试与 `CMakeLists`）；**每次从源码全量重编译**（无陈旧对象文件），同一自撰探针。**各跑 3 次**：

| 态 | 回退的文件 | 期望 | 本席实测（3 次） | 方位 |
|---|---|---|---|---|
| `lastonly` | `risk_state.h` + `risk_state.cc` | 1 | **1 / 1 / 1** | `risk_state.cc:69` ↔ `risk_state.h:63`（`O-1`） |
| `statsonly` | `snapshot_manager.h` + `.cc` | 2 | **2 / 2 / 2** | `snapshot_manager.cc:44` ×2 ↔ `:74`（`O-3`） |
| `all4` | 4 个实现文件 | 3 | **3 / 3 / 3** | 上两者合并 |

⇒ **两处守卫各自承重**（无「仍绿 ⇒ 空转」）；计数与 `te-verify-t034` 的 `O-1`/`O-3` 一致。**回退后残留复位校验 = 0**（隔离副本外无物）。

**★附加变异（本席自设，超派单要求）—— 隔离 seqlock「逻辑」守卫**：把 `last_result()` 的**前后 `seq_` 一致性复检**去掉（各字段仍为原子存取，**故 TSAN 必空**），同探针 3 跑：

| 态 | TSAN WARNING | `torn`（自洽性违例） |
|---|---|---|
| 冻结点 | 0 | **0** |
| `nolastcheck`（去复检） | **0（TSAN 空转）** | **24 / 44 / 6** |

⇒ 两点：① **`torn` 检查确为承重**（能捕获被去掉的守卫）；② **「TSAN 干净」单独不足以验证 seqlock**（全原子时 TSAN 必绿）——须配合自洽性断言，本交付**两者兼备**。

## T124.5 `E4` 两口径 + 无锁/无分配/无同步 I/O

**本席实测**（共享树，`cwd=仓库根`）：

| 口径 | 命令 | 待扫描文件 | 命中 | WARN | rc |
|---|---|---|---|---|---|
| 单根 | `scan_lock_free.sh --root cpp/src/risk_state` | **17** | 0 | **3** | **0** |
| 默认并集根 | `scan_lock_free.sh` | **47** | 0 | **3** | **0** |
| 改前同根 | `scan_lock_free.sh --root /root/t124pre/...` | 16 | 0 | **3** | **0** |
| 聚合 | `arch-check.sh` | — | — | — | **0**（`E1`–`E7` 全 0） |

- **WARN 3 逐条核对**：`risk_snapshot.cc:231`、`risk_state.cc:22`、`risk_state.cc:41` —— **全为既有 `new`**，**改前改后逐条相同 ⇒ 无新增**。
- **`E4` 关切面核对（代码级）**：改动仅新增 `std::atomic`（`DiagnosticSlot` 5 字段 + `retired_count_`）与 2 处 relaxed `store` / 4 处 RMW；**未引入 `mutex`/`condition_variable`、未新增动态分配、未新增同步 I/O**（`E4` 未覆盖的判据 C「无动态分配」由代码审查兜底：`publishLastResult()`/`last_result()`/`stats()` 均**零分配**）。
- **热路径零影响**：`snapshot()`/`peek()`/`enter()` 读路径与 `published_` 原子替换**未改一行**（见 T124.7 diff）；改动只在非热路径诊断访问器与其写侧。

## T124.6 用例数以 `ctest -N` 为准 + 新增用例真在跑

- **`ctest -N`（隔离全量构建 `/root/t124v/full-build`）= 33**，其中 **`Test #25: risk_state.diagnostic_accessor_test`**。risk_state 段：#21–#25。
- **risk_state 5 单测独立复跑**（自撰命令，非 ctest，`-O2 -Wall -Wextra -Werror`，`rc 0`）：

| 用例 | checks / failures | rc |
|---|---|---|
| `risk_state.data_model_test` | 70 / 0 | 0 |
| `risk_state.snapshot_manager_test` | 418 / 0 | 0 |
| `risk_state.risk_state_test` | 39 / 0 | 0 |
| `risk_state.rsk04_conservative_test` | 24 / 0 | 0 |
| `risk_state.diagnostic_accessor_test` | **2003 / 0** | 0 |
| 合计 | **2554 / 0** | — |

⇒ 新增用例**确在跑**且通过；`2003` 与产出方自报一致。

**全量 `ctest` 端到端复跑（隔离构建 `/root/t124v/full-build`，`-j1`，`rc 0`）**：

```
1/33 Test  #1: arch_check .....................................   Passed   18.55 sec
...
21/33 Test #21: risk_state.data_model_test ....................   Passed    0.01 sec
22/33 Test #22: risk_state.snapshot_manager_test ..............   Passed    0.04 sec
23/33 Test #23: risk_state.risk_state_test ....................   Passed    0.01 sec
24/33 Test #24: risk_state.rsk04_conservative_test ............   Passed    0.03 sec
25/33 Test #25: risk_state.diagnostic_accessor_test ...........   Passed    1.15 sec
...
100% tests passed, 0 tests failed out of 33
Total Test time (real) =  22.70 sec
```

⇒ **33/33 全过**（含 `arch_check` fixture 与新增用例）；构建日志 `warning:` **0** / `error:` **0**。**产出方「`ctest` 33/33、构建 0 warning / 0 error」自报属实**。

## T124.7 断言未被削弱（`git diff 0b83114 HEAD` 逐处）

**改动面（6 文件）**：`CMakeLists.txt`（+1 测试名）、`risk_state.h`（文件头注释改写 + `last_result()` 声明化 + `DiagnosticSlot` + `publishLastResult` + `last_` 类型）、`snapshot_manager.h`（`retired_pending()` 改读原子 + 新 `retired_count_`）、`risk_state.cc`（3× `last_ = r` → `publishLastResult` + 新增两函数）、`snapshot_manager.cc`（计数初始化/更新/归零）、`tests/diagnostic_accessor_test.cc`（**新增**）。

- **既有测试零改动**：`git diff --name-status 0b83114 HEAD -- cpp/src/risk_state/tests/` = **仅 `A diagnostic_accessor_test.cc`**（+116）；**无任何既有测试文件被修改/断言放宽**。
- **新增测试确为并发**（读其代码，`tests/diagnostic_accessor_test.cc`）：`const int kReaders = 3;`（`:71`）→ 3 个 `std::thread` 在**写者循环之前**启动（`:74`–`:90`，循环体 `:77`–`:89` 反复调 `st.last_result()`/`st.stats()`）；写者交错发布成功/失败两种批次（`:94`–`:102`）；`join`（`:104`）。**非「主线程顺序读」恒真形态**。
- **热路径读侧未改**：`snapshot()`/`acquire()`/`ready()`/`param_version()` 一字未动。

## T124.8 探索性发现

- **`last_result()` 的 seqlock 内存序（信息级）**：读者以 `acquire` 载入前后 `seq_`、`relaxed` 读数据字段，**未在第二次 `seq_` 载入后加显式 `acquire` 栅栏**（教科书 seqlock 写法）。在目标平台 **x86-64（TSO）** 下：load 不与 load 重排、写者第二次 `fetch_add` 为全栅栏 RMW ⇒ **实际正确且 TSAN 干净**；仅对弱序 ISA（ARM 等）理论上需补栅栏。**非本平台缺陷**，登记备参。
- **`last_result()` 自旋无 `pause`/`yield`（信息级）**：若写者在写入窗口（`seq_` 为奇）被抢占/死亡，读者将忙等。窗口极短 + 非热路径 ⇒ **实际可忽略**，属 seqlock 固有性质（实现注释已载）。
- **新测试的竞争检出面（信息级，经变异厘清）**：普通（非 TSAN）构建下该用例**不检出改前的数据竞争**（本席改前探针 `torn=0` —— 竞争窗口小于撕裂窗口，用户例的 `torn==0` 改前亦绿）。**但**它对 **seqlock 逻辑**是承重的（见 T124.4 附加变异：去 `seq_` 复检 ⇒ `torn` 24/44/6 ⇒ 该用例会红）。⇒ 该用例 = **seqlock 逻辑回归网 + 并发冒烟**；**数据竞争判据只在 TSAN 下成立**（产出方已于用例头 `:8`–`:10` 如实披露）。**非缺陷**，登记以免下游误以为 `ctest 33/33` 本身即竞争回归网。
- **产出方的两个辅助锚值不可复现（信息级）**：其备注所载「原始 17 文件 = `8883093c…`」「4 实现回退 + 我的测试 = `da741de5…`」，本席按**仓库根口径**实测得 `2a6eea7a…`（= 改前 17）与 `8f8cf018e2c68ac4ed118c0eb1c3a551`（= 4 回退 + 新增测试 18），**均不符**；亦不匹配任何 cwd 变体。⇒ 其辅助取样疑用非标准口径。**不影响冻结点**（`d89387cb…` 复现一致），但削弱「`82b2e677` 不对应任何已知部分态」这句的取证力。

## T124.9 缺陷 / 观察清单

| 编号 | 严重度 | 类别 | 结论 |
|---|---|---|---|
| —— | —— | 缺陷 | **无**（修复有效；未发现新缺陷） |
| `OBS-T124-1` | 低 | 取证口径 | 产出方两辅助锚值（`8883093c`/`da741de5`）按标准口径**不可复现** ⇒ 辅助取样口径存疑（冻结点不受影响） |
| `OBS-T124-2` | 信息 | 锚点离群 | `82b2e677…` 不可复现、无并发写入证据 ⇒ 判一次性测量假象；**具体成因不可判定** |
| `OBS-T124-3` | 信息 | 内存序 | `last_result()` seqlock 未在二次 `seq_` 载入后加 `acquire` 栅栏；**x86-64 下无害**，弱序 ISA 备参 |
| `OBS-T124-4` | 信息 | 回归强度 | 新测试的**竞争**检出**只在 TSAN 下成立**（普通 `ctest` 改前亦绿）；但对 **seqlock 逻辑**承重（附加变异 `torn>0`）。产出方已披露 |

## T124.10 未决 / 存疑

- **`ctest` 全量 33/33 已端到端复跑成功**（T124.6），无存疑。
- 产出方**未**在 `ARCHITECTURE.md §4.4.3` 登记本次无锁发布（其裁定：属实现内部改动、`docs/**` 非其写权）—— 本席核 §4.4.3 原文**未描述**该两诊断访问器，**改动不构成对已载条文的矛盾**，裁定**认可**。
- **本席不置 `DONE`**（门禁结论由编排者落）；`TASKS.md`/`BOARD.md`/`ARCHITECTURE.md` **零改动**；未修改任何交付制品。

## T124.11 纯追加证明（自指值不写死）

- 追加前基线：`md5 36297e76ecd5ece5a7c167a684e0b68d` / **4131 行**（紧邻写入前 **3 次 ×10s** 取样不变）。
- 追加后：`head -4131 docs/TEST_RESULT.md | md5sum` = `36297e76ecd5ece5a7c167a684e0b68d`（⇒ 前 4131 行**逐字节未变**）；追加后 `wc -l` 与 `md5sum docs/TEST_RESULT.md` **以实时为准**（写死会因追加本身而失效）。

---

# `T-126` 独立复核证据（2026-09-22）

> 复核方：**测试工程师（独立复核，非产出方）** ｜ 实例 `te-verify-t126` ｜ 时刻 `2026-09-22T22:04+08:00`
> 立场：本项目 `DONE` 判据 = **授权 + 独立验证**；产出方一切自报视为**待证命题**。本席**未复用产出方用例/探针**（自撰探针 `/root/t126-probe-post.cc`、`/root/t126-probe-plain.cc`）；全文分栏「**独立实测**」与「**产出方自述（本席未复现者单列）**」。
> 未修改任何交付制品（本证章对本文件为**纯追加**，见 T126.11）；**共享源树只读**（未写一字节）；变异/回退**一律在隔离副本**（`/root/t126v`、`/root/t126v-pre`、`/root/t126v-preconsume`、`/root/t126v-mut1..3`）；**不自评 `DONE`**。工具链：`g++ 15.2.0` / `cmake` / `ctest`；构建目录与 `TMPDIR` 一律置 `/`（`/tmp` 为 804M tmpfs ⇒ 满）、`-j1`。

## T126.0 结论

**结论：`通过`（0 阻断缺陷；3 项信息/低观察，见 T126.9）。**

`T-126`（`PermissionEntry` 岗位维度承载补齐 + 引擎 `evalPermission` 消费）经**独立取证**证实：
(1) **改前确实是坏的** —— 改前锚下两字段无载体（编译即失败），消费侧仅回退即复现 **`仅具结算岗 → kPass/0x0000`（放行）**、**`仅具管理层 → kPass/0x0000`（放行）**；
(2) **改后确实是好的** —— 冻结结点下 **`仅具结算岗 → kReject/0x100E`**、**`仅具管理层 → kReject/0x100F`**、**`普通交易岗 → kPass/0x0000`（放行不被误伤）**；
(3) **守卫确实会咬** —— 两分支**各自独立承重**（移除任一支 ⇒ 对应用例必红，无空转）；
(4) `E4` 两口径 `rc 0`、**无新增锁/互斥/同步 I/O/分配**；聚合 `rc 0`（`E1`–`E7` 全 0）；
(5) `ctest` **本树 38/38 全绿**、构建 **0 warning / 0 error**；新增 3 用例**确在跑**；
(6) **既有断言未被削弱**（无断言依赖被改写的 `detail` 串）；
(7) **口径边界表述属实**：该两字段**除测试外无任何生产加载点**（恒缺省 `false`）—— 产出方主动披露**正确**。

**验收标准逐条**（`docs/TASKS.md` `T-126`）：① 承载补齐 ✅ ｜ ② 加载侧（已按架构席裁定 ⑤ 分流 `T-038`/`T-042`，本单不落）✅ ｜ ③ 引擎消费（仅结算岗/仅风控岗**两个独立用例**齐备）✅ ｜ ④ 反退化（移除消费 ⇒ 对应用例必红）✅ ｜ ⑤ 门禁（0 告警 / `ctest` 全绿 / 聚合 `rc 0` / `E4` 命中 0）✅。

**产出方自报逐项比对**（本席独立实测）：

| 项 | 产出方自述 | 本席独立实测 | 一致 |
|---|---|---|---|
| 改动面 | 4 文件 / +77 −3 | **4 文件 / `git diff --shortstat 0e0d192 65f010f` = 77 insertions(+), 3 deletions(-)** | ✅ |
| 冻结点 `risk_state` | `b47c2fd3…`/18 | **`b47c2fd38cac4e369644f9c8dcdaecc6` / 18**（3/3 次取样一致） | ✅ |
| 冻结点 `risk_engine` | `24df98dc…`/29 | **`24df98dcb72799c634fc53bb46d4823c` / 29**（3/3 次取样一致） | ✅ |
| 改前同口径 | `d89387cb…`/18、`35cf0818…`/29 | **`d89387cb27c9c81f9218dbdf85587e5f`/18、`35cf08187b4354df81aeed6cf1213342`/29**（= `git 0e0d192`，即 T-126 前态） | ✅ |
| 改前缺陷复现（`仅结算岗 → kPass/0x0000`） | 是 | **是**（本席自撰探针；另 `仅管理层 → kPass/0x0000`） | ✅ |
| 反退化三态 | `M-SETTLE` 2 红 / `M-MGMT` 2 红 / `M-FULL` 4 红 | **是**（`:88/:89`、`:123/:124`、四红；见 T126.4） | ✅ |
| `ctest` | **33/33** | **本树 38/38 全绿**（数量差 = 冻结时点后**并发 T-033 `ctp_adapter` 移植新增用例**，非本单引入；见 T126.6 + `OBS-T126-1`） | ⚠️（数量，非实质） |
| 构建 0 warning / 0 error | 是 | **是**（全量构建 `grep -icE 'warning:' = 0`、`'error:' = 0`，`FULL_RC=0`） | ✅ |
| `E4` 实扫 47 / 命中 0（WARN 3 既有） | 是 | **是**（默认并集根 47 文件 / 命中 0 / WARN 3；改前同根 WARN 逐条相同 ⇒ 非本次引入） | ✅ |
| 聚合 `rc 0`（`E1`–`E7` 全 0） | 是 | **是** | ✅ |
| 「两字段无生产加载点」 | 是 | **是**（见 T126.8） | ✅ |
| 「details 字符串无测试断言」 | 是 | **是**（见 T126.7） | ✅ |
| 副本 4 文件与共享树逐字节一致 | 是 | **是**（4/4 `MATCH` vs `git 65f010f`） | ✅ |

## T126.1 锚点独立确认（3 次取样）＋「隔离等价于共享交付态」

**命令**（`cwd = 仓库根`，与产出方同口径）：`find <dir> -type f | LC_ALL=C sort | xargs md5sum | md5sum`。

| 取样 | `risk_state`（POST） | 文件数 | `risk_engine`（POST） | 文件数 |
|---|---|---|---|---|
| 1 | `b47c2fd38cac4e369644f9c8dcdaecc6` | 18 | `24df98dcb72799c634fc53bb46d4823c` | 29 |
| 2 | `b47c2fd38cac4e369644f9c8dcdaecc6` | 18 | `24df98dcb72799c634fc53bb46d4823c` | 29 |
| 3 | `b47c2fd38cac4e369644f9c8dcdaecc6` | 18 | `24df98dcb72799c634fc53bb46d4823c` | 29 |

- **改前同口径**（本席独立重建，取自 `git 0e0d192` 的 4 文件覆盖）：`risk_state` = **`d89387cb27c9c81f9218dbdf85587e5f`/18**；`risk_engine` = **`35cf08187b4354df81aeed6cf1213342`/29** ⇒ **与产出方自述逐字符一致**（且 `risk_engine` 改前 = `T-035` 修复态 `35cf0818…`，旁证「本单只叠 T-126 一层」）。
- **隔离等价**：本席隔离副本 `/root/t126v` 的 4 个交付文件 vs `git 65f010f`（= 落库的 T-126 提交，见下）逐字节比对 = **4/4 `MATCH`**。
- **⚠️ 时点披露（并发写者）**：本席开工时 `HEAD = 10b65f4`（彼时 T-126 **未提交**，故「`HEAD grep=0`」成立）；复核期间编排者提交 **`65f010f`（「工作区快照 #2」含 T-126）**、`HEAD` 前移至 `4457589`。故本证章一律以**提交 `0e0d192`（T-126 前）↔ `65f010f`（T-126 后）**为改前/改后锚，**不依赖「当前 HEAD 的 grep」**。

## T126.2 改前「确实是坏的」—— 自撰探针（不复用产出方探针）

**被测源**：`/root/t126v-pre/cpp/**`（严格 = 改前锚 `d89387cb…`/18 + `35cf0818…`/29，4 文件取自 `git 0e0d192`，其余同冻结点）。

**证据 (A) —— 承载缺口（编译级，无歧义）**：把**自撰探针 `t126-probe-post.cc`**（置 `has_settle_role` / `is_management_role`）对改前头文件编译：

```
/root/t126-probe-post.cc:42:5: error: 'struct hg::risk_state::PermissionEntry' has no member named 'has_settle_role'
/root/t126-probe-post.cc:44:5: error: 'struct hg::risk_state::PermissionEntry' has no member named 'is_management_role'
```

⇒ 改前「结算岗 / 管理层」维度**在 `PermissionEntry` 中不可表示**（⇒ 必然不可消费）。另 `git grep -e has_settle_role -e is_management_role 0e0d192 -- cpp/` = **0 命中**（与产出方自述一致）。

**证据 (B) —— 消费缺口（本席自撰探针，仅回退消费 `risk_engine.cc`）= 「改前同一口径」**：

```
=== te-verify-t126 probe (POST-state binary) ===
scenario                           | bind | decision | code
plain_trading (no flags)           |  1   |    0     | 0x0000
risk_post_only                     |  1   |    2     | 0x100E
settle_role_only                   |  1   |    0     | 0x0000   ← 结算岗被放行（缺陷态）
leader_only (is_responsible)       |  1   |    2     | 0x100F
management_only                    |  1   |    0     | 0x0000   ← 管理层被放行（缺陷态）
settle+management (both)           |  1   |    0     | 0x0000   ← 二者皆被放行
leader+risk (both 禁操盘支)        |  1   |    2     | 0x100F
```

**证据 (C) —— 端到端语义（改前锚，旧 `PermissionEntry` 唯一可表示形态）**：`/root/t126v-pre` 下 `t126-probe-plain.cc`（`has_trading_post=true`、其余缺省）⇒ **`trading_capable_only | decision=0 | code=0x0000`**（结算岗账号在旧模型里只能这样表示，被放行）。

**证据 (D) —— 产出方用例在改前消费态**：其自身 `risk_engine_permission_test` 在**仅回退消费**的副本上 ⇒ **`29 checks, 4 failures`**，红点 = `:88`(`decision==kReject`)/`:89`(`code==0x100E`)/`:123`/`:124`(`0x100F`) —— **与产出方自述「`:88/:89`、`:123/:124`」逐行一致**。

## T126.3 改后「确实是好的」＋ 误拒方向

**被测源**：`/root/t126v`（= 冻结点 `b47c2fd3…`/`24df98dc…`）。同自撰探针：

```
=== te-verify-t126 probe (POST-state binary) ===
plain_trading (no flags)           |  1   |  0  | 0x0000   ← 普通交易岗放行（无误伤；与「拒」同等重要）
risk_post_only                     |  1   |  2  | 0x100E
settle_role_only                   |  1   |  2  | 0x100E   ← 本单修复点
leader_only (is_responsible)       |  1   |  2  | 0x100F
management_only                    |  1   |  2  | 0x100F   ← 本单修复点
settle+management (both)           |  1   |  2  | 0x100F   ← 先判 0x100F 支（顺序稳定）
leader+risk (both 禁操盘支)        |  1   |  2  | 0x100F
```

- **期望基线**（依据）：`REQUIREMENTS/RISK.md:194`（`FR-RISK-7`「具备风控**或**结算岗位权限的账号**不得**下单」）；`ARCHITECTURE.md` §4.3.2 `0x100E`（岗位冲突 = 风控岗/结算岗）；§4.3.2 `0x100F`（「企业负责人 **或** 具备管理层标记 `is_management_role`；`CH-010` 析取」）；`REQUIREMENTS/AUTH.md:395`（`CH-010` 明写「析取、不得读作合取」）。
- **产出方用例**：`risk_engine_permission_test: 29 checks, 0 failures -> PASS`；`data_model_test: 72 checks, 0 failures -> PASS`（**两个 checks 数与自述逐字一致**）。

## T126.4 ★反退化三态（各自独立承重）—— 隔离副本

**方法**：三份**独立源树** `/root/t126v-mut{1,2,3}`（各自**从冻结点全量重编译**，无陈旧对象），逐分支注入（`sed` 后**断言 `count==1`**，避免「未注入」假绿）：

| 态 | 注入（`risk_engine.cc`） | 其自身用例实测 | 红点（文件:行） | 本席探针（关键行） |
|---|---|---|---|---|
| `M-SETTLE` | `p->has_risk_post \|\| p->has_settle_role` → `p->has_risk_post` | **`29 checks, 2 failures`** | `:88`、`:89`（`test_settle_role_conflict`） | `settle_role_only → kPass/0x0000`；`management_only → 0x100F`（绿） |
| `M-MGMT` | `p->is_responsible \|\| p->is_management_role` → `p->is_responsible` | **`29 checks, 2 failures`** | `:123`、`:124`（`test_management_role_forbidden`） | `management_only → kPass/0x0000`；`settle_role_only → 0x100E`（绿） |
| `M-FULL` | 两者皆移除 | **`29 checks, 4 failures`** | `:88`、`:89`、`:123`、`:124` | `settle_role_only → kPass`；`management_only → kPass` |

⇒ **两分支各自独立承重，无「仍绿 ⇒ 守卫空转」**；红点数与方位与产出方自述**完全一致**。复位后本席**以冻结点全量构建**复跑 `ctest` 38/38 全绿（T126.6）——即「复位 + clean 重编译回绿」成立。

## T126.5 `E4` 两口径 + 无锁/无分配/无同步 I/O + 聚合

**本席实测**（`cwd = 仓库根`）：

| 口径 | 命令 | 待扫描文件 | 命中 | WARN | rc |
|---|---|---|---|---|---|
| 单根（引擎） | `scan_lock_free.sh --root cpp/src/risk_engine` | **28** | 0 | **0** | **0** |
| 单根（状态） | `scan_lock_free.sh --root cpp/src/risk_state` | **17** | 0 | **3** | **0** |
| 默认并集根 | `scan_lock_free.sh` | **47** | 0 | **3** | **0** |
| 改前同根（状态） | `scan_lock_free.sh --root /root/t126v-pre/cpp/src/risk_state` | 17 | 0 | **3（逐条相同）** | **0** |
| 聚合 | `arch-check.sh` | — | — | — | **0**（`E1`–`E7` 全 0） |

- **WARN 3 逐条**：`risk_snapshot.cc:231`、`risk_state.cc:22`、`risk_state.cc:41` —— 全为**既有 `new`**，**改前改后逐条相同 ⇒ 无新增**（且这三处不在本单 4 文件内）。
- **`E4` 关切面（代码级）**：本单改动仅 = 2 个 `bool` 成员 + 2 处 `||` 逻辑 + 注释 + 一处静态字符串常量 + 测试断言；**未引入 `mutex`/`lock_guard`/`condition_variable`、未新增动态分配、未新增同步 I/O（`fsync`/`read`/`write`/`send`/`sleep` 等）**。

## T126.6 用例数以 `ctest -N` 为准 + 全绿

- **`ctest -N`（本席隔离全量构建 `/t126v-build-post`）= 38**，其中 **`Test #20: risk_engine.risk_engine_permission_test`**（本单新增 3 用例所在），**`Test #26: risk_state.data_model_test`**。
- **全量 `ctest` 端到端复跑**（`-j1`，`rc 0`）：`100% tests passed, 0 tests failed out of 38`（含 `arch_check` fixture）。构建日志 `warning:` **0** / `error:` **0**。
- **新增 3 用例确在跑**：`main()`（`:188`–`:190`）注册 `test_settle_role_conflict` / `test_risk_post_only_conflict` / `test_management_role_forbidden`；用例数 **8 → 11**；单测输出 `29 checks`。
- **⚠️ 数量与自述（33）不符之归因（见 `OBS-T126-1`）**：非本单引入。冻结时点（约 `2026-09-22T20:3x`）全树为 **33**（旁证：`T-124` 证章 `T124.6` 实测 **33**，同一时段）；其后**并发**的 `T-033` 官方 CTP 移植向 `ctp_adapter` **增补用例**（`tests/ct_lifecycle_test.cc` `mtime 20:53`、`ct_lifecycle_ctp_test.cc` `mtime 20:54`、`ct_adapter_test.cc` `21:28`…），使本树变为 **38**。**T-126 本身未增/删任何测试可执行体** ⇒ 不影响门禁判据（「全绿」满足）。

## T126.7 用例独立性 + 既有断言未被削弱

- **三用例确为三条独立断言**（读 `risk_engine_permission_test.cc` 原文）：
  - `test_settle_role_conflict`（`:77`–`:90`）：`has_risk_post=false`、`has_settle_role=true` ⇒ **仅结算岗**；
  - `test_risk_post_only_conflict`（`:94`–`:107`）：**`has_settle_role=false`**、`has_risk_post=true` ⇒ **仅风控岗**（**它确把 `has_settle_role` 显式置 `false`** ⇒ 两支各自独立成立，**证得析取而非合取**，**不构成缺陷**）；
  - `test_management_role_forbidden`（`:112`–`:125`）：`is_responsible=false`、`is_management_role=true` ⇒ **仅管理层**。
  - 三者**互不互为前提**（各置单条、其余显式排除）。
- **既有断言未被削弱**：`git diff 0e0d192 65f010f -- <2 模块>` 逐 hunk = **纯增**（`risk_engine_permission_test.cc` `56 +0`、`data_model_test.cc` `6 +0`、`model.h` `9 +0`；`risk_engine.cc` `6 +3` 仅 2 处 `||` + 注释 + 1 处 `detail` 串 + 1 处 `else if` 改写）；**无任何既有断言被删除或放宽**。既有 `test_enterprise_leader_forbidden`（`is_responsible=true → 0x100F`）与 `test_risk_post_conflict`（`has_risk_post=true → 0x100E`）在改后仍 **PASS**。
- **★「确无断言依赖被改写的 `detail` 串」已独立证实**：`grep -rn '\.detail' cpp/src/risk_engine/tests/` = **0 命中**；全仓（`cpp/`+`java/`+`web/`）仅 `cpp/src/hgcp/tests/envelope_test.cc:218` 断言 `ErrorBody.detail`（**hgcp 错误体**，与 `risk_engine` 的 `RiskResult::detail` **无关**）。改前串 `"enterprise leader is forbidden to trade"` 在**共享树内 0 命中**（唯一命中外仅 `.codebuddy/worktrees/**` 的背景 worktree）⇒ **字符串改写无测试依赖，非削弱**。

## T126.8 口径边界核实（「两字段当前无生产加载点」是否属实）

- `grep -rn 'has_settle_role\|is_management_role' cpp/`（排测试）= **仅 3 处**：`model.h`（声明/注释）+ `risk_engine.cc:406/411`（**读**）—— **无任何「写」点**。
- `PermissionEntry` 的生产级使用面：`risk_state`（`load_batch.*` / `risk_snapshot.*` 之**整结构体拷贝**与访问器）＋ `risk_engine.cc`（`evalPermission`，**读**）。**无 4 布尔逐字段映射**（`CMD_UPDATE_PERMISSION` 的协议→模型映射属 `T-038`）；**无 Java `DB→wire` 组装**（属 `T-042`）。
  - 交叉核对 `docs/TASKS.md`：`:911`（`T-038` 验收 +1 = 4 布尔逐字段映射）、`:995`（`T-042` 验收 +1 = Java 侧 `t_user_role→t_role` 派生组装）—— **确已分流登记**。
- ⇒ **产出方表述属实**：生产路径下两字段恒为缺省 `false`，**本单交付的是「承载 + 引擎消费」的可验证逻辑，不等于「生产已生效」**；产出方已在 `model.h:236-237` 与台账备注**主动写明**（**正确，未过度断言**）。

## T126.9 观察清单（均无阻断）

| 编号 | 严重度 | 类别 | 结论 |
|---|---|---|---|
| —— | —— | **缺陷** | **无**（修复有效；未发现新缺陷） |
| `OBS-T126-1` | 信息 | 取证口径 | 自述「`ctest` 33/33」**不可在本树复现**（本树 = **38**）。归因 = 冻结时点后**并发** `T-033` 向 `ctp_adapter` 增补用例（mtime 20:53–21:28），**非本单引入**（`T-124` 同证章 `T124.6` 实测 33 为旁证）。**建议**产出方/台账将「33」标注为**冻结时点口径**（现树 38 全绿，实质不变量满足） |
| `OBS-T126-2` | 低 | 注释锚点 | `model.h:231` 注释引「`ARCHITECTURE.md §4.2.3 :818`」；**实测该行现为 `:823`**（`0x0116 CMD_UPDATE_PERMISSION` 行；`TASKS.md` 裁定文本亦沿引 `:818`）。活跃制品**固定行号**易漂移 ⇒ 建议改**章节锚点**（`§4.2.3` 表行 `0x0116`） |
| `OBS-T126-3` | 低 | 测试覆盖 | `data_model_test.cc` 承载断言仅正向验 `has_settle_role=true` 与 `is_management_role`**缺省 false**（`:272-273`）⇒ `is_management_role=true` 的**正向承载未被断言**。因两字段同构（plain `bool` + 整结构体拷贝）**风险低**，建议补 1 条以对称 |

## T126.10 未决 / 存疑

- **无阻断存疑**。`ctest` 38/38 已端到端复跑；`E4` 两口径 + 聚合 `rc 0` 已复现。
- **领域外（本席未裁、不阻断本单）**：`RiskSnapshot::canTrade`（`risk_snapshot.cc:154-168`）亦消费 `PermissionEntry` 但**只判 `is_responsible`/`has_trading_post`**、不含新两字段 —— 已由技术席裁定另立 **`T-127`**（`docs/TASKS.md:1978`；其「生产判定消费点共 2 处、无第三处」穷举本席**未独立复算**，属技术席范围）。**不并入 `T-126`**（避免作废其冻结点），**不构成 `T-126` 缺陷**。
- **本席不置 `DONE`**（门禁结论由编排者落）；`docs/TASKS.md`/`BOARD.md`/`ARCHITECTURE.md` **零改动**；未修改任何交付制品。

## T126.11 纯追加证明（自指值不写死）

- 追加前基线：`md5sum docs/TEST_RESULT.md` = `22c54ba70e314202d32830fae43b6af9` / **4327 行**（**紧邻写入前 3 次 ×10s 取样不变**：`22c54ba7…`/4327 ×3）。
- 追加后：`head -4327 docs/TEST_RESULT.md | md5sum` = `22c54ba70e314202d32830fae43b6af9`（⇒ 前 4327 行**逐字节未变**）。追加后 `wc -l` 与 `md5sum docs/TEST_RESULT.md` **以实时为准**（写死会因追加本身而失效）。

# `T-033` / `T-027` 独立复核证据（2026-09-22）

**复核席**：`te-verify-t033b`（独立复核；**只读**，交付制品零改动）。
**对象**：`T-033`（CTP 适配层官方分离式移植 + U1 缺省对齐）、`T-027`（探路件官方基线重做）。
**基线**：本机 SDK = 官方 CTP `v6.7.13_20260225_api`（`/root/myspace/hedge-guard/sdk/v6.7.13_20260225_api`，只读）；对照 = `CTP Mini`（`/home/ctpmini/ctpmini_admin/ctp_sdk`，仅比对、勿采用）。
**隔离副本**：`/root/t033v-b`（共享树只读副本，布局 `<root>/{docs,java,cpp,tools,proto}`）+ 变体 `/root/t033v-b-{noguard,nosmoke,muutf,nosys,single,empty}`；构建目录 `/t033v-build/*` 与 `TMPDIR=/` 全在 `/`；`-j1`；**所有变异仅在副本**，共享树残留 0。

## T033b.0 结论（分别三档）

- **`T-033` = 有条件通过**（阻断级缺陷 **0**；条件见 T033b.11 观察项 + 基线限定 `OBS-T033b-1`）。
- **`T-027` = 通过**（官方基线 `run_probe.sh` `PASS=12/FAIL=0` 本席独立复现）。

（本席**不自评 `DONE`**，门禁由编排者落。）

## T033b.1 锚点独立确认（3 次取样）

- `find cpp/src/ctp_adapter -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`41e9ac046e0289ea68f45c234961ca88`**（×3 取样逐字节相同），**23 文件 / 3225 行** ⇒ 与实施方自述一致。
- 写域合并锚 `find cpp/src/ctp_adapter cpp/cmake cpp/ctp_smoke cpp/CMakeLists.txt -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`6333a743361f074324d3033b06070721`** ⇒ 一致。
- `find cpp/src -type f | … | md5sum` = `b5b675be98aae53942bb87a6c5b59ed0` / 115 文件（含他模块）⇒ 一致。
- 隔离副本与共享树写域 `diff -rq` **逐字节一致**（`ctp_adapter`/`cmake` 输出为空）。
- 共享树变异残留扫描（`*.orig`/`*.bak`/`*MUTATION*`/`*.rej`/`hgctp_flow_*`，排除 `build*`）= **0**。

## T033b.2 ★静默降级双向（本单最重要）

现态（官方根）：`cmake -S cpp -B … -DCTP_SDK_ROOT=<官方父目录>` ⇒ rc=0，CMake 原文
`-- [CTP] 发现 CTP SDK（cache-variable CTP_SDK_ROOT）：trader-include=…/v6.7.13_20260225_api_traderapi_se_linux64 ; md-include=…/…_mduserapi_se_linux64 ; trader-lib=…/thosttraderapi_se.so ; md-lib=…/thostmduserapi_se.so`。

**① 复现前态（假绿）** —— 隔离副本 `/root/t033v-b-noguard`（**移除守卫**，且令交易头名 = 旧口径 `ThostFtdcUserApi.h`；根 = 官方 SDK）：
- 配置 `rc=0`，原文 `-- [CTP] CTP SDK 不可得（…缺：交易头目录（ThostFtdcUserApi.h）;交易库（…））` → `-- [CTP] → CTP_FOUND=OFF（ctp_adapter 走空实现）`；
- 构建 `rc=0`（假绿成立）；`ctest` **35/35 全绿**；`ctp_adapter_ct_adapter_test` 输出 `89 checks, 0 failures -> PASS`（backend=stub）。
⇒ **「官方 SDK 在场却静默走空实现、构建与测试照样全绿」实证复现**。

**② 现态 = `CTP_FOUND=ON`**（见上 CMake 原文）。

**③ 守卫必须会咬（显式给解析不出的根）**：
- 全工程轨：`-DCTP_SDK_ROOT=/root/t033v-b-empty` ⇒ **rc=1**，`CMake Error at ctp_smoke/CMakeLists.txt:132 (message): [RSK-02] 已显式指定 CTP SDK 根，但解析必需头/库失败：… → 拒绝静默跳过探路`。
- **主工程轨**（隔离 `/root/t033v-b-nosmoke` 关闭 `add_subdirectory(ctp_smoke)`）⇒ **rc=1**，`CMake Error at cmake/HgDependencies.cmake:95 (message)` ⇒ **主工程守卫自身确实会咬**（非仅探路件）。

**④ MUT-D（删守卫）承重**：`/root/t033v-b-noguard` + 解析不出的根 ⇒ **rc=0** 且 `→ CTP_FOUND=OFF（ctp_adapter 走空实现）` ⇒ **守卫是承重的**（删之即静默回落）。

## T033b.3 三布局各自可用（配置到 `CTP_FOUND` + 编译一个 TU）

| 布局 | 命令 | 结果 |
|---|---|---|
| ① 官方父目录自动探测 | `-DCTP_SDK_ROOT=<官方父目录>` | rc=0；`CTP_FOUND=ON`；trader/md include 分处两子目录（见 T033b.2） |
| ② 显式覆盖 | `-DCTP_TRADER_ROOT=<…traderapi…> -DCTP_MD_ROOT=<…mduserapi…>` | rc=0；`CTP_FOUND=ON`；`trader-include=…traderapi…`、`md-include=…mduserapi…` |
| ③ 老式单目录 | `-DCTP_SDK_ROOT=/root/t033v-b-single`（单目录含两套头+两库） | rc=0；`CTP_FOUND=ON`；`trader-include == md-include == /root/t033v-b-single` |

- 布局 ②/③ 各 `cmake --build … --target hg_ctp_adapter` ⇒ **rc=0**，产物 `libhg_ctp_adapter.a` 生成，`error:` **0**。
⇒ **三布局均可用；老式单目录未被破坏**。

## T033b.4 三轨独立复跑（名单以 `ctest -N` 为准）

- `ctp`（`CTP_SDK_ROOT=官方`，系统 protobuf 3.21.12）：`ctest -N` **38**，`ctest` **38/38 通过、0 跳过**（含 `rsk02.ctp_header_cxx11_compat`、`ctp_adapter.{ct_queues,ct_adapter,ct_lifecycle,ct_mapping,ct_spi,ct_lifecycle_ctp}_test`）。构建 `warning:`/`error:` **0/0**。
- `stub`（无 SDK 根；非显式 ⇒ 无守卫触发）：`ctest -N` **34**，**34/34 通过**。构建 `warning:` 0。
- `fetch`（`-DCTP_USE_FETCH_DEPS=ON`，`FETCHCONTENT_SOURCE_DIR_PROTOBUF=<缓存源码>` 离线）：**本席只构建了 CTP 相关目标**（`ctp_adapter_*` 6 个测试目标 + `hgcp_ctp_probe`）⇒ rc=0；`ctest -R "ctp_adapter\."` **7/7 通过**（6 用例 + `arch_check` fixture）。**本席未构建 protobuf 全量**（实测该 build 树内 `libprotobuf*.a` / `_deps/**/*.o` **均为 0**）。
- **实施方「`fetch` 只验 CTP 面、未跑 protobuf 全量」声明核验**：核查其实施副本 `/root/t033p-b-fetch` ⇒ 含 `ctp_adapter` 全量 `.o`/`libhg_ctp_adapter.a` 与 `ctp_smoke` 探针对象，**`libprotobuf*.a`=无、protobuf 对象=无** ⇒ **该声明属实**（与本席实跑口径一致）。**protobuf 全量构建在本单中确未被任何人验证**（属 `T-026` 范围，本单不追）。

## T033b.5 `-isystem` 必要性（MUT-A，隔离 `/root/t033v-b-nosys`）

- 去掉两目录的 `SYSTEM`（`ctp_adapter/CMakeLists.txt:45` `SYSTEM PUBLIC` → `PUBLIC`）⇒ `cmake --build … --target hg_ctp_adapter` **rc=2**，命中 `-Werror=unused-parameter` **567 条**（如 `…/ThostFtdcTraderApi.h:42:46: error: unused parameter 'nReason' [-Werror=unused-parameter]`）。
- **逐行核**：`grep 'error:' … | grep -v 'sdk/v6.7.13'` = **空** ⇒ **全部 error 来自 SDK 头，自有代码 0 告警**。⇒ **两目录 `-isystem` 既生效又必需**。

## T033b.6 `.so` 链接真实可用

对 CTP 轨 `ctp_adapter_ct_adapter_test`（真实链接）：
- `readelf -d`：`NEEDED` = **绝对路径** `…/thosttraderapi_se.so`、`…/thostmduserapi_se.so`（官方 `.so` **无 `lib` 前缀、无 SONAME** ⇒ 必须绝对路径，`-l<name>` 不可用）；`RUNPATH` = 两 SDK 目录。
- `ldd`：两个官方 `.so` **均解析成功**（绝对路径 + 运行期地址）⇒ 运行期可载入。
- 佐证：`ct_lifecycle_ctp_test` 经 `GetApiVersion()` 取到 `v6.7.13_20260225 14:16:30.12079`（非空）⇒ `.so` 真载入。

## T033b.7 U1 缺省与断言（含 MUT-F）

- 代码：`ct_types.h:89` `CtConfig() : env_type(CtEnvType::kSimulation) {}`；`redacted()`（`:111-112`）含 `env_type=`（生效模式可观测）。
- 断言：`ct_adapter_test` = **89 checks / 0 failures / PASS**（缺省 `kSimulation`、`productionMode()==false`、`redacted()` 含 `env_type=SIMULATION`）。
- **MUT-F（隔离 `/root/t033v-b-mutf`，缺省改回 `kProduction`）**：构建 rc=0，运行 ⇒
  ```
  [FAIL] ct_adapter_test.cc:321: default_cfg.env_type == …::kSimulation
  [FAIL] ct_adapter_test.cc:322: !default_cfg.productionMode()
  [FAIL] ct_adapter_test.cc:323: default_cfg.redacted().find("env_type=SIMULATION") != npos
  ct_adapter_test: 89 checks, 3 failures -> FAIL
  ```
  ⇒ **新缺省值断言确在承重**（若仍绿即空转，本席实测为红）。

## T033b.8 `T-027` 探针独立重跑

- 官方基线：`CTP_SDK_ROOT=<官方父目录> bash cpp/ctp_smoke/ci/run_probe.sh` ⇒ `PROBE_RC=0`，**`PASS=12  FAIL=0` / `RESULT: PASS`**（B1、B2、B3、B4、B2b、B5、B6、B6b、B7、B7b、B7c、B8）⇒ **与自述 `12` 一致**。
- **`B6`/`B6b` 判据反转且会咬**（本席按脚本内 `_hdr_dir` + 编译逻辑构造反例）：

  | SDK 根 | `ThostFtdcTraderApi.h` | `ThostFtdcUserApi.h` | B6 | B6b |
  |---|---|---|---|---|
  | 官方 v6.7.13 | 可得 | **不可得** | PASS | PASS |
  | `CTP Mini`（`/home/ctpmini/…`） | 不可得 | 存在 | **FAIL** | **FAIL** |

  ⇒ `B6`（TraderApi 可得=通过）/`B6b`（合并版不可得=通过）**双向会咬**。
- **「上轮 `PASS=11` 系 Mini 专属」确认**：`find sdk/… -name ThostFtdcUserApi.h` = **空**（官方无合并版头）；该头仅存在于 `CTP Mini` ⇒ 上轮基于合并版头的结论**对交付基线不成立**。

## T033b.9 `TE-T033-001` 实现侧（本席**不重裁**，仅证实现无相关改动）

- `sendOrder` 返回 `CtOrderAck`（`ct_adapter.h:59`）；`CtOrderAck` 仍带 `code` / `order_local_id` / `retained`（`ct_types.h:175-181`）。
- `ct_adapter.cc:146`/`:154` 在「未连接/退回」路径置 `ack.retained = true` ⇒ 退回语义在实现侧可观测。
⇒ 与上一轮复核一致：**实现正确、零改动**（依技术席裁定，本席**不重新裁定**接口形态）。

## T033b.10 残余 / 隔离 / 探索性

- 共享树**未由本席写入任何交付制品**；本席全部副本在 `/root/t033v-b*`、构建在 `/t033v-build/*`（均**在共享树之外**）。
- 探索性：① 显式坏根 ⇒ 守卫咬（含主工程轨单测）；② 缺 `-isystem` ⇒ `-Werror` 炸（自有代码干净）；③ 缺省翻转 ⇒ 断言红；④ 官方无合并版头 ⇒ Mini 专属结论失效；⑤ 父目录/显式覆盖/单目录三布局互通。
- 观察（非缺陷）：`ctp_smoke` 与主工程用**不同的头名变量**（`HG_CTP_HEADER_STYLE` vs `HG_CTP_TRADER_HEADER`）⇒ 在「只覆盖主工程头名」的构造下二者可达**不同 `CTP_FOUND`**（本席前态复现时 `ctp_smoke` 因默认 `CLASSIC` 仍注册 `rsk02`；故前态 `ctest` 为 35 = 规范 stub 34 + `rsk02`）。属口径差异、无 `FR` 冲突，登记备查。

## T033b.11 缺陷 / 观察清单

| 编号 | 严重度 | 类别 | 说明 |
|---|---|---|---|
| —— | —— | 缺陷 | **无**（静默降级已消除、守卫双向承重、三布局/三轨/`-isystem`/`.so`/U1/探针 均独立证实） |
| `OBS-T033b-1` | 信息 | 基线限定 | 所有「与官方 CTP 一致」结论仅对官方 `v6.7.13` 成立；`CTP Mini` 结论已失效（见 T033b.8） |
| `OBS-T033b-2` | 信息 | 口径 | `fetch` 轨的 protobuf 全量构建**未验证**（实施方如实声明、本席亦然）；属 `T-026` 范围 |
| `OBS-T033b-3` | 低 | 口径 | `ctp_smoke` 与主工程头名变量不同源（见 T033b.10 观察） |

## T033b.12 未决 / 存疑

- 无阻断存疑；阻断级缺陷 0。
- `T-033` **不置 `DONE`**：门禁由编排者落（`DONE` = 授权 + 独立验证）。
- 未验证（本席范围外）：`fetch` 轨 protobuf 全量构建；`T-026` protobuf 供给链路（非本单）。

## T033b.13 纯追加证明（自指值不写死）

- 追加前基线：`md5sum docs/TEST_RESULT.md` = `8d92ec1615c05ae3f050ef051f91693c` / **4502 行**（写入前 **3 次 ×10s 取样不变**：`8d92ec16…`/4502 ×3）。
- 追加后：`head -4502 docs/TEST_RESULT.md | md5sum` 应 = `8d92ec1615c05ae3f050ef051f91693c`（⇒ 前 4502 行**逐字节未变**）。追加后 `wc -l` 与 `md5sum docs/TEST_RESULT.md` **以实时为准**（写死会因追加本身而失效）。

---

# `T-126` 增量复检（`T-127` 触及 `risk_engine/**` 后的 delta 复检）（2026-09-22）

> 复核方：**测试工程师（独立复核，非产出方）** ｜ 实例 `te-verify-t126b` ｜ 时刻 `2026-09-22T22:47+08:00`
> 用途：`T-126` 经独立复核后，`T-127`（`PermissionEntry` 岗位维度**抽共用纯函数**）触及 `cpp/src/risk_engine/**` ⇒ `T-126` 冻结点前移 ⇒ 依「冻结后要改须显式重锚 + delta 复检」纪律，须**证明该改动对 `evalPermission` 语义等价**（**不是**「测试还绿」这一条）。
> 立场：产出方一切自报视为**待证命题**；本席**未复用产出方探针**（自撰穷举对拍 `/root/t126b/probe/diff_probe.cc`）。
> **只读**：未改任何交付制品（本证章对本文件为**纯追加**，见 T126b.11）；**共享源树零写入**；变异/还原/旧实现重建**一律在隔离副本** `/root/t126b`（另 `/root/t126b_headchk` 仅 `git archive` 展开）。`TMPDIR` 与构建目录置 `/`（`/tmp` 为 804M tmpfs ⇒ 满）；`-j1`。
> 工具链：`g++ 15.2.0` / `cmake 4.2.3` / `ctest` / `protobuf 3.21.12`。

## T126b.0 结论

**结论：`通过`（0 阻断缺陷；2 项信息/低观察，见 T126b.9）。**

**★核心（语义等价，机械化判据）**：对 `PermissionEntry` 冲突四位**全 16 组合** × 交易岗位位 × 授权位 × 额度位**穷举 257 个工况**，**改后 `evalPermission` 与「还原为原内联表达式」的改前实现，`decision` + `code` + `rule` 逐行完全相同 ⇒ 差异 = 0 行**（T126b.2）。故 `T-126` 的权威消费点（引擎 `evalPermission`）**保持语义等价** ⇒ **冻结点前移不改变 `T-126` 的三条判据**（T126b.5 独立复现）。

**逐项**：
1. **★语义等价** ✅（穷举对拍差异 0 + 源码级逐字核验）；
2. **反退化** ✅（从共用纯函数移除任一字段 ⇒ **两个消费点同时红**，非「仅一处红」）；
3. **`T-126` 三判据未破坏** ✅（仅结算岗 `0x100E`／仅风控岗 `0x100E`／仅管理层 `0x100F`）；
4. **`E4` 两口径 + 聚合** ✅（`risk_state` / `risk_engine` 均 `rc 0`、默认并集 47 文件、聚合 `E1`–`E7` 全 0；**无新增锁/互斥/同步 I/O/分配**）；
5. **锚点 3 次取样** ✅（两模块前/后锚**逐字符吻合派单值**）；**共享树残留 = 0** ✅；
6. **端到端**：隔离全量构建 `-j1` **0 warning / 0 error**、`ctest` **38/38 全绿**（`ctest -N` 名单口径已核，见 T126b.6）；
7. **`canTrade` 改动 = 有意扩面（非等价）**，本席**不按 `T-126` 判据计**，另单列（T126b.2b / T126b.9）。

---

## T126b.1 锚点独立确认（3 次取样）+ 隔离副本等价

命令（`cwd = 仓库根`）：`find <dir> -type f | LC_ALL=C sort | xargs md5sum | md5sum`（**必须 `LC_ALL=C`**，否则 `sort` 区域设置致假差异）。

| 项 | 派单「前 → 后」 | 本席独立实测（前 = `git archive c9e056e` 展开） | 文件数 | 一致 |
|---|---|---|---|---|
| `cpp/src/risk_engine` | `24df98dc…`/29 → `09b88329…`/29 | **`24df98dcb72799c634fc53bb46d4823c`/29 → `09b883292dc5732fa930e7ff297c2a62`/29** | 29 | ✅ |
| `cpp/src/risk_state` | `b47c2fd3…`/18 → `3f4e4750…`/18 | **`b47c2fd38cac4e369644f9c8dcdaecc6`/18 → `3f4e475007591f61255812d23b6355e8`/18** | 18 | ✅ |

- 3 次取样（`14:40:04` / `14:40:14` / `14:40:24`）**两模块锚点全一致**（`09b88329…`/`3f4e4750…` 各 3/3）。
- **隔离等价**：隔离副本 `/root/t126b` 与共享树**逐字节比对 5/5 `MATCH`**（`risk_engine.cc ca9488e8…`、`model.h d450c18f…`、`risk_snapshot.cc 308ab673…`、`data_model_test.cc 3b9c5dfb…`、`risk_engine_permission_test.cc 540f3d99…`）。
- ⚠️ **旁证**：`git status` 显示 `T-127` 改动**仍在工作区未提交**（`HEAD = c9e056e`）⇒ 「前」锚**恰等于 `HEAD`**（`T-126` 定版态），由 `git archive HEAD` 直接可取，无「历史态不可复现」问题。

## T126b.2 ★语义等价（穷举对拍，本单最重要的机械判据）

**方法**（自撰，不复用产出方探针）：探针 `diff_probe.cc` 遍历 **`is_responsible` / `is_management_role` / `has_risk_post` / `has_settle_role` 四位全 16 组合 × `has_trading_post`{0,1} × `allow_all_sub_accounts`{0,1} × `allow_all_products`{0,1} × `individual_quota`{0,1} = 256 工况**（+ 无权限记录 1 例 = **257 行**），每工况经 `RiskEngine::check(baseOrder())` 打印 `decision/code/rule`。**同一探针源码**分别与两份实现链接：
- **新**：现树（`risk_engine.cc` 调用 `permissionLeaderForbidden` / `permissionPostConflict`）；
- **旧**：`risk_engine.cc` **还原为原内联表达式**（`p->is_responsible || p->is_management_role`、`p->has_risk_post || p->has_settle_role`，取自 `git show HEAD:`，md5 `0870aa47…`）、`risk_snapshot.cc` 还原为 `if (p->is_responsible) return false;`（md5 `3510c50f…`）。

**结果**：

```
$ diff <(grep '^E ' out_new.txt) <(grep '^E ' out_old.txt) | wc -l
0
```

⇒ **257 行 `evalPermission` 输出逐行相同，差异 = 0**；`code` 分布亦相同（`0x100F`×192 / `0x100E`×48 / `0x100B`×9 / `0x100D`×4 / `0x0000`×4 = 257）。**`evalPermission` 的 `T-127` 改写 = 严格语义等价（no-op）**。

**链式优先级未变（补充）**：leader 支先于 post 支 —— `combo=3/7/15 → 0x100F`、`combo=12 → 0x100E`（新旧一致）。

### T126b.2b 同探针第二段：`canTrade` **非等价（有意扩面）**

同一探针另打印 `RiskSnapshot::canTrade(7,1,10)`（256 行）：

```
$ diff <(grep '^C ' out_new.txt) <(grep '^C ' out_old.txt) | grep -c '^< C .*canTrade=0$'   → 56   # 新=false, 旧=true
$ diff <(grep '^C ' out_new.txt) <(grep '^C ' out_old.txt) | grep -c '^< C .*canTrade=1$'   → 0    # 新=true, 旧=false
```

⇒ `canTrade` **56/256 工况由 `true` 变 `false`、反向 0 例**（**单向收严**，无「放行回归」）。这是 `T-127` 的**交付意图本身**（`canTrade` 补判 `is_management_role` / `has_risk_post` / `has_settle_role`），**不是 `T-126` 的等价性主张** —— **「语义等价」仅对 `evalPermission` 成立**；本席明确分栏，**不将 `canTrade` 的扩面读作等价、亦不据此判 `T-126`**（该扩面正确性属 `T-127` 自身验收面）。

## T126b.3 源码级核验（「同一布尔表达式 → 函数调用」，短路顺序逐字未变）

| 位置 | 改前（`HEAD`，行号） | 改后（现树，行号） | 函数体（`model.h`） |
|---|---|---|---|
| `evalPermission` 支① | `:406` `} else if (p->is_responsible \|\| p->is_management_role) {` | `:410` `} else if (permissionLeaderForbidden(*p)) {` | `:287` `return p.is_responsible \|\| p.is_management_role;` |
| `evalPermission` 支② | `:411` `} else if (p->has_risk_post \|\| p->has_settle_role) {` | `:415` `} else if (permissionPostConflict(*p)) {` | `:290` `return p.has_risk_post \|\| p.has_settle_role;` |
| `canTrade` | `:159` `if (p->is_responsible) return false;` | `:162/:163` 两函数各一行 | 同上 |

- **逐字确认**：两处替换均为「**同一布尔表达式 → 函数调用**」，**操作数顺序逐字保持**（`is_responsible` 先于 `is_management_role`；`has_risk_post` 先于 `has_settle_role`）⇒ **短路顺序未变**（`||` 左操作数先求值；同一表达式逐字搬运）。支①②的 `code` / `detail` 文案**未改**（`0x100F` / `0x100E` 两串逐字相同）。
- **改动面**：`git diff --numstat`（两模块 4 文件）= `risk_engine.cc +9/−4`、`model.h +23/−2`、`risk_snapshot.cc +6/−2`、`data_model_test.cc +90/−5`；`git diff --name-only` = **仅 `risk_engine/**` 与 `risk_state/**`（+ 本 `TEST_RESULT.md`），无第三模块**。

## T126b.4 ★反退化（「共用」是否名副其实）

**方法**：**不改交付制品** —— 变异体置于独立搜索路径（`-I /root/t126b/mut/include` 前置），**`-H` 实测确认 `hg/risk_state/model.h` 解析到变异件**（防「未注入」假绿）；两消费点各自全量重编译。

| 态 | 注入（共用纯函数） | 消费点① `risk_state/data_model_test` | 消费点② `risk_engine/risk_engine_permission_test` |
|---|---|---|---|
| 基线 | 无 | `82 checks, 0 failures -> PASS` | `29 checks, 0 failures -> PASS` |
| `M-LEADER` | `permissionLeaderForbidden` 去 `is_management_role` | **`1 failure`** @ `data_model_test.cc:381` | **`2 failures`** @ `:123`、`:124` |
| `M-POST` | `permissionPostConflict` 去 `has_settle_role` | **`1 failure`** @ `:366` | **`2 failures`** @ `:88`、`:89` |

⇒ **任一字段从共用纯函数移除 ⇒ 两个消费点同时变红**（无「仅一处红 ⇒ 共用名不副实」）。**「共用」名副其实**，且红点方位与派单自述（`:381` 且 `:123/:124`）**逐行一致**。复位：本席**从未改交付制品**（变异在独立搜索路径），端到端复跑见 T126b.6（全绿）。

## T126b.5 `T-126` 三判据独立复现（未被破坏）

由 T126b.2 的 257 行对拍输出直接取（全授权、`quota=0`）：

| 判据 | 工况 | 实测 |
|---|---|---|
| 仅结算岗 → `0x100E` | `combo=8`（`has_settle_role=1`，其余三位 0） | `dec=2 code=0x100E rule=7`（`kReject`） ✅ |
| 仅风控岗 → `0x100E`（**析取**：`has_settle_role=false`） | `combo=4` | `dec=2 code=0x100E` ✅ |
| 仅管理层 → `0x100F` | `combo=2`（`is_responsible=0`） | `dec=2 code=0x100F` ✅ |
| （附）仅负责人 → `0x100F` | `combo=1` | `dec=2 code=0x100F` ✅ |

- 产出方用例侧同源复现：`risk_engine_permission_test`（含 `test_settle_role_conflict` / `test_risk_post_only_conflict` / `test_management_role_forbidden`）基线 `29 checks, 0 failures -> PASS`。

## T126b.6 `E4` 两口径 + 无锁/无分配/无同步 I/O + 聚合 + `ctest` 名单/全绿

**`E4`（`cwd = 仓库根`）**：

| 口径 | 命令 | 待扫描文件 | 命中 | WARN | rc |
|---|---|---|---|---|---|
| 单根（状态） | `scan_lock_free.sh --root cpp/src/risk_state` | 17 | 0 | 3 | **0** |
| 单根（引擎） | `scan_lock_free.sh --root cpp/src/risk_engine` | 28 | 0 | 0 | **0** |
| 默认并集根 | `scan_lock_free.sh` | 47 | 0 | 3 | **0** |

- WARN 3 逐条 = `risk_snapshot.cc:235`、`risk_state.cc:22`、`risk_state.cc:41`（**既有 `new`**，**不在 `T-127` 改动面内** ⇒ 非本次引入）。
- **无新增锁/互斥/同步 I/O/分配**：对 `T-127` 新增行做 `new`/`malloc`/`calloc`/`realloc`/`std::vector`/`std::string`/`std::function`/`reserve`/`push_back` 与 `mutex`/`lock_guard`/`unique_lock`/`condition_variable`/`atomic`/`fsync`/`read`/`write`/`send`/`recv`/`sleep` 双向**增行扫描 = 0 命中**；改动仅 = 2 个 `bool` 表达式抽成 inline 函数 + 注释 + 测试。
- **聚合 `arch-check.sh` = `rc 0`**（`通过(0)=7 命中(1)=0 用法/前置错误(2)=0`；`E1`–`E7` 全 0）。

**`ctest -N` 名单口径（隔离双轨 fresh configure，非复用共享 build 目录）**：

| 轨 | 配置 | `ctest -N` 总数 |
|---|---|---|
| CTP 可得 | `-DCTP_SDK_ROOT=<iso>/sdk/v6.7.13_20260225_api`（`CTP_FOUND=ON`） | **38** |
| CTP 不可得 | `-DCTP_SDK_ROOT=`（`CTP_FOUND=OFF`） | **34** |

- 差集 **4 条**（实测）：`ctp_adapter.ct_lifecycle_ctp_test`、`ctp_adapter.ct_mapping_test`、`ctp_adapter.ct_spi_test`、`rsk02.ctp_header_cxx11_compat`。
- **口径裁定（对派单解释的精确化）**：派单称「差 4 = `ctp_smoke` 类 target 未注册」—— **实质成立、表述不精确**：4 条中**仅 1 条**来自 `ctp_smoke` 探路（`rsk02.ctp_header_cxx11_compat`），另 **3 条**来自 `ctp_adapter` 的 **CTP 轨单测**（`src/ctp_adapter/CMakeLists.txt:75-81` 的 `if(CTP_FOUND)` 追加）。准确表述 = 「**4 条 = 全部 CTP 相关 target**（1 探路 + 3 适配层）由 `CTP_FOUND` 门控注册」（见 T126b.9 `OBS-T126b-1`）。
- **端到端全绿**：隔离全量构建（`-j1`）`BUILD rc=0`、`grep -icE 'warning:' = 0`、`'error:' = 0`；`ctest --output-on-failure -j1` ⇒ **`100% tests passed, 0 tests failed out of 38`**（含 `arch_check` fixture `Passed`；`#20 risk_engine.risk_engine_permission_test`、`#26 risk_state.data_model_test` 均 Passed）。

## T126b.7 纯追加 / 只读声明

- 本席**未修改任何交付制品**：`docs/TASKS.md` / `BOARD.md` / `ARCHITECTURE.md` / 源码 / 测试**零写入**；**共享源树零字节写入**（变异经独立 `-I` 搜索路径，未就地改任何 `.h`/`.cc`）。
- 隔离区：`/root/t126b`（源副本 + `build-ctp` / `build-stub` + `probe/` + `old_src/`）；`/root/t126b_headchk`（`git archive HEAD` 展开，仅读）。**残留扫描**：共享树 `find . \( -name '*.orig' -o -name '*.bak' -o -name '*MUT*' -o -name '*.rej' \)`（排除 `.git`）= **0**。

## T126b.8 未决 / 存疑

- **无阻断存疑**。语义等价（`evalPermission`）已机械化证实（差异 0）；`ctest` 38/38 已端到端复跑；`E4` 两口径 + 聚合 `rc 0` 已复现。
- **不可判定/未做**：`T-127` 对 `canTrade` 的扩面**是否符合其自身验收标准**（`docs/TASKS.md` `T-127` 条）**不在本单判据内**，本席**未裁**；本席仅证实其**方向为单向收严、无放行回归**（T126b.2b）。

## T126b.9 观察清单（均无阻断）

| 编号 | 严重度 | 类别 | 结论 |
|---|---|---|---|
| —— | —— | **缺陷** | **无**（`evalPermission` 语义等价已证实；`T-126` 三判据未被破坏；「共用」名副其实） |
| `OBS-T126b-1` | 信息 | 派单表述 | 派单「差 4 = `ctp_smoke` 类 target」**表述不精确**：实测 4 条 = 1 条 `ctp_smoke` 探路 + 3 条 `ctp_adapter` CTP 轨单测。**实质（`CTP_FOUND` 门控注册）成立**。建议改写为「全部 CTP 相关 target」。 |
| `OBS-T126b-2` | 低 | 测试断言面（`data_model_test.cc` 重构） | `T-127` 重构把 `T-126` 原断言 `HG_CHECK(!perm->is_management_role)`（「未置位者保持缺省 false」）**移除**，改在新记录 `9101` 断言**显式置位者保持 true**（`carried->is_management_role`，正是 `T-126` `OBS-3` 的建议项）。⇒ **正向承载获补**（好事），但「**缺省 false 保持**」这一属性**不再有直接断言**（仅由 `!s.canTrade(9001,…)` 为 `true` 间接蕴含）。**风险低**（plain `bool` + 整结构体拷贝），**不阻断**；如需可补 1 条对称断言。 |

## T126b.10 未决（本席范围外，不裁）

- `T-127` 的 `canTrade` 扩面是否符合其自身验收标准 ⇒ 归 `T-127` 复核面，本席不裁（见 T126b.8）。
- 本席**不置 `DONE`**（门禁结论由编排者落）。

## T126b.11 纯追加证明（自指值不写死）

- 追加前基线：`md5sum docs/TEST_RESULT.md` = `69fa98abaf0c86e8ed95dab392c83a5f` / **4630 行**（**紧邻写入前 3 次 ×10s 取样不变**：`69fa98ab…`/4630 ×3 @ `14:46:26`/`:36`/`:46`）。
- 追加后：`head -4630 docs/TEST_RESULT.md | md5sum` = `69fa98abaf0c86e8ed95dab392c83a5f`（⇒ 前 4630 行**逐字节未变**）。追加后 `wc -l` 与 `md5sum docs/TEST_RESULT.md` **以实时为准**（写死会因追加本身而失效）。

---

# `T-127` 独立复核证据（2026-09-22）

> 复核方：**测试工程师（独立复核，非产出方）** ｜ 实例 `te-verify-t127` ｜ 时刻 `2026-09-22T22:52+08:00`
> 立场：本项目 `DONE` 判据 = **授权 + 独立验证**；产出方一切自报视为**待证命题**。本席**未复用产出方用例**——自撰探针 `/root/t127v/probe/t127_probe.cc`（**同时打印 `canTrade` 与 `evalPermission` 原始可观测值，不做断言**，同一探针分别跑于改前/改后两态）。
> **只读**：共享源树（`/root/myspace/hedge-guard`）**未写一字节**；变异/回退**一律在隔离副本**（`/root/t127v`＝冻结点、`/root/t127v-pre`＝`risk_snapshot.cc` 回退至 `HEAD`、`/root/t127v-mc`/`/root/t127v-md`＝共用函数变异、`/root/t127v-mab`＝调用点变异）。工具链 `g++ 15.2.0` / `cmake` / `ctest`；构建目录与 `TMPDIR` 一律置 `/`（`/tmp` 为 804M tmpfs）、`-j1`。
> 全文分栏「**独立实测**」与「**产出方自述（本席未复现者单列）**」。本席**不自评 `DONE`**。

## T127.0 结论

**结论：`通过`（0 阻断缺陷；3 项观察，见 T127.9）。**

产出方 `T-127` 交付经**独立取证**证实：
(1) **改前确实是坏的** —— 用改前锚（`risk_snapshot.cc` 回退至 `HEAD`）实测：**仅 `has_risk_post` / 仅 `has_settle_role` / 仅 `is_management_role`** 三情形 `canTrade` **均返回 `true`（放行）** —— 即缺陷态；三条**全部承重**（无一「改前也已是 `false`」）；
(2) **改后确实是好的** —— 冻结点下三情形 `canTrade` **各自 `false`**；**四冲突位全 false 且授权齐备 ⇒ `true`（无误拒）**；
(3) **「共用」名副其实** —— 从**共用纯函数**分别移除 `is_management_role`（`M-C`）、`has_settle_role`（`M-D`）⇒ **两个消费点（`canTrade` 与 `evalPermission`）同时变红**（非「只红一个」）；复位 + 重编译回绿；
(4) **反退化 4 变异全部独立复现**（`M-A` 红 1 / `M-B` 红 2 / `M-C` 与 `M-D` 各红两个消费点）；
(5) **门禁**：构建 0 warning / 0 error；`ctest` **34/34**（CTP 不可得）/ **38/38**（CTP 可用）；聚合 `rc 0`（`E1`–`E7` 全 0）；`E4` 两口径 `rc 0`、`WARN 3` 与基线同源（仅行号位移）；
(6) **既有断言**无「静默放宽」；**1 条断言被更替**（见 T127.5 / 观察 `OBS-T127-2`，与 `te-verify-t126b` 的 `OBS-T126b-2` **独立同源**）；
(7) **改动面 / 锚点 / 残留**与自述一致（见 T127.8）。

**验收标准逐条**（`docs/TASKS.md` `T-127`）：补齐 `canTrade` 岗位/角色维度判定 ✅ ｜ 与 `evalPermission` 对齐 ✅ ｜ 共用纯函数抽出且**单点变更多点响应** ✅ ｜ 反退化（移除消费 ⇒ 对应用例必红）✅ ｜ 门禁（0 告警 / `ctest` 全绿 / 聚合 `rc 0` / `E4` 命中 0）✅。

## T127.1 独立复现「改前是坏的」

- **改前态构造**（隔离）：`/root/t127v-pre` = 冻结点副本，**仅** `cpp/src/risk_state/src/risk_snapshot.cc` 回退为 `git show HEAD:…`（`diff` 实测 `IDENTICAL`，第 159 行 `if (p->is_responsible) return false;`）。**唯一自变量 = `canTrade` 判定体**。
- 命令：`g++ -std=c++11 … t127_probe.cc <改前 libhg_risk_state.a> <冻结 libhg_risk_engine.a> -lpthread && ./probe-pre`
- **实测输出（改前）**：

```
[canTrade] op=1 only has_risk_post         => TRUE
[canTrade] op=2 only has_settle_role       => TRUE
[canTrade] op=3 only is_management_role    => TRUE
[canTrade] op=4 only is_responsible        => false
[canTrade] op=5 control(all four false)    => TRUE
[canTrade] op=6 control(no trading post)   => false
```

⇒ 三条单冲突位**改前均放行**（缺陷态），且**无一改前已 `false`** ⇒ 三条**均承重**（不触发「不承重计缺陷」）。`op=4`（仅负责人）改前已 `false`（旧代码唯一判到位）。

## T127.2 独立复现「改后是好的」

- 冻结点（`/root/t127v`，树锚 `3f4e4750…`/18 · `09b88329…`/29）实测输出：

```
[canTrade] op=1 only has_risk_post         => false
[canTrade] op=2 only has_settle_role       => false
[canTrade] op=3 only is_management_role    => false
[canTrade] op=4 only is_responsible        => false
[canTrade] op=5 control(all four false)    => TRUE
[canTrade] op=6 control(no trading post)   => false
```

⇒ 三条单冲突位**各自 `false`**；**`op=5` 对照「四冲突位全 false + 全授权」仍 `TRUE`**（**误伤方向已查：无**）；`op=6`（无交易岗）`false`。`evalPermission`（经 `RiskEngine::check`）同态：`0x100E` / `0x100E` / `0x100F` / `0x100F` / `0x0000`（对照放行） / `0x100B`。**改前 `evalPermission` 输出与改后逐字相同**（T127.7 佐证「重构语义中性」）。

## T127.3 「共用」是否名副其实（本单最关键）

在**共用纯函数**（`model.h` `permissionLeaderForbidden` / `permissionPostConflict`）上做单点变异，**双侧检测器**（`canTrade` 用 `risk_state_data_model_test`、`evalPermission` 用 `risk_engine_permission_test`）同看：

| 变异 | 变更 | `canTrade` 消费点 | `evalPermission` 消费点 | 判定 |
|---|---|---|---|---|
| `M-C` | 共用函数删 `is_management_role` | 红（`data_model_test.cc:381`） | 红（`permission_test.cc:123/124` 0x100F） | **两点同时红** ✅ |
| `M-D` | 共用函数删 `has_settle_role` | 红（`data_model_test.cc:366`） | 红（`permission_test.cc:88/89` 0x100E） | **两点同时红** ✅ |

- 原始输出（`M-C`）：`data_model_test: 82 checks, 1 failures` ＋ `risk_engine_permission_test: 29 checks, 2 failures`；
- 原始输出（`M-D`）：`data_model_test: 82 checks, 1 failures` ＋ `risk_engine_permission_test: 29 checks, 2 failures`；
- **自撰探针交叉印证**：`M-C` 下 `canTrade(仅管理层)=TRUE` **且** `evalPermission(仅管理层)=0x0000`；`M-D` 下 `canTrade(仅结算岗)=TRUE` **且** `evalPermission(仅结算岗)=0x0000`。⇒ **单点编辑同时改变两个消费点** = **同一实现被两处共用**（若为「各自实现」则只有一处会变）。**「共用」名副其实**。
- **复位 + 重编译回绿**：`M-C` 恢复为冻结点 `model.h`（`cmp` 逐字节 `identical`）后重编译 ⇒ `data_model_test: 82 checks, 0 failures -> PASS`、`risk_engine_permission_test: 29 checks, 0 failures -> PASS`；探针三单条件复归 `false`。

## T127.4 用例独立性与计数

- **析取可证**：三条新用例（`data_model_test.cc` `test_can_trade_{risk_post,settle_role,management_role}_only`）**各自仅置位一条、并将其余三条冲突位显式写 `false`**，且**同批次载入对照条目 `op=2`（四冲突位全 false）断言 `canTrade==true`** ⇒ 一条即足以阻断 = **析取**（非合取）。**本席自撰探针以「仅一条置位」组合独立复现同一结论**（T127.1/2）。
- **计数以实跑为准**：`data_model_test` 实跑 **`82 checks, 0 failures`**、`risk_engine_permission_test` 实跑 **`29 checks, 0 failures`**；`ctest -N` 实测总 **34**（CTP 不可得）/ **38**（CTP 可用）。改前（`HEAD` 测试源码 × `HEAD` 代码）实跑 **`72 checks, 0 failures`** ⇒ 产出方自述「**72 → 82 checks**」**实测一致**。

## T127.5 既有断言对照（无静默放宽）

`data_model_test.cc` 权限批次（`⑩`）**断言行级对照**（左 `HEAD` / 右 冻结点）：

- **保留**：`HG_CHECK(perm != nullptr)`、`HG_CHECK_EQ(perm->individual_quota, 500)`、`HG_CHECK(s.canTrade(9001,7,1))`、`HG_CHECK(!s.canTrade(9001,8,1))`、`HG_CHECK(!s.canTrade(9001,7,3))`、`HG_CHECK(!s.canTrade(9002,7,1))`、`HG_CHECK(!s.canTrade(9999,7,1))` —— **逐字未改**。
- **更替（1 条）**：`HG_CHECK(perm->has_settle_role)` → 迁至新记录 `9101`（`carried->has_settle_role`，**同命题**）；`HG_CHECK(!perm->is_management_role)`（「未置位者保持缺省 `false`」）→ 改为 `HG_CHECK(carried->is_management_role)`（「显式置位者保持 `true`」，**反向命题**）。⇒ 「**缺省 `false` 保持**」这一性质**不再有直接断言**（与独立复核席 `te-verify-t126b` 的 `OBS-T126b-2` **同源**）。
- **夹具变更必要性（本席独立证明）**：`HEAD` 原夹具令 `9001` 携带 `has_settle_role=true` 却断言 `canTrade(9001,7,1)==true` —— **该断言本身锁定了缺陷形状**。实测：`HEAD` 测试源码（`72 checks`）链接**改后** `hg_risk_state` ⇒ **恰好 1 红**：`data_model_test.cc:275: s.canTrade(9001, 7, 1)`。⇒ 产出方把 `9001` 改为「四冲突位全 false 的纯净交易岗」、把结算岗承载迁至 `9101`，**系修复必需**，非 gratuitous 放宽。

## T127.6 `E4` 两口径 + 聚合门禁

- `bash tools/arch-check/scan_lock_free.sh`（默认三目录并集，**含 `risk_state` 与 `risk_engine`**）⇒ **`rc 0`**，`WARN 3` 全部落在 `risk_state`（`risk_snapshot.cc:235` `clone` / `risk_state.cc:22` 构造 / `risk_state.cc:41` `rebuild`）——**均为未覆盖判据 C（动态分配）的信息性提示**，**源码行未改**（`risk_snapshot.cc` 的 `new` 在 `clone` 内、diff 未触及；`risk_state.cc` 不在 diff 内），行号 `231→235` 系 `canTrade` 注释块上移 4 行所致。分模块 `--root` 亦 `rc 0`（`risk_state` 17 文件 / `WARN 3`；`risk_engine` 28 文件 / `WARN 0`）。
- `bash tools/arch-check/arch-check.sh` ⇒ **`rc 0`**（汇总 `通过(0)=7 命中(1)=0 用法错误(2)=0`；`E1`–`E7` 全 0）。
- **未引入锁/互斥/同步 I/O/新增分配**：对 diff 的新增行做 `mutex|lock|guard|condition_variable|fsync|::write|::read|::send|::recv|sleep|new |malloc|…` 扫描 ⇒ **0 命中**；两 helper 为 `const PermissionEntry&` → `bool` 的纯 inline，无分配。

## T127.7 反退化 4 变异（独立复现，全部命中）

| 变异 | 本席自撰/既有检测器结果 | 与自述 |
|---|---|---|
| `M-A` 删 `canTrade` 的 `permissionLeaderForbidden` 调用 | `data_model_test: 82 checks, 1 failures`（`:381`） | 红 1 ✅ |
| `M-B` 删 `canTrade` 的 `permissionPostConflict` 调用 | `data_model_test: 82 checks, 2 failures`（`:351`/`:366`） | 红 2 ✅ |
| `M-C` 共用函数删 `is_management_role` | `data_model_test` 红 1 **＋** `risk_engine_permission_test` 红 2 | 两点同红 ✅ |
| `M-D` 共用函数删 `has_settle_role` | `data_model_test` 红 1 **＋** `risk_engine_permission_test` 红 2 | 两点同红 ✅ |

`evalPermission` **语义中性**佐证：`risk_engine.cc` 两处以「等价布尔式 → 共用函数调用」替换（`is_responsible||is_management_role`、`has_risk_post||has_settle_role`），改前 vs 改后 `RiskEngine::check` 对同六组合的输出**逐字相同**。

## T127.8 改动面 / 锚点 / 残留

| 项 | 产出方自述 | 本席独立实测 | 一致 |
|---|---|---|---|
| 改动面 | 4 文件 / +128 −13 | `git diff --shortstat -- cpp/src/risk_state cpp/src/risk_engine` = **128 insertions(+), 13 deletions(-)**（`TEST_RESULT.md` 另计，非交付代码） | ✅ |
| 冻结点 `risk_state` | `3f4e4750…`/18 | **`3f4e475007591f61255812d23b6355e8` / 18**（3×10s 取样一致） | ✅ |
| 冻结点 `risk_engine` | `09b88329…`/29 | **`09b883292dc5732fa930e7ff297c2a62` / 29**（3×10s 取样一致） | ✅ |
| 改前锚 | `b47c2fd3…`/18、`24df98dc…`/29 | **`b47c2fd38cac4e369644f9c8dcdaecc6`/18**、**`24df98dcb72799c634fc53bb46d4823c`/29**（= `git HEAD` 该二路径；`HEAD` = `27e98ba`，改动**未提交**，工作区态） | ✅ |
| 构建告警 | 0 warning / 0 error | `grep -ciE "warning:\|error:"` 两轨均 **0** | ✅ |
| `ctest` | 38/38 · 34/34 | 实测 **38/38**（`-DCTP_SDK_ROOT=<sdk>` ⇒ `CTP_FOUND=ON`）/ **34/34**（`CTP_FOUND=OFF`） | ✅ |
| 共享树残留 | —— | `cpp/src` 下 `*.orig`/`*.bak`/`*MUT*`/`*.rej` = **0**；`git status --porcelain` 仅 4 交付文件 + `docs/TEST_RESULT.md` | ✅ |

## T127.9 观察清单（均无阻断）

| 编号 | 严重度 | 类别 | 结论 |
|---|---|---|---|
| —— | —— | **缺陷** | **无**（改前坏/改后好/共用名副其实、反退化 4 变异、门禁全绿、既有断言未静默放宽 —— 均独立证实） |
| `OBS-T127-1` | 信息 | 影响面（生产调用点） | 全仓穷举：`canTrade` 的调用点**全部在 `tests/`**（`data_model_test.cc`），`src/main` **0 调用** ⇒ `T-127` 属**预防性收口**（消除「第二个消费点」与 `evalPermission` 的潜在分叉），**无在线的生产行为变更**。抽出的**共用纯函数**即本单主要价值。 |
| `OBS-T127-2` | 低 | 测试断言面 | 与 `te-verify-t126b` `OBS-T126b-2` **同源**：`data_model_test.cc` 原断言 `HG_CHECK(!perm->is_management_role)`（缺省 `false` 保持）被**更替**为「显式置位保持 `true`」⇒ **正向承载获补**，但「缺省 `false` 保持」**不再有直接断言**（仅由 `canTrade(9001,…)` 为 `true` 间接蕴含）。风险低（plain `bool` + 整结构体拷贝），**不阻断**。 |
| `OBS-T127-3` | 信息 | 范围边界 | `canTrade(operator, sub, product)` **无手数入参** ⇒ `individual_quota`（超个人额度）**不可表达**，与 `evalPermission` 的 `0x100D` 分支存在**固有范围差**；产出方声明「非本项缺口、未改签名」**属实**（`risk_snapshot.h:124` 签名未变）。 |

## T127.10 未决（本席范围外，不裁）

- `T-126` 的 **delta 复检**（`risk_engine` 冻结点前移 `24df98dc…`→`09b88329…`）归 `te-verify-t126b`，本席不裁。
- `T-127` 是否满足 `DONE`，由编排者落门禁结论；本席**不置 `DONE`**。

## T127.11 纯追加证明（自指值不写死）

- 追加前基线：`md5sum docs/TEST_RESULT.md` = `cf912232de57f58118c738107beed741` / **4787 行**（**紧邻写入前 4 次 ×10s 取样不变**：`cf912232…`/4787 ×4 @ `22:52`）。〔该基线与内存中的 `69fa98ab…`/4630 **不同**，系复核期 `te-verify-t126b` 追加其 `T-126` 增量复核章所致 ⇒ **以实时值为准**。〕
- 追加后：`head -4787 docs/TEST_RESULT.md | md5sum` = 基线 `cf912232de57f58118c738107beed741`（⇒ 前 4787 行**逐字节未变**）。追加后 `wc -l` 与 `md5sum docs/TEST_RESULT.md` **以实时为准**（写死会因追加本身而失效）。

---

# `T-126` 增量复检 —— 补遗 `T126b.12`（`OBS-T126b-2` 精确化，2026-09-23 追加）

> 由 `te-verify-t126b` 追加（**纯追加**；补遗原因：另一独立信道 `te-verify-t127` 提供了与 `OBS-T126b-2` 同源的证据，本席**已自行独立复现**后据实精确化原观察，**不改前文**）。

- **本席独立复现（自撰命令；非采信他席自述）**：取 `HEAD` 原测试源 `git show HEAD:cpp/src/risk_state/tests/data_model_test.cc`（md5 `ea8ba67076997446b111c48dc476ae49`，**72 checks**）链**改后库**（现树 `risk_state/src/*.cc` + 现树 `-I` 头）编译运行 ⇒ **恰 1 红**：`data_model_test_head.cc:275: s.canTrade(9001, 7, 1)`（`72 checks, 1 failures`）。
- **精确化**：`HEAD` 夹具中 `9001` **带 `has_settle_role=true`**；`T-127` 收严 `canTrade` 后该记录**不再可交易** ⇒ 原夹具断言 `s.canTrade(9001,7,1)` **必然变红**。故 `T-127` 把 `has_settle_role` 从 `9001` 移出、改由新记录 `9101` 承接正向承载断言，**是必需（被 `T-127` 的 `canTrade` 收严所迫）而非无谓削弱**。
- ⇒ **`OBS-T126b-2` 的定级（低、不阻断）不变**，但措辞应读作「**断言面在 `canTrade` 语义变更下被迫重构**」；且「缺省 false 保持」属性仍**无直接断言**（仅由 `!s.canTrade(9001,…)` 间接蕴含）这一事实不变。
- 追加前基线：`md5sum docs/TEST_RESULT.md` = `35599d3f4a25cc5a13237950e012f41f` / **4918 行**（写入前 **3 次 ×10s 取样不变**：`35599d3f…`/4918 ×3 @ `14:53:56`/`:54:06`/`:54:16`）。
- 追加后：`head -4918 docs/TEST_RESULT.md | md5sum` = `35599d3f4a25cc5a13237950e012f41f`（⇒ 前 4918 行**逐字节未变**）。追加后 `wc -l` 与 `md5sum docs/TEST_RESULT.md` **以实时为准**（写死会因追加本身而失效）。

---

# `T-128` 独立复核证据（2026-09-22）

> **复核席**：`te-verify-t128`（独立复核；**自撰探针 / 自设变异**，不复用产出方用例或产出方声明的变异）。
> **对象**：`T-128` 统一 CTP 头名变量源（来源 = `T-033`/`T-027` 复核观察 `OBS-T033b-3`）。
> **代码版本**：`cpp/**` 锚点对应检查点提交 **`ee05769`**（`chore(checkpoint): 工作区快照 #3`，2026-09-22 23:48:28）。
> 复核时 `HEAD` 已前移至台账提交 **`d9a71b8`**（第 80 轮补）；**实测 `git diff --stat ee05769 HEAD -- cpp/` = 空**（`cpp/**` 未被台账提交触及）⇒ 本单源码锚点不受影响。
> **环境**：CMake **4.2.3**；官方 CTP **v6.7.13**（分离式）位于 `<repo>/sdk/v6.7.13_20260225_api`；合并版 CTP Mini 位于 `/home/ctpmini/ctpmini_admin/ctp_sdk`。
> **隔离副本**：`/root/t128v`（布局 `<root>/{cpp,docs,java,proto,sdk,tools,web}`；排除 `build*`/`_deps`）。**共享源树只读**：`git status --porcelain` 全程为空。
> **变异纪律**：所有变异仅在隔离副本内进行（`.orig` 备份 → 改 → 跑 → 复位 → 逐字节校 md5）；**共享树与副本 `*.orig/*.bak/*.rej` = 0**，`MUTATION` 仅命中 `docs/**` 既有散文与第三方 `_deps/protobuf-src`（protobuf 自带 PDDM 宏，先存）。

## T128.0 冻结点 / 独立复算锚点

| 项 | 口径 | 实测值（取样 `2026-09-23T00:07:36+08:00`） | 编排者所给 | 一致 |
|----|------|------|------|------|
| `cpp/src` | `find … -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` | `327118d860c1618ecc9609f7d95b1d0e` / **115 文件** | `327118d8…` / 115 | ✔ |
| `cpp/cmake` | 同上 | `dda00ee5994b0daa8e345991ccf91821` / **3 文件** | `dda00ee5…` / 3 | ✔ |
| **交付域** | `find cpp/cmake cpp/ctp_smoke -type f -not -path '*/build*' \| LC_ALL=C sort \| xargs md5sum \| md5sum` | **`654cf2f2997084d29b194e26bdb8afca` / 9 文件** | 产出方自报同值 | ✔ |

单文件（源树实测）：

| 文件 | md5 |
|------|-----|
| `cpp/cmake/HgCtpStyle.cmake`（新增） | `bc2d90eb2cd031c741a39208818ae0e0` |
| `cpp/cmake/HgDependencies.cmake`（改） | `1b43b61c14d7005d82890d2c9d4be829` |
| `cpp/ctp_smoke/CMakeLists.txt`（改） | `daf53783fa6ccc564f156b632ecfb1b0` |
| `cpp/ctp_smoke/ci/check_ctp_found_parity.sh`（新增） | `2efe5ede5c2c9daa0060e20566ec3082` |
| `cpp/cmake/HgCtpLocate.cmake`（未改，他人在用） | `51052fbb99220887d37cd83e04132050` |

- **`build*` 含入版**（产出方判「不可复现、勿引用」）：同口径得 **`8b728ec062f003fe7b1cb308dbf6d5c5` / 81 文件**。本席**连续两次取样同值** ⇒ 该值非「立即不可复现」，而是**随构建产物漂移**（脆弱）⇒ **交付域锚点一律取上方 `-not -path '*/build*'` 版**（采纳产出方口径）。

## T128.1 判据 A —— 「单一来源」是否真成立（穷举）

**判定**：**成立**。存在**唯一**缓存入口 + **唯一**派生实现，**无第三独立解析点**。

**证据（独立穷举，`文件:行号`）**：

- **唯一入口**（可 `-D` 覆盖的 cache 变量）：`cpp/cmake/HgCtpStyle.cmake:44` `set(HG_CTP_HEADER_STYLE "CLASSIC" CACHE STRING …)`。
  全仓 `grep -rn "CACHE" cpp/cmake/*.cmake cpp/ctp_smoke/CMakeLists.txt | grep -i hg_ctp` ⇒ 与「头名/风格」相关的 `CACHE` **仅此 1 处**（其余 `HG_CTP_REQUIRE_SDK` / `HG_CTP_INCLUDE_AS_SYSTEM` 与头名无关）。
- **唯一派生实现**：`cpp/cmake/HgCtpStyle.cmake:51` `macro(hg_ctp_resolve_style)`；两个消费方**共用同一 `include`**：
  - 主工程：`cpp/cmake/HgDependencies.cmake:55` `include("…/HgCtpStyle.cmake")` + `:56` `hg_ctp_resolve_style()`
  - 探路件：`cpp/ctp_smoke/CMakeLists.txt:73` `include("…/../cmake/HgCtpStyle.cmake")` + `:74` `hg_ctp_resolve_style()`
- **派生量非缓存**：`HG_CTP_TRADER_HEADER` / `HG_CTP_MD_HEADER` / `_API_CLASS` / `_SPI_CLASS` / `_PRIVATE_TOPIC_ARITY` / `_STYLE_MERGED` 均为宏内**普通 `set`**（`:53–73`），**无 `CACHE`** ⇒ 不可能各自被 `-D` 分别覆盖形成第二源。
- **两种拼写均为真**（非笔误）：`HG_CTP_TRADER_HEADER` = 派生输出（`HgCtpStyle.cmake:53/60`、`HgDependencies.cmake:81`）；探路件另用**私有** `HGCP_CTP_TRADER_HEADER`（`ctp_smoke/CMakeLists.txt:78–83`，**取值全部来自** `HG_CTP_*`）。二者**非**两处独立解析。
- **大小写不敏感复核**：`grep -rniE "hg_?cp_ctp_(trader|md)_header|header_style"`（排除 `build*`/`_deps`/`*.md`）命中的**配置面文件**仅 = `HgCtpStyle.cmake`（定义）/`HgDependencies.cmake`（消费）/`ctp_smoke/CMakeLists.txt`（消费）/`ci/run_probe.sh`+`ci/check_ctp_found_parity.sh`（调用方传参）/`src/hgcp_ctp_headers.h.in`+`src/ctp_include_probe.cpp`（消费生成宏）。**无新落点**。
- **仍消费头名的下游**：`HgCtpLocate.cmake` 经 `HG_CL_TRADER_HEADER`/`HG_CL_MD_HEADER` 入参（`HgDependencies.cmake:81–82`、`ctp_smoke/CMakeLists.txt:101–102`）⇒ 它是**消费方**（由调用方喂入派生值），**非**解析点。

## T128.2 判据 B —— 判据脚本不空转（★自设变异，非复用产出方）

**判定**：**主工程侧分歧 ⇒ `rc=1`（两分支均承重）**；**探路件侧分歧 ⇒ `rc=3`（见 T128.5 观察 `OBS-T128-2`）**。

**判据脚本**：`cpp/ctp_smoke/ci/check_ctp_found_parity.sh`：一次 top-level `cmake -S cpp` 配置，抓两行 `[HG-CTP-FOUND] role=smoke\|main found=… style=… trader_header=… md_header=…`，先比 `found`（`:92`），再**全字段逐字**比（`:95`）。退码 `0/1/3`。

**基线（不改任何东西，隔离副本）**：

| 工况 | 命令要点 | 两侧结论 | rc |
|------|----------|----------|----|
| 无 SDK | `--build-dir /root/t128v-b1` | `smoke=main=OFF` | 0 |
| 官方 SDK + `CLASSIC` | `--sdk-root …/v6.7.13_20260225_api --style CLASSIC` | `smoke=main=ON`（全字段逐字同） | 0 |
| CTP Mini + `MERGED` | `--sdk-root /home/ctpmini/…/ctp_sdk --style MERGED` | `smoke=main=ON` | 0 |
| 老式单目录（自建：同目录含两分离头+两库） | `--sdk-root /root/t128v-oldsingle --style CLASSIC` | `smoke=main=ON` | 0 |

**自设变异（4 例；每例 `.orig` 备份 → 改 → 跑 → 复位 → 校 md5 逐字节回原）**：

| 变异 | 改动（隔离副本内） | 期望 | 实测 | 复位 |
|------|-------------------|------|------|------|
| **M1** 主工程侧绕开宏自解析 | `HgDependencies.cmake` 把 `include+resolve` 换成 `set(HG_CTP_TRADER_HEADER "ThostFtdcUserApi.h")` | `rc=1` | **`rc=1`** —— `[FAIL] 判定分歧：smoke=ON main=OFF`（官方 SDK：探路件找 `ThostFtdcTraderApi.h` 得、主工程找 `ThostFtdcUserApi.h` 不得） | ✔ `1b43b61c…` |
| **M2** 探路件侧分歧 | `ctp_smoke/CMakeLists.txt` 在 `hg_ctp_resolve_style()` 后加 `set(HG_CTP_TRADER_HEADER "ThostFtdcUserApi.h")` | `rc=1` | **`rc=3`**（不可判定） —— 探路件先行 `FATAL`（`ctp_smoke/CMakeLists.txt:149`），主工程**未及打印** ⇒ `smoke='OFF' main=''` | ✔ `daf53783…` |
| **M3** 删旧入口守卫 | `HgCtpStyle.cmake` 删除 `:83–100` 两守卫块 | 见下 | **parity 仍 `rc=0`**（守卫与 parity 是**两套机制**，见 T128.5） | ✔ `bc2d90eb…` |
| **M4** 主工程侧分歧（`found` 同、仅头名不同） | 同 M1，但 SDK = **自建含双交易头**（`ThostFtdc{Trader,User}Api.h` + 两库）`/root/t128v-synth` | `rc=1`（字段分支） | **`rc=1`** —— `[FAIL] 判定字段不一致（found 相同但入口字段不同）` | ✔ `1b43b61c…` |

⇒ **判据的 `found` 分支（`:92`）与 `字段` 分支（`:95`）均被真实打红 ⇒ 判据不空转**（主工程侧的第二来源会被抓住，含「`found` 同而仅头名不同」这一更难的构造）。
⇒ **复位证明**：四例复位后三文件 md5 逐字节回原（表列），隔离副本 `*.orig`/`MUTATION` 残留 = 0。

## T128.3 判据 D —— 旧入口防退化守卫是否有效

**判定**：**有效**（冲突即配置期 `FATAL`；一致则放行）。

- **守卫实现**：`HgCtpStyle.cmake:83–100` —— 若缓存中残留旧入口（`HG_CTP_TRADER_HEADER`/`HG_CTP_MD_HEADER`）且与当前风格**派生的**值**冲突** ⇒ `FATAL_ERROR`。
- **D-1（冲突必抓）**：`cmake -S /root/t128v/cpp -B … -DHG_CTP_HEADER_STYLE=CLASSIC -DHG_CTP_TRADER_HEADER=ThostFtdcUserApi.h` ⇒ **`rc=1`**，`CMake Error at cmake/HgCtpStyle.cmake:85` `[CTP] 检测到过时的旧入口缓存项 …`。
- **D-2（一致放行）**：`-DHG_CTP_TRADER_HEADER=ThostFtdcTraderApi.h`（= CLASSIC 派生值）⇒ **`rc=0`**，无报错（设计如此：避免无谓打断既有构建目录）。
- **变异 M3 反证**：删守卫后，**D-1 同命令 ⇒ `rc=0`**（静默接受冲突旧值）⇒ **守卫确为承重**（非装饰）。
- **真实场景佐证**：共享树既有陈旧构建目录 `cpp/build-ctp/CMakeCache.txt:210/223` 等**同时**含 `HG_CTP_HEADER_STYLE:STRING=MERGED` 与 `HG_CTP_TRADER_HEADER:STRING=ThostFtdcUserApi.h`（T-128 前态遗留）。二者**自洽**（MERGED ⇒ 派生 `ThostFtdcUserApi.h`）⇒ 若在那类目录就地重配会被放行；一旦用户改口 `-DHG_CTP_HEADER_STYLE=CLASSIC` 而不清旧项即被守卫拦下（D-1 已实测）。

## T128.4 判据 C —— 外围件（★判据未接线）

**判定**：**判据未接线（可调用 ≠ 已接线）**。脚本本体非空转，但**无任何调用点** ⇒ **不参与任何门禁**。

**证据**（`grep -rn "check_ctp_found_parity" .` 排除 `.git`/`.codebuddy`，全仓仅 5 处命中）：

| 可能接线面 | 实测 | 结论 |
|-----------|------|------|
| `ctest`（`add_test`） | `grep -rn "add_test" cpp/…` ⇒ `ctp_smoke` 仅注册 `rsk02.ctp_header_cxx11_compat`（`ctp_smoke/CMakeLists.txt:214`）| **未注册** |
| `arch-check.sh` 聚合入口 | `tools/arch-check/arch-check.sh:71–77` 聚合 **E1–E7** 七脚本，**无** parity | **未聚合** |
| `run_probe.sh`（同目录 CI 脚本） | `cpp/ctp_smoke/ci/run_probe.sh` 无 `parity` 调用 | **未调用** |
| `mvn`（`java/pom.xml` exec） | 无引用 | **未调用** |
| 其余命中 | `TASKS.md`/`BOARD.md`（台账散文）、`HgDependencies.cmake:106`/`ctp_smoke/CMakeLists.txt:135`（**注释**）、脚本自述 `:15` | 均**非**调用点 |

⇒ **须由编排者登记接线任务**（否则该判据永不生效）。产出方已**如实披露**「待回写（非本席写域）：`tools/arch-check/README.md` 宜补…」——**未**声称已接线，故本条为**外围件缺口**、非「虚假声称」。

## T128.5 判据 E —— 三项未决（独立实测）

**(i) 主工程对 `-DHG_CTP_HEADER_STYLE=MERGED` 会怎样？** —— **既非静默给出错误的 `CTP_FOUND`、亦非完全静默**，而是**按 SDK 分两种后果**（独立以「仅主工程」最小 harness `/root/t128v-mainonly` 实测）：

| 工况（仅主工程 `include(HgDependencies.cmake)`） | 实测 | rc |
|------|------|----|
| `MERGED` + **官方** SDK | `found=OFF` ⇒ **`FATAL_ERROR`** @ `HgDependencies.cmake:120`（「已显式指定 CTP SDK 根，但解析必需头/库失败」） | 1 |
| `MERGED` + **CTP Mini**（含 `ThostFtdcUserApi.h`） | `found=ON`、**配置期无报错**；但**构建期** `cpp/src/ctp_adapter/src/module.cc:7:10: fatal error: ThostFtdcTraderApi.h: No such file or directory` ⇒ **`rc=2`** | 0→2 |

⇒ 「是否应当显式拒绝」**是真缺口、但属诊断质量**：官方 SDK 下**已失败**（只是报错未点名 `MERGED`）；合并 SDK 下**配置静默通过、到编译期才以「缺分离头」失败**。**均无「静默错误的 `CTP_FOUND`」**。**不阻断**（`CH-015` 交付基线 = 官方分离式）⇒ 与产出方披露 **②（潜伏陷阱）一致**，本席**实测证实**。

**(ii) 旧入口移除是否属契约变更（需补 `CH`）？** —— **本席判断：不属**。依据：

1. **权威冻结面**：`docs/ARCHITECTURE.md` §7.2.1（`:2196–2204`）对 CTP SDK 的「获取方式」仅列 `find_package`/本地包 + **「`CTP_FOUND` 宏控制」**——**未**把 `HG_CTP_TRADER_HEADER`/`HG_CTP_MD_HEADER`/`HG_CTP_HEADER_STYLE` 列为冻结接口。
2. **接口冻结文档**：`java/CONTRACTS.md` 仅 1 处涉 CTP（`:509` `/ws/quote` 节流表），**与 CMake 变量无关**。
3. **仓内无既有用法**：`grep -rn "HG_CTP_TRADER_HEADER=\|HG_CTP_MD_HEADER=\|DHG_CTP_TRADER_HEADER"`（排除 `build*`/`.git`）⇒ **除守卫自身报错文案外 0 命中**（无 `-D` 调用点、无 CI/脚本/文档规定性用法）⇒ 移除**不破坏仓内既有用法**。**外部**影响 = 构建命令行行为变更（旧 `-D` 不再作入口；冲突时由守卫转为配置期失败，属**改善**）。
4. ⇒ 该变更落在**构建配置内部细节**（非项目级契约）⇒ **免 `CH`**；唯一需登记的是「`ctp_smoke` 为冻结产物、其改动须由对应任务『重做重冻结』」——本单**已授权**改该目录（`TASKS.md` `T-128` 备注已声明「连带 `T-027` 冻结点前移」）。

**(iii) 交付报告 §七 其余登记项** —— 逐项独立复核：

| 登记项 | 独立实测 | 属实 |
|--------|----------|------|
| 交付域冻结点 `654cf2f2…`/9（`-not -path '*/build*'`） | 实测同值同数 | ✔ |
| 唯一派生实现 `hg_ctp_resolve_style()`、主/探路共用 | T128.1 已证 | ✔ |
| 判据形态（两行 `[HG-CTP-FOUND]` 全字段逐字比、退码 `0/1/3`） | T128.2 已证（0/1/3 均实测到） | ✔ |
| 反例工况 ①`CTP Mini`+`MERGED`②官方+`-DHG_CTP_TRADER_HEADER=ThostFtdcUserApi.h` ⇒ 探路件「可得」/主工程 `FATAL` | T128.6 已**独立复现** | ✔ |
| 判据非空转（主工程侧硬编码 ⇒ `rc=1`，含字段分支） | T128.2 M1/M4 | ✔ |
| 「`tools/arch-check/README.md` 现 0 处提及 `HgCtpStyle`/`单一入口`」 | 实测 `README.md` **无** `HgCtpStyle`、**无**「单一入口」字样（**但 `:311` 已引 `HG_CTP_HEADER_STYLE`**）⇒ 作为字面断言**成立** | ✔（措辞可更精确） |
| 存量缺陷 ②「主工程 `ctp_adapter` 硬编码分离式头名」 | T128.5(i) 实测构建失败 @ `module.cc:7`；`module.cc:7`/`ct_gateway_ctp.cc:22–23`/`ct_spi_impl.h:27–28` + 2 测试实测硬引 | ✔ |

## T128.6 —— 改前根因独立复现（前态缺陷确实存在）

**方法**：隔离副本 `/root/t128pre` = `/root/t128v` 的拷贝 + 仅回退 T-128 三文件（`HgDependencies.cmake`/`ctp_smoke/CMakeLists.txt` 取自 T-128 父提交 `04699a8`；删 `HgCtpStyle.cmake`）。

| 前态工况 | 探路件（`subdirectory` 先求值） | 主工程 | 结论 |
|----------|------------------------------|--------|------|
| CTP Mini + `-DHG_CTP_HEADER_STYLE=MERGED` | `[RSK-02] 发现 CTP SDK`（**ON**，注册 `rsk02`） | `[CTP] CTP SDK 不可得` ⇒ **`FATAL`** @ `HgDependencies.cmake:95` | **互相矛盾**（`rc=1`） |
| 官方 SDK + `-DHG_CTP_TRADER_HEADER=ThostFtdcUserApi.h` | `[RSK-02] 发现 CTP SDK`（**ON**） | `[CTP] CTP SDK 不可得` ⇒ **`FATAL`** | **互相矛盾**（`rc=1`） |

**改后**：同工况 ①（CTP Mini + `MERGED`）⇒ `smoke=main=ON` **一致**（`rc=0`）；②（旧 knob）⇒ 守卫 `FATAL`（`HgCtpStyle.cmake:85`）。
⇒ **根因（两处独立解析源）与「诊断误导」症状均被独立证实**，且改后消除。

## T128.7 判据 F —— 不回归（构建 + `ctest` + 聚合门禁）

**方法**：隔离副本三轨 + 老式单目录 + 探针 standalone；计数**以 `ctest -N` 为准**（非 `grep -c @Test`）。

| 轨 | 配置要点 | 构建 | `warning:`/`error:`（实测） | `ctest -N` | `ctest` |
|----|----------|------|------|------|------|
| **ctp**（官方 SDK） | `-DCTP_SDK_ROOT=…/v6.7.13_20260225_api` | `rc=0` | **0 / 0** | **38** | **38/38 通过** |
| **stub**（无 SDK） | 无 `CTP_SDK_ROOT` | `rc=0` | **0 / 0** | **34** | **34/34 通过** |
| **fetch** | `-DCTP_USE_FETCH_DEPS=ON` | **`rc=2`** | 2 / 4（**全为第三方 protobuf 源码头 `port.h`**） | —— | 构建失败（见下） |
| 探针 standalone | `-S cpp/ctp_smoke`（`project()` 生效） | `rc=0` | **0 / 0** | **1** | **1/1 通过** |
| 老式单目录 | 见 T128.2 | —— | —— | —— | 判据 `rc=0` |

- **聚合门禁**：`bash tools/arch-check/arch-check.sh` ⇒ **`rc=0`**（`E1–E7` 全 `0`）。`E4` = **实扫**：`待扫描文件 47 个`、命中 0（`rc=0`），3 处 `[WARN·未覆盖判据]` 属信息性（判据 C/D 未覆盖），**根集** = `cpp/src/{order_engine,risk_engine,risk_state}`（**不含** `ctp_adapter`/`hgcp`）。
- **「0 告警」核对**：`ctp`/`stub`/`standalone` 轨**实测 0 告警**；**`fetch` 轨非 0 告警**（其 4 error + 2 warning 均出在**第三方 protobuf `port.h`**，自家代码 0 告警）⇒ 台账「三轨…均 0 告警」的**合并措辞**对 `fetch` 轨不精确（见 `OBS-T128-3`）。

**`fetch` 轨 `rc=2` 系存量缺陷（`T-129`）—— 独立证实「改前同点同错」**：

- `payload_codec.cc`（`cpp/src/hgcp/src/`）**前/后态逐字节相同**：现树 md5 `de717d111d19849a301351ae49e9f3ea` = `git show 04699a8:…payload_codec.cc` md5；`git diff 04699a8 ee05769 -- cpp/src/hgcp/src/payload_codec.cc` **空**。
- **T-128 未触及该 locus**：`git diff --name-only 04699a8 ee05769 -- cpp/src/hgcp cpp/src/CMakeLists.txt proto` **空**；`payload_codec.cc` 引入提交 `8b6e55f` 经 `git merge-base --is-ancestor 8b6e55f ee05769` = **YES**（**先于** `T-128`）。
- **端到端同点同错**：取 `compile_commands.json` 中该 TU 的**原样编译命令**（含 `-I/root/t128pb-src/src` = protobuf **源码**目录、`-Wall -Wextra -Werror`），分别编译**前态文件**与**后态文件** ⇒ **错误文本 diff 为空**，均为：
  `…/google/protobuf/port.h:46:41: error: unused parameter 'size' [-Werror=unused-parameter]` 与 `…port.h:53:46: …`。
  完整 fetch 构建则 `rc=2`，`gmake[2]: *** [src/hgcp/CMakeFiles/hg_hgcp.dir/build.make:199: …/payload_codec.cc.o] Error 1`（另 `:213` `pb_transport_policy.cc.o` 同错）⇒ **与 `T-129` 描述逐字一致**、**非本单引入**。

## T128.8 缺陷 / 观察清单

**缺陷**：**0 项「阻断级」**。以下均**不阻断**转 `DONE`（依编排者判据「有条件通过 + 0 阻断 = 可转 `DONE`」）。

| 编号 | 级别 | 事项 | 证据 / 复现 | 影响面 |
|------|------|------|-------------|--------|
| `OBS-T128-1` | **中**（外围件缺口，非虚假声称） | **判据未接线** —— `check_ctp_found_parity.sh` 无任何调用点（未注册 `ctest`／未入 `arch-check` E1–E7／`run_probe.sh` 未调／`mvn` 未调）⇒ **判据永不生效** | T128.4 表；`grep -rn "check_ctp_found_parity" .` 仅 5 命中（3 散文 + 2 注释 + 自述） | 建议编排者登记接线任务（如纳入 `ctest` 或 `arch-check`），或至少落入 `tools/arch-check/README.md` |
| `OBS-T128-2` | 低（判据覆盖方向盲区） | **探路件侧**分歧 + **显式 SDK 根**时，判据返回 **`rc=3`（不可判定）而非 `rc=1`** —— 因 `add_subdirectory(ctp_smoke)` 先于 `include(HgDependencies.cmake)`，探路件 `FATAL` 使主工程**未及打印**判定行 | T128.2 M2：`smoke='OFF' main=''`；基线（不改）同工况 `rc=0` | 判据仍**非零**（非静默放行）；仅「FAIL vs 不可判定」区分度下降。若 CI 以 `rc==1` 判定失败则可能漏；以 `rc!=0` 判定则无碍 |
| `OBS-T128-3` | 低（台账措辞） | 台账「**三轨** + 老式单目录 + 探针 standalone 均 **0 告警**」对 **`fetch` 轨不成立** | T128.7：`fetch` 实测 2 warning + 4 error（**均第三方 `port.h`**；自家代码 0 告警） | 仅措辞；`fetch` 失败已另**显式披露**（`T-129`）⇒ 非隐瞒 |
| `OBS-T128-4` | 建议 | 主工程对 `MERGED` 的失败**未点名风格**（官方 SDK 下报「解析必需头/库失败」，未提「主工程仅支持 `CLASSIC`」）；合并 SDK 下**配置静默通过**、到编译期才失败 | T128.5(i) | 诊断质量；对应产出方未决 (i)，建议措辞订正（**非** `CH`） |

## T128.9 结论

- **判据 A（单一来源）**：**通过** —— 唯一入口 + 唯一派生 + 无第三解析点（穷举含大小写变体）。
- **判据 B（不空转）**：**通过** —— 主工程侧第二来源触发 `rc=1`（`found` 分支与**字段**分支**均**被自设变异打红）；**探路件侧**分歧触发 `rc=3`（`OBS-T128-2`）。
- **判据 C（外围件）**：**未接线**（`OBS-T128-1`）—— 脚本非空转但**不参与任何门禁**。
- **判据 D（守卫）**：**通过** —— 冲突必 `FATAL`、一致放行；变异删除后失效（承重）。
- **判据 E（未决）**：三项**均独立实测**并回答；(i) 为**诊断质量**缺口（无静默错误的 `CTP_FOUND`）；(ii) 本席判**不属契约变更（免 `CH`）**；(iii) 登记项**逐项属实**（含 `README` 字面断言与存量缺陷 ②）。
- **判据 F（不回归）**：**通过** —— `ctp` **38/38**、`stub` **34/34**、standalone **1/1**、老式单目录一致、聚合 **`rc=0`**；`fetch` **`rc=2`** 经**改前同点同错**证实为**存量缺陷**（`T-129`，**非本单引入**）。
- **改前根因**：两处独立解析源 ⇒ 同配置下 `CTP_FOUND` 矛盾（**独立复现**）；改后消除。

> **最终结论：`T-128` = 有条件通过 / 0 阻断缺陷 ⇒ 可转 `DONE`。**
> 条件（不阻断、须由**编排者**登记闭环）：`OBS-T128-1` 判据接线（否则判据不生效）；`OBS-T128-2/3/4` 为低/建议项，宜随台账一并处置。

---

**纯追加证明**：追加前基线 `md5sum docs/TEST_RESULT.md` = `f6c4b582ac37d8ac203a5f30815f0aea` / **4930 行**（写入前 **3 次 ×10s 取样不变** @ `00:19:02`/`:12`/`:22`）；追加后 `head -4930 docs/TEST_RESULT.md | md5sum` = `f6c4b582ac37d8ac203a5f30815f0aea`（⇒ 前 4930 行**逐字节未变**）。追加后行数/md5 **以实时为准**（写死会因追加本身而失效）。

---

# `T-128` 独立复核证据 —— 补充（同一任务 · 6 项）（2026-09-22）

> **性质**：`T-128` **同一任务**的补充复核（编排者补充交底 6 项），**非新任务**、**不另开单**。承接上章 `# \`T-128\` 独立复核证据（2026-09-22）`。
> **同一纪律**：隔离副本自设变异；共享树只读；`LC_ALL=C sort` 口径；计数以 `ctest -N` 为准。

## T128.10 补充 0 —— 锚点复核（含被作废值）

| 口径 | 实测 @ `2026-09-23T00:21:49+08:00` | 判定 |
|------|-----------------------------------|------|
| `find cpp/cmake cpp/ctp_smoke -type f -not -path '*/build*' \| LC_ALL=C sort \| xargs md5sum \| md5sum` | **`654cf2f2997084d29b194e26bdb8afca` / 9 文件** | ✔ 与编排者订正后值、与产出方自报**均吻合** |
| 编排者**作废**的 `cpp/ctp_smoke`（含 `build*`）= `87041c712c3f53940e32ac4d082c927d` / 78 | 现口径下含 `build*` 实测 = **`8b728ec062f003fe7b1cb308dbf6d5c5` / 81** | 作废**成立**（含 `build*` 不可复现）；**且文件数已由 78 漂到 81**（构建产物累积）⇒ 佐证「构建产物永不作锚点」 |

四单文件（`HgCtpStyle bc2d90eb…` / `HgDependencies 1b43b61c…` / `ctp_smoke/CMakeLists.txt daf53783…` / `check_ctp_found_parity.sh 2efe5ede…`）**逐一同值**。

## T128.11 补充 1 —— 判据退码语义（`0/1/2/3`）

**判定**：退码**互斥**（每次运行恰返回一个）；但**存在「该红而退 `3`」的路径**；且**头注未文档化 `rc=2`**。两行 `[HG-CTP-FOUND]` **确由同一次配置产生**。

**证据（独立抽取脚本全部 `exit`/`STATUS`）**：

| 退码 | 触发点（`check_ctp_found_parity.sh`） | 语义 |
|------|--------------------------------------|------|
| `0` | `:43`（`--help`）、`:111` `exit "${STATUS}"`（`STATUS=0`） | 通过（一致） |
| `1` | `:94`/`:99`/`:107` ⇒ `STATUS=1` ⇒ `:111` | **分歧**（`found` 不同 / 字段不同 / `--expect` 不符） |
| `2` | `:44` 未知参数 | 用法错误 —— **头注（`:25`）只写 `0/1/3`，未文档化 `2`** |
| `3` | `:71`（`FOUND_LINES` 为空）；`:83`（`smoke` 或 `main` 为空） | **不可判定**（配置未产出并列判定行） |

- **「该红而退 `3`」路径：存在** —— ① 探路件侧分歧 + 显式根（本席变异 **M2**：`smoke='OFF' main=''` ⇒ `:83` ⇒ `3`）；② **任何与分歧无关的配置中止**（如缺 protobuf、SDK 路径错）同样退 `3`。
- **两行由**同一次**配置产生**：脚本内 **恰 1 次** `cmake` 执行（`:60` `cmake "${args[@]}"`；另 2 处 `cmake` 字样为注释/`echo`，非执行）；`:65` 从该**单一** `${LOG}` grep。实测 `/root/t128v-b2/configure.log`（**单文件**）含 **2** 行 `[HG-CTP-FOUND]`（`role=smoke` + `role=main`）⇒「**同一配置下比较**」**成立**。
- **可绕过性**：`3 ≠ 0` ⇒ 对以「**非零即失败**」判定的门禁**安全**；对以「**仅 `rc==1` 为失败**」判定的门禁**不安全**（`3` 会被当作「不可判定/skip」）。⚠️ 该判据**当前无任何调用点（`OBS-T128-1`）** ⇒ 上述语义**尚无门禁承接** —— 接线时**须显式约定 `3` 的处置**（建议：`3` 亦视为失败，或加「配置必须成功」前置）。

## T128.12 补充 2 —— 第二项存量缺陷（主工程在合并式 SDK 上不可编译）

**判定**：**证实**。属「**已声明而主工程不可用**」的**潜伏陷阱（轻）**，但**同一新文件已注释披露 + `CH-015` 基线 = 官方分离式 ⇒ 不阻断**；「主工程**显式拒绝 `MERGED`**」为**应有处置（建议，非阻断）**。

**(a) 实测（两路取证）**：

1. **端到端（真 CTP Mini 合并 SDK）**：`cmake -S cpp -DHG_CTP_HEADER_STYLE=MERGED -DCTP_SDK_ROOT=/home/ctpmini/ctpmini_admin/ctp_sdk` ⇒ **配置成功**（`role=smoke found=ON` + `role=main found=ON`）；`cmake --build` ⇒ **`rc=2`**，错误原文：
   `cpp/src/ctp_adapter/src/module.cc:7:10: fatal error: ThostFtdcTraderApi.h: No such file or directory`
2. **受控构造（自建 merged-only SDK `/root/t128v-mergedonly` = 真 Mini 的 4 头 + 2 库）**：`g++ -std=c++11 -I<dir> -c` ⇒ `#include <ThostFtdcTraderApi.h>` **失败**（`No such file or directory`）；`#include <ThostFtdcUserApi.h>` **通过**。
   - **该构造的局限**：依赖本机遗留的 `CTP Mini` 资产（`/home/ctpmini/...`）；合并版 `UserApi` **不属** `CH-015` 交付基线，故此路仅用于**触达分支**、不代表受支持配置。

**硬引点（实测，非转述）**：`cpp/src/ctp_adapter/src/module.cc:7`（`#include <ThostFtdcTraderApi.h>`）、`src/ct_gateway_ctp.cc:22-23`、`include/hg/ctp_adapter/ct_spi_impl.h:27-28`，另 2 测试（`tests/ct_lifecycle_ctp_test.cc:14-15`、`tests/ct_spi_test.cc:13-14`）。
**关键机制**：`module.cc:4-8` 的 include **仅由 `#if HG_CTP_FOUND` 守卫**（= SDK 是否可得），**不受 `HG_CTP_HEADER_STYLE` 影响** ⇒ 一旦 `CTP_FOUND=ON`（合并 SDK 下即 `ON`），**必** `#include` 分离式头 ⇒ 必失败。**非静默错误**。

**(b) 判决依据（读原文，非凭转述）**：
- `docs/ARCHITECTURE.md` §7.2.1（`:2196–2204`）：CTP SDK 依赖行**冻结形态 = 官方分离式** `ThostFtdcTraderApi.h` + `ThostFtdcMdApi.h`；「获取方式…`CTP_FOUND` 宏控制」。
- `CH-015` 的**权威留痕**：`docs/REQUIREMENTS/INDEX.md:669`（「交付基线已由 `CTP Mini`（合并版）改为**官方 CTP v6.7.13（分离式）**（`CH-015`，用户已裁定）」）+ `PROJECT.md` 变更表（`:654`）+ `ARCHITECTURE.md` §3.1/§9/§13 与抬头。⚠️ **`docs/DECISIONS/` 下无 `CH-015` 独立文件**（该目录仅 `websocket-selection.md`）⇒ 请勿按「`docs/DECISIONS/CH-015.md` 有原文」定位。
- `cpp/cmake/HgCtpStyle.cmake:32-38` **自披露**：「`MERGED`（历史对照）…**仅供探路件做历史对照**，主工程在合并版 SDK 上**会在编译期如实失败（非静默）**」。
⇒ **判决**：属**已声明限制 + 诊断质量缺口**（官方 SDK 下 `FATAL` 报错**不点名风格**；合并 SDK 下配置**静默通过**），**非** correctness/security 缺陷；`CH-015` 基线 = 官方分离式 ⇒ **不阻断**。

**(c) 应有处置建议（非阻断）**：在**主工程消费点**（`cpp/cmake/HgDependencies.cmake`，**非**共享文件 `HgCtpStyle.cmake` —— 否则会破坏探路件的合法 `MERGED` 用途）加一条：`HG_CTP_HEADER_STYLE STREQUAL "MERGED"` ⇒ `FATAL_ERROR`（「主工程仅支持 `CLASSIC`，见 `CH-015`；`MERGED` 仅供探路件历史对照」）。**理由**：与本文件自身纪律一致（`HgCtpStyle.cmake:80`「宁可配置期报错，不可静默走另一套」），消除「配置期静默通过 / 报错不点名」的误导路径。**若不改**，至少应把失败信息补上「风格 → 不可用」的因果。

## T128.13 补充 3 —— `T-027` 冻结点前移（判据口径 **未变**）

**判定**：`run_probe.sh` **本体与判据口径/计数均未变**；**制品 md5 已变** ⇒ **`T-027` 冻结点在制品层面已前移**，但**无需因计数做 delta 复检**。

| 项 | 前态（`04699a8`） | 后态（T-128） | 变? |
|----|-------------------|---------------|-----|
| `cpp/ctp_smoke/ci/run_probe.sh` | 同 | `git diff 04699a8 ee05769 -- …/run_probe.sh` = **空** | **未变** |
| `run_probe.sh` 判据集 | B1/B2/B3/B4/B2b/B7/B7b/B7c/B8/B5/B6/B6b = **12 条** | **同 12 条** | **未变** |
| `run_probe.sh` 结果（官方 SDK，隔离副本实跑） | **`PASS=12 FAIL=0`** | **`PASS=12 FAIL=0`** | **未变** |
| 前/后 `run_probe.sh` 逐条 `[PASS]` 行 `diff` | —— | **空（集合与结论逐字节同）** | **未变** |
| `cpp/ctp_smoke/CMakeLists.txt` | `f78a75168519f42838dc21b42f93c00d` | `daf53783fa6ccc564f156b632ecfb1b0` | **已变** |

⇒ 结论：`T-128` **未改变 `T-027` 的判据条数与口径**（`B6`/`B6b`/`PASS` 计数不变）；其**制品**（`ctp_smoke/CMakeLists.txt`）已变 ⇒ 台账「`T-027` 冻结点前移」的表述**对制品成立、对判据口径不成立**。**须做**：T-027 冻结点 md5 **重登记**（非重跑）。

## T128.14 补充 4 —— `README` 待回写项的定级

**判定**：属**判据类交付的外围件 ③（README / 自测记录）缺失 ⇒ T-128 的**未完成项（低 · 非阻断）**，**非**「建议级改进」。

**依据（本项目纪律：判据类交付须连核 ① 聚合入口 ② 接线点 ③ README/自测记录）**：
- ① 聚合入口：**无**（`arch-check.sh` 不含该脚本）；② 接线点：**无**（`ctest`/`run_probe.sh`/`mvn` 均无）——二者同 `OBS-T128-1`；
- ③ README / 自测记录：**缺** —— 实测 `cpp/ctp_smoke/` **无任何 `*.md`/`README*`**；脚本**仅有头注**（`:1–26`），**无独立 README、无自测记录**。
- 编排者/产出方所提「在 `tools/arch-check/README.md` 补一句」是**可接受落点之一**，但**非唯一**；更直接落点 = 脚本旁的 README/自测记录段。
- 复核 `tools/arch-check/README.md`：**无** `HgCtpStyle`、**无**「单一入口」字样（该**字面断言成立**）；但 **`:311` 已引 `HG_CTP_HEADER_STYLE`** 并指向 `cpp/ctp_smoke/CMakeLists.txt:5–24`、`cpp/cmake/HgDependencies.cmake:10–11`（T-128 后行号已挪，指针**宜复核**）。

## T128.15 补充 5 —— 产出方隔离副本 / 共享树残留

- **产出方隔离副本** `/root/t128iso/`：**存在**（含 `prefix`/`postfix`/`mut1`/`mut2`/`po-*`/`pf-*`/`sdk-both`/`sdk-flat` 等）。**本席未复用其中任何产物作为证据**；仅据路径与时间（`2026-09-22 22:59–23:30`）作「产出方确实做过隔离验证」的**辅证**。
- **共享源树残留**：`*.orig`/`*.bak`/`*.rej` = **0**；`MUTATION` 命中仅 `docs/**`（既有散文）与第三方 `_deps/protobuf-src`（protobuf 自带宏，先存）⇒ **无人在共享树就地变异**。
- 另注：`.codebuddy/worktrees/bg-01a0bd8e/`（harness 生成的 git worktree，`.gitignore` 忽略）非本任务产物、非残留。

## T128.16 补充 —— 最终结论（不变）

**`T-128` = 有条件通过 / 0 阻断缺陷 ⇒ 可转 `DONE`。**
补充 6 项**未改变**上章结论，仅**补全**两项事实（退码语义 `0/1/2/3` + 「该红而退 3」路径；第二项存量缺陷的 (a)(b)(c) 裁定）与**修正一处口径**（`T-027` 冻结点：**制品前移、判据未变**）。**非阻断观察累计（含上章）**：`OBS-T128-1`（中·判据未接线，**含** README/自测记录缺失）、`OBS-T128-2`（低·`rc=3` 方向盲区）、`OBS-T128-3`（低·台账「三轨 0 告警」对 fetch 不精确）、`OBS-T128-4`（建议·`MERGED` 失败未点名风格）。

---
**纯追加证明（补充章）**：追加前 `md5sum docs/TEST_RESULT.md` = `deb0635fcbaa0345941c1a0d20305906` / **5124 行**（写入前 **3 次 ×10s 取样不变** @ `00:22:22`/`:32`/`:43`）；追加后 `head -5124 docs/TEST_RESULT.md | md5sum` = **`deb0635fcbaa0345941c1a0d20305906`**（⇒ 前 5124 行**逐字节未变**）。追加后行数/md5 **以实时为准**（写死会因追加本身而失效）。

---

# `T-129` 独立复核证据（2026-09-23）

> **席位**：`te-verify-t129`（独立复核席）。**对象**：`T-129` —— 修复 `fetch` 轨构建失败（`hgcp` 经 `hgcp.pb.h` 引入 protobuf **源码头** ⇒ `-Werror=unused-parameter`）。
> **方法**：一切变异 / 回退 / 构建均在**隔离副本** `/root/t129v`（布局 `<root>/{cpp,docs,java,proto,sdk,tools,web}`，排除 `build*`/`_deps`）内进行；**共享源树 `cpp/**` 只读、零改动**。
> **锚点口径**：`find … | LC_ALL=C sort | xargs md5sum | md5sum`，**一律排除 `build*`/`_deps`/`target`**，**连同文件数**记录；跨目录比较统一在各自 repo 根下用**相对路径**（前缀不同会致假差异）。
> **产出方自述仅作线索**，以下每项均**自撰探针 / 自设变异**独立取证。

## T129.0 复核对象、冻结点与「仅一文件」核实

| 项 | 产出方自述 | 本席实测（命令 / 输出） | 结论 |
|----|-----------|------------------------|------|
| 唯一改动 | `cpp/cmake/HgDependencies.cmake`（+30/−0 纯追加 1 段） | `git diff --name-status 0bf9a65 d4a2a27` = **仅 `M cpp/cmake/HgDependencies.cmake`**；`git diff --numstat` = **`30 0`** | ✔ 吻合 |
| 该文件 md5 | 新 `c1f5aa6837fd5fda63cf387d38f23198`（原 `1b43b61c…`） | `md5sum` = `c1f5aa68…`；`git show 0bf9a65:…` = `1b43b61c…` | ✔ 吻合 |
| `cpp/src/**` 零改动 | `327118d860c1618ecc9609f7d95b1d0e` / 115 | `git diff 0bf9a65 d4a2a27 -- cpp/src` **空**；锚 = `327118d8…` / **115** | ✔ 吻合 |
| `cpp/cmake` 锚 | `bfe01dd49edef5318777862997d929ec` / 3 | 实测 `bfe01dd4…` / **3** | ✔ 吻合 |
| 交付域锚（`cpp/cmake`+`cpp/ctp_smoke`，排除 build 目录） | `82bd22457dbccc834738ffa432d179fc` / 9 | 实测 `82bd2245…` / **9** | ✔ 吻合 |
| 未放宽 `-Werror` | 未放宽任何 `-Werror` | 改动文件内 **0 处** `-Wno-`/`-Werror`（仅注释提及）；`cpp/CMakeLists.txt:36 add_compile_options(-Wall -Wextra -Werror)` **未动** | ✔ 吻合 |

> 取样时刻：`2026-09-23T06:41`–`07:53 +08:00`。**以实时为准**。

## T129.1 判据 A —— 改前复现 / 改后通过（双向）

**独立做法**：把**改前文件**（`git show 0bf9a65:cpp/cmake/HgDependencies.cmake`，md5 `1b43b61c…`）放入副本，配置/构建 `fetch` 轨（`CTP_USE_FETCH_DEPS=ON` + 官方分离式 CTP + `CLASSIC`）；随后换回**改后文件**同树重配重编。

| 步骤 | 命令（摘要） | 关键输出 | rc |
|------|-------------|---------|----|
| 改前配置 | `cmake -S cpp -B build-fetch -DCTP_USE_FETCH_DEPS=ON …` | 配置成功 | 0 |
| 改前 `flags` | `grep CXX_INCLUDES …/hg_hgcp.dir/flags.make` | `… -I/root/t129v/_pb_src_cache/src`（**非 SYSTEM**） | —— |
| **改前构建** | `cmake --build build-fetch` | 4 `error:`（`port.h:46:41` / `port.h:53:46` `unused parameter 'size' [-Werror=unused-parameter]`）+ 2 `warning:`；`gmake[2]: *** […:199: …/payload_codec.cc.o] Error 1`、`[…:213: …/pb_transport_policy.cc.o] Error 1` | **2** |
| 改后配置 | 换 `c1f5aa68…` 同树重配 | 配置成功 | 0 |
| 改后 `flags` | 同上 | `… -isystem /root/t129v/_pb_src_cache/src`（**SYSTEM**） | —— |
| **改后构建** | `cmake --build build-fetch` | `0 error` / `0 warning` | **0** |

**旁证（与 `T-128` 证据逐字一致）**：改前失败 TU 与行号 `build.make:199`/`:213`、错误文本 `port.h:46/53` **完全一致** ⇒ 本单复现的正是同一存量缺陷（**非本单引入**）。

**机制级独立探针**（绕过整套构建，直击告警源）：
```
printf '#include <google/protobuf/io/coded_stream.h>\nint main(){return 0;}\n' > probe.cpp
g++ -std=c++11 -Wall -Wextra -Werror -I       <pb3.21.12/src> -c probe.cpp   # rc=1  port.h:46/53
g++ -std=c++11 -Wall -Wextra -Werror -isystem <pb3.21.12/src> -c probe.cpp   # rc=0
```

## T129.2 判据 B —— 根因裁定是否成立 + 处置是否「过宽」

**（a）是否真「无从回避」经 `hgcp.pb.h` 包含 protobuf 源码头？—— 是，且不止于自家头。**
- 包含链（文件∶行号为实测）：`cpp/src/hgcp/include/hg/hgcp/payload_codec.h:27 #include "hgcp.pb.h"` → 生成码 `hgcp.pb.h:23 #include <google/protobuf/io/coded_stream.h>` → `coded_stream.h:134 #include <google/protobuf/port.h>` → `port.h:46/53`（`SizedDelete`/`SizedArrayDelete` 具名未用参）。
- **关键**：protoc **生成码本身** `hgcp.pb.cc:8` 也 `#include <google/protobuf/io/coded_stream.h>`，而 `hgcp.pb.cc` 是 `hg_hgcp` 的源（`cpp/src/hgcp/CMakeLists.txt:70`）。⇒ 即便把**自家头**的包含面收窄（前向声明 / 只引公开头），`hg_hgcp` 仍必编 `hgcp.pb.cc`，其**必然**包含 protobuf 头 ⇒ **无从回避**。

**（b）是否存在更窄正解？—— 「库目标 SYSTEM」确是较宽形态；存在一个更窄且**实测等效**的形态；但**未发现任何具体掩盖**。**
- 本席实测三条候选：
  1. **库目标标 SYSTEM**（= 交付形态，`set_target_properties(libprotobuf PROPERTIES SYSTEM TRUE)`）⇒ `-isystem`，rc=0。
  2. **供给目标接口标 SYSTEM**（`get_target_property(...); target_include_directories(hg_deps_protobuf SYSTEM INTERFACE …)`；**= 交付件 fallback 分支的形态**）⇒ 亦 `-isystem`，rc=0（本席探针 `CAND-N`）。
  3. **标错目标**（`set_target_properties(hg_deps_protobuf PROPERTIES SYSTEM TRUE)`，即在**无自有 include 的 INTERFACE 目标**上置 SYSTEM **属性**）⇒ 仍 `-I`，**rc=2**（变异 `MUT-B` 证伪）。
- 「过宽」的**实际影响面**：形态 1 使 protobuf 头对**所有 `libprotobuf` 消费方**变 SYSTEM；形态 2 仅对**本工程供给目标 `hg_deps_protobuf` 的消费方**。**本工程内 `hg_deps::protobuf` 的唯一消费方 = `hg_hgcp`**（全仓 `CMakeLists.txt` 实测：仅 `cpp/src/hgcp/CMakeLists.txt:84`）⇒ **两形态在本工程内作用面相同**（均 → `hg_hgcp` 及其测试）。
- **故**：形态 1 相对形态 2「更宽」属**理论宽度**；本席**未能构造任何「本工程对 protobuf 头的误用被掩盖」的具体反例**（`-Werror` 对**自家代码**的约束不受影响：`add_compile_options` 未动、`hgcp.pb.cc` 等自家/生成源仍全额受检）。**判为「建议级」观察**（见 `OBS-T129-1`），**非缺陷**。
- 另一「更窄」思路（对 `hg_hgcp` **定向** `-Wno-unused-parameter`）经判**不可行**：告警源 `port.h` 被 `payload_codec.cc` / `pb_transport_policy.cc` / `hgcp.pb.cc` / 各测试 **多 TU** 包含 ⇒ 定向豁免必然退化为**目标级（乃至全工程级）** `-Wno-unused-parameter`，反而**连带压制自家代码** ⇒ 比 SYSTEM 更宽、更差。

**裁定：根因成立；交付形态（库目标 SYSTEM）为「正解之一」且与项目既有纪律（CTP SDK 头 SYSTEM、`ARCHITECTURE.md §1.2`「严格告警只约束自家代码」）同族；「过宽」属建议级、不阻断。**

## T129.3 判据 C —— 反退化（**自设**变异，非复用产出方 2 例）

> 产出方 2 例 = 「revert ⇒ rc=2 / restore ⇒ rc=0」。本席**另设** 2 例 + 1 形态探针；每例：副本内改 → 重配 → **先证 `flags`/`build_rc`** → 复位 → 逐字节校 md5。

| 变异 | 改动 | 期望 | 实测 | 复位校核 |
|------|------|------|------|---------|
| **MUT-A** | `SYSTEM TRUE` → `SYSTEM FALSE`（库目标） | `-I` ⇒ rc=2 | `flags` 变 **`-I`**；build **rc=2**（4 error，`port.h:46/53`） | 复位后 md5 = `c1f5aa68…`（逐字节） |
| **MUT-B** | 标记**移错目标**：`set_target_properties(hg_deps_protobuf PROPERTIES SYSTEM TRUE)` | `-I` ⇒ rc=2 | `flags` 仍 **`-I`**；build **rc=2**（4 error） | 同上 |
| **CAND-N** | 主路径改用**供给目标 SYSTEM INTERFACE**（fallback 同形） | `-isystem` ⇒ rc=0 | `flags` = **`-isystem`**；build **rc=0** | 同上 |

**复位 + clean 重编译证明**：恢复 `c1f5aa68…` → 重配（`flags` 回 `-isystem`）→ **删净 `build-fetch/src/hgcp/**/*.o` 后全量重编** ⇒ **rc=0 / 0 error / 0 warning**（⇒ 无陈旧对象假绿；且证明「承重方向」双向：去之则红、复之则绿）。**副本内 `*.orig`/`*.bak` 归零**（仅本席自留 `HgDeps.fixed.bak` 备份于副本根，非共享树）。

## T129.4 判据 D —— 三轨 + 门禁不回归

**三轨 `ctest`（以 `ctest -N` 计数 + 实跑 rc 为准，非 grep 近似）**：

| 轨 | 配置 | `ctest -N` 计数 | 实跑 | build error/warning |
|----|------|----------------|------|--------------------|
| `ctp` | `CTP_USE_FETCH_DEPS=OFF` + 官方分离式 + `CLASSIC` | **38** | **38/38 passed**，rc=0 | 0 / 0 |
| `stub` | 同上去 SDK | **34** | **34/34 passed**，rc=0 | 0 / 0 |
| `fetch` | `CTP_USE_FETCH_DEPS=ON` + 官方 + `CLASSIC` | **38** | **38/38 passed**，rc=0 | 0 / 0 |

⇒ 三轨 = **`ctp 38/38 · stub 34/34 · fetch 38/38`**，与产出方一致。

**聚合门禁**：`bash tools/arch-check/arch-check.sh` ⇒ **rc=0**（`E1–E7` 全 `0`）。
**`E4`**（实扫，非 SKIP）：**待扫描文件 47 个 / 命中 0 / `[WARN·未覆盖判据]` 3 处**（`risk_snapshot.cc:235`、`risk_state.cc:22`、`risk_state.cc:41`，均 `new`）；扫描根 = `cpp/src/{order_engine,risk_engine,risk_state}`（**不含** `ctp_adapter`/`hgcp`）⇒ **与基线同、未恶化**。

## T129.5 判据 E —— `cpp/build-*` 重配合法性与口径

> 产出方如实披露：三轨原为 `Mini SDK + HG_CTP_HEADER_STYLE=MERGED`，为主工程硬编码分离式头名所不容 ⇒ 按 `CH-015`（官方分离式 + `CLASSIC`）**重配**了 `cpp/build-{ctp,stub,fetch}`。

**（a）原配置下 `build-ctp` 是否确实 `rc=2`？—— 独立复现，是。**
本席在副本内造 `MERGED + Mini` 配置（`-DCTP_SDK_ROOT=/home/ctpmini/ctpmini_admin/ctp_sdk -DHG_CTP_HEADER_STYLE=MERGED`）：
- 配置 **rc=0**，两行 `[HG-CTP-FOUND]`（`role=smoke`/`role=main`）均 `found=ON style=MERGED trader_header=ThostFtdcUserApi.h`；
- 构建 `hg_ctp_adapter` ⇒ **rc=2**：`cpp/src/ctp_adapter/src/module.cc:7:10: fatal error: ThostFtdcTraderApi.h: No such file or directory`。
⇒ 与产出方「原配置下 `module.cc:7` 不可编译、属 `T-128` §2 ② 存量缺陷」**一致**；`T-129` 改动未触及该 locus（唯一改动为 `HgDependencies.cmake` 的 protobuf FetchContent 分支）。

**（b）重配是否改变三轨 `ctest` 语义/计数口径？**
`MERGED + Mini` 下 `ctest -N` 计数 **亦为 38**（配置成功、测试**注册数不变**）；但该配置**根本编不出** `hg_ctp_adapter` ⇒ `ctp`（及 `fetch`）轨**不可能有 38/38 通过**。⇒「`38/38`」是**重配后（= 与 `CH-015` 交付基线一致的官方分离式）才成立**的表述；**计数口径（38）不因重配而变，通过性才是重配后才有**。

**（c）是否越界？是否影响「交付基线」声索？—— 不越界、不影响。**
- `cpp/build*` **全部 gitignore、0 个入库文件**（`git ls-files 'cpp/build*'` = 0；`cpp/.gitignore:2–5`）⇒ 重配**不构成任何受控源变更**。
- 权威「交付基线」= `CH-015`：`docs/REQUIREMENTS/INDEX.md:669` + `docs/ARCHITECTURE.md`（多处，如 `:2882` 依赖对齐表）⇒ 交付基线 = **官方 CTP v6.7.13 分离式**（**不**由 `cpp/build-*` 承载）。重配**正是使本地构建目录对齐该基线**，**不改变**基线本身。⚠️ `docs/DECISIONS/` 下确**无** `CH-015` 文件（实测仅 `websocket-selection.md`）——与派单所述一致。
- 产出方与编排者**均已如实披露**（`BOARD.md` 第 83 轮）。

## T129.6 判据 F —— 同类扫描「不受影响」结论（**未否证**）

**独立穷举「非 SYSTEM 第三方头消费点」**（`grep target_include_directories`/`target_link_libraries`，全仓 `CMakeLists.txt`）：

| 消费点 | 第三方头 | 引入形态 | 结论 |
|--------|---------|---------|------|
| `hg_hgcp`（`src/hgcp/CMakeLists.txt:84` → `hg_deps::protobuf`） | protobuf | 修复前 `-I` / 修复后 `-isystem` | **本单已修** |
| `hg_ctp_adapter`（`:45 SYSTEM`）/ `hgcp_ctp_probe`（`ctp_smoke:193 SYSTEM`） | CTP | `-isystem`（实测 `ctp_smoke` flags = 两条 `-isystem …/traderapi_se…`） | 已 SYSTEM |
| `hgcp_proto_smoke`（main 轨） | protobuf（**系统包** = IMPORTED） | IMPORTED ⇒ SYSTEM 默认 | 无 `-I`（实测 flags 无 protobuf 目录） |
| `hgcp_proto_smoke`（**fallback 轨**） | protobuf **3.19.6**（FetchContent） | `-I`（实测 `flags` 含 `-I…/_deps/protobuf-src/src`） | **见下：不受影响** |
| `Threads::Threads`（各处） | 无头 | —— | 不适用 |

**fallback 轨「不受影响」的独立验证（版本依赖 + 真机跑通）**：
- `port.h` 实测：**3.21.12** = `src/google/protobuf/port.h` **80 行**、`:46` `SizedDelete` / `:53` `SizedArrayDelete`；**3.19.6** = 同名文件 **40 行**、`SizedDelete` **0 命中**（= 该 80 行版本为 ≥ 3.20 引入）。
- 机制级探针：**3.21.12 头 + `-I` ⇒ rc=1**（`port.h:46/53`）；**3.19.6 头 + `-I` ⇒ rc=0**。
- 真机跑通：副本内 `-DHG_PROTOBUF_FORCE_FETCH=ON -DHG_PROTOBUF_FETCH_VIA=URL -DHG_PROTOBUF_TARBALL=<3.19.6 全量 tarball，SHA256=`9a301cf94a8ddcb380b901e7aac852780b826595075577bb967004050c835056`（与内置值一致）>` ⇒ 解析 **protobuf 3.19.6**、`-I` 引入，**build rc=0（0 error/0 warning）、`ctest` 3/3 passed**。

⇒ **产出方「产品内唯一非 SYSTEM 第三方头消费点已修；fallback（3.19.6）不受影响」的结论成立**，**未否证**。其登记于 `T-130` 的硬约束「fallback 一旦抬到 ≥ 3.20 须同法标 SYSTEM」经本席证实**必要**。

## T129.7 判据 G —— `< 3.25` fallback 分支

- **(a) 披露诚实性**：产出方明写该分支「未做真机版本测试、仅以机制等价探针验证」。本机 `cmake --version` = **4.2.3**（唯一版本）⇒ 主路径被取用、`else` 分支**在本机不可达**，披露**属实**。
- **(b) 更可靠的验证方式（本席已做）**：该分支的**形态** = `get_target_property(... INTERFACE_INCLUDE_DIRECTORIES)` + `target_include_directories(hg_deps_protobuf SYSTEM INTERFACE …)` —— 本席以 **`CAND-N`** 在 4.2.3 上**原样注入该形态**并实测 ⇒ **`-isystem`、rc=0**。即：**分支的机制本身已被真机跑通**；未验证者仅「版本分派条件在 <3.25 上走 `else`」与「<3.25 有 `target_include_directories(... SYSTEM INTERFACE ...)`」，后者自 CMake 2.8.12 起即有。
- **(c) 残留是否可接受**：可接受（**建议级**）。理由：`cmake_minimum_required(3.16)`（`cpp/CMakeLists.txt:24`）⇒ `3.16–3.24` 属**受支持区间**、该分支**非死代码**；其机制已真机验证；`SYSTEM` 目标属性确为 3.25 引入（`VERSION_GREATER_EQUAL "3.25"` 判据正确）。**唯一未实测** = 一台 <3.25 的 CMake ⇒ 建议 CI 增一台旧版 CMake 跑一次该分支（登记为观察，不阻断）。

## T129.8 环境坑（非缺陷，留痕）

副本内 `proto_smoke` fallback 首跑曾 **rc=2**，错误为 `fatal error: error writing to /tmp/ccy3eXnE.s: No space left on device` —— 系本机 `/tmp` 为 **804M tmpfs（常满）**、gcc 临时 `.s` 写 `/tmp` 所致，**与产品无关**；`TMPDIR=/root/t129v/_tmp`（落 `/`）重跑 ⇒ **rc=0**。**留痕以免误判为「fallback 有缺陷」**。

## T129.9 缺陷 / 观察清单

**缺陷（`TE-T129-00x`）：0 项 · 含 0 项阻断级。** 未发现任何阻断 `T-129` 转 `DONE` 的问题。

**观察项（不阻断）**：

| 编号 | 级别 | 事项 | 证据 / 复现 | 影响面 |
|------|------|------|-------------|--------|
| `OBS-T129-1` | **建议** | 交付件**主路径**（库目标 `SYSTEM TRUE`）比**其自身 fallback 形态**（供给目标 `SYSTEM INTERFACE`）**更宽**；后者本席实测等效（`CAND-N`），但未构造出具体掩盖反例 | T129.2(b)；本工程 `hg_deps::protobuf` 唯一消费方 = `hg_hgcp` | 无功能影响；一致性建议（可统一为供给目标形态） |
| `OBS-T129-2` | **低** | `< 3.25` fallback 分支**未在真机旧版 CMake 上跑过**（机制已验、分派未验） | T129.7；本机仅 CMake 4.2.3 | 建议 CI 补一台 <3.25 版本 |
| `OBS-T129-3` | **低** | `cpp/build-*` 系**构建产物**（gitignore）已被按 `CH-015` 重配 ⇒ 三轨证据**不能从原始目录态复现**，须按基线重配后复现（本席已复现） | T129.5；`git ls-files 'cpp/build*'` = 0 | 无；证据可复现性说明 |
| `OBS-T129-4` | **低** | `proto_smoke` fallback 轨 protobuf 头为 `-I`（非 SYSTEM）—— **当下无碍**（3.19.6），但若 fallback 版本抬到 ≥ 3.20 即复发 | T129.6；已由 `T-130` 登记硬约束 | 未来回归风险；已挂任务 |

## T129.10 结论

- **判据 A（改前/改后双向）**：**通过** —— 改前 `rc=2`（`port.h:46/53`，`build.make:199`/`:213`）、改后 `rc=0`（`flags` `-I`→`-isystem`）。
- **判据 B（根因/过宽）**：**通过** —— 根因成立、「无从回避」经**生成码 `hgcp.pb.cc` 亦必含 protobuf 头**证实；「过宽」为**建议级**、**未发现具体掩盖**。
- **判据 C（反退化）**：**通过** —— **自设** 2 变异全杀（`MUT-A`/`MUT-B` ⇒ rc=2）、`CAND-N` 复绿（rc=0）、复位逐字节 + clean 重编 rc=0。
- **判据 D（不回归）**：**通过** —— 三轨 `38/38·34/34·38/38`、聚合 `rc=0`、`E4` 47/0/WARN3。
- **判据 E（重配）**：**通过** —— 原 `MERGED+Mini` 确 `rc=2`（`module.cc:7`）；计数口径不变（38）、通过性重配后成立；构建目录 gitignore、**不越界**、**不改变 `CH-015` 交付基线**。
- **判据 F（同类扫描）**：**通过（未否证）** —— 唯一非 SYSTEM 消费点已修；fallback（3.19.6）`-I` 下 `rc=0`（机制 + 真机双证）。
- **判据 G（<3.25 分支）**：**通过（建议级残留）** —— 披露诚实；分支机制已真机跑通；残留可接受。

> **最终结论：`T-129` = 有条件通过 / 0 阻断缺陷 ⇒ 可转 `DONE`。**
> 条件（不阻断）：`OBS-T129-1`（建议·统一 SYSTEM 作用面）；`OBS-T129-2`（低·CI 补旧版 CMake 验证 fallback）；`OBS-T129-3/4`（低·证据可复现性与未来回归说明，`OBS-4` 已由 `T-130` 承载）。

**独立复算锚点（本席实测；`LC_ALL=C sort`，排除构建产物；取样 `2026-09-23T06:41`–`07:53 +08:00`；以实时为准）**：

| 锚点 | 值 |
|------|----|
| `cpp/src` | `327118d860c1618ecc9609f7d95b1d0e` / **115** |
| `cpp/cmake` | `bfe01dd49edef5318777862997d929ec` / **3** |
| `cpp/cmake/HgDependencies.cmake` | `c1f5aa6837fd5fda63cf387d38f23198`（改前 `1b43b61c14d7005d82890d2c9d4be829`） |
| 交付域（`cpp/cmake`+`cpp/ctp_smoke`，排除 build 目录） | `82bd22457dbccc834738ffa432d179fc` / **9** |
| 共享树残留（`*.orig`/`*.bak`/`*.rej`，排除 build） | **0** |

---

**纯追加证明**：追加前 `md5sum docs/TEST_RESULT.md` = `a4b04ab89132e152cb4b73dff18fa4b9` / **5217 行**（写入前 **3 次 ×10s 取样不变** @ `07:54:07`/`:17`/`:27`）；追加后 `head -5217 docs/TEST_RESULT.md | md5sum` = **`a4b04ab89132e152cb4b73dff18fa4b9`**（⇒ 前 5217 行**逐字节未变**）。追加后行数/md5 **以实时为准**（写死会因追加本身而失效）。

# `T-130` 独立复核证据（2026-09-23）

> 复核者 = **独立复核席 `te-verify-t130`**（**自撰探针 / 自设变异**，**不复用产出方 `be-t130` 声明的变异**）。
> **共享源树只读**：`cpp/**`、`tools/**`、`docs/**` 一行未改（本证据章为**纯追加**）；**变异/构建一律在隔离副本** `/root/t130v`、`/root/t130v-mut`（排除构建目录；`TMPDIR=/`、`PARITY_TMPDIR=/` 以避本机 `/tmp` 804M tmpfs）。
> **开局残留扫描**：`git status --porcelain` **空**；`find`（排 build/.git）`*.orig`/`*.bak`/`*.rej` = **0**；源码树 `grep MUTATION` = **0**（仅 `docs/**` 既有散文 + 第三方 `_deps/protobuf-src` 自带宏）⇒ **无人在共享树就地变异**。
> **结论：有条件通过 / 0 阻断缺陷**（判据「有条件通过 + 0 阻断 = 可转 `DONE`」）。

---

## `T130.0` 复核对象、冻结点与独立复算

- **任务** `T-130`（`check_ctp_found_parity.sh` 接线 + 外围件补齐 + 诊断质量收口）；源码检查点 **`4adac20`**（HEAD）；台账 **`6d23524`**（第 86 轮）。
- **环境**：官方 CTP **v6.7.13（分离式）** = `<repo>/sdk/v6.7.13_20260225_api`；CMake **4.2.3**；GCC **15.2.0**；`nproc=2`。

**五件产出物逐一实测（均与产出方自述吻合）**：

| 文件 | 行数 | md5（实测） | 与自述 |
|---|---|---|---|
| `cpp/CMakeLists.txt` | 109 | `3431e7c4f9553d03bf849d5cba5bda81` | ✅ |
| `cpp/cmake/HgDependencies.cmake` | 299 | `2baf596f2a37c56782935968f2eea891` | ✅ |
| `cpp/ctp_smoke/ci/check_ctp_found_parity.sh` | 124 | `6bc8fab23c85b0e64e89dfaa95a39e45` | ✅ |
| `tools/arch-check/README.md` | 363 | `e8a68ae95718cea0c8ccdc8fb8ba85ed` | ✅ |
| **新增** `cpp/ctp_smoke/README.md` | 127 | `dc6adb94625e36cf8613a1b1fe23824f` | ✅ |

**冻结点（排除构建目录，`LC_ALL=C sort`；连续 3×10s 取样一致 @ `2026-09-23T08:54:27+0800`）**：
`cpp/src` = `327118d860c1618ecc9609f7d95b1d0e` / **115**（**未变**，`T-130` 未触碰 `cpp/src`）；`cpp/cmake` = `1e08aef44c9739b721a0f6dc5b7dd688` / **3**；**交付域**（`cpp/cmake`+`cpp/ctp_smoke`，`-not -path '*/build/*' -not -path '*/build-*/*'`）= **`288b3b33c80a13b3e07c684881030799` / 10**（9→10，+README）。**与编排者预复测逐字节吻合**。

---

## `T130.1`（A）接线**真实生效**且**非空转** —— 通过

- **`ctest -N`（以 `ctest -N` 为准）**：`ctp` **39** · `stub` **35** · `fetch` **39**（自述 `38/34/38 → 39/35/39`，**各 +1 吻合**）。`ctp_found_parity` 在**三轨均注册**（`Test #2`）。
- **三轨实跑**：`ctest --test-dir <轨> -R '^ctp_found_parity$'` ⇒ **三轨 `Passed`**（`ctp` 1.12s / `stub` 1.01s / `fetch` 1.03s）。
- **注册命令（`CTestTestfile.cmake` 原文）**：
  `"cmake" "-E" "env" "TMPDIR=…/build-ctp" "PARITY_TMPDIR=…/build-ctp" "bash" "…/check_ctp_found_parity.sh" "--sdk-root" "…/sdk/v6.7.13_20260225_api" "--style" "CLASSIC" "--expect" "on" "--build-dir" "…/build-ctp/ctp_found_parity_build"`，`TIMEOUT 300`（`CMakeLists.txt:108`）。
  ⇒ **临时目录确落构建树内**：实跑后 `cpp/build-ctp/ctp_found_parity_build/` 存在，**`/tmp/hg-ctp-parity-*` = 0**（未污染 `/tmp`）。`stub` 轨 `--expect auto`（无 SDK ⇒ 两端一致 `OFF`）。
- **自设变异 M5（证明「是接线让它执行」）**：删去 `cpp/CMakeLists.txt` 的 `if(UNIX) … endif()` 接线块 ⇒ 重配后 `ctest -N` **39→38**、`ctp_found_parity` **消失**（`grep` 仅余 `:84` 的注释行）⇒ **接线前该用例不执行**。
- **自设变异 M6a（参数写死是否被发现）**：把接线参数 `--style "${HG_CTP_HEADER_STYLE}"` 改成**写死 `--style MERGED`**（构建仍 `CLASSIC`）⇒ 该用例 **FAILED**（`[FAIL] 判定行不完整 … 未披露侧=main`）⇒ **错写死的参数会被判据抓出**。
- **自设变异 M6b（`--expect on|auto` 分支合理性）**：无 SDK 构建目录下，把 `_hg_parity_expect` 写死 `on`（正确逻辑应为 `auto`）⇒ **FAILED**（`[FAIL] 期望 found=ON，实测 OFF`）；正确逻辑（`auto`）⇒ **PASSED**。⇒ 现行 `if(CTP_SDK_ROOT AND CTP_FOUND) expect=on else auto` **两分支均被实跑覆盖且行为合理**。

**⇔ 判据**：接线**真实生效**（`ctest` 实扫、非空转），且**对错误接线/写死参数敏感**。

---

## `T130.2`（B）★判据承重 —— 消除「该红而退 `3`」盲区 —— 通过

**核心对照（同一输入：官方 SDK + `--style MERGED`；新旧脚本同相对落点，排除 `CPP_DIR` 解析偏差）**：

| 脚本 | 结果 | 输出关键行 |
|---|---|---|
| **旧（`68f4bcf`，md5 `2efe5ede5c2c9daa0060e20566ec3082`）** | **`rc=3`** | `== [不可判定] 判定行不完整（smoke='OFF' main=''）—— 一侧在打印前即中止` |
| **新（`T-130`，`6bc8fab2…`）** | **`rc=1`** | `== [FAIL] 判定行不完整（smoke='OFF' main=''）—— 一侧未及披露判定即中止，parity 无法建立（未披露侧=main）` |

> 机制：`add_subdirectory(ctp_smoke)`（`CMakeLists.txt:56`）**先于** `include(HgDependencies.cmake)`（`:63`）⇒ `MERGED` 下**探路件先打印 `found=OFF` 再 `FATAL`**（`ctp_smoke/CMakeLists.txt:139` 打印、`:148` FATAL），主工程**未及披露** ⇒ 仅 1 行。旧码把「仅一侧」归入 `rc=3`（不可判定），新码归 `rc=1`（FAIL）。**两侧一视同仁**：由 `aborted="main"; [[ -z "${smoke}" ]] && aborted="smoke"`（`:91`）判未披露侧。

**自设变异（均自撰、非产出方 M1/M2）**：

| # | 构造 | 结果（`rc` / 两行是否俱在） |
|---|---|---|
| **M1** | 强制 `role=smoke found=ON`（无 SDK ⇒ 主=OFF） | **`rc=1`**；两行**俱在** ⇒ `[FAIL] 判定分歧：smoke=ON main=OFF` |
| **M2** | 主工程**披露前**注入 `FATAL`（`CLASSIC`+SDK） | **`rc=1`**；仅 smoke 一行 ⇒ `未披露侧=main` |
| **M3** | 顶层 `project()` 后**任何一侧披露前**即 `FATAL` | **`rc=3`**；**无任何判定行** ⇒ 与「仅一侧」明确区分 |
| **M4** | 用已配 `build-ctest` 跑接线用例，制造 M3 条件 | **`ctest` 报 `Failed`**（`0% tests passed, 1 tests failed`）⇒ **`rc=3` 不静默通过** |

**★对「盲区」定性的据实更正（不夸大、不缩小）**：`rc=3` **本就是非 0**，故在 `ctest` 下 `1` 与 `3` **均为失败**（M4 实证）⇒ 就 `ctest` 而言**并非「假绿/静默放行」**，而是**退码语义误分类**（把「该红」记为「不可判定」）+ 头注**漏文档化** `rc=2`。新码令「仅一侧中止」**语义正确归 FAIL(1) 并点名未披露侧**，且头注 `:36` 明写「**1/2/3 均非 0 ⇒ 一律视为失败**」⇒ **盲区（误分类）已消除**；「该红而退 3」若被**外部消费者**当作「不可判定 = 环境问题、可跳过」才有风险 —— 本次未在任何 CI/消费者中发现把 `3` 映射为 SKIP 的接法（接线仅设 `TIMEOUT`、未设 `SKIP_RETURN_CODE`）。

**⇔ 判据**：两侧分歧**均判红**（M1/M2 + 真实 `MERGED` 场景）；`rc=3` 仅剩「**无任何判定行**」一种语义（M3），且该语义**确被 `ctest` 视为失败**（M4）。

---

## `T130.3`（C）退码语义完整性 —— 通过（一处**范围限制**见观察 `OBS-T130-3`）

**实现内 `exit` 点穷举（行号）↔ 头注 `:25`–`:36` 四码定义逐一对齐**：

| 行 | 分支 | 码 | 头注定义 |
|---|---|---|---|
| `:54` | `--help` | `0` | 一致/正常退出 |
| `:55` | 未知参数 | `2` | 用法错误（未知参数） |
| `:82` | 无任何 `[HG-CTP-FOUND]` 行 | `3` | 不可判定（未及披露即终止） |
| `:95` | **仅一侧**有判定行 | `1` | 失败②（`T-130` 修复点） |
| `:124` | `exit "${STATUS}"` | `0`/`1` | 一致 / 分歧或字段不一致 |

- **`rc=2` 仅来自未知参数**：实跑 `bash … --bogus` ⇒ `未知参数：--bogus` / **`rc=2`**；实现中 `exit 2` **仅 `:55` 一处** ⇒ 无第二来源。**头注补 `rc=2`** 与实现一致。
- **`--help` 脆弱性已修复且实测有效**：旧形 `sed -n '2,26p'` 因 `T-130` 头注增行后**会截断**（实测对增行后副本仅打 25 行）；新形 `awk '/^# =====/{c++; if(c==2)exit; …}'` **按 `# ====` 围栏取范围** ⇒ 头注增行后**仍打全 36 行**（末行 = 「…一律视为失败；仅 0 为通过。」）。**残留（低）**：若在头注**正文内**再插一行 `# =====` 会提前截断（现无此形；围栏契约合理）。
- **`--help` 不误报 PATH**：输出自 `# =====`（不含 shebang）。

**⇔ 判据**：四码定义与实现**逐点对齐**；`rc=2` **唯一来源**正确；`--help` 由**脆弱硬编码行号**改为**围栏取范围**（实测抗增行）。

---

## `T130.4`（D）验收 4 · 诊断质量 —— 通过（附一处范围说明）

- **主工程 `-DHG_CTP_HEADER_STYLE=MERGED` ⇒ `FATAL` 且点名风格**（无 SDK 根时，主工程可达该检查）：
  `CMake Error at cmake/HgDependencies.cmake:71 (message):`
  `[CTP] 主工程不支持 HG_CTP_HEADER_STYLE=MERGED（实测传入 'MERGED'）。` … `Call Stack: CMakeLists.txt:63 (include)`。**点名 `MERGED` + 说明根因（`ctp_adapter/**` 直引分离式头、`CH-015`）+ 给处理建议** ✓。
- **探路件 `MERGED` 仍可用**（证明「只加在主工程、不动共用 `HgCtpStyle.cmake`」未削弱探路件）：以 **CTP Mini SDK**（`/home/ctpmini/ctpmini_admin/ctp_sdk`，含 `ThostFtdcUserApi.h`）**standalone** 配置 `cpp/ctp_smoke` + `-DHG_CTP_HEADER_STYLE=MERGED` ⇒ `rc=0`，`[HG-CTP-FOUND] role=smoke found=ON style=MERGED trader_header=ThostFtdcUserApi.h` ✓。
- **范围说明（低·非缺陷）**：若**同时**给 `MERGED` **与显式 SDK 根**，则**探路件先 `FATAL`**（`ctp_smoke/CMakeLists.txt:148`），主工程的 `:71` 检查**被遮蔽**、未及执行 —— 其报错点名了缺失的合并版头 `ThostFtdcUserApi.h` 但**未点名风格**。此为 `OBS-T128-4` 修复的**边界**（修复在主工程侧、且因求值序被探路件侧先中断），现行文本已自披露「仅加在主工程」，**不阻断**。

**⇔ 判据**：验收 4 **达成**（主工程 `MERGED` 显式 `FATAL` 且点名风格；探路件 `MERGED` 能力保留）。

---

## `T130.5`（E）验收 6 · README 回写 + ★`:299` 裁定复核 —— 通过；**裁定成立**

- **历史块相对 `68f4bcf` 逐字节未变**：`git diff --numstat 68f4bcf 4adac20 -- tools/arch-check/README.md` = **`38  0`**（**插入 38 / 删除 0**）⇒ 「`2026-09-21` 的『`ctest` 前置接线』整块」（含 `:299`）**无任何改写**；新增仅为 `:313`–`:314` 的**指针行**（`>` 前缀）与文末 §6。
- **§6 现行值正确且无歧义**（实测吻合）：`build-ctp` = `<repo>/sdk/v6.7.13_20260225_api` / `CLASSIC` / **39**；`build-stub` = **空** / `CLASSIC` / **35**；`build-fetch` = `<repo>/sdk/v6.7.13_20260225_api` / `ON` / **39**；指针行 `:314` 指向 **§6（`:331`）** ⇒ **可达**。
- **★对编排者裁定的独立复核（`:299` 应『只加不改』）⇒ 本席判**：**裁定成立**。依据：(1) `:299` 位于 **`2026-09-21` 整块**内，而该块**已被 `2026-09-22` 的「历史时点口径…只加不改」注解**（`:310`–`:312`）声明「**原句保留以留痕**」「**不作为官方基线下的结论**」；(2) 该注解**已显式点名 `:299` 的陈旧值** `CTP_SDK_ROOT=/home/ctpmini/ctpmini_admin/ctp_sdk` 属 `CTP Mini` 时代 ⇒ 陈旧值**已被就地标注**，再加改写**既破坏留痕、又与块自身声明冲突**；(3) 与本项目既有纪律一致（历史/自指块**只加标注不替换**，区别于 **live 指针**方可改锚点）。**新指针行 `:314` + §6** 已使「现行三轨值」**无歧义可得** ⇒ 合格形态成立。**未见相反先例**（本 README 内无「历史块内直接改写」的既有做法）。

**⇔ 判据**：验收 6 **达成**；`:299`「只加不改」**裁定正确**。

---

## `T130.6`（F）验收 7 · 统一 SYSTEM 形态 —— 通过

- **只剩一条路径**：`cpp/cmake/HgDependencies.cmake:259`–`:275` 已**删除**「`CMAKE_VERSION ≥ 3.25` 走 `set_target_properties(libprotobuf PROPERTIES SYSTEM TRUE)` / 否则走供给目标」的**版本二分**（diff 实证：`-12` 行旧形态），现行**唯一**形态 = `:273` `target_include_directories(hg_deps_protobuf SYSTEM INTERFACE ${_hg_pb_inc_dirs})`。全文件 `SYSTEM TRUE` / `VERSION_GREATER_EQUAL "3.25"` **仅存于注释**（`:262`/`:265`），**无第二处代码路径**。
- **编译命令行独立验证**（`fetch` 轨，`gh_hgcp` 目标，含 `payload_codec.cc`）：
  `CXX_INCLUDES = -I…/cpp/src/hgcp/include -I…/build-fetch/src/hgcp/generated -isystem …/_deps/protobuf-src/src`；
  `CXX_FLAGS = -O2 -g -DNDEBUG -std=c++11 -Wall -Wextra -Werror`。
  `compile_commands.json` 中 `payload_codec.cc` 同形（**`-isystem …/protobuf-src/src` 在、`-Wall -Wextra -Werror` 在、无任何 `-Wno-`**）。**共享树 `cpp/build-fetch` 亦同形**（独立比对一致）。
- **「作用面等价」独立复核**：全仓（`cpp/`+`tools/`，排 build）grep `hg_deps::protobuf` ⇒ **唯一消费方 = `cpp/src/hgcp/CMakeLists.txt:84`（`hg_hgcp`）**；`tools/` **无 CMake**；`proto_smoke` 用**自身的** `find_package(Protobuf)`/FetchContent 供给（`protobuf::libprotobuf`，**非** `hg_deps::protobuf`）⇒ **不存在第二个消费方** ⇒ 「作用面等价」**成立**。
- **约束登记充分性**：`:268`–`:269` **已登记**「若将来出现**绕过 `hg_deps::protobuf` 直连 `libprotobuf`** 的新消费方，本形态不再覆盖它 ⇒ 届时须改用 `libprotobuf` 的 `SYSTEM` 目标属性（需 CMake ≥ 3.25）」。按本项目惯例（登记于代码注释）**足够**；残留为**无自动守卫**（见 `OBS-T130-4`）。

**⇔ 判据**：验收 7 **达成**；形态统一为供给目标、命令行 `-isystem` **实测生效**、消费者**唯一**、约束**已登记**。

---

## `T130.7`（G）不回归 —— 通过

- **三轨 `ctest`（副本 `/root/t130v`，全量）**：`ctp` **39/39** · `stub` **35/35** · `fetch` **39/39**，**均 `rc=0`**。
- **三轨 build**：`ctp`/`stub`/`fetch` **均 `rc=0`**，**项目代码 0 告警**（`ctp`/`stub` `grep -icE 'warning:'` = **0**）。**`fetch` 轨自建（from-scratch 编译 protobuf）另有 2 条第三方告警**（见 `OBS-T130-1`，**非本工程代码、不失败构建**）。
- **`fetch` 轨确可构建**（`T-129` 修复生效，**未再坏**）：`cmake --build cpp/build-fetch` **`rc=0` / 0 error**。
- **聚合 `arch-check`**：`bash tools/arch-check/arch-check.sh` **`rc=0`**；汇总 `通过(0)=7 命中(1)=0 错误(2)=0`。
- **`E4` 扫描数**：**47 文件 / 命中 0 / WARN 3（`new`）**、退出 `0`（扫描根 = `cpp/src/{order_engine,risk_engine,risk_state}`）⇒ **与自述一致、未恶化**（`T-130` 未触碰 `cpp/src`）。

**⇔ 判据**：三轨 `ctest` 全绿、build 无**项目代码**告警、聚合 `rc=0`、`E4` 未恶化。

---

## `T130.8`（H）同类扫描复核（穷举 + 判定标准）—— 通过（**计数一处偏差**见 `OBS-T130-2`）

- **独立穷举**（`find . \( -name '*.sh' -o -name '*.py' \) -not -path '*/build*' -not -path './.git/*' -not -path '*/.codebuddy/*'`）= **11 个**（`*.sh` **10** + `*.py` **1**）：`check_ctp_found_parity.sh`、`run_probe.sh`、`run_smoke.sh`、`arch-check.sh`、`check_frontend.sh`、`check_no_db_links.sh`、`scan_cpp11.sh`、`scan_cpp_sql.sh`、`scan_java_sql.sh`、`scan_lock_free.sh`、`scan_hgcp_schema.py`。
  ⇒ **产出方自述「全仓 `*.sh`/`*.py`…= 10 个」实测为 11**（差 1，见 `OBS-T130-2`）。
- **接线核对**：`E1`–`E7` 经**聚合入口** `arch-check.sh`（`:71`–`:77` 表 + `:107` 按扩展名分派 `python3`/`bash`）接线 ✅；`arch-check.sh` **自身**经 `ctest` fixture `arch_check`（`cpp/CMakeLists.txt:77`）与 `mvn validate` 接线 ✅；`check_ctp_found_parity.sh` **本单经 `ctest` 接线** ✅。
- **判定标准（本席提出，用于区分「判据」与「探路复现」）**：
  - **判据脚本**（`gate`）：断言**当前仓库**须恒成立的性质；**环境无关**（或可优雅 `SKIP`/降级）；**非 0 即应阻断**；应**接线**。
  - **探路复现脚本**（`recon`）：复现**一次性**探查（`RSK-*`）结论；**依赖外部资源**（真实 SDK / 网络下载）**在通用 CI 不保证可得**；结论**已冻结**（改动须走对应任务重冻结）；**不接线**合理。
- **按上述标准判**：`run_probe.sh`（**须显式 `CTP_SDK_ROOT`**，`T-027` 探路件，冻结）、`run_smoke.sh`（**须网络拉 3.19.6 fallback**，`T-026`，冻结）⇒ **不适用项分类成立**。**补充加固**：二者中**环境无关**的断言（`B7`/`A7` 无 DB 客户端库、`B8` C++11 边界、`A8` 无前端构建链产物）**已被接线的 `E1`（`check_no_db_links.sh`）/`E3`（`scan_cpp11.sh`）/`E6`（`check_frontend.sh`）覆盖** ⇒ **未接线的恰是「依赖 SDK/网络」的那部分**。
- **结论**：**无第三处「已交付未接线」的判据**。（唯一数量偏差 = `OBS-T130-2`，**不改变该结论**。）

---

## `T130.9` 观察项清单（均**不阻断**）

| 编号 | 级 | 观察 | 证据 |
|---|---|---|---|
| `OBS-T130-1` | 低 | 台账「三轨 build **0 告警**（`grep -icE 'warning:'` = 0）」对 **`fetch` 轨 from-scratch 构建不精确**：自建 fetch 轨另有 **2 条第三方告警**（`protobuf-src/…/message_lite.cc` 经 `stl` 头触发 `-Wstringop-overflow=`），**非本工程代码、不失败构建**（protobuf 自身未加 `-Werror`）。**项目代码 0 告警**属实。系与 `OBS-T128-3` 同类（增量构建下不复现）。 | `/root/t130v-build-fetch.log`（2 命中，源均在 `_deps/`） |
| `OBS-T130-2` | 低 | 同类扫描**计数偏差**：自述「全仓 `*.sh`/`*.py`…= **10 个**」，**实测 = 11**（`*.sh` 10 + `*.py` 1）。疑因**漏计聚合入口 `arch-check.sh` 或 `.py`（`scan_hgcp_schema.py`，E7）未计入总数**。**结论（无第三处未接线判据）不受影响**。 | `find` 穷举（11 行） |
| `OBS-T130-3` | 低·范围 | **判据只断言「parity」、不断言「configure 成败」**：若配置在**两侧均已披露**判定行**之后**才失败，判据返 **`rc=0`（PASS）**（实测注入主工程披露后 `FATAL` ⇒ `[PASS] 判定一致…`/`rc=0`）。**属判据既定契约内**（头注 `0 = 两侧均产出判定行且全字段相同`）；且此类失败会由**外层构建本身**（同一源树重配）另有拦截，风险低。 | 变异实跑（mut `/root/t130v-mut/pt-both`） |
| `OBS-T130-4` | 低·建议 | **F** 的「作用面等价」**无自动守卫**：仅靠 `:268`–`:269` 注释登记「新消费方须回退 `SYSTEM TRUE`」。若将来新增直连 `libprotobuf` 的消费方，**无判据会变红**。现消费方唯一 ⇒ 无实际风险；可考虑后续加判据。 | `grep` 全仓唯一消费方；注释登记 |
| `OBS-T130-5` | 低 | **`MERGED` + 显式 SDK 根**时，探路件侧 `FATAL`（`:148`）**遮蔽**主工程 `:71` 的风格点名（见 `T130.4`）。主工程检查本身正确，仅**求值序**致其不可达。 | `cmake -S cpp -DHG_CTP_HEADER_STYLE=MERGED -DCTP_SDK_ROOT=…` ⇒ 报 `ctp_smoke/CMakeLists.txt:148` |
| `OBS-T130-6` | 极低 | `--help` 若在**头注正文内**再插一行 `# =====`，`awk` 围栏会提前截断（现无此形）。 | 机制分析 + 增行自测（仍 36 行） |

---

## `T130.10` 对编排者两项裁定的复核结论

1. **`:299` 应「只加不改」** ⇒ **成立**（依据见 `T130.5`：`:299` 在 `2026-09-21` 整块内、该块已有「只加不改」注解并**已点名该陈旧值**、`git diff` 删除数 = 0、新指针 + §6 使现行值无歧义；无相反先例）。**本席未主张直接改写 `:299`**。
2. **「三轨计数 +1 予以认可、驳回『改挂 `run_probe.sh` 以保持计数不变』」** ⇒ **驳回正确**。依据：`OBS-T128-1` 的要害是**判据无真实调用点 ⇒ 永不生效**；若把判据**藏进 `run_probe.sh`**（探路复现脚本，**须显式 SDK、CI 无 SDK 环境不跑**）⇒ 判据在 CI **仍不执行**，**违背验收 1「实扫」**。而**新增独立 ctest 用例**使判据在**三轨（含无 SDK 的 `stub`）均执行**（`stub` 轨实测 `Passed`、两端一致 `OFF`）⇒ **+1 是「实扫」的正当代价**，非「为避免计数变化而妥协」。

---

## `T130.11` 独立复算锚点（取样 @ `2026-09-23T08:54:27+0800`；连续 3×10s 一致）

| 锚点 | 值 | 文件数 |
|---|---|---|
| `cpp/src`（`LC_ALL=C sort`） | `327118d860c1618ecc9609f7d95b1d0e` | **115** |
| `cpp/cmake` | `1e08aef44c9739b721a0f6dc5b7dd688` | **3** |
| **交付域**（`cpp/cmake`+`cpp/ctp_smoke`，**排除构建目录**） | **`288b3b33c80a13b3e07c684881030799`** | **10** |
| 旧判据脚本（`68f4bcf`）参考值 | `2efe5ede5c2c9daa0060e20566ec3082` | — |

**共享树零污染**：复核全程 `git status --porcelain` **空**；`*.orig`/`*.bak`/`*.rej` = **0**；源码树 `MUTATION` = **0**。所有变异均在 `/root/t130v-mut` 内、**逐例复位**（`HgDependencies.cmake`→`2baf596f…`、`ctp_smoke/CMakeLists.txt`→`daf53783…`、`CMakeLists.txt`→`3431e7c4…`，均逐字节回原）。

---

**纯追加证明**：本证据章落笔前 `md5sum docs/TEST_RESULT.md` = `3145e5179eb5c12935e0a853b6510b7a` / **5392 行**；追加后 `head -5392 docs/TEST_RESULT.md | md5sum` = **`3145e5179eb5c12935e0a853b6510b7a`**（⟹ 前 5392 行**逐字节未变**）。追加后章数/md5 **以实时为准**（本章为共享 `docs/TEST_RESULT.md` 的**纯追加**，未删改任何既有行）。

# `T-018` 独立复核证据（2026-09-23）

> 复核席：`te-verify-t018`（**自撰探针 / 自设变异**，未复用产出方 harness）。
> 共享源树**只读**（`web/**`、`java/**`、`docs/**` 除本章外均未改）；变异/探针仅在隔离副本 `/root/t018v`。
> 结论：**有条件通过（0 阻断缺陷）** ⇒ 按本项目判据可转 `DONE`；4 观察项 + 3 项立号核对意见（见下）。

## 0. 复核方法与工具

| 手段 | 内容 |
|------|------|
| 静态契约核对 | 逐字段把 `product.js` 消费/提交的字段名与**已实现 Handler** 的请求/视图类**逐字比对**（`ProductHandler`/`WhitelistHandler`/`QuotaHandler`/`ProductContractHandler`/`ProductMappingHandler`/`ProductChangeLogHandler`/`ProductImportHandler`/`ProductExportHandler`） |
| 独立运行探针 | 自撰 `/root/t018v/probe.mjs`：`global.window`/`document`/`fetch` 全桩 + 载入**真实** `web/js/core/{ns,format,errmsg,ui,routes,router,session,api}.js` 与 `web/js/pages/product.js`，真机执行 `renderList`/`renderDetail` 六 Tab/导出/导入错误路径 ⇒ **31 PASS / 0 FAIL** |
| 自设变异 | 隔离副本内对 `web/js/pages/product.js` 注入 ①`jdbc` ②`127.0.0.1:<port>` ③`package.json`，各跑 `E6`（`check_frontend.sh`） |
| 接线核查 | 聚合 `tools/arch-check/arch-check.sh`（E1–E7）实跑 |
| 残留扫描 | `git status --porcelain`、`find -name '*.orig' -o -name '*.bak'`、`grep -rl MUTATION` |

**残留扫描结果**：`*.orig`/`*.bak` = **0**；`MUTATION` 命中仅在 `docs/*.md` 散文与第三方 `cpp/build-fetch/_deps/protobuf-src/**` ⇒ **无我方或产出方遗留变异态**。

## 1. 复核对象与锚点（实测，非转述）

| 对象 | 复核席实测 | 与你（编排者）派单 |
|------|-----------|------------------|
| `web/js/pages/product.js` | `b8e66b70927d596ab62ec5cb83e39398` / **999 行** | 吻合 |
| `web/js/core/api.js` | `db1d4e9dd661cbe097c6c7cd0b7f5c1d` / **172 行** | 吻合 |
| `web` 树锚 | `f827e00e6687afb092ab933fe0816ce4` / **27 文件**（基线 `81a06bce…`/27） | 吻合 |
| `git diff --numstat 71e857a 6acead5 -- web/` | `45/1  api.js`、`995/15  product.js` | 吻合（`45/1`） |
| `web/js/core/routes.js` | **未改**（`:54-62` 已登记 9 条 PRODUCT 路径） | 吻合 |
| HEAD | `6acead5` | 吻合 |
| `docs/ARCHITECTURE.md` | `94cedcf5dfa37d9e55f1c4e2ba341599` / 3267 行 | （§4.1.1 `:625`、`G-2` `:1282` 逐字已核） |

**静默取证**（本席落笔前）：`2026-09-23T09:25:40` / `09:25:50` / `09:26:01` 三次取样，`product.js`/`api.js`/`web` 树锚**逐次一致**（`b8e66b70…` / `db1d4e9d…` / `f827e00e…`/27）。

## 2. 验收判据逐条（A/B/C/D/E）

### A. 验收 1 —— 六 Tab 真取数 + 三态 + errmsg 四项（探针实跑 + file:line）

| Tab | 取数调用点（`product.js`） | 渲染 | 三态 |
|-----|--------------------------|------|------|
| 概览 `overview` | 复用 `loadHead` 的 `GET /product/{id}`（`:471`）；`:505` 起渲染「已覆盖/接口缺口」诚实登记卡 | `:511-523` | 头卡 `renderLoading:469` / `renderError:477` |
| 合约关联 `contracts` | `:538` `GET /product/{id}/contracts`；写 `:608` `POST` | `:544` | `renderLoading:536` / `renderEmpty:540` / `renderError:555` |
| 现货映射 `mapping` | `:636` `GET /product/mapping?productId=`；写 `:688` `POST` | `:642` | `:634`/`:638`/`:650` |
| 白名单 `whitelist` | 写 `:766` `POST /product/whitelist`；留痕 `:792` `GET /product/change-log?productId=` | `:798`/`:783` | `:790`/`:794`/`:808` |
| 额度 `quota` | `:837` `GET /product/quota?subAccountId=&productId=`；写 `:900` `POST` | `:928-932` 三层表 | `:835`/`:924`/`:839` |
| 变更历史 `changelog` | `:966` `GET /product/change-log?productId=` | `:973` | `:964`/`:968`/`:983` |

- **真取数不空壳**：探针实跑确认——`overview`/`contracts`/`mapping`/`whitelist`/`changelog` 五个 Tab 均先 `GET /api/v1/product/7` 再命中各自端点；`quota` Tab 经「查询三层全景」按钮命中 `GET /api/v1/product/quota?subAccountId=3&productId=7`，并消费 `{layer1SubProduct, layer2SubInstrument, layer3AccountProduct, note}` 四键，渲染层①/②/③三表 + `note`。
- **三态真分支**：上表 6 处数据视图均为 `renderLoading`/`renderEmpty`/`renderError` 三分支（非空壳）。
- **失败真走 errmsg 四项**：所有 `catch` 经 `ui.renderError`（`:366`/`:477`/`:555`/`:650`/`:808`/`:839`/`:983`）或 `ui.blockedHTML`（`:129`/`:203`/`:261`/`:613`/`:693`/`:771`/`:905`）；二者最终调 `HG.errmsg.render`（`ui.js:176`/`ui.js:66`）。探针注入 `{code:10004,ruleId,current,threshold,nextStep}` ⇒ 渲染出「规则编号/当前值/阈值/下一步」**四项俱在**。

### B. 验收 2 —— 6 端点对接 + ★字段名逐字核对（wire caleCamel，以 Handler 为准）

| 端点 | `product.js` 调用点 | 消费字段（前端） | Handler DTO（后端） | 判定 |
|------|---------------------|------------------|---------------------|------|
| `GET /product` | `:360` | `{items,total,page,size}`（`normalizePage:66`） | `PageData<ProductView>`（`ProductHandler:102`） | ✅ 信封真消费（探针 `total=45` ⇒ 「共 45 条」） |
| `POST /product` | `:254` | `{productCode,productName,category,remark}` | `CreateRequest{productCode,productName,category,remark}`（`:123-136`） | ✅ |
| `GET /product/{id}` | `:471` | `id,productCode,productName,category,categoryManaged,status,isDomesticExchange,currency,remark,disabledReason` | `ProductView`（`:139-185`）**同 10 字段、无 createdTime** | ✅ |
| `GET\|POST /product/{id}/contracts` | `:538`/`:608` | `instrumentId,instrumentType,exchangeCode,contractMultiplier,expiryDate,optionType,strikePrice,status` / 请求同 | `ContractView`（`:102-146`）/ `Request`（`:74-99`） | ✅ |
| `GET\|POST /product/mapping` | `:636`/`:688` | `spotCategoryCode,spotCategoryName,mappingDirection,isPrimary,status` / 请求 `{spotCategoryCode,spotCategoryName?,productId,mappingDirection,isPrimary,changeDesc?}` | `MappingView`（`:100-135`）/ `Request`（`:78-97`） | ✅ |
| `POST /product/whitelist` | `:766` | `{action,subAccountId,productId,categoryOverride?,reason?}` | `Request`（`:62-78`） | ✅ |
| `GET\|POST /product/quota` | `:837`/`:900` | 响应键 `layer1SubProduct/layer2SubInstrument/layer3AccountProduct/note`；请求 `{limitLayer,subAccountId,productId,instrumentId?,quotaLimit,quotaUnit,effectiveFrom}` | `quotaPanorama`（`VarietyService:764-773`）/ `Request`（`:117-139`） | ✅（字段名对等；**数值以 number 输出属 `T-132`，见 §4.4**） |
| `GET /product/change-log` | `:792`/`:966` | `changeTime,changeType,changedField,beforeValue,afterValue,subAccountId,operatorId,changeReason` | `ChangeView`（`:60-102`） | ✅ |
| `POST /product/import` | `:197` | 请求 `{csvLines}`；响应 `inserted` | `Request{csvLines}` / `ImportResult{inserted}`（`:54-65`） | ✅ |
| `GET /product/export` | `:118`（经 `HG.api.download`） | `Content-Disposition` filename | `ProductExportHandler`（`:42-43` `attachment; filename="products.csv"`） | ✅ |

- **无字段名不符**（camelCase 逐字对齐；未按 `DATA_MODEL` snake_case 判）。
- **分页信封**：`normalizePage` 真消费 `{items,total,page,size}`，并兼容旧 bare 数组兜底（`:67`）——非「假定数组」。
- **★排序方向**：`query(page)`（`:352`）恒发 `sort = sortField + ',' + sortDir`，缺省 `sortDir='desc'`（`:278`）⇒ 实测 URL `…&sort=created_time%2Cdesc`，**显式带方向**。规避了「`PageQuery` 缺省恒 ASC」陷阱；`PRODUCT_SORTS`（`:56-62`）5 项与 `PRODUCT_SORT_FIELDS`（`VarietyService:105-107`）**逐项一致**。
- **取值域一致性**（前端常量 vs 后端）：`CATEGORIES{COMMODITY,TRADE}`、`LIMIT_LAYERS{SUB_PRODUCT,SUB_INSTRUMENT,ACCOUNT_PRODUCT}`、`QUOTA_UNITS{LOT,TON}`、`INSTRUMENT_TYPES{FUTURES,OPTION}`、`MAPPING_DIRECTIONS{PURCHASE,SALES,BOTH}` 全部吻合（`VarietyService:73-90`）。

### C. 验收 3 —— 导入逐行提示 / 导出 CSV 下载

- **导入**：`:147-160` `importProblemsHTML` 按 `/\n\s*-\s*/` 解析后端 **10019** 的 `message`；后端消息格式实测为 `"导入文件校验失败（共 N 项问题，数据行 M；已全部回滚、未产生部分写入）：\n  - <问题1>\n  - <问题2>"`（`VarietyService:989-993` `importFailed` + `:798` 「缺少必需列：…」）⇒ **解析形态对得上**。探针注入该消息 ⇒ 渲染出「逐行问题清单」且含「缺少必需列：currency」「第 5 行分类越域…」（**逐行定位成立**）。
- **导出**：`:118` 经 `HG.api.download(R.EXPORT)`（`api.js:142`，**同源 fetch，唯一出网通道**）。探针注入 **`Content-Type: application/json` + 错误信封 `{code:10004,ruleId,…}`** ⇒ `download()` 抛错（`api.js:161`）⇒ 被 `doExport` catch 经 `ui.blockedHTML` 渲染**四项**（**不吞错**）。成功路径（`text/csv` + `Content-Disposition: attachment; filename="products.csv"`）⇒ 触发 `triggerBlobDownload`（`product.js:101-111`）。

### D. `E6` 承重性（自设变异）

- 基线（隔离副本 `product.js` 为原件 `b8e66b70…`）：`bash tools/arch-check/check_frontend.sh --root /root/t018v/web` ⇒ **rc=0**。
- **M1** 注入 `jdbc:dm://probe` ⇒ **rc=1**（`web/js/pages/product.js:1001` 命中）。
- **M2** 注入 `fetch("http://127.0.0.1:27402/x")` ⇒ **rc=1**（同上）。
- **M3** 注入 `web/package.json` ⇒ **rc=1**（`BUILD-CHAIN` 命中）。
- 复位后：`product.js` md5 回 `b8e66b70…`、`web` 树回 `f827e00e…`/27 ⇒ **逐字节复原**。
⇒ **`E6` 对该目录承重（三项均变红）**；且 `E6` 已接线（`arch-check.sh:76` 调用、`java/pom.xml:212` 经聚合入口）——非空转。

### E. `api.js` 改动越界 / 能力削弱核查

- `git diff 71e857a 6acead5 -- web/js/core/api.js` **仅**：新增 `filenameFromDisposition()`（`:128`）+ 新增 `download()`（`:142-169`）+ 导出清单追加 `download`（`:171`）。**唯一的 1 处删除行** = 原 `HG.api = {…}` 导出行（追加 `download` 后重写）。
- **8 个既有函数**（`newIdempotencyKey`/`buildQuery`/`request`/`get`/`post`/`put`/`del`/`list`）定义与调用形态**逐字节未动**（diff 中 `:1-124` 全 context，无 hunk）。
- **唯一出网通道**：全 `web/**` 仅 2 处 `fetch(`（`api.js:63`、`api.js:146`），**无** `XMLHttpRequest`（实测 `grep -rn`）。`download()` 仍为同源 `fetch`，未新增直连。
- **不吞错**：`download()` 对 `Content-Type: text/json` 分支按信封抛错（`:161`/`:163`）——见 §2.C 探针实证。

## 3. 独立探针输出（摘录，可复现）

```
== T-018 独立探针 ==
PASS 列表页发起 GET /api/v1/product
PASS 列表页 sort 显式带方向(created_time,desc)：/api/v1/product?page=1&size=20&sort=created_time%2Cdesc
PASS 列表页 size=20
PASS 列表渲染出 productCode=CU（camelCase 字段被消费）
PASS categoryManaged=Y 渲染为「纳入」
PASS 分页信封 total=45 被消费（共 45 条）
PASS 详情页 Tab=contracts 取数端点命中 /product/7/contracts
PASS 详情页 Tab=mapping 取数端点命中 /product/mapping?productId=7
PASS 详情页 Tab=whitelist 取数端点命中 /product/change-log?productId=7
PASS 详情页 Tab=changelog 取数端点命中 /product/change-log?productId=7
PASS quota Tab 渲染三层表（层①/②/③ 表标题）
PASS quota Tab 消费 pano.note
PASS quota 数值走 F.decimal 字符串路径（1,000.5）
PASS quota 取数带 subAccountId=3&productId=7
PASS 错误走 errmsg 四项（规则编号/当前值/阈值/下一步）
PASS 导出走 GET /api/v1/product/export（经 HG.api.download）
PASS 导出遇 JSON 错误信封 ⇒ 抛出并渲染（不吞错）
PASS 导入错误逐行清单：含「缺少必需列：currency」
PASS 导入错误逐行清单：含「第 5 行…」（逐行定位）
== 汇总：31 PASS / 0 FAIL ==
```

> 说明：探针**自撰**（不复用产出方 harness）。首轮曾因**我的** DOM 桩缺 `.children` / handler 键写错 / 未补 `window.scrollTo` 而假红 11 项，逐项定位为**探针缺陷**（非制品缺陷）后修正，最终 31/31。

## 4. `G` —— 7 项未决逐条复核（含「立号是否正确」）

### 4.1 写面端点缺失 ⇒ `T-131`（**立号正确**）
**核**：`ProductRoutes.register`（`:36-52`）仅注册 `GET|POST /product`、`GET /product/{id}`、`POST /product/whitelist`、`GET|POST /product/quota`、`GET|POST /product/{id}/contracts`、`GET|POST /product/mapping`、`GET /product/change-log`、`POST /product/import`、`GET /product/export` ⇒ **确无** `PUT /product/{id}`／停用启用动作／计量单位端点（`ApiRoutes:29-31` 9 路径佐证）。服务层确有 `createProduct:242`/`disableProduct:283`/`enableProduct:338`/`putUnit:369`（实测行号吻合）。⇒ **「已实现未接线」成立，`T-131` 正确**。

### 4.2 `GET /product/whitelist` 无读取端点（⇒ 并入 `T-131`）
**核**：仅 `POST` 注册（`ProductRoutes:39`）；确无 `GET /product/whitelist`。
**`FR-PRODUCT-12` 是否要求读取**：**不要求**。原文（`PRODUCT.md:247-260`）输入 = 「子账户标识、品种集合（增/减）」；验收 1~4（`:257-260`）无「列出/读取当前白名单」项 ⇒ **就 `FR-PRODUCT-12` 而言，此条不是缺陷**。
**但**：`FR-PRODUCT-6`（`:161-162`）要求**详情含「所属子账户白名单」** ⇒「无法列出当前白名单」在 **`FR-PRODUCT-6` 面**是要求缺口。`T-131` 验收 3（`TASKS.md:2691`）**只要求核 `FR-PRODUCT-12`** ⇒ 该 FR-PRODUCT-6 缺口**可能逃逸**（见观察 `OBS-T018-4`）。

### 4.3 `GET /product` 无筛选参数 ⇒ `T-116`（**立号正确**）
**核**：`ProductHandler.list`（`:90-104`）仅读 `sort`/`page`/`size`（`:91`/`:95`）⇒ 无 status/分类/关键字/子账户筛选。`FR-PRODUCT-5` 输入列（`PRODUCT.md:148`）要求「品种编码/名称关键字、状态、分类、子账户」⇒ **要求 > 实现**成立；`T-116` 验收 1/2（`TASKS.md:2460-2461`）明载承载 ⇒ **正确**。

### 4.4 `GET /product/quota` 以 JSON `number` 输出 `BigDecimal` ⇒ `T-132`（**立号正确**）
**核**：`quotaPanorama`（`VarietyService:756-775`）直出 `List<ProductQuota>`/`List<SubInstrumentQuota>`；两实体 `quotaLimit`/`usedQty`/`frozenQty` 均 `public BigDecimal`（`ProductQuota:27/33/36`、`SubInstrumentQuota:27/33/36`）；`JsonCodec` 用**裸 Gson**（`GsonBuilder().disableHtmlEscaping()`，**无 `BigDecimal` 适配器**，`:25-27`）⇒ Gson 默认把 `BigDecimal` 序列化为 **JSON number**。`ARCHITECTURE.md:625`「金额/数量 → **字符串化十进制**」+ `:1282` `G-2`（额度字段一律 `string`）⇒ **确违约**。`T-132` 含「**全仓穷举同类点**」（`TASKS.md:2709`），与「同类面可能不止 PRODUCT」相称 ⇒ **恰当**。

### 4.5 `GET /product/export` 无数据仍返回仅表头 CSV
**核**：`exportProducts`（`VarietyService:877-884`）**恒先 add 表头** ⇒ 无可见数据时返回仅表头 CSV。`FR-PRODUCT-8` 验收 3（`PRODUCT.md:199`）：「无数据时导出被拦截并提示，**不生成空文件**」。
**判定**：前端**已在界面侧拦截**（`product.js:121-125`：仅表头 ⇒ warn 提示、不触发下载）并**诚实标注启发式局限** ⇒ 用户侧「不生成空文件」**已满足**；后端仍生成表头 CSV 属**边界语义差异**，**是否算缺陷取决于「不生成空文件」是界面裁决还是服务端契约**（**建议产品经理澄清**）。
**立号核对**：`TASKS.md:2696`（`T-131` 备注）称本项「**亦归 `T-116`**」，但 `T-116` 验收（`:2459-2464`）**只列 `FR-PRODUCT-8` 验收 1**，**未含验收 3** ⇒ **承载文本对不上**（见 `OBS-T018-3`）。

### 4.6 `ProductView` 无 `createdTime` ／ `ProductChangeLogHandler` 仅接受 `productId`
**核**：
- `ProductView` 确无 `createdTime`（`ProductHandler:139-185` 10 字段）。但 `FR-PRODUCT-5` 输出列（`:149`）要的是「状态、分类、**子账户**、**最近变更时间**」——**不含 `createdTime`** ⇒ 「无 `createdTime`」**本身不是需求缺口**；真缺口 = **子账户 + 最近变更时间**，正是 `T-116` 验收 3（`TASKS.md:2462`）⇒ **该半项承载正确**。
- `ProductChangeLogHandler` 确仅读 `productId`（`:40-51`），**不支持时间范围/变更类型筛选**。`FR-PRODUCT-14` 输入（`PRODUCT.md:279`）=「品种标识、**时间范围**、**变更类型筛选**」⇒ **要求 > 实现**成立。但该缺口**不在 `T-116` 验收内**（`T-116` 追溯 `FR-PRODUCT-5`/`FR-PRODUCT-8`，**不含 `FR-PRODUCT-14`**）⇒ **承载不成立**（见 `OBS-T018-2`）。**另注**：`T-105` 备注（`TASKS.md:2206`）已登记 `FR-PRODUCT-14` 的**隔离**待确认项——那是**隔离**、非**筛选输入**，二者不同。

### 4.7 层③授权 = 已知 `CH-012`（**处置正确**）
**核**：层③写面授权口径确载于 `CH-012`（`PRODUCT.md:235` 处理规则 + 新增验收 5 `:245`；`INDEX.md` §10 `:666`）。交付方**仅做界面**（`product.js:848` 明示「本页仅提供界面，不做授权判定，越权由后端拒绝」）⇒ 符合「前端不做业务/授权判定」纪律，**未当缺陷报正确**。
**⚠️ 附带发现（文档一致性，非 T-018 缺陷）**：`INDEX.md` **文件头 `:3`** 写 `CH-012 … **待用户确认**`，而 **§10 `:666`** 写 `**✅ 已由用户确认（2026-09-21）**`；`BOARD.md:392`（第 30 轮）又记「用户**暂缓确认** `CH-012`」⇒ **两/三处状态不一致**（见 `OBS-T018-1`）。

### 4.8 立号总评
| 项 | 去向 | 本席核 |
|----|------|--------|
| (1) 写面端点 | `T-131` | ✅ 正确 |
| (2) 白名单读 | 并入 `T-131` | ⚠️ `FR-PRODUCT-12` **不要求**（就 12 而言非缺陷）；真依据在 `FR-PRODUCT-6`，`T-131` 验收 3 未涵盖 ⇒ 建议补 |
| (3) 列表筛选 | `T-116` | ✅ 正确 |
| (4) number/BigDecimal | `T-132` | ✅ 正确、穷举要求恰当 |
| (5) 导出空数据 | `T-116` | ⚠️ `T-116` 验收**未含** `FR-PRODUCT-8` 验收 3；且「是否缺陷」待产品澄清 |
| (6) createdTime/变更日志筛选 | `T-116` | ⚠️ 前半（最近变更时间）✅；后半（`FR-PRODUCT-14` 筛选输入）**无承载** |
| (7) 层③授权 | `CH-012` | ✅ 正确 |

## 5. `F` —— 证据充分性判定（单独结论）

**产出方证据形态**：无头 DOM + `fetch` 桩（Node 22）自测 **23/23 PASS**，并**如实标注「非真实浏览器验证」**。**该 23/23 harness 未入库/未提交** ⇒ 本席**无法在仓库内复现其具体断言**（仅能自撰等价探针）。

**本席采信口径 = 部分采信（对「契约 / 逻辑面」充分；对「浏览器 / 真机面」不足）**：

| 结论面 | 采信 | 理由 |
|--------|------|------|
| 6 端点调用点、camelCase 字段名、分页信封消费、排序**显式方向** | **可采信** | 静态逐字核对 + **本席独立探针**双向证实 |
| 三态分支存在、errmsg 四项渲染、导入逐行解析、导出不吞错 | **可采信** | 本席探针实跑（31/31） |
| 页面在**真实浏览器**可加载/渲染/交互（ES module 加载、真实 CSS 布局、`Blob`/`Content-Disposition` 真实下载、`FileReader` 选文件） | **不可采信** | 环境无浏览器/jsdom/puppeteer；无头桩**不覆盖**真实渲染与真实网络/会话 |
| 与**真实 Java 后端**端到端（真实信封/`HGSESSION`/真实 DTO 序列化） | **不可采信** | 探针为 `fetch` 桩，未打通真实服务 |

**因此不能成立的具体结论**：①「页面在浏览器中可用」**只能**降级为「页面**逻辑**可用」；②「导出**真实**触发浏览器下载」未验；③「导入**真实**选文件→`FileReader`→提交」未验；④真实 `BigDecimal→number` 的**界面观感**未验（但契约违规已由静态确证）。

**要更强证据需要什么**：① 若环境可得，引入无头浏览器（Playwright/Puppeteer）跑「加载 `index.html` → 导航 `#/product` → 断言 DOM 含列/触发导出下载」；② 或对**已装配的 Java 服务**做端到端冒烟（真实 Cookie 会话 + 真实 DTO）。**当前环境两者皆不可得** ⇒ 记录为本任务**残余验证风险**，建议并入 M-里程碑的**前端浏览器冒烟**（不阻断 T-018 的逻辑级验收）。

## 6. 缺陷清单 / 观察项 / 结论

**缺陷单：0（无 `TE-T018-00x`）** —— 未在 `product.js`/`api.js` 发现可复现的**实现缺陷**（前述所有缺口均属**后端/需求面**并由 `T-116`/`T-131`/`T-132` 或 `CH-012` 承载，**非本单产出面**）。

**观察项（均不阻断）**：

| 编号 | 级别 | 内容 | 去向建议 |
|------|------|------|---------|
| `OBS-T018-1` | 低·文档 | `REQUIREMENTS/INDEX.md` `:3`（待确认）vs `:666`（已确认 2026-09-21）vs `BOARD.md:392`（暂缓确认）三处 `CH-012` 状态不一致 | 产品席/编排者厘清（非 T-018） |
| `OBS-T018-2` | 中·承载 | (6) 的「变更日志**筛选输入**（`FR-PRODUCT-14`）」**无任务承载**（`T-116` 追溯不含 `FR-PRODUCT-14`） | 补入 `T-116` 或另立号 |
| `OBS-T018-3` | 中·承载 | (5)「导出空数据」`T-131` 备注称归 `T-116`，但 `T-116` 验收未含 `FR-PRODUCT-8` 验收 3 ⇒ 承载文本对不上；且「是否缺陷」待产品澄清 | 补 `T-116` 验收 或 澄清语义 |
| `OBS-T018-4` | 低·承载 | (2) 白名单读：`FR-PRODUCT-12` 不要求，但 `FR-PRODUCT-6` 要求「详情含所属子账户白名单」⇒ `T-131` 验收 3 若只核 12 会漏 | `T-131` 验收 3 扩为同时核 `FR-PRODUCT-6` |

**结论：`T-018` = 有条件通过（0 阻断缺陷）** ⇒ 按本项目判据**可转 `DONE`**（授权 + 独立验证）。**条件/残留**：真实浏览器端到端证据缺位（环境限制，见 §5），登记为残余验证风险；4 观察项建议由编排者处置（不影响 T-018 达标）。

## 7. 纯追加证明

- 落笔**前**（提交态冻结）：`docs/TEST_RESULT.md` = `6988ede839c95a13916793350296b28a` / **5578 行**。
- 落笔**后**：`head -5578 docs/TEST_RESULT.md | md5sum` = `6988ede839c95a13916793350296b28a`（**与落笔前整档逐字节相同**）⇒ **本章为纯追加**、未触碰既有内容。
- 共享源树 `web/**`/`java/**` 本章落笔期间**未改**（`product.js` 仍 `b8e66b70…`、`web` 树仍 `f827e00e…`/27）。

---

# `T-023` 交付报告 —— M1 单测与集成测试（JUnit 5）〔交付实例 `be-t023`，2026-09-23〕

> **本章由编排者落笔**（内容为交付方 `be-t023` 回传的正文，**未改写其结论**；仅将首行改为本章标题并删去交付附言）。
> 交付实例**未改 `docs/**`**；原始证据日志 `/root/t023-*.log`、`/root/t023-coverage-summary.txt`。

---

## 0. 结论速览

| 项 | 结论 |
|----|------|
| 验收 1（覆盖率 ≥ 80%） | ✅ **达标**：五模块**行覆盖率**均 ≥ 80%（配置 91.6% / 产品 89.9% / 权限 87.9% / 计划 83.1% / 现货 82.0%） |
| 验收 2（`-Punit` + 集成测试） | ✅ **字面满足**（补 `unit` profile）；集成测试以**等价执行**跑通「建表→读写→查询」；**⚠️ 未验 DM8 实机** |
| 验收 3（约束三类 + 敞口三分量） | ✅ **全部通过**（详见 §3） |
| 全量用例 | **504 / 0 / 0 / 0**（62 类；surefire 名单为准） |
| 反退化 | ✅ 2 个自设变异均**杀红**，复位逐字节一致 |
| 构建 | `mvn -B -o -Punit clean test` **BUILD SUCCESS**；`mvn verify` 前置 arch-check 聚合 **rc 0** |

---

## 1. 改动清单（仅列本实例所改）

| 文件 | md5 | 类型 | 摘要 |
|------|-----|------|------|
| `java/pom.xml` | `c5b52006ca5ec6807ae20a94a57cd2d8` | 改 | **仅新增两个 profile**：`unit`（空语义=默认档，字面满足验收 2）与 `coverage`（jacoco 0.8.12 + surefire `argLine` 追加 `${argLine}`）。**未动依赖/插件、未动主源** |
| `java/src/test/java/com/hedgeguard/dao/testsupport/InMemoryDmEngine.java` | `02f6964a960af635e561a6e94bdb75fa` | 新 | 按 SQL 语义求值的**内存假 JDBC 引擎**（\~1268 行）：建表（列/NOT NULL/IDENTITY/DEFAULT/主键/唯一/外键/CHECK）、INSERT/UPDATE/DELETE、SELECT（WHERE/IN/ORDER BY/LIMIT-OFFSET/COUNT 包裹） |
| `java/src/test/java/com/hedgeguard/dao/M1Dm8EquivalentIntegrationTest.java` | `057928803550032c1aa94fb728f04523` | 新 | **集成测试（等价执行）**（380 行、10 例）：建表→读写→查询 + 约束三类 + 敞口三分量 DB 级往返 |
| `java/src/test/java/com/hedgeguard/service/ConfigServiceAdminTest.java` | `778e0c7bb748e2b10918ae662c659566` | 新 | **CONFIG 管理面覆盖补齐**（762 行、10 例）：字典/会计科目/报告周期/CTP 前置/参数下发/导入导出 |

> **落点说明**：前两个文件置于 `**/dao/**` 下，因 E5 判据「Java 的 SQL 只允许出现在 `dao` 层」
> 对全仓（含 `src/test`）生效——与既有 `M1DaoMappingAndWriteTest`（`dao/impl`）同惯例。
> 初稿曾落 `com.hedgeguard.integration` / `com.hedgeguard.testsupport`，被 E5 判红 6 处后改置。
>
> **未改主源（`java/src/main/**`）零改动**；`docs/**` 零改动。
> `git status` 另示 `docs/TEST_PLAN/{INDEX,PRODUCT,RISK,SPOT}.md` 为 modified —— **非本实例改动**（他人并发写入，本实例全程未触 docs）。

---

## 2. 逐条验收自证

### 验收 1：覆盖率 ≥ 80%

**手段**：新增 `coverage` profile 接 jacoco。命令与输出：

```
$ cd java && TMPDIR=/root mvn -B -o -Pcoverage test
[INFO] Tests run: 504, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- jacoco:0.8.12:report (jacoco-report) @ hg-biz ---
[INFO] BUILD SUCCESS
```

> jacoco 插件 0.8.12 系**在线下载一次**入本地仓（`mvn -o` 之前先 `dependency:get`），此后离线可复现。
> 报告落 `java/target/site/jacoco/index.html` 与 `jacoco.xml`。
> **坑**：默认 surefire 的 `argLine` 写死 `-Dfile.encoding=UTF-8`，会覆盖 jacoco 注入的 `${argLine}`
> ⇒ 首跑报「missing execution data file」；已在 `coverage` profile 内把 argLine 改为默认值 + `${argLine}` 修正。

**五模块（TASKS T-023 验收 1 点名）逐模块行覆盖率**：

| 模块 | 主服务类 | 行覆盖率 LINE | 指令 INSTR | 分支 BRANCH | 判定 |
|------|---------|--------------|-----------|------------|------|
| 配置 | `ConfigService` | **91.6%** | 90.8% | 68.7% | ✅ |
| 产品 | `VarietyService` | **89.9%** | 87.8% | 76.2% | ✅ |
| 权限 | `AuthService` | **87.9%** | 86.3% | 67.6% | ✅ |
| 计划 | `PlanService` | **83.1%** | 81.9% | 61.6% | ✅ |
| 现货 | `SpotService` | **82.0%** | 79.8% | 65.1% | ✅ |

**NFR-TEST-1 核心模块（方案计算 / 额度校验 / 会计生成）行覆盖率**：

| 核心模块 | 承载类 | 行覆盖率 | 判定 |
|---------|--------|---------|------|
| 方案计算（套保比率三档） | `PlanService` | 83.1% | ✅ |
| 额度校验（额度/红线） | `VarietyService`（+ `PlanService` 红线段、`SubAccountVisibility` 100%） | 89.9% | ✅ |
| 会计生成（凭证/文档） | **M1 未交付该模块** | **无法度量** | 不适用（M1 不在范围） |

**未达 80% 的模块（如实列出并说明）**——以下均**非** T-023 点名的「核心逻辑」层，且不属 NFR-TEST-1 三类核心模块：

| 包 | 行覆盖率 | 说明 |
|----|---------|------|
| `com.hedgeguard.dao.impl` | 41.2% | 主要未覆盖在**各表 DAO 的 `updateById`/专用查询**等分支；本轮以**真实 DAO 的 insert/findById/findPage/findByCode** 端到端覆盖了主通路（见验收 2），未覆盖分支多为未接线/未使用的写入方法 |
| `com.hedgeguard.web.handler.{spot,auth,config,product,plan}` | 20.2% / 37.1% / 21.5% / 32.0% / 65.1% | **HTTP Handler 适配层**（薄封装）；其业务逻辑在 service 层已被覆盖，Handler 的 happy-path 由路由/分发用例与集成测试覆盖，细分支未逐一覆盖 |
| `com.hedgeguard.web` | 76.8% | 分发器/过滤器；接近阈值 |

**项目整体**（供参考，非本验收判据）：LINE 66.9%、INSTR 66.8%、BRANCH 59.7%、METHOD 70.0%、CLASS 78.3%。
（整体 < 80% 系 Handler 适配层与部分 DAO 写入分支未覆盖所致；**核心模块**口径已达标。）

### 验收 2：`mvn test -Punit` + 集成测试

**(b1) `-Punit` 字面满足**（已补 profile，未改判据）：

```
$ cd java && TMPDIR=/root mvn -B -o -Punit test          # 结果：Tests run: 504, 0/0/0；BUILD SUCCESS
$ cd java && mvn -B -Punit help:active-profiles          # （在线一次，helper 插件离线缺）
The following profiles are active:
 - unit (source: com.hedgeguard:hg-biz:1.0.0-SNAPSHOT)
```

`unit` profile 语义 = **不启用 `dm` 的默认档**（不引 DM 驱动、不建真实连接，全部走假 DAO / 假 JDBC）。
本 profile **不增删依赖与插件** ⇒ `mvn test -Punit` 与 `mvn test` 等价。**未改判据**。

**(b2) 集成测试「建表→读写→查询」——等价执行**（⚠️ **未验 DM8 实机**）：

DM8 环境**不可达**（全盘 `DmJdbcDriver*.jar` 0 命中、`java/lib` 不存在、5236 无监听、无 `dmserver`）。
依编排者裁定 (a)，以**按 SQL 语义求值的内存假 JDBC 引擎**真跑：

| 阶段 | 做法（真执行，非仅断言 SQL 字符串） | 证据用例 |
|------|-----------------------------------|---------|
| **建表** | 把 4 个 M1 脚本（`m1_config_auth`/`m1_product`/`m1_spot`/`m1_plan`）的 `CREATE TABLE` 逐条经 `Statement.execute` 喂给引擎 ⇒ **登记 46 张表**，并按 DDL 语义登记 NOT NULL/主键/唯一/外键/CHECK | `M1Dm8EquivalentIntegrationTest.ddlScriptsCreateSchema`（断言 `tableCount()==46`、`uk_product_code`、`fk_exposure_sub→t_sub_account` 等） |
| **读写** | 由**真实 `DmProductDao`** 生成 INSERT/SELECT/UPDATE，经引擎按语义求值：主键回填 `id=1`、`findById`/`findByCode` 读回、`updated_time` 空值语义、乐观锁 `row_version` 推进 | `insertAndReadBackThroughRealDao`、`optimisticLockUpdateAdvancesVersion` |
| **查询** | `findPage` 的 `SELECT COUNT(*) AS cnt FROM (…) hg_cnt` 包裹 + `ORDER BY … LIMIT ? OFFSET ?` 经引擎求值：`total=3`、第一页 3 行、第二页 0 行、`id` 升序首行 `A1` | `pagedQueryReturnRowsAndCount` |

引擎自披露：可求值 CHECK **110** 条、登记但未求值的复杂 CHECK **54** 条（`engineDisclosesUncheckedChecks`，控制台 `[T-023] 内存引擎 CHECK：可求值=110，登记未求值=54`）。
**明确不覆盖**（诚实披露）：条件唯一索引 `WHERE` 谓词、子查询/JOIN/聚合、多列外键、事务回滚还原 —— 均在引擎类 javadoc 与本表披露。

> **口径声明**：以上是**等价执行**，**不构成 DM8 实机验证**。「DM8 实机建表→读写→查询」仍待部署方在可达实例复跑，
> 由 `T-024`（端到端验收）承接。**不得**将本档写成 DM8 验证。

### 验收 3：约束类三类 + 敞口三分量

**约束类**（判据口径见 `TEST_PLAN/INDEX.md` §35：唯一约束→重复插入；非空→空值；外键→孤儿数据）。**全部通过**：

| 类 | 用例 | 断言（含结果） |
|----|------|--------------|
| **重复插入** | `M1Dm8EquivalentIntegrationTest.duplicateInsertOnUniqueColumnIsRejected` | DB 级：同 `product_code` 二次插入 → `IllegalStateException` 含「违反唯一约束 … uk_product_code」；引擎行数仍 1；`rejectedByUnique()==1` |
| 重复插入（服务级补充） | `ConfigServiceTest.missingItemAndDuplicateKey`(→10010)、`ConfigServiceTest.createSubAccountForcesEnabledAndLogs`(重复编码→10010)、`VarietyServiceTest`「新增品种：编码重复 10010」、`SpotServiceTest`「同子账户同品种同类型同日重复录入拒绝」、`AuthSeedDataTest`「三段 INSERT 均以 NOT EXISTS 去重」 | 均绿 |
| **空值** | `M1Dm8EquivalentIntegrationTest.nullOnNotNullColumnIsRejected` | DB 级：`t_product.product_code`（NOT NULL）写 null → `IllegalStateException` 含「NOT NULL … product_code」；落库 0；`rejectedByNotNull()==1` |
| 空值（服务级补充） | `ConfigServiceTest.disableRequiresReasonAndExistingAccount`（原因空白→10001）、`VarietyServiceTest`「期权三要素必填」 | 均绿 |
| **孤儿数据** | `M1Dm8EquivalentIntegrationTest.orphanForeignKeyIsRejected` | DB 级：`t_sub_product_whitelist.sub_account_id=9999` 在 `t_sub_account` 无父行 → `IllegalStateException` 含「外键 … t_sub_account」；落库 0；`rejectedByForeignKey()==1`；**反向对照**：补 id=9999 父行后同写入成功（证拒绝确由「孤儿」引起） |
| 孤儿/引用完整性（服务级补充） | `SpotServiceTest.voidReferenceCheck`（被敞口明细引用→10011）、`SpotT109FixTest` FR-SPOT-16（被生效计划引用→拒因列「计划」） | 均绿 |
| 取值域（CHECK 边界，附加） | `M1Dm8EquivalentIntegrationTest.checkConstraintRejectsOutOfRangeValue` | `t_product.category='OTHER'` → `IllegalStateException` 含「CHECK … ck_product_category」 |

**敞口三分量**用例（`TC-SPOT-14` / `TC-BOUND-9`）**全部通过**：

| 用例 | 断言 |
|------|------|
| `SpotServiceTest.exposureRejectsComponentMismatch`（:284） | 写入侧：Σ分量 ≠ 合计 → **拒绝写入**且不落库 |
| `SpotServiceTest.exposureDetailsMustSumToComponents`（:329） | 明细之和须等于分量列（否则拒写）；一致时连明细一并落库 |
| `SpotServiceTest.exposureUnmappedSentinel`（:296） | 未映射哨兵值（0）与标记双向联动（DC-041） |
| `SpotServiceTest.exposureValuationLabelsNonCurrentPrice`（:358） | 估值沿用非当日价须标注 `price_as_of` + 非当日价标记 |
| `SpotServiceTest.spotCategoryDualState`（:373） | 品类双态（CH-008）：未映射按真实品类各占一行、已映射归一行 |
| `SpotServiceTest.displayRecalcNeverWritesBack`（:424） | 展示侧重算：不一致 → 留痕 `COMPONENT_RECALC` 且 `writtenBack=false` |
| **`M1Dm8EquivalentIntegrationTest.exposureThreeComponentsRoundTrip`（新）** | **DB 级往返**：真实 `DmExposureDao` 写入 `contract_unexecuted_qty=40 / inventory_qty=10 / production_plan_qty=10` 后 `findById` 读回，**Σ分量 = 合计（60）** |

---

## 3. 现值数字与锚点

**测试总数**（surefire 名单为准，非 `grep -c @Test`）：

```
父 POM 全量：Tests run: 504, Failures: 0, Errors: 0, Skipped: 0        （62 个 *Test 类）
基线（T-023 开工时）           ：484 / 0 / 0 / 0
本实例新增                     ：+20（集成测试 10 + CONFIG 管理面 10）
```

**`java/src` 锚点**（`find java/src -type f | LC_ALL=C sort | xargs md5sum | md5sum`）：

```
基线（T-023 开工时）： 8bf0e1d6c2e83a52e62d511616324c4b  / 335 文件
交付后（本次）      ： c5817cb53f61b169ad7b6cdca335a817  / 338 文件   （+3 文件 = 引擎 + 2 测试；前缀一致）
```

> 说明：锚点**不含** `java/pom.xml`（本锚点为 `java/src` 级）。若需仓库级锚点请另行定义口径。

**构建门禁**：`mvn -B -o -Punit clean test` → BUILD SUCCESS；聚合 `tools/arch-check/arch-check.sh` **rc 0**（E1–E7 全 0、E5 退 0；新增文件不再触发 E5——见 §1 落点说明）。

---

## 4. 反退化（自设变异，均复位逐字节一致）

| # | 变异（探针，**非交付改动**） | 期望 | 实测 | 复位校验 |
|---|---------------------------|------|------|---------|
| A | `InMemoryDmEngine.validate()` 关闭**外键强制**（`if (value == null)` → `if (value == null \|\| true)`） | `orphanForeignKeyIsRejected` 变红 | ✅ 红：`Expected java.lang.IllegalStateException to be thrown, but nothing was thrown`（`t023-mutA.log`） | `md5 = 02f6964a960af635e561a6e94bdb75fa`，`diff` 逐字节一致，无 `MUTATION-A` 残留 |
| B | `ConfigService.saveReportPeriod()` 关闭**合规必需报告门禁**（`if ("Y".equals… && !"Y".equals…)` → `if (false && …)`） | `ConfigServiceAdminTest.reportPeriodLifecycle` 变红 | ✅ 红：`Expected com.hedgeguard.web.BizException to be thrown, but nothing was thrown`（`:179`，`t023-mutB.log`） | `md5 = 6f656de0dde946bb52576a7d2aae9d48`，`diff` 逐字节一致，无 `MUTATION-B` 残留 |

> **变异 B 触及 `java/src/main`**：系**临时探针**（`.orig` 备份 → 单次运行 → 逐字节复位 → md5 校验），
> **未留下任何主源改动**（`ConfigService.java` 现行 md5 = `6f656de0dde946bb52576a7d2aae9d48` = 变异前值）。
> 之所以作此探针：仅**测试侧**变异只能证明「用例对自身桩在承重」，**证明「用例对生产规则承重」只能对被测生产行为注入故障**——
> 此为仓库既有既定手法（备份 + 复位 + md5 校验）。若编排者认此越界，可仅采信变异 A（纯测试域）。

---

## 5. 未决 / 建议（「还差什么才算 T-023 真正完成」）

**✅ 已可判定达标**：验收 1（五模块行覆盖率均 ≥80%）、验收 2 的 `-Punit` 字面档、验收 3（约束三类 + 敞口三分量）。

**❓ 未决 / 待承接（诚实清单）**：

1. **DM8 实机未验**（最大缺口）：本轮「建表→读写→查询」仅**等价执行**。真正闭环须在**可达 DM8 实例**上复跑 4 个 DDL 脚本 + 真实 DAO 读写查询；建议 `T-024` 承接，并保留本报告 §2 的等价证据作对照。
2. **ACCT 核心模块无覆盖率**：NFR-TEST-1 点名的「会计生成」在 M1 未交付 ⇒ 该类覆盖**无法度量**；待 ACCT 模块交付后单独立项。
3. **`java/lib/DmJdbcDriver18.jar` 缺失**：`dm` profile 的 `systemPath` 指向不存在的文件 ⇒ `mvn -Pdm …` 不可用（非本任务范围，属部署前置 R-5）。
4. **DAO 写面分支覆盖偏低**（`dao.impl` 41.2%）：多为未接线/未使用的 `updateById`/专用查询分支；如需提升需针对各表 DAO 补写用例（建议随各表任务增量补，不必在 T-023 一次性拉满）。
5. **Handler 适配层覆盖偏低**（`web.handler.*` 20%–65%）：属薄适配层，业务逻辑已在 service 覆盖；若门禁要求全包 ≥80%，需单列任务。

**📌 交付物完整性**：本轮 `java/src/test` 新增 3 文件、`java/pom.xml` 新增 2 profile；`java/src/main` 与 `docs/**` 零改动。

**复现命令**（全部离线可复现，除首次 jacoco / help 插件下载）：
```
cd java
TMPDIR=/root mvn -B -o -Punit clean test          # 504/0/0/0
TMPDIR=/root mvn -B -o -Pcoverage test            # 生成 target/site/jacoco（覆盖率）
```

---

# `T-022` 独立复核证据（2026-09-23）

**复核席**：测试工程师（实例 `te-verify-t022`） ｜ **只读范围**：`docs/TEST_PLAN/**`、`java/**`、`web/**`、`docs/TASKS.md`、`docs/BOARD.md`、`docs/REQUIREMENTS/**`（本席零改动）。
**复核对象**：`T-022`（制定 M1 测试策略与用例设计 · 约束→用例映射）交付物 = `docs/TEST_PLAN/**`。
**交付 commit**：`64b706d`（复核时点 `HEAD = 6462c0c`）。
**结论**：**有条件通过**（**0 阻断缺陷**；1 低缺陷 `TE-T022-001`；4 观察项）。按本项目判据「有条件通过 + 阻断缺陷未清零 = 退回」⇒ 本单**无阻断缺陷**，可转 `DONE`（须先处置低缺陷或经编排者接受）。

## 1. 锚点复现（独立实测）

| 项 | 声称 | 独立实测 | 判定 |
|---|---|---|---|
| `INDEX.md` | `bc4e19fa7a9dbd5022c7b66712295fa9` / 862 | 同（`md5sum`） | ✔ |
| `SPOT.md` | `2b4b0cfa1f24fb821faac7da3a8579c7` / 663 | 同 | ✔ |
| `RISK.md` | `442634a929bfc29ce236cfc1cdb3a7f9` / 888 | 同 | ✔ |
| `PRODUCT.md` | `74ca4b51bb1442c77deac5a33a2a572f` / 486 | 同 | ✔ |
| 树锚 | `b5d58673ab2b4eb9c607cce776fc8dc2` / 14 | 同（`find docs/TEST_PLAN -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum`，cwd=仓库根） | ✔ |
| `git diff --numstat c8d9e0a 64b706d -- docs/TEST_PLAN/` | `141/15` `1/0` `28/1` `85/1` | 逐条吻合 | ✔ |

**静默**：4 文件 md5 与树锚**连续 3 次取样（间隔 ≥6s）恒定**（`304dc706…` 组合值 ×3）。✔

## 2. ★测试设计质量（本单核心）

**回读原文基准（非只信编号）**：
- `DATA_MODEL.md:4503`（`#17`「敞口三分量缺失 / 合计不一致」）：③「确需改落库值…重算生成新快照（新 `snapshot_id`）、旧快照保留（禁止静默覆盖）」；④「沿用最近一期分量…**套保比率计算用途下不得沿用，一律拒绝**」；⑤「`Q-8` 敞口变动 >20%…分量不完整时**暂缓判定并告警**，不得用残缺分量得出触发结论」。其行末用例枚举 ①–⑤ 中 **③④⑤** 即本轮补件对应项。
- `DATA_MODEL.md:4498`（`#13` 三层限额全序）：用例枚举 **⑤ =「层①+层③同时超 → 回 `SUB_PRODUCT`」**。
- `ARCHITECTURE.md` §6.10（`:1879–1888`）「用例判据」①缺分量+比率→明确报错**且不落比率记录**；③缺分量+>20%→**不产出生效触发记录**、仅告警；⑥改落库→**新 `snapshot_id` 且旧快照仍可查**。

| 用例 | 声称对应 | 独立实测 | 唯一预期值？ | 判定 |
|---|---|---|---|---|
| `TC-SPOT-21`（`SPOT.md:560`） | `#17`③ / §6.10 判据⑥ | 语义逐字吻合（新 `snapshot_id`≠`S1`、合计 181、旧行 `S1` 保留、行数 ≥2） | 是（数值确定） | ✔ |
| `TC-SPOT-22`（`SPOT.md:588`） | `#17`④ / §6.10 判据① | 语义吻合（缺分量→拒算；`hedge_ratio` 保 NULL） | 是（`code=10015`、NULL） | ✔（见 `O-1`） |
| `TC-SPOT-23`（`SPOT.md:614`） | `#17`⑤ / §6.10 判据③ | 语义吻合（生效触发记录数=0、告警 ≥1） | 是（计数确定） | ✔ |
| `TC-RISK-31`（`RISK.md:838`） | `#13`⑤ / `DATA_MODEL.md:4498` | 语义吻合（`limit_layer=SUB_PRODUCT`、层②未命中、`entries[]` 2 条、`0x1004`） | 是（值确定） | ✔ |

**FR 归属核（防张冠李戴）**：`TC-RISK-31` 挂 `FR-RISK-2` ⇒ 实测 `REQUIREMENTS/RISK.md:113`「### FR-RISK-2 事前风控：额度校验（三层限额）」**吻合** ✔；`TC-SPOT-21/22/23` 挂 `FR-SPOT-14` ⇒ 实测 `REQUIREMENTS/SPOT.md:294`「### FR-SPOT-14 计算实货敞口」**吻合**（`R-SPOT-4` `:399` 载三分量处置）✔。**无张冠李戴**。

**错误码核**：
- `0x1004`（`TC-RISK-31`）⇒ 实测 `ARCHITECTURE.md:1363` = `RISK_SUB_PRODUCT_QUOTA_INSUFFICIENT`「限额层① 子账户+品种额度不足」、`:1445`「层① `0x1004`」⇒ **码存在、语义相符** ✔。
- `10015`（`TC-SPOT-22`）⇒ 实测 `ARCHITECTURE.md:1318` =「数据不足，拒绝计算」**含义相符**；但触发条件列为「现货价样本 < 30 交易日 / 缺价」**不含缺分量** ⇒ 见观察项 `O-1`。

## 3. 缺口声明 `Q1`–`Q4` 逐条验真（`INDEX.md` §16.8）

| # | 产出方声明 | 独立实测 | 判定 |
|---|---|---|---|
| `Q2` | **已闭环**（`#17`③④⑤ 补 `TC-SPOT-21~23`） | `TC-SPOT-21/22/23` 确落到 `SPOT.md`，语义逐条对上 `#17`③④⑤（§2） | **闭环成立** ✔ |
| `Q3` | **已闭环**（`#13`⑤ 补 `TC-RISK-31`） | `TC-RISK-31` 确落到 `RISK.md`，语义对上 `#13`⑤ | **闭环成立** ✔ |
| `Q4` | 行文**仍称「缺 `里程碑` 行 / 0 命中 / 未授权前不改」** | 实测 `PRODUCT.md:23`、`SPOT.md:22` **已有** `\| 里程碑 \| M1 \|`；`grep -c '^| 里程碑 |'` 恰 1；**11/11** 模块齐且**同形**（皆 2 列 `\| 里程碑 \| … \|`） | **底层缺口已补，但 §16.8 行文未更新 ⇒ 自相矛盾**（`TE-T022-001`） |
| `Q1` | **未闭合**（外键→孤儿 / `CHECK`→取值域**无 `TC-` 级用例**），由 `T-023` 承载 | `grep -rn '孤儿\|引用完整' docs/TEST_PLAN/*.md` = 5 命中，**全为散文提及**（无一是 `^### TC-` 级）；`T-023` 集成测试确有 `orphanForeignKeyIsRejected`/`checkConstraintRejectsOutOfRangeValue`（`M1Dm8EquivalentIntegrationTest.java:228/:256`） | **声明属实** ✔（证据列措辞见 `O-4`） |

> 注：`Q4` 与台账 `BOARD.md:201` / `TASKS.md:606` 自称之「**11/11 模块「里程碑」行齐**」**互相矛盾**——台账/实测为「齐」，`INDEX.md:860` 为「缺」。

## 4. 结构完整性

- **§16 八子节**：`16.1`–`16.8` **无重号**（`grep '^### 16\.'` 逐条：753/767/786/797/809/826/840/853）✔。
- **表格列数等值**（按前导反斜杠**奇偶**扣转义；口径 `有效 = 原始 \| 数 − 应扣转义数`，列数 = 有效 − 1）：`INDEX.md` 全 43 块、`SPOT.md` 全 49 块、`RISK.md` 全 63 块、`PRODUCT.md` 全 26 块——**逐块列数唯一的表 100% 等值，不符 0**（自建 `eff_pipes()` 按奇偶判转义）。例：`§16.8` 头行原始 5 \|、含 2 处 `\|`（`\| 里程碑 \| M1 \|` 转义）= 有效 4 ⇒ 列 4，与各数据行一致 ✔。
- **§15 尾行逐字节保留**：`diff <(git show c8d9e0a:…\|sed -n '734,736p') <(sed -n '736,738p')` **IDENTICAL** ✔（`§16` 系纯追加）。
- **`git diff` 每条 `−` 行均可用「换数/换值」解释（逐条审）**：`INDEX` **15 条**全为计数/值更新（`SPOT` 20→23、`RISK` 30→31、合计 307→311、`M/N` 1.11→1.28 / 1.58→1.63、`≤20/>20` 行 SPOT 移位、`三层限额 6→7 条`、`FR-SPOT-14` 用例串增 3、`FR-RISK-2` 串增 1、执行率（307→311）×2、文件清单（20→23 / 30→31）×2）✔；`SPOT` **1 条** =`\| FR-SPOT-14 \| 4 \|`→`7`；`RISK` **1 条** =`\| FR-RISK-2 \| 6 \|`→`7`；`PRODUCT` **0**。**无内容误删** ✔。

## 5. 跨制品计数（独立复测）

- **用例总数 311**：逐文件 `grep -cE '^### TC-'` 求和 = **311**（`SPOT` 23、`RISK` 31）✔；`SPOT.md §2` 表行 23、`RISK.md §2` 表行 31 ✔。
- **`\b307\b`**：`grep -rn '\b307\b' docs/` = **4 命中**（`TEST_RESULT.md:991`=`plan.js:304-307` 行区间；`TASKS.md:606`、`BOARD.md:201`、`BOARD.md:459`=`「307→311」变更留痕`）；`docs/TEST_PLAN/**` 内 **0 命中** ⇒ 见 `O-3`。
- **`272/174/98` 未改**：`INDEX.md:112/:120/:122/:124` 三数原样；`:125` 为**新增**时点边界注（只加不改）。独立佐证：本席实测 `grep -rn '@Test' java/src/test --include=*.java \| wc -l` = **504**（> 272，与时点注「已 > 272」一致）✔。

## 6. 范围合规

`git diff --name-only c8d9e0a 64b706d` = **4 文件**（皆 `docs/TEST_PLAN/**`）；`git show --name-only 64b706d` **无** `java/**`、`web/**`、`docs/TASKS.md`、`docs/BOARD.md`、`docs/TEST_RESULT.md` ⇒ **范围合规** ✔。（复核期另见工作区 `M docs/REQUIREMENTS/INDEX.md`、`M docs/REQUIREMENTS/PRODUCT.md`——系**他人**在改（`CH-012` 同步），**不在本单范围**，未触碰本席写入面。）

## 7. ★自设变异（证明判据/结构承重）

| # | 形态（在**隔离副本** `/root/t022_probe/TEST_PLAN/` 内做，**共享源树零改动**） | 期望 | 实测 | 复位 |
|---|---|---|---|---|
| A | 把 `TC-SPOT-21` 步 1 预期值改为「结果正确、合理即可」（不可判定） | 若有守卫应红 | **无任何检查会红**（`tools/` 0 引用 `TEST_PLAN`；`Makefile`/`pom.xml` 不校验） | ✔（副本已弃） |
| B | 删 `SPOT.md §2` 的 `TC-SPOT-21` 行 | 若能自洽校验应红 | `§2` 行=**22** 而 `^### TC-`=**23**（**不一致**）⇒ **无守卫检出** | ✔（副本已弃） |

**结论（判据强度事实，非缺陷）**：`docs/TEST_PLAN/**` 的「用例计数」「§16 映射自洽」**无机器承重**，全靠人工/复核——故本单须靠本席逐条实测（§2–§5）。**如实披露，不夸大**。

## 8. (F) 可用性判定：能否支撑 `T-023`（执行）/ `T-024`（端到端验收）

**可用于取数**：
- `§16.4`/`§16.5` 给出 `#17`③④⑤、`#13`⑤ 的**用例→文件归属**，执行方无需回推即可定位 `TC-SPOT-21~23`（`SPOT.md`）/`TC-RISK-31`（`RISK.md`）；
- 4 条新用例**预期值确定**（数值/计数/枚举），可直接落 JUnit 断言；
- `§16.2` 约束↔用例、`§16.3` 子账户隔离落点、`§16.6` 假 DAO 档**均有文件归属**；`§16.6` 引用的 **4 个方法名实测存在**（`M1Dm8EquivalentIntegrationTest.java:198/213/228/256`）；`§16` 内 **5 条路径引用 `[ -f ]` EXISTS、MISS=0**（`D1` 已修实证）。

**仍不足 / 会误导执行方**：
- `INDEX.md:860`（`Q4` 行）称 `PRODUCT/SPOT` 仍缺 `里程碑` 行——**与实况相反**，`T-024` 若照抄会**重开已闭合项**（`TE-T022-001`）；
- `TC-SPOT-22` 的 `code=10015` 系**推断**（无文档显式绑定「缺分量→10015」）⇒ 执行方按此断言**有假失败风险**（`O-1`）；
- `Q1`：外键→孤儿 / `CHECK`→取值域**无 `TC-` 级用例**（仅 `T-023` DB 层承载）⇒ 若 `T-024` 需 `TC-` 级追溯，须另行补登（产出方已如实披露）。

## 9. (G) 逐条复核产出方的缺口判定

- `Q2`/`Q3`：**判对**（两个 `#` 缺口确由 4 条新 TC 覆盖，语义与原文逐条吻合）✔。
- `Q1`：**判对**（`TC-` 级确无承载；`T-023` 方法确存在）✔；仅证据列措辞陈旧（`O-4`）。
- `Q4`：**产出方判错/未更新** —— 底层行已补齐（11/11），但 `§16.8` 行文仍判「缺 / 0 命中 / 未授权前不改」⇒ 该行**为假**（`TE-T022-001`）。
- **编排者转述勘误**：派发称「`Q4` **声称已补**」——**与制品不符**：`INDEX.md:860` 未称已补，仍称缺口开放。**「已补」这一事实**成立（行确已加），但**载于台账**（`BOARD.md:201`/`TASKS.md:606`），**未回写 `INDEX.md` §16.8**。⇒ 拟：**编排者转述把「台账所称」当成「制品所称」**，非产出方在制品内判「已闭环」。

## 10. 缺陷与观察项

**`TE-T022-001`（级别：低；非阻断）** —— `INDEX.md` §16.8 `Q4` 行未随本轮补件更新，**证据列命题为假且与本 commit 自身所加的行自相矛盾**。
- 位置：`docs/TEST_PLAN/INDEX.md:860`（`Q4` 行）。
- 复现：`grep -n '里程碑' docs/TEST_PLAN/PRODUCT.md docs/TEST_PLAN/SPOT.md` ⇒ 实测 **2 命中**（`PRODUCT.md:23`、`SPOT.md:22`），**非行文所写「0 命中」**；`grep -c '^| 里程碑 |'` 各 1、全 11 模块齐。
- 期望依据：`§16.8` 自定性为「逐条点名、不得概括为『已覆盖』」的**缺口清单**，其证据列须为真；台账 `BOARD.md:201`/`TASKS.md:606` 亦自称「11/11 齐」。
- 影响：读者据 §16.8 会**误判 `Q4` 仍开放**；`T-024` 若照抄将重开已闭合项。**不阻断** `T-023`/`T-024` 执行。

**观察项**：
- `O-1`（映射精度）：`TC-SPOT-22` 断言 `code=10015` 无文档显式绑定（`ARCHITECTURE.md:1318` 触发条件列为价缺口侧；`10015` 全仓绑定点 `FR-SPOT-13`/`FR-PLAN-7` 皆价侧）。建议补限定语或请裁定；否则执行方按 10015 断言存在**假失败**风险。
- `O-2`（守卫强度事实）：`docs/TEST_PLAN/**` **无自动守卫**（变异 A/B 无检出），计数/自洽全人工承重（§7）。
- `O-3`（计数口径）：`grep -rn '\b307\b' docs/` = **4 命中，非 0**（皆变更留痕/无关行区间）；`docs/TEST_PLAN/**` 内 0。
- `O-4`（证据列措辞）：`Q1` 行「命中仅 `INDEX.md`（§1.2）」实测有 **5 处**（§1.2 + §16.2×3 + §16.8），实质结论（无 `TC-` 级）仍成立。

## 11. 证据章落笔前后

- **落笔前**：`docs/TEST_RESULT.md` = `57f9ff5d5701aceea09b79eb793bb8c2` / **5979 行**（3×6s 静默恒定）。
- **本追加章**：`L5980` 起。
- **落笔后**：以实时 `md5sum` 为准；**纯追加证明**：`head -5979 docs/TEST_RESULT.md | md5sum` = `57f9ff5d5701aceea09b79eb793bb8c2`（=落笔前全档 md5）。
- **只读声明**：本席**未改** `docs/TEST_PLAN/**`、`java/**`、`web/**`、`docs/TASKS.md`、`docs/BOARD.md`、`docs/REQUIREMENTS/**`；唯一写入 = 本追加章。
- **副本**：`/root/t022_probe/`（变异用，已弃；**未落 `/tmp`**）。
