---
ticket: T-204
type: task
from: project-manager
to: backend-engineer#java
priority: P2
created: 2026-10-26T11:00+08:00
refs: [docs/TASKS.md#T-204, docs/REQUIREMENTS/SETTLE.md#FR-SETTLE-16（SETTLE.md:314-330）, T-054 披露①用户裁定 A（2026-10-26，a04ec5a）, T-193 形态先例（EvidenceExportHandler text/csv 直返·TE-T069-003）, T-195 先例（FundExportHandler）, ConfigExportHandler/ProductExportHandler/SelfcheckExportHandler 单表直返先例, java/src/main/java/com/hedgeguard/web/handler/settle/SettleExportHandler.java, web/js/pages/settle.js:935-1014, tools/probes/t054_settle_probe.mjs:508-524]
---

## 事由

T-054 披露①经用户裁定采 A 案（2026-10-26）：`GET /settle/export` 后端 CSV 直返化，收口 FR-SETTLE-16「输出 = CSV/报表文件」张力。现态：SettleExportHandler 返回 JSON 信封（exportTime/exportedBy/columns/rows/rowCount），settle.js 浏览器侧 csvCell 拼装转 CSV 下载，横幅披露张力（:940-945）。与 T-193（同型张力曾判 P2 缺陷并修 CSV 直返）/T-195 双先例一致化。

## 要求

1. **后端直返化**：对齐单表导出先例形态（`ConfigExportHandler`：service 出 `List<String>` 已转义 CSV 行 → handler `HttpResult.bytes(200, "text/csv; charset=UTF-8", …)` + `Content-Disposition: attachment; filename="settle-export.csv"`；转义规则 = 既有 csvCell 同构：含 `,`/`"`/换行 加引号、`"` 翻倍）。落点自择（service 新增 `exportReportCsv(...)` 或 handler 内编排），**可见性强制/加总对账结论列/导出时间与导出人列/列序（columns 口径）零语义变更**——Service 取数路径不动，仅承载形态变。
2. **验收 3 语义保持**：无数据（rows 空）不生成文件并给提示——实测 Product/Config/Fund 先例的无数据处理同构承接（前端在响应无行时不触发下载、显式提示；后端空态契约与先例一致，勿发明新形态）。
3. **前端随改**（settle.js）：删 csvCell 拼装改 blob 直下载（HTML 字符串禁直接 appendChild 红线照旧）；横幅「形态披露」段改「已直返化（T-204）」并保留可见性范围/加总列/无数据提示说明；routes.js **零变化**（路径/方法对不变，勿触碰）。
4. **探针随迁**（t054_settle_probe）：:508-524 导出段随迁——「JSON 信封」张力披露断言改「已直返化」断言；新增/改造导出断言（桩返回 CSV 文本形态、无数据负例保持、调用路径断言保持）；断言总数变化须报口径（基线 113）。
5. **测试**：grep 现有 SettleExportHandler/exportReport 测试（编排者初测无 handler 专属测试）——须补直返测试（CSV 字节形态/转义负例〔含逗号引号换行的单元格〕/无数据空态/可见性过滤不回归）；先例测试形态（ConfigExportHandler 同构测试，若有）对齐。
6. **CONTRACTS 核对**：实测 `java/CONTRACTS.md` 对 /settle/export 响应形态的记载——若记载了 JSON 信封形态须同步订正并回报（记载形态以实测为准，勿凭记忆）；路径/方法对不变。
7. **门禁**：全量 `mvn -B -o clean test` ≥1248/0/0/0（1248+新增，@Test==surefire 口径）；`mvn validate` arch rc0；探针回归 t054 新基线 + t053 129/t055 100/t070 104/t071 71/t083 202 全绿；`node --check` 触碰 js；树锚（`cd <仓库> && find java -type f | sort | xargs md5sum | md5sum` 与 web 侧分别报，或 find web java 合并口径——附命令原文与文件数；勿在跑过 mvn 的树里算）。
8. **边界**：写域 = `SettleExportHandler.java` + `SettleService.java`（若编排放 service）+ `settle.js` + `tools/probes/t054_settle_probe.mjs` + `java/CONTRACTS.md`（仅当实测须订正）+ 新/改测试文件；**不碰** routes.js/ApiRoutes/SettleRoutes 注册面/其他 handler/文档（TASKS/BOARD/TEST_RESULT 编排者落盘）。
9. **交付形态（硬约束）**：主树直接工作（开工前 `git log --oneline -3` + `git status --short` 复核）；完成 `git commit --only <精确路径>`、提交号回报；工单处理记录追加一行。业务口径分歧停手 SendMessage 回报 team-lead。

## 回复要求

回报：提交号、numstat、CSV 直返形态要点（落点/转义/无数据契约/Content-Disposition）、无数据先例实测结论、CONTRACTS 核对结论、探针新基线四元组、既有探针回归、全量计数、arch rc0、树锚+文件数、边界遵守声明。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-26 | 编排者 | 用户批准派发（披露①裁定 A 承接；实例 be-t204） | OPEN，待 backend-engineer#java 处置 |
