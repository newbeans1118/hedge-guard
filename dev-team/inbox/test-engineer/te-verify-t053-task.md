---
ticket: te-verify-t053
type: review
from: project-manager
to: test-engineer
priority: P0
created: 2026-10-25T20:30+08:00
refs: [docs/TASKS.md#T-053, dev-team/inbox/frontend-engineer/T-053-task.md, 提交 4809373（交付）/438463b（处理记录）, docs/REQUIREMENTS/TRADE.md FR-TRADE-1~18, java/src/main/java/com/hedgeguard/web/handler/trade/TradeRoutes.java, java/src/main/java/com/hedgeguard/web/ApiRoutes.java, docs/ARCHITECTURE.md §4.1.2/§5.3, CH-007]
---

## 事由

T-053 交易执行前端（含行情 WebSocket）交付 `4809373` 已落共享树，状态「已完成，待独立复核」。你是独立复核席，**不采信产出方自评**——所有判据独立取证，证据分列「独立实测」vs「产出方自述」。

交付声明（供对照，非证据）：trade.js 65→569 行 8-Tab；routes.js TRADE 键 2 项纯追加 + NOT_REGISTERED TRADE 9 键；探针 t053_trade_probe 444 行 129 断言全过；回归 t055 100/t070 104/t071 71/t083 202；node --check rc0；arch-check rc0；Java 门禁零波及（交付时点基线 1238/0/0/0，BootstrapAssemblyTest 129 对不变）；树锚 7b5cac83…（find web java 口径）；numstat = t053_trade_probe 444/0 + routes.js 29/3 + trade.js 525/44。

## 复核纪律（硬约束）

1. **隔离副本**：`git archive 4809373 | tar -x -C /tmp/t053-verify`（纯净树免 target 污染；副本须含 `tools/`、`java/`、`web/`、`docs/`）。所有变异/探针只在副本内做；**主树零改动**（只读：git show / md5sum / grep）。
2. **冻结点注意**：当前 HEAD（3cd21b9）routes.js 已含 T-054（dc376df SETTLE 键）与 T-040（5d1d708）后续交付——凡「与 HEAD 不同的 routes.js 内容」属 T-054/T-054 写域，**不得计入 T-053**；核对面一律锚定 4809373 快照。
3. **树锚仲裁**：7b5cac83… 用规范形态复现（`cd <副本> && find web java -type f | sort | xargs md5sum | md5sum`，附命令原文与文件数；树锚对命令路径形态敏感——T-078 判例，非规范变体不复现不算产出方错，须先自证用了同形态）。
4. **全绿≠无缺陷**：门禁复现之外必须自设扫描面（下方 §扫描面）；「产出方已披露」≠已排查。
5. **变异必 clean**：变异后探针重跑须重新加载文件（node 无编译缓存问题，但须确认跑的是变异后副本）；每个变异给「击杀器用例名 + 失败输出」；变异结束复位并 md5 对照。
6. **结论分级**：通过 / 有条件通过（列条件）/ 不通过；缺陷编号 TE-T053-V-001 起，标 P1/P2/P3；观察 O-x 不阻断；阻塞自述（如「零波及」）须核实不得直接采信。

## 门禁复现（独立实测）

在隔离副本内逐项跑并记录 rc 与计数：
- `node tools/probes/t053_trade_probe.mjs` ⇒ 声称 129/0
- 回归：`t055_fund_probe.mjs`(100) / `t070_risk_probe.mjs`(104) / `t071_audit_probe.mjs`(71) / `t083_acct_probe.mjs`(202) / `t071_probe_globals.mjs`(rc0)
- `node --check` 三触碰文件
- `cd java && mvn -B -o clean test` ⇒ 声称零波及 1238/0/0/0（4809373 时点基线 1238；**当前主树基线 1246 含 T-040 +8，勿混淆**）；如全量太重，至少跑 `BootstrapAssemblyTest` + `mvn validate`（arch rc0），并在报告注明口径
- numstat 对账：`git show 4809373 --numstat` 与交付声明逐行吻合

## 自设扫描面（逐项给结论，含「不适用」清单）

1. **routes.js 键对账**：TRADE 键 2 项（ORDER + orderAction 参数化）与 `ApiRoutes.java` BY_MODULE.TRADE / TradeRoutes.java 已注册 (方法,路径) 对逐字对账；NOT_REGISTERED 9 键与后端未注册读取面 9 路径一一对应（多键/缺键/错路径均记缺陷）；**TRADE_ORDER 历史键必须保留**（t070 探针锚，删除即红）；确认纯追加（`git show 4809373 -- web/js/core/routes.js` 无既有键删改）。
2. **wire 字段对账**：trade.js 请求体字段名 vs `TradeService.SubmitRequest/ReasonInput` 实测 camelCase 逐字段（≠ DATA_MODEL snake_case）。
3. **业务预检承载**：NFR-USE-2 预填 / R-TRADE-1 绑定红线预检 / R-TRADE-9 理由结构化必填（CH-004：提交路径缺关联对象即拒）/ R-TRADE-7 cancel 仅未成交 / confirmToken 必填（0x1018 单次消费语义披露）/ 幂等头 / 二次确认——逐项在 trade.js 找到实现点（行号）并在探针里找到对应断言；有实现无断言记观察。
4. **WS 形态**：/ws/quote 订阅接线、最近值语义、throttle 节流（§5.3）、过期行情显式标识（FR-TRADE-12 验收2）、CH-007 补数帧消费侧语义披露、防僵尸连接（risk.js 同型）——形态对齐 risk.js 既有用法，ws.js 公共契约零改动（`git show 4809373 --stat` 确认未触碰）。
5. **XSS 面**：全文件扫 `{html:` 赋值点——值须为 F.esc(...) 包裹或纯静态字面量；扫 `appendChild` 直接接 HTML 字符串的违规（红线判例：须 ui.el({html}) 包裹）；含插值模板串逐一核对转义。
6. **未注册诚实提示**：未注册 Tab 零网络调用（NOT_REGISTERED 语义）；探针负控「假路径必红」实测成立。
7. **探针判别力**：抽查 t053_trade_probe 判据形态是否与被测对象合法取值重合（假绿盲区判例）；**至少 2 个变异实测必红**（建议：①摘 trade.js 幂等头注入点 ②摘 routes.js 某键或 NOT_REGISTERED 键；另自选 1 个探针断言侧变异验证判据非恒绿）。
8. **ESM 形态**：`node --check` 之外核对桩形态（ESM 桩须全动态 import 判例）；storage/口令零落盘扫描。

## 报告

- 落 `/root/t053-verify.md`：结论 + 门禁复现四元组 + 扫描面逐项结果（独立实测 vs 产出方自述分列）+ 缺陷/观察清单 + 树锚复现命令与值 + 变异清单（击杀器/存活判定与理由）。
- 回执给 team-lead：报告路径 + md5 + 行数 + 结论一句话 + 缺陷/观察计数。
- **不贴标、不改 TASKS/BOARD/TEST_RESULT**（编排者落账）；主树零改动。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-25 | 编排者 | 用户批准派发（三线待复核第一单；实例 te-verify-t053） | OPEN，待 test-engineer 处置 |
