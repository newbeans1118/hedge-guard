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

---

# M2 代码与架构合规审查（T-060，审查席 cr-t060，2026-10-13）

> **审查范围**：S7 首审（2026-09-21 时点，`60ea187`）之后交付的 M2 主体——C++ 六模块（含 T-041 recovery/recovery_hook/0x0401）、Java 后端 M2 服务面（fund/trade/settle/risk/audit + T-194 check-tasks）、前端 12 页面（含 T-071 audit.js / T-055 fund.js）。**只读审查，未改任何代码。**
> **冻结点快照**：HEAD = `a129a4a449a35e0b7defdcf0676e280c31ccb59f`（2026-10-13 00:15:22 +0800，"ledger: 第 238 轮"）；`git status --short` = 仅 `?? .verify/`（未跟踪审查夹具目录，非源码面）；`web/`、`docs/REVIEW.md` 工作树干净。
> **追加前基线**：`docs/REVIEW.md` = 277 行 / md5 `8eb53017e30f052f6f1c878e0cc16c31`（本节为其纯追加）。

## M2.1 审查结论

**通过**（M2 合规门禁口径）。E1~E6 全量实跑全部退出码 0（E7/E8 亦 0，一并报告）；C++ 无 DB 无 SQL、C++11 词法合规、敏感字段零落盘三项独立复核均成立；新发现问题仅 **低/建议级 3 项**（R2-001/002/006），无阻塞/严重项。已知 P0 缺口（层③功能权限门禁，T-118/T-119 在途）按纪律列「已知项」不重复开单，但作为 M2 收官遗留明示于 M2.6。

## M2.2 E1~E8 实跑结果表（2026-10-13，仓库根，`bash tools/arch-check/arch-check.sh` 聚合 + 逐脚本单跑）

| 判据 | 脚本 | 聚合 rc | 单跑 rc | 扫描量（实测） |
|------|------|---------|---------|----------------|
| E1 C++ 链接检查 | `check_no_db_links.sh` | 0 | 0 | **实扫非 SKIP**：ldd 2 个 binary（`cpp/build/src/hg-trading`、`cpp/build_stub/src/hg-trading`）+ **218 个 link.txt** |
| E2 C++ SQL 扫描 | `scan_cpp_sql.sh` | 0 | 0 | 142 个 C++ 源文件，0 命中 |
| E3 C++11 合规 | `scan_cpp11.sh` | 0 | 0 | 142 个文件；判据①编译参数通过 + ②禁用特性 0 命中 |
| E4 无锁/无同步 I/O | `scan_lock_free.sh` | 0 | 0 | **实扫非 SKIP**：`待扫描文件: 61 个`（三目录并集）；WARN 3 处（`new RiskSnapshot` 动态分配，属已披露未覆盖判据 C，信息性不判红） |
| E5 Java SQL 扫描 | `scan_java_sql.sh` | 0 | 0 | 333 个 Java 文件，0 命中 |
| E6 前端零构建链 | `check_frontend.sh` | 0 | 0 | `web/`，0 命中 |
| E7 HGCP schema | `scan_hgcp_schema.py` | 0 | 0（以 `python3` 直跑；见 R2-006） | 消息 40+10 / 字段 214+67 / 枚举 28 一致 |
| E8 CONTRACTS payload | `scan_contracts_payload.py` | 0 | 0（以 `python3` 直跑；见 R2-006） | 四指令双向差集空 |

> 聚合退出码 0；逐脚本 rc 均独立记录如上。**字面口径 E1~E6 全 0 ⇒ 验收 1 成立**；E7/E8 按 M2 期间新增判据一并报告（同为 0），是否扩入口径报**建议**（M2.5 R2-005）不自行改。

## M2.3 六项验收逐条判定

1. **E1~E6 全量通过**：✅（见 M2.2；聚合任一失败即整体失败——实测无一失败）。
2. **C++ 不链接 DB 客户端库、无 SQL**：✅。E1/E2 之外独立复核：① CMake `target_link_libraries` 全量 grep（21 处）——链接目标仅 `hg::*` 内部库 / `Threads::Threads` / `protobuf` / `hg_deps::protobuf`，无任何 DB 客户端；② `cpp/` 内 `mysql|postgres|sqlite|odbc|oci|libpq|jdbc|DmDriver` 全量 grep **0 命中**；③ SQL 语句形态 grep（SELECT…FROM / INSERT INTO）**0 命中**。
3. **C++11 严格合规**：✅。E3 全量 + 本席独立词法 grep：`std::optional` / `std::string_view` / `std::variant` / `make_unique` / `if constexpr` / 结构化绑定（`auto[…] =`）在 `cpp/src` + `cpp/proto_smoke` **0 命中**（含 T-041 新增 `recovery.cc` / `recovery_hook.cc` 覆盖面内）。
4. **敏感字段零落盘（结构 + grep + 跨页汇总）**：✅。结构面：`t_user` 唯一口令形态 = `password_hash`（`m1_config_auth.sql:335`，明文列不存在）；`PasswordHasher` = PBKDF2WithHmacSHA256、盐 16 字节 SecureRandom、`MessageDigest.isEqual` 恒时比较、javadoc 明载明文不返回/不入库/不入日志；`SensitiveLog` 脱敏过滤器（CFG-19 四字段 `password/auth_code/fund_password/auth_token`）已在 `RequestDispatcher`/`Bootstrap` 装配。grep 面：Java `src/main` 无口令进日志路径；前端 `auth.js`/`login.js` 全部口令输入 `type=password` + `autocomplete` 正确形态；**跨页汇总**：`fund.js`/`audit.js` 等其余 12 页 0 个资金密码/口令字段；`session.js` 仅落 sessionStorage 非敏感摘要（userId/username/expireTime/mustChangePassword）；全仓硬编码密钥字面量 grep 0 命中。种子数据 `data-init.sql` 无 `INSERT INTO t_user`（⇒ 无明文口令落盘，但见 R2-001 的部署面后果）。
5. **问题清单（只报不改）**：✅ 见 M2.5（R2-001~R2-006，分级：低 2 / 建议 4；无严重/阻塞）。
6. **E4 扫描根 vs §3.1–§3.6 六模块布局一致性**：✅。§3.0.2 E4 脚注（v1.5.13）扫描根 = `cpp/src/{order_engine,risk_engine,risk_state}`；实际目录 = `ctp_adapter/order_engine/risk_engine/risk_state/flow_store/hgcp` + `main.cc`，与 §3.1–§3.6 六模块**逐一对得上**（无增删/改名）；扫描根为六模块子集系 v1.5.12 裁定口径（T4 交易路径），非漂移。E4 实扫 61 文件（v1.5.13 时点 6 → 现 61，系 T-059 等测试文件入扫，覆盖面扩大方向，非缺陷；ARCH 内「6 个」系历史时点实测语）。

## M2.4 采信/新扫分列（重复劳动规避声明）

**采信既有独立复核（不重复其逐行工作）**：
- T-041（te-verify-t041：崩溃恢复/0x0401 合取/树锚 9df51d8c）——C++ recovery 功能正确性面；
- T-042/T-058（装配级 e2e 五缺陷修复 + 复核）——Java↔C++ 桥接语义面；
- T-059（te-verify-t059c：覆盖率 880/998=88.18% 双轮复现）——C++ 单测覆盖面；
- T-055（te-verify-t055：fund.js 六态/负例四方向）与 T-071（te-verify-t071：audit.js 红线四点）——前端两单大交付逐行面；
- T-194（te-verify-t194：check-tasks 端点/路由四方对账）——AUDIT 增量面；
- T-193/T-190/T-189/T-188 等 settle 链各 te-verify——结算幂等/落库面。

**本席新扫（本单价值面）**：
① E1~E8 全量机器执行（此前复核多为聚焦跑；E1 实测 218 link.txt + 2 binary、E4 实扫 61 文件均为本单首证）；
② C++11 词法全量独立 grep（验收 3 指定本席价值点）；
③ DB 库链接面独立三查（CMake link 目标 + DB 名 grep + SQL 形态 grep）；
④ 敏感字段跨页汇总复核（12 页 × 口令/资金密码/密钥字面量/sessionStorage 落盘内容，此前各席只扫本页）；
⑤ S7 后无人复核的边角：`main.cc`（66 行工程骨架，与 TEST_PLAN/M2.md §2「真联档不可达」披露一致，未漂移）、`docs/DEPLOY/`（两工具脚本实跑 rc=0，见 M2.7；发现 R2-001）、`tools/arch-check/` 解释器分派面（R2-006）；
⑥ `putQuota` 层③门禁现状机制取证（M2.6 遗留风险①，非新缺陷、已由 T-118/T-119 承载）。

## M2.5 问题清单（R2 系 = M2 审查轮系；只报不改）

| 编号 | 级别 | 类型 | 位置 | 摘要 |
|------|------|------|------|------|
| R2-001 | 低 | 部署面漂移 | `docs/DEPLOY/schema/data-init.sql`（无 `INSERT INTO t_user`/`t_user_role`）+ `docs/DEPLOY/README.md` §7 步骤 6 | 首个管理员账号无创建路径 |
| R2-002 | 低 | 判据环境依赖 | `tools/arch-check/check_no_db_links.sh`（E1） | E1 实扫依赖仓库内 build 产物（link.txt 218 个） |
| R2-003 | 建议 | 口径扩展 | `docs/ARCHITECTURE.md` §3.0.2 / TASKS T-060 验收 1 | E7/E8 已稳定接入聚合入口，建议 M3 收官单起字面口径扩为 E1~E8 |
| R2-004 | 建议 | 文档时点语 | `docs/ARCHITECTURE.md` §3.0.2 E4 脚注「待扫描文件: 6 个」 | 历史时点实测语现值为 61（测试文件入扫），建议下次触碰时点注化 |
| R2-005 | 建议 | 工程健壮性 | `tools/arch-check/scan_hgcp_schema.py` / `scan_contracts_payload.py` | 脱离聚合入口以 `bash` 直跑得 rc=2（见 R2-006 复现），建议加可执行位或入口注释 |
| R2-006 | 建议 | 可维护性 | `cpp/src/main.cc`（66 行） | 生产装配仍为骨架——**已披露未达项**（TEST_PLAN/M2 §2/§6、T-057 未达 5），非新缺陷，登记防「收官=完成」误读 |

**逐条明细**：

- **R2-001（低·部署面漂移）**
  - 位置：`docs/DEPLOY/schema/data-init.sql`（播种覆盖表 `:84-90` 共 7 表，无 `t_user`/`t_user_role`；全文 0 处 `INSERT INTO t_user`）；`docs/DEPLOY/README.md:222`（部署步骤 6 = 仅执行 `data-init.sql`）。
  - 复现：`grep -rn 'INSERT INTO t_user' docs/DEPLOY/schema/*.sql` → 0 命中；`grep -c 'INSERT INTO' docs/DEPLOY/schema/data-init.sql` → 8（`t_sub_account`/`t_product`/`t_position_group`/`t_role`/`t_position_conflict`/`t_account_subject`/`t_report_period_config`/`t_dict_item`/`t_config_item` 系，无用户表）。
  - 失效场景：按 DEPLOY README §7 十步部署后，`t_user` 零行 ⇒ **无人能登录**（`/auth/login` 查无用户；`must_change_password` 首登门禁也无从触发）；`t_role` 已播 7 角色但 `t_user_role` 零行，CH-012 落地硬前置「播种须与实现同批」的后半（`t_user_role`）同样缺位。
  - 对应需求：部署可用性（DEPLOY README §7 目标 ≤30 分钟可交付）、`CH-012` 播种硬前置（ARCHITECTURE §8.2③）。
  - 建议方向：立号补「初始管理员 bootstrap」承载（其一：种子脚本加受控初始行 + 首登强制改密已天然覆盖；或提供一次性引导命令），并同步 DEPLOY README §7。
  - 级别说明：**低**而非严重——部署尚未实机执行（§8.1 DM8 不可达已披露），缺陷在实机部署前暴露即可，未影响任何已验证面。

- **R2-002（低·判据环境依赖）**
  - 位置：`tools/arch-check/check_no_db_links.sh`（E1）。
  - 复现：本冻结点实测 E1 实扫 218 个 link.txt + 2 个 binary——**这些产物来自仓库内 `cpp/build*` 目录**；干净检出（无 build）时 E1 走「无产物 ⇒ SKIP + 退出 0」分支（脚本头自述该行为）。
  - 失效场景：CI 若在无 build 环境跑聚合门禁，E1 静默降级为 SKIP——**E1「通过」与「实扫通过」不可区分**（输出有 SKIP 说明但聚合 rc 同为 0），M2 收官本单赖以成立的是实扫形态。
  - 建议：聚合入口对 E1 输出形态（SKIP vs 实扫）做可见性提示，或 CI 固定先构建后跑判据。不判缺陷：脚本头已如实披露 SKIP 语义，属环境约定缺口。
  - 对应需求：`NFR-SEC-9`（E1 链接检查 CI 强制）。

- **R2-003（建议·口径扩展）**：T-060 验收 1 字面为 E1~E6；实测 E7（T-121）/E8（T-170）已接入聚合入口且多单依赖其守卫（CH-019 四制品链）。建议 M3 起任务字面口径扩为 E1~E8（编排者裁决域，本席不改口径）。

- **R2-004（建议·文档时点语）**：ARCHITECTURE §3.0.2 E4 脚注载「实测待扫描文件: 6 个」为 v1.5.13 时点语；现值 61（T-059 等测试入扫）。按「活跃制品禁固定时点值当 live 值」先例，建议下次触碰该节时加时点标注。不影响判据正确性。

- **R2-005（建议）**：`scan_hgcp_schema.py`/`scan_contracts_payload.py` 无可执行位（`-rw-r--r--`）；以 `bash tools/arch-check/scan_hgcp_schema.py` 直跑被 bash 解释 python 语法，报错退出 **2**（幸为「用法/前置错误」语义、非 0，**不构成假绿**）。建议加可执行位 + shebang 已有（`#!/usr/bin/env python3`）即闭环。

- **R2-006（建议·登记防误读）**：`cpp/src/main.cc` 仍为 66 行自检骨架（打印六模块名 + `--self-test`），不监听端口/不做握手。此系 **已披露未达项**（TEST_PLAN/M2.md §2「真联档不可达」、§6.5「生产侧启动装配 🔴 未达」、T-057 未达项），M2 收官语境下重申：**M2 合规门禁通过 ≠ C++ 生产进程可联**，真联档欠账仍在（见 M2.6③）。

## M2.6 遗留风险

| # | 风险 | 现状与依据 | 建议 |
|---|------|-----------|------|
| ① | **层③（账户级）功能权限门禁仍缺位** | 本席机制取证（2026-10-13 冻结点）：`VarietyService.putQuota`（`VarietyService.java:676-752`）层③分支仅有参数校验 + 层①可见性强制（`:708-710` 仅 `scopeSubAccount > 0` 触发），**无任何功能权限/角色断言**；`AuthService.hasFunctionPermission` 在 `java/src/main` **0 命中**；`RolePermissionDao` 仅自身接口文件、无 impl、无 caller、`t_role_permission` 零播种——与 TASKS T-108/T-118/T-119（均 `TODO`）登记完全一致 | 按既定编号推进 T-119（断言器）→ T-108（链路+播种）→ T-118（全局对象写面）；M3 收官单须复核该链闭合 |
| ② | **E1 覆盖面声明** | E1 仅覆盖直接链接（ldd 一层 + link.txt 文本）；静态库间接引入不在覆盖内（脚本头已披露） | 维持脚本自述口径；符号级检查待有真实构建产物后再议 |
| ③ | **真联档欠账** | `main.cc` 骨架 ⇒ 跨进程真联不可达（TEST_PLAN/M2 已披露，B 档结论以打桩档标注） | C++ 生产侧启动装配派单后补跑真联档；勿因本单「通过」而跳过 |
| ④ | **前端非敏感面未全扫** | 本席敏感字段跨页汇总只扫口令/资金密码/密钥/直连四类；`localStorage` 仅 `GATE_KEY`（布尔门禁标记）与 sessionStorage 摘要，无 token 类持久化——但前端整体（audit.js 1183 行等）功能正确性面已由各自 te-verify 承载，本席不重复 | 维持现状 |

## M2.7 已执行的验证（全部真实执行，2026-10-13）

| 验证 | 命令 | 结果 |
|------|------|------|
| 冻结点 | `git log -1` / `git status --short` | `a129a4a…` / 仅 `?? .verify/` |
| 聚合门禁 | `bash tools/arch-check/arch-check.sh` | rc=0，八判据全 0 |
| 逐脚本单跑 | 8 脚本逐一执行记 rc | E1~E8 逐 rc 见 M2.2 |
| E1 实扫形态 | `bash tools/arch-check/check_no_db_links.sh`（看输出头） | 待检 binary 2 / link 218，非 SKIP |
| CMake 链接面 | `grep -rn 'target_link_libraries' cpp/` | 21 处，无 DB 库 |
| DB 痕迹 grep | `grep -rniE 'mysql\|postgres\|sqlite\|odbc\|oci\|libpq\|jdbc\|DmDriver' cpp/ …` | 0 命中 |
| SQL 形态 grep | `SELECT…FROM / INSERT INTO` 于 `cpp/src` | 0 命中 |
| C++11 词法 | `std::optional\|string_view\|variant\|make_unique\|if constexpr\|auto[…]` | 0 命中 |
| E4 实扫量 | `bash tools/arch-check/scan_lock_free.sh` | 61 文件，WARN 3（判据 C 信息性） |
| 敏感面 | `PasswordHasher` 通读 / `t_user` 结构 / `SensitiveLog` 装配 / 14 页 grep / sessionStorage 内容审 | 均成立（M2.3-4） |
| DEPLOY 工具 | `python3 docs/DEPLOY/tools/reconcile-schema.py` / `fk-order-check.py` | rc=0 / rc=0（与文档声明一致） |
| 种子核查 | `grep 'INSERT INTO t_user' docs/DEPLOY/schema/*.sql` | 0 命中 ⇒ R2-001 |
| 基线锚 | `md5sum docs/REVIEW.md`（追加前） | `8eb53017e30f052f6f1c878e0cc16c31` / 277 行 ✓ |

## M2.8 在途并发披露

- **T-195（/fund/export CSV 化 + 申请列表端点，backend 席）**：与本单并行。冻结点实测：`routes.js` FUND 键 = 12 路径（T-055 交付形态，`EXPORT: '/fund/export'` 注释仍为「JSON 数据」）；`git status` 无 FUND 面未提交改动 ⇒ 冻结点未见在途痕迹，FUND 面按 T-055 复核结论采信。T-195 落地后 FUND 键数/形态将变，**不构成本单缺陷**。
- **`.verify/` 未跟踪目录**：本会话前已存在，非本席创建（本席零写入该目录），列披露不处置。

## M2.9 未决项

1. R2-001~R2-006 处置（立号/收编/仅登记）归编排者裁定；
2. E7/E8 是否扩入验收字面（R2-003）归编排者；
3. 层③链（T-108/T-118/T-119）推进时序归编排者（M2.6①）；
4. 本报告按纪律只落 `docs/REVIEW.md`，未写 `dev-team/**` 工单（S7 节先例同姿态，工单投递建议由编排者据 M2.5 派发）。

---

# M3 代码与架构合规审查（T-075，第 243 轮）

> 审查席 `cr-t075`；冻结点 `21b1b26e…`（HEAD，工作树仅 `?? .verify/` 非本席创建）；追加前基线 406 行 / md5 `04218d7b…`（负控吻合）。
> 审查范围 = **a129a4a（T-060 冻结点）之后的代码增量**。范围勘误见 §M3.2①。

## M3.1 审查结论

**通过（0 阻断 / 0 严重）**。四验收逐条判定见 §M3.4；验收 2 专项（审计旁路 + 阻断无开关）全部成立；问题清单 R3-001~R3-005 均为一般/建议级，不构成放行障碍。

## M3.2 审查范围实测勘误（工单前置事实① vs 实测）

1. **工单称 T-193/T-194/T-041 属本单新审面，实测均在 a129a4a 之前已交付**（`git merge-base --is-ancestor` 证实：T-193 `77425a6`、T-194 `8cec406`、T-041 `d6fe701` 均为 a129a4a 祖先）。T-060 冻结点后的**真实代码增量**仅有：
   - Java：T-195（`FundExportHandler` CSV 直返 + `FundApplyListHandler` 新端点 + `FundService.listDepositWithdrawApplies`/`listFlow` 载体改写 + `FundParams`/`FundRoutes`/`ApiRoutes`/`FullWebAssembly`/`BootstrapAssemblyTest` 随动，共 8 文件）；
   - 前端：T-070（`risk.js` 832 行全新 + `routes.js` RISK/NOT_REGISTERED 键）+ T-195 随动（`fund.js`）；
   - **C++ 零增量**（a129a4a 后 `cpp/` 无任何改动）。
   - 处置：T-193/T-194 虽在冻结点前，但其交付与复核记录均落在 T-060 审查时点之后（第 236~240 轮），**T-060 的 M2 节未实际覆盖**——本单按「采信各自 te-verify 结论 + 本单补核审计旁路面」处置（T-193/T-194 的 te-verify 均已通过，且两者审计链面在 §M3.5 专项中实际覆盖）。
2. **路由计数口径**：工单派发上下文引「147→149」，实际链路为 145→147（T-194，`BootstrapAssemblyTest` 断言值）→ **148**（T-195，FUND 15→16 对 + ping 2）；工单「147→149 实为 148」的自查结论与实测吻合（146 业务 + 2 ping）。

## M3.3 已执行的验证（全量实跑，逐 rc）

| # | 验证 | 命令 | 结果 | vs T-060 时点 |
|---|------|------|------|--------------|
| 1 | E1 | `arch-check.sh`（E1 C++ 链接检查） | rc=0（218 link.txt + 2 binary 实扫） | 零回归 |
| 2 | E2 | 同上（C++ SQL 痕迹，142 文件） | rc=0 | 零回归 |
| 3 | E3 | 同上（C++11 合规，142 文件 + 10 CMakeLists） | rc=0 | 零回归 |
| 4 | E4 | 同上（三目录并集无锁/无同步 I/O，61 文件） | rc=0（WARN 3 处为已知未覆盖判据信息性提示） | 零回归 |
| 5 | E5 | 同上（Java dao 外无 SQL，336 文件——T-195 新增文件已含入扫描面） | rc=0 | 零回归 |
| 6 | E6 | 同上（前端零构建链 + 无直连——risk.js 新增面已含入） | rc=0 | 零回归 |
| 7 | E7 | 同上（HGCP §4.2.8 ↔ proto：40+10 消息、214+67 字段、28 枚举） | rc=0 | 零回归 |
| 8 | E8 | 同上（CONTRACTS §3.2.1 payload 键 ↔ proto 字段名） | rc=0 | 零回归 |
| 9 | Java 全量 | `cd java && mvn -B -o clean test` | **1099/0/0/0 skip=0**，BUILD SUCCESS（2m39s） | T-195 交付自述 1099，独立复现吻合 |
| 10 | C++ ctest | `cd cpp/build && ctest` | 54/54 通过（含 hgcp.recovery_gate） | 与 be-t041 自述 54 吻合 |
| 11 | 前端语法 | `node --check risk.js/fund.js/routes.js` | rc=0 ×3 | — |
| 12 | 前端探针 | `node tools/probes/t070_risk_probe.mjs` / `t055_fund_probe.mjs` | 104/0 与 100/0，双 rc=0 | 独立复现吻合 |

## M3.4 四验收逐条判定

1. **E1~E6 复跑通过** ✅——E1~E8 全量 rc=0（§M3.3 表），对 T-060 时点全 0 零回归；E5 扫描面 336 文件已含 T-195 新增 Java 文件、E6 已含 risk.js 832 行新增面，非「旧文件旧口径」通过。
2. **无绕过审计日志的旁路；阻断无开关** ✅——专项见 §M3.5。
3. **组件边界无越界；敏感字段零落盘** ✅——E 系承载（E1~E8 rc=0）；T-070 risk.js 新增面敏感扫描：`password/口令/资金密码/secret/token` **零命中**（无凭据输入框、无敏感字段渲染）；74 处 `F.esc` 覆盖全部插值渲染（`ui.tableHTML` 对 `render` 回调结果不再转义，本页所有 `render` 回调内插值均经 `F.esc`——抽查 15 处无一漏逸；`alertLevelHTML`/`disposedHTML`/`lagBadgeHTML`/`hintBadgeHTML` 四个徽标函数内部已转义）。
4. **问题清单不改代码** ✅——R3-001~R3-005（§M3.6），本席零触碰源码/TASKS/BOARD/工具。

## M3.5 验收 2 专项：审计旁路扫描与阻断无开关（本单独有判据）

### M3.5.1 扫描面声明

| 扫什么 | 怎么扫 | 命中 |
|--------|--------|------|
| 「写业务数据但不写审计日志」的写路径 | ① `t_audit_log` 生产者面清点：`grep -rln AuditLogService`（src/main）= 6 文件（AuditLogService 自身 + AuditReportService + EvidenceExportService + AuditLogHandler/Routes + Bootstrap）——即审计日志**唯一写入口** = `AuditLogService.append`（`logDao.insert` 单点 `:131`）；② 逐写路径对照 `R-AUDIT-3` 留痕范围：业务写留痕承载 = **各业务表自身**（`t_deposit_withdraw_apply` 状态机、`t_fund_review_record` 越权留痕、`t_risk_disposal` 处置留痕、`t_param_change_log` 等）——`R-AUDIT-3` 明文「留痕范围…每条含操作人、时间、对象、动作、结果」以**业务留痕表**为承载，`t_audit_log` 承载「审计操作自身」（`AUDIT.md` 术语表「审计日志 = 审计操作自身的记录」）；③ 导出链交叉核验：三个导出面（evidence/report/fund）均先 `assertChainIntact` + 写 `AuditLog`（10017 先写日志），无「只导出不留痕」面 | **0 旁路** |
| 10017/10018 阻断链开关 | `grep -iE 'enabled\|skip\|bypass\|dryRun\|dry_run'` 于审计链 6 文件（AuditLogService/EvidenceExportService/AuditReportService/ErrorCode/DmAuditLogDao/DmAuditLogChecksumDao）：**零命中**；`System.getProperty/getenv/config` 于审计链两 service：**零命中**（阻断不可经配置/环境变量关闭） | **0 开关** |
| 全仓开关字面命中甄别 | service 层 8 文件命中逐条核语境：`ENABLED/DISABLED` = 用户/角色/产品状态枚举（AuthService/ConfigService/VarietyService，业务状态非开关）；`skipIfUnsourced`（TradeService 10 处）= 事件富化缺源跳过落库（T-172 既有语义，非审计链）；`dryRun/bypass` 全仓 src/main **零命中** | 全部为非开关语义 |
| 10018 阻断无条件性 | `assertChainIntact`（AuditLogService:287）→ `verify(1,0)` 不通过即抛 `AUDIT_CHAIN_TAMPERED`（412），无任何条件分支可跳过；两个消费点（EvidenceExportService:125 / AuditReportService:279）均为方法体首行无条件调用 | 无条件成立 |
| 10017 先写后导出 | EvidenceExportService:143-166：组装 AuditLog → `append` → **catch RuntimeException 转 10017 中断**（403）；导出文件组装与落库均在 append 成功之后（`:168` 起）——「不记录即不允许发生」时序成立 | 成立 |
| t_audit_log 不可篡改 | AuditLogService 无 update/delete 方法；DmAuditLogDao 显式拒绝改写（`:100`） | 成立 |

### M3.5.2 T-194/T-195 新端点只读语义核实

- **T-194**（`GET /audit/check-tasks`、`GET /audit/check-tasks/overdue`）：`CheckTaskQueryHandler` 仅 GET 分派（POST 405 兜底）；消费既有 `CheckTaskQueryService.query/overdue`（服务本体零改动），两方法均为 `findPage`/清单查询，**无任何 insert/update 路径**——无隐藏写，无审计旁路面（只读端点不写业务数据即不涉旁路）。
- **T-195**（`GET /fund/apply-list`）：`FundApplyListHandler.handle` 单一 `PageQuery` → `FundService.listDepositWithdrawApplies` → `dwDao.findPage`，只读成立；`/fund/export` CSV 化改写的是**响应载体**（JSON→CSV 直返），取数链 `exportReconcile` 不变且无写路径。
- 佐证：全量 1099 测试含 T-194 十二用例（405/隔离/分页信封）与 `FundApplyListHandlerTest` 292 行，均绿。

### M3.5.3 旁路结论

**无旁路**。`t_audit_log` 写入单点（`AuditLogService.append`）+ 三导出面均「先校验（10018）→ 先写日志（失败 10017 中断）→ 后出文件」时序完整；阻断链无配置/环境/条件开关。唯一近似风险点为 `AuditLogService.query` 自写日志（`append(self)` `:191`）在 append 失败时会向上抛——该失败路径本身即 10017 语义（写失败即拒绝查询结果返回），属 fail-closed，非旁路。

### M3.5.4 R3-003 关联披露（审计链外的诚实披露项）

生产装配两处「暂未接线」均**显式注释披露**且各有承接号，不判缺陷、列 R3-003 跟踪：
- `Bootstrap.java:652`：`TamperAlarm` 暂接 NOOP（运维告警 sink 未交付，与 T-065/T-177 同姿态）——校验结论仍落库可查、10018 阻断不受影响（阻断在 service 层，与告警端口无关）；
- `Bootstrap.java:718`：`RiskAlertNotifier = null`（/ws/alert 生产推送未接线，T-040 承接）——**注意**：risk.js 验收 3 的「实时推送」依赖此端口，当前生产面实际为「REST 补拉可用、推送待 T-040」；前端断线重连补拉逻辑（`since=lastTsMs`，`alert_ts_ms > since` 服务端谓词 + `alertView.alertTsMs` 出参齐全）不受影响。

## M3.6 问题清单（R3 系；一般/低/建议，无阻塞/严重）

| 编号 | 级别 | 类型 | 位置 | 问题与失效场景 | 建议 | 对应需求 |
|------|------|------|------|----------------|------|----------|
| R3-001 | 一般 | 语义不对称 | web/js/pages/risk.js:232 | **断线补拉窗口依赖 WS 帧时钟，静默期后补拉可能漏行**：`backfillAfterReconnect` 以 `st.lastTsMs`（最后收到的 WS 推送帧 tsMillis）为 since；若断线期间既有「WS 推送失败未达」又有「此后再无新告警」（lastTsMs 停留在断线前最后一条），则 `alert_ts_ms > lastTsMs` 能补齐该最后一条之后的所有行——该方向正确；但**首连后未加载列表即断线重连**时 `st.lastTsMs=0`，`backfillAfterReconnect` 因 `!st.lastTsMs` 直接 return（:229），补拉被跳过、依赖用户手动查询列表。失效场景：页面加载→WS 首连前/后立即断线→重连成功→实时流为空且无提示诱导手动查询。 | 首连成功时即做一次 `loadAlerts(1)` 或在 lastTsMs=0 时以「当前时间−窗口」兜底拉取 | FR-RISK-19 / T-070 验收 3 |
| R3-002 | 一般 | 边界 | web/js/pages/risk.js:321 | `q.fromTime = aFrom.value.replace('T','T')` 为**恒等替换**（疑似占位残留）；dtInput 值形如 `2026-10-13T09:30`（无秒），服务端 `localDateTime` 可解析（ISO-8601 允许无秒），现行为不致错，但恒等 replace 无谓且易被误读为有时区/格式转换语义。 | 删除恒等 replace 或改为显式秒补齐 | 可维护性 |
| R3-003 | 一般 | 披露面 | Bootstrap.java:652/:718 | 两处「暂未接线」披露只存在于**代码注释与 LOG**，REVIEW/ARCH 制品面未见对应「生产推送/告警 sink 未接线」的运行时声明汇总（T-060 §M2.6 已登记层③链，但 TamperAlarm NOOP 与 notifier=null 两项在此前审查制品中未单列）。失效场景：运维据「告警不可静默（R-RISK-11）」与「防篡改须告警（FR-AUDIT-10）」验收采信，实际生产告警链路半通。 | 在 DEPLOY 或运维面文档汇总「暂未接线端口清单」（承接号 T-040/sink） | NFR-MAINT-4 |
| R3-004 | 低 | 一致性 | java/.../FundExportHandler.java:170 | `csvEscape` 判定 `quoted = text.contains("\"")` 恒假：`:169` 已将 `"` 全部加倍为 `""`，加倍后仍含 `"`（成对存在），故该分支实际可达——**但**注释称「额外覆盖换行符」而先例 VarietyService 版本不含 `\r\n`，两实现同名单不同行为，后读先例者可能误判等价性。现行行为正确（换行字段确被包裹，CsvTest 已锁定）。 | 注释点明「与 VarietyService 先例的差异 = 增 \r\n 覆盖」 | 可维护性 |
| R3-005 | 建议 | 一致性 | web/js/core/routes.js:152 | `dispose: (id) => '/risk/alert/' + enc(id) + '/dispose'` 为 routes.js 首个**函数型键**（其余全为字符串常量）；NOT_REGISTERED 注释与 risk.js 头注均称「调用即 404」仅适用于 5 个未注册路径，函数型键为已注册路径的正常形态——但与「ROUTES 值恒为字符串」的潜在消费方假设（如深链生成、枚举遍历）可能不兼容。现消费面（risk.js:417）正确使用函数调用，无现行缺陷。 | 在 routes.js 头注显式声明「允许函数型键（参数化路径）」约定 | NFR-MAINT-4 |

## M3.7 一致性核对（文档 vs 实现）

| 项 | 文档说法 | 实现情况 | 判定 |
|----|----------|----------|------|
| 路由计数 148 | BootstrapAssemblyTest 注释 146 业务 + ping 2 | `ROUTE_COUNT=146`（FullWebAssembly）+ Bootstrap 装配实测 | 一致 |
| FUND 模块 13 路径 | routes.js 注释「13 路径，T-195 起新增」 | ApiRoutes FUND 表实数 13 | 一致 |
| RISK 端点注册面不对称 | risk.js 头注「可接 5 + 未注册 5」 | ApiRoutes 路径表 10 / BY_MODULE_ROUTES 6 对（alert GET+dispose POST + rule 2 + limit-usage 2 = 5 端点 + mirror-status 装配面）实数吻合；NOT_REGISTERED 5 路径 handler/ 零实存 | 一致 |
| WS /ws/alert NEVER_DROP 语义 | ws.js 注释「断线重连后补拉」 | 服务端 WsConnection.enqueue NEVER_DROP → 队列满 close(1013)（:258）实存；补拉谓词 `alert_ts_ms > since` 实存 | 一致（生产推送未接线 = R3-003 披露） |
| ARCHITECTURE v1.5.22 FUND 行 | apply-list 已补登（96f01b3） | ApiRoutes/routes.js/FundRoutes 三落点同步 | 一致 |

## M3.8 采信 / 新扫分列

- **采信**（有独立证据链、本单不复算）：T-193/T-194/T-041 的 te-verify 结论；T-195 变异 M1/M2/M3 击杀；T-070 探针 104 断言逐项设计。
- **新扫**（本单独立执行）：E1~E8 全量；Java 1099 全量复跑；C++ ctest 54；双探针复跑；审计旁路全仓扫描（§M3.5.1）；开关字面全仓甄别；risk.js 敏感字段/XSS 面扫描；T-194/T-195 只读语义核验。

## M3.9 遗留风险与未决项

1. **R3-001 补拉首连盲区**（一般级）：生产 notifier 未接线（T-040 承接）期间该盲区实际不可达（无推送即无「重连补拉」场景）；T-040 落地后即成真缺口——建议 T-040 验收清单纳入本条。
2. **R3-003** 汇总披露落点（DEPLOY/运维面）归编排者指定承接任务。
3. 层③链（T-108/T-118/T-119）、R2-001~006 处置：维持 T-060 §M2.6/M2.9 口径，无恶化，不重复立。
4. `.verify/` 未跟踪目录：同 T-060 §M2.8 口径，非本席创建，列披露不处置。

---

# M1 审查章（T-025，2026-10-20）

> **审查席**：`cr-t025`（code-reviewer 席实例）。**工单**：`dev-team/inbox/code-reviewer/T-025-task.md`（P0/16 人时）。
> **前锚**：追加前 `docs/REVIEW.md` md5 = `5a1cafcee3f72e0158bf8773c29dd3e5` / **511 行**（2026-10-20 09:25+08:00 实测，追加即测）。
> **审查时点**：2026-10-20 08:40–09:30+08:00；HEAD `fafab89`（工作树 clean，`git status --porcelain` = 0 行；在飞 T-111 于独立 worktree `bg-01a0bd8e`，主树零在飞改动）。
> **编号**：R1-xxx 系（M1 域），与 S7 CR-xxx、M2 R2-xxx、M3 R3-xxx 不冲突（实测 R1 前缀 0 占用）。
> **形态**：沿 T-060/T-075 先例；只读审查——源码/TEST_RESULT/TASKS/BOARD 零触碰，写域仅本章 + 工单处理记录。

## M1.1 审查结论

**有条件通过（0 阻断 / 0 严重 / 1 缺陷（中）+ 3 建议 / 2 观察）**。

M1 交付域（AUTH/CONFIG/PRODUCT/SPOT/PLAN 五模块）六轴全部实扫：门禁独立复现 1223/0/0/0 + arch rc0；路由遮蔽回归（BUG-004 家族）独立复跑 5 类 20 用例全绿；「已声明未注册/注册未声明」双向差集自建脚本扫描——**M1 五模块 0 差集**（TRADE/RISK 差集均为既有登记域，见 R1-004）；契约一致性抽验（CONTRACTS §1.2/§2.2/§4.1.1 路由计数 168）全吻合；可见性强制读/写/导出三面逐模块核过 + 变异抽验 2 例均证实有牙；敏感面（口令零落盘/导出脱敏/XSS 转义）抽验通过。唯一缺陷 R1-003（中）为**幂等契约三落点不一致**（ARCH §4.1.1 声明划拨必需 `Idempotency-Key`，实现与 FR-FUND-10 均未承载），不阻断 M1 放行（划拨属 M2 域），但须在 M2 收官前闭环。

## M1.2 已执行的验证（全部真实执行，2026-10-20 08:40–09:30+08:00）

| # | 验证 | 命令 | 结果 |
|---|------|------|------|
| 1 | Java 全量门禁（第 1 轮） | `cd java && mvn -B -o clean test` | **1223/0/0/0**，BUILD SUCCESS（4m54s，rc=0） |
| 2 | Java 全量门禁（变异复位后复绿） | 同上（`-q` 模式，surefire 151 文件聚合） | **total=1223 fail=0 err=0 skip=0**，rc=0 |
| 3 | arch-check 聚合 | `bash tools/arch-check/arch-check.sh` | **rc=0**（E1~E8 全 0；E1 实扫 218 link.txt + 2 binary；E2 142 文件；E5 336 文件） |
| 4 | E5 非空转负控 | 种子探针 `--root /tmp/e5root`（植入 `String s = "SELECT a FROM t_x"`） | **rc=1 命中并正确报文件/行**（判据有牙，非恒绿空转）；探针在 /tmp，主树零污染 |
| 5 | E6 独立复跑 | `bash tools/arch-check/check_frontend.sh` | rc=0（构建链残留 0、直连字样 0） |
| 6 | 路由遮蔽回归（BUG-004 家族，**独立复跑非引用**） | `mvn -B -o test -Dtest='RouteReachabilityExhaustiveTest,RouteReachabilityBug004Test,RouteReachabilityProductionParityTest,ModuleRoutesConsistencyTest,BootstrapAssemblyTest'` | Exhaustive **6/6**、Bug004 **2/2**、ProductionParity **2/2**、ModuleRoutesConsistency **3/3**、BootstrapAssembly **7/7**，全绿（surefire 报告逐类核实） |
| 7 | 树锚 | `cd java && find src -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` | **`069f373ab2997bc9429f4747636d131d` / 764 文件**（与 T-201 贴标口径复现一致）；变异复位后**复测同值** |
| 8 | 变异抽验 M1（可见性写面） | 摘除 `SpotService.java:1230` `requireSubAccountVisible(...)`（备份 `.orig` md5 `26d25085…`） | `SpotT109FixTest` **2/22 红**（TE-T109-002 越权导出负例 + fail-closed 负例同时变红）⇒ **守卫有牙**；复位逐字节（diff 空 + md5 同值） |
| 9 | 变异抽验 M2（敏感导出脱敏） | `ConfigService.exportConfigItemsCsv` 内 `boolean sensitive = isSensitiveKey(...) ‖ "Y".equals(...)` 改 `false`（备份 md5 `b6164422…`） | `ConfigServiceAdminTest` **1/10 红**（导出脱敏断言）⇒ **有牙**；复位逐字节同值 |
| 10 | 前端语法 | `node --check` × auth/config/product/spot/plan.js | rc=0 ×5 |
| 11 | 前端探针复跑 | `node tools/probes/t083_acct_probe.mjs` | **PASS=202 FAIL=0**（rc=0，与 T-083 交付口径吻合） |
| 12 | 幂等头覆盖复算（M1 五页） | 逐页 `api.(post\|put)` 调用点 × `newIdempotencyKey` 8 行窗口配对（python 脚本） | auth 9/9、config 8/8、product 6/6、spot 9/9、plan 6/6——**写调用全带幂等头，0 遗漏** |
| 13 | 双向路由差集（自建脚本） | python 解析 `ApiRoutes.java` `BY_MODULE` vs `BY_MODULE_ROUTES` 双向差集 | 路径→对方向：**M1 五模块（AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18）0 差集**；对→路径方向 0 越界；TRADE 缺 9 / RISK 缺 5（既有登记域，见 M1.5/R1-004） |
| 14 | DATA_MODEL ↔ 实体抽验 | `t_exposure`（TB-SPOT-8）列清单 vs `Exposure.java` 字段集 python 差集 | **0 差异**（文档 21 列 ⊂→= 实体 23 字段含 `sub_account_id`/三分量，`row_version` 由 `AuditedEntity` 继承承载） |

## M1.3 六轴逐轴结论

| 轴 | 结论 | 要点 |
|----|------|------|
| ①E1–E6 实扫 + 非空转抽验 | ✅ 通过 | rc0 全过（§M1.2 #3）；E5 种子负控证实判据有牙（#4）——非「复述 rc0」 |
| ②分层红线 | ✅ 通过 | handler/service 层 SQL/连接形态 0 命中（E5 承载 + 独立 grep）；handler 不 import DAO 实现类（仅 entity/PageQuery 值对象，`grep -rEn 'import com\.hedgeguard\.dao\.(dao\|impl)\.'` 0 命中）；前端零构建链（E6） |
| ③契约一致性 | ✅ 通过（1 项跨域张力除外） | §1.2 公开白名单三对逐字吻合 `SessionAuthFilter.PUBLIC_EXACT:38`；§2.2 `TradingBridge` 七签名逐字吻合（含 HGC-1.6 形态 `confirmToken`）；§4.1.1 路由计数：`BootstrapAssemblyTest:270` 断言 168 = 166 业务 + ping 2，与 `BY_MODULE_ROUTES` 实数 166 吻合；`PageQuery` 缺省 ASC 冻结口径未漂移；CROSS_READONLY 哨兵写面放行系 CH-003/T-102 既定口径不报 |
| ④安全面 | ✅ 通过 | 见 M1.4 专项 |
| ⑤测试质量（变异 ≥2 例） | ✅ 通过 | 两例注入反演均红（§M1.2 #8/#9），断言非同源（负例断言锚 `ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED`/CSV 脱敏列，非生产常量回写）；复位后 clean 全量复绿 1223（#2）+ 树锚复测同值（#7） |
| ⑥文档↔实现漂移 | ⚠️ 2 项 | DATA_MODEL v1.3.8 `t_exposure` 抽验 0 差异；漂移项见 R1-003/R1-004 |

## M1.4 安全面专项（可见性 / 口令 / 审计留痕）

| 子项 | 结论 | 证据 |
|------|------|------|
| 读面可见性逐模块 | ✅ | PRODUCT：`exportProducts`（VarietyService:922）走 `visibleSubAccountIds` + `findAllScoped`；SPOT 导出：`exportExposuresCsv:1230` `requireSubAccountVisible`；PLAN 读面隔离由 `M1EndToEndAcceptanceTest` 验收 2a/2b/2c 三态守卫（11 用例在册全绿） |
| 写面可见性逐模块 | ✅ | 五服务强制点计数：AUTH 6 / CONFIG 0（见下）/ PRODUCT 15 / SPOT 21 / PLAN 9；SPOT 写面 `requireSubAccountWritable`（T-115 家族）；CONFIG 子账户写面走**账户级**授权（v1.5.11 两阶段口径 M1 角色近似），层③断言器 `assertCanConfigureAccountLevel` 实测 `java/src` 0 命中——**与 T-119/T-118/T-108（均 TODO）登记完全一致，维持 T-060 §M2.6① 口径，不重复开单** |
| 导出面 | ✅ | SPOT 敏感导出 fail-closed（可见性入口缺位 ⇒ 10004，SpotT109FixTest 三态负例守卫）；CONFIG 导出敏感键取值列恒空（`exportConfigItemsCsv` 脱敏 + SENSITIVE_KEY_MARKERS=4 词形）且变异有牙 |
| 口令零落盘/零日志 | ✅ | `PasswordHasher` PBKDF2 形态未回归；LoginHandler 明文仅内存持有、错误消息不含取值；前端 `login.js` `type=password` + `autocomplete=current-password`、`auth.js:514` 提交后 `pwd.value=''` 不驻留；session.js 仅落非敏感摘要（S7/M2 结论零回归抽验） |
| 审计留痕 | ✅ | 越权写统一经 `AuthService.assertSubAccountWritable:709` 覆写 → `recordAttempt` → `t_unauthorized_attempt`（R-AUTH-8 单点留痕）；`SettleCheckService:701` 第二写入点属 M3 域既有形态；M1 域无绕过单点的第二写入口 |
| XSS 转义抽验 | ✅ | 五页 `F.esc` 206 处；`innerHTML` 直接赋值 8 处逐一目视：5 处为 `sel.innerHTML=''` 清空、3 处 `importProblemsHTML`/`ui.tableHTML` 产物内部已全量 `F.esc`（config.js:1063-1073 逐行核过） |

## M1.5 M1 专属重点核过结果

1. **路由遮蔽回归（BUG-004 家族）**：独立复跑 5 类 20 用例全绿（§M1.2 #6）。`RouteReachabilityExhaustiveTest` 的「实例路径回查 + 哨兵碰撞机械断言」双层判据形态复核成立；`ModuleRoutesConsistencyTest` 等值判据（声明=运行时）在 M1 五模块面成立。
2. **凭证/自检链四键深链（T-200 域，从边核）**：后端 `GET /spot/exposure/{id}` 实存（SpotRoutes:71）；前端 `relatedDocTarget:128` `exposureId` → `/spot/exposure/` 深链 + `spot.js:1394` 路由注册；**前端注册序**：spot.js 静态键 `/spot`、`/spot/exposure` 均先于 `:id`（:1387/:1388/:1394），plan.js `/plan/new` 先于 `/plan`、`/plan` 先于 `:id`（:1281-1283）——前端 hash 路由（router.js 顺序遍历 regex）无遮蔽面；spot.js:1393 注释显式披露「recalc/export 为 API 路径非 hash 路由」。t083 探针 202/0 复现（含跳转断言）。
3. **双向差集**：TRADE 9 条 / RISK 5 条「已声明未注册」——RISK 5 条既有完整登记链（routes.js `NOT_REGISTERED` 键 + risk.js 头注「可接 5 + 未注册 5」+ T-070 披露 + M3.7 一致性表核对一致）；TRADE 9 条中 `TRADE_ORDER` 有 NOT_REGISTERED 键，**其余 8 条路径（order-report/fill/position/reason/reject-record/flow/quote/strategy）在 routes.js 无键、依赖 T-053（BLOCKED）承接**——处置见 R1-004（建议级，既有域非新缺陷）。**「注册未声明」方向：0**（等值判据承载）。

## M1.6 问题清单（R1 系；只报不改）

| 编号 | 级别 | 类型 | 位置 | 问题摘要 |
|------|------|------|------|---------|
| R1-001 | 建议 | 一致性 | `java/src/main/java/com/hedgeguard/service/ConfigService.java:1055`（`exportConfigItemsCsv`） | CONFIG 导出与 SPOT/PRODUCT 导出的**可见性口径不对称**：SPOT 导出强制 `requireSubAccountVisible`、PRODUCT 导出走可见集过滤，CONFIG 导出（配置项，全局资源、无 `sub_account_id` 维度）无任何调用者身份校验——**非缺陷**（配置项无子账户归属，`t_config_item` 无该列），但三导出面审查口径不一，建议在 ARCH §8.2 或 CONTRACTS §1 显式登记「全局资源导出豁免可见性」判定，防后续审计轮重复争议 |
| R1-002 | 建议 | 可维护性 | `java/src/main/java/com/hedgeguard/web/SessionAuthFilter.java:38` vs `java/CONTRACTS.md` §1.2 | 公开白名单以**字符串字面量**落 `PUBLIC_EXACT`（`"POST /api/v1/auth/login"` 等 3 对），未引用 `ApiRoutes.full(...)`（对比同文件 `:47` FORCE_CHANGE_ALLOWED 已用 `ApiRoutes.full` 拼装）——两形态并存，ApiRoutes 改基路径时 `PUBLIC_EXACT` 会静默脱钩（E 系判据与测试均拦不住：白名单脱钩方向是「未认证即 10006」，属 fail-closed 不产生越权，故仅建议级）。建议统一为 `ApiRoutes.full` 形态 |
| R1-003 | **中** | 一致性/契约 | `docs/ARCHITECTURE.md` §4.1.1 幂等行（:625）vs `docs/REQUIREMENTS/FUND.md` FR-FUND-10 输入列 vs `FundService.initiateTransfer:771` | **幂等契约三落点不一致（跨域，M2 域）**：ARCH §4.1.1 声明「写类接口要求头 `Idempotency-Key`（出入金/**划拨**/下单/参数下发必需）」，但 ① `TransferRequest`（FundService:1842）**无 `idempotencyKey` 字段**、`initiateTransfer` 全程不读该头（`grep idempotencyKey` 于 initiateTransfer 0 命中）；② FR-FUND-10 输入列亦未列幂等标识（仅 §2.0 术语表通用行声明「每次提交须携带」）；③ 下单（TradeService）与参数下发（ParamDispatchServiceImpl）分别以服务端生成 `clientOrderRef`/`param_version` 单调号承载幂等，非经 `Idempotency-Key` 头。失效场景：划拨请求网络重试 ⇒ 同一笔划拨重复创建（无 `findByIdem` 拦截，对照出入金 `:501` `requireText(req.idempotencyKey)` + `findByIdempotencyKey` no-op 形态）——重复划拨在审批前可被驳回拦截，且属 M2/T-049 已 DONE 域，故**不阻断 M1 放行**；但 ARCH 声明与实现二选一必须收口：要么补划拨幂等键承载（对齐出入金形态），要么订正 §4.1.1 幂等行与 FR-FUND-10 并走 CH。建议归口 T-049 域立 delta 单或转权威席裁定 |
| R1-004 | 建议 | 披露面 | `web/js/core/routes.js:162`（NOT_REGISTERED 键） | TRADE 模块 12 条路径级声明中 9 条无方法对（差集脚本实测），其中仅 `TRADE_ORDER` 有 NOT_REGISTERED 登记键，其余 8 条（order-report/fill/position/reason/reject-record/flow/quote/strategy）无前端镜像登记——TRADE 域属 M2（T-046 DONE 后端 3 对 + T-053 前端 BLOCKED），属既有登记域非新缺陷；但 RISK 侧 T-070 已建立「未注册路径逐条进 NOT_REGISTERED」的先例，TRADE 侧未对齐。建议 T-053 解锁时或 routes.js 下次触碰时补齐 8 键，防「前端臆造路径」防线在 TRADE 域留缝 |

**观察（不开单）**：
- O1：`ConfigService` 五服务中唯一可见性强制点计数为 0（CONFIG 资源为全局/账户级，子账户维度不适用），与 v1.5.11 §8.2「层③账户级不适用子账户维度哨兵」口径自洽——留观 T-119 落地时 CONFIG 写面须挂 `assertCanConfigureAccountLevel`，届时本观察自动闭。
- O2：`M1EndToEndAcceptanceTest` 11 用例聚焦 PLAN 隔离/取号域；AUTH/CONFIG/PRODUCT/SPOT 四模块无同等装配级 e2e 类（各模块覆盖靠 service/handler 分层用例 1223 全量），非缺口（M2 e2e T-058 已覆盖装配级面），列观察防「M1 e2e = 全模块」误读。

## M1.7 一致性核对（文档 vs 实现，抽验）

| 项 | 文档说法 | 实现情况 | 判定 |
|----|----------|----------|------|
| 路由计数 | `BootstrapAssemblyTest:270` 断言 168（166 业务 + ping 2）；routes.js 头注 AUTH17/CONFIG18/PRODUCT13/SPOT15/PLAN18 | `BY_MODULE_ROUTES` 实数：AUTH 17 / CONFIG 18 / PRODUCT 13 / SPOT 15 / PLAN 18（合计 166 全模块） | 一致 |
| ARCH §4.1.1 资源表 | M1 五模块路径清单 | `ApiRoutes.BY_MODULE` 五模块逐路径字面比对（差集脚本） | 一致（0 差异） |
| CONTRACTS §1.2 公开白名单 | login POST / ping GET+HEAD | `SessionAuthFilter.PUBLIC_EXACT:38` 三对逐字一致 | 一致（形态分裂见 R1-002） |
| CONTRACTS §2.2 TradingBridge | 七签名（HGC-1.6 形态） | `TradingBridge.java:29-85` 逐签名一致（含 `confirmOrder(long,long,String)`） | 一致 |
| CONTRACTS §2.2 `SubAccountScope` | `ALL_ACCOUNT_SENTINEL=0` + 空集恒假 | `SubAccountScope.java:19` 哨兵 0 + `IN (-1)` 恒假形态实存 | 一致 |
| PageQuery 缺省 ASC | §4.1.1 v1.5.7 约定 | `PageQuery.java:43,63` ASC 缺省 + 5 参方向重载 | 一致 |
| DATA_MODEL v1.3.8 `t_exposure` | TB-SPOT-8 全列清单 | `Exposure.java` 字段集差集 0（`row_version` 继承承载） | 一致 |
| 幂等（§4.1.1） | 划拨/下单/参数下发 `Idempotency-Key` 必需 | 三者均未按该头承载（详见 R1-003） | **不一致 → R1-003** |

## M1.8 采信 / 新扫分列

- **采信**（有独立证据链、不复算）：T-200/T-201 的 te-verify 与编排者贴标结论；BUG-005 闭环（tcM4_19 4/4 断言形态目视复核成立，M4DocPackageCas24SelfcheckTest:553-585）；T-102/T-115/T-112 可见性裁定链。
- **新扫**（本单独立执行）：E1~E8 聚合 + E5 负控；Java 1223 全量两轮（首扫 + 变异复位复绿）；路由 5 类判据独立复跑；双向差集自建脚本；幂等头五页配对复算；DATA_MODEL↔实体差集；变异抽验 2 例（注入→复位→逐字节核→clean 复绿全链）；t083 探针复跑 202/0。

## M1.9 遗留风险与未决项

1. **R1-003（中）**闭环前，ARCH §4.1.1 幂等行的「划拨必需」声明处于不实状态——M2 资金域验收或外部审计引用该行时会形成假依赖；建议随 T-049 域下次触碰时收口（修复或订正二选一，须权威席裁方向）。
2. **层③功能权限链（T-108/T-118/T-119 均 TODO）**：维持 T-060 §M2.6① 口径——CONFIG/PRODUCT 全局写面（createProduct/putUnit/config PUT 等）现依赖「会话存在 + 子账户维度哨兵」之外**无功能权限断言**，M2 功能权限接线前属已知登记缺口，非本单新发现，无恶化。
3. R2-001（首个管理员无创建路径）、R3-003（生产推送 sink 未接线汇总披露）：维持原编号处置，无恶化不复述。
4. 在飞 T-111（敞口推导引擎）位于独立 worktree，主树零改动；本报告全部结论对 HEAD `fafab89` 主树成立，T-111 合入后 SPOT 写面相关结论须随其 delta 复核自然更新。

## M1.10 结论

**有条件通过**：门禁三件（1223/0/0/0、arch rc0、树锚 069f373a…/764）独立复现；六轴无阻断/严重项；R1-003（中，跨域幂等契约不一致）与 R1-001/002/004（建议）按 §M1.6 处置建议归口，不构成 M1 收官放行障碍。
