package com.hedgeguard.web.handler.fund;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.FundService;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-195}：出入金申请列表 {@code GET /fund/apply-list} 端点用例
 * （{@code FR-FUND-8} 验收 3「未收到回执时状态为『超时待查』，系统可通过查询接口确认最终结果」；
 * {@code te-verify-t055} 披露①承接——超时态资金岗此前无法定位申请 ID）。
 *
 * <p>真实 DAO + InMemory 引擎 + 生产装配 {@code RequestDispatcher} 进程内分发，断言：
 * <ol>
 *   <li>端点已注册（非 404；POST ⇒ 405 只读面）；</li>
 *   <li>分页信封形态（{@code PageData}：items/total/page/size，{@code §4.1.1} 冻结）；</li>
 *   <li>状态筛选：{@code status=TIMEOUT_UNKNOWN} 真下推（超时态定位申请 ID = 本端点验收承载）；</li>
 *   <li>子账户隔离：可见集过滤下推（受限可见 ⇒ 仅可见集；空可见 ⇒ 恒假 fail-closed）+
 *       显式越权筛选 ⇒ {@code 10004}；</li>
 *   <li>时间段筛选：{@code from}/{@code to} 按 {@code created_time} 区间（含当日端点）；</li>
 *   <li>非法状态取值 ⇒ 明确报参错（不静默忽略）；</li>
 *   <li>行视图字段契约（applyNo/direction/amount/status/rejectReason/createdTime）。</li>
 * </ol>
 *
 * <p>WHERE 形态披露：服务侧 {@code listDepositWithdrawApplies} 以占位符原子逐条件拼接
 * （不写 {@code 1 = 1} 恒真字面量——该形态在等价引擎不可求值，见 {@code FundService.listFlow}
 * 同族披露），条件全空时传 {@code null}（{@code AbstractDmDao.findPage} 允许无 WHERE）。
 */
class FundApplyListHandlerTest {

    private static final int SUB_A = 11;
    private static final int SUB_B = 12;
    private static final long OPERATOR = 100L;
    private static final long REVIEWER = 101L;
    private static final long OTHER = 102L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private static final String PATH = "/api/v1/fund/apply-list";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private RequestDispatcher dispatcher;
    private SessionStore sessionStore;
    private Router router;
    private FundService fundService;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : schemaScripts()) {
            engine.executeScript(new String(
                    Files.readAllBytes(repoRoot().resolve(rel)), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB_A, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, SUB_B, "SA-B", "商品子账户B");
        M1E2eSeed.user(engine, OPERATOR, "op1", "操作人1", "NP-OP1");
        M1E2eSeed.user(engine, REVIEWER, "op2", "复核人1", "NP-OP2");
        M1E2eSeed.user(engine, OTHER, "op3", "旁人1", "NP-OP3");
        M1E2eSeed.perm(engine, OPERATOR, SUB_A, "LIMITED", OPERATOR);
        M1E2eSeed.perm(engine, REVIEWER, SUB_A, "LIMITED", OPERATOR);
        M1E2eSeed.perm(engine, REVIEWER, SUB_B, "LIMITED", OPERATOR);
        M1E2eSeed.perm(engine, OTHER, SUB_B, "LIMITED", OPERATOR);

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        fundService = new FundService(tx, uid -> {
            if (uid == OPERATOR) {
                return List.of(SUB_A);
            }
            if (uid == REVIEWER) {
                return List.of(SUB_A, SUB_B);
            }
            if (uid == OTHER) {
                return List.of(); // 空可见集 ⇒ 恒假（fail-closed）
            }
            return List.of();
        }, uid -> false, key -> null,
                new com.hedgeguard.dao.impl.DmFundLedgerDao(tx),
                new com.hedgeguard.dao.impl.DmFundFlowDao(tx),
                new com.hedgeguard.dao.impl.DmDepositWithdrawApplyDao(tx),
                new com.hedgeguard.dao.impl.DmTransferApplyDao(tx),
                new com.hedgeguard.dao.impl.DmCapitalReceiptDao(tx),
                new com.hedgeguard.dao.impl.DmFundReviewRecordDao(tx),
                new com.hedgeguard.dao.impl.DmFundQuotaDao(tx),
                new com.hedgeguard.dao.impl.DmFundQuotaAlertDao(tx),
                new com.hedgeguard.dao.impl.DmFundReconcileDao(tx));

        // 种子：SUB_A 两笔（一笔待复核、一笔超时待查）、SUB_B 一笔（待复核）
        seedApply(SUB_A, "K-A1", FundService.DW_PENDING_REVIEW, "用途说明A1", null);
        seedApply(SUB_A, "K-A2", FundService.DW_TIMEOUT_UNKNOWN, "用途说明A2", "回执超时未知");
        seedApply(SUB_B, "K-B1", FundService.DW_PENDING_REVIEW, "B1-来源", null);

        router = new Router();
        FundRoutes.register(router, fundService);
        sessionStore = new SessionStore.InMemory();
        sessionStore.save(new Session("S-OP", OPERATOR, "op1", 1L, Clocks.now().plusMinutes(30)));
        sessionStore.save(new Session("S-REV", REVIEWER, "op2", 1L, Clocks.now().plusMinutes(30)));
        sessionStore.save(new Session("S-OTH", OTHER, "op3", 1L, Clocks.now().plusMinutes(30)));
        dispatcher = new RequestDispatcher(router, new StaticHandler(repoRoot()),
                new SessionAuthFilter(sessionStore, "HGSESSION"));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("端点已注册：GET 非 404；POST ⇒ 405（只读面）")
    void routeRegisteredAndReadOnly() {
        HttpResult get = call("GET", PATH, "", "S-OP");
        assertNotEquals(404, get.status, "GET /fund/apply-list 须已注册（非 404）");
        HttpResult post = call("POST", PATH, "{}", "S-OP");
        assertEquals(405, post.status, "只读面 POST 须 405（Dispatcher 方法不符兜底）");
    }

    @Test
    @DisplayName("分页信封 + 行视图字段契约（PageData 形态，§4.1.1 冻结）")
    void pageEnvelopeAndViewContract() {
        HttpResult r = call("GET", PATH, "", "S-OP");
        assertEquals(200, r.status);
        assertEquals("application/json; charset=UTF-8", r.contentType);
        String body = new String(r.body, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"total\":2"), "可见集 SUB_A ⇒ total=2（A1+A2）： " + body);
        assertTrue(body.contains("\"applyNo\":\"DW20261006"), "行视图须含申请号（DW<yyyyMMdd><seq>）：" + body);
        assertTrue(body.contains("\"status\":\"PENDING_REVIEW\""), "行视图须含状态");
        assertTrue(body.contains("\"direction\":\"IN\""), "行视图须含方向");
        assertTrue(body.contains("\"rejectReason\""), "行视图须含驳回原因列（键存在）");
        assertTrue(body.contains("\"createdTime\""), "行视图须含创建时间（时间段筛选承载列）");
    }

    @Test
    @DisplayName("FR-FUND-8 验收3：status=TIMEOUT_UNKNOWN 真下推（超时态可定位申请 ID）")
    void timeoutUnknownFilterPushedDown() {
        // REVIEWER 可见 SUB_A+SUB_B：共 3 笔，其中 1 笔 TIMEOUT_UNKNOWN
        HttpResult r = call("GET", PATH, "status=TIMEOUT_UNKNOWN", "S-REV");
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"total\":1"), "超时态筛选 ⇒ 仅 1 笔：" + body);
        assertTrue(body.contains("\"status\":\"TIMEOUT_UNKNOWN\""), "命中行状态=TIMEOUT_UNKNOWN：" + body);
        assertTrue(body.contains("\"subAccountId\":" + SUB_A), "超时行属 SUB_A：" + body);
    }

    @Test
    @DisplayName("子账户筛选：可见集内下推；越权筛选 ⇒ 10004（fail-closed）")
    void subAccountFilterAndIsolation() {
        // REVIEWER 可见 SUB_A + SUB_B：total=3
        HttpResult all = call("GET", PATH, "", "S-REV");
        assertTrue(new String(all.body, StandardCharsets.UTF_8).contains("\"total\":3"),
                "REVIEWER 可见两子账户 ⇒ total=3");
        // 限定 SUB_B ⇒ 1
        HttpResult b = call("GET", PATH, "subAccountId=" + SUB_B, "S-REV");
        String bBody = new String(b.body, StandardCharsets.UTF_8);
        assertTrue(bBody.contains("\"total\":1") && bBody.contains("\"subAccountId\":" + SUB_B),
                "subAccountId=SUB_B ⇒ 仅 SUB_B 行：" + bBody);
        // OPERATOR 越权筛 SUB_B（不可见）⇒ 10004
        BizException denied = assertThrows(BizException.class, () ->
                fundService.listDepositWithdrawApplies(
                        PageQuery.of("1", "20", null, "id", "desc"), null, null, SUB_B, null, OPERATOR));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, denied.errorCode(),
                "越权子账户筛选须 10004（读面断言）");
        // 空可见集 ⇒ 恒假（total=0，绝不退化全可见）
        HttpResult none = call("GET", PATH, "", "S-OTH");
        assertTrue(new String(none.body, StandardCharsets.UTF_8).contains("\"total\":0"),
                "空可见集 ⇒ 恒假（fail-closed）："
                        + new String(none.body, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("时间段筛选：from/to 按 created_time 区间（同日=当日全天）")
    void dateRangeFilter() {
        // 当日 12:00 创建；from=to=当日 ⇒ 含当日全天（from=00:00、to=23:59:59.999999999）⇒ 3 笔
        HttpResult r = call("GET", PATH, "from=" + DAY + "&to=" + DAY, "S-REV");
        assertTrue(new String(r.body, StandardCharsets.UTF_8).contains("\"total\":3"),
                "同日 from=to 覆盖当日全天 ⇒ 3 笔");
        // to 前一日 ⇒ 0 笔（created_time 均为当日）
        HttpResult past = call("GET", PATH, "to=" + DAY.minusDays(1), "S-REV");
        assertTrue(new String(past.body, StandardCharsets.UTF_8).contains("\"total\":0"),
                "截止昨日 ⇒ 0 笔");
    }

    @Test
    @DisplayName("非法 status 取值 ⇒ 明确报参错（不静默忽略）")
    void invalidStatusRejected() {
        BizException ex = assertThrows(BizException.class, () ->
                fundService.listDepositWithdrawApplies(
                        PageQuery.of("1", "20", null, "id", "desc"), null, null, null,
                        "NOT_A_STATUS", OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, ex.errorCode(), "非法状态须 10001");
        assertTrue(ex.getMessage().contains("ck_dw_status"), "错误信息须给出取值域");
    }

    @Test
    @DisplayName("分页行为：size=20 两页遍历无重复无漏行（全序兜底；size 白名单 {10,20,50,100} 内）")
    void paginationStable() {
        // 第 1 页（3 行）+ 第 2 页（空）
        List<Long> seen = new ArrayList<>();
        for (int page = 1; page <= 2; page++) {
            HttpResult r = call("GET", PATH, "page=" + page + "&size=20", "S-REV");
            assertEquals(200, r.status, "分页请求须成功（size 白名单内）");
            String body = new String(r.body, StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"id\":(\\d+)").matcher(body);
            while (m.find()) {
                seen.add(Long.parseLong(m.group(1)));
            }
        }
        assertEquals(3, seen.size(), "size=20 遍历 ⇒ 恰收 3 行：" + seen);
        assertEquals(3, seen.stream().distinct().count(), "跨页无重复行（全序兜底）");
    }

    // ------------------------------------------------------------ 夹具

    /** 种子：以指定终态直接落一笔申请（绕开复核/回执链，聚焦查询面）。 */
    private void seedApply(int sub, String idem, String status, String desc, String rejectReason) {
        com.hedgeguard.dao.entity.DepositWithdrawApply a =
                new com.hedgeguard.dao.entity.DepositWithdrawApply();
        a.subAccountId = sub;
        a.applyNo = "DW" + DAY.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                + String.format("%05d", 100 + engine.table("t_deposit_withdraw_apply").rows.size());
        a.idempotencyKey = idem;
        a.direction = "IN";
        a.amount = new java.math.BigDecimal("500");
        a.fundSourceDesc = desc;
        a.status = status;
        a.applicantId = OPERATOR;
        a.rejectReason = rejectReason;
        a.originalConfirmed = "N";
        new com.hedgeguard.dao.impl.DmDepositWithdrawApplyDao(tx).insert(a, OPERATOR);
    }

    private HttpResult call(String method, String path, String query, String session) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=" + session);
        return dispatcher.dispatch(new RequestContext(method, path, query, headers,
                "", "HG-T195", "127.0.0.1"));
    }

    private static List<String> schemaScripts() {
        return List.of(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m2_fund.sql");
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
