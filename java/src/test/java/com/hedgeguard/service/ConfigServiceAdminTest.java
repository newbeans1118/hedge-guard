package com.hedgeguard.service;

import com.hedgeguard.dao.AccountSubjectDao;
import com.hedgeguard.dao.ConfigChangeLogDao;
import com.hedgeguard.dao.ConfigItemDao;
import com.hedgeguard.dao.CtpFrontConfigDao;
import com.hedgeguard.dao.DictItemDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ParamDispatchDao;
import com.hedgeguard.dao.ReportPeriodConfigDao;
import com.hedgeguard.dao.entity.AccountSubject;
import com.hedgeguard.dao.entity.ConfigChangeLog;
import com.hedgeguard.dao.entity.ConfigItem;
import com.hedgeguard.dao.entity.CtpFrontConfig;
import com.hedgeguard.dao.entity.DictItem;
import com.hedgeguard.dao.entity.ParamDispatch;
import com.hedgeguard.dao.entity.ReportPeriodConfig;
import com.hedgeguard.testsupport.FakeDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigService} <b>管理面单测补齐</b>（{@code T-023} 验收标准 1 的覆盖率证据）。
 *
 * <p>{@code ConfigServiceTest} 只覆盖子账户生命周期与配置取值/留痕；本类补齐 {@code T-109}
 * 声明面新增的字典 / 会计科目 / 报告周期 / CTP 前置 / 参数下发 / 导入导出方法的正常 + 异常路径，
 * 把 CONFIG 模块单测覆盖率提升到阈值以上。
 *
 * <p>桩实现为内存假 DAO（{@code NFR-TEST-2}：不引 Mockito）；事务经 {@link FakeTx} 的真实
 * {@code TxTemplate} 驱动。
 */
class ConfigServiceAdminTest {

    private FakeDictItemDao dictItemDao;
    private FakeAccountSubjectDao accountSubjectDao;
    private FakeReportPeriodConfigDao reportPeriodConfigDao;
    private FakeCtpFrontConfigDao ctpFrontConfigDao;
    private FakeParamDispatchDao paramDispatchDao;
    private FakeConfigItemDao configItemDao;
    private FakeConfigChangeLogDao changeLogDao;
    private ConfigService service;

    @BeforeEach
    void setUp() {
        dictItemDao = new FakeDictItemDao();
        accountSubjectDao = new FakeAccountSubjectDao();
        reportPeriodConfigDao = new FakeReportPeriodConfigDao();
        ctpFrontConfigDao = new FakeCtpFrontConfigDao();
        paramDispatchDao = new FakeParamDispatchDao();
        configItemDao = new FakeConfigItemDao();
        changeLogDao = new FakeConfigChangeLogDao();
        service = new ConfigService(FakeTx.newTemplate(), null, configItemDao, changeLogDao,
                dictItemDao, accountSubjectDao, reportPeriodConfigDao, ctpFrontConfigDao,
                paramDispatchDao);
    }

    // ------------------------------------------------------------ 配置项读面

    @Test
    @DisplayName("配置项读面：getConfig / listByCategory / listDispatchable 委托 DAO")
    void configItemReads() {
        ConfigItem a = item("hg.risk.ratio", "RISK_PARAM");
        a.configValue = "0.5";
        ConfigItem b = item("hg.risk.other", "RISK_PARAM");
        b.dispatchToCtp = "Y";
        configItemDao.store(a);
        configItemDao.store(b);

        assertNotNull(service.getConfig("hg.risk.ratio"));
        assertNull(service.getConfig("hg.absent"));
        assertEquals(2, service.listByCategory("RISK_PARAM").size());
        assertEquals(1, service.listDispatchable().size());
        assertEquals("hg.risk.other", service.listDispatchable().get(0).configKey);
    }

    // ------------------------------------------------------------ 字典项

    @Test
    @DisplayName("字典项：listDictItems 分类/全量；saveDictItem 新增→更新→重复→系统项禁停用")
    void dictItemLifecycle() {
        DictItem d = dict("COLOR", "RED");
        assertEquals(1L, service.saveDictItem(d, 1L), "新增返回主键");
        assertEquals(1, service.listDictItems(null, page()).items().size());
        assertEquals(1, service.listDictItems("COLOR", page()).items().size());

        BizException dup = assertThrows(BizException.class, () -> service.saveDictItem(dict("COLOR", "RED"), 1L));
        assertEquals(ErrorCode.UNIQUE_CONFLICT, dup.errorCode(), "同类型同编码重复 → 10010");

        DictItem update = dict("COLOR", "RED");
        update.id = d.id;
        update.itemName = "红色";
        update.status = "DISABLED";
        assertEquals(d.id, service.saveDictItem(update, 1L), "更新返回原主键");

        DictItem system = dict("SYS", "X");
        system.isSystem = "Y";
        system.status = "ENABLED";
        service.saveDictItem(system, 1L);
        DictItem disable = dict("SYS", "X");
        disable.id = system.id;
        disable.status = "DISABLED";
        BizException e = assertThrows(BizException.class, () -> service.saveDictItem(disable, 1L));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, e.errorCode(), "系统内置项不得停用 → 10009");
    }

    @Test
    @DisplayName("字典项：更新不存在 → 10001；取值域非法 → 10001")
    void dictItemValidation() {
        DictItem missing = dict("COLOR", "RED");
        missing.id = 99L;
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.saveDictItem(missing, 1L)).errorCode());
        DictItem bad = dict("COLOR", "RED");
        bad.status = "GONE";
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.saveDictItem(bad, 1L)).errorCode());
    }

    // ------------------------------------------------------------ 会计科目

    @Test
    @DisplayName("会计科目：listSubjects 场景/全量；saveSubject 新增→更新→重复")
    void accountSubjectLifecycle() {
        AccountSubject s = subject("1001", "OPEN_POSITION");
        assertEquals(1L, service.saveSubject(s, 1L));
        assertEquals(1, service.listSubjects(null, page()).items().size());
        assertEquals(1, service.listSubjects("OPEN_POSITION", page()).items().size());

        BizException dup = assertThrows(BizException.class, () -> service.saveSubject(subject("1001", "CLOSE_PNL"), 1L));
        assertEquals(ErrorCode.UNIQUE_CONFLICT, dup.errorCode());

        AccountSubject update = subject("1001", "CLOSE_PNL");
        update.id = s.id;
        update.subjectName = "平仓损益";
        assertEquals(s.id, service.saveSubject(update, 1L));

        AccountSubject missing = subject("1002", "COMMISSION");
        missing.id = 99L;
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.saveSubject(missing, 1L)).errorCode());
    }

    // ------------------------------------------------------------ 报告周期

    @Test
    @DisplayName("报告周期：listReportPeriods；saveReportPeriod 新增→更新；合规必需报告不得关闭 → 10012")
    void reportPeriodLifecycle() {
        assertEquals(0, service.listReportPeriods(page()).items().size());

        ReportPeriodConfig cfg = reportPeriod("MONTHLY");
        assertEquals(1L, service.saveReportPeriod(cfg, 1L));
        assertEquals(1, service.listReportPeriods(page()).items().size());

        ReportPeriodConfig update = reportPeriod("MONTHLY");
        update.periodValue = 2;
        assertEquals(cfg.id, service.saveReportPeriod(update, 1L), "同 reportType 走更新");

        ReportPeriodConfig blocked = reportPeriod("QUARTERLY");
        blocked.isMandatory = "Y";
        blocked.isEnabled = "N";
        BizException e = assertThrows(BizException.class, () -> service.saveReportPeriod(blocked, 1L));
        assertEquals(ErrorCode.COMPLIANCE_GATE_FAILED, e.errorCode(), "合规必需报告不得关闭 → 10012");
        assertEquals("R-CONFIG-7", e.ruleId(), "规则编号须为 R-CONFIG-7");
    }

    // ------------------------------------------------------------ CTP 前置

    @Test
    @DisplayName("CTP 前置：listCtpFronts 环境过滤/全量；saveCtpFront 新增→更新→非法地址→不存在")
    void ctpFrontLifecycle() {
        assertEquals(0, service.listCtpFronts(null, page()).items().size());

        CtpFrontConfig cfg = ctpFront("SIMULATION", "TRADE", "tcp://127.0.0.1:41213");
        assertEquals(1L, service.saveCtpFront(cfg, 1L));
        assertEquals(1, service.listCtpFronts("SIMULATION", page()).items().size());
        assertEquals(1, service.listCtpFronts(null, page()).items().size());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.listCtpFronts("BOGUS", page())).errorCode());

        CtpFrontConfig update = ctpFront("SIMULATION", "TRADE", "tcp://127.0.0.1:41214");
        update.id = cfg.id;
        assertEquals(cfg.id, service.saveCtpFront(update, 1L), "id>0 走更新");

        CtpFrontConfig badAddr = ctpFront("SIMULATION", "MD", "127.0.0.1:41215");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.saveCtpFront(badAddr, 1L)).errorCode());

        CtpFrontConfig missing = ctpFront("PRODUCTION", "TRADE", "tcp://127.0.0.1:41216");
        missing.id = 99L;
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.saveCtpFront(missing, 1L)).errorCode());
    }

    @Test
    @DisplayName("CTP 连接测试：无启用交易前置 → 10001；有前置 → 10002（M1 未交付桥接，诚实失败）")
    void ctpConnectionTestFailsHonestly() {
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.testCtpConnection("SIMULATION")).errorCode());

        ctpFrontConfigDao.insert(ctpFront("SIMULATION", "TRADE", "tcp://127.0.0.1:41213"), 1L);
        BizException e = assertThrows(BizException.class, () -> service.testCtpConnection("SIMULATION"));
        assertEquals(ErrorCode.COMPONENT_UNAVAILABLE, e.errorCode(), "有前置但无桥接 → 10002");
    }

    // ------------------------------------------------------------ 下发记录与变更留痕

    @Test
    @DisplayName("参数下发：getDispatch 按主键读；listChangeLogs 分类 + 时间范围（parseDay 解析）")
    void dispatchAndChangeLogs() {
        ParamDispatch dispatch = new ParamDispatch();
        dispatch.id = 7L;
        paramDispatchDao.rows.put(7L, dispatch);
        assertEquals(7L, service.getDispatch(7L).id);
        assertNull(service.getDispatch(99L));

        ConfigChangeLog log = new ConfigChangeLog();
        log.configKey = "hg.risk.ratio";
        log.configCategory = "RISK_PARAM";
        log.changeTime = LocalDateTime.of(2026, 9, 15, 12, 0);
        changeLogDao.logs.add(log);
        assertEquals(1, service.listChangeLogs(page(), "RISK_PARAM", "2026-09-01", "2026-09-30")
                .items().size());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.listChangeLogs(page(), null, "not-a-date", null)).errorCode());
    }

    // ------------------------------------------------------------ 导入 / 导出

    @Test
    @DisplayName("导入：全量预校验通过后一次性写入（新增 + 覆盖均留痕）；含非法项则整体不导入 → 10019")
    void importConfigItemsWritesOrRejectsWhole() {
        ConfigItem existing = item("hg.risk.a", "RISK_PARAM");
        existing.configValue = "0";
        configItemDao.store(existing);

        ConfigItem a = item("hg.risk.a", "RISK_PARAM");   // 已存在 → OVERWRITE 走更新 + 留痕
        a.configValue = "1";
        ConfigItem b = item("hg.risk.b", "RISK_PARAM");   // 新 → 新增
        b.configValue = "2";
        assertEquals(2, service.importConfigItems(Arrays.asList(a, b), "OVERWRITE", 1L),
                "两项均写入 → 2");
        assertEquals(2, configItemDao.rows.size());
        assertEquals(1, changeLogDao.logs.size(), "覆盖分支逐项留痕");

        ConfigItem dup = item("hg.risk.c", "RISK_PARAM");
        dup.configValue = "3";
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED,
                assertThrows(BizException.class,
                        () -> service.importConfigItems(Arrays.asList(dup, dup), "INSERT_ONLY", 1L))
                        .errorCode(), "批次内重复 → 10019，整体不导入");

        ConfigItem bad = item("hg.risk.d", "RISK_PARAM");
        bad.configKey = null;   // 必填缺失
        BizException e = assertThrows(BizException.class,
                () -> service.importConfigItems(Arrays.asList(bad), "INSERT_ONLY", 1L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode(), "预校验失败 → 10019");
        assertEquals(2, configItemDao.rows.size(), "整体不导入，既有行不变");
    }

    @Test
    @DisplayName("导出：exportConfigItemsCsv 首行表头 + 敏感项取值列恒空 + CSV 转义")
    void exportCsvExcludesSensitiveValue() {
        ConfigItem plain = item("hg.risk.ratio", "RISK_PARAM");
        plain.configValue = "0.5";
        configItemDao.store(plain);
        ConfigItem sensitive = item("hg.ctp.password", "CTP_CONN");
        sensitive.isSensitive = "Y";
        sensitive.sensitiveState = "SET";
        sensitive.configValue = "SHOULD-NOT-LEAK";
        configItemDao.store(sensitive);

        List<String> csv = service.exportConfigItemsCsv(null);
        assertEquals("config_key,config_category,config_value,value_type,enum_scope,persist_scope,"
                + "dispatch_to_ctp,sensitive_state", csv.get(0), "首行表头");
        assertEquals(3, csv.size(), "表头 + 2 行");
        String joined = String.join("\n", csv);
        assertTrue(joined.contains("hg.risk.ratio,RISK_PARAM,0.5"), "普通项取值导出：" + joined);
        assertTrue(!joined.contains("SHOULD-NOT-LEAK"), "敏感项取值不得出现在 CSV");
        assertEquals(2, service.exportConfigItemsCsv("RISK_PARAM").size(), "按分类导出：表头 + 1 行");
    }

    // ------------------------------------------------------------ 桩与数据

    private static PageQuery page() {
        return PageQuery.of("1", "20", null, "id");
    }

    private static ConfigItem item(String key, String category) {
        ConfigItem item = new ConfigItem();
        item.configKey = key;
        item.configCategory = category;
        item.valueType = "DECIMAL";
        item.isSensitive = "N";
        item.dispatchToCtp = "N";
        item.doubleReviewRequired = "N";
        item.persistScope = "LOCAL_ADMIN";
        return item;
    }

    private static DictItem dict(String type, String code) {
        DictItem d = new DictItem();
        d.dictType = type;
        d.itemCode = code;
        d.itemName = code;
        d.status = "ENABLED";
        return d;
    }

    private static AccountSubject subject(String code, String scene) {
        AccountSubject s = new AccountSubject();
        s.subjectCode = code;
        s.subjectName = code;
        s.direction = "DEBIT";
        s.bizScene = scene;
        s.status = "ENABLED";
        return s;
    }

    private static ReportPeriodConfig reportPeriod(String type) {
        ReportPeriodConfig cfg = new ReportPeriodConfig();
        cfg.reportType = type;
        cfg.periodUnit = "MONTH";
        cfg.periodValue = 1;
        cfg.remindRoleCodes = "ROLE_RISK";
        cfg.isMandatory = "Y";
        cfg.isEnabled = "Y";
        return cfg;
    }

    private static CtpFrontConfig ctpFront(String env, String type, String addr) {
        CtpFrontConfig cfg = new CtpFrontConfig();
        cfg.envType = env;
        cfg.frontType = type;
        cfg.frontAddr = addr;
        cfg.brokerId = "BROKER-1";
        cfg.investorId = "INV-1";
        cfg.isEnabled = "Y";
        return cfg;
    }

    // ------------------------------------------------------------ 假 DAO

    private static final class FakeDictItemDao implements DictItemDao {
        private final Map<Long, DictItem> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public int insert(DictItem entity, long operatorId) {
            entity.id = nextId++;
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public int updateById(DictItem entity, long operatorId) {
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public DictItem findById(Long id) {
            return rows.get(id);
        }

        @Override
        public PageResult<DictItem> findPage(PageQuery query, String where, List<Object> whereArgs) {
            List<DictItem> all = new ArrayList<>(rows.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public int deleteById(Long id) {
            throw FakeDao.unsupported("deleteById");
        }

        @Override
        public DictItem findByTypeAndCode(String dictType, String itemCode) {
            for (DictItem d : rows.values()) {
                if (d.dictType.equals(dictType) && d.itemCode.equals(itemCode)) {
                    return d;
                }
            }
            return null;
        }

        @Override
        public List<DictItem> findEnabledByType(String dictType) {
            return new ArrayList<>(rows.values());
        }
    }

    private static final class FakeAccountSubjectDao implements AccountSubjectDao {
        private final Map<Long, AccountSubject> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public int insert(AccountSubject entity, long operatorId) {
            entity.id = nextId++;
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public int updateById(AccountSubject entity, long operatorId) {
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public AccountSubject findById(Long id) {
            return rows.get(id);
        }

        @Override
        public PageResult<AccountSubject> findPage(PageQuery query, String where, List<Object> whereArgs) {
            List<AccountSubject> all = new ArrayList<>(rows.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public AccountSubject findByCode(String subjectCode) {
            for (AccountSubject s : rows.values()) {
                if (s.subjectCode.equals(subjectCode)) {
                    return s;
                }
            }
            return null;
        }

        @Override
        public List<AccountSubject> findTemplateDefaults() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public List<AccountSubject> findEnabledByScene(String bizScene) {
            return new ArrayList<>(rows.values());
        }
    }

    private static final class FakeReportPeriodConfigDao implements ReportPeriodConfigDao {
        private final Map<Long, ReportPeriodConfig> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public int insert(ReportPeriodConfig entity, long operatorId) {
            entity.id = nextId++;
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public int updateById(ReportPeriodConfig entity, long operatorId) {
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public ReportPeriodConfig findById(Long id) {
            return rows.get(id);
        }

        @Override
        public PageResult<ReportPeriodConfig> findPage(PageQuery query, String where, List<Object> whereArgs) {
            List<ReportPeriodConfig> all = new ArrayList<>(rows.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public ReportPeriodConfig findByType(String reportType) {
            for (ReportPeriodConfig c : rows.values()) {
                if (c.reportType.equals(reportType)) {
                    return c;
                }
            }
            return null;
        }

        @Override
        public List<ReportPeriodConfig> findEnabled() {
            return new ArrayList<>(rows.values());
        }
    }

    private static final class FakeCtpFrontConfigDao implements CtpFrontConfigDao {
        private final Map<Long, CtpFrontConfig> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public int insert(CtpFrontConfig entity, long operatorId) {
            entity.id = nextId++;
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public int updateById(CtpFrontConfig entity, long operatorId) {
            rows.put(entity.id, entity);
            return 1;
        }

        @Override
        public CtpFrontConfig findById(Long id) {
            return rows.get(id);
        }

        @Override
        public PageResult<CtpFrontConfig> findPage(PageQuery query, String where, List<Object> whereArgs) {
            List<CtpFrontConfig> all = new ArrayList<>();
            for (CtpFrontConfig c : rows.values()) {
                if (where != null && where.contains("env_type = ?") && whereArgs != null
                        && !whereArgs.isEmpty() && !c.envType.equals(whereArgs.get(0))) {
                    continue;
                }
                all.add(c);
            }
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public List<CtpFrontConfig> findEnabledByPriority(String envType, String frontType) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public boolean existsEnabledProduction() {
            return false;
        }
    }

    private static final class FakeParamDispatchDao implements ParamDispatchDao {
        private final Map<Long, ParamDispatch> rows = new LinkedHashMap<>();

        @Override
        public int insert(ParamDispatch entity, long operatorId) {
            throw FakeDao.unsupported("insert");
        }

        @Override
        public int updateById(ParamDispatch entity, long operatorId) {
            throw FakeDao.unsupported("updateById");
        }

        @Override
        public ParamDispatch findById(Long id) {
            return rows.get(id);
        }

        @Override
        public PageResult<ParamDispatch> findPage(PageQuery query, String where, List<Object> whereArgs) {
            throw FakeDao.unsupported("findPage");
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            throw FakeDao.unsupported("count");
        }

        @Override
        public long maxParamVersion() {
            throw FakeDao.unsupported("maxParamVersion");
        }

        @Override
        public List<ParamDispatch> findByVersion(long paramVersion, String dispatchType) {
            throw FakeDao.unsupported("findByVersion");
        }

        @Override
        public boolean isBatchComplete(long paramVersion, String dispatchType, int batchTotal) {
            throw FakeDao.unsupported("isBatchComplete");
        }

        @Override
        public List<ParamDispatch> findProblems() {
            throw FakeDao.unsupported("findProblems");
        }

        @Override
        public List<ParamDispatch> findBySubAccount(int subAccountId) {
            throw FakeDao.unsupported("findBySubAccount");
        }

        @Override
        public int updateAck(long id, String dispatchStatus, LocalDateTime ackTime, String ackDetail,
                             int retryCount) {
            throw FakeDao.unsupported("updateAck");
        }
    }

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
            store(entity);
            return 1;
        }

        @Override
        public int updateById(ConfigItem entity, long operatorId) {
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
            List<ConfigItem> all = new ArrayList<>(rows.values());
            return new PageResult<>(all, all.size(), query.page(), query.size());
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return rows.size();
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

    private static final class FakeConfigChangeLogDao implements ConfigChangeLogDao {
        private final List<ConfigChangeLog> logs = new ArrayList<>();
        private long nextId = 1;

        @Override
        public int insert(ConfigChangeLog entity, long operatorId) {
            entity.id = nextId++;
            logs.add(entity);
            return 1;
        }

        @Override
        public int updateById(ConfigChangeLog entity, long operatorId) {
            throw FakeDao.unsupported("updateById");
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
            return new PageResult<>(new ArrayList<>(logs), logs.size(), query.page(), query.size());
        }

        @Override
        public long count(String where, List<Object> whereArgs) {
            return logs.size();
        }

        @Override
        public List<ConfigChangeLog> findByKeyBetween(String configKey, LocalDateTime from,
                                                      LocalDateTime to) {
            return new ArrayList<>(logs);
        }

        @Override
        public List<ConfigChangeLog> findDispatchProblems() {
            return new ArrayList<>(logs);
        }
    }
}
