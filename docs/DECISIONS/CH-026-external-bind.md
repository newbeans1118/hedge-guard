# CH-026 前端 HTTP/WS 监听地址技术裁定书

> **裁定单编号**：`CH-026`（现行最大 `CH-025` 顺延；`CH-081` 系 `TL-ARCH-081` 正则误命中，非 CH 单）——**待编排者登记确认**
> **裁定来源**：用户 2026-11-24 指令「java 服务只监听了回环地址，无法接受外部流量」及随后两轮逐项裁定
> **性质**：**纯裁定 + 提案**（不动代码、不动冻结件、不改 `PROJECT.md` 原文、不改 `TASKS`/`BOARD`/`TEST_RESULT` 共享台账）
> **时点**：2026-11-24；本文引用的行号 / 版本 / md5 **以实时为准**
> **依据链**：`PROJECT.md`（唯一事实源）· `ARCHITECTURE.md` · `REQUIREMENTS/` · `DEPLOY/README.md` · `java/CONTRACTS.md`（HGC-1.6）
> **配套提案**：`CH-026-project-md-revision-proposal.md`

---

## 1. 裁定结论（用户逐项选定）

| # | 裁定项 | 选定值 | 备注 |
|---|--------|--------|------|
| ① | 变更范围 | **仅前端 HTTP/WS**（`hg.web.bind` 可配非回环 + 新增 `hg.web.ws.bind`）；**`hg.tcp.bind` 保持回环** | C++ 侧零改动；HGCP 仍回环 + token 鉴权 |
| ② | 默认行为 | **默认即对外**（`0.0.0.0`） | 推翻「默认回环」的安全默认 |
| ③ | 安全补偿 | **仅保留现有鉴权** | 不拒绝通配绑定、不加 TLS/ACL、不加应用内 IP 白名单、不新增依赖 |
| ④ | 编号与形态 | **`CH-026` 顺延**；先交纯裁定提案，用户重新确认后再逐件落地 | 本裁定书 + `PROJECT.md` 修订提案两份 |

**定稿时已向用户列明并经确认的后果**：明文 HTTP 对外；`POST /api/v1/auth/login` 提交明文口令、`/ws/*` 明文；`/api/v1/ping` 与 `/web/**` 本为公开路径；所有既有部署重启后 `:80`/`:81` 自动对外；生产部署同样生效；本次为 dev/test 档（SQLite 代 DM8，CH-016），结论不替代生产验收。

---

## 2. 关键语义发现与上一轮表述更正（如实登记）

- 严格读四条锚点条款原文，**措辞均限定于「组件间 TCP 通信 / 组件通信」**：
  - `PROJECT.md:484` —— 「**组件间 TCP** 仅走本地回环 + 连接鉴权」
  - `REQUIREMENTS/NONFUNCTIONAL.md:105` —— 「**组件间 TCP 通信**仅监听 `127.0.0.1`」
  - `REQUIREMENTS/CONFIG.md:336` `R-CONFIG-5` —— 「**组件通信安全**（⬅ 硬约束）」
  - `ARCHITECTURE.md:3060` 第 9 条 —— 「监听 `0.0.0.0` 或关闭连接鉴权 → 违反 `NFR-SEC-3`」（该节语境为组件通信）
- 裁定 ① 明确 HGCP 组件通道**保持回环 + 连接鉴权** ⇒ 上述红线**实质仍成立，不需要推翻**。
- 现状前端也被锁回环，成因是**实现侧与运维文档把该约束扩展到了前端**：`Config.loopbackOnly()` 为统一函数、`ConfigService.LOOPBACK_BIND_KEYS` 含 `hg.web.bind`、`WsServer.java:156` 硬编码 `InetAddress.getLoopbackAddress()`、`DEPLOY/README.md:53` 把 `hg.web.bind` 与 `hg.tcp.bind` 并列白名单、`ARCHITECTURE.md:78` G7 判据以 `ss -lntp` 覆盖全部监听、`:1084` T8、`:2208` WS 句。
- **更正**：上一轮影响清单把 `PROJECT.md:484` / `NFR-SEC-3` / `R-CONFIG-5` 列为「推翻的锚点条款」**不够精确**。按 `CODEBUDDY.md`「措辞冲突优先语义消解」「勿为措辞统一而改定版制品」纪律，本裁定改为**保留红线原文、澄清适用范围**，仅改「前端绑定」相关实现与表述。变更面显著收窄。
- **②③ 的残余风险不因本更正而减少**（见 §5）；`R-CONFIG-8`（`Config.java:22`「默认值不得低于合规口径」）需随 ② 在本裁定内**重定义合规口径**，而非删除该纪律。

---

## 3. 事实基线（行号级实测，2026-11-24）

| 归类 | 制品:行 | 现状 |
|------|---------|------|
| 保留原文 | `PROJECT.md:454`、`:479`、`:484`、`:481-483` | TCP 通信安全要求 / 组件通信行 / 安全红线(4) 及其余三条红线 |
| 保留原文 | `REQUIREMENTS/NONFUNCTIONAL.md:105`；`REQUIREMENTS/CONFIG.md:336` | `NFR-SEC-3` / `R-CONFIG-5` |
| 保留原文 | `ARCHITECTURE.md:3060`、`:108`、`:109` | 红线第 9 条 / 组件通信拓扑 / 安全红线 |
| 需改（实现） | `common/Config.java:219-228`、`:22`、`:28-34` | `loopbackOnly()` 白名单 + `R-CONFIG-8` javadoc + `KEY_HTTP_BIND` javadoc |
| 需改（实现） | `web/HttpServerLauncher.java:30`、`:58`、`:63` | HTTP 绑定守卫与 javadoc |
| 需改（实现） | `web/ws/WsServer.java:156`、`service/ConfigService.java:98-99` | WS 硬编码回环；「WebSocket 无独立绑定键」注释 |
| 需改（实现） | `service/ConfigService.java:101-106`、`:301-302`、`:382-391` | `LOOPBACK_BIND_KEYS` / `LOOPBACK_ONLY` / `requireLoopbackBind()` |
| 需拆分 | `web/Bootstrap.java:558` | HGCP 客户端复用 `loopbackOnly(KEY_TCP_BIND)`；须拆成「组件通道守卫」与「前端绑定解析」两条路径，前者保持回环 |
| 需改（文档） | `ARCHITECTURE.md:78`、`:1084`、`:2208`、`:765-768`、`:2867-2881`、`:2717`、`:2750` | G7 门禁 / T8 判据 / WS 句 / CFG-32/33 / 追溯表 |
| 需改（文档） | `DEPLOY/README.md` §1 拓扑、`:27-30`、`:52-53`、`:133-148`、`:224-235`、`:395-405`、§9 | 「浏览器仅本机/内网」/ 三不变量 / 仅回环 / CFG 表 / 健康检查 / §10 |
| 需改（文档） | `DEPLOY/hg.properties.template:24`、`:31-37`；`docs/DATA_MODEL.md:4943`（核对） | bind 项与注释；FR-CONFIG-9 登记 |
| 需改（冻结件） | `java/CONTRACTS.md`（HGC-1.6，`:23`、`:38-40`） | 唯一写者 `backend-engineer#java`；新增约定须投 `question` 落笔，不得先改后报 |
| 需改（脚本） | `scripts/deploy/common.sh:49-54`；`start-hg-biz.sh:41/68/80-81`；`health-check.sh:31/33/49-70`；`start-all.sh:23/30/34/40`；`start-hg-trading.sh:52/65/87/98` | BIND 探测地址与「回环」文案 |
| 需改（测试） | `SensitiveLoggingAndConfigTest:123-135`；`ConfigServiceTest`；`WsProductionAssemblyTest`；`WsServerTest`；`BootstrapAssemblyTest`（复核） | 回环拒绝断言反转 |
| **不动** | `cpp/**` | 裁定 ① ⇒ C++ 侧零改动（`app_config.cc:437-450`、`hgcp/endpoint.h:23/29/43/47`、`tcp.h:15` 均不动） |
| 需登记 | `web/ApiRoutes.java` + `web/js/core/routes.js` | 若新增配置端点/键，按 CODEBUDDY.md 约定同步 |

---

## 4. 收窄后的落地顺序

1. **用户重新确认** `PROJECT.md` 修订提案（配套第二份文件）—— `PROJECT.md:5-7`、`:646` 前置门禁。
2. 编排者登记 `CH-026`、`TASKS.md` 立号、`BOARD.md` 逐轮留痕（共享文档单写者、逐条留痕、可回退）。
3. 文档链单写者逐件：`REQUIREMENTS` → `ARCHITECTURE` → `DATA_MODEL` → `DEPLOY` → `CONTRACTS`（唯一写者投 `question`）→ `DECISIONS` 归档。
4. 代码：拆 `Config` 守卫 → `HttpServerLauncher` → `WsServer` → `ConfigService` → `Bootstrap` → `ApiRoutes`/`routes.js` → 部署脚本。
5. 测试反转 + 新增正向/负向用例；全门禁复跑：`mvn -B -o clean test`（现行 1554 用例）、`mvn validate`（E1–E9）、`bash tools/arch-check/arch-check.sh`、C++ `ctest`、前端探针。
6. `docs/TEST_RESULT.md` **只追加**；`docs/BACKLOG.md` W 项按需登记。

---

## 5. 安全补偿与残余风险（按 ③ 裁定，如实披露）

按用户裁定「仅保留现有鉴权」，**不新增任何代码侧补偿**（零新增 Maven 依赖，符合红线）。保留项：`SessionAuthFilter` + `HGSESSION`（HttpOnly）、`/ws/*` 的 W-2 子账户授权、审计留痕、HGCP token 鉴权。

| # | 残余风险 | 说明 |
|---|---------|------|
| R1 | 明文传输 | 登录口令、会话 Cookie、业务数据、WS 帧均可被网络嗅探；无 TLS 终止要求进代码 |
| R2 | 无来源控制 | 不拒绝 `0.0.0.0` 通配绑定，无 ACL / IP 白名单 / 速率限制 |
| R3 | 自动暴露 | ② 使既有部署重启即对外，运维可能未及配置网络层防护 |
| R4 | 公开面 | `/api/v1/ping`、`/web/**` 本就公开，对外后信息面扩大 |
| R5 | 生产同效 | 默认对外不区分 dev/test 与生产档 |

部署文档须**如实标注**上述风险与建议前置（TLS 反向代理、网络 ACL），但按裁定**不作为代码强制项**。

---

## 6. 替换后的验收判据（T8' / G7'）

- **T8'（替换 `:1084` 原 T8）**：按配置绑定 —— `hg.tcp.bind` 恒为 `127.0.0.1` 且配置非回环仍拒绝启动；`hg.web.bind` / `hg.web.ws.bind` 默认 `0.0.0.0` 时 `:80`/`:81` 对外可连；组件通道鉴权不变。
- **G7'（替换 `:78` 门禁）**：「组件间通信仅监听 `127.0.0.1` + 强制鉴权 + 凭据零落盘」三项零命中不变；`ss -lntp` 判据收窄为**仅对 `39000` 判回环**，`:80`/`:81` 按配置判定。
- 新增负向用例：HGCP 配 `0.0.0.0` 仍须拒绝启动（证明红线未失守）。

---

## 7. 未决 / 待编排者

- `CH-026` 编号登记确认；`PROJECT.md` 版本 `v1 → v1.4`（顺延 §11 现行最大 `v1.3`）。
- CFG-32 新增 `hg.web.ws.bind` 键的落登（`ARCHITECTURE.md §11` 唯一写者）。
- `java/CONTRACTS.md` 是否需新增约定 → 投 `question` 由 `backend-engineer#java` 落笔。
- `PROJECT.md:675`「企业自己用、自行部署和管理…**非对外 SaaS**」与 ② 的口径张力，须在 `PROJECT.md` 修订提案中显式裁定（建议：部署归属仍自持，仅监听面放开）。
- 是否同步 `web/js/core/routes.js`（若新增端点）。

---

## 8. 用户确认结论与授权（2026-11-24）

| 项 | 结论 |
|---|------|
| `PROJECT.md 修订提案 §5` 勾选 | **4 项全选**：版本 `v1 → v1.4` 与 §11 登记；保留安全红线原文、仅澄清适用范围；§6 新增「前端 HTTP/WS 监听」约束行；知悉 R1–R5 残余风险 |
| `§4` 口径张力 | **裁定 (a)**：`PROJECT.md:675` 原文保留 —— 部署形态仍为企业自持、非 SaaS 服务，`CH-026` 仅放开监听地址，不改变部署归属与用户范围 |
| 落地授权 | **授权按本裁定书 §4 顺序逐件落地** |
| 编号 | `CH-026`（顺延现行最大 `CH-025`），**待编排者登记确认** |

**登记说明（如实披露）**：`CH-026` 为**裁定单**，确认结论与授权落本文件及配套 `PROJECT.md` 修订提案。`docs/TASKS.md` / `docs/BOARD.md` / `docs/TEST_RESULT.md` 属共享台账（单写者、逐条留痕、可回退；`BOARD.md §5` 为倒序逐轮表、`TASKS.md §5.2` 为任务数/人时现行值唯一来源），其立号与逐轮留痕**由编排者按 `BOARD.md §5` 顺序登记**，本轮不擅自改动、不计任务数/人时增量。

---

## 9. 落地结果与门禁证据（2026-11-24 实测）

| 判据 | 命令 | 结果 |
|------|------|------|
| 参考数据静态校验 | `gen_reference.py --check --groups master,demo` | **PASS (RC=0)**；246 行 / 7 表 0 差异 |
| Java 全量单测（标准门禁） | `cd java && mvn -B -o clean test` | **BUILD SUCCESS**；`Tests run: 1563, Failures: 0, Errors: 0, Skipped: 0` |
| 架构门禁（Maven） | `cd java && mvn -B -o validate` | **BUILD SUCCESS**；E1–E9 全 0 |
| 架构门禁（脚本） | `bash tools/arch-check/arch-check.sh` | **PASS**；九判据全 0 |
| C++ 真实轨 | `ctest --test-dir cpp/build` | **65/65 PASS** |
| C++ stub 轨 | `ctest --test-dir cpp/build_stub` | **60/61**；`hgcp.event_emitter_test` 失败于 `srv.start() != BindStatus::kOk`。`unshare -n` 隔离网络后**仍失败** ⇒ 非端口占用；**C++ 侧本轮零改动**（`git diff --stat -- cpp/` 所示 7 文件均为**会话前既有**工作区改动，本轮未触碰），且**真实轨含 `Test #61 hgcp.event_emitter_test` 并通过** ⇒ 判为 stub 轨特有的**既有残留**，与本变更无关，如实登记不擅修 |
| 重启后监听面 | `ss -ltnp` | HTTP `*:80`、WS `*:81`（缺省 `0.0.0.0`，**可接受外部流量**）；HGCP 仍 `127.0.0.1:39000`（组件通道回环不失守） |
| 就绪与连通 | `health-check.sh all` / `/monitor/component?refresh=1` | **PASS=6 / FAIL=0**；`state=READY`、`paramVersion=18`、`bridgeAssembled/linkEstablished=true`、`ctpLoggedIn=true`、`ctpDegraded=false` |
| 业务抽样 | 登录 + 8 个 REST 端点 | **8/8 HTTP 200 / code=0** |

**落地残留（如实披露，不隐瞒）**：
1. `java/CONTRACTS.md:689`、`:700-701` 仍登记 `hg.web.bind = 127.0.0.1` / 「仅回环」。该文件 `:38-40` 规定唯一写者 `backend-engineer#java`、须投 `question` 落笔 ⇒ **本轮未改**，须由唯一写者按 `CH-026` 同步，否则文档与实现存在字面漂移（不影响编译与门禁）。
2. 共享台账 `TASKS.md` / `BOARD.md` / `TEST_RESULT.md` 的立号与逐轮留痕按「单写者、只追加、可回退」纪律**留给编排者**（`BOARD.md §5` 为倒序逐轮表、`TASKS.md §5.2` 为数值唯一来源），本轮全部结论与门禁证据已落本文件 §8/§9。
3. `hg-biz` 重启后 `reconnectCount` 归零（新进程计数），C++ 侧 `param_version` 17 → 18 证明重连后重新下发全量参数并恢复 `READY`。

---

*本文件为裁定书 + 落地提案；`PROJECT.md` 已按配套提案修订至 v1.4。除本文件与 §9 所列改动外，未改动任何共享台账原文；行号与版本引用以 2026-11-24 实测为准，以实时为准。*
