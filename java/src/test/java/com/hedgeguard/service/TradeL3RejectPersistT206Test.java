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
import com.hedgeguard.dao.impl.DmTradeL3RejectDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-206}（{@code cr-t088} {@code R4-002} 承接）L3 护栏/紧急停止拒绝事件<b>持久化留痕</b>服务层验收。
 *
 * <p><b>真实 DAO + 内存假 JDBC 引擎</b>形态（禁假 DAO 覆盖接线面：留痕写经
 * {@code TradeService} 内由 {@code tx} 派生的 {@link DmTradeL3RejectDao} 落
 * {@code t_trade_l3_reject}，行断言直读 {@code engine.table(...).rows}）。
 * 护栏取数面（CFG/额度/台账）与提交路径其余依赖复用 {@code TradeServiceL3GuardTest} 假件族
 * （{@code NFR-TEST-2} 不引 Mockito）。
 *
 * <p>覆盖：
 * <ul>
 *   <li><b>接线</b>：G-6 子账户级 / G-6 账户级 / G-7 紧急停止三类拒绝各落一行，
 *       行字段与 LOG/响应载荷<b>同源</b>（ruleId/current/threshold 逐字）；</li>
 *   <li><b>响应面零变更守卫</b>：{@code 10012} + {@code ruleId} + 四项载荷键集与取值不变
 *       （工单验收 3：加守卫测试锁键集）；</li>
 *   <li><b>R-FUND-7 语义零改动</b>：受理成功路径零行（提交路径零写不变）；</li>
 *   <li><b>非 L3 拒绝不留痕</b>：④ 计划一致性拒绝等非护栏分支零行（留痕写入方唯一）；</li>
 *   <li><b>落库失败吞并 + LOG</b>：表未建时拒绝结果仍为 {@code 10012} 四项载荷原样
 *       （不把「拒绝」变成「放行」，不吞响应）。</li>
 * </ul>
 */
class TradeL3RejectPersistT206Test {

    private static final LocalDate BASE_DAY = LocalDate.of(2026, 10, 27);
    private static final long TRADER_ID = 1L;
    private static final int SUB_A = 101;
    private static final int SUB_B = 102;
    private static final long PLAN_ID = 9001L;
    private static final long EXPOSURE_ID = 7001L;
    private static final String DDL = "docs/DEPLOY/schema/m2_trade.sql";

    private InMemoryDmEngine engine;
    private InMemoryTradingBridge bridge;
    private FakeConfigItemDao configDao;
    private FakeFundQuotaDao quotaDao;
    private FakeFundLedgerDao ledgerDao;
    private TxTemplate tx;
    private TradeService service;

    private final List<LogRecord> logs = new ArrayList<>();
    private Handler logHandler;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        buildWorld(true);
        Logger svcLogger = Logger.getLogger(TradeService.class.getName());
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
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
        svcLogger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(TradeService.class.getName()).removeHandler(logHandler);
        Clocks.reset();
    }

    /** 组装被测世界；{@code withTable=false} 时跳过建表脚本（落库失败场景用）。 */
    private void buildWorld(boolean withTable) {
        engine = new InMemoryDmEngine();
        if (withTable) {
            loadSchema();
        }
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));

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

        // TradeService 主构造（T-205 扩展签名）；t_trade_l3_reject DAO 由 tx 派生（T-206 接线面）
        service = new TradeService(tx, bridge, authService,
                planDao, hedgePlanDao, exposureDao,
                new FakeTradeOrderDao(audit), new FakeTradeReasonDao(audit),
                new FakeTradeOrderReportDao(audit), new FakeTradeFillDao(audit),
                new FakeTradePositionDao(audit), new FakePreTradeRejectDao(audit),
                new FakeCommandFlowDao(audit),
                quotaDao, ledgerDao, configDao);
    }

    private void loadSchema() {
        try {
            engine.executeScript(Files.readString(repoRoot().resolve(DDL), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("建表脚本执行失败：" + DDL, e);
        }
    }

    private List<Map<String, Object>> l3Rows() {
        InMemoryDmEngine.Table t = engine.table("t_trade_l3_reject");
        assertNotNull(t, "t_trade_l3_reject 应已建表");
        return t.rows;
    }

    private List<String> logLines() {
        List<String> out = new ArrayList<>();
        for (LogRecord r : logs) {
            out.add(String.valueOf(r.getMessage()));
        }
        return out;
    }

    // ================================================================ G-6：子账户级命中

    @Test
    @DisplayName("G-6 子账户级：占用==上限 ⇒ 拒绝（响应四项原样）+ 落 1 行（字段与载荷同源）")
    void fundCapSubLevelRejectionPersistsRow() {
        subQuota(SUB_A, "1000");
        subLedger(SUB_A, "1000");

        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(validReason()), TRADER_ID));

        // 响应面零变更守卫（10012 + ruleId + 四项键集，T-205 值逐字）
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertEquals(10012, e.errorCode().code());
        assertEquals(TradeService.RULE_ID_FUND_CAP, e.ruleId());
        assertEquals("1000", e.current());
        assertEquals("1000", e.threshold());
        assertEquals("下调占用或调整资金额度后重试（本笔未发 CTP）", e.nextStep());
        assertEquals(0, bridge.placedOrders().size(), "不得发 CTP");

        // 持久化留痕：恰 1 行，字段与 LOG/载荷同源
        List<Map<String, Object>> rows = l3Rows();
        assertEquals(1, rows.size(), "L3 拒绝应恰落 1 行：" + rows);
        Map<String, Object> row = rows.get(0);
        assertEquals("L3-FUND-CAP", row.get("rule_id"));
        assertEquals("L3-FUND-CAP", row.get("trigger_source"), "trigger_source 值域=rule_id（工单 T-206）");
        assertEquals(SUB_A, row.get("sub_account_id"), "提交方子账户");
        assertEquals(55L, row.get("product_id"), "请求携带品种应记录");
        assertEquals("SUB_ACCOUNT", row.get("quota_scope"));
        assertEquals("1000", row.get("current_value"), "current=占用值（与载荷同源）");
        assertEquals("1000", row.get("threshold_value"), "threshold=上限值（与载荷同源）");
        assertEquals("PLACE_ORDER", row.get("blocked_operation"));
        assertEquals(TRADER_ID, row.get("operator_id"));
        assertNotNull(row.get("event_time"), "事件时间应落行");
        assertNotNull(row.get("created_time"), "审计 created_time 应落行");
        assertTrue(((Number) row.get("row_version")).intValue() >= 1, "row_version 应 ≥1");
    }

    // ================================================================ G-6：账户级命中

    @Test
    @DisplayName("G-6 账户级：Σ 占用 ≥ 账户额度 ⇒ 落行 quota_scope=ACCOUNT、sub_account_id=提交方、占用/阈值=账户级汇总")
    void fundCapAccountLevelRejectionPersistsRow() {
        subLedger(SUB_A, "200");
        subLedger(SUB_B, "9900");
        accountQuota("10000");

        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(validReason()), TRADER_ID));

        assertEquals(TradeService.RULE_ID_FUND_CAP, e.ruleId());
        assertEquals("10100", e.current(), "账户级 current=Σ margin_occupied");
        assertEquals("10000", e.threshold());

        List<Map<String, Object>> rows = l3Rows();
        assertEquals(1, rows.size(), "账户级命中应恰落 1 行：" + rows);
        Map<String, Object> row = rows.get(0);
        assertEquals("ACCOUNT", row.get("quota_scope"), "维度=ACCOUNT");
        assertEquals(SUB_A, row.get("sub_account_id"), "账户级命中记提交方子账户（列 NOT NULL）");
        assertEquals("10100", row.get("current_value"), "占用为账户级汇总值");
        assertEquals("10000", row.get("threshold_value"));
    }

    // ================================================================ G-7：紧急停止

    @Test
    @DisplayName("G-7：emergency_stop=Y ⇒ 落行 trigger_source=L3-EMERGENCY-STOP、quota_scope=NULL + 响应四项原样")
    void emergencyStopRejectionPersistsRow() {
        configDao.byKey.put(TradeService.CFG_EMERGENCY_STOP, boolItem("Y"));

        BizException e = assertThrows(BizException.class,
                () -> service.submitOrder(request(validReason()), TRADER_ID));

        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertEquals(10012, e.errorCode().code());
        assertEquals(TradeService.RULE_ID_EMERGENCY_STOP, e.ruleId());
        assertEquals("emergency_stop=Y", e.current());
        assertEquals("新提交=全部拒绝", e.threshold());
        assertEquals(0, bridge.placedOrders().size(), "不得发 CTP");

        List<Map<String, Object>> rows = l3Rows();
        assertEquals(1, rows.size(), "紧急停止拒绝应恰落 1 行：" + rows);
        Map<String, Object> row = rows.get(0);
        assertEquals("L3-EMERGENCY-STOP", row.get("rule_id"));
        assertEquals("L3-EMERGENCY-STOP", row.get("trigger_source"));
        assertEquals(SUB_A, row.get("sub_account_id"));
        assertEquals(55L, row.get("product_id"));
        assertNull(row.get("quota_scope"), "紧急停止拒绝 quota_scope 应为 NULL");
        assertEquals("emergency_stop=Y", row.get("current_value"));
        assertEquals("新提交=全部拒绝", row.get("threshold_value"));
        assertEquals("PLACE_ORDER", row.get("blocked_operation"));
    }

    // ================================================================ 零行面

    @Test
    @DisplayName("R-FUND-7 语义零改动：受理成功路径（占用<上限）零行——提交路径零写不变")
    void successPathLeavesNoRow() {
        subQuota(SUB_A, "1000");
        subLedger(SUB_A, "999");

        var result = service.submitOrder(request(validReason()), TRADER_ID);

        assertTrue(result.accepted, "占用低于上限应放行");
        assertEquals(1, bridge.placedOrders().size(), "应经桥接发出 1 笔");
        assertEquals(0, l3Rows().size(), "受理成功路径不得落留痕行");
    }

    @Test
    @DisplayName("写入方唯一：非 L3 拒绝（④ 计划一致性方向不符）零行")
    void nonGuardRejectionLeavesNoRow() {
        TradeService.SubmitRequest req = request(validReason());
        req.direction = "SELL"; // 计划方向 BUY ⇒ ④ 校验拒绝（非护栏分支，位于门禁之后）

        assertThrows(BizException.class, () -> service.submitOrder(req, TRADER_ID));

        assertEquals(0, l3Rows().size(), "非 L3 拒绝不得落留痕行（写入方唯一=L3 拒绝路径）");
    }

    // ================================================================ 落库失败：吞并 + LOG

    @Test
    @DisplayName("落库失败（表未建）⇒ 吞并 + LOG，拒绝结果仍为 10012 四项载荷原样（不改变主流程结果）")
    void persistFailureSwallowedAndLogged() {
        tearDown();
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        buildWorld(false); // 不建表 ⇒ INSERT 必败

        Logger svcLogger = Logger.getLogger(TradeService.class.getName());
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        svcLogger.addHandler(h);
        try {
            subQuota(SUB_A, "1000");
            subLedger(SUB_A, "1000");

            BizException e = assertThrows(BizException.class,
                    () -> service.submitOrder(request(validReason()), TRADER_ID));

            // 拒绝结果零变更（不得把「拒绝」变成「放行」，不得改载荷）
            assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
            assertEquals(10012, e.errorCode().code());
            assertEquals(TradeService.RULE_ID_FUND_CAP, e.ruleId());
            assertEquals("1000", e.current());
            assertEquals("1000", e.threshold());
            assertEquals(0, bridge.placedOrders().size(), "仍不得发 CTP");
            assertTrue(logLines().stream().anyMatch(m -> m.contains("L3 拒绝事件落库失败")
                            && m.contains("rule_id=L3-FUND-CAP")),
                    "落库失败应有 LOG 告警行：" + logLines());
        } finally {
            svcLogger.removeHandler(h);
        }
    }

    // ================================================================ LOG 与库并行

    @Test
    @DisplayName("留痕与既有 LOG 告警并行：拒绝同时产出 LOG 告警行（T-205 形态零改动）")
    void logTraceStillEmittedAlongsideRow() {
        subQuota(SUB_A, "1000");
        subLedger(SUB_A, "1000");

        assertThrows(BizException.class, () -> service.submitOrder(request(validReason()), TRADER_ID));

        assertEquals(1, l3Rows().size());
        assertTrue(logLines().stream().anyMatch(m -> m.contains("L3 资金上限护栏告警[CRITICAL]")
                        && m.contains("blocked_operation=PLACE_ORDER")),
                "T-205 LOG 告警形态应保留（并行）：" + logLines());
    }

    // ================================================================ 播种工具（TradeServiceL3GuardTest 同族）

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

    // ================================================================ 请求/实体夹具（TradeServiceL3GuardTest 同族）

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

    // ================================================================ 假 DAO（逐字复刻 TradeServiceL3GuardTest 假件族）

    /** 写调用审计（提交路径零写断言的等价载体）。 */
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

    /** 配置项假 DAO（仅 findByKey 被护栏驱动）。 */
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

    /** 资金额度假 DAO（子账户/范围两取数面）。 */
    private static final class FakeFundQuotaDao extends UnsupportedBaseDao<FundQuota, Long>
            implements FundQuotaDao {
        final List<FundQuota> rows = new ArrayList<>();

        @Override
        public List<FundQuota> findBySubAccount(int subAccountId) {
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

    /** 资金台账假 DAO（子账户+日期 / 日期两取数面）。 */
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

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根（应含 docs/DATA_MODEL.md），当前目录="
                + Paths.get("").toAbsolutePath() + "；复现=在 java/ 目录下运行 mvn test");
    }
}
