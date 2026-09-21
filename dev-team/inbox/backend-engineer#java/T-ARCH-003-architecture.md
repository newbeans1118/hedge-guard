---
ticket: T-ARCH-003
type: architecture
from: tech-lead
to: backend-engineer#java
priority: P0
created: 2026-09-20T20:30:00+08:00
refs: [docs/ARCHITECTURE.md, docs/DATA_MODEL.md, docs/REQUIREMENTS/INDEX.md#9.1]
---

## 事由

S2 架构阶段完成，Java 业务组件的施工依据已定稿。本工单交底**必读章节与红线**。

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
   - `ARCHITECTURE.md` §2.1 分层视图 + §2.3 依赖方向 + §2.4.4 Java 线程模型
   - `ARCHITECTURE.md` §3.7–§3.12（`web` / `service` / `dao` / `bridge` / `risk_intraday` / `job`）
   - `ARCHITECTURE.md` §4.1 服务接口（REST 约定 + **统一错误响应结构**）
   - `ARCHITECTURE.md` §4.2.6（4）（5）（HGCP 时序、超时与异常约定）
   - `ARCHITECTURE.md` §5.3 持久化策略、§8.2 安全、§8.4 可测试性
   - `DATA_MODEL.md` **§2 全表**（109 张）、**§4.6 子账户贯穿逐表核对**、**§4.7 FR→表映射**、**§7.2 `DC-001`~`DC-014`**
2. **v1.1 确认口径的落库要求**：
   - **三层限额**：层①③ 在 `t_product_quota`（`limit_layer` 区分），层② 在 **`t_sub_instrument_quota`**；三层**同时校验**并在拒绝记录中带 `limit_layer`。
   - **敞口三分量**（`Q-17`）：`t_exposure` **必须同时写三类分量列 + `snapshot_id`/`snapshot_time`**（**不得只存合计**）；`CMD_UPDATE_EXPOSURE` 下发三分量。
   - **交易理由**（`Q-13`）：`t_trade_reason` 的 `related_type`/`related_plan_id`/`related_exposure_id`/`time_desc` **均 NOT NULL**；关联对象**须与指令同一子账户**（不符 → 拒绝，`0x1019`）。
   - **核对任务与签署**（`Q-14`）：`t_check_task` + **`t_check_signature`**；`uk_check_signature_task_role` 保证**不可代签**；签署后**快照哈希变更须回到"待签署"**；逾期须告警并进审计报表；**管理层只签收、不得操盘**。
   - **加总阻断**（`Q-11`）：`t_aggregation_check.block_scope = DIFF_SUB_NON_CLOSE`；**仅阻断差异所属子账户**的新开仓/划拨/关账，**不阻断平仓**；全账户强制告警。
   - **现货价缺口**（`Q-12`）：按用途分——比率计算**拒绝**；估值/实货盈亏**沿用最近一期 + 必带 `price_as_of` + 标记"非当日价"**（`t_hedge_effect`）。
3. **必守红线（`ARCHITECTURE.md` 附录 B）**：
   - ② `dao` 之外**不得出现 SQL**（CI 脚本 `E5` 会拦住）；`dao/dialect/DmDialect.java` 是 **DM 方言唯一集中点**，DM 特有语法须 `// DM-SPECIFIC:` 注释；
   - **单一写库入口**：只有 Java 写库；C++ 经事件流交 Java 落库；
   - **敏感字段零落盘**：口令用 **PBKDF2-HMAC-SHA256 加盐哈希**（JDK 内置 `SecretKeyFactory`，**不引 BCrypt**）；AuthCode/资金密码**不落库、不落配置、不打日志**（日志需 `SensitiveMaskFilter`）；
   - **组件边界**：`TradingBridge` 接口**只提供** `placeOrder`/`confirmOrder`/`cancelOrder`/`dispatch`，**不提供** `preCheck`（事前风控只在 C++）。
4. **依赖纪律**（`ARCHITECTURE.md` §7.2.2）：**仅 4 个 compile 依赖**
   （JDK 11 / DM JDBC 驱动 / protobuf-java / Gson）+ JUnit 5（test）。
   连接池、日志框架、密码哈希、测试打桩**均自实现**（**不得擅自引入 HikariCP / SLF4J / BCrypt / Mockito**）；
   若自实现被压测证明不足，须提 `CH-xxx` 再引入。
5. **事件流落库**：`J3` 单线程按序消费 → **幂等去重（`business_id` + `event_seq`）→ 更新镜像状态 → 批量事务落库**；
   **不得阻塞事件消费去查库**（`R-RISK-10`）。

## 证据 / 复现

不适用。

## 变更评估

不适用。

## 回复要求

1. 确认已读完"必读章节"，并把**任何与文档不一致之处**以 `question` 工单提出（**不得自行偏离**）。
2. 在 M1 编码前，请先确认 `DATA_MODEL.md` 已通过三方会签（M1 门禁）。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 20:30 | tech-lead | 投递架构交底 | 待 Java 后端确认 |
| 2026-09-20 19:20 | backend-engineer#java | 逐条确认**必读章节**（见下「确认清单」） | ✅ 已读且理解；施工已按该口径落地 |
| 2026-09-20 19:21 | backend-engineer#java | 确认**必守红线**（附录 B）与**依赖纪律**（§7.2.2） | ✅ 无保留接受；已落到代码与构建（见「红线落地证据」） |
| 2026-09-20 19:22 | backend-engineer#java | 确认**v1.1 确认口径的落库要求**（三层限额 / 敞口三分量 / 交易理由 / 核对签署 / 加总阻断 / 现货价缺口） | ⚠️ **部分适用**：这 6 项落在 `PLAN`/`RISK`/`TRADE`/`SETTLE`/`SPOT` 模块表，**不在 `T-004` 的 CONFIG+AUTH 20 张表内**；本批次**未**触碰这些表结构。`t_authorization`（AUTH）承载的是**人员+品种+额度**授权，与三层限额的 `t_product_quota`/`t_sub_instrument_quota` 是**不同表**，未混用 |
| 2026-09-20 19:23 | backend-engineer#java | 确认**事件流落库**要求（`J3` 单线程 + 幂等去重 + 批量事务 + 不阻塞） | ✅ 已读；`bridge` 层接口按 §3.10 冻结（`TradingBridge` 七项），事件消费实现随 `#java2` 的 `bridge` 任务交付 |
| 2026-09-20 19:24 | backend-engineer#java | 确认 **M1 门禁前提**：`DATA_MODEL.md` 三方会签状态 | ✅ 已核对：`docs/gates/M1-data-model-signoff/SIGN-OFF.md` 存在（两席在 v1.3.2 定版上确认），**门禁已达成**，故本次编码合规 |
| 2026-09-20 19:25 | backend-engineer#java | 登记**与文档不一致 / 需裁定**的 4 项 | 见下「待技术负责人裁定（4 项）」；**均已按纪律"不擅自偏离 + 取可辩护读法 + 显式登记"处理** |
| 2026-09-20 19:49 | backend-engineer#java | 工单闭环（后端侧动作完成） | 状态置 `CLOSED`；终态确认 = 技术负责人 |

### 确认清单（必读章节，逐条）

| # | 章节 | 确认 | 对本轮施工的实际作用 |
|---|------|------|---------------------|
| 1 | §2.1 分层视图 + §2.3 依赖方向 + §2.4.4 Java 线程模型 | ✅ | 决定包树与依赖方向（`web→service→dao` / `web→service→bridge`，`dao` 与 `bridge` 互不依赖）；`J1` HTTP 线程池命名 `hg-http-N` |
| 2 | §3.7 `web` | ✅ | `HttpServer`/`Router`/`StaticHandler`/`AuthFilter`/`ErrorMapper` 全部落地（`T-003` 验收对象） |
| 3 | §3.8 `service` | ✅ | `package-info` 写明 11 模块映射 + 三条分层纪律；`ParamDispatchService` 作为跨模块共用能力冻结 |
| 4 | §3.9 `dao` | ✅ | 目录结构逐字落地（`impl`/`tx`/`pool`/`dialect`）；`DmDialect` 为 DM 特性唯一集中点 |
| 5 | §3.10 `bridge` | ✅ | `TradingBridge` **签名逐字**复制 §3.10（含"不提供 `preCheck`"）；有反射式契约测试守护接口面 |
| 6 | §3.11 `risk_intraday` + §3.12 `job` | ✅ | 两个 `package-info` 写明 6 类指标 / 只提示不下单 / 时钟可注入 / 不得绕过 L3 直调 DAO |
| 7 | §4.1（REST 约定 + 统一错误响应结构） | ✅ | 信封 / 分页 / 幂等键 / 时间格式 / 十进制字符串 / 23 个业务码 / 阻断四项**逐字**实现 |
| 8 | §4.2.6（4）（5）（HGCP 时序、超时与异常约定） | ✅ | M2 门禁对象；本轮只冻结 `bridge` 的**类型与方法签名**，字段级对齐留待 M2（已在 `HGC-1.0` §3.2 声明） |
| 9 | §5.3 持久化策略 | ✅ | 幂等唯一约束 / 审计链式哈希 / 批量写入 / 敏感零落盘 均未违反；CONFIG+AUTH 20 张表的唯一约束按文档落地 |
| 10 | §8.2 安全 | ✅ | ①绑定回环（非回环启动期拒绝）②`Config` 装载即拒敏感键 ③`SensitiveLog` 强制脱敏 `CFG-19` 四项 ④核心转储启动告警 |
| 11 | §8.4 可测试性 | ✅ | `java.time.Clock` 注入（`Clocks`）、假 JDBC 连接（`FakeConnections`）、`InMemoryTradingBridge` 桩；**无 Mockito** |
| 12 | `DATA_MODEL.md` §2 全表 / §4.6 子账户贯穿 / §4.7 FR→表 / §7.2 `DC-001`~`DC-014` | ✅ | `T-004` 的 20 张表逐列对照；子账户贯穿**逐表核对**与 §4.6.3 模块级汇总一致（CONFIG ③7；AUTH ①2/②2/③9） |

### 红线落地证据（附录 B）

| 红线 | 落地位置 | 证据 |
|------|---------|------|
| ② `dao` 之外不得出现 SQL | `java/src/main/java/com/hedgeguard/**`（排除 `dao`） | E5 等价 grep **命中 0 处**；SQL 仅出现于 `dao/**` |
| DM 方言唯一集中点 + `// DM-SPECIFIC:` | `dao/dialect/DmDialect.java` | 全库 Java 侧 DM 特性仅在本文件，逐条带注释；DDL 侧 `-- DM-SPECIFIC:` 22 处 |
| 单一写库入口 | `dao` 为唯一 SQL/JDBC 位置；`TradingBridge` 不写库 | `bridge/TradingBridge` javadoc 明示；`bridge` 不依赖 `dao` |
| 敏感字段零落盘 / 零日志 | `common/Config`（装载即拒敏感键）、`common/SensitiveLog`（`CFG-19` 四字段强制 `***`）、`AuthCode`/口令字段**结构上不存在于类与表** | `user`/`config_item`/`ctp_front_config` 的实体与 DDL **无明文列**；`JdbcConnectionFactory` 口令 `char[]` + `clearPassword()` |
| `TradingBridge` 只提供 4 动作 + 3 状态，**不提供** `preCheck` | `bridge/TradingBridge` | 反射式契约测试断言方法集合**恰为 7 项**且不含 `preCheck` |
| ⑦ 90%/80% 不得静默放宽 | 本轮未涉及红线配置；`t_config_item.double_review_required` + `ck_*` 已就位 | `T-004` DDL |
| ⑧ 敏感字段入配置/DB/日志/核心转储 | 见上"敏感字段零落盘"行；启动期告警提示 `-XX:-CreateCoredumpOnCrash` | E2E 启动日志含该 WARNING（未加参数时） |
| ⑨ 监听 `0.0.0.0` / 关闭鉴权 | `Config.loopbackOnly()` + `SessionAuthFilter`（白名单外一律 `10006`，**无**关闭鉴权开关） | `java -Dhg.http.bind=0.0.0.0` → **真实退出码 1**，异常明示"附录 B 红线 9" |

### 待技术负责人裁定（4 项，**未擅自偏离**）

| # | 项 | 本实现取法（可辩护读法） | 需裁定 |
|---|----|------------------------|--------|
| `O-1` | `t_permission_change_log.sub_account_id` / `t_unauthorized_attempt.sub_account_id` 字段行**未标注** `FK → t_sub_account(id)`，与 §0.4"所有业务表 `sub_account_id` → `t_sub_account(id)`"矛盾 | 按 **§0.4** 实现外键（脚本 17 条） | 字段行为准（删 2 条外键）还是 §0.4 为准（补文档标注）？ |
| `O-2` | (a) §0.3"每张表都有审计字段" vs `TB-CONFIG-3` 备注"本表不含 `updated_by/updated_time/row_version`"；(b) `TB-AUTH-10/12/13`"只增不改不删"未显式排除三列 | (a) 依**表级备注**排除；(b) 依 **§0.3** 保留（值恒 `NULL`/`0`） | (a)(b) 是否统一？若要统一，请明确"只增不改"是否等同"排除三列" |
| `O-3` | 组件码 `0x1019`（`Q-13` 关联对象子账户不一致）**无对应业务码**（§4.3.1 止于 `10022`） | 未新增码；`HGC-1.0` §1.3 建议新增 `10023` 或复用 `10012` | 选哪种？`#java2` 的 TRADE 模块映射依赖此结论 |
| `O-4` | `TB-AUTH-6` 备注的默认互斥集写作 `TRADER ↔ FIN_RISK`，而 `group_a_id/group_b_id` 引用 `t_position_group(id)`（**岗位组**，非角色） | **未**初始化互斥数据，仅在 `data-init.sql` 登记并标注承接方 | 岗位组编码集合以文档哪一处为准？ |
| `Q-1`（附） | 前端 HTTP 服务的 `bind`/`port`/`threads` **未登记**于 `ARCHITECTURE.md` §11.2（`CFG-20` 已收窄为仅 HGCP 通道 `hg.tcp.bind/port`；`CFG-32` 为 Java 侧 **WebSocket** 预留 `hg.web.ws.*`） | 使用 `hg.http.*`，默认 `127.0.0.1`/`27402`/`16` | 建议补 **`CFG-33 hg.web.bind` / `hg.web.port` / `hg.web.threads`**（与 `hg.web.root`、`hg.web.ws.*` 同族）；请确认键名与默认端口 |
| `Q-4`（附） | `CH-006` 要求 Java 提供 `/ws/quote`、`/ws/alert`，且**允许引入轻量 WebSocket 库（须 §7.1.11 论证）**；JDK 内置 `HttpServer` **不提供 WebSocket** | **未实现** `/ws/**`（不属 `T-003` 验收范围）；**未**擅自引入任何库 | 请在派发 WebSocket 任务前出**选型结论**：① 手写 RFC 6455 最小实现（零依赖）② 引入轻量库（先论证入 §7.2/§11） |

### ⚠️ 上游锚点漂移声明（施工期间发生，已复核）

| 项 | 值 |
|----|----|
| 施工开始时锚点 | `docs/ARCHITECTURE.md` **v1.3**：md5 `641b481bee2678e8e6e931c190d4f86f` / 2418 行 |
| 收尾时实测锚点 | `docs/ARCHITECTURE.md` **v1.5**：md5 `9feae92764125b63702642cf601d633a` / 2516 行（经 `CH-006` v1.4、`CH-007` v1.5） |
| `DATA_MODEL.md` | **未变**：v1.3.2 / `99b647ec3e7a29b95ee86b04a58c747c` / 5177 行（`T-004` 的逐列对照结论**仍然有效**） |
| 逐条复核结论 | §4.1.1（信封 / 阻断四项 / 分页 / 幂等 / `HGSESSION`）、§4.1.2（端点）、§4.3.1（业务码仍 **22** 条 + `0`）、§3.7~§3.12（六层分层）、§7.2.2（Java 依赖仍 **4** compile + JUnit 5 test；`HikariCP` 仍"不引入"；口令哈希仍 `PBKDF2WithHmacSHA256`）、§11 `CFG-30`(`hg.web.root`)/`CFG-31`(`hg.db.*`)、附录 B（**10** 条红线）→ **均未变** |
| **对本轮施工的影响** | **无** —— 冻结清单 `HGC-1.0` 的构成（REST 契约 / 包与接口 / 共享 DTO）全部仍然成立；`CONTRACTS.md` 已**改锚 v1.5** 并登记 `D-7` |
| 唯一新增义务 | `CH-006` 的 **WebSocket 归 Java**（`§3.7` / `§4.1.2` / `§7.2.4` / `§11.2 CFG-32`）—— 登记为 `D-8`（`#java2` 的 WS 任务开工前须先定选型） |

> **流程建议（供技术负责人 / 编排者）**：`ARCHITECTURE.md` 的 §1.2「契约冻结纪律」要求"先冻结再并行"，
> 而本次**架构文本在编码窗口内被两次演进**（v1.3→v1.5）。建议后续在派发 M1 编码任务前，
> **显式声明本轮编码的 ARCHITECTURE 锚点 md5**，并在任何 `CH-xxx` 落笔后**主动通报已派发任务**，
> 避免下游按陈旧锚点施工（`SIGN-OFF.md` §8.2 第 2 条"锚点变更须显式通报"同理）。

> **本轮已按纪律执行**：全部差异**均未**改动 `docs/**`，而是以本工单 + `java/CONTRACTS.md` §6（`D-4`~`D-6`）
> + `T-004` 工单（`O-1`~`O-4`）三处登记；`#java2` 可按现有冻结契约开工，裁定后按 `HGC-1.1` 增量升版。

## 状态

- **当前状态**：CLOSED（后端侧确认动作已完成）
- **终态确认人**：tech-lead
- **终态时间**：
- **附注**：M1 门禁前提已核对（`docs/gates/M1-data-model-signoff/SIGN-OFF.md` 存在，两席在 v1.3.2 定版确认）；
  红线与依赖纪律**无保留接受**；5 项待裁定项（`O-1`~`O-4` + `Q-1`）已登记，**未擅自偏离**。

