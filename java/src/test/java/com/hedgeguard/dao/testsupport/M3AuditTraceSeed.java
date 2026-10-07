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
 * {@code T-067} 审计全链路追溯用例的最小播种件（追溯链中/下游：绑定 / 审批 / 指令 / 委托 / 成交 / 结算 / 留痕）。
 *
 * <p>与 {@link M2E2eSeed} 同法（E5：{@code dao} 之外不得出现 SQL），只在 {@code dao} 命名空间内直写内存引擎，
 * 上游 M1 段（子账户 / 品种 / 敞口 / 套保计划 / 交易计划）复用 {@link M2E2eSeed}。
 *
 * <p>本类不属产品代码，仅测试夹具；不改变任何 {@code src/main} 行为。
 */
public final class M3AuditTraceSeed {

    private M3AuditTraceSeed() {
    }

    /** 计划-敞口绑定行（{@code BOUND}）。 */
    public static void hedgePlanExposure(InMemoryDmEngine engine, long id, int subAccountId,
                                         long hedgePlanId, long exposureId, String coveredQty,
                                         LocalDateTime bindTime) throws SQLException {
        // 内存引擎把 {	t NULL} 字面量按字符串 'NULL' 求值 ⇒ 可空 TIMESTAMP 列必须以占位符绑真实 null。
        exec(engine, "INSERT INTO t_hedge_plan_exposure (id, sub_account_id, hedge_plan_id, exposure_id,"
                        + " covered_qty, bind_time, unbind_time, status, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'BOUND', ?)",
                id, subAccountId, hedgePlanId, exposureId, new BigDecimal(coveredQty), bindTime, null, 100L);
    }

    /** 审批节点（{@code APPROVE / APPROVED}）。 */
    public static void planApproval(InMemoryDmEngine engine, long id, int subAccountId, long hedgePlanId,
                                    int nodeSeq, LocalDateTime actionTime) throws SQLException {
        exec(engine, "INSERT INTO t_plan_approval (id, sub_account_id, hedge_plan_id, node_seq, node_type,"
                        + " applicant_id, approver_id, approval_result, action_time, created_by)"
                        + " VALUES (?, ?, ?, ?, 'APPROVE', 100, 200, 'APPROVED', ?, ?)",
                id, subAccountId, hedgePlanId, nodeSeq, actionTime, 200L);
    }

    /** 交易指令行。 */
    public static void tradeOrder(InMemoryDmEngine engine, long id, int subAccountId, String clientOrderRef,
                                  Long cppOrderId, long tradePlanId, long hedgePlanId, long exposureId,
                                  long productId, String instrumentId, String status,
                                  LocalDateTime submitTime) throws SQLException {
        exec(engine, "INSERT INTO t_trade_order (id, sub_account_id, client_order_ref, order_id,"
                        + " trade_plan_id, hedge_plan_id, exposure_id, product_id, instrument_id,"
                        + " instrument_type, direction, offset_flag, price_type, volume, operator_id,"
                        + " status, submit_time, client_ts_ms, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'FUTURES', 'BUY', 'OPEN', 'LIMIT', 10,"
                        + " 100, ?, ?, ?, ?)",
                id, subAccountId, clientOrderRef, cppOrderId, tradePlanId, hedgePlanId, exposureId,
                productId, instrumentId, status, submitTime,
                submitTime == null ? 0L
                        : submitTime.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                100L);
    }

    /** 委托（报单）回报行；{@code orderId} 为 {@code t_trade_order(id)}（本地主键）。 */
    public static void tradeOrderReport(InMemoryDmEngine engine, long id, int subAccountId, String orderSysId,
                                        long orderId, String instrumentId, String orderStatus, int volumeTotal,
                                        int volumeTraded, LocalDateTime reportTime) throws SQLException {
        exec(engine, "INSERT INTO t_trade_order_report (id, sub_account_id, order_sys_id, order_id,"
                        + " instrument_id, direction, offset_flag, order_status, volume_total,"
                        + " volume_traded, volume_left, report_ts_ms, report_time, event_seq, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, 'BUY', 'OPEN', ?, ?, ?, ?, 0, ?, 1, ?)",
                id, subAccountId, orderSysId, orderId, instrumentId, orderStatus, volumeTotal,
                volumeTraded, volumeTotal - volumeTraded, reportTime, 100L);
    }

    /** 成交行；{@code orderId} 为 {@code t_trade_order(id)}（本地主键，{@code TE-T058-004} 口径）。 */
    public static void tradeFill(InMemoryDmEngine engine, long id, int subAccountId, String tradeId,
                                 long orderId, long tradePlanId, long hedgePlanId, long exposureId,
                                 long productId, String instrumentId, String fillPrice, int fillVolume,
                                 LocalDate tradeDate, LocalDateTime fillTime) throws SQLException {
        exec(engine, "INSERT INTO t_trade_fill (id, sub_account_id, trade_id, order_id, order_sys_id,"
                        + " trade_plan_id, hedge_plan_id, exposure_id, product_id, instrument_id,"
                        + " instrument_type, direction, offset_flag, fill_price, fill_volume,"
                        + " trade_ts_ms, trade_date, fill_time, event_seq, settled_flag, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FUTURES', 'BUY', 'OPEN', ?, ?, 0, ?, ?, 1, 'N', ?)",
                id, subAccountId, tradeId, orderId, null, tradePlanId, hedgePlanId, exposureId, productId,
                instrumentId, new BigDecimal(fillPrice), fillVolume, tradeDate, fillTime, 100L);
    }

    /** 结算单（{@code CONFIRMED}）。 */
    public static void settleCycleConfirmed(InMemoryDmEngine engine, long id, int subAccountId, String settleNo,
                                            LocalDate tradeDate, LocalDateTime confirmedTime) throws SQLException {
        exec(engine, "INSERT INTO t_settle_cycle (id, sub_account_id, settle_no, trade_date, is_aggregate,"
                        + " status, close_pnl, float_pnl, commission, margin_occupied, available_fund,"
                        + " equity, negative_fund_flag, confirmed_by, confirmed_time, voided_flag, created_by)"
                        + " VALUES (?, ?, ?, ?, 'N', 'CONFIRMED', 0, 0, 0, 0, 0, 0, 'N', 200, ?, 'N', ?)",
                id, subAccountId, settleNo, tradeDate, confirmedTime, 100L);
    }

    /** 操作留痕行。 */
    public static void operationLog(InMemoryDmEngine engine, long id, Integer subAccountId, long operatorId,
                                    String operationType, String objectType, Long objectId, String objectNo,
                                    String actionResult, LocalDateTime operationTime) throws SQLException {
        exec(engine, "INSERT INTO t_operation_log (id, sub_account_id, operator_id, operation_type,"
                        + " object_type, object_id, object_no, action_result, result_detail,"
                        + " operation_time, client_ip, trace_id, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '127.0.0.1', 'T-1', ?)",
                id, subAccountId, operatorId, operationType, objectType, objectId, objectNo,
                actionResult, null, operationTime, 100L);
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
