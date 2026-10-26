---
ticket: T-081b
type: task
from: project-manager
to: backend-engineer#java2
priority: P0
created: 2026-10-26T22:10+08:00
refs: [docs/TASKS.md#T-081, INV-081082 盘点报告 /root/inv-081082.md §1.2（md5 92a0c9ed…/121）, R-AUDIT-5 条件①, FR-AUDIT-7/8, docs/REQUIREMENTS/AUDIT.md, MajorEventLossThresholdTrigger.java:244（条件⑤先例）, T-188 双轨触发先例, DmMajorEventDao uk=event_no 单列]
---

## 事由（T-081 重做单·缺口=一个窄增量触发器+接线+幂等判据）

T-081 完成断言指向已丢失 git 线（账实背离 2026-10-26 实锤·用户裁定 A 重做）。INV-081082 盘点结论：验收 1~4（FR-AUDIT-8 证据链导出四面）已由当前链 T-067/T-193 线**在树承载**、`t_settle_pnl` 轨1 由 T-189 在树——**缺口仅 6 项**（报告 §1.2）：条件①触发器 / aggregate 提交后接线 / 24h CFG 超时种子 / 幂等 AGG 键 / 跨日幂等用例+M1′ 变异 / TEST_RESULT §T081。**重做 = 窄增量，预估 6~8h（原 16h）**。

**⏸ 派发时序**：本单含 TEST_RESULT 追加，与 T-082b（同文件写域）串行——**待 T-082b 交付贴标后再派**（编排者调度）。

## 要求

1. **条件①触发器**（R-AUDIT-5「加总对账不一致且超时未闭环」自动写入方）：新建 `AggregationInconsistencyTrigger`——语义：加总对账不一致事件存在且超时未闭环（CFG 键缺省 **24h**·键名你定如 `hg.audit.agg_inconsistency_timeout_hours`）⇒ 自动写 `t_major_event`（事项类型=聚合不一致提醒·含聚合上下文字段）；形态对齐 `MajorEventLossThresholdTrigger.java:244` 条件⑤先例（T-188 双轨触发·`t_major_event` 写入方现仅 2 处：⑤+人工 `AuditReportService.java:222`）。
2. **aggregate 提交后接线**：`SettleService.aggregate`（`:1256`）提交后调用触发器（对齐 :543-544 轨1 接线先例形态）；SettleService 改动**最小化**（接线点+参数传递，不动既有关账门禁 R-SETTLE-2 阻断逻辑 :1312/:1464/:1521）。
3. **幂等 AGG 键**：`eventNo` 采用 **tradeDate 稳定键**（丢失线判例：取触发日会跨日重复落行——锚业务日勿锚触发时点）；`DmMajorEventDao` uk=event_no 单列引擎强制 ⇒ 幂等=确定性编号（tradeDate 派生）+ uk 冲突即跳过（跨日不重复、同日不重刷）。
4. **CFG 种子**：24h 缺省键落 `docs/DEPLOY/schema/data-init.sql`（形态对齐既有 CFG 键；**data-init.sql 为共享文件**——先 `git status` 确认无在飞实例触碰，发现写域交叠停手回报）。
5. **测试**：跨日幂等用例（D+1 再触发不重复落行）+ 正常触发用例 + M1′ 变异（摘 tradeDate 稳定键改触发时点 ⇒ 必红·clean 重建）+ 24h 缺省值断言；测试 SQL 落 dao/testsupport（E5 合规）。
6. **FR-AUDIT-7 验收 2/3/4 与 R-AUDIT-5 ②③④**：**复核引用不施工**（盘点报告 §1.3：报告五节字段/事项类型拒绝/留痕已在树；②③④无自动写入方系范围外观察——触发器 javadoc 显式声明本单仅承接条件①）。
7. **证据章 §T081**：`docs/TEST_RESULT.md` **纯追加** §T081 段（门禁+触发器/接线/幂等判据+变异+承载复核引用+背离承接链）；追加前测尾部 md5。
8. **门禁**：全量 = 开工基线+新增（≥1258+T-082b 增量后现值，开工时实测）；arch rc0；树锚 find java 规范形态。
9. **边界**：写域 = 触发器新类 + `SettleService.java`（仅接线点）+ CFG 种子 data-init.sql（仅新键）+ 新测试类 + `docs/TEST_RESULT.md`（仅 §T081 纯追加）+ 工单处理记录；不碰 DmMajorEventDao 公共契约（uk 不变）/其他 handler/文档。
10. **交付形态（硬约束）**：主树直接工作；`git commit --only <精确路径>`、提交号回报；工单处理记录追加一行。业务口径分歧停手 SendMessage 回报。

## 回复要求

回报：提交号、numstat、触发器语义要点（键名/缺省/写入形态）、接线点行号、幂等键构造、变异证据（击杀器+复位 md5）、§T081 追加前后锚、全量计数、arch rc0、树锚+文件数、边界声明。

---

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-26 | 编排者 | 工单落箱（用户裁定 A 案重做承接·判据直采 INV-081082 §1.2；**待 T-082b 交付后派**——TEST_RESULT 单写者纪律） | QUEUED，待编排者调度 |
