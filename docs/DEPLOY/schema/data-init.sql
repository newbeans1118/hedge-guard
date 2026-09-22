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
--   | 5 | `t_report_period_config` | 六类报告的周期与提醒角色 | TB-CONFIG-6 | CONFIG 模块任务 | 待 CONFIG |
--   | 6 | `t_dict_item` | 业务枚举字典（TRADE_REASON / UNIT_LABEL / EXPOSURE_DIRECTION 等） | TB-CONFIG-4 | CONFIG 模块任务 | 待 CONFIG |
--   | 7 | `t_config_item` | `ARCHITECTURE.md` §11 的全部 CFG-*（默认值 = 已确认口径） | §11 / R-CONFIG-8 | CONFIG 模块任务 | 待 CONFIG |
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

