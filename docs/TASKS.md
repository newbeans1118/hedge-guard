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
| T-010 | 实现用户与权限服务（岗位分离 + 子账户软隔离） | FR-AUTH-1~18 | backend-engineer#java2 | P0 | T-004 | 20 人时 | 初版 | **DONE（授权 + 独立验证）**〔第 90 轮 **状态前缀订正**：原前缀「已完成，待独立复核」为**陈旧值**—— 本单实经 `te-verify-t010b`（四项硬缺口全过、唯一低缺陷 `TE-T010b-001`）+ `te-rec-t010`（delta 复检通过 / 0 缺陷）复核，已于第 56 轮置 `DONE`（依据见 `BOARD.md` `DONE` 区）；本次仅订正 §1 前缀、下方交付叙事**一字未动**〕（2026-09-22 **退回重做交付**〔实例 `be-t010`〕：四项硬缺口**全部闭环** —— `(a)` AUTH 注册 **2/10 → 10/10**（17 个 `(方法,路径)` 对；路由计数 **67 → 82**，`BootstrapAssemblyTest:136` 断言同步订正**并加注理由=需求面缺口补齐**）；`(b)` `FR-AUTH-1~18` 覆盖 **2 已交付/5 部分/11 未交付 → 18/18 已交付**；`(c)` ① 锁定阈值/时长**接 `Config`**（`hg.auth.login_fail_limit`/`_lock_minutes`）并补 `LOCKED`/`UNLOCKED` 事件留痕；② `SessionAuthFilter` → **`SessionRenewer` → `touchSession` 滑动续期**（`AuthSessionIdleTimeoutTest` 证「+20min 请求后 +40min 仍有效」⇒ 绝对过期下必红）；`(d)` AUTH 预设数据**幂等播种**（`t_position_group` 7 / `t_role` 7 / `t_position_conflict` 5，`INSERT…SELECT…WHERE NOT EXISTS`）+ `AuthSeedDataTest` 断言行数/关键字段/幂等。**自报门禁**：`mvn -B -o clean test` **484/0/0/0**、`@Test` **484** == surefire **484**、聚合 **`rc 0`**（`E1`–`E7` 全 0、`E5` 退 0）；**4 例变异全杀**（短路 `conflicts()` / `\|\|`→`&&` / 去续期器 / 去 `NOT EXISTS`）+ 复位字节复原；冻结点 `find java/src -type f \| LC_ALL=C sort \| xargs md5sum \| 已完成，待独立复核（2026-09-22 **`TE-T010b-001` 修复交付**〔实例 `be-t010fix`〕：取**方案 (i) 段② 改「直连分发」**（2 参 `SessionAuthFilter` = **不续期**，认证过才进路由 ⇒ **恢复 404/500 区分力**）；**否决方案 (ii)**（理由：装配面注册**已由 `BootstrapAssemblyTest:140` 的 `routeCount == 82` 守卫**，(ii) 系三重重复）。**改动仅** `java/src/test/java/com/hedgeguard/web/T109EndpointRegistrationTest.java`（段② 改直连 `plain`、反向对照复用同一 `plain`、类级 javadoc + 段内加注），diff **+29/−5**；**`src/main` 零改动**。**★双向证据**：① **先证空转**：同一变异（摘 `ConfigRoutes` 的 `GET /config/ctp`）下**原段② 仍绿**（假驱动下已认证请求一律 500、永不触 404/405）；② **新段② 必红**（`[GET /api/v1/config/ctp → 405]`）、**复位（byte 复原 md5 `aa92832f…`）后绿**；直连分发下未注册路径 = **404**，19 条全 500/400、**无一是 404/405**。**自报门禁**：`mvn -B -o -T1 clean test` **484/0/0/0** + `BUILD SUCCESS`、`@Test` **484** == surefire **484**、聚合 **`rc 0`**（`E1`–`E7` 全 0、`E5`=0）；冻结点 `find java/src -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` = `8bf0e1d6c2e83a52e62d511616324c4b` / **335 文件**。**修复证据已纯追加**至 `docs/TEST_RESULT.md` 新章「`TE-T010b-001` 修复证据」（追加前 `04a87a2d…`/3817 → 现 `1243a62b…`/3863；`head -3817 \| **DONE（授权 + 独立验证）**〔第 56 轮〕（**完整验收链**：① **四项硬缺口**独立复核 `te-verify-t010b` **全部通过**（注册 **2/10→10/10**、`FR-AUTH-1~18`→**18/18**、阈值接 `Config` + **滑动续期**、预设数据**幂等播种** 7/7/5）；② **唯一低缺陷 `TE-T010b-001`（守卫空转）已修复并经 `te-rec-t010` delta 复检 = 通过 / 0 缺陷**；**编排者 2026-09-22 授权**）。**修复复检要点（独立实测）**：`mvn -B -o -T1 clean test` **484/0/0/0**、surefire **484** == `@Test` **484**（无注释吞并）、聚合 **`rc 0`**（`E1`–`E7` 全 0、`E5`=0）；**★空转复现成功**（自撰探针，未复用产出方手法）：经装配分发器 19 条 DISPATCH + 1 条不存在路径**一律 500**；**等价还原「修复前段②」在 M1 下仍绿** ⇒ **空转证实**；**★新守卫双向**：同 M1 下**修复后段② 红**（`t109EndpointsNot404Not405:167`、`[GET /api/v1/config/ctp → 405]`）、**复位字节复原 + clean 重编译后绿**；**无关变更（另注册新端点）不误红**；断言前后**逐条一致、无放宽**（仅分发器由装配改直连、强度升）；`src/main` **零改动**（mtime+diff 范围佐证）；**「方案 (ii) 系重复」的理由成立** —— `BootstrapAssemblyTest:140` 的 `routeCount==82` 确有，**M1 下实测红**（`82 vs 81`）；冻结点 `java/src` = `8bf0e1d6c2e83a52e62d511616324c4b` / **335 文件**（3×≥9s 不变）。**证据章**：`docs/TEST_RESULT.md` **`:3692`**（`T-010` 复核）+ `:3867–:3983`（**修复 delta 复检**，纯追加已证：`head -3863` = `1243a62b…`；追加后 `b4b47f6a…`）。**3 项信息级观察（无缺陷、不另立）**：`OBS-1` 覆盖移交（段② 不再经 Bootstrap，由 `BootstrapAssemblyTest:140` 兜住、M1 下已实测红）；`OBS-2` 「三重重复」措辞宜作「**1 完全重复 + 1 部分重叠**」；`OBS-3` `src/main` 零改动系 mtime+diff 范围佐证（**修复前树不可重建**）。**⚠️ 1 项需求阐释项（低，不阻塞本单）**：`forceLogout`（`FR-AUTH-15④`）的「触发入口是否须入 REST 面」—— 实测原文**无**端点措辞、能力在服务层已实现 ⇒ **按原文判非缺口**，**留待产品席**；若认领 ⇒ 另立号） |
| T-011 | 实现参数下发编排 ParamDispatchService | R-CONFIG-3, R-AUTH-9, R-PRODUCT-9, R-PLAN-5, R-RISK-9 | backend-engineer#java2 | P0 | T-004, T-061 | 12 人时 | 初版 | 已完成，待独立复核 |
| T-012 | 实现品种管理与白名单/额度服务 | FR-PRODUCT-1~14 | backend-engineer#java2 | P0 | T-005 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-013 | 实现现货管理服务（合同/生产/库存/现货价） | FR-SPOT-1~12, CFG-18 | backend-engineer#java2 | P0 | T-006 | 20 人时 | 初版 | 已完成，待独立复核 |
| T-014 | 实现实货敞口计算服务（三分量 + 快照时点 + 缺口处置） | FR-SPOT-13~18, CFG-12/12b/17/17b | backend-engineer#java2 | P0 | T-013 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-015 | 实现套保比率三档计算 | FR-PLAN-6/7/8, R-SPOT-8, CFG-06/06b | backend-engineer#java2 | P1 | T-007 | 16 人时 | 初版 | 已完成，待独立复核 |
| T-016 | 实现套保计划编制/书面文件门禁/审批链/状态机 | FR-PLAN-1~5/9~20, R-PLAN-5 | backend-engineer#java2 | P0 | T-007, T-015 | 24 人时 | 初版 | DONE（附条件；判据 = 授权 + 独立验证；独立复核 = 有条件通过〔13 项判据 12 符合；`CR-002` 核心反例闭合；反退化 8/8 全红；隔离副本 `346/0/0/0` 独立复现〕；条件 = (c)「审批页面展示」由 `T-020` 承载，义务不减） |
| T-017 | 搭建前端基座（登录/核心工具/零构建链） | NFR-USE-1~5, NFR-MAINT-1/5, NFR-COMPAT-3 | frontend-engineer | P0 | T-003 | 16 人时 | 初版 | **DONE（授权 + 独立验证）**〔第 95 轮 · 独立复核 `te-verify-t017` = **通过 / 0 缺陷 / 5 观察**〕 |
| T-018 | 实现品种管理前端页面 | FR-PRODUCT-1~14 | frontend-engineer | P1 | T-017, T-012 | 12 人时 | 初版 | **DONE（授权 + 独立验证）**〔第 91 轮 · 独立复核 `te-verify-t018` = **有条件通过 / 0 阻断缺陷**（缺陷单 0；观察 4）⇒ 转 `DONE`；证据章 `docs/TEST_RESULT.md:5580`（`L5579..5775`，**纯追加已核**：`head -5578` = `6988ede8…`）〕（2026-09-23 实例 `be-t018` 交付，**不自评 `DONE`**；**2 改**：`web/js/pages/product.js` 19 行 stub → **999 行**真实界面（`b8e66b70…`）、`web/js/core/api.js` **+45/−1**（`db1d4e9d…`，唯一改动 = `HG.api` 导出清单追加 `download`；8 个既有函数未动）；`web` 树锚 **`f827e00e…`/27**（基线 `81a06bce…`/27）；`E6` **rc=0**；无头桩自测 **23/23 PASS**（**非真实浏览器**，已如实标注）；**7 项未决**见 §2，其中 **2 项已立 `T-131`/`T-132`**） |
| T-019 | 实现现货与实货敞口前端页面 | FR-SPOT-1~18 | frontend-engineer | P1 | T-017, T-013, T-014 | 16 人时 | 初版 | TODO |
| T-020 | 实现套保计划前端页面 | FR-PLAN-1~20 | frontend-engineer | P1 | T-017, T-016 | 16 人时 | 初版 | TODO |
| T-021 | 实现权限与系统配置前端页面 | FR-AUTH-1~18, FR-CONFIG-1~15 | frontend-engineer | P1 | T-017, T-008, T-009, T-010 | 16 人时 | 初版 | TODO |
| T-022 | 制定 M1 测试策略与用例设计（约束→用例映射） | NFR-TEST-1/2/3, NFR-MAINT-2 | test-manager | P0 | T-002 | 16 人时 | 初版 | **DONE（授权 + 独立验证）**〔第 98 轮 · delta 复检 `te-rec-t022` = **通过 / 0 阻断**〕 |
| T-023 | 编写 M1 单测与集成测试（JUnit 5） | NFR-TEST-1/3, NFR-MAINT-2 | test-engineer | P0 | T-010, T-012~T-016 | 32 人时 | 初版 | **IN_PROGRESS**（第 92 轮独立复核 = **有条件通过 / 0 阻断 / 1 缺陷（中）** ⇒ **退回补测** `TE-T023-001`） |
| T-024 | 执行 M1 端到端验收（双子账户→品种/敞口→计划→隔离→落库） | PROJECT §9 M1 验收, §10-34 | test-engineer | P0 | T-023 | 16 人时 | 初版 | TODO |
| T-025 | 执行 M1 代码与架构合规审查 | NFR-MAINT-4/5/6, NFR-SEC-9, PROJECT §10-7 | code-reviewer | P0 | T-012~T-021 | 16 人时 | 初版 | TODO |
| T-026 | RSK-01 protobuf 在 C++11 下空壳构通验证 | ARCH §9 RSK-01, §7.1.5 | backend-engineer#cpp | P0 | — | 8 人时 | 初版 | DONE（**附条件**；判据 = **授权 + 独立验证**；`T-030` 汇签结论 = **通过**〔**技术风险已排除，不得上推为「协议已验证」** —— `proto/hgcp.proto` 实测不存在，协议级闭环待 `T-032`〕；见 §2 备注） |
| T-027 | RSK-02 CTP 头文件 C++11 兼容验证 | ARCH §9 RSK-02, NFR-COMPAT-1/2 | backend-engineer#cpp | P0 | — | 7 人时 | 初版 | **DONE（授权 + 独立验证）**〔第 74 轮〕（独立复核 `te-verify-t033b` = **通过**；**编排者 2026-09-22 授权**。原为【2026-09-22 因 CTP 基线变更退回重做、**同日重做交付**】原结论「真实 CTP SDK `V1.7.7` 合并版头文件 C++11 编译 + 链接通过、`PASS=11`」**是在 `CTP Mini`（SFIT 上期技术）基线上取得**，而用户已裁定**交付基线 = 官方 CTP、且 `CTP Mini` 不作本机开发替代**（`CH-015`）⇒ **该结论对交付基线不成立**；用户已于 2026-09-22 提供**官方 CTP v6.7.13**，现**在官方 SDK 上重做**（实例 `be-ctp-official`）⇒ 依 `DONE` 判据〔授权 + 独立验证〕**退回**。**原 `DONE` 的历史事实保留**：独立复核 `te-verify-t027` **通过** ＋ `T-030` 汇签**通过**，**适用范围仅限 `CTP Mini` 基线**。⚠️ **`T-030` 汇签中「RSK-02 已排除」的适用范围随之收窄**；**本项重做结论到达前，`RSK-02` 对交付基线维持「未排除」**。**📌（2026-09-22）重做已交付**：官方基线 `ci/run_probe.sh` = **`PASS=12 / FAIL=0`**（`B1–B8 + B5 + B6 + B6b`；**新增 `B6b`**，且 **`B6` 判据已反转**为「`TraderApi` **可得** = 通过」）；探路件已按官方分离式布局重冻结。**✅ U1 缺省对齐小改已于同日落地**（见 §2 备注）⇒ **本项转「待独立复核」**（其冻结点以实时 `md5sum` 为准）） |
| T-028 | RSK-06 Java 端 WebSocket 选型与实现 | ARCH §9 RSK-06, §4.1.2, NFR-PERF-5, `CH-006` | backend-engineer#java | P0 | — | 16 人时 | 初版（`CH-006` 口径收紧） | DONE（独立复核通过；见 §2 备注） |
| T-029 | RSK-07 websocketpp 落点确认（**已取消**） | ARCH §9 RSK-07（**因 `CH-006` 移除 websocketpp 而失效**） | backend-engineer#cpp | P0 | — | 0 人时（原 8，已扣除） | `CH-006` | REJECTED |
| T-030 | 汇签 M2 探路结论并下达铺开许可 | ARCH §9（三项探路：T-026/T-027/T-028）, §3.13 | tech-lead | P0 | T-026, T-027, T-028 | 4 人时 | 初版 | DONE（见 §2 备注） |
| T-031 | 搭建 C++ 交易组件工程骨架与构建 + arch-check 聚合入口与 CI 接线 | NFR-COMPAT-1/2/7, NFR-MAINT-4/6 | backend-engineer#cpp | P0 | T-030 | 24 人时 | 初版 | **DONE**（判据 = **授权 + 独立验证**；**依据链见 §2 备注**：原 `TODO` 系**账实背离**，2026-09-22 编排者订正） |
| T-032 | 实现 HGCP 帧编解码与元协议（鉴权/心跳/补数） | FR-TRADE-14, FR-CONFIG-6/9, NFR-SEC-3, NFR-REL-2 | backend-engineer#cpp | P0 | T-031 | 32 人时 | 初版 | 已完成，待独立复核（2026-09-22 **残余收口交付**〔实例 `be-t032res`〕：补数 `MSG_RESUME_DATA` body 已按 schema 编码（`TransportPolicy` 增纯虚 `resume_data_body(from,to,is_last,frames)`；`server_loop.cc:179-197` 逐批组装）；**自报门禁**：构建 **0 warning/0 error**、`ctest` **31/31**（名单以 `ctest -N` 为准）、`transport_test` **2970 checks/0 fail**、聚合 **rc 0**（`E1`–`E7` 全 `0`）、**2 例变异全杀**（退回原始拼接 ⇒ 红；`is_last` 反转 ⇒ 红，含 **500/501** 边界双向）+ 复位 clean 重编译回绿；**断言订正仅注释**（`transport_test.cc:602-606`，**断言 `:612` 未改未放宽**）；冻结点 `find cpp/src -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` = `38d3f3fb61859bc0592e536399b0bc5a` / **110 文件**（模块级 `cpp/src/hgcp` = `a0a041de…` / 37）。 **只读确认回报**：(1) **`I-2` 已实现且与 `CH-014` 一致**（`server_loop.cc:137-141` 超限 ⇒ 回 `0x0308` **随后** `close_conn()`）；(2) `T032_GAP_ANALYSIS §5` **未加注**（仍历史态，**只报不改**）；(4) 枚举值名前缀**已确认**，但 **proto 包名 `hgcp.v1` 未在 `§4.2.8` 权威正文确认**（仅附录 C 非权威；`E7` 显式不覆盖 `package`）。 **⚠️ 三项未决（单列，不得并入"已完成"）**：(a) `from/to` 的**权威语义** `§4.2.8` 未明确定义（实施方取「**本批首/末帧 SEQ**」，替代解为整体区间 `[last+1, last_sent]`）⇒ **跨侧（`T-042`）须对齐，已请架构席裁定**；(b) `resume_data_body` 系 `TransportPolicy` **接口新增纯虚**（3 实现者均在 `hgcp` 内）⇒ 须架构席确认该接缝扩张可接受 / 是否需登记；(c) `T032_GAP_ANALYSIS §5` 加注**不在本单范围**（低优先，另议）。 **独立复核待派**（**因本机负载 5.04、两 JVM 在建，按负载排队**）；依 `DONE` 判据〔授权 + 独立验证〕**不置 `DONE`**） |
| T-033 | 实现 CTP 适配层 ctp_adapter（交易+行情+资金划转） | FR-TRADE-2/4/5/6/12, FR-FUND-5/6/8 | backend-engineer#cpp | P0 | T-031 | 29 人时 | 初版 | **DONE（授权 + 独立验证）**〔第 74 轮〕（独立复核 `te-verify-t033b` = **有条件通过 / 阻断级缺陷 0**〔3 项观察、均无阻断〕；**编排者 2026-09-22 授权**。原为【2026-09-22 因 CTP 基线变更退回、**同日完成官方分离式移植**】交付物原系按 `CTP Mini` 的**合并式 `UserApi`** 形态（`ThostFtdcUserApi.h` / `libthostuserapi.so` / `CThostFtdcUserSpi`）实现，而**官方 CTP 为分离式**（`ThostFtdcTraderApi.h` + `ThostFtdcMdApi.h`；`CreateFtdcTraderApi()` / `CreateFtdcMdApi()`；`CThostFtdcTraderSpi` / `CThostFtdcMdSpi`）⇒ **须移植**；用户 2026-09-22 已提供**官方 CTP v6.7.13**，**移植工作量盘点中**（实例 `be-ctp-official`），**估时待盘点后上修**（现行登记仍为 24 人时）。**原交付历史保留**：2026-09-22 `be-t033` 交付；**自报** `cpp/src/ctp_adapter/**` **20 文件 / 2845 行**；两轨构建 `rc 0`、`warning:`/`error:` **0/0**（CTP 轨 9 TU / stub 轨 5 TU 均带 `-std=c++11 -Wall -Wextra -Werror`，CTP 轨 `-isystem /home/ctpmini/ctpmini_admin/ctp_sdk`）；`ctest` **CTP 22/22 / stub 19/19**（差 = 无 SDK 轨少注册 `rsk02` 且 `ct_mapping_test`/`ct_spi_test` 依赖真实头/库）；聚合 **`rc 0`（`E1`–`E7` 全 0）**；隔离副本 4 例变异全杀（`/root/t033probe`）+ 复位；**共享树残留 0**；冻结点 `find cpp/src/ctp_adapter -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` = `4ab77612bc964a3bcdebc11291cbbfab` / **20 文件**（**编排者已独立复测一致**）。**独立复核 `te-verify-t033` 进行中**；依 `DONE` 判据〔**授权 + 独立验证**〕**不置 `DONE`**；见 §2 备注；**📌 独立复核 `te-verify-t033` = 有条件通过（阻断级缺陷 0）**：两向验证「该防的已防、未见误伤」、两轨（CTP/stub）真编真跑、反退化 MUT-A 双向、`-isystem` 经实测**既生效且必需**（改 `-I` ⇒ 第三方头告警撞 `-Werror` rc 2）；证据章 `docs/TEST_RESULT.md`:3372 起（**纯追加已证**：`head -3368` = 基线 `dbad2d39…`）。**⚠️ 两项条件未清 ⇒ 不转 `DONE`**：(i) **`TE-T033-001`（中·待裁定）** `sendOrder` 返回 `CtOrderAck` vs `ARCHITECTURE.md` §3.1 裸 `OrderLocalId` —— **接口形态偏离，归属技术席裁定**（复核席明确不自行判缺陷；并指出验收 4 + §4.3.2 `0x2004` 要求「回码 + 退回标志」，**裸 `OrderLocalId` 无法承载** ⇒ 存在张力）；(ii) **SDK 基线限定**：本机 SDK 实为**上期技术（SFIT）`CTP Mini` V1.7.7（非官方 CTP）** ⇒ 凡涉「与 §3.1 CTP 口径一致」的判定**须附基线限定**，`CH-015` 待用户裁定。另：`MUT-C` 探针证明「回调只序列化+入队」纪律**现有判据拦不下**（`E4` 默认根不扫 `ctp_adapter`）⇒ 已报为**守卫强度事实**、非缺陷）。**📌（2026-09-22）移植与探路件重做已交付**（实例 `be-t033port`，**不自评 `DONE`**）：`cpp/src/ctp_adapter` = `fb6c7ce5cd990c9fd30b2f3b912f00b6` / **23 文件 / 3213 行**（前态 20 文件 / 2845 行）；**★静默降级已消除 + 守卫双向承重**（显式给 SDK 根却解析失败 ⇒ **`rc=1` + CMake `FATAL_ERROR`**；删守卫 MUT-D ⇒ 回落 `rc=0` ⇒ 守卫承重；前态复现「`CTP_FOUND=OFF` 走空实现而构建成功」= 假绿）；三轨 `ctp`（官方 v6.7.13）**38/38**、`stub` 34/34、**`fetch` 仅验 CTP 相关目标/用例（未跑 protobuf 全量构建）—— 实施方如实声明**；`.so` 以**绝对路径 + RUNPATH** 链接（官方无 `lib` 前缀/无 SONAME）；聚合 `rc 0`（`E1`–`E7` 全 0）；**MUT-A 去掉 `-isystem` ⇒ `rc=2` `-Werror=unused-parameter`** ⇒ 证两目录 `-isystem` **生效且必需**。**U1 已裁定并派回修**：`CtConfig` 缺省由 `kProduction` **对齐 `CFG-22` 的已登记缺省 `SIMULATION`**（理由：唯一权威缺省 + **fail-safe**〔缺省指评测只「连不上」、指生产可能**误连实盘**〕）；**须显式披露该行为变更**（改前不配置⇒生产、改后⇒评测；上生产须显式设 `CFG-22=PRODUCTION`）。**U2 裁定：无需重估、不立号**（`nSeqNo` 仅在 `THOST_TERT_RESUME_FROM_SEQ_NO` 下有意义，现 1 参调用与旧版**逐字等价**；其「`QUICK` 不重放断连期私有流事件」记为**观察项**、无 `FR` 依据不立号）。**实施方自查自修三处**（`run_probe.sh B4` 大写 `V` 假红、`B6` 用普通 `-I` 撞 `-Werror` 假红、**`cp -a` 保 mtime ⇒ 陈旧对象不重编译 ⇒ MUT-C 曾假绿** ⇒ 已改 `touch` + clean 重建）。**⚠️ 状态仍为 `IN_PROGRESS`**：U1 小改落地前**冻结点未定** ⇒ 落地后一并转「待独立复核」。**✅ U1 已于同日落地**（见 §2 备注：缺省改 `kSimulation`、行为变更 4 处披露、**新增缺省值断言**且 MUT-F 证其承重）⇒ **本项转「待独立复核」**（冻结点 `cpp/src/ctp_adapter` = `41e9ac046e0289ea68f45c234961ca88` / 23 文件 / 3225 行，**以实时 `md5sum` 为准**）；**另：文档过期口径**（「CTP 合并版 `UserApi`」，含「活文 vs 历史留痕」分类）**已分两单派出**（`tl-arch-cd` → `ARCHITECTURE.md` + `tools/arch-check/README.md`；`pm-risk-norms` → `PROJECT.md` + `docs/REQUIREMENTS/**`），**要求两席自行实测复核、不照单全信**。**仍待技术席裁定**：`TE-T033-001`（`sendOrder` 返回 `CtOrderAck` vs §3.1 裸 `OrderLocalId`）） |
| T-034 | 实现风控内存状态 risk_state（不可变快照 + 原子替换） | FR-RISK-9/10, ARCH §4.4, NFR-PERF-1 | backend-engineer#cpp | P0 | T-031 | 24 人时 | 初版 | **DONE（授权 + 独立验证）**〔第 42 轮〕（独立复核 `te-verify-t034` = **通过、0 阻断缺陷**；**编排者 2026-09-22 授权**；冻结点 `cpp/src` `22dc2265…`/68 文件复现一致；**3 项建议级观察** `O-1`/`O-2`/`O-3` ⇒ 其中 `O-1`/`O-3` 由 **`T-124`** 承接） |
| T-035 | 实现事前风控引擎 risk_engine（8 规则 + 三层限额 + 双层校验） | FR-RISK-1~8, FR-TRADE-3, NFR-PERF-1 | backend-engineer#cpp | P0 | T-034 | 32 人时 | 初版 | **DONE（授权 + 独立验证）**〔第 58 轮〕（2026-09-22 **收口实例 `be-t035b` 判定：零改动交付** —— 相对接手锚 `fc086003667fe0a890a69358c3a51c56` **逐字节一致**（前序实例 `be-t035` 的实现已达标）；**自报**：构建 0 warning/0 error、18 个 risk_engine TU **全部**带 `-std=c++11 -Wall -Wextra -Werror`、`ctest` **13/13**、聚合 **`rc 0`**（`E1`–`E7` 全 0，**`E4` 实扫 45 文件、命中 0**）、**4 例变异全杀**（层序反转 / 撤平仓豁免 / `thresholds_` 改 mutable / 判定路径自旋 ⇒ **P99 28.962ms > 5ms 必红**）+ 复位 clean 重编译回绿；**P99 实测 33–83µs**（≪5ms）；冻结点 `find cpp/src/risk_engine -type f \| LC_ALL=C sort \| xargs md5sum \| 已完成，待独立复核（2026-09-22 **`TE-T035-001` 修复交付**〔实例 `be-t035fix`〕：`risk_engine.cc` `:212-214` 的 `evalQuota` 增 `bool is_open` + 首行 `if(!is_open) return;`、调用点 `:552` 传参；`order_request.h:28-34` 补 `kClose` 语义注释；**新增** `tests/risk_engine_close_exemption_test.cc`（**9 用例 / 72 断言**）；`BUG-002.md` 修复节已填（结论「已修复，待复检」、**未自评 `CLOSED`**）。**自报门禁**：构建 rc 0 / 0 warning / 0 error、`ctest` **32/32**、新用例 **72 checks / 0 failures**、聚合 **`rc 0`**（`E1`–`E7` 全 0、**`E4` 实扫 46 文件 / 命中 0**）；**5 例豁免移除变异**（`mut_quota`/`poslimit`/`scale`/`netpos`/`term`/`all5`）**新用例均红**、复位后全绿、副本残留 0；冻结点 `find cpp/src/risk_engine -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` = `35cf08187b4354df81aeed6cf1213342` / **29 文件**（改前 `fc086003…`/28）。**8 规则「需否平仓豁免」三栏表（同类扫描结论）**：**需豁免 5 条**（`R-RISK-2` 三层限额〔本次修复〕/`R-RISK-3` 持仓上限/`R-RISK-4` 规模红线/`R-RISK-5` 时点净持仓/`R-RISK-6` 期限红线）**修复后全部已豁免**；**不需豁免 3 条**（`R-RISK-1` 白名单〔依据 `FR-RISK-1` 无豁免 + `FR-PRODUCT-12@PRODUCT.md:258` 验收2「有未平仓持仓不得移除白名单」⇒ 场景不成立〕/`R-RISK-7` 权限与授权〔`FR-RISK-7`「一律拒绝」无平仓例外；§3.11 `scope=OPEN_ONLY` 指**事中限开仓处置**、非身份/授权校验〕/`R-RISK-8` 价格偏离〔`FR-RISK-8` 仅「市价单不适用」，目的是**防异常报单**、非限制类处置〕）。**同类扫描未发现第二处漏补。****⚠️ 一处「实测订正」（产出方纠正编排者派单转述，据实留痕）**：编排者派单曾转述「撤平仓豁免的变异**只在持仓上限/时点净持仓上红**」；实施方实测**该转述不准** —— **`scale`（规模红线）与 `term`（期限红线）的既有用例也会红**（各 1），**真正无守卫的只有 `quota`** ⇒ 此即**原缺陷逃逸之因**（`quota_layers` 既有用例在该变异下**仍绿**）。**已请独立复检方（`te-verify-t035b`）独立复现该订正**（决定「为何逃逸」的归因表述）。**⚠️ 1 项待架构确认（不阻塞本单）**：`R-RISK-7` 的 **`individual_quota` 子项**是否属「限制类处置」而**亦须豁免平仓** —— 实施方判「不豁免」已入 `order_request.h` 注释、**现无改动、无锁死断言**；若架构席另裁须豁免 ⇒ 另立号。**📌 独立复检结论（2026-09-22，复核者 `te-verify-t035b`）= `通过` / 0 缺陷**（证据章 `docs/TEST_RESULT.md` **`:3987`–`:4120`**，**纯追加已证**：`head -3983 \| md5sum` = 追加前基线 `b4b47f6a…` → 追加后 `4d171628…`/4120）。**独立实测**：构建 rc 0 / 0 warning / 0 error、`ctest` **32/32**、聚合 **`rc 0`**（`E1`–`E7` 全 0）、**`E4` 实扫 46 文件 / 命中 0**（三目录并集、非 `SKIP`）、新用例 **72 checks / 0 failures**、**自建探针 94 checks / 0 failures（不复用产出方用例）**。**缺陷消除 + 反向**：额度层 ①/②/③ 及三层全超 ⇒ **开仓仍拒**（`0x1004`/`0x1017`/`0x1005`、layer 正确）＋**仅改 `offset=kClose` 即 PASS（`entries=0`）**、减仓方向同；反向 = 白名单/权限/价格偏离三条**平仓也拒**（无多豁免）、非法 `offset` 有前置校验、无旁路。**★归因订正被独立确认**：逐处撤 5 条豁免实测既有用例红数 = 撤 `quota`→**既有 0**（仅新用例红）/ 撤 `positionLimit`→`position_limit`+`net_position` / 撤 **`scale`→`scale_redline`（1）** / 撤 **`term`→`term_test`（1）** / 撤 `netPosition`→`net_position` ⇒ **编排者原派单转述错误、产出方正确**；另**反向变异**（开仓豁免 / 调用点传 `false`）⇒ 新用例红 35/20 ⇒ **双向承重**。**快照**：修复后锚 `35cf08187b4354df81aeed6cf1213342`/29 与自报吻合，**本席独立重建修复前同口径** `fc086003…`/28（与上轮 `T035.0` 吻合）；`risk_engine.cc` mtime 复核期未变；共享树残留 0。**5 项观察（均低、无缺陷、不另立号）**：① `R-RISK-n` 编号在 RISK §4 与 `ARCHITECTURE.md` §3.3 含义不同（**措辞歧义**）；② `net_position_test::test_close_is_exempt` 闭包**兼依赖 `R-RISK-3`**（非最小对偶）；③ 产出方表中 Permission/Price 行号**偏移 4**；④ `git diff HEAD` 另含**前一轮交付**的 `risk_engine.h`/purity/bench/test_util（**勿误归本单**）；⑤ `ARCHITECTURE.md` §3.3 的 `R-RISK-2` 行**未标「平仓不受限」**（依据 `R-RISK-5` 已足够 ⇒ **架构席域**、本席未改）。**编排者 2026-09-22 授权**；`DONE` 判据〔授权 + 独立验证〕满足；**`T-126` 依赖解除**） |
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
| T-096 | 为 `PageQuery` 增加「缺省排序方向」重载（向后兼容）+ **稳定排序**（多键 / 唯一 tie-break） | ARCH §4.1.1「分页默认排序方向」, FR-PLAN-4, **FR-PLAN-15 处理规则（分页排序稳定性）** | backend-engineer#java | P0 | 无 | **3 人时** | 新增（架构 §4.1.1 约定落点；**本轮追加「稳定排序」要求**，来源 = `T-103` 交付披露 (b)：分页后 `ORDER BY` 仅单列、无唯一 tie-break） | **DONE（授权 + 独立验证）**〔第 90 轮 **状态前缀订正**：原前缀「已完成，待独立复核」为**陈旧值**—— 本单实经 `te-verify-t096` 独立复核**通过**（0 阻断缺陷、6 项观察）并于第 48 轮置 `DONE`（依据见 `BOARD.md` `DONE` 区）；本次仅订正 §1 前缀、下方交付叙事**一字未动**〕（2026-09-22 交付**主件 + 追加授权**；**自报** `mvn -B -o clean test` **464/0/0/0**、`@Test` grep **464** == surefire **464**、`arch-check` 聚合 **`rc 0`**（`E1`–`E7` 全 `0`）；**(a)** 装配层 `orderByEnsuringUnique(<PK>)`（PK 走 `idColumn()`、**未写死 `id`**；逐行 `" ORDER BY "` 装配点实测**恰 2 处**）⇒ 默认路径真实 SQL = `exec_deadline ASC, id ASC`、两页并集 = 记录数；**(b)** 多键**逐键**白名单（归属判定 = **本批引入** ⇒ 本批闭环，依据 `git show 772bd27:…PageQuery.java` 为单键实现）；**(c)** 3 处「锁缺陷形状」的既有断言已反转并加注（`DmTradePlanDaoScopedSqlTest:80` 实测为 `contains` ⇒ 仍绿、无须订正）；**反退化**：还原 `orderBy()` ⇒ 默认路径两判据红、白名单退化 ⇒ 3 用例各红；**孤立副本内变异、共享树残留 0**；冻结点 `find java/src -type f \| LC_ALL=C sort \| xargs md5sum \| **DONE（授权 + 独立验证）**〔第 47 轮〕（独立复核 `te-verify-t096` = **通过、0 阻断缺陷**；**编排者 2026-09-22 授权**。**验收证据（复核者独立实测）**：`mvn -B -o clean test` **464/0/0/0** + `BUILD SUCCESS`；`@Test` grep **464** == surefire **464**（裸 grep 与「行首注解」两法各 464）；`arch-check` 聚合 **`rc 0`**（`通过(0)=7/命中(0)=0/用法或前置(2)=0`）；`E5` 单跑 **`rc 0`**；冻结点 `java/src` = `d71105d257ca82a297f0450ca470ea46` / **320 文件**（3 次取样一致）；共享树**变异残留 0**；**承重判据全部独立成立**（默认路径全序 / 逐键白名单 / 反退化必红 / 归属判定「多键本批引入」经 git + 快照双重独立证据）。**6 项观察（0 阻断）**：`O-1`（`DmTradePlanDaoScopedSqlTest` 子串断言对 `ORDER BY` 形状**失去守卫能力**，产出方已披露）⇒ **维持现状**（形状守卫由 `DmTradePlanStableOrderTest` 承接）；`O-2`（**同类未披露**：`DmProductDaoScopedSqlTest:79`/`:148` 亦为子串断言，且**产品侧无**等价稳定排序守卫 —— 编排者已实测确认）与 `O-4`（`orderByEnsuringUnique` 的「已含唯一列」判定**大小写敏感**，非白名单路径传大写 `ID` 会产冗余键）⇒ **`T-125`**；`O-3`（旧两例「反转」无法经 git 独立复现）/ `O-5`（副本缺 `cpp/`、`web/` 致 `E1`–`E4`/`E6` SKIP，共享树实测全 0）/ `O-6`（「前一交付」措辞无法由 git 单独证实）⇒ **信息级、不另立**。**证据章**：`docs/TEST_RESULT.md` `:3167`（复核证据，纯追加已证：`head -3165` = 基线 `1978bbbe…`）+ `:3292`（**二次独立复算**）+ `:3345`（`BUG-001` 复检记录）。） |
| T-097 | `ApiRoutes` 升级为冻结 `(方法,路径)` 对 + 一致性判据改等值断言 | ARCH §4.1.1「路由声明与判据的目标形态」, FR-PLAN-4 | backend-engineer#java | P0 | 无 | 4 人时 | 新增（架构 §4.1.1 约定落点） | TODO |
| T-098 | 实现 `CROSS_READONLY` 跨账户只读强制点（写操作拒绝 + 留痕） | ARCH §8.2「NFR-SEC-7 服务端强制点」③, FR-AUTH-13, R-AUTH-8 | backend-engineer#java | P0 | 无 | 3 人时 | 新增（架构 §8.2 强制点落点） | DONE（独立复核通过；复核结论 = **通过（无条件）**〔`te-verify-t115`，2026-09-21；原列唯一条件已消解 —— 该测试文件差异属**已登记的树演进/瞬态**，见 `BOARD.md` §5 第 24 轮行与本任务备注〕；实现 = `assertSubAccountWritable` × 各服务写面改向；越权 ⇒ `10004` + `t_unauthorized_attempt` 留痕） |
| T-099 | 处置 `DmUserSubAccountPermDao.scopeOf` 不读 `perm_type`（补分支或弃用指向） | ARCH §8.2 机制订正（v1.5.8）, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java | P0 | 无 | 2 人时 | 新增（架构 §8.2 机制订正落点） | **DONE**（判据 = **授权 + 独立验证**；**交付形态 = (c) 删除**〔非原文 (a)/(b) 择一；**改判已登记**，见 §2 备注〕；**独立复核 `te-verify-t099` = 通过、0 阻断缺陷**；见 §2 备注） |
| T-100 | 装配 `GET /plan` 读取面子账户隔离（列表 + 详情） | ARCH §8.2①③④（NFR-SEC-7 服务端强制点）, FR-PLAN-4 验收4, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java | P0 | T-016 | 4 人时 | 新增（架构 §8.2 主落点编号补登记） | DONE |
| T-101 | 装配 `GET /plan/{id}/trade-plans` 读取面子账户隔离 | ARCH §8.2③（读取面通则）, FR-PLAN-15 验收4, FR-PLAN-14 验收4, R-PLAN-7, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java | P0 | T-100 | 3 人时 | 新增（架构 §8.2③「其余读取面」通则落点；编排者 2026-09-21 裁定立项） | DONE |
| T-102 | 写面**子账户可见性 / 交易权限**强制补齐（`assertSubAccountVisible` + `assertCanTrade` 未接线 + `withdraw` 发起人校验缺失 + `decomposeTradePlans` 写路径读取未 scope） | FR-AUTH-9, FR-AUTH-10, FR-AUTH-13, NFR-SEC-7, R-AUTH-8, R-AUTH-13 | backend-engineer#java | P0 | T-100 | 5 人时 | 新增（`T-100` 专项复核 `EXP-T100-1` 同族发现；编排者 2026-09-21 裁定立项并扩范围；**第 3 轮再扩 ④** = `decomposeTradePlans` 写路径读取未 scope，来源 = `T-101` 交付「未完成/待裁定 ①」披露；`PlanService.java` 行号以实时 `md5sum` 为准） | **DONE**（判据 = **授权 + 独立验证**；复核链 = 首轮 `te-verify-t102`「**通过、0 阻断**」〔缺陷 `TE-T102-001`〕→ `②b` 补修 → **delta 复检「通过、0 缺陷」**；**外依赖**：`assertCanTrade` 的 M2 真强制点 = **`T-046`（`BLOCKED`）**，M1 期 `src/main` 0 调用为**有意**〔接 `decompose` 会误拒 `FR-PLAN-14` 的财务/风控岗〕；见 §2 备注） |
| T-103 | `GET /plan/{id}/trade-plans` 补**分页/筛选/排序**（按 `FR-PLAN-15` **输入列**） | FR-PLAN-15 输入列（`PLAN.md:321`）, ARCH §4.1.1（分页约定）, CONTRACTS §1.1（`PageData` 信封）, NFR-SEC-7 | backend-engineer#java | P0 | T-101 | 4 人时 | 新增（`T-101` 独立复核探索性发现 `EXP-T101-1`；编排者 2026-09-21 裁定立项，项目经理登记） | DONE |
| T-104 | `FR-PLAN-15` 处理规则两项：交易计划**剩余可下单量** + **已达上限标记**（含前端展示） | FR-PLAN-15 处理规则（`PLAN.md:322`；引用时点，**以实时 `md5sum` 为准**）, FR-PLAN-14 验收4 | backend-engineer#java | P0 | T-103 | 2 人时 | 新增（`FR-PLAN-15`「处理规则」两项被 `T-101`/`T-103` **两次**判为「非本任务范围」⇒ 单独立号以免持续掉队；编排者 2026-09-21 裁定立项，项目经理登记） | TODO |
| T-105 | PRODUCT **列表类**读取面口径收口（`GET /product` 补分页 + 默认创建时间倒序 + 子账户隔离；`GET /product/export` 范围与排序对齐） | FR-PRODUCT-5（`PRODUCT.md:143`~`:156`）, FR-PRODUCT-8（`:187`~`:199`）, FR-AUTH-12/13, NFR-SEC-7 | backend-engineer#java2 | P0 | T-100 | 5 人时 | 新增（`tl-triage` 读取面普查 + 编排者 2026-09-21 裁定立项，项目经理登记） | DONE（独立复核通过；见 §2 备注） |
| T-106 | PRODUCT **单对象**读取面隔离（`GET /product/{id}` + `GET /product/quota`） | FR-PRODUCT-6 验收3, FR-PRODUCT-11（`Q-16`）, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java2 | P0 | T-100 | 2 人时 | 新增（`tl-triage` 读取面普查 + 编排者 2026-09-21 裁定立项，项目经理登记） | TODO |
| T-107 | SPOT 读取面口径收口（`GET /spot/exposure` 补分页 + 默认敞口金额/数量倒序 + 隔离；`GET /spot/exposure/{id}` 隔离） | FR-SPOT-15（`SPOT.md:312`~`:325`）验收3, FR-AUTH-13, NFR-SEC-7 | backend-engineer#java2 | P0 | T-100 | 4 人时 | 新增（`tl-triage` 读取面普查 + 编排者 2026-09-21 裁定立项，项目经理登记） | TODO |
| T-108 | 补齐 **AUTH 3 个**未实现 DAO 的实现类（**第 19 轮范围收窄**：CONFIG 侧 4 个已由 `T-109` 交付；`AuthorizationDao` 由 `T-016` 顺带实现，**不在本任务内**）（**2026-09-22 第 51 轮范围再收窄：3 → 1** —— `DmPermissionChangeLogDao` 与 `DmPositionGroupDao` 已由 `T-010` 退回重做顺带交付〔无此二者则 `FR-AUTH-6/7/11/18` 无法交付〕⇒ 本任务**仅剩 `RolePermissionDao` 1 个**；`T-010` 交付登记见其 §2 备注①）| FR-CONFIG-1, FR-AUTH-1/14, NFR-MAINT-3, NFR-COMPAT-5 | backend-engineer#java2 | P0 | T-004 | 8 人时 | 新增（`T-016`/`CR-002` 实施受阻连带发现；编排者 2026-09-21 裁定立项；**2026-09-21 第 19 轮范围收窄**） | TODO |
| T-109 | 补齐「已声明未注册」端点（CONFIG 9 / SPOT 5 / PLAN 1） | 端点自身 FR（`REQUIREMENTS/CONFIG.md`/`SPOT.md`/`PLAN.md`）, ARCH §4.1.1, NFR-MAINT-5 | backend-engineer#java2 | P0 | T-005, T-006, T-007 | 12 人时 | 新增（验收面↔产出面对齐普查 `audit-cov`；编排者 2026-09-21 裁定立项） | DONE（附条件；判据 = **授权 + 独立验证**；**三轮独立复核**〔首轮「有条件通过」7 缺陷 → 复检「有条件通过」→ 增量复核「有条件通过」〕；两处**严重**缺陷 `TE-T109-001`（`FR-SPOT-11/12` 输入契约）与 `TE-T109-002`（`/spot/exposure/export` **越权读取**）**均已真修好并证实伪**；隔离副本 `375/0/0/0` 独立复现；`ApiRoutes.java` 未改〔`3dec526b…`；**以实时 `md5sum` 为准**〕；**唯一附条件 = `TE-T109-005` 验收 1（变更后重算敞口）未达成**，原因为**诚实阻塞**〔`java/src/main` 与 `cpp` 内**无任何敞口推导实现**〕⇒ 由 **`T-111`** 承接，先造引擎再回填验证 1） |
| T-110 | 补齐 SPOT 写面（`T-013` 范围）子账户可见性强制 | `FR-AUTH-13`, `NFR-SEC-7`, `R-SPOT-2`（子账户归属维度；**现货价属录入归属、不加强制**，见备注） | backend-engineer#java2 | P0 | T-109 | 8 人时 | 新增（增量复核 `te-verify-t109c` 探索性新发现 (A)；与 `TE-T109-002` **同族**、原任务表**无承载**） | DONE（判据 = **授权 + 独立验证**；独立复核 `te-verify-t110` 判定**通过**：8 处新增强制**逐一越权实测 `10004`/HTTP 403 且业务库零写入**；分类表经**双向**核〔无「该防没防」、无「误拒合法写入」〕；4/4 自设变异全红、复位全绿；隔离副本 `mvn -B -o clean test` = **`394/0/0/0`** 独立复现〔`2026-09-21T13:00:29+08:00`，clean、无排除；基线 375 ⇒ +19〕；`ApiRoutes.java` 未改〔`3dec526b…`；**以实时 `md5sum` 为准**〕） |
| T-111 | 实现服务端敞口推导引擎并回填 `FR-SPOT-16` 验收 1（变更后重算敞口） | `FR-SPOT-16` 处理规则 / 验收 1, `R-SPOT-4`, `Q-17` | backend-engineer#java2 | P1 | T-109 | 8 人时 | 新增（`T-109` 的 `TE-T109-005`） | TODO |
| T-112 | 写路径子账户可见性强制的全域收口（PRODUCT 写面 + PLAN 写面） | `FR-AUTH-13`, `NFR-SEC-7`；涉及写入口自身 FR = `FR-PRODUCT-12`（`/product/whitelist`）/ `FR-PRODUCT-11`（`/product/quota`）/ `FR-PLAN-6`·`FR-PLAN-7`·`FR-PLAN-8`（`/plan/ratio`）/ `FR-PLAN-1`（`POST /plan`） | backend-engineer#java2 | P0 | T-110 | 8 人时 | 新增（`te-verify-t110` 域外同类复核；四组逐条已证**未强制**且**在线可调用**） | DONE（独立复核通过；复核结论 = `te-verify-t112` **有条件通过**〔锚 `7e316b27…`〕+ 两条件闭环〔① 由 `T-098` 实现关闭；② 建议级已落〕+ **delta 已由 `te-verify-t115` 在现行锚 `1cfc6a3a…` 复验**；`T-115` 系重复立项、已 `REJECTED`） |
| T-113 | 修正 SPOT 现货单据创建在 `subAccountId` 缺省时的错误呈现（现 NPE→500，应明确提示「子账户必填」） | `FR-SPOT-1` 验收 2（`SPOT.md:101` 起；「未选择子账户即提交被拒绝，提示"子账户必填"」）, `R-SPOT-2` | backend-engineer#java2 | P1 | —（**归属 `T-013`**） | 2 人时 | 新增（`te-verify-t110` 域外同类复核新发现；`T-013` 既有 `requireSubAccount(int)` 对 `Integer` 字段自动拆箱 NPE） | TODO |
| T-114 | 补齐 `POST /spot/exposure/recalc`（`recalcForDisplay`）的子账户可见性强制 | `FR-AUTH-13`, `NFR-SEC-7`；`FR-SPOT-15`（敞口端点自身 FR，`SPOT.md:312`~`:325`） | backend-engineer#java2 | P1 | T-110（复用其范式） | 2 人时 | 新增（`T-112` 实施者第 3 步全仓写路径扫描；写路径越权面〔读 + `t_spot_change_log` 留痕写〕，与 `TE-T109-002` 同族） | TODO |
| T-115 | 修正 `CROSS_READONLY` 写面放行（写路径拒绝跨账户只读身份；读面语义不变） | `FR-AUTH-13` 验收3、`NFR-SEC-7`、`docs/DATA_MODEL.md`（`CROSS_READONLY` 只读性**由服务层保证**、写前校验 `perm_type`） | backend-engineer#java | P0 | T-102, T-110, T-112 | 0 人时（原 6，已扣除） | 新增（`te-verify-t112` 独立复核缺陷①（高·真绕过）；系 `T-102`/`T-110` 既有机制、非 `T-112` 引入，但 `T-112` 扩大暴露面） | REJECTED（**重复立项**：与既有 `T-098` 范围逐条重合；成果归 `T-098`；编号留痕、不计人时；2026-09-21） |
| T-116 | PRODUCT 读取面**「需求面 > 实现面」缺口补齐**（`FR-PRODUCT-5` 输出列「子账户」「最近变更时间」+ **`FR-PRODUCT-5` 筛选输入** + `FR-PRODUCT-8` 验收 1「导出与列表筛选一致」+ **`FR-PRODUCT-8` 验收 3「无数据时提示」** + **`FR-PRODUCT-14` 输入列「时间范围 / 变更类型筛选」**） | FR-PRODUCT-5 输出列（`PRODUCT.md:149`）、FR-PRODUCT-8 验收 1（`:198`）、FR-PRODUCT-5 输入列过滤（`:148`）、**FR-PRODUCT-8 输出列「无数据时提示"无可导出数据"」（`:192`）**、**FR-PRODUCT-14 输入列（`:279`）** | backend-engineer#java2 | P1 | T-105 | **5 人时** | 新增（`te-verify-t105` 独立复核「范围外观察」第 1/3 项；编排者 2026-09-21 裁定立项，项目经理登记）；**第 91 轮范围扩**（+`FR-PRODUCT-8` 验收 3 +`FR-PRODUCT-14` 筛选输入；依据 = `te-verify-t018` 观察 `OBS-T018-2`/`-3`；`3 人时 → 5 人时`） | TODO |
| T-117 | 修正 PRODUCT **白名单可见性 over-inclusive**（子查询补 `status='ENABLED'`，已移出品种不再算「可见」） | FR-PRODUCT-5 处理规则（「仅返回有权限的子账户范围内的品种」，`PRODUCT.md:147`）；载体 = `t_sub_product_whitelist`（`m1_product.sql:158`~`:189`，`status ∈ {ENABLED,DISABLED}`、移出 = 置 `DISABLED` 保留历史） | backend-engineer#java2 | P1 | T-105 | 2 人时 | 新增（`te-verify-t105` 独立复核「范围外观察」第 2 项，**判缺陷：可见性过宽**）；编排者 2026-09-21 裁定立项，项目经理登记） | TODO |
| T-118 | 补齐**全局 / 账户级对象写面的功能权限强制点**（`createProduct`/`disableProduct`/`enableProduct`/`putUnit`/`putContract`/`putSpotMapping`/`importProducts` 现**零权限门禁**） | FR-AUTH-13 **处理规则**（「跨子账户权限默认只读……不得执行下单、审批、改配置等操作」）、NFR-SEC-7、R-AUTH-8 | backend-engineer#java2 | P0 | T-119（层③断言器：`assertCanConfigureAccountLevel`；**2026-09-21 由 `T-049` 订正而来**） | 5 人时 | 新增（`tl-quota-authz` 技术席实测「全局对象写面零门禁」类缺口；用户 2026-09-21 裁定「同类单独立号」，项目经理登记） | TODO |
| T-119 | 补齐 `putQuota` **层③**（`ACCOUNT_PRODUCT` / 账户哨兵 `0`）**写面授权门禁**（M1 = 角色近似） | `FR-AUTH-12`（功能权限维度；`AUTH.md` §2）、`FR-AUTH-13`、`NFR-SEC-7`、`R-AUTH-8`；依据 `ARCHITECTURE.md` §8.2③ 新增条目（`v1.5.11`） | backend-engineer#java（断言器 `assertCanConfigureAccountLevel` 落 `AuthService`） | P0 | `CH-012`（需求侧待用户确认）+ **播种前置**（`t_role`/`t_user_role` 须与实现同批） | 5 人时 | 新增（`tl-quota-authz` 技术席层③实测 + 编排者 2026-09-21 裁定立项；**层③断言器原无编号承载**，本号补登记） | TODO |
| T-120 | `E4` 判据接线落地（默认根改三目录集合 `cpp/src/{order_engine,risk_engine,risk_state}` + `arch-check.sh` / `README` 同步） | ARCH §3.0.2 `E4` 脚注④（**诚实披露**）+ `§3.13`（判据脚本聚合入口）, NFR-PERF-1, NFR-MAINT-4/6 | tech-lead | P0 | T-031 | 3 人时 | 新增（`tl-e4-scope` 的 `ARCHITECTURE.md` §3.0.2 `E4` 脚注④ + 独立复核 `te-verify-t031` 第 2 点：「`E4` 恒 `SKIP`」= 判据未生效；判为**独立任务、非 `T-031` 缺陷**） | DONE（判据 = **授权 + 独立验证**；首轮复核「有条件通过」→ 补修 → **delta 复检通过、0 缺陷**；见 §2 备注） |
| T-121 | 落地「`ARCHITECTURE.md` §4.2.8 ↔ `proto/hgcp.proto` 一致性」CI 判据脚本 | `ARCHITECTURE.md` §4.2.8（`CH-013`）, §3.13（判据脚本产出清单）, NFR-MAINT-4/6 | tech-lead | P0 | 无 | 4 人时 | 新增（`te-verify-proto` 独立复核观察 `O-2`：**字段号/枚举值漂移「测试测不出」**（`MsgError.rule_id` 号 4→7 合法但不符 `§4.2.8` ⇒ 14 用例全绿），而字段号**不可回收** ⇒ 须把人工机械化差集固化为**可复跑判据**；编排者 2026-09-22 裁定立项） | **DONE（授权 + 独立验证）**〔第 41 轮〕（独立复核 `te-verify-t121` = **通过、0 阻断缺陷**〔六类判据**逐类**自设反例全红、未解析行 6 形态全退 `2`、`ComponentRole` 白名单**双向**证「只免一例」、锚定口径经**行号位移证伪**、聚合失败传播 M0–M3、接线两侧零改动〕；**编排者 2026-09-22 授权**。**四条建议级观察去向**：`O-1`（自报锚 = 提交态 ≠ live）⇒ **由编排者本批提交承接**；`O-2`（`cpp/CMakeLists.txt:70` / `java/pom.xml:129` 注释仍写「`E1`–`E6` 六判据」）⇒ **`T-123`**；`O-3`（`README.md:273` `E4 = 6 个` 已失真为 `20 个`）/ `O-4`（脚本头 `N5` 把 `option` 写成「不判」、实测退 `2`）⇒ **`tl-t121` 补修**〔条件承接，与 `T-103` 先例同构〕） |
| T-123 | 订正判据计数注释（`cpp/CMakeLists.txt` / `java/pom.xml` 仍写「聚合 **`E1`–`E6`** 六判据脚本」） | `ARCHITECTURE.md` §3.0.2（现为**七大**工程化手段）、§3.13 | backend-engineer#cpp（`cpp/CMakeLists.txt:70`）+ backend-engineer#java（`java/pom.xml:129`）**跨写域** | P2 | 无 | 1 人时 | 新增（`T-121` 独立复核观察 `O-2`：**接线本身正确**、仅注释计数陈旧；**不判 `T-121` 缺陷**，但依本项目「**已知隐患须落任务表**」纪律立号承接，防「后来者重复发现」） | TODO |
| T-124 | `risk_state` **诊断访问器的并发读安全化**（`last_result()` / `stats()` 与发布路径竞争） | `ARCHITECTURE.md` §4.4.3（并发策略）、§8.1.3（零阻塞口径）、`NFR-PERF-1` | backend-engineer#cpp | P2 | `T-034` | 2 人时 | 新增（`T-034` 独立复核 `te-verify-t034` 观察 `O-1`/`O-3`：`last_result()` 跨线程读竞争 **TSAN 证实**（`risk_state.h:63` vs `risk_state.cc:36`）、`stats()` 读 `retired_.size()` 与 `publish()` 的 `push_back` 竞争；**后者产出方未披露**；均属**非热路径诊断访问器**、无 FR 违背 ⇒ 建议级，但依「**已知隐患须落任务表**」立号承接） | **DONE（授权 + 独立验证）**〔第 66 轮〕（独立复核 `te-verify-t124` = **通过 / 0 阻断缺陷**〔4 项信息级观察〕；**编排者 2026-09-22 授权**。**证据章**：`docs/TEST_RESULT.md` **`:4133` 起**（`T124.0`–`T124.11`，**纯追加已证**：`head -4131` = 追加前基线 `36297e76…` → 现 `22c54ba70e314202d32830fae43b6af9`/4327）。详见 §2 备注） |
| T-125 | `T-096` 复核观察收口：① 产品侧 `ORDER BY` 形状守卫补齐（`DmProductDaoScopedSqlTest` 子串断言已失去守卫力，且产品侧无等价稳定排序用例）② `orderByEnsuringUnique` 的「已含唯一列」判定改大小写不敏感 | `FR-PRODUCT-5`（默认按创建时间倒序）、`ARCHITECTURE.md` §4.1.1；依据 `T-096` 独立复核观察 `O-2`/`O-4` | backend-engineer#java | P2 | `T-096` | 2 人时 | 新增（来源 = `T-096` 独立复核 `te-verify-t096` 的观察 `O-2`（同类未披露）与 `O-4`（低）；**均为非阻断**，但依本项目「**已知隐患须落任务表**」纪律立号承接，防「后来者重复发现」；`O-1` 同类已裁定**维持现状**，本号只收 `O-2`/`O-4`） | TODO |
| T-126 | `PermissionEntry` **岗位维度承载补齐**（`has_settle_role` / `is_management_role`）＋ 引擎 `evalPermission` 消费 | `FR-RISK-7`, `R-RISK-7`, `ARCHITECTURE.md` §3.3 / §4.3.2（`0x100E` 要求「风控岗/结算岗」）/ §4.4.1 | backend-engineer#cpp | P1 | `T-035` | 4 人时 | 新增（`T-035` 独立复核 `te-verify-t035` 观察 `EXP-T035-002`：引擎 `PermissionEntry` **无 `has_settle_role`、无 `is_management_role`** ⇒ **仅具结算岗者当前可操盘**，与 §4.3.2 `0x100E` 不符；**复核方明确「不能维持产出方 ⑥『非缺陷』结论」**；属**跨任务承载缺口**（`T-034` 内存模型 / `T-038` 加载侧映射）⇒ 立号承接） | **DONE（授权 + 独立验证）**〔第 73 轮〕（独立复核 `te-verify-t126` = **通过 / 0 阻断缺陷**〔3 项信息/低观察〕；**编排者 2026-09-22 授权**。`risk_state` = `b47c2fd3…`/18、`risk_engine` = `24df98dc…`/29；4 文件 +77/−3；**改前缺陷已复现**〔承载缺口到编译级 + 消费缺口 `仅具结算岗/仅具管理层 → kPass`〕+ **反退化三分支各自承重** + **全量 `ctest` 38/38**（**`ctest` 33/33 为冻结时点口径**：其后 `T-033` 并发向 `ctp_adapter` 增补用例）+ 聚合 `rc 0`；**证据章** `docs/TEST_RESULT.md` `:4329`–`:4502`（**纯追加已证**：`head -4327` = `22c54ba7…` → 现 `8d92ec16…`）。详见 §2 备注） |
| T-127 | `RiskSnapshot::canTrade` **岗位维度判定补齐**（与 `evalPermission` 对齐） | `NFR-SEC-5`, `FR-RISK-7`, `R-RISK-7`, `ARCHITECTURE.md` §4.3.2（`0x100E`/`0x100F`）、`CH-010` 析取 | backend-engineer#cpp | P2 | `T-126`（已交付）、`T-034` | 2 人时 | 新增（**`T-126` 交付时实施方主动披露 + 技术席只读裁定 `tl-risk-ruling` 判 ①「第二处同类缺口」**；**穷举结论 = 生产判定消费点共 2 处、无第三处**） | **DONE（授权 + 独立验证）**〔第 78 轮〕（独立复核 `te-verify-t127` = **通过 / 0 阻断缺陷**〔3 观察〕；**编排者 2026-09-22 授权**。2026-09-22 实例 `be-t127` 交付；4 文件 **+128/−13**；`risk_state` = `3f4e4750…`/18、`risk_engine` = `09b88329…`/29；**三单条件用例（各自显式排除其余三条）+ 快照内对照** ⇒ 证**析取**；**反退化 4 变异**，其中 **M-C/M-D（从「共用纯函数」移除某字段）⇒ 两个消费点同时红** ⇒ 证「共用」名副其实；`ctest` **38/38**（CTP 可用）/ **34/34**（CTP 不可得）、聚合 `rc 0`、`E4` 命中 0；**⚠️ 已显式声明「`T-126` 的 `risk_engine` 冻结点已前移（`24df98dc…` → `09b88329…`）、需 delta 复检」**〔**已派 `te-verify-t126b` 并通过**〕；**证据章** `docs/TEST_RESULT.md` `:4787–:4918`（**纯追加已证**：`head -4787` = `cf912232…`）。**复核要点**：① **改前三条全部承重**（仅 `has_risk_post`/仅 `has_settle_role`/仅 `is_management_role` **均曾放行**）；② 改后各自拒 + **误伤方向已查**；③ **「共用」名副其实**（从共用函数删字段 ⇒ **两消费点同时红**）；④ **既有断言未静默放宽**（7 条逐字未改；**1 条更替系必需** —— 原夹具 `9001` 带 `has_settle_role=true` 却断言可交易，**该断言本身锁定了缺陷形状**，复核方独立佐证）；⑤ **`evalPermission` 改前/改后输出逐字相同** ⇒ 重构语义中性；⑥ `ctest` 38/38 / 34/34、聚合 `rc 0`、`E4` 三口径 `rc 0`。**完整记录见 `docs/BOARD.md` 第 78 轮行**） |
| T-128 | 统一 **CTP 头名变量源**（`ctp_smoke` 的 `HG_CTP_HEADER_STYLE` ↔ 主工程 `HG_CTP_TRADER_HEADER` 不同源） | `ARCHITECTURE.md` §7.2.1/§3.1、`NFR-MAINT-3` | backend-engineer#cpp | P2 | `T-033`（已交付）、`T-027` | 1 人时 | 新增（**`T-033`/`T-027` 独立复核观察 `OBS-T033b-3`**：两处头名变量**不同源** ⇒ 某些构造下**可达不同 `CTP_FOUND`**；前态复现时探路件因默认 `CLASSIC` **仍注册 `rsk02`**；**无 `FR` 冲突** ⇒ 低，但**探针结论可能与产品构建不一致** ⇒ 立号承接） | **DONE（授权 + 独立验证）**〔第 81 轮〕（2026-09-22 实例 `be-t128` 交付；**独立复核 `te-verify-t128` = 有条件通过 / 0 阻断缺陷**〔4 观察〕，**编排者 2026-09-23 授权**；复核要点：**交付域锚 `654cf2f2…`/9 独立吻合**、**改前反例复现**、**判据非空转**、**旧入口移除判不属契约变更（免 `CH`）**、**`T-027` 判据口径未变（`PASS=12/FAIL=0`）仅制品 md5 前移 ⇒ 重登记非重跑**；**4 观察**：`OBS-T128-1`（**中**）判据**全仓零调用点**且 `cpp/ctp_smoke/` **无任何 README**（外围件三缺）／`-2` 退码头注漏 `rc=2` 且**存在「该红而退 3」盲区**（探路件侧分歧）／`-3` 详见 §2；**新增 `cpp/cmake/HgCtpStyle.cmake` = 唯一入口 + 派生 + 旧入口防退化守卫**；`ctp_smoke`/主工程**均改由该单一来源派生**；**新增判据脚本 `cpp/ctp_smoke/ci/check_ctp_found_parity.sh`**；**穷举后确认同概念仅 2 个「缓存入口」**（`HG_CTP_TRADER_HEADER`/`HG_CTP_MD_HEADER` 主工程 + `HG_CTP_HEADER_STYLE` 探路件），`HGCP_*` 系**派生输出宏**（非入口）；**★判据双向含反例**（改前同一配置下探路件 ON／主工程 FATAL **互相矛盾**；改后一致；**2 变异均使判据 rc=1** ⇒ 不空转）；三轨 + 老式单目录 + 探路件 standalone 均 **0 告警**、`ctest` 38/38 · 34/34、聚合 `rc 0`；**⚠️ 两项存量缺陷（均非本单引入）**：① `fetch` 轨 `rc=2`（经 `hgcp.pb.h` 引入 protobuf 源码头）⇒ **已立 `T-129`**；② 主工程 `cpp/src/ctp_adapter/**` **硬编码分离式头名**（`module.cc:7`/`ct_gateway_ctp.cc:22-23`/`ct_spi_impl.h:27-28` + 2 测试）⇒ **`MERGED` 名义上仅探路件可用**（`CH-015` 基线 = 官方分离式 ⇒ 不阻断，但属「已声明而主工程不可用」的潜伏陷阱）；**交付域冻结点 `654cf2f2…`/9（`-not -path '*/build*'` 口径）**；详见 §2 备注与 `docs/BOARD.md` §5 第 80 轮） |
| T-129 | 修复 **`fetch` 轨构建失败**（`hgcp` 经 `hgcp.pb.h` 引入 protobuf **源码头**，`-Werror=unused-parameter`） | `NFR-COMPAT-1`, `ARCHITECTURE.md` §7.2.1（依赖清单） | backend-engineer#cpp | P1 | 无（`T-026` 相关，`fetch` 轨本身） | 2 人时 | 新增（**`T-128` 交付时发现的存量缺陷**：`cpp/src/hgcp/src/payload_codec.cc` 经 `hgcp.pb.h` 引入 **protobuf 源码头** ⇒ `port.h:46/53 -Werror=unused-parameter`；`fetch` 轨（`CTP_USE_FETCH_DEPS=ON`）**构建 `rc=2`**；**改前 prefix 端到端同点同错** ⇒ 与本单无关 ⇒ 立号承接） | **DONE（授权 + 独立验证）**〔第 84 轮〕（2026-09-23 实例 `be-t129` 交付；**独立复核 `te-verify-t129` = 有条件通过 / 0 阻断缺陷**（4 观察）；**编排者 2026-09-23 授权**；复核方**逐项复测全部吻合**（`+30/−0`／`c1f5aa68…`／`cpp/src 327118d8…`/115 零改动／`cpp/cmake bfe01dd4…`/3／交付域 `82bd2245…`/9／三轨 `38/38·34/34·38/38`／`arch-check rc0`／`E4 47/0/WARN3`），**`-Werror` 未动**（改动文件 0 处 `-Wno-`）；**关键裁定**：根因成立且**无从回避**（连 protoc 生成码 `hgcp.pb.cc:8` 亦必含 `coded_stream.h` ⇒ 收窄自家包含面无用），**「处置过宽」判建议级、非缺陷**（`hg_deps::protobuf` **唯一消费方 = `hg_hgcp`** ⇒ 两种 SYSTEM 形态**作用面相同**，且**未能构造出「掩盖误用」的反例**）；**自设 2 变异全杀**（`SYSTEM TRUE→FALSE` ⇒ `rc=2`；移错目标 ⇒ 仍 `-I`/`rc=2`）+ `CAND-N` 真机跑通 fallback 形态；**唯一改动 = `cpp/cmake/HgDependencies.cmake`（+30/−0）** ⇒ `c1f5aa68…`；`cpp/src/**` **零改动**（`327118d8…`/115 未变）；**根因 = protobuf 头经非 IMPORTED 目标的 `INTERFACE_INCLUDE_DIRECTORIES` 以 `-I` 传播给消费方 ⇒ 本工程 `-Werror` 施加到第三方头** ⇒ 标 SYSTEM（与 CTP SDK 头既有处置同族），**未放宽任何 `-Werror`**；`fetch` 轨 **rc=0 / 0 告警**、三轨 `ctest` **38/38 · 34/34 · 38/38**、聚合 `arch-check rc 0`、`E4` 47 文件/0 命中/3 WARN（未恶化）；**反退化**：revert ⇒ `rc=2` 同点同错、restore ⇒ `rc=0`；交付域锚 `82bd2245…`/9；**⚠️ 已按 `CH-015` 交付基线重配 `cpp/build-*`（原 Mini+MERGED ⇒ 官方分离式+CLASSIC）** ⇒ 连带 `tools/arch-check/README.md:299` 陈旧值待回写（并入 `T-130`）） |
| T-130 | `check_ctp_found_parity.sh` **接线 + 外围件补齐 + 诊断质量收口** | `ARCHITECTURE.md` §3.13（判据脚本产出清单）、§7.2.1、`NFR-MAINT-4/6` | backend-engineer#cpp | P1 | `T-128`（已交付） | 3 人时 | 新增（**`T-128` 独立复核 `te-verify-t128` 观察 `OBS-T128-1`〔中：判据**全仓零调用点** ⇒ **永不生效**；`cpp/ctp_smoke/` **无任何 README** ⇒ 外围件三缺〕/ `OBS-T128-2`〔退码头注漏 `rc=2`；且**存在「该红而退 3」盲区**〕/ `OBS-T128-4`〔`MERGED` 失败未点名风格〕** —— 依「**判据类交付须连核外围件**（①聚合入口 ②接线点 ③README/自测记录）」与「**已知隐患须落任务表**」纪律立号承接） | **DONE（授权 + 独立验证）**〔第 87 轮〕（2026-09-23 实例 `be-t130` 交付；**独立复核 `te-verify-t130` = 有条件通过 / 0 阻断缺陷**（6 观察、「缺陷单 0」）、**七档判据全过**；**编排者 2026-09-23 授权**；复核要点：接线**三轨均注册且实跑 Passed**（含**无 SDK 的 `stub` 轨**）、**自设变异** M5（摘接线 ⇒ `ctest -N` **39→38、用例消失**）/M6a·M6b（写死 `--style`/`--expect` ⇒ FAILED ⇒ 参数写死会被抓）/M1–M4（**同一输入旧脚本 `rc=3` / 新脚本 `rc=1`**；M4 把 `rc=3` 条件接进 `ctest` ⇒ **报 Failed** ⇒ 不静默通过）；`:299` 历史块 `git diff --numstat` = `38 0` **逐字节未变**；`SYSTEM` 形态 `:273` **唯一路径**、`hg_deps::protobuf` **唯一消费方 = `hg_hgcp`** ⇒「作用面等价」成立；三轨 `ctest` **`ctp 39/39` · `stub 35/35` · `fetch 39/39`**、聚合 `arch-check rc0`、`E4 47/0/WARN3`；**6 观察均不阻断**（见 §2）；**4 改 + 1 新**：`cpp/CMakeLists.txt` `3431e7c4…`（新增 ctest `ctp_found_parity`，注册于 `:99`）／`cpp/cmake/HgDependencies.cmake` `2baf596f…`（**MERGED 显式 FATAL 并点名风格** + 统一 SYSTEM 形态）／`cpp/ctp_smoke/ci/check_ctp_found_parity.sh` `6bc8fab2…`（头注补 `rc=2`、**消除「该红而退 3」盲区**）／`tools/arch-check/README.md` `e8a68ae9…`（新增 §6 官方基线重测 + 指针行）／**新增** `cpp/ctp_smoke/README.md` `dc6adb94…`；**接线后 `ctest -N` = `ctp 39` · `stub 35` · `fetch 39`**（+1 = 新注册用例，三轨实跑 `ctp_found_parity` Passed）；`cpp/src` **零改动**（`327118d8…`/115）；交付域锚 **`288b3b33…`/10**（9→10，+README）） |
| T-131 | 补齐 **PRODUCT 写面 REST 端点**（品种修改 / 停用 / 启用 / 计量单位；**服务层已实现、端点未注册**） | `FR-PRODUCT-2/3/4/13`、`FR-PRODUCT-1~14` 追溯面、`ARCHITECTURE.md` §4.1.1 | backend-engineer#java2 | P1 | `T-012`（已交付）、`T-105` | 4 人时 | 新增（**`T-018` 交付时由实施方主动披露**：`VarietyService` **已有** `createProduct:242` / `disableProduct:283` / `enableProduct:338` / `putUnit:369`，但 `ProductRoutes.java:36-52` **仅注册** `GET\|POST /product`、`GET /product/{id}`、`POST /product/whitelist`、`GET\|POST /product/quota`、`GET\|POST /product/{id}/contracts`、`GET\|POST /product/mapping`、`GET /product/change-log`、`POST /product/import`、`GET /product/export` ⇒ **无 `PUT /product/{id}`、无停用/启用动作、无计量单位端点** ⇒ 前端**无法实现**修改/停用/启用/计量单位（`FR-PRODUCT-2/3/4/13`）。**属「已实现未接线」**（同族：`T-109` 已声明未注册；`T-128` 头名不同源） | TODO |
| T-132 | 数值字段**「字符串化十进制」契约收口**（`GET /product/quota` 以 JSON `number` 输出 `BigDecimal`）+ **穷举同类点** | `ARCHITECTURE.md` §4.1.1（`:625`「金额 / 数量 → **字符串化十进制**（避免 JS `Number` 精度丢失）」）、`:917`、`:1085`、`:1282`（`G-2` 额度字段口径 = **`string`（十进制）**，与 `DATA_MODEL` `DECIMAL(20,4)` 交叉核对） | backend-engineer#java2 | P1 | `T-105` | 3 人时 | 新增（**`T-018` 交付时由实施方披露 + 编排者独立复核**：`GET /product/quota` 的 `quotaPanorama` 直出实体，`BigDecimal`（`quotaLimit`/`usedQty`/`frozenQty`）经 Gson 序列化为 **JSON number** ⇒ **违反 §4.1.1 `:625` 与 `G-2`**。**⚠️ 同类扫描面未穷举** ⇒ 本任务**须全仓穷举**「以 JSON `number` 输出 `BigDecimal`」的响应点并给清单（含不适用项） | TODO |
| T-122 | 落地「**冻结不变量**（消息 `40` / 错误码 `62` / 线格式 `VER 0x0100` / `FR 184`）」CI 判据脚本 | `ARCHITECTURE.md` §3.0.2（判据清单）、§3.13（判据脚本产出清单）、`NFR-MAINT-4/6`、`NFR-COMPAT-5` | tech-lead | P0 | 无（**`FR` 项口径已由编排者 2026-09-22 裁定 ⇒ 前置解除**） | 4 人时 | 新增（`te-verify-proto2` 建议「`40/62/VER/FR` 等**不变量**宜与 `§4.2.8` schema 一致性**分开**另立判据、避免耦合」，经 `tl-t121` 以 `U-3` 上报；编排者 2026-09-22 受理立项） | TODO |

**合计：132 个任务**（M1 35 / M2 68 / M3 16 / M4 13）——**132 = 130 + `T-131`（1）+ `T-132`（1）〔第 89 轮新立〕；其中 130 = 129 + `T-130`（1）〔第 81 轮新立〕；其中 129 = 128 + `T-129`（1）〔第 80 轮新立〕；其中 128 = 127 + `T-128`（1）〔第 74 轮新立〕；其中 127 = 126 + `T-127`（1）〔第 70 轮新立〕；其中 126 = 125 + `T-126`（1）〔第 52 轮新立〕；其中 125 = 124 + `T-125`（1）〔第 47 轮新立〕；其中 124 = 123 + `T-124`（1）〔第 42 轮新立〕；其中 123 = 122 + `T-123`（1）〔第 41 轮新立〕；其中 122 = 121 + `T-122`（1）〔第 41 轮新立〕；其中 121 = 120 + `T-121`（1）〔第 39 轮新立、**第 41 轮补登记入各分区**〕；其中 120 = 119 + `T-120`（1）〔第 31 轮新立〕；其中 119 = 118 + `T-119`（1）〔第 29 轮新立〕；其中 118 = 115 + `T-116`（1）+ `T-117`（1）+ `T-118`（1）〔第 28 轮新立〕；其中 115 = 114 + `T-115`（1） = 113 + `T-114`（1） = 111 + `T-112`（1）+ `T-113`（1） = 109 + `T-110`（1）+ `T-111`（1） = 104 + `T-105`（1）+ `T-106`（1）+ `T-107`（1）+ `T-108`（1）+ `T-109`（1） = 89 + `T-090`~`T-093`（4）+ `T-094`（1）+ `T-095`（1）+ `T-096`~`T-099`（4）+ `T-100`（1）+ `T-101`（1）+ `T-102`（1）+ `T-103`（1）+ `T-104`（1）+ `T-109`（1）**（`T-109` = 「已声明未注册」端点补齐（CONFIG 9 / SPOT 5 / PLAN 1），`#java2`，**M1 窗口**，依赖 `T-005`/`T-006`/`T-007`，来源 = 对齐普查 `audit-cov` + 编排者 2026-09-21 裁定；`T-090`~`T-093` = 架构 §3.0.2 判据 `E1`/`E3`/`E4`/`E6` 脚本落点，M2 首周批；**`T-094` = `T-061` 范围收窄后承接剩余 10 张 RISK 表，改归 M3 窗口**；**`T-095` = `E1`~`E6` 六脚本形态 / 退出码语义一致性收口（原 `E2` 按 `E5` 同法收紧，第 10 轮扩范围；兼收 `TE-AC-002`/`TE-AC-003`/`TE-AC-004`），M2 窗口**；**`T-096`~`T-099` = `ARCHITECTURE.md`（登记时点 `v1.5.8`；**以实时 `md5sum` 为准**）§4.1.1 两项约定 + §8.2 强制点／机制订正的落点，负责人 `#java`，M2 窗口，编号由技术负责人建议、项目经理登记方为事实**；**`T-100` = 架构 §8.2①③④ **主落点**（`GET /plan` 读取面隔离装配）的**编号补登记**，依赖 `T-016`，`#java`，M2 窗口，编排者 2026-09-21 裁定立项**；**`T-101` = `GET /plan/{id}/trade-plans` 读取面**子账户隔离装配**（`T-100` 的**相邻残余缺口** —— 同性质缺陷换端点仍复现；动 `PlanService.listTradePlans` + `TradePlanDao`/`DmTradePlanDao` + `TradePlanHandler`，**写域与 `T-100` 不同**，故**单独立号**），依赖 `T-100`，`#java`，M2 窗口，编排者 2026-09-21 裁定立项**；**`T-102` = 写面**子账户可见性强制**补齐（`T-100` 专项复核 `EXP-T100-1` 的同族发现：`AuthService.assertSubAccountVisible` **与** `AuthService.assertCanTrade` **均「已实现但生产 0 处调用」** + `PlanService.withdraw` **javadoc 承诺与实现不符** + **第 3 轮再扩 ④** `PlanService.decomposeTradePlans`（`:786`）**写路径读取未 scope**，来源 = `T-101` 交付披露），依赖 `T-100`，`#java`，M2 窗口，编排者 2026-09-21 裁定立项（**范围含 `assertCanTrade`**）**；**`T-103` = `GET /plan/{id}/trade-plans` **补分页/筛选/排序**（按 `FR-PLAN-15` **输入列**，`PLAN.md:321`；`FR-PLAN-15` 自身优先级 = **P0**〔`PLAN.md:324`〕；依赖 `T-101`，`#java`，M2 窗口；**不需 `CH-xxx`** —— `CONTRACTS §1.1` 已冻结「分页接口用 `PageData` 信封」，本项属**向既有冻结口径对齐**、系**修正违规**非变更契约）**；**`T-104` = `FR-PLAN-15` **处理规则**两项（**剩余可下单量** = 数量 − 已下达指令量；**已达可下单量上限标记**）的独立承载 —— 该项被 `T-101`/`T-103` **两次**判为「非本任务范围」⇒ 单独立号以免持续掉队；依赖 `T-103`，`#java`，M2 窗口**）；`T-029` 保留编号、状态 `REJECTED`，**不计入人时**；**总估时 1833 人时**（**现行值**；以下为历轮累加叙述、**不再逐笔维护**，增量以 §5 角色行实时明细为准）= **1795**（第 39 轮时点）+ `T-122` **4** + `T-123` **1** + `T-124` **2** + `T-125` **2** + `T-126` **4** = **+13**〔第 41 / 42 / 47 / 52 轮〕+ **8**（`T-027` +3 / `T-033` +5，第 61 轮：**官方 CTP 基线变更**的重做与移植）+ **2**（`T-127`，第 70 轮：`canTrade` 岗位维度补齐）+ **1**（`T-128`，第 74 轮：统一 CTP 头名变量源）+ **2**（`T-129`，第 80 轮：修 `fetch` 轨构建失败）+ **3**（`T-130`，第 81 轮：判据接线＋外围件＋诊断质量）+ **4**（`T-131`，第 89 轮：PRODUCT 写面端点）+ **3**（`T-132`，第 89 轮：字符串化十进制收口）+ **2**（`T-116`，第 91 轮：**范围扩 3 → 5 人时**，并入 `FR-PRODUCT-8` 验收 3「无数据时提示」+ `FR-PRODUCT-14` 变更日志筛选输入；依据 = `te-verify-t018` 观察 `OBS-T018-2`/`-3`）；其中 1795 = 1788 + `T-120` **3 人时** = +3〔第 31 轮〕；= 1779 − `T-115` **6 人时** = −6〔第 25 轮：`T-115` 判 `REJECTED`、不计人时〕+ `T-116` **3 人时** + `T-117` **2 人时** + `T-118` **5 人时** = +10〔第 28 轮〕+ `T-119` **5 人时** = +5〔第 29 轮〕；= 1773 + `T-115` **6 人时** = +6〔第 24 轮〕；= 1771 + `T-114` **2 人时** = +2〔第 23 轮〕；= 1761 + `T-112` **8 人时** + `T-113` **2 人时** = +10；= 1714 + `T-105` **5 人时** + `T-106` **2 人时** + `T-107` **4 人时** + `T-108` **8 人时** + `T-109` **12 人时** + `T-110` **8 人时** + `T-111` **8 人时** = +47；= 1691 + `T-100` **4 人时** + `T-101` **3 人时** + `T-102` **5 人时** + `T-103` **4 人时** + `T-104` **2 人时** + `T-096` 上修 **1 人时** + `T-095` 上修 **4 人时**）——`T-061` 由 20 → **2 人时**（首增量单表）、`T-094` **+18 人时**（RISK DDL 总量守恒 20 人时）、`T-095` **+4 人时**、`T-096`~`T-099` **+2 +4 +3 +2 = +11 人时**、`T-100` **+4 人时**、`T-101` **+3 人时**、`T-102` **+5 人时**（`+4` → `+5`，第 3 轮再扩 ④ 后上修）、`T-103` **+4 人时**（第 4 轮新增，`EXP-T101-1`）、`T-104` **+2 人时**（本轮新增，`FR-PLAN-15` 处理规则两项，`#java`，M2）、`T-096` **+1 人时**（本轮上修 **2 → 3 人时**，追加「稳定排序」能力；依据见 §2 `T-096` 备注）、**`T-095` 第 10 轮再上修 4 → 8 人时（+4）**（扩为 `E1`~`E6` 六脚本形态/退出码语义一致性收口，兼收 `TE-AC-002`/`TE-AC-003`/`TE-AC-004`；**总估时 1710 → 1714**）。 **第 11 轮**：新增 **`T-105`/`T-106`/`T-107`**（`#java2`，M2 窗口，**+5 +2 +4 = +11 人时**）—— PRODUCT/SPOT **读取面口径收口**（`tl-triage` 全模块只读普查 + 编排者 2026-09-21 裁定立项）；**总估时 1714 → 1725 → 1733**。 **第 15 轮**：新增 **`T-108`**（补齐 CONFIG+AUTH **7 个**未实现 DAO 的实现类，`#java2`，M2 窗口，**+8 人时**；**第 19 轮范围收窄为 AUTH 3 个**，CONFIG 侧 4 个由 `T-109` 交付）；**任务数 107 → 108**、**总估时 1725 → 1733**。 **第 16 轮**：新增 **`T-109`**（补齐「已声明未注册」端点 **CONFIG 9 / SPOT 5 / PLAN 1**，`#java2`，**M1 窗口**，**+12 人时**）；**任务数 108 → 109**、**总估时 1733 → 1745**；**第 21 轮新增 `T-110`/`T-111`**（各 8 人时，`#java2`；`T-110` = SPOT 写面（`T-013` 范围）子账户可见性强制〔M1 窗口〕、`T-111` = 服务端敞口推导引擎 + `FR-SPOT-16` 验收 1 回填〔M2 窗口〕）**；任务数 109 → 111**、**总估时 1745 → 1761**。 **第 22 轮**：新增 **`T-112`**（写路径子账户可见性强制的全域收口〔PRODUCT 写面 + PLAN 写面〕，`#java2`，M1 窗口，**+8 人时**，**依赖 `T-110`**；来源 = `te-verify-t110` 域外同类复核）与 **`T-113`**（修正 SPOT 现货单据创建 `subAccountId` 缺省时的错误呈现〔NPE→500 ⇒ 应「子账户必填」〕，`#java2`，M1 窗口，**+2 人时**，归属 `T-013`）；**`T-110` 由 `TODO` → `DONE`**（独立复核 `te-verify-t110` 判定**通过**）；**任务数 111 → 113**、**总估时 1761 → 1771**。 **第 23 轮**：新增 **`T-114`**（补齐 `POST /spot/exposure/recalc`（`recalcForDisplay`）子账户可见性强制，`#java2`，M1 窗口，**+2 人时**，**依赖 `T-110`**；来源 = `T-112` 实施者第 3 步全仓写路径扫描）；**`T-112` 由 `TODO` → `IN_PROGRESS`**（2026-09-21 派发实施中，实施者 `backend-engineer#java2` / 实例 `be-t112`）；**任务数 113 → 114**、**总估时 1771 → 1773**。 **第 24 轮**：新增 **`T-115`**（修正 `CROSS_READONLY` **写面**放行〔写路径拒绝跨账户只读身份；读面语义不变〕，`#java`，M1 窗口，**+6 人时**，**依赖 `T-102`/`T-110`/`T-112`**；来源 = `te-verify-t112` 独立复核缺陷①〔高·真绕过〕）；**`T-112` 保持 `IN_PROGRESS`（第 2 轮：复核后补修）**；**任务数 114 → 115**、**总估时 1773 → 1779**。 **第 25 轮**：**`T-115` 判 `REJECTED`**（**重复立项** —— 与既有 `T-098` 范围逐条重合：同为「命中 `perm_type='CROSS_READONLY'` 的写操作**一律拒绝（服务端强制）**」、同为错误码 `10004`（403）、同为留痕 `t_unauthorized_attempt`（`R-AUTH-8`）、同为正/反双向测试要求；**成果归 `T-098`**；编号留痕、**不计人时**，依 `T-029` 先例；**动因** = 编排者立号时未扫整张既有任务表）；**`T-098` / `T-112` 转「已完成，待独立复核」**（纯状态口径、人时不变）；**任务数 115 不变**、**总估时 1779 → 1773**。

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

  **📌（2026-09-22 第 48 轮派发登记 · 只加不改）** **`T-010` 退回重做再派发**（实例 `be-t010`，唯一写者；**Java 侧**，产出面 `service/AuthService` + REST `/auth/**` + `web/` 的 `SessionAuthFilter`，**并唯一授权改 `docs/DEPLOY/schema/data-init.sql`**〔验收标准 5 要求播种；只追加/幂等，勿重排〕）。**派单要点**：四项硬缺口 `(a)` REST 8 端点未注册（声明 10 / 实注 2）⇒ 须补齐并**同步订正路由计数断言**（**理由只能是需求**，须加注 + 列前后值）；`(b)` `FR-AUTH-1~18` 覆盖（已 2 / 部分 5 / 未 11）须补到「已交付」并逐条给实现位置；`(c)` `FR-AUTH-17④` 阈值/时长接 `CONFIG`、`FR-AUTH-15②` 须实现**空闲**超时（`SessionAuthFilter` 调 `touchSession` + `SessionStore` 不再按绝对 `expireTime`）；`(d)` AUTH 预设数据按 `data-init.sql` §② **诚实披露表**（AUTH = 3 项）**幂等播种** + 测试断言行数/关键字段。⚠️ **头号陷阱**：验收标准 3 的「负责人禁操盘」判据 = **析取（`CH-010`）**，**不得实现为合取**；须给「仅①」「仅②」**各自独立**的拒单用例。
  **📌（2026-09-22 `T-010` 退回重做交付登记 · 只加不改）** 四项硬缺口闭环（详见 §1 状态栏）；**4 项未决 + 编排者裁定**：**① `T-108` 范围重叠（2/3）** —— 本单新建了 `DmPermissionChangeLogDao` / `DmPositionGroupDao`（无此二者则 `FR-AUTH-6/7/11/18` 无法交付）⇒ **编排者裁定：`T-108` 收窄为 `RolePermissionDao` 1 个**（原「AUTH 3 个」；须在 `T-108` 条目回写，见下轮台账）。**② 无 DM8 实机** —— 播种/DAO **未在真机执行**；`INSERT…SELECT…WHERE NOT EXISTS` 的 **DM8 语法未经实机验证** ⇒ 属**环境限制**（沿用项目既有「替代证据法」），**须在交付报告与复核中据实标注**，不得表述为「已在 DM8 验证」。**③ 岗位组编码口径（7 组；`group_code` 与角色编码同名；`front_mid_back` 取 5 类归并）系**实施方自定** ⇒ 编排者裁定：属**实现侧假定**，**在取得产品席/架构席书面确认前，不得作为权威口径**；`T-010` 转 `DONE` 前须闭环该项（已登记为待确认项）。**④ 3 处常量修正（`MANAGEMENT_TRADE_BLOCKED→LEADER_TRADE`、`SUB_ACCOUNT_DENIED→PERMISSION_DENIED`、`PERMISSION_CHANGED→KICKED`）** —— 实施方**未获显式授权**、主动报请核准；**编排者裁定：批准**（理由：原值**落在 DDL `CHECK` 域之外**⇒ 实例不可用；属「实例可用性」修复、且与 `T-010` 自身特性同源），**须留痕并列入交付清单**。**⑤ 断言订正纪律（核过，合规）**：`BootstrapAssemblyTest:136` 计数 `67→82` **已加注「理由=需求面缺口补齐」**、非为过测试放宽；`T109EndpointRegistrationTest:145-163` 的反向对照改为直连分发（理由：`T-010` 起 Bootstrap 认证**会写库**，假驱动下 500 属**环境假象**、非路由问题）⇒ 亦已加注。

  **📌（2026-09-22 `T-010` 复核结论与退回小修登记 · 只加不改）** 复核者 `te-verify-t010b`（隔离副本 `/hgt010iso`，未采信产出方）判 **有条件通过**；**四硬缺口全过**（详见 §1 状态栏）。**唯一缺陷 `TE-T010b-001`（低·守卫空转·非阻断）**：`T109EndpointRegistrationTest` **段②** 经装配分发器（含续期器）在假驱动下**一律 500** ⇒ `problems.isEmpty()` **恒真**、**已不守卫注册**；产出方改「反向对照为直连分发」的**理由成立**，但**未察觉主断言被架空**。**编排者裁定**：本项目已在《断言的假红/假绿》纪律中确立 —— **「仍绿 ≠ 仍被守卫」**（守卫强度事实须报出并另立精确断言）⇒ **必须修，不得仅记观察**。**派 `be-t010fix`**：二选一 —— **(i)** 段② 改走**直连分发**（同产出方已改的反向对照，假驱动下不触写库 ⇒ 恢复 404/500 区分力）；或 **(ii)** 段② 改**断言 `routeCount`**（直接用装配结果计数判注册完整性）；**无论选哪个，须给双向守卫强度反转证据**：摘掉某已注册端点 ⇒ **新断言必红**、恢复 ⇒ 绿（**若新断言在此变异下仍绿 = 换了个空转的守卫，须重做**）；须**加注**说明原空转成因；**不得**改成「只查长度/非空/不抛异常」等恒真形态。**另 2 项观察（非缺陷，勿改）**：① `assertCanTrade` 在 `src/main` **0 调用** 已有生效裁定（`T-102`：接线点 = M2 下单路径 `T-046`、**M1 不接线** —— 接 `decompose` 会误拒 `FR-PLAN-14` 的财务/风控岗）⇒ **有意、非缺陷**；② `forceLogout`（`FR-AUTH-15④`）`src/main` 0 调用且无 REST 端点 ⇒ 复核方列**观察**；**已请实施席只报「该 FR 原文是否要求 REST 端点」**，若是 ⇒ 属需求面/实现面缺口、**由编排者另立号**，实施席**不得自行扩面**。

  **📌（2026-09-22 `TE-T010b-001` 修复交付登记 · 只加不改）** 详见 §1 状态栏。**要点**：只改测试（`src/main` 零改动）、方案 (i) 直连分发、**双向证据齐**（先证原段② 空转 ⇒ 再证新段② 必红 ⇒ 复位回绿）；冻结点 `8bf0e1d6c2e83a52e62d511616324c4b`/**335 文件**；修复证据章纯追加（`04a87a2d…`/3817 → `1243a62b…`/3863）。**独立 delta 复检 `te-rec-t010` 进行中**，复检面含：① **原守卫确实为空的复现**（若复不出须明说）② **新守卫双向会咬**（变异下必红、复位必绿；**仍绿即计缺陷**）③ 锚「行为」而非实现细节（含「无关变更不应红」旁证）④ 未为过测试放宽 + 加注 ⑤ `src/main` 零改动 ⑥ 回归与 `@Test`==surefire ⑦ 「方案 (ii) 系三重重复」的理由是否成立（核 `BootstrapAssemblyTest` 是否确有 `routeCount==82` 守卫）。**待产品席（低，不另立号）**：`forceLogout` 的「触发入口是否须入 REST 面」属需求阐释；已实测原文无端点措辞、能力在服务层已实现 ⇒ **按原文判非缺口**。

  **📌（2026-09-22 `T-010` 转 `DONE` · `TE-T010b-001` delta 复检 = 通过 / 0 缺陷 · 只加不改）** 复检方 `te-rec-t010`（隔离副本 `/root/hgt010rec`，自撰探针、未复用产出方手法）。**★两点最关键**：**(1) 先证「旧的确实是空的」** —— 自撰探针实测经装配分发器 19 条 DISPATCH + 1 条不存在路径**一律 500**，且**等价还原的「修复前段②」在同一变异（M1 删 `ConfigRoutes:46` 的 `GET /config/ctp`）下仍绿** ⇒ **空转被独立复现**（不是只听产出方说）；**(2) 再证「新的确实会咬」** —— 同 M1 下**修复后段② 红**（`t109EndpointsNot404Not405:167`、`[GET /api/v1/config/ctp → 405]`），**复位字节复原 + clean 重编译后绿**。**其余**：无关变更（另注册新端点）**不误红**；断言前后逐条一致、**无放宽**（仅分发器由装配改直连、强度升）；`src/main` **零改动**；**`BootstrapAssemblyTest:140` 的 `routeCount==82` 确有且 M1 下实测红（82 vs 81）** ⇒ 佐证「方案 (ii) 系重复」的理由成立。**证据章**：`:3867–:3983`（纯追加已证：`head -3863` = `1243a62b…` → `b4b47f6a…`）；冻结点 `java/src` = `8bf0e1d6c2e83a52e62d511616324c4b`/335。**3 项信息级观察**（`OBS-1` 覆盖移交 / `OBS-2` 「三重重复」措辞宜作「1 完全重复 + 1 部分重叠」/ `OBS-3` 零改动系 mtime+diff 佐证）**均不另立**。**1 项需求阐释项留产品席**：`forceLogout` 的「触发入口是否须入 REST 面」（按原文判非缺口）。
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
  5. **（2026-09-23 第 92 轮补 · 依据见 §2 备注）`web/js/core/routes.js` 作为「已注册路径镜像」须与权威源一致**：① **头部计数**须与实时实测一致（现行 **82 个 `(方法,路径)` 对**；判据 = `java/src/test/java/com/hedgeguard/web/BootstrapAssemblyTest.java:140` `assertEquals(82, assembly.routeCount(), …)`，**且须写明该判据来源**，不得只写数字）；② **`NOT_REGISTERED` 逐条属实**（现 `/plan`、`/auth/user`、`/auth/sub-account-perm` 三项**已注册却列为未注册 ⇒ 必须移出**；`/risk/dashboard`、`/trade/order` 实测无 handler ⇒ **保留**）；③ **各模块段须覆盖该模块全部已注册路径**（现行缺 **23** 条：AUTH 8 / CONFIG 9 / SPOT 5 / PLAN 1，清单见 §2 备注）；④ **判据**：以「对 `handler/**/*Routes.java` 全部 `router.register` + 各 `*Handler.PATH` 常量**逐条解析**（含 `PlanActionHandler.Action.values()` / `PlanRatioHandler.Method.values()` 两处循环注册）后与 `routes.js` 对账」为口径，**须给「已注册 − 已登记 = ∅」的实测输出**；⑤ **不得**为凑数而登记**未注册**路径（`handler/` 下无该模块目录者一律不得写入可调用段）。
- **产出**：`web/index.html`, `web/login.html`, `web/css/hg.css`, `web/js/core/**`
- **备注**：前端只与 Java 的 `/api/v1/**` 与 `/ws/**` 通信，**不得**直连数据库或 C++（`E6`）。
  **📌（2026-09-23 编排者只读复核 · 只加不改 —— 订正第 88 轮普查「T-017 仅差登记」的结论）** 本席在派发 `T-022` 后顺带实测 T-017 交付面，结论 = **「仅差登记」不成立**，其交付物 **`web/js/core/routes.js` 另有 4 处陈旧/不实**（该文件自述为「前端只读镜像」，且**头部明确要求**「新增/改名须先改 §4.1.1 → `ApiRoutes.java` → 本文件」⇒ **镜像失真即违约**）：
  **(1) 头部路由计数陈旧**：`:14` 写「统一写 **47 个 (方法, 路径) 对 / 37 个路径**」，而**实测现行 = 82 对**（判据 = `java/src/test/java/com/hedgeguard/web/BootstrapAssemblyTest.java:140` `assertEquals(82, assembly.routeCount(), …)`）⇒ **陈旧**（该计数系 `T-109` 补齐「已声明未注册」端点前的旧值）。
  **(2) 「`GET /plan` 未注册」为不实**：`:33` 头部把「`GET /plan`（列表）」列入**「未注册（前端不得调用）」**清单，且 `:84` `NOT_REGISTERED.PLAN_LIST = '/plan'`；**实测已注册** = `java/src/main/java/com/hedgeguard/web/handler/plan/PlanRoutes.java:52` `router.register("GET", PlanHandler.PATH, new PlanHandler(planService))` ⇒ **不实**（与 `plan.js:15-16` 的陈旧误述**同源**，本席第 88 轮补已就 `plan.js` 登记于 `T-020`；**本条在 `routes.js`，属 `T-017` 产出面**）。
  **(3) SPOT 段漏登记 5 条已注册路径**：`:64-73` 仅列 8 条，缺 `T-109` 补齐的 **`/spot/production`**（`SpotRoutes.java:57`）、**`/spot/price/import`**（`:58`）、**`/spot/price/export`**（`:59`）、**`/spot/exposure/export`**（`:60`）、**`/spot/doc/sub-account`**（`:62`）—— 逐条 `grep` 实测**均为 0 命中**。**⚠️ 直接阻塞面**：`T-019`（现货与实货敞口前端页面）若按本镜像施工，将**看不到**上述 5 条 ⇒ **`T-019` 开工前须先修好本镜像**（或授权其自行直读 `ApiRoutes.java`）。
  **(4) PLAN 段未覆盖 `/plan/{id}/bind-exposure`**（小项）：`:74-81` 用通用式 `action(id, action)` 覆盖 8 个 action、`ratio(method)` 覆盖 3 个 ratio，但 `bind-exposure` 既非 `Action` 枚举亦非 ratio ⇒ **未覆盖**（`ApiRoutes.java` PLAN 组含该路径）。
  **另附（非缺陷，据实存档）**：验收 2 列举 **6** 个 `js/core/*.js`，实有 **9** 个（多 `ns.js` 命名空间、`routes.js` 路径镜像、`ws.js`）⇒ **超集、验收 2 满足**；`web` 树锚 = `f827e00e6687afb092ab933fe0816ce4` / **27 文件**，与 `T-018` 冻结点**逐字节一致**（⇒ 自 `T-018` 交付以来 `web/**` 未再变）。
  **处置**：**并入 `T-017`**（其产出面 = `web/js/core/**`；不另立号）；**待 `te-verify-t018` 回执后再改 `web/**`**（避免污染该复核的取证锚点）。

  **★★（2026-09-23 第 92 轮 —— 本席**据实更正上一条的**过窄**计数 · 只加不改）** 上条写「**4 处**陈旧/不实」，系**只抽验了 4 个点**即下结论（**又一次「定性先于事实」，此次方向是「说少」**）。第 92 轮**系统化审计**（对 `handler/**/*Routes.java` 全部 `router.register` + 各 `*Handler.PATH` 常量**逐条解析**后与 `routes.js` 对账）实测**缺口远大于 4**：
  - **(a) 头部计数**：`:14` 写「**47 对 / 37 路径**」⇒ 实测 **82 对**（判据 `BootstrapAssemblyTest.java:140`）。**（同前）**
  - **(b) `NOT_REGISTERED` 三项不实（新增 2 项）**：除 `/plan` 外，**`/auth/user`** 与 **`/auth/sub-account-perm`** 亦**已注册**（`AuthRoutes.java` 内 `UserHandler.PATH`〔=`/auth/user`，`UserHandler.java:32`〕GET/POST/PUT 三注册、`SubAccountPermHandler.PATH`〔=`/auth/sub-account-perm`，`SubAccountPermHandler.java:29`〕GET/POST 两注册）⇒ `:85`/`:86` 与头部 `:33` 的「未注册」声明**不实**。**保留正确者**：`RISK_DASHBOARD`(`/risk/dashboard`)、`TRADE_ORDER`(`/trade/order`) —— 实测 `handler/` 下**无 `risk`/`trade` 目录** ⇒ 确未注册。
  - **(c) AUTH 段缺 8 条**已注册路径：`/auth/user`、`/auth/role`、`/auth/position-group`、`/auth/authorization`、`/auth/sub-account-perm`、`/auth/change-log`、`/auth/unauthorized-attempt`、`/auth/password`（该段现仅 `LOGIN`/`LOGOUT` 两条）。
  - **(d) CONFIG 段缺 9 条**：`/config/ctp`、`/config/ctp/test`、`/config/dict`、`/config/dispatch/{id}`、`/config/export`、`/config/import`、`/config/report-period`、`/config/subject`、`/config/change-log`（该段现仅 `ITEM`/`SUB_ACCOUNT`）。
  - **(e) SPOT 段缺 5 条**（同前）：`/spot/production`、`/spot/price/import`、`/spot/price/export`、`/spot/exposure/export`、`/spot/doc/sub-account`。
  - **(f) PLAN 段缺 1 条**：`/plan/{id}/bind-exposure`（`action()`/`ratio()` 通用式未覆盖）。
  - **合计：头部计数 1 项 + `NOT_REGISTERED` 不实 3 项 + 缺失已注册路径 23 条**（AUTH 8 + CONFIG 9 + SPOT 5 + PLAN 1；其中 `/auth/user`、`/auth/sub-account-perm` 同时属 (b)/(c)，故**去重后缺失路径 = 23**）。**判据口径**：路径「已存在」以**是否能由 `routes.js` 的常量或其拼接式还原**为准（`/product/{id}` 等由 `detail:`/`exposureDetail:`/`doc:`/`tradePlans:` 拼接者**算已存在**）⇒ 故**不含** `/product/{id}`、`/product/{id}/contracts`、`/spot/exposure/{id}`、`/plan/{id}`、`/plan/{id}/doc`、`/plan/{id}/trade-plans`。
  **⇒ 纪律（本次第四类失败）**：「**N 处**」这类计数**必须来自穷举**（对权威源**逐条**对账），**不得**由「抽验若干点」得出；抽验只能支撑「**至少 N 处**」，**不得**写成「**共 N 处**」。**已同步写入 `T-017` 验收 5**（见下）。
  **📌（2026-09-23 第 92 轮派发登记 · 只加不改）** 状态 = **IN_PROGRESS**（实例 `be-t017`、**唯一写者**：`web/index.html`、`web/login.html`、`web/css/hg.css`、`web/js/core/**`）。**派发前置**：`te-verify-t018` 已回执并转 `DONE` ⇒ **该复核的取证锚点已定**，改 `web/**` 不再污染它（本条即上一条「待其回执后再改」的满足）。**基线**：`web` 树 = `f827e00e6687afb092ab933fe0816ce4` / **27 文件**（自 `T-018` 交付以来未变）。**交付内容** = ① 修 `routes.js` 镜像（**头部计数 47→82**、**`NOT_REGISTERED` 移出 `/plan`+`/auth/user`+`/auth/sub-account-perm` 三项**、**补 23 条缺失已注册路径**：AUTH 8 / CONFIG 9 / SPOT 5 / PLAN 1）；② 对验收 1–4 逐条实测自证。**并发约束**：同轮在飞 `te-verify-t022`/`te-verify-t023`（**均只读**，且在**隔离副本**内跑）与 `pm-ch012sync`（写 `docs/REQUIREMENTS/**`）⇒ 写域与 `web/**` 不重叠。**须你给「已注册 − 已登记 = ∅」的实测输出**（见验收 5）。**自评**：状态只能写「已完成（待独立复核）」。

  **📌（2026-09-23 第 93 轮交付登记 · 已完成，待独立复核 · 只加不改）** 实例 `be-t017`（**唯一写者**：`web/js/core/**`；**另经本席授权**越界修 `web/js/pages/plan.js` 最小回归，见下）。**改动 3 文件**：`web/js/core/routes.js` **+49/−20**（改前 `80909124…` → **`dfd23bfd24dbc3af3e4a37b05066eaf8`**/122）、`web/js/core/session.js` **+2/−1**（**`25740708d293a5f86057dcb344b7f872`**/112）、`web/js/pages/plan.js` **+10/−10**（改前 `4fcc0456…` → **`28316f6b41cfe526648181ea693992e6`**/339）。**`web` 树锚**：`f827e00e…`/27 → **`926022d9389bfa40c171279d2657d56d`/27**。
  **镜像修复（验收 5）**：**实测权威源 = 82 个 `(方法,路径)` 对 / 60 个唯一路径**（分模块 `auth 17 / config 17 / product 13 / spot 15 / plan 18 / ping 2`）；**本席独立复算逐字一致**（我按不含循环得 69，+`PlanActionHandler.Action.values()` 8 +`PlanRatioHandler.Method.values()` 3 +`ping` 2 = 82）。**对账实证：`已注册 − 已登记 = ∅`、`已登记 − 已注册 = ∅`、`NOT_REGISTERED` 不实 = 0**（改前同脚本 = 缺 **23** 条 + 不实 **3** 条，与本席清单**逐条吻合**）；头部计数已改为「**82 对 / 60 路径**」并**写明判据来源**（`BootstrapAssemblyTest.java:140`）；`bind-exposure` 用专用 `PLAN.bindExposure(id)` 承载；**保留** `NOT_REGISTERED` 中正确的 `/risk/dashboard`、`/trade/order`（实测 `handler/` 下无 `risk`/`trade` 目录）。
  **边界内同源订正**：`session.js:8` 原称 `/auth/user`、`/auth/sub-account-perm` **未注册** ⇒ 不实，已订正。
  **★授权跨界修复（本席裁定，留痕）**：`routes.js` 移除不实的 `NOT_REGISTERED.PLAN_LIST` 后，**全仓唯一消费方** `web/js/pages/plan.js:89` **求值为 `undefined`**（探测按钮将请求 `/api/v1undefined`）⇒ **属本单造成的真回归**。因 `plan.js` 属主 `T-020` **未在飞**，本席**授权 `be-t017` 做最小修复**（「因我方改动造成的回归、由改动方消除」），**限定**：`:89` → `HG.routes.PLAN.LIST`；`:15-17`/`:63-66` 陈旧误述订正为事实（`PlanRoutes.java:52`、`T-010` 补齐）；`:91`/`:96` 探测按钮前提串同步（**`kind='warn'` 未动**）。**已复测**：键级 `NOT_REGISTERED.` 引用现 **0 命中**（原仅 `plan.js:89` 一处）。
  **⚠️ 回执中一处不实（低，据实记录）**：交付方称 `plan.js` 改前 **208 行**，**实测 `339` 行**（改前改后均 339，`+10/−10` 净值不变）⇒ 该行数陈述不实；**md5 与 numstat 均属实**，不影响结论。
  **余留（**不在授权范围、如实报出，归 `T-020`**）**：`plan.js` 内仍有同源陈旧措辞 —— **`:56`**（头注「后端未提供列表端点，显式登记阻塞」）与 **`:111`**（链接文本「← 返回计划列表（接口未就绪说明）」）⇒ 该二处**非回归**（属 `T-020` 页面业务实现面），本席裁定**随 `T-020` 一并收口**（已在 `T-020` §2 登记）。
  **自测（交付方报）**：无头桩探针 **25/25 PASS**（`/root/be-t017/probe/run.mjs`，副本 md5 已核）；`E6` **rc 0**；聚合 `arch-check` **rc 0**；`node --check` 9 文件 OK。**⚠️ 环境无浏览器/jsdom/puppeteer ⇒ 一律「静态 + 无头桩」层面，非真实浏览器验证。** **未自评 `DONE`。**

  **📌（2026-09-23 第 92 轮独立复核登记 · 退回小修 · 只加不改）** 复核席 `te-verify-t022`（**自撰变异 A/B、隔离副本 `/root/t022_probe/`、共享源树零改动**）。**结论 = 有条件通过 / 0 阻断缺陷 / 1 低缺陷 / 4 观察**。**证据章** = `docs/TEST_RESULT.md` **`L5980..6099`**（**纯追加已独立证实**：`head -5979` = `57f9ff5d…` 不变；新全档 `4e7e8479…`/6099）。**复核时点** HEAD `6462c0c`；`docs/TEST_PLAN/**` 自 `64b706d` 后**未被任何提交动过** ⇒ 复核有效（本席已实测）。
  **复核方独立成立项**：4 文件锚点与树锚 `b5d58673…`/14（3×6s 静默）、`numstat`、`307→311`、`272/174/98 未改`、§16 **16.1–16.8 无重号**、**181 表格块列数等值不符 0**（自建 `eff_pipes()` 按**反斜杠奇偶**扣转义）、`§15` 尾行逐字节保留、**每条 `−` 行全为换数/换值**（无误删）、范围合规（`git diff --name-only c8d9e0a 64b706d` 仅 4 个 TEST_PLAN 文件）、**4 条新用例语义逐条对上** `DATA_MODEL.md:4503`（`#17`）/`:4498`（`#13`）原文且**均有唯一确定预期值**、**FR 归属无张冠李戴**（`FR-RISK-2`@`RISK.md:113`、`FR-SPOT-14`@`SPOT.md:294` 实测吻合）、错误码 `0x1004`@`ARCH:1363` **码存且语义相符**。
  **★缺陷 `TE-T022-001`（低·非阻断，本席已复现）**：`docs/TEST_PLAN/INDEX.md` **`:860`**（§16.8 缺口清单 `Q4` 行）**未随本轮补件更新** —— 行文仍称「`PRODUCT.md`/`SPOT.md` §1 **缺 `里程碑` 行**」、证据列「`grep … → **0 命中**`」、处置「**未授权前不改**」；而**实测两文件各 1 命中**（`PRODUCT.md:23`/`SPOT.md:22`、**11/11 齐**）⇒ **该行证据命题为假、且与本 commit 自身所加的行自相矛盾**。**影响**：读者据 §16.8 误判 `Q4` 仍开放；`T-024` 若照抄会**重开已闭合项**。**处置 = 退回小修**（**保留原条目 + 加「已闭环 + 落点」**，照 `Q2`/`Q3` 体例）。
  **观察项处置（本席裁定）**：`O-1`（`TC-SPOT-22` 的 `code=10015` 系**推断**：码存于 `ARCHITECTURE.md:1318` 且语义相符，但**文档未把「缺分量」显式绑定为其触发条件**）⇒ **保留码值作唯一预期值 + 加依据/推断标注**（不得改成不可判定表述）；`O-2`（`docs/TEST_PLAN/**` **无自动守卫**，复核方两变异均无守卫检出）⇒ **登记为判据强度事实**，**M1 期间不另立号**（设计件、一致性由 `T-024` 人工执行时抽验）；`O-3`（`grep '\b307\b' docs/` = **4 命中**，皆变更留痕/无关行区间）⇒ 非缺陷，本席按精确口径记账；`O-4`（`Q1` 行证据列称「命中仅 `INDEX.md`」而实测 **5 处**，全为散文）⇒ **实质结论仍成立**，但**证据列计数须为真**（本文件 §16.8 自订纪律）⇒ 一并小修。
  **★本席两处更正（由我承担，不计产出方）**：**(i)** 我把**台账所称**当成了**制品所称** —— 我派单交底写「`Q4` **声称已补**」，**与制品不符**：`INDEX.md:860` **并未**称已补（仍称开放）；「已补」这一**事实**载于**台账**（`BOARD.md` / 本任务交付登记），**未回写 `INDEX.md` §16.8** ⇒ **此即 `TE-T022-001` 的成因**；复核方归因（「非产出方在制品内判已闭环」）**正确**。**(ii)** 我在第 92 轮台账写「`Q4` 已修」而**未核 `§16.8` 行文是否同步** ⇒ **纪律：补了底层事实 ≠ 该制品已自洽**（新增纪律，见 `BOARD.md` §5 第 93 轮）。

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
  **📌（2026-09-23 派发登记 · 只加不改）** 状态 = **IN_PROGRESS**（实例 `be-t018`、**唯一写者**：`web/js/pages/product.js` + `web/js/routes.js`）。**派发前置经实测核验**：其验收 2 所列 **6 个端点全部已在服务端注册且有 Handler**（`/product`、`/product/{id}`、`/product/whitelist`、`/product/quota`、`/product/import`、`/product/export`；注册清单见 `java/src/main/java/com/hedgeguard/web/ApiRoutes.java:29-31`）。**形式依赖 `T-017` 登记为 `TODO` 但实现面已在树**（`web/js/core/` 6 文件 + 登录页 + `window.HG`；`E6` 已 `rc=0`）⇒ **实质前置满足**。**基线**：`web` 树 = `81a06bce19f9db6df02c46a34f93eb20` / **27 文件**（`product.js` 本为 19 行 `renderStub` 桩件）。
  **📌（2026-09-23 交付登记 · 已完成，待独立复核 · 只加不改）** 实例 `be-t018`（**唯一写者**；**未改 `docs/**`**；**不自评 `DONE`**）。**改动 = 2 文件**：`web/js/pages/product.js` 19 行 stub → **999 行**（`b8e66b70927d596ab62ec5cb83e39398`；列表 `#/product` + 详情 `#/product/{id}` 六 Tab：概览/合约关联/现货映射/白名单/额度（三层）/变更历史；三态齐全）；`web/js/core/api.js` **`+45/−1`**（`db1d4e9dd661cbe097c6c7cd0b7f5c1d`）—— 新增 `HG.api.download()`（非 JSON 响应的下载通道）+ `filenameFromDisposition()`；**编排者实测 `git diff --numstat` = `45/1`** ⇒ 交付方自述「**+46 行纯增量**」**表述不精确**（实为 `+45/−1`，净 +44）；**唯一的那 1 处修改** = `HG.api = { … , list, **download**, request }` 导出清单**追加 `download`** ⇒ 其余 **8 个既有函数定义与调用形态确未动**（该表述**实质成立**）。**未改 `web/js/core/routes.js`** —— 交付方**纠正了本席派单的一处事实错误**：派单写 `web/js/routes.js`，**该路径不存在**，真实路径为 **`web/js/core/routes.js`**，且其中 **`:54-62` 已登记全部 9 条 PRODUCT 路径**（含验收 2 的 6 个端点）⇒ **无需改动**（本席据普查转述的 `routes.js` 未核目录即写入派单，**属我的错**）。**自测**：`E6`（`check_frontend.sh`）**rc=0**；**无头 DOM + fetch 桩**自测（Node 22）**23/23 PASS** —— 交付方**如实标注「非真实浏览器验证」**（本环境无浏览器/jsdom/puppeteer）⇒ 属**运行态证据但非浏览器证据**。**冻结点**：`web` 树 = **`f827e00e6687afb092ab933fe0816ce4` / 27 文件**（连续 3 次一致；文件数不变、内容变更）。**★7 项未决（交付方**未自行改后端**，处置见下）**：**(1)** `FR-PRODUCT-2/3/4/13` 的**写面端点缺失**（服务层已有 `disableProduct`/`enableProduct`/`putUnit`）⇒ **已立 `T-131`**；**(2)** `GET /product/whitelist` **无读取端点**（仅 POST ENABLE/DISABLE）⇒ 白名单页**可写与看留痕、无法列出当前完整白名单** ⇒ **并入 `T-131`**（若 `FR-PRODUCT-12` 要求读取端点）；**(3)** `GET /product` **无筛选参数**（Handler 仅读 `page/size/sort`）⇒ **已由 `T-116` 承载**（不另立）；**(4)** `GET /product/quota` **以 JSON `number` 输出 `BigDecimal`** ⇒ 违反 §4.1.1 `:625` 与 `G-2` ⇒ **已立 `T-132`**；**(5)** `GET /product/export` 无数据仍返回仅含表头 CSV ⇒ 与 `FR-PRODUCT-8` 验收 3 冲突 ⇒ **已由 `T-116` 承载**（其验收 2 含「导出与列表筛选一致」；前端已以「仅表头则拦截」兜底并标注启发式局限）；**(6)** `ProductView` 无 `createdTime`／`ProductChangeLogHandler` 仅接受 `productId`（缺时间范围/类型筛选）⇒ **已由 `T-116` 承载**；**(7)** 层③ `ACCOUNT_PRODUCT` 写面授权 = 已知 `CH-012`（待用户确认）⇒ 实施方**仅做界面、未当缺陷报**（处置正确）。**★同类扫描（交付方给）**：`web/js/pages/` 仍为 `renderStub` 骨架 **7 个**（`acct/audit/auth/config/fund/risk/settle`，各 16–19 行）；**半成品** `trade.js`（62 行，`/trade/**` 未注册，未发任何请求、已诚实登记）；**已实现** `product`(本单)/`spot`(572)/`plan`(339)/`login`(70)/`_dashboard`(62)/`_stub`。
  **📌（2026-09-23 独立复核登记 · 只加不改 · **已转 `DONE`**）** 席位 `te-verify-t018`（**自撰独立探针** `/root/t018v/probe.mjs`，**31/31 PASS**；**未复用产出方 harness**）。**结论 = 有条件通过 / 0 阻断缺陷**（缺陷单 0；观察 4）⇒ 按本项目判据（**授权 + 独立验证**）**转 `DONE`**。**本席独立实测核验其锚点全部吻合**：`product.js` `b8e66b70…`/999、`api.js` `db1d4e9d…`/172、`web` 树 `f827e00e…`/27（与交付冻结点**逐字节一致**）、`git diff --numstat 71e857a 6acead5 -- web/` = `45/1` + `995/15`、`routes.js` **未被本单改动**；证据章 `docs/TEST_RESULT.md:5580`（`L5579..5775`）**纯追加已独立证实**（`head -5578` = `6988ede8…` 不变；全档 `1feb4fbd…`/5775）。**★证据口径（F）= 部分采信**：**采信**逻辑/契约面（端点调用点、camelCase 字段逐字对上、分页信封真消费、**排序显式 `created_time,desc`**、errmsg 四项、导入逐行、导出不吞错）；**不可采信**浏览器/真机面（真实浏览器渲染、ESM 加载、`Blob` 真下载、`FileReader`、与真实 Java 后端 e2e —— 环境无浏览器/jsdom/puppeteer）⇒「页面**浏览器**可用」**只能降级为「页面逻辑可用」**，**登记为残余验证风险**，建议并入 M-里程碑**前端浏览器冒烟**（**不阻断**本单逻辑级验收）。**★立号核对（G）**：`T-131`/`T-116`/`T-132` **立号正确**；但**承载有漏** ⇒ `OBS-T018-2/-3/-4` **已就地收口**（`T-116` 验收补 6/7 + 范围扩 3 → 5 人时；`T-131` 验收 3 订正为核 `FR-PRODUCT-6`+`FR-PRODUCT-12`）。**未收口项**：`OBS-T018-1`（`CH-012` 三处状态冲突）⇒ **提请用户裁定**（见 `T-119`、`BOARD.md` §5 第 91 轮）。

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

  **📌（2026-09-23 第 95 轮 · 前置约束 · 只加不改）** `T-017` 独立复核观察 `O-1`：**`web/js/core/routes.js` 的 `ROUTES` 对象只含路径、不含 HTTP 方法**（方法仅在文件头部散文，如 `GET|POST|PUT /auth/user`）⇒ **本任务取用路由时须并读头部方法清单，或直读 `java/src/main/java/com/hedgeguard/web/handler/**/*Routes.java` 的 `router.register`**；**勿只读 `ROUTES` 对象就假定方法**。另：**全仓 grep/审计须排除 `.codebuddy/`**（内含陈旧 worktree，会命中幻影，见 `T-017` §2 `O-4`）。

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

  **📌（2026-09-23 编排者派单前实测 · 只加不改）** **订正一条误导性自述**：`web/js/pages/plan.js:15-16` 写「**`GET /plan`（列表）未注册**（`PlanRoutes` 仅注册 `POST /plan` 与 `GET /plan/{id}`）⇒ 命中 `pathExists` → **405**」，并据此做了「探测真实响应」按钮（`:96`）。**实测否证**：`PlanRoutes.java:52` = `router.register("GET", PlanHandler.PATH, new PlanHandler(planService));` ⇒ **`GET /plan` 已注册**（同文件 `:53` 注册 `POST /plan`、`:54` 注册 `GET /plan/{id}`；`ApiRoutes.java:38` 亦含 `"/plan"`）。该自述引用的 `Q-FE-001` **早已 `CLOSED`**（`GET /plan` 的隔离/分页已由 `T-100`/`T-103` 交付）⇒ **注释与按钮前提均已陈旧**。**处置（归本任务，不另立号）**：交付 `T-020` 时**一并订正**该注释与探测按钮的表述，**不得**按「后端未注册」去改后端（`java/**` 非本任务写域）；**其真正的缺口**见本任务验收 5（`unverified` 展示，`grep unverified web/` = 0）与验收 4（**真前置 `T-104` = `TODO`**）。
  **📌（2026-09-23 缺口登记 · 只加不改）** 编排者派单前实测（另一席只读普查）：本页 `plan.js` = **339 行（部分实现）**；**缺** 编制表单（`POST /plan`）、**三档比率**（`POST /plan/ratio/{simple|minvar|custom}`）、**书面文件预览**（`POST /plan/{id}/doc`，三要素门禁）、**8 个状态操作**（`submit/approve/reject/withdraw/freeze/unfreeze/terminate/extend` —— `grep approve web/` 的 2 处命中全在 `routes.js` 注释与 `fund.js` 桩）；且 **`unverified` / `precheckResult` / `limitReached` / `remainingQty` 在 `web/` 全 0 命中**。**注意**：验收 4 的 `remainingQty`/`limitReached` **须消费服务端字段**，而服务端字段归 **`T-104`（实测 `TODO`）** ⇒ **T-020 验收 4 有真前置**。


  **📌（2026-09-23 第 93 轮补登 · 只加不改）** 本席已对 `plan.js` 的**同源陈旧措辞**做**最小回归修复**（`:89` 改用 `HG.routes.PLAN.LIST`；`:15-17`/`:63-66` 误述订正；`:91`/`:96` 前提串同步）—— 该修复由 `T-017` 实例 `be-t017` 经授权执行（原因：`T-017` 移除不实的 `NOT_REGISTERED.PLAN_LIST` 致 `:89` 求值 `undefined`，属其造成的回归）。**仍余 2 处属本任务业务实现面、未改**：**`:56`**（头注「后端未提供列表端点，显式登记阻塞」）与 **`:111`**（链接文本「← 返回计划列表（接口未就绪说明）」）⇒ **本任务交付时一并订正**（二者为**非回归**的陈旧措辞）。**本任务的真实缺口清单不变**（编制表单／三档比率／书面文件预览／8 状态操作／`unverified` 展示／验收 4 真前置 `T-104`）。
  **📌（2026-09-23 第 95 轮 · 前置约束 · 只加不改）** `T-017` 独立复核观察 `O-1`：**`web/js/core/routes.js` 的 `ROUTES` 对象只含路径、不含 HTTP 方法**（方法仅在文件头部散文，如 `GET|POST|PUT /auth/user`）⇒ **本任务取用路由时须并读头部方法清单，或直读 `java/src/main/java/com/hedgeguard/web/handler/**/*Routes.java` 的 `router.register`**；**勿只读 `ROUTES` 对象就假定方法**。另：**全仓 grep/审计须排除 `.codebuddy/`**（内含陈旧 worktree，会命中幻影，见 `T-017` §2 `O-4`）。

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

  **📌（2026-09-23 第 95 轮 · 前置约束 · 只加不改）** `T-017` 独立复核观察 `O-1`：**`web/js/core/routes.js` 的 `ROUTES` 对象只含路径、不含 HTTP 方法**（方法仅在文件头部散文，如 `GET|POST|PUT /auth/user`）⇒ **本任务取用路由时须并读头部方法清单，或直读 `java/src/main/java/com/hedgeguard/web/handler/**/*Routes.java` 的 `router.register`**；**勿只读 `ROUTES` 对象就假定方法**。另：**全仓 grep/审计须排除 `.codebuddy/`**（内含陈旧 worktree，会命中幻影，见 `T-017` §2 `O-4`）。

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
- **产出**（**📌 2026-09-23 编排者就地修订，理由见下；原写 `docs/TEST_PLAN/M1.md`**）：**以 `docs/TEST_PLAN/**` 为承载**（14 文件：`INDEX.md` + 11 模块 + `BOUNDARY.md` + `NONFUNCTIONAL.md`），并在 `INDEX.md` **增一节「里程碑视图（M1）」**索引 M1 相关用例。**不另造 `M1.md`**（避免与既有模块化件重复）。**修订理由（据实）**：只读普查实测 —— 该 TEST_PLAN 为**阶段 S5 / v0.1 / 状态:草稿**的**全里程碑**件，其内容**实质已覆盖本任务验收**（`INDEX.md:35` 唯一约束/非空/外键/CHECK 映射；`:109-121` JUnit→TC 映射 174/272；`:666` `RSK-03` 假 DAO 无 DM 档；`:598` 三层限额全序；`:599` 敞口三分量），而 `T-022/T-056/T-072/T-084` 各自命名的 `M1/M2/M3/M4.md` **全部不存在** ⇒ **书面产出面与实际承载形态不符，是边界写在事实之前**（同族先例：`T-130` 验收 6）。**待办**：该「里程碑视图」小节与「草稿→定版」状态由 `T-022` 交付时一并处理。
  **📌（2026-09-23 派发登记 · 只加不改）** 状态 = **IN_PROGRESS**（实例 `tm-t022`、**唯一写者**：`docs/TEST_PLAN/**`）。**派发前置经实测核验**：依赖 `T-002` 已完成（M1 数据模型门禁三方会签达成）；**基线** `docs/TEST_PLAN` 树锚 = `ed2c9b21180e269ab70cf6698baa98bd` / **14 文件**（口径 `find docs/TEST_PLAN -type f | LC_ALL=C sort | xargs md5sum | md5sum`，cwd = 仓库根）；`INDEX.md:3` = `阶段：S5 ｜ 版本：v0.1`、`:8` = `状态：草稿`。**并发约束**：同轮在飞 `be-t023`（**写** `java/src/test/**` + `java/pom.xml`）、`te-verify-t018`（**只读**）⇒ 写域不重叠。**交付面** = `INDEX.md` 增「里程碑视图（M1）」节（**不另造 `M1.md`**）。**派单已明示的范围边界**：① 不得因本任务只覆盖 M1 而把**全里程碑档**（阶段 S5 / v0.1 / 草稿）单方面改为「定版」，如需变更须**报请裁定**；② 不得改 `java/**`、`web/**`、`docs/TASKS.md`、`docs/BOARD.md`、`docs/TEST_RESULT.md`（其 `T-022` 节由编排者落笔）；③ 须**逐条独立实测**本席转述的 5 条落点线索（`INDEX.md:35`/`:109-121`/`:666`/`:598`/`:599`）并按「独立实测 vs 派单所述」两栏回报；④ 验收项若实际未覆盖须**逐条点名**，不得概括为「已覆盖」；⑤ 临时产物**不得落 `/tmp`**（本机 `/tmp` 为小 tmpfs 且常满）。
- **备注**：**不写代码**；用例设计属测试经理职责。
  **📌（2026-09-23 退回补件交付登记 · 已完成，待独立复核 · 只加不改）** 实例 `tm-t022`（**唯一写者** `docs/TEST_PLAN/**`）。**改动 4 文件**：`INDEX.md` **+141/−15**（`bc4e19fa…`/862）、`SPOT.md` **+85/−1**（`2b4b0cfa…`/663）、`RISK.md` **+28/−1**（`442634a9…`/888）、`PRODUCT.md` **+1/−0**（`74ca4b51…`/486）；**树锚 `b5d58673…`/14**（3×4s 恒定）。**用例总数 307 → 311**（SPOT 20→23、RISK 30→31；编排者独立实测逐文件 `grep -cE '^### TC-'` 合计 = **311** ✔）。**本席独立实测复核全部吻合**：**D1 两路径**已改为 `dao/` 前缀（`[ -f ]` EXISTS；旧两条 MISS）＋ §16 全路径自扫 18 引用 **MISS=0**；4 条新用例 `TC-SPOT-21/22/23`（`#17`③④⑤）+ `TC-RISK-31`（`#13`⑤）正文含「**预期结果**」且**可判定**；§16 八子节 / 8 表 / 49 行 **列数等值不符 0**；`SPOT.md §2` 23 行、`RISK.md §2` 31 行**列数不符 0**；**11/11** 模块各 1 行 `| 里程碑 | M1 |`；`§16.8` 的 `Q2`/`Q3` 已标「已闭环」且**保留原条目**（留痕）；`§2.2` 加**时点边界注**且 **272/174/98 数字未改**；`§15` 尾行逐字节保留。**未改** `java/**`、`web/**`、`docs/TASKS.md`、`docs/BOARD.md`、`docs/TEST_RESULT.md`。**状态字段维持「草稿」**（本席裁定）。**残留**：`Q1`（外键→孤儿 / `CHECK`→取值域**无 `TC-` 级用例**）仍由 `T-023` 集成测试承载、**未闭合到 TC 级**（交付方如实披露）。**未自评 `DONE`**。
  **📌（2026-09-23 第 95 轮 · 退修改由新实例执行 · 只加不改）** 本席曾向席位 `tm-t022` 投递退修指令（修 `§16.8 Q4` 行 + `Q1` 行证据列 + `TC-SPOT-22` 加依据标注），但**该席位实例已下线、指令成为死信**（工具提示「not yet registered … will be delivered when they come online」；本席**实测复核**：`docs/TEST_PLAN/INDEX.md` 仍为 `bc4e19fa…`、工作区干净、无构建进程）⇒ **改由新实例 `tm-t022b` 执行**（**用新名**，避免与滞留在 `tm-t022` 收件箱的同一指令**重复触发** —— 本项目已有「同席位两份指令被两实例各执行一条」的事故）。**属「席位≠实例」的又一实例 ⇒ 派单与台账须记「实例名」，不能只写席位名。**

  **📌（2026-09-23 第 97 轮 · `TE-T022-001` 退修登记 · 已完成，待 delta 复检 · 只加不改）** 实例 `tm-t022b`（**唯一写者 `docs/TEST_PLAN/**`**）。**改动 2 文件**：`docs/TEST_PLAN/INDEX.md` **+2/−2**（`bc4e19fa…` → **`7e7ac6f9d8c6ead7234e10caa79b96f3`**；`Q1` 行 `:857` 与 `Q4` 行 `:860` 原位替换、净行数不变）、`docs/TEST_PLAN/SPOT.md` **+2/−0**（`2b4b0cfa…` → **`b1cd35ebd7894d097ef7ba20f04ccef3`**；`TC-SPOT-22` 加「依据」标注）；**树锚** `b5d58673…`/14 → **`21451792a1883d4edce75bd257dc859d`/14**；**用例总数 `311` 不变**。
  **本席独立实测复核（逐项吻合）**：**(a) `Q4` 行**已改为「**原缺口；已于 2026-09-23 闭环，见右**」并在证据列**显式标注原命题有误**（「原写 … → **0 命中**（**该命题有误**）；实测 各 **1**（合计 **2**）」）+ 结论列给「✅ 已闭环（`T-022` 授权补齐）…实测 **11/11** …复核命令」——**保留原条目、未删除** ✔；**(b) `Q1` 行**证据列改为「**实测 5 处命中，全在 `INDEX.md`**（`:35`/`:769`/`:779`/`:784`/`:857`）…均为散文、无 `TC-` 级」——**本席复跑 `grep -rn '孤儿\|引用完整' docs/TEST_PLAN/*.md` 得同一 5 处行号，逐处吻合** ✔；**(c) `TC-SPOT-22`** 加「依据」标注（`10015` 取自 `ARCHITECTURE.md:1318`「数据不足，拒绝计算」语义相符；**文档未显式绑定「缺分量」为其触发条件**〔该行触发列在价缺口侧〕⇒ **设计推断**，执行期以实现为准），**预期值仍为唯一 `code=10015`、未改判据** ✔；**(d) 状态字段维持「草稿」未改** ✔。
  **交付方自检四项全绿（本席复测一致）**：**全 14 文件表块列数等值不符 0**、**悬空行 0**、无行截断（`git diff --stat` = `+2/−2`、`+2/−0`；行数 `862→862`、`663→665`）、`§16` 八子节完好（16.1–16.8）。全程**整行锚**、替换文本无前导换行。
  **★交付方主动披露其首版自身缺陷（正确处置）**：首版在 `Q4` 行内写了**未转义**的 `grep -c '^| 里程碑'` ⇒ 该行**有效竖线 7 ≠ 表头 5**（**被其自检抓到**），已改为 `^\| 里程碑`；改后**不符 0**。⇒ 属本项目「**表格竖线转义**」家族的**又一实例**，且**由产出方自查发现**。
  **★本席据实更正其一处「观察」**：交付方报「`docs/TEST_RESULT.md` 工作区被改（+207 行，mtime `10:45:14`）＝并发写者」—— **实测为误报**：本席已复测 `docs/TEST_RESULT.md` = **`edd8bc88c07c4f895390974f5bc97d80`/6500**，与已提交的 `68da7dd` **逐字节相同** ⇒ **无并发写者**；其实见的是**本席尚未提交的 T-017 证据章**（后已提交）。⇒ **纪律：报「并发写者」前须核「该改动是否恰为本席/他人已提交或待提交的产物」**（本项目已有同类前科）。
  **⇒ 待 delta 复检（仅核上述 3 处与自检四项）。**
  **📌（2026-09-23 第 98 轮 · delta 复检登记 · **通过 / 0 阻断 ⇒ 转 `DONE`** · 只加不改）** 复检席 `te-rec-t022`（**纯只读、未落证据章**〔delta 从简，本席裁定不强制〕、**未跑构建**）。**4 项独立实测逐项吻合**：
  **(1) `Q4` 行（`:860`）**：①已标「原缺口；已于 2026-09-23 闭环，见右」**且保留原条目**；②证据列**显式承认原命题有误**；③结论列给闭环落点（`PRODUCT.md:23`/`SPOT.md:22`、**11/11**、复核命令）；④**自跑 `grep -c` 里程碑计数** ⇒ 11 个模块文件各 **1**、3 个非模块文件各 **0** ⇒ **11/11** ✔
  **(2) `Q1` 行证据为真且无行漂移**：自跑 `grep -rn '孤儿|引用完整'` = **恰 5 处、全在 `INDEX.md`**（`:35`/`:769`/`:779`/`:784`/`:857`），与声明**逐位一致**；`TC-` 级命中 **0** ✔
  **(3) `TC-SPOT-22` 只加注未改预期值**（`+2/−0`；`10015` 仍唯一），并**回读 `ARCHITECTURE.md:1318` 原文**证实「数据不足，拒绝计算」相符、**触发条件确在价缺口侧、未绑定「缺分量」** ⇒ 标注属实 ✔
  **(4) 结构回归**：两文件 md5 / `numstat` / 树锚 `21451792…`/14 / 用例 **311**（`^### TC-` 头 = distinct id = 311、dup 0）/ 范围（仅两文件）/ `§16` 八节（无重号）**全吻合**；**`git log bfa9b50..HEAD -- docs/TEST_PLAN/` = 空**（其后无再触）✔
  **★表块完整性（含探针负控）**：自写探针扫**全 14 文件 / 622 表块 / 0 列数不等 / 0 悬空行**，且**经负控验证探针非空转**（注入未转义竖线 ⇒ 报 `COLMISMATCH eff=6 hdr=5`；注入孤行 ⇒ 报 `DANGLING`）⇒ **真实树 0 命中有效**；`Q4` 行**现已转义**（有效竖线 5 = 表头），全树无其它同类。**⇒ 本项目曾多次因「体检脚本自身空转」得到假绿 ⇒ 负控是必要步骤。**
  **观察（不阻断）**：**(i)** 行内竖线以转义形式呈现是 Markdown 表格**既有惯例**、**渲染态命令实跑通过**；唯**从原始 `.md` 源码复制**会得退化 BRE（`^` 单独**恒真**）⇒ 登记「**引用此类命令须取渲染态**」；**(ii)** 工作树另有**未跟踪** `PlanBindExposureTest.java`、`BindExposureHandlerTest.java`（= `T-023` 补测在办，**不在本单范围**）。
  **⇒ 依 `DONE` 判据（授权 + 独立验证）转 `DONE`。**

  **📌（2026-09-23 第 95 轮独立复核登记 · **通过 / 0 缺陷 / 5 观察 ⇒ 转 `DONE`** · 只加不改）** 复核席 `te-verify-t017`（**自撰严判据对账脚本 `reconcile_strict.mjs`（Node **实际求值**常量与拼接式、**非字面 grep**）+ 5 变异 + 3 假绿构造；隔离副本 `/root/verify-t017/`、**共享源树零改动**）。**证据章** = `docs/TEST_RESULT.md` `L6297..6500`（**纯追加已独立证实**：`head -6293` = `83377e3d…` 不变；新全档 `edd8bc88…`/6500）。**复核时点** HEAD `1b15d92 → c0bd24f`（`git diff --name-only` 仅 `docs/**` ⇒ `web/**` 未变，锚点复测一致）。
  **★镜像对账（严判据）= 本席/复核方/产出方三方独立复算一致**：权威源 **82 对 / 60 路径**（`AUTH 17 / CONFIG 17 / PRODUCT 13 / SPOT 15 / PLAN 18 / PING 2`）；**改后 ①已注册−已登记=∅、②已登记−已注册=∅、`NOT_REGISTERED` 不实=0**；**改前同脚本 = 缺 23 条 + 不实 3 条**（与本席第 92 轮清单逐条吻合）。**逐条目视 52 条 key→value 表，无错值/误绑。** 头部 `:14` 写「82 对 / 60 路径」、`:15-16` 写明判据并**逐字引用** `BootstrapAssemblyTest.java:140`。
  **判据 1–9 全过**：锚点/`numstat`；`NOT_REGISTERED` 仅余 `/risk/dashboard`、`/trade/order`（`handler/` 无 risk/trade 目录）；`session.js` 单 hunk 只改 `:8`；**`plan.js` 授权修复恰 3 hunk、`+10/−10`、`:89`→`PLAN.LIST`、`kind` 未动**；`web/` 内 `NOT_REGISTERED.` = **0**、15 处 `HG.routes.*` **全可解析**、`node --check` 24/24；`plan.js:56`/`:111` **未动且确陈旧**（归 `T-020`）；验收 1–4 静态+无头桩全过（**非真实浏览器**）；`E6` rc 0、聚合 `arch-check` rc 0（E1–E7 全 0）。
  **★复核方 3 例「假绿」构造（**如实披露本单对账判据的盲区**，均**不阻断**）**：**`FG1`** 两侧**一致地错**（同一把 `/auth/login` 改成 `/auth/loginx`，Java 与镜像同改）⇒ ①②均 ∅ ⇒ **通过** ⇒ **本单对账证明的是「一致性」而非「正确性」，此为根本极限**；**`FG2`** **键名**改错（`PRICE_IMPORT`→`PRICE_IMP`，值不变）⇒ 通过（**判据不校验键名**）；**`FG3`** 两个合法值**对调**（`PURCHASE`↔`SALES`）⇒ 通过（**不校验键↔值绑定**）。⇒ **`FG2`/`FG3` 已由「逐条目视 52 条 key→value 表」补偿**；**`FG1` 无从补偿（须以权威源为准，不得以「两边一致」当正确性证据）。**
  **★复核方方法论自曝（我方亦已踩）**：其**初版**「通配吞并任意字面」的**宽松口径**得改前缺 **21**（**漏** `/spot/exposure/export`、`/plan/{id}/bind-exposure`）；改**严口径**（通配须对齐 `{id}` 参数位 + 枚举展开）得 **23**、与台账吻合 ⇒ **M4/M5 变异证明严口径严格更强**，最终取严口径。（本席第 94 轮用**字面 grep** 得「19 条缺失」即同类**宽松/错误口径**，当时已自认方法论错误。）
  **观察项处置（本席裁定）**：**`O-1`**（`ROUTES` 对象**只含路径、不含 HTTP 方法**，方法仅在头部散文）⇒ 验收 5 为路径级故**本单合规**，但**对 `T-019`/`T-020`/`T-021` 有误用方法之虞** ⇒ **已在该三任务 §2 增设约束**「须并读头部方法清单或直读 Java 端 `router.register`」；**`O-2`**（对账判据盲区 `FG1/FG2/FG3`）⇒ 见上，**登记为判据强度事实**（`FG2`/`FG3` 有目视补偿、`FG1` 为极限）；**`O-3`**（`plan.js:56`/`:111`）⇒ 归 `T-020`（已在 `T-020` §2 登记）；**`O-4`**（**仓库内存在陈旧 git worktree** `.codebuddy/worktrees/bg-01a0bd8e`（分支 `worktree-bg-01a0bd8e`、commit `9221262`），内含**旧版** `routes.js`/`plan.js`（仍带 `NOT_REGISTERED.PLAN_LIST`）⇒ **全仓 grep（不排除 `.codebuddy/`）会命中 1 处幻影**）⇒ **本席实测确认**（`git worktree list` 可见；`.codebuddy/` 已被 `.gitignore:26` 忽略）⇒ **新增审计纪律：全仓 grep/审计须排除 `.codebuddy/`**；**该 worktree 不删**（属 git 注册工作树、可能为其它会话隔离区，删除有破坏风险）；**`O-5`**（验收 2 列 6 个 `core/*.js` 而实有 9）⇒ 超集、**满足**。
  **本席两处「据实记录」经复核方独立证实**（均针对**报告**而非制品）：`plan.js` 改前 **208 行不实（实测 339）**；`dd55255c…` 确系 `tools/arch-check/README.md` 的 md5。
  **⇒ 依 `DONE` 判据（授权 + 独立验证）转 `DONE`**；**5 观察均不阻断**。

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
- **产出**：`java/src/test/**`、测试报告（**产出路径补登 2026-09-21**：落 `docs/TEST_RESULT.md` §`T-023` 节 —— **该节由编排者落笔，交付方回传正文**）
  **📌（2026-09-23 派发登记 + 三条裁定 · 只加不改）** 状态 = **IN_PROGRESS**（实例 `be-t023`、**唯一写者**：`java/src/test/**` + `java/pom.xml` 测试档）。**现状（另一席只读普查实测）**：`java/src/test` 66 文件 / 60 `*Test.java` / `@Test` 484；陈旧 surefire 快照（`2026-09-22 17:22:52`）**484 / 0 / 0 / 0**（该快照后 `T109EndpointRegistrationTest.java` 被改过 ⇒ **现值须重跑**）；**无 `jacoco`**（覆盖率 ≥80% **当前无法证明**）；**profile 只有 `dm`**（`pom.xml:64-84`，`systemPath=${dm.driver.jar}` 指向**不存在的 `java/lib/`**）；**无任何真库依赖**（无 DM/H2/SQLite；全部为假 JDBC / 假 DAO / 内存求值）。**★裁定 (a)：验收 2 的「DM8 实机」档 —— 环境不可达，改以「等价执行」**（实测：全盘无 `DmJdbcDriver*.jar`、`java/lib` 不存在、`ss -ltnp | grep 5236` 无监听、无 `dmserver`/`dmap` 进程）⇒ 用**内存求值假 JDBC**（本项目已有先例）**真跑** `建表 DDL → INSERT → SELECT` 的**语义**，并**显式标注「未验 DM8 实机」**；**不得**把等价执行写成 DM8 验证（与 `RSK-03`/`R-5`（`BOARD.md` 状态**开放**）一致）。**★裁定 (b)：补 `unit` profile** —— 判据字面要求 `mvn test -Punit` 而 `pom.xml` 无该 profile ⇒ **补一个 `unit` profile**（语义 = 不启用 `dm` 的默认档）使**判据字面可满足**，**不改判据**、**不降级**。**★裁定 (c)：前置口径** —— 形式依赖 `T-012`~`T-015` 登记为「已完成，待独立复核」，其**实现面已在树**（各模块测试已存在）⇒ **构成实质前置，可直接开工**（若实测发现前置实现缺失须报）。
- **备注**：—
  **📌（2026-09-23 交付登记 · 已完成，待独立复核 · 只加不改）** 实例 `be-t023`（**唯一写者**；`java/src/main` **零改动**、`docs/**` **零改动**）。**改动 = 4 文件**：`java/pom.xml` **+61/−0**（`c5b52006…`；**仅新增** `unit`（空语义=默认档）+ `coverage`（jacoco 0.8.12 + surefire `argLine` 追加 `${argLine}`）两 profile，**未动依赖/插件/主源**）；**新增** `dao/testsupport/InMemoryDmEngine.java`（`02f6964a…`/1268）、`dao/M1Dm8EquivalentIntegrationTest.java`（`05792880…`/380，10 例）、`service/ConfigServiceAdminTest.java`（`778e0c7b…`/762，10 例）。**本席独立实测复核锚点全部吻合**：4 文件 md5/行数逐一；`java/src` 树锚 **`c5817cb5…`/338**（基线 `8bf0e1d6…`/335）；`@Test` `grep -rc` 合计 **504**；`java/src/main` `git status` **空**（零改动）；`docs/**` 零改动（`docs/TEST_PLAN/**` 的改动属**并发席 `tm-t022`**、非本单）。**门禁（自报，待独立复核）**：`mvn -B -o -Punit clean test` **504/0/0/0**（62 类；surefire == `@Test` == 504）、`BUILD SUCCESS`、聚合 `arch-check` **rc 0**；五模块**行覆盖率** 配置 **91.6** / 产品 **89.9** / 权限 **87.9** / 计划 **83.1** / 现货 **82.0**（均 ≥80，jacoco）。**★裁定 (a) 的执行口径**：「DM8 实机」档**环境不可达** ⇒ 以**内存假 JDBC 引擎按 SQL 语义求值**真跑「建表（**46 表**）→ **真实 `DmProductDao`** 读写 → 分页查询」的**语义**，并**显式标注「未验 DM8 实机」**（交付方**未**写成 DM8 验证 ✔）；**实机复跑由 `T-024` 承接**。**反退化 2 变异均杀红 + 复位逐字节一致**；**⚠️ 变异 B 触及 `java/src/main/ConfigService.java` 系临时探针**（`.orig` 备份 → 单跑 → 逐字节复位 → md5 `6f656de0…` 校验），**现行主源零残留**（本席 `git status java/src/main` 实测**空** ⇒ 属实）；其理由（「仅测试侧变异只能证『用例对桩承重』」）**成立**，依仓库既定手法**采信**。**★证据口径**：覆盖率/用例数由 jacoco 与 surefire 出具，**非 DM8 实机**；**`dao.impl` 41.2% / Handler 适配层 20–65% 未达 80%** 已如实列出（**非** `T-023` 点名的核心模块 NFR-TEST-1）。**报告正文已由本席落入** `docs/TEST_RESULT.md` 章 # `T-023` 交付报告（**纯追加已独立证实**：`head -5775` = `1feb4fbd…` 不变；新全档 `57f9ff5d…`/5979）。**未自评 `DONE`**。
  **📌（2026-09-23 第 92 轮独立复核登记 · 退回补测 · 只加不改）** 复核席 `te-verify-t023`（**自撰变异 M-A~M-D / P-cond / P-noncond / P-rollback / P-subq / P-JOIN / P-agg / H1 / H2、隔离副本 `/root/t023v/`（布局 `<root>/{docs,java,tools,proto,web,cpp/src}`、`TMPDIR=/root`）**）。**结论 = 有条件通过 / 0 阻断缺陷 / 1 缺陷（中）/ 4 观察**；**证据章** = `docs/TEST_RESULT.md` `L6100..6293`（**纯追加已独立证实**：`head -6099` = `4e7e8479…` 不变；新全档 `83377e3d…`/6293）。**复核时点** `HEAD = 1b15d92`；`git diff efc2a59 HEAD -- java/src java/pom.xml` = **空** ⇒ 交付面无后续改动（本席已复测确认）。**复核方独立成立项**：4 文件锚点/行数逐一吻合、`java/src` 树锚 `c5817cb5…`/338（基线 335）、`numstat` `61/0,380/0,1268/0,762/0`；`-Punit clean test` **504/0/0/0 + BUILD SUCCESS**、surefire XML **62 类/504 例** == `@Test` == 504 == `*Test.java` 62（**无注释吞并**）、`arch-check` rc 0（E1–E7 全 0）、`E5` rc 0；**jacoco 逐值复算吻合**（LINE 91.6/89.9/87.9/83.1/82.0，INSTR/BRANCH 亦吻合 ⇒ **未混淆指标**）；`unit` profile 经 pom diff **真等价默认档**（仅 `<properties>`、无依赖/插件）；**引擎四强制点各只杀对应 1 例 + 复位**、**46 表 = 20+8+10+8**、**110+54=164 = 全部 DDL CHECK**、不覆盖清单**逐项探针**；验收 3 三类断言**含具名原因**且孤儿**含反向对照**；全树 `.orig/.bak/.rej/MUTATION` **0 命中**、`ConfigService.java` = `6f656de0…`；范围合规（`git show --name-only efc2a59` = 仅 4 文件）；**DM8 诚实性**：驱动 0 命中/`java/lib` 无/5236 无监听 ⇒ **不可达成立**，报告 `DM8` 6 处**全为「未验/不可达」、无 overclaim**。**★缺陷 `TE-T023-001`（中 · 覆盖盲区；本席已独立复现）**：`java/src/main/java/com/hedgeguard/service/PlanService.java:623` 的 **`bindExposure`**（端点 `POST /api/v1/plan/{id}/bind-exposure`，`PlanRoutes`→`BindExposureHandler:55`）—— **`java/src/test` 引用数 = 0**（本席实测）、**jacoco 行覆盖 0%**；复核方在隔离副本内把 **`:661`** 的 `if (bind.coveredQty.compareTo(uncovered) > 0)`（= `FR-PLAN-19` **验收 3**「覆盖量 ≤ 未覆盖量」核心规则）改为 `… && false` ⇒ **`mvn -B -o -Punit test` = 504/0/0/0 全绿**；**对照**：对同形的 **`:552`**（`createPlan` 内）施同法 ⇒ **红 1**（`PlanServiceTest.createPlanRules`）。⇒ **一条真实核心规则缺陷可全绿逃逸**；原报告 §5 未披露该零覆盖方法。**★口径裁定（本席）= 严格口径 ⇒ 退回补测**：复核方请择 **(a) 量化口径**（五模块行覆盖率 ≥80% ⇒ 不阻断）vs **(b) 严格口径**（验收 1「JUnit 5 单测**覆盖…计划…核心逻辑**」⇒ 零覆盖 ⇒ 退回）。**裁定 (b)**，理由：**验收 1 的主语是「覆盖核心逻辑」，`≥80%` 是附加量化约束、非唯一判据**；**可被单行改动弄坏却 504/0/0/0 全绿逃逸的核心规则不能算「已覆盖」**（本项目纪律 **「全绿 ≠ 无缺陷」**）。**观察项处置（本席裁定）**：`OBS-1`（**`-Pcoverage` 非确定性**：2 跑 1 红，`WsAcceptanceTest.e2` P95=236ms 在 jacoco 开销下越界）⇒ **须在报告披露**；该用例越界本身归 `T-028`、**本单不改**（另记观察）；`OBS-2`（**产出方变异 B 在共享源树就地改 `src/main/ConfigService.java`**）⇒ **本席更正先前表态**：我曾说「依仓库既定手法**采信**」，**太宽** —— 项目纪律为「**变异/探针严禁在共享源树就地做**」（瞬时变异态会污染并发读者快照）；其**主动披露 + 复位零残留**使结果仍可采信，但**手法不合规**，本轮及今后**一律在隔离副本内做**；`OBS-3`（「`E5` 判红 **6** 处」）⇒ 复核方复现为 **5 处** ⇒ **订正为实测值**（方向/结论不变）；`OBS-4`（引擎「不覆盖」清单把 **JOIN/聚合** 与 **子查询** 并列）⇒ 复核方探针实测 **JOIN/聚合「静默返回错值」（返回 3 行而非正确 0/1 行）**、**子查询是 loud 抛** ⇒ **性质不同、披露须分开表述**（本单未触发）。**复核方自报插曲（据实记录）**：其首次 `rm -rf` 清理时**误删隔离副本内 2 个既有文件**（`testsupport/{FakeDao,UnsupportedBaseDao}.java`），**已即时从共享树复原**、副本树锚重回 `c5817cb5…`/338；**共享树全程未受影响**（`git status` 仅 `TEST_RESULT.md`）⇒ **处置正确（自报 + 复原 + 校验）**，不影响结论。
  **📌（2026-09-23 第 95 轮 · 退修补测改由新实例执行 · 只加不改）** 本席曾向席位 `be-t023` 投递补测指令（补 `bindExposure` 覆盖 + 报告四项披露），但**该席位实例已下线、指令成为死信**（本席**实测复核**：`ConfigServiceAdminTest.java` 仍 `778e0c7b…`、工作区干净、无 `mvn` 进程）⇒ **改由新实例 `be-t023b` 执行**（**用新名以避免重复触发**，理由同 `T-022` 处注）。**增补纪律（供本轮实施方遵守）**：**变异/探针一律在隔离副本内做，共享源树只做正式交付写入**。

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
  **📌（2026-09-23 冻结点重登记 · 只加不改）** `T-128`（统一 CTP 头名变量源）经授权改动了本任务判据口径所在目录 `cpp/ctp_smoke/`。**`T-128` 独立复核 `te-verify-t128` 的连带结论**：**判据口径与计数未变**（`run_probe.sh` diff 空；改动前/后 **`PASS=12 FAIL=0`** 逐条相同）而**制品 md5 已变**（`ctp_smoke/CMakeLists.txt` `f78a7516… → daf53783…`）⇒ **「冻结点前移」对制品成立、对判据不成立** ⇒ 处置 = **重登记冻结点 md5（非重跑）**。**现行交付域锚**：`find cpp/cmake cpp/ctp_smoke -type f -not -path '*/build*' | LC_ALL=C sort | xargs md5sum | md5sum` = `654cf2f2997084d29b194e26bdb8afca` / 9 文件（**口径须排除 `build*`**）。
- **备注**：**M2 首周探路任务**。CTPmini 同栈已跑通，概率低-中。**已提前投递**（2026-09-20，工单 `T-027-task.md`）；**2026-09-20 探路通过 → `DONE`**（状态列见 §1，验收证据见 `BOARD.md` §2 `DONE` 区）；M2 门禁与「探路→汇签（`T-030`）」约束不变。
  **📌（2026-09-21 状态收口 · 只加不改）** 本任务现行状态 = **`DONE`（判据 = 授权 + 独立验证）** —— **独立复核通过 + `T-030` 汇签结论通过**。
  **📌（2026-09-21 `T-030` 汇签登记 · 只加不改）** `PASS` 计数订正：验收 4 原载 `PASS=9`（`B7b`/`B7c` 两条断言后加未回写）⇒ 本轮回写为 **`PASS=11`**。依据 = `docs/gates/M2-recon-signoff/SIGN-OFF.md` §3 条件 2。
  **📌（2026-09-22 因 CTP 基线变更退回重做 · 只加不改）** **触发**：用户裁定 **交付基线 = 官方 CTP、且 `CTP Mini` 不作本机开发替代**（`CH-015`）⇒ 本任务**原结论的适用范围失效**。**原结论原文**：真实 CTP SDK `V1.7.7` **合并版**头文件（`ThostFtdcUserApi.h`）C++11 编译 + 链接通过、`PASS=11`（系统 10 / 回退 2？见 §1 计数口径 / 降级 1，**以 `SIGN-OFF` §3 为准**）；该结论系在 `/home/ctpmini/ctpmini_admin/ctp_sdk`（**SFIT `CTP Mini`**，`.so` 含 `_sfit_enH` 标记、`GetApiVersion()==V1.7.7`）取得。**用户 2026-09-22 已提供官方 SDK** ⇒ **现于官方 CTP v6.7.13 上重做**（实例 `be-ctp-official`，**只读探路**：编译 + 链接 smoke TU，`-std=c++11 -Wall -Wextra -Werror`，逐项 `PASS` 计数并与 Mini 基线的差异对照）。**本席独立验明 SDK 为官方（非 SFIT）**：两个 `.so` 的 `strings -a | grep -c '_sfit_enH'` **均为 0**，对照 `CTP Mini` 的 `libthostuserapi.so` = **2**；且为**分离式 API**（`class CThostFtdcTraderApi` + `CreateFtdcTraderApi(...)`、`class CThostFtdcMdApi` + `CreateFtdcMdApi(...)`，而非合并的 `CThostFtdcUserApi`）。**SDK 冻结点锚（本席实测，以实时为准）**：`sdk/v6.7.13_20260225_api_traderapi_linux64.tar` `6b44c501354f8dbc1163f984257945eb`、`..._mduserapi_linux64.tar` `75ee2189927ae9a98a7cec53a8f92e25`；解包后 `ThostFtdcTraderApi.h` 52511B、`ThostFtdcMdApi.h` 5842B、两份共享头 `ThostFtdcUserApiStruct.h` `224697e18f6e1f6374ab91c9cf92aed4` / `ThostFtdcUserApiDataType.h` `87a1c784dd1a5b3d5e8710e513ce54c1`（**两包内逐字节相同**）、`thosttraderapi_se.so` 5757703B、`thostmduserapi_se.so` 5091252B；均 `ELF 64-bit x86-64`。**性质**：本项**属重做、非修订**（同一验收、不同基线）⇒ 依 `DONE` 判据**退回 `IN_PROGRESS`**；**重做结论到达前 `RSK-02` 对交付基线维持「未排除」**，`T-030` 汇签中该条的适用范围随之收窄。
  **📌（2026-09-22 `T-027` 官方基线重做 · 已完成，待独立复核 · 只加不改）** 实例 `be-ctp-official`（**只读**，共享树零写入；报告落 `/root/ctp27/T027_T033_REPORT.md`）。**结论：官方分离式 API 在 `-std=c++11` 下可编译 / 可链接 / 可运行 ⇒ `PASS=11 / FAIL=0`**（同一 TU 同时 include 两端头、派生两套 SPI 并 `override`、运行期建对象走完整生命周期、两个 `.so` 均载入）。**★关键实测**：① 两包各自的 `ThostFtdcUserApiStruct.h`/`ThostFtdcUserApiDataType.h` **逐字节相同**（`224697e1…`/`87a1c784…`）⇒ **可安全共享**，`-H` 实证 TU 只拉入 1 份（include-guard 生效）。② 官方头在 `-Wextra` 下**不干净：602 条 `-Wunused-parameter`**（全来自 SPI 基类具名未用参内联虚）⇒ **规避口径 = SDK 头按 `-isystem` 引入**（**正是本项目既有口径**，`ctp_adapter/CMakeLists.txt:40`）；**非 C++11 不兼容**，且 `CTP Mini` 同形 **509** 条 ⇒ **两基线共有、非官方新增**。③ **零 C++11 不兼容构造**；四头**零 `std::`/零 STL include** ⇒ `_GLIBCXX_USE_CXX11_ABI` 在本接口边界**无关**。④ **官方 `.so` 无 `lib` 前缀、无 SONAME**（本席 `readelf -d` 独立确认）⇒ `-lthosttraderapi_se` **失败**，须全路径或 `-l:`，运行期须 rpath/`LD_LIBRARY_PATH`。⑤ **运行期新发现**：`Release()` **必须在 `Init()` 之后**，**`Release()`-before-`Init()` 段错误（rc=139）**（Trader/Md 均然；`CTP Mini` 同形 ⇒ 亦是**两基线共有**）。**与 Mini 的 7 项差异（上轮「已通过」在官方基线上不再成立）**：`D1` 官方**无合并版 `ThostFtdcUserApi.h`**（分离式）⇒ 上轮 `PASS=11` 系 **Mini 专属结论**；`D2` `GetApiVersion()==V1.7.7` 不再成立（官方 `v6.7.13_20260225 …`）；`D3` `Init` 由 `Init(bool)` → **`Init()` 无参**；`D4` `CreateFtdcMdApi` **多第 4 参 `bIsProductionMode=true`**；`D5` `CreateFtdcUserApi(const char*)` → `CreateFtdcTraderApi(const char*, bool bIsProductionMode=true)`；`D6` 库命名/布局：单目录 + `lib` 前缀（`-l` 可用）→ **双目录 + 无 `lib` 前缀**；`D7` 导出宏 `USER_API_DLL_EXPORT` → `TRADER_API_EXPORT`/`MD_API_EXPORT`（Linux 下均为空 ⇒ 无影响）。**未经文档化的信息项（不建议使用）**：官方 `thosttraderapi_se.so` **仍导出**合并版 `CThostFtdcUserApi::*` 符号 —— 本席独立 `nm -D --defined-only | grep -c CThostFtdcUserApi` = **1074 命中**（**头却不存在**）⇒ 存在旁路、**不得依赖**。**待裁定（不阻塞）**：`U1` `bIsProductionMode` 默认 `true`（=生产）⇒ 若联调接**评测前置**会连不上（依 `sdk/6.7.13_API接口说明.md` §1.2 `:875-885`）⇒ **须可配置**，其默认取值属**部署/业务口径**（已随 `T-033` 派单下达「须可配置」）。**SDK 冻结点锚（本席独立实测；⚠️ 该口径为「路径清单级」，**前缀不同则值不同**，故并列三值）**：**绝对路径** `find /root/myspace/hedge-guard/sdk/v6.7.13_20260225_api -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` = `a8f76e4868258e4436dabe0e6fe19a3d`（**与产出方声明值一致**）；**仓库根相对路径**同命令 = `6459430621174b15cd747287741e0b26`；**仅哈希内容（无路径前缀，最可复现）** = `8f6a9fc4cff40eb196ffd35d40e34afb`；**均 12 文件**（该「前缀致值不同」现象已由本席实测复现，非矛盾）。

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
- **状态**：**DONE**（判据 = **授权 + 独立验证**）。**⚠️ 状态订正（2026-09-22 编排者）**：本条原标 `TODO`，而其**制品早已交付**（C++ 骨架 + `arch-check` 聚合入口 + CI 接线）⇒ 属**账实背离**，现订正。**依据链**：① 独立复核 `te-verify-t031` = **有条件通过**，其 3 处缺陷（探路内容锚不重现 / `stub`·`fetch` 非 6/6 / link 仅 protobuf）经查**均为「回执文本级」、非制品缺陷**；② 该 3 处的事实性内容已由 `T-120` 的 `README.md` 实测订正（三轨数字表 + 链接证据行）并经 `te-verify-t120` **独立复检通过**；③ 其制品已被后续**多轮独立复核实际覆盖**：`te-verify-t031` 三轨 `ctest`（`6/6`·`5/5`·`6/6`）、`te-verify-t120` 聚合入口**实扫**（`E4` `N=6`、非 SKIP）、`te-verify-t032`/`te-verify-proto`/`te-verify-proto2` 的 C++ 全栈 `ctest`。**披露**：**未单独跑一次「`T-031` delta 复检」**（因该 3 处非制品级、且制品已由上述复核覆盖）—— 若事后认为仍需一次专项复检，请指示。
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
- **状态**：**IN_PROGRESS**（**部分交付、在work中**；2026-09-21 由 `T-030` 汇签解锁 `BLOCKED` → `TODO`；2026-09-22 首批交付后转本状态）。**已交付（schema-free 全层，全绿）**：`cpp/src/hgcp/**` —— 帧层（32B 帧头 + `CRC32` 尾 + 大端 + 1 MiB 上限；含坏 MAGIC / 坏 VER / `RESERVED`≠0 / CRC 错 / 超限五类反例与 `kNeedMore` 半包不消费）、鉴权（SHA-256 过 NIST 三向量 + 常量时间**结构性**证据 + **令牌零日志** `grep`=0）、心跳/退避/补数（`timeout_ms()==3000 ≤ 5000`、退避 `500/1000/2000/4000/8000` 封顶、环形 `65536`、1200 帧 → `500+500+200`）、端点（回环白名单**精确匹配**、非回环拒绝启动、**重复连接拒 `0x0302`**）、帧信封 + **不透明 payload 逐字节透传**、**流式切帧**（粘包 / 半包 / 逐字节喂）、**`PayloadCodec` 可替换接缝**（`OpaquePayloadCodec`）、**真实回环 socket E2E**（`transport_test` 742 检查 / 0 失败）。**门禁**：构建 `-Wall -Wextra -Werror` **零告警**、`ctest` **13/13**、`bash tools/arch-check/arch-check.sh` 聚合 **0**（E1~E6 各 0）；**变异承重 4 例**（含 `FrameStreamReader` 漏 `off_ += consumed` ⇒ envelope 7 红）**全杀 + 复位**。**冻结点**：`find cpp/src -type f | LC_ALL=C sort | xargs md5sum | md5sum` = `571327138901b535c03609b532ea019d` / **50 文件**（**以实时为准**）。**⚠️ 余项（单列，不得并入"已完成"）**：`S-1` `proto/hgcp.proto` 本体（字段类型 + 字段号 **0/40**）、`S-2` 元协议 9 消息 payload 语义编解码、`S-3` 带 payload 帧的握手/心跳/补数 E2E、`S-4` 验收 6（`proto_smoke` 指向真实 schema 重跑）、`S-5` 控制 17 + 事件 14 字段级定义 —— **全部待 `CH-013`**（用户 2026-09-22 **已批准、附条件**：须先并入 `G-1`/`G-6` 后方定稿）。 **📌（2026-09-22 `CH-013` 定稿并生效 ⇒ `S-1`~`S-5` 解除阻塞）** `docs/ARCHITECTURE.md` 升至 **v1.5.15**（`c38fca066eb351c08aa990e854cd763d` / **3216 行**）新增 **§4.2.8「字段级 schema」**（40 条消息 / **214 顶层字段号**；`I-1`~`I-4` 与 `G-1`/`G-6`/`G-8` 全部定稿）；**转录依据 = §4.2.8**（非附录 C）。⇒ 已派 `#cpp`（实例 `be-t032d`）执行：**新建 `proto/hgcp.proto` 依 §4.2.8 逐字转录** + **`PbPayloadCodec` 替换 `OpaquePayloadCodec`**（元协议 9 消息 payload 编解码，接缝仍 `TransportPolicy` 单一符号、帧层零改动）+ **补齐 `O-1` 延伸**（线上 ERROR 帧须带 code + `test_not_handshaken_rejected` 增断言）+ **验收 6**（`cpp/proto_smoke` 指向真实 schema，以 `-std=c++11 -Wall -Wextra -Werror` 重跑）；`hgcp_smoke.proto` **保留 + 加注「已被 `CH-013` 取代」**；**`RSK-01` 闭环仍待该项落地**（`SIGN-OFF §3` 条件 1 义务**不变**）。 **📌（2026-09-22 转录批交付 · 自报待独立复核）** 实施方（实例 `be-t032d`，唯一写者）自报完成：**① `proto/hgcp.proto` 新建**（**801 行** / `860e401d01225f7532bbf7ec7acf291f`）—— **50 message**（40 顶层 + 10 复合）+ **28 enum**；**顶层字段号合计 214**（38/77/99）经**脚本机械核验**；**② `PbPayloadCodec` 替换 `OpaquePayloadCodec`**（40 消息可编解码，元协议 9 条往返；接缝仍 `TransportPolicy` 单一符号）；**③ `O-1` 延伸落地**（线上 `MSG_ERROR.err_code` = **`0x0107`** + `test_not_handshaken_rejected` 增断言 + `test_pb_transport_policy_bodies`）；**④ 验收 6 达成**（`proto_smoke` 另从**真实 schema** 生成，`-std=c++11 -Wall -Wextra -Werror` 零告警，`--print-config` 报 `__cplusplus=201103`/`is_cxx11=YES`）。**门禁自报**：构建零告警 / `ctest` **13/13** / `arch-check` 聚合 **0**（E4 实扫 `N=6`）；**变异 4 例（隔离副本 `/root/t032d-mut` 内，共享树零变异）全杀 + 复位**；**不变量 40 / 62 / `VER 0x0100` / `FR 184` 未动**。**冻结点**：`find cpp/src -type f \| LC_ALL=C sort \| xargs md5sum \| md5sum` = `5b0d6b21e0e74210ceb77839ec6b41eb` / **54 文件**（基线 `e29a4979…`/52，+2）。 **⚠️ 本批四处未决（如实单列，不得并入"已完成"）**：**(1) `I-2` 行为面未实现** —— 取值面已一致（用 `0x0308`），但 §4.2.8.8 的「**不中断连接**」**未实现**：`server_loop` 对帧内错误（含 `LEN` > 1 MiB）仍 `close_conn()` 且不回发 `0x0308`。**实施方理由**（我判成立）：`LEN` 本身超限 ⇒ 接收侧**无法知道跳过多少字节** ⇒ **帧同步失序**，无边界的"不中断"**不可实现**。⇒ **已请架构席裁定权威落地形态**（是否允许"回 `0x0308` 后关闭"、或定义重同步规则；并判性质与是否需再提请用户）。**(2) `MSG_RESUME_DATA` body 未接 schema** —— `send_resume` 仍为"批内原始帧字节顺序拼接"，未按 `MsgResumeData{from_seq,to_seq,is_last,frames}` 编码（codec 已支持）⇒ **跨侧（`T-042`）格式须对齐**，**待续做**。**(3) `T032_GAP_ANALYSIS.md §5`** 的 `S-1`~`S-5`「阻塞」表述**保留历史态未改**（避免超范围改文档）⇒ 编排者裁定：**保留 + 加注"已被 `§4.2.8` 取代"**（与 `hgcp_smoke.proto` 同处置，**不回收**）。**(4) `§4.2.8` 未规定项的工程自择两处**（**如实披露、未擅改**）：proto **包名 `hgcp.v1`**（沿用既有 `hgcp_smoke.proto`）、**枚举值名加枚举名前缀 + `*_UNSET` → `<ENUM>_UNSET`** ⇒ **已请架构席确认**（若需改则须判是否触冻结契约）。 **📌 独立复核状态**：本批**自报未独立验证** ⇒ 已派 `te-verify-proto` 专核**「`.proto` 是否逐字转录 §4.2.8」**（机械化集合差 + 枚举逐值 + 三元组 + 214 自洽），**其结论到达前本批不计入任何"通过"**。 **📌（2026-09-22 转录批独立复核 = 通过 / 0 阻断缺陷）** `te-verify-proto` 以**机械化集合差**核验 `proto/hgcp.proto` ↔ `§4.2.8`：**消息集 / 字段三元组（名↔号↔类型）/ 枚举逐值 / 必填性 四类差集全空**；`214 = 38+77+99`、复合 **66**、**28 枚举**、`MsgType` **41**（`UNSET` + 40 冻结码逐值一致）、字段号 `1..n` **连续无跳号**；**自建 84 检查探针**证元协议 9 条往返；**门禁** = 构建零告警 / `ctest` **13/13（无 CTP）· 14/14（有 CTP）** / `arch-check` 聚合 **0**（`E4` 实扫 `N=6`、非 SKIP）；**验收 6** 真实生成证据（`generated_real/hgcp.pb.{h,cc}` + 目标文件 + 三模式输出）；**自设 5 例变异全杀**（**M1/M2 打在 `.proto` → 生成码链路上**）+ 复位证明。 **⚠️ 关键覆盖缺口（`O-2`，本批最有价值的观察）**：把 `MsgError.rule_id` 字段号 `4→7`（**合法但不符 `§4.2.8`**）⇒ **14 个用例全绿** ⇒ **测试套件完全不锚定字段号**；而字段号**不可回收** ⇒ 当前**唯一**保证字段号正确的机制是"这一次的人工机械化差集"，**非持续性**。⇒ **编排者裁定立号 `T-121`**（「`§4.2.8` ↔ `proto/hgcp.proto` 一致性判据脚本，CI 可跑」，落 `tools/arch-check/` 系），**登记于本轮台账回写**。 **📌（2026-09-22 `I-2` 行为面收口已施工 · 现行文本缺口闭合）** 依 `tl-i2-resync` 的拆分：**(2a) 补「尽力回 `0x0308`」= 对**现行生效**文本（`CH-013` 正文含"回 `0x0308` + 提示分片"）的验收缺口，不待 `CH-014`** ⇒ 已由 `be-t032d` **施工闭合**：`TransportPolicy` 增 `frame_too_large_body()`；`server_loop` 帧超限 ⇒ `send_frame(kMsgError, kFlagIsError, policy_->frame_too_large_body())`（`err_code=0x0308`、`err_msg` 载"use fragmentation"）**随后** `close_conn()`（照 `:138` 心跳判死先例：写失败不阻塞、不改变关闭决定）；**其余帧内错误**（坏 MAGIC / CRC 错 / 版本错 / `RESERVED`≠0）**维持直接关闭、不回码**（与 `§4.3.2` 各码处置一致，**未顺手都回码**）；新增用例 `test_frame_too_large_replies_0x0308`；**并用三条可复现理由改正了原注释论据****已废弃**（`cpp/**`/`proto/**`/`tools/**`/`java/**` **实测 0 命中**；`docs/**` 内存在"引述 + 随即废弃/更正"类留痕（**不列计数** —— 该计数会被"写它"这一动作本身改变；**以实时 `grep` 为准**））。**(2b) 「不中断连接」的例外化 = `CH-014`**（**用户已批准** 2026-09-22）⇒ 由架构席落**生效态**（`ARCHITECTURE.md` → v1.5.16）。**新冻结点**：`find cpp/src -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`97524bde8c4dbebcfa2c67c8b941b855` / 54 文件**（前值 `5b0d6b21…` 已废）。**残余（不变）**：`MSG_RESUME_DATA` body 仍未按 `MsgResumeData` 编码（跨侧 `T-042` 须对齐）⇒ 待续做。**⚠️ 待返工项**：**事件循环装配**（`accept` → 读循环 → 心跳定时器 → 退避重连 → 补数装配 → 单连接治理）当前**仅存在于测试装配**（`tests/transport_test.cc`）⇒ 须**下沉到 `src/` 生产侧**、`transport_test` 退化为驱动器（**责任在编排者**：其裁「保留 A、删除 B」时删掉的 B 之 `server.{h,cc}` 即该装配）。 **📌（2026-09-22 已落 · 待独立复核）** 该返工**已完成**：新增生产侧 `src/server_loop.{h,cc}`（装配唯一归属）、`tests/transport_test.cc` 退化为驱动器（542 行）、`tcp.h` 补「配置级 vs 原语」校验层级注释；生产方自报 构建 `-Werror` 零告警 / `ctest` 13/13 / `arch-check` 聚合 0 / **变异 5 例全杀 + 复位**（含 1 例打在新增的 `server_loop.cc` 上）；冻结点 `find cpp/src -type f | LC_ALL=C sort | xargs md5sum | md5sum` = `e29a4979512503a3ae213e2f3a0250af` / **52 文件**。**独立复核进行中**（生产方自报不作门禁结论）。 **📌（2026-09-22 独立复核 = 通过 · **回执级留痕**）** `te-verify-t032` 判 **通过（0 阻断缺陷）**：10 项判据**逐项实测吻合**；**装配调用点唯一归属 `src/server_loop.cc`**（`accept_one`:75 / `on_new_connection`:80 / `on_connected`:87 / `on_tick`:136 / `build_resume`:161），`tests/` **无** `TestServer`/`ServerLoop` 类定义；CMake 源列表↔磁盘 **12=12**、无 `.orig/.bak/.rej`；`tcp.h` 校验层级注释在位；E2E **742/0** 复现逐字一致；构建零告警、`ctest` **13/13**、`arch-check` 聚合 **0**（**`E4` 实扫 `N=6`、非 `SKIP`**）；**自设 4 例变异全杀**（**M1–M3 均打在新增的 `server_loop.cc`** ⇒ 证新装配**承重且被真实 E2E 驱动**）+ 复位证明 + **clean 重编译 13/13** + `MUTATION` 残留扫描干净（仅既有第三方 `_deps/protobuf-src/**`）；`0x0104`/`0x0308` **显式标注「待 `CH-013` 裁定」**（未擅定案）。**两处纠正编排者**：① 「逐字节喂」在 `session_test.cc:51-59`（非 `transport_test`）；② **「拒 `0x0107`」只到帧级** —— 线上 ERROR 帧 **body 为空、不含 code**（`0x0107` 仅见于注释），`test_not_handshaken_rejected` 只断言 `type` + `IS_ERROR` 位。**⚠️ 已知限制（挂 `CH-013` 验收延伸）**：`CH-013` 定稿、`PbPayloadCodec` 接入后**必须补齐线上 ERROR 帧 code 并加断言**。**⚠️ 可选清理项（裁定不返工）**：`wait_readable` 在 `server_loop.cc:18-31` 与 `transport_test.cc:85-98` 各一份（消费者不同；去重须提为 `tcp.h` 公共原语）。**✅ 证据章状态（2026-09-22 已落 · **更正上文**）**：本轮独立复核证据**已落仓库** = `docs/TEST_RESULT.md` 章 `# T-032（首批 + 装配下沉返工）独立复核证据（事后补录，2026-09-22）`（`:2571` 起，含 `T032.0`~`T032.9` **共 10 节**；文件 `a13f3aa36aed20cc189722771e91d9c2` / **2682 行**；**纯追加已证** —— `head -2569 | md5sum` = 追加前基线 `c5d6f38f992c9d9eae3017c8abe25bc1`）⇒ **本节结论现为「仓库可查」**。（原登记为「回执级、待 `T-032` 收口补录」，**已被本次落盘取代**；按纪律补正，不留陈旧现行态。）**⚠️ `docs/**` 非本任务改动的归属**（复核方已核、不判污染）：`ARCHITECTURE.md` 附录 C = 架构席；`BOARD.md`/`TASKS.md`/`REQUIREMENTS/INDEX.md` = 编排者台账。**⚠️ 冲突留痕**：本任务曾发生**同席位两实例并写**（根因 = 编排者对同一席位**连发序贯指令 ⇒ 重复派单**），已由编排者裁定保留 `tcp`/`session` 集合、删除 `server*`/`socket_util*` **死代码 6 文件**、并另起**唯一写者**收口；**ODR 已清零**（`send_all` 仅 `src/tcp.cc:43` 一份定义）。**⚠️ 待裁定项（非阻塞）**：`I-2` 帧超限码双记（`0x0104` 兼含 vs `0x0308` 专码）经用户裁定**并入 `CH-013`**；实现侧暂取 `0x0308` 并已**显式标注「待 `CH-013` 裁定」**、未静默。
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

  **📌（2026-09-22 第 48 轮派发登记 · 只加不改）** **`T-032` 残余收口派发**（实例 `be-t032res`，唯一写者，产出面 `cpp/src/hgcp/**`）。**范围收窄为唯一残余项**：补数消息 `MSG_RESUME_DATA` 的 **body 未按 schema 编码** —— 现状实测 `server_loop.cc:173` 的 `send_resume` 仍为「批内原始帧字节顺序拼接」、该文件 `:181` 有**明载注释**指出字段级编码未做；schema 权威源 = `proto/hgcp.proto:377` 的 `MsgResumeData`；**解码侧已就绪**（`payload_codec.cc:41`）⇒ 只需补**发送侧编码**，接缝仍走既有 codec（帧层零改动）。**其余三项残余不在本单范围**，只要求**只读确认并回报**：`(1)` `I-2` 是否已按 `CH-014`（**回 `0x0308` 后关闭**）实现；`(3)`/`(4)` 的文档加注与两处工程自择是否已由架构席确认。**须产出「跨侧格式契约注记」**（字段名/号/类型/语义/边界，基于 proto 与实测）供转 `T-042`（Java 消费侧）。**判据**：`E7` 不得变红；反退化 ≥2 条（退回原始拼接 ⇒ 必红；`is_last` 语义含「恰好 500 帧」边界错置 ⇒ 必红）；**编码后同 codec 解码逐字段往返相等**。**优先级理由**：`T-039` 依赖 `T-032` ⇒ 本项在**关键路径**上。

  **📌（2026-09-22 残余收口交付登记 · 只加不改）** 实施方 `be-t032res` 交付**唯一残余项**：`MSG_RESUME_DATA` body 按 schema 编码（`+253/−8`，均在 `cpp/src/hgcp/**`）——`session.h` 的 `TransportPolicy` 增纯虚 `resume_data_body(from,to,is_last,frames)`（`+12`）、`session.cc` Empty 空实现（`+5`）、`pb_transport_policy.{h,cc}` 实现＝`pbv1::MsgResumeData` + `PbPayloadCodec::encode`（`+5/+25`）、`server_loop.cc:179-197` `send_resume` 重写（逐批取 frames ⇒ 批首/末 SEQ ⇒ `is_last=最后一批`）、`transport_test.cc` `+193`（新用例 `test_resume_data_body_schema`：单批 5 帧 E2E / 501 帧→2 批 E2E / 500·600 帧 / 往返含空帧·`0x00`·300B）。
  **门禁（生产者自报，待独立复核）**：构建 **0 warning/0 error**、4 个 `hgcp` TU 均带 `-std=c++11 -Wall -Wextra -Werror`（`compile_commands.json` 证）；`ctest` **31/31**、`transport_test` **2970 checks / 0 fail**；聚合 **rc 0**（`E1`–`E7` 全 `0`，`E7` = 0）；**变异 2 例全杀**（① 退回原始拼接 ⇒ `:671` decode 失败 rc=1；② `is_last` 反转 `(i+1<size)` ⇒ `:674/:721/:726` 三处失败，**含 500/501 边界双向**）+ 复位 `rm -rf build` clean 重编译 ⇒ rc 0、2970/0、0 warning；隔离副本 `/root/t032res-mut`（含 `docs/cpp/tools/proto/java`），**共享树变异残留 = 0**。**往返验证**：编码后同 codec 解码 4 字段全等。
  **断言订正（仅注释，未放宽）**：`transport_test.cc:602-606`（原 `:591`）注释「body＝批内帧字节拼接」→「经 `TransportPolicy` 接缝注入（本用例 TestPolicy 不透明占位）」+ 加注「原注释锁定的是『body 未编码』中间态＝缺陷」；**断言 `:612` 未改、未放宽**；TestPolicy 覆写系新增纯虚的必需实现。
  **只读确认（只报不改）**：**(1) `I-2` 已实现且与 `CH-014` 一致**（`server_loop.cc:137-141` 帧超限 ⇒ `send_frame(0x0308 body)` **随后** `close_conn()`）；**(2)** `T032_GAP_ANALYSIS §5` 的 `S-1`~`S-5`「卡点/阻塞」**未加注**（全文 `grep 取代` = 0，`:175`/`:189` 仍「待 `CH-013`」）⇒ 属**低优先文档项**，**另议**；**(4)** 枚举值名前缀**已确认**（`§4.2.8.7` 值名约定 + `ComponentRole` 唯一例外），但 **proto 包名 `hgcp.v1` 未在 `§4.2.8` 权威正文确认**（仅附录 C、标注非权威；`E7` 显式不覆盖 `package`）。
  **⚠️ 跨侧格式契约注记（供转 `T-042`，Java 消费侧）**：`MsgResumeData` = type `0x0009`，`FLAGS.bit3 IS_RESUME_DATA=1`。字段：`from_seq`(1:uint64·必) / `to_seq`(2:uint64·必) / `is_last`(3:bool·必) / `frames`(4:repeated bytes·条件〔`is_last=false` 时非空〕)。**`frames[k]` = 完整 wire 帧（含 `MAGIC`）**，头 32B 全大端 ⇒ Java **可逐元素直喂 `FrameStreamReader`**（实测复解 SEQ/type 正确）。`from/to` = 本批**首/末帧 SEQ**（单批 5 帧 ⇒ 2/6；501 帧两批 ⇒ 2/501、502/502）。`is_last` = 最后一批；边界：**恰 500 帧 ⇒ 1 批 `true`**、**501 ⇒ 2 批 `false/true`**；**本实现不产生空批**（`§4.2.8.5`「末批可为空」不发生，Java 仍宜容忍 `is_last=true` + 空 `frames`）；**不产生 `from > to`**。
  **⚠️ 三项未决（单列）**：(a) `from/to` **权威语义未定义**（取「本批首/末帧 SEQ」；替代解＝整体区间 `[last+1, last_sent]`）⇒ **已请架构席裁定、并须与 `T-042` 跨侧对齐**；(b) `TransportPolicy` **接口新增纯虚** ⇒ 须架构席确认接缝扩张可接受/是否需登记；(c) `§5` 加注**不在本单范围**。**独立复核待派（负载排队）。**
  **📌（2026-09-22 三项未决只读裁定 · 架构席 `tl-t032-ruling` · 只加不改）** 锚点实测 `ARCHITECTURE.md` `de1c08ca…`/3245、`proto/hgcp.proto` `860e401d…`/801、`cpp/src/hgcp/T032_GAP_ANALYSIS.md` `c456411a…`/190。**(a) `MsgResumeData.from_seq`/`to_seq` 语义 —— 文本无源，判 `L3`（须开 `CH` + 用户批准）**：逐处核 §4.2.3`:804`（仅字段名 + `frames(原始帧)`）、§4.2.8.2`:1100`（仅名/号/类型/必填性）、§4.2.6(3)`:971`（只约束「批」的构成：`is_last` 标识最后一批、单批 ≤ 500 帧，**未约束 from/to 取值口径**）、§4.2.8.5`:1158`、§4.3.2`:1320`、`补数完成判据`:973`（**以线路帧为准**）、§6.12`:1898`、附录 C`:3073`（非权威）⇒ **均不唯一确定**。**裁定 = per-batch（`from/to` = 本批 `frames` 首/末帧 SEQ）为权威**，理由：① §4.2.6(3) 已把该消息粒度定为「批」⇒ 同消息内 `frames` 与 `from/to` **同指一批**才自洽；② **对 `T-042` 校验力严格更强**（可断言「解码 `frames` 的 SEQ == `[from,to]` 且连续」＋跨批「`to(i)+1 == from(i+1)`」＋「`is_last` 批的 `to` = 复算终点」；整体区间解**做不了前两项**）；③ 与 `is_last` 正交；④ 批恒非空 ⇒ 不产生 `from > to`；⑤ 对齐成本最低（实施方已如此实现、`T-042` 未实现 ⇒ 现在钉住＝零返工）。**层级三问**：冻结范围含该内容？**是**（`:718` 冻结范围含「**字段定义**」＋`:720` 实质范围含「消息类型与字段定义」）；契约级？**是**（`:715` 冻结后任何变更须走 `CH-xxx`（L3）+ 同步 `proto` + 重跑 `NFR-TEST-5`）；有可转录源？**无** ⇒ 无源＝设计。同构先例 `CH-013`（线格式表示缺口）/`CH-014`（行为面订正）均为「L3 + **用户批准**」⇒ **不得援引「由技术负责人批准」自放**。**提案（未动笔）**：§4.2.8.5 追加一行 ＋ §4.2.6(3) 加指针：`from_seq`/`to_seq` = **本批** `frames` 的**首/末帧通道 SEQ**（批内连续），**非**整体补数区间；不变式 `to(i)+1 == from(i+1)`；`is_last=true` 批的 `to_seq` = 补数终点。**空批口径（§4.2.8.5 确规定「末批可为空」）**：`from_seq = to_seq =` 上一批末帧 SEQ（无上一批则 = `MsgResumeReq.last_received_seq`）；**接收侧不得据 `from/to` 断言 `frames` 非空**。**不变式**：40 消息 / 62 码 / `VER 0x0100` / `FR 184` / `214` 顶层字段号**全不变**（纯语义补全，不改字段/号/类型）。**须用户批准** ⓐper-batch（否决整体区间）ⓑ空批口径 ⓒ归属章节。**→ 本项拟开 `CH-017`（编排者定号，待登记）。**
  **⚠️ 顺带核实（性质澄清，非缺陷）**：§4.2.8.5`:1158` **确**规定「`frames`：`is_last=false` 时非空（**最后一批可为空**）」⇒ 实施方「从不产生空批」＝满足**更强**不变式 ⇒ **「schema 允许、实现不产生」＝合规子集**，**非缺陷**；但正因「允许」，接收侧**必须容忍**，而 per-batch 下空批 `from/to` 在原文本**无取值来源** ⇒ ⓑ 必须与主口径**同批钉住**（否则 `T-042` 要么拒收合法输入、要么取值二义）。
  **📌 (b) `TransportPolicy` 新增纯虚 —— 判「无需登记」（实现细节）**：`grep TransportPolicy|PayloadCodec|接缝 docs/ARCHITECTURE.md` = **0 命中** ⇒ 架构**未列举也未约束**该接缝的方法集；§3.6`:466–471` 对 M6 只给职责/对外接口/依赖/需求，**无模块内类清单**；§3.0.1`:306` 只约束**组件级**；§3.0.2`:314` 的 `E1`–`E7` 是**跨模块/跨语言**判据 ⇒ 属**模块内抽象**。唯一相关纪律（`T032_GAP_ANALYSIS §7.3`:167–174「单一注入接缝、不新增第二套符号」）**已满足**（**加方法而非加符号**）。**爆炸半径＝低**：`: public TransportPolicy` 全仓 **3 处、全在 `cpp/src/hgcp`**（Empty/Pb/Test）且**已全部实现**；纯虚 ⇒ 编译期强制齐补、无跨模块/跨语言扩散、无外部实现者；本仓同源重编译、**无外部 ABI 承诺**。**留痕建议（非必办）**：落 `T032_GAP_ANALYSIS §7.4` 接缝清单，**不必动 `ARCHITECTURE`**。**`E1`–`E7` 逐条不受影响**（各给核验内容）。**⚠️ `E7` 覆盖盲区（与本裁定直接相关，须记住）**：`E7` 显式**不覆盖**散文注记 / 条件语义 / **字段语义** ⇒ 即便日后把 `from/to` 口径写进 §4.2.8，**`E7` 不守护它** ⇒ `T-042` 跨侧对齐**不能只靠 `E7`**，须**双落点**（§4.2.8 正文 ＋ `CONTRACTS.md` 补登；`ARCHITECTURE.md:3049` 已把该补登归 `T-042` 实施面）。
  **📌 (c) `T032_GAP_ANALYSIS §5` 加注处置 —— 判「历史留痕，加注不改；但加注应办」**：**先更正事实** —— §5`:121–132` 的「卡点」列**并不含字面「待 `CH-013`」**（该字面只在 **§7.4`:189`**）；「全文 `grep 取代` = 0」**成立**。§5 现为**全局过期**（`S-1`~`S-5` 已全解）。依据：当时为真（判定口径`:123–124` 明写「在架构席给出权威定义前」）＋ 文件自载非权威声明（`:3–8`）⇒ 改写会抹掉审计价值；编排者**已有生效裁定**（`TASKS.md:748`「保留 + 加注『已被 `§4.2.8` 取代』」，先例 `hgcp_smoke.proto:4–8`）；但 §5 表述是「**现状**」而非「时点」⇒ **须加时点 + 失效声明**。**承接**：**不另立任务**（1 处加注、成本 > 收益）；该文件在 `T-032` 产出面内 ⇒ **随 `T-032` 收口一并落地**，台账登记一行、**不新开号**；§7.4`:189` 同批加注。**建议措辞已备**（仿先例、只加不改）。
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

  **📌（2026-09-22 派发登记 · 只加不改）** 编排者派发实施（实例 `be-t033`，唯一写者，产出面 `cpp/src/ctp_adapter/**`）。**本席在派单中给定的硬约束（留痕）**：① **CTP 合并版名称勘误须以头文件实测为准** —— 存在的是 `<ThostFtdcUserApi.h>`，交易 SPI 基类为 **`CThostFtdcUserSpi`**、行情为 **`CThostFtdcMdSpi`**；**`<ThostFtdcTraderApi.h>` / `CThostFtdcTraderSpi` 在合并版不存在**；② 真实 SDK 在 **`/home/ctpmini/ctpmini_admin/ctp_sdk`**（须自行核实并报实测路径）；③ **CTP include 作 SYSTEM 头**（防其告警撞 `-Werror`）；④ `E3` C++11 合规、`E2` 禁 SQL/驱动痕迹、**`E1` 的判据串含 `sqlite`** ⇒ 勿引入任何 DB 客户端库；⑤ **`E4` 扫描根不含 `ctp_adapter`** ⇒ 本模块不禁止锁/同步调用；⑥ **两轨（有 SDK / 无 SDK 降级）都要报**构建与 `ctest`；⑦ 构建串行化（与并行席位 `be-t035` 互斥）。**须自证**：两轨构建零告警 + 两轨 `ctest` 名单 / 聚合 `E1`–`E7` 全 `0` / **至少 3 条承重反退化**（未连接回 `0x2004` 且退回不静默丢弃、SPI 回调仅序列化+入队、期货与期权各覆盖）/ 冻结点锚 + 时刻 / **实测 CTP 头文件路径与 `CThostdFtdc*Spi` 类名清单**（对既有勘误的独立复测）。**状态只能写「已完成（待独立复核）」。**
  **📌（2026-09-22 交付登记 · 只加不改）** 实施方 `be-t033` 交付 **20 文件 / 2845 行**（新增 17 = 头 6 + 源 5 + 测 6；改 1 = `CMakeLists.txt` 22→83；`module.h/.cc` 未改；**未 commit**）。**编排者独立复测**：`find cpp/src/ctp_adapter -type f | LC_ALL=C sort | xargs md5sum | md5sum` = **`4ab77612bc964a3bcdebc11291cbbfab` / 20 文件**，与自报**逐字节一致**。**门禁（自报，待独立复核）**：两轨构建 `rc 0`、告警/错误 **0/0**；`ctest` **CTP 22/22（基线 18，+4）/ stub 19/19（基线 17，+2）**；聚合 `bash tools/arch-check/arch-check.sh` **`rc 0`（`E1`–`E7` 逐条 0）**，`E4` 默认三目录扫 **45** 文件而 **`ctp_adapter` 不在其内**（与派单一致）；**隔离副本 `/root/t033probe` 4 例变异全杀**（① 去「未连接退回保留」→ `adapter_test` 红；② `OnRtnTrade` 不入队 → `spi_test` 红；③ 拒 `kOption` → `adapter_test` 红；④ `quoteQueue` 丢最新 → `queues_test` 红）+ 复位；**共享树残留 0**。**⚠️ 本轮一处「派单论据不实」的错误更正（责任在编排者）**：派单写「行情侧是 `CThostFtdcMdSpi`（**不是** `CThostFtdcMdApi`）」—— 实施方实测 SDK 后指出 **`ThostFtdcMdApi.h` 内 `CThostFtdcMdSpi`（`:25`）与 `CThostFtdcMdApi`（`:75`）**两者都存在** ⇒ **该半条不成立**（「无 `ThostFtdcTraderApi.h`/`CThostfdcTraderSpi`」半条**成立**）。**结论修正为**：合并版 **`ThostFtdcUserApi.h` + `CThostFtdcUserSpi`** 承担交易；`ThostFtdcMdApi.h` 提供 **`CThostFtdcMdApi`（工厂/接口）+ `CThostFtdcMdSpi`（回调基类）**；**`ThostFtdcTraderApi.h` / `CThostFtdcTraderSpi` 确不存在**。（既有 `ARCHITECTURE.md` 勘误本身**不受影响** —— 它订正的是「SPI 基类应为 `CThostFtdcMdSpi`」，**未**主张 `CThostFtdcMdApi` 不存在；**是编排者转述时自行加码出错**。）**未决 7 项（实施方单列，待裁定）**：① `sendOrder` 返回由 §3.1 的裸 `OrderLocalId` **加宽为** `CtOrderAck{code,client_order_ref,order_local_id,retained}` 以承载验收 4 的 `0x2004` ⇒ **须技术席确认形态**；② `CMD_CONFIRM_ORDER` **状态机/重试入口归 `T-036`**，本模块只到边界（回 `0x2004` + 退回保留 + `takeRetainedOrder`）；③ **无 CTP 前置 ⇒ 未做真实登录/报单联调**（端到端仅到「回调注入 + 队列」）；④ 银期 `TradeCode` `202001`/`202002` 为**约定值**、权威文档未登记；⑤ `ReqAuthenticate` **已实现未验收**；⑥ `quoteQueue` 用互斥体（`ctp_adapter` 不在 `E4` 根，`§3.0.2` 脚注 ⑤ 已披露）；**T4 可达面（`sendOrder`/退回存储）无锁**；⑦ 期权多腿/自对冲（`ReqOptionSelfCloseInsert`）**未接**，不属本任务验收面。
  **📌（2026-09-22 因 CTP 基线变更退回 · 只加不改）** **触发** = 用户裁定交付基线 = 官方 CTP（`CH-015`）⇒ 本任务交付物**对基线不可接受**：其实现系按 `CTP Mini` 的**合并式 `UserApi`** 形态（`ThostFtdcUserApi.h` / `libthostuserapi.so` / `CThostFtdcUserSpi` / `CThostFtdcUserApi`）封装，而**官方 CTP 为分离式**：`ThostFtdcTraderApi.h`（`class CThostFtdcTraderApi` + `static CreateFtdcTraderApi(const char*, bool bIsProductionMode=true)` + `class CThostFtdcTraderSpi`）与 `ThostFtdcMdApi.h`（`class CThostFtdcMdApi` + `static CreateFtdcMdApi(const char*, bool bIsUsingUdp=false, bool bIsMulticast=false, bool bIsProductionMode=true)` + `class CThostFtdcMdSpi`）**两套**。⇒ **须移植**（非修订）。**处置**：状态由「已完成，待独立复核」**退回 `IN_PROGRESS`**；**移植缺口与工作量盘点中**（实例 `be-ctp-official`：含**官方 SDK 目录布局为 headers/libs 分成两个目录**，须核对 `cpp/cmake/HgDependencies.cmake` 的 `CTP_SDK_ROOT` 单根假设是否兼容），**估时待盘点后上修**（现行登记 24 人时）。**原复核结论的历史事实保留但附基线限定**：`te-verify-t033` = **有条件通过（阻断级 0）**，其 `T033.13` 已自载「基线实为上期技术 `CTP Mini`、非官方 CTP」的勘误（见 `docs/TEST_RESULT.md:3539`）；**`T033.10` 第 9 项**（`CtOrderAck` 形态是否偏离 `§3.1` 裸 `OrderLocalId`）的判定**照旧、但同样附基线限定**，须在官方基线上重验。**SDK 锚点同 `T-027` 备注**（官方 v6.7.13；`_sfit_enH` 两 `.so` 均 0 命中）。
  **📌（2026-09-22 `T-033` 官方基线移植缺口盘点 · 已完成，待独立复核 · 只加不改）** 实例 `be-ctp-official`（**只盘不改**，共享树零写入）。**结论：移植面很窄且机械 —— 源码级真语义缺口仅 1 处；SPI 回调签名 100% 兼容**（`CtSpiImpl` 13 个 + `CtMdSpiImpl` 5 个 `override` 在官方基类上**全部通过 `override` 检查**）；`ct_mapping.cc`/`ct_adapter.cc`/`module.cc` 与三个测试**无需语义改**。**★但同时发现一个「静默降级」缺陷（必须修）**：现接线指向官方 SDK 时 **`CTP_FOUND` 判为 `OFF` ⇒ `ctp_adapter` 静默走空实现（stub）**，构建照样成功、测试照样「全绿」，**却根本没有 CTP 适配层** —— 与本项目《断言的假红/假绿》中「**控制看起来有、实际没有**」同类，**属缺陷**（已列入本任务验收 4：须消除并加可核守卫，负例必失败）。**移植工作清单（file:line 相对仓库根）**：`G1` 头名 **4 行**（`module.cc:7`、`ct_gateway_ctp.cc:19`、`ct_spi_impl.h:27`、`tests/ct_spi_test.cc:14`）；`G2` 类型名 **4 行**（`ct_spi_impl.h:33`、`ct_gateway_ctp.cc:124`、`:119`、`ct_spi_test.cc:194`）；**`G3` 唯一真语义缺口 = 工厂名 1 行**（`ct_gateway_ctp.cc:44` `CreateFtdcUserApi(p)` → `CreateFtdcTraderApi(...)`）；`G4` 同源文案/注释约 **12 行**（`ct_gateway_ctp.cc:3,7,46`、`ct_spi_impl.h:6,10-13`、`ct_gateway.h:13,38,54`、`ct_mapping.h:11-12`、`ctp_adapter/CMakeLists.txt:7,13`）；`G5` **CMake 接线 3 项不兼容**（`cpp/cmake/HgDependencies.cmake:32` 的 `HG_CTP_TRADER_HEADER=ThostFtdcUserApi.h`、`:31`/`:10` 的 `CTP_SDK_ROOT` **单目录**假设、`ctp_adapter/CMakeLists.txt:40` 须**两个** SYSTEM include 目录、`:49-50` 的库名/前缀/路径）；`G6` 探路件 `cpp/ctp_smoke/**` 须在官方基线上**重做并重冻结**（`T-027` 的冻结产物，判据反转，含 `ci/run_probe.sh:152` 的 `B6`）。**估时上修 24 → 29 人时（+5）**：依据 = 上述 `G1`–`G5` 的代码/CMake 改造 + **官方轨端到端 `ctest` 全绿** + **静默降级守卫（负例必失败）**；`G6` 的 **+3 人时归 `T-027`**（其自有探路件，总量守恒见 §5）。**两项未决本席已裁定并随派单下达**：`U3` **两个 SDK 根的参数形态** = `CTP_SDK_ROOT` 仍可指官方**解包父目录**（内部自动探测两子目录）＋另给 **`CTP_TRADER_ROOT`**/**`CTP_MD_ROOT`** 显式覆盖，**且须保持既有三轨（`ctp`/`stub`/`fetch`）语义不变**；`U1` **`bIsProductionMode` 须可配置**（优先复用既有 `CFG-*`；**无既有承载则不得臆造新 CFG 编号**，改为显式入参并把默认取值作为待裁定项回报）。**已派实施**（实例 `be-t033port`，唯一写者 = `cpp/src/ctp_adapter/**` + `cpp/cmake/**` + `cpp/ctp_smoke/**`；**明确回避 `cpp/src/risk_state/**`** 以免与 `be-t124` 双写）。**待架构席核（非阻塞）**：`U2` 官方 `SubscribePrivateTopic(THOST_TE_RESUME_TYPE, int nSeqNo=1)` 的**重连 resume 语义是否需重估**（现 1 参调用**编译通过且语义未变**）。**证据边界（据实披露）**：盘点方**未**做「改动后官方轨 `ctest` 全绿」验证（守「不要修」纪律）⇒ 该证据由本轮实施方补齐（验收 3）。
  **📌（2026-09-22 `T-033` 移植交付 + U1 落地 · **已完成，待独立复核** · 只加不改）** 实例 `be-t033port`（**唯一写者 `cpp/src/ctp_adapter/**` + `cpp/cmake/**` + `cpp/ctp_smoke/**` + `cpp/CMakeLists.txt`**；不自评 `DONE`）。**交付锚（两次取样）**：移植完成时 `cpp/src/ctp_adapter` = `fb6c7ce5cd990c9fd30b2f3b912f00b6`/23 文件/3213 行；**U1 落地后 = `41e9ac046e0289ea68f45c234961ca88` / 23 文件 / 3225 行**（⚠️ **`fb6c7ce5…` 已被取代、勿引用**）；写域合并锚 = `6333a743361f074324d3033b06070721`；`cpp/src` = `b5b675be98aae53942bb87a6c5b59ed0`/115（含他人模块）。**★静默降级消除 + 守卫双向承重**（前态复现「`CTP_FOUND=OFF` 走空实现而构建成功」的假绿；现 `CTP_FOUND=ON`；**显式给根却解析失败 ⇒ `rc=1` + CMake `FATAL_ERROR`**；删守卫 MUT-D ⇒ 回落 `rc=0`）**在三布局上验证**（官方父目录自动探测 / `CTP_TRADER_ROOT`+`CTP_MD_ROOT` 显式覆盖 / 老式单目录）。**三轨**：`ctp`（官方 v6.7.13）**38/38、0 跳过**；`stub` **34/34**；`fetch` 轨**仅验 CTP 相关目标**（**未跑 protobuf 全量构建，实施方如实声明**）。**`-isystem` 两目录**（`ctp_adapter/CMakeLists.txt:43-45` 注明「官方分离式布局下交易头与行情头分处两个目录 ⇒ 两目录都须以 SYSTEM 引入」）；**MUT-A 去掉 `-isystem` ⇒ `rc=2` `-Werror=unused-parameter`** ⇒ **既生效又必需**。**U1 落地三件事**：① `ct_types.h:89` `CtConfig() : env_type(CtEnvType::kSimulation)`，并注明「**与架构已登记配置项 `CFG-22` 的缺省逐字一致**」；② **行为变更 4 处披露**（`ct_types.h:82`/`:86`「改前不配置⇒生产、改后⇒评测」+「**上生产须显式设 `CFG-22=PRODUCTION`**」、`ct_gateway_ctp.cc:50`/`:154` 交易与行情两处调用点、测试注释、报告）；③ `redacted()` 保留 `env_type=`（**生效模式可观测**）**并新增缺省值断言**（`ct_adapter_test.cc:317–336`，用例数 87→**89 checks**）。**★反退化双证（我点名要求的那点）**：**MUT-E（`productionMode()` 恒 `true`）仍红**（2 failures）；**MUT-F（缺省回退 `kProduction`）⇒ 红**（3 failures）⇒ **新缺省值断言确在承重**；两者均**逐字节复位 + `touch` + clean 重编 + 复测 PASS(89/89)**。**共享树聚合门禁 `rc 0`（`E1`–`E7` 全 0）**；`ctp_smoke` 未受 U1 影响（`run_probe.sh` 仍 **`PASS=12/FAIL=0`**）。**实施方自查自修三处（诚实披露）**：`B4` 大写 `V` 假红、`B6` 普通 `-I` 撞 `-Werror` 假红、**`cp -a` 保 mtime ⇒ 陈旧对象不重编译 ⇒ MUT-C 曾假绿**（已改 `touch` + clean 重建）。**负载纪律**：开工前有界等待（3.77→2.10 才开工）；`-j1`；产物与 `TMPDIR` 在 `/`；**隔离残留 = 0**、副本与写域 `diff -r` 一致。**待独立复核**（复核须覆盖：三布局 / 守卫双向 / 三轨 / U1 缺省与断言 / `-isystem` 必要性 / 残留）。**仍待技术席裁定**：`TE-T033-001`（`sendOrder` 返回 `CtOrderAck` vs §3.1 裸 `OrderLocalId`）——**已派只读裁定，结论到达前本项不计入 `DONE`**。
### T-034 实现风控内存状态 risk_state

- **需求追溯**：`FR-RISK-9/10`, `NFR-PERF-1`, `ARCHITECTURE.md` §3.4/§4.4
- **负责人**：backend-engineer#cpp
- **优先级**：P0
- **依赖**：T-031
- **估时**：24 人时
- **来源**：初版
- **状态**：**DONE（授权 + 独立验证）**〔第 42 轮〕（独立复核 `te-verify-t034` = **通过、0 阻断缺陷；3 项建议级观察**；**编排者 2026-09-22 授权**。**验收证据（复核者独立实测，非采信自述）**：交付锚 `cpp/src` = `22dc22656fad5cdf5b6872bfcd0c8641` / **68 文件**（`2026-09-22T05:56:11Z`）**逐项复现一致**；**产出方自述全部 8 项（锚 md5 / 68 文件 / 14 新 / mtime / `ctest 17/17` / `551 checks` / `E4` / `ASAN`）无一「自述 ≠ 实测」**；隔离副本 `/root/t034v` 构建 `Release` `rc 0`、`warning:` **0** / `error:` **0**，9 个 `risk_state` TU **全部**带 `-std=c++11 -Wall -Wextra -Werror`；`ctest` **17/17**（名单以 `ctest -N`）、四单测 checks = `70+418+39+24` = **551 / 0 fail**；`E4` `--root cpp/src/risk_state` ⇒ **`rc 0`、`N=16`、命中 0、WARN 3**（逐条核为 `new`：`risk_snapshot.cc:231`、`risk_state.cc:22`/`:41`）、默认并集根 ⇒ `N=20`；聚合 **`E1`–`E7` 全 `rc 0`**；**承重变异复现**：`snapshot_manager.cc:26` 去读者登记 ⇒ **ASAN `heap-use-after-free`**、`:58` 去纪元宽限 ⇒ 断言红（`snapshot_manager_test.cc:117`），复位 clean 重建 418/0 PASS；**四条验收**：①13 类数据集 **13/13** 有载体；②不可变 + RCU（含探针「校验失败不发布」+「71289 读零撕裂」）；③`E4` 通过；④`RSK-04` **双向**（**36917 组：0 误拒 / 0 单调反例 / 551 调低更拒对**）。**3 项建议级观察去向**：`O-1`（`last_result()` 跨线程读竞争，**TSAN 证实**，产出方**已披露**）/ `O-3`（`stats()` 读 `retired_.size()` 与 `publish()` `push_back` 竞争，产出方**未披露**）⇒ **`T-124`**；`O-2`（未注册 `snapshot()`(peek) 跨发布边界 ⇒ ASAN UAF）⇒ **已文档化**（§4.4.3）；`O-5`（信息：`E4` 的 `N` 两口径 16/20 勿混引）⇒ 不另立。**证据章**：`docs/TEST_RESULT.md` `:2961`–`:3165`（`te-verify-t034` 落、**纯追加已证** —— `head -2959 \| md5sum` = 追加前基线 `8e914198…`；追加后 `1978bbbe…` / 3165 行）。）
  **📌（2026-09-22 交付披露 · 只加不改）** ① **交付面 vs 验收面缺口**：`PositionLimit`、`TotalLimits` 两类数据集在 `DATA_MODEL.md` **无独立数据字典表**（权威源 = `ARCHITECTURE.md` §4.4.1）—— 若 `T-035` 需要其字段级契约，须先确认权威源；② **接线不在本任务**：`hgcp` 控制指令 → `LoadBatch`/`rebuild` 的解析与投递归 `T-032`（`hgcp`）/ `T-038`（参数生效 / `param_version` 单调）；③ **`last_result()` 的跨线程读取未加同步**（文档注明「写者静止后读取」）—— 若后续需并发读该字段，须补无锁发布或明确约束（**本任务未做，如实披露**）。
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

  **📌（2026-09-22 派发登记 · 只加不改）** 编排者按**关键路径** `… → T-031 ✔ → T-034 ✔ → **T-035** → T-036 → …` **派发**实施（实例 `be-t035`，唯一写者，产出面 `cpp/src/risk_engine/**`）。**本席在派单中给定的硬约束（留痕）**：① **`E4` 扫描根含 `cpp/src/risk_engine`** ⇒ 代码内**不得**出现 `mutex`/`lock_guard`/`unique_lock`/`condition_variable` 与同步 I/O 调用形态（`read`/`write`/`send`/`recv`/`sleep` 等），**且勿用这些名字命名**；② `E3` C++11 合规（`-std=c++11 -Wall -Wextra -Werror`，禁 `make_unique`/`optional`/`variant`/`string_view`/`if constexpr`/结构化绑定）；③ `E2` 禁 SQL/驱动痕迹；④ 上游 `RiskState` 已冻结（`T-034`，`DONE`）——**须用 `acquire()`，勿用未注册的 `snapshot()`(peek)**；⑤ 构建串行化（与并行席位 `be-t033` 互斥：`-j1`、负载 ≥2.5 则有界等待）、构建目录与 `TMPDIR` 放 `/`（`/tmp` 为小 tmpfs）。**须自证**：构建零告警 / `ctest` 名单（以 `ctest -N` 为准）/ 聚合 `E1`–`E7` 全 `0` 且 **`E4` 实扫命中 0** / **至少 3 条承重反退化**（三层限额全序、平仓豁免、纯函数式或 P99）/ **P99 ≤ 5 ms 的测量方法与实测数** / 冻结点锚 + 时刻。**状态只能写「已完成（待独立复核）」，不得自评 `DONE`。**
  **📌（2026-09-22 第 48 轮派发登记 · 只加不改）** **`T-035` 收口再派发**（原实例 `be-t035` **已离线且未交付/未回报**；接替实例 `be-t035b`，唯一写者，产出面 `cpp/src/risk_engine/**`）。**编排者只读实测的起始态**：`risk_engine` **28 文件**、内容级锚 `fc086003667fe0a890a69358c3a51c56`（cwd=仓库根）；构建目录 `/root/b-t035` 已配置（**须先确认无人在用**）。**交接要点**：第一步 = **现状实测**（构建 → `ctest`（名单以 `ctest -N` 为准）→ 聚合 `E1`–`E7` + **`E4` 实扫 N/命中**），**先出「现状判定表」**（4 条验收 + 8 条规则逐条：已满足/未满足/无法判定），**再决定补哪些**；**不得推翻既有实现重做**。**派单硬约束**：`E4` 扫描根**含 `cpp/src/risk_engine`** ⇒ 禁 `mutex`/`lock_guard`/`unique_lock`/`condition_variable` 与同步 I/O 调用形态、**勿用这些名字命名**；`E3` C++11 合规；`E2` 禁 SQL；上游 `RiskState` 已冻结（**用 `acquire()`**，勿用未注册的 `snapshot()`(peek)）。**承重判据**：至少 3 条反退化（**三层限额全序** / **平仓豁免** / **纯函数式或 P99**）+ **`P99 ≤ 5 ms` 给测量方法与实测数**（不得写"应该满足"）。

  **📌（2026-09-22 `T-035` 交付登记 · 只加不改）** 收口实例 `be-t035b` 判定**零改动交付**（前序实例 `be-t035` 的实现已达标，相对接手锚逐字节一致）。**交接事实**：先前构建目录 `/root/b-t035` 指向 **worktree** `bg-01a0bd8e`，其 `risk_engine` 与主树 `diff -r` 一致。**门禁（自报，待独立复核）**：0 warning/0 error；18 个 TU 全带三开关；`ctest -R risk_engine` **13/13**；聚合 **`rc 0`**（`E1`–`E7` 全 0，**`E4` 实扫 45 文件、命中 0**；3 处 WARN 全在 `risk_state` 的 `new`，非本模块）；**4 例变异全杀**（M1 层①②全序反转 / M2 撤平仓豁免 / M3 `thresholds_` 改 mutable 破纯函数式 / M4 判定路径自旋 ⇒ **P99 28.962ms>5ms 断言红**）+ 每条「变红→复位→clean 重编译→回绿」；隔离副本 `/root/iso-t035b`。**P99 实测 33–83µs**（`bench_risk_check` 100000 次，5 次采样），max 偶达 6–10ms（2 核调度抖动、非判定路径）。
  **⚠️ 7 项未决（单列，不得并入"已完成"）**：**① 验收 2 的「同层并列取占用率最高、再按 `id` 升序」在引擎端不可达** —— 内存模型三层各键唯一 ⇒ `check()` 每层至多 1 条命中，该规则**仅经 `selectFinestQuotaHit` 选择器单测生效**（判据是**选择器函数**而非引擎端到端）；需复核席知悉，并请架构席确认该「并列」情形是**防御性代码**还是**判据须改写**。**② 「超限被拒且未发往 CTP」本模块不可独立判定** —— 模块无 CTP 链接（`E1` 通过）⇒ 只能证「判定 = `kReject`」；「不发送」由调用方 `order_engine`（`T-036`）承接。**③ `R-RISK-8`「品种级可覆盖」未承载** —— `RiskThresholds` 仅全局 `risk.price.warn_pct/block_pct`，快照 `thresholds()` 无品种级键约定 ⇒ 如需，须先由架构定键/承载（疑随 `T-038` 参数生效）。**④ `CFG-16` 的 `risk.limit.layers` 键未被显式解析** —— 三层恒参与（层②「可选」由数据缺位表达），与默认口径等价；若要按配置裁剪层集须另承接。**⑤ `R-RISK-5` 无敞口记录时静默通过**（`evalNetPosition` 仅 `!= nullptr` 才判）—— 需求未明确「缺敞口是否拒绝」⇒ **须产品侧澄清**。**⑥ 已核实非缺陷（勿误报）**：`EVT_PRE_TRADE_REJECT`(0x0202) 契约仅 `current_value/threshold_value`、**无「基数」槽位** ⇒ `FR-RISK-4` 输出与 `§4.2.4` 一致；`R-RISK-7` 的 `is_management_role` 追溯落 `is_enterprise_leader→is_responsible→0x100F`，加载侧映射属 `T-038`。**⑦ 已文档化取舍**：裸 `applySnapshot(const RiskSnapshot&)` 无读句柄保护（跨 rebuild 有 UAF 风险），头文件已注明并推荐 `applySnapshot(const RiskState&)`（经 `acquire()`）。
  **⚠️ 并发事实（非本任务缺陷）**：收口席观测到「全量 `cmake --build .` 在 `hgcp_transport_test` 链接失败：`undefined reference to PbTransportPolicy::resume_data_body`」—— 彼时 `cpp/src/hgcp/**` **正被 `be-t032res` 实时写入**（mtime `16:57:43–16:58:11`）⇒ 属**在飞编辑期的瞬时假象**（先例：活跃写入期级联失败），**不得据此判 `T-035` 或 `T-032` 失败**；`be-t032res` 随后交付并自报 `ctest 31/31`、聚合 `rc 0`。**另**：`T-035` 收口席只构建 `risk_engine`/`risk_state` 目标 ⇒ 其全绿结论仅覆盖该两模块。
  **📌（2026-09-22 `T-035` 退回修复派发登记 · 只加不改）** 独立复核**不通过**（唯一 P1 `TE-T035-001`：`evalQuota` 缺平仓豁免 ⇒ 合法平仓被误拒 `0x1004`，违反 `R-RISK-5` 且与 `R-RISK-3/4/5/6` 口径不一致；已建 `docs/BUGS/BUG-002.md`）。
**派 `be-t035fix`**（唯一写者 `cpp/src/risk_engine/**`；另授权填 `docs/BUGS/BUG-002.md` 的「修复」节），派单核心纪律三条：
**① 同类扫描（本项目铁律「修一类缺陷必须查全同类」）** —— 8 条规则**逐条**给「需否平仓豁免 / 现状是否已豁免 / 证据 `file:line`」三栏表，**漏的全部同批补齐**（不得只修 `evalQuota` 一处；产出方此前的「撤平仓豁免」变异**只在持仓上限/时点净持仓上红**，正是「豁免只在部分规则实现」的证据）；判「某规则不该豁免」**须给需求/架构依据**，不得凭感觉。
**② 逐规则对偶用例** —— 每条需豁免的规则各给「额度/上限已耗尽下**平仓必放行**」＋「同条件**开仓必拒**」，用**仅改 `offset` 为 `kClose`** 的最小对偶构造（复核方即以此抓到本缺陷）。
**③ 逐处反退化** —— 补的每一处豁免**逐处移除** ⇒ 对应用例**必红**；复位 + clean 重编译回绿。
**明确不在本单范围（只读知悉）**：`EXP-002` 岗位维度承载 ⇒ 已立 **`T-126`**；`EXP-003` fail-open ⇒ **须产品澄清**、不改；`EXP-004`~`007` 均低、不改；**验收 2「同层并列引擎端不可达」已被复核方判「防御性代码（合规）」** ⇒ 不得为它改判据。
**构建纪律**：**勿用 `/root/b-t035`**（指向 `worktree bg-01a0bd8e`、`cpp/src/hgcp` 恐为陈旧快照），自建 `/root/b-t035fix`；`-j1` + 负载门限；`TMPDIR` 放 `/`。

  **📌（2026-09-22 `TE-T035-001` 修复交付登记 · 只加不改）** 详见 §1 状态栏。**要点**：`evalQuota` 增 `is_open` 短路 + 新增 `risk_engine_close_exemption_test.cc`（9 用例/72 断言）+ `order_request.h` 注释补 `kClose` 语义；冻结点 `35cf08187b4354df81aeed6cf1213342`/**29 文件**（改前 `fc086003…`/28）。**8 规则同类扫描**：需豁免 5 条（`R-RISK-2/3/4/5/6`）**全部已豁免**；不需 3 条（`R-RISK-1/7/8`）**各有 FR/R 明文依据**；**未发现第二处漏补**。**⚠️ 实测订正（实施方纠正编排者派单转述）**：派单原转述「撤平仓豁免的变异只在持仓上限/时点净持仓上红」**不准** —— 实测 **`scale`/`term` 的既有用例也会红**（各 1），**真正无守卫的只有 `quota`**（`quota_layers` 既有用例在变异下**仍绿**）⇒ **此即原缺陷逃逸之因**。**教训：派单不得转述未复现的「红/绿」观察**（本席原样转述了前一轮产出方的说法而未自测 ⇒ 已请复检方独立复现、并以此为准）。**待架构确认（不阻塞）**：`R-RISK-7` 的 `individual_quota` 子项是否属「限制类处置」而须豁免平仓（现判「不豁免」、无锁死断言）。**独立复检 `te-verify-t035b` 进行中**（复检面含：缺陷确已消除 / 逐层三层限额 / **误伤反向** / 8 规则表逐条依据（尤其 3 个「不需豁免」）/ 新用例守卫强度 / 既有断言未被削弱 / 回归）。
  **📌（2026-09-22 `T-035` 转 `DONE` · 独立复检 `te-verify-t035b` = 通过 / 0 缺陷 · 只加不改）** 复核方**未采信产出方**，独立实测：构建 rc 0 / 0 warning；`ctest` **32/32**；聚合 `arch-check.sh` **`rc 0`**（`E1`–`E7` 全 0）；**`E4` 实扫 46 文件 / 命中 0**（三目录并集、非 `SKIP`）；新用例 **72 checks / 0 failures**；**自建探针 94 checks / 0 failures（`/root/vb-probe-t035.cc`，不复用产出方用例）**。**缺陷确已消除 + 双向**：额度层 ①/②/③ 及三层全超 ⇒ **开仓仍拒**（`0x1004`/`0x1017`/`0x1005`、layer 正确）＋**仅改 `offset=kClose` 即 PASS（`entries=0`）**、减仓方向同；反向 = 白名单 / 权限 / 价格偏离 3 条**平仓也拒**（无多豁免）、非法 `offset` 有前置校验、无旁路。**★归因订正已被独立确认（并推翻编排者原转述）**：隔离副本逐处撤 5 条豁免，实测既有用例红数 = 撤 `quota`→**既有 0**（仅新用例红）/ 撤 `positionLimit`→`position_limit`+`net_position` / 撤 **`scale`→`scale_redline`（1）** / 撤 **`term`→`term_test`（1）** / 撤 `netPosition`→`net_position` ⇒ **`scale`/`term` 既有守卫确实各红 1，唯一无既有守卫者 = `quota`**（与产出方实测一致、与编排者原转述「只在持仓上限/时点净持仓上红」**不符**）；另**反向变异** M6/M8（开仓豁免 / 调用点传 `false`）⇒ 新用例红 35 / 20 ⇒ **双向承重**。**快照**：修复后 `35cf08187b4354df81aeed6cf1213342`/29（与自报吻合）；**复核方独立重建修复前同口径** `fc086003667fe0a890a69358c3a51c56`/28（与上轮 `T035.0` 吻合）；`risk_engine.cc` mtime `19:45:53` 复核期未变；共享树变异残留 **0**。**证据章**：`docs/TEST_RESULT.md` **`:3987`–`:4120`**（**纯追加已证**：`head -3983` = `b4b47f6a…` → 追加后 `4d171628…`/4120）。**5 项观察（均低、无缺陷、本席裁定不另立号）**：① `R-RISK-n` 编号在 `RISK.md` §4 与 `ARCHITECTURE.md` §3.3 **含义不同**（措辞歧义，属架构/需求措辞域）；② `net_position_test::test_close_is_exempt` 闭包**兼依赖 `R-RISK-3`**（非最小对偶）；③ 产出方表中 Permission/Price **行号偏移 4**；④ `git diff HEAD` 另含**前一轮交付**的 `risk_engine.h` / purity / bench / test_util（**勿误归本单**）；⑤ `ARCHITECTURE.md` §3.3 的 `R-RISK-2` 行**未标「平仓不受限」** —— 依据 `R-RISK-5` 已足够 ⇒ **架构席域**、复核方未改。**待架构确认（不阻塞，仍开放）**：`R-RISK-7` 的 `individual_quota` 子项是否须豁免平仓（现判「不豁免」、无锁死断言）⇒ **若架构席另裁须豁免则另立号**。**编排者 2026-09-22 授权**：`DONE` 判据〔授权 + 独立验证〕满足；**`T-126` 的依赖 `T-035` 就此解除**（其**开工前置 = 架构席对字段命名与承载的裁定**仍待）。
  **📌（2026-09-22 `R-RISK-7` 的 `individual_quota` 子项 —— **用户裁定终局** · 只加不改）** 架构席原判「**不豁免**」，其**决定性依据**为「`individual_quota` 是**单笔**手数上限（`risk_engine.cc:428` 仅比较单笔 `req.volume`）⇒ 超限平仓**可拆单完成**、平仓通道不被堵死 ⇒ 不触发 `R-RISK-5`『任何限制类处置一律不得阻断平仓』的立法理由」。**但该依据依赖一个需求侧未明的口径**（`DATA_MODEL.md:790` `t_authorization.quota_limit` 只写「个人上限」，**未写单笔/累计**）⇒ 产品席 `pm-risk-norms` 独立核证后判「**不可判定、须用户裁定**」（证据链：`DATA_MODEL.md:790`/`:809-810`、`m1_config_auth.sql:547`、`REQUIREMENTS/AUTH.md:234`/`:241-244`、`RISK.md:50`/`:194`/`:399`、`ARCHITECTURE.md:1351`、`DATA_MODEL.md:2248`/`:3202`/`:4255`、`PROJECT.md:142` 经 `Q-16` 归**三层限额**、`INDEX.md:588` 的 17 条 `Q` **无一条**覆盖）⇒ 编排者提请用户裁定。**✅ 用户 2026-09-22 裁定 = 「单笔上限」⇒ 维持「不豁免」**：**零制品改动**（现状即正确，`order_request.h:30-33` 的注释成立、`risk_engine.cc:428` 的单笔比较即正确实现），**不另立号、免 CH**。**旁证（本席实测）**：该判定**已有测试覆盖**（`cpp/src/risk_engine/tests/risk_engine_permission_test.cc:113` `test_individual_quota_exceeded()`，`p.individual_quota = 1` 断言超限拒绝）⇒ 非「实现有、无守卫」。**⚠️ 若将来需求改判为「累计」** ⇒ 该依据失效、「不豁免」结论翻转，且**现模型无法实现**（`t_authorization`（`DATA_MODEL.md:785-798`）与内存 `PermissionSnapshot`（`ARCHITECTURE.md:1410`）**均无 `used`/`frozen`**）⇒ 届时须「需求加豁免条款 + 新技术任务 + 可能 `CH`」。**本项就此闭环，不再挂账**。
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
  5. **（2026-09-22 新增 · 由 `tl-arch-cd` 的 `§3.1` 签名普查牵连而出）** `order_engine` 实现落地后，**须对 `ARCHITECTURE.md` §3.2 的「对外接口」签名草图做一次同法普查**（**草图 vs 真实签名**），并**按 (A)/(B)/(C) 分类**：**(A) 会误导**（**元数 / 类型 / 返回类别**不同）⇒ **随本单订正**；**(B) 不误导**（仅 `const`/引用/参数名等约定差异）⇒ **列而不改**；**(C) 草图非穷尽（omission）** ⇒ **列而不改**。**理由**：`§3.1` 本次普查查出 1 处 (A) 类（`cancelOrder` 草图元数 2 → 真实 1）⇒ **同类问题在 §3.2 存在与否，当前不可判定**（`cpp/src/order_engine/**` 现仅 3 个文件、`module.h` 为骨架占位且**自述「真实对外接口见 `ARCHITECTURE.md` §3.2」**）⇒ **须待本任务实现落地后补做**。落点 = `docs/ARCHITECTURE.md` §3.2（`docs/**` 须走授权落笔）。
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
  5. **（2026-09-22 架构席裁定 `tl-risk-ruling` A.4 新增）** `CMD_UPDATE_PERMISSION`(0x0116) 的 **4 个布尔逐字段映射**到 `PermissionEntry` —— 协议 `is_enterprise_leader` → 模型 `is_responsible`；协议 `has_risk_role` → 模型 `has_risk_post`；`is_management_role` / `has_settle_role` **同名直映**；**须给「来源 → 字段」逐列映射表**。依据 = 本条属验收 1「17 种控制指令可消费并生效」中 `CMD_UPDATE_*` 的**具体化**（字段映射本即本任务既有义务），且与 **`T-126`**（字段承载 + 引擎消费）配对。
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
  6. **（2026-09-22 架构席裁定 `tl-risk-ruling` A.4 新增 · Java 侧 `DB → wire` 组装）** 下发 `CMD_UPDATE_PERMISSION`(0x0116) 时须从库**派生**并组装 4 个布尔：`t_user_role → t_role`（`SETTLER` → `has_settle_role`、`FIN_RISK` → `has_risk_role`、`APPROVER` → `is_management_role`）＋ `t_user.is_enterprise_leader` → `is_enterprise_leader`。⚠️ **`DATA_MODEL.md` 无这四个列** ⇒ **必须派生、不得直读**（属 `FR-AUTH-10` 授权下发域）。依据 = 与 `T-038` 验收 5 配对，构成「**组装（本任务）→ 下发 → 消费（`T-038`/`T-126`）**」闭环。
  7. **（2026-09-22 `CH-017` 生效后新增 · 跨侧契约登记）** 在 **`java/CONTRACTS.md`** 补登 **`MsgResumeData.from_seq`/`to_seq` 的权威语义 = per-batch（本批 `frames` 首/末帧通道 `SEQ`）+ 空批口径**（依 `CH-017`，**用户 2026-09-22 已批准 ⇒ 即日生效**；权威文本见 `ARCHITECTURE.md` §4.2.8.5 注记）。**理由**：`ARCHITECTURE.md` 侧已落（`tl-arch-cd`，2026-09-22），但 **`E7` 显式不覆盖字段语义/散文注记** ⇒ **跨侧对齐须双落点**，只落 `ARCHITECTURE.md` 会把契约登记**只落一半**；本任务为 Java 侧 HGCP 客户端与跨侧对齐的承载者。**登记口径**：属**向既有冻结口径补登记**（`CH-017` 已定版），**不另开 `CH`**；若触及 `HGC-1.x` 增量号，按 `java/CONTRACTS.md` 既有体例处理并**在交付报告说明是否升 `HGC`**。
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

### T-126 `PermissionEntry` 岗位维度承载补齐（`has_settle_role` / `is_management_role`）＋ 引擎 `evalPermission` 消费

- **需求追溯**：`FR-RISK-7`, `R-RISK-7`, `ARCHITECTURE.md` §3.3 / §4.3.2（`0x100E` 要求「风控岗/结算岗」）/ §4.4.1
- **负责人**：backend-engineer#cpp
- **优先级**：P1
- **依赖**：`T-035`
- **估时**：**4 人时**
- **来源**：新增（`T-035` 独立复核 `te-verify-t035` 的观察 `EXP-T035-002`：引擎 `PermissionEntry` **无 `has_settle_role`、无 `is_management_role`** ⇒ **仅具结算岗者当前可操盘**，与 `§4.3.2` 的 `0x100E`（「风控岗/结算岗」）不符；**复核方明确「不能维持产出方 ⑥『非缺陷』结论」**。属**跨任务承载缺口**：**内存模型侧**在 `T-034`（`cpp/src/risk_state/**`，已 `DONE`）、**加载侧映射**在 `T-038`（TODO）⇒ 立号承接以免「已知隐患不落表」）
- **状态**：**DONE（授权 + 独立验证）**〔第 73 轮〕（实例 `be-t126` 交付；独立复核 `te-verify-t126` = **通过 / 0 阻断缺陷**；**编排者 2026-09-22 授权**）
- **验收标准**：
  1. **承载补齐**：`PermissionEntry` 增加 `has_settle_role` / `is_management_role`（**✅ 键名与类型已由架构席 2026-09-22 裁定钉住：两者均为 `bool`、直采协议名** —— 依据 = `ARCHITECTURE.md` §4.2.3 `:818`（`CMD_UPDATE_PERMISSION` 字段列含 `…, is_management_role, has_risk_role, has_settle_role`）+ §4.2.8 `:1178`（`CH-013` 定版字段级 schema：`is_management_role`(6:bool·必) / `has_settle_role`(8:bool·必)）。⚠️ **`§4.4.1` 与 `DATA_MODEL.md` 均无字段级名**（AUTH 13 表无此三列，仅 `t_user.is_enterprise_leader`）⇒ **字段级权威只在 §4.2.3/§4.2.8**）。
  2. **加载侧**：与 `T-038`（参数生效 / 加载侧映射）对齐来源；若决定在本任务内落，须给「来源表 → 字段」的逐列映射。
  3. **引擎消费**：`evalPermission` 按 `§4.3.2` `0x100E` 语义判定；**须给「仅具结算岗」与「仅具风控岗」两个独立用例**（当前应可复现「仅具结算岗者被放行」的缺陷态）。
  4. **反退化**：把新增字段的消费移除 ⇒ 对应用例**必红**。
  5. **门禁**：构建 0 告警（`-std=c++11 -Wall -Wextra -Werror`）、`ctest` 全绿（**名单以 `ctest -N` 为准**）、聚合 `rc 0`（`E1`–`E7` 全 0）、**`E4` 实扫命中 0**。
- **产出**：`cpp/src/risk_state/**`（模型字段）+ `cpp/src/risk_engine/**`（消费）；**加载侧视裁定**（`T-038` 或本任务）
- **备注**：**勿与 `T-035` 的 P1 缺陷 `TE-T035-001`（平仓豁免缺失）混淆** —— 本项是**岗位/角色维度承载缺口**、`TE-T035-001` 是**方向豁免缺口**，两者独立、可并行修。**⚠️ 本项在取得架构席对「字段命名与承载」的裁定前不得开工**（否则会产出与权威模型不一致的新字段）。
  **📌（2026-09-22 架构席裁定 `tl-risk-ruling` · **开工前置解除** · 只加不改）** ① **键名/类型有明文（非「无明文」，故无需臆造）** = `has_settle_role` / `is_management_role`，**均 `bool`**；依据 = `ARCHITECTURE.md` §4.2.3 `:818` + §4.2.8 `:1178`（`CH-013` 定版冻结对象）。② **承载位置（file:line）**：字段落 `cpp/src/risk_state/include/hg/risk_state/model.h` → `struct PermissionEntry`（裁定时 `:220–233`）新增两 `bool`；消费落 `cpp/src/risk_engine/src/risk_engine.cc` → `evalPermission`（`:395–442`）：`:409` 的 `else if (p->has_risk_post)` → `else if (p->has_risk_post || p->has_settle_role)`（回 `kErrPositionConflict` `0x100E`）、`:406` 的 `else if (p->is_responsible)` → `else if (p->is_responsible || p->is_management_role)`（回 `kErrLeaderForbidden` `0x100F`）。**现状佐证缺陷属实**：`grep -rn "has_settle_role\|is_management_role" cpp/` = **0 命中**（本席复核），且 `PermissionEntry` **无生产加载点**（仅 `model.h`/`risk_snapshot.*`/`load_batch.*` 与测试）。③ **语义 = 析取（任一即可、同一阻断码）**：`REQUIREMENTS/RISK.md:194`（`FR-RISK-7`）「具备风控**或**结算岗位权限的账号**不得**下单」＋ `ARCHITECTURE.md` §4.3.2 `:1352`（`0x100E`「岗位冲突 | 操作人具备风控岗/结算岗权限」）；`is_management_role` 归 **`0x100F` 第二支**（与「企业负责人标记」构成 **`CH-010` 析取** —— `REQUIREMENTS/AUTH.md:219`（处理规则明写「析取」）/`:226`/`:228`，`ARCHITECTURE.md` §4.2.3 `:818`「`is_management_role='Y'` 者禁止操盘」）。④ **命名不一致（已披露、本单不改名）**：协议 `is_enterprise_leader` ↔ 模型 `is_responsible`（`model.h:231`）；协议 `has_risk_role` ↔ 模型 `has_risk_post`（`:230`）。新字段**直采协议名**以免出现第三套命名；**既有两字段本单不改**（改则连锁 `risk_snapshot`/`load_batch`/多处测试）。⑤ **加载侧义务已分流**：**不并入本任务、不另立号** ⇒ 补入 **`T-038` 验收 +1**（`CMD_UPDATE_PERMISSION` 的 4 布尔逐字段映射）；**Java 侧 `DB → wire` 组装**补入 **`T-042` 验收 +1**（从 `t_user_role → t_role` 派生 + `t_user.is_enterprise_leader`）。⑥ **层级 = 勘误/实现缺口补齐，非实质变更 ⇒ 免 CH、不须用户批准**（冻结对象 40/62/`VER 0x0100`/`184`/`214` 全不动；协议侧字段早已由 `CH-013` 定版，本项只是把它落到 C++ 模型与消费侧）。⑦ **待独立复核关注面**：既有测试夹具 `risk_engine_test_util.h`、`risk_engine_permission_test.cc`、`data_model_test.cc`。
  **📌（2026-09-22 交付登记 · **已完成，待独立复核** · 只加不改）** 实例 `be-t126`（**唯一写者 `cpp/src/risk_state/**` + `cpp/src/risk_engine/**`**；不自评 `DONE`）。**改动 4 文件 / +77 −3**：① `cpp/src/risk_state/include/hg/risk_state/model.h:238-239` —— `PermissionEntry` 新增 `bool has_settle_role = false;` / `bool is_management_role = false;`（**直采协议名**，仅注释 + 字段；**未改既有 `is_responsible`/`has_risk_post` 名**）；② `risk_engine.cc:406` 的 `else if (p->is_responsible)` → **`|| p->is_management_role`**（→ `0x100F`）、`:411` 的 `else if (p->has_risk_post)` → **`|| p->has_settle_role`**（→ `0x100E`）；③ `risk_engine_permission_test.cc` **新增 3 条独立用例**（`test_settle_role_conflict` 仅结算岗→`0x100E`、`test_risk_post_only_conflict` 仅风控岗→`0x100E`（**显式 `has_settle_role=false` 证析取**）、`test_management_role_forbidden` 仅管理层→`0x100F`），用例 8→**11**、本次 **29 checks / 0 failures**；④ `risk_state/tests/data_model_test.cc:145,268-273` 新增「两字段经 `LoadBatch`→`Snapshot` 承载」往返断言（**72 checks / 0 failures**）。**★「改前确实存在缺陷」已复现**：HEAD 实测 `grep -rn "has_settle_role\|is_management_role" cpp/` = **0 命中**（结算岗信号无载体）；隔离副本「消费全回退」后自建探针实测 **`仅具结算岗 → decision=kPass code=0x0000 【放行】`**（**正是缺陷态**）+ `仅具管理层 → kPass/0x0000`，同时 permission 用例 **4 处 FAIL（`:88/:89`、`:123/:124`）**。**★反退化双向各自承重**：`M-SETTLE`（仅移除 `|| has_settle_role`）⇒ 仅结算岗 **2 处红**、管理层绿；`M-MGMT`（仅移除 `|| is_management_role`）⇒ 仅管理层 **2 处红**、结算岗绿；`M-FULL`（两者都移除）⇒ **4 处红**；复位 + clean 重编译全绿。**门禁**：构建 **0 warning / 0 error**（`-std=c++11 -Wall -Wextra -Werror`）、`ctest` **33/33**（名单以 `ctest -N` 为准）、聚合 **`rc 0`**（`E1`–`E7` 全 0）、**`E4` 实扫 47 文件 / 命中 0**（`WARN 3` 为既有未覆盖判据 C/D 信息提示，**非本次引入**、**未新增锁/同步 I/O/分配**）。**冻结点**：`risk_state` = `b47c2fd38cac4e369644f9c8dcdaecc6`/**18**（改前 `d89387cb…`/18）、`risk_engine` = `24df98dcb72799c634fc53bb46d4823c`/**29**（改前 `35cf0818…`/29）。**隔离残留 = 0**；副本 4 文件与共享树**逐字节一致**（故「隔离等价于共享交付态」）。**✅ 口径说明（照裁定第 6 条，防「生产已生效」的过度断言）**：本任务**只交付模型字段 + 引擎消费**；**该两字段当前无生产加载点**（4 布尔映射归 `T-038`、Java 侧 `DB→wire` 组装归 `T-042`）⇒ **生产路径下两字段恒为缺省 `false`**；用例为**直接构造 `PermissionEntry`** 验证消费逻辑 —— **实施方主动写明此点，正确**。**⚠️ 一项观察已由本席转出裁定（不阻断本单）**：`RiskSnapshot::canTrade`（`risk_snapshot.cc:154` / 声明 `risk_snapshot.h:124`）**亦为权限消费点**，但**只判 `is_responsible` + `has_trading_post`**、**不含** `has_risk_post` 与新两字段；本席一手复核确认它 **`src/main` 无生产调用点**（仅 `data_model_test.cc:275-279` 调用）⇒ **已派只读裁定 `tl-risk-ruling`**：判其性质（第二处同类缺口 / 有意为之 / 不可判定）、架构有无明文、是否待接线、若须补齐则**另立号**（**不得并入已交付的 `T-126`**，以免作废其冻结点），并**穷举**两模块其它读取 `PermissionEntry`/`PermissionSnapshot` 的判定函数（**「同类是否还有第三处」**）。
  **📌（2026-09-22 独立复核 = `通过` / 0 阻断缺陷 · 3 项观察 · 转 `DONE` · 只加不改）** 复核者 `te-verify-t126`（**自撰探针 `/root/t126-probe-post.cc` + `-plain.cc`，未复用实施方用例**；变异在**独立源树 + 全量重编译**）。**★它比产出方深一层**：不仅复现「**消费**缺口」，还复现「**承载**缺口」的**编译级**证据 —— 改前锚（`git 0e0d192`）下置新字段 ⇒ `error: 'PermissionEntry' has no member named 'has_settle_role' / 'is_management_role'`。**逐条**：① **改前确坏**：仅回退 `risk_engine.cc` ⇒ **`仅具结算岗 → kPass/0x0000`、`仅具管理层 → kPass/0x0000`**，且其自身用例 `29 checks / 4 failures` 红在 `:88/:89`、`:123/:124`。② **改后确好**：`仅结算岗 → kReject/0x100E`、`仅管理层 → kReject/0x100F`、**`普通交易岗 → kPass/0x0000`（无误伤）**。③ **反退化三分支各自承重**（各独立源树、全量重编译、注入时断言 `count==1`）：`M-SETTLE` → 仅 `:88/:89` 红；`M-MGMT` → 仅 `:123/:124` 红；`M-FULL` → 四红 ⇒ **无「仍绿⇒空转」**。④ **用例独立性**：`test_risk_post_only_conflict` **确把 `has_settle_role=false`** ⇒ **证出析取（非合取）**，**不构成缺陷**；`main()` 确注册 3 条（8→11）。⑤ **既有断言未削弱**：diff 逐 hunk 纯增（+77/−3）；并**独立证实**「**无断言依赖被改写的 `detail` 串**」（`grep '\.detail'` 于 risk_engine 测试 = **0**；全仓唯一 `detail` 断言在 `hgcp/envelope_test.cc:218`、与 `RiskResult::detail` 无关）。⑥ **口径边界属实**：全仓该两字段**仅声明 + 读、无任何写点**；映射侧确已分流 `T-038`/`T-042`。⑦ **`E4` 两口径 + 聚合**：默认并集根 **47 文件 / 命中 0 / `WARN 3`**（**改前同根 `WARN` 逐条相同 ⇒ 非本次引入**）、`--root risk_engine` 28/0/0、`--root risk_state` 17/0/3；聚合 **`rc 0`**（`E1`–`E7` 全 0）；**未引入锁/互斥/同步 I/O/分配**。⑧ **锚点 3/3 取样一致**、隔离副本 4 文件 vs `git 65f010f` = **4/4 MATCH**、共享树残留 **0**；**构建全量 0 warning / 0 error**、**全量 `ctest` 38/38 全绿**。**3 项观察处置**：**`OBS-T126-1`（信息·取证口径）⇒ 本席已就地订正**（自述「`ctest` 33/33」**在本树不可复现**〔现 **38**〕；归因 = **冻结时点后 `T-033` 并发向 `ctp_adapter` 增补用例**〔mtime 20:53–21:28〕、**非本单引入** ⇒ 已在 §1 标注「**33 为冻结时点口径**」并给出现行 38）；**`OBS-T126-2`（低）** ⇒ `model.h:231` 注释引「`§4.2.3 :818`」而该行现为 `:823`（**固定行号漂移**）⇒ **已折入 `T-127` 验收**（改为章节锚点）；**`OBS-T126-3`（低）** ⇒ `data_model_test.cc:272-273` **`is_management_role=true` 正向承载未断言**（两字段同构、风险低）⇒ **已折入 `T-127` 验收**（补对称断言）。**未决**：无阻断存疑；`canTrade` 缺新两字段一事**已另立 `T-127`**、**不构成 `T-126` 缺陷、不并入本单**。**⚠️ 复核方另指出我派单的一处陈旧**：其开工时派单所载「126 任务 / 1816 人时」**当时已陈旧**（`T-127` 于第 70 轮新立 ⇒ 现行 **127 / 1818**）—— 属「**写死数字必陈旧**」的同类现象，本席记之（派单宜写「**以实时为准**」）。
  **📌（2026-09-22 `T-126` **增量复检（delta re-check）= 通过 / 0 阻断缺陷** · 2 项信息/低观察 · 只加不改）** 复核者 `te-verify-t126b`（**新派生实例**：原复核席 `te-verify-t126` 已**下线**、消息只能入队 ⇒ 见第 76 轮台账；对「等价性」而言冷眼复核更好）。**触发**：`T-127` 为抽「共用纯函数」**触及 `cpp/src/risk_engine/**`** ⇒ `T-126` 的冻结点前移（`24df98dc…`/29 → **`09b88329…`/29**；`risk_state` `b47c2fd3…`/18 → **`3f4e4750…`/18**）⇒ 依「**冻结后要改须显式重锚 + delta 复检**」纪律复核。**★核心判据 = 机械对拍（**我明确要求不得只用「测试还绿」**）**：自撰穷举探针 `/root/t126b/probe/diff_probe.cc` —— **冲突四位全 16 组合 × `has_trading_post` × 授权位 × 额度位 = 256 工况 + 无记录 1 例 = 257 行**；**新**（现树，调用共用纯函数）vs **旧**（`risk_engine.cc` 以 `git show HEAD:` 还原为原内联布尔表达式）：**`decision` + `code` + `rule` 逐行完全相同、`diff | wc -l` = 0** ⇒ **`T-127` 对 `evalPermission` 的改写 = 严格语义等价（no-op）** ⇒ **冻结点前移不改变 `T-126` 的三判据**。并**逐字核验**：两处替换均为「同一布尔表达式 → 函数调用」、**操作数顺序逐字保持**（`is_responsible` 先于 `is_management_role`；`has_risk_post` 先于 `has_settle_role`）⇒ **短路顺序未变**；`0x100F`/`0x100E` 的 code/detail 文案未改。**反退化（「共用」名副其实）**：不改交付制品、变异体走独立 `-I` 搜索路径且 `-H` 证实解析到变异件 ⇒ 去掉 `is_management_role` / 去掉 `has_settle_role` **均使两消费点同时红**（`data_model_test.cc:381` 且 `risk_engine_permission_test.cc:123/:124`；镜像方向 `:366` 且 `:88/:89`）⇒ **无「仅一处红」**。**`T-126` 三判据独立复现** ✅（仅结算岗→`0x100E`／仅风控岗→`0x100E`〔`has_settle_role=false` 证析取〕／仅管理层→`0x100F`）。**`E4`/聚合/端到端**：`E4` 三口径（`risk_state` 17 / `risk_engine` 28 / 默认并集 47）**均 `rc 0`**、**无新增锁/互斥/同步 I/O/分配**；聚合 **`rc 0`**（`E1`–`E7` 全 0）；隔离全量 `-j1` 构建 **0 warning / 0 error**、`ctest` **38/38**（`risk_engine_permission_test`、`data_model_test` 均 Passed）。**锚点 3/3 一致**、隔离副本 5/5 MATCH、**共享树残留 0、共享源树零写入**。**⚠️ 它主动做的「分栏」（这正是我要的纪律）**：同探针显示 **`RiskSnapshot::canTrade` 非等价**（**56/256 工况 `true→false`、反向 0 例** = **单向收严、无放行回归**）—— 它明确声明**这是 `T-127` 的交付意图本身**（补判 3 个岗位位）、**不据此判 `T-126`**、其正确性**属 `T-127` 自身验收面**。⇒ **避免了一次「把有意的行为收紧误报为语义变更」的过度断言**。**2 项观察**：**`OBS-T126b-1`（信息）** = 派单所载「CTP 可得 38 / 不可得 34 **差 4 = `ctp_smoke` 类 target**」**实质成立但表述不精确** —— 实测差的 4 条为 `ctp_adapter.ct_{lifecycle_ctp,mapping,spi}_test` + `rsk02.ctp_header_cxx11_compat`，其中**仅 1 条**来自 `ctp_smoke` 探路、**另 3 条**来自 `ctp_adapter` 的 `CTP_FOUND` 轨（`cpp/src/ctp_adapter/CMakeLists.txt:75-81`）⇒ **本席据此更正表述**；**`OBS-T126b-2`（低）** = `data_model_test.cc` 重构时**移除了 `T-126` 原断言 `HG_CHECK(!perm->is_management_role)`**（「未置位者保持缺省 false」），改为在 `9101` 断言**显式置位保持 true**（**正是 `T-126` 的 `OBS-3` 建议**）⇒ **正向承载获补，但「缺省 false 保持」不再有直接断言**（仅由 `!s.canTrade(9001,…)` 间接蕴含）。**处置**：**不另立号** —— 因 `T-127` **仍在「待复核」**、`te-verify-t127` **正在复核其交付物本身** ⇒ **由该复核结论决定**；**若复核确认此为一个（低）缺陷 ⇒ 按既有流程「退回小修」，无需新编号**（这比为一个断言开新任务更符合既有流程）。**证据章** `docs/TEST_RESULT.md` `:4634` 起（**纯追加已证**：`head -4630` = `69fa98ab…` → 现 `cf912232…`/4787）。
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
- **状态**：**DONE（授权 + 独立验证）**〔第 47 轮〕（独立复核 `te-verify-t096` = **通过、0 阻断缺陷**；**编排者 2026-09-22 授权**。**验收证据（复核者独立实测，非采信自述）**：`mvn -B -o clean test` **464/0/0/0** + `BUILD SUCCESS`（隔离副本）；`@Test` grep **464** == surefire **464**（裸 grep 与「行首注解」两法各 464）；`arch-check` 聚合 **`rc 0`**（`通过(0)=7/命中(0)=0/用法或前置(2)=0`）；`E5` 单跑 **`rc 0`**；冻结点 `java/src` = `d71105d257ca82a297f0450ca470ea46` / **320 文件**（3 次取样一致）；共享树**变异残留 0**；**承重判据全部独立成立**（默认路径全序 / 多键逐键白名单 / 反退化必红 / 归属判定「多键本批引入」经 git + 快照**双重独立证据**）。**6 项观察去向**：`O-1`（`DmTradePlanDaoScopedSqlTest` 子串断言对 `ORDER BY` 形状**失去守卫能力**；产出方已披露）⇒ **维持现状**（形状守卫由 `DmTradePlanStableOrderTest` 承接）；`O-2`（**同类未披露**：`DmProductDaoScopedSqlTest:79`/`:148` 亦为子串断言，且**产品侧无**等价稳定排序守卫 —— **编排者已实测确认**）与 `O-4`（`orderByEnsuringUnique` 的「已含唯一列」判定**大小写敏感**）⇒ **`T-125`**；`O-3`/`O-5`/`O-6` ⇒ **信息级、不另立**。**证据章**：`docs/TEST_RESULT.md` `:3167`（复核证据；**纯追加已证** —— `head -3165` = 追加前基线 `1978bbbe93a1ed9a0cd3b50f1fc10202`）+ `:3292`（**二次独立复算**，复报通过）+ `:3345`（`BUG-001` 复检记录）。**⚠️ 台账勘误**：本行原为 `TODO`（第 43/44 轮只回写了 §1 状态与备注，**§2 状态行漏改**）⇒ 本轮一并订正。
- **验收标准**：
  1. **落点**：`java/src/main/java/com/hedgeguard/dao/PageQuery.java`；**新增重载** `of(page, size, sort, defaultSortField, defaultDirection)`，`defaultDirection ∈ {"asc","desc"}`（大小写不敏感；非法值抛 `IllegalArgumentException`）；
  2. **向后兼容**：现 4 参形式 `of(page, size, sort, defaultSortField)` **语义不变**——未传 `sort` 或缺方向时**缺省恒 `ASC`**（即 4 参形式等价于新重载以 `"asc"` 传参）；既有调用点（`web/handler/plan/PlanHandler.java:134`）**无需改动**且行为不变；
  3. **能力**：5 参形式在「未传 `sort`」或「只给字段名不给方向」时，方向取 `defaultDirection`（**可表达倒序**）；`sort` **显式给出方向**时**以 `sort` 为准**（覆盖 `defaultDirection`）；
  4. **单测两例**（`src/test`）：① 5 参传 `"desc"` 且不传 `sort` ⇒ `direction() == "DESC"`、`sortField() == defaultSortField`；② 4 参形式不传 `sort` ⇒ `direction() == "ASC"`（**回归护栏**）；
  5. **稳定排序（本轮追加）**：`PageQuery` 须支持**稳定排序** —— **多键**（`sort` 接受多字段，如 `"f1,dir1,f2,dir2"`）**或**自动末键追加**唯一 tie-break**（如主键），使**同一排序值跨页不重复、不漏行**；**4 参形式语义不变**（单字段 + 缺省 `ASC`）；**须有单测**：构造两个**同排序值**元素，断言**翻页前后无重复 / 无遗漏**（**当前单列 `ORDER BY` 下该用例必红**，即反退化自证）；
  6. `mvn -B -o clean test` **全绿**（**冻结点上**全量，无排除）。
- **产出**：`java/src/main/java/com/hedgeguard/dao/PageQuery.java`（新增重载；`sortField` / `ascending` 语义不变）+ 对应单测；**本轮追加**：`PageQuery` **稳定排序**支持（多键 / 唯一 tie-break）+ 对应单测
- **备注**：**向后兼容的新增重载**——**不改** 4 参形式语义（架构 §4.1.1 明写「4 参形式的缺省仍是 ASC」）。**边界**：本任务**只**动 `PageQuery` 公共件与其单测；**不得**顺手改动 §8.2 的 `PlanService` / `HedgePlanDao` / `Bootstrap`（§8.2④ 写死边界：`PlanService`+DAO+装配由**同一后端实例**改，且「**不得**顺手动其他模块的分页或 `PageQuery` 公共件」——此禁令约束的是§8.2 装配任务，本任务即 `PageQuery` 的**唯一授权改动单元**）。**根因与现状**：`PageQuery.java:62` 的 `boolean asc = true` 使调用方不传方向时**恒升序**，与多数列表 FR 的「最新在前」相反。`FR-PLAN-4` 曾因此出现**真实缺陷 `BUG-001`**（`docs/BUGS/BUG-001.md`，由 `backend-engineer#java` **2026-09-21 00:37 提交**、**状态「已修复，待复检」**——修复落在 **handler 层**：`PlanHandler.java:50` 增私有 `DEFAULT_SORT = "created_time,desc"` 兜底，`BUG-001.md` 明写「`PageQuery.java` md5 未变（本单不动公共件）」）。故本任务为**根因②（公共件缺「缺省方向」能力）**的收口，属**新增能力**，**不重复修 BUG-001**（该单已在 handler 层兜底闭环待复检）。**时点纪律**：`BUG-001` 结论以 `docs/BUGS/BUG-001.md` **实时内容**为准；引用架构锚点一律**以实时 `md5sum` 为准**。**handler 侧兜底保留（编排者 2026-09-21 裁定）**：`PlanHandler.java:50` 的 `DEFAULT_SORT = "created_time,desc"`（即 `BUG-001` 的修复）**保留、不迁移**——属**有意冗余**：handler 侧显式带方向，是对 `PageQuery` 缺省方向任何改动的**回归保护**（删掉反而降低安全性）。**📌 本轮追加「稳定排序」要求（来源 = `T-103` 交付披露 (b)，编排者 2026-09-21 裁定）**：`T-103` 分页化后 `ORDER BY` 仅**单列**（`exec_deadline ASC`），而旧非分页版为 `exec_deadline ASC, id ASC` ⇒ **同 `exec_deadline` 值跨页可能重复或漏行**（经典分页缺陷，**绿测不会暴露**）；根因 = `PageQuery` 的 `sort` 只支持**单字段**（`field,asc|desc`）。故本任务须一并支持**稳定排序**（**多键**或**自动补唯一 tie-break**）。**估时判据（项目经理独立判断）**：原 **2 人时**为「缺省方向重载 + 2 例单测」；本轮追加**多键 / 唯一 tie-break**，涉及 `sort` 解析（多字段）、`ORDER BY` 多列拼装与**跨页稳定性单测** ⇒ **由 2 人时上修至 3 人时**（**+1**）。**依赖关系（本轮确立）**：**`T-103` 其分页排序的稳定性依赖本任务**（`T-103` 当前无 tie-break）；`T-103` 已改分页信封，**本任务须在其交付后收口**（`T-103` 已交付、待独立复核）。 **📌 其跨页稳定性判据当前为红（来源 = `T-103` 独立复核者实测并复现；`docs/TEST_RESULT.md` §`T-103` 节；**以实时 `md5sum` 为准**）**：`T-103` 分页版 `ORDER BY exec_deadline ASC` **单列、无 tie-break** ⇒ 15 条同 `exec_deadline`、`size=10` 时 page1 `[1..10]`、page2 `[5,4,3,2,1]`，**跨页重复 `id=[1..5]`、漏行 `id=[11..15]`**；**本任务验收 5 的跨页稳定性单测当前应为红** ⇒ **`T-096` 落地后该判据必须转绿**，否则不得判完成。 **📌（2026-09-22 编排者裁定 · **边界修订** · 只加不改）** 原边界「**只**动 `PageQuery` 公共件与其单测」**修订为**：「**只动 `PageQuery`（公共件）+ 其单测 + 生产侧两个 `ORDER BY` 装配点**（`java/src/main/java/com/hedgeguard/dao/impl/AbstractDmDao.java` 与 `DmConfigChangeLogDao.java`，**各 1 行**）」。**理由**（实施方 `be-t096` 开工前实测并**先报后改**）：两处装配点均为**单键**拼装（`" ORDER BY " + query.sortField() + " " + query.direction()`）⇒ **若只加 `PageQuery` 能力而不接线，稳定排序在生产不生效，且新方法成"零生产调用点"的死代码**（与「无死代码」纪律、以及本任务立项理由——`T-103` **已用真实 DAO 复现**的生产症状——**双重冲突**）。⇒ 属「**边界写得早于事实（装配点当时未知）** ⇒ 边界应服务于用途、不得让用途落空」一类，故就地修订。 **附加硬要求**：① 装配点须**穷举扫描**并**列清单**（若多于两处 ⇒ **先报**、**不擅自扩大**）；② 稳定排序须在 **DAO 级**给「**两页并集基数**」判据（真实 DAO + 假引擎）+ **反例**（去 tie-break ⇒ **必红**）；③ `AbstractDmDao` 是**共享装配点**（`findPageScoped` 等经它）⇒ 须给**全量回归**（基线 **446**：`mvn -B -o clean test` 全绿·无排除、`@Test` == surefire、`arch-check` 聚合 **0**）。 **采纳实施方两处提议**：多键形态 `"exec_deadline,asc,id,asc"`；`sortField()` / `direction()` **返回首键**以保**向后兼容**（须有 4 参形式回归护栏用例）。


  **📌（2026-09-22 `T-096` 边界**第三次**修订 · 追加授权 · 只加不改）** 实施方交付后**自报关键未决项**：`TradePlanHandler:94` 在客户端不传 `sort` 时传**单键** `PageQuery.of(..., "exec_deadline")` ⇒ 装配点输出仍为 `ORDER BY exec_deadline ASC`（**无 tie-break**）⇒ **`T-103` 复核记录的生产症状（跨页重复 / 漏行）在**默认路径**未消除**；稳定排序仅在调用方主动传多键时生效。**编排者裁定**：**这不是「范围外」——它使本任务的立项理由落空，属**边界错**，就地修订**（依据：**立项理由若是生产症状，修复点必落在授权范围内**；同类先例 = 本任务上一次边界修订）。 ⇒ **补充授权三项**：**① 默认路径必须稳定**：在**分页装配层**（`AbstractDmDao` 的 `ORDER BY` 装配点）保证 `ORDER BY` 为**全序** —— 排序键不含唯一列时**自动追加主键 tie-break（`ASC`）**、已含则不重复追加；**须先穷举 `AbstractDmDao` 子类核实 PK 可取得**，**不得写死 `id`**，取不到则**停手上报**。**② 多键须逐键过白名单**：`PlanService`/`VarietyService` 现仅校验**首键** —— **须先判归属**（多键支持是否由本任务引入）：本任务引入 ⇒ 本批闭环；先前已有 ⇒ **单列报告、另立号**。**③ 被本修订推翻的既有断言须订正**：`DmTradePlanDaoScopedSqlTest`（约 `:80`）断 `ORDER BY exec_deadline ASC` —— **该断言锁的正是缺陷形状** ⇒ 须订正并在测试处**加注说明**（「原断言锁定无 tie-break 的缺陷形状，该形状已被边界修订判为缺陷」）、**列全文件+行+前后值**、**理由只能是需求、不得为过测试而放宽断言**。**承重判据**：**默认路径**（不传 `sort`）真实 SQL 须为 `ORDER BY exec_deadline ASC, id ASC`、两页并集 = 记录数、**无重复无漏行**；**反退化**（去掉自动 tie-break）⇒ **必红**；复位 clean 重编译回绿。**备注**：交付方原 `singleKeyOrderByDuplicatesAndMissesRows` 用例**断言的是缺陷存在**（与 `T-103` 记录逐值吻合）⇒ 改后须**反转**为「默认路径稳定」的正判据。**`T-096` 在本补充交付并过独立复核前不得置「已完成」。**

  **📌（2026-09-22 追加授权交付登记 · 只加不改）** 实施方已交付**全部三项补充**（锚 = `d71105d257ca82a297f0450ca470ea46` / **320 文件**，**编排者独立复测一致**）：**(a) 装配层自动 tie-break** —— `PageQuery` 新增 `sortFields()` / `orderByEnsuringUnique(String)`；两处装配点（`AbstractDmDao:163`、`DmConfigChangeLogDao:138`）改走 `orderByEnsuringUnique(<PK>)`；**穷举**：逐行拼接的 `" ORDER BY "` 装配点实测**恰 2 处**、`query.orderBy()` 残留 **0**；**PK 走 `idColumn()` 访问器**（43 个子类全实现、实测均返 `id`；非子类的 `ChangeLogDao` 用自身 `ID_COLUMN`）—— **未写死 `id`**；**默认路径**（不传 `sort`）真实 DAO SQL = `ORDER BY exec_deadline ASC, id ASC`；15 条同值 / `size=10` 翻两页 ⇒ **并集 15、去重 15**。 **(b) 多键逐键白名单** —— `PlanService`(`:1431`/`:1586`)、`VarietyService`(`:212`) 改**逐键**校验；新增「第 2 键非法 ⇒ 拒（不触达 DAO）」与「全合法多键 ⇒ 接受」用例。**归属判定 = 本批引入 ⇒ 本批闭环**，依据 = `git show 772bd27:java/src/main/java/com/hedgeguard/dao/PageQuery.java` 为**单键**实现（仅解析 `parts[0]`/`parts[1]`，第 3+ 片段**静默忽略**）。 **(c) 断言订正 3 处**（均加注「原断言锁定的是『无 tie-break』的缺陷形状，该形状已被 `T-096` 边界修订判定为缺陷，故随修复一并订正」）：`DmTradePlanStableOrderTest` 的「断言缺陷存在」两例**反转为** `defaultPathIsStableAcrossPages` / `defaultOrderByAddsPrimaryKeyTieBreak`；`PageQueryTest` 的 `singleKeyOrderByCanDuplicateAcrossPages` → `singleKeyCompletedToTotalOrder`。**⚠️ 一条守卫强度事实（留痕，勿当缺陷）**：`DmTradePlanDaoScopedSqlTest:80` 的断言实测为 **`contains`（子串包含）** ⇒ 加 tie-break 后仍绿、无须订正，但**该断言在 tie-break 被移除时也不会变红**（对 ORDER BY 形状已无守卫能力）；新增的 `DmTradePlanStableOrderTest` 已补上该形状守卫。 **自证门禁（生产者自报，待独立复核）**：`mvn -B -o clean test` **464/0/0/0**、`@Test` grep **464** == surefire **464**、`arch-check` 聚合 **`rc 0`**（`E1`–`E7` 全 `0`）；**反退化**：还原 `orderBy()` ⇒ 默认路径两判据**红**（`expected <exec_deadline ASC, id ASC> but was <exec_deadline ASC>`）、白名单退化为仅首键 ⇒ 3 个逐键用例**各红**；复位 clean 回绿。**隔离副本内变异、共享树 `MUTATION`/`.orig` 残留 = 0**；`cpp/**`、`docs/**`、`tools/**`、`proto/**` **零改动**。**独立复核 `te-verify-t096` 进行中 ⇒ 不置 `DONE`。**
### T-127 `RiskSnapshot::canTrade` 岗位维度判定补齐（与 `evalPermission` 对齐）

- **需求追溯**：`NFR-SEC-5`、`FR-RISK-7`、`R-RISK-7`、`ARCHITECTURE.md` §4.3.2（`0x100E` 风控岗/结算岗、`0x100F` 负责人**或**管理层）、`CH-010`（**析取**）
- **负责人**：backend-engineer#cpp
- **优先级**：**P2**（**潜伏缺口**：`src/main` **无调用点**，仅自身单测调用 ⇒ **非现行运行时缺陷**；但**一旦接线即为真权限绕过** —— 原因见备注「防第三次分叉」）
- **依赖**：`T-126`（已交付）、`T-034`
- **估时**：**2 人时**
- **来源**：新增（`T-126` 交付时**实施方 `be-t126` 主动披露** + 技术席只读裁定 `tl-risk-ruling` 判 **①「第二处同类缺口」**；其**同类穷举结论 = 生产判定消费点共 2 处、无第三处**）
- **状态**：**TODO**
- **验收标准**：
  1. **三个独立单条件用例**（仅 `has_risk_post` / 仅 `has_settle_role` / 仅 `is_management_role`）**各自** ⇒ `canTrade == false`；**三条不得互为前提**（照 `CH-010` 与 `T-126` 先例，**须给仅置单条的构造**）。
  2. **反退化**：移除任一新条件 ⇒ 对应用例**必红**；复位 + clean 重编译回绿。
  3. **★抽共用纯函数**（如 `permissionPostConflict(p)`），供 `evalPermission` 与 `canTrade` **共用** ⇒ **从根上消除两处再分叉**（本缺陷的产生机制即「消费点分散、无清单」）。**若判定超出 2 人时**，可在交付报告说明实际人时并**建议上修**（按 `T-102` 先例）。
  4. **与 `evalPermission` 的岗位维度判据逐条一致**：须给**对照表**（条件 → `canTrade` 结论 → `evalPermission` 结论）。
  5. **不适用项须显式列出**：`canTrade(operator, sub, product)` **无手数入参** ⇒ 「**超个人额度**」（`individual_quota`）在其签名下**不可表达** ⇒ **非本项缺口**（不得为此改签名）。
  6. **门禁**：构建 **0 告警**（`-std=c++11 -Wall -Wextra -Werror`）、`ctest` **全绿**（**名单以 `ctest -N` 为准**）、聚合 `bash tools/arch-check/arch-check.sh` **`rc 0`**（`E1`–`E7` 全 0）、**`E4` 实扫命中 0**（`cpp/src/risk_state` **在 `E4` 默认根内** ⇒ 不得引入锁/互斥/同步 I/O/新增分配）。
  7. **产出面**：`cpp/src/risk_state/**`（`risk_snapshot.cc` / `risk_snapshot.h` / `tests/data_model_test.cc` / `model.h`）—— **不动 `cpp/src/risk_engine/**`**（避免与 `T-126` 的冻结点冲突）。
     - **📌（2026-09-22 编排者改判 · 只加不改）本条与验收 3 自相矛盾、就地修订**：验收 3 要求「**抽共用纯函数**」供 `evalPermission` 与 `canTrade` 共用 ⇒ **必须触及 `cpp/src/risk_engine/**`**，而原写「**不动**」⇒ **两处不可能同时成立**。**裁定**：**授权扩面至 `cpp/src/risk_engine/**`**（理由：验收 3 是**根治分叉**的唯一手段，且 `T-126` **已交付并经独立复核通过**、不再是「在飞写者」⇒ 原「避免冲突」的前提已消失）。**代价与配套**：该改动会**前移 `T-126` 的 `risk_engine` 冻结点**（`24df98dc…`/29）⇒ ① 改动须**语义等价**（`T-126` 的三条岗位判据逐条不变，验收 4 的对照表即为此用）；② **交付时须显式声明「`T-126` 的冻结点已前移、需 delta 复检」**，由编排者安排；③ **纯函数宜落 `risk_state` 侧**（如 `model.h` 的 inline 自由函数，因它判的正是 `PermissionEntry` 自身的岗位冲突）⇒ `risk_engine.cc` 只把两处内联条件改为调用，**改动面最小**。
  8. **（2026-09-22 由 `T-126` 复核观察 `OBS-T126-2` 折入）** 订正 `model.h` 中**引用架构规范却使用固定行号**的注释（`:231` 引「`§4.2.3 :818`」，而该行现已漂移为 `:823`）⇒ **改为章节锚点**（`§4.2.3`），消除固定行号漂移。
  9. **（2026-09-22 由 `T-126` 复核观察 `OBS-T126-3` 折入）** `tests/data_model_test.cc:272-273` 现仅断言 `has_settle_role=true` 与 `is_management_role` 缺省 `false` ⇒ **须补 `is_management_role=true` 的正向承载断言**（两字段**对称**，避免单侧遗漏）。
- **产出**：`cpp/src/risk_state/**` 修改（+ 视验收 3 而定，可能含**共用纯函数**的落点）
- **备注**：**① 责任归属（据实留痕）**：技术席裁定明确「**这不是实施方 `be-t126` 的遗漏，而是我上轮裁定只点名 `evalPermission` 的范围缺口**」—— `T-126` 的 `model.h:233-235` 注释「消费见 `risk_engine.cc` 的 `evalPermission`」**忠实执行了该裁定** ⇒ **责任在裁定侧，不在实施侧**。**② 架构无明文**：`grep 'canTrade\|findPermission' docs/ARCHITECTURE.md` = **0 命中**；§3.4（`risk_state`）「对外接口」**只列** `snapshot()` / `rebuild()` 且**未授予任何判定职责**（判定在 §3.3 `risk_engine`）；§4.4.1 **只把 `PermissionSnapshot` 列为数据集**（非判定函数）⇒ `canTrade` 系**实现自行添加、无架构承载**。**③ 可达性**：`src/main` **0 调用点**（唯一调用者 = `data_model_test.cc:275-279`）⇒ **潜伏缺口**。**④ 备选处置（本席未采、备查）**：**删除或降级**（更贴合 §3.4「`risk_state` 不授判定职责」）—— 未采因需**产品席确认**「C++ 侧是否存在『可否交易』查询需求」且会**失去既有测试覆盖**；**若产品席将来确认无此需求，可改采此支**（此时须同批处理 `data_model_test.cc:269-273`/`:275-279` 的断言）。**⑤ ★防第三次分叉（本条即为「消费点清单」，登记义务）**：**岗位维度判定消费点 = 2 处** —— `evalPermission`（**权威**，`risk_engine.cc:396-442`，`T-126` 已补 3 条）＋ `canTrade`（**辅助、无调用点**，`risk_snapshot.cc:154-168`，缺 `has_risk_post`/`has_settle_role`/`is_management_role`）；**穷举口径** = `grep "is_responsible\|has_trading_post\|has_risk_post\|has_settle_role\|is_management_role\|allow_all_*\|individual_quota\|permit_type"` 于两模块，**并加做全 `cpp/` 复核 ⇒ 无模块外命中**；**不适用项**：`findPermission`（`:148-152` 访问器）、`permissions()`（`risk_snapshot.h:71`）、`load_batch.cc:84-87`、`load_batch.h:96`、`individual_quota`（**签名无手数 ⇒ 不可表达**）⇒ 均**非消费点**。**将来若给 `canTrade` 接线，必须同批补齐岗位维度**（此为**硬约束**）。**⑥ 可选（本任务不代写）**：在 `ARCHITECTURE.md` §3.4 或 §4.4.1 加**一行注**列出上述两个消费点（`docs/**` 归 `tl-arch-cd`）——**是否落由技术席/编排者定**。

### T-125 `T-096` 复核观察收口：产品侧 `ORDER BY` 形状守卫补齐 + `orderByEnsuringUnique` 大小写不敏感

- **需求追溯**：`FR-PRODUCT-5`（默认按创建时间倒序）、`ARCHITECTURE.md` §4.1.1；依据 = `T-096` 独立复核观察 `O-2` / `O-4`
- **负责人**：backend-engineer#java
- **优先级**：P2
- **依赖**：`T-096`（已 `DONE`）
- **估时**：**2 人时**
- **来源**：新增（来源 = `T-096` 独立复核 `te-verify-t096` 的观察 `O-2`（**同类未披露**）与 `O-4`（低）。**均为非阻断**，依本项目「**已知隐患须落任务表**」纪律立号承接，防「后来者重复发现」。**`O-1` 同类已裁定「维持现状」** —— 因为其形状守卫由 `DmTradePlanStableOrderTest` 承接；**本号只收 `O-2`/`O-4`**）
- **状态**：TODO
- **验收标准**：
  1. **产品侧 `ORDER BY` 形状须有真实守卫**：现状 `DmProductDaoScopedSqlTest` 的两处断言为 **`contains`（子串）**（`:79`、`:148` 断 `ORDER BY created_time DESC`）⇒ 加了 `id` tie-break 后**仍绿** ⇒ **对形状已无守卫能力**；且**产品侧无**等价于 `DmTradePlanStableOrderTest` 的稳定排序用例（**编排者已实测确认**）。⇒ **二选一并取证**：(a) 新增产品侧稳定排序/形状判据（参照 `DmTradePlanStableOrderTest` 的做法：真实 DAO 产 SQL + 两页并集基数 + 反例）；或 (b) 把该两处断言改为**精确/全等**形态（含 tie-break 子句）。
  2. **反退化**：去掉 `id` tie-break ⇒ 上述守卫**必红**（给红的具体断言与数字），复位 clean 后回绿。
  3. `orderByEnsuringUnique` 的「**已含唯一列**」判定改为**大小写不敏感**（避免非白名单路径传大写 `ID` 时产生冗余键 `ID ASC, id ASC`）；给用例覆盖 `id` / `ID` / `Id` 三种写法**均不重复追加**。
  4. **不得为过测试而放宽断言**；若改动既有断言，须在测试处**加注说明**理由（属「形状守卫补齐」）。
  5. `mvn -B -o clean test` 全绿且 **`@Test` 计数 == surefire 名单计数**（两数都报）；`bash tools/arch-check/arch-check.sh` 聚合 **`rc 0`**。
- **产出**：`java/src/test/**`（+ 必要时 `java/src/main/java/com/hedgeguard/dao/PageQuery.java` 的判定逻辑）
- **备注**：**低优先、非阻断** —— `T-096` 的门禁结论**不因本任务而变**（复核已判**通过、0 阻断缺陷**）；本任务只收口**守卫强度**与**低危边界**两项观察。**勿与 `O-1` 混淆**（已裁定维持现状）。**勿把「子串断言仍绿」当作「仍被守卫」**（本会话已入库的纪律：`contains` 会随取值增长而静默失去守卫力）。

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
- **状态**：**DONE**（判据 = **授权 + 独立验证**；**交付形态 = (c) 删除**〔**改判已登记**，见下方 📌〕；**授权** = 编排者 2026-09-21；**实施** = 两阶段（阶段一 `be-t099` 弃用形态 → **编排队改判** → 阶段二 `be-t099b` 删除）；**独立复核** = `te-verify-t099` **通过、0 阻断缺陷**。**验收证据**：① `scopeOf` 在 `java/src` **无声明/无实现/无调用** —— 源级 `grep` 仅剩 javadoc/守卫引用，**字节码级** `javap -p` 与 `grep -ra scopeOf target/classes` **零命中**（主源）；② 删除面清点 = **2 main**（接口声明 + 实现）+ **5 个测试假件 override**（5 者均 `throw unsupported`，删后无削弱）；③ **反射防复活守卫** `T099ScopeOfDeprecationTest`（`scopeOfMustNotBeRedeclared` + `interfaceContractIntact`）**双向承重**：只加回实现 ⇒ 红；接口 + 实现 + 5 假件全量加回 ⇒ 红（两次均 **clean 重编译**）；④ 冻结全量 **`446/0/0/0`**（基线 444 ⇒ 净 +2），`@Test` grep `446` == surefire `446`；⑤ **零新增告警**（`git archive HEAD` 取基线、基线与现行各 `clean test-compile`、`[WARNING]` 归一后 `diff` **空**；基线 1 条 == 现行 1 条同位置）；⑥ **`java/CONTRACTS.md` 勘误** diff **恰 3 hunk**（`:312`、§5.1 登记行、原 `:798`→现 `:799`），**未触契约实质**、`HGC-1.6` 不变、`:734`(T-105) 行完好。**证据章落点** = `docs/TEST_RESULT.md` 章 `# T-099 独立复核证据（事后补录，2026-09-21）`（`:2410` 起；**以实时 `md5sum` 为准**））
- **验收标准**：处置**二者择一**（须在工单交付报告中**写明所选分支与理由**）：
  **📌（2026-09-21 改判登记 · 只加不改）交付形态 = **(c) 删除**，非原文所设的 (a)/(b) 择一 —— 此处按 (c) 判定，勿按原文 (a)/(b) 字面判「未按验收交付」。** **授权链** = 编排者 2026-09-21 裁定；**依据** = ① 编排者 javac 探针（`/root/depprobe`，施后可复跑）：**接口方法未弃用 + 实现 `@Deprecated(forRemoval=true)` 时，经接口引用调用零告警**（仅具体类型调用告警）⇒ (b) 的弃用形态对本仓「注入接口类型 → 调 `scopeOf`」这一**真实场景零防护**，属「控制看起来有、实际没有」；② 实施方实证 **(a) 不可行** —— `SubAccountScope` **无法表达「无 scope」**（`AbstractDmDao.findPageScoped` 直接 `scope.sql()`，传 `null` ⇒ NPE；哨兵 `0` ⇒ `IN (0)` 对行级归属列恒空），且 `v1.5.8` 已把「无 scope」判定移到调用方 ⇒ 该签名与目标语义**不兼容**。**独立复核** = `te-verify-t099` **通过、0 阻断**（含**独立复现**该 javac 探针，判改判正当、删除完整且不过头）。
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
  6. **（第 91 轮补）`GET /product/export` 无数据语义**（`FR-PRODUCT-8` 输出列 `docs/REQUIREMENTS/PRODUCT.md:192`「CSV 文件；**无数据时提示"无可导出数据"**」）：无符合条件数据时**不得**返回「仅含表头的 CSV」；须返回**明确提示/错误信封**（码值与 `FR-PRODUCT-8` 口径一致）。用例 = 无数据导出 ⇒ 断言**不是**「200 + 仅表头」，且提示**文案或码可判定**。**依据** = `te-verify-t018` 观察 `OBS-T018-3`（`T-131` 备注早已声明「导出空数据语义归 `T-116`」而本任务验收**未落实** ⇒ **已声明未落实**）。
  7. **（第 91 轮补）`GET /product/change-log` 筛选输入**（`FR-PRODUCT-14` 输入列 `:279`「品种标识、**时间范围**、**变更类型筛选**（新建/修改/启用/停用）」）：`ProductChangeLogHandler` 增时间范围与变更类型筛选参数并下推至 SQL；输出**按时间倒序**（`FR-PRODUCT-14` 输出列 `:280`）。用例 = 指定时间范围/类型 ⇒ 结果全落在范围内、类型全匹配；**移除筛选后用例必红**。**依据** = `te-verify-t018` 观察 `OBS-T018-2`（`FR-PRODUCT-14` 的**筛选输入**在 `TASKS.md` 中**无任何承载**，已实测确认）。
- **产出**：`java/.../service/VarietyService.java`（`listProducts`/`exportProducts` 增筛选）、`java/.../web/handler/product/ProductHandler.java`（`ProductView` 增列）＋ 对应测试
- **备注**：**来源 = `te-verify-t105` 独立复核「范围外观察」（该复核者未立号、未改文档）**。第 1 项（「导出与列表筛选一致」**当前不成立**：`listProducts`/`exportProducts` **均无筛选参数**）+ 第 3 项（`ProductView` **未含**「子账户」「最近变更时间」）同属 **PRODUCT 读取面「需求面 > 实现面」缺口** ⇒ **合并为一个任务**（同模块、同来源、均属输入/输出面缺口；单列过细 ⇒ 依 §5.1「不到半天 ⇒ 合并到相邻任务」）。**估时判据（项目经理）**：两条端点的筛选入参 + 输出列补齐 + 用例 ⇒ **3 人时**。**写者边界**：`#java2`（PRODUCT 属主 `T-005`），与 `#java` 链**同一 `java/**`、同一时刻只允许一实例写**。
  **📌（2026-09-23 第 91 轮范围扩 · 只加不改）** 估时 **3 → 5 人时**（+2）；需求追溯增 **`FR-PRODUCT-8` 验收 3**（输出列「无数据时提示『无可导出数据』」，`PRODUCT.md:192`）与 **`FR-PRODUCT-14` 输入列**（`:279`「时间范围 / 变更类型筛选」）；验收标准补 **6/7**（见上）。**依据** = `te-verify-t018` 独立复核观察：**`OBS-T018-3`** —— `T-131` 备注**早已声明**「`FR-PRODUCT-8` 导出空数据语义**亦归 `T-116`**」，而本任务验收**未落实**（⇒ **「已声明未落实」**）；**`OBS-T018-2`** —— `FR-PRODUCT-14` 的**筛选输入**在 `TASKS.md` 中**无任何承载**（**已由编排者实测复核**：`grep` 全表无该 FR 的筛选面承载）。**裁定（编排者）**：**并入本任务**、**不另立号** —— 同模块、同「读取面『需求面 > 实现面』缺口」性质，且本任务**尚未开工**（合并成本低；与 `T-103`/`T-104` 因「被两次判为非本任务范围 ⇒ 单独立号以免持续掉队」的情形不同）。**连带账实**：总人时 **1831 → 1833**；M2 **839 → 841 人时**（本任务属 **M2**，不在 M1 清单内 —— 已实测 `M1` 里程碑行**不含** `T-116`）；`#java2` **624 → 626 人时**（任务数 44 不变）；里程碑占比按新分母 **1833** 重算。

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
  **📌（2026-09-21 `CH-012` 依赖状态登记 · 只加不改）** 依赖 `CH-012`（层③账户级写面授权口径，**L2 澄清性**）——**用户暂缓确认**（同日裁决）⇒ **`CH-012` 确认前本任务不得开工**。**主体范围已裁定**：可写主体 = **财务/风控岗 或 系统管理员**（**含 `SYS_ADMIN`**；依据 `FR-PRODUCT-11` actor 原文 + 对称先例 `FR-FUND-13` + `INDEX.md §4` 系统管理员职责含「风险阈值」+ `R-AUTH-10` 排除的是「风控**判定**」而非「配置」）——**该范围随 `CH-012` 一并待最终确认**。 **📌（2026-09-21 用户确认 · 只加不改）** `CH-012` **已确认**（主体 = **财务/风控岗 或 系统管理员（含 `SYS_ADMIN`）**；失败码 **`10003`**；**M1 角色近似 → M2 功能权限判据**）⇒ **本项依赖已满足**。⚠️ **但仍不可开工**：硬前置 = ① **功能权限链路无实现**；② **`t_role`/`t_user_role` 零播种**，且**播种须与实现同批**（不同批 ⇒ 修完即**全拒 = 误拒**）。⇒ 编排者裁定：**先落「播种 + 功能权限链路」再落本强制点**（建议由 `T-108`〔AUTH DAO〕/`T-009`/`T-010` 那一族承接前置）。

### T-119 补齐 `putQuota` **层③**（`ACCOUNT_PRODUCT` / 账户哨兵 `0`）**写面授权门禁**（M1 = 角色近似）

- **需求追溯**：`FR-AUTH-12`（功能权限维度；`AUTH.md` §2）、`FR-AUTH-13`、`NFR-SEC-7`、`R-AUTH-8`（越权尝试必留痕）；`CH-012`（需求侧澄清性变更、**待用户确认**）；依据 `ARCHITECTURE.md` §8.2③ 新增条目（登记时点 `v1.5.11`；**以实时 `md5sum` 为准**）
- **负责人**：backend-engineer#java（断言器 `assertCanConfigureAccountLevel` 落 `AuthService`）
- **优先级**：P0
- **依赖**：`CH-012`（**用户已于 2026-09-21 确认** ⇒ 依赖已满足）＋ **播种前置**（`t_role`/`t_user_role` 预置数据须与实现**同批**；现**零播种** ⇒ 否则**全拒 = 误拒**）
  **📌（2026-09-23 第 91 轮 —— 🔴 `CH-012` 状态三处冲突，提请用户裁定 · 只加不改）** 由 `te-verify-t018` 观察 `OBS-T018-1`（其原文仅点到「不一致」，本席**逐处实测复核后**定性如下）：**同一日期（2026-09-21）在四处给出两个相反的确认状态** —— **(a) 「已确认」侧**：`docs/REQUIREMENTS/INDEX.md` §10 `:666` **确认人列** = 「**✅ 已由用户确认（2026-09-21）**」（并载**确认范围**：主体 = 财务/风控岗 或 系统管理员（含 `SYS_ADMIN`）、失败码 `10003`、M1 角色近似 → M2 功能权限）；**本任务「依赖」字段即采此说**（「用户已于 2026-09-21 确认 ⇒ **依赖已满足**」）。**(b) 「未确认」侧**：`docs/REQUIREMENTS/INDEX.md` **§1 文件头 `:3`** = 「`CH-012` …（澄清性，L2，**待用户确认**）」；`TASKS.md` **本任务与 `T-118` 的 §2 备注**（2026-09-21「`CH-012` 依赖状态登记 · 只加不改」）= 「**用户暂缓确认** ⇒ **`CH-012` 确认前本任务不得开工**」（**同文件内与本行「依赖」字段直接矛盾**）；`docs/BOARD.md:392`（第 30 轮 **确认人列**）= 「用户（`CH-012` **暂缓确认**）」。**⇒ 影响面 = `T-118`/`T-119` 的开工许可**（若为「未确认」则两者须维持阻塞）。**本席不自行裁定**（涉及**用户决定**的记录，且属「结论必须后于事实」纪律约束区）⇒ **提请用户一句定案**；定案后须**同步**：`REQUIREMENTS/INDEX.md` `:3` 文件头（归**产品席**）、`T-118`/`T-119` 备注与依赖字段（归**编排者**）、`BOARD.md:392` 若需加注「其后已确认」（历史行**只加注不改写**）。**注**：另有 `TEST_RESULT.md:2660`（`T-032` 证据章）记「`REQUIREMENTS/INDEX.md` — 编排者（**`CH-012` 确认** + `CH-013` 编号登记）」⇒ **旁证指向「已确认」**，但**不构成用户决定本身的留痕**。

  **✅（2026-09-23 第 91 轮 · 用户定案 · 只加不改）** **用户裁定：`CH-012` = 「已确认（2026-09-21）」**。⇒ **冲突消解**，`T-118`/`T-119` 的 `CH-012` **依赖满足**（**仍受「播种硬前置」约束** —— 见本任务与 `T-119` 上方 2026-09-21「用户确认」条：`t_role`/`t_user_role` **零播种**且**须与实现同批**，否则修完即**全拒 = 误拒**）。
  **★据实更正本席上一条的定性（同轮）**：上条标题写「**状态三处冲突**」而正文写「**四处**」，**标题与正文自相矛盾**（标题漏「第四处 = 本任务 §2 备注」）；更重要的是，**「同文件内与本行『依赖』字段直接矛盾」这一说法过重、不成立** —— 实测 `T-118`/`T-119` 的 §2 **本就有先后两条「只加不改」条目**：**前者**（2026-09-21「依赖状态登记」）记「用户**暂缓确认**」，**后者**（2026-09-21「用户确认」）记「`CH-012` **已确认** ⇒ 本项依赖已满足」⇒ 二者是**`只加不改` 的前后时序留痕、后者取代前者**，**不是矛盾**。**live 陈旧点（本席第 91 轮判「唯一」，第 92 轮据实更正为 3 处）** = `docs/REQUIREMENTS/INDEX.md` §1 文件头 `:3` ＋ **`docs/REQUIREMENTS/PRODUCT.md:235`（`FR-PRODUCT-11` 处理规则「该主体范围结论待编排者/用户确认」）＋ `:245`（验收 5「范围待确认」）** —— **后两处系本席派单时误列为「已正确、勿改」的未实测断言**，由产品席 `pm-ch012sync` 实测发现并订正（`02d623cb…`）（仍写「待用户确认」）；`BOARD.md:392`（第 30 轮）属**历史轮次行**（只加不改）。⇒ **教训：判「矛盾」前须先看该文件是否采用「只加不改」体例** —— 追加式制品里的前后两说**默认是时序取代**，不是冲突。
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
  **📌（2026-09-21 `CH-012` 依赖状态登记 · 只加不改）** 依赖 `CH-012`（层③账户级写面授权口径，**L2 澄清性**）——**用户暂缓确认**（同日裁决）⇒ **`CH-012` 确认前本任务不得开工**。**主体范围已裁定**：可写主体 = **财务/风控岗 或 系统管理员**（**含 `SYS_ADMIN`**；依据 `FR-PRODUCT-11` actor 原文 + 对称先例 `FR-FUND-13` + `INDEX.md §4` 系统管理员职责含「风险阈值」+ `R-AUTH-10` 排除的是「风控**判定**」而非「配置」）——**该范围随 `CH-012` 一并待最终确认**。 **📌（2026-09-21 用户确认 · 只加不改）** `CH-012` **已确认**（主体 = **财务/风控岗 或 系统管理员（含 `SYS_ADMIN`）**；失败码 **`10003`**；**M1 角色近似 → M2 功能权限判据**）⇒ **本项依赖已满足**。⚠️ **但仍不可开工**：硬前置 = ① **功能权限链路无实现**；② **`t_role`/`t_user_role` 零播种**，且**播种须与实现同批**（不同批 ⇒ 修完即**全拒 = 误拒**）。⇒ 编排者裁定：**先落「播种 + 功能权限链路」再落本强制点**（建议由 `T-108`〔AUTH DAO〕/`T-009`/`T-010` 那一族承接前置）。

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

### T-121 落地「`ARCHITECTURE.md` §4.2.8 ↔ `proto/hgcp.proto` 一致性」CI 判据脚本

- **需求追溯**：`ARCHITECTURE.md` §4.2.8（`CH-013`；字段级 schema = 两侧同源单点）、§3.13（判据脚本产出清单）、`NFR-MAINT-4/6`、`NFR-COMPAT-5`
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：无（`proto/hgcp.proto` 已落地）
- **估时**：**4 人时**（与 `T-090`~`T-093` 各 4 人时同族；含脚本本体 + 自测夹具 + 接入聚合入口 `tools/arch-check/arch-check.sh`）
- **来源**：新增（**来源 = `te-verify-proto` 独立复核的观察 `O-2`**：把 `MsgError.rule_id` 字段号 `4→7`（**合法但不符 `§4.2.8`**）⇒ **14 个用例全绿** ⇒ **测试套件完全不锚定字段号**；而 proto3 字段号**一经发布不可回收** ⇒ 当前**唯一**保证字段号正确的机制是"人工机械化差集"，**非持续性**。编排者 2026-09-22 裁定立项）
- **状态**：**DONE（授权 + 独立验证）**〔第 41 轮〕（独立复核 `te-verify-t121` = **通过、0 阻断缺陷**；**编排者 2026-09-22 授权**。**验收证据**：`E7` 六类判据**逐类自设反例均判红（无空转）**、**未解析行 6 形态全退 `2`（不静默）**、`ComponentRole` 白名单**双向**证「只免一例、不扩散」、锚定口径经**行号位移（前插 5 行）证伪**仍 `rc 0`、聚合入口失败传播 M0–M3 齐备、`E1`–`E6` 脚本**相对 HEAD 逐字节未动**、`cpp/CMakeLists.txt`/`java/pom.xml` 接线两侧**零改动**、共享树**零变异残留**；`E7` 与聚合在 live 与 HEAD 两态**行为逐字节等价**。**四条建议级观察（0 阻断）**：`O-1`（自报锚 = 提交态 ≠ live）⇒ **编排者本批提交承接**；`O-2`（两处接线注释仍写「六判据」）⇒ **`T-123` 承接**；`O-3`（`README.md:273` 的 `E4 = 6 个` 已失真为 `20 个`）与 `O-4`（脚本头 `N5` 把 `option` 写成「不判」、实测退 `2`）⇒ **`tl-t121` 补修承接**。**证据章**：`docs/TEST_RESULT.md` 章 `# \`T-121\` 独立复核证据（2026-09-22）`（由 `te-verify-t121` 补录、**纯追加**））
  **📌（2026-09-22 交付登记 · 只加不改）** 交付形态 = **新增第七条判据 `E7`**（`ARCHITECTURE.md` §3.0.2 已登记为「**七大**工程化手段」、`§3.13` 判据脚本产出清单同步）；脚本 **单文件 Python3 / 无第三方依赖**，锚 **`^##### 4.2.8.x`** 求表段（**禁固定行号** —— `ARCHITECTURE.md` 为活跃制品）。**六类判据**：消息集（40 + 10）/ 字段三元组（名 ↔ 号 ↔ 类型）/ 枚举逐值 / 字段号连续性（`1..n` 无跳号重号）/ 自洽计数（`214 = 38 + 77 + 99`）/ 必填性。**退出码语义** = 与既有六脚本统一（`0` 通过/SKIP、`1` 命中、**显式路径不存在 ⇒ `2`**）；「**未解析行 ⇒ `2`**」经裁定**维持**（判为「工具需升级」，**不静默吞**未识别行）。**已知盲区（显式披露、不计缺陷）**：**只锚 `^##### 4.2.8.x`、不扫附录 C** —— 附录 C `C.3.1`–`C.3.5` 系 §4.2.8 的**近重复副本**，故「§4.2.8 已改、附录 C 未同步」这类不一致**不会被该判据拦下**；架构席已在附录 C `C.3` 头注**就地追加指针**披露（**只加不改**，原「一律以 §4.2.8 为准」句完整保留）。**落点（以实时 `md5sum` 为准）**：`tools/arch-check/scan_hgcp_schema.py`、`tools/arch-check/arch-check.sh`（按扩展名分派 `.sh` / `.py`）、`tools/arch-check/README.md`（`E7` 章节）、`docs/ARCHITECTURE.md`（§3.0.2 `E7` 行 + §3.13 + §8.3 `NFR-MAINT-4` 六→七 + §13 变更记录行）。**聚合复跑 = `rc 0`（七条判据全过）**。
- **验收标准**：
  1. **脚本** `tools/arch-check/` 下新增（命名与既有 `E*` 同族），**输入** = `docs/ARCHITECTURE.md` 的 **§4.2.8 各表** + `proto/hgcp.proto`；**输出** = 差集清单 + **退出码语义与既有六脚本统一**（`0` 通过 / `1` 命中 / **显式路径不存在 ⇒ `2`** / 默认路径不存在 ⇒ `0` SKIP）。
  2. **必须机械核（逐项、不得目测抽样）**：① **消息集**（40 顶层 + 10 复合，含**多/少/改名**）；② **字段三元组**（名 ↔ **号** ↔ 类型）；③ **枚举逐值**（含 `MsgType` = `0` 保留 + **40 个冻结类型码逐值**、`ComponentState` = `I-4` 5 值）；④ **字段号连续性**（`1..n` 连续、**无跳号/重号**）；⑤ **顶层字段号总数 = 214 且分项 38/77/99 自洽**；⑥ **必填性标注**（`G-1` + `§4.2.8.5` 条件必填清单）。
  3. **必须覆盖 `O-2` 场景**：把任一字段号改为"合法但不符 `§4.2.8`"的值 ⇒ **脚本必须判红**（这是本任务的**立项理由**，须有自证）。
  4. **显式披露不可机械化的项**（如"值名不落线路"类语义、`ComponentRole` 值名例外），**不得以"看起来一致"含糊带过**。
  5. **接入**：纳入聚合入口 `tools/arch-check/arch-check.sh`（**任一脚本非 0 ⇒ 整体非 0**），并核其**在 `ctest` / `mvn verify` 前置中的实扫**（**不得像 `E4` 曾出现的"恒 `SKIP` = 判据未生效"**）。
  6. `java/**`、`cpp/**`、`proto/**` **零改动**（本任务只**新增判据**）；`bash tools/arch-check/arch-check.sh` 在现行制品上**聚合退 `0`**。
- **产出**：`tools/arch-check/` 新判据脚本 + 自测夹具 + `README.md` 相应章节 + 聚合入口接线
- **备注**：**勿与 `T-090`~`T-093`（`E1`/`E3`/`E4`/`E6`）与 `T-095`（六脚本一致性）混淆** —— 本任务**新增第七条**（`§4.2.8` ↔ `.proto` 一致性），其必要性来自 `CH-013` 把字段号定为**契约级且不可回收**。**可与 `T-042`（Java 侧同源消费）互为约束**：脚本判红的任一侧即触发两侧返工。 **📌（2026-09-22 独立复核席 `te-verify-proto2` 的实施指导 · 已采纳）**：① **落点 = `tools/arch-check/` 新增判据 `E7`**（如 `scan_hgcp_schema.py`），沿用既有退出码语义（`0` 通过/SKIP、`1` 命中、**显式路径不存在 ⇒ `2`**）；接入 `arch-check.sh` 聚合入口后，**`cpp/CMakeLists.txt` 的 `arch_check` ctest fixture 与 `java/pom.xml` 的 `mvn validate` exec 前置会自动传播 ⇒ 两侧零改动**。② **判据应锚（全量机械化、禁止目测抽样）**：消息集（40 + 10，**含顺序**）/ **字段三元组**（名↔号↔类型↔`repeated`，**逐字段**）/ **必填性**（`必/可/条件` vs proto 尾注释）/ **枚举**（集合 + **逐值**，含前缀剥离；`MsgType` 取自 §4.2.3）/ **自洽计数**（`214 = 38+77+99`、复合 `66`、`1..n` **连续**）/ 命名派生。③ **必须显式列「不覆盖」**：`§4.2.8.5` 条件必填的**条件语义**（只能判"清单行存在"）、散文注记（**只锚表体、不锚正文**）、以及「40 / 62 / `VER` / `FR`」等**不变量 —— 建议（`te-verify-proto2` 意见）另立一条判据，与 §4.2.8 一致性**分开**，避免耦合**。④ **必须避开的 4 个陷阱**（源自对前序临时脚本的评估）：**对"未解析行"必须 fail**（前序 `__UNPARSED__` **静默吞掉未识别行** ⇒ 新语法会**静默漏判**）；**不得硬编码绝对路径**；**顶层/复合须按 `^##### 4.2.8.x` 表段判定**（前序按 `messages[:40]` **切片位置**区分、较脆）；`ComponentRole` 这类**不合前缀约定者须有白名单**。⑤ **约束**：**单文件 Python3、无第三方依赖**；**必须带反例自测**（注入一处字段号漂移 ⇒ 判据须退 `1`），否则判据**无法证伪**；**判据按 `^##### 4.2.8.x` 求段、禁固定行号**（`ARCHITECTURE.md` 是**活跃制品**，本会话实测其在作业期内连写）。

### T-122 落地「冻结不变量（消息 `40` / 错误码 `62` / 线格式 `VER 0x0100` / `FR 184`）」CI 判据脚本

- **需求追溯**：`ARCHITECTURE.md` §3.0.2（判据清单）、§3.13（判据脚本产出清单）、`NFR-MAINT-4/6`、`NFR-COMPAT-5`
- **负责人**：tech-lead
- **优先级**：P0
- **依赖**：无（**`FR` 项口径已裁定 ⇒ 前置解除**，见备注）
- **估时**：**4 人时**（与 `T-090`~`T-093` / `T-121` 同族；含脚本本体 + 自测夹具 + 接入聚合入口 `tools/arch-check/arch-check.sh`）
- **来源**：新增（**来源 = `te-verify-proto2` 的判据设计建议**：把「`40` / `62` / `VER` / `FR`」等**标量不变量**与 `§4.2.8` schema 一致性判据**分开另立**、**避免耦合**；经 `tl-t121` 以观察项 `U-3` 上报「不变量判据另立是否值得」⇒ **编排者 2026-09-22 受理立项**）
- **状态**：TODO（**未开工**；`FR` 项口径未定前，其余三项可先行）
- **验收标准**：
  1. **脚本** `tools/arch-check/` 下新增（命名与既有 `E*` 同族），**输入** = `docs/ARCHITECTURE.md` 相关表段 + 实现侧常量；**输出** = 逐项实测量 + 差集清单 + **退出码语义与既有七脚本统一**（`0` 通过 / `1` 命中 / **显式路径不存在 ⇒ `2`** / 默认路径不存在 ⇒ `0` SKIP）。
  2. **必须机械核四条不变量（逐项、不得目测抽样）**：① **消息集 `40`**（顶层；与 §4.2.3 数据行一致、`1..40` 连续）；② **错误码 `62`**（§4.3.2 **表体 **62** 行**；须处置 `I-3` 勘误项，**且须与同节 4 列「统计」表区分** —— 该节含**多张表**，取段须分段）。**⚠️ 已知陷阱（`tl-t121` 实测、勿判缺陷）**：§4.3.2 的**节标题**写「共 **50** 个」= **已勘误留痕**（历史值），**不得**据标题判红；判据口径 = **表体 62 行**。③ **线格式 `VER` = `0x0100`**（**锚实现常量** `cpp/src/hgcp/include/hg/hgcp/protocol.h:31` 的 `kWireVersion` = `0x0100u`；**须先实测该常量存在**，不得仅锚文档字面）；④ **`FR` 计数 `184`** —— **口径已裁定（编排者 2026-09-22）**：「**全仓（`docs/REQUIREMENTS/*.md`）唯一 `FR-<业务模块>-<n>` 计数**」。**实测**：唯一 ID 数 = **184**、**标题定义数亦 = 184**（两侧差集**均为 0**）⇒ **「定义 / 引用」两种形态指向同一 184 个 ID ⇒ 无需区分二者**。**⚠️ 必须避开的测量陷阱（否则会得 `234`）**：`NFR-<族>-<n>` 的**字面上含 `FR-` 子串**（`NFR-PERF-1` 里就有 `FR-PERF-1`）⇒ 朴素 `FR-[A-Z]+-\d+` 正则会**把 50 条非功能项一起数进来**（184 + 50 = 234）；**必须用前缀边界**（`(?<![A-Za-z])FR-`）或等价手段。**旁证**：11 个业务模块的定义数 `14/19/20/18/17/17/19/13/14/18/15` 逐模块 `1..n` **连续、无重号跳号**，与 §1 冻结值 `184` 自洽。
  3. **必须自证可证伪**：注入一处漂移（如把 `kWireVersion` 改 `0x0101`、或把 §4.3.2 表体删一行）⇒ **脚本必须判红**（`rc 1`），并**复位证明**。
  4. **显式披露不可机械化的项**（如「§4.3.2 统计表 vs 表体」双表区分、`FR` 口径未定部分的**不覆盖声明**），**不得以"看起来一致"含糊带过**。
  5. **接入**：纳入聚合入口 `tools/arch-check/arch-check.sh`（**任一脚本非 0 ⇒ 整体非 0**），并核其**在 `ctest` / `mvn verify` 前置中的实扫**（**不得像 `E4` 曾出现的"恒 `SKIP` = 判据未生效"**）。
  6. `java/**`、`cpp/**`、`proto/**` **零改动**（本任务只**新增判据**）；`bash tools/arch-check/arch-check.sh` 在现行制品上**聚合退 `0`**。
- **产出**：`tools/arch-check/` 新判据脚本 + 自测夹具 + `README.md` 相应章节 + 聚合入口接线
- **备注**：**勿与 `T-121`（`E7`：`§4.2.8` ↔ `proto/hgcp.proto` **schema 一致性**）混淆** —— 本任务核的是**四个标量不变量**（`40` / `62` / `0x0100` / `184`），与字段级 schema 一致性**正交**；两者共用聚合入口但**判据互不覆盖**。**`FR` 项口径已由编排者 2026-09-22 实测裁定**：口径 = 「全仓（`docs/REQUIREMENTS/*.md`）唯一 `FR-<业务模块>-<n>` 计数」= **184**（**定义集合与出现集合差集均为 0** ⇒ 无「定义 vs 引用」歧义）；**原判「硬前置、结构上不可判定」已被实测推翻** —— 该结论源自 `tl-t121` `U-3` 把 `FR-` 正则**误匹配 `NFR-` 内含子串**而得 `234`（`NFR-PERF-1` ⊃ `FR-PERF-1`），系**测量假象**、非口径缺口。**判据实现要求**：正则须带**前缀边界**；并请**独立复算** `184`（并给命令），**不得直接引用本备注的数值**。**与 `T-121` 的判据形态差异**：`T-121` 锚**表段内容**（可证伪性强），本任务 ③④ 锚**跨制品（文档 ↔ 实现常量 / 文档 ↔ 需求库）等值**，其「等值成立」须给**两侧实测值 + 命令**，**不得只写"一致"**。

### T-123 订正判据计数注释（`cpp/CMakeLists.txt` / `java/pom.xml` 仍写「聚合 `E1`–`E6` 六判据脚本」）

- **需求追溯**：`ARCHITECTURE.md` §3.0.2（现为「**七大**工程化手段」）、§3.13（判据脚本产出清单）
- **负责人**：backend-engineer#cpp（`cpp/CMakeLists.txt`）+ backend-engineer#java（`java/pom.xml`）—— **跨写域**（两个文件分属两席，须各自改自己那一处）
- **优先级**：P2
- **依赖**：无
- **估时**：**1 人时**（两处各一行注释；**合计记 1 人时**，不按席拆分）
- **来源**：新增（**来源 = `T-121` 独立复核 `te-verify-t121` 的观察 `O-2`**：`cpp/CMakeLists.txt:70` 与 `java/pom.xml:129` 的接线注释仍写「聚合 **`E1`–`E6`** 六判据脚本」，而聚合入口现为 **`E1`–`E7`**。**接线本身正确**（两处均只调聚合入口 `arch-check.sh`、不枚举脚本）⇒ **不判 `T-121` 缺陷**；但依本项目「**已知隐患须落任务表**」纪律立号承接，防「后来者重复发现」）
- **状态**：TODO
- **验收标准**：
  1. `cpp/CMakeLists.txt` 中该接线注释的判据计数订正为 **`E1`–`E7` / 七个判据脚本**（**仅改注释**，`add_test` / `FIXTURES_SETUP` 等**接线语句逐字节不动**）。
  2. `java/pom.xml` 中该接线注释同样订正（**仅改注释**，`exec-maven-plugin` 的 `<argument>` 等**逐字节不动**）。
  3. **零行为变更证明**：`bash tools/arch-check/arch-check.sh` 复跑 **`rc 0`**（`通过(0)=7`）；`git diff` 中**非注释变更行数 = 0**。
  4. 两处**各自**由对应席位改（`#cpp` 改 CMake、`#java` 改 `pom.xml`），**不得由单席跨域改两文件**。
- **产出**：两处注释订正（无新增文件）
- **备注**：**勿与本轮 `T-122` 混淆** —— `T-122` 是**新增第八条判据脚本**（`40`/`62`/`VER`/`FR` 不变量），本任务**只是让既有注释追上现有判据条数**。**低优先、可附随**：任何后续动到这两个文件的工单可顺带完成，但**须显式登记**，勿因「顺手」而致归属真空。**不阻断任何门禁**（无 FR/架构违背、无功能影响）。

### T-124 `risk_state` 诊断访问器的并发读安全化（`last_result()` / `stats()` 与发布路径竞争）

- **需求追溯**：`ARCHITECTURE.md` §4.4.3（并发策略）、§8.1.3（零阻塞口径）、`NFR-PERF-1`
- **负责人**：backend-engineer#cpp
- **优先级**：P2
- **依赖**：`T-034`
- **估时**：**2 人时**
- **来源**：新增（**来源 = `T-034` 独立复核 `te-verify-t034` 的观察 `O-1` / `O-3`**：`last_result()` 的跨线程读竞争经 **TSAN 证实**（`risk_state.h:63` vs `risk_state.cc:36`）；`stats()` 读 `retired_.size()` 与 `publish()` 的 `push_back` 竞争 —— **后一项产出方未披露**。两项均属**非热路径诊断访问器**、**无 FR 违背** ⇒ 判**建议级**，但依本项目「**已知隐患须落任务表**」纪律立号承接，防「后来者重复发现」）
- **状态**：**DONE（授权 + 独立验证）**〔第 66 轮〕（实例 `be-t124` 交付；独立复核 `te-verify-t124` = **通过 / 0 阻断缺陷**；**编排者 2026-09-22 授权**）
- **验收标准**：
  1. **二选一并取证**：(a) 把两处诊断读改为**无锁发布**（如原子尺寸/原子版本快照）；或 (b) **显式确立调用约束**（写明「仅允许写者静止时调用」）并在**实现处**（非仅 javadoc/注释）以可核手段体现。
  2. **须给出判据**：若选 (a) ⇒ 以 **TSAN 干净**为判据（给命令与输出）；若选 (b) ⇒ 以**调用点审计**为判据（列全部调用点 + 证明均在写者静止期）。
  3. **热路径零影响**：`E4` 复跑 `rc 0`（`--root cpp/src/risk_state` 与默认并集根两口径均报 `N` 与命中数）；`ctest` 不回归。
  4. **交付面**：`cpp/src/risk_state/**`（必要时含 `ARCHITECTURE.md` §4.4.3 的约束登记 —— **须先请示**，`docs/**` 不属本席位默认写权）。
- **产出**：`cpp/src/risk_state/**` 修改（+ 视选择可能含架构 §4.4.3 约束登记）
- **备注**：**`O-2`（未注册的 `snapshot()`(peek) 跨发布边界 ⇒ UAF）不纳入本任务** —— 它**已文档化**（§4.4.3）且**安全替代 `acquire()` 已暴露**，属**设计取舍**、非缺口；若后续判定须改，另立号。**`O-5`（`E4` 的 `N` 两口径 16/20）不另立** —— 属使用说明，README 已改为「不写死、以实时为准」。**勿把本任务与 `T-035`（`risk_engine`）混淆**：`T-035` 消费的是 `snapshot()`/`rebuild()`，**不含**这两个诊断访问器。
  **📌（2026-09-22 交付登记 · 已完成，待独立复核 · 只加不改）** 实例 `be-t124`（**唯一写者 `cpp/src/risk_state/**`**；**不自评 `DONE`**）。**方案 = (a) 无锁发布**（本席倾向项；理由：真正消除竞争且可判〔TSAN 干净〕，优于把风险转成注释约定；两处均为**非热路径**诊断访问器、改造成本低）。**改动清单（5 改 1 增）**：① `include/hg/risk_state/snapshot_manager.h` — `:124-126` `retired_pending()` 改读原子 `retired_count_`、`:141-144` 新增 `std::atomic<std::size_t> retired_count_`；② `src/snapshot_manager.cc` — `:10` 构造初始化、`:23` 析构归零、`:48` `push_back` 后 / `:70` `swap` 后**同步计数**、`:79` `stats()` 改读原子计数；③ `include/hg/risk_state/risk_state.h` — 文件头 15–29 改为「无锁发布」、`:75` `last_result()` 改声明（定义移 `.cc`）、`:81-93` 新增 `DiagnosticSlot`（`seq_` + 4 原子字段）+ `publishLastResult()` + 成员 `last_`；④ `src/risk_state.cc` — `:36/:58/:69` 三处 `last_ = r;` → `publishLastResult(r)`、`:73-99` 新增「写者前后各 `fetch_add(1, release)`（偶数=稳定 / 奇数=写入中）＋读者前后 `seq_` 一致才采信」；⑤ `CMakeLists.txt:39` 注册新测试；⑥ 新增 `tests/diagnostic_accessor_test.cc`（116 行，**3 读者线程并发读 + 写者交错**）。**机制**：`last_result()` 全字段原子 + **版本戳快照**；`stats().retired_pending` 与 `retired_` **并行维护的原子计数**（读者不再触碰 `retired_`）⇒ **零锁 / 零新增分配 / 零同步 I/O**（`E4` 关切面）。**★判据 1（TSAN 干净）**：`-fsanitize=thread -g -O0` 构建后 `TSAN_OPTIONS='halt_on_error=0'` 运行 ⇒ `2003 checks, 0 failures -> PASS`、**WARNING = 0**、`rc=0`。**★反退化（隔离副本，三态各自承重）**：4 实现文件**全回退** ⇒ **3 告警**（`snapshot_manager.cc:44`↔`:74`〔`O-3`〕+ `risk_state.cc:69`↔`risk_state.h:63`〔`O-1`〕）；**仅回退 `last_result`** ⇒ **1**（`O-1`）；**仅回退 `stats`** ⇒ **2**（`O-3`）⇒ 与 `te-verify-t034` 的 `O-1`/`O-3` **逐位吻合**。**判据 2**：配置/构建 `rc 0`、`warning:0 / error:0`、`ctest` **33/33**（含新增用例）、risk_state 五单测合计 **2554 checks / 0 fail**。**判据 3（`E4` 两口径，共享树实测）**：`--root cpp/src/risk_state` ⇒ **`N=17`、命中 0、WARN 3、`rc 0`**；**默认并集根** ⇒ **`N=47`、命中 0、WARN 3、`rc 0`**（WARN 3 仍全为既有 `new`，**无新增**）。**冻结点锚**：`d89387cb27c9c81f9218dbdf85587e5f` / **18 文件**（原 `2a6eea7a…`/17，+1 = 新增测试）。**⚠️ 锚点离群（实施方如实报告，本席裁定为测量假象、待复检方确认）**：清理命令中**一次性取样**得 `82b2e677…`，其**前后共 7 次取样均 `d89387cb…`**；`mtime==ctime` 无改写、`risk_state` 无晚于 `20:35:34` 的文件、同期仅 `docs/*` 被改（本席）；该值**不对应任何已知部分态**（⚠️ **本括注经独立复核方 `te-verify-t124` 指出口径问题、已订正**：产出方原给的两个旁证锚 `"4 实现回退+我的测试" = da741de5…` 与 `"原始 17 文件" = 8883093c…` **在仓库根标准口径下不可复现** —— 复核方实测同义状态为 `8f8cf018…` 与 `2a6eea7a…`（后者即文档别处所用改前锚）⇒ 原两值**属非标准口径取样**，**不得再作为「不对应任何已知部分态」的取证依据**；**冻结点 `d89387cb…`/18 不受影响**。复核方以 **64 种组合**（16 种回退子集 × `CMakeLists` × 测试）× cwd 变体 × 3 种瞬时备份态**全部 0 命中** `82b2e677…`，且 `mtime==ctime`、末次改动 `20:35:34`、采样期无写者进程 ⇒ 判定为**不可复现的一次性测量假象、非并发写入**；**其成因明确记为「不可判定」（复核方不臆测）**）⇒ 判**一次性瞬时异物/测量假象**，**非可复现并发写入**（本席确证：**无其它实例在写 `risk_state`**）。**4 项未决本席裁定**：① **不在 `ARCHITECTURE.md` §4.4.3 登记** —— 准（选 (a) 属实现内部改动、**未新增对外调用约束**；`docs/**` 亦非该席写权）；② **证据落痕** ⇒ `docs/**` 非实施席写权 ⇒ **由独立复检方按既有体例追加**（`docs/TEST_RESULT.md`）；③ 锚点离群 ⇒ 见上，**复检方须独立确认冻结点**；④ `std::atomic` 的 lock-free **未加 `ATOMIC_LLONG_LOCK_FREE` 静态断言** ⇒ 准（避免平台耦合；本平台 x86-64 下即为 lock-free，`E4` 已兜住「无锁」关切）。**待派独立复核 `te-verify-t124`**。
  **📌（2026-09-22 独立复核 = `通过` / 0 阻断缺陷 · 4 项信息级观察 · 转 `DONE` · 只加不改）** 复核者 `te-verify-t124`（**自撰探针 `/root/t124v/probe/t124_probe.cc`，未复用产出方用例**；变异/回退全在隔离副本；共享树残留 0）。**① 改前确坏**：改前锚 `2a6eea7a…`/17 下 **TSAN `WARNING=3`（4 跑恒定）** —— `snapshot_manager.cc:44`(push_back)×2 ↔ `:74`(`stats`/`retired_.size()`) = **`O-3`**、`risk_state.cc:69`(`last_=r`) ↔ `risk_state.h:63`(`last_result`) = **`O-1`** ⇒ **方位与 `te-verify-t034` 逐位吻合 ⇒ 竞争为真、非探针不承重**。**② 改后确好**：冻结点下同探针 **6 跑全 `WARNING=0`、`rc 0`**。**③★反退化三态各自承重**（逐步回退 + 全量重编译，各 3 跑稳定）：仅回退 `last_result` ⇒ **1**；仅回退 `stats` ⇒ **2**；4 实现文件全回退 ⇒ **3** ⇒ **无「仍绿 ⇒ 空转」**。**④★★附加自设变异（本单最有方法论价值的一处）**：**去掉 `last_result()` 的前后 `seq_` 一致性复检**（字段仍全原子）⇒ **TSAN 仍 0 告警，但 `torn` = 24 / 44 / 6 > 0** ⇒ 同时证明 **(a) 自洽性断言确在承重**、**(b)「TSAN 干净」单独不足以验证 seqlock** —— 本交付**两者兼备**。**⑤ 锚点**：**12/12 次取样（5s 间隔、跨 58s）= `d89387cb27c9c81f9218dbdf85587e5f`/18** ⇒ 冻结点成立；**离群值 `82b2e677…` 判为不可复现的一次性测量假象、非并发写入**（复核方以 **64 种组合**〔16 种回退子集 × `CMakeLists` × 测试〕× cwd 变体 × 3 种瞬时备份态 **全部 0 命中**；`mtime==ctime`、末次改动 `20:35:34`、采样期无写者进程）；**成因明确记为「不可判定」（不臆测）**。**⑥ `E4` 两口径**：`--root cpp/src/risk_state` ⇒ 17 文件 / 命中 0 / `WARN 3` / `rc 0`；默认并集根 ⇒ 47 / 0 / 3 / `rc 0`；**改前同根亦 `WARN 3` ⇒ 无新增**；聚合 `arch-check` **`rc 0`**（`E1`–`E7` 全 0）⇒ **修复未引入锁 / 互斥量 / 新增分配 / 同步 I/O**。**⑦ `ctest`**：隔离全量构建（`-j1`、`rc 0`、**0 warning / 0 error**）⇒ **33/33 全过**（`ctest -N` = 33）；risk_state 5 单测独立复跑 `70/418/39/24/2003` = **2554 checks / 0 fail**。**⑧ 断言未削弱**：`git diff 0b83114 HEAD -- cpp/src/risk_state` 中**既有测试零改动**（`tests/` 仅新增 `diagnostic_accessor_test.cc`）；新增测试**确为并发**（`:71` 3 读者线程先于写者循环 `:94`–`:102` 启动）。**4 项观察处置**：**`OBS-T124-1`（低·取证口径）⇒ 已订正**（产出方原给两个旁证锚在仓库根标准口径下**不可复现** ⇒ 见上文 §1 备注中已就地标注「非标准口径取样」并给出可复现值 `8f8cf018…`/`2a6eea7a…`，**该括注不得再作取证依据**）；**`OBS-T124-2`（信息）** = 离群值（如上）；**`OBS-T124-3`（信息）** = seqlock 未在二次 `seq_` 载入后加 `acquire` 栅栏，**x86-64(TSO) 下无害**、弱序 ISA 备参；**`OBS-T124-4`（信息）** = 新测试的**竞争检出面只在 TSAN 下成立**（普通 `ctest` 改前亦绿），但对 **seqlock 逻辑**承重、且产出方已在用例头 `:8`–`:10` 披露。**未决**：无阻断项；产出方**未在 `ARCHITECTURE.md` §4.4.3 登记**（属实现内部改动、§4.4.3 原文未描述该两访问器）⇒ **复核方认可**。**编排者 2026-09-22 授权**：`DONE` 判据〔授权 + 独立验证〕**满足**。

### T-128 统一 CTP 头名变量源（`ctp_smoke` 的 `HG_CTP_HEADER_STYLE` ↔ 主工程 `HG_CTP_TRADER_HEADER` 不同源）

- **需求追溯**：`ARCHITECTURE.md` §7.2.1（CMake 依赖与 CTP 定位）、§3.1（CTP 适配层契约）、`NFR-MAINT-3`
- **负责人**：backend-engineer#cpp
- **优先级**：P2
- **依赖**：`T-033`（已交付）、`T-027`
- **估时**：**1 人时**
- **来源**：新增（**来源 = `T-033`/`T-027` 独立复核观察 `OBS-T033b-3`**：两处头名变量**不同源** ⇒ 某些构造下**可达不同 `CTP_FOUND`**；前态复现时探路件因默认 `CLASSIC` **仍注册 `rsk02`**；**无 `FR` 冲突** ⇒ 定 **P2**，但**探针结论可能与产品构建不一致** ⇒ 立号承接）
- **状态**：**DONE（授权 + 独立验证）**〔第 81 轮〕（2026-09-22 实例 `be-t128` 交付；**独立复核 `te-verify-t128` = 有条件通过 / 0 阻断缺陷**〔4 观察〕；**编排者 2026-09-23 授权**）
- **验收标准**：
  1. **单一来源**：CTP 头名风格须由**唯一入口**决定，`ctp_smoke` 与主工程**不得各自解析**同一概念；`HG_CTP_TRADER_HEADER`/`HG_CTP_MD_HEADER` 与 `HG_CTP_HEADER_STYLE` 须同源派生。
  2. **一致性判据**：须有可执行判据证明「同一配置下主工程与探路件的 `CTP_FOUND` 一致」，且**判据本身双向含反例**（改前矛盾态须可复现、改后一致；**变异须使判据 `rc=1`**）。
  3. **防退化守卫**：旧入口若保留，须有守卫防「旧入口改了风格而新入口不知」的静默分叉。
  4. **不回归**：`ctp`/`stub`/`fetch` 三轨 + 老式单目录 + 探路件 standalone 的构建须 **0 告警**；`ctest` 与聚合 `arch-check` 不回归。
- **产出**：`cpp/cmake/HgCtpStyle.cmake`（新增）、`cpp/cmake/HgDependencies.cmake`、`cpp/ctp_smoke/CMakeLists.txt`、`cpp/ctp_smoke/ci/check_ctp_found_parity.sh`（新增）
- **备注**：**穷举结论 = 同概念仅 2 个「缓存入口」**（主工程 `HG_CTP_TRADER_HEADER`/`HG_CTP_MD_HEADER` + 探路件 `HG_CTP_HEADER_STYLE`），`HGCP_*` 系**派生输出宏**（非入口）。**唯一派生实现** = `cpp/cmake/HgCtpStyle.cmake` 的 `hg_ctp_resolve_style()`，在调用方作用域派生 `HG_CTP_{TRADER,MD}_HEADER` / `_API_CLASS` / `_SPI_CLASS` / `_PRIVATE_TOPIC_ARITY` / `_STYLE_MERGED`；主工程与探路件**共用同一实现**。**★判据形态**：`check_ctp_found_parity.sh` 以**一次 top-level 配置**抓两行 `[HG-CTP-FOUND] role=smoke\|main found=… style=… trader_header=… md_header=…` ⇒ **全字段逐字比较**；退码 `0/1/3`。**反例工况（改前分歧）**：① `CTP Mini` + `-DHG_CTP_HEADER_STYLE=MERGED`、② 官方 SDK + `-DHG_CTP_TRADER_HEADER=ThostFtdcUserApi.h` ⇒ **探路件「SDK 可得」而主工程 FATAL，同一配置下结论矛盾**；改后 ① 两侧一致。**判据非空转**：主工程侧硬编码头名（含「`found` 同而仅头名不同」这一更难抓住的构造）⇒ 变异均 `rc=1`。三轨 + 老式单目录 + 探路件 standalone 均 **0 告警**、`ctest` 38/38 · 34/34、聚合 `rc 0`。**★交付域冻结点（可复现口径）**：`find cpp/cmake cpp/ctp_smoke -type f -not -path '*/build*' | LC_ALL=C sort | xargs md5sum | md5sum` = **`654cf2f2997084d29b194e26bdb8afca` / 9 文件**（3 次取样一致）；4 文件分别 `HgCtpStyle bc2d90eb…` / `HgDependencies 1b43b61c…` / `ctp_smoke/CMakeLists.txt daf53783…` / `check_ctp_found_parity.sh 2efe5ede…`。**⚠️ 不可复现指纹（勿引用，据实留痕）**：含 `build*` 的同口径取值**不稳定**（实测 81 文件版 `8b728ec0…`）⇒ 本单锚点**一律排除 `build*`**；编排者第 80 轮曾把 `cpp/ctp_smoke`（78 文件、含 `build*`）写入检查点提交信息，**该值不可复现、以本条为准**。**⚠️ 两项存量缺陷（均非本单引入）**：① **`fetch` 轨构建失败**（`payload_codec.cc` 经 `hgcp.pb.h` 引入 protobuf 源码头 ⇒ `port.h:46/53 -Werror=unused-parameter`，`rc=2`；改前 prefix 端到端同点同错）⇒ **已立 `T-129`**；② **主工程在合并式 SDK 上无法编译** —— `cpp/src/ctp_adapter/**` **硬编码分离式头名**（`src/module.cc:7`、`src/ct_gateway_ctp.cc:22-23`、`include/hg/ctp_adapter/ct_spi_impl.h:27-28` + 2 个测试）⇒ **`MERGED` 名义上仅探路件可用**。`CH-015` 交付基线 = 官方分离式 ⇒ **不阻断**，但属「**已声明而主工程不可用**」的**潜伏陷阱** ⇒ T-128 只统一「头名变量源」、不改此事实。**⚠️ 待回写（非本席写域）**：`tools/arch-check/README.md` 宜补「CTP 单一入口见 `cpp/cmake/HgCtpStyle.cmake`」（**实测该 README 现 0 处提及 `HgCtpStyle`/`单一入口`**）。**⚠️ 连带冻结点前移**：`T-027` 的 CI 判据口径引用 `cpp/ctp_smoke/`（本单即授权改动该目录）⇒ **`T-027` 冻结点已前移**，其 delta 复检与「官方基线下三轨计数/行号须重测」一并由复核方核实。**⚠️ 未决（不阻断转待复核、请复核方一并独立核实）**：(i) 主工程对 `-DHG_CTP_HEADER_STYLE=MERGED` 应**显式拒绝**（明确失败）还是维持现状（静默不可编译）；(ii) 旧 `-DHG_CTP_TRADER_HEADER`/`-DHG_CTP_MD_HEADER` 入口的移除是否属**契约变更**（若属，须补 `CH`）；(iii) 交付报告 §七 其余登记项。详见 `docs/BOARD.md` §2「已完成，待独立复核」区 `T-128` 行 + §5 第 80 轮。
  **📌（2026-09-22 独立复核 = 有条件通过 / 0 阻断缺陷 · 4 观察 · 转 `DONE` · 只加不改）** 复核者 `te-verify-t128`（**自撰探针 / 自设变异**，不复用产出方用例；隔离副本 `/root/t128v`、**共享树只读**、`git status --porcelain` 全程空）。**独立复算全部吻合**：`cpp/src 327118d8…`/115、`cpp/cmake dda00ee5…`/3、**交付域 `654cf2f2…`/9**、4 单文件逐一同比；并**证实编排者作废的含-`build*` 锚成立**（现口径 81 文件 `/878…` 漂移）。**判据非空转**（自设变异使判据红）。**关键可复用纪律（本次新增）**：① **「旧入口防退化守卫」与「parity 判据」是两套独立机制** —— 删守卫**不会**让 parity 变红 ⇒ 判据文档若把二者混谈会给出**错误期望**；② **parity 判据有方向盲区** —— `add_subdirectory(ctp_smoke)` **先于** `include(HgDependencies.cmake)` ⇒ **探路件侧**分歧时探路件先 `FATAL`、主工程未及打印判定行 ⇒ 返 **`rc=3`（不可判定）而非 `rc=1`**（仍非零、非静默放行）；**主工程侧**分歧才返 `rc=1`；③ **退码实为 `0/1/2/3`**（头注只写 `0/1/3`，`2`＝未知参数**未文档化**）；④ **两行 `[HG-CTP-FOUND]` 确由同一次配置产生**（脚本内恰 1 次 `cmake`、grep 单一 `${LOG}`）。**E 项裁定（复核方给出、本席采纳）**：**(a)** 旧入口（`HG_CTP_TRADER_HEADER`/`_MD_HEADER`）移除**判不属契约变更、免 `CH`**（依据：ARCH §7.2.1 只冻结 `CTP_FOUND` 宏；`java/CONTRACTS.md` 与 CMake 变量无关；全仓零 `-D` 调用点）；**(b)** 存量缺陷 ②（主工程合并不了）**实测属实**：`-DHG_CTP_HEADER_STYLE=MERGED` + 合并版 SDK ⇒ **配置静默通过**（两侧 `found=ON`）、构建 **`rc=2`**（`ctp_adapter/src/module.cc:7` 缺分离式头），**`#include` 仅由 `#if HG_CTP_FOUND` 守卫、不受风格影响** ⇒ 判「**已声明限制 + 诊断质量缺口**」（`HgCtpStyle.cmake:32-38` 已自披露）、**非阻断**；处置建议 = 在**主工程消费点** `HgDependencies.cmake` 对 `MERGED` **显式 `FATAL` 并点名风格**（已并入 `T-130`）。⚠️ 另核：`docs/DECISIONS/` **无 `CH-015` 文件**（该 CH 的权威留痕＝`REQUIREMENTS/INDEX.md:669` + `PROJECT.md:654` + ARCH）。**`T-027` 连带结论（订正本单 §2 原表述）**：判据口径/计数**未变**（`run_probe.sh` diff 空；前/后 **`PASS=12 FAIL=0`** 逐条同）而**制品 md5 已变**（`ctp_smoke/CMakeLists.txt f78a7516… → daf53783…`）⇒「前移」**对制品成立、对判据不成立** ⇒ 处置 = **重登记冻结点 md5（非重跑）**。**4 观察去向**：`OBS-T128-1`（中：判据未接线 + 外围件三缺）+ `-2`（退码语义/盲区）+ `-4`（`MERGED` 未点名风格）⇒ **并入 `T-130`**；`OBS-T128-3`（台账「三轨 0 告警」对 `fetch` 不精确）⇒ **本轮已就地订正**（该轨 4 error + 2 warning **全为第三方 protobuf `port.h:46/53`**，即 `T-129`）。**证据章**：`docs/TEST_RESULT.md` `# \`T-128\` 独立复核证据`（`:4934` 起）+ `—— 补充（同一任务 · 6 项）`（`:5128` 起）。

### T-129 修复 `fetch` 轨构建失败（`hgcp` 经 `hgcp.pb.h` 引入 protobuf **源码头**）

- **需求追溯**：`NFR-COMPAT-1`、`ARCHITECTURE.md` §7.2.1（依赖清单）
- **负责人**：backend-engineer#cpp
- **优先级**：P1
- **依赖**：无（与 `T-026` 相关；`fetch` 轨本身）
- **估时**：**2 人时**
- **来源**：新增（**`T-128` 交付时发现的存量缺陷**：`cpp/src/hgcp/src/payload_codec.cc` 经 `hgcp.pb.h` 引入 **protobuf 源码头** ⇒ `port.h:46/53 -Werror=unused-parameter`；`CTP_USE_FETCH_DEPS=ON` 时构建 **`rc=2`**；**改前 prefix 端到端同点同错** ⇒ **与本单无关** ⇒ 立号承接，防「后来者重复发现」）
- **状态**：**DONE（授权 + 独立验证）**〔第 84 轮〕（2026-09-23 实例 `be-t129` 交付；**独立复核 `te-verify-t129` = 有条件通过 / 0 阻断缺陷**（4 观察）；**编排者 2026-09-23 授权**）
- **验收标准**：
  1. **`fetch` 轨构建成功**：`CTP_USE_FETCH_DEPS=ON` 下配置 + 构建 **`rc=0`**、**0 告警**（给出命令与输出）。
  2. **根因处置正确**：须判明是「protobuf 源码头不应被 `-Werror` 覆盖」还是「本工程不应经 `pb.h` 直引源码头」—— **择优修正，不得以放宽 `-Werror` 掩盖**（若确需豁免第三方源码头，须显式登记，并说明为何与「SDK 头须走 `-isystem`」的既有处置同族）。
  3. **不回归**：`ctp`/`stub`/`fetch` 三轨 `ctest` 不回归；聚合 `arch-check` `rc 0`；`E4` 扫描结果不恶化。
- **产出**：`cpp/src/hgcp/**` 与/或 `cpp/cmake/**`（视根因裁定）
- **备注**：**发现时点证据**：`T-128` 交付方在 `fetch` 轨观测到 `rc=2`，并以**改前 prefix 端到端复现同点同错**、确认**非本单引入** ⇒ 存量缺陷。**与 `T-026` 的关系**：`T-026` 是 protobuf 三链路**探路**（已通过、技术风险已排除），本项是 **`fetch` 轨在真实 `hgcp` 源码上的构建失败**，属**承载实现**层面。
  **📌（2026-09-23 交付登记 · 已完成，待独立复核 · 只加不改）** 实例 `be-t129`（**唯一写者 `cpp/src/hgcp/**` + `cpp/cmake/**`**；**不自评 `DONE`**）。**唯一改动 = `cpp/cmake/HgDependencies.cmake`（+30/−0，纯追加 1 段）** ⇒ `c1f5aa68…`（原 `1b43b61c…`，与 `T-128` §2 记录逐字节一致；revert 后精确复原）。**`cpp/src/**` 零改动**。**改前复现**：`cmake --build cpp/build-fetch --target hg_hgcp` ⇒ `rc=2`、`errors=2`，落点 `…/protobuf-src/…/port.h:46:41` 与 `:53:46` `error: unused parameter 'size' [-Werror=unused-parameter]`（包含链 `payload_codec.cc ← payload_codec.h ← hgcp.pb.h ← io/coded_stream.h ← port.h`）。**根因判定（二选一 → 选「第三方源码头不应被本工程 `-Werror` 覆盖」）**：用 protobuf 就**必然**经 `hgcp.pb.h` 传递包含 `google/protobuf/**`、**无从回避** ⇒ 「本工程不该直引源码头」不成立；真根因 = `HgDependencies.cmake:198-211` 的既有豁免（`get/set_directory_properties(COMPILE_OPTIONS)`）**只豁免 protobuf 自身编译**、**不覆盖「消费方包含其头」**，而 `hg_hgcp`（`src/hgcp/CMakeLists.txt:84`）link 的 `hg_deps::protobuf` → **非 IMPORTED** 的 `libprotobuf`，其 `INTERFACE_INCLUDE_DIRECTORIES` 以 **`-I …/protobuf-src/src`（非 SYSTEM）** 传播 ⇒ 本工程 `-Werror` 施加到第三方头。**处置 = 标 SYSTEM**（`CMAKE_VERSION ≥ 3.25` 走 `set_target_properties(libprotobuf PROPERTIES SYSTEM TRUE)`；`< 3.25` 走 `target_include_directories(hg_deps_protobuf SYSTEM INTERFACE …)` fallback），**与 CTP SDK 头的既有处置同族**（`ctp_adapter/CMakeLists.txt:45`、`ctp_smoke/CMakeLists.txt:193` 亦走 SYSTEM）；**未放宽任何 `-Werror`**。**改后证据**：`fetch` 轨配置 `rc=0`、构建 **`rc=0 / 0 error / 0 warning`**（`flags.make` 由 `-I` 变 **`-isystem`**）；三轨 `ctest`（以 `ctest -N` 为准）= **`ctp 38/38` · `stub 34/34` · `fetch 38/38`**，均 `rc 0`；聚合 `arch-check` **`rc 0`**（`E1`~`E7` 全 0）；`E4` **47 文件 / 命中 0 / WARN 3（`new`，与基线同）** ⇒ **未恶化**。**★反退化（修复承重、非「本来就绿」）**：真 TU（同一 `payload_codec.cc` 只换 include 模式）`-I` ⇒ **`rc=1`**、`-isystem` ⇒ **`rc=0`**；整树 revert `HgDependencies.cmake` + reconfigure ⇒ **`rc=2`（同点同错）**、restore ⇒ **`rc=0 / 0 告警`**；`< 3.25` fallback 的**机制**以隔离探针在 4.2.3 上验证（同目录亦在非 SYSTEM 列表时 SYSTEM 胜出 ⇒ `-isystem`）。**冻结点（连续 3 次取样一致，口径排除 `build*`）**：`cpp/src` = `327118d8…`/**115**（**未变**）、`cpp/cmake` = `bfe01dd4…`/**3**、**交付域**（`cpp/cmake`+`cpp/ctp_smoke` 排除 `build*`）= **`82bd2245…`/9**（原 `654cf2f2…`/9，仅 `HgDependencies.cmake` 变）。**⚠️ 三条未决/限制（如实登记，请复核方一并独立核实）**：**(1)** **`cpp/build-{ctp,stub,fetch}` 已按 `CH-015` 交付基线重配**（原 `Mini SDK + HG_CTP_HEADER_STYLE=MERGED` ⇒ **官方分离式 `sdk/v6.7.13_20260225_api` + `CLASSIC`**）—— 原配置下 `ctp_adapter/src/module.cc:7` **无法编译**（`build-ctp` `rc=2`），此系 **`T-128` §2 ② 的存量缺陷、非本单引入**；重配依据 = `CH-015` 交付基线 + `tools/arch-check/README.md:310-312` 自要求「三轨计数须在官方基线上重测」；**连带**：`tools/arch-check/README.md:299` 的 `CTP_SDK_ROOT=/home/ctpmini/…` 已成**陈旧值** ⇒ **待回写，已并入 `T-130`**（`tools/**` 非本单写域）。**(2)** **同类扫描（产品内）**：唯一非 SYSTEM 第三方头消费点 = `hg_hgcp` → protobuf（**已修**）；CTP 头已 SYSTEM；`Threads::Threads` 无头。**`proto_smoke` 探路件 fallback 轨实测 `flags` 亦为 `-I`，但不受影响** —— 因本缺陷**只在 protobuf ≥ 3.20**（`port.h` 才含 `SizedDelete`），而 fallback 用 **3.19.6**（其 `port.h` 仅 40 行、实测 `-I` 下 `rc=0`）⇒ **若将来把 fallback 版本抬到 3.21，须同法标 SYSTEM**（已作为**硬约束**登记入 `T-130` 备注）。**(3)** `< 3.25` 分支**未做真机版本测试**（本机仅 CMake 4.2.3）—— 已在代码注释与本报告**如实标注**，以机制等价探针替代。
  **📌（2026-09-23 独立复核 = 有条件通过 / 0 阻断缺陷 · 4 观察 · 转 `DONE` · 只加不改）** 复核者 `te-verify-t129`（**自撰探针 / 自设变异**；隔离副本 `/root/t129v`；**共享源树 `cpp/**`/`docs/**` 只读、仅追加自己的证据章**；残留 `*.orig/*.bak/*.rej` = 0、`git status --porcelain` 空）。**七档判据全过（A 双向 / B 根因+过宽 / C 反退化 / D 三轨+门禁 / E `build-*` 重配 / F 同类扫描 / G `<3.25`）**。**★事项 E/F 均未被否证（置前结论）**：**(E)** 原 `Mini+MERGED` 配置**确** `rc=2`（自造配置复现 `module.cc:7:10: fatal error: ThostFtdcTraderApi.h: No such file or directory`）；`ctest -N` 计数**重配前后均 38**（注册数不变）但**只有重配后（= `CH-015` 官方分离式）才可能有 38/38 通过**；`cpp/build*` **gitignore、0 入库** ⇒ **不越界、不改交付基线**（基线留痕 = `REQUIREMENTS/INDEX.md:669` + `PROJECT.md:654` + ARCH；`docs/DECISIONS/` 确**无** `CH-015` 文件）⇒ **产出方与本席第 83 轮的披露均属实**。**(F)** 同类扫描「不受影响」**成立**：实测 **3.21.12 的 `port.h` = 80 行含 `SizedDelete`（`:46`/`:53`）；3.19.6 = 40 行、0 命中**；机制探针 **`3.21.12 -I ⇒ rc1` / `3.19.6 -I ⇒ rc0`**；fallback **真机 build `rc0` / `ctest 3/3`** ⇒ `T-130` 的硬约束（fallback ≥3.20 须同法标 SYSTEM）**必要**。**(B) 关键裁定 —— 「处置过宽」判「建议级、非缺陷」**：根因成立且**无从回避**（**不止自家头**：protoc 生成码 `hgcp.pb.cc:8` 亦必 `#include <coded_stream.h>`，而它是 `hg_hgcp` 的源 ⇒ 收紧自家包含面**无用**）；交付用「**库目标** `SYSTEM TRUE`」，确比其**自身 fallback** 的「**供给目标** `SYSTEM INTERFACE`」更宽，**但** `hg_deps::protobuf` 的**唯一消费方 = `hg_hgcp`**（全仓 grep）⇒ 两形态**作用面相同**；**且未能构造出「掩盖本工程对 protobuf 头误用」的具体反例** ⇒ 本席**采纳**该裁定（我原提的「过宽」风险**未被证据支持**）。**(C)** **自设 2 变异全杀**：`SYSTEM TRUE → FALSE` ⇒ `-I`/`rc=2`；`set_target_properties(hg_deps_protobuf … SYSTEM TRUE)`（**移错目标**）⇒ 仍 `-I`/`rc=2`；另 **`CAND-N`**（把 fallback 的 `SYSTEM INTERFACE` 形态注入主路径）⇒ `-isystem`/`rc=0`；复位逐字节 + 删净 `hgcp/**/*.o` clean 重编 ⇒ `rc=0`。**(G)** 本机仅 CMake 4.2.3 ⇒ `<3.25` 分支**不可达**；产出方「未真机测」**披露属实**；复核方以 `CAND-N` **真机跑通该分支形态**（仅「版本分派」未验）⇒ 残留**建议级可接受**。**★4 项观察（0 阻断）与去向**：`OBS-T129-1`（**建议**：主路径 SYSTEM 作用面较其 fallback 宽，可统一为**供给目标形态**；无功能影响）⇒ **并入 `T-130` 验收第 7 条**（同文件、宜做、零风险，且符合本项目「**规则同形**」纪律）；`OBS-T129-2`（低：`<3.25` fallback 未在**真机旧版 CMake** 上跑，建议 CI 补一台）⇒ **不另立号**，待 CI 具备旧版 CMake 时并入；`OBS-T129-3`（低：`cpp/build-*`（构建产物）已按 `CH-015` 重配 ⇒ 三轨证据须在**重配后**复现 —— 复核方**已复现**）⇒ 信息级，不立号；`OBS-T129-4`（低：`proto_smoke` fallback `-I` 非 SYSTEM，当下无碍）⇒ **已由 `T-130` 承载**（其备注硬约束）。**证据章**：`docs/TEST_RESULT.md` `# \`T-129\` 独立复核证据（2026-09-23）`（`:5221` 起，`T129.0`–`T129.10`）；**纯追加经编排者独立自测**（`head -5217` md5 = `a4b04ab8…` = 提交前 HEAD 整档 md5；复核方报的写前基线同值）；新全档 = `3145e517…` / 5392 行（**以实时为准**）。**⚠️ 一条环境坑（非缺陷，已在证据章留痕）**：副本内 fallback 首跑 `rc=2` 系本机 `/tmp`（804M tmpfs）满致 gcc `.s` 写失败，`TMPDIR` 指到 `/` 重跑 `rc0` ⇒ **与产品无关**。

### T-130 `check_ctp_found_parity.sh` 接线 + 外围件补齐 + 诊断质量收口

- **需求追溯**：`ARCHITECTURE.md` §3.13（判据脚本产出清单）、§7.2.1、`NFR-MAINT-4/6`
- **负责人**：backend-engineer#cpp
- **优先级**：P1
- **依赖**：`T-128`（已交付）
- **估时**：**3 人时**
- **来源**：新增（**`T-128` 独立复核 `te-verify-t128` 的三条观察**：`OBS-T128-1`（**中**）= 判据 `cpp/ctp_smoke/ci/check_ctp_found_parity.sh` **全仓零调用点**（未注册 `ctest`、未入 `arch-check` `E1`~`E7`、未入 `run_probe.sh`、未入 `mvn`）⇒ **「可调用 ≠ 已接线」、判据永不生效**；且 `cpp/ctp_smoke/` **无任何 README** ⇒ 外围件 ①②③ **全缺**。`OBS-T128-2` = 退码头注只写 `0/1/3`、**漏 `rc=2`（未知参数）**，且**存在「该红而退 `3`」盲区**（探路件侧分歧时返 `rc=3` 而非 `rc=1`）。`OBS-T128-4` = 主工程在合并式 SDK 上构建失败时**未点名风格**。依本项目「**判据类交付须连核外围件**（①聚合入口 ②接线点 ③README/自测记录）」＋「**已知隐患须落任务表**」立号承接）
- **状态**：**DONE（授权 + 独立验证）**〔第 87 轮〕（2026-09-23 实例 `be-t130` 交付；**独立复核 `te-verify-t130` = 有条件通过 / 0 阻断缺陷**〔6 观察、缺陷单 0〕；**编排者 2026-09-23 授权**）
- **验收标准**：
  1. **接线生效**：判据须有**真实调用点**（在 `ctest` fixture / `arch-check.sh` 聚合 / `run_probe.sh` / `mvn validate` 中**至少一处**，须给**行号**与**实跑输出**）；并给「接线前该调用点不执行本判据」的对照证据 ⇒ 证明是**实扫**而非**空转**。
  2. **外围件补齐**：补 `cpp/ctp_smoke/README.md`（或等价登记），说明该判据的**用途 / 触发 / 退码语义 / 与「旧入口防退化守卫」的区别**。
  3. **退码语义收口**：头注补 `rc=2`；**消除「该红而退 `3`」盲区** —— 探路件侧分歧与主工程侧分歧**均须判红**（或明确将 `3` 定义为「不阻断但须告警」并给出**为何不算漏判**的论证）。
  4. **诊断质量**：主工程消费点（`cpp/cmake/HgDependencies.cmake`）对 `-DHG_CTP_HEADER_STYLE=MERGED` **显式 `FATAL` 并点名风格**（若本席裁定维持现状，须给出「静默不可编译」可接受的论证并登记）。
  5. **不回归**：`ctp`/`stub` 轨 `ctest` 与聚合 `arch-check` `rc` 不回归；`E4` 扫描不恶化。
  6. **回写 `tools/arch-check/README.md` 陈旧值**（来源 = `T-129` 交付时的连带发现，`tools/**` 非 `T-129` 写域）：`:299` 现写 `build-ctp` 的 `CTP_SDK_ROOT=/home/ctpmini/ctpmini_admin/ctp_sdk`（**Mini + `MERGED` 时代口径**），而 `T-129` 已按 `CH-015` 交付基线把三轨重配为**官方分离式 + `CLASSIC`** ⇒ 须落实该文档前缀已自要求的**三轨计数重测值**。**📌 本席 2026-09-23 就地订正本条口径（我自己的表述写在事实之前，第 86 轮）**：原写「须**同批更新** `CTP_SDK_ROOT`」；实测 `:299` 位于 **`2026-09-21` 的整块**内，而该块**已被 `2026-09-22` 的「历史时点口径 … **只加不改**」注解**声明「原句保留以留痕」「**不作为官方基线下的结论**」⇒ **应当「只加不改」**、**不得改写 `:299`**（改写既破坏历史留痕、又与该块自身声明冲突）。⇒ **合格形态 = 保留历史块 + 单列现行值 + 明确指针**（交付方即按此办理）。**判据**：文档中「现行三轨 `CTP_SDK_ROOT` / 风格 / 计数」须**无歧义可得**。
  7. **（建议级、宜做）统一 SYSTEM 形态**（来源 = `T-129` 复核观察 `OBS-T129-1`）：现主路径用「**库目标** `SYSTEM TRUE`」、`<3.25` fallback 用「**供给目标** `SYSTEM INTERFACE`」；两者**作用面当前相同**（`hg_deps::protobuf` 唯一消费方 = `hg_hgcp`）且**无功能影响**，但形态不一。宜统一为**供给目标形态**使两条路径**同形**；若判定不宜，须**写明理由**。
- **产出**：`cpp/ctp_smoke/**`（含新增 `README.md`）、`cpp/cmake/**`、**`tools/arch-check/README.md`**（回写 `:299` 陈旧 `CTP_SDK_ROOT`）、视裁定可能含接线点（`cpp/CMakeLists.txt` 或 `java/pom.xml`）
  **📌（2026-09-23 交付登记 · 已完成，待独立复核 · 只加不改）** 实例 `be-t130`（**唯一写者**：接线点 + `cpp/cmake/**` + `cpp/ctp_smoke/**` + `tools/arch-check/README.md`；**未改 `docs/**`**；**不自评 `DONE`**）。**① 接线（验收 1）**：调用点 = **ctest 用例 `ctp_found_parity`**，注册于 `cpp/CMakeLists.txt:99`（块 `:84`–`:108`；临时目录落构建树内以避 `/tmp`）；**接线前对照**：全仓 `grep -rn check_ctp_found_parity`（排 `build`/`.git`）**仅散文/注释命中**、**无** ctest/arch-check/run_probe/pom 调用点，接线前 `ctest -N` = 38/34/38（无该用例）⇒ **接线后 39/35/39 且三轨实跑 Passed**（**实扫**而非空转）。**② 外围件（验收 2）**：新增 `cpp/ctp_smoke/README.md` `dc6adb94…`，含用途/触发/退码语义，并**单列**「与『旧入口防退化守卫』的区别」（**正交**：守卫在 `HgCtpStyle.cmake` 管**陈旧缓存项冲突**、parity 管**两侧同源**；**删守卫不使 parity 变红**）。**③ 退码语义（验收 3）**：头注 `:25`–`:36` **已文档化 `0/1/2/3` 全四码**（含 `:33` 的 `2 = 用法错误`），并**明写「1/2/3 均非 0 ⇒ 调用方一律视为失败、仅 0 通过」**；**盲区已消除**（`:27`–`:32`：**仅一侧**产出判定行 ⇒ **判 `rc=1`**，两侧一视同仁；交付方变异 **M2** 实证：同一变异「预修复口径」返 `rc=3`、修复后返 `rc=1`）。**④ 诊断质量（验收 4）**：`HgDependencies.cmake:70-77` 对 `HG_CTP_HEADER_STYLE=MERGED` `FATAL` 且**点名风格**，并说明产品侧根因（`ctp_adapter/**` 直引分离式头，`CH-015`）与处理建议；**只加在主工程、不动共用 `HgCtpStyle.cmake`**（探路件仍可用 `MERGED`）。**⑤ 不回归（验收 5）**：build **项目代码 0 告警**（`grep -icE 'warning:'` = 0）；三轨 `ctest` **`ctp 39/39` · `stub 35/35` · `fetch 39/39`**（`rc 0`）；聚合 `arch-check` **`rc 0`**（`通过(0)=7`）；`E4` **47 文件 / 命中 0 / WARN 3**（**未恶化**；本次未触碰 `cpp/src`）。**⑥ README（验收 6，本席已裁）**：**未改 `:299`**，改以「**只加不改**」+ **新增 §6「官方基线口径重测（2026-09-23 · `T-130`）」**（`:331`）+ **历史块后追加指针行** ⇒ 现行三轨 `CTP_SDK_ROOT` = `<repo>/sdk/v6.7.13_20260225_api`（`ctp`/`fetch`）、空（`stub`）、风格 `CLASSIC`、计数 **39/35/39**。**⑦ 统一 SYSTEM 形态（验收 7，建议级）**：**采纳** —— `HgDependencies.cmake:259-275` 删除「`SYSTEM TRUE`/fallback 版本二分」，**一律** `target_include_directories(hg_deps_protobuf SYSTEM INTERFACE …)`；已验 `payload_codec.cc` 命令行 **`-isystem` 在、`-Wall -Wextra -Werror` 在、无 `-Wno-*`**、单 TU 重编 0 告警、`fetch` 轨 `hg_hgcp` 全量 build `rc=0`；并登记「若将来出现**绕过 `hg_deps::protobuf` 直连 `libprotobuf`** 的新消费方，须回退 `SYSTEM TRUE`」。**★反退化（3 个自设变异，隔离副本 `/root/t130-iso`）**：**M1** 主工程侧分歧 ⇒ 两行俱在、`rc=1`；**M2** 探路件侧分歧 ⇒ 修复后 `rc=1`（见上）；**M3** 摘除接线块 ⇒ `ctest -N` 中 `ctp_found_parity` **1→0**（Total 34）⇒ 证「是接线让它执行」；三变异**复位后逐字节一致**。**冻结点（连续 3 次一致）**：`cpp/src` = `327118d8…`/**115**（未变）、`cpp/cmake` = **`1e08aef4…`/3**、**交付域 = `288b3b33…`/10**。**★同类扫描（穷举「已交付未接线」判据脚本）**：全仓 `*.sh`/`*.py`（排 `build`/`.git`）= **10 个**〔**📌 编排者 2026-09-23 订正：复核方独立穷举得 **11 个**（`*.sh` 10 + `*.py` 1）；**结论不受影响**，我原计数疑漏计聚合入口或 `.py`〕；`arch-check` `E1`–`E7` 经聚合入口接线 ✅、`check_ctp_found_parity.sh` 本单接线 ✅；**不适用项** = `cpp/ctp_smoke/ci/run_probe.sh`、`cpp/proto_smoke/ci/run_smoke.sh`（**探路复现脚本**，须显式 `SDK_ROOT`、CI 无 SDK 环境不可跑，非门禁判据）⇒ **无第三处「已交付未接线」判据**。**⚠️ 两条需求本席裁定 → 已裁**（见 `BOARD.md` §5 第 86 轮）：(a) `:299` 是否**直接改写**；(b) 三轨计数 38/34/38 → 39/35/39 是否需**避免**。
  **📌（2026-09-23 独立复核 = 有条件通过 / 0 阻断缺陷 · 6 观察 · 转 `DONE` · 只加不改）** 复核者 `te-verify-t130`（**自撰探针 / 自设变异**，未复用产出方 M1/M2/M3；隔离副本 `/root/t130v`、`/root/t130v-mut`；**共享源树只读**，仅追加证据章）。**缺陷单 = 0（无 `TE-T130-00x`）**；**锚点全部实测吻合**（`cpp/src 327118d8…`/115 未变、`cpp/cmake 1e08aef4…`/3、交付域 `288b3b33…`/10、5 单文件逐一吻合；取样 `2026-09-23T08:54:27+0800`，3×10s 一致）。**★两条裁定均经独立复核维持**：**(1) `:299`「只加不改」→ 成立**（依据：该行在 `2026-09-21` 整块内、该块已有「只加不改」注解**且已点名该陈旧值**、删除数 0、新指针 + §6 已使现行值无歧义；**无相反先例**）；**(2) 驳回「改挂 `run_probe.sh` 保计数」→ 正确**（要害是「判据无调用点 ⇒ 永不生效」；藏进须 SDK、CI 不跑的 `run_probe.sh` **仍不执行、违背验收 1「实扫」**；新增独立 `ctest` 用例使判据在**三轨（含无 SDK 的 `stub`）均执行**（`stub` 实测 Passed）⇒ **+1 是「实扫」的正当代价**）。**★复核方对本单定性的精化（本席采纳，据实留痕）**：我原用「**盲区**」并暗示可能「假绿」；复核方实测指出 —— **`rc=3` 本就非 0，就 `ctest` 而言 1/3 **均为失败、并非假绿****；真问题是**退码语义误分类**（把「该红」记为「不可判定」）**＋头注漏 `rc=2`**；新码令「仅一侧」**语义正确归 FAIL(1) 且点名未披露侧** ⇒ **盲区已消除**，且**未见任何消费者把 `3` 映射为 SKIP**。**★6 项观察（均不阻断）与去向**：`OBS-T130-1`（低）**台账「三轨 build 0 告警」对 `fetch` from-scratch 不精确** —— 自建 `fetch` 轨另有 **2 条第三方告警**（protobuf `message_lite.cc` 的 `-Wstringop-overflow`，**非本工程代码、不失败构建**；增量构建下不复现，与 `OBS-T128-3` 同类）⇒ **本席已就地订正台账措辞为「项目代码 0 告警」**；`OBS-T130-2`（低）**同类扫描计数**：自述 10、**实测 11**（`*.sh` 10 + `*.py` 1）⇒ **本席已就地订正**，结论不受影响；`OBS-T130-3`（低·范围）判据**只断言 parity、不断言 configure 成败** ⇒ 若配置在**两侧均已披露后**才失败，判据返 `rc=0`（实测）—— 属**既定契约内**，且外层构建另会拦截 ⇒ **登记为已知范围，不另立号**；`OBS-T130-4`（低·建议）F 的「作用面等价」**无自动守卫**（仅注释登记）—— 现消费方唯一 ⇒ **无实际风险**，注释登记即缓解 ⇒ **不另立号**（若将来出现第二个消费方，须回退 `SYSTEM TRUE`，已登记）；`OBS-T130-5`（低）`MERGED` + **显式 SDK 根**时**探路件 `:148` 的 FATAL 遮蔽**主工程 `:71` 的点名（**主工程检查本身正确**，仅求值序致不可达）⇒ **登记为已知诊断序限制，不另立号**；`OBS-T130-6`（极低）`--help` 若在头注正文内再插 `# =====` 会提前截断（**现无此形**）⇒ 不另立号。**证据章**：`docs/TEST_RESULT.md` `# \`T-130\` 独立复核证据（2026-09-23）`（`:5394` 起，186 行）；**纯追加经编排者独立自测**（`head -5392` md5 = `3145e517…` = 提交前 HEAD 整档 md5）；现全档 `6988ede8…` / 5578 行（**以实时为准**）。
- **备注**：**⚠️ 硬约束（来源 = `T-129` 交付的同类扫描）**：若将来把 `proto_smoke` 探路件的 **protobuf fallback 版本抬到 ≥ 3.20**，须**同法把其头目录标 SYSTEM**（现用 3.19.6，其 `port.h` 仅 40 行、`-I` 下 `rc=0`，故**当前不受影响**；`T-129` 的 `-Werror=unused-parameter` 缺陷只在 ≥ 3.20 出现）。**判据形态**（作接线时不必改）：一次 top-level 配置抓两行 `[HG-CTP-FOUND] role=smoke\|main found=… style=… trader_header=… md_header=…` ⇒ 全字段逐字比较。**两套机制勿混谈**（复核方关键纪律）：**「旧入口防退化守卫」**只在「陈旧冲突缓存项被静默接受」处生效；**删守卫不会让 parity 变红**。**锚点** 交付域 = `654cf2f2…`/9（口径须排除 `build*`）。

### T-131 补齐 PRODUCT 写面 REST 端点（品种修改 / 停用 / 启用 / 计量单位）

- **需求追溯**：`FR-PRODUCT-2/3/4/13`、`FR-PRODUCT-1~14` 追溯面、`ARCHITECTURE.md` §4.1.1（控制性动词通则 / 路由与分页约定）
- **负责人**：backend-engineer#java2
- **优先级**：P1
- **依赖**：`T-012`（已交付）、`T-105`
- **估时**：**4 人时**
- **来源**：新增（**`T-018` 交付时由实施方主动披露**，编排者独立复核成立：**服务层已实现** —— `VarietyService` 有 `createProduct`(`:242`) / `disableProduct`(`:283`) / `enableProduct`(`:338`) / `putUnit`(`:369`)；**但 REST 未注册** —— `ProductRoutes.java:36-52` 仅注册 `GET|POST /product`、`GET /product/{id}`、`POST /product/whitelist`、`GET|POST /product/quota`、`GET|POST /product/{id}/contracts`、`GET|POST /product/mapping`、`GET /product/change-log`、`POST /product/import`、`GET /product/export` ⇒ **无 `PUT /product/{id}`、无停用/启用动作端点、无计量单位端点**。**属「已实现未接线」**（同族先例：`T-109`「已声明未注册」端点补齐、`T-128` 头名不同源））
- **状态**：`TODO`
- **验收标准**：
  1. **端点补齐**：注册 品种**修改**、**停用**、**启用**、**计量单位** 四类写面端点（**形态须按 §4.1.1 的控制性动词通则**定，**不得**自创 REST 风格；`PUT` 或动作式 `POST` 由实现方按通则裁定并写明理由）；**须给出 `(方法,路径)` 清单**（依 `T-097` 目标形态，**方法级**而非仅路径级）。
  2. **前端对接**：`web/js/pages/product.js` 的「概览」Tab 里已诚实登记的「FR 覆盖与接口缺口」须**随之更新为已覆盖**（或写明仍未覆盖的项与理由）——**不得**留陈旧登记（同族：`T-020` 已登记需订正 `plan.js` 陈旧误述）。
  3. **白名单读取**（**第 91 轮订正 —— 原验收依据错位**）：本条原仅核 `FR-PRODUCT-12`；**实测** `FR-PRODUCT-12` 的**输入列不含读取**（`PRODUCT.md:250`「子账户标识、品种集合（增/减）」）、**不要求读取端点**，故原「若不要求 ⇒ 写明依据」会成为**合规出口**、把真缺口放走。**真正的读取义务在 `FR-PRODUCT-6`** ——「处理规则：详情含基础属性、额度指标、期货合约关联、现货品类映射、计量单位、**所属子账户白名单**、最近变更历史摘要」（`docs/REQUIREMENTS/PRODUCT.md:162`）⇒ **须补查询端点**。**要求**：注册 `GET /product/whitelist` 返回当前**完整白名单**（现仅 `POST` ENABLE/DISABLE，见 `ProductRoutes.java:39`）；**须同时核 `FR-PRODUCT-6` + `FR-PRODUCT-12` 两条**，并写明 `subAccountId` 取值域与权限口径。**依据** = `te-verify-t018` 观察 `OBS-T018-4`。（FR 原文行号）并说明前端「无法列出当前白名单」属**合法限制**。
  4. **权限**：写面须接功能权限强制点 —— **与 `T-118` 的关系须写明**（`T-118` 覆盖 `createProduct`/`disableProduct`/`enableProduct`/`putUnit` 的**权限门禁**）；若 `T-118` 未交付 ⇒ **说明本任务是否依赖它**、或按 `CH-012` 口径先接最小门禁（**不得**引入与 `CH-012`（待用户确认）冲突的判定）。
  5. **不回归**：`mvn -B -o clean test` 全绿；路由计数**只能增加**（并同步 `T-097`/路由一致性判据的期望值）；聚合 `arch-check` `rc 0`；`E5`（`dao` 外无 SQL）保持 `rc 0`。
  6. **前端**：`bash tools/arch-check/check_frontend.sh` 保持 **`E6` rc=0**。
- **产出**：`java/src/main/java/com/hedgeguard/web/handler/product/**`（+ `ProductRoutes.java`）、`web/js/pages/product.js`（对接）、视裁定可能含 `web/js/core/routes.js`
- **备注**：**勿越界**：本任务只补**端点与对接**；`FR-PRODUCT-5` 的**筛选参数**与**输出列**（子账户 / 最近变更时间）**属 `T-116`**，不在此重复；`FR-PRODUCT-8` 导出空数据语义**亦归 `T-116`**。

### T-132 数值字段「字符串化十进制」契约收口（`GET /product/quota`）+ 穷举同类点

- **需求追溯**：`ARCHITECTURE.md` §4.1.1 `:625`（「**金额 / 数量** | **字符串化十进制**（避免 JS `Number` 精度丢失），如 `"1234.5600"`」）、`:917`、`:1085`、`:1282`（**`G-2` 额度字段口径** = `quota_limit` / `used` / `frozen` / `limit` / `released_quota` **一律 `string`（十进制）**，与 `DATA_MODEL.md` `DECIMAL(20,4)` 列交叉核对）；`NFR-COMPAT-*`
- **负责人**：backend-engineer#java2
- **优先级**：P1
- **依赖**：`T-105`
- **估时**：**3 人时**
- **来源**：新增（**`T-018` 交付时由实施方披露 + 编排者独立复核**：`GET /product/quota` 的 `quotaPanorama` **直出实体**，`BigDecimal`（`quotaLimit` / `usedQty` / `frozenQty`）经 **Gson 序列化为 JSON `number`** ⇒ **违反 §4.1.1 `:625` 与 `G-2`**（额度字段一律 `string`）。**注意**：这不是「精度观感」问题，而是**契约面不符** —— 前端 `format.decimal` 兜底**只掩盖症状**）
- **状态**：`TODO`
- **验收标准**：
  1. **修正**：`GET /product/quota` 的金额/数量类字段一律序列化为 **`string`（十进制）**（形态与 §4.1.1 `:625` 示例一致，如 `"1234.5600"`）；`GET` 与 `POST` 两条路径的**请求/响应**均须核。
  2. **★穷举同类点**（**本任务主要工作量**）：**全仓穷举**「以 JSON `number` 输出 `BigDecimal`（金额/数量/价格/比率/额度类）」的**响应点**，给**清单**（文件:行号 + 字段名 + 判定），并**单列「不适用项」**（例如确为计数的整数、或已在 DTO 层转 `string` 者）及理由。**判据**：清单须可由**同一命令**复现（给出该命令）。
  3. **契约断言（双向）**：须新增或修订**契约级断言**（例如「响应 JSON 中该字段 `type == string`」）；**改前必红、改后必绿**（给两次运行证据）。
  4. **不回归**：`mvn -B -o clean test` 全绿；聚合 `arch-check` `rc 0`；前端 `E6` `rc 0`；前端**不得**依赖 `number`（可保留兜底但须标注为兼容）。
- **产出**：涉事 Handler/序列化层（`java/src/main/java/com/hedgeguard/web/handler/**` 或共享序列化配置）+ 测试；清单回传编排者
- **备注**：**同类面可能不止 PRODUCT** ⇒ 第 2 条**必须穷举**（勿只改一处）。若发现**同一根因**在大范围存在（例如全局 Gson 配置未注册 `BigDecimal` → `string` 适配器），**优先做单点修复 + 全仓回归**，并在报告中说明修复面。

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
| **M2** | ④交易 `TRADE`(9 表) + ⑤结算 `SETTLE`(16 表) + ⑨资金 `FUND`(9 表) | **C++ + Java**（双组件联调） | **M2 门禁 = TCP 协议冻结**（✅ HGCP v1.0.1 FROZEN；消息集 40 / 错误码 62 不变，`CH-007`）+ **3 项探路（T-026 / T-027 / T-028）+ 汇签（T-030）**（`T-029` `REJECTED`） | T-026 ~ T-060（35，其中 `T-029` 已取消、计 0 人时）+ **T-090 ~ T-093**（4，架构 §3.0.2 判据 E1/E3/E4/E6 落点，M2 首周批）+ **T-095**（1，`E2` 判据按 `E5` 同法收紧，M2 窗口）+ **`T-096` ~ `T-099`**（4，架构 §4.1.1 两项约定 + §8.2 强制点／机制订正落点，`#java`，M2 窗口）+ **`T-100`**（1，架构 §8.2①③④ **主落点**：`GET /plan` 读取面隔离装配，`#java`，M2 窗口，**依赖 `T-016`**）+ **`T-101`**（1，架构 §8.2③「其余读取面」通则落点：`GET /plan/{id}/trade-plans` 读取面隔离装配，`#java`，M2 窗口，**依赖 `T-100`**）+ **`T-102`**（1，写面**子账户可见性 / 交易权限强制补齐**：`assertSubAccountVisible` 与 `assertCanTrade` 未接线 + `withdraw` 发起人校验缺失 + **第 3 轮再扩 ④** `decomposeTradePlans` 写路径读取未 scope，`#java`，M2 窗口，**依赖 `T-100`**）+ **`T-103`**（1，`GET /plan/{id}/trade-plans` **补分页/筛选/排序**〔按 `FR-PLAN-15` 输入列〕，`#java`，M2 窗口，**依赖 `T-101`**）+ **`T-104`**（1，`FR-PLAN-15` **处理规则**两项〔**剩余可下单量** + **已达上限标记**〕（含前端展示，另派），`#java`，M2 窗口，**依赖 `T-103`**）+ **`T-105`**（1，PRODUCT **列表类**读取面口径收口，`#java2`，M2 窗口，**依赖 `T-100`**）+ **`T-106`**（1，PRODUCT **单对象**读取面隔离，`#java2`，M2 窗口，**依赖 `T-100`**）+ **`T-107`**（1，SPOT 读取面口径收口，`#java2`，M2 窗口，**依赖 `T-100`**）+ **`T-108`**（1，补齐 **AUTH 3 个**未实现 DAO 的实现类〔第 19 轮收窄：原「CONFIG+AUTH 7 个」，CONFIG 侧 4 个由 `T-109` 交付〕，`#java2`，M2 窗口，**依赖 `T-004`**）+ **`T-111`**（1，**服务端敞口推导引擎** + 回填 `FR-SPOT-16` 验收 1〔变更后重算敞口〕，`#java2`，M2 窗口，**依赖 `T-109`**；来源 = `TE-T109-005`）+ **`T-116`**（1，PRODUCT 读取面「需求面 > 实现面」缺口：`FR-PRODUCT-5` 输出列 + `FR-PRODUCT-8` 导出筛选一致，`#java2`，M2 窗口，**依赖 `T-105`**；来源 = `te-verify-t105` 范围外观察）+ **`T-117`**（1，PRODUCT 白名单可见性 over-inclusive 修正〔可见子查询补 `status='ENABLED'`〕，`#java2`，M2 窗口，**依赖 `T-105`**；来源 = `te-verify-t105` 范围外观察）+ **`T-118`**（1，补齐全局 / 账户级对象写面功能权限强制点，`#java2`，M2 窗口，**依赖 `T-049`**；来源 = `tl-quota-authz` 实测）+ **`T-120`**（1，**`E4` 判据接线落地**〔默认根改 `cpp/src/{order_engine,risk_engine,risk_state}` + `arch-check.sh` / `README` 同步〕，`tech-lead`，M2 窗口，**依赖 `T-031`**；来源 = `tl-e4-scope` E4 扫描根裁定〔`ARCHITECTURE.md` `v1.5.12`〕+ `te-verify-t031` 第 2 点）+ **`T-121`**（1，落地「`§4.2.8` ↔ `proto/hgcp.proto` 一致性」**CI 判据脚本**〔`E7`〕，`tech-lead`，M2 窗口；来源 = `te-verify-proto` 观察 `O-2`；**第 39 轮新立、第 41 轮补登记入本行**）+ **`T-122`**（1，落地「**冻结不变量**（`40` / `62` / `VER 0x0100` / `FR 184`）」**CI 判据脚本**，`tech-lead`，M2 窗口；来源 = `te-verify-proto2` 判据设计建议经 `tl-t121` `U-3` 上报；**第 41 轮新立**；⚠️ **`FR` 项有硬前置 = 计数口径须先定义**）+ **`T-123`**（1，订正判据计数注释〔`cpp/CMakeLists.txt:70` / `java/pom.xml:129` 仍写「`E1`–`E6` 六判据」〕，`#cpp` + `#java` **跨写域**，M2 窗口；来源 = `te-verify-t121` 观察 `O-2`；**第 41 轮新立**）+ **`T-124`**（1，`risk_state` 诊断访问器（`last_result()`/`stats()`）并发读安全化，`#cpp`，M2 窗口，**依赖 `T-034`**；来源 = `te-verify-t034` 观察 `O-1`/`O-3`；**第 42 轮新立**）+ **`T-125`**（1，`T-096` 复核观察收口〔产品侧 `ORDER BY` 形状守卫补齐 + `orderByEnsuringUnique` 大小写不敏感〕，`#java`，M2 窗口，**依赖 `T-096`**；来源 = `te-verify-t096` 观察 `O-2`/`O-4`；**第 47 轮新立**）+ **`T-126`**（1，`PermissionEntry` 岗位维度承载补齐〔`has_settle_role`/`is_management_role`〕＋ 引擎 `evalPermission` 消费，`#cpp`，M2 窗口，**依赖 `T-035`**；来源 = `T-035` 独立复核观察 `EXP-T035-002`〔仅具结算岗者当前可操盘，与 `§4.3.2` `0x100E` 不符〕；**第 52 轮新立**）= **64** | C++ 交易组件（CTP/事前风控/指令流水/重放/HGCP）+ Java 桥接与业务服务 + 前端 + **`tools/arch-check/` 判据脚本** | CTP 模拟盘期货开平 + 期权策略，每单可追溯子账户与计划；交易路径零 DB 访问；子账户加总一致；划拨后加总不变（T-058） |
| **M3** | ⑥风险控制 `RISK`(11 表) + ⑦审计 `AUDIT`(8 表) | **C++（事前，M2 已交付）+ Java（事中/事后）** | 依赖 M2 交付（`T-030` 后的事前风控引擎 T-035） | T-061 ~ T-075 + T-094（16） | 事中/事后风控与风险报表、规则校验与红线、全链路追溯、违规筛查、报表导出 | 逐类构造超限场景验证阻断；验证事中风控告警；崩溃恢复不产生超额下单；导出季度报表与追溯报告（T-073/T-074） |
| **M4** | ⑧套保会计 `ACCT`(12 表) + 实盘验证 + 硬化 | **Java + 系统级** | 依赖 M3 交付 | T-076 ~ T-088（13） | CAS 24/IFRS 9 文档、有效性评估与再平衡、凭证草稿、双侧盈亏、证据链导出、安全加固、实盘运行 | 生成完整会计文档包；实盘运行 1–2 个月无违规告警；安全审计通过；换库可行性与断网终验（T-085~T-087） |

> **表数核对**（`DATA_MODEL.md` §0.6，合计 **111**）：M1 = 8+10+8+13+7 = **46 张**；M2 = 9+16+9 = **34 张**；M3 = 11+8 = **19 张**；M4 = **12 张**；46+34+19+12 = **111** ✅。
> **门禁依赖的显式性**：T-003 起（M1 编码）依赖 T-002；T-031 起（M2 开发）依赖 T-030（其中已含 M2 协议冻结事实）；M2 协议冻结本身已完成，`T-032`/`T-042` 直接引用 `proto/hgcp.proto`。

---

## 5. 资源负荷

| 角色 | 负责任务数 | 合计估时 | 负荷评估 |
|------|-----------|---------|---------|
| `backend-engineer#cpp` | 20 | **291 人时**（约 36.4 人天） | 高。集中于 M2；`T-032`/`T-035`/`T-036` 为最长链，**单点风险**，须优先投入。**`CH-006` 两笔已扣**：`T-029` 取消（-8）、`T-040` 改派 `#java`（-16）；**第 41 轮 `T-123` +1**（`268 → 269`）；**第 42 轮 `T-124` +2**（`269 → 271`）；**第 52 轮 `T-126` +4**（`271 → 275`，`PermissionEntry` 岗位维度承载补齐）；**第 61 轮 `T-027` +3 / `T-033` +5**（`275 → 283`，**官方 CTP 基线变更**的重做与移植）；**第 70 轮 `T-127` +2**（`283 → 285`，`canTrade` 岗位维度补齐）；**第 74 轮 `T-128` +1**（`285 → 286`，统一 CTP 头名变量源）；**第 80 轮 `T-129` +2**（`286 → 288`，修 `fetch` 轨构建失败）；**第 81 轮 `T-130` +3**（`288 → 291`，判据接线＋外围件＋诊断质量） |
| `backend-engineer#java`（主 · **实时与交易链**） | 27 | **329 人时**（约 41.1 人天） | 高。骨架/DAO 基座/桥接/交易执行/事中风控/RISK 参数与复算/安全加固 + **行情 WebSocket 服务端（生产级，`CH-006` 定界移入 `T-040`，+16 人时）**；**M1 关键路径 `T-003`→`T-004` 由本席先行交付并冻结接口**（`R-12` 预案 A 已生效）。**2026-09-20 `T-061` 范围收窄**：首增量 `t_param_dispatch` 单表 20 → **2 人时**（-18），余 10 张表转 `T-094`（待指派）。**2026-09-21 新增 `T-096`~`T-099`**（2 / 4 / 3 / 2 人时 = **+11 人时**，架构 §4.1.1 两项约定 + §8.2 强制点／机制订正落点，M2 窗口）；**2026-09-21 新增 `T-100`**（4 人时，架构 §8.2①③④ **主落点**：`GET /plan` 读取面隔离装配，依赖 `T-016`，M2 窗口）；**2026-09-21 新增 `T-101`**（3 人时，架构 §8.2③「其余读取面」通则落点：`GET /plan/{id}/trade-plans` 读取面隔离装配，**相邻残余缺口**，依赖 `T-100`，M2 窗口）；**2026-09-21 新增 `T-102`**（**5 人时**，写面**子账户可见性 / 交易权限强制补齐**：`AuthService.assertSubAccountVisible` 与 `assertCanTrade` 未接线 + `PlanService.withdraw` 发起人校验缺失 + **第 3 轮再扩 ④** `PlanService.decomposeTradePlans`（`:786`）写路径读取未 scope；`T-100` 专项复核 `EXP-T100-1` 同族发现 + `T-101` 交付披露；依赖 `T-100`，M2 窗口）；**2026-09-21 新增 `T-103`**（4 人时，`GET /plan/{id}/trade-plans` **补分页/筛选/排序**〔按 `FR-PLAN-15` 输入列，`PLAN.md:321`〕；来源 = `T-101` 独立复核 `EXP-T101-1`；依赖 `T-101`，M2 窗口）；**2026-09-21 新增 `T-104`**（2 人时，`FR-PLAN-15` **处理规则**两项〔**剩余可下单量** + **已达上限标记**〕；被 `T-101`/`T-103` **两次**判为「非本任务范围」⇒ 单独立号以免持续掉队；依赖 `T-103`，M2 窗口）；**2026-09-21 `T-096` 估时上修**（**2 → 3 人时**，追加「稳定排序」能力，来源 = `T-103` 交付披露 (b)：分页 `ORDER BY` 仅单列、无唯一 tie-break）；**2026-09-21 第 24 轮新增 `T-115`**（**+6 人时**，修正 `CROSS_READONLY` **写面**放行〔写路径拒绝跨账户只读身份；读面语义不变〕；来源 = `te-verify-t112` 独立复核缺陷①（高·真绕过）；依赖 `T-102`/`T-110`/`T-112`，M1 窗口）；**2026-09-21 第 25 轮 `T-115` 判 `REJECTED`**（重复立项、编号留痕、**不计人时**）⇒ **−6 人时**（**310 → 304**）；**2026-09-21（第 27 轮）编排者指派承接 `T-094`**（RISK 余 10 张表 DDL 与 DAO，M3 窗口，**+18 人时**）⇒ **304 → 322 人时（24 → 25 个）**；**2026-09-21（第 29 轮）新增 `T-119`**（补齐 `putQuota` 层③〔`ACCOUNT_PRODUCT`/账户哨兵 `0`〕账户级写面授权门禁，**+5 人时**）⇒ **322 → 327 人时（25 → 26 个）**；**2026-09-22（第 47 轮）新增 `T-125`**（`T-096` 复核观察收口，**+2 人时**）⇒ **327 → 329 人时（26 → 27 个）**；**2026-09-23（第 89 轮）新增 `T-131` +4 / `T-132` +3**（PRODUCT 写面端点补齐、字符串化十进制契约收口，来源 = `T-018` 交付时披露）⇒ `#java2` **42 → 44 个**、**617 → 624 人时**；**第 91 轮 `T-116` 范围扩（3 → 5 人时，+2）** ⇒ **44 个不变**、**624 → 626 人时** |
| `backend-engineer#java2`（新 · **台账与合规链**） | 44 | **626 人时**（约 78.0 人天） | **最高**。品种/现货/敞口/计划 → 结算/资金 → 审计 → 会计全链；**待 `#java` 冻结 `T-003`/`T-004` 接口后并行开工**，其解阻点仍由 `#java` 承担（见 §5.1）。**2026-09-21 第 11 轮新增 `T-105`/`T-106`/`T-107`**（**+5 +2 +4 = +11 人时**，PRODUCT/SPOT **读取面口径收口**，`tl-triage` 普查 + 编排者裁定，M2 窗口）；另**承接 `T-016` 的 `CR-002` 修复**（严重级，写作属主 = `#java2`）；**2026-09-21 第 15 轮新增 `T-108`**（**+8 人时**，补齐 CONFIG+AUTH **7 个**未实现 DAO 的实现类，来源 = `T-016`/`CR-002` 实施受阻连带发现，M2 窗口；**第 19 轮范围收窄为 AUTH 3 个**，CONFIG 侧 4 个由 `T-109` 交付）；**2026-09-21 第 16 轮新增 `T-109`**（**+12 人时**，补齐 CONFIG/SPOT/PLAN「已声明未注册」端点 **15** 条〔CONFIG 9 / SPOT 5 / PLAN 1〕，**M1 窗口**，依赖 `T-005`/`T-006`/`T-007`，来源 = 对齐普查 `audit-cov`）；**2026-09-21 第 21 轮新增 `T-110`/`T-111`**（各 **8 人时** = **+16 人时**；`T-110` = SPOT 写面（`T-013` 范围）**子账户可见性强制**〔M1 窗口〕、`T-111` = **服务端敞口推导引擎** + 回填 `FR-SPOT-16` 验收 1〔M2 窗口〕，均依赖 `T-109`）；**2026-09-21 第 22 轮新增 `T-112`/`T-113`**（`T-112` = 写路径子账户可见性强制的全域收口〔PRODUCT 写面 + PLAN 写面〕，**+8 人时**；`T-113` = SPOT 现货单据 `subAccountId` 缺省错误呈现修正，**+2 人时**；均 `#java2`、M1 窗口）；**2026-09-21 第 23 轮新增 `T-114`**（**+2 人时**，补齐 `POST /spot/exposure/recalc`（`recalcForDisplay`）子账户可见性强制，写路径越权面〔读 + `t_spot_change_log` 留痕写〕，**M1 窗口**，依赖 `T-110`；来源 = `T-112` 实施者第 3 步全仓写路径扫描）；**2026-09-21 第 28 轮新增 `T-116`/`T-117`/`T-118`**（**+3 +2 +5 = +10 人时**，均 M2 窗口：`T-116`/`T-117` = PRODUCT 读取面「需求面 > 实现面」缺口与白名单 over-inclusive 修正〔来源 = `te-verify-t105` 范围外观察〕、`T-118` = 全局 / 账户级对象写面功能权限强制点〔来源 = `tl-quota-authz` 实测〕） |
| `frontend-engineer` | 11 | **176 人时**（约 22 人天） | 中。依赖后端 REST/WS 就绪（T-012/T-016/T-046/T-047/T-049/T-080）后方可开工，前期可先做 T-017 基座 |
| `tech-lead` | 10 | **43 人时** | 低（任务数少）；但 T-030（M2 铺开门禁）**不可延**。另承担架构 §3.0.2 判据脚本 `E1`/`E3`/`E4`/`E6` 四条落点（`T-090`~`T-093`，各 4 人时）+ **`E1`~`E6` 六脚本形态/退出码语义一致性收口（`T-095`，第 10 轮 4 → 8 人时）** + **`T-120`（`E4` 判据接线落地：默认根改 `cpp/src/{order_engine,risk_engine,risk_state}` + `arch-check.sh` / `README` 同步，**3 人时**，第 31 轮新增）** + **`T-121`（`E7`：`§4.2.8` ↔ `proto/hgcp.proto` 一致性判据脚本，**4 人时**，第 39 轮新增）** + **`T-122`（冻结不变量 `40`/`62`/`VER 0x0100`/`FR 184` 判据脚本，**4 人时**，第 41 轮新增）**——**均为 M2 批，可与 `T-030` 并行**。**本行计数守恒**：`T-090`~`T-093`（4）+ `T-095`（1）+ `T-120`（1）+ `T-121`（1）+ `T-122`（1）= **8**，另 **`T-001`**（4 人时）与 **`T-030`**（汇签，4 人时）= **2** ⇒ **10 个 / 43 人时**（`16 + 8 + 3 + 4 + 4 + 4 + 4`）。 |
| `编排者`（team-lead） | 1（T-089） | **4 人时** | 低；T-089 已完成（门禁制品定版冻结，属治理动作，非 7 类执行角色） |
| `test-manager` | 5 | **60 人时** | 中。`T-022`/`T-056`/`T-072`/`T-084` 分别卡在 M1/M2/M3/M4 前段，须**提前于对应开发**准备 |
| `test-engineer` | 10 | **240 人时**（约 30 人天） | 高。M2/M3 测试与生产级联调叠加，须与后端并行推进 |
| `code-reviewer` | 4 | **64 人时** | 低-中。每里程碑末段集中审查（T-025/T-060/T-075/T-088），阶段性峰值高 |
| **合计** | **132** | **1833 人时**（约 **229.1 人天**） | 按 `PROJECT.md` §6 工期 5–6 个月（含实盘观察期 1–2 个月日历），**须 C++/Java 双后端并行、测试前置**方可匹配。**守恒自检**：§5 九个角色行合计 `291 + 329 + 626 + 176 + 43 + 4 + 60 + 240 + 64` = **1833** ✔。**⚠️ 逐轮增量口径：以本表各角色行的实时明细为准；历轮累加叙述已不再逐笔维护（避免与并发写入者的增量冲突）**。 |

**按里程碑估时**：

| 里程碑 | 任务数 | 合计估时 | 占比 |
|--------|-------|---------|------|
| M1 | 35 | 452 人时 | 24.66% |
| M2 | 68 | 841 人时 | 45.88% |
| M3 | 16 | 288 人时 | 15.71% |
| M4 | 13 | 252 人时 | 13.75% |
| **合计** | **132** | **1833 人时** | 100% |

> **本表口径**：任务数按**编号计**（`T-029` `REJECTED` 仍占 1 个编号、计入 M2）；**人时按实际执行计**（`T-029` 计 0），故 M2 = 720 - 8 + **16**（`T-090`~`T-093`）+ **8**（`T-095`，第 10 轮 4 → 8）+ **11**（`T-096`~`T-099`）+ **4**（`T-100`）+ **3**（`T-101`）+ **5**（`T-102`）+ **4**（`T-103`）+ **2**（`T-104`）+ **1**（`T-096` 上修）+ **11**（`T-105`~`T-107`，第 11 轮）+ **8**（`T-108`，第 15 轮）+ **8**（`T-111`，第 21 轮）+ **10**（`T-116`~`T-118`，第 28 轮）+ **3**（`T-120`，第 31 轮）+ **4**（`T-121`，第 39 轮）+ **4**（`T-122`，第 41 轮）+ **1**（`T-123`，第 41 轮）+ **2**（`T-124`，第 42 轮）+ **2**（`T-125`，第 47 轮）+ **4**（`T-126`，第 52 轮）+ **8**（`T-027` +3 / `T-033` +5，第 61 轮）+ **2**（`T-127`，第 70 轮）+ **1**（`T-128`，第 74 轮）+ **2**（`T-129`，第 80 轮）+ **3**（`T-130`，第 81 轮）+ **2**（`T-116` 范围扩，第 91 轮：3 → 5 人时）= **841 人时**；M1 = **445 人时**（= 408 + `T-109` **12**〔第 16 轮〕+ `T-110` **8**〔第 21 轮〕+ `T-112` **8** + `T-113` **2**〔第 22 轮〕+ `T-114` **2**〔第 23 轮〕+ `T-115` **6**〔第 24 轮〕− `T-115` **6**〔第 25 轮：`REJECTED`、不计人时〕+ `T-119` **5**〔第 29 轮〕）；M3 = **288 人时**（`T-061` 20 → **2** + `T-094` **18**，RISK DDL 总量守恒），全项目 **1833 人时**（详见 §1 合计行）。**占比分母 = 1833 人时**（各段独立四舍五入至 2 位小数；**尾差按最大余数法分配** ⇒ 分项合计 100.00%（M1 `24.66` / M2 `45.88` / M3 `15.71` / M4 `13.75`）；⚠️ 旧注「尾差计入 M2 ⇒ **M2 = 834**」为**历史口径、已废**（M2 现 = **839 人时**））。**⚠️ 本表勘误（第 41 轮）**：`M2` 原记「58 个 / 806 人时」**与全表口径不符**（漏 `T-121` 一个编号 + 4 人时；`806` 系第 31 轮时点值）；**本轮据 §1 行数实测（121 → 122 → 123）+ 人时守恒一并订正为「61 个 / 815 人时」**（**该值为第 41 轮时点值、非现行**；**现行以上表与 §5 角色行为准 = `64 个 / 823 人时`**），各行占比按新分母重算。

> **负荷结论**：Java 侧合计 **953 人时**（`#java` **329** ＝ `R-12` 二分 276 + `CH-006` 定界移入 `T-040` 16 − `T-061` 收窄 18 + `T-096`~`T-099` 11 + `T-100` 4 + `T-101` 3 + `T-102` 5 + `T-103` 4 + `T-104` 2 + `T-096` 上修 1 + `T-115` 6〔第 24 轮〕− `T-115` 6〔第 25 轮：`REJECTED`、不计人时〕+ `T-119` 5〔第 29 轮〕+ `T-125` 2〔第 47 轮〕（**历轮增量叙述，不再逐笔维护；现行值以 §5 角色行为准**）；`#java2` **617**（= 595 + `T-112` 8 + `T-113` 2，第 22 轮 + `T-114` 2，第 23 轮 + `T-116` 3 + `T-117` 2 + `T-118` 5，第 28 轮），`R-12` 预案 A 二分后两个实例）与 `backend-engineer#cpp`（**291 人时**，已扣除 `T-029` 8 + 移出 `T-040` 16；**含 `T-123` +1、`T-124` +2、`T-126` +4、`T-027` +3、`T-033` +5、`T-127` +2、`T-128` +1、`T-129` +2**）合计 **1244 人时 = 全项目的 67.94%**（分母 1831；**`T-094` 18 人时已于 2026-09-21（第 27 轮）由编排者指派入 `#java`，故已计入 Java 侧**），与 `PROJECT.md` §8「本项目必须有 C++ 与 Java 两种后端能力」一致。**若任一后端能力缺位，M2 双组件联调无法闭环**——应在 S3 门禁处向用户确认资源到位。
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

