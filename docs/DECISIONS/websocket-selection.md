# 决策记录：Java 端 WebSocket 承载选型（`CFG-32` / `/ws/quote`、`/ws/alert`）

> **决策编号**：`WS-DEC-001` ｜ **负责人**：技术负责人（`tech-lead-14`）｜ **日期**：2026-09-20
> **状态**：**已定案 —— 采用方案 A′（自实现，零新增依赖）**，与架构现行版本、编排者裁定一致
> **定位**：**架构侧裁决与登记**。选型**采纳 `#java` 已产出的实证**（`java/CONTRACTS.md` §4.2 结论与候选依赖树表、§4.1 对外约定；`java/SELECTION_HTTP_WS.md` 证据附录）；本席对**承载结论的关键事实**做了独立复核（§2），**结论一致、无相反意见**。
> **需求追溯**：`PROJECT.md` §5.1 / §11 `CH-006`（HTTP / WebSocket 全在 Java 端；允许轻量库）、`ARCHITECTURE.md` §7.1.11 / §7.1.7 / §4.1.2 / §6.13 / §9 `RSK-06` / §11.2 `CFG-32`、`NFR-PERF-5`、`NFR-SEC-3`、`NFR-SEC-7`（`CH-007` 消费侧规则）
> **对应任务**：`T-028`（选型 + 最小可行实现）、`T-040`（生产级服务端，依赖本决策）
> **适用范围**：仅覆盖 `hg.web.ws.port` 上、由 **Java 业务组件**提供的两个升级端点；**不含** C++ 组件（`CH-006` 后 C++ 不提供任何 HTTP / WebSocket）。

---

## 0. 结论摘要

**推荐方案 A′：在独立 `ServerSocket`（监听 `CFG-32 hg.web.ws.port`）上手工完成 HTTP Upgrade 握手 + 自实现 RFC 6455 帧编解码，零新增依赖。**

一句话理由：**在"尽量降低第三方库依赖"的硬约束下，唯一可行的引库路径已被证伪**（`Java-WebSocket` 强依赖被禁的 `slf4j-api`；`nanohttpd-websocket` 9 年未更新且每连接一线程；Javalin/Jetty 体积与 JDK 门槛不符），**而自实现的需求面极窄**（两条服务端单向通道、无客户端业务消息、不需要分片 / 压缩 / subprotocol），A′ 是**唯一零新增依赖且与已冻结契约零冲突**的路径。

> ⚠️ **对派发前提的一处更正（必读）**：派发描述中的**方案 A**「JDK 内置 `HttpServer` 协议升级 + 自实现 RFC 6455」**不可实现** ——
> `com.sun.net.httpserver.HttpExchange` / `HttpServer` 的公开 API **不提供** `upgrade` / `hijack` / 裸套接字接管能力。
> 本席独立复跑取证见 §2.1。故方案 A 已订正为 **A′：独立监听 + 手工握手**（本席已复核该订正成立）。
> 另：派发描述中「`org.java-websocket` 单 jar 且零强制传递依赖」**不成立**，本席独立取证见 §2.2。

---

## 1. 决策要素（架构 §7.1.11 要求的四要素）

### 1.1 用途

Java 业务组件为**前端**提供两个 WebSocket 端点（`ARCHITECTURE.md` §4.1.2），源数据经 `TradingBridge` 事件流镜像获得：

| 端点 | 方向 | 承载 | 频率 / 丢帧 | 语义要点 |
|------|------|------|-------------|----------|
| `/ws/quote` | 服务端→客户端 | 行情 `{"type":"quote","instrument":…,"last":…,"bid":…,"ask":…,"ts":…}` | CTP 行情驱动，**节流 ≤ 5 条/秒/合约**；**允许丢帧** | **覆盖式**（每合约只推最新值）、**可丢、不补数、无回执** |
| `/ws/alert` | 服务端→客户端 | 告警 `{"type":"alert","level":…,"metric":…,"value":…,"threshold":…,"subAccount":…,"ts":…}` | 事件驱动；**不允许丢** | 断线重连后由客户端**补拉 REST `/risk/alert?since=`** |

在选型维度上，用途必须同时满足下列**七项功能**（缺一即不可用）：

1. **订阅模型**：客户端→服务端 `subscribe` / `unsubscribe`（按 `channels` + `instruments` + `subAccounts`），服务端回 `subscribed` 确认；仅向**已订阅**连接推送其订阅合约 / 子账户的数据。
2. **鉴权复用 `HGSESSION` Cookie 与子账户上下文**：握手期**复用既有会话设施**（§4.1.1 的 `HGSESSION`（HttpOnly）或 `X-HG-Session`），**不因 WS 而放宽**；会话解析结果（`userId` / 权限版本 / 已授权子账户集）注入连接上下文，成为推送过滤的**唯一依据**。
3. **心跳与重连**：使用 **RFC 6455 原生 `ping` / `pong` 控制帧**（非业务 JSON 心跳），间隔 `hg.web.ws.heartbeat_ms`（**≥ 1000 ms**）；连续 3 次无 `pong` → 关闭连接并清理资源；客户端重连后**必须重新订阅**（服务端不跨连接保留订阅）。
4. **背压与限流**：全局与**单连接**推送速率有上限（`/ws/quote` 节流 **≤ 5 条/秒/合约**）；**每连接有界出站队列**，满时按通道语义处置而**绝不阻塞**事件消费线程（`J3`），即**不背压** `EVT_MARKET_QUOTE` 消费路径（`R-8` / `RSK-11`）。
5. **覆盖式推送**：同一合约只保留最新一帧，旧值被覆盖（丢弃而非排队），保证前端始终渲染最近价。
6. **可丢不补数**：行情不做补数、不发回执、不占 `SEQ`；告警的"不丢"由**客户端重连补拉 REST** 兑现，**不由 WS 层追加缓存或用例**。
7. **与 `CH-007` 消费侧规则一致**：`IS_RESUME_DATA` 补数批次中的 `EVT_MARKET_QUOTE` **接收侧不消费、不推前端**（行情为最近值语义，重放陈旧价会误导）——WS 层**不得**把补数回放的行情当作有效行情下发，**也不得**把该批次计入"行情新鲜度"。

**边界（不做）**：不做事前风控判定；不查库阻塞推送；不直连 C++（仅消费 `TradingBridge` 事件流镜像）；不提供客户端→服务端的**业务**消息（仅订阅 / 心跳）。

### 1.2 版本

| 候选 | 版本 | 许可 | 新增依赖 | 本席取证 |
|------|------|------|----------|----------|
| **A′（推荐）** | **无外部版本** —— 对标 **RFC 6455**；握手正确性以 RFC 6455 §1.3 官方向量断言。运行基线 = **JDK 11**（本环境实测 `11.0.32`） | N/A（自实现，随产品） | **0**（`java.base` 内的 `ServerSocket` / `MessageDigest` / `Base64` / `java.util.concurrent`） | **已取证**（§2.1） |
| B1 `org.java-websocket:Java-WebSocket` | `1.5.7` / `1.6.0` | MIT | `org.slf4j:slf4j-api`（compile，`2.0.6` / `2.0.13`） | **❌ 与本项目禁引冲突**（§2.2，本席独立取证） |
| B2 `org.nanohttpd:nanohttpd-websocket` | `2.3.1` | （以该构件 POM 为准，本轮未取证） | `org.nanohttpd:nanohttpd`（compile） | 产出方取证（引用，§1.3） |
| B3 `io.javalin:javalin` | `7.2.3` | （本轮未取证） | 29 个传递件 | 产出方取证（引用，§1.3） |

> **A′ 不需要在 §7.2.2 登记依赖行**（新增依赖 = 无）；`java/pom.xml` 维持 **2 个 compile 依赖**（`protobuf-java` 3.21.12、`gson` 2.10.1）+ **test** JUnit 5；DM JDBC 走 `profile(dm)`。

### 1.3 替代方案

| # | 方案 | 结论 | 理由（关键事实） |
|---|------|------|------------------|
| **A** | JDK 内置 `HttpServer` **协议升级** + 自实现 RFC 6455 | **不可实现，已订正为 A′** | `HttpExchange` / `HttpServer` 公开 API **无** `upgrade` / `hijack` / 裸套接字接管；`HttpExchange` 只能经 `sendResponseHeaders()` + `getResponseBody()` 走 **HTTP 响应体（含分块编码）**，无法把连接降级为裸字节流。**本席独立复跑取证见 §2.1** |
| **A′** | **独立 `ServerSocket` 监听 `hg.web.ws.port` + 手工 Upgrade 握手 + 自实现 RFC 6455** | ✅ **采用** | 零新增依赖；与 `CFG-32` 已预留的**独立端口**天然吻合；分层（`web` = `J1`）、依赖方向、`TradingBridge` 边界均不变；无需任何架构分层改动 |
| **B1** | `Java-WebSocket` 1.5.7 / 1.6.0 | ❌ **不可用** | `slf4j-api` 是其**真实 compile 传递依赖**且**运行期字节码必需**（`WebSocketImpl` 构造器即 `LoggerFactory.getLogger(...)`），而 §7.2.2 明令**不引入 SLF4J**。**本席独立取证见 §2.2**。若确要引库，唯一合规路径 = 先走 `CH-xxx` 放宽 SLF4J 禁令 |
| **B2** | `nanohttpd-websocket` 2.3.1 | ⚪ **形式合规但不推荐** | **形式合规**：它是**唯一不引 `slf4j` 的候选**（`NanoWSD` 用 `java.util.logging`，`OpCode` 含 `Ping`/`Pong`）。但① `lastUpdated=20160812`（**9 年无发布**）；② `NanoHTTPD` **每连接一线程**阻塞流模型，与 `T-040`「多客户端 / 背压 / 限流」相悖；③ 无 subprotocol / 压缩协商。**注：依赖树与更新状态为产出方实测，本席未独立取证** |
| **B3** | Javalin 7.2.3（内嵌 Jetty 12 WebSocket） | ❌ **否决** | ① 底层需 **JDK 17**（`<compiler.release>17</compiler.release>`）→ 与本项目 **JDK 11** 硬约束冲突；② **29 个传递件**（Jetty 12 + EE10 + CDI + ASM + `kotlin-stdlib`），违反最小依赖。**注：同为产出方实测，本席未独立取证** |
| **B4** | Jetty 直用 `websocket-server:9.4.58` | ❌ **否决** | 12 个传递件（含用不到的 servlet 栈）；9.4 系已 EOL。**注：产出方实测** |
| **C** | **SSE**（`text/event-stream`，复用已实现的 `HttpServer`） | ⚪ **备选登记，本期不采用** | 零依赖、开发量更低、原生断线重连 + `Last-Event-ID`；但**须走 `CH-xxx`** 改 §4.1.2 端点载体、`CFG-32` 键名（`hg.web.ws.*` → `hg.web.sse.*`）与前端契约 → 属**实质变更**，本期不做 |

> **B1 的否决是本决策的关键**：它把"引库"从"成本权衡"变成了"**合规红线**"问题。
> 因此 A′ 不是"在没有更好选择时的兜底"，而是**在给定约束下唯一可行且合规的方案**。

### 1.4 不引入的代价（即选择 A′ 的代价）

| 代价项 | 具体内容 | 兜底手段 |
|--------|----------|----------|
| **一次性开发量** | 握手（`Sec-WebSocket-Key` → `SHA-1` → `Base64` 得 `Sec-WebSocket-Accept`）、帧编解码（掩码 / 长度三档 / 分片 / 控制帧规则 / UTF-8 校验）、`ping`/`pong`、关闭握手 ≈ **300 行** | 一次性投入，逻辑**完全可离线单测**（与现有测试同风格，`NFR-TEST-2`） |
| **自研正确性风险** | 帧解析边界（长度溢出、非法 opcode、掩码缺失、控制帧超 125 字节、超大帧）——即 `RSK-06` 的**残余风险** | RFC 6455 **官方向量** + 边界用例 + **接收侧强制掩码 / 发送侧不掩码** 的双向单测；载荷上限 **1 MiB** 防内存耗尽 |
| **放弃的库能力** | 分片、`permessage-deflate` 压缩协商、subprotocol 协商 | **本系统不需要**：两条**服务端单向**通道、**无客户端业务消息**（仅订阅/心跳）、无二进制大帧 |
| **维护责任自担** | 无上游 CVE / 兼容性公告 | 依赖面为零，**无供应链风险**；等价于把"暴露面"换成"自持代码" |

**代价可控的支撑事实**：需求面窄（上表）+ 可在无网络、无 DB 的环境下**纯离线单测**全部帧/握手逻辑。

---

## 2. 独立验证记录（本席取证，不采信产出方自评）

> 纪律：本席对**承载推荐的两条关键事实**做了**一手取证**；其余候选的实测数据属**产出方取证**，已在上文逐项标注引用来源，**未冒充为本席结论**。

### 2.1 `HttpExchange` / `HttpServer` 无协议升级能力 —— ✅ 本席独立取证

```
$ java -version
openjdk version "11.0.32" 2026-07-21 ; javac 11.0.32

$ javap com.sun.net.httpserver.HttpExchange        # 17 项成员，逐一列出于下
  getRequestHeaders getResponseHeaders getRequestURI getRequestMethod getHttpContext
  close getRequestBody getResponseBody sendResponseHeaders(int,long)
  getRemoteAddress getResponseCode getLocalAddress getProtocol
  getAttribute setAttribute setStreams getPrincipal

$ javap com.sun.net.httpserver.HttpServer
  create create(InetSocketAddress,int) bind start setExecutor getExecutor stop
  createContext(x2) removeContext(x2) getAddress

$ javap com.sun.net.httpserver.HttpExchange com.sun.net.httpserver.HttpServer \
    | grep -cE 'upgrade|hijack|detach|getConnection'
0
```

**结论**：`HttpExchange` 只能经 `sendResponseHeaders()` + `getResponseBody()` 走 **HTTP 响应体**；
`getProtocol()` 仅返回协议串，`getRemoteAddress()` / `getLocalAddress()` 仅返回 `InetSocketAddress` **元信息**；
**不存在**把连接降级为裸字节流的公开途径。⇒ 派发描述中的「方案 A（`HttpServer` 协议升级）」**不可实现**，
**A′ 的订正成立**（该订正与 `ARCHITECTURE.md` v1.5.3 §7.1.7 / §7.1.11 现行文本一致）。

### 2.2 `Java-WebSocket` 的真实传递依赖 —— ✅ 本席独立取证（一手取 POM，非引用）

直接读取 Maven Central 上该构件的 `.pom`（构件元数据的权威来源）：

```
https://repo.maven.apache.org/maven2/org/java-websocket/Java-WebSocket/1.6.0/Java-WebSocket-1.6.0.pom
https://repo.maven.apache.org/maven2/org/java-websocket/Java-WebSocket/1.5.7/Java-WebSocket-1.5.7.pom
```

两版本的 `<dependencies>`（**非** `<dependencyManagement>`）段**均显式声明**：

| 构件 | 版本 | `<dependencies>` 中声明的依赖 | scope | 许可 |
|------|------|------------------------------|-------|------|
| `Java-WebSocket` | **1.6.0** | `org.slf4j:slf4j-api`（版本属性 `slf4j.version` = `2.0.13`） | `compile` | MIT |
| `Java-WebSocket` | **1.5.7** | `org.slf4j:slf4j-api`（`slf4j.version` = `2.0.6`） | `compile` | MIT |

**结论**：`slf4j-api` 是**真实 compile 传递依赖**，**派发描述中"单 jar 且零强制传递依赖"与本席取证不符**；
其与 §7.2.2「日志框架 ❌ 不引入（不用 SLF4J/Logback）」**直接冲突** ⇒ **B1 不可引**。
（另：该库编译目标 `release 7`、Multi-Release jar，**JDK 11 可用性不是问题**——否决理由**仅**为 SLF4J 依赖。）

> ✅ **否决理由的准确表述（`#java` 隔离探针实测，本席采纳并据 POM 复核）**：
> 否决 B1 的**唯一**理由是「`slf4j-api` 是**真实 compile 传递依赖且字节码必需**」，**不是**"依赖树干净但运行期炸"。
> - **树可见**：隔离探针（仅声明该库）`mvn -B dependency:tree` **如实报出** —— `1.6.0 → org.slf4j:slf4j-api:jar:2.0.13:compile`；`1.5.7 → org.slf4j:slf4j-api:jar:2.0.6:compile`（本席另据 POM 的 `<dependencies>` 段复核，二者一致）。
> - **字节码必需**：该库 **8 个 class** 引用 `org/slf4j`（`javap -c WebSocketImpl` 显示字段 `private final org.slf4j.Logger log` 经 `LoggerFactory.getLogger(Class)` 初始化），且 `java/util/logging` **0 命中**（**无 JUL 回退**）⇒ 排除 `slf4j-api` 不可行。
> - ⚠️ **一条已撤回的说法（留痕，防再次引用）**：曾有一版判定称「`slf4j-api` 藏在 `<dependencyManagement>`，故 `dependency:tree` 报 0 传递依赖、运行期才炸」。
>   该说法**不成立**：`slf4j-api` 在 POM 中**同时**出现于 `<dependencyManagement>` **与** `<dependencies>`，`dependency:tree` **会**报出它。
>   ⇒ 「树与运行期背离」这一现象**在本例不存在**；本文件的否决理由已按上表口径表述。

### 2.3 现状核对（只读观测，用于判断决策是否已落地）

| 观测项 | 实测值 |
|--------|--------|
| `docs/ARCHITECTURE.md` | **v1.5.3**，md5 **`02e0316dd249f6f7282f37974737564c`**，2586 行（§7.1.11 已**定调 A′**；§9 `RSK-06` 已登记「引库路径已事实关闭」；`CFG-32` 已填值） |
| `java/CONTRACTS.md` | md5 **`9ea19933c47af497aa51b0368cc05359`**，714 行（§4.2 选型论证 / §4.3 实现细节冻结） |
| `java/SELECTION_HTTP_WS.md` | md5 **`4f580b42a773c5a4eaa257f6ce0c71ff`**（证据附录，非独立结论） |
| `java/pom.xml` | md5 **`26920ad85c07cf8aa35b0e6df59504a3`**；compile 依赖仍为 **2 个**（protobuf-java、gson）⇒ **本轮选型未新增任何依赖** |
| A′ 实现存在性 | `java/src/main/java/com/hedgeguard/web/ws/` 已存在 10 个类（`WsServer` / `WsHandshake` / `WsFrameCodec` / `WsConnection` / `WsSubscription` / `WsAuthorizer` / `WsMessages` / `WsCloseCode` / `WsFrame` / `WsProtocolException`，`WsServer.java` md5 `480862879811d27607a244ba0d81ee48`）+ 6 个测试类 |
| A′ 实现测试记录 | `java/target/surefire-reports/` 中 5 个 WS 套件共 **63 用例**，**4 个 error**（`WsAuthorizerTest` 2、`WsServerTest` 2），错误信息均为 `NoClassDefFoundError: com/hedgeguard/web/ws/WsAuthorizer$PermDaoGrantReader` / `WsServer$Stats`（**嵌套类缺失**，典型"测试类相对主类陈旧编译"症状） |

> ⚠️ **上述 surefire 记录不构成"通过"证据**：4 个 `NoClassDefFoundError` 指向**构建陈旧**而非逻辑缺陷，但**必须以一次 clean 全量构建的绿色结果重取证据**，方可作为 `T-028` 的验收凭据（见 §3 判据 E1）。
> 本席**不**据此判定实现有缺陷，也**不**采信"已交付即已通过"。

---

## 3. `T-028` 可判定验收判据（推荐方案 A′ 下）

> 每条判据给出**阈值 / 判定方法 / 证据形式**；凡涉及数值者均为**实测值须留档**，不得以"设计应满足"代替。

| # | 判据 | 阈值 / 期望 | 判定方法 | 证据形式 |
|---|------|-------------|----------|----------|
| **E1** | **握手与连通性** | 浏览器（或等价 RFC 6455 客户端）可对 `/ws/quote` 升级成功；`Sec-WebSocket-Accept` 与 RFC 6455 §1.3 官方向量**逐字节一致** | 官方向量单测 + 一次真实浏览器连通 | 单测输出 + 浏览器截图/日志 |
| **E2** | **并发连接下 P95 推送时延** | 在 **N ≥ 32** 并发连接、单合约 **≤ 5 条/秒** 节流下，`publish → 客户端收到` 的 **P95 ≤ 200 ms**（`NFR-PERF-5` 端到端口径，**不破坏**该指标）；同时记录 P50 / P99 与样本量 | 压测脚本以消息 `tsMillis` 差值计；**须记录 N、样本量、节流配置** | 压测报告（含 N / 样本量 / P50 / P95 / P99） |
| **E3** | **断连重连 + 重订阅成功率** | **100%**（N 次强制断连 → 重连 → 重新订阅后，**再次收到该合约行情**的比例；重连后未订阅**不得**收到任何推送） | 断开 TCP → 重连 → 重发 `subscribe`；分别断言"重订阅前无推送、重订阅后有推送" | 自动化用例 + 运行日志 |
| **E4** | **心跳间隔 ≥ 1000 ms** | 实测 `ping` 间隔 **≥ 1000 ms**（配置 `hg.web.ws.heartbeat_ms`，默认 `30000`）；间隔 < 1000 的配置在**构造期即拒绝**；连续 **3** 次无 `pong` → 关闭连接并清理资源 | 抓取控制帧时间戳；含"配置 < 1000 → 抛错"负例 | 用例 + 控制帧抓包/日志 |
| **E5** | **订阅越权拒绝（安全必做，`W-2`）** | 申请**无授权子账户**的数据 → **拒绝** + **留痕**（`10004`）+ `close(1008)`；**安全默认 = 空集**（授权集为空 ⇒ 恒不下发）；`CROSS_READONLY` 仅得只读告警 | 构造无授权 / 跨子账户订阅用例；断言"零下发"而非仅"报错" | 用例 + 审计留痕样例 |
| **E6** | **背压弃帧可观测且不背压** | 每连接有界队列满时 `/ws/quote` **丢弃最旧行情并计数**（丢弃计数可读：日志/指标）；**事件消费线程不被阻塞**（不背压 `EVT_MARKET_QUOTE`）；`/ws/alert` 满则 `close(1013)` 而非静默丢 | 注入慢客户端（不读 socket）+ 高频推送，断言"消费线程推进不受影响 + 丢弃计数增长" | 用例 + 丢弃计数读数 |
| **E7** | **鉴权复用 `HGSESSION` Cookie 与子账户上下文** | 握手期复用既有会话设施（`HGSESSION`（HttpOnly）/ `X-HG-Session`），**不新增鉴权通道**；缺失 / 失效 → **HTTP 401（不升级）**；会话解析结果驱动订阅过滤，**越权判定只依据服务端解析结果**，不信任客户端自报的 `subAccounts` | 无 Cookie / 过期会话 / 伪造 `subAccounts` 三类负例 | 用例 + 401 响应证据 |
| **E8** | **覆盖式 + 可丢不补数** | 同合约**只推最新值**（旧值被覆盖，不排队）；`/ws/quote` **无补数、无回执**；`/ws/alert` 的"不丢"由前端重连补拉 REST 兑现，**WS 层不追加缓存或用例** | 高频同合约推送断言"收到的 `ts` 单调且无历史堆积" | 用例 + 帧序列证据 |
| **E9** | **与 `CH-007` 消费侧规则一致** | `IS_RESUME_DATA` 补数批次中的 `EVT_MARKET_QUOTE` **接收侧不消费、不推前端**；该批次**不得**计入行情新鲜度 | 注入含行情帧的补数批次，断言"WS 无对应下发" | 用例 + 下发日志 |
| **E10** | **仅监听 `127.0.0.1`** | 绑定地址白名单**仅** `127.0.0.1`；配置为非回环 → **拒绝启动并告警**（`NFR-SEC-3` / `FR-CONFIG-9`） | 启动期负例 | 用例 + 启动失败日志 |
| **E11** | **敏感字段零落盘、零日志** | 会话 ID / Cookie 全值 / 口令类字段**不落盘、不入日志**；日志经既有脱敏设施 | 代码审查 + 日志扫描用例 | 扫描用例输出 |
| **E12** | **前端零构建链** | 前端使用**原生 `WebSocket` API**（浏览器内置）；**不得**出现 `package.json` / `node_modules` / 打包产物（`NFR-MAINT-1`） | 仓库扫描 | 扫描结果 |
| **E13** | **依赖为零 + 禁引清单复核** | `mvn dependency:tree` 中 **compile 依赖仍为 JDK 11 / DM JDBC / protobuf-java / Gson**；**不得**出现 `HikariCP` / `SLF4J` / `Logback` / `Log4j` / `BCrypt` / `Mockito` | `dependency:tree` + 禁引名单关键词扫描 | 依赖树输出 + 扫描结果 |

> **判据与既有冻结点的一致性**：E4 / E5 / E10 / E13 与 `ARCHITECTURE.md` §11.2 `CFG-32`（心跳 **≥ 1000 ms**、连接数 **≥ 1**、bind 仅回环）逐条对齐；
> E2 与 `NFR-PERF-5` 对齐；E5 与 `NFR-SEC-7` / `10004` 对齐；E9 与 `CH-007` 对齐。
> **不达标时的处置**：若 E2（或 A′ 最小可行性）不达标 → 回退 **1 s 长轮询**并**提 `CH-xxx` 降级声明 `NFR-PERF-5`**（该回退会破坏 P95 ≤ 200 ms，见 §7.1.7 / §9 `RSK-06`）。

---

## 4. 与既有约束的一致性核对

| 约束 | 来源 | A′ 下的落实 | 结论 |
|------|------|-------------|------|
| **尽量降低第三方库依赖** | `PROJECT.md` §5.1 `CH-006` | 新增依赖 = **0**；`pom.xml` compile 依赖保持 2 个 | ✅ 一致（**本方案的核心理由**） |
| **HTTP / WebSocket 一律在 Java 端** | `CH-006` | WS 服务端在 Java 业务组件；C++ 不提供 HTTP/WS | ✅ 一致 |
| **不得引入被禁依赖**（`HikariCP`/`SLF4J`/`Logback`/`Log4j`/`BCrypt`/`Mockito`） | 派发约束 + §7.2.2 | A′ 零依赖，**天然合规**；B1 正因 `slf4j-api` 被排除 | ✅ 一致 |
| **仅监听 `127.0.0.1`** | `NFR-SEC-3` / `FR-CONFIG-9` | 独立 `ServerSocket` 绑定回环；非回环拒绝启动 | ✅ 一致（判据 E10） |
| **敏感字段零落盘、零日志** | `NFR-SEC-1` | 复用既有会话设施与脱敏日志；WS 层不新增持久化 | ✅ 一致（判据 E11） |
| **前端零构建链** | `NFR-MAINT-1` | 前端用**原生 `WebSocket`**，无 npm / 打包 | ✅ 一致（判据 E12） |
| **协议冻结不受影响** | §4.2 `FROZEN`（`CH-007`） | A′ 仅涉前端 ↔ Java 的 WS 载体，**不触** HGCP 实质范围（40 消息 / 62 错误码 / `VER 0x0100` 不变） | ✅ 一致 |
| **`CFG-32` 已预留独立 WS 端口** | §11.2 | A′ 的"独立监听"形态与该设计**天然吻合**，**无需改分层** | ✅ 一致 |

**结论**：A′ 与全部既有约束**零冲突**；B1 与"禁引 SLF4J"**硬冲突**；C（SSE）需 `CH-xxx` 方可落地。

### 4.1 依赖准入判据（新增；建议登记于架构 §7.2.2 依赖纪律）

任何**新引入依赖**（**不限于 WebSocket**，适用于 Java 侧全部依赖）在准入前必须**同时**通过两道关：

| 关 | 判据 | 证据形式 | 目的 |
|---|------|----------|------|
| **①** | **传递依赖清单** | `mvn dependency:tree` 全量输出 + 逐条说明「名称 / 版本 / 许可 / 是否含原生依赖」，并与 §7.2.2 禁引名单（`HikariCP` / `SLF4J` / `Logback` / `Log4j` / `BCrypt` / `Mockito`）比对 | 阻断"隐式带上禁引依赖"与依赖膨胀 |
| **②** | **冒烟实例化证据** | 在**隔离探针**（仅声明该依赖）中**实际实例化**目标类，留运行输出 | 防 **`provided` / `optional` / 反射 / `ServiceLoader`** 这类**确实不出现在依赖树里**的运行期缺失 |

> ⚠️ **关②的理由必须表述准确**：它防的是「**依赖树看不见的运行期需求**」（`provided` / `optional` / 反射 / `ServiceLoader`），
> **不是**「防 `<dependencyManagement>` 隐藏依赖」——后者是**错误**的：位于 `<dependencyManagement>` **不构成**依赖；
> 而位于 `<dependencies>` 的依赖**一定**会出现在 `dependency:tree` 中（详见 §2.2 的撤回留痕）。
> 📌 本判据建议由技术负责人登记入 **`ARCHITECTURE.md` §7.2.2 依赖清单的「纪律」段**（**本轮不落笔**，见 §5.1 ④）。

---

## 5. 建议的后续架构改动清单（**本席不改**，供另派技术负责人落笔）

> 边界：本席**不得**修改 `docs/ARCHITECTURE.md` / `docs/TASKS.md` / `docs/BOARD.md` / `docs/DATA_MODEL.md` / `PROJECT.md` / `docs/REQUIREMENTS/` / `docs/gates/` / `java` / `cpp`。
> 与 `tech-lead-13` 的 `ARCHITECTURE.md` 写域**串行**：本轮本席**只写本文件**，架构正文的落地由编排者另派。

### 5.1 派发要求登记的四项（含现状核对）

| # | 目标章节 | 派发要求的建议改动 | 现状（**v1.5.3 实测**，md5 `02e0316dd249f6f7282f37974737564c`） | 处置 |
|---|----------|--------------------|------------------------------------------------------------|------|
| **①** | §7.1.11 选项 A 措辞 | 改为 **A′**（独立 `ServerSocket` + 手工 Upgrade 握手 + RFC 6455） | ✅ **已落笔**：标题已为「已定调 A′」；候选列 A′；附 v1.5.3 勘误块（含 `javap` 复跑命令） | **无需再改，仅需复核** |
| **②** | §9 `RSK-06` | 处置列补「**已定调 A′**」 | ✅ **已落笔**：处置列已写「已定调：采用 §7.1.11 的 A′」+「**引库路径已事实关闭**」+ 回退长轮询口径 | **无需再改，仅需复核** |
| **③** | §11.2 `CFG-32` 填值 | `hg.web.ws.port` / `hg.web.ws.heartbeat_ms`（**≥ 1000 ms**）/ `hg.web.ws.max_connections` | ⚠️ **部分落笔**：`heartbeat_ms` = `30000` ✅、`max_connections` = `200` ✅、下限「心跳 **≥ 1000 ms**」✅；**`hg.web.ws.port` 仍为"部署时填写"、无默认值** ❌ | 仅 **port 默认值缺口** → 见 §5.2 `A-2` |
| **④** | §7.2.2 依赖清单「纪律」段 | 新增**依赖准入判据**：① 传递依赖清单 ② **冒烟实例化证据**（理由：防 `provided` / `optional` / 反射 / `ServiceLoader`） | ❌ **未落笔** | 判据正文见 **§4.1**；待编排者另派后落笔 |

### 5.2 残余缺口（非选型本身）

| # | 目标章节 | 建议改动 | 优先级 | 理由 |
|---|----------|----------|--------|------|
| **A-1** | `docs/ARCHITECTURE.md` §4.1.2 | 在"前端 ↔ Java（WebSocket）"表中补一行**客户端→服务端**约定，采纳 `java/CONTRACTS.md` §4.1.2 的 `W-1` 提案值：`{"type":"subscribe","channels":["quote","alert"],"instruments":[…],"subAccounts":[…]}` / `{"type":"unsubscribe",…}`，服务端回 `{"type":"subscribed","channels":[…],"ts":…}` | **高** | §4.1.2 只定义了**服务端→客户端**消息；`W-1`（订阅消息格式）**至今在架构正文未定义**（`CONTRACTS.md` 仍标"待批"）。`T-040` 验收第 3 条要求"与冻结清单逐字段一致"，**当前无冻结清单可依** |
| **A-2** | `docs/ARCHITECTURE.md` **§11.2 `CFG-32`** | `hg.web.ws.port` 现值"部署时填写"（无默认）。建议按既定规则「**登记值取实测实现值，编排者不填数**」由实现方给出实际默认端口后回填（并确保与 `CFG-33 hg.web.port=27402`、`CFG-20 hg.tcp.port=27400` **分离**） | 中 | 部署配置缺默认值；一致性检查（端口分离）需要具体值才可校验 |
| **A-3** | `docs/ARCHITECTURE.md` **§7.1.11** | 追加一句：A′ 的**形态约束已成为 `T-040` 的实现基线**——独立 `ServerSocket` 监听 / 每连接**有界出站队列** / 行情丢最旧·告警 `1013` / **原生 `ping`-`pong`**；并注明 `T-040` **不得重新选型** | 中 | 防止 `T-040` 实施者二次选型或改动已冻结形态（本决策第 6 节边界） |
| **A-4** | `docs/ARCHITECTURE.md` **§4.1.2**（或 §6.13） | 明确 **`CH-007` 规则对 WS 层的适用**：补数批次中的行情帧在接收侧不消费，故**不得**触发任何 WS 下发；建议与 §4.2.6 交叉引用 | 中 | 本席已在判据 E9 列为验收项，但架构正文未把该规则显式连到 WS 下发路径 |
| **A-5** | `java/CONTRACTS.md` §4.2（**非架构正文**，属 `#java` 制品） | 该节"纪律"段仍写"本节给出推荐 + 证据，**未**声称已定。请技术负责人 / 编排者裁决" —— 与 v1.5.3 **已定调 A′** 存在**时态不一致**。建议由 `#java` 改为"**已定调 A′（架构 v1.5.3）**" | 低 | 跨制品时态一致性；避免后来者误判"选型仍未定"（本席**不代改**他人制品） |

**明确"无需改动"项（避免误改）**：
- §7.1.7（已含 A′ 与"`HttpServer` 不提供 upgrade / hijack"的订正）、§7.1.8、§7.2.1、**§7.2.2 依赖表行**（已含"WebSocket 轻量库 ❌ 不引入"）、§9 `RSK-06`（已定调 A′）、`CFG-33`（已登记）、§12 `DEV-3` —— **均已由 v1.5.3 落笔，本轮复核一致，无需再动**。
- ⚠️ 注意区分：**§7.2.2 的「依赖表行」已落笔**；而**同一节的「纪律」段新增依赖准入判据尚未落笔**（见 §5.1 ④ 与 §4.1）。

---

## 6. 边界声明与留痕

1. 本文件**不修改**任何受保护制品（`docs/ARCHITECTURE.md`、`docs/TASKS.md`、`docs/BOARD.md`、`docs/DATA_MODEL.md`、`PROJECT.md`、`docs/REQUIREMENTS/`、`docs/gates/`、`java/`、`cpp/`），**仅新增**本文件。
2. 本文件**未**向 `java/pom.xml` 新增任何依赖（实测 md5 `26920ad85c07cf8aa35b0e6df59504a3`，compile 依赖仍为 2 个）。
3. 本决策的**定案锚点**为 `ARCHITECTURE.md` **v1.5.3**（md5 `02e0316dd249f6f7282f37974737564c` / 2586 行）。
   ⚠️ **引用前请重测 md5**：该文件在 2026-09-20 内已多次漂移（v1.4 → v1.5 → v1.5.1 → v1.5.2 → v1.5.3）。
4. **取证归属**：§2.1 / §2.2 为本席一手取证；B2 / B3 / B4 / B1 的字节码与运行期复现为**产出方取证**（`java/CONTRACTS.md` §4.2、`java/SELECTION_HTTP_WS.md`），本席已注明，**未冒充**。
5. 本席观察到 `java/target/surefire-reports/` 中 WS 套件存在 **4 个 `NoClassDefFoundError`（嵌套类缺失）**，判定为**构建陈旧**症状；**证据须以一次 clean 全量构建的绿色结果重取**（判据 E1）。本席**不**据此判定实现缺陷。
6. **裁定采纳留痕**：本文件据编排者 2026-09-20 的两次收口指令定稿 —— ① 选型**采纳 `#java` 实证**（A′；SSE 备选登记；引库路径事实关闭）；② 否决 B1 的**理由已按 `#java` 隔离探针更正**为「**真实 compile 传递依赖且字节码必需**」，并**撤回**「`dependency:tree` 报 0 传递依赖 / 运行期才炸」的旧表述（留痕见 §2.2）；③ `nanohttpd-websocket 2.3.1` 如实登记为「**形式合规但不推荐**」（§1.3 B2）；④ **依赖准入判据**（传递依赖清单 + **冒烟实例化证据**）新增于 §4.1，建议登记入架构 §7.2.2「纪律」段（§5.1 ④）。
