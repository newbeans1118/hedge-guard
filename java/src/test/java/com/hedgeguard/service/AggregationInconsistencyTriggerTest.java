package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.MajorEvent;
import com.hedgeguard.dao.entity.SettleAggregationCheck;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.SettleCtpFund;
import com.hedgeguard.dao.impl.DmMajorEventDao;
import com.hedgeguard.dao.impl.DmSettleAggDiffCandidateDao;
import com.hedgeguard.dao.impl.DmSettleAggregationCheckDao;
import com.hedgeguard.dao.impl.DmSettleCtpBatchDao;
import com.hedgeguard.dao.impl.DmSettleCtpFundDao;
import com.hedgeguard.dao.impl.DmSettleCtpPositionDao;
import com.hedgeguard.dao.impl.DmSettleCtpTradeDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmSettleFundDao;
import com.hedgeguard.dao.impl.DmSettlePnlDao;
import com.hedgeguard.dao.impl.DmSettlePositionDao;
import com.hedgeguard.dao.impl.DmSettleReconcileDifferenceDao;
import com.hedgeguard.dao.impl.DmSettleReconcileRecordDao;
import com.hedgeguard.dao.impl.DmSettleReportDao;
import com.hedgeguard.dao.impl.DmSettleStressTestDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-081} 加总对账不一致超时未闭环自动触发（{@code R-AUDIT-5} 条件①）
 * 「真实 DAO + 内存假 JDBC 引擎」用例（禁假 DAO；CFG 键
 * {@code audit.major_event.agg_inconsistency_timeout_hours} 缺省 24h）。
 *
 * <p>覆盖：超时未闭环触发 / 未超时不触发 / 已闭环不触发 / 一致结论不触发 /
 * 跨日幂等（D+1 再评不重复落行——AGG 稳定键锚对账记录业务日，M1′ 变异击杀器）/
 * 24h 缺省边界（恰 24h 不触发，超 24h 触发）/ 缺键回退 / 非法显式拒 /
 * 配置生效 / 全链路（{@code SettleService.aggregate} 生产路径：首次对账新鲜不触发 →
 * 时钟前进后再对账对历史未闭环记录触发）/ 失败隔离（触发器抛错不阻断加总对账主流程）。
 * 触发行全部由生产路径或触发器本体产生，<b>无任何直插 {@code t_major_event} 伪造触发</b>。
 */
class AggregationInconsistencyTriggerTest {

    private static final int SUB = 21;
    private static final long SYSTEM_OP = 0L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    /** 评估时点 T0（DAY 12:00）。 */
    private static final LocalDateTime T0 = DAY.atTime(12, 0);

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private DmMajorEventDao majorEventDao;
    private DmSettleAggregationCheckDao checkDao;
    private DmSettleCycleDao cycleDao;
    private DmSettleCtpFundDao ctpFundDao;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(T0.atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : schemaScripts()) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-T081", "商品子账户T081");
        M2E2eSeed.product(engine, 65L, "CU", "沪铜");

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        majorEventDao = new DmMajorEventDao(tx);
        checkDao = new DmSettleAggregationCheckDao(tx);
        cycleDao = new DmSettleCycleDao(tx);
        ctpFundDao = new DmSettleCtpFundDao(tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    private static List<String> schemaScripts() {
        return Arrays.asList(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m1_product.sql",
                "docs/DEPLOY/schema/m1_spot.sql",
                "docs/DEPLOY/schema/m1_plan.sql",
                "docs/DEPLOY/schema/m2_trade.sql",
                "docs/DEPLOY/schema/m2_settle.sql",
                "docs/DEPLOY/schema/m2_fund.sql",
                "docs/DEPLOY/schema/m3_risk.sql",
                "docs/DEPLOY/schema/m3_audit.sql");
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/DEPLOY/schema"))) {
            p = p.getParent();
        }
        assertNotNull(p, "未找到仓库根（docs/DEPLOY/schema）");
        return p;
    }

    // ------------------------------------------------------------------ 装配辅助

    /** 缺键（回退默认 24h）的触发器。 */
    private AggregationInconsistencyTrigger trigger() {
        return new AggregationInconsistencyTrigger(majorEventDao, checkDao, () -> null, SYSTEM_OP);
    }

    private AggregationInconsistencyTrigger trigger(String rawConfigValue) {
        return new AggregationInconsistencyTrigger(majorEventDao, checkDao,
                () -> rawConfigValue, SYSTEM_OP);
    }

    /**
     * 播种一行加总对账记录。
     *
     * @param checkType 对账类型（ck_t_aggregation_check_check_type 冻结域字面量）
     * @param result    对账结论（ck_t_aggregation_check_result 冻结域字面量）
     * @param checkTime 校验执行时间
     * @param closed    阻断解除时间（{@code null} = 未闭环）
     */
    private SettleAggregationCheck seedCheck(String checkType, String result,
                                             LocalDateTime checkTime, LocalDateTime closed) {
        SettleAggregationCheck c = new SettleAggregationCheck();
        c.tradeDate = DAY;
        c.checkType = checkType;
        c.sumSubAccount = new BigDecimal("1000");
        c.realAccountValue = new BigDecimal("2000");
        c.difference = new BigDecimal("-1000");
        c.result = result;
        c.blockScope = "DIFF_SUB_NON_CLOSE";
        if (SettleService.AGG_INCONSISTENT.equals(result)) {
            c.blockEffectiveTime = checkTime;
            c.alertTime = checkTime;
        }
        c.checkTime = checkTime;
        c.closedTime = closed;
        checkDao.insert(c, SYSTEM_OP);
        assertTrue(c.id > 0, "对账记录应取到生成主键");
        return c;
    }

    /** 预置一张 PENDING 结算单（资金面取数承载）。 */
    private SettleCycle seedCycle(int subAccountId, LocalDate date, String equity) {
        SettleCycle c = new SettleCycle();
        c.subAccountId = subAccountId;
        c.settleNo = "SC" + date.toString().replace("-", "") + String.format("%06d", subAccountId);
        c.tradeDate = date;
        c.isAggregate = "N";
        c.status = "PENDING";
        c.closePnl = BigDecimal.ZERO;
        c.floatPnl = BigDecimal.ZERO;
        c.commission = BigDecimal.ZERO;
        c.marginOccupied = BigDecimal.ZERO;
        c.availableFund = BigDecimal.ZERO;
        c.equity = new BigDecimal(equity);
        c.negativeFundFlag = "N";
        c.voidedFlag = "N";
        cycleDao.insert(c, SYSTEM_OP);
        assertTrue(c.id > 0, "结算单应取到生成主键");
        return c;
    }

    /** 播种一行 CTP 资金（与系统内权益不一致 ⇒ 加总对账 FUND 项不一致）。 */
    private void seedCtpFund(LocalDate date, String equity) {
        // t_ctp_settle_fund.settle_batch_id NOT NULL ⇒ 先播种一行 CTP 批次承载外键
        com.hedgeguard.dao.entity.SettleCtpBatch b = new com.hedgeguard.dao.entity.SettleCtpBatch();
        b.tradeDate = date;
        b.sourceIdentifier = "CTP-T081";
        b.receivedTime = T0;
        b.positionCount = 0;
        b.fundCount = 1;
        b.tradeCount = 0;
        b.parseStatus = "OK";
        b.retryCount = 0;
        b.isConfirmed = "N";
        new DmSettleCtpBatchDao(tx).insert(b, SYSTEM_OP);
        assertTrue(b.id > 0, "CTP 批次应取到生成主键");

        SettleCtpFund f = new SettleCtpFund();
        f.tradeDate = date;
        f.settleBatchId = b.id;
        f.equity = new BigDecimal(equity);
        f.availableFund = BigDecimal.ZERO;
        f.marginOccupied = BigDecimal.ZERO;
        f.frozenMargin = BigDecimal.ZERO;
        f.commissionToday = BigDecimal.ZERO;
        f.depositToday = BigDecimal.ZERO;
        f.withdrawToday = BigDecimal.ZERO;
        f.closePnl = BigDecimal.ZERO;
        f.floatPnl = BigDecimal.ZERO;
        ctpFundDao.insert(f, SYSTEM_OP);
    }

    private SettleService newSettleService(AggregationInconsistencyTrigger aggTrigger) {
        SettleService service = new SettleService(tx, userId -> Arrays.asList(SUB),
                new DmSettleCtpBatchDao(tx), new DmSettleCtpPositionDao(tx),
                new DmSettleCtpTradeDao(tx), new DmSettleCtpFundDao(tx),
                cycleDao, new DmSettlePositionDao(tx),
                new DmSettleFundDao(tx), new DmSettlePnlDao(tx),
                new DmSettleReconcileRecordDao(tx), new DmSettleReconcileDifferenceDao(tx),
                checkDao, new DmSettleAggDiffCandidateDao(tx),
                new DmSettleReportDao(tx), new DmSettleStressTestDao(tx));
        service.setAggregationInconsistencyTrigger(aggTrigger);
        return service;
    }

    private List<Map<String, Object>> majorRows() {
        return engine.table("t_major_event").rows;
    }

    private Map<String, Object> singleRow() {
        assertEquals(1, majorRows().size(), "应恰有 1 行触发行");
        return majorRows().get(0);
    }

    // ================================================================ 触发判定

    @Test
    @DisplayName("条件①：不一致未闭环且超 24h ⇒ 触发 1 行（AGG_INCONSISTENT_TIMEOUT/TRIGGERED，含对账快照）")
    void firesWhenStaleUnclosedInconsistent() {
        seedCheck(SettleService.CHECK_FUND, SettleService.AGG_INCONSISTENT,
                T0.minusHours(25), null);

        assertEquals(1, trigger().evaluate(), "应触发 1 行");
        Map<String, Object> row = singleRow();
        assertEquals("AGG_INCONSISTENT_TIMEOUT", row.get("event_type"));
        assertEquals("TRIGGERED", row.get("status"));
        // 幂等稳定键：锚对账记录业务日（tradeDate），不锚触发时点
        assertEquals("AGG-20261010-FUND", row.get("event_no"));
        assertNull(row.get("sub_account_id"), "加总对账为全账户级核对，主体非单一子账户");
        String desc = String.valueOf(row.get("event_desc"));
        assertTrue(desc.contains("FUND"), "描述应携带对账类型：" + desc);
        assertTrue(desc.contains("1000"), "描述应携带 Σ子账户 快照值：" + desc);
        assertTrue(desc.contains("-1000"), "描述应携带差额快照值：" + desc);
        assertTrue(desc.contains("24"), "描述应携带超时阈值：" + desc);
        String scope = String.valueOf(row.get("impact_scope"));
        assertTrue(scope.contains("DIFF_SUB_NON_CLOSE"), "影响范围应携带阻断范围：" + scope);
        assertTrue(String.valueOf(row.get("trigger_source")).startsWith("AGGREGATION_CHECK:"),
                "触发来源应关联对账记录主键");
    }

    @Test
    @DisplayName("未超时（1h）不触发；恰 24h 不触发（严格小于）；超 24h 触发（CFG-26b 缺省边界）")
    void defaultTimeoutBoundary() {
        seedCheck(SettleService.CHECK_FUND, SettleService.AGG_INCONSISTENT,
                T0.minusHours(1), null);
        seedCheck(SettleService.CHECK_PNL, SettleService.AGG_INCONSISTENT,
                T0.minusHours(24), null);
        seedCheck(SettleService.CHECK_MARGIN, SettleService.AGG_INCONSISTENT,
                T0.minusHours(24).minusMinutes(1), null);

        assertEquals(1, trigger().evaluate(), "恰 24h 不触发（严格小于），超 24h 触发");
        assertEquals("AGG-20261010-MARGIN", singleRow().get("event_no"),
                "唯一触发行应为超 24h 的 MARGIN 记录");
    }

    @Test
    @DisplayName("已闭环（closed_time 非空）不触发；一致结论不触发")
    void closedOrConsistentDoesNotFire() {
        seedCheck(SettleService.CHECK_FUND, SettleService.AGG_INCONSISTENT,
                T0.minusHours(25), T0.minusHours(1)); // 已闭环
        seedCheck(SettleService.CHECK_PNL, SettleService.RESULT_CONSISTENT,
                T0.minusHours(25), null); // 一致（且无阻断范围）

        assertEquals(0, trigger().evaluate(), "已闭环/一致结论均不触发");
        assertTrue(majorRows().isEmpty(), "不得落行");
    }

    // ================================================================ 幂等（AGG 稳定键）

    @Test
    @DisplayName("跨日幂等：D 日触发后 D+1 再评估不重复落行（同号先查后插；M1′ 变异击杀器）")
    void crossDayIdempotent() {
        seedCheck(SettleService.CHECK_FUND, SettleService.AGG_INCONSISTENT,
                T0.minusHours(25), null);

        AggregationInconsistencyTrigger t = trigger();
        assertEquals(1, t.evaluate(), "D 日首评触发 1 行");
        assertEquals("AGG-20261010-FUND", singleRow().get("event_no"));

        // 时钟前进至 D+1 13:00（+25h）：同一笔超时未闭环记录仍在，重评不得重复落行
        Clocks.set(Clock.fixed(T0.plusHours(25).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        assertEquals(0, t.evaluate(), "D+1 再评估应被 AGG 稳定键幂等去重");
        assertEquals(1, majorRows().size(), "跨日不得重复落行");
    }

    @Test
    @DisplayName("同日同型多笔记录：同一 tradeDate+checkType 至多 1 行提醒（确定性编号去重）")
    void sameDaySameTypeDeduplicated() {
        seedCheck(SettleService.CHECK_FUND, SettleService.AGG_INCONSISTENT,
                T0.minusHours(30), null);
        seedCheck(SettleService.CHECK_FUND, SettleService.AGG_INCONSISTENT,
                T0.minusHours(26), null);

        assertEquals(1, trigger().evaluate(), "同日同型重跑产生的多笔记录只提醒 1 行");
        assertEquals(1, majorRows().size());
    }

    // ================================================================ 配置处置

    @Test
    @DisplayName("配置回退：缺键 ⇒ 默认 24h 生效（25h 触发 / 23h 不触发）")
    void timeoutFallbackWhenKeyMissing() {
        seedCheck(SettleService.CHECK_FUND, SettleService.AGG_INCONSISTENT,
                T0.minusHours(25), null);
        seedCheck(SettleService.CHECK_PNL, SettleService.AGG_INCONSISTENT,
                T0.minusHours(23), null);

        assertEquals(1, trigger().evaluate(), "仅 25h 记录触发（默认 24h 生效）");
        assertEquals("AGG-20261010-FUND", singleRow().get("event_no"));
    }

    @Test
    @DisplayName("配置非法：≤0 / 非数字 ⇒ 显式拒（IllegalStateException，不回退默认、不落行）")
    void invalidTimeoutExplicitlyRejected() {
        seedCheck(SettleService.CHECK_FUND, SettleService.AGG_INCONSISTENT,
                T0.minusHours(25), null);

        assertThrows(IllegalStateException.class, () -> trigger("0").evaluate());
        assertThrows(IllegalStateException.class, () -> trigger("-5").evaluate());
        assertThrows(IllegalStateException.class, () -> trigger("abc").evaluate());
        assertThrows(IllegalStateException.class, () -> trigger("24.5").evaluate());
        assertTrue(majorRows().isEmpty(), "显式拒不得落行");
        assertNull(majorEventDao.findByEventNo("AGG-20261010-FUND"));
    }

    @Test
    @DisplayName("正向取值生效：配置 1h ⇒ 2h 前记录触发、30min 前记录不触发")
    void configuredTimeoutTakesEffect() {
        seedCheck(SettleService.CHECK_FUND, SettleService.AGG_INCONSISTENT,
                T0.minusHours(2), null);
        seedCheck(SettleService.CHECK_PNL, SettleService.AGG_INCONSISTENT,
                T0.minusMinutes(30), null);

        assertEquals(1, trigger("1").evaluate(), "配置 1h：2h 前记录触发");
        assertEquals("AGG-20261010-FUND", singleRow().get("event_no"));
    }

    // ================================================================ 全链路：aggregate 生产路径接线

    @Test
    @DisplayName("全链路：aggregate 生产路径首次对账（新鲜不一致）不触发 → 时钟前进 25h 再对账触发 1 行")
    void fullChainAggregateFiresAfterTimeout() {
        seedCycle(SUB, DAY, "1000");
        seedCtpFund(DAY, "2000"); // Σ子=1000 ≠ 真实=2000 ⇒ FUND 项不一致
        AggregationInconsistencyTrigger t = trigger();
        SettleService service = newSettleService(t);

        // 首次加总对账（T0）：产生新鲜不一致记录（checkTime=T0 < 24h）⇒ 不触发
        SettleService.AggregateResult first = service.aggregate(
                findCycleId(), SYSTEM_OP);
        assertFalse(first.consistent, "构造的不一致场景应产出 INCONSISTENT_BLOCKED 结论");
        assertEquals(0, majorRows().size(), "新鲜记录（未超时）不得触发");

        // 时钟前进 25h 后再次对账：T0 的不一致记录仍未闭环且已超时 ⇒ 触发 1 行
        Clocks.set(Clock.fixed(T0.plusHours(25).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        SettleService.AggregateResult second = service.aggregate(findCycleId(), SYSTEM_OP);
        assertFalse(second.consistent, "差异未处置，结论仍不一致");
        assertEquals(1, majorRows().size(), "生产路径应对超时未闭环记录触发 1 行");
        assertEquals("AGG_INCONSISTENT_TIMEOUT", singleRow().get("event_type"));
        assertEquals("AGG-20261010-FUND", singleRow().get("event_no"));
    }

    @Test
    @DisplayName("失败隔离：触发器抛错（配置非法）不得阻断加总对账主流程，且不落行")
    void aggregateCompletesWhenTriggerFails() {
        seedCycle(SUB, DAY, "1000");
        seedCtpFund(DAY, "2000");
        SettleService service = newSettleService(trigger("abc")); // 非法阈值 ⇒ evaluate 抛

        SettleService.AggregateResult res = service.aggregate(findCycleId(), SYSTEM_OP);

        assertFalse(res.consistent, "加总对账主流程不受触发器失败影响");
        assertTrue(majorRows().isEmpty(), "失败不得落行");
    }

    /** 取播种结算单主键（全链路用例唯一结算单）。 */
    private long findCycleId() {
        assertEquals(1, engine.table("t_settle_cycle").rows.size(), "全链路用例应恰 1 张结算单");
        return ((Number) engine.table("t_settle_cycle").rows.get(0).get("id")).longValue();
    }
}
