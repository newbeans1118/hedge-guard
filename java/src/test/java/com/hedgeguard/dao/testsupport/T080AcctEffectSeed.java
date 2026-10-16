package com.hedgeguard.dao.testsupport;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code T-080} 套保效果综合评估与双侧盈亏上报单测的<b>最小播种件</b>（建在 {@code dao}
 * 命名空间内，E5 纪律：SQL 只允许出现在 {@code dao} 之下；{@code T077AcctSeed} 同法）。
 *
 * <p><b>范围</b>：{@code m4_acct.sql} 的 {@code t_hedge_effect}/{@code t_dual_pnl_report}
 * 由脚本建；本夹具补三张跨模块父表的最小列集建表 + 链路行——
 * {@code t_hedge_plan_exposure}（{@code m1_plan}）、{@code t_settle_pnl}（{@code m2_settle}，
 * 衍生品盈亏数据源）、{@code t_spot_price}（{@code m1_spot}，实货盈亏价格数据源）。
 *
 * <p><b>行集</b>（配合 {@link T077AcctSeed#seedChains} 的 子账户 1/2、敞口 21/22、计划 11/12）：
 * 新增子账户 1 的计划 13 与敞口 23（量 50），BOUND 绑定 计划 11↔敞口 21（覆盖 100）与
 * 计划 13↔敞口 23（覆盖 50）——两明细行使「汇总 = 明细之和」成为非平凡恒等式；
 * 子账户 2 的敞口 22（量 100）绑定计划 12。
 *
 * <p><b>引擎绑定陷阱（T-136 复核教训）</b>：等价引擎按绑定对象原样存储——DATE 列须
 * {@link LocalDate}、TIMESTAMP 列须 {@link LocalDateTime}。
 *
 * <p>本类不属于产品代码，仅测试夹具；不改变任何 {@code src/main} 行为。
 */
public final class T080AcctEffectSeed {

    /** 效果计算日 = 子账户 1 敞口 21/23 所在期间（2026-10）期末。 */
    public static final LocalDate CALC_DATE = LocalDate.of(2026, 10, 31);

    private T080AcctEffectSeed() {
    }

    /** 建跨模块父表最小列集（{@code t_hedge_plan_exposure}/{@code t_settle_pnl}/{@code t_spot_price}）。 */
    public static void createEffectTables(InMemoryDmEngine engine) {
        try {
            engine.executeScript(
                    "CREATE TABLE t_hedge_plan_exposure ("
                            + " id BIGINT IDENTITY(1,1) NOT NULL, sub_account_id INT,"
                            + " hedge_plan_id BIGINT, exposure_id BIGINT, covered_qty DECIMAL(20,4),"
                            + " bind_time TIMESTAMP, unbind_time TIMESTAMP, status VARCHAR(24),"
                            + " created_by BIGINT, created_time TIMESTAMP, updated_by BIGINT,"
                            + " updated_time TIMESTAMP, row_version INT DEFAULT 0 NOT NULL,"
                            + " CONSTRAINT pk_t080_hpe PRIMARY KEY (id),"
                            + " CONSTRAINT uk_t080_hpe UNIQUE (hedge_plan_id, exposure_id),"
                            + " CONSTRAINT ck_t080_hpe_status CHECK (status IN ('BOUND','UNBOUND')));\n"
                            + "CREATE TABLE t_settle_pnl ("
                            + " id BIGINT IDENTITY(1,1) NOT NULL, sub_account_id INT,"
                            + " settle_cycle_id BIGINT, trade_date DATE, instrument_id VARCHAR(32),"
                            + " product_id BIGINT, hedge_plan_id BIGINT, pnl_type VARCHAR(24),"
                            + " amount DECIMAL(20,4), qty DECIMAL(20,4),"
                            + " created_by BIGINT, created_time TIMESTAMP, updated_by BIGINT,"
                            + " updated_time TIMESTAMP, row_version INT DEFAULT 0 NOT NULL,"
                            + " CONSTRAINT pk_t080_sp PRIMARY KEY (id),"
                            + " CONSTRAINT ck_t080_sp_type CHECK (pnl_type IN ('CLOSE_REALIZED',"
                            + " 'FLOAT_UNREALIZED','COMMISSION','MARGIN_CHANGE')));\n"
                            + "CREATE TABLE t_spot_price ("
                            + " id BIGINT IDENTITY(1,1) NOT NULL, sub_account_id INT,"
                            + " product_id BIGINT, price_date DATE, spot_price DECIMAL(20,6),"
                            + " source_type VARCHAR(24), source_desc VARCHAR(500),"
                            + " import_file_name VARCHAR(256), operator_id BIGINT, entry_time TIMESTAMP,"
                            + " is_shared CHAR(1) DEFAULT 'Y' NOT NULL,"
                            + " overwritten_price DECIMAL(20,6),"
                            + " overwrite_confirmed CHAR(1) DEFAULT 'N' NOT NULL, price_unit VARCHAR(16),"
                            + " created_by BIGINT, created_time TIMESTAMP, updated_by BIGINT,"
                            + " updated_time TIMESTAMP, row_version INT DEFAULT 0 NOT NULL,"
                            + " CONSTRAINT pk_t080_spr PRIMARY KEY (id),"
                            + " CONSTRAINT uk_t080_spr UNIQUE (product_id, price_date, is_shared));\n");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("T-080 父表建表失败：" + e.getMessage(), e);
        }
    }

    /** 播种链路行：子账户 1 的第二计划 13 + 敞口 23；三条 BOUND 绑定；衍生品盈亏与现货价行。 */
    public static void seedEffectChains(InMemoryDmEngine engine) {
        // 计划 13（子账户 1，品种 1）——使子账户 1 有两条明细行（非平凡汇总恒等式：汇总=明细之和）
        exec(engine,
                "INSERT INTO t_hedge_plan (id, sub_account_id, plan_no, plan_type, product_id,"
                        + " hedge_direction, plan_qty, unit, term_start_date, term_end_date,"
                        + " hedge_ratio, ratio_method, calc_base_date, status, executed_qty,"
                        + " ordered_qty, applicant_id, current_doc_version, quota_occupied, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                13L, 1, "HP202609010003", "COMMODITY", 2L, "BUY_HEDGE",
                new BigDecimal("50.0000"), "TON", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 31), new BigDecimal("0.8"), "SIMPLE",
                LocalDate.of(2026, 9, 1), "APPROVED", new BigDecimal("0"),
                new BigDecimal("0"), 100L, 1, "N", 0L);
        // 品种 2（沪铝）——计划 13/敞口 23 的品种（使子账户 1 两条绑定分属不同品种 ⇒ 生产形态下
        // 结算盈亏可按品种经绑定敞口唯一归集，见 settlePnl 计划号 NULL 播种）
        exec(engine, "INSERT INTO t_product (id, product_code, product_name, created_by)"
                + " VALUES (?, ?, ?, ?)", 2L, "AL", "沪铝", 0L);
        // 敞口 23（子账户 1，品种 2，量 50）
        exec(engine,
                "INSERT INTO t_exposure (id, sub_account_id, product_id, spot_category_code,"
                        + " direction, calc_mode, contract_unexecuted_qty, inventory_qty,"
                        + " production_plan_qty, components_enabled, snapshot_id, snapshot_time,"
                        + " as_of_date, calc_time, exposure_qty, unit, covered_qty, status,"
                        + " unmapped_flag, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                23L, 1, 2L, "__MAPPED__", "SELL", "CONTRACT_UNEXECUTED",
                new BigDecimal("50.0000"), new BigDecimal("0"), new BigDecimal("0"),
                "CONTRACT_UNEXECUTED", "SNP-23", LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDate.of(2026, 9, 1), LocalDateTime.of(2026, 9, 1, 0, 0),
                new BigDecimal("50.0000"), "TON", new BigDecimal("0"), "PENDING_HEDGE", "N", 0L);
        // BOUND 绑定：子账户 1 计划 11 ↔ 敞口 21（覆盖 100）/ 计划 13 ↔ 敞口 23（覆盖 50）；
        // 子账户 2 计划 12 ↔ 敞口 22（覆盖 100）
        binding(engine, 1L, 1, 11L, 21L, "100.0000");
        binding(engine, 2L, 1, 13L, 23L, "50.0000");
        binding(engine, 3L, 2, 12L, 22L, "100.0000");
        // 衍生品盈亏（t_settle_pnl）：<b>生产撰写形态</b>——hedge_plan_id = NULL（SettleService
        // .insertTranscribedPnl:923 对 CLOSE/FLOAT 行恒写 NULL），按 (sub_account_id, product_id)
        // 经绑定敞口归集（TE-T080-001 同族教训：夹具必须与生产写入同构）。品种 1 行归集计划 11、
        // 品种 2 行归集计划 13、子账户 2 品种 1 行归集计划 12。
        settlePnl(engine, 1L, 1, LocalDate.of(2026, 10, 15), null, 1L, "CLOSE_REALIZED", "-8000.0000", 7L);
        settlePnl(engine, 2L, 1, LocalDate.of(2026, 10, 15), null, 1L, "FLOAT_UNREALIZED", "-250.0000", 7L);
        settlePnl(engine, 3L, 1, LocalDate.of(2026, 10, 15), null, 2L, "CLOSE_REALIZED", "-2000.0000", 7L);
        settlePnl(engine, 4L, 1, LocalDate.of(2026, 10, 15), null, 2L, "FLOAT_UNREALIZED", "-100.0000", 7L);
        settlePnl(engine, 5L, 2, LocalDate.of(2026, 10, 15), null, 1L, "CLOSE_REALIZED", "-4000.0000", 7L);
        settlePnl(engine, 6L, 2, LocalDate.of(2026, 10, 15), null, 1L, "FLOAT_UNREALIZED", "-100.0000", 7L);
        // 现货价：品种 1/品种 2 于子账户 1 期间期末 2026-10-31 当日价 110（三态①：当日价）
        spotPrice(engine, 1L, 1, LocalDate.of(2026, 10, 31), "110.000000", 7L);
        spotPrice(engine, 2L, 2, LocalDate.of(2026, 10, 31), "110.000000", 7L);
    }

    /**
     * 用例级：把品种 1 的现货价改为<b>仅历史时点</b>（2026-10-20，价 105），从而期末当日无价
     * （三态②：非当日价）。
     *
     * <p><b>为何用 UPDATE 而非 DELETE</b>：{@code InMemoryDmEngine} 的 {@code DELETE ... WHERE}
     * 对绑定参数不求值（实测 affected=0；字面量形态抛「无法求值的 WHERE 原子」），故以 UPDATE
     * 改写行而非删行（等价可观察效果）。
     */
    public static void onlyHistoricalPrice(InMemoryDmEngine engine) {
        exec(engine, "UPDATE t_spot_price SET price_date = ?, spot_price = ? WHERE id = ?",
                LocalDate.of(2026, 10, 20), new BigDecimal("105.000000"), 1L);
        exec(engine, "UPDATE t_spot_price SET price_date = ?, spot_price = ? WHERE id = ?",
                LocalDate.of(2026, 10, 20), new BigDecimal("105.000000"), 2L);
    }

    /** 用例级：把品种 1/2 的现货价移到期末之后（2027-01-31）⇒ 期末无当日价且无「≤期末」历史价（三态③）。 */
    public static void noPriceAtAll(InMemoryDmEngine engine) {
        exec(engine, "UPDATE t_spot_price SET price_date = ? WHERE id = ?",
                LocalDate.of(2027, 1, 31), 1L);
        exec(engine, "UPDATE t_spot_price SET price_date = ? WHERE id = ?",
                LocalDate.of(2027, 1, 31), 2L);
    }

    /**
     * 用例级：插入一条<b>计划号直配</b>（层①）结算盈亏行——子账户 1、计划 11、品种 1、已实现 −300
     * （使计划 11 明细衍生品由 −8250 → −8550，用于验证层①）。
     */
    public static void planLinkedSettlePnl(InMemoryDmEngine engine) {
        settlePnl(engine, 8L, 1, LocalDate.of(2026, 10, 15), 11L, 1L, "CLOSE_REALIZED",
                "-300.0000", 7L);
    }

    /**
     * 用例级：插入一条<b>不可归集</b>（层③）结算盈亏行——子账户 1、品种 3（无任何绑定敞口）、
     * 已实现 −500（使残余 ≠ 0，触发未关联计划残余行）。
     */
    public static void unattributableSettlePnl(InMemoryDmEngine engine) {
        settlePnl(engine, 9L, 1, LocalDate.of(2026, 10, 15), null, 3L, "CLOSE_REALIZED",
                "-500.0000", 7L);
    }

    /**
     * 用例级：把层③ 不可归集行（id=9，品种 3）改归品种 1 ⇒ 变为可按品种归集到计划 11，
     * 残余归零（用于验证未关联残余行在残余归零时被摘除——不得残留致 Σ明细 ≠ 汇总）。
     */
    public static void reattributeResidualToProduct1(InMemoryDmEngine engine) {
        exec(engine, "UPDATE t_settle_pnl SET product_id = ? WHERE id = ?", 1L, 9L);
    }

    // ------------------------------------------------------------------ 行级播种辅助

    private static void binding(InMemoryDmEngine engine, long id, int subAccountId,
                                long planId, long exposureId, String coveredQty) {
        exec(engine,
                "INSERT INTO t_hedge_plan_exposure (id, sub_account_id, hedge_plan_id, exposure_id,"
                        + " covered_qty, bind_time, status, created_by, row_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, subAccountId, planId, exposureId, new BigDecimal(coveredQty),
                LocalDateTime.of(2026, 9, 1, 9, 0), "BOUND", 7L, 1);
    }

    private static void settlePnl(InMemoryDmEngine engine, long id, int subAccountId,
                                   LocalDate tradeDate, Long planId, long productId, String pnlType,
                                   String amount, long operatorId) {
        String instrument = productId == 2L ? "al2701" : "cu2612";
        exec(engine,
                "INSERT INTO t_settle_pnl (id, sub_account_id, settle_cycle_id, trade_date,"
                        + " instrument_id, product_id, hedge_plan_id, pnl_type, amount, qty,"
                        + " created_by, row_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, subAccountId, 900L + id, tradeDate, instrument, productId, planId, pnlType,
                new BigDecimal(amount), new BigDecimal("1.0000"), operatorId, 1);
    }

    private static void spotPrice(InMemoryDmEngine engine, long id, long productId,
                                  LocalDate priceDate, String price, long operatorId) {
        exec(engine,
                "INSERT INTO t_spot_price (id, sub_account_id, product_id, price_date, spot_price,"
                        + " source_type, operator_id, entry_time, is_shared, overwrite_confirmed,"
                        + " price_unit, created_by, row_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, 1, productId, priceDate, new BigDecimal(price), "MANUAL", operatorId,
                LocalDateTime.of(2026, 9, 1, 9, 0), "Y", "N", "TON", operatorId, 1);
    }

    /** 通用执行（测试夹具专用）。 */
    public static void exec(InMemoryDmEngine engine, String sql, Object... args) {
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("播种失败：" + e.getMessage(), e);
        }
    }
}
