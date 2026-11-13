# 项目工作备忘录 · 后续工作清单：C++ 服务 ↔ CTP 真联

> **来源**：用户 2026-11-13 指令「将任务清单写入项目备忘录或者工作列表，以后实现」；目标 = 把 `hg-trading`（C++ 交易组件）与远程 CTP 前置真正连通并完成真联档验证。
> **时点**：2026-11-13 13:59 +0800；本文件全部计数 / 行号 / 状态引用**以实时为准**。

## 0. 定位与边界（先读）

- 本文件是**面向未来实现的工作列表 / 备忘录**，**不是项目任务台账**：
  - **不新增 `T-` 号、不改动** `docs/TASKS.md` / `docs/BOARD.md` / `docs/TEST_RESULT.md` / `docs/REVIEW.md` 的任何既有行；
  - 现行账面维持 **235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2 = 235` /「全部任务闭环」**（依据 `docs/BOARD.md` §5 第 278 轮，时点 2026-11-12，以实时为准）；
  - 待用户**明确裁定正式立项**时，再按「单写者、逐条留痕、可回退」传导进台账并重算任务数/人时——届时才改变账面。
- 与 `docs/TEST_RESULT.md:18106`「未闭环工单清单」的区别：该章是第 268 轮 `O-6` 承接的**历史挂账清单**（只追加、不回改原文）；本文件是**未来执行序列**，独立成文。两者不互相覆盖。
- 编号 `W-01`~`W-08` 为本备忘录工作项编号，**非 `T-` 号**，不计入任何现行账面。
- 工作项状态取值：`BLOCKED`（前置未满足）/ `READY`（可开工）/ `IN_PROGRESS` / `DONE`。状态更新采用**逐条追加进度行、原文保留**，不静默删除或回改既有行。

## 1. 实测起点（2026-11-13）

| 面 | 实测结论 | 证据 |
|---|---|---|
| 程序面 | **具备**：`cpp/src/main.cc` 188 行（T-207 启动编排已实化）、官方 CTP SDK v6.7.13 在 `sdk/v6.7.13_20260225_api/`、`cpp/build/src/hg-trading` 可执行、`--self-test` PASS（六模块，`ctp_adapter backend=ctp-sdk`）；实际启动成功并监听 `127.0.0.1:27400`，SIGTERM 优雅退出 `exit 0` | `cpp/src/trading_app.cc:551`、`docs/DEPLOY/README.md:196-199` |
| 连接面 | **不具备**：`--dump-config` 显示 `trade_front`/`md_front`/`broker_id`/`user_id`/`flow_path`/`password`/`auth_code`/`app_id`/`auth_token` 全 `<empty>`；`hg.ctp.env_type=SIMULATION`；凭据环境变量全未设置、无 `hg.properties`；仓库内无任何真实 CTP 前置地址 | `cpp/src/app_config.cc:50-60` |
| 缺省启动结果 | `ctp=DEGRADED CTP 前置未配置（hg.ctp.trade_front 为空）`，`state=NOT_READY` ⇒ **未发起任何远程连接** | `cpp/src/trading_app.cc:553-575` |
| 受控尝试 | 注入本地回环占位 front（`127.0.0.1:1`）+ 占位 broker/user、不设任何口令 ⇒ 代码路径确达 `CtAdapter::login` → SDK `ReqUserLogin`，结果 `DEGRADED CTP 登录请求失败：ReqUserLogin 未被 CTP 受理`；无外部连接产生 | 2026-11-13 本地实测（临时数据在 `/tmp`，已清理） |
| 治理前置 | `hg.ctp.env_type=SIMULATION`；L3 准入 5 项未就绪；真联档与实盘观察期均未启动 | `docs/DEPLOY/T086_OBSERVATION_FRAMEWORK.md:7`、`docs/TEST_PLAN/M4.md` §8.1 |

## 2. 工作清单

| 编号 | 工作项 | 类别 | 前置 | 完成判据（可观测） | 状态 |
|---|---|---|---|---|---|
| **W-01** | 取得 CTP 连接参数与凭据 | **外部输入** | — | 部署方提供 `trade_front`/`md_front`（`tcp://host:port`）、`broker_id`、`user_id`、`app_id`、认证码、口令；敏感项**仅经环境变量**注入（`HG_CTP_PASSWORD` / `HG_CTP_AUTH_CODE` / `HG_CTP_APP_ID` / `HG_HGCP_AUTH_TOKEN`），零落盘。`hg-trading --dump-config` 中相应字段非 `<empty>`、敏感项仍脱敏 | **BLOCKED** |
| **W-02** | 确认目标环境与实盘准入 | **治理裁定** | W-01 | 明确模拟评测前置 or 实盘；若 `PRODUCTION` 须 `CFG-22` 二次确认 + 双人复核留痕，并完成 L3 准入 5 项（L2 基线复跑 / 护栏预演 / 回滚演练 / 资金上限 / C++ 装配 + CTP 连通）。判据 = `T086_OBSERVATION_FRAMEWORK.md` §1 启动记录逐项签字 | **BLOCKED** |
| **W-03** | 核验到 CTP 前置的网络连通性 | 环境 | W-01 | 取得地址后验证到 `trade_front`/`md_front` 的 TCP 可达，记录路由 / 端口 / 防火墙证据；当前仅有默认路由 + 1 条 nameserver，无目标地址 ⇒ 不可测 | **BLOCKED** |
| **W-04** | 注入 CTP 配置并启动 `hg-trading` | 本地实施 | W-01, W-02, W-03 | 以环境变量 / 属性文件装载非敏感参数，启动 `cpp/build/src/hg-trading`；确认 `listen=127.0.0.1:27400`、配置校验通过、`ctp` 不再 DEGRADED（或给出明确原因），并观察 CTP 登录回调 `trade_connected=true` | **BLOCKED** |
| **W-05** | 与 Java `hg-biz` 跨进程联调 | 本地实施 | W-04 | 启动业务侧，验证 HGCP `MSG_HELLO`/`HELLO_ACK`、心跳与参数下发，观察 `state=REPLAYING → READY param_version=N`；`health-check.sh trading` 复核通过 | **BLOCKED** |
| **W-06** | 补跑真联档 `T-057` 跨进程用例 | 测试 | W-05 | 执行 `docs/TEST_PLAN/M2.md` §5.4/§5.5/§6 跨进程版本（模拟盘期货开平、期权策略、银期转账回执、组件双向通信），单列证据；按两档并存纪律，**不得以打桩档冒充真联** | **BLOCKED** |
| **W-07** | 收口 M2/M4 测试计划中 `main.cc` 骨架过时表述 | 文档 | — | `docs/TEST_PLAN/M2.md:63/75/105`、`M4.md:60/80/124` 仍称 `main.cc` 66 行骨架 / 不监听 / 真联不可达；实测 188 行且监听 27400。按「只追加显式登记、不回改历史」核定（`docs/TEST_RESULT.md` 清单 #3 已记 188 行） | **READY**（可独立做） |
| **W-08** | 真联证据章、独立复核与实盘观察期 | 交付 | W-01~W-07 | `docs/TEST_RESULT.md` **纯追加**真联证据章并走独立复核；随后按 `T086` 框架启动 `TC-M4-24` 实盘观察期（1–2 个月日历期、不占工时），每日记录、违规告警计数 == 0 | **BLOCKED** |

## 3. 阻塞链与所需外部输入

```
W-01 (外部输入) ─┬─→ W-02 (治理裁定) ─→ W-04 ─→ W-05 ─→ W-06 ─→ W-08
                 └─→ W-03 (网络可达) ──┘
W-07 可独立于主线推进
```

**要启动主线，需用户提供 / 部署方提供：**

1. CTP 交易前置与行情前置地址（`HG_CTP_TRADE_FRONT` / `HG_CTP_MD_FRONT`）；
2. 身份参数（`HG_CTP_BROKER_ID` / `HG_CTP_USER_ID`）；
3. 凭据（**仅环境变量、零落盘**）：`HG_CTP_PASSWORD` / `HG_CTP_AUTH_CODE` / `HG_CTP_APP_ID` / `HG_HGCP_AUTH_TOKEN`；
4. 明确环境：模拟评测前置，或实盘（后者须二次确认 + 双人复核）。

> 上述 1–4 到位后，W-04~W-06 可立即执行并把结果逐条留痕；W-01~W-03 未满足前，**不得声称已连接 CTP**。

## 4. 边界与纪律（实现时必须遵守）

- **零落盘**：口令 / 密钥 / 凭据**不得**写入代码、文档、测试、CLI 参数或属性文件；`app_config.cc` 对敏感项出现在 CLI/属性文件会**拒绝启动**；本文件只登记环境变量**名称**，不登记任何值。
- **不臆造公网 CTP 地址**：未获授权、无凭据、L3 准入未就绪前，不得主动连接真实第三方柜台；本地受控探测仅指向回环占位地址。
- **两档并存硬纪律**（`docs/TEST_PLAN/M2.md` §3.2）：真联档前置未达时一律标「未达（前置未满足）」，不得以 in-process 桩 / 单侧 ctest 冒充跨进程联调。
- **实盘准入**：`hg.ctp.env_type` 切 `PRODUCTION` 须 `CFG-22` / `R-CONFIG-9` 二次确认 + 双人复核；观察期 `TC-M4-24` 不得提前开始。
- **不回改历史**：`docs/TEST_RESULT.md` 只追加；既有章、时点快照、台账原文一律保留；措辞冲突优先语义消解（显式登记）。
- **文档漂移提示**：`M2.md` / `M4.md` 关于 `main.cc` 骨架的表述已过时，实现 W-07 前须重新实测行号 / 监听状态（会漂移）。

## 5. 进度登记（以后实现时逐条追加，原文保留）

| 时间 | 工作项 | 动作 | 结论 | 留痕 |
|---|---|---|---|---|
| 2026-11-13 | 全清单 | 建立本备忘录；状态初值如上 | 8 项均未完成，W-07 可独立开工 | 本文件创建（未提交前为本地工作区改动） |
| 2026-11-13 | W-01 / W-02 / W-04 | 用户指令「开始连接 CTP 测试前置」；完成 SDK 版本形态核查 + **纯 env 注入链路受控实跑**（回环占位 front、零凭据） | 目标裁定为 `SIMULATION`（评测前置）；代码侧 `bIsProductionMode=false` 已就绪、注入链路验证通过；**真实连接仍 BLOCKED 于 W-01**（无前置地址 / 账号 / 凭据） | §6 新增规程与实测留痕；临时数据在 `/tmp` 已清理；仓库仅 `?? docs/BACKLOG.md` |
| 2026-11-13 | W-01 | 用户提供 CTP 评测前置地址，指令「写入配置文件、不使用环境变量」；新建 `./hg.properties` 仅登记 `hg.ctp.trade_front` / `hg.ctp.md_front` 两个**非敏感**键 | 属性文件生效：零环境变量下 `--dump-config` 实测 `config_file=./hg.properties`、两地址读入、4 个敏感项仍 `<empty>`；**未启动服务、未发起任何连接**；W-01 **部分完成**（地址已就位；账号 / 凭据仍缺） | 新增 `?? hg.properties`；`?? docs/BACKLOG.md` 追加本行；属性文件格式依 `app_config.cc:210-260`（`key=value`、`#` 注释、trim） |
| 2026-11-13 | W-01 / W-03 / W-04 | 用户提供测试账号（`broker_id`/`user_id` 写入 `hg.properties`；**口令仅经一次性 env 注入，值不写入任何文件**）；测试前置「不做终端认证」⇒ `app_id`/`auth_code` 留空以跳过 `ReqAuthenticate`；实际启动并发起 CTP 登录 | **W-01 输入齐备、W-03 网络已通**（TCP 30001/30011 可达；运行期 `ss` 实测 `hg-trading` 与两前置均 `ESTAB`）；**但 W-04 未达成**：`ctp=DEGRADED CTP 登录请求失败：ReqUserLogin 未被 CTP 受理`，服务仍 `state=NOT_READY`。⇒ 根因为 **C++ 登录时序缺陷，非配置/凭据/网络问题** | 见下方 §7 诊断；本行不登记任何凭据值；源码零改动 |
| 2026-11-13 | 全清单 / W-09 | 用户指令「裁定 OnFrontConnected 补登录授权及立项方式」⇒ 编排者作出裁定并登记 §8 | ① **准予授权** `OnFrontConnected` 补登录修复（最小改动集 + 测试 + 门禁）；② 立项方式裁定 **仅作 BACKLOG 工作项 `W-09`、暂不动正式台账**；账面维持 235 / 2235 / 全部闭环；不启用新轮次号 | 本文件纯追加 §8 与本行；源码 / 共享文档零改动；裁定已生效，`W-09` 实施待下一步指令 |
| 2026-11-13 | W-09 / W-04 | 用户指令「开始实施 W-09 源码修复」；完成 5 文件源码改动 + 6 个新增单测；双轨构建 / ctest / E1–E9 门禁 + 真实评测前置实测 | **W-09 代码修复 DONE**（`ReqUserLogin 未被受理` 已消除、优雅退出恢复 `exit 0`、双轨 60/60 + 64/64、门禁 9/0/0）；**但 W-04 真联仍 BLOCKED** —— 阻塞原因**迁移**为 SDK 层 `Decrypt handshake data failed`（前置/SDK 密钥或版本形态不匹配），非 W-09 缺陷 | 见 §9 实施章；源码 5 文件 178+/19−；本行不登记任何凭据值；临时数据在 `/tmp` 已清理 |

## 7. CTP 登录失败诊断（2026-11-13 实测，W-04 阻塞根因）

### 7.1 已确认具备

| 项 | 实测证据 |
|---|---|
| 前置地址 | `hg.properties` 4 个非敏感键生效（`--dump-config`：`config_file=./hg.properties`、`broker_id=9999`、`user_id=121964`、两 front 地址；`env_type=SIMULATION`） |
| 凭据注入 | 口令仅经一次性 `env` 传入进程，日志与 `--dump-config` 均显示 `<redacted>`；**未写入任何文件、文档、CLI 参数**；`app_id`/`auth_code` 留空（不做终端认证） |
| 网络层 | `/dev/tcp` 探测 30001/30011 均可连通；运行期 `ss -tnp` 实测 `hg-trading` 与 `182.254.243.31:30001`、`:30011` **均 `ESTAB`**（交易 + 行情前置都连上） |
| 进程面 | `listen=127.0.0.1:27400`、`state=NOT_READY`（等 Java 参数下发） |

### 7.2 根因：异步 `Init()` 后未等 `OnFrontConnected` 即发 `ReqUserLogin`

| 环节 | 代码 | 问题 |
|---|---|---|
| 建连 | `ct_gateway_ctp.cc:45-66` `connect()`：`Create → RegisterSpi → RegisterFront → Subscribe* → Init()` 后**立即 `return true`** | CTP `Init()` 是**异步**的，返回时 TCP 尚未建立 |
| 发登录 | `ct_adapter.cc:125-135` `login()`：`trade_->connect(...)` 返回后**紧接着** `trade_->reqLogin(...)` → `api_->ReqUserLogin(...)` | 连接未建立 ⇒ SDK 返回非 0 ⇒ `kCtNotConnected` ⇒ `DEGRADED` |
| 回调 | `ct_spi_impl.cc:37-39` `OnFrontConnected()` 仅 `sink_->onFrontConnected(false)`；`ct_adapter.cc:397-403` 仅置 `trade_connected_=true` | **不补发 `ReqUserLogin`**；登录请求一旦失败永不重试 |
| 全仓 | `grep` 无 `waitFor` / 条件变量 / `sleep` / `retry` 等等待或重试机制 | 无可恢复路径 |

即：**即使地址、账号、凭据、网络全部正确，当前代码也走不到登录应答**。上一轮回环占位出现的同一报错，其主因亦为此（非「无凭据」）。

### 7.3 次要待查

- **优雅退出未完成**：本次 20s / 12s 两次受控启动，日志均无 `state=STOPPING` / `stopped` / `exit 0`（`timeout` 以 TERM + `kill-after` 收尾，`wait rc=124`）；上一轮回环占位（CTP 未真正建连）则正常 `exit 0`。疑似 CTP SDK 在活跃连接下 `Release()` 阻塞，需单独复现。
- `./data/`（含 `flow-20261028.log`）为 **2026-10-28 历史遗留**，非本次产生；因 `*.log` 被 `.gitignore` 忽略故 `git status` 不显示。本轮未清理（不擅删历史文件）。

### 7.4 结论与待裁定

- **W-01 已齐备、W-03 已通过**（地址 / 账号 / 口令注入方式 / TCP 连通均有实测证据）。
- **W-04 被 C++ 生产代码缺陷阻塞**，非外部依赖。最小修复方向：在 `OnFrontConnected` 回调中（连接建立后）补发 `ReqUserLogin`，或在 `login()` 中等 `trade_connected_` 条件变量后再发（带超时与 SPI 线程安全）；须同步补 `ct_adapter` / `ct_lifecycle` 用例并重跑双轨 `ctest` + E1–E9 门禁。
- 该改动触及 `cpp/src/ctp_adapter/` 生产代码、HGC-1.6 冻结接口与现行「全部任务闭环」账面（235 / 2235），**按项目纪律需用户明确裁定是否修复、以及如何立号登记**；本轮未改任何源码，仅登记诊断。

## 8. 裁定章：OnFrontConnected 补登录授权与立项方式（2026-11-13）

> **依据**：用户 2026-11-13 指令「裁定 OnFrontConnected 补登录授权及立项方式」。先例：`docs/BOARD.md:624` 第 278 轮「用户指令『裁定 OBS-A … 是否收口』」⇒ 编排者作出裁定并登记（2026-11-13 实测，行号以实时为准）。
> **性质**：编排者裁定登记，**纯追加、不回改任何历史行**；**不启用新轮次号**（`docs/BOARD.md` §5 未立行，避免跨账形差，与挂账核定章同口径）；本文件全部计数 / 行号引用**以实时为准**。

### 8.1 裁定项一：修复授权 —— **准予**

- **授权范围**：修复 §7.2 确诊的 CTP 登录时序缺陷，方案锁定为 **`OnFrontConnected` 回调中补发 `ReqUserLogin`**（用户点名方案）。
- **允许的最小改动集**：
  1. `cpp/src/ctp_adapter/src/ct_adapter.cc` / `cpp/src/ctp_adapter/include/hg/ctp_adapter/ct_adapter.h`：`onFrontConnected(false)`（`ct_adapter.cc:397`）在交易前置连上后补发登录；`login()`（`ct_adapter.cc:123`）语义调整为「只建连、登录结果以 SPI 回调为准」，消除启动时重复发登录；补去重与 SPI 回调线程安全（防重复 `ReqUserLogin`）。
  2. `cpp/src/trading_app.cc`：`start()` 的 DEGRADED 判定与 `refreshCtpState`（`:850`）迁移须与异步模型一致——「已连前置但尚未登录」**不等于故障**，避免误标 DEGRADED。
  3. **测试**：补 `ct_adapter` / `ct_lifecycle` 用例覆盖「连上后发登录」「不重复登录」「断线重连」；实施后重跑 **stub + CTP 双轨 `ctest`** 与 `mvn -B -o validate`（E1–E9，须在 `java/` 下执行）。
- **不在授权范围**：不扩 HGCP 协议（无新 `CMD_*`）、不改 HGC-1.6 冻结接口、不动 `DATA_MODEL` / Java 侧 / 前端、不切 `PRODUCTION`、不启动 `TC-M4-24` 观察期。
- **完成判据**：`hg-trading` 连测试前置 ⇒ `trade_connected=true` → `OnRspUserLogin` 应答 → `isLoggedIn()==true` → `ctp` 不再 DEGRADED；`state` 仍按 Java 参数下发迁移 `REPLAYING → READY`。

### 8.2 裁定项二：立项方式 —— **仅作 BACKLOG 工作项 `W-09`，暂不动正式台账**

| 方案 | 评估 | 裁定 |
|---|---|---|
| A. 正式立项（新 `T-` 号，改 `TASKS` / `BOARD`，重算 235 / 2235 / 六区 / 状态） | 连锁代价大：须重算任务数与人时、改 §1 合计行 + §5.2 + BOARD 抬头 + §5 逐轮账面 + `TEST_RESULT` 证据章，并**打破第 278 轮「全部任务闭环」结论**；与第 274 轮「历史快照有意保留、不回改」裁定（`docs/TASKS.md:3441`，2026-11-13 实测）冲突。立号先例（第 274 轮 `T-236`）系**用户明确改判**才触发，本轮指令未含改判 | **不采纳** |
| B. 仅登记为 `BACKLOG` 工作项 `W-09`，源码修复照做，**账面维持不变** | 与 §0 定位一致（BACKLOG 承载「以后实现」、不计入现行账面）；不动共享台账、单写者风险 ∅；待真联档验证完成、需正式交付时再按用户裁定一次性传导并重算 | **采纳** |

- **`W-09`**：修复 CTP 登录时序（`OnFrontConnected` 补登录）+ 配套测试与门禁；类别 = 本地实施（代码）；前置 = §7 已确诊且 W-01 / W-03 已达成；状态 = **READY（裁定已生效，待实施）**。
- **账面维持**：**235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2` /「全部任务闭环」/ 机读 221**（`docs/BOARD.md` §5 第 278 轮，时点以实时为准）。
- **语义消解**：§0「编号 `W-01`~`W-08`」系备忘录建立时点快照，原文保留；`W-09` 为本裁定新增的工作项编号，按「措辞冲突优先语义消解」以本节为准。§6.4 / §7.4 中「W-04 仍 BLOCKED / 待用户裁定」同为执行前时点陈述，原文保留，裁定结论以本节为准。
- **不启用新轮次号**：`docs/BOARD.md` §5 未立行，本裁定仅登记于本文件 §8 与 §5 进度行。

### 8.3 边界与纪律

- **凭据零落盘**：口令仍仅经一次性 env 注入；`hg.properties` 只保留 4 个非敏感键（`trade_front` / `md_front` / `broker_id` / `user_id`）；本裁定章不登记任何凭据值。
- **两档并存**：W-09 完成并取到 `OnRspUserLogin` 应答前，**不得声称已连接 CTP**、不得标真联档通过；W-04 保持 BLOCKED。
- **实盘边界**：`PRODUCTION` 切换、`CFG-22` 二次确认 + 双人复核、L3 准入 5 项、`TC-M4-24` 观察期**不在本裁定范围**。
- **实施纪律**：源码改动须单写者；涉及共享文档时只追加、逐条留痕、可回退；重跑双轨 `ctest` + E1–E9 门禁后方可推进 W-05。
- **本轮范围**：仅作出裁定并登记，**未改任何源码 / 共享文档**；`W-09` 的源码实施待下一步指令。

## 6. W-04 测试前置注入规程（2026-11-13 受控实跑已验证）

### 6.1 本轮「开始工作任务」已完成的准备（实测留痕）

| 项 | 实测结论 | 证据 |
|---|---|---|
| 目标环境裁定 | 用户指令 = **连接 CTP 测试前置** ⇒ 目标 `SIMULATION`（评测前置），**不设** `HG_CTP_ENV_TYPE=PRODUCTION` | `docs/DEPLOY/README.md:199` ②「缺省评测前置」；`app_config.cc:490` 缺省 `SIMULATION` |
| SDK 版本形态（新发现） | SDK **v6.7.13 将评测/生产版合并进同一 `.so`**，由 `Create*Api(..., bIsProductionMode)` 选择，**默认接入生产版**；连评测/测试前置须显式 `false`。本地 SDK 形态为 `*_se_linux64`（`thosttraderapi_se.so` / `thostmduserapi_se.so`，看穿式监管 front_se 变体） | `sdk/6.7.13_API接口说明.md:3373,4602,7302,885`；`sdk/v6.7.13_20260225_api/` 目录实测 |
| 代码是否已处理 | **已处理、无需改码**：`ct_types.h:60-61,89,91` 定义 `kSimulation` ⇒ `productionMode()=false`；`app_config.cc:376` 把缺省 `SIMULATION` 映射为 `kSimulation`；`ct_gateway_ctp.cc:51,162` 显式传入 `CreateFtdcTraderApi` / `CreateFtdcMdApi`；`ct_adapter_test.cc:315-334` U1 用例覆盖 | 源码 + 单测实测 |
| 配置键名（实测） | 本次真联所需**非敏感**键：`HG_CTP_TRADE_FRONT` / `HG_CTP_MD_FRONT` / `HG_CTP_BROKER_ID` / `HG_CTP_USER_ID` / `HG_CTP_FLOW_PATH` / `HG_FLOW_DIR` / `HG_CTP_ENV_TYPE`；**敏感项仅 env**：`HG_CTP_PASSWORD` / `HG_CTP_AUTH_CODE` / `HG_CTP_APP_ID` / `HG_HGCP_AUTH_TOKEN` | `cpp/src/app_config.cc:38-61`；敏感项出现在 CLI/属性文件会被拒绝启动（`docs/DEPLOY/README.md:194`） |
| 注入链路实跑 | 纯 env 注入（**故意不设** `HG_CTP_ENV_TYPE` 以验缺省）→ `--dump-config` 显示 5 个 CTP 非敏感键全部生效、`hg.ctp.env_type=SIMULATION`、4 个敏感项仍 `<empty>`；启动 → `listen=127.0.0.1:27400` → `ctp=DEGRADED CTP 登录请求失败：ReqUserLogin 未被 CTP 受理（异步模型：结果以回调为准）` → `state=NOT_READY` → SIGTERM `state=STOPPING` → `exit 0` | 2026-11-13 本地实测；占位 front `tcp://127.0.0.1:1`、占位 broker/user、**未设任何凭据**；临时数据在 `/tmp`，已清理 |
| 边界 | 占位 front 指向本机回环端口 1；`ss` 所列 ESTAB 均为本机既有 `hbrclient` / `node` / `sshd` 会话，**未见 `hg-trading` 产生任何外部连接**；未连接任何第三方柜台 | 同上 |

### 6.2 仍待部署方提供的输入（W-01，唯一硬阻塞）

1. **评测/测试前置地址**：`HG_CTP_TRADE_FRONT` / `HG_CTP_MD_FRONT`（`tcp://host:port`）——须与本地 `.so` 的密钥形态（评测版）匹配；官方硬规则「评测版 API 只能连评测版前置，生产版 API 只能连生产版前置」（`sdk/6.7.13_API接口说明.md:885,887`）。
2. **测试账号**：向 SIMNOW 或期货公司申请仿真账号（官方说明 `sdk/6.7.13_API接口说明.md:936-938` **只给申请途径、不提供前置地址**）；据此给出 `HG_CTP_BROKER_ID` / `HG_CTP_USER_ID`。
3. **凭据（仅环境变量、零落盘）**：`HG_CTP_PASSWORD` / `HG_CTP_AUTH_CODE` / `HG_CTP_APP_ID`；若启用 Java 联调再给 `HG_HGCP_AUTH_TOKEN`。**不得**写入代码、文档、测试、CLI 或属性文件。
4. **可写流水目录**：`HG_CTP_FLOW_PATH`（CTP SDK 生成 `.con`）与 `HG_FLOW_DIR`（业务流水）；置于部署区或 `/tmp`，勿污染仓库。

> `HG_CTP_ENV_TYPE` **保持不设**（缺省即 `SIMULATION`）或显式 `=SIMULATION`；**不得**设 `PRODUCTION`。切实盘属另一次裁定，须 `CFG-22` 二次确认 + 双人复核 + L3 准入 5 项（`T086` 框架）与 `TC-M4-24` 观察期，**不在本次「测试前置」范围**。

### 6.3 输入到位后的执行序列（W-04 → W-05）

```bash
# 1) 由部署方在其受控环境 export 上述变量（此处只列键名，绝不落值）
: "${HG_CTP_TRADE_FRONT:?}" "${HG_CTP_MD_FRONT:?}" "${HG_CTP_BROKER_ID:?}" \
  "${HG_CTP_USER_ID:?}" "${HG_CTP_FLOW_PATH:?}" "${HG_FLOW_DIR:?}"
# 2) 脱敏复核：5 个 CTP 键应为真实值、4 个敏感项应为 <redacted>、env_type=SIMULATION
./cpp/build/src/hg-trading --dump-config
# 3) 启动交易组件（前台；SIGTERM 优雅退出 exit 0）
./cpp/build/src/hg-trading --verbose
# 4) 另起终端启 Java hg-biz，观察 state=REPLAYING → READY param_version=N
# 5) health-check.sh trading 复核
```

判据：`listen=` 出现、`ctp` 不再 DEGRADED、CTP 登录回调 `trade_connected=true`、`state` 迁移 `READY`；否则按 `trading_app.cc:551-575` 的 DEGRADED 原因逐项定位（前置不可达 / 密钥形态不匹配 / 凭据错误 / 账号未开通），并按两档并存纪律**不得**以桩档结论冒充真联。

### 6.4 未完成事项（如实登记）

- **W-01 / W-03 仍 BLOCKED**：无真实前置地址 ⇒ 无法做 TCP 可达核验，也无法发起真实连接。**本轮未连接 CTP**。
- **W-02 裁定部分完成**（目标 = 测试前置 / `SIMULATION`）；PRODUCTION 治理、`TC-M4-24` 观察期不在本范围。
- **W-04 机制已验证、真实启动待 W-01**；W-05 / W-06 / W-08 依次阻塞；W-08 的实盘观察期部分随实盘裁定另行启动。
- **W-07 仍 READY**，可独立推进。
- 本文件仍未纳入版本控制（`?? docs/BACKLOG.md`）；提交 / push 需单独指令。

## 9. W-09 实施记录：OnFrontConnected 补登录（2026-11-13，编排者 · 单写者）

> 依据：§8 裁定（授权准予 + 立项方式 = 仅 `W-09`、不动正式台账）+ 用户指令「开始实施 W-09 源码修复」。
> 性质：**源码实施 + 逐条留痕**；章节按追加时序落盘（§7/§8/§9 在 §6 之后追加，物理顺序不回改）；全部计数 / 行号**以实时为准**。

### 9.1 改动清单（5 文件，178 insertions / 19 deletions）

| 文件 | 改动 |
|---|---|
| `cpp/src/ctp_adapter/include/hg/ctp_adapter/ct_adapter.h` | 新增 `login_mutex_` + `login_configured_` / `trade_login_pending_` / `md_login_pending_` / `trade_login_rid_` / `md_login_rid_` 私有状态；订正 `login()` 与 `onFrontConnected()` 接口注释 |
| `cpp/src/ctp_adapter/src/ct_adapter.cc` | `login()` 改为**只建连**（预生成 RequestID、保存配置快照、置 `login_configured_`），不再立即 `ReqUserLogin`；`onFrontConnected()` 在**锁外**补发一次登录（临界区只做去重判定 + 取快照，防 SDK 同步回吐 SPI 自锁）；`onFrontDisconnected()` 清去重位以支持断线重登；`logout()` 清 `login_configured_` 阻断残留回调 |
| `cpp/src/trading_app.cc` | `start()` 措辞修正：`login()` 非 `kCtOk` 仅可能是前置连接失败；`!isLoggedIn()` 为**异步模型正常中间态**，DEGRADED 由 `refreshCtpState` 在登录成功后自动解除（迁移逻辑本身正确，未改） |
| `cpp/src/ctp_adapter/tests/ct_adapter_test.cc` | 更新 `login_success_is_async_state_driven` 断言（`login()` 后 logins=0、`bringOnline()` 后 =1）；**新增 6 个 W-09 回归用例** |
| `cpp/src/ctp_adapter/tests/fake_gateway.h` | `reqLogin()` 记录 `last_login_cfg`，供断言补发登录携带的是 `login()` 配置（非空配置） |

**线程安全边界**：新 `login_mutex_` 只保护「配置快照 + 去重位 + 预生成 RequestID」；状态位与退回队列沿用既有无锁/单线程纪律，**不扩面**。RequestID 在启动线程预生成，SPI 回调线程不触碰 `request_seq_`（维持其文档化单线程访问）。`scan_lock_free.sh`（E4）显式排除 `ctp_adapter`（T2/T3 SPI 回调），且该头文件本已有 `query_mutex_` 先例 ⇒ 合规。

### 9.2 新增用例（`ct_adapter_test.cc`，全部纳入 `main()`）

| 用例 | 判据 |
|---|---|
| `front_connected_without_login_does_not_send_login` | 未调 `login()` 时 `OnFrontConnected` 不补发（保护 `ct_order_query_test.cc` 等直接模拟状态位的用例 ⇒ **零回归**） |
| `front_connected_sends_login_only_once_per_connection` | 交易/行情各重复回调 2 次 ⇒ 各只发 1 次登录（防重复 `ReqUserLogin`） |
| `front_connected_login_uses_login_config` | 补发登录携带 `broker_id`/`user_id`/`password`/`trade_front` == `login()` 配置 |
| `front_disconnect_allows_relogin_on_reconnect` | 断线清零去重位 ⇒ 重连重新补发（logins 1→2） |
| `logout_blocks_late_front_connected` | 登出后残留回调不补发 |
| `login_success_is_async_state_driven`（更新） | `login()` 后 logins=0、`bringOnline()` 后 =1，异步回调置登录态 |

### 9.3 门禁与测试（本轮实测）

| 门禁 | 结果 |
|---|---|
| stub 轨增量构建 | `rc=0`，零 `error:` / `warning:` |
| CTP 轨增量构建 | `rc=0`，零 `error:` / `warning:` |
| `ctest --test-dir cpp/build_stub` | **60/60 Passed，rc=0** |
| `ctest --test-dir cpp/build` | **64/64 Passed，rc=0** |
| `mvn -B -o validate`（`java/`） | **E1–E9 9/0/0，arch-check 通过，BUILD SUCCESS** |
| 双轨差异 | 未重新 configure，沿用既有 `CTP_FOUND` 开关（`HG_CTP_REQUIRE_SDK=OFF` 等），无漂移 |

### 9.4 真实评测前置实测（关键）

| 项 | 结果 |
|---|---|
| 配置 | `hg.properties` 4 个非敏感键生效；口令仅一次性 env；`app_id`/`auth_code` 空 ⇒ 跳过 `ReqAuthenticate`；`SIMULATION` ⇒ `bIsProductionMode=false` |
| **W-09 目标达成** | 日志由 `CTP 登录请求失败：ReqUserLogin 未被 CTP 受理` 变为 **`ctp=DEGRADED CTP 前置连接已发起，登录结果待异步回调`** ⇒ 时序缺陷消除，登录请求交由 `OnFrontConnected` 补发 |
| **优雅退出恢复** | `state=STOPPING` → `stopped` → `exit 0`（修复前连真实前置时该路径缺失，见 §7.3 次要待查） |
| **仍未登录** | 无 `ctp=LOGGED_IN` / `DEGRADED 解除`（计数 0）；SDK 层输出 **`Decrypt handshake data failed`** ×2（14s / 25s 两次一致）；CTP 流目录无 `.con` 文件 |
| 零落盘 | 全仓（排除 `.git`/`sdk`/`build*`）口令字面量扫描 **0 命中**；日志与 `--dump-config` 均 `<redacted>` |

### 9.5 W-04 阻塞原因迁移（如实登记）

- **已消除**：§7.2 的 C++ 登录时序缺陷（`Init()` 后立即 `ReqUserLogin`）⇒ W-09 修复 + 单测 + 双轨 ctest 全绿。
- **新阻塞（非 W-09 缺陷、非配置/凭据/网络）**：SDK 层 `Decrypt handshake data failed` —— 官方 `sdk/6.7.13_API接口说明.md:875/887`（2026-11-13 实测）明确「看穿式监管 `_se` 版 API **只能接入 6.5.0+ 的 `front_se` 前置**」「评测版 API 只能连评测版前置，生产版 API 只能连生产版前置」「API、采集库和前置版本必须一一对应，否则**无法连接前置或无法解密采集数据**」。本地 `sdk/` 仅 `thosttraderapi_se.so` / `thostmduserapi_se.so`（看穿式监管版），目标 `182.254.243.31:30001/30011` 端口 TCP 可达但握手解密失败 ⇒ **前置类型 / SDK 版本 / 评测-生产密钥形态不匹配**。
- **待部署方裁定（新增外部输入）**：① 该前置是否为 `front_se`（看穿式监管评测前置）及对应 SDK 版本；② 是否需换用**非 `_se` 普通版** SDK 以连普通 front；③ 评测/生产密钥形态与 `bIsProductionMode` 取值是否对应；④ 提供匹配的 SDK 包与配套前置地址。
- **不得**据此声称已连接 CTP 或标真联档通过；W-04 保持 **BLOCKED**，W-05 / W-06 / W-08 依次阻塞。W-09 状态 = **代码修复 DONE**（§8.1 授权范围内的最小改动集 + 测试 + 门禁全部完成）。

### 9.6 语义消解与边界

- §6.4 / §7.4 / §8.3 中「W-04 仍 BLOCKED / 待用户裁定 / 本轮未改源码」均为**各时点陈述**，原文保留；本节为最新实施结论。
- **账面维持**：235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2` / 全部任务闭环 / 机读 221（本轮机读实测）。未立 `T-` 号、未改 `TASKS`/`BOARD`/`TEST_RESULT`/`REVIEW`/`CODEBUDDY` 任何一行。
- **不启用新轮次号**（`BOARD.md` §5 未立行），避免跨账形差；不新增 `W-` 号（§9.5 待裁定项不单列工作项）。
- 源码 5 文件 + `docs/BACKLOG.md` + `hg.properties` **均未提交**；提交 / push 需单独指令。
