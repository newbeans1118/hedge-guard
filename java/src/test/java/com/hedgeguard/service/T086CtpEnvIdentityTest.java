package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.ConfigItem;
import com.hedgeguard.dao.entity.CtpFrontConfig;
import com.hedgeguard.dao.impl.DmConfigItemDao;
import com.hedgeguard.dao.impl.DmCtpFrontConfigDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
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
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-086}／{@code TC-M4-21} 环境类型显式区分、无实盘误连（{@code RSK-15}）。
 *
 * <p><b>用例判据（TEST_PLAN/M4.md §8 · 唯一预期值口径）</b>：
 * ①环境标识非法值拒绝（服务面逐入口 {@code PARAM_INVALID}）；②{@code CFG-22} 种子默认值
 * {@code SIMULATION}、取值域恰 2 值；③模拟盘取数面实盘地址命中 {@code == 0}（误连探测）；
 * ④连接测试无静默回退（已配置启用中交易前置计数 {@code == 1} 且组件桥缺失显式拒）；
 * ⑤启动自检基元 {@code existsEnabledProduction} 双向判定成立。
 *
 * <p><b>播种形态（§4.2 生产形态纪律）</b>：真实 {@code DmCtpFrontConfigDao}/{@code DmConfigItemDao}
 * + {@code InMemoryDmEngine}（{@code m1_config_auth.sql}/{@code m1_product.sql} 真实 DDL），
 * 全部行经生产 DAO 写入、零直插伪造；{@code CFG-22} 行字段值与 {@code data-init.sql} §④ 逐字段
 * 一致（文件级结构断言由既有 {@code ConfigSeedDataTest} 承载，本类不复述）。
 *
 * <p><b>判据不同源（§4.3）</b>：断言锚冻结域字面量 {@code "SIMULATION"}/{"@code "PRODUCTION"}，
 * 不引用 {@code ConfigService.CTP_ENV_TYPES} 生产常量。
 *
 * <p><b>诚实披露（L3 前置未达）</b>：启动时「非法环境标识拒启（退出码非 0）」「切实盘二次确认 +
 * 双人复核留痕」「界面环境横幅」三项在 {@code Bootstrap.main} / 前端均无承载（本类只验服务面可达判据，
 * 缺口按 T-086 证据章单列）；{@code main.cc} 骨架 ⇒ 跨进程误连面不在本类判据内。
 */
class T086CtpEnvIdentityTest {

    /** 非法环境标识（不在 SIMULATION/PRODUCTION 值域内的探针值）。 */
    private static final String ILLEGAL_ENV = "UAT";

    /** 模拟盘交易前置地址（探针专设，非生产常量）。 */
    private static final String SIM_ADDR = "tcp://127.0.0.1:41001";
    /** 实盘交易前置地址（探针专设，非生产常量）。 */
    private static final String PROD_ADDR = "tcp://127.0.0.1:42001";

    private static final long OPERATOR = 100L;

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private DmCtpFrontConfigDao frontDao;
    private DmConfigItemDao configItemDao;
    private ConfigService service;

    @BeforeEach
    void buildWorld() throws Exception {
        pinClock();
        engine = new InMemoryDmEngine();
        for (String rel : new String[] {
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m1_product.sql"}) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        frontDao = new DmCtpFrontConfigDao(tx);
        configItemDao = new DmConfigItemDao(tx);
        service = new ConfigService(tx, null, configItemDao, null,
                null, null, null, frontDao, null);
        seedCfg22();
    }

    /**
     * {@code CFG-22} 种子行经生产 DAO 写入（§4.2 零直插伪造）。
     *
     * <p>字段值与 {@code data-init.sql} §④ {@code hg.ctp.env_type} 行逐字段一致
     * （文件级结构断言由既有 {@code ConfigSeedDataTest} 承载；本引擎不解析该脚本
     * 的 DM 专有语法，故按等价语义经生产写入路径播种）。
     */
    private void seedCfg22() {
        ConfigItem cfg = new ConfigItem();
        cfg.configKey = "hg.ctp.env_type";
        cfg.configCategory = "CTP_CONN";
        cfg.configValue = "SIMULATION";
        cfg.valueType = "ENUM";
        cfg.enumScope = "SIMULATION,PRODUCTION";
        cfg.defaultValue = "SIMULATION";
        cfg.isSensitive = "N";
        cfg.persistScope = "LOCAL_ADMIN";
        cfg.dispatchToCtp = "Y";
        cfg.doubleReviewRequired = "Y";
        assertEquals(1, configItemDao.insert(cfg, OPERATOR), "CFG-22 种子行插入影响行数");
    }

    @AfterEach
    void restoreClock() {
        Clocks.reset();
    }

    /** 测试档时钟固定（判据与运行日解耦）。 */
    private static void pinClock() {
        Clocks.set(Clock.fixed(
                LocalDate.of(2026, 10, 26).atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()));
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("docs/DEPLOY/schema"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到仓库根（docs/DEPLOY/schema）");
    }

    /** 经生产 DAO 写入一行前置配置（§4.2：零直插伪造）。 */
    private long seedFront(String envType, String frontType, String addr, String enabled) {
        CtpFrontConfig e = new CtpFrontConfig();
        e.envType = envType;
        e.frontType = frontType;
        e.frontAddr = addr;
        e.priority = 0;
        e.brokerId = "9999";
        e.investorId = "00000001";
        e.connectTimeoutMs = 5000;
        e.reconnectIntervalMs = 3000;
        e.flowPath = "/tmp/hg-flow";
        e.isEnabled = enabled;
        assertEquals(1, frontDao.insert(e, OPERATOR), "前置配置插入影响行数");
        return e.id;
    }

    // ================================================================ TC-M4-21

    @Test
    @DisplayName("TC-M4-21①：环境标识非法值（UAT）三入口均拒——listCtpFronts/saveCtpFront/testCtpConnection")
    void tcM4_21_illegalEnvTypeRejectedThreeFaces() {
        BizException listEx = assertThrows(BizException.class, () ->
                service.listCtpFronts(ILLEGAL_ENV, PageQuery.of("1", "10", null, "id")));
        assertEquals(ErrorCode.PARAM_INVALID, listEx.errorCode(), "listCtpFronts 非法 envType 业务码");

        CtpFrontConfig bad = new CtpFrontConfig();
        bad.envType = ILLEGAL_ENV;
        bad.frontType = "TRADE";
        bad.frontAddr = SIM_ADDR;
        bad.brokerId = "9999";
        bad.investorId = "00000001";
        BizException saveEx = assertThrows(BizException.class, () -> service.saveCtpFront(bad, OPERATOR));
        assertEquals(ErrorCode.PARAM_INVALID, saveEx.errorCode(), "saveCtpFront 非法 envType 业务码");
        assertEquals(0, engine.table("t_ctp_front_config").rows.size(), "非法值不得落库");

        BizException testEx = assertThrows(BizException.class, () ->
                service.testCtpConnection(ILLEGAL_ENV));
        assertEquals(ErrorCode.PARAM_INVALID, testEx.errorCode(), "testCtpConnection 非法 envType 业务码");
    }

    @Test
    @DisplayName("TC-M4-21②：CFG-22 种子 hg.ctp.env_type 默认值 = SIMULATION，取值域恰 {SIMULATION, PRODUCTION}")
    void tcM4_21_cfg22SeedDefaultsToSimulationWithTwoValueDomain() {
        ConfigItem cfg = configItemDao.findByKey("hg.ctp.env_type");
        assertTrue(cfg != null, "CFG-22 配置行经生产 DAO 可读");
        assertEquals("SIMULATION", cfg.configValue, "CFG-22 默认值（data-init.sql §④ 冻结口径）");
        assertTrue(cfg.enumScope != null && cfg.enumScope.contains(",")
                        && cfg.enumScope.indexOf(',') == cfg.enumScope.lastIndexOf(','),
                "取值域恰含一个分隔符：实际=" + cfg.enumScope);
        assertTrue(cfg.enumScope.contains("SIMULATION") && cfg.enumScope.contains("PRODUCTION"),
                "取值域含 SIMULATION 与 PRODUCTION：实际=" + cfg.enumScope);
        assertEquals(2, cfg.enumScope.split(",").length, "取值域值数");
    }

    @Test
    @DisplayName("TC-M4-21④（误连探测）：模拟盘取数面实盘地址命中 == 0")
    void tcM4_21_simulationFaceZeroProductionAddrHit() {
        seedFront("SIMULATION", "TRADE", SIM_ADDR, "Y");
        seedFront("PRODUCTION", "TRADE", PROD_ADDR, "Y");

        PageResult<CtpFrontConfig> page = service.listCtpFronts("SIMULATION",
                PageQuery.of("1", "100", null, "id"));
        assertEquals(1, page.items().size(), "模拟盘取数面行数");
        assertEquals(0, page.items().stream().filter(f -> PROD_ADDR.equals(f.frontAddr)).count(),
                "实盘地址在模拟盘取数面命中数");
        assertEquals(0, page.items().stream().filter(f -> "PRODUCTION".equals(f.envType)).count(),
                "实盘环境行在模拟盘取数面命中数");

        List<CtpFrontConfig> simTrade = frontDao.findEnabledByPriority("SIMULATION", "TRADE");
        assertEquals(1, simTrade.size(), "模拟盘启用交易前置数");
        assertEquals(SIM_ADDR, simTrade.get(0).frontAddr, "模拟盘启用交易前置地址");
        assertEquals(0, simTrade.stream().filter(f -> PROD_ADDR.equals(f.frontAddr)).count(),
                "实盘地址在模拟盘启用前置集命中数");
    }

    @Test
    @DisplayName("TC-M4-21⑤（无静默回退）：SIMULATION 连接测试显式 COMPONENT_UNAVAILABLE 且已配置启用交易前置 == 1")
    void tcM4_21_connectionTestNoSilentFallback() {
        seedFront("SIMULATION", "TRADE", SIM_ADDR, "Y");
        BizException ex = assertThrows(BizException.class, () ->
                service.testCtpConnection("SIMULATION"));
        assertEquals(ErrorCode.COMPONENT_UNAVAILABLE, ex.errorCode(), "组件桥缺失显式拒业务码");
        assertTrue(String.valueOf(ex.getMessage()).contains("已配置启用中交易前置=1"),
                "已配置启用中交易前置计数披露：实际报文=" + ex.getMessage());
    }

    @Test
    @DisplayName("TC-M4-21⑥（启动自检基元·R-CONFIG-9）：existsEnabledProduction 双向判定 true→false")
    void tcM4_21_existsEnabledProductionPrimitiveBothDirections() {
        long prodId = seedFront("PRODUCTION", "TRADE", PROD_ADDR, "Y");
        assertTrue(frontDao.existsEnabledProduction(), "存在启用实盘前置 ⇒ true");

        CtpFrontConfig row = frontDao.findById(prodId);
        row.isEnabled = "N";
        assertEquals(1, frontDao.updateById(row, OPERATOR), "停用实盘前置影响行数");
        assertFalse(frontDao.existsEnabledProduction(), "停用后 ⇒ false");
    }
}
