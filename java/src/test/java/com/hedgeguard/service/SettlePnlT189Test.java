package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.SettlePnl;
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
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.handler.settle.SettleCtpDataHandler;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-189} 结算链生产写入 {@code t_settle_pnl}（「真实 DAO + 内存假 JDBC 引擎」，禁假 DAO）。
 *
 * <p>覆盖：导入契约扩列（{@code closeProfit}/{@code positionProfit}）回执披露与旧模板兼容
 * （缺列/空值不硬失败）、{@code COMMISSION} 转录行落库（Σ {@code CtpTradeRow.commission}
 * 加总非推算）、重复结算幂等（跳过式，uk_settle_pnl_key）、多子账户共用合约归属歧义跳过
 * （不默认归入）、无成交/空手续费边界。触发行与全链路见 {@code MajorEventLossTriggerTest}。</p>
 *
 * <p>⚠️ 诚实披露：{@code CLOSE_REALIZED}/{@code FLOAT_UNREALIZED} 转录行的数据源（导入契约扩列）
 * 已裁方案 A，但其中间持久化承载须扩 {@code TB-SETTLE-2}（DATA_MODEL 冻结面，
 * {@code M2SettleSchemaTest} 逐列锁 DDL）⇒ 本单按停手条款升级 CH，批准前不落该两类行。</p>
 */
class SettlePnlT189Test {

    private static final int SUB_A = 11;
    private static final int SUB_B = 12;
    private static final long PRODUCT = 55L;
    private static final long SYSTEM_OP = 0L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private DmSettlePnlDao settlePnlDao;
    private SettleService service;

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
        M2E2eSeed.subAccount(engine, SUB_A, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, SUB_B, "SA-B", "商品子账户B");
        M2E2eSeed.product(engine, PRODUCT, "CU", "沪铜");

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        settlePnlDao = new DmSettlePnlDao(tx);
        service = newService(null);
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

    private SettleService newService(MajorEventLossThresholdTrigger trigger) {
        SettleService s = new SettleService(tx, userId -> Arrays.asList(SUB_A, SUB_B),
                new DmSettleCtpBatchDao(tx), new DmSettleCtpPositionDao(tx),
                new DmSettleCtpTradeDao(tx), new DmSettleCtpFundDao(tx),
                new DmSettleCycleDao(tx), new DmSettlePositionDao(tx),
                new DmSettleFundDao(tx), settlePnlDao,
                new DmSettleReconcileRecordDao(tx), new DmSettleReconcileDifferenceDao(tx),
                new DmSettleAggregationCheckDao(tx), new DmSettleAggDiffCandidateDao(tx),
                new DmSettleReportDao(tx), new DmSettleStressTestDao(tx));
        if (trigger != null) {
            s.setLossThresholdTrigger(trigger);
        }
        return s;
    }

    // ------------------------------------------------------------------ 请求构造辅助

    private SettleService.CtpDataRequest ctpRequest() {
        SettleService.CtpDataRequest r = new SettleService.CtpDataRequest();
        r.tradeDate = DAY.toString();
        r.sourceIdentifier = "CTP-T189";
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

    private SettleService.CtpTradeRow trade(String instrumentId, String tradeId, String commission) {
        SettleService.CtpTradeRow t = new SettleService.CtpTradeRow();
        t.tradeId = tradeId;
        t.instrumentId = instrumentId;
        t.direction = "LONG";
        t.offsetFlag = "OPEN";
        t.fillPrice = "70000";
        t.fillVolume = 1;
        t.commission = commission;
        return t;
    }

    private void addPosition(SettleService.CtpDataRequest r, String instrumentId, String qty) {
        SettleService.CtpPositionRow p = new SettleService.CtpPositionRow();
        p.instrumentId = instrumentId;
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = qty;
        r.positions.add(p);
    }

    private SettleService.SettlementInput settlementInput() {
        return settlementInput(true);
    }

    private SettleService.SettlementInput settlementInput(boolean subAHoldsCu) {
        SettleService.SettlementInput in = new SettleService.SettlementInput();
        in.tradeDate = DAY.toString();
        in.positions = new ArrayList<>();
        if (subAHoldsCu) {
            in.positions.add(sysPosition(SUB_A, "cu2612", "1"));
        }
        in.funds = new ArrayList<>();
        SettleService.SystemFundRow f = new SettleService.SystemFundRow();
        f.subAccountId = SUB_A;
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

    private SettleService.SystemPositionRow sysPosition(int sub, String instrumentId, String qty) {
        SettleService.SystemPositionRow p = new SettleService.SystemPositionRow();
        p.subAccountId = sub;
        p.instrumentId = instrumentId;
        p.productId = PRODUCT;
        p.instrumentType = "FUTURES";
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = qty;
        return p;
    }

    private List<SettlePnl> pnlRows() {
        return settlePnlDao.findByTradeDate(DAY);
    }

    private List<SettlePnl> commissionRows() {
        // 断言侧锚定冻结字面量（ck_t_settle_pnl_pnl_type CHECK 域），不引生产常量：
        // 防止「常量翻转双侧同变」的自我同源变异存活（T-189 变异 M2 教训）
        List<SettlePnl> out = new ArrayList<>();
        for (SettlePnl p : pnlRows()) {
            if ("COMMISSION".equals(p.pnlType)) {
                out.add(p);
            }
        }
        return out;
    }

    // ================================================================ 一、导入契约扩列：回执披露 + 旧模板兼容

    @Test
    @DisplayName("导入扩列回执：1 行携带 / 1 行未携带逐合约盈亏 ⇒ 回执分计数披露（T-189）")
    void ingestReceiptDisclosesPnlFieldCoverage() {
        SettleService.CtpDataRequest req = ctpRequest();
        req.positions.get(0).closeProfit = "1000.50";
        req.positions.get(0).positionProfit = "-200.00";
        addPosition(req, "au2612", "2"); // 旧模板行：无盈亏字段

        SettleService.CtpIngestResult r = service.ingestCtpData(req, SYSTEM_OP);

        assertFalse(r.pendingRetry);
        assertEquals(2, r.positionCount);
        assertEquals(1, r.positionPnlPresentCount, "携带盈亏字段的行数应披露");
        assertEquals(1, r.positionPnlMissingCount, "未携带盈亏字段的行数应披露");
        assertTrue(r.message.contains("1 行"), "回执说明应披露缺失行数：" + r.message);
    }

    @Test
    @DisplayName("旧模板兼容：持仓行全部缺盈亏列 ⇒ 不硬失败、批次照常确认、missing=2")
    void oldTemplateWithoutPnlFieldsIngestsCleanly() {
        SettleService.CtpDataRequest req = ctpRequest(); // 两行（构造 1 行 + au2612）均无盈亏字段
        addPosition(req, "au2612", "2");

        SettleService.CtpIngestResult r = service.ingestCtpData(req, SYSTEM_OP);

        assertFalse(r.pendingRetry, "缺列不得置失败/重试");
        assertEquals(0, r.positionPnlPresentCount);
        assertEquals(2, r.positionPnlMissingCount);
        assertEquals("OK", engine.table("t_ctp_settle_batch").rows.get(0).get("parse_status"),
                "旧模板批次应照常确认（向后兼容）");
    }

    @Test
    @DisplayName("handler 层：JSON 请求混排旧模板行与扩列行 ⇒ 200 + present=1/missing=1（缺列解析不硬失败）")
    void handlerJsonParsesExtendedFields() {
        SettleCtpDataHandler handler = new SettleCtpDataHandler(service);

        // 同批次混排：1 行带盈亏字段（cu2612）+ 1 行旧模板（au2612）；
        // t_ctp_settle_position uk=(trade_date,instrument,direction,type)、
        // t_ctp_settle_fund uk=(trade_date) ⇒ 同日不可多批次重复入库，混排单批覆盖两形态
        String body = "{\"tradeDate\":\"" + DAY + "\",\"sourceIdentifier\":\"CTP-T189-J1\","
                + "\"positions\":["
                + "{\"instrumentId\":\"cu2612\",\"direction\":\"LONG\",\"positionType\":\"TODAY\","
                + "\"positionQty\":\"1\",\"closeProfit\":\"1000.50\",\"positionProfit\":\"-200.00\"},"
                + "{\"instrumentId\":\"au2612\",\"direction\":\"LONG\",\"positionType\":\"TODAY\","
                + "\"positionQty\":\"2\"}],"
                + "\"funds\":[{\"equity\":\"1000\",\"availableFund\":\"800\",\"marginOccupied\":\"200\"}]}";
        HttpResult result = handler.handle(new RequestContext("POST", SettleCtpDataHandler.PATH, null,
                new HashMap<>(), body, "t189", "127.0.0.1"));
        assertEquals(200, result.status, "旧模板行混排不得解析失败");
        String resp = new String(result.body, StandardCharsets.UTF_8);
        assertTrue(resp.contains("\"positionPnlPresentCount\":1"), "扩列字段应被解析并回传：" + resp);
        assertTrue(resp.contains("\"positionPnlMissingCount\":1"), "旧模板行应计入 missing：" + resp);
        assertTrue(resp.contains("旧模板兼容"), "回执说明应披露兼容处置：" + resp);
    }

    // ================================================================ 二、COMMISSION 转录行

    @Test
    @DisplayName("COMMISSION 转录：单子账户独有合约、两笔成交 ⇒ 1 行 (sub,cu2612,COMMISSION,Σ=20.00)")
    void settleTranscribesCommissionRow() {
        SettleService.CtpDataRequest req = ctpRequest();
        req.trades.add(trade("cu2612", "T1", "12.50"));
        req.trades.add(trade("cu2612", "T2", "7.50"));
        service.ingestCtpData(req, SYSTEM_OP);

        List<SettleCycle> cycles = service.runSettlement(settlementInput(), SYSTEM_OP);
        assertEquals(1, cycles.size());

        List<SettlePnl> rows = commissionRows();
        assertEquals(1, rows.size(), "应恰落 1 行 COMMISSION");
        SettlePnl row = rows.get(0);
        assertEquals(SUB_A, row.subAccountId);
        assertEquals("cu2612", row.instrumentId);
        assertEquals("COMMISSION", row.pnlType, "pnl_type 须为冻结域字面量");
        assertEquals(0, row.amount.compareTo(new BigDecimal("20.00")), "手续费应为两笔加总（Σ 非推算）");
        assertEquals(PRODUCT, row.productId, "品种取系统内持仓行的品种归属");
        assertEquals(cycles.get(0).id, row.settleCycleId, "结算单外键应指向本次结算单");
        assertEquals(DAY, row.tradeDate);
    }

    @Test
    @DisplayName("幂等（跳过式）：同 uk 键已存在 ⇒ 转录跳过不重插（仍恰 1 行、原值不被覆盖）")
    void settleRerunIdempotent() {
        // ⚠️ 端到端二次结算当前被既有 uk_settle_position_key 崩溃阻断（applyPositionSplit
        // 无条件重插；T-047 域既有缺陷，本单不修）⇒ 在可达粒度验证跳过式幂等：
        // 预置同键盈亏行（uk_settle_pnl_key 命中）⇒ 结算转录须跳过、不得插入第二行。
        SettleCycle c = new SettleCycle();
        c.subAccountId = SUB_A;
        c.settleNo = "SC-T189-PRESEEDED";
        c.tradeDate = DAY;
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
        new DmSettleCycleDao(tx).insert(c, SYSTEM_OP);
        SettlePnl pre = new SettlePnl();
        pre.subAccountId = SUB_A;
        pre.settleCycleId = c.id;
        pre.tradeDate = DAY;
        pre.instrumentId = "cu2612";
        pre.productId = PRODUCT;
        pre.pnlType = "COMMISSION";
        pre.amount = new BigDecimal("1.23");
        settlePnlDao.insert(pre, SYSTEM_OP);

        SettleService.CtpDataRequest req = ctpRequest();
        req.trades.add(trade("cu2612", "T1", "12.50"));
        service.ingestCtpData(req, SYSTEM_OP);
        service.runSettlement(settlementInput(), SYSTEM_OP);

        List<SettlePnl> rows = commissionRows();
        assertEquals(1, rows.size(), "同键已存在 ⇒ 转录跳过（uk 幂等）");
        assertEquals(0, rows.get(0).amount.compareTo(new BigDecimal("1.23")),
                "已存在行不得被转录覆盖（跳过式语义）");
    }

    @Test
    @DisplayName("归属歧义：两子账户共用合约 ⇒ 跳过转录不默认归入（不破坏可加总性）；独有合约正常落行")
    void multiSubInstrumentSkippedNotAssigned() {
        SettleService.CtpDataRequest req = ctpRequest();
        addPosition(req, "au2612", "2"); // 仅持仓面占位；成交面以下两笔
        req.trades.add(trade("cu2612", "T1", "12.50")); // 仅 SUB_A 持有 ⇒ 落行
        req.trades.add(trade("au2612", "T2", "99.00")); // A/B 共同持有 ⇒ 歧义跳过
        service.ingestCtpData(req, SYSTEM_OP);

        SettleService.SettlementInput in = settlementInput();
        in.positions.add(sysPosition(SUB_B, "au2612", "1"));
        in.positions.add(sysPosition(SUB_A, "au2612", "1"));
        SettleService.SystemFundRow fb = new SettleService.SystemFundRow();
        fb.subAccountId = SUB_B;
        fb.equity = "500";
        fb.ledgerEquity = "500";
        in.funds.add(fb);
        service.runSettlement(in, SYSTEM_OP);

        List<SettlePnl> rows = commissionRows();
        assertEquals(1, rows.size(), "仅独有合约落行");
        assertEquals("cu2612", rows.get(0).instrumentId);
        assertTrue(rows.stream().noneMatch(p -> "au2612".equals(p.instrumentId)),
                "共用合约不得默认归入任一子账户");
    }

    @Test
    @DisplayName("边界：合约当日无 CTP 成交 ⇒ 无 COMMISSION 行；成交缺手续费值 ⇒ 按 0 转录")
    void noTradesOrBlankCommissionBoundaries() {
        SettleService.CtpDataRequest req = ctpRequest(); // 无成交
        service.ingestCtpData(req, SYSTEM_OP);
        service.runSettlement(settlementInput(), SYSTEM_OP);
        assertTrue(commissionRows().isEmpty(), "无成交 ⇒ 无行");

        // 换交易日重置：成交 1 笔、commission 缺省（旧模板）⇒ Σ(空)=0 仍转录
        LocalDate next = DAY.plusDays(1);
        Clocks.set(Clock.fixed(next.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        SettleService.CtpDataRequest req2 = ctpRequest();
        req2.tradeDate = next.toString();
        req2.trades.add(trade("cu2612", "T9", null));
        service.ingestCtpData(req2, SYSTEM_OP);
        SettleService.SettlementInput in = settlementInput();
        in.tradeDate = next.toString();
        service.runSettlement(in, SYSTEM_OP);

        List<SettlePnl> rows = settlePnlDao.findByTradeDate(next);
        assertEquals(1, rows.size(), "有成交但未记录手续费 ⇒ 转录为 0（Σ 空值）");
        assertEquals(0, rows.get(0).amount.compareTo(BigDecimal.ZERO));
    }
}
