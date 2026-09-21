---
ticket: CH-001
type: change
from: 编排者
to: product-manager, 编排者
priority: P0
created: 2026-09-20
refs: [docs/REQUIREMENTS/INDEX.md#9, docs/REQUIREMENTS/RISK.md, docs/REQUIREMENTS/PLAN.md, docs/REQUIREMENTS/SPOT.md, docs/REQUIREMENTS/TRADE.md, docs/REQUIREMENTS/SETTLE.md, docs/REQUIREMENTS/ACCT.md, docs/REQUIREMENTS/AUDIT.md, docs/REQUIREMENTS/FUND.md, docs/REQUIREMENTS/PRODUCT.md, docs/REQUIREMENTS/AUTH.md, docs/REQUIREMENTS/CONFIG.md]
---

## 事由

S1 门禁通过后，用户在 2026-09-20 答复了 `REQUIREMENTS/INDEX.md §9` 登记的待澄清问题 Q-1 ～ Q-11（共 11 条）。这些问题的答案直接决定风险控制、套保会计、结算对账的判定口径，须从「待澄清 + 临时默认口径」回写为「已确认口径」。

## 要求

1. **回写 INDEX.md §9**：Q-1 ～ Q-11 状态由「未解决」改为「已解决（2026-09-20 用户确认，见 CH-001）」，并在该表（或紧邻的「答复记录」小节）逐条登记确认后的口径。
2. **回写受影响模块文件**：把每条问题对应 `R-<模块>-<序号>` 规则中原本标注「未解决、按默认口径实现并跟踪」的临时口径，改为**已确认口径**，并标注 `源自变更 CH-001`。
3. **受影响模块**：RISK、PLAN、SPOT、TRADE、SETTLE、ACCT、AUDIT、FUND、PRODUCT、AUTH、CONFIG（按 INDEX.md §9「影响范围」列为准）。
4. **登记 INDEX.md §10 变更记录**：新增一行 `CH-001`。
5. **可配置项清单**：凡本次确认为阈值的（规模比例、频率次数、期限月数、宽限天数、偏离百分比等），须在对应规则中明确「系统配置项」及其默认值，使后续用户答复变更只需改配置。

## 证据 / 复现

不适用（非缺陷工单）。

## 变更评估

- **变更级别**：**L2 需求级**（已定稿需求条目的判定口径变更）
- **变更对象**：`docs/REQUIREMENTS/INDEX.md` §9、§10；11 个模块需求文件中相关 `R-<模块>-<序号>` 与部分 `FR-<模块>-<序号>`
- **变更内容**：Q-1 ～ Q-11 由「待澄清（临时默认口径）」→「用户已确认口径」（逐条口径见本工单「处理记录」及编排者下发的具体答复）
- **触发原因**：这些口径直接决定风控阻断、会计有效性判定与加总对账处置；不落盘会导致下游按临时默认口径实现，用户答复后需返工
- **影响面评估**：
  - 受影响下游产物：`docs/ARCHITECTURE.md`（风控内存模型、判定算法）、`docs/DATA_MODEL.md`（配置项表、规模基数表、科目表）、`docs/TASKS.md`、`docs/TEST_PLAN/`（阻断类用例的期望值）
  - 需重开的阶段：无（S2 进行中，按已确认口径继续；S1 产物按本工单回写）
  - 工期影响：约 +0.5 人日（回写与交叉核对）
- **审批结论**：
  - 审批人：**用户（老齐）**——L2 变更必须用户拍板
  - 结论：**批准**（用户在 2026-09-20 明确回复「确认落盘」）
  - 时间：2026-09-20

## 回复要求

完成后向编排者汇报：
1. 已回写的 Q 编号清单（须覆盖 Q-1 ～ Q-11，不得遗漏）
2. 每个 Q 落到哪些文件、哪些 `R-`/`FR-` 编号（编号级清单）
3. 新增/修改的配置项清单（配置键 + 默认值 + 所在模块）
4. 是否有某条口径在回写时发现与既有 FR 冲突——如有，**不要自行调整**，投 `question` 工单给编排者并汇报

> 特殊约定：`change` 类型 → 评估与审批结论写入上方「变更评估」区块；变更生效后须回写 `PROJECT.md` §11 / `REQUIREMENTS/INDEX.md` §10 的变更记录

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 | 编排者 | 提出变更：汇总用户对 Q-1 ～ Q-11 的答复，判定为 L2 需求级变更 | 需用户审批 |
| 2026-09-20 | 用户 | 审批：「确认落盘」 | **批准** |
| 2026-09-20 | 产品经理 | **执行回写**：`INDEX.md` §9 全部 11 条状态改为「已解决（2026-09-20 用户确认，见 CH-001）」并新增 §9.1「答复记录」逐条登记口径；§10 新增 `CH-001` 行；回写 `RISK`/`PLAN`/`SPOT`/`TRADE`/`SETTLE`/`ACCT`/`AUDIT`/`FUND`/`PRODUCT`/`AUTH`/`CONFIG` 及 `INDEX.md` §5 / `FLOWS.md` 中相关 `R-`/`FR-` 条目（标注 `源自变更 CH-001`），阈值与开关均写明配置键 + 默认值；各模块内变更记录同步登记 | **CLOSED**（已完成回写，见下方「回写结果」） |

---

## 回写结果（2026-09-20，产品经理）

**Q → 文件 / 编号映射**

| Q | 落盘文件与条目 |
|---|---------------|
| `Q-1` | `INDEX.md` §9/§9.1、§5.1 异常分支 3、§5.3 备注；`RISK.md` §2（规模红线）、`FR-RISK-4`、`R-RISK-4`；`PLAN.md` §2（计划类型/期限）、`FR-PLAN-10`、`R-PLAN-3`；`PRODUCT.md` §2（额度指标）；`CONFIG.md` `FR-CONFIG-5`、`R-CONFIG-8`；`FLOWS.md` 异常分支 4 |
| `Q-2` | `PRODUCT.md` §2（品种分类/分类管理标记）、`FR-PRODUCT-1`、`FR-PRODUCT-2`、`R-PRODUCT-4`；`PLAN.md` §2（计划类型）、`FR-PLAN-10`、`R-PLAN-3`；`CONFIG.md` §2（子账户参数）、`FR-CONFIG-7`、`FR-CONFIG-8`、`R-CONFIG-4`；`RISK.md` `R-RISK-4` |
| `Q-3` | `RISK.md` §2（时点净持仓）、`FR-RISK-5`、`R-RISK-4`；`TRADE.md` `FR-TRADE-3`；`SPOT.md` §2（新增「敞口快照」） |
| `Q-4` | `RISK.md` §2（期限红线）、`FR-RISK-6`、`R-RISK-6`；`PLAN.md` §2（计划期限）、`FR-PLAN-1`、`FR-PLAN-10`、`FR-PLAN-18`、`R-PLAN-3`、`R-PLAN-6`、`R-PLAN-9`；`TRADE.md` `FR-TRADE-3`；`CONFIG.md` `FR-CONFIG-5`、`R-CONFIG-8`；`INDEX.md` §5.1 异常分支 3；`FLOWS.md` 异常分支 4 |
| `Q-5` | `RISK.md` `FR-RISK-14`；`AUDIT.md` `FR-AUDIT-4`；`CONFIG.md` `FR-CONFIG-5` |
| `Q-6` | `PLAN.md` `FR-PLAN-8`、`R-PLAN-4` |
| `Q-7` | `ACCT.md` §2（有效性评估/经济关系/新增「有效性监测指标」）、`FR-ACCT-2`、`FR-ACCT-4`、`R-ACCT-2`；`AUDIT.md` `FR-AUDIT-4` |
| `Q-8` | `ACCT.md` §2（再平衡）、`FR-ACCT-3`、`R-ACCT-3`；`PLAN.md` `R-PLAN-6` |
| `Q-9` | `ACCT.md` §2（会计科目）、`FR-ACCT-6`、`R-ACCT-11`；`CONFIG.md` §2（科目字典）、`FR-CONFIG-14` |
| `Q-10` | `CONFIG.md` §2（子账户）、`FR-CONFIG-7`、`R-CONFIG-4`；`AUTH.md` §5（口径确认） |
| `Q-11` | `SETTLE.md` §2（加总对账/新增「差异待处理事项」）、§2.1 状态机、`FR-SETTLE-8`、`R-SETTLE-2`；`RISK.md` `R-RISK-5`；`FUND.md` §2（划拨申请）、`FR-FUND-10`、`R-FUND-6`；`INDEX.md` §5.3 异常分支 2；`FLOWS.md` 异常分支 10 |

**配置项清单（Q-1 ～ Q-11）**：`hedge.scale.base=EXPOSURE_QTY`（可选 `REVENUE`/`PURCHASE_QTY`）、`hedge.scale.scope=BOTH`（RISK/PLAN）、`sub_account.category=COMMODITY(90)/TRADE(80)`（PRODUCT/CONFIG）、`risk.position.check_mode=PRE_TRADE_AND_EOD`（RISK）、`risk.term.max_months=12`、`risk.term.grace_days=30`、`risk.term.action=WARN_THEN_BLOCK`（RISK/PLAN/CONFIG）、`risk.freq.*`（RISK/CONFIG）。

**§9 状态**：11 条全部「已解决」，零待澄清项（本工单范围内）。**§10**：新增 `CH-001` 1 行。
**冲突项**：无阻塞性冲突；`FR-RISK-6` 原验收标准第 2 条系旧「取较大者」临时口径的表述，已随 `Q-4` 确认口径（取**较早者**）同步修订为一致性表述（属口径变更的直接后果，非独立需求）。

---

## 状态

- **当前状态**：CLOSED
- **终态确认人**：产品经理（回写完成），用户（老齐）已批准
- **终态时间**：2026-09-20
