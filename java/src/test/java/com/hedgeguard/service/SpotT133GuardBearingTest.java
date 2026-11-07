package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmExposureDetailDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmInventoryLedgerDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmProductSpotCategoryDao;
import com.hedgeguard.dao.impl.DmProductUnitDao;
import com.hedgeguard.dao.impl.DmProductionPlanDao;
import com.hedgeguard.dao.impl.DmProductionPlanLineDao;
import com.hedgeguard.dao.impl.DmPurchaseContractDao;
import com.hedgeguard.dao.impl.DmSalesContractDao;
import com.hedgeguard.dao.impl.DmSpotChangeLogDao;
import com.hedgeguard.dao.impl.DmSpotPriceDao;
import com.hedgeguard.dao.impl.DmSubAccountDao;
import com.hedgeguard.dao.impl.DmWipRecordDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.T133SpotGuardSeed;
import com.hedgeguard.dao.testsupport.TransactionAwareInMemory;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.dao.entity.SpotPrice;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-133} 零覆盖守卫<b>承重</b>验收（{@code FR-SPOT-19} 现货价导出 / {@code R-SPOT-3} 覆盖须确认 /
 * {@code FR-SPOT-16} 单据改挂）。
 *
 * <p><b>存在理由</b>：{@code T-023} 独立复核以 jacoco 线级数据筛出 {@code SpotService} 的
 * <b>8 个 {@code ci==0}（从未求值）的 {@code if} 守卫</b>——{@code :1135}（覆盖写入乐观锁）、
 * {@code :1179}/{@code :1183}/{@code :1184}/{@code :1190}/{@code :1191}（导出筛选拼接）、
 * {@code :1765}/{@code :1774}（单据改挂乐观锁）。本类为其中<b>核心规则</b>者补用例，
 * 使「关掉该守卫 ⇒ 必红」（承重自证；见 {@code T-133} 报告 §3/§4）。
 *
 * <p><b>形态</b>：真实 {@code Dm*Dao} + {@link InMemoryDmEngine}（{@code m1_*} 真实 DDL）+
 * {@link TransactionAwareInMemory}（回滚真实生效）；建数 SQL 落 {@code dao/testsupport/T133SpotGuardSeed}
 * （{@code E5}：SQL 只允许出现在 {@code dao} 包）。
 */
class SpotT133GuardBearingTest {

    private static final List<String> SCRIPTS = List.of(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql");

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z"); // 北京 10:00
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 10, 0);

    private static final int SUB_A = 8;
    private static final int SUB_B = 9;
    private static final long PRODUCT = 5L;
    private static final long PRODUCT_B = 6L;
    private static final long OPERATOR = 999L;

    private InMemoryDmEngine engine;
    private TransactionAwareInMemory aware;
    private DataSourcePool pool;
    private SpotService service;
    private DmSpotPriceDao priceDao;
    private DmSalesContractDao salesDao;
    private DmProductionPlanDao planDao;

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String file : SCRIPTS) {
            engine.executeScript(Files.readString(repoRoot().resolve(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB_A, "SUBA", "子账户A");
        M2E2eSeed.subAccount(engine, SUB_B, "SUBB", "子账户B");
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        M2E2eSeed.product(engine, PRODUCT_B, "AL", "铝");
        aware = new TransactionAwareInMemory(engine);
        pool = new DataSourcePool(aware, new PoolConfig(0, 4, 500, 2));
        TxTemplate tx = new TxTemplate(pool);
        priceDao = new DmSpotPriceDao(tx);
        salesDao = new DmSalesContractDao(tx);
        planDao = new DmProductionPlanDao(tx);
        service = new SpotService(tx,
                new DmPurchaseContractDao(tx), salesDao,
                new DmProductSpotCategoryDao(tx), new DmWipRecordDao(tx),
                new DmInventoryLedgerDao(tx), priceDao,
                new DmExposureDao(tx), new DmExposureDetailDao(tx), new DmSpotChangeLogDao(tx),
                planDao, new DmProductionPlanLineDao(tx),
                new DmSubAccountDao(tx), new DmProductDao(tx),
                uid -> List.of(SUB_A, SUB_B), new DmHedgePlanExposureDao(tx),
                new DmProductUnitDao(tx)); // 与生产 Bootstrap 同构（BUG-006 导入补默认单位依赖）
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
        assertEquals(0, aware.pendingCount(), "事务未残留");
        if (pool != null) {
            pool.close();
        }
    }

    // ============================================================ 一、现货价导出（FR-SPOT-19）

    @Test
    @DisplayName("FR-SPOT-19：无筛选 ⇒ 全量按 price_date 升序 + 表头逐字 + CSV 单元格转义")
    void exportWithoutFiltersReturnsAllRowsAscending() throws Exception {
        seedPrice(1L, PRODUCT, "2026-09-18", "4100", "手工录入");
        seedPrice(2L, PRODUCT, "2026-09-17", "4000", "手工录入");
        seedPrice(3L, PRODUCT_B, "2026-09-20", "9000", "含,逗号与\"引号\"");

        List<String> lines = service.exportSpotPricesCsv(null, null, null);

        assertEquals(4, lines.size(), "表头 + 3 行数据（全量、不截断）");
        assertEquals("product_id,price_date,spot_price,source_type,source_desc,import_file_name,"
                + "price_unit,operator_id,entry_time", lines.get(0), "表头须逐字一致（FR-SPOT-19）");
        assertRow(lines, 1, PRODUCT, "2026-09-17");
        assertRow(lines, 2, PRODUCT, "2026-09-18");
        assertRow(lines, 3, PRODUCT_B, "2026-09-20");
        assertTrue(lines.get(3).contains("\"含,逗号与\"\"引号\"\""),
                "含逗号/引号的单元格须加引号并双写转义：实际=" + lines.get(3));
    }

    @Test
    @DisplayName("FR-SPOT-19：productId 筛选 ⇒ 仅该品种（其余品种不得混入）")
    void exportFiltersByProduct() throws Exception {
        seedPrice(1L, PRODUCT, "2026-09-18", "4100", "手工录入");
        seedPrice(2L, PRODUCT_B, "2026-09-20", "9000", "手工录入");

        List<String> lines = service.exportSpotPricesCsv(PRODUCT_B, null, null);

        assertEquals(2, lines.size(), "仅产品 6 的 1 行 + 表头");
        assertRow(lines, 1, PRODUCT_B, "2026-09-20");
    }

    @Test
    @DisplayName("FR-SPOT-19：productId + from + to 组合 ⇒ AND 拼接不破（闭区间）")
    void exportAppliesCombinedFilters() throws Exception {
        seedPrice(1L, PRODUCT, "2026-09-17", "4000", "手工录入");
        seedPrice(2L, PRODUCT, "2026-09-18", "4100", "手工录入");
        seedPrice(3L, PRODUCT, "2026-09-19", "4200", "手工录入");

        List<String> lines = service.exportSpotPricesCsv(PRODUCT,
                LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 18));

        assertEquals(2, lines.size(), "仅 2026-09-18 一行 + 表头");
        assertRow(lines, 1, PRODUCT, "2026-09-18");
    }

    @Test
    @DisplayName("FR-SPOT-19：仅 from ⇒ 下界生效（上界缺省不拼空谓词）")
    void exportFiltersByFromOnly() throws Exception {
        seedPrice(1L, PRODUCT, "2026-09-18", "4100", "手工录入");
        seedPrice(2L, PRODUCT, "2026-09-19", "4200", "手工录入");

        List<String> lines = service.exportSpotPricesCsv(null, LocalDate.of(2026, 9, 19), null);

        assertEquals(2, lines.size(), "仅 2026-09-19 一行 + 表头");
        assertRow(lines, 1, PRODUCT, "2026-09-19");
    }

    @Test
    @DisplayName("FR-SPOT-19：仅 to ⇒ 上界生效，且首谓词不得被「 AND 」前缀破坏")
    void exportFiltersByToOnly() throws Exception {
        seedPrice(1L, PRODUCT, "2026-09-18", "4100", "手工录入");
        seedPrice(2L, PRODUCT, "2026-09-19", "4200", "手工录入");

        List<String> lines = service.exportSpotPricesCsv(null, null, LocalDate.of(2026, 9, 18));

        assertEquals(2, lines.size(), "仅 2026-09-18 一行 + 表头");
        assertRow(lines, 1, PRODUCT, "2026-09-18");
    }

    @Test
    @DisplayName("FR-SPOT-19：无匹配（from > to 不校验区间顺序）⇒ 仍返回文件，仅含表头")
    void exportEmptyResultKeepsHeaderOnly() throws Exception {
        seedPrice(1L, PRODUCT, "2026-09-18", "4100", "手工录入");

        List<String> lines = service.exportSpotPricesCsv(null,
                LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 19));

        assertEquals(1, lines.size(), "无匹配数据时仍返回文件（仅表头）——FR-SPOT-19 已裁定保留现状");
        assertEquals("product_id,price_date,spot_price,source_type,source_desc,import_file_name,"
                + "price_unit,operator_id,entry_time", lines.get(0));
    }

    // ============================================================ 二、导入覆盖（R-SPOT-3）

    @Test
    @DisplayName("R-SPOT-3：未确认覆盖 ⇒ 10009 整体拒绝（零写入）；确认后覆盖并留原值")
    void importRefusesSilentOverwriteThenOverwritesWhenConfirmed() throws Exception {
        seedPrice(1L, PRODUCT, "2026-09-18", "4100", "手工录入");
        // 仅取「同期已存在」一行：新增行支路另有缺陷（price_unit NOT NULL 未赋值，见 BUG 单），
        // 在缺陷修复前不得写成通过期望（T-133 报告 §5）。
        List<String> lines = List.of("CU,2026-09-18,4500");

        BizException refused = assertThrows(BizException.class,
                () -> service.importSpotPrices(lines, SUB_A, "BATCH_PASTE", null, false, OPERATOR));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, refused.errorCode(), "不得静默覆盖（10009）");
        assertTrue(refused.getMessage().contains("R-SPOT-3"), "拒因须点名 R-SPOT-3：实际="
                + refused.getMessage());
        assertEquals(1, engine.table("t_spot_price").rows.size(), "整体拒绝 ⇒ zero-write");

        Map<String, Object> summary = service.importSpotPrices(lines, SUB_A, "BATCH_PASTE", null,
                true, OPERATOR);
        assertEquals(1, summary.get("updated"), "同期已存在 ⇒ 覆盖 1 行");
        assertEquals(0, summary.get("inserted"), "本次未含新日期行");
        assertEquals(1, summary.get("total"));
        SpotPrice row = priceDao.findGlobalPrice(PRODUCT, LocalDate.of(2026, 9, 18));
        assertNotNull(row);
        assertEquals(0, new BigDecimal("4500").compareTo(row.spotPrice), "覆盖为新值");
        assertEquals(0, new BigDecimal("4100").compareTo(row.overwrittenPrice), "被覆盖原值须留痕");
        assertEquals("Y", row.overwriteConfirmed, "覆盖须标记已确认");
        assertEquals("TON", row.priceUnit, "导入行未给单位 ⇒ 保留既有单位");
    }

    // ============================================================ 三、单据改挂（FR-SPOT-16）

    @Test
    @DisplayName("FR-SPOT-16：销售合同与生产计划的改挂分支须真实落库 + 留痕")
    void changeDocSubAccountMovesSalesContractAndProductionPlan() throws Exception {
        T133SpotGuardSeed.salesContract(engine, 21L, SUB_A, "SC-021", PRODUCT,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31));
        T133SpotGuardSeed.productionPlan(engine, 31L, SUB_A, "PP-031",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        Map<String, Object> sales = service.changeDocSubAccount("SALES_CONTRACT", 21L, SUB_B,
                OPERATOR);
        assertEquals(SUB_A, sales.get("fromSubAccountId"));
        assertEquals(SUB_B, sales.get("toSubAccountId"));
        assertEquals(SUB_B, salesDao.findById(21L).subAccountId, "销售合同归属须落库");

        Map<String, Object> plan = service.changeDocSubAccount("PRODUCTION_PLAN", 31L, SUB_B,
                OPERATOR);
        assertEquals(SUB_A, plan.get("fromSubAccountId"));
        assertEquals(SUB_B, planDao.findById(31L).subAccountId, "生产计划归属须落库");

        long logs = engine.table("t_spot_change_log").rows.stream()
                .filter(r -> "SUB_ACCOUNT_CHANGE".equals(r.get("change_type")))
                .count();
        assertEquals(2, logs, "每次改挂须留痕（FR-SPOT-16 验收 3）");
    }

    // ============================================================ 辅助

    private void seedPrice(long id, long productId, String date, String price, String sourceDesc)
            throws Exception {
        T133SpotGuardSeed.spotPrice(engine, id, SUB_A, productId, LocalDate.parse(date), price,
                "MANUAL", sourceDesc, "TON", NOW);
    }

    private static void assertRow(List<String> lines, int index, long productId, String date) {
        assertTrue(lines.get(index).startsWith(productId + "," + date + ","),
                "第 " + index + " 行应为 product=" + productId + " date=" + date
                        + "：实际=" + lines.get(index));
    }

    private static Path repoRoot() {
        Path path = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根");
    }
}
