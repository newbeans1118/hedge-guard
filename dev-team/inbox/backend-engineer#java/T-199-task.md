---
ticket: T-199
type: task
from: project-manager
to: backend-engineer#java
priority: P1
created: 2026-10-27T07:00+08:00
refs: [docs/TASKS.md#T-199（本单立号）, NFR-SEC-8 @ docs/REQUIREMENTS/NONFUNCTIONAL.md:125, CH-025 禁 DELETE 分域裁定（第 253 轮收官行·docs/TASKS.md 收官叙事）, T-086 复核 TE-T086-V-001（/root/t086-verify.md）, docs/DEPLOY/README.md（现止于 §11）, docs/DEPLOY/schema/*.sql（11 个脚本·T-086 复核 O-3 口径：10 建表+data-init 种子）, T-081/T-082 背离重做先例]
---

## 事由（丢失线第三例·立号+重做合一）

T-086 复核立案 `TE-T086-V-001`（P1，编排者复核实锤）：T-199（DEPLOY §12 数据库账户权限清单）完成断言指向丢失线——贴标提交 `82c91d5` cat-file fatal、TASKS 跳号无行、DEPLOY 全 docs「数据库账户权限清单」0 命中；INV-081082 盘点叙述失真已注记。用户批准立号+重做合一派发。本单补齐 NFR-SEC-8 承载落痕，T-086 贴标条件③随之闭环。

## 要求

1. **五判据提取（先定义后施工）**：从 `NFR-SEC-8`（`NONFUNCTIONAL.md:125` 最小权限原则原文+验收）与 `CH-025`（禁 DELETE 分域细化裁定·第 253 轮收官行检索）提取**五判据**，逐条显式列出依据行号——判据定义本身写入 §12 开头（丢失线形态为五判据，细节不可考；你提取的五判据如与丢失线不符系合法重定义，报告注明）。
2. **§12 权限清单**（DEPLOY.md 纯追加）：逐数据库账户（应用账户/部署账户/审计账户等·以 schema 脚本与 Config 实测连接面为准）列权限矩阵——账户×对象×权限（SELECT/INSERT/UPDATE/DELETE/DDL）+ 最小权限原则逐项判定 + **豁免域**（DAO 行为如 uk 冲突重试、框架性 DELETE 等「非授权放开」逐项核实——CH-025 的 8 处禁 DELETE 逐处对账）+ 数据来源（脚本文件:行号）。
3. **判据形态纪律**：残留/命中类判据**禁用改前即 0 命中的盲形态**（T-203 判例：须对改前状态跑负控或改用非平凡判据——如「账户×对象矩阵行数==N」「豁免项计数==8 对齐 CH-025」等可证伪形态）。
4. **纯追加**：DEPLOY.md 仅追加 §12（既有 §1~§11 零触碰）；docs/TEST_RESULT.md **无需**追加证据章（文档单·由复核席在报告中落章，或按你实测需要最小追加并注明口径）。
5. **门禁**：`mvn -o validate` arch rc0；Java 侧零改动则无需全量（如实测波及列清单）；树锚 `find java -type f | sort | xargs md5sum | md5sum` + docs 侧自报口径（附命令原文与文件数）。
6. **边界**：写域 = `docs/DEPLOY/README.md`（仅 §12 追加）；**不碰** schema 脚本/Java/TASKS/BOARD/TEST_RESULT/ARCH（CFG-26b 类落登属他单）。
7. **交付形态（硬约束）**：主树直接工作（开工前 `git log --oneline -3` + `git status --short` 复核）；`git commit --only docs/DEPLOY/README.md`；工单处理记录追加一行。业务口径分歧停手回报。

## 回复要求

回报：提交号、numstat、五判据清单（逐条依据行号）、权限矩阵概要（账户数×对象数）、CH-025 八处对账结果、豁免域清单、判据形态与负控说明、arch rc0、树锚、边界声明。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-27 | 编排者 | 用户批准立号+重做合一派发（TE-T086-V-001 承接·实例 be-t199） | OPEN，待 backend-engineer#java 处置 |
