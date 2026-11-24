package com.hedgeguard.dao.testsupport;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * HedgeGuard 参考数据播种件（由 gen_reference.py 生成，勿手工编辑）。
 *
 * <p>组：master,demo；共 53 张表 / 246 行（master 140 行 / demo 106 行）。
 * 落在 dao 命名空间内以满足 E5（dao 外无 SQL）。
 *
 * <p>InMemoryDmEngine 语义：显式给全列、id 确定性分配（哨兵 0）、
 * DATE 传 LocalDate、TIMESTAMP 传 LocalDateTime（Timestamp 会 ClassCastException）、
 * row_version=1、表名大小写敏感、表级 UNIQUE 已在生成前按 natural_key 去重、
 * rollback 为 no-op 故装载测试不依赖回滚语义。
 *
 * <p>幂等：exec 先按 (table, id) 查已装载行，命中即跳过；重复 load 不产生重复行、
 * 也不产生 UNIQUE 拒绝。零落盘：password_hash 由生成器注入非凭证明文标记 __PENDING_SETUP__，
 * 真实凭据经环境变量 + AuthService API 设置。前置：调用方须已用 DDL 建好表。
 */
public final class ReferenceSeed {

    private ReferenceSeed() {
    }

    /** 装载全部选中组（master + demo），等价于 --groups master,demo。 */
    public static void load(InMemoryDmEngine engine) throws SQLException {
        loadMaster(engine);
        loadDemo(engine);
    }

    /** 只装 master（必需基准），等价于 --groups master。 */
    public static void loadMaster(InMemoryDmEngine engine) throws SQLException {
        // ---- t_sub_account (1 行) ----
        exec(engine, "t_sub_account", 0L,
                "INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, business_line_desc, status, category, category_approved_by, is_default, initial_fund, ledger_start_date, remark, disabled_reason, disable_precheck_json, archive_export_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                0L, "__ALL_ACCOUNT__", "全账户（系统保留）", null, "DISABLED", "COMMODITY", null, "N", null, null, null, "系统保留行：账户级/全账户口径的作用域哨兵值载体", null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_product (7 行) ----
        exec(engine, "t_product", 0L,
                "INSERT INTO t_product (id, product_code, product_name, category, category_managed, status, is_domestic_exchange, currency, remark, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                0L, "__UNMAPPED__", "未映射（系统保留）", "COMMODITY", "Y", "DISABLED", "Y", "CNY", null, "系统保留行：未映射实货敞口的品种哨兵值载体", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product", 1L,
                "INSERT INTO t_product (id, product_code, product_name, category, category_managed, status, is_domestic_exchange, currency, remark, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "CU", "阴极铜", "COMMODITY", "Y", "ENABLED", "Y", "CNY", "境内场内 SHFE 阴极铜（示例，企业可增删改）", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product", 2L,
                "INSERT INTO t_product (id, product_code, product_name, category, category_managed, status, is_domestic_exchange, currency, remark, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "AL", "铝", "COMMODITY", "Y", "ENABLED", "Y", "CNY", "境内场内 SHFE 铝（示例，企业可增删改）", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product", 3L,
                "INSERT INTO t_product (id, product_code, product_name, category, category_managed, status, is_domestic_exchange, currency, remark, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, "RB", "螺纹钢", "COMMODITY", "Y", "ENABLED", "Y", "CNY", "境内场内 SHFE 螺纹钢（示例，企业可增删改）", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product", 4L,
                "INSERT INTO t_product (id, product_code, product_name, category, category_managed, status, is_domestic_exchange, currency, remark, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, "M", "豆粕", "COMMODITY", "Y", "ENABLED", "Y", "CNY", "境内场内 DCE 豆粕（示例，企业可增删改）", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product", 5L,
                "INSERT INTO t_product (id, product_code, product_name, category, category_managed, status, is_domestic_exchange, currency, remark, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, "AU", "黄金", "COMMODITY", "Y", "ENABLED", "Y", "CNY", "境内场内 SHFE 黄金（示例，企业可增删改）", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product", 6L,
                "INSERT INTO t_product (id, product_code, product_name, category, category_managed, status, is_domestic_exchange, currency, remark, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, "SC", "原油", "TRADE", "Y", "ENABLED", "Y", "CNY", "境内场内 INE 原油（示例：品种级覆盖为贸易类，80% 红线）", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_position_group (7 行) ----
        exec(engine, "t_position_group", 1L,
                "INSERT INTO t_position_group (id, group_code, group_name, front_mid_back, is_conflict_enforced, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "TRADER", "期货交易岗", "FRONT", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_group", 2L,
                "INSERT INTO t_position_group (id, group_code, group_name, front_mid_back, is_conflict_enforced, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "FIN_RISK", "财务/风控岗", "MID", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_group", 3L,
                "INSERT INTO t_position_group (id, group_code, group_name, front_mid_back, is_conflict_enforced, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, "SETTLER", "结算岗", "MID", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_group", 4L,
                "INSERT INTO t_position_group (id, group_code, group_name, front_mid_back, is_conflict_enforced, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, "CASHIER", "资金岗", "MID", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_group", 5L,
                "INSERT INTO t_position_group (id, group_code, group_name, front_mid_back, is_conflict_enforced, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, "APPROVER", "管理层（审批）", "MANAGE", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_group", 6L,
                "INSERT INTO t_position_group (id, group_code, group_name, front_mid_back, is_conflict_enforced, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, "AUDITOR", "审计/合规岗", "BACK", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_group", 7L,
                "INSERT INTO t_position_group (id, group_code, group_name, front_mid_back, is_conflict_enforced, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L, "SYS_ADMIN", "系统管理员", "SYSTEM", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_role (7 行) ----
        exec(engine, "t_role", 1L,
                "INSERT INTO t_role (id, role_code, role_name, position_group_id, is_preset, is_business_role, has_trading_operation, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "TRADER", "期货交易岗", 1L, "Y", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_role", 2L,
                "INSERT INTO t_role (id, role_code, role_name, position_group_id, is_preset, is_business_role, has_trading_operation, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "FIN_RISK", "财务/风控岗", 2L, "Y", "Y", "N", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_role", 3L,
                "INSERT INTO t_role (id, role_code, role_name, position_group_id, is_preset, is_business_role, has_trading_operation, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, "SETTLER", "结算岗", 3L, "Y", "Y", "N", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_role", 4L,
                "INSERT INTO t_role (id, role_code, role_name, position_group_id, is_preset, is_business_role, has_trading_operation, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, "CASHIER", "资金岗", 4L, "Y", "Y", "N", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_role", 5L,
                "INSERT INTO t_role (id, role_code, role_name, position_group_id, is_preset, is_business_role, has_trading_operation, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, "APPROVER", "管理层（审批人）", 5L, "Y", "Y", "N", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_role", 6L,
                "INSERT INTO t_role (id, role_code, role_name, position_group_id, is_preset, is_business_role, has_trading_operation, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, "AUDITOR", "审计/合规岗", 6L, "Y", "Y", "N", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_role", 7L,
                "INSERT INTO t_role (id, role_code, role_name, position_group_id, is_preset, is_business_role, has_trading_operation, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L, "SYS_ADMIN", "系统管理员", 7L, "Y", "N", "N", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_position_conflict (5 行) ----
        exec(engine, "t_position_conflict", 1L,
                "INSERT INTO t_position_conflict (id, group_a_id, group_b_id, conflict_reason, is_mandatory, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 2L, "8 号文 四(一)：交易岗与风控岗不得由同一人担任", "Y", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_conflict", 2L,
                "INSERT INTO t_position_conflict (id, group_a_id, group_b_id, conflict_reason, is_mandatory, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 1L, 3L, "8 号文 四(一)：交易岗与结算岗不得由同一人担任", "Y", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_conflict", 3L,
                "INSERT INTO t_position_conflict (id, group_a_id, group_b_id, conflict_reason, is_mandatory, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 2L, 3L, "8 号文 四(一)：风控岗与结算岗不得由同一人担任", "Y", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_conflict", 4L,
                "INSERT INTO t_position_conflict (id, group_a_id, group_b_id, conflict_reason, is_mandatory, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, 1L, 4L, "R-AUTH-3：资金岗与交易岗互斥", "Y", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_position_conflict", 5L,
                "INSERT INTO t_position_conflict (id, group_a_id, group_b_id, conflict_reason, is_mandatory, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, 1L, 5L, "R-AUTH-13 / CH-002：管理层的签署权与交易权互斥（禁操盘）", "Y", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_role_permission (3 行) ----
        exec(engine, "t_role_permission", 1L,
                "INSERT INTO t_role_permission (id, role_id, perm_code, perm_category, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 2L, "PRODUCT:EDIT", "FUNCTION", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_role_permission", 2L,
                "INSERT INTO t_role_permission (id, role_id, perm_code, perm_category, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 2L, "CONFIG:EDIT", "FUNCTION", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_role_permission", 3L,
                "INSERT INTO t_role_permission (id, role_id, perm_code, perm_category, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 3L, "SETTLE:EDIT", "FUNCTION", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_dict_item (2 行) ----
        exec(engine, "t_dict_item", 1L,
                "INSERT INTO t_dict_item (id, dict_type, item_code, item_name, item_desc, sort_no, status, is_system, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "EXPOSURE_DIRECTION", "BUY", "买入", "买入套保方向：对应采购/原料侧实货敞口（exposure_direction=BUY，ck_*_direction 取值域）", 1L, "ENABLED", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_dict_item", 2L,
                "INSERT INTO t_dict_item (id, dict_type, item_code, item_name, item_desc, sort_no, status, is_system, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "EXPOSURE_DIRECTION", "SELL", "卖出", "卖出套保方向：对应销售/产成品侧实货敞口（exposure_direction=SELL，ck_*_direction 取值域）", 2L, "ENABLED", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_account_subject (11 行) ----
        exec(engine, "t_account_subject", 1L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "1002", "银行存款", null, 1L, "DEBIT", "MARGIN_CHANGE", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 2L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "3101", "衍生工具", null, 1L, "DEBIT", "OPEN_POSITION", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 3L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, "3201", "套期工具", null, 1L, "DEBIT", "OPEN_POSITION", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 4L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, "320101", "套期工具—期权权利金", "3201", 2L, "DEBIT", "PREMIUM", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 5L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, "3202", "被套期项目", null, 1L, "DEBIT", "HEDGE_EFFECT", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 6L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, "6101", "公允价值变动损益", null, 1L, "CREDIT", "FLOAT_PNL", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 7L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L, "6111", "投资收益", null, 1L, "CREDIT", "CLOSE_PNL", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 8L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                8L, "6401", "主营业务成本", null, 1L, "DEBIT", "CLOSE_PNL", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 9L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                9L, "6603", "财务费用", null, 1L, "DEBIT", "COMMISSION", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 10L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                10L, "660301", "财务费用—手续费", "6603", 2L, "DEBIT", "COMMISSION", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_account_subject", 11L,
                "INSERT INTO t_account_subject (id, subject_code, subject_name, parent_code, subject_level, direction, biz_scene, is_template_default, editable_by_enterprise, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                11L, "6901", "以前年度损益调整", null, 1L, "CREDIT", "OTHER", "Y", "Y", "ENABLED", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_report_period_config (6 行) ----
        exec(engine, "t_report_period_config", 1L,
                "INSERT INTO t_report_period_config (id, report_type, period_unit, period_value, remind_advance_days, remind_role_codes, is_mandatory, is_enabled, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "DAILY_SETTLE", "DAY", 1L, 0L, "SETTLER,FIN_RISK", "Y", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_report_period_config", 2L,
                "INSERT INTO t_report_period_config (id, report_type, period_unit, period_value, remind_advance_days, remind_role_codes, is_mandatory, is_enabled, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "DAILY_RISK", "DAY", 1L, 0L, "FIN_RISK", "Y", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_report_period_config", 3L,
                "INSERT INTO t_report_period_config (id, report_type, period_unit, period_value, remind_advance_days, remind_role_codes, is_mandatory, is_enabled, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, "MONTHLY_CHECK", "MONTH", 1L, 0L, "FIN_RISK,TRADER,SETTLER", "Y", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_report_period_config", 4L,
                "INSERT INTO t_report_period_config (id, report_type, period_unit, period_value, remind_advance_days, remind_role_codes, is_mandatory, is_enabled, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, "QUARTERLY_REPORT", "QUARTER", 1L, 0L, "FIN_RISK,TRADER,APPROVER", "Y", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_report_period_config", 5L,
                "INSERT INTO t_report_period_config (id, report_type, period_unit, period_value, remind_advance_days, remind_role_codes, is_mandatory, is_enabled, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, "ANNUAL_REPORT", "YEAR", 1L, 0L, "AUDITOR", "Y", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_report_period_config", 6L,
                "INSERT INTO t_report_period_config (id, report_type, period_unit, period_value, remind_advance_days, remind_role_codes, is_mandatory, is_enabled, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, "MAJOR_EVENT", "DAY", 1L, 0L, "FIN_RISK,APPROVER,AUDITOR", "Y", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_config_item (68 行) ----
        exec(engine, "t_config_item", 1L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "hedge.scale.base", "RISK_PARAM", "EXPOSURE_QTY", "ENUM", "EXPOSURE_QTY,REVENUE,PURCHASE_QTY", null, null, "EXPOSURE_QTY", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-1", 0L, "CFG-01 规模红线基数口径：年度现货敞口累计量（实物量），不用营业收入", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 2L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "hedge.scale.scope", "RISK_PARAM", "BOTH", "ENUM", "SUB_ACCOUNT,TOTAL_ACCOUNT,BOTH", null, null, "BOTH", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-1", 0L, "CFG-02 规模红线计算范围：子账户与全账户双算、取严者", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 3L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, "hedge.scale.ratio_commodity", "RISK_PARAM", "0.90", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.90", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-2", 0L, "CFG-02b 商品类规模红线比例 90%", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 4L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, "hedge.scale.ratio_trade", "RISK_PARAM", "0.80", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.80", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-2", 0L, "CFG-02b 贸易类规模红线比例 80%", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 5L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, "sub_account.category", "SUB_ACCOUNT", "COMMODITY", "ENUM", "COMMODITY,TRADE", null, null, "COMMODITY", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-2", 0L, "CFG-02c 子账户分类默认商品类，允许品种级覆盖；变更风控岗发起+财务负责人审批", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 6L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, "risk.term.max_months", "RISK_PARAM", "12", "INT", null, new BigDecimal("1"), new BigDecimal("120"), "12", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-4", 0L, "CFG-03 期限红线：建仓日 + N 个月", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 7L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L, "risk.term.grace_days", "RISK_PARAM", "30", "INT", null, new BigDecimal("0"), new BigDecimal("180"), "30", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-4", 0L, "CFG-03 实货合同到期宽限天数", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 8L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                8L, "risk.term.action", "RISK_PARAM", "WARN_THEN_BLOCK", "ENUM", "WARN_THEN_BLOCK,BLOCK,WARN_AND_APPROVE", null, null, "WARN_THEN_BLOCK", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-4", 0L, "CFG-03b 超期限处置：剩余<=30天告警，超期未审批阻断新开仓；不阻断平仓与展期置换", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 9L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                9L, "risk.position.check_mode", "RISK_PARAM", "PRE_TRADE_AND_EOD", "ENUM", "PRE_TRADE_AND_EOD,ON_ORDER,EOD_ONLY", null, null, "PRE_TRADE_AND_EOD", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-3", 0L, "CFG-04 时点净持仓校验时点：下单瞬间硬阻断+日终复核，不做盘中逐笔重算", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 10L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                10L, "exposure.snapshot.mode", "RISK_PARAM", "LATEST_SNAPSHOT_OF_DAY", "ENUM", "LATEST_SNAPSHOT_OF_DAY,CONTRACT_REMAINING", null, null, "LATEST_SNAPSHOT_OF_DAY", "N", null, "LOCAL_ADMIN", "N", "N", "Q-3", 0L, "CFG-04b 校验用敞口时点：当日最新快照，须记录快照时点", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 11L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                11L, "risk.freq.intraday_warn", "RISK_PARAM", "6", "INT", null, new BigDecimal("1"), new BigDecimal("100"), "6", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-5", 0L, "CFG-05 频繁开平仓：日内滚动窗开平合计告警阈值", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 12L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                12L, "risk.freq.intraday_block", "RISK_PARAM", "10", "INT", null, new BigDecimal("1"), new BigDecimal("100"), "10", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-5", 0L, "CFG-05 频繁开平仓：日内滚动窗开平合计限制新开仓阈值", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 13L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                13L, "risk.freq.7d_rounds_warn", "RISK_PARAM", "5", "INT", null, new BigDecimal("1"), new BigDecimal("100"), "5", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-5", 0L, "CFG-05b 7 日滚动开平回合告警阈值", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 14L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                14L, "risk.freq.min_hold_days", "RISK_PARAM", "3", "INT", null, new BigDecimal("1"), new BigDecimal("60"), "3", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-5", 0L, "CFG-05b 平均持仓天数告警线（<3 且回合>=5 告警）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 15L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                15L, "plan.ratio.custom.mode", "RISK_PARAM", "FIXED_RATIO_CAP_WITH_CONDITIONS", "ENUM", "FIXED_RATIO_CAP_WITH_CONDITIONS", null, null, "FIXED_RATIO_CAP_WITH_CONDITIONS", "N", null, "LOCAL_ADMIN", "N", "N", "Q-6", 0L, "CFG-06 自定义规则法：固定比例上限+适用条件，不做表达式引擎；规则实体化 TB-PLAN-8", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 16L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                16L, "plan.ratio.minvar_min_samples", "RISK_PARAM", "30", "INT", null, new BigDecimal("10"), new BigDecimal("250"), "30", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-06b 最小方差法最小样本（交易日）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 17L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                17L, "accounting.effectiveness.observers", "RISK_PARAM", "PRODUCT_LINKAGE,DIRECTION,TERM±3M", "STRING", null, null, null, "PRODUCT_LINKAGE,DIRECTION,TERM±3M", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-7", 0L, "CFG-07 有效性三项定性观察点（三项均满足即有效）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 18L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                18L, "accounting.effectiveness.monitor_threshold", "RISK_PARAM", "0.50", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("5"), "0.50", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-7", 0L, "CFG-07b 量化监测指标：偏离1超±50%提示复核；仅监测不判定有效性", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 19L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                19L, "accounting.rebalance.exposure_delta_pct", "RISK_PARAM", "0.20", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.20", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-8", 0L, "CFG-08 再平衡触发：敞口变动阈值（敞口侧）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 20L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                20L, "accounting.rebalance.ratio_deviation_pct", "RISK_PARAM", "0.20", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.20", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-8", 0L, "CFG-08 再平衡触发：套期比率偏离阈值（工具侧）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 21L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                21L, "accounting.subject.template", "DICT_SUBJECT", "HEDGE_TEMPLATE", "STRING", null, null, null, "HEDGE_TEMPLATE", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-9", 0L, "CFG-09 科目表：含套期会计常用科目模板，企业可增删改（t_account_subject 载体）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 22L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                22L, "accounting.voucher.mode", "DICT_SUBJECT", "DRAFT_ONLY", "STRING", null, null, null, "DRAFT_ONLY", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-9", 0L, "CFG-09 凭证模式：草稿含科目建议，不做总账过账，标注待企业财务复核入账", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 23L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                23L, "sub_account.lifecycle", "SUB_ACCOUNT", "DISABLE_ONLY", "ENUM", "DISABLE_ONLY", null, null, "DISABLE_ONLY", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-10", 0L, "CFG-10 子账户生命周期纪律：不可物理删除仅停用，历史永久只读且仍参与加总对账", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 24L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                24L, "settle.aggregation.block_scope", "RISK_PARAM", "DIFF_SUB_NON_CLOSE", "ENUM", "DIFF_SUB_NON_CLOSE,DIFF_SUB,ALL_SUB", null, null, "DIFF_SUB_NON_CLOSE", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-11", 0L, "CFG-11 加总不一致阻断范围：仅差异所属子账户新开仓/资金划拨/日终关账；不阻断平仓", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 25L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                25L, "spot.price.gap_calc", "RISK_PARAM", "REJECT", "ENUM", "REJECT,INTERPOLATE_MAX_3D", null, null, "REJECT", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-12", 0L, "CFG-12 现货价缺口（套保比率计算用途）：拒绝计算并明确报错；插值可选默认关闭", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 26L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                26L, "spot.price.gap_valuation", "RISK_PARAM", "CARRY_FORWARD_LABELED", "ENUM", "CARRY_FORWARD_LABELED,REJECT", null, null, "CARRY_FORWARD_LABELED", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-12", 0L, "CFG-12b 现货价缺口（实货盈亏/敞口估值用途）：沿用最近一期但必须标注 price_as_of", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 27L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                27L, "trade.reason.mode", "RISK_PARAM", "STRUCTURED_MANDATORY", "ENUM", "STRUCTURED_MANDATORY", null, null, "STRUCTURED_MANDATORY", "N", null, "LOCAL_ADMIN", "N", "N", "Q-13", 0L, "CFG-13 交易理由形式：关联类型+关联对象+时点说明三项必填，校验不通过即拒绝", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 28L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                28L, "settle.sign.roles", "REPORT_PERIOD", "RISK,TRADE,FINANCE", "STRING", null, null, null, "RISK,TRADE,FINANCE", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-14", 0L, "CFG-14 核对任务应签角色：月度三方各自签署；季度 RISK+TRADE 后 MANAGE 签收；年度 AUDITOR", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 29L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                29L, "settle.sign.deadline_days", "REPORT_PERIOD", "30", "INT", null, new BigDecimal("1"), new BigDecimal("120"), "30", "N", null, "LOCAL_ADMIN", "N", "Y", "Q-14", 0L, "CFG-14 签署截止：期间结束后 N 天内", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 30L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                30L, "risk.price.base", "RISK_PARAM", "PREV_SETTLEMENT", "ENUM", "PREV_SETTLEMENT", null, null, "PREV_SETTLEMENT", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-15", 0L, "CFG-15 价格偏离基准：昨结算价（CTP 提供）；市价单不适用并标注", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 31L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                31L, "risk.price.warn_pct", "RISK_PARAM", "0.02", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.02", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-15", 0L, "CFG-15 价格偏离告警线：(2%,5%] 告警+二次确认（0x1018）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 32L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                32L, "risk.price.block_pct", "RISK_PARAM", "0.05", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.05", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-15", 0L, "CFG-15 价格偏离阻断线：>5% 阻断（0x1010）；品种级可覆盖", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 33L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                33L, "risk.limit.layers", "RISK_PARAM", "SUB_PRODUCT,SUB_INSTRUMENT,ACCOUNT_PRODUCT", "LIST", "SUB_PRODUCT,SUB_INSTRUMENT,ACCOUNT_PRODUCT", null, null, "SUB_PRODUCT,SUB_INSTRUMENT,ACCOUNT_PRODUCT", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-16", 0L, "CFG-16 限额三层全保留：层①主维度/层②可选/层③总账户防串用；层①不可关闭", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 34L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                34L, "exposure.components", "RISK_PARAM", "CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN", "LIST", "CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN", null, null, "CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-17", 0L, "CFG-17 实货敞口默认口径（组合，含生产计划）：三分量全开；快照必须存三类分量", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 35L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                35L, "exposure.component.gap_calc", "RISK_PARAM", "REJECT", "ENUM", "REJECT,CARRY_FORWARD_LABELED", null, null, "REJECT", "N", null, "LOCAL_ADMIN", "Y", "Y", "Q-17", 0L, "CFG-17b 敞口分量缺失/不一致：写入侧拒绝；展示侧重算+告警+留痕；比率计算一律拒绝", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 36L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                36L, "audit.chain.inflight_max_cycles", "RISK_PARAM", "1", "INT", null, new BigDecimal("1"), new BigDecimal("3"), "1", "N", null, "LOCAL_ADMIN", "N", "Y", null, 0L, "CFG-17c 追溯链在途时窗上限（结算周期数）：逾时 UNKNOWN+告警，再逾则 HAS_BREAK", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 37L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                37L, "spot.price.scope", "RISK_PARAM", "GLOBAL", "ENUM", "GLOBAL", null, null, "GLOBAL", "N", null, "LOCAL_ADMIN", "Y", "N", null, 0L, "CFG-18 现货价子账户口径：唯一取值 GLOBAL（CH-003 收敛）；PER_SUB_ACCOUNT 已废弃禁止启用", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 38L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                38L, "hg.tcp.bind", "COMPONENT_COMM", "127.0.0.1", "STRING", null, null, null, "127.0.0.1", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-20 HGCP 组件通道绑定地址：bind 白名单仅 127.0.0.1（R-CONFIG-5）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 39L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                39L, "hg.tcp.port", "COMPONENT_COMM", "39000", "INT", null, new BigDecimal("1"), new BigDecimal("65535"), "39000", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-20 HGCP 组件通道端口", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 40L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                40L, "risk.margin_ratio_warn", "RISK_PARAM", "0.70", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.70", "N", null, "LOCAL_ADMIN", "Y", "N", null, 0L, "CFG-21 风险度告警阈值", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 41L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                41L, "risk.margin_ratio_critical", "RISK_PARAM", "0.90", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.90", "N", null, "LOCAL_ADMIN", "Y", "N", null, 0L, "CFG-21 保证金占用率严重阈值", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 42L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                42L, "risk.limit_usage_hint", "RISK_PARAM", "0.80", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.80", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-21b 限额使用率提示线", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 43L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                43L, "risk.limit_usage_warn", "RISK_PARAM", "0.95", "DECIMAL", null, new BigDecimal("0"), new BigDecimal("1"), "0.95", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-21b 限额使用率警告线", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 44L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                44L, "hg.ctp.env_type", "CTP_CONN", "SIMULATION", "ENUM", "SIMULATION,PRODUCTION", null, null, "SIMULATION", "N", null, "LOCAL_ADMIN", "Y", "Y", null, 0L, "CFG-22 模拟盘/实盘显式标识；切为 PRODUCTION 须二次确认+双人复核（R-CONFIG-9）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 45L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                45L, "hg.auth.login_fail_limit", "SYSTEM", "5", "INT", null, new BigDecimal("1"), new BigDecimal("20"), "5", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-23 登录失败锁定阈值（FR-AUTH-17）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 46L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                46L, "hg.auth.login_lock_minutes", "SYSTEM", "5", "INT", null, new BigDecimal("1"), new BigDecimal("1440"), "5", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-23 登录失败锁定时长（分钟）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 47L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                47L, "hg.auth.session_idle_minutes", "SYSTEM", "30", "INT", null, new BigDecimal("5"), new BigDecimal("120"), "30", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-23b 会话空闲超时（分钟）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 48L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                48L, "hg.auth.password_min_length", "SYSTEM", "12", "INT", null, new BigDecimal("8"), new BigDecimal("64"), "12", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-23b 口令强度最小长度", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 49L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                49L, "hg.auth.password_history_size", "SYSTEM", "5", "INT", null, new BigDecimal("0"), new BigDecimal("24"), "5", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-23b 口令历史重用检查深度", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 50L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                50L, "plan.expiry_remind_days", "REPORT_PERIOD", "30", "INT", null, new BigDecimal("1"), new BigDecimal("180"), "30", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-24 计划到期提前提醒期", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 51L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                51L, "fund.quota.alert_levels", "RISK_PARAM", "0.80,0.95,1.00", "STRING", null, null, null, "0.80,0.95,1.00", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-25 资金额度告警级别阈值（递增序列）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 52L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                52L, "audit.major_event.loss_threshold", "REPORT_PERIOD", "100000", "INT", null, new BigDecimal("1"), null, "100000", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-26 重大事项触发：单笔/当日亏损阈值（元）；触发 MAJOR_EVENT 报告", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 53L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                53L, "audit.major_event.agg_inconsistency_timeout_hours", "REPORT_PERIOD", "24", "INT", null, new BigDecimal("1"), null, "24", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-26b 重大事项触发条件①：加总对账不一致且超时未闭环阈值（小时；R-AUDIT-5 ①/T-081）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 54L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                54L, "settle.stress.scenarios", "REPORT_PERIOD", "-3%,-5%,-10%;+10%", "STRING", null, null, null, "-3%,-5%,-10%;+10%", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-27 压力测试情景参数", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 55L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                55L, "hg.tcp.heartbeat_ms", "COMPONENT_COMM", "1000", "INT", null, null, null, "1000", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-28 HGCP 心跳间隔（毫秒）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 56L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                56L, "hg.cmd_timeout_ms", "COMPONENT_COMM", "3000", "INT", null, null, null, "3000", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-28 命令超时（毫秒）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 57L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                57L, "hg.trade.confirm_timeout_ms", "COMPONENT_COMM", "60000", "INT", null, null, null, "60000", "N", null, "LOCAL_ADMIN", "Y", "N", null, 0L, "CFG-28 人工确认超时（毫秒）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 58L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                58L, "hg.flow.dir", "COMPONENT_COMM", "./data/flow", "STRING", null, null, null, "./data/flow", "N", null, "LOCAL_ADMIN", "Y", "N", null, 0L, "CFG-29 指令流水目录（非敏感，不得存放明文凭证）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 59L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                59L, "hg.flow.fsync_mode", "COMPONENT_COMM", "BATCH", "STRING", null, null, null, "BATCH", "N", null, "LOCAL_ADMIN", "Y", "N", null, 0L, "CFG-29 指令流水 fsync 模式", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 60L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                60L, "hg.flow.retain_days", "COMPONENT_COMM", "90", "INT", null, null, null, "90", "N", null, "LOCAL_ADMIN", "Y", "N", null, 0L, "CFG-29 指令流水保留天数", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 61L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                61L, "hg.web.root", "SYSTEM", "./web", "STRING", null, null, null, "./web", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-30 前端静态资源目录", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 62L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                62L, "hg.web.ws.heartbeat_ms", "COMPONENT_COMM", "30000", "INT", null, new BigDecimal("1000"), null, "30000", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-32 Java WebSocket 心跳间隔（毫秒，>=1000，RFC 6455 原生 ping/pong）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 63L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                63L, "hg.web.ws.max_connections", "COMPONENT_COMM", "200", "INT", null, new BigDecimal("1"), null, "200", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-32 Java WebSocket 最大连接数", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 64L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                64L, "hg.web.bind", "COMPONENT_COMM", "0.0.0.0", "STRING", null, null, null, "0.0.0.0", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-33 Java 前端 HTTP 绑定地址：CH-026 后默认 0.0.0.0 可接受外部流量；组件通道 hg.tcp.bind 仍白名单仅 127.0.0.1", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 65L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                65L, "hg.web.port", "COMPONENT_COMM", "80", "INT", null, new BigDecimal("1"), new BigDecimal("65535"), "80", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-33 Java 前端 HTTP 端口（与 hg.web.ws.port / hg.tcp.port 分离）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 66L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                66L, "hg.web.threads", "COMPONENT_COMM", "16", "INT", null, new BigDecimal("1"), null, "16", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-33 Java 前端 HTTP 处理线程数（取 T-003 实测实现值，可调）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 67L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                67L, "hg.trade.fund_cap_enabled", "RISK_PARAM", "Y", "BOOL", null, null, null, "Y", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-34 L3 资金上限护栏启用态（T-205/G-6）：Y=提交路径强制（占用≥额度即拒绝+告警留痕+不发 CTP，子账户/账户两级）；与 R-FUND-7 告警不阻断为独立语义层，R-FUND-7 告警面零改动", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_config_item", 68L,
                "INSERT INTO t_config_item (id, config_key, config_category, config_value, value_type, enum_scope, min_value, max_value, default_value, is_sensitive, sensitive_state, persist_scope, dispatch_to_ctp, double_review_required, related_question_no, effective_version, description, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                68L, "hg.trade.emergency_stop", "RISK_PARAM", "N", "BOOL", null, null, null, "N", "N", null, "LOCAL_ADMIN", "N", "N", null, 0L, "CFG-35 L3 紧急停止开关（T-205/G-7）：Y=全部新提交拒绝（在途指令与既有流程不受影响，R-TRADE-13）；经既有 CONFIG 参数面切换、双人复核关（TC-M4-25 ≤1 操作停新开仓）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_ctp_front_config (2 行) ----
        exec(engine, "t_ctp_front_config", 1L,
                "INSERT INTO t_ctp_front_config (id, env_type, front_type, front_addr, priority, broker_id, investor_id, connect_timeout_ms, reconnect_interval_ms, flow_path, is_enabled, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "SIMULATION", "TRADE", "tcp://127.0.0.1:30001", 1L, "SIM_BROKER", "SIM_INVESTOR", 5000L, 3000L, "./data/flow", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_ctp_front_config", 2L,
                "INSERT INTO t_ctp_front_config (id, env_type, front_type, front_addr, priority, broker_id, investor_id, connect_timeout_ms, reconnect_interval_ms, flow_path, is_enabled, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "SIMULATION", "MD", "tcp://127.0.0.1:30011", 1L, "SIM_BROKER", "SIM_INVESTOR", 5000L, 3000L, "./data/flow", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_product_unit (6 行) ----
        exec(engine, "t_product_unit", 1L,
                "INSERT INTO t_product_unit (id, product_id, spot_unit, futures_unit, convert_factor, convert_desc, status, effective_from, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "TON", "LOT", new BigDecimal("5"), "1 手 = 5 吨", "ENABLED", LocalDate.parse("2026-01-01"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_unit", 2L,
                "INSERT INTO t_product_unit (id, product_id, spot_unit, futures_unit, convert_factor, convert_desc, status, effective_from, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 2L, "TON", "LOT", new BigDecimal("5"), "1 手 = 5 吨", "ENABLED", LocalDate.parse("2026-01-01"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_unit", 3L,
                "INSERT INTO t_product_unit (id, product_id, spot_unit, futures_unit, convert_factor, convert_desc, status, effective_from, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 3L, "TON", "LOT", new BigDecimal("10"), "1 手 = 10 吨", "ENABLED", LocalDate.parse("2026-01-01"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_unit", 4L,
                "INSERT INTO t_product_unit (id, product_id, spot_unit, futures_unit, convert_factor, convert_desc, status, effective_from, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, 4L, "TON", "LOT", new BigDecimal("10"), "1 手 = 10 吨", "ENABLED", LocalDate.parse("2026-01-01"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_unit", 5L,
                "INSERT INTO t_product_unit (id, product_id, spot_unit, futures_unit, convert_factor, convert_desc, status, effective_from, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, 5L, "KG", "LOT", new BigDecimal("1"), "1 手 = 1 千克（1000 克）", "ENABLED", LocalDate.parse("2026-01-01"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_unit", 6L,
                "INSERT INTO t_product_unit (id, product_id, spot_unit, futures_unit, convert_factor, convert_desc, status, effective_from, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, 6L, "BARREL", "LOT", new BigDecimal("1000"), "1 手 = 1000 桶", "ENABLED", LocalDate.parse("2026-01-01"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_product_contract (7 行) ----
        exec(engine, "t_product_contract", 1L,
                "INSERT INTO t_product_contract (id, product_id, instrument_id, instrument_type, exchange_code, contract_multiplier, expiry_date, underlying_instrument_id, strike_price, option_type, status, is_domestic_exchange, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "cu2612", "FUTURES", "SHFE", new BigDecimal("5"), LocalDate.parse("2026-12-15"), null, null, null, "ENABLED", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_contract", 2L,
                "INSERT INTO t_product_contract (id, product_id, instrument_id, instrument_type, exchange_code, contract_multiplier, expiry_date, underlying_instrument_id, strike_price, option_type, status, is_domestic_exchange, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 2L, "al2612", "FUTURES", "SHFE", new BigDecimal("5"), LocalDate.parse("2026-12-15"), null, null, null, "ENABLED", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_contract", 3L,
                "INSERT INTO t_product_contract (id, product_id, instrument_id, instrument_type, exchange_code, contract_multiplier, expiry_date, underlying_instrument_id, strike_price, option_type, status, is_domestic_exchange, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 3L, "rb2612", "FUTURES", "SHFE", new BigDecimal("10"), LocalDate.parse("2026-12-15"), null, null, null, "ENABLED", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_contract", 4L,
                "INSERT INTO t_product_contract (id, product_id, instrument_id, instrument_type, exchange_code, contract_multiplier, expiry_date, underlying_instrument_id, strike_price, option_type, status, is_domestic_exchange, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, 4L, "m2701", "FUTURES", "DCE", new BigDecimal("10"), LocalDate.parse("2027-01-15"), null, null, null, "ENABLED", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_contract", 5L,
                "INSERT INTO t_product_contract (id, product_id, instrument_id, instrument_type, exchange_code, contract_multiplier, expiry_date, underlying_instrument_id, strike_price, option_type, status, is_domestic_exchange, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, 5L, "au2612", "FUTURES", "SHFE", new BigDecimal("1"), LocalDate.parse("2026-12-15"), null, null, null, "ENABLED", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_contract", 6L,
                "INSERT INTO t_product_contract (id, product_id, instrument_id, instrument_type, exchange_code, contract_multiplier, expiry_date, underlying_instrument_id, strike_price, option_type, status, is_domestic_exchange, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, 6L, "sc2701", "FUTURES", "INE", new BigDecimal("1000"), LocalDate.parse("2027-01-15"), null, null, null, "ENABLED", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_contract", 7L,
                "INSERT INTO t_product_contract (id, product_id, instrument_id, instrument_type, exchange_code, contract_multiplier, expiry_date, underlying_instrument_id, strike_price, option_type, status, is_domestic_exchange, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L, 1L, "cu2612C70000", "OPTION", "SHFE", new BigDecimal("5"), LocalDate.parse("2026-11-25"), "cu2612", new BigDecimal("70000"), "CALL", "ENABLED", "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_product_spot_category (8 行) ----
        exec(engine, "t_product_spot_category", 1L,
                "INSERT INTO t_product_spot_category (id, spot_category_code, spot_category_name, product_id, mapping_direction, is_primary, status, change_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "SPOT-CU-CATHODE", "阴极铜现货", 1L, "BOTH", "Y", "ENABLED", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_spot_category", 2L,
                "INSERT INTO t_product_spot_category (id, spot_category_code, spot_category_name, product_id, mapping_direction, is_primary, status, change_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "SPOT-AL-INGOT", "铝锭现货", 2L, "BOTH", "Y", "ENABLED", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_spot_category", 3L,
                "INSERT INTO t_product_spot_category (id, spot_category_code, spot_category_name, product_id, mapping_direction, is_primary, status, change_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, "SPOT-RB-REBAR", "螺纹钢现货", 3L, "BOTH", "Y", "ENABLED", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_spot_category", 4L,
                "INSERT INTO t_product_spot_category (id, spot_category_code, spot_category_name, product_id, mapping_direction, is_primary, status, change_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, "SPOT-M-SOYMEAL", "豆粕现货", 4L, "BOTH", "Y", "ENABLED", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_spot_category", 5L,
                "INSERT INTO t_product_spot_category (id, spot_category_code, spot_category_name, product_id, mapping_direction, is_primary, status, change_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, "SPOT-AU-GOLD", "黄金现货", 5L, "BOTH", "Y", "ENABLED", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_spot_category", 6L,
                "INSERT INTO t_product_spot_category (id, spot_category_code, spot_category_name, product_id, mapping_direction, is_primary, status, change_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, "SPOT-SC-CRUDE", "原油现货", 6L, "BOTH", "Y", "ENABLED", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_spot_category", 7L,
                "INSERT INTO t_product_spot_category (id, spot_category_code, spot_category_name, product_id, mapping_direction, is_primary, status, change_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L, "SPOT-NONFERROUS", "有色金属现货", 1L, "PURCHASE", "Y", "ENABLED", "示例：一个现货品类映射到两个品种时指定主映射", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_spot_category", 8L,
                "INSERT INTO t_product_spot_category (id, spot_category_code, spot_category_name, product_id, mapping_direction, is_primary, status, change_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                8L, "SPOT-NONFERROUS", "有色金属现货", 2L, "PURCHASE", "N", "ENABLED", "示例：非主映射", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
    }

    /** 只装 demo（可叠加在 loadMaster 之后），不构成独立的合法初始化。 */
    private static void loadDemo(InMemoryDmEngine engine) throws SQLException {
        // ---- t_sub_account (2 行) ----
        exec(engine, "t_sub_account", 1L,
                "INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, business_line_desc, status, category, category_approved_by, is_default, initial_fund, ledger_start_date, remark, disabled_reason, disable_precheck_json, archive_export_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "SA-COMMODITY", "商品类子账户A", "境内商品期货套保（示例）", "ENABLED", "COMMODITY", null, "Y", new BigDecimal("10000000.0000"), LocalDate.parse("2026-01-01"), "示例业务子账户A，企业可增删改", null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_sub_account", 2L,
                "INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, business_line_desc, status, category, category_approved_by, is_default, initial_fund, ledger_start_date, remark, disabled_reason, disable_precheck_json, archive_export_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "SA-TRADE", "贸易类子账户B", "现货贸易配套套保（示例）", "ENABLED", "TRADE", null, "N", new BigDecimal("5000000.0000"), LocalDate.parse("2026-01-01"), "示例业务子账户B，企业可增删改", null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_user (7 行) ----
        exec(engine, "t_user", 1L,
                "INSERT INTO t_user (id, username, real_name, natural_person_key, department, is_enterprise_leader, leader_flag_reviewer_id, status, password_hash, must_change_password, password_updated_time, fail_count, locked_until, last_login_time, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "trader01", "张交易", "NP-001", "交易部", "N", null, "ENABLED", "__PENDING_SETUP__", "Y", null, 0L, null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user", 2L,
                "INSERT INTO t_user (id, username, real_name, natural_person_key, department, is_enterprise_leader, leader_flag_reviewer_id, status, password_hash, must_change_password, password_updated_time, fail_count, locked_until, last_login_time, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, "finrisk01", "李风控", "NP-002", "财务/风控部", "N", null, "ENABLED", "__PENDING_SETUP__", "Y", null, 0L, null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user", 3L,
                "INSERT INTO t_user (id, username, real_name, natural_person_key, department, is_enterprise_leader, leader_flag_reviewer_id, status, password_hash, must_change_password, password_updated_time, fail_count, locked_until, last_login_time, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, "settler01", "王结算", "NP-003", "结算部", "N", null, "ENABLED", "__PENDING_SETUP__", "Y", null, 0L, null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user", 4L,
                "INSERT INTO t_user (id, username, real_name, natural_person_key, department, is_enterprise_leader, leader_flag_reviewer_id, status, password_hash, must_change_password, password_updated_time, fail_count, locked_until, last_login_time, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, "cashier01", "赵资金", "NP-004", "资金部", "N", null, "ENABLED", "__PENDING_SETUP__", "Y", null, 0L, null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user", 5L,
                "INSERT INTO t_user (id, username, real_name, natural_person_key, department, is_enterprise_leader, leader_flag_reviewer_id, status, password_hash, must_change_password, password_updated_time, fail_count, locked_until, last_login_time, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, "approver01", "陈管理", "NP-005", "管理层", "Y", 6L, "ENABLED", "__PENDING_SETUP__", "Y", null, 0L, null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user", 6L,
                "INSERT INTO t_user (id, username, real_name, natural_person_key, department, is_enterprise_leader, leader_flag_reviewer_id, status, password_hash, must_change_password, password_updated_time, fail_count, locked_until, last_login_time, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, "auditor01", "周审计", "NP-006", "审计/合规部", "N", null, "ENABLED", "__PENDING_SETUP__", "Y", null, 0L, null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user", 7L,
                "INSERT INTO t_user (id, username, real_name, natural_person_key, department, is_enterprise_leader, leader_flag_reviewer_id, status, password_hash, must_change_password, password_updated_time, fail_count, locked_until, last_login_time, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L, "sysadmin01", "系统管理员", "NP-007", "信息技术部", "N", null, "ENABLED", "__PENDING_SETUP__", "Y", null, 0L, null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_user_role (7 行) ----
        exec(engine, "t_user_role", 1L,
                "INSERT INTO t_user_role (id, user_id, role_id, granted_by, granted_time, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, 7L, LocalDateTime.parse("2026-01-01T09:00:00"), 6L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user_role", 2L,
                "INSERT INTO t_user_role (id, user_id, role_id, granted_by, granted_time, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 2L, 2L, 7L, LocalDateTime.parse("2026-01-01T09:00:00"), 6L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user_role", 3L,
                "INSERT INTO t_user_role (id, user_id, role_id, granted_by, granted_time, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 3L, 3L, 7L, LocalDateTime.parse("2026-01-01T09:00:00"), 6L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user_role", 4L,
                "INSERT INTO t_user_role (id, user_id, role_id, granted_by, granted_time, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, 4L, 4L, 7L, LocalDateTime.parse("2026-01-01T09:00:00"), 6L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user_role", 5L,
                "INSERT INTO t_user_role (id, user_id, role_id, granted_by, granted_time, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, 5L, 5L, 7L, LocalDateTime.parse("2026-01-01T09:00:00"), 6L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user_role", 6L,
                "INSERT INTO t_user_role (id, user_id, role_id, granted_by, granted_time, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, 6L, 6L, 7L, LocalDateTime.parse("2026-01-01T09:00:00"), 7L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user_role", 7L,
                "INSERT INTO t_user_role (id, user_id, role_id, granted_by, granted_time, reviewer_id, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L, 7L, 7L, 7L, LocalDateTime.parse("2026-01-01T09:00:00"), 6L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_user_sub_account_perm (4 行) ----
        exec(engine, "t_user_sub_account_perm", 1L,
                "INSERT INTO t_user_sub_account_perm (id, user_id, sub_account_id, perm_type, granted_by, reviewer_id, effective_from, effective_to, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, "LIMITED", 7L, 6L, LocalDate.parse("2026-01-01"), null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user_sub_account_perm", 2L,
                "INSERT INTO t_user_sub_account_perm (id, user_id, sub_account_id, perm_type, granted_by, reviewer_id, effective_from, effective_to, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 2L, 1L, "CROSS_READONLY", 7L, 6L, LocalDate.parse("2026-01-01"), null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user_sub_account_perm", 3L,
                "INSERT INTO t_user_sub_account_perm (id, user_id, sub_account_id, perm_type, granted_by, reviewer_id, effective_from, effective_to, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 3L, 1L, "LIMITED", 7L, 6L, LocalDate.parse("2026-01-01"), null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_user_sub_account_perm", 4L,
                "INSERT INTO t_user_sub_account_perm (id, user_id, sub_account_id, perm_type, granted_by, reviewer_id, effective_from, effective_to, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, 1L, 2L, "LIMITED", 7L, 6L, LocalDate.parse("2026-01-01"), null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_authorization (2 行) ----
        exec(engine, "t_authorization", 1L,
                "INSERT INTO t_authorization (id, user_id, sub_account_id, product_id, quota_limit, effective_from, effective_to, status, applicant_id, reviewer_id, dispatch_status, dispatch_version, reject_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, 1L, new BigDecimal("1000.0000"), LocalDate.parse("2026-01-01"), null, "EFFECTIVE", 1L, 2L, "SUCCESS", 1L, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_authorization", 2L,
                "INSERT INTO t_authorization (id, user_id, sub_account_id, product_id, quota_limit, effective_from, effective_to, status, applicant_id, reviewer_id, dispatch_status, dispatch_version, reject_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 1L, 2L, 6L, new BigDecimal("500.0000"), LocalDate.parse("2026-01-01"), null, "EFFECTIVE", 1L, 2L, "SUCCESS", 1L, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_product_quota (3 行) ----
        exec(engine, "t_product_quota", 1L,
                "INSERT INTO t_product_quota (id, sub_account_id, limit_layer, product_id, quota_limit, quota_unit, used_qty, frozen_qty, effective_from, effective_to, dispatch_status, dispatch_version, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "SUB_PRODUCT", 1L, new BigDecimal("1000.0000"), "TON", new BigDecimal("180.0000"), new BigDecimal("0.0000"), LocalDate.parse("2026-01-01"), null, "SUCCESS", 1L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_quota", 2L,
                "INSERT INTO t_product_quota (id, sub_account_id, limit_layer, product_id, quota_limit, quota_unit, used_qty, frozen_qty, effective_from, effective_to, dispatch_status, dispatch_version, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 2L, "SUB_PRODUCT", 6L, new BigDecimal("500.0000"), "TON", new BigDecimal("0.0000"), new BigDecimal("0.0000"), LocalDate.parse("2026-01-01"), null, "SUCCESS", 1L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_product_quota", 3L,
                "INSERT INTO t_product_quota (id, sub_account_id, limit_layer, product_id, quota_limit, quota_unit, used_qty, frozen_qty, effective_from, effective_to, dispatch_status, dispatch_version, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 0L, "ACCOUNT_PRODUCT", 1L, new BigDecimal("2000.0000"), "TON", new BigDecimal("180.0000"), new BigDecimal("0.0000"), LocalDate.parse("2026-01-01"), null, "SUCCESS", 1L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_sub_product_whitelist (3 行) ----
        exec(engine, "t_sub_product_whitelist", 1L,
                "INSERT INTO t_sub_product_whitelist (id, sub_account_id, product_id, category_override, status, dispatch_status, dispatch_version, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, null, "ENABLED", "SUCCESS", 1L, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_sub_product_whitelist", 2L,
                "INSERT INTO t_sub_product_whitelist (id, sub_account_id, product_id, category_override, status, dispatch_status, dispatch_version, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 1L, 2L, null, "ENABLED", "SUCCESS", 1L, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_sub_product_whitelist", 3L,
                "INSERT INTO t_sub_product_whitelist (id, sub_account_id, product_id, category_override, status, dispatch_status, dispatch_version, disabled_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 2L, 6L, null, "ENABLED", "SUCCESS", 1L, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_sub_instrument_quota (2 行) ----
        exec(engine, "t_sub_instrument_quota", 1L,
                "INSERT INTO t_sub_instrument_quota (id, sub_account_id, instrument_id, product_id, quota_limit, quota_unit, used_qty, frozen_qty, effective_from, effective_to, dispatch_status, dispatch_version, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "cu2612", 1L, new BigDecimal("500.0000"), "LOT", new BigDecimal("180.0000"), new BigDecimal("0.0000"), LocalDate.parse("2026-01-01"), null, "SUCCESS", 1L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_sub_instrument_quota", 2L,
                "INSERT INTO t_sub_instrument_quota (id, sub_account_id, instrument_id, product_id, quota_limit, quota_unit, used_qty, frozen_qty, effective_from, effective_to, dispatch_status, dispatch_version, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 2L, "sc2701", 6L, new BigDecimal("200.0000"), "LOT", new BigDecimal("0.0000"), new BigDecimal("0.0000"), LocalDate.parse("2026-01-01"), null, "SUCCESS", 1L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_fund_quota (2 行) ----
        exec(engine, "t_fund_quota", 1L,
                "INSERT INTO t_fund_quota (id, sub_account_id, quota_scope, quota_value, effective_from, effective_to, operator_id, change_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "SUB_ACCOUNT", new BigDecimal("10000000.0000"), LocalDate.parse("2026-01-01"), null, 7L, "初始化资金额度（示例）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_fund_quota", 2L,
                "INSERT INTO t_fund_quota (id, sub_account_id, quota_scope, quota_value, effective_from, effective_to, operator_id, change_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 0L, "ACCOUNT", new BigDecimal("50000000.0000"), LocalDate.parse("2026-01-01"), null, 7L, "账户级资金额度（示例）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_annual_scale (3 行) ----
        exec(engine, "t_annual_scale", 1L,
                "INSERT INTO t_annual_scale (id, sub_account_id, scope_type, product_id, scale_year, expected_spot_business_qty, accumulated_hedge_qty, category, scale_ratio_limit, usage_ratio, dispatch_status, dispatch_version, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "SUB_ACCOUNT", 1L, 2026L, new BigDecimal("10000.0000"), new BigDecimal("900.0000"), "COMMODITY", new BigDecimal("0.90"), new BigDecimal("0.09"), "SUCCESS", 1L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_annual_scale", 2L,
                "INSERT INTO t_annual_scale (id, sub_account_id, scope_type, product_id, scale_year, expected_spot_business_qty, accumulated_hedge_qty, category, scale_ratio_limit, usage_ratio, dispatch_status, dispatch_version, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 0L, "TOTAL_ACCOUNT", 1L, 2026L, new BigDecimal("20000.0000"), new BigDecimal("900.0000"), "COMMODITY", new BigDecimal("0.90"), new BigDecimal("0.045"), "SUCCESS", 1L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_annual_scale", 3L,
                "INSERT INTO t_annual_scale (id, sub_account_id, scope_type, product_id, scale_year, expected_spot_business_qty, accumulated_hedge_qty, category, scale_ratio_limit, usage_ratio, dispatch_status, dispatch_version, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 2L, "SUB_ACCOUNT", 6L, 2026L, new BigDecimal("5000.0000"), new BigDecimal("0.0000"), "TRADE", new BigDecimal("0.80"), new BigDecimal("0"), "SUCCESS", 1L, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_purchase_contract (1 行) ----
        exec(engine, "t_purchase_contract", 1L,
                "INSERT INTO t_purchase_contract (id, sub_account_id, contract_no, spot_category_code, product_id, supplier_name, total_qty, executed_qty, pricing_mode, contract_start_date, contract_end_date, exposure_direction, status, void_reason, closed_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "PC-2026-001", "SPOT-CU-CATHODE", 1L, "供应商甲（示例）", new BigDecimal("1000.0000"), new BigDecimal("600.0000"), "FIXED", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "BUY", "ACTIVE", null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_sales_contract (1 行) ----
        exec(engine, "t_sales_contract", 1L,
                "INSERT INTO t_sales_contract (id, sub_account_id, contract_no, spot_category_code, product_id, customer_name, total_qty, delivered_qty, pricing_mode, contract_start_date, contract_end_date, exposure_direction, has_dual_direction, buy_direction_qty, status, void_reason, closed_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 2L, "SC-2026-001", "SPOT-SC-CRUDE", 6L, "客户乙（示例）", new BigDecimal("800.0000"), new BigDecimal("300.0000"), "FIXED", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"), "SELL", "N", null, "ACTIVE", null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_production_plan (1 行) ----
        exec(engine, "t_production_plan", 1L,
                "INSERT INTO t_production_plan (id, sub_account_id, plan_no, plan_period_start, plan_period_end, status, overlap_flag, closed_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "PP-2026-Q4", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-12-31"), "SCHEDULED", "N", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_production_plan_line (2 行) ----
        exec(engine, "t_production_plan_line", 1L,
                "INSERT INTO t_production_plan_line (id, sub_account_id, production_plan_id, line_type, product_id, quantity, unit, exposure_direction, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, "FINISHED", 1L, new BigDecimal("500.0000"), "TON", "SELL", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_production_plan_line", 2L,
                "INSERT INTO t_production_plan_line (id, sub_account_id, production_plan_id, line_type, product_id, quantity, unit, exposure_direction, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 1L, 1L, "RAW_MATERIAL", 1L, new BigDecimal("300.0000"), "TON", "BUY", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_wip_record (1 行) ----
        exec(engine, "t_wip_record", 1L,
                "INSERT INTO t_wip_record (id, sub_account_id, product_id, production_plan_id, wip_qty, completion_ratio, exposure_direction, record_date, unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, 1L, new BigDecimal("100.0000"), new BigDecimal("0.40"), "SELL", LocalDate.parse("2026-11-20"), "TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_inventory_ledger (1 行) ----
        exec(engine, "t_inventory_ledger", 1L,
                "INSERT INTO t_inventory_ledger (id, sub_account_id, product_id, inventory_type, quantity, negative_confirmed, unit, valuation, record_date, exposure_direction, remark, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, "RAW_MATERIAL", new BigDecimal("200.0000"), "N", "TON", new BigDecimal("14000000.0000"), LocalDate.parse("2026-11-20"), "BUY", "示例库存", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_spot_price (32 行) ----
        exec(engine, "t_spot_price", 1L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 0L, 1L, LocalDate.parse("2026-10-08"), new BigDecimal("70000.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-08T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 2L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 0L, 1L, LocalDate.parse("2026-10-09"), new BigDecimal("70050.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-09T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 3L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 0L, 1L, LocalDate.parse("2026-10-12"), new BigDecimal("70100.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-12T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 4L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, 0L, 1L, LocalDate.parse("2026-10-13"), new BigDecimal("70150.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-13T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 5L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                5L, 0L, 1L, LocalDate.parse("2026-10-14"), new BigDecimal("70200.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-14T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 6L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                6L, 0L, 1L, LocalDate.parse("2026-10-15"), new BigDecimal("70250.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-15T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 7L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                7L, 0L, 1L, LocalDate.parse("2026-10-16"), new BigDecimal("70300.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-16T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 8L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                8L, 0L, 1L, LocalDate.parse("2026-10-19"), new BigDecimal("70350.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-19T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 9L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                9L, 0L, 1L, LocalDate.parse("2026-10-20"), new BigDecimal("70400.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-20T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 10L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                10L, 0L, 1L, LocalDate.parse("2026-10-21"), new BigDecimal("70450.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-21T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 11L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                11L, 0L, 1L, LocalDate.parse("2026-10-22"), new BigDecimal("70500.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-22T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 12L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                12L, 0L, 1L, LocalDate.parse("2026-10-23"), new BigDecimal("70550.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-23T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 13L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                13L, 0L, 1L, LocalDate.parse("2026-10-26"), new BigDecimal("70600.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-26T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 14L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                14L, 0L, 1L, LocalDate.parse("2026-10-27"), new BigDecimal("70650.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-27T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 15L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                15L, 0L, 1L, LocalDate.parse("2026-10-28"), new BigDecimal("70700.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-28T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 16L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                16L, 0L, 1L, LocalDate.parse("2026-10-29"), new BigDecimal("70750.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-29T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 17L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                17L, 0L, 1L, LocalDate.parse("2026-10-30"), new BigDecimal("70800.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-10-30T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 18L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                18L, 0L, 1L, LocalDate.parse("2026-11-02"), new BigDecimal("70850.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-02T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 19L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                19L, 0L, 1L, LocalDate.parse("2026-11-03"), new BigDecimal("70900.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-03T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 20L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                20L, 0L, 1L, LocalDate.parse("2026-11-04"), new BigDecimal("70950.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-04T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 21L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                21L, 0L, 1L, LocalDate.parse("2026-11-05"), new BigDecimal("71000.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-05T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 22L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                22L, 0L, 1L, LocalDate.parse("2026-11-06"), new BigDecimal("71050.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-06T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 23L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                23L, 0L, 1L, LocalDate.parse("2026-11-09"), new BigDecimal("71100.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-09T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 24L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                24L, 0L, 1L, LocalDate.parse("2026-11-10"), new BigDecimal("71150.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-10T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 25L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                25L, 0L, 1L, LocalDate.parse("2026-11-11"), new BigDecimal("71200.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-11T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 26L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                26L, 0L, 1L, LocalDate.parse("2026-11-12"), new BigDecimal("71250.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-12T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 27L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                27L, 0L, 1L, LocalDate.parse("2026-11-13"), new BigDecimal("71300.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-13T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 28L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                28L, 0L, 1L, LocalDate.parse("2026-11-16"), new BigDecimal("71350.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-16T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 29L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                29L, 0L, 1L, LocalDate.parse("2026-11-17"), new BigDecimal("71400.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-17T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 30L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                30L, 0L, 1L, LocalDate.parse("2026-11-18"), new BigDecimal("71450.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-18T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 31L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                31L, 0L, 1L, LocalDate.parse("2026-11-19"), new BigDecimal("71500.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-19T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_spot_price", 32L,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price, source_type, source_desc, import_file_name, operator_id, entry_time, is_shared, overwritten_price, overwrite_confirmed, price_unit, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                32L, 0L, 1L, LocalDate.parse("2026-11-20"), new BigDecimal("71550.0000"), "CSV_IMPORT", "示例现货价序列（演示数据，非真实行情）", "spot_price_demo.csv", 2L, LocalDateTime.parse("2026-11-20T09:00:00"), "Y", null, "N", "CNY/TON", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_exposure (2 行) ----
        exec(engine, "t_exposure", 1L,
                "INSERT INTO t_exposure (id, sub_account_id, product_id, spot_category_code, direction, calc_mode, contract_unexecuted_qty, inventory_qty, production_plan_qty, components_enabled, snapshot_id, snapshot_time, combined_components, as_of_date, calc_time, exposure_qty, unit, exposure_amount, price_missing_flag, price_as_of, non_current_price_flag, snapshot_as_of, covered_qty, status, unmapped_flag, row_version, created_by, created_time, updated_by, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, "__MAPPED__", "BUY", "COMBINED", new BigDecimal("400.0000"), new BigDecimal("200.0000"), new BigDecimal("300.0000"), "CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN", "SNAP-2026-11-20-CU-001", LocalDateTime.parse("2026-11-20T15:00:00"), "CONTRACT_UNEXECUTED+INVENTORY+PRODUCTION_PLAN", LocalDate.parse("2026-11-20"), LocalDateTime.parse("2026-11-20T15:30:00"), new BigDecimal("900.0000"), "TON", new BigDecimal("63000000.0000"), "N", LocalDate.parse("2026-11-20"), "N", LocalDateTime.parse("2026-11-20T15:00:00"), new BigDecimal("0.0000"), "PENDING_HEDGE", "N", 1, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null);
        exec(engine, "t_exposure", 2L,
                "INSERT INTO t_exposure (id, sub_account_id, product_id, spot_category_code, direction, calc_mode, contract_unexecuted_qty, inventory_qty, production_plan_qty, components_enabled, snapshot_id, snapshot_time, combined_components, as_of_date, calc_time, exposure_qty, unit, exposure_amount, price_missing_flag, price_as_of, non_current_price_flag, snapshot_as_of, covered_qty, status, unmapped_flag, row_version, created_by, created_time, updated_by, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 2L, 6L, "__MAPPED__", "SELL", "COMBINED", new BigDecimal("500.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"), "CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN", "SNAP-2026-11-20-SC-001", LocalDateTime.parse("2026-11-20T15:00:00"), "CONTRACT_UNEXECUTED", LocalDate.parse("2026-11-20"), LocalDateTime.parse("2026-11-20T15:30:00"), new BigDecimal("500.0000"), "BARREL", new BigDecimal("250000.0000"), "N", LocalDate.parse("2026-11-20"), "N", LocalDateTime.parse("2026-11-20T15:00:00"), new BigDecimal("0.0000"), "PENDING_HEDGE", "N", 1, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null);
        // ---- t_exposure_detail (4 行) ----
        exec(engine, "t_exposure_detail", 1L,
                "INSERT INTO t_exposure_detail (id, sub_account_id, exposure_id, component_type, source_type, source_id, source_no, contributed_qty, unit, direction, calc_as_of_date, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", 1L, "PC-2026-001", new BigDecimal("400.0000"), "TON", "BUY", LocalDate.parse("2026-11-20"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_exposure_detail", 2L,
                "INSERT INTO t_exposure_detail (id, sub_account_id, exposure_id, component_type, source_type, source_id, source_no, contributed_qty, unit, direction, calc_as_of_date, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 1L, 1L, "INVENTORY", "INVENTORY_LEDGER", 1L, "INV-CU-20261120", new BigDecimal("200.0000"), "TON", "BUY", LocalDate.parse("2026-11-20"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_exposure_detail", 3L,
                "INSERT INTO t_exposure_detail (id, sub_account_id, exposure_id, component_type, source_type, source_id, source_no, contributed_qty, unit, direction, calc_as_of_date, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 1L, 1L, "PRODUCTION_PLAN", "PRODUCTION_PLAN", 1L, "PP-2026-Q4", new BigDecimal("300.0000"), "TON", "BUY", LocalDate.parse("2026-11-20"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_exposure_detail", 4L,
                "INSERT INTO t_exposure_detail (id, sub_account_id, exposure_id, component_type, source_type, source_id, source_no, contributed_qty, unit, direction, calc_as_of_date, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                4L, 2L, 2L, "CONTRACT_UNEXECUTED", "SALES_CONTRACT", 1L, "SC-2026-001", new BigDecimal("500.0000"), "BARREL", "SELL", LocalDate.parse("2026-11-20"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_hedge_plan (1 行) ----
        exec(engine, "t_hedge_plan", 1L,
                "INSERT INTO t_hedge_plan (id, sub_account_id, plan_no, plan_type, product_id, hedge_direction, plan_qty, unit, term_start_date, term_end_date, hedge_ratio, ratio_method, calc_base_date, status, executed_qty, ordered_qty, applicant_id, current_doc_version, freeze_reason, terminate_reason, extend_basis, quota_occupied, row_version, created_by, created_time, updated_by, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "HP-2026-001", "COMMODITY", 1L, "BUY_HEDGE", new BigDecimal("900.0000"), "TON", LocalDate.parse("2026-11-20"), LocalDate.parse("2027-05-20"), new BigDecimal("0.90"), "SIMPLE", LocalDate.parse("2026-11-20"), "APPROVED", new BigDecimal("180.0000"), new BigDecimal("180.0000"), 1L, 1L, null, null, null, "Y", 1, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null);
        // ---- t_hedge_plan_exposure (1 行) ----
        exec(engine, "t_hedge_plan_exposure", 1L,
                "INSERT INTO t_hedge_plan_exposure (id, sub_account_id, hedge_plan_id, exposure_id, covered_qty, bind_time, unbind_time, status, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, 1L, new BigDecimal("900.0000"), LocalDateTime.parse("2026-11-20T16:00:00"), null, "BOUND", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_hedge_ratio_calc (1 行) ----
        exec(engine, "t_hedge_ratio_calc", 1L,
                "INSERT INTO t_hedge_ratio_calc (id, sub_account_id, hedge_plan_id, exposure_id, ratio_method, input_json, output_ratio, rho, sigma_spot, sigma_futures, sample_count, sample_start_date, sample_end_date, custom_rule_id, custom_rule_desc, applied_ratio_cap, applied_rule_id, hit_rule_count, result_code, fail_detail, operator_id, calc_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, 1L, "SIMPLE", "{\"spot_qty\":900,\"futures_qty\":900}", new BigDecimal("0.90"), null, null, null, 32L, LocalDate.parse("2026-10-08"), LocalDate.parse("2026-11-20"), null, null, new BigDecimal("0.90"), null, null, "SUCCESS", null, 2L, LocalDateTime.parse("2026-11-20T15:30:00"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_hedge_doc (1 行) ----
        exec(engine, "t_hedge_doc", 1L,
                "INSERT INTO t_hedge_doc (id, sub_account_id, hedge_plan_id, doc_no, doc_version, is_current, elem_formal_designation, elem_effectiveness_method, elem_rebalance_mechanism, hedged_item_exposure_id, hedging_instrument, custom_rule_id, pending_external_review, generated_by, generated_time, invalidated_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, "HD-2026-001", 1L, "Y", "正式指定：采购合同 PC-2026-001 未执行敞口 400 吨 + 库存 200 吨 + 生产计划 300 吨，合计 900 吨，以 cu2612 期货买入套保。", "定性观察：品种相关性、方向一致性、期限对应性（±3 个月）；量化监测偏离 1 超 ±50% 仅提示复核。", "敞口变动 ≥20% 或套期比率偏离 ≥20% 触发再平衡，须审批留痕。", 1L, "cu2612（SHFE 阴极铜期货）", null, "Y", 2L, LocalDateTime.parse("2026-11-20T16:30:00"), null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_plan_approval (2 行) ----
        exec(engine, "t_plan_approval", 1L,
                "INSERT INTO t_plan_approval (id, sub_account_id, hedge_plan_id, node_seq, node_type, applicant_id, approver_id, approval_result, approval_comment, reject_reason, precheck_result, action_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, 1L, "SUBMIT", 1L, null, null, null, null, null, LocalDateTime.parse("2026-11-20T16:00:00"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_plan_approval", 2L,
                "INSERT INTO t_plan_approval (id, sub_account_id, hedge_plan_id, node_seq, node_type, applicant_id, approver_id, approval_result, approval_comment, reject_reason, precheck_result, action_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 1L, 1L, 2L, "APPROVE", 1L, 5L, "APPROVED", "同意（示例）", null, null, LocalDateTime.parse("2026-11-20T17:00:00"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_trade_plan (1 行) ----
        exec(engine, "t_trade_plan", 1L,
                "INSERT INTO t_trade_plan (id, sub_account_id, trade_plan_no, hedge_plan_id, exposure_id, product_id, instrument_id, instrument_type, direction, plan_qty, executed_qty, ordered_qty, exec_deadline, status, strategy_id, row_version, created_by, created_time, updated_by, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "TP-2026-001", 1L, 1L, 1L, "cu2612", "FUTURES", "BUY", new BigDecimal("180.0000"), new BigDecimal("180.0000"), new BigDecimal("180.0000"), LocalDate.parse("2026-12-31"), "EXECUTING", null, 1, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null);
        // ---- t_plan_state_log (2 行) ----
        exec(engine, "t_plan_state_log", 1L,
                "INSERT INTO t_plan_state_log (id, sub_account_id, hedge_plan_id, from_status, to_status, quota_action, quota_qty, quota_product_id, dispatch_status, dispatch_version, operator_id, reason, action_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, "DRAFT", "PENDING_APPROVAL", "NONE", null, null, "NOT_REQUIRED", null, 1L, "提交审批", LocalDateTime.parse("2026-11-20T16:00:00"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_plan_state_log", 2L,
                "INSERT INTO t_plan_state_log (id, sub_account_id, hedge_plan_id, from_status, to_status, quota_action, quota_qty, quota_product_id, dispatch_status, dispatch_version, operator_id, reason, action_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 1L, 1L, "PENDING_APPROVAL", "APPROVED", "OCCUPY", new BigDecimal("180.0000"), 1L, "SUCCESS", 1L, 5L, "审批通过并占用额度", LocalDateTime.parse("2026-11-20T17:00:00"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_trade_order (1 行) ----
        exec(engine, "t_trade_order", 1L,
                "INSERT INTO t_trade_order (id, sub_account_id, client_order_ref, order_id, order_sys_id, trade_plan_id, hedge_plan_id, exposure_id, product_id, instrument_id, instrument_type, direction, offset_flag, price_type, limit_price, volume, operator_id, strategy_id, leg_no, trade_reason_id, status, reject_rule_id, reject_code, reject_reason, confirm_operator_id, confirm_time, accept_time, submit_time, client_ts_ms, expire_time, cancel_reason, cancel_operator_id, cancel_time, row_version, created_by, created_time, updated_by, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "ORD-2026-11-20-001", null, null, 1L, 1L, 1L, 1L, "cu2612", "FUTURES", "BUY", "OPEN", "LIMIT", new BigDecimal("70000.0000"), 180L, 1L, null, null, null, "ALL_FILLED", null, null, null, 2L, LocalDateTime.parse("2026-11-20T09:30:02"), LocalDateTime.parse("2026-11-20T09:30:05"), LocalDateTime.parse("2026-11-20T09:30:00"), 1795167000000L, null, null, null, null, 1, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null);
        // ---- t_trade_fill (1 行) ----
        exec(engine, "t_trade_fill", 1L,
                "INSERT INTO t_trade_fill (id, sub_account_id, trade_id, order_id, order_sys_id, trade_plan_id, hedge_plan_id, exposure_id, product_id, instrument_id, instrument_type, direction, offset_flag, fill_price, fill_volume, contract_multiplier, commission, trade_ts_ms, trade_date, fill_time, event_seq, settled_flag, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "FILL-2026-11-20-001", 1L, null, 1L, 1L, 1L, 1L, "cu2612", "FUTURES", "BUY", "OPEN", new BigDecimal("70000.0000"), 180L, new BigDecimal("5"), new BigDecimal("500.0000"), 1795167000000L, LocalDate.parse("2026-11-20"), LocalDateTime.parse("2026-11-20T09:31:00"), 1L, "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_trade_reason (1 行) ----
        exec(engine, "t_trade_reason", 1L,
                "INSERT INTO t_trade_reason (id, sub_account_id, trade_order_id, related_type, related_plan_id, related_exposure_id, time_desc, reason_text, forced_by_freq_alert, related_object_missing_flag, operator_id, fill_time, is_modified, before_code, before_text, modified_by, modified_time, reason_missing_flag, missing_marked_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, "PLAN_EXECUTION", 1L, 1L, "CONTRACT_SIGNED", "执行套保计划 HP-2026-001，对冲采购合同 PC-2026-001 敞口（示例）", "N", "N", 1L, LocalDateTime.parse("2026-11-20T09:30:00"), "N", null, null, null, null, "N", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_position (1 行) ----
        exec(engine, "t_position", 1L,
                "INSERT INTO t_position (id, sub_account_id, instrument_id, product_id, direction, position_type, position_qty, available_qty, frozen_qty, avg_open_price, last_settlement_price, hedge_plan_id, as_of_time, event_seq, row_version, created_by, created_time, updated_by, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "cu2612", 1L, "LONG", "TODAY", new BigDecimal("180.0000"), new BigDecimal("180.0000"), new BigDecimal("0.0000"), new BigDecimal("70000.0000"), new BigDecimal("70100.0000"), 1L, LocalDateTime.parse("2026-11-20T15:00:00"), 1L, 1, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null);
        // ---- t_settle_cycle (1 行) ----
        exec(engine, "t_settle_cycle", 1L,
                "INSERT INTO t_settle_cycle (id, sub_account_id, settle_no, trade_date, is_aggregate, status, close_pnl, float_pnl, commission, margin_occupied, available_fund, equity, risk_ratio, negative_fund_flag, confirmed_by, confirmed_time, voided_flag, void_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "SET-2026-11-20-01", LocalDate.parse("2026-11-20"), "N", "CONFIRMED", new BigDecimal("0.0000"), new BigDecimal("18000.0000"), new BigDecimal("500.0000"), new BigDecimal("630000.0000"), new BigDecimal("9369500.0000"), new BigDecimal("10000000.0000"), new BigDecimal("0.063"), "N", 3L, LocalDateTime.parse("2026-11-20T18:00:00"), "N", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_settle_position (1 行) ----
        exec(engine, "t_settle_position", 1L,
                "INSERT INTO t_settle_position (id, sub_account_id, settle_cycle_id, trade_date, instrument_id, product_id, instrument_type, direction, position_type, position_qty, attribution_source, unattributed_qty, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, LocalDate.parse("2026-11-20"), "cu2612", 1L, "FUTURES", "LONG", "TODAY", new BigDecimal("180.0000"), "TRADE_RECORD", null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_settle_fund (1 行) ----
        exec(engine, "t_settle_fund", 1L,
                "INSERT INTO t_settle_fund (id, sub_account_id, settle_cycle_id, trade_date, begin_balance, equity, available_fund, margin_occupied, frozen_margin, amount_in, amount_out, transfer_in, transfer_out, close_pnl, float_pnl, commission, margin_change, end_balance, fund_ledger_id, attribution_source, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, LocalDate.parse("2026-11-20"), new BigDecimal("10000000.0000"), new BigDecimal("10018000.0000"), new BigDecimal("9369500.0000"), new BigDecimal("630000.0000"), null, new BigDecimal("0.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"), new BigDecimal("0.0000"), new BigDecimal("18000.0000"), new BigDecimal("500.0000"), new BigDecimal("630000.0000"), new BigDecimal("10018000.0000"), null, "LEDGER", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_settle_pnl (1 行) ----
        exec(engine, "t_settle_pnl", 1L,
                "INSERT INTO t_settle_pnl (id, sub_account_id, settle_cycle_id, trade_date, instrument_id, product_id, hedge_plan_id, pnl_type, amount, qty, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, LocalDate.parse("2026-11-20"), "cu2612", 1L, 1L, "FLOAT_UNREALIZED", new BigDecimal("18000.0000"), new BigDecimal("180.0000"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_check_task (1 行) ----
        exec(engine, "t_check_task", 1L,
                "INSERT INTO t_check_task (id, check_type, period_key, sub_account_id, required_sign_roles, signed_roles, status, snapshot_hash, snapshot_json, check_items, check_conclusion, content_json, submitted_to, deadline_date, overdue_alerted, generated_time, completed_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "MONTHLY", "2026-11", 0L, "RISK,TRADE,FINANCE", "RISK,TRADE,FINANCE", "COMPLETED", "DEMO-HASH-0001", null, "加总对账,限额,签署", "一致（示例）", null, null, LocalDate.parse("2026-12-30"), "N", LocalDateTime.parse("2026-11-30T09:00:00"), LocalDateTime.parse("2026-11-30T18:00:00"), 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_check_signature (3 行) ----
        exec(engine, "t_check_signature", 1L,
                "INSERT INTO t_check_signature (id, sub_account_id, check_task_id, sign_role, signer_id, signer_role_code, sign_time, sign_ip, snapshot_hash, sign_result, sign_opinion, proxy_rejected, manage_role_trade_blocked, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 0L, 1L, "RISK", 2L, "FIN_RISK", LocalDateTime.parse("2026-11-30T10:00:00"), "127.0.0.1", "DEMO-HASH-0001", "SIGNED", "核对一致", "N", "N", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_check_signature", 2L,
                "INSERT INTO t_check_signature (id, sub_account_id, check_task_id, sign_role, signer_id, signer_role_code, sign_time, sign_ip, snapshot_hash, sign_result, sign_opinion, proxy_rejected, manage_role_trade_blocked, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 0L, 1L, "TRADE", 1L, "TRADER", LocalDateTime.parse("2026-11-30T11:00:00"), "127.0.0.1", "DEMO-HASH-0001", "SIGNED", "核对一致", "N", "N", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_check_signature", 3L,
                "INSERT INTO t_check_signature (id, sub_account_id, check_task_id, sign_role, signer_id, signer_role_code, sign_time, sign_ip, snapshot_hash, sign_result, sign_opinion, proxy_rejected, manage_role_trade_blocked, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                3L, 0L, 1L, "FINANCE", 4L, "CASHIER", LocalDateTime.parse("2026-11-30T12:00:00"), "127.0.0.1", "DEMO-HASH-0001", "SIGNED", "核对一致", "N", "N", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_hedge_relation (1 行) ----
        exec(engine, "t_hedge_relation", 1L,
                "INSERT INTO t_hedge_relation (id, sub_account_id, relation_no, hedge_plan_id, hedged_item_exposure_id, hedging_instrument_type, hedging_instrument_desc, hedge_doc_id, status, designated_time, terminated_time, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "HR-2026-001", 1L, 1L, "FUTURES", "cu2612 SHFE 阴极铜期货", 1L, "DESIGNATED", LocalDateTime.parse("2026-11-20T17:30:00"), null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_accounting_period (1 行) ----
        exec(engine, "t_accounting_period", 1L,
                "INSERT INTO t_accounting_period (id, period_key, period_type, period_start, period_end, status, closed_by, closed_time, close_reviewer_id, reopened_by, reopened_time, reopen_reason, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, "2026-11", "MONTH", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), "OPEN", null, null, null, null, null, null, 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_voucher (1 行) ----
        exec(engine, "t_voucher", 1L,
                "INSERT INTO t_voucher (id, sub_account_id, voucher_no, period_key, voucher_date, voucher_type, hedge_plan_id, hedge_relation_id, settle_cycle_id, exposure_id, status, total_amount, generated_by, generated_time, reversal_of_id, reversed_by_id, reversal_reason, pending_external_review, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "VC-2026-11-0001", "2026-11", LocalDate.parse("2026-11-20"), "FLOAT_PNL", 1L, 1L, 1L, 1L, "DRAFT", new BigDecimal("18000.0000"), 2L, LocalDateTime.parse("2026-11-20T19:00:00"), null, null, null, "Y", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        // ---- t_voucher_line (2 行) ----
        exec(engine, "t_voucher_line", 1L,
                "INSERT INTO t_voucher_line (id, sub_account_id, voucher_id, line_no, subject_code, subject_name, direction, amount, qty, trade_fill_id, biz_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, 1L, 1L, "6101", "公允价值变动损益", "DEBIT", new BigDecimal("18000.0000"), new BigDecimal("180.0000"), 1L, "浮动盈亏草稿（示例）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
        exec(engine, "t_voucher_line", 2L,
                "INSERT INTO t_voucher_line (id, sub_account_id, voucher_id, line_no, subject_code, subject_name, direction, amount, qty, trade_fill_id, biz_desc, created_by, created_time, updated_by, updated_time, row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                2L, 1L, 1L, 2L, "6101", "公允价值变动损益", "CREDIT", new BigDecimal("18000.0000"), new BigDecimal("180.0000"), 1L, "浮动盈亏草稿（示例）", 0L, LocalDateTime.parse("2026-11-20T00:00:00"), null, null, 1);
    }

    /** 与 M1E2eSeed 同法的 JDBC 绑定助手；先按 (table, id) 判重实现幂等。 */
    private static void exec(InMemoryDmEngine engine, String table, long rowId,
            String sql, Object... args) throws SQLException {
        InMemoryDmEngine.Table t = engine.tables().get(table);
        if (t != null) {
            for (Map<String, Object> existing : t.rows) {
                Object v = existing.get("id");
                if (v instanceof Number && ((Number) v).longValue() == rowId) {
                    return;
                }
            }
        }
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                int idx = i + 1;
                if (a instanceof Integer) {
                    ps.setInt(idx, (Integer) a);
                } else if (a instanceof Long) {
                    ps.setLong(idx, (Long) a);
                } else if (a instanceof LocalDate) {
                    ps.setDate(idx, Date.valueOf((LocalDate) a));
                } else if (a instanceof LocalDateTime) {
                    ps.setTimestamp(idx, Timestamp.valueOf((LocalDateTime) a));
                } else if (a instanceof BigDecimal) {
                    ps.setBigDecimal(idx, (BigDecimal) a);
                } else if (a == null) {
                    ps.setNull(idx, Types.NULL);
                } else {
                    ps.setString(idx, String.valueOf(a));
                }
            }
            ps.executeUpdate();
        }
    }
}
