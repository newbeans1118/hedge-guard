package com.hedgeguard.dao;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.AbnormalOrderStat;
import com.hedgeguard.dao.entity.AnnualScale;
import com.hedgeguard.dao.entity.MirrorWatermark;
import com.hedgeguard.dao.entity.RiskAlert;
import com.hedgeguard.dao.entity.RiskDisposal;
import com.hedgeguard.dao.entity.RiskMetricSnapshot;
import com.hedgeguard.dao.entity.RiskThreshold;
import com.hedgeguard.dao.impl.DmAbnormalOrderStatDao;
import com.hedgeguard.dao.impl.DmAnnualScaleDao;
import com.hedgeguard.dao.impl.DmMirrorWatermarkDao;
import com.hedgeguard.dao.impl.DmRiskAlertDao;
import com.hedgeguard.dao.impl.DmRiskDisposalDao;
import com.hedgeguard.dao.impl.DmRiskMetricSnapshotDao;
import com.hedgeguard.dao.impl.DmRiskThresholdDao;
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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-094}：RISK 余表「真实 DAO + 内存假 JDBC 引擎」逐列往返（0 差异）用例。
 *
 * <p>本机无 DM8 实例（归 {@code T-052}）⇒ 以等价执行替代：把生产建表脚本
 * {@code docs/DEPLOY/schema/m3_risk.sql} 真喂给 {@link InMemoryDmEngine} 建表，再由
 * <b>真实</b> {@code Dm*Dao} 生成 {@code INSERT}/{@code SELECT} 并真正执行。
 * <b>不使用假 DAO</b>（{@code T-136}/{@code T-058} 判例）。
 *
 * <p>判据：① 每表 insert → findById 后，实体<b>全部</b>字段（业务列 + 审计列）逐字段相等
 * （{@code BigDecimal} 按 {@code compareTo} 比）⇒ DDL 列 / {@code COLUMNS} / bind / read 四处无漂移；
 * ② 各表专用查询返回预期行；③ 引擎报告的 NOT NULL / 外键拒绝为 0。
 */
class M3RiskDaoRoundTripTest {

    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql",
            "docs/DEPLOY/schema/m2_trade.sql",
            "docs/DEPLOY/schema/m2_settle.sql",
            "docs/DEPLOY/schema/m2_fund.sql",
            "docs/DEPLOY/schema/m3_risk.sql");

    private static final long SYS = 900L;
    private static final long OPERATOR = 201L;
    private static final int SUB = 11;
    private static final long PRODUCT = 55L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 20);
    private static final LocalDateTime TS = DAY.atTime(10, 0);

    private InMemoryDmEngine engine;
    private DmAnnualScaleDao annualScaleDao;
    private DmRiskThresholdDao thresholdDao;
    private DmMirrorWatermarkDao mirrorDao;
    private DmRiskAlertDao alertDao;
    private DmRiskDisposalDao disposalDao;
    private DmAbnormalOrderStatDao abnormalDao;
    private DmRiskMetricSnapshotDao metricDao;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");
        M2E2eSeed.product(engine, PRODUCT, "CU", "沪铜");

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        annualScaleDao = new DmAnnualScaleDao(tx);
        thresholdDao = new DmRiskThresholdDao(tx);
        mirrorDao = new DmMirrorWatermarkDao(tx);
        alertDao = new DmRiskAlertDao(tx);
        disposalDao = new DmRiskDisposalDao(tx);
        abnormalDao = new DmAbnormalOrderStatDao(tx);
        metricDao = new DmRiskMetricSnapshotDao(tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("七表 insert→findById 逐字段 0 差异（业务列 + 审计列）")
    void roundTripAllColumns() {
        AnnualScale as = annualScale();
        assertEquals(1, annualScaleDao.insert(as, OPERATOR));
        assertNotNull(as.id);
        assertSameFields(as, annualScaleDao.findById(as.id));

        RiskThreshold th = threshold();
        assertEquals(1, thresholdDao.insert(th, OPERATOR));
        assertSameFields(th, thresholdDao.findById(th.id));

        MirrorWatermark mw = mirror();
        assertEquals(1, mirrorDao.insert(mw, OPERATOR));
        assertSameFields(mw, mirrorDao.findById(mw.id));

        RiskDisposal dp = disposal();
        assertEquals(1, disposalDao.insert(dp, OPERATOR));
        assertSameFields(dp, disposalDao.findById(dp.id));

        RiskAlert al = alert();
        assertEquals(1, alertDao.insert(al, OPERATOR));
        assertSameFields(al, alertDao.findById(al.id));

        AbnormalOrderStat ab = abnormal();
        assertEquals(1, abnormalDao.insert(ab, OPERATOR));
        assertSameFields(ab, abnormalDao.findById(ab.id));

        RiskMetricSnapshot ms = metric();
        assertEquals(1, metricDao.insert(ms, OPERATOR));
        assertSameFields(ms, metricDao.findById(ms.id));

        assertEquals(0, engine.rejectedByNotNull(), "不得出现 NOT NULL 拒绝");
        assertEquals(0, engine.rejectedByForeignKey(), "不得出现外键拒绝");
    }

    @Test
    @DisplayName("七表专用查询返回预期行（含 WHERE / IS NULL / ORDER BY）")
    void findersReturnExpectedRows() {
        AnnualScale as = annualScale();
        annualScaleDao.insert(as, OPERATOR);
        AnnualScale got = annualScaleDao.findByScopeAndProduct("SUB_ACCOUNT", SUB, PRODUCT, 2026);
        assertNotNull(got);
        assertEquals(as.id, got.id);

        RiskThreshold th = threshold();
        thresholdDao.insert(th, OPERATOR);
        RiskThreshold gotTh = thresholdDao.findByKey("HG.RISK.Q5.WARN", "GLOBAL", 0);
        assertNotNull(gotTh);
        assertEquals(th.id, gotTh.id);

        MirrorWatermark mw = mirror();
        mirrorDao.insert(mw, OPERATOR);
        MirrorWatermark gotMw = mirrorDao.findByComponentAndStream("hg-trading", "EVENT");
        assertNotNull(gotMw);
        assertEquals(mw.id, gotMw.id);

        RiskDisposal dp = disposal();
        disposalDao.insert(dp, OPERATOR);
        assertEquals(1, disposalDao.findActiveBySubAccount(SUB).size(),
                "released_time IS NULL 的生效处置应为 1 条");

        RiskAlert al = alert();
        alertDao.insert(al, OPERATOR);
        List<RiskAlert> alerts = alertDao.findBySubAccount(SUB);
        assertEquals(1, alerts.size());
        assertEquals(al.id, alerts.get(0).id);

        AbnormalOrderStat ab = abnormal();
        abnormalDao.insert(ab, OPERATOR);
        List<AbnormalOrderStat> abs = abnormalDao.findByStatDate(DAY);
        assertEquals(1, abs.size());
        assertEquals(ab.id, abs.get(0).id);

        RiskMetricSnapshot ms = metric();
        metricDao.insert(ms, OPERATOR);
        List<RiskMetricSnapshot> mss = metricDao.findBySubAccount(SUB);
        assertEquals(1, mss.size());
        assertEquals(ms.id, mss.get(0).id);
    }

    // ------------------------------------------------------------------ 实体构造

    private static AnnualScale annualScale() {
        AnnualScale e = new AnnualScale();
        e.subAccountId = SUB;
        e.scopeType = "SUB_ACCOUNT";
        e.productId = PRODUCT;
        e.scaleYear = 2026;
        e.expectedSpotBusinessQty = new BigDecimal("10000.0000");
        e.accumulatedHedgeQty = new BigDecimal("1234.0000");
        e.category = "COMMODITY";
        e.scaleRatioLimit = new BigDecimal("0.900000");
        e.usageRatio = new BigDecimal("0.123400");
        e.dispatchStatus = "PENDING";
        e.dispatchVersion = null;
        return e;
    }

    private static RiskThreshold threshold() {
        RiskThreshold e = new RiskThreshold();
        e.thresholdKey = "HG.RISK.Q5.WARN";
        e.thresholdValue = "6";
        e.valueType = "INT";
        e.minValue = new BigDecimal("1.000000");
        e.maxValue = new BigDecimal("100.000000");
        e.defaultValue = "6";
        e.isComplianceRedline = "Y";
        e.scopeType = "GLOBAL";
        e.subAccountId = 0;
        e.relatedQuestionNo = "Q-5";
        e.dispatchStatus = "PENDING";
        e.dispatchVersion = null;
        e.description = "日内开平合计告警线";
        return e;
    }

    private static MirrorWatermark mirror() {
        MirrorWatermark e = new MirrorWatermark();
        e.componentId = "hg-trading";
        e.streamType = "EVENT";
        e.lastConsumedSeq = 100L;
        e.lastProducedSeq = 105L;
        e.lag = 5L;
        e.lagThreshold = 100L;
        e.lagAlarm = "N";
        e.lastUpdateTime = TS;
        e.connectionState = "CONNECTED";
        return e;
    }

    private static RiskAlert alert() {
        RiskAlert e = new RiskAlert();
        e.subAccountId = SUB;
        e.alertType = "ABNORMAL_ORDER";
        e.alertLevel = "WARNING";
        e.metricName = "open_close_count";
        e.metricValue = "8";
        e.thresholdValue = "6";
        e.instrumentId = "cu2612";
        e.alertDetail = "日内开平合计 8 超告警线";
        e.alertTsMs = 1000L;
        e.alertTime = TS;
        e.disposed = "N";
        e.disposalId = null;
        return e;
    }

    private static RiskDisposal disposal() {
        RiskDisposal e = new RiskDisposal();
        e.subAccountId = SUB;
        e.isGlobal = "N";
        e.disposalType = "SUSPEND_OPEN";
        e.scanScope = "OPEN_ONLY";
        e.reason = "频繁开平仓";
        e.hintInstrumentId = null;
        e.hintDirection = null;
        e.hintVolume = null;
        e.hintAccepted = null;
        e.rejectReason = null;
        e.operatorId = OPERATOR;
        e.disposalTime = TS;
        e.dispatchStatus = "SUCCESS";
        e.dispatchVersion = 7L;
        e.releasedTime = null;
        return e;
    }

    private static AbnormalOrderStat abnormal() {
        AbnormalOrderStat e = new AbnormalOrderStat();
        e.subAccountId = SUB;
        e.productId = PRODUCT;
        e.instrumentId = "cu2612";
        e.statDate = DAY;
        e.windowMinutes = 1440;
        e.openCloseCount = 8;
        e.minHoldMinutes = 2;
        e.avgHoldMinutes = new BigDecimal("30.0000");
        e.roundTripCount = 4;
        e.lastOrderTime = TS;
        e.isAbnormal = "Y";
        e.actionTaken = "ALERT_AND_SUSPEND_OPEN";
        e.orderIds = "1,2,3";
        return e;
    }

    private static RiskMetricSnapshot metric() {
        RiskMetricSnapshot e = new RiskMetricSnapshot();
        e.subAccountId = SUB;
        e.snapshotTime = TS;
        e.snapshotDate = DAY;
        e.positionQtyNet = new BigDecimal("10.0000");
        e.floatPnl = new BigDecimal("-50.0000");
        e.marginOccupied = new BigDecimal("200.0000");
        e.marginRatio = new BigDecimal("0.150000");
        e.riskRatio = new BigDecimal("0.300000");
        e.availableFund = new BigDecimal("800.0000");
        e.equity = new BigDecimal("1000.0000");
        e.mirrorSeq = 100L;
        e.snapshotSource = "PERIODIC";
        return e;
    }

    // ------------------------------------------------------------------ 逐字段比较

    /** 反射比较实体全部实例字段（含继承的审计字段）；BigDecimal 按 compareTo。 */
    private static void assertSameFields(Object expected, Object actual) {
        assertNotNull(actual, "读回实体为 null：" + expected.getClass().getSimpleName());
        for (Class<?> c = expected.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object ve;
                Object va;
                try {
                    ve = f.get(expected);
                    va = f.get(actual);
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException("反射读字段失败：" + f.getName(), ex);
                }
                String label = expected.getClass().getSimpleName() + "." + f.getName();
                if (ve instanceof BigDecimal && va instanceof BigDecimal) {
                    assertTrue(((BigDecimal) ve).compareTo((BigDecimal) va) == 0,
                            label + " 数值不一致：期望=" + ve + " 实际=" + va);
                } else {
                    assertEquals(ve, va, label + " 不一致");
                }
            }
        }
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根；cwd=" + Paths.get("").toAbsolutePath());
    }
}
