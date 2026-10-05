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
 * {@code T-180} 事后风险报表用例用的<b>风险表内联 DDL 与播种件</b>（建在 {@code dao} 命名空间内）。
 *
 * <p><b>为何内联</b>：{@code docs/DEPLOY/schema/m3_risk.sql} 当前仅含 {@code TB-RISK-2}
 * {@code t_param_dispatch}；剩余 10 张 RISK 表（含本用例所需的 {@code t_post_risk_report} /
 * {@code t_risk_violation} / {@code t_limit_usage_stat}）由 {@code T-094} 收口。为在
 * {@code docs/**} 写域之外以<b>真实 DAO</b> 驱动集成测试，本类将 {@code DATA_MODEL.md} §2.8
 * TB-RISK-7/8/9 的建表口径按<b>列级</b>转录为内联 DDL（仅测试夹具；生产 DDL 归 {@code T-094}）。
 *
 * <p><b>裁剪披露（列级转录，非逐条全等）</b>：{@code t_risk_violation} 的三个<b>可空外键</b>
 * {@code trade_order_id} / {@code trade_fill_id} / {@code hedge_plan_id} —— <b>列本身</b>已转录，
 * 但其<b>父表外键约束</b>（{@code → t_trade_order} / {@code → t_trade_fill} / {@code → t_hedge_plan}）
 * <b>未</b>内联（父表不在本内联集）；该三约束归 {@code T-094} 生产 DDL。除此外，本内联 DDL 与
 * §2.8 所列列 / 约束逐条一致。
 *
 * <p><b>为何单独成类</b>：E5（{@code dao} 之外不得出现 SQL）对 {@code src/test} 同规则，
 * 故 SQL/DDL 一律落 {@code dao} 命名空间之下。本类不属于产品代码。
 */
public final class M3RiskReportDdl {

    private M3RiskReportDdl() {
    }

    /** {@code TB-RISK-7/8/9} 的内联 DDL（列级转录 {@code DATA_MODEL.md} §2.8；裁剪范围见类注释）。 */
    public static final String DDL =
            "CREATE TABLE t_post_risk_report ("
            + " id BIGINT IDENTITY(1,1) NOT NULL,"
            + " sub_account_id INT NULL,"
            + " report_date DATE NOT NULL,"
            + " report_type VARCHAR(32) NOT NULL,"
            + " content_json CLOB NOT NULL,"
            + " settle_reconcile_key VARCHAR(64) NULL,"
            + " generated_by BIGINT NOT NULL,"
            + " generated_time TIMESTAMP NOT NULL,"
            + " settle_cycle_id BIGINT NULL,"
            + " created_by BIGINT NOT NULL,"
            + " created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " updated_by BIGINT NULL,"
            + " updated_time TIMESTAMP NULL,"
            + " row_version INT DEFAULT 0 NOT NULL,"
            + " CONSTRAINT pk_t_post_risk_report PRIMARY KEY (id),"
            + " CONSTRAINT ck_post_risk_report_type CHECK (report_type IN"
            + " ('RISK_METRIC','VIOLATION_SUMMARY','LIMIT_USAGE')),"
            + " CONSTRAINT uk_post_risk_report UNIQUE (report_date, report_type, sub_account_id),"
            + " CONSTRAINT fk_post_risk_report_sub FOREIGN KEY (sub_account_id)"
            + " REFERENCES t_sub_account (id),"
            + " CONSTRAINT fk_post_risk_report_settle FOREIGN KEY (settle_cycle_id)"
            + " REFERENCES t_settle_cycle (id));"
            + "CREATE INDEX idx_post_risk_report_date ON t_post_risk_report (report_date, report_type);"
            + "CREATE INDEX idx_post_risk_report_settle ON t_post_risk_report (settle_cycle_id);"

            + "CREATE TABLE t_risk_violation ("
            + " id BIGINT IDENTITY(1,1) NOT NULL,"
            + " sub_account_id INT NOT NULL,"
            + " violation_date DATE NOT NULL,"
            + " violation_type VARCHAR(32) NOT NULL,"
            + " severity VARCHAR(16) NOT NULL,"
            + " rule_id VARCHAR(32) NULL,"
            + " trade_order_id BIGINT NULL,"
            + " trade_fill_id BIGINT NULL,"
            + " hedge_plan_id BIGINT NULL,"
            + " violation_desc VARCHAR(1000) NOT NULL,"
            + " trade_reason_text VARCHAR(1000) NULL,"
            + " source VARCHAR(24) NOT NULL,"
            + " occur_time TIMESTAMP NOT NULL,"
            + " created_by BIGINT NOT NULL,"
            + " created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " updated_by BIGINT NULL,"
            + " updated_time TIMESTAMP NULL,"
            + " row_version INT DEFAULT 0 NOT NULL,"
            + " CONSTRAINT pk_t_risk_violation PRIMARY KEY (id),"
            + " CONSTRAINT ck_risk_violation_type CHECK (violation_type IN"
            + " ('OVER_PRODUCT','OVER_SCALE','OVER_TERM','OVER_AUTH','FREQUENT_TRADE','DOC_MISSING',"
            + "'REASON_MISSING','RELATED_MISSING')),"
            + " CONSTRAINT ck_risk_violation_severity CHECK (severity IN ('LOW','MEDIUM','HIGH')),"
            + " CONSTRAINT ck_risk_violation_source CHECK (source IN"
            + " ('PRE_TRADE_REJECT','INTRADAY_ALERT','POST_AUDIT_SCREEN','MANUAL')),"
            + " CONSTRAINT fk_risk_violation_sub FOREIGN KEY (sub_account_id)"
            + " REFERENCES t_sub_account (id));"
            + "CREATE INDEX idx_risk_violation_sub_date ON t_risk_violation (sub_account_id, violation_date);"
            + "CREATE INDEX idx_risk_violation_type_date ON t_risk_violation (violation_type, violation_date);"
            + "CREATE INDEX idx_risk_violation_severity ON t_risk_violation (severity, violation_date);"
            + "CREATE INDEX idx_risk_violation_order ON t_risk_violation (trade_order_id);"

            + "CREATE TABLE t_limit_usage_stat ("
            + " id BIGINT IDENTITY(1,1) NOT NULL,"
            + " sub_account_id INT NOT NULL,"
            + " product_id BIGINT NOT NULL,"
            + " stat_date DATE NOT NULL,"
            + " limit_type VARCHAR(24) NOT NULL,"
            + " limit_value DECIMAL(20,4) NOT NULL,"
            + " used_value DECIMAL(20,4) NOT NULL,"
            + " usage_ratio DECIMAL(9,6) NOT NULL,"
            + " hint_level VARCHAR(16) NOT NULL,"
            + " hint_threshold_1 DECIMAL(9,6) NULL,"
            + " hint_threshold_2 DECIMAL(9,6) NULL,"
            + " created_by BIGINT NOT NULL,"
            + " created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,"
            + " updated_by BIGINT NULL,"
            + " updated_time TIMESTAMP NULL,"
            + " row_version INT DEFAULT 0 NOT NULL,"
            + " CONSTRAINT pk_t_limit_usage_stat PRIMARY KEY (id),"
            + " CONSTRAINT ck_limit_usage_limit_type CHECK (limit_type IN"
            + " ('PRODUCT_QUOTA','POSITION_LIMIT','SCALE_REDLINE','FUND_QUOTA','AUTH_QUOTA')),"
            + " CONSTRAINT ck_limit_usage_hint_level CHECK (hint_level IN"
            + " ('NORMAL','HINT','WARNING','EXCEEDED')),"
            + " CONSTRAINT ck_limit_usage_ratio_nonneg CHECK (usage_ratio >= 0),"
            + " CONSTRAINT uk_limit_usage_stat UNIQUE (stat_date, sub_account_id, product_id, limit_type),"
            + " CONSTRAINT fk_limit_usage_sub FOREIGN KEY (sub_account_id)"
            + " REFERENCES t_sub_account (id),"
            + " CONSTRAINT fk_limit_usage_product FOREIGN KEY (product_id)"
            + " REFERENCES t_product (id));"
            + "CREATE INDEX idx_limit_usage_sub_date ON t_limit_usage_stat (sub_account_id, stat_date);"
            + "CREATE INDEX idx_limit_usage_hint ON t_limit_usage_stat (hint_level, stat_date);"
            + "CREATE INDEX idx_limit_usage_product ON t_limit_usage_stat (product_id, stat_date);";

    /** 品种额度行（层① {@code SUB_PRODUCT}；子账户 × 品种）。 */
    public static void productQuota(InMemoryDmEngine engine, int subAccountId, long productId,
                                    String quotaLimit, String usedQty, String frozenQty,
                                    LocalDate effectiveFrom)
            throws SQLException {
        exec(engine, "INSERT INTO t_product_quota (sub_account_id, limit_layer, product_id, quota_limit,"
                        + " quota_unit, used_qty, frozen_qty, effective_from, dispatch_status, created_by)"
                        + " VALUES (?, 'SUB_PRODUCT', ?, ?, 'LOT', ?, ?, ?, 'SUCCESS', 0)",
                subAccountId, productId, new BigDecimal(quotaLimit), new BigDecimal(usedQty),
                new BigDecimal(frozenQty), effectiveFrom);
    }

    /** 事前拒绝记录行（{@code t_pre_trade_reject}）。 */
    public static void preTradeReject(InMemoryDmEngine engine, int subAccountId, long productId,
                                      String clientOrderRef, String ruleId, String rejectCode,
                                      String currentValue, String thresholdValue, LocalDate tradeDate)
            throws SQLException {
        exec(engine, "INSERT INTO t_pre_trade_reject (sub_account_id, client_order_ref, order_digest,"
                        + " product_id, instrument_id, direction, volume, rule_id, reject_code, limit_layer,"
                        + " current_value, threshold_value, base_price_source, reject_reason, operator_id,"
                        + " reject_ts_ms, reject_time, trade_date, event_seq, created_by)"
                        + " VALUES (?, ?, 'digest', ?, 'cu2612', 'BUY', 1, ?, ?, 'SUB_PRODUCT', ?, ?,"
                        + " 'NOT_APPLICABLE', 'pre-trade reject', 201, 0, ?, ?, 1, 0)",
                subAccountId, clientOrderRef, productId, ruleId, rejectCode, currentValue, thresholdValue,
                tradeDate.atStartOfDay(), tradeDate);
    }

    /** 结算单行（{@code t_settle_cycle}；{@code is_aggregate='N'}）。 */
    public static void settleCycle(InMemoryDmEngine engine, long id, int subAccountId, String settleNo,
                                   LocalDate tradeDate, String floatPnl, String equity)
            throws SQLException {
        exec(engine, "INSERT INTO t_settle_cycle (id, sub_account_id, settle_no, trade_date, is_aggregate,"
                        + " status, close_pnl, float_pnl, commission, margin_occupied, available_fund,"
                        + " equity, negative_fund_flag, voided_flag, created_by)"
                        + " VALUES (?, ?, ?, ?, 'N', 'GENERATED', 0, ?, 0, 0, 0, ?, 'N', 'N', 0)",
                id, subAccountId, settleNo, tradeDate, new BigDecimal(floatPnl), new BigDecimal(equity));
    }

    /** 他源违规事项行（如 {@code POST_AUDIT_SCREEN} 的文档缺失），供汇总合并。 */
    public static void existingViolation(InMemoryDmEngine engine, int subAccountId, LocalDate date,
                                         String violationType, String severity, String ruleId,
                                         String desc, String source) throws SQLException {
        exec(engine, "INSERT INTO t_risk_violation (sub_account_id, violation_date, violation_type,"
                        + " severity, rule_id, violation_desc, source, occur_time, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                subAccountId, date, violationType, severity, ruleId, desc, source, date.atStartOfDay());
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
