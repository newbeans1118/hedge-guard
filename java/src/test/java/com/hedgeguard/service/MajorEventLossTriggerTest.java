package com.hedgeguard.service;

import com.hedgeguard.bridge.PositionView;
import com.hedgeguard.bridge.ReplayProgress;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.MajorEvent;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.SettlePnl;
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
import com.hedgeguard.risk_intraday.AsyncRiskMetricSink;
import com.hedgeguard.risk_intraday.CompositeRiskMetricSink;
import com.hedgeguard.risk_intraday.IntradayRiskMonitor;
import com.hedgeguard.risk_intraday.RiskEvaluation;
import com.hedgeguard.risk_intraday.RiskThresholds;
import com.hedgeguard.risk_intraday.SubAccountRiskMetrics;
import com.hedgeguard.service.impl.RiskIntradayPersistenceSink;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.handler.audit.AuditReportHandler;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-188} 重大事项「亏损阈值」自动触发（双轨）「真实 DAO + 内存假 JDBC 引擎」用例
 * （禁假 DAO；{@code CFG-26} = {@code audit.major_event.loss_threshold}；用户 2026-10-10
 * 「双轨」裁定）。
 *
 * <p>覆盖：轨 1（EOD 已实现，source=SETTLED）/ 轨 2（盘中镜像，source=INTRADAY_MIRROR）
 * 超阈值触发、恰等阈值不触发（严格大于）、未越限不落行、幂等（同日同源至多 1 行）、
 * 两源共存、自然日重置、配置缺省回退（默认 100000）/ 非法显式拒、
 * 全链路（{@code SettleService.runSettlement} 生产路径落行 →
 * {@code GET /audit/report/major-event} 端点读出）与
 * 全链路盘中（{@code IntradayRiskMonitor.evaluate} → {@code AsyncRiskMetricSink} →
 * {@code CompositeRiskMetricSink} → 触发器）。触发行全部由生产路径产生，
 * <b>无任何直插 {@code t_major_event} 伪造触发</b>。
 */
class MajorEventLossTriggerTest {

    private static final int SUB = 11;
    private static final int OTHER = 12;
    private static final long PRODUCT = 55L;
    private static final long SYSTEM_OP = 0L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private DmMajorEventDao majorEventDao;
    private DmSettlePnlDao settlePnlDao;
    private DmSettleCycleDao settleCycleDao;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : schemaScripts()) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, OTHER, "SA-B", "商品子账户B");
        M2E2eSeed.product(engine, PRODUCT, "CU", "沪铜");

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        majorEventDao = new DmMajorEventDao(tx);
        settlePnlDao = new DmSettlePnlDao(tx);
        settleCycleDao = new DmSettleCycleDao(tx);
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

    /** 缺键（回退默认 100000）的触发器。 */
    private MajorEventLossThresholdTrigger trigger() {
        return new MajorEventLossThresholdTrigger(majorEventDao, settlePnlDao,
                () -> null, SYSTEM_OP);
    }

    private MajorEventLossThresholdTrigger trigger(String rawConfigValue) {
        return new MajorEventLossThresholdTrigger(majorEventDao, settlePnlDao,
                () -> rawConfigValue, SYSTEM_OP);
    }

    /** 预置一张 PENDING 结算单（{@code t_settle_pnl} 外键承载；正式结算复用之）。 */
    private SettleCycle seedCycle(int subAccountId, LocalDate date) {
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
        c.equity = BigDecimal.ZERO;
        c.negativeFundFlag = "N";
        c.voidedFlag = "N";
        settleCycleDao.insert(c, SYSTEM_OP);
        assertTrue(c.id > 0, "结算单应取到生成主键");
        return c;
    }

    /** 播种一行结算盈亏（模拟未来结算链写入方；本单测试的<b>输入数据</b>，非触发行）。 */
    private void seedPnl(int subAccountId, LocalDate date, long cycleId,
                         String instrumentId, String amount) {
        SettlePnl p = new SettlePnl();
        p.subAccountId = subAccountId;
        p.settleCycleId = cycleId;
        p.tradeDate = date;
        p.instrumentId = instrumentId;
        p.productId = PRODUCT;
        p.pnlType = "CLOSE_REALIZED";
        p.amount = new BigDecimal(amount);
        settlePnlDao.insert(p, SYSTEM_OP);
    }

    private List<Map<String, Object>> majorRows() {
        return engine.table("t_major_event").rows;
    }

    private Map<String, Object> singleRow() {
        assertEquals(1, majorRows().size(), "应恰有 1 行触发行");
        return majorRows().get(0);
    }

    // ================================================================ 轨 1：EOD 已实现（SETTLED）

    @Test
    @DisplayName("轨1：t_settle_pnl 子账户当日求和 -150000 越阈值（默认 100000）⇒ 触发 1 行 SETTLED")
    void eodFiresWhenLossBeyondThreshold() {
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-100000");
        seedPnl(SUB, DAY, c.id, "au2612", "-50000");

        int fired = trigger().evaluateSettled(DAY);

        assertEquals(1, fired, "应触发 1 行");
        Map<String, Object> row = singleRow();
        assertEquals("LOSS_THRESHOLD", row.get("event_type"));
        assertEquals("TRIGGERED", row.get("status"));
        assertEquals("SETTLED", row.get("trigger_source"));
        assertEquals(SUB, ((Number) row.get("sub_account_id")).intValue());
        assertEquals("LOSS-20261010-" + SUB + "-STLD", row.get("event_no"));
        String desc = String.valueOf(row.get("event_desc"));
        assertTrue(desc.contains("-150000"), "描述应携带当日求和快照值：" + desc);
        assertTrue(desc.contains("100000"), "描述应携带阈值：" + desc);
    }

    @Test
    @DisplayName("轨1：恰等阈值（-100000）不触发（严格大于）")
    void eodExactThresholdDoesNotFire() {
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-60000");
        seedPnl(SUB, DAY, c.id, "au2612", "-40000");

        assertEquals(0, trigger().evaluateSettled(DAY), "恰等阈值不得触发");
        assertTrue(majorRows().isEmpty(), "不得落行");
    }

    @Test
    @DisplayName("轨1：未越限（小额亏损）与盈利均不落行")
    void eodBelowThresholdOrProfitDoesNotFire() {
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-50000");

        assertEquals(0, trigger().evaluateSettled(DAY), "小额亏损不触发");

        seedPnl(SUB, DAY, c.id, "au2612", "200000");
        assertEquals(0, trigger().evaluateSettled(DAY), "净盈利不触发");
        assertTrue(majorRows().isEmpty());
    }

    @Test
    @DisplayName("幂等：同日同源二次评估不重复落行（确定性 eventNo + uk_major_event_no）")
    void idempotentSameDaySameSource() {
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-150000");

        assertEquals(1, trigger().evaluateSettled(DAY));
        assertEquals(0, trigger().evaluateSettled(DAY), "同日同源二次触发应被幂等去重");
        assertEquals(1, majorRows().size());
    }

    @Test
    @DisplayName("自然日重置：次日同源可再触发（eventNo 内日期更替）")
    void naturalDayReset() {
        SettleCycle d1 = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, d1.id, "cu2612", "-150000");
        LocalDate next = DAY.plusDays(1);
        SettleCycle d2 = seedCycle(SUB, next);
        seedPnl(SUB, next, d2.id, "cu2612", "-150000");

        MajorEventLossThresholdTrigger t = trigger();
        assertEquals(1, t.evaluateSettled(DAY));
        assertEquals(1, t.evaluateSettled(next), "次日应自然重置、可再触发");
        assertEquals(2, majorRows().size());
    }

    // ================================================================ 轨 2：盘中镜像（INTRADAY_MIRROR）

    @Test
    @DisplayName("轨2：镜像 floatPnl -120000 越阈值 ⇒ 触发 1 行 INTRADAY_MIRROR（含快照值，提醒级）")
    void intradayFiresWhenMirrorLossBeyond() {
        SubAccountRiskMetrics sub = new SubAccountRiskMetrics(SUB);
        sub.floatPnl = new BigDecimal("-120000");
        SubAccountRiskMetrics agg = new SubAccountRiskMetrics(0);
        agg.floatPnl = new BigDecimal("-120000");
        RiskEvaluation eval = new RiskEvaluation(
                new com.hedgeguard.risk_intraday.MirrorMetrics(ReplayProgress.State.READY, 42L, 7L,
                        DAY.atTime(11, 0).toString(), 0L, false,
                        Collections.singletonList(sub), agg),
                Collections.emptyList(), Collections.emptyList(), Collections.emptySet(),
                Clocks.epochMilli());

        trigger().publish(eval);

        Map<String, Object> row = singleRow();
        assertEquals("INTRADAY_MIRROR", row.get("trigger_source"));
        assertEquals("TRIGGERED", row.get("status"));
        assertEquals("LOSS-20261010-" + SUB + "-MIRR", row.get("event_no"));
        String desc = String.valueOf(row.get("event_desc"));
        assertTrue(desc.contains("-120000"), "描述应携带镜像快照值：" + desc);
        assertTrue(desc.contains("提醒级"), "裁定边界应落描述（镜像禁止记账）：" + desc);
        // 铁律 4：镜像轨只落提醒行，不得产生任何记账写操作（t_major_event 即唯一副作用）
        assertEquals(1, engine.table("t_major_event").rows.size());
    }

    @Test
    @DisplayName("轨2：恰等阈值不触发；汇总行（哨兵 0）非触发主体")
    void intradayExactAndAggregateNotFire() {
        SubAccountRiskMetrics exact = new SubAccountRiskMetrics(SUB);
        exact.floatPnl = new BigDecimal("-100000");
        SubAccountRiskMetrics agg = new SubAccountRiskMetrics(0);
        agg.floatPnl = new BigDecimal("-999999");
        RiskEvaluation eval = new RiskEvaluation(
                new com.hedgeguard.risk_intraday.MirrorMetrics(ReplayProgress.State.READY, 1L, 1L,
                        DAY.atTime(11, 0).toString(), 0L, false,
                        Collections.singletonList(exact), agg),
                Collections.emptyList(), Collections.emptyList(), Collections.emptySet(),
                Clocks.epochMilli());

        trigger().publish(eval);

        assertTrue(majorRows().isEmpty(), "恰等阈值不触发；汇总行非主体");
    }

    @Test
    @DisplayName("两源共存：同子账户同日 SETTLED + INTRADAY_MIRROR 各 1 行")
    void bothSourcesCoexist() {
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-150000");

        assertEquals(1, trigger().evaluateSettled(DAY));

        SubAccountRiskMetrics sub = new SubAccountRiskMetrics(SUB);
        sub.floatPnl = new BigDecimal("-120000");
        trigger().publish(new RiskEvaluation(
                new com.hedgeguard.risk_intraday.MirrorMetrics(ReplayProgress.State.READY, 42L, 7L,
                        DAY.atTime(11, 0).toString(), 0L, false,
                        Collections.singletonList(sub), sub),
                Collections.emptyList(), Collections.emptyList(), Collections.emptySet(),
                Clocks.epochMilli()));

        assertEquals(2, majorRows().size(), "两源各 1 行");
        long settled = majorRows().stream().filter(r -> "SETTLED".equals(r.get("trigger_source"))).count();
        long mirror = majorRows().stream().filter(r -> "INTRADAY_MIRROR".equals(r.get("trigger_source"))).count();
        assertEquals(1, settled);
        assertEquals(1, mirror);
    }

    // ================================================================ 配置处置

    @Test
    @DisplayName("配置回退：缺键 ⇒ 默认 100000 生效（-100000 不触发 / -100000.01 触发）")
    void thresholdFallbackWhenKeyMissing() {
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-100000");
        assertEquals(0, trigger().evaluateSettled(DAY), "恰等默认阈值不触发");
        assertEquals(0, majorRows().size());

        seedPnl(SUB, DAY, c.id, "au2612", "-0.01");
        assertEquals(1, trigger().evaluateSettled(DAY), "合计 -100000.01 应触发（默认阈值生效）");
        assertEquals(1, majorRows().size());
    }

    @Test
    @DisplayName("配置非法：≤0 / 非数字 ⇒ 显式拒（IllegalStateException，不回退默认、不落行）")
    void invalidThresholdExplicitlyRejected() {
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-150000");

        assertThrows(IllegalStateException.class, () -> trigger("0").evaluateSettled(DAY));
        assertThrows(IllegalStateException.class, () -> trigger("-5").evaluateSettled(DAY));
        assertThrows(IllegalStateException.class, () -> trigger("abc").evaluateSettled(DAY));
        assertTrue(majorRows().isEmpty(), "显式拒不得落行");
        assertNull(majorEventDao.findByEventNo("LOSS-20261010-" + SUB + "-STLD"));
    }

    @Test
    @DisplayName("正向取值生效：配置 50000 ⇒ -60000 即触发（非默认阈值）")
    void configuredThresholdTakesEffect() {
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-60000");

        assertEquals(1, trigger("50000").evaluateSettled(DAY));
        String desc = String.valueOf(singleRow().get("event_desc"));
        assertTrue(desc.contains("50000"), "描述应携带生效阈值：" + desc);
    }

    // ================================================================ 全链路：生产路径 → 端点读出

    @Test
    @DisplayName("全链路（轨1）：runSettlement 生产路径落行 → GET /audit/report/major-event 读出")
    void fullChainSettlementFiresAndEndpointReads() {
        // 预置结算单与盈亏（盈亏行为未来结算链写入方的替身输入；触发行本身由生产路径产生）
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-150000");

        MajorEventLossThresholdTrigger trigger = trigger();
        SettleService settleService = newSettleService(trigger);

        // 生产路径：CTP 日终入库 → 执行结算（结算事务提交后触发器接线被调用）
        settleService.ingestCtpData(ctpRequest(), SYSTEM_OP);
        List<SettleCycle> cycles = settleService.runSettlement(settlementInput(), SYSTEM_OP);
        assertFalse(cycles.isEmpty(), "结算应产出结算单");

        assertEquals(1, majorRows().size(), "结算完成应触发 1 行（生产路径真实落行）");
        assertEquals("SETTLED", singleRow().get("trigger_source"));

        // 端点读出：GET /api/v1/audit/report/major-event（无 reportNo ⇒ 待生成提醒清单）
        AuditReportService reportService = new AuditReportService(null, majorEventDao,
                null, null, null, null, null, null, null);
        HttpResult result = new AuditReportHandler(AuditReportHandler.MODE_MAJOR_EVENT, reportService)
                .handle(new RequestContext("GET", AuditReportHandler.PATH_MAJOR_EVENT, null,
                        new HashMap<>(), null, "t188", "127.0.0.1"));
        assertEquals(200, result.status);
        String body = new String(result.body, StandardCharsets.UTF_8);
        assertTrue(body.contains("LOSS-20261010-" + SUB + "-STLD"),
                "端点响应应含触发行事项编号：" + body);
        assertTrue(body.contains("LOSS_THRESHOLD"), "端点响应应含事项类型：" + body);
    }

    @Test
    @DisplayName("失败隔离（轨1）：触发器抛错（配置非法）不得阻断结算主流程，且不落行")
    void settlementCompletesWhenTriggerFails() {
        SettleCycle c = seedCycle(SUB, DAY);
        seedPnl(SUB, DAY, c.id, "cu2612", "-150000");

        MajorEventLossThresholdTrigger broken = trigger("-5"); // 非法阈值 ⇒ evaluateSettled 抛
        SettleService settleService = newSettleService(broken);

        settleService.ingestCtpData(ctpRequest(), SYSTEM_OP);
        List<SettleCycle> cycles = settleService.runSettlement(settlementInput(), SYSTEM_OP);

        assertFalse(cycles.isEmpty(), "结算主流程不得因触发器失败而失败");
        assertTrue(majorRows().isEmpty(), "非法阈值不得落行");
    }

    @Test
    @DisplayName("全链路（轨2）：monitor.evaluate → AsyncSink → CompositeSink → 触发器落行（R-RISK-10 异步下游）")
    void fullChainIntradayViaMonitorAndAsyncSink() throws Exception {
        MajorEventLossThresholdTrigger trigger = trigger();
        RiskIntradayPersistenceSink persistence = new RiskIntradayPersistenceSink(tx,
                new com.hedgeguard.dao.impl.DmRiskAlertDao(tx),
                new com.hedgeguard.dao.impl.DmRiskMetricSnapshotDao(tx),
                new com.hedgeguard.dao.impl.DmMirrorWatermarkDao(tx),
                new com.hedgeguard.dao.impl.DmAbnormalOrderStatDao(tx),
                null, null, RiskIntradayPersistenceSink.DEFAULT_COMPONENT_ID, SYSTEM_OP, 1000L);
        com.hedgeguard.bridge.stub.InMemoryTradingBridge bridge =
                new com.hedgeguard.bridge.stub.InMemoryTradingBridge();
        bridge.setMirror(mirrorSnapshot("-120000"));
        IntradayRiskMonitor monitor = new IntradayRiskMonitor(bridge, RiskThresholds.defaults(),
                null,
                new AsyncRiskMetricSink(new CompositeRiskMetricSink(persistence, trigger)),
                null, SYSTEM_OP);

        // 生产路径：事件消费线程调 evaluate()（只算 + 非阻塞投递），落行在工作线程完成
        monitor.evaluate();

        Map<String, Object> row = null;
        for (int i = 0; i < 50 && row == null; i++) {
            Thread.sleep(100L);
            for (Map<String, Object> r : majorRows()) {
                if ("INTRADAY_MIRROR".equals(r.get("trigger_source"))) {
                    row = r;
                }
            }
        }
        assertNotNull(row, "异步下游应在超时前落行（生产路径真实产生）");
        assertEquals("LOSS-20261010-" + SUB + "-MIRR", row.get("event_no"));
        assertTrue(String.valueOf(row.get("event_desc")).contains("-120000"));
    }

    // ================================================================ 装配辅助（全链路）

    private SettleService newSettleService(MajorEventLossThresholdTrigger trigger) {
        SettleService service = new SettleService(tx, userId -> Arrays.asList(SUB, OTHER),
                new DmSettleCtpBatchDao(tx), new DmSettleCtpPositionDao(tx),
                new DmSettleCtpTradeDao(tx), new DmSettleCtpFundDao(tx),
                settleCycleDao, new DmSettlePositionDao(tx),
                new DmSettleFundDao(tx), settlePnlDao,
                new DmSettleReconcileRecordDao(tx), new DmSettleReconcileDifferenceDao(tx),
                new DmSettleAggregationCheckDao(tx), new DmSettleAggDiffCandidateDao(tx),
                new DmSettleReportDao(tx), new DmSettleStressTestDao(tx));
        service.setLossThresholdTrigger(trigger);
        return service;
    }

    private SettleService.CtpDataRequest ctpRequest() {
        SettleService.CtpDataRequest r = new SettleService.CtpDataRequest();
        r.tradeDate = DAY.toString();
        r.sourceIdentifier = "CTP-T188";
        r.positions = new ArrayList<>();
        SettleService.CtpPositionRow p = new SettleService.CtpPositionRow();
        p.instrumentId = "cu2612";
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = "1";
        r.positions.add(p);
        r.funds = new ArrayList<>();
        SettleService.CtpFundRow f = new SettleService.CtpFundRow();
        f.equity = "1000";
        f.availableFund = "800";
        f.marginOccupied = "200";
        r.funds.add(f);
        r.trades = new ArrayList<>();
        return r;
    }

    private SettleService.SettlementInput settlementInput() {
        SettleService.SettlementInput in = new SettleService.SettlementInput();
        in.tradeDate = DAY.toString();
        in.positions = new ArrayList<>();
        SettleService.SystemPositionRow p = new SettleService.SystemPositionRow();
        p.subAccountId = SUB;
        p.instrumentId = "cu2612";
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = "1";
        p.instrumentType = "FUTURES";
        p.productId = PRODUCT;
        in.positions.add(p);
        in.funds = new ArrayList<>();
        SettleService.SystemFundRow f = new SettleService.SystemFundRow();
        f.subAccountId = SUB;
        f.beginBalance = "0";
        f.equity = "1000";
        f.ledgerEquity = "1000";
        f.availableFund = "800";
        f.marginOccupied = "200";
        f.closePnl = "0";
        f.floatPnl = "0";
        f.commission = "0";
        in.funds.add(f);
        in.trades = new ArrayList<>();
        return in;
    }

    private com.hedgeguard.bridge.MirrorSnapshot mirrorSnapshot(String floatPnl) {
        PositionView p = new PositionView();
        p.subAccountId = SUB;
        p.instrumentId = "cu2612";
        p.productId = PRODUCT;
        p.positionQty = "1";
        p.availableQty = "1";
        p.avgPrice = "70000";
        p.floatPnl = floatPnl;
        p.marginOccupied = "0";
        return new com.hedgeguard.bridge.MirrorSnapshot(ReplayProgress.State.READY, 42L, 7L,
                DAY.atTime(11, 0).toString(),
                Collections.singletonList(p), Collections.emptyList());
    }
}
