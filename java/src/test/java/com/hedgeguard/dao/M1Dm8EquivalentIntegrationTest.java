package com.hedgeguard.dao;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmSubProductWhitelistDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-023} <b>验收标准 2 的「集成测试」等价执行</b>（建表 → 读写 → 查询）。
 *
 * <p><b>⚠️ 未验 DM8 实机</b>：本环境无可达的 DM8 实例与驱动
 * （全盘 {@code DmJdbcDriver*.jar} 0 命中、{@code java/lib} 不存在、5236 无监听、无 {@code dmserver} 进程），
 * 故本用例**不构成** DM8 实机验证，改以**等价执行**：
 * <ol>
 *   <li><b>建表</b>：把 4 个 M1 建表脚本（{@code m1_config_auth / m1_product / m1_spot / m1_plan}）
 *       的 {@code CREATE TABLE} 逐条喂给 {@link InMemoryDmEngine}——引擎按 DDL 语义登记列/NOT NULL/
 *       主键/唯一/外键/CHECK（非仅断言 SQL 字符串）；</li>
 *   <li><b>读写</b>：由**真实 DAO**（{@code DmProductDao} / {@code DmSubProductWhitelistDao} /
 *       {@code DmExposureDao}）生成 {@code INSERT}/{@code SELECT}/{@code UPDATE}，经引擎真正执行、按语义求值；</li>
 *   <li><b>查询</b>：断言分页（{@code COUNT} 包裹 + {@code LIMIT ? OFFSET ?}）、排序、按列过滤的**实际返回行**。</li>
 * </ol>
 *
 * <p>引擎求值覆盖面与明确不覆盖项见 {@link InMemoryDmEngine} 类注释；本用例在
 * {@link #engineDisclosesUncheckedChecks()} 中显式披露「登记但未求值」的复杂 CHECK 数量。
 *
 * <p>结论口径：本用例通过 ⇒ 「建表 → 读写 → 查询」链路在**等价引擎**上跑通；
 * 「DM8 实机」**仍待部署方在可达实例上复跑**（{@code T-024} 端到端验收承接）。
 */
class M1Dm8EquivalentIntegrationTest {

    /** 4 个 M1 建表脚本（相对仓库根）。 */
    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql");

    /** 4 脚本合计建表数（config_auth 20 + product 8 + spot 10 + plan 8 = 46）。 */
    private static final int EXPECTED_TABLES = 46;

    private InMemoryDmEngine engine;
    private TxTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
    }

    // ------------------------------------------------------------ 建表

    @Test
    @DisplayName("建表：4 个 M1 脚本逐条执行后登记 46 张表，关键约束（主键/唯一/外键/CHECK）已按语义登记")
    void ddlScriptsCreateSchema() {
        assertEquals(EXPECTED_TABLES, engine.tableCount(),
                "4 个 M1 建表脚本应合计登记 46 张表，实际 " + engine.tableCount());

        InMemoryDmEngine.Table product = engine.table("t_product");
        assertNotNull(product, "t_product 应已建表");
        assertTrue(product.columns.containsKey("product_code"), "t_product 应有 product_code 列");
        assertTrue(product.columns.get("product_code").notNull, "product_code 应 NOT NULL");
        assertTrue(product.columns.get("id").identity, "id 应 IDENTITY 自增");
        assertTrue(hasUnique(product, "uk_product_code"), "t_product 应登记唯一约束 uk_product_code");
        assertTrue(hasUnique(product, "pk_t_product"), "t_product 应登记主键 pk_t_product");

        InMemoryDmEngine.Table exposure = engine.table("t_exposure");
        assertNotNull(exposure, "t_exposure 应已建表");
        assertTrue(hasFk(exposure, "fk_exposure_sub", "t_sub_account"), "t_exposure 应有外键到 t_sub_account");
        assertTrue(hasFk(exposure, "fk_exposure_product", "t_product"), "t_exposure 应有外键到 t_product");
        assertTrue(hasUnique(exposure, "uk_exposure_key"), "t_exposure 应登记 uk_exposure_key");
        assertTrue(hasCheck(exposure, "ck_exposure_direction"), "t_exposure 应登记 ck_exposure_direction");
        // 三分量三列均为 NOT NULL（DATA_MODEL.md §2.4 敞口快照口径）
        for (String col : Arrays.asList("contract_unexecuted_qty", "inventory_qty", "production_plan_qty")) {
            assertTrue(exposure.columns.get(col).notNull, "t_exposure." + col + " 应 NOT NULL");
        }
    }

    @Test
    @DisplayName("建表：引擎显式披露可求值 CHECK 与「登记但未求值」的复杂 CHECK 数量（不夸大覆盖面）")
    void engineDisclosesUncheckedChecks() {
        long enforced = engine.enforcedCheckCount();
        long unchecked = engine.uncheckedCheckCount();
        System.out.println("[T-023] 内存引擎 CHECK：可求值=" + enforced + "，登记未求值=" + unchecked);
        assertTrue(enforced >= 10, "可求值的单列 IN / = 字面量 CHECK 应不少于 10 条，实际 " + enforced);
        assertTrue(enforced + unchecked > 0, "应至少登记一条 CHECK");
    }

    // ------------------------------------------------------------ 读写

    @Test
    @DisplayName("读写：真实 DmProductDao 生成 INSERT/SELECT 经引擎执行——主键回填、按 id/编码读回、空值语义正确")
    void insertAndReadBackThroughRealDao() {
        DmProductDao dao = new DmProductDao(tx);
        Product product = product("CU", "沪铜", "COMMODITY");

        assertEquals(1, dao.insert(product, 7L), "insert 影响 1 行");
        assertEquals(1L, product.id, "自增主键应回填实体");
        assertEquals(1, engine.table("t_product").rows.size(), "引擎中应有 1 行");
        assertEquals(7L, product.createdBy, "审计 created_by = operatorId");
        assertNotNull(product.createdTime, "审计 created_time 由时钟填充");
        assertEquals(1, product.rowVersion, "insert 后 row_version = 1");

        Product byId = dao.findById(product.id);
        assertNotNull(byId, "按 id 应读回");
        assertEquals("CU", byId.productCode);
        assertEquals("沪铜", byId.productName);
        assertEquals("COMMODITY", byId.category);
        assertEquals("ENABLED", byId.status);
        assertEquals("CNY", byId.currency);
        assertNull(byId.remark, "可空列 remark 应读回 null");

        Product byCode = dao.findByCode("CU");
        assertNotNull(byCode, "按编码应读回");
        assertEquals(product.id, byCode.id);

        assertNull(dao.findById(999L), "不存在的 id 应返回 null");
    }

    @Test
    @DisplayName("读写：updateById 乐观锁经引擎求值——命中推进 row_version，陈旧版本影响 0 行")
    void optimisticLockUpdateAdvancesVersion() {
        DmProductDao dao = new DmProductDao(tx);
        Product product = product("AL", "沪铝", "COMMODITY");
        dao.insert(product, 1L);

        product.status = "DISABLED";
        assertEquals(1, dao.updateById(product, 2L), "乐观锁命中 → 影响 1 行");
        assertEquals(2, product.rowVersion, "成功后版本推进 1 → 2");
        assertEquals("DISABLED", dao.findById(product.id).status, "取值已落引擎");

        Product stale = product("AL", "沪铝", "COMMODITY");
        stale.id = product.id;
        stale.status = "ENABLED";
        stale.rowVersion = 1;   // 陈旧版本
        assertEquals(0, dao.updateById(stale, 3L), "版本冲突 → 影响 0 行");
        assertEquals("DISABLED", dao.findById(product.id).status, "冲突时不得改写");
    }

    @Test
    @DisplayName("查询：findPage 的 COUNT 包裹 + LIMIT/OFFSET 经引擎求值——总数与分页行数正确、排序稳定")
    void pagedQueryReturnRowsAndCount() {
        DmProductDao dao = new DmProductDao(tx);
        for (String code : Arrays.asList("A1", "A2", "A3")) {
            dao.insert(product(code, "品种" + code, "COMMODITY"), 1L);
        }

        PageQuery first = PageQuery.of("1", "10", "id,asc", null);
        PageResult<Product> page1 = dao.findPage(first, null, Collections.emptyList());
        assertEquals(3L, page1.total(), "COUNT 包裹应求值为 3");
        assertEquals(3, page1.items().size(), "第一页 10 条上限内应返回 3 行");
        assertEquals("A1", page1.items().get(0).productCode, "id 升序：首行应为 A1");

        PageQuery second = PageQuery.of("2", "10", "id,asc", null);
        PageResult<Product> page2 = dao.findPage(second, null, Collections.emptyList());
        assertEquals(0, page2.items().size(), "第二页应无行（OFFSET 越过 3 行）");
        assertEquals(3L, page2.total(), "总数与页码无关");
    }

    // ------------------------------------------------------------ 约束类（重复插入 / 空值 / 孤儿数据）

    @Test
    @DisplayName("约束类·重复插入：同 product_code 二次插入 → 唯一约束拒绝，且引擎行数不变")
    void duplicateInsertOnUniqueColumnIsRejected() {
        DmProductDao dao = new DmProductDao(tx);
        dao.insert(product("CU", "沪铜", "COMMODITY"), 1L);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> dao.insert(product("CU", "沪铜副本", "COMMODITY"), 1L),
                "重复 product_code 应被唯一约束拒绝");
        assertTrue(e.getMessage().contains("违反唯一约束") && e.getMessage().contains("uk_product_code"),
                "错误应点名唯一约束 uk_product_code：" + e.getMessage());
        assertEquals(1, engine.table("t_product").rows.size(), "被拒后不得落库");
        assertEquals(1L, engine.rejectedByUnique(), "引擎应记录 1 次唯一冲突");
    }

    @Test
    @DisplayName("约束类·空值：NOT NULL 列（product_code）写入 null → 引擎按 DDL 语义拒绝")
    void nullOnNotNullColumnIsRejected() {
        DmProductDao dao = new DmProductDao(tx);
        Product broken = product("CU", "沪铜", "COMMODITY");
        broken.productCode = null;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> dao.insert(broken, 1L), "NOT NULL 列写 null 应被拒绝");
        assertTrue(e.getMessage().contains("NOT NULL") && e.getMessage().contains("product_code"),
                "错误应点名 NOT NULL 列 product_code：" + e.getMessage());
        assertEquals(0, engine.table("t_product").rows.size(), "被拒后不得落库");
        assertEquals(1L, engine.rejectedByNotNull(), "引擎应记录 1 次 NOT NULL 冲突");
    }

    @Test
    @DisplayName("约束类·孤儿数据：外键列指向不存在的父行 → 拒绝（t_sub_product_whitelist → t_sub_account）")
    void orphanForeignKeyIsRejected() throws SQLException {
        // 父行：一个品种（供另一外键满足）；不建 t_sub_account 行 ⇒ sub_account_id 成孤儿
        DmProductDao productDao = new DmProductDao(tx);
        Product product = product("CU", "沪铜", "COMMODITY");
        productDao.insert(product, 1L);

        DmSubProductWhitelistDao whitelistDao = new DmSubProductWhitelistDao(tx);
        SubProductWhitelist wl = new SubProductWhitelist();
        wl.subAccountId = 9999;   // 无对应 t_sub_account 行
        wl.productId = product.id;
        wl.status = "ENABLED";
        wl.dispatchStatus = "PENDING";

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> whitelistDao.insert(wl, 1L), "孤儿外键应被拒绝");
        assertTrue(e.getMessage().contains("外键") && e.getMessage().contains("t_sub_account"),
                "错误应点名外键与引用表：" + e.getMessage());
        assertEquals(0, engine.table("t_sub_product_whitelist").rows.size(), "被拒后不得落库");
        assertEquals(1L, engine.rejectedByForeignKey(), "引擎应记录 1 次外键冲突");

        // 反向对照：补齐 id=9999 的父行后同一写入应成功（证明拒绝确由「孤儿」引起，而非其它列约束）
        seedSubAccount(9999);
        assertEquals(1, whitelistDao.insert(wl, 1L), "补齐父行后写入成功");
        assertEquals(1, engine.table("t_sub_product_whitelist").rows.size());
    }

    @Test
    @DisplayName("约束类·取值域：可求值 CHECK（单列 IN）拒绝越域取值（t_product.category='OTHER'）")
    void checkConstraintRejectsOutOfRangeValue() {
        DmProductDao dao = new DmProductDao(tx);
        Product bad = product("CU", "沪铜", "OTHER");   // 值域仅 COMMODITY/TRADE

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> dao.insert(bad, 1L), "越域 category 应被 CHECK 拒绝");
        assertTrue(e.getMessage().contains("CHECK") && e.getMessage().contains("ck_product_category"),
                "错误应点名 CHECK ck_product_category：" + e.getMessage());
        assertEquals(1L, engine.rejectedByCheck(), "引擎应记录 1 次 CHECK 冲突");
    }

    // ------------------------------------------------------------ 敞口三分量（DB 级往返）

    @Test
    @DisplayName("敞口三分量：真实 DmExposureDao 写入三列分量后读回——三分量列各存其值且 Σ=合计")
    void exposureThreeComponentsRoundTrip() throws SQLException {
        seedSubAccount(1);
        DmProductDao productDao = new DmProductDao(tx);
        Product product = product("CU", "沪铜", "COMMODITY");
        productDao.insert(product, 1L);

        DmExposureDao exposureDao = new DmExposureDao(tx);
        Exposure exposure = new Exposure();
        exposure.subAccountId = 1;
        exposure.productId = product.id;
        exposure.spotCategoryCode = "__MAPPED__";
        exposure.direction = "BUY";
        exposure.calcMode = "COMBINED";
        exposure.contractUnexecutedQty = new BigDecimal("40.0000");
        exposure.inventoryQty = new BigDecimal("10.0000");
        exposure.productionPlanQty = new BigDecimal("10.0000");
        exposure.componentsEnabled = "CONTRACT_UNEXECUTED,INVENTORY,PRODUCTION_PLAN";
        exposure.snapshotId = "SNAP-T023-1";
        exposure.snapshotTime = LocalDateTime.of(2026, 9, 20, 10, 0);
        exposure.combinedComponents = "CONTRACT_UNEXECUTED+INVENTORY+PRODUCTION_PLAN";
        exposure.asOfDate = LocalDate.of(2026, 9, 20);
        exposure.calcTime = LocalDateTime.of(2026, 9, 20, 10, 0);
        exposure.exposureQty = new BigDecimal("60.0000");   // = 40 + 10 + 10
        exposure.unit = "TON";
        exposure.priceMissingFlag = "N";
        exposure.nonCurrentPriceFlag = "N";
        exposure.coveredQty = BigDecimal.ZERO;
        exposure.status = "PENDING_HEDGE";
        exposure.unmappedFlag = "N";

        assertEquals(1, exposureDao.insert(exposure, 1L), "敞口快照应写入");

        Exposure loaded = exposureDao.findById(exposure.id);
        assertNotNull(loaded, "应能按 id 读回敞口");
        assertEquals(0, loaded.contractUnexecutedQty.compareTo(new BigDecimal("40.0000")));
        assertEquals(0, loaded.inventoryQty.compareTo(new BigDecimal("10.0000")));
        assertEquals(0, loaded.productionPlanQty.compareTo(new BigDecimal("10.0000")));
        BigDecimal sum = loaded.contractUnexecutedQty.add(loaded.inventoryQty).add(loaded.productionPlanQty);
        assertEquals(0, sum.compareTo(loaded.exposureQty),
                "三分量之和应等于合计（" + sum + " vs " + loaded.exposureQty + "）");
    }

    // ------------------------------------------------------------ 工具

    private static Product product(String code, String name, String category) {
        Product p = new Product();
        p.productCode = code;
        p.productName = name;
        p.category = category;
        p.categoryManaged = "Y";
        p.status = "ENABLED";
        p.isDomesticExchange = "Y";
        p.currency = "CNY";
        return p;
    }

    /** 经真实 JDBC 路径补一条子账户父行（供外键/敞口用例）。 */
    private void seedSubAccount(int id) throws SQLException {
        try (java.sql.Connection conn = engine.create();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO t_sub_account (id, sub_account_code, sub_account_name, status, created_by)"
                             + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setInt(1, id);
            ps.setString(2, "SA-" + id);
            ps.setString(3, "子账户" + id);
            ps.setString(4, "ENABLED");
            ps.setLong(5, 1L);
            ps.executeUpdate();
        }
    }

    private static boolean hasUnique(InMemoryDmEngine.Table table, String name) {
        for (InMemoryDmEngine.Unique u : table.uniques) {
            if (u.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFk(InMemoryDmEngine.Table table, String name, String refTable) {
        for (InMemoryDmEngine.ForeignKey fk : table.fks) {
            if (fk.name.equals(name) && fk.refTable.equals(refTable)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCheck(InMemoryDmEngine.Table table, String name) {
        for (InMemoryDmEngine.Check c : table.checks) {
            if (c.name.equals(name)) {
                return true;
            }
        }
        return false;
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
