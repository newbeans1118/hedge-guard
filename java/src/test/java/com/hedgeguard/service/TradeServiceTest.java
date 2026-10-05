package com.hedgeguard.service;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.HgcpConstants;
import com.hedgeguard.bridge.HgcpEventListener;
import com.hedgeguard.bridge.PlaceOrderCmd;
import com.hedgeguard.bridge.PlaceOrderResult;
import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradeCommandFlowDao;
import com.hedgeguard.dao.TradeFillDao;
import com.hedgeguard.dao.TradeOrderDao;
import com.hedgeguard.dao.TradeOrderReportDao;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.TradePositionDao;
import com.hedgeguard.dao.TradePreTradeRejectDao;
import com.hedgeguard.dao.TradeReasonDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.TradeCommandFlow;
import com.hedgeguard.dao.entity.TradeFill;
import com.hedgeguard.dao.entity.TradeOrder;
import com.hedgeguard.dao.entity.TradeOrderReport;
import com.hedgeguard.dao.entity.TradePlan;
import com.hedgeguard.dao.entity.TradePosition;
import com.hedgeguard.dao.entity.TradePreTradeReject;
import com.hedgeguard.dao.entity.TradeReason;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-046} {@link TradeService} 服务层单测（{@code NFR-TEST-2}：不引 Mockito，以严格记录型假 DAO 替代）。
 *
 * <p>覆盖验收 1/2/3/5 与强制点接线：
 * <ul>
 *   <li>验收 1：提交经桥接 {@code placeOrder}；确认/撤单经 {@code confirmOrder}/{@code cancelOrder}；</li>
 *   <li>验收 2：交易理由结构化必填（关联类型 + 关联对象 + 时点说明），缺一即拒；</li>
 *   <li>验收 3（第①层）：Java 提交时权威校验「关联对象同子账户」（第②层 C++ {@code 0x1019} 在 cpp 域，另披露；第③层落库复核见表末用例）；</li>
 *   <li>验收 5：<b>提交路径零 DAO 写调用审计</b>——100 笔下单，写 DAO 调用数 = 0（读调用数如实报告）。</li>
 * </ul>
 *
 * <p><b>环境口径（据实披露）</b>：本机无 DM8 实例 / 驱动，{@code docs/DEPLOY/schema} 的 M2 交易表无法真实建库；
 * 故验收 5 以「<b>DAO 层写调用审计</b>」等价执行（严格记录型假 DAO：任何 {@code insert}/{@code updateById}
 * 一经调用即计数并留方法名）。{@link WriteAudit} 的非空转由 {@link #writeAuditHasTeeth()}（事件落库用例写调用 > 0）承重。
 */
class TradeServiceTest {

    private static final LocalDate BASE_DAY = LocalDate.of(2026, 10, 4);
    private static final long TRADER_ID = 1L;
    private static final int SUB_A = 101;
    private static final long PLAN_ID = 9001L;
    private static final long EXPOSURE_ID = 7001L;

    private TradeService service;
    private InMemoryTradingBridge bridge;
    private final WriteAudit audit = new WriteAudit();

    private FakeUserAccountDao userDao;
    private FakeRoleDao roleDao;
    private FakeUserRoleDao userRoleDao;
    private FakePermDao permDao;
    private FakeTradePlanDao planDao;
    private FakeHedgePlanDao hedgePlanDao;
    private FakeExposureDao exposureDao;
    private FakeTradeOrderDao orderDao;
    private FakeTradeOrderReportDao reportDao;
    private FakeTradeFillDao fillDao;
    private FakeTradePositionDao positionDao;
    private FakePreTradeRejectDao preTradeRejectDao;
    private FakeCommandFlowDao commandFlowDao;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        audit.reset();

        userDao = new FakeUserAccountDao();
        roleDao = new FakeRoleDao();
        userRoleDao = new FakeUserRoleDao();
        permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();

        // 有效交易岗：TRADER（has_trading_operation=Y, ENABLED），非负责人 / 非管理层
        userDao.users.put(TRADER_ID, user(TRADER_ID, "N"));
        roleDao.roles.put(10L, role(10L, AuthService.ROLE_TRADER, "Y", "ENABLED"));
        userRoleDao.rows.add(userRole(1L, TRADER_ID, 10L));
        permDao.rows.add(perm(TRADER_ID, SUB_A, "LIMITED"));

        AuthService authService = new AuthService(FakeTx.newTemplate(), userDao, roleDao, userRoleDao,
                null, null, null, null, permDao, attemptDao, null, null, null, null, null);

        planDao = new FakeTradePlanDao(audit);
        hedgePlanDao = new FakeHedgePlanDao();
        exposureDao = new FakeExposureDao();
        orderDao = new FakeTradeOrderDao(audit);
        planDao.plans.put(PLAN_ID, plan(PLAN_ID, SUB_A, EXPOSURE_ID));
        // 交易理由关联计划（related_plan_id）按套保计划校验（TE-T058-003；FK→t_hedge_plan）
        hedgePlanDao.plans.put(PLAN_ID, hedgePlan(PLAN_ID, SUB_A));
        exposureDao.rows.put(EXPOSURE_ID, exposure(EXPOSURE_ID, SUB_A));

        bridge = new InMemoryTradingBridge();
        reportDao = new FakeTradeOrderReportDao(audit);
        fillDao = new FakeTradeFillDao(audit);
        positionDao = new FakeTradePositionDao(audit);
        preTradeRejectDao = new FakePreTradeRejectDao(audit);
        commandFlowDao = new FakeCommandFlowDao(audit);
        service = new TradeService(FakeTx.newTemplate(), bridge, authService,
                planDao, hedgePlanDao, exposureDao,
                orderDao, new FakeTradeReasonDao(audit),
                reportDao, fillDao,
                positionDao, preTradeRejectDao,
                commandFlowDao);
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ================================================================ 验收 1 / 5：提交不落库、经桥接

    @Test
    @DisplayName("验收1+5：合法提交 ⇒ 桥接收到 placeOrder，且写 DAO 调用数 = 0（提交路径零写）")
    void submitAcceptedOrderCallsBridgeAndWritesNothing() {
        PlaceOrderResult result = service.submitOrder(request(validReason()), TRADER_ID);

        assertTrue(result.accepted, "应被受理");
        assertTrue(result.orderId > 0, "应返回 C++ 指令号");
        assertEquals(1, bridge.placedOrders().size(), "桥接应收到 1 笔下单");
        PlaceOrderCmd cmd = bridge.placedOrders().get(0);
        assertEquals(SUB_A, cmd.subAccountId);
        assertEquals(PLAN_ID, cmd.tradePlanId);
        assertEquals("10", cmd.volume);
        assertEquals("BUY", cmd.side);
        assertTrue(cmd.clientOrderRef.matches("HG\\d{8}\\d{6}"), "clientOrderRef 形态：HG<yyyyMMdd><seq6>");
        // 验收 5：提交路径不写库
        assertEquals(0, audit.writes.size(), "提交路径不得有任何写 DAO 调用：命中=" + audit.writes);
        // 读调用如实报告（非断言失败）：至少 plan / exposure / auth
        assertFalse(audit.reads.isEmpty(), "本用例应读到 plan/exposure（用于权威校验）");
    }

    @Test
    @DisplayName("T-173/CH-020：submitOrder 组装的 PlaceOrderCmd 六字段须与理由/计划绑定逐字一致")
    void submitOrderFillsRelatedObjectAndReasonFields() {
        TradeService.ReasonInput reason = validReason();
        reason.relatedType = "ROLLOVER";
        reason.relatedPlanId = PLAN_ID;
        reason.relatedExposureId = EXPOSURE_ID;
        reason.timeDesc = "EXPIRY_ROLLOVER";
        reason.reasonText = "到期展期置换";

        service.submitOrder(request(reason), TRADER_ID);

        assertEquals(1, bridge.placedOrders().size(), "桥接应收到 1 笔下单");
        PlaceOrderCmd cmd = bridge.placedOrders().get(0);
        // 字段 3：实货敞口 ID「源自计划绑定」（plan.exposureId = EXPOSURE_ID）。
        assertEquals(EXPOSURE_ID, cmd.exposureId, "exposureId 须取计划绑定的敞口");
        // 字段 19/20：关联对象逐字承载。
        assertEquals(PLAN_ID, cmd.relatedPlanId, "relatedPlanId 须逐字承载理由的计划 ID");
        assertEquals(EXPOSURE_ID, cmd.relatedExposureId, "relatedExposureId 须逐字承载理由的敞口 ID");
        // 字段 16/17/18：结构化理由逐字承载。
        assertEquals("ROLLOVER", cmd.tradeReasonCode, "tradeReasonCode 须逐字承载 relatedType");
        assertEquals("EXPIRY_ROLLOVER", cmd.tradeReasonTimeDesc, "tradeReasonTimeDesc 须逐字承载 timeDesc");
        assertEquals("到期展期置换", cmd.tradeReasonText, "tradeReasonText 须逐字承载 reasonText");
    }

    @Test
    @DisplayName("T-173：reasonText 缺省时 tradeReasonText 为 null，不臆造值")
    void submitOrderLeavesReasonTextNullWhenAbsent() {
        service.submitOrder(request(validReason()), TRADER_ID);
        PlaceOrderCmd cmd = bridge.placedOrders().get(0);
        assertEquals(null, cmd.tradeReasonText, "reasonText 缺省 ⇒ DTO 保持 null，由桥侧映射空串");
    }

    @Test
    @DisplayName("验收5 非空转（正控）：事件落库确实产生写调用 ⇒ 记录器有牙")
    void writeAuditHasTeeth() {
        service.onPreTradeReject(preTradeReject("HG20261004000001", "R-RISK-2"));
        assertFalse(audit.writes.isEmpty(), "事件落库应产生写调用（否则记录器对写失明）");
        assertTrue(audit.writes.get(0).contains("PreTradeReject"), "应记录被写 DAO：" + audit.writes);
    }

    @Test
    @DisplayName("验收5：100 笔下单，写 DAO 调用数 = 0")
    void hundredOrdersWriteNothing() {
        for (int i = 0; i < 100; i++) {
            service.submitOrder(request(validReason()), TRADER_ID);
        }
        assertEquals(100, bridge.placedOrders().size(), "100 笔均应下发");
        assertEquals(0, audit.writes.size(), "100 笔下单零写调用：命中=" + audit.writes);
    }

    // ================================================================ 验收 2：理由结构化必填

    @Test
    @DisplayName("验收2：未提供理由 ⇒ 拒绝（10012），且不下发")
    void submitRejectsNullReason() {
        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(null), TRADER_ID));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode(), "缺理由属参数/结构化校验失败（10001）");
        assertEquals(0, bridge.placedOrders().size(), "被拒不得下发");
    }

    @Test
    @DisplayName("验收2：缺关联类型 ⇒ 拒绝；非法取值同样拒绝")
    void submitRejectsMissingOrIllegalRelatedType() {
        TradeService.ReasonInput r = validReason();
        r.relatedType = null;
        assertRejected(() -> service.submitOrder(request(r), TRADER_ID), "缺关联类型");
        TradeService.ReasonInput r2 = validReason();
        r2.relatedType = "NOT_A_TYPE";
        assertRejected(() -> service.submitOrder(request(r2), TRADER_ID), "非法关联类型");
    }

    @Test
    @DisplayName("验收2：缺时点说明 ⇒ 拒绝")
    void submitRejectsMissingTimeDesc() {
        TradeService.ReasonInput r = validReason();
        r.timeDesc = null;
        assertRejected(() -> service.submitOrder(request(r), TRADER_ID), "缺时点说明");
    }

    @Test
    @DisplayName("验收2：提交路径缺关联对象（计划/敞口任一）⇒ 拒绝")
    void submitRejectsMissingRelatedObject() {
        TradeService.ReasonInput r1 = validReason();
        r1.relatedPlanId = null;
        assertRejected(() -> service.submitOrder(request(r1), TRADER_ID), "缺关联计划");
        TradeService.ReasonInput r2 = validReason();
        r2.relatedExposureId = null;
        assertRejected(() -> service.submitOrder(request(r2), TRADER_ID), "缺关联敞口");
    }

    // ================================================================ 验收 3：Java 权威层同子账户校验

    @Test
    @DisplayName("验收3（第①层）：关联计划与敞口跨子账户 ⇒ 拒绝（0x1019 语义，业务码 10012）")
    void submitRejectsCrossSubAccountRelatedObject() {
        // 关联计划（= 套保计划，TE-T058-003 后按 t_hedge_plan 校验）归属另一子账户
        hedgePlanDao.plans.put(PLAN_ID, hedgePlan(PLAN_ID, SUB_A + 1));
        assertBlocked(() -> service.submitOrder(request(validReason()), TRADER_ID), "关联计划跨子账户");
        hedgePlanDao.plans.put(PLAN_ID, hedgePlan(PLAN_ID, SUB_A));

        // 敞口归属另一子账户
        exposureDao.rows.put(EXPOSURE_ID, exposure(EXPOSURE_ID, SUB_A + 1));
        assertBlocked(() -> service.submitOrder(request(validReason()), TRADER_ID), "关联敞口跨子账户");
    }

    @Test
    @DisplayName("验收3（第③层）：落库复核再次比对同子账户，跨子账户拒落库")
    void reviewAndPersistReasonRechecksOwnership() {
        // 先经事件落一笔指令，取得其主键（假 DAO 自增）
        service.onOrderAccepted(acceptedEvent(validReason()));
        assertFalse(orderDao.rows.isEmpty(), "受理事件应落指令");
        long orderId = orderDao.rows.get(0).id;
        TradeService.ReasonInput cross = validReason();
        cross.relatedExposureId = 99999L; // 不存在
        assertThrows(BizException.class,
                () -> service.reviewAndPersistReason(orderId, cross, TRADER_ID, true),
                "第③层复核应拒绝不可解析的关联对象");
    }

    // ================================================================ 强制点：assertCanTrade 接线

    @Test
    @DisplayName("强制点：负责人/管理层禁操盘（10005）⇒ 提交被拒且不下发")
    void submitBlocksEnterpriseLeader() {
        userDao.users.put(TRADER_ID, user(TRADER_ID, "Y")); // is_enterprise_leader='Y'
        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(validReason()), TRADER_ID));
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT, e.errorCode());
        assertEquals(0, bridge.placedOrders().size(), "被拒不得下发");
        assertEquals(0, audit.writes.size(), "拒绝路径（提交态）亦不写交易表");
    }

    @Test
    @DisplayName("强制点：不具交易岗（10003）⇒ 提交被拒且不下发")
    void submitBlocksNonTrader() {
        roleDao.roles.put(10L, role(10L, AuthService.ROLE_TRADER, "N", "ENABLED"));
        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(validReason()), TRADER_ID));
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED, e.errorCode());
        assertEquals(0, bridge.placedOrders().size());
    }

    @Test
    @DisplayName("强制点：仅跨账户只读（CROSS_READONLY）不得写（10004）⇒ 提交被拒")
    void submitBlocksCrossReadonlyWrite() {
        permDao.rows.clear();
        permDao.rows.add(perm(TRADER_ID, SUB_A, "CROSS_READONLY"));
        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(validReason()), TRADER_ID));
        assertEquals(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED, e.errorCode());
        assertEquals(0, bridge.placedOrders().size());
    }

    // ================================================================ 验收 1：确认 / 撤单

    @Test
    @DisplayName("验收1：确认经桥接 confirmOrder（操作人与令牌分列）")
    void confirmOrderCallsBridge() {
        AckResult ack = service.confirmOrder(123456L, TRADER_ID, "TOKEN-1");
        assertTrue(ack.acknowledged, "应收到成功回执");
    }

    @Test
    @DisplayName("验收1：撤单经桥接 cancelOrder（携带操作人）")
    void cancelOrderCallsBridge() {
        AckResult ack = service.cancelOrder(123456L, "市场变化", TRADER_ID);
        assertTrue(ack.acknowledged, "应收到成功回执");
    }

    @Test
    @DisplayName("验收1：确认缺令牌 ⇒ 拒绝（不下发）")
    void confirmRequiresToken() {
        assertThrows(BizException.class, () -> service.confirmOrder(1L, TRADER_ID, " "));
    }

    // ================================================================ 验收 4：事件流落库 + 回写

    @Test
    @DisplayName("验收4：受理事件落指令 + 理由，并占用计划量（ordered_qty += volume）")
    void onOrderAcceptedPersistsAndOccupiesPlan() {
        service.onOrderAccepted(acceptedEvent(validReason()));
        TradePlan after = planDao.plans.get(PLAN_ID);
        assertEquals(0, after.orderedQty.compareTo(new BigDecimal("10")),
                "受理应占用 ordered_qty = 10");
        assertFalse(audit.writes.isEmpty());
    }

    @Test
    @DisplayName("验收4：成交事件落库 + 回写计划进度（executed_qty += 量；ordered_qty 回退），幂等")
    void onFillPersistsWritesBackAndIsIdempotent() {
        service.onOrderAccepted(acceptedEvent(validReason()));
        TradeService.FillEvent fill = new TradeService.FillEvent();
        fill.subAccountId = SUB_A;
        fill.tradeId = "T-FILL-1";
        fill.orderId = 1001L; // acceptedEvent 的 C++ 指令号（TE-T058-004 后须可解析出父行 DB 主键）
        fill.tradePlanId = PLAN_ID;
        fill.instrumentId = "cu2612";
        fill.fillVolume = 4;
        fill.fillPrice = "70500";
        fill.direction = "BUY";
        fill.offsetFlag = "OPEN";
        fill.tradeTsMs = 1760000000000L;
        fill.eventSeq = 31L;
        service.onFill(fill);
        TradePlan after = planDao.plans.get(PLAN_ID);
        assertEquals(0, after.executedQty.compareTo(new BigDecimal("4")), "executed_qty=4");
        assertEquals(0, after.orderedQty.compareTo(new BigDecimal("6")), "ordered_qty 回退为 6");

        int writesBefore = audit.writes.size();
        service.onFill(fill); // 重复推送
        assertEquals(writesBefore, audit.writes.size(), "重复成交回报必须幂等（不再写）");
        assertEquals(0, planDao.plans.get(PLAN_ID).executedQty.compareTo(new BigDecimal("4")),
                "重复回报不得重复计入");
    }

    @Test
    @DisplayName("验收4：事前风控拒绝事件落合规证据（幂等）")
    void onPreTradeRejectIdempotent() {
        TradeService.PreTradeRejectEvent e = preTradeReject("HG20261004000001", "R-RISK-2");
        service.onPreTradeReject(e);
        int n = audit.writes.size();
        service.onPreTradeReject(e);
        assertEquals(n, audit.writes.size(), "同指令同规则只记一条（幂等）");
    }

    @Test
    @DisplayName("验收4：指令流水重复投递幂等（同 client_order_ref+flow_type+flow_ts_ms 仅落 1 行）")
    void onCommandFlowIdempotentOnRepeatedDelivery() {
        TradeService.CommandFlowEvent e = commandFlow("HG20261004000009", "ACCEPTED", 1760000000000L);
        service.onCommandFlow(e);
        assertEquals(1, commandFlowDao.rows.size(), "首帧应落 1 行");
        service.onCommandFlow(e); // 同帧重复投递
        assertEquals(1, commandFlowDao.rows.size(),
                "同帧重复投递不得重复落库（uk_command_flow_idem：ref+flow_type+flow_ts_ms）");
    }

    @Test
    @DisplayName("验收4：指令流水幂等键区分维度（flow_type / flow_ts_ms 变更即视为新流水）")
    void onCommandFlowDistinguishesIdemKeyDimensions() {
        service.onCommandFlow(commandFlow("HG20261004000010", "ACCEPTED", 1760000000000L));
        service.onCommandFlow(commandFlow("HG20261004000010", "SENT_CTP", 1760000000000L));
        service.onCommandFlow(commandFlow("HG20261004000010", "ACCEPTED", 1760000000001L));
        assertEquals(3, commandFlowDao.rows.size(), "三个维度任一不同即为新流水");
    }

    // ================================================================ T-172：提交上下文富化（六事件落库面）

    @Test
    @DisplayName("T-172 富化全链①：submitOrder → 受理事件（线格式仅回显 5 字段）→ 落库含全部富化 NOT NULL 字段")
    void enrichmentOrderAcceptedFromSubmitContext() {
        PlaceOrderResult r = service.submitOrder(request(validReason()), TRADER_ID);
        assertTrue(r.accepted && r.orderId > 0, "应先受理取得 order_id");
        // 线格式 EvtOrderAccepted 仅 order_id/client_order_ref/risk_passed/effective_timeout_ms/accepted_ts_ms
        TradeService.OrderAcceptedEvent wire = new TradeService.OrderAcceptedEvent();
        wire.orderId = r.orderId;
        wire.clientOrderRef = r.clientOrderRef;
        service.onOrderAccepted(wire);
        assertEquals(1, orderDao.rows.size(), "应按上下文富化后落 1 行（缺源会被 skip）");
        TradeOrder row = orderDao.rows.get(0);
        assertEquals(SUB_A, row.subAccountId.intValue(), "sub_account_id 富化自上下文");
        assertEquals(PLAN_ID, row.tradePlanId.longValue(), "trade_plan_id 富化自上下文");
        assertEquals(1L, row.hedgePlanId.longValue(), "hedge_plan_id 富化（NOT NULL）");
        assertEquals(EXPOSURE_ID, row.exposureId.longValue(), "exposure_id 富化");
        assertEquals(55L, row.productId.longValue(), "product_id 富化");
        assertEquals("cu2612", row.instrumentId);
        assertEquals("BUY", row.direction);
        assertEquals("OPEN", row.offsetFlag);
        assertEquals("LIMIT", row.priceType);
        assertEquals(10, row.volume.intValue());
        assertEquals(TRADER_ID, row.operatorId.longValue());
    }

    @Test
    @DisplayName("T-172 富化全链②：submitOrder → 成交事件（线格式无 product_id）→ 落库含品种/计划/持仓归属")
    void enrichmentFillFromSubmitContext() {
        PlaceOrderResult r = submitAndAccept();
        TradeService.FillEvent wire = new TradeService.FillEvent();
        wire.tradeId = "T-FILL-ENRICH";
        wire.orderId = r.orderId;
        wire.orderSysId = "SYS-1";
        wire.instrumentId = "cu2612";
        wire.direction = "BUY";
        wire.offsetFlag = "OPEN";
        wire.fillPrice = "70500";
        wire.fillVolume = 4;
        wire.tradeTsMs = 1760000000000L;
        wire.eventSeq = 41L;
        service.onFill(wire);
        assertEquals(1, fillDao.rows.size(), "应按上下文富化后落成交（product_id 等 NOT NULL）");
        TradeFill f = fillDao.rows.get(0);
        assertEquals(55L, f.productId.longValue(), "product_id 经上下文解析");
        assertEquals(PLAN_ID, f.tradePlanId.longValue(), "trade_plan_id 富化");
        assertEquals(1L, f.hedgePlanId.longValue(), "hedge_plan_id 富化（NOT NULL）");
        assertEquals(EXPOSURE_ID, f.exposureId.longValue(), "exposure_id 富化");
        assertEquals(SUB_A, f.subAccountId.intValue());
        assertEquals(LocalDate.of(2025, 10, 9), f.tradeDate, "trade_date 由 trade_ts_ms 派生（信源忠实）");
    }

    @Test
    @DisplayName("T-172 富化全链③：submitOrder → 委托回报（线格式无 sub_account_id/品种）→ 落库含富化字段")
    void enrichmentOrderReportFromSubmitContext() {
        PlaceOrderResult r = submitAndAccept();
        TradeService.OrderReportEvent e = new TradeService.OrderReportEvent();
        e.orderId = r.orderId;
        e.orderSysId = "SYS-R1";
        e.orderStatus = "ORDER_STATUS_ALL_TRADED";
        e.volumeTotal = 10;
        e.volumeTraded = 10;
        e.volumeLeft = 0;
        e.reportTsMs = 1760000000000L;
        e.eventSeq = 71L;
        service.onOrderReport(e);
        assertEquals(1, reportDao.rows.size(), "应按上下文富化后落回报");
        TradeOrderReport row = reportDao.rows.get(0);
        assertEquals(SUB_A, row.subAccountId.intValue(), "sub_account_id 富化自上下文");
        assertEquals("cu2612", row.instrumentId, "instrument_id 富化自上下文");
        assertEquals("BUY", row.direction);
        assertEquals("OPEN", row.offsetFlag);
        assertEquals(71L, row.eventSeq.longValue(), "event_seq 由调用方（帧头 SEQ）承载");
    }

    @Test
    @DisplayName("T-172 富化：持仓事件（线格式无 product_id）→ 经 instrument_id 索引回填")
    void enrichmentPositionFromInstrumentIndex() {
        service.submitOrder(request(validReason()), TRADER_ID);
        TradeService.PositionChangeEvent e = new TradeService.PositionChangeEvent();
        e.subAccountId = SUB_A;
        e.instrumentId = "cu2612";
        e.direction = "BUY";
        e.positionType = "SPECULATION";
        e.positionQty = new BigDecimal("4");
        e.eventSeq = 81L;
        service.onPositionChange(e);
        assertEquals(1, positionDao.rows.size(), "应按 instrument 索引回填 product_id 后落库");
        TradePosition p = positionDao.rows.values().iterator().next();
        assertEquals(55L, p.productId.longValue(), "product_id 经 instrument 索引解析");
    }

    @Test
    @DisplayName("T-172 Q-5：productIdForOrder 经上下文取真值（保品种级分组）；未知返回 0")
    void productIdResolverFromContext() {
        PlaceOrderResult r = service.submitOrder(request(validReason()), TRADER_ID);
        assertEquals(55L, service.productIdForOrder(r.orderId), "经上下文取品种真值");
        assertEquals(0L, service.productIdForOrder(888888L), "未关联降级 0（与既有接缝默认一致）");
    }

    @Test
    @DisplayName("T-172 硬边界②：指令流水无 client_order_ref 且 order_id 未关联 → skip+LOG+残留清单（不 crash、不落库）")
    void untraceableCommandFlowSkippedAndListed() {
        TradeService.CommandFlowEvent e = new TradeService.CommandFlowEvent();
        e.orderId = 999999L;
        e.flowType = "ACCEPTED";
        e.flowTsMs = 1760000000000L;
        e.eventSeq = 51L;
        service.onCommandFlow(e);
        assertEquals(0, commandFlowDao.rows.size(), "不可溯源流水不得落库");
        assertFalse(service.residualEvents().isEmpty(), "应登记残留清单");
        assertTrue(service.residualEvents().get(0).contains("EVT_COMMAND_FLOW"),
                "残留项应点名事件：" + service.residualEvents());
    }

    @Test
    @DisplayName("T-172 reject_reason 规范化派生：线格式无源 ⇒ DERIVED[rule;layer;cur;thr] 落库（不再 skip）")
    void preTradeRejectDerivesReasonWhenUnsourced() {
        TradeService.PreTradeRejectEvent e = new TradeService.PreTradeRejectEvent();
        e.subAccountId = SUB_A;
        e.clientOrderRef = "HG20261004000077";
        e.ruleId = "R-RISK-2";
        e.rejectCode = "0x1004";
        e.orderDigest = "{d}";
        e.limitLayer = "ACCOUNT";
        e.currentValue = "5";
        e.thresholdValue = "3";
        e.operatorId = TRADER_ID;
        e.rejectTsMs = 1760000000000L;
        e.eventSeq = 61L;
        service.onPreTradeReject(e);
        assertEquals(1, preTradeRejectDao.rows.size(), "派生后不再因 reject_reason 缺源而 skip");
        assertEquals("DERIVED[rule=R-RISK-2;layer=ACCOUNT;cur=5;thr=3]",
                preTradeRejectDao.rows.get(0).rejectReason, "派生串形态（四段齐全、显式 DERIVED 标注）");
        assertTrue(service.residualEvents().isEmpty(), "无缺源字段，不应登记残留：" + service.residualEvents());
    }

    @Test
    @DisplayName("T-172 reject_reason 规范化派生：缺项省略对应段（仅 rule 可用 ⇒ DERIVED[rule=...]）")
    void preTradeRejectDerivesReasonOmitsMissingSegments() {
        TradeService.PreTradeRejectEvent e = new TradeService.PreTradeRejectEvent();
        e.subAccountId = SUB_A;
        e.clientOrderRef = "HG20261004000078";
        e.ruleId = "R-RISK-2";
        e.rejectCode = "0x1004";
        e.orderDigest = "{d}";
        e.operatorId = TRADER_ID;
        e.rejectTsMs = 1760000000000L;
        e.eventSeq = 62L;
        service.onPreTradeReject(e);
        assertEquals(1, preTradeRejectDao.rows.size(), "派生后仍落库");
        assertEquals("DERIVED[rule=R-RISK-2]", preTradeRejectDao.rows.get(0).rejectReason,
                "缺项省略对应段（layer/cur/thr 均缺）");
    }

    @Test
    @DisplayName("T-172 硬边界①：提交上下文有界（容量上限 + LRU 淘汰最久未访问者）")
    void submitContextBoundedAndEvicts() {
        long firstOrderId = 0;
        for (int i = 0; i < TradeService.SUBMIT_CONTEXT_CAPACITY + 2; i++) {
            PlaceOrderResult r = service.submitOrder(request(validReason()), TRADER_ID);
            if (i == 0) {
                firstOrderId = r.orderId;
            }
        }
        assertEquals(TradeService.SUBMIT_CONTEXT_CAPACITY, service.submitContextSize(), "条目数封顶");
        assertEquals(2, service.submitContextEvictions(), "超容逐出最久未访问者");
        assertEquals(0L, service.productIdForOrder(firstOrderId), "最久未访问者已被淘汰");
    }

    @Test
    @DisplayName("T-172 接缝适配：中立 EvtTradeFill → 帧头 SEQ 补入 → 富化落库（event_seq 有源、Q-5 品种级）")
    void hgcpEventSinkFillEndToEnd() {
        PlaceOrderResult r = submitAndAccept();
        TradeService.HgcpEventSink sink = service.new HgcpEventSink();
        HgcpEventListener.TradeFill wire = new HgcpEventListener.TradeFill();
        wire.tradeId = "T-SINK-1";
        wire.orderId = r.orderId;
        wire.orderSysId = "SYS-SINK";
        wire.instrumentId = "cu2612";
        wire.direction = "DIRECTION_BUY";
        wire.offsetFlag = "OFFSET_FLAG_OPEN";
        wire.price = "70500";
        wire.volume = 4;
        wire.tradeTsMs = 1760000000000L;
        wire.subAccountId = SUB_A;
        wire.tradePlanId = PLAN_ID;
        wire.exposureId = EXPOSURE_ID;
        sink.onTradeFill(wire);
        assertEquals(0, fillDao.rows.size(), "冲刷前不落库（帧头 SEQ 未到）");
        sink.onEventConsumed(HgcpConstants.EVT_TRADE_FILL, 99L);
        assertEquals(1, fillDao.rows.size(), "冲刷后应富化落库");
        TradeFill f = fillDao.rows.get(0);
        assertEquals(99L, f.eventSeq.longValue(), "event_seq 由帧头 SEQ 补入");
        assertEquals(55L, f.productId.longValue(), "product_id 经上下文富化（Q-5 品种级）");
        assertEquals("BUY", f.direction, "方向去线格式前缀 DIRECTION_");
    }

    // ================================================================ 夹具

    private void assertRejected(Runnable r, String what) {
        BizException e = assertThrows(BizException.class, r::run, what);
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode(), what + " 属结构性拒绝（10001）");
        assertEquals(0, bridge.placedOrders().size(), what + " 被拒不得下发");
    }

    /** 阈值型阻断（10012）拒绝：四项齐备（0x1019 语义）。 */
    private void assertBlocked(Runnable r, String what) {
        BizException e = assertThrows(BizException.class, r::run, what);
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode(), what);
        assertNotNull(e.ruleId(), what + " 阻断须带 ruleId");
        assertNotNull(e.current(), what + " 阻断须带 current");
        assertNotNull(e.threshold(), what + " 阻断须带 threshold");
        assertNotNull(e.nextStep(), what + " 阻断须带 nextStep");
        assertEquals(0, bridge.placedOrders().size(), what + " 被拒不得下发");
    }

    /** 提交并受理（建立父指令行；TE-T058-004 后子表 order_id 须可解析出父行 DB 主键）。 */
    private PlaceOrderResult submitAndAccept() {
        PlaceOrderResult r = service.submitOrder(request(validReason()), TRADER_ID);
        TradeService.OrderAcceptedEvent accepted = new TradeService.OrderAcceptedEvent();
        accepted.orderId = r.orderId;
        accepted.clientOrderRef = r.clientOrderRef;
        service.onOrderAccepted(accepted);
        return r;
    }

    private static TradeService.SubmitRequest request(TradeService.ReasonInput reason) {
        TradeService.SubmitRequest req = new TradeService.SubmitRequest();
        req.subAccountId = SUB_A;
        req.tradePlanId = PLAN_ID;
        req.exposureId = EXPOSURE_ID;
        req.productId = 55L;
        req.instrumentId = "cu2612";
        req.direction = "BUY";
        req.offsetFlag = "OPEN";
        req.priceType = "LIMIT";
        req.price = "70000";
        req.volume = 10;
        req.reason = reason;
        return req;
    }

    private static TradeService.ReasonInput validReason() {
        TradeService.ReasonInput r = new TradeService.ReasonInput();
        r.relatedType = "PLAN_EXECUTION";
        r.relatedPlanId = PLAN_ID;
        r.relatedExposureId = EXPOSURE_ID;
        r.timeDesc = "CONTRACT_SIGNED";
        return r;
    }

    private static TradeService.OrderAcceptedEvent acceptedEvent(TradeService.ReasonInput reason) {
        TradeService.OrderAcceptedEvent e = new TradeService.OrderAcceptedEvent();
        e.subAccountId = SUB_A;
        e.orderId = 1001L;
        e.clientOrderRef = "HG20261004000001";
        e.tradePlanId = PLAN_ID;
        e.exposureId = EXPOSURE_ID;
        e.productId = 55L;
        e.instrumentId = "cu2612";
        e.direction = "BUY";
        e.offsetFlag = "OPEN";
        e.priceType = "LIMIT";
        e.limitPrice = "70000";
        e.volume = 10;
        e.operatorId = TRADER_ID;
        e.reason = reason;
        return e;
    }

    private static TradeService.PreTradeRejectEvent preTradeReject(String ref, String ruleId) {
        TradeService.PreTradeRejectEvent e = new TradeService.PreTradeRejectEvent();
        e.subAccountId = SUB_A;
        e.clientOrderRef = ref;
        e.ruleId = ruleId;
        e.rejectCode = "0x1004";
        e.rejectReason = "超额度";
        e.orderDigest = "{digest}";
        e.operatorId = TRADER_ID;
        e.rejectTsMs = 1760000000000L;
        e.eventSeq = 11L;
        return e;
    }

    private static TradeService.CommandFlowEvent commandFlow(String ref, String flowType, Long tsMs) {
        TradeService.CommandFlowEvent e = new TradeService.CommandFlowEvent();
        e.subAccountId = SUB_A;
        e.orderId = 1001L;
        e.clientOrderRef = ref;
        e.flowType = flowType;
        e.flowDetail = "{\"k\":\"v\"}";
        e.flowTsMs = tsMs;
        e.eventSeq = 21L;
        return e;
    }

    private static UserAccount user(long id, String leader) {
        UserAccount u = new UserAccount();
        u.id = id;
        u.username = "trader01";
        u.status = "ENABLED";
        u.isEnterpriseLeader = leader;
        return u;
    }

    private static Role role(long id, String code, String hasTrading, String status) {
        Role r = new Role();
        r.id = id;
        r.roleCode = code;
        r.roleName = code;
        r.positionGroupId = 1;
        r.hasTradingOperation = hasTrading;
        r.status = status;
        return r;
    }

    private static UserRole userRole(long id, long userId, long roleId) {
        UserRole ur = new UserRole();
        ur.id = id;
        ur.userId = userId;
        ur.roleId = roleId;
        return ur;
    }

    private static UserSubAccountPerm perm(long userId, int subAccountId, String permType) {
        UserSubAccountPerm p = new UserSubAccountPerm();
        p.userId = userId;
        p.subAccountId = subAccountId;
        p.permType = permType;
        p.effectiveFrom = LocalDate.of(2020, 1, 1);
        return p;
    }

    private static TradePlan plan(long id, int subAccountId, long exposureId) {
        TradePlan p = new TradePlan();
        p.id = id;
        p.subAccountId = subAccountId;
        p.tradePlanNo = "TP2026100400001";
        p.hedgePlanId = 1L;
        p.exposureId = exposureId;
        p.productId = 55L;
        p.instrumentId = "cu2612";
        p.instrumentType = "FUTURES";
        p.direction = "BUY";
        p.planQty = new BigDecimal("100");
        p.executedQty = BigDecimal.ZERO;
        p.orderedQty = BigDecimal.ZERO;
        p.execDeadline = LocalDate.of(2026, 12, 31);
        p.status = "EXECUTING";
        return p;
    }

    private static HedgePlan hedgePlan(long id, int subAccountId) {
        HedgePlan p = new HedgePlan();
        p.id = id;
        p.subAccountId = subAccountId;
        p.planNo = "HP2026100400001";
        p.status = "APPROVED";
        return p;
    }

    private static Exposure exposure(long id, int subAccountId) {
        Exposure e = new Exposure();
        e.id = id;
        e.subAccountId = subAccountId;
        e.exposureQty = new BigDecimal("100");
        return e;
    }

    // ================================================================ 严格记录型假 DAO

    /** 写调用审计器：记录任何 {@code insert}/{@code updateById} 的方法名。 */
    static final class WriteAudit {
        final List<String> writes = new ArrayList<>();
        final List<String> reads = new ArrayList<>();

        void write(String owner, String method) {
            writes.add(owner + "." + method);
        }

        void read(String owner, String method) {
            reads.add(owner + "." + method);
        }

        void reset() {
            writes.clear();
            reads.clear();
        }
    }

    private abstract static class AuditDao<T, ID> extends UnsupportedBaseDao<T, ID> {
        final WriteAudit audit;
        final String owner;

        AuditDao(WriteAudit audit, String owner) {
            this.audit = audit;
            this.owner = owner;
        }

        void wrote(String method) {
            audit.write(owner, method);
        }

        void read(String method) {
            audit.read(owner, method);
        }
    }

    private static final class FakeUserAccountDao extends UnsupportedBaseDao<UserAccount, Long>
            implements UserAccountDao {
        final Map<Long, UserAccount> users = new LinkedHashMap<>();

        @Override
        public UserAccount findById(Long id) {
            return users.get(id);
        }

        @Override
        public UserAccount findByUsername(String u) {
            throw FakeDao.unsupported("findByUsername");
        }

        @Override
        public UserAccount findByNaturalPersonKey(String k) {
            throw FakeDao.unsupported("findByNaturalPersonKey");
        }

        @Override
        public int incrementFailCount(long userId) {
            throw FakeDao.unsupported("incrementFailCount");
        }

        @Override
        public int lockUntil(long userId, LocalDateTime t) {
            throw FakeDao.unsupported("lockUntil");
        }

        @Override
        public int unlock(long userId) {
            throw FakeDao.unsupported("unlock");
        }

        @Override
        public int updatePasswordHash(long userId, String h, LocalDateTime t) {
            throw FakeDao.unsupported("updatePasswordHash");
        }

        @Override
        public int touchLastLogin(long userId, LocalDateTime t) {
            throw FakeDao.unsupported("touchLastLogin");
        }
    }

    private static final class FakeRoleDao extends UnsupportedBaseDao<Role, Long> implements RoleDao {
        final Map<Long, Role> roles = new LinkedHashMap<>();

        @Override
        public Role findById(Long id) {
            return roles.get(id);
        }

        @Override
        public Role findByCode(String code) {
            throw FakeDao.unsupported("findByCode");
        }

        @Override
        public List<Role> findByPositionGroup(int g) {
            throw FakeDao.unsupported("findByPositionGroup");
        }

        @Override
        public List<Role> findTradingRoles() {
            throw FakeDao.unsupported("findTradingRoles");
        }
    }

    private static final class FakeUserRoleDao extends UnsupportedBaseDao<UserRole, Long>
            implements UserRoleDao {
        final List<UserRole> rows = new ArrayList<>();

        @Override
        public List<UserRole> findByUser(long userId) {
            List<UserRole> out = new ArrayList<>();
            for (UserRole ur : rows) {
                if (ur.userId == userId) {
                    out.add(ur);
                }
            }
            return out;
        }

        @Override
        public List<UserRole> findByRole(long roleId) {
            throw FakeDao.unsupported("findByRole");
        }
    }

    private static final class FakePermDao extends UnsupportedBaseDao<UserSubAccountPerm, Long>
            implements UserSubAccountPermDao {
        final List<UserSubAccountPerm> rows = new ArrayList<>();

        @Override
        public List<UserSubAccountPerm> findByUser(long userId) {
            List<UserSubAccountPerm> out = new ArrayList<>();
            for (UserSubAccountPerm p : rows) {
                if (p.userId == userId) {
                    out.add(p);
                }
            }
            return out;
        }

        @Override
        public List<Long> findUserIdsBySubAccount(int subAccountId) {
            throw FakeDao.unsupported("findUserIdsBySubAccount");
        }
    }

    private static final class FakeAttemptDao extends UnsupportedBaseDao<UnauthorizedAttempt, Long>
            implements UnauthorizedAttemptDao {
        @Override
        public int insert(UnauthorizedAttempt entity, long operatorId) {
            return 1;
        }

        @Override
        public List<UnauthorizedAttempt> findByOperatorSince(long operatorId, LocalDateTime from) {
            throw FakeDao.unsupported("findByOperatorSince");
        }

        @Override
        public long countByTypeBetween(String type, LocalDateTime from, LocalDateTime to) {
            throw FakeDao.unsupported("countByTypeBetween");
        }
    }

    // 交易 DAO 假件（写调用计审计；读为内存储）

    private static final class FakeTradePlanDao extends AuditDao<TradePlan, Long> implements TradePlanDao {
        final Map<Long, TradePlan> plans = new LinkedHashMap<>();

        FakeTradePlanDao(WriteAudit audit) {
            super(audit, "TradePlanDao");
        }

        @Override
        public TradePlan findById(Long id) {
            read("findById");
            return plans.get(id);
        }

        @Override
        public int updateById(TradePlan e, long operatorId) {
            wrote("updateById");
            plans.put(e.id, e);
            return 1;
        }

        @Override
        public int insert(TradePlan e, long operatorId) {
            wrote("insert");
            plans.put(e.id, e);
            return 1;
        }

        @Override
        public List<TradePlan> findByHedgePlan(long h) {
            throw FakeDao.unsupported("findByHedgePlan");
        }

        @Override
        public List<TradePlan> findByHedgePlanScoped(long h, SubAccountScope s) {
            throw FakeDao.unsupported("findByHedgePlanScoped");
        }

        @Override
        public PageResult<TradePlan> findPageScoped(PageQuery q, SubAccountScope s, String w, List<Object> a) {
            throw FakeDao.unsupported("findPageScoped");
        }

        @Override
        public TradePlan findByTradePlanNo(String no) {
            throw FakeDao.unsupported("findByTradePlanNo");
        }

        @Override
        public String findMaxTradePlanNoByPrefix(String p) {
            throw FakeDao.unsupported("findMaxTradePlanNoByPrefix");
        }
    }

    private static final class FakeExposureDao extends UnsupportedBaseDao<Exposure, Long>
            implements ExposureDao {
        final Map<Long, Exposure> rows = new LinkedHashMap<>();

        @Override
        public Exposure findById(Long id) {
            return rows.get(id);
        }

        @Override
        public PageResult<Exposure> findPageScoped(PageQuery q, SubAccountScope s, String w, List<Object> a) {
            throw FakeDao.unsupported("findPageScoped");
        }

        @Override
        public Exposure findByIdScoped(long id, SubAccountScope s) {
            throw FakeDao.unsupported("findByIdScoped");
        }
    }

    private static final class FakeHedgePlanDao extends UnsupportedBaseDao<HedgePlan, Long>
            implements HedgePlanDao {
        final Map<Long, HedgePlan> plans = new LinkedHashMap<>();

        @Override
        public HedgePlan findById(Long id) {
            return plans.get(id);
        }

        @Override
        public HedgePlan findByPlanNo(String planNo) {
            throw FakeDao.unsupported("findByPlanNo");
        }

        @Override
        public PageResult<HedgePlan> findPageScoped(PageQuery q, SubAccountScope s, String w, List<Object> a) {
            throw FakeDao.unsupported("findPageScoped");
        }

        @Override
        public String findMaxPlanNoByPrefix(String planNoPrefix) {
            throw FakeDao.unsupported("findMaxPlanNoByPrefix");
        }

        @Override
        public HedgePlan findByIdScoped(long id, SubAccountScope s) {
            throw FakeDao.unsupported("findByIdScoped");
        }

        @Override
        public int deleteById(Long id) {
            throw FakeDao.unsupported("deleteById");
        }
    }

    private static final class FakeTradeOrderDao extends AuditDao<TradeOrder, Long> implements TradeOrderDao {
        final List<TradeOrder> rows = new ArrayList<>();
        private long seq = 100L;

        FakeTradeOrderDao(WriteAudit audit) {
            super(audit, "TradeOrderDao");
        }

        @Override
        public int insert(TradeOrder e, long operatorId) {
            wrote("insert");
            e.id = ++seq;
            rows.add(e);
            return 1;
        }

        @Override
        public int updateById(TradeOrder e, long operatorId) {
            wrote("updateById");
            return 1;
        }

        @Override
        public TradeOrder findById(Long id) {
            read("findById");
            for (TradeOrder o : rows) {
                if (o.id == id) {
                    return o;
                }
            }
            return null;
        }

        @Override
        public TradeOrder findByClientOrderRef(String ref) {
            throw FakeDao.unsupported("findByClientOrderRef");
        }

        @Override
        public TradeOrder findByOrderId(long orderId) {
            read("findByOrderId");
            for (TradeOrder o : rows) {
                if (o.orderId != null && o.orderId == orderId) {
                    return o;
                }
            }
            return null;
        }

        @Override
        public List<TradeOrder> findBySubAccountAndStatus(int s, String st) {
            throw FakeDao.unsupported("findBySubAccountAndStatus");
        }
    }

    private static final class FakeTradeReasonDao extends AuditDao<TradeReason, Long>
            implements TradeReasonDao {
        final List<TradeReason> rows = new ArrayList<>();
        private long seq = 200L;

        FakeTradeReasonDao(WriteAudit audit) {
            super(audit, "TradeReasonDao");
        }

        @Override
        public int insert(TradeReason e, long operatorId) {
            wrote("insert");
            e.id = ++seq;
            rows.add(e);
            return 1;
        }

        @Override
        public TradeReason findByTradeOrderId(long orderId) {
            read("findByTradeOrderId");
            for (TradeReason r : rows) {
                if (r.tradeOrderId != null && r.tradeOrderId == orderId) {
                    return r;
                }
            }
            return null;
        }
    }

    private static final class FakeTradeOrderReportDao extends AuditDao<TradeOrderReport, Long>
            implements TradeOrderReportDao {
        final List<TradeOrderReport> rows = new ArrayList<>();

        FakeTradeOrderReportDao(WriteAudit audit) {
            super(audit, "TradeOrderReportDao");
        }

        @Override
        public int insert(TradeOrderReport e, long operatorId) {
            wrote("insert");
            rows.add(e);
            return 1;
        }

        @Override
        public TradeOrderReport findByIdem(String os, String st, int vt) {
            read("findByIdem");
            for (TradeOrderReport r : rows) {
                if (os.equals(r.orderSysId) && st.equals(r.orderStatus)
                        && r.volumeTraded != null && r.volumeTraded == vt) {
                    return r;
                }
            }
            return null;
        }

        @Override
        public List<TradeOrderReport> findByOrder(long orderId) {
            throw FakeDao.unsupported("findByOrder");
        }
    }

    private static final class FakeTradeFillDao extends AuditDao<TradeFill, Long> implements TradeFillDao {
        final List<TradeFill> rows = new ArrayList<>();

        FakeTradeFillDao(WriteAudit audit) {
            super(audit, "TradeFillDao");
        }

        @Override
        public int insert(TradeFill e, long operatorId) {
            wrote("insert");
            rows.add(e);
            return 1;
        }

        @Override
        public TradeFill findByTradeId(String tradeId) {
            read("findByTradeId");
            for (TradeFill f : rows) {
                if (tradeId.equals(f.tradeId)) {
                    return f;
                }
            }
            return null;
        }

        @Override
        public List<TradeFill> findByOrder(long orderId) {
            throw FakeDao.unsupported("findByOrder");
        }
    }

    private static final class FakeTradePositionDao extends AuditDao<TradePosition, Long>
            implements TradePositionDao {
        final Map<String, TradePosition> rows = new LinkedHashMap<>();

        FakeTradePositionDao(WriteAudit audit) {
            super(audit, "TradePositionDao");
        }

        private static String key(int sub, String ins, String dir, String type) {
            return sub + "|" + ins + "|" + dir + "|" + type;
        }

        @Override
        public TradePosition findByKey(int sub, String ins, String dir, String type) {
            read("findByKey");
            return rows.get(key(sub, ins, dir, type));
        }

        @Override
        public int insert(TradePosition e, long operatorId) {
            wrote("insert");
            rows.put(key(e.subAccountId, e.instrumentId, e.direction, e.positionType), e);
            return 1;
        }

        @Override
        public int updateById(TradePosition e, long operatorId) {
            wrote("updateById");
            rows.put(key(e.subAccountId, e.instrumentId, e.direction, e.positionType), e);
            return 1;
        }

        @Override
        public List<TradePosition> findBySubAccount(int sub) {
            throw FakeDao.unsupported("findBySubAccount");
        }
    }

    private static final class FakePreTradeRejectDao extends AuditDao<TradePreTradeReject, Long>
            implements TradePreTradeRejectDao {
        final List<TradePreTradeReject> rows = new ArrayList<>();

        FakePreTradeRejectDao(WriteAudit audit) {
            super(audit, "TradePreTradeRejectDao");
        }

        @Override
        public int insert(TradePreTradeReject e, long operatorId) {
            wrote("insert");
            rows.add(e);
            return 1;
        }

        @Override
        public TradePreTradeReject findByIdem(String ref, String ruleId) {
            read("findByIdem");
            for (TradePreTradeReject r : rows) {
                if (ref.equals(r.clientOrderRef) && ruleId.equals(r.ruleId)) {
                    return r;
                }
            }
            return null;
        }

        @Override
        public List<TradePreTradeReject> findByClientOrderRef(String ref) {
            throw FakeDao.unsupported("findByClientOrderRef");
        }
    }

    private static final class FakeCommandFlowDao extends AuditDao<TradeCommandFlow, Long>
            implements TradeCommandFlowDao {
        final List<TradeCommandFlow> rows = new ArrayList<>();

        FakeCommandFlowDao(WriteAudit audit) {
            super(audit, "TradeCommandFlowDao");
        }

        @Override
        public int insert(TradeCommandFlow e, long operatorId) {
            wrote("insert");
            rows.add(e);
            return 1;
        }

        @Override
        public List<TradeCommandFlow> findByOrderId(long orderId) {
            throw FakeDao.unsupported("findByOrderId");
        }

        @Override
        public List<TradeCommandFlow> findByClientOrderRef(String ref) {
            read("findByClientOrderRef");
            List<TradeCommandFlow> out = new ArrayList<>();
            for (TradeCommandFlow row : rows) {
                if (ref == null ? row.clientOrderRef == null : ref.equals(row.clientOrderRef)) {
                    out.add(row);
                }
            }
            return out;
        }
    }
}
