# CH-027 行情订阅动态化：新增 HGCP 控制指令（L3 契约级）

> **裁定单编号**：`CH-027`（现行最大 `CH-026` 顺延）—— 编排者登记
> **裁定来源**：用户 2026-11-25 连续三轮指令与裁定
>   ① 「通过订阅改变行情合约未成功……增加查询合约并选择订阅的功能」；
>   ② 三档通道裁定选 **C 新增 HGCP 命令**（否决 A 本地文件 / B 前端过滤）；
>   ③ 见到 40→41 完整连锁代价后**坚持 C**。
> **性质**：**L3 契约级变更，用户批准** —— 首次改动「消息集 40」冻结不变量（→41）；
>   升 `HGC-1.8`；新增 `TradingBridge` 第八方法；同步 E7/E9 判据常量与 ARCH/CONTRACTS 冻结表。
> **时点**：2026-11-25；本文引用的行号 / 版本 / 计数 **以实时为准**。
> **依据链**：`PROJECT.md` · `docs/ARCHITECTURE.md` §4.2/§3.0.2/§11 · `java/CONTRACTS.md`（HGC-1.7）·
>   `proto/hgcp.proto` · `cpp/src/hgcp/include/hg/hgcp/protocol.h` · `tools/arch-check/{scan_hgcp_schema,scan_frozen_invariants}.py`

---

## 1. 裁定结论

| # | 裁定项 | 选定值 |
|---|--------|--------|
| ① | 动态订阅通道 | **新增 HGCP 控制指令** `CMD_SET_QUOTE_SUBSCRIPTION = 0x011F`（Java→C++），C++ 退订旧集 + 订阅新集 |
| ② | 合约目录 | **前端静态目录**（按交易所分组、品种→合约代码规则生成），标注「非权威全市场、随换月维护」 |
| ③ | 主力口径 | **按月份规则近似**（每品种取最近活跃月），标注「静态口径、非实时持仓量判定」 |
| ④ | 冻结面 | **消息集 40→41**；`HGC-1.7→1.8`；`TradingBridge` 七→八方法；E7/E9 常量同步；用户批准 |
| ⑤ | 交付形态 | 分阶段落地，每阶段门禁验证；本文为裁定与实施留痕，不改历史记录行 |

## 2. 事实基线（实测）

- 前端 `conn.subscribe({instruments})` 只到 Java：`WsServer.doSubscribe` 设 `WsSubscription` 过滤集，**不下发 C++**。
- `WsSubscription.matchesQuote` 有定义但**零调用点**；`flushQuotes` 仅判 `wantsQuote()` ⇒ W-1 `instruments` 对实时帧根本不生效（仅快照用）。
- C++ `trading_app.cc` 只在 MD 登录时按 `hg.ctp.subscribe_instruments` 订阅一次（`md_subscribed_` 去重）。
- CTP 有 `UnSubscribeMarketData`（`ThostFtdcMdApi.h:142`）；项目网关未暴露，需补封装。
- 消息集 40 = 元协议 9 + 控制指令 17 + 事件流 14；顶层字段号 214 = 38+77+99。
- `protoc 3.21.12` 可逐字重生成 `Hgcp.java`（67103 行一致）。

## 3. 冻结面连锁（用户已批准）

| 制品 | 变更 |
|------|------|
| `proto/hgcp.proto` | `MsgType` 加 `CMD_SET_QUOTE_SUBSCRIPTION=0x011F`；新增 `message CmdSetQuoteSubscription{ repeated string instruments=1; }` |
| `java/.../hgcp/v1/Hgcp.java` | protoc 重生成（生成文件，DO NOT EDIT） |
| `cpp/.../protocol.h` | 枚举 + `kMessageTypeCount 40→41` |
| `HgcpConstants.java` | 新常量 + `MESSAGE_TYPE_COUNT 40→41` + `isCommand` 上限 |
| `protocol.cc`/`payload_codec.cc`/`command_handler.cc`/`HgcpClient.java` | 各加分支 |
| `TradingBridge.java` | 第八方法 `setQuoteSubscription(List<String>)` |
| `java/CONTRACTS.md` | HGC-1.8、§3.10/§2.2 签名、§3.2.1 payload、§4.2.8、§5.1 编辑登记表、§13 |
| `docs/ARCHITECTURE.md` | §4.2.3「共40种→41」+表、统计、§4.2.8.1 `214→215`/`77→78`、§4.2.8.3「17→18」+表、§13 |
| `scan_hgcp_schema.py` (E7) | `TOP_MSG_COUNT 40→41`、`TOP_TOTAL 214→215`、`SEC_EXPECT 控制段 77→78` |
| `scan_frozen_invariants.py` (E9) | `MSG_TOTAL 40→41` |
| REST 端点 | `ApiRoutes.java` + `web/js/core/routes.js` 登记 + 四处 `ROUTE_COUNT` 硬编码 |
| `WsServer.java` | `flushQuotes` 应用 `matchesQuote`（修复既有缺陷） |

## 4. 不变项（红线）

- 错误码 **62**、`VER 0x0100`、**FR 184** 均不变；既有 40 条消息的字段名·号·类型·必填性**逐字不动**（纯增量）。
- 既有部署重启后行为不变（未配置新命令时订阅逻辑与 §18/§19 完全一致）。
- 行情仍只展示、不驱动任何自动决策（R-TRADE-11）；不下单、无实盘资金。
- 零落盘：订阅集仅内存 + 本地 admin 可持久化范围；口令/令牌不落盘。

## 5. 验收

E1–E9（含 E7/E9 新常量自洽）、双轨 ctest、`mvn -B -o clean test`、前端探针全量、
`health-check.sh all`、端到端动态订阅（选合约→C++ 重订阅→WS 只推选中合约）。

---

## 6. 实施与验证结论（2026-11-25 12:33 +0800 · 逐条留痕，完整账见 `docs/BACKLOG.md` §22）

| 验收项 | 状态 | 证据 |
|---|---|---|
| E1–E9 冻结面自洽（41 消息 / 62 错误码 / VER 0x0100 / FR 184） | ✅ | `mvn -B -o validate` 9/0/0 |
| Java 全量单测 | ✅ | `mvn -B -o clean test` **1564 / 0 / 0**（+1 W-1 白名单负向回归） |
| C++ 双轨 ctest（clean env） | ✅ | 真轨 67/67、stub 轨 63/63 |
| 前端探针 | ✅ | 全量 10/10；`t053` 167 断言 ALL PASS |
| 组件健康 | ✅ | `health-check.sh all` PASS=6、`state=READY param_version=23` |
| **运行期命令链**（REST→HGCP 0x011F→C++ 回调→`CtAdapter` 退订/订阅） | ✅ | 实测 `POST /trade/quote-subscription` 返回 `acknowledged:true, componentCode:0`，日志 `ctp.md set-subscription count=4 detail=…applied: count=4`；恢复原 11 合约 `count=11`；原生大小写逐字保留 |
| **帧级端到端**（页面选合约→WS 帧集合随订阅改变） | ⏸ **待复验** | 12:20 处于 11:30–13:00 午间休市，WS 12s 内 0 帧（非代码故障）；行情前置 30011 已登录、订阅集已就位，待交易时段内复验 |

**口径澄清**：`ctpLoggedIn=false` 取 `trade_logged_in_`（交易前置），而行情订阅守卫取 `md_logged_in_`（行情前置）；本轮 `setQuoteSubscription` 返回 `applied` 而非 `rejected` ⇒ 行情前置登录回调已成功。交易前置 DEGRADED 系上轮两台进程同时停机导致一次性 `HG_CTP_PASSWORD` 丢失（零落盘、不可恢复），属**独立事项**，不影响行情帧；如需恢复完整态须重注口令并重启。

**裁定兑现**：①②③④⑤ 全部落地；冻结面 40→41、`HGC-1.8`、第八方法、E7/E9 常量同步均经门禁自洽验证。本文为裁定与实施留痕，历史记录行不回改；后续帧级复验结论追加至 `BACKLOG.md`，不改写本节。

---

## 7. 帧级复验结清（2026-11-25 13:09 +0800 · 显式登记，不回改 §6）

> §6 表中「帧级端到端 ⏸ **待复验**」一行由本节**显式登记结清**（原文保留、不回改）；完整逐条账见 `docs/BACKLOG.md` §23。

- 13:07 交易时段恢复后实测（未重启、未重注口令）：基线 → `POST ["rb2701","IF2612"]`（`applied: count=2`）→ WS 14s 帧集恰为 `{IF2612, RB2701}`，**旧集合约 0 残留帧** → 恢复 11 合约后 11 合约全部回归。
- 三次 REST 均 `acknowledged=true / componentCode=0`；C++ 日志 `ctp.md set-subscription count=2/11`（日志行 379/380）留痕。
- ⇒ 「选合约 → C++ 重订阅 → WS 帧集合随订阅改变」闭环成立；**验收矩阵全绿，CH-027 结案**。
- 余项（非阻塞）：交易前置 30001 因一次性口令丢失仍 DEGRADED，行情帧走 MD 前置不受影响；如需恢复完整态须重注口令并重启，另案留痕。
- 仍 dev/test 档、零落盘；改动未提交 git，提交 / push 需单独指令。
