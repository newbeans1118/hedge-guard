---
ticket: T-053
type: task
from: project-manager
to: frontend-engineer
priority: P0
created: 2026-10-22T12:00+08:00
refs: [docs/TASKS.md#T-053, docs/REQUIREMENTS/TRADE.md FR-TRADE-1~18, web/js/core/routes.js, web/js/core/ws.js, cr-t025 R1-004, docs/ARCHITECTURE.md §4.1.2（W-1 订阅清单）, java/src/main/java/com/hedgeguard/web/ApiRoutes.java]
---

## 事由

交易执行前端（含行情 WebSocket）——M2 前端最后主链。现 `web/js/pages/trade.js` 仅 65 行 stub；`routes.js` TRADE 键仅 `TRADE_ORDER: '/trade/order'` 1 项，TRADE 域 9 条「已声明未注册」路径（order-report/fill/position/reason/reject-record/flow/quote/strategy 等）的前端镜像登记缺口随本单承接（cr-t025 R1-004，2026-10-22 用户批准解锁派发）。依赖 T-017/T-046 均机读 DONE。

## 要求

1. **先实测后施工（交底不可信判例）**：开工前逐项取证——①后端 TRADE 面已实现 Handler 清单：读 `java/src/main/java/com/hedgeguard/web/handler/trade/`（T-046 交付）与 `ApiRoutes.java` TRADE 键，列出**全部已注册 (方法,路径) 对**（注意 cr-t025 实测 TRADE 差集 9 条系**前端镜像缺键**而非后端缺端点；与我不符请指出）；②wire 字段名以已实现 Handler 为准（camelCase，≠ DATA_MODEL snake_case）；③既有前端形态基准：`fund.js`（T-055 交付，7-Tab/1027 行）与 `risk.js`（T-070，9-Tab，端点注册面不对称=可接真接+未注册诚实提示先例）。
2. **trade.js 重写**（65 行 stub → 生产级）：覆盖 FR-TRADE-1~18 前端承载——下单（TRADE_ORDER 已注册键）/报单回报/成交/持仓/拒单/指令流水/行情展示等，按后端实际注册面分 Tab；后端未注册/未交付面**诚实提示**（risk.js NOT_REGISTERED 先例，勿造假接口调用）。
3. **行情 WebSocket 接线**：`web/js/core/ws.js`（既有 RFC6455 客户端）+ `T-040` 服务端契约（§4.1.2 W-1 订阅清单冻结 + 补数批次行情帧消费侧丢弃 CH-007 语义）；订阅/心跳/断线重连形态对齐 risk.js 既有 WS 用法；**WS 补拉 since 形态**（T-070 先例）。
4. **routes.js 镜像登记**：TRADE 键补齐至与后端注册面对齐（R1-004 的 9 条缺口逐条登记；纯追加；同步 `FullWebAssembly.ROUTE_COUNT`/`BootstrapAssemblyTest` 连锁计数**只报不改**——Java 侧计数属后端写域，在回报中列出预期变更值由编排者核）。
5. **探针**（headless DOM 桩 + 断言，落 `tools/probes/`）：对齐 t055/t070/t083 探针形态；判据 = 页面渲染/Tab 切换/API 调用路径与 routes.js 键一致/NOT_REGISTERED 诚实提示/负例 ≥2（假接口路径必红）。
6. **门禁**：全部既有前端探针回归（t083 202/0·t055 100/0·t070 104/0·t071 71/0 等）+ 新探针全绿；`node --check` 全部触碰 js 文件；arch-check rc0；树锚（`find web java -type f` 口径附时刻）回报。**Java 门禁若 routes.js 计数连锁波及**（routes.js 系前端只读镜像，Java 测试不读它——如实测波及列清单）。
7. **边界**：写域 = `web/js/pages/trade.js` + `web/js/core/routes.js`（TRADE 键段纯追加）+ `tools/probes/` 新探针；**不碰** ws.js 公共契约（消费侧用法自由）、settle.js（T-054 在飞并行实例写域）、fund.js/risk.js/acct.js/audit.js 既有页面、Java 侧任何文件。**前端红线**：HTML 字符串禁直接 appendChild（须 ui.el({html}) 包裹）；`{html:}` 须 F.esc 转义或静态；storage/口令零落盘；ESM 桩须全动态 import。
8. **交付形态（硬约束）**：主树直接工作（HEAD f6dfbb4 起，开工前 `git log --oneline -3` + `git status --short` 复核；若发现写域交叠在飞提交 ⇒ 停手回报）；完成 `git commit --only <精确路径>`、提交号回报。**不写** docs/TEST_RESULT/TASKS/BOARD（编排者落盘）；不置 DONE；工单处理记录追加一行（本单文件）。
9. 业务口径分歧 ⇒ 停手 SendMessage 回报 team-lead，不得自行裁定。

## 回复要求

- 回报：提交号、numstat、后端注册面实测清单（对接映射表：FR-TRADE-xx → 端点 → Tab）、探针 PASS/FAIL 四元组、既有探针回归结果、routes.js 增键清单、树锚+时刻、边界遵守声明。

---

## 处理记录

<!-- 每次处理追加一行，不覆盖历史。 -->

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-22 | 编排者 | 用户批准派发（第 255 轮 R1-004 解锁后主链派发；实例 fe-t053） | OPEN，待 frontend-engineer 处置 |
| 2026-10-22 | fe-t053 | 实施完成：trade.js 重写（65→546 行，8-Tab）+ routes.js TRADE 键 2 项/NOT_REGISTERED TRADE 9 键纯追加 + 新探针 tools/probes/t053_trade_probe.mjs（129/0）；既有探针回归 t083 202/0·t055 100/0·t070 104/0·t071 71/0·t071g rc0；node --check 触碰文件 rc0；arch-check rc0（java/ mvn validate 八判据全过）；提交 **4809373**（--only 精确路径，settle.js/t054 探针未触碰）；树锚 7b5cac83…/2026-10-25T18:33+08:00 | 已实施，待复核 |
