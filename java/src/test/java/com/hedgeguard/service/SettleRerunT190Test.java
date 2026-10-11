package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.SettleFund;
import com.hedgeguard.dao.entity.SettlePnl;
import com.hedgeguard.dao.entity.SettlePosition;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmSettleCtpBatchDao;
import com.hedgeguard.dao.impl.DmSettleCtpFundDao;
import com.hedgeguard.dao.impl.DmSettleCtpPositionDao;
import com.hedgeguard.dao.impl.DmSettleCtpTradeDao;
import com.hedgeguard.dao.impl.DmSettleFundDao;
import com.hedgeguard.dao.impl.DmSettlePnlDao;
import com.hedgeguard.dao.impl.DmSettlePositionDao;
import com.hedgeguard.dao.impl.DmSettleReconcileDifferenceDao;
import com.hedgeguard.dao.impl.DmSettleReconcileRecordDao;
import com.hedgeguard.dao.impl.DmSettleReportDao;
import com.hedgeguard.dao.impl.DmSettleStressTestDao;
import com.hedgeguard.dao.impl.DmSettleAggDiffCandidateDao;
import com.hedgeguard.dao.impl.DmSettleAggregationCheckDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-190} 修复①：未关账重复结算端到端幂等（「真实 DAO + 内存假 JDBC 引擎」，禁假 DAO）。
 *
 * <p>缺陷（te-verify-t189 O-4① 结构性确认）：{@code applyPositionSplit}/{@code applyFund}
 * 无条件重插 ⇒ 真库（DM8 enforce uk_settle_position_key / uk_settle_fund_date_sub）重复结算必崩。
 *
 * <p><b>勘误（te-verify-t190 探针证伪，2026-09）</b>：本类早期 javadoc 称「InMemory 引擎不
 * enforce uk」<b>不成立</b>——引擎在建表时登记表级 UNIQUE 约束并在写入时强制校验
 * （{@code InMemoryDmEngine} UNIQUE 违反即抛，与 DM8 同语义）；既有测试假绿的真因是
 * <b>从未端到端重跑结算</b>（无重复执行路径 ⇒ uk 校验从未被触发），而非引擎不 enforce。
 *
 * <p><b>断言纪律</b>：幂等用例以「重复执行后<b>行数/内容正确</b>」为主判据（幂等语义的
 * 验收口径），不以「uk 报错路径」作为预期行为；引擎事实上 enforce uk ⇒ 守卫漏键时
 * 重插会以 UNIQUE 违反异常当场显形（TE-T190-001 探针即据此复现崩溃）。
 * 修复语义 = 跳过式幂等（同 uk 键已存在即跳过，与 T-189 盈亏转录姿态一致）；
 * 「已确认/关账」单重算仍走 R-SETTLE-5 作废重签（PERIOD_CLOSED），状态机不变。
 */
class SettleRerunT190Test {

    private static final int SUB_A = 11;
    private static final long PRODUCT = 55L;
    private static final long SYSTEM_OP = 0L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private DmSettleCycleDao cycleDao;
    private DmSettlePositionDao positionDao;
    private DmSettleFundDao fundDao;
    private DmSettlePnlDao pnlDao;
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
        M2E2eSeed.product(engine, PRODUCT, "CU", "沪铜");

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        cycleDao = new DmSettleCycleDao(tx);
        positionDao = new DmSettlePositionDao(tx);
        fundDao = new DmSettleFundDao(tx);
        pnlDao = new DmSettlePnlDao(tx);
        service = new SettleService(tx, userId -> Arrays.asList(SUB_A),
                new DmSettleCtpBatchDao(tx), new DmSettleCtpPositionDao(tx),
                new DmSettleCtpTradeDao(tx), new DmSettleCtpFundDao(tx),
                cycleDao, positionDao, fundDao, pnlDao,
                new DmSettleReconcileRecordDao(tx), new DmSettleReconcileDifferenceDao(tx),
                new DmSettleAggregationCheckDao(tx), new DmSettleAggDiffCandidateDao(tx),
                new DmSettleReportDao(tx), new DmSettleStressTestDao(tx));
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

    // ------------------------------------------------------------------ 数据构造辅助

    private SettleService.CtpDataRequest ctpRequest() {
        SettleService.CtpDataRequest r = new SettleService.CtpDataRequest();
        r.tradeDate = DAY.toString();
        r.sourceIdentifier = "CTP-T190";
        r.positions = new ArrayList<>();
        SettleService.CtpPositionRow p = new SettleService.CtpPositionRow();
        p.instrumentId = "cu2612";
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = "1";
        p.closeProfit = "1000.50";
        p.positionProfit = "-200.00";
        r.positions.add(p);
        r.funds = new ArrayList<>();
        SettleService.CtpFundRow f = new SettleService.CtpFundRow();
        f.equity = "1000";
        f.availableFund = "800";
        f.marginOccupied = "200";
        r.funds.add(f);
        r.trades = new ArrayList<>();
        SettleService.CtpTradeRow t = new SettleService.CtpTradeRow();
        t.tradeId = "T1";
        t.instrumentId = "cu2612";
        t.direction = "LONG";
        t.offsetFlag = "OPEN";
        t.fillPrice = "70000";
        t.fillVolume = 1;
        t.commission = "12.50";
        r.trades.add(t);
        return r;
    }

    private SettleService.SettlementInput settlementInput() {
        SettleService.SettlementInput in = new SettleService.SettlementInput();
        in.tradeDate = DAY.toString();
        in.positions = new ArrayList<>();
        SettleService.SystemPositionRow p = new SettleService.SystemPositionRow();
        p.subAccountId = SUB_A;
        p.instrumentId = "cu2612";
        p.productId = PRODUCT;
        p.instrumentType = "FUTURES";
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = "1";
        in.positions.add(p);
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

    // ------------------------------------------------------------------ 查询辅助

    private List<SettlePosition> positionRows() {
        PageResult<SettlePosition> page = positionDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Arrays.asList((Object) DAY));
        return page.items();
    }

    private List<SettleFund> fundRows() {
        PageResult<SettleFund> page = fundDao.findPage(
                PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Arrays.asList((Object) DAY));
        return page.items();
    }

    private List<SettlePnl> pnlRows() {
        return pnlDao.findByTradeDate(DAY);
    }

    // ================================================================ 一、重复结算端到端幂等（跳过式）

    @Test
    @DisplayName("重复结算不崩溃且幂等：二次结算后持仓/资金/盈亏/结算单行数与值与首跑逐项一致（T-190 修复①）")
    void rerunSettlementIdempotentEndToEnd() {
        service.ingestCtpData(ctpRequest(), SYSTEM_OP);
        List<SettleCycle> first = service.runSettlement(settlementInput(), SYSTEM_OP);
        assertEquals(1, first.size(), "首跑应产出 1 张结算单");

        // 首跑快照
        List<SettlePosition> pos1 = positionRows();
        List<SettleFund> fund1 = fundRows();
        List<SettlePnl> pnl1 = pnlRows();
        assertEquals(1, pos1.size(), "首跑持仓明细 1 行");
        assertEquals(1, fund1.size(), "首跑资金明细 1 行");
        assertEquals(3, pnl1.size(), "首跑盈亏明细 3 行（COMMISSION/CLOSE/FLOAT 各 1）");

        // 二次结算（未关账重跑）：修复前 ⇒ applyPositionSplit/applyFund 无条件重插，真库违 uk 崩溃；
        // 修复后 ⇒ 跳过式幂等，不崩且行数/值不变
        List<SettleCycle> second = service.runSettlement(settlementInput(), SYSTEM_OP);
        assertEquals(1, second.size(), "重跑不新增结算单（upsertCycle 幂等）");
        assertEquals(first.get(0).id, second.get(0).id, "重跑复用同一结算单");

        List<SettlePosition> pos2 = positionRows();
        List<SettleFund> fund2 = fundRows();
        List<SettlePnl> pnl2 = pnlRows();
        assertEquals(pos1.size(), pos2.size(), "重复结算后持仓行数不变（不重插）");
        assertEquals(fund1.size(), fund2.size(), "重复结算后资金行数不变（不重插）");
        assertEquals(pnl1.size(), pnl2.size(), "重复结算后盈亏行数不变（T-189 转录幂等）");
        assertEquals(1, cycleDao.findPage(PageQuery.of("1", "100", "id,asc", "id"),
                "trade_date = ?", Arrays.asList((Object) DAY)).items().size(), "结算单仍 1 份");

        // 内容逐项一致（行数与值；比较用 compareTo，规避标度差异）
        for (int i = 0; i < pos1.size(); i++) {
            assertEquals(pos1.get(i).instrumentId, pos2.get(i).instrumentId);
            assertEquals(pos1.get(i).direction, pos2.get(i).direction);
            assertEquals(pos1.get(i).positionType, pos2.get(i).positionType);
            assertEquals(0, pos1.get(i).positionQty.compareTo(pos2.get(i).positionQty),
                    "持仓数量逐行一致（首跑=" + pos1.get(i).positionQty + "）");
        }
        for (int i = 0; i < fund1.size(); i++) {
            assertEquals(0, fund1.get(i).equity.compareTo(fund2.get(i).equity), "权益一致");
            assertEquals(0, fund1.get(i).endBalance.compareTo(fund2.get(i).endBalance),
                    "期末资金一致（不重算不重插）");
        }
        for (int i = 0; i < pnl1.size(); i++) {
            assertEquals(pnl1.get(i).pnlType, pnl2.get(i).pnlType);
            assertEquals(0, pnl1.get(i).amount.compareTo(pnl2.get(i).amount), "盈亏金额逐行一致");
        }
    }

    @Test
    @DisplayName("跳过式原值保持：重跑时已存在持仓/资金行不被覆盖删除（ uk 键命中即跳过）")
    void rerunKeepsFirstRunDetailRows() {
        service.ingestCtpData(ctpRequest(), SYSTEM_OP);
        service.runSettlement(settlementInput(), SYSTEM_OP);
        long firstPositionId = positionRows().get(0).id;
        long firstFundId = fundRows().get(0).id;

        service.runSettlement(settlementInput(), SYSTEM_OP);

        assertEquals(firstPositionId, positionRows().get(0).id,
                "持仓行须为首跑同一行（跳过式，不是删后重插）");
        assertEquals(firstFundId, fundRows().get(0).id,
                "资金行须为首跑同一行（跳过式，不是删后重插）");
    }

    // ================================================================ 二、单户 >100 行（TE-T190-001 回归）

    /** TE-T190-001 探针形态：SUB_A 110 行（超单页上限 100）+ SUB_B 2 行。 */
    private static final int SUB_B = 12;
    private static final int WIDE_ROWS = 110;

    /**
     * 宽输入：SUB_A 110 个不同合约（LONG/TODAY）+ SUB_B 2 个合约。
     * 110 个合约共用已播种品种 {@code PRODUCT}（fk_t_settle_position_product 满足）；
     * 这些合约不在 CTP 批次 ⇒ 盈亏转录跳过（无转录值，T-189 语义），不影响持仓拆分。
     */
    private SettleService.SettlementInput wideInput() {
        SettleService.SettlementInput in = new SettleService.SettlementInput();
        in.tradeDate = DAY.toString();
        in.positions = new ArrayList<>();
        for (int i = 1; i <= WIDE_ROWS; i++) {
            in.positions.add(row(SUB_A, String.format("cu2%03d", i)));
        }
        in.positions.add(row(SUB_B, "cu2301"));
        in.positions.add(row(SUB_B, "cu2302"));
        in.funds = new ArrayList<>();
        in.funds.add(fund(SUB_A));
        in.funds.add(fund(SUB_B));
        in.trades = new ArrayList<>();
        return in;
    }

    private static SettleService.SystemPositionRow row(int subId, String instrumentId) {
        SettleService.SystemPositionRow p = new SettleService.SystemPositionRow();
        p.subAccountId = subId;
        p.instrumentId = instrumentId;
        p.productId = PRODUCT;
        p.instrumentType = "FUTURES";
        p.direction = "LONG";
        p.positionType = "TODAY";
        p.positionQty = "1";
        return p;
    }

    private static SettleService.SystemFundRow fund(int subId) {
        SettleService.SystemFundRow f = new SettleService.SystemFundRow();
        f.subAccountId = subId;
        f.beginBalance = "0";
        f.equity = "1000";
        f.ledgerEquity = "1000";
        f.availableFund = "800";
        f.marginOccupied = "200";
        f.closePnl = "0";
        f.floatPnl = "0";
        f.commission = "0";
        return f;
    }

    /** 全量持仓行（逐页补载——单页 100 上限会截断，断言侧同主代码纪律）。 */
    private List<SettlePosition> allPositionRows() {
        List<SettlePosition> out = new ArrayList<>();
        int pageNo = 1;
        while (true) {
            PageResult<SettlePosition> page = positionDao.findPage(
                    PageQuery.of(String.valueOf(pageNo), "100", "id,asc", "id"),
                    "trade_date = ?", Arrays.asList((Object) DAY));
            out.addAll(page.items());
            if (page.items().size() < 100) {
                return out;
            }
            pageNo++;
        }
    }

    @Test
    @DisplayName("TE-T190-001 回归：单户 110 行 + 他户 2 行重复结算——键集逐页补载，不违 uk、行数内容不变")
    void rerunSingleSubOverPageSizeIdempotent() throws Exception {
        M2E2eSeed.subAccount(engine, SUB_B, "SA-B", "商品子账户B");
        service.ingestCtpData(ctpRequest(), SYSTEM_OP);
        List<SettleCycle> first = service.runSettlement(wideInput(), SYSTEM_OP);
        assertEquals(2, first.size(), "两子账户各 1 张结算单");

        List<SettlePosition> pos1 = allPositionRows();
        assertEquals(WIDE_ROWS + 2, pos1.size(),
                "首跑持仓 112 行（SUB_A 110 + SUB_B 2；超单页上限 100）");
        long subA1 = pos1.stream().filter(p -> p.subAccountId == SUB_A).count();
        long subB1 = pos1.stream().filter(p -> p.subAccountId == SUB_B).count();
        assertEquals(WIDE_ROWS, subA1, "SUB_A 首跑 110 行");
        assertEquals(2, subB1, "SUB_B 首跑 2 行");

        // 二跑：修复前键集单页截断（100 行上限）⇒ SUB_A 第 101~110 行漏键重插
        // ⇒ uk_settle_position_key 违反崩溃（TE-T190-001 探针实测）；
        // 修复后键集逐页循环补载 ⇒ 全部命中跳过 ⇒ 不崩且行数/内容不变
        List<SettleCycle> second = service.runSettlement(wideInput(), SYSTEM_OP);
        assertEquals(2, second.size(), "重跑不新增结算单");
        assertEquals(first.get(0).id, second.get(0).id, "SUB_A 复用同一结算单");
        assertEquals(first.get(1).id, second.get(1).id, "SUB_B 复用同一结算单");

        List<SettlePosition> pos2 = allPositionRows();
        assertEquals(pos1.size(), pos2.size(), "重复结算后持仓行数不变（112 行，不重插）");
        // 内容逐行一致（uk 全序：date|sub|instrument|direction|type 唯一 ⇒ 按 id 排序可比）
        for (int i = 0; i < pos1.size(); i++) {
            assertEquals(pos1.get(i).subAccountId, pos2.get(i).subAccountId);
            assertEquals(pos1.get(i).instrumentId, pos2.get(i).instrumentId,
                    "第 " + (i + 1) + " 行合约一致（含 SUB_A 边界行 cu2100/cu2110）");
            assertEquals(0, pos1.get(i).positionQty.compareTo(pos2.get(i).positionQty));
        }
        // 跳过式：首跑同一物理行（非删后重插）
        assertEquals(pos1.get(0).id, pos2.get(0).id, "首行为首跑同一物理行");
        assertEquals(pos1.get(pos1.size() - 1).id, pos2.get(pos2.size() - 1).id,
                "末行（单页截断边界外）为首跑同一物理行");
    }

    // ================================================================ 三、关账单重算被拒（R-SETTLE-5 状态机不变）

    @Test
    @DisplayName("已确认关账结算单重算 ⇒ PERIOD_CLOSED 拒绝；明细行数不被扰动（R-SETTLE-5 不绕过）")
    void confirmedCycleRerunRejected() {
        service.ingestCtpData(ctpRequest(), SYSTEM_OP);
        List<SettleCycle> first = service.runSettlement(settlementInput(), SYSTEM_OP);
        assertEquals(1, first.size());
        SettleCycle cycle = cycleDao.findById(first.get(0).id);
        assertNotNull(cycle);
        cycle.status = "CONFIRMED"; // 关账（R-SETTLE-5 作废重签入口之外的直接终态）
        assertEquals(1, cycleDao.updateById(cycle, SYSTEM_OP), "关账更新应成功（乐观锁版本取自读取）");

        long posBefore = positionRows().size();
        long fundBefore = fundRows().size();
        long pnlBefore = pnlRows().size();

        BizException e = assertThrows(BizException.class,
                () -> service.runSettlement(settlementInput(), SYSTEM_OP),
                "已确认结算单重算须被拒（R-SETTLE-5）");
        assertEquals(ErrorCode.PERIOD_CLOSED, e.errorCode(), "错误码须为 10016 期间已关账");
        assertEquals(posBefore, positionRows().size(), "被拒后持仓行数不变");
        assertEquals(fundBefore, fundRows().size(), "被拒后资金行数不变");
        assertEquals(pnlBefore, pnlRows().size(), "被拒后盈亏行数不变");
    }

    // ================================================================ 四、回归：结算单六项与勾稽在重跑后仍正确

    @Test
    @DisplayName("重跑后结算单六项仍与首跑一致（跳过资金插入但六项回填不破坏一致性）")
    void rerunKeepsCycleSixItemsConsistent() {
        service.ingestCtpData(ctpRequest(), SYSTEM_OP);
        List<SettleCycle> first = service.runSettlement(settlementInput(), SYSTEM_OP);
        SettleCycle c1 = cycleDao.findById(first.get(0).id);

        service.runSettlement(settlementInput(), SYSTEM_OP);
        SettleCycle c2 = cycleDao.findById(first.get(0).id);

        assertNotNull(c1);
        assertNotNull(c2);
        assertEquals(0, nz(c1.equity).compareTo(nz(c2.equity)), "权益一致");
        assertEquals(0, nz(c1.availableFund).compareTo(nz(c2.availableFund)), "可用资金一致");
        assertEquals(0, nz(c1.marginOccupied).compareTo(nz(c2.marginOccupied)), "保证金占用一致");
        assertEquals(c1.negativeFundFlag, c2.negativeFundFlag, "负资金标记一致");
        if (c1.riskRatio == null) {
            assertTrue(c2.riskRatio == null, "风险度一致（均为空）");
        } else {
            assertEquals(0, c1.riskRatio.setScale(6, RoundingMode.HALF_UP)
                            .compareTo(c2.riskRatio.setScale(6, RoundingMode.HALF_UP)),
                    "风险度一致");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
