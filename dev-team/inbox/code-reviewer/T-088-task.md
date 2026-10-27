---
ticket: T-088
type: task
from: project-manager
to: code-reviewer
priority: P0
created: 2026-10-27T13:00+08:00
refs: [docs/TASKS.md#T-088, NFR-SEC-9/NFR-MAINT-4/6（NONFUNCTIONAL.md:129/183/191）, 执行先例 cr-t075（M3 合规审查·REVIEW.md §M3·R3-xxx 问题清单·merge-base 范围核对·并发边界声明）, 当前基线：1289 绿/arch rc0/机读 181/HEAD 待开工实测, 挂账批：TL-ARCH-081（CFG-26b）/TL-ARCH-081b（CFG-34/35+护栏语义+DATA_MODEL 增表·T-205 披露②）均 QUEUED 未派]
---

## 事由（M4 终审·收官单）

T-088 执行 M4 代码与架构终审。依赖 T-081/T-082/T-087 全机读 DONE。执行形态沿 **cr-t075 M3 合规审查先例**：只读审查 + 问题清单（不代改代码）+ merge-base 范围核对 + 并发边界声明。

## 审查范围与判据

1. **范围核对**：merge-base 实测 M4 终审覆盖面（M4 里程碑全域——ACCT 域 T-076~T-080/T-083 前端/T-084 计划/T-085~T-087 证据章/T-081/T-082 重做链/T-205 护栏/T-199 权限清单；cr-t025 判例：范围核对先行并留痕）。
2. **NFR-SEC-9**：C++ 组件零数据库访问代码/零 DB 客户端库链接/静态扫描零 SQL（E3/E5 判据现状复核——C++ 侧 SQL 红线终审）。
3. **NFR-MAINT-4/6**：组件边界不可越界（C++ 无库无业务逻辑搬移/事前风控未搬 Java/Java 未替代下单职责——注意 **G-6 护栏系 Java 侧提交前置校验而非替代 C++ 下单职责**，护栏语义边界为 T-205 复核已裁定形态·你独立复核该边界陈述）；单一写库入口（只有 Java 写库·C++ 经事件流）。
4. **门禁终态复核**：全量 1289/0/0/0 + arch rc0（E1~E8）+ 树锚双形态（canonical+LC_ALL=C·附命令原文）独立复现。
5. **M4 证据链完整性**：§T081~§T087 证据章存在性与锚一致性抽查（TEST_RESULT 只追加红线核）；BOARD/TASKS 与机读计数对账（181）。
6. **挂账批登记核**：TL-ARCH-081（CFG-26b）/TL-ARCH-081b（CFG-34/35+护栏语义+DATA_MODEL 增表）**QUEUED 未派系已知状态**——终审报告列为「待办承接」非缺陷；如实测发现 ARCH/DATA_MODEL 与实态背离的**其他**未登记项，逐条单列。
7. **问题清单**：编号 R4-001 起（沿 R3 系先例）；分级（阻断/严重/建议/观察）；**只报不改**——源码/TASKS/BOARD/TEST_RESULT/工具零触碰。

## 交付要求

1. **报告**：`docs/REVIEW.md` **纯追加** §M4 终审章（沿 §M3 形态：范围核对/逐验收判定/问题清单/并发边界声明/结论）——追加前实测尾部 md5 留痕；结论分级（通过/有条件通过/不通过）。
2. **门禁**：全量 `mvn -B -o clean test` 1289 复跑 + `mvn validate` rc0 + ctest（cpp 桩轨 50/50 口径实测）+ 树锚双形态。
3. **边界**：写域 = `docs/REVIEW.md`（仅 §M4 纯追加）+ 工单处理记录；**其余全零触碰**（含 TASKS/BOARD/TEST_RESULT/源码/工具/TEST_PLAN）。
4. **交付形态（硬约束）**：主树直接工作（开工前 `git log --oneline -3` + `git status --short` 复核）；`git commit --only docs/REVIEW.md`、提交号回报；工单处理记录追加一行。业务口径分歧停手 SendMessage 回报。

## 回复要求

回报：提交号、numstat、范围核对（merge-base 命令与结论）、NFR-SEC-9/NFR-MAINT-4/6 逐条判定、门禁四元组、证据链抽查结果、R4 问题清单（分级计数+逐条一句话）、结论分级、REVIEW 追加前后锚、树锚双形态、边界声明。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-27 | 编排者 | 用户批准派发（M4 终审收官单·依赖 T-081/T-082/T-087 全满足；实例 cr-t088） | OPEN，待 code-reviewer 处置 |
