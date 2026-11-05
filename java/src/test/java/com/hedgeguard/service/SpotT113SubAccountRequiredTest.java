package com.hedgeguard.service;

import com.hedgeguard.dao.entity.InventoryLedger;
import com.hedgeguard.dao.entity.ProductionPlan;
import com.hedgeguard.dao.entity.ProductionPlanLine;
import com.hedgeguard.dao.entity.PurchaseContract;
import com.hedgeguard.dao.entity.SalesContract;
import com.hedgeguard.dao.entity.WipRecord;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-113}：SPOT 现货单据创建在 {@code subAccountId} <b>缺省</b>时的错误呈现。
 *
 * <p><b>缺陷</b>（{@code te-verify-t110} 复核实测）：实体 {@code subAccountId} 为 {@code Integer}，
 * JSON 缺该字段即 {@code null}；原 {@code requireSubAccount(int)} 的 {@code int} 形参在调用点
 * <b>自动拆箱 ⇒ NPE ⇒ 经 ErrorMapper 归为 {@code 10022} / HTTP 500</b>，而
 * {@code FR-SPOT-1} 验收 2（{@code SPOT.md:101}）要求「未选择子账户即提交被拒绝，提示"子账户必填"」。
 *
 * <p><b>本用例锁定四件事</b>：
 * <ol>
 *   <li>5 个 {@code create*} <b>全部</b>在 {@code subAccountId = null} 时抛 {@link BizException} 而非 NPE；
 *       错误码为 {@link ErrorCode#PARAM_INVALID}（{@code 10001} / HTTP 400），<b>不得</b>为
 *       {@code 10022}（内部错误）或 {@code 10004}（那是"无权访问他人子账户"的数据权限语义，与"没填"无关）；</li>
 *   <li>提示语含「子账户必填」（需求逐字要求）；</li>
 *   <li>判定发生在<b>子账户可见性/写面断言之前</b>（用例注入的 {@link SubAccountVisibility} 一旦被触达即抛
 *       {@link IllegalStateException}；缺失入参属调用方可自行修复的报错，不应先落成"权限不足"）；</li>
 *   <li>被拒时<b>零写入</b>（DAO 全为 {@code null}：任何继续执行的路径都会 NPE 而令用例转红）。</li>
 * </ol>
 *
 * <p><b>反退化</b>：把 {@code requireSubAccount(Integer)} 改回 {@code int}（撤销本单修复），
 * 本类 5 个用例在调用点即抛 {@code NullPointerException} ⇒ {@code assertThrows(BizException.class)}
 * 失败 ⇒ 必红。
 *
 * <p>其余同源说明：{@code putSpotPrice} / {@code computeExposure} 与 5 个 {@code create*} 共用同一
 * {@code requireSubAccount}（共 7 处调用），修复为单点，行为同源收敛，不另立重复用例。
 *
 * <p>完全离线（{@code NFR-TEST-2}）：内存假事务模板，不含 SQL / 连接。
 */
class SpotT113SubAccountRequiredTest {

    // ============================================================ 一、缺省（null）⇒ 10001 + 「子账户必填」

    @Test
    @DisplayName("T-113：createPurchaseContract 缺 subAccountId ⇒ 10001 子账户必填（不 NPE / 不 500 / 不 10022）")
    void createPurchaseContractRequiresSubAccount() {
        PurchaseContract contract = purchaseContract();
        contract.subAccountId = null;   // JSON 缺字段 ⇒ null（原 int 形参在此拆箱 ⇒ NPE ⇒ 10022/500）

        BizException e = assertThrows(BizException.class,
                () -> service().createPurchaseContract(contract, 1L));
        assertSubAccountRequired(e, "createPurchaseContract");
    }

    @Test
    @DisplayName("T-113：createSalesContract 缺 subAccountId ⇒ 10001 子账户必填（不 NPE / 不 500 / 不 10022）")
    void createSalesContractRequiresSubAccount() {
        SalesContract contract = salesContract();
        contract.subAccountId = null;

        BizException e = assertThrows(BizException.class,
                () -> service().createSalesContract(contract, 1L));
        assertSubAccountRequired(e, "createSalesContract");
    }

    @Test
    @DisplayName("T-113：createInventory 缺 subAccountId ⇒ 10001 子账户必填（不 NPE / 不 500 / 不 10022）")
    void createInventoryRequiresSubAccount() {
        InventoryLedger ledger = inventoryLedger();
        ledger.subAccountId = null;

        BizException e = assertThrows(BizException.class,
                () -> service().createInventory(ledger, 1L));
        assertSubAccountRequired(e, "createInventory");
    }

    @Test
    @DisplayName("T-113：createWip 缺 subAccountId ⇒ 10001 子账户必填（不 NPE / 不 500 / 不 10022）")
    void createWipRequiresSubAccount() {
        WipRecord record = wipRecord();
        record.subAccountId = null;

        BizException e = assertThrows(BizException.class, () -> service().createWip(record, 1L));
        assertSubAccountRequired(e, "createWip");
    }

    @Test
    @DisplayName("T-113：createProductionPlan 缺 subAccountId ⇒ 10001 子账户必填（不 NPE / 不 500 / 不 10022）")
    void createProductionPlanRequiresSubAccount() {
        ProductionPlan plan = productionPlan();
        plan.subAccountId = null;

        BizException e = assertThrows(BizException.class,
                () -> service().createProductionPlan(plan, planLines(), 1L));
        assertSubAccountRequired(e, "createProductionPlan");
    }

    // ============================================================ 二、0 值（handler 的 int 缺省）同口径

    @Test
    @DisplayName("T-113：subAccountId=0（handler 请求体 int 缺省值）同样提示「子账户必填」")
    void zeroSubAccountAlsoSaysRequired() {
        // 多数 spot handler 的请求体字段为基本类型 int：JSON 缺字段 ⇒ 0 而非 null（不经 NPE，
        // 但原提示语为「子账户必须为真实子账户」，不含需求逐字要求的「子账户必填」）。
        PurchaseContract contract = purchaseContract();
        contract.subAccountId = 0;

        BizException e = assertThrows(BizException.class,
                () -> service().createPurchaseContract(contract, 1L));
        assertSubAccountRequired(e, "createPurchaseContract(subAccountId=0)");
    }

    // ============================================================ 辅助

    /**
     * 缺省 {@code subAccountId} 的统一判据。
     *
     * <p>类型断言本身即"不得 NPE"的自证：NPE 是 {@link RuntimeException} 但非 {@link BizException}，
     * {@code assertThrows(BizException.class, ...)} 会直接失败。
     */
    private static void assertSubAccountRequired(BizException e, String method) {
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode(),
                method + "：缺省 subAccountId 属参数校验失败（10001），不得为其它语义");
        assertEquals(10001, e.errorCode().code(), method + "：业务码须为 10001");
        assertEquals(400, e.errorCode().httpStatus(), method + "：对外表现须为 HTTP 400（非 500）");
        assertNotEquals(ErrorCode.INTERNAL_ERROR, e.errorCode(),
                method + "：不得归为内部错误 10022（原 NPE 症状）：实际=" + e.getMessage());
        assertNotEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode(),
                method + "：不得误用 10004（那是「无权访问他人子账户」，与「没填」无关）");
        assertTrue(e.getMessage().contains("子账户必填"),
                method + "：提示语须含「子账户必填」（FR-SPOT-1 验收 2），实际=" + e.getMessage());
    }

    /**
     * 构造被测服务：DAO 全 {@code null}（被拒时零写入，任何继续执行都会 NPE 令用例转红）；
     * 可见性入口<b>一被触达即抛</b> —— 锁定"入参校验先于权限断言"的调用顺序。
     */
    private static SpotService service() {
        SubAccountVisibility visibilityNotReached = userId -> {
            throw new IllegalStateException("缺省 subAccountId 须在可见性/写面断言之前拒绝（T-113）："
                    + "userId=" + userId + "；复现=核对 create* 中 requireSubAccount 的调用位置");
        };
        return new SpotService(FakeTx.newTemplate(), null, null, null, null, null, null, null, null,
                null, null, null, null, null, visibilityNotReached, null);
    }

    /** 采购合同：除 {@code subAccountId} 外全部合法（确保被拒只因"没填子账户"）。 */
    private static PurchaseContract purchaseContract() {
        PurchaseContract c = new PurchaseContract();
        c.contractNo = "PC-T113";
        c.spotCategoryCode = "CAT-CU";
        c.totalQty = new BigDecimal("100");
        c.contractStartDate = LocalDate.of(2026, 1, 1);
        c.contractEndDate = LocalDate.of(2026, 12, 31);
        return c;
    }

    /** 销售合同：其余字段合法。 */
    private static SalesContract salesContract() {
        SalesContract c = new SalesContract();
        c.contractNo = "SC-T113";
        c.spotCategoryCode = "CAT-CU";
        c.totalQty = new BigDecimal("80");
        c.contractStartDate = LocalDate.of(2026, 1, 1);
        c.contractEndDate = LocalDate.of(2026, 12, 31);
        return c;
    }

    /** 库存行：其余字段合法。 */
    private static InventoryLedger inventoryLedger() {
        InventoryLedger l = new InventoryLedger();
        l.productId = 5L;
        l.inventoryType = "RAW_MATERIAL";
        l.quantity = new BigDecimal("50");
        l.negativeConfirmed = "N";
        l.unit = "TON";
        l.recordDate = LocalDate.of(2026, 9, 20);
        l.exposureDirection = "BUY";
        return l;
    }

    /** 在制品行：其余字段合法。 */
    private static WipRecord wipRecord() {
        WipRecord r = new WipRecord();
        r.productId = 5L;
        r.wipQty = new BigDecimal("20");
        r.completionRatio = new BigDecimal("0.5");
        r.exposureDirection = "BUY";
        r.recordDate = LocalDate.of(2026, 9, 20);
        r.unit = "TON";
        return r;
    }

    /** 生产计划头：其余字段合法。 */
    private static ProductionPlan productionPlan() {
        ProductionPlan p = new ProductionPlan();
        p.planNo = "PP-T113";
        p.planPeriodStart = LocalDate.of(2026, 1, 1);
        p.planPeriodEnd = LocalDate.of(2026, 3, 31);
        return p;
    }

    /** 生产计划行（至少一行；产成品）。 */
    private static List<ProductionPlanLine> planLines() {
        ProductionPlanLine line = new ProductionPlanLine();
        line.lineType = "FINISHED";
        line.productId = 5L;
        line.quantity = new BigDecimal("10");
        line.unit = "TON";
        return Collections.singletonList(line);
    }
}
