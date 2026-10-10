package com.hedgeguard.dao.testsupport;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code T-066} Java 侧红线复算用例的<b>最小播种件</b>（建在 {@code dao} 命名空间内，
 * 满足 {@code E5}「SQL 基建须落 {@code **&#47;dao&#47;**}」分域）。
 *
 * <p>只写入复算所需的行：子账户 / 品种 / 合约乘数 / 计量换算 / 年度规模基数 / 实货敞口 /
 * 持仓 / 套保计划 / 实货合同。主键显式给出，便于断言。
 */
public final class T066RiskRecomputeSeed {

    private T066RiskRecomputeSeed() {
    }

    /** 年度规模基数行（{@code t_annual_scale}）。 */
    public static void annualScale(InMemoryDmEngine engine, long id, Integer subAccountId,
                                   String scopeType, long productId, int scaleYear,
                                   String expected, String accumulated, String category,
                                   String ratioLimit) throws SQLException {
        exec(engine, "INSERT INTO t_annual_scale (id, sub_account_id, scope_type, product_id,"
                        + " scale_year, expected_spot_business_qty, accumulated_hedge_qty, category,"
                        + " scale_ratio_limit, dispatch_status, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS', ?)",
                id, subAccountId, scopeType, productId, scaleYear,
                new BigDecimal(expected), new BigDecimal(accumulated), category,
                new BigDecimal(ratioLimit), 0L);
    }

    /** 实货敞口行（{@code t_exposure}；方向与快照时点可控）。 */
    public static void exposure(InMemoryDmEngine engine, long id, int subAccountId, long productId,
                                String direction, String qty, LocalDateTime snapshotTime)
            throws SQLException {
        exec(engine, "INSERT INTO t_exposure (id, sub_account_id, product_id, spot_category_code,"
                        + " direction, calc_mode, contract_unexecuted_qty, inventory_qty,"
                        + " production_plan_qty, components_enabled, snapshot_id, snapshot_time,"
                        + " as_of_date, calc_time, exposure_qty, unit, covered_qty, status,"
                        + " unmapped_flag, created_by)"
                        + " VALUES (?, ?, ?, '__MAPPED__', ?, 'CONTRACT_UNEXECUTED', ?, 0, 0,"
                        + " 'CONTRACT_UNEXECUTED', ?, ?, ?, ?, ?, 'TON', 0, 'PENDING_HEDGE', 'N', ?)",
                id, subAccountId, productId, direction, new BigDecimal(qty), "SNP-" + id,
                snapshotTime, snapshotTime.toLocalDate(), snapshotTime, new BigDecimal(qty), 0L);
    }

    /** 持仓行（{@code t_position}；方向 LONG/SHORT，仓型 YESTERDAY）。 */
    public static void position(InMemoryDmEngine engine, long id, int subAccountId,
                                String instrumentId, long productId, String direction, String qty)
            throws SQLException {
        exec(engine, "INSERT INTO t_position (id, sub_account_id, instrument_id, product_id,"
                        + " direction, position_type, position_qty, available_qty, frozen_qty,"
                        + " as_of_time, event_seq, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, 'YESTERDAY', ?, ?, 0, ?, 1, ?)",
                id, subAccountId, instrumentId, productId, direction, new BigDecimal(qty),
                new BigDecimal(qty), LocalDateTime.of(2026, 1, 1, 15, 0), 0L);
    }

    /** 品种-合约（{@code t_product_contract}；合约乘数）。 */
    public static void productContract(InMemoryDmEngine engine, long id, long productId,
                                       String instrumentId, String multiplier, LocalDate expiry)
            throws SQLException {
        exec(engine, "INSERT INTO t_product_contract (id, product_id, instrument_id, instrument_type,"
                        + " exchange_code, contract_multiplier, expiry_date, status,"
                        + " is_domestic_exchange, created_by)"
                        + " VALUES (?, ?, ?, 'FUTURES', 'SHFE', ?, ?, 'ENABLED', 'Y', ?)",
                id, productId, instrumentId, new BigDecimal(multiplier), expiry, 0L);
    }

    /** 计量换算关系（{@code t_product_unit}；换算系数）。 */
    public static void productUnit(InMemoryDmEngine engine, long id, long productId, String factor,
                                   LocalDate effectiveFrom) throws SQLException {
        exec(engine, "INSERT INTO t_product_unit (id, product_id, spot_unit, futures_unit,"
                        + " convert_factor, status, effective_from, created_by)"
                        + " VALUES (?, ?, 'TON', 'LOT', ?, 'ENABLED', ?, ?)",
                id, productId, new BigDecimal(factor), effectiveFrom, 0L);
    }

    /** 采购合同（{@code t_purchase_contract}；{@code ACTIVE}）。 */
    public static void purchaseContract(InMemoryDmEngine engine, long id, int subAccountId,
                                        long productId, String no, LocalDate start, LocalDate end)
            throws SQLException {
        exec(engine, "INSERT INTO t_purchase_contract (id, sub_account_id, contract_no,"
                        + " spot_category_code, product_id, total_qty, executed_qty,"
                        + " contract_start_date, contract_end_date, exposure_direction, status,"
                        + " created_by) VALUES (?, ?, ?, '__MAPPED__', ?, 100, 0, ?, ?, 'BUY',"
                        + " 'ACTIVE', ?)",
                id, subAccountId, no, productId, start, end, 0L);
    }

    /** 销售合同（{@code t_sales_contract}；{@code ACTIVE}）。 */
    public static void salesContract(InMemoryDmEngine engine, long id, int subAccountId,
                                     long productId, String no, LocalDate start, LocalDate end)
            throws SQLException {
        exec(engine, "INSERT INTO t_sales_contract (id, sub_account_id, contract_no,"
                        + " spot_category_code, product_id, total_qty, delivered_qty,"
                        + " contract_start_date, contract_end_date, exposure_direction,"
                        + " has_dual_direction, status, created_by)"
                        + " VALUES (?, ?, ?, '__MAPPED__', ?, 100, 0, ?, ?, 'SELL', 'N', 'ACTIVE', ?)",
                id, subAccountId, no, productId, start, end, 0L);
    }

    /** 套保计划（{@code t_hedge_plan}；可指定状态与展期依据）。 */
    public static void hedgePlan(InMemoryDmEngine engine, long id, int subAccountId, long productId,
                                 String planNo, String hedgeDirection, LocalDate start, LocalDate end,
                                 String status, String extendBasis) throws SQLException {
        exec(engine, "INSERT INTO t_hedge_plan (id, sub_account_id, plan_no, plan_type, product_id,"
                        + " hedge_direction, plan_qty, unit, term_start_date, term_end_date,"
                        + " hedge_ratio, ratio_method, calc_base_date, status, executed_qty,"
                        + " ordered_qty, applicant_id, current_doc_version, extend_basis,"
                        + " quota_occupied, created_by)"
                        + " VALUES (?, ?, ?, 'COMMODITY', ?, ?, 100, 'TON', ?, ?, 0.8, 'SIMPLE', ?,"
                        + " ?, 0, 0, 100, 0, ?, 'N', ?)",
                id, subAccountId, planNo, productId, hedgeDirection, start, end, start, status,
                extendBasis, 0L);
    }

    private static void exec(InMemoryDmEngine engine, String sql, Object... args) throws SQLException {
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
