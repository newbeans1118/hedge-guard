package com.hedgeguard.service;

import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.bridge.ParamChangeEncoder;
import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.impl.DmConfigChangeLogDao;
import com.hedgeguard.dao.impl.DmParamDispatchDao;
import com.hedgeguard.dao.impl.DmRiskThresholdDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.impl.ParamDispatchServiceImpl;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import hgcp.v1.Hgcp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
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
 * {@code T-063}：风控阈值配置与下发闭环（合规下限校验）——真实 {@code Dm*Dao} + 内存假 JDBC
 * 引擎 + 进程内桥（{@code NFR-TEST-2}；**禁假 DAO**，{@code T-136}/{@code T-058} 判例）。
 *
 * <p>覆盖：① 16 项阈值清单与 §9.1 默认值；② 收紧变更放行 + 下发 + 留痕 + 回执；
 * ③ 放宽无复核 → {@code 10012}（阻断四项）；④ 放宽有复核 → 放行且留痕复核人；
 * ⑤ 越界 → {@code 10001}；⑥ 回执失败 → 未生效 + {@code 10014}；
 * ⑦ 连续下发后发覆盖前发（{@code param_version} 单调）；⑧ 线格式编码器（16 字段嵌套）。
 */
class T063RiskThresholdTest {

    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql",
            "docs/DEPLOY/schema/m2_trade.sql",
            "docs/DEPLOY/schema/m2_settle.sql",
            "docs/DEPLOY/schema/m2_fund.sql",
            "docs/DEPLOY/schema/m3_risk.sql");

    private static final long OPERATOR = 201L;
    private static final long REVIEWER = 999L;
    private static final long OTHER_OPERATOR = 202L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private InMemoryDmEngine engine;
    private DmRiskThresholdDao thresholdDao;
    private DmParamDispatchDao dispatchDao;
    private InMemoryTradingBridge bridge;
    private RiskService riskService;

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

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        thresholdDao = new DmRiskThresholdDao(tx);
        dispatchDao = new DmParamDispatchDao(tx);
        bridge = new InMemoryTradingBridge();
        ParamDispatchServiceImpl dispatch =
                new ParamDispatchServiceImpl(tx, dispatchDao, bridge);
        riskService = new RiskService(tx, thresholdDao, dispatch, new DmConfigChangeLogDao(tx));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("清单：16 项阈值可见，默认值 = INDEX §9.1 口径，且标记合规红线")
    void listReturnsAllRulesWithComplianceDefaults() {
        List<RiskService.ThresholdView> views = riskService.listThresholds();
        assertEquals(RiskThresholdSpec.values().length, views.size(), "应列出全部已登记阈值");
        assertEquals(16, views.size(), "CMD_UPDATE_RISK_THRESHOLD 线格式恰 16 字段");

        assertEquals("0.05", byKey(views, "risk.price.block_pct").thresholdValue);
        assertEquals("0.02", byKey(views, "risk.price.warn_pct").thresholdValue);
        assertEquals("0.90", byKey(views, "hedge.scale.ratio_commodity").thresholdValue);
        assertEquals("0.80", byKey(views, "hedge.scale.ratio_trade").thresholdValue);
        assertEquals("12", byKey(views, "risk.term.max_months").thresholdValue);
        assertEquals("6", byKey(views, "risk.freq.intraday_warn").thresholdValue);
        assertEquals("Y", byKey(views, "risk.price.block_pct").isComplianceRedline);
        // 尚未落库 ⇒ 无下发状态
        assertNull(byKey(views, "risk.price.block_pct").dispatchStatus);
    }

    @Test
    @DisplayName("收紧变更：无复核放行 → 下发 CMD_UPDATE_RISK_THRESHOLD → 留痕 + 回执")
    void stricterChangeDispatchedAndTraced() {
        RiskService.ThresholdView v = riskService.updateThreshold(
                "risk.term.max_months", "6", OPERATOR, null, "收紧期限红线");
        assertEquals("6", v.thresholdValue);
        assertEquals("SUCCESS", v.dispatchStatus);
        assertNotNull(v.dispatchVersion);

        // 下发留痕
        assertEquals(1, bridge.dispatchedChanges().size());
        ParamChange sent = bridge.dispatchedChanges().get(0);
        assertEquals(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, sent.type);
        assertEquals(16, sent.payload.size(), "须下发全量 16 字段");
        assertEquals("6", sent.payload.get("term_max_months"));

        // t_param_dispatch 落库（去前缀域）
        List<Map<String, Object>> rows = engine.table("t_param_dispatch").rows;
        assertEquals(1, rows.size());
        assertEquals("UPDATE_RISK_THRESHOLD", rows.get(0).get("dispatch_type"));
        assertEquals("SUCCESS", rows.get(0).get("dispatch_status"));

        // 双人复核留痕表（收紧无复核 ⇒ reviewer_id 为空）
        List<Map<String, Object>> logs = engine.table("t_config_change_log").rows;
        assertEquals(1, logs.size());
        assertEquals("risk.term.max_months", logs.get(0).get("config_key"));
        assertEquals("12", logs.get(0).get("before_value"));
        assertEquals("6", logs.get(0).get("after_value"));
        assertNull(logs.get(0).get("reviewer_id"));
    }

    @Test
    @DisplayName("放宽合规红线无复核 → 10012（阻断四项），不落库、不下发")
    void relaxedChangeWithoutReviewerRejected() {
        BizException ex = assertThrows(BizException.class, () ->
                riskService.updateThreshold("hedge.scale.ratio_commodity", "0.95",
                        OPERATOR, null, "放宽规模红线"));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, ex.errorCode());
        assertEquals("R-CONFIG-8", ex.ruleId());
        assertNotNull(ex.current());
        assertNotNull(ex.threshold());
        assertNotNull(ex.nextStep());
        assertTrue(bridge.dispatchedChanges().isEmpty(), "被拒不得下发");
        assertEquals(0, engine.table("t_param_dispatch").rows.size());
        assertNull(thresholdDao.findByKey("hedge.scale.ratio_commodity", "GLOBAL", 0),
                "被拒不得落库");
    }

    @Test
    @DisplayName("放宽合规红线 + 复核人 = 操作人本人 → 仍拒（不得自审）")
    void relaxedChangeReviewerEqualsOperatorRejected() {
        BizException ex = assertThrows(BizException.class, () ->
                riskService.updateThreshold("hedge.scale.ratio_commodity", "0.95",
                        OPERATOR, OPERATOR, "自审"));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, ex.errorCode());
    }

    @Test
    @DisplayName("放宽合规红线 + 独立复核人 → 放行，留痕含复核人与合规风险提示")
    void relaxedChangeWithReviewerAllowedAndTraced() {
        RiskService.ThresholdView v = riskService.updateThreshold(
                "hedge.scale.ratio_commodity", "0.95", OPERATOR, REVIEWER, "客户确需调整");
        assertEquals("0.95", v.thresholdValue);
        assertEquals("SUCCESS", v.dispatchStatus);

        List<Map<String, Object>> logs = engine.table("t_config_change_log").rows;
        assertEquals(1, logs.size());
        assertEquals(Long.valueOf(REVIEWER), (Long) logs.get(0).get("reviewer_id"));
        assertTrue(String.valueOf(logs.get(0).get("change_reason")).contains("R-CONFIG-8"));
    }

    @Test
    @DisplayName("越界取值 → 10001（比例 150% 被拒，FR-CONFIG-5 验收 3）")
    void outOfRangeRejected() {
        BizException ex = assertThrows(BizException.class, () ->
                riskService.updateThreshold("hedge.scale.ratio_commodity", "1.50",
                        OPERATOR, REVIEWER, "越界"));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode());
        assertTrue(bridge.dispatchedChanges().isEmpty());
    }

    @Test
    @DisplayName("回执失败：状态 = 未生效（FAIL）+ 抛 10014（R-CONFIG-3）")
    void dispatchNotAcknowledgedMarksFailed() {
        bridge.setDispatchAcknowledged(false);
        BizException ex = assertThrows(BizException.class, () ->
                riskService.updateThreshold("risk.term.max_months", "6", OPERATOR, null, "收紧"));
        assertEquals(ErrorCode.DISPATCH_NOT_EFFECTIVE, ex.errorCode());
        assertEquals("R-CONFIG-3", ex.ruleId());

        Map<String, Object> row = engine.table("t_risk_threshold").rows.get(0);
        assertEquals("FAIL", row.get("dispatch_status"), "未生效须如实标记");
        assertEquals("FAIL", engine.table("t_param_dispatch").rows.get(0).get("dispatch_status"));
    }

    @Test
    @DisplayName("连续下发：param_version 单调递增，后发覆盖前发（顺序保证）")
    void consecutiveDispatchLastWins() {
        riskService.updateThreshold("risk.term.max_months", "11", OPERATOR, null, "第一次");
        riskService.updateThreshold("risk.term.max_months", "10", OPERATOR, null, "第二次");
        List<ParamChange> sent = bridge.dispatchedChanges();
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).paramVersion > sent.get(0).paramVersion, "版本须单调递增");
        assertEquals("10", sent.get(1).payload.get("term_max_months"), "后发覆盖前发");
        assertEquals("10", thresholdDao.findByKey("risk.term.max_months", "GLOBAL", 0).thresholdValue);
    }

    @Test
    @DisplayName("线格式编码器：16 字段组装为 CmdUpdateRiskThreshold 嵌套 RiskThresholds")
    void encoderBuildsNestedThresholds() throws Exception {
        ParamChange change =
                new ParamChange(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, 42L, OPERATOR);
        change.with("scale_ratio_commodity", "0.90").with("scale_ratio_trade", "0.80")
                .with("scale_base", "EXPOSURE_QTY").with("scale_scope", "BOTH")
                .with("term_max_months", "12").with("term_grace_days", "30")
                .with("term_action", "WARN_THEN_BLOCK").with("price_base", "PREV_SETTLEMENT")
                .with("price_warn_pct", "0.02").with("price_block_pct", "0.05")
                .with("position_check_mode", "PRE_TRADE_AND_EOD")
                .with("freq_intraday_warn", "6").with("freq_intraday_block", "10")
                .with("freq_7d_rounds_warn", "5").with("freq_min_hold_days", "3")
                .with("margin_ratio_threshold", "0.70");
        Hgcp.CmdUpdateRiskThreshold msg =
                Hgcp.CmdUpdateRiskThreshold.parseFrom(ParamChangeEncoder.encode(change));
        assertEquals(42L, msg.getParamVersion());
        assertEquals("0.90", msg.getThresholds().getScaleRatioCommodity());
        assertEquals(Hgcp.ScaleBase.SCALE_BASE_EXPOSURE_QTY, msg.getThresholds().getScaleBase());
        assertEquals(Hgcp.ScaleScope.SCALE_SCOPE_BOTH, msg.getThresholds().getScaleScope());
        assertEquals(Hgcp.TermAction.TERM_ACTION_WARN_THEN_BLOCK, msg.getThresholds().getTermAction());
        assertEquals(12, msg.getThresholds().getTermMaxMonths());
        assertEquals(5, msg.getThresholds().getFreq7DRoundsWarn());
        assertEquals("0.70", msg.getThresholds().getMarginRatioThreshold());
    }

    @Test
    @DisplayName("线格式编码器：缺键 / 协议外键一律拒绝（HGC-1.6，不得静默丢弃）")
    void encoderRejectsMissingAndUnknownKeys() {
        ParamChange missing =
                new ParamChange(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, 1L, OPERATOR);
        missing.with("scale_ratio_commodity", "0.90");
        assertThrows(IllegalArgumentException.class, () -> ParamChangeEncoder.encode(missing));

        ParamChange extra =
                new ParamChange(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, 1L, OPERATOR);
        for (RiskThresholdSpec spec : RiskThresholdSpec.values()) {
            extra.with(spec.wireField(), spec.defaultValue());
        }
        extra.with("sneaky_key", "1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ParamChangeEncoder.encode(extra));
        assertTrue(ex.getMessage().contains("sneaky_key"), "须报出违规键名");
    }

    @Test
    @DisplayName("下发服务未装配 → 写面拒绝 10002（不得静默不生效）")
    void dispatchServiceMissingRejectsWrite() {
        RiskService bare = new RiskService(
                new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2))),
                thresholdDao, null, new DmConfigChangeLogDao(
                        new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)))));
        BizException ex = assertThrows(BizException.class, () ->
                bare.updateThreshold("risk.term.max_months", "6", OTHER_OPERATOR, null, "x"));
        assertEquals(ErrorCode.COMPONENT_UNAVAILABLE, ex.errorCode());
        assertFalse(thresholdDao.findByKey("risk.term.max_months", "GLOBAL", 0) != null);
    }

    private static RiskService.ThresholdView byKey(List<RiskService.ThresholdView> views, String key) {
        for (RiskService.ThresholdView v : views) {
            if (v.thresholdKey.equals(key)) {
                return v;
            }
        }
        throw new AssertionError("未找到阈值视图：" + key);
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("docs/DEPLOY/schema/m3_risk.sql"))) {
            p = p.getParent();
        }
        assertNotNull(p, "未找到仓库根（docs/DEPLOY/schema/m3_risk.sql）");
        return p;
    }
}
