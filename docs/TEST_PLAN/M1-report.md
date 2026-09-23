# M1 端到端验收报告（`T-024`）

> 负责人：测试工程师（`test-engineer`，执席实例 `te-t024`）｜阶段：M1 门禁（`PROJECT.md` §9）
> 需求追溯：`PROJECT.md` §9 M1 验收方式（**本报告逐条对照**）、§10-34
> 层次：**REST 装配级**（真实 `Router` + `RequestDispatcher` + `SessionAuthFilter` + 五个业务模块真实服务/真实 DAO）
> 结论口径：**验收 1 未达成（P0 缺陷 `BUG-003` 阻断）；验收 2 / 3 / 4 达成**（详见 §3、§5）

---

## 1. 执行概况

| 项 | 内容 |
|----|------|
| 代码版本 | 本地 git `HEAD = 2beae55`；`java/src` 树锚 = `find java/src -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` = **`82dd4419f831e79431cd5f9655eddfd9` / 342 文件**（`cwd = 仓库根`；**基线** `9c6a101b4557229da5ddfa2e83441b22` / 340 ⇒ 本任务新增 2 文件） |
| 构建/执行 | `TMPDIR=/root mvn -B -o -Punit clean test`（离线；`java/` 下）；**`Tests run: 526, Failures: 0, Errors: 0, Skipped: 0`**，`BUILD SUCCESS`（`surefire` 名单 526 == 源码 `@Test` 526；65 个测试类，基线 64 + 本任务 1） |
| 架构门禁 | 聚合 `bash tools/arch-check/arch-check.sh` **rc 0**（`E1`–`E7` 全 0）；`E5`（dao 外无 SQL）**rc 0** |
| 测试环境 | Ubuntu / JDK 11 / `maven-surefire-plugin` 3.2.5 / 离线（`-o`）；**无可达 DM8 实例与驱动**（全盘 `DmJdbcDriver*.jar` 0 命中、`java/lib` 不存在、5236 无监听、无 `dmserver`/`dmap`） |
| 执行人 | 测试工程师（`te-t024`） |
| 执行时间 | 2026-09-23 11:3x–11:5x +0800 |
| 产出 | 本报告（**新建**）+ `java/src/test/**` e2e 夹具（**新增** 2 文件）+ 缺陷单 `docs/BUGS/BUG-003.md`（**新建**） |

### 1.1 本轮新增/改动文件（`+n/−n`）

| 文件 | 类型 | `+n/−n` | md5（落笔后） |
|------|------|---------|----------------|
| `java/src/test/java/com/hedgeguard/web/M1EndToEndAcceptanceTest.java` | 新增（e2e 主类，9 用例） | +638/−0 | `b215f5672d3f4b1f2aa280e2ac657fdb` |
| `java/src/test/java/com/hedgeguard/dao/testsupport/M1E2eSeed.java` | 新增（保留系统行/用户/权限播种件；**置于 `dao` 命名空间以过 `E5`**） | +96/−0 | `9cdd470b5ee6a535494d4a0519ba568c` |
| `docs/BUGS/BUG-003.md` | 新增（缺陷单，128 行） | +128/−0 | `9d5343361c2b2abbde765f15a888c21e` |
| `docs/TEST_PLAN/M1-report.md` | 新增（本报告） | +198/−0 | *（自指；以落笔后实时 `md5sum` 为准）* |

> **`java/src/main/**` 零改动**（`git status --short java/src/main` = 0 条）；`web/**`、`docs/TASKS.md`、`docs/BOARD.md`、`docs/REQUIREMENTS/**`、`docs/TEST_RESULT.md`、`cpp/**` 均**只读**（`git status` 中 `web/js/app.js` 的改动**非本席所为**，属前端并行任务）。

---

## 2. 层次与边界声明（**必须显式，勿误读覆盖面**）

| 维度 | 本报告覆盖 | 不在本报告范围 |
|------|-----------|----------------|
| 层次 | **REST 装配级**：真实 `Router` + `RequestDispatcher` + `SessionAuthFilter` + 真实服务（`AuthService`/`ConfigService`/`VarietyService`/`SpotService`/`PlanService`）+ 真实 DAO（`Dm*Dao`） | **UI 级**（前端页面 `T-019`/`T-020`/`T-021` **正在并行开发**，本任务不含） |
| 持久化 | **等价执行**：真实 DAO 经 `T-023` 交付的**按 SQL 语义求值的内存假 JDBC 引擎**（`InMemoryDmEngine`）落库并**逐行、逐字段**断言 | **DM8 实机**（**未验**，见 §4 与 §9） |
| 会话 | 会话直接注入 `SessionStore.InMemory`（2 参 `SessionAuthFilter`，**不注入续期器**） | 登录/口令/滑动续期链路（另由 `T-010` 家族覆盖） |
| 网络 | 无网络、无真实 socket（`NFR-TEST-2` 离线） | HTTP 真实监听（`HttpServerLauncher`）、启动期自检 |

---

## 3. 验收判定速览（对照 `docs/TASKS.md` §2 `T-024` 验收标准 1–4）

| # | 验收标准（`docs/TASKS.md` §2 `T-024`（执行 M1 端到端验收）详节 · 验收标准 1–4） | 判定 | 依据 |
|---|-------------------------------|------|------|
| 1 | 创建两个子账户 → 分别配置品种与实货敞口 → **各自产出套保计划，全链路无断点** | **❌ 未达成** | **P0 `BUG-003`**：两子账户在**同一业务日**各自编制计划时，第二个因 `plan_no` 全局唯一冲突**必失败**（`500/10022`）。单子账户链路本身贯通（见 V-2），但**验收要求的「两子账户各自产出」被阻断**。**反例已固化**（V-3） |
| 2 | 验证子账户数据隔离：A 用户看不到 B 的数据；**审计岗可跨账户查看** | **✅ 达成** | 双向证据见 §7（越权被拒 + 不误拒 + `CROSS_READONLY` 读面放行/写面拒绝） |
| 3 | 验证数据在 **DM8** 中正确落库（子账户标识、敞口三分量、额度三层） | **✅ 达成（等价执行；DM8 实机未验）** | 见 V-4；引擎口径与不覆盖项见 §4 |
| 4 | 出具 M1 验收报告，逐条对照 `PROJECT.md` §9 M1 验收方式 | **✅ 达成** | 即本报告；逐条对照见 §5 |

---

## 4. 引擎「等价执行」口径与不覆盖项（**诚实边界**）

- **未验 DM8 实机**：本机不可达 DM8（无实例、无驱动、5236 无监听）⇒ 依 `T-023` 已确立口径，以
  `InMemoryDmEngine`（测试专用、零依赖）做**等价执行**：由**真实 DAO** 生成 `INSERT/SELECT/UPDATE`，
  引擎**按 DDL/DML 语义求值**（NOT NULL / 主键 / 唯一 / 外键 / 单列 `IN`·`=` 字面量 CHECK / 分页 / 排序）。
  **本报告一律不把等价执行写成「DM8 验证」**。
- **引擎已披露不覆盖项**（`InMemoryDmEngine` 类注释）：条件唯一索引 `WHERE` 谓词、**JOIN/聚合 → 静默返回错值**、
  **子查询 → loud 抛**、多列外键、事务隔离/回滚。**本 e2e 链路不依赖其中任何一项**：
  - 业务查询**主体**为**单表** `WHERE col = ?` / `col IN (?)` / `ORDER BY` / `LIMIT ? OFFSET ?`；
    唯**分页取 `total`** 时列表端点经 `DmDialect.countSql` 生成 `SELECT COUNT(*) AS cnt FROM (<inner>) hg_cnt`
    的**子查询 + 聚合**形态 —— 该形态由 `InMemoryDmEngine` 的 `COUNT_WRAPPER` **忠实支持**（先于通用 `SELECT`
    解析拦截、按内层行数求 `cnt`）⇒ 属**受支持形态、非假绿**，与上述「不覆盖」项**不同类**
    （**如实披露，勿读作「未使用子查询」**）；
  - 未使用条件唯一索引的 `WHERE` 语义（`uk_exposure_key`/`uk_hedge_plan_no` 均为**普通**唯一约束，引擎**忠实强制**）；
  - 事务仅用于「同事务多点写入」，**未依赖回滚语义**（失败路径断言的是「未落库」，非「回滚还原」）。
- **保留系统行**：`docs/DEPLOY/schema/data-init.sql` 使用 DM 专有 `SET IDENTITY_INSERT`，**本引擎不解析**；
  故 `T-024` 需要的保留系统行（`t_sub_account(id=0)`，作用域哨兵 0 的外键载体）由播种件 `M1E2eSeed` **按等价语义**写入
  （`E5` 要求 SQL 落 `dao` 命名空间，故该类置于 `dao/testsupport/`）。**未执行 data-init.sql 全文**。
- **外部依赖规避**：`SpotService` 估值在「无价」时会走 `findLatestOnOrBefore`（SQL 含 `<=`，**引擎不支持的比较算子**）⇒
  e2e 中为每个敞口基准日**录入当日精确现货价**，使估值走 `findGlobalPrice`（`=` 路径），**不依赖引擎不覆盖项**。

---

## 5. `PROJECT.md` §9 M1 验收方式 —— 逐条对照（**回读原文**，逐字引用含行号）

> **原文出处**：`PROJECT.md` §9「里程碑」表 M1 行的「**验收方式**」列（起草时点行定位 `:561`；该文件后续修订会致行号漂移，判定**以章节锚点为准**）。
> 原文（逐字）：「**数据模型三方会签通过后方可开工**；创建两个子账户 → 分别配置品种与实货敞口 → 产出套保计划；**验证子账户数据隔离**；数据在 DM8 中正确落库」

| § | §9 验收方式条款（`PROJECT.md` §9 M1 行「验收方式」列 原文子句） | 判定 | 证据 / 备注 |
|---|-------------------------------------------|------|-------------|
| ① | **数据模型三方会签通过后方可开工** | **前置已满足**（只读核对） | 依既有门禁留痕：`docs/gates/M1-data-model-signoff/`（`SIGN-OFF.md` + `REAFFIRM-product.md` + `REAFFIRM-test.md` + 三席意见件）。本任务**只读核对**、不重开 M1 门禁 |
| ② | **创建两个子账户 → 分别配置品种与实货敞口 → 产出套保计划** | **❌ 未达成** | 子账户/品种/白名单/合约/三层额度/敞口**均落库**（V-2/V-4，业务码 0）；但「**两子账户各自产出套保计划**」在**同一业务日**被 `BUG-003` 阻断（V-3）。**单子账户**链路可贯通（V-2） |
| ③ | **验证子账户数据隔离** | **✅ 达成** | §7：越权被拒（`10004`）+ 不误拒 + 审计岗 `CROSS_READONLY` 跨账户可读、写面被拒（`T-115` 口径） |
| ④ | **数据在 DM8 中正确落库** | **✅ 达成（等价执行）** | V-4：子账户标识、敞口三分量（且 Σ=合计）、额度三层（层①`SUB_PRODUCT` / 层②`SUB_INSTRUMENT` / 层③`ACCOUNT_PRODUCT` 哨兵 0）逐行断言。**DM8 实机未验**（§4） |

> **§10-34 说明**：`T-024` 追溯所列 `§10-34`（`PROJECT.md` §10「成功标准」相关项）**超出 M1 验收面**（多数属 M2–M4），
> 本报告不展开；仅在此登记以免遗漏追溯。

---

## 6. 执行记录（`TEST_PLAN` 模块用例编号说明）

> `T-024` 属 `TEST_PLAN/INDEX.md` **未登记的端到端验收任务**（非按 `TC-<模块>-<n>` 的模块用例）⇒
> 执行项取 `V-<n>` 编号（**不与 `TC-*` 撞号**），沿用 `docs/TEST_RESULT.md` 既有 `V-*` 体例。

| 用例 | 验证项 | 优先级 | 状态 | 实际结果 | 关联缺陷 |
|------|--------|--------|------|---------|---------|
| V-1 | 基线：`-Punit clean test` | P0 | 通过 | `526/0/0/0`（基线 517 + 本任务 9）；65 类；`arch-check` rc0；`E5` rc0 | — |
| V-2 | 全链路（**单子账户**）：建子账户→品种/白名单/合约/现货价/三层额度→敞口→计划 | P0 | 通过 | 各步 `200/code0`；计划行含 `sub_account_id`+`plan_no`；详情接口返回绑定敞口 | — |
| V-3 | **验收 1 反例（跨子账户 × 同日）** | P0 | **失败（复现缺陷）** | 第二子账户编制 → `500/10022`，根因 `uk_hedge_plan_no` 冲突；计划行不增 | **BUG-003** |
| V-4 | 落库（子账户标识 / 敞口三分量 / 额度三层） | P0 | 通过 | 见 §8 与用例断言 | — |
| V-5 | 隔离·读：A 读 B 的计划 | P0 | 通过 | `400/10001`（不可见=不存在，不泄露存在性） | — |
| V-6 | 隔离·列表：A 的计划列表 | P0 | 通过 | `total=1`，仅 A 自身计划（服务端强制过滤，不因省略筛选越权） | — |
| V-7 | 隔离·审计跨账户读 | P0 | 通过 | 审计岗读 B 的计划 `200`；列表 `total=2`（全量） | — |
| V-8 | 隔离·越权写：A 向 B 编制计划 | P0 | 通过 | `403/10004`；不落库 | — |
| V-9 | 隔离·不误拒 + `CROSS_READONLY` 写面 | P0 | 通过 | A 写自身子账户 `200`；审计岗写 → `403/10004` + 越权留痕 | — |
| V-10 | 变异 M1（写面守卫置空） | — | 通过（杀 2 例） | 见 §9 | — |
| V-11 | 变异 M2（读面去 scope） | — | 通过（杀 1 例） | 见 §9 | — |

---

## 7. 子账户隔离 —— 双向证据（验收 2）

**口径冻结**（`SubAccountVisibility`/`AuthService`）：`CROSS_READONLY`（跨账户只读）**读面放行、写面拒绝**（`T-115`）；判定入口 `AuthService.visibleSubAccountIds`（读 `perm_type`，`ARCHITECTURE.md` §8.2②，含 `perm_type` 语义，非已删除的 `scopeOf`）。

| 方向 | 场景 | 期望 | 实测 | 证据（引擎/响应） |
|------|------|------|------|-------------------|
| **越权被拒（读）** | 用户 A（仅 `LIMITED@A`）读 B 的计划 | 拒绝 | `400 / code 10001` | 不可见 ⇒ `findByIdScoped(sub_account_id IN (A))` 无命中 ⇒ 与不存在同码 |
| **越权被拒（写）** | 用户 A 向 B 编制计划 | 拒绝 | `403 / code 10004` | `assertSubAccountWritable(A, B)` 无命中 ⇒ 拒；计划行数不增 |
| **不误拒** | 用户 A 写自身子账户 A（不同基准日避唯一键） | 放行 | `200 / code 0` | 真实落库 |
| **`CROSS_READONLY` 读面放行** | 审计岗读 B 的计划 / 全量列表 | 放行 | `200`；`total=2` | `visibleSubAccountIds=[0]`（哨兵）⇒ 不构造 scope |
| **`CROSS_READONLY` 写面拒绝** | 审计岗向 A 写敞口 | 拒绝 | `403 / code 10004` | 哨兵 0 **不等于**真实子账户 ⇒ `assertSubAccountWritable` 拒；`t_unauthorized_attempt` 留痕 ≥1 |

> 说明：审计岗的「读面放行」以**计划详情/列表**（`PlanService.getPlan`/`listPlans`，**服务端强制可见性**）为取证点；
> `GET /spot/exposure` 列表端点当前**无**服务端可见性守卫（属 `SpotService.listPendingHedge` 直取），本报告**不以之为隔离证据**（登记为范围外观察，见 §9）。

---

## 8. 落库证据（验收 3；等价执行）

以引擎行集**逐行、逐字段**断言（`setUp` 内建 world）：

| 断言对象 | 内容 | 实测 |
|---------|------|------|
| 子账户标识 | `t_sub_account`（2 业务 + 1 保留系统行 id=0）；`t_sub_product_whitelist` 2 行 | ✅ |
| 子账户归属列 | `t_exposure.sub_account_id` = {A,B}；`t_hedge_plan.sub_account_id` = {A,B}；`t_hedge_plan_exposure` 2 行 | ✅ |
| 敞口**三分量** | `contract_unexecuted_qty` / `inventory_qty` / `production_plan_qty` 逐列值正确，且 **Σ三分量 = `exposure_qty`**（A=100、B=200） | ✅ |
| **额度三层** | 层① `t_product_quota(limit_layer='SUB_PRODUCT', sub_account_id=A)`；层② `t_sub_instrument_quota(sub_account_id=A, instrument_id='cu2612')`；层③ `t_product_quota(limit_layer='ACCOUNT_PRODUCT', sub_account_id=0 哨兵)` | ✅ |
| 计划落库 | `t_hedge_plan` 2 行（`plan_no` 非空）、`t_hedge_plan_exposure` 2 行、`t_plan_state_log` 2 行 | ✅ |

---

## 9. 承重证明 —— 自设变异（≥2）

> 变异**只在隔离副本** `/root/t024iso/{docs,java,tools}` 内做（**未动共享源树**；`TMPDIR=/root`，**未用 `/tmp`**）；
> 变体一律 `System.nanoTime()` 形式的「运行时真」守卫（避 `if(true)` 诱发的 unreachable 编译错），
> 跑毕**逐字节 `cmp` 复位**（`PlanService.java`/`AuthService.java` 与共享树**逐字节相同**）。

| 变异 | 形态（副本内 `java/src/main`） | 期望 | 实测 | 复位 |
|------|-------------------------------|------|------|------|
| **M1** | `AuthService.assertSubAccountWritable` 首行插入 `if (System.nanoTime() >= 0) { return; }`（**写面守卫置空**） | 写面拒绝用例必红 | **`Failures: 2`** — `userACannotWriteToSubAccountB`、`crossReadonlyAuditCannotWrite` | ✅ `cmp` 一致 |
| **M2** | `PlanService.getPlan` 首行插入 `if (System.nanoTime() >= 0) { return hedgePlanDao.findById(planId); }`（**读面去 scope**） | 隔离读用例必红 | **`Failures: 1`** — `userACannotReadSubAccountBPlan` | ✅ `cmp` 一致 |

> ⇒ e2e 的隔离断言**真承重**：既非「只看装配层」，亦非「假绿」。基线（副本内）9/9 绿；两变异逐处**精准**击落对应用例（无多杀、无漏杀）。

---

## 10. 度量与覆盖核对

| 指标 | 值 |
|------|----|
| `-Punit`（surefire） | **526 / 0 / 0 / 0**（基线 517 + 本任务 9）；测试类 65（基线 64 + 1） |
| 源码 `@Test` 数 == surefire 用例数 | **526 == 526**（一致；本项目有「注释吞并 `@Test` 致 `grep` 虚高」前科，故以 surefire 名单为准复核） |
| 聚合 `arch-check` | rc **0**（E1–E7 全 0） |
| `E5`（dao 外无 SQL） | rc **0**（`M1E2eSeed` 的 SQL 置于 `dao` 命名空间以合规） |
| `java/src` 树锚 | `82dd4419f831e79431cd5f9655eddfd9` / **342**（基线 `9c6a101b…` / 340） |
| `docs/TEST_PLAN` 树锚 | 基线 `21451792a1883d4edce75bd257dc859d` / **14** ⇒ 本报告加入后 **/ 15**（**自指**：锚点含本报告自身内容，故不写死哈希；现测口径 `find docs/TEST_PLAN -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum`） |

**覆盖对照（`TEST_PLAN`）**：`T-024` 未被 `TEST_PLAN/*.md` 登记为 `TC-*` 用例（端到端验收任务），故不并入 `INDEX §6` 追溯矩阵；
本报告以 `V-1`–`V-11` 自证覆盖。**`java/src/main` 覆盖不变**（本任务零改动主代码）。

---

## 11. 诚实披露清单（**不得把未达成写成已达成**）

1. **验收 1 = 未达成**：P0 `BUG-003`（`plan_no` 跨子账户冲突）阻断「两子账户同日各自产出计划」。已固化反例（V-3）并投缺陷单。
2. **DM8 实机未验**：全程以内存假 JDBC 引擎**等价执行**；「数据在 DM8 中正确落库」的判定**限定于等价执行**，实机复跑由部署方在可达实例上进行。
3. **UI 级不在本任务范围**：只覆盖到 REST 装配级；前端页面 `T-019`/`T-020`/`T-021` 并行开发中。
4. **引擎不覆盖项**：条件唯一索引 `WHERE`、JOIN/聚合（静默错值）、子查询（loud 抛）、多列外键、事务回滚——本链路**不依赖**（§4）；唯分页 `count()` 的 `COUNT_WRAPPER` 子查询+聚合属引擎**受支持**形态、非上述不覆盖项。
5. **e2e 为隔离/落库构造 world 时**：子账户 B 的计划以**次一业务日窗口**创建（**规避 `BUG-003`**，勿读作「两子账户同日成功」）；验收 1 的「同日」场景由 V-3 **如实复现**。此规避已在用例内注明。
6. **范围外观察（登记，不并入本验收判定）**：
   - `GET /api/v1/spot/exposure`（列表）经 `SpotService.listPendingHedge` **直取**，**未过服务端子账户可见性守卫**（读面越权面；非本任务范围）。
   - `BizNoGenerator.retryOnUniqueConflict`（§3.5「冲突重试最多 3 次」）在 `PlanService.createPlan` **未接线**（`BUG-003` 影响面已列）。
7. **会话/登录**：以直接注入 `SessionStore` 的会话驱动（`SessionAuthFilter` 未注入续期器）；登录/口令/滑动续期链路不在本任务。
8. **只读声明**：`java/src/main/**` 零改动；未改 `web/**`、`docs/TASKS.md`、`docs/BOARD.md`、`docs/REQUIREMENTS/**`、`docs/TEST_RESULT.md`、`cpp/**`。
9. **状态**：本任务**未自评 `DONE`**——状态为「**已完成（待独立复核）**」。

---

## 12. 落笔证明 / 只读声明

- **落笔前**（本报告写入前）：`docs/TEST_PLAN/` 树锚 `21451792a1883d4edce75bd257dc859d` / 14 文件。
- **本报告**：`docs/TEST_PLAN/M1-report.md`（本档）。**落笔后**以实时 `md5sum`/`wc -l` 为准（活跃制品，勿引固定值）。
- **只读声明**：未改 `java/src/main/**`（`git status --short java/src/main` = 0）；未改 `web/**`（`web/js/app.js` 的改动**非本席**）、`docs/TASKS.md`、`docs/BOARD.md`、`docs/REQUIREMENTS/**`、`docs/TEST_RESULT.md`、`cpp/**`。
- **隔离副本**：`/root/t024iso/`（变异跑毕逐字节复位、`cmp` 校验；**未用 `/tmp`**）；备份 `/root/t024-M1-AuthService.orig`、`/root/t024-M2-PlanService.orig`（仓库外）。
- **未自评 `DONE`**。
