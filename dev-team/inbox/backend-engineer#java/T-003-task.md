---
ticket: T-003
type: task
from: project-manager
to: backend-engineer#java
priority: P0
created: 2026-09-20T22:00:00+08:00
refs: [docs/REQUIREMENTS/CONFIG.md#FR-CONFIG-1, docs/REQUIREMENTS/NONFUNCTIONAL.md, docs/ARCHITECTURE.md#3.7, docs/ARCHITECTURE.md#4.1, docs/ARCHITECTURE.md#1.2, docs/DATA_MODEL.md]
---

## 事由

**S4 进入 M1 施工，本工单为首发工单之一。** `R-12`（Java 单点 824 人时）已由编排者 **2026-09-20 裁决 = 采纳预案 A（加派 `backend-engineer#java2`）**，S4 派发挂起解除。
`T-003` 是 **M1 关键路径首环**（`T-003 → T-004 → T-007 → T-015 → T-016`），且是 **`#java2` 全部 29 条任务的解阻前置**——本任务（连同 `T-004`）交付后须**冻结 REST 契约与共享 DTO**，`#java2` 方可并行开工。

## 任务详情

| 项 | 内容 |
|----|------|
| 编号 | `T-003` |
| 标题 | 搭建 Java 业务组件工程骨架与 HTTP 服务基座 |
| 负责人 | `backend-engineer#java` |
| 优先级 | **P0** |
| 依赖 | `T-002`（✅ **已闭环**：两席在 v1.3.2 定版上均无条件确认） |
| 估时 | **16 人时** |
| 里程碑 | M1 |
| 需求追溯 | `FR-CONFIG-1 @ REQUIREMENTS/CONFIG.md`；`NFR-MAINT-1/3/5`、`NFR-SEC-6/7`、`NFR-DEPLOY-1 @ REQUIREMENTS/NONFUNCTIONAL.md` |
| 上游产物（唯一事实源） | `docs/ARCHITECTURE.md` **v1.3 / md5 `641b481b…` / 2418 行**；`docs/DATA_MODEL.md` **v1.3.2 / md5 `99b647ec…` / 5177 行**；`docs/REQUIREMENTS/`（`INDEX.md` + `CONFIG.md` + `NONFUNCTIONAL.md`）；`PROJECT.md` v1.2 |
| 产出 | `java/`（工程骨架 + `web` 层基座） |

## 要求

1. **任务目标**：按 `ARCHITECTURE.md` §3 模块划分搭建 Java 业务组件工程骨架与内置 HTTP 服务基座（**不含**连接池/日志/口令哈希的完整实现，仅骨架与 `web` 层基座）。
2. **验收标准（可客观判定，缺一不可）**：
   1. Maven 工程按 `ARCHITECTURE.md` §3.7~§3.12 分层建立包结构（`web/service/dao/bridge/risk_intraday/job`），`mvn -q compile` 目标 `JDK 11` 零错误、**不引入** HikariCP/SLF4J/BCrypt/Mockito；
   2. 内置 `com.sun.net.httpserver.HttpServer` 可启动，`GET /api/v1/ping` 返回 `{"code":0,...}` 信封；
   3. 静态托管 `GET /web/**` 生效，未知扩展名返回 404，仅放行白名单扩展名（`.html/.js/.css/.png/.svg/.ico/.woff2`）；
   4. 统一错误映射器输出 `{code,message,ruleId,current,threshold,nextStep,traceId}`（阻断类必含后四字段）。
3. **🔒 接口冻结（本任务对全局的关键交付物，务必显式产出）**：编码骨架的同时，**冻结并公布** ① REST 契约（路径 / 方法 / 请求·响应信封 / 统一错误结构）② 包结构与公共层接口约定（`web/service/dao/bridge/...` 的对外签名）。完成后**在「处理记录」中登记「冻结清单 + 版本号」**，并立即回报项目经理（由其通知 `#java2` 解阻）。
   > 依据：`ARCHITECTURE.md` **§1.2 约束「契约冻结纪律」**（先冻结再并行）；编排规则 **§6.3.4**。
4. **必读上游章节**：`ARCHITECTURE.md` §2.1 分层视图 / §2.3 依赖方向 / §2.4.4 Java 线程模型 / §3.7~§3.12 / §4.1（REST 约定 + 统一错误响应结构）/ §5.3 / §8.2 / §8.4；`DATA_MODEL.md` §0.6 表编号总览 + §4.7 FR→表映射。
5. **依赖纪律（`ARCHITECTURE.md` §7.2.2）**：仅 4 个 compile 依赖（JDK 11 / DM JDBC 驱动 / protobuf-java / Gson）+ JUnit 5（test）；连接池、日志框架、密码哈希、测试打桩**均自实现**，若自实现被证明不足须提 `CH-xxx` 再引入。
6. **第 7 项 · 待办工单（必办，不得静默跳过）**：你的 inbox 中已有未闭环工单 **`T-ARCH-003-architecture.md`**（`from: tech-lead`，`type: architecture`，P0，**当前状态 `OPEN`**）——技术负责人架构交底，含「必读章节」「v1.1 确认口径落库要求」「必守红线」「依赖纪律」。
   - **本次请一并处理**：逐条确认已读并在该工单「处理记录」登记确认；
   - 发现与文档不一致处 → **以 `question` 工单**提出（**不得自行偏离**）；
   - 若本次确不能处理，**必须在本工单「处理记录」写明暂缓理由与预期处理时间**（不得沉默）。
7. **纪律提醒**：`java/` 目录内你为唯一写者；**不得改** `docs/gates/`、`docs/REQUIREMENTS/`、`docs/ARCHITECTURE.md`、`docs/DATA_MODEL.md`、`docs/TASKS.md`、`docs/BOARD.md`（后者由项目经理唯一写）。

## 证据 / 复现

不适用（`type: task`）。

## 变更评估

不适用（`type: task`）。

## 回复要求

1. 完成后将**产出文件清单 + 接口冻结清单（含版本）**追加到本工单「处理记录」，并把工单「状态」置 `CLOSED`。
2. 同时把上述结论回报给**项目经理**（本工单发起人），并抄送 `#java2` 的解阻通知由项目经理统一发布。
3. 阻塞（含 DM JDBC 驱动不可得 `R-5`）**1 小时内无法自行解除即上报**，不得隐瞒。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 22:00 | project-manager | 投递 M1 首发工单（`R-12` 裁决后） | 待 `backend-engineer#java` 开工 |
| 2026-09-20 19:34 | backend-engineer#java | 开工：读 `backend-engineer.md` + 本工单 + `T-ARCH-003` + `ARCHITECTURE.md` §1.2/§2/§3.7~§3.12/§4.1/§4.3/§5.3/§7.2/§8.2/§8.4/§11/附录 B + `DATA_MODEL.md` §0 全局约定 | 已读；无与文档冲突的阻断项 |
| 2026-09-20 19:34 | backend-engineer#java | 建立 Maven 工程（`java/pom.xml`，JDK 11，`-Xlint:all`）与六层包结构 | 产出 `java/src/main/java/com/hedgeguard/{web,service,dao,bridge,risk_intraday,job,common}` |
| 2026-09-20 19:35 | backend-engineer#java | 实现 `web` 层基座（HttpServer / Router / ApiRoutes / ErrorMapper / JsonCodec / StaticHandler / AuthFilter / RequestDispatcher） | `mvn -q compile` 零错误零警告 |
| 2026-09-20 19:36 | backend-engineer#java | 真实启动 HTTP 服务并 `curl` 验证（关闭核心转储参数） | `GET /api/v1/ping` → `200 {"code":0,...,"traceId":"HG…"}`；`/web/**` 白名单 200 / 未知扩展名 404 / 穿越 404 |
| 2026-09-20 19:39 | backend-engineer#java | 依赖举证（`mvn dependency:tree` / `dependency:list`） | 仅 `protobuf-java 3.21.12` + `gson 2.10.1`（compile）+ JUnit 5（test）；**无** HikariCP/SLF4J/Logback/BCrypt/Mockito |
| 2026-09-20 19:40 | backend-engineer#java | 处理待办工单 `T-ARCH-003`（逐条确认必读章节与红线） | 已在 `T-ARCH-003` 工单登记确认；提出 3 项待澄清（见该工单） |
| 2026-09-20 19:45 | backend-engineer#java | 🔒 **登记接口冻结清单 + 版本号** | **`HGC-1.0`**（2026-09-20）：① REST 契约 ② 包结构/公共接口签名 ③ 共享 DTO。落点：`java/CONTRACTS.md`（全 7 节，含已知偏离 D-1~D-6 与 `#java2` 开工检查清单） |
| 2026-09-20 19:46 | backend-engineer#java | 单测（JUnit 5，`mvn -q test`） | **`Tests run: 99, Failures: 0, Errors: 0`**；覆盖错误映射（阻断四项）/ 路由 / 静态白名单+穿越 / 鉴权 / 脱敏 / 配置零落盘 |
| 2026-09-20 19:47 | backend-engineer#java | 红线自检 | 绑定 `0.0.0.0` → **启动期拒绝**（退出码 1，`NFR-SEC-3`/红线 9）；`dao` 之外 SQL/驱动关键字命中 **0 处**（E5 等价扫描） |
| 2026-09-20 19:48 | backend-engineer#java | 工单闭环 | 状态置 `CLOSED`；回报 `team-lead`，请其通知 `#java2` 解阻（`T-004` 亦已闭环） |
| 2026-09-20 19:50 | backend-engineer#java | ⚠️ **收尾复核：上游锚点已漂移**（派发单声明 **v1.3**（`641b481b…`/2418 行），收尾实测 **v1.5**（`9feae927…`/2516 行），经 `CH-006`/`CH-007`） | 逐条复核：§4.1.1 信封/阻断四项/分页/幂等/鉴权、§4.1.2 端点、§4.3.1 业务码（仍 **22** 条）、§3.7~§3.12 分层、§7.2.2 Java 依赖（仍 **4** compile）、§11 `CFG-30/31`、附录 B **10** 条红线 —— **全部未变**，本冻结**不受影响**；`HGC-1.0` 改锚 **v1.5**（`CONTRACTS.md` `D-7`）。<br>（说明：`ARCHITECTURE.md` 漂移到 v1.5 一事在 `SIGN-OFF.md` §8.1/§8.7 与 `CH-006`/`CH-007` 已有记录；本行**新增的价值是"逐条复核对 `HGC-1.0` 无影响"这一结论**，而非发现漂移本身。教训：引用 `ARCHITECTURE.md` 前须重新 `md5sum`，不可沿用派发单声明的锚点） |
| 2026-09-20 19:50 | backend-engineer#java | ⚠️ **发现新义务**：`CH-006` 新增「**HTTP/WebSocket 归宿**」——`/ws/quote`、`/ws/alert` 由 **Java** 提供，且 Java 侧 **允许引入轻量库（须 §7.1.11 论证）**；`§11.2` 已预留 **`CFG-32 hg.web.ws.*`**（取值待选型） | **不属 `T-003` 验收范围**（本工单验收仅 HTTP + 静态托管），故**未实现** `/ws/**`；登记为 `D-8`，WS 相关任务派发前须先定选型（手写 RFC 6455 / 轻量库） |
| 2026-09-20 19:50 | backend-engineer#java | ⚠️ **修正 `Q-1`**：`CFG-20` 在 v1.5 已收窄为**仅 HGCP 组件通道**，`CFG-32` 已为 WS 预留 | 故 HTTP 服务 bind/port/threads **仍未登记**；建议补 **`CFG-33 hg.web.bind` / `hg.web.port` / `hg.web.threads`**（与 `hg.web.root`/`hg.web.ws.*` 同族），登记为 `D-9` |

### 产出文件清单（`T-003`）

| 文件 | 说明 |
|------|------|
| `java/pom.xml` | Maven 工程；`source/target=11`、`-Xlint:all`、`-Pdm` profile 承接 DM 驱动（默认关闭） |
| `java/CONTRACTS.md` | 🔒 **冻结清单 `HGC-1.0`**（REST 契约 / 包与接口 / 共享 DTO / 配置键 / 变更纪律 / 未决项） |
| `java/src/main/java/com/hedgeguard/web/` | `Bootstrap` `HttpServerLauncher` `RequestDispatcher` `Router` `ApiRoutes` `ApiResponse` `ErrorInfo` `ErrorCode` `BizException` `ErrorMapper` `JsonCodec` `PageData` `HttpResult` `RequestContext` `QueryStrings` `RouteHandler` `StaticHandler` `AuthFilter` `SessionAuthFilter` `Session` `SessionStore` `TraceIds` `handler/PingHandler` |
| `java/src/main/java/com/hedgeguard/service/` | `package-info`（11 模块映射 + 分层纪律）+ `ParamDispatchService` |
| `java/src/main/java/com/hedgeguard/bridge/` | `TradingBridge`（签名逐字照 §3.10）+ 8 个 HGCP DTO + `stub/InMemoryTradingBridge` |
| `java/src/main/java/com/hedgeguard/risk_intraday/`、`job/` | `package-info`（职责与纪律） |
| `java/src/main/java/com/hedgeguard/common/` | `Config`（敏感键装载即拒）`Clocks`（可注入时钟）`SensitiveLog`（CFG-19 强制脱敏） |
| `java/src/test/java/com/hedgeguard/` | 10 个测试类 / 99 个用例 |
| `web/index.html` `web/css/hg.css` `web/js/core/api.js` | 静态托管自检用**占位页**（前端交付物就绪后覆盖；零构建链） |

### 证据（实际命令与实际输出）

```text
$ mvn -q compile            # 退出码 0，无 WARNING / ERROR
$ mvn -q test               # Tests run: 99, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS

$ java -XX:-CreateCoredumpOnCrash -XX:ErrorFile=/dev/null -Dhg.coredump.off=true \
       -Dhg.http.port=27402 -Dhg.web.root=.../web -cp target/classes:$(cat target/cp.txt) \
       com.hedgeguard.web.Bootstrap
INFO: HTTP 服务已启动：http://127.0.0.1:27402（静态托管前缀 /web/**，REST 前缀 /api/v1）
INFO: hg-biz 就绪：2026-09-20T19:34:56

$ curl -s -i http://127.0.0.1:27402/api/v1/ping
HTTP/1.1 200 OK
Content-type: application/json; charset=UTF-8
X-hg-traceid: HG20260920193524461db048
{"code":0,"message":"OK","data":{"service":"hg-biz","status":"UP","time":"2026-09-20T19:35:25",
 "uptimeSeconds":29,"apiBase":"/api/v1"},"traceId":"HG20260920193524461db048"}

$ curl -s -i http://127.0.0.1:27402/web/index.html
HTTP/1.1 200 OK
Content-type: text/html; charset=UTF-8      Cache-control: no-store

$ curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:27402/web/README.md      → 404（未放行扩展名）
$ curl -s -o /dev/null -w '%{http_code}\n' --path-as-is \
        http://127.0.0.1:27402/web/../docs/DATA_MODEL.md                            → 404（穿越被拦）
$ curl -s -i http://127.0.0.1:27402/api/v1/auth/user
HTTP/1.1 401 → {"code":10006,"message":"未提供会话凭据：请先登录（Cookie HGSESSION 或请求头 X-HG-Session）",...}
$ curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:27402/api/v1/ping → 401（非公开方法）
$ curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:27402/                     → 404

$ java -Dhg.http.bind=0.0.0.0 ... com.hedgeguard.web.Bootstrap     # 真实退出码 1
java.lang.IllegalStateException: 拒绝启动：绑定地址=0.0.0.0（键 hg.http.bind）；
  原因=只允许监听本地回环，禁止对外暴露（NFR-SEC-3 / 附录 B 红线 9）；处置=改回 127.0.0.1 后重启

$ mvn -B dependency:tree
[INFO] +- com.google.protobuf:protobuf-java:jar:3.21.12:compile
[INFO] +- com.google.code.gson:gson:jar:2.10.1:compile
[INFO] \- org.junit.jupiter:junit-jupiter:jar:5.10.2:test
# 命中禁用依赖检查：✅ 未命中（HikariCP/SLF4J/Logback/BCrypt/Mockito）

$ grep -rniE '\b(select|insert|update|delete|drop|alter|truncate|jdbc|odbc|sqldriver|connectstring)\b' \
      java/src/main/java --include=*.java | grep -v '/dao/'         # 命中 0 处（E5 等价扫描）
```

### 待澄清（已按工单纪律登记，不得自行偏离）

| # | 项 | 影响 | 建议 |
|---|----|------|------|
| `Q-1` | 前端 HTTP 服务的 `bind` / `port` / `threads` **未登记**在 `ARCHITECTURE.md` §11.2（`CFG-20` 已收窄为**仅 HGCP 组件通道** `hg.tcp.bind/port`；`CFG-32` 为 **WebSocket** 预留 `hg.web.ws.*`） | 配置清单缺口（`NFR-DEPLOY-4`）；本实现暂用 `hg.http.*` | 补 **`CFG-33 hg.web.bind` / `hg.web.port` / `hg.web.threads`**（默认 `127.0.0.1` / `27402` / `16`），与 `hg.web.root`、`hg.web.ws.*` 同族 |
| `Q-2` | §4.3.1 业务码仅至 `10022`，组件码 `0x1019`（关联对象子账户不一致）**无对应业务码** | `#java2` 的 TRADE 模块映射缺一档 | 新增 `10023` 或明确复用 `10012` |
| `Q-3` | 分发顺序（**先认证再路由** vs 先路由再认证）文档未规定；本实现取"先认证"（避免端点枚举） | 影响 404/405 对未认证请求的可见性 | 请技术负责人确认后写入 §4.1.1 |
| `Q-4` | `CH-006` 要求 Java 侧提供 `/ws/quote`、`/ws/alert`，**允许引入轻量第三方库（须 §7.1.11 论证）**；而 JDK 内置 `com.sun.net.httpserver` **无 WebSocket** | 影响 WebSocket 任务的依赖与工作量（手写 RFC 6455 vs 引库） | 请在派发 WS 任务前**先出选型结论**；本组件**未**擅自引入任何库 |

> 上述四项均已写入 `java/CONTRACTS.md`（§1.3 / §4 / §4.1 / §6 `D-4`~`D-9`）与 `T-ARCH-003` 工单处理记录，
> **未**擅自扩大实现范围；`#java2` 可按现有冻结契约先行开工，待结论后按 `HGC-1.1` 增量升版。

### 裁定结果（2026-09-20，team-lead 回单；**全部不返工**）

| 原项 | 裁定 | 我的落地动作 |
|------|------|-------------|
| `Q-1`（HTTP 配置键未登记） | ✅ **采纳**：补登记 **`CFG-33 hg.web.bind/port/threads`**（由技术负责人落 `§11`，**不是我的写域**） | 无需改代码（我仍用 `hg.http.*`，落地后改 `Config` 常量即可）；已更正 `CONTRACTS.md` `D-5` 里"建议补 `CFG-32`"的过时表述（`CFG-32` 已被 WS 占用） |
| `Q-2`（`0x1019` 无业务码） | ✅ **暂不新增**，维持错误码 **62** 不变（新增＝协议实质变更，须 `CH-xxx` + 重跑协议专项测试）；**挂账**为 M2 前（`T-030` 汇签前）技术负责人裁定项，**不阻塞 M1** | 无代码改动；`CONTRACTS.md` `D-4` + §1.3 已登记：`#java2` 的 TRADE 映射**暂按 `10012` 并保留 `0x1019` 语义** |
| `Q-3`（分发顺序） | ✅ **采纳「先认证再路由」**，写入 `§4.1.1`（由技术负责人落） | **我的实现即为此语义**（`RequestDispatcher` 先 `authenticate` 后 `route`），**无代码改动**；已登记 `D-13` |
| `Q-4`（WS 选型） | ⏳ **`T-003` 不覆盖 WS**（确认）；WS 选型论证由技术负责人按 `§7.1.11` 落，**`T-028` 开工前必须出结论** | 我另交付**选型论证输入件**（见下）：`java/CONTRACTS.md` §4.2 + `java/SELECTION_HTTP_WS.md` |

### WebSocket 约定与选型论证（`CH-006` 传导，本轮追加交付）

| 交付 | 落点 | 要点 |
|------|------|------|
| **WS 对外约定** | `java/CONTRACTS.md` **§4.1** | ① `§4.1.1 已由文档冻结`：端点 / 仅回环 / `HGSESSION` 鉴权 / 两侧消息格式 / 频率与丢帧策略 / 时效标注 / `CFG-32` / 线程模型 / 数值格式（**逐条带出处**）；② `§4.1.2 文档未定义项`：`W-1`~`W-6` 六项**提案**（客户端→服务端订阅格式、**子账户数据权限过滤（安全缺口）**、心跳、握手拒绝与关闭码、首帧语义、`ts` 语义）——**均标注"待批"，未擅自定死** |
| **选型论证** | `java/CONTRACTS.md` **§4.2** + `java/SELECTION_HTTP_WS.md` | 推荐 **A'（独立监听 + 自实现 RFC 6455，零依赖）**；**证据**：`Java-WebSocket`（B1）**运行期实测** `NoClassDefFoundError: org/slf4j/LoggerFactory`（静态分析 + 动态复现双证）→ 被 §7.2.2 的 SLF4J 禁令排除；`nanohttpd-websocket`（B2）9 年无发布 + 每连接一线程不匹配 `T-040`；Jetty 直用（12 传递件）/ Javalin（**需 JDK 17**、29 传递件）否决；补 **候选 C = SSE**（零依赖，但须 `CH-xxx`）。**`pom.xml` 未新增任何依赖** |
| **两处技术更正**（本轮价值最高） | `CONTRACTS.md` §4.2.1 + `SELECTION_HTTP_WS.md` §1/§2.1 | ① **`§7.1.11` 选项 A 措辞不可实现**：「JDK 内置 `HttpServer` **协议升级**」—— `javap` 证明 `HttpExchange`/`HttpServer` **无 `upgrade`/`hijack`/裸套接字访问**，只能走 HTTP 响应体（含分块），**无法降级为裸字节流** → 可行形态是**独立 `ServerSocket` 监听 `CFG-32` 的 `hg.web.ws.port`**（与文档已预留独立 WS 端口天然吻合，**无需改架构**）。② **依赖取证口径更正**：我最初报「`dependency:tree` 报"**0 传递依赖**"≠ 运行期可用（`Java-WebSocket` 的反例，`slf4j-api` 藏在 `<dependencyManagement>`）」——**该判定已由我独立复核推翻并撤回**（见下方"自我更正"行）：实测 tree **如实报出** `org.slf4j:slf4j-api:2.0.13:compile`（1.5.7 → 2.0.6），且 `slf4j-api` 在 pom 的 `<dependencyManagement>` **与真实 `<dependencies>` 两处都有**（无 scope、无 `optional`）。**结论不变**：`Java-WebSocket` 带真实 compile 传递依赖 → 与 §7.2.2 的 SLF4J 禁令冲突 → **不可引入**（**无需任何"隐藏依赖"假设**）。`W-9`（新依赖须补"冒烟实例化"）**仍建议采纳，但理由改为**防 `provided`/`optional`/反射/`ServiceLoader` 这类**确实不进依赖树**的缺失 |

### ⚠️ 并发写入留档（`java/CONTRACTS.md`，2026-09-20 20:01:12）

收尾时发现**我的写域内文件被同时写入**：`java/CONTRACTS.md` 于 **20:01:12** 由 21 672 B → 30 556 B，
新增 `§4.1 WebSocket 对外约定` + `§4.2 选型论证`，并把冻结标识改为 `HGC-1.1` —— **内容方向与 team-lead 派发要求完全一致，
系同一次派发的同名实例（`backend-engineer#java`）所写，但未事先获通知**（与 `SIGN-OFF.md` §8.1/§8.7
记录的"同一实例跨唤醒 / 冗余派发"同源，属 `R-15` 同类事件）。

**我的处置（按纪律，不删除、不改写）**：
1. **未删除、未改写**其任何内容；
2. 仅**追加** `§4.2.1 补充与一处技术更正`（A→A' 的 `javap` 证据、B1 运行期复现、候选 C=SSE、陷阱结论）+ §6 的裁定登记（`D-4`/`D-5`/`D-6` 更新 + `D-13`/`D-14`）；
3. 把我先写的 `java/SELECTION_HTTP_WS.md` **改写为"证据附录"**（结论以 `CONTRACTS.md` §4.2 为准），**避免两份制品各说一套**；
4. 在 `CONTRACTS.md` §4.2.1 与 `SELECTION_HTTP_WS.md` §7 **双向声明归属**；
5. **已上报** `team-lead`，请其明确 `java/**` 的单写者身份后再派后续任务。

> **对项目的建议**：`java/**` 目前存在**两个同名实例**竞争写入的风险。请编排者按 `SIGN-OFF.md` §8.2 第 1 条
> （"涉及制品写入的任务一律新起实例、不在旧实例上续跑"）的**对偶**要求补充一条：
> **同一制品在任一时刻只允许一个活跃写者，跨实例交接须先显式冻结并公布当前 md5**。

### 处理记录（续，裁定与 WS 交付）

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 19:58 | backend-engineer#java | 收到 `team-lead` 转达的**用户技术栈裁决**（HTTP/WebSocket 全在 Java 端；允许轻量库但须先论证） | 本工单 HTTP 部分**不受影响**；新增 WS 义务由后续 `T-028`/`T-040` 承接 |
| 2026-09-20 19:59 | backend-engineer#java | 实测 `Java-WebSocket` 的依赖真相（`dependency:tree` = 0 传递依赖 **但**运行期 `NoClassDefFoundError: org/slf4j/LoggerFactory`）；`javap` 核实 `HttpExchange`/`HttpServer` **无协议升级能力** | 得出两项结论：① 库路线被 §7.2.2 SLF4J 禁令排除；② `§7.1.11` 选项 A 措辞须更正为"独立监听 + 手工握手" |
| 2026-09-20 20:00 | backend-engineer#java | 交付选型论证输入件 | `java/SELECTION_HTTP_WS.md`（后改写为**证据附录**，结论以 `CONTRACTS.md` §4.2 为准） |
| 2026-09-20 20:01 | backend-engineer#java（**同名实例**） | 落笔 `java/CONTRACTS.md` §4.1 WS 对外约定 + §4.2 选型论证，冻结标识改 `HGC-1.1` | ⚠️ **并发写入，未获通知**；已留档（见上"并发写入留档"节） |
| 2026-09-20 20:07 | backend-engineer#java | 追加 `CONTRACTS.md` §4.2.1（技术更正 A→A' + B1 运行期复现 + 候选 C=SSE + 陷阱结论）与 §6 裁定登记（含 `D-13`/`D-14`） | **未删除、未改写**同名实例内容；仅追加并双向声明归属 |
| 2026-09-20 20:08 | backend-engineer#java | 登记 5 项裁定结果（`Q-1`/`Q-2`/`Q-3` 采纳或挂账；`Q-4` 转 WS 通道） | 全部**不返工**；仅 `CONTRACTS.md` 文档登记与一处过时表述更正（`D-5` 的 `CFG-32`→`CFG-33`） |
| 2026-09-20 20:09 | backend-engineer#java | 回归验证（本席改动仅限 `.md`，**未改任何代码/`pom.xml`**） | `mvn -B test` 全绿。**测试数为变动值**：我交付时（19:45）`99/99`；`#java2` 并行开发中持续新增（`M1ProductSpotPlanSchemaTest` +4，`impl/M1DaoReadAndClobTest` +2 与 `impl/M1DaoMappingAndWriteTest` +3 …）→ 20:31 实测 **108/108 绿**。**故不得以某一时点的用例数作为验收判据**（判据应为"当前 HEAD 全绿"）。`dependency:tree` 仍为 **2 compile + JUnit 5 test（未新增依赖）** |
| 2026-09-20 20:12 | backend-engineer#java | **冻结契约的早期集成校验**（对 `#java2` 已落地的 `PRODUCT`/`SPOT`/`PLAN` 批次做只读抽查） | ✅ **冻结有效**：`dao/impl` 现有 **30 个文件**（`AbstractDmDao` + **29 个 `Dm*Dao`**），**全部 `extends AbstractDmDao`**；接口置于 `com.hedgeguard.dao`、实现置于 `dao.impl`、构造注入 `TxTemplate`、`businessColumns().clone()`、`readBusiness` 读主键 —— **逐条符合 `HGC-1.0` §2.3 约定**；`dao` 之外 SQL/驱动关键字命中 **0**；`impl` 内 `.close()` 误用 **0**。→ `HGC-1.0` 的可用性得到实证（非自评） |
| 2026-09-20 20:14 | backend-engineer#java | ⚠️ **锚点第 3 次漂移复核**：`ARCHITECTURE.md` 已由 v1.5（`9feae927…`/2516）推进到 **v1.5.2**（`32babcacd031af19d7009c8e1cb9befd`/2546，经 `v1.5.1` M2 探路登记 + CTP API 名勘误、`v1.5.2` FR 计数 181→183 勘误；两者均自述"**非 `CH-xxx`、不改变架构分层与接口契约**"） | **重新实测复核**：§4.3.1 业务码仍 **22 行（10001~10022）**、附录 B 仍 **10 条红线**、静态托管白名单不变、§7.2.2 仍 `HikariCP 不引入`、`CFG-32/33` 在位 → **对本冻结无影响**。`CONTRACTS.md` 锚点已更新为 **v1.5.2**（`D-7` 记 3 次漂移）；`DATA_MODEL.md` 全程 `99b647ec…`**未变**（唯一稳定锚）。<br>**另实测**：§7.1.11「选项 A：JDK 内置 HttpServer **协议升级**」措辞**截至 v1.5.2 仍未更正** → `W-7` 仍待技术负责人落笔，`T-028` 开工前必须确认 |
| 2026-09-20 20:33 | backend-engineer#java | ⚠️ **自我更正（我的测量错误）**本工单前文（及我给 `team-lead`/`#java2` 的消息）曾报「`dependency:tree` 报 **0 传递依赖**，故 `Java-WebSocket` 是"树干净但运行期炸"的陷阱」。**该判定基于我自己的错误测量，现撤回** | **根因 = 我的 grep 过滤**：我用 `^\[INFO\] (probe\|\+\-\|\\-\|\|)` 取树，**带缩进的子节点行**（`[INFO]    \- …`，`[INFO] ` 后是空格）**不匹配** → 只看到 1 行就下了"0 传递依赖"的结论。**无过滤重跑**：`[INFO]    \- org.slf4j:slf4j-api:jar:2.0.13:compile`（1.5.7 → `2.0.6`）；`python3` 解析 pom：`slf4j-api` 在 `<dependencyManagement>`**与真实 `<dependencies>`（无 scope、无 optional）两处都有** ⇒ **必然传递**。<br>**结论不变、理由更正**：`Java-WebSocket` 带**真实、树可见的 compile 传递依赖** `slf4j-api` ⇒ 与 §7.2.2 SLF4J 禁令**直接冲突** ⇒ 不可引入。**运行期 `NoClassDefFoundError` 复现仍有效，但它是"字节码要求 slf4j"的必然结果，不是"树与运行期背离"的证据。**`W-9` 理由改为编排者的重述：防 `provided`/`optional`/反射/`ServiceLoader`（`dependency:tree` 是**必要**判据，冒烟是**补充**判据，二者不可互替——我此前把它们对立起来是错的）。<br>**已同步更正**：`CONTRACTS.md` §4.2.1(2)、`SELECTION_HTTP_WS.md` §2.1（**两处更正由同名席位先行落笔，我已复核其证据与我的实测一致**）；已向 `team-lead`、`#java2` 发更正。**教训**：与记忆中 `feedback_dependency_probe.md` 的同一条警告重复犯错——**"树干净但运行期炸"须先证明"树干净"是用对的命令与对的过滤得到的** |
| 2026-09-20 20:47 | backend-engineer#java | 🔴 **我的写入纪律违规（自查自报）**：接 `T-061` 后我用 `Write` 直接创建 `dao/ParamDispatchDao.java` 与 `dao/entity/ParamDispatch.java`，**两文件已存在**（由同名席位于 20:41–20:42 作为 `T-061` 首个增量交付，含 `docs/DEPLOY/schema/m3_risk.sql` + `impl/DmParamDispatchDao.java` + `impl/RiskParamDispatchSchemaTest.java`）→ **我的覆盖使接口方法名与既有实现不匹配，构建一度失败** | **根因**：我**先写后查**（`grep ParamDispatch` 的引用检查是在写入之后才做的），违反了我自己刚在两封消息里主张的"写前先查"。<br>**影响**：仅 `ParamDispatchDao`（方法名 `findByVersionAndType`/`findProblemsSince`/`findBySubAccountSince` vs 实现的 `findByVersion`/`findProblems`/`findBySubAccount`）与 `ParamDispatch`（字段集与文档一致、与实现用法一致 ⇒ **功能等价**）；**未触碰**其 `m3_risk.sql` / `DmParamDispatchDao.java` / `RiskParamDispatchSchemaTest.java`。<br>**修复**：按**既有实现的实际方法集**重写接口（保住我的 `D-16` 警告 javadoc，**未新增任何签名**）→ `mvn -B test` **125/125 全绿、BUILD SUCCESS**。<br>**诚实声明**：原两文件的 **javadoc 措辞无法复原**（无版本控制）；**功能契约已恢复并经编译+测试验证**。已上报编排者，并**请其先裁定 `T-061` 的唯一写者**——查明同名席位正在并行推进 `T-061`，**在其裁定前我不再动 `T-061` 任何文件**（避免继续双写） |
| 2026-09-20 20:48 | backend-engineer#java | 顺带发现（供 `T-052` 部署清单）：`docs/DEPLOY/schema/` 文件命名**不统一**——`m1_config_auth.sql`（我）/ `m1_product.sql`、`m1_spot.sql`、`m1_plan.sql`（`#java2`）/ **`m3_risk.sql`**（同名席位）。同一个 M1 阶段的脚本混用 `m1_` 与 `m3_` 前缀 | 建议编排者统一命名（并登记进部署清单执行顺序）；我**不擅自改名**（涉及他人文件与部署引用） |
| 2026-09-20 20:58 | backend-engineer#java | 按编排者裁定执行三项：① `hg.web.threads` 默认值 **`32` → `16`**（`HttpServerLauncher.DEFAULT_HTTP_THREADS` 与 `Config` javadoc；**登记值取实测实现值**）；② `CONTRACTS.md` §4 订正为 `16` 并留痕「32 系编排者越界拟值、已撤回」；③ 锚点更新至 **v1.5.3**（**实测** `522b2ef4e44737c26ac56a4d32965688` / 2572 行 / 287253 B / mtime `20:52:22`，**连采 3 次跨 6 s 恒定**） | **验证**：`mvn -B -q compile` **RC=0 / 0 ERROR**；本席 15 个测试类 **101/101 全绿 / BUILD SUCCESS**；本席文件内 `hg.http.*` **清零**（`Config` 三键已为 `hg.web.bind|port|threads`）。<br>⚠️ **锚点的一处中间态留痕**：同一席位先前记的 v1.5.3 md5 为 `a35bb076…`（**同为 2572 行**）—— 那是 `tech-lead-15` 同一修订轮**未写完连锁订正**时的中间态 ⇒ **教训：行数相同不代表内容未变，冻结指纹只能靠「多次采样 md5 恒定」**。<br>**全量套件当前为红**（`142 tests / 1 failure`），失败在 `#java2` 的 `service/AuthServiceTest.loginSucceeds`（**非本席代码**）；按第 15 条纪律，活跃写入期的快照不作验收判据。`#java2` 报「`DmParamDispatchDao` 编译失败」经复核为**过期中间态**（窗口 20:46:55→20:48:02，已于 20:48:02 修复），已回信请其重测。<br>**`T-061` 剩余 10 张表：按编排者指示暂停**（等模块恢复绿） |
## 状态

- **当前状态**：CLOSED
- **终态确认人**：项目经理
- **终态时间**：2026-09-20T19:48+08:00
- **终态附注**：本工单**不含** DM8 相关验收项（`T-003` 无需连接数据库）；
  冻结清单 `HGC-1.0` 已产出，`#java2` 解阻前置达成。
  **本状态为提交态**，最终验收以技术负责人 / 测试经理门禁复核为准（`backend-engineer.md` §10.6 不自我验收）。

