package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.entity.AccountingPeriod;
import com.hedgeguard.dao.entity.Cas24Selfcheck;
import com.hedgeguard.dao.entity.Cas24SelfcheckItem;
import com.hedgeguard.dao.entity.DualPnlReport;
import com.hedgeguard.dao.entity.EffectivenessAssess;
import com.hedgeguard.dao.entity.HedgeEffect;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.dao.entity.PeriodCloseLog;
import com.hedgeguard.dao.entity.RebalanceRecord;
import com.hedgeguard.dao.entity.Voucher;
import com.hedgeguard.dao.entity.VoucherLine;
import com.hedgeguard.dao.entity.VoucherReview;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACCT 模块 12 个 DAO 实现的两类机器可复现证据（T-076 验收 4 + 验收 2 的行为面）。
 * 形态先例：{@code M2SettleDaoMappingAndWriteTest}（T-044）。
 *
 * <ol>
 *   <li><b>列映射一致性（逐序）</b>：每个 ACCT 域 Dm*Dao 的 businessColumns() 与建表脚本
 *       m4_acct.sql 的业务列清单逐序一致（同时保证 bindBusiness/readBusiness 不错位）。</li>
 *   <li><b>写入/查询等价执行</b>：以 InMemoryDmEngine 建表（本环境无 DM8 实例），由真实 DAO
 *       生成 SQL 并执行：凭证草稿插入/读回（含 voucher_type=EXERCISE）、
 *       t_voucher.sub_account_id 由数据库层 NOT NULL 强制（缺子账户被拒，R-ACCT-5 / FR-ACCT-6 验收 2）、
 *       uk_voucher_no 唯一约束、t_effectiveness_assess/t_rebalance_record/t_period_close_log
 *       只增不改（updateById 显式拒绝）、分录 amount &gt; 0 CHECK、
 *       spot_pnl_column_present 恒 Y CHECK、待外部复核恒 Y CHECK、乐观锁更新。</li>
 * </ol>
 */
class M4AcctDaoMappingAndWriteTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m4_acct.sql";
    private static final List<String> AUDIT = Arrays.asList(
            "id", "created_by", "created_time", "updated_by", "updated_time", "row_version");

    private static final List<String> DAO_CLASSES = Arrays.asList(
            "DmHedgeRelationDao", "DmEffectivenessAssessDao", "DmRebalanceRecordDao",
            "DmVoucherDao", "DmVoucherLineDao", "DmAccountingPeriodDao", "DmPeriodCloseLogDao",
            "DmHedgeEffectDao", "DmDualPnlReportDao", "DmVoucherReviewDao",
            "DmCas24SelfcheckDao", "DmCas24SelfcheckItemDao");

    private InMemoryDmEngine engine;
    private DataSourcePool pool;
    private TxTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        // 本单只喂 m4_acct.sql：跨模块父表（t_sub_account / t_hedge_plan / t_exposure / t_hedge_doc）
        // 缺失，引擎对缺失引用表跳过外键校验（模块内 FK 仍强制）；跨模块外键的存在性由
        // M4AcctSchemaTest 静态核对（先例：FundDm8EquivalentIntegrationTest 同口径）。
        // 播种用例改在引擎上直接建父表最小列集（seedParentTables），满足模块内 FK 与 NOT NULL 面。
        Path file = repoRoot().resolve(SCRIPT);
        assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
        engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        seedParentTables();
        pool = new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2));
        tx = new TxTemplate(pool);
    }

    /** 跨模块父表的最小列集建表 + 保留行（仅测试夹具；父表全列由各模块脚本与 SchemaTest 锁定）。 */
    private void seedParentTables() throws Exception {
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
                        + " CONSTRAINT uk_seed_plan_no UNIQUE (plan_no));\n"
                        + "CREATE TABLE t_hedge_doc (id BIGINT IDENTITY(1,1) NOT NULL,"
                        + " sub_account_id INT NOT NULL, hedge_plan_id BIGINT NOT NULL,"
                        + " doc_no VARCHAR(32) NOT NULL, doc_version INT NOT NULL,"
                        + " is_current CHAR(1) NOT NULL, elem_formal_designation CLOB NOT NULL,"
                        + " elem_effectiveness_method CLOB NOT NULL, elem_rebalance_mechanism CLOB NOT NULL,"
                        + " hedged_item_exposure_id BIGINT NOT NULL, hedging_instrument VARCHAR(256) NOT NULL,"
                        + " pending_external_review CHAR(1) NOT NULL, generated_by BIGINT NOT NULL,"
                        + " generated_time TIMESTAMP NOT NULL, created_by BIGINT NOT NULL,"
                        + " CONSTRAINT pk_t_hedge_doc PRIMARY KEY (id),"
                        + " CONSTRAINT uk_seed_doc_no UNIQUE (doc_no));\n");
        // 保留系统行 id=0（哨兵载体）+ 用例行
        exec("INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, status, category,"
                + " is_default, disabled_reason, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                0, "__ALL_ACCOUNT__", "全账户（系统保留）", "DISABLED", "COMMODITY", "N",
                "系统保留行", 0L);
        exec("INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, status, category,"
                + " is_default, disabled_reason, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                1, "SA-A", "商品子账户A", "ENABLED", "COMMODITY", "N", null, 0L);
        exec("INSERT INTO t_product (id, product_code, product_name, created_by) VALUES (?, ?, ?, ?)",
                1L, "CU", "沪铜", 0L);
        exec("INSERT INTO t_exposure (id, sub_account_id, product_id, spot_category_code, direction,"
                + " calc_mode, contract_unexecuted_qty, inventory_qty, production_plan_qty,"
                + " components_enabled, snapshot_id, snapshot_time, as_of_date, calc_time,"
                + " exposure_qty, unit, covered_qty, status, unmapped_flag, created_by)"
                + " VALUES (?, ?, ?, '__MAPPED__', 'SELL', 'CONTRACT_UNEXECUTED', ?, 0, 0,"
                + " 'CONTRACT_UNEXECUTED', ?, ?, ?, ?, ?, 'TON', 0, 'PENDING_HEDGE', 'N', ?)",
                21L, 1, 1L, new BigDecimal("100.0000"), "SNP-21",
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDate.of(2026, 9, 1),
                LocalDateTime.of(2026, 9, 1, 0, 0), new BigDecimal("100.0000"), 0L);
        exec("INSERT INTO t_hedge_plan (id, sub_account_id, plan_no, plan_type, product_id,"
                + " hedge_direction, plan_qty, unit, term_start_date, term_end_date, hedge_ratio,"
                + " ratio_method, calc_base_date, status, executed_qty, ordered_qty, applicant_id,"
                + " current_doc_version, quota_occupied, created_by)"
                + " VALUES (?, ?, ?, 'COMMODITY', ?, 'BUY_HEDGE', ?, 'TON', ?, ?, 0.8, 'SIMPLE', ?,"
                + " 'APPROVED', 0, 0, ?, 0, 'N', ?)",
                11L, 1, "HP202609010001", 1L, new BigDecimal("100.0000"),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2026, 9, 1),
                100L, 0L);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) pool.close();
    }

    @Test
    @DisplayName("建表：m4_acct.sql 登记 12 张表 + 夹具父表；具名 CHECK 已求值数披露")
    void ddlRegisters12Tables() {
        // 12 张 ACCT 表 + 夹具 5 张跨模块父表（t_sub_account/t_product/t_exposure/t_hedge_plan/t_hedge_doc）
        assertEquals(17, engine.tableCount(), "应登记 17 张表（12 ACCT + 5 夹具父表），实际 " + engine.tableCount());
        // 诚实披露：引擎仅求值「单列 IN / = 字面量」形态 CHECK；amount > 0 等比较形态登记不求值
        // （既有引擎口径，InMemoryDmEngine javadoc「明确不覆盖」），amount>0 静态判据由 M4AcctSchemaTest 承载
        long unchecked = engine.uncheckedCheckCount();
        assertTrue(unchecked >= 1, "amount > 0 CHECK 应为登记未求值形态（引擎口径披露），实际未登记数=" + unchecked);
    }

    @Test
    @DisplayName("12 个 ACCT DAO 的 businessColumns() 与 DDL 业务列逐序一致，且不含审计字段")
    void businessColumnsMatchDdlInOrder() throws Exception {
        Map<String, List<String>> ddl = parseOrderedColumns();
        assertEquals(12, ddl.size(), "应解析到 12 张表，实际 " + ddl.keySet());

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String name : DAO_CLASSES) {
            Class<?> cls = Class.forName("com.hedgeguard.dao.impl." + name);
            AbstractDmDao<?, ?> dao = (AbstractDmDao<?, ?>) cls.getConstructor(TxTemplate.class).newInstance(tx);
            String table = dao.tableName();
            List<String> expected = ddl.get(table);
            assertNotNull(expected, name + " 对应表不在脚本中：" + table);
            List<String> actual = Arrays.asList(dao.businessColumns());
            for (String c : actual) if (AUDIT.contains(c)) problems.add(name + " 业务列误含审计字段：" + c);
            if (!expected.equals(actual)) problems.add(name + "（" + table + "）列序不一致：脚本=" + expected + " DAO=" + actual);
            checked++;
        }
        assertEquals(12, checked, "应核对 12 个 DAO");
        assertTrue(problems.isEmpty(), "DAO 列映射与建表脚本不一致（" + problems.size() + " 项）：\n  - "
                + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("写入：真实 DmVoucherDao 插入凭证（voucher_type=EXERCISE）/按 id 读回；findByVoucherNo 命中")
    void voucherInsertReadBack() {
        seedRelationChain(1);
        DmVoucherDao dao = new DmVoucherDao(tx);
        Voucher v = new Voucher();
        v.subAccountId = 1;
        v.voucherNo = "AV20260900001";
        v.periodKey = "2026-09";
        v.voucherDate = LocalDate.of(2026, 9, 30);
        v.voucherType = "EXERCISE";
        v.hedgePlanId = 11L;
        v.hedgeRelationId = 1L;
        v.status = "DRAFT";
        v.totalAmount = new BigDecimal("12000.0000");
        v.generatedBy = 9L;
        v.generatedTime = LocalDateTime.of(2026, 9, 30, 16, 0);
        v.pendingExternalReview = "Y";

        assertEquals(1, dao.insert(v, 9L), "insert 影响 1 行");
        assertEquals(1L, v.id, "自增主键回填");
        Voucher got = dao.findById(v.id);
        assertNotNull(got);
        assertEquals("AV20260900001", got.voucherNo);
        assertEquals("EXERCISE", got.voucherType, "行权凭证类型（v1.2 DC-031）");
        assertEquals("DRAFT", got.status);
        assertEquals(0, got.totalAmount.compareTo(new BigDecimal("12000.0000")));
        assertNull(got.settleCycleId, "可空列读回 null");

        Voucher byNo = dao.findByVoucherNo("AV20260900001");
        assertNotNull(byNo, "按凭证号查询应命中");
        assertEquals(v.id, byNo.id);
    }

    @Test
    @DisplayName("验收2：t_voucher.sub_account_id 由数据库层 NOT NULL 强制（缺子账户被拒，R-ACCT-5）")
    void voucherSubAccountNotNullEnforced() {
        DmVoucherDao dao = new DmVoucherDao(tx);
        Voucher v = new Voucher();
        v.subAccountId = null;   // 故意缺失
        v.voucherNo = "AV20260900002";
        v.periodKey = "2026-09";
        v.voucherDate = LocalDate.of(2026, 9, 30);
        v.voucherType = "COMMISSION";
        v.status = "DRAFT";
        v.totalAmount = new BigDecimal("100.0000");
        v.generatedBy = 9L;
        v.generatedTime = LocalDateTime.of(2026, 9, 30, 16, 0);
        v.pendingExternalReview = "Y";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> dao.insert(v, 9L));
        assertTrue(messageChain(ex).contains("NOT NULL"),
                "应因 NOT NULL 被拒（缺子账户标识的凭证根本无法插入，R-ACCT-5）：" + messageChain(ex));
    }

    @Test
    @DisplayName("uk_voucher_no 唯一约束强制（凭证编号全系统唯一，FR-ACCT-6）")
    void voucherNoUniqueEnforced() {
        seedRelationChain(1);
        DmVoucherDao dao = new DmVoucherDao(tx);
        assertEquals(1, dao.insert(voucher("AV20260900003", "CLOSE_PNL"), 9L));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dao.insert(voucher("AV20260900003", "COMMISSION"), 9L));
        assertTrue(messageChain(ex).contains("违反唯一约束") || messageChain(ex).contains("uk_voucher_no"),
                "应因 uk_voucher_no 被拒：" + messageChain(ex));
    }

    @Test
    @DisplayName("凭证乐观锁更新生效（状态流转 DRAFT→PENDING_REVIEW 经业务动作承载；版本推进后旧版本被拒）")
    void voucherOptimisticLockUpdate() {
        seedRelationChain(1);
        DmVoucherDao dao = new DmVoucherDao(tx);
        Voucher v = voucher("AV20260900004", "FLOAT_PNL");
        assertEquals(1, dao.insert(v, 9L));
        Voucher loaded = dao.findById(v.id);
        loaded.status = "PENDING_REVIEW";
        assertEquals(1, dao.updateById(loaded, 8L), "乐观锁更新 1 行");
        assertEquals("PENDING_REVIEW", dao.findById(v.id).status, "状态已流转");
        // loaded.rowVersion 已被 advanceVersion 推进；重新读出的行以旧版本更新被拒
        Voucher stale = dao.findById(v.id);
        stale.rowVersion = 1;   // 旧版本
        stale.status = "POSTED";
        assertEquals(0, dao.updateById(stale, 8L), "旧版本更新应 0 行（乐观锁冲突，10009/10010 语义）");
    }

    @Test
    @DisplayName("只增不改：t_effectiveness_assess / t_rebalance_record / t_period_close_log updateById 显式拒绝")
    void appendOnlyTablesRejectUpdate() {
        seedRelationChain(1);

        EffectivenessAssess ea = new EffectivenessAssess();
        ea.subAccountId = 1;
        ea.hedgeRelationId = 1L;
        ea.periodKey = "2026-09";
        ea.assessMethod = "QUALITATIVE_ECONOMIC_RELATION";
        ea.observerLinkage = "Y";
        ea.observerDirection = "Y";
        ea.observerTerm = "Y";
        ea.monitorReviewFlag = "N";
        ea.conclusion = "EFFECTIVE";
        ea.needActionFlag = "N";
        ea.assessorId = 7L;
        ea.assessTime = LocalDateTime.of(2026, 9, 30, 15, 0);
        ea.isCorrected = "N";
        DmEffectivenessAssessDao assessDao = new DmEffectivenessAssessDao(tx);
        assertEquals(1, assessDao.insert(ea, 9L));
        UnsupportedOperationException ex1 = assertThrows(UnsupportedOperationException.class,
                () -> assessDao.updateById(ea, 9L));
        assertTrue(ex1.getMessage().contains("不得事后修改"), "评估记录不得事后修改（R-ACCT-2）：" + ex1.getMessage());

        RebalanceRecord rr = new RebalanceRecord();
        rr.subAccountId = 1;
        rr.hedgeRelationId = 1L;
        rr.rebalanceNo = "RB20260900001";
        rr.triggerType = "EXPOSURE_DELTA";
        rr.triggerSide = "EXPOSURE_SIDE";
        rr.triggerReason = "敞口变化 25% > 20%";
        rr.triggerThreshold = new BigDecimal("0.200000");
        rr.ratioBefore = new BigDecimal("0.800000");
        rr.ratioAfter = new BigDecimal("1.000000");
        rr.qtyBefore = new BigDecimal("100.0000");
        rr.qtyAfter = new BigDecimal("120.0000");
        rr.newHedgeDocId = 21L;
        rr.executorId = 7L;
        rr.rebalanceTime = LocalDateTime.of(2026, 9, 30, 16, 30);
        rr.planFeedbackFlag = "N";
        DmRebalanceRecordDao rebalanceDao = new DmRebalanceRecordDao(tx);
        assertEquals(1, rebalanceDao.insert(rr, 9L));
        UnsupportedOperationException ex2 = assertThrows(UnsupportedOperationException.class,
                () -> rebalanceDao.updateById(rr, 9L));
        assertTrue(ex2.getMessage().contains("不得事后修改"), "再平衡记录不得事后修改（R-ACCT-3）：" + ex2.getMessage());

        PeriodCloseLog log = new PeriodCloseLog();
        log.periodKey = "2026-09";
        log.action = "CLOSE";
        log.precheckResult = "①全部凭证已审核②结算已完成③评估已完成";
        log.operatorId = 7L;
        log.reviewerId = 8L;
        log.actionTime = LocalDateTime.of(2026, 10, 5, 9, 0);
        DmPeriodCloseLogDao logDao = new DmPeriodCloseLogDao(tx);
        assertEquals(1, logDao.insert(log, 9L));
        UnsupportedOperationException ex3 = assertThrows(UnsupportedOperationException.class,
                () -> logDao.updateById(log, 9L));
        assertTrue(ex3.getMessage().contains("只增不改"), "关账留痕只增不改（TB-ACCT-7）：" + ex3.getMessage());
    }

    @Test
    @DisplayName("约束有牙：spot_pnl_column_present 恒 Y / 待外部复核恒 Y / voucher_type 与关账 action 取值域")
    void namedChecksBite() {
        seedRelationChain(1);
        DmVoucherDao voucherDao = new DmVoucherDao(tx);

        // voucher_type 取值域（含 EXERCISE/EXPIRED_WORTHLESS 合法域）——非法值被拒
        Voucher badType = voucher("AV20260900005", "VOUCHER_X");   // 越域
        RuntimeException ex0 = assertThrows(RuntimeException.class, () -> voucherDao.insert(badType, 9L));
        assertTrue(messageChain(ex0).contains("CHECK") || messageChain(ex0).contains("voucher_type"),
                "voucher_type 取值域 CHECK 应有牙：" + messageChain(ex0));

        // voucher_type = EXERCISE（行权，DC-031 合法域）应可写入——与越域负例对偶
        assertEquals(1, voucherDao.insert(voucher("AV20260900005", "EXERCISE"), 9L));

        DualPnlReport report = new DualPnlReport();
        report.subAccountId = 1;
        report.reportNo = "DP20260900001";
        report.reportPeriod = "2026-09";
        report.reportType = "MONTHLY";
        report.spotPnlColumnPresent = "N";   // 越域：栏位不得省略
        report.derivativePnlValue = new BigDecimal("100.0000");
        report.submittedBy = 7L;
        report.submittedTime = LocalDateTime.of(2026, 10, 5, 10, 0);
        report.pendingExternalReview = "Y";
        DmDualPnlReportDao reportDao = new DmDualPnlReportDao(tx);
        RuntimeException ex2 = assertThrows(RuntimeException.class, () -> reportDao.insert(report, 9L));
        assertTrue(messageChain(ex2).contains("CHECK") || messageChain(ex2).contains("spot_pnl_column_present"),
                "spot_pnl_column_present 恒 Y CHECK 应有牙（FR-ACCT-10 验收 2 结构保证）：" + messageChain(ex2));

        Voucher badFlag = voucher("AV20260900006", "OTHER");
        badFlag.pendingExternalReview = "N";   // 越域：待外部复核不得移除
        RuntimeException ex3 = assertThrows(RuntimeException.class, () -> voucherDao.insert(badFlag, 9L));
        assertTrue(messageChain(ex3).contains("CHECK") || messageChain(ex3).contains("pending_external_review"),
                "待外部复核恒 Y CHECK 应有牙（R-ACCT-10）：" + messageChain(ex3));

        PeriodCloseLog badAction = new PeriodCloseLog();
        badAction.periodKey = "2026-09";
        badAction.action = "REOPEN_ALL";   // 越域
        badAction.precheckResult = "x";
        badAction.operatorId = 7L;
        badAction.reviewerId = 8L;
        badAction.actionTime = LocalDateTime.of(2026, 10, 5, 9, 0);
        DmPeriodCloseLogDao logDao = new DmPeriodCloseLogDao(tx);
        RuntimeException ex4 = assertThrows(RuntimeException.class, () -> logDao.insert(badAction, 9L));
        assertTrue(messageChain(ex4).contains("CHECK") || messageChain(ex4).contains("action"),
                "action 取值域 CHECK 应有牙：" + messageChain(ex4));

        // ⚠️ 诚实披露：t_voucher_line.amount > 0 为「比较形态」CHECK，引擎登记不求值
        // （InMemoryDmEngine 仅求值「单列 IN / = 字面量」；静态判据由 M4AcctSchemaTest 承载）
        DmVoucherLineDao lineDao = new DmVoucherLineDao(tx);
        Voucher v = voucherDao.findByVoucherNo("AV20260900005");
        VoucherLine line = new VoucherLine();
        line.subAccountId = 1;
        line.voucherId = v.id;
        line.lineNo = 1;
        line.subjectCode = "5101";
        line.direction = "DEBIT";
        line.amount = new BigDecimal("-1.0000");   // 负金额
        lineDao.insert(line, 9L);                  // 引擎不拒（披露口径，非缺陷）
        assertEquals(1, lineDao.findByVoucher(v.id).size(), "负金额行在等价引擎上落库（披露：amount>0 未求值）");
    }

    @Test
    @DisplayName("双侧盈亏同 行（spot/derivative 列成对）；hedge_effect 幂等键查询；CAS24 头/条目读写")
    void pairedPnlAndSelfcheck() {
        seedRelationChain(1);
        DmHedgeEffectDao effectDao = new DmHedgeEffectDao(tx);
        HedgeEffect e = new HedgeEffect();
        e.subAccountId = 1;
        e.periodKey = "2026-09";
        e.hedgePlanId = 11L;
        e.exposureId = 21L;
        e.spotPnlRealized = new BigDecimal("5000.0000");
        e.spotPnlUnrealized = new BigDecimal("200.0000");
        e.spotPnlTotal = new BigDecimal("5200.0000");
        e.derivativePnlRealized = new BigDecimal("-4800.0000");
        e.derivativePnlUnrealized = new BigDecimal("-150.0000");
        e.derivativePnlTotal = new BigDecimal("-4950.0000");
        e.netEffect = new BigDecimal("250.0000");
        e.unhedgedQty = new BigDecimal("10.0000");
        e.spotPriceMissing = "N";
        e.nonCurrentPriceFlag = "N";
        e.calcTime = LocalDateTime.of(2026, 9, 30, 17, 0);
        assertEquals(1, effectDao.insert(e, 9L));
        HedgeEffect got = effectDao.findByKey(1, "2026-09", 11L, 21L);
        assertNotNull(got, "幂等键查询应命中");
        assertNotNull(got.spotPnlTotal, "实货侧列");
        assertNotNull(got.derivativePnlTotal, "衍生品侧列（两侧成对同 行，R-ACCT-4）");
        assertEquals(0, got.netEffect.compareTo(new BigDecimal("250.0000")));

        DmCas24SelfcheckDao scDao = new DmCas24SelfcheckDao(tx);
        Cas24Selfcheck sc = new Cas24Selfcheck();
        sc.subAccountId = 1;
        sc.selfcheckNo = "SC20260900001";
        sc.scopeType = "HEDGE_RELATION";
        sc.scopeKey = "HR20260900001";
        sc.passCount = 5;
        sc.failCount = 0;
        sc.conclusion = "ALL_PASS";
        sc.pendingExternalReview = "Y";
        sc.notSubstituteNote = "自检不替代外部复核";
        sc.executorId = 7L;
        sc.selfcheckTime = LocalDateTime.of(2026, 10, 5, 11, 0);
        assertEquals(1, scDao.insert(sc, 9L));
        assertNotNull(scDao.findBySelfcheckNo("SC20260900001"), "按批次号查询应命中");

        DmCas24SelfcheckItemDao itemDao = new DmCas24SelfcheckItemDao(tx);
        Cas24SelfcheckItem item = new Cas24SelfcheckItem();
        item.selfcheckId = sc.id;
        item.itemSeq = 1;
        item.itemCode = "FORMAL_DESIGNATION";
        item.itemName = "套期关系正式指定";
        item.result = "PASS";
        item.evidenceType = "t_hedge_relation";
        item.evidenceId = 1L;
        item.evidenceDesc = "套期关系 HR20260900001";
        assertEquals(1, itemDao.insert(item, 9L));
        assertEquals(1, itemDao.findBySelfcheck(sc.id).size());
        assertEquals(1, itemDao.findByCodeAndResult("FORMAL_DESIGNATION", "PASS").size());
    }

    @Test
    @DisplayName("评估记录三查询路径（findActiveByRelationAndPeriod / findByPeriodAndConclusion / 监测筛选）")
    void effectivenessQueries() {
        seedRelationChain(1);
        DmEffectivenessAssessDao dao = new DmEffectivenessAssessDao(tx);
        assertEquals(1, dao.insert(assess(1L, "2026-09", "EFFECTIVE", "N", "N"), 9L));
        assertEquals(1, dao.insert(assess(1L, "2026-10", "NEED_REBALANCE", "Y", "Y"), 9L));

        assertNotNull(dao.findActiveByRelationAndPeriod(1L, "2026-09"), "未更正记录应命中");
        assertNull(dao.findActiveByRelationAndPeriod(99L, "2026-09"), "无记录返回 null");
        assertEquals(1, dao.findByPeriodAndConclusion("2026-10", "NEED_REBALANCE").size());
        assertEquals(1, dao.findMonitorFlaggedByPeriod("2026-10").size(), "监测超阈值记录应被筛出");
        assertEquals(0, dao.findMonitorFlaggedByPeriod("2026-09").size());
    }

    @Test
    @DisplayName("分页查询：DmVoucherDao.findPage 走基类（FR-ACCT-8 列表）")
    void voucherFindPage() {
        seedRelationChain(1);
        DmVoucherDao dao = new DmVoucherDao(tx);
        for (int i = 1; i <= 3; i++) {
            assertEquals(1, dao.insert(voucher(String.format("AV202609%05d", 10 + i), "OTHER"), 9L));
        }
        com.hedgeguard.dao.PageResult<Voucher> page = dao.findPage(
                PageQuery.of("1", "20", null, "voucher_no"),
                "sub_account_id = ?", Arrays.asList(1));
        assertEquals(3, page.total(), "总数 3");
        assertEquals(3, page.items().size(), "每页 20 全取出");
    }

    // ------------------------------------------------------------------ 夹具

    /** 播种 ACCT 域外键父表链的行级依赖（父表结构已在 seedParentTables 建好）。 */
    private void seedRelationChain(int subAccountId) {
        try {
            // 套期关系书面文件（t_hedge_doc，三要素 NOT NULL）
            exec("INSERT INTO t_hedge_doc (id, sub_account_id, hedge_plan_id, doc_no, doc_version,"
                    + " is_current, elem_formal_designation, elem_effectiveness_method,"
                    + " elem_rebalance_mechanism, hedged_item_exposure_id, hedging_instrument,"
                    + " pending_external_review, generated_by, generated_time, created_by)"
                    + " VALUES (?, ?, ?, ?, 1, 'Y', ?, ?, ?, ?, ?, 'Y', ?, ?, ?)",
                    21L, subAccountId, 11L, "HD202609010001", "正式指定：敞口21×cu2612",
                    "定性经济关系法", "偏离>20%再平衡", 21L, "cu2612", 7L,
                    LocalDateTime.of(2026, 9, 1, 9, 0), 7L);
            // 套期关系（指定态）
            exec("INSERT INTO t_hedge_relation (id, sub_account_id, relation_no, hedge_plan_id,"
                    + " hedged_item_exposure_id, hedging_instrument_type, hedging_instrument_desc,"
                    + " hedge_doc_id, status, designated_time, created_by)"
                    + " VALUES (?, ?, 'HR20260900001', ?, 21, 'FUTURES', 'cu2612 持仓', 21,"
                    + " 'DESIGNATED', ?, ?)",
                    1L, subAccountId, 11L, LocalDateTime.of(2026, 9, 1, 9, 0), 7L);
        } catch (Exception e) {
            throw new IllegalStateException("播种失败：" + e.getMessage(), e);
        }
    }

    private Voucher voucher(String voucherNo, String type) {
        Voucher v = new Voucher();
        v.subAccountId = 1;
        v.voucherNo = voucherNo;
        v.periodKey = "2026-09";
        v.voucherDate = LocalDate.of(2026, 9, 30);
        v.voucherType = type;
        v.status = "DRAFT";
        v.totalAmount = new BigDecimal("1000.0000");
        v.generatedBy = 9L;
        v.generatedTime = LocalDateTime.of(2026, 9, 30, 16, 0);
        v.pendingExternalReview = "Y";
        return v;
    }

    private EffectivenessAssess assess(long relationId, String periodKey, String conclusion,
                                       String needAction, String monitorFlag) {
        EffectivenessAssess ea = new EffectivenessAssess();
        ea.subAccountId = 1;
        ea.hedgeRelationId = relationId;
        ea.periodKey = periodKey;
        ea.assessMethod = "QUALITATIVE_ECONOMIC_RELATION";
        ea.observerLinkage = "Y";
        ea.observerDirection = "Y";
        ea.observerTerm = "Y";
        ea.monitorReviewFlag = monitorFlag;
        ea.observerDetail = "观察点判定过程（结论可复现）";
        ea.conclusion = conclusion;
        ea.needActionFlag = needAction;
        ea.actionNote = needAction.equals("Y") ? "需终止或再平衡" : null;
        ea.assessorId = 7L;
        ea.assessTime = LocalDateTime.of(2026, 9, 30, 15, 0);
        ea.isCorrected = "N";
        return ea;
    }

    private void exec(String sql, Object... args) throws Exception {
        try (java.sql.Connection conn = engine.create();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static String messageChain(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = e; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private static Map<String, List<String>> parseOrderedColumns() throws IOException {
        String text = new String(Files.readAllBytes(repoRoot().resolve(SCRIPT)), StandardCharsets.UTF_8);
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        Matcher m = CREATE_TABLE.matcher(text);
        while (m.find()) {
            List<String> cols = new ArrayList<>();
            for (String raw : m.group(2).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("--")) continue;
                String head = line.split("\\s+")[0].toUpperCase(java.util.Locale.ROOT);
                if (head.equals("CONSTRAINT") || head.equals("PRIMARY") || head.equals("FOREIGN")
                        || head.equals("UNIQUE") || head.equals("CHECK")) continue;
                Matcher c = COLUMN_LINE.matcher(line);
                if (!c.matches()) continue;
                if (AUDIT.contains(c.group(1))) continue;
                cols.add(c.group(1));
            }
            out.put(m.group(1), cols);
        }
        return out;
    }

    private static final Pattern CREATE_TABLE = Pattern.compile("(?s)CREATE TABLE\\s+([a-z0-9_]+)\\s*\\((.*?)\\n\\);");
    private static final Pattern COLUMN_LINE = Pattern.compile("^([a-z][a-z0-9_]*)\\s+([A-Za-z]+(\\s*\\([0-9,\\s]*\\))?).*$");

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) return p;
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根；cwd=" + Paths.get("").toAbsolutePath());
    }
}
