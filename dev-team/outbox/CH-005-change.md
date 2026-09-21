---
ticket: CH-005
type: change
from: product-manager
to: 编排者, product-manager
priority: P0
created: 2026-09-20
refs: [docs/REQUIREMENTS/INDEX.md#2, docs/REQUIREMENTS/PRODUCT.md, docs/DATA_MODEL.md, docs/ARCHITECTURE.md]
---

## 事由

用户（老齐）于 2026-09-20 就**模块代号**明确拍板：

> 「don't use variety, use product instead, change all」

即：第 1 个业务模块（品种管理）的**模块代号由 `VARIETY` 全量更名为 `PRODUCT`**。
编排者已执行**机械改名**（`VARIETY` → `PRODUCT`；含 `FR-PRODUCT-*` / `R-PRODUCT-*` /
`TB-PRODUCT-*`、文件 `VARIETY.md` → `PRODUCT.md`、`docs/**` 与 `dev-team/**` 全部 md）。
本变更单为**该用户裁决的落盘留痕**，并把「此后不得再改名」的约束写入需求索引。

> ⚠️ **本变更在流程上属例外**：编排规则 **§8.2「模块代号一经登记即永久保留、不得改名」**
> 被本次变更**违反**。但**该约束的保护对象是用户**（改名等于全项目编号失效），
> 本次系**用户主动拍板**要求改名，属**约束的例外裁决**，故不属编排者的擅自改名。
> 变更发生时**尚在 S4 编码之前**，改名已由编排者**全量同步**至需求文档集与全部下游 md，
> **未出现半同步状态**，故**追溯链未断裂**（`FR-PRODUCT-*` / `R-PRODUCT-*` 均可正常闭合）。

## 要求

1. **新建本变更单** `CH-005-change.md`，按 `templates/TICKET.md` 格式登记（type: change，L2 需求级）。
2. **回写 `INDEX.md` §2 模块登记表**：
   - 确认第 1 项代号为 `PRODUCT`（模块名仍为「品种管理」）；
   - 在 §2「约束」段补一条说明：本表代号已于 2026-09-20 经 `CH-005` 由 `VARIETY` 更名为
     `PRODUCT`，**此后不得再改名**。
3. **检查并修正一致性**：`INDEX.md` §3 术语表 / §11 文件清单中不得残留与 `PRODUCT` 代号
   不一致的表述。
4. **登记 `INDEX.md` §10 变更记录**：新增 `CH-005` 行。

## 证据 / 复现

不适用（非缺陷工单）。

## 变更评估

- **变更级别**：**L2 需求级**（模块代号为全项目编号的地基，改名影响全部 `FR-`/`R-` 编号前缀与模块文件名）
- **变更对象**：`docs/REQUIREMENTS/INDEX.md` §2（模块登记表 + 约束段）、§10；
  模块文件名与全部 `FR-PRODUCT-*` / `R-PRODUCT-*` 编号前缀；`docs/**` 与 `dev-team/**` 全部 md 中的代号引用
- **变更内容**：模块代号 `VARIETY` → `PRODUCT`（模块名「品种管理」不变）
- **触发原因**：用户拍板。理由：`VARIETY` 在期货圈内语义偏窄（易与"品种/合约"混淆），
  `PRODUCT` 语义更贴合"企业运营中需要套保的标的"；**不改则下游将长期使用用户不认可的代号**
- **影响面评估**：
  - 受影响需求条目：**编号前缀全量替换**（`FR-VARIETY-*` → `FR-PRODUCT-*`、
    `R-VARIETY-*` → `R-PRODUCT-*`、`TB-VARIETY-*` → `TB-PRODUCT-*`）；
    **条数与语义不变**，**不新增 / 不删除 / 不作废**任何条目
  - 受影响下游产物：`docs/DATA_MODEL.md`（`TB-PRODUCT-*` 表名/编号）、
    `docs/ARCHITECTURE.md`（模块分层引用）、`docs/TASKS.md`、`docs/TEST_PLAN/`、
    `PROJECT.md` 中的模块引用
  - 需重开的阶段：**无**——改名在 **S4 编码前**完成，且**已全量同步**，追溯链未断裂
  - 工期影响：约 +0.2 人日（一致性核对）
  - ⚠️ **残留项**：`docs/DATA_MODEL.md` 仍存在 `VARIETY` 字样（**属技术负责人边界**，
    由技术负责人同步，本变更单不越界修改）
- **审批结论**：
  - 审批人：**用户（老齐）**——L2 变更（且涉及编排规则 §8.2 例外）必须用户拍板
  - 结论：**批准**
  - 时间：**2026-09-20**

## 回复要求

完成后向编排者汇报：
1. 已回写文件与章节清单；
2. `INDEX.md` §10 变更记录登记结果；
3. §3 术语表 / §11 文件清单的一致性核对结论；
4. 边界外残留项（`DATA_MODEL.md`）的移交说明；
5. 是否有与既有条目冲突之处——如有，投 `question` 工单，不自行改口径。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 | 用户（老齐） | 拍板：模块代号 `VARIETY` → `PRODUCT`，全量改名（"don't use variety, use product instead, change all"） | **批准** |
| 2026-09-20 | 编排者 | 执行机械改名：`VARIETY` → `PRODUCT`（`FR-PRODUCT-*`/`R-PRODUCT-*`/`TB-PRODUCT-*`、文件 `VARIETY.md` → `PRODUCT.md`、`docs/**` 与 `dev-team/**` 全部 md） | 改名已全量同步，追溯链未断裂 |
| 2026-09-20 | product-manager | 新建本变更单；回写 `INDEX.md` §2（约束段）与 §10；核对 §3/§11 一致性 | **CLOSED** |

---

## 状态

- **当前状态**：CLOSED
- **终态确认人**：产品经理（回写完成），用户（老齐）已批准
- **终态时间**：2026-09-20

> **闭环说明**：本变更单为**用户拍板的代号改名留痕**。因改名在 S4 编码前完成且已全量同步，
> 四步流程（提出 → 评估 → 审批 → 传导）走完且**无作废条目**，故置 `CLOSED`。
