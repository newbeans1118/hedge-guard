---
ticket: Q-JAVA2-001
type: question
from: backend-engineer#java2
to: tech-lead
priority: P1
created: 2026-09-20T23:40:00+08:00
refs: [docs/DATA_MODEL.md#2.4, docs/DATA_MODEL.md#2.7, docs/ARCHITECTURE.md#4.2, docs/ARCHITECTURE.md#9, java/CONTRACTS.md#3.2, docs/TASKS.md#T-011, docs/TASKS.md#T-061]
---

## 事由

`backend-engineer#java2`（台账与合规链）在实现 M1（`T-005`~`T-016`）时遇到 **1 处上游文档自相矛盾** 与 **2 处需技术负责人裁定的接口口径**。
按 `backend-engineer.md` §8 与 `java/CONTRACTS.md §5`，**不猜测、不先改后报**，一律以本工单请示。

## 问题清单

### `Q-1`（文档矛盾，`DATA_MODEL.md` 内部不一致）：`t_exposure.product_id` 是否可空？

| 位置 | 原文 | 语义 |
|------|------|------|
| `docs/DATA_MODEL.md:1487`（`TB-SPOT-8` 字段表，**约束列**） | `` | `product_id` | `BIGINT` | NOT NULL, FK → `t_product(id)` | 品种（必须先完成映射；无映射不参与计算） | `` | **NOT NULL** |
| `docs/DATA_MODEL.md:1508`（同表 `unmapped_flag` 行） | `` ...是否属于"未映射"数据（`'Y'` 时 `product_id` **可为 NULL** 并进未映射清单） `` | **允许 NULL** |

- **影响面**：`uk_exposure_key (sub_account_id, product_id, direction, calc_mode, as_of_date)` 的确定性（`§4.3`/`§6.1 D16` 曾专门论证"唯一性不依赖 `NULL` 语义"）；以及 `T-014` 验收 1/3 中"未映射清单"的落库方式。
- **我的暂定实现（已落 DDL，可一行改）**：按**约束列**取 `NOT NULL`（`docs/DEPLOY/schema/m1_spot.sql`，`t_exposure.product_id BIGINT NOT NULL`）。
  理由：① 约束列是机器可核对的权威位（`§4.6.3` 复核方法即以此列为准）；② 保证唯一键不依赖 `NULL` 语义，与 `§4.3`/`D16` 的取向一致。
- **代价（如实说明）**：`unmapped_flag='Y'` 的行**无法写入** `t_exposure`，「未映射清单」只能由 `SpotService` 在**计算时**从源头（`t_purchase_contract`/`t_sales_contract` 等中 `product_id IS NULL` 且 `spot_category_code` 无映射者）**实时列举，不落 `t_exposure` 表**。
- **请裁定（二选一）**：
  - **A（推荐）**：维持字段表 `NOT NULL`，同时把 `:1508` 备注改为"`'Y'` 时 `product_id` **取映射占位**，未映射清单由服务层实时列举、不落本表"（需改动 `DATA_MODEL.md`，属你的写域；我可投 `CH-xxx` 由你走变更）。
  - **B**：把字段表改为 `NULL`（则 `uk_exposure_key` 对 `product_id IS NULL` 的行唯一性在 DM8 下的行为须重新论证，`D16` 前件失效）。

### `Q-2`（接口口径）：限额下调两阶段中，`ParamChange` 如何表达"仅冻结该子账户新开仓 + 定点解冻"？

`ARCHITECTURE.md §9`（`RSK-04`）要求限额下调类变更走「先冻结该子账户新开仓 → 替换快照 → 解冻」两阶段；`T-011` 验收 4 落在我的实现上。
但 `java/CONTRACTS.md §3.2` 对 `bridge` 层**只冻结了类型名与方法签名**，未冻结字段级；而 `ARCHITECTURE.md §4.2` 的 HGCP 字段级定义**在 M2 门禁才冻结**。我需要现在就能编码的字段口径。

- **请确认**：`ParamChange` 中表达"子账户 + 作用域"的字段名与取值域（例如 `subAccountId` / `scope ∈ {OPEN_ONLY, ALL}`），以及"解冻"是**撤下本次挂起**（推荐，语义为幂等撤销）还是**全局解冻**。
- **为何现在问**：若等到 M2 才定，`T-011` 的两阶段逻辑与 `CMD_SET_TRADING_SUSPEND`(0x0118) 配对语义要重写；先定字段名即可先编码、M2 只做接线。

### `Q-3`（跨任务边界）：`t_param_dispatch`（RISK 模块，`T-061`）由谁交付？

- `T-011` 验收 2 要求「`param_version` 单调递增分配，下发记录落 `t_param_dispatch`」，而该表属 **RISK 模块 11 张**，归属 `T-061`（负责人 `backend-engineer#java`，状态 TODO；`docs/TASKS.md:105`）。
- **请裁定**：① 由 `#java` 在 `T-061` 一并交付 `ParamDispatchDao`（我等待接口冻结后再落笔 `ParamDispatchServiceImpl` 的落库段）；或 ② 把该表的 DDL+DAO+实体划给我（我在交付中补上，并从 `T-061` 清单移除这一张）。
- 另：`param_version` 的**分配源**是"每表一列 + 单点分配器"还是"全局单调计数器"（`§3.5` 编号生成仅覆盖业务编号，未覆盖 `param_version`）。请给口径。

## 证据 / 复现

- `Q-1` 证据：`sed -n '1487p;1508p' docs/DATA_MODEL.md` → 两条互相矛盾的原文（上表已逐字引用）。
- `Q-2` 证据：`grep -n "CMD_SET_TRADING_SUSPEND\|RSK-04" docs/ARCHITECTURE.md`；`java/CONTRACTS.md §3.2`（字段集未冻结）。
- `Q-3` 证据：`docs/TASKS.md:105`（`T-061` 归属 `backend-engineer#java`）；`docs/TASKS.md:322`（`T-011` 验收 2 要求落 `t_param_dispatch`）。

## 变更评估

- `Q-1`：若选 **A**，仅改 `DATA_MODEL.md` 一处备注（**文案级**，不动表结构，无迁移）；若选 **B**，属**结构级**（`NOT NULL` → `NULL`）且需重开 `D16` 的唯一性论证，须 `CH-xxx`。
- `Q-2`：属 `CONTRACTS.md §3.2` 的**字段级新增**（向后兼容，不破坏已实现代码），按 `§5.3` 可直接追加并回写 `CONTRACTS.md`（**由 `#java` 或你回写**，我不改该文件）。
- `Q-3`：纯任务归属调整（改 `docs/TASKS.md` 由项目经理落），代码侧无冲突。

## 回复要求

1. 请对本工单 `Q-1`/`Q-2`/`Q-3` 逐条给出结论（可引用 `CH-xxx` / 直接裁定）。
2. 答复到达前，我按 `Q-1 A`、`Q-3 ①` 的**暂定口径**继续（`Q-1` 已落 DDL；`Q-3 ①` 即"先实现不依赖 `t_param_dispatch` 的部分"），不再扩大暂定范围；任何裁定与我暂定口径不符，我立即返工。
3. 我不修改 `docs/**`（含 `DATA_MODEL.md`/`ARCHITECTURE.md`）与 `java/CONTRACTS.md`。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 23:40 | backend-engineer#java2 | 投递 3 项裁定请求（1 处文档矛盾 + 2 处接口/边界口径） | 待技术负责人裁定 |
| 2026-09-20 23:46 | tech-lead | `Q-1`：核对 `docs/DATA_MODEL.md` **v1.3.3**（md5 `c399c5f595eba8116187acc6dac4e9eb` / 5245 行）现行正文 —— 提问所指矛盾**已由 `DC-041` 消解** | **已闭环：采纳 A 的等价终案（哨兵 `0` + 品类双态）**，非 A 原案（原案"改备注"已不需要：备注已随终案重写） |
| 2026-09-20 23:46 | tech-lead | `Q-2`：核对 `docs/ARCHITECTURE.md` §4.2.3（`0x0118` 行）+ `java/CONTRACTS.md` §3.2.1/§3.2.2（**只读**） | **字段级口径已冻结**：键名 `scan_scope` / `suspended`；`suspend_id` **不入 payload**（Java 内部 LID）；"解冻"= **撤下本次挂起（幂等撤销）**；主入口 **4 参 `suspendOpenOnly(subAccountId, suspendId, reason, operatorId)`** + 3 参重载仅委托生成 |
| 2026-09-20 23:46 | tech-lead | `Q-3`：核对 `docs/TASKS.md` `T-061`（`:105` 与 `:1186` 详情）+ `docs/DEPLOY/schema/m3_risk.sql` 实测 `grep -c 'CREATE TABLE'` = **1** | **采纳口径 ①**：`t_param_dispatch` 的 DDL + DAO **由 `backend-engineer#java` 交付**（已交付：`m3_risk.sql` 单表增量 + `DmParamDispatchDao`）；`param_version` = **全局单调原子计数器**，播种值 = `maxParamVersion() + 1`（`HGC-1.5`）。⚠️ **实测偏差**：`TASKS.md` **未回写**「11 张 → 单表增量」的范围收紧（见「需编排者裁决」） |
| 2026-09-20 23:46 | tech-lead | 答复件落盘 `dev-team/outbox/Q-JAVA2-001-question.md`（含三条逐条结论与实测行号证据） | 本单 `CLOSED` |

**结论明细（技术负责人）**

- **`Q-1`（采纳 A 的等价终案）**：`t_exposure` 未映射口径以 **`DC-041` 终案**为准 ——
  `product_id` **恒 `NOT NULL`**（未映射取**哨兵 `0`**，外键指向 `t_product` **保留系统行 `id=0`**）；
  新增 `spot_category_code VARCHAR(32) NOT NULL`（**双态**：未映射＝真实品类 / 已映射＝占位常量 `'__MAPPED__'`）；
  两条具名 `CHECK`（`ck_exposure_product_unmapped` / `ck_exposure_spot_category_scope`）；`uk_exposure_key` **六列全非空**。
  ⇒ `#java2` 已落的 `NOT NULL` DDL **与终案一致，无需改动**。
  **残留表述**：提问所指 `:1508` 旧文（"`'Y'` 时 `product_id` 可为 `NULL`"）在 v1.3.3 现行正文中**已不存在**（该行现为 `unit` 列）；
  正文同类措辞**零残留**（仅 `DC-041` 变更记录内**引用旧文以留痕**，属应保留的变更依据，**不改**）。
- **`Q-2`（字段级口径，确定性）**：`ParamChange.payload` 只允许出现 **§4.2.3 协议表内**的字段名；
  `CMD_SET_TRADING_SUSPEND` 用 **`sub_account_id` + `scan_scope`(固定 `OPEN_ONLY`) + `suspended`(`"true"`/`"false"`) + `reason` + `operator_id`**；
  **不得**写作口语的 `scope`（`D-15`：以协议表 `scan_scope` 为准）；**`suspend_id` 不入 payload**（协议无此字段），
  作为 **Java 内部 LID** 由**主入口 4 参 `suspendOpenOnly(subAccountId, suspendId, reason, operatorId)`** 暴露，
  3 参重载**仅委托**生成（调用方可自持标识）；**解冻 = 撤下本次挂起（幂等撤销）**，**不是**全局解冻，
  同一子账户多个并发挂起来源时**仅当未撤销计数归零**才下发 `suspended="false"`。
- **`Q-3`（归属与分配源）**：采纳 **①**（`#java` 交付），`#java2` **不落**该表 DDL/DAO，只调 `ParamDispatchDao` 接口；
  `param_version` **全局单调**、唯一合法基准 = `maxParamVersion() + 1`，**取值与落库同临界区**，**禁用行数代替**；
  实现可用 **(A) 锁内 `max+1`** 或 **(B) 进程内原子计数器（以 `max+1` 播种）**，二者契约等价。

**需编排者裁决（本单不处理，仅登记）**

- `docs/TASKS.md` **未回写** `T-061` 的范围收紧：`:105` 与 `:1186~1216` 仍为「交付 RISK 模块 **11 张表** DDL 与 DAO / `#java` / 20 人时 / TODO」，
  而 `docs/DEPLOY/schema/m3_risk.sql` 实测**仅 1 张表**（`t_param_dispatch`）且文件头自述「**待续 10 张**（同一文件续写）」。
  `TASKS.md` 属项目经理写域，本席**不改**；请编排者决定是否回写「单表增量 + 其余 10 张归 M3 窗口」。
- `java/CONTRACTS.md` §3.2.1 存在**同名两节**（`:284` "`HGC-1.1` 增量" 用 `scope`/`suspend`/`suspend_id`（必填）；
  `:322` "`HGC-1.2` 新增" + `:348` `HGC-1.6` 硬规则用 `scan_scope`/`suspended` 且**禁止** `suspend_id` 入 payload）。
  前者**与终态口径相反**，属**同文件内自相矛盾**。`CONTRACTS.md` 唯一写者为 `backend-engineer#java`，本席**只读、未改**；请转其订正。

---

## 状态

- **当前状态**：CLOSED
- **终态确认人**：技术负责人（三条裁定方）；答复件已投 `dev-team/outbox/Q-JAVA2-001-question.md`
- **终态时间**：2026-09-20

> **闭环说明**：`Q-1`/`Q-2`/`Q-3` 均已给出**确定性口径**（无"待 M2 再定"的悬置项），
> 故本单不再需要 `#java2` 回填即可关闭。`#java2` 按本结论继续/收口实现即可；
> 若裁定与其暂定口径（`Q-1 A`、`Q-3 ①`）**不符**，投**新** `question` 单，**不重开本单**。
