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
 * {@code T-204} 结算导出专用种子（测试基建，落点 {@code dao.testsupport}——SQL 字面量
 * 须在 dao 包内以过 {@code E5} 门禁，先例 {@code M3AuditTraceSeed} / {@code T080AcctEffectSeed}）。
 *
 * <p>与 {@code M3AuditTraceSeed.settleCycleConfirmed} 同构但<b>状态/权益参数化</b>，
 * 另补 {@code t_aggregation_check} 种子（加总对账结论列载体）。
 */
public final class T204SettleExportSeed {

    private T204SettleExportSeed() {
    }

    /** 结算单行（is_aggregate='N'；状态/权益参数化）。 */
    public static void settleCycle(InMemoryDmEngine engine, long id, int subAccountId, String settleNo,
                                   LocalDate tradeDate, String status, String equity,
                                   LocalDateTime confirmedTime, long createdBy) throws SQLException {
        exec(engine, "INSERT INTO t_settle_cycle (id, sub_account_id, settle_no, trade_date,"
                        + " is_aggregate, status, close_pnl, float_pnl, commission, margin_occupied,"
                        + " available_fund, equity, negative_fund_flag, confirmed_by, confirmed_time,"
                        + " voided_flag, created_by)"
                        + " VALUES (?, ?, ?, ?, 'N', ?, 0, 0, 0, 0, 0, ?, 'N', ?, ?, 'N', ?)",
                id, subAccountId, settleNo, tradeDate, status, new BigDecimal(equity),
                createdBy, confirmedTime, createdBy);
    }

    /** 加总对账行（结论列载体；check_type=FUND）。 */
    public static void aggregationCheck(InMemoryDmEngine engine, long id, LocalDate tradeDate,
                                        String result, LocalDateTime checkTime, long createdBy)
            throws SQLException {
        exec(engine, "INSERT INTO t_aggregation_check (id, trade_date, check_type,"
                        + " sum_sub_account, real_account_value, difference, result,"
                        + " check_time, created_by)"
                        + " VALUES (?, ?, 'FUND', 0, 0, 0, ?, ?, ?)",
                id, tradeDate, result, checkTime, createdBy);
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
