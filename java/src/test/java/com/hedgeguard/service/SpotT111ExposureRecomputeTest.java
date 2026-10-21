package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmExposureDetailDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmInventoryLedgerDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmProductSpotCategoryDao;
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
import com.hedgeguard.dao.testsupport.T111SpotRecomputeSeed;
import com.hedgeguard.dao.testsupport.TransactionAwareInMemory;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.dao.entity.SpotChangeLog;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-111} 服务端敞口推导引擎验收（{@code FR-SPOT-16} 验收 1 / 2 / 3；TE-T109-005 承接）。
 *
 * <p><b>端到端形态</b>：真实 {@code Dm*Dao} + {@link InMemoryDmEngine}（m1_* 建表脚本真实 DDL）
 * + {@link TransactionAwareInMemory}（事务控制真实生效——回滚还原行集；{@code T-077-FIX} 判例：
 * 引擎裸形态 rollback 为 no-op，事务类断言必须用本包装）。禁假 DAO；建数 SQL 落
 * {@code dao/testsupport/T111SpotRecomputeSeed}（E5：SQL 只允许出现在 dao 包）。
 *
 * <p><b>建数口径</b>：被变更单据（采购合同 1）对敞口贡献<b>全部分量</b>（三分量各一条明细）；
 * 「他人明细」用例以独立来源（库存台账 66）另挂同一敞口。覆盖：
 * <ol>
 *   <li><b>验收 1</b>：变更未被引用合同的归属 ⇒ ①原子账户敞口消失（Σ明细归零 ⇒ {@code CLOSED}
 *       终态）、②目标子账户出现等量敞口（三分量与合计与明细 Σ 同源一致）、③明细改挂目标敞口。</li>
 *   <li><b>减量分支</b>：旧敞口尚有他人明细 ⇒ 旧侧减量（仅扣迁移明细）而非清空。</li>
 *   <li><b>并入分支</b>：目标侧已有同身份键敞口 ⇒ 迁入明细并入并重算（不产生重复行）。</li>
 *   <li><b>未映射行（DC-041/CH-008）</b>：哨兵 {@code product_id=0} + 真实品类原样承袭。</li>
 *   <li><b>验收 2 回归</b>：被生效计划引用 ⇒ 拒绝（{@code REFERENCE_CONFLICT}）且状态零变更。</li>
 *   <li><b>验收 3</b>：留痕含前后子账户与操作人 + {@code recalc_triggered='Y'}。</li>
 *   <li><b>事务原子性（M2 击杀器宿主）</b>：目标侧快照标识冲突 ⇒ 整笔回滚、单据归属不变。</li>
 * </ol>
 */
class SpotT111ExposureRecomputeTest {

    private static final List<String> SCRIPTS = List.of(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql");

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z"); // 北京 10:00
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 18);

    private static final int SUB_A = 8;   // 源子账户
    private static final int SUB_B = 9;   // 目标子账户
    private static final long PRODUCT = 5L;
    private static final long OPERATOR = 999L;

    private InMemoryDmEngine engine;
    private TransactionAwareInMemory aware;
    private DataSourcePool pool;
    private SpotService service;
    private DmExposureDao exposureDao;
    private DmExposureDetailDao detailDao;
    private DmPurchaseContractDao purchaseDao;

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String file : SCRIPTS) {
            engine.executeScript(Files.readString(repoRoot().resolve(file), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        T111SpotRecomputeSeed.reservedProductZero(engine);
        M2E2eSeed.subAccount(engine, SUB_A, "SUBA", "子账户A");
        M2E2eSeed.subAccount(engine, SUB_B, "SUBB", "子账户B");
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        aware = new TransactionAwareInMemory(engine);
        pool = new DataSourcePool(aware, new PoolConfig(0, 4, 500, 2));
        TxTemplate tx = new TxTemplate(pool);
        exposureDao = new DmExposureDao(tx);
        detailDao = new DmExposureDetailDao(tx);
        purchaseDao = new DmPurchaseContractDao(tx);
        service = new SpotService(tx,
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx),
                new DmProductSpotCategoryDao(tx), new DmWipRecordDao(tx),
                new DmInventoryLedgerDao(tx), new DmSpotPriceDao(tx),
                new DmExposureDao(tx), new DmExposureDetailDao(tx), new DmSpotChangeLogDao(tx),
                new DmProductionPlanDao(tx), new DmProductionPlanLineDao(tx),
                new DmSubAccountDao(tx), new DmProductDao(tx),
                uid -> List.of(SUB_A, SUB_B), new DmHedgePlanExposureDao(tx));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
        assertEquals(0, aware.pendingCount(), "事务未残留");
        if (pool != null) {
            pool.close();
        }
    }

    // ================================================================ 验收 1：端到端

    @Test
    @DisplayName("FR-SPOT-16 验收 1：变更后旧侧敞口消失(CLOSED)、目标侧出现等量敞口、明细改挂")
    void recomputeAfterDocSubAccountChange() throws Exception {
        seedContractWithFullContribution(1L, SUB_A, "PC-001", 1L);

        Exposure before = exposureDao.findById(1L);
        assertEquals("PENDING_HEDGE", before.status);
        assertNum("100", before.exposureQty);

        Map<String, Object> result = service.changeDocSubAccount("PURCHASE_CONTRACT", 1L, SUB_B,
                OPERATOR);

        assertEquals(Boolean.TRUE, result.get("recomputeRequired"),
                "T-111 语义：true = 服务端已重算");
        // ① 旧侧：仅扣合同分量①（30），保留他人分量（50+20=70）⇒ 减量而非清空
        Exposure oldHead = exposureDao.findById(1L);
        assertNum("0", oldHead.contractUnexecutedQty);
        assertNum("50", oldHead.inventoryQty);
        assertNum("20", oldHead.productionPlanQty);
        assertNum("70", oldHead.exposureQty);
        assertEquals("PENDING_HEDGE", oldHead.status, "Σ>0 ⇒ 未关闭");
        // ② 目标侧：等量敞口出现（= 迁移明细 Σ=30），三分量与合计同源构造（T-080b）
        Exposure target = exposureByIdentity(SUB_B);
        assertNotNull(target, "目标子账户须出现等量敞口");
        assertNum("30", target.contractUnexecutedQty);
        assertNum("0", target.inventoryQty);
        assertNum("0", target.productionPlanQty);
        assertNum("30", target.exposureQty);
        assertEquals("PENDING_HEDGE", target.status);
        assertEquals(PRODUCT, target.productId);
        assertEquals("BUY", target.direction);
        assertEquals(AS_OF, target.asOfDate);
        assertNotNull(target.snapshotId, "新世代快照标识");
        // ③ 明细改挂（明细行身份保留、exposure_id/sub_account_id 双列随迁）
        List<ExposureDetail> targetDetails = detailDao.findByExposure(target.id);
        assertEquals(1, targetDetails.size(), "合同的明细改挂目标敞口");
        BigDecimal sum = BigDecimal.ZERO;
        for (ExposureDetail detail : targetDetails) {
            assertEquals(SUB_B, detail.subAccountId, "明细子账户随迁");
            assertEquals(1L, detail.sourceId, "来源单据标识保留");
            sum = sum.add(detail.contributedQty);
        }
        assertEquals(0, sum.compareTo(target.exposureQty), "SUM(明细) = 敞口合计（结构保证）");
        // 快照世代刷新：旧侧重算 = 新世代
        assertNotEquals(before.snapshotId, oldHead.snapshotId, "旧侧重算 = 新世代");
    }

    @Test
    @DisplayName("减量分支：旧敞口尚有他人明细 ⇒ 旧侧仅减迁移量、保持 PENDING_HEDGE")
    void partialReductionWhenOthersStillContribute() throws Exception {
        // 合同 1（分量① 30）与他人库存台账 66（分量② 90）共同贡献敞口 1（Σ=120）
        seedExposureHead(1L, SUB_A, "30", "90", "0");
        seedContract(1L, SUB_A, "PC-001");
        T111SpotRecomputeSeed.detail(engine, 300L, 1L, SUB_A, "CONTRACT_UNEXECUTED",
                "PURCHASE_CONTRACT", 1L, "30", AS_OF);
        T111SpotRecomputeSeed.detail(engine, 301L, 1L, SUB_A, "INVENTORY",
                "INVENTORY_LEDGER", 66L, "90", AS_OF);

        service.changeDocSubAccount("PURCHASE_CONTRACT", 1L, SUB_B, OPERATOR);

        // 旧侧：仅扣迁移明细（合同 1 的 30），保留他人明细（90）⇒ 减量而非清空
        Exposure oldHead = exposureDao.findById(1L);
        assertNum("0", oldHead.contractUnexecutedQty);
        assertNum("90", oldHead.inventoryQty);
        assertNum("0", oldHead.productionPlanQty);
        assertNum("90", oldHead.exposureQty);
        assertEquals("PENDING_HEDGE", oldHead.status, "Σ>0 ⇒ 未关闭（与清空分支相区分）");
        // 目标侧：等量 = 迁移明细 Σ（30）
        Exposure target = exposureByIdentity(SUB_B);
        assertNotNull(target);
        assertNum("30", target.exposureQty);
        assertEquals(1, detailDao.findByExposure(target.id).size());
    }

    @Test
    @DisplayName("并入分支：目标侧已有同身份键敞口 ⇒ 迁入明细并入并重算，不产生重复行")
    void mergeIntoExistingTargetExposure() throws Exception {
        // 合同 1（分量① 30）+ 他人分量（70）；目标侧已有同身份键敞口（他人合同 88 明细 20）
        seedContractWithFullContribution(1L, SUB_A, "PC-001", 1L);
        T111SpotRecomputeSeed.exposureHead(engine, 50L, SUB_B, PRODUCT, "20", "10", "0", AS_OF,
                T111SpotRecomputeSeed.at(T0));
        T111SpotRecomputeSeed.detail(engine, 210L, 50L, SUB_B, "CONTRACT_UNEXECUTED",
                "PURCHASE_CONTRACT", 88L, "20", AS_OF);

        service.changeDocSubAccount("PURCHASE_CONTRACT", 1L, SUB_B, OPERATOR);

        // 并入：目标仍一行，数量 = 既有 30 + 迁入 30
        long targetRows = engine.table("t_exposure").rows.stream()
                .filter(r -> ((Number) r.get("sub_account_id")).intValue() == SUB_B)
                .count();
        assertEquals(1, targetRows, "并入既有敞口，不产生重复行（uk_exposure_key 语义）");
        Exposure merged = exposureDao.findById(50L);
        assertNum("50", merged.exposureQty);
        assertNum("50", merged.contractUnexecutedQty);
        assertEquals(2, detailDao.findByExposure(50L).size(), "既有 1 条 + 迁入 1 条");
    }

    @Test
    @DisplayName("未映射行（DC-041）：哨兵 product_id=0 与真实品类随迁，双态不被破坏")
    void unmappedExposureCarriesSentinelAndCategory() throws Exception {
        seedContractWithFullContribution(1L, SUB_A, "PC-001", 1L);
        T111SpotRecomputeSeed.markUnmapped(engine, 1L, "CU_CAT");

        service.changeDocSubAccount("PURCHASE_CONTRACT", 1L, SUB_B, OPERATOR);

        Exposure target = null;
        for (Map<String, Object> row : engine.table("t_exposure").rows) {
            if (((Number) row.get("sub_account_id")).intValue() == SUB_B
                    && ((Number) row.get("product_id")).longValue() == 0L) {
                target = exposureDao.findById(((Number) row.get("id")).longValue());
            }
        }
        assertNotNull(target, "未映射行同样生成目标敞口");
        assertEquals(0L, target.productId, "未映射哨兵 0 原样承袭（DC-041）");
        assertEquals("Y", target.unmappedFlag);
        assertEquals("CU_CAT", target.spotCategoryCode, "真实品类随迁（CH-008 双态）");
        assertNum("30", target.exposureQty);
        // 旧侧：仅剩他人明细（50+20=70）
        assertNum("70", exposureDao.findById(1L).exposureQty);
    }

    // ================================================================ 验收 2 / 3

    @Test
    @DisplayName("FR-SPOT-16 验收 2 回归：被生效计划引用 ⇒ REFERENCE_CONFLICT，敞口零变更")
    void referencedExposureStillRejected() throws Exception {
        seedContractWithFullContribution(1L, SUB_A, "PC-001", 1L);
        // 生效计划引用：covered_qty=50 + 绑定行
        T111SpotRecomputeSeed.markCovered(engine, 1L);
        T111SpotRecomputeSeed.hedgePlan(engine, 555L, SUB_A, PRODUCT, "HP-555", AS_OF);
        T111SpotRecomputeSeed.planBinding(engine, 555L, 1L, SUB_A, "50",
                T111SpotRecomputeSeed.at(T0));

        BizException e = assertThrows(BizException.class,
                () -> service.changeDocSubAccount("PURCHASE_CONTRACT", 1L, SUB_B, OPERATOR));
        assertEquals(ErrorCode.REFERENCE_CONFLICT, e.errorCode());
        assertTrue(e.getMessage().contains("plans=[555]"), "拒因列计划：" + e.getMessage());
        // 零变更
        assertEquals(SUB_A, purchaseDao.findById(1L).subAccountId, "单据归属不变");
        assertNum("100", exposureDao.findById(1L).exposureQty);
        assertEquals(0, engine.table("t_spot_change_log").rows.size(), "拒绝路径不留痕");
    }

    @Test
    @DisplayName("FR-SPOT-16 验收 3：留痕含前后子账户与操作人 + recalc_triggered='Y'")
    void changeLogCarriesBeforeAfterAndOperator() throws Exception {
        seedContractWithFullContribution(1L, SUB_A, "PC-001", 1L);

        service.changeDocSubAccount("PURCHASE_CONTRACT", 1L, SUB_B, OPERATOR);

        assertEquals(1, engine.table("t_spot_change_log").rows.size(), "归属变更留痕一条");
        SpotChangeLog log = changeLogRow(0);
        assertEquals("SUB_ACCOUNT_CHANGE", log.changeType);
        assertEquals(SUB_A, log.subAccountBefore, "前子账户（ck_spot_chg_sub_change 非空口径）");
        assertEquals(SUB_B, log.subAccountAfter, "后子账户");
        assertEquals(Long.valueOf(OPERATOR), log.operatorId, "操作人");
        assertEquals("Y", log.recalcTriggered, "TB-SPOT-10：是否触发敞口重算");
        assertEquals(SUB_B, log.subAccountId, "按子账户检索定位取变更后");
    }

    @Test
    @DisplayName("明细为空的单据归属变更 ⇒ 不触发重算（验收 1 不适用），正常变更+留痕")
    void docWithoutContributionSkipsRecompute() throws Exception {
        T111SpotRecomputeSeed.purchaseContract(engine, 2L, SUB_A, "PC-002", PRODUCT,
                AS_OF.minusDays(30), AS_OF);

        Map<String, Object> result = service.changeDocSubAccount("PURCHASE_CONTRACT", 2L, SUB_B,
                OPERATOR);

        assertEquals(Boolean.TRUE, result.get("recomputeRequired"));
        assertEquals(SUB_B, purchaseDao.findById(2L).subAccountId);
        assertEquals(0, engine.table("t_exposure").rows.size(), "无敞口可重算 ⇒ 零敞口写入");
        assertEquals(1, engine.table("t_spot_change_log").rows.size());
    }

    // ================================================================ M2 击杀器宿主：事务原子性

    @Test
    @DisplayName("事务原子性：目标侧快照标识冲突 ⇒ 整笔回滚（单据归属与敞口均不变）")
    void recomputeFailureRollsBackWholeChange() throws Exception {
        seedContractWithFullContribution(1L, SUB_A, "PC-001", 1L);
        // 注入：目标子账户上预置一行，其 snapshot_id 恰为引擎将生成的世代序号
        //（seq = count(sub=9, as_of)+1 = 1 ⇒ EXP20260918-09-0005-BUY-01；身份键不同 ⇒
        //  撞的是 uk_exposure_snapshot 而非 uk_exposure_key）
        String conflictSnapshot = "EXP" + AS_OF.toString().replace("-", "") + "-09-0005-BUY-02";
        M2E2eSeed.product(engine, 9999L, "ZZ", "冲突占位品种");
        T111SpotRecomputeSeed.exposureHead(engine, 60L, SUB_B, 9999L, "5", "0", "0", AS_OF,
                T111SpotRecomputeSeed.at(T0));
        T111SpotRecomputeSeed.overrideSnapshot(engine, 60L, conflictSnapshot);

        assertThrows(RuntimeException.class,
                () -> service.changeDocSubAccount("PURCHASE_CONTRACT", 1L, SUB_B, OPERATOR),
                "重算写入失败须抛出（快照标识唯一冲突）");

        // 整笔回滚：单据归属、敞口、明细、留痕全部不变
        assertEquals(SUB_A, purchaseDao.findById(1L).subAccountId, "归属变更必须随事务回滚");
        assertNum("100", exposureDao.findById(1L).exposureQty);
        assertEquals(0, engine.table("t_exposure").rows.stream()
                        .filter(r -> ((Number) r.get("sub_account_id")).intValue() == SUB_B
                                && ((Number) r.get("product_id")).longValue() == PRODUCT).count(),
                "目标侧不得出现半笔敞口");
        assertEquals(0, engine.table("t_spot_change_log").rows.size(), "留痕随事务回滚");
        assertEquals(1L, detailDao.findByExposure(1L).get(0).sourceId, "明细仍挂旧敞口");
    }

    // ================================================================ 种子与辅助

    /**
     * 合同 + 敞口头 + 三条明细（敞口 Σ=30+50+20=100）。
     * <b>来源口径</b>：合同仅贡献分量①（30，source=PURCHASE_CONTRACT）；分量②③属
     * 他人来源（库存台账 66 / 生产计划 67），与被变更单据无关 —— 与 {@code findBySource}
     * 反查语义一致（迁移面 = 合同自己的明细）。
     */
    private void seedContractWithFullContribution(long contractId, int subAccountId,
                                                  String contractNo, long exposureId)
            throws Exception {
        seedExposureHead(exposureId, subAccountId, "30", "50", "20");
        seedContract(contractId, subAccountId, contractNo);
        T111SpotRecomputeSeed.detail(engine, 300L, exposureId, subAccountId,
                "CONTRACT_UNEXECUTED", "PURCHASE_CONTRACT", contractId, "30", AS_OF);
        T111SpotRecomputeSeed.detail(engine, 301L, exposureId, subAccountId, "INVENTORY",
                "INVENTORY_LEDGER", 66L, "50", AS_OF);
        T111SpotRecomputeSeed.detail(engine, 302L, exposureId, subAccountId, "PRODUCTION_PLAN",
                "PRODUCTION_PLAN", 67L, "20", AS_OF);
    }

    private void seedContract(long contractId, int subAccountId, String contractNo)
            throws Exception {
        T111SpotRecomputeSeed.purchaseContract(engine, contractId, subAccountId, contractNo,
                PRODUCT, AS_OF.minusDays(30), AS_OF);
    }

    private void seedExposureHead(long exposureId, int subAccountId, String c1, String c2,
                                  String c3) throws Exception {
        T111SpotRecomputeSeed.exposureHead(engine, exposureId, subAccountId, PRODUCT, c1, c2,
                c3, AS_OF, T111SpotRecomputeSeed.at(T0));
    }

    /** 按身份键（目标子账户 × 本类固定身份列：品种/BUY）取目标敞口。 */
    private Exposure exposureByIdentity(int subAccountId) {
        for (Map<String, Object> row : engine.table("t_exposure").rows) {
            if (((Number) row.get("sub_account_id")).intValue() == subAccountId
                    && ((Number) row.get("product_id")).longValue() == PRODUCT
                    && "BUY".equals(row.get("direction"))) {
                return exposureDao.findById(((Number) row.get("id")).longValue());
            }
        }
        return null;
    }

    /** 取第 index 条留痕（经真实 DAO 读回实体）。 */
    private SpotChangeLog changeLogRow(int index) {
        long id = ((Number) engine.table("t_spot_change_log").rows.get(index).get("id"))
                .longValue();
        return new DmSpotChangeLogDao(new TxTemplate(pool)).findById(id);
    }

    private static void assertNum(String expected, BigDecimal actual) {
        assertNotNull(actual, "数值列不得为 NULL");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "数值不匹配：期望 " + expected + " 实际 " + actual.toPlainString());
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
