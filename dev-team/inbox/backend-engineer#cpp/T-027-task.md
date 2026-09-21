---
ticket: T-027
type: task
from: project-manager
to: backend-engineer#cpp
priority: P0
created: 2026-09-20T22:00:00+08:00
refs: [docs/ARCHITECTURE.md#9, docs/REQUIREMENTS/NONFUNCTIONAL.md, PROJECT.md#5.1, PROJECT.md#6]
---

## 事由

**M2 首周探路任务，已提前投递**。`T-027` 验证 **CTP SDK 头文件能否在 C++11 边界下编译**（`RSK-02`）——**未过不得铺开 M2 业务开发**（由 `T-030` 汇签并下达铺开许可）。

## 任务详情

| 项 | 内容 |
|----|------|
| 编号 | `T-027` |
| 标题 | `RSK-02` CTP 头文件 C++11 兼容验证 |
| 负责人 | `backend-engineer#cpp` |
| 优先级 | **P0** |
| 依赖 | **无**（CTP SDK 为既有交付物；CTPmini 同栈已跑通） |
| 估时 | **4 人时** |
| 里程碑 | M2（**探路前置**，先于 `T-031` 起业务开发） |
| 需求追溯 | `RSK-02`、`ARCHITECTURE.md` §9、`NFR-COMPAT-1/2 @ REQUIREMENTS/NONFUNCTIONAL.md`、`PROJECT.md` §5.1/§6 |
| 上游产物（唯一事实源） | `docs/ARCHITECTURE.md` **v1.3 / md5 `641b481b…`**（§9 `RSK-02`、§3.2 `ctp_adapter`、§1.2 编译标准约束）；`PROJECT.md` §5.1（技术栈）/ §6（C++11 硬约束·工期）；CTP SDK（合并版 `ThostFtdc*Api.h`） |
| 产出 | `cpp/ctp_smoke/`（最小 `#include` 验证工程）+ 结论记录（**回写本工单「处理记录」**） |

## 要求

1. **任务目标**：以最小程序 `#include <ThostFtdcTraderApi.h>` 在 `-std=c++11 -Werror` 下编译通过，完成兼容性判定。
2. **验收标准（可客观判定，缺一不可）**：
   1. 以最小程序 `#include <ThostFtdcTraderApi.h>` 在 `-std=c++11 -Werror` 下编译通过；
   2. 记录 CTP SDK 版本与编译参数；
   3. 若冲突 → 提 `CH-xxx`（**L1/L2 级，牵动 `PROJECT.md` §6 硬约束**）上报用户，**不得自行放宽 C++11 边界**。
3. **必读上游章节**：`ARCHITECTURE.md` §9 `RSK-02`、§3.2 `ctp_adapter`（交易/行情/资金划转）、§1.2 编译标准约束；`PROJECT.md` §5.1 / §6。
4. **红线**：**不得自行放宽 C++11 边界**（`CMAKE_CXX_STANDARD 11`、禁 `std::make_unique`/`std::optional`/`std::string_view`/结构化绑定/`if constexpr`）；冲突须走 `CH-xxx` 上报用户。
5. **第 7 项 · 待办工单（必办，不得静默跳过）**：你的 inbox 中已有未闭环工单 **`T-ARCH-002-architecture.md`**（`from: tech-lead`，P0，**`OPEN`**）——其「回复要求」第 1 条明确要求**在 M2 D1 完成 `RSK-01`/`RSK-02` 探路并回写该工单「处理记录」**。
   - **本次请一并处理**：完成 `T-027` 后，把结论（编译是否通过 / SDK 版本 / 编译参数 / 是否需 `CH-xxx`）**同时回写 `T-ARCH-002` 工单的「处理记录」**；
   - 若本次确不能处理，**必须在本工单「处理记录」写明暂缓理由与预期处理时间**（不得沉默）。
6. **纪律提醒**：`cpp/ctp_smoke/**` 内你为唯一写者；**不得改** `docs/gates/`、`docs/REQUIREMENTS/`、`docs/ARCHITECTURE.md`、`docs/DATA_MODEL.md`、`docs/TASKS.md`、`docs/BOARD.md`。

## 证据 / 复现

不适用（`type: task`）。结论记录中请附**CTP SDK 版本、编译命令与参数、编译输出**。

## 变更评估

不适用（`type: task`）。若与 C++11 冲突，**另提 `CH-xxx`（L1/L2）** 上报用户，本工单不承载变更评估。

## 回复要求

1. 完成后将**CTP SDK 版本 + 编译参数 + 编译结果 + 是否需 `CH-xxx`** 追加到本工单「处理记录」，并把「状态」置 `CLOSED`。
2. **同步**回写 `T-ARCH-002-architecture.md` 的「处理记录」。
3. 结论同时回报**项目经理**（`T-030` 汇签前需要 4 项探路结论齐备）。
4. 阻塞 1 小时内无法自行解除即上报。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 22:00 | project-manager | 提前投递 M2 首周探路工单（`R-12` 裁决后） | 待 `backend-engineer#cpp` 开工 |
| 2026-09-20 19:20 | backend-engineer#cpp | 完成 `RSK-02` 探路：真实 CTP SDK 编译 + 链接 + 版本取值 | ✅ **通过**（**未** 触碰 C++11 边界，**无需** `CH-xxx`）；附带 2 项**非 C++11** 的施工口径结论（见下「探路结论」），需 `T-030` 汇签确认 |

### 探路结论（`RSK-02`）

> ⚠️ **基线复核（开工前实测 + 收尾复核）**：本工单引用 `ARCHITECTURE.md` **v1.3 / `641b481b…` / 2418 行**；
> **开工时实测现行基线为 v1.5 / `9feae927…` / 2516 行**；**收尾时已推进至 v1.5.1 → v1.5.2**（`32babcacd0…` / 2546 行）。
> 复核要点：**技术负责人已在 v1.5.1 采纳本工单「发现 ②」的订正** ——
> §3.1 的 `CtSpiImpl : CThostFtdcTraderSpi` → `CThostFtdcUserSpi`、`CtMdSpiImpl : CThostFtdcMdApi` → `CThostFtdcMdSpi`；
> §9 `RSK-02` 应对② 的 `<ThostFtdcTraderApi.h>` → `<ThostFtdcUserApi.h>`；`TASKS.md` `T-027` 验收标准 1 的旧名由项目经理同步订正。
> 同时「发现 ①」（CTP SDK include 以 **SYSTEM 头**引入）与 `RSK-02` 状态「**已通过（探路实证）**、CTP SDK `V1.7.7`」均已登记入架构。
> `§7.2.1` 依赖清单在 v1.5 已收紧为 **CTP SDK + protobuf**（`CH-006` 移除 `websocketpp`/`Boost::system`），本次探路**不涉及**这两项被移除的依赖，结论不受影响。
> **锚点以实时 `md5sum` 为准。** 教训：**派单文案中的架构锚点可能已陈旧，开工前必须实时 `md5sum` 复核**。

**结论一句话**：CTP SDK 头文件在 `-std=c++11 -Wall -Wextra -Werror` 下**编译通过**；`RSK-02` 假设的「头文件要求 C++14+」**不成立**。但实测发现 **2 项与 C++ 语言标准无关**、却影响 `ctp_adapter` 施工口径的事实，须由 `T-030` 汇签时确认处置。

**取证方式**：**真实 SDK 验证**（非桩、非等价头文件）。
SDK 位于 `/home/ctpmini/ctpmini_admin/ctp_sdk`（编排者已就绪的环境），**可用且真实**：
`ThostFtdcUserApi.h`(946 行) / `ThostFtdcMdApi.h`(151 行) / `ThostFtdcUserApiDataType.h`(6631 行) / `ThostFtdcUserApiStruct.h`(9830 行) + `libthostuserapi.so` / `libthostmduserapi.so`。
探路程序**链接了真实动态库**并调用 `GetApiVersion()` 取回 SDK 自报版本 —— 这是"非桩"的客观证据。

**记录项**：

| 项 | 实测值 |
|----|--------|
| CTP SDK 自报版本（交易 / 行情） | **`V1.7.7`** / **`V1.7.7`**（`CThostFtdcUserApi::GetApiVersion()` / `CThostFtdcMdApi::GetApiVersion()`） |
| SDK 目录 | `/home/ctpmini/ctpmini_admin/ctp_sdk`（来源：cache 变量 `CTP_SDK_ROOT`） |
| 头文件风格 | **MERGED（合并版 UserApi）** —— 与 `PROJECT.md` §5.1 / `ARCHITECTURE.md` §7.2.1 口径一致 |
| 交易 Api / SPI 类名 | `CThostFtdcUserApi` / `CThostFtdcUserSpi` |
| 编译参数 | `-std=c++11 -Wall -Wextra -Werror`（`__cplusplus == 201103`，`CMAKE_CXX_STANDARD_REQUIRED ON`、`CXX_EXTENSIONS OFF`） |
| 编译器 | `g++ (Ubuntu 15.2.0-16ubuntu1) 15.2.0` |
| 构建类型 | `RelWithDebInfo` |
| 构建结果 | **成功**（`hgcp_ctp_probe` 可执行）；`ctest` 1/1 通过 |

**核心取证命令与输出摘要**：

```bash
# 1) 配置（自动发现 SDK + 链接真实动态库）
cmake -S cpp/ctp_smoke -B build \
      -DCTP_SDK_ROOT=/home/ctpmini/ctpmini_admin/ctp_sdk -DHG_CTP_HEADER_STYLE=MERGED
# -- [RSK-02] 发现 CTP SDK：/home/ctpmini/ctpmini_admin/ctp_sdk，头文件风格 MERGED（ThostFtdcUserApi.h）
# -- [RSK-02] 链接 SDK 动态库：.../libthostuserapi.so;.../libthostmduserapi.so
# -- [RSK-02] CTP SDK 目录以 SYSTEM 头引入：/home/ctpmini/ctpmini_admin/ctp_sdk

# 2) 构建 —— 无任何 warning/error
cmake --build build -j"$(nproc)"
# [100%] Built target hgcp_ctp_probe

# 3) 运行取证
./build/hgcp_ctp_probe
# cxx.standard.is_cxx11      : YES          __cplusplus == 201103
# sdk.linked                 : YES
# sdk.api.version.trader     : V1.7.7
# sdk.api.version.md         : V1.7.7
# sizeof.InputOrderField     : 360   sizeof.OrderField : 784   sizeof.TradeField : 424
# sizeof.DepthMarketDataField: 528   sizeof.TradingAccountField : 384   sizeof.ReqTransferField : 704
# api.surface.bound          : YES (编译期绑定：ReqUserLogin/ReqOrderInsert/ReqOrderAction/
#                                   SubscribePrivateTopic/RegisterSpi/SubscribeMarketData)
# RESULT: PASS
```

探路程序做了 5 类客观判定（全部落在**编译期/运行期事实**上，不含"应该没问题"式断言）：
① `#include <ThostFtdcUserApi.h>` + `#include <ThostFtdcMdApi.h>` 在 `-std=c++11 -Werror` 下编译通过；
② `static_assert` 校验接口形态：`CThostFtdcUserApi`/`CThostFtdcMdApi` 为抽象多态类，SPI 基类为多态**非**抽象类（可默认构造）；
③ `override` 派生 SPI 实现（`CtProbeSpi`/`CtMdProbeSpi`），签名漂移即编译失败 —— 对应 `ARCHITECTURE.md` §3.1 `CtSpiImpl`；
④ 成员函数指针绑定 §3.1 所需能力：`ReqUserLogin` / `ReqOrderInsert` / `ReqOrderAction` / `SubscribePrivateTopic` / `RegisterSpi` / `SubscribeMarketData`（**签名级**校验）；
⑤ 链接真实 `libthostuserapi.so` / `libthostmduserapi.so` 并取回 `V1.7.7`。

**CI 复现路径（一条命令）**：

```bash
CTP_SDK_ROOT=/home/ctpmini/ctpmini_admin/ctp_sdk bash cpp/ctp_smoke/ci/run_probe.sh
# 实测：PASS=11  FAIL=0  → RESULT: PASS
```

`run_probe.sh` 的 11 条断言：`B1` SDK 可得（含头文件 md5）／`B2` 编译通过／`B2b` ctest 全绿／`B3` `__cplusplus==201103`／`B4` 链接真实库并取到版本／`B5` 反证①／`B6` 反证②／`B7` `ldd` 无 DB 库（附录 B 红线 1）／**`B7b` E6 前端零构建链（范围与排除项已自证）**／**`B7c` CMake 无 websocketpp/Boost（`CH-006`）**／`B8` C++11 边界未被放宽。

**C++ 侧最终依赖清单（`CH-006` 后）**：**CTP SDK（本工单实测 `V1.7.7`）+ protobuf**；系统能力为 POSIX Threads / BSD sockets；**已移除 `websocketpp` 与 `Boost::system`**。`cpp/**` 源码级自证：`find_package` 仅 `Protobuf`/`Threads`，全库 `websocketpp`/`Boost::system` **0 处真实使用**（仅 CI 脚本自身的"断言其不存在"守卫字符串），由 `B7c` 固化。详见 `T-026` 工单「C++ 侧最终依赖清单」节。

### 发现（`T-030` 汇签需确认的 2 项施工口径）

**发现 ①：CTP 头文件不是 `-Wextra -Werror` 干净的 —— 与 C++ 语言标准无关，但必须定施工口径**

- 事实：`ThostFtdcUserApi.h` / `ThostFtdcMdApi.h` 的 SPI 基类以 `virtual void OnXxx(...) {};` 内联空实现，**参数具名但未使用**，在 `-Wall -Wextra` 下产生大量 `-Wunused-parameter`；叠加 `-Werror` 即**编译失败**。
  实测首条：`ThostFtdcUserApi.h:55:46: error: unused parameter 'nReason' [-Werror=unused-parameter]`。
- **性质判定**：这**不是** `RSK-02` 所设想的"C++14+ 语法"冲突 —— 头文件中**不含任何** C++14/17 特性（`make_unique` / `std::optional` / `string_view` / `if constexpr` / 结构化绑定 全库 0 命中）。它是**第三方头文件的告警洁癖**问题。
- **本项目处置（已实施，且未触碰 C++11 边界）**：把 CTP SDK 目录作为 **SYSTEM 头**（`target_include_directories(... SYSTEM ...)`）引入 → GCC 抑制第三方头内部告警，而**本工程自有源码仍受完整 `-Wall -Wextra -Werror` 约束**。
  这既满足 `ARCHITECTURE.md` §1.2「零警告」对本方代码的要求，又如实保留 `-Werror` 的拦截力（`B8` 断言编译参数中无 `c++14/17`；反证 `B5` 证明 `-Werror` 真实生效）。
- **请 `T-030` 确认**：`T-031`/`T-033` 搭建 `cpp/src/ctp_adapter/**` 时，**CTP SDK include 目录一律以 SYSTEM 头引入**；若技术负责人要求改用 `-Wno-unused-parameter` 局部抑制，请明确指示（二者效果等价，前者作用域更窄、更不易误伤自有代码）。
- 反证已固化进 CI：`-DHG_CTP_INCLUDE_AS_SYSTEM=OFF` 可复现该冲突（`run_probe.sh` 的 `B5`）。

**发现 ②：`ThostFtdcTraderApi.h` 在本 SDK 上不存在 —— 属**命名口径差异**，非兼容性冲突**

- `T-027` 验收标准原文要求 `#include <ThostFtdcTraderApi.h>`；本 SDK 为**合并版 UserApi**，交易头文件名为 **`ThostFtdcUserApi.h`**，交易 Api/SPI 类名为 `CThostFtdcUserApi` / `CThostFtdcUserSpi`（`CThostFtdcTraderApi` / `CThostFtdcTraderSpi` **不存在**）。
- 实测证据：`fatal error: ThostFtdcTraderApi.h: No such file or directory`（CI `B6` 断言）。
- **性质判定**：与 `PROJECT.md` §5.1（「**CTP SDK（ThostFtdc 合并版 UserApi）**」）与 `ARCHITECTURE.md` §7.2.1 依赖清单**一致**；**不是** C++11 兼容问题，**不构成** `CH-xxx` 事由。
- 两处文档**措辞**需对齐（**仅文档措辞，非设计缺陷**，故未提 `CH-xxx`）：
  - `TASKS.md` `T-027` 验收标准 1 写作 `ThostFtdcTraderApi.h` → 建议改为 `ThostFtdcUserApi.h`（合并版）；
  - `ARCHITECTURE.md` §3.1 写作 `CtSpiImpl : CThostFtdcTraderSpi`（且 `CtMdSpiImpl : CThostFtdcMdApi` 疑为笔误，MdSpi 应派生自 `CThostFtdcMdSpi`）→ 建议改为 `CtSpiImpl : CThostFtdcUserSpi` / `CtMdSpiImpl : CThostFtdcMdSpi`。
  - 探路工程已用 `HG_CTP_HEADER_STYLE=MERGED|CLASSIC` **双风格**参数吸收该差异（同一份源码 + 生成的 include 头，无需改代码即可切换），故上述措辞不影响施工。

**是否需 `CH-xxx`**：**不需要**。C++11 边界未被放宽（`RSK-02` 未触发），两项发现均属施工口径/文档措辞层面，已在本工单与 `T-ARCH-002` 中如实登记，交 `T-030` 汇签裁决。

### 阻塞 / 剩余风险

| # | 内容 | 影响 | 建议 |
|---|------|------|------|
| R1 | SDK 可用性依赖**部署环境**提供的 `CTP_SDK_ROOT`；本次探路用的是 CTPmini 既有 SDK（`V1.7.7`），**客户端实盘柜台版本可能不同** | 实盘 SDK 版本与本次探路不同 → 需复测 | 部署前按 `NFR-DEPLOY-5` 记录实测版本并**重跑 `run_probe.sh`**；探路工程已支持 `CTP_SDK_ROOT` 参数化，复测成本 ~1 分钟 |
| R2 | 发现①的 SYSTEM 头处置**尚未经技术负责人确认** | `T-031` 若按普通头引入会构建失败 | 见「发现 ①」的 `T-030` 确认项 |
| R3 | 本次探路**未验证**：登录/报单/回报等**运行期**行为、CTP 线程模型与 `ARCHITECTURE.md` §2.4（T1–T9）的匹配 | `RSK-02` 只覆盖"编译/链接兼容"，运行期行为不在其范围 | 由 `T-033`（`ctp_adapter`）覆盖；本工单不扩展范围 |

**未执行项（如实声明）**：本次**未**连接任何客服端柜台、**未**创建 API 实例（避免产生流文件/线程/登录副作用）；**未**验证 `CThostFtdcUserApi` 的虚表布局在跨编译器（如 `clang`）下的一致性 —— 部署环境以 `g++` 为准，故未纳入本次探路。

## 状态

- **当前状态**：**CLOSED**（`RSK-02` 探路**通过**；产出与证据见上「处理记录 / 探路结论」；`T-ARCH-002` 已同步回写）
- **终态确认人**：项目经理（`T-030` 汇签由 tech-lead 裁决）
- **终态时间**：2026-09-20（探路执行完成；终态签发待 `T-030`）

