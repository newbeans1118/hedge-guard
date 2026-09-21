# `T-028` 验收证据：RSK-06 Java 端 WebSocket（A′）`E1`–`E13`

> **交付席**：`backend-engineer#java`（`java/**` 唯一写者）｜ **时间**：2026-09-21（+08:00）
> **判据出处**：`docs/DECISIONS/websocket-selection.md` §3（`E1`–`E13`），与 `ARCHITECTURE.md` §4.1.2 / §7.1.11 / §9 `RSK-06` / §11.2 `CFG-32` 对齐
> **任务契约**：`docs/TASKS.md` `### T-028`（验收 1–5）
> **范围边界**：本单 = **选型 + 最小可行实现 + E1–E13 证据**；生产级（连接/订阅/推送/鉴权/心跳/背压的规模化装配）归 `T-040`。
> 本文仅为**证据登记**（非契约变更），不改任何签名 / 字段 / 错误码 / 路径。

---

## 0. 构建与自检（四要素）

| 项 | 值 |
|---|---|
| 隔离副本 | `/tmp/t028`（布局 `<root>/{docs,java}`） |
| 命令 | `cd /tmp/t028/java && mvn -B -o clean test` |
| 时间戳 | **2026-09-21 15:43（+08:00）**（最终态；干净构建 3 次一致） |
| 结果 | **`Tests run: 443, Failures: 0, Errors: 0, Skipped: 0` ｜ BUILD SUCCESS** |
| 基线对比 | 基线 `435/0/0/0` → **`443/0/0/0`**（**+8** = 本单新增 `WsAcceptanceTest` 8 用例；**无既有用例改动**） |
| `@Test` 计数 | `grep -rho '@Test' java/src/test \| wc -l` = **443** == surefire `tests=` **443** ✅ |

> ✅ **陈旧构建产物已证伪**：`docs/DECISIONS/websocket-selection.md` §2.3 / §6 记录的 4 个
> `NoClassDefFoundError`（`WsAuthorizer$PermDaoGrantReader` / `WsServer$Stats`）系**旧 `target/` 症状**；
> **一次 `clean` 全量构建即全绿**（63 个既有 WS 用例全过）。

---

## 1. `E1`–`E13` 逐条判定

| # | 判据要点 | 现状 | 证据（命令 + 实测） |
|---|---|---|---|
| **E1** | 握手成功 + `Sec-WebSocket-Accept` 与 RFC 6455 §1.3 官方向量逐字节一致 | ✅ **满足** | `WsFrameCodecTest.officialHandshakeVector` / `WsHandshakeTest.acceptKeyMatchesSpec`（`dGhlIHNhbXBsZSBub25jZQ==` → `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`）；真实 TCP 升级 `WsAcceptanceTest.e1RealSocketUpgradeCarriesOfficialAccept`（101 + Accept 官方向量）。**无浏览器环境**，以真实 socket 客户端完成 101 升级等价替代（诚实说明） |
| **E2** | N ≥ 32 并发、单合约 ≤5 条/秒节流下，P95 ≤ 200 ms | ✅ **满足（已补证据）** | `WsAcceptanceTest.e2ConcurrentLatencyP95Under200ms`；**实测 N=32，3 次运行 P95 ∈ {15, 27, 44} ms**，样本量 480，P50≈8ms、P99≈19–55ms。**口径**：行情**持续驱动**（市场真实形态）→ 覆盖式使节拍处恒为最新值，故时延远小于节拍上限 |
| **E3** | 强制断连→重连→重订阅 100%（重连后须重新订阅） | ⚠️ **主指标满足；措辞一处不符（待裁定）** | `WsAcceptanceTest.e3ReconnectThenResubscribeDeliversAgain`：5 轮断连-重连-重订阅，重订阅后**100% 再次收到**新合约行情；**旧订阅对象不跨连接保留**（新连接按会话重新求交授权）。<br>⚠️ **E3 原文「重连后未订阅不得收到任何推送」与冻结契约 §4.3「端点即通道 / 首帧 = `subscribed`」不符**：实现**在连接建立时即以「端点通道 + 该用户全部已授权子账户」初始订阅**（`WsServer:363–366`），故未显式 `subscribe` 的**新连接**仍会收到该通道推送。**建议**：以 §4.3 为准修订 E3 措辞，或明确"须显式订阅才下发"（属契约变更，须走 `CH-xxx`）。**本席不擅自改冻结行为** |
| **E4** | 心跳 ≥1000 ms；<1000 **构造期拒绝**；连续 3 次无 pong → 关闭 | ✅ **满足（含负例）** | `WsAcceptanceTest.e4HeartbeatBelow1000RejectedAtConstruction`（999/0 ms → `IllegalArgumentException`；1000 合法；`max_connections<1`、`port>65535` 亦拒）；`WsAcceptanceTest.e4ThreeMissedPongsCloseConnection`（1s 心跳、不回 pong → 服务端 `close(1001)` 并清理连接）；实时 ping 抓取 `WsServerTest.serverSendsProtocolPing` |
| **E5** | 越权订阅 → `10004` + 留痕 + `close(1008)`；安全默认 = 空集 | ✅ **满足** | `WsAuthorizerTest.unauthorizedSubAccountRejectedAndRecorded` / `mixedRequestIsRejectedAtomically` / `noGrantMeansNothingDelivered`；端到端 `WsServerTest.unauthorizedSubscriptionIsRejectedAndRecorded`（先 `error{10004}` 再 `close(1008)` + 留痕）、`noGrantMeansNoAlertDelivery`（零下发）。**`CROSS_READONLY`**：WS 订阅语义为**读**，读面授权集统一纳入（不区分 `perm_type`）——即"跨账户只读用户可得只读告警"，与 §4.1.2 一致；**写面**的 `CROSS_READONLY` 拒绝不属本轮（归 `T-098`/`T-112`/`T-115`） |
| **E6** | 背压弃帧可观测 + 不背压消费线程；告警满 → `1013` | ✅ **满足** | `WsConnectionTest.quoteDropsOldestWhenFull`（丢最旧 + `droppedQuote` 计数 + 容量恒定）、`enqueueNeverBlocks`（5000 次满队入队 <1s）、`alertNeverDropsButClosesOnOverflow`（`1013`）；计数可读 `WsServer.Stats.droppedQuote` |
| **E7** | 握手复用 `HGSESSION`/`X-HG-Session`；缺失/失效 → 401；越权只依服务端解析 | ✅ **满足（已补过期负例）** | 无凭据 401 `WsServerTest.handshakeWithoutSessionIs401`（业务码 `10006`）；**过期会话 401** `WsAcceptanceTest.e7ExpiredSessionRejectedAndForgedSubAccountsIgnored`；伪造 `subAccounts` 由 `WsAuthorizer` 强制求交裁决（见 `WsAuthorizerTest`） |
| **E8** | 覆盖式（只推最新、无堆积）+ 可丢不补数 | ✅ **满足（已补断言）** | `WsAcceptanceTest.e8QuoteIsLatestOnlyAndTsMonotonic`（同合约一窗口内推 50 次 → 客户端仅收 ≤2 帧；跨节拍 `tsMillis` 单调不减）+ `quoteStaleFrameWellFormed`；`WsServerTest.subscribeThenReceiveQuote` |
| **E9** | 与 `CH-007` 消费侧一致：补数批次行情帧**接收侧不消费、不推前端** | ❌ **本单不可判定（落点未建）** | `grep -rn 'EVT_MARKET_QUOTE\|IS_RESUME_DATA\|resume' java/src` → **0 命中**；`TradingBridge`（签名冻结）**无行情事件回调**，仅 `mirror()` 快照。⇒ Java 侧**尚无 HGCP 事件消费路径**，WS 的"接收侧"不在本轮存在。**E9 的落点 = HGCP 事件消费层（`T-040` 及行情接入任务）**。**本席不把"无法判定"写成"已通过"**；WS 侧仅保证 `publishQuote` 只推被显式发布的值（消费侧须对补数批次不调用） |
| **E10** | 仅监听 `127.0.0.1` | ✅ **满足（构造性）** | `WsServerTest.bindsLoopbackOnly`（`isLoopbackAddress()` 真、`isAnyLocalAddress()` 假）；`WsServer.start()` **硬编码** `InetAddress.getLoopbackAddress()`，**从不接受 bind 参数** ⇒ 不存在"非回环配置"可达路径（比"配置拒绝"更强）。故 E10 的"配置非回环 → 拒绝启动"负例**N/A**（无该配置入口） |
| **E11** | 会话 ID / Cookie 全值 / 口令 **不落盘、不入日志** | ✅ **满足** | `grep -rn 'sessionId\|Cookie\|password\|secret' java/src/main/java/com/hedgeguard/web/ws/` → **仅 1 处 javadoc 说明**（`WsHandshake:21`），**无任何日志/落盘**；WS 包 13 条 `LOG.*` 均为 `connId/userId/path/traceId/ip/reason`，**不含会话凭据**；全局脱敏 `SensitiveLog.installRoot()`（`Bootstrap:248`） |
| **E12** | 前端零构建链（无 `package.json`/`node_modules`/打包产物） | ✅ **满足** | 仓库扫描 `find`（排除 `java/target`）：`package.json` / `package-lock.json` / `node_modules` / `yarn.lock` / `pnpm-lock.yaml` / `webpack.config.js` / `vite.config.js` → **0 命中**；`web/` 仅 `index.html` / `login.html` / `css/` / `js/`（原生 `WebSocket`） |
| **E13** | 依赖零新增 + 禁引清单复核 | ✅ **满足** | `mvn -B -o dependency:list`：**compile = 2**（`protobuf-java:3.21.12`、`gson:2.10.1`）+ **test = JUnit 5**；禁引名单（`HikariCP`/`SLF4J`/`Logback`/`Log4j`/`BCrypt`/`Mockito`）**0 命中**；DM JDBC 仅在 `profile(dm)`（默认不激活）。**本轮未新增任何依赖** |

**判定小结**：满足 **11**（E1/E2/E4/E5/E6/E7/E8/E10/E11/E12/E13）、**部分/措辞待裁定 1**（E3）、**不可判定 1**（E9，落点未建）。

---

## 2. 缺口补齐清单（本单新增）

| 文件 | 改动 | 用例 |
|---|---|---|
| `java/src/test/java/com/hedgeguard/web/ws/WsAcceptanceTest.java`（**新增**，380 行） | 承载 E1–E13 端到端可判定证据 | `e1RealSocketUpgradeCarriesOfficialAccept`、`e2ConcurrentLatencyP95Under200ms`、`e3ReconnectThenResubscribeDeliversAgain`、`e4HeartbeatBelow1000RejectedAtConstruction`、`e4ThreeMissedPongsCloseConnection`、`e7ExpiredSessionRejectedAndForgedSubAccountsIgnored`、`e8QuoteIsLatestOnlyAndTsMonotonic`、`quoteStaleFrameWellFormed` |

> **`src/main` 零改动**：`web/ws/**` 10 个类的 md5 与基线**逐字节一致**（含 `WsServer` `480862879811d27607a244ba0d81ee48`，与 `websocket-selection.md` §2.3 记录一致）。
> `web/ApiRoutes.java` **未改动**（`3dec526b47b5b444d57de8898a07e030`，全程一致）。

---

## 3. 反退化（自设变异，本单实测）

> 变异**仅在隔离副本**施加；每轮**先备份 `.orig`**、跑目标用例取证、**逐字节复位校验**、`clean` 重建复绿。

| # | 变异点 | 改法 | 预期变红的用例（实测） | 结果 |
|---|---|---|---|---|
| **M1** | 放宽握手校验 | `WsHandshake.validateUpgrade` 去掉 `Sec-WebSocket-Version: 13` 检查 | `WsHandshakeTest.validatesUpgrade` ✅ 红（`Tests run: 11, Failures: 1`） | 🔴→复位 |
| **M2** | 去掉心跳超时 | `WsConnection.heartbeatExpired()` 恒 `false` | `WsAcceptanceTest.e4ThreeMissedPongsCloseConnection` ✅ 红（收到 7 次 ping 无 close） | 🔴→复位 |
| **M3** | 越权静默求交 | `WsAuthorizer.authorize` 越权分支改 `continue`（不拒/不留痕） | `WsAuthorizerTest` **3 红** + `WsServerTest.unauthorizedSubscriptionIsRejectedAndRecorded` **1 红** | 🔴→复位 |

**复位校验**：三文件 md5 与共享树 `MATCH`；`grep -rn MUTANT java/src` → **0 残留**；复位后 `clean test` = `443/0/0/0`。

---

## 4. 遗留 / 阻塞（如实登记，**不写成"已通过"**）

| # | 项 | 状态 | 处置 |
|---|---|---|---|
| **R-1** | `E3` 措辞「重连后未订阅不得收到任何推送」与冻结契约 §4.3「端点即通道、连接即 `subscribed`」**不一致** | 待裁定 | 请技术负责人裁定：以 §4.3 修订 E3 措辞，或改为"须显式订阅才下发"（后者为实质变更，须 `CH-xxx`）。**本席未改冻结行为** |
| **R-2** | `E9`（`CH-007` 消费侧）**无落点** | 不可判定 | Java 无 HGCP 行情事件消费路径（`TradingBridge` 无回调）。落点 = `T-040`/行情接入任务；须在那层注入"含行情帧的补数批次"做断言 |
| **R-3** | `E2` 稀疏发布（≈5 条/秒）下时延受 **200 ms 节拍**支配（上限 ≈200 ms） | 说明（非缺陷） | 节拍 = `1000/5` 是"≤5 条/秒/合约"的直接实现；**持续行情**下 P95 ≪ 200 ms（实测 ≤44 ms）。稀疏场景 P95 逼近 200 ms 属设计内在口径，供 `T-030` 参考 |
| **R-4** | `WsServer` 未接线到 `Bootstrap` | 非本单 | 归 `A-2`/`T-040`（`CFG-32 hg.web.ws.port` 无实现值，不得编造默认端口） |
| **R-5** | 真浏览器连通（`E1` 后半句） | 环境前置 | 无浏览器环境；以真实 TCP 客户端 101 升级 + 官方向量 Accept 等价替代 |

---

## 5. 锚点与文件（以**实时 `md5sum` 为准**）

| 项 | 值 |
|---|---|
| `java/src` 内容级锚点 | **`6bfe9cc3227be5c6b8c1deb1763642ce`** / **318 文件** —— `find java/src -type f \| sort \| xargs md5sum \| md5sum`（cwd=仓库根） |
| 基线（本单开工前实测） | `55723a7dbbb7ee61fd839a72ca156d7e` / 317 |
| `web/ApiRoutes.java` | `3dec526b47b5b444d57de8898a07e030`（**未改动**） |
| `web/ws/WsServer.java` | `480862879811d27607a244ba0d81ee48`（未改动） |
| 新增测试 | `WsAcceptanceTest.java` `8bbc07519740babd385d1a9c7042ae11` / 380 行 |
| 停笔时刻 | **2026-09-21 15:43（+08:00）** |

> **停笔声明**：**本实例停笔于 `2026-09-21 15:43 (+08:00)`；同席位是否另有实例在写我无法担保，以实时 `md5sum` 为准。**
