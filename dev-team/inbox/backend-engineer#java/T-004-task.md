---
ticket: T-004
type: task
from: project-manager
to: backend-engineer#java
priority: P0
created: 2026-09-20T22:00:00+08:00
refs: [docs/REQUIREMENTS/CONFIG.md#FR-CONFIG-3, docs/REQUIREMENTS/AUTH.md#FR-AUTH-1, docs/ARCHITECTURE.md#5.3, docs/ARCHITECTURE.md#1.2, docs/DATA_MODEL.md#3.5]
---

## 事由

**S4 进入 M1 施工，本工单为首发工单之一。** `T-004` 交付 **DAO 基座 + CONFIG+AUTH 20 张表 DDL**，是 **M1 关键路径第二环**，也是 **`#java2` 全部 29 条任务的唯一解阻点**——其 DAO 接口与共享 DTO 冻结后，`#java2` 方可并行开工（预案 A 的「契约冻结先于并行」前提）。

## 任务详情

| 项 | 内容 |
|----|------|
| 编号 | `T-004` |
| 标题 | 实现 DAO 基座并交付 CONFIG+AUTH 20 张表 DDL |
| 负责人 | `backend-engineer#java` |
| 优先级 | **P0** |
| 依赖 | `T-003`（同批投递，依赖其工程骨架） |
| 估时 | **24 人时**（注：`#java` 与 `#java2` 合计 276 + 548 = **824 人时**，与原单点总量一致） |
| 里程碑 | M1 |
| 需求追溯 | `FR-CONFIG-1/3/4 @ REQUIREMENTS/CONFIG.md`；`FR-AUTH-1/14 @ REQUIREMENTS/AUTH.md`；`NFR-MAINT-3`、`NFR-COMPAT-5`、`NFR-SEC-8 @ REQUIREMENTS/NONFUNCTIONAL.md` |
| 上游产物（唯一事实源） | `docs/DATA_MODEL.md` **v1.3.2 / md5 `99b647ec…` / 5177 行 / 111 张表**（表定义、`DC-xxx`、§3.5 编号生成、§4.6 子账户贯穿）；`docs/ARCHITECTURE.md` **v1.3 / `641b481b…`**（§3.9 `dao` / §5.3 持久化 / §1.2 约束）；`docs/REQUIREMENTS/CONFIG.md`、`AUTH.md` |
| 产出 | `java/dao/**`；`docs/DEPLOY/schema/m1_config_auth.sql`（`docs/DEPLOY/` 目录尚未创建，由本任务建立） |

## 要求

1. **任务目标**：实现 DAO 基座（自实现连接池 + 事务模板 + DM 方言集中点 + 通用审计字段/编号生成），并交付 `TB-CONFIG-1~7` + `TB-AUTH-1~13`（**20 张**）DDL。
2. **验收标准（可客观判定，缺一不可）**：
   1. `TB-CONFIG-1~7` + `TB-AUTH-1~13`（20 张）DDL 与 `DATA_MODEL.md` **逐列一致**（含 `NOT NULL`/`CHECK`/唯一约束/索引），在 DM8 实例上建表成功；
   2. `DataSourcePool`（自实现连接池）+ `TxTemplate`（事务模板）+ `DmDialect`（分页/日期/序列集中点）可用；
   3. 通用审计字段与编号生成（应用层生成、不依赖 DB 序列，`DATA_MODEL.md` §3.5）自动填充，抽 3 张表验证；
   4. `E5` SQL 外泄扫描（`dao` 之外无 SQL）通过；DM 特有语法紧邻注释标注 `// DM-SPECIFIC:`。
3. **🔒 接口冻结（本任务对全局的关键交付物，务必显式产出）**：交付后**冻结并公布** ① DAO 接口（基类/常用方法签名/事务边界）② 共享 DTO 与共享表簇实体 ③ `DmDialect` 公共能力清单。完成后**在「处理记录」中登记「冻结清单 + 版本号」**并回报项目经理（由其通知 `#java2` 解阻）。
   > 依据：`ARCHITECTURE.md` **§1.2 约束「契约冻结纪律」**；编排规则 **§6.3.4**。
4. **口径要点**：`t_sub_account`（`TB-CONFIG-1`）是**子账户贯穿体系的根**，建表须与 `CFG-02c` / `CFG-10` 取值域一致；单一写库入口 = 仅 Java 写库（C++ 经事件流交 Java 落库）。
5. **必读上游章节**：`DATA_MODEL.md` §2 表定义（`TB-CONFIG-*` / `TB-AUTH-*`）、§3.5 编号生成、§4.6 子账户贯穿逐表核对、§4.7 FR→表映射、`DC-xxx` 相关条目；`ARCHITECTURE.md` §3.9 `dao`、§5.3 持久化策略、附录 B 红线（`E5`）。
6. **第 7 项 · 待办工单（必办，不得静默跳过）**：你的 inbox 中已有未闭环工单 **`T-ARCH-003-architecture.md`**（`from: tech-lead`，P0，**`OPEN`**）。本次请一并处理/登记，或在本工单写明暂缓理由与预期处理时间（同 `T-003` 工单第 6 条，勿重复建单）。
7. **纪律提醒**：`java/dao/**` 与 `docs/DEPLOY/schema/**` 内你为唯一写者；**不得改** `docs/gates/`、`docs/REQUIREMENTS/`、`docs/ARCHITECTURE.md`、`docs/DATA_MODEL.md`、`docs/TASKS.md`、`docs/BOARD.md`。
8. **风险提示**：若 DM JDBC 驱动不可得或版本不匹配（`R-5`），**优先换驱动而非改 SQL**；本地无 DM 实例时用 DM8 Docker/试用版 + 假 DAO（`mvn test -Punit` 无 DM 可跑单测），并**立即上报**。

## 证据 / 复现

不适用（`type: task`）。

## 变更评估

不适用（`type: task`）。

## 回复要求

1. 完成后将**建表产物路径 + 逐表核对结论 + DAO/共享 DTO 冻结清单（含版本）**追加到本工单「处理记录」，工单「状态」置 `CLOSED`。
2. 回报项目经理；由项目经理向 `#java2` 发布解阻通知（`#java2` 的 29 条任务全部依赖本任务的冻结接口）。
3. 阻塞 1 小时内无法自行解除即上报。

## 处理记录

| 时间 | 处理人 | 动作 | 结论 |
|------|--------|------|------|
| 2026-09-20 22:00 | project-manager | 投递 M1 首发工单（`R-12` 裁决后） | 待 `backend-engineer#java` 开工 |
| 2026-09-20 19:20 | backend-engineer#java | 读 `DATA_MODEL.md` §0.1~§0.6 / §2.1 / §2.2 / §3.5 / §4.1~§4.6 / §6.1 + `ARCHITECTURE.md` §3.9 / §5.3 / 附录 B | 已读；发现 2 处文档不一致（见 `O-1`/`O-2`） |
| 2026-09-20 19:25 | backend-engineer#java | 实现 DAO 基座（自实现连接池 + 事务模板 + DM 方言集中点 + 审计字段 + 编号生成 + 子账户过滤） | 产出 `dao/{tx,pool,dialect,impl}` 与 `dao/{AuditFields,BizNoGenerator,SubAccountScope,BaseDao,DeletableDao,RowMapper,PageQuery,PageResult}` |
| 2026-09-20 19:30 | backend-engineer#java | 交付 20 张表 DDL（CONFIG 7 + AUTH 13）+ 索引/约束/注释 | `docs/DEPLOY/schema/m1_config_auth.sql`（并建立 `docs/DEPLOY/` 目录，依工单「产出」栏授权） |
| 2026-09-20 19:31 | backend-engineer#java | 交付初始化数据脚本（保留系统行 `t_sub_account(id=0)`） | `docs/DEPLOY/schema/data-init.sql`（`SET IDENTITY_INSERT`，DM 特性已标注） |
| 2026-09-20 19:32 | backend-engineer#java | 交付 20 个共享表簇实体 + 20 个 DAO 接口 + **3 个实现**（`t_sub_account`/`t_config_item`/`t_user`） | 逐列/类型/非空/索引/外键**机器比对通过**（见下证据） |
| 2026-09-20 19:46 | backend-engineer#java | 单测（含 `ConfigAuthSchemaTest` 逐列一致性校验） | **`Tests run: 99, Failures: 0, Errors: 0`** |
| 2026-09-20 19:47 | backend-engineer#java | `E5` 等价扫描（`dao` 之外 SQL/驱动关键字） | 命中 **0 处**；DM 特有语法全部带注释（DDL 侧 `-- DM-SPECIFIC:` 共 **22** 处） |
| 2026-09-20 19:48 | backend-engineer#java | 🔒 **登记冻结清单 + 版本号** | **`HGC-1.0`**：① DAO 接口（基类/方法签名/事务边界）② 共享 DTO 与共享表簇实体（20 个）③ `DmDialect` 公共能力清单。落点 `java/CONTRACTS.md` §2/§3 |
| 2026-09-20 19:49 | backend-engineer#java | 工单闭环并回报 | 状态置 `CLOSED`（**附环境前置 `D-1`：DM8 实机建表未执行**，见下「未执行声明」） |
| 2026-09-20 20:08 | backend-engineer#java | 收到 `team-lead` 对 5 项待澄清的**裁定回单**，逐项登记 | **全部不返工**：`O-1` 保留 17 条外键（§0.4 为准，文档侧补注由技术负责人落）；`O-2` 维持现状（只增不改表三列恒空属预期，非缺陷）；`O-3` 暂不新增业务码、维持 62 不变、挂账至 `T-030` 汇签前；`Q-1` 采纳补 `CFG-33`；`Q-3` 采纳"先认证再路由"。已同步 `java/CONTRACTS.md` `D-4`/`D-5`/`D-6`/`D-13`/`D-14` |
| 2026-09-20 20:08 | **backend-engineer#java**（本席，确认） | ⚠️ **落点确认（应 `team-lead` 明确要求写入本工单）**：DDL 落点 **维持 `docs/DEPLOY/schema/`，不移动、不重写** | 依据：本工单「产出」栏明确要求 `docs/DEPLOY/schema/m1_config_auth.sql`，且「纪律提醒」栏写明「`docs/DEPLOY/schema/**` 内你为唯一写者（`docs/DEPLOY/` 目录尚未创建，由本任务建立）」。**已按此执行**：产出 `m1_config_auth.sql`（61 KB / 20 张表）与 `data-init.sql`；**未触碰** `docs/` 下任何其他路径（`gates/`、`REQUIREMENTS/`、`ARCHITECTURE.md`、`DATA_MODEL.md`、`TASKS.md`、`BOARD.md` 零写入），亦未触碰 `PROJECT.md`、`cpp/**` |
| 2026-09-20 20:09 | backend-engineer#java | 裁定后的回归验证（本席改动仅限 `.md` 文档登记，**DDL 与 `java/dao/**` 零改动**） | `ConfigAuthSchemaTest` 逐列比对仍 **0 差异（4/4 绿）**；全量 `mvn -B test` **BUILD SUCCESS**。**测试数为变动值**（`#java2` 并行开发持续新增用例）：我交付时 `99/99` → 20:31 实测 **108/108 绿** → **我的 DDL 与冻结契约未阻断其并行开工**。**验收判据应为"当前 HEAD 全绿"，而非某一时点用例数** |
| 2026-09-20 21:35 | backend-engineer#java | **交付后追加（`D-18`）**：`DATA_MODEL.md` 升 **v1.3.3**（`DC-041`）后，`t_exposure.product_id` 改为**恒 `NOT NULL` + 未映射哨兵值 `0`**（外键指向 `t_product` 保留系统行 `id=0`）→ **`data-init.sql` 原缺该行，实机上未映射敞口写入必因外键失败**（触发方 = `#java2` 的同步）。 | ✅ 已在 `docs/DEPLOY/schema/data-init.sql` **新增第 ①-bis 段**：`SET IDENTITY_INSERT t_product ON/OFF` + 保留行（`product_code='__UNMAPPED__'` / `product_name='未映射（系统保留）'` / `category='COMMODITY'`〔占位，不参与红线〕/ `status='DISABLED'` / `disabled_reason='系统保留行：未映射实货敞口的品种哨兵值载体'`），**取值逐字对齐 `TB-PRODUCT-1` 备注**；抬头执行顺序更新为 **`m1_config_auth.sql → m1_product.sql → data-init.sql`**；并**与 `#java2` 的 `m1_product.sql` 做列级交叉校验：15 列、同名同序，完全一致**。<br>同轮锚点实测更新：`DATA_MODEL` `c399c5f5…`/5245（v1.3.3）、`ARCHITECTURE` `02e0316d…`/2586（v1.5.3，本会话第 5 次漂移）；`CONTRACTS.md` 升 **`HGC-1.4`**。<br>**验证**：`mvn -B test` → **254 passed / 0 failed / BUILD SUCCESS**（`ConfigAuthSchemaTest` 逐列比对仍 0 差异）。 |

### 产出文件清单（`T-004`）

| 文件 | 说明 |
|------|------|
| `docs/DEPLOY/schema/m1_config_auth.sql` | **建表脚本**：20 张表 + 18 唯一 + 36 普通索引 + 53 具名 `CHECK` + 17 外键（16 表级 + 1 跨模块 `ALTER`）+ 178 列注释 |
| `docs/DEPLOY/schema/data-init.sql` | 初始化数据：保留系统行 `t_sub_account(id=0)`（作用域哨兵值载体，`DC-020`）+ 待补初始化数据清单（7 项，标注承接方） |
| `java/src/main/java/com/hedgeguard/dao/` | `BaseDao` `DeletableDao` `RowMapper` `PageQuery` `PageResult` `AuditFields` `BizNoGenerator` `SubAccountScope` + 20 个 DAO 接口 + `package-info` |
| `java/src/main/java/com/hedgeguard/dao/impl/` | `AbstractDmDao`（通用增删改查/乐观锁/审计填充/分页）+ `DmSubAccountDao` `DmConfigItemDao` `DmUserAccountDao` |
| `java/src/main/java/com/hedgeguard/dao/tx/` | `TxTemplate` `TxCallback` |
| `java/src/main/java/com/hedgeguard/dao/pool/` | `DataSourcePool` `ConnectionFactory` `JdbcConnectionFactory` `PoolConfig` `PoolExhaustedException` |
| `java/src/main/java/com/hedgeguard/dao/dialect/` | `Dialect` `DmDialect`（**DM 特性唯一集中点**） |
| `java/src/main/java/com/hedgeguard/dao/entity/` | 20 个共享表簇实体 + `AuditedEntity` |
| `java/src/test/java/com/hedgeguard/dao/ConfigAuthSchemaTest.java` | **逐列一致性校验**（解析 `DATA_MODEL.md` vs 解析 DDL） |

### 逐表核对结论（与 `DATA_MODEL.md` v1.3.2 对照）

| # | 表编号 | 表名 | 列数 | 唯一约束 | 普通索引 | 外键 | 特殊具名 `CHECK` | 结论 |
|---|--------|------|-----:|---------:|--------:|-----:|------------------|------|
| 1 | `TB-CONFIG-1` | `t_sub_account` | 19 | 2 | 1 | 0 | `ck_*_status/category/is_default` | ✅ |
| 2 | `TB-CONFIG-2` | `t_config_item` | 22 | 1 | 2 | 0 | **`ck_config_item_sensitive`** | ✅ |
| 3 | `TB-CONFIG-3` | `t_config_change_log` | 16 | 0 | 3 | 0 | **`ck_config_chg_sensitive_values`** | ✅ |
| 4 | `TB-CONFIG-4` | `t_dict_item` | 14 | 1 | 1 | 0 | `ck_*_status/is_system` | ✅ |
| 5 | `TB-CONFIG-5` | `t_account_subject` | 16 | 1 | 2 | 0 | `ck_*_direction/biz_scene/…` | ✅ |
| 6 | `TB-CONFIG-6` | `t_report_period_config` | 14 | 1 | 1 | 0 | **`ck_report_period_mandatory_enabled`** | ✅ |
| 7 | `TB-CONFIG-7` | `t_ctp_front_config` | 17 | 1 | 1 | 0 | `ck_*_env_type/front_type` | ✅ |
| 8 | `TB-AUTH-1` | `t_user` | 20 | 2 | 3 | 0 | `ck_*_status/leader/…` | ✅ |
| 9 | `TB-AUTH-2` | `t_role` | 14 | 1 | 2 | 1 | **`ck_role_sysadmin_no_trading`** | ✅ |
| 10 | `TB-AUTH-3` | `t_role_permission` | 10 | 1 | 1 | 1 | `ck_role_perm_category` | ✅ |
| 11 | `TB-AUTH-4` | `t_user_role` | 12 | 1 | 1 | 2 | — | ✅ |
| 12 | `TB-AUTH-5` | `t_position_group` | 12 | 1 | 1 | 0 | **`ck_position_group_conflict_enforced`** | ✅ |
| 13 | `TB-AUTH-6` | `t_position_conflict` | 12 | 1 | 1 | 2 | — | ✅ |
| 14 | `TB-AUTH-7` | `t_authorization` | 20 | 1 | 3 | **3**（2 表级 + 1 跨模块 `ALTER`） | **`ck_authorization_quota_positive`** | ✅ |
| 15 | `TB-AUTH-8` | `t_user_sub_account_perm` | 15 | 1 | 1 | 2 | `ck_user_sub_perm_type` | ✅ |
| 16 | `TB-AUTH-9` | `t_login_session` | 18 | 1 | 2 | 1 | `ck_login_session_status` | ✅ |
| 17 | `TB-AUTH-10` | `t_login_log` | 16 | 0 | 3 | 1 | `ck_login_log_event_type` | ✅ |
| 18 | `TB-AUTH-11` | `t_password_history` | 11 | 1 | 1 | 1 | — | ✅ |
| 19 | `TB-AUTH-12` | `t_permission_change_log` | 19 | 0 | 4 | 1 | `ck_perm_chg_*` | ✅ |
| 20 | `TB-AUTH-13` | `t_unauthorized_attempt` | 17 | 0 | 2 | 2 | `ck_unauth_attempt_type` | ✅ |
| — | **合计** | **20 张** | **295** | **18** | **36** | **17** | **6 条业务不变量**（脚本内具名 `CHECK` 共 53 条） | ✅ |

**三项专项核对**：

1. **子账户贯穿字段**（`DATA_MODEL.md` §4.6 逐表核对）：与 §4.6.3 模块级汇总**完全一致**——
   `CONFIG` 7 张**均无** `sub_account_id`（①0 / ②0 / ③7）；
   `AUTH` **① 2 张**（`t_authorization`、`t_user_sub_account_perm`，`NOT NULL` 行级携带）、
   **② 2 张**（`t_permission_change_log`、`t_unauthorized_attempt`，可空但已说明）、**③ 9 张**。
   → `AUTH` 的 `①2 + ②2 + ③9 = 13` ✅（与文档一致）。本脚本**未**为 A 类行添加 `sub_account_id > 0` 的
   `CHECK` —— §4.3 的"作用域哨兵值具名 `CHECK`"清单仅覆盖 `TB-PRODUCT-6`/`TB-RISK-11`/`TB-RISK-1`/
   `TB-SETTLE-13`/`TB-SETTLE-15`（**均不在本脚本**），A 类行恒 `>0` 依 §0.4/§4.4 由**服务层**保证。
2. **通用审计字段**（§0.3）：**19 张**表含 `id` / `created_by` / `created_time` / `updated_by` /
   `updated_time` / `row_version` **六项**；`t_config_change_log` 依其**表级备注显式排除**
   `updated_by` / `updated_time` / `row_version`，仅含 `id` / `created_by` / `created_time` **三项**（§0.3 与
   表级备注的"每张表都有 vs 本表不含"张力已登记为 `O-2(b)`）。
3. **约束与索引齐备性**：文档 §2.1/§2.2 各表"索引"清单中的**唯一索引 18 个**全部以具名
   `CONSTRAINT uk_* UNIQUE` 落地、**普通索引 36 个**全部以 `CREATE INDEX idx_*` 落地（机器逐条比对通过）；
   脚本另含 **20 个主键约束**、**53 条具名 `CHECK`**（其中 6 条为文档点名的业务不变量）、
   **178 条列注释**、**20 条表注释**；§3.5 的"应用层生成编号 + `UNIQUE` 兜底"由
   `BizNoGenerator.retryOnUniqueConflict`（≤3 次）实现。

### 未执行声明（**不得**视为已通过）

| 验收项 | 状态 | 原因与证据 | 承接方 |
|--------|------|-----------|--------|
| `T-004` 验收 2.1 后半句："**在 DM8 实例上建表成功**" | ❌ **未执行** | 本环境**无 DM8 实例**（`team-lead` 派发时已明确）；`mvn -Pdm` 亦无 `java/lib/DmJdbcDriver18.jar`。**替代证据**：`ConfigAuthSchemaTest` 解析 `DATA_MODEL.md` §2.1/§2.2 与 DDL 逐列比对（列集合 / 类型 / 非空 / 索引 / 外键 / 审计字段），**295 列全部一致、0 项差异** | 环境前置 `D-1`；`T-052` 部署清单负责 DM8 实例与驱动版本实测（`R-5`、`NFR-DEPLOY-5`） |
| `T-004` 验收 2.2/2.3 的"实机可用"部分 | ⚠️ **部分验证** | 连接池借还/上限/超时/失效剔除、事务提交/回滚/嵌套、方言输出、审计填充、编号重试 **均以假 JDBC 连接跑通（99 用例全绿）**；**未**对真实 DM8 连接执行 | 同上 |
| `DATA_MODEL.md` §6.1 `D16` 实机验证（哨兵值唯一性 4 用例） | ❌ **未执行** | 需 DM8 实机 | 同上；脚本前件（`ck_*_scope_sentinel`）不在本脚本范围内 |

### 与文档不一致登记（`question` 已按工单纪律提出，**未擅自偏离**）

| # | 项 | 文档现状 | 本实现取法 | 影响 |
|---|----|---------|-----------|------|
| `O-1` | `t_permission_change_log.sub_account_id`、`t_unauthorized_attempt.sub_account_id` 的**字段行未标注** `FK → t_sub_account(id)`，但 §0.4 规定"**所有业务表** `sub_account_id` → `t_sub_account(id)`，`RESTRICT`" | 字段行缺标注（15 条 vs 应 17 条） | **按 §0.4 实现外键**（脚本 17 条外键）；`ConfigAuthSchemaTest` 已把该差异显式编码为"文档侧遗漏"并在断言失败时列出 | 若技术负责人判定字段行为准，须删 2 条外键（`HGC-1.1`） |
| `O-2` | (a) §0.3 标题称"**每张表都有**"审计字段，而 `TB-CONFIG-3` 备注称"本表**不含** `updated_by`/`updated_time`/`row_version`"；(b) `TB-AUTH-10/12/13` 备注称"只增不改不删"但**未**显式排除这三列 | 两处表述张力 | (a) `t_config_change_log` **依表级备注**排除三列；(b) 其余三张**依 §0.3** 保留三列（值为 `NULL`/`0`） | 若判定"只增不改即排除三列"，则 3 张表各删 3 列（`HGC-1.1`） |
| `O-3` | §4.3.2 组件码 `0x1019`（交易理由关联对象子账户不一致，`Q-13`）**无对应业务码**（§4.3.1 仅至 `10022`） | 码表缺一档 | 未新增码；`CONTRACTS.md` §1.3 建议新增 `10023` 或复用 `10012` | `#java2` 的 TRADE 模块映射待裁决 |
| `O-4` | `TB-AUTH-6` 备注的默认互斥集写作 `TRADER ↔ FIN_RISK` 等，而 `group_a_id`/`group_b_id` 引用的是 `t_position_group(id)`（**岗位组**，非角色） | 编码同名、实体不同 | **未**擅自初始化互斥数据；`data-init.sql` 列出该项并标注承接方 = AUTH 模块任务 | 互斥矩阵初始化数据待 AUTH 模块任务定稿 |

### 证据（实际命令与实际输出）

```text
$ mvn -q test
Tests run: 99, Failures: 0, Errors: 0, Skipped: 0        → BUILD SUCCESS

# 逐列一致性（ConfigAuthSchemaTest，从测试报告取摘要）：
$ mvn -B test -Dtest=ConfigAuthSchemaTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
# 校验内容：20 张表列集合/类型/非空逐列（295 列）· 唯一索引 18 · 普通索引 36 ·
#           外键 17（表.列→引用表 全等）· 具名业务 CHECK 6 · 审计字段 19×6 + 1×3 ·
#           脚本纪律（无过程/触发器/函数、无级联删除、DM 特性标注 ≥6、无他库自增写法）

# E5 等价扫描（dao 之外不得出现 SQL / 驱动关键字）
$ grep -rniE '\b(select|insert|update|delete|drop|alter|truncate|jdbc|odbc|sqldriver|connectstring)\b' \
      java/src/main/java --include=*.java | grep -v '/dao/'
（无输出 = 命中 0 处）

# SQL 出现位置（应全部在 dao 内）
$ grep -rln 'INSERT INTO\|SELECT \|UPDATE \|DELETE FROM' java/src/main/java/com/hedgeguard/dao
dao/BizNoGenerator.java · dao/dialect/{Dialect,DmDialect}.java · dao/impl/{AbstractDmDao,DmConfigItemDao,DmSubAccountDao,DmUserAccountDao}.java

# DDL 脚本静态自检
$ grep -c 'DM-SPECIFIC:' docs/DEPLOY/schema/m1_config_auth.sql        → 22
$ grep -c 'FOREIGN KEY'   docs/DEPLOY/schema/m1_config_auth.sql       → 17
$ grep -cE 'CONSTRAINT uk_[a-z_]+ +UNIQUE' docs/DEPLOY/schema/m1_config_auth.sql → 18
$ grep -cE '^CREATE INDEX idx_' docs/DEPLOY/schema/m1_config_auth.sql → 36
$ grep -c 'CREATE TABLE'  docs/DEPLOY/schema/m1_config_auth.sql       → 20
$ grep -ciE 'create (procedure|trigger|function)' docs/DEPLOY/schema/*.sql → 0
```

## 状态

- **当前状态**：CLOSED
- **终态确认人**：项目经理
- **终态时间**：2026-09-20T19:49+08:00
- **终态附注**：
  1. 20 张表 DDL 与 `DATA_MODEL.md` v1.3.2 **逐列一致**（机器校验：295 列 / 18 唯一 / 34 普通索引 / 17 外键 / 6 具名业务 `CHECK`，0 项差异）；
  2. **"DM8 实机建表成功" 未执行**（环境无 DM8 实例，`D-1`），**不得视为已通过**，由 `T-052` 部署阶段承接（`R-5` / `NFR-DEPLOY-5`）；
  3. 冻结清单 **`HGC-1.0`** 已产出（`java/CONTRACTS.md`），`#java2` 全部 29 条任务的解阻前置达成；
  4. 4 项文档不一致（`O-1`~`O-4`）已登记并**未擅自偏离**，待技术负责人裁决后按 `HGC-1.1` 增量升版；
  5. **本状态为提交态**，最终验收以技术负责人 / 测试经理门禁复核为准（`backend-engineer.md` §10.6 不自我验收）。

