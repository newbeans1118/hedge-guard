# HedgeGuard 参考数据（Reference Data）

> **制品**：`docs/DEPLOY/reference/**`
> **状态**：步骤 1–6 **全部交付**。`README.md` + `manifest.json` + `master/*.tsv`（14 / 140 行）+ `demo/*.tsv`（40 / 106 行）+ `tools/gen_reference.py` + `java/.../ReferenceSeed.java` + `java/.../ReferenceDataLoadTest.java`（7 用例）+ `docs/DEPLOY/README.md §7.2` 登记；全量门禁复跑结果见 §8 交付回执。
> **依据**：`PROJECT.md`（唯一事实源）· `docs/DATA_MODEL.md`（schema 唯一来源）· `docs/REQUIREMENTS/`（14 模块 FR）· `docs/REQUIREMENTS/FLOWS.md`（主数据流）· `docs/REQUIREMENTS/INDEX.md §9.1`（已确认口径）· `docs/ARCHITECTURE.md §11`（CFG-*）· `docs/DEPLOY/schema/*.sql`（DDL，列/唯一键/CHECK/FK 的机读真值）。
> **纪律**：引用版本号 / md5 / 行号一律以实时为准；勘误只追加、不回改历史原文。

---

## 1. 什么是本系统的 reference 数据

本仓此前无 `reference/` 命名制品，但存在三处同义口径，共同指向同一件事：

| 口径 | 出处 |
|------|------|
| 「品种白名单与额度指标是全链路校验的**基准数据**」 | `docs/REQUIREMENTS/INDEX.md:161` |
| 「**全局主数据** / 外部基准数据」（`t_dict_item` / `t_account_subject` / `t_report_period_config` / `t_ctp_front_config` / `t_product*` / `t_config_item` …） | `docs/DATA_MODEL.md:4838-4861` |
| 部署步 6「播种初始化数据」→ `docs/DEPLOY/schema/data-init.sql` | `docs/DEPLOY/README.md:258` |

**判定**：reference 数据 = **静态主数据 / 基准数据**（被业务数据引用的那部分），落点即「初始化播种面」。它不包含运行期由系统自身产生的流水、日志、告警、会话等（见 §7）。

与 `data-init.sql` 的关系：

- `data-init.sql` 已覆盖 ① 保留系统行（`t_sub_account(id=0)` / `t_product(id=0)`）、③ AUTH（岗位组 7 / 角色 7 / 互斥 5 / 功能权限 3）、④ CONFIG（字典 2 / 报告周期 6 / 配置 68 键）。
- 其 ②「尚未初始化」清单 7 项中 **6 项已交付，仅 #4 `t_account_subject`（套期会计常用科目模板，`is_template_default='Y'`）仍标「待 ACCT」**（`data-init.sql:88`）——本目录 `master/account_subject.tsv` 承接补齐。
- **本目录不重写、不回改 `data-init.sql` 原文**。重叠段（③/④）在 `master/` 中以同源数据复现，并由 `gen_reference.py --check` 做**只读一致性核对**，避免产生第二套冲突真值。

## 2. 目录结构

```
docs/DEPLOY/reference/
├── README.md                 # 本文：口径 / 选装 / 格式 / 纪律 / 验证
├── manifest.json             # 单一事实源：数据集 → 表 → 组 → 加载序 → 自然键 → 依赖
├── master/                   # ① 必需基准（静态、被业务引用）
│   ├── sub_account.tsv            # 仅 id=0 保留系统行（DC-020 作用域哨兵载体）
│   ├── product.tsv                # id=0 保留系统行 + 真实品种（DC-041）
│   ├── position_group.tsv / role.tsv / position_conflict.tsv / role_permission.tsv
│   ├── dict_item.tsv / account_subject.tsv / report_period_config.tsv
│   ├── config_item.tsv / ctp_front_config.tsv
│   └── product_unit.tsv / product_contract.tsv / product_spot_category.tsv
├── demo/                     # ② 可选叠加（依赖 master，一条 FLOW-001 最小闭环）
│   ├── sub_account.tsv / user.tsv / user_role.tsv / user_sub_account_perm.tsv / authorization.tsv
│   ├── product_quota.tsv / sub_product_whitelist.tsv / sub_instrument_quota.tsv
│   ├── fund_quota.tsv / annual_scale.tsv
│   ├── purchase_contract.tsv / sales_contract.tsv / production_plan.tsv / production_plan_line.tsv
│   ├── wip_record.tsv / inventory_ledger.tsv / spot_price.tsv
│   ├── exposure.tsv / exposure_detail.tsv
│   ├── hedge_plan.tsv / hedge_plan_exposure.tsv / hedge_ratio_calc.tsv / hedge_doc.tsv
│   ├── plan_approval.tsv / trade_plan.tsv / plan_state_log.tsv
│   ├── trade_order.tsv / trade_fill.tsv / trade_reason.tsv / position.tsv
│   ├── settle_cycle.tsv / settle_position.tsv / settle_fund.tsv / settle_pnl.tsv
│   ├── check_task.tsv / check_signature.tsv
│   └── hedge_relation.tsv / accounting_period.tsv / voucher.tsv / voucher_line.tsv
└── tools/
    └── gen_reference.py       # stdlib only，零新增依赖
```

> **对已确认计划的一处细化（透明登记）**：为保持「**一文件一表**」并让 `--check` 能直接按表校验，原计划中的 `reserved_rows.tsv` 拆并入所属表文件 —— `t_sub_account(id=0)` 落在 `master/sub_account.tsv`、`t_product(id=0)` 落在 `master/product.tsv`；哨兵行由 TSV 中显式 `id=0` 标识，生成器据此走 DM `SET IDENTITY_INSERT`。分组语义与「分开存放、可按需选装」不变。

## 3. 分组与选装语义

| 组 | 含义 | 是否必需 | 依赖 |
|----|------|----------|------|
| `master` | 全局主数据 / 基准数据 + 两条保留系统行 | **恒必需** | 无 |
| `demo` | 可演示业务基线（一条 `敞口 → 计划 → 审批 → 成交 → 结算 → 凭证草稿` 闭环） | 可选 | **依赖 `master`** |

- `--groups master`：只装基准，系统「能建表、有品种与字典、但无业务用户与业务数据」。
- `--groups master,demo`：装完整演示基线。
- **不支持只装 `demo`**：`demo` 中的 FK 大量指向 `master` 的哨兵行与品种行，缺 `master` 必然违反外键。
- 生成器另提供 `--only <table>` 供单表挑装（调试用，仍需自行满足依赖）。

## 4. TSV 格式约定

中立数据文件统一 **TSV**（沿用 `cpp/src/hgcp/tests/golden/*.tsv` 先例；中文描述常含逗号，TSV 可免引号转义）。

| 项 | 约定 |
|----|------|
| 编码 / 换行 | UTF-8 无 BOM / LF |
| 首行 | **表头 = DDL 列名（snake_case）扣除 5 个注入审计列后的子集**，相对顺序同 DDL；`gen_reference.py --check` 直接解析 `docs/DEPLOY/schema/*.sql` 校验列名/列数，**manifest 不复制列清单**（避免双真值漂移） |
| 注释 | 以 `#` 开头的行跳过；空行跳过 |
| 空值 | 空串 = `NULL`；字面量 `\N` 亦视为 `NULL` |
| 日期 / 时间 | `yyyy-MM-dd` / `yyyy-MM-dd HH:mm:ss`；生成器按目标环境转成 `DATE` / `TIMESTAMP` / `LocalDateTime` |
| 布尔 / 枚举 | 直接写 DDL `CHECK` 域内的字面值（`Y`/`N`、`ENABLED`、`BUY`/`SELL` …） |
| `id` 列 | **留空 = 由目标环境自增 / 生成器赋值**（DDL 虽 `NOT NULL`，但属生成器解析列，`--check` 显式豁免空值）；**显式写 `0`** = 保留系统行，DM8 目标下包 `SET IDENTITY_INSERT ON/OFF`。除 `0` 外不允许其它显式 id |
| 通用审计列 | `created_by` / `created_time` / `updated_by` / `updated_time` / `row_version` **不写入 TSV**，由生成器按目标语义注入（见 §5）；其余带 `DEFAULT` 的字段一律显式给值，不依赖 DDL `DEFAULT`（`InMemoryDmEngine` 不生效） |
| 外键列 | **填目标表的自然键值，不填数值 id**（见下） |

### 4.1 外键符号引用（可移植性的关键）

TSV 中形如 `xxx_id` 的外键列一律写**父表自然键的值**，由 `gen_reference.py` 在装载时通过「自然键 → 实际 id」映射解析。映射表见 `manifest.json` 的 `fk_ref_columns`，例如：

| 列 | 解析目标 | 填入值示例 |
|----|----------|-----------|
| `sub_account_id` | `t_sub_account.sub_account_code` | `SA-COMMODITY` |
| `product_id` | `t_product.product_code` | `CU` |
| `role_id` | `t_role.role_code` | `TRADER` |
| `user_id` / `applicant_id` / `reviewer_id` | `t_user.username` | `trader01` |
| `instrument_id` | `t_product_contract.instrument_id`（**原样透传**） | `cu2612` |
| `exposure_id` | `t_exposure.snapshot_id` | `SNAP-2026-11-20-CU-001` |
| `hedge_plan_id` | `t_hedge_plan.plan_no` | `HP-2026-001` |
| `client_order_ref` 类 | `t_trade_order.client_order_ref` | `ORD-2026-11-20-001` |
| `subject_code` | `t_account_subject.subject_code`（**原样透传**） | `6101` |

`fk_ref_columns` 同时覆盖**软引用列**（无 DB FK 但存 `t_user.username`）：`granted_by` / `reviewer_id` / `operator_id` / `applicant_id` / `approver_id` / `confirm_operator_id` / `generated_by` / `confirmed_by` / `signer_id` / `leader_flag_reviewer_id`。`plain_value_overrides` 登记 `t_trade_order.order_id`（普通可空的 C++ 指令号，不做解析）。

父表在本目录范围之外的 FK 列（`strategy_id` → `t_option_strategy`、`custom_rule_id` → `t_custom_hedge_rule`、`fund_ledger_id` → `t_fund_ledger` 等）一律留 `NULL`；`--check` 会核对未解析引用为 0。

**三类例外（实测 DDL 后登记，避免列名级误判）**：

| 类别 | 列 | 处理 |
|------|----|------|
| 自然键外键 / 普通业务代码 | `instrument_id`、`underlying_instrument_id`、`subject_code` | **原样透传**，不做 id 解析。`t_sub_instrument_quota.instrument_id` 的 FK 指向 `t_product_contract.instrument_id`（自然键而非 `id`）；`t_voucher_line.subject_code` 指向 `t_account_subject.subject_code`；其余表中 `instrument_id` 根本无 FK，只是合约代码 |
| 多态软外键 | `t_exposure_detail.source_id` | 按 `source_type` 选父表，值 = 父行 key 列以 `\|` 拼接（`DATA_MODEL.md` 记为「全模型唯一的软外键，无 DB FK 约束」）。映射见 `manifest.json` 的 `polymorphic_ref_columns` |
| 生成器解析列 | `id`、`password_hash` | `id` 留空 = 自增 / 生成器赋值；`password_hash` 留空 = 生成器注入非凭证明文标记（零落盘）。见 §6 |

### 4.2 值域纪律

- 所有取值必须落在 DDL 的 `CHECK` / `NOT NULL` / `UNIQUE` 域内（`manifest.json` 的 `natural_key` 取自 DDL 唯一约束；无唯一约束的表用 `key_source=derived` 显式声明派生的业务去重键）。
- 业务口径贴齐 `docs/REQUIREMENTS/INDEX.md §9.1` 已确认口径与 `docs/ARCHITECTURE.md §11` 默认值：**规模红线 90%（商品）/ 80%（贸易）**（Q-2）、现货价 `GLOBAL` 唯一口径（CH-003 / CFG-18）、敞口三分量全开（Q-17）、`EXPOSURE_DIRECTION` 仅 `BUY`/`SELL`。`R-CONFIG-8`：默认值不得低于确认口径，**不得静默放宽**。
- 中性文件**不复制 DDL 列清单**，也不内嵌自身 md5；一致性以 DDL 与 `--check` 为准。

## 5. 生成器 `gen_reference.py`（**已交付**，stdlib only）

```
python3 docs/DEPLOY/reference/tools/gen_reference.py --check [--groups master,demo]
python3 docs/DEPLOY/reference/tools/gen_reference.py --target dm8|sqlite|java-fixture \
        [--groups master,demo] [--out PATH]
```

- 无参数即 `--check`；`--check` 与 `--target` 互斥；`--groups demo` 单独使用报 RC=2（`demo` 依赖 `master`）。
- 默认输出 `docs/DEPLOY/reference/out/`（**派生制品，勿入库**），建议显式 `--out` 到版本控制之外。

| 命令 | 产出 |
|------|------|
| `--check` | 静态校验：TSV 表头/列数 vs DDL、值 vs `CHECK` 域、**DATE/TIMESTAMP/数值列格式 vs DDL 类型**、自然键唯一、组合式 CHECK、`fk_ref_columns` / 多态软外键全部可解析、透传列值存在于父表自然键集、`load_order` 与 DDL FK 拓扑一致、零敏感字段扫描、与 `data-init.sql` ③④ 重叠段只读一致性 |
| `--target dm8 --groups master[,demo]` | 幂等 `INSERT ... SELECT ... FROM (SELECT 1) src WHERE NOT EXISTS`（与 `data-init.sql` 同形态）；FK 自然键值以**标量子查询**在装载时解析为实际 id；哨兵行走 `SET IDENTITY_INSERT`；`created_time=CURRENT_TIMESTAMP`；**仅 INSERT，无存储过程/触发器/自定义函数** |
| `--target sqlite ...` | dev/test 轨（`CH-016` 允许），无 DM 专有语法；**不得冒充 DM8** |
| `--target java-fixture ...` | 生成 `ReferenceSeed.java`（package `com.hedgeguard.dao.testsupport`，**E5：dao 外无 SQL**，同 `M1E2eSeed` 先例）；id 确定性分配、`DATE`→`LocalDate`、`TIMESTAMP`→`LocalDateTime`、`DECIMAL`→`BigDecimal`、`row_version=1` |

**多态软外键的解析要点**：`source_id` 的复合键中若某列本身是 FK（如 `t_inventory_ledger.sub_account_id` / `product_id`），父表存的是解析后的数字 id，SQL 目标中该比较条件也必须同样用标量子查询解析，不能拿符号值去比 INTEGER 列；Java 夹具目标则直接以符号复合键查已分配的 id。

**交付时实测证据**（本轮）：
- `--check`：`master` 与 `master,demo` 均 PASS（RC=0）。
- `sqlite` 目标：建表骨架后**执行两遍**，246 语句 → 246 行 / 246 行，幂等成立；FK 与多态 `source_id` 解析抽样正确。
- `java-fixture` 目标：JDK 11 `javac -Xlint:all -Werror` 编译通过；载入 `InMemoryDmEngine`（112 表）后 `master`=140 行、`master,demo`=246 行，且 `rejectedByNotNull/Unique/ForeignKey/Check` **全为 0**。
- `dm8` 目标：246 条 INSERT + 4 条 `SET IDENTITY_INSERT`（两表 ON/OFF），已作静态核对（本环境无 DM8 实例，实机执行并入 U-1 承接）。

### 5.1 注入列与 `InMemoryDmEngine` 陷阱

生成器统一注入 `created_by=0`、`created_time`、`updated_by=NULL`、`updated_time=NULL`、`row_version`。针对 `java-fixture` 目标必须遵守 `CODEBUDDY.md` 列出的 6 个已知陷阱：

1. `row_version` 须 **≥1**（`data-init.sql` 中哨兵行用 0，仅 DM8 目标沿用；java-fixture 一律取 1）；
2. DDL `DEFAULT` 在内存引擎中不生效 → **insert 显式给全列**，`created_time` 传 `LocalDateTime`（`Timestamp` 会 `ClassCastException`）；
3. 显式 `NULL` 撞 `NOT NULL` → 由生成器按 DDL 可空性裁列；
4. `engine.table(name)` 表名区分大小写；
5. 引擎强制表级 `UNIQUE` → 自然键去重必须在生成前完成；
6. 引擎 `rollback` 为 no-op → 装载测试不依赖回滚语义。

## 6. 红线

- **零落盘**：TSV / 生成脚本 / 生成物均不得含任何口令、口令哈希、鉴权令牌、资金密码。`t_user.password_hash` 在 DDL 中是 `NOT NULL`，故 `demo/user.tsv` 保留该列但**留空**（`--check` 显式豁免），由生成器注入固定的**非凭证明文标记**（如 `__PENDING_SETUP__`）；账号仅含元数据并标 `must_change_password='Y'`。真实凭据由部署方经**环境变量注入 + 系统 API**（`AuthService` 的建用户 / 重置口令路径）设置。代价是「可登录」多一步，属必要且如实披露。
  - `--check` 的零敏感扫描应**只作用于 `*.tsv` 的数据行**（跳过 `#` 注释行与 `README.md` / `manifest.json` 文档），否则「不含 `password_hash`」这类**声明性文字**会被朴素正则误报为敏感字面值。
- `ctp_front_config.tsv` 只含前置地址 / 经纪代码 / 超时等非敏感字段；口令类一律不落盘（`docs/DEPLOY/README.md §10` 装载守卫）。
- **不新增 Maven 依赖**（compile 白名单仅 JDK11 / protobuf-java / gson）；生成器纯 Python3 stdlib。
- **不改冻结制品**、不回改 `data-init.sql` 原文、不改写 `docs/TEST_RESULT.md` 既有内容。
- wire 字段名 camelCase（`java/CONTRACTS.md`）与 DB 列名 snake_case 各归各位：本目录所有 TSV 表头恒为 **snake_case 的 DDL 列名**。
- C++ 侧零 SQL / 零 DB 直连（E1）：生成器不产出任何 C++ 侧 SQL。

## 7. 未纳入范围（诚实声明）

以下表属**运行期由系统自身产生**，不是可播种的参考数据，故意不建 TSV：`t_config_change_log`、`t_permission_change_log`、`t_login_session`、`t_login_log`、`t_password_history`、`t_unauthorized_attempt`、`t_operation_log`、`t_audit_log*`、`t_violation_screen_*`、`t_evidence_export`、`t_major_event`、`t_param_dispatch`、`t_risk_*`（告警/处置/快照）、`t_command_flow`、`t_replay_log`、`t_pre_trade_reject`、`t_trade_l3_reject`、`t_mirror_watermark`、`t_fund_flow` / `t_fund_ledger` 等资金流水，以及 CTP 侧合并数据（`t_ctp_settle_*`，`DATA_MODEL.md` 例外 E1「外部基准数据」，由对接文件导入而非播种）。

## 8. 验证清单（步骤 6）

```
python3 docs/DEPLOY/reference/tools/gen_reference.py --check --groups master        # rc 0（已实测）
python3 docs/DEPLOY/reference/tools/gen_reference.py --check --groups master,demo  # rc 0（已实测）
python3 docs/DEPLOY/tools/reconcile-schema.py                                    # 建表脚本未被破坏
python3 docs/DEPLOY/tools/fk-order-check.py                                       # rc 0
cd java && mvn -B -o clean test && mvn -B -o validate                            # 全绿 / E1–E9 rc 0
bash tools/arch-check/arch-check.sh                                               # rc 0
```

**交付回执（2026-11-24 实测，仓库根为工作目录）**

| 判据 | 命令 | 结果 |
|------|------|------|
| 参考数据静态校验（master） | `python3 docs/DEPLOY/reference/tools/gen_reference.py --check --groups master` | **PASS (RC=0)** |
| 参考数据静态校验（master,demo） | `... --check --groups master,demo` | **PASS (RC=0)**；246 行、FK/多态解析 0 未解析、7 表与 `data-init.sql` 一致性 0 差异 |
| 外键两阶段顺序 | `python3 docs/DEPLOY/tools/fk-order-check.py` | **PASS (RC=0)**；100 表 / 172 唯一 FK 边 |
| 架构门禁 | `bash tools/arch-check/arch-check.sh` | **PASS (RC=0)**；E1–E9 九判据全 0 |
| 架构门禁（Maven） | `cd java && mvn -B -o validate` | **BUILD SUCCESS (RC=0)**；E1–E9 全 0 |
| Java 全量单测 | `cd java && mvn -B -o clean test` | **BUILD SUCCESS (RC=0)**；`Tests run: 1554, Failures: 0, Errors: 0, Skipped: 0`；`ReferenceDataLoadTest` 7/7 通过；新增两文件零编译警告 |
| `sqlite` 目标幂等 | `gen_reference.py --target sqlite ...` + 真实 `sqlite3` 骨架执行两遍 | 246 语句 → **246 / 246 行**，幂等成立 |
| `java-fixture` 目标 | JDK 11 `javac -Xlint:all -Werror` + `InMemoryDmEngine` 实载 | 编译通过；master=140 / 全量=246 / 重复 load / 增量补齐四类拒绝计数全 0 |
| `dm8` 目标 | 生成 + 静态核对 | 246 INSERT + 4 `SET IDENTITY_INSERT`；**实机执行仍待 DM8 实例**（U-1 承接） |

**已知既有缺陷（非本次工作引入，未修）**：`python3 docs/DEPLOY/tools/reconcile-schema.py` 返回 **RC=1 / RESULT: FAIL**，报 `t_dual_pnl_report 缺 _unrealized / 多 derivative_pnl_unrealized`。根因是 `docs/DATA_MODEL.md:3845` 把两列写成 `` `derivative_pnl_realized` / `_unrealized` `` 简写，解析器拆出 `_unrealized`，而 `docs/DEPLOY/schema/m4_acct.sql` 实际列名为 `derivative_pnl_unrealized`。`git diff -- docs/DEPLOY/schema/ docs/DATA_MODEL.md` 为空，证明本工作未触及这两个文件；`docs/DEPLOY/schema-reconcile-result.txt`（2026-10-06 基线，99 表 / 0 失败）之后由其它提交引入该漂移。按「勘误不回改历史 / 勿为措辞统一改定版制品」纪律，此处**只登记不擅改**，需另立单裁定。该脚本不属于 E1–E9 门禁，不影响上表其余判据。

## 9. 与既有文档的挂接（只追加）

- `docs/DEPLOY/README.md` **新增 §7.2「参考数据」（只追加）**：登记 `master` / `demo` 两组选装语义、三目标生成器命令、执行顺序不变、幂等、零落盘与 `ReferenceDataLoadTest` CI 判据；§7 步 6 与 §7.1 原文未改。
- `java/src/test/java/com/hedgeguard/dao/testsupport/ReferenceSeed.java`（生成式播种件，dao 命名空间满足 E5）与 `java/src/test/java/com/hedgeguard/dao/ReferenceDataLoadTest.java`（7 用例）新增，纳入 surefire。
- `data-init.sql` ② 清单状态**不回改**；`t_account_subject` 的「待 ACCT」由本目录交付后在 `README.md` 与本文件显式登记，原文保留。

## 10. 步骤台账

| 步 | 内容 | 状态 |
|----|------|------|
| 1 | `README.md` + `manifest.json`（规格） | **已交付** |
| 2 | `master/*.tsv`（14 文件 / 140 行，含补齐 `t_account_subject` 11 行模板） | **已交付**（DDL CHECK / NOT NULL / 自然键 / FK 符号引用 / 零落盘 静态校验 0 错） |
| 3 | `demo/*.tsv`（40 文件 / 106 行，一条 FLOW-001 最小闭环） | **已交付**（54 文件全量静态校验 0 错） |
| 4 | `tools/gen_reference.py`（`--check` + 三目标） | **已交付**（`--check` 两组 PASS；sqlite 幂等实测；java-fixture JDK11 编译 + InMemoryDmEngine 实载 0 拒绝；dm8 静态核对） |
| 5 | `docs/DEPLOY/README.md` §7.2 只追加登记 | **已交付**（原文未改，新增一小节） |
| 6 | §8 验证清单复跑 + 交付回执 | **已交付**（`mvn -B -o clean test` 1554/0/0/0、`mvn -B -o validate` E1–E9 全 0、`arch-check.sh` 全 0、`fk-order-check.py` PASS、`ReferenceDataLoadTest` 7/7；`reconcile-schema.py` 既有 FAIL 已登记未修） |

**未闭环项（诚实披露）**：① `dm8` 目标实机执行需 DM8 实例（U-1）；② `reconcile-schema.py` 的既有 FAIL 需另立单裁定；二者均非本工作引入，不影响已交付的参考数据可用性与门禁。

**`master/` 交付说明**：`config_item.tsv` 的 68 行由 `data-init.sql ④-3` **机械派生**（键集合逐一对齐，`default_value=config_value`，三项敏感/部署期键不播）；`account_subject.tsv` 承接 ② 残留的「待 ACCT」#4，覆盖 `ck_account_subject_biz_scene` **全部 8 个场景**，使 `AcctService.suggestSubject` 的 `findEnabledByScene` 对任一 `voucher_type` 均有可用科目（避免 `10015` 科目字典缺失）。带「示例」标注的业务取值（品种、合约、现货品类、CTP 回环前置地址）为模板建议值，企业可增删改。

**`demo/` 交付说明**：一条贯通的 `敞口 → 计划 → 审批 → 成交 → 结算 → 凭证草稿` 基线，锚在 `2026-11-20`：2 个业务子账户（`COMMODITY` 90% / `TRADE` 80%）、7 岗位用户（满足岗位分离）、三层限额（层③取哨兵 `__ALL_ACCOUNT__`）、32 个交易日现货价（满足 `CFG-06b` 最小方差法最小样本 30）、`t_exposure` 三分量全开 + `spot_category_code='__MAPPED__'`（`ck_exposure_spot_category_scope`）、套期文档与凭证均带 `pending_external_review='Y'`（`R-ACCT-10`）、凭证借贷同额同科目（对齐 `AcctService.insertBalancedLines`）。所有业务编号、金额、地址均为演示模板建议值。

---

*本文件与 `manifest.json` 由规格阶段交付；列名 / 唯一键 / FK 以 `docs/DEPLOY/schema/*.sql` 实时解析为准，行号与版本引用以实时为准。*
