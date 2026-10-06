# 测试计划索引

> 负责人：测试经理 ｜ 阶段：S5 ｜ 版本：v0.1

- **项目**：企业期货套期保值管理系统（HedgeGuard）
- **编写人**：测试经理
- **编写时间**：2026-09-20
- **状态**：草稿

> 📁 **本文件是测试计划的入口**：承载**全局性内容**——目标范围、依据、策略、环境、用例总表、追溯矩阵、准入准出、缺陷管理、度量、风险。
> **模块用例**见各 `docs/TEST_PLAN/<代号>.md`；**边界/异常**见 `BOUNDARY.md`；**非功能**见 `NONFUNCTIONAL.md`。**本文件不写用例正文**。

---

## 1. 测试目标与范围

### 1.1 目标

验证 HedgeGuard 按 `PROJECT.md` §1「敞口 → 方案 → 交易 → 会计」合规闭环正确实现，**核心目标是发现缺陷并确保设计与实现一致**。重点验证：
① 子账户贯穿与加总对账（`Σ 子账户 = 真实账户`）；
② 三级风控的合规红线（规模/期限/时点净持仓/三层限额/价格偏离）；
③ 双组件架构红线（C++ 零 DB、事前风控全内存零 I/O、单一写库入口）；
④ 零落盘与岗位分离等安全硬约束；
⑤ CAS 24 套期会计与全链路审计追溯。

### 1.2 范围

| 包含 | 说明 |
|------|------|
| 功能性测试 | 11 个模块 183 条 `FR`（`PRODUCT`14 / `SPOT`18 / `PLAN`20 / `TRADE`18 / `SETTLE`17 / `RISK`19 / `AUDIT`13 / `ACCT`14 / `FUND`17 / `AUTH`18 / `CONFIG`15） |
| 边界与异常测试 | 空值/超长/超限/非法格式/超时/依赖失败/并发/重复提交/资源耗尽/崩溃恢复 |
| 非功能测试 | 50 条 `NFR`（性能/可靠性/安全/兼容/可维护/可测试/易用/可部署） |
| 端到端测试 | `REQUIREMENTS/INDEX.md` §5 的 6 条核心流程 + `FLOW-001`（`FLOWS.md`） |
| 协议专项测试 | `ARCHITECTURE.md` §4.2.7 `T1`–`T11`（`NFR-TEST-5`） |
| 数据层测试 | 约束/索引是**用例设计输入**（唯一约束→重复插入；非空→空值；外键→孤儿数据；`CHECK`→取值域边界） |

### 1.3 不测范围

| 排除项 | 理由 |
|--------|------|
| 投机/自营交易 | `PROJECT.md` §4.2 明确排除（永久不做）——系统不提供该能力，无从测 |
| 多租户 SaaS、移动端 App | 本期不含（`INDEX.md` §7）；无对应入口 |
| 行情分析/预测/投研功能 | `PROJECT.md` §4.2 排除；系统不做市场判断 |
| 自动交易 | 系统**不提供**自动下单入口（`R-TRADE-2`/`R-RISK-13`）——测"不存在"，见 `TC-TRADE-3`/`TC-RISK-26` |
| 境外/场外衍生品 | 本期仅境内场内（`R-TRADE-16`） |
| 第三方现货资讯源对接 | 系统零外部数据依赖（`NFR-COMPAT-7`） |
| 完整 ERP、银企直连、组件间 JNI 同进程 | `INDEX.md` §7 排除 |
| CTP **实盘**资金验证 | 用 CTP 模拟环境 + `ICtGateway` 桩 / `InMemoryTradingBridge` 桩（`ARCHITECTURE.md` §8.4）；实盘不在本期 |
| 外部会计顾问复核 | `PROJECT.md` §6：低优先级、不阻塞交付（文档标注"待外部复核"，见 `TC-ACCT-15`） |

> ⚠️ 不测范围**已逐项写明理由**，无静默省略。

---

## 2. 用例总表

> ⚠️ **本表是测试用例文件的唯一事实源**。**模块代号全部取自 `REQUIREMENTS/INDEX.md` §2 模块登记表**，**未新建、未改名、未自造**。
> 扫描口径：`ls docs/TEST_PLAN/*.md` + `grep -cE '^### TC-<代号>-'`（逐模块计数）。

| # | 模块代号 | 模块名 | 用例文件 | 用例数 | 状态 |
|---|---------|--------|---------|-------|------|
| 1 | `PRODUCT` | 品种管理 | `docs/TEST_PLAN/PRODUCT.md` | 16 | 已写 |
| 2 | `SPOT` | 现货管理 | `docs/TEST_PLAN/SPOT.md` | 23 | 已写 |
| 3 | `PLAN` | 套保计划 | `docs/TEST_PLAN/PLAN.md` | 24 | 已写 |
| 4 | `TRADE` | 交易执行 | `docs/TEST_PLAN/TRADE.md` | 25 | 已写 |
| 5 | `SETTLE` | 结算 | `docs/TEST_PLAN/SETTLE.md` | 22 | 已写 |
| 6 | `RISK` | 风险控制 | `docs/TEST_PLAN/RISK.md` | 31 | 已写 |
| 7 | `AUDIT` | 审计 | `docs/TEST_PLAN/AUDIT.md` | 16 | 已写 |
| 8 | `ACCT` | 套保会计 | `docs/TEST_PLAN/ACCT.md` | 16 | 已写 |
| 9 | `FUND` | 资金管理 | `docs/TEST_PLAN/FUND.md` | 20 | 已写 |
| 10 | `AUTH` | 用户与权限 | `docs/TEST_PLAN/AUTH.md` | 22 | 已写 |
| 11 | `CONFIG` | 系统配置 | `docs/TEST_PLAN/CONFIG.md` | 18 | 已写 |
| — | — | **边界与异常** | `docs/TEST_PLAN/BOUNDARY.md` | 28（`TC-BOUND`16 + `TC-EXC`12） | 已写 |
| — | — | **非功能** | `docs/TEST_PLAN/NONFUNCTIONAL.md` | 50 | 已写 |
| **—** | **—** | **合计** | — | **311** | — |

> **不是每个需求模块都必须有用例文件**——本期 11 个模块**均有独立用例文件**（每个模块都有被测 FR）。
> **每个 `FR` / `NFR` 都必须被某条用例覆盖**（见 §6 追溯矩阵）。
> **里程碑视图（M1）**：M1 相关用例索引、数据模型约束→用例映射、子账户隔离 / 敞口三分量 / 限额三层 / 假 DAO 档，见 **§16**。

### 2.1 用例拆分规则核对

| 情形 | 判定 | 处理 |
|------|------|------|
| 单模块用例 ≤ 20 条 | `PRODUCT`(16) `AUDIT`(16) `ACCT`(16) `FUND`(20) `CONFIG`(18) | 单文件，未触发拆分 |
| 单模块用例 > 20 条 | `SPOT`(23) `PLAN`(24) `TRADE`(25) `SETTLE`(22) `RISK`(31) `AUTH`(22) | **未拆模块**——原因为**模块代号不可自造**（§4.2 红线），且各模块 **FR 均 ≤20**（`INDEX.md` §2.3）。这些模块用例多，是同类 FR 的**多场景覆盖**（如 `RISK` 三层限额 7 条），非模块边界过粗。**结论：无需拆模块、无需二级文件** |
| `BOUNDARY.md` > 12 条 | 28 条 | **文件不变**，内部按「边界值 / 异常路径」分节 |
| `NONFUNCTIONAL.md` > 12 条 | 50 条 | **文件不变**，内部按 8 类 NFR 分节 |

> ⚠️ **说明**：`SPOT`(23)、`RISK`(31) 等超 20 条的模块，本可"拆模块"，但**模块代号是需求侧登记的锚点**，测试侧**不得自造子模块代号**（§4.2）。该情形下**保持单文件**是正确处置——顺序号在本模块内连续递增即可自证归属。已在各模块文件 §1 说明。

**量化依据（M/N = 用例数 / 模块 FR 数）**——供后来者无需重新推导：

| 模块 | FR 数（N） | TC 数（M） | **M/N** | 触发"拆模块"线？ |
|------|-----------|-----------|---------|----------------|
| `PRODUCT` | 14 | 16 | 1.14 | 否 |
| `SPOT` | 18 | 23 | 1.28 | 否 |
| `PLAN` | 20 | 24 | 1.20 | 否 |
| `TRADE` | 18 | 25 | 1.39 | 否 |
| `SETTLE` | 17 | 22 | 1.29 | 否 |
| `RISK` | 19 | 31 | **1.63** | 否 |
| `AUDIT` | 13 | 16 | 1.23 | 否 |
| `ACCT` | 14 | 16 | 1.14 | 否 |
| `FUND` | 17 | 20 | 1.18 | 否 |
| `AUTH` | 18 | 22 | 1.22 | 否 |
| `CONFIG` | 15 | 18 | 1.20 | 否 |

> **判读**：① **触发"拆模块"的是模块 FR 数**（`INDEX.md` §2.3：单模块 FR > 20 才拆），**本期最大 FR 数 = `PLAN` 20，全部 ≤20 ⇒ 均未触发**；② `M/N` 反映**用例密度**（多为多场景/正反/边界覆盖），**与模块粒度无关**——`RISK` 的 M/N=1.63 最高，因三层限额（7 条）、价格偏离（3 条）等合规红线需**逐层级 / 逐边界**建例，属**必要的覆盖深度**，非模块边界过粗；③ 因此 `M > 20` 是"M 高"而非"N 高"，**不构成拆模块理由**。

### 2.2 既有 JUnit 用例 → TC 编号 映射覆盖率

> **依据**：`java/src/test/java/**` 共 **34 个测试类 / 272 个 `@Test`**（扫描口径：`grep -rn '@Test' java/src/test --include=*.java | wc -l` = 272）。
> 本表把既有 JUnit **映射回 `TC-` 编号**，避免另起一套。

| 映射层级 | JUnit 数 | 说明 | 代表类 |
|---------|---------|------|--------|
| **可追到具体模块 `TC-<模块>-<序号>`** | **174** | 与某个 FR 用例一一对应（服务层/DAO/契约层） | `VarietyServiceTest`(13)→`PRODUCT`；`SpotServiceTest`(15)→`SPOT`；`PlanServiceTest`(11)→`PLAN`；`AuthServiceTest`(12)/`PasswordHasherTest`(5)/`M1AuthDaoImplTest`(5)/`SubAccountScopeTest`(6)→`AUTH`；`ConfigServiceTest`(15)→`CONFIG`；`ParamDispatchServiceImplTest`(15)→`RISK`/`CONFIG`；`TradingBridgeContractTest`(8)→`TRADE`；`WsServerTest`(14)/`WsConnectionTest`(8)/`WsAuthorizerTest`(11)→`TRADE`/`AUTH`；`SensitiveLoggingAndConfigTest`(10)→`AUTH`/`CONFIG`；`RequestDispatcherTest`(10)→`AUTH`；`M1ProductSpotPlanSchemaTest`(4)→`PRODUCT`/`SPOT`/`PLAN`；`PageQueryTest`(5)→`TC-BOUND-1`；`ConfigAuthSchemaTest`(5)、`RiskParamDispatchSchemaTest`(2) |
| **可追到 NFR 类别 / 架构判据 TC** | **98** | 基础设施/跨切面测试，映射到 `TC-SEC/REL/MAINT/COMPAT/TEST` 或架构判据（`E1`–`E6`），**不指向单一 FR** | `TxTemplateTest`(8)→`TC-REL-3`；`DataSourcePoolTest`(8)→性能/可靠性；`DmDialectTest`(6)→`TC-COMPAT-5`；`ErrorMapperTest`(6)→`TC-USE-3`；`RouterTest`(9)/`ModuleRoutesConsistencyTest`(2)/`StaticHandlerTest`(5)→`TC-MAINT-5`；`WsFrameCodecTest`(19)/`WsHandshakeTest`(11)→`TC-TEST-5`（`RSK-06`）；`AuditFieldsTest`(7)/`BizNoGeneratorTest`(7)/`DaoColumnAlignmentTest`(3)/`M1DaoMappingAndWriteTest`(5)/`M1DaoReadAndClobTest`(2)→`TC-MAINT-3` |
| **无法追溯** | **0** | 无 | — |
| **合计** | **272** | — | — |

> **映射覆盖率**：174 / 272 = **64.0%** 可追到具体模块 FR 的 `TC-` 编号；其余 98 / 272 = **36.0%** 为基础设施/契约层，已归入 NFR 类别与架构判据。
> **0 个无法追溯**——每个 JUnit 都有归属（要么 FR TC，要么 NFR/判据 TC）。
> 复核命令：`grep -rn '@Test' java/src/test --include=*.java | wc -l`（应得 272）。
> ⚠️ **时点边界（`T-022` 补注，2026-09-23）**：本表为 **2026-09-20 冻结点快照**；自 **2026-09-23** 起 `T-023` 正在新增 `java/src/test/**` 用例（本席当日实测 `@Test` 总数已 **> 272**）⇒ 上述「应得 272」**仅对该时点成立**，**现行值须以实时复算为准**；本表映射分析**未随新用例重算**（待 `T-023` 闭锁后统一刷新）。本注**只加不改**——`272` / `174` / `98` 等数字**均未改动**。

### 2.3 接口面覆盖的分母口径（⚠️ 专题：断言清单 ≠ 全量清单）

> **本条的由来**：本轮出现一次基线口径错误——把 `ModuleRoutesConsistencyTest` 里枚举的 **6 条** product 路由当成了"product 只有 6 条"。
> 性质 = **把测试的「下限断言清单」当成了被断言对象的「事实全量清单」**。**这类陷阱对"路由一致性 / 接口覆盖"类用例设计是致命的**（分母偏小 ⇒ 覆盖率虚高、漏测真实路径）。

**纪律（本测试计划强制）**：

| 项 | 规则 |
|----|------|
| **接口面唯一权威** | `java/src/main/java/com/hedgeguard/web/ApiRoutes.java`（**冻结的注册路径清单**）。**覆盖分母一律取自它**，**不得**取自任何测试文件里的枚举/常量表 |
| **断言清单的定位** | `ModuleRoutesConsistencyTest` 等测试文件中的路径枚举是**下限断言**（"至少这些存在"），**不得反向当作总量** |
| **写数字必标口径** | 若必须引用路由数，须写明口径：**「`(方法, 路径)` 对」** 与 **「路径」** 两个口径**不可混用**。**当前最优口径 = 119 个 `(方法, 路径)` 对 / 89 个路径**（运行期实测：以生产装配同构方式直调全部已交付模块注册器 + 平台级 `ping`，读 `Router.routes()` 后按方法去重计「对」、按路径去重计「路径」；实测日期 **2026-10-06**，权威源 `java/src/main/java/com/hedgeguard/web/ApiRoutes.java`（md5 `8d285a2a21d5d6e20a64e90b9651a83c`），实测对集合与 `ApiRoutes.allRoutes()` **等值**）。按「对」计：业务 117 = `AUTH 17 / CONFIG 18 / PRODUCT 13 / SPOT 15 / PLAN 18 / RISK 3 / TRADE 3 / SETTLE 15 / FUND 15`，+ `ping` 的 `GET`+`HEAD` 2 = **119**；按「路径」计：业务去重 88 + `ping` 1 = **89**。**前一口径（`T-063` 前，`RISK` 仅 `GET /risk/mirror-status` 1 对）**：117 个 `(方法, 路径)` 对 / 88 个路径（业务 115 = 上列各模块之 `RISK 1` 版 + `ping` 2 = 117；业务去重 87 + `ping` 1 = 88）——**该拆解已被 `T-063` 取代，勿当作现值**。其中 `PLAN` 的部分对来自**循环注册**（`PlanActionHandler.Action.values()` / `PlanRatioHandler.Method.values()`），故**不可凭源码 `register(` 处数推断对数**。**历史口径（2026-09-20 时点，`T-097` 前，仅 5 个模块交付）**：47 个 `(方法, 路径)` 对 / 37 个路径（`AUTH 2 / CONFIG 4 / PRODUCT 13 / SPOT 10 / PLAN 16 = 45` + `ping` 的 `GET`+`HEAD` 2 = 47；其中 `PLAN` 的 11 对来自循环注册 `PlanActionHandler.Action.values()` 8 + `PlanRatioHandler.Method.values()` 3）——**该拆解为当时快照，勿当作现值** |
| **本期用例现状** | 本计划**未以路由总数做任何覆盖率分母**（用例按 FR/NFR 追溯，见 §6）；接口契约面由 `TC-TEST-5`（协议专项）与 NFR（`TC-MAINT-5` 前端隔离）承载。新增"接口覆盖"用例时，分母**必须**来自 `ApiRoutes.java` 实际清单 |

> 关联：`TC-MAINT-5`（前端隔离）、`TC-TEST-5`（`T1`–`T11`）；`INDEX.md` §2.2 中 `ModuleRoutesConsistencyTest`(2) 的映射**仅指其下限断言**，不代表接口面全量。

---

## 3. 测试依据

| 文档 | 章节 | 用途 |
|------|------|------|
| `docs/REQUIREMENTS/INDEX.md` | §2 模块登记表 | **模块代号来源**（用例归属） |
| `docs/REQUIREMENTS/INDEX.md` | §5 业务流程（6 条 + `FLOW-001`） | **端到端用例的起点** |
| `docs/REQUIREMENTS/INDEX.md` | §9.1 答复记录（`Q-1`～`Q-17`） | **阻断类用例期望值的唯一事实源**（v1.1 口径） |
| `docs/REQUIREMENTS/<代号>.md` | 全部 | 模块需求与验收标准 |
| `docs/REQUIREMENTS/NONFUNCTIONAL.md` | 全部 | 50 条 NFR 指标 |
| `docs/ARCHITECTURE.md` | **编写时点 v1.5.4**（`2624` 行）；**门禁复核时以实时 `md5sum` 为准**。§4.1.2 / §4.2.7 / §4.3 / §8.1.2 / §8.4 / §8.9 / §9 | 接口契约、协议专项 `T1`–`T11`、NFR 手段、打桩方案、未闭环风险 |
| `docs/DATA_MODEL.md` | **编写时点 v1.3.3**（`5245` 行）；**门禁复核时以实时 `md5sum` 为准**。§4.4 / §4.6 / 附录 C | 约束/索引（用例设计输入）、无法由 DB 保证的规则、子账户贯穿核对 |
| `docs/TASKS.md` | 全部 | 了解实现范围 |

---

## 4. 测试策略

### 4.1 测试层级

| 层级 | 目标 | 责任方 | 覆盖目标 |
|------|------|--------|---------|
| 单元测试 | 验证函数/类级逻辑 | 开发工程师 | 核心算法（套保比率三档、风控判定、凭证生成）、边界、异常分支。既有 272 JUnit + C++ `ctest`。目标：`NFR-TEST-1` 覆盖率 ≥80% |
| 集成测试 | 验证模块间契约 | 开发 + 测试 | 接口字段、错误码（§4.3）、事件流不丢不乱序、DAO↔DDL 对齐 |
| 系统测试 | 验证端到端业务流 | 测试工程师 | §5 的 6 条核心流程 + `FLOW-001`；P0 完整链路 |
| 非功能测试 | 验证 NFR 指标 | 测试工程师 | 性能、并发、安全、兼容性（`NONFUNCTIONAL.md`） |
| 协议专项测试 | 验证 HGCP 协议 | 测试工程师 | `T1`–`T11`（§4.2.7），C++ 侧 `ctest -L protocol` + Java 侧集成，两侧互跑 |
| 回归测试 | 防止修复引入新缺陷 | 测试工程师 | 已修复缺陷 + 关联功能 |

### 4.2 协议专项测试 `T1`–`T11` 落点

> 来源：`ARCHITECTURE.md` §4.2.7（`NFR-TEST-5`）。**每条 → 对应 `TC-`**：

| # | 测试 | 架构判据 | 落点用例 |
|---|------|---------|---------|
| `T1` | 不丢 | 连续 1000 事件，Java 接收 = 1000 | `TC-TRADE-21`（+`TC-PERF-6`） |
| `T2` | 不乱序 | 1000 事件 `SEQ` 与业务字段逐条一致 | `TC-TRADE-21`（+`TC-PERF-6`） |
| `T3` | 断连重连补数 | 断连 5 s 内 200 事件 → 重连全补齐无重复落库 | `TC-EXC-3` |
| `T4` | 缓冲越界降级 | 缺口 > 65536 帧 → `0x0306` → 全量重建；期间指令被拒 `0x0401` | `TC-BOUND-15` |
| `T5` | 心跳与检出 | 静默一侧 → 对侧 ≤5 s 检出断连 | `TC-EXC-3`（+`TC-REL-2`） |
| `T6` | 错误码 | 逐条构造 §4.3.2 每类错误码，返回码符合期望 | `TC-RISK-2`/`TC-RISK-3`/`TC-RISK-4`（组件码 `0x1004`/`0x1017`/`0x1005`）+ `TC-TRADE-6` |
| `T7` | 鉴权 | 错误/缺失令牌/版本不符 → 拒绝 + 关连接 + 告警 | `TC-SEC-3` |
| `T8` | 回环绑定 | `ss -lntp` 绑定 `127.0.0.1`；配 `0.0.0.0` 拒绝启动 | `TC-CONFIG-18`（+`TC-SEC-3`） |
| `T9` | `param_version` 单调 | 连续两次变更（旧版本后到）→ C++ 最终值 = 最后一次 | `TC-RISK-21` |
| `T10` | 大消息分片 | 预加载 10 万条额度，分片后全部生效，条数一致 | `TC-BOUND-14` |
| `T11` | 性能 | 通道 A 吞吐 ≥1000 帧/秒（`NFR-PERF-6`）；判定 P99 ≤5 ms（`NFR-PERF-1`） | `TC-PERF-6`（+`TC-PERF-1`） |

> **覆盖**：`T1`–`T11` **全部有落点**，无遗漏。

### 4.2.1 `RSK-06` / `RSK-10` 未闭环风险的验证点预留

| 风险 | 影响 | 预留验证点 |
|------|------|-----------|
| `RSK-06`（自实现 WebSocket，A′） | `NFR-PERF-5` P95 ≤200 ms 不达标 | `TC-PERF-5`（端到端延迟采样 ≥1000）；`TC-TEST-5`（`WsFrameCodecTest`/`WsHandshakeTest` 离线单测兜底 RFC 6455 正确性） |
| `RSK-10`（重放期在途指令 CTP 查询不可用） | 重放无法判定在途指令真实状态 | `TC-EXC-5`（记为"状态未知"、不释放冻结额度、告警并要求人工介入、期间继续拒绝新指令） |

### 4.3 用例设计方法

| 方法 | 应用点 |
|------|--------|
| 等价类划分 | 输入分有效/无效类（如白名单内/外品种） |
| 边界值分析 | 规模红线 90%/80%、价格偏离 2%/5%、期限阈值、数量上下限、口令长度、失败次数 |
| 场景法 | 6 条核心业务流程 + `FLOW-001` 端到端 |
| 错误推测 | 并发下单、重复提交、回报幂等、断连、崩溃、越权 |
| 状态迁移 | 交易指令状态机、计划审批链、核对任务签署状态 |
| 正交组合 | 三层限额组合（层①②③超/不超）、岗位互斥组合、期权多腿 |

---

## 5. 测试环境

| 项 | 配置 |
|----|------|
| 操作系统 | Ubuntu 22.04 LTS（`NFR-COMPAT-4`） |
| 构建方式 | C++：CMake ≥3.16 + FetchContent（`CTP_USE_FETCH_DEPS` 双轨）；Java：Maven（JDK 11） |
| 部署地址 | 同机双进程（C++ `hg-trading` + Java `hg-biz`），**非 JNI**；本地 TCP 回环 `127.0.0.1` |
| 依赖服务 | 达梦 DM8（标准 JDBC）；CTP 柜台（模拟环境 + 桩可替）；无外网/无 MQ/无 Redis |
| 测试数据 | 各模块造数脚本（1 万条流水/凭证/成交、10 子账户、10 万条额度） |
| 桩件 | C++ `ICtGateway`（`MockCtGateway` 可脚本化回报）；Java `TradingBridge`（`InMemoryTradingBridge`）；内存假 DAO；可注入 `Clock` |

**环境准备步骤**：

```bash
# 1) 建库（前置，由 DBA 执行；应用账号仅 SELECT/INSERT/UPDATE）
# 2) C++ 组件（无 DB 依赖）
cd cpp && cmake -B build -DCMAKE_CXX_STANDARD=11 && cmake --build build
ctest --test-dir build -L unit      # 单元
ctest --test-dir build -L protocol  # 协议专项 T1–T11（需 Java 侧配合/桩）
# 3) Java 业务组件
cd java && mvn -q test              # unit 档（无外部依赖，含 272 JUnit）
mvn -q verify -Pit                  # it 档（真实 DM8 + HGCP 端到端）
# 4) 端到端冒烟
tools/smoke/run.sh                  # 录敞口→计划→审批→下单→成交→结算→凭证，连跑 3 次
```

---

## 6. 需求追溯矩阵

> **双向核对**：正向查"需求有没有用例覆盖"，反向查"用例有没有需求来源"。

### 6.1 正向：需求 → 用例

> **覆盖判定口径**：✅ = 有 ≥1 条对应用例；❌ = 无对应用例（须写理由与处理方式）。
> 扫描命令：各模块文件 §4「模块用例覆盖率」+ 本表逐条核对。

#### 6.1.A `PRODUCT`（14 条）

| 需求编号（含文件归属） | 需求描述 | 对应用例（含文件归属） | 覆盖状态 |
|---------------------|---------|---------------------|---------|
| `FR-PRODUCT-1 @ REQUIREMENTS/PRODUCT.md` | 新增品种 | `TC-PRODUCT-1, TC-PRODUCT-15 @ TEST_PLAN/PRODUCT.md`；`TC-BOUND-7` | ✅ |
| `FR-PRODUCT-2 @ REQUIREMENTS/PRODUCT.md` | 修改品种 | `TC-PRODUCT-2, TC-PRODUCT-15 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-3 @ REQUIREMENTS/PRODUCT.md` | 停用品种 | `TC-PRODUCT-3, TC-PRODUCT-16 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-4 @ REQUIREMENTS/PRODUCT.md` | 启用品种 | `TC-PRODUCT-4 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-5 @ REQUIREMENTS/PRODUCT.md` | 查询品种列表 | `TC-PRODUCT-5 @ TEST_PLAN/PRODUCT.md`；`TC-BOUND-1` | ✅ |
| `FR-PRODUCT-6 @ REQUIREMENTS/PRODUCT.md` | 查看品种详情 | `TC-PRODUCT-6 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-7 @ REQUIREMENTS/PRODUCT.md` | 导入品种 | `TC-PRODUCT-7 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-8 @ REQUIREMENTS/PRODUCT.md` | 导出品种 | `TC-PRODUCT-8 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-9 @ REQUIREMENTS/PRODUCT.md` | 维护品种-合约关联 | `TC-PRODUCT-9 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-10 @ REQUIREMENTS/PRODUCT.md` | 维护品种-现货品类映射 | `TC-PRODUCT-10 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-11 @ REQUIREMENTS/PRODUCT.md` | 配置额度指标与分类标记 | `TC-PRODUCT-11 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-12 @ REQUIREMENTS/PRODUCT.md` | 按子账户配置白名单 | `TC-PRODUCT-12, TC-PRODUCT-16 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-13 @ REQUIREMENTS/PRODUCT.md` | 维护计量单位与换算关系 | `TC-PRODUCT-13 @ TEST_PLAN/PRODUCT.md` | ✅ |
| `FR-PRODUCT-14 @ REQUIREMENTS/PRODUCT.md` | 记录品种变更历史 | `TC-PRODUCT-14 @ TEST_PLAN/PRODUCT.md` | ✅ |

#### 6.1.B `SPOT`（18 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-SPOT-1 @ REQUIREMENTS/SPOT.md` | 新增采购合同 | `TC-SPOT-1 @ TEST_PLAN/SPOT.md` | ✅ |
| `FR-SPOT-2 @ REQUIREMENTS/SPOT.md` | 修改采购合同 | `TC-SPOT-2` | ✅ |
| `FR-SPOT-3 @ REQUIREMENTS/SPOT.md` | 作废采购合同 | `TC-SPOT-3` | ✅ |
| `FR-SPOT-4 @ REQUIREMENTS/SPOT.md` | 查询采购合同列表 | `TC-SPOT-4` | ✅ |
| `FR-SPOT-5 @ REQUIREMENTS/SPOT.md` | 新增销售合同/订单 | `TC-SPOT-5` | ✅ |
| `FR-SPOT-6 @ REQUIREMENTS/SPOT.md` | 修改销售合同 | `TC-SPOT-6` | ✅ |
| `FR-SPOT-7 @ REQUIREMENTS/SPOT.md` | 查询销售合同列表 | `TC-SPOT-7` | ✅ |
| `FR-SPOT-8 @ REQUIREMENTS/SPOT.md` | 维护生产计划与在制品 | `TC-SPOT-8` | ✅ |
| `FR-SPOT-9 @ REQUIREMENTS/SPOT.md` | 维护库存台账 | `TC-SPOT-9` | ✅ |
| `FR-SPOT-10 @ REQUIREMENTS/SPOT.md` | 手工录入现货价格 | `TC-SPOT-10, TC-SPOT-20`；`TC-BOUND-10` | ✅ |
| `FR-SPOT-11 @ REQUIREMENTS/SPOT.md` | 批量粘贴录入现货价 | `TC-SPOT-11`；`TC-EXC-12` | ✅ |
| `FR-SPOT-12 @ REQUIREMENTS/SPOT.md` | CSV 导入现货价 | `TC-SPOT-12`；`TC-EXC-12` | ✅ |
| `FR-SPOT-13 @ REQUIREMENTS/SPOT.md` | 查询现货价序列 | `TC-SPOT-13` | ✅ |
| `FR-SPOT-14 @ REQUIREMENTS/SPOT.md` | 计算实货敞口 | `TC-SPOT-14, TC-SPOT-15, TC-SPOT-20, TC-SPOT-21, TC-SPOT-22, TC-SPOT-23`；`TC-BOUND-9` | ✅ |
| `FR-SPOT-15 @ REQUIREMENTS/SPOT.md` | 查询实货敞口列表与详情 | `TC-SPOT-16` | ✅ |
| `FR-SPOT-16 @ REQUIREMENTS/SPOT.md` | 指定实货敞口子账户归属 | `TC-SPOT-17` | ✅ |
| `FR-SPOT-17 @ REQUIREMENTS/SPOT.md` | 维护现货价来源标注 | `TC-SPOT-18` | ✅ |
| `FR-SPOT-18 @ REQUIREMENTS/SPOT.md` | 导出实货敞口 | `TC-SPOT-19` | ✅ |

> 其余 `SPOT` 用例均在 `TEST_PLAN/SPOT.md`。

#### 6.1.C `PLAN`（20 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-PLAN-1 @ REQUIREMENTS/PLAN.md` | 新增套保计划 | `TC-PLAN-1 @ TEST_PLAN/PLAN.md` | ✅ |
| `FR-PLAN-2` | 修改套保计划（草稿） | `TC-PLAN-2` | ✅ |
| `FR-PLAN-3` | 删除套保计划（草稿） | `TC-PLAN-3` | ✅ |
| `FR-PLAN-4` | 查询套保计划列表 | `TC-PLAN-4` | ✅ |
| `FR-PLAN-5` | 查看套保计划详情 | `TC-PLAN-5` | ✅ |
| `FR-PLAN-6` | 简单比率法 | `TC-PLAN-6` | ✅ |
| `FR-PLAN-7` | 最小方差法 | `TC-PLAN-7`；`TC-BOUND-8` | ✅ |
| `FR-PLAN-8` | 自定义规则法 | `TC-PLAN-8` | ✅ |
| `FR-PLAN-9` | 生成套期关系书面文件 | `TC-PLAN-9, TC-PLAN-21` | ✅ |
| `FR-PLAN-10` | 提交套保计划审批 | `TC-PLAN-10, TC-PLAN-21` | ✅ |
| `FR-PLAN-11` | 审批套保计划 | `TC-PLAN-11, TC-PLAN-22` | ✅ |
| `FR-PLAN-12` | 驳回套保计划 | `TC-PLAN-12` | ✅ |
| `FR-PLAN-13` | 撤销套保计划 | `TC-PLAN-13` | ✅ |
| `FR-PLAN-14` | 分解生成交易计划 | `TC-PLAN-14, TC-PLAN-24` | ✅ |
| `FR-PLAN-15` | 查询交易计划 | `TC-PLAN-15` | ✅ |
| `FR-PLAN-16` | 挂起/冻结与解冻 | `TC-PLAN-16` | ✅ |
| `FR-PLAN-17` | 终止套保计划 | `TC-PLAN-17` | ✅ |
| `FR-PLAN-18` | 计划期限管理与展期 | `TC-PLAN-18` | ✅ |
| `FR-PLAN-19` | 绑定实货敞口到套保计划 | `TC-PLAN-19, TC-PLAN-23` | ✅ |
| `FR-PLAN-20` | 查询计划执行进度 | `TC-PLAN-20` | ✅ |

#### 6.1.D `TRADE`（18 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-TRADE-1` | 提交交易指令 | `TC-TRADE-1, TC-TRADE-2 @ TEST_PLAN/TRADE.md`；`TC-BOUND-5`/`TC-BOUND-6`/`TC-EXC-7` | ✅ |
| `FR-TRADE-2` | 人工确认后发出 | `TC-TRADE-3, TC-TRADE-4`；`TC-EXC-1`/`TC-EXC-2` | ✅ |
| `FR-TRADE-3` | 事前风控校验 | `TC-TRADE-5, TC-TRADE-6`；`TC-EXC-6` | ✅ |
| `FR-TRADE-4` | 接收委托回报 | `TC-TRADE-7`；`TC-EXC-4` | ✅ |
| `FR-TRADE-5` | 接收成交回报 | `TC-TRADE-8, TC-TRADE-9`；`TC-EXC-4` | ✅ |
| `FR-TRADE-6` | 撤销交易指令 | `TC-TRADE-10, TC-TRADE-11` | ✅ |
| `FR-TRADE-7` | 查询委托列表 | `TC-TRADE-12` | ✅ |
| `FR-TRADE-8` | 查询成交记录 | `TC-TRADE-13` | ✅ |
| `FR-TRADE-9` | 查询持仓 | `TC-TRADE-14` | ✅ |
| `FR-TRADE-10` | 记录交易理由 | `TC-TRADE-15, TC-TRADE-16, TC-TRADE-17` | ✅ |
| `FR-TRADE-11` | 回写额度与进度 | `TC-TRADE-18` | ✅ |
| `FR-TRADE-12` | 查询行情 | `TC-TRADE-19`；`TC-EXC-10` | ✅ |
| `FR-TRADE-13` | 期权策略下单（多腿） | `TC-TRADE-20` | ✅ |
| `FR-TRADE-14` | 推送事件流并落库 | `TC-TRADE-21`；`TC-EXC-3`/`TC-BOUND-15` | ✅ |
| `FR-TRADE-15` | 指令流水落盘与重放 | `TC-TRADE-22`；`TC-EXC-5`/`TC-EXC-11` | ✅ |
| `FR-TRADE-16` | 查询交易指令流水 | `TC-TRADE-23` | ✅ |
| `FR-TRADE-17` | 查询事前风控拒绝记录 | `TC-TRADE-24` | ✅ |
| `FR-TRADE-18` | 导出交易记录 | `TC-TRADE-25` | ✅ |

#### 6.1.E `SETTLE`（17 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-SETTLE-1` | 获取 CTP 日终结算数据 | `TC-SETTLE-1 @ TEST_PLAN/SETTLE.md` | ✅ |
| `FR-SETTLE-2` | 按子账户拆分持仓 | `TC-SETTLE-2` | ✅ |
| `FR-SETTLE-3` | 按子账户拆分资金 | `TC-SETTLE-3` | ✅ |
| `FR-SETTLE-4` | 子账户独立日终结算 | `TC-SETTLE-4, TC-SETTLE-22` | ✅ |
| `FR-SETTLE-5` | 成交记录核对 | `TC-SETTLE-5` | ✅ |
| `FR-SETTLE-6` | 仓位核对 | `TC-SETTLE-6` | ✅ |
| `FR-SETTLE-7` | 资金核对 | `TC-SETTLE-7` | ✅ |
| `FR-SETTLE-8` | 子账户加总对账 | `TC-SETTLE-8, TC-SETTLE-9`；`TC-BOUND-16`/`TC-REL-6` | ✅ |
| `FR-SETTLE-9` | 差异标记与处理 | `TC-SETTLE-10` | ✅ |
| `FR-SETTLE-10` | 生成每日结算报告 | `TC-SETTLE-11` | ✅ |
| `FR-SETTLE-11` | 保证金监控 | `TC-SETTLE-12` | ✅ |
| `FR-SETTLE-12` | 生成风控日报 | `TC-SETTLE-13` | ✅ |
| `FR-SETTLE-13` | 月度核对记录 | `TC-SETTLE-14` | ✅ |
| `FR-SETTLE-14` | 季度核对与报告 | `TC-SETTLE-15` | ✅ |
| `FR-SETTLE-15` | 查询历史结算记录 | `TC-SETTLE-16` | ✅ |
| `FR-SETTLE-16` | 导出结算报表 | `TC-SETTLE-17` | ✅ |
| `FR-SETTLE-17` | 生成核对任务并管理电子签署 | `TC-SETTLE-18, TC-SETTLE-19, TC-SETTLE-20, TC-SETTLE-21` | ✅ |

#### 6.1.F `RISK`（19 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-RISK-1` | 品种白名单校验 | `TC-RISK-1 @ TEST_PLAN/RISK.md` | ✅ |
| `FR-RISK-2` | 额度校验（三层限额） | `TC-RISK-2, TC-RISK-3, TC-RISK-4, TC-RISK-5, TC-RISK-6, TC-RISK-7, TC-RISK-31` | ✅ |
| `FR-RISK-3` | 持仓上限校验 | `TC-RISK-8, TC-RISK-9` | ✅ |
| `FR-RISK-4` | 规模红线校验 | `TC-RISK-10, TC-RISK-11`；`TC-BOUND-2` | ✅ |
| `FR-RISK-5` | 时点净持仓校验 | `TC-RISK-12, TC-RISK-13` | ✅ |
| `FR-RISK-6` | 期限匹配校验 | `TC-RISK-14, TC-RISK-15`；`TC-BOUND-4` | ✅ |
| `FR-RISK-7` | 交易权限与授权校验 | `TC-RISK-16` | ✅ |
| `FR-RISK-8` | 价格偏离校验 | `TC-RISK-17, TC-RISK-18, TC-RISK-19`；`TC-BOUND-3` | ✅ |
| `FR-RISK-9` | 参数全量预加载 | `TC-RISK-20`；`TC-BOUND-14` | ✅ |
| `FR-RISK-10` | 参数变更下发 | `TC-RISK-21`；`TC-EXC-9` | ✅ |
| `FR-RISK-11` | 事前拒绝记录上报 | `TC-RISK-22` | ✅ |
| `FR-RISK-12` | 事中实时持仓与浮盈 | `TC-RISK-23` | ✅ |
| `FR-RISK-13` | 保证金占用率与风险度 | `TC-RISK-24` | ✅ |
| `FR-RISK-14` | 异常下单频次监控 | `TC-RISK-25` | ✅ |
| `FR-RISK-15` | 告警与限制新开仓 | `TC-RISK-27` | ✅ |
| `FR-RISK-16` | 强制平仓提示 | `TC-RISK-26` | ✅ |
| `FR-RISK-17` | 风险报表生成与落库 | `TC-RISK-28` | ✅ |
| `FR-RISK-18` | 违规事项汇总与限额使用 | `TC-RISK-29` | ✅ |
| `FR-RISK-19` | 查询风险指标与告警历史 | `TC-RISK-30` | ✅ |

#### 6.1.G `AUDIT`（13 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-AUDIT-1` | 全链路正向追溯 | `TC-AUDIT-1, TC-AUDIT-16 @ TEST_PLAN/AUDIT.md` | ✅ |
| `FR-AUDIT-2` | 全链路反向追溯 | `TC-AUDIT-2` | ✅ |
| `FR-AUDIT-3` | 追溯链完整性校验 | `TC-AUDIT-3, TC-AUDIT-4, TC-AUDIT-15` | ✅ |
| `FR-AUDIT-4` | 违规事项筛查 | `TC-AUDIT-5` | ✅ |
| `FR-AUDIT-5` | 生成季度报表 | `TC-AUDIT-6` | ✅ |
| `FR-AUDIT-6` | 生成年度专项报告 | `TC-AUDIT-7` | ✅ |
| `FR-AUDIT-7` | 生成重大事项专项报告 | `TC-AUDIT-8` | ✅ |
| `FR-AUDIT-8` | 导出审计证据链 | `TC-AUDIT-9` | ✅ |
| `FR-AUDIT-9` | 审计日志查询 | `TC-AUDIT-10` | ✅ |
| `FR-AUDIT-10` | 审计日志防篡改校验 | `TC-AUDIT-11` | ✅ |
| `FR-AUDIT-11` | 操作留痕查询 | `TC-AUDIT-12` | ✅ |
| `FR-AUDIT-12` | 导出审计报表 | `TC-AUDIT-13`；`TC-EXC-8` | ✅ |
| `FR-AUDIT-13` | 查询核对任务与签署记录 | `TC-AUDIT-14` | ✅ |

#### 6.1.H `ACCT`（14 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-ACCT-1` | 生成套期关系文档 | `TC-ACCT-1 @ TEST_PLAN/ACCT.md` | ✅ |
| `FR-ACCT-2` | 生成有效性评估记录 | `TC-ACCT-2` | ✅ |
| `FR-ACCT-3` | 生成再平衡记录 | `TC-ACCT-3` | ✅ |
| `FR-ACCT-4` | 套期有效性评估 | `TC-ACCT-2, TC-ACCT-4` | ✅ |
| `FR-ACCT-5` | 套保效果综合评估 | `TC-ACCT-5` | ✅ |
| `FR-ACCT-6` | 生成会计凭证草稿 | `TC-ACCT-6, TC-ACCT-7, TC-ACCT-16` | ✅ |
| `FR-ACCT-7` | 审核会计凭证 | `TC-ACCT-8` | ✅ |
| `FR-ACCT-8` | 查询会计凭证列表 | `TC-ACCT-9` | ✅ |
| `FR-ACCT-9` | 导出会计凭证 | `TC-ACCT-10` | ✅ |
| `FR-ACCT-10` | 双侧盈亏同时上报 | `TC-ACCT-11` | ✅ |
| `FR-ACCT-11` | 期权套保会计处理 | `TC-ACCT-12` | ✅ |
| `FR-ACCT-12` | 会计期间结账/关账 | `TC-ACCT-13` | ✅ |
| `FR-ACCT-13` | 凭证作废与冲销 | `TC-ACCT-14` | ✅ |
| `FR-ACCT-14` | CAS 24 条款自检清单 | `TC-ACCT-15` | ✅ |

#### 6.1.I `FUND`（17 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-FUND-1` | 建立子账户资金台账 | `TC-FUND-1 @ TEST_PLAN/FUND.md` | ✅ |
| `FR-FUND-2` | 记录资金流水 | `TC-FUND-2` | ✅ |
| `FR-FUND-3` | 查询资金流水台账 | `TC-FUND-3` | ✅ |
| `FR-FUND-4` | 导出资金对账 | `TC-FUND-4` | ✅ |
| `FR-FUND-5` | 发起入金申请 | `TC-FUND-5, TC-FUND-20` | ✅ |
| `FR-FUND-6` | 发起出金申请 | `TC-FUND-6, TC-FUND-20` | ✅ |
| `FR-FUND-7` | 出入金双人复核 | `TC-FUND-7` | ✅ |
| `FR-FUND-8` | 处理 CTP 资金回执与异步回调 | `TC-FUND-8` | ✅ |
| `FR-FUND-9` | 出入金失败重试与幂等 | `TC-FUND-9` | ✅ |
| `FR-FUND-10` | 发起子账户间资金划拨 | `TC-FUND-10, TC-FUND-11`；`TC-BOUND-11` | ✅ |
| `FR-FUND-11` | 子账户划拨审批与留痕 | `TC-FUND-12, TC-FUND-13` | ✅ |
| `FR-FUND-12` | 子账户划拨加总校验 | `TC-FUND-14, TC-FUND-15` | ✅ |
| `FR-FUND-13` | 资金额度设定 | `TC-FUND-16` | ✅ |
| `FR-FUND-14` | 资金额度超限告警 | `TC-FUND-16` | ✅ |
| `FR-FUND-15` | 保证金占用视图 | `TC-FUND-17` | ✅ |
| `FR-FUND-16` | 资金流向与划拨记录查询 | `TC-FUND-18` | ✅ |
| `FR-FUND-17` | 每日资金对账 | `TC-FUND-19` | ✅ |

#### 6.1.J `AUTH`（18 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-AUTH-1` | 新增用户 | `TC-AUTH-1 @ TEST_PLAN/AUTH.md` | ✅ |
| `FR-AUTH-2` | 修改用户 | `TC-AUTH-2` | ✅ |
| `FR-AUTH-3` | 停用用户 | `TC-AUTH-3` | ✅ |
| `FR-AUTH-4` | 启用用户 | `TC-AUTH-4` | ✅ |
| `FR-AUTH-5` | 查询用户列表 | `TC-AUTH-5` | ✅ |
| `FR-AUTH-6` | 定义角色 | `TC-AUTH-6` | ✅ |
| `FR-AUTH-7` | 配置岗位分离互斥 | `TC-AUTH-7` | ✅ |
| `FR-AUTH-8` | 校验岗位分离约束 | `TC-AUTH-8` | ✅ |
| `FR-AUTH-9` | 禁止企业负责人直接操盘 | `TC-AUTH-9` | ✅ |
| `FR-AUTH-10` | 配置人员授权 | `TC-AUTH-10` | ✅ |
| `FR-AUTH-11` | 授权变更留痕 | `TC-AUTH-11` | ✅ |
| `FR-AUTH-12` | 配置子账户数据权限 | `TC-AUTH-12, TC-AUTH-22` | ✅ |
| `FR-AUTH-13` | 授予跨子账户查看权限 | `TC-AUTH-13` | ✅ |
| `FR-AUTH-14` | 用户登录与身份认证 | `TC-AUTH-14, TC-AUTH-15` | ✅ |
| `FR-AUTH-15` | 登出与登录会话管理 | `TC-AUTH-16, TC-AUTH-17` | ✅ |
| `FR-AUTH-16` | 口令管理与强度策略 | `TC-AUTH-18, TC-AUTH-19`；`TC-BOUND-13` | ✅ |
| `FR-AUTH-17` | 登录失败锁定 | `TC-AUTH-20`；`TC-BOUND-12` | ✅ |
| `FR-AUTH-18` | 查询授权与权限变更记录 | `TC-AUTH-21` | ✅ |

#### 6.1.K `CONFIG`（15 条）

| 需求编号 | 需求描述 | 对应用例 | 覆盖状态 |
|---------|---------|---------|---------|
| `FR-CONFIG-1` | 配置 CTP 连接参数 | `TC-CONFIG-1 @ TEST_PLAN/CONFIG.md` | ✅ |
| `FR-CONFIG-2` | 敏感字段内存持有与手工录入 | `TC-CONFIG-2, TC-CONFIG-3` | ✅ |
| `FR-CONFIG-3` | 本地 admin 配置持久化 | `TC-CONFIG-4` | ✅ |
| `FR-CONFIG-4` | API 下发配置不落盘 | `TC-CONFIG-5` | ✅ |
| `FR-CONFIG-5` | 配置风险阈值参数 | `TC-CONFIG-6` | ✅ |
| `FR-CONFIG-6` | 配置事前风控参数并下发 | `TC-CONFIG-7` | ✅ |
| `FR-CONFIG-7` | 配置子账户 | `TC-CONFIG-8, TC-CONFIG-9` | ✅ |
| `FR-CONFIG-8` | 配置子账户参数 | `TC-CONFIG-10` | ✅ |
| `FR-CONFIG-9` | 配置组件间 TCP 连接 | `TC-CONFIG-11, TC-CONFIG-18`；`TC-SEC-3` | ✅ |
| `FR-CONFIG-10` | 配置行情与交易前置地址 | `TC-CONFIG-12` | ✅ |
| `FR-CONFIG-11` | 查询与修改系统参数 | `TC-CONFIG-13` | ✅ |
| `FR-CONFIG-12` | 配置变更留痕 | `TC-CONFIG-14` | ✅ |
| `FR-CONFIG-13` | 系统参数导入导出 | `TC-CONFIG-15` | ✅ |
| `FR-CONFIG-14` | 配置数据字典与枚举维护 | `TC-CONFIG-16` | ✅ |
| `FR-CONFIG-15` | 配置报告周期与提醒 | `TC-CONFIG-17` | ✅ |

#### 6.1.L 非功能性需求（50 条 → `NONFUNCTIONAL.md`）

> NFR 归属统一为 `REQUIREMENTS/NONFUNCTIONAL.md`，对应用例归属为 `TEST_PLAN/NONFUNCTIONAL.md`。**逐条列出，50 条无遗漏**。

| 需求编号（含文件归属） | 需求描述 | 对应用例（含文件归属） | 覆盖状态 |
|---------------------|---------|---------------------|---------|
| `NFR-PERF-1 @ REQUIREMENTS/NONFUNCTIONAL.md` | 事前风控 P99≤5ms、零 DB I/O | `TC-PERF-1 @ TEST_PLAN/NONFUNCTIONAL.md` | ✅ |
| `NFR-PERF-2` | 确认→CTP P95≤50ms | `TC-PERF-2` | ✅ |
| `NFR-PERF-3` | 台账/凭证查询 P95<2s | `TC-PERF-3` | ✅ |
| `NFR-PERF-4` | 列表查询 P95<2s | `TC-PERF-4` | ✅ |
| `NFR-PERF-5` | 行情 P95≤200ms | `TC-PERF-5` | ✅ |
| `NFR-PERF-6` | 事件流≥1000/s 不丢不乱序 | `TC-PERF-6` | ✅ |
| `NFR-PERF-7` | 日终结算≤10min | `TC-PERF-7` | ✅ |
| `NFR-REL-1 @ REQUIREMENTS/NONFUNCTIONAL.md` | 崩溃恢复≤60s | `TC-REL-1` | ✅ |
| `NFR-REL-2` | 断连检出≤5s、恢复≤30s | `TC-REL-2` | ✅ |
| `NFR-REL-3` | 持久化事务性 | `TC-REL-3` | ✅ |
| `NFR-REL-4` | 可用性≥99.5% | `TC-REL-4` | ✅ |
| `NFR-REL-5` | 外部回报幂等 | `TC-REL-5` | ✅ |
| `NFR-REL-6` | 加总告警≤60s 阻断立即生效 | `TC-REL-6` | ✅ |
| `NFR-SEC-1 @ REQUIREMENTS/NONFUNCTIONAL.md` | 零落盘红线 | `TC-SEC-1` | ✅ |
| `NFR-SEC-2` | 口令加盐哈希 | `TC-SEC-2` | ✅ |
| `NFR-SEC-3` | 回环绑定+鉴权 | `TC-SEC-3` | ✅ |
| `NFR-SEC-4` | 双人复核 | `TC-SEC-4` | ✅ |
| `NFR-SEC-5` | 岗位分离强制 | `TC-SEC-5` | ✅ |
| `NFR-SEC-6` | 日志不可篡改 | `TC-SEC-6` | ✅ |
| `NFR-SEC-7` | 子账户软隔离 | `TC-SEC-7` | ✅ |
| `NFR-SEC-8` | DB 最小权限 | `TC-SEC-8` | ✅ |
| `NFR-SEC-9` | C++ 零 DB | `TC-SEC-9` | ✅ |
| `NFR-COMPAT-1 @ REQUIREMENTS/NONFUNCTIONAL.md` | 严格 C++11 | `TC-COMPAT-1` | ✅ |
| `NFR-COMPAT-2` | JDK 11 | `TC-COMPAT-2` | ✅ |
| `NFR-COMPAT-3` | Chromium 内核 | `TC-COMPAT-3` | ✅ |
| `NFR-COMPAT-4` | Ubuntu 同机双进程 | `TC-COMPAT-4` | ✅ |
| `NFR-COMPAT-5` | DM8 + 标准 JDBC | `TC-COMPAT-5` | ✅ |
| `NFR-COMPAT-6` | CTP 合并版 UserApi | `TC-COMPAT-6` | ✅ |
| `NFR-COMPAT-7` | 零外部数据依赖 | `TC-COMPAT-7` | ✅ |
| `NFR-MAINT-1 @ REQUIREMENTS/NONFUNCTIONAL.md` | 前端零构建链 | `TC-MAINT-1` | ✅ |
| `NFR-MAINT-2` | 最小依赖 | `TC-MAINT-2` | ✅ |
| `NFR-MAINT-3` | DAO 分层 | `TC-MAINT-3` | ✅ |
| `NFR-MAINT-4` | 组件边界 | `TC-MAINT-4` | ✅ |
| `NFR-MAINT-5` | 前端隔离 | `TC-MAINT-5` | ✅ |
| `NFR-MAINT-6` | 单一写库入口 | `TC-MAINT-6` | ✅ |
| `NFR-TEST-1 @ REQUIREMENTS/NONFUNCTIONAL.md` | 覆盖率≥80% | `TC-TEST-1` | ✅ |
| `NFR-TEST-2` | ctest+JUnit 全通过 | `TC-TEST-2` | ✅ |
| `NFR-TEST-3` | 冒烟可重复 | `TC-TEST-3` | ✅ |
| `NFR-TEST-4` | 依赖可注入 | `TC-TEST-4` | ✅ |
| `NFR-TEST-5` | 协议专项 T1–T11 | `TC-TEST-5` | ✅ |
| `NFR-USE-1 @ REQUIREMENTS/NONFUNCTIONAL.md` | 30 分钟上手 | `TC-USE-1` | ✅ |
| `NFR-USE-2` | 操作步数≤5 | `TC-USE-2` | ✅ |
| `NFR-USE-3` | 提示含三要素 | `TC-USE-3` | ✅ |
| `NFR-USE-4` | 可执行下一步提示 | `TC-USE-4` | ✅ |
| `NFR-USE-5` | 切换≤2 击、双侧同屏 | `TC-USE-5` | ✅ |
| `NFR-DEPLOY-1 @ REQUIREMENTS/NONFUNCTIONAL.md` | 同机双进程 | `TC-DEPLOY-1` | ✅ |
| `NFR-DEPLOY-2` | 部署≤30min | `TC-DEPLOY-2` | ✅ |
| `NFR-DEPLOY-3` | 构建可复现 | `TC-DEPLOY-3` | ✅ |
| `NFR-DEPLOY-4` | 配置分级 | `TC-DEPLOY-4` | ✅ |
| `NFR-DEPLOY-5` | 依赖文档化 | `TC-DEPLOY-5` | ✅ |

#### 6.1.M 覆盖缺口汇总

| 项 | 结论 | 扫描口径 |
|----|------|---------|
| FR 总数 | **183** | `grep -oE 'FR-<代号>-[0-9]+' docs/REQUIREMENTS/*.md \| sort -u \| wc -l` 逐模块求和 = 14+18+20+18+17+19+13+14+17+18+15 = **183** |
| 已覆盖 FR | **183** | 逐条见 §6.1.A–K，无 ❌ |
| **未覆盖 FR** | **0** | 本表逐条核对，**无 ❌ 行** |
| NFR 总数 | **50** | `NONFUNCTIONAL.md` NFR 总表（7+6+9+7+6+5+5+5） |
| 未覆盖 NFR | **0** | `NONFUNCTIONAL.md` §9 覆盖核对无 ❌、无 ⚪ |

> ⚠️ **"0 未覆盖"的扫描口径**：以 `docs/REQUIREMENTS/INDEX.md` §11 文件清单登记的 FR 数为基准（`PRODUCT`14/`SPOT`18/`PLAN`20/`TRADE`18/`SETTLE`17/`RISK`19/`AUDIT`13/`ACCT`14/`FUND`17/`AUTH`18/`CONFIG`15 = 183），
> 用 `grep -oE 'FR-<代号>-[0-9]+' docs/REQUIREMENTS/<代号>.md | sort -u` 去重后逐模块核对，与 §6.1.A–K 的 183 行**逐条一一对应**；
> NFR 以 `NONFUNCTIONAL.md` NFR 总表 50 条为基准，与 §6.1.L 逐类一一对应。

### 6.2 反向：用例 → 需求

> 反向检查：**每条 `TC-` 都应能追到需求**。本表列出**无直接 FR 来源**的用例及其理由。

| 用例编号（含文件归属） | 无对应需求的理由 |
|---------------------|----------------|
| `TC-PERF-*` / `TC-REL-*` / `TC-SEC-*` / `TC-COMPAT-*` / `TC-MAINT-*` / `TC-TEST-*` / `TC-USE-*` / `TC-DEPLOY-* @ TEST_PLAN/NONFUNCTIONAL.md` | 对应 **NFR** 而非 FR（来源 `REQUIREMENTS/NONFUNCTIONAL.md`），已在 §6.1.L 登记，**非无来源** |
| `TC-BOUND-1`～`TC-BOUND-16 @ TEST_PLAN/BOUNDARY.md` | 均有对应 FR（见 §6.1 各行）；属**边界值派生用例**，边界依据来自需求约束/数据字典/架构（`DATA_MODEL.md` §4） |
| `TC-EXC-1`～`TC-EXC-12 @ TEST_PLAN/BOUNDARY.md` | 均有对应 FR；属**异常路径派生用例**，触发方式来自 §5 异常分支/§8.5 降级矩阵 |
| `TC-TEST-5`（协议专项） | 追溯 `NFR-TEST-5` + `ARCHITECTURE.md` §4.2.7（架构级），非单一 FR |

> **结论**：**无"无来源"的用例**——所有 `TC-` 均可追到 `FR` 或 `NFR`；`TC-BOUND/TC-EXC` 的 FR 归属已逐条写在其「对应需求」字段与 §6.1 各行。

---

## 7. 准入标准

- [ ] 需求文档、架构文档已定稿（`REQUIREMENTS` 零待澄清 + `ARCHITECTURE.md` **编写时点 v1.5.4** + `DATA_MODEL.md` **编写时点 v1.3.3**；**门禁复核时以实时 `md5sum` 为准**）
- [ ] 代码可构建、可部署到测试环境（`mvn -q test` 全绿 + C++ `cmake --build` 零警告）
- [ ] 开发自测通过，主流程可跑通
- [ ] 接口契约与架构文档一致（`java/CONTRACTS.md` **编写时点 `HGC-1.4`**；**门禁复核时以实时 `md5sum` 为准**）
- [ ] 测试环境与数据就绪（DM8 实例 + CTP 模拟/桩 + 造数脚本）
- [ ] **`DATA_MODEL.md` §4.4「无法由数据库保证的业务规则」可测性会签结论已闭环**（见 §7.1）

> ✅ **架构判据脚本状态（`E5` 误报已修复，2026-09-20）**：`E5`（`tools/arch-check/scan_java_sql.sh`，段② SQL/驱动判定）此前有 **5 处误报**——`main` 1 处 javadoc 内 SQL（`ParamDispatchServiceImpl.java:63` `{SELECT MAX(...)}`）+ `test` 4 处装配夹具的 `jdbc:dm://…` 配置值（`BootstrapAssemblyTest.java:103`、`SensitiveLoggingAndConfigTest.java:160/167/174`），**均非真缺陷**；根因＝旧判据段②的形态与 `hg.db.url` 的**合法取值形状**重合（只要有人测装配，判据必红）。**技术负责人已作判据侧修复**：① 预处理**剔除 Java 注释**（字符串字面量内不视为注释）；② 段②拆 `②a`（`DriverManager.getConnection(` / 驱动类加载，**全仓**）/ `②a2`（`registerDriver(`，**仅生产**）/ `②b`（`jdbc:`/`odbc:`/`jdbcUrl`，**仅生产**）/ `②c`（其它关键字，全仓）。**判据未削弱、有反例**（生产 SQL 字面量 / 生产 `getConnection(` / 生产驱动类加载 / 生产 `registerDriver(` / 生产内嵌连接串**全部命中**；`src/test` 内的**真外泄**仍命中）。**基线：**`scan_java_sql.sh` → **108 文件 / 0 命中 / 退出码 0**；**`java/**` 一行未改、用例震动面 = 0**。口径以 `ARCHITECTURE.md` §3.0.2 为准（**引用时点 v1.5.6**；**以实时 `md5sum` 为准**）。
> **对测试计划的影响**：`E5` 修复**不影响本计划交付**，也不影响用例设计；此前「不得引用 `E5` 为"已通过"」的**挂起条款已解除**——`TC-MAINT-3`/`TC-MAINT-6`/`TC-SEC-9`/`TC-COMPAT-5` 按**现行判据**（`ARCHITECTURE.md` §3.0.2）执行即可。

### 7.1 `DATA_MODEL.md` §4.4 可测性会签结论（测试经理独立复核）

> **来源**：本席在 `T-ARCH-005` 出具的 M1 会签意见（`docs/gates/M1-data-model-signoff/test-manager.md`），
> 该意见为**有条件通过**，含阻塞 **B-1**、**B-2** 与一般项 G-1~G-6。**本次 S5 落盘复核如下**——**每一项均在本复核时点的文件上实跑复验，未沿用历史结论**（命令+输出见「证据」列）。
>
> **复核时点（2026-09-20，本席实测）**：`docs/DATA_MODEL.md` = **v1.3.3** `c399c5f595eba8116187acc6dac4e9eb` / 5245 行；`docs/ARCHITECTURE.md` = **v1.5.6** `3ba86db24ccade401c5372db935c42aa` / 2650 行。（均**以实时 `md5sum` 为准**。）

| 阻塞项 | 原意见 | 复核结论（实测） | 证据（复核时点 + 命令 → 输出） |
|--------|--------|-------------------|------|
| **B-1** | `idx_trade_reason_code` 建于**已删除列** `reason_code` | ✅ **已闭环**（结构已订正） | `grep -n 'reason_code' docs/DATA_MODEL.md` → `:4285-4286` 明载「`t_trade_reason` 内**不存在** `reason_code` 列；索引 `idx_trade_reason_code` 已在 v1.2 移除/改建（`DC-017`）」 |
| **B-2** | `t_trade_reason` 关联对象 `NOT NULL` 与"关联对象缺失"筛查维度**冲突** | ✅ **已闭环**——`CH-004` 定为**条件非空**：提交路径缺关联即拒单，数据层允许补填态（带缺失标记）；`REASON_MISSING` 与 `RELATED_MISSING` 并列独立、均可筛出 | `grep -n 'ck_trade_reason_related_or_missing' docs/DATA_MODEL.md` → `:2162`（CHECK 定义）、`:4501`（§4.4 规则）、`:5241`（附录 C 第 19 项）；`DC-024`（`:5087`）实载 `related_plan_id`/`related_exposure_id` `NOT NULL→NULL` + 条件非空 |
| **G-5** | DM8 对"含 NULL 唯一约束"实机行为确认（或改哨兵值） | ✅ **已闭环**——`DC-041`：`product_id` 恒 `NOT NULL` + 未映射哨兵 `0`；新增 `spot_category_code NOT NULL`（双态）；`uk_exposure_key` 六列全非空 | `grep -n 'ck_exposure_product_unmapped\|ck_exposure_spot_category_scope\|uk_exposure_key\|__MAPPED__' docs/DATA_MODEL.md` → `:1494`/`:1495`/`:1523`/`:1538`/`:1544`；`t_product` 保留行 `id=0` 见 `:1012` |
| **#13** | 三层限额"最细命中者"全序与并列取一 | ✅ **已写死**——层②`SUB_INSTRUMENT` > 层①`SUB_PRODUCT` > 层③`ACCOUNT_PRODUCT`（层③兜底不参与选择）；同层多条取占用率最高者、再并列取 `id` 小者 | `grep -c 'SUB_INSTRUMENT' docs/DATA_MODEL.md` = **6**；层②载体 `TB-PRODUCT-8` 见 `:296`；§4.4（`:4498`）写死全序+并列取一 |
| **#17** | 敞口三分量不一致时"拒绝"还是"重算" | ✅ **已写死**——写侧拒绝、展示侧重算+告警+留痕、修正须生成新快照 | `grep -c 'COMPONENT_RECALC' docs/DATA_MODEL.md` = **7**；§4.4（`:4503`）写死三分量处置 |
| **#19** | "在途"判定条件与最长时窗 | ✅ **已写死**——两档时窗（结算时点前 `IN_FLIGHT` 不计断点 → 逾期 `UNKNOWN` 告警 → 再逾 1 结算周期 `HAS_BREAK`） | `grep -c 'IN_FLIGHT' docs/DATA_MODEL.md` = **7**；`audit.chain.inflight_max_cycles` 命中 **1**；§4.4（`:4505`）写死时窗 |
| **G-6** | 阻断范围无结构化取数来源 | ✅ **已闭环**——新增 `t_agg_diff_candidate`（`TB-SETTLE-16`）；机判取数路径 = 该表 / `t_reconcile_difference` | `grep -c 't_agg_diff_candidate' docs/DATA_MODEL.md` = **16**；`TB-SETTLE-16` 登记见 `:27`；§4.4.1（`:4509`）取数路径 |
| **G-3** | `ARCHITECTURE.md` §6.14/§12.3 将签署记录标为 `TB-SETTLE-14`（应为 `TB-SETTLE-15`） | ✅ **已闭环**——该表号**前序版本确曾错标（曾为真缺陷，`G-3` 见 `docs/gates/M1-data-model-signoff/` 会签）**；**订正在前序版本完成（非本轮）**；**经本席本轮实测现行版本已闭合、本轮零改动** | `grep -n 'TB-SETTLE-14' docs/ARCHITECTURE.md`（v1.5.6 `3ba86db2…`/2650）→ **规范引用 0**：唯一 **1 处** `:2605` 位于 **v1.5.6 变更记录**、系**引述本工单 Q1 的留痕**，**非定义性引用**；`§6.14`（标题 `:1619`）实体为 `TB-SETTLE-15`（`:1639`） |
| **G-4** | 多角色账号 `signer_role_code` 解析规则 | ✅ **已写死**——由签署动作显式选择；未显式选择/角色不在 `required_sign_roles`/用户不持有 → 一律拒绝 | `grep -c 'signer_role_code' docs/DATA_MODEL.md` = **3**；`required_sign_roles` = **4**；`proxy_rejected` = **5** |

> **结论**：`DATA_MODEL.md` §4.4 的 **20 条"DB 无法保证"规则均已具备可执行验证手段**；
> 原 B-1/B-2/G-5/#13/#17/#19/G-6/G-4 等**阻塞与关键口径已全部闭环**。
> **准入判定：§4.4 可测性门禁达成**（`DC-041`/`CH-004`/`CH-008` 已传导）。**8 项阻塞/关键口径 + G-3 均在本复核时点实测复验通过**（命令+输出见上表；**未沿用历史结论**）——其中 G-3 系**历史问题、现行版本已闭合**（规范引用 0），本席用例按**语义**引用 ⇒ **零改动**。`E5` 误报**已由技术负责人判据侧修复**（见 §7 上注）。

---

## 8. 准出标准

- [ ] 计划用例执行率 = 100%（311 条）
- [ ] P0 用例通过率 = 100%
- [ ] P1 用例通过率 ≥ 95%
- [ ] 无未闭环的 P0/P1 缺陷
- [ ] 所有缺陷经复检确认修复
- [ ] 需求追溯矩阵无未覆盖项（183 FR + 50 NFR，见 §6.1.M）
- [ ] `T1`–`T11` 协议专项全部通过
- [ ] `RSK-06`/`RSK-10` 验证点结论明确（通过 / 降级声明）

---

## 9. 缺陷管理

### 9.1 缺陷分级

| 级别 | 定义 | 处理时限 |
|------|------|---------|
| P0 致命 | 核心功能不可用、数据丢失、安全漏洞、崩溃；**架构红线被突破**（C++ 访问 DB、事前风控出现 DB I/O、多写库入口） | 立即，阻塞发布 |
| P1 严重 | 主要功能异常、无规避方案、契约不符；**合规红线失效**（阻断未生效、加总阻断波及平仓） | 当轮修复 |
| P2 一般 | 次要功能异常、有规避方案 | 可延后 |
| P3 轻微 | 界面瑕疵、文案、体验 | 排期优化 |

### 9.2 缺陷生命周期

```
NEW → ASSIGNED → FIXED → VERIFYING → CLOSED
                            └──> REOPENED → ASSIGNED
```

---

## 10. 质量度量

| 指标 | 计算方式 | 目标值 |
|------|---------|--------|
| 用例执行率 | 已执行 / 计划（311） | 100% |
| 用例通过率 | 通过 / 已执行 | P0 = 100%；P1 ≥ 95% |
| FR 覆盖率 | 已覆盖 FR / 183 | 100% |
| NFR 覆盖率 | 已覆盖 NFR / 50 | 100% |
| JUnit→TC 映射覆盖率 | 可追到模块 TC 的 JUnit / 272 | ≥ 60%（实测 64.0%，见 §2.2） |
| 缺陷密度 | 缺陷数 / 模块规模 | 按模块统计监控 |
| P0/P1 缺陷数 | — | 0（准出时） |
| 缺陷修复率 | 已闭环 / 总缺陷 | 100% |
| 协议专项通过率 | `T1`–`T11` 通过 / 11 | 100% |

---

## 11. 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| `RSK-06` 自实现 WebSocket（A′）帧编解码正确性 | `NFR-PERF-5` P95 ≤200 ms 不达标，行情推送不可用 | `TC-PERF-5` 端到端采样 + `TC-TEST-5`/`WsFrameCodecTest` 离线单测兜底；不达标则走 `CH-xxx` 降级声明 |
| `RSK-10` 重放期在途指令 CTP 查询不可用 | 重放无法判定在途指令真实状态 | `TC-EXC-5` 保守处理（不释放冻结额度、告警、人工闭环）、`TC-REL-1` |
| `RSK-03` DM JDBC 驱动版本不匹配 | 全部持久化功能阻塞 | `TC-DEPLOY-5` 核对实测版本；`unit` 档用假 DAO 无 DM 可跑 |
| `RSK-04` 快照与 T4 已用量不一致窗口 | 极短窗口判定基于"旧上限+新已用量" | `TC-RISK-21` 覆盖两阶段下调（先冻结→替换快照→解冻） |
| 三层限额"最细命中"依赖 **C++ 内存权威值** | DB 投影直插会产生反例误判 | 构造纪律 `S-9`：**必须经协议/内存构造**（`0x0113`），不得 DB 直插（`TC-RISK-6` 已注明） |
| 阻断类用例期望值若用 v1.0 临时口径 | 用例与实现口径漂移 | 全部阻断类用例期望值锚定 **`INDEX.md` §9.1 v1.1 确认口径**（见 §12） |

---

## 12. 阻断类用例的 v1.1 确认口径落点（不得用 v1.0 临时口径）

> 依据：`T-ARCH-005` 要求 2 + `REQUIREMENTS/INDEX.md` §9.1。**逐条 → 落点用例**：

| 口径（`Q-*`） | 确认口径要点 | 落点用例 |
|--------------|-------------|---------|
| 三层限额（`Q-16`） | 层①超 / 层②超 / 层③超**均能阻断**，且**拒绝信息标明命中层级** | `TC-RISK-2`（层①）、`TC-RISK-3`（层②）、`TC-RISK-4`（层③）、`TC-RISK-5`（三层同时超）、`TC-TRADE-6` |
| 价格偏离（`Q-15`） | **(2%, 5%] 告警 + 二次确认；> 5% 阻断**（两个边界各一组） | `TC-RISK-17`（>5%）、`TC-RISK-18`（(2%,5%] 两端）、`TC-BOUND-3` |
| 期限红线（`Q-4`） | 取「建仓日+12 个月」与「合同到期+30 天」**较早者** | `TC-RISK-14`、`TC-BOUND-4`、`TC-PLAN-10` |
| 加总对账（`Q-11`） | 不一致时**仅差异所属子账户**被阻断、**其他子账户正常**、**平仓不被阻断** | `TC-SETTLE-9`、`TC-BOUND-16`、`TC-RISK-15`、`TC-FUND-15` |
| 核对签署（`Q-14`） | **非应签角色签署 / 代签被拒**；签署后改数据 → 回"待签署"；逾期 → 告警 + 进审计报表 | `TC-SETTLE-19`（代签）、`TC-SETTLE-20`（快照变更）、`TC-SETTLE-21`（逾期）、`TC-AUDIT-14` |
| 交易理由（`Q-13`/`CH-004`） | 关联对象与指令**不同子账户 → 拒绝**；关联对象缺失 → 进"关联对象缺失"筛查维度 | `TC-TRADE-16`、`TC-TRADE-17`、`TC-AUDIT-5` |
| 规模红线（`Q-1`/`Q-2`） | 商品类 90% / 贸易类 80%；基数 = 年度现货敞口累计量；子账户与全账户双算取严者 | `TC-RISK-10`、`TC-RISK-11`、`TC-BOUND-2` |
| 时点净持仓（`Q-3`） | 下单瞬间硬阻断 + 日终复核 | `TC-RISK-12`、`TC-RISK-13` |
| 敞口三分量（`Q-17`/`CH-004`） | 写侧拒绝 / 展示侧重算；快照必存三分量 | `TC-SPOT-14`、`TC-BOUND-9` |
| 放宽现货价（`Q-12`/`CH-003`） | 现货价唯一口径 = 品种+日期+来源（不含子账户）；缺口按用途分拒绝/沿用标注 | `TC-SPOT-10`、`TC-SPOT-20` |

---

## 13. 文件清单

> 交付自检用。**列出的文件必须全部存在**，否则测试计划不完整。

| 文件 | 内容 | 状态 |
|------|------|------|
| `docs/TEST_PLAN/INDEX.md` | 本文件（索引 + 全局） | ☑ |
| `docs/TEST_PLAN/PRODUCT.md` | 品种管理用例（16） | ☑ |
| `docs/TEST_PLAN/SPOT.md` | 现货管理用例（23） | ☑ |
| `docs/TEST_PLAN/PLAN.md` | 套保计划用例（24） | ☑ |
| `docs/TEST_PLAN/TRADE.md` | 交易执行用例（25） | ☑ |
| `docs/TEST_PLAN/SETTLE.md` | 结算用例（22） | ☑ |
| `docs/TEST_PLAN/RISK.md` | 风险控制用例（31） | ☑ |
| `docs/TEST_PLAN/AUDIT.md` | 审计用例（16） | ☑ |
| `docs/TEST_PLAN/ACCT.md` | 套保会计用例（16） | ☑ |
| `docs/TEST_PLAN/FUND.md` | 资金管理用例（20） | ☑ |
| `docs/TEST_PLAN/AUTH.md` | 用户与权限用例（22） | ☑ |
| `docs/TEST_PLAN/CONFIG.md` | 系统配置用例（18） | ☑ |
| `docs/TEST_PLAN/BOUNDARY.md` | 边界与异常用例（28） | ☑ |
| `docs/TEST_PLAN/NONFUNCTIONAL.md` | 非功能用例（50） | ☑ |
| `docs/TEST_PLAN/cases/TC-*.md` | 复杂用例独立成文 | **不适用**（无单条复杂到需独立成文的用例；各模块内联已含完整字段） |

---

## 14. 与上游文档不一致之处（附行号级证据）

> ⚠️ 本席**只读**上游文档，发现不一致**不自行修改**，以 `question` 工单反馈（`tech-lead` / `product-manager`）。

| # | 不一致 | 证据（行号） | 影响 | 处置 |
|---|--------|-------------|------|------|
| 1 | `ARCHITECTURE.md` §6.14/§12.3 **曾**将**签署记录表**标为 `TB-SETTLE-14`，而 `DATA_MODEL.md` 中 `TB-SETTLE-14` = `t_stress_test`，签署记录应为 `TB-SETTLE-15` | 曾见 `ARCHITECTURE.md` §6.14（表号引用）；`DATA_MODEL.md` `TB-SETTLE-14/15` | 追溯交叉引用错位；**不影响用例可测性**（用例按语义引用签署记录表） | ✅ **已闭环（2026-09-20，本席实测复验）**——`grep -n 'TB-SETTLE-14' docs/ARCHITECTURE.md`（v1.5.6 `3ba86db2…`/2650）→ **规范引用 0**（唯一 1 处 `:2605` 为 v1.5.6 变更记录内**引述本工单**的留痕）；`§6.14`（`:1619`）实体为 `TB-SETTLE-15`（`:1639`）。本席**按语义引用 ⇒ 零改动**；**结论以本席实测为准，未采信转述** |
| 2 | `T-ARCH-005` 工单「上游制品」表将 `REQUIREMENTS` 写作 **183 FR / 131 R / 50 NFR**，而 `REQUIREMENTS/INDEX.md` §11 现行为 **183 FR**（R 条数以各模块文件为准，`INDEX.md` §11 未单列 R 总数） | `T-ARCH-005` L23 vs `REQUIREMENTS/INDEX.md` §11 | 仅工单描述陈旧，**不影响用例归属** | 无需动作（工单为历史制品，FR/NFR 口径一致） |

> **说明**：除上述外，本席未发现测试计划与上游需求/架构/数据模型其它不一致。

---

## 15. 上游反馈（`question` 工单，**均已于 2026-09-20 闭环**）

| 项 | 内容 | 收件人 | 状态 |
|----|------|--------|------|
| Q1 | `ARCHITECTURE.md` §6.14/§12.3 签署记录表号 `TB-SETTLE-14` 疑应为 `TB-SETTLE-15`（§14 第 1 项） | `tech-lead` | ✅ **已闭环（本席实测复验）**——`grep -n 'TB-SETTLE-14' docs/ARCHITECTURE.md` → **规范引用 0**（唯一 1 处 `:2605` 为 v1.5.6 变更记录内引述本工单的**留痕**）；`§6.14`（`:1619`）实体为 `TB-SETTLE-15`（`:1639`）。答复件 `dev-team/outbox/T-S5-TESTPLAN-Q1-question.md`；本席**零改动** |
| Q2 | `E5` 判据误报（`scan_java_sql.sh` 段②）：**实测 5 处**（`ParamDispatchServiceImpl.java:63` javadoc + `BootstrapAssemblyTest.java:103` + `SensitiveLoggingAndConfigTest.java:160/167/174` 的 `jdbc:dm://` URL），**均非真缺陷** | `tech-lead` | ✅ **已闭环（2026-09-20）**——技术负责人**判据侧修复**（预处理剔 Java 注释 + 段②拆 `②a/②a2/②b/②c`）；**判据未削弱、有反例**；基线 `scan_java_sql.sh` → **108 文件 / 0 命中 / 退出码 0**；`java/**` 一行未改、**用例震动面 0**。口径见 `ARCHITECTURE.md` §3.0.2（**引用时点 v1.5.6**；以实时 `md5sum` 为准） |

> ⚠️ **复核纪律（本席，2026-09-20 重申，源于 `Q1` 的教训）**：凡转出「上游制品有缺陷」类结论（**尤其是沿用的历史清单** `G-*` / `B-*` / 待办项）前，**必须在当前版本上实跑复验，并附命令 + 输出**——历史结论只说明「**当时**存在」，不说明「现在存在」；**无行号的章节级引用多半是转述而非实测**。本表与 §7.1 的所有条目已按此在**复核时点**的文件上复验；`G-3` 即因**未复验历史清单**而被误转，现已在现行版本实测闭合（本条为其实例注记）。

---

## 16. 里程碑视图（M1）

> **本章性质**：**索引与映射章**——把 M1 相关用例与**数据模型约束**集中索引；**本表不写用例正文**（`T-022` 授权补齐的用例正文落各模块文件，见 §16.4 / §16.5）。
> **M1 门禁**＝`docs/DATA_MODEL.md` 三方会签（`PROJECT.md` §9），**已达成**——`docs/gates/M1-data-model-signoff/SIGN-OFF.md` §1 载「**M1 数据模型门禁 —— 达成（三方全部通过）**」（产品经理 / 技术负责人 / 测试经理）。
> 本章供 `T-023`（单测 / 集成执行）与 `T-024`（端到端验收）取用。

> **锚点（本章编写时点，⚠️ 一律以实时 `md5sum` 为准）**：
> - `docs/DATA_MODEL.md` = **v1.3.5** `d17bce0c37e513a0a236304b9cb3f25d` / **5252 行**；
> - 约束抽取源 = `docs/gates/M1-data-model-signoff/test-manager.md` **§4「约束 / 索引作为测试设计输入 —— 逐类抽取与可构造性确认」**（该文件 md5 `59090a6a090470174bd6d41f3eac268d` / 1215 行）；
> - `docs/gates/M1-data-model-signoff/SIGN-OFF.md` **§1 = 门禁达成**。

### 16.1 M1 范围与验收点 → 用例落点

M1 = 模块 ①②③⑩⑪ + **子账户体系**（`REQUIREMENTS/INDEX.md` §2「里程碑」列；`PROJECT.md` §9）。
本计划中 `里程碑 = M1` 的模块：`PRODUCT`(16) / `SPOT`(23) / `PLAN`(24) / `AUTH`(22) / `CONFIG`(18)，**合计 103 条**。

| M1 验收点（`PROJECT.md` §9 验收方式） | 用例落点（含文件归属） |
|---|---|
| 创建两个子账户并配置 | `TC-CONFIG-8`（创建 / 启用 / 停用）、`TC-CONFIG-9`（停用前置）、`TC-CONFIG-10`（子账户参数）@ `TEST_PLAN/CONFIG.md` |
| 分别配置品种 | `TC-PRODUCT-11`（额度指标 / 分类标记）、`TC-PRODUCT-12`（按子账户白名单）@ `TEST_PLAN/PRODUCT.md` |
| 录入实货敞口 | `TC-SPOT-1`～`TC-SPOT-9`、`TC-SPOT-14`（敞口计算）@ `TEST_PLAN/SPOT.md` |
| 产出套保计划 | `TC-PLAN-1`、`TC-PLAN-6`～`TC-PLAN-8`（比率三档）、`TC-PLAN-9`～`TC-PLAN-11`（书面文件 / 审批）@ `TEST_PLAN/PLAN.md` |
| **验证子账户数据隔离** | 见 **§16.3** |
| **数据在 DM8 中正确落库** | 见 **§16.2**（约束映射）+ **§16.6**（假 DAO 档） |

### 16.2 数据模型约束 → 用例映射（逐类，源＝会签 §4）

> **判据来源**：测试席会签意见 §4 逐类确认「§4.3 的约束体系**足以支撑四类派生用例**（唯一→重复插入、非空→空值、外键→孤儿数据、`CHECK`→取值域边界），**无缺口**」。
> **两栏口径**：**应用层**用例＝`TEST_PLAN/*` 的 `TC-*`（经 API / 服务调用触发）；**DB 层**用例＝`T-023` 交付的**假 JDBC 等价引擎**集成测试（直接执行 `INSERT` 并按 DDL 语义强制约束，见 §16.6）。

| 约束类别（会签 §4） | 模型实例（示例） | 派生用例形态（会签 §4） | 应用层用例（含文件归属） | DB 层用例（§16.6） |
|---|---|---|---|---|
| 唯一约束（业务编号） | `uk_product_code` / `uk_hedge_plan_no` / `uk_voucher_no` | 重复插入同编号 → 拒绝 | `TC-PRODUCT-15 @ TEST_PLAN/PRODUCT.md`（品种编码，`code=10010`） | `M1Dm8EquivalentIntegrationTest#duplicateInsertOnUniqueColumnIsRejected` |
| 唯一约束（不可代签 / 快照重签） | `uk_check_signature_task_role` | 同任务同角色二次签署 → 拒绝 | `TC-SETTLE-19, TC-SETTLE-20 @ TEST_PLAN/SETTLE.md` | —（M2 模块） |
| 唯一约束（幂等键） | `uk_command_flow_idem` / `t_capital_receipt` 幂等键 | 重复投递同一事件 → 仅一条生效 | `TC-EXC-4 @ TEST_PLAN/BOUNDARY.md`；`TC-FUND-9 @ TEST_PLAN/FUND.md`；`TC-REL-5 @ TEST_PLAN/NONFUNCTIONAL.md` | —（M2 模块） |
| 条件唯一索引 | `uk_product_spot_primary(is_primary='Y')` | 插入第二条 `is_primary='Y'` → 拒绝 | `TC-PRODUCT-10 @ TEST_PLAN/PRODUCT.md`（主映射唯一，`code=10010`） | 引擎**登记**条件唯一索引（`WHERE` 谓词**不求值**，见 §16.6 披露） |
| 非空约束 | 五类表 `sub_account_id` / 三必填列 | 空值插入 → 拒绝 | `TC-BOUND-6 @ TEST_PLAN/BOUNDARY.md`（计划 ID / 子账户 / 关联对象空值） | `M1Dm8EquivalentIntegrationTest#nullOnNotNullColumnIsRejected` |
| 外键 `RESTRICT` | 追溯链外键 / `sub_account_id`→`t_sub_account` | 孤儿数据插入 → 拒绝 | **无独立 TC**（见 §16.8-Q1） | `M1Dm8EquivalentIntegrationTest#orphanForeignKeyIsRejected` |
| `CHECK`（枚举） | §3.1 全部枚举列 | 取值域外插入 → 拒绝 | **无独立 TC**（见 §16.8-Q1） | `M1Dm8EquivalentIntegrationTest#checkConstraintRejectsOutOfRangeValue` |
| `CHECK`（业务不变量） | `available_qty<=position_qty` / 敞口三分量恒等式 | 违反不变量 → 拒绝 | `TC-SPOT-14, TC-BOUND-9 @ TEST_PLAN/SPOT.md, BOUNDARY.md`（三分量恒等式，应用层） | 复杂 `CHECK` **仅登记不求值**（引擎显式披露，见 §16.6） |
| `CHECK`（数值） | `position_qty>=0` / `amount>0` | 边界 ±1 / 负值 / 0 → 拒绝 | `TC-BOUND-5, TC-BOUND-11 @ TEST_PLAN/BOUNDARY.md` | 单列 `IN (...)` / `= '字面量'` 形态可求值 |

> **M1 侧覆盖判定**：约束体系**九类均有落点**——其中「外键→孤儿」「`CHECK`→取值域」的 **DB 层派生用例**由 `T-023` 集成测试承载（§16.6）；`TEST_PLAN/*` 本身**未设对应独立 TC**（见 §16.8-Q1）。

### 16.3 子账户隔离（M1 验收点「验证子账户数据隔离」）

| 隔离面 | 用例（含文件归属） | 唯一预期值 |
|---|---|---|
| 安全默认＝空集 | `TC-AUTH-22 @ TEST_PLAN/AUTH.md` | 仅见 `SUB-A`；查 `SUB-B` → `code=10004` |
| 跨子账户查看（仅管理层 / 审计岗，只读） | `TC-AUTH-13 @ TEST_PLAN/AUTH.md` | 越权 → 拒绝 |
| 子账户数据权限配置 | `TC-AUTH-12 @ TEST_PLAN/AUTH.md` | 按 `t_user_sub_account_perm` 生效 |
| 子账户数据软隔离（NFR） | `TC-SEC-7 @ TEST_PLAN/NONFUNCTIONAL.md` | 无授权默认空集；WS 越权 `10004` |
| 白名单按子账户 | `TC-PRODUCT-12 @ TEST_PLAN/PRODUCT.md` | 未列入品种被拒 |
| 敞口子账户归属 | `TC-SPOT-17 @ TEST_PLAN/SPOT.md` | 归属显式、可加总 |

### 16.4 敞口三分量一致性（`#17` —— `DATA_MODEL.md` §4.4 必测用例 ①–⑤）

> 判据源：`DATA_MODEL.md:4503`（**已写死**：写入侧拒绝 / 展示侧重算 + 告警 + `COMPONENT_RECALC` 留痕 / 改落库须新快照）。

| `#17` 用例（`DATA_MODEL.md:4503`） | 用例落点（含文件归属） |
|---|---|
| ① 改分量使 `Σ ≠ 合计` → 写入被拒 | `TC-SPOT-14 @ TEST_PLAN/SPOT.md`（步 2）、`TC-BOUND-9 @ TEST_PLAN/BOUNDARY.md`（步 2） |
| ② 展示重算 → 告警 + `COMPONENT_RECALC` 留痕、**未回写** | `TC-BOUND-9 @ TEST_PLAN/BOUNDARY.md`（备注）、`TC-SPOT-14`（备注） |
| ③ 改落库值 → 生成新 `snapshot_id`、旧快照仍可查 | `TC-SPOT-21 @ TEST_PLAN/SPOT.md`（`T-022` 补） |
| ④ 缺分量做比率 → 报错 | `TC-SPOT-22 @ TEST_PLAN/SPOT.md`（`T-022` 补；`TC-SPOT-20` 系**现货价缺口** `Q-12`，非分量缺口，不可替代） |
| ⑤ 缺分量 + 敞口变动 >20% → 无生效触发、仅告警 | `TC-SPOT-23 @ TEST_PLAN/SPOT.md`（`T-022` 补） |

### 16.5 限额三层全序（`#13` —— `DATA_MODEL.md` §4.4 必测用例 ①–⑦）

> 判据源：`DATA_MODEL.md:4498`（**已写死全序**：层②`SUB_INSTRUMENT` > 层①`SUB_PRODUCT` > 层③`ACCOUNT_PRODUCT`；同层并列取**占用率最高者**、再并列取 `id` 升序）。
> ⚠️ `RISK` 模块里程碑 = **M3**（事前风控 C++），但**限额三层的表结构在 M1 建立**，故 `#13` 判据在本视图登记。

| `#13` 用例（`DATA_MODEL.md:4498`） | 用例落点（`TEST_PLAN/RISK.md`） |
|---|---|
| ① 仅层① → `SUB_PRODUCT`（`0x1004`） | `TC-RISK-2` |
| ② 仅层② → `SUB_INSTRUMENT`（`0x1017`） | `TC-RISK-3` |
| ③ 仅层③ → `ACCOUNT_PRODUCT`（`0x1005`） | `TC-RISK-4` |
| ④ 三层同时超 → 必回 `SUB_INSTRUMENT` | `TC-RISK-5` |
| ⑤ 层① + 层③ 同时超 → 回 `SUB_PRODUCT` | `TC-RISK-31 @ TEST_PLAN/RISK.md`（`T-022` 补） |
| ⑥ 同层两条同时命中 → 占用率高者（并列取 `id` 小者） | `TC-RISK-6` |
| ⑦ 错误码与层级一致 | `TC-RISK-2` / `TC-RISK-3` / `TC-RISK-4`（各含错误码断言） |

> **构造纪律**：`#13` ⑥⑦ 依赖 **C++ 内存占用率权威值**，`TC-RISK-2` 备注载「**必须经协议 / 内存构造**（`0x0113`），不得 DB 直插（`S-9`）」。

### 16.6 M1 不依赖 DM8 的「假 DAO」单测档（`RSK-03` 应对）

> `RSK-03`＝DM JDBC 驱动版本不匹配（`INDEX.md` §11）。本机**无 DM8 实例 / 驱动**（`T-023` 实测：`DmJdbcDriver*.jar` 全盘 0 命中、`java/lib` 不存在、5236 无监听、无 `dmserver` 进程）。

| 档 | 载体 | 说明 |
|---|---|---|
| `unit` 档（无外部依赖） | `mvn -q test`（`INDEX.md` §5） | 含既有 272 JUnit；**无需 DM8** |
| **M1 假 DAO 等价档** | `java/src/test/java/com/hedgeguard/dao/testsupport/InMemoryDmEngine.java` | 「**按 SQL 语义求值**」的内存假 JDBC 引擎：把 M1 建表脚本的 `CREATE TABLE` 真喂入，再由**真实 DAO** 生成 `INSERT` / `SELECT` / `UPDATE` 并真正执行、按语义求值返回行（比"仅断言 SQL 字符串"更强） |
| M1 等价集成用例 | `java/src/test/java/com/hedgeguard/dao/M1Dm8EquivalentIntegrationTest.java` | 建表（4 脚本 46 表）→ 读写 → 查询；**四类约束路径**（唯一 / 非空 / 外键 / 取值域）+ 敞口三分量读写 |
| 依赖版本核对 | `TC-DEPLOY-5 @ TEST_PLAN/NONFUNCTIONAL.md` | `RSK-03` 应对；`INDEX.md` §11 |

> ⚠️ **口径边界（诚实披露）**：上述等价档**不构成 DM8 实机验证**——`M1Dm8EquivalentIntegrationTest` 类注释明载「本用例**不构成** DM8 实机验证」、「**DM8 实机**仍待部署方在可达实例上复跑（`T-024` 承接）」。引擎**明确不覆盖**：条件唯一索引 `WHERE` 谓词、子查询、JOIN、聚合（除 `count`）、多列外键、DM 专有函数、事务隔离 / 回滚。
> ⚠️ **并发交付**：本节所引 `java/src/test/**` 两类系 `T-023` **并发交付**（非本席写入）；类名 / 方法名**以实时为准**。

### 16.7 M1 用例索引（按文件）

| 文件 | M1 用例数 | 备注 |
|---|---|---|
| `TEST_PLAN/PRODUCT.md` | 16 | 里程碑 = M1 |
| `TEST_PLAN/SPOT.md` | 23 | 里程碑 = M1 |
| `TEST_PLAN/PLAN.md` | 24 | 里程碑 = M1 |
| `TEST_PLAN/AUTH.md` | 22 | 里程碑 = M1 |
| `TEST_PLAN/CONFIG.md` | 18 | 里程碑 = M1 |
| `TEST_PLAN/BOUNDARY.md` | 见 §16.2 / §16.4 | `TC-BOUND-1` / `-6` / `-7` / `-9` / `-10` / `-12` / `-13` + `TC-EXC-12` 涉 M1 模块 |
| `TEST_PLAN/NONFUNCTIONAL.md` | 见 §16.3 / §16.6 | `TC-SEC-7`、`TC-DEPLOY-5` |
| `TEST_PLAN/RISK.md` | 见 §16.5 | `#13` 三层全序 7 用例（模块属 M3） |

### 16.8 M1 缺口清单（逐条点名，**不得概括为"已覆盖"**）

| # | 缺口 | 证据 | 未覆盖 → 建议承载 |
|---|---|---|---|
| Q1 | 「外键→孤儿」「`CHECK`→取值域」在 `TEST_PLAN/*` **无独立 TC**（仅 `INDEX.md` §1.2 第 5 行作"设计输入"提及） | `grep -rn '孤儿\|引用完整' docs/TEST_PLAN/*.md` → **实测 5 处命中，全在 `INDEX.md`**（`:35` §1.2、`:769`/`:779`/`:784` §16.2、`:857` §16.8 本行；行号以实时为准），**均为散文、无 `TC-` 级**；`grep -cE '^### TC-'` 无对应条目 | **已由 `T-023` 集成测试承载**（§16.6 两方法）；若要求 `TC-` 级追溯，建议 `T-023` / `T-024` 执行时补登映射 |
| Q2 | `#17` 用例 **③（新快照保留）、④（缺分量做比率报错）、⑤（缺分量 + >20% 暂缓判定）** 未单列 TC（**原缺口；已于 2026-09-23 闭环，见右**） | `DATA_MODEL.md:4503` 列 5 用例；`TC-SPOT-14` / `TC-BOUND-9` 覆盖 ①② | ✅ **已闭环（`T-022` 授权补齐，2026-09-23）**——原建议「补 `TC-SPOT-21`～`-23`」**已执行**；落点 `TC-SPOT-21` / `TC-SPOT-22` / `TC-SPOT-23 @ TEST_PLAN/SPOT.md`（见 §16.4） |
| Q3 | `#13` 用例 **⑤（层① + 层③ → 层①）** 未单列 TC（**原缺口；已于 2026-09-23 闭环，见右**） | `DATA_MODEL.md:4498` 列 7 用例；`TC-RISK-2`～`-7` 覆盖 ①②③④⑥⑦ | ✅ **已闭环（`T-022` 授权补齐，2026-09-23）**——落点 `TC-RISK-31 @ TEST_PLAN/RISK.md`（见 §16.5） |
| Q4 | `PRODUCT.md` / `SPOT.md` §1 **缺 `里程碑` 行**（其余 9 个模块文件均含）（**原缺口；已于 2026-09-23 闭环，见右**） | 原写 `grep -n '里程碑' docs/TEST_PLAN/PRODUCT.md docs/TEST_PLAN/SPOT.md` → **0 命中**（**该命题有误**）；实测 `grep -c '^\| 里程碑' docs/TEST_PLAN/PRODUCT.md docs/TEST_PLAN/SPOT.md` → 各 **1**（合计 **2**） | ✅ **已闭环（`T-022` 授权补齐，2026-09-23）**——已于 `PRODUCT.md:23` / `SPOT.md:22` 各补 `\| 里程碑 \| M1 \|`（同形、同位置）⇒ 实测 **11/11** 模块齐；复核命令 `grep -c '^\| 里程碑' docs/TEST_PLAN/*.md` |

> **§16 初次落盘未新增用例**；`Q2` / `Q3` 已由 `T-022` **授权补齐**（2026-09-23；用例正文落 `SPOT.md` / `RISK.md`，见 §16.4 / §16.5）⇒ **已闭环**；`Q1` 仍为**映射缺口**（判据在 `DATA_MODEL.md` 已写死、`TC-` 级承载由 `T-023` 集成测试承担），**非**"需求未覆盖"；`Q4` 为**表述一致性**小项。
