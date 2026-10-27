---
ticket: T-205
type: task
from: project-manager
to: backend-engineer#java
priority: P0
created: 2026-10-27T09:00+08:00
refs: [docs/TASKS.md（本单立号 T-205）, T-086 复核缺口 G-6/G-7（/root/t086-verify.md + §T086-5）, PROJECT.md §10-12/13/20/24, M4.md TC-M4-25（护栏预演判据）, R-FUND-7（额度告警不阻断——**语义边界，勿混淆**）, assertCanTrade M2 强制点先例（T-102·TradeService javadoc :67-69）, FundService 额度面（:145-158/:1008）, R-TRADE-13 不得带病运行]
---

## 事由（T-086 贴标条件①·P0 合并单）

T-086 复核缺口两项功能级缺口（终验段 T-087 排程前必须闭环）：
- **G-6**：额度→下单阻断无 Java 接线——`TradeService` 零 fund 引用；超限指令阻断判定全在 C++（事前风控），Java 提交路径无护栏，TC-M4-25 判据「超限指令被阻断且不发 CTP（Java 调用计数==0）」在 Java 侧不可达。
- **G-7**：紧急停止动作零承载（全库 grep 零命中）。

**语义边界（硬约束，防混淆）**：`R-FUND-7` 额度系**业务额度告警不阻断**（既有·不动）；本单新增系 **L3 实盘护栏强制点**（资金上限 hard stop·独立语义层）——两者并存、命名与留痕分离。

## 要求

1. **先提取原文（先定义后施工）**：`PROJECT.md` §10-12/13/20/24 与 M4.md TC-M4-25 全文逐句提取，列依据行号；护栏语义要件（触发条件/阻断对象/告警/留痕）在 §12 设计说明中显式定义——与工单框定不符或有歧义 ⇒ 停手 SendMessage 回报，勿自行裁定。
2. **G-6 护栏强制点**：`TradeService` 提交路径（`assertCanTrade` 之后·同形强制点先例）新增「资金上限护栏校验」——触发：占用 ≥ 上限（CFG 键·账户+子账户两级按你实测 FundService 取数面定形态）⇒ 拒绝（专用错误码·PARAM/QUOTA 域）+ 告警留痕（对齐 FundService 告警面形态）+ **不发 CTP**；CFG 开关（护栏启用态·缺省值与缺省行为须显式定义并披露）。**R-FUND-7 告警面零改动**。
3. **G-7 紧急停止**：最小承载形态（工单框定）：CFG 键（如 `hg.trade.emergency_stop`·布尔）——true ⇒ `TradeService` 全部新提交拒绝（专用错误码+留痕+告警），在途指令/既有流程不受影响（R-TRADE-13 边界）；切换入口 = 既有 CONFIG 参数面（零新端点）。PROJECT/M4.md 原文如有超出此形态的语义要件（如全平仓），**列回报编排者裁，勿自行扩面**。
4. **测试**：新测试类覆盖——护栏触发（临界+超限）/不触发（低于上限）/开关关闭直通/紧急停止全拒+在途不受影响/错误码与留痕断言；变异 ≥2（摘护栏校验/摘紧急停止判断·clean 重建复位 md5）；测试 SQL 落 dao/testsupport（E5 合规）；断言锚冻结域字面量。
5. **门禁**：全量 `mvn -B -o clean test` = 1278 基线+新增（@Test==surefire 口径）；`mvn validate` arch rc0；CFG 种子（若新增键）落 data-init.sql（先 git status 核无在飞触碰）+ 计数锁连锁逐点更新（ConfigSeedDataTest/README/data-init 头注——T-081b 先例）；树锚 find java 规范形态（附命令原文与文件数）。
6. **边界**：写域 = `TradeService.java` + 新测试类 + `data-init.sql`（仅新键）+ 计数锁连锁三件 + `java/CONTRACTS.md`（仅当错误码新增须登记——实测 CONTRACTS 错误码表形态后定）+ 工单处理记录；**不碰** FundService 既有告警面/C++/前端/TASKS/BOARD/TEST_RESULT（证据章由复核席或编排者落盘——本单报告落 `/root/t205-report.md`）。ARCH 落登（护栏/紧急停止语义）挂账转 tech-lead（TL-ARCH 模式·工单内披露）。
7. **交付形态（硬约束）**：主树直接工作（开工前 `git log --oneline -3` + `git status --short` 复核·确认无在飞写域交叠——be-t199 已收工、te-verify-t199 只读）；`git commit --only <精确路径>`、提交号回报；工单处理记录追加一行。业务口径分歧停手回报。

## 回复要求

回报：提交号、numstat、原文提取清单（行号）、护栏/紧急停止设计要点（键名/缺省/错误码/留痕形态）、G-6 接线点行号、G-7 边界（在途不受影响证据）、变异证据、全量计数、arch rc0、CFG 连锁清单、树锚+文件数、边界声明。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-27 | 编排者 | 用户批准立号 T-205+派发（T-086 条件①·G-6/G-7 合并单·实例 be-t205） | OPEN，待 backend-engineer#java 处置 |
