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
--   | # | 表 | 内容 | 依据 | 承接方 |
--   |---|----|------|------|--------|
--   | 1 | `t_position_group` | 岗位组（前中后台归并） | TB-AUTH-5 | AUTH 模块任务 |
--   | 2 | `t_role` | 六类业务角色 + SYS_ADMIN（is_preset='Y'） | TB-AUTH-2 备注 | AUTH 模块任务 |
--   | 3 | `t_position_conflict` | 默认互斥集（两两互斥 + 资金岗/交易岗互斥） | TB-AUTH-6 备注 | AUTH 模块任务 |
--   | 4 | `t_account_subject` | 套期会计常用科目模板（is_template_default='Y'） | TB-CONFIG-5 / Q-9 | ACCT 模块任务 |
--   | 5 | `t_report_period_config` | 六类报告的周期与提醒角色 | TB-CONFIG-6 | CONFIG 模块任务 |
--   | 6 | `t_dict_item` | 业务枚举字典（TRADE_REASON / UNIT_LABEL / EXPOSURE_DIRECTION 等） | TB-CONFIG-4 | CONFIG 模块任务 |
--   | 7 | `t_config_item` | `ARCHITECTURE.md` §11 的全部 CFG-*（默认值 = 已确认口径） | §11 / R-CONFIG-8 | CONFIG 模块任务 |
--
--   ✅ 已解决（v1.3.3 / DC-041）：`t_product` 保留系统行 `id=0` **已在本脚本第 ①-bis 段落盘**
--      （由 `#java2` 的 `question` 触发：`t_exposure.product_id = 0` 需要该行作外键目标）。
--
--   ⚠️ 待澄清（已按工单纪律上报，见 T-004 工单处理记录「与文档不一致登记」`O-4`）
--      （**勘误**：原文此处误引 `O-2`，实为 `O-4`；`O-2` 另指"审计字段张力"条）：
--      TB-AUTH-6 备注的默认互斥集写作 `TRADER ↔ FIN_RISK` 等，而 `t_position_conflict` 的
--      `group_a_id` / `group_b_id` 引用的是 `t_position_group(id)`（岗位组），不是角色。
--      两者编码同名但实体不同 —— 需 AUTH 模块任务确认岗位组编码集合后再落初始化数据。
-- -----------------------------------------------------------------------------
