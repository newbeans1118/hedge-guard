package com.hedgeguard.service.impl;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.SettlePnl;
import com.hedgeguard.dao.entity.SettlePosition;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmSettlePnlDao;
import com.hedgeguard.dao.impl.DmSettlePositionDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.M3AuditTraceSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AuditReportService.ReportRow;
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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-069} 审计报表聚合数据源（达梦实现）「真实 DAO + 内存假 JDBC 引擎」用例（禁假 DAO）。
 */
class DmAuditReportDataProviderTest {

    private static final int SUB = 11;
    private static final long PRODUCT = 7001L;
    private static final long PLAN = 1001L;
    private static final long SETTLE = 5001L;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime TS = DAY.atTime(10, 0);

    private InMemoryDmEngine engine;
    private TxTemplate tx;

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
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        M2E2eSeed.hedgePlan(engine, PLAN, SUB, PRODUCT, "HP202610060001", "100", DAY, DAY.plusMonths(3));
        M3AuditTraceSeed.settleCycleConfirmed(engine, SETTLE, SUB, "ST-1", DAY, TS);
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("按子账户×品种聚合：规模/持仓/衍生品盈亏 + 实货盈亏缺口")
    void aggregates() {
        DmSettlePositionDao positionDao = new DmSettlePositionDao(tx);
        DmSettlePnlDao pnlDao = new DmSettlePnlDao(tx);
        SettlePosition position = new SettlePosition();
        position.subAccountId = SUB;
        position.settleCycleId = SETTLE;
        position.tradeDate = DAY;
        position.instrumentId = "cu2612";
        position.productId = PRODUCT;
        position.instrumentType = "FUTURES";
        position.direction = "LONG";
        position.positionType = "YESTERDAY";
        position.positionQty = new BigDecimal("10");
        position.attributionSource = "TRADE_RECORD";
        tx.inTransaction(connection -> positionDao.insert(position, OPERATOR));

        SettlePnl pnl = new SettlePnl();
        pnl.subAccountId = SUB;
        pnl.settleCycleId = SETTLE;
        pnl.tradeDate = DAY;
        pnl.instrumentId = "cu2612";
        pnl.productId = PRODUCT;
        pnl.hedgePlanId = PLAN;
        pnl.pnlType = "CLOSE_REALIZED";
        pnl.amount = new BigDecimal("500");
        pnl.qty = new BigDecimal("10");
        tx.inTransaction(connection -> pnlDao.insert(pnl, OPERATOR));

        DmAuditReportDataProvider provider = new DmAuditReportDataProvider(new DmHedgePlanDao(tx),
                positionDao, pnlDao, new DmProductDao(tx));
        List<ReportRow> rows = provider.rows(DAY, DAY.plusMonths(3),
                Collections.singletonList(SUB), false);

        assertEquals(1, rows.size());
        ReportRow r = rows.get(0);
        assertEquals(Integer.valueOf(SUB), r.subAccountId);
        assertEquals(Long.valueOf(PRODUCT), r.productId);
        assertEquals("铜", r.productName);
        assertEquals(0, r.hedgeScale.compareTo(new BigDecimal("100")), "保值规模=计划量");
        assertEquals(0, r.positionQty.compareTo(new BigDecimal("10")), "持仓");
        assertEquals(0, r.derivativePnl.compareTo(new BigDecimal("500")), "衍生品盈亏=已实现");
        assertNull(r.physicalPnl, "实货盈亏缺口须为 null（不伪造）");
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
        if (p == null) {
            throw new IllegalStateException("无法定位仓库根（docs/DEPLOY/schema）");
        }
        return p;
    }
}
