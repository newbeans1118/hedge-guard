package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.TradeOrder;
import com.hedgeguard.dao.entity.TradePreTradeReject;
import com.hedgeguard.dao.entity.TradeReason;
import com.hedgeguard.dao.entity.ViolationScreenItem;
import com.hedgeguard.dao.entity.ViolationScreenRun;
import com.hedgeguard.dao.impl.DmTradeOrderDao;
import com.hedgeguard.dao.impl.DmTradePreTradeRejectDao;
import com.hedgeguard.dao.impl.DmTradeReasonDao;
import com.hedgeguard.dao.impl.DmViolationScreenItemDao;
import com.hedgeguard.dao.impl.DmViolationScreenRunDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.ViolationScreenService.ViolationScreenQuery;
import com.hedgeguard.service.ViolationScreenService.ViolationScreenRequest;
import com.hedgeguard.service.ViolationScreenService.ViolationScreenResult;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-068} 违规事项筛查「真实 DAO + 内存假 JDBC 引擎」用例（禁假 DAO）。
 *
 * <p>把生产建表脚本真喂 {@link InMemoryDmEngine}，再用<b>真实</b> {@code Dm*Dao} 播种与读取，
 * 覆盖：超品种/超规模…四维（事前拒绝记录）、频繁开平仓（指令计数）、
 * {@code REASON_MISSING}/{@code RELATED_MISSING} 并列独立（Q-13/CH-004）、无命中结论、
 * 运行可复现、查询筛选与子账户软隔离。
 */
class ViolationScreenServiceTest {

    private static final int SUB = 11;
    private static final int OTHER_SUB = 12;
    private static final long PRODUCT = 7001L;
    private static final long EXP = 2001L;
    private static final long PLAN = 1001L;
    private static final long TRADE_PLAN = 3001L;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate DAY2 = DAY.plusDays(1);
    private static final LocalDateTime TS = DAY.atTime(10, 0);

    private InMemoryDmEngine engine;
    private DmTradeOrderDao orderDao;
    private DmTradeReasonDao reasonDao;
    private DmTradePreTradeRejectDao rejectDao;
    private DmViolationScreenRunDao runDao;
    private DmViolationScreenItemDao itemDao;

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
        M2E2eSeed.subAccount(engine, OTHER_SUB, "SA-B", "商品子账户B");
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        M2E2eSeed.exposure(engine, EXP, SUB, PRODUCT, "100", DAY);
        M2E2eSeed.hedgePlan(engine, PLAN, SUB, PRODUCT, "HP202610060001", "100", DAY, DAY.plusMonths(3));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN, SUB, PLAN, EXP, PRODUCT, "TP-1", "cu2612", "10",
                DAY.plusDays(30));

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        orderDao = new DmTradeOrderDao(tx);
        reasonDao = new DmTradeReasonDao(tx);
        rejectDao = new DmTradePreTradeRejectDao(tx);
        runDao = new DmViolationScreenRunDao(tx);
        itemDao = new DmViolationScreenItemDao(tx);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ 规则维度

    @Test
    @DisplayName("超规模：事前拒绝 R-RISK-2 → 命中 OVER_SCALE/HIGH，对象为对应指令")
    void overScaleHitsOrder() {
        TradeOrder o = order("HP-C1", "OPEN", TS);
        reject("HP-C1", "R-RISK-2", "0x1002", "110", "100", DAY);
        ViolationScreenResult r = screen(dims("OVER_SCALE"), DAY, DAY, "VS-OVER");
        assertEquals(1, r.items.size(), "应命中 1 条超规模");
        ViolationScreenItem it = r.items.get(0);
        assertEquals("OVER_SCALE", it.violationType);
        assertEquals("HIGH", it.severity);
        assertEquals("R-RISK-2", it.ruleId);
        assertEquals("TRADE_ORDER", it.objectType);
        assertEquals(o.id, it.objectId, "应指向该笔指令");
        assertTrue(it.violationDesc.contains("current=110"), it.violationDesc);
        assertEquals(1, r.run.hitCount);
        assertEquals("VS-OVER", r.run.screenNo);
        assertEquals(OPERATOR, r.run.operatorId);
        assertEquals(DAY, r.run.dateFrom);
        assertEquals(DAY, r.run.dateTo);
        assertEquals("OVER_SCALE", r.run.screenDimensions);
        assertNotNull(runDao.findByScreenNo("VS-OVER"), "运行记录应落库");
        assertEquals(1, itemDao.findByScreenRunAndType(r.run.id, "OVER_SCALE").size(),
                "命中项应落库并可按批次+类型查回");
    }

    @Test
    @DisplayName("超品种：R-RISK-1 → OVER_PRODUCT/LOW")
    void overProductLowSeverity() {
        order("HP-C2", "OPEN", TS);
        reject("HP-C2", "R-RISK-1", "0x1001", "-", "-", DAY);
        ViolationScreenResult r = screen(dims("OVER_PRODUCT"), DAY, DAY, null);
        assertEquals(1, r.items.size());
        assertEquals("OVER_PRODUCT", r.items.get(0).violationType);
        assertEquals("LOW", r.items.get(0).severity);
    }

    @Test
    @DisplayName("按时间/规则筛选：区间外与不属六类规则（R-RISK-8）均不命中")
    void rangeAndRuleFilter() {
        order("HP-C3", "OPEN", TS);
        reject("HP-C3", "R-RISK-2", "0x1002", "110", "100", DAY);          // 区间外
        reject("HP-C3b", "R-RISK-2", "0x1002", "120", "100", DAY2);        // 区间内
        reject("HP-C3c", "R-RISK-8", "0x1008", "0.061", "0.05", DAY2);     // 不属六类
        ViolationScreenResult r = screen(dims("OVER_SCALE"), DAY2, DAY2, null);
        assertEquals(1, r.items.size(), "仅区间内且属六类的 1 条");
        assertEquals("OVER_SCALE", r.items.get(0).violationType);
    }

    @Test
    @DisplayName("频繁开平仓：同日同品种开平合计 6 次 → 命中（MEDIUM），10 次 → HIGH")
    void frequentTradeByCount() {
        for (int i = 0; i < 6; i++) {
            order("FREQ-A-" + i, i % 2 == 0 ? "OPEN" : "CLOSE", DAY.atTime(9, i));
        }
        ViolationScreenResult warn = screen(dims("FREQUENT_TRADE"), DAY, DAY, null);
        assertEquals(1, warn.items.size(), "一组应聚为 1 条告警项");
        assertEquals("FREQUENT_TRADE", warn.items.get(0).violationType);
        assertEquals("MEDIUM", warn.items.get(0).severity, "6 次 < 限制阈值 10");
        assertTrue(warn.items.get(0).violationDesc.contains("6 次"), warn.items.get(0).violationDesc);

        for (int i = 0; i < 4; i++) {
            order("FREQ-B-" + i, "OPEN", DAY.atTime(10, i));
        }
        ViolationScreenResult limit = screen(dims("FREQUENT_TRADE"), DAY, DAY, null);
        assertEquals(1, limit.items.size());
        assertEquals("HIGH", limit.items.get(0).severity, "10 次 >= 限制阈值");
    }

    // ------------------------------------------------------------ 理由/关联并列独立

    @Test
    @DisplayName("Q-13：理由缺失与关联缺失并列独立、互不串味")
    void reasonMissingAndRelatedMissingIndependent() {
        TradeOrder oA = order("HP-RA", "OPEN", TS);
        TradeOrder oB = order("HP-RB", "OPEN", TS);
        // A：理由内容为空 → REASON_MISSING（关联对象齐备）
        reason(oA.id, null, PLAN, EXP, "N", "N");
        // B：理由已填（文本非空）但关联对象未提供 → RELATED_MISSING
        reason(oB.id, "对冲建仓", null, null, "Y", "N");

        ViolationScreenResult r = screen(
                dims("REASON_MISSING", "RELATED_MISSING"), DAY, DAY, null);
        List<ViolationScreenItem> reasonHits = byType(r, "REASON_MISSING");
        List<ViolationScreenItem> relatedHits = byType(r, "RELATED_MISSING");
        assertEquals(1, reasonHits.size(), "仅 A 计为理由缺失");
        assertEquals(oA.id, reasonHits.get(0).objectId);
        assertEquals(1, relatedHits.size(), "仅 B 计为关联缺失");
        assertEquals(oB.id, relatedHits.get(0).objectId);
        assertTrue(relatedHits.get(0).relatedObjectInfo.contains("计划ID"),
                relatedHits.get(0).relatedObjectInfo);
        assertFalse(reasonHits.stream().anyMatch(i -> i.objectId == oB.id),
                "B 不得被计为理由缺失");
        assertFalse(relatedHits.stream().anyMatch(i -> i.objectId == oA.id),
                "A 不得被计为关联缺失");
    }

    @Test
    @DisplayName("Q-13：无 t_trade_reason 记录 → REASON_MISSING（无理由记录）")
    void orderWithoutReasonRow() {
        TradeOrder o = order("HP-NR", "OPEN", TS);
        ViolationScreenResult r = screen(dims("REASON_MISSING"), DAY, DAY, null);
        assertEquals(1, r.items.size());
        assertEquals("REASON_MISSING", r.items.get(0).violationType);
        assertEquals(o.id, r.items.get(0).objectId);
        assertNull(r.items.get(0).tradeReasonId, "无理由记录时理由外键为空");
    }

    @Test
    @DisplayName("有理由且关联齐备 → 两维均不命中")
    void completeReasonNoHit() {
        TradeOrder o = order("HP-OK", "OPEN", TS);
        reason(o.id, "正常对冲", PLAN, EXP, "N", "N");
        ViolationScreenResult r = screen(
                dims("REASON_MISSING", "RELATED_MISSING"), DAY, DAY, null);
        assertEquals(0, r.items.size());
        assertEquals(ViolationScreenService.CONCLUSION_NO_VIOLATION, r.run.conclusion);
    }

    // ------------------------------------------------------------ 结论/可复现/隔离

    @Test
    @DisplayName("无命中：结论「未发现违规」，运行记录可复现（条件/时间/执行人）")
    void noHitConclusion() {
        ViolationScreenResult r = screen(dims("OVER_SCALE"), DAY, DAY, null);
        assertTrue(r.items.isEmpty());
        assertEquals(ViolationScreenService.CONCLUSION_NO_VIOLATION, r.run.conclusion);
        assertEquals(0, r.run.hitCount);
        ViolationScreenRun persisted = runDao.findByScreenNo(r.run.screenNo);
        assertNotNull(persisted);
        assertEquals(ViolationScreenService.CONCLUSION_NO_VIOLATION, persisted.conclusion);
        assertEquals(OPERATOR, persisted.operatorId);
        assertEquals(DAY, persisted.dateFrom);
        assertEquals(DAY, persisted.dateTo);
        assertEquals("OVER_SCALE", persisted.screenDimensions);
    }

    @Test
    @DisplayName("维度非法 → 参数错误；区间非法 → 参数错误")
    void invalidInput() {
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> screen(dims("NOT_A_DIM"), DAY, DAY, null)).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> screen(dims("OVER_SCALE"), DAY2, DAY, null)).errorCode());
    }

    @Test
    @DisplayName("子账户软隔离：越权子账户请求被拒（10004）；有限授权结果明示边界")
    void subAccountIsolation() {
        order("HP-ISO", "OPEN", TS);
        reject("HP-ISO", "R-RISK-2", "0x1002", "110", "100", DAY);

        ViolationScreenService limited = service(Collections.singletonList(SUB));
        ViolationScreenRequest req = request(dims("OVER_SCALE"), DAY, DAY);
        req.subAccountIds = Collections.singletonList(OTHER_SUB);
        BizException e = assertThrows(BizException.class, () -> limited.screen(req, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());

        ViolationScreenResult ok = limited.screen(request(dims("OVER_SCALE"), DAY, DAY), OPERATOR);
        assertTrue(ok.permissionLimited, "未含哨兵 0 ⇒ 受软隔离");
        assertNotNull(ok.note);
        assertEquals(1, ok.items.size());
    }

    @Test
    @DisplayName("GET 查询：按违规类型/子账户筛选；跨账户只读可见全部")
    void queryItemsFilter() {
        order("HP-Q1", "OPEN", TS);
        reject("HP-Q1", "R-RISK-2", "0x1002", "110", "100", DAY);
        order("HP-Q2", "OPEN", TS);
        reject("HP-Q2", "R-RISK-1", "0x1001", "-", "-", DAY);
        ViolationScreenService cross = service(Collections.singletonList(0));
        cross.screen(request(dims("OVER_SCALE", "OVER_PRODUCT"), DAY, DAY), OPERATOR);

        ViolationScreenQuery q = new ViolationScreenQuery();
        q.violationType = "OVER_SCALE";
        PageResult<ViolationScreenItem> page = cross.queryItems(q,
                PageQuery.of(null, null, null, "id", "asc"), OPERATOR);
        assertEquals(1, page.total());
        assertEquals("OVER_SCALE", page.items().get(0).violationType);

        ViolationScreenQuery q2 = new ViolationScreenQuery();
        q2.subAccountId = OTHER_SUB;
        assertEquals(0, cross.queryItems(q2, PageQuery.of(null, null, null, "id", "asc"),
                OPERATOR).total(), "异子账户无命中");
    }

    // ------------------------------------------------------------ 夹具

    private ViolationScreenService service(List<Integer> visible) {
        SubAccountVisibility visibility = uid -> new ArrayList<>(visible);
        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        return new ViolationScreenService(rejectDao, orderDao, reasonDao, runDao, itemDao,
                visibility, tx);
    }

    private ViolationScreenResult screen(Set<String> dims, LocalDate from, LocalDate to,
                                         String screenNo) {
        ViolationScreenService svc = service(Collections.singletonList(0));
        return svc.screen(request(dims, from, to, screenNo), OPERATOR);
    }

    private static ViolationScreenRequest request(Set<String> dims, LocalDate from, LocalDate to) {
        return request(dims, from, to, null);
    }

    private static ViolationScreenRequest request(Set<String> dims, LocalDate from, LocalDate to,
                                                  String screenNo) {
        ViolationScreenRequest req = new ViolationScreenRequest();
        req.dateFrom = from;
        req.dateTo = to;
        req.dimensions = dims;
        req.screenNo = screenNo;
        return req;
    }

    private static Set<String> dims(String... d) {
        return new LinkedHashSet<>(Arrays.asList(d));
    }

    private static List<ViolationScreenItem> byType(ViolationScreenResult r, String type) {
        List<ViolationScreenItem> out = new ArrayList<>();
        for (ViolationScreenItem it : r.items) {
            if (type.equals(it.violationType)) {
                out.add(it);
            }
        }
        return out;
    }

    private TradeOrder order(String ref, String offset, LocalDateTime submit) {
        TradeOrder o = new TradeOrder();
        o.subAccountId = SUB;
        o.clientOrderRef = ref;
        o.tradePlanId = TRADE_PLAN;
        o.hedgePlanId = PLAN;
        o.exposureId = EXP;
        o.productId = PRODUCT;
        o.instrumentId = "cu2612";
        o.instrumentType = "FUTURES";
        o.direction = "BUY";
        o.offsetFlag = offset;
        o.priceType = "LIMIT";
        o.limitPrice = new BigDecimal("70000");
        o.volume = 1;
        o.operatorId = OPERATOR;
        o.tradeReasonId = null;
        o.status = "SUBMITTED";
        o.submitTime = submit;
        o.clientTsMs = submit.atZone(Clocks.ZONE).toInstant().toEpochMilli();
        assertEquals(1, orderDao.insert(o, OPERATOR));
        return o;
    }

    private void reason(long orderId, String text, Long planId, Long exposureId, String relatedFlag,
                        String missingFlag) {
        TradeReason r = new TradeReason();
        r.subAccountId = SUB;
        r.tradeOrderId = orderId;
        r.relatedType = "NEW_EXPOSURE";
        r.relatedPlanId = planId;
        r.relatedExposureId = exposureId;
        r.timeDesc = "CONTRACT_SIGNED";
        r.reasonText = text;
        r.forcedByFreqAlert = "N";
        r.relatedObjectMissingFlag = relatedFlag;
        r.operatorId = OPERATOR;
        r.fillTime = TS;
        r.isModified = "N";
        r.reasonMissingFlag = missingFlag;
        assertEquals(1, reasonDao.insert(r, OPERATOR));
    }

    private void reject(String ref, String ruleId, String code, String current, String threshold,
                        LocalDate tradeDate) {
        TradePreTradeReject r = new TradePreTradeReject();
        r.subAccountId = SUB;
        r.clientOrderRef = ref;
        r.orderDigest = "digest";
        r.productId = PRODUCT;
        r.instrumentId = "cu2612";
        r.direction = "BUY";
        r.volume = 1;
        r.ruleId = ruleId;
        r.rejectCode = code;
        r.limitLayer = "SUB_PRODUCT";
        r.currentValue = current;
        r.thresholdValue = threshold;
        r.basePriceSource = "NOT_APPLICABLE";
        r.rejectReason = "pre-trade reject";
        r.operatorId = OPERATOR;
        r.rejectTsMs = 0L;
        r.rejectTime = tradeDate.atStartOfDay();
        r.tradeDate = tradeDate;
        r.eventSeq = 1L;
        r.reportedRetryCount = 0;
        assertEquals(1, rejectDao.insert(r, OPERATOR));
    }

    private static List<String> schemaScripts() {
        return Arrays.asList(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m1_product.sql",
                "docs/DEPLOY/schema/m1_spot.sql",
                "docs/DEPLOY/schema/m1_plan.sql",
                "docs/DEPLOY/schema/m2_trade.sql",
                "docs/DEPLOY/schema/m3_audit.sql");
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/DEPLOY/schema"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("无法定位仓库根（docs/DEPLOY/schema）");
        }
        return p;
    }
}
