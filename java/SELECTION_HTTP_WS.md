# HTTP / WebSocket 承载选型 —— **证据附录**（M1 输入件）

> **定位**：本文件是 **`java/CONTRACTS.md` §4.2「HTTP / WebSocket 选型论证」的证据附录**，**不是独立结论**。
> **结论与候选依赖树表以 `CONTRACTS.md` §4.2 为准**（该节由同名实例撰写，含 `nanohttpd-websocket` / Jetty 9.4.58 / Javalin 7.2.3 的逐项实测）。
> 本文件只补三件 §4.2 未覆盖的内容：① **一处技术更正（A → A'，可用 `javap` 复现）**；② **B1 否决的运行期复现**（§4.2 用的是静态字节码分析）；③ **补充候选 SSE**；以及全部命令的可重跑清单。
>
> **关联**：`CH-006`（HTTP/WebSocket 归 Java）、`RSK-06`、`T-028`（WS 选型 + 最小可行实现）、`T-040`（生产级 WS 服务端，归属 `#java`）
> **依据锚点**：`ARCHITECTURE.md` **v1.5.2**（md5 `32babcacd031af19d7009c8e1cb9befd` / 2546 行）—— **引用前请重测 md5**（本会话内该文件已漂移 3 次：v1.3 → v1.5 → v1.5.2）。
> **本轮未新增任何依赖**：`java/pom.xml` 维持 `protobuf-java` + `gson`（compile）+ JUnit 5（test）；即 §2.5⑤「新增依赖清单 = 无」是**当前事实**，不是待办。
>
> ⚠️ **`§7.1.11` 选项 A 的措辞截至 v1.5.2 仍未更正**（实测该行仍为「JDK 内置 HttpServer **协议升级**」）；
> 本文件 §1 的 A→A' 更正**尚未被技术负责人落笔**，故 `T-028` 开工前必须由技术负责人确认（`W-7`）。

---

## 1. 技术更正：`§7.1.11` 选项 A 措辞不可实现 → 采用 **A'**

`§7.1.11` 选项 A 写作「JDK 内置 `HttpServer` **协议升级** + 自实现 RFC 6455」，`CONTRACTS.md` §4.2 沿用了该措辞。**实测不可实现**：

```text
$ javap com.sun.net.httpserver.HttpExchange      # 全部公开方法
  getRequestHeaders()  getResponseHeaders()  getRequestURI()  getRequestMethod()  getHttpContext()
  close()  getRequestBody()  getResponseBody()  sendResponseHeaders(int, long)  getRemoteAddress()
  getResponseCode()  getLocalAddress()  getProtocol()  getAttribute(String)  setAttribute(String,Object)
  setStreams(InputStream, OutputStream)  getPrincipal()

$ javap com.sun.net.httpserver.HttpServer        # 全部公开方法
  create()  create(InetSocketAddress,int)  bind(InetSocketAddress,int)  start()  setExecutor(Executor)
  getExecutor()  stop(int)  createContext(...)  removeContext(...)  getAddress()
```

**无 `upgrade` / `hijack` / 裸套接字访问**。`HttpExchange` 只能经 `sendResponseHeaders()` + `getResponseBody()`
走 **HTTP 响应体（含分块编码）**，**无法把连接降级为裸字节流**交给 WebSocket 帧读写
（`getProtocol()` 仅返回请求协议串，不提供接管能力）。

⇒ **可行形态 = A'**：**独立 `ServerSocket` 监听 `CFG-32` 的 `hg.web.ws.port`**，在该端口手工完成
HTTP Upgrade 握手 + RFC 6455 帧收发。

**为何这不需要架构变更**：`§11.2 CFG-32` **本就预留了独立 WS 端口**（`hg.web.ws.port`），
与"独立监听"形态天然吻合；分层（`web` = J1）、依赖方向、`TradingBridge` 边界**均不变**。
**唯一需改的是 `§7.1.11` 那句措辞**（`T-028` 的开工输入）。

---

## 2. B1（`Java-WebSocket`）否决的**运行期**复现

`CONTRACTS.md` §4.2 已用静态分析（`javap -c` + `grep -rl "org/slf4j"` → 8 类命中；`java/util/logging` → 0 命中）证明其**无 JUL 回退**。另补**运行期实测**：

```text
$ java -cp .:Java-WebSocket-1.6.0.jar WsProbe2        # 子类化 WebSocketServer 并实例化
FAILED: java.lang.NoClassDefFoundError / org/slf4j/LoggerFactory
root cause: java.lang.ClassNotFoundException / org/slf4j/LoggerFactory

$ java -cp .:Java-WebSocket-1.6.0.jar:slf4j-api-1.7.36.jar WsProbe2
instantiated OK
（stderr）SLF4J: Defaulting to no-operation (NOP) logger implementation
```

### 2.1 ❌ 「陷阱结论」**已撤回**（`backend-engineer#java` 独立复核推翻，2026-09-20 20:1x）

> **原判定（本节前一版，现已撤回）**：『`mvn dependency:tree` 报"0 传递依赖" ≠ 运行期可用；
> `slf4j-api` 声明位于 pom 的 `<dependencyManagement>`（**不构成传递依赖**）』。
>
> **撤回理由（本席独立复现，两次、版本覆盖 1.5.7 与 1.6.0）**：
> ```text
> $ # 隔离探针：仅声明 Java-WebSocket:1.6.0（/tmp/wsprobe16/pom.xml）
> $ mvn -B dependency:tree
> [INFO] \- org.java-websocket:Java-WebSocket:jar:1.6.0:compile
> [INFO]    \- org.slf4j:slf4j-api:jar:2.0.13:compile        ← ★ 传递依赖**在树里可见**
>
> $ # 同样对 1.5.7（/tmp/wsprobe/pom.xml）
> [INFO] +- org.java-websocket:Java-WebSocket:jar:1.5.7:compile
> [INFO] |  \- org.slf4j:slf4j-api:jar:2.0.6:compile          ← ★ 同样可见
>
> $ # pom 结构：slf4j-api 同时出现在 dependencyManagement（行~51）**与** dependencies（行~290）
> $ awk '…slf4j-api 上下文…' Java-WebSocket-1.6.0.pom
>   行50: <artifactId>slf4j-api</artifactId>  | 上下文: dependencyManagement=1 dependencies=
>   行283: <artifactId>slf4j-api</artifactId> | 上下文: dependencyManagement=0 dependencies=1   ← ★ 真实依赖
> ```
> ⇒ **`dependency:tree` 对 `Java-WebSocket` 恰恰是"有效"的**：它**如实报出** `slf4j-api` 为 compile 传递依赖。
> 故「依赖树干净但运行期炸」这一**现象在本例并不存在**，原判定的**前提不成立**，结论随之撤回。
> **本席首轮也曾被 `pom` 中被 `grep` 截断上下文而误导**（只看到 `<dependencyManagement>` 那一处），
> 二次核对行号上下文与隔离探针输出后才定案 —— 这本身就是"**不可只看一次 grep**"的实例。
>
> **仍然成立且值得保留的部分**：本文件 §2 的**运行期复现**（`NoClassDefFoundError: org/slf4j/LoggerFactory`）
> 与 `dependency:tree` 的结论**完全一致**（二者都说"需要 slf4j"），因此**不构成**"树与运行期背离"的证据；
> 它的价值在于把"字节码要求"提升为"可执行的失败复现"。**"加一次冒烟实例化"作为加固手段仍可采纳**，
> 但**理由应改为**："防 `provided` / `optional` / 反射 / `ServiceLoader` / 运行期 API 缺失"这类
> **确实不会出现在依赖树里**的情形 —— 而**不是**本例（本例树里就有）。

**结论（不变）**：`Java-WebSocket` 1.5.7 / 1.6.0 均**不可引入** —— `slf4j-api` 是其**真实 compile 传递依赖**
（`dependency:tree` 可见、pom `dependencies` 段可见、运行期必需），与 `§7.2.2`「**不引入 SLF4J/Logback**」直接冲突。
**修正归属**：本节 §2 的**运行期复现**由同名第二实例提供（保留）；**§2.1 的判定撤回与上列证据**由
`backend-engineer#java`（本席）追加。

---

## 3. 补充候选：**C = SSE（Server-Sent Events）** —— 零依赖，**但须 `CH-xxx`**

| 维度 | 评估 |
|------|------|
| 与需求匹配度 | `§4.1.2` 两路端点**均为服务端→客户端单向** → **正是 SSE 的语义**（SSE 不支持客户端→服务端业务消息，而本系统也不需要，见 `W-1` 仅订阅/心跳） |
| 依赖 | **0** —— 复用**已实现并已验证**的 `com.sun.net.httpserver`（`Content-Type: text/event-stream` + `sendResponseHeaders(200, -1)` 分块流） |
| 断线重连 | SSE **原生**支持自动重连 + `Last-Event-ID`，恰好对上 `/ws/alert`「断线后重连补拉」；补拉亦可继续走 `/risk/alert?since=` |
| 开发量 / 风险 | **低于 A'**（无需手写握手/掩码/控制帧；无 `RSK-06` 的帧解析正确性风险） |
| 代价 | 须 `CH-xxx`：改 `§4.1.2`（端点与消息载体）、`§11.2 CFG-32` 键名（`hg.web.ws.*` → `hg.web.sse.*`）、**前端契约**（`EventSource` 替代 `WebSocket`）|
| 边界 | 单向（无客户端上行）；不适合二进制大帧（本系统不需要）；`/ws/quote` 的 `Last-Event-ID` **不**适用于"覆盖式最新值"语义（须由前端/服务端约定忽略补发） |

> **权衡提示（供技术负责人）**：若"**零依赖 + 最低开发量**"优先级最高，且允许变更 `§4.1.2` 字面契约 → **C 优于 A'**；
> 若必须保持 `§4.1.2` 已冻结的 WebSocket 端点与消息格式 → **选 A'**。两者**均为零新增依赖**。

---

## 4. 五要素汇总（按 `§7.1.11` 要求；与 `CONTRACTS.md` §4.2 一致）

| 要素 | HTTP | WebSocket |
|------|------|-----------|
| **① 推荐方案** | **维持 JDK 内置 `com.sun.net.httpserver`**（架构 `§7.1.11` 已定，本轮复核实证成立） | **A'：独立 `ServerSocket` 监听 `hg.web.ws.port` + 自实现 RFC 6455**（零依赖） |
| **② 版本** | JDK 11 内置（本环境 `11.0.32` 实测） | 无外部版本（自实现；对标 **RFC 6455**，握手可用其 §1.3 官方向量断言） |
| **③ 替代方案** | Javalin（**实测需 JDK 17**、29 传递件）／Jetty 直用（12 传递件、9.4 系 EOL）／NanoHTTPD（9 年无发布、每连接一线程）| **B1 `Java-WebSocket`**（**运行期实测被 SLF4J 禁令排除**）／**B2 `nanohttpd-websocket`**（形式合规、实质不匹配）／**C SSE**（零依赖，**须 `CH-xxx`**）|
| **④ 不引入的代价** | **0**（`T-003` 已端到端验证） | ≈ **300 行** + 单测；放弃分片 / `permessage-deflate` / subprotocol（**本系统不需要**）；正确性风险 = `RSK-06`，由官方测试向量 + 边界用例兜底，且逻辑可**完全离线单测** |
| **⑤ 新增依赖清单** | **无** | **无** |

**HTTP 已知边界（不构成换库理由）**：无 `multipart/form-data` 解析 → 导入接口约定为**原始 CSV/JSON 请求体**；无 HTTP/2、无 TLS（仅回环，不需要）。

---

## 5. 待裁决（与 `CONTRACTS.md` §4.1.2 `W-1`~`W-6`、§4.2 合并后的完整清单）

| # | 项 | 出处 |
|---|----|------|
| `W-1` | 客户端→服务端订阅消息格式未定义（`§4.1.2` 称"沿用既有口径"，实未定义）→ 已附提案 | §4.1.2 |
| `W-2` | **`/ws/alert` 含 `subAccount` 但未规定按 `t_user_sub_account_perm` 过滤** → **越权泄露风险**（`NFR-SEC-7`）→ 已附提案（安全默认 = 空集） | §4.1.2、`D-10` |
| `W-3`~`W-6` | 心跳机制（用 RFC 6455 原生 ping/pong）、握手拒绝与关闭码、首帧语义、`ts` 语义与格式 → 均已附提案 | §4.1.2 |
| **W-7** | **`§7.1.11` 选项 A 措辞更正**（"HttpServer 协议升级" → "独立监听 + 手工握手"） | 本文件 §1 |
| **W-8** | **A' vs C（SSE）** 的取舍（选 C 须 `CH-xxx`） | 本文件 §3 |
| **W-9** | 是否采纳"**冒烟实例化 / 字节码引用检查**"作为 `§7.2.2` 的新登记要求 | 本文件 §2.1 |

---

## 6. 复现清单（全部可重跑）

```bash
# ① A→A' 更正：HttpExchange / HttpServer 无协议升级能力
javap com.sun.net.httpserver.HttpExchange
javap com.sun.net.httpserver.HttpServer

# ② B1 否决：传递依赖 vs 运行期实需 的落差
mkdir -p /tmp/wsprobe && cd /tmp/wsprobe
cat > pom.xml <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
  <groupId>probe</groupId><artifactId>wsprobe</artifactId><version>1</version>
  <properties><maven.compiler.source>11</maven.compiler.source></properties>
  <dependencies><dependency><groupId>org.java-websocket</groupId>
    <artifactId>Java-WebSocket</artifactId><version>1.6.0</version></dependency></dependencies>
</project>
EOF
mvn -B dependency:tree                    # → 0 传递依赖（"看起来完美"）
curl -sO https://repo.maven.apache.org/maven2/org/java-websocket/Java-WebSocket/1.6.0/Java-WebSocket-1.6.0.jar
javac -cp Java-WebSocket-1.6.0.jar -d . WsProbe2.java     # 子类化 WebSocketServer（编译期不需要 slf4j）
java  -cp .:Java-WebSocket-1.6.0.jar WsProbe2             # → NoClassDefFoundError: org/slf4j/LoggerFactory
curl -sO https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar
java  -cp .:Java-WebSocket-1.6.0.jar:slf4j-api-1.7.36.jar WsProbe2   # → instantiated OK（NOP 提示）

# ③ HTTP 侧维持现状的证据（T-003 已交付）
cd java && mvn -q test && mvn -B dependency:tree
```

---

## 7. 边界声明

- 本文件**不修改** `ARCHITECTURE.md` / `PROJECT.md` / `docs/**`（除已授权的 `docs/DEPLOY/schema/**`）；正式论证由技术负责人落入 `§7.1.11` / `§7.2.2` / `§11.2`。
- 本文件**未**向 `java/pom.xml` 新增任何依赖。
- **并发写入留档**：`java/CONTRACTS.md` §4.1/§4.2 主体由**同名实例**于 `2026-09-20 20:01:12` 落笔（未获通知）；本文件的作者实例**未删除、未改写**其内容，仅追加 §4.2.1 与 §6 裁定登记，并已上报编排者。
