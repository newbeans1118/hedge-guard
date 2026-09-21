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
