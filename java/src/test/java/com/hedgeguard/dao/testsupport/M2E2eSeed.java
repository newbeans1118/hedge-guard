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

/**
 * {@code T-058} M2 端到端验收用的<b>最小 M1 前置播种件</b>（建在 {@code dao} 命名空间内）。
 *
 * <p><b>为何单独成类</b>：与 {@link M1E2eSeed} 同法——E5 对 {@code src/test} 同规则
 * （{@code dao} 之外不得出现 SQL）；{@code data-init.sql} 的 {@code SET IDENTITY_INSERT}
 * 本内存引擎不解析，故按等价语义直接写入。
 *
 * <p><b>范围</b>：本类只播种 M2 链路<b>上游</b>（M1 段）的最小行（品种 / 岗位组 / 角色 / 用户角色 /
 * 敞口 / 套保计划 / 交易计划），使 {@code T-058} 的 M2 段（交易 → 结算 → 资金）可在真实 DAO +
 * 真实服务装配下跑通。<b>M1 链（敞口 → 方案）本身已由 {@code T-024} 端到端验收覆盖</b>，
 * 本单不重复其业务校验，仅提供可用行。
 *
 * <p>本类不属于产品代码，仅测试夹具；不改变任何 {@code src/main} 行为。
 */
public final class M2E2eSeed {

    private M2E2eSeed() {
    }

    /** 子账户行（{@code ENABLED}，{@code COMMODITY}）。 */
    public static void subAccount(InMemoryDmEngine engine, int id, String code, String name)
            throws SQLException {
        exec(engine, "INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, status,"
                        + " category, is_default, disabled_reason, created_by)"
                        + " VALUES (?, ?, ?, 'ENABLED', 'COMMODITY', 'N', NULL, ?)",
                id, code, name, 0L);
    }

    /** 岗位组（角色外键载体）。 */
    public static void positionGroup(InMemoryDmEngine engine, long id, String code, String name,
                                     String frontMidBack) throws SQLException {
        exec(engine, "INSERT INTO t_position_group (id, group_code, group_name, front_mid_back,"
                        + " is_conflict_enforced, status, created_by)"
                        + " VALUES (?, ?, ?, ?, 'Y', 'ENABLED', ?)",
                id, code, name, frontMidBack, 0L);
    }

    /**
     * 角色行。
     *
     * @param hasTradingOperation {@code Y} = 具交易下单能力
     */
    public static void role(InMemoryDmEngine engine, long id, String code, String name,
                            long positionGroupId, String hasTradingOperation) throws SQLException {
        exec(engine, "INSERT INTO t_role (id, role_code, role_name, position_group_id, is_preset,"
                        + " is_business_role, has_trading_operation, status, created_by)"
                        + " VALUES (?, ?, ?, ?, 'Y', 'Y', ?, 'ENABLED', ?)",
                id, code, name, positionGroupId, hasTradingOperation, 0L);
    }

    /** 用户角色行。 */
    public static void userRole(InMemoryDmEngine engine, long id, long userId, long roleId)
            throws SQLException {
        exec(engine, "INSERT INTO t_user_role (id, user_id, role_id, granted_by, granted_time,"
                        + " created_by) VALUES (?, ?, ?, ?, ?, ?)",
                id, userId, roleId, 100L, LocalDateTime.of(2026, 1, 1, 0, 0), 100L);
    }

    /** 品种（显式主键；{@code status=ENABLED}）。 */
    public static void product(InMemoryDmEngine engine, long id, String code, String name)
            throws SQLException {
        exec(engine, "INSERT INTO t_product (id, product_code, product_name, category,"
                        + " category_managed, status, is_domestic_exchange, currency, created_by)"
                        + " VALUES (?, ?, ?, 'COMMODITY', 'Y', 'ENABLED', 'Y', 'CNY', ?)",
                id, code, name, 0L);
    }

    /** 已映射实货敞口（{@code SELL} 方向，{@code CONTRACT_UNEXECUTED} 分量）。 */
    public static void exposure(InMemoryDmEngine engine, long id, int subAccountId, long productId,
                                String qty, LocalDate asOf) throws SQLException {
        exec(engine, "INSERT INTO t_exposure (id, sub_account_id, product_id, spot_category_code,"
                        + " direction, calc_mode, contract_unexecuted_qty, inventory_qty,"
                        + " production_plan_qty, components_enabled, snapshot_id, snapshot_time,"
                        + " as_of_date, calc_time, exposure_qty, unit, covered_qty, status,"
                        + " unmapped_flag, created_by)"
                        + " VALUES (?, ?, ?, '__MAPPED__', 'SELL', 'CONTRACT_UNEXECUTED', ?, 0, 0,"
                        + " 'CONTRACT_UNEXECUTED', ?, ?, ?, ?, ?, 'TON', 0, 'PENDING_HEDGE', 'N', ?)",
                id, subAccountId, productId, qty, "SNP-" + id,
                asOf.atStartOfDay(), asOf, asOf.atStartOfDay(), qty, 0L);
    }

    /** 套保计划（{@code BUY_HEDGE}，{@code APPROVED}）。 */
    public static void hedgePlan(InMemoryDmEngine engine, long id, int subAccountId, long productId,
                                 String planNo, String qty, LocalDate start, LocalDate end)
            throws SQLException {
        exec(engine, "INSERT INTO t_hedge_plan (id, sub_account_id, plan_no, plan_type, product_id,"
                        + " hedge_direction, plan_qty, unit, term_start_date, term_end_date,"
                        + " hedge_ratio, ratio_method, calc_base_date, status, executed_qty,"
                        + " ordered_qty, applicant_id, current_doc_version, quota_occupied, created_by)"
                        + " VALUES (?, ?, ?, 'COMMODITY', ?, 'BUY_HEDGE', ?, 'TON', ?, ?, 0.8,"
                        + " 'SIMPLE', ?, 'APPROVED', 0, 0, ?, 0, 'N', ?)",
                id, subAccountId, planNo, productId, qty, start, end,
                start, 100L, 100L);
    }

    /** 交易计划（{@code BUY} 方向，{@code EXECUTING}，期货合约）。 */
    public static void tradePlan(InMemoryDmEngine engine, long id, int subAccountId, long hedgePlanId,
                                 long exposureId, long productId, String planNo, String instrumentId,
                                 String qty, LocalDate deadline) throws SQLException {
        // row_version 显式取 1：DAO 的乐观锁以「读到的版本 > 0」为更新前提
        // （AuditFields.applyUpdate），与 DAO insert 的 rowVersion=1 约定一致。
        exec(engine, "INSERT INTO t_trade_plan (id, sub_account_id, trade_plan_no, hedge_plan_id,"
                        + " exposure_id, product_id, instrument_id, instrument_type, direction,"
                        + " plan_qty, executed_qty, ordered_qty, exec_deadline, status, row_version,"
                        + " created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'FUTURES', 'BUY', ?, 0, 0, ?,"
                        + " 'EXECUTING', 1, ?)",
                id, subAccountId, planNo, hedgePlanId, exposureId, productId, instrumentId, qty,
                deadline, 100L);
    }

    /** CTP 日终批次（{@code parse_status} 取冻结域内合法值 {@code OK}）。 */
    public static void ctpBatch(InMemoryDmEngine engine, long id, LocalDate tradeDate,
                                String source, int positionCount, int fundCount)
            throws SQLException {
        exec(engine, "INSERT INTO t_ctp_settle_batch (id, trade_date, source_identifier,"
                        + " received_time, position_count, fund_count, trade_count, parse_status,"
                        + " retry_count, is_confirmed, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, 'OK', 0, 'Y', ?)",
                id, tradeDate, source, tradeDate.atStartOfDay(), positionCount, fundCount, 0L);
    }

    /** CTP 持仓明细（账户级合并持仓，外部核对基准）。 */
    public static void ctpPosition(InMemoryDmEngine engine, long batchId, LocalDate tradeDate,
                                   String instrumentId, String direction, String positionType,
                                   String qty) throws SQLException {
        exec(engine, "INSERT INTO t_ctp_settle_position (trade_date, settle_batch_id, instrument_id,"
                        + " direction, position_type, position_qty, avg_price, settlement_price,"
                        + " margin, created_by) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, ?)",
                tradeDate, batchId, instrumentId, direction, positionType, qty, 0L);
    }

    /** CTP 资金明细（账户级合并资金）。 */
    public static void ctpFund(InMemoryDmEngine engine, long batchId, LocalDate tradeDate,
                               String equity, String availableFund, String margin,
                               String commission, String closePnl, String floatPnl)
            throws SQLException {
        exec(engine, "INSERT INTO t_ctp_settle_fund (trade_date, settle_batch_id, equity,"
                        + " available_fund, margin_occupied, frozen_margin, commission_today,"
                        + " deposit_today, withdraw_today, close_pnl, float_pnl, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, 0, ?, 0, 0, ?, ?, ?)",
                tradeDate, batchId, equity, availableFund, margin, commission, closePnl, floatPnl,
                0L);
    }

    private static void exec(InMemoryDmEngine engine, String sql, Object... args) throws SQLException {
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                int idx = i + 1;
                if (a instanceof Integer) {
                    ps.setInt(idx, (Integer) a);
                } else if (a instanceof Long) {
                    ps.setLong(idx, (Long) a);
                } else if (a instanceof BigDecimal) {
                    ps.setBigDecimal(idx, (BigDecimal) a);
                } else if (a instanceof LocalDate) {
                    ps.setDate(idx, Date.valueOf((LocalDate) a));
                } else if (a instanceof LocalDateTime) {
                    ps.setTimestamp(idx, Timestamp.valueOf((LocalDateTime) a));
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
