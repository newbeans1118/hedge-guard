# 任务分解表

> 负责人：项目经理 ｜ 阶段：S3 ｜ 版本：v0.1

- **项目**：企业期货套期保值管理系统（HedgeGuard）
- **编写人**：项目经理
- **编写时间**：2026-09-20

> **分解依据（唯一事实源）**：
> - 里程碑与门禁：`PROJECT.md` §9（M1 门禁 = 数据模型三方会签；M2 门禁 = TCP 协议冻结）
> - 数据边界：`docs/DATA_MODEL.md` §0.6 表编号总览（111 张）+ §4.7 FR→表映射 + §4.6.3 三层计数
> - 组件边界与协议：`docs/ARCHITECTURE.md` §3 模块划分、§4.1 REST/WS、§4.2 HGCP v1.0.1（FROZEN）、§4.4 内存模型、§11 配置项
> - 需求追溯：`docs/REQUIREMENTS/`（184 FR / 129 R / 50 NFR；`INDEX.md` §9.1 17 条已确认口径）
>
> **分解粒度**：按**数据边界（模块 → 表簇）**切分，**不按页面切**；每任务 1 人 0.5～2 人天可完成，验收标准可客观判定（`project-manager.md` §5.1）。
> **负责人取值**：`backend-engineer#cpp`（交易组件）/ `backend-engineer#java`（业务组件·实时与交易链）/ `backend-engineer#java2`（业务组件·台账与合规链，**R-12 预案 A 已生效，见 §5.1**）/ `frontend-engineer` / `tech-lead` / `test-manager` / `test-engineer` / `code-reviewer`。

---

## 0. 开工前置条件（门禁状态，详见 `BOARD.md` §1）

| 门禁 | 对应里程碑 | 状态 | 锚点 | 对任务分解的约束 |
|------|-----------|------|------|-----------------|
| **M1 门禁：数据模型三方会签** | M1 编码前 | ✅ **已达成**（2026-09-20，产品经理 + 技术负责人 + 测试经理三方全部通过，见 `docs/gates/M1-data-model-signoff/SIGN-OFF.md`） | 会签锚点 md5 `112b33243173196a33d4c0d575622d68`（5152 行，v1.3）→ 订正 **v1.3.1** `03b1fc729352f5c94886020a5866c574`（5169 行）→ **v1.3.2 定版 `99b647ec3e7a29b95ee86b04a58c747c`（5177 行 / 442225 字节 / mtime 18:43:53，写者已冻结）**〔**历史层**〕→ **v1.3.4 `da7799826de29ebedc0de33f416fb4af`（5246 行）〔历史层〕→ 现行定版 v1.3.5 `d17bce0c37e513a0a236304b9cb3f25d`（5252 行）；以实时 `md5sum` 为准**〔v1.3.2→v1.3.4 两笔增量 = `v1.3.3`（`DC-041` 同编号改写：`t_exposure` 未映射口径改哨兵 `0` + 恒 `NOT NULL` + 新增 `spot_category_code` 品类双态 + `uk_exposure_key` 六列全非空；新增 `t_product` 保留系统行 `id=0`）+ `v1.3.4`（`TB-PLAN-1.term_end_date` 期限口径 `OR`→`min`），经**两席书面追认**承接，见 `T-002` 备注与 `docs/gates/M1-data-model-signoff/REAFFIRM-*.md`〕；**（2026-09-21 再前移）v1.3.5 = 非结构计数传导**（`CH-011` 新增 `FR-SPOT-19` ⇒ 抬头 `FR` `183→184`；`DATA_MODEL.md` 仅计数同步、**无结构变更**）⇒ **不重开门禁**、两席 `REAFFIRM` 追认**继续有效**（其审查对象 `v1.3.3`/`v1.3.4` 未变，`v1.3.5` 不在其范围内、无须追认），依据 `docs/gates/M1-data-model-signoff/SIGN-OFF.md` **§9.7** | M1 门禁已达成、**T-002 两席定点再验证已闭环（两席均无条件确认）**；**M1 编码已可开工**；`R-12` 已由编排者裁定 **采纳预案 A（加派 `backend-engineer#java2`）**，**施工工单已派发、S4 进入 M1 施工**（见 §5.1） |
| **M2 门禁：TCP 协议冻结** | M2 编码前 | ✅ **已冻结**（技术负责人单方签署，`ARCHITECTURE.md` §4.2：**HGCP v1.0.1，FROZEN**；v1.0 → v1.0.1 为向后兼容澄清性修订，见 `CH-007`；**消息集 40 / 错误码 62 不变**） | `proto/hgcp.proto`（唯一定义，两侧同源生成）；冻结日期 2026-09-20；`ARCHITECTURE.md` 定版 md5 `641b481bee2678e8e6e931c190d4f86f`；**注**：`ARCHITECTURE.md` 其后经 `CH-006`（技术栈收紧）升至 **v1.4 `a7003553…` / 2478 行**，再经 `CH-007`（协议澄清性修订）升 **v1.5**（md5 `9feae927…` / 2516 行；§4.2 冻结登记块补 ③ 后定稿；**锚点以实时 `md5sum` 为准**）；`CH-006`/`CH-007` **不改变架构分层与接口契约**（HGCP 仍 FROZEN），**不影响 M1 门禁结论**（门禁实质对象 `DATA_MODEL.md`：**门禁定版 v1.3.2 `99b647ec…`（5177 行）〔历史层〕→ 现行定版 v1.3.5 `d17bce0c…`（5252 行），以实时 `md5sum` 为准**〔v1.3.3 `c399c5f5…`（5245 行）/ v1.3.4 `da779982…`（5246 行）同列**历史层**、不删；**v1.3.5 = 非结构计数传导（`CH-011` ⇒ `FR` `183→184`）、不重开门禁**，依据 `docs/gates/M1-data-model-signoff/SIGN-OFF.md` **§9.7**〕；⚠️ **锚点漂移已裁定**：v1.3.2 定版后经 `DC-038`~`DC-041` 演进至 v1.3.3 → **v1.3.4**，**该漂移对 M1 门禁结论的影响已由编排者「(B) 务实案」裁定 + 两席书面追认承接**〔`REAFFIRM-product.md`（`5ac0bc90…` / 207 行）与 `REAFFIRM-test.md`（`19fed18e…` / 267 行），均「认可·无条件」，2026-09-21〕，**无需另行门禁复核**） | C++ / Java 分头开工的前置条件已具备；冻结后任何协议变更须走 `CH-xxx`（L3，技术负责人批准）并重跑协议专项测试（T-057） |
| **M2 首周探路门禁（技术负责人加设）** | M2 业务开发铺开前 | ✅ **已达成**（2026-09-21 `T-030` 汇签；三项探路 `T-026`/`T-027`/`T-028` 均通过；许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`） | `ARCHITECTURE.md` §9：`RSK-01` / `RSK-02` / `RSK-06` **三项**（`RSK-07` 已由 `CH-006` 消除），全部落在 **T-026 / T-027 / T-028** | **未完成不得铺开 M2 业务开发**；`RSK-01`（protobuf C++11）/ `RSK-02`（CTP 头文件 C++11）**已通过（探路实证）** → `T-026` / `T-027` **DONE**；第 3 项 `RSK-06`（Java 端 WebSocket，`T-028`）**待 `#java`**，故 `T-030` 汇签**仍为 `TODO`**（`T-029` 已 `REJECTED`，编号留痕、**不计入人时**） **（2026-09-21 台账对齐）**受本门禁阻塞的任务已在 `§1`/`BOARD §2` 标 `BLOCKED`；解除点 = `T-030` 汇签通过。 **（2026-09-21 解锁回写）** `T-030` 汇签已完成（`docs/gates/M2-recon-signoff/SIGN-OFF.md`，`081f37a2…` / 254 行），**许可令已下**（`T-031`~`T-060` 解除阻塞）；受阻塞任务按「阻塞原因」分流 —— 仅门禁者转 `TODO`、含依赖未满足者维持 `BLOCKED`（详见 `BOARD.md` §5 第 30 轮）。 |

> 📌 **M1 数据模型定版状态（2026-09-20，编排者裁决口径）**：
> M1 门禁会签后，数据模型经历 **v1.3.1 一致性订正**（`SIGN-OFF.md` §5 打包的 5 项建议级）与 **v1.3.2 收尾轮**（两席再验证披露的 `O-1`=`PI-24`、`PI-22`、`PI-23` 三项建议级引用/口径问题）。
> - **最终定版已锁定**：`docs/DATA_MODEL.md` md5 **`99b647ec3e7a29b95ee86b04a58c747c`**（5177 行 / 442225 字节 / mtime 18:43:53），**写者已冻结，停写取样证据 4 次零变化**（T-089，由编排者执行）。
> - **`SIGN-OFF.md` 已补 §7.3（两席再验证结果 + 最终锚点）与 §8（锚点变更史 + 流程纪律）**，回写由编排者完成（`docs/gates/` 为门禁证据目录，项目经理不改）。
> - **T-002 已闭环**：产品席 + 测试席**各自独立取证、均「无条件确认」**（不变式零漂移、实质改动 0、行号引用归零）→ **M1 编码已放行**。
> - **`R-12` 已裁决、派发已下达**：编排者 2026-09-20 裁定**采纳预案 A（加派 `backend-engineer#java2`）**；M1 首发工单已投递（`T-003`/`T-004` → `#java`，`T-026`/`T-027` → `#cpp`，`#java2` 首批为开工说明性工单），S4 进入 M1 施工。方案与二分见 §5.1。
> - 该过程属**门禁制品的固有订正流程**，归 `BOARD.md` `R-11`（**已闭环，纪律已固化**），**不是制品内容缺陷**，不计入缺陷清单。
> - **（2026-09-21 追加）现行定版已前移**：编排者「(B) 务实案」裁定 **M1 门禁现行锚点 = v1.3.4 `da7799826de29ebedc0de33f416fb4af` / 5246 行**（**以实时 `md5sum` 为准**）；上列 **v1.3.2 `99b647ec…`（5177 行）改列「历史层」（不删）**；v1.3.3 / v1.3.4 两笔增量由 `docs/gates/M1-data-model-signoff/REAFFIRM-product.md`（`5ac0bc90…` / 207 行）与 `REAFFIRM-test.md`（`19fed18e…` / 267 行）**两席书面追认承接**（**均「认可·无条件」、均判两笔不影响原会签结论**）；`T-002` 结论**由追认延续**（见 §2 `T-002`）。 **（2026-09-21 再前移 · 只加不改）现行定版 = v1.3.5 `d17bce0c37e513a0a236304b9cb3f25d` / 5252 行**（**以实时 `md5sum` 为准**）—— v1.3.5 系**非结构计数传导**（`CH-011` 新增 `FR-SPOT-19` ⇒ 抬头 `FR` `183→184`；`DATA_MODEL.md` 仅计数同步、**无结构变更**），**不重开门禁**、两席 `REAFFIRM` 追认**继续有效**（其审查对象 `v1.3.3`/`v1.3.4` 未变；`v1.3.5` 不在其范围内、无须追认），依据 `docs/gates/M1-data-model-signoff/SIGN-OFF.md` **§9.7**；**v1.3.4 `da779982…`（5246 行）随之改列「历史层」（不删）**。

---

## 1. 任务总览

> **状态说明**：`TODO` / `IN_PROGRESS` / **`已完成，待独立复核`**（实现与自测到位、**尚无独立验证证据**；**不得视为 `DONE`**）/ `DONE`（判据 = **授权 + 独立验证**）/ `BLOCKED` / `REJECTED（作废，不得静默删除）`。
> **来源**：初版任务填「初版」；由变更或门禁决议引出的填 `CH-xxx` / `SIGN-OFF` 依据。

| 编号 | 标题 | 需求追溯 | 负责人 | 优先级 | 依赖 | 估时 | 来源 | 状态 |
|------|------|---------|--------|--------|------|------|------|------|
| T-001 | 落地数据模型 v1.3.1 一致性订正 | SIGN-OFF §5（N-1~N-4, PI-21） | tech-lead | P0 | — | 4 人时 | SIGN-OFF | DONE |
| T-002 | 定点再验证 v1.3.1 订正结果（只验变更行与受影响计数） | SIGN-OFF §5 | test-manager | P0 | T-001 | 4 人时 | SIGN-OFF | DONE（结论由两席追认承接，2026-09-21） |
| T-003 | 搭建 Java 业务组件工程骨架与 HTTP 服务基座 | FR-CONFIG-1, NFR-MAINT-1/3/5, NFR-SEC-6/7, NFR-DEPLOY-1 | backend-engineer#java | P0 | T-002 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-004 | 实现 DAO 基座并交付 CONFIG+AUTH 20 张表 DDL | FR-CONFIG-1/3/4, FR-AUTH-1/14, NFR-MAINT-3, NFR-COMPAT-5, NFR-SEC-8 | backend-engineer#java | P0 | T-003 | 24 人时 | 初版 | 已完成，待独立复核 |
| T-005 | 交付 PRODUCT 模块 8 张表 DDL 与 DAO | FR-PRODUCT-1~14 | backend-engineer#java2 | P0 | T-004 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-006 | 交付 SPOT 模块 10 张表 DDL 与 DAO | FR-SPOT-1~18 | backend-engineer#java2 | P0 | T-004 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-007 | 交付 PLAN 模块 8 张表 DDL 与 DAO | FR-PLAN-1~20 | backend-engineer#java2 | P0 | T-004 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-008 | 实现子账户管理与全生命周期服务 | FR-CONFIG-1, CFG-02c/10 | backend-engineer#java2 | P0 | T-004 | 12 人时 | 初版 | 已完成，待独立复核 |
| T-009 | 实现系统配置服务与零落盘约束 | FR-CONFIG-1~15, NFR-SEC-1/2 | backend-engineer#java2 | P0 | T-004 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-010 | 实现用户与权限服务（岗位分离 + 子账户软隔离） | FR-AUTH-1~18 | backend-engineer#java2 | P0 | T-004 | 20 人时 | 初版 | IN_PROGRESS（2026-09-21 独立复核判不通过，退回重做） |
| T-011 | 实现参数下发编排 ParamDispatchService | R-CONFIG-3, R-AUTH-9, R-PRODUCT-9, R-PLAN-5, R-RISK-9 | backend-engineer#java2 | P0 | T-004, T-061 | 12 人时 | 初版 | 已完成，待独立复核 |
| T-012 | 实现品种管理与白名单/额度服务 | FR-PRODUCT-1~14 | backend-engineer#java2 | P0 | T-005 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-013 | 实现现货管理服务（合同/生产/库存/现货价） | FR-SPOT-1~12, CFG-18 | backend-engineer#java2 | P0 | T-006 | 20 人时 | 初版 | 已完成，待独立复核 |
| T-014 | 实现实货敞口计算服务（三分量 + 快照时点 + 缺口处置） | FR-SPOT-13~18, CFG-12/12b/17/17b | backend-engineer#java2 | P0 | T-013 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-015 | 实现套保比率三档计算 | FR-PLAN-6/7/8, R-SPOT-8, CFG-06/06b | backend-engineer#java2 | P1 | T-007 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-016 | 实现套保计划编制/书面文件门禁/审批链/状态机 | FR-PLAN-1~5/9~20, R-PLAN-5 | backend-engineer#java2 | P0 | T-007, T-015 | 24 人时 | 初版 | DONE（附条件；判据 = 授权 + 独立验证；独立复核 = 有条件通过〔13 项判据 12 符合；`CR-002` 核心反例闭合；反退化 8/8 全红；隔离副本 `346/0/0/0` 独立复现〕；条件 = (c)「审批页面展示」由 `T-020` 承载，义务不减） |
| T-017 | 搭建前端基座（登录/核心工具/零构建链） | NFR-USE-1~5, NFR-MAINT-1/5, NFR-COMPAT-3 | frontend-engineer | P0 | T-003 | 16 人时 | 初版 | TODO |
| T-018 | 实现品种管理前端页面 | FR-PRODUCT-1~14 | frontend-engineer | P1 | T-017, T-012 | 12 人时 | 初版 | TODO |
| T-019 | 实现现货与实货敞口前端页面 | FR-SPOT-1~18 | frontend-engineer | P1 | T-017, T-013, T-014 | 16 人时 | 初版 | TODO |
| T-020 | 实现套保计划前端页面 | FR-PLAN-1~20 | frontend-engineer | P1 | T-017, T-016 | 16 人时 | 初版 | TODO |
| T-021 | 实现权限与系统配置前端页面 | FR-AUTH-1~18, FR-CONFIG-1~15 | frontend-engineer | P1 | T-017, T-008, T-009, T-010 | 16 人时 | 初版 | TODO |
| T-022 | 制定 M1 测试策略与用例设计（约束→用例映射） | NFR-TEST-1/2/3, NFR-MAINT-2 | test-manager | P0 | T-002 | 16 人时 | 初版 | TODO |
| T-023 | 编写 M1 单测与集成测试（JUnit 5） | NFR-TEST-1/3, NFR-MAINT-2 | test-engineer | P0 | T-010, T-012~T-016 | 32 人时 | 初版 | TODO |
| T-024 | 执行 M1 端到端验收（双子账户→品种/敞口→计划→隔离→落库） | PROJECT §9 M1 验收, §10-34 | test-engineer | P0 | T-023 | 16 人时 | 初版 | TODO |
| T-025 | 执行 M1 代码与架构合规审查 | NFR-MAINT-4/5/6, NFR-SEC-9, PROJECT §10-7 | code-reviewer | P0 | T-012~T-021 | 16 人时 | 初版 | TODO |
| T-026 | RSK-01 protobuf 在 C++11 下空壳构通验证 | ARCH §9 RSK-01, §7.1.5 | backend-engineer#cpp | P0 | — | 8 人时 | 初版 | DONE（**附条件**；判据 = **授权 + 独立验证**；`T-030` 汇签结论 = **通过**〔**技术风险已排除，不得上推为「协议已验证」** —— `proto/hgcp.proto` 实测不存在，协议级闭环待 `T-032`〕；见 §2 备注） |
| T-027 | RSK-02 CTP 头文件 C++11 兼容验证 | ARCH §9 RSK-02, NFR-COMPAT-1/2 | backend-engineer#cpp | P0 | — | 4 人时 | 初版 | DONE（独立复核**通过** + `T-030` 汇签结论 = **通过**；见 §2 备注） |
| T-028 | RSK-06 Java 端 WebSocket 选型与实现 | ARCH §9 RSK-06, §4.1.2, NFR-PERF-5, `CH-006` | backend-engineer#java | P0 | — | 16 人时 | 初版（`CH-006` 口径收紧） | DONE（独立复核通过；见 §2 备注） |
| T-029 | RSK-07 websocketpp 落点确认（**已取消**） | ARCH §9 RSK-07（**因 `CH-006` 移除 websocketpp 而失效**） | backend-engineer#cpp | P0 | — | 0 人时（原 8，已扣除） | `CH-006` | REJECTED |
| T-030 | 汇签 M2 探路结论并下达铺开许可 | ARCH §9（三项探路：T-026/T-027/T-028）, §3.13 | tech-lead | P0 | T-026, T-027, T-028 | 4 人时 | 初版 | DONE（见 §2 备注） |
| T-031 | 搭建 C++ 交易组件工程骨架与构建 + arch-check 聚合入口与 CI 接线 | NFR-COMPAT-1/2/7, NFR-MAINT-4/6 | backend-engineer#cpp | P0 | T-030 | 24 人时 | 初版 | TODO |
| T-032 | 实现 HGCP 帧编解码与元协议（鉴权/心跳/补数） | FR-TRADE-14, FR-CONFIG-6/9, NFR-SEC-3, NFR-REL-2 | backend-engineer#cpp | P0 | T-031 | 32 人时 | 初版 | TODO |
| T-033 | 实现 CTP 适配层 ctp_adapter（交易+行情+资金划转） | FR-TRADE-2/4/5/6/12, FR-FUND-5/6/8 | backend-engineer#cpp | P0 | T-031 | 24 人时 | 初版 | TODO |
| T-034 | 实现风控内存状态 risk_state（不可变快照 + 原子替换） | FR-RISK-9/10, ARCH §4.4, NFR-PERF-1 | backend-engineer#cpp | P0 | T-031 | 24 人时 | 初版 | TODO |
| T-035 | 实现事前风控引擎 risk_engine（8 规则 + 三层限额 + 双层校验） | FR-RISK-1~8, FR-TRADE-3, NFR-PERF-1 | backend-engineer#cpp | P0 | T-034 | 32 人时 | 初版 | TODO |
| T-036 | 实现指令生命周期管理 order_engine | FR-TRADE-1/2/6/13, R-TRADE-1/2/5/6/7/12 | backend-engineer#cpp | P0 | T-033, T-035 | 32 人时 | 初版 | TODO |
| T-037 | 实现指令流水与重放 flow_store | FR-TRADE-15/16, NFR-REL-1 | backend-engineer#cpp | P0 | T-031 | 24 人时 | 初版 | TODO |
| T-038 | 实现控制指令消费与参数生效（param_version 单调） | FR-RISK-10, FR-CONFIG-6, ARCH §4.2.3B | backend-engineer#cpp | P0 | T-032, T-034 | 16 人时 | 初版 | TODO |
| T-039 | 实现事件流产出与保序补数（含缓冲保留） | ARCH §4.2.6, NFR-REL-2/5, NFR-PERF-6 | backend-engineer#cpp | P0 | T-032, T-036 | 24 人时 | 初版 | TODO |
| T-040 | 实现 Java 端行情 WebSocket 服务端（**生产级**） | FR-TRADE-12, R-TRADE-11, NFR-PERF-5, `EVT_MARKET_QUOTE`, ARCH §4.1.2/§6.13/§7.1.11 | backend-engineer#java | P1 | T-028, T-042 | 16 人时 | 初版（`CH-006` 定界改派 `#java`） | BLOCKED |
| T-041 | 实现崩溃恢复集成（在途指令不释放冻结额度） | PROJECT §10-27, FR-TRADE-15/16, ARCH §9 RSK-10 | backend-engineer#cpp | P0 | T-036, T-037 | 24 人时 | 初版 | TODO |
| T-042 | 实现组件桥接层 TradingBridge（HGCP 客户端） | FR-TRADE-14, FR-RISK-9~12, NFR-REL-2/5 | backend-engineer#java | P0 | T-030 | 32 人时 | 初版 | TODO |
| T-043 | 交付 TRADE 模块 9 张表 DDL 与 DAO | FR-TRADE-1~18 | backend-engineer#java | P0 | T-004 | 16 人时 | 初版 | TODO |
| T-044 | 交付 SETTLE 模块 16 张表 DDL 与 DAO | FR-SETTLE-1~17 | backend-engineer#java2 | P0 | T-004 | 24 人时 | 初版 | TODO |
| T-045 | 交付 FUND 模块 9 张表 DDL 与 DAO | FR-FUND-1~17 | backend-engineer#java2 | P0 | T-004 | 16 人时 | 初版 | TODO |
| T-046 | 实现交易执行服务 TradeService（不落库下单 + 事件流落库） | FR-TRADE-1~18, CFG-13 | backend-engineer#java | P0 | T-042, T-043 | 24 人时 | 初版 | BLOCKED |
| T-047 | 实现结算服务 SettleService（独立结算 + 加总对账 + 差异归属） | FR-SETTLE-1~12/15/16, CFG-11, Q-11 | backend-engineer#java2 | P0 | T-042, T-044, T-046 | 32 人时 | 初版 | BLOCKED |
| T-048 | 实现核对任务与电子签署 | FR-SETTLE-13/14/17, CFG-14, R-SETTLE-11 | backend-engineer#java2 | P0 | T-047 | 16 人时 | 初版 | BLOCKED |
| T-049 | 实现资金管理服务 FundService（出入金 + 划拨 + 额度） | FR-FUND-1~17, CFG-25 | backend-engineer#java2 | P0 | T-042, T-045 | 24 人时 | 初版 | BLOCKED |
| T-050 | 实现事中风控与镜像状态 risk_intraday | FR-RISK-12~16/19, R-RISK-13 | backend-engineer#java | P0 | T-042 | 20 人时 | 初版 | BLOCKED |
| T-051 | 实现调度 job（日终/报表/对账/到期提醒，可注入时钟） | NFR-TEST-4, ARCH §3.12 | backend-engineer#java2 | P1 | T-047, T-049 | 16 人时 | 初版 | BLOCKED |
| T-052 | 交付部署与运维支撑（部署/环境/配置清单/启动脚本） | NFR-DEPLOY-2/3/4/5, ARCH §3.14, CFG-01~31 | backend-engineer#java2 | P0 | T-009, T-049 | 16 人时 | 初版 | BLOCKED |
| T-053 | 实现交易执行前端（含行情 WebSocket） | FR-TRADE-1~18, NFR-PERF-5 | frontend-engineer | P0 | T-017, T-046 | 20 人时 | 初版 | BLOCKED |
| T-054 | 实现结算前端页面 | FR-SETTLE-1~17 | frontend-engineer | P1 | T-047 | 16 人时 | 初版 | BLOCKED |
| T-055 | 实现资金管理前端页面 | FR-FUND-1~17 | frontend-engineer | P1 | T-049 | 16 人时 | 初版 | BLOCKED |
| T-056 | 制定 M2 联调测试策略与 HGCP 协议专项测试设计 | NFR-TEST-5, ARCH §4.2.7 | test-manager | P0 | T-030 | 16 人时 | 初版 | TODO |
| T-057 | 执行 M2 协议专项测试（一致性/保序/补数/幂等/重连） | NFR-TEST-5, NFR-REL-2/5, PROJECT §10-31/32 | test-engineer | P0 | T-039, T-042, T-056 | 32 人时 | 初版 | BLOCKED |
| T-058 | 执行 M2 端到端（模拟盘期货+期权/零 DB/加总/划拨） | PROJECT §9 M2 验收, §10-1/21/23/30/35/36/37 | test-engineer | P0 | T-046~T-049, T-052, T-057 | 32 人时 | 初版 | BLOCKED |
| T-059 | 执行 C++ ctest 单测（风控/指令/流水/协议） | NFR-TEST-1, PROJECT §10-8 | test-engineer | P0 | T-035~T-041 | 24 人时 | 初版 | BLOCKED |
| T-060 | 执行 M2 代码与架构合规审查（E1~E6 全量） | NFR-SEC-9, NFR-MAINT-4/6, PROJECT §10-6/29 | code-reviewer | P0 | T-041, T-049, T-055 | 16 人时 | 初版 | BLOCKED |
| T-061 | 交付 RISK 首增量 `t_param_dispatch` 单表 DDL 与 DAO（原 11 张表范围已收窄） | FR-RISK-1~19 | backend-engineer#java | P0 | T-004 | 2 人时 | 初版（2026-09-20 范围收窄） | 已完成，待独立复核 |
| T-062 | 交付 AUDIT 模块 8 张表 DDL 与 DAO | FR-AUDIT-1~13 | backend-engineer#java2 | P0 | T-004 | 16 人时 | 初版 | TODO |
| T-063 | 实现风控阈值配置与下发闭环（合规下限校验） | FR-RISK-1~10, CFG-01~05/15/16/21, R-CONFIG-8 | backend-engineer#java | P0 | T-038, T-061, T-094 | 20 人时 | 初版 | TODO |
| T-064 | 实现事后风控与风险报表落库 | FR-RISK-17/18, PROJECT §10-26 | backend-engineer#java | P0 | T-047, T-061, T-094 | 24 人时 | 初版 | TODO |
| T-065 | 实现风险告警/处置/异常下单统计/指标快照 | FR-RISK-11~16/19 | backend-engineer#java | P0 | T-050, T-061, T-094 | 20 人时 | 初版 | TODO |
| T-066 | 实现规模红线/时点净持仓/期限红线复算与统计 | FR-RISK-4/5/6, CFG-01~04/17, PROJECT §10-12/13/14 | backend-engineer#java | P0 | T-063 | 24 人时 | 初版 | TODO |
| T-067 | 实现全链路追溯（正向/反向/完整性 + 在途时窗） | FR-AUDIT-1~3/11, R-AUDIT-2/11, CFG-17c | backend-engineer#java2 | P0 | T-062 | 24 人时 | 初版 | TODO |
| T-068 | 实现违规事项筛查（含关联缺失/理由缺失并列） | FR-AUDIT-4~6, Q-13, R-AUDIT-7 | backend-engineer#java2 | P1 | T-062 | 16 人时 | 初版 | TODO |
| T-069 | 实现审计报表与证据链导出 + 审计日志不可篡改 | FR-AUDIT-6~10/12/13, R-AUDIT-4, PROJECT §10-17 | backend-engineer#java2 | P0 | T-062 | 24 人时 | 初版 | TODO |
| T-070 | 实现风险控制前端页面 | FR-RISK-11~19 | frontend-engineer | P1 | T-064, T-065 | 16 人时 | 初版 | TODO |
| T-071 | 实现审计前端页面 | FR-AUDIT-1~13 | frontend-engineer | P1 | T-067, T-068, T-069 | 16 人时 | 初版 | TODO |
| T-072 | 制定 M3 测试策略（6 类风险逐类触发用例） | PROJECT §10-5, NFR-TEST-2 | test-manager | P0 | T-056 | 12 人时 | 初版 | TODO |
| T-073 | 执行 M3 超限阻断与崩溃恢复不超额下单验证 | PROJECT §9 M3 验收, §10-24/25/27, ARCH §9 | test-engineer | P0 | T-041, T-063, T-066 | 24 人时 | 初版 | TODO |
| T-074 | 执行 M3 追溯与报表导出端到端 | PROJECT §9 M3 验收, §10-16/17 | test-engineer | P0 | T-069, T-071 | 16 人时 | 初版 | TODO |
| T-075 | 执行 M3 代码与架构合规审查 | NFR-SEC-9, NFR-MAINT-4/6 | code-reviewer | P0 | T-069, T-070, T-071 | 16 人时 | 初版 | TODO |
| T-076 | 交付 ACCT 模块 12 张表 DDL 与 DAO | FR-ACCT-1~14 | backend-engineer#java2 | P0 | T-004 | 24 人时 | 初版 | TODO |
| T-077 | 实现套期关系与有效性评估（定性三项 + 量化监测） | FR-ACCT-1~6, CFG-07/07b, Q-7 | backend-engineer#java2 | P0 | T-076 | 24 人时 | 初版 | TODO |
| T-078 | 实现再平衡记录（四类触发） | FR-ACCT-7~9, CFG-08, Q-8 | backend-engineer#java2 | P1 | T-077 | 16 人时 | 初版 | TODO |
| T-079 | 实现会计凭证草稿/审核/冲销 + 科目模板 + 期间关账 | FR-ACCT-10~14, CFG-09, Q-9 | backend-engineer#java2 | P0 | T-076 | 24 人时 | 初版 | TODO |
| T-080 | 实现套保效果评估与双侧盈亏同屏上报 | FR-ACCT-1~14, PROJECT §10-18 | backend-engineer#java2 | P0 | T-077, T-079 | 20 人时 | 初版 | TODO |
| T-081 | 实现证据链导出（断点显式标记） | FR-AUDIT-7, PROJECT §10-1/16 | backend-engineer#java2 | P0 | T-067, T-080 | 16 人时 | 初版 | TODO |
| T-082 | 执行安全加固（零落盘全库审计/日志脱敏/鉴权） | NFR-SEC-1~8, PROJECT §10-4 | backend-engineer#java | P0 | T-049, T-063 | 20 人时 | 初版 | TODO |
| T-083 | 实现套保会计前端页面 | FR-ACCT-1~14 | frontend-engineer | P1 | T-077~T-080 | 16 人时 | 初版 | TODO |
| T-084 | 制定 M4 测试策略与实盘验证方案 | NFR-TEST-4, PROJECT §9 M4, §6 工期隐性成本 | test-manager | P0 | T-072 | 12 人时 | 初版 | TODO |
| T-085 | 执行 M4 会计文档包与 CAS 24 自检报告 | FR-ACCT-1~14, PROJECT §10-3 | test-engineer | P0 | T-080, T-083 | 24 人时 | 初版 | TODO |
| T-086 | 执行实盘小资金运行验证与安全审计 | PROJECT §9 M4 验收, NFR-SEC-1~9 | test-engineer | P0 | T-082, T-085 | 24 人时 | 初版 | TODO |
| T-087 | 执行换库可行性、组件边界与全流程断网终验 | PROJECT §10-11/28/29/33 | test-engineer | P0 | T-082, T-086 | 16 人时 | 初版 | TODO |
| T-088 | 执行 M4 代码与架构终审 | NFR-SEC-9, NFR-MAINT-4/6 | code-reviewer | P0 | T-081, T-082, T-087 | 16 人时 | 初版 | TODO |
| T-089 | 冻结数据模型写者、锁定定版 md5 并回写 SIGN-OFF 基线表 | SIGN-OFF §2/§6, `PROJECT.md` §4.1.E（三）契约冻结纪律 | 编排者 | P0 | T-001 | 4 人时 | 新增（基线冻结，编排者执行） | 已完成，待独立复核 |
| T-090 | 落地架构判据 E1 脚本（C++ 不得链接 DB 客户端库） | ARCH §3.0.2 E1, §3.13, NFR-SEC-9, PROJECT §10-29 | tech-lead | P0 | 无 | 4 人时 | 新增（判据落点补齐） | DONE |
| T-091 | 落地架构判据 E3 脚本（C++11 合规扫描） | ARCH §3.0.2 E3, §3.13, NFR-COMPAT-1 | tech-lead | P0 | 无 | 4 人时 | 新增（判据落点补齐） | DONE |
| T-092 | 落地架构判据 E4 脚本（交易路径无锁/无动态分配/无同步 I/O 扫描） | ARCH §3.0.2 E4, §3.13, NFR-PERF-1 | tech-lead | P0 | 无 | 4 人时 | 新增（判据落点补齐） | DONE |
| T-093 | 落地架构判据 E6 脚本（前端零构建链与无直连扫描） | ARCH §3.0.2 E6, §3.13, NFR-MAINT-1/5, NFR-COMPAT-3 | tech-lead | P0 | 无 | 4 人时 | 新增（判据落点补齐） | DONE |
| T-094 | 交付 RISK 模块剩余 10 张表 DDL 与 DAO（M3 窗口） | FR-RISK-1~19 | backend-engineer#java | P0 | T-004 | 18 人时 | 新增（`T-061` 范围收窄，余表转 M3） | TODO |
| T-095 | **`E1`~`E6` 六条判据脚本形态 / 退出码语义一致性收口**（原 `E2` 按 `E5` 同法收紧） | ARCH §3.0.2 `E1`~`E6`, §3.13, NFR-SEC-9, NFR-COMPAT-1, NFR-PERF-1, PROJECT §10-29 | tech-lead | P0 | 无 | **8 人时** | 新增（判据与 E5 同构化；**第 10 轮扩范围** = 兼收 `TE-AC-002`/`TE-AC-003`/`TE-AC-004`） | DONE |
| T-096 | 为 `PageQuery` 增加「缺省排序方向」重载（向后兼容）+ **稳定排序**（多键 / 唯一 tie-break） | ARCH §4.1.1「分页默认排序方向」, FR-PLAN-4, **FR-PLAN-15 处理规则（分页排序稳定性）** | backend-engineer#java | P0 | 无 | **3 人时** | 新增（架构 §4.1.1 约定落点；**本轮追加「稳定排序」要求**，来源 = `T-103` 交付披露 (b)：分页后 `ORDER BY` 仅单列、无唯一 tie-break） | TODO |
| T-097 | `ApiRoutes` 升级为冻结 `(方法,路径)` 对 + 一致性判据改等值断言 | ARCH §4.1.1「路由声明与判据的目标形态」, FR-PLAN-4 | backend-engineer#java | P0 | 无 | 4 人时 | 新增（架构 §4.1.1 约定落点） | TODO |
| T-098 | 实现 `CROSS_READONLY` 跨账户只读强制点（写操作拒绝 + 留痕） | ARCH §8.2「NFR-SEC-7 服务端强制点」③, FR-AUTH-13, R-AUTH-8 | backend-engineer#java | P0 | 无 | 3 人时 | 新增（架构 §8.2 强制点落点） | DONE（独立复核通过；复核结论 = **通过（无条件）**〔`te-verify-t115`，2026-09-21；原列唯一条件已消解 —— 该测试文件差异属**已登记的树演进/瞬态**，见 `BOARD.md` §5 第 24 轮行与本任务备注〕；实现 = `assertSubAccountWritable` × 各服务写面改向；越权 ⇒ `10004` + `t_unauthorized_attempt` 留痕） |
| T-099 | 处置 `DmUserSubAccountPermDao.scopeOf` 不读 `perm_type`（补分支或弃用指向） | ARCH §8.2 机制订正（v1.5.8）, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java | P0 | 无 | 2 人时 | 新增（架构 §8.2 机制订正落点） | TODO |
| T-100 | 装配 `GET /plan` 读取面子账户隔离（列表 + 详情） | ARCH §8.2①③④（NFR-SEC-7 服务端强制点）, FR-PLAN-4 验收4, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java | P0 | T-016 | 4 人时 | 新增（架构 §8.2 主落点编号补登记） | DONE |
| T-101 | 装配 `GET /plan/{id}/trade-plans` 读取面子账户隔离 | ARCH §8.2③（读取面通则）, FR-PLAN-15 验收4, FR-PLAN-14 验收4, R-PLAN-7, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java | P0 | T-100 | 3 人时 | 新增（架构 §8.2③「其余读取面」通则落点；编排者 2026-09-21 裁定立项） | DONE |
| T-102 | 写面**子账户可见性 / 交易权限**强制补齐（`assertSubAccountVisible` + `assertCanTrade` 未接线 + `withdraw` 发起人校验缺失 + `decomposeTradePlans` 写路径读取未 scope） | FR-AUTH-9, FR-AUTH-10, FR-AUTH-13, NFR-SEC-7, R-AUTH-8, R-AUTH-13 | backend-engineer#java | P0 | T-100 | 5 人时 | 新增（`T-100` 专项复核 `EXP-T100-1` 同族发现；编排者 2026-09-21 裁定立项并扩范围；**第 3 轮再扩 ④** = `decomposeTradePlans` 写路径读取未 scope，来源 = `T-101` 交付「未完成/待裁定 ①」披露；`PlanService.java` 行号以实时 `md5sum` 为准） | **DONE**（判据 = **授权 + 独立验证**；复核链 = 首轮 `te-verify-t102`「**通过、0 阻断**」〔缺陷 `TE-T102-001`〕→ `②b` 补修 → **delta 复检「通过、0 缺陷」**；**外依赖**：`assertCanTrade` 的 M2 真强制点 = **`T-046`（`BLOCKED`）**，M1 期 `src/main` 0 调用为**有意**〔接 `decompose` 会误拒 `FR-PLAN-14` 的财务/风控岗〕；见 §2 备注） |
| T-103 | `GET /plan/{id}/trade-plans` 补**分页/筛选/排序**（按 `FR-PLAN-15` **输入列**） | FR-PLAN-15 输入列（`PLAN.md:321`）, ARCH §4.1.1（分页约定）, CONTRACTS §1.1（`PageData` 信封）, NFR-SEC-7 | backend-engineer#java | P0 | T-101 | 4 人时 | 新增（`T-101` 独立复核探索性发现 `EXP-T101-1`；编排者 2026-09-21 裁定立项，项目经理登记） | DONE |
| T-104 | `FR-PLAN-15` 处理规则两项：交易计划**剩余可下单量** + **已达上限标记**（含前端展示） | FR-PLAN-15 处理规则（`PLAN.md:322`；引用时点，**以实时 `md5sum` 为准**）, FR-PLAN-14 验收4 | backend-engineer#java | P0 | T-103 | 2 人时 | 新增（`FR-PLAN-15`「处理规则」两项被 `T-101`/`T-103` **两次**判为「非本任务范围」⇒ 单独立号以免持续掉队；编排者 2026-09-21 裁定立项，项目经理登记） | TODO |
| T-105 | PRODUCT **列表类**读取面口径收口（`GET /product` 补分页 + 默认创建时间倒序 + 子账户隔离；`GET /product/export` 范围与排序对齐） | FR-PRODUCT-5（`PRODUCT.md:143`~`:156`）, FR-PRODUCT-8（`:187`~`:199`）, FR-AUTH-12/13, NFR-SEC-7 | backend-engineer#java2 | P0 | T-100 | 5 人时 | 新增（`tl-triage` 读取面普查 + 编排者 2026-09-21 裁定立项，项目经理登记） | DONE（独立复核通过；见 §2 备注） |
| T-106 | PRODUCT **单对象**读取面隔离（`GET /product/{id}` + `GET /product/quota`） | FR-PRODUCT-6 验收3, FR-PRODUCT-11（`Q-16`）, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java2 | P0 | T-100 | 2 人时 | 新增（`tl-triage` 读取面普查 + 编排者 2026-09-21 裁定立项，项目经理登记） | TODO |
| T-107 | SPOT 读取面口径收口（`GET /spot/exposure` 补分页 + 默认敞口金额/数量倒序 + 隔离；`GET /spot/exposure/{id}` 隔离） | FR-SPOT-15（`SPOT.md:312`~`:325`）验收3, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java2 | P0 | T-100 | 4 人时 | 新增（`tl-triage` 读取面普查 + 编排者 2026-09-21 裁定立项，项目经理登记） | TODO |
| T-108 | 补齐 **AUTH 3 个**未实现 DAO 的实现类（**第 19 轮范围收窄**：CONFIG 侧 4 个已由 `T-109` 交付；`AuthorizationDao` 由 `T-016` 顺带实现，**不在本任务内**） | FR-CONFIG-1, FR-AUTH-1/14, NFR-MAINT-3, NFR-COMPAT-5 | backend-engineer#java2 | P0 | T-004 | 8 人时 | 新增（`T-016`/`CR-002` 实施受阻连带发现；编排者 2026-09-21 裁定立项；**2026-09-21 第 19 轮范围收窄**） | TODO |
| T-109 | 补齐「已声明未注册」端点（CONFIG 9 / SPOT 5 / PLAN 1） | 端点自身 FR（`REQUIREMENTS/CONFIG.md`/`SPOT.md`/`PLAN.md`）, ARCH §4.1.1, NFR-MAINT-5 | backend-engineer#java2 | P0 | T-005, T-006, T-007 | 12 人时 | 新增（验收面↔产出面对齐普查 `audit-cov`；编排者 2026-09-21 裁定立项） | DONE（附条件；判据 = **授权 + 独立验证**；**三轮独立复核**〔首轮「有条件通过」7 缺陷 → 复检「有条件通过」→ 增量复核「有条件通过」〕；两处**严重**缺陷 `TE-T109-001`（`FR-SPOT-11/12` 输入契约）与 `TE-T109-002`（`/spot/exposure/export` **越权读取**）**均已真修好并证实伪**；隔离副本 `375/0/0/0` 独立复现；`ApiRoutes.java` 未改〔`3dec526b…`；**以实时 `md5sum` 为准**〕；**唯一附条件 = `TE-T109-005` 验收 1（变更后重算敞口）未达成**，原因为**诚实阻塞**〔`java/src/main` 与 `cpp` 内**无任何敞口推导实现**〕⇒ 由 **`T-111`** 承接，先造引擎再回填验证 1） |
| T-110 | 补齐 SPOT 写面（`T-013` 范围）子账户可见性强制 | `FR-AUTH-13`, `NFR-SEC-7`, `R-SPOT-2`（子账户归属维度；**现货价属录入归属、不加强制**，见备注） | backend-engineer#java2 | P0 | T-109 | 8 人时 | 新增（增量复核 `te-verify-t109c` 探索性新发现 (A)；与 `TE-T109-002` **同族**、原任务表**无承载**） | DONE（判据 = **授权 + 独立验证**；独立复核 `te-verify-t110` 判定**通过**：8 处新增强制**逐一越权实测 `10004`/HTTP 403 且业务库零写入**；分类表经**双向**核〔无「该防没防」、无「误拒合法写入」〕；4/4 自设变异全红、复位全绿；隔离副本 `mvn -B -o clean test` = **`394/0/0/0`** 独立复现〔`2026-09-21T13:00:29+08:00`，clean、无排除；基线 375 ⇒ +19〕；`ApiRoutes.java` 未改〔`3dec526b…`；**以实时 `md5sum` 为准**〕） |
| T-111 | 实现服务端敞口推导引擎并回填 `FR-SPOT-16` 验收 1（变更后重算敞口） | `FR-SPOT-16` 处理规则 / 验收 1, `R-SPOT-4`, `Q-17` | backend-engineer#java2 | P1 | T-109 | 8 人时 | 新增（`T-109` 的 `TE-T109-005`） | TODO |
| T-112 | 写路径子账户可见性强制的全域收口（PRODUCT 写面 + PLAN 写面） | `FR-AUTH-13`, `NFR-SEC-7`；涉及写入口自身 FR = `FR-PRODUCT-12`（`/product/whitelist`）/ `FR-PRODUCT-11`（`/product/quota`）/ `FR-PLAN-6`·`FR-PLAN-7`·`FR-PLAN-8`（`/plan/ratio`）/ `FR-PLAN-1`（`POST /plan`） | backend-engineer#java2 | P0 | T-110 | 8 人时 | 新增（`te-verify-t110` 域外同类复核；四组逐条已证**未强制**且**在线可调用**） | DONE（独立复核通过；复核结论 = `te-verify-t112` **有条件通过**〔锚 `7e316b27…`〕+ 两条件闭环〔① 由 `T-098` 实现关闭；② 建议级已落〕+ **delta 已由 `te-verify-t115` 在现行锚 `1cfc6a3a…` 复验**；`T-115` 系重复立项、已 `REJECTED`） |
| T-113 | 修正 SPOT 现货单据创建在 `subAccountId` 缺省时的错误呈现（现 NPE→500，应明确提示「子账户必填」） | `FR-SPOT-1` 验收 2（`SPOT.md:101` 起；「未选择子账户即提交被拒绝，提示"子账户必填"」）, `R-SPOT-2` | backend-engineer#java2 | P1 | —（**归属 `T-013`**） | 2 人时 | 新增（`te-verify-t110` 域外同类复核新发现；`T-013` 既有 `requireSubAccount(int)` 对 `Integer` 字段自动拆箱 NPE） | TODO |
| T-114 | 补齐 `POST /spot/exposure/recalc`（`recalcForDisplay`）的子账户可见性强制 | `FR-AUTH-13`, `NFR-SEC-7`；`FR-SPOT-15`（敞口端点自身 FR，`SPOT.md:312`~`:325`） | backend-engineer#java2 | P1 | T-110（复用其范式） | 2 人时 | 新增（`T-112` 实施者第 3 步全仓写路径扫描；写路径越权面〔读 + `t_spot_change_log` 留痕写〕，与 `TE-T109-002` 同族） | TODO |
| T-115 | 修正 `CROSS_READONLY` 写面放行（写路径拒绝跨账户只读身份；读面语义不变） | `FR-AUTH-13` 验收3、`NFR-SEC-7`、`docs/DATA_MODEL.md`（`CROSS_READONLY` 只读性**由服务层保证**、写前校验 `perm_type`） | backend-engineer#java | P0 | T-102, T-110, T-112 | 0 人时（原 6，已扣除） | 新增（`te-verify-t112` 独立复核缺陷①（高·真绕过）；系 `T-102`/`T-110` 既有机制、非 `T-112` 引入，但 `T-112` 扩大暴露面） | REJECTED（**重复立项**：与既有 `T-098` 范围逐条重合；成果归 `T-098`；编号留痕、不计人时；2026-09-21） |
| T-116 | PRODUCT 读取面**「需求面 > 实现面」缺口补齐**（`FR-PRODUCT-5` 输出列「子账户」「最近变更时间」+ `FR-PRODUCT-8` 验收 1「导出与列表筛选一致」） | FR-PRODUCT-5 输出列（`PRODUCT.md:149`）、FR-PRODUCT-8 验收 1（`:198`）、FR-PRODUCT-5 输入列过滤（`:148`） | backend-engineer#java2 | P1 | T-105 | 3 人时 | 新增（`te-verify-t105` 独立复核「范围外观察」第 1/3 项；编排者 2026-09-21 裁定立项，项目经理登记） | TODO |
| T-117 | 修正 PRODUCT **白名单可见性 over-inclusive**（子查询补 `status='ENABLED'`，已移出品种不再算「可见」） | FR-PRODUCT-5 处理规则（「仅返回有权限的子账户范围内的品种」，`PRODUCT.md:147`）；载体 = `t_sub_product_whitelist`（`m1_product.sql:158`~`:189`，`status ∈ {ENABLED,DISABLED}`、移出 = 置 `DISABLED` 保留历史） | backend-engineer#java2 | P1 | T-105 | 2 人时 | 新增（`te-verify-t105` 独立复核「范围外观察」第 2 项，**判缺陷：可见性过宽**）；编排者 2026-09-21 裁定立项，项目经理登记） | TODO |
| T-118 | 补齐**全局 / 账户级对象写面的功能权限强制点**（`createProduct`/`disableProduct`/`enableProduct`/`putUnit`/`putContract`/`putSpotMapping`/`importProducts` 现**零权限门禁**） | FR-AUTH-13 **处理规则**（「跨子账户权限默认只读……不得执行下单、审批、改配置等操作」）、NFR-SEC-7、R-AUTH-8 | backend-engineer#java2 | P0 | T-119（层③断言器：`assertCanConfigureAccountLevel`；**2026-09-21 由 `T-049` 订正而来**） | 5 人时 | 新增（`tl-quota-authz` 技术席实测「全局对象写面零门禁」类缺口；用户 2026-09-21 裁定「同类单独立号」，项目经理登记） | TODO |
| T-119 | 补齐 `putQuota` **层③**（`ACCOUNT_PRODUCT` / 账户哨兵 `0`）**写面授权门禁**（M1 = 角色近似） | `FR-AUTH-12`（功能权限维度；`AUTH.md` §2）、`FR-AUTH-13`、`NFR-SEC-7`、`R-AUTH-8`；依据 `ARCHITECTURE.md` §8.2③ 新增条目（`v1.5.11`） | backend-engineer#java（断言器 `assertCanConfigureAccountLevel` 落 `AuthService`） | P0 | `CH-012`（需求侧待用户确认）+ **播种前置**（`t_role`/`t_user_role` 须与实现同批） | 5 人时 | 新增（`tl-quota-authz` 技术席层③实测 + 编排者 2026-09-21 裁定立项；**层③断言器原无编号承载**，本号补登记） | TODO |
| T-120 | `E4` 判据接线落地（默认根改三目录集合 `cpp/src/{order_engine,risk_engine,risk_state}` + `arch-check.sh` / `README` 同步） | ARCH §3.0.2 `E4` 脚注④（**诚实披露**）+ `§3.13`（判据脚本聚合入口）, NFR-PERF-1, NFR-MAINT-4/6 | tech-lead | P0 | T-031 | 3 人时 | 新增（`tl-e4-scope` 的 `ARCHITECTURE.md` §3.0.2 `E4` 脚注④ + 独立复核 `te-verify-t031` 第 2 点：「`E4` 恒 `SKIP`」= 判据未生效；判为**独立任务、非 `T-031` 缺陷**） | DONE（判据 = **授权 + 独立验证**；首轮复核「有条件通过」→ 补修 → **delta 复检通过、0 缺陷**；见 §2 备注） |

**合计：120 个任务**（M1 33 / M2 58 / M3 16 / M4 13）——**120 = 119 + `T-120`（1）〔第 31 轮新立〕；其中 119 = 118 + `T-119`（1）〔第 29 轮新立〕；其中 118 = 115 + `T-116`（1）+ `T-117`（1）+ `T-118`（1）〔第 28 轮新立〕；其中 115 = 114 + `T-115`（1） = 113 + `T-114`（1） = 111 + `T-112`（1）+ `T-113`（1） = 109 + `T-110`（1）+ `T-111`（1） = 104 + `T-105`（1）+ `T-106`（1）+ `T-107`（1）+ `T-108`（1）+ `T-109`（1） = 89 + `T-090`~`T-093`（4）+ `T-094`（1）+ `T-095`（1）+ `T-096`~`T-099`（4）+ `T-100`（1）+ `T-101`（1）+ `T-102`（1）+ `T-103`（1）+ `T-104`（1）+ `T-109`（1）**（`T-109` = 「已声明未注册」端点补齐（CONFIG 9 / SPOT 5 / PLAN 1），`#java2`，**M1 窗口**，依赖 `T-005`/`T-006`/`T-007`，来源 = 对齐普查 `audit-cov` + 编排者 2026-09-21 裁定；`T-090`~`T-093` = 架构 §3.0.2 判据 `E1`/`E3`/`E4`/`E6` 脚本落点，M2 首周批；**`T-094` = `T-061` 范围收窄后承接剩余 10 张 RISK 表，改归 M3 窗口**；**`T-095` = `E1`~`E6` 六脚本形态 / 退出码语义一致性收口（原 `E2` 按 `E5` 同法收紧，第 10 轮扩范围；兼收 `TE-AC-002`/`TE-AC-003`/`TE-AC-004`），M2 窗口**；**`T-096`~`T-099` = `ARCHITECTURE.md`（登记时点 `v1.5.8`；**以实时 `md5sum` 为准**）§4.1.1 两项约定 + §8.2 强制点／机制订正的落点，负责人 `#java`，M2 窗口，编号由技术负责人建议、项目经理登记方为事实**；**`T-100` = 架构 §8.2①③④ **主落点**（`GET /plan` 读取面隔离装配）的**编号补登记**，依赖 `T-016`，`#java`，M2 窗口，编排者 2026-09-21 裁定立项**；**`T-101` = `GET /plan/{id}/trade-plans` 读取面**子账户隔离装配**（`T-100` 的**相邻残余缺口** —— 同性质缺陷换端点仍复现；动 `PlanService.listTradePlans` + `TradePlanDao`/`DmTradePlanDao` + `TradePlanHandler`，**写域与 `T-100` 不同**，故**单独立号**），依赖 `T-100`，`#java`，M2 窗口，编排者 2026-09-21 裁定立项**；**`T-102` = 写面**子账户可见性强制**补齐（`T-100` 专项复核 `EXP-T100-1` 的同族发现：`AuthService.assertSubAccountVisible` **与** `AuthService.assertCanTrade` **均「已实现但生产 0 处调用」** + `PlanService.withdraw` **javadoc 承诺与实现不符** + **第 3 轮再扩 ④** `PlanService.decomposeTradePlans`（`:786`）**写路径读取未 scope**，来源 = `T-101` 交付披露），依赖 `T-100`，`#java`，M2 窗口，编排者 2026-09-21 裁定立项（**范围含 `assertCanTrade`**）**；**`T-103` = `GET /plan/{id}/trade-plans` **补分页/筛选/排序**（按 `FR-PLAN-15` **输入列**，`PLAN.md:321`；`FR-PLAN-15` 自身优先级 = **P0**〔`PLAN.md:324`〕；依赖 `T-101`，`#java`，M2 窗口；**不需 `CH-xxx`** —— `CONTRACTS §1.1` 已冻结「分页接口用 `PageData` 信封」，本项属**向既有冻结口径对齐**、系**修正违规**非变更契约）**；**`T-104` = `FR-PLAN-15` **处理规则**两项（**剩余可下单量** = 数量 − 已下达指令量；**已达可下单量上限标记**）的独立承载 —— 该项被 `T-101`/`T-103` **两次**判为「非本任务范围」⇒ 单独立号以免持续掉队；依赖 `T-103`，`#java`，M2 窗口**）；`T-029` 保留编号、状态 `REJECTED`，**不计入人时**；**总估时 1791 人时**（= 1788 + `T-120` **3 人时** = +3〔第 31 轮〕；= 1779 − `T-115` **6 人时** = −6〔第 25 轮：`T-115` 判 `REJECTED`、不计人时〕+ `T-116` **3 人时** + `T-117` **2 人时** + `T-118` **5 人时** = +10〔第 28 轮〕+ `T-119` **5 人时** = +5〔第 29 轮〕；= 1773 + `T-115` **6 人时** = +6〔第 24 轮〕；= 1771 + `T-114` **2 人时** = +2〔第 23 轮〕；= 1761 + `T-112` **8 人时** + `T-113` **2 人时** = +10；= 1714 + `T-105` **5 人时** + `T-106` **2 人时** + `T-107` **4 人时** + `T-108` **8 人时** + `T-109` **12 人时** + `T-110` **8 人时** + `T-111` **8 人时** = +47；= 1691 + `T-100` **4 人时** + `T-101` **3 人时** + `T-102` **5 人时** + `T-103` **4 人时** + `T-104` **2 人时** + `T-096` 上修 **1 人时** + `T-095` 上修 **4 人时**）——`T-061` 由 20 → **2 人时**（首增量单表）、`T-094` **+18 人时**（RISK DDL 总量守恒 20 人时）、`T-095` **+4 人时**、`T-096`~`T-099` **+2 +4 +3 +2 = +11 人时**、`T-100` **+4 人时**、`T-101` **+3 人时**、`T-102` **+5 人时**（`+4` → `+5`，第 3 轮再扩 ④ 后上修）、`T-103` **+4 人时**（第 4 轮新增，`EXP-T101-1`）、`T-104` **+2 人时**（本轮新增，`FR-PLAN-15` 处理规则两项，`#java`，M2）、`T-096` **+1 人时**（本轮上修 **2 → 3 人时**，追加「稳定排序」能力；依据见 §2 `T-096` 备注）、**`T-095` 第 10 轮再上修 4 → 8 人时（+4）**（扩为 `E1`~`E6` 六脚本形态/退出码语义一致性收口，兼收 `TE-AC-002`/`TE-AC-003`/`TE-AC-004`；**总估时 1710 → 1714**）。 **第 11 轮**：新增 **`T-105`/`T-106`/`T-107`**（`#java2`，M2 窗口，**+5 +2 +4 = +11 人时**）—— PRODUCT/SPOT **读取面口径收口**（`tl-triage` 全模块只读普查 + 编排者 2026-09-21 裁定立项）；**总估时 1714 → 1725 → 1733**。 **第 15 轮**：新增 **`T-108`**（补齐 CONFIG+AUTH **7 个**未实现 DAO 的实现类，`#java2`，M2 窗口，**+8 人时**；**第 19 轮范围收窄为 AUTH 3 个**，CONFIG 侧 4 个由 `T-109` 交付）；**任务数 107 → 108**、**总估时 1725 → 1733**。 **第 16 轮**：新增 **`T-109`**（补齐「已声明未注册」端点 **CONFIG 9 / SPOT 5 / PLAN 1**，`#java2`，**M1 窗口**，**+12 人时**）；**任务数 108 → 109**、**总估时 1733 → 1745**；**第 21 轮新增 `T-110`/`T-111`**（各 8 人时，`#java2`；`T-110` = SPOT 写面（`T-013` 范围）子账户可见性强制〔M1 窗口〕、`T-111` = 服务端敞口推导引擎 + `FR-SPOT-16` 验收 1 回填〔M2 窗口〕）**；任务数 109 → 111**、**总估时 1745 → 1761**。 **第 22 轮**：新增 **`T-112`**（写路径子账户可见性强制的全域收口〔PRODUCT 写面 + PLAN 写面〕，`#java2`，M1 窗口，**+8 人时**，**依赖 `T-110`**；来源 = `te-verify-t110` 域外同类复核）与 **`T-113`**（修正 SPOT 现货单据创建 `subAccountId` 缺省时的错误呈现〔NPE→500 ⇒ 应「子账户必填」〕，`#java2`，M1 窗口，**+2 人时**，归属 `T-013`）；**`T-110` 由 `TODO` → `DONE`**（独立复核 `te-verify-t110` 判定**通过**）；**任务数 111 → 113**、**总估时 1761 → 1771**。 **第 23 轮**：新增 **`T-114`**（补齐 `POST /spot/exposure/recalc`（`recalcForDisplay`）子账户可见性强制，`#java2`，M1 窗口，**+2 人时**，**依赖 `T-110`**；来源 = `T-112` 实施者第 3 步全仓写路径扫描）；**`T-112` 由 `TODO` → `IN_PROGRESS`**（2026-09-21 派发实施中，实施者 `backend-engineer#java2` / 实例 `be-t112`）；**任务数 113 → 114**、**总估时 1771 → 1773**。 **第 24 轮**：新增 **`T-115`**（修正 `CROSS_READONLY` **写面**放行〔写路径拒绝跨账户只读身份；读面语义不变〕，`#java`，M1 窗口，**+6 人时**，**依赖 `T-102`/`T-110`/`T-112`**；来源 = `te-verify-t112` 独立复核缺陷①〔高·真绕过〕）；**`T-112` 保持 `IN_PROGRESS`（第 2 轮：复核后补修）**；**任务数 114 → 115**、**总估时 1773 → 1779**。 **第 25 轮**：**`T-115` 判 `REJECTED`**（**重复立项** —— 与既有 `T-098` 范围逐条重合：同为「命中 `perm_type='CROSS_READONLY'` 的写操作**一律拒绝（服务端强制）**」、同为错误码 `10004`（403）、同为留痕 `t_unauthorized_attempt`（`R-AUTH-8`）、同为正/反双向测试要求；**成果归 `T-098`**；编号留痕、**不计人时**，依 `T-029` 先例；**动因** = 编排者立号时未扫整张既有任务表）；**`T-098` / `T-112` 转「已完成，待独立复核」**（纯状态口径、人时不变）；**任务数 115 不变**、**总估时 1779 → 1773**。

> 📌 **派发状态（2026-09-20，M1 施工推进）**：`T-003`/`T-004` → **`已完成，待独立复核`**（`#java` 交付并**冻结接口契约** `java/CONTRACTS.md`（**现行 `05c8161207e94d14c643fa632e66bc46` / 728 行，以实时 `md5sum` 为准**），工单已 `CLOSED`）；`T-026`/`T-027` → **`已完成，待独立复核`**（`#cpp` M2 首周探路**均通过**；**证据档 = 自评探路实证**）；`T-005`/`T-006`/`T-007` → **`已完成，待独立复核`**（`#java2` 同批交付 PRODUCT/SPOT/PLAN **26 张表** DDL 与 DAO；**已完成、待技术负责人门禁复核确认（不自评）**）。（**2026-09-20 时点快照，非现行**）**`T-061` → `DONE`（首增量 `t_param_dispatch` 单表 DDL + DAO 已完成、待独立复核）**，其余 10 张 RISK 表转 **`T-094`（M3 窗口，负责人待定）**。**`T-008`~`T-016` → `已完成，待独立复核`**（实现与自测到位、**尚无独立验证证据**，见 `REVIEW.md` `CR-003`）；`T-017` 起（含 `#java2` 其余条目、`T-028`）**未投递**；`T-030`（探路汇签）**仍为 `TODO`**，待第 3 项探路 `T-028`（`#java`）完成后由技术负责人汇签并下达 M2 铺开许可。

## 2. 任务详情

### T-001 落地数据模型 v1.3.1 一致性订正

- **需求追溯**：`SIGN-OFF.md` §5（测试席 `N-1`~`N-4` + 产品席 `PI-21`，5 项建议级）
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：无
- **估时**：4 人时
- **来源**：SIGN-OFF 决议
- **状态**：**DONE**（2026-09-20 18:34 落地）
- **验收标准**：
  1. `DATA_MODEL.md` §7 变更记录存在 **v1.3.1** 行，逐项覆盖 `N-1`~`N-4` 与 `PI-21`；
  2. 表数维持 **111**（`§0.6` 与 `§4.6.3` 合计恒等式 `①59+②19+③33=111` 仍成立）；
  3. `chain_integrity` 取值域与 `t_audit_log` 统一为 4 值（`DC-038`）；
  4. 改动不触及任何已写死的用例预期值（`SIGN-OFF.md` §5 判定依据）。
- **产出**：`docs/DATA_MODEL.md`（v1.3.1 订正已落地；**v1.3.2 收尾轮为其最终轮，已定版 `99b647ec3e7a29b95ee86b04a58c747c` / 5177 行**）
- **备注**：本任务为 **M1 门禁后的一致性订正**，不改变门禁「已达成」结论。**v1.3.2 为其收尾轮**（并入两席再验证披露的 `O-1`=`PI-24`、`PI-22`、`PI-23` 三项建议级），由编排者裁决出品并已完成定版冻结（`T-089`）；最终定版 md5 已回写 §0 与 `BOARD.md` §1.1。

### T-002 定点再验证 v1.3.1 订正结果

- **需求追溯**：`SIGN-OFF.md` §5（定点再验证条款）
- **负责人**：test-manager
- **优先级**：P0
- **依赖**：T-001
- **估时**：4 人时
- **来源**：SIGN-OFF 决议
- **状态**：**DONE**（2026-09-20 闭环：产品席、测试席**各自独立取证、均「无条件确认」**——不变式零漂移、实质改动 0、行号引用归零；**结论由两席追认承接、2026-09-21**）
- **验收标准**：
  1. 独立实测 `DATA_MODEL.md` md5/行数，与定版 md5 `99b647ec3e7a29b95ee86b04a58c747c`（5177 行 / 442225 字节）一致（不得采信产出方自评，`feedback/gate` 纪律）；✅〔**历史层**：现行定版 = `da779982…` / 5246 行；本结论由两席书面追认承接（2026-09-21），见本任务备注〕
  2. 对变更项做**行号级**取证（`N-1`~`N-4`、`PI-21`，及 v1.3.2 并入的 `O-1`=`PI-24`、`PI-22`、`PI-23`）；✅
  3. 机器重算**不变式**：表号去重 = 111、`DC` 唯一 40、索引 93/297/390、`§4.6.3` 三层合计 = 59/19/33、「可加总」恰 43、`§4.4` 22 条；✅
  4. **两席各自独立出具**（产品席 + 测试席，**各自独立取证、不得代汇总**），**两席均确认为「无条件确认」**；✅
- **产出**：`docs/gates/M1-data-model-signoff/product-manager.md`（**第 775–901 行**「v1.3.2 定点再验证（终局）」章节，作者 `product-manager-7`）与 `test-manager.md`（**`R12` 章**，作者 `test-manager-5`）的两席 v1.3.2 结论；`SIGN-OFF.md` **§7.3/§7.4**（两席结论 + T-002 闭环声明）与 **§8**（锚点变更史 + 6 条流程纪律）
- **备注**：**已闭环，M1 编码放行**（`R-12` 已裁决 → 预案 A，首发工单已派发，见 §5.1）。若定版 md5 再变动，本任务结论自动作废、须重跑。**（2026-09-21 承接说明）**：定版 md5 其后确已由 `99b647ec…`（v1.3.2）经 **v1.3.3**（`DC-041` 同编号改写：`t_exposure` 未映射口径改哨兵 `0` + 恒 `NOT NULL` + 新增 `spot_category_code` 品类双态 + `uk_exposure_key` 六列全非空；新增 `t_product` 保留系统行 `id=0`）与 **v1.3.4**（`TB-PLAN-1.term_end_date` 期限口径 `OR` → `min`）演变至 **`da7799826de29ebedc0de33f416fb4af`（5246 行；**以实时 `md5sum` 为准**）** ⇒ 上句自条款被触发。**补救 = 由两席书面追认承接**：`docs/gates/M1-data-model-signoff/REAFFIRM-product.md`（`5ac0bc90…` / 207 行）与 `REAFFIRM-test.md`（`19fed18e…` / 267 行）（两席均**认可·无条件**，均判两笔**不影响其原会签结论**，2026-09-21）——即对 v1.3.3 / v1.3.4 的**增量定点再验证**。故 `T-002` 结论**由两席追认延续**、**不自本条作废**；上句「自动作废、须重跑」**保留为原条款留痕**。

### T-003 搭建 Java 业务组件工程骨架与 HTTP 服务基座

- **需求追溯**：`FR-CONFIG-1`, `NFR-MAINT-1`, `NFR-MAINT-3`, `NFR-MAINT-5`, `NFR-SEC-6`, `NFR-SEC-7`, `NFR-DEPLOY-1`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-002
- **估时**：16 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**（证据档：实现 + 自测 + **PM 旁证复算**；工单 `T-003-task.md` 置 `CLOSED`；**无第三方独立验证记录**）
- **验收标准**：
  1. Maven 工程按 `ARCHITECTURE.md` §3.7~§3.12 分层建立包结构（`web/service/dao/bridge/risk_intraday/job`），`mvn -q compile` 目标 `JDK 11` 零错误、**不引入** HikariCP/SLF4J/BCrypt/Mockito；
  2. 内置 `com.sun.net.httpserver.HttpServer` 可启动，`GET /api/v1/ping` 返回 `{"code":0,...}` 信封；
  3. 静态托管 `GET /web/**` 生效，未知扩展名返回 404，仅放行白名单扩展名（`.html/.js/.css/.png/.svg/.ico/.woff2`）；
  4. 统一错误映射器输出 `{code,message,ruleId,current,threshold,nextStep,traceId}`（阻断类必含后四字段）；
  5. **WebSocket 对外约定纳入冻结清单**（与 REST 契约、共享 DTO 同批冻结）：**连接路径**（如 `/ws/quote`）、**鉴权方式**（握手期令牌/子账户上下文）、**订阅消息格式**（订阅/退订/行情帧）、**心跳与重连语义**（心跳间隔、断线重连后重新订阅）；冻结后**显式公布**并供前端（`T-017`/`T-053`）与 `#java2` 共用（`CH-006`：HTTP/WebSocket **全部在 Java 端实现**）。
- **产出**：`java/`（工程骨架 + `web` 层基座）
- **备注**：连接池、日志、口令哈希为**自实现**（`PROJECT.md` §5.1 最小依赖策略），本任务只搭骨架。**阻断已解除并已派发**（T-002 闭环 + `R-12` 已裁决 → 预案 A）。**本任务与 `T-004` 是 `#java2` 全部 29 条任务的前置**，交付后须**冻结 REST 契约、共享 DTO 与 WebSocket 对外约定**（`ARCHITECTURE.md` §1.2 约束「契约冻结纪律」；见 §5.1）。

### T-004 实现 DAO 基座并交付 CONFIG+AUTH 20 张表 DDL

- **需求追溯**：`FR-CONFIG-1/3/4`, `FR-AUTH-1/14`, `NFR-MAINT-3`, `NFR-COMPAT-5`, `NFR-SEC-8`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-003
- **估时**：24 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**（证据档：实现 + 自测 + **PM 旁证复算**；工单 `T-004-task.md` 置 `CLOSED`；**无第三方独立验证记录**）
- **验收标准**：
  1. `TB-CONFIG-1~7` + `TB-AUTH-1~13`（20 张）DDL 与 `DATA_MODEL.md` **逐列一致**（含 `NOT NULL`/`CHECK`/唯一约束/索引），在 DM8 实例上建表成功；
  2. `DataSourcePool`（自实现连接池）+ `TxTemplate`（事务模板）+ `DmDialect`（分页/日期/序列集中点）可用；
  3. 通用审计字段与编号生成（应用层生成、不依赖 DB 序列，`DATA_MODEL.md` §3.5）自动填充，抽 3 张表验证；
  4. `E5` SQL 外泄扫描（`dao` 之外无 SQL）通过；DM 特有语法紧邻注释标注 `// DM-SPECIFIC:`；
  5. **WebSocket 对外约定纳入同一冻结清单**：冻结 DAO 接口与共享 DTO 时，一并冻结 **WebSocket 对外约定**（连接路径 / 鉴权 / 订阅消息 / 心跳重连），与 `T-003` 的 REST/WS 冻结物**版本一致、显式公布**（供前端与 `#java2` 依赖；`CH-006`：WebSocket 全部在 Java 端实现）。
  6. **`java/dao/**` 下每个 DAO 接口均有对应 `Dm*` 实现类；若某个接口本期不需实现，须在 `TASKS.md` 显式登记「延后实现 + 原因 + 承接任务」（不得沉默略过）。**
- **产出**：`java/dao/**`、`docs/DEPLOY/schema/m1_config_auth.sql`
- **备注**：`t_sub_account`（`TB-CONFIG-1`）是子账户贯穿体系的根，建表须与 `CFG-02c/CFG-10` 取值域一致。**本任务是 `#java2` 全部 29 条任务（`T-005`~`T-016` / `T-044`/`T-045`/`T-047`~`T-049`/`T-051`/`T-052` / `T-062`/`T-067`/`T-068`/`T-069` / `T-076`~`T-081`）的**唯一解阻点**：交付后须**冻结 DAO 接口、共享 DTO（含共享表簇的实体/DTO）与 WebSocket 对外约定**并显式公布，`#java2` 方可并行开工（`ARCHITECTURE.md` §1.2 约束「契约冻结纪律」；见 §5.1）。 **缺口登记（2026-09-21，编排者取证）**：实测 `java/dao/` 下 **8 个 DAO 接口无 `dao/impl/Dm*` 实现**（`AccountSubjectDao`/`AuthorizationDao`/`CtpFrontConfigDao`/`DictItemDao`/`PermissionChangeLogDao`/`PositionGroupDao`/`ReportPeriodConfigDao`/`RolePermissionDao`），且 `AuthorizationDao` 全仓 **0 引用**。本任务原验收标准仅覆盖「基座 + 20 张表 DDL + 审计/编号」⇒ **产出面（`java/dao/**`）大于验收面**，缺口逃过验收。**故补验收标准第 6 条**（**只加不改**；原 1~5 条一字未动；编排者派单原称「第 4 条」，因本任务**实有 5 条**验收标准，新增项按序号顺延为**第 6 条**）；余 **7 个**实现由 **`T-108`** 承接，`AuthorizationDao` 由 **`T-016`** 顺带实现。

### T-005 交付 PRODUCT 模块 8 张表 DDL 与 DAO

- **需求追溯**：`FR-PRODUCT-1~14`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：16 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**（证据档：交付报告 + **PM 旁证复算**；**无第三方独立验证记录**）——依据 `#java2` 交付报告 + 项目经理独立实测复核：`docs/DEPLOY/schema/m1_product.sql` **8 张表 / 120 列**、`dao/impl` 本批 **8 个** `DmProduct*Dao`；同批 `mvn -o test` **108 用例 / 0 失败 / 0 错误 / BUILD SUCCESS**。逐列一致性比对与验收标准逐条结论见工单 `dev-team/inbox/backend-engineer#java2/T-005-task.md` 交付记录。⚠️ **DM8 实机建表未执行**（环境无 DM8 实例，`R-5`），已声明为环境前置，归 `T-052`——**不因此状态视为已通过**
- **验收标准**：
  1. `TB-PRODUCT-1~8` 交付（含 `TB-PRODUCT-6` 层①③额度、`TB-PRODUCT-8` 层②额度），与 `DATA_MODEL.md` 逐列一致，DM8 建表成功；
  2. `t_product_quota` 与 `t_sub_instrument_quota` 的 `sub_account_id` 为 `NOT NULL`（§4.6.3 ① 层）；
  3. 增删改查 DAO 覆盖 8 张表，`t_product_change_log` 前后值留痕写入可验证。
- **产出**：`java/dao/impl/DmProduct*Dao.java`、`docs/DEPLOY/schema/m1_product.sql`
- **备注**：数据边界 = PRODUCT 模块全部表；不得跨模块改表。
- **共享裁定登记（`TB-SPOT-8`，2026-09-20；本批 `T-005`/`T-006`/`T-007` 通用）**：`DATA_MODEL.md` 的 `TB-SPOT-8` `t_exposure.product_id` 原自相矛盾（约束列 `NOT NULL` 与备注「`unmapped_flag` 为 Y 时可 NULL）。已裁定改为条件非空：(`unmapped_flag` 为 Y 且 `product_id` 为 NULL) 或 (`unmapped_flag` 为 N 且 `product_id` 为 `NOT NULL`)，与 `Q-13` 的 `related` 列同构。订正已派 `tech-lead-18`（`DC` 号待回填）；本批建表脚本需同步调整，等其落地后由 `backend-engineer#java2` 调整并复测。

### T-006 交付 SPOT 模块 10 张表 DDL 与 DAO

- **需求追溯**：`FR-SPOT-1~18`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：16 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**（证据档：交付报告 + **PM 旁证复算**；**无第三方独立验证记录**）——依据 `#java2` 交付报告 + 项目经理独立实测复核：`docs/DEPLOY/schema/m1_spot.sql` **10 张表 / 181 列**、`dao/impl` 本批 **10 个** `DmSpot*Dao`；同批 `mvn -o test` 108 用例 0 失败。⚠️ **偏离登记**：`DATA_MODEL.md` §2.4 `TB-SPOT-8` **自相矛盾**（`t_exposure.product_id` 约束列写 `NOT NULL`，同表备注写「`unmapped_flag='Y'` 时可为 NULL」）——`#java2` 按**约束列**实现 `NOT NULL` 并已投 `question` 给技术负责人，**已裁定（2026-09-20，见本任务备注「共享裁定登记」）**；⚠️ DM8 实机建表未执行（归 `T-052`）
- **验收标准**：
  1. `TB-SPOT-1~10` 交付，DM8 建表成功，与 `DATA_MODEL.md` 逐列一致；
  2. `t_exposure`（根实体）含子账户标识、三类分量、`snapshot_time`/`price_as_of`/`snapshot_as_of`；
  3. `t_spot_price` 唯一约束为 `品种+日期`（`CFG-18` 口径 `GLOBAL`，`sub_account_id` 仅录入归属）；
  4. `t_spot_change_log.change_type` 列级 `CHECK` 含 `COMPONENT_RECALC`（v1.3 `DC-033`）。
  5. **`java/dao/**` 下本模块每个 DAO 接口均有对应 `Dm*` 实现类；若某个接口本期不需实现，须在 `TASKS.md` 显式登记「延后实现 + 原因 + 承接任务」（不得沉默略过）。**
- **产出**：`java/dao/impl/DmSpot*Dao.java`、`docs/DEPLOY/schema/m1_spot.sql`
- **备注**：`t_exposure` 的 `sub_account_id` 为 `NOT NULL`（§4.6.3 ① 层）。
- **共享裁定登记（`TB-SPOT-8`，2026-09-20）**：`DATA_MODEL.md` 的 `TB-SPOT-8` `t_exposure.product_id` 原自相矛盾（约束列 `NOT NULL` 与备注「`unmapped_flag` 为 Y 时可 NULL）。已裁定改为条件非空：(`unmapped_flag` 为 Y 且 `product_id` 为 NULL) 或 (`unmapped_flag` 为 N 且 `product_id` 为 `NOT NULL`)，与 `Q-13` 的 `related` 列同构。订正已派 `tech-lead-18`（`DC` 号待回填）；本批建表脚本需同步调整，等其落地后由 `backend-engineer#java2` 调整并复测。**本任务为 `t_exposure` 所在模块（SPOT），上述裁定直接适用**——`m1_spot.sql` 需由原「按约束列实现 `NOT NULL`」改为**列级 `CHECK` 条件非空**（与 `Q-13` 的 `related` 列同构）。

### T-007 交付 PLAN 模块 8 张表 DDL 与 DAO

- **需求追溯**：`FR-PLAN-1~20`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：16 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**（证据档：交付报告 + **PM 旁证复算**；**无第三方独立验证记录**）——依据 `#java2` 交付报告 + 项目经理独立实测复核：`docs/DEPLOY/schema/m1_plan.sql` **8 张表 / 163 列**、`dao/impl` 本批 **8 个** `DmPlan*Dao`；同批 `mvn -o test` 108 用例 0 失败。⚠️ **偏离登记**：`t_trade_plan.strategy_id → t_option_strategy`（TRADE，归 `T-043`）为**跨模块外键**，脚本以 `ALTER TABLE` 声明并注明执行顺序前置；⚠️ DM8 实机建表未执行（归 `T-052`）
- **验收标准**：
  1. `TB-PLAN-1~8` 交付（含 v1.2 新增 `TB-PLAN-8` `t_custom_hedge_rule`），DM8 建表成功；
  2. 计划/交易计划/书面文件/审批链均含 `NOT NULL` 子账户标识（§4.6.3 ① 层，PLAN 模块 8/8 全携带）；
  3. `t_custom_hedge_rule` 支持「`ratio_cap` 升序 → `priority` 升序 → `id` 升序」命中算法所需字段与索引（`CFG-06`）。
  4. **`java/dao/**` 下本模块每个 DAO 接口均有对应 `Dm*` 实现类；若某个接口本期不需实现，须在 `TASKS.md` 显式登记「延后实现 + 原因 + 承接任务」（不得沉默略过）。**
- **产出**：`java/dao/impl/DmPlan*Dao.java`、`docs/DEPLOY/schema/m1_plan.sql`
- **备注**：—
- **共享裁定登记（`TB-SPOT-8`，2026-09-20；本批 `T-005`/`T-006`/`T-007` 通用）**：`DATA_MODEL.md` 的 `TB-SPOT-8` `t_exposure.product_id` 原自相矛盾（约束列 `NOT NULL` 与备注「`unmapped_flag` 为 Y 时可 NULL）。已裁定改为条件非空：(`unmapped_flag` 为 Y 且 `product_id` 为 NULL) 或 (`unmapped_flag` 为 N 且 `product_id` 为 `NOT NULL`)，与 `Q-13` 的 `related` 列同构。订正已派 `tech-lead-18`（`DC` 号待回填）；本批建表脚本需同步调整，等其落地后由 `backend-engineer#java2` 调整并复测。

### T-008 实现子账户管理与全生命周期服务

- **需求追溯**：`FR-CONFIG-1`, `CFG-02c`, `CFG-10`, `PROJECT.md` §4.1.B/§4.1.C
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：12 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**
- **验收标准**：
  1. `POST /api/v1/config/sub-account` 创建子账户（含 `category` 商品类/贸易类），返回 200 且落库；
  2. 停用走 `DISABLE_ONLY`：资金 0 + 持仓 0 + 无在途指令 + 当日已结算四条件不满足时返回阻断错误；**无物理删除接口**；
  3. 停用后历史数据只读且**仍参与加总对账**可验证；
  4. 子账户分类变更须留痕（`t_config_change_log`）。
- **产出**：`java/service/ConfigService.java`（子账户部分）、REST `/config/sub-account`
- **备注**：**跨栈依赖**：子账户额度/白名单变更须经 T-011 下发 `CMD_UPDATE_QUOTA` / `CMD_UPDATE_WHITELIST`；M1 阶段仅落库 + 生成待下发记录，M2 由 T-042 接线生效。

### T-009 实现系统配置服务与零落盘约束

- **需求追溯**：`FR-CONFIG-1~15`, `NFR-SEC-1`, `NFR-SEC-2`, `R-CONFIG-5/8/9`, `CFG-19/20/22`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：16 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**
- **验收标准**：
  1. `CFG-01`~`CFG-31` 全部配置项可 CRUD，分类与持久化属性符合 `ARCHITECTURE.md` §11.3；
  2. **敏感字段零落盘**：`password/auth_code/fund_password/auth_token` 结构上**无存值能力**（仅 `sensitive_state`），全库 grep 零命中（`PROJECT.md` §10-4）；
  3. `hg.tcp.bind` / `hg.ws.bind` 白名单仅 `127.0.0.1`，配为其它值**拒绝启动并告警**（`FR-CONFIG-9`）；
  4. 配置变更落 `t_config_change_log`（含前后值、操作人、下发结果）。
  5. **按 `docs/DEPLOY/schema/data-init.sql` §② 诚实披露表所列本模块承接项，在 `data-init.sql` 完成播种（幂等），并在测试中断言播种后行数/关键字段。**
- **产出**：`java/service/ConfigService.java`、REST `/config/**`
- **备注**：**跨栈依赖**：影响 C++ 判定的配置项经 T-011 下发；默认值不得低于 `INDEX.md` §9.1 口径（`R-CONFIG-8`）。**待核项（独立复核范围，项目经理 2026-09-21 补登）**：其独立复核须一并核 `docs/DEPLOY/schema/data-init.sql` §② 登记的三项 **CONFIG** 待播种数据**是否已落地** —— `t_dict_item`（业务枚举字典）、`t_config_item`（`ARCHITECTURE.md` §11 全部 `CFG-*` 默认值）、`t_report_period_config`（六类报告周期与提醒角色）；**实测全 `docs/DEPLOY/schema/` 目录仅 2 条 `INSERT`**（`t_sub_account` / `t_product`）⇒ **似未落地**。**承接方已在 §② 登记为「CONFIG 模块任务」**。**理由**：`T-010` 刚被独立复核判**不通过**，其缺口含「预设数据零播种」；CONFIG 三项属**同一类** ⇒ 应在**同一判据**下被核，而非等其自行暴露。

### T-010 实现用户与权限服务（岗位分离 + 子账户软隔离）

- **需求追溯**：`FR-AUTH-1~18`, `R-AUTH-13`, `PROJECT.md` §3 岗位分离约束、§10-15/34
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：20 人时
- **来源**：初版
- **状态**：**IN_PROGRESS**（2026-09-21 独立复核判定**不通过**，退回重做）
- **验收标准**：
  1. `/auth/login`/`/auth/logout` 可用，会话空闲 30 min（`CFG-23b`）超时；口令加盐哈希自实现，历史重用 5 次（`CFG-23b`）；
  2. **岗位互斥**：同一用户不能同时拥有交易岗与风控岗；配置越权角色时系统拒绝（`PROJECT.md` §10-15）；
  3. **负责人禁操盘（判据为「析取」—— 依据 `CH-010`）**：下述**两条各自独立**的拒绝条件，满足**任一**即拒绝操盘（**不得**解读为须**同时**满足 / 合取）：① `is_enterprise_leader='Y'` 的用户操盘被拒（**不要求该账号同时具管理层角色**）；② 持有**管理层（审批人）**角色者（判定来源 `APPROVER`；字段 `is_management_role='Y'`）操盘被拒（**不要求该账号同时具企业负责人标记**）。（`R-AUTH-13`；`CH-010` 已登记于 `docs/REQUIREMENTS/AUTH.md`，**以实时 `md5sum` 为准**。）
  4. **子账户软隔离**：子账户 A 用户看不到 B 数据；审计/管理层授权后可跨账户查看（`PROJECT.md` §10-34）。
  5. **按 `docs/DEPLOY/schema/data-init.sql` §② 诚实披露表所列本模块承接项，在 `data-init.sql` 完成播种（幂等），并在测试中断言播种后行数/关键字段。**
- **产出**：`java/service/AuthService.java`、REST `/auth/**`
- **备注**：**跨栈依赖**：权限快照经 T-011 下发 `CMD_UPDATE_PERMISSION`（M2 生效）。
- **📌 独立复核结论（2026-09-21，复核者 `te-verify-t010`）：判定「不通过」→ 退回重做**（`docs/TEST_RESULT.md` §`T-010`，**以实时 `md5sum` 为准**；基线 `310/0/0/0` clean、共享树源码零改动）。以下**四项**硬缺口**仅登记，不改上方原验收原文**：
  - **(a) REST 8 端点未注册**：`ApiRoutes` 声明 AUTH **10** 条路径，`AuthRoutes.register` **实际注册 2**（`POST /auth/login`、`POST /auth/logout`）⇒ 差集 **8**、反向 ∅ ⇒ REST 完成度 **2/10 = 20%**。未注册 8 条：`/auth/user`、`/auth/role`、`/auth/position-group`、`/auth/authorization`、`/auth/sub-account-perm`、`/auth/change-log`、`/auth/unauthorized-attempt`、`/auth/password`。
  - **(b) `FR-AUTH-1~18` 覆盖 = 已交付 2 / 部分 5 / 未交付 11**（逐条清单见 `TEST_RESULT` §`T-010.6`）。
  - **(c) 两处逻辑缺口**：① `FR-AUTH-17` 验收④ —— 锁定阈值/时长**硬编码常量、未接 `CONFIG`**；② `FR-AUTH-15` ② —— `SessionAuthFilter` **不调用 `touchSession`**、`SessionStore.InMemory.find` 按**绝对** `expireTime` 判定 ⇒ 实为「登录后 30 分钟**绝对**过期」，**非「空闲」超时**。
  - **(d) AUTH 预设数据零播种**（项目经理 2026-09-21 实测）：`docs/DEPLOY/schema/` 全目录**仅 2 条 `INSERT`**（`t_sub_account`、`t_product`，均在 `data-init.sql`）；`t_role` / `t_position_group` / `t_position_conflict` / `t_user` **0 命中**。而 `docs/DEPLOY/schema/m1_config_auth.sql:508` 自述 `t_position_conflict.is_mandatory`「**默认互斥集恒为 `Y`（不可转为可配置）**」⇒ 默认集**本应被播种**。**影响**：`FR-AUTH-6`（定义角色）、`FR-AUTH-7`（配置岗位分离互斥）在**部署后无数据** ⇒ **岗位分离与权限体系在实例上无法工作**；亦是复核探针中 `APPROVER + TRADER` 组合可达成之因（**互斥矩阵为空**）。**取证命令**：`grep -rhoiE 'insert into [a-z_]+' docs/DEPLOY/schema/ | sort | uniq -c` ⇒ 仅 `1 INSERT INTO t_sub_account` / `1 INSERT INTO t_product`。**承接核查**：`TASKS.md` 全表**无**任务把 `data-init.sql` 列入产出、亦**无** `D-xx` 部署工作项承接数据初始化（`T-004` 产出仅 `java/dao/**` + `docs/DEPLOY/schema/m1_config_auth.sql`，其验收 1 覆盖 DDL 逐列一致、**不含**数据播种）⇒ **AUTH 预设数据无既有承接任务**。载体文件 `docs/DEPLOY/schema/data-init.sql` **已存在**，其 §② 诚实声明表**逐条登记** `t_position_group` / `t_role` / `t_position_conflict` 的播种**承接方 = 「AUTH 模块任务」** ⇒ **由本任务（即 AUTH 模块任务）一并交付**（在既有 `data-init.sql` 追加 AUTH 预设段；`t_user` 按需附带）；其 DDL 载体 `m1_config_auth.sql` 由 **`T-004`** 交付 ⇒ **依赖 `T-004`**。**阻塞依赖（项目经理 2026-09-21 补登）**：`t_position_conflict` 的 `group_a_id` / `group_b_id` 引用的是 **`t_position_group(id)`（岗位组）**，而 `TB-AUTH-6` 备注的默认互斥集写作**角色编码**（`TRADER ↔ FIN_RISK` 等）—— **两者编码同名但实体不同** ⇒ **须先确认「岗位组编码集合」的口径**（该澄清已登记于 `T-004` 工单「与文档不一致登记」`O-4` 条；**实测编号为 `O-4`，交底所述 `O-2` 经核为该工单审计字段条、非本条**）⇒ **缺口 (d) 的关闭前提 = 该岗位组编码口径澄清**。
  - **(跨写域) `FR-AUTH-15`② 的接线点 `SessionAuthFilter`（`web/` 框架类）属 `#java` 写权** ⇒ 该缺口**跨写域**，重做时**不能只改 `AuthService`**（来源 `EXP-T010-4`；`docs/TEST_RESULT.md` §`T-010.7`）。
  - **不拆号**（编排者口径）：其产出列已含 `REST /auth/**`，8 端点缺口在此范围内；拆号会造成范围重复。

### T-011 实现参数下发编排 ParamDispatchService

- **需求追溯**：`R-CONFIG-3`, `R-AUTH-9`, `R-PRODUCT-9`, `R-PLAN-5`, `R-RISK-9`, `ARCHITECTURE.md` §3.8/§6.3
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004, T-061
- **估时**：12 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**
- **验收标准**：
  1. 统一「改 Java 侧 → 下发 → 等回执 → 留痕」流程，供 Variety/Plan/Risk/Auth/Config **共用**（避免 5 处各写一套）；
  2. `param_version` 单调递增分配，下发记录落 `t_param_dispatch`；
  3. 回执超时 3000 ms（`CFG-28`）标记「未生效」+ 告警 + **不得视为已生效**（`R-CONFIG-3`）；
  4. 限额下调类变更实现「先冻结该子账户新开仓 → 替换快照 → 解冻」两阶段（`ARCHITECTURE.md` §9 `RSK-04`）。
- **产出**：`java/service/ParamDispatchService.java`
- **备注**：**跨栈依赖**：依赖 HGCP 控制指令 `CMD_UPDATE_WHITELIST`(0x0112) / `CMD_UPDATE_QUOTA`(0x0113) / `CMD_UPDATE_POSITION_LIMIT`(0x0114) / `CMD_UPDATE_RISK_THRESHOLD`(0x0115) / `CMD_UPDATE_PERMISSION`(0x0116) / `CMD_UPDATE_EXPOSURE`(0x0117) / `CMD_LOAD_RISK_PARAMS`(0x0111)；M1 仅落库占位，M2 由 T-042/T-038 打通。

### T-012 实现品种管理与白名单/额度服务

- **需求追溯**：`FR-PRODUCT-1~14`, `R-PRODUCT-9`, `CFG-16`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-005
- **估时**：16 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**
- **验收标准**：
  1. 品种增删改启停 + 计量单位换算 + 合约关联 + 现货品类映射全部可用；
  2. **白名单**按子账户维度维护（`t_sub_product_whitelist`），额度按三层（层①`SUB_PRODUCT`、层②`SUB_INSTRUMENT`、层③`ACCOUNT_PRODUCT`）维护；
  3. CSV 导入做全量预校验后一次性插入，缺列/格式错误给出明确报错（不静默降级）；
  4. 导出可用；变更落 `t_product_change_log`。
- **产出**：`java/service/VarietyService.java`、REST `/product/**`
- **备注**：**跨栈依赖**：白名单/额度变更经 T-011 下发 `CMD_UPDATE_WHITELIST` / `CMD_UPDATE_QUOTA` / `CMD_UPDATE_POSITION_LIMIT`（M2 生效）。

### T-013 实现现货管理服务（合同/生产/库存/现货价）

- **需求追溯**：`FR-SPOT-1~12`, `CFG-18`, `R-SPOT-8`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-006
- **估时**：20 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**
- **验收标准**：
  1. 采购/销售/生产计划/在制品/库存台账单证 CRUD，均携带 `NOT NULL` 子账户标识；
  2. 现货价支持单条录入、批量粘贴、CSV 导入三种方式，**唯一口径 = `品种+日期+来源标注`**（`CFG-18`，无第三方资讯源对接）；
  3. 现货价缺口处置按用途分流：比率计算用途 `REJECT`、估值/展示用途 `CARRY_FORWARD_LABELED` 且标注 `price_as_of`（`CFG-12/12b`）；
  4. 草稿类单据可物理删除，非草稿仅状态流转。
- **产出**：`java/service/SpotService.java`、REST `/spot/**`
- **备注**：模块边界 = 现货视图，不含完整 ERP 流程（`PROJECT.md` §4.2）。

### T-014 实现实货敞口计算服务（三分量 + 快照时点 + 缺口处置）

- **需求追溯**：`FR-SPOT-13~18`, `CFG-17`, `CFG-17b`, `Q-17`, `PROJECT.md` §10-9
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-013
- **估时**：16 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**
- **验收标准**：
  1. 敞口 = 合同未执行量 + 库存 + 生产计划三类分量（`CFG-17` 全开），**同事务计算三分量与合计**；
  2. `Σ分量 ≠ 合计` 时**写入侧拒绝**（不静默覆盖）；确需改落库值须重算生成新快照、旧快照保留；
  3. 展示侧不一致 → 以分量重算 + 告警 + 留痕 `COMPONENT_RECALC`，**不回写落库值**（`CFG-17b`）；
  4. 敞口快照含 `snapshot_time`，比率计算用途缺价一律拒绝。
- **产出**：`java/service/SpotService.java`（敞口部分）、REST `/spot/exposure*`
- **备注**：**跨栈依赖**：敞口快照经 T-011 下发 `CMD_UPDATE_EXPOSURE`(0x0117)（含三分量与 `snapshot_time`，M2 生效）；敞口是 17 号文「期现一体」的根，方向单向。

### T-015 实现套保比率三档计算

- **需求追溯**：`FR-PLAN-6/7/8`, `R-SPOT-8`, `CFG-06/06b`, `Q-6`
- **负责人**：backend-engineer#java2
- **优先级**：P1
- **依赖**：T-007
- **估时**：16 人时
- **来源**：初版
- **状态**：**已完成，待独立复核**
- **验收标准**：
  1. 简单比率法、最小方差法 `h = ρ × σ_S / σ_F`、自定义规则法三档均可用；
  2. 最小方差法样本 ≥ 30 个交易日（`CFG-06b`），不足时**明确提示样本不足**、不静默降级；导入 ≥ 30 日序列可正确算出 ρ 与 `σ_S`（`PROJECT.md` §10-9）；
  3. 自定义规则法按 `TB-PLAN-8` 实体「`ratio_cap` 升序 → `priority` 升序 → `id` 升序」取一；
  4. 每次计算留痕落 `t_hedge_ratio_calc`（含方法、输入、结果）。
- **产出**：`java/service/PlanService.java`（比率部分）、REST `/plan/ratio/*`
- **备注**：—

### T-016 实现套保计划编制/书面文件门禁/审批链/状态机

- **需求追溯**：`FR-PLAN-1~5/9~20`, `R-PLAN-5`, `PROJECT.md` §6 规模/期限红线、§9 M1 验收
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-007, T-015
- **估时**：24 人时
- **来源**：初版
- **状态**：**DONE**（附条件；2026-09-21；判据 = **授权 + 独立验证**；独立复核 = **有条件通过**（13 项判据 12 符合 / 1 不符〔不符项 = ③ 可见性之 (c)「审批页面展示」〕；`CR-002` 本体核心反例闭合；反退化 8/8 全红；隔离副本 `346/0/0/0` 独立复现）；**条件 = (c)「审批页面展示」由 `T-020` 承载**（编排者 2026-09-21 裁定：(c) 的**接口响应侧**由本任务交付、**页面侧**由 `T-020` 交付；**义务不减**）；原 2026-09-21 `CR-002`（严重）退回事由已由本轮独立复核闭合）
- **验收标准**：
  1. 计划绑定子账户与实货敞口（`t_hedge_plan_exposure`），状态机 `DRAFT→…→APPROVED`、支持撤销/冻结/终止/延期，全部流转落 `t_plan_state_log`；
  2. **书面文件三要素门禁**：未含「正式指定 + 有效性评估方法 + 再平衡机制」不得提交审批（CAS 24 法定留痕）；
  3. 计划分解为交易计划 `t_trade_plan`，审批链落 `t_plan_approval`；
  4. 审批通过占用额度并留痕，撤销/终止释放额度。
  5. **提交前服务端阻断式合规预校验（`R-PLAN-3` 本期强制 6 项，架构 §6.4「过渡口径与字段级判定」，`v1.5.7`；以实时 `md5sum` 为准）**：由 `PlanService.submitForApproval` **服务端自行重算** ①②④⑤⑥⑦ 六项，**任一不通过即阻断提交**，输出**四项载荷** `ruleId`（`R-PLAN-3/①`…`/⑦`）/`current`/`threshold`/`nextStep`；**禁止**以客户端传入的 `precheckResult` 作为通过依据（服务端**忽略其内容**，且**不因其缺失/为空而拒单**）。**判定顺序（短路式）**：① `status=DRAFT`（`10009`）→ ② 书面文件三要素（`10012`）→ ③ 岗位分离（`10005`）→ ④ 操作授权（`10004`/`10003`）→ ⑤ 白名单（`10003`）→ ⑥ 期限红线（`10012`）→ ⑦ 数量 ≤ 未覆盖量（`10012`）；**③ 规模红线不在序内**。**③ 规模红线归 M3 承接**（分母 ∈ `t_annual_scale`，M1 不存在）：**M1 不得因 ③ 单项拒单**，但**必须满足三项可见性，缺一即缺陷** —— **(a)** 提交响应体含 `unverified` 且含 `"R-PLAN-3/③"`；**(b)** 落库 `t_plan_approval.precheck_result.unverified` 含 `"R-PLAN-3/③"`；**(c)** 审批接口响应与审批页面**均须展示**该标记。**期限红线口径**：上限取「建仓日 + `CFG-03` 冻结默认 12 个月」与「实货合同到期日 + 30 天宽限」**较早者**（与 `DATA_MODEL.md` `TB-PLAN-1.term_end_date` `v1.3.4` 勘误后的 `min` 口径一致）。**不接入测试/不判红说明**：③ 未覆盖期间的判定以 **(a)(b)(c) 三项可见性**替代。 **📌 位号澄清（编排者 2026-09-21 裁定）**：本节及 `ARCHITECTURE.md` §6.4 的「判定顺序」用**执行次序位号**（① `status=DRAFT` → ② 书面文件 → ③ 岗位分离 → ④ 操作授权 → ⑤ 白名单 → ⑥ 期限 → ⑦ 数量），与实际报出的 **`ruleId` 编号不是同一套**；**`ruleId` 的唯一语义基准 = `REQUIREMENTS/PLAN.md` 的校验项编号**（① 操作授权 / ② 白名单 / ③ 规模红线 / ④ 期限 / ⑤ 数量 / ⑥ 书面文件 / ⑦ 岗位分离）。**不得按执行位号取 `ruleId`。** **📌 范围划分（编排者 2026-09-21 裁定）**：本条 (c) 三项可见性中，**(a) 提交响应体**与 **(b) 落库**由本任务（`T-016`）交付；**(c) 的「审批接口响应」**亦由本任务交付（`GET /plan/{id}` 详情响应）；**(c) 的「审批页面展示」**不在本任务**产出面**内（本任务产出 = `java/service/PlanService.java` + REST `/plan/**`），**由 `T-020` 交付**（见 `T-020` 验收标准相应条目）。**该项义务不减**，仅落点更正（依据：`FR-PLAN-10` 验收 2 要求「审批接口响应**与审批页面均**展示」；本项目「产出面 ≥ 验收面」原则）。
- **产出**：`java/service/PlanService.java`、REST `/plan/**`
- **备注**：**跨栈依赖**：计划批准后的额度下发 `CMD_UPDATE_QUOTA`（经 T-011，M2 生效）。 **📌 写者边界与排程（编排者 2026-09-21 裁定）**：**`CR-002` 修复的写者 = `#java2`（本任务属主）**；因 `PlanService.java` 与 `#java` 的 `T-100`/`T-102`/`T-104` 等改动**同属一文件**，须与 `#java` **串行（同一文件单写者），不得并发**。**排程**：`CR-002` 修复**插在 `#java` 当前串行链的 `T-102` 之后立即执行**（即 `T-102 → T-016(CR-002) → T-104 → T-099 → T-098 → T-096 → T-097`）—— 理由：`CR-002` 为**严重级**（阻断式合规预校验可被伪造绕过，使 8 号文「阻断违规操作」失效），**优先于 M2 窗口的边界一致性项**。 **📌 追溯登记（2026-09-21，编排者取证 + 交付方只读自查）**：① **`PlanService` 未承载的 `FR-PLAN`** = **`FR-PLAN-2` / `FR-PLAN-3` / `FR-PLAN-20`**（交付方实测：在 `PlanService` **无独立方法承载** ⇒ 属「**无落点**」而非「缺引」，**不得硬塞引用**；其真实落点须在读取/展示面或变更纪律中确认，**待编排者裁定**）；② **`FR-PLAN-20`（查询计划执行进度）** 的「进度/偏差视图（时间进度占比、偏差提示）」在 handler / `PlanView` 层，**落点待定**；③ **`java/CONTRACTS.md:38` 的写者自声明**（「🔒 本文件的唯一写者 = `backend-engineer#java`」）**与编排者实际授权不符**（编排者已授权 `#java2` 写其 `§1.5` / `§2.2.1` / 位号澄清）⇒ **编排者裁定：写者边界改为「后端工程师席位（`#java` / `#java2`）均可写，但同一时刻只允许一个实例写」**；**`CONTRACTS.md:38` 的措辞待回写**（归后端席位，**待编排者另派**）。

### T-017 搭建前端基座（登录/核心工具/零构建链）

- **需求追溯**：`NFR-USE-1~5`, `NFR-MAINT-1`, `NFR-MAINT-5`, `NFR-COMPAT-3`, `ARCHITECTURE.md` §3.15/§7.1.9
- **负责人**：frontend-engineer
- **优先级**：P0
- **依赖**：T-003
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 零构建链：**无** `package.json`/`node_modules`/打包产物；改动源文件刷新即生效（`E6` 脚本 + 全库 grep 双通过）；
  2. `js/core/{api.js,router.js,session.js,ui.js,format.js,errmsg.js}` 就位，统一命名空间 `HG.<module>.*`，禁全局 `var`；
  3. 登录页对接 `POST /api/v1/auth/login`，会话 `HGSESSION`（HttpOnly Cookie）；
  4. 阻断类错误统一渲染 `ruleId/current/threshold/nextStep`（`NFR-USE-3/4`）；金额/数量按字符串展示（避免 `Number` 精度丢失）。
- **产出**：`web/index.html`, `web/login.html`, `web/css/hg.css`, `web/js/core/**`
- **备注**：前端只与 Java 的 `/api/v1/**` 与 `/ws/**` 通信，**不得**直连数据库或 C++（`E6`）。

### T-018 实现品种管理前端页面

- **需求追溯**：`FR-PRODUCT-1~14`
- **负责人**：frontend-engineer
- **优先级**：P1
- **依赖**：T-017, T-012
- **估时**：12 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 品种列表/详情/白名单/额度/变更历史页面可用；
  2. 对接 REST：`/api/v1/product`、`/product/{id}`、`/product/whitelist`、`/product/quota`、`/product/import`、`/product/export`；
  3. 导入错误按行提示（缺列/格式错误）；导出触发 CSV 下载。
- **产出**：`web/js/pages/product.js`
- **备注**：接口依据 `ARCHITECTURE.md` §4.1.1。

### T-019 实现现货与实货敞口前端页面

- **需求追溯**：`FR-SPOT-1~18`, `NFR-USE-5`
- **负责人**：frontend-engineer
- **优先级**：P1
- **依赖**：T-017, T-013, T-014
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 采购/销售/生产/在制品/库存/现货价（含批量粘贴与 CSV）页面可用；
  2. 实货敞口页展示三类分量与合计，标注快照时点；分量不一致时展示重算结果与告警；
  3. 对接 REST：`/spot/purchase`、`/spot/sales`、`/spot/production`、`/spot/wip`、`/spot/inventory`、`/spot/price`、`/spot/price/import`、`/spot/exposure*`。
- **产出**：`web/js/pages/spot.js`
- **备注**：—

### T-020 实现套保计划前端页面

- **需求追溯**：`FR-PLAN-1~20`
- **负责人**：frontend-engineer
- **优先级**：P1
- **依赖**：T-017, T-016
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 计划编制（含三档比率计算调用）、书面文件预览、提交/审批/驳回/撤销/冻结/终止/延期操作可用；
  2. 对接 REST：`/plan`、`/plan/{id}/bind-exposure`、`/plan/{id}/doc`、`/plan/{id}/submit|approve|reject|withdraw|freeze|unfreeze|terminate|extend`、`/plan/ratio/*`；
  3. 三要素未齐的书面文件无法提交（前端与后端门禁一致，前端提示原因）。
  4. **交易计划列表**（`GET /plan/{id}/trade-plans`，`FR-PLAN-15`）消费 `T-103` 的**分页信封**（`PageData`，默认 `size=20`）并展示 **`remainingQty`（剩余可下单量）** / **`limitReached`（已达上限标记）** —— 两字段由 `T-104` 服务端提供（**本项依赖 `T-103` / `T-104`**）；已达可下单量上限的已生效计划**须明确标记**。
  5. **审批页面须展示合规预校验的未校验标记**：当计划提交时含 `unverified`（M1 期间恒含 `"R-PLAN-3/③"`），**审批页面须显式展示该标记**（消费 `GET /plan/{id}` 详情响应 `data.precheckResult.unverified`）。依据 `FR-PLAN-10` 验收 2（`docs/REQUIREMENTS/PLAN.md:253`）「审批接口响应**与审批页面均展示**该标记，**任一缺失即缺陷**」+ `java/CONTRACTS.md §1.5` + `docs/ARCHITECTURE.md §6.4`；来源 = `T-016` 独立复核 `TE-016-001`（编排者 2026-09-21 裁定由本任务承接）。
- **产出**：`web/js/pages/plan.js`
- **备注**：**交易计划列表前端**（`FR-PLAN-15`，`GET /plan/{id}/trade-plans`）归本任务 —— 须消费 `T-103` 的 `PageData` 分页信封并展示 `T-104` 的 `remainingQty` / `limitReached`（**依赖 `T-103` / `T-104`**）。⚠️ 本条为 `T-104`「前端展示」归属的落点登记（原「待编排者指派」已明确归本任务）；**本任务状态与估时不变**。

### T-021 实现权限与系统配置前端页面

- **需求追溯**：`FR-AUTH-1~18`, `FR-CONFIG-1~15`
- **负责人**：frontend-engineer
- **优先级**：P1
- **依赖**：T-017, T-008, T-009, T-010
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 用户/角色/岗位组/授权/子账户数据权限/变更历史页面可用；岗位互斥冲突时给出明确提示；
  2. 配置项/CTP 配置/子账户/字典/报告周期页面可用，敏感字段仅显示「已设置/未设置」状态、**不可回显**；
  3. 对接 REST：`/auth/user`、`/auth/role`、`/auth/authorization`、`/auth/sub-account-perm`、`/config/item`、`/config/ctp`、`/config/sub-account`。
- **产出**：`web/js/pages/auth.js`, `web/js/pages/config.js`
- **备注**：—

### T-022 制定 M1 测试策略与用例设计（约束→用例映射）

- **需求追溯**：`NFR-TEST-1/2/3`, `NFR-MAINT-2`, `DATA_MODEL.md` §4.3/§4.4
- **负责人**：test-manager
- **优先级**：P0
- **依赖**：T-002
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 数据模型约束 → 用例映射成文：唯一约束→重复插入、非空约束→空值、外键→孤儿数据，逐条列出（`PROJECT.md` §9 M1 门禁测试席视角）；
  2. 覆盖子账户隔离、敞口三分量一致性（`#17` 必测用例①②③④⑤）、限额三层全序（`#13`）；
  3. 明确 M1 不依赖 DM8 的「假 DAO」单测档（`RSK-03` 应对）；
  4. 用例均可给出唯一预期值。
- **产出**：`docs/TEST_PLAN/M1.md`
- **备注**：**不写代码**；用例设计属测试经理职责。

### T-023 编写 M1 单测与集成测试（JUnit 5）

- **需求追溯**：`NFR-TEST-1/3`, `NFR-MAINT-2`, `PROJECT.md` §10-8
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-010, T-012, T-013, T-014, T-015, T-016
- **估时**：32 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. JUnit 5 单测覆盖产品/现货/计划/权限/配置核心逻辑，**核心模块单测覆盖率 ≥ 80%**（`PROJECT.md` §10-8）；
  2. 集成测试在 DM8 上跑通建表→读写→查询；无 DM 时 `mvn test -Punit` 用假 DAO 可跑单测；
  3. 约束类用例（重复插入/空值/孤儿数据）与敞口三分量用例全部通过。
- **产出**：`java/src/test/**`、测试报告（**产出路径补登 2026-09-21**：落 `docs/TEST_RESULT.md` §`T-023` 节）
- **备注**：—

### T-024 执行 M1 端到端验收

- **需求追溯**：`PROJECT.md` §9 M1 验收, §10-34
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-023
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 创建两个子账户 → 分别配置品种与实货敞口 → 各自产出套保计划，全链路无断点；
  2. **验证子账户数据隔离**：A 用户登录看不到 B 的数据；审计岗登录可跨账户查看；
  3. 验证数据在 **DM8** 中正确落库（含子账户标识、敞口三分量、额度三层）；
  4. 出具 M1 验收报告，逐条对照 `PROJECT.md` §9 M1 验收方式。
- **产出**：`docs/TEST_PLAN/M1-report.md`
- **备注**：—

### T-025 执行 M1 代码与架构合规审查

- **需求追溯**：`NFR-MAINT-4/5/6`, `NFR-SEC-9`, `PROJECT.md` §10-7
- **负责人**：code-reviewer
- **优先级**：P0
- **依赖**：T-012, T-013, T-014, T-015, T-016, T-017, T-018, T-019, T-020, T-021
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. `E5`（`dao` 外无 SQL）与 `E6`（前端零构建链 + 无直连）扫描通过；
  2. 组件边界无越界：Java 无事前风控判定、无 CTP 报文解析、无直连 C++；
  3. 敏感字段零落盘复核（结构 + grep 双证）；
  4. 出具问题清单（问题/位置/建议/级别四要素），不改代码。
- **产出**：`docs/REVIEW.md`（M1 部分）
- **备注**：只读审查。

### T-026 RSK-01 protobuf 在 C++11 下空壳构通验证

- **需求追溯**：`ARCHITECTURE.md` §9 `RSK-01`, §7.1.5, `NFR-COMPAT-1/2`
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：无（M2 协议已 FROZEN，可直接开工）
- **估时**：8 人时
- **来源**：初版（技术负责人 M2 首周探路要求）
- **状态**：**DONE（附条件；判据 = 授权 + 独立验证）**（2026-09-21；原「已完成，待独立复核」〔证据档 = `#cpp` **自评探路实证**、**无第三方独立验证记录**〕**已由独立复核 + `T-030` 汇签承接** ⇒ 判据满足。**独立复核 = 通过**；**`T-030` 汇签结论 = 通过**〔**技术风险已排除，不得上推为「协议已验证」** —— `proto/hgcp.proto` 实测不存在，其协议级闭环待 `T-032`〕，见 `BOARD.md` §2 `DONE` 区与 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）（2026-09-20 探路通过：protobuf `3.21.12` 在 `-std=c++11 -Wall -Wextra -Werror` 下空壳构通成功）
- **验收标准**：
  1. 在 `-std=c++11 -Wall -Wextra -Werror` 下，用 `proto/hgcp.proto` 仅编译一个 **HGCP 空消息**并完成收发，构建成功；
  2. 首选 protobuf `3.21.12`，失败回退 `3.19.6`，回退链路在 CI 中可复现；
  3. 记录实际可用版本与编译参数；两侧 protobuf 大版本一致（`3.x`，构建时校验）；
  4. 若两版本皆不可用 → **提 `CH-xxx`（L3）** 申请 `protobuf-lite` 或降级 `3.17.3`，**不得静默改技术栈**。
  5. **可测判据（CI 复现口径）**：`HG_STAGE=all bash cpp/proto_smoke/ci/run_smoke.sh` → **`PASS=13  FAIL=0`（`RESULT: PASS`）**，退码 0；逐条断言：
     - **主路径**（`HG_STAGE=system`，实测 `PASS=10`；含 `A8`/`A9` 两条后加断言）：`A1` `compile_commands.json` 中 `-std=c++11 -Wall -Wextra -Werror` **实际生效**；`A1b` `__cplusplus == 201103`；`A2` 构建成功 **且** `ctest` **3/3**；`A3` 实际可用版本 = **`3.21.12`**；`A4` 构建期大版本 = **3**；`A5` `-Werror` 反证（故意使用 3.21 已弃用 API `ByteSize()` **必须编译失败**，且失败原因为 `deprecated`）；`A7` `ldd` 产物无任何 DB 客户端库；
     - **回退链路**（`HG_STAGE=fallback`，实测 `PASS=2`）：protobuf **`3.19.6`** 由 `FetchContent` 从源码构建成功且空消息**真机收发成功**（`A6`）、`ctest` 全绿（`A6b`）；
     - **降级链路**（`HG_STAGE=lite`，实测 `PASS=1`）：`protobuf-lite` 构通且 `ctest` 全绿（`A6c`）；
     - `HG_STAGE=all` 的回退段须可取到 `3.19.6` 源码（联网走 `GIT`，或预置 tarball `HG_PROTOBUF_TARBALL=<...>` 按内置 SHA256 校验）；**纯离线仅可跑主路径**（`HG_SKIP_FALLBACK=1`）。
  6. **供给策略（部署承接）**：protobuf **优先系统包 `3.21.12`**；`FetchContent`（**钉死 SHA256** + `SOURCE_SUBDIR cmake` + `CMAKE_POLICY_VERSION_MINIMUM=3.5`）作**离线回退**；`protobuf-lite` 为**降级链路**。三条链路**均已实测跑通**（对应上条 `A*` / `A6*`）。
- **产出**：`cpp/proto_smoke/`（空壳验证）+ 结论记录
- **备注**：**M2 首周探路任务，未过不得铺开 M2 业务开发**（`T-030` 汇签）。**已提前投递**（2026-09-20，工单 `T-026-task.md`）；**2026-09-20 探路通过 → `DONE`**（状态列见 §1，验收证据见 `BOARD.md` §2 `DONE` 区）；M2 门禁与「探路→汇签（`T-030`）」约束不变。
  **📌（2026-09-21 状态收口 · 只加不改）** 本任务现行状态 = **`DONE`（附条件；判据 = 授权 + 独立验证）** —— **独立复核通过 + `T-030` 汇签结论通过**；「附条件」= 保留限定语「**技术风险已排除，不得上推为「协议已验证」**（`proto/hgcp.proto` 实测不存在，协议级闭环待 `T-032`）」，**义务不减**。
  **📌（2026-09-21 `T-030` 汇签登记 · 只加不改）** `PASS` 计数订正：验收 5 原载 `PASS=11`（`A8`/`A9` 两条断言后加未回写）⇒ 本轮回写为 **`PASS=13`**（= 系统 10 + 回退 2 + 降级 1 三腿合计）；同条主路径明细原载 `PASS=8` ⇒ 一并订正为 `PASS=10`（含 `A8`/`A9`）。依据 = `docs/gates/M2-recon-signoff/SIGN-OFF.md` §3 条件 2。

### T-027 RSK-02 CTP 头文件 C++11 兼容验证

- **需求追溯**：`ARCHITECTURE.md` §9 `RSK-02`, `NFR-COMPAT-1/2`, `PROJECT.md` §5.1/§6
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：无
- **估时**：4 人时
- **来源**：初版（M2 首周探路要求）
- **状态**：**DONE（判据 = 授权 + 独立验证）**（2026-09-21；原「已完成，待独立复核」〔证据档 = `#cpp` **自评探路实证**、**无第三方独立验证记录**〕**已由独立复核 + `T-030` 汇签承接** ⇒ 判据满足。**独立复核 = 通过**；**`T-030` 汇签结论 = 通过**，见 `BOARD.md` §2 `DONE` 区与 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）（2026-09-20 探路通过：真实 CTP SDK `V1.7.7` 合并版头文件 `<ThostFtdcUserApi.h>` 在 C++11 下编译 + 链接通过）
- **验收标准**：
  1. 以最小程序 `#include <ThostFtdcUserApi.h>` 在 `-std=c++11 -Werror` 下编译通过；
  2. 记录 CTP SDK 版本与编译参数；
  3. 若冲突 → 提 `CH-xxx`（**L1/L2 级，牵动 `PROJECT.md` §6 硬约束**）上报用户，**不得自行放宽 C++11 边界**。
  4. **可测判据（CI 复现口径）**：`CTP_SDK_ROOT=<dir> bash cpp/ctp_smoke/ci/run_probe.sh` → **`PASS=11  FAIL=0`（`RESULT: PASS`）**，退码 0；逐条断言：
     - `B1` SDK 可得（记录路径 / 头文件 / md5）；`B2` `-std=c++11 -Wall -Wextra -Werror` 下编译通过（**CTP SDK include 必须以 SYSTEM 头引入**）；`B2b` `ctest` 全绿；`B3` `__cplusplus == 201103`；`B4` 链接**真实** SDK 动态库并取回 **SDK 自报版本 `V1.7.7`**（非桩、非等价头文件）；`B7` `ldd` 产物无任何 DB 客户端库；`B8` C++11 边界**未被放宽**（编译参数仅 `-std=c++11`）；
     - **`B5` 反证判据**：`-DHG_CTP_INCLUDE_AS_SYSTEM=OFF` **必须编译失败**（SPI 基类 `virtual void OnXxx(...) {};` 的**参数具名但未使用** → `-Werror=unused-parameter`）；并注明此失败**与 C++ 标准无关**（`make_unique` / `optional` / `string_view` / `if constexpr` / 结构化绑定 **全库 0 命中**），**不得**据此放宽 C++11 边界；
     - **签名级证据（编译期绑定，签名不符即编译失败）**：`override` 派生 SPI + 成员函数指针绑定 `ReqUserLogin` / `ReqOrderInsert` / `ReqOrderAction` / `SubscribeMarketData`（另含 `SubscribePrivateTopic` / `RegisterSpi`）；`static_assert` 校验**抽象多态**接口类（`is_abstract` / `is_polymorphic`）；
     - `B6`：`<ThostFtdcTraderApi.h>` 在合并版 SDK 上**不可得**（属**命名口径差异**，**非 C++11 冲突**）。
  5. **施工口径（延续至 `T-031` / `T-033`）**：CTP SDK include 目录**一律以 SYSTEM 头引入**（`-isystem` / CMake `target_include_directories(... SYSTEM ...)`），作用域窄于 `-Wno-unused-parameter`；M2 业务代码**一律用 `ByteSizeLong()`**。
  6. **残余风险（部署复测）**：实盘柜台 SDK 版本可能与本次探路（`V1.7.7`）不同 → 部署前按 **`NFR-DEPLOY-5`** 记录实测版本并**重跑 `run_probe.sh`**（约 1 分钟；探路工程已支持 `CTP_SDK_ROOT` 参数化）；**承接归 `T-052` 部署清单**。
- **产出**：`cpp/ctp_smoke/` + 结论记录
- **备注**：**M2 首周探路任务**。CTPmini 同栈已跑通，概率低-中。**已提前投递**（2026-09-20，工单 `T-027-task.md`）；**2026-09-20 探路通过 → `DONE`**（状态列见 §1，验收证据见 `BOARD.md` §2 `DONE` 区）；M2 门禁与「探路→汇签（`T-030`）」约束不变。
  **📌（2026-09-21 状态收口 · 只加不改）** 本任务现行状态 = **`DONE`（判据 = 授权 + 独立验证）** —— **独立复核通过 + `T-030` 汇签结论通过**。
  **📌（2026-09-21 `T-030` 汇签登记 · 只加不改）** `PASS` 计数订正：验收 4 原载 `PASS=9`（`B7b`/`B7c` 两条断言后加未回写）⇒ 本轮回写为 **`PASS=11`**。依据 = `docs/gates/M2-recon-signoff/SIGN-OFF.md` §3 条件 2。

### T-028 RSK-06 Java 端 WebSocket 选型与实现

- **需求追溯**：`ARCHITECTURE.md` §9 `RSK-06`, §4.1.2, `NFR-PERF-5`, `PROJECT.md` §5.1/§11 `CH-006`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：无
- **估时**：16 人时
- **来源**：初版（M2 首周探路要求）；`CH-006` 口径收紧（**WebSocket 确定在 Java 端实现**，不再是「自实现是否可行」的探路）
- **状态**：**DONE（独立复核通过）**（判据 = **授权 + 独立验证**；见「备注」）
- **验收标准**：
  1. **完成 Java 端 WebSocket 服务端实现**（行情推送 `/ws/quote`），浏览器可连通；**连接路径 / 鉴权 / 订阅消息 / 心跳重连**四项对外约定须写入冻结清单（与 `T-003`/`T-004` 一致）；
  2. **给出轻量库选型论证（四要素）**：**用途 / 版本 / 替代方案 / 不引入的代价**，落于 `ARCHITECTURE.md` §7.1.11（由技术负责人定稿，本任务提供论证输入）；替代方案须含「JDK 内置 `HttpServer` 自实现 RFC 6455」一档；
  3. **传递依赖举证**：列出所选库的**全部传递依赖**（名称/版本/许可/是否含原生依赖），并说明为何无法以 JDK 标准库替代（`CH-006`「尽量降低第三方库依赖」）；
  4. 验证 P95 推送延迟满足行情实时性口径（为 `NFR-PERF-5` 提供依据）；
  5. 若所选方案（含自实现）不达标 → 回退 1 s 长轮询并**提 `CH-xxx` 降级声明 `NFR-PERF-5`**（P95 ≤ 200 ms 不再保证）。
- **产出**：`java/src/main/java/com/hedgeguard/web/ws/**`（WebSocket 服务端）+ **选型论证与传递依赖清单**（结论记录）（**路径订正 2026-09-21**：原写 `java/ws/`，实测该目录**不存在**；实物在 `web/ws/`，依 `CONTRACTS` 包结构 + 项目经理实测）
- **备注**：**M2 首周探路任务，未过不得铺开 M2 业务开发**（`T-030` 汇签）。**已由用户裁决（`CH-006`）确定 WebSocket 在 Java 端实现**，本任务不再是可行性探路，而是**选型 + 实现 + 依赖举证**；`T-029`（websocketpp 落点）已随之 `REJECTED`。
  **范围边界（与 `T-040` 显式分工）**：本任务 = **选型 + 最小可行实现**（连通性 + 轻量库四要素论证 + 传递依赖举证 + P95 实测），**不承担生产级要求**；**生产级实现**（连接管理 / 订阅管理 / 推送 / 鉴权 / 心跳重连 / 背压或限流）归 **`T-040`**（`#java`，依赖本任务与 `T-042`）。
  **（2026-09-21 转「已完成，待独立复核」· 只加不改）**：产出 `mvn -B -o clean test` = **`443/0/0/0`**（基线 435 ⇒ **+8**；新增 `WsAcceptanceTest` 8 例 + `java/WS_T028_ACCEPTANCE.md`）；`src/main` **零改动**。**`E1`–`E13` 判据 = 满足 11 / 措辞待裁 1 / 不可判定 1**；**`R-1` 已裁定**（`E3` 措辞与冻结契约 §4.3 冲突 ⇒ **以冻结契约为准、修订 `E3` 措辞、不改实现**；已派技术席落笔）；**`R-2` 转派登记**（`E9` 不可判定，落点 = **`T-040`** / HGCP 行情消费层，须在彼层注入含行情帧的补数批次做断言 ⇒ **已在 `T-040` 备注登记该承接义务**）；`R-3`（`E2` 稀疏发布时延受 200ms 节拍支配）/ `R-4`（`WsServer` **未接线**、`CFG-32 port` 无实现值 ⇒ 归 `A-2` / `T-040`）**如实登记**。**判据 = 授权 + 独立验证** ⇒ 独立复核完成前**不置 `DONE`**。
  **（2026-09-21 转 `DONE` · 只加不改）**：独立复核 **`te-verify-t028`** 判 **有条件通过、不阻断 `T-030`** —— **`E1`–`E13`：满足 12 / `E9` 合法不可判定 / `E3` 措辞订正后消解**；`E9` 系**合法不可判定**（其独立 `grep` 全仓 `EVT_MARKET_QUOTE|IS_RESUME_DATA` = **0**、`TradingBridge` 无行情回调 ⇒ **非「有而不用」**）；`E3` 订正后**与冻结契约 §4.3 硬一致且仍有可失败负例**；**独立复现 `443/0/0/0` 两次**；**`src/main` 零改动经 git 证明**（`git diff --stat 59b6f70 -- java/src/main` = 空 + `git show 59b6f70:…WsServer.java` md5 == 工作树）；反退化自设 4 条红 + 1 条**存活**。**两项非阻塞承接（均转 `T-040`）**：`(a)` `E6` **补「有界队列丢最旧时保留的是最新帧」断言**（其自设变异 `pollFirst→pollLast` **存活** ⇒ 实现正确但**测试未覆盖该属性**）；`(b)` `E2` **显式声明工况**（实测**稀疏 max=193 ms、相位最坏 204 ms**，而判据文字指向的正是该工况）。

### T-029 RSK-07 websocketpp 落点确认（**已取消 · REJECTED**）

- **需求追溯**：`ARCHITECTURE.md` §9 `RSK-07`, §7.1.2, §7.1.8 —— **已因 `CH-006` 失效**
- **负责人**：backend-engineer#cpp
- **优先级**：P0（原）
- **依赖**：—（原 `T-028`，已解除）
- **估时**：0 人时（原 8 人时，**已从总量扣除**）
- **来源**：`CH-006`（原「初版（M2 首周探路要求）」）
- **状态**：**REJECTED**（2026-09-20，取消；**保留编号与留痕，任务总数仍 89**）
- **取消原因**：用户 **2026-09-20 技术栈裁决**已在 `PROJECT.md` §5.1 落地为 **`CH-006`**——**C++ 侧仅保留 CTP SDK + protobuf，移除 `websocketpp`（及其传递依赖 `Boost::system`）**；**HTTP / WebSocket 全部在 Java 端实现**。故 `RSK-07`（「websocketpp 若最终不用于主通道」）**风险前提消失**，本任务无标的物、无验收对象。
- **原验收标准（仅留痕，不再执行）**：
  1. ~~确认 `websocketpp`（+ Boost::system）可用于通道 B 行情流并给出构建/运行证据~~；
  2. ~~若 `T-028` 失败并回退长轮询 → `websocketpp` 无落点，须提 `CH-xxx` 上报~~；
  3. ~~记录结论与依赖清单~~。
- **产出**：（无；原 `cpp/wspp_smoke/` 不再产出）
- **备注**：**M2 探路项由四项减为三项**（`T-026` / `T-027` / `T-028`），`T-030` 汇签范围同步收紧。**不得静默删除本行**（`REJECTED` 留痕）；原 C++ 侧行情 WebSocket 服务端任务 `T-040` **已按 `CH-006` 完成定界改派**——**改派 `#java`**，改为「**实现 Java 端行情 WebSocket 服务端（生产级）**」，依赖 `T-028, T-042`（见 `T-040` 详情与 `BOARD.md` §4 `R-17`）。

### T-030 汇签 M2 探路结论并下达铺开许可

- **需求追溯**：`ARCHITECTURE.md` §9（**三项探路：`RSK-01`/`RSK-02`/`RSK-06`**）, §3.13
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：**T-026, T-027, T-028**（原含 `T-029`，因 `CH-006` 取消而移除）
- **估时**：4 人时
- **来源**：初版（技术负责人加设门禁）
- **状态**：**DONE**（2026-09-21 汇签完成；许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）
- **验收标准**：
  1. 对 **3 项探路**（**T-026 / T-027 / T-028**）结论逐项给出「通过/不通过」，并记录所用版本与编译参数；
  2. 若任一项不通过，明确走 `CH-xxx` 或降级，**M2 业务开发任务（T-031 起）保持 `BLOCKED`**；
  3. 全部通过后以书面许可（本任务结论）解锁 T-031 及后续 M2 开发任务。
- **产出**：`docs/TASKS.md` 本任务备注 + `docs/BOARD.md` 阶段状态更新
- **备注**：**这是 M2 探路门禁的闭环点**；未下达许可前 T-031~T-060 不得开工。探路项由四项减为**三项**（`T-029` `REJECTED`，见本文 §1）。
  **📌（2026-09-21 转 `DONE` · 只加不改）** 汇签记录 = `docs/gates/M2-recon-signoff/SIGN-OFF.md`（`081f37a2…` / 254 行）；逐项结论 `T-027` 通过 / **`T-026` 通过（技术风险已排除，不得上推为「协议已验证」）** / `T-028` 通过（`E9` 合法不可判定）；两项条件见该记录 §3；**许可令已下**（`T-031`~`T-060` 解除阻塞）。

### T-031 搭建 C++ 交易组件工程骨架与构建 + arch-check E1~E4

- **需求追溯**：`NFR-COMPAT-1/2/7`, `NFR-MAINT-4/6`, `ARCHITECTURE.md` §3.0.2/§3.13
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-030
- **估时**：24 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. CMake ≥ 3.16 + FetchContent 工程建立，`CMAKE_CXX_STANDARD 11`，`CTP_FOUND`/`CTP_USE_FETCH_DEPS` 双轨构建可用；`ctest` 可跑；
  2. 按 `ARCHITECTURE.md` §3.1~§3.6 建立六模块目录（`ctp_adapter/order_engine/risk_engine/risk_state/flow_store/hgcp`）；
  3. **判据脚本不重复交付**（`E1`/`E2`/`E3`/`E4` 由 `T-090`~`T-092` 唯一交付，`E5`/`E6` 由既有脚本交付）；本任务交付 **`tools/arch-check/arch-check.sh` 聚合入口**（按 `ARCHITECTURE.md` §3.13：**任一脚本非 0 ⇒ 整体非 0**）**+ `tools/arch-check/README.md`**（含各脚本用法、退出码语义、**各脚本自测三条的可复跑记录**），并**纳入 `ctest` / `mvn verify` 前置步骤**；
  4. **不链接任何 DB 客户端库**，`E1` 通过（`PROJECT.md` §10-29）。
- **产出**：`cpp/CMakeLists.txt`, `cpp/src/**`, `tools/arch-check/{arch-check.sh,README.md}`
- **备注**：`E5`/`E6` 归 T-060 一并核查。 **📌 聚合入口义务补承载（编排者 2026-09-21 裁定）**：原验收标准 3 / 产出列的**判据脚本交付义务已消解至 `T-090`~`T-092`**，本任务改为**交付聚合入口 `arch-check.sh` 与 `README.md` + 接线（`ctest` / `mvn verify` 前置）**（落实 `ARCHITECTURE.md` §3.13 产出清单「6 脚本 + 1 聚合入口 `arch-check.sh` + `README.md`」）。**依据（项目经理实测）**：`tools/arch-check/` 现仅 **7 个文件**（6 判据脚本 + `strip_java_comments.awk`），**无 `README.md`、无 `arch-check.sh`**；全仓**无 `Makefile`／CI 配置**；而 `docs/TASKS.md` 中 `arch-check.sh` **仅被引用、无任何任务承载**（`T-060` 验收标准 1、`T-090` 验收标准 4/5）⇒ 判据在引用一个**不存在、且无任务交付**的制品（**追溯链断裂**）。**估时维持 24 人时**（「接入构建与 CI」本已在需求追溯内）。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-032 实现 HGCP 帧编解码与元协议

- **需求追溯**：`FR-TRADE-14`, `FR-CONFIG-6/9`, `NFR-SEC-3`, `NFR-REL-2`, `ARCHITECTURE.md` §4.2.1/§4.2.2/§4.2.6
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-031
- **估时**：32 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. 帧格式正确：32 字节帧头（`MAGIC="HGCP"` / `VER=0x0100` / `TYPE` / `FLAGS` / `SEQ` / `MSG_ID` / `LEN`）+ 尾部 `CRC32`，大端，最大帧 1 MiB；
  2. 元协议 9 种消息（`MSG_HELLO`/`HELLO_ACK`/`AUTH_FAIL`/`HEARTBEAT`/`HEARTBEAT_ACK`/`ACK`/`ERROR`/`RESUME_REQ`/`RESUME_DATA`）编解码可用；
  3. 鉴权：持有 `auth_token` 的 **SHA-256 摘要**并做**常量时间比较**；失败 `MSG_AUTH_FAIL` + 立即关连接 + 告警留痕；**禁止**日志打印令牌；
  4. 心跳 1000 ms、连续 3 次未收 ACK 判定断连（**≤ 3 s ≤ 5 s**，`NFR-REL-2`）；重连指数退避 500ms→8s 上限、无限重试；补数缓冲 65536 帧、单批 ≤ 500 帧；
  5. 仅绑定 `127.0.0.1`，非回环值拒绝启动；通道 A 单连接只允许 1 条业务连接，重复连接拒 `0x0302`。
  6. **（2026-09-21 由 `T-030` 汇签登记）** 真实 schema `proto/hgcp.proto` 落地后，须以 **`-std=c++11 -Wall -Wextra -Werror`** 重跑 `cpp/proto_smoke`（**指向真实 schema**）并附证据，**方可确认 `RSK-01` 闭环**。
- **产出**：`cpp/src/hgcp/**`, `proto/hgcp.proto`（与 Java 侧同源）
- **备注**：**跨栈依赖**：本任务产出与 Java 侧 T-042 用**同一份 `.proto`** 生成代码，结构上不可能字段漂移（M2 门禁的工程价值）。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-033 实现 CTP 适配层 ctp_adapter

- **需求追溯**：`FR-TRADE-2/4/5/6/12`, `FR-FUND-5/6/8`, `NFR-COMPAT-6`, `ARCHITECTURE.md` §3.1
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-031
- **估时**：24 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. 封装 CTP 合并版 `ThostFtdc UserApi`（交易 + 行情 + 资金划转），登录/登出/连接状态可用；
  2. `sendOrder`/`cancelOrder`/`subscribeMarket`/`reqFundTransfer` 接口可用，异步 SPI 回调**只做序列化并投递队列**；
  3. 覆盖**期货与期权**（`PROJECT.md` §10-10）；
  4. 按异步回调模型处理，**不假设同步返回**；CTP 未连接时 `CMD_CONFIRM_ORDER` 回 `0x2004` 且指令退回不静默丢弃。
- **产出**：`cpp/src/ctp_adapter/**`
- **备注**：**跨栈依赖**：成交/委托/资金回执经 T-039 以 `EVT_*` 推给 Java。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-034 实现风控内存状态 risk_state

- **需求追溯**：`FR-RISK-9/10`, `NFR-PERF-1`, `ARCHITECTURE.md` §3.4/§4.4
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-031
- **估时**：24 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. 常驻内存数据集齐备：品种白名单、三层额度、持仓上限、敞口（含三分量 + `snapshot_time`）、权限、阈值、年度规模因子；
  2. 采用**不可变快照 + 原子指针替换（RCU 风格）**，`snapshot()` 原子读、`rebuild()` 构建新快照后原子替换；
  3. 交易路径**零数据库 I/O、零阻塞调用、零锁竞争**（`E4` 无锁扫描通过）；
  4. 快照替换期间的保守方向符合 `ARCHITECTURE.md` §9 `RSK-04`（上限调低更易拒，不会超限放行）。
- **产出**：`cpp/src/risk_state/**`
- **备注**：本模块是「内存为交易路径权威源」的载体。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-035 实现事前风控引擎 risk_engine（8 规则 + 三层限额 + 双层校验）

- **需求追溯**：`FR-RISK-1~8`, `FR-TRADE-3`, `R-RISK-1~8`, `NFR-PERF-1`, `CFG-16`, `ARCHITECTURE.md` §3.3
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-034
- **估时**：32 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. 8 条规则全内存判定：白名单 / **三层限额** / 持仓上限（平仓不受限）/ 规模红线（90%·80%，基数=年度现货敞口累计实物量）/ 时点净持仓 / 期限红线（三级处置）/ 权限与授权 / 价格偏离（昨结算价基准，2% 告警 5% 阻断，市价单不适用）；
  2. **三层限额全序写死**：层② > 层① > 层③（层③不参与「最细命中」选择）；同层并列取**占用率最高者**，再并列按 `id` 升序；`EVT_PRE_TRADE_REJECT.entries[]` 携带**全部命中项**；
  3. **双层校验**：子账户维度 + 总账户维度均生效；`RiskEngine::check` **纯函数式**（const 快照 + 入参，无副作用）；
  4. **P99 ≤ 5 ms**（§4.4.4 预算分解）；构造超限指令被拒且**未发往 CTP**（`PROJECT.md` §10-24）。
- **产出**：`cpp/src/risk_engine/**`
- **备注**：非法判定值一律拒绝；平仓/减仓方向按规则豁免。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-036 实现指令生命周期管理 order_engine

- **需求追溯**：`FR-TRADE-1/2/6/13`, `R-TRADE-1/2/5/6/7/12`, `ARCHITECTURE.md` §3.2/§6.1
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-033, T-035
- **估时**：32 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. 状态机：受理 → 事前风控 → 置「待确认」→ 人工确认 → 落流水 → 发 CTP → 回报处理 → 终态；支持超时失效、撤单、部分成交；
  2. **人工确认**：确认人必须与提交人一致，否则拒 `0x0305`（防代确认）；确认时**再跑一次风控**（防确认期状态变化）；校验 `confirm_token`（防重放）；
  3. 确认超时 60 s（`CFG-28`）→ 指令置失效、**释放冻结额度**、发 `EVT_ORDER_EXPIRED`、**不发 CTP**；
  4. 每笔指令携带 `trade_plan_id`/`exposure_id`/`sub_account_id`，缺失（`trade_plan_id=0`/`sub_account_id=0`）→ 拒绝（`R-TRADE-1`）；期权多腿经 `strategy_id`+`leg_no` 关联同一计划（`PROJECT.md` §10-10）。
- **产出**：`cpp/src/order_engine/**`
- **备注**：**跨栈依赖**：入口 `CMD_PLACE_ORDER`(0x0101)/`CMD_CONFIRM_ORDER`(0x0102)/`CMD_CANCEL_ORDER`(0x0103)；出口 `EVT_ORDER_ACCEPTED`(0x0201)/`EVT_PRE_TRADE_REJECT`(0x0202)/`EVT_ORDER_REPORT`(0x0203)/`EVT_ORDER_CANCEL_RESULT`(0x0206)/`EVT_ORDER_EXPIRED`(0x0207)。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-037 实现指令流水与重放 flow_store

- **需求追溯**：`FR-TRADE-15/16`, `NFR-REL-1`, `CFG-29`, `ARCHITECTURE.md` §3.5/§4.5
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-031
- **估时**：24 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. append-only 流水落盘（`hg.flow.dir` 默认 `./data/flow`，`fsync_mode=BATCH`，保留 90 天）；
  2. 启动重放；状态机 `NOT_READY` / `REPLAYING` / `READY` 对外可见（`EVT_REPLAY_PROGRESS`/`EVT_SYSTEM_STATE`）；
  3. 重放期与 CTP 侧状态对齐；**非 `READY` 时对交易指令回 `0x0401`**（系统未就绪）→ 不产生基于陈旧状态的错误判定；
  4. 在途指令（已发 CTP、未收回报）**不释放冻结额度**，标记「状态未知」+ 告警。
- **产出**：`cpp/src/flow_store/**`
- **备注**：**跨栈依赖**：`EVT_REPLAY_PROGRESS`(0x0209)/`EVT_SYSTEM_STATE`(0x020A) 告知 Java 就绪/参数版本；Java 侧 `TradingBridge.isReady()` 依赖之。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-038 实现控制指令消费与参数生效

- **需求追溯**：`FR-RISK-10`, `FR-CONFIG-6`, `ARCHITECTURE.md` §4.2.3B/§6.3
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-032, T-034
- **估时**：16 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. 17 种控制指令可消费并生效（`CMD_PLACE_ORDER`/`CMD_CONFIRM_ORDER`/`CMD_CANCEL_ORDER`/`CMD_LOAD_RISK_PARAMS`/`CMD_UPDATE_*`/`CMD_SET_TRADING_SUSPEND`/`CMD_SET_READY_STATE`/`CMD_RELOAD`/`CMD_TRIGGER_REPLAY`/`CMD_QUERY_STATUS`/`CMD_FUND_TRANSFER`/`CMD_QUERY_FUND_RESULT`）；
  2. `param_version` 单调校验：**仅接受 `param_version > current`**，否则回 `0x0403` 并丢弃（后发不会先至覆盖新值）；
  3. `CMD_LOAD_RISK_PARAMS` 支持分批（`batch_size` 默认 2000，`batch_index`/`batch_total`），每批回 `MSG_ACK`，完成发 `EVT_SYSTEM_STATE(READY, param_version)`；
  4. `CMD_UPDATE_RISK_THRESHOLD` 默认值不得低于 `INDEX.md` §9.1 合规口径（`R-CONFIG-8`，C++ 侧拒绝低于下限）。
- **产出**：`cpp/src/hgcp/command_handler.cc`, `cpp/src/risk_state/rebuild.cc`
- **备注**：**跨栈依赖**：与 T-011（Java 侧编排）配对，构成「下发→回执→留痕」闭环；`CMD_SET_TRADING_SUSPEND` 与 T-050 事中风控配对。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-039 实现事件流产出与保序补数

- **需求追溯**：`ARCHITECTURE.md` §4.2.3C/§4.2.6, `NFR-REL-2/5`, `NFR-PERF-6`
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-032, T-036
- **估时**：24 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. 14 种事件可产出，其中**须落库的 10 种**（`0x0202`,`0x0203`,`0x0204`,`0x0205`,`0x0206`,`0x0207`,`0x0208`,`0x0209`,`0x020A`,`0x020B`）齐备；
  2. 通道 A **所有业务帧共用一个单调递增 `SEQ`**（含事件帧与控制帧），不可跳号；跳号触发补数；
  3. 断连时 `eventQueue` 切保留模式（容量 16384，满则阻塞 T4 并告警）→ **不丢**；行情事件 **`EVT_MARKET_QUOTE`**（同一 HGCP 通道的**可丢例外**）断连直接丢弃、不保留（原「通道 B」，`CH-006` 后并入单一 HGCP 通道）；
  4. 补数：`MSG_RESUME_REQ`/`MSG_RESUME_DATA`（`is_last` 标识、单批 ≤ 500 帧）；缺口超缓冲 → 回 `MSG_ERROR{0x0306}` + 触发全量状态重建，期间拒新指令 `0x0401`；
  5. 压力下（连续 1000 笔）Java 侧接收数量与顺序与发出完全一致（`PROJECT.md` §10-31）。
- **产出**：`cpp/src/hgcp/event_emitter.cc`
- **备注**：**跨栈依赖**：事件被 T-042/T-046/T-047/T-049/T-050 消费（落库 + 镜像状态）。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-040 实现 Java 端行情 WebSocket 服务端（**生产级**）

- **需求追溯**：`FR-TRADE-12`, `R-TRADE-11`, `NFR-PERF-5`, `EVT_MARKET_QUOTE`(0x020C), `ARCHITECTURE.md` §4.1.2/§6.13/§7.1.11（v1.4 `CH-006` 定界）
- **负责人**：**backend-engineer#java**（`CH-006` 定界改派；原 `backend-engineer#cpp`）
- **优先级**：P1
- **依赖**：**T-028, T-042**（原 `T-029`〔已 `REJECTED`，悬空依赖消除〕、`T-031`〔C++ 骨架，已随改派移除〕）
- **估时**：16 人时
- **来源**：初版；`CH-006` 定界改派（组件边界见 `ARCHITECTURE.md` §4.1.2/§7.1.2，v1.4）
- **状态**：**BLOCKED**
- **验收标准**：
  1. 在 `T-028` 的选型与最小可行实现之上，交付**生产级** Java WebSocket 服务端（行情端点 `/ws/quote`），浏览器可连通，**长稳运行 ≥ 1 h 无连接/内存泄漏**（连接数与堆占用有观测证据）；
  2. **连接管理**：连接建立/关闭生命周期完整；**最大连接数受控**（`CFG-32` `hg.web.ws.max_connections`，超限拒绝并留痕）；**慢客户端隔离**——每连接有界发送队列，满则丢弃旧行情并计数，**不得阻塞 Java 事件消费线程（J3）**，即**不背压** `EVT_MARKET_QUOTE` 消费路径；
  3. **订阅管理**：订阅/退订消息格式与 `T-003`/`T-004` 冻结清单**逐字段一致**；仅向已订阅连接推送其订阅合约的行情，**覆盖式**（每合约只保最新一帧）；
  4. **鉴权**：握手期强制鉴权，沿用 `ARCHITECTURE.md` §4.1.1 的 `HGSESSION` Cookie（HttpOnly）+ 子账户上下文；未鉴权、越权订阅（跨子账户不可见数据）**拒绝并留痕**；服务**仅监听 `127.0.0.1`**（`NFR-SEC-3`），非回环值拒绝启动；
  5. **心跳与重连**：服务端心跳间隔（`CFG-32` `hg.web.ws.heartbeat_ms` ≥ 1000 ms）与「客户端断线重连后**重新订阅**」语义与冻结清单一致；断连即清理订阅与连接资源；
  6. **推送**：行情源为 `T-042` 消费的 `EVT_MARKET_QUOTE`（通道 A 事件流，本地 TCP 回环）；推送**覆盖式、允许丢帧、不补数、无回执**，前端按 `§6.13` 标注数据时间；
  7. **背压/限流**：单连接与全局推送速率有上限（节流 ≤ 5 条/秒/合约），丢弃与拒绝计数**可观测**（日志/指标）；**交易与回报路径不受影响**（行情不得背压交易路径，`R-8`/`RSK-11`）；
  8. 全部对外约定（**连接路径 / 鉴权 / 订阅消息 / 心跳重连 / 端口与连接上限**）纳入 `T-003`/`T-004` 已冻结清单；若需变更，须走 `CH-xxx`，**不得单侧改动**。
- **产出**：`java/src/main/java/com/hedgeguard/web/ws/**`（生产级 WebSocket 服务端）+ 长稳与限流运行证据（**路径订正 2026-09-21**：原写 `java/ws/**`，实测该目录**不存在**；实物在 `web/ws/`）
- **备注**：**定界（`CH-006` / `ARCHITECTURE.md` v1.4）** —— 原「**行情通道 B**（C++ 侧 `websocketpp`）」**已废弃**：行情改由 **CTP → C++（仅事件源）→ HGCP 事件流 `EVT_MARKET_QUOTE`（本地 TCP 回环）→ Java →（Java 端 WebSocket）→ 前端**，**C++ 组件不提供 HTTP / WebSocket**（§4.2.1/§3.6/§7.1.2）。本任务即**该 Java 侧 WebSocket 服务端**；组件边界**以架构为准**，任务侧不得自行定义。
  **范围边界（与 `T-028` 显式分工）**：`T-028` = **选型 + 最小可行实现**（连通性 + 四要素论证 + 传递依赖举证 + P95 实测）；**本任务 = 生产级实现**（连接管理 / 订阅管理 / 推送 / 鉴权 / 心跳重连 / 背压或限流）。**两者不重叠**：`T-028` 不承担生产级要求，本任务不重复选型论证。
  **跨栈依赖**：上游为 `T-042`（Java 桥接层消费 `EVT_MARKET_QUOTE`）；下游为前端 `T-053`（`/ws/quote`）。
  **（2026-09-21 承接义务登记 · 只加不改）**：`T-028` 判据 **`E9`（不可判定）** 的**落点 = 本任务** —— `T-028` 的 `E9` 需在**含行情帧的补数批次**（`IS_RESUME_DATA` 中的 `EVT_MARKET_QUOTE` **接收侧不消费**，见 `CH-007` / `ARCHITECTURE.md` §4.2.6）下做断言，属 **HGCP 行情消费层**，故**由本任务接管该断言义务**（在 `T-042` 消费 `EVT_MARKET_QUOTE` 之后、本服务的推送路径上补齐）。**依据**：`T-028` 交付的 `E1`–`E13` 判据集（`java/WS_T028_ACCEPTANCE.md`）中 `E9` 判定「不可判定」并转派（见 `T-028` 备注）。
  **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-042`（`T-028` 已 `DONE`〔独立复核通过〕）。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-042`。
  **（2026-09-21 承接，来自 `T-028` 独立复核 · 只加不改）** 三项义务：① **`E9` 落点**（HGCP 行情事件消费层，须注入含行情帧的补数批次做断言）；② **`E6` 补「有界队列丢最旧时保留的是最新帧」断言**（现有 `quoteDropsOldestWhenFull` 仅断言计数 ⇒ 改坏成 `pollLast` 的变异会存活）；③ **`E2` 证据显式声明工况**（稀疏发布下时延受 `QUOTE_TICK_MILLIS=200` 节拍支配、相位最坏 ≈204 ms）。另：`CFG-32` 的 `hg.web.ws.port` **默认值 / 接线**亦归本任务或架构 `A-2`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-042` 阻塞 ⇒ 维持 `BLOCKED`。

### T-041 实现崩溃恢复集成（在途指令不释放冻结额度）

- **需求追溯**：`PROJECT.md` §10-27, `FR-TRADE-15/16`, `ARCHITECTURE.md` §9 `RSK-10`/§6.2
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-036, T-037
- **估时**：24 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. 模拟「已发 CTP 未收回报」时强杀进程 → 重启重放 → 额度与持仓**无重复计入、无遗漏**（`PROJECT.md` §10-27）；
  2. CTP 查询不可用/超时时：保守记为「状态未知」、**不释放冻结额度**、告警、要求人工介入；
  3. 提供「人工核验并释放」管理入口（**双人复核 + 留痕**）；
  4. 该场景下系统**继续拒绝新指令**，直到人工闭环。
- **产出**：`cpp/src/flow_store/recovery.cc`, `cpp/src/order_engine/recovery_hook.cc`
- **备注**：**跨栈依赖**：`EVT_REPLAY_PROGRESS` 通知 Java 重放进度与结果。 **📌（2026-09-21 台账对齐）阻塞原因 = M2 首周探路门禁未过（`T-028` 未完成）；解除人 = `T-030`（技术负责人汇签）；依 `T-030` 验收原文『任一项不通过 → `T-031` 起保持 `BLOCKED`』。**
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-042 实现组件桥接层 TradingBridge（HGCP 客户端）

- **需求追溯**：`FR-TRADE-14`, `FR-RISK-9~12`, `R-TRADE-3/4`, `NFR-REL-2/5`, `ARCHITECTURE.md` §3.10
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-030
- **估时**：32 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. HGCP TCP 客户端：连接/鉴权/心跳/重连/补数/protobuf 编解码/命令回执关联全实现；用**同一份 `proto/hgcp.proto`** 与 C++ 侧同源生成；
  2. 接口就位：`placeOrder`/`confirmOrder`/`cancelOrder`/`dispatch`/`isReady`/`replayProgress`/`mirror`；**不提供 `preCheck`**（判定只在 C++）；
  3. 事件流**按序消费 + 幂等去重 + 镜像状态构建**：按 `business_id`（`trade_id`/`order_sys_id`/`receipt_id`）+ `event_seq` 建内存 LRU，并落库唯一约束双保险；
  4. **事件驱动、不轮询**：不主动查库、不主动查 C++；
  5. 命令回执超时 3000 ms（`CFG-28`）标记未生效；断连后两侧状态最终一致（`PROJECT.md` §10-32）。
- **产出**：`java/bridge/{TradingBridge.java,HgcpClient.java,MirrorState.java,IdempotencyCache.java}`
- **备注**：**跨栈依赖**：与 T-032/T-038/T-039 配对，是 C++↔Java 的唯一通道；协议变更须走 `CH-xxx` 并重跑 T-057。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」；本任务依赖 `T-030`）。**解除人** = `T-030`（技术负责人汇签）。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-043 交付 TRADE 模块 9 张表 DDL 与 DAO

- **需求追溯**：`FR-TRADE-1~18`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-004
- **估时**：16 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. `TB-TRADE-1~9` 交付，DM8 建表成功，与 `DATA_MODEL.md` 逐列一致；
  2. `t_trade_fill.trade_id` 建唯一约束（幂等键）；`t_pre_trade_reject` 含 `limit_layer` 与敞口快照列；
  3. 交易指令/委托/成交/持仓/拒绝记录均 `NOT NULL` 携带子账户标识（§4.6.3 ① 层，TRADE 8/9）；
  4. 成交/委托/流水表含事件幂等键所需唯一约束。
  5. **`java/dao/**` 下本模块每个 DAO 接口均有对应 `Dm*` 实现类；若某个接口本期不需实现，须在 `TASKS.md` 显式登记「延后实现 + 原因 + 承接任务」（不得沉默略过）。**
- **产出**：`java/dao/impl/DmTrade*Dao.java`, `docs/DEPLOY/schema/m2_trade.sql`
- **备注**：— **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」；本任务依赖 `T-004`，**已完成**、非阻塞源）。**解除人** = `T-030`（技术负责人汇签）。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-044 交付 SETTLE 模块 16 张表 DDL 与 DAO

- **需求追溯**：`FR-SETTLE-1~17`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：24 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. `TB-SETTLE-1~16` 交付（含 `TB-SETTLE-15` 核对签署、`TB-SETTLE-16` 加总差异候选归属），DM8 建表成功；
  2. 结算持仓/资金/盈亏表按子账户可加总（`NOT NULL` 子账户标识）；`t_check_signature` 唯一约束 `uk_check_signature_task_role` 支持「不可代签」；
  3. `t_agg_diff_candidate`（候选态）与 `t_reconcile_difference.sub_account_id`（定位态）字段齐备，支撑 `CFG-11` 阻断范围机判（`Q-11`）。
  4. **`java/dao/**` 下本模块每个 DAO 接口均有对应 `Dm*` 实现类；若某个接口本期不需实现，须在 `TASKS.md` 显式登记「延后实现 + 原因 + 承接任务」（不得沉默略过）。**
- **产出**：`java/dao/impl/DmSettle*Dao.java`, `docs/DEPLOY/schema/m2_settle.sql`
- **备注**：SETTLE 共享台账边界允许跨模块写表（`R-SETTLE-8`）。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」；本任务依赖 `T-004`，**已完成**、非阻塞源）。**解除人** = `T-030`（技术负责人汇签）。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-045 交付 FUND 模块 9 张表 DDL 与 DAO

- **需求追溯**：`FR-FUND-1~17`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：16 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. `TB-FUND-1~9` 交付，DM8 建表成功，与 `DATA_MODEL.md` 逐列一致；
  2. 资金台账/流水表 `NOT NULL` 携带子账户标识，支持「期初 + 发生额 = 期末」可加总；
  3. 资金流水覆盖期初、出入金、调拨、盈亏结转、手续费、保证金变动、期末，每笔有凭证编号；
  4. 出入金幂等键（`idempotency_key`）唯一约束就位。
  5. **`java/dao/**` 下本模块每个 DAO 接口均有对应 `Dm*` 实现类；若某个接口本期不需实现，须在 `TASKS.md` 显式登记「延后实现 + 原因 + 承接任务」（不得沉默略过）。**
- **产出**：`java/dao/impl/DmFund*Dao.java`, `docs/DEPLOY/schema/m2_fund.sql`
- **备注**：FUND 共享台账边界允许跨模块写表（`R-FUND-8`）。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」；本任务依赖 `T-004`，**已完成**、非阻塞源）。**解除人** = `T-030`（技术负责人汇签）。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-046 实现交易执行服务 TradeService

- **需求追溯**：`FR-TRADE-1~18`, `CFG-13`, `Q-13`, `PROJECT.md` §10-23
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-042, T-043
- **估时**：24 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. 提交指令**不落库**（避免交易路径 DB I/O），经桥接下 `CMD_PLACE_ORDER`；确认/撤单经 `CMD_CONFIRM_ORDER`/`CMD_CANCEL_ORDER`；
  2. **交易理由结构化必填**（`CFG-13`）：关联类型 + 关联对象（计划 ID + 敞口 ID，须同子账户）+ 时点说明三项；校验不通过即拒绝；
  3. **关联对象三层时点**：Java 提交时权威校验 → C++ `0x1019` 兜底 → 落库复核（`CH-004` 裁定，**不得只依赖 C++**）；
  4. 事件流回传后落库（成交/委托/持仓/拒绝/流水），回写额度占用与交易计划进度；
  5. 验证交易路径零数据库访问：DB 层全量语句审计下跑 100 笔下单，该路径 SQL 记录 **= 0**（`PROJECT.md` §10-23）。
- **产出**：`java/service/TradeService.java`, REST `/trade/**`
- **备注**：**跨栈依赖**：`CMD_PLACE_ORDER`/`CMD_CONFIRM_ORDER`/`CMD_CANCEL_ORDER` ↔ `EVT_ORDER_ACCEPTED`/`EVT_PRE_TRADE_REJECT`/`EVT_ORDER_REPORT`/`EVT_TRADE_FILL`(0x0204)/`EVT_POSITION_CHANGE`(0x0205)/`EVT_ORDER_CANCEL_RESULT`/`EVT_ORDER_EXPIRED`/`EVT_COMMAND_FLOW`(0x0208)。**`assertCanTrade` 的 M2 强制点（2026-09-21 登记，只加不改）**：`AuthService.assertCanTrade` 在 `src/main` **无 M1 调用点**（**有意**，非缺陷）；其**服务端强制点 = M2 Java 下单写路径**（`POST /trade/order` → `TradingBridge.placeOrder` 前置），**归 `T-046`**。**M1 有意不接线的依据**：`FR-PLAN-14` 明列主体 = **财务 / 风控岗或期货交易岗**，其输出 / 验收**不含权限项** ⇒ 在 `decomposeTradePlans` 处要求交易岗能力会**误拒合法主体**。该结论现**仅存于 `PlanService` 代码注释**，故登记入表。（同族登记见 `T-102` 备注） **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-042`/`T-043`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-042`/`T-043`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-042`/`T-043` 阻塞 ⇒ 维持 `BLOCKED`。

### T-047 实现结算服务 SettleService（独立结算 + 加总对账 + 差异归属）

- **需求追溯**：`FR-SETTLE-1~12/15/16`, `CFG-11`, `Q-11`, `PROJECT.md` §10-36
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-042, T-044, T-046
- **估时**：32 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. CTP 日终入库 → 子账户拆分 → 独立结算（持仓/资金/盈亏按子账户）→ 三类核对（仓位/资金/成交）；
  2. **加总对账**：校验 `Σ 子账户数据 = 真实账户数据`；**人为构造不一致 → 告警触发**（`PROJECT.md` §10-36）；
  3. **阻断范围**：仅差异所属子账户的新开仓/资金划拨/日终关账，**不波及其他子账户、不阻断平仓**；全账户强制告警（`CFG-11`）；`possible_sub_accounts` 仅展示，机判取 `t_agg_diff_candidate`/`t_reconcile_difference`；
  4. 关账门禁生效，差异未闭环不得关账。
- **产出**：`java/service/SettleService.java`, REST `/settle/**`
- **备注**：**跨栈依赖**：CTP 日终数据与成交/持仓事件经 T-042 消费。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-042`/`T-044`/`T-046`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-042`/`T-044`/`T-046`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-042`/`T-044`/`T-046` 阻塞 ⇒ 维持 `BLOCKED`。

### T-048 实现核对任务与电子签署

- **需求追溯**：`FR-SETTLE-13/14/17`, `CFG-14`, `R-SETTLE-11`, `Q-14`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-047
- **估时**：16 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. 月度三方核对（`RISK,TRADE,FINANCE` 各自签署）、季度（`RISK` 编制 + `TRADE` 会签 + `MANAGE` 签收）、年度（`AUDITOR` 全覆盖）；
  2. **不可代签**：按角色级判据与多角色解析规则拒绝代签（`CFG-14` / `DC-022`）；
  3. 签署截止 30 天（`settle.sign.deadline_days`）；核对记录与签署记录作为合规证据可导出；
  4. 审计席（`FR-AUDIT-13`）可**只读**查询核对任务与签署记录。
- **产出**：`java/service/SettleService.java`（核对与签署部分）、REST `/settle/monthly-check`、`/settle/quarterly-report`
- **备注**：`t_check_signature` 唯一约束 `uk_check_signature_task_role`。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-047`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-047`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-047` 阻塞 ⇒ 维持 `BLOCKED`。

### T-049 实现资金管理服务 FundService（出入金 + 划拨 + 额度）

- **需求追溯**：`FR-FUND-1~17`, `CFG-25`, `PROJECT.md` §4.1 ⑨-a、§10-19/20/21/22/37
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-042, T-045
- **估时**：24 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. 资金台账完整：期初 + 发生额 = 期末，每笔有凭证编号，可导出对账（`PROJECT.md` §10-19）；
  2. **出入金经 CTP 异步**：提交 → 等回执 → 更新台账；幂等键去重；超时标记「超时待查」**不得假设未到账**（`PROJECT.md` §10-21）；
  3. **双人复核**：发起人与审批人不得为同一人，同账号发起+审批被拒（`PROJECT.md` §10-22）；
  4. **子账户划拨**为系统内记账：A 减、B 增、**加总不变**、有审批与留痕（`PROJECT.md` §10-37）；划拨后可回滚；
  5. 资金额度上限触及时告警（`CFG-25`，`PROJECT.md` §10-20）。
- **产出**：`java/service/FundService.java`, REST `/fund/**`
- **备注**：**跨栈依赖**：`CMD_FUND_TRANSFER`(0x011D)/`CMD_QUERY_FUND_RESULT`(0x011E) ↔ `EVT_FUND_RECEIPT`(0x020B)。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-042`/`T-045`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-042`/`T-045`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-042`/`T-045` 阻塞 ⇒ 维持 `BLOCKED`。

### T-050 实现事中风控与镜像状态 risk_intraday

- **需求追溯**：`FR-RISK-12~16/19`, `R-RISK-13`, `ARCHITECTURE.md` §3.11/§6.8
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-042
- **估时**：20 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. 基于 `TradingBridge.mirror()` 实时监控 6 类指标：实时持仓 / 浮动盈亏 / 保证金占用率 / 风险度 / 资金可用量 / 异常下单行为；
  2. 超阈值 → 告警 / **限制新开仓**（经 `CMD_SET_TRADING_SUSPEND`，`scope=OPEN_ONLY`）/ 强制平仓**只提示不自动下单**（`R-RISK-13`）；
  3. 指标在内存中计算，**先算后异步落库**，不因 DB 查询阻塞事件消费（`PROJECT.md` §10-25，全过程无 DB 查询）；
  4. 「只限开仓、不限平仓」：C++ 侧对平仓/减仓跳过该规则。
- **产出**：`java/risk_intraday/**`, REST `/risk/mirror-status`
- **备注**：**跨栈依赖**：消费 `EVT_TRADE_FILL`/`EVT_POSITION_CHANGE`；下发 `CMD_SET_TRADING_SUSPEND`(0x0118)；告警经 `/ws/alert` 推送。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-042`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-042`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-042` 阻塞 ⇒ 维持 `BLOCKED`。

### T-051 实现调度 job

- **需求追溯**：`NFR-TEST-4`, `ARCHITECTURE.md` §3.12/§6.5/§6.9/§6.10
- **负责人**：backend-engineer#java2
- **优先级**：P1
- **依赖**：T-047, T-049
- **估时**：16 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. 定时任务：日终结算、事后风控、报表生成、对账、到期提醒（`CFG-24`）、报告周期提醒、事件流水位巡检；
  2. 调度任务**不绕过 service 层直接调 DAO**；
  3. **可注入时钟**（`NFR-TEST-4`），单测可控时间推进；
  4. 任务失败可告警且可重入，不产生重复结算。
- **产出**：`java/job/**`
- **备注**：— **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-047`/`T-049`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-047`/`T-049`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-047`/`T-049` 阻塞 ⇒ 维持 `BLOCKED`。

### T-052 交付部署与运维支撑

- **需求追溯**：`NFR-DEPLOY-2/3/4/5`, `ARCHITECTURE.md` §3.14/§11, `CFG-01~31`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-009, T-049
- **估时**：16 人时
- **来源**：初版（承接 `T-ARCH-001` 要求 4）
- **状态**：**BLOCKED**
- **验收标准**：
  1. 部署清单（`NFR-DEPLOY-2`）、环境依赖清单（`NFR-DEPLOY-5`，**含 DM JDBC 驱动实测版本**）、配置项清单（`CFG-01`~`CFG-31`）齐备；
  2. 双组件启动顺序脚本：`hg-trading` → `hg-biz`（Java 连接时 C++ 已在重放/就绪态）；
  3. 健康检查脚本可用；前端与后端**可独立升级**（零构建链，`ARCHITECTURE.md` §7.1.9）；
  4. 敏感字段零落盘在部署文档中显式声明。
  5. **DM8 实机建表跑通**：对 `docs/DEPLOY/schema/*.sql` 全部建表脚本在 DM8 实例上执行成功，且与 `DATA_MODEL.md` 逐列一致（**承接 `T-005`/`T-006`/`T-007`/`T-061`/`T-094` 五处备注转来的「DM8 实机建表未执行 ⇒ 归 `T-052`」义务**；环境依赖见本任务验收 1）。
  6. **protobuf 供给策略落地**：给出 `protobuf` 依赖的获取 / 锁定策略并落地（依据本任务备注，**以备注原文为准**）。
  7. **CTP SDK 实盘版本复测**：给出 CTP SDK 实盘版本复测结论（承接 `T-027` 的探路结论至实盘版本）。
- **产出**：`docs/DEPLOY/README.md`、启动/健康检查脚本
- **备注**：DM JDBC 驱动版本须记录（`RSK-03`）。**环境依赖清单须并入两项 M2 探路承接项**：① protobuf **供给策略**——优先系统包 `3.21.12` / `FetchContent` 离线回退 / `protobuf-lite` 降级（见 `T-026` 验收 6）；② **CTP SDK 实盘版本复测**——按 `NFR-DEPLOY-5` 记录实测版本并重跑 `cpp/ctp_smoke/ci/run_probe.sh`（见 `T-027` 验收 6）。 **📌 承接义务落点登记（编排者 2026-09-21）**：**本任务验收标准新增的第 5/6/7 三条，即 `T-005`/`T-006`/`T-007`/`T-061`/`T-094` 五处备注转来义务的落点**（验收 5 = 五处「DM8 实机建表未执行」义务；验收 6/7 = `T-026`/`T-027` 探路结论的实盘承接）。此前本任务**验收段对 `DM8`/`protobuf`/`CTP SDK` 零命中** ⇒ 五处转来的义务在验收面**完全落空**，现补齐。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-049`（`T-009` 已完成）。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-049`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-049` 阻塞 ⇒ 维持 `BLOCKED`。

### T-053 实现交易执行前端（含行情 WebSocket）

- **需求追溯**：`FR-TRADE-1~18`, `NFR-PERF-5`, `ARCHITECTURE.md` §4.1.1/§4.1.2
- **负责人**：frontend-engineer
- **优先级**：P0
- **依赖**：T-017, T-046
- **估时**：20 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. 下单（含交易理由结构化三项必填）/ 待确认列表 / 人工确认 / 撤单 / 委托回报 / 成交 / 持仓 / 事前风控拒绝记录页面可用；
  2. **行情**：`ws://<host>/ws/quote` 实时展示，允许丢帧仅显示最新值；断线重连；
  3. 对接 REST：`/trade/order`、`/trade/order/{id}/confirm`、`/trade/order/{id}/cancel`、`/trade/order-report`、`/trade/fill`、`/trade/position`、`/trade/reason`、`/trade/reject-record`、`/trade/flow`、`/trade/quote/{instrument}`、`/trade/strategy`；
  4. 事前风控拒绝展示 `ruleId/current/threshold/limit_layer` 与处理建议。
- **产出**：`web/js/pages/trade.js`
- **备注**：**跨栈依赖（链式）**：前端 ← Java（REST/`/ws/quote`）← Java 消费 C++ `EVT_MARKET_QUOTE`（同一 HGCP 通道，行情为**可丢例外**）+ `EVT_*`（原「通道 B」/「通道 A」，`CH-006` 后并入单一 HGCP 通道）。 **📌（2026-09-21 第 29 轮台账对齐）** 阻塞原因 = **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）—— **已有开工骨架、尚未完成**（`web/js/pages/trade.js` 自述「开工骨架」）；「**开工骨架**」**不等于**可继续开工 ⇒ **不得继续**；**依赖亦未满足**：`T-017`/`T-046`。**解除人** = `T-030`（技术负责人汇签）。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-017`/`T-046` 阻塞 ⇒ 维持 `BLOCKED`。

### T-054 实现结算前端页面

- **需求追溯**：`FR-SETTLE-1~17`
- **负责人**：frontend-engineer
- **优先级**：P1
- **依赖**：T-047
- **估时**：16 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. CTP 日终数据、结算单、三类核对、加总对账（含差异候选与阻断范围提示）、差异处理、结算报告、保证金、压力测试、月度/季度页面可用；
  2. 对接 REST：`/settle/ctp-data`、`/settle/cycle*`、`/settle/difference`、`/settle/report`、`/settle/margin`、`/settle/stress-test`、`/settle/monthly-check`、`/settle/quarterly-report`、`/settle/export`；
  3. 加总不一致时高亮差异子账户并提示阻断影响范围。
- **产出**：`web/js/pages/settle.js`
- **备注**：— **📌（2026-09-21 第 29 轮台账对齐）** 阻塞原因 = **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）—— **已有开工骨架、尚未完成**；**依赖亦未满足**：`T-047`。**解除人** = `T-030`（技术负责人汇签）。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-047` 阻塞 ⇒ 维持 `BLOCKED`。

### T-055 实现资金管理前端页面

- **需求追溯**：`FR-FUND-1~17`
- **负责人**：frontend-engineer
- **优先级**：P1
- **依赖**：T-049
- **估时**：16 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. 资金台账/流水、出入金申请与审批（双人复核提示）、子账户划拨与审批、回执、资金额度、保证金占用视图页面可用；
  2. 对接 REST：`/fund/ledger`、`/fund/flow`、`/fund/deposit`、`/fund/withdraw`、`/fund/{id}/review`、`/fund/transfer`、`/fund/transfer/{id}/approve`、`/fund/receipt`、`/fund/quota`、`/fund/margin`、`/fund/reconcile`；
  3. 划拨页显式提示「系统内记账，加总不变」；「超时待查」状态显式展示。
- **产出**：`web/js/pages/fund.js`
- **备注**：— **📌（2026-09-21 第 29 轮台账对齐）** 阻塞原因 = **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）—— **已有开工骨架、尚未完成**；**依赖亦未满足**：`T-049`。**解除人** = `T-030`（技术负责人汇签）。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-049` 阻塞 ⇒ 维持 `BLOCKED`。

### T-056 制定 M2 联调测试策略与 HGCP 协议专项测试设计

- **需求追溯**：`NFR-TEST-5`, `ARCHITECTURE.md` §4.2.7/§4.2.6
- **负责人**：test-manager
- **优先级**：P0
- **依赖**：T-030
- **估时**：16 人时
- **来源**：初版
- **状态**：**TODO**（2026-09-21 `T-030` 汇签解锁，由 `BLOCKED` 转）
- **验收标准**：
  1. 协议专项测试设计覆盖：帧格式/CRC/版本不匹配、握手鉴权失败、心跳 3 s 检出、断连重连补数、**补数批次行情帧的接收侧不消费（`CH-007`，出处 `ARCHITECTURE.md` §4.2.6）**、乱序与缺口、幂等去重、`param_version` 单调、最大帧分片；
  2. 双组件联调测试策略：C++/Java 分别打桩与真联两档；
  3. 明确 `NFR-PERF-6`（两侧逐帧数量与顺序一致）的验证口径；
  4. 用例均可给出唯一预期值。
- **产出**：`docs/TEST_PLAN/M2.md`
- **备注**：协议已冻结，**冻结后变更须重跑本套测试**。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」；本任务依赖 `T-030`）。**解除人** = `T-030`（技术负责人汇签）。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条无其他阻塞源 ⇒ `BLOCKED` → `TODO`。

### T-057 执行 M2 协议专项测试

- **需求追溯**：`NFR-TEST-5`, `NFR-REL-2/5`, `NFR-PERF-6`, `PROJECT.md` §10-31/32
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-039, T-042, T-056
- **估时**：32 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. 逐一执行 T-056 设计的协议用例并留证；
  2. 压力下（连续 1000 笔）事件流**不丢、不乱序**（`PROJECT.md` §10-31）；
  3. 模拟断开连接 → 重连后两侧状态最终一致、补数不丢关键事件（`PROJECT.md` §10-32）；
  4. 断连检出 ≤ 5 s（实测约 3 s）；
  5. **补数批次中的行情帧在接收侧不消费**（`CH-007`，出处：`ARCHITECTURE.md` §4.2.6）：构造「断连 → 补数」场景，断言 ① `MSG_RESUME_DATA` 批次**线路侧完整回放**（含行情帧，`SEQ` 连续无缺口、**不触发 `0x0106`**）、**补数收敛**（不出现反复重补，无死循环、不进入永久 `NOT_READY`）；② **接收侧对该批次中的 `EVT_MARKET_QUOTE` 一律丢弃、不消费、不透传、不推前端、不进入行情缓存**；③ 普通（关键）事件帧仍**完整补数**（**不得误伤**）**并落库（须落库的 10 种事件）**。
- **产出**：协议专项测试报告（**产出路径补登 2026-09-21**：落 `docs/TEST_RESULT.md` §`T-057` 节）
- **备注**：**协议冻结后的回归基线**；第 5 条为 `CH-007` 追加（**消费侧规则**，出处 `ARCHITECTURE.md` §4.2.6；协议 `v1.0.1`，消息集 40 / 错误码 62 不变；**线路未过滤**、**线格式 `VER` 仍 `0x0100`**）。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-039`/`T-042`/`T-056`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-039`/`T-042`/`T-056`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-039`/`T-042`/`T-056` 阻塞 ⇒ 维持 `BLOCKED`。

### T-058 执行 M2 端到端（模拟盘期货+期权/零 DB/加总/划拨）

- **需求追溯**：`PROJECT.md` §9 M2 验收, §10-1/21/23/30/35/36/37
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-046, T-047, T-048, T-049, T-052, T-057
- **估时**：32 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. 在 CTP 模拟盘按 M1 计划完成**一笔期货开平 + 一笔期权策略（如领口）**，每单可追溯至子账户与计划；
  2. 验证**交易路径零 DB 访问**（DB 层语句审计，路径 SQL = 0；或停库方式验证下单路径不受影响，`PROJECT.md` §10-23）；
  3. **子账户加总与真实账户一致**；人为构造不一致 → 告警触发（§10-36）；
  4. **子账户间划拨后加总不变**（§10-37）；出入金经 CTP 跑通并回执回写（§10-21）；
  5. 组件通信双向可用：C++ 成交 Java 落库，Java 改风控参数 C++ 内存生效（§10-30）。
- **产出**：`docs/TEST_PLAN/M2-report.md`
- **备注**：依赖 T-052 部署脚本搭建模拟盘环境。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-046`~`T-049`/`T-052`/`T-057`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-046`~`T-049`/`T-052`/`T-057`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-046`~`T-049`/`T-052`/`T-057` 阻塞 ⇒ 维持 `BLOCKED`。

### T-059 执行 C++ ctest 单测（风控/指令/流水/协议）

- **需求追溯**：`NFR-TEST-1`, `PROJECT.md` §10-8
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-035, T-036, T-037, T-038, T-039, T-041（原含 `T-040`；`T-040` 已由 `CH-006` 定界改派 `#java`，不再属 C++ 单测范围）
- **估时**：24 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. ctest 覆盖 `risk_engine`（8 规则 + 三层限额全序 + 并列取一）、`order_engine`（状态机/超时/确认人一致）、`flow_store`（重放）、`hgcp`（帧/CRC/补数）；
  2. C++ 侧核心模块（额度校验、事前风控）单测覆盖率 ≥ 80%；
  3. `ctest` 全部通过；`-Werror` 下零警告。
- **产出**：`cpp/tests/**` + ctest 报告（**产出路径补登 2026-09-21**：落 `docs/TEST_RESULT.md` §`T-059` 节）
- **备注**：— **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-035`~`T-041`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-035`~`T-041`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-035`~`T-041` 阻塞 ⇒ 维持 `BLOCKED`。

### T-060 执行 M2 代码与架构合规审查（E1~E6 全量）

- **需求追溯**：`NFR-SEC-9`, `NFR-MAINT-4/6`, `PROJECT.md` §10-6/29
- **负责人**：code-reviewer
- **优先级**：P0
- **依赖**：T-041, T-049, T-055
- **估时**：16 人时
- **来源**：初版
- **状态**：**BLOCKED**
- **验收标准**：
  1. `E1`~`E6` 六脚本全量通过（聚合入口 `arch-check.sh` 任一失败即整体失败）；
  2. C++ 组件**不链接任何 DB 客户端库**、无 SQL 语句（`PROJECT.md` §10-29）；
  3. C++11 严格合规：无 `make_unique`/`optional`/`string_view`/结构化绑定/`if constexpr`（§10-6）；
  4. 敏感字段零落盘复核（结构 + grep）；组件边界无越界；
  5. 出具问题清单（问题/位置/建议/级别），不改代码。
  6. **（2026-09-21 编排者裁定增补）核对 `ARCHITECTURE.md` §3.0.2 `E4` 的扫描根集合（`cpp/src/{order_engine,risk_engine,risk_state}`）与 §3.1–§3.6 的六模块实际布局是否一致**；若模块增删 / 改名而未同步，须**报为缺陷**（依据：`v1.5.12` 裁定 + `v1.5.13` 脚注 ⑥「残留风险（已接受）」；本项即该风险的**自动化外兜底** —— 「改名并同步更新 `CMakeLists` / 包含路径」的完整迁移**当前无自动化兜底**，故由本审查承接）。
- **产出**：`docs/REVIEW.md`（M2 部分）
- **备注**：只读审查。 **📌（2026-09-21 第 28 轮台账对齐）** 阻塞原因 = ① **M2 首周探路门禁未过**（`T-030` 未汇签；依 `T-030` 备注原文「**未下达许可前 `T-031`~`T-060` 不得开工**」）＋ ② **依赖未满足**：`T-041`/`T-049`/`T-055`。**解除人** = `T-030`（技术负责人汇签）；**依赖**：`T-041`/`T-049`/`T-055`。
  **📌（2026-09-21 `T-030` 汇签解锁 · 只加不改）** 门禁已由 `T-030` 汇签解除（许可令见 `docs/gates/M2-recon-signoff/SIGN-OFF.md`）；本条仍受依赖 `T-041`/`T-049`/`T-055` 阻塞 ⇒ 维持 `BLOCKED`。

### T-061 交付 RISK 首增量 `t_param_dispatch` 单表 DDL 与 DAO

- **需求追溯**：`FR-RISK-1~19`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-004
- **估时**：2 人时（原 20 人时；范围收窄为单表首增量，余 18 人时转 `T-094`）
- **来源**：初版（2026-09-20 编排者裁定范围收窄）
- **状态**：**已完成，待独立复核**
- **验收标准**：
  1. `TB-RISK-2 t_param_dispatch` 单表 DDL 交付（`docs/DEPLOY/schema/m3_risk.sql`，该文件 `CREATE TABLE` = **1**），与 `DATA_MODEL.md` v1.3.2 §2.8 逐列一致；
  2. `ParamDispatchDao` / `DmParamDispatchDao` 交付，支撑 `T-011` 参数下发编排（`param_version` 单调 + 下发结果/回执记录）；
  3. 交付范围**不含**其余 10 张 RISK 表（转 `T-094`）。
- **产出**：`docs/DEPLOY/schema/m3_risk.sql`（首增量）、`java/src/main/java/com/hedgeguard/dao/ParamDispatchDao.java`、`java/src/main/java/com/hedgeguard/dao/impl/DmParamDispatchDao.java`
- **备注**：**范围收窄依据（2026-09-20 编排者裁定）**：`t_param_dispatch` 归 `#java` 且**已交付**（`T-011` 验收 2 直接依赖 `ParamDispatchDao`）；**剩余 10 张 RISK 表撤出本轮、改归 M3 窗口**，由 `T-094` 承接。`m3_risk.sql` 头部（`:7`~`:27`）已**诚实标注「增量交付声明」**（明写「当前仅含 1 张表」，并列出待续 10 表及其作用域哨兵 `CHECK` 约束）。⚠️ **DM8 实机建表未执行**（环境无 DM8 实例）。**与 `T-005`~`T-007` 同口径：已完成、待独立复核，不自评。**

### T-062 交付 AUDIT 模块 8 张表 DDL 与 DAO

- **需求追溯**：`FR-AUDIT-1~13`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. `TB-AUDIT-1~8` 交付，DM8 建表成功，与 `DATA_MODEL.md` 逐列一致；
  2. `t_audit_log` 与 `t_audit_report`/`t_evidence_export` 的 `chain_integrity` **统一 4 值**（`COMPLETE`/`HAS_BREAK`/`IN_FLIGHT`/`UNKNOWN`，v1.3.1 `DC-038`）；
  3. `t_audit_log_checksum` 支撑不可篡改校验；
  4. 追溯/筛查/报表/证据链导出所需字段与索引齐备。
  5. **`java/dao/**` 下本模块每个 DAO 接口均有对应 `Dm*` 实现类；若某个接口本期不需实现，须在 `TASKS.md` 显式登记「延后实现 + 原因 + 承接任务」（不得沉默略过）。**
- **产出**：`java/dao/impl/DmAudit*Dao.java`, `docs/DEPLOY/schema/m3_audit.sql`
- **备注**：—

### T-063 实现风控阈值配置与下发闭环（合规下限校验）

- **需求追溯**：`FR-RISK-1~10`, `CFG-01~05/15/16/21`, `R-CONFIG-8`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-038, T-061, T-094
- **估时**：20 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 风控阈值 `CFG-01`~`CFG-05`/`CFG-15`/`CFG-16`/`CFG-21` 可配置并经 T-011 下发 `CMD_UPDATE_RISK_THRESHOLD`；
  2. **默认值不得低于 `INDEX.md` §9.1 口径**；放宽（如 90%→95%）须**显式双人复核 + 留痕 + 提示合规风险**，不得静默放宽；
  3. 下发携带 `param_version`，C++ 回执与生效结果落 `t_param_dispatch`；
  4. 风控规则清单/阈值可在界面查看（对应 `/risk/rule`）。
- **产出**：`java/service/RiskService.java`（阈值部分）、REST `/risk/rule`
- **备注**：**跨栈依赖**：`CMD_UPDATE_RISK_THRESHOLD`(0x0115)/`CMD_UPDATE_QUOTA`(0x0113)；与 T-038 配对。

### T-064 实现事后风控与风险报表落库

- **需求追溯**：`FR-RISK-17/18`, `PROJECT.md` §10-26, `ARCHITECTURE.md` §6.9
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-047, T-061, T-094
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 交易日结束后基于当日全量数据计算并**落库**：风险报表、风险指标统计、违规事项汇总、限额使用情况；
  2. 跑完一个交易日 → 执行事后风控 → 报表落库且数据与当日一致（`PROJECT.md` §10-26）；
  3. 覆盖市场/信用/操作/流动性风险（`PROJECT.md` §4.1 ⑥-b）；
  4. 对接审计报表导出（`FR-AUDIT-6`）。
- **产出**：`java/service/RiskService.java`（事后部分）、REST `/risk/post-report`
- **备注**：依赖 T-051 调度触发。

### T-065 实现风险告警/处置/异常下单统计/指标快照

- **需求追溯**：`FR-RISK-11~16/19`, `R-RISK-10/13`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-050, T-061, T-094
- **估时**：20 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 告警记录落 `t_risk_alert`，处置记录落 `t_risk_disposal`（含限制新开仓/解除）；
  2. 异常下单行为统计落 `t_abnormal_order_stat`（含持仓/回合字段），支持瞬时高频判定（`CFG-05`）；
  3. 风险指标快照落 `t_risk_metric_snapshot`，镜像水位落 `t_mirror_watermark`；
  4. 告警可经 `/ws/alert` 推送，客户端断线后重连补拉（`/risk/alert?since=`）。
- **产出**：`java/service/RiskService.java`（告警部分）、`java/risk_intraday/**` 持久化、REST `/risk/alert*`
- **备注**：**跨栈依赖**：处置动作经 `CMD_SET_TRADING_SUSPEND`(0x0118)。

### T-066 实现规模红线/时点净持仓/期限红线复算与统计

- **需求追溯**：`FR-RISK-4/5/6`, `CFG-01~04/17`, `PROJECT.md` §10-12/13/14
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-063
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. **规模红线**：基数 = 年度现货敞口累计实物量（非营业收入）；商品类 ≤ 90% / 贸易类 ≤ 80%；子账户与全账户双算取严；构造临界数据 → 下单前阻断并给明确提示（`PROJECT.md` §10-12）；
  2. **时点净持仓**：任一交易时点净持仓不得超对应实货风险敞口；构造「计划已用满再下单」→ 阻断（§10-13）；
  3. **期限红线**：持仓超 12 个月或超实货合同期限 → 告警/阻断，不阻断平仓与展期置换（§10-14）；
  4. Java 侧复算结果与 C++ 事前判定一致（同一口径，抽 10 组数据比对）。
- **产出**：`java/service/RiskService.java`（红线复算）
- **备注**：**跨栈依赖**：判定权威在 C++（T-035），本任务为落库复算与统计展示。

### T-067 实现全链路追溯（正向/反向/完整性 + 在途时窗）

- **需求追溯**：`FR-AUDIT-1~3/11`, `R-AUDIT-2/11`, `CFG-17c`, `PROJECT.md` §10-16
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-062
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 正向遍历 `实货敞口 → 套保计划 → 审批 → 交易 → 结算 → 会计凭证`；反向从任一凭证反查敞口，链条无断点（`PROJECT.md` §10-16）；
  2. 链条完整性校验：`COMPLETE`/`HAS_BREAK`/`IN_FLIGHT`/`UNKNOWN` 四态正确标注（**数据缺失致不可判定标 `UNKNOWN`**，不得误标完整）；
  3. **在途时窗**（`CFG-17c`）：当日结算时点前 `IN_FLIGHT` 不计断点 → 逾时 → `UNKNOWN` + 告警 → 再逾上限 → `HAS_BREAK`；
  4. 追溯链含子账户维度。
- **产出**：`java/service/AuditService.java`（追溯部分）、REST `/audit/trace/*`
- **备注**：—

### T-068 实现违规事项筛查（含关联缺失/理由缺失并列）

- **需求追溯**：`FR-AUDIT-4~6`, `Q-13`, `R-AUDIT-7`, `CH-004`
- **负责人**：backend-engineer#java2
- **优先级**：P1
- **依赖**：T-062
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 筛查超品种/超规模/超期限/超授权四类违规（对应 8 号文「超限额或违规交易预警」）；
  2. **「理由缺失（`REASON_MISSING`）」与「关联缺失（`RELATED_MISSING`）」为并列独立维度**，二者结构上均可产生、可构造（`CH-004`/`DC-024`）；
  3. 筛查运行落 `t_violation_screen_run`，命中项落 `t_violation_screen_item`；
  4. 支持按时间/子账户/规则筛选。
- **产出**：`java/service/AuditService.java`（筛查部分）、REST `/audit/violation-screen`
- **备注**：—

### T-069 实现审计报表与证据链导出 + 审计日志不可篡改

- **需求追溯**：`FR-AUDIT-6~10/12/13`, `R-AUDIT-4`, `PROJECT.md` §10-17
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-062
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 季度报表、年度专项报告、重大事项专项报告可**一键导出**，字段完整性与数据正确（`PROJECT.md` §10-17）；
  2. 证据链导出落 `t_evidence_export`，直导出 CSV（流式）；
  3. **审计日志不可篡改**：`t_audit_log_checksum` 校验可用，修改历史日志可被检出；
  4. **审计日志写入失败须阻断导出**（`R-AUDIT-4`：不记录即不允许发生），**不得提供绕过开关**；UI 明确提示原因。
  5. 重大事项按 `CFG-26` 亏损阈值触发并落 `t_major_event`。
- **产出**：`java/service/AuditService.java`（报表/导出/日志）、REST `/audit/report/*`、`/audit/evidence/export`、`/audit/log/verify`
- **备注**：支撑 T-081 证据链导出的存储与校验基座。

### T-070 实现风险控制前端页面

- **需求追溯**：`FR-RISK-11~19`
- **负责人**：frontend-engineer
- **优先级**：P1
- **依赖**：T-064, T-065
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 风险仪表盘、告警列表与处置、限制开仓、强制平仓提示、违规事项、限额使用、镜像状态、规则查看、事后报表页面可用；
  2. 对接 REST：`/risk/dashboard`、`/risk/alert`、`/risk/alert/{id}/dispose`、`/risk/limit-open`、`/risk/force-close-hint`、`/risk/violation`、`/risk/limit-usage`、`/risk/mirror-status`、`/risk/rule`、`/risk/post-report`；
  3. 告警经 `ws /ws/alert` 实时推送，断线重连补拉。
- **产出**：`web/js/pages/risk.js`
- **备注**：接口依据 `ARCHITECTURE.md` §4.1.1/§4.1.2。

### T-071 实现审计前端页面

- **需求追溯**：`FR-AUDIT-1~13`
- **负责人**：frontend-engineer
- **优先级**：P1
- **依赖**：T-067, T-068, T-069
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 正向/反向追溯、链条完整性、违规筛查、季度/年度/重大事项报表、证据链导出、审计日志与校验、操作留痕页面可用；
  2. 对接 REST：`/audit/trace/forward`、`/audit/trace/backward`、`/audit/trace/integrity`、`/audit/violation-screen`、`/audit/report/*`、`/audit/evidence/export`、`/audit/log`、`/audit/log/verify`、`/audit/operation-log`；
  3. 断点显式标记，导出按钮触发流式 CSV 下载。
- **产出**：`web/js/pages/audit.js`
- **备注**：—

### T-072 制定 M3 测试策略（6 类风险逐类触发用例）

- **需求追溯**：`PROJECT.md` §10-5, `NFR-TEST-2`, `PROJECT.md` §6 风险行
- **负责人**：test-manager
- **优先级**：P0
- **依赖**：T-056
- **估时**：12 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 6 类风险（超额套保/品种错配/期限错配/频繁开平仓/会计文档缺失/敏感信息泄露）**逐类构造触发条件**的设计；
  2. 覆盖事前阻断（C++）、事中告警（Java）、事后报表三层；
  3. 崩溃恢复用例（在途指令）设计；
  4. 用例均可给出唯一预期值。
- **产出**：`docs/TEST_PLAN/M3.md`
- **备注**：—

### T-073 执行 M3 超限阻断与崩溃恢复不超额下单验证

- **需求追溯**：`PROJECT.md` §9 M3 验收, §10-24/25/27, `ARCHITECTURE.md` §9
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-041, T-063, T-066
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 按 T-072 逐类构造超限场景，验证**阻断生效**且不发 CTP；6 个用例全通过（`PROJECT.md` §10-5）；
  2. 验证事前风控全内存判定 + 判定耗时满足高频要求（§10-24）；
  3. 验证事中风控告警/限制生效且过程中无 DB 查询（§10-25）；
  4. **崩溃恢复不产生超额下单**：在途指令场景强杀 → 重启重放 → 额度与持仓正确（§10-27）。
- **产出**：`docs/TEST_PLAN/M3-report.md`（风险与恢复部分）
- **备注**：—

### T-074 执行 M3 追溯与报表导出端到端

- **需求追溯**：`PROJECT.md` §9 M3 验收, §10-16/17
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-069, T-071
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 全链路追溯正向/反向双向验证，链条无断点（§10-16）；
  2. 导出季度报表与全链路追溯报告，字段与数据正确（§10-17）；
  3. 链条完整性四态（含 `UNKNOWN`）可构造并正确标注；
  4. 审计日志篡改可被检出。
- **产出**：`docs/TEST_PLAN/M3-report.md`（追溯与报表部分）
- **备注**：—

### T-075 执行 M3 代码与架构合规审查

- **需求追溯**：`NFR-SEC-9`, `NFR-MAINT-4/6`
- **负责人**：code-reviewer
- **优先级**：P0
- **依赖**：T-069, T-070, T-071
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. `E1`~`E6` 复跑通过；
  2. 无绕过审计日志的旁路；审计日志写入失败阻断逻辑无开关；
  3. 组件边界无越界；敏感字段零落盘；
  4. 出具问题清单（问题/位置/建议/级别），不改代码。
- **产出**：`docs/REVIEW.md`（M3 部分）
- **备注**：只读审查。

### T-076 交付 ACCT 模块 12 张表 DDL 与 DAO

- **需求追溯**：`FR-ACCT-1~14`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. `TB-ACCT-1~12` 交付，DM8 建表成功，与 `DATA_MODEL.md` 逐列一致；
  2. 凭证（草稿与正式）、有效性评估记录、再平衡记录均 `NOT NULL` 携带子账户标识（§4.6.3 ① 层，ACCT 7/12）；
  3. 凭证分录行、科目模板、期间关账相关表齐备；`voucher_type` 取值含行权/作废（v1.2 `DC-031`）。
  4. **`java/dao/**` 下本模块每个 DAO 接口均有对应 `Dm*` 实现类；若某个接口本期不需实现，须在 `TASKS.md` 显式登记「延后实现 + 原因 + 承接任务」（不得沉默略过）。**
- **产出**：`java/dao/impl/DmAcct*Dao.java`, `docs/DEPLOY/schema/m4_acct.sql`
- **备注**：—

### T-077 实现套期关系与有效性评估（定性三项 + 量化监测）

- **需求追溯**：`FR-ACCT-1~6`, `CFG-07/07b`, `Q-7`, `PROJECT.md` §4.1 ⑧
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-076
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 生成套期关系文档，含 CAS 24 三要素「正式指定 + 有效性评估方法 + 再平衡机制」，**文档标注「待外部复核」**；
  2. 有效性按**三项定性观察点**（产品关联性 / 方向 / 期限 ±3M）判定，三项均满足即有效（`CFG-07`）；
  3. **量化监测指标**（`CFG-07b`，偏离 ±50% 提示复核）**仅监测、不判定有效性**；
  4. 评估记录落 `t_effectiveness_assess`（含观察点）。
- **产出**：`java/service/AcctService.java`（套期关系与有效性）、REST `/acct/hedge-relation`、`/acct/effectiveness`
- **备注**：—

### T-078 实现再平衡记录（四类触发）

- **需求追溯**：`FR-ACCT-7~9`, `CFG-08`, `Q-8`
- **负责人**：backend-engineer#java2
- **优先级**：P1
- **依赖**：T-077
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 再平衡四类触发可判定并留痕：敞口变动 ≥ 20%、比率偏离 ≥ 20%（敞口侧与工具侧各一项，`CFG-08`）；
  2. 再平衡记录落 `t_rebalance_record`（含触发类型、前后比例、操作人）；
  3. 再平衡与计划/敞口快照可追溯关联，不改历史快照（与 `Q-17` 处置一致）。
- **产出**：`java/service/AcctService.java`（再平衡）、REST `/acct/rebalance`
- **备注**：—

### T-079 实现会计凭证草稿/审核/冲销 + 科目模板 + 期间关账

- **需求追溯**：`FR-ACCT-10~14`, `CFG-09`, `Q-9`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-076
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 会计凭证草稿生成（含科目建议），模式 `DRAFT_ONLY`、**不做总账过账**，标注「待企业财务复核入账」（`CFG-09`）；
  2. 科目表按 `HEDGE_TEMPLATE` 套期会计常用科目模板，**企业可增删改**；
  3. 凭证审核/冲销可用，第二复核人字段生效（v1.2 `DC-032`）；凭证可按子账户出账；
  4. 会计期间关账可用，关账后凭证不可修改。
- **产出**：`java/service/AcctService.java`（凭证与期间）、REST `/acct/voucher*`、`/acct/period*`
- **备注**：—

### T-080 实现套保效果评估与双侧盈亏同屏上报

- **需求追溯**：`FR-ACCT-1~14`, `PROJECT.md` §10-18, 17 号文「双侧盈亏」要求
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-077, T-079
- **估时**：20 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 套保效果评估同时展示**实货盈亏与衍生品盈亏**（两侧同屏、可对账，`PROJECT.md` §10-18）；
  2. 价格时点标注正确（`price_as_of`/`snapshot_as_of`），缺口处置按 `CFG-12b` 标注「非当日价」；
  3. 综合评判金融衍生业务盈亏与实货盈亏（8 号文要求）；
  4. 对接审计证据链与报表导出。
- **产出**：`java/service/AcctService.java`（效果评估）、REST `/acct/effect`、`/acct/report/dual-pnl`
- **备注**：—

### T-081 实现证据链导出（断点显式标记）

- **需求追溯**：`FR-AUDIT-7`, `PROJECT.md` §10-1/16
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-067, T-080
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 一键导出「实货敞口 → 套保计划 → 审批 → 交易 → 结算 → 会计凭证」完整证据链（`PROJECT.md` §10-1）；
  2. 链条断点**显式标记**（不隐藏、不静默补全）；
  3. 导出落 `t_evidence_export` 并计入审计留痕；
  4. 导出的每个环节均有数据，抽查无空档。
- **产出**：`java/service/{AcctService,AuditService}.java`（证据链）、REST `/audit/evidence/export`
- **备注**：—

### T-082 执行安全加固（零落盘全库审计/日志脱敏/鉴权）

- **需求追溯**：`NFR-SEC-1~8`, `PROJECT.md` §10-4
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-049, T-063
- **估时**：20 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. **敏感字段零落盘**：密码与认证码不出现在任何持久化介质（全库 grep + 日志 grep + 配置文件审计**零命中**），重启后须重新输入（`PROJECT.md` §10-4）；
  2. 日志脱敏生效，敏感字段不得出现在日志（含长度与片段）；
  3. TCP 仅监听 `127.0.0.1` + 连接鉴权；环境类型（模拟/实盘）显式标识，切实盘须二次确认 + 双人复核（`CFG-22`/`RSK-15`）；
  4. 资金密码按零落盘红线处理。
- **产出**：安全加固记录 + 审计证据（**产出路径补登 2026-09-21**：落 `docs/TEST_RESULT.md` §`T-082` 节）
- **备注**：—

### T-083 实现套保会计前端页面

- **需求追溯**：`FR-ACCT-1~14`
- **负责人**：frontend-engineer
- **优先级**：P1
- **依赖**：T-077, T-078, T-079, T-080
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 套期关系文档、有效性评估、再平衡、凭证草稿/审核/冲销、会计期间、套保效果（双侧盈亏同屏）、自检页面可用；
  2. 对接 REST：`/acct/hedge-relation`、`/acct/effectiveness`、`/acct/rebalance`、`/acct/voucher*`、`/acct/period*`、`/acct/effect`、`/acct/report/dual-pnl`、`/acct/selfcheck`、`/acct/export`；
  3. 双侧盈亏同屏展示，标注价格时点；文档显著标注「待外部复核」。
- **产出**：`web/js/pages/acct.js`
- **备注**：—

### T-084 制定 M4 测试策略与实盘验证方案

- **需求追溯**：`NFR-TEST-4`, `PROJECT.md` §9 M4, §6 工期隐性成本
- **负责人**：test-manager
- **优先级**：P0
- **依赖**：T-072
- **估时**：12 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. M4 测试策略：会计文档包端到端、CAS 24 自检、换库可行性、组件边界终验、断网验证；
  2. **实盘验证方案**：模拟盘 → 小资金实盘观察 1–2 个月，**明确该期间不占开发工时但占日历**（`PROJECT.md` §6）；
  3. 安全审计清单；
  4. 用例均可给出唯一预期值。
- **产出**：`docs/TEST_PLAN/M4.md`
- **备注**：—

### T-085 执行 M4 会计文档包与 CAS 24 自检报告

- **需求追溯**：`FR-ACCT-1~14`, `PROJECT.md` §10-3
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-080, T-083
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 对 M2 交易生成完整会计文档包（套期关系 + 有效性评估 + 再平衡 + 凭证草稿）；
  2. 按 CAS 24 条款逐项自检，产出**自检报告**（含「正式指定 + 有效性评估方法 + 再平衡机制」三要素）；
  3. 文档标注「**待外部复核**」；会计顾问确认为低优先级、不阻塞交付（`PROJECT.md` §6）；
  4. 双侧盈亏同时上报可验证。
- **产出**：`docs/TEST_PLAN/M4-report.md`（会计文档包 + 自检报告）
- **备注**：—

### T-086 执行实盘小资金运行验证与安全审计

- **需求追溯**：`PROJECT.md` §9 M4 验收, `NFR-SEC-1~9`
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-082, T-085
- **估时**：24 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. 小资金实盘运行 **1–2 个月**（日历期，不占开发工时），期间**无违规告警**；
  2. 环境类型显式区分，无实盘误连（`RSK-15`）；
  3. 安全审计通过（零落盘、鉴权、日志脱敏）；
  4. 出具实盘运行记录与安全审计结论。
- **产出**：实盘运行记录 + 安全审计报告（**产出路径补登 2026-09-21**：落 `docs/TEST_RESULT.md` §`T-086` 节）
- **备注**：本任务含日历等待期，排期时须显式扣除（`PROJECT.md` §6）。

### T-087 执行换库可行性、组件边界与全流程断网终验

- **需求追溯**：`PROJECT.md` §10-11/28/29/33
- **负责人**：test-engineer
- **优先级**：P0
- **依赖**：T-082, T-086
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. **DM8 全功能跑通**：建表/读写/查询/大字段正常；SQL 无 MySQL 方言残留（§10-28）；
  2. **换库可行性**：DAO 层之外无 SQL；无存储过程/触发器/自定义函数依赖（§10-33）；
  3. **组件边界**：C++ 组件无任何 DB 访问代码、不链接 DB 客户端库、静态扫描无 SQL（§10-29）；
  4. **零外部数据依赖**：断开外网（保留 CTP 连通）后全流程正常（§10-11）。
- **产出**：`docs/TEST_PLAN/M4-report.md`（终验部分）
- **备注**：—

### T-088 执行 M4 代码与架构终审

- **需求追溯**：`NFR-SEC-9`, `NFR-MAINT-4/6`
- **负责人**：code-reviewer
- **优先级**：P0
- **依赖**：T-081, T-082, T-087
- **估时**：16 人时
- **来源**：初版
- **状态**：TODO
- **验收标准**：
  1. `E1`~`E6` 全量复跑通过；C++11 严格合规；
  2. 组件边界、零落盘、追溯链完整性终审通过；
  3. 未闭环 P0/P1 缺陷为 0（`BOARD.md` §3 门槛）；
  4. 出具终审报告与遗留风险清单。
- **产出**：`docs/REVIEW.md`（终审）
- **备注**：只读审查；终审通过是 S8 交付的前置。

### T-089 冻结数据模型写者、锁定定版 md5 并回写 SIGN-OFF 基线表

- **需求追溯**：`SIGN-OFF.md` §2/§6, `PROJECT.md` §4.1.E（三）契约冻结纪律（「先冻结再并行」）
- **负责人**：编排者（team-lead）
- **优先级**：P0
- **依赖**：T-001
- **估时**：4 人时
- **来源**：新增（门禁制品定版冻结，**由编排者执行**）
- **状态**：**已完成，待独立复核**（证据档：**PM 旁证复算**（md5 / 字节数 / 间隔取样稳定）；编排者执行；**无第三方独立验证记录**）
- **验收标准**：
  1. **定版 md5 锁定**：公布 `docs/DATA_MODEL.md` 定版 = **`99b647ec3e7a29b95ee86b04a58c747c`**（5177 行 / 442225 字节 / mtime 18:43:53）；
  2. **写入停止取样证据**：写者已冻结、取样留档证明写入已停止；
  3. **`SIGN-OFF.md` §2 基线表回写**完成，并向下游（项目经理、两席）下发定版 md5。
- **产出**：`docs/gates/M1-data-model-signoff/SIGN-OFF.md` §2 回写（**由编排者维护，项目经理不改 `docs/gates/`**）
- **备注**：**交付方 = 编排者**。触发原因：项目经理在 18:41–18:43 实测到 `DATA_MODEL.md` 在 gate 证据（18:35–18:41）之后仍被写入（md5 连变、行数由 5169 增至 5177）；编排者裁决出品 v1.3.2 收尾并完成冻结/锁定/回写。本任务属**流程前置条件**，不计入制品缺陷清单。完成后 M1 编码的放行条件收敛为 **T-002 两席定点再验证闭环**。

### T-090 落地架构判据 E1 脚本（C++ 不得链接 DB 客户端库）

- **需求追溯**：`ARCHITECTURE.md` §3.0.2 `E1`, §3.13, §5.3（`NFR-SEC-9`）, `PROJECT.md` §10-29
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：无（`tools/` 与 C++ / Java 源码不冲突，可并行）
- **估时**：4 人时
- **来源**：新增（架构 §3.0.2 判据落点补齐；M2 首周批，与 `T-026`/`T-027`/`T-028` 同批）
- **状态**：**DONE**（2026-09-21；**其 4 项边界发现已由 `T-095` 收口**（`TE-AC-002`/`TE-AC-003`/`TE-AC-004`；`TE-AC-001` 经编排者裁定**非缺陷**）⇒ 原「退回重做」事由消除；**三轮独立复核已过**（一轮 / 二轮 / 三卷，见 `docs/TEST_RESULT.md` §`T-090`~§`T-093` 节，**以实时 `md5sum` 为准**）⇒ **判据（授权 + 独立验证）满足**）。**交付留档**：`tools/arch-check/check_no_db_links.sh`（145 行 / `0755` / md5 `fedd522a…`；**以实时 `md5sum` 为准**））
- **验收标准**：
  1. **落点**：`tools/arch-check/check_no_db_links.sh`（可执行，`bash <脚本>` 直跑）；
  2. **判据**：对目标二进制执行 `ldd`，**并**在 CMake 产物 `link.txt` 中检索 DB 库名；命中 `libdmjdbc` / `libdmdpi` / `mysql` / `sqlite` / `pq` / `odbc` / `oci` → **失败**；
  3. **判据须可满足（不得裸关键字误伤同名标识符）**：命中模式须为**库名形态**（`ldd` 输出行 / `-l<name>` 链接项 / `lib<name>.so*` 路径），**不得**对源码或 `CMakeLists.txt` 全文裸检索 `pq` / `odbc` / `oci` 等短词；短词匹配须加词边界**且**限定于链接项上下文；**显式豁免**：CTP SDK 自带库，以及白名单文件（`tools/arch-check/allowlist_e1.txt`，如设）内逐条声明的条目；
  4. **退出码语义**：`0` = 无命中（通过）；`1` = 命中（失败）；`2` = 用法/前置错误（目标不存在、`ldd` 不可用）。聚合入口 `arch-check.sh` 下任一脚本非 0 → 整体失败；
  5. **自测三条（可复跑，须写入脚本头部注释或 README）**：① 对 `T-026` 产出工程（`cpp/proto_smoke/build/`）的二进制 → **`0`**；② 以 `-lmysqlclient`（或 `-ldmjdbc`）临时链接探针目标 → **`1`**；③ 目标路径不存在 → **`2`**。
- **产出**：`tools/arch-check/check_no_db_links.sh`（+ 白名单文件，如设）
- **备注**：M2 首周批（与 `T-026`/`T-027` 同批），`tools/arch-check/` 与 C++ / Java 源码目录不冲突，可并行。**落点去重**：`T-031`（C++ 骨架）验收标准 3 与产出亦列有本脚本名，**本任务为脚本的唯一交付与验收单元**（脚本实现 + 自测三条 + 退出码语义），`T-031` 侧仅保留「接入构建与 CI」；该去重口径**已报 team-lead 裁定**（本表不改 `T-031` 条文）。

### T-091 落地架构判据 E3 脚本（C++11 合规扫描）

- **需求追溯**：`ARCHITECTURE.md` §3.0.2 `E3`, §3.13, §8.7（`NFR-COMPAT-1`）
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：无（`tools/` 与 C++ 源码不冲突，可并行）
- **估时**：4 人时
- **来源**：新增（架构 §3.0.2 判据落点补齐；M2 首周批）
- **状态**：**DONE**（2026-09-21；**其 4 项边界发现已由 `T-095` 收口**（`TE-AC-002`/`TE-AC-003`/`TE-AC-004`；`TE-AC-001` 经编排者裁定**非缺陷**）⇒ 原「退回重做」事由消除；**三轮独立复核已过**（一轮 / 二轮 / 三卷，见 `docs/TEST_RESULT.md` §`T-090`~§`T-093` 节，**以实时 `md5sum` 为准**）⇒ **判据（授权 + 独立验证）满足**）。**交付留档**：`tools/arch-check/scan_cpp11.sh`（188 行 / `0755` / md5 `8381011b…`；**以实时 `md5sum` 为准**））
- **验收标准**：
  1. **落点**：`tools/arch-check/scan_cpp11.sh`；扫描范围 `cpp/**`（构建参数取证 + 禁用特性关键字扫描）；
  2. **编译参数判据**：对实际生效参数取证（`compile_commands.json` / CMake cache），须为 `-std=c++11 -Wall -Wextra -Werror`；**不作「看起来配了」式判断**；
  3. **禁用特性命中集合**：`make_unique` / `std::optional` / `std::string_view` / `if constexpr` / `std::variant` / 结构化绑定；
  4. **判据须可满足（不得裸关键字误伤同名标识符）**：须以**明确形态**匹配——`std::` 命名空间限定（`std::optional` / `std::string_view` / `std::variant`）、两词连写形态（`if constexpr`）、结构化解构形态（`auto [` … `]`）、调用形态（`make_unique\s*<`）；**豁免**：`cpp/third_party/**`、`cpp/build*/**`、`_deps/**`、注释与字符串字面量（须先剥离，或在 README 显式声明为已知误报边界）——**不得**因命名中含 `optional` / `variant` 字样的自有标识符而命中；
  5. **退出码语义**：`0` = 无命中；`1` = 命中；`2` = 用法/前置错误；
  6. **自测三条（可复跑）**：① 对 `T-027` 探路工程（`cpp/ctp_smoke/`，实测 `make_unique`/`optional`/`string_view`/`if constexpr`/结构化绑定**全 0 命中**）→ **`0`**；② 注入 `auto p = std::make_unique<int>(1);` 的探针 → **`1`**；③ 仅出现注释 `// std::optional 不可用` 或标识符 `optional_flag` → **`0`**。
- **产出**：`tools/arch-check/scan_cpp11.sh`
- **备注**：M2 首周批。**落点去重**：`T-031` 验收标准 3 与产出亦列有本脚本名，**本任务为唯一交付与验收单元**，`T-031` 侧仅保留「接入构建与 CI」；该去重口径**已报 team-lead 裁定**（本表不改 `T-031` 条文）。

### T-092 落地架构判据 E4 脚本（交易路径无锁/无动态分配/无同步 I/O 扫描）

- **需求追溯**：`ARCHITECTURE.md` §3.0.2 `E4`, §3.13, §5.3（`NFR-PERF-1`）, §9（判据：交易指令路径不得出现 `mutex`、动态分配、同步 I/O）
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：无（`tools/` 与 C++ 源码不冲突，可并行）
- **估时**：4 人时
- **来源**：新增（架构 §3.0.2 判据落点补齐；M2 首周批）
- **状态**：**DONE**（2026-09-21；**其 4 项边界发现已由 `T-095` 收口**（`TE-AC-002`/`TE-AC-003`/`TE-AC-004`；`TE-AC-001` 经编排者裁定**非缺陷**）⇒ 原「退回重做」事由消除；**三轮独立复核已过**（一轮 / 二轮 / 三卷，见 `docs/TEST_RESULT.md` §`T-090`~§`T-093` 节，**以实时 `md5sum` 为准**）⇒ **判据（授权 + 独立验证）满足**）。**交付留档**：`tools/arch-check/scan_lock_free.sh`（157 行 / `0755` / md5 `bd5c2e9e…`；**以实时 `md5sum` 为准**））
- **验收标准**：
  1. **落点**：`tools/arch-check/scan_lock_free.sh`；扫描范围按架构 §3 模块划分取**交易指令路径**所在目录（`cpp/src/trading/**` 或 `risk_engine` / `order_engine` / `risk_state` / `flow_store` 等判定函数所在目录；目录名以架构为准，实际映射须在 README 写明）；
  2. **三类命中**：① 锁与同步原语 `mutex` / `lock_guard` / `unique_lock` / `condition_variable`；② **动态分配** `new` / `delete` / `malloc` / `free` / `make_shared` / `make_unique`；③ **同步 I/O** `fsync` / `write` / `send` / `recv` / `sleep` / `std::this_thread::sleep_for`；
  3. **判据须可满足（不得裸关键字误伤同名标识符）**：须以**调用/声明形态**匹配（如 `\bnew\s+\w`、`\bmalloc\s*\(`、`\bstd::mutex\b`、`\bstd::lock_guard\s*<`），**不得**裸词命中——注释、字符串字面量、日志文案与含同名字段的标识符（如 `mutex_owner` / `new_count`）**均不得命中**；**豁免**：`#include` 行与白名单文件（`tools/arch-check/allowlist_e4.txt`，如设）；
  4. **退出码语义**：`0` = 无命中；`1` = 命中；`2` = 用法/前置错误；
  5. **自测三条（可复跑）**：① 对探路/骨架产物 → **`0`**；② 注入 `std::lock_guard<std::mutex> g(m);` 或 `auto* p = new int;` 的探针 → **`1`**；③ 仅出现标识符 `mutex_owner` 与注释 `// no mutex here` → **`0`**。
- **产出**：`tools/arch-check/scan_lock_free.sh`（+ 白名单文件，如设）
- **备注**：M2 首周批。**落点去重**：`T-031` 验收标准 3 与产出亦列有本脚本名，**本任务为唯一交付与验收单元**，`T-031` 侧仅保留「接入构建与 CI」；该去重口径**已报 team-lead 裁定**（本表不改 `T-031` 条文）。

### T-093 落地架构判据 E6 脚本（前端零构建链与无直连扫描）

- **需求追溯**：`ARCHITECTURE.md` §3.0.2 `E6`, §3.13, §3.15/§8.3（`NFR-MAINT-1` / `NFR-MAINT-5`）, §8.7（`NFR-COMPAT-3`）
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：无（`tools/` 与前端源码不冲突，可并行）
- **估时**：4 人时
- **来源**：新增（架构 §3.0.2 判据落点补齐；M2 首周批）
- **状态**：**DONE**（2026-09-21；**其 4 项边界发现已由 `T-095` 收口**（`TE-AC-002`/`TE-AC-003`/`TE-AC-004`；`TE-AC-001` 经编排者裁定**非缺陷**）⇒ 原「退回重做」事由消除；**三轮独立复核已过**（一轮 / 二轮 / 三卷，见 `docs/TEST_RESULT.md` §`T-090`~§`T-093` 节，**以实时 `md5sum` 为准**）⇒ **判据（授权 + 独立验证）满足**）。**交付留档**：`tools/arch-check/check_frontend.sh`（113 行 / `0755` / md5 `9f1dd5f1…`；**以实时 `md5sum` 为准**））
- **验收标准**：
  1. **落点**：`tools/arch-check/check_frontend.sh`；
  2. **零构建链判据**：存在 `package.json` / `node_modules` / **打包产物**（`dist/`、`*.bundle.js`、`*.min.js`）→ **失败**（`NFR-MAINT-1`）；
  3. **须排除目录（避免误伤）**：**`build` 开头**的目录、`_deps`、`third_party`——三类目录下的同名文件**不得命中**；
  4. **前端无直连判据**：前端源码出现 `jdbc` / `127.0.0.1:<端口>` / `dsn` → **失败**（前端只与 Java `/api/v1`、`/ws` 通信，`NFR-MAINT-5`）；**判据须可满足（不得裸关键字误伤同名标识符）**：`127.0.0.1` 须限定为**带端口的端点形态**且检索范围限定在前端源码扩展名（`.js` / `.html` / `.css`）；`jdbc` / `dsn` 须加词边界，**不得**命中注释、文案与含同名字段的标识符；
  5. **退出码语义**：`0` = 无命中；`1` = 命中；`2` = 用法/前置错误；
  6. **自测三条（可复跑）**：① 干净前端树 → **`0`**；② 置入空 `package.json`（或 `node_modules/` 目录）→ **`1`**；③ 在 `third_party/` 或 `build_*/` 下置入 `package.json`、以及在文案中写入 `dsn` 字样 → **不得命中**（`0`）。
- **产出**：`tools/arch-check/check_frontend.sh`
- **备注**：M2 首周批；与 `T-017`（前端基座·零构建链）衔接。**落点说明**：`T-031` 未含 `E6`，`T-060`/`T-088` 为判据**消费方**（全量审查），本任务为 `E6` 的**唯一交付与验收单元**。`E2`（`scan_cpp_sql.sh`）由 `tech-lead-19` 另行落地；`E5`（`scan_java_sql.sh`）已落地（`ARCHITECTURE.md` §3.0.2 脚注）。

### T-094 交付 RISK 模块剩余 10 张表 DDL 与 DAO（M3 窗口）

- **需求追溯**：`FR-RISK-1~19`
- **负责人**：**backend-engineer#java**（原 `#java` 口径；2026-09-21 编排者指派；本任务承接 `T-061` 收窄后撤出的余表）
- **优先级**：P0
- **依赖**：T-004
- **估时**：18 人时（**折算依据**：`T-061` 原估 20 人时覆盖 `TB-RISK-1~11` 共 11 张表 ≈ 1.82 人时/表；首增量 `t_param_dispatch` 单表已由 `T-061` 交付并计 2 人时，余 10 表 = 20 − 2 = **18 人时**；RISK DDL 工作总量守恒 20 人时；全项目口径见 §5 合计行）
- **来源**：新增（2026-09-20，`T-061` 范围收窄分摊）
- **状态**：TODO
- **验收标准**：
  1. `docs/DEPLOY/schema/m3_risk.sql` **续写**剩余 10 张表（`TB-RISK-1`/`3`~`11`，即 `t_risk_threshold`/`t_mirror_watermark`/`t_risk_alert`/`t_risk_disposal`/`t_abnormal_order_stat`/`t_risk_metric_snapshot`/`t_post_risk_report`/`t_risk_violation`/`t_limit_usage_stat`/`t_annual_scale`），最终该文件 `CREATE TABLE` = **11**；
  2. 与 `DATA_MODEL.md` v1.3.2 §2.8 / §4.3 逐列逐约束一致；**`TB-RISK-1 t_risk_threshold` / `TB-RISK-11 t_annual_scale` 的作用域哨兵值具名 `CHECK`（`ck_risk_threshold_scope_sentinel` / `ck_annual_scale_scope_sentinel`）须逐字采用 §4.3 权威 DDL**；
  3. 对应 `DmRisk*Dao` 齐备，逐列一致性测试 0 差异；
  4. `t_risk_threshold` 支撑「默认值不得低于 `INDEX.md` §9.1 口径」的存储表达。
- **产出**：`docs/DEPLOY/schema/m3_risk.sql`（续写）、`java/src/main/java/com/hedgeguard/dao/impl/DmRisk*Dao.java`
- **备注**：**M3 窗口**（依赖仅 `T-004`，技术上可与 M1/M2 并行，但按编排者裁定归 M3 排期）。⚠️ **DM8 实机建表未执行**（环境无 DM8 实例，归 `T-052`）。下游 `T-063`/`T-064`/`T-065` 依赖本任务交付的风险表。 **（2026-09-21 编排者指派）**负责人定为 `backend-engineer#java`（依原口径；`#java` 的 `T-098`/`T-102` 已收口，负荷可容纳）。**状态仍 `TODO`**（M3 窗口）。

### T-095 `E1`~`E6` 六条判据脚本形态 / 退出码语义一致性收口（原 `E2` 按 `E5` 同法收紧）

- **需求追溯**：`ARCHITECTURE.md` §3.0.2 `E1`~`E6`, §3.13, `NFR-SEC-9`, `NFR-COMPAT-1`, `NFR-PERF-1`, `PROJECT.md` §10-29；承接 `docs/TEST_RESULT.md` §`T-090`~§`T-093` 节（md5 `92e80b35…` / 1215 行，**以实时 `md5sum` 为准**）披露的 `TE-AC-002` / `TE-AC-003` / `TE-AC-004`
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：无（`tools/` 与 C++ 源码不冲突，可并行）
- **估时**：**8 人时**（**第 10 轮由 4 人时上修**，扩为六脚本一致性收口并承接三项缺陷；依据见「备注」估时判据）
- **来源**：新增（`E2` 判据与已修订的 `E5` 同构化；**第 10 轮扩范围**为 `E1`~`E6` 六脚本一致性收口，兼收 `TE-AC-002`/`TE-AC-003`/`TE-AC-004`；M2 窗口）
- **状态**：**DONE**（2026-09-21；**判据 = 授权 + 独立验证**）：**三轮独立复核**（一轮 / 二轮 / **三卷**，复核者 `te-verify-archcheck`；`docs/TEST_RESULT.md` §`T-090`~§`T-093`〔md5 `92e80b35…` / 1215 行〕、§「`T-095` 二轮独立复核」〔md5 `14abe6ea…` / 1388 行〕、§「`T-095` 三卷」〔md5 `f92f82752d7edac5646a069f3bdde0c8` / 1514 行〕，**均以实时 `md5sum` 为准**）**主体判据全过**：`TE-AC-001`~`TE-AC-004` 逐项闭合、`E5` 未误改、一环 40 项真阳零非预期回归、架构一致性 9/9、零残留；`R2-1`（低，E4 指针/引用返回类型定义误报）经三卷**枚举 11 种定义形态全部收口**。**残余 `R3-1` / `R3-2` / `R3-3`（均低）** 经**编排者 2026-09-21 裁定「以显式披露收口、不改检测逻辑」**并已落地 —— `tools/arch-check/scan_lock_free.sh` 文档头**仅注释块 +10 行**、**判定段（`:110`–`:196`）md5 `4bb442c25983144a54ed9e3f2d627ed6` 改前改后逐字节相同**（检测逻辑零变化），`docs/ARCHITECTURE.md` `:321`/`:322`/`:2683` **原位改写（+0 行）**，`bash -n` 通过，**真仓库六脚本 `exit` 全 0**；其中 **`R3-2` 系「真阳放松」**（`expr * name(` 漏判），裁定为「修误报的对称代价、取舍方向可接受」，**已在架构 `:321` 与脚本头如实写明、不回避**。**交付留档**：六脚本一致性收口 + 架构 `v1.5.10`（`docs/ARCHITECTURE.md`；**锚点以实时 `md5sum` 为准**））
- **验收标准**：
  1. **落点**：`tools/arch-check/scan_cpp_sql.sh`（`E2` 判据脚本）；
  2. **注释预处理**：扫描前先剥离注释与字符串字面量（同 `E5` 的 `strip_java_comments.awk` 思路，产出对应 `strip_cpp_comments.awk` 或等价预处理），**不得**因注释/文案中含 `jdbc` / `odbc` 而命中；
  3. **② 按「调用形态 / 目录」分域**（对齐修订后 `E5` 的 `②a`/`②a2`/`②b`/`②c` 结构）：C++ 源码中「SQL / 数据库客户端 API 调用形态」与「目录分域」分别判定；**豁免** `cpp/third_party/**`、`cpp/build*/**`、`_deps/**`；
  4. **判据须可满足（不得裸关键字误伤同名标识符）**：**不得**因命名中含 `jdbc` / `odbc` 字样的自有标识符或注释而命中；
  5. **退出码语义**：`0` = 无命中；`1` = 命中；`2` = 用法/前置错误；
  6. **自测三条（可复跑）**：① 干净 C++ 树 → **`0`**；② 注入真实 SQL/DB 调用形态的探针 → **`1`**；③ 仅注释/字符串中出现 `jdbc` 字样 → **`0`**。
  7. **`E4` 方法名误报收口（`TE-AC-002`，中）**：`read` / `write` / `send` / `recv` 等**须限定为调用形态**（`::send(` / `.send(` / `->send(` 等），**不得**裸词命中**方法名/标识符**（如 `struct S{int read(int);}; s.read(3)` **不得**命中）——`scan_lock_free.sh` 订正后须以既有探针/单测复验；
  8. **退出码语义四脚本统一（`TE-AC-003`，中）**：**显式传入**的不存在路径 ⇒ 退 **`2`**（用法/前置错误）；**默认路径**不存在（组件未铺开）⇒ 退 **`0`**（SKIP）；二者**必须可区分** —— 这使 `T-090`~`T-093` 验收标准 4/5③「目标路径不存在 → `2`」成为**可满足**；`scan_lock_free.sh` / `check_frontend.sh` / `scan_cpp11.sh` 三者行为一致（现 `scan_lock_free.sh` / `check_frontend.sh` 对显式不存在路径退 `0`，与 `scan_cpp11.sh` 退 `2` 不一致）；
  9. **头注释订正（`TE-AC-004`，低·表述）**：订正 `scan_cpp11.sh` 头注释「…故不产生漏报」的**不实表述**（实测 `std::make_` + 换行 + `unique<int>(1)` 退 `0`）；
  10. **`E3`/`E4` 判定排除非代码上下文（`TE-AC-001` 同根因）**：`E3`/`E4` 的扫描**须先剥离注释与字符串字面量**再判定；**连带**须同步订正 `docs/ARCHITECTURE.md` §3.0.2 `E3`/`E4` 的落点描述（把「先剔 C++ 注释」扩为「先剔 C++ 注释**与字符串字面量**」）——**架构同步属 `tech-lead` 写域**（本任务只提同步需求，**不得**自改架构）。
- **产出**：`tools/arch-check/scan_cpp_sql.sh`（`E2`，+ 注释预处理脚本）**+** `E1`/`E3`/`E4`/`E6` 四脚本（`check_no_db_links.sh` / `scan_cpp11.sh` / `scan_lock_free.sh` / `check_frontend.sh`）的**形态 / 退出码语义一致性订正** + `scan_cpp11.sh` 头注释订正
- **备注**：M2 窗口。**缘由**：现 `scan_cpp_sql.sh` 仍为裸关键字（含 `jdbc`/`odbc`）、无注释预处理，**与已收紧的 `E5` 不同构**；当前恰好 0 命中故不红，但 C++ 侧测试落地后**必然复现同类误报**——技术负责人建议、编排者**升级为任务**（防"已知隐患未落任务表 → 后来者重复发现"）。消费方 `T-060`/`T-088`（`E1`~`E6` 全量审查）、`T-031`（接入构建与 CI）。 **📌 第 10 轮扩范围（编排者 2026-09-21 裁定）**：本任务扩为 **`E1`~`E6` 六条判据脚本形态/退出码语义的一致性收口**，并登记为 `docs/TEST_RESULT.md` §`T-090`~§`T-093` 节披露的 **`TE-AC-002` / `TE-AC-003` / `TE-AC-004` 三项缺陷的唯一承接者**（`TE-AC-001` 经编排者裁定为**非缺陷**；其**同根因**——`E3`/`E4` 须排除字符串字面量——并入本任务第 10 项）。**估时判据（项目经理 2026-09-21 判断）**：原 **4 人时**为「`E2` 单脚本同构化」；本轮增至 **四脚本退出码语义统一 + `E4` 调用形态收口 + `E3`/`E4` 非代码上下文剥离 + `scan_cpp11.sh` 头注释订正**（跨 **4** 脚本 + 新增探针/单测）⇒ **上修至 8 人时**（**+4**）。**架构同步（`tech-lead` 写域）**：`docs/ARCHITECTURE.md` §3.0.2 `E3`/`E4` 落点描述须同步订正（本任务只提同步需求，**不改架构**）。

### T-096 为 `PageQuery` 增加「缺省排序方向」重载（向后兼容）+ **稳定排序**（多键 / 唯一 tie-break）

- **需求追溯**：`ARCHITECTURE.md` §4.1.1「分页默认排序方向」约定块（`v1.5.7` 新增；登记时点 `v1.5.8`，**以实时 `md5sum` 为准**）；`FR-PLAN-4`（默认按创建时间倒序，`REQUIREMENTS/PLAN.md:155`）；**`FR-PLAN-15` 处理规则（分页排序稳定性，`PLAN.md:322`；引用时点，以实时 `md5sum` 为准）**
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：无
- **估时**：**3 人时**（**本轮由 2 人时上修**，追加「稳定排序」能力；依据见「备注」估时判据）
- **来源**：新增（架构 §4.1.1 约定落点；**技术负责人建议编号，项目经理 2026-09-21 登记**）
- **状态**：TODO
- **验收标准**：
  1. **落点**：`java/src/main/java/com/hedgeguard/dao/PageQuery.java`；**新增重载** `of(page, size, sort, defaultSortField, defaultDirection)`，`defaultDirection ∈ {"asc","desc"}`（大小写不敏感；非法值抛 `IllegalArgumentException`）；
  2. **向后兼容**：现 4 参形式 `of(page, size, sort, defaultSortField)` **语义不变**——未传 `sort` 或缺方向时**缺省恒 `ASC`**（即 4 参形式等价于新重载以 `"asc"` 传参）；既有调用点（`web/handler/plan/PlanHandler.java:134`）**无需改动**且行为不变；
  3. **能力**：5 参形式在「未传 `sort`」或「只给字段名不给方向」时，方向取 `defaultDirection`（**可表达倒序**）；`sort` **显式给出方向**时**以 `sort` 为准**（覆盖 `defaultDirection`）；
  4. **单测两例**（`src/test`）：① 5 参传 `"desc"` 且不传 `sort` ⇒ `direction() == "DESC"`、`sortField() == defaultSortField`；② 4 参形式不传 `sort` ⇒ `direction() == "ASC"`（**回归护栏**）；
  5. **稳定排序（本轮追加）**：`PageQuery` 须支持**稳定排序** —— **多键**（`sort` 接受多字段，如 `"f1,dir1,f2,dir2"`）**或**自动末键追加**唯一 tie-break**（如主键），使**同一排序值跨页不重复、不漏行**；**4 参形式语义不变**（单字段 + 缺省 `ASC`）；**须有单测**：构造两个**同排序值**元素，断言**翻页前后无重复 / 无遗漏**（**当前单列 `ORDER BY` 下该用例必红**，即反退化自证）；
  6. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/dao/PageQuery.java`（新增重载；`sortField` / `ascending` 语义不变）+ 对应单测；**本轮追加**：`PageQuery` **稳定排序**支持（多键 / 唯一 tie-break）+ 对应单测
- **备注**：**向后兼容的新增重载**——**不改** 4 参形式语义（架构 §4.1.1 明写「4 参形式的缺省仍是 ASC」）。**边界**：本任务**只**动 `PageQuery` 公共件与其单测；**不得**顺手改动 §8.2 的 `PlanService` / `HedgePlanDao` / `Bootstrap`（§8.2④ 写死边界：`PlanService`+DAO+装配由**同一后端实例**改，且「**不得**顺手动其他模块的分页或 `PageQuery` 公共件」——此禁令约束的是§8.2 装配任务，本任务即 `PageQuery` 的**唯一授权改动单元**）。**根因与现状**：`PageQuery.java:62` 的 `boolean asc = true` 使调用方不传方向时**恒升序**，与多数列表 FR 的「最新在前」相反。`FR-PLAN-4` 曾因此出现**真实缺陷 `BUG-001`**（`docs/BUGS/BUG-001.md`，由 `backend-engineer#java` **2026-09-21 00:37 提交**、**状态「已修复，待复检」**——修复落在 **handler 层**：`PlanHandler.java:50` 增私有 `DEFAULT_SORT = "created_time,desc"` 兜底，`BUG-001.md` 明写「`PageQuery.java` md5 未变（本单不动公共件）」）。故本任务为**根因②（公共件缺「缺省方向」能力）**的收口，属**新增能力**，**不重复修 BUG-001**（该单已在 handler 层兜底闭环待复检）。**时点纪律**：`BUG-001` 结论以 `docs/BUGS/BUG-001.md` **实时内容**为准；引用架构锚点一律**以实时 `md5sum` 为准**。**handler 侧兜底保留（编排者 2026-09-21 裁定）**：`PlanHandler.java:50` 的 `DEFAULT_SORT = "created_time,desc"`（即 `BUG-001` 的修复）**保留、不迁移**——属**有意冗余**：handler 侧显式带方向，是对 `PageQuery` 缺省方向任何改动的**回归保护**（删掉反而降低安全性）。**📌 本轮追加「稳定排序」要求（来源 = `T-103` 交付披露 (b)，编排者 2026-09-21 裁定）**：`T-103` 分页化后 `ORDER BY` 仅**单列**（`exec_deadline ASC`），而旧非分页版为 `exec_deadline ASC, id ASC` ⇒ **同 `exec_deadline` 值跨页可能重复或漏行**（经典分页缺陷，**绿测不会暴露**）；根因 = `PageQuery` 的 `sort` 只支持**单字段**（`field,asc|desc`）。故本任务须一并支持**稳定排序**（**多键**或**自动补唯一 tie-break**）。**估时判据（项目经理独立判断）**：原 **2 人时**为「缺省方向重载 + 2 例单测」；本轮追加**多键 / 唯一 tie-break**，涉及 `sort` 解析（多字段）、`ORDER BY` 多列拼装与**跨页稳定性单测** ⇒ **由 2 人时上修至 3 人时**（**+1**）。**依赖关系（本轮确立）**：**`T-103` 其分页排序的稳定性依赖本任务**（`T-103` 当前无 tie-break）；`T-103` 已改分页信封，**本任务须在其交付后收口**（`T-103` 已交付、待独立复核）。 **📌 其跨页稳定性判据当前为红（来源 = `T-103` 独立复核者实测并复现；`docs/TEST_RESULT.md` §`T-103` 节；**以实时 `md5sum` 为准**）**：`T-103` 分页版 `ORDER BY exec_deadline ASC` **单列、无 tie-break** ⇒ 15 条同 `exec_deadline`、`size=10` 时 page1 `[1..10]`、page2 `[5,4,3,2,1]`，**跨页重复 `id=[1..5]`、漏行 `id=[11..15]`**；**本任务验收 5 的跨页稳定性单测当前应为红** ⇒ **`T-096` 落地后该判据必须转绿**，否则不得判完成。

### T-097 `ApiRoutes` 升级为冻结 `(方法, 路径)` 对 + 一致性判据改等值断言

- **需求追溯**：`ARCHITECTURE.md` §4.1.1「路由声明与判据的目标形态」块（`v1.5.7` 新增；登记时点 `v1.5.8`，**以实时 `md5sum` 为准**）；`FR-PLAN-4`（`/plan` 路径已注册、缺 `GET` 方法逃逸）
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：无
- **估时**：4 人时
- **来源**：新增（架构 §4.1.1 约定落点；**技术负责人建议编号，项目经理 2026-09-21 登记**）
- **状态**：TODO
- **验收标准**：
  1. **落点**：`java/.../web/ApiRoutes.java`（**声明面**）+ `ModuleRoutesConsistencyTest`（**判据面**）+ `ARCHITECTURE.md` §4.1.1 方法列按需细化（**架构侧由技术负责人改；本任务只提「需细化的方法清单」，不得自改架构**）；
  2. **声明面**：`ApiRoutes` 由「只冻结路径」升级为**声明 `(方法, 路径)` 对**（`ApiRoutes` 是 §4.1.1 路由表的**代码镜像**）；
  3. **判据面（等值断言）**：`ModuleRoutesConsistencyTest` 断言 **「运行时已注册的 `(方法,路径)` 集合 == `ApiRoutes` 声明集合」**——**等值**（非 `⊆`）：**少注册一条即红**（保 `GAP-1` 的保护）、**同步 `ApiRoutes` 即自动跟随**（不必手改常量）；
  4. **基线**：以现行路由口径 **48 个 `(方法, 路径)` 对 / 37 个路径**（业务 **46 对 / 36 路径**；`+1` 来源 = `FR-PLAN-4` 收口新增 `GET /plan`）为等值断言的初始期望；**先修数据、后改判据**——`GET /plan` 须**已在 `ApiRoutes` 与运行时注册两侧同时存在**，否则等值断言先红（即本任务须先补齐声明面数据）；
  5. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/.../web/ApiRoutes.java`、`ModuleRoutesConsistencyTest`、§4.1.1 方法列细化清单（**交技术负责人**）
- **备注**：**等值断言同时防退化（少注册即红）与免漂移（同步 `ApiRoutes` 自动跟随）**。**背景**：`FR-PLAN-4` 的缺口（`/plan` 路径已注册、缺 `GET` 方法）逃过了所有**路径级**检查，靠人工与 S7 审查才发现（架构 §4.1.1「判据覆盖边界」块明写「**在 `T-097` 落地前现存路由核对为路径级**，方法级缺口由 §10 追溯矩阵 + S7 审查兜底」）。**路由集合的唯一事实源 = `ApiRoutes`**（`docs/REVIEW.md` `CR-001` 口径，**以实时内容为准**）。**不触 `CH-xxx`**（架构明写 `ApiRoutes` 是**本节表的代码镜像，非 HGCP 协议本体**）。**估时口径**：与同族判据落点任务 **`T-090`~`T-093`（各 4 人时）/ `T-095`（4 人时）** 可比——公共件升级 + 判据改等值断言 + 全量回归。 **📌 决定性证据（2026-09-21，独立复核 `te-verify-t010` · `EXP-T010-2`）**：**实证 —— `T-010` 的 8 条已声明未注册端点**（`/auth/user`、`/auth/role`、`/auth/position-group`、`/auth/authorization`、`/auth/sub-account-perm`、`/auth/change-log`、`/auth/unauthorized-attempt`、`/auth/password`）**未被任何 CI 判据捕获** —— `ModuleRoutesConsistencyTest` 的覆盖下限**硬编码 `AUTH=2`**（`ModuleRoutesConsistencyTest.java:51-53`），对「少注册」**结构性失明**；本任务的**等值断言**可同时抓「多注册」与「少实现已声明路径」（故 `T-097` 是该缺口的**判据侧收口**）。来源：`docs/TEST_RESULT.md` §`T-010.7` `EXP-T010-2` / §`T-010.3`（**以实时 `md5sum` 为准**）。

### T-098 实现 `CROSS_READONLY` 跨账户只读强制点（写操作拒绝 + 留痕）

- **需求追溯**：`ARCHITECTURE.md` §8.2「`NFR-SEC-7` 服务端强制点」③（`v1.5.7` 新增；登记时点 `v1.5.8`，**以实时 `md5sum` 为准**）；`FR-AUTH-13` 验收 3；`R-AUTH-8`；`NFR-SEC-7`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：无
- **估时**：3 人时
- **来源**：新增（架构 §8.2 强制点落点；**技术负责人建议编号，项目经理 2026-09-21 登记**）
- **状态**：**DONE（独立复核通过）**（实现 = `assertSubAccountWritable`（`SubAccountVisibility` **新增 `default`** + `AuthService` **新增同名覆盖**）× 各服务写面调用点改向（`PlanService`/`SpotService`/`VarietyService`）；越权 ⇒ **`10004`** + **`t_unauthorized_attempt` 留痕**；**独立复核 `te-verify-t115` 判「通过（无条件）」**（2026-09-21；原列唯一条件已消解 —— 该测试文件差异属**已登记的树演进/瞬态**，见 `BOARD.md` §5 第 24 轮行与下设备注）（真实 `AuthService` 逐条绕过全被拦、**读面仍放行**无误拒、独立构建 `421/0/0/0`、自设 4 组反退化全红、替身无假绿）。**判据 = 授权 + 独立验证** ⇒ `DONE`）
- **验收标准**：
  1. **强制点**：命中 `perm_type = 'CROSS_READONLY'`（§8.2① 取数规则）的用户，调用写操作 `submit` / `approve` / `reject` / `withdraw` / `freeze` / `unfreeze` / `terminate` / `extend` **一律拒绝**（**服务端强制**，非客户端校验；依据 §1.2 通则「控制性需求的服务端强制点」——**客户端校验与字段留痕均不构成强制点**）；
  2. **拒绝语义**：错误码 **`10004`**（子账户数据权限不足，403，§4.3.1），响应信封 `{code,message,...}` 与统一错误映射器一致；
  3. **留痕**：每次拒绝落 **`t_unauthorized_attempt`**（`R-AUTH-8`），字段齐备（用户 / 操作 / 时间 / 目标子账户或计划）；
  4. **测试**：≥ 1 例**正分支**（`CROSS_READONLY` 调写操作 ⇒ `10004` **且**留痕行写入）+ 1 例**反分支**（`LIMITED` 或无跨账户授权用户写操作**不受**该拦截）；
  5. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`CROSS_READONLY` 拒绝判定 + `t_unauthorized_attempt` 留痕落点（**`#java` 写域**）+ 对应测试
- **备注**：**与 §8.2 主落点（①③④ `GET /plan` 读取面隔离）分离**——本任务**只**做**写操作**的只读强制点；`PlanService` / `HedgePlanDao` / `Bootstrap` 的装配边界见 §8.2④（须 **`#java` 单实例**，且**不得**两实例并行改同一类）。**估时判据（项目经理独立判断）**：介于 **`T-096`/`T-099`（各 2 人时，「改动面小 + 含单测」）** 与 **`T-097`（4 人时，「公共件升级 + 判据改等值断言 + 回归」）** 之间——本任务 = 跨 **8 类写操作**的**集中式**守卫 + 留痕落库 + 双向测试，**3 人时**与建议值一致。⚠️ **条件性上修**：**若守卫无法集中、须逐 handler 添加**，应上修至 **4 人时**（已在交付报告中要求写明所选实现形态）。 **（2026-09-21 实现落地与重复立项更正）**：本任务由**编排者另立的 `T-115`**（**重复立项**，见 `T-115` 条）实际实施；**成果归本任务**。实现形态 = **集中式守卫**（接口 `default` + `AuthService` 覆盖 + 各服务私有助手），**非**逐 handler 添加 ⇒ 本条备注原有「**条件性上修至 4 人时**（若守卫无法集中、须逐 handler 添加）」**不触发**，估时维持 **3 人时**。 **（2026-09-21 复核补登）**：`SpotService` 写面为 **11 个调用点**（含 `changeDocSubAccount` 的两处：源/目标各一）；**读面仅 1 处**（`exportExposuresCsv`）且**仍放行** `CROSS_READONLY`（**误拒已排除**）。另：「Plan 读面走 DAO scoping、未走统一断言」经复核判**非缺陷**（两套机制均合规，`FR-AUTH-13` 验收1 由 scoping 满足）。 **（2026-09-21）`te-verify-t115` 报的唯一「条件」已由编排者关闭**：复核者指出某测试文件 `T112MissingSubAccountIdHandlerTest`（曾出现于 `e8f59e52…`/315 文件态）在现行树不存在、无法判定去向。**编排者裁定**：该文件系**另一实例于 14:00 前后加入、并于 14:10 前由同一实例自行撤回** —— 证据 = 树在该删除后**回到与增前逐字节相同**的 `7e316b27be4644378c0525a1f4417063`（314 文件）。⇒ **既非有意删除、也非并写丢失**；记忆中的 `e8f59e52…`/424 即该**瞬态**。**无需任何人补查、不构成缺陷。** **（2026-09-21 复核升级 + 来源澄清）** ①复核由『有条件通过』**升级为『通过（无条件）』**（唯一条件已消解，见上）；②**如实记明交付性质**：`T-098` 的原文侧重「**新实现**强制点」，而本次实际交付是**修正既有 `assertSubAccountVisible` 的写面放行**（该断言自 `T-102`/`T-110` 起已被写路径调用）——**验收面逐条被满足**（① 写操作一律拒绝服务端强制／② `10004`／③ `t_unauthorized_attempt` 留痕／④ 正反双向测试／⑤ 冻结点全绿），故**收受成立**；但**不得记为「从零新实现」**。③交付范围为其**超集**（另覆盖 SPOT/Variety 写面）。④**审计诚实项**：旧锚 `7e316b27…` **不可重跑**（仓库无 VCS），其 `420/0/0/0` 系**引述**而非复核者复算 ⇒ 后续表述宜用「**相对最近取样的 Δ**」或保留旧树快照。

### T-099 处置 `DmUserSubAccountPermDao.scopeOf` 不读 `perm_type`（补分支或弃用指向）

- **需求追溯**：`ARCHITECTURE.md` §8.2「`NFR-SEC-7` 服务端强制点」②机制订正段（`v1.5.8` 订正；**以实时 `md5sum` 为准**）；`FR-AUTH-13`；`NFR-SEC-7`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：无
- **估时**：2 人时
- **来源**：新增（架构 §8.2 机制订正段；**技术负责人建议编号，项目经理 2026-09-21 登记**）
- **状态**：TODO
- **验收标准**：处置**二者择一**（须在工单交付报告中**写明所选分支与理由**）：
  - **(a) 补 `perm_type` 分支**：给 `scopeOf` 增加 `perm_type` 读取分支——命中 `CROSS_READONLY` ⇒ 返回「**无 scope**」信号（与 `SubAccountScope.ALL_ACCOUNT_SENTINEL` 哨兵语义对齐），使其**可安全作取数入口**；
  - **(b) 弃用指向**：给 `DmUserSubAccountPermDao.scopeOf` 加 `@Deprecated`，javadoc **指向 `AuthService.visibleSubAccountIds`**（唯一带 `perm_type` 语义的取数入口）；
  1. **不改**授权查询的对外行为之外的东西（若选 (a)，须补单测：`CROSS_READONLY` 行 ⇒ 「无 scope」信号；`LIMITED` 行 ⇒ 真实范围）；
  2. **复核命令（须写入交付报告）**：`grep -n "permType" java/src/main/java/com/hedgeguard/dao/impl/DmUserSubAccountPermDao.java` —— 结果须能证明 `perm_type` **已进入判定逻辑**（选 (a)）或**已标注废弃并指向正确入口**（选 (b)）；
  3. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/dao/impl/DmUserSubAccountPermDao.java`（(a) 补分支 或 (b) `@Deprecated`）+ 对应测试（若选 (a)）
- **备注**：**现状（技术负责人在 `v1.5.8` 实测，登记时点；以实时 `md5sum` 为准）**：`scopeOf`（`:108–122`）**全程不读 `permType`**（该字段仅见于 `:66` bind 与 `:78` mapRow）⇒ 它把 `CROSS_READONLY` 行**当普通授权行**并入 `allowed`：取真实子账户 ⇒ **跨子账户可见性失效**；取哨兵 `0` ⇒ 对 `t_hedge_plan`（行级归属列、值域 `>0`）**恒空** ⇒ **两种取值下 `FR-AUTH-13` 均静默失效**。故它是「**看起来可用、实际会让 `FR-AUTH-13` 失效**」的入口（可复用纪律见 `feedback_mechanism_probe`：存在性/签名/javadoc ≠ 机制可用）。**唯一实现路径**见 §8.2②：`AuthService.visibleSubAccountIds(userId)`（`java/src/main/java/com/hedgeguard/service/AuthService.java:502`，**已实现**）→ 由**调用方**判定返回集合是否含哨兵 `0`（`SubAccountScope.ALL_ACCOUNT_SENTINEL`）→ `SubAccountScope.of(...)`（集合为空 ⇒ 恒假条件）→ `AbstractDmDao.findPageScoped(query, scope, where, args)`（`dao/impl/AbstractDmDao.java:250`，已就绪）。**⚠️ 编号出场核对（项目经理实测）**：`T-099` 编号**仅出现在技术负责人的落地建议中**——`ARCHITECTURE.md` 正文（2729 行 / `ebed9711…`，登记时点）**未含 `T-099` 字样**，正文只写 `T-096`/`T-097`/`T-098`（见 `:2044` 与 `:2682`）；故本编号**由项目经理登记后方为事实**（与派单口径一致）。

### T-100 装配 `GET /plan` 读取面子账户隔离（列表 + 详情）

- **需求追溯**：`ARCHITECTURE.md` §8.2「`NFR-SEC-7` 服务端强制点」①③④（引用时点 `v1.5.8`；**以实时 `md5sum` 为准**）；`FR-PLAN-4` 验收 4；`FR-AUTH-13`；`NFR-SEC-7`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-016
- **估时**：4 人时
- **来源**：新增（架构 §8.2①③④ **主落点编号补登记**；编排者 2026-09-21 裁定立项，项目经理登记）
- **状态**：**DONE**（判据 = **授权 + 独立验证**）——独立复核已完成并通过（`docs/TEST_RESULT.md`「T-100 专项独立复核」节 / 复核者 `te-verify-t100`，2026-09-21；**引用时点，以实时 `md5sum` 为准**）：基线 **300 / 0 / 0 / 0**（clean、无排除）且**共享树源码零改动**；**自建 11 例探针**（非产出方用例）验证三条隔离分支 + **空集 fail-closed**；**真实 DAO SQL 注入验证**；**两向反退化变异**（变异 A 短路 scope 分支 → 6/8 红；变异 B「空集也当不过滤」的危险默认 → 3 红）；**无存在性泄露**（不可见 ≡ 不存在，同一 `ErrorCode` 与同一文案）；9 条交叉核对**全部符合**、无法判定 0 ⇒ 满足本项目 `DONE`（授权 + 独立验证）
- **验收标准**：
  1. **服务层签名**：`PlanService.listPlans` 增**调用者身份入参**（`operatorId`）——现签名不含调用者身份（`ARCHITECTURE.md` §8.2③ :2048 登记时点）；
  2. **取数入口** = `AuthService.visibleSubAccountIds(userId)`（`java/src/main/java/com/hedgeguard/service/AuthService.java:502`，**已实现**）→ 由**调用方**判定：返回集合**含哨兵 `0`**（`SubAccountScope.ALL_ACCOUNT_SENTINEL`，由 `perm_type='CROSS_READONLY'` 显式映射）⇒ **不构造 scope、不加过滤**；**空集 ⇒ 恒假条件**（**严禁**退化为「不过滤 = 全可见」）；
  3. **DAO 层**：`HedgePlanDao` / `DmHedgePlanDao` 补 **scoped 分页**与**按 id 读取**方法，消费 `AbstractDmDao.findPageScoped(query, scope, where, args)`（`dao/impl/AbstractDmDao.java:250`，已就绪）——**DAO 层不新增 SQL 分支、Service 层不自行拼 `IN (...)` 字符串**（`SubAccountScope` 集中构造）；
  4. **装配**：`web/Bootstrap.java` 完成装配（受 `hg.db.url` 开关保护）；
  5. **详情同接过滤**：`getPlan`（`GET /plan/{id}`）**同接该过滤**（越权取单条 = 同性质缺陷）；
  6. **四类用例**（`src/test`）：① `LIMITED` 只见己方子账户；② **无授权 ⇒ 空集**（**不得返回全部**）；③ `CROSS_READONLY` ⇒ **不过滤**（跨子账户可见）；④ 详情读**不可见计划 ⇒ 被拒**；且**移除过滤后上述用例必红**（**反退化自证**）；
  7. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/service/PlanService.java`（增 `operatorId`）、`java/src/main/java/com/hedgeguard/dao/HedgePlanDao.java` + `dao/impl/DmHedgePlanDao.java`（scoped 方法）、`java/src/main/java/com/hedgeguard/web/Bootstrap.java`（装配）+ 对应测试
- **备注**：**本任务是 §8.2 的「主落点」，此前已由编排者派出实现（`backend-engineer` 进行中）但无任务编号承载**（**追溯链断裂**）——故本编号为**补登记**，由编排者 2026-09-21 裁定立项。**⚠️ 写者边界（§8.2④ :2053–2054 登记时点）**：`Bootstrap` 属 `web/` 框架类、**写权唯一 `#java`**；`PlanService`（取 scope）＋ `HedgePlanDao`（scoped 方法）＋ `Bootstrap`（装配）**三项必须由同一后端实例改**；只准动「`PlanService` 读取路径 + `Bootstrap` 装配 + 该端点测试」，**不得**顺手动其他模块的分页或 `PageQuery` 公共件（`PageQuery` 见 `T-096`）。**该约束仅覆盖本任务 §8.2 装配的三处（`PlanService` 取 scope + `HedgePlanDao` scoped 方法 + `Bootstrap` 装配）之原子性；`PlanService` 其余改动的属主仍为 `#java2`（`T-015`/`T-016`）。**（编排者 2026-09-21 裁定）**⚠️ 禁止**：**不得**以 `DmUserSubAccountPermDao.scopeOf` 作读取入口（`scopeOf` **不读 `perm_type`** ⇒ 使 `FR-AUTH-13` 双向静默失效；该陷阱的处置归 **`T-099`**）。**串行顺序（编排者裁定 2026-09-21；`T-101` 以本任务**相邻残余缺口**身份插于**本任务之后**、`T-103`（补分页/筛选/排序）随后、`T-102` 以**写面强制补齐**身份插于**`T-103` 之后、`T-099` 之前**）**：`T-100 → T-101 → T-103 → T-102 → T-099 → T-098 → T-096 → T-097`（前五项同属 `#java` 且动公共件／服务层／读取路径，**同一时刻只允许一个实例写**；`T-097` 最后做——它是**判据升级**，应「收束」前面所有新端点，而非在端点尚在变动时改判据）。**不并入 `T-016` 的理由（裁定）**：`T-016` 现为「**已完成，待独立复核**」，回改会污染本轮刚建立的 `DONE` 判据；且本项有**独立验收标准**（四类隔离用例 + 反退化），须独立可追溯。**估时判据（项目经理独立判断）**：机制件（`findPageScoped` / `visibleSubAccountIds` / `SubAccountScope`）**均已就绪**，本任务主体为**跨层装配 + 4 类用例 + 反退化自证** ⇒ 与 `T-097`（4 人时）同档、略高于 `T-098`（3 人时）；**取 4 人时**（与裁定建议一致）。⚠️ **条件性上修**：若详情路径须**新增独立 DAO 方法**并为四类用例各补正反两面，应上修 **5 人时**（交付报告须写明实际覆盖面）。

### T-101 装配 `GET /plan/{id}/trade-plans` 读取面子账户隔离

- **需求追溯**：`ARCHITECTURE.md` §8.2③「强制点清单」的**读取面通则**（「其余读取面（`getPlan` 等按 id 读取）**同须**接同一过滤（越权取单条 = 同性质缺陷）」，`ARCHITECTURE.md:2049`；引用时点 `v1.5.8`；**以实时 `md5sum` 为准**）；`FR-PLAN-15` 验收 4（本端点**自身**隔离判据：「子账户 B 的交易计划对子账户 A 的交易岗不可见」，`docs/REQUIREMENTS/PLAN.md:331`）；`FR-PLAN-14` 验收 4（交易计划子账户归属，`PLAN.md:316`）；`R-PLAN-7`（计划与子账户一致性，`PLAN.md:420`）；`FR-AUTH-13`；`NFR-SEC-7`
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-100
- **估时**：3 人时
- **来源**：新增（架构 §8.2③「其余读取面」通则落点；编排者 2026-09-21 裁定**单独立号**，项目经理登记）
- **状态**：**DONE**（判据 = **授权 + 独立验证**）——独立复核已完成并通过（`docs/TEST_RESULT.md`「`T-101` 专项独立复核」节，2026-09-21；**引用时点，以实时 `md5sum` 为准**）：基线 **310 / 0 / 0 / 0**（clean、无排除）；**共享树源码清单 md5（284 `.java` + `pom.xml`）复核前后全等 ⇒ 源码零改动**；**自建 11 例探针**（真实 `PlanService` + 真实 `DmTradePlanDao` + 按 SQL 语义求值的假 JDBC，非产出方用例）；**三向反退化变异**（A 退回无条件查询 → 6/10 红；**B 空集当不过滤 → 2 红**；**C 空 scope 恒真 `1=1` → 2 红**）；真实 DAO SQL 形状与 AND 括号/顺序均核；13 条交叉核对**全部符合** ⇒ 满足本项目 `DONE`（**授权 + 独立验证**）
- **验收标准**：
  1. **服务层签名**：`PlanService.listTradePlans` 增**调用者身份入参**（`operatorId`）——现签名 `listTradePlans(long planId)`（`java/src/main/java/com/hedgeguard/service/PlanService.java:851`，**无调用者身份、无 scope 过滤**，内部直调 `tradePlanDao.findByHedgePlan(planId)`）；
  2. **取数入口** = **复用 `T-100` 已建立的 `SubAccountVisibility`**（`java/src/main/java/com/hedgeguard/service/SubAccountVisibility.java`，`@FunctionalInterface`；由 `AuthService.visibleSubAccountIds(long)` 实现，`AuthService.java:502`）→ 由**调用方**判定：返回集合**含哨兵 `0`**（`SubAccountScope.ALL_ACCOUNT_SENTINEL`，由 `perm_type='CROSS_READONLY'` 显式映射）⇒ **不构造 scope、不加过滤**；**空集 ⇒ 恒假条件**（**严禁**退化为「不过滤 = 全可见」）；`null` **按空集处理**（fail-closed）；**⚠️ 禁止**以 `DmUserSubAccountPermDao.scopeOf` 作取数入口（`scopeOf` **不读 `perm_type`** ⇒ 使 `FR-AUTH-13` 双向静默失效），其处置归 **`T-099`**；
  3. **DAO 层**：`TradePlanDao` / `DmTradePlanDao` 补 **scoped 读取方法**，**消费集中构造件 `SubAccountScope`**（`appendTo(String)`（`SubAccountScope.java:84`）/ `sql()`（`:62`）/ `params()`（`:74`）/ `isEmpty()`（`:48`））拼 WHERE；**DAO 层禁止自行拼 `IN (...)` 字符串**。**✅ 形态裁定（编排者 2026-09-21）= (b)：保持非分页 `List` 形态** —— 现 `listTradePlans` 返回**非分页** `List<TradePlan>`（`TradePlanDao.findByHedgePlan(long)`，`TradePlanDao.java:20`）；理由：① (a) 会把已实现端点的响应信封由 `List` 改 `PageData` ⇒ **契约/前端同步变更，成本远超本任务（3 人时）**；② `FR-PLAN-15` 验收 1 仅要求「列出子账户 A 的待执行交易计划」、**未要求分页** ⇒ 无需求依据支撑 (a)；③ `SubAccountScope.appendTo(...)` 的存在意义**正是**「给不走分页的查询复用集中构造」⇒「DAO 不得自拼 `IN (...)`」在 (b) 下**可满足**，不构成豁免。**⚠️ 升级条件（交付方必须显式回答，不得沉默选一）**：**若交付方读 `FR-PLAN-15`「处理规则」后判定其**明确要求分页**，则改走 (a)** —— 此时**必须同时另立「前端/契约同步」项**（`§4.1.1` / `CONTRACTS` 信封同步），**不得只改后端**；交付报告须写明所选形态与依据。**两种形态**下 **DAO 一律禁止自拼 `IN (...)`**；（项目经理登记时实测：`FR-PLAN-15`「处理规则」**不含分页**、仅「输入」列有「分页」⇒ 现行裁定 (b) 成立。）
  4. **装配**：`TradePlanHandler`（`java/src/main/java/com/hedgeguard/web/handler/plan/TradePlanHandler.java:46`）**传 `ctx.currentUserId()`**（既有 API，见 `web/RequestDispatcher.java:110` 等）；
  5. **四类用例**（`src/test`，与 `T-100` 同构）：① `LIMITED` 只见己方子账户的交易计划；② **无授权 ⇒ 空集**（**不得返回全部**）；③ `CROSS_READONLY` ⇒ **不过滤**（跨子账户可见）；④ 跨子账户的计划 ⇒ **其交易计划不可见**（越权读 ⇒ 空）；且**移除过滤后上述用例必红**（**反退化自证**）；
  6. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/service/PlanService.java`（`listTradePlans` 增 `operatorId`）、`java/src/main/java/com/hedgeguard/dao/TradePlanDao.java` + `dao/impl/DmTradePlanDao.java`（scoped 方法）、`java/src/main/java/com/hedgeguard/web/handler/plan/TradePlanHandler.java`（传 `ctx.currentUserId()`）+ 对应测试
- **备注**：**本项是 `T-100` 的「相邻残余缺口」** —— `T-100` 修了 `GET /plan`（列表）与 `GET /plan/{id}`（详情），本项修 `GET /plan/{id}/trade-plans`（`FR-PLAN-15` 查询交易计划）；**同一类缺陷换端点仍复现**（`TradePlanHandler` → `PlanService.listTradePlans` 无调用者身份入参、无 scope 过滤 ⇒ 可跨子账户读取交易计划），故须**独立可追溯**。**不并入 `T-100` 的理由（编排者裁定）**：本项动 `TradePlanDao` + `listTradePlans` + `TradePlanHandler`，**写域与 `T-100` 不同**；且 `T-100` 已可独立验收（并入会让已交付项无法闭环）。**⚠️ 写者边界**：`TradePlanHandler` 属 `web/` 类、**写权唯一 `#java`**；`PlanService`（取 scope）＋ `TradePlanDao`（scoped 方法）＋ `TradePlanHandler`（装配）**三项必须由同一后端实例改**。**估时判据（项目经理独立判断）**：与 `T-100`（4 人时）同构，但**少一个端点（详情）与一个 DAO 方法**，且**机制件（`SubAccountVisibility`/`findPageScoped`/`SubAccountScope`）已由 `T-100` 建立、本项为复用**（净减），仅**多一处 `TradePlanHandler` 装配**（与 `T-100` 的 `Bootstrap` 装配同量级）⇒ **取 3 人时**（与裁定建议一致、与 `T-098` 同档）。⚠️ **条件性上修**：若选 (a) 改分页形态并须同步前端/契约信封，应上修 **4 人时**（交付报告须写明所选形态与实际覆盖面）。**⚠️ 需求追溯修正（项目经理登记前实测）**：派单给的依据为 `FR-PLAN-4` 验收 4（`REQUIREMENTS/PLAN.md:164`，系 `GET /plan` **列表**的判据）；本端点**自身**的隔离判据实为 **`FR-PLAN-15` 验收 4**（`PLAN.md:331`）——故登记以**端点自身 FR** 为主锚，`FR-PLAN-4` 验收 4 作**同构参照**（同属 §8.2「服务端强制点」同性质）。

### T-102 写面**子账户可见性 / 交易权限**强制补齐（`assertSubAccountVisible` + `assertCanTrade` 未接线 + `withdraw` 发起人校验缺失 + `decomposeTradePlans` 写路径读取未 scope）

- **需求追溯**：`FR-AUTH-9`（禁止企业负责人直接操盘；**操盘拒绝判据为「析取」**，依 `CH-010` —— 「企业负责人标记」与「持有管理层（审批人）角色」**各自独立**，满足**任一**即拒，**不得**要求同时满足）、`FR-AUTH-10`（配置人员授权）、`FR-AUTH-13`（子账户数据权限）；`NFR-SEC-7`（子账户软隔离 · 服务端强制）；`R-AUTH-8`（权限约束）、`R-AUTH-13`（负责人 / 管理层禁操盘）；依据出处 = `ARCHITECTURE.md` §8.2「`NFR-SEC-7` 服务端强制点」（登记时点 `v1.5.8`；**以实时 `md5sum` 为准**）；**事件来源** = `docs/TEST_RESULT.md`「`T-100` 专项独立复核」§`T-100.10` 探索性发现 **`EXP-T100-1`** + 其**补录节**（`te-record-exp1` 独立复跑，§补录.2 / §补录.5 第 3 项；**引用时点；以实时 `md5sum` 为准**）
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-100
- **估时**：**5 人时**（**第 2 轮由 3 人时上修至 4 人时；第 3 轮再纳入 ④ 后上修至 5 人时**，依据见「备注」估时判据）
- **来源**：新增（`T-100` 专项独立复核 `EXP-T100-1` 的同族发现；编排者 2026-09-21 裁定立项、同日**裁定扩范围纳入 `assertCanTrade`**，项目经理登记。**无对应 `BUGS/` 单** —— 已按「缺陷若已有授权任务承载则以任务号为准、不重复建号」处理）；**第 3 轮再扩范围纳入 ④（`decomposeTradePlans` 写路径读取未 scope）** —— **来源 = `T-101` 交付报告「未完成/待裁定 ①」披露**（引用时写明「来源 = `T-101` 交付披露」；**非 `BUGS/` 单**）；因 `PlanService.java` 刚由 `T-101` 改动，**其行号以实时 `md5sum` 为准**（本席登记时实测 = md5 `a18d23b767cbd8e5d1a7433e72f239d6` / **1237 行**，`2026-09-21T06:36:57+0800`）
- **状态**：**DONE**（判据 = **授权 + 独立验证**；**授权** = 编排者 2026-09-21；**复核链** = 首轮 `te-verify-t102`「**通过、0 阻断缺陷**」〔缺陷 `TE-T102-001`（建议级）：②b 未独立成例〕→ 编排者裁定「**须补**」（依据验收 ⑤ 原文「两条单条件**各须独立成例**」）→ `be-t102-fix` 补修 → **delta 复检「通过、0 缺陷」**。**验收证据**：① 写面强制点 = `requireWritablePlan`（`PlanService:1739-1753`）→ 调**写面** `assertSubAccountWritable`（`:1751`），覆盖 **15 写入口**（11 经 `requireWritablePlan` + 4 经 `requireSubAccountWritable`）；`assertSubAccountVisible` **非悬空**（读面 `SpotService:1544`）；② 判据 = **析取**（`AuthService:440` `leader || management`）；③ 走「**修正 javadoc**」支；④ `existingTradePlansScoped`（`:1341`→`:1704`），跨子账户既有量不计入 `used`；⑤ 用例映射齐备（②a/②b **各独立成例**）；⑥ 冻结全量 **`444/0/0/0`**、`@Test` grep `444` == surefire `444`。**反退化**：首轮自设 **4 向变异全灭**（析取→合取 3 红 / 删写面断言 6 红 / 哨兵恒不过滤 2 红 / 放行 `CROSS_READONLY` 2 红）+ delta 自建变异（析取→合取 ⇒ ②a 与新 ②b **双红**、`leaderWithManagementRoleCannotTrade` **存活**〔其测"两条件同满足"，`&&` 下仍拒〕）；复位逐字节同值 + **clean 重跑全绿**。**双向探针**（真实 `AuthService`）：**11 写入口全拒 `10004` + 11 条留痕**；并**证伪误拒**（财务/风控主体对可见子账户 `decomposeTradePlans` 放行 ⇒ 反证 `assertCanTrade` 未被误接）。**外依赖**：`assertCanTrade` 的 M2 真强制点 = **`T-046`（`BLOCKED`）**；M1 期 `src/main` 0 调用为**有意裁定**（`FR-PLAN-14` 主体含财务/风控岗）。**证据章落点** = `docs/TEST_RESULT.md` 章 `# `T-102` 独立复核证据（事后补录，2026-09-21）`（`:2288`–`:2408`，登记时点；**以实时 `md5sum` 为准**））
- **验收标准**：
  1. **接线或替代（择一并写明依据）**：裁定 `AuthService.assertSubAccountVisible`（`java/src/main/java/com/hedgeguard/service/AuthService.java:479`，登记时点）的**正确用途**并将其接入**写路径**（计划写动作的可见性强制点），**或**明确其不应作写面入口并给出替代强制点 —— **不得留下「方法已实现、`src/main` 0 处调用」的悬空守卫**；
  2. **接线或替代（同法，`assertCanTrade`）**：裁定 `AuthService.assertCanTrade`（`java/src/main/java/com/hedgeguard/service/AuthService.java:417`，登记时点；其 javadoc 判据 = `R-AUTH-13` 负责人 / 管理层禁操盘）的**正确接线点**（操盘前置强制点 = 下单写路径）并接线，**或**明确其用途并给替代 —— **同样不得留下「已实现、生产 0 处调用」的悬空守卫**；**⚠️ 并须订正其判据形态（依 `CH-010`）**：实测源码作 `if (leader && management)`（**合取**）⇒ **两个方向均漏**（`leader='Y'` / `management='N'` 放行；`leader='N'` / `management='Y'` 放行）—— 须改为**析取**（满足**任一**即拒；`leader || management`）；
  3. **发起人校验（二者择一，但必须消除不一致）**：`PlanService.withdraw`（`java/src/main/java/com/hedgeguard/service/PlanService.java:639-655`，登记时点）**按 javadoc 实现发起人校验**，**或**修正 javadoc 使之与实现一致；
  4. **`decomposeTradePlans` 写路径读取 scope 的裁定与处理（同族第四项 ④）**：**裁定** `PlanService.decomposeTradePlans`（`java/src/main/java/com/hedgeguard/service/PlanService.java:775`，**登记时点 md5 `a18d23b7…`**）内于 `:786` 仍调用**未 scoped** 的 `tradePlanDao.findByHedgePlan(planId)`（**写路径内读取未做子账户过滤**；与 `T-101` 已修复的 `listTradePlans` **同形残余**）**是否须施加子账户可见性过滤**并**依裁处置** —— **推荐**与 `listTradePlans` 同法复用 `visibleSubAccountIds(operatorId)` + `SubAccountScope` + `findByHedgePlanScoped`（机制件已由 `T-100`/`T-101` 建立）；若判「不须过滤」须给**书面依据**；**不得留下静默的跨子账户读取缺口**；
  5. **补用例覆盖拒绝情形 + ④ 覆盖**：① 越权写（对该计划无子账户可见性）⇒ **被拒**；② `assertCanTrade` 判据形态（依 `CH-010` **析取**）**两条单条件各须独立成例**：②a `is_enterprise_leader='Y'` **单条件** ⇒ **被拒**；②b 持有**管理层（审批人）**角色（`APPROVER` / `is_management_role='Y'`）**单条件** ⇒ **被拒**（②a/②b **均不得以对方同时成立为前提**；**合取实现下此两例必红**）；③ `withdraw` 非发起人 ⇒ **被拒**（若选「修正 javadoc」则 ③ 改为「javadoc 与实现一致性」断言）；④ 若 ④ 裁定须过滤 ⇒ 跨子账户既有交易计划**不计入** `used`（未过滤时该用例**必红**）；且**移除强制/校验后用例必红**（**反退化自证**）；
  6. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/service/AuthService.java`（`assertSubAccountVisible` + `assertCanTrade` 接线 / 标注其正确用途）**或**替代强制点所在类 + `java/src/main/java/com/hedgeguard/service/PlanService.java`（`withdraw` 发起人校验 或 javadoc 订正）+ 对应测试（覆盖三类拒绝 + 反退化）
- **备注**：**本项系 `T-100` 专项独立复核的同族发现**（`docs/TEST_RESULT.md` §`T-100.10`，`EXP-T100-1`；**补录节** §补录.2 / §补录.5 第 3 项）。**四条同族问题**（**「控制看起来有、实际没有」**）：① `AuthService.assertSubAccountVisible`（约 `:479`）在 `src/main` **0 处调用** —— **已实现的守卫未接线**（与 `DmUserSubAccountPermDao.scopeOf` **同形**：方法存在，但没人调）；② 写动作经 `requirePlan`（约 `:1064`）→ `findById`（**不做子账户过滤**）⇒ **写面无子账户可见性强制**；③ `AuthService.assertCanTrade`（`:417`）在 `src/main` **亦 0 处调用**（`:53` 的 `{@link #assertCanTrade}` 系 javadoc 引用、**非调用**）—— 同为「已实现、生产无人接」的**交易权限守卫**（`R-AUTH-13` 负责人 / 管理层禁操盘）；④ `PlanService.withdraw`（约 `:639-655`）的 **javadoc 称「须为发起人」、实现仅校验状态** —— javadoc 承诺与实现不符。**⚠️ 与 `T-098` 的边界（不合并）**：`T-098` = **`CROSS_READONLY` 跨账户只读用户的「只读强制点」**（该用户**不得写**）；**`T-102` = 写面可见性断言未接线（`assertSubAccountVisible`）+ 交易权限断言未接线（`assertCanTrade`）+ `withdraw` 发起人校验缺失**。**相关但不同** —— 三者同属 `NFR-SEC-7` 权限强制族，但**强制轴向不同**：`T-098` 管「**这类用户**能不能写」，本项管「**写动作**有没有按子账户可见性 / 交易权限强制 + 发起人身份校验」；**故不合并、亦不并入 `T-098`**。**⚠️ 与 `T-099` 的边界**：`T-099` 处置 `scopeOf` **不读 `perm_type`** 的机制缺陷（**读面取数入口**）；本项处置**写面**强制点，二者不同层。**⚠️ 写者边界**：`AuthService` / `PlanService` 均 `#java` 写权；本项与其串行前序（`T-100`/`T-101`）**动同一服务层**，须遵守**单实例写**纪律。**⚠️ 禁止**：**不得**以 `DmUserSubAccountPermDao.scopeOf` 作取数入口（`scopeOf` **不读 `perm_type`**；其处置归 **`T-099`**）。**估时判据（项目经理独立判断 · 同族可比）**：与 `T-098`（3 人时）同族可比 —— `T-098` 为**一个强制点的实现 + 留痕 + 用例**（3 人时）。**第 1 轮（未含 `assertCanTrade`）**按「两个强制点择一接线/订正 + 两类拒绝用例 + 反退化」取 **3 人时**；**第 2 轮（本轮，编排者裁定纳入 `assertCanTrade`）**增至**三个强制点、且两条守卫均须接线（不再「择一」）**：`assertSubAccountVisible`（写面可见性）+ `assertCanTrade`（操盘前置交易权限）+ `withdraw` 发起人校验 ⇒ **由 3 人时上修至 4 人时**（较 `T-098` 多两项强制点 + 一类拒绝用例；机制件 `AuthService.visibleSubAccountIds` / `SubAccountScope` / 既有 `requirePlan` 路径**均已就绪、无需新建**，故未按 3×`T-098` 线性放大）。⚠️ **条件性上修**：若交付方判定两条守卫的接线点须**新增独立 DTO / 路由 / DAO**（而非复用既有写路径），应再上修**至 5～6 人时**（交付报告须写明所选接线点与实际覆盖面）。**串行顺序（编排者裁定 2026-09-21）**：`T-100 → T-101 → T-103 → T-102 → T-099 → T-098 → T-096 → T-097`（`T-103` 插于 `T-101` 之后、`T-102` 插于 `T-103` 之后、`T-099` 之前；前六项同属 `#java`、动公共件／服务层／写读路径，**同一时刻只允许一个实例写**；`T-097` 为判据升级、最后收束）。 **第 3 轮再扩（同族第四项 ④）**：`T-101` 交付报告主动披露「未完成/待裁定 ①」 —— `PlanService.decomposeTradePlans`（`PlanService.java:775`，**登记时点 md5 `a18d23b7…` / 1237 行**）在 `:786` 仍调用**未 scoped** 的 `tradePlanDao.findByHedgePlan(planId)`，属**写路径内读取未做子账户过滤**，与前述三强制点**同族**（「控制看起来有、实际没有」的**写路径读取面**变体）。**原三项强制点** = ① `assertSubAccountVisible` 未接线 / ② `assertCanTrade` 未接线 / ③ `withdraw` 发起人校验缺失；**本项为同族第四项 ④**。**估时判据（第 3 轮）**：④ 复用 `T-101` 已建立的 `findByHedgePlanScoped` + `SubAccountScope`，且 `decomposeTradePlans` **已接受 `operatorId` 入参**（无须改签名）⇒ 增量小于 `T-101`（3 人时）；但 ④ 为**第四个强制点 / 覆盖项**，须自带「跨子账户既有交易计划不计入 `used`」用例 + **反退化自证** ⇒ 按本项目「每个强制点 ≥ 1 人时」粒度 **+1 人时，由 4 人时上修至 5 人时**。**⚠️ 回落条件**：若交付方判定 ④ 完全复用既有 scoped 方法、无新增 DTO/路由/DAO 且覆盖面最小，可在交付报告中说明实际人时并**建议回落至 4 人时**（须给覆盖面证据）。 **第 4 轮扩项（项目经理 2026-09-21）**：本任务**再扩一项** —— 除「接线」外，`assertCanTrade` 的**判据形态须由合取订正为析取**（依 `CH-010`，见验收 2），并补**两类单条件拒绝用例**（验收 5 ②a / ②b）。**估时判据（项目经理独立判断）**：该扩项系**既有强制点 ② 的判据语义订正（合取 → 析取）+ 既有拒绝用例的拆分细化（② → ②a/②b）**，**非新增独立强制点 / 覆盖项**，落在既有 5 人时（含「条件性上修」缓冲）范围内 ⇒ **不上修，维持 5 人时**。（**备选**：若编排者 / 交付方判定须按本项目「每个覆盖项 ≥ 1 人时」粒度上修，则 **+1 → 6 人时**；此时须联动 §1 合计行 / §4 里程碑映射 / §5 资源负荷等聚合口径。）

**（2026-09-21 登记 · 待裁定）`CROSS_READONLY` 可写 —— 归本任务口径**：`assertSubAccountVisible` 对「调用者授权集含哨兵 `0`（`CROSS_READONLY`）」**一律放行** ⇒ `CROSS_READONLY` 用户**可写** `POST /spot/production`、`/spot/doc/sub-account`。与「只读」命名相悖，但与本任务已落地的 `assertSubAccountVisible` 语义**同源**（`PlanService` 写面亦如此）⇒ 属**本任务口径问题**，**非 `T-109` 引入**。**待裁定：`CROSS_READONLY` 是否应允许写路径。**（**只加不改**登记；来源 = `T-109` 增量复核 `te-verify-t109c` 探索性新发现 (B)）
**📌（2026-09-21 闭环 · 只加不改）** 本「待裁定」项**已闭环**：由 **`T-098`** 落地（**写面拒绝 `CROSS_READONLY`**、错误码 **`10004`**、留痕 `t_unauthorized_attempt`；**读面语义不变**）+ **读 / 写断言分离**（读面 `assertSubAccountVisible` / 写面 `assertSubAccountWritable`）；依据 `ARCHITECTURE.md` §8.2③。`T-098` 已 **`DONE`**（`te-verify-t115` 独立复核**通过**，结论已由「有条件通过」升级为「**通过（无条件）**」）⇒ 判定为「**`CROSS_READONLY` 不得写路径**」，与「只读」命名一致。

### T-103 `GET /plan/{id}/trade-plans` 补分页/筛选/排序（按 `FR-PLAN-15` 输入列）

- **需求追溯**：`FR-PLAN-15`（查询交易计划）**「输入」列**（`docs/REQUIREMENTS/PLAN.md:321`：「筛选条件（子账户、套保计划、合约、状态、执行期限）、排序、**分页**」；同 FR **优先级 = P0**，`PLAN.md:324`；**处理规则**默认按**执行期限升序**，`PLAN.md:322`）；`ARCHITECTURE.md` §4.1.1（分页约定 `?page=&size=&sort=`、`size ∈ {10,20,50,100}`、默认 20）；`java/CONTRACTS.md` §1.1（`:70` 分页口径 + `:266` `PageData<T>` 字段 = `items`/`total`/`page`/`size`；现行锚点 `05c8161207e94d14c643fa632e66bc46` / 728 行，**以实时 `md5sum` 为准**）；`NFR-SEC-7`（隔离不得回退）
- **负责人**：backend-engineer#java
- **优先级**：P0（与 `FR-PLAN-15` 自身优先级一致，`PLAN.md:324`；与同端点族 `T-101`/`T-102` 同档）
- **依赖**：T-101
- **估时**：4 人时（依据见「备注」估时判据）
- **来源**：新增（`T-101` 独立复核**探索性发现 `EXP-T101-1`**；编排者 2026-09-21 裁定立项，项目经理登记）
- **状态**：**DONE**（判据 = **授权 + 独立验证**）——独立复核已完成、判定 **有条件通过、0 缺陷**（`docs/TEST_RESULT.md` 追加 §`T-103.1`~§`T-103.13`，2026-09-21；**引用时点，以实时 `md5sum` 为准**）：隔离副本 clean 全量 **319 / 0 / 0 / 0**（无排除）、**共享树 clean 319/0/0/0** 且**源清单 md5 `9ce6e911…`（285 文件）复核前后一致**、**隔离×分页 9/9 自建探针**（真实 DAO；`COUNT` 与 `SELECT` **两条 SQL 均带 scope**、哨兵/`null` 分支 fail-closed）、**两向反退化变异**（移除过滤 / 移除分页）**均变红**、前端 **22/22** + `decSub` **24/24**（5000 组随机 vs BigInt **0 失败**）、交叉核对 **28 符合 / 0 不符 / 1 无法判定**（仅"基线 310"因无 `git` 不可判定）、**缺陷 0 条**；**两个「条件」由既有授权任务承载** —— (a) `FR-PLAN-15` 处理规则两项 → **`T-104`**；(b) 分页排序稳定性 → **`T-096`** ⇒ 满足本项目 `DONE`（**授权 + 独立验证**）
- **验收标准**：
  1. **请求参数**：端点接受 `?page=&size=&sort=` —— `size ∈ {10, 20, 50, 100}`（**白名单校验**、越界拒绝或回落默认）、**默认 20**；`page` 缺省 1；
  2. **响应信封**：由现「非分页 `List<TradePlan>`」改为 **`PageData{items, total, page, size}`**（`CONTRACTS §1.1` 冻结口径）；
  3. **DAO**：改走 **已有** `AbstractDmDao.findPageScoped(query, scope, where, args)` 出分页数据 + **count**；**DAO 层禁止自拼 `IN (...)`**（scope 由集中构造件 `SubAccountScope` 提供）；
  4. **子账户隔离不得回退**：保持 `PlanService.listTradePlans(planId, operatorId)` 的 scope 逻辑（`SubAccountVisibility` / `AuthService.visibleSubAccountIds` → `SubAccountScope`；含哨兵 `0` ⇒ **不过滤**、**空集 ⇒ 恒假**、`null` 按空集 **fail-closed**）；**移除过滤后原有隔离用例必红**（**反退化自证**）；
  5. **筛选条件**按 `FR-PLAN-15` **输入列**：套保计划（= 路径 `{id}`）/ 合约 / 状态 / 执行期限；**子账户筛选由后端 scope 强制**，**不得**由调用方越权指定以绕过隔离；
  6. **排序**：接受 `sort`（`field,dir`）；**缺省 = 执行期限升序**（`FR-PLAN-15` 处理规则，`PLAN.md:322`）。⚠️ `PageQuery.of(sort=null)` **恒 ASC**（见 `T-096`）——缺省方向虽与本 FR 对齐，但**缺省字段名须显式给定**，否则无排序语义；
  7. **用例**（`src/test`）：分页边界（`size` 白名单 / 默认 20 / `total` 与 `page` 协同）、筛选命中与空集、排序（含缺省执行期限升序）、**隔离四类用例回归**；且**移除过滤或取消 scope 后用例必红**；
  8. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/web/handler/plan/TradePlanHandler.java`（解析 `page/size/sort` + 筛选参数、装配 `PageQuery` / 筛选 DTO）、`java/src/main/java/com/hedgeguard/service/PlanService.java`（`listTradePlans` 增分页/筛选/排序入参、返回 `PageData`）、`java/src/main/java/com/hedgeguard/dao/TradePlanDao.java` + `dao/impl/DmTradePlanDao.java`（分页 scoped 查询 + count）+ 对应测试；`java/CONTRACTS.md` 若需补字段说明由 `#java` 落笔
- **备注**：**来源 = `T-101` 独立复核的探索性发现 `EXP-T101-1`**（`docs/TEST_RESULT.md`，**引用时点、以实时 `md5sum` 为准**）。**⚠️ 为何另立（不并入 `T-101`）**：`T-101` 采用**非分页 `List`**（形态 (b)）系依据「`FR-PLAN-15` **处理规则**不含分页」；但复核者指出**需求依据在「输入」列**（`PLAN.md:321` **明列「分页」**）⇒ **该前提不成立、端点现值未实现该 FR 的「输入」**；因 `T-101` 已进入独立复核/闭合流程，**不回改**，另立 `T-103`。**⚠️ 为何不需 `CH-xxx`（依据）**：`CONTRACTS §1.1`（`:70`）**已冻结**「分页接口用 `PageData` 信封」，本项是**向既有冻结口径对齐**（与 `GET /product` 返回裸数组同类 —— 那处已被判为 `§1.1` 违规）⇒ 属**修正违规**，**非变更契约**。**⚠️ 附带同步**：改 wire 形状 ⇒ **须同步前端** —— 交易计划列表的**前端展示归 `T-020`**（已登记为其验收 4）；`CONTRACTS.md` 若需补字段说明由 `#java` 落笔（**非项目经理写域**）。**估时判据（项目经理独立判断）**：`T-101`（3 人时）已建立隔离骨架；本项在其上**新增**分页参数解析（含 `size` 白名单 + 默认 20）、筛选 DTO（合约/状态/执行期限）、排序（含 `PageQuery` 缺省方向陷阱）、**响应信封 `List` → `PageData`**、分页 DAO + count、以及**「分页 × 隔离」组合回归** —— 其中**信封变更**一项，`T-101` 备注已自认定「成本**远超** 3 人时」（见 `T-101` 详情 验收③ 形态 (a) 之评估）⇒ 本项 **≥ `T-101`**，故取 **4 人时**（**不取 3 人时**）。⚠️ **条件性上修**：若前端同步纳入本任务（而非另立），应上修 **5 人时**（交付报告须写明所选形态与实际覆盖面）。**⚠️ 与 `T-102` 的串行关系**：二者同动 `PlanService` / `TradePlanDao` 族，须遵守**单实例写**；**若本项将 `TradePlanDao.findByHedgePlanScoped` 替换为分页版，`T-102` ④（`decomposeTradePlans` 复用 `findByHedgePlanScoped`）须相应复核**。**📌 交付披露的未完成 / 待裁定项（`T-103` 交付报告自述，项目经理仅登记、不改本项验收原文）：** **(a)** `FR-PLAN-15`「**处理规则**」的**剩余可下单量**与**已达可下单量上限标记**仍未实现（`T-103` 明确只做「**输入**」列）⇒ **另立 `T-104` 承载**；**(b)** 分页 `ORDER BY` **仅单列、无唯一 tie-break**（旧非分页版为 `exec_deadline ASC, id ASC`）⇒ **同 `exec_deadline` 值跨页可能重复或漏行**；`PageQuery`（`T-096`）**未支持多列** ⇒ **本项分页排序的稳定性依赖 `T-096`**（`T-103` 当前无 tie-break；「稳定排序」要求已登记入 `T-096`）；**(c)** `TradePlanDao.findByHedgePlanScoped` 生产侧**已无调用者**（仅测试引用、**保留为 `T-101` 冻结契约面**）；**(e)** 「套保计划」筛选由**端点路径 `{id}`** 承载、**未另设查询参数**；「执行期限」实现为**闭区间** `execDeadlineFrom/To`（**同值即精确匹配**）。

### T-104 `FR-PLAN-15` 处理规则两项：交易计划**剩余可下单量** + **已达上限标记**（含前端展示）

- **需求追溯**：`FR-PLAN-15`（查询交易计划）**「处理规则」**（`docs/REQUIREMENTS/PLAN.md:322`；引用时点，**以实时 `md5sum` 为准**：「每条交易计划须展示**剩余可下单量**（= 数量 − 已下达指令量）；已生效但**已达可下单量上限**的交易计划须**明确标记**」）；`FR-PLAN-14` 验收 4（交易计划子账户归属，`PLAN.md:316`）；同 FR 优先级 = **P0**（`PLAN.md:324`）
- **负责人**：backend-engineer#java（**服务端字段**；前端展示**另派**，非本任务写域）
- **优先级**：P0（与 `FR-PLAN-15` 自身优先级一致）
- **依赖**：T-103（`T-103` 已改分页信封 `PageData`；列表投影须在其上落地）
- **估时**：2 人时（依据见「备注」估时判据）
- **来源**：新增（`FR-PLAN-15` **「处理规则」两项**被 `T-101`/`T-103` **两次**判为「非本任务范围」⇒ **单独立号以免持续掉队**；编排者 2026-09-21 裁定立项，项目经理登记）
- **状态**：TODO
- **验收标准**：
  1. **字段（服务端）**：交易计划视图（`TradePlanView`）增 **`remainingQty`**（= `planQty` − `orderedQty`；**口径以 `FR-PLAN-15` 处理规则为准**，交付报告须核字段语义与列名）与 **`limitReached`**（`orderedQty >= planQty`；**适用于已生效计划**）；
  2. **同一口径**：**列表**（`GET /plan/{id}/trade-plans`）与**详情**读取路径的 `remainingQty` / `limitReached` **取值口径一致**（同一计算来源，**不得**两处各算一份）；
  3. **用例**（`src/test`）：① 未达上限 ⇒ `remainingQty > 0` 且 `limitReached = false`；② 已达上限（`orderedQty == planQty`）⇒ `remainingQty = 0` 且 `limitReached = true`；③ 列表与详情**同值**；
  4. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/...`（`TradePlanView` 增 `remainingQty` / `limitReached`）+ 对应读取路径与测试
- **备注**：**本项已被两次判为「非本任务范围」**（`T-101` 形态裁定只做「读取面隔离」；`T-103` 明确只做「**输入**」列）⇒ `FR-PLAN-15`「**处理规则**」两项**始终无任务承载**；本编号为其**独立承载**。**⚠️ 前端展示归 `T-020`**（`T-020` 追溯 `FR-PLAN-1~20` **已覆盖 `FR-PLAN-15`**；其备注/验收已登记消费本任务的 `remainingQty` / `limitReached`；**本任务只做服务端字段**）。**⚠️ 写者边界**：`TradePlanView` / 服务层读取投影属 `#java` 写权；与 `T-103` 同族（动同一读取路径）⇒ **须遵守单实例写、串行在 `T-103` 之后**。**估时判据（项目经理独立判断）**：主体 = 视图增**两个派生字段** + 列表/详情**同口径** + **3 例**单测；两字段均由既有 `planQty` / `orderedQty` 派生（**无需新增查询 / DAO / 路由**），改动面小于 `T-096`（3 人时）⇒ **取 2 人时**（与裁定建议一致）。⚠️ **条件性上修**：若 `orderedQty`（已下达指令量）当前**无现成来源**、须新增聚合查询或 DAO，应上修至 **3 人时**（交付报告须写明字段来源与实际覆盖面）。 **📌 双实现风险（来源 = `T-103` 独立复核者 `EXP-T103-2`，`docs/TEST_RESULT.md` §`T-103` 节；**以实时 `md5sum` 为准**）**：**服务端 `limitReached` / `remainingQty` 为唯一权威**；前端**不得自行计算**「已达上限」（前端自算与服务端标记属**双实现**、二者可能**分歧**）。`T-020` 的前端展示须改为**消费服务端字段**，不得本地复算。

### T-105 PRODUCT 列表类读取面口径收口（`GET /product` 分页 / 默认倒序 / 子账户隔离 + `GET /product/export`）

- **需求追溯**：`FR-PRODUCT-5`（查询品种；「输入」列 `PRODUCT.md:146` 含「分页参数（页码、每页条数）」；「处理规则」`:147` = 「默认按创建时间倒序」+「仅返回当前用户有权限查看的子账户范围内的品种（`FR-AUTH-12`、`FR-AUTH-13`）」）；`FR-PRODUCT-8`（导出，`:187`~`:199`；`:191`「仅导出当前用户有权限的子账户范围」+「导出内容与 `FR-PRODUCT-5` 的当前筛选条件一致」）；`FR-AUTH-12`/`FR-AUTH-13`；`NFR-SEC-7`
- **负责人**：backend-engineer#java2（PRODUCT 模块属主 = `T-005`）
- **优先级**：P0
- **依赖**：T-100（复用 `AuthService.visibleSubAccountIds` / `SubAccountScope`）
- **估时**：5 人时（依据见「备注」估时判据）
- **来源**：新增（`tl-triage` 全模块读取面只读普查 + 编排者 2026-09-21 裁定立项，项目经理登记）
- **状态**：**DONE（独立复核通过）**（判据 = **授权 + 独立验证**；见「备注」）
- **验收标准**：
  1. **`GET /product` 补分页信封**（`FR-PRODUCT-5` 输入列 `:146`）：现裸数组（`ProductHandler.java:53-57`）⇒ 改 `PageQuery` + `PageData{items,total,page,size}`（`CONTRACTS §1.1` 冻结口径）；
  2. **默认排序改创建时间倒序**（`:147`）：现 `DmProductDao.java:104` `ORDER BY product_code ASC` ⇒ 改为 `created_time DESC`；⚠️ **须显式声明方向**（`PageQuery` 缺省恒 `ASC`；本项目 `BUG-001` 即此坑）；
  3. **子账户隔离**（`:147`）：载体 = **`t_sub_product_whitelist`**（`docs/DEPLOY/schema/m1_product.sql:160`–`:188`，含 `sub_account_id` + `product_id` + `UNIQUE (sub_account_id, product_id)`）；按 `AuthService.visibleSubAccountIds(userId)` 的可见集 join `product_id`；**哨兵 `0` ⇒ 不加过滤**、**空集 ⇒ 恒假**、`null` 按空集 **fail-closed**；**禁**用 `DmUserSubAccountPermDao.scopeOf`；
  4. **`GET /product/export`**（`FR-PRODUCT-8` `:191`）：按**同一可见集**过滤（现 `VarietyService.java:808-810` 全量导出）+ 排序与 ① 的 `GET /product` **对齐**；
  5. **四类隔离用例**（`src/test`）：① `LIMITED` 只见己方子账户；② 无授权 ⇒ 空集；③ `CROSS_READONLY` ⇒ 不过滤；④ 跨子账户 ⇒ 不可见；且**移除过滤必红**（反退化自证）；
  6. **真实 DAO SQL 形状断言**：`COUNT` 与 `SELECT` **两条均带 scope**；空 scope ⇒ `IN (-1)`；哨兵 `0` ⇒ 不含；**分页端点须断言 `PageData` 信封且 `defaultDirection` 显式为 `desc`**（只查 `sortField` 会漏，`BUG-001` 教训）；
  7. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/.../web/handler/product/ProductHandler.java`、`java/.../service/VarietyService.java`、`java/.../dao/impl/DmProductDao.java` + 对应测试
- **备注**：**来源 = `tl-triage` Part 2 全模块只读普查**（11 个已注册列表类端点 + 3 个单对象同族端点，逐端点回读**其自身 FR 原文**后比实现；编排者已抽查关键结论成立）。**载体裁定（编排者 2026-09-21 查实）**：`FR-PRODUCT-5`「只返回有权限的子账户范围内的品种」—— `t_product` **无** `sub_account_id` 列，故载体易被误判为「无载体」；实测 `m1_product.sql:160`–`:188` **`t_sub_product_whitelist` 有 `sub_account_id`** ⇒ **载体 = 该白名单表**，由「疑似」升为「**确定缺陷**」。**待确认项（产品经理；仅登记，本任务不实现）**：`GET /product/change-log`（`FR-PRODUCT-14`）的隔离要求 —— `PRODUCT.md:280` **未写控制动词**，仅验收 4 提「审计岗可跨子账户查看」⇒ **蕴含**非审计岗受限；**确认成立则另立号**。**估时判据（项目经理）**：两条端点（列表 + 导出）+ 分页信封变更 + 默认方向变更 + 白名单 join 隔离 + 四类用例 + SQL 形状断言 ⇒ **5 人时**。**写者边界**：`VarietyService`/`ProductHandler`/`DmProductDao` 属 `#java2` 写权（PRODUCT 属主 `T-005`）；与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。
  **（2026-09-21 转 `DONE` · 只加不改）**：独立复核由 **`te-verify-t105`** 出具，判 **有条件通过、0 阻断缺陷** ⇒ 满足本项目 `DONE`（**授权 + 独立验证**）。**复核要点**：验收 **7/7**；**两个方向均核**（越权 + 防误拒 —— 含 `CROSS_READONLY` 读面经**真实 `AuthService`** 放行、哨兵 `0` 下两条 SQL **均无过滤**）；**独立构建 `mvn -B -o clean test` = `435/0/0/0`**；`@Test` 数与 surefire 一致；**自设 4 条反退化探针全红**（7/1/3/6）；`ApiRoutes` **未改**。**唯一「不符」** = 产出方自述 `DmProductDao` **179** 行而实测 **183**（**`md5` 逐字节相同 ⇒ 内容一致、仅行数自述笔误**）⇒ **不构成缺陷**。**范围外三项**（复核者未立号、未改文档）：① `FR-PRODUCT-8` 验收 1「导出与列表筛选一致」当前不成立（`listProducts`/`exportProducts` 均无筛选参数）、② **白名单子查询未过滤 `status`（over-inclusive，已移出品种仍算「可见」）**、③ `FR-PRODUCT-5` 输出列「子账户」「最近变更时间」`ProductView` 未含 ⇒ **已由项目经理登记立项**：①+③ 合并 → **`T-116`**；②（**判缺陷：可见性过宽**）→ **`T-117`**。**跨页 tie-break 缺位**（分页排序稳定性）经复核者**独立复现**（15 条同 `exec_deadline`、`size=10` ⇒ 并集=10、漏 11–15 + 重复 1–5）⇒ 归 **`T-096`**、**不阻断本任务**。**替身改动**经**逐字节 diff** 确认**仅追加 3 个 stub、未削弱既有断言**。

### T-106 PRODUCT 单对象读取面隔离（`GET /product/{id}` + `GET /product/quota`）

- **需求追溯**：`FR-PRODUCT-6` 验收 3（「查询无权限子账户的品种，返回无权限提示」）；`FR-PRODUCT-11`（三层限额按子账户，`Q-16`）；`FR-AUTH-13`；`NFR-SEC-7`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-100
- **估时**：2 人时
- **来源**：新增（`tl-triage` 普查 + 编排者 2026-09-21 裁定立项，项目经理登记）
- **状态**：TODO
- **验收标准**：
  1. **`GET /product/{id}`**（`FR-PRODUCT-6` 验收 3）：`VarietyService.getProduct` / handler 接**可见性校验**（无权限子账户的品种 ⇒ 无权限提示）；
  2. **`GET /product/quota`**（`FR-PRODUCT-11`）：`QuotaHandler` **须校验调用者对该子账户可见**（现按调用方传入的 `subAccountId` 取全景、**无越权校验**）；
  3. **用例**：① 有权限 ⇒ 正常；② 无权限 ⇒ 拒 / 无权限提示；且**移除校验必红**（反退化自证）；
  4. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/.../service/VarietyService.java`、`java/.../web/handler/product/QuotaHandler.java` + 对应测试
- **备注**：**同族**（`T-100`/`T-101` 的读取面隔离缺陷在 PRODUCT **单对象**端点复现）。**估时判据（项目经理）**：两个单对象端点的可见性校验 + 双向用例 ⇒ **2 人时**。**写者边界**：`#java2`（PRODUCT 属主 `T-005`），与 `#java` 链串行。

### T-107 SPOT 读取面口径收口（`GET /spot/exposure` 分页 / 默认倒序 / 隔离 + `GET /spot/exposure/{id}` 隔离）

- **需求追溯**：`FR-SPOT-15`（查询现货敞口，`SPOT.md:312`–`:325`；`:315` 输入含「排序、分页」；`:316` 处理规则「默认按**敞口金额或数量倒序**」+「**仅返回有权限的子账户范围**」；验收 3 `:325`「A 用户查不到 B 的敞口」）；`FR-AUTH-13`；`NFR-SEC-7`
- **负责人**：backend-engineer#java2（SPOT 模块属主 = `T-006`）
- **优先级**：P0
- **依赖**：T-100
- **估时**：4 人时
- **来源**：新增（`tl-triage` 普查 + 编排者 2026-09-21 裁定立项，项目经理登记）
- **状态**：TODO
- **验收标准**：
  1. **`GET /spot/exposure` 补分页**（`:315`）：现裸数组（`SpotExposureHandler.java:73-85`）⇒ `PageQuery` + `PageData`；
  2. **默认排序**（`:316`「敞口金额或数量倒序」）：现 `DmExposureDao.java:134` `ORDER BY product_id ASC, direction ASC` ⇒ 须改；**显式声明方向 `desc`**；
  3. **隔离**（`:316` + 验收 3）：`SpotService.listPendingHedge(int)` 改为接**身份**（增 `operatorId`）+ `visibleSubAccountIds` → scoped；**禁**由调用方传 `subAccountId` 决定可见集（现 `SpotExposureHandler.java:73` `ctx.queryParam("subAccountId")` 即如此）；
  4. **`GET /spot/exposure/{id}`**（验收 3）：`SpotService.getExposure(long):515` 增 `operatorId` 并接**同一过滤**；
  5. **四类隔离用例** + **移除过滤必红** + **真实 DAO SQL 形状断言**（同 `T-105` 验收 5/6；分页端点断言 `PageData` 且 `defaultDirection` 显式 `desc`）；
  6. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/.../service/SpotService.java`、`java/.../web/handler/spot/SpotExposureHandler.java`、`java/.../dao/impl/DmExposureDao.java` + 对应测试
- **备注**：**同族**（`T-100`/`T-101` 读取面隔离缺陷在 SPOT 端点复现；且现值由**调用方传 `subAccountId`** 决定可见集 ⇒ **越权面**）。**估时判据（项目经理）**：列表端点（分页 + 默认方向 + 隔离）+ 详情端点隔离 + 四类用例 + SQL 形状断言 ⇒ **4 人时**。**写者边界**：`SpotService`/`SpotExposureHandler`/`DmExposureDao` 属 `#java2`（SPOT 属主 `T-006`），与 `#java` 链串行。
**（2026-09-21 复核补登，只加不改）** `GET /spot/exposure`→`listPendingHedge`、`GET /spot/exposure/{id}`→`getExposure` **确认未强制**（属本任务范围，实现时须覆盖）；另 **`POST /spot/exposure/recalc`→`recalcForDisplay` 亦无可见性**（传身份但不断言；**是否纳入本任务范围请裁定**，如不纳入须另立号）。**（2026-09-21 裁定）** `POST /spot/exposure/recalc` **属写路径**（读 + 留痕写），**不纳入本任务**（本任务为**读取面**）⇒ 已另立 **`T-114`** 承接；本任务范围仍为 `GET /spot/exposure` 与 `GET /spot/exposure/{id}`。

### T-108 补齐 **AUTH 3 个**未实现 DAO 的实现类（原「CONFIG+AUTH 7 个」已收窄 —— CONFIG 侧 4 个由 `T-109` 交付）

- **需求追溯**：`FR-CONFIG-1`, `FR-AUTH-1/14`, `NFR-MAINT-3`, `NFR-COMPAT-5`；承接 `T-004` 产出面（`java/dao/**`）〔**历史层**：CONFIG 侧（含 `FR-CONFIG-1`）已随 4 个 DAO 转 `T-109`（2026-09-21 第 19 轮）；本任务**现行**范围 = AUTH 3 个，对应 FR 见 `docs/REQUIREMENTS/AUTH.md`〕
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-004
- **估时**：8 人时
- **来源**：新增（`T-016`/`CR-002` 实施受阻连带发现；编排者 2026-09-21 裁定立项）
- **状态**：TODO
- **验收标准**：
  1. **AUTH 3 个接口各自有 `Dm*` 实现类**：`PermissionChangeLogDao` / `PositionGroupDao` / `RolePermissionDao` → 新建对应 `dao/impl/Dm*.java`；**不改任何接口签名**（改接口＝契约变更）；〔**第 19 轮范围收窄**：原列的 CONFIG 侧 4 个（`AccountSubjectDao` / `CtpFrontConfigDao` / `DictItemDao` / `ReportPeriodConfigDao`）**已由 `T-109` 交付**（项目经理实测 `dao/impl/` 下四个 `Dm*` 实现类均在位），**不在本任务内**〕
  2. 实现遵循既有 DAO 模式（`AbstractDmDao` + `BaseDao` 契约 + `DmDialect`；SQL 只在 `dao/impl`）；
  3. `bash tools/arch-check/scan_java_sql.sh` 退出码 **0**；
  4. 每个 DAO 至少一条真 DAO SQL 形状断言（或等价用例）；隔离副本 + 共享树 `mvn -B -o clean test` 全绿无排除（四要素）；
  5. **若某个 DAO 经复核确认本期不需实现**（例如其表属 M2/M3），**必须在 `TASKS.md` 该任务备注与本条目内显式登记「延后实现 + 原因 + 承接任务」**，不得沉默略过。
- **产出**：`java/src/main/java/com/hedgeguard/dao/impl/` 下新建 **3 个**实现类 —— `DmPermissionChangeLogDao.java`、`DmPositionGroupDao.java`、`DmRolePermissionDao.java`（**第 19 轮范围收窄**：原列 7 个中 CONFIG 侧 4 个 `DmAccountSubjectDao` / `DmCtpFrontConfigDao` / `DmDictItemDao` / `DmReportPeriodConfigDao` **已由 `T-109` 交付**、不在本任务内）
- **备注**：**来源** = `T-016`/`CR-002` 实施受阻连带发现（`be-java2-cr002` 实施需「④ 操作授权」时发现 `AuthorizationDao` **只有接口、无实现**）。**普查口径（编排者 2026-09-21 取证 + 项目经理复核）**：命令 `ls java/src/main/java/com/hedgeguard/dao/*Dao.java` 与 `ls java/src/main/java/com/hedgeguard/dao/impl/Dm*.java` 反查（`*Dao.java` 共 **49**；**剔除清单** = 非实现型基接口 `BaseDao`/`DeletableDao` + 非 DAO 文件 `PageQuery`/`SubAccountScope`/`PageResult`/`RowMapper`）⇒ **8 个 DAO 接口无 `dao/impl/Dm*` 实现**（`AccountSubjectDao`/`AuthorizationDao`/`CtpFrontConfigDao`/`DictItemDao`/`PermissionChangeLogDao`/`PositionGroupDao`/`ReportPeriodConfigDao`/`RolePermissionDao`）；其中 `AuthorizationDao`（**全仓 0 引用** —— `grep -rln 'AuthorizationDao' java/src/` 仅回接口自身）由 **`T-016`** 顺带实现 ⇒ **本任务补其余 7 个**。**边界**：只新建 `Dm*` 实现类，**不动接口、不动 `T-004` 已交付的基座与 20 张表 DDL**；CONFIG/AUTH 属主 = `#java2`（`T-004`），与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。**（2026-09-21 第 19 轮范围收窄，编排者裁定）**：CONFIG 侧 4 个 DAO（`AccountSubjectDao` / `CtpFrontConfigDao` / `DictItemDao` / `ReportPeriodConfigDao`）**已由 `T-109` 交付**（项目经理实测 `java/src/main/java/com/hedgeguard/dao/impl/` 下 `DmAccountSubjectDao.java` / `DmCtpFrontConfigDao.java` / `DmDictItemDao.java` / `DmReportPeriodConfigDao.java` 四者**均在位**）⇒ 本任务**收敛为 AUTH 3 个**（`PermissionChangeLogDao` / `PositionGroupDao` / `RolePermissionDao`）。**估时 8 人时未改**（是否随范围收窄调整由编排者另行裁定；本席判断见回报）。**（2026-09-21 估时裁定）范围收窄后估时维持 8 人时**（编排者 2026-09-21 裁定）。理由：固定验证开销（`scan_java_sql.sh` 退 0 + 隔离/共享 clean 四要素 + 骨架）不随 DAO 数下降；差额视为**缓冲**。本席曾建议下调至 ≈5 人时（4~6 区间），**未被采纳、留痕于此**。

### T-109 补齐「已声明未注册」端点（CONFIG 9 / SPOT 5 / PLAN 1）

- **需求追溯**：端点自身 FR（`docs/REQUIREMENTS/CONFIG.md` / `SPOT.md` / `PLAN.md`）+ `ApiRoutes` 声明面（`ARCHITECTURE.md` §4.1.1 代码镜像）、`NFR-MAINT-5`
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-005, T-006, T-007
- **估时**：12 人时
- **来源**：新增（「验收面 ↔ 产出面」对齐普查 `audit-cov` 新发现；编排者 2026-09-21 裁定立项，项目经理登记）
- **状态**：**DONE（附条件）**〔**第 21 轮**〕（判据 = **授权 + 独立验证**；**三轮独立复核均已回**〔首轮 `te-verify-t109`「有条件通过、7 条缺陷未清零」→ 复检 `te-verify-t109b`「有条件通过」〔`001/002/003/004/006` 真修好〕→ 增量复核 `te-verify-t109c`「有条件通过」〔`(C)` CSV 表头判空防 NPE→500、`(D)` 注释编号两处残留已修〕〕；**两处严重缺陷 `TE-T109-001`（`FR-SPOT-11/12` 输入契约）与 `TE-T109-002`（`/spot/exposure/export` 越权读取）均已真修好并证实伪**；隔离副本 `mvn -B -o clean test` = **`375/0/0/0`** 独立复现〔`2026-09-21T12:32:23+08:00`，clean、无排除〕；`ApiRoutes.java` **全程未改**〔`3dec526b…`；**以实时 `md5sum` 为准**〕；**唯一附条件 = `TE-T109-005` 验收 1（变更后重算敞口）未达成**，原因为**诚实阻塞**〔独立复核证实：`java/src/main` 与 `cpp` 内**无任何**敞口推导实现；「无引擎」成立〕 ⇒ 由 **`T-111`** 承接（先造引擎再回填验收 1）。**留痕·首轮退回（2026-09-21）**：独立复核 `te-verify-t109` 判定**有条件通过** —— 核心交付成立〔注册面 **19 个 `(方法,路径)` 对**全注册、路由 **48→67**、隔离副本 `348/0/0/0`、`ApiRoutes.java` **未改**〕，但**缺陷未清零**〔**7 条** = 2 严重 + 3 一般 + 2 建议，其中 `TE-T109-007` 待产品经理裁定〕 ⇒ 按本项目判据〔`DONE` = **授权 + 独立验证**；「**有条件通过 + 缺陷未清零**」= **退回**〕**退回修复**（修复单 `be-t109-fix`，`java/**`）。
- **验收标准**：
  1. **逐条核对该端点自身 FR**（`docs/REQUIREMENTS/CONFIG.md` / `SPOT.md` / `PLAN.md`）确认是否属本期；**属本期者须实现并注册**；
  2. **不属本期者**（例如确属 M2/M3）**必须在 `TASKS.md` 本任务备注内逐条显式登记「延后实现 + 原因 + 承接任务号」**，**不得沉默略过**；
  3. 实现后 `requestDispatcher` 对上述路径不再返回 `405/404`；
  4. 隔离副本 + 共享树 `mvn -B -o clean test` **全绿无排除**（四要素）；
  5. `bash tools/arch-check/scan_java_sql.sh` 退出码 **0**。
- **产出**：`java/.../web/handler/config/ConfigRoutes.java`、`java/.../web/handler/spot/SpotRoutes.java`、`java/.../web/handler/plan/PlanRoutes.java` + 各端点 Handler/Service 实现 + 对应测试
- **备注**：**来源** = 「验收面 ↔ 产出面」对齐普查（`audit-cov`，108 条全覆盖、无抽样、通配展到文件级；编排者抽查通过）。**普查命令**：`sed -n '75,78p' java/src/main/java/com/hedgeguard/web/ApiRoutes.java`（声明面 `BY_MODULE.put("CONFIG"|"SPOT"|"PLAN", ...)`）+ `grep -c 'router.register(' java/src/main/java/com/hedgeguard/web/handler/{config/ConfigRoutes,spot/SpotRoutes,plan/PlanRoutes}.java`（注册面）。**声明 / 注册对照（以证明「15 条」的分母）**：① `CONFIG` 声明 **11** / 注册 **2** 路径（`ConfigRoutes.java:33-36` 共 4 个 `router.register` = `/config/item`〔GET/PUT〕+ `/config/sub-account`〔GET/POST〕）⇒ 差 **9**：`/config/ctp`、`/config/ctp/test`、`/config/dict`、`/config/subject`、`/config/report-period`、`/config/dispatch/{id}`、`/config/change-log`、`/config/import`、`/config/export`；② `SPOT` 声明 **13** / 注册 **8** 路径（`SpotRoutes.java:38-51`）⇒ 差 **5**：`/spot/production`、`/spot/price/import`、`/spot/price/export`、`/spot/exposure/export`、`/spot/doc/sub-account`；③ `PLAN` 声明 **16** / 注册 **15** 路径（`PlanRoutes.java:50-61`）⇒ 差 **1**：`/plan/{id}/bind-exposure`。**合计 15 条**（另 `AUTH` 8 条已在案、归 **`T-010`** ⇒ 声明-注册缺口共 **23 条**）。`ConfigRoutes.java:18-20`、`SpotRoutes.java:24`、`PlanRoutes.java:37` **自述「属后续任务」**，但**全表此前无任何任务承载** ⇒ **义务悬空**；且 `ModuleRoutesConsistencyTest.java:50` 覆盖下限为**硬编码现值** ⇒ 对「少注册」**结构性失明**。**⚠️ 排程裁定（编排者 2026-09-21，写入本条与 `BOARD.md` §5「影响」栏）**：**本任务必须排在 `T-097` 之前** —— `T-097` 把路由判据由「路径级 · 子集」升级为 **`(方法,路径)` 等值断言**，**若先升判据，这 23 条会立刻全红**；正确次序 = **先补 / 登记端点（`T-109`、`T-010`）→ 再升判据（`T-097`）**。**写者边界**：动 `ConfigRoutes`/`SpotRoutes`/`PlanRoutes` 及各模块 Handler/Service，属 **`#java2`**（CONFIG / SPOT / PLAN 属主 `T-005`/`T-006`/`T-007`），与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。**（2026-09-21 登记）两条追溯缺口**：① **`/spot/price/export` 无对应 FR** —— 编排者独立搜索 `docs/REQUIREMENTS/SPOT.md`：CSV 导出条款**只见于 `FR-SPOT-18`**（**敞口**导出），**价格导出确无 FR** ⇒ 属「**产出面 > 需求面**」缺口 ⇒ **待产品经理裁决：补 `FR`（走 `CH-xxx`）或删路由（走 `CH-xxx`）；在本任务内按 `FR-SPOT-13` 派生形态交付**。② **`/spot/price/import` 输入契约待复核** —— FR 要求输入为「**粘贴文本块**」/「**CSV 文件（列：品种编码、日期、现货价）**」含**表头列完整性校验**与**逐行行号定位**；实现收的是**已解析 JSON**（取 `productId`）⇒ **登记为「待独立复核确认」**（**不作已判定缺陷**）。

**（2026-09-21 第 20 轮：独立复核结论 + 缺陷登记，复核者 `te-verify-t109`）** 独立复核 = **有条件通过**（核心交付成立：**19 个 `(方法,路径)` 对全注册**、路由 **48→67**、隔离副本 `mvn -B -o clean test` = **`348/0/0/0`**、`ApiRoutes.java` 未改），但**缺陷未清零**（**7 条** = 2 严重 + 3 一般 + 2 建议）；下表为**复核结论转述**（项目经理未逐条复现）：

| 编号 | 严重度 | 摘要 | 违反 FR | 承接 |
|------|--------|------|---------|------|
| `TE-T109-001` | **严重** | `/spot/price/import` **未实现输入契约**（无 CSV / 粘贴解析、无表头列校验、无行号定位；取 `productId` 而非「**品种编码**」） | `FR-SPOT-11` / `FR-SPOT-12`（P0） | **本任务修复** |
| `TE-T109-002` | **严重·安全** | `/spot/exposure/export` **无可见性过滤** ⇒ 任意已认证用户传任意 `subAccountId` 可**越权导出** | `FR-SPOT-18` | **本任务修复** |
| `TE-T109-003` | 一般 | `/spot/production` 算了 `overlapFlag` **却不回传** | `FR-SPOT-8` 验收 3 | 本任务修复 |
| `TE-T109-004` | 一般 | `/config/import` **缺取值范围预校验**（`validateValue` 未调用） | `FR-CONFIG-13` 验收 2 | 本任务修复 |
| `TE-T109-005` | 一般 | `/spot/doc/sub-account` **不重算敞口**（仅回 `recomputeRequired=true`） | `FR-SPOT-16` 处理规则 / 验收 1 | 本任务修复（若不可实现须回报阻塞 + 承接建议） |
| `TE-T109-006` | 建议 | 验收 2 报错列 `exposureIds` 而非「**计划**」 | `FR-SPOT-16` 验收 2 | 本任务修复 |
| `TE-T109-007` | 建议·待裁定 | `/spot/price/export` **确无对应 FR**（独立复核已证实）⇒ 产出面 > 需求面 | — | **待产品经理裁决**（补 `FR` 或删路由，**两者均须走 `CH-xxx`**）**（2026-09-21 闭环）** 已闭环 = 采「**补 `FR`**」形态；产品席已回写 **`CH-011`** 并新增 **`FR-SPOT-19`**（用户 2026-09-21 已确认采形态①；`docs/REQUIREMENTS/SPOT.md` + `INDEX.md` 已落） |

- **C. 隔离归属订正（纠错留痕，非任务范围变更）**：编排者先前误判「`/spot/exposure/export` 的 operator 级隔离归 `T-107`」—— **独立复核证伪**：`T-107` 范围为 `/spot/exposure` + `/spot/exposure/{id}`（`FR-SPOT-15`），**不含** export 端点 ⇒ 该越权面（`TE-T109-002`）**由 `T-109` 自行承接修复、不挂 `T-107`**。
- **D. `/config/ctp/test` 延后登记**：其**连通性**在 M1 不可实测（须 C++ `ctp_adapter` + HGCP 桥接），**延后至 M2**；**建议承接任务 = `T-042`**（复核者称查无更贴切承载）。M1 行为 = 显式返回 `10002 COMPONENT_UNAVAILABLE`、**从不伪成功**（经独立复核核实）。

**（2026-09-21 第 21 轮：`T-109` 收口结论 + 两项立项）** `T-109` 三轮独立复核均已回（首轮 `te-verify-t109` / 复检 `te-verify-t109b` / 增量复核 `te-verify-t109c`，**均判「有条件通过」**）：两处严重缺陷 `TE-T109-001` / `TE-T109-002` **已真修好并证实伪**，隔离副本 `375/0/0/0` 独立复现、`ApiRoutes.java` 全程未改 ⇒ 判据〔**授权 + 独立验证**〕达成 ⇒ 置 **`DONE（附条件）`**；**唯一附条件 = `TE-T109-005` 验收 1 未达成**（**诚实阻塞**，无敞口推导实现）⇒ 由 **`T-111`** 承接。另 **`TE-T109-007`**（`/spot/price/export` 无对应 FR）**待产品经理裁定**（补 `FR` 或删路由，**均须走 `CH-xxx`**）—— 属需求侧裁决项，未清零不影响本任务收口。

### T-110 补齐 SPOT 写面（`T-013` 范围）子账户可见性强制

- **需求追溯**：`FR-AUTH-13`（子账户数据权限）、`NFR-SEC-7`（子账户软隔离 · 服务端强制）、`R-SPOT-2`（子账户归属必填且不可静默变更）；涉及写入口所属 FR = `FR-SPOT-2`/`FR-SPOT-5`/`FR-SPOT-6`（合同 / 库存 / 在制品 / 敞口）；**现货价** 专项见备注（`CH-003` / `R-SPOT-2` 注：全账户共享、其 `subAccountId` 仅**录入归属**）
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-109（复用其已落地的 `requireSubAccountVisible` 机制）
- **估时**：8 人时
- **来源**：新增（增量复核 `te-verify-t109c` 探索性新发现 **(A)**；系 `TE-T109-002`（`/spot/exposure/export` 越权）**同族**，且经查 `TASKS.md` **无任何承载任务** ⇒ 立项）
- **状态**：**DONE**（判据 = **授权 + 独立验证**；独立复核 `te-verify-t110` 判定**通过**：8 处新增强制**逐一越权实测 `10004`/HTTP 403 且业务库零写入**；分类表经**双向**核〔无「该防没防」、无「误拒合法写入」，含 `putSpotPrice`/`importSpotPrices` 按 `CH-003` **正确不加**〕；4/4 自设变异全红、复位全绿；隔离副本 `mvn -B -o clean test` = **`394/0/0/0`** 独立复现〔`2026-09-21T13:00:29+08:00`，clean、无排除；基线 375 ⇒ +19〕；`ApiRoutes.java` **未改**〔`3dec526b…`；**以实时 `md5sum` 为准**〕）
- **验收标准**：
  1. **先出分类表再动手**：产出「**方法 → 归属类型（录入归属 / 业务归属）→ 是否加强制**」分类表（依 `CH-003` / `R-SPOT-2`），随交付报告提交；
  2. **业务归属类**写入口（合同 / 库存 / 在制品 / 敞口）**逐个接入** `SpotService.requireSubAccountVisible(operatorId, subAccountId)`（**沿用 `T-109` 已建立的同一机制，不得另造**）——清零「**取客户端提供的 `subAccountId` 却不做可见性校验 ⇒ 可将他方子账户数据写入**」缺口；
  3. **录入归属类**（**现货价 `putSpotPrice` 一类**）**不得**按「业务归属」加可见性强制（现行为 `price.isShared='Y'` 的 **GLOBAL 口径**；`CH-003` / `R-SPOT-2` 明定现货价为**全账户共享市场数据**、其 `subAccountId` **不参与业务归属判定**、不参与加总）——分类表须为其给出**「不加强制」的书面依据**，避免把共享价的正常录入误拒；
  4. **fail-closed**：若某写入口**拿不到操作者身份**，属实现缺陷，**须报告**（不得绕过强制）；
  5. 补**越权写入拒绝**用例（对他方子账户提交写请求 ⇒ 被拒）+ **反退化自证**（移除强制后用例必红）；
  6. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/service/SpotService.java`（写入口接入可见性强制）+ `java/src/main/java/com/hedgeguard/web/handler/spot/`（`SpotContractHandler` / `SpotStockHandler` / `SpotPriceHandler` / `SpotExposureHandler`，如需上层装配）+ 对应测试
- **备注**：**来源** = `T-109` 增量复核（`te-verify-t109c`）**探索性新发现 (A)** —— 属 `T-109` **声明范围之外**、但为 `TE-T109-002` **同族**，且原任务表无承载。
  1. **`T-109` 修复已建立范式（照录）**：`SpotService` 现有 `requireSubAccountVisible(operatorId, subAccountId)`（**4 个调用点 / 3 个方法**：`createProductionPlan`、`exportExposuresCsv`、`changeDocSubAccount`〔源 / 目标**各一**〕；定义于 `SpotService.java`〔登记时点 `:1511`〕）——本任务**沿用同一机制**，**不得另造**。
  2. **必须逐方法按业务语义分类（照录）**：依 `CH-003` / `R-SPOT-2`，**现货价是「全账户共享的市场数据」**（实测 `putSpotPrice` 内 `price.isShared = "Y"`），其行内 `subAccountId` **仅为「录入归属」、不参与业务归属判定** ⇒ `putSpotPrice` 一类**不得**按「业务归属」加可见性（否则会把共享价的正常录入误拒）；**业务归属类**（合同 / 库存 / 在制品 / 敞口）才需强制。**要求实现方先出分类表、再动手**（验收 1 / 3）。
  3. **读取面那半已有承载**：`GET /spot/exposure` + `/{id}` 归 **`T-107`（TODO）**；**写面这半在原任务表中无承载**，故立本号。
  4. **写者边界**：`SpotService` / `Spot*Handler` 属 **`#java2`**（SPOT 属主 = `T-006`）；与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。
  5. **（2026-09-21 复核更正，只加不改）** 本任务实施方在同类扫描中曾报「`PlanService.decomposeTradePlans` 读路径未 scope」—— 经独立复核（`te-verify-t110`）实测**不实**：该处**已**调用 `requireWritablePlan(planId, operatorId)`，既有量读走 `existingTradePlansScoped`（`findByHedgePlanScoped`，仅哨兵 `0` 时不过滤）⇒ **该项作废**，不得据以立项。
  6. **（2026-09-21 登记，只加不改）M2 接线义务**：以下方法**取子账户标识但当前无生产（`src/main`）调用点（未接线）** ⇒ **接线时必须一并补可见性强制**：① `ConfigService.disableSubAccount` / `changeCategory`（实测 2026-09-21：`src/main` 除自身定义 **0 调用点**，仅 `ConfigServiceTest` 命中）；② `ParamDispatchServiceImpl.suspendOpenOnly` / `releaseOpenOnly` / `decreaseQuotaTwoPhase`（实测 2026-09-21：`src/main` 除自身定义 **0 调用点**，仅 `ParamDispatchServiceImplTest` 命中）。**勘误留痕**：编排者派单原名「`suspend` / `release`」，实测实际方法名为 **`suspendOpenOnly` / `releaseOpenOnly`**（本登记以实测名为准）。

### T-111 实现服务端敞口推导引擎并回填 `FR-SPOT-16` 验收 1（变更后重算敞口）

- **需求追溯**：`FR-SPOT-16` 处理规则（子账户归属变更后**须重算敞口**并留痕 `SPOT.md:331`）/ 验收 1（原敞口**消失**、目标**出现等量**敞口）；`R-SPOT-4`（**禁静默改写历史快照**）、`Q-17`（三分量不一致处置）
- **负责人**：backend-engineer#java2
- **优先级**：P1
- **依赖**：T-109
- **估时**：8 人时
- **来源**：新增（`T-109` 的 `TE-T109-005`）
- **状态**：TODO
- **验收标准**：
  1. **实现服务端敞口推导引擎**（从**合同 / 库存 / 生产计划**推导三分量与合计），作为 `/spot/doc/sub-account`（`changeDocSubAccount`）**变更后重算**的落点；
  2. **回填 `FR-SPOT-16` 验收 1**：子账户归属变更后，**原子账户敞口消失、目标子账户出现等量敞口**（可客观判定）；
  3. **遵守** `R-SPOT-4` / `Q-17`：**禁止静默改写历史快照**（重算须留痕、不得原地覆写既有快照）；
  4. **补断言**：`TE-T109-005` 在测试中**无任何对应断言**（`recomputeRequired` 字样仅出现在 `src`、无测试引用）⇒ 本任务须**补仓库内断言**，且**移除重算后必红**（反退化自证）；
  5. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/service/SpotService.java`（敞口推导 + 变更后重算）+ 对应 DAO（如需）+ 对应测试
- **备注（照录）**：
  1. **现状**：`FR-SPOT-16` 处理规则要求「变更后须**重算敞口**」，验收 1 要求「原敞口**消失**、目标**出现等量**敞口」；当前 `/spot/doc/sub-account` 仅回 `recomputeRequired=true` + note，**未重算**。
  2. **阻塞理由经独立复核证实成立**：`java/src/main` 与 `cpp` 内**无任何**敞口推导实现；`computeExposure(exposure, details, operatorId)` 的**三分量与合计由调用方传入**（服务仅校验 + 落库）；`changeDocSubAccount` 仅改单据 `sub_account_id`。
  3. **硬做会违反** `R-SPOT-4` / `Q-17`（**禁止静默改写历史快照**）。
  4. **本单须先造引擎**（从合同 / 库存 / 生产计划推导三分量），**再回填 `FR-SPOT-16` 验收 1**。
  5. **覆盖缺口登记**：`TE-T109-005` 在测试中**无任何对应断言** ⇒ 本任务须**补断言**（验收 4）。
  6. **写者边界**：`SpotService` 属 **`#java2`**；与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。

### T-112 写路径子账户可见性强制的全域收口（PRODUCT 写面 + PLAN 写面）

- **需求追溯**：`FR-AUTH-13`（子账户数据权限）、`NFR-SEC-7`（子账户软隔离 · 服务端强制）；涉及写入口自身 FR = `FR-PRODUCT-12`（按子账户配置品种白名单）/ `FR-PRODUCT-11`（配置品种额度指标与分类管理标记）/ `FR-PLAN-6`·`FR-PLAN-7`·`FR-PLAN-8`（套保比率三档落库）/ `FR-PLAN-1`（新建套保计划）
- **负责人**：backend-engineer#java2
- **优先级**：P0
- **依赖**：T-110（复用其 `requireSubAccountVisible` 范式）
- **估时**：8 人时
- **来源**：新增（`te-verify-t110` 域外同类复核；四组逐条已证**未强制**且**在线可调用**）
- **状态**：**DONE（独立复核通过）**（2026-09-21；实施者 `backend-engineer#java2` / 实例 `be-t112`）。**复核结论 = `te-verify-t112` 有条件通过**（锚 `7e316b27…`：分类表 **8 行全对**、**双向无缺陷**、独立复现 **`420/0/0/0`**、自设 **6 变异全红**、`394` 基线独立证实）**+ 两条件均已闭环**（① `CROSS_READONLY` 写面绕过**已由 `T-098` 的实现关闭**；② `removeWhitelist` 一致性〔建议级〕**已落**）**+ delta 已由 `te-verify-t115` 在现行锚 `1cfc6a3a…` 复验**（8 个写面点仍拒 `CROSS_READONLY`）**+ 判据 = 授权 + 独立验证** ⇒ `DONE`。**`T-115` 系重复立项、已 `REJECTED`**，与本任务关联仅在同一批实施。
- **验收标准**：
  1. **先出分类表再动手**（范式同 `T-110`）：产出「**方法 → 依据原文 → 归属类型（录入归属 / 业务归属）→ 是否强制**」分类表，随交付报告提交；**凡语义属「录入归属 / 非子账户业务维度」者不得加强制**（加了会**误拒合法写入**）；
  2. **四组写入口逐个接入**子账户可见性强制（**沿用 `T-110` 的 `requireSubAccountVisible` 同一机制，不得另造**）：① `VarietyService.putWhitelist` / `removeWhitelist`（`/product/whitelist`；`subAccountId` 由请求体直接给出 ⇒ **可直接写他人子账户**，**本号最高优先项**）；② `VarietyService.putQuota` / `putInstrumentQuota`（`/product/quota`；同形态）；③ `PlanService.calcSimple` / `calcMinVariance` / `calcCustomRule`（`/plan/ratio`；可写他人子账户 `HedgeRatioCalc`）；④ `PlanService.createPlan`（`POST /plan`；`plan.subAccountId` **无操作者可见性**，现仅 `requireSubAccount(>0)` + `requireExposure` 的**同子账户一致性**校验）；
  3. **`createPlan` 须先判定设计意图**（属「**新建**」动作、无既有计划可 `requireWritablePlan`）：**若判定应加则加**；**若判定设计上不应加，须书面说明依据**并回报编排者裁定；
  4. **fail-closed**：若某写入口**拿不到操作者身份**，属实现缺陷，**须报告**（不得绕过强制）；
  5. 补**越权写入拒绝**用例（对他方子账户提交写请求 ⇒ 被拒）+ **反退化自证**（移除强制后用例必红）；
  6. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/service/VarietyService.java`、`java/src/main/java/com/hedgeguard/service/PlanService.java`（写入口接入可见性强制）+ 对应测试
- **备注（照录）**：
  1. **必须先出分类表**（方法 → 依据原文 → 归属类型 → 是否强制）**再动手**，范式同 `T-110`；**特别**：凡语义属「录入归属 / 非子账户业务维度」者**不得**加强制（加了会**误拒合法写入**）。
  2. `createPlan` 属「**新建**」动作、无既有计划可 `requireWritablePlan`，**须先判定设计意图**（是否应要求对 `plan.subAccountId` 有可见性）；**若判定应加则加**，**若判定设计上不应加，须书面说明依据**并回报编排者裁定。
  3. 复核确认：`T-102` 的范围是**对既有计划**写动作的 `assertSubAccountVisible`；`CONTRACTS §2.2.1` 只登记 **DAO 级** scoped 读取 ⇒ 本条四项**均不在任何已登记范围内**。
  4. 与 `T-105`/`T-106`（PRODUCT **读取面**）**不同**：本条为**写面**，不重复。
  5. **锚点取证（2026-09-21，项目经理 · 附并发披露）**：**立项时点（约 `13:05`）实测** —— `VarietyService.putWhitelist`（`:477`）/`removeWhitelist`（`:535`）/`putQuota`（`:581`）/`putInstrumentQuota`（`:651`）签名含 `subAccountId`、`WhitelistHandler`（`:49`/`:52`）/`QuotaHandler`（`:62`/`:65`）已传请求体 `subAccountId` + `ctx.currentUserId()`；`PlanService.calcSimple`（`:255`）/`calcMinVariance`（`:289`）/`calcCustomRule`（`:355`）/`createPlan`（`:489`）；`PlanRatioHandler`（`:86`/`:95`/`:100`）已传身份；**该时点上述服务层均无可见性断言**（全仓 `grep requireSubAccountVisible` 仅命中 `SpotService`），路由已注册（`ProductRoutes`（`:39`–`:41`）、`PlanRoutes`（`:62`/`:53`））。⚠️ **落笔时实测：本任务四项范围已在共享树被另一实例实施**（`VarietyService.java` mtime `2026-09-21 13:16:42` 新增 `requireSubAccountVisible`〔定义 `:983`；调用 `:494` putWhitelist / `:555` removeWhitelist / `:633` putQuota 公共段 / `:679` putInstrumentQuota〕；`PlanService.java` mtime `13:17:32` 新增 `requireSubAccountVisible`〔调用 `:257` calcSimple / `:293` calcMinVariance / `:360` calcCustomRule / `:499` createPlan〕）⇒ **本任务范围疑已实现（含 `createPlan` 已按「应加」判定接入）**；**归属与状态待编排者裁定**（是否改置「已完成，待独立复核」）。**行号以实时 `md5sum` 为准。**
  6. **写者边界**：`VarietyService`/`PlanService` 属 **`#java2`**（PRODUCT/PLAN 属主 `T-005`/`T-007`）；与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。
  7. **（2026-09-21 更正）**本任务立项时曾据工作区观测记为「**疑已被另一实例实施**」——经编排者核实，**该写入者为本任务唯一授权的实施实例 `be-t112`**，**非越界、非重复派发**；此处更正留痕（原第 5 条观测记录保留为留痕、不回改）。**同轮实施事实**：`VarietyService.requireSubAccountVisible`（定义 `:983`；调用 `:494` `putWhitelist`／`:555` `removeWhitelist`／`:633` `putQuota` 公共段／`:679` `putInstrumentQuota`）、`PlanService.requireSubAccountVisible`（定义 `:1769`；调用 `:257` `calcSimple`／`:293` `calcMinVariance`／`:360` `calcCustomRule`／`:499` `createPlan`）——**行号为观测时点，以实时 `md5sum` 为准**。
  8. **（2026-09-21 登记·待架构裁定）账户级写面的授权点**：`VarietyService.putQuota` 的**层③（`ACCOUNT_PRODUCT`）有效目标为账户哨兵 `0`**、非某子账户 ⇒ **不适用子账户可见性**。**实施者判定并获编排者确认为「本单不加」**，理由：若按『哨兵 `0` 目标』调 `assertSubAccountVisible`，能通过的只有持 `CROSS_READONLY` 者，而 `CROSS_READONLY` 语义为**跨账户只读**（`ARCHITECTURE.md` §8.2③ 另登记 `T-098`：**只读身份命中应拒写**）⇒ 会把层③额度**变成谁都不能写**，构成**新的误拒**。⇒ **待裁定**：账户级写面（目标为哨兵 `0`）应由什么权限门禁？**归架构裁定**。
  9. **（2026-09-21 第 2 轮 · 复核口径更正与权威数）权威用例数 = `420`**（非产出方交稿时的 `418`）：本任务两类各 **13** 例（`VarietyT112VisibilityTest` 13 + `PlanT112VisibilityTest` 13 = **26**）；`418` 系交稿时点旧值（当时 12+12=24）。
  10. **（同上）产出方测试文件自述的 md5 已陈旧**（主源相符，**项目经理落笔前实测复算**）：`VarietyT112VisibilityTest` 自述 `d090180f…`/457 → 实测 **`3dd100037b305008a30dd74fb30f4715`/492**；`PlanT112VisibilityTest` 自述 `420f50f4…`/501 → 实测 **`896c75ac8988d7417d68493c95052fd5`/538**。
  11. **（同上）产出方自述的「树级 md5 `e4bb06a8…`」不可复现**；**统一口径** = `find java/src -type f | sort | xargs md5sum | md5sum` ⇒ 复核时点 **`7e316b27be4644378c0525a1f4417063`**（**以实时 `md5sum` 为准**；⚠️ 项目经理落笔时实测 = `e8f59e52abb0129049359cf1e3b1310b`；差异源 = 复核后新增测试文件 `T112MissingSubAccountIdHandlerTest.java`，mtime `2026-09-21 14:00:12` ⇒ 树已前进、`②` 补修落地中）。
  12. **（同上）复核并行披露的越界观察（登记、不立号）**：`ConfigService.disableSubAccount` / `changeCategory` **取裸 `subAccountId` 且全无可见性强制**（CONFIG 模块；项目经理实测 `:443`/`:499`，**行号为观测时点、以实时 `md5sum` 为准**）；`removeWhitelist` 之外的 **PRODUCT 写面**已由本任务覆盖。
  13. **（2026-09-21）复核结论 = 有条件通过**（锚 `7e316b27…`：分类表 **8 行全对**、**双向无缺陷**〔无漏防、无误拒〕、独立复现 **`420/0/0/0`**，两条严重/低危条件见下）；**条件① 已由 `T-098` 的实现关闭**（复核 `te-verify-t115` 在现行锚 `1cfc6a3a…` 上补核 delta：8 个写面点仍拒、`removeWhitelist` 已落）；**条件② 已闭环**（建议级一致性）。**`T-115` 系重复立项、已 `REJECTED`**，与本任务的关联仅在实施方同一批。 **（2026-09-21 转 `DONE`）** 复核结论 = `te-verify-t112` **有条件通过**（锚 `7e316b27…`：分类表 8 行全对、双向无缺陷、`420/0/0/0`）+ 两条件闭环（① 由 `T-098` 实现关闭；② 建议级已落）+ **delta 已由 `te-verify-t115` 在现行锚 `1cfc6a3a…` 复验**（8 个写面点仍拒 `CROSS_READONLY`）。

### T-113 修正 SPOT 现货单据创建在 `subAccountId` 缺省时的错误呈现

- **需求追溯**：`FR-SPOT-1` 验收 2（`SPOT.md:101` 起；「未选择子账户即提交被拒绝，提示"子账户必填"」）、`R-SPOT-2`（子账户归属必填）
- **负责人**：backend-engineer#java2
- **优先级**：P1
- **依赖**：—（**归属 `T-013`**）
- **估时**：2 人时
- **来源**：新增（`te-verify-t110` 域外同类复核新发现；`T-013` 既有 `requireSubAccount(int)` 对 `Integer` 字段自动拆箱 NPE）
- **状态**：TODO
- **验收标准**：
  1. **`subAccountId` 缺省时**（JSON 缺该字段 ⇒ `null`）现货单据创建**返回可判定的业务错误**（提示「子账户必填」），**不得**为 NPE / HTTP 500 / `10022`；
  2. **补仓库内断言**（现全仓**无任何**测试断言「子账户必填」；`grep 子账户必填 java/src/test` = 0 命中）——新增显式断言「缺省 `subAccountId` ⇒ 拒绝 + 提示子账户必填」；
  3. **反退化自证**：撤销修复后该断言必红；
  4. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/service/SpotService.java`（`requireSubAccount` 调用前加 `null` 前置校验或改 `Integer` 承载）+ `java/src/main/java/com/hedgeguard/web/handler/spot/`（如须补 `subAccountId` 显式校验）+ 对应测试
- **备注（照录）**：
  1. **现象（独立复核实测）**：`SpotService` 的 **5 个 `create*` 方法**（`createPurchaseContract` / `createSalesContract` / `createInventory` / `createWip` / `createProductionPlan`）在可见性断言**之前**调用 `requireSubAccount(int)`，对 `Integer` 字段 `null` **自动拆箱 → NPE**，经 `ErrorMapper` 归为 **`10022` / HTTP 500**。
  2. **可达性**：`SpotContractHandler` 等仅校验 `contractNo`、**未校验 `subAccountId`**；JSON 缺该字段即 `null`。
  3. **与需求不符**：`FR-SPOT-1` 验收 2 期望「提示**子账户必填**」，现为「内部错误」。
  4. **归属**：`requireSubAccount(int)` 系 **`T-013` 既有**，**非 `T-110` 引入**（`T-110` 未改其签名与调用顺序）；故本缺陷归 `T-013` 域，**单独立号**以免掉落。
  5. **非安全绕过**（拒写、未放行）；全仓**无任何**测试断言「子账户必填」（`grep 子账户必填 java/src/test` = 0 命中）⇒ 本单须**补断言**。
  6. **锚点取证（2026-09-21 实测，项目经理）**：`SpotService.requireSubAccount` 定义于 `:1574`（`private static void requireSubAccount(int subAccountId)`）；7 处调用中 **5 个 `create*` 方法**（`:262`/`:296`/`:447`/`:490`/`:837`）**先于** `requireSubAccountVisible` 调用；实体字段 `PurchaseContract.subAccountId` 等为 `Integer`（`Grep 'Integer subAccountId'` 命中 `dao/entity/PurchaseContract.java:19` 等）。**行号以实时 `md5sum` 为准。**
  7. **写者边界**：`SpotService`/`Spot*Handler` 属 **`#java2`**（SPOT 属主 = `T-006`）；与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。

### T-114 补齐 `POST /spot/exposure/recalc`（`recalcForDisplay`）的子账户可见性强制

- **需求追溯**：`FR-AUTH-13`（子账户数据权限）、`NFR-SEC-7`（子账户软隔离 · 服务端强制）；端点属**敞口族**（参照 `FR-SPOT-15`，`SPOT.md:312`~`:325`；**以复核核验为准**）
- **负责人**：backend-engineer#java2
- **优先级**：P1
- **依赖**：T-110（复用其 `requireSubAccountVisible` 范式）
- **估时**：2 人时
- **来源**：新增（`T-112` 实施者第 3 步全仓写路径扫描）
- **状态**：TODO
- **验收标准**：
  1. `SpotService.recalcForDisplay` 接入**子账户可见性强制**（**沿用 `T-110` 的 `requireSubAccountVisible` 同一机制，不得另造**）：按 `exposureId` 取敞口 → 校验调用方对**该敞口所属子账户**可见，不可见 ⇒ 拒（越权 `10004`）；
  2. **反退化自证**：移除该强制后，「越权 `recalc` 被拒」用例必红；
  3. **先校验、后留痕**：若 `recalc` 在不一致时写 `t_spot_change_log` 的 `COMPONENT_RECALC` 留痕，**写留痕须在可见性校验之后**（不得先写后拒 ⇒ 不得对越权对象产生落库副作用）；
  4. `bash tools/arch-check/scan_java_sql.sh` 退出码 **0**；
  5. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/service/SpotService.java`（`recalcForDisplay` 接入可见性强制）+ `java/src/main/java/com/hedgeguard/web/handler/spot/SpotExposureHandler.java`（如需上层装配）+ 对应测试
- **备注（照录）**：**（2026-09-21 立项，来源 = `T-112` 实施者第 3 步全仓写路径扫描）**现象：`SpotService.recalcForDisplay(exposureId, operatorId)` 经 `POST /spot/exposure/recalc` **在线可调用**，**无任何子账户可见性强制**：按 `exposureId` **直读他人子账户敞口**，且在不一致时**写 `t_spot_change_log` 的 `COMPONENT_RECALC` 留痕** ⇒ 属**写路径越权面**（读 + 留痕写），与 `T-110` 修复的 `TE-T109-002` 同族。**不在 `T-112` 的 4 项范围**（其范围为 PRODUCT 写面 + PLAN 写面），故单独立号。**相邻读面**：`GET /spot/exposure/{id}` 走未 scoped 的 `getExposure` **已归 `T-107`**，本单**不重复**。
  1. **写者边界**：`SpotService`/`Spot*Handler` 属 **`#java2`**（SPOT 属主 = `T-006`）；与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。
  2. **锚点取证**：`recalcForDisplay` 定义与路由点行号**以实时 `md5sum` 为准**（立项时点未由项目经理落笔固化行号）。

### T-115 修正 `CROSS_READONLY` 写面放行（写路径拒绝跨账户只读身份；读面语义不变）

- **需求追溯**：`FR-AUTH-13` **处理规则**（「跨子账户下**不得**执行下单、审批、改配置等操作」，`docs/REQUIREMENTS/AUTH.md:280`）〔**锚点订正 2026-09-21**：原引「验收 3」，该条字面仅「跨子账户权限下尝试执行下单/审批操作被拒绝（只读）」、**不含「改配置」**〕、`NFR-SEC-7`（跨子账户权限默认只读）、`docs/DATA_MODEL.md`（`CROSS_READONLY` 只读性**由服务层保证**、写前校验 `perm_type`）
- **负责人**：backend-engineer#java
- **优先级**：P0
- **依赖**：T-102, T-110, T-112（三者均在其写面上暴露该绕过）
- **估时**：0 人时（原 6 人时，**已从总量扣除**）
- **来源**：新增（`te-verify-t112` 独立复核缺陷①（高·真绕过）；系 `T-102`/`T-110` 既有机制、非 `T-112` 引入，但 `T-112` 扩大了暴露面）
- **状态**：**REJECTED**（**重复立项** —— 与既有 `T-098` 范围逐条重合；**成果归 `T-098`**；编号保留留痕、**不计人时**；依 `T-029` 先例）
- **验收标准**：
  1. `AuthService` **写面**断言**拒绝** `CROSS_READONLY`（`PERM_CROSS_READONLY`）——当前 `assertSubAccountVisible` 对任意 `PERM_CROSS_READONLY` **直接放行** ⇒ 可写「他人」子账户；须修正为**写路径拒写**（越权 ⇒ `10004`）；
  2. **读面语义不变**：`visibleSubAccountIds` 的「哨兵 `0` ⇒ 不加过滤」**保持不变**（`CROSS_READONLY` 的读面 = 跨账户可见）；
  3. **全域横扫** `assertSubAccountVisible` 的**全部写面调用方**（`T-102` `requireWritablePlan` 一族、`T-110` SPOT 8 处、`T-112` PRODUCT 4 + PLAN 4 处、`T-109` 3+4 处），**逐条确认修正后拒绝**；
  4. **不得**把**只读**端点一并拒掉（防**误拒**）：读面调用方须逐一核「仍放行」；
  5. **反退化自证**：移除「写面拒绝 `CROSS_READONLY`」判定后，「跨账户只读身份写入被拒」用例必红；
  6. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/service/AuthService.java`（写面断言拒绝 `CROSS_READONLY`）+ 对应测试
- **备注（照录）**：
  1. **需求依据（三条，均与现行实现冲突 ⇒ 现行实现作废）**：`docs/DATA_MODEL.md`（`CROSS_READONLY` 只读性**由服务层保证**、写前校验 `perm_type`）；`FR-AUTH-13` **处理规则**（「跨子账户下**不得**执行下单、审批、改配置等操作」，`docs/REQUIREMENTS/AUTH.md:280`）〔**锚点订正 2026-09-21**：原引「验收 3」、字面不含「改配置」〕；`NFR-SEC-7`（跨子账户权限**默认只读**）。
  2. **实证**：独立复核以**真实 `AuthService` 探针**经 `VarietyService.putWhitelist` **成功写入他人子账户白名单行** ⇒ **真绕过**，非口径差异。
  3. **修正范围**：`AuthService` **写面**断言拒绝 `CROSS_READONLY`；**读面**（`visibleSubAccountIds` 的「哨兵 `0` ⇒ 不加过滤」）**保持不变**；须**全域横扫** `assertSubAccountVisible` 的**全部写面调用方**（`T-102` `requireWritablePlan` 一族、`T-110` SPOT 8 处、`T-112` PRODUCT 4 + PLAN 4 处、`T-109` 3+4 处），逐条确认修正后拒绝；**不得**把**只读**端点一并拒掉（防**误拒**）。
  3b. **预期会变红的既有用例**：断言「`CROSS_READONLY` 放行」的用例断言的是**错误口径**，须按新口径改写；要求实现方**逐条列出被改写的用例 + 旧/新断言 + 为何旧断言错**。
  4. **保留的开放设计问题**：`putQuota` **层③ `ACCOUNT_PRODUCT`**（目标为**账户哨兵 `0`**）**不适用子账户可见性**——本修正**不改变**该结论；其授权点仍归**架构裁定**（已在 `T-112` 备注第 8 项登记）。
  5. **写者边界**：本任务主体改 `AuthService`（属 **`#java`**）；横扫的写面调用方多属 `#java2`（PRODUCT/SPOT/PLAN 链）——**以读/核为主**，若须改动他属文件，按 `TASKS.md` §5.1「跨实例共享接口须先冻结再并行」与**单写者纪律**协调。与 `#java2` 链**同一 `java/**`、同一时刻只允许一实例写**。
  6. **与 `T-098` 的关系**：`T-098` 登记的是「`CROSS_READONLY` **只读强制点**（8 类写操作拒绝 → `10004` + 留痕）」，本任务为其**同族**、**须协调不重复**：`T-115` 收口的是「`assertSubAccountVisible` **写面**对 `CROSS_READONLY` 的**放行**」这一**具体绕过**（复核已实证），`T-098` 的通用写操作强制点可在其落地时**吸收**本判定，避免双实现。
  7. **（2026-09-21）裁定 `REJECTED`** —— 与既有 `T-098` 范围**逐条重合**（连错误码 `10004`、留痕表 `t_unauthorized_attempt`/`R-AUTH-8`、正/反双向测试要求均一致）⇒ 属**重复立项**；其实施成果由 **`T-098` 收受**；**编号保留留痕、不计人时**（依 `T-029` 先例）。**动因留痕**：编排者立号时未扫整张既有任务表。

### T-116 PRODUCT 读取面「需求面 > 实现面」缺口补齐（`FR-PRODUCT-5` 输出列 + `FR-PRODUCT-8` 导出筛选一致）

- **需求追溯**：`FR-PRODUCT-5` 输出列（`docs/REQUIREMENTS/PRODUCT.md:149`：「品种列表（含状态、分类、**子账户**、**最近变更时间**）」）、`FR-PRODUCT-5` 输入列（`:148`「筛选条件（品种编码/名称关键字、状态、分类、子账户）」）、`FR-PRODUCT-8` 验收 1（`:198`：「在『状态=启用』筛选下导出，文件仅含启用品种，**条数与列表一致**」）、`T-105` 产出面
- **负责人**：backend-engineer#java2（PRODUCT 属主 = `T-005`）
- **优先级**：P1
- **依赖**：T-105
- **估时**：3 人时（依据见「备注」）
- **来源**：新增（`te-verify-t105` 独立复核「范围外观察」第 1 项 + 第 3 项；编排者 2026-09-21 裁定立项，项目经理登记）
- **状态**：TODO
- **验收标准**：
  1. **`GET /product` 支持筛选**（`FR-PRODUCT-5` 输入列 `:148`）：`listProducts` 增筛选参数（品种编码/名称关键字、状态、分类、子账户），下推至 `COUNT` 与 `SELECT` **两条 SQL**；
  2. **`GET /product/export` 与列表筛选一致**（`FR-PRODUCT-8` 验收 1 / 处理规则 `:196`）：`exportProducts` 接受**同一筛选口径**，导出条数与列表一致、仅含符合筛选的品种；
  3. **输出列补齐**（`FR-PRODUCT-5` 输出列 `:149`）：`ProductView` 增「子账户」「最近变更时间」字段（「最近变更时间」取值口径须写明：`product_change_log` 最新一条 或 `updated_time`）；
  4. **用例**：① 状态筛选「停用」⇒ 结果全为停用；② 导出与列表在同一筛选下**条数一致**；③ 输出含新增两列；且**移除筛选 / 列后用例必红**；
  5. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/.../service/VarietyService.java`（`listProducts`/`exportProducts` 增筛选）、`java/.../web/handler/product/ProductHandler.java`（`ProductView` 增列）＋ 对应测试
- **备注**：**来源 = `te-verify-t105` 独立复核「范围外观察」（该复核者未立号、未改文档）**。第 1 项（「导出与列表筛选一致」**当前不成立**：`listProducts`/`exportProducts` **均无筛选参数**）+ 第 3 项（`ProductView` **未含**「子账户」「最近变更时间」）同属 **PRODUCT 读取面「需求面 > 实现面」缺口** ⇒ **合并为一个任务**（同模块、同来源、均属输入/输出面缺口；单列过细 ⇒ 依 §5.1「不到半天 ⇒ 合并到相邻任务」）。**估时判据（项目经理）**：两条端点的筛选入参 + 输出列补齐 + 用例 ⇒ **3 人时**。**写者边界**：`#java2`（PRODUCT 属主 `T-005`），与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。

### T-117 修正 PRODUCT 白名单可见性 over-inclusive（子查询补 `status='ENABLED'`）

- **需求追溯**：`FR-PRODUCT-5` 处理规则（「**仅返回当前用户有权限查看的子账户范围内的品种**」，`docs/REQUIREMENTS/PRODUCT.md:147`）；载体 = `t_sub_product_whitelist`（`docs/DEPLOY/schema/m1_product.sql:158`~`:189`：`status ∈ {ENABLED,DISABLED}`、**移出白名单 = 置 `DISABLED` 保留历史**）；`FR-AUTH-13`；`NFR-SEC-7`
- **负责人**：backend-engineer#java2（PRODUCT 属主 = `T-005`）
- **优先级**：P1
- **依赖**：T-105
- **估时**：2 人时（依据见「备注」）
- **来源**：新增（`te-verify-t105` 独立复核「范围外观察」第 2 项，**判缺陷：可见性过宽**；编排者 2026-09-21 裁定立项，项目经理登记）
- **状态**：TODO
- **验收标准**：
  1. **可见品种子查询过滤 `status`**：`DmProductDao` 的可见主键谓词（现 `:181` `id IN (SELECT product_id FROM t_sub_product_whitelist WHERE <scope>)`）**追加 `AND status = 'ENABLED'`** —— 已移出（`DISABLED`）白名单的品种**不得**算入可见集；
  2. **`GET /product` 与 `GET /product/export` 两处同口径**（均消费同一可见谓词）；
  3. **用例**：构造「曾入白名单、后置 `DISABLED`」的行 ⇒ 该品种**不出现在**列表与导出；且**移除 `status` 过滤后该用例必红**（反退化自证）；
  4. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/.../dao/impl/DmProductDao.java`（可见子查询追加 `status` 谓词）＋ 对应测试
- **备注**：**来源 = `te-verify-t105` 独立复核「范围外观察」第 2 项，判缺陷（可见性过宽 / over-inclusive）** —— 子查询**不带 `status='ENABLED'`**，而「移出白名单 = 置 `DISABLED` 保留历史」，故**已移出品种仍算「可见」**。**跨页 tie-break 与分页稳定性**归 `T-096`（与本任务无关）。**估时判据（项目经理）**：单谓词追加 + 双向用例 ⇒ **2 人时**（与 `T-099` 同档）。**写者边界**：`#java2`（PRODUCT 属主 `T-005`）；**与 `T-105` 同写域**（`DmProductDao`），须**单实例串行**。

### T-118 补齐全局 / 账户级对象写面的功能权限强制点

- **需求追溯**：`FR-AUTH-13` **处理规则**（「跨子账户权限默认只读……**不得执行下单、审批、改配置等操作**」，`docs/REQUIREMENTS/AUTH.md:280`）、`NFR-SEC-7`（子账户软隔离 · 服务端强制）、`R-AUTH-8`（越权尝试必留痕）
- **负责人**：backend-engineer#java2（PRODUCT 属主 = `T-005`）
- **优先级**：P0
- **依赖**：**T-119**（层③断言器：`assertCanConfigureAccountLevel`；**2026-09-21 由 `T-049` 订正而来**）
- **估时**：5 人时（依据见「备注」）
- **来源**：新增（`tl-quota-authz` 技术席实测「全局对象写面零门禁」类缺口；用户 2026-09-21 裁定「同类单独立号」；项目经理登记）
- **状态**：TODO
- **验收标准**：
  1. **功能权限强制点落地**：`VarietyService` 的 `createProduct` / `disableProduct` / `enableProduct` / `putUnit` / `putContract` / `putSpotMapping` / `importProducts` **七个写方法**各接入**功能权限断言**（均为**零权限门禁**，现仅字段级 `requireText`）；越权 ⇒ 拒绝（错误码与 `R-AUTH-8` 留痕口径须写明）；
  2. **判据口径**（依 `FR-AUTH-13` 处理规则）：主体 = **有权配置全局 / 账户级对象者**（财务 / 风控岗或系统管理员，具体口径以 `CH-012` 为准）；**不得**以子账户可见性（`assertSubAccountVisible`）替代（这些方法目标为**全局对象**、非子账户维度）；
  3. **不得误拒**：合法配置主体仍可写（反向用例）；
  4. **用例**：每个写方法 ≥ 1 例正分支（有权 ⇒ 成功）+ 1 例反分支（无权 ⇒ 拒绝 + 留痕）；且**移除断言后反分支用例必红**（反退化自证）；
  5. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/.../service/VarietyService.java`（七个写方法接入功能权限断言）＋ `java/.../service/AuthService.java`（若断言落此）＋ 对应测试
- **备注（照录 + 依赖/估时判据）**：**同属「全局 / 账户级写面无功能权限强制点」类**；**层③（`ACCOUNT_PRODUCT`，账户哨兵 `0`）的修复不覆盖本类**（层③属**额度**写面、本类属**对象配置**写面）；**需求依据 = `FR-AUTH-13` 处理规则**（「不得执行下单、审批、**改配置**等操作」）。**来源事实（`tl-quota-authz` 实测）**：`createProduct`/`disableProduct`/`enableProduct`/`putUnit`/`putContract`/`putSpotMapping`/`importProducts` **一律零权限门禁**（与层③同源）。**⚠️ 依赖依据（项目经理实测提示、待复核）**：编排者给的依赖 = **`T-049`**（其中含层③实现 `assertCanConfigureAccountLevel`）；**本席实测**：层③机制落在 `VarietyService.putQuota`（**非 `T-049` 的 `FundService`**），且该断言器**当前无任务承载**（`CH-012` 已起草、待用户确认）⇒ **若 `T-049` 不交付该断言器，本任务依赖不成立**；**建议**改依赖「承载 `assertCanConfigureAccountLevel` 的任务（待立号）/ `T-010`（AUTH 功能权限）」，**请编排者复核**（本席按派单原值登记 `T-049`、不擅改）。**估时判据（项目经理）**：七个写方法的断言接入 + 正反双向用例 + 反退化 ⇒ **5 人时**（类比 `T-110` 的 8 处 / 8 人时、`T-098` 的单点 / 3 人时：本类 7 处、单服务、单轴向 ⇒ 取 5 人时）。**写者边界**：`#java2`（PRODUCT 属主 `T-005`）；若断言落 `AuthService` 则涉 `#java` 写权 ⇒ 须按 §5.1「跨实例共享接口须先冻结再并行」与**单写者纪律**协调。
  **（2026-09-21 依赖订正 · 只加不改）**：原记依赖 `T-049`；经实测，层③断言器 `assertCanConfigureAccountLevel` 由 **`T-119`** 承载（`T-049` 为 `FundService` 域，**不交付该断言**）⇒ **依赖改为 `T-119`**。
  **📌（2026-09-21 `CH-012` 依赖状态登记 · 只加不改）** 依赖 `CH-012`（层③账户级写面授权口径，**L2 澄清性**）——**用户暂缓确认**（同日裁决）⇒ **`CH-012` 确认前本任务不得开工**。**主体范围已裁定**：可写主体 = **财务/风控岗 或 系统管理员**（**含 `SYS_ADMIN`**；依据 `FR-PRODUCT-11` actor 原文 + 对称先例 `FR-FUND-13` + `INDEX.md §4` 系统管理员职责含「风险阈值」+ `R-AUTH-10` 排除的是「风控**判定**」而非「配置」）——**该范围随 `CH-012` 一并待最终确认**。

### T-119 补齐 `putQuota` **层③**（`ACCOUNT_PRODUCT` / 账户哨兵 `0`）**写面授权门禁**（M1 = 角色近似）

- **需求追溯**：`FR-AUTH-12`（功能权限维度；`AUTH.md` §2）、`FR-AUTH-13`、`NFR-SEC-7`、`R-AUTH-8`（越权尝试必留痕）；`CH-012`（需求侧澄清性变更、**待用户确认**）；依据 `ARCHITECTURE.md` §8.2③ 新增条目（登记时点 `v1.5.11`；**以实时 `md5sum` 为准**）
- **负责人**：backend-engineer#java（断言器 `assertCanConfigureAccountLevel` 落 `AuthService`）
- **优先级**：P0
- **依赖**：`CH-012`（需求侧待用户确认）＋ **播种前置**（`t_role`/`t_user_role` 预置数据须与实现**同批**；现**零播种** ⇒ 否则**全拒 = 误拒**）
- **估时**：5 人时（依据见「备注」）
- **来源**：新增（`tl-quota-authz` 技术席层③实测 + 编排者 2026-09-21 裁定立项；**层③断言器原无编号承载** ⇒ 本号补登记）
- **状态**：TODO
- **验收标准**：
  1. **断言器落地**：`AuthService` 新增 `assertCanConfigureAccountLevel(userId)`（M1 = **角色近似**：财务/风控岗 **或** 系统管理员）；`VarietyService.putQuota` 的**层③**（`ACCOUNT_PRODUCT`、目标 = 账户哨兵 `0`）写路径接入该断言；
  2. **不得套用子账户可见性**：层③目标为**账户级**（哨兵 `0`）⇒ **不适用** `assertSubAccountWritable`（其放行条件 = 「`id ≠ 哨兵 0` 且 `id == 目标`」，与哨兵 `0` **结构性互斥** ⇒ 套用即**误拒**）；
  3. **失败语义**：无权 ⇒ 拒绝；错误码 **`10003`**（**不新造**）＋ `R-AUTH-8` 留痕；
  4. **播种**：`t_role`/`t_user_role` 预置数据与实现**同批**交付（否则无权者全拒 = 误拒）；
  5. **改判既有用例**：同步改写 2 条锁定现状的用例（`VarietyT112VisibilityTest.putQuotaAccountLevelNotSubAccountScoped`、`realAuthServiceAllowsAccountLevelQuota`），注释写明「**旧设计决定已被推翻 + 理由**」；
  6. **正反双向用例** + **反退化自证**（移除断言 ⇒ 反分支必红）；
  7. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/.../service/AuthService.java`（`assertCanConfigureAccountLevel`）＋ `java/.../service/VarietyService.java`（层③接线）＋ `docs/DEPLOY/schema/data-init.sql`（AUTH 预设段：`t_role`/`t_user_role`）＋ 对应测试
- **备注（照录 + 估时判据）**：① **不适用子账户可见性**（`assertSubAccountWritable` 以 `target=0` 调用**无人放行** —— 放行条件为「`id ≠ 哨兵 0` 且 `id == 目标`」，两者**结构性互斥**；**套用即误拒**）；② 归**功能权限维度**（`AUTH.md` §2；`FR-AUTH-12`）；③ **M1 = 角色近似**（财务/风控岗 **或** 系统管理员 ⇒ `AuthService.assertCanConfigureAccountLevel`）→ **M2 = 功能权限 `t_role_permission.perm_code`**；④ **失败码 `10003`**（不新造）＋ `R-AUTH-8` 留痕；⑤ **硬前置：`t_role`/`t_user_role` 播种必须与实现同批**（现为零播种 ⇒ 否则**全拒 = 误拒**）；⑥ **须同步改 2 条锁定现状的用例**（`VarietyT112VisibilityTest.putQuotaAccountLevelNotSubAccountScoped`、`realAuthServiceAllowsAccountLevelQuota`）并在注释写明「旧设计决定已被推翻 + 理由」；⑦ 依据 `ARCHITECTURE.md §8.2③` 新增条目（`v1.5.11`）。**估时判据（项目经理）**：新增断言器 + 层③接线（单点）+ 播种 + 2 条既有用例改判 + 正反双向 + 反退化 ⇒ **5 人时**（与 `T-102` 同档：单服务写面 + 断言器接线 + 双向用例；高于 `T-098` 单点 3 人时，因含**播种前置**与**既有用例改判**）。**写者边界**：断言器落 `AuthService` ⇒ **`#java` 写权**；`VarietyService` 属 `#java2` ⇒ **跨写域**，须按 §5.1「契约冻结先于并行」与**单写者纪律**协调（**同一 `java/**`、同一时刻只允许一实例写**）。
  **📌（2026-09-21 `CH-012` 依赖状态登记 · 只加不改）** 依赖 `CH-012`（层③账户级写面授权口径，**L2 澄清性**）——**用户暂缓确认**（同日裁决）⇒ **`CH-012` 确认前本任务不得开工**。**主体范围已裁定**：可写主体 = **财务/风控岗 或 系统管理员**（**含 `SYS_ADMIN`**；依据 `FR-PRODUCT-11` actor 原文 + 对称先例 `FR-FUND-13` + `INDEX.md §4` 系统管理员职责含「风险阈值」+ `R-AUTH-10` 排除的是「风控**判定**」而非「配置」）——**该范围随 `CH-012` 一并待最终确认**。

### T-120 `E4` 判据接线落地（默认根改三目录集合 `cpp/src/{order_engine,risk_engine,risk_state}` + `arch-check.sh` / `README` 同步）

- **需求追溯**：`ARCHITECTURE.md` §3.0.2 `E4` 脚注④（**诚实披露**：判据默认扫描根口径）+ `§3.13`（判据脚本聚合入口 `arch-check.sh` + CI 接线）、`NFR-PERF-1`（交易路径无锁 / 无动态分配 / 无同步 I/O）、`NFR-MAINT-4/6`
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：`T-031`（C++ 骨架 + `tools/arch-check/arch-check.sh` 聚合入口与 CI 接线）
- **估时**：3 人时
- **来源**：新增（`tl-e4-scope` 的 `ARCHITECTURE.md` §3.0.2 `E4` 脚注④「诚实披露」+ 独立复核 `te-verify-t031` 第 2 点；**判为独立任务、非 `T-031` 缺陷**）
- **状态**：**DONE（授权 + 独立验证）**〔第 34 轮〕（**首轮复核** `te-verify-t120` = **有条件通过**〔缺陷 `TE-T120-001`〕→ `tl-t120` **补修**〔`README` 订正 + `ARCHITECTURE.md` §3.0.2 `E4` 脚注 ⑥「残留风险（已接受）」〕→ **delta 复检 = 通过、0 缺陷**；**编排者 2026-09-21 授权**。**验收证据**：`E4` 由 `SKIP` → **实扫（`N=6`）**、聚合退 `0`、六条 `0/0/0/0/0/0`；`scan_lock_free.sh` 整文件 md5 与首轮冻结点**逐字节相同** ⇒ **判定段未变**；`java/src` / `cpp/src` **零改动**。**空-SDK 全新配置硬证据**：`CTP_USE_FETCH_DEPS=OFF/ON` 两轨 `ctp_smoke` 均 **0 个 `add_test`**（代码链 `cpp/CMakeLists.txt:54 add_subdirectory(ctp_smoke)` **早于** `:61 include(HgDependencies)` ⇒ fetch 逻辑**结构上不影响** `rsk02` 注册）；**`:128` 为注册守卫**（首轮 `:129` 系笔误、已更正）。**补修后锚点（以实时 `md5sum` 为准）**：`tools/arch-check/README.md` `04523ee239b22da61a49c5241762fc01` / 220 行；`docs/ARCHITECTURE.md` `3c7e2e1ac202698cb453bbf2e1b302e5` / **2780 行**（**仍 `v1.5.13`**、**同轮追加未升版**〔与 `v1.5.7` / `v1.5.11` 同轮追加先例一致〕）；`scan_lock_free.sh` `64fb6c1a…` / 277、`arch-check.sh` `306daf0a…` / 126 **未变**。**证据章落点（分支 B）**：`docs/TEST_RESULT.md` 章 ``# `T-031` / `T-120` 独立复核证据（事后补录，2026-09-21）``（**由 `te-verify-t120` 落笔**；**已落**：章起 `:2115`、止 `:2256`；文件 `5e94243cb4e5ba02242904aa666f95f7` / **2256 行**〔登记时点；**以实时 `md5sum` 为准**〕；**纯追加已证**〔`head -2111 | md5sum` = 追加前基线 `fac8b8da…`〕；原登记时点该章尚未落）。）
- **验收标准**：
  1. **默认扫描根改三目录集合**：`E4` 脚本（`tools/arch-check/scan_lock_free.sh`）默认根由「指向**永不存在的老路径**」改为 **`cpp/src/{order_engine,risk_engine,risk_state}`**（T4 热路径三模块；「交易路径」定义 = **执行在 T4 上的代码**），依据 `ARCHITECTURE.md` §3.0.2 `E4` 脚注④（`v1.5.12`；**以实时 `md5sum` 为准**）；
  2. **聚合入口与说明同步**：`tools/arch-check/arch-check.sh` 调用 `E4` 时**传 `--root`**（三目录集合）或使默认根生效；`tools/arch-check/README.md` 同步；
  3. **判据不再空转**：真仓库运行 `E4` **退出码非 `SKIP`**（对三目录集合实际生效）；负例（临时摘除 `--root` / 默认根回退，或向三目录注入 `fsync`/动态分配/同步 I/O）**必须变红**（反退化自证）；
  4. **不误报**：父目录 `cpp/src` 作根**已实测证伪**（3 处合法 `fsync`/`send`/`recv` 被误判为红）⇒ 改判据默认根，对交易路径三目录外的合法调用**不得**误判为红；
  5. `bash -n` 通过；`E4` 的 `exit` 语义与其余五脚本统一（显式不存在 ⇒ `2`、默认 ⇒ `0` `SKIP`）。
- **产出**：`tools/arch-check/scan_lock_free.sh`（默认根）+ `tools/arch-check/arch-check.sh`（`E4` 传根）+ `tools/arch-check/README.md` + 自测记录
- **备注**：**来源事实** —— 独立复核 `te-verify-t031` 第 2 点发现 **`E4` 恒 `SKIP`**（**判据未生效**）：默认根指向**永不存在的老路径**，且聚合入口 `arch-check.sh` **不传 `--root`** ⇒ 判据在 CI 中**空转**；扫描根口径由 `tl-e4-scope` 裁定 = **`cpp/src/{order_engine,risk_engine,risk_state}`**，`ARCHITECTURE.md` `v1.5.12` 已登记（**以实时 `md5sum` 为准**）。**边界**：本任务**仅动判据默认根 / 聚合入口 / README**，**不改 `E4` 检测逻辑**（`scan_lock_free.sh` 判定段归 `T-092`/`T-095`）；**判为独立任务、非 `T-031` 缺陷**（`T-031` 交付的聚合入口 + CI 接线**本身成立**，缺的是「默认根口径落地」这一独立工作项）。**⬆ 判据脚本落点边界（立号前已实测全表）**：`T-092`（`DONE`）= `E4` 脚本**本体**、`T-095`（`DONE`）= 六脚本**形态 / 退出码一致性收口**、`T-031`（`TODO`）= 聚合入口 + CI 接线 —— **三者均不含「改判据默认根」**。**写者边界**：`tools/**` 与 `docs/ARCHITECTURE.md` 由 `tech-lead` 单写者持有（**同一时刻只允许一实例写**）。
  **📌（2026-09-21 `T-120` 交付登记 · 只加不改）** **冻结点 `2026-09-21 18:25:01`**（三次取样至 `18:26:07` 不变）；交付物与指纹（**均以实时 `md5sum` 为准**）：`tools/arch-check/scan_lock_free.sh`（277 行 / `64fb6c1a99583ce73c0e4a1c54758a69`）、`tools/arch-check/arch-check.sh`（126 行 / `306daf0aca2e625243ad16e562a1641a`）、`tools/arch-check/README.md`（211 行 / `8a68c9ab4b40198db8c39f06b7e7646c`）、`docs/ARCHITECTURE.md`（**升版 `v1.5.13`**，2774 行 / `4b7d5602f6be68f7243c450a5e2d9158` —— 本任务立项时所述「登记时点 `v1.5.12`」为该轮事实；**现行以实时 `md5sum` 为准**）。**实现要点**：默认扫描根 = `cpp/src/{order_engine,risk_engine,risk_state}` **并集**；`SKIP` 条件 = 三目录**全部**不存在；显式 `--root` 单目录语义与「不存在 ⇒ 退 `2`」护栏不变；`arch-check.sh` **逻辑零改动**（仅注释 / `usage`）；**判定段逐字节未变**（`STRIP_AWK` `2d45b42d…` / `DETECT_AWK` `93257337…` / `WARN_RE` `a676da63…`）。**自测（产出方自述，待独立复核）**：聚合退 `0`、六脚本 `0/0/0/0/0/0`、**`E4` 实扫「待扫描文件: 6 个」（非 `SKIP`）**；注入 `std::mutex` ⇒ `1`、删净 ⇒ `0`；`--root /no/such` ⇒ `2`；`E4` 现随 `ctest` fixture `arch_check`（`cpp/CMakeLists.txt:75`）与 `mvn validate`（`java/pom.xml` exec）**实际执行** ⇒ `NFR-SEC-9` 的 `E4` 一项现成立。**`ARCHITECTURE.md` `v1.5.13`**：§3.0.2 `E4` 表行 + 脚注④ 由「恒 `SKIP` = 未生效」回写为「已接线 / 已生效」+ §13 新增 `v1.5.13` 行 + 抬头「编写时间」追加；**`FR` 计数 `184` 未变、消息 40 / 错误码 62 未变、线格式 `VER` 仍 `0x0100`；`java/**`、`cpp/**` 零改动**。**状态 = 已完成，待独立复核**（`te-verify-t120` 进行中；**不置 `DONE`**）。
  **📌（2026-09-21 `T-120` 补修 delta 登记 · 只加不改）** **`te-verify-t120` 首轮复核 = 有条件通过（= 退回）**；`tl-t120` 已完成**两项订正**（冻结于 `2026-09-21 18:34:26`，三次取样不变）。**补修后锚点（均以实时 `md5sum` 为准）**：`tools/arch-check/README.md` = **`04523ee239b22da61a49c5241762fc01` / 220 行**（原 `8a68c9ab…` / 211，**旧值为历史**）、`docs/ARCHITECTURE.md` = **`3c7e2e1ac202698cb453bbf2e1b302e5` / 2780 行**（原 `4b7d5602…` / 2774，**旧值为历史**；**仍 `v1.5.13`，同轮追加、未升 `v1.5.14`** ⇒ 本任务立项主引用「登记时点 `v1.5.12`」**不变**）；`tools/arch-check/scan_lock_free.sh`（`64fb6c1a…` / 277）与 `arch-check.sh`（`306daf0a…` / 126）**未变**。⚠️ `README.md` 在首提 `59b6f70` 中**未跟踪**（`git status` = `??`）⇒ 只能以 `md5` + 行数复现。**补修内容三笔**：① **`TE-T120-001`（一般，首轮复核提出）已订正** —— `README.md` 删去「`FetchContent` 取回 CTP」这一**错误理由**；三轨表**增 `CTP_SDK_ROOT` 列**并标明 `6/6` 为**条件性结果**（前置 = 该构建目录的 `CTP_SDK_ROOT` 指向含 `ThostFtdcUserApi.h` 的目录 ⇒ `CTP_FOUND=ON`）；表前增「注册条件」块、表后增「**不是恒真**」警示。② **`ARCHITECTURE.md` §3.0.2 `E4` 脚注新增 ⑥「残留风险（已接受）」**（`:383` 起，只加不改）—— 登记「默认扫描根**部分缺席**时只扫现存者、退出码仍 `0`」的残留风险 + **编排者裁定**（不采纳「改退出码」：与 `v1.5.12` 既定口径冲突 / 会把「组件尚未铺开」误判为架构偏离 / 已非完全静默）+ 三条理由。③ **兜底判定结论（`tl-t120` 实测）=「部分兜底；对『已同步的完整迁移』无兜底」** —— **有效兜底**（覆盖「删 / 改名**未同步**」）：`cpp/src/CMakeLists.txt` 逐一 `add_subdirectory(<mod>)` + 链入 `hg-trading` + `cpp/src/main.cc` 逐一 `#include "hg/<mod>/module.h"` ⇒ **构建失败（CI 红）、不静默**；**部分兜底**（`hg.skeleton_modules`）：只断言六模块 `module_name()` **字面量**，**不**把 `E4` 扫描根集合与实际目录绑定；**⇒ 对「改名并同步更新 `CMakeLists` / 包含路径」的完整迁移：当前无自动化兜底**，由**代码审查**承接（**承接点 = `T-060` 验收标准第 6 条**）；另 `E1`/`E2`/`E3` 均**按内容扫描、不钉目录名**，`E6` 只扫 `web` ⇒ **§3.1–§3.6 无其它目录名强制点**。**状态 = 已完成，待独立复核**（**delta 复检**尚未回；**不置 `DONE`**）。

## 3. 依赖图与关键路径

> 图为**主干依赖**（略去同层并列支线）；`🚪` = 门禁节点。

```
[M1 门禁前置]
T-001 ✔ ──> T-089 ✔ ──> T-002 ──┐
                                │
[M1 Java 业务组件]               │
  T-003 <───────────────────────┘
    ├──> T-004 ──┬──> T-005 ──> T-012 ──> T-018
    │            ├──> T-006 ──> T-013 ──> T-014 ──> T-019
    │            ├──> T-007 ──> T-015 ──> T-016 ──> T-020
    │            ├──> T-008 ──┬────────────────────┐
    │            ├──> T-009 ──┼──────────────────┐ │
    │            ├──> T-010 ──┴──> T-021 <───────┘ │
    │            └──> T-011                          │
    ├──> T-017 ──> (T-018/T-019/T-020/T-021) <──────┘
    └──> T-022 ──> T-023 ──> T-024
                                             T-025（审查，收口 M1）
T-005/T-006/T-007 ──> T-109（补齐 CONFIG / SPOT / PLAN「已声明未注册」端点 15 条 —— CONFIG 9 / SPOT 5 / PLAN 1；`#java2`，M1 窗口；**须先于 `T-097` 判据升级完成**，否则 23 条立刻全红） ✔
T-109 ✔ ──┬──> T-110 ✔（SPOT 写面（`T-013` 范围）**子账户可见性强制**〔`#java2`，M1 窗口；沿用 `T-109` 已建立的 `requireSubAccountVisible` 机制；来源 = `te-verify-t109c` 探索性新发现 (A)〕
        └──> T-111（**服务端敞口推导引擎** + 回填 `FR-SPOT-16` 验收 1〔变更后重算敞口〕〔`#java2`，M2 窗口；来源 = `TE-T109-005`〕）
T-110 ✔ ──> T-112（写路径子账户**可见性强制的全域收口**〔PRODUCT 写面 `/product/whitelist` + `/product/quota`；PLAN 写面 `/plan/ratio` + `POST /plan`〕〔`#java2`，M1 窗口；来源 = `te-verify-t110` 域外同类复核〕）
T-013 ──> T-113（修正 SPOT 现货单据创建 `subAccountId` 缺省时的错误呈现〔现 NPE→500 ⇒ 应「子账户必填」〕〔`#java2`，M1 窗口；归属 `T-013`〕）
T-110 ✔ ──> T-114（补齐 `POST /spot/exposure/recalc`（`recalcForDisplay`）的**子账户可见性强制**〔写路径越权面：读 + `t_spot_change_log` 留痕写〕〔`#java2`，M1 窗口；来源 = `T-112` 实施者第 3 步全仓写路径扫描〕）
T-102 / T-110 ✔ / T-112 ──> T-115（修正 `CROSS_READONLY` **写面**放行〔写路径拒绝跨账户只读身份；**读面语义不变**〕〔`#java`，M1 窗口；来源 = `te-verify-t112` 独立复核缺陷①（高·真绕过）；系 `T-102`/`T-110` 既有机制、非 `T-112` 引入，但 `T-112` 扩大暴露面〕）（**⚠️ 2026-09-21：`T-115` 判 `REJECTED` —— 重复立项（与 `T-098` 范围逐条重合）、成果归 `T-098`、编号留痕、不计人时**）

[M2 首周探路 —— 必须先完成（三项）]
T-026 ┐
T-027 ├──> T-030 🚪（铺开许可）
T-028 ┘      （T-029 已 REJECTED —— CH-006 移除 websocketpp，见 §1）

[ M2 首周并行 —— 架构 §3.0.2 判据脚本落点，依赖：无，可与上块并行 ]
T-090（E1）/ T-091（E3）/ T-092（E4）──> tools/arch-check/*.sh
T-093（E6）──────────────────────────> tools/arch-check/check_frontend.sh
T-095（E2）──────────────────────────> tools/arch-check/scan_cpp_sql.sh（按 E5 同法收紧）
        （E5 已落地 scan_java_sql.sh）
        消费方：T-060 / T-088（E1~E6 全量审查）、T-031（接入构建与 CI）
T-031 ──> T-120（**`E4` 判据接线落地**：默认根改 **`cpp/src/{order_engine,risk_engine,risk_state}`** + `arch-check.sh` 传 `--root` + `README` 同步；来源 = `tl-e4-scope` E4 扫描根裁定〔`ARCHITECTURE.md` `v1.5.12`〕+ `te-verify-t031` 第 2 点「`E4` 恒 `SKIP`」；`#tech-lead`，M2 窗口）

[ M2 公共件与安全强制点 —— 架构裁定落点（依赖：`T-096`~`T-099` 依赖无；`T-100` 依赖 `T-016`；`T-101`/`T-102` 依赖 `T-100`；`T-103` 依赖 `T-101`；`T-104` 依赖 `T-103`；`T-105`/`T-106`/`T-107` 依赖 `T-100`；`T-108` 依赖 `T-004`）]
T-096（`PageQuery` 缺省排序方向重载，向后兼容）
T-097（`ApiRoutes` 声明 (方法,路径) 对 + `ModuleRoutesConsistencyTest` 改等值断言）
T-098（`CROSS_READONLY` 只读强制点：8 类写操作拒绝 → `10004` + `t_unauthorized_attempt` 留痕）
T-099（`DmUserSubAccountPermDao.scopeOf` 补 `perm_type` 分支 或 弃用指向 `visibleSubAccountIds`）
T-016 ✔ ──> T-100（`GET /plan` **读取面隔离装配**：`PlanService` 增 `operatorId` + `HedgePlanDao` scoped + `Bootstrap` 装配 + 4 类隔离用例）
        ── 出处：架构 `§4.1.1` 两项约定（`v1.5.7`）+ `§8.2` 强制点/机制订正（`v1.5.7`/`v1.5.8`）+ `§8.2①③④` 主落点（`T-100`）
T-100 ✔ ──> T-101（`GET /plan/{id}/trade-plans` **读取面隔离装配**：`PlanService.listTradePlans` 增 `operatorId` + `TradePlanDao`/`DmTradePlanDao` scoped + `TradePlanHandler` 装配 + 4 类隔离用例；**同性质缺陷换端点**）
        ── 出处：架构 `§8.2③`「其余读取面同须接同一过滤」通则（`ARCHITECTURE.md:2049`，引用时点 `v1.5.8`；**以实时 `md5sum` 为准**）+ `FR-PLAN-15` 验收4 / `FR-PLAN-14` 验收4 / `R-PLAN-7`
T-101 ✔ ──> T-103（`GET /plan/{id}/trade-plans` **补分页/筛选/排序**〔按 `FR-PLAN-15` **输入列**〕：`PlanService.listTradePlans` 增分页/筛选/排序入参并返回 `PageData` + `TradePlanDao`/`DmTradePlanDao` 走 `findPageScoped` + `TradePlanHandler` 解析 `page/size/sort` 与筛选；**隔离不得回退**；**来源 = `T-101` 独立复核 `EXP-T101-1`**）
        ── 出处：`FR-PLAN-15` **输入列**（`PLAN.md:321`）+ `ARCHITECTURE.md` §4.1.1 分页约定 + `CONTRACTS §1.1` `PageData` 信封（**向冻结口径对齐 = 修正违规，非变更契约**）
T-103 ✔ ──> T-104（`FR-PLAN-15` **处理规则**两项：`TradePlanView` 增 `remainingQty`（= 数量 − 已下达指令量）+ `limitReached`（`orderedQty >= planQty`）；**列表与详情同一口径**；前端展示**另派**）
        ── 出处：`FR-PLAN-15` **处理规则**（`PLAN.md:322`；引用时点；**以实时 `md5sum` 为准**）+ `FR-PLAN-14` 验收4；**该项被 `T-101`/`T-103` 两次判为「非本任务范围」⇒ 单独立号**；⚠️ 本项若涉分页排序稳定性，依赖 `T-096`
T-100 ✔ ──> T-102（写面**子账户可见性 / 交易权限强制补齐**：`AuthService.assertSubAccountVisible` 与 `assertCanTrade` 分别接线到写路径〔或给替代强制点〕 + `PlanService.withdraw` 发起人校验〔或 javadoc 订正〕 + 三类拒绝用例 + **同族第四项 ④**：`PlanService.decomposeTradePlans`（`:786`）写路径读取未 scope 的裁定与处理〔来源 = `T-101` 交付披露〕；**`T-100` 专项复核 `EXP-T100-1` 同族发现**）
        ── 出处：`FR-AUTH-13` / `NFR-SEC-7` / `R-AUTH-8`；事件来源 = `docs/TEST_RESULT.md`「`T-100` 专项独立复核」§`T-100.10`（`EXP-T100-1`，引用时点；**以实时 `md5sum` 为准**）
           ⚠️ `§8.2④` 写死边界：`GET /plan` **读取面**装配（`PlanService`+`HedgePlanDao`+`Bootstrap`）须 **`#java` 单实例**改 = **`T-100`**；`T-098`/`T-099` 与之**分工不重叠**（写面／机制处置）；`T-101` **另动** `TradePlanHandler`（同属 `web/`、`#java` 写权），与 `T-100` 主落点**不同行不同类**；`T-102` **另动** `AuthService`/`PlanService` **写路径**（**写面强制点**），与 `T-098`（`CROSS_READONLY` 只读强制）**相关但不同、不合并**；`T-103` **另动** `TradePlanHandler`/`TradePlanDao` 的**分页/筛选/排序**（与 `T-101` 同族、同 `#java` 写权，须单实例串行）；`T-104` **另动** `TradePlanView` / 服务层读取**投影**（列表与详情同口径），与 `T-103` **同族、同 `#java` 写权，须单实例串行**
           🔒 **串行顺序（编排者 2026-09-21 裁定；`T-101` 插于 `T-100` 之后、`T-103` 插于 `T-101` 之后、`T-104` 插于 `T-103` 之后、`T-102` 插于 `T-104` 之后）**：`T-100 → T-101 → T-103 → T-104 → T-102 → T-099 → T-098 → T-096 → T-097`（同一时刻只允许一个 `#java` 实例写；`T-097` 收束）
T-100 ✔ ──> T-105 / T-106 / T-107（**PRODUCT / SPOT 读取面口径收口**〔`tl-triage` 全模块只读普查 + 编排者 2026-09-21 裁定立项〕：`T-105` = `GET /product` 补分页 + 默认创建时间倒序 + `t_sub_product_whitelist` 子账户隔离 + `GET /product/export` 对齐；`T-106` = `GET /product/{id}` + `GET /product/quota` 越权校验；`T-107` = `GET /spot/exposure` 补分页 + 默认倒序 + 隔离 + `GET /spot/exposure/{id}` 隔离；**均须四类隔离用例 + 反退化自证 + 真实 DAO SQL 形状断言**）
        ── 出处：`FR-PRODUCT-5`/`FR-PRODUCT-6`/`FR-PRODUCT-8`/`FR-PRODUCT-11`（`PRODUCT.md`）+ `FR-SPOT-15`（`SPOT.md:312`~`:325`）+ `FR-AUTH-13` / `NFR-SEC-7`；**载体裁定** = `t_sub_product_whitelist`（`m1_product.sql:160`–`:188`，编排者查实「疑似→确定」）
           ⚠️ 写者边界：`VarietyService`/`ProductHandler`/`QuotaHandler`/`SpotService`/`SpotExposureHandler`/`DmProductDao`/`DmExposureDao` 属 **`#java2`**（PRODUCT/SPOT 属主 `T-005`/`T-006`）；与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**
           🔒 `#java2` 串行顺序（编排者 2026-09-21）：`T-016`(CR-002 修复，严重级优先) → `T-105` → `T-106` → `T-107` → `T-108`（DAO 实现补齐，依赖 `T-004`）→ `T-109` ✔ → `T-110` ✔（SPOT 写面可见性强制）→ `T-112`（写路径可见性全域收口：PRODUCT + PLAN 写面）→ `T-113`（SPOT 现货单据 `subAccountId` 缺省错误呈现修正）→ `T-111`（敞口推导引擎 + `FR-SPOT-16` 验收 1 回填）
           🔒 **第 28 轮新增（均 `#java2`）**：`T-105 ✔` ──> **`T-116`**（PRODUCT 读取面「需求面 > 实现面」缺口：`FR-PRODUCT-5` 输出列「子账户 / 最近变更时间」+ `FR-PRODUCT-8` 导出与列表筛选一致；来源 = `te-verify-t105` 范围外观察第 1/3 项）；`T-105 ✔` ──> **`T-117`**（PRODUCT 白名单可见性 **over-inclusive** 修正：可见子查询补 `status='ENABLED'`，已移出品种不再算「可见」；来源 = `te-verify-t105` 范围外观察第 2 项，**判缺陷**）；**`T-118`**（补齐全局 / 账户级对象写面功能权限强制点：`createProduct`/`disableProduct`/`enableProduct`/`putUnit`/`putContract`/`putSpotMapping`/`importProducts` 现**零权限门禁**；来源 = `tl-quota-authz` 实测 + 用户「同类单独立号」裁定；**依赖依据 T-049 待复核**）
           🔒 **第 29 轮新增 / 订正**：**`T-119`**（补齐 `putQuota` 层③〔`ACCOUNT_PRODUCT` / 账户哨兵 `0`〕**账户级写面授权门禁**：`AuthService.assertCanConfigureAccountLevel`〔M1 角色近似〕+ 层③接线 + `t_role`/`t_user_role` 播种同批；**`#java`**〔断言器落 `AuthService`〕/M1/P0/**5 人时**；依赖 `CH-012`〔需求侧待确认〕+ 播种前置）；**`T-118` 依赖由 `T-049` 订正为 `T-119`**（层③断言器原无编号承载）。
T-004 ✔ ──> T-108（补齐 **AUTH 3 个**未实现 DAO 的实现类：`PermissionChangeLogDao`/`PositionGroupDao`/`RolePermissionDao`；`AuthorizationDao` 由 `T-016` 顺带实现；CONFIG 侧 4 个已由 `T-109` 交付〔第 19 轮收窄〕；**新建 `dao/impl/Dm*.java`，不改接口签名**）

[M2 双组件开发 —— 依赖 T-030]
T-031 ──┬──> T-032 ──┬──> T-038 ──┐
        ├──> T-033 ──┼──> T-036 ──┼──> T-039 ──┐
        ├──> T-034 ──> T-035 ─────┘            │
        └──> T-037 ──────────> T-041 ──────────┤
T-042（Java 桥接，依赖 T-030）─┬───────────────┤
T-028（Java WS 选型/最小可行）─┴──> T-040（Java 行情 WS，生产级）┤
T-043 / T-044 / T-045（DDL，依赖 T-004）         │
T-042 + T-043 ──> T-046 ──> T-047 ──> T-048     │
T-042 + T-045 ──> T-049 ──> T-051 / T-052       │
T-042 ──> T-050                                 │
T-046/T-047/T-049 ──> T-053/T-054/T-055         │
T-056 ──> T-057 <───────────────────────────────┘
T-057 + T-046~T-049 + T-052 ──> T-058 ──> T-060（审查，收口 M2）

[M3]
T-061（首增量 t_param_dispatch）+ T-094（余 10 张 RISK 表）
        ──┬──> T-063 ──> T-066 ──┐
          ├──> T-064 ──┐          │
          └──> T-065 ──┴──> T-070 │
T-062 ──┬──> T-067 ──┬──> T-071 │
        ├──> T-068 ──┘          │
        └──> T-069 ─────────────┤
T-072 ──> T-073 <───────────────┘
T-069 + T-071 ──> T-074 ──> T-075（审查，收口 M3）

[M4]
T-076 ──┬──> T-077 ──┬──> T-078 ──┐
        └──> T-079 ──┴──> T-080 ──┴──> T-083 ──> T-085 ──> T-086 ──> T-087
T-067 + T-080 ──> T-081
T-049 + T-063 ──> T-082 ──> T-086
T-084 ──> T-085
T-081 + T-082 + T-087 ──> T-088（终审，收口 M4 → S8）

[贯穿]
T-030 ──> T-042 / T-050 / T-056        T-030 ──> T-031
```

**关键路径（Critical Path）**：

```
T-001 ✔ → T-089 ✔ → T-002 → T-003 → T-004 → T-007 → T-015 → T-016   [M1：定版/再验证→套保计划主链]
      → T-026/T-027 → T-030                                  [M2 门禁：探路汇签]
      → T-031 → T-034 → T-035 → T-036 → T-039 → T-057 → T-058  [M2：风控/指令/事件/协议/联调]
      → T-094 → T-063 → T-066 → T-073                        [M3：余表→风控参数→红线复算→阻断验证]
      → T-076 → T-077 → T-080 → T-085 → T-086 → T-087 → T-088  [M4：会计→实盘→终验]
```

> **关键路径特征**：M2 段的路径**穿 C++ 组件**（`risk_engine → order_engine → 事件流 → 协议测试`），因事前风控 8 规则 + 三层限额 + 保序补数是全系统最高复杂度与最长串行链；Java 侧 `TradingBridge`（T-042）可与之并行，但 `T-046` 起的主流程必须等 C++ 事件流（T-039）就绪。M4 段含**实盘观察期 1–2 个月日历等待**（T-086），不占工时但不可压缩。

---

## 4. 里程碑映射

> 依据 `PROJECT.md` §9 的模块与里程碑映射。**两道门禁必须体现在依赖中**（§0）。

| 里程碑 | 模块范围 | 组件归属 | **前置门禁** | 包含任务 | 交付物 | 验收方式 |
|--------|---------|---------|-------------|---------|--------|---------|
| **M1** | ①品种 `PRODUCT`(8 表) + ②现货 `SPOT`(10 表) + ③套保计划 `PLAN`(8 表) + ⑩权限 `AUTH`(13 表) + ⑪配置 `CONFIG`(7 表) + **子账户体系** | **Java**（`#java` 实时链 + `#java2` 台账链，`R-12` 预案 A）+ 前端 | **M1 门禁 = 数据模型三方会签**（✅ 已达成）+ **最终锚定 v1.3.2 `99b647ec…`（T-089 ✅）→ T-002 两席无条件确认闭环 ✅**；`R-12` 已裁决（预案 A），**首发工单已派发** | T-001 ~ T-025 + **T-089** + **`T-109`**（「已声明未注册」端点补齐，CONFIG 9 / SPOT 5 / PLAN 1；`#java2`，M1 窗口） + **`T-110`**（SPOT 写面（`T-013` 范围）子账户可见性强制，`#java2`，M1 窗口，**依赖 `T-109`**；来源 = `te-verify-t109c` 探索性新发现 (A)）+ **`T-112`**（写路径子账户可见性强制的全域收口〔PRODUCT 写面 + PLAN 写面〕，`#java2`，M1 窗口，**依赖 `T-110`**；来源 = `te-verify-t110` 域外同类复核）+ **`T-113`**（修正 SPOT 现货单据创建 `subAccountId` 缺省时的错误呈现〔NPE→500 ⇒ 应「子账户必填」〕，`#java2`，M1 窗口，归属 `T-013`；来源 = `te-verify-t110` 域外同类复核）+ **`T-114`**（补齐 `POST /spot/exposure/recalc`（`recalcForDisplay`）的子账户可见性强制，`#java2`，M1 窗口，**依赖 `T-110`**；来源 = `T-112` 实施者第 3 步全仓写路径扫描）+ **`T-115`**（修正 `CROSS_READONLY` **写面**放行〔写路径拒绝跨账户只读身份；读面语义不变〕，`#java`，M1 窗口，**依赖 `T-102`/`T-110`/`T-112`**；来源 = `te-verify-t112` 独立复核缺陷①）（**33**；其中 `T-115` 已 `REJECTED`〔重复立项、成果归 `T-098`、编号留痕、不计人时〕；+ **`T-119`**（1，补齐 `putQuota` 层③账户级写面授权门禁，`#java`，M1 窗口，**依赖 `CH-012` + 播种前置**；来源 = `tl-quota-authz` 层③实测）） | 品种/现货/敞口/计划/权限/配置/子账户功能 + DM8 DAO 层 + 前端页面 | 创建 2 子账户 → 配置品种与敞口 → 产出计划；验证子账户隔离；DM8 正确落库（T-024） |
| **M2** | ④交易 `TRADE`(9 表) + ⑤结算 `SETTLE`(16 表) + ⑨资金 `FUND`(9 表) | **C++ + Java**（双组件联调） | **M2 门禁 = TCP 协议冻结**（✅ HGCP v1.0.1 FROZEN；消息集 40 / 错误码 62 不变，`CH-007`）+ **3 项探路（T-026 / T-027 / T-028）+ 汇签（T-030）**（`T-029` `REJECTED`） | T-026 ~ T-060（35，其中 `T-029` 已取消、计 0 人时）+ **T-090 ~ T-093**（4，架构 §3.0.2 判据 E1/E3/E4/E6 落点，M2 首周批）+ **T-095**（1，`E2` 判据按 `E5` 同法收紧，M2 窗口）+ **`T-096` ~ `T-099`**（4，架构 §4.1.1 两项约定 + §8.2 强制点／机制订正落点，`#java`，M2 窗口）+ **`T-100`**（1，架构 §8.2①③④ **主落点**：`GET /plan` 读取面隔离装配，`#java`，M2 窗口，**依赖 `T-016`**）+ **`T-101`**（1，架构 §8.2③「其余读取面」通则落点：`GET /plan/{id}/trade-plans` 读取面隔离装配，`#java`，M2 窗口，**依赖 `T-100`**）+ **`T-102`**（1，写面**子账户可见性 / 交易权限强制补齐**：`assertSubAccountVisible` 与 `assertCanTrade` 未接线 + `withdraw` 发起人校验缺失 + **第 3 轮再扩 ④** `decomposeTradePlans` 写路径读取未 scope，`#java`，M2 窗口，**依赖 `T-100`**）+ **`T-103`**（1，`GET /plan/{id}/trade-plans` **补分页/筛选/排序**〔按 `FR-PLAN-15` 输入列〕，`#java`，M2 窗口，**依赖 `T-101`**）+ **`T-104`**（1，`FR-PLAN-15` **处理规则**两项〔**剩余可下单量** + **已达上限标记**〕（含前端展示，另派），`#java`，M2 窗口，**依赖 `T-103`**）+ **`T-105`**（1，PRODUCT **列表类**读取面口径收口，`#java2`，M2 窗口，**依赖 `T-100`**）+ **`T-106`**（1，PRODUCT **单对象**读取面隔离，`#java2`，M2 窗口，**依赖 `T-100`**）+ **`T-107`**（1，SPOT 读取面口径收口，`#java2`，M2 窗口，**依赖 `T-100`**）+ **`T-108`**（1，补齐 **AUTH 3 个**未实现 DAO 的实现类〔第 19 轮收窄：原「CONFIG+AUTH 7 个」，CONFIG 侧 4 个由 `T-109` 交付〕，`#java2`，M2 窗口，**依赖 `T-004`**）+ **`T-111`**（1，**服务端敞口推导引擎** + 回填 `FR-SPOT-16` 验收 1〔变更后重算敞口〕，`#java2`，M2 窗口，**依赖 `T-109`**；来源 = `TE-T109-005`）+ **`T-116`**（1，PRODUCT 读取面「需求面 > 实现面」缺口：`FR-PRODUCT-5` 输出列 + `FR-PRODUCT-8` 导出筛选一致，`#java2`，M2 窗口，**依赖 `T-105`**；来源 = `te-verify-t105` 范围外观察）+ **`T-117`**（1，PRODUCT 白名单可见性 over-inclusive 修正〔可见子查询补 `status='ENABLED'`〕，`#java2`，M2 窗口，**依赖 `T-105`**；来源 = `te-verify-t105` 范围外观察）+ **`T-118`**（1，补齐全局 / 账户级对象写面功能权限强制点，`#java2`，M2 窗口，**依赖 `T-049`**；来源 = `tl-quota-authz` 实测）+ **`T-120`**（1，**`E4` 判据接线落地**〔默认根改 `cpp/src/{order_engine,risk_engine,risk_state}` + `arch-check.sh` / `README` 同步〕，`tech-lead`，M2 窗口，**依赖 `T-031`**；来源 = `tl-e4-scope` E4 扫描根裁定〔`ARCHITECTURE.md` `v1.5.12`〕+ `te-verify-t031` 第 2 点）= **58** | C++ 交易组件（CTP/事前风控/指令流水/重放/HGCP）+ Java 桥接与业务服务 + 前端 + **`tools/arch-check/` 判据脚本** | CTP 模拟盘期货开平 + 期权策略，每单可追溯子账户与计划；交易路径零 DB 访问；子账户加总一致；划拨后加总不变（T-058） |
| **M3** | ⑥风险控制 `RISK`(11 表) + ⑦审计 `AUDIT`(8 表) | **C++（事前，M2 已交付）+ Java（事中/事后）** | 依赖 M2 交付（`T-030` 后的事前风控引擎 T-035） | T-061 ~ T-075 + T-094（16） | 事中/事后风控与风险报表、规则校验与红线、全链路追溯、违规筛查、报表导出 | 逐类构造超限场景验证阻断；验证事中风控告警；崩溃恢复不产生超额下单；导出季度报表与追溯报告（T-073/T-074） |
| **M4** | ⑧套保会计 `ACCT`(12 表) + 实盘验证 + 硬化 | **Java + 系统级** | 依赖 M3 交付 | T-076 ~ T-088（13） | CAS 24/IFRS 9 文档、有效性评估与再平衡、凭证草稿、双侧盈亏、证据链导出、安全加固、实盘运行 | 生成完整会计文档包；实盘运行 1–2 个月无违规告警；安全审计通过；换库可行性与断网终验（T-085~T-087） |

> **表数核对**（`DATA_MODEL.md` §0.6，合计 **111**）：M1 = 8+10+8+13+7 = **46 张**；M2 = 9+16+9 = **34 张**；M3 = 11+8 = **19 张**；M4 = **12 张**；46+34+19+12 = **111** ✅。
> **门禁依赖的显式性**：T-003 起（M1 编码）依赖 T-002；T-031 起（M2 开发）依赖 T-030（其中已含 M2 协议冻结事实）；M2 协议冻结本身已完成，`T-032`/`T-042` 直接引用 `proto/hgcp.proto`。

---

## 5. 资源负荷

| 角色 | 负责任务数 | 合计估时 | 负荷评估 |
|------|-----------|---------|---------|
| `backend-engineer#cpp` | 13 | **268 人时**（约 33.5 人天） | 高。集中于 M2；`T-032`/`T-035`/`T-036` 为最长链，**单点风险**，须优先投入。**`CH-006` 两笔已扣**：`T-029` 取消（-8 人时）、`T-040` 改派 `#java`（-16 人时） |
| `backend-engineer#java`（主 · **实时与交易链**） | 26 | **327 人时**（约 40.9 人天） | 高。骨架/DAO 基座/桥接/交易执行/事中风控/RISK 参数与复算/安全加固 + **行情 WebSocket 服务端（生产级，`CH-006` 定界移入 `T-040`，+16 人时）**；**M1 关键路径 `T-003`→`T-004` 由本席先行交付并冻结接口**（`R-12` 预案 A 已生效）。**2026-09-20 `T-061` 范围收窄**：首增量 `t_param_dispatch` 单表 20 → **2 人时**（-18），余 10 张表转 `T-094`（待指派）。**2026-09-21 新增 `T-096`~`T-099`**（2 / 4 / 3 / 2 人时 = **+11 人时**，架构 §4.1.1 两项约定 + §8.2 强制点／机制订正落点，M2 窗口）；**2026-09-21 新增 `T-100`**（4 人时，架构 §8.2①③④ **主落点**：`GET /plan` 读取面隔离装配，依赖 `T-016`，M2 窗口）；**2026-09-21 新增 `T-101`**（3 人时，架构 §8.2③「其余读取面」通则落点：`GET /plan/{id}/trade-plans` 读取面隔离装配，**相邻残余缺口**，依赖 `T-100`，M2 窗口）；**2026-09-21 新增 `T-102`**（**5 人时**，写面**子账户可见性 / 交易权限强制补齐**：`AuthService.assertSubAccountVisible` 与 `assertCanTrade` 未接线 + `PlanService.withdraw` 发起人校验缺失 + **第 3 轮再扩 ④** `PlanService.decomposeTradePlans`（`:786`）写路径读取未 scope；`T-100` 专项复核 `EXP-T100-1` 同族发现 + `T-101` 交付披露；依赖 `T-100`，M2 窗口）；**2026-09-21 新增 `T-103`**（4 人时，`GET /plan/{id}/trade-plans` **补分页/筛选/排序**〔按 `FR-PLAN-15` 输入列，`PLAN.md:321`〕；来源 = `T-101` 独立复核 `EXP-T101-1`；依赖 `T-101`，M2 窗口）；**2026-09-21 新增 `T-104`**（2 人时，`FR-PLAN-15` **处理规则**两项〔**剩余可下单量** + **已达上限标记**〕；被 `T-101`/`T-103` **两次**判为「非本任务范围」⇒ 单独立号以免持续掉队；依赖 `T-103`，M2 窗口）；**2026-09-21 `T-096` 估时上修**（**2 → 3 人时**，追加「稳定排序」能力，来源 = `T-103` 交付披露 (b)：分页 `ORDER BY` 仅单列、无唯一 tie-break）；**2026-09-21 第 24 轮新增 `T-115`**（**+6 人时**，修正 `CROSS_READONLY` **写面**放行〔写路径拒绝跨账户只读身份；读面语义不变〕；来源 = `te-verify-t112` 独立复核缺陷①（高·真绕过）；依赖 `T-102`/`T-110`/`T-112`，M1 窗口）；**2026-09-21 第 25 轮 `T-115` 判 `REJECTED`**（重复立项、编号留痕、**不计人时**）⇒ **−6 人时**（**310 → 304**）；**2026-09-21（第 27 轮）编排者指派承接 `T-094`**（RISK 余 10 张表 DDL 与 DAO，M3 窗口，**+18 人时**）⇒ **304 → 322 人时（24 → 25 个）**；**2026-09-21（第 29 轮）新增 `T-119`**（补齐 `putQuota` 层③〔`ACCOUNT_PRODUCT`/账户哨兵 `0`〕账户级写面授权门禁，**+5 人时**）⇒ **322 → 327 人时（25 → 26 个）** |
| `backend-engineer#java2`（新 · **台账与合规链**） | 42 | **617 人时**（约 77.1 人天） | **最高**。品种/现货/敞口/计划 → 结算/资金 → 审计 → 会计全链；**待 `#java` 冻结 `T-003`/`T-004` 接口后并行开工**，其解阻点仍由 `#java` 承担（见 §5.1）。**2026-09-21 第 11 轮新增 `T-105`/`T-106`/`T-107`**（**+5 +2 +4 = +11 人时**，PRODUCT/SPOT **读取面口径收口**，`tl-triage` 普查 + 编排者裁定，M2 窗口）；另**承接 `T-016` 的 `CR-002` 修复**（严重级，写作属主 = `#java2`）；**2026-09-21 第 15 轮新增 `T-108`**（**+8 人时**，补齐 CONFIG+AUTH **7 个**未实现 DAO 的实现类，来源 = `T-016`/`CR-002` 实施受阻连带发现，M2 窗口；**第 19 轮范围收窄为 AUTH 3 个**，CONFIG 侧 4 个由 `T-109` 交付）；**2026-09-21 第 16 轮新增 `T-109`**（**+12 人时**，补齐 CONFIG/SPOT/PLAN「已声明未注册」端点 **15** 条〔CONFIG 9 / SPOT 5 / PLAN 1〕，**M1 窗口**，依赖 `T-005`/`T-006`/`T-007`，来源 = 对齐普查 `audit-cov`）；**2026-09-21 第 21 轮新增 `T-110`/`T-111`**（各 **8 人时** = **+16 人时**；`T-110` = SPOT 写面（`T-013` 范围）**子账户可见性强制**〔M1 窗口〕、`T-111` = **服务端敞口推导引擎** + 回填 `FR-SPOT-16` 验收 1〔M2 窗口〕，均依赖 `T-109`）；**2026-09-21 第 22 轮新增 `T-112`/`T-113`**（`T-112` = 写路径子账户可见性强制的全域收口〔PRODUCT 写面 + PLAN 写面〕，**+8 人时**；`T-113` = SPOT 现货单据 `subAccountId` 缺省错误呈现修正，**+2 人时**；均 `#java2`、M1 窗口）；**2026-09-21 第 23 轮新增 `T-114`**（**+2 人时**，补齐 `POST /spot/exposure/recalc`（`recalcForDisplay`）子账户可见性强制，写路径越权面〔读 + `t_spot_change_log` 留痕写〕，**M1 窗口**，依赖 `T-110`；来源 = `T-112` 实施者第 3 步全仓写路径扫描）；**2026-09-21 第 28 轮新增 `T-116`/`T-117`/`T-118`**（**+3 +2 +5 = +10 人时**，均 M2 窗口：`T-116`/`T-117` = PRODUCT 读取面「需求面 > 实现面」缺口与白名单 over-inclusive 修正〔来源 = `te-verify-t105` 范围外观察〕、`T-118` = 全局 / 账户级对象写面功能权限强制点〔来源 = `tl-quota-authz` 实测〕） |
| `frontend-engineer` | 11 | **176 人时**（约 22 人天） | 中。依赖后端 REST/WS 就绪（T-012/T-016/T-046/T-047/T-049/T-080）后方可开工，前期可先做 T-017 基座 |
| `tech-lead` | 8 | **35 人时** | 低（任务数少）；但 T-030（M2 铺开门禁）**不可延**。另承担架构 §3.0.2 判据脚本 `E1`/`E3`/`E4`/`E6` 四条落点（`T-090`~`T-093`，各 4 人时）+ **`E1`~`E6` 六脚本形态/退出码语义一致性收口（`T-095`，第 10 轮 4 → 8 人时）** + **`T-120`（`E4` 判据接线落地：默认根改 `cpp/src/{order_engine,risk_engine,risk_state}` + `arch-check.sh` / `README` 同步，**3 人时**，第 31 轮新增）**——**M2 批，可与 `T-030` 并行** |
| `编排者`（team-lead） | 1（T-089） | **4 人时** | 低；T-089 已完成（门禁制品定版冻结，属治理动作，非 7 类执行角色） |
| `test-manager` | 5 | **60 人时** | 中。`T-022`/`T-056`/`T-072`/`T-084` 分别卡在 M1/M2/M3/M4 前段，须**提前于对应开发**准备 |
| `test-engineer` | 10 | **240 人时**（约 30 人天） | 高。M2/M3 测试与生产级联调叠加，须与后端并行推进 |
| `code-reviewer` | 4 | **64 人时** | 低-中。每里程碑末段集中审查（T-025/T-060/T-075/T-088），阶段性峰值高 |
| **合计** | **120** | **1791 人时**（约 **223.9 人天**） | 按 `PROJECT.md` §6 工期 5–6 个月（含实盘观察期 1–2 个月日历），**须 C++/Java 双后端并行、测试前置**方可匹配。**合计已扣除 `T-029`（`REJECTED`）的 8 人时**；**已计入 `T-090`~`T-093` 的 16 人时 + `T-095` 的 **8** 人时 + `T-096`~`T-099` 的 11 人时 + `T-100` 的 4 人时 + `T-101` 的 3 人时 + `T-102` 的 5 人时 + `T-103` 的 4 人时 + `T-104` 的 2 人时 + `T-096` 上修的 1 人时**（`T-102` 由 4 → 5，第 3 轮再扩 ④；`T-103` 为第 4 轮新增；`T-104` 与 `T-096` 上修为第 8 轮新增；**`T-095` 第 10 轮由 4 → 8 上修**）；**`T-061` 20 → 2、`T-094` +18（RISK DDL 总量守恒于 20 人时）**；**第 11 轮新增 `T-105`/`T-106`/`T-107`（`#java2`，M2 窗口，+5 +2 +4 = +11 人时）**；**第 15 轮新增 `T-108`（`#java2`，M2 窗口，+8 人时）**；**第 16 轮新增 `T-109`（`#java2`，M1 窗口，+12 人时）**；**第 21 轮新增 `T-110`/`T-111`（`#java2`，M1/M2 窗口，+8 +8 = +16 人时）**；**第 22 轮新增 `T-112`/`T-113`（`#java2`，M1 窗口，+8 +2 = +10 人时；`T-110` 由 `TODO` → `DONE`，人时不变）**；**第 23 轮新增 `T-114`（`#java2`，M1 窗口，+2 人时；`T-112` 由 `TODO` → `IN_PROGRESS`，人时不变）**；**第 24 轮新增 `T-115`（`#java`，M1 窗口，+6 人时；`T-112` 保持 `IN_PROGRESS`、人时不变）**；**第 25 轮 `T-115` 判 `REJECTED`（**重复立项** —— 与既有 `T-098` 范围逐条重合；**成果归 `T-098`**；编号留痕、不计人时；动因 = 编排者立号时未扫整张既有任务表）⇒ **−6 人时**（**1779 → 1773**）；`T-098` / `T-112` 转「已完成，待独立复核」（纯状态口径、人时不变）**；**第 28 轮新增 `T-116`/`T-117`/`T-118`（`#java2`，M2 窗口，+3 +2 +5 = **+10 人时**；来源 = `te-verify-t105` 范围外观察 + `tl-quota-authz` 实测）⇒ **1773 → 1783**；**第 29 轮新增 `T-119`（`#java`，M1 窗口，**+5 人时**）⇒ **1783 → 1788**；**第 31 轮新增 `T-120`（`tech-lead`，M2 窗口，**+3 人时**）⇒ **1788 → 1791** |

**按里程碑估时**：

| 里程碑 | 任务数 | 合计估时 | 占比 |
|--------|-------|---------|------|
| M1 | 33 | 445 人时 | 24.85% |
| M2 | 58 | 806 人时 | 45.00% |
| M3 | 16 | 288 人时 | 16.08% |
| M4 | 13 | 252 人时 | 14.07% |
| **合计** | **120** | **1791 人时** | 100% |

> **本表口径**：任务数按**编号计**（`T-029` `REJECTED` 仍占 1 个编号、计入 M2）；**人时按实际执行计**（`T-029` 计 0），故 M2 = 720 - 8 + **16**（`T-090`~`T-093`）+ **8**（`T-095`，第 10 轮 4 → 8）+ **11**（`T-096`~`T-099`）+ **4**（`T-100`）+ **3**（`T-101`）+ **5**（`T-102`）+ **4**（`T-103`）+ **2**（`T-104`）+ **1**（`T-096` 上修）+ **11**（`T-105`~`T-107`，第 11 轮）+ **8**（`T-108`，第 15 轮）+ **8**（`T-111`，第 21 轮）+ **10**（`T-116`~`T-118`，第 28 轮）+ **3**（`T-120`，第 31 轮）= **806 人时**；M1 = **445 人时**（= 408 + `T-109` **12**〔第 16 轮〕+ `T-110` **8**〔第 21 轮〕+ `T-112` **8** + `T-113` **2**〔第 22 轮〕+ `T-114` **2**〔第 23 轮〕+ `T-115` **6**〔第 24 轮〕− `T-115` **6**〔第 25 轮：`REJECTED`、不计人时〕+ `T-119` **5**〔第 29 轮〕）；M3 = **288 人时**（`T-061` 20 → **2** + `T-094` **18**，RISK DDL 总量守恒），全项目 **1791 人时**（详见 §1 合计行）。**占比分母 = 1791 人时**（各段独立四舍五入至 2 位小数，尾数合计 100.00%；M2 = **806**）。

> **负荷结论**：Java 侧合计 **944 人时**（`#java` **327** ＝ `R-12` 二分 276 + `CH-006` 定界移入 `T-040` 16 − `T-061` 收窄 18 + `T-096`~`T-099` 11 + `T-100` 4 + `T-101` 3 + `T-102` 5 + `T-103` 4 + `T-104` 2 + `T-096` 上修 1 + `T-115` 6〔第 24 轮〕− `T-115` 6〔第 25 轮：`REJECTED`、不计人时〕+ `T-119` 5〔第 29 轮〕；`#java2` **617**（= 595 + `T-112` 8 + `T-113` 2，第 22 轮 + `T-114` 2，第 23 轮 + `T-116` 3 + `T-117` 2 + `T-118` 5，第 28 轮），`R-12` 预案 A 二分后两个实例）与 `backend-engineer#cpp`（**268 人时**，已扣除 `T-029` 8 + 移出 `T-040` 16）合计 **1212 人时 = 全项目的 67.67%**（分母 1791；**`T-094` 18 人时已于 2026-09-21（第 27 轮）由编排者指派入 `#java`，故已计入 Java 侧**），与 `PROJECT.md` §8「本项目必须有 C++ 与 Java 两种后端能力」一致。**若任一后端能力缺位，M2 双组件联调无法闭环**——应在 S3 门禁处向用户确认资源到位。
>
> **`R-12` 处置结果**：原 `#java` 单点 **42 个 / 824 人时**（全项目 49.5%）经编排者 2026-09-20 裁定**采纳预案 A**，沿数据边界二分后降为 **13/276 与 29/548**，**总量 824 不变**；两个 Java 实例**各自独立 inbox**（`dev-team/inbox/backend-engineer#java/` 与 `.../backend-engineer#java2/`）。详见 §5.1。
>
> **`CH-006` 定界追加（2026-09-20）**：`T-040`（行情 WebSocket 服务端）由 `#cpp` **改派 `#java`**（16 人时），故**本表当前值** = `#java` **14 个 / 292 人时**、`#cpp` **13 个 / 268 人时**；Java 侧总量由 `R-12` 二分时的 824 → **840 人时**（+16）。**其后 `T-061` 范围收窄再降至 822（-18），见下条追加。**
>
> **架构判据落点追加（2026-09-20，项目经理）**：新增 **`T-090`~`T-093`**（`tech-lead`，各 4 人时，M2 首周批）= 架构 `ARCHITECTURE.md` §3.0.2 判据 `E1`/`E3`/`E4`/`E6` 的脚本落点；故**该轮全项目口径 = 93 个编号 / 1676 人时**（89 / 1660 + 4 / 16）；`tech-lead` 由 **2 个 / 8 人时** → **6 个 / 24 人时**。
>
> **`E2` 判据同构化追加（2026-09-20，项目经理）**：新增 **`T-095`**（`tech-lead`，**4 人时**，M2 窗口）= 将 `E2` 判据脚本（`scan_cpp_sql.sh`）按**已修订的 `E5` 同法收紧**（**注释预处理** + ② 按「**调用形态 / 目录**」分域）；缘由 = 现脚本仍为裸关键字（含 `jdbc`/`odbc`）、无注释预处理，与 `E5` **不同构**，C++ 侧测试落地后必然复现同类误报。故**当前全项目口径 = 95 个编号 / 1680 人时**（1676 + 4）；`tech-lead` 由 **6 个 / 24 人时** → **7 个 / 28 人时**；§4 里程碑映射 M2 由 39 → **40 个**、728 → **732 人时**。
>
> **`T-061` 范围收窄追加（2026-09-20，项目经理）**：RISK 模块 DDL/DAO 的**首增量**收敛为单表 `t_param_dispatch`（`T-061`，**20 → 2 人时**，已完成、待独立复核）；**剩余 10 张 RISK 表撤出本轮、改归 M3 窗口**，新增 **`T-094`（18 人时，负责人待编排者指派）**。故**该轮全项目口径 = 94 个编号 / 1676 人时**（人时守恒）；`#java` **14 个 / 274 人时**、`#cpp` **13 个 / 268 人时**、**待指派 `T-094` 1 个 / 18 人时**；M3 由 **15 → 16 个**（人时不变 288）。依据见 `T-061` 详情备注与 `docs/DEPLOY/schema/m3_risk.sql` 头部「增量交付声明」。
>
> **架构约定落点追加（2026-09-21，项目经理）**：新增 **`T-096`~`T-099`**（均 `#java`，M2 窗口，**+2 +4 +3 +2 = +11 人时**）= `ARCHITECTURE.md`（登记时点 `v1.5.8`；**以实时 `md5sum` 为准**）**§4.1.1** 两项约定（`T-096` `PageQuery` 缺省方向重载 / `T-097` `ApiRoutes` 声明 `(方法,路径)` 对 + 判据改等值断言）+ **§8.2** 强制点／机制订正（`T-098` `CROSS_READONLY` 只读强制点 / `T-099` `scopeOf` 不读 `perm_type` 的处置）的**落点**。**缘由**：`v1.5.7`/`v1.5.8` 架构裁定已把四项落地建议写入正文，但**任务编号尚未进本表**；技术负责人明确「编号系其建议，须项目经理登记后方为事实」。**故该轮全项目口径 = 99 个编号 / 1691 人时**（1680 + 11；**已被下条 `T-100` 追加取代，现行 = 100 / 1695**）；`#java` 由 **14 个 / 274 人时** → **18 个 / 285 人时**；§4 里程碑映射 M2 由 40 → **44 个**、732 → **743 人时**。**⚠️ 该缺口已报编排者、并于同日裁定 → 立 `T-100` 补登记（见下条追加）。**

> **`T-100` 编号补登记追加（2026-09-21，项目经理）**：编排者就上条「§8.2 **主落点**（①③④ `GET /plan` **读取面**隔离装配）**无编号承载**」之发现裁定 **立 `T-100`**（`#java`，**4 人时**，M2 窗口，**依赖 `T-016`**）——该工作此前**已由编排者派出实现**（`backend-engineer` 进行中）但**编号缺位 ⇒ 追溯链断裂**，故本任务为**编号补登记**（起因为项目经理上报）。**故当前全项目口径 = 100 个编号 / 1695 人时**（1691 + 4）；`#java` 由 **18 个 / 285 人时** → **19 个 / 289 人时**；§4 里程碑映射 M2 由 44 → **45 个**、743 → **747 人时**；Java 侧合计 833 → **837**（全项目占比 65.1% → **65.2%**）。**同批三项裁定：** ① `T-099` 未在架构正文出现 → **以 `TASKS.md` 登记为准，不改 `ARCHITECTURE.md`**；② `T-096` 落地后 **保留** `PlanHandler.java:50` 的 handler 侧兜底 `DEFAULT_SORT`（**属有意冗余**，是对公共件缺省方向的回归保护——已在 `T-096` 备注写明）；③ `BUG-001` 复检**不进任务表**（走 `BUGS/` 单状态 + S5 测试执行；已按此理解）。**串行顺序（编排者裁定）**：`T-100 → T-099 → T-098 → T-096 → T-097`（同一时刻只允许一个 `#java` 实例写；`T-097` 为判据升级、最后收束）。**`T-016` 回改**：待 `T-100` 落地后一次性回改 `T-016` 验收（补「`R-PLAN-3` 六项服务端强制」= `CR-002` 的 §6.4 裁定），**本轮不动**。

> **`T-101` 编号登记追加（2026-09-21，项目经理）**：编排者就 `T-100` 实现期发现的**相邻残余缺口**裁定 **立 `T-101`**（`#java`，**3 人时**，M2 窗口，**依赖 `T-100`**）——`GET /plan/{id}/trade-plans`（`TradePlanHandler` → `PlanService.listTradePlans`）**无调用者身份入参、无 scope 过滤** ⇒ **可跨子账户读取交易计划**（**同性质缺陷换端点仍复现**）。**单独立号理由（裁定）**：本项动 `TradePlanDao` + `listTradePlans` + `TradePlanHandler`，**写域与 `T-100` 不同**；且 `T-100` 已可独立验收（并入会让已交付项无法闭环）。**故当前全项目口径 = 101 个编号 / 1698 人时**（1695 + 3）；`#java` 由 **19 个 / 289 人时** → **20 个 / 292 人时**；§4 里程碑映射 M2 由 45 → **46 个**、747 → **750 人时**；Java 侧合计 837 → **840**（Java + `#cpp` = **1108 人时** = 全项目 **65.25%**，分母 1698）。**串行顺序更新（编排者裁定）**：`T-100 → T-101 → T-099 → T-098 → T-096 → T-097`（`T-101` 插于 `T-100` 之后、`T-099` 之前）。**依据修正（项目经理登记前实测）**：派单给的依据为 `FR-PLAN-4` 验收 4（`PLAN.md:164`，系 `GET /plan` 列表判据）；本端点**自身**隔离判据实为 **`FR-PLAN-15` 验收 4**（`PLAN.md:331`）⇒ 登记以端点自身 FR 为主锚，`FR-PLAN-4` 验收 4 作同构参照。**同步更新**：§1 总览（新增行 + 合计）、§2 详情（新增块 + `T-100` 串行顺序句）、§3 依赖图、§4 里程碑映射 M2、§5 资源负荷与 `BOARD.md` 同步。

### 5.1 `R-12` 人员分布采纳方案（**预案 A 已生效 —— 编排者 2026-09-20 裁决**）

> 触发：`backend-engineer#java` 单点 824 人时（全项目 49.5%，横跨 M1–M4）＝最现实的顺延源。
> **裁决（编排者 2026-09-20）：采纳预案 A —— 加派 `backend-engineer#java2`。** 理由：Java 侧 824 人时 = 总工时 49.5%、横跨 M1–M4 的**单点**且无缓冲，任一次延期直接顺延 M3 阻断验证与 M4 实盘；编排规则 §6.5 允许多实例、§6.4 允许按规模调整实例数；二分**按数据边界而非页面**、**M1 关键路径不跨实例**、**总量不变（276+548=824，此处指 `R-12` 二分口径）**；裁决**可逆、切换成本极低**（仅改本文 §5 + §5.1 + `BOARD.md` `R-12`）。
> **生效标记：✅ 预案 A 已生效（编排者 2026-09-20 裁决）**；**预案 B（不加派）不予采纳**，仅作留档。

**预案 A（已生效）—— 加派 `backend-engineer#java2`（按「链」二分，编排规则 §6.5 允许多实例）**

- **切分原则**：沿**数据边界**二分（**非按页面**），且**接口先冻结再并行**——`#java` 先交付 `T-003`/`T-004`（工程骨架 + DAO 基座 + REST 约定）并冻结 REST 契约、DAO 接口与 **WebSocket 对外约定**（`CH-006`）；`#java2` 在其上并行。
- 🔒 **跨实例共享接口（`TradingBridge` / DAO 基座 / 共享 DTO）须先冻结再并行**——对齐 `ARCHITECTURE.md` **§1.2 约束「契约冻结纪律」**（数据模型与 TCP 协议先冻结再并行；表结构变更先改 `DATA_MODEL.md` 再改代码）与编排规则 **§6.3.4**；冻结物须**显式公布**（接口清单 + 版本）后方可并行，未冻结前不得双实例各自实现同一契约。
- **`#java`（主）＝实时与交易链**：`T-003`(16) `T-004`(24) `T-028`(16) `T-040`(16) `T-042`(32) `T-043`(16) `T-046`(24) `T-050`(20) `T-061`(2) `T-063`(20) `T-064`(24) `T-065`(20) `T-066`(24) `T-082`(20) → **14 个 / 274 人时**（`T-061` 范围收窄 20 → 2；余 10 张 RISK 表转 `T-094`，负责人待指派）**（历史版本，非现行——本清单为 `T-061` 收窄轮口径；现行 `#java` = 26 个 / 327 人时，含 `T-096`~`T-104`（`T-115` 已 `REJECTED`、不计人时），见本节末「架构约定落点回写」）**
- **`#java2`（新）＝台账与合规链**：`T-005`~`T-016`(200) + `T-044`/`T-045`/`T-047`/`T-048`/`T-049`/`T-051`/`T-052`(144) + `T-062`/`T-067`/`T-068`/`T-069`(80) + `T-076`~`T-081`(124) → **29 个 / 548 人时**
- **效果**：单点 824 → **292 / 548**（`R-12` 二分时为 276 / 548；其后 `CH-006` 定界将 `T-040` 移入 `#java`，+16 → **292 / 548**，Java 侧合计 **840**），M1 关键路径（`T-003`→`T-004`→`T-007`→`T-015`→`T-016`）**不跨实例**（`T-005`~`T-016` 均在 `#java2`，解阻仍由 `#java` 的 `T-003`/`T-004` 承担）；**`R-12` 二分本身不改 Java 侧总量（824）**，日历可压缩。
- **代价/约束**：需 1 名额资源；`#java2` 的 inbox、工单、审查均需独立（`dev-team/inbox/backend-engineer#java2/`）；**跨实例改同一表簇仍须单写者纪律**。
- **首批派发**：`#java2` 的 **29 条全部（直接或传递）依赖 `T-003`/`T-004` 冻结接口**（`T-005`~`T-016` 依赖 `T-004`；`T-044`/`T-045`/`T-062`/`T-076` 依赖 `T-004`；其余为其下游）——故**无法首批发实做工单**，改为投递 1 条**开工说明性工单**（`T-005-task.md`），内含 29 条清单与解阻条件；`#java` 冻结接口后即按清单顺序开工。

**预案 B（不予采纳，留档）—— 不加派（维持单点）**

- `backend-engineer#java` 仍 42 个 / 824 人时；**无缓冲**，任一次返工或病假即顺延关键路径。
- 缓解：`#java` 优先交付 `T-003`/`T-004` 解阻全员；前端与测试在接口冻结后并行；M1 期间即启动 `T-005`~`T-016` 的连续推进。
- 不采纳原因：无缓冲、单点过载不可接受（编排者裁决，见上）。

> **切换已完成（项目经理）**：已改写本文 §5 资源负荷表 + §5.1 生效标记 + `BOARD.md` `R-12`，并投递首发工单；`R-12` 二分**不改编号、不改 Java 侧总量（824 人时）**。**该轮全项目口径 = 89 个编号 / 1660 人时**（任务编号 89 不变；人时已扣除 `CH-006` 取消的 `T-029` 8 人时）。
>
> **`CH-006` 定界回写（项目经理，2026-09-20）**：`T-040`（行情 WebSocket 服务端）**改派 `#java`**（`CH-006` 组件定界，架构 `ARCHITECTURE.md` v1.4 §4.1.2/§7.1.2 为准）——`#java` **14 个 / 292 人时**、`#cpp` **13 个 / 268 人时**；Java 侧合计 **840 人时**（`R-12` 二分 824 + `T-040` 16）。**该轮编号 89、总量 1660 人时均不变**；两文件（`docs/TASKS.md` / `docs/BOARD.md`）为项目经理唯一写入范围（`SIGN-OFF.md` §8.3）。
>
> **架构判据落点回写（项目经理，2026-09-20）**：新增 `T-090`~`T-093`（`tech-lead`，M2 首周批，各 4 人时）——补齐架构 `ARCHITECTURE.md` §3.0.2 的 `E1`/`E3`/`E4`/`E6` 四条判据**脚本落点**（此前仅 `E5` 已落地 `tools/arch-check/scan_java_sql.sh`、`E2` 由 `tech-lead-19` 落地中）。**该轮全项目口径 = 93 个编号 / 1676 人时**；`tech-lead` = **6 个 / 24 人时**；§4 里程碑映射 M2 由 35 → **39 个**、712 → **728 人时**。四条任务的验收判据均为**可复跑命令 + 退出码语义（`0`/`1`/`2`）**，并按架构 §3.0.2 脚注（`E5` 教训）要求**命中模式为语句形态或明确形态 + 显式豁免**，**不得裸关键字误伤同名标识符**。
>
> **`T-061` 范围收窄回写（项目经理，2026-09-20）**：`T-061` 范围由「RISK 11 张表」收窄为**首增量 `t_param_dispatch` 单表**（**20 → 2 人时**；已完成、待独立复核）；**其余 10 张 RISK 表撤出本轮、改归 M3 窗口**，新增 **`T-094`（18 人时，负责人待编排者指派）**。**该轮全项目口径 = 94 个编号 / 1676 人时**（人时守恒）；`#java` = **14 个 / 274 人时**、`#cpp` = **13 个 / 268 人时**、待指派 = **1 个 / 18 人时**；M3 = **16 个 / 288 人时**。同步更新：§1 总览（`T-061` 行 + 新增 `T-094` 行 + 合计）、§2 详情（`T-061` 改写 + `T-094` 新增 + `T-011`/`T-063`/`T-064`/`T-065` 依赖补 `T-061`/`T-094`）、§3 依赖图与关键路径、§4 里程碑映射 M3、§5 资源负荷与 `BOARD.md` 同步。
>
> **架构约定落点回写（项目经理，2026-09-21）**：新增 `T-096`~`T-100`（均 `#java`，M2 窗口；`T-096`~`T-099` 各 2 / 4 / 3 / 2 人时 + `T-100` 4 人时 = **+15 人时**）——登记 `ARCHITECTURE.md`（登记时点 `v1.5.8`；**以实时 `md5sum` 为准**）§4.1.1 两项约定 + §8.2 强制点／机制订正的落点，以及 **§8.2①③④ 主落点**（`T-100`，依赖 `T-016`）的**编号补登记**。**该轮全项目口径 = 100 个编号 / 1695 人时**；`#java` = **19 个 / 289 人时**、`#cpp` = **13 个 / 268 人时**、待指派 = **1 个 / 18 人时**；**M2 = 45 个 / 747 人时**。同步更新：§1 总览（5 新增行 + 合计）、§2 详情（5 新增块）、§3 依赖图、§4 里程碑映射 M2、§5 资源负荷与 `BOARD.md` 同步。
>
> **`T-101` 登记回写（项目经理，2026-09-21）**：新增 **`T-101`**（`#java`，M2 窗口，**3 人时**，**依赖 `T-100`**）= 架构 §8.2③「其余读取面同须接同一过滤」通则的落点（`GET /plan/{id}/trade-plans` 读取面隔离装配；`T-100` 的**相邻残余缺口**）。**该轮全项目口径 = 101 个编号 / 1698 人时**；`#java` = **20 个 / 292 人时**、`#cpp` = **13 个 / 268 人时**、待指派 = **1 个 / 18 人时**；**M2 = 46 个 / 750 人时**；Java 侧合计 **840 人时**（Java + `#cpp` = 1108 = 全项目 **65.25%**，分母 1698）。**串行顺序**：`T-100 → T-101 → T-099 → T-098 → T-096 → T-097`。同步更新：§1 总览（新增行 + 合计）、§2 详情（新增块 + `T-100` 串行顺序句）、§3 依赖图、§4 里程碑映射 M2、§5 资源负荷与 `BOARD.md` 同步。

> **`T-102` 登记回写 + `T-100` 置 `DONE`（项目经理，2026-09-21）**：① **`T-100` 由「已完成，待独立复核」→ `DONE`**（判据 = **授权 + 独立验证**）——`test-engineer` 独立复核已回、判定**通过**（`docs/TEST_RESULT.md`「`T-100` 专项独立复核」节 / 复核者 `te-verify-t100`，2026-09-21；**以实时 `md5sum` 为准**）：基线 **`300/0/0/0`**（clean、无排除）、**共享树源码零改动**、**自建 11 例探针**、**两向反退化变异**（变异 A 短路 scope 分支 → 6/8 红；变异 B「空集也当不过滤」危险默认 → 3 红）、**无存在性泄露**、9 条交叉核对全符合、无法判定 0。② 新增 **`T-102`**（`#java`，M2 窗口，**3 人时**，**依赖 `T-100`**）= 写面**子账户可见性强制补齐**（`T-100` 专项复核 `EXP-T100-1` **同族发现**：`AuthService.assertSubAccountVisible` **已实现但 `src/main` 0 处调用**；写动作经 `requirePlan` → `findById` **无子账户过滤**；`PlanService.withdraw` **javadoc 承诺与实现不符**）。**边界（不合并）**：与 `T-098`（`CROSS_READONLY` **只读强制点**）**相关但不同** —— `T-098` 管「这类用户能不能写」，`T-102` 管「写动作有无按子账户可见性强制 + 发起人身份校验」。**该轮全项目口径 = 102 个编号 / 1701 人时**（1698 + 3）；`#java` = **21 个 / 295 人时**、`#cpp` = **13 个 / 268 人时**、待指派 = **1 个 / 18 人时**；**M2 = 47 个 / 753 人时**；Java 侧合计 **843 人时**（Java + `#cpp` = **1111 人时** = 全项目 **65.31%**，分母 1701）。**串行顺序（现行）**：`T-100 → T-101 → T-102 → T-099 → T-098 → T-096 → T-097`（**由** `T-100 → T-101 → T-099 → T-098 → T-096 → T-097` **更新而来**）。**⚠️ 历史条目保留各轮原口径**：本条之前的 §5 追加条目（`T-100`/`T-101` 轮）其编号数、人时与串行顺序均为**该轮次事实**，未回改；现行值以 §1 合计行 + 本节本条 + `TASKS.md` §2 `T-100`/`T-102` 备注为准。同步更新：§1 总览（`T-100` 状态 + 新增 `T-102` 行 + 合计）、§2 详情（`T-100` 状态行 + 新增 `T-102` 块 + `T-100` 串行顺序句）、§3 依赖图、§4 里程碑映射 M2、§5 资源负荷与 `BOARD.md` 同步。

> **`T-102` 范围扩（纳入 `assertCanTrade`）+ 估时上修（3 → 4 人时）（项目经理，2026-09-21 第 2 轮）**：编排者依据 `docs/TEST_RESULT.md`「`T-100` 复核的探索性发现」**补录节**（`te-record-exp1` 独立复跑；`EXP-T100-1` §补录.5 第 3 项）裁定：**`assertCanTrade`（`AuthService.java:417`）与 `assertSubAccountVisible` 同族 —— 二者在 `src/main` 均 0 处调用**（`assertCanTrade` 的 `:53` 仅 `{@link}` javadoc 引用、**非调用**）⇒ **并入 `T-102`**。**故 `T-102` = 三个强制点**（`assertSubAccountVisible` 写面可见性 + `assertCanTrade` 操盘前置交易权限 + `withdraw` 发起人校验），**不再「两大点择一」**；**估时由 3 人时上修至 4 人时**（较 `T-098` 多两项强制点 + 一类拒绝用例；**条件性再上修至 5～6 人时**见 §2 `T-102` 备注）。**该轮全项目口径 = 102 个编号 / 1702 人时**（1701 + 1）；`#java` = **21 个 / 296 人时**、`#cpp` = **13 个 / 268 人时**、待指派 = **1 个 / 18 人时**；**M2 = 47 个 / 754 人时**；Java 侧合计 **844 人时**（Java + `#cpp` = **1112 人时** = 全项目 **65.33%**，分母 1702）。**勘误说明**：本条之前的 §5 `T-102` 条目（第 1 轮：3 人时 / 1701 人时）**为其轮次事实，保留不回改**；现行值以 §1 合计行 + 本节本条 + §2 `T-102` 为准。**同轮另两裁定**：① `AuthService` 的 `touchSession` / `changePassword` / `resetPassword` / `grantRole`（生产 0 调用）**属「端点未实现（进度）」而非「控制未强制（缺陷）」**，其 FR 已被**既有 `T-010`（`FR-AUTH-1~18`，产出含 `AuthService` + REST `/auth/**`）**覆盖 ⇒ **不新建号**（详见本轮回报）；② `BOARD.md` §1「M1 施工进展」note 补注 `T-100` 现行状态（`DONE`）。同步更新：§1 总览（`T-102` 行 + 合计）、§2 详情（`T-102` 块）、§3 依赖图、§4 里程碑映射 M2、§5 资源负荷、`BOARD.md`。
| 2026-09-21 | **`T-101` → 「已完成，待独立复核」+ `T-102` 范围再扩（纳入 ④ `decomposeTradePlans`）+ 估时 4 → 5 人时（项目经理 · 第 3 轮）**：① **`T-101`** 由 `TODO` → **「已完成，待独立复核」**（`backend-engineer` 已交付 `GET /plan/{id}/trade-plans` 读取面隔离：`PlanService.listTradePlans(long, long)` 服务端强制点 + `TradePlanDao`/`DmTradePlanDao.findByHedgePlanScoped`（消费 `SubAccountScope.appendTo(...)`、**保持 `List` 非分页**）+ `TradePlanHandler` 传 `ctx.currentUserId()`；隔离副本 clean 全量 **310/0/0/0**（`2026-09-21T06:27:47`，无排除）、6 例服务层 + 4 例真实 DAO SQL、反退化 10 例中 6 红；`ApiRoutes.java` md5 未变、`grep -rc 'TEMP-'` = 0、`E5` 114 文件 / 0 命中 / 退出码 0；**独立复核进行中** ⇒ 按「`DONE` = 授权 + 独立验证；仅自证 = 已完成，待独立复核」置该状态，**不替其下「通过」结论**）。② **`T-102` 范围再扩 ④**（`decomposeTradePlans` 写路径读取未 scope）+ **估时 4 → 5 人时**（依据 = `T-101` 交付报告「未完成/待裁定 ①」披露；`PlanService.java` 登记时点 md5 `a18d23b7…` / 1237 行，`:775` 签名已接受 `operatorId`、`:786` 仍调未 scoped `findByHedgePlan`）。**该轮全项目口径 = 102 个编号 / 1703 人时**（任务数不变，仅 `T-102` +1）；`#java` = **21 个 / 297 人时**、`#cpp` = **13 个 / 268 人时**、待指派 = **1 个 / 18 人时**；**M2 = 47 个 / 755 人时**；Java 侧合计 **845 人时**（Java + `#cpp` = **1113 人时** = 全项目 **65.36%**，分母 1703）。**历史口径保留**：本条之前的 §5 条目（`T-102` 第 1/2 轮）保留其轮次事实、不回改；现行值以 §1 合计行 + 本节本条 + §2 `T-102` 为准。同步更新：§1 总览（`T-101`/`T-102` 行 + 合计）、§2 详情（`T-101` 状态行 + `T-102` 块）、§3 依赖图、§4 里程碑映射 M2、§5 资源负荷、`BOARD.md`。 | 编排者（裁定）/ 项目经理（回写） |
| 2026-09-21 | **`T-061` 移区（`DONE` → 「已完成，待独立复核」）（项目经理 · 第 3 轮补充）**：`BOARD.md` §2 由 `DONE` 区移入「已完成，待独立复核」区；`TASKS.md` §1 状态格与 §2 状态行**去掉 `DONE` 前缀**，只留「已完成，待独立复核」（与其余 17 条同形）。**两表分类与文案一致** | `BOARD.md` §2 `T-061` 原在 `DONE` 区，但其**证据档写「已完成，待独立复核确认（不自评）」** ⇒ **分区与证据档不一致**（**预存**，本轮由项目经理 §7 第 1 条报出）。**分区即操作信号**（「这行能不能依赖」）⇒ 本项目 `DONE` = **授权 + 独立验证**，`T-061` **只有自评** ⇒ 应移入待复核区。编排者 2026-09-21 裁定 | **纯状态/分区口径，无任务增删、无人时变化**（**102 编号 / 1703 人时不变**）；`BOARD.md` §2 计数 `DONE 4 → 3`、待复核 `18 → 19`。**历史快照不回改**：`TASKS.md:150`（派发状态 2026-09-20 note）与 `BOARD.md:25`（M1 施工进展 2026-09-20 note）内「`T-061` → `DONE`」为**时点快照**，按项目惯例保留 | 编排者（裁定）；项目经理（`TASKS.md`/`BOARD.md` 回写） |
| 2026-09-21 | **`T-101` → `DONE` + 新增 `T-103`（项目经理 · 第 4 轮回写）**：① **`T-101`** 由「已完成，待独立复核」→ **`DONE`**（判据 = **授权 + 独立验证**；独立复核已回、判定**通过**：基线 **`310/0/0/0`** clean 无排除、**共享树源码清单 md5 复核前后全等（零改动）**、**自建 11 例探针**、**三向反退化变异**〔A 退回无条件查询 6/10 红 / **B 空集当不过滤 2 红** / **C 空 scope 恒真 `1=1` 2 红**〕、真实 DAO SQL 形状与 AND 括号/顺序均核、13 条交叉核对全符合）；本文 §1/§2 `T-101` 状态置 `DONE`（`BOARD.md` 由「已完成，待独立复核」区移入 `DONE` 区：`待复核 19 → 18`、`DONE 3 → 4`）。② 新增 **`T-103`**（`#java`，M2 窗口，**4 人时**，**依赖 `T-101`**）= `GET /plan/{id}/trade-plans` **补分页/筛选/排序** —— 依据 `FR-PLAN-15` **「输入」列**（`PLAN.md:321` 明列「筛选条件（子账户、套保计划、合约、状态、执行期限）、排序、**分页**」；同 FR 优先级 = **P0**，`PLAN.md:324`）；**不需 `CH-xxx`**（`CONTRACTS §1.1` 已冻结「分页接口用 `PageData` 信封」，属**向冻结口径对齐 = 修正违规**，非变更契约）；来源 = `T-101` 独立复核探索性发现 `EXP-T101-1`；**改 wire 形状 ⇒ 须同步前端**（交易计划列表前端归属**待编排者指派**）。**收窄说明**：`T-061` 移区（原改动二）**已由 `pm-t101-status` 实例先行完成**，本轮仅**核验、未重复写**（本文 `T-061` §1 与 §2 均已无 `DONE` 前缀）。**该轮全项目口径 = 103 个编号 / 1707 人时**（1703 + 4）；`#java` **21 个 / 297 人时 → 22 个 / 301 人时**；**M2 = 48 个 / 759 人时**；Java 侧合计 **849 人时**（Java + `#cpp` = **1117 人时** = 全项目 **65.44%**，分母 1707；M1 23.90 / M2 44.46 / M3 16.87 / M4 14.76 %）。**串行顺序更新**：`T-100 → T-101 → T-103 → T-102 → T-099 → T-098 → T-096 → T-097`。**历史条目保留原口径**（第 1/2/3 轮不回改） | 编排者（裁定）；项目经理（`TASKS.md`/`BOARD.md` 回写） |
| 2026-09-21 | **`T-010` 退回重做 + 四项硬缺口登记 + `CH-010` 验收 3 订正 + `T-102` 判据订正 + `T-097` 证据补入（项目经理 · 第 5 轮回写）**：① **`T-010`** 经独立复核（复核者 `te-verify-t010`）判**不通过** ⇒ 由「已完成，待独立复核」区移入 `IN_PROGRESS` 区（退回重做）。**四项硬缺口（仅登记、不改原验收）**：(a) REST `/auth/**` 声明 **10** / 注册 **2** / **差 8**；(b) `FR-AUTH-1~18` 已交付 **2** / 部分 **5** / 未交付 **11**；(c) `FR-AUTH-17④` 阈值硬编码未接 `CONFIG` + `FR-AUTH-15②` `SessionAuthFilter` 不调 `touchSession`（**绝对**过期，非「空闲」超时）；**(d) 新增 —— AUTH 预设数据零播种**：`docs/DEPLOY/schema/` 全目录仅 **2** 条 `INSERT`（`t_sub_account` / `t_product`），`t_role` / `t_position_group` / `t_position_conflict` / `t_user` **0 命中**，而 `m1_config_auth.sql:508` 自述默认互斥集恒为 `Y` ⇒ 默认集本应被播种；**影响** `FR-AUTH-6` / `FR-AUTH-7` **部署后无数据**（岗位分离 / 权限体系无法工作）。**承接核查**：`TASKS.md` 全表**无**任务列出 `data-init.sql` 产出、亦**无** `D-xx` 部署工作项 ⇒ **无既有承接任务，由 `T-010` 一并交付**（在既有 `data-init.sql` 追加 AUTH 预设段；DDL 载体由 `T-004` 交付 ⇒ 依赖 `T-004`）。② **`T-010` 验收标准 3 订正为「析取」** —— 依 **`CH-010`**（`FR-AUTH-9` 操盘拒绝判据为**析取**）：原「`is_enterprise_leader='Y'` **且** `is_management_role='Y'`」订正为**两条各自独立**（满足**任一**即拒），标注依据 `CH-010`；**验收标准 1（会话空闲 30 min 超时）不改**（该条正确、错在实现）。③ **`T-102` 验收面扩一项** —— 除接线外，`AuthService.assertCanTrade` 判据形态须由**合取改析取**（依 `CH-010`）、补**两类单条件拒绝用例**（②a `is_enterprise_leader='Y'` 单条件 / ②b `management`（`APPROVER`）单条件各拒）；**估时维持 5 人时（不上修，理由见 §2 `T-102` 备注）**。④ **`T-097` 证据补入** —— `EXP-T010-2`（`T-010` 的 8 条已声明未注册端点未被任何 CI 判据捕获；`ModuleRoutesConsistencyTest` 覆盖下限硬编码 `AUTH=2`、对**少注册**结构性失明 ⇒ `T-097` 的等值断言为该缺口的**判据侧收口**）。**该轮全项目口径 = 103 个编号 / 1707 人时不变**（纯验收/备注/加行口径，**无任务增删、无人时变化**） | `docs/TEST_RESULT.md` §`T-010`（复核者 `te-verify-t010`，2026-09-21；**以实时 `md5sum` 为准**）判 `T-010` **不通过**；`CH-010` 已由需求侧登记 `docs/REQUIREMENTS/AUTH.md`；（d）由项目经理实测；编排者 2026-09-21 裁定 | 编排者（裁定）；项目经理（`TASKS.md`/`BOARD.md` 回写） |
| 2026-09-21 | **`T-103` 交付 → 「已完成，待独立复核」+ 登记 `T-104` + `T-096` 追加「稳定排序」并上修 2 → 3 人时（项目经理 · 第 6 轮回写）**：① **`T-103`** 由 `TODO` → **「已完成，待独立复核」**（`backend-engineer` 已交付 `GET /plan/{id}/trade-plans` 补分页/筛选/排序：隔离副本 clean 全量 **`319/0/0/0`**（`2026-09-21T07:18:40`–`07:20:00`，无排除）、共享树 clean **`319/0/0/0`**（`07:16:43`）；**反退化两方向**（移除过滤 / 移除分页）**均变红**；真实 DAO SQL 两方向均核：`COUNT` 与 `SELECT` **两条均带 scope**、**空 scope ⇒ 两条均含 `IN (-1)`**、**哨兵 `0` ⇒ 两条均不含**；`total` 为**过滤后总数**（假 DAO + 真实 DAO 双证）；`ApiRoutes.java` 未变、`E5` 退出码 0；**独立复核尚未进行** ⇒ 按本项目判据「`DONE` = **授权 + 独立验证**」置该状态，**不替其下「通过」结论**）。② 登记 **`T-104`**（`#java`，M2 窗口，**2 人时**，**依赖 `T-103`**）= `FR-PLAN-15` **处理规则**两项（**剩余可下单量** = 数量 − 已下达指令量 + **已达可下单量上限标记**；（含前端展示，**另派**））—— 该项被 `T-101`/`T-103` **两次**判为「非本任务范围」⇒ **单独立号以免持续掉队**。③ **`T-096` 追加「稳定排序」要求 + 估时上修 2 → 3 人时**（来源 = `T-103` 交付披露 (b)：分页 `ORDER BY` 仅单列、**无唯一 tie-break** ⇒ 同 `exec_deadline` 值**跨页可能重复/漏行**；`T-103` 分页排序稳定性**依赖 `T-096`**）。**`T-103` 交付披露项已登记入其备注（仅登记、不改其验收原文）**：(a)→`T-104`；**(b)**→`T-096`；**(c)** `TradePlanDao.findByHedgePlanScoped` 生产侧**已无调用者**（仅测试引用、保留为 `T-101` 冻结契约面）；**(e)** 「套保计划」筛选由**端点路径 `{id}`** 承载（未另设查询参数）+ 「执行期限」实现为**闭区间** `execDeadlineFrom/To`。**该轮全项目口径 = 104 个编号 / 1710 人时**（1707 + `T-104` **2** + `T-096` 上修 **1**）；`#java` **22 个 / 301 人时 → 23 个 / 304 人时**；**M2 = 49 个 / 762 人时**；Java 侧合计 **852 人时**（Java + `#cpp` = **1120 人时** = 全项目 **65.50%**，分母 1710；M1 23.86 / M2 44.56 / M3 16.84 / M4 14.74 %）。**串行顺序更新**：`T-100 → T-101 → T-103 → T-104 → T-102 → T-099 → T-098 → T-096 → T-097`。**历史条目保留原口径**（第 1~5 轮不回改）；现行值以 §1 合计行 + 本节本条 + §2 `T-096`/`T-103`/`T-104` 为准。 | `backend-engineer` 交付 `T-103`（证据 `319/0/0/0` + 反退化两方向 + 真实 DAO SQL 双方向）；`FR-PLAN-15`「处理规则」两项被 `T-101`/`T-103` 两次判为「非本任务范围」；`T-103` 交付披露 (b) 分页无 tie-break（属经典分页缺陷、绿测不暴露）；编排者 2026-09-21 裁定 | **任务数 103 → 104**（M2 48 → 49）；**总估时 1707 → 1710 人时**（`T-104` +2、`T-096` 上修 +1）；`#java` **22 个 / 301 人时 → 23 个 / 304 人时**；`#cpp` 13/268、`#java2` 29/548、待指派 1/18 均不变；**M2 = 49 个 / 762 人时**；占比分母改 **1710**（M1 23.86 / M2 44.56 / M3 16.84 / M4 14.74 %）；Java 侧合计 849 → **852**（Java + `#cpp` = **1120 人时** = 全项目 **65.50%**） | 编排者（裁定）；项目经理（`TASKS.md`/`BOARD.md` 回写） |

> **📌 本表自第 7 轮起不再逐轮登记**（第 7 轮及以后的变更记录**唯一现行台账 = `BOARD.md` §5「变更记录」**）。本表保留第 1~6 轮历史行，**不回填**，以免时序错乱。

