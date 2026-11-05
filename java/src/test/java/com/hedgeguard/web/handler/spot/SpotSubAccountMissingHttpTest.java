package com.hedgeguard.web.handler.spot;

import com.hedgeguard.service.FakeTx;
import com.hedgeguard.service.SpotService;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-113} 的 <b>HTTP 边界</b>回归：请求体<b>缺 {@code subAccountId}</b>时须得到可判定的
 * 业务拒绝（{@code 10001} + 「子账户必填」），<b>不得</b>落到 {@code 10022} / HTTP 500。
 *
 * <p><b>为何必须有这一层</b>（两类请求体形态导致两种症状，服务层单测只能覆盖 null 一种）：
 * <ul>
 *   <li>{@code ProductionPlanHandler.Request.subAccountId} 为 {@link Integer} ⇒ JSON 缺字段即
 *       {@code null} ⇒ 原 {@code requireSubAccount(int)} <b>拆箱 NPE ⇒ 10022 / HTTP 500</b>（本单缺陷主症状）；</li>
 *   <li>{@code SpotContractHandler.PurchaseRequest.subAccountId} 为基本类型 {@code int} ⇒ JSON 缺字段即
 *       {@code 0} ⇒ 原提示语「子账户必须为真实子账户」，不含 {@code FR-SPOT-1} 验收 2 逐字要求的
 *       「子账户必填」。</li>
 * </ul>
 * 两处现已同口径收敛到 {@link ErrorCode#PARAM_INVALID}（{@code 10001} / HTTP 400）。
 *
 * <p>完全离线（{@code NFR-TEST-2}）：假事务模板 + {@code null} DAO（被拒时零写入）。
 */
class SpotSubAccountMissingHttpTest {

    /** 操作者 ID（会话缺位时 {@code currentUserId()} 为 0，不影响本用例判定）。 */
    private static final long OPERATOR = 1L;

    @Test
    @DisplayName("T-113 HTTP：POST /spot/production 缺 subAccountId ⇒ 10001「子账户必填」（原 NPE ⇒ 500）")
    void productionPlanMissingSubAccountIs400Not500() {
        ProductionPlanHandler handler = new ProductionPlanHandler(service());
        // 除 subAccountId 外字段齐备且合法：确保被拒只因"没填子账户"
        String body = "{\"planNo\":\"PP-T113\",\"planPeriodStart\":\"2026-01-01\","
                + "\"planPeriodEnd\":\"2026-03-31\",\"lines\":[{\"lineType\":\"FINISHED\","
                + "\"productId\":5,\"quantity\":\"10\",\"unit\":\"TON\"}]}";

        BizException e = assertThrows(BizException.class, () -> handler.handle(ctx(
                ProductionPlanHandler.PATH, body)));
        assertSubAccountRequired(e, "POST /spot/production");
    }

    @Test
    @DisplayName("T-113 HTTP：POST /spot/purchase 缺 subAccountId ⇒ 10001「子账户必填」（原提示缺该字样）")
    void purchaseContractMissingSubAccountIs400() {
        SpotContractHandler handler = new SpotContractHandler(service(), true);
        String body = "{\"contractNo\":\"PC-T113\",\"spotCategoryCode\":\"CAT-CU\","
                + "\"totalQty\":\"100\",\"contractStartDate\":\"2026-01-01\","
                + "\"contractEndDate\":\"2026-12-31\"}";

        BizException e = assertThrows(BizException.class, () -> handler.handle(ctx(
                SpotContractHandler.PATH_PURCHASE, body)));
        assertSubAccountRequired(e, "POST /spot/purchase");
    }

    // ============================================================ 辅助

    private static void assertSubAccountRequired(BizException e, String endpoint) {
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode(),
                endpoint + "：缺 subAccountId 属参数校验失败（10001）");
        assertEquals(10001, e.errorCode().code(), endpoint + "：业务码须为 10001");
        assertEquals(400, e.errorCode().httpStatus(), endpoint + "：对外表现须为 HTTP 400（非 500）");
        assertNotEquals(ErrorCode.INTERNAL_ERROR, e.errorCode(),
                endpoint + "：不得归为内部错误 10022：实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("子账户必填"),
                endpoint + "：提示语须含「子账户必填」（FR-SPOT-1 验收 2），实际=" + e.getMessage());
    }

    /** 被测服务：DAO 全 {@code null}（被拒时零写入）；可见性入口 {@code null} 也不得被先行触达。 */
    private static SpotService service() {
        return new SpotService(FakeTx.newTemplate(), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private static RequestContext ctx(String path, String body) {
        return new RequestContext("POST", path, null, Collections.emptyMap(), body, "HGTEST",
                "127.0.0.1");
    }
}
