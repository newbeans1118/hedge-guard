---
ticket: T-ARCH-002
type: architecture
from: tech-lead
to: backend-engineer#cpp
priority: P0
created: 2026-09-20T20:30:00+08:00
refs: [docs/ARCHITECTURE.md, docs/DATA_MODEL.md, docs/REQUIREMENTS/INDEX.md#9.1]
---

## 事由

S2 架构阶段完成，C++ 交易组件的施工依据已定稿。本工单交底**必读章节与红线**。

## 上游制品

| 制品 | 路径 | 版本 | 状态 |
|------|------|------|------|
| 架构方案 | `docs/ARCHITECTURE.md` | **v1.1** | 待评审（M1 门禁：`DATA_MODEL.md` 三方会签；M2 门禁：§4.2 TCP 协议已 **FROZEN**） |
| 数据模型 | `docs/DATA_MODEL.md` | **v1.1** | 待评审（M1 门禁对象，须三方会签） |
| 立项书（唯一锚点） | `PROJECT.md` | v1.2 | 已确认 |
| 需求文档集 | `docs/REQUIREMENTS/` | — | **183 FR / 131 R / 50 NFR；17 条待澄清已全部解决**（`INDEX.md` §9.1） |

> ⚠️ **v1.1 重要变更（务必先读 `ARCHITECTURE.md` 开头的「v1.1 修订说明」）**：
> 产品经理通过 **`CH-001` / `CH-002`** 完成 `Q-1`～`Q-17` 全部口径确认，并**新增 2 条 FR
> （`FR-SETTLE-17`、`FR-AUDIT-13`）与 2 条规则（`R-SETTLE-11`、`R-AUDIT-11`）**。
> 架构与数据模型已**全量传导**（逐项登记见 `ARCHITECTURE.md` §12.5 与 `DATA_MODEL.md` §7.2）。
> **请勿再按 v1.0 的"临时默认口径"理解**——尤其：
> 三层限额（`Q-16`）、敞口三类分量（`Q-17`）、交易理由结构化必填（`Q-13`）、
> 加总阻断仅限差异所属子账户（`Q-11`）、价格偏离昨结算价基准 + 2%/5% 梯度（`Q-15`）、
> 核对任务电子签署（`Q-14`）。


## 要求

1. **必读章节（按顺序）**：
   - `ARCHITECTURE.md` §2.4.2 C++ 线程模型（T1–T9，**T4 交易线程是交易内存状态的唯一写者**）
   - `ARCHITECTURE.md` §3.1–§3.6 C++ 模块划分（`ctp_adapter` / `order_engine` / `risk_engine` / `risk_state` / `flow_store` / `hgcp`）
   - `ARCHITECTURE.md` **§4.2 全节（HGCP v1.0 已冻结）** — 帧格式、40 种消息、字段、错误码、心跳与重连
   - `ARCHITECTURE.md` **§4.4 事前风控内存模型**（数据结构选型、RCU 快照、耗时预算、并发安全论证）
   - `ARCHITECTURE.md` **§4.5 崩溃恢复**（流水格式、启动重放、「系统未就绪」状态）
   - `ARCHITECTURE.md` §9 风险（**`RSK-01`/`RSK-02` 必须在 M2 D1 完成探路验证**）
2. **按 v1.1 确认口径实现判定**（**不得按 v1.0 的旧口径**）：
   - **三层限额**（`Q-16`，§3.3 `R-RISK-2`）：层① 子账户+品种（主维度）/ 层② 子账户+合约 / 层③ 账户+品种；
     **单笔按最细命中者、累计三层同时校验**；拒绝须回 `limit_layer`（新错误码 `0x1017`）。
   - **价格偏离**（`Q-15`，`R-RISK-8`）：基准 = **昨结算价**；**(2%, 5%] → `0x1018` 告警 + 需二次确认**；**> 5% → `0x1010` 阻断**；市值单不适用并标注。
   - **时点净持仓**（`Q-3`）：**下单瞬间硬阻断**；敞口取**当日最新快照**，校验记录须带**快照时点**。
   - **期限红线**（`Q-4`）：建仓日起算；上限取「建仓日 + 12 个月」与「合同到期 + 30 天宽限」的**较早者**；**不阻断平仓与展期置换**。
   - **规模红线**（`Q-1`/`Q-2`）：基数 = 年度现货敞口累计量（实物量）；**子账户与全账户双算取严者**；比例由**子账户分类**（默认商品类 90%）决定，**允许品种级覆盖**。
   - **交易理由**（`Q-13`）：`related_type` + `related_plan_id` + `related_exposure_id`（**须同子账户**）+ `time_desc` 四项必填；缺失或不一致 → `0x1019`。
3. **必守红线（`ARCHITECTURE.md` 附录 B）**：
   - ① C++ 组件**不得**出现任何数据库/SQL 代码或链接 DB 库（CI 脚本 `E1`/`E2` 会拦住）；
   - ④ 交易路径**不得**引入 `mutex` / 动态分配 / 同步 I/O（`E4` 脚本 + 基准测试）；
   - ③ **禁止**单侧修改 `proto/hgcp.proto`（M2 协议已冻结，须走 `CH-xxx`）。
4. **协议专项测试**：按 `ARCHITECTURE.md` §4.2.7 的 T1–T11 **独立测试集**（不丢 / 不乱序 / 断连补数 / 缓冲越界降级 / 心跳检出 / 错误码 / 鉴权 / 回环绑定 / `param_version` 单调 / 大消息分片 / 性能）。
5. **安全**：鉴权令牌与资金密码**零落盘**（含核心转储：启动时 `setrlimit(RLIMIT_CORE, 0)`）；监听地址白名单仅 `127.0.0.1`。

## 证据 / 复现

不适用。

## 变更评估

不适用。

## 回复要求

1. 在 M2 D1 完成 **`RSK-01` / `RSK-02` 两项探路**，并把结论（成功 / 失败 / 回退版本）回写本工单「处理记录」。
2. 开始编码前，请确认已读完上述"必读章节"，并把**任何与本文档不一致之处**以 `question` 工单提出（**不得自行偏离**）。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 20:30 | tech-lead | 投递架构交底 | 待 C++ 后端确认 |
| 2026-09-20 19:40 | backend-engineer#cpp | ① 确认已读「必读章节」并完成 `RSK-01`（`T-026`）/ `RSK-02`（`T-027`）两项探路；② 回写探路结论（见下）；③ 提出 **3 项须技术负责人裁决的施工口径问题**（`Q-A`/`Q-B`/`Q-C`） | **两项探路均通过**（详见下节）；**未发现需走 `CH-xxx` 的架构偏离**；3 项问题见下，请裁决 |

---

### 一、必读章节确认

> ⚠️ **基线复核（开工前实测 + 收尾复核，请技术负责人知悉）**：本工单与派单 prompt 引用的是
> `ARCHITECTURE.md` **v1.3 / md5 `641b481bee2678e8e6e931c190d4f86f` / 2418 行**；
> **开工时实测现行基线**为 **v1.5 / md5 `9feae92764125b63702642cf601d633a` / 2516 行**
> （`CH-006` 承载层改为单一 HGCP 通道、C++ 移除 `websocketpp`/`Boost::system`；
> `CH-007` 协议标称 v1.0.1 + 补数消费侧规则）。
> 本节各章节确认与本次两项探路**均按 v1.5 现行文本**执行；`CH-006`/`CH-007`
> **不改变我的探路结论**（`RSK-01`/`RSK-02` 均与通道承载、依赖库集合无关）。
> 同步复核：`TASKS.md` = `063fcf2c22e6458081ad3ceccddf24ed`、`BOARD.md` = `d23ecb5ebecc6dbd5bb707fab46ae629`、`PROJECT.md` = `af72be757ec6f8d9918e0058c97ebd55`（已含 `CH-006`）。
> **收尾复核（本次汇报后）**：技术负责人**已据本次探路把架构推进至 v1.5.1 → v1.5.2**（现测 `32babcacd031af19d7009c8e1cb9befd` / 2546 行），
> 并在 **v1.5.1「修订说明（勘误 + 状态登记）」中逐条采纳本工单的全部 4 项发现**（见下「四、结论被采纳情况」）。
> **锚点以实时 `md5sum` 为准**（本文件在会话中曾 6 次变动，属多写者并发特征）。
> 教训：派单文案中的架构锚点可能已陈旧，**开工前必须实时 `md5sum` 复核**。

已逐节读完并据此施工，**无异议**：

| 章节 | 状态 | 本次施工中的落点 |
|------|------|-----------------|
| §2.4.2 C++ 线程模型（T1–T9，T4 为交易内存唯一写者） | ✅ 已读 | 本次探路程序**不使用任何 `mutex`**，帧收发用「服务端线程 + 主线程 connect/join」的 join 式结构，不引入锁（为 T4 独占模型留白） |
| §3.1–§3.6 C++ 模块划分 | ✅ 已读 | `cpp/CMakeLists.txt` 已建为**顶层构建入口**，模块目录留给 `T-031`；`ctp_adapter` 的可行性由 `T-027` 实测支撑 |
| §4.2 全节（HGCP v1.0 FROZEN） | ✅ 已读 | `T-026` 的 schema 替代物按 §4.2.5 序列化规定编写（proto3 / 枚举 0 保留 `*_UNSET` / 价格为十进制 string / 时间为 int64 ms）；帧头常量按 §4.2.2（`MAGIC="HGCP"`、上限 1 MiB） |
| §4.4 事前风控内存模型 | ✅ 已读 | **本次未施工**（属 `T-031` 起）；RCU 快照 / 三张限额表 / 判定预算的口径已理解，无异议 |
| §4.5 崩溃恢复 | ✅ 已读 | **本次未施工**（属 `flow_store`）；「系统未就绪」状态语义已理解 |
| §9 风险（`RSK-01`/`RSK-02` M2 D1 探路） | ✅ 已读 | 即本次交付 |

### 二、`RSK-01` / `RSK-02` 探路结论（`T-ARCH-002` 回复要求第 1 条）

| 风险 | 结论 | 实测版本 / 参数 | 需 `CH-xxx`？ |
|------|------|----------------|--------------|
| **`RSK-01`** protobuf 在 `-std=c++11 -Werror` 下构建 | ✅ **通过**（技术风险已排除） | `protobuf 3.21.12`（系统包）；`-std=c++11 -Wall -Wextra -Werror`；`__cplusplus==201103`；HGCP 空消息经 `127.0.0.1` 真机 TCP 往返成功（双向 payload 0 字节）；`ctest` 3/3 | **否** |
| **`RSK-02`** CTP 头文件 C++11 兼容 | ✅ **通过**（非桩：真实 SDK） | CTP SDK 自报版本 **`V1.7.7`**；合并版 `ThostFtdcUserApi.h`/`ThostFtdcMdApi.h`；链接真实 `libthostuserapi.so`/`libthostmduserapi.so`；`-std=c++11 -Wall -Wextra -Werror` 编译通过；`ctest` 1/1 | **否**（未触碰 C++11 边界） |

**结论对 `§9` 风险表的直接影响**：`RSK-01`、`RSK-02` 可据本次实测**下调概率/转已缓解**。按 v1.5 §9「风险 → 阶段」，M2 首周必须先验证的**三项**为 `RSK-01` / `RSK-02` / `RSK-06`；本次已闭环**其中两项**（`RSK-01`、`RSK-02`）。**`RSK-06`（Java 端 WebSocket）不在本人工单范围**。`RSK-07`（`websocketpp` 无落点）已随 `CH-006` **闭环**，不再是开口风险。
**v1.5 依赖口径合规性**：`CH-006` 后 C++ 侧硬依赖收敛为 **CTP SDK + protobuf**（已移除 `websocketpp` / `Boost::system`）。本次两个探路工程**完全符合**：`cpp/proto_smoke` 仅依赖 protobuf + 系统线程/socket；`cpp/ctp_smoke` 仅依赖 CTP SDK；**均未引入任何 WebSocket / Boost 依赖**（`ldd` 可见，见 CI `A7`/`B7`）。

**证据位置**：`cpp/proto_smoke/ci/run_smoke.sh`（8 条断言，`PASS=8 FAIL=0`）、`cpp/ctp_smoke/ci/run_probe.sh`（9 条断言，`PASS=9 FAIL=0`）；逐条命令与输出见 `T-026` / `T-027` 工单「处理记录」。

### 三、须技术负责人裁决的 3 项问题（**提交后已被全部采纳，见「四」**）

> ✅ **收尾复核结论：3 项**（`Q-A`/`Q-B`/`Q-C`）**均已被技术负责人在 `ARCHITECTURE.md` v1.5.1 中采纳并落文**，无需再单独裁决。
> 本节保留提出问题时的原文与依据，便于回溯。

> 说明：以下 3 项**均不构成架构偏离**（未违反附录 B 任一条），故**未**走 `CH-xxx`；但它们决定 `T-031` 起 M2 铺开后的施工口径，按「有歧义先问、不猜测」的纪律在此提交裁决。我已按推荐口径实施，若你改判，改回成本很低（均为单点参数）。

**`Q-A`（影响 `T-031`/`T-032` 全局）——冻结物 `proto/hgcp.proto` 在仓库中不存在，`RSK-01` 只能判「技术风险已排除」**

- 事实：`§4.2` 把 `proto/hgcp.proto` 定义为「**唯一定义**，两侧同源生成」；但 `TASKS.md` 中它是 **`T-032` 的产出**，而 `T-032` 依赖 `T-031`（`T-030` 许可前 `BLOCKED`）。**故 `T-026` 要求「用 `proto/hgcp.proto` 编译空消息」在时序上不可满足。**
- 我的处置：在 `cpp/proto_smoke/proto/hgcp_smoke.proto` 放**等价替代物**，文件头显式声明「不承载任何协议语义、不得被任何一侧当作协议定义引用」，并把该事实固化进二进制可打印字段（`schema.is.frozen.contract : NO`）。
- **请裁决**：是否在 `T-032` 的 DoD 增加一条 —— 「`proto/hgcp.proto` 落地后，以 `-std=c++11 -Wall -Wextra -Werror` **重跑** `cpp/proto_smoke`（`HGCP_SMOKE_SCHEMA_FILE` 指向真实 schema）并附证据」？
  我**未**自行创建 `proto/hgcp.proto`：它不在我的写作边界（`cpp/**` + 本人 `bug`/`task` 工单），且附录 B 红线 ③ / `§4.2` 冻结规则明令禁止单侧触碰协议产物。若你认为应由本工单代产，请以 `CH-xxx`/`question` 明确授权。

**`Q-B`（影响 `T-031`/`T-033`）——CTP SDK 头文件须以 SYSTEM 头引入（`ARCHITECTURE.md` §1.2「零警告」的适用边界）**

- 事实：CTP 的 SPI 回调基类用 `virtual void OnXxx(...) {};` 内联空实现，**参数具名未使用**，在 `-Wall -Wextra` 下产生大量 `-Wunused-parameter`；叠加 `-Werror` 即**编译失败**（实测首条：`ThostFtdcUserApi.h:55:46: error: unused parameter 'nReason' [-Werror=unused-parameter]`）。
- **性质**：**与 C++ 语言标准无关**（CTP 头中 `make_unique`/`std::optional`/`string_view`/`if constexpr`/结构化绑定 **全库 0 命中**），属**第三方头文件的告警洁癖**。故 `RSK-02` 所设想的「C++14+ 语法」冲突**不成立**。
- 我的处置（**未放宽 C++11 边界**）：把 CTP SDK 目录作为 **SYSTEM 头**引入 → 抑制第三方头内部告警，**本方源码仍受完整 `-Wall -Wextra -Werror`**。已用反证固化：`-DHG_CTP_INCLUDE_AS_SYSTEM=OFF` 必失败（CI `B5`），且 `B8` 断言编译参数中**无** `c++14/17`。
- **请裁决**：`T-031`/`T-033` 是否**统一采用**「CTP SDK include 目录以 SYSTEM 头引入」？（备选：仅对 `ctp_adapter` 目标加 `-Wno-unused-parameter`，效果等价但作用域更宽、更易误伤自有代码。）我推荐前者。

**`Q-C`（影响全部 M2 业务代码）——protobuf 3.21 已弃用 API 会被 `-Werror` 直接拦下**

- 事实：`MessageLite::ByteSize()` 自 3.21 起 `deprecated`（`message_lite.h:381`），在 `-Werror` 下即编译失败。**这是本次探路真实撞到的第一条构建失败**（首次 `cmake --build` 即此因），已改用 `ByteSizeLong()` 并写入代码注释；CI 反证 `A5` 固化了「用 `ByteSize()` 必须编译失败」。
- **请裁决**：确认 M2 业务代码**一律使用 `ByteSizeLong()`**，**不得**为通过构建而整体加 `-Wno-deprecated-declarations`（那会同时屏蔽其他真问题）。

### 三·补：结论被采纳情况（收尾复核，`ARCHITECTURE.md` v1.5.1）

技术负责人已在 **`ARCHITECTURE.md` v1.5.1「修订说明（勘误 + 状态登记，非变更）」** 中**逐条采纳本工单的全部 4 项发现**，并明确**不构成 `CH-xxx`**（消息仍 40、错误码仍 62、线格式 `VER` 仍 `0x0100`）：

| 本工单发现 | v1.5.1 落文 | 对应条目 |
|-----------|------------|---------|
| 「发现 ②」CTP 合并版 API 名称（§3.1 + §9 + `TASKS.md` `T-027`） | ✅ 已勘误：§3.1 → `CThostFtdcUserSpi` / `CThostFtdcMdSpi`；§9 `RSK-02` 应对② → `<ThostFtdcUserApi.h>`；`TASKS.md` `T-027` 由项目经理同步 | v1.5.1 ① |
| `RSK-01` / `RSK-02` 探路结论 | ✅ 状态更新为「**已通过（探路实证）**」，并写明 **protobuf `3.21.12` → 锁定 `3.21.x`（回退 `3.19.6`）**、真实 CTP SDK **`V1.7.7`** | v1.5.1 ② |
| 「发现 ①」CTP 头 SYSTEM 头引入 + 「`Q-C`」`ByteSizeLong()` | ✅ 两条施工口径均已登记 | v1.5.1 ③ |
| 「发现 ①」`proto/hgcp.proto` 时序缺口 | ✅ 已登记：`RSK-01` **只能判「技术风险已排除」**，不得据此宣称「HGCP 协议已在 C++ 侧验证通过」；§4.2 冻结登记「Schema 文件」行亦加注「真实 schema 落地后须以 C++11 重跑 `cpp/proto_smoke` 并附证据」 | v1.5.1 ④ |

→ **本工单 3 项「待裁决」已全部闭环**；`T-030` 汇签时**无需**再就口径本身裁决，只需核对探路证据是否齐备（按 v1.5 §9「风险 → 阶段」共 **3 项**探路：`T-026`/`T-027`/`T-028`；本次交付**前两项**，`T-028` 属 Java 侧）。

### 四、其他须技术负责人知悉的实测事实（无需裁决，但影响施工）

1. **`ThostFtdcTraderApi.h` / `CThostFtdcTraderSpi` 类名措辞（**现行 v1.5 仍存在，共 3 处**）**：实盘 SDK 为**合并版**，正确命名是 `ThostFtdcUserApi.h` / `CThostFtdcUserApi` / `CThostFtdcUserSpi`（`CThostFtdcTraderApi`/`CThostFtdcTraderSpi` **不存在**，`#include` 报 `No such file or directory`）。需更正处：
   - `ARCHITECTURE.md` **§3.1**：`CtSpiImpl : CThostFtdcTraderSpi` → `CtSpiImpl : CThostFtdcUserSpi`；同处 `CtMdSpiImpl : CThostFtdcMdApi` **疑为笔误**（MdSpi 应派生自 `CThostFtdcMdSpi`，`CThostFtdcMdApi` 是行情 Api 而非回调基类）。
   - `ARCHITECTURE.md` **§9 `RSK-02` 应对②**：写作「用最小 `#include <ThostFtdcTraderApi.h>` 编译验证」→ 应为 `ThostFtdcUserApi.h`。
   - `TASKS.md` **`T-027` 验收标准 1**：同上。
   **属文档措辞，非设计缺陷**（`§7.2.1` 依赖清单的「CTP SDK（ThostFtdc 合并版 UserApi）」才是权威口径），故**未提 `CH-xxx`**。探路工程已用 `HG_CTP_HEADER_STYLE=MERGED|CLASSIC` 双风格参数吸收该差异，**不影响施工**。
2. **`§7.2.1` 依赖获取方式**：本次实测用的是**系统包** `libprotobuf-dev 3.21.12`（非 `FetchContent` 在线拉取）。两者版本同为 3.21.12，但"长期版本线锁定"到底靠**系统包**还是靠 **`FetchContent`** 需明确。我的建议：**CI 与部署优先系统包 3.21.12**（可复现、可审计、不依赖外网），`FetchContent` 仅作离线/异常回退（见下条 3）。
3. **`FetchContent` 回退到 3.19.6 的 3 个真实陷阱 —— 已实测撞到、已修复、回退链路已跑通**（详见 `T-026`）：
   - `protobuf` 的 CMake 入口位置**随版本变化**：v3.19.6 **无顶层 `CMakeLists.txt`**（入口在 `cmake/`，须 `SOURCE_SUBDIR cmake`）；v3.21.12 有顶层 `CMakeLists.txt`。若漏配，`FetchContent_MakeAvailable` 只会 populate 源码而**不配置任何目标**，表现为"configure 静默成功、后续才炸"。
   - protobuf 3.19.6 的 `cmake_minimum_required(VERSION 3.1.3)` **被 CMake 4.x 拒绝**（<3.5 兼容已移除），须显式 `CMAKE_POLICY_VERSION_MINIMUM=3.5`。
   - release 的 `protobuf-cpp-<ver>.tar.gz` 资产**不可**用于 CMake `FetchContent`（无可用入口）；应使用 codeload 源码 tarball（已钉死 SHA256）或 `GIT`。
   **实测结果**：`protobuf 3.19.6` 由 `FetchContent` **从源码构建成功**，HGCP 空消息真机收发成功、`ctest` 全绿。以上均已写入构建脚本注释与守卫，`T-031` 若复用 `FetchContent` 请直接沿用。
4. **`libprotobuf` 运行库大版本为 `32`**（`libprotobuf.so.32.0.12`），与语义版本 `3.21.12` 的映射是 `32 = 21 + 11`。**部署清单请统一记录语义版本 `3.21.12`**，`.so.32` 仅作运维排查线索，避免混淆。

### 五、纪律自检（对照本工单「要求」第 3/4/5 条）

| 红线 | 本次自检 | 证据 |
|------|---------|------|
| ① C++ 组件**不得**出现 DB/SQL 代码或链接 DB 库 | ✅ 未违反 | `cpp/**` 源码 E2 扫描 0 命中；`ldd` 全部产物无 `dmjdbc`/`dmdpi`/`mysql`/`sqlite`/`pq`/`odbc`/`oci`（CI `A7`/`B7`） |
| ④ 交易路径**不得**引入 `mutex`/动态分配/同步 I/O | ✅ 未违反（探路非交易路径，仍主动规避） | 探路源码 E4 扫描 0 命中；改用 join 式线程结构 |
| ③ **禁止**单侧修改 `proto/hgcp.proto` | ✅ 未触碰 | 该文件不存在；我**未**创建，仅用显式标注的替代物（`Q-A`） |
| C++11 严格边界（禁 `make_unique`/`optional`/`string_view`/结构化绑定/`if constexpr`） | ✅ 未违反 | E3 扫描 0 命中；编译参数仅 `-std=c++11`（CI `B8`），且源码内 `#if __cplusplus != 201103L → #error` 硬闸 |
| 仅监听 `127.0.0.1`，禁 `0.0.0.0` | ✅ 遵守 | 探路程序的回声服务仅绑 `127.0.0.1`；全库 `0.0.0.0` 0 命中 |
| 敏感字段零落盘/零日志 | ✅ 遵守 | 探路程序不含任何凭证字段；仅 `GetApiVersion()` 等无敏感信息调用；**未创建 API 实例、未建柜台连接** |
| 不伪造不可得的外部依赖 | ✅ 遵守 | CTP SDK 与 protobuf 均为**真实**依赖；`T-027` 用真实 SDK 而非桩（见 `T-027` 结论） |

### 六、本次**未**验证、请勿据以推断的边界

1. `RSK-01` 的判定对象是**技术风险**（protobuf 生成代码在 C++11 下能否构通），**不是**对冻结协议 schema 的验证 —— **不得**据本次结论宣称「HGCP 协议已在 C++ 侧验证通过」（协议验证属 `T-032`/`T-057`）。
2. `RSK-02` 只覆盖**编译/链接兼容**，**未**覆盖 CTP 运行期行为（登录/报单/回报）、**未**验证 CTP 线程模型与 `§2.4`（T1–T9）的匹配 —— 该部分属 `T-033`。
3. 本次**未**连接任何柜台、**未**创建 API 实例。
4. `RSK-06`/`RSK-07`（WebSocket，含 `websocketpp` 落点）**不在本人工单范围**，本次未做任何判定。

## 状态

- **当前状态**：**RESPONDED / CLOSED**（必读章节已确认；`RSK-01`/`RSK-02` 探路结论已回写；
  **3 项口径问题 `Q-A`/`Q-B`/`Q-C` 已被技术负责人在 `ARCHITECTURE.md` v1.5.1 全部采纳**，其中 `RSK-01`/`RSK-02` 状态已更新为「已通过（探路实证）」→ 本工单**已无待裁决项**）
- **终态确认人**：技术负责人
- **终态时间**：待技术负责人确认（我方交付已完成，无在途写入）

