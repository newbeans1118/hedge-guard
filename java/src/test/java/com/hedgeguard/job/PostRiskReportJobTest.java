package com.hedgeguard.job;

import com.hedgeguard.bridge.ReplayProgress;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.entity.LimitUsageStat;
import com.hedgeguard.dao.impl.DmLimitUsageStatDao;
import com.hedgeguard.dao.impl.DmPostRiskReportDao;
import com.hedgeguard.dao.impl.DmProductQuotaDao;
import com.hedgeguard.dao.impl.DmRiskViolationDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmTradePreTradeRejectDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.M3RiskReportDdl;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.risk_intraday.MirrorMetrics;
import com.hedgeguard.risk_intraday.RiskAlert;
import com.hedgeguard.risk_intraday.RiskAlertLevel;
import com.hedgeguard.risk_intraday.RiskEvaluation;
import com.hedgeguard.risk_intraday.RiskMetricType;
import com.hedgeguard.risk_intraday.SubAccountRiskMetrics;
import com.hedgeguard.service.PostRiskReportResult;
import com.hedgeguard.service.PostRiskReportService;
import com.hedgeguard.service.impl.PostRiskReportServiceImpl;
import com.hedgeguard.web.BizException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-180} 事后报表层端到端用例（{@code FR-RISK-17}/{@code FR-RISK-18}）。
 *
 * <p>细化 {@code docs/TEST_PLAN/M3.md}：
 * <ul>
 *   <li>{@code TC-M3-28} 报表落库并与当日结算数据勾稽（唯一预期值：报表落库行数 {@code == 1}／
 *       与 {@code SETTLE} 差异 {@code == 0}）；</li>
 *   <li>{@code TC-M3-29} 违规事项按类型汇总 + 限额使用率（子账户 × 品种）（唯一预期值：六类覆盖；
 *       {@code >=80%} 提示 / {@code >=95%} 警告计数正确）。</li>
 * </ul>
 *
 * <p><b>真实 DAO + 内存假 JDBC 引擎等价执行</b>（本机无 DM8 实例）：装配 {@code Dm*Dao} 与
 * {@code PostRiskReportServiceImpl}，内联 DDL 见 {@link M3RiskReportDdl}（{@code DATA_MODEL.md}
 * §2.8 列级转录，裁剪范围见其类注释；生产 DDL 归 {@code T-094}）。<b>不使用假 DAO</b>
 * （{@code T-136}/{@code T-058} 判例）。
 */
class PostRiskReportJobTest {

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
    private static final int SUB_A = 11;
    private static final int SUB_B = 12;
    private static final long PRODUCT_ID = 55L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 20);

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private DmPostRiskReportDao reportDao;
    private DmRiskViolationDao violationDao;
    private DmLimitUsageStatDao limitDao;
    private PostRiskReportService service;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        engine.executeScript(M3RiskReportDdl.DDL);
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB_A, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, SUB_B, "SA-B", "商品子账户B");
        M2E2eSeed.product(engine, PRODUCT_ID, "CU", "沪铜");

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        reportDao = new DmPostRiskReportDao(tx);
        violationDao = new DmRiskViolationDao(tx);
        limitDao = new DmLimitUsageStatDao(tx);
        service = new PostRiskReportServiceImpl(reportDao, violationDao, limitDao,
                new DmTradePreTradeRejectDao(tx), new DmProductQuotaDao(tx), new DmSettleCycleDao(tx),
                tx, Config.empty());
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------------ TC-M3-28 / TC-M3-29

    @Test
    @DisplayName("TC-M3-28/29 事后风控：报表落库 + 与结算勾稽 == 0；六类违规汇总 + 80%/95% 计数正确")
    void generateReport() throws Exception {
        // 当日数据：层①额度 82%（提示）与 96%（警告）
        M3RiskReportDdl.productQuota(engine, SUB_A, PRODUCT_ID, "100.0000", "82.0000", "0", DAY);
        M3RiskReportDdl.productQuota(engine, SUB_B, PRODUCT_ID, "100.0000", "96.0000", "0", DAY);
        // 事前拒绝记录（四类：超品种/超规模/超期限/超授权）
        M3RiskReportDdl.preTradeReject(engine, SUB_A, PRODUCT_ID, "REF-1", "R-RISK-1", "0x1001", "1", "0", DAY);
        M3RiskReportDdl.preTradeReject(engine, SUB_A, PRODUCT_ID, "REF-2", "R-RISK-4", "0x1007", "95", "90", DAY);
        M3RiskReportDdl.preTradeReject(engine, SUB_A, PRODUCT_ID, "REF-3", "R-RISK-6", "0x1008", "400", "365", DAY);
        M3RiskReportDdl.preTradeReject(engine, SUB_A, PRODUCT_ID, "REF-4", "R-RISK-7", "0x100B", "0", "1", DAY);
        // 他源违规（审计筛查-文档缺失）
        M3RiskReportDdl.existingViolation(engine, SUB_A, DAY,
                PostRiskReportService.V_DOC_MISSING, PostRiskReportService.SEV_MEDIUM, "R-PLAN-9",
                "会计文档缺失", "POST_AUDIT_SCREEN");
        // 当日结算单（勾稽对象）：浮动盈亏合计 = 100 + 50 = 150
        M3RiskReportDdl.settleCycle(engine, 1001L, SUB_A, "ST20260920A", DAY, "100.0000", "1000.0000");
        M3RiskReportDdl.settleCycle(engine, 1002L, SUB_B, "ST20260920B", DAY, "50.0000", "500.0000");

        // 日终镜像指标（与结算浮动盈亏一致 ⇒ 差异 0）
        MirrorMetrics metrics = metrics("100.0000", "50.0000");
        // 事中告警：异常下单行为 ⇒ 频繁开平仓（第六类）
        RiskAlert alert = new RiskAlert(SUB_B, RiskMetricType.ABNORMAL_ORDER, RiskAlertLevel.WARNING,
                "8", "6", DAY.atStartOfDay().toString(), "日内开平合计 8 超告警线 6");
        RiskEvaluation eval = new RiskEvaluation(metrics, Collections.singletonList(alert),
                Collections.emptyList(), Collections.emptySet(), 0L);

        List<String> alarms = new ArrayList<>();
        PostRiskReportJob job = new PostRiskReportJob(service, () -> eval,
                (message, cause) -> alarms.add(message));
        PostRiskReportResult result = job.run(DAY, SYS);

        // TC-M3-28：当日报表落库（三类各一行）+ 与结算差异 0
        assertEquals(3, result.reportRows, "当日应落库三类报表");
        assertEquals(1, reportDao.count("report_date = ? AND report_type = ?",
                Arrays.asList(DAY, PostRiskReportService.REPORT_TYPE_RISK_METRIC)),
                "TC-M3-28 唯一预期值：风险指标报表当日落库行数 == 1");
        assertNotNull(reportDao.findByKey(DAY, PostRiskReportService.REPORT_TYPE_RISK_METRIC, null),
                "风险指标报表应可按幂等键定位");
        assertNotNull(reportDao.findByKey(DAY, PostRiskReportService.REPORT_TYPE_VIOLATION_SUMMARY, null));
        assertNotNull(reportDao.findByKey(DAY, PostRiskReportService.REPORT_TYPE_LIMIT_USAGE, null));
        assertEquals(0, result.reconcileDiff.compareTo(BigDecimal.ZERO),
                "TC-M3-28 唯一预期值：与 SETTLE 结算数据差异 == 0");
        assertTrue(alarms.isEmpty(), "成功生成不应告警");

        // TC-M3-29：六类违规全覆盖、各 1 条
        Map<String, Integer> expectedTypes = new LinkedHashMap<>();
        for (String type : Arrays.asList(PostRiskReportService.V_OVER_PRODUCT,
                PostRiskReportService.V_OVER_SCALE, PostRiskReportService.V_OVER_TERM,
                PostRiskReportService.V_OVER_AUTH, PostRiskReportService.V_FREQUENT_TRADE,
                PostRiskReportService.V_DOC_MISSING)) {
            expectedTypes.put(type, 1);
        }
        assertEquals(expectedTypes, result.violationByType,
                "TC-M3-29 唯一预期值：违规类型覆盖 {超品种,超规模,超期限,超授权,频繁开平仓,文档缺失}");
        assertEquals(6, result.violationTotal, "当日违规事项共 6 条");
        assertEquals(6, violationDao.findByDate(DAY).size(), "违规事项须真实落库");

        // TC-M3-29：限额使用率 82% ⇒ 提示、96% ⇒ 警告
        assertEquals(1, result.hintCount, ">=80% 提示计数 == 1");
        assertEquals(1, result.warningCount, ">=95% 警告计数 == 1");
        assertEquals(0, result.exceededCount);
        assertEquals(2, result.limitUsageRows);
        List<LimitUsageStat> usages = limitDao.findByDate(DAY);
        assertEquals(2, usages.size(), "限额使用须真实落库");
        String levelA = hintOf(usages, SUB_A);
        String levelB = hintOf(usages, SUB_B);
        assertEquals(PostRiskReportService.HINT_HINT, levelA, "82% 应标 HINT");
        assertEquals(PostRiskReportService.HINT_WARNING, levelB, "96% 应标 WARNING");
    }

    @Test
    @DisplayName("TC-M3-28 负控：镜像浮动盈亏与结算不一致时差异非 0（勾稽确在比对）")
    void reconcileDetectsMismatch() throws Exception {
        M3RiskReportDdl.settleCycle(engine, 1001L, SUB_A, "ST20260920A", DAY, "100.0000", "1000.0000");
        M3RiskReportDdl.settleCycle(engine, 1002L, SUB_B, "ST20260920B", DAY, "50.0000", "500.0000");
        // 镜像合计 140 ≠ 结算合计 150 ⇒ 差异 10（若勾稽是空转，恒 0 则本用例必红）
        PostRiskReportResult result = service.generate(
                new com.hedgeguard.service.PostRiskReportRequest(DAY, metrics("100.0000", "40.0000"),
                        Collections.emptyList(), SYS));
        assertEquals(0, result.reconcileDiff.compareTo(new BigDecimal("10")),
                "负控：与结算差异应 == 10（证明勾稽确在比对）");
    }

    @Test
    @DisplayName("TC-M3-28 异常：当日无结算单 ⇒ 拒绝生成并告警（FR-RISK-17 验收 4）")
    void generateWithoutSettleAlarms() {
        List<String> alarms = new ArrayList<>();
        PostRiskReportJob job = new PostRiskReportJob(service,
                () -> new RiskEvaluation(metrics("0", "0"), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptySet(), 0L),
                (message, cause) -> alarms.add(message));

        assertThrows(BizException.class, () -> job.run(DAY.plusDays(30), SYS),
                "当日无结算单应拒绝生成");
        assertEquals(1, alarms.size(), "生成失败须告警");
        assertEquals(0, reportDao.count("report_date = ?",
                Collections.singletonList((Object) DAY.plusDays(30))), "失败不得落库报表");
    }

    // ------------------------------------------------------------------ T-180 退修回归（TE-T180-001 + A/B/C/O-1/O-2）

    @Test
    @DisplayName("TE-T180-001 回归：同子账户同天同规则 2 条拒绝 ⇒ 违规行数 == 2；job 重跑 ⇒ 仍 == 2（O-2 幂等，三表不翻番）")
    void violationCountsPreservedAndIdempotentOnRerun() throws Exception {
        // 同子账户 + 同交易日 + 同规则 R-RISK-1 两条事前拒绝：自然键相同，仅「发生次数」不同
        M3RiskReportDdl.preTradeReject(engine, SUB_A, PRODUCT_ID, "REF-A1", "R-RISK-1", "0x1001", "1", "0", DAY);
        M3RiskReportDdl.preTradeReject(engine, SUB_A, PRODUCT_ID, "REF-A2", "R-RISK-1", "0x1001", "1", "0", DAY);
        M3RiskReportDdl.productQuota(engine, SUB_A, PRODUCT_ID, "100.0000", "50.0000", "0", DAY);
        M3RiskReportDdl.settleCycle(engine, 1001L, SUB_A, "ST20260920A", DAY, "10.0000", "1000.0000");

        RiskEvaluation eval = new RiskEvaluation(metrics("10.0000", "0"), Collections.emptyList(),
                Collections.emptyList(), Collections.emptySet(), 0L);
        List<String> alarms = new ArrayList<>();
        PostRiskReportJob job = new PostRiskReportJob(service, () -> eval, (m, c) -> alarms.add(m));

        // 首次：2 条发生 ⇒ 2 行（原实现按自然键去重只落 1 行 ⇒ 本断言必红）
        PostRiskReportResult first = job.run(DAY, SYS);
        assertEquals(2, first.violationTotal, "2 条同规则拒绝应派生 2 条违规（FR-RISK-18 验收1「次数」）");
        assertEquals(2, first.violationByType.get(PostRiskReportService.V_OVER_PRODUCT),
                "超品种次数 == 2");
        assertEquals(2, violationDao.findByDate(DAY).size(), "违规事项须真实落 2 行");
        assertEquals(2, violationDao.countByNaturalKey(SUB_A, DAY, PostRiskReportService.V_OVER_PRODUCT,
                "R-RISK-1", PostRiskReportService.SRC_PRE_TRADE_REJECT), "落库行数 == 发生次数");
        assertTrue(alarms.isEmpty(), "成功生成不应告警");

        // 重跑：幂等不翻倍；三表行数不翻番、勾稽差异不变（O-2 机器守卫）
        PostRiskReportResult second = job.run(DAY, SYS);
        assertEquals(2, second.violationTotal, "重跑后违规总数仍为 2（幂等不翻倍）");
        assertEquals(2, violationDao.findByDate(DAY).size(), "重跑后违规行数仍为 2");
        assertEquals(3, reportDao.count("report_date = ?", Collections.singletonList((Object) DAY)),
                "三张报表各一行（重跑不新增）");
        assertEquals(1, limitDao.findByDate(DAY).size(), "限额使用行重跑不翻倍");
        assertEquals(0, second.reconcileDiff.compareTo(first.reconcileDiff), "重跑勾稽差异不变");
        assertEquals(0, second.reconcileDiff.compareTo(BigDecimal.ZERO), "本例镜像与结算一致 ⇒ 差异 0");
    }

    @Test
    @DisplayName("O-1 回归：占用率分子 = used + frozen（ARCH §3.3 R-RISK-2），frozen>0 计入")
    void usageRatioIncludesFrozenQuantity() throws Exception {
        // used 70 + frozen 12 = 82 / 100 ⇒ 82% HINT（若漏 frozen 则 70% ⇒ NORMAL，本用例必红）
        M3RiskReportDdl.productQuota(engine, SUB_A, PRODUCT_ID, "100.0000", "70.0000", "12.0000", DAY);
        M3RiskReportDdl.settleCycle(engine, 1001L, SUB_A, "ST20260920A", DAY, "0", "1000.0000");

        service.generate(new com.hedgeguard.service.PostRiskReportRequest(
                DAY, metrics("0", "0"), Collections.emptyList(), SYS));

        List<LimitUsageStat> usages = limitDao.findByDate(DAY);
        assertEquals(1, usages.size());
        LimitUsageStat u = usages.get(0);
        assertEquals(0, u.usedValue.compareTo(new BigDecimal("82.0000")),
                "used_value 应含 frozen（70+12）");
        assertEquals(0, u.usageRatio.compareTo(new BigDecimal("0.820000")),
                "使用率 = (used+frozen)/quota_limit = 0.82");
        assertEquals(PostRiskReportService.HINT_HINT, u.hintLevel, "82% 应标 HINT（漏 frozen 会退为 NORMAL）");
    }

    @Test
    @DisplayName("B 回归：settle_reconcile_key = trade_date + 结算单号（多单逗号连接，DATA_MODEL §2.8）")
    void reconcileKeyCarriesSettleNos() throws Exception {
        M3RiskReportDdl.settleCycle(engine, 1001L, SUB_A, "ST20260920A", DAY, "10.0000", "1000.0000");
        M3RiskReportDdl.settleCycle(engine, 1002L, SUB_B, "ST20260920B", DAY, "5.0000", "500.0000");

        PostRiskReportResult result = service.generate(new com.hedgeguard.service.PostRiskReportRequest(
                DAY, metrics("10.0000", "5.0000"), Collections.emptyList(), SYS));

        assertEquals(DAY + "#ST20260920A,ST20260920B", result.settleReconcileKey,
                "勾稽键应为 交易日 + 结算单号（多单逗号连接，id 升序）");
        assertEquals(result.settleReconcileKey,
                reportDao.findByKey(DAY, PostRiskReportService.REPORT_TYPE_RISK_METRIC, null).settleReconcileKey,
                "落库报表的勾稽键应与结果一致");
    }

    @Test
    @DisplayName("A 回归：频繁开平仓派生违规 rule_id = FR-RISK-14（非空间甲 R-RISK-14=阈值集中配置）")
    void frequentTradeRuleIdIsFrRisk14() throws Exception {
        M3RiskReportDdl.settleCycle(engine, 1001L, SUB_A, "ST20260920A", DAY, "0", "1000.0000");
        RiskAlert alert = new RiskAlert(SUB_A, RiskMetricType.ABNORMAL_ORDER, RiskAlertLevel.WARNING,
                "8", "6", DAY.atStartOfDay().toString(), "日内开平合计 8 超告警线 6");

        service.generate(new com.hedgeguard.service.PostRiskReportRequest(DAY, metrics("0", "0"),
                Collections.singletonList(alert), SYS));

        List<com.hedgeguard.dao.entity.RiskViolation> violations = violationDao.findByDate(DAY);
        assertEquals(1, violations.size());
        assertEquals("FR-RISK-14", violations.get(0).ruleId,
                "频繁开平仓 rule_id 应为 FR-RISK-14（异常下单监控）；"
                        + "R-RISK-14 在空间甲＝阈值集中配置，不得误用");
    }

    // ------------------------------------------------------------------ 夹具

    private static MirrorMetrics metrics(String floatA, String floatB) {
        SubAccountRiskMetrics a = new SubAccountRiskMetrics(SUB_A);
        a.floatPnl = new BigDecimal(floatA);
        SubAccountRiskMetrics b = new SubAccountRiskMetrics(SUB_B);
        b.floatPnl = new BigDecimal(floatB);
        SubAccountRiskMetrics agg = new SubAccountRiskMetrics(0);
        agg.floatPnl = a.floatPnl.add(b.floatPnl);
        return new MirrorMetrics(ReplayProgress.State.READY, 1L, 1L, null, -1L, false,
                Arrays.asList(a, b), agg);
    }

    private static String hintOf(List<LimitUsageStat> usages, int subAccountId) {
        for (LimitUsageStat u : usages) {
            if (u.subAccountId != null && u.subAccountId == subAccountId) {
                return u.hintLevel;
            }
        }
        return null;
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("docs/DEPLOY/schema"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（含 docs/DEPLOY/schema）");
    }
}
