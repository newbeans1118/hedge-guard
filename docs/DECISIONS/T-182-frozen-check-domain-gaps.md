# T-182 冻结 CHECK 域承载缺口技术裁定书

> **裁定单编号**：`T-182`（来源：`docs/TASKS.md`〔`T-181` 行后〕；用户指令「派发技术席 001b/002 缺口任务」2026-10-05）
> **裁定席**：tech-lead ｜ **实例**：`be-t182`
> **裁定日期**：2026-10-06
> **性质**：**纯裁定 + 提案**（不动代码、不动 DDL、不动 `DATA_MODEL`/`proto`/`CONTRACTS`/`ARCHITECTURE` 冻结件、不动 `TASKS`/`BOARD`）
> **依据**：`T-058` 装配级 e2e 五缺陷（`te-t058` 发现 + `te-verify-t058` 独立复现）/ `T-178` 退修披露③ / 双侧复核结论
> **上游制品（引用时点 md5，以实时为准）**：
> - `docs/DATA_MODEL.md` v1.3.5 = d17bce0c37e513a0a236304b9cb3f25d（5252 行）
> - `docs/DEPLOY/schema/m2_settle.sql` = 9cb586671814caca0cbcd6846ac99698（781 行）
> - `docs/DEPLOY/schema/m3_risk.sql` = f9e755edb75f78c93bcd4b64f3e2f327（507 行）
> - `docs/ARCHITECTURE.md` = be7a415ce3a32aa9709004990d85871b
> - `docs/REQUIREMENTS/SETTLE.md` = b1ccc8f0b40ba008e9181bef2335edae ｜ `CONFIG.md` = 159e0d489ea12055a1da06b6f90f52dc ｜ `RISK.md` = dc546e97048a7f45f1c423a5bb2a7fbc

---

## 1. 结论速览

| 缺口 | 现象 | 裁定方案 | 定级 | 前置 |
|------|------|---------|------|------|
| **001b**（高） | `t_ctp_settle_batch.parse_status` 冻结域 `{OK, PARTIAL, FAILED}` 无「未到达」承载，服务写 `PENDING_DATA` 被 CHECK 拒 | **(a) 扩 CHECK 域**，新增取值 **`NOT_ARRIVED`** | **L3**（冻结域变更） | **须 `CH-021`（part 1）+ 用户批准** |
| **002**（中） | `t_param_dispatch.dispatch_type` 冻结域 8 值 vs `ParamChange.Type` 枚举 11 值，`SET_READY_STATE`/`TRIGGER_REPLAY`/`RELOAD` 无域对应 | **(a) 扩 CHECK 域**，新增取值 **`SET_READY_STATE`、`TRIGGER_REPLAY`、`RELOAD`** | **L3**（冻结域变更） | **须 `CH-021`（part 2）+ 用户批准** |

- 两缺口**同性质**（均为冻结 CHECK 域对既有 FR 承载不足），与编排者「归并单」一致 ⇒ 建议**合并为一份 `CH-021`**（含 part 1 / part 2 两个变更项），一次提请用户批准；`CH-021` 编号为**全局序推定值**（现行最大 = `CH-020`），**待编排者登记确认**。
- 两项 CH 均**不触碰 HGCP 线格式 / `proto` / `CONTRACTS` / `ARCHITECTURE`**：仅动 `DATA_MODEL` 两个取值域 + 两份建表脚本 + 服务侧常量/文案 + 测试。
- 实施任务建议**归并为一单**（理由见 §5：两项均须改同一冻结件 `DATA_MODEL.md`，必须单写者原子落地）。

---

## 2. 缺口 001b：`parse_status` 无「未到达」承载

### 2.1 事实（全部行号级实测）

| # | 事实 | 出处（行号） |
|---|------|-------------|
| F1 | 冻结域 = `{OK, PARTIAL, FAILED}` | `DATA_MODEL.md:2386`（`parse_status` 行）；DDL `m2_settle.sql:44`（`ck_t_ctp_settle_batch_parse_status CHECK (parse_status IN ('OK','PARTIAL','FAILED'))`） |
| F2 | 服务在**未到达分支**写 `PENDING_DATA` | `SettleService.java:156`（`PARSE_PENDING = "PENDING_DATA"`）、`:296`（赋值）、`:307`（返回消息文案） |
| F3 | 服务在**到达分支**写 `OK`（001a 已由 `T-178` 修复） | `SettleService.java:154`（`PARSE_PARSED = "OK"`）、`:309`、`:312-313` |
| F4 | 幂等守卫以 `is_confirmed='Y'` 判定 no-op | `SettleService.java:283`；DDL `m2_settle.sql:43`（`uk_ctp_settle_batch_date(trade_date, source_identifier)`） |
| F5 | `is_confirmed` 语义 = 「是否已确认（**重复接收以最后一次确认为准并留痕**）」 | `DATA_MODEL.md:2390`；DDL CHECK `m2_settle.sql:45`（`{Y,N}`） |
| F6 | `parse_error` 语义 = 「解析错误说明（**未到达/不完整/格式异常**）」 | `DATA_MODEL.md:2387` |
| F7 | 索引 `idx_ctp_settle_batch_status(parse_status, trade_date)` 用途 = 「巡检『**待重试**』批次（结算任务的阻塞点）」 | `DATA_MODEL.md:2397`；DDL `m2_settle.sql:59` |
| F8 | FR 要求「数据未到达时**不得**用推算数据替代，结算任务置为待重试并**记录未达原因**」 | `REQUIREMENTS/SETTLE.md:91`（处理规则） |
| F9 | FR 输出：失败 → 明确错误（**未到达** / 数据不完整 / 格式异常）并保留重试入口 | `REQUIREMENTS/SETTLE.md:92` |
| F10 | FR 验收 2：数据未到达时结算任务处于「待结算」或明确的重试状态，不产生基于推算的结算结果 | `REQUIREMENTS/SETTLE.md:98` |
| F11 | FR 验收 4：接收记录中含接收时间与来源标识 | `REQUIREMENTS/SETTLE.md:100` |
| F12 | TC-SETTLE-1 判定方式 = **查数据库**（「任务置『待重试』并记录未达原因」） | `TEST_PLAN/SETTLE.md:59`、`:79-81` |

**缺陷定性**：`PENDING_DATA` 是「未到达」态的合法业务表达（FR 明列该失败原因），但冻结 CHECK 域**未收录**该取值 ⇒ 在真实 DDL 下写库被拒（`ck_t_ctp_settle_batch_parse_status`）⇒ 未到达分支**无法留痕**，FR-SETTLE-1 验收 2（重试状态可查）与验收 4（接收记录）在 DB 级**不可达成**。`T-178` 已修 001a（字面量 `PARSED`→`OK`），001b（域缺口本身）遗留本单。

### 2.2 候选方案逐案评估

#### (a) 扩展 CHECK 域，新增「未到达」取值

- **做法**：`DATA_MODEL.md:2386` 取值域与 `m2_settle.sql:44` CHECK 同步新增一个取值（推荐 `NOT_ARRIVED`）；`§7` 登记版本行（v1.3.6）与字段级 `DC-042`。
- **理由（成立）**：
  1. **直接闭合 F8/F10/F12**：DB 级可判定「未到达」态，TC-SETTLE-1「查数据库」判据成立。
  2. **保持既有三值语义纯净**：`OK`（完整到达）/ `PARTIAL`（不完整）/ `FAILED`（格式异常）含义不变；「未到达」是**第四种独立事实**（未发生解析），与 F6 的「未到达/不完整/格式异常」三分一一对应。
  3. **维持 F7 索引语义**：`idx_ctp_settle_batch_status` 的「待重试批次」可由 `NOT_ARRIVED` 精确巡检，不再与「已发生解析失败」混淆。
- **影响面**：见 §4（CH-021 part 1）。`DATA_MODEL`（取值域行 + §7.1/§7.2）、`m2_settle.sql`（CHECK + 抬头 md5 引用）、`SettleService`（常量值 + javadoc）、3 处测试期望值。
- **代价**：**冻结域变更 ⇒ L3，须 CH + 用户批准**（本单已备 CH-021）。

#### (b) 以 `is_confirmed='N'` 承载「未到达」

- **做法**：不改域；「未到达」行以 `is_confirmed='N'` 标识，`parse_status` 另填域内值（只能取 `FAILED`），`parse_error` 记未达原因。
- **评估：不兼容 / 不可承载**，理由（逐条对照工单判据「语义可合并且不破坏既有 uk/查询语义」）：
  1. **`is_confirmed` 语义正交，不可合并**：F5 明定其语义为「是否已**确认**（重复接收以最后一次确认为准）」，即**幂等重收的最终性**；「数据是否**到达**」是另一根轴。二者存在取值重合区——「已接收但未确认」（如未来 `PARTIAL` 分支）与「未到达」在 `is_confirmed` 上**同为 'N'** ⇒ 「未到达」**不可由 `is_confirmed='N'` 反推**，承载不成立。
  2. **单独无法承载**：`parse_status` 为 `NOT NULL` + CHECK（F1）⇒ 即便用 `is_confirmed`，仍**必须**填入域内值。可选项只有 `FAILED`，而「未到达」时**未发生解析**，写 `FAILED` 是**对 DB 声明一个未发生的事实**，违背 R-SETTLE-3/R-SETTLE-10 的审计自证要求。
  3. **破坏 F7 查询语义**：巡检「待重试批次」的索引列是 `parse_status`；承载后「未到达」混入 `FAILED`，「可重试」与「解析失败（可能终态）」在 DB 级不可分。
  4. **破坏 F6 三分可判定性**：`parse_error` 是自由文本；仅靠文本区分「未到达/不完整/格式异常」⇒ **DB 级不可判**，TC 判据（查数据库）失去确定性。
- **结论**：uk（`trade_date, source_identifier`）语义本身不受损，但**字段语义与查询/索引语义均受损** ⇒ **如实判「不兼容」**。

#### (c) 其它方案

| 方案 | 评估 |
|------|------|
| (c1) **不为「未到达」写批次行**，改由审计/应用日志承载 | **否决**：违反 F10/F12（须 DB 可查的「待重试」状态）与 F11（接收记录须留痕）；日志非接收记录承载。 |
| (c2) **仅用 `FAILED` + `parse_error` 文本区分，不新增取值** | **否决**：与 (b) 同源缺陷——DB 级不可判「未到达」，且对未发生解析记 `FAILED` 事实不实。 |
| (c3) **新增专用列/表承载**（如 `is_arrived` 或独立「结算任务」表） | **否决**：较扩域更重的结构变更，仍须 CH 且更贵；且 `TB-SETTLE-1..16`（`DATA_MODEL.md:2376/2401/2430/2455/2482/2523/2553/2588/2616/2644/2681/2717/2745/2775/2823/2868`）**不存在**可承载「结算任务重试态」的既有表，凭空增表超出本缺口所需。 |

### 2.3 裁定

> **采纳 (a)**：将 `t_ctp_settle_batch.parse_status` 冻结域由 `{OK, PARTIAL, FAILED}` 扩为 **`{OK, PARTIAL, FAILED, NOT_ARRIVED}`**，「未到达」以 **`NOT_ARRIVED`** 承载。
>
> **新增取值命名**：推荐 **`NOT_ARRIVED`**（与 `PARTIAL`=不完整 / `FAILED`=格式异常**并列描述事实**，且与 F6 原文「未到达」逐词对应）。`CH-021` 文本已按该值固化。
> **备选**（若编排者/**用户**要求零代码改动）：沿用现服务字面量 `PENDING_DATA`。**不予推荐**：其名含 `_DATA` 后缀、且与 `t_param_dispatch.dispatch_status` 的 `PENDING` 在跨表阅读时易混；冻结枚举键应一次取得自洽名。**最终以用户批准文本为准**。

---

## 3. 缺口 002：`dispatch_type` 三类指令无域承载

### 3.1 事实（全部行号级实测）

| # | 事实 | 出处（行号） |
|---|------|-------------|
| G1 | 冻结域 8 值（**无** `CMD_` 前缀）：`LOAD_RISK_PARAMS / UPDATE_WHITELIST / UPDATE_QUOTA / UPDATE_POSITION_LIMIT / UPDATE_RISK_THRESHOLD / UPDATE_PERMISSION / UPDATE_EXPOSURE / SET_TRADING_SUSPEND` | `DATA_MODEL.md:2989`；DDL `m3_risk.sql:65-66`；列注释 `m3_risk.sql:75` |
| G2 | `ParamChange.Type` 枚举 **11 值**（带 `CMD_` 前缀） | `ParamChange.java:20-54` |
| G3 | 服务以「去 `CMD_` 前缀」映射持久化；注释**自认**三值「在冻结域内无对应取值（域缺口，生产未构造，另行登记，不在本单范围）」 | `ParamDispatchServiceImpl.java:719-727` |
| G4 | 三类**无域对应**：`CMD_SET_READY_STATE` / `CMD_TRIGGER_REPLAY` / `CMD_RELOAD` | 由 G1/G2 差集；编码器已支持三类（`ParamChangeEncoder.java:46-51`）；常量 `HgcpConstants.java:57/59/61` |
| G5 | ARCH 冻结三类为控制指令：21 `0x0119 CMD_SET_READY_STATE`「启停（业务开关）」/ 22 `0x011A CMD_RELOAD`「重载配置并重新登录 CTP」/ 23 `0x011B CMD_TRIGGER_REPLAY`「手工触发重放」 | `ARCHITECTURE.md:828-830`（§4.2.3）；proto 映射 `:1125-1127`（§4.2.8） |
| G6 | `TB-RISK-2` 用途 = 「Java → C++ 的控制指令下发与回执（`FR-RISK-9`、`FR-RISK-10`、`FR-CONFIG-6`、`R-CONFIG-3`）」 | `DATA_MODEL.md:2983` |
| G7 | **FR-RISK-10 输入**明列「变更类型（白名单/额度/阈值/启停/**重载**/**触发重放**）、变更内容、变更操作人」 | `REQUIREMENTS/RISK.md:238` |
| G8 | **FR-RISK-10 处理规则**：「下发须留痕（操作人、时间、变更内容、**回执结果**）」；**验收 4**：「下发过程有留痕，含操作人、时间与**回执**」 | `REQUIREMENTS/RISK.md:240`、`:249` |
| G9 | `R-CONFIG-3`：「下发结果须**留痕**」；`FR-CONFIG-6` 输入仅「品种白名单、额度、阈值、启停」、验收 4 留痕含「参数类型」 | `REQUIREMENTS/CONFIG.md:334`、`:178`、`:188` |
| G10 | 生产**未构造**三类（`src/main` 的 `new ParamChange(...)` 仅 CMD_SET_TRADING_SUSPEND / CMD_UPDATE_QUOTA / CMD_UPDATE_PERMISSION） | `ParamDispatchServiceImpl.java:354/427/481/608` |
| G11 | 测试以**假 DAO** 构造 `CMD_RELOAD`（假 DAO 不执行 CHECK ⇒ **掩盖**越域） | `ParamDispatchServiceImplTest.java:134/142/162/320` |
| G12 | `dispatch()` 对任意已知类型先 `persistPending` 落库（真 DAO 下三类将撞 CHECK） | `ParamDispatchServiceImpl.java:268-291` |

**缺陷定性**：`dispatch_type` 是 `TB-RISK-2`（G6）的**下发留痕主键维度**，而 G7/G8 明确要求「重载/触发重放」的下发**留痕含回执**。当前冻结域缺失三类取值 ⇒ 一旦生产构造（G10 尚未），`persistPending` 将撞 CHECK（G12）；测试用假 DAO（G11）掩盖，属「**域 ↔ 枚举 ↔ FR 三方不一致**」。

**与工单背景的一处细化订正**：002 的**主 FR 依据是 `FR-RISK-10`**（`RISK.md:238` 明含重载/触发重放），而非 `FR-CONFIG-6`（`CONFIG.md:178` 输入**不含**重载/触发重放，其「启停」对应域内既有 `SET_TRADING_SUSPEND`）。`FR-CONFIG-6` 验收 4 的「参数类型」约束不到这三类 ⇒ 工单把 002 并列挂在 `FR-CONFIG-6` 下**方向对但依据偏**，本单以 `FR-RISK-10` 为主依据。`ARCHITECTURE.md:1048` 亦印证：`CMD_UPDATE_*` 族统一经 `ParamDispatchService` 下发。

### 3.2 候选方案逐案评估

#### (a) 扩展 CHECK 域，补齐三类取值

- **做法**：`DATA_MODEL.md:2989` 与 `m3_risk.sql:65-66` 同步新增 **`SET_READY_STATE`、`TRIGGER_REPLAY`、`RELOAD`**（去 `CMD_` 前缀，与既有 8 值命名法一致）；`§7` 登记 `DC-043`。
- **理由（成立）**：
  1. **G7/G8 明文要求**：`FR-RISK-10` 把「重载/触发重放」列为变更类型并要求**留痕含回执**；`TB-RISK-2`（G6）是唯一承载「下发 + 回执」（`dispatch_status/ack_time/ack_detail`，`DATA_MODEL.md:2995-2997`）的表 ⇒ 域**必须**收录，否则 FR 不可达成。
  2. **与枚举/协议对齐（G2/G5）**：补后域 = 11 值 = `ParamChange.Type` 11 值，一一对应；服务现有去前缀映射（G3）对其自然成立，**行为零变化**。
  3. **零协议影响**：三类线格式（`0x0119/0x011A/0x011B`）与 proto 早已冻结（G5），本项纯 DB 取值域对齐，**不触 HGCP**。
- **影响面**：见 §4（CH-021 part 2）。`DATA_MODEL`（取值域行 + §7.1/§7.2）、`m3_risk.sql`（CHECK + COMMENT + 抬头 md5）、`ParamDispatchServiceImpl` javadoc（`:724` 删除「域缺口…另行登记」表述）；建议补「三类落真实 DAO」回归用例（去掩盖，见 §5）。
- **代价**：**冻结域变更 ⇒ L3，须 CH + 用户批准**。

#### (b) 服务侧拒绝三类落库（留痕于错误/审计而非 `dispatch` 表）

- **做法**：`dispatch()` 对三类提前 fail-fast（不 `persistPending`），留痕改落审计/日志。
- **评估：不采纳**。核 `FR-CONFIG-6`：其验收 4 的「参数类型」不含三类，**单看 FR-CONFIG-6 确实允许**不落 `t_param_dispatch`；**但 `FR-RISK-10` 验收 4（G8）不允许**——它要求重载/触发重放的下发留痕含**回执**，而回执（`ack_time/ack_detail/status`）**只有 `t_param_dispatch` 承载**，审计/日志表无此列。故 (b) **使 `FR-RISK-10` 验收 4 不可达成**，与 G6 的表用途冲突。
- **附带风险**：即使只对 `CMD_SET_READY_STATE`（G7 未点名）走 (b)，也会使同族三指令在留痕上**分叉**（两条落表、一条走日志），增加实现与审计复杂度，无收益。

#### (c) 其它方案

| 方案 | 评估 |
|------|------|
| (c1) **把三类映射到既有域内值**（如 `SET_READY_STATE`→`SET_TRADING_SUSPEND`） | **否决**：语义错——`SET_TRADING_SUSPEND` 是「限制新开仓」（`ARCHITECTURE.md:827`），与组件启停/重载/重放无关；伪造留痕违反审计自证。 |
| (c2) **新增独立表承载生命周期指令** | **否决**：G6 明定 `TB-RISK-2` 承载 `FR-RISK-10`；另起表使下发/回执分表，违背「统一下发编排」（`ARCHITECTURE.md:520`）。 |
| (c3) **收窄 `ParamChange.Type`，删除三类** | **否决**：G5 已冻结三类为控制指令、proto 已生成 `CmdSetReadyState/CmdReload/CmdTriggerReplay`、编码器已支持（G4）⇒ 删枚举破坏线格式能力，且与 G7 冲突。 |

### 3.3 裁定

> **采纳 (a)**：将 `t_param_dispatch.dispatch_type` 冻结域由 8 值扩为 **11 值**，新增 **`SET_READY_STATE`、`TRIGGER_REPLAY`、`RELOAD`**（与 `ParamChange.Type` 逐值对齐，命名沿用既有「去 `CMD_` 前缀」法）。
> **主 FR 依据 = `FR-RISK-10`（`RISK.md:238`/`:249`）**；`FR-CONFIG-6` 仅为次级追溯（不覆盖三类）。

---

## 4. `CH-021` 草案（可直接提请用户批准）

> **编号**：`CH-021`（**全局序推定**：现行最大 = `CH-020`；**待编排者登记确认**）
> **日期**：2026-10-06 ｜ **发起席**：tech-lead（实例 `be-t182`）
> **定级**：**L3**（变更 `DATA_MODEL.md` 冻结 CHECK 取值域，属项目冻结对象实质变更）
> **拟批准人**：**用户**
> **性质**：冻结 CHECK 域承载补齐（非勘误——改冻结取值域、改服务可写值集）

### 4.1 变更对象

| 项 | 冻结对象 | 位置 |
|----|---------|------|
| **part 1** | `t_ctp_settle_batch.parse_status` CHECK 取值域 | `DATA_MODEL.md:2386`；DDL `m2_settle.sql:44` |
| **part 2** | `t_param_dispatch.dispatch_type` CHECK 取值域 | `DATA_MODEL.md:2989`；DDL `m3_risk.sql:65-66`（注释 `:75`） |
| 伴随 | `DATA_MODEL.md` §7.1 版本行（v1.3.5 → **v1.3.6**）+ §7.2 字段级登记（新增 **`DC-042`**〔part 1〕/ **`DC-043`**〔part 2〕，编号推定待登记） | `DATA_MODEL.md:5046`、`:5060` |

### 4.2 前后对照

**part 1 — `t_ctp_settle_batch.parse_status`**

```
变更前 CHECK：parse_status IN ('OK', 'PARTIAL', 'FAILED')
变更后 CHECK：parse_status IN ('OK', 'PARTIAL', 'FAILED', 'NOT_ARRIVED')
```
| 取值 | 变更前 | 变更后 | 含义 |
|------|-------|-------|------|
| `OK` | 有 | 有（不变） | 完整到达并解析成功 |
| `PARTIAL` | 有 | 有（不变） | 数据不完整 |
| `FAILED` | 有 | 有（不变） | 格式异常 / 解析失败 |
| **`NOT_ARRIVED`** | — | **新增** | **数据未到达（未发生解析），置待重试** |

**part 2 — `t_param_dispatch.dispatch_type`**

```
变更前 CHECK：dispatch_type IN ('LOAD_RISK_PARAMS','UPDATE_WHITELIST','UPDATE_QUOTA',
              'UPDATE_POSITION_LIMIT','UPDATE_RISK_THRESHOLD','UPDATE_PERMISSION',
              'UPDATE_EXPOSURE','SET_TRADING_SUSPEND')
变更后 CHECK：dispatch_type IN (…原 8 值不变…, 'SET_READY_STATE','TRIGGER_REPLAY','RELOAD')
```
| 取值 | 变更前 | 变更后 | 对应线格式（`ARCHITECTURE.md` §4.2.3） |
|------|-------|-------|------|
| 原 8 值 | 有 | 有（不变） | `0x0111`/`0x0112`…/`0x0118` |
| **`SET_READY_STATE`** | — | **新增** | `CMD_SET_READY_STATE` = `0x0119`（启停业务开关，`:828`） |
| **`RELOAD`** | — | **新增** | `CMD_RELOAD` = `0x011A`（重载配置并重登 CTP，`:829`） |
| **`TRIGGER_REPLAY`** | — | **新增** | `CMD_TRIGGER_REPLAY` = `0x011B`（手工触发重放，`:830`） |

### 4.3 理由

1. **part 1**：`FR-SETTLE-1` 处理规则与验收 2/4（`SETTLE.md:91/98/100`）要求「数据未到达」态可落库、可查、可重试；`parse_error` 语义（`DATA_MODEL.md:2387`）已明列「未到达」。冻结域缺该取值 ⇒ 未到达分支无法留痕（`te-verify-t058` 独立复现，`T-178` 披露 001b 遗留）。
2. **part 2**：`FR-RISK-10` 输入与验收 4（`RISK.md:238/249`）要求「重载/触发重放」下发留痕含回执；`TB-RISK-2` 用途（`DATA_MODEL.md:2983`）指向 `FR-RISK-10`，为唯一回执承载表。域缺三值 ⇒ 与 `ParamChange.Type`（`ParamChange.java:20-54`）及已冻结指令族（`ARCHITECTURE.md:828-830`）不一致。
3. **合并理由**：两项同性质（冻结 CHECK 域 ⊆ 既有 FR 承载不足），且均须改同一冻结件 `DATA_MODEL.md`，合并为一份 CH 以单写者原子落地。

### 4.4 影响面

| 制品 | part 1 | part 2 |
|------|--------|--------|
| `docs/DATA_MODEL.md` | `:2386` 取值域；§7.1 版本 v1.3.6；§7.2 `DC-042` | `:2989` 取值域；§7.1 版本 v1.3.6；§7.2 `DC-043` |
| `docs/DEPLOY/schema/m2_settle.sql` | `:44` CHECK；`:4` 抬头引用 DATA_MODEL md5 须更新 | — |
| `docs/DEPLOY/schema/m3_risk.sql` | — | `:65-66` CHECK；`:75` COMMENT；`:4` 抬头 md5 须更新 |
| 服务实现 | `SettleService.java:156` 常量值 → `NOT_ARRIVED`；javadoc `:63-64`/`:262`；`:307` 文案 | `ParamDispatchServiceImpl.java:724` javadoc 删除「域缺口…另行登记」；**行为零变化**（去前缀映射已正确） |
| 测试 | `SettleServiceTest.java:125`（期望值）；`T178ViolationRegressionTest.java:209-228`（由「断言被拒」改为「断言成功落 `NOT_ARRIVED`」）；`M2E2eSeed.java:124` 注释 | `T178ViolationRegressionTest.java:236-245`（无需改）；建议**新增**三类落真实 `DmParamDispatchDao`+引擎的回归用例 |
| **不触** | HGCP 线格式 / `proto/hgcp.proto` / `java/CONTRACTS.md` / `ARCHITECTURE.md` | 同左（三类指令线格式早已冻结） |
| 数据迁移 | **无生产数据**（DM8 尚未实机执行，建表脚本一次性生成）；若已建库，由 DBA 按 `ALTER … DROP/ADD CONSTRAINT` 或重建 | 同左 |
| 计数连锁 | 表数 111 不变；列/索引不变 | 同左 |

### 4.5 回滚

- **回滚方式**：从 CHECK 取值域移除新增值（`NOT_ARRIVED` / `SET_READY_STATE`/`TRIGGER_REPLAY`/`RELOAD`），并回退 `DATA_MODEL` §7 登记行与两份 DDL。
- **回滚前置**：**须先处置已落库的新取值行**——删除或重新归类（例如把 `NOT_ARRIVED` 行按未到达态判定处理），否则 `ALTER … ADD CONSTRAINT` 将被既有行拒绝。**无生产数据时无此负担**。
- **回滚后果**：回到缺陷态（未到达分支写 `PENDING_DATA` 被拒、三类指令下发无法留痕），即本 CH 的动因重现 ⇒ 回滚仅在用户否决或方案改判时执行。

---

## 5. 实施任务建议

| 项 | 建议 |
|----|------|
| **拆分/归并** | **归并为一单**（建议号 `T-183`）：「冻结 CHECK 域承载补齐（001b + 002）」。**理由**：两项均须改同一冻结件 `DATA_MODEL.md`（§7.1/§7.2 同一区），且须与 `CH-021` 同批原子落地；拆两单会引入 `DATA_MODEL.md` 并发写者（违反单写者纪律）。 |
| **依赖** | **强依赖用户批准 `CH-021`**（L3 门禁）。批准前不得动冻结件与实现；可先行准备实现补丁骨架但不落盘。 |
| **归属** | `backend-engineer#java`（SettleService + ParamDispatchServiceImpl javadoc + 两份 DDL + `DATA_MODEL` + 测试）。DDL 由 DBA 执行（`NFR-SEC-8`，应用账号禁 DDL）——实现席只改脚本，不连库。 |
| **估时** | **4 人时**（001b：域 + 常量 + 3 处测试 ≈ 2；002：域 + 注释 + 1 新回归用例 ≈ 2）。若拆两单则各 2 人时，但不建议拆。 |
| **验收标准（建议）** | ① `DATA_MODEL.md` 两处取值域与两份 DDL 逐字一致；② §7.1 新增 v1.3.6 行、§7.2 新增 `DC-042`/`DC-043`；③ **真实 `Dm*Dao` + `InMemoryDmEngine`** 装配级用例：未到达分支成功落 `NOT_ARRIVED`（不再被拒）、三类指令下发各落表成功；④ **去掩盖变异**：把 `NOT_ARRIVED` 改回 `PENDING_DATA` ⇒ 必红；把三类之一改回带 `CMD_` 前缀 ⇒ 必红；⑤ 全量门禁全绿 + `arch-check` rc 0。 |
| **回归/去掩盖** | `T178ViolationRegressionTest.java:209-228` 现判「被拒」，须随本单改为「成功」；`ParamDispatchServiceImplTest.java`（假 DAO）不得作为三类域合法性的唯一证据，应补真实 DAO 用例。 |

---

## 6. 引用条款行号清单（实测时点）

**`docs/DATA_MODEL.md`（v1.3.5）**
- `:2376` `TB-SETTLE-1` 表头 ｜ `:2378` 用途 ｜ **`:2386` `parse_status`** ｜ `:2387` `parse_error` ｜ `:2388` `retry_count` ｜ **`:2390` `is_confirmed`** ｜ **`:2397` `idx_ctp_settle_batch_status`**
- `:2981` `TB-RISK-2` 表头 ｜ `:2983` 用途 ｜ **`:2989` `dispatch_type`** ｜ `:2995-2997` `dispatch_status/ack_time/ack_detail` ｜ `:3005` `idx_param_dispatch_version`
- `:5041` §7 ｜ `:5046` §7.1 ｜ `:5060` §7.2

**`docs/DEPLOY/schema/m2_settle.sql`**
- `:4` 抬头引 `DATA_MODEL` md5 ｜ `:31` 列 ｜ `:35` `is_confirmed` 列 ｜ `:43` `uk` ｜ **`:44` `ck_t_ctp_settle_batch_parse_status`** ｜ `:45` `is_confirmed` CHECK ｜ `:59` 索引

**`docs/DEPLOY/schema/m3_risk.sql`**
- `:4` 抬头 md5 ｜ `:46` `dispatch_type` 列 ｜ **`:65-66` `ck_param_dispatch_type`** ｜ `:75` COMMENT

**`docs/REQUIREMENTS/`**
- `SETTLE.md:87` `FR-SETTLE-1` ｜ `:91` 处理规则 ｜ `:92` 输出 ｜ `:98` 验收 2 ｜ `:99` 验收 3 ｜ `:100` 验收 4
- `CONFIG.md:175` `FR-CONFIG-6` ｜ `:178` 输入 ｜ `:180` 处理规则 ｜ `:188` 验收 4 ｜ `:334` `R-CONFIG-3`
- `RISK.md:236` `FR-RISK-10` ｜ **`:238` 输入（含重载/触发重放）** ｜ `:240` 处理规则 ｜ **`:249` 验收 4**

**`docs/ARCHITECTURE.md`**
- `:520` `ParamDispatchService` ｜ `:820` `CMD_LOAD_RISK_PARAMS` ｜ `:827` `CMD_SET_TRADING_SUSPEND` ｜ **`:828-830` 控制指令 21/22/23** ｜ `:1048` 顺序保证 ｜ `:1125-1127` proto 映射 ｜ `:1680` §6.3 下发流程

**实现侧（证据）**
- `SettleService.java:154/156/158/283/296-300/307/309/312-313/403`
- `ParamChange.java:20-54` ｜ `ParamDispatchServiceImpl.java:268-291/354/427/481/608/719-727` ｜ `ParamChangeEncoder.java:46-51` ｜ `HgcpConstants.java:57/59/61`
- 测试：`T178ViolationRegressionTest.java:182-206/209-228/236-245` ｜ `SettleServiceTest.java:125` ｜ `ParamDispatchServiceImplTest.java:134/142/162/320`

---

## 7. 工单引用行号纠偏（实测）

| 工单原引 | 实测 | 说明 |
|---------|------|------|
| `m2_settle.sql:42` | `:44`（`:42` 是 `pk_t_ctp_settle_batch` 主键约束） | CHECK 行实为 `44` |
| `m3_risk.sql:53-55` | `:65-66`（`:46` 为列声明；`:75` 为 COMMENT） | 文件经 `T-094` 续写后行号前移 |
| 002 依据挂 `FR-CONFIG-6` | 主依据应为 **`FR-RISK-10`**（`RISK.md:238` 明含重载/触发重放） | `FR-CONFIG-6` 输入不含三类，见 §3.1 |

> 其余引用（`DATA_MODEL.md:2386`、`FR-SETTLE-1` 等）与实测一致。

---

## 8. 本裁定边界与未决

- 本单**仅出裁定 + CH 草案**：未改任何代码 / DDL / 冻结件 / 任务表；实施须待 `CH-021` 用户批准后另行派发（§5）。
- **未决（留编排者/用户）**：① `CH-021` 编号与 `DC-042/043` 编号的正式登记；② part 1 新增值命名最终确认（推荐 `NOT_ARRIVED`，备选 `PENDING_DATA`）；③ 是否将 `FAILED` 与 `NOT_ARRIVED` 一并纳入「待重试」巡检口径（`idx_ctp_settle_batch_status` 用途文字是否需同步细化——**本裁定不强制**，属实现期细节）。

---

*本裁定书由 tech-lead 实例 `be-t182` 出具；引用行号均于 2026-10-06 实测；上游制品 md5 见文首，引用前请以实时 `md5sum` 为准。*
