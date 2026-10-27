package com.hedgeguard.service;

import com.hedgeguard.bridge.stub.InMemoryTradingBridge;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ConfigItemDao;
import com.hedgeguard.dao.FundLedgerDao;
import com.hedgeguard.dao.FundQuotaDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.entity.ConfigItem;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.FundLedger;
import com.hedgeguard.dao.entity.FundQuota;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.TradePlan;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.TradeCommandFlowDao;
import com.hedgeguard.dao.TradeFillDao;
import com.hedgeguard.dao.TradeOrderDao;
import com.hedgeguard.dao.TradeOrderReportDao;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.TradePositionDao;
import com.hedgeguard.dao.TradePreTradeRejectDao;
import com.hedgeguard.dao.TradeReasonDao;
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
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-205} L3 实盘护栏服务层单测（T-086 缺口 G-6 资金上限 / G-7 紧急停止）。
 *
 * <p>覆盖工单验收面：
 * <ul>
 *   <li><b>G-6 触发</b>：占用 = 上限（临界）与占用 &gt; 上限（超限）⇒ 拒绝（{@code 10012} +
 *       {@code ruleId=L3-FUND-CAP} 四项载荷）+ 不发 CTP（{@code bridge.placedOrders()=0}）+ LOG 告警留痕
 *       （{@code blocked_operation=PLACE_ORDER}）；</li>
 *   <li><b>G-6 不触发</b>：占用 &lt; 上限 / 无额度配置 ⇒ 放行经桥接；</li>
 *   <li><b>G-6 开关关闭</b>：{@code CFG-34='N'} ⇒ 直通（超限也放行）；</li>
 *   <li><b>G-6 账户级</b>：子账户级未超但 Σ 占用 ≥ 账户级额度 ⇒ 拒绝（§10-35 总账户限额）；</li>
 *   <li><b>G-7 全拒</b>：{@code CFG-35='Y'} ⇒ 全部新提交拒绝（{@code ruleId=L3-EMERGENCY-STOP}）+
 *       不发 CTP；</li>
 *   <li><b>G-7 在途不受影响</b>（{@code R-TRADE-13} 边界）：紧急停止下确认/撤单仍经桥接正常返回
 *       （护栏不含确认/撤单/事件路径的结构性证据）；</li>
 *   <li><b>错误码与留痕断言</b>：锚冻结域字面量；</li>
 *   <li><b>额度面不可用放行</b>：取数异常 ⇒ 视同无有效额度放行（据实披露形态，用例固化防静默收紧）。</li>
 * </ul>
 *
 * <p>桩形态与 {@code TradeServiceTest} 同族（{@code NFR-TEST-2} 不引 Mockito）：护栏取数面以
 * 内存假 DAO 注入（T-205 扩展构造器）；提交路径其余依赖复用记录型假 DAO。告警留痕以
 * {@link Handler} 捕获 {@link TradeService} LOG 断言（LOG 形态留痕的等价断言载体）。
 */
class TradeServiceL3GuardTest {

    private static final LocalDate BASE_DAY = LocalDate.of(2026, 10, 27);
    private static final long TRADER_ID = 1L;
    private static final int SUB_A = 101;
    private static final int SUB_B = 102;
    private static final long PLAN_ID = 9001L;
    private static final long EXPOSURE_ID = 7001L;

    private InMemoryTradingBridge bridge;
    private FakeConfigItemDao configDao;
    private FakeFundQuotaDao quotaDao;
    private FakeFundLedgerDao ledgerDao;
    private TradeService service;

    private final List<LogRecord> guardLogs = new ArrayList<>();
    private Handler guardLogHandler;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));

        FakeUserAccountDao userDao = new FakeUserAccountDao();
        FakeRoleDao roleDao = new FakeRoleDao();
        FakeUserRoleDao userRoleDao = new FakeUserRoleDao();
        FakePermDao permDao = new FakePermDao();
        FakeAttemptDao attemptDao = new FakeAttemptDao();

        userDao.users.put(TRADER_ID, user(TRADER_ID, "N"));
        roleDao.roles.put(10L, role(10L, AuthService.ROLE_TRADER, "Y", "ENABLED"));
        userRoleDao.rows.add(userRole(1L, TRADER_ID, 10L));
        permDao.rows.add(perm(TRADER_ID, SUB_A, "LIMITED"));

        AuthService authService = new AuthService(FakeTx.newTemplate(), userDao, roleDao, userRoleDao,
                null, null, null, null, permDao, attemptDao, null, null, null, null, null);

        WriteAudit audit = new WriteAudit();
        FakeTradePlanDao planDao = new FakeTradePlanDao(audit);
        planDao.plans.put(PLAN_ID, plan(PLAN_ID, SUB_A, EXPOSURE_ID));
        FakeHedgePlanDao hedgePlanDao = new FakeHedgePlanDao();
        hedgePlanDao.plans.put(PLAN_ID, hedgePlan(PLAN_ID, SUB_A));
        FakeExposureDao exposureDao = new FakeExposureDao();
        exposureDao.rows.put(EXPOSURE_ID, exposure(EXPOSURE_ID, SUB_A));

        bridge = new InMemoryTradingBridge();
        configDao = new FakeConfigItemDao();
        quotaDao = new FakeFundQuotaDao();
        ledgerDao = new FakeFundLedgerDao();

        // T-205 扩展构造器：显式注入护栏取数面（假 DAO），其余依赖与 TradeServiceTest 同族
        service = new TradeService(FakeTx.newTemplate(), bridge, authService,
                planDao, hedgePlanDao, exposureDao,
                new FakeTradeOrderDao(audit), new FakeTradeReasonDao(audit),
                new FakeTradeOrderReportDao(audit), new FakeTradeFillDao(audit),
                new FakeTradePositionDao(audit), new FakePreTradeRejectDao(audit),
                new FakeCommandFlowDao(audit),
                quotaDao, ledgerDao, configDao);

        Logger guardLogger = Logger.getLogger(TradeService.class.getName());
        guardLogHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                guardLogs.add(record);
            }

            @Override
            public void flush() {
                // 无缓冲
            }

            @Override
            public void close() {
                // 无资源
            }
        };
        guardLogger.addHandler(guardLogHandler);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(TradeService.class.getName()).removeHandler(guardLogHandler);
        Clocks.reset();
    }

    // ================================================================ G-6：资金上限护栏

    @Test
    @DisplayName("G-6 临界：占用 == 上限 ⇒ 拒绝（10012 + L3-FUND-CAP 四项）+ 不发 CTP + 告警留痕")
    void fundCapBlocksAtExactBoundary() {
        subQuota(SUB_A, "1000");
        subLedger(SUB_A, "1000");

        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(validReason()), TRADER_ID));

        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode(), "应为 10012 阻断类");
        assertEquals(10012, e.errorCode().code());
        assertEquals(TradeService.RULE_ID_FUND_CAP, e.ruleId(), "ruleId 应锚冻结字面量");
        assertEquals("1000", e.current(), "current=占用值");
        assertEquals("1000", e.threshold(), "threshold=上限值");
        assertEquals(0, bridge.placedOrders().size(), "不得发 CTP（TC-M4-25 调用计数==0）");
        assertTrue(alertLogs().stream().anyMatch(m -> m.contains("blocked_operation=PLACE_ORDER")
                        && m.contains("quota_scope=SUB_ACCOUNT")
                        && m.contains("quota_value=1000") && m.contains("occupied_value=1000")),
                "LOG 应含对齐 FundService 告警面字段形态的留痕行："
                        + alertLogs());
    }

    @Test
    @DisplayName("G-6 超限：占用 > 上限 ⇒ 拒绝")
    void fundCapBlocksOverLimit() {
        subQuota(SUB_A, "1000");
        subLedger(SUB_A, "1001");

        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(validReason()), TRADER_ID));

        assertEquals(TradeService.RULE_ID_FUND_CAP, e.ruleId());
        assertEquals("1001", e.current());
        assertEquals(0, bridge.placedOrders().size(), "不得发 CTP");
    }

    @Test
    @DisplayName("G-6 不触发：占用 < 上限 ⇒ 放行经桥接（且提交路径零写：假 DAO 写方法一经调用即失败）")
    void fundCapPassesBelowLimit() {
        subQuota(SUB_A, "1000");
        subLedger(SUB_A, "999");

        var result = service.submitOrder(request(validReason()), TRADER_ID);

        assertTrue(result.accepted, "占用低于上限应放行");
        assertEquals(1, bridge.placedOrders().size(), "应经桥接发出 1 笔");
    }

    @Test
    @DisplayName("G-6 开关关闭（CFG-34=N）⇒ 直通：占用超限也放行（显式可关断路径）")
    void fundCapDisabledSwitchPassesThrough() {
        configDao.byKey.put(TradeService.CFG_FUND_CAP_ENABLED, boolItem("N"));
        subQuota(SUB_A, "1000");
        subLedger(SUB_A, "5000");

        var result = service.submitOrder(request(validReason()), TRADER_ID);

        assertTrue(result.accepted, "开关关闭应直通");
        assertEquals(1, bridge.placedOrders().size(), "直通路径应发 CTP");
    }

    @Test
    @DisplayName("G-6 账户级：子账户级未超但 Σ 占用 ≥ 账户级额度 ⇒ 拒绝（§10-35 总账户限额生效）")
    void accountLevelCapBlocksWhenSubLevelClears() {
        // 子账户级：SUB_A 额度 1000，占用 200 ⇒ 未超
        subQuota(SUB_A, "1000");
        subLedger(SUB_A, "200");
        // 另一子账户台账 9900（无额度行）⇒ 账户级 Σ = 10100 ≥ 账户级额度 10000 ⇒ 拒绝
        subLedger(SUB_B, "9900");
        accountQuota("10000");

        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(validReason()), TRADER_ID));

        assertEquals(TradeService.RULE_ID_FUND_CAP, e.ruleId());
        assertEquals("10100", e.current(), "账户级 current=Σ margin_occupied");
        assertEquals("10000", e.threshold());
        assertEquals(0, bridge.placedOrders().size(), "不得发 CTP");
        assertTrue(alertLogs().stream().anyMatch(m -> m.contains("quota_scope=ACCOUNT")),
                "LOG 应标明账户级维度：" + alertLogs());
    }

    @Test
    @DisplayName("G-6 无额度配置 ⇒ 惰性放行（无阈值可执行；FundService 缺额度同姿态）")
    void fundCapInertWithoutQuota() {
        subLedger(SUB_A, "999999");

        var result = service.submitOrder(request(validReason()), TRADER_ID);

        assertTrue(result.accepted, "无有效额度应放行");
        assertEquals(1, bridge.placedOrders().size());
    }

    @Test
    @DisplayName("G-6 额度面取数异常 ⇒ 视同无有效额度放行（据实披露形态；C++ 额度层独立存在）")
    void fundCapOpensOnQuotaSourceFailure() {
        quotaDao.throwOnRead = true;

        var result = service.submitOrder(request(validReason()), TRADER_ID);

        assertTrue(result.accepted, "额度面不可用应放行（披露形态，防静默收紧）");
        assertEquals(1, bridge.placedOrders().size());
    }

    // ================================================================ G-7：紧急停止

    @Test
    @DisplayName("G-7 全拒：emergency_stop=Y ⇒ 全部新提交拒绝（10012 + L3-EMERGENCY-STOP）+ 不发 CTP")
    void emergencyStopRejectsAllNewSubmissions() {
        configDao.byKey.put(TradeService.CFG_EMERGENCY_STOP, boolItem("Y"));

        for (int i = 0; i < 2; i++) {
            BizException e = assertThrows(BizException.class,
                    () -> service.submitOrder(request(validReason()), TRADER_ID));
            assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
            assertEquals(TradeService.RULE_ID_EMERGENCY_STOP, e.ruleId(), "ruleId 应锚冻结字面量");
            assertEquals("emergency_stop=Y", e.current());
            assertEquals("新提交=全部拒绝", e.threshold());
        }
        assertEquals(0, bridge.placedOrders().size(), "紧急停止下不得有任何新提交发往 CTP");
        assertTrue(alertLogs().stream().anyMatch(m -> m.contains("emergency_stop=Y")
                        && m.contains("blocked_operation=PLACE_ORDER")),
                "LOG 应含紧急停止告警留痕：" + alertLogs());
    }

    @Test
    @DisplayName("G-7 在途不受影响（R-TRADE-13）：紧急停止下确认/撤单仍经桥接正常返回")
    void emergencyStopLeavesInFlightUntouched() {
        configDao.byKey.put(TradeService.CFG_EMERGENCY_STOP, boolItem("Y"));

        // 确认与撤单是「在途指令的既有流程」：门禁不含此二路径（结构性边界）
        var ack = service.confirmOrder(4242L, TRADER_ID, "token-4242");
        assertNotNull(ack, "确认路径应不受紧急停止影响");
        var cancelAck = service.cancelOrder(4242L, "紧急停止前在途指令撤单", TRADER_ID);
        assertNotNull(cancelAck, "撤单路径应不受紧急停止影响");

        // 新提交仍全拒
        assertThrows(BizException.class, () -> service.submitOrder(request(validReason()), TRADER_ID));
        assertEquals(0, bridge.placedOrders().size(), "新提交仍不得发 CTP");
    }

    @Test
    @DisplayName("G-7 缺省：缺键 ⇒ 不停止（CFG-35 缺省 N，正常受理）")
    void emergencyStopDefaultsOff() {
        assertFalse(configDao.byKey.containsKey(TradeService.CFG_EMERGENCY_STOP),
                "前置：不播种紧急停止键");

        var result = service.submitOrder(request(validReason()), TRADER_ID);

        assertTrue(result.accepted, "缺省应正常受理");
        assertEquals(1, bridge.placedOrders().size());
    }

    // ================================================================ 留痕断言工具

    /** 告警留痕行（LOG 形态·对齐 FundService 告警面字段形态）。 */
    private List<String> alertLogs() {
        List<String> out = new ArrayList<>();
        for (LogRecord r : guardLogs) {
            out.add(String.valueOf(r.getMessage()));
        }
        return out;
    }

    // ================================================================ 播种工具

    private static ConfigItem boolItem(String value) {
        ConfigItem item = new ConfigItem();
        item.configKey = "probe";
        item.configCategory = "RISK_PARAM";
        item.configValue = value;
        item.valueType = "BOOL";
        return item;
    }

    private void subQuota(int subAccountId, String value) {
        FundQuota q = new FundQuota();
        q.subAccountId = subAccountId;
        q.quotaScope = FundService.SCOPE_SUB;
        q.quotaValue = new BigDecimal(value);
        q.effectiveFrom = BASE_DAY.minusDays(1);
        quotaDao.rows.add(q);
    }

    private void accountQuota(String value) {
        FundQuota q = new FundQuota();
        q.subAccountId = null;
        q.quotaScope = FundService.SCOPE_ACCOUNT;
        q.quotaValue = new BigDecimal(value);
        q.effectiveFrom = BASE_DAY.minusDays(1);
        quotaDao.rows.add(q);
    }

    private void subLedger(int subAccountId, String marginOccupied) {
        FundLedger l = new FundLedger();
        l.subAccountId = subAccountId;
        l.ledgerDate = BASE_DAY;
        l.marginOccupied = new BigDecimal(marginOccupied);
        ledgerDao.rows.add(l);
    }

    // ================================================================ 请求/实体夹具（TradeServiceTest 同族）

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
        p.tradePlanNo = "TP2026102700001";
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
        p.planNo = "HP2026102700001";
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

    // ================================================================ 假 DAO（逐字复刻 TradeServiceTest 假件族；写一经调用即审计留痕）

    /** 写调用审计（TradeServiceTest.WriteAudit 同形）：提交路径零写断言的等价载体。 */
    static final class WriteAudit {
        final List<String> writes = new ArrayList<>();
        final List<String> reads = new ArrayList<>();
    }

    private abstract static class AuditDao<T, ID> extends UnsupportedBaseDao<T, ID> {
        final WriteAudit audit;
        final String owner;

        AuditDao(WriteAudit audit, String owner) {
            this.audit = audit;
            this.owner = owner;
        }

        void wrote(String method) {
            audit.writes.add(owner + "." + method);
        }

        void read(String method) {
            audit.reads.add(owner + "." + method);
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
            return 1;
        }

        @Override
        public TradePlan findByTradePlanNo(String no) {
            throw FakeDao.unsupported("findByTradePlanNo");
        }

        @Override
        public String findMaxTradePlanNoByPrefix(String prefix) {
            throw FakeDao.unsupported("findMaxTradePlanNoByPrefix");
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
        public int updateCurrentDocVersion(long planId, int version, long operatorId) {
            throw FakeDao.unsupported("updateCurrentDocVersion");
        }

        @Override
        public int deleteById(Long id) {
            throw FakeDao.unsupported("deleteById");
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

    private static final class FakeTradeOrderDao extends AuditDao<com.hedgeguard.dao.entity.TradeOrder, Long>
            implements TradeOrderDao {

        FakeTradeOrderDao(WriteAudit audit) {
            super(audit, "TradeOrderDao");
        }

        @Override
        public com.hedgeguard.dao.entity.TradeOrder findByClientOrderRef(String ref) {
            throw FakeDao.unsupported("findByClientOrderRef");
        }

        @Override
        public com.hedgeguard.dao.entity.TradeOrder findByOrderId(long orderId) {
            throw FakeDao.unsupported("findByOrderId");
        }

        @Override
        public List<com.hedgeguard.dao.entity.TradeOrder> findBySubAccountAndStatus(int s, String st) {
            throw FakeDao.unsupported("findBySubAccountAndStatus");
        }

        @Override
        public List<com.hedgeguard.dao.entity.TradeOrder> findByHedgePlan(long hedgePlanId) {
            throw FakeDao.unsupported("findByHedgePlan");
        }
    }

    private static final class FakeTradeReasonDao extends AuditDao<com.hedgeguard.dao.entity.TradeReason, Long>
            implements TradeReasonDao {

        FakeTradeReasonDao(WriteAudit audit) {
            super(audit, "TradeReasonDao");
        }

        @Override
        public com.hedgeguard.dao.entity.TradeReason findByTradeOrderId(long orderId) {
            throw FakeDao.unsupported("findByTradeOrderId");
        }
    }

    private static final class FakeTradeOrderReportDao
            extends AuditDao<com.hedgeguard.dao.entity.TradeOrderReport, Long>
            implements TradeOrderReportDao {

        FakeTradeOrderReportDao(WriteAudit audit) {
            super(audit, "TradeOrderReportDao");
        }

        @Override
        public com.hedgeguard.dao.entity.TradeOrderReport findByIdem(String os, String st, int vt) {
            throw FakeDao.unsupported("findByIdem");
        }

        @Override
        public List<com.hedgeguard.dao.entity.TradeOrderReport> findByOrder(long orderId) {
            throw FakeDao.unsupported("findByOrder");
        }
    }

    private static final class FakeTradeFillDao extends AuditDao<com.hedgeguard.dao.entity.TradeFill, Long>
            implements TradeFillDao {

        FakeTradeFillDao(WriteAudit audit) {
            super(audit, "TradeFillDao");
        }

        @Override
        public com.hedgeguard.dao.entity.TradeFill findByTradeId(String tradeId) {
            throw FakeDao.unsupported("findByTradeId");
        }

        @Override
        public List<com.hedgeguard.dao.entity.TradeFill> findByOrder(long orderId) {
            throw FakeDao.unsupported("findByOrder");
        }
    }

    private static final class FakeTradePositionDao
            extends AuditDao<com.hedgeguard.dao.entity.TradePosition, Long>
            implements TradePositionDao {

        FakeTradePositionDao(WriteAudit audit) {
            super(audit, "TradePositionDao");
        }

        @Override
        public com.hedgeguard.dao.entity.TradePosition findByKey(int sub, String ins, String dir, String type) {
            throw FakeDao.unsupported("findByKey");
        }

        @Override
        public List<com.hedgeguard.dao.entity.TradePosition> findBySubAccount(int sub) {
            throw FakeDao.unsupported("findBySubAccount");
        }
    }

    private static final class FakePreTradeRejectDao
            extends AuditDao<com.hedgeguard.dao.entity.TradePreTradeReject, Long>
            implements TradePreTradeRejectDao {

        FakePreTradeRejectDao(WriteAudit audit) {
            super(audit, "TradePreTradeRejectDao");
        }

        @Override
        public com.hedgeguard.dao.entity.TradePreTradeReject findByIdem(String ref, String ruleId) {
            throw FakeDao.unsupported("findByIdem");
        }

        @Override
        public List<com.hedgeguard.dao.entity.TradePreTradeReject> findByClientOrderRef(String ref) {
            throw FakeDao.unsupported("findByClientOrderRef");
        }
    }

    private static final class FakeCommandFlowDao
            extends AuditDao<com.hedgeguard.dao.entity.TradeCommandFlow, Long>
            implements TradeCommandFlowDao {

        FakeCommandFlowDao(WriteAudit audit) {
            super(audit, "TradeCommandFlowDao");
        }

        @Override
        public List<com.hedgeguard.dao.entity.TradeCommandFlow> findByOrderId(long orderId) {
            throw FakeDao.unsupported("findByOrderId");
        }

        @Override
        public List<com.hedgeguard.dao.entity.TradeCommandFlow> findByClientOrderRef(String ref) {
            throw FakeDao.unsupported("findByClientOrderRef");
        }
    }

    /** T-205 护栏：配置项假 DAO（仅 findByKey 被护栏驱动）。 */
    private static final class FakeConfigItemDao extends UnsupportedBaseDao<ConfigItem, Long>
            implements ConfigItemDao {
        final Map<String, ConfigItem> byKey = new LinkedHashMap<>();

        @Override
        public ConfigItem findByKey(String configKey) {
            return byKey.get(configKey);
        }

        @Override
        public List<ConfigItem> findByCategory(String category) {
            throw FakeDao.unsupported("findByCategory");
        }

        @Override
        public List<ConfigItem> findDispatchable() {
            throw FakeDao.unsupported("findDispatchable");
        }

        @Override
        public int bumpEffectiveVersion(String configKey) {
            throw FakeDao.unsupported("bumpEffectiveVersion");
        }
    }

    /** T-205 护栏：资金额度假 DAO（子账户/范围两取数面）。 */
    private static final class FakeFundQuotaDao extends UnsupportedBaseDao<FundQuota, Long>
            implements FundQuotaDao {
        final List<FundQuota> rows = new ArrayList<>();
        boolean throwOnRead;

        @Override
        public List<FundQuota> findBySubAccount(int subAccountId) {
            if (throwOnRead) {
                throw new IllegalStateException("额度面不可用（注入）");
            }
            List<FundQuota> out = new ArrayList<>();
            for (FundQuota q : rows) {
                if (q.subAccountId != null && q.subAccountId.intValue() == subAccountId) {
                    out.add(q);
                }
            }
            return out;
        }

        @Override
        public List<FundQuota> findByScope(String quotaScope) {
            if (throwOnRead) {
                throw new IllegalStateException("额度面不可用（注入）");
            }
            List<FundQuota> out = new ArrayList<>();
            for (FundQuota q : rows) {
                if (quotaScope.equals(q.quotaScope)) {
                    out.add(q);
                }
            }
            return out;
        }

        @Override
        public List<FundQuota> findScoped(String quotaScope, SubAccountScope scope) {
            throw FakeDao.unsupported("findScoped");
        }
    }

    /** T-205 护栏：资金台账假 DAO（子账户+日期 / 日期两取数面）。 */
    private static final class FakeFundLedgerDao extends UnsupportedBaseDao<FundLedger, Long>
            implements FundLedgerDao {
        final List<FundLedger> rows = new ArrayList<>();

        @Override
        public FundLedger findBySubAccountAndDate(int subAccountId, LocalDate ledgerDate) {
            for (FundLedger l : rows) {
                if (l.subAccountId != null && l.subAccountId.intValue() == subAccountId
                        && ledgerDate.equals(l.ledgerDate)) {
                    return l;
                }
            }
            return null;
        }

        @Override
        public List<FundLedger> findByDate(LocalDate ledgerDate) {
            List<FundLedger> out = new ArrayList<>();
            for (FundLedger l : rows) {
                if (ledgerDate.equals(l.ledgerDate)) {
                    out.add(l);
                }
            }
            return out;
        }
    }
}
