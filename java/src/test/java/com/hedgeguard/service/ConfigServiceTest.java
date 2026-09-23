package com.hedgeguard.service;

import com.hedgeguard.dao.ConfigChangeLogDao;
import com.hedgeguard.dao.ConfigItemDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountDao;
import com.hedgeguard.dao.entity.ConfigChangeLog;
import com.hedgeguard.dao.entity.ConfigItem;
import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.service.impl.UnavailableSubAccountDisablePrecheck;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.handler.config.SubAccountHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigService} 单元测试（{@code T-008} 子账户全生命周期 + {@code T-009} 系统配置与零落盘约束）。
 *
 * <p>覆盖：正常路径 / 边界 / 异常路径，以及两条"结构性"断言：
 * ①敏感取值<b>无法</b>经由本服务落库（{@code 10020}，且留痕与实体均无值）；
 * ②本服务<b>不存在</b>任何物理删除方法（{@code CFG-10} {@code DISABLE_ONLY}）。
 *
 * <p>桩实现为内存假 DAO（{@code NFR-TEST-2}：不引 Mockito）；事务经 {@link FakeTx} 的真
 * {@code TxTemplate} 驱动，故"改值 + 留痕同事务"的边界也被真实执行。
 */
class ConfigServiceTest {

    private FakeSubAccountDao subAccountDao;
    private FakeConfigItemDao configItemDao;
    private FakeConfigChangeLogDao changeLogDao;
    private ConfigService service;

    @BeforeEach
    void setUp() {
        subAccountDao = new FakeSubAccountDao();
        configItemDao = new FakeConfigItemDao();
        changeLogDao = new FakeConfigChangeLogDao();
        service = new ConfigService(FakeTx.newTemplate(), subAccountDao, configItemDao, changeLogDao);
    }

    // ------------------------------------------------------------ T-009 零落盘

    @Test
    @DisplayName("敏感字段零落盘：变更取值被拒 10020，且实体与留痕均无值")
    void sensitiveValueIsRejectedAndNothingPersisted() {
        ConfigItem sensitive = sensitiveItem("hg.ctp.password");
        configItemDao.store(sensitive);

        BizException e = assertThrows(BizException.class,
                () -> service.changeConfigValue("hg.ctp.password", "P@ssw0rd!", 9L, null, "误操作"));
        assertEquals(ErrorCode.SENSITIVE_FIELD_REJECTED, e.errorCode(), "应返回 10020");
        assertNull(configItemDao.findByKey("hg.ctp.password").configValue, "取值不得落库");
        assertTrue(changeLogDao.logs.isEmpty(), "被拒的变更不得留痕（事务未执行）");
        assertFalse(e.getMessage().contains("P@ssw0rd!"), "错误信息不得回显敏感取值");
    }

    @Test
    @DisplayName("敏感字段零落盘：新增配置项时携带取值被拒 10020")
    void sensitiveCreateWithValueIsRejected() {
        ConfigItem item = sensitiveItem("hg.ctp.auth_code");
        item.configValue = "AUTHCODE-1";
        BizException e = assertThrows(BizException.class, () -> service.createConfigItem(item, 1L));
        assertEquals(ErrorCode.SENSITIVE_FIELD_REJECTED, e.errorCode());
        assertNull(configItemDao.findByKey("hg.ctp.auth_code"), "不得落库");
    }

    @Test
    @DisplayName("敏感项只能改状态：SET 留痕只记状态不记取值（R-CONFIG-2）")
    void sensitiveStateChangeIsLoggedWithoutValue() {
        ConfigItem item = sensitiveItem("hg.fund.fund_password");
        item.sensitiveState = "UNSET";
        configItemDao.store(item);

        service.changeSensitiveState("hg.fund.fund_password", "SET", 7L, "运维配置完成");

        assertEquals("SET", configItemDao.findByKey("hg.fund.fund_password").sensitiveState);
        assertEquals(1, changeLogDao.logs.size(), "应留痕一条");
        ConfigChangeLog log = changeLogDao.logs.get(0);
        assertEquals("Y", log.isSensitiveChange, "标记为敏感变更");
        assertEquals("UNSET", log.sensitiveStateBefore);
        assertEquals("SET", log.sensitiveStateAfter);
        assertNull(log.beforeValue, "敏感变更前后值必须为 NULL");
        assertNull(log.afterValue, "敏感变更前后值必须为 NULL");
        assertNull(configItemDao.findByKey("hg.fund.fund_password").configValue, "仍无取值");
    }

    @Test
    @DisplayName("绑定地址回环白名单：非 127.0.0.1 一律 10021（启动期与变更期同一入口）")
    void loopbackBindWhitelistIsEnforced() {
        assertEquals("127.0.0.1", ConfigService.LOOPBACK_ONLY);
        ConfigService.requireLoopbackBind("hg.tcp.bind", "127.0.0.1");
        ConfigService.requireLoopbackBind("hg.web.port", "0.0.0.0");   // 非绑定键 → 放行

        BizException e = assertThrows(BizException.class,
                () -> ConfigService.requireLoopbackBind("hg.tcp.bind", "0.0.0.0"));
        assertEquals(ErrorCode.SECURITY_CONFIG_REJECTED, e.errorCode(), "应返回 10021");

        ConfigItem bind = item("hg.web.bind", "STRING", null, null);
        bind.configValue = "127.0.0.1";
        configItemDao.store(bind);
        BizException e2 = assertThrows(BizException.class,
                () -> service.changeConfigValue("hg.web.bind", "192.168.1.10", 3L, null, "改绑定"));
        assertEquals(ErrorCode.SECURITY_CONFIG_REJECTED, e2.errorCode(), "变更期同样拒绝");
        assertEquals("127.0.0.1", configItemDao.findByKey("hg.web.bind").configValue, "取值不得被改写");
    }

    // ------------------------------------------------------------ T-009 取值校验与留痕

    @Test
    @DisplayName("取值校验：DECIMAL 上下界、BOOL 取值域、ENUM 取值域各返回 10001")
    void valueValidationByTypeAndScope() {
        ConfigItem decimal = item("hg.risk.ratio", "DECIMAL", new BigDecimal("0.1"),
                new BigDecimal("0.9"));
        configItemDao.store(decimal);
        assertThrows(BizException.class,
                () -> service.changeConfigValue("hg.risk.ratio", "0.05", 1L, null, "过低"));
        assertThrows(BizException.class,
                () -> service.changeConfigValue("hg.risk.ratio", "0.95", 1L, null, "过高"));
        assertThrows(BizException.class,
                () -> service.changeConfigValue("hg.risk.ratio", "0.1234567", 1L, null, "精度超 6 位"));
        service.changeConfigValue("hg.risk.ratio", "0.8", 1L, null, "正常");
        assertEquals("0.8", configItemDao.findByKey("hg.risk.ratio").configValue);

        ConfigItem bool = item("hg.trade.suspend", "BOOL", null, null);
        configItemDao.store(bool);
        assertThrows(BizException.class,
                () -> service.changeConfigValue("hg.trade.suspend", "true", 1L, null, "非法 BOOL"));
        service.changeConfigValue("hg.trade.suspend", "Y", 1L, null, "正常");

        ConfigItem en = item("hg.risk.scale_base_mode", "ENUM", null, null);
        en.enumScope = "COMMODITY,TRADE";
        configItemDao.store(en);
        assertThrows(BizException.class,
                () -> service.changeConfigValue("hg.risk.scale_base_mode", "OTHER", 1L, null, "越域"));
        service.changeConfigValue("hg.risk.scale_base_mode", "COMMODITY", 1L, null, "正常");
    }

    @Test
    @DisplayName("变更留痕：记录前后值、操作人、复核人与下发结果；需下发项记 UNCONFIRMED（R-CONFIG-3）")
    void changeWritesBeforeAfterAndDispatchResult() {
        ConfigItem dispatch = item("hg.risk.threshold", "DECIMAL", null, null);
        dispatch.dispatchToCtp = "Y";
        dispatch.configValue = "0.5";
        configItemDao.store(dispatch);

        service.changeConfigValue("hg.risk.threshold", "0.6", 11L, null, "季节性调整");

        assertEquals("0.6", configItemDao.findByKey("hg.risk.threshold").configValue);
        assertEquals(1L, configItemDao.findByKey("hg.risk.threshold").effectiveVersion,
                "effective_version 应递增（param_version）");
        ConfigChangeLog log = changeLogDao.logs.get(changeLogDao.logs.size() - 1);
        assertEquals("0.5", log.beforeValue, "留痕须含变更前值");
        assertEquals("0.6", log.afterValue, "留痕须含变更后值");
        assertEquals(11L, log.operatorId);
        assertEquals("UNCONFIRMED", log.dispatchResult,
                "M1 无 HGCP 通道 → 未收到回执，不得视为已生效");
        assertEquals(Long.valueOf(1L), log.dispatchParamVersion, "留痕须含下发所用 param_version");
        assertNotNull(log.changeTime, "变更时间经时钟写入");
    }

    @Test
    @DisplayName("双人复核项：复核人缺失或等于操作人 → 10005；补齐后放行")
    void doubleReviewIsEnforced() {
        ConfigItem item = item("hg.risk.relax_redline", "DECIMAL", null, null);
        item.doubleReviewRequired = "Y";
        configItemDao.store(item);

        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class,
                        () -> service.changeConfigValue("hg.risk.relax_redline", "0.9", 5L, null, "放宽"))
                        .errorCode());
        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class,
                        () -> service.changeConfigValue("hg.risk.relax_redline", "0.9", 5L, 5L, "放宽"))
                        .errorCode());
        service.changeConfigValue("hg.risk.relax_redline", "0.9", 5L, 6L, "放宽");
        assertEquals(Long.valueOf(6L), changeLogDao.logs.get(changeLogDao.logs.size() - 1).reviewerId,
                "复核人须留痕");
    }

    @Test
    @DisplayName("配置项不存在 → 10001；重复新增 → 10010")
    void missingItemAndDuplicateKey() {
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.changeConfigValue("hg.unknown.key", "1", 1L, null, "x")).errorCode());

        ConfigItem first = item("hg.web.port", "INT", null, null);
        service.createConfigItem(first, 1L);
        assertEquals(ErrorCode.UNIQUE_CONFLICT,
                assertThrows(BizException.class,
                        () -> service.createConfigItem(item("hg.web.port", "INT", null, null), 1L))
                        .errorCode());
    }

    // ------------------------------------------------------------ T-008 子账户生命周期

    @Test
    @DisplayName("创建子账户：强制 ENABLED、留痕 lifecycle、重复编码 10010、非法分类 10001")
    void createSubAccountForcesEnabledAndLogs() {
        SubAccount sa = subAccount("SA-TRADE-01", "贸易一账", "TRADE", "DISABLED");
        long id = service.createSubAccount(sa, 1L);
        assertEquals(1L, id);
        assertEquals("ENABLED", subAccountDao.findById(1).status, "新建一律 ENABLED");
        assertEquals(1, changeLogDao.logs.size());
        ConfigChangeLog log = changeLogDao.logs.get(0);
        assertEquals(ConfigService.KEY_SUB_ACCOUNT_LIFECYCLE, log.configKey);
        assertEquals(ConfigService.CATEGORY_SUB_ACCOUNT, log.configCategory);
        assertTrue(log.changeReason.contains("SA-TRADE-01"), "留痕须可定位到子账户");

        assertEquals(ErrorCode.UNIQUE_CONFLICT,
                assertThrows(BizException.class,
                        () -> service.createSubAccount(subAccount("SA-TRADE-01", "重名", "TRADE", null), 1L))
                        .errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.createSubAccount(subAccount("SA-X", "X", "UNKNOWN", null), 1L))
                        .errorCode());
    }

    @Test
    @DisplayName("停用门禁：四条件任一不满足 → 阻断 10012 且四项齐备，状态与留痕均不变")
    void disableIsBlockedWhenPrecheckFails() {
        service.createSubAccount(subAccount("SA-COMM-01", "商品一账", "COMMODITY", null), 1L);
        int logsBefore = changeLogDao.logs.size();

        BizException e = assertThrows(BizException.class,
                () -> service.disableSubAccount(1, "业务结束", precheck(false, true, true, true), 1L));

        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode(), "应返回 10012");
        assertTrue(ErrorCode.COMPLIANCE_GATE_FAILED.thresholdBlocking(), "该码为阻断类");
        assertEquals("CFG-10", e.ruleId(), "规则编号须为 CFG-10（子账户生命周期纪律）");
        assertNotNull(e.current(), "current 必填");
        assertNotNull(e.threshold(), "threshold 必填");
        assertNotNull(e.nextStep(), "nextStep 必填");
        assertTrue(e.current().contains("资金清零=false"), "current 须说明哪条不满足：" + e.current());
        assertEquals("ENABLED", subAccountDao.findById(1).status, "被拒后状态不得改变");
        assertEquals(logsBefore, changeLogDao.logs.size(), "被拒后不得留痕");
    }

    @Test
    @DisplayName("停用成功：DISABLE_ONLY 下线、留痕含四条预检结果、行保留（无物理删除）")
    void disableSucceedsAndKeepsRow() {
        service.createSubAccount(subAccount("SA-COMM-02", "商品二账", "COMMODITY", null), 1L);
        service.disableSubAccount(1, "业务结束", precheck(true, true, true, true), 1L);

        SubAccount disabled = subAccountDao.findById(1);
        assertEquals("DISABLED", disabled.status);
        assertEquals("业务结束", disabled.disabledReason, "停用原因须落库");
        assertNotNull(disabled.disablePrecheckJson, "四条件判定结果须落库供审计复现");
        for (String key : Arrays.asList("fundIsZero", "positionsAreZero", "noInFlightOrders",
                "settledToday")) {
            assertTrue(disabled.disablePrecheckJson.contains(key), "预检明细缺少 " + key);
        }
        assertNotNull(disabled, "停用后行必须保留（历史数据永久只读且仍参与加总对账）");
        assertEquals(1, subAccountDao.findById(1).id, "按 id 仍可读——行未被删除");
        assertTrue(service.listEnabled().isEmpty(), "停用后不再出现在启用集合（加总取启用全集）");
        assertEquals(ConfigService.KEY_SUB_ACCOUNT_LIFECYCLE,
                changeLogDao.logs.get(changeLogDao.logs.size() - 1).configKey);

        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.disableSubAccount(1, "重复停用",
                                precheck(true, true, true, true), 1L)).errorCode());
    }

    @Test
    @DisplayName("停用不入参：原因必填（10001）；子账户不存在（10001）")
    void disableRequiresReasonAndExistingAccount() {
        service.createSubAccount(subAccount("SA-COMM-03", "商品三账", "COMMODITY", null), 1L);
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.disableSubAccount(1, "  ", precheck(true, true, true, true), 1L))
                        .errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.disableSubAccount(99, "不存在",
                                precheck(true, true, true, true), 1L)).errorCode());
    }

    @Test
    @DisplayName("T-135 FR-CONFIG-7 验收3：PUT /config/sub-account {subAccountId, reason} 经 handler 接线停用")
    void disableEndpointWiredViaHandler() {
        service.createSubAccount(subAccount("SA-T135-01", "停用接线", "COMMODITY", null), 1L);
        SubAccountHandler handler = new SubAccountHandler(service, precheck(true, true, true, true));
        HttpResult r = handler.handle(new RequestContext("PUT", SubAccountHandler.PATH, null,
                java.util.Collections.emptyMap(),
                "{\"subAccountId\":1,\"reason\":\"业务结束\"}", "HGT135", "127.0.0.1"));
        assertEquals(200, r.status);
        assertEquals("DISABLED", subAccountDao.findById(1).status,
                "PUT 须接线到 disableSubAccount（此前生产 0 调用）");
    }

    @Test
    @DisplayName("T-135 FR-CONFIG-7 验收3：停用条件不满足经 handler 仍阻断 10012，状态不变")
    void disableEndpointBlocksWhenPrecheckFails() {
        service.createSubAccount(subAccount("SA-T135-02", "停用阻断", "COMMODITY", null), 1L);
        SubAccountHandler handler = new SubAccountHandler(service, precheck(false, true, true, true));
        BizException e = assertThrows(BizException.class, () -> handler.handle(
                new RequestContext("PUT", SubAccountHandler.PATH, null, java.util.Collections.emptyMap(),
                        "{\"subAccountId\":1,\"reason\":\"业务结束\"}", "HGT135", "127.0.0.1")));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode());
        assertEquals("ENABLED", subAccountDao.findById(1).status, "被拒后状态不得改变");
    }

    @Test
    @DisplayName("T-135：M1 停用前置端口不可用 ⇒ 10002 且不改变状态（诚实失败，不伪报成功）")
    void m1PrecheckUnavailableFailsHonestly() {
        service.createSubAccount(subAccount("SA-T135-03", "M1 不可用", "COMMODITY", null), 1L);
        SubAccountHandler handler = new SubAccountHandler(service,
                new UnavailableSubAccountDisablePrecheck("FUND/TRADE/SETTLE（M1 未交付）"));
        BizException e = assertThrows(BizException.class, () -> handler.handle(
                new RequestContext("PUT", SubAccountHandler.PATH, null, java.util.Collections.emptyMap(),
                        "{\"subAccountId\":1,\"reason\":\"业务结束\"}", "HGT135", "127.0.0.1")));
        assertEquals(ErrorCode.COMPONENT_UNAVAILABLE, e.errorCode(), "应返回 10002");
        assertTrue(e.getMessage().contains("FUND"), "错误信息须点名缺失数据源：" + e.getMessage());
        assertEquals("ENABLED", subAccountDao.findById(1).status, "不得改状态");
    }

    @Test
    @DisplayName("T-135：未装配停用端口 / 缺参 ⇒ 明确失败（不静默放行）")
    void disableEndpointRejectsWhenUnwiredOrMalformed() {
        SubAccountHandler unwired = new SubAccountHandler(service);
        assertEquals(ErrorCode.COMPONENT_UNAVAILABLE,
                assertThrows(BizException.class, () -> unwired.handle(
                        new RequestContext("PUT", SubAccountHandler.PATH, null,
                                java.util.Collections.emptyMap(),
                                "{\"subAccountId\":1,\"reason\":\"x\"}", "T", "127.0.0.1"))).errorCode());
        SubAccountHandler handler = new SubAccountHandler(service, precheck(true, true, true, true));
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> handler.handle(
                        new RequestContext("PUT", SubAccountHandler.PATH, null,
                                java.util.Collections.emptyMap(), "{}", "T", "127.0.0.1"))).errorCode());
    }

    @Test
    @DisplayName("分类变更：须双人复核（发起人≠复核人）且留痕到 sub_account.category（CFG-02c）")
    void categoryChangeRequiresDoubleReviewAndLogs() {
        service.createSubAccount(subAccount("SA-COMM-04", "商品四账", "COMMODITY", null), 1L);
        int logsBefore = changeLogDao.logs.size();

        assertEquals(ErrorCode.POSITION_SEPARATION_CONFLICT,
                assertThrows(BizException.class,
                        () -> service.changeCategory(1, "TRADE", 4L, 4L, "分类订正")).errorCode());
        assertEquals("COMMODITY", subAccountDao.findById(1).category, "被拒后分类不得改变");
        assertEquals(logsBefore, changeLogDao.logs.size(), "被拒后不得留痕");

        service.changeCategory(1, "TRADE", 4L, 5L, "经营范围调整");

        SubAccount changed = subAccountDao.findById(1);
        assertEquals("TRADE", changed.category);
        assertEquals(Long.valueOf(5L), changed.categoryApprovedBy, "复核人须落库");
        ConfigChangeLog log = changeLogDao.logs.get(changeLogDao.logs.size() - 1);
        assertEquals(ConfigService.KEY_SUB_ACCOUNT_CATEGORY, log.configKey, "留痕到 CFG-02c 口径");
        assertEquals("COMMODITY", log.beforeValue);
        assertEquals("TRADE", log.afterValue);

        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.changeCategory(1, "TRADE", 4L, 5L, "同值")).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.changeCategory(1, "OTHER", 4L, 5L, "非法")).errorCode());
    }

    @Test
    @DisplayName("已停用子账户不得变更分类 → 10009")
    void disabledSubAccountRejectsCategoryChange() {
        service.createSubAccount(subAccount("SA-COMM-05", "商品五账", "COMMODITY", null), 1L);
        service.disableSubAccount(1, "结束", precheck(true, true, true, true), 1L);
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.changeCategory(1, "TRADE", 4L, 5L, "改分类")).errorCode());
    }

    @Test
    @DisplayName("接口面自检：本服务不提供任何物理删除 / 归档外清除方法（CFG-10 DISABLE_ONLY）")
    void noPhysicalDeleteApiExists() {
        Set<String> offensive = new TreeSet<>();
        for (Method m : ConfigService.class.getMethods()) {
            String name = m.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("delete") || name.startsWith("remove") || name.startsWith("purge")
                    || name.startsWith("drop") || name.startsWith("truncate")) {
                offensive.add(m.getName());
            }
        }
        assertTrue(offensive.isEmpty(), "不得存在物理删除入口，实际发现：" + offensive
                + "；复现=CFG-10 要求 DISABLE_ONLY + 导出归档");
    }

    // ------------------------------------------------------------ 桩与数据

    private static ConfigItem item(String key, String valueType, BigDecimal min, BigDecimal max) {
        ConfigItem item = new ConfigItem();
        item.configKey = key;
        item.configCategory = "RISK_PARAM";
        item.valueType = valueType;
        item.minValue = min;
        item.maxValue = max;
        item.isSensitive = "N";
        item.dispatchToCtp = "N";
        item.doubleReviewRequired = "N";
        item.persistScope = "LOCAL_ADMIN";
        return item;
    }

    private static ConfigItem sensitiveItem(String key) {
        ConfigItem item = item(key, "STRING", null, null);
        item.configCategory = "CTP_CONN";
        item.isSensitive = "Y";
        item.sensitiveState = "UNSET";
        item.persistScope = "NEVER";
        return item;
    }

    private static SubAccount subAccount(String code, String name, String category, String status) {
        SubAccount sa = new SubAccount();
        sa.subAccountCode = code;
        sa.subAccountName = name;
        sa.category = category;
        sa.status = status;
        return sa;
    }

    private static ConfigService.SubAccountDisablePrecheck precheck(boolean fund, boolean positions,
                                                                   boolean noInFlight, boolean settled) {
        return new ConfigService.SubAccountDisablePrecheck() {
            @Override
            public boolean fundIsZero(int subAccountId) {
                return fund;
            }

            @Override
            public boolean positionsAreZero(int subAccountId) {
                return positions;
            }

            @Override
            public boolean hasNoInFlightOrders(int subAccountId) {
                return noInFlight;
            }

            @Override
            public boolean settledToday(int subAccountId) {
                return settled;
            }
        };
    }

    /** 内存假子账户 DAO。 */
    private static final class FakeSubAccountDao implements SubAccountDao {

        private final Map<Integer, SubAccount> rows = new LinkedHashMap<>();
        private int nextId = 1;

        @Override
        public int insert(SubAccount entity, long operatorId) {
            entity.id = nextId++;
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public int updateById(SubAccount entity, long operatorId) {
            if (!rows.containsKey(entity.id)) {
                return 0;
            }
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public SubAccount findById(Integer id) {
            return rows.get(id);
        }

        @Override
        public PageResult<SubAccount> findPage(PageQuery query, String where, List<Object> whereArgs) {
            throw FakeDao.unsupported("findPage");
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            throw FakeDao.unsupported("count");
        }

        @Override
        public SubAccount findByCode(String code) {
            for (SubAccount sa : rows.values()) {
                if (sa.subAccountCode.equals(code)) {
                    return sa;
                }
            }
            return null;
        }

        @Override
        public List<SubAccount> findEnabled() {
            List<SubAccount> out = new ArrayList<>();
            for (SubAccount sa : rows.values()) {
                if ("ENABLED".equals(sa.status)) {
                    out.add(sa);
                }
            }
            return out;
        }
    }

    /** 内存假配置项 DAO。 */
    private static final class FakeConfigItemDao implements ConfigItemDao {

        private final Map<String, ConfigItem> rows = new LinkedHashMap<>();
        private long nextId = 1;

        void store(ConfigItem item) {
            if (item.id == 0L) {
                item.id = nextId++;
            }
            rows.put(item.configKey, item);
        }

        @Override
        public int insert(ConfigItem entity, long operatorId) {
            if (rows.containsKey(entity.configKey)) {
                return 0;
            }
            entity.createdBy = operatorId;
            entity.createdTime = LocalDateTime.now();
            entity.rowVersion = 1;
            store(entity);
            return 1;
        }

        @Override
        public int updateById(ConfigItem entity, long operatorId) {
            if (!rows.containsKey(entity.configKey)) {
                return 0;
            }
            entity.rowVersion = entity.rowVersion + 1;
            rows.put(entity.configKey, entity);
            return 1;
        }

        @Override
        public ConfigItem findById(Long id) {
            for (ConfigItem item : rows.values()) {
                if (item.id == id) {
                    return item;
                }
            }
            return null;
        }

        @Override
        public PageResult<ConfigItem> findPage(PageQuery query, String where, List<Object> whereArgs) {
            throw FakeDao.unsupported("findPage");
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            throw FakeDao.unsupported("count");
        }

        @Override
        public ConfigItem findByKey(String configKey) {
            return rows.get(configKey);
        }

        @Override
        public List<ConfigItem> findByCategory(String category) {
            List<ConfigItem> out = new ArrayList<>();
            for (ConfigItem item : rows.values()) {
                if (category.equals(item.configCategory)) {
                    out.add(item);
                }
            }
            return out;
        }

        @Override
        public List<ConfigItem> findDispatchable() {
            List<ConfigItem> out = new ArrayList<>();
            for (ConfigItem item : rows.values()) {
                if ("Y".equals(item.dispatchToCtp)) {
                    out.add(item);
                }
            }
            return out;
        }

        @Override
        public int bumpEffectiveVersion(String configKey) {
            ConfigItem item = rows.get(configKey);
            if (item == null) {
                return 0;
            }
            item.effectiveVersion = item.effectiveVersion + 1;
            return 1;
        }
    }

    /** 内存假配置变更留痕 DAO（只增不改：{@code updateById} 显式拒绝）。 */
    private static final class FakeConfigChangeLogDao implements ConfigChangeLogDao {

        private final List<ConfigChangeLog> logs = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ConfigChangeLog entity, long operatorId) {
            entity.id = nextId++;
            entity.createdBy = operatorId;
            entity.createdTime = LocalDateTime.now();
            logs.add(entity);
            return 1;
        }

        @Override
        public int updateById(ConfigChangeLog entity, long operatorId) {
            throw new UnsupportedOperationException(
                    "t_config_change_log 只增不改（DATA_MODEL.md §2.1 TB-CONFIG-3 备注）");
        }

        @Override
        public ConfigChangeLog findById(Long id) {
            for (ConfigChangeLog log : logs) {
                if (log.id == id) {
                    return log;
                }
            }
            return null;
        }

        @Override
        public PageResult<ConfigChangeLog> findPage(PageQuery query, String where, List<Object> whereArgs) {
            throw FakeDao.unsupported("findPage");
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            throw FakeDao.unsupported("count");
        }

        @Override
        public List<ConfigChangeLog> findByKeyBetween(String configKey, LocalDateTime from,
                                                      LocalDateTime to) {
            List<ConfigChangeLog> out = new ArrayList<>();
            for (ConfigChangeLog log : logs) {
                if (configKey.equals(log.configKey)) {
                    out.add(log);
                }
            }
            return out;
        }

        @Override
        public List<ConfigChangeLog> findDispatchProblems() {
            List<ConfigChangeLog> out = new ArrayList<>();
            Set<String> bad = new LinkedHashSet<>(Arrays.asList("FAIL", "UNCONFIRMED"));
            for (ConfigChangeLog log : logs) {
                if (bad.contains(log.dispatchResult)) {
                    out.add(log);
                }
            }
            return out;
        }
    }
}
