package com.hedgeguard.dao.testsupport;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * {@code T-111} 敞口推导引擎验收种子（{@code FR-SPOT-16} 验收 1/2/3；仅 {@code src/test}）。
 *
 * <p>承载 {@code SpotT111ExposureRecomputeTest} 的建数 SQL（SQL 只允许出现在 dao 包，
 * {@code E5} 判据；与 {@code T066RiskRecomputeSeed} 同形态）。表结构取自
 * {@code docs/DEPLOY/schema/m1_*.sql} 真实 DDL，由用例先装载再播种。
 */
public final class T111SpotRecomputeSeed {

    private T111SpotRecomputeSeed() {
    }

    /**
     * 在引擎上执行一条更新语句。
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

    /** {@link Instant} → 北京时间 {@link LocalDateTime}（{@code Clocks.ZONE}）。 */
    public static LocalDateTime at(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"));
    }

    /** 采购合同（{@code t_purchase_contract}；{@code ACTIVE}、BUY、已映射）。 */
    public static void purchaseContract(InMemoryDmEngine engine, long id, int subAccountId,
                                        String contractNo, long productId, LocalDate start,
                                        LocalDate end) throws SQLException {
        exec(engine, "INSERT INTO t_purchase_contract (id, sub_account_id, contract_no,"
                        + " spot_category_code, product_id, total_qty, executed_qty,"
                        + " contract_start_date, contract_end_date, exposure_direction, status,"
                        + " row_version, created_by)"
                        + " VALUES (?, ?, ?, '__MAPPED__', ?, 100, 0, ?, ?, 'BUY', 'ACTIVE', 1, 0)",
                id, subAccountId, contractNo, productId, start, end);
    }

    /** 品种保留行 id=0（DC-041 未映射哨兵外键载体；同 data-init.sql 形态）。 */
    public static void reservedProductZero(InMemoryDmEngine engine) throws SQLException {
        exec(engine, "INSERT INTO t_product (id, product_code, product_name, category,"
                        + " category_managed, status, is_domestic_exchange, currency, created_by)"
                        + " VALUES (0, '__UNMAPPED__', '未映射（系统保留）', 'COMMODITY', 'Y',"
                        + " 'ENABLED', 'Y', 'CNY', 0)");
    }

    /** 敞口头（已映射、BUY、COMBINED、基准日 asOf、数量=Σ三分量）。 */
    public static void exposureHead(InMemoryDmEngine engine, long id, int subAccountId,
                                    long productId, String contractQty, String inventoryQty,
                                    String planQty, LocalDate asOf, LocalDateTime snapshotTime)
            throws SQLException {
        BigDecimal sum = new BigDecimal(contractQty).add(new BigDecimal(inventoryQty))
                .add(new BigDecimal(planQty));
        exec(engine, "INSERT INTO t_exposure (id, sub_account_id, product_id, spot_category_code,"
                        + " direction, calc_mode, contract_unexecuted_qty, inventory_qty,"
                        + " production_plan_qty, components_enabled, snapshot_id, snapshot_time,"
                        + " as_of_date, calc_time, exposure_qty, unit, covered_qty, status,"
                        + " unmapped_flag, row_version, created_by)"
                        + " VALUES (?, ?, ?, '__MAPPED__', 'BUY', 'COMBINED', ?, ?, ?,"
                        + " 'CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN', ?, ?, ?, ?, ?, 'TON',"
                        + " 0, 'PENDING_HEDGE', 'N', 1, 0)",
                id, subAccountId, productId, new BigDecimal(contractQty),
                new BigDecimal(inventoryQty), new BigDecimal(planQty), "SNP-" + id,
                snapshotTime, asOf, snapshotTime, sum);
    }

    /** 构成明细（方向 BUY、基准日同敞口头；子账户随敞口头冗余）。 */
    public static void detail(InMemoryDmEngine engine, long id, long exposureId, int subAccountId,
                              String componentType, String sourceType, long sourceId,
                              String qty, LocalDate asOf) throws SQLException {
        exec(engine, "INSERT INTO t_exposure_detail (id, sub_account_id, exposure_id,"
                        + " component_type, source_type, source_id, contributed_qty, unit,"
                        + " direction, calc_as_of_date, row_version, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'TON', 'BUY', ?, 1, 0)",
                id, subAccountId, exposureId, componentType, sourceType, sourceId,
                new BigDecimal(qty), asOf);
    }

    /** 计划-敞口绑定行（{@code t_hedge_plan_exposure}；验收 2 反查拒因用）。 */
    public static void planBinding(InMemoryDmEngine engine, long planId, long exposureId,
                                   int subAccountId, String coveredQty,
                                   LocalDateTime bindTime) throws SQLException {
        exec(engine, "INSERT INTO t_hedge_plan_exposure (hedge_plan_id, exposure_id, covered_qty,"
                        + " status, bind_time, sub_account_id, row_version, created_by)"
                        + " VALUES (?, ?, ?, 'BOUND', ?, ?, 1, 0)",
                planId, exposureId, new BigDecimal(coveredQty), bindTime, subAccountId);
    }

    /**
     * 敞口头分量/合计校准（减量分支：敞口先对齐 Σ明细=140）。
     * ⚠️ SET 一律走 {@code ?} 绑定——引擎对非 {@code ?} 右值按「自增表达式」求值，字面量会失真。
     */
    public static void recalibrateExposure(InMemoryDmEngine engine, long exposureId)
            throws SQLException {
        exec(engine, "UPDATE t_exposure SET contract_unexecuted_qty = ?, inventory_qty = ?,"
                + " production_plan_qty = ?, exposure_qty = ? WHERE id = ?",
                new BigDecimal("30"), new BigDecimal("90"), new BigDecimal("20"),
                new BigDecimal("140"), exposureId);
    }

    /** 敞口行改判未映射态（DC-041 哨兵 0 + 真实品类）。 */
    public static void markUnmapped(InMemoryDmEngine engine, long exposureId, String category)
            throws SQLException {
        exec(engine, "UPDATE t_exposure SET product_id = ?, unmapped_flag = ?,"
                + " spot_category_code = ? WHERE id = ?", 0L, "Y", category, exposureId);
    }

    /** 敞口行置覆盖态（验收 2 前置）。 */
    public static void markCovered(InMemoryDmEngine engine, long exposureId)
            throws SQLException {
        exec(engine, "UPDATE t_exposure SET covered_qty = ?, status = ? WHERE id = ?",
                new BigDecimal("50"), "PARTIALLY_COVERED", exposureId);
    }

    /** 套保计划行（{@code t_hedge_plan}；绑定外键载体，{@code APPROVED}）。 */
    public static void hedgePlan(InMemoryDmEngine engine, long id, int subAccountId,
                                 long productId, String planNo, LocalDate asOf)
            throws SQLException {
        exec(engine, "INSERT INTO t_hedge_plan (id, sub_account_id, plan_no, plan_type,"
                        + " product_id, hedge_direction, plan_qty, unit, term_start_date,"
                        + " term_end_date, hedge_ratio, ratio_method, calc_base_date, status,"
                        + " executed_qty, ordered_qty, applicant_id, current_doc_version,"
                        + " extend_basis, quota_occupied, row_version, created_by)"
                        + " VALUES (?, ?, ?, 'COMMODITY', ?, 'BUY_HEDGE', 50, 'TON', ?, ?,"
                        + " 0.8, 'SIMPLE', ?, 'APPROVED', 0, 0, 100, 0, ?, 'N', 1, 0)",
                id, subAccountId, planNo, productId, asOf.minusDays(1), asOf.plusDays(30),
                asOf, "CFG-03");
    }

    /** 构成明细改挂（重算路径核心写入；供事务内探针复用）。 */
    public static void retargetDetail(InMemoryDmEngine engine, long detailId, long exposureId,
                                      int subAccountId) throws SQLException {
        exec(engine, "UPDATE t_exposure_detail SET exposure_id = ?, sub_account_id = ?"
                + " WHERE id = ?", exposureId, subAccountId, detailId);
    }

    /** 敞口行改快照标识（M2 击杀器：uk_exposure_snapshot 冲突注入）。 */
    public static void overrideSnapshot(InMemoryDmEngine engine, long exposureId,
                                        String snapshotId) throws SQLException {
        exec(engine, "UPDATE t_exposure SET snapshot_id = ? WHERE id = ?",
                snapshotId, exposureId);
    }
}
