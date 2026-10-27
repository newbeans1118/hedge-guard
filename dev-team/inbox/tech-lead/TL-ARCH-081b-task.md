---
ticket: TL-ARCH-081b
type: task
from: project-manager
to: tech-lead
priority: P1
created: 2026-10-27T15:00+08:00
refs: [docs/ARCHITECTURE.md §11（CFG 键清单）/§4.3.1（错误码冻结）/§3.8, docs/DATA_MODEL.md:4157（t_fund_quota_alert CHECK 冻结）, T-205 交付 1bb5797（te-verify-t205 通过·报告 e66987e1…/90）, T-088 终审 4eb96e9（R4-001/R4-002/R4-003）, TL-ARCH-081 先例（QUEUED·可合并执行）, 勘误免 CH 口径先例（v1.5.21/22）]
---

## 事由（T-205 护栏/紧急停止 ARCH 落登·R4-003 承接）

T-205（实盘护栏+紧急停止）交付复核通过，三笔 ARCH/DATA_MODEL 落登挂账未清（T-088 终审 R4-003 点名工单未落箱·丢失线三例教训 ⇒ 立即落箱）：

1. **ARCH §11 登记 CFG-34/35**：`hg.trade.fund_cap_enabled`（BOOL·缺省 Y=启用）与 `hg.trade.emergency_stop`（BOOL·缺省 N）——形态对齐 TL-ARCH-081（CFG-26b）同批执行。
2. **ARCH §4.3.1/§3.8 补登护栏与紧急停止语义**：10012 复用（CONTRACTS.md:117-121 O-3 先例「按 10012 处理并保留 ruleId」）+ ruleId=L3-FUND-CAP/L3-EMERGENCY-STOP 双形态 + 护栏系「Java 提交前置校验非替代 C++ 下单职责」边界（T-088 终审 NFR-MAINT-4 判定在案）。
3. **失效模式登记（R4-001）**：G-6 缺省 fail-open（无有效额度/取数异常⇒惰性放行·FundService 同姿态）——护栏静默失效时 LOG 为唯一观测面，语义与观测面在 ARCH 注记。
4. **DATA_MODEL 增表挂账（R4-002）**：L3 硬停告警落库强化须 t_fund_quota_alert 之外增表（blocked_operation CHECK 恒 'NONE' 冻结⇒现表不可承载）——**DATA_MODEL 增表须走门禁流程（M1 会签口径）非勘误级**，本单仅登记挂账与需求描述，不直接改 DATA_MODEL；是否立项由编排者呈用户。
5. **O-1 随批注记**：latestEffectiveQuota best 选择含 `|| effectiveFrom==null` 增强偏离（更保守非漏洞）——随 §3.8 注记。

## 要求

1. 与 TL-ARCH-081（CFG-26b）**合并执行**（同文件同形态）；勘误免 CH 口径（v1.5.21/22 先例·须记动因+授权链=T-086 条件①/T-205 交付/T-088 终审 R4 链）。
2. 门禁：`mvn -o validate` arch rc0；Java 零改动则不跑全量（如实测波及列清单）。
3. 边界：仅 `docs/ARCHITECTURE.md`；TASKS/BOARD/TEST_RESULT/DATA_MODEL 编排者或门禁流程处置。
4. 主树直接工作；`git commit --only docs/ARCHITECTURE.md`；提交号回报 team-lead。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-10-27 | 编排者 | T-088 R4-003 承接落箱（与 TL-ARCH-081 合并执行·待派或批处理） | QUEUED |
| 2026-10-27 | tl-arch-081 | 与 TL-ARCH-081 合并执行完毕：CFG-34/35 落登 ARCH §11.2（落笔时点 :2860-2861；键名/缺省 Y·N 与 data-init.sql:522-529 种子逐字一致；归 §11.2 依据=「hg.」前缀键空间纪律·既有 hg.* 键全在 §11.2·非 Q 口径来源）+ §4.3.1 10012 复用指针（O-3 先例·ruleId=L3-FUND-CAP/L3-EMERGENCY-STOP·不新增业务码）+ §3.8 护栏语义注记块（NFR-MAINT-4 边界陈述引 REVIEW §M4.5 + R4-001 fail-open 失效模式 + O-1 latestEffectiveQuota 随批 + R4-002 增表挂账登记·不改 DATA_MODEL）+ §13 v1.5.24 行 + 编写时间行原位扩展；勘误级免 CH | 提交号见 git log（单提交）；门禁 rc0；抬头第 3 行未动（v1.5.21/22/23 先例均未动） |
