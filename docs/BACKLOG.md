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
| 2026-11-19 | W-01 / W-03 / W-04 | 服务恢复（交易日）；批量 TCP 探测第一套三组 6/6 通；**`SIMULATION` vs `PRODUCTION` 双模式对照实测**（用户裁定「方案 1：不改代码，先试」，仅运行时 `HG_CTP_ENV_TYPE` 切换） | **`W-04 达成`**：`PRODUCTION`（`bIsProductionMode=true`）下 `Decrypt handshake data failed`=0、`ctp=LOGGED_IN CTP 登录成功（DEGRADED 解除）` 于启动后 +1.2s / +1.4s 两次稳定复现；`SIMULATION` 稳定失败（Decrypt=2、无 LOGGED_IN）⇒ **根因确认为 SDK 评测/生产密钥形态，非 SDK 包版本、非服务可用性** | 见 §10 验证章；仅一次性 env、零落盘、未改任何源码/配置/文档既有行；本行不登记任何凭据值；临时数据在 `/tmp` 已清理 |
| 2026-11-19 | W-10 / W-04 | 用户裁定「方案 B」（新增独立配置解耦 SDK 密钥模式与 `env_type` 治理语义）；实施 5 文件源码改动 + 双轨构建 / ctest / E1–E9 门禁 + **零 env 覆盖**的固化启动验证 | **`W-10 DONE`**：新增 `hg.ctp.api_key_mode`（AUTO/PRODUCTION/EVALUATION，缺省 AUTO 零回归）；`env_type=SIMULATION`（治理：非实盘）+ `api_key_mode=PRODUCTION`（SDK：生产密钥）写入 `hg.properties` ⇒ 启动 +0.1s `LOGGED_IN`、95s 内 ESTAB 恒为 2、Decrypt=0、优雅退出 `exit 0`；`health-check.sh trading` = PASS=2 / FAIL=1（`NOT_READY` 需 Java 参数下发，属 W-05） | 见 §11 实施章；源码 154+/3−；双轨 60/60 + 64/64、门禁 9/0/0；本行不登记任何凭据值；`hg.properties` 被 `.gitignore` 忽略、不入库 |
| 2026-11-19 | W-05 | 用户指令「推进 W-05」；核查 Java 侧启动前置，用 `/tmp` 临时探针（不改产品代码）连真实 C++ 组件验证 HGCP 握手 | **W-05 前半段达成、后半段仍 BLOCKED**：`MSG_HELLO → HELLO_ACK` 握手成功（`authState=AUTHENTICATED`）；但发现 **Java `HgcpClient` 缺 `MSG_HEARTBEAT` 分支**，不回 C++ 主动心跳 ⇒ C++ 判死 `0x0301 ERR_HEARTBEAT_TIMEOUT` 关连接 ⇒ Java 反复重连；且 `CMD_LOAD_RISK_PARAMS` 需真 DM8 + `HG_DB_PASSWORD` + `DmJdbcDriver18.jar`（不随仓库分发） | 见 §12 W-05 章；探针在 `/tmp` 已清理、无残留进程；本行不登记任何凭据值；未改任何产品代码 |
| 2026-11-19 | W-11 | 用户裁定「a,b」：a 提交 §12 登记（`b403a62`）；b 修心跳应答缺陷（方案 A）；实施 2 文件 Java 改动 + 2 用例 + 全量门禁 + **修复前/后公平对照实验** | **修复本身正确但因果判断被证伪**：`MSG_HEARTBEAT → MSG_HEARTBEAT_ACK` 分支补齐（符合 ARCH §4.2.8.5 双向心跳契约），单测 9/0/0、全量 1511/0/0/0、E1–E9 9/0/0、两文件零警告；但对照实验实测**修复前/后跨进程重连几乎一致**（`0x301` 各 3 次、`0x302` 8 vs 10）⇒ 缺 ACK **不是**跨进程重连主因；真实主因另待查（疑似 C++ 单连接治理 + 探针未走完整 `hg-biz` 装配）。W-05 仍 BLOCKED | 见 §13；改动 `HgcpClient.java` 31+ / `HgcpClientGuardTest.java` 49+；探针/基线在 `/tmp` 已清理、无残留进程；本行不登记任何凭据值 |
| 2026-11-20 | dev/test 全流程可用性 | 用户裁定「测试阶段·非验收·SQLite 代 DM8·重点测全流程业务可用性」；派生 SQLite DDL 建库 + 重启 hg-biz + `/tmp` 探针点亮 C++ READY + 全流程 REST 复测 | **SQLite 档可用**：112 表 / 302 索引播种、175 路由、登录与 28 端点 25 绿、写链路落库；`health-check.sh all` = **PASS=6 / FAIL=0 / rc=0**；C++ `state=READY param_version=1`。**W-05 仍 BLOCKED**：产品侧无 `CMD_LOAD_RISK_PARAMS` 编码 / 构造点、`isReady` 守卫死锁；完整装配下 `0x301`/`0x302` 重连致参数下发回执 412/10014「连接中断」 | 见 §14；产物全在 `/tmp`；不改产品代码 / 共享台账；零落盘；账面维持 235 / 2235 / 全部闭环；提交需单独指令 |
| 2026-11-20 | W-05 产品实现 | 用户指令「实现 CMD_LOAD_RISK_PARAMS 全量下发 并排查 HGCP 重连」；Java 4 文件 + 2 新测试类；逐层实测定位重连 4 根因 | **全链打通**：启动自动下发 LOAD ⇒ C++ `NOT_READY→REPLAYING→READY`、增量 `POST /risk/rule` 200/SUCCESS、**重连与 `0x106` 增量归零**、`health-check.sh all` PASS=6/FAIL=0；门禁 1521/0/0/0 + C++ 64/64 + 60/60 + 探针 9/9 | 见 §15；不改线格式 / 契约 / C++；账面维持 235 / 2235 / 全部闭环；提交需单独指令 |
| 2026-11-22 | 组件监控面（阶段 1–3） | 用户指令链「设计监控方案 → 先完成阶段 1 → 进入阶段 2 → 阶段 3」；Java / C++ / 前端分阶段实现：`GET /api/v1/monitor/component`、`CMD_QUERY_STATUS.what` 位掩码 + JSON detail、状态变更事件补推、阈值告警 + 事件驱动 + `/ws/alert` 冻结内推送 | **三阶段全绿**：门禁 1547/0/0/0、E1–E9 9/0/0、C++ 双轨 65/65 + 61/61、前端探针 10/10；端到端 `?refresh=1` 返回 `what=15` 全量指标（replay / queues / counters / ctp / uptime），WS 经既有 `/ws/alert` 收到 `component.ctp` 告警帧，`health-check.sh all` PASS=6/FAIL=0、重连与 `0x106` 增量归零 | 见 §16；不改 `proto/hgcp.proto` / `TradingBridge` 签名 / `java/CONTRACTS.md` / `docs/ARCHITECTURE.md` / 共享台账 / `health-check.sh`；账面维持 235 / 2235 / 全部闭环；未立 `T-` 号、未新增 `W-` 号、未启新轮次号；本行不登记任何凭据值 |

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

## 10. SimNow 连通性验证与 W-04 达成（2026-11-19，编排者 · 单写者）

> **依据**：§8 裁定（W-09 仅作 `BACKLOG` 工作项、不动正式台账）+ 用户裁定「方案 1，不改代码，先试」（仅运行时 `HG_CTP_ENV_TYPE` 对照）+ 用户确认「目标 = SimNow 服务、不做终端认证」。
> **性质**：只读探测 + 受控启动 + **逐条留痕**；章节按追加时序落盘（物理顺序不回改）；全部计数 / 行号**以实时为准**。

### 10.1 目标与服务状态（官网公告核对）

- 用户确认目标为 **SimNow 仿真前置**，**不做终端认证**（`app_id`/`auth_code` 留空 ⇒ `ct_gateway_ctp.cc:85` 跳过 `ReqAuthenticate`）。
- 官方首页公告（WebFetch 走服务端通道读到，2026-11-19 实测）：
  - 2025/07/15：7x24 环境 `182.254.243.31:40001/40011`，**「看穿式前置，使用监控中心生产秘钥」**，BrokerID `9999`；服务时段「交易日 16:00～次日 09:00；非交易日 16:00～次日 12:00」。
  - 2025/06/17：第一套环境 `30001/30011`（第一组）、`30002/30012`（第二组）、`30003/30013`（第三组）。
- 11-14（周六非交易日）第一套 `30001/30011` 曾 `ECONNREFUSED`（errno 111）；当时用户即确认「服务确实不可用」⇒ 属服务端状态，非本地问题。

### 10.2 连通性批量探测（2026-11-19 09:19 +0800，周四交易日）

| 端口 | 用途 | 结果 |
|---|---|---|
| `30001` / `30002` / `30003` | 第一套三组交易 | **CONNECTED**（32 / 37 / 45 ms） |
| `30011` / `30012` / `30013` | 第一套三组行情 | **CONNECTED**（34 / 37 / 42 ms） |
| `40001` / `40011` | 7x24 交易 / 行情 | `ECONNREFUSED`（errno 111） |

⇒ 服务已恢复，第一套 **6/6 全通**；当前配置 `30001/30011` 运行期 `ss` 实测 `hg-trading` 与两前置均 `ESTAB`。**TCP 层阻塞已排除**。

### 10.3 根因确认：SDK 评测/生产密钥形态（双模式对照）

- 官方 FAQ 22（`sdk/6.7.13_API接口说明.md:4104`，2026-11-14 实测）：`Decrypt handshake data failed` = 「终端使用的 api 与前置版本不匹配，应核对版本」。
- SDK v6.7.11+ 已把评测/生产**合并进同一 `.so`**（§3377 / 头签名 `CreateFtdcTraderApi(flow, bool bIsProductionMode = true)`）；本地只有 `thosttraderapi_se.so` / `thostmduserapi_se.so`，**无需下载新 SDK 包**。
- 本项目 `app_config.cc:376` 把 `SIMULATION` → `CtEnvType::kSimulation` → `productionMode()=false`（`ct_types.h:91`）；而 SimNow 前置用**生产密钥** ⇒ 评测密钥解不开 FTD 握手 ⇒ `OnFrontConnected` 不回调 ⇒ W-09 补登录无机会执行。

**用户裁定「方案 1，不改代码，先试」**：仅运行时 `HG_CTP_ENV_TYPE=PRODUCTION`（连的仍是 SimNow 仿真前置、测试账号 `9999`/`121964`，**不下单、无实盘资金**）。Python 驱动的干净对照（排除 shell 变量丢失缺陷）：

| 模式 | `Decrypt handshake data failed` | `ctp=LOGGED_IN` 时点 | exit |
|---|---|---|---|
| `SIMULATION`（`bIsProductionMode=false`） | **2** | 未出现 | 0 |
| `PRODUCTION`（`bIsProductionMode=true`） | **0** | **+1.4s** | 0 |

另一次独立复现 `PRODUCTION` 于 **+1.2s** 出现 `ctp=LOGGED_IN CTP 登录成功（DEGRADED 解除）`。三次有效实测一致，非偶然。

### 10.4 W-04 达成范围（如实界定）

- ✅ **达成**：TCP 连通 → FTD 握手通过 → `OnFrontConnected` → W-09 补发 `ReqUserLogin` → `OnRspUserLogin` → `isLoggedIn()==true` → `refreshCtpState` 自动 `ctp=LOGGED_IN` / DEGRADED 解除（`trading_app.cc:850`）。
- ⏸ **未达成**：服务仍 `state=NOT_READY`（等 Java `hg-biz` 下发 `CMD_LOAD_RISK_PARAMS`），`REPLAYING → READY` 属 **W-05** 跨进程联调；本轮未发任何交易指令、未验证真联档 `T-057`。
- ◐ `ctpflow` 未落 `.con` 流文件（SDK 可能写入工作目录），不影响登录判据；如需可另查。

### 10.5 边界与治理提示（重要）

- **未固化**：`PRODUCTION` 仅是本次授权的**一次性运行时 env**，未写入 `hg.properties`、未改源码、未改任何既有文档行。工作区干净、零落盘、口令仅 env 注入。
- **语义冲突**：项目把 `env_type=PRODUCTION` 等同于「上实盘」（`CFG-22` 二次确认 + 双人复核、`T086` L3 准入、`TC-M4-24` 观察期），但此处仅表示 **SDK 密钥模式**，目标是 SimNow 仿真。若要把该模式变为常态配置或写进规程，**需用户做一次治理裁定**明确「SimNow 仿真下 `env_type=PRODUCTION` 仅用于密钥选择、不构成实盘准入」，否则每次需临时 env 且易与实盘治理混淆。
- **可选解耦**（未授权）：把 `env_type` 与 `bIsProductionMode` 拆成独立配置，需另立工作项、改源码、走双轨 `ctest` + E1–E9 门禁。

### 10.6 语义消解与账面

- §6.4 / §7.4 / §8.3 / §9.5 / §9.6 中「W-04 仍 BLOCKED」「SDK/前置版本形态不匹配、待部署方提供 SDK 包」「均未提交」等均为**各时点陈述**，原文保留；**最新结论以本章为准**：根因是密钥模式（同一 SDK 包内切换即可），服务可用性已恢复，W-04 已达成。
- **账面维持**：235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2` / 全部任务闭环 / 机读 221（本轮未重算，沿用 §9.6 实测，以实时为准）。未立 `T-` 号、未新增 `W-` 号、未启用新轮次号、未改 `TASKS`/`BOARD`/`TEST_RESULT`/`REVIEW`/`CODEBUDDY` 任何一行。
- 本轮仅追加 §5 进度行 + 本章；提交 / push 需单独指令。

## 11. W-10 实施：SDK 密钥模式与 env_type 治理语义解耦（2026-11-19，编排者 · 单写者）

> **依据**：§10.5 提出的方案 B + 用户裁定「B」（新增独立配置、不改 A 的治理歧义）；§8 裁定「仅作 `BACKLOG` 工作项、不动正式台账」继续适用。
> **性质**：源码实施 + 逐条留痕；章节按追加时序落盘（物理顺序不回改）；全部计数 / 行号**以实时为准**。

### 11.1 问题与方案选型

`env_type` 同时承担两种语义：① `CFG-22` 的**实盘准入治理标识**（`PRODUCTION` 需二次确认 + 双人复核）；② 官方 SDK `Create*Api(..., bIsProductionMode)` 的**握手密钥选择**。SimNow 看穿式前置用监控中心**生产密钥**，却属仿真环境 ⇒ 二者冲突（§10.5）。

| 方案 | 做法 | 裁定 |
|---|---|---|
| A | 写入 `env_type=PRODUCTION`（仅运行时或固化） | 未采纳：治理语义混淆 |
| **B** | **新增独立配置 `hg.ctp.api_key_mode`，解耦密钥与治理** | **采纳**（语义最干净） |
| C | 让 `SIMULATION` 也传 `bIsProductionMode=true` | 未采纳：违背 CFG-22 缺省保守与 U1 用例 |

### 11.2 设计（三态、缺省零回归）

- 新增 `CtApiKeyMode { kAuto, kProduction, kEvaluation }`（`ct_types.h`）：
  - **`AUTO`（缺省）** ⇒ `productionMode()` **完全跟随 `env_type`** ⇒ 不配置新键时行为逐字不变，U1 用例 / CFG-22 / 既有部署零回归；
  - `PRODUCTION` ⇒ 强制 `bIsProductionMode=true`（生产密钥）；
  - `EVALUATION` ⇒ 强制 `false`（评测密钥）。
- 该枚举**只决定 SDK 握手密钥**，不表示「是否实盘」；实盘准入仍由 `CFG-22` 治理。
- 值域校验大小写敏感（`AUTO`/`PRODUCTION`/`EVALUATION`），非法值启动期拒绝（错误信息不含敏感值）。
- 键非敏感（`isSecretKeyName` 不拦；`kKeys` 表 `secret=false`），`HG_CTP_API_KEY_MODE` / 属性文件 / CLI 三来源均可装载；`redactedSummary` 与 `CtConfig::redacted()` 均增可观测字段。

### 11.3 改动清单（5 文件，154+ / 3−）

| 文件 | 改动 |
|---|---|
| `cpp/src/ctp_adapter/include/hg/ctp_adapter/ct_types.h` | 新增 `CtApiKeyMode` 枚举 + `CtConfig::api_key_mode` 字段；`productionMode()` 三态逻辑；`redacted()` 增 `api_key_mode=` / `bIsProductionMode=` |
| `cpp/src/app_config.h` | `AppConfig::ctp_api_key_mode` 字段 + 键集注释（`W-10`） |
| `cpp/src/app_config.cc` | `kKeys` 表、构造缺省 `AUTO`、`applyKey`、`validateAppConfig` 值域、`toCtConfig()` 映射、`redactedSummary` |
| `cpp/src/ctp_adapter/tests/ct_adapter_test.cc` | 新增 `api_key_mode_overrides_env_type`（三态覆盖 + 口令零回显） |
| `cpp/src/tests/trading_app_test.cc` | `test_config()` 内补 W-10：缺省 AUTO 零回归 / 值域 / 治理 SIMULATION+生产密钥 / EVALUATION 覆盖 / CLI+env 可达性 |

**冻结面核查**：`java/CONTRACTS.md` §4 冻结的是 Java 侧 `hg.web.*` 基础设施键，**不含 `hg.ctp.*`**；`hg.ctp.*` 归 C++ / `ARCHITECTURE.md` §11.2 `CFG-22` 同族最小键集 ⇒ 新增本地配置键不触碰 HGC-1.6，未新增协议字段。

### 11.4 门禁与测试（本轮实测）

| 门禁 | 结果 |
|---|---|
| stub / CTP 双轨增量构建（改公共头触发大范围重编） | 均 `rc=0`，零 `error:` / `warning:` |
| `ctest --test-dir cpp/build_stub` | **60/60 Passed** |
| `ctest --test-dir cpp/build` | **64/64 Passed** |
| `mvn -B -o validate`（`java/`） | **E1–E9 9/0/0，arch-check 通过，BUILD SUCCESS** |
| 值域实测 | `AUTO`/`PRODUCTION`/`EVALUATION` 生效；`BOGUS` 启动期拒绝 |

### 11.5 固化启动验证（零 env 覆盖）

`hg.properties`（被 `.gitignore:51` 忽略、不入库）纯追加 `hg.ctp.api_key_mode=PRODUCTION`；`env_type` 保持缺省 `SIMULATION`。**不设 `HG_CTP_ENV_TYPE` / `HG_CTP_API_KEY_MODE`**，仅口令按硬约束走 env：

| 判据 | 结果 |
|---|---|
| `--dump-config` | `env_type=SIMULATION` + `api_key_mode=PRODUCTION` + `config_file=./hg.properties` |
| CTP 登录 | `+0.1s` `ctp=LOGGED_IN CTP 登录成功（DEGRADED 解除）`，全程仅 1 次 |
| 95s 稳定性 | 6 次采样到前置 **ESTAB 恒为 2**、`Decrypt`=0、后续 DEGRADED=0、SIGTERM → `stopped` → `exit 0` |
| 对照 | `AUTO`（跟随 SIMULATION）仍 `Decrypt` 失败 ⇒ 新键只切密钥、不改治理语义 |

### 11.6 可用性（按部署约定 `health-check.sh trading`）

```
[PASS] 进程存活
[PASS] HGCP 回环端口可连 127.0.0.1:27400（CFG-20）
[FAIL] 就绪态 = NOT_READY：交易指令将被拒（0x0401），须等 Java 参数下发/重放完成
汇总：PASS=2 FAIL=1
```

**如实界定**：CTP 连接/会话层**可用且稳定**；业务就绪层按 §4.2.6 / `R-TRADE-13` 停在 `NOT_READY`，需 Java `hg-biz` 下发 `CMD_LOAD_RISK_PARAMS` 才能 `REPLAYING → READY`（**W-05**）。这是既定就绪门，非 C++ 缺陷；本轮未发任何交易指令、无实盘资金。

### 11.7 语义消解与边界

- §6.4 / §7.4 / §8.3 / §9.6 / §10.6 中「W-04 仍 BLOCKED」「待部署方提供 SDK 包」「仅一次性 env」「均未提交」等均为**各时点陈述**，原文保留；最新结论以 §10 / 本章为准：W-04 已达成、W-10 已固化、无需新 SDK 包。
- **账面维持**：235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2` / 全部任务闭环 / 机读 221。未立 `T-` 号、未新增 `W-` 号、未启用新轮次号、未改 `TASKS`/`BOARD`/`TEST_RESULT`/`REVIEW`/`CODEBUDDY` 任何一行。
- 零落盘：口令仅一次性 env；源码/测试/文档无凭据值；`hg.properties` 只含非敏感键且被忽略。
- 过程中两次 shell `&` 变量作用域陷阱导致日志/pid 路径异常与残留进程，已按 `comm` 精确 TERM 清理并复核（端口释放、无残留、误写文件已删）；最终验证改用 Python 驱动，结果可靠。
- 源码 5 文件 + `docs/BACKLOG.md` 的提交 / push 由后续单独指令办理；`hg.properties` 永不入库。

## 12. W-05 推进：跨进程 HGCP 联调（2026-11-19，编排者 · 单写者）

> **依据**：§2 `W-05`「与 Java `hg-biz` 跨进程联调」+ 用户指令「登记，提交，推进 W-05」。
> **性质**：只读核查 + `/tmp` 临时探针（**不改任何产品代码**）+ 逐条留痕；章节按追加时序落盘；全部计数 / 行号**以实时为准**。

### 12.1 前置核查（真实阻塞链）

| 前置 | 现状 | 依据 |
|---|---|---|
| Java 运行 classpath | `java/target/classes` 存在（无 jar / 无 `target/dependency`）；本地 m2 有 `protobuf-java-3.21.12.jar` / `gson-2.10.1.jar` | 实测 `~/.m2`、`java/target/` |
| HGCP 桥装配 | `Bootstrap.assembleHgcpClient()` **嵌在 `config.databaseConfigured()` 分支内**；`hg.db.url` 为空 ⇒ 连桥都不装配 | `Bootstrap.java:283-356`、`:454`；`Config.databaseConfigured()` = `databaseUrl()!=null` |
| 凭据 | 缺 `HG_HGCP_AUTH_TOKEN` ⇒ 桥不装配；缺 `HG_DB_PASSWORD` ⇒ 不装配数据库 | `start-hg-biz.sh:46-50` |
| 真库 | `DmJdbcDriver18.jar` 不随仓库分发，需部署方提供；`dm` profile system scope | `CODEBUDDY.md` / `java/pom.xml` |
| C++ 侧鉴权 | 未配 `hg.hgcp_auth_token` ⇒ 不注入 `TokenVerifier` ⇒ **空 token 放行**（`auth_guard_.enabled()==false`） | `trading_app.cc:474`、`server_loop.cc:299`、`auth.cc:52` |

⇒ 完整 `hg-biz`（含参数下发）需 **真 DM8 实例 + DM JDBC 驱动 + 两个凭据**，属外部硬前置，本地不可补。但 `HgcpClient` 构造**不依赖 DB**，故 W-05 的**握手/心跳前半段**可独立验证。

### 12.2 前半段实测：`MSG_HELLO → HELLO_ACK` 握手成功

`/tmp` 临时 Java 探针（`W05Probe`，仅引用现有 `HgcpClient` / `TcpHgcpTransport`，空 token、`tokenVersion=1`）连真实 `hg-trading`（`127.0.0.1:27400`）：

```
[+0.5s .. +2.0s] authState=AUTHENTICATED  isReady=false
[+2.5s .. +3.0s] authState=HELLO_SENT     ← 断线重连
[+3.5s .. +4.0s] authState=AUTHENTICATED
stderr: INFO: HGCP 重连成功 / WARNING: HGCP 错误帧 0x301
```

C++ 侧同时：`listen=127.0.0.1:27400` → `ctp=LOGGED_IN`（SimNow）→ `state=NOT_READY`（等参数）。⇒ **握手门禁通过**（`AUTHENTICATED`），但**连接不稳定，反复重连**。探针在 `/tmp` 已清理、无残留进程、零落盘。

### 12.3 发现：Java `HgcpClient` 缺心跳应答（真实产品缺陷）

- **架构口径**：`docs/ARCHITECTURE.md:978` 明确心跳**双向**——「两侧各自定时发送 `MSG_HEARTBEAT`，对端回 `MSG_HEARTBEAT_ACK`」；`:976`「连续 3 次未收到 `HEARTBEAT_ACK`」判死；`:1360` `0x0301 ERR_HEARTBEAT_TIMEOUT` ⇒ 关闭连接 → 重连。
- **实测**：Java `HgcpClient.onFrame()`（`HgcpClient.java:253-295`）只处理 `MSG_HELLO_ACK` / `MSG_AUTH_FAIL` / `MSG_HEARTBEAT_ACK` / `MSG_ACK` / `MSG_ERROR` / `MSG_RESUME_DATA`，**没有 `MSG_HEARTBEAT` 分支** ⇒ C++ 主动发来的心跳落进 `default` → `isEventType(0x0004)==false` → 仅 `LOG.fine` 忽略，**不回 ACK**。
- C++ 侧 `session.on_tick()` 连发 3 次心跳未收 ACK ⇒ `0x0301` + `close_conn()`（`session.cc:146`、`server_loop.cc:208-216`）⇒ Java `onDisconnected()` 重连，循环。
- `MSG_HEARTBEAT` 常量存在（`HgcpConstants.java:21`）；`HgcpClientGuardTest` 只覆盖下单/事件去重，**无心跳应答用例** ⇒ 该缺口此前未被跨进程真联暴露（既有 `M2EndToEndAcceptanceTest` 用 `InMemoryTradingBridge` 进程内桩，明载「不构成跨进程真联」）。

**结论**：W-05 前半段（握手）达成，但**连接无法稳定保持**；这是 Java 侧产品缺陷，不是探针或 C++ 问题。

### 12.4 后半段仍 BLOCKED

`CMD_LOAD_RISK_PARAMS` 下发 → `state=REPLAYING → READY param_version=N`（§2 W-05 判据）需要：
1. 修复 §12.3 心跳应答缺陷（否则连接反复重连，参数下发无法完成）；
2. 真 DM8 实例 + `DmJdbcDriver18.jar` + `HG_DB_PASSWORD`（`ParamDispatchService` 依赖 `DmParamDispatchDao` / `TxTemplate`）；
3. `HG_HGCP_AUTH_TOKEN`（若启用鉴权，C++ 侧也须配同值）。

⇒ **W-05 未闭环**；`health-check.sh trading` 的 `NOT_READY` FAIL 在本轮仍如实成立。

### 12.5 待裁定（未擅自实施）

按项目纪律，改 Java 生产代码需先裁定。建议方案（**最小修复**）：

| 方案 | 做法 | 影响面 |
|---|---|---|
| **A（推荐）** | 在 `HgcpClient.onFrame()` 增 `case MSG_HEARTBEAT:` → 解析 `MsgHeartbeat` 并回 `MSG_HEARTBEAT_ACK`（`echo_ts_ms` / `server_ts_ms`）；补 `HgcpClientGuardTest` 用例断言「收到 C++ 心跳必回 ACK」 | `HgcpClient.java` + 1 测试；符合 §4.2.8.5 双向心跳契约 |
| B | 保持现状，先在 `docs/BACKLOG.md` 登记为已知缺陷 | 零代码改动，W-05 仍无法推进 |

若采用 A，需另立工作项、改源码、跑 `mvn -B -o -Punit clean test` 全量 Java 门禁 + 双轨 ctest（Java 改动不触 C++，但按惯例复核）。

### 12.6 边界与账面

- 本轮**未改任何产品源码 / 共享台账 / 配置**；探针为 `/tmp` 临时类、已删除；工作区干净（W-10 提交 `d04a19c` 后仅本文件追加）。
- 口令仅一次性 env、零落盘；未发交易指令、无实盘资金；未臆造公网地址。
- **账面维持**：235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2` / 全部任务闭环 / 机读 221。未立 `T-` 号、未新增 `W-` 号、未启用新轮次号。
- 本轮仅追加 §5 进度行 + 本章；**提交 / push 需单独指令**。

## 13. W-11：心跳应答补齐与因果判断证伪（2026-11-19，编排者 · 单写者）

> **依据**：§12.5 方案 A + 用户裁定「a,b」（a=提交 §12 登记，b=修心跳应答缺陷）。
> **性质**：Java 源码实施 + 全量门禁 + **公平对照实验** + 逐条留痕；章节按追加时序落盘；全部计数 / 行号**以实时为准**。

### 13.1 a：登记提交

`b403a62` `ledger(W-05): 跨进程 HGCP 握手实测与心跳应答缺陷登记 —— BACKLOG §12 纯追加（0 删行·账面不变）`（1 文件 / 67+/0−）。

### 13.2 b：修复内容（2 文件，31+ / 49+）

| 文件 | 改动 |
|---|---|
| `java/src/main/java/com/hedgeguard/bridge/HgcpClient.java` | `onFrame()` 增 `case MSG_HEARTBEAT:` → `onHeartbeat(d)`；新方法解析 `MsgHeartbeat.sent_ts_ms`，回 `MSG_HEARTBEAT_ACK{echo_ts_ms=原样回显, server_ts_ms=Clocks.epochMilli()}`。写帧失败仅 `WARNING`（不主动重连，避免与读线程 `onDisconnected()` 重入） |
| `java/src/test/java/com/hedgeguard/bridge/HgcpClientGuardTest.java` | +2 用例：`heartbeatFromComponentIsAcked`（收 C++ 心跳必回 ACK、echo 逐字回显、server_ts 非 0）、`heartbeatIsNotTreatedAsEvent`（心跳不落入事件消费分支、仍回 ACK） |

**契约依据**：`docs/ARCHITECTURE.md:978`「心跳双向，两侧各自定时发 `MSG_HEARTBEAT`，对端回 `MSG_HEARTBEAT_ACK`」；`proto/hgcp.proto:338-348` 字段定义；`§4.2.8.5 / :1114` `MsgHeartbeatAck{echo_ts_ms, server_ts_ms}`。修复前 `HgcpClient.onFrame()` 无 `MSG_HEARTBEAT` 分支 ⇒ C++ 主动心跳落入 `default`/`isEventType==false` 被忽略、不回 ACK —— 该**协议层缺口属实**。

### 13.3 门禁（本轮实测）

| 门禁 | 结果 |
|---|---|
| `mvn -B -o -Punit -Dtest=HgcpClientGuardTest test` | **9/0/0**（原 7 → 9） |
| `mvn -B -o clean test`（全量标准门禁） | **1511/0/0/0 BUILD SUCCESS**（原 1509） |
| 本次两文件 `-Xlint:all` 警告 | **0**（日志中 unchecked 警告来自 HEAD 既有的 `Cas24SelfcheckServiceTest` 等，非本次引入） |
| `mvn -B -o validate`（E1–E9） | **9/0/0，arch-check 通过** |

### 13.4 关键：公平对照实验证伪 §12.3 的因果判断

§12.3 曾判断「Java 缺心跳 ACK ⇒ C++ `0x0301` 判死 ⇒ 跨进程反复重连」。为验证，把 **HEAD（修复前）的 `HgcpClient.java` 单独编译到 `/tmp/hg-baseline`**（classpath 优先覆盖），用**同一探针、同样 12s、同一 C++ 组件**对照：

| 版本 | state 迁移 | `0x301` | `0x302` | 重连成功 |
|---|---|---|---|---|
| 修复前 HEAD | 6 | 3 | 8 | 11 |
| 修复后 W-11 | 7 | 3 | 10 | 13 |

**结论**：两版几乎一致 ⇒ **缺心跳 ACK 不是跨进程重连的主因**，§12.3 的因果推断**证伪**。修复后 `0x301`（C++ 心跳判死）与 `0x302`（`ERR_DUPLICATE_CONNECTION`，C++ 单连接治理：旧连接未释放时新连接被拒）仍周期性出现；单次连接探针（8s）也在 +3.5s 断连。

**可能的真实主因（未定论，不臆断）**：探针是**简化装配**（空 token、无 DB、无完整 `hg-biz` 参数下发/补数流程），与 C++ 服务端「单连接治理 + 重连补数 + SEQ 单调校验」交互；`onDisconnected()` 重连时 `restartReader()` 与旧连接释放存在时序竞态。需**完整 `hg-biz`（真 DM8 + 驱动 + 凭据）**才能定论，不在本轮可判定范围。

### 13.5 处置与边界（重要）

- **修复予以保留**：`MSG_HEARTBEAT → ACK` 分支在协议契约层**独立正确**、零回归、单测与全量门禁通过；即便它不是本次重连主因，也是架构明文要求且此前缺失的机制（既有 `M2EndToEndAcceptanceTest` 用进程内桩，明载「不构成跨进程真联」，故未暴露）。
- **登记更正**：§12.3 的标题性结论「这就是跨进程重连主因」以本章为准；§5 / §12 各进度行与正文**原文逐字保留、零回改**，语义消解统一由本章（§13.4 / §13.5）显式登记 —— 符合「勘误不回改历史」且保持 diff 纯追加。
- **W-05 仍 BLOCKED**：完整联调依赖 ① 真 DM8 + `DmJdbcDriver18.jar` + `HG_DB_PASSWORD` ② `HG_HGCP_AUTH_TOKEN`（可选）③ 查明 §13.4 的跨进程重连时序问题（需完整装配后复验，不擅自继续改产品代码）。
- **未擅自扩大改动**：曾怀疑并发写（`onFrame` 读线程与 `heartbeatLoop` 共享无锁 `transport.send`），经对照实验后**未**据此加同步/改更多代码 —— 避免在无定论时引入未验证的改动。
- 探针 / baseline 类均在 `/tmp`，已删除；无残留进程；口令仅一次性 env、零落盘；未发交易指令、无实盘资金。
- **账面维持**：235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2` / 全部任务闭环 / 机读 221。未立 `T-` 号、未新增 `W-` 号、未启用新轮次号、未改共享台账任何一行。
- 本轮改动（Java 2 文件 + `docs/BACKLOG.md` 追加）**未提交**；提交 / push 需单独指令。

## 14. dev/test 全流程业务可用性实跑（2026-11-20，编排者 · 单写者 · 纯追加）

> **用户裁定**：当前连接 SimNow 属**测试阶段、非验收**；**允许以 SQLite 代替 DM8**；目标 = 重点测试全流程业务可用性。
> **性质**：纯运行验证 + `/tmp` 测试探针，**不改产品代码、不改共享台账任何既有行**；产物全在 `/tmp`，不入库。
> **时点**：2026-11-20；全部计数 / 行号 / 状态**以实时为准**。账面维持 235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2` / 全部任务闭环 / 机读 221（沿用第 279 轮，未重算）。

### 14.1 SQLite 落地（CH-016 dev/test 档）
- 驱动 `org.xerial:sqlite-jdbc:3.36.0.3` 置于 `/tmp/sqlite-jdbc.jar`（无 SLF4J 传递类、含 SPI `java.sql.Driver`），仅加入 `HG_BIZ_CLASSPATH`，**不改 pom.xml、不新增 Maven 坐标**；本地 m2 无该 jar（T-087.1.1「SQLite 真实集成落点不可达」结论复现）。
- `/tmp/hg_sqlite_ddl.py` 从 11 个 `m*.sql` 派生 `/tmp/hg-sqlite-schema.sql`：剥离 `COMMENT ON` 844 行、后置 `ALTER TABLE` 10 处、`IDENTITY(1,1)`→`INTEGER PRIMARY KEY`、删除表级单列主键、`SET IDENTITY_INSERT` 4 处；产物 `/tmp/hg-dev.db` = **112 表 / 302 索引**，播种 `t_config_item` 68 / `t_role` 7 / 系统行 2。
- `hg.properties`（被 `.gitignore` 忽略、不入库）追加 2 个非敏感键 `hg.db.url=jdbc:sqlite:/tmp/hg-dev.db` / `hg.db.driver=org.sqlite.JDBC`；`JdbcConnectionFactory` 驱动类名 / URL 本就配置注入（`Config.java:62-66`、`Bootstrap.java:300-304`），**连接层零代码改动**。
- 实测 `DmDialect` 的 6 个 DM 特有方法在生产 DAO 中调用数**全为 0**，唯一有调用的 `paginate` 返回 `LIMIT ? OFFSET ?`（SQLite 原生支持）⇒ 方言层无冲突，**未新增 `SqliteDialect`**。

### 14.2 Java 侧全流程业务可用性（通过）
- 装配：`已装配数据库层`、`HGCP 桥建连成功 127.0.0.1:27400`、`参数下发服务已装配`、18 业务服务 / **175 路由**。
- `/tmp/hg_flow_test.py` 驱动：登录 200/code0 → 9 业务模块 + 支撑模块 28 个端点，**25 个 200+code0**；3 个 NON-OK 均为业务语义拒绝（品种不存在 10001 / 无历史价 10015 / 无结算单 10015），非系统故障。
- 真实写链路：`POST /product` → SQLite `t_product` 落库 1 行 → `GET /product` 读回；重复编码 409/10010（唯一约束生效）、`t_product_change_log` 留痕 1 行。
- 注：`start-hg-biz.sh:47` 的 warn「未设 `HG_DB_PASSWORD` ⇒ 不装配数据库」与实测不符 —— 真实开关是 `hg.db.url`（`Config#databaseConfigured()`，`Config.java:307-309`）。

### 14.3 C++ READY 的产品缺口（与数据库选型无关）
- `ParamChangeEncoder.java:36-60` 的 `encode()` **无 `CMD_LOAD_RISK_PARAMS` 分支**，落入 `default` 抛「本里程碑未实现的参数变更类型」；全仓无该类型构造点。
- `ParamDispatchServiceImpl.java:272-277` 的 `bridge.isReady()` 守卫 ⇒ 启动期死锁（要 READY 才能下发，要下发才能 READY）；`POST /risk/rule` 实测 503/10002。
- `HgcpClient.dispatch()` 本身仅查 `authState`，不查 isReady ⇒ 生产守卫在服务层。

### 14.4 /tmp 探针点亮 C++ READY（不改产品代码）
- `/tmp/HgLoadProbe.java`（复用 `HgcpFrame.encode` 帧编码）模拟 Java 侧全量下发：
  1. 干净重启 `hg-trading`（`HG_FLOW_DIR=/tmp/hg-flow-clean`、真实 CTP 轨、`HG_CONFIG_FILE` 剔除 db 键）⇒ `HgcpSession.last_received_seq_=0`；
  2. 首轮 `MSG_HELLO` 后 LOAD 被回 `0x0106 ERR_SEQ_OUT_OF_ORDER` —— 根因 `HgcpSession::on_connected()`（`session.cc:79-83`）**不重置 `last_received_seq_`**，历史连接累积 SEQ 使新连接 seq=1/2 被判回退；
  3. 空载荷 LOAD 虽被受理（二次发同版本被 `0x0403` 拒，证明 version=1 已应用），但 `risk_state_.ready() == !mgr_.peek().empty()`（`risk_state.h:65`）⇒ 快照空 ⇒ 不 READY；
  4. 携 1 条合法 `VarietySpec`（`product_id=1` / `COMMODITY` / `ENABLED` / `unit_factor=1.0000`）后：`MSG_ACK(result=0, applied_version=1)` + `EVT_SYSTEM_STATE(SERVER_STATE_READY, param_version_applied=1)` ⇒ **`state=REPLAYING reason=param_version=1` → `state=READY param_version=1 reason=重放一致且门卫通过`**。
- 重启 `hg-biz`（带 token）后 HELLO_ACK 返回 READY ⇒ Java `isReady()=true`；`health-check.sh all` = **PASS=6 / FAIL=0 / rc=0**。

### 14.5 剩余阻塞（W-05 未闭环，实测精确定位）
- `POST /risk/rule` 不再被 isReady 拒绝，但回执 **412/10014「参数下发未生效…组件回执=连接中断」**，`t_param_dispatch` 落 1 行 `FAIL/连接中断`。
- Java 日志在完整装配（真 DB + HGCP 桥 + token）下仍周期 `0x301`/`0x302`/`0x106` 重连；`javap` 实测运行字节码**已含 `onHeartbeat`**（W-11 已编译）⇒ **§13.4「缺心跳 ACK 不是重连主因」在完整装配下再次成立**，主因仍属单连接治理 / SEQ 时序竞态，未定论、不臆断、不擅自改代码。

### 14.6 边界与账面
- **测试档界定**：不作验收结论、不标真联档通过、不启动 `TC-M4-24` 实盘观察期、未发交易指令、无实盘资金；生产部署口径恒 DM8（`DEPLOY/README.md:29`），**不得以 SQLite 结论替代 DM8 验收**。
- 探针仅为**测试手段**：产品侧 `CMD_LOAD_RISK_PARAMS` 全量下发仍未实现，C++ READY 由探针点亮**不等于**生产链路打通。
- 零落盘：测试口令仅 Python 进程内存生成、`HG_HGCP_AUTH_TOKEN` 随机值仅 env；无任何凭据写入代码 / 文档 / 测试 / 属性文件。
- 产物全在 `/tmp`：`hg-dev.db`、`hg-sqlite-schema.sql`、`sqlite-jdbc.jar`、`hg_sqlite_ddl.py`、`hg_flow_test.py`、`HgLoadProbe.java`、`hg-flow-result.json`；仓库共享台账 `TASKS/BOARD/TEST_RESULT/REVIEW/CODEBUDDY` 与源码**零改动**。
- 本轮 `docs/BACKLOG.md` 追加未提交；提交 / push 需单独指令。

## 15. CMD_LOAD_RISK_PARAMS 全量下发实现 + HGCP 重连排查（2026-11-20，编排者 · 单写者 · 纯追加）

> **用户指令**：「实现 CMD_LOAD_RISK_PARAMS 全量下发 并排查 HGCP 重连」。
> **性质**：改产品代码（Java 侧 4 文件）+ 新增 2 个测试类；**不改线格式**（§4.2.3 已冻结的 `CmdLoadRiskParams` 消息仅启用，字段名/号/类型零改动）、不改 `proto/hgcp.proto`、不改 `java/CONTRACTS.md`、不改 C++。
> **时点**：2026-11-20；全部计数 / 行号 / 状态**以实时为准**。账面维持 235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2` / 全部任务闭环 / 机读 221（未重算、未立 `T-` 号、未新增 `W-` 号、未启新轮次号）。

### 15.1 改动清单
| 文件 | 改动 |
|---|---|
| `ParamChangeEncoder.java` | `encode()` 补 `CMD_LOAD_RISK_PARAMS` 分支；抽 `buildRiskThresholds()` 复用 `RiskThresholds`（G-5）；新增 `encodeLoadRiskParams()`（版本 / `batch_index` / `batch_total` / 阈值 / `entries[n]` 品种）+ `mapProductCategory` / `mapProductStatus` / `rejectUnknownLoad`（HGC-1.6 不得静默丢弃） |
| `ParamDispatchServiceImpl.java` | `dispatch()` 守卫对 `CMD_LOAD_RISK_PARAMS` **豁免** `bridge.isReady()` —— 否则「要 READY 才能下发、要下发才能 READY」启动死锁；其余类型维持 NFR-DEPLOY-5 显式拒绝 |
| `HgcpClient.java` | 新增 `setStartupDispatchHook` + `fireStartupDispatch`（HELLO_ACK 且 `server_state != READY` 时异步触发、同一时刻仅一个在飞；已 READY 不重复）；重连修复（见 §15.2） |
| `Bootstrap.java` | `assembleHgcpClient` 双参重载注入启动钩子；`runStartupDispatch`（有界等待装配引用 → `dispatch` + `requireEffective`）；`buildStartupSnapshot`（16 阈值取 `RiskThresholdSpec` 缺省基线 + `DmProductDao.findAllOrderByCode()` 真实品种使 `RiskSnapshot` 非空 + 单批 0/1） |
| `ParamChangeEncoderLoadTest.java`（新） | 7 例：逐字段解析 / 阈值回归 / 缺键 / 未知顶层键 / 未知条目键 / 空载荷提示 |
| `HgcpClientStartupDispatchTest.java`（新） | 3 例：hook 仅非 READY 触发 / 重连关闭旧 transport / `SocketTimeoutException` 不重连 |

### 15.2 HGCP 重连根因链（逐层实测定位，非臆断）
| # | 根因 | 证据 | 修复 |
|---|---|---|---|
| 1 | 重连 `connectAndHandshake()` **不关闭旧 transport** ⇒ 服务端单连接治理仍视旧连接 active ⇒ 新连接被回 `0x0302 ERR_DUPLICATE_CONNECTION` 后关闭 ⇒ 循环 | `HgcpClient` 原实现直接 `transport = factory.get()` | 新建前 `old.close()` |
| 2 | `readLoop` / `heartbeatLoop` / `writeFrame` 可同时判断连 ⇒ 并发重连 + 多个 reader 线程 | 日志 `0x302`/`0x301` 交替、`restartReader()` 不中断旧线程 | `AtomicBoolean reconnecting` 串行化 + `restartReader()` 中断旧 reader |
| 3 | 重连完成后**过期读线程**的迟到 EOF/异常仍调 `onDisconnected()` ⇒ 连锁重连 | 基线观测 20s +13 次重连、reader `elapsed=0.23s` 不断重建 | `AtomicLong connectionEpoch`，`readLoop(epoch)` 仅在当代际匹配时触发重连 |
| 4 | **`SO_TIMEOUT_MS=1000`（`TcpHgcpTransport`）与服务端 `hg.tcp.heartbeat_ms=1000` 同值**，读超时 `SocketTimeoutException` 被 `catch (IOException)` 误判为断连 | 诊断日志 `HGCP-DIAG IOException java.net.SocketTimeoutException: Read timed out thread=hgcp-reader`（瞬时抓到 168 条） | `readLoop` 前置 `catch (SocketTimeoutException)` ⇒ 仅继续读、不重连（真断连仍由 EOF / `Connection reset` / 心跳 miss 判定） |

> §13.4「缺心跳 ACK 不是重连主因」再次成立：`javap` 实测运行字节码已含 `onHeartbeat`（W-11 已编译）。真实主因为上表 4 项，均已定位并修复。

### 15.3 门禁（全绿）
- `mvn -B -o clean test` = **1521 / 0 / 0 / 0 + BUILD SUCCESS**（新增 10 例：7 + 3）；compile 阶段 arch-check **E1–E9 9/0/0 rc0**。
- C++ 双轨 `ctest --test-dir cpp/build` = **64/64**、`cpp/build_stub` = **60/60**。
  - ⚠️ 首次 `ctest --test-dir cpp` 报 `No tests were found`，且带组件运行时 `hgcp.event_emitter_test` 因 `srv.start()` 绑定 27400 失败（端口占用）；**停组件后重跑全绿** ⇒ 环境冲突，非代码缺陷。
- 前端探针 **9/9 rc0**。

### 15.4 端到端实跑（dev/test 档，非验收）
- 同时干净重启双组件：C++ 起点 `NOT_READY` → Java `HELLO_ACK(NOT_READY)` 自动触发启动下发 → **`启动全量参数下发已生效：paramVersion=7`** → C++ `REPLAYING → READY param_version=7 reason=重放一致且门卫通过`。
- `t_param_dispatch`：`LOAD_RISK_PARAMS / SUCCESS / 7`；增量 `POST /risk/rule` → **200 / code 0**，`UPDATE_RISK_THRESHOLD / SUCCESS / 8`（修复前为 412/10014「连接中断」）。
- **稳定 25s 观测：重连增量 = 0、`0x106` 增量 = 0**（修复前每 20s +13 次重连）。
- `health-check.sh all` = **PASS=6 / FAIL=0 / rc=0**；全流程 REST 复测 28 端点 25 绿（3 个为业务语义拒绝 10001/10015）、写链路落库、唯一约束生效。

### 15.5 边界与账面
- **测试档界定**：不作验收结论、不标真联档通过、不启动 `TC-M4-24` 实盘观察期、未发交易指令、无实盘资金；生产部署口径恒 DM8（`DEPLOY/README.md:29`），SQLite 结论不得替代 DM8 验收。
- 零落盘：`HG_HGCP_AUTH_TOKEN` 随机值仅 env、测试口令仅进程内存；无凭据写入代码 / 文档 / 测试 / 属性文件。
- 共享台账 `TASKS/BOARD/TEST_RESULT/REVIEW/CODEBUDDY` 零改动；`hg.properties` 仅本地非敏感键且被忽略；`/tmp` 产物不入库。
- 本轮产品代码与 `docs/BACKLOG.md` 追加**未提交**；提交 / push 需单独指令。

## 16. 组件监控面（阶段 1–3）：方案 → REST → QUERY_STATUS → 告警推送（2026-11-22，编排者 · 单写者 · 纯追加）

> **用户指令链**：「设计 cpp 服务器监控接口，让 java 服务器通过接口连接服务器，用于监控 cpp 服务器的工作状态。先设计监控方案」→「先完成阶段 1」→「进入阶段 2」→「阶段 3」→「纯追加登记 `docs/BACKLOG.md` 后 提交并 push」。
> **时点**：2026-11-22；全部计数 / 行号 / 状态**以实时为准**。账面维持 235 任务 / 2235 人时 / 233 DONE / 2 REJECTED / 六区 `0/0/0/233/0/2` / 全部任务闭环 / 机读 221（沿用第 279 轮，未重算）。
> **边界**：本备忘录 `W-` 号不计正式台账；未立 `T-` 号、未新增 `W-` 号、未启新轮次号；dev/test 档（SQLite 代 DM8、CTP 未 `LOGGED_IN`），不作验收结论。

### 16.1 方案的三条硬约束（决定设计走向）
- **单连接治理 `0x0302`**（`docs/ARCHITECTURE.md:758`）：通道 A 同时只允许 1 条业务连接 ⇒ 监控**不得新开 socket / 端口 / 连接**，必须复用既有唯一 HGCP 长连接。
- **`TradingBridge` §3.10「不得以定时轮询同步两侧状态」** ⇒ `CMD_QUERY_STATUS` 只作**按需核验**，绝不写回 `MirrorState`；监控 ≠ 状态同步，服务端不设任何定时器。
- **冻结门禁 E7 / E9**（`proto/hgcp.proto` 字段级 schema、冻结不变量跨制品）⇒ **proto 零改动**，结构化数据全部装进既有 `MsgAck.detail` string 字段。
- 现状盘点：协议层已冻结并可用 `CMD_QUERY_STATUS 0x011C` / `EVT_SYSTEM_STATE 0x020A` / `EVT_CTP_STATE 0x020D` / `EVT_TRADING_ALERT 0x020E`；C++ 已实现 QUERY_STATUS 但 `what` 被忽略、只回 key=value 摘要；Java 侧**从未发过** QUERY_STATUS，且 CTP/告警事件落 `default` 丢弃；`health-check.sh` 靠 `grep` 日志判就绪。

### 16.2 阶段 1：REST 只读端点（零 proto / 零 C++ 改动）
- 新增 `bridge/ComponentStatus.java`（不可变语义快照，与 `MirrorState` 物理隔离）、`bridge/StatusDetailParser.java`（key=value 宽松解析）、`monitor/ComponentMonitor.java`（**无定时器**、`refresh()` 严格单飞）、`web/handler/monitor/MonitorHandler.java` / `MonitorRoutes.java`。
- `HgcpClient`：加 `queryStatus(what)` / `componentStatus()` / 链路观测字段（`lastFrameAtMs` / `connectionEpoch` / `reconnectCount`）；`HgcpEventListener` 增 3 个 `default` 回调，`CompositeHgcpEventListener` 显式转发。
- `GET /api/v1/monitor/component`：默认**不查 C++、不查库**；仅 `?refresh=1` 发一帧查询，有界等待、超时返 `refreshPending` 缓存不抛 5xx；不在公开白名单 ⇒ 需会话（匿名实测 401）。
- 登记 `ApiRoutes.java` + `web/js/core/routes.js`（`MONITOR` 模块），路由数 175 → 176（`FullWebAssembly.ROUTE_COUNT` 173 → 174；`BootstrapAssemblyTest` / `JobAssemblyTest` / `WsProductionAssemblyTest` / `ModuleRoutesConsistencyTest` 四处硬编码同批同步）。
- 门禁 1537/0/0/0、E1–E9 9/0/0、C++ 64/64 + 60/60、探针 9/9；端到端默认 `lastQueryTsMs=0 / queryDetail=null`、`?refresh=1` 收到 `ready=true;param_version=9;load=0/0`、`health-check.sh all` PASS=6/FAIL=0。

### 16.3 阶段 2：`what` 位掩码 + 结构化 detail + 状态事件补推
- C++ `CommandHandler` 增 `StatusDetailFn` 注入接缝（函数指针 + user，**hgcp 不反向依赖 app**）；**未注入时保留阶段 1 的 key=value 降级路径**，既有夹具逐字节兼容（`command_handler_test` 原断言不受影响）。
- `TradingApp::statusDetail` 按 `what` 位组装 JSON：`0x01` 状态 / `0x02` 重放 / `0x04` 参数批次 / `0x08` 队列·计数·CTP·uptime；`what==0` 视作全量（兼容阶段 1 客户端）。字段含 `state`（AppState 细名，可区分 BLOCKED/FAILED）/ `serverState`（§4.2.8.7 映射，BLOCKED/FAILED 仍归 `NOT_READY`，**不新增枚举值**）/ `replay` / `load` / `queues` / `counters` / `ctp` / `uptimeMs` / `tsMs` / `what`；纯内存聚合、不查库、不落盘（E1/E2）。
- C++ 补推 `NOT_READY` / `REPLAYING` / `STOPPING` 三段 `EVT_SYSTEM_STATE`（READY 仍只由启动编排发布，§4.5.3 ⑦；`app.ready` 既有「READY 帧恰 1」判据不受影响）。
- Java `StatusDetailParser` 双形态（gson JSON / key=value，非法 JSON 标 `unparsed` 不猜值）、`ComponentStatus` 扩指标、`ComponentMonitor.WHAT_ALL` 由 `0` → `0x0F`。
- 新增 C++ `app.status_detail` 双轨用例 + `command_handler_test` 注入接缝用例；门禁 1542/0/0/0、C++ 65/65 + 61/61、探针 9/9。端到端 `what=15`、`replayRuns=1`、`eventQueueCapacity=16384`、`framesIn=55`、`ctp.degraded=true / loggedIn=false`（如实反映 CTP 凭据未注入，不粉饰状态）。

### 16.4 阶段 3：阈值告警 + 事件驱动 + 冻结内 WS 推送
- **关键裁定（零冻结面）**：`java/CONTRACTS.md §4.1` 把 WS 端点冻结为 `/ws/quote`、`/ws/alert`，文件头规定唯一写者 `backend-engineer#java`、新增路由/约定须投 `question` 落笔、不得先改后报；`WsServer.ALLOWED_PATHS` 与 `W-1 channels` 值域同族冻结 ⇒ **未新增 `/ws/monitor` 专用路径 / 通道 / 消息形态**，改为复用既有 `/ws/alert` 的 `{"type":"alert"}` 帧，以 `metric` 的 `component.` 前缀标识。专用通道仍待 `CH-xxx` 用户裁定 + 唯一写者落笔。
- 新增 `bridge/ComponentAlert.java` / `monitor/ComponentAlertSink.java`；`ComponentMonitor` 加 5 类阈值（state / link / ctp / reconnect / queue）、去重（持续异常不刷屏、解除后可再触发）、有界内存历史（100 条，随 `alerts` 数组返回）、`implements HgcpEventListener` 事件驱动（**无轮询**）；出口异常不中断监控主链路。
- 装配：`setAlertSink` → `wsServer.alertNotifier()` → `WsServer.publishAlert(0, …)`（账户级哨兵 0，仍经 W-2 `t_user_sub_account_perm` 授权过滤）；`ComponentMonitor` 加入 T-172 组合器（阶段 1「不加入」注释按「只追加不回改」补阶段 3 说明）。
- 前端 `web/js/core/ws.js` 增并导出 `connectComponentAlerts(handlers)`：复用 `connect('alert')`、过滤 `component.` 前缀、其余帧原样透传；新增 `tools/probes/monitor_ws_probe.mjs`（8 例）。
- **告警留痕仅内存**（`ComponentStatus.alerts`，重启不保留）。未落 `t_risk_alert`：其 `ck_risk_alert_type` CHECK 值域冻结为 8 类事中指标、无 `COMPONENT` 类型 ⇒ 落库需改 `docs/DATA_MODEL.md` / DDL + `CH-xxx`，本轮未擅自扩面。
- 门禁 1547/0/0/0、E1–E9 9/0/0、C++ 双轨 65/65 + 61/61、前端探针 10/10。

### 16.5 阶段 3 端到端实跑（dev/test 档，非验收）
- 运行时启用 `hg.web.ws.port=27403`（**仅 `/tmp/hg-biz-stage3.properties`，仓库 `hg.properties` 仍 0 处 ws.port**）；为 `devtest` 播种账户级授权（`/tmp/hg-dev.db`，可逆）。
- 实测链：登录 → WS 握手 `HTTP/1.1 101 Switching Protocols` → `subscribed channels=['alert'] subAccounts=[0]`（W-2 授权生效）→ `GET /api/v1/monitor/component?refresh=1` → `ctpDegraded=true` 触发 `component.ctp` → WS 收到 `{"type":"alert","level":"WARNING","metric":"component.ctp","value":"degraded=true,loggedIn=false","threshold":"loggedIn=true,degraded=false","subAccount":0,...}`；REST `alerts` 内存留痕 1 条。
- 排查留痕：首次播种 `effective_from='2026-01-01'` 触发 sqlite-jdbc `Error parsing time stamp` ⇒ W-2 fail-closed 空集；改可解析格式后授权生效 —— 恰好验证「安全默认空集」未被绕过。
- 12s 观测：重连 **+0**、`0x106` **+0**；`health-check.sh all` = **PASS=6 / FAIL=0 / rc=0**；C++ `state=READY`。

### 16.6 边界、纪律与待裁定
- **零改动**：`proto/hgcp.proto`、`java/CONTRACTS.md`、`TradingBridge` 签名、`docs/ARCHITECTURE.md`、`docs/DATA_MODEL.md`、TASKS / BOARD / TEST_RESULT / REVIEW / CODEBUDDY、`health-check.sh`（仍读日志，按方案建议保留；改为依赖 Java 端点会引入组件依赖，需单独裁定）。
- 零落盘：`HG_HGCP_AUTH_TOKEN` 随机值仅 env、测试口令仅进程内存；`/tmp` 产物与运行时配置不入库；5 个 CTP SDK `*.con` 运行产物未纳入。
- **`what` 位语义属冻结字段内语义补全**，与 `/ws/monitor` 专用通道、告警落库一样，均需 `CH-xxx` 显式裁定才能写入 `CONTRACTS.md` / `ARCHITECTURE.md` / `DATA_MODEL.md` —— 本轮按「勿为措辞统一改定版制品」未擅自登记。
- 测试档界定：不作验收结论、不标真联档通过、不启动 `TC-M4-24` 实盘观察期、未发交易指令、无实盘资金；生产部署口径恒 DM8（`DEPLOY/README.md:29`），SQLite 结论不替代 DM8 验收。
- 本轮登记后按用户指令提交并 push（提交号以 `git log` 实时为准）；§15.5 既有「未提交 / 提交需单独指令」为**时点快照原文**，按「勘误不回改历史」保留不改。

## 17. 浏览器 WS 断线根因修复（2026-11-24，编排者 · 单写者；用户裁定「批准最小修复」）

> **性质**：只读排查 + 受控最小改动 + 仅重启 hg-biz + 逐条留痕；章节按追加时序落盘，原文不回改。全部计数 / 行号 / 时点**以实时为准**。

### 17.1 两段根因（逐层暴露）

1. **端口错配（方案 A，零 Java 改动）**：`web/js/core/ws.js` 原 `wsBase()` 取 `window.location.host`（同源 :80），而 `CFG-32` 的 WS 独立监听 `hg.web.ws.port=81` ⇒ 浏览器握手打到 HTTP :80，`GET /ws/quote status=404` 按指数退避反复（封顶 15s，实测日志节奏吻合），徽标恒为「已断线（自动重连中）」。
   - 修复：`web/index.html` 增 `<meta name="hg-ws-port" content="81">`；`ws.js` 的 `wsBase()` 优先读 meta 拼 `ws://<hostname>:<port>`，无 `document` / 无 meta 退回同源（兼容反向代理部署）；`tools/probes/monitor_ws_probe.mjs` 补 2 例覆盖新分支（PASS 8 → 10）。**未动** `CONTRACTS.md` / `ApiRoutes.java` / `web/js/core/routes.js` / `FullWebAssembly.ROUTE_COUNT` / C++ 双轨。
   - 生效证据：22:30:27 浏览器完整刷新后，:80 的 `/ws/quote` 404 **停止新增**；`ss` 见 `183.211.200.246 → :81` 的 TCP 连接（TIME-WAIT / FIN-WAIT-1）。
2. **握手拒绝浏览器默认扩展头（本轮修复）**：`ss` 证明浏览器已连到 :81，但 WsServer 既无「连接建立」也无「鉴权失败」日志，且 FIN-WAIT-1 有 ~229 字节待发 ⇒ 卡在 `validateUpgrade`。实测（同一会话、:81）：不带 `Sec-WebSocket-Extensions` → `101`；带 `permessage-deflate; client_max_window_bits`（浏览器默认）→ `400 {"code":10001,"message":"不支持扩展协商（permessage-deflate 等）"}`；扩展+子协议 → `400`。
   - 根因：`java/src/main/java/com/hedgeguard/web/ws/WsHandshake.java` 原 `validateUpgrade` 对任何 `Sec-WebSocket-Extensions` 一律返回 400。真实浏览器默认携带该 offer 且前端 API 无法关闭 ⇒ **所有真实浏览器都连不上**；此前 localhost 裸握手 / Python 脚本不带该头 ⇒ 101，故一直未暴露（历史日志里成功的 WS 连接全部来自本机脚本，无一次真浏览器）。

### 17.2 修复内容与冻结面处置（用户裁定「批准最小修复」）

- **改动 2 个 Java 文件**：`WsHandshake.validateUpgrade` 删除「请求扩展即拒」分支，改按 RFC 6455 忽略 offer —— 101 响应（`upgradeResponse` 本来就不含 `Sec-WebSocket-Extensions` / `Sec-WebSocket-Protocol`）不回显相应头即表示不协商、不产生压缩帧；`WsHandshakeTest.validatesUpgrade` 把原「扩展请求被拒」断言改为「offer 不拒绝 + 101 不回显两个头」。
- **冻结契约**：`java/CONTRACTS.md:882`「非法 Upgrade（缺头/版本≠13/**请求扩展**）⇒ 400」与 `:855`「不得改动本节已冻结项」为既有冻结原文，按「勘误不回改历史」**保留不改**；本次行为变更仅在本节追加登记，**待 `CH-xxx` 显式裁定**后再由唯一写者决定是否修订冻结行 / 升版。在此之前代码与该冻结行**存在已知不一致**，如实披露、不粉饰。
- 未动：`proto/hgcp.proto`、`java/CONTRACTS.md`、`TradingBridge`、`docs/ARCHITECTURE.md`、`docs/DATA_MODEL.md`、TASKS / BOARD / TEST_RESULT / REVIEW / CODEBUDDY、`health-check.sh`、`docs/DEPLOY/`。

### 17.3 验证（实测数值，以实时为准）

| 项 | 结果 |
|---|---|
| Java 全量单测 `mvn -B -o clean test` | **1563 / 0 / 0 / 0，BUILD SUCCESS** |
| E1–E9 `mvn -B -o validate` | **9 / 0 / 0，arch-check 通过** |
| 前端探针全量 10 个 `.mjs` | **0 失败**（monitor_ws_probe PASS=10、t053 129、t070 104） |
| 编译产物 | `WsHandshake.class` 已无「不支持扩展协商」字样 |
| 重启范围 | **仅 hg-biz**（pid 478859 → 614301）；hg-trading pid 478806 未动，CTP `:40001` / `:40011` 与 HGCP `:39000` 连接保持 |
| 浏览器式握手（带 `permessage-deflate`） | `:81/ws/quote` → **101 Switching Protocols** + `subscribed` 回帧；不带扩展头回归仍 101 |
| 组件状态 `?refresh=1` | `linkEstablished=true` / `state=READY` / `paramVersion=19` / `ctpTradeConnected=true` / `ctpMdConnected=true` / `ctpLoggedIn=true` / `ctpDegraded=false` / `reconnectCount=0` / `alerts=[]` |
| `health-check.sh all` | **PASS=6 / FAIL=0 / rc=0** |
| :80 `/ws/quote` 404 | 本次重启后 **0 条** |

- 重启瞬间日志出现 2 条 `HGCP 错误帧 0x106`（跨进程重连时序，§13.4 同族）；最终 `linkEstablished=true`、`reconnectCount=0`、`alerts=[]`、`health-check` 全绿 ⇒ 已收敛，按「如实登记」保留。
- 重启清空内存会话：22:45:22 真实浏览器 `183.211.200.246` 的一次 WS 握手返回 `401 WS 握手鉴权失败`（旧 `HGSESSION` 失效）。**用户须重新登录再刷新页面**，之后浏览器即按修复后的链路连 :81。

### 17.4 边界、纪律与遗留

- **前端仍需一步人工操作**：重新登录 + 刷新页面（静态文件 `Cache-Control: no-store`，刷新即生效）。`hg-ws-port` 的 meta 值须与 `hg.web.ws.port` 同步；若页面改走 HTTPS，需 `wss://` 且证书覆盖 81（本轮仍明文 `ws://`）。
- 徽标变「已连接」后行情表仍可能「暂无行情」：C++ 侧未调 `SubscribeMarketData`（`ct_adapter.cc:232` 按需订阅），`ctpEvents=0`，与本次断线是两个独立问题，未在本轮扩面。
- 零落盘：`HG_HGCP_AUTH_TOKEN` / `HG_CTP_PASSWORD` / devtest 测试口令仅驻进程内存（重启凭据从 `/proc/<pid>/environ` 注入本次 shell，未回显、未写盘）；`/tmp` 产物与运行时配置不入库。
- 仍为 dev/test 档（`env_type=SIMULATION` + SQLite），非实盘、非验收；未发交易指令、无实盘资金；生产部署口径恒 DM8。
- 改动未提交 git；提交 / push 需单独指令。

## 18. CTP 行情链路装配（2026-11-24，编排者 · 单写者；用户裁定「A 配置键 + 最小登记」）

> **性质**：受控最小改动 + 只重启 hg-trading + 逐条留痕；章节按追加时序落盘，原文不回改。全部计数 / 行号 / 时点**以实时为准**。

### 18.1 故障与两段断点

页面「已连接」但行情表恒「暂无行情」。实测（`:81/ws/quote` 订阅带 `instruments` 后等 12s）只收到 `subscribed` 控制帧、**零 `type=quote` 帧** ⇒ 前端渲染逻辑（`web/js/pages/trade.js:442`）无数据，非连接故障。
说明：前端「订阅」是 WS 层 `WsSubscription` 过滤登记（`WsServer.java:503`），**不驱动 CTP 的 `SubscribeMarketData`**；页面文案隐含的上游数据源当时不成立。

- **断点 A（C++ 发布）**：`cpp/src/trading_app.cc` 原 `pollCtp` 把行情队列排空计数（`while (ct_.pollQuoteEvent(&q)) ++stats_.quotes_dropped;`），注释明写「发布未在本单装配」；`emitEngineEvent` 无行情分支 ⇒ 即使 CTP 推了行情也出不了 C++。
- **断点 B（CTP 订阅）**：`ct_gateway_ctp.cc:157` 的 MD `connect()` 只 `RegisterSpi → RegisterFront → Init`，无 `SubscribeMarketData`；`CtAdapter::subscribeMarket`（`ct_adapter.cc:232`）全仓生产代码零调用点（仅 `ct_adapter_test.cc` 调用）⇒ `OnRtnDepthMarketData` 永不触发。佐证 `ctpEvents=0` / `quotesDropped=0`（不是收到后丢弃，是没收到）。
- **底层全就绪**：`proto/hgcp.proto:663` 的 `EvtMarketQuote`、`event_emitter.cc:143` 的 `emit_market_quote`（可丢例外）、`payload_codec.cc:72`、Java `WsQuoteBridge`（`Bootstrap.java:1463`）、`WsServer.publishQuote`、前端渲染 —— 只差两处装配。

### 18.2 改动内容（用户裁定「A 配置键 + 最小登记」）

| 文件 | 改动 |
|---|---|
| `cpp/src/app_config.h/.cc` | 新增非敏感键 `hg.ctp.subscribe_instruments` / `HG_CTP_SUBSCRIBE_INSTRUMENTS`：逗号分隔、装载时大写归一 / 去空白 / 去重 / 保序；`redactedSummary` 照实回显；键集注释补登 |
| `cpp/src/trading_app.h/.cc` | `pollCtp` 改为 `publishQuotes(now)`：`CtQuoteEvent → EvtMarketQuote` 编码 + `emitter_.emit_market_quote`；**按合约 200ms 节流**（与 Java `WsServer.QUOTE_TICK_MILLIS=200` 对齐，`emit_quote` 直发不排队故须 C++ 侧限流）；`priceToString` 定点十进制并把 CTP `DBL_MAX` 哨兵价归一为空串；`quote_events` 计数与 statusDetail `quoteEvents` 字段；`isMdLoggedIn()` false→true 时调 `ct_.subscribeMarket` 一次，断线重连自动重订阅 |
| `cpp/src/tests/trading_app_test.cc`、`cpp/src/CMakeLists.txt` | 新增 `quote_config`（配置归一 / env 覆盖，6 checks）、`quote_publish`（发布载荷 / 节流 / 空合约 / 哨兵价，29 checks）两用例双轨注册 |
| `hg.properties`、`/tmp/hg-verify-run.properties` | 填入当前挂牌活跃合约集 11 个（**勿填已交割月份**；上一轮页面填的 `RB2601` 属 2026-01 已到期） |
| `docs/DEPLOY/hg.properties.template` | 补注释说明该键、留空语义、前端过滤须有交集 |

- **零改动**：`proto/hgcp.proto`、`java/CONTRACTS.md`、Java 源码、`docs/ARCHITECTURE.md` / `DATA_MODEL.md`、`data-init.sql` 68 键、E9 四项不变量、TASKS / BOARD / TEST_RESULT / REVIEW / CODEBUDDY、`web/`（除 §17 的 3 文件）。
- 按「最小登记」裁定，本节只在 `docs/BACKLOG.md` 追加；`ARCHITECTURE.md §11.2` 配置表 / `CH-xxx` / `T-` 号台账**未立**，如需正式登记另案。

### 18.3 验证（实测数值，以实时为准）

| 项 | 结果 |
|---|---|
| C++ 双轨编译 | 真轨 / stub 轨均 `Built target hg-trading` + `hg_app_test`，无 warning |
| 新用例双轨 | `quote_config` 6/6、`quote_publish` 29/29，真轨与 stub 轨均 PASS |
| C++ 双轨 ctest 全量 | 干净环境真轨 **66/66**、stub 轨 **62/62** 全通过（另见 18.4 端口冲突说明） |
| Java `mvn -B -o clean test` | **1563 / 0 / 0 / 0，BUILD SUCCESS**（含 E1–E9 validate 9/0/0） |
| 前端探针全量 10 个 `.mjs` | **0 失败** |
| 重启范围 | **仅 hg-trading**（pid 478806 → 655230，真 CTP 轨二进制）；hg-biz 未动 |
| 启动日志 | `hg.ctp.subscribe_instruments=RB2701,CU2612,…,SC2612` 生效；`ctp.md subscribe instruments=11 code=0 accepted=11`；`ctp=LOGGED_IN`；`state=READY param_version=20` |
| 组件计数 | `quoteEvents=209`、`quotesDropped=3`、`ctpMdConnected=true`、`ctpDegraded=false` |
| 端到端 WS | 会话 + `:81/ws/quote` 握手 101 → 订阅（instruments 留空=全部）→ **12s 内 17 条 `type=quote` 帧**（如 `IF2612 last=3910.0000 bid=3909.8000 ask=3910.0000`），前端「暂无行情」已解除 |
| `health-check.sh all` | 前一轮 §17 已 PASS=6/FAIL=0；本轮 hg-trading 重启后 C++ `state=READY` |

### 18.4 环境性失败说明（如实登记，非代码回归）

- `hgcp.event_emitter_test` 用 `default_tcp_config()` 固定端口 **39000**，与长驻 hg-trading 冲突 ⇒ 服务运行时跑 ctest 该项 FAIL；在**停 hg-trading 的窗口**（39000 空闲）复跑该项 **PASS**。故按「排除固定端口冲突项」在干净环境跑全量得 66/66、62/62，且停服务窗口已单独验证该项通过。
- 一次 ctest 中 `app.config` FAIL 系排查用 shell **导出了 `HG_*` 环境变量**污染子进程（`app.config` 本身测 env 优先级）；干净环境（`env -u HG_*`）复跑 **39/39 PASS**。两者均非本轮改动引起。

### 18.5 边界、纪律与遗留

- **前端使用口径**：`/ws/quote` 的「合约过滤」**留空 = 全部**；若填写，须与 `hg.ctp.subscribe_instruments` 有交集（`WsSubscription.matchesQuote` 大写归一过滤）。页面若改走 HTTPS 需 `wss://` 且证书覆盖 81。
- **W-5 首帧快照仍未接**（`Bootstrap.java:691` 如实披露「行情最近值缓存无承载」）：新连接需等下一帧才有值，按 W-5「不影响正确性」未扩面。
- **已知冻结面不一致**：`java/CONTRACTS.md:882`「请求扩展⇒400」（§17 WS 修复）原文保留，**待 `CH-xxx` 裁定**；本轮不回改。
- 仍为 dev/test 档（`env_type=SIMULATION` + SQLite），非实盘、非验收；未发交易指令、无实盘资金；生产部署口径恒 DM8。SimNow 7x24 服务时段（交易日 16:00～次日 09:00）外无行情属服务端状态，不算故障。
- 零落盘：`HG_HGCP_AUTH_TOKEN` / `HG_CTP_PASSWORD` / devtest 测试口令仅驻进程内存（重启凭据从 `/proc/<pid>/environ` 注入本次 shell，未回显、未写盘）；`/tmp` 产物与运行时配置不入库；改动未提交 git，提交 / push 需单独指令。

## 19. 只展示 IF2612 的根因订正（2026-11-25，编排者 · 单写者；用户裁定「按清单修复」）

> **性质**：§18 交付后用户实测「订阅全部合约却只展示 `IF2612`」⇒ 只读排查 + 独立对照实验 + 最小订正 + 逐条留痕。§18 原文**按「勘误不回改历史」保留不改**，本节为最新结论。全部计数 / 行号 / 时点**以实时为准**。

### 19.1 根因：CTP 合约代码大小写敏感，§18 误把 WS 过滤口径套到 CTP 订阅

- §18 在 `app_config.cc` 新增的 `normalizeInstruments()` 做了 `upper()` 大写归一，把部署配置里的交易所原生代码改成全大写；同时 §18 填写的合约集本身也不规范（郑商所写成 4 位 `SR2701`/`TA2701`）。
- **CTP `InstrumentID` 大小写敏感且各所位数不同**：SHFE / DCE / INE 小写 4 位（`rb2701`/`cu2612`/`sc2612`）、CFFEX 大写 4 位（`IF2612`）、CZCE 大写 3 位（`SR701`/`TA701`）。项目既有单测（`ct_mapping_test.cc` / `ct_adapter_test.cc` 的 `rb2601`、`risk_state/data_model_test.cc` 的 `cu2612`）全用小写，可作旁证。
- ⇒ 11 个订阅代码里**只有 `IF2612` 恰好不受大写归一影响**，其余 10 个对 CTP 均为无效代码 ⇒ 只有它推送行情。这就是「只展示一个合约」的全部原因。
- **混淆来源**：`web/js/core/ws.js:107` 与 `WsSubscription.java:89` 的 `toUpperCase` 属 **W-1 前端 WS 订阅过滤**（比较入口），与 CTP `SubscribeMarketData` 的 `InstrumentID` 是两套语义；§18 把前者误当成后者的一致口径。

### 19.2 对照实验（独立 CTP 探针直连 SimNow `:40011`，不触碰运行服务）

临时探针 `/tmp/ctp_quote_probe.cpp`（仓库内零残留）：登录 → `OnRspSubMarketData` 逐合约应答 → 收 8s `OnRtnDepthMarketData` 按合约计数。

| 组 | 订阅集 | 逐合约应答 | 行情结果 |
|---|---|---|---|
| **A**：§18 当前配置（全大写） | `RB2701,CU2612,AU2612,AG2612,RU2701,I2701,M2701,SR2701,TA2701,IF2612,SC2612` | 11 个全部 `ErrorID=0 CTP:No Error` | **1 个合约 / 19 ticks，仅 `IF2612`** —— 完全复现 |
| **B**：原生大小写 + 位数变体集（13 个） | `rb2701,cu2612,au2612,ag2612,ru2701,i2701,m2701,sc2612,SR2701,TA2701,SR701,TA701,IF2612` | 13 个全部 `ErrorID=0` | **11 个合约 / 164 ticks**；`SR2701`/`TA2701`（大写 4 位）**无行情**，`SR701`/`TA701`（大写 3 位）**有行情** |

⇒ 根因确证（大小写 + 郑商所位数），并排除「SimNow 只回放 IF2612」这一替代解释。

### 19.3 三层遮蔽：为何 §18 的验证没能发现

1. **CTP 订阅应答不校验代码有效性**：A 组 11 个无效/有效代码的 `OnRspSubMarketData` 全部 `ErrorID=0`；`SubscribeMarketData` 返回 0 也只表示请求被受理。故 §18 日志 `ctp.md subscribe instruments=11 code=0 accepted=11` 与探针 `[subscribe] rc=0` **不能证明 11 个合约有效** —— §18.3 把它记为「订阅成功」系误判，此处订正。
2. **项目 SPI 未实现 `OnRspSubMarketData`**（`ct_spi_impl.cc:218` 的 `OnRspError` 为空实现），逐合约应答未被消费 ⇒ 运行日志无任何线索。
3. **§18 的 `test_quote_config` 断言把缺陷固化**：期望 `"RB2601,RU2701,AU2712"`，等于把大写归一锁成「正确行为」。

### 19.4 订正内容（用户裁定「按清单修复」）

| 文件 | 改动 |
|---|---|
| `cpp/src/app_config.cc/.h` | `normalizeInstruments()` **删除 `upper()`**（并删去不再使用的 `upper()` helper），仅保留 trim / 去空 / 去重 / 保序；两处键集注释改为「保留交易所原生大小写与位数，SHFE/DCE/INE 小写 4 位 / CFFEX 大写 4 位 / CZCE 大写 3 位」 |
| `cpp/src/trading_app.cc` | `splitInstruments` 注释同步（不再称「大写归一」） |
| `cpp/src/tests/trading_app_test.cc` | `test_quote_config` 断言改为混合原生写法期望 `rb2701,IF2612,SR701,cu2612`，并补「不含 `RB2701`/`sr701`」「大小写不同不去重」两条回归断言，锁死「不归一」 |
| `hg.properties`、`/tmp/hg-verify-run.properties` | 合约集改为 `rb2701,cu2612,au2612,ag2612,ru2701,i2701,m2701,SR701,TA701,IF2612,sc2612`；注释写明大小写/位数规则 |
| `docs/DEPLOY/hg.properties.template` | 注释补「★ 不得大写归一」及各所位数示例 |
| `web/`、`java/`、`proto/`、`CONTRACTS.md` | **零改动**：WS 过滤的 `toUpperCase` 属 W-1 且自洽，不动 |

### 19.5 验证（实测，以实时为准）

| 项 | 结果 |
|---|---|
| 双轨重编译 | 真轨 / stub 轨均成功，无 warning |
| `quote_config` 双轨 | **8 checks / 0 failures**（含新增大小写回归） |
| `quote_publish` 双轨 | 29 checks / 0 failures（未受影响） |
| 双轨 ctest 全量（干净环境，排除固定端口冲突项 `hgcp.event_emitter_test`，见 §18.4） | 真轨 **66/66**、stub 轨 **62/62** |
| Java / 前端 | 源码零改动 ⇒ §18 的 1563 单测、10 探针结论继续有效 |
| 重启范围 | **仅 hg-trading**（真 CTP 轨），hg-biz 未动 |
| 端到端 WS | 会话 → `:81/ws/quote` 101 → 订阅（留空 = 全部）→ **20s 内 352 条 `type=quote` 帧 / 10 个不同合约**：`ag2612`41、`rb2701`40、`m2701`40、`au2612`40、`ru2701`40、`IF2612`39、`i2701`37、`cu2612`36、`SR701`20、`TA701`19（`sc2612` 本时段无回放帧，属服务端数据，非订阅失败）⇒ 多合约展示已恢复 |

### 19.6 边界与遗留

- **前端使用口径不变**：「合约过滤」留空 = 全部；若填写，前端 `toUpperCase` 与服务端 `WsSubscription.matchesQuote` 的 `toUpperCase` 自洽（CTP 推来的小写代码会被比较前归一），故填写 `RB2701` 仍可匹配 `rb2701` 的行情。CTP 订阅侧**不得**做同样归一。
- 配置须填当前挂牌活跃合约；已交割月份、错误大小写/位数均会被 CTP 静默忽略（`code=0` 但不推行情），**日志无法区分** ⇒ 部署后应以端到端帧数核验，不能只看 `ctp.md subscribe code=0`。
- §18 的「订阅 11 个合约成功」表述以本节 19.3 订正为准；§18 原文按「勘误不回改历史」保留。
- 仍为 dev/test 档、非实盘非验收；零落盘；改动未提交 git，提交 / push 需单独指令。

## 20. 行情展示页终端化重构（2026-11-25，编排者 · 单写者；用户裁定「A 纯前端 / 局部深色 / 四模块」）

> **性质**：用户「重新设计行情展示页面，风格与内容参照主流行情终端（大智慧 / 同花顺）；先做计划，等用户确认」⇒ 计划经 `AskUserQuestion` 三项确认后实施。本节只追加，§17–§19 原文不回改。全部计数 / 行号 / 时点**以实时为准**。

### 20.1 裁定与边界

- 三项确认：**数据范围 A（纯前端重构，零 wire/契约改动）**、**视觉 局部深色终端面板**、**四模块全做**（报价板排序/搜索/选中、红涨绿跌与闪烁、选中合约盘口 + sparkline、自选持久化）。
- **硬约束**（`java/CONTRACTS.md:727` 冻结）：`/ws/quote` 消息仅 `instrument / last / bid / ask / ts` 五字段；`Bootstrap.java:1459` 明确 proto 其余字段「在 W-1 冻结的 quote 消息形态中无承载位，据实不透传」。故**不臆造涨跌幅 / 成交量 / 开高低 / 五档盘口 / 分时 K 线**。
- **口径披露**（写进页面）：「会话变动」以本会话首帧价为基准，**非昨结算价、非真实涨跌幅**；红涨绿跌仅表示会话内方向；走势为**会话内最近值，非完整分时**；行情只展示、不驱动任何自动决策（R-TRADE-11）。
- 零构建链：不引入 npm / 打包器 / 第三方图表库；sparkline 用 **inline SVG 字符串**（探针 DOM 桩无 `canvas.getContext` / `createElementNS`）。
- 局部深色：新增 `.hg-q-*` 命名空间，**不覆盖既有类、不改全局浅色主题**（其余 12 个页面不受影响）。

### 20.2 改动内容

| 文件 | 改动 |
|---|---|
| `web/js/pages/trade.js` | 仅替换 `quoteTab`（TABS / 路由 / 其余 11 个 Tab 不动）。状态层 `quotes / sessionOpen / prevLast / hist(60) / rows / sortKey / sortDir / filter / selected / dense / favorites / frames`；差量渲染（`data-instr` 行索引，只更新单元格，顺序变化才 `reorder`）；四模块；`HG.ws.throttle(renderFeed, 300)` 保留 |
| `web/css/hg.css` | **纯追加** `.hg-q-*`：深色变量、红涨绿跌、等宽 `tabular-nums`、闪烁 keyframes、紧凑密度、sticky 表头、盘口比例条、SVG sparkline 容器；既有类零改动 |
| `tools/probes/t053_trade_probe.mjs` | 既有 15 条行情断言**逐字保留全绿**；新增 22 条 UI 断言（新列 / 口径 / 状态条 / 图例 / 差量行 / 新鲜度锚 / 红涨 / 排序 / 选中详情盘口 + SVG / 搜索 / 自选持久化 / 密度），补内存版 `window.localStorage` 桩 |
| `docs/BACKLOG.md` | 本节纯追加 |
| Java / C++ / proto / `CONTRACTS.md` / `routes.js` / `ApiRoutes.java` | **零改动**（无新端点、无 wire 变更、无 RBAC 变更） |

**保留的探针/纪律锚点**：`HG.ws.connect('quote')` + 防僵尸 `close()`、`subscribe channels=["quote"]` 与 instruments 大写归一由 `ws.js` 承担（页面传原始列表）、标题「实时行情（/ws/quote）」、`ui.banner` 原文案（`/ws/quote` / `允许丢帧` / `不用于任何自动决策` / `CH-007` / `数据时间` / `行情延迟/过期` / R-TRADE-11）、`data-ws-state`、`data-quote-stale="stale|fresh"`（带引号 HTML 字面）、「已连接」/「已断线（自动重连中）」、`F.dateTime` 数据时间、`QUOTE_STALE_MS=15000`、`tr-q-instr` 输入框与「订阅」按钮。

### 20.3 实施中修正的两处真实浏览器缺陷（DOM 桩不覆盖）

1. **星标点击冒泡到行选中**：`hg-q-fav` 的 click 会冒泡到 `tr` 的行选中 handler ⇒ 真实浏览器点星标会误选中行。修为 `e.stopPropagation()`（防御式调用，探针桩无该方法不报错）。DOM 桩不模拟冒泡 ⇒ 探针未发现，人工复核补上。
2. **筛选后无匹配的空态**：搜索 / 「仅自选」把可见行过滤为 0 时原逻辑仍显示空表格。修为按 `keys.length` 切换「无匹配合约（调整搜索词或「仅自选」筛选）。」空态与表格显隐。

### 20.4 验证（实测，以实时为准）

| 项 | 结果 |
|---|---|
| `node --check web/js/pages/trade.js` | OK |
| `t053_trade_probe.mjs` | **151 assertions ALL PASS**（既有 129 → +22 新 UI） |
| 全量前端探针 10 个 `.mjs` | **0 失败** |
| `mvn -B -o validate`（E1–E9） | **9 / 0 / 0，arch-check 通过**（E6 零构建链 + 跨模块引用） |
| 静态资源 served | `/web/js/pages/trade.js` 含 60 处 `hg-q`、`/web/css/hg.css` 含 93 行 `hg-q`；`Cache-Control: no-store` ⇒ **刷新即生效，无需重启 hg-biz** |
| `health-check.sh all` | **PASS=6 / FAIL=0**；双组件进程未重启（hg-biz 614301 / hg-trading 887910） |

**验证边界（如实披露）**：本环境无真实浏览器（无系统 chrome / playwright / puppeteer），DOM 结构、事件绑定、差量渲染、四模块交互均由 `t053` 的 **headless DOM 桩**断言覆盖（151 条），CSS 仅经静态 served + E6 零构建链门禁校验，**未经真实浏览器像素级渲染验证**；深色视觉、sticky 表头、闪烁动画、SVG sparkline 的最终呈现需在浏览器刷新后人工目视确认。§20.3 两处缺陷即因 DOM 桩不覆盖事件冒泡/真实布局而由人工复核补上。前端未引入任何真实浏览器专属 API（`canvas` / `createElementNS` 均未用）。

### 20.5 端到端数据源与时段边界（如实披露）

- 改造前 §19.5 已实测 20s / 352 帧 / 10 合约；本次改造**只动前端**，C++/Java/wire 零改动，数据源链路不变。
- 本次端到端复测时（2026-11-25 09:18 +0800，周三）：`:81/ws/quote` 握手 101、订阅成功，但 **10s 内 0 帧**。核因：`ctpMdConnected=false`、`ctpLoggedIn=false`、无到 `:40011` 的 TCP 连接、日志 `ctp=DEGRADED CTP 未登录/连接断开`，而累计 `quoteEvents=18881`（改造前行情一直正常）。⇒ **SimNow 7x24 服务时段结束**（交易日 16:00～次日 09:00，§19.6 已登记）导致前置断开，属服务端状态，**非 UI 改造故障**；待下一服务时段自动恢复连接后，刷新页面即可见多合约行情。
- 前端「已连接」徽标仅表示浏览器↔hg-biz 的 WS 通道，**不代表 CTP 前置有行情源**；断线/过期标识与 §17–§19 口径一致。

### 20.6 边界与遗留

- 无新端点 ⇒ `web/ApiRoutes.java` / `web/js/core/routes.js` 镜像不动；无新依赖（G8）；高频渲染仍经 throttle + 差量（§5.3）；行情不驱动决策（R-TRADE-11）未新增任何下单入口。
- 名称字典为**前端静态常量**（约 40 条，仅展示、不可配置、映射不到回退代码），非 wire 字段、不参与决策；如需名称可配置或真实涨跌幅，须另案走 `CH-xxx` 扩字段（本轮裁定 A 明确不做）。
- 自选持久化用 `window.localStorage` 键 `hg.q.favs`（非敏感），try/catch 降级；未引入 cookie/口令落盘。
- 仍为 dev/test 档、非实盘非验收；零落盘；改动未提交 git，提交 / push 需单独指令。

## 21. SimNow 前置切回第一组 30001/30011（2026-11-25，编排者 · 单写者）

> **性质**：用户「将地址切换到第一组 simnow，即 30001 端口后 30011 端口」⇒ 配置切换 + 只重启 hg-trading + 逐条留痕。§17–§20 原文不回改。全部计数 / 行号 / 时点**以实时为准**。

### 21.1 背景与预检

- §17 曾由第一组 `30001/30011` 切到 7x24 组 `40001/40011`；§20.5 记录 7x24 服务时段（交易日 16:00～次日 09:00）结束后 `ctpMdConnected=false`、端到端 0 帧。本次为交易日白天（09:53 +0800，周三）切回第一组。
- 切换前 TCP 预检（实测）：**`30001` 19 ms / `30011` 21 ms 均 CONNECTED**；`40001`/`40011` 均 `ECONNREFUSED`（errno 111）⇒ 印证切换原因，且第一组服务时段可用。

### 21.2 密钥形态裁定（保持 `api_key_mode=PRODUCTION`）

- `hg.ctp.api_key_mode=PRODUCTION` 是 §17 为 7x24「看穿式前置、监控中心生产秘钥」引入（W-10：SDK 握手密钥与 `CFG-22` 治理语义解耦，强制 `bIsProductionMode=true`，`env_type` 仍 `SIMULATION`）。
- **切回第一组不改此值**：§10.3 正是在第一组 `30001/30011` 上做的双模式对照 —— 评测密钥（`bIsProductionMode=false`）⇒ `Decrypt handshake data failed` ×2、无 LOGGED_IN；生产密钥（`true`）⇒ 0 次失败、+1.4s `ctp=LOGGED_IN`。本次重启后第一组同样 `ctp=LOGGED_IN`（见 21.4），复证该结论。
- 根 `hg.properties` 第 5 行日期/组名与 W-10 注释同步订正为「两组前置均需生产密钥」并附 §10.3 / §17 依据（**只改配置注释，非定版制品**）；`env_type=SIMULATION` 保持（治理：非实盘、测试账号 `9999`/`121964`、不下单）。

### 21.3 改动内容

| 文件 | 改动 |
|---|---|
| `hg.properties` | `trade_front` / `md_front`：`40001/40011` → **`30001/30011`**；第 5 行与 W-10 注释订正 |
| `/tmp/hg-verify-run.properties` | 同上两行地址（运行时派生配置，两个进程实际加载；不入库） |
| `docs/BACKLOG.md` | 本节纯追加 |
| 代码 / proto / 契约 / `subscribe_instruments` / `broker_id` / `user_id` / 端口分离 | **零改动**（仅地址，合约集 11 个与凭据不变） |

- `--dump-config --config /tmp/hg-verify-run.properties` 预校验回显 `trade_front=tcp://182.254.243.31:30001` / `md_front=...:30011` / `env_type=SIMULATION` / `api_key_mode=PRODUCTION`。
- 只重启 **hg-trading**（pid 887910 → 915249，真 CTP 轨二进制）；hg-biz 未动（不直连 CTP）。凭据仍从 `/proc/<pid>/environ` 内存注入，零落盘。

### 21.4 验证（实测）

| 项 | 结果 |
|---|---|
| 启动日志 | `state=NOT_READY` → `ctp=LOGGED_IN CTP 登录成功（DEGRADED 解除）` → `ctp.md subscribe instruments=11 code=0 accepted=11` → `state=READY param_version=22`；**无 `Decrypt handshake data failed`** |
| 组件状态 `?refresh=1` | `state=READY` / `ready=true` / `paramVersion=22` / `ctpTradeConnected=true` / `ctpMdConnected=true` / `ctpLoggedIn=true` / `ctpDegraded=false` / `ctpFront=tcp://182.254.243.31:30001` |
| CTP TCP | 两条 `ESTAB` → `182.254.243.31:30001`（fd=15）与 `:30011`（fd=13），无 40001/40011 残留 |
| 行情计数 | `quoteEvents=124` 并持续增长、`quotesDropped=65` |
| `health-check.sh all` | **PASS=6 / FAIL=0 / rc=0** |
| 端到端 WS | `:81/ws/quote` 握手 101 → 订阅（留空=全部）→ **12s 内 186 条 `type=quote` / 10 个不同合约**：`ag2612`20、`au2612`20、`rb2701`19、`IF2612`19、`i2701`19、`TA701`19、`ru2701`19、`m2701`18、`SR701`17、`cu2612`16 ⇒ 前端多合约行情恢复 |

**如实披露**：`alerts` 中残留 `component.ctp degraded=true,loggedIn=false`（09:18，7x24 时段结束）与 `component.state STOPPING`（09:55 本次重启）等历史内存告警（§16.5 已登记「告警留痕仅内存、重启不保留」——本次未重启 hg-biz，故留痕仍在），均为**时点快照**，与当前 `ctpDegraded=false` / `READY` 不一致属预期；`reconnectCount=6` 同为 Java 侧累计值（未重启 hg-biz）。当前连接态以 21.4 实时快照为准。

### 21.5 边界与遗留

- 第一组为常规交易时段服务，**非 7x24**；非交易时段/收盘后可能再次断连 ⇒ 属服务端状态，届时如需可切回 7x24 组（时段 16:00～次日 09:00），两组前置均用 `api_key_mode=PRODUCTION`，仅端口不同。
- 前端行情终端（§20）零改动；`Cache-Control: no-store`，刷新页面即看到第一组行情。
- 仍为 dev/test 档（`env_type=SIMULATION` + SQLite），非实盘、非验收；未发交易指令、无实盘资金；生产部署口径恒 DM8。零落盘；改动未提交 git，提交 / push 需单独指令。

## 22. CH-027 行情订阅动态化：实施落地与验证（2026-11-25，编排者 · 单写者）

> **性质**：用户「通过订阅改变行情合约未成功……增加查询合约并选择订阅」+ 三档通道裁定选 **C 新增 HGCP 命令** + 见到 40→41 连锁代价后**坚持 C** ⇒ L3 契约级实施。裁定单见 `docs/DECISIONS/CH-027-quote-subscription-command.md`。§17–§21 原文不回改，本节纯追加；全部计数 / 版本 / 行号 / 时点**以实时为准**。

### 22.1 实施落地清单

| 层 | 制品 | 改动 |
|---|---|---|
| wire | `proto/hgcp.proto` | `CMD_SET_QUOTE_SUBSCRIPTION=0x011F` + `message CmdSetQuoteSubscription{repeated string instruments=1}`；消息集 **40→41** |
| Java 生成 | `java/.../hgcp/v1/Hgcp.java` | `protoc 3.21.12` 重生成（先逐字校验原文件） |
| C++ 协议 | `protocol.h/cc`、`payload_codec.cc`、`command_handler.h/cc`、`frame_test.cc` | 枚举 / `is_known` / `to_string` / 解码分支 / 回调注入 `QuoteSubscriptionFn` / 消息表 41 项 |
| Java 契约 | `HgcpConstants.java`、`TradingBridge.java`（第八方法 `setQuoteSubscription`）、`HgcpClient.java`（覆写发令）、`java/CONTRACTS.md`（`HGC-1.8`）、`TradingBridgeContractTest`（7→8 方法） | |
| CTP 适配 | `ct_gateway.h` / `ct_gateway_ctp.cc`（`UnSubscribeMarketData` 封装）、`ct_adapter.h/cc`（`setQuoteSubscription` 完整替换：等价短路、退订旧集、订阅新集、空集=全退订、`subscribed_instruments_` 记录） | |
| C++ 应用 | `trading_app.h/cc`（`handler_.set_quote_subscription_fn` 注入 + `setQuoteSubscription` 回调 + 日志 `ctp.md set-subscription`） | |
| REST | 新增 `QuoteSubscriptionHandler.java`（`POST /api/v1/trade/quote-subscription`，**原生大小写不归一**）、`TradeRoutes.java`（3 参 `register`）、`Bootstrap.java`、`ApiRoutes.java`、`web/js/core/routes.js` | |
| WS | `WsServer.java#flushQuotes` 应用 `matchesQuote(instrument)` —— 修复既有缺陷（此前仅判 `wantsQuote()`，W-1 `instruments` 对实时帧零生效） | |
| 前端 | `web/js/pages/trade.js`（静态目录 `QUOTE_CATALOG`、交易所/品种/主力筛选、`applyContracts` POST 原生大小写 + WS 大写过滤）、`web/css/hg.css`、探针 `t053_trade_probe.mjs` | |
| 冻结面 | `docs/ARCHITECTURE.md`（§4.2.3 41 种 / §4.2.8.1 215 / §4.2.8.3 控制 18 条 78）、`scan_hgcp_schema.py`(E7)、`scan_frozen_invariants.py`(E9)、四处 `ROUTE_COUNT` 硬编码 | |

### 22.2 离线门禁与测试（本轮实测 2026-11-25 12:26–12:33 +0800）

| 门禁 | 结果 |
|---|---|
| `mvn -B -o clean test` | **1564 tests / 0 failures / 0 errors**（较上轮 +1：新增 `WsServerTest#realtimeQuoteRespectsInstrumentWhitelist`，W-1 白名单**负向**回归） |
| `mvn -B -o validate`（E1–E9） | **通过(0)=9 / 命中(1)=0 / 前置错误(2)=0**；E9 四项冻结不变量：消息 **41** / 错误码 **62** / VER **0x0100** / FR **184** 全部一致 |
| C++ 双轨 ctest（clean env，上轮） | 真轨 **67/67**、stub 轨 **63/63**（本轮仅改 Java 测试，C++ 制品未动，不重跑） |
| 前端探针（上轮） | 全量 **10/10**；`t053_trade_probe.mjs` **167 断言 ALL PASS**（本轮未动前端，不重跑） |
| 新增 W-1 回归定向 | `mvn -B -o -Dtest=WsServerTest test` **16/16**；先单跑确认负向用例不 flake（700ms 观察窗 ≥3 个 200ms 节拍、心跳 30000ms 不干扰） |
| `health-check.sh all`（12:33） | **PASS=6 / FAIL=0**；两进程存活、`state=READY param_version=23` |

- 编译 `-Xlint:all`：本轮新增测试**零新增告警**；日志中存量 test-scope `unchecked conversion` 与业务 `WARNING:` 输出非本轮引入。

### 22.3 运行期命令链实测（本轮关键增量，2026-11-25 12:18–12:22 +0800）

> 不依赖 CTP 交易前置登录、不依赖真实行情帧；验证「REST → HGCP → C++ 回调 → `CtAdapter`」是否真正贯通。会话走 `devtest`（`must_change_password=N`），凭据取自 `/tmp` 运行期助手（零落盘、不入库、不写本文字面值）。

| 步骤 | 实测 |
|---|---|
| 组件鉴权令牌 | 两进程 `HG_HGCP_AUTH_TOKEN` 摘要**一致** ⇒ HGCP 通道可用 |
| `POST /api/v1/trade/quote-subscription` `{"instruments":["rb2701","IF2612","SR701","cu2612"]}` | `HTTP 200` `{"code":0,...,"acknowledged":true,"componentCode":0,"message":"quote subscription applied: count=4"}`；**原生大小写逐字回显** |
| hg-trading 日志 | `ctp.md set-subscription count=4 detail=quote subscription applied: count=4` ⇒ `CMD_SET_QUOTE_SUBSCRIPTION` 已抵达 C++ 并触发回调 |
| 恢复原 11 合约 | `POST` 原集 ⇒ `applied: count=11`；日志 `count=11 detail=…count=11`，**系统状态复原**（退订旧集 + 订阅新集路径均实走） |

**结论**：`CH-027` 的 Java REST → HGCP `0x011F` → C++ `CommandHandler` → `TradingApp::setQuoteSubscription` → `CtAdapter::setQuoteSubscription` 全链**已在运行期贯通并留有日志证据**；`acknowledged=true` 且 `componentCode=0`。

### 22.4 端到端行情帧：未完成（如实披露，含口径澄清）

- 12:20 +0800 于 `:81/ws/quote` 握手 101、订阅（留空=全部）后 **12s 内 0 帧 `type=quote`**。
- **口径澄清（本轮实测）**：`GET /api/v1/monitor/component?refresh=1` 返回 `ctpLoggedIn=false` / `ctpDegraded=true` / `ctpReason="CTP 前置连接已发起，登录结果待异步回调"`，且 `hg.ctp.password=<empty>`。但 `CtAdapter::isLoggedIn()` 取的是 **`trade_logged_in_`**（交易前置，ct_adapter.cc:186），而行情订阅守卫取 **`md_logged_in_`**（行情前置，ct_adapter.cc:259）。§22.3 返回 `applied` 而非 `rejected: MD not logged in` ⇒ **行情前置 30011 的登录回调已成功**（CTP MD 前置常接受空/任意口令，与交易前置不同）；两条 `:30001`/`:30011` TCP 均 ESTAB。
- ⇒ 0 帧的**主因是时段**，而非行情前置未登录：12:20 处于国内期货 **11:30–13:00 午间休市**，全市场无 tick；§21.4 于 09:53（早盘）实测 12s / 186 帧 / 10 合约可复证「时段外自然 0 帧」。
- ⇒ **「页面选合约 → WS 行情帧集合随订阅改变」这一帧级端到端尚未闭环**。命令链（§22.3）已闭环；帧级闭环只需 **① 交易时段内**（午休结束后），行情前置已登录且订阅集已就位；`WsServer.flushQuotes` 白名单过滤已由离线 `WsServerTest` 负向用例覆盖。
- **交易前置 DEGRADED 是独立事项**：`ctpLoggedIn=false` 仅因交易前置 30001 未完成登录（一次性口令丢失，§22.5）；它影响组件就绪观感与交易能力，**不影响行情帧**（行情走 MD 前置）。恢复完整态需重注口令。

### 22.5 运行期凭据事故与现状（如实披露）

- 上轮为腾出 `:39000` 跑 clean-env ctest，**同时停止** hg-trading / hg-biz；一次性 `HG_CTP_PASSWORD` 与原 `HG_HGCP_AUTH_TOKEN` 仅存在于两进程 `/proc/<pid>/environ`（零落盘设计）⇒ 停机即**不可恢复**。
- 本轮实测：遍历全部 `/proc/*/environ` **无任何进程持有非空 `HG_CTP_PASSWORD`**；仓内 / `/tmp` 无字面值。
- 现状：两进程已用**新生成且一致**的 `HG_HGCP_AUTH_TOKEN` 恢复（12:06），`health-check.sh all` PASS=6、`state=READY param_version=23`；`hg.ctp.password=<empty>`、交易前置 DEGRADED、行情前置已登录。
- 恢复动作（若用户要求完整态，待重注一次性口令后执行并**另追加**结论，不回改 22.4）：重注 `HG_CTP_PASSWORD` → 以一致 token 重启两进程 → 核 `ctpLoggedIn=true` / `ctpDegraded=false` → 交易时段内做帧级端到端复验。

### 22.6 边界与遗留

- 前端静态合约为「非权威全市场、随换月维护」，主力为「按月份规则近似、非实时持仓量判定」—— 均为裁定②③明确的展示层口径，不扩 wire 字段。
- 仍为 dev/test 档（`env_type=SIMULATION` + SQLite），非实盘、非验收；行情只展示不驱动决策（R-TRADE-11）；未发交易指令、无实盘资金；生产部署口径恒 DM8。
- 零落盘：订阅集仅内存；口令/令牌不落盘、不入库、不写本文字面值。
- 改动未提交 git，提交 / push 需单独指令。

## 23. CH-027 帧级端到端复验结论（2026-11-25 13:07–13:09 +0800，编排者 · 单写者）

> **性质**：用户裁定「13:00 后自动复验」；§22.4 已写明「后续帧级复验结论追加至 `BACKLOG.md`，不改写本节」⇒ 本节纯追加，§22.4 的 ⏸ 状态由本节**显式登记结清**。§17–§22 原文不回改；全部计数 / 时点**以实时为准**。

### 23.1 条件与前提

- 时点 13:07（午间休市 11:30–13:00 已结束，交易时段恢复）；**无需重注口令、未重启进程、未改配置**。
- 会话 `devtest`；`:81/ws/quote` 握手 101，`subscribe{instruments:[]}`（**留空=全部**）⇒ 刻意不改 WS 白名单，以隔离出 **CTP 侧重订阅**的真实效果（W-1 前端过滤另由 §22.2 离线回归覆盖）。
- 三次 REST 均经 `POST /api/v1/trade/quote-subscription`，全部 `acknowledged=true` / `componentCode=0`。

### 23.2 实测（帧级）

| 阶段 | 动作 | WS 观察窗内 `type=quote` 帧 | 合约集 |
|---|---|---|---|
| ① 基线 | 原 11 合约 | 12s / 21 帧 | `IF2612`×21（其余 10 合约该窗口无 tick） |
| ② 动态改 2 合约 | `POST ["rb2701","IF2612"]` → `applied: count=2` | 14s / 32 帧 | **`{IF2612, RB2701}`**（`RB2701`×1 为订阅初始快照；其余 9 合约 **0 帧**） |
| ③ 恢复 11 合约 | `POST` 原集 → `applied: count=11` | 10s / 35 帧 | **全部 11 合约**（`IF2612`×25 + 其余各 1 帧初始快照） |

- C++ 日志逐条对应：`ctp.md set-subscription count=2 detail=…applied: count=2`（日志行 379）、`count=11`（380）；连同 §22.3 的 377 / 378，共 **4 次下发全部留痕**。
- **判定**：阶段② 合约集 `{IF2612, RB2701}` ⊆ 所选 `{rb2701, IF2612}`，**旧集合约零残留帧**；阶段③ 11 合约全部回归 ⇒ 「选合约 → C++ 重订阅 → WS 帧集合随订阅改变」**闭环成立**。

### 23.3 如实披露（帧分布口径）

- 本窗口行情极度不均：`IF2612`（CFFEX 股指）持续 tick（21→31→25 帧），其余 10 个商品合约多数仅收到 CTP 订阅后的 **1 帧初始快照**。属 SimNow 第一组该时段数据源活跃度差异，与 §21.4（09:53 早盘 12s / 186 帧 / 10 合约）可对照，**非 CH-027 故障**；订阅集与帧集合的对应关系不受影响。
- `ctpLoggedIn` 仍为 `false`（交易前置 30001 未完成登录，§22.5 一次性口令丢失）；**行情帧走 MD 前置，不受影响** —— 本轮在 DEGRADED 观感下仍收到真实行情帧，复证 §22.4 口径澄清。
- 未重启进程、未改配置、未动 C++ / 前端制品；订阅集最终**已恢复为原 11 合约**。

### 23.4 CH-027 结案

- 裁定 ①②③④⑤ 全部兑现；冻结面 40→41 / `HGC-1.8` / 第八方法 / E7/E9 同步经门禁自洽。
- 验收矩阵（§22.2 全部门禁 + §22.3 运行期命令链 + 本节帧级端到端）**全部通过**；§22.4 / 裁定单 §6 的 ⏸ 条目由本节显式登记结清（原文保留、不回改）。
- 仍为 dev/test 档（`env_type=SIMULATION` + SQLite），非实盘非验收；行情只展示不驱动决策（R-TRADE-11）；零落盘；改动未提交 git，提交 / push 需单独指令。
