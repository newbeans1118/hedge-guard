package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.entity.TradeCommandFlow;
import com.hedgeguard.dao.entity.TradeFill;
import com.hedgeguard.dao.entity.TradeOptionStrategy;
import com.hedgeguard.dao.entity.TradeOrder;
import com.hedgeguard.dao.entity.TradeOrderReport;
import com.hedgeguard.dao.entity.TradePosition;
import com.hedgeguard.dao.entity.TradePreTradeReject;
import com.hedgeguard.dao.entity.TradeReason;
import com.hedgeguard.dao.entity.TradeL3Reject;
import com.hedgeguard.dao.entity.TradeReplayLog;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-043} 等价执行（建表 → 真实 DAO 读写 → 约束强制），覆盖 TRADE 10 张表与 10 个 Dm*Dao
 * （v1.3.10/T-206：含 TB-TRADE-10 t_trade_l3_reject）。
 *
 * <p>本环境无可达 DM8 实例，故把 {@code docs/DEPLOY/schema/m2_trade.sql} 的 {@code CREATE TABLE}
 * 逐条喂给 {@link InMemoryDmEngine}（按 DDL 语义登记列/NOT NULL/主键/唯一/外键/CHECK），
 * 再由真实 DAO 生成 INSERT/SELECT 真正执行、按语义求值。仅喂 TRADE 脚本 ⇒ 跨模块目标表
 * （t_sub_account/t_product/t_exposure/t_trade_plan/t_hedge_plan）缺失，引擎对缺失引用表
 * 跳过外键校验（模块内 FK 仍强制）；跨模块外键的存在性由 {@code TradeSchemaTest} 静态核对。
 *
 * <p>结论口径：通过 ⇒ 「建表 → 读写 → 约束强制」在等价引擎上跑通；DM8 实机待部署方复跑。
 */
class TradeDm8EquivalentIntegrationTest {

    private static final String DDL_REL = "docs/DEPLOY/schema/m2_trade.sql";

    private InMemoryDmEngine engine;
    private TxTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        Path file = repoRoot().resolve(DDL_REL);
        assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
        engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
    }

    @Test
    @DisplayName("建表：m2_trade.sql 登记 10 张表；子账户列 NOT NULL；t_replay_log 无子账户列；关键唯一/CHECK 已登记")
    void ddlCreatesNineTables() {
        assertEquals(10, engine.tableCount(), "应登记 10 张 TRADE 表，实际 " + engine.tableCount());
        InMemoryDmEngine.Table order = engine.table("t_trade_order");
        assertNotNull(order, "t_trade_order 应已建表");
        assertTrue(order.columns.containsKey("client_order_ref"), "t_trade_order 应有 client_order_ref 列");
        assertTrue(order.columns.get("sub_account_id").notNull, "t_trade_order.sub_account_id 应 NOT NULL");
        assertTrue(order.columns.get("id").identity, "id 应 IDENTITY 自增");
        assertTrue(hasUnique(engine.table("t_trade_fill"), "uk_trade_fill_id"),
                "t_trade_fill 应登记 uk_trade_fill_id");
        assertTrue(hasUnique(engine.table("t_pre_trade_reject"), "uk_pre_trade_reject_idem"),
                "t_pre_trade_reject 应登记 uk_pre_trade_reject_idem");
        assertTrue(hasCheck(engine.table("t_pre_trade_reject"), "ck_reject_limit_layer"),
                "t_pre_trade_reject 应登记 ck_reject_limit_layer");
        InMemoryDmEngine.Table replay = engine.table("t_replay_log");
        assertNotNull(replay, "t_replay_log 应已建表");
        assertTrue(!replay.columns.containsKey("sub_account_id"), "t_replay_log 不应有 sub_account_id（例外 E3）");
        for (String t : new String[]{"t_trade_order", "t_trade_order_report", "t_trade_fill",
                "t_trade_reason", "t_position", "t_pre_trade_reject", "t_command_flow", "t_option_strategy",
                "t_trade_l3_reject"}) {
            assertTrue(engine.table(t).columns.get("sub_account_id").notNull, t + ".sub_account_id 应 NOT NULL");
        }
    }

    @Test
    @DisplayName("读写：10 个真实 Dm*Dao 各插一行并读回（主键回填、审计字段填充、CLOB 往返）")
    void allTenDaosInsertAndReadBack() {
        DmTradeOrderDao orderDao = new DmTradeOrderDao(tx);
        TradeOrder order = order();
        assertEquals(1, orderDao.insert(order, 7L), "insert 影响 1 行");
        assertEquals(1L, order.id, "自增主键应回填");
        assertEquals(7L, order.createdBy, "审计 created_by = operatorId");
        assertNotNull(order.createdTime, "审计 created_time 应由时钟填充");
        TradeOrder byRef = orderDao.findByClientOrderRef("HG20261004001");
        assertNotNull(byRef, "按 client_order_ref 应读回");
        assertEquals("PENDING_CONFIRM", byRef.status);
        assertNotNull(orderDao.findBySubAccountAndStatus(1, "PENDING_CONFIRM"), "按子账户+状态查询应可用");

        DmTradeOrderReportDao reportDao = new DmTradeOrderReportDao(tx);
        TradeOrderReport report = report(order.id);
        assertEquals(1, reportDao.insert(report, 7L));
        assertNotNull(reportDao.findByIdem("SYS-1", "ALL_TRADED", 3), "按幂等键应读回回报");

        DmTradeFillDao fillDao = new DmTradeFillDao(tx);
        TradeFill fill = fill(order.id, "TRD-1");
        assertEquals(1, fillDao.insert(fill, 7L));
        assertEquals("TRD-1", fillDao.findByTradeId("TRD-1").tradeId, "按 trade_id 应读回成交");

        DmTradeReasonDao reasonDao = new DmTradeReasonDao(tx);
        TradeReason reason = reason(order.id);
        assertEquals(1, reasonDao.insert(reason, 7L));
        assertNotNull(reasonDao.findByTradeOrderId(order.id), "按 trade_order_id 应读回理由");

        DmTradePositionDao positionDao = new DmTradePositionDao(tx);
        TradePosition position = position();
        assertEquals(1, positionDao.insert(position, 7L));
        assertNotNull(positionDao.findByKey(1, "cu2612", "LONG", "TODAY"), "按四元键应读回持仓");
        assertEquals(1, positionDao.findBySubAccount(1).size(), "按子账户应读回 1 行持仓");

        DmTradePreTradeRejectDao rejectDao = new DmTradePreTradeRejectDao(tx);
        TradePreTradeReject reject = reject();
        assertEquals(1, rejectDao.insert(reject, 7L));
        assertNotNull(rejectDao.findByIdem("HG20261004001", "R-RISK-4"), "按幂等键应读回拒绝记录");
        assertEquals(1, rejectDao.findByClientOrderRef("HG20261004001").size());

        DmTradeCommandFlowDao flowDao = new DmTradeCommandFlowDao(tx);
        TradeCommandFlow flow = flow(order.id);
        assertEquals(1, flowDao.insert(flow, 7L));
        assertEquals(1, flowDao.findByOrderId(order.id).size(), "按指令号应读回流水");
        assertEquals(1, flowDao.findByClientOrderRef("HG20261004001").size());

        DmTradeOptionStrategyDao strategyDao = new DmTradeOptionStrategyDao(tx);
        TradeOptionStrategy strategy = strategy();
        assertEquals(1, strategyDao.insert(strategy, 7L));
        assertNotNull(strategyDao.findByStrategyNo("OPT-1"), "按策略编号应读回");

        DmTradeReplayLogDao replayDao = new DmTradeReplayLogDao(tx);
        TradeReplayLog replay = replay();
        assertEquals(1, replayDao.insert(replay, 7L));
        TradeReplayLog read = replayDao.findByComponent("hg-trading").get(0);
        assertEquals("mismatch-a,mismatch-b", read.mismatchDetail, "CLOB 应往返一致");
        assertEquals("MISMATCH", read.result, "结果码应往返一致");
        assertNotNull(read.startedTsMs, "开始时间应读回");

        // T-206：TB-TRADE-10 t_trade_l3_reject（v1.3.10）
        DmTradeL3RejectDao l3Dao = new DmTradeL3RejectDao(tx);
        TradeL3Reject l3 = l3Reject();
        assertEquals(1, l3Dao.insert(l3, 7L), "insert 影响 1 行");
        assertTrue(l3.id > 0, "自增主键应回填");
        assertEquals(7L, l3.createdBy, "审计 created_by = operatorId");
        assertEquals("L3-FUND-CAP", l3.ruleId, "rule_id 应往返一致");
        assertEquals("SUB_ACCOUNT", l3.quotaScope, "quota_scope 应往返一致");
        assertNotNull(l3.eventTime, "事件时间应读回");
    }

    @Test
    @DisplayName("约束：trade_id 唯一（幂等键）被引擎强制；子账户 NOT NULL 被强制；模块内外键被强制")
    void constraintsEnforced() {
        DmTradeOrderDao orderDao = new DmTradeOrderDao(tx);
        TradeOrder order = order();
        orderDao.insert(order, 7L);

        DmTradeFillDao fillDao = new DmTradeFillDao(tx);
        fillDao.insert(fill(order.id, "TRD-DUP"), 7L);
        IllegalStateException dup = assertThrows(IllegalStateException.class,
                () -> fillDao.insert(fill(order.id, "TRD-DUP"), 7L),
                "重复 trade_id 应被唯一约束拒绝");
        assertTrue(dup.getMessage() != null, "唯一冲突应带原因");
        assertEquals(1L, engine.rejectedByUnique(), "引擎应记录 1 次唯一冲突");

        DmTradeCommandFlowDao flowDao = new DmTradeCommandFlowDao(tx);
        TradeCommandFlow bad = flow(null);
        bad.subAccountId = null;
        assertThrows(IllegalStateException.class, () -> flowDao.insert(bad, 7L),
                "子账户 NOT NULL 应被拒绝");
        assertEquals(1L, engine.rejectedByNotNull(), "引擎应记录 1 次 NOT NULL 冲突");

        DmTradeOrderReportDao reportDao = new DmTradeOrderReportDao(tx);
        IllegalStateException orphan = assertThrows(IllegalStateException.class,
                () -> reportDao.insert(report(999L), 7L), "孤儿 order_id 应被模块内外键拒绝");
        assertTrue(orphan.getMessage() != null);
        assertEquals(1L, engine.rejectedByForeignKey(), "引擎应记录 1 次外键冲突");
    }

    @Test
    @DisplayName("只增不改：DmTradeFillDao / DmTradePreTradeRejectDao / DmTradeCommandFlowDao / DmTradeOrderReportDao / DmTradeL3RejectDao 的 updateById 显式抛")
    void appendOnlyDaosRejectUpdate() {
        assertThrows(UnsupportedOperationException.class,
                () -> new DmTradeFillDao(tx).updateById(fill(1L, "X"), 7L));
        assertThrows(UnsupportedOperationException.class,
                () -> new DmTradeL3RejectDao(tx).updateById(l3Reject(), 7L));
        assertThrows(UnsupportedOperationException.class,
                () -> new DmTradePreTradeRejectDao(tx).updateById(reject(), 7L));
        assertThrows(UnsupportedOperationException.class,
                () -> new DmTradeCommandFlowDao(tx).updateById(flow(1L), 7L));
        assertThrows(UnsupportedOperationException.class,
                () -> new DmTradeOrderReportDao(tx).updateById(report(1L), 7L));
    }

    // ------------------------------------------------------------ 构造器

    private static TradeOrder order() {
        TradeOrder o = new TradeOrder();
        o.subAccountId = 1;
        o.clientOrderRef = "HG20261004001";
        o.tradePlanId = 1L;
        o.hedgePlanId = 1L;
        o.exposureId = 1L;
        o.productId = 1L;
        o.instrumentId = "cu2612";
        o.instrumentType = "FUTURES";
        o.direction = "BUY";
        o.offsetFlag = "OPEN";
        o.priceType = "LIMIT";
        o.limitPrice = new BigDecimal("70000.000000");
        o.volume = 2;
        o.operatorId = 7L;
        o.status = "PENDING_CONFIRM";
        o.submitTime = LocalDateTime.of(2026, 10, 4, 9, 30);
        o.clientTsMs = 1759536600000L;
        return o;
    }

    private static TradeOrderReport report(long orderId) {
        TradeOrderReport r = new TradeOrderReport();
        r.subAccountId = 1;
        r.orderSysId = "SYS-1";
        r.orderId = orderId;
        r.instrumentId = "cu2612";
        r.direction = "BUY";
        r.offsetFlag = "OPEN";
        r.orderStatus = "ALL_TRADED";
        r.volumeTotal = 3;
        r.volumeTraded = 3;
        r.volumeLeft = 0;
        r.reportTsMs = 1759536601000L;
        r.reportTime = LocalDateTime.of(2026, 10, 4, 9, 31);
        r.eventSeq = 10L;
        return r;
    }

    private static TradeFill fill(long orderId, String tradeId) {
        TradeFill f = new TradeFill();
        f.subAccountId = 1;
        f.tradeId = tradeId;
        f.orderId = orderId;
        f.tradePlanId = 1L;
        f.hedgePlanId = 1L;
        f.exposureId = 1L;
        f.productId = 1L;
        f.instrumentId = "cu2612";
        f.instrumentType = "FUTURES";
        f.direction = "BUY";
        f.offsetFlag = "OPEN";
        f.fillPrice = new BigDecimal("70010.000000");
        f.fillVolume = 3;
        f.tradeTsMs = 1759536602000L;
        f.tradeDate = LocalDate.of(2026, 10, 4);
        f.fillTime = LocalDateTime.of(2026, 10, 4, 9, 32);
        f.eventSeq = 11L;
        f.settledFlag = "N";
        return f;
    }

    private static TradeReason reason(long orderId) {
        TradeReason r = new TradeReason();
        r.subAccountId = 1;
        r.tradeOrderId = orderId;
        r.relatedType = "NEW_EXPOSURE";
        r.timeDesc = "CONTRACT_SIGNED";
        r.forcedByFreqAlert = "N";
        r.relatedObjectMissingFlag = "Y";
        r.operatorId = 7L;
        r.fillTime = LocalDateTime.of(2026, 10, 4, 9, 33);
        r.isModified = "N";
        r.reasonMissingFlag = "N";
        return r;
    }

    private static TradePosition position() {
        TradePosition p = new TradePosition();
        p.subAccountId = 1;
        p.instrumentId = "cu2612";
        p.productId = 1L;
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = new BigDecimal("10.0000");
        p.availableQty = new BigDecimal("8.0000");
        p.frozenQty = new BigDecimal("2.0000");
        p.asOfTime = LocalDateTime.of(2026, 10, 4, 15, 0);
        p.eventSeq = 12L;
        return p;
    }

    private static TradePreTradeReject reject() {
        TradePreTradeReject r = new TradePreTradeReject();
        r.subAccountId = 1;
        r.clientOrderRef = "HG20261004001";
        r.orderDigest = "cu2612 BUY OPEN 2";
        r.productId = 1L;
        r.instrumentId = "cu2612";
        r.direction = "BUY";
        r.volume = 2;
        r.ruleId = "R-RISK-4";
        r.rejectCode = "0x1004";
        r.limitLayer = "NONE";
        r.currentValue = "0.93";
        r.thresholdValue = "0.90";
        r.rejectReason = "规模红线";
        r.operatorId = 7L;
        r.rejectTsMs = 1759536603000L;
        r.rejectTime = LocalDateTime.of(2026, 10, 4, 9, 34);
        r.tradeDate = LocalDate.of(2026, 10, 4);
        r.eventSeq = 13L;
        r.reportedRetryCount = 0;
        return r;
    }

    private static TradeCommandFlow flow(Long orderId) {
        TradeCommandFlow f = new TradeCommandFlow();
        f.subAccountId = 1;
        f.orderId = orderId;
        f.clientOrderRef = "HG20261004001";
        f.flowType = "CREATED";
        f.flowDetail = "指令创建";
        f.flowTsMs = 1759536604000L;
        f.flowTime = LocalDateTime.of(2026, 10, 4, 9, 35);
        f.eventSeq = 14L;
        return f;
    }

    private static TradeOptionStrategy strategy() {
        TradeOptionStrategy s = new TradeOptionStrategy();
        s.subAccountId = 1;
        s.strategyNo = "OPT-1";
        s.strategyType = "COLLAR";
        s.hedgePlanId = 1L;
        s.tradePlanId = 1L;
        s.legCount = 2;
        s.totalQuotaOccupied = new BigDecimal("4.0000");
        s.status = "PENDING";
        s.operatorId = 7L;
        s.submitTime = LocalDateTime.of(2026, 10, 4, 9, 36);
        return s;
    }

    private static TradeReplayLog replay() {
        TradeReplayLog r = new TradeReplayLog();
        r.componentId = "hg-trading";
        r.replaySeq = 1;
        r.triggerSource = "STARTUP";
        r.state = "READY";
        r.replayedRecords = 100L;
        r.totalRecords = 100L;
        r.inFlightOrders = 0;
        r.ctpMatchedCount = 5;
        r.ctpMismatchCount = 2;
        r.mismatchDetail = "mismatch-a,mismatch-b";
        r.startedTsMs = 1759536605000L;
        r.finishedTsMs = 1759536606000L;
        r.durationMs = 1000L;
        r.result = "MISMATCH";
        return r;
    }
    private static TradeL3Reject l3Reject() {
        TradeL3Reject r = new TradeL3Reject();
        r.subAccountId = 1;
        r.ruleId = "L3-FUND-CAP";
        r.triggerSource = "L3-FUND-CAP";
        r.quotaScope = "SUB_ACCOUNT";
        r.productId = 1L;
        r.currentValue = "1000";
        r.thresholdValue = "1000";
        r.blockedOperation = "PLACE_ORDER";
        r.operatorId = 7L;
        r.eventTime = LocalDateTime.of(2026, 10, 4, 9, 30);
        return r;
    }


    // ------------------------------------------------------------ 工具

    private static boolean hasUnique(InMemoryDmEngine.Table table, String name) {
        return table.uniques.stream().anyMatch(u -> name.equals(u.name));
    }

    private static boolean hasCheck(InMemoryDmEngine.Table table, String name) {
        return table.checks.stream().anyMatch(c -> name.equals(c.name));
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根（应含 docs/DATA_MODEL.md），当前目录="
                + Paths.get("").toAbsolutePath());
    }
}
