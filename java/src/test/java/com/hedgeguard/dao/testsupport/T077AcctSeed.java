package com.hedgeguard.dao.testsupport;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code T-077} 套期会计服务单测的<b>最小播种件</b>（建在 {@code dao} 命名空间内，E5 纪律：
 * SQL 只允许出现在 {@code dao} 之下；{@code M2E2eSeed}/{@code M1E2eSeed} 同法）。
 *
 * <p><b>范围</b>：{@code m4_acct.sql} 建表段所引用的跨模块父表（{@code t_sub_account}/
 * {@code t_product}/{@code t_exposure}/{@code t_hedge_plan}/{@code t_hedge_doc}）
 * 的<b>最小列集建表 + 链路行</b>——父表全列由各模块脚本与 SchemaTest 锁定，本夹具不重复。
 * 行集：11 号计划（子账户 1，文档 21 三要素齐）/ 12 号计划（子账户 2，文档 22 缺要素③，
 * 作三要素门禁负例载体）。
 *
 * <p><b>引擎绑定陷阱（T-136 复核教训）</b>：等价引擎按绑定对象<b>原样存储</b>——
 * DATE 列须 {@link LocalDate}、TIMESTAMP 列须 {@link LocalDateTime}，传字符串读回时
 * {@code getDate}/{@code getTimestamp} 强转 {@code ClassCastException}。
 *
 * <p>本类不属于产品代码，仅测试夹具；不改变任何 {@code src/main} 行为。
 */
public final class T077AcctSeed {

    private T077AcctSeed() {
    }

    /** 建父表最小列集（等价引擎无 SET IDENTITY_INSERT，直接按等价语义建表）。 */
    public static void createParentTables(InMemoryDmEngine engine) {
        try {
            engine.executeScript(
                    "CREATE TABLE t_sub_account (id INT IDENTITY(1,1) NOT NULL,"
                            + " sub_account_code VARCHAR(32) NOT NULL, sub_account_name VARCHAR(128) NOT NULL,"
                            + " status VARCHAR(24) NOT NULL, category VARCHAR(16) NOT NULL,"
                            + " is_default CHAR(1) NOT NULL, disabled_reason VARCHAR(500), created_by BIGINT NOT NULL,"
                            + " CONSTRAINT pk_t_sub_account PRIMARY KEY (id));\n"
                            + "CREATE TABLE t_product (id BIGINT IDENTITY(1,1) NOT NULL,"
                            + " product_code VARCHAR(32) NOT NULL, product_name VARCHAR(128) NOT NULL,"
                            + " created_by BIGINT NOT NULL, CONSTRAINT pk_t_product PRIMARY KEY (id));\n"
                            + "CREATE TABLE t_exposure (id BIGINT IDENTITY(1,1) NOT NULL,"
                            + " sub_account_id INT NOT NULL, product_id BIGINT NOT NULL,"
                            + " spot_category_code VARCHAR(32) NOT NULL, direction VARCHAR(16) NOT NULL,"
                            + " calc_mode VARCHAR(32) NOT NULL, contract_unexecuted_qty DECIMAL(20,4) NOT NULL,"
                            + " inventory_qty DECIMAL(20,4) NOT NULL, production_plan_qty DECIMAL(20,4) NOT NULL,"
                            + " components_enabled VARCHAR(128) NOT NULL, snapshot_id VARCHAR(64) NOT NULL,"
                            + " snapshot_time TIMESTAMP NOT NULL, as_of_date DATE NOT NULL,"
                            + " calc_time TIMESTAMP NOT NULL, exposure_qty DECIMAL(20,4) NOT NULL,"
                            + " unit VARCHAR(16) NOT NULL, covered_qty DECIMAL(20,4) NOT NULL,"
                            + " status VARCHAR(24) NOT NULL, unmapped_flag CHAR(1) NOT NULL,"
                            + " created_by BIGINT NOT NULL, CONSTRAINT pk_t_exposure PRIMARY KEY (id));\n"
                            + "CREATE TABLE t_hedge_plan (id BIGINT IDENTITY(1,1) NOT NULL,"
                            + " sub_account_id INT NOT NULL, plan_no VARCHAR(32) NOT NULL,"
                            + " plan_type VARCHAR(16) NOT NULL, product_id BIGINT NOT NULL,"
                            + " hedge_direction VARCHAR(16) NOT NULL, plan_qty DECIMAL(20,4) NOT NULL,"
                            + " unit VARCHAR(16) NOT NULL, term_start_date DATE NOT NULL,"
                            + " term_end_date DATE NOT NULL, hedge_ratio DECIMAL(9,6) NOT NULL,"
                            + " ratio_method VARCHAR(16) NOT NULL, calc_base_date DATE NOT NULL,"
                            + " status VARCHAR(24) NOT NULL, executed_qty DECIMAL(20,4) NOT NULL,"
                            + " ordered_qty DECIMAL(20,4) NOT NULL, applicant_id BIGINT NOT NULL,"
                            + " current_doc_version INT NOT NULL, quota_occupied CHAR(1) NOT NULL,"
                            + " created_by BIGINT NOT NULL, CONSTRAINT pk_t_hedge_plan PRIMARY KEY (id),"
                            + " CONSTRAINT uk_t077_plan_no UNIQUE (plan_no));\n"
                            + "CREATE TABLE t_hedge_doc (id BIGINT IDENTITY(1,1) NOT NULL,"
                            + " sub_account_id INT NOT NULL, hedge_plan_id BIGINT NOT NULL,"
                            + " doc_no VARCHAR(32) NOT NULL, doc_version INT NOT NULL,"
                            + " is_current CHAR(1) NOT NULL, elem_formal_designation CLOB NOT NULL,"
                            + " elem_effectiveness_method CLOB NOT NULL, elem_rebalance_mechanism CLOB NOT NULL,"
                            + " hedged_item_exposure_id BIGINT NOT NULL, hedging_instrument VARCHAR(256) NOT NULL,"
                            + " pending_external_review CHAR(1) NOT NULL, generated_by BIGINT NOT NULL,"
                            + " generated_time TIMESTAMP NOT NULL, created_by BIGINT NOT NULL,"
                            + " CONSTRAINT pk_t_hedge_doc PRIMARY KEY (id),"
                            + " CONSTRAINT uk_t077_doc_no UNIQUE (doc_no));\n");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("父表建表失败：" + e.getMessage(), e);
        }
    }

    /** 播种链路行：子账户（含哨兵 0 保留行）/ 品种 / 敞口 21、22 / 计划 11、12 / 文档 21、22。 */
    public static void seedChains(InMemoryDmEngine engine) {
        exec(engine,
                "INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, status, category,"
                        + " is_default, disabled_reason, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                0, "__ALL_ACCOUNT__", "全账户（系统保留）", "DISABLED", "COMMODITY", "N", "系统保留行", 0L);
        exec(engine,
                "INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, status, category,"
                        + " is_default, disabled_reason, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                1, "SA-A", "商品子账户A", "ENABLED", "COMMODITY", "N", null, 0L);
        exec(engine,
                "INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, status, category,"
                        + " is_default, disabled_reason, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                2, "SA-B", "商品子账户B", "ENABLED", "COMMODITY", "N", null, 0L);
        exec(engine, "INSERT INTO t_product (id, product_code, product_name, created_by)"
                + " VALUES (?, ?, ?, ?)", 1L, "CU", "沪铜", 0L);
        for (long exposureId : new long[]{21L, 22L}) {
            exec(engine,
                    "INSERT INTO t_exposure (id, sub_account_id, product_id, spot_category_code,"
                            + " direction, calc_mode, contract_unexecuted_qty, inventory_qty,"
                            + " production_plan_qty, components_enabled, snapshot_id, snapshot_time,"
                            + " as_of_date, calc_time, exposure_qty, unit, covered_qty, status,"
                            + " unmapped_flag, created_by)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    exposureId, exposureId == 21L ? 1 : 2, 1L, "__MAPPED__", "SELL",
                    "CONTRACT_UNEXECUTED", new BigDecimal("100.0000"), new BigDecimal("0"),
                    new BigDecimal("0"), "CONTRACT_UNEXECUTED", "SNP-" + exposureId,
                    LocalDateTime.of(2026, 9, 1, 0, 0), LocalDate.of(2026, 9, 1),
                    LocalDateTime.of(2026, 9, 1, 0, 0), new BigDecimal("100.0000"), "TON",
                    new BigDecimal("0"), "PENDING_HEDGE", "N", 0L);
        }
        for (long planId : new long[]{11L, 12L}) {
            exec(engine,
                    "INSERT INTO t_hedge_plan (id, sub_account_id, plan_no, plan_type, product_id,"
                            + " hedge_direction, plan_qty, unit, term_start_date, term_end_date,"
                            + " hedge_ratio, ratio_method, calc_base_date, status, executed_qty,"
                            + " ordered_qty, applicant_id, current_doc_version, quota_occupied, created_by)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    planId, planId == 11L ? 1 : 2,
                    planId == 11L ? "HP202609010001" : "HP202609010002", "COMMODITY", 1L,
                    "BUY_HEDGE", new BigDecimal("100.0000"), "TON",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                    new BigDecimal("0.8"), "SIMPLE", LocalDate.of(2026, 9, 1), "APPROVED",
                    new BigDecimal("0"), new BigDecimal("0"), 100L, 1, "N", 0L);
        }
        // 文档 21：三要素齐备（正例载体）。row_version=1：生产中 PLAN 侧文档经 DAO 落库
        // （applyInsert 置 rv=1），T-078 再平衡须对该行做版本化更新 ⇒ 夹具按生产形态播种
        // （rv=0 的裸插行不可更新，属夹具失真非生产形态）。
        exec(engine,
                "INSERT INTO t_hedge_doc (id, sub_account_id, hedge_plan_id, doc_no, doc_version,"
                        + " is_current, elem_formal_designation, elem_effectiveness_method,"
                        + " elem_rebalance_mechanism, hedged_item_exposure_id, hedging_instrument,"
                        + " pending_external_review, generated_by, generated_time, created_by,"
                        + " row_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                21L, 1, 11L, "HD202609010001", 1, "Y", "正式指定：敞口21×cu2612",
                "定性经济关系法", "偏离>20%再平衡", 21L, "cu2612", "Y", 7L,
                LocalDateTime.of(2026, 9, 1, 9, 0), 7L, 1);
        // 文档 22：缺要素③的负例载体——生产表 elem_rebalance_mechanism NOT NULL 使「缺要素文档」
        // 结构上写不进库（DATA_MODEL TB-PLAN-4 备注：缺任一要素写不进库=数据库级保证），
        // 故服务层门禁的负例以「空串」构造（NOT NULL 允许空串；服务层 isBlank 判空 ⇒ 语义等价缺失）。
        exec(engine,
                "INSERT INTO t_hedge_doc (id, sub_account_id, hedge_plan_id, doc_no, doc_version,"
                        + " is_current, elem_formal_designation, elem_effectiveness_method,"
                        + " elem_rebalance_mechanism, hedged_item_exposure_id, hedging_instrument,"
                        + " pending_external_review, generated_by, generated_time, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                22L, 2, 12L, "HD202609010002", 1, "Y", "正式指定：敞口22×cu2601",
                "定性经济关系法", "", 22L, "cu2601", "Y", 7L,
                LocalDateTime.of(2026, 9, 1, 9, 0), 7L);
    }

    /** 用例级小步变更：把文档 22 补齐要素③（三要素门禁正例化）。 */
    public static void completeDocRebalance(InMemoryDmEngine engine) {
        exec(engine, "UPDATE t_hedge_doc SET elem_rebalance_mechanism = ?"
                + " WHERE id = ?", "偏离>20%再平衡", 22L);
    }

    /** 用例级小步变更：文档 21 置为历史版本（is_current='N'）+ 新增 v2 当前版本文档（id=23）。 */
    public static void promoteDocV2(InMemoryDmEngine engine) {
        exec(engine, "UPDATE t_hedge_doc SET is_current = ?, invalidated_time = ?"
                + " WHERE id = ?", "N", LocalDateTime.of(2026, 10, 13, 9, 0), 21L);
        exec(engine,
                "INSERT INTO t_hedge_doc (id, sub_account_id, hedge_plan_id, doc_no, doc_version,"
                        + " is_current, elem_formal_designation, elem_effectiveness_method,"
                        + " elem_rebalance_mechanism, hedged_item_exposure_id, hedging_instrument,"
                        + " pending_external_review, generated_by, generated_time, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                23L, 1, 11L, "HD202609010001-V2", 2, "Y", "v2指定", "v2方法",
                "v2再平衡", 21L, "cu2612", "Y", 7L,
                LocalDateTime.of(2026, 10, 13, 9, 0), 7L);
    }

    /** 用例级小步变更：预置一条旧编号套期关系（O-4 取号负例载体：HR0009）。 */
    public static void seedLegacyRelation(InMemoryDmEngine engine) {
        exec(engine,
                "INSERT INTO t_hedge_relation (id, sub_account_id, relation_no, hedge_plan_id,"
                        + " hedged_item_exposure_id, hedging_instrument_type, hedging_instrument_desc,"
                        + " hedge_doc_id, status, designated_time, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                90L, 1, "HR0009", 11L, 21L, "FUTURES", "旧关系", 21L, "DESIGNATED",
                LocalDateTime.of(2026, 9, 1, 9, 0), 7L);
    }

    /** 用例级小步变更：敞口量改写（T-078 触发场景构造；单表单列，不做联动）。 */
    public static void setExposureQty(InMemoryDmEngine engine, long exposureId, String qty) {
        exec(engine, "UPDATE t_exposure SET exposure_qty = ? WHERE id = ?",
                new BigDecimal(qty), exposureId);
    }

    /** 用例级小步变更：删除敞口行（T-078「敞口消失」场景构造）。 */
    public static void deleteExposure(InMemoryDmEngine engine, long exposureId) {
        exec(engine, "DELETE FROM t_exposure WHERE id = ?", exposureId);
    }

    /** 用例级小步变更：计划状态改写（T-078「计划修订/终止」场景构造）。 */
    public static void setPlanStatus(InMemoryDmEngine engine, long planId, String status) {
        exec(engine, "UPDATE t_hedge_plan SET status = ? WHERE id = ?", status, planId);
    }

    /** 用例级小步变更：文档要素③改写（T-078 三要素门禁负例构造；空串 = 语义缺失）。 */
    public static void setDocRebalanceMechanism(InMemoryDmEngine engine, long docId, String value) {
        exec(engine, "UPDATE t_hedge_doc SET elem_rebalance_mechanism = ? WHERE id = ?",
                value, docId);
    }

    /** 用例级小步变更：关系行乐观锁版本拨乱（T-078 整笔事务回滚构造）。 */
    public static void forceRelationRowVersion(InMemoryDmEngine engine, long relationId, int version) {
        exec(engine, "UPDATE t_hedge_relation SET row_version = ? WHERE id = ?", version, relationId);
    }

    /**
     * 用例级小步变更：预置一个占用指定版本号的<b>非当前</b>版本文档行（T-078 整笔事务
     * 回滚构造：使再平衡的新文档插入撞 {@code uk_hedge_doc_no}）。
     */
    public static void seedDocVersionConflict(InMemoryDmEngine engine, long planId,
                                              long exposureId, String docNo, int version, long id) {
        exec(engine,
                "INSERT INTO t_hedge_doc (id, sub_account_id, hedge_plan_id, doc_no, doc_version,"
                        + " is_current, elem_formal_designation, elem_effectiveness_method,"
                        + " elem_rebalance_mechanism, hedged_item_exposure_id, hedging_instrument,"
                        + " pending_external_review, generated_by, generated_time, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, 1, planId, docNo, version, "N", "占用版本号的冲突行",
                "定性经济关系法", "偏离>20%再平衡", exposureId, "cu2612", "Y", 7L,
                LocalDateTime.of(2026, 9, 2, 9, 0), 7L);
    }

    /** 用例级小步变更：预置旧编号再平衡记录（T-078 RB 取号负例载体：RB0009；父关系行 id=1 须先在）。 */
    public static void seedLegacyRebalance(InMemoryDmEngine engine) {
        // 父关系行（t_rebalance_record.hedge_relation_id 有 FK）
        exec(engine,
                "INSERT INTO t_hedge_relation (id, sub_account_id, relation_no, hedge_plan_id,"
                        + " hedged_item_exposure_id, hedging_instrument_type, hedging_instrument_desc,"
                        + " hedge_doc_id, status, designated_time, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                1L, 1, "HR0001", 11L, 21L, "FUTURES", "旧关系", 21L, "NEED_REBALANCE",
                LocalDateTime.of(2026, 9, 1, 9, 0), 7L);
        exec(engine,
                "INSERT INTO t_rebalance_record (id, sub_account_id, hedge_relation_id,"
                        + " rebalance_no, trigger_type, trigger_side, trigger_reason,"
                        + " trigger_threshold, ratio_before, ratio_after, qty_before, qty_after,"
                        + " new_hedge_doc_id, executor_id, rebalance_time, plan_feedback_flag,"
                        + " created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                90L, 1, 1L, "RB0009", "EXPOSURE_DELTA", "EXPOSURE_SIDE", "旧记录",
                new BigDecimal("0.200000"), new BigDecimal("0.800000"),
                new BigDecimal("0.800000"), new BigDecimal("100.0000"),
                new BigDecimal("100.0000"), 21L, 7L,
                LocalDateTime.of(2026, 9, 1, 9, 0), "N", 7L);
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
