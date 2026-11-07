package com.hedgeguard.dao.testsupport;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code T-133} 零覆盖守卫承重验收种子（仅 {@code src/test}）。
 *
 * <p>承载 {@code SpotT133GuardBearingTest} 的建数 SQL（SQL 只允许出现在 {@code dao} 包，
 * {@code E5} 判据；与 {@code T111SpotRecomputeSeed} 同形态）。纪律：
 * <ul>
 *   <li>列一律<b>显式给全</b>——{@code InMemoryDmEngine} 不执行 DDL {@code DEFAULT}；</li>
 *   <li>{@code row_version} 一律 {@code >= 1}；</li>
 *   <li><b>可空列一律走 {@code ?} + {@code null} 绑定，禁用字面量 {@code NULL}</b>——
 *       引擎 {@code parseLiteral} 不识别 {@code NULL}，会把它当字符串 {@code "NULL"} 存入，
 *       随后 {@code getBigDecimal/getTimestamp} 抛 {@code NumberFormatException}
 *       （本单实测发现的引擎陷阱，已回报；勿在种子中重犯）。</li>
 * </ul>
 */
public final class T133SpotGuardSeed {

    private T133SpotGuardSeed() {
    }

    /**
     * 在引擎上执行一条写入语句。
     *
     * @param engine 内存假 JDBC 引擎
     * @param sql    SQL（占位符）
     * @param args   参数
     * @throws SQLException 写入失败
     */
    public static void exec(InMemoryDmEngine engine, String sql, Object... args)
            throws SQLException {
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /** 现货价行（{@code t_spot_price}；{@code is_shared='Y'} 全账户共享，{@code overwrite_confirmed='N'}）。 */
    public static void spotPrice(InMemoryDmEngine engine, long id, int subAccountId, long productId,
                                 LocalDate priceDate, String price, String sourceType,
                                 String sourceDesc, String priceUnit, LocalDateTime entryTime)
            throws SQLException {
        exec(engine, "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date,"
                        + " spot_price, source_type, source_desc, import_file_name, operator_id,"
                        + " entry_time, is_shared, overwritten_price, overwrite_confirmed,"
                        + " price_unit, created_by, row_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'Y', ?, 'N', ?, 0, 1)",
                id, subAccountId, productId, priceDate, new BigDecimal(price), sourceType,
                sourceDesc, null, entryTime, null, priceUnit);
    }

    /** 销售合同行（{@code t_sales_contract}；{@code ACTIVE}、{@code SELL}）。 */
    public static void salesContract(InMemoryDmEngine engine, long id, int subAccountId,
                                     String contractNo, long productId, LocalDate start,
                                     LocalDate end) throws SQLException {
        exec(engine, "INSERT INTO t_sales_contract (id, sub_account_id, contract_no,"
                        + " spot_category_code, product_id, customer_name, total_qty, delivered_qty,"
                        + " pricing_mode, contract_start_date, contract_end_date, exposure_direction,"
                        + " has_dual_direction, buy_direction_qty, status, void_reason, closed_time,"
                        + " created_by, row_version)"
                        + " VALUES (?, ?, ?, '__MAPPED__', ?, ?, 100, 0, ?, ?, ?, 'SELL',"
                        + " 'N', ?, 'ACTIVE', ?, ?, 0, 1)",
                id, subAccountId, contractNo, productId, null, null, start, end, null, null, null);
    }

    /** 生产计划行（{@code t_production_plan}；{@code SCHEDULED}、不重叠）。 */
    public static void productionPlan(InMemoryDmEngine engine, long id, int subAccountId,
                                      String planNo, LocalDate start, LocalDate end)
            throws SQLException {
        exec(engine, "INSERT INTO t_production_plan (id, sub_account_id, plan_no,"
                        + " plan_period_start, plan_period_end, status, overlap_flag, closed_time,"
                        + " created_by, row_version)"
                        + " VALUES (?, ?, ?, ?, ?, 'SCHEDULED', 'N', ?, 0, 1)",
                id, subAccountId, planNo, start, end, null);
    }
}
