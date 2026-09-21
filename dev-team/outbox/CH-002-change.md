---
ticket: CH-002
type: change
from: 编排者
to: product-manager, 编排者
priority: P0
created: 2026-09-20
refs: [docs/REQUIREMENTS/INDEX.md#9, docs/REQUIREMENTS/INDEX.md#10, docs/REQUIREMENTS/SPOT.md, docs/REQUIREMENTS/PLAN.md, docs/REQUIREMENTS/TRADE.md, docs/REQUIREMENTS/SETTLE.md, docs/REQUIREMENTS/RISK.md, docs/REQUIREMENTS/ACCT.md, docs/REQUIREMENTS/AUDIT.md, docs/REQUIREMENTS/AUTH.md, docs/REQUIREMENTS/CONFIG.md, docs/REQUIREMENTS/PRODUCT.md]
---

## 事由

用户在 2026-09-20 答复了 `REQUIREMENTS/INDEX.md §9` 剩余的待澄清问题 Q-12 ～ Q-17（共 6 条）。至此 §9 全部 17 条待澄清问题均已获答复，须从「待澄清 + 临时默认口径」回写为「已确认口径」，使需求文档集**零待澄清项**进入 S2/S3。

## 要求

1. **回写 INDEX.md §9**：Q-12 ～ Q-17 状态由「未解决」改为「已解决（2026-09-20 用户确认，见 CH-002）」，并在「答复记录」小节逐条登记确认后的口径。
2. **回写受影响模块文件**：把对应 `R-<模块>-<序号>` / `FR-<模块>-<序号>` 中「未解决、按默认口径实现并跟踪」的临时口径改为**已确认口径**，标注 `源自变更 CH-002`。
3. **受影响模块**：SPOT、PLAN、TRADE、SETTLE、RISK、ACCT、AUDIT、AUTH、CONFIG、PRODUCT（按 §9「影响范围」列为准）。
4. **登记 INDEX.md §10 变更记录**：新增 `CH-002` 行（与 CH-001 并列）。
5. **配置项清单**：阈值与开关类口径须写明**配置键 + 默认值**。
6. 与 CH-001 **同批执行**：本工单与 CH-001（Q-1 ～ Q-11）为同一轮回写，须一次完成，避免两遍改动同一批文件。

## 证据 / 复现

不适用（非缺陷工单）。

## 变更评估

- **变更级别**：**L2 需求级**
- **变更对象**：`docs/REQUIREMENTS/INDEX.md` §9、§10；SPOT / PLAN / TRADE / SETTLE / RISK / ACCT / AUDIT / AUTH / CONFIG / PRODUCT 中相关 `R-`/`FR-` 条目
- **变更内容**：Q-12 ～ Q-17 由「待澄清（临时默认口径）」→「用户已确认口径」
- **触发原因**：配合 CH-001 使需求集零待澄清项；否则 S2 架构与 S3 分解只能按临时口径设计
- **影响面评估**：
  - 受影响下游产物：`docs/ARCHITECTURE.md`（敞口分量存储、限额三层内存模型、价格偏离校验）、`docs/DATA_MODEL.md`（敞口快照分量表、核对任务与签署记录表、限额表三层）、`docs/TASKS.md`、`docs/TEST_PLAN/`
  - 需重开的阶段：无
  - 工期影响：约 +0.5 人日
- **审批结论**：
  - 审批人：**用户（老齐）**——L2 变更必须用户拍板
  - 结论：**批准**（用户 2026-09-20 回复「确认并建 CH-002 落盘」）
  - 时间：2026-09-20

## 回复要求

完成后向编排者汇报：
1. 已回写的 Q 编号清单（覆盖 Q-1 ～ Q-17）
2. 每个 Q 落到哪些文件、哪些 `R-`/`FR-` 编号
3. 新增/修改的配置项清单（键名 + 默认值 + 模块）
4. 冲突项：不要自行调整，投 `question` 工单给编排者

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 | 编排者 | 提出变更：汇总用户对 Q-12 ～ Q-17 的答复 | 需用户审批 |
| 2026-09-20 | 用户 | 审批：「确认并建 CH-002 落盘」 | **批准** |
| 2026-09-20 | 产品经理 | **执行回写**（与 CH-001 同批完成）：`INDEX.md` §9 全部 6 条状态改为「已解决（2026-09-20 用户确认，见 CH-002）」并在 §9.1「答复记录」逐条登记；§10 新增 `CH-002` 行；回写 `SPOT`/`PLAN`/`TRADE`/`SETTLE`/`RISK`/`ACCT`/`AUDIT`/`AUTH`/`CONFIG`/`PRODUCT` 及 `INDEX.md` §5 / `FLOWS.md` 中相关 `R-`/`FR-` 条目（标注 `源自变更 CH-002`），阈值与开关均写明配置键 + 默认值；各模块内变更记录同步登记 | **CLOSED**（已完成回写，见下方「回写结果」） |

---

## 回写结果（2026-09-20，产品经理）

**Q → 文件 / 编号映射**

| Q | 落盘文件与条目 |
|---|---------------|
| `Q-12` | `SPOT.md` `R-SPOT-4`、§2（填写要求）；`PLAN.md` `FR-PLAN-7`、`R-PLAN-4`；`ACCT.md` §2（实货盈亏）、`FR-ACCT-5`、`FR-ACCT-10`、`R-ACCT-9`；`FLOWS.md` 异常分支 11 |
| `Q-13` | `TRADE.md` §2（交易理由）、`FR-TRADE-10`、`R-TRADE-9`；`AUDIT.md` §2（违规事项筛查）、`FR-AUDIT-4`、`R-AUDIT-7`；`CONFIG.md` §2（枚举字典）；`INDEX.md` §5.2 第 11 步与异常分支 5；`FLOWS.md` 异常分支 9 |
| `Q-14` | `SETTLE.md` §2（新增「核对任务」「签署记录」，更新月度核对记录/季度报告）、`FR-SETTLE-13`、`FR-SETTLE-14`、**新增 `FR-SETTLE-17`**、**新增 `R-SETTLE-11`**；`AUDIT.md` §2（新增「核对任务与签署记录」）、`FR-AUDIT-6`、**新增 `FR-AUDIT-13`**、**新增 `R-AUDIT-11`**；`AUTH.md` §2（角色/岗位组/新增「签署权与交易权互斥」）、`FR-AUTH-9`、**新增 `R-AUTH-13`**；`INDEX.md` §5.3 备注 |
| `Q-15` | `RISK.md` §2（价格偏离）、`FR-RISK-8`、`R-RISK-8`；`TRADE.md` `FR-TRADE-3`；`CONFIG.md` `FR-CONFIG-5`、`R-CONFIG-8` |
| `Q-16` | `RISK.md` §2（可用额度/持仓上限）、`FR-RISK-2`、`R-RISK-2`；`TRADE.md` `FR-TRADE-3`、`R-TRADE-10`；`PRODUCT.md` §2（额度指标）、`FR-PRODUCT-11`；`CONFIG.md` §2（子账户参数）、`FR-CONFIG-8`；`FLOWS.md` 第 13 步 |
| `Q-17` | `SPOT.md` §2（敞口计算口径/新增「敞口快照」）、`FR-SPOT-14`、`FR-SPOT-15`、`R-SPOT-4`；`CONFIG.md` `FR-CONFIG-5`、`R-CONFIG-8` |

**配置项清单（Q-12 ～ Q-17）**：`spot.price.gap_calc=REJECT`（可选 `INTERPOLATE_MAX_3D`）、`spot.price.gap_valuation=CARRY_FORWARD_LABELED`（SPOT/PLAN/ACCT）、`risk.price.base=PREV_SETTLEMENT`、`risk.price.warn_pct=2`、`risk.price.block_pct=5`（RISK/TRADE/CONFIG）、`exposure.components=CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN`（SPOT/CONFIG）、`settle.sign.deadline_days`（SETTLE，逾期判定口径）；限额三层维度（子账户+品种 / 子账户+合约 / 账户+品种）由 `CONFIG` 统一维护。

**§9 状态**：6 条全部「已解决」；**§9 全部 17 条清零待澄清项**。**§10**：新增 `CH-002` 1 行（与 `CH-001` 并列）。
**冲突项**：无阻塞性冲突；`ACCT.FR-ACCT-10` / `R-ACCT-9` 原「现货价缺失一律报错」的临时口径已按 `Q-12` 的「按用途分两种」拆分修订（属口径变更的直接后果）。
**下游提示**：`DATA_MODEL.md` 需新增/预留——**敞口快照分量表**（合同未执行量/库存/生产计划三类分量）、**限额三层表**、**核对任务表与签署记录表**、**科目表**（含常用科目模板）。

---

## 状态

- **当前状态**：CLOSED
- **终态确认人**：产品经理（回写完成），用户（老齐）已批准
- **终态时间**：2026-09-20
