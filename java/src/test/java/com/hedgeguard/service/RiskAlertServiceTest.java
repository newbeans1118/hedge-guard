package com.hedgeguard.service;

import com.hedgeguard.bridge.ReplayProgress;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.entity.AbnormalOrderStat;
import com.hedgeguard.dao.entity.MirrorWatermark;
import com.hedgeguard.dao.entity.RiskAlert;
import com.hedgeguard.dao.entity.RiskDisposal;
import com.hedgeguard.dao.entity.RiskMetricSnapshot;
import com.hedgeguard.dao.impl.DmAbnormalOrderStatDao;
import com.hedgeguard.dao.impl.DmMirrorWatermarkDao;
import com.hedgeguard.dao.impl.DmRiskAlertDao;
import com.hedgeguard.dao.impl.DmRiskDisposalDao;
import com.hedgeguard.dao.impl.DmRiskMetricSnapshotDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.risk_intraday.AbnormalOrderTracker;
import com.hedgeguard.risk_intraday.DisposalAction;
import com.hedgeguard.risk_intraday.MirrorMetrics;
import com.hedgeguard.risk_intraday.ProductAbnormalStat;
import com.hedgeguard.risk_intraday.RiskAlertLevel;
import com.hedgeguard.risk_intraday.RiskEvaluation;
import com.hedgeguard.risk_intraday.RiskMetricType;
import com.hedgeguard.risk_intraday.RiskThresholds;
import com.hedgeguard.risk_intraday.SubAccountRiskMetrics;
import com.hedgeguard.risk_intraday.TradeAction;
import com.hedgeguard.risk_intraday.TradeActivity;
import com.hedgeguard.risk_intraday.TradingSuspendControl;
import com.hedgeguard.service.impl.RiskIntradayPersistenceSink;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-065} 告警落库 / 处置 / 查询 / 异常统计「真实 DAO + 内存假 JDBC 引擎」用例（禁假 DAO）。
 *
 * <p>把生产建表脚本真喂 {@link InMemoryDmEngine} 建表，再由<b>真实</b> {@code Dm*Dao} 读写。
 * 覆盖：sink 落 t_risk_alert + t_risk_metric_snapshot + t_mirror_watermark + t_abnormal_order_stat、
 * 类型派生、FR-RISK-19 查询（级别/since/默认倒序/分页/可见性隔离）、FR-RISK-15/16 处置
 * （限制/解除/强制平仓提示）、下发失败不视为生效、写面权限。
 */
class RiskAlertServiceTest {

    private static final int SUB = 11;
    private static final int OTHER = 12;
    private static final long PRODUCT = 55L;
    private static final long OPERATOR = 9001L;
    private static final long SYSTEM = 900L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);

    private InMemoryDmEngine engine;
    private DmRiskAlertDao alertDao;
    private DmRiskDisposalDao disposalDao;
    private DmRiskMetricSnapshotDao snapshotDao;
    private DmMirrorWatermarkDao watermarkDao;
    private DmAbnormalOrderStatDao abnormalDao;

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
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        alertDao = new DmRiskAlertDao(tx);
        disposalDao = new DmRiskDisposalDao(tx);
        snapshotDao = new DmRiskMetricSnapshotDao(tx);
        watermarkDao = new DmMirrorWatermarkDao(tx);
        abnormalDao = new DmAbnormalOrderStatDao(tx);
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
                "docs/DEPLOY/schema/m3_risk.sql");
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/DEPLOY/schema"))) {
            p = p.getParent();
        }
        assertNotNull(p, "未找到仓库根（docs/DEPLOY/schema）");
        return p;
    }

    // ============================================================ sink 落库

    @Test
    @DisplayName("sink：告警落 t_risk_alert（FR-RISK-15 类型派生）+ 指标快照 + 镜像水位")
    void sinkPersistsAlertsMetricsWatermark() throws Exception {
        List<String> notified = new ArrayList<>();
        RiskIntradayPersistenceSink sink = sink(notified, null);

        SubAccountRiskMetrics agg = metrics(0, "100", "0.95", "0.80");
        SubAccountRiskMetrics sub = metrics(SUB, "100", null, "0.80");
        MirrorMetrics mm = new MirrorMetrics(ReplayProgress.State.READY, 42L, 7L,
                DAY.atTime(11, 0).toString(), 0L, false,
                Collections.singletonList(sub), agg);

        com.hedgeguard.risk_intraday.RiskAlert margin = new com.hedgeguard.risk_intraday.RiskAlert(
                SUB, RiskMetricType.MARGIN_USAGE_RATE, RiskAlertLevel.WARNING, "0.95", "0.90",
                DAY.atTime(11, 0).toString(), "保证金占用率超限");
        com.hedgeguard.risk_intraday.RiskAlert lag = new com.hedgeguard.risk_intraday.RiskAlert(
                0, RiskMetricType.POSITION, RiskAlertLevel.WARNING, "31000", "30000",
                DAY.atTime(11, 0).toString(), "镜像状态明显滞后");
        lag.alertType = "MIRROR_LAG";
        RiskEvaluation eval = new RiskEvaluation(mm, Arrays.asList(margin, lag),
                Collections.emptyList(), Collections.emptySet(), DAY.atTime(11, 0).atZone(Clocks.ZONE).toInstant().toEpochMilli());

        sink.publish(eval);

        List<RiskAlert> rows = alertDao.findBySubAccount(SUB);
        assertEquals(1, rows.size(), "子账户告警应落 1 行");
        assertEquals("MARGIN_RATIO", rows.get(0).alertType, "MARGIN_USAGE_RATE 须派生为 MARGIN_RATIO");
        assertEquals("N", rows.get(0).disposed);
        assertEquals(2, notified.size(), "两条告警均应推送");

        List<RiskMetricSnapshot> snaps = snapshotDao.findBySubAccount(SUB);
        assertEquals(1, snaps.size(), "子账户指标快照应落 1 行");
        assertEquals("EVENT", snaps.get(0).snapshotSource);
        assertEquals(42L, snaps.get(0).mirrorSeq);
        assertEquals(1, snapshotDao.findBySubAccount(0).size(), "汇总（哨兵 0）快照亦应落库");

        MirrorWatermark wm = watermarkDao.findByComponentAndStream("hg-trading", "EVENT");
        assertNotNull(wm, "镜像水位应落库");
        assertEquals(42L, wm.lastConsumedSeq);
        assertEquals("N", wm.lagAlarm);
    }

    @Test
    @DisplayName("sink：异常下单统计按（子账户×品种×窗口）幂等 upsert，count 单调更新不新增行")
    void sinkUpsertsAbnormalStat() throws Exception {
        AbnormalOrderTracker tracker = new AbnormalOrderTracker(Clocks.clock());
        for (int i = 0; i < 6; i++) {
            tracker.record(new TradeActivity(SUB, PRODUCT, "cu2612", TradeAction.OPEN,
                    BigDecimal.ONE, DAY.atTime(9, i)));
            tracker.record(new TradeActivity(SUB, PRODUCT, "cu2612", TradeAction.CLOSE,
                    BigDecimal.ONE, DAY.atTime(9, i).plusMinutes(5)));
        }
        RiskIntradayPersistenceSink sink = sink(new ArrayList<>(),
                () -> tracker.snapshot(RiskThresholds.defaults(),
                        RiskIntradayPersistenceSink.INTRADAY_WINDOW_MINUTES,
                        RiskIntradayPersistenceSink.ROLLING_WINDOW_MINUTES));

        sink.publish(evaluationWithAbnormal(SUB));
        List<AbnormalOrderStat> first = abnormalDao.findByStatDate(DAY);
        assertFalse(first.isEmpty(), "异常下单统计应落库");
        AbnormalOrderStat intraday = pick(first, SUB, PRODUCT, 1440);
        assertNotNull(intraday);
        assertEquals(12, intraday.openCloseCount, "日内开平合计 = 6 开 + 6 平");
        assertEquals(6, intraday.roundTripCount);
        assertEquals("Y", intraday.isAbnormal);
        assertEquals("ALERT_AND_SUSPEND_OPEN", intraday.actionTaken);

        // 再推一次同一自然键（计数不变）⇒ 幂等：不新增行
        sink.publish(evaluationWithAbnormal(SUB));
        assertEquals(first.size(), abnormalDao.findByStatDate(DAY).size(), "同自然键须 upsert 不新增");
    }

    // ============================================================ FR-RISK-19 查询

    @Test
    @DisplayName("查询：级别筛选正确 + 未处置回填 N（FR-RISK-19 验收 1/2）")
    void queryFiltersByLevel() throws Exception {
        insertAlert(SUB, "RISK_RATIO", "CRITICAL", "1", "0.9", DAY.atTime(10, 0), "N");
        insertAlert(SUB, "RISK_RATIO", "WARNING", "0.8", "0.7", DAY.atTime(10, 5), "N");
        RiskAlertService service = service(id -> Arrays.asList(SUB, OTHER));

        RiskAlertService.AlertQuery q = new RiskAlertService.AlertQuery();
        q.alertLevel = "CRITICAL";
        RiskAlertService.AlertPage page = service.queryAlerts(q, PageQuery.of(null, null, null,
                "alert_time", "desc"), OPERATOR);
        assertEquals(1, page.total, "仅 CRITICAL 1 条");
        assertEquals("N", page.items.get(0).disposed, "未处置须显式标注 N");
        assertNull(page.items.get(0).disposalType);
    }

    @Test
    @DisplayName("查询：默认按时间倒序 + 主键兜底全序（跨页无重复/漏行）")
    void queryDefaultDescStable() throws Exception {
        for (int i = 0; i < 25; i++) {
            insertAlert(SUB, "RISK_RATIO", "WARNING", "0.8", "0.7",
                    DAY.atTime(10, 0), "N");   // 同排序值（time 相同）⇒ 依赖 id 兜底
        }
        RiskAlertService service = service(id -> Arrays.asList(SUB, OTHER));
        RiskAlertService.AlertPage p1 = service.queryAlerts(new RiskAlertService.AlertQuery(),
                PageQuery.of("1", "20", null, "alert_time", "desc"), OPERATOR);
        RiskAlertService.AlertPage p2 = service.queryAlerts(new RiskAlertService.AlertQuery(),
                PageQuery.of("2", "20", null, "alert_time", "desc"), OPERATOR);
        assertEquals(25, p1.total);
        assertEquals(20, p1.items.size());
        assertEquals(5, p2.items.size());
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (RiskAlertService.AlertView v : p1.items) {
            assertTrue(ids.add(v.id), "跨页重复行：" + v.id);
        }
        for (RiskAlertService.AlertView v : p2.items) {
            assertTrue(ids.add(v.id), "跨页重复行：" + v.id);
        }
        assertEquals(25, ids.size(), "两页并集应为全集（无漏行）");
    }

    @Test
    @DisplayName("查询：可见性隔离——有限授权仅见己；跨账户只读见全部；空集恒假")
    void querySubAccountIsolation() throws Exception {
        insertAlert(SUB, "RISK_RATIO", "WARNING", "0.8", "0.7", DAY.atTime(10, 0), "N");
        insertAlert(OTHER, "RISK_RATIO", "WARNING", "0.8", "0.7", DAY.atTime(10, 1), "N");

        RiskAlertService limited = service(id -> Collections.singletonList(SUB));
        assertEquals(1, limited.queryAlerts(new RiskAlertService.AlertQuery(),
                PageQuery.of(null, null, null, "alert_time", "desc"), 1L).total,
                "有限授权仅见 SUB");

        RiskAlertService crossReadOnly = service(id -> Collections.singletonList(0));
        assertEquals(2, crossReadOnly.queryAlerts(new RiskAlertService.AlertQuery(),
                PageQuery.of(null, null, null, "alert_time", "desc"), 2L).total,
                "跨账户只读见全部");

        RiskAlertService none = service(id -> Collections.emptyList());
        assertEquals(0, none.queryAlerts(new RiskAlertService.AlertQuery(),
                PageQuery.of(null, null, null, "alert_time", "desc"), 3L).total,
                "空集恒假（不得退化为全可见）");
    }

    @Test
    @DisplayName("查询：since 断线补拉（alert_ts_ms > since）")
    void querySinceCatchUp() throws Exception {
        insertAlert(SUB, "RISK_RATIO", "WARNING", "0.8", "0.7", DAY.atTime(10, 0), "N");
        long threshold = DAY.atTime(10, 0).atZone(Clocks.ZONE).toInstant().toEpochMilli();
        insertAlert(SUB, "RISK_RATIO", "WARNING", "0.8", "0.7", DAY.atTime(10, 30), "N");
        RiskAlertService service = service(id -> Collections.singletonList(SUB));
        RiskAlertService.AlertQuery q = new RiskAlertService.AlertQuery();
        q.sinceMs = threshold;
        assertEquals(1, service.queryAlerts(q, PageQuery.of(null, null, null, "alert_time", "desc"),
                OPERATOR).total, "仅补拉 since 之后的告警");
    }

    // ============================================================ FR-RISK-15/16 处置

    @Test
    @DisplayName("处置：限制新开仓成功下发 + 告警置 N=Y 关联处置（FR-RISK-15 验收 1/4）")
    void disposeSuspendOpen() throws Exception {
        long alertId = insertAlert(SUB, "ABNORMAL_ORDER", "CRITICAL", "10", "10",
                DAY.atTime(10, 0), "N");
        StubSuspendControl ctl = new StubSuspendControl(true);
        RiskAlertService service = new RiskAlertService(txTemplate(), alertDao, disposalDao,
                id -> Arrays.asList(SUB), ctl);

        RiskAlertService.DisposeInput in = new RiskAlertService.DisposeInput();
        in.action = RiskAlertService.ACTION_SUSPEND_OPEN;
        in.reason = "日内开平合计超严重阈值";
        RiskAlertService.DisposalView view = service.dispose(alertId, in, OPERATOR);

        assertEquals("SUSPEND_OPEN", view.disposalType);
        assertEquals("OPEN_ONLY", view.scanScope, "限制范围恒 OPEN_ONLY（R-RISK-5）");
        assertEquals("SUCCESS", view.dispatchStatus);
        assertEquals(1, ctl.suspends, "应下发一次限制");

        RiskAlert alert = alertDao.findById(alertId);
        assertEquals("Y", alert.disposed);
        assertEquals(Long.valueOf(view.id), alert.disposalId);
    }

    @Test
    @DisplayName("处置：下发未生效不视为生效（阻断 10014），处置留痕 FAIL，告警仍未处置")
    void disposeSuspendFailsNotEffective() throws Exception {
        long alertId = insertAlert(SUB, "ABNORMAL_ORDER", "CRITICAL", "10", "10",
                DAY.atTime(10, 0), "N");
        StubSuspendControl ctl = new StubSuspendControl(false);
        RiskAlertService service = new RiskAlertService(txTemplate(), alertDao, disposalDao,
                id -> Arrays.asList(SUB), ctl);

        RiskAlertService.DisposeInput in = new RiskAlertService.DisposeInput();
        in.action = RiskAlertService.ACTION_SUSPEND_OPEN;
        in.reason = "test";
        BizException e = assertThrows(BizException.class, () -> service.dispose(alertId, in, OPERATOR));
        assertEquals(ErrorCode.DISPATCH_NOT_EFFECTIVE, e.errorCode());

        List<RiskDisposal> all = disposalDao.findActiveBySubAccount(SUB);
        assertEquals(1, all.size(), "处置记录仍须留痕（FAIL）");
        assertEquals("FAIL", all.get(0).dispatchStatus);
        assertEquals("N", alertDao.findById(alertId).disposed, "下发未生效 ⇒ 告警不得标为已处置");
    }

    @Test
    @DisplayName("处置：解除限制置 released_time + 下发（FR-RISK-15 验收 4）")
    void disposeRelease() throws Exception {
        long alertId = insertAlert(SUB, "ABNORMAL_ORDER", "CRITICAL", "10", "10",
                DAY.atTime(10, 0), "N");
        StubSuspendControl ctl = new StubSuspendControl(true);
        RiskAlertService service = new RiskAlertService(txTemplate(), alertDao, disposalDao,
                id -> Arrays.asList(SUB), ctl);

        RiskAlertService.DisposeInput suspend = new RiskAlertService.DisposeInput();
        suspend.action = RiskAlertService.ACTION_SUSPEND_OPEN;
        suspend.reason = "限制";
        RiskAlertService.DisposalView suspended = service.dispose(alertId, suspend, OPERATOR);

        RiskAlertService.DisposeInput release = new RiskAlertService.DisposeInput();
        release.action = RiskAlertService.ACTION_RELEASE_SUSPEND;
        release.reason = "解除";
        service.dispose(alertId, release, OPERATOR);

        assertEquals(1, ctl.releases);
        RiskDisposal d = disposalDao.findById(suspended.id);
        assertNotNull(d.releasedTime, "被解除的限制须置 released_time");
        assertTrue(disposalDao.findActiveBySubAccount(SUB).stream()
                        .noneMatch(x -> RiskAlertService.ACTION_SUSPEND_OPEN.equals(x.disposalType)),
                "解除后不应仍有生效中的限制");
    }

    @Test
    @DisplayName("处置：强制平仓提示——拒绝须填理由，接受则不填（FR-RISK-16 验收 4）")
    void disposeForceCloseHint() throws Exception {
        long alertId = insertAlert(SUB, "RISK_RATIO", "CRITICAL", "0.95", "0.90",
                DAY.atTime(10, 0), "N");
        RiskAlertService service = service(id -> Arrays.asList(SUB));

        RiskAlertService.DisposeInput reject = new RiskAlertService.DisposeInput();
        reject.action = RiskAlertService.ACTION_FORCE_CLOSE_HINT;
        reject.reason = "风险处置";
        reject.hintAccepted = "N";
        BizException e = assertThrows(BizException.class, () -> service.dispose(alertId, reject, OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode(), "拒绝提示未填理由须拒");

        reject.rejectReason = "已有对冲安排";
        RiskAlertService.DisposalView v = service.dispose(alertId, reject, OPERATOR);
        assertEquals("FORCE_CLOSE_HINT", v.disposalType);
        assertNull(v.dispatchStatus, "仅提示不下发");
    }

    @Test
    @DisplayName("处置：跨账户只读写面被拒（FR-AUTH-13 验收 3 / NFR-SEC-7）")
    void disposeWriteFaceDeniedForCrossReadOnly() throws Exception {
        long alertId = insertAlert(SUB, "RISK_RATIO", "WARNING", "0.8", "0.7",
                DAY.atTime(10, 0), "N");
        RiskAlertService service = new RiskAlertService(txTemplate(), alertDao, disposalDao,
                id -> Collections.singletonList(0), new StubSuspendControl(true));
        RiskAlertService.DisposeInput in = new RiskAlertService.DisposeInput();
        in.action = RiskAlertService.ACTION_ALERT_ONLY;
        in.reason = "仅标记";
        BizException e = assertThrows(BizException.class, () -> service.dispose(alertId, in, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode(),
                "跨账户只读（哨兵 0）不得写子账户数据");
    }

    // ============================================================ helpers

    private RiskAlertService service(SubAccountVisibility visibility) {
        return new RiskAlertService(txTemplate(), alertDao, disposalDao, visibility,
                new StubSuspendControl(true));
    }

    private TxTemplate txTemplate() {
        return new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
    }

    private RiskIntradayPersistenceSink sink(List<String> notified,
                                             java.util.function.Supplier<List<ProductAbnormalStat>> abnormal) {
        return new RiskIntradayPersistenceSink(txTemplate(), alertDao, snapshotDao, watermarkDao,
                abnormalDao,
                (sub, level, metric, value, threshold) -> notified.add(sub + "/" + level + "/" + metric),
                abnormal, "hg-trading", SYSTEM, 30000L);
    }

    private long insertAlert(int sub, String type, String level, String value, String threshold,
                             LocalDateTime time, String disposed) {
        RiskAlert row = new RiskAlert();
        row.subAccountId = sub;
        row.alertType = type;
        row.alertLevel = level;
        row.metricName = type;
        row.metricValue = value;
        row.thresholdValue = threshold;
        row.alertTsMs = time.atZone(Clocks.ZONE).toInstant().toEpochMilli();
        row.alertTime = time;
        row.disposed = disposed;
        alertDao.insert(row, SYSTEM);
        return row.id;
    }

    private static SubAccountRiskMetrics metrics(int sub, String position, String riskRatio,
                                                 String marginRatio) {
        SubAccountRiskMetrics m = new SubAccountRiskMetrics(sub);
        m.positionQty = new BigDecimal(position);
        m.marginOccupied = new BigDecimal("1000");
        m.balance = new BigDecimal("2000");
        m.available = new BigDecimal("1000");
        m.riskRatio = riskRatio == null ? null : new BigDecimal(riskRatio);
        m.marginUsageRate = marginRatio == null ? null : new BigDecimal(marginRatio);
        return m;
    }

    private RiskEvaluation evaluationWithAbnormal(int sub) {
        SubAccountRiskMetrics m = metrics(sub, "100", null, "0.80");
        MirrorMetrics mm = new MirrorMetrics(ReplayProgress.State.READY, 42L, 7L,
                DAY.atTime(11, 0).toString(), 0L, false,
                Collections.singletonList(m), metrics(0, "100", null, "0.80"));
        com.hedgeguard.risk_intraday.RiskAlert a = new com.hedgeguard.risk_intraday.RiskAlert(
                sub, RiskMetricType.ABNORMAL_ORDER, RiskAlertLevel.CRITICAL, "10", "10",
                DAY.atTime(11, 0).toString(), "日内开平合计超严重阈值");
        a.disposalAction = DisposalAction.SUSPEND_OPEN;
        return new RiskEvaluation(mm, Collections.singletonList(a), Collections.emptyList(),
                Collections.singleton(sub), DAY.atTime(11, 0).atZone(Clocks.ZONE).toInstant().toEpochMilli());
    }

    private static AbnormalOrderStat pick(List<AbnormalOrderStat> list, int sub, long product, int window) {
        for (AbnormalOrderStat e : list) {
            if (e.subAccountId != null && e.subAccountId == sub
                    && e.productId != null && e.productId == product
                    && e.windowMinutes != null && e.windowMinutes == window) {
                return e;
            }
        }
        return null;
    }

    /** 记录式下发桩（非假 DAO；只记录调用与是否回执成功）。 */
    private static final class StubSuspendControl implements TradingSuspendControl {
        private final boolean acknowledged;
        private int suspends;
        private int releases;

        StubSuspendControl(boolean acknowledged) {
            this.acknowledged = acknowledged;
        }

        @Override
        public DispatchOutcome suspendOpen(int subAccountId, String reason, long operatorId) {
            suspends++;
            return new DispatchOutcome(true, acknowledged, 1L,
                    acknowledged ? "SUCCESS" : "FAIL", "stub");
        }

        @Override
        public DispatchOutcome releaseOpen(int subAccountId, String reason, long operatorId) {
            releases++;
            return new DispatchOutcome(true, acknowledged, 2L,
                    acknowledged ? "SUCCESS" : "FAIL", "stub");
        }
    }
}
