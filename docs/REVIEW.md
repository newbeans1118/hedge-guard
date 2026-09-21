# 代码与文档审查报告

> 负责人：代码审查员 ｜ 阶段：S7

- **审查人**：`cr-s7`（code-reviewer）
- **审查时间**：2026-09-21 00:00–00:17 +0800
- **审查版本**：仓库**无 git**（`git rev-parse HEAD` → `fatal: not a git repository`）。以**文件 md5 + 取样时刻**锚定。
- **审查范围**：`java/**`、`cpp/**`、`web/**`、`tools/arch-check/**`、`docs/ARCHITECTURE.md`、`docs/DATA_MODEL.md`、`docs/TASKS.md`、`docs/BOARD.md`、`docs/REQUIREMENTS/**`、`docs/TEST_PLAN/**`、`docs/TEST_RESULT.md`、`java/CONTRACTS.md`

> ⚠️ **本次审查期间，仓库处于多席位并发写入状态**（详见 §6 时间序列）。`docs/ARCHITECTURE.md`（技术负责人 v1.5.6 订正）、`docs/TASKS.md`/`docs/BOARD.md`（项目经理回写）、`java/.../handler/plan/**`（`GET /plan` 收口）、`tools/arch-check/scan_java_sql.sh`（`E5` 判据两次改版）**在审查窗口内均被改动**。
> **本报告结论一律标注取样时刻并给出可复核命令；对变动中的制品，结论仅对该时刻成立，不构成对最终版的断言**（同 `BOARD.md` `R-15`/`R-16` 纪律）。

---

## 1. 审查结论

**有条件通过** —— **五条架构红线（组件边界 / C++11 / 敏感字段零落盘 / 分层 / 单一写库入口）在最终取样点（00:16:02）全部成立且经独立实测**；Java 组件可构建、`Tests run: 290, Failures: 0, Errors: 0, Skipped: 0`（`BUILD SUCCESS`），C++ `ctest` 3/3 通过。**但存在 1 处严重级未闭环项（CR-002：计划提交的阻断式合规预校验服务端未落实）与 3 处文档时效性/口径问题**，须按 §3「处置要求」闭环后方可判 S7 终态。

**明确不属于本报告结论范围**：已知项（§5）不重复开单；`E5` 判据在最终取样点**已为绿**。

> **过程留痕（重要）**：审查窗口内，`plan` 模块的 `GET /plan` 收口（00:09:28 落 `PlanRoutes` 路由）**一度使 CI 转红**——00:10:28 实测 `BootstrapAssemblyTest:136 expected: <47> but was: <48>`、`Tests run: 284, Failures: 1`、`BUILD FAILURE`；该断言已于 00:12:02 同步为 `48`，00:16:02 复跑转绿（`290/0/0/0`）。**即「实现改动未同步冻结口径」曾在窗口内造成真实红灯**，详见 CR-001。

---

## 2. 发现汇总

| 编号 | 级别 | 类型 | 位置 | 问题摘要 | 状态 |
|------|------|------|------|---------|------|
| **CR-001** | 一般 | 一致性/口径 | `docs/BOARD.md:258`、`docs/TEST_RESULT.md:26,47` vs 实现；`BootstrapAssemblyTest.java:136` | **路由口径漂移**：`FR-PLAN-4` 收口新增 `GET /plan` ⇒ `(方法,路径)` 对 **47 → 48**（路径数不变 37）；文档仍记 47；断言**一度**未同步致 CI 红（已于 00:12 修复） | 待同步文档 |
| **CR-002** | **严重** | 契约/安全 | `java/.../service/PlanService.java:527-556`；`java/.../web/handler/plan/PlanActionHandler.java:78-81` | **`FR-PLAN-10` / `R-PLAN-3`「提交前合规预校验（阻断式）」服务端未强制**：仅原样持久化客户端字串，重算/阻断 6 项校验均缺 | 待裁定/待修复 |
| **CR-003** | 一般 | 文档/追溯 | `docs/TASKS.md`、`docs/BOARD.md` | 任务状态与实现不符：M1 服务已实现并有测试覆盖，看板仍标 `TODO` | 待修复（收尾中） |
| **CR-004** | 一般 | 文档（时效） | `docs/TEST_PLAN/INDEX.md:583-584` | `E5` 判据现状描述过时（记「实测 5 处误报」，取样时刻实为 **0 命中/退出 0**） | 待同步 |
| **CR-005** | 建议 | 文档 | `docs/TEST_RESULT.md:23,72`；`docs/BOARD.md:258` | 测试基线陈旧（记 283，取样时刻实为 **290**） | 建议修复 |
| **CR-006** | 建议 | 文档（可执行性） | `docs/ARCHITECTURE.md` §3.0.2（`:317,319,320,322`） | 引用 `E1`/`E3`/`E4`/`E6` 脚本落点，脚本**尚不存在**（`T-090`~`T-093` 为 `TODO`） | 建议修复 |

> 级别：阻塞 / 严重 / 一般 / 建议　｜　类型：正确性 / 契约 / 安全 / 性能 / 可维护性 / 文档

---

## 3. 详细问题列表

### CR-001 路由口径漂移：`FR-PLAN-4` 收口使 `(方法,路径)` 对 47 → 48，文档与断言未同步

- **级别**：一般（**过程影响为阻塞级**：曾致 CI 红，见下）
- **类型**：一致性 / 口径
- **位置**：`java/.../web/handler/plan/PlanRoutes.java`（收口前后 md5 `0a33c947…`→`90d841e6…`）；`java/.../web/handler/plan/PlanHandler.java:61,118`（新增 `list()`）；`java/src/test/.../BootstrapAssemblyTest.java:136`；`docs/BOARD.md:258`、`docs/TEST_RESULT.md:26,47`
- **问题描述**：审查开始时（00:05）`FR-PLAN-4`（P0「查询套保计划列表」）**无实现路由**（`GET /plan` 未注册，仅 `POST /plan` 与 `GET /plan/{id}`），前端已投 `Q-FE-001`（`web/js/pages/plan.js:10-12`）。**审查窗口内（00:08–00:09）** 后端落 `PlanRoutes.java:51` `router.register("GET", PlanHandler.PATH, …)` 与 `PlanHandler.list()` → **该功能缺口已闭合**。但由此：
  - **路由口径由 47 → 48**（新增 1 个 `(方法,路径)` 对；`/plan` 路径此前已存在，**路径数仍 37**）。而 `BOARD.md:258`、`TEST_RESULT.md:26/47` 仍以「**47 个 `(方法,路径)` 对 / 37 个路径**」为登记值（派发说明亦以 47 为定案）。
  - **冻结断言未同步**：00:10:28 实测 `BootstrapAssemblyTest.configuredDatabaseRegistersAllBusinessRoutes:136` → `expected: <47> but was: <48>`，`Tests run: 284, Failures: 1`，**BUILD FAILURE**（即**删除/短路 `registerBusinessRoutes` 之外的任何路由增减，都会按设计使该用例变红**——这正是该用例的意图，但也暴露了「路由集合变更必须同步断言+文档」的纪律未被自动约束）。该断言已于 00:12:02 改为 `48`；00:16:02 复跑 `290/0/0/0` 绿。
- **失效场景**：① 任何未同步 48 口径的制品（`BOARD`/`TEST_RESULT`/`TEST_PLAN`/CI 脚本）引用 47 即与实现不符，后续「路由一致性」复核会误判；② 若后续再增删路由而不同步 `BootstrapAssemblyTest`，CI 立即转红（本轮已真实发生一次）。
- **证据**：
```
$ grep -nE 'router.register' PlanRoutes.java        # 收口后
51: router.register("GET", PlanHandler.PATH, new PlanHandler(planService));   ← 新增
52: router.register("POST", PlanHandler.PATH, ...)
...
$ # 00:10:28 隔离副本 mvn -B -o test
[ERROR] BootstrapAssemblyTest.configuredDatabaseRegistersAllBusinessRoutes:136
        业务路由必须全部注册：45（...）+ ping 2 ==> expected: <47> but was: <48>
[ERROR] Tests run: 284, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
$ # 00:16:02 复跑（断言已改 48）
[INFO] Tests run: 290, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
- **处置要求**：**定案新口径并全量传导**——建议口径 = **48 个 `(方法,路径)` 对 / 37 个路径**（`PLAN` 由 16→17 对）；同步 `BOARD.md`、`TEST_RESULT.md`、`TEST_PLAN/INDEX.md`（含 §2.3 口径段）与 `BootstrapAssemblyTest`。**若判定 `GET /plan` 不属本期**，则相反须撤路由并走 `CH-xxx`——**不得停留「实现 48 / 文档 47」的分裂态**。
- **对应需求/契约**：`FR-PLAN-4`、`ApiRoutes.byModule()`、`TEST_RESULT.md` `EXP-1`/`V-5`、`TEST_PLAN/INDEX.md:136`。

---

### CR-002 `FR-PLAN-10` / `R-PLAN-3`「提交前合规预校验（阻断式）」服务端未强制

- **级别**：严重
- **类型**：契约 / 安全（合规门禁可绕过）
- **位置**：`java/src/main/java/com/hedgeguard/service/PlanService.java:527-556`（`submitForApproval`）；`java/src/main/java/com/hedgeguard/web/handler/plan/PlanActionHandler.java:78-81`
- **问题描述**：需求 `FR-PLAN-10`（`docs/REQUIREMENTS/PLAN.md:240-248`）与规则 `R-PLAN-3`（`:414`）明定：**提交审批前必须逐项校验并通过 ①操作授权 ②品种白名单 ③规模红线 ④期限红线 ⑤数量≤未覆盖量 ⑥书面文件三要素；「任一不通过即阻断提交」并输出规则编号+当前值/阈值**。实现侧：
  - `PlanService.submitForApproval(planId, precheckJson, operatorId)` 把 `precheckJson` 当**不透明客户端字串**，**仅校验非空**（`:543`）后**原样持久化**为 `plan_approval.precheck_result`（`:555`）；**服务端未重算 ①②③④⑤⑥ 任何一项**，唯一服务端门禁是书面文件存在性（`:527-542`，对应 `R-PLAN-8`）。
  - `PlanActionHandler` 直接透传请求体（`:79-80`：`request.precheckResult`）。
  - **无人产出该字段**：`java/CONTRACTS.md` **未定义** `precheckResult`（`grep -nE 'precheck|submit' java/CONTRACTS.md` 仅命中 `TxTemplate` 注释）；前端 `web/js/pages/plan.js` **不产出**（`grep -nE 'precheck|submit|红线|校验' web/js/pages/plan.js` → **0 命中**）。
- **失效场景**（其一必发生）：
  1. **正常链路不可用**：前端不产出 `precheckResult` → 提交被 `:543` 判 `PARAM_INVALID` 拒绝 → **`FR-PLAN-10` 正常路径走不通**；或
  2. **门禁可绕过**：任意 REST 调用方伪造一段 `precheckResult` 文本（内容不受校验）→ `:550` 直接置 `PENDING_APPROVAL` → **规模红线 / 期限红线 / 授权 / 岗位分离（发起人不得具备交易岗）等 6 项阻断式校验形同虚设**，与 8 号文「下单前阻断违规操作」的合规要求相悖。
- **证据**：
```
$ sed -n '543,556p' PlanService.java
if (isBlank(precheckJson)) { throw ... "提交须携带合规预校验结果..." }   ← 仅「非空」
... plan.status = "PENDING_APPROVAL"; ...
approval(plan, plan.applicantId, plan.applicantId, "SUBMIT", null, null, precheckJson);  ← 原样落库
```
- **处置要求**：**须技术负责人裁定归属**——(a) 若属本期，服务端须**真正执行并阻断** `R-PLAN-3` 六项（不得依赖客户端字串）；(b) 若判定「③④由 M3 `T-063`/`T-066` 承接、M1 仅留痕」，须在 `ARCHITECTURE.md` / `FR-PLAN-10` 显式注明**过渡口径**并同步 `CONTRACTS.md` 定义请求字段，消除「契约未定义 / 客户端不产出 / 服务端判必填」的**三方不一致**。
- **对应需求/契约**：`FR-PLAN-10`、`R-PLAN-3`、`R-PLAN-2`（岗位分离）、`R-PLAN-8`；成功标准 12/13/14/15。
- **边界说明**：本项**不影响**「事前风控在 C++」结论（§4.1）——争议点是**计划审批业务流程**的服务端强制力，非高频下单路径。

---

### CR-003 任务看板状态与实现不符

- **级别**：一般
- **类型**：文档 / 追溯链（任务→实现）
- **位置**：`docs/TASKS.md`、`docs/BOARD.md`
- **问题描述**：`BOARD.md` §2 将 `T-008`~`T-016` 标为 `TODO`、`IN_PROGRESS` 区「当前无进行中任务」；但 `VarietyService`(47KB)、`SpotService`(43KB)、`PlanService`(57KB)、`AuthService`(27KB)、`ConfigService`(32KB)、`ParamDispatchServiceImpl`(32KB) **均已实现且被 290 用例覆盖通过**；`TASKS.md` 实测状态分布 `DONE 10 / TODO 82`（94 行），与实现面严重背离。
- **失效场景**：以看板为门禁/交接依据者会**误判 M1 未开工**，重复派工或漏验已完成项；追溯链「任务」节点状态失真。
- **证据**：`grep -cE '^\| T-[0-9]+ \|' docs/TASKS.md` → 94；`ls java/src/main/java/com/hedgeguard/service/` 见上述服务。
- **处置要求**：项目经理回写看板至与实现一致（**已在收尾中**，本席登记为**待确认闭环**）。
- **对应契约**：`TASKS.md` §0/§2、`BOARD.md` §1/§2。

---

### CR-004 `TEST_PLAN/INDEX.md` 对 `E5` 判据现状的描述已过时

- **级别**：一般
- **类型**：文档（时效性/一致性）
- **位置**：`docs/TEST_PLAN/INDEX.md:583-584`
- **问题描述**：该处记「`E5`…**运行期实测 5 处**…**待裁定**」。但 `tools/arch-check/scan_java_sql.sh` 已 `v1.5.6` 改版（`ARCHITECTURE.md` §3.0.2 变更记录 `:2605`：剔 Java 注释 + ②拆 `②a/②a2/②b/②c`），**最终取样点实测 0 命中 / 退出码 0**——5 处误报已由判据侧消化，**非「待裁定」状态**。
- **失效场景**：后来者据该段继续「裁定误报」浪费工时；或据「不得引用 E5 为已通过」而弃用已转绿的判据。
- **证据**：`bash tools/arch-check/scan_java_sql.sh; echo $?` → `E5 通过：dao 之外未发现 SQL（退出码 0）` / `0`。
- **处置要求**：测试经理更新为当前口径（`E5` 绿 / 0 命中）并保留「以实时 `md5sum` 为准」。
- **对应契约**：`NFR-MAINT-3`、`ARCHITECTURE.md` §3.0.2 `E5`。

---

### CR-005 测试基线数字陈旧（283 vs 290）

- **级别**：建议
- **类型**：文档
- **位置**：`docs/TEST_RESULT.md:23,72`、`docs/BOARD.md:258`（均记 `Tests run: 283`）
- **问题描述**：`TEST_RESULT.md` 记全量基线 283（23:51:40 取样）；最终取样点（00:16:02，副本 00:13:13）实测 **290**（GAP-1 用例 + `plan` 列表用例等增量）。
- **失效场景**：以 283 为回归基线者会把人月新增用例当异常增量。
- **证据**：见 §6 第 3/4 行（284→290 的实测）。
- **处置要求**：测试席位同步基线数字与取样时刻。
- **对应契约**：`NFR-TEST-2`。

---

### CR-006 `ARCHITECTURE.md` §3.0.2 引用尚不存在的 `E1`/`E3`/`E4`/`E6` 脚本落点

- **级别**：建议
- **类型**：文档（可执行性）
- **位置**：`docs/ARCHITECTURE.md` §3.0.2（`:317` E1、`:319` E3、`:320` E4、`:322` E6）
- **问题描述**：§3.0.2 列 `tools/arch-check/{check_no_db_links.sh(E1), scan_cpp11.sh(E3), scan_lock_free.sh(E4), check_frontend.sh(E6)}` 为判据落点，但该目录**仅有** `scan_cpp_sql.sh`(E2)、`scan_java_sql.sh`(E5)、`strip_java_comments.awk`；上述 4 脚本**不存在**（`T-090`~`T-093` 仍 `TODO`）。
- **失效场景**：读者据 §3.0.2 认为 `E1`/`E3`/`E4`/`E6` 可 CI 化执行，实际调用 `No such file`。
- **证据**：`for s in check_no_db_links.sh scan_cpp11.sh scan_lock_free.sh check_frontend.sh; do [ -f tools/arch-check/$s ] && echo EXISTS || echo MISSING; done` → `MISSING ×4`。
- **处置要求**：脚本落地前，§3.0.2 对应行加「**落点待建（`T-090`~`T-093`）**」标注。
- **对应契约**：`ARCHITECTURE.md` §3.0.2、`TASKS.md` `T-090`~`T-093`。

---

## 4. 逐维度审查结论

> 凡「零命中 / 全部一致」类否定性结论，均列**命令 + 口径 + 排除项**；`E2`/`E5` 判据脚本已实际运行（§6）。

### 4.1 组件边界（最高优先）——✅ **通过**

| 子项 | 结论 | 证据（命令 / 命中） |
|------|------|--------------------|
| `cpp/` 无任何数据库访问 | **通过** | `bash tools/arch-check/scan_cpp_sql.sh` → **2 文件 / 0 命中 / 退出码 0**（口径 `cpp/**/*.{cpp,cxx,cc,c,hpp,h,hpp,hh}`，排除 `build*`/`_deps`/`third_party`）。**独立复核**：`cpp/` 非构建源码仅 `ctp_include_probe.cpp`、`hgcp_proto_smoke_main.cpp`（+`.in`），逐一目视**无** JDBC/ODBC/SQL/连接串/驱动。**已知边界**：C++ 业务模块（`cpp/src/<模块>`，`T-031` 起）**尚未开工**，故此结论**只对现有源码成立**（见 §7）。 |
| 业务逻辑未混入 C++ | **通过（现阶段）** | `grep -rnEi '会计\|结算\|报表\|现货\|套保\|settle\|accounting\|spot\|hedge\|invoice\|ledger' cpp`（排除 `build*`）→ **0 命中**。 |
| 事前风控未搬到 Java | **通过** | `TradingBridge` 显式声明「**不提供 `preCheck`——事前风控只在 C++ 组件内**」（`bridge/TradingBridge.java:10-11`）；`risk_intraday` 包纪律「处置只提示不自动下单；限制开仓经 `CMD_SET_TRADING_SUSPEND` 且 `scope=OPEN_ONLY`（**平仓放行**）」（`risk_intraday/package-info.java:9-12`）。Java **无**盘中实时限额阻断判定；`ParamDispatchServiceImpl` 仅做参数编排/下发。**注**：`PlanService` 的计划审批预校验属**业务流程**门禁（其缺口见 CR-002），不计入高频事前风控。 |

### 4.2 C++11 严格合规 ——✅ **通过**

| 子项 | 结论 | 证据 |
|------|------|------|
| 编译标准实际生效 | **通过** | 独立配置 + 构建（`cmake -S cpp -B /tmp/... -DCMAKE_CXX_STANDARD=11 && cmake --build`），`compile_commands.json` 实测源文件编译行含 `-std=c++11 -Wall -Wextra -Werror`，**构建退出码 0**。 |
| ctest 通过 | **通过** | `ctest` → `rsk01.cxx11_standard_in_effect / empty_message_roundtrip / fields_message_roundtrip` **3/3 Passed**。（`ctp_smoke` 因本机无 `CTP_SDK_ROOT` 未构建，属既有条件分支，非失败。） |
| 禁用 C++14/17 特性 | **通过** | `grep -rnEi 'std::make_unique\|std::optional\|std::string_view\|if constexpr\|std::variant\|std::any\|std::filesystem\|auto \[\|std::shared_mutex\|std::byte' cpp`（排除 `build*`）→ **0 命中**；泛型 lambda 形参启发式 → 0 命中。**口径**：仅覆盖现有 2 源文件 + `.in`。 |
| 运行期自证 | **通过** | 两源文件编译期 `#error` 守卫 + ctest 运行期断言 `__cplusplus==201103L`。 |

### 4.3 敏感字段零落盘 / 零日志（`NFR-SEC-1`，附录 B 红线 8）——✅ **通过（含 1 处「无法实测」披露）**

| 子项 | 结论 | 证据 |
|------|------|------|
| 属性文件拒绝敏感键 | **通过** | `Config.rejectSecrets`（`Config.java:120-131`）对键名含 `password/authcode/auth_code/token/secret` 的属性项抛异常；**刻意不提供** `hg.db.password` 属性键。 |
| 口令仅经环境变量、`char[]` 持有 | **通过** | `Config.databasePassword()→secret(ENV_DB_PASSWORD)`（`:175-178,236-238`，只读环境变量返回 `char[]`）；`JdbcConnectionFactory` 持 `char[]` 副本 + `clearPassword()`（`:43,73-77`）。 |
| 口令不入日志/实体 | **通过** | `java/src/main` 全仓：`grep -rnE '(info\|warning\|severe\|fine\|log\|print)\s*\(.*(password\|pwd\|token\|authcode\|secret)'` → **0 命中**；`dao/entity/` **无任何实体含口令/AuthCode 字段**（`CtpFrontConfig` 一类**声明其无敏感字段**）；实体**无 `toString()` 覆写** → 无 `toString` 泄露路径。 |
| 仓库无口令类文件 | **通过** | `find . -name '*.properties'` → **0**；`grep -rniE '(password\|pwd\|authcode)\s*=\s*"[^"]' java/src/main cpp web` → 仅常量名与 `mustChangePassword="Y"`（标志非口令）。 |
| `char[]` 及时清零 | ⚠️ **无法实测（披露）** | 与 `TEST_RESULT.md` EXP-3 同因：测试进程无法运行期注入 `HG_DB_PASSWORD`，本席**未获实测证据**，属静态审阅。**不作否定性结论。** |

> 关键文件已逐行阅读：`Config.java`、`SensitiveLog.java`（`MaskingFilter` 脱敏）、`JdbcConnectionFactory.java`、`PasswordHasher.java`（PBKDF2WithHmacSHA256 ≥120000；明文不回显；`PBEKeySpec.clearPassword()`；`MessageDigest.isEqual` 防时序侧信道）。

### 4.4 分层纪律（`service` 不得出现 SQL）——✅ **通过**

| 子项 | 结论 | 证据 |
|------|------|------|
| `E5` 判据（dao 之外无 SQL） | **通过（取样时刻）** | `bash tools/arch-check/scan_java_sql.sh` → **108 文件 / 0 命中 / 退出码 0**。 |
| 独立复算（不信脚本） | **通过** | 自写正则 `grep -rnEi '\b(select…\|insert into\|update…set\|delete from\|create(table\|index\|view)\|drop…\|alter table)\b' java/src/main/java --include=*.java \| grep -vE '/dao/'` → **0 命中**；`service/**`、`web/**` 各自 SQL **0 命中**；`DriverManager\|Class.forName\|java.sql.Connection\|getConnection\|jdbc:` 在 `service/**` → **0 命中**。 |
| 前端零构建链 / 无直连（`E6` 意图） | **通过** | `find web -name package.json -o -name node_modules -o -name '*.lock' -o -name 'vite.config*' -o -name 'webpack*'` → **0**；`grep -rniE 'jdbc\|dsn\|0\.0\.0\.0' web/` → 0（`password` 命中均为登录表单项/API 调用）；`grep -rnoE '127\.0\.0\.1:[0-9]+' web/` → 0。 |
| 依赖纪律（禁引名单） | **通过** | `java/pom.xml` 仅 `protobuf-java`(compile)/`gson`(compile)/`junit-jupiter`(test)/`DmJdbcDriver18`(system, 条件 profile)；`grep -niE 'hikari\|slf4j\|logback\|bcrypt\|mockito'` → 仅注释中的**禁引声明**，**无实际依赖**。 |

### 4.5 追溯链完整性 ——⚠️ **基本完整；1 处已闭合（FR-PLAN-4，见 CR-001）、1 处未落实（FR-PLAN-10，见 CR-002）**

方法：逐 FR `grep` 全部制品。**关键提示**：`TASKS.md` 采**区间记法**（如 `FR-SPOT-1~18`），单条 FR 字面不命中 ≠ 断链，须按区间判覆盖。

| 需求 | 架构承载 | 任务 | 用例 | 实现 | 判定 |
|------|---------|------|------|------|------|
| `FR-PRODUCT-1` | §3.8/§10.1（`:460,:2159`）✅ | `T-005/012/018`（`1~14`）✅ | `TC-PRODUCT-1` ✅ | `VarietyService` ✅ | 完整 |
| `FR-SPOT-14` | §5.2/§10.1（`:2186`）✅ | `T-014`（`13~18`）✅ | `TC-SPOT-14/15/20` ✅ | `SpotService` ✅ | 完整 |
| `FR-PLAN-7` | §3.8/§4.3.1/§10.1（`:2197`）✅ | `T-015`（`6/7/8`）✅ | `TC-PLAN-7` ✅ | `PlanService` ✅ | 完整 |
| `FR-AUTH-14` | §8.2/§10.1（`:2322`）✅ | `T-010`（`1~18`）✅ | `TC-AUTH-14/15` ✅ | `AuthService` ✅ | 完整 |
| `FR-CONFIG-2` | §8.2/§10.1（`:2328`）✅ | `T-009`（`1~15`）✅ | `TC-CONFIG-2/3` ✅ | `ConfigService` ✅ | 完整 |
| `FR-PLAN-4` | §10.1 ✅ | `T-016` ✅ | `TC-PLAN-4` ✅ | `GET /plan` + `PlanHandler.list`（**窗口内补**）✅ | **四列齐备；口径未同步 → CR-001** |
| `FR-PLAN-10` | §10.1 ✅ | `T-016` ✅ | `TC-PLAN-10/21` ✅ | ⚠️ **存在但未落实 `R-PLAN-3` 阻断语义** | **断链 → CR-002** |

> **数量口径核对**：`ARCHITECTURE.md` §10 覆盖 **233（183 FR + 50 NFR）**；`grep -oE 'FR-[A-Z]+-[0-9]+' docs/ARCHITECTURE.md | sort -u | wc -l` = **233**（该正则兼计 `NFR-*`），`docs/REQUIREMENTS/**` 去重同为 **233** → **架构侧覆盖无缺口**。`TEST_PLAN/INDEX.md` §6.1 声称 FR 183/183、NFR 50/50——本席**未逐条复算 233 行**（时间盒），**未作否定性结论**。

---

## 5. 已知项（**已在派发说明或落盘制品中登记，不重复开单**）

| 事项 | 状态（最终取样点） | 处置 |
|------|------------------|------|
| `E5` 判据「5 处误报」 | **已由判据侧消化**：取样时刻 **0 命中 / 退出 0**（`v1.5.6` 剔注释 + ②分域） | 引用；文档同步见 CR-004 |
| `GAP-1`：`Bootstrap` 正分支无仓库内直接断言 | **已补**：`BootstrapAssemblyTest#configuredDatabaseRegistersAllBusinessRoutes` 以假驱动直接断言 `routeCount`；实测通过 | 视为已闭环，**待责任方 + 门禁复核确认** |
| `E5` 判据脚本与假驱动夹具交互 | 窗口内脚本两次改版：旧版（裸 `drivermanager`）对 `FakeJdbcDriver` 报 2 命中；新版（锚 `getConnection(` + `registerDriver` 仅生产）→ 0 命中 | 引用；§6/§7 留痕 |
| 路由口径 | **原登记 47 对 / 37 路径 → 现实现 48 对 / 37 路径**（窗口内因 FR-PLAN-4 收口变化） | **口径漂移 → CR-001** |
| `ARCHITECTURE.md` `TB-SETTLE-14` 应 `TB-SETTLE-15` | **已闭环**：`grep -n 'TB-SETTLE-14' docs/ARCHITECTURE.md` → **0 命中**（仅 v1.5.6 变更叙述内历史提及） | 采信，复核一致 |
| `java/CONTRACTS.md` §3.2.1 同名两节 | **已消歧**（`05c81612…` / 728 行，未升版） | 采信，md5 一致 |

---

## 6. 已执行的验证

> 全部命令在**本机实际执行**；时间戳 +0800。Java 侧用**隔离副本**（避免扰动在写制品）。**因仓库窗口内并发写入**，同一命令多次执行结果不同，**逐次留证**。

### 6.1 时间序列（同一命令多次执行，证明并发写入影响）

| 时刻 | 命令 | 结果 |
|------|------|------|
| 00:04:09 | `mvn -B -o clean test`（副本 `cp -a` @~23:59） | `Tests run: 284, Failures: 0, Errors: 0, Skipped: 0` / **BUILD SUCCESS** |
| 00:10:28 | `mvn -B -o test`（副本 @00:10:15） | `Tests run: 284, Failures: 1, Errors: 0` / **BUILD FAILURE**：`BootstrapAssemblyTest:136 expected: <47> but was: <48>` |
| 00:16:02 | `mvn -B -o test`（副本 @00:13:13） | `Tests run: 290, Failures: 0, Errors: 0, Skipped: 0` / **BUILD SUCCESS** |

> 三次执行差异的唯一来源 = 窗口内 `PlanRoutes`/`PlanHandler`/`BootstrapAssemblyTest` 的被并发改动。

### 6.2 验证清单

| # | 验证项 | 命令 | 结果 |
|---|--------|------|------|
| 1 | C++ 可构建 | `cmake -S cpp -B /tmp/cr_s7_cppbuild -DCMAKE_CXX_STANDARD=11 && cmake --build …` | **EXIT=0**；`[100%] Built target hgcp_proto_smoke`；编译行含 `-std=c++11 -Wall -Wextra -Werror` |
| 2 | C++ 测试 | `ctest --test-dir /tmp/cr_s7_cppbuild --output-on-failure` | **EXIT=0**；`100% tests passed, 0 tests failed out of 3` |
| 3 | Java 构建+测试 | `mvn -B -o clean test` / `mvn -B -o test`（隔离副本） | 见 §6.1（末次 `290/0/0/0` / SUCCESS） |
| 4 | 架构判据 `E2` | `bash tools/arch-check/scan_cpp_sql.sh` | 2 文件 / **0 命中 / 退出 0** |
| 5 | 架构判据 `E5` | `bash tools/arch-check/scan_java_sql.sh`（脚本 `660f358d…`） | 108 文件 / **0 命中 / 退出 0** |
| 6 | 独立复算 service/web | `grep -rnEi '…SQL 语句形态…' java/src/main/java/com/hedgeguard/{service,web}` 等 | `service/**`、`web/**` **0 命中**；`service/**` 连接创建关键字 **0 命中** |
| 7 | C++ 禁用特性（E3 意图手工） | `grep -rnEi 'std::make_unique\|…' cpp`（排除 `build*`） | **0 命中** |
| 8 | C++ 业务语汇 | `grep -rnEi '会计\|结算\|现货\|套保\|settle\|hedge\|…' cpp` | **0 命中** |
| 9 | 口令入日志 | `grep -rnE '(info\|…)\s*\(.*(password\|token\|…)' java/src/main` | **0 命中** |
| 10 | 前端零构建链 | `find web -name package.json -o -name node_modules …`；`grep -rniE 'jdbc\|dsn\|0\.0\.0\.0' web/` | 均 **0 命中** |
| 11 | 追溯链抽样 | 逐 FR `grep` 需求/架构/任务/用例 | 见 §4.5 |
| 12 | 已知项复核 | `grep -n 'TB-SETTLE-14' docs/ARCHITECTURE.md`；`md5sum java/CONTRACTS.md` | TB-SETTLE-14 **0 命中**；CONTRACTS `05c81612…` 一致 |

**未执行（说明原因）**：

| 项 | 原因 |
|----|------|
| DM8 实机建表 / JDBC 端到端（`NFR-DEPLOY-5`） | 环境无 DM8 实例与 DM 驱动（`R-5`/`CONTRACTS.md` `D-1`）——**证据不足，无法判定** |
| CTP 实机 / HGCP 双组件联调 / `T1`–`T11` 协议专项 | 无 CTP 柜台；C++ 业务模块（`T-031` 起）未开工——**证据不足** |
| `char[]` 口令清零运行期证据 | 测试进程无法运行期注入 `HG_DB_PASSWORD`（同 EXP-3）——**证据不足** |
| §6.1 追溯矩阵 233 行逐条复算 | 时间盒；已抽样 5 条 + 数量口径核对——**未逐条**，未作否定性结论 |

---

## 7. 遗留风险

| 风险 | 无法确认的原因 | 建议 |
|------|---------------|------|
| **C++ 零 DB / C++11 结论的覆盖面** | 现有 `cpp/` 仅 2 探路源文件；M2 业务模块（`risk_engine`/`ctp_adapter`/`flow_store` 等）**尚未开工**（`T-031` 起 `TODO`） | M2 代码落地后**重跑 `E1`~`E4`**（`E1`/`E3`/`E4` 脚本待 `T-090`~`T-093` 落地），并入 `T-060` |
| **窗口内并发写入导致的可复现性下降** | `ARCHITECTURE.md`/`TASKS.md`/`BOARD.md`/`plan/**`/`scan_java_sql.sh` 取样期间均被改动；同一 `mvn test` 三次结果不同（283→284 绿 / 284 红 / 290 绿） | 终审前**重新 `md5sum` 并冻结**；本报告结论**仅对 §6.1 所列时刻成立**（同 `R-15`/`R-16`） |
| **「路由集合变更 → 断言/文档同步」无自动约束** | `BootstrapAssemblyTest` 的 47/48 断言与文档口径靠人工同步，本轮已因此真实转红一次 | 将路由数口径**单一事实源**指向 `ApiRoutes`，断言改「下界 + ⊆ 校验」或由 CI 自动重算，避免手写常量 |
| **`E5` 判据与测试夹具形态的耦合** | `FakeJdbcDriver`（GAP-1 夹具）与 `E5` 的 `②a/②a2` 分域是敏感耦合点，本轮已因它触发判据改版 | 新增 test 夹具（尤其涉 `DriverManager`/连接串）时**先确认 `E5` 分域仍成立**；口径写进 §3.0.2 脚注 |
| **`FR-PLAN-10` 合规门禁的最终口径** | 归属（本期实现 vs M3 承接）属技术负责人裁定域（CR-002） | 裁定后在 `ARCHITECTURE.md`/`FR-PLAN-10`/`CONTRACTS.md` **三处同步** |
| **`GAP-1` 闭环的形式确认** | 本席实测新用例通过，但「是否关闭」按纪律由责任方 + 门禁复核确认 | 由 `backend-engineer#java` + 门禁复核在 `TEST_RESULT.md`/`BOARD.md` 标注闭环 |

---

> **报告性质**：本项目**首次**对 Java/业务组件做独立代码审查。审查**只读**（未修改任何源码/文档；`docs/REVIEW.md` 为本席唯一产出）。
> **问题工单**：按派发说明 §6 文件边界，本席不写 `dev-team/**`；CR-001~CR-006 已完整登记于本报告，**建议由编排者据此派发**（责任方：CR-001 → `backend-engineer#java`、CR-002 → `#java`/`#java2` + `tech-lead` 裁定、CR-003 → 项目经理、CR-004/CR-005 → 测试经理、CR-006 → 技术负责人）。
