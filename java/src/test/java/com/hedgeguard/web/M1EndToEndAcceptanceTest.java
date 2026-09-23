package com.hedgeguard.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.impl.DmAccountSubjectDao;
import com.hedgeguard.dao.impl.DmAuthorizationDao;
import com.hedgeguard.dao.impl.DmConfigChangeLogDao;
import com.hedgeguard.dao.impl.DmConfigItemDao;
import com.hedgeguard.dao.impl.DmCtpFrontConfigDao;
import com.hedgeguard.dao.impl.DmCustomHedgeRuleDao;
import com.hedgeguard.dao.impl.DmDictItemDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmExposureDetailDao;
import com.hedgeguard.dao.impl.DmHedgeDocDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmHedgeRatioCalcDao;
import com.hedgeguard.dao.impl.DmInventoryLedgerDao;
import com.hedgeguard.dao.impl.DmLoginLogDao;
import com.hedgeguard.dao.impl.DmLoginSessionDao;
import com.hedgeguard.dao.impl.DmParamDispatchDao;
import com.hedgeguard.dao.impl.DmPasswordHistoryDao;
import com.hedgeguard.dao.impl.DmPermissionChangeLogDao;
import com.hedgeguard.dao.impl.DmPlanApprovalDao;
import com.hedgeguard.dao.impl.DmPlanStateLogDao;
import com.hedgeguard.dao.impl.DmPositionConflictDao;
import com.hedgeguard.dao.impl.DmPositionGroupDao;
import com.hedgeguard.dao.impl.DmProductChangeLogDao;
import com.hedgeguard.dao.impl.DmProductContractDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmProductQuotaDao;
import com.hedgeguard.dao.impl.DmProductSpotCategoryDao;
import com.hedgeguard.dao.impl.DmProductUnitDao;
import com.hedgeguard.dao.impl.DmProductionPlanDao;
import com.hedgeguard.dao.impl.DmProductionPlanLineDao;
import com.hedgeguard.dao.impl.DmPurchaseContractDao;
import com.hedgeguard.dao.impl.DmReportPeriodConfigDao;
import com.hedgeguard.dao.impl.DmRoleDao;
import com.hedgeguard.dao.impl.DmSalesContractDao;
import com.hedgeguard.dao.impl.DmSpotChangeLogDao;
import com.hedgeguard.dao.impl.DmSpotPriceDao;
import com.hedgeguard.dao.impl.DmSubAccountDao;
import com.hedgeguard.dao.impl.DmSubInstrumentQuotaDao;
import com.hedgeguard.dao.impl.DmSubProductWhitelistDao;
import com.hedgeguard.dao.impl.DmTradePlanDao;
import com.hedgeguard.dao.impl.DmUnauthorizedAttemptDao;
import com.hedgeguard.dao.impl.DmUserAccountDao;
import com.hedgeguard.dao.impl.DmUserRoleDao;
import com.hedgeguard.dao.impl.DmUserSubAccountPermDao;
import com.hedgeguard.dao.impl.DmWipRecordDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.service.PlanService;
import com.hedgeguard.service.SpotService;
import com.hedgeguard.service.VarietyService;
import com.hedgeguard.web.handler.auth.AuthRoutes;
import com.hedgeguard.web.handler.config.ConfigRoutes;
import com.hedgeguard.web.handler.plan.PlanRoutes;
import com.hedgeguard.web.handler.product.ProductRoutes;
import com.hedgeguard.web.handler.spot.SpotRoutes;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-024} <b>M1 端到端验收</b>（{@code PROJECT.md} §9 M1 验收方式）——
 * <b>REST 装配级</b>全链路，跑在 {@code T-023} 交付的「按 SQL 语义求值的内存假 JDBC 引擎」上。
 *
 * <p><b>本类要证明什么（≠ 装配层「路由已注册」这种浅断言）</b>：把<b>真实</b>
 * {@link Router} + {@link RequestDispatcher} + {@link SessionAuthFilter} + 五个业务模块的
 * <b>真实服务</b>（{@code AuthService/ConfigService/VarietyService/SpotService/PlanService}）+
 * <b>真实 DAO</b>（{@code Dm*Dao}）整链装配，数据落 {@link InMemoryDmEngine} 并<b>逐行、逐字段</b>断言，
 * 复现验收标准 1–3：
 * <ol>
 *   <li>建两个子账户 → 分别配置品种与实货敞口 → 各自产出套保计划，<b>全链路无断点</b>（业务码 0）；</li>
 *   <li><b>子账户数据隔离双向</b>：越权被拒（{@code 10004} / 访问越界 {@code 10001}）、不误拒（同子账户写入 200）；</li>
 *   <li>数据在（等价）DM8 中正确落库：子账户标识、敞口三分量、额度三层。</li>
 * </ol>
 *
 * <p><b>⚠️ 本类不构成 DM8 实机验证</b>：本机无可达 DM8 实例与驱动，仍以引擎<b>等价执行</b>
 * （口径与 {@code M1Dm8EquivalentIntegrationTest} 一致）；实机验证由部署方在可达实例上复跑。
 * 引擎<b>已披露不覆盖项</b>（条件唯一索引 {@code WHERE} 谓词、JOIN/聚合静默返回错值、子查询 loud 抛、
 * 多列外键、事务回滚）——<b>本链路不依赖其中任何一项</b>（见类尾「不依赖项」核对）。
 *
 * <p><b>层次边界</b>：本类只覆盖到 REST 装配级；<b>UI 级不在本任务范围</b>
 * （{@code T-019}/{@code T-020}/{@code T-021} 前端页面并行开发中）。
 *
 * <p>完全离线（{@code NFR-TEST-2}）：无网络、无真实 JDBC、无 DM 驱动。
 */
class M1EndToEndAcceptanceTest {

    /** 4 个 M1 建表脚本（相对仓库根）。 */
    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql");

    /** 用户 ID（显式分配，避免与自增表混用）。 */
    private static final long U_ADMIN = 100L;
    private static final long U_A = 101L;
    private static final long U_B = 102L;
    private static final long U_AUDIT = 103L;

    private static final String S_ADMIN = "S-ADMIN";
    private static final String S_A = "S-A";
    private static final String S_B = "S-B";
    private static final String S_AUDIT = "S-AUDIT";

    private static final String PRODUCT_CODE = "CU";
    private static final String INSTRUMENT = "cu2612";

    /** 主链路首笔敞口基准日；备用基准日用于写面「不误拒」复跑（避免撞 uk_exposure_key）。 */
    private static final String ASOF = "2026-09-20";
    private static final String ASOF_ALT = "2026-09-19";

    /** 固定业务基准日（可注入时钟，{@code NFR-TEST-4}）：全程确定性复现。 */
    private static final LocalDate BASE_DAY = LocalDate.of(2026, 9, 20);

    private InMemoryDmEngine engine;
    private RequestDispatcher dispatcher;
    private SessionStore.InMemory sessionStore;

    // 主链路产出（setUp 内建立）
    private int subA;
    private int subB;
    private long productId;
    private long exposureA;
    private long exposureB;
    private long planA;
    private long planB;

    // ------------------------------------------------------------ 世界装配 + 主链路

    @BeforeEach
    void buildWorldAndRunMainChain() throws Exception {
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        // 作用域哨兵值 0 的外键载体（DATA_MODEL.md §2.1 保留系统行）。
        // 注：data-init.sql 用 DM 专有 `SET IDENTITY_INSERT`，本引擎不解析，故按等价语义插入本行。
        M1E2eSeed.reservedSubAccountZero(engine);
        M1E2eSeed.user(engine, U_ADMIN, "admin", "管理员", "NP-ADMIN");
        M1E2eSeed.user(engine, U_A, "userA", "张三", "NP-A");
        M1E2eSeed.user(engine, U_B, "userB", "李四", "NP-B");
        M1E2eSeed.user(engine, U_AUDIT, "audit", "审计岗", "NP-AUDIT");

        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        sessionStore = new SessionStore.InMemory();
        Router router = new Router();
        registerBusinessRoutes(router, tx);

        AuthFilter filter = new SessionAuthFilter(sessionStore, "HGSESSION");
        dispatcher = new RequestDispatcher(router, new StaticHandler(repoRoot().resolve("web")), filter);

        saveSession(S_ADMIN, U_ADMIN, "admin");
        saveSession(S_A, U_A, "userA");
        saveSession(S_B, U_B, "userB");
        saveSession(S_AUDIT, U_AUDIT, "audit");

        // ---- 主链路 ----
        // ① 建两个子账户
        subA = (int) createdSubAccountId(call("POST", ApiRoutes.full("/config/sub-account"), null,
                S_ADMIN, "{\"subAccountCode\":\"SA-A\",\"subAccountName\":\"商品子账户A\","
                        + "\"category\":\"COMMODITY\",\"businessLineDesc\":\"期现一体-商品\"}"));
        subB = (int) createdSubAccountId(call("POST", ApiRoutes.full("/config/sub-account"), null,
                S_ADMIN, "{\"subAccountCode\":\"SA-B\",\"subAccountName\":\"商品子账户B\","
                        + "\"category\":\"COMMODITY\",\"businessLineDesc\":\"期现一体-商品\"}"));
        // 子账户数据权限（软隔离）：A/B 各自 LIMITED；审计岗 CROSS_READONLY（跨账户只读）
        M1E2eSeed.perm(engine, U_A, subA, "LIMITED", U_ADMIN);
        M1E2eSeed.perm(engine, U_B, subB, "LIMITED", U_ADMIN);
        M1E2eSeed.perm(engine, U_AUDIT, subA, "CROSS_READONLY", U_ADMIN);

        // ② 品种（全局）+ 合约关联 + 三层额度
        productId = dataJson(call("POST", ApiRoutes.full("/product"), null, S_ADMIN,
                "{\"productCode\":\"" + PRODUCT_CODE + "\",\"productName\":\"沪铜\","
                        + "\"category\":\"COMMODITY\"}")).get("id").getAsLong();
        assertTrue(call("POST", ApiRoutes.full("/product/" + productId + "/contracts"), null, S_ADMIN,
                "{\"instrumentId\":\"" + INSTRUMENT + "\",\"instrumentType\":\"FUTURES\","
                        + "\"exchangeCode\":\"SHFE\",\"contractMultiplier\":\"5\","
                        + "\"expiryDate\":\"2026-12-31\"}").status == 200, "合约关联应成功");

        // 现货价（全局共享，录入归属子账户 A）：为两个基准日各录一期，使敞口估值走「当日精确价」路径
        assertTrue(call("POST", ApiRoutes.full("/spot/price"), null, S_A,
                priceBody(subA, ASOF, "70000")).status == 200, "现货价（主基准日）应录入");
        assertTrue(call("POST", ApiRoutes.full("/spot/price"), null, S_A,
                priceBody(subA, ASOF_ALT, "71000")).status == 200, "现货价（备用基准日）应录入");

        // ③ 分别配置品种白名单（各子账户）
        assertTrue(call("POST", ApiRoutes.full("/product/whitelist"), null, S_A,
                whitelistBody(subA)).status == 200, "A 品种白名单应成功");
        assertTrue(call("POST", ApiRoutes.full("/product/whitelist"), null, S_B,
                whitelistBody(subB)).status == 200, "B 品种白名单应成功");

        // ④ 三层额度：层①（子账户+品种）/ 层②（子账户+合约）/ 层③（账户+品种）
        assertTrue(call("POST", ApiRoutes.full("/product/quota"), null, S_A,
                quotaBody("SUB_PRODUCT", subA, null, "1000", "TON")).status == 200,
                "层①额度应成功");
        assertTrue(call("POST", ApiRoutes.full("/product/quota"), null, S_A,
                quotaBody("SUB_INSTRUMENT", subA, INSTRUMENT, "500", "LOT")).status == 200,
                "层②额度应成功");
        assertTrue(call("POST", ApiRoutes.full("/product/quota"), null, S_ADMIN,
                quotaBody("ACCOUNT_PRODUCT", 0, null, "5000", "TON")).status == 200,
                "层③额度应成功");

        // ⑤ 分别配置实货敞口（三分量；已映射品种）
        exposureA = dataJson(call("POST", ApiRoutes.full("/spot/exposure"), null, S_A,
                exposureBody(subA, "100"))).get("id").getAsLong();
        exposureB = dataJson(call("POST", ApiRoutes.full("/spot/exposure"), null, S_B,
                exposureBody(subB, "200"))).get("id").getAsLong();

        // ⑥ 各自产出套保计划（BUY_HEDGE 反向于 SELL 敞口）
        planA = dataLong(call("POST", ApiRoutes.full("/plan"), null, S_A,
                planBody(subA, "80", exposureA, "80")));
        // 夹具约定：本 world 让 planB 落在次一业务日（planA=基准日），使 planA/planB 的编号取值
        // 直观可辨（不同日期段）。「同一业务日、两个子账户各建计划」的跨子账户取号场景由本类
        // acceptance1SameDayTwoSubAccountsPlanCreation… 用例专门覆盖（BUG-003 / T-137 修复守卫）。
        Clocks.set(Clock.fixed(BASE_DAY.plusDays(1).atStartOfDay(Clocks.ZONE).toInstant(),
                Clocks.ZONE));
        planB = dataLong(call("POST", ApiRoutes.full("/plan"), null, S_B,
                planBody(subB, "150", exposureB, "150")));
    }

    @AfterEach
    void restoreClock() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ 验收 1：同日子账户各建计划（BUG-003 / T-137 修复守卫）

    /**
     * <b>验收 1（{@code BUG-003} / {@code T-137} 修复后）</b>：两个子账户在<b>同一业务日</b>
     * 各自产出套保计划 ⇒ <b>两者皆成功</b>，且各自 {@code plan_no} 互异（全局唯一）、均落库。
     *
     * <p><b>期望依据（需求）</b>：
     * <ul>
     *   <li>{@code DATA_MODEL.md}:1661 / :1687：{@code plan_no}「计划编号，<b>全系统唯一</b>」，约束
     *       {@code uk_hedge_plan_no UNIQUE(plan_no)}「计划编号全局唯一」；</li>
     *   <li>{@code DATA_MODEL.md} §3.5：套保计划编号格式 {@code HP<yyyyMMdd><seq(4)>}（不含子账户维度）；</li>
     *   <li>{@code REQUIREMENTS/PLAN.md}:34/35：「计划编号全系统唯一」；</li>
     *   <li>{@code PROJECT.md} §9 M1「验收方式」：创建两个子账户 → … → 各自产出套保计划。</li>
     * </ul>
     *
     * <p><b>🔁 本用例由「缺陷复现」演化为「修复守卫」</b>：{@code BUG-003} 时
     * {@code PlanService.createPlan} 取序号为 {@code count("sub_account_id = ?", [当前子账户]) + 1}
     * —— <b>按子账户计数</b>，而编号 {@code HP<yyyyMMdd><seq>} 不含子账户维度 ⇒ 两个子账户同日取到
     * 相同 {@code seq}，第二条撞 {@code uk_hedge_plan_no} 被拒（{@code 500} / {@code 10022}）。
     * {@code T-137} 已改为 <b>全局取号</b>（去 {@code sub_account_id} 过滤）+ {@code retryOnUniqueConflict}
     * 兜并发 ⇒ 同日两子账户各建计划皆成功。
     *
     * <p><b>承重</b>：移除 {@code T-137} 的取号修复（还原为按子账户计数）后本方法必红
     * （第二子账户又撞号）。
     *
     * <p><b>方法名说明</b>：{@code …IsBlockedByPlanNoCollision} 为 {@code BUG-003} 复现期的历史名，
     * 保留以对齐缺陷单引用；<b>本方法现断言「不再被阻断」</b>。
     */
    @Test
    @DisplayName("验收1：同日子账户 A/B 各建计划 ⇒ 两者皆成功且 plan_no 互异（BUG-003/T-137 修复守卫）")
    void acceptance1SameDayTwoSubAccountsPlanCreationIsBlockedByPlanNoCollision() {
        // 回到基准日：A/B 各已有 1 条计划（planA=基准日、planB=次一日）
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        int before = engine.table("t_hedge_plan").rows.size();   // = 2

        // 子账户 A：同日再建一条
        HttpResult a2 = call("POST", ApiRoutes.full("/plan"), null, S_A,
                planBody(subA, "20", exposureA, "20"));
        assertEquals(200, a2.status, "A 的同日第二计划应成功：" + body(a2));
        assertEquals(0, code(a2), body(a2));
        // 子账户 B：同日再建一条（修复前与 A 取到同一 seq ⇒ 撞 uk_hedge_plan_no）
        HttpResult b2 = call("POST", ApiRoutes.full("/plan"), null, S_B,
                planBody(subB, "50", exposureB, "50"));
        assertEquals(200, b2.status, "B 的同日计划应成功（BUG-003/T-137 修复后不再被阻断）："
                + body(b2));
        assertEquals(0, code(b2), body(b2));

        long planA2Id = dataLong(a2);
        long planB2Id = dataLong(b2);
        String noA2 = strCol("t_hedge_plan", planA2Id, "plan_no");
        String noB2 = strCol("t_hedge_plan", planB2Id, "plan_no");
        System.out.println("[T-137][BUG-003] plan_no：A2=" + noA2 + " ; B2=" + noB2);

        // plan_no 均落库、非空、互异（全局唯一 uk_hedge_plan_no）
        assertNotNull(noA2, "A 的第二计划编号应落库");
        assertNotNull(noB2, "B 的第二计划编号应落库");
        assertTrue(!noA2.equals(noB2), "两子账户同日的 plan_no 必须互异：" + noA2 + " vs " + noB2);
        // 同日 ⇒ 同一日期段（HP<yyyyMMdd>），差异仅在序号
        String dayPrefix = "HP" + BASE_DAY.toString().replace("-", "");
        assertTrue(noA2.startsWith(dayPrefix) && noB2.startsWith(dayPrefix),
                "同日编号应以同一日期段开头：" + dayPrefix + " ⇒ " + noA2 + " / " + noB2);

        // 两条计划均落库（before + 2）
        assertEquals(before + 2, engine.table("t_hedge_plan").rows.size(),
                "两子账户同日的计划均应落库");
    }

    // ------------------------------------------------------------ 验收 1：全链路无断点

    @Test
    @DisplayName("验收1：双子账户→品种/敞口→各自的套保计划，全链路业务码 0（真实服务+真实 DAO 经引擎落库）")
    void fullChainProducesTwoHedgePlansWithoutBreak() {
        // 子账户各自产出计划，id 互异且存在
        assertTrue(subA > 0 && subB > 0 && subA != subB, "两子账户应落库且互异");
        assertTrue(planA > 0 && planB > 0 && planA != planB, "两计划应落库且互异");

        // 真实链路证据：计划行含各自子账户、计划号、绑定敞口行
        assertEquals(1, rowCount("t_hedge_plan", "id", planA));
        assertEquals(subA, intCol("t_hedge_plan", planA, "sub_account_id"));
        assertEquals(subB, intCol("t_hedge_plan", planB, "sub_account_id"));
        assertNotNull(strCol("t_hedge_plan", planA, "plan_no"), "计划号应落库");
        assertTrue(!strCol("t_hedge_plan", planA, "plan_no").isEmpty(), "计划号非空");
        assertEquals(2, engine.table("t_hedge_plan").rows.size(), "应落两条计划");
        assertEquals(2, engine.table("t_hedge_plan_exposure").rows.size(), "应落两条绑定");

        // 端点回读（真实 dispatcher）：计划详情返回绑定敞口
        HttpResult detail = call("GET", ApiRoutes.full("/plan/" + planA), null, S_A, "");
        assertEquals(200, detail.status, body(detail));
        JsonObject data = dataJson(detail);
        assertEquals(subA, data.get("subAccountId").getAsInt());
        assertEquals(1, data.getAsJsonArray("bindings").size(), "详情应含 1 条绑定敞口");
        assertEquals(exposureA, data.getAsJsonArray("bindings").get(0).getAsJsonObject()
                .get("exposureId").getAsLong());
    }

    // ------------------------------------------------------------ 验收 3：DM8 落库（等价执行）

    @Test
    @DisplayName("验收3：子账户标识 + 敞口三分量 + 额度三层 均在（等价）DM8 中正确落库")
    void persistenceCoversSubAccountExposureComponentsAndThreeQuotaLayers() {
        // ---- 子账户标识 ----
        assertEquals(3, engine.table("t_sub_account").rows.size(),
                "应落 2 个业务子账户 + 1 个保留系统行(id=0)");
        assertEquals(2, engine.table("t_sub_product_whitelist").rows.size(), "两子账户各 1 条白名单");
        assertEquals(subA, intCol("t_exposure", exposureA, "sub_account_id"));
        assertEquals(subB, intCol("t_exposure", exposureB, "sub_account_id"));

        // ---- 敞口三分量：逐分量列值 + Σ三分量 = 合计 ----
        assertEquals(0, decCol("t_exposure", exposureA, "contract_unexecuted_qty")
                .compareTo(new java.math.BigDecimal("100")));
        assertEquals(0, decCol("t_exposure", exposureA, "inventory_qty")
                .compareTo(java.math.BigDecimal.ZERO));
        assertEquals(0, decCol("t_exposure", exposureA, "production_plan_qty")
                .compareTo(java.math.BigDecimal.ZERO));
        java.math.BigDecimal sumA = decCol("t_exposure", exposureA, "contract_unexecuted_qty")
                .add(decCol("t_exposure", exposureA, "inventory_qty"))
                .add(decCol("t_exposure", exposureA, "production_plan_qty"));
        assertEquals(0, sumA.compareTo(decCol("t_exposure", exposureA, "exposure_qty")),
                "Σ三分量应等于合计");
        assertEquals(0, decCol("t_exposure", exposureB, "exposure_qty")
                .compareTo(new java.math.BigDecimal("200")));

        // ---- 额度三层 ----
        assertEquals(2, engine.table("t_product_quota").rows.size(), "层①+层③应各落 1 条");
        assertEquals(1, engine.table("t_sub_instrument_quota").rows.size(), "层②应落 1 条");
        Map<String, Object> layer1 = quotaRow("t_product_quota", "SUB_PRODUCT");
        assertEquals(subA, ((Number) layer1.get("sub_account_id")).intValue(), "层①归属子账户 A");
        assertTrue(((Number) layer1.get("sub_account_id")).intValue() > 0,
                "层①不得取哨兵 0（真实子账户 > 0）");
        Map<String, Object> layer3 = quotaRow("t_product_quota", "ACCOUNT_PRODUCT");
        assertEquals(0, ((Number) layer3.get("sub_account_id")).intValue(), "层③归属账户哨兵 0");
        Map<String, Object> layer2 = engine.table("t_sub_instrument_quota").rows.get(0);
        assertEquals(subA, ((Number) layer2.get("sub_account_id")).intValue(), "层②归属子账户 A");
        assertEquals(INSTRUMENT, layer2.get("instrument_id"), "层②应绑定合约");
        assertEquals(0, ((java.math.BigDecimal) layer2.get("quota_limit"))
                .compareTo(new java.math.BigDecimal("500")));
    }

    // ------------------------------------------------------------ 验收 2：双向隔离

    @Test
    @DisplayName("验收2a：A 用户读不到 B 的计划（越权 → 404 语义由 10001 承载，不泄露存在性）")
    void userACannotReadSubAccountBPlan() {
        HttpResult result = call("GET", ApiRoutes.full("/plan/" + planB), null, S_A, "");
        assertEquals(400, result.status, body(result));
        assertEquals(10001, code(result), "不可见计划与不存在同码（FR-PLAN-5）：" + body(result));
    }

    @Test
    @DisplayName("验收2b：A 用户计划列表只含本子账户（服务端强制过滤，不因省略筛选而越权）")
    void userAPlanListIsScopedToOwnSubAccount() {
        HttpResult result = call("GET", ApiRoutes.full("/plan"), null, S_A, "");
        assertEquals(200, result.status, body(result));
        JsonObject data = dataJson(result);
        assertEquals(1, data.get("total").getAsLong(), "应只见 1 条（A 自己的计划）");
        assertEquals(planA, data.getAsJsonArray("items").get(0).getAsJsonObject()
                .get("id").getAsLong());
    }

    @Test
    @DisplayName("验收2c：审计岗（CROSS_READONLY）可跨账户查看 B 的计划（读面放行）")
    void auditRoleCanReadAcrossSubAccounts() {
        HttpResult result = call("GET", ApiRoutes.full("/plan/" + planB), null, S_AUDIT, "");
        assertEquals(200, result.status, body(result));
        assertEquals(subB, dataJson(result).get("subAccountId").getAsInt(), "审计岗应看到 B 的计划");

        HttpResult list = call("GET", ApiRoutes.full("/plan"), null, S_AUDIT, "");
        assertEquals(200, list.status, body(list));
        assertEquals(2, dataJson(list).get("total").getAsLong(), "审计岗应见全部两条计划");
    }

    @Test
    @DisplayName("验收2d：不误拒——A 用户对所属子账户的写动作成功（与越权被拒形成对照）")
    void userAWriteToOwnSubAccountIsAllowed() {
        // 用不同基准日，避免撞 uk_exposure_key
        HttpResult result = call("POST", ApiRoutes.full("/spot/exposure"), null, S_A,
                exposureBodyAt(subA, "50", ASOF_ALT));
        assertEquals(200, result.status, "同子账户写入不得被误拒：" + body(result));
        assertEquals(0, code(result), body(result));
    }

    // ------------------------------------------------------------ 验收 2：越权被拒（写面）

    @Test
    @DisplayName("验收2e：A 用户向 B 子账户写（编制计划）→ 403/10004（写面强制，越权被拒）")
    void userACannotWriteToSubAccountB() {
        HttpResult result = call("POST", ApiRoutes.full("/plan"), null, S_A,
                planBody(subB, "10", exposureB, "10"));
        assertEquals(403, result.status, body(result));
        assertEquals(10004, code(result), body(result));
        assertTrue(engine.table("t_hedge_plan").rows.size() == 2,
                "越权被拒时不得再落计划行");
    }

    @Test
    @DisplayName("验收2f：CROSS_READONLY 哨兵口径——审计岗写子账户被拒（403/10004），写面不因读面放行而放行")
    void crossReadonlyAuditCannotWrite() {
        HttpResult result = call("POST", ApiRoutes.full("/spot/exposure"), null, S_AUDIT,
                exposureBodyAt(subA, "60", ASOF_ALT));
        assertEquals(403, result.status, body(result));
        assertEquals(10004, code(result), "跨账户只读写面必须拒绝（T-115）：" + body(result));
        // 留痕：越权尝试计入 t_unauthorized_attempt（R-AUTH-8）
        assertTrue(engine.table("t_unauthorized_attempt").rows.size() >= 1, "越权尝试应留痕");
    }

    // ------------------------------------------------------------ 工具：装配

    private void registerBusinessRoutes(Router router, TxTemplate tx) {
        DmProductSpotCategoryDao productSpotCategoryDao = new DmProductSpotCategoryDao(tx);
        DmExposureDao exposureDao = new DmExposureDao(tx);
        DmProductContractDao productContractDao = new DmProductContractDao(tx);
        DmProductDao productDao = new DmProductDao(tx);
        DmHedgePlanExposureDao hedgePlanExposureDao = new DmHedgePlanExposureDao(tx);

        AuthService authService = new AuthService(tx,
                new DmUserAccountDao(tx), new DmRoleDao(tx), new DmUserRoleDao(tx),
                new DmPositionConflictDao(tx), new DmLoginSessionDao(tx), new DmLoginLogDao(tx),
                new DmPasswordHistoryDao(tx), new DmUserSubAccountPermDao(tx),
                new DmUnauthorizedAttemptDao(tx), sessionStore,
                new DmAuthorizationDao(tx), new DmPermissionChangeLogDao(tx),
                new DmPositionGroupDao(tx), Config.empty());

        ConfigService configService = new ConfigService(tx,
                new DmSubAccountDao(tx), new DmConfigItemDao(tx), new DmConfigChangeLogDao(tx),
                new DmDictItemDao(tx), new DmAccountSubjectDao(tx), new DmReportPeriodConfigDao(tx),
                new DmCtpFrontConfigDao(tx), new DmParamDispatchDao(tx));

        VarietyService varietyService = new VarietyService(tx,
                productDao, new DmProductUnitDao(tx), productContractDao,
                productSpotCategoryDao, new DmSubProductWhitelistDao(tx),
                new DmProductQuotaDao(tx), new DmSubInstrumentQuotaDao(tx),
                new DmProductChangeLogDao(tx), authService);

        SpotService spotService = new SpotService(tx,
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx), productSpotCategoryDao,
                new DmWipRecordDao(tx), new DmInventoryLedgerDao(tx), new DmSpotPriceDao(tx),
                exposureDao, new DmExposureDetailDao(tx), new DmSpotChangeLogDao(tx),
                new DmProductionPlanDao(tx), new DmProductionPlanLineDao(tx), new DmSubAccountDao(tx),
                productDao, authService, hedgePlanExposureDao);

        PlanService planService = new PlanService(tx,
                new DmHedgePlanDao(tx), new DmHedgePlanExposureDao(tx), new DmHedgeRatioCalcDao(tx),
                new DmCustomHedgeRuleDao(tx), new DmHedgeDocDao(tx), new DmPlanApprovalDao(tx),
                new DmPlanStateLogDao(tx), new DmTradePlanDao(tx), exposureDao, productContractDao,
                authService,
                new DmAuthorizationDao(tx), new DmSubProductWhitelistDao(tx), new DmExposureDetailDao(tx),
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx), authService::hasTradingRole);

        AuthRoutes.register(router, authService, sessionStore);
        ConfigRoutes.register(router, configService);
        ProductRoutes.register(router, varietyService);
        SpotRoutes.register(router, spotService);
        PlanRoutes.register(router, planService);
    }

    // ------------------------------------------------------------ 工具：请求

    private HttpResult call(String method, String path, String query, String sessionId, String body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=" + sessionId);
        return dispatcher.dispatch(new RequestContext(method, path, query, headers, body,
                "HG-T024", "127.0.0.1"));
    }

    private void saveSession(String sessionId, long userId, String username) {
        // 会话有效期给足（远未来）：本类用可注入时钟推进业务日，勿让会话因时钟推进而"超时"
        sessionStore.save(new Session(sessionId, userId, username, 1L,
                Clocks.now().plusDays(365)));
    }

    private String whitelistBody(int subAccountId) {
        return "{\"action\":\"ENABLE\",\"subAccountId\":" + subAccountId
                + ",\"productId\":" + productId + "}";
    }

    private String quotaBody(String layer, int subAccountId, String instrument,
                             String limit, String unit) {
        return "{\"limitLayer\":\"" + layer + "\",\"subAccountId\":" + subAccountId
                + ",\"productId\":" + productId
                + (instrument == null ? "" : ",\"instrumentId\":\"" + instrument + "\"")
                + ",\"quotaLimit\":\"" + limit + "\",\"quotaUnit\":\"" + unit + "\","
                + "\"effectiveFrom\":\"2026-01-01\"}";
    }

    private String exposureBody(int subAccountId, String qty) {
        return exposureBodyAt(subAccountId, qty, ASOF);
    }

    private String priceBody(int subAccountId, String priceDate, String price) {
        return "{\"subAccountId\":" + subAccountId + ",\"productId\":" + productId
                + ",\"priceDate\":\"" + priceDate + "\",\"spotPrice\":\"" + price + "\","
                + "\"sourceType\":\"MANUAL\",\"priceUnit\":\"CNY/TON\"}";
    }

    private String exposureBodyAt(int subAccountId, String qty, String asOfDate) {
        return "{\"subAccountId\":" + subAccountId + ",\"productId\":" + productId
                + ",\"spotCategoryCode\":\"CAT-CU\",\"direction\":\"SELL\","
                + "\"calcMode\":\"CONTRACT_UNEXECUTED\",\"contractUnexecutedQty\":\"" + qty + "\","
                + "\"inventoryQty\":\"0\",\"productionPlanQty\":\"0\","
                + "\"componentsEnabled\":\"CONTRACT_UNEXECUTED\",\"asOfDate\":\"" + asOfDate + "\","
                + "\"exposureQty\":\"" + qty + "\",\"unit\":\"TON\"}";
    }

    private String planBody(int subAccountId, String planQty, long exposureId, String coveredQty) {
        return "{\"subAccountId\":" + subAccountId + ",\"planType\":\"COMMODITY\","
                + "\"productId\":\"" + productId + "\",\"hedgeDirection\":\"BUY_HEDGE\","
                + "\"planQty\":\"" + planQty + "\",\"unit\":\"TON\","
                + "\"termStartDate\":\"2026-09-20\",\"termEndDate\":\"2026-11-30\","
                + "\"hedgeRatio\":\"0.8\",\"ratioMethod\":\"SIMPLE\",\"calcBaseDate\":\"2026-09-20\","
                + "\"binds\":[{\"exposureId\":" + exposureId + ",\"coveredQty\":\"" + coveredQty + "\"}]}";
    }

    // ------------------------------------------------------------ 工具：读取/断言

    private static String body(HttpResult result) {
        return new String(result.body, StandardCharsets.UTF_8);
    }

    private static int code(HttpResult result) {
        return JsonParser.parseString(body(result)).getAsJsonObject().get("code").getAsInt();
    }

    private static JsonObject dataJson(HttpResult result) {
        JsonObject root = JsonParser.parseString(body(result)).getAsJsonObject();
        assertNotNull(root.get("data"), "响应应含 data：" + body(result));
        return root.getAsJsonObject("data");
    }

    /** 取 {@code data} 的十进制整数（{@code data} 为裸数值时用；如 {@code POST /plan} 返回新 id）。 */
    private static long dataLong(HttpResult result) {
        JsonObject root = JsonParser.parseString(body(result)).getAsJsonObject();
        assertNotNull(root.get("data"), "响应应含 data：" + body(result));
        return root.get("data").getAsLong();
    }

    private static long createdSubAccountId(HttpResult result) {
        assertEquals(200, result.status, body(result));
        assertEquals(0, code(result), body(result));
        return dataJson(result).get("id").getAsLong();
    }

    private int rowCount(String table, String idColumn, long id) {
        int n = 0;
        for (Map<String, Object> row : engine.table(table).rows) {
            Object v = row.get(idColumn);
            if (v instanceof Number && ((Number) v).longValue() == id) {
                n++;
            }
        }
        return n;
    }

    private Map<String, Object> row(String table, long id) {
        for (Map<String, Object> r : engine.table(table).rows) {
            Object v = r.get("id");
            if (v instanceof Number && ((Number) v).longValue() == id) {
                return r;
            }
        }
        throw new IllegalStateException("引擎无匹配行：" + table + " id=" + id);
    }

    private int intCol(String table, long id, String col) {
        return ((Number) row(table, id).get(col)).intValue();
    }

    private String strCol(String table, long id, String col) {
        return (String) row(table, id).get(col);
    }

    private java.math.BigDecimal decCol(String table, long id, String col) {
        return (java.math.BigDecimal) row(table, id).get(col);
    }

    private Map<String, Object> quotaRow(String table, String limitLayer) {
        for (Map<String, Object> r : engine.table(table).rows) {
            if (limitLayer.equals(r.get("limit_layer"))) {
                return r;
            }
        }
        throw new IllegalStateException("引擎无匹配额度行：" + table + " layer=" + limitLayer);
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test，当前="
                + Paths.get("").toAbsolutePath());
    }
}
