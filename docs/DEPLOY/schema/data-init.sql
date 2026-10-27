-- =============================================================================
--  HedgeGuard 初始化数据脚本（M1 首批：2 行保留系统行）
--  制品编号：docs/DEPLOY/schema/data-init.sql
--  依据：docs/DATA_MODEL.md §0.5（初始化数据用独立的 data-init.sql）
--        §2.1 TB-CONFIG-1 备注（保留系统行 t_sub_account(id=0)）/ §4.3（作用域哨兵值，DC-020）
--        §2.3 TB-PRODUCT-1 备注（保留系统行 t_product(id=0)）/ §7.1 v1.3.3 + DC-041
--  前置（**执行顺序**）：m1_config_auth.sql → m1_product.sql → 本脚本
--        （第 ② 段插 t_product，故 m1_product.sql 必须先执行）。
--  执行者：DBA（应用账号禁 INSERT 之外的 DDL；本脚本仅 INSERT）。
--
--  版本说明：v2（T-009 承接，2026-10-03）——追加第 ④ 段 CONFIG 模块播种
--        （t_dict_item 2 行 / t_report_period_config 6 行 / t_config_item 68 键〔66 + T-205 增补
--          CFG-34/35 2 键，2026-10-27〕），
--        §② 表对应三行状态同步更新；第 ①/②/③ 段原文未改动。
--
--  ⚠️ 脚本纪律：不含存储过程 / 触发器 / 自定义函数（PROJECT.md §6 永久禁止）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- ① 保留系统行 t_sub_account(id = 0) —— 作用域哨兵值 0 的外键载体（DC-020）
--
-- 语义（DATA_MODEL.md §2.1 备注）：
--   · 该行**不是业务子账户**：不参与任何业务、不进入"启用子账户"列表（idx_sub_account_status 只取 ENABLED）、
--     名下无任何业务数据行，故**不影响 Σ 子账户 = 真实账户**；
--   · 作用域列（t_product_quota 层③ / t_annual_scale 全账户 / t_risk_threshold GLOBAL /
--     t_check_task / t_check_signature 的 sub_account_id）取哨兵值 0 时**外键指向本行**。
-- -----------------------------------------------------------------------------
-- DM-SPECIFIC: SET IDENTITY_INSERT —— DM 特有语法，允许向 IDENTITY 列显式插入指定值；换库需按 §6.1 D1 改写
SET IDENTITY_INSERT t_sub_account ON;

INSERT INTO t_sub_account (
    id, sub_account_code, sub_account_name, business_line_desc, status, category,
    category_approved_by, is_default, initial_fund, ledger_start_date, remark,
    disabled_reason, disable_precheck_json, archive_export_id,
    created_by, created_time, updated_by, updated_time, row_version
) VALUES (
    0, '__ALL_ACCOUNT__', '全账户（系统保留）', NULL, 'DISABLED', 'COMMODITY',
    NULL, 'N', NULL, NULL, NULL,
    '系统保留行：账户级/全账户口径的作用域哨兵值载体',
    NULL, NULL,
    0, CURRENT_TIMESTAMP, NULL, NULL, 0
);

-- DM-SPECIFIC: SET IDENTITY_INSERT —— 关闭显式插入模式
SET IDENTITY_INSERT t_sub_account OFF;

-- -----------------------------------------------------------------------------
-- ①-bis 保留系统行 t_product(id = 0) —— **未映射实货敞口**哨兵值 0 的外键载体（v1.3.3 / DC-041）
--
-- 语义（DATA_MODEL.md §2.3 TB-PRODUCT-1 备注，逐字对齐）：
--   · product_code = '__UNMAPPED__'、product_name = '未映射（系统保留）'、
--     category = 'COMMODITY'（**占位值**，不参与红线计算）、status = 'DISABLED'、
--     disabled_reason = '系统保留行：未映射实货敞口的品种哨兵值载体'；
--   · 该行**不是真实品种**：不参与任何业务、不进入品种列表（idx_product_status_category 只取 ENABLED）、
--     名下无任何业务数据行，故**不影响规模红线与套保比例的分子/分母**；
--   · t_exposure.product_id 的**未映射哨兵值 0** 外键指向本行（配合具名 CHECK ck_exposure_product_unmapped）。
--
--  ⚠️ **本行是强制前置**：t_exposure.product_id 恒 NOT NULL 且未映射行取 0（DC-041），
--     若缺本行，**实机上任何"未映射敞口"写入都会因外键失败**（FR-SPOT-14 验收 2 的"未映射清单"将无行可落）。
-- -----------------------------------------------------------------------------
-- DM-SPECIFIC: SET IDENTITY_INSERT —— DM 特有语法，允许向 IDENTITY 列显式插入指定值；换库需按 §6.1 D1 改写
SET IDENTITY_INSERT t_product ON;

INSERT INTO t_product (
    id, product_code, product_name, category, category_managed, status,
    is_domestic_exchange, currency, remark, disabled_reason,
    created_by, created_time, updated_by, updated_time, row_version
) VALUES (
    0, '__UNMAPPED__', '未映射（系统保留）', 'COMMODITY', 'Y', 'DISABLED',
    'Y', 'CNY', NULL, '系统保留行：未映射实货敞口的品种哨兵值载体',
    0, CURRENT_TIMESTAMP, NULL, NULL, 0
);

-- DM-SPECIFIC: SET IDENTITY_INSERT —— 关闭显式插入模式
SET IDENTITY_INSERT t_product OFF;

-- -----------------------------------------------------------------------------
-- ② 尚未初始化、由后续任务补齐的数据（**诚实声明，本脚本不含**）
--
--   以下清单来自 DATA_MODEL.md 各表备注，均属"初始化数据"，但**其取值域需由业务模块任务定稿**
--   （避免现在凭空造数据造成口径漂移）。请由对应模块任务投递补充脚本，届时追加到本文件末尾。
--
--   | # | 表 | 内容 | 依据 | 承接方 | 状态 |
--   |---|----|------|------|--------|------|
--   | 1 | `t_position_group` | 岗位组（前中后台归并） | TB-AUTH-5 | AUTH 模块任务 | ✅ **已交付：见第 ③ 段（T-010）** |
--   | 2 | `t_role` | 六类业务角色 + SYS_ADMIN（is_preset='Y'） | TB-AUTH-2 备注 | AUTH 模块任务 | ✅ **已交付：见第 ③ 段（T-010）** |
--   | 3 | `t_position_conflict` | 默认互斥集（两两互斥 + 资金岗/交易岗互斥） | TB-AUTH-6 备注 | AUTH 模块任务 | ✅ **已交付：见第 ③ 段（T-010）** |
--   | 4 | `t_account_subject` | 套期会计常用科目模板（is_template_default='Y'） | TB-CONFIG-5 / Q-9 | ACCT 模块任务 | 待 ACCT |
--   | 5 | `t_report_period_config` | 六类报告的周期与提醒角色 | TB-CONFIG-6 | CONFIG 模块任务 | ✅ **已交付：见第 ④ 段（T-009 承接）** |
--   | 6 | `t_dict_item` | 业务枚举字典（TRADE_REASON / UNIT_LABEL / EXPOSURE_DIRECTION 等） | TB-CONFIG-4 | CONFIG 模块任务 | ✅ **已交付：见第 ④ 段（T-009 承接）** |
--   | 7 | `t_config_item` | `ARCHITECTURE.md` §11 的全部 CFG-*（默认值 = 已确认口径） | §11 / R-CONFIG-8 | CONFIG 模块任务 | ✅ **已交付：见第 ④ 段（T-009 承接）**；CFG-34/35 2 键系 T-205 增补（2026-10-27，ARCH §11 落登待编排者转技术负责人） |
--
--   ✅ 已解决（v1.3.3 / DC-041）：`t_product` 保留系统行 `id=0` **已在本脚本第 ①-bis 段落盘**
--      （由 `#java2` 的 `question` 触发：`t_exposure.product_id = 0` 需要该行作外键目标）。
--
--   ✅ **已解决（T-010，2026-09-21）：AUTH 三项（岗位组 / 角色 / 默认互斥集）已由第 ③ 段播种**
--      （承接方 = AUTH 模块任务 = `T-010`）。
--
--   📌 岗位组编码口径澄清（原 `O-4` 待澄清项，**T-010 已确认，本段与第 ③ 段据以落地**）：
--      TB-AUTH-6 备注的默认互斥集写作 `TRADER ↔ FIN_RISK` 等；`t_position_conflict` 的
--      `group_a_id` / `group_b_id` 引用的是 `t_position_group(id)`（岗位组）。
--      **确认口径**：`t_position_group.group_code` **与角色编码同名、逐一对应**（7 组：
--      `TRADER`/`FIN_RISK`/`SETTLER`/`CASHIER`/`APPROVER`/`AUDITOR`/`SYS_ADMIN`），
--      其 `front_mid_back` 取 `AUTH.md` §2 数据字典的 **5 类归并**
--      （`FRONT 交易 / MID 风控·结算·资金 / BACK 审计 / MANAGE 管理审批 / SYSTEM 系统`）——
--      即 **`front_mid_back` = 5 类归并枚举（与 DDL CHECK 逐值一致）**，而
--      **`group_code` 为达到「交易/风控/结算三者两两互斥」所需的更细粒度**（若按 5 类粗分，
--      风控与结算同为 `MID` ⇒ 无法构造互斥对，`FR-AUTH-8` 验收 1/2 将不可达）。
--      该口径与 `AUTH.md` §2「岗位组 = 角色按前中后台归并的类别」不冲突：归并类别即 `front_mid_back`。
--      （**勘误**：原文此处曾误引 `O-2`，实为 `O-4`；`O-2` 另指"审计字段张力"条。）
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- ③ AUTH 模块任务播种（**T-010**；`FR-AUTH-6` / `FR-AUTH-7`）
--
--  依据：DATA_MODEL.md §2.2 TB-AUTH-5/TB-AUTH-2/TB-AUTH-6 备注 +
--        docs/REQUIREMENTS/AUTH.md §2 数据字典（岗位组归并 / 六类业务角色 + 系统管理员）+
--        R-AUTH-3（交易/风控/结算两两互斥；资金岗与交易岗互斥）+ R-AUTH-13（签署权与交易权互斥）。
--
--  ⚠️ 幂等：每行 INSERT 均带 `WHERE NOT EXISTS`（按自然键 group_code / role_code / (a,b) 去重），
--     重复执行不产生重复行。
--  ⚠️ 依赖顺序：先岗位组 → 再角色（引用岗位组 id）→ 再互斥矩阵（引用岗位组 id）。
--  ⚠️ 仅 INSERT，不含存储过程 / 触发器 / 自定义函数（PROJECT.md §6）。
-- -----------------------------------------------------------------------------

-- ③-1 岗位组（7 组；front_mid_back 取 5 类归并枚举）
INSERT INTO t_position_group (
    group_code, group_name, front_mid_back, is_conflict_enforced, status,
    created_by, created_time, row_version
)
SELECT src.code, src.name, src.fmb, 'Y', 'ENABLED', 0, CURRENT_TIMESTAMP, 0
FROM (
    SELECT 'TRADER' AS code, '期货交易岗' AS name, 'FRONT' AS fmb
    UNION ALL SELECT 'FIN_RISK', '财务/风控岗', 'MID'
    UNION ALL SELECT 'SETTLER', '结算岗', 'MID'
    UNION ALL SELECT 'CASHIER', '资金岗', 'MID'
    UNION ALL SELECT 'APPROVER', '管理层（审批）', 'MANAGE'
    UNION ALL SELECT 'AUDITOR', '审计/合规岗', 'BACK'
    UNION ALL SELECT 'SYS_ADMIN', '系统管理员', 'SYSTEM'
) src
WHERE NOT EXISTS (SELECT 1 FROM t_position_group g WHERE g.group_code = src.code);

-- ③-2 角色（六类业务角色 + 系统管理员；is_preset='Y'）
--     has_trading_operation 仅 TRADER 为 'Y'；系统管理员 is_business_role='N'（R-AUTH-10，禁一键通办）
INSERT INTO t_role (
    role_code, role_name, position_group_id, is_preset, is_business_role,
    has_trading_operation, status, created_by, created_time, row_version
)
SELECT src.code, src.name, g.id, 'Y', src.biz, src.trade, 'ENABLED', 0, CURRENT_TIMESTAMP, 0
FROM (
    SELECT 'TRADER' AS code, '期货交易岗' AS name, 'TRADER' AS grp, 'Y' AS biz, 'Y' AS trade
    UNION ALL SELECT 'FIN_RISK', '财务/风控岗', 'FIN_RISK', 'Y', 'N'
    UNION ALL SELECT 'SETTLER', '结算岗', 'SETTLER', 'Y', 'N'
    UNION ALL SELECT 'CASHIER', '资金岗', 'CASHIER', 'Y', 'N'
    UNION ALL SELECT 'APPROVER', '管理层（审批人）', 'APPROVER', 'Y', 'N'
    UNION ALL SELECT 'AUDITOR', '审计/合规岗', 'AUDITOR', 'Y', 'N'
    UNION ALL SELECT 'SYS_ADMIN', '系统管理员', 'SYS_ADMIN', 'N', 'N'
) src
JOIN t_position_group g ON g.group_code = src.grp
WHERE NOT EXISTS (SELECT 1 FROM t_role r WHERE r.role_code = src.code);

-- ③-3 默认互斥集（岗位组两两互斥；矩阵单向存储 a<b；is_mandatory 恒 'Y' 不可关闭，R-AUTH-3）
INSERT INTO t_position_conflict (
    group_a_id, group_b_id, conflict_reason, is_mandatory,
    created_by, created_time, row_version
)
SELECT a.id, b.id, src.reason, 'Y', 0, CURRENT_TIMESTAMP, 0
FROM (
    SELECT 'TRADER' AS ga, 'FIN_RISK' AS gb,
           '8 号文 四(一)：交易岗与风控岗不得由同一人担任' AS reason
    UNION ALL SELECT 'TRADER', 'SETTLER', '8 号文 四(一)：交易岗与结算岗不得由同一人担任'
    UNION ALL SELECT 'FIN_RISK', 'SETTLER', '8 号文 四(一)：风控岗与结算岗不得由同一人担任'
    UNION ALL SELECT 'TRADER', 'CASHIER', 'R-AUTH-3：资金岗与交易岗互斥'
    UNION ALL SELECT 'TRADER', 'APPROVER', 'R-AUTH-13 / CH-002：管理层的签署权与交易权互斥（禁操盘）'
) src
JOIN t_position_group a ON a.group_code = src.ga
JOIN t_position_group b ON b.group_code = src.gb
WHERE NOT EXISTS (
    SELECT 1 FROM t_position_conflict c WHERE c.group_a_id = a.id AND c.group_b_id = b.id
);

-- -----------------------------------------------------------------------------
-- ④ CONFIG 模块任务播种（**T-009** 承接；`FR-CONFIG-14` / `FR-CONFIG-15` / `R-CONFIG-8`）
--
--  依据：DATA_MODEL.md §2.1 TB-CONFIG-4（枚举字典）/ TB-CONFIG-2（配置项载体）/
--        TB-CONFIG-6（报告周期）+ ARCHITECTURE.md §11.1（CFG-01~18，默认值 = 已确认口径）/
--        §11.2（CFG-19~33 基础设施）/ §11.3（分类与持久化属性，persist_scope / dispatch_to_ctp 逐项核对）+
--        REQUIREMENTS/INDEX.md §9.1（R-CONFIG-8：默认值不得低于确认口径）。
--
--  ⚠️ 幂等：每段 INSERT 均带 `WHERE NOT EXISTS`（按自然键 dict_type+item_code / config_key /
--     report_type 去重），重复执行不产生重复行。
--  ⚠️ 敏感项：§11.2 中零落盘/部署时填写项**不播种**——`CFG-19`（hg.security.sensitive_fields，
--     仅内存）、`CFG-31`（hg.db.*，部署时填写、密码不落盘）、`CFG-32` 的 `hg.web.ws.port`
--     （部署时填写）不含明文凭证；其余 `hg.*` 为非敏感基础设施参数（回环地址 / 端口 / 超时）。
--  ⚠️ `t_dict_item` 粒度：**只播代码实际消费的枚举域**（当前 = `EXPOSURE_DIRECTION` 的
--     `BUY`/`SELL`，消费点 `SpotService` 与 6 张 SPOT 表的 `ck_*_direction` CHECK；
--     `TRADE_REASON` / `UNIT_LABEL` 尚无代码消费面〔前者由 `TB-TRADE-4` 结构化 CHECK 承载、
--     `t_trade_*` 表属 M2〕，不预造「将来可能用」的行，避免口径漂移）。
--  ⚠️ `t_report_period_config.remind_role_codes`：逗号分隔、均为 `t_role` 已播种角色编码
--     （`FIN_RISK`/`TRADER`/`SETTLER`/`APPROVER`/`AUDITOR`，见第 ③ 段）；提醒角色映射依据
--     `Q-14`（INDEX.md §9.1）与 `R-SETTLE-6`/`R-SETTLE-11`；`remind_advance_days` 无权威取值源，
--     取保守默认 0（到期即提醒，不提前）；`MAJOR_EVENT` 为事件触发（`CFG-26`），按每日评估
--     触发条件落 `DAY/1`，`is_mandatory='Y'`（`R-CONFIG-7` 精神：重大事项报告不得关闭）。
--  ⚠️ 仅 INSERT，不含存储过程 / 触发器 / 自定义函数（PROJECT.md §6）。
-- -----------------------------------------------------------------------------

-- ④-1 枚举字典 t_dict_item（2 行：EXPOSURE_DIRECTION BUY/SELL）
INSERT INTO t_dict_item (
    dict_type, item_code, item_name, item_desc, sort_no, status, is_system,
    created_by, created_time, row_version
)
SELECT src.dt, src.code, src.name, src.descr, src.sort, 'ENABLED', 'Y', 0, CURRENT_TIMESTAMP, 0
FROM (
    SELECT 'EXPOSURE_DIRECTION' AS dt, 'BUY' AS code, '买入' AS name,
           '买入套保方向：对应采购/原料侧实货敞口（exposure_direction=BUY，ck_*_direction 取值域）' AS descr, 1 AS sort
    UNION ALL SELECT 'EXPOSURE_DIRECTION', 'SELL', '卖出',
           '卖出套保方向：对应销售/产成品侧实货敞口（exposure_direction=SELL，ck_*_direction 取值域）', 2
) src
WHERE NOT EXISTS (
    SELECT 1 FROM t_dict_item d WHERE d.dict_type = src.dt AND d.item_code = src.code
);

-- ④-2 报告周期配置 t_report_period_config（6 行；角色映射依据 Q-14 / R-SETTLE-6 / R-SETTLE-11）
INSERT INTO t_report_period_config (
    report_type, period_unit, period_value, remind_advance_days, remind_role_codes,
    is_mandatory, is_enabled, created_by, created_time, row_version
)
SELECT src.rt, src.pu, 1, 0, src.roles, 'Y', 'Y', 0, CURRENT_TIMESTAMP, 0
FROM (
    SELECT 'DAILY_SETTLE' AS rt, 'DAY' AS pu, 'SETTLER,FIN_RISK' AS roles
    UNION ALL SELECT 'DAILY_RISK', 'DAY', 'FIN_RISK'
    UNION ALL SELECT 'MONTHLY_CHECK', 'MONTH', 'FIN_RISK,TRADER,SETTLER'
    UNION ALL SELECT 'QUARTERLY_REPORT', 'QUARTER', 'FIN_RISK,TRADER,APPROVER'
    UNION ALL SELECT 'ANNUAL_REPORT', 'YEAR', 'AUDITOR'
    UNION ALL SELECT 'MAJOR_EVENT', 'DAY', 'FIN_RISK,APPROVER,AUDITOR'
) src
WHERE NOT EXISTS (
    SELECT 1 FROM t_report_period_config r WHERE r.report_type = src.rt
);

-- ④-3 配置项 t_config_item（68 键：ARCHITECTURE.md §11.1 全部 37 键 + §11.2 可持久化 28 键
--     + CFG-26b 1 键〔T-081b 增补·R-AUDIT-5 条件①超时阈值；ARCH §11 落登待编排者转技术负责人〕
--     + CFG-34/35 2 键〔T-205 增补·L3 实盘护栏启用态 / 紧急停止开关；ARCH §11 落登挂账转技术负责人〕）
--     列映射：persist_scope / dispatch_to_ctp 按 §11.3 逐项；related_question_no = §11.1 来源 Q-n；
--     config_value = default_value = 已确认口径默认值（R-CONFIG-8：不得低于 INDEX.md §9.1）。
INSERT INTO t_config_item (
    config_key, config_category, config_value, value_type, enum_scope,
    min_value, max_value, default_value, is_sensitive, sensitive_state,
    persist_scope, dispatch_to_ctp, double_review_required, related_question_no,
    description, created_by, created_time, row_version
)
SELECT src.k, src.cat, src.val, src.vt, src.scope,
       src.lo, src.hi, src.val, 'N', NULL,
       src.ps, src.dsp, src.rev, src.qno,
       src.dscr, 0, CURRENT_TIMESTAMP, 0
FROM (
    SELECT 'hedge.scale.base' AS k, 'RISK_PARAM' AS cat, 'EXPOSURE_QTY' AS val, 'ENUM' AS vt,
           'EXPOSURE_QTY,REVENUE,PURCHASE_QTY' AS scope, NULL AS lo, NULL AS hi,
           'LOCAL_ADMIN' AS ps, 'Y' AS dsp, 'Y' AS rev, 'Q-1' AS qno,
           'CFG-01 规模红线基数口径：年度现货敞口累计量（实物量），不用营业收入' AS dscr
    UNION ALL SELECT 'hedge.scale.scope', 'RISK_PARAM', 'BOTH', 'ENUM',
           'SUB_ACCOUNT,TOTAL_ACCOUNT,BOTH', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-1',
           'CFG-02 规模红线计算范围：子账户与全账户双算、取严者'
    UNION ALL SELECT 'hedge.scale.ratio_commodity', 'RISK_PARAM', '0.90', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-2',
           'CFG-02b 商品类规模红线比例 90%'
    UNION ALL SELECT 'hedge.scale.ratio_trade', 'RISK_PARAM', '0.80', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-2',
           'CFG-02b 贸易类规模红线比例 80%'
    UNION ALL SELECT 'sub_account.category', 'SUB_ACCOUNT', 'COMMODITY', 'ENUM',
           'COMMODITY,TRADE', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-2',
           'CFG-02c 子账户分类默认商品类，允许品种级覆盖；变更风控岗发起+财务负责人审批'
    UNION ALL SELECT 'risk.term.max_months', 'RISK_PARAM', '12', 'INT',
           NULL, 1, 120,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-4',
           'CFG-03 期限红线：建仓日 + N 个月'
    UNION ALL SELECT 'risk.term.grace_days', 'RISK_PARAM', '30', 'INT',
           NULL, 0, 180,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-4',
           'CFG-03 实货合同到期宽限天数'
    UNION ALL SELECT 'risk.term.action', 'RISK_PARAM', 'WARN_THEN_BLOCK', 'ENUM',
           'WARN_THEN_BLOCK,BLOCK,WARN_AND_APPROVE', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-4',
           'CFG-03b 超期限处置：剩余<=30天告警，超期未审批阻断新开仓；不阻断平仓与展期置换'
    UNION ALL SELECT 'risk.position.check_mode', 'RISK_PARAM', 'PRE_TRADE_AND_EOD', 'ENUM',
           'PRE_TRADE_AND_EOD,ON_ORDER,EOD_ONLY', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-3',
           'CFG-04 时点净持仓校验时点：下单瞬间硬阻断+日终复核，不做盘中逐笔重算'
    UNION ALL SELECT 'exposure.snapshot.mode', 'RISK_PARAM', 'LATEST_SNAPSHOT_OF_DAY', 'ENUM',
           'LATEST_SNAPSHOT_OF_DAY,CONTRACT_REMAINING', NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', 'Q-3',
           'CFG-04b 校验用敞口时点：当日最新快照，须记录快照时点'
    UNION ALL SELECT 'risk.freq.intraday_warn', 'RISK_PARAM', '6', 'INT',
           NULL, 1, 100,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-5',
           'CFG-05 频繁开平仓：日内滚动窗开平合计告警阈值'
    UNION ALL SELECT 'risk.freq.intraday_block', 'RISK_PARAM', '10', 'INT',
           NULL, 1, 100,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-5',
           'CFG-05 频繁开平仓：日内滚动窗开平合计限制新开仓阈值'
    UNION ALL SELECT 'risk.freq.7d_rounds_warn', 'RISK_PARAM', '5', 'INT',
           NULL, 1, 100,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-5',
           'CFG-05b 7 日滚动开平回合告警阈值'
    UNION ALL SELECT 'risk.freq.min_hold_days', 'RISK_PARAM', '3', 'INT',
           NULL, 1, 60,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-5',
           'CFG-05b 平均持仓天数告警线（<3 且回合>=5 告警）'
    UNION ALL SELECT 'plan.ratio.custom.mode', 'RISK_PARAM', 'FIXED_RATIO_CAP_WITH_CONDITIONS', 'ENUM',
           'FIXED_RATIO_CAP_WITH_CONDITIONS', NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', 'Q-6',
           'CFG-06 自定义规则法：固定比例上限+适用条件，不做表达式引擎；规则实体化 TB-PLAN-8'
    UNION ALL SELECT 'plan.ratio.minvar_min_samples', 'RISK_PARAM', '30', 'INT',
           NULL, 10, 250,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-06b 最小方差法最小样本（交易日）'
    UNION ALL SELECT 'accounting.effectiveness.observers', 'RISK_PARAM', 'PRODUCT_LINKAGE,DIRECTION,TERM±3M', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-7',
           'CFG-07 有效性三项定性观察点（三项均满足即有效）'
    UNION ALL SELECT 'accounting.effectiveness.monitor_threshold', 'RISK_PARAM', '0.50', 'DECIMAL',
           NULL, 0, 5,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-7',
           'CFG-07b 量化监测指标：偏离1超±50%提示复核；仅监测不判定有效性'
    UNION ALL SELECT 'accounting.rebalance.exposure_delta_pct', 'RISK_PARAM', '0.20', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-8',
           'CFG-08 再平衡触发：敞口变动阈值（敞口侧）'
    UNION ALL SELECT 'accounting.rebalance.ratio_deviation_pct', 'RISK_PARAM', '0.20', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-8',
           'CFG-08 再平衡触发：套期比率偏离阈值（工具侧）'
    UNION ALL SELECT 'accounting.subject.template', 'DICT_SUBJECT', 'HEDGE_TEMPLATE', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-9',
           'CFG-09 科目表：含套期会计常用科目模板，企业可增删改（t_account_subject 载体）'
    UNION ALL SELECT 'accounting.voucher.mode', 'DICT_SUBJECT', 'DRAFT_ONLY', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-9',
           'CFG-09 凭证模式：草稿含科目建议，不做总账过账，标注待企业财务复核入账'
    UNION ALL SELECT 'sub_account.lifecycle', 'SUB_ACCOUNT', 'DISABLE_ONLY', 'ENUM',
           'DISABLE_ONLY', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-10',
           'CFG-10 子账户生命周期纪律：不可物理删除仅停用，历史永久只读且仍参与加总对账'
    UNION ALL SELECT 'settle.aggregation.block_scope', 'RISK_PARAM', 'DIFF_SUB_NON_CLOSE', 'ENUM',
           'DIFF_SUB_NON_CLOSE,DIFF_SUB,ALL_SUB', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-11',
           'CFG-11 加总不一致阻断范围：仅差异所属子账户新开仓/资金划拨/日终关账；不阻断平仓'
    UNION ALL SELECT 'spot.price.gap_calc', 'RISK_PARAM', 'REJECT', 'ENUM',
           'REJECT,INTERPOLATE_MAX_3D', NULL, NULL,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-12',
           'CFG-12 现货价缺口（套保比率计算用途）：拒绝计算并明确报错；插值可选默认关闭'
    UNION ALL SELECT 'spot.price.gap_valuation', 'RISK_PARAM', 'CARRY_FORWARD_LABELED', 'ENUM',
           'CARRY_FORWARD_LABELED,REJECT', NULL, NULL,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-12',
           'CFG-12b 现货价缺口（实货盈亏/敞口估值用途）：沿用最近一期但必须标注 price_as_of'
    UNION ALL SELECT 'trade.reason.mode', 'RISK_PARAM', 'STRUCTURED_MANDATORY', 'ENUM',
           'STRUCTURED_MANDATORY', NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', 'Q-13',
           'CFG-13 交易理由形式：关联类型+关联对象+时点说明三项必填，校验不通过即拒绝'
    UNION ALL SELECT 'settle.sign.roles', 'REPORT_PERIOD', 'RISK,TRADE,FINANCE', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-14',
           'CFG-14 核对任务应签角色：月度三方各自签署；季度 RISK+TRADE 后 MANAGE 签收；年度 AUDITOR'
    UNION ALL SELECT 'settle.sign.deadline_days', 'REPORT_PERIOD', '30', 'INT',
           NULL, 1, 120,
           'LOCAL_ADMIN', 'N', 'Y', 'Q-14',
           'CFG-14 签署截止：期间结束后 N 天内'
    UNION ALL SELECT 'risk.price.base', 'RISK_PARAM', 'PREV_SETTLEMENT', 'ENUM',
           'PREV_SETTLEMENT', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-15',
           'CFG-15 价格偏离基准：昨结算价（CTP 提供）；市价单不适用并标注'
    UNION ALL SELECT 'risk.price.warn_pct', 'RISK_PARAM', '0.02', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-15',
           'CFG-15 价格偏离告警线：(2%,5%] 告警+二次确认（0x1018）'
    UNION ALL SELECT 'risk.price.block_pct', 'RISK_PARAM', '0.05', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-15',
           'CFG-15 价格偏离阻断线：>5% 阻断（0x1010）；品种级可覆盖'
    UNION ALL SELECT 'risk.limit.layers', 'RISK_PARAM', 'SUB_PRODUCT,SUB_INSTRUMENT,ACCOUNT_PRODUCT', 'LIST',
           'SUB_PRODUCT,SUB_INSTRUMENT,ACCOUNT_PRODUCT', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-16',
           'CFG-16 限额三层全保留：层①主维度/层②可选/层③总账户防串用；层①不可关闭'
    UNION ALL SELECT 'exposure.components', 'RISK_PARAM', 'CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN', 'LIST',
           'CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-17',
           'CFG-17 实货敞口默认口径（组合，含生产计划）：三分量全开；快照必须存三类分量'
    UNION ALL SELECT 'exposure.component.gap_calc', 'RISK_PARAM', 'REJECT', 'ENUM',
           'REJECT,CARRY_FORWARD_LABELED', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', 'Q-17',
           'CFG-17b 敞口分量缺失/不一致：写入侧拒绝；展示侧重算+告警+留痕；比率计算一律拒绝'
    UNION ALL SELECT 'audit.chain.inflight_max_cycles', 'RISK_PARAM', '1', 'INT',
           NULL, 1, 3,
           'LOCAL_ADMIN', 'N', 'Y', NULL,
           'CFG-17c 追溯链在途时窗上限（结算周期数）：逾时 UNKNOWN+告警，再逾则 HAS_BREAK'
    UNION ALL SELECT 'spot.price.scope', 'RISK_PARAM', 'GLOBAL', 'ENUM',
           'GLOBAL', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'N', NULL,
           'CFG-18 现货价子账户口径：唯一取值 GLOBAL（CH-003 收敛）；PER_SUB_ACCOUNT 已废弃禁止启用'
    -- ---- §11.2 基础设施配置（可持久化项；CFG-19/31 与 hg.web.ws.port 不播种，见段头说明） ----
    UNION ALL SELECT 'hg.tcp.bind', 'COMPONENT_COMM', '127.0.0.1', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-20 HGCP 组件通道绑定地址：bind 白名单仅 127.0.0.1（R-CONFIG-5）'
    UNION ALL SELECT 'hg.tcp.port', 'COMPONENT_COMM', '27400', 'INT',
           NULL, 1, 65535,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-20 HGCP 组件通道端口'
    UNION ALL SELECT 'risk.margin_ratio_warn', 'RISK_PARAM', '0.70', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'Y', 'N', NULL,
           'CFG-21 风险度告警阈值'
    UNION ALL SELECT 'risk.margin_ratio_critical', 'RISK_PARAM', '0.90', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'Y', 'N', NULL,
           'CFG-21 保证金占用率严重阈值'
    UNION ALL SELECT 'risk.limit_usage_hint', 'RISK_PARAM', '0.80', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-21b 限额使用率提示线'
    UNION ALL SELECT 'risk.limit_usage_warn', 'RISK_PARAM', '0.95', 'DECIMAL',
           NULL, 0, 1,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-21b 限额使用率警告线'
    UNION ALL SELECT 'hg.ctp.env_type', 'CTP_CONN', 'SIMULATION', 'ENUM',
           'SIMULATION,PRODUCTION', NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'Y', NULL,
           'CFG-22 模拟盘/实盘显式标识；切为 PRODUCTION 须二次确认+双人复核（R-CONFIG-9）'
    UNION ALL SELECT 'hg.auth.login_fail_limit', 'SYSTEM', '5', 'INT',
           NULL, 1, 20,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-23 登录失败锁定阈值（FR-AUTH-17）'
    UNION ALL SELECT 'hg.auth.login_lock_minutes', 'SYSTEM', '5', 'INT',
           NULL, 1, 1440,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-23 登录失败锁定时长（分钟）'
    UNION ALL SELECT 'hg.auth.session_idle_minutes', 'SYSTEM', '30', 'INT',
           NULL, 5, 120,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-23b 会话空闲超时（分钟）'
    UNION ALL SELECT 'hg.auth.password_min_length', 'SYSTEM', '12', 'INT',
           NULL, 8, 64,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-23b 口令强度最小长度'
    UNION ALL SELECT 'hg.auth.password_history_size', 'SYSTEM', '5', 'INT',
           NULL, 0, 24,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-23b 口令历史重用检查深度'
    UNION ALL SELECT 'plan.expiry_remind_days', 'REPORT_PERIOD', '30', 'INT',
           NULL, 1, 180,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-24 计划到期提前提醒期'
    UNION ALL SELECT 'fund.quota.alert_levels', 'RISK_PARAM', '0.80,0.95,1.00', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-25 资金额度告警级别阈值（递增序列）'
    UNION ALL SELECT 'audit.major_event.loss_threshold', 'REPORT_PERIOD', '100000', 'INT',
           NULL, 1, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-26 重大事项触发：单笔/当日亏损阈值（元）；触发 MAJOR_EVENT 报告'
    UNION ALL SELECT 'audit.major_event.agg_inconsistency_timeout_hours', 'REPORT_PERIOD', '24', 'INT',
           NULL, 1, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-26b 重大事项触发条件①：加总对账不一致且超时未闭环阈值（小时；R-AUDIT-5 ①/T-081）'
    UNION ALL SELECT 'settle.stress.scenarios', 'REPORT_PERIOD', '-3%,-5%,-10%;+10%', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-27 压力测试情景参数'
    UNION ALL SELECT 'hg.tcp.heartbeat_ms', 'COMPONENT_COMM', '1000', 'INT',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-28 HGCP 心跳间隔（毫秒）'
    UNION ALL SELECT 'hg.cmd_timeout_ms', 'COMPONENT_COMM', '3000', 'INT',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-28 命令超时（毫秒）'
    UNION ALL SELECT 'hg.trade.confirm_timeout_ms', 'COMPONENT_COMM', '60000', 'INT',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'N', NULL,
           'CFG-28 人工确认超时（毫秒）'
    UNION ALL SELECT 'hg.flow.dir', 'COMPONENT_COMM', './data/flow', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'N', NULL,
           'CFG-29 指令流水目录（非敏感，不得存放明文凭证）'
    UNION ALL SELECT 'hg.flow.fsync_mode', 'COMPONENT_COMM', 'BATCH', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'N', NULL,
           'CFG-29 指令流水 fsync 模式'
    UNION ALL SELECT 'hg.flow.retain_days', 'COMPONENT_COMM', '90', 'INT',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'Y', 'N', NULL,
           'CFG-29 指令流水保留天数'
    UNION ALL SELECT 'hg.web.root', 'SYSTEM', './web', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-30 前端静态资源目录'
    UNION ALL SELECT 'hg.web.ws.heartbeat_ms', 'COMPONENT_COMM', '30000', 'INT',
           NULL, 1000, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-32 Java WebSocket 心跳间隔（毫秒，>=1000，RFC 6455 原生 ping/pong）'
    UNION ALL SELECT 'hg.web.ws.max_connections', 'COMPONENT_COMM', '200', 'INT',
           NULL, 1, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-32 Java WebSocket 最大连接数'
    UNION ALL SELECT 'hg.web.bind', 'COMPONENT_COMM', '127.0.0.1', 'STRING',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-33 Java 前端 HTTP 绑定地址：白名单仅 127.0.0.1，取其它值拒绝启动（FR-CONFIG-9）'
    UNION ALL SELECT 'hg.web.port', 'COMPONENT_COMM', '27402', 'INT',
           NULL, 1, 65535,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-33 Java 前端 HTTP 端口（与 hg.web.ws.port / hg.tcp.port 分离）'
    UNION ALL SELECT 'hg.web.threads', 'COMPONENT_COMM', '16', 'INT',
           NULL, 1, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-33 Java 前端 HTTP 处理线程数（取 T-003 实测实现值，可调）'
    UNION ALL SELECT 'hg.trade.fund_cap_enabled', 'RISK_PARAM', 'Y', 'BOOL',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-34 L3 资金上限护栏启用态（T-205/G-6）：Y=提交路径强制（占用≥额度即拒绝+告警留痕+不发 CTP，子账户/账户两级）；与 R-FUND-7 告警不阻断为独立语义层，R-FUND-7 告警面零改动'
    UNION ALL SELECT 'hg.trade.emergency_stop', 'RISK_PARAM', 'N', 'BOOL',
           NULL, NULL, NULL,
           'LOCAL_ADMIN', 'N', 'N', NULL,
           'CFG-35 L3 紧急停止开关（T-205/G-7）：Y=全部新提交拒绝（在途指令与既有流程不受影响，R-TRADE-13）；经既有 CONFIG 参数面切换、双人复核关（TC-M4-25 ≤1 操作停新开仓）'
) src
WHERE NOT EXISTS (
    SELECT 1 FROM t_config_item c WHERE c.config_key = src.k
);
