---
ticket: INV-081082
type: task
from: project-manager
to: backend-engineer#java
priority: P0
created: 2026-10-26T21:00+08:00
refs: [docs/TASKS.md#T-081, docs/TASKS.md#T-082, docs/REQUIREMENTS/AUDIT.md FR-AUDIT-7/8, docs/REQUIREMENTS/（NFR-SEC-1~8）, PROJECT.md §10-1/16/§10-4, 账实背离排查 2026-10-26（编排者·用户裁定 A）]
---

## 事由（账实背离·只读盘点，不施工）

编排者 2026-10-26 排查实锤：T-081/T-082 的完成断言（记忆/台账叙事指向贴标提交 `744f8d5`/`94b83c7`）**在当前 main 链上不存在**——`git cat-file` fatal、reflog/worktree/606 提交全量无痕迹；交付物专名 `AggregationInconsistencyTrigger`（T-081）/`DUMMY_BANK_PASSWORD`（T-082）在 HEAD **grep 零命中**。判读：完成断言指向一条已丢失的 git 线；当前链自洽（`3b6afa9` 自载 166 → 现行 175 逐单吻合、不含此二单）。用户已裁定：**先盘点缺口、再按缺口合成重做单**。

你的任务 = **纯只读盘点**（不得改动任何文件），产出两单在当前树（HEAD `07feb18`，基线 1258）的逐验收项三态清单：**在树（路径+行号）/ 异形在树（候选+判据）/ 缺失**。

## 盘点基准（交付物检索线索——来自丢失线的记忆断言，仅作检索起点，**一切以仓库实测为准**）

### T-081 实现证据链导出（FR-AUDIT-7 验收1·PROJECT §10-1/16；16 人时档）
- `AggregationInconsistencyTrigger`：审计聚合不一致触发器（FR-AUDIT-7「超时未闭环无写入方」缺口承接；24h 缺省 CFG 披露 + 幂等 AGG 键 `eventNo`=tradeDate 稳定键〔跨日不重复落行〕）
- `aggregate` 提交后接线（聚合服务提交后调用触发器）
- FR-AUDIT-8 四条验收承载自证
- 测试：跨日幂等用例 + M1′ 变异（eventNo 取触发日→改 tradeDate）
- `t_settle_pnl` 轨1 激活关联面（T-189 边界·CH-023 v1.3.7 两列）
- 证据章 `docs/TEST_RESULT.md` §T081 段（TEST_RESULT 只追加——若丢失线证据章未在当前链则该段缺失）

### T-082 执行安全加固（NFR-SEC-1~8·PROJECT §10-4；20 人时档；五判据 R-1~R-5）
- R-1 零落盘全库审计（审计旁路=0：单点写入口+时序+无条件调用+开关零命中四合一实证）
- R-2 日志脱敏（口令/密钥日志面清零）
- R-3 鉴权加固面
- R-4 `DUMMY_BANK_PASSWORD` 哑口令词形清单（判据词）
- R-5 `DUMMY_PASSWORD` 与 `DUMMY_BANK_PASSWORD` 词形包含关系（须逐字符重测口径）
- 证据章 §T082 段

## 盘点方法

1. 专名 grep 组（如上）+ 语义组（按 FR-AUDIT-7/NFR-SEC-1~8 验收逐条在 `java/src`、`docs/TEST_RESULT.md`、`docs/DEPLOY/`、CFG 种子找承载）。
2. 三态判定须给证据（路径+行号或 grep 原文）；「异形在树」须给判据（为何认定等价承载）。
3. **残留排查**：两单若曾部分落地，可能留有「孤儿引用」（其他代码/文档引用缺失类）——grep 引用面并给清单。
4. **不写任何文件**（含 /tmp 以外）；报告落 `/root/inv-081082.md`。
5. 报告末尾给：缺口重做工作量预估（对照原 16h/20h 档）+ 建议拆单方案（单做/合并做/先行项）。

## 回复要求

SendMessage 回执 team-lead：报告路径 + md5 + 行数 + 两单缺口计数（缺失/异形/在树）+ 工作量预估一句话。**不贴标、不改 TASKS/BOARD/TEST_RESULT、零文件改动**。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-26 | 编排者 | 用户裁定 A 案派盘点单（实例 be-inv-081082） | OPEN，待 backend-engineer#java 处置 |
