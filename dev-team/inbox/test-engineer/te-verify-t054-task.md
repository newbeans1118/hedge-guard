---
ticket: te-verify-t054
type: review
from: project-manager
to: test-engineer
priority: P1
created: 2026-10-25T21:00+08:00
refs: [docs/TASKS.md#T-054, dev-team/inbox/frontend-engineer/T-054-task.md, 提交 8c2f389（交付）+ dc376df（SETTLE 键落法裁决落地）, docs/REQUIREMENTS/SETTLE.md FR-SETTLE-1~17, java/src/main/java/com/hedgeguard/web/handler/settle/（T-044/T-047 注册面）, java/src/main/java/com/hedgeguard/web/ApiRoutes.java, docs/ARCHITECTURE.md, R-SETTLE-5, T-193/T-195 导出先例]
---

## 事由

T-054 结算前端页面交付已落共享树，状态「已完成，待独立复核」。交付横跨两笔提交（同一写域连续实施）：

- **`8c2f389`**：settle.js 18 行 stub → 1065 行 6-Tab（numstat 1061/14）+ 探针 t054_settle_probe 548 行 103 断言；SETTLE 15 方法对全承载；R-SETTLE-5 关账态三动作禁用；FR-SETTLE-1 pendingRetry 警示；Q-11 阻断渲染；ADJUST 双人复核预检；签署不可代签；routes.js SETTLE 键零变更只读核对。
- **`dc376df`**（编排者裁决项①处置）：routes.js SETTLE 键落法落地（12 路径/15 方法对与 ApiRoutes BY_MODULE.SETTLE 对齐，cycleAction 参数化沿 PLAN.action 先例）+ settle.js S 常量整体迁移 HG.routes.SETTLE 别名（逐字不变）+ 探针升级 **113 断言**（SETTLE 键负断言转正断言逐键逐字 + 负例①扫描面扩 routes.js + S 迁移形态断言）。

你是独立复核席，**不采信产出方自评**——证据分列「独立实测」vs「产出方自述」。

## 复核纪律（硬约束）

1. **隔离副本**：`git archive dc376df | tar -x -C /tmp/t054-verify`（该快照含 8c2f389 全部内容 + 当日 T-053 交付；凡 routes.js 的 **TRADE 段差异属 T-053 写域不计入本单**，SETTLE 段才是本单核对面）。所有运行/变异在副本内；**主树零改动**。
2. **树锚**：产出方未回报独立树锚（编号回归全绿系探针口径）——你须自算规范树锚（`cd <副本> && find web java -type f | sort | xargs md5sum | md5sum`，附命令原文与文件数）作为本单复核锚；勿跑 mvn 后再算（target 污染判例）。
3. **全绿≠无缺陷**：门禁复现外必须自设扫描面；「产出方已披露」≠已排查。
4. **变异**：≥3 个（建议：①摘 R-SETTLE-5 关账态前端守卫 ②摘 routes.js 某 SETTLE 键 ③探针断言侧自变异证判据非恒绿）；给击杀器用例名+失败输出；结束复位 md5 对照。
5. 结论分级：通过/有条件通过/不通过；缺陷 TE-T054-V-001 起分 P1/P2/P3；观察 O-x；阻塞自述须核实。

## 门禁复现（独立实测，隔离副本内）

- `node tools/probes/t054_settle_probe.mjs` ⇒ 终态 113/0（8c2f389 时点 103/0 可用 `git show 8c2f389:tools/probes/t054_settle_probe.mjs` 对照演进，不必跑旧版）
- 回归：t053 129/t055 100/t070 104/t071 71/t083 202/t071g rc0（注意 t053 探针在 dc376df 快照内已存在——TRADE 段属 T-053 但其回归绿是本单不破坏 T-053 的证据）
- `node --check` 触碰 js 文件（settle.js/routes.js）
- `cd java && mvn -B -o clean test` ⇒ 声称零波及（web/tools 改动不触 Java 测试；dc376df 时点基线 1246 含 T-040 +8）。过重则至少 `BootstrapAssemblyTest` + `mvn validate`（arch rc0）并注明口径
- numstat 对账：两笔提交逐笔与上文交付声明吻合

## 自设扫描面（逐项给结论，含「不适用」清单）

1. **routes.js SETTLE 键对账**：12 路径/15 方法对 vs `ApiRoutes.java` BY_MODULE.SETTLE 逐字对账；cycleAction 参数化形态 vs PLAN.action 先例；确认既有键零删改（`git show dc376df -- web/js/core/routes.js` 纯追加/迁移）。
2. **S 常量迁移断言**：settle.js 内 HG.routes.SETTLE 别名迁移后**零字面路径残留**（grep `/settle/` 扫 settle.js 应仅剩注释或零命中）——防「迁移不彻底双源」。
3. **wire 字段对账**：settle.js 请求体字段名 vs settle handler 实测 camelCase 逐字段（≠ DATA_MODEL snake_case）。
4. **业务红线承载**（逐项给行号+探针对应断言）：R-SETTLE-5 关账态三动作禁用（前端守卫+负例②关账态重算点击不发请求）；FR-SETTLE-1 pendingRetry 警示不产出推算数据；Q-11 阻断范围渲染；ADJUST 双人复核预检；签署不可代签/角色显式选择；幂等 Key 全写操作。
5. **导出张力披露核实**：/settle/export 页面浏览器侧 JSON→CSV 转换实存且横幅披露；该张力（FR-SETTLE-16「CSV/报表文件」vs 后端 JSON）**属待用户裁定项——只核实披露属实，勿替用户裁**。
6. **接缝演练入口披露核实**：执行结算明细区横幅诚实披露实存（事件源归 T-172 域）；同理只核实不裁。
7. **XSS 面**：全文件扫 `{html:` 赋值点（F.esc 包裹或纯静态）；`appendChild` 直接接 HTML 字符串违规扫描（红线判例）。
8. **ESM 形态**：ESM 桩全动态 import；storage/口令零落盘扫描。
9. **探针判据判别力**：判据形态与合法取值重合检查（假绿盲区判例）；断言侧自变异至少 1 个。

## 报告

- 落 `/root/t054-verify.md`：结论 + 门禁复现四元组 + 扫描面逐项（独立实测 vs 产出方自述分列）+ 缺陷/观察清单 + 自算树锚 + 变异清单。
- 回执给 team-lead：报告路径 + md5 + 行数 + 结论一句话 + 缺陷/观察计数。
- **不贴标、不改 TASKS/BOARD/TEST_RESULT**；主树零改动；披露①②的裁定留给用户，报告只判「披露是否属实」。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-25 | 编排者 | 用户批准派发（三线待复核第二单；实例 te-verify-t054） | OPEN，待 test-engineer 处置 |
