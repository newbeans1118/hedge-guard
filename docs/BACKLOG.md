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
