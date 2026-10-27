---
ticket: TL-ARCH-081
type: task
from: project-manager
to: tech-lead
priority: P2
created: 2026-10-26T23:30+08:00
refs: [docs/ARCHITECTURE.md §11（CFG 键清单）, docs/DEPLOY/schema/data-init.sql:464（CFG-26b 种子）, docs/DEPLOY/README.md §④-3（计数 65→66 连锁）, T-081b 交付 a99e8f1（te-verify-t081b 通过·报告 472c3568…/99）, 勘误免 CH 口径先例（v1.5.21/22·须记动因+授权链）]
---

## 事由（勘误级落登·不重开门禁）

T-081b 新增 CFG 键 **CFG-26b**：`audit.major_event.agg_inconsistency_timeout_hours`（缺省 24h·R-AUDIT-5 条件①超时阈值·种子 `data-init.sql:464`）——data-init §④-3 段注与 DEPLOY/README 均已披露该键**未列 ARCHITECTURE §11 清单**（ARCH 冻结制品非实施席写域，T-081b 遗留挂账·te-verify-t081b O-3）。你按**勘误免 CH 口径**（v1.5.21/22 先例：须记动因+授权链）将 CFG-26b 落登 ARCH §11（键名/缺省/语义/来源单 T-081b），版本号递增并记编写时间行；**不重开门禁、不回改历史**。

## 要求

1. 实测 ARCH §11 现行形态与 CFG-26 键族记载位置，同形落登 CFG-26b。
2. 动因与授权链：T-081 重做承接（账实背离处置·用户裁定 A 案 2026-10-26）+ 编排者挂账转办；版本递增+时间行原位扩展。
3. 门禁：`mvn -o validate` arch rc0（ARCH 改动后 E 判据仍绿）；Java 侧无需重跑全量（文档改动零波及——如实测波及列清单）。
4. 边界：仅 `docs/ARCHITECTURE.md`；TASKS/BOARD/TEST_RESULT 编排者落盘。
5. 主树直接工作；`git commit --only docs/ARCHITECTURE.md`；提交号回报 team-lead。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-26 | 编排者 | T-081b 遗留挂账转办落箱（待派或下一轮批处理） | QUEUED |
| 2026-10-27 | tl-arch-081 | 与 TL-ARCH-081b 合并执行完毕：CFG-26b 落登 ARCH §11.2（落笔时点 :2859；键名/缺省 24h 与 data-init.sql:466-469 种子逐字一致；归 §11.2 依据=同族 CFG-26 在 §11.2 且非 Q 口径来源）+ §4.3.1 10012 行指针 + §3.8 护栏语义注记块 + §13 v1.5.24 行 + 编写时间行原位扩展；勘误级免 CH（v1.5.21/22 先例） | 提交号见 git log（单提交）；门禁 `mvn -o -f java/pom.xml validate` rc0（八判据全过）；Java 零改动不跑全量 |
