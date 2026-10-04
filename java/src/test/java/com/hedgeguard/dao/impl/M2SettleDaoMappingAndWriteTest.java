package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.entity.SettleAggDiffCandidate;
import com.hedgeguard.dao.entity.SettleAggregationCheck;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.dao.entity.SettleCtpBatch;
import com.hedgeguard.dao.entity.SettlePosition;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SETTLE 模块 16 个 DAO 实现的两类机器可复现证据（T-044 验收 4 + 验收 2/3 的行为面）。
 *
 * <ol>
 *   <li><b>列映射一致性（逐序）</b>：每个 DmSettle*Dao 的 businessColumns() 与建表脚本
 *       m2_settle.sql 的业务列清单逐序一致（同时保证 bindBusiness/readBusiness 不错位）。
 *       该判据比 DaoColumnAlignmentTest 的集合判据更强（顺序敏感）。</li>
 *   <li><b>写入/查询等价执行</b>：以 InMemoryDmEngine 建表（本环境无 DM8 实例），由真实 DAO
 *       生成 SQL 并执行：CTP 批次插入/读回、结算持仓子账户 NOT NULL 强制、
 *       核对签署 uk_check_signature_task_role 唯一约束强制、加总差异候选阻断集合机判。</li>
 * </ol>
 */
class M2SettleDaoMappingAndWriteTest {

    private static final String SCRIPT = "docs/DEPLOY/schema/m2_settle.sql";
    private static final List<String> AUDIT = Arrays.asList(
            "id", "created_by", "created_time", "updated_by", "updated_time", "row_version");

    private static final List<String> DAO_CLASSES = Arrays.asList(
            "DmSettleCtpBatchDao", "DmSettleCtpPositionDao", "DmSettleCtpFundDao", "DmSettleCtpTradeDao",
            "DmSettleCycleDao", "DmSettlePositionDao", "DmSettleFundDao", "DmSettlePnlDao",
            "DmSettleReconcileRecordDao", "DmSettleReconcileDifferenceDao", "DmSettleAggregationCheckDao",
            "DmSettleReportDao", "DmSettleCheckTaskDao", "DmSettleStressTestDao",
            "DmSettleCheckSignatureDao", "DmSettleAggDiffCandidateDao");

    private InMemoryDmEngine engine;
    private DataSourcePool pool;
    private TxTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        Path file = repoRoot().resolve(SCRIPT);
        assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
        engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        pool = new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2));
        tx = new TxTemplate(pool);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) pool.close();
    }

    @Test
    @DisplayName("建表：m2_settle.sql 登记 16 张表")
    void ddlRegisters16Tables() {
        assertEquals(16, engine.tableCount(), "应登记 16 张表，实际 " + engine.tableCount());
    }

    @Test
    @DisplayName("16 个 DmSettle*Dao 的 businessColumns() 与 DDL 业务列逐序一致，且不含审计字段")
    void businessColumnsMatchDdlInOrder() throws Exception {
        Map<String, List<String>> ddl = parseOrderedColumns();
        assertEquals(16, ddl.size(), "应解析到 16 张表，实际 " + ddl.keySet());

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
        assertEquals(16, checked, "应核对 16 个 DAO");
        assertTrue(problems.isEmpty(), "DAO 列映射与建表脚本不一致（" + problems.size() + " 项）：\n  - "
                + String.join("\n  - ", problems));
    }

    @Test
    @DisplayName("写入：真实 DmSettleCtpBatchDao 插入/按 id 读回；findByTradeDateAndSource 命中")
    void ctpBatchInsertReadBack() {
        DmSettleCtpBatchDao dao = new DmSettleCtpBatchDao(tx);
        SettleCtpBatch b = new SettleCtpBatch();
        b.tradeDate = LocalDate.of(2026, 9, 30);
        b.sourceIdentifier = "ctp_20260930.dat";
        b.receivedTime = LocalDateTime.of(2026, 9, 30, 16, 0);
        b.positionCount = 3; b.fundCount = 1; b.tradeCount = 5;
        b.parseStatus = "OK"; b.retryCount = 0; b.isConfirmed = "N";

        assertEquals(1, dao.insert(b, 9L), "insert 影响 1 行");
        assertEquals(1L, b.id, "自增主键回填");
        SettleCtpBatch got = dao.findById(b.id);
        assertNotNull(got);
        assertEquals("ctp_20260930.dat", got.sourceIdentifier);
        assertEquals("OK", got.parseStatus);
        assertNull(got.parseError, "可空列读回 null");

        SettleCtpBatch byKey = dao.findByTradeDateAndSource(LocalDate.of(2026, 9, 30), "ctp_20260930.dat");
        assertNotNull(byKey, "幂等键查询应命中");
        assertEquals(b.id, byKey.id);
    }

    @Test
    @DisplayName("验收2：t_settle_position.sub_account_id 由数据库层 NOT NULL 强制（缺子账户被拒）")
    void settlePositionSubAccountNotNullEnforced() {
        DmSettlePositionDao dao = new DmSettlePositionDao(tx);
        SettlePosition p = new SettlePosition();
        p.subAccountId = null;   // 故意缺失
        p.settleCycleId = 999L;
        p.tradeDate = LocalDate.of(2026, 9, 30);
        p.instrumentId = "cu2612";
        p.productId = 1L;
        p.instrumentType = "FUTURES";
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = new BigDecimal("10.0000");
        p.attributionSource = "TRADE_RECORD";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> dao.insert(p, 9L));
        assertTrue(messageChain(ex).contains("NOT NULL"),
                "应因 NOT NULL 被拒（而非其它异常）：" + messageChain(ex));
    }

    @Test
    @DisplayName("验收2：uk_check_signature_task_role 支持不可代签（同任务同角色二次签署被拒）")
    void checkSignatureUniqueConstraintEnforced() {
        long task1 = insertCheckTask("2026-09");
        long task2 = insertCheckTask("2026-10");
        DmSettleCheckSignatureDao dao = new DmSettleCheckSignatureDao(tx);
        assertEquals(1, dao.insert(signature(task1, "RISK"), 9L));
        assertNotNull(dao.findByTaskAndRole(task1, "RISK"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> dao.insert(signature(task1, "RISK"), 9L));
        assertTrue(messageChain(ex).contains("违反唯一约束") || messageChain(ex).contains("uk_check_signature_task_role"),
                "应因 uk_check_signature_task_role 唯一约束被拒：" + messageChain(ex));

        // 不同任务 / 不同角色可各自签署
        assertEquals(1, dao.insert(signature(task2, "RISK"), 9L));
        assertEquals(1, dao.insert(signature(task1, "FINANCE"), 9L));
    }

    @Test
    @DisplayName("验收3：findBlockingCandidates 机判阻断集合 = resolution_status ∈ {PENDING, CONFIRMED}")
    void aggDiffCandidateBlockingSet() {
        long checkId = insertAggregationCheck();
        long otherCheckId = insertAggregationCheck();
        DmSettleAggDiffCandidateDao dao = new DmSettleAggDiffCandidateDao(tx);
        dao.insert(candidate(checkId, 1, 1, "PENDING", "10"), 9L);
        dao.insert(candidate(checkId, 2, 2, "CONFIRMED", "20"), 9L);
        dao.insert(candidate(checkId, 3, 3, "EXCLUDED", "30"), 9L);
        dao.insert(candidate(otherCheckId, 1, 1, "PENDING", "40"), 9L);   // 另一次对账，不应混入

        List<SettleAggDiffCandidate> blocking = dao.findBlockingCandidates(checkId);
        assertEquals(2, blocking.size(), "阻断集合应恰为 2（PENDING + CONFIRMED）");
        Set<Integer> subs = new LinkedHashSet<>();
        for (SettleAggDiffCandidate c : blocking) subs.add(c.subAccountId);
        assertEquals(new LinkedHashSet<>(Arrays.asList(1, 2)), subs, "阻断子账户集合应为 {1,2}");
        assertEquals(1, blocking.get(0).candidateRank, "应按 candidate_rank 升序");
    }

    @Test
    @DisplayName("验收3：uk_agg_diff_candidate(aggregation_check_id, sub_account_id) 唯一约束强制")
    void aggDiffCandidateUniqueEnforced() {
        long checkId = insertAggregationCheck();
        DmSettleAggDiffCandidateDao dao = new DmSettleAggDiffCandidateDao(tx);
        dao.insert(candidate(checkId, 1, 1, "PENDING", "10"), 9L);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> dao.insert(candidate(checkId, 1, 2, "PENDING", "11"), 9L));
        assertTrue(messageChain(ex).contains("违反唯一约束") || messageChain(ex).contains("uk_agg_diff_candidate"),
                "应因 uk_agg_diff_candidate 被拒：" + messageChain(ex));
    }

    /** 播种 t_check_task 父行（供 t_check_signature 外键），返回其自增主键。 */
    private long insertCheckTask(String periodKey) {
        DmSettleCheckTaskDao taskDao = new DmSettleCheckTaskDao(tx);
        SettleCheckTask task = new SettleCheckTask();
        task.checkType = "MONTHLY";
        task.periodKey = periodKey;
        task.subAccountId = 0;
        task.requiredSignRoles = "RISK,TRADE,FINANCE";
        task.signedRoles = "";
        task.status = "PENDING_SIGN";
        task.snapshotHash = "snap";
        task.checkItems = "持仓/资金/盈亏/额度";
        task.deadlineDate = LocalDate.of(2026, 10, 30);
        task.overdueAlerted = "N";
        task.generatedTime = LocalDateTime.of(2026, 9, 30, 17, 0);
        taskDao.insert(task, 9L);
        return task.id;
    }

    /** 播种 t_aggregation_check 父行（供 t_agg_diff_candidate 外键），返回其自增主键。 */
    private long insertAggregationCheck() {
        DmSettleAggregationCheckDao checkDao = new DmSettleAggregationCheckDao(tx);
        SettleAggregationCheck ac = new SettleAggregationCheck();
        ac.tradeDate = LocalDate.of(2026, 9, 30);
        ac.checkType = "POSITION";
        ac.sumSubAccount = new BigDecimal("100.0000");
        ac.realAccountValue = new BigDecimal("90.0000");
        ac.difference = new BigDecimal("10.0000");
        ac.result = "INCONSISTENT_BLOCKED";
        ac.checkTime = LocalDateTime.of(2026, 9, 30, 17, 0);
        checkDao.insert(ac, 9L);
        return ac.id;
    }

    private static SettleCheckSignature signature(long taskId, String role) {
        SettleCheckSignature s = new SettleCheckSignature();
        s.subAccountId = 0;
        s.checkTaskId = taskId;
        s.signRole = role;
        s.signerId = 7L;
        s.signerRoleCode = role;
        s.signTime = LocalDateTime.of(2026, 9, 30, 17, 0);
        s.signIp = "127.0.0.1";
        s.snapshotHash = "hash-" + taskId + "-" + role;
        s.signResult = "SIGNED";
        s.proxyRejected = "N";
        s.manageRoleTradeBlocked = "N";
        return s;
    }

    private static SettleAggDiffCandidate candidate(long checkId, int sub, int rank, String status, String diff) {
        SettleAggDiffCandidate c = new SettleAggDiffCandidate();
        c.subAccountId = sub;
        c.aggregationCheckId = checkId;
        c.candidateRank = rank;
        c.diffAmount = new BigDecimal(diff);
        c.resolutionStatus = status;
        return c;
    }

    private static String messageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) { if (t.getMessage() != null) sb.append(t.getMessage()).append(" | "); t = t.getCause(); }
        return sb.toString();
    }

    /** 解析 m2_settle.sql 的每张表业务列（按声明顺序，排除审计字段）。 */
    private static Map<String, List<String>> parseOrderedColumns() throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String text = new String(Files.readAllBytes(repoRoot().resolve(SCRIPT)), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?s)CREATE TABLE\\s+([a-z0-9_]+)\\s*\\((.*?)\\n\\);").matcher(text);
        Pattern colLine = Pattern.compile("^([a-z][a-z0-9_]*)\\s+([A-Za-z]+(\\s*\\([0-9,\\s]*\\))?).*$");
        while (m.find()) {
            List<String> cols = new ArrayList<>();
            for (String raw : m.group(2).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("--")) continue;
                String head = line.split("\\s+")[0].toUpperCase(Locale.ROOT);
                if (head.equals("CONSTRAINT") || head.equals("PRIMARY") || head.equals("FOREIGN")
                        || head.equals("UNIQUE") || head.equals("CHECK")) continue;
                Matcher c = colLine.matcher(line);
                if (!c.matches()) continue;
                if (AUDIT.contains(c.group(1))) continue;
                cols.add(c.group(1));
            }
            out.put(m.group(1), cols);
        }
        return out;
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) return p;
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根；cwd=" + Paths.get("").toAbsolutePath());
    }
}

