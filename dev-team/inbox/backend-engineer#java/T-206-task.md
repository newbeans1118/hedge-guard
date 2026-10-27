---
ticket: T-206
type: task
from: team-lead
to: backend-engineer#java
priority: P2
created: 2026-10-27T14:30+08:00
refs: [docs/REVIEW.md:705（R4-002 原文）, docs/ARCHITECTURE.md v1.5.24 §3.8 挂账登记, docs/DATA_MODEL.md:4157/:4167（t_fund_quota_alert CHECK 冻结）, T-205 交付 1bb5797（te-verify-t205 通过）, cr-t088 终审 4eb96e9, T-189 先例（DATA_MODEL v1.3.7→v1.3.8 两表增列+schema md5 硬锁随动）, T-201 先例（DATA_MODEL 升版三落点）]
---

## 事由（cr-t088 R4-002 承接·用户 2026-10-27 批准立项）

T-205 的 L3 护栏/紧急停止告警当前仅 **LOG + 响应载荷**，不落库——`t_fund_quota_alert.blocked_operation` 有 `CHECK 恒 'NONE'` 冻结（`DATA_MODEL.md:4157`，`FR-FUND-14` 验收 3「告警不阻断」），te-verify-t205 已裁定 LOG 留痕系该表唯一合规形态。失效场景（cr-t088 R4-002）：**事后审计/告警回溯依赖日志留存策略，无持久化审计行**。ARCH v1.5.24 §3.8 已登记挂账。本单以**新表**承载 L3 拒绝事件持久化并接线。

## 要求

1. **DATA_MODEL 升版 v1.3.9 → v1.3.10**（**门禁流程·非勘误级**——增表属实质变更，交付后须经独立复核方可贴标）：
   - 新表全列清单（含类型/约束/COMMENT），**表编号接续**（§0.6 表总览 111 → 112）；
   - 版本行（头部 `:13`）+ §7.1 变更链**纯追加**一行；既有行**零丢失**（自查口径 `A∖B=∅` 且 numstat 删除=0）；
   - **schema md5 硬锁测试随动**（先例：`M3AuditSchemaTest:203` 硬锁 DATA_MODEL md5·T-189 判例；先实测现行哪些测试锁 DATA_MODEL/schema 计数，逐个列清单并随动）。
2. **DDL 落点**：`docs/DEPLOY/schema/` 对应模块脚本（**模块归属由你按键语义判定并给依据**——L3 拒绝事件源于交易提交路径但语义属资金额度域，FUND vs TRADE vs AUDIT 自行论证择一）；**建表计数锁连锁评估**：全仓扫「111 张/表数 111/tableCount」等断言（含测试与文档），逐处列「须随动 / 判定不动·给依据」清单——111→112 波及面**零遗漏**。
3. **DAO + TradeService 留痕接线**：
   - 新 DAO（`java/src/main/java/com/hedgeguard/dao/`…）纯增量；
   - `TradeService` L3 拒绝路径（护栏 L3-FUND-CAP / 紧急停止 L3-EMERGENCY-STOP·现位于 submitOrder ②-b/②-c 之后）落库留痕，**与既有 LOG/响应载荷并行**；
   - **响应面零变更**：10012 + ruleId + 载荷四项键集不变（加守卫测试锁键集）；
   - `R-FUND-7` 告警不阻断语义零改动：落库**不得**阻断/改变下单主流程结果——落库失败的处置（吞并+LOG vs 同事务回滚）由你选型并**如实披露事务边界依据**（先例判例：事务内写后抛吞留痕三段式修法·T-182-184 轮）；
   - 留痕行至少含：ruleId、sub_account_id、product_id（如有）、current、threshold、trigger_source（L3-FUND-CAP/L3-EMERGENCY-STOP）、事件时间。
4. **边界（红线）**：`t_fund_quota_alert` 及其 CHECK **零触碰**；CFG 键**零新增**（无 CFG 计数连锁）；`ARCHITECTURE.md`/`REVIEW.md`/`TASKS.md`/`BOARD.md`/`TEST_RESULT.md` 不碰（编排者落盘）；`cpp/**`/`web/**`/`proto/**` 零改动。
5. **门禁**：全量 `mvn -B -o clean test` 绿（基线 **1289**/0/0/0 + 新增·skip=0·以 surefire 为准）+ `mvn -o validate` arch rc0；树锚落笔即测（规范形态 `cd java && find src -type f | LC_ALL=C sort | xargs md5sum | md5sum`——⚠️ 本沙箱 `cd java && mvn` 形态的 cd 前缀曾静默丢失 ⇒ mvn 用 `mvn -o -f /root/myspace/hedge-guard/java/pom.xml` 形态）；**变异 ≥2**：①接线摘除 ⇒ 落库断言红；②ruleId/字段变异 ⇒ 红；变异须 clean 重建（surefire:test 不重编译主源）+ 复位 md5 对照。
6. 单 commit（`git commit --only <精确路径>` 列全文件）；提交号回报 team-lead。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-27 | 编排者 | 用户批准立项，立号落箱（TASKS 合计 204→205）并派发 | TODO→已派 be-t206 |
