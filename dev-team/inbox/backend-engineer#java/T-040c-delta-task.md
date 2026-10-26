---
ticket: T-040c
type: task
from: project-manager
to: backend-engineer#java
priority: P2
created: 2026-10-25T23:00+08:00
refs: [docs/TASKS.md#T-040, te-verify-t040 报告 /root/t040-verify.md（md5 9c993814…/178）, 提交 5d1d708（被补测交付）, java/src/main/java/com/hedgeguard/web/Bootstrap.java:831-838/:537, java/src/test/java/com/hedgeguard/web/WsProductionAssemblyTest.java:28-31/:161/:178, TE-T040-V-001]
---

## 事由

te-verify-t040 复核＝**有条件通过**，条件 = TE-T040-V-001（P2 测试判据缺口）delta 闭环。用户已批准派发本 delta 单。

V-001 实测（复核席独立亲证，编排者核采信；与编排者交付期亲证存活一致）：

- **M1**：摘 `Bootstrap.java:834` `consumers.add(new WsQuoteBridge(wsServer.server()))` ⇒ 全量 1246 恒绿。根因：组合器清单系方法体内局部构造（:831-832 `new ArrayList<>(Arrays.asList(feed, sink))`），测试环境 `hgcpClient=null` 走 fallback 同构造路径，**绕过被摘行**；`WsProductionAssemblyTest` javadoc :28-29「摘行情桥 ⇒ endToEndQuoteFrameReachesWsClient 必红」**声明失实**。
- **M2**：摘 `Bootstrap.java:537` lambda 内 `server.publishAlert(...)` 调用（保留 notifier 槽引用非 null）⇒ 全量恒绿。根因：:178 正证①只结构性击杀「槽置 null」变异；摘 lambda 委托行时**唯一击杀器不在装配测试**（无授权客户端经生产 lambda 收 alert 帧的判别器）。复核席细化界定：「摘 notifier 产出」（holder 置 null）已被 :179 assertNotNull 击杀，无牙的只是 lambda 委托行。
- M3（3 红）/M4（2 红）有牙不回归；生产行为正确，**不判不通过**——缺的是测试判别力。

## 要求

1. **M1 判别力补齐**：把组合器清单构造提取为**包内可见接缝**（Bootstrap 内静态/实例方法，如 `buildEventConsumers(...)`，**行为零变更·最小 diff**；feed/sink/wsQuoteBridge 组装逻辑原样搬移）；装配测试新增断言：接缝产物清单含 WsQuoteBridge 实例（且生产路径与 fallback 路径分别取证，防再绕行）。验收：摘 `consumers.add(WsQuoteBridge)`（或接缝等价点）⇒ **全量必红**（clean 重建）。
2. **M2 判别力补齐**：装配测试播种**授权客户端**（授权行使形态）订阅 alert 通道，经生产 lambda（:537 `server.publishAlert(...)`）真实注入告警 ⇒ 断言客户端收到 alert 帧。验收：摘 lambda 内 publishAlert 调用行（槽仍非 null）⇒ **必红**。
3. **javadoc 订正**：`WsProductionAssemblyTest.java:28-31` 变异声明改为与实测击杀器一致的表述（修复后原声明若成真可保留并注明本轮验证；失实部分必须改）。**「变异必红」声明失实须 delta 非披露**（T-040 复核判例）。
4. **变异复证矩阵**：M1/M2 修复后各自必红 + M3/M4 既有击杀器不回归 + 自设 1 个断言侧变异证判据非恒绿。全部 clean 重建（`mvn -B -o clean test`，surefire 残留假红判例），复位后 md5 对照。
5. **门禁**：全量 ≥1246/0/0/0（1246+新增用例数，@Test==surefire 口径）；`mvn validate` arch rc0；树锚（`cd <仓库> && find java -type f | sort | xargs md5sum | md5sum`，**纯净口径勿混 target**——先测后报告，附命令原文与文件数）。
6. **边界**：写域 = `Bootstrap.java`（仅 M1 接缝提取）+ `WsProductionAssemblyTest.java`（或同包新增测试类）+ javadoc；**不碰** HgcpClient/HgcpEventListener/WsServer 公共契约/CONTRACTS/DATA_MODEL/前端/文档（TASKS/BOARD/TEST_RESULT 编排者落盘）。行为零变更——生产 diff 仅限接缝提取搬移。
7. **交付形态（硬约束）**：主树直接工作（开工前 `git log --oneline -3` + `git status --short` 复核）；完成 `git commit --only <精确路径>`、提交号回报；工单处理记录追加一行。业务口径分歧停手 SendMessage 回报 team-lead。

## 回复要求

回报：提交号、numstat、M1/M2 变异复证（击杀器用例名+失败输出）、M3/M4 不回归证据、全量计数、arch rc0、树锚+文件数、边界遵守声明。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-25 | 编排者 | 用户批准派发（TE-T040-V-001 delta 补测；实例 be-t040c） | OPEN，待 backend-engineer#java 处置 |
| 2026-10-26 | be-t040c | 交付提交 **a9404cb**（numstat：Bootstrap 29+/3-·WsProductionAssemblyTest 134+/14-）。M1=接缝 `buildEventConsumers(feed,sink,WsServerHolder)` 提取（行为零变更）+接缝直证/e2e 改喂接缝产物；M2=新用例经生产 lambda 收 alert 帧（授权行使形态=订阅置 {7}·求交语义由 WsAuthorizerTest/WsServerTest 覆盖·假驱动 fail-closed 空集已在用例 javadoc 披露——复核报告建议的「经 TxTemplate 播种授权行」在 FakeJdbcDriver 下不可行，SQL 走 null 代理，故采反射等价手段）；javadoc 判据声明订正。变异矩阵（全部全量 clean 重建·复位 md5 三文件对照 OK）：M1=2 红/M2=1 红/M3=3 红/M4=2 红（与复核报告逐一吻合）/断言侧反转变异=1 红；复位后 **1248/0/0/0 + mvn validate rc0**；树锚（git archive a9404cb 快照·`find java -type f \| sort \| xargs md5sum \| md5sum`）=**e1d0534251486a5ea12cd5e087bb9b40 / 773 文件**（基线 5d1d708=1070e223…/773 同文件数，无新增文件） | DONE，待独立复核 |
