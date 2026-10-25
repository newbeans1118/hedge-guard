---
ticket: te-verify-t040
type: review
from: project-manager
to: test-engineer
priority: P1
created: 2026-10-25T22:00+08:00
refs: [docs/TASKS.md#T-040, 提交 5d1d708（be-t040b 续作，承接裁定方案 A）, docs/ARCHITECTURE.md §4.1.2（W-1/W-2 订阅清单）§5.3, CH-007（补数帧消费侧语义）, java/CONTRACTS.md HGC-1.6, D-19 Bootstrap 受配置保护装配, docs/TASKS.md#T-028（WS A′ 基线）, CH-032/CFG-32 装配开关]
---

## 事由

T-040 Java 端行情 WebSocket 服务端生产装配接线交付 `5d1d708`（单提交，Java-only 写域），状态「已完成，待独立复核」。

交付声明（供对照，非证据）：7 文件 +825/−12——HgcpEventListener onMarketQuote+MarketQuote 载荷、HgcpClient quote case（+20）、Bootstrap CFG-32 装配开关+WsServer+桥+告警 notifier（244/10）、CONTRACTS §5.1（+1）、测试 527 行（HgcpMarketQuoteBridgeTest 180 + HgcpResumeMarketQuoteDropTest 7/2 + WsProductionAssemblyTest 340）；全量 **1246/0/0/0**（基线 1238+8）/ arch rc0 / 树锚 57bc843a…/**769**（find java 口径）；CH-007 拦截分支 Bootstrap:281-287 逐字节未动。

**★编排者预仲裁事项（你须独立判定处置，勿采信编排者结论）**：交付变异 M3 有牙（2 红亲证）；**M1+M2 存活**——产出方自述红系误报：M1 测试环境 fallback 同构造路径不经组合器、M2 唯一击杀器不在装配测试；编排者初判「测试判据缺口非交付缺陷，生产路径真实接线 + W-2 面由 WsServerTest:221 覆盖」。你须：①独立复测 M1/M2/M3 三变异形态与存活理由；②独立裁定处置——「测试判据缺口须补测（delta 单）」vs「判据缺口披露接受（观察级）」vs「系交付缺陷（不通过）」，报告给结论与理由。

## 复核纪律（硬约束）

1. **隔离副本**：`git archive 5d1d708 | tar -x -C /tmp/t040-verify`；变异与运行全在副本内；**主树零改动**。
2. **树锚**：产出方 57bc843a…/769 为 find java 口径——你用规范形态复现（`cd <纯净副本> && find java -type f | sort | xargs md5sum | md5sum`，附命令原文与文件数）；**勿在跑过 mvn 的副本里算**（target 污染，T-053 O-1 判例）；对不上先核命令形态（T-078/T-202 判例），仍不复现则报告双向疑点。
3. **全绿≠无缺陷**：门禁复现外必须自设扫描面；「编排者已亲测」≠证据。
4. **变异纪律**：变异后 clean 重建（`mvn -B -o clean test` 不复用 target，surefire 残留假红判例）；每变异给击杀器用例名+失败输出；结束复位 md5 对照。
5. 结论分级：通过/有条件通过/不通过；缺陷 TE-T040-V-001 起分 P1/P2/P3；观察 O-x；阻塞自述须核实。

## 门禁复现（独立实测，隔离副本内）

- `cd java && mvn -B -o clean test` ⇒ 声称 1246/0/0/0（5d1d708 时点基线 = 1238+8；主树现行 1246 同口径，勿混淆 T-054 基线 1238 的快照差异）
- `cd java && mvn validate`（arch rc0，E1–E8）
- numstat 对账：7 文件逐行与上文吻合

## 自设扫描面（逐项给结论，含「不适用」清单）

1. **CH-007 守卫零触碰**：`git show 5d1d708 -- java/src/main/java/com/hedgeguard/web/Bootstrap.java` 核拦截分支（声称 :281-287）逐字节未动；补数批次行情帧消费侧丢弃语义（T-174）在装配后仍成立。
2. **CFG-32 装配开关语义**：开关键名/缺省值/关闭形态（D-19 `hg.db.url` 受配置保护装配先例——关态须零副作用：不开 WsServer 端口、不注册桥、告警 notifier 静默）；开态装配完整链（HgcpClient quote case → HgcpEventListener onMarketQuote → 桥 → WsServer 广播）逐环节行号取证。
3. **CONTRACTS §5.1 变更合规**：+1 行具体内容；冻结契约变更规则（CH 流程/向后兼容澄清级先例）是否满足——若属实质变更须报「待裁定」。
4. **W-1/W-2 订阅清单一致性**：装配面订阅实现 vs ARCHITECTURE §4.1.2 冻结清单；WsServerTest:221 覆盖声明核实。
5. **0x020C 不占 SEQ 语义**：行情帧在桥/HgcpClient 解码侧不推进 SEQ 的实证（编排者称由 C++ emit_quote 实证——Java 侧测试是否也有承载）。
6. **W-5 快照未接披露核实**：披露属实性（只核实，处置建议可给）。
7. **告警 notifier 面**：告警 sink 是否零落盘/无外部副作用；与既有告警形态一致性。
8. **测试判据判别力**：三测试类的关键断言抽查（假绿盲区判例：断言是否锚定生产行为而非同源常量）；结合预仲裁 M1/M2 存活分析给出处置结论。
9. **E5 合规**：测试内 SQL 字面量（若 HgcpMarketQuoteBridgeTest 用引擎）落点是否合规（service 包 SQL 字面量会被 E5 拦——既有先例）。

## 报告

- 落 `/root/t040-verify.md`：结论 + 门禁四元组 + 扫描面逐项（独立实测 vs 产出方自述分列）+ 缺陷/观察 + 树锚复现 + M1/M2/M3 变异复测与处置裁定理由。
- 回执给 team-lead：报告路径 + md5 + 行数 + 结论一句话 + 缺陷/观察计数 + M1/M2 处置裁定。
- **不贴标、不改 TASKS/BOARD/TEST_RESULT**；主树零改动；契约/业务口径分歧标「待裁定」不替裁。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-25 | 编排者 | 用户批准派发（三线待复核第三单；实例 te-verify-t040） | OPEN，待 test-engineer 处置 |
