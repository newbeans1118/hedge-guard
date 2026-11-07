package com.hedgeguard.service;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Product;
import com.hedgeguard.dao.entity.ProductChangeLog;
import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.impl.DmProductChangeLogDao;
import com.hedgeguard.dao.impl.DmSubAccountDao;
import com.hedgeguard.dao.impl.DmProductDao;
import com.hedgeguard.dao.impl.DmSubProductWhitelistDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.handler.product.ProductExportHandler;
import com.hedgeguard.web.handler.product.ProductHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-116} PRODUCT 读取面「需求面 > 实现面」缺口补齐（{@code FR-PRODUCT-5} 输入/输出列 +
 * {@code FR-PRODUCT-8} 导出筛选一致与无数据语义 + {@code FR-PRODUCT-14} 筛选输入）。
 *
 * <p><b>为何用「真实 DAO + 等价引擎」而不是假 DAO</b>：{@code T-116} 的判据是「筛选<b>下推到 SQL</b>」
 * （计数与取数<b>两条</b> SQL 都带筛选）与「导出与列表<b>同口径</b>」——假 DAO 里自己实现一遍过滤
 * 会把实现缺陷（漏下推、只筛 SELECT 不筛 COUNT）一并隐藏。故本用例把
 * {@code docs/DEPLOY/schema/m1_config_auth.sql} + {@code m1_product.sql} 真喂给
 * {@link InMemoryDmEngine} 建表，再经<b>真实</b> {@code DmProductDao} / {@code DmSubProductWhitelistDao} /
 * {@code DmProductChangeLogDao} 读写，断言落在<b>实际返回行</b>与 {@code total} 上。
 *
 * <p><b>{@code total} 为何是关键断言</b>：{@code AbstractDmDao#findPage} 用<b>独立</b> COUNT SQL 求
 * {@code total}。若筛选只拼进 SELECT 而漏拼 COUNT，{@code items} 已过滤而 {@code total} 仍是全集 ⇒
 * 分页错乱。本用例对「{@code total} 等于筛选后条数」与「不筛选时 {@code total} 更大」<b>成对</b>断言，
 * 使「漏筛 COUNT」与「整个筛选被删」两种缺陷都能变红。
 *
 * <p><b>隔离身份</b>：本用例用哨兵 {@code 0}（跨账户只读）⇒ 走<b>不加 scope</b> 路径，从而把断言聚焦在
 * 「筛选」本身（scope 与筛选的<b>合并</b>由 {@code DmProductDaoScopedSqlTest} 形状用例 +
 * {@code ScopedSqlPredicateEffectTest} 语义守卫覆盖）。理由：{@code T-152} 已实测
 * {@code InMemoryDmEngine} <b>不解析</b> {@code id IN (SELECT …)} 子查询（静默错值），而
 * {@code t_product} 的 scope 载体正是该子查询形态，故 scope 生效与否不能用本引擎断言。
 */
class ProductReadFaceT116Test {

    /** 建表脚本（相对仓库根）：t_product / t_sub_product_whitelist / t_product_change_log + t_sub_account。 */
    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql");

    private InMemoryDmEngine engine;
    private DmSubAccountDao subAccountDao;
    private DmProductDao productDao;
    private DmSubProductWhitelistDao whitelistDao;
    private DmProductChangeLogDao changeLogDao;
    private VarietyService service;

    private int sa1;
    private int sa2;
    private long cuId;
    private long alId;
    private long rbId;
    private long pbId;
    private long snId;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        for (String rel : SCRIPTS) {
            Path file = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(file), "缺少建表脚本：" + file);
            engine.executeScript(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        }
        TxTemplate tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        productDao = new DmProductDao(tx);
        whitelistDao = new DmSubProductWhitelistDao(tx);
        changeLogDao = new DmProductChangeLogDao(tx);
        // 哨兵 0 ⇒ 跨账户只读：不加 scope（筛选用例只验筛选；scope 见类注释）
        service = new VarietyService(tx, productDao, null, null, null, whitelistDao, null, null,
                changeLogDao, uid -> Collections.singletonList(SubAccountScope.ALL_ACCOUNT_SENTINEL),
                uid -> { });

        subAccountDao = new DmSubAccountDao(tx);
        sa1 = seedSubAccount(1);
        sa2 = seedSubAccount(2);
        cuId = insertProduct("CU", "沪铜", "COMMODITY", "ENABLED");
        alId = insertProduct("AL", "沪铝", "COMMODITY", "ENABLED");
        rbId = insertProduct("RB", "螺纹钢", "TRADE", "ENABLED");
        pbId = insertProduct("PB", "沪铅", "COMMODITY", "DISABLED");
        snId = insertProduct("SN", "沪锡", "COMMODITY", "DISABLED");
        // 子账户 sa1 在册：CU；AL 已移出（DISABLED 行 = 历史保留，不算在册）。子账户 sa2 在册：RB
        insertWhitelist(sa1, cuId, "ENABLED");
        insertWhitelist(sa1, alId, "DISABLED");
        insertWhitelist(sa2, rbId, "ENABLED");
    }

    // ============================================================ 验收 1：列表筛选（下推 COUNT + SELECT）

    @Test
    @DisplayName("验收 1/4①：状态=停用 ⇒ 结果全为停用；且 total = 停用条数 < 不筛选时的 total（COUNT 与 SELECT 同带筛选）")
    void statusFilterAppliesToCountAndSelect() {
        VarietyService.ProductFilter filter = VarietyService.ProductFilter.none();
        filter.status = "DISABLED";

        PageResult<Product> filtered = service.listProducts(1L, firstPage(), filter);
        PageResult<Product> all = service.listProducts(1L, firstPage());

        assertEquals(2L, filtered.total(), "total 须为停用条数（若 COUNT 漏拼筛选会是 5）");
        assertEquals(2, filtered.items().size());
        for (Product row : filtered.items()) {
            assertEquals("DISABLED", row.status, "FR-PRODUCT-5 验收 1：停用筛选下所有记录状态均为停用");
            assertTrue(row.id == pbId || row.id == snId, "命中行须为 PB/SN，实际=" + row.productCode);
        }
        assertEquals(5L, all.total(), "对照基线：不筛选时 total = 5（证明上面的 2 确由筛选得出）");

        VarietyService.ProductFilter enabled = VarietyService.ProductFilter.none();
        enabled.status = "ENABLED";
        assertEquals(3L, service.listProducts(1L, firstPage(), enabled).total(),
                "反向对照：启用筛选 total = 3（筛选非恒假）");
    }

    @Test
    @DisplayName("验收 1：编码/名称关键字、分类、子账户筛选逐项下推（含恒假：无在册品种的子账户 ⇒ 空而非全量）")
    void keywordCategoryAndSubAccountFilters() {
        VarietyService.ProductFilter byName = VarietyService.ProductFilter.none();
        byName.productName = "沪";
        List<String> names = codes(service.listProducts(1L, firstPage(), byName));
        assertEquals(Arrays.asList("AL", "CU", "PB", "SN"), sorted(names),
                "名称关键字 LIKE %沪% ⇒ 沪铜/沪铝/沪铅/沪锡（不筛状态）：实际=" + names);

        VarietyService.ProductFilter byCode = VarietyService.ProductFilter.none();
        byCode.productCode = "RB";
        assertEquals(Collections.singletonList("RB"), codes(service.listProducts(1L, firstPage(), byCode)),
                "编码关键字 ⇒ 命中 RB");

        VarietyService.ProductFilter byCategory = VarietyService.ProductFilter.none();
        byCategory.category = "TRADE";
        assertEquals(Collections.singletonList("RB"),
                codes(service.listProducts(1L, firstPage(), byCategory)), "分类 TRADE ⇒ 仅 RB");

        VarietyService.ProductFilter bySubAccount = VarietyService.ProductFilter.none();
        bySubAccount.subAccountId = sa1;
        PageResult<Product> scopedToSubAccount = service.listProducts(1L, firstPage(), bySubAccount);
        assertEquals(Collections.singletonList("CU"), codes(scopedToSubAccount),
                "子账户 1 在册品种 = CU（AL 的白名单行已 DISABLED ⇒ 不算在册）");
        assertEquals(1L, scopedToSubAccount.total());

        VarietyService.ProductFilter noInBook = VarietyService.ProductFilter.none();
        noInBook.subAccountId = 99;
        PageResult<Product> none = service.listProducts(1L, firstPage(), noInBook);
        assertTrue(none.items().isEmpty(), "无在册品种的子账户 ⇒ 空集（严禁退化为不过滤 = 全量 5 条）");
        assertEquals(0L, none.total(), "恒假条件亦须作用于 COUNT");

        VarietyService.ProductFilter combined = VarietyService.ProductFilter.none();
        combined.productCode = "CU";
        combined.status = "DISABLED";
        assertEquals(0L, service.listProducts(1L, firstPage(), combined).total(),
                "多条件为 AND：CU 为启用 ⇒ 与停用组合后无命中");
    }

    @Test
    @DisplayName("验收 1：筛选取值越域 ⇒ 10001（明确报错，不静默忽略）")
    void invalidFilterValuesRejected() {
        VarietyService.ProductFilter badStatus = VarietyService.ProductFilter.none();
        badStatus.status = "DELETED";
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> service.listProducts(1L, firstPage(), badStatus)).errorCode(),
                "品种无 DELETED 状态（DATA_MODEL）⇒ 越域须报错");

        VarietyService.ProductFilter badCategory = VarietyService.ProductFilter.none();
        badCategory.category = "OTHER";
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> service.listProducts(1L, firstPage(), badCategory)).errorCode());
    }

    // ============================================================ 验收 2：导出与列表同口径

    @Test
    @DisplayName("验收 2/4②：同一筛选下导出条数 == 列表 total，且导出仅含符合筛选的品种（顺序亦一致）")
    void exportMatchesListUnderSameFilter() {
        VarietyService.ProductFilter enabled = VarietyService.ProductFilter.none();
        enabled.status = "ENABLED";

        PageResult<Product> page = service.listProducts(1L, firstPage(), enabled);
        List<String> csv = service.exportProducts(1L, enabled);

        assertEquals(page.total() + 1, (long) csv.size(),
                "导出数据行数须等于列表 total（FR-PRODUCT-8 验收 1「条数与列表一致」）");
        assertEquals(codes(page), csvCodes(csv), "导出顺序须与列表缺省排序（created_time 倒序）一致");
        assertTrue(csvCodes(csv).containsAll(Arrays.asList("CU", "AL", "RB")));
        assertFalse(csvCodes(csv).contains("PB"), "停用品种不得出现在启用筛选的导出中");

        VarietyService.ProductFilter disabled = VarietyService.ProductFilter.none();
        disabled.status = "DISABLED";
        assertEquals(service.listProducts(1L, firstPage(), disabled).total() + 1,
                (long) service.exportProducts(1L, disabled).size(),
                "换筛选后仍一致（导出不是固定全量）");
        assertEquals(Arrays.asList("PB", "SN"), sorted(csvCodes(service.exportProducts(1L, disabled))),
                "停用筛选的导出仅含停用品种");

        // 反向对照：不带筛选的导出含全部 5 条（证明上面的 3 条确由筛选得出）
        assertEquals(6, service.exportProducts(1L).size(), "不筛选 ⇒ 表头 + 5 行");
    }

    @Test
    @DisplayName("验收 2：导出的子账户筛选与列表同口径（子账户 2 ⇒ 仅 RB）")
    void exportHonoursSubAccountFilter() {
        VarietyService.ProductFilter filter = VarietyService.ProductFilter.none();
        filter.subAccountId = sa2;

        assertEquals(Collections.singletonList("RB"), csvCodes(service.exportProducts(1L, filter)),
                "子账户 2 在册品种 = RB");
        assertEquals(service.listProducts(1L, firstPage(), filter).total() + 1,
                (long) service.exportProducts(1L, filter).size(), "导出与列表同口径");
    }

    // ============================================================ 验收 6：无数据导出语义

    @Test
    @DisplayName("验收 6：无数据导出 ⇒ 不得返回「200 + 仅表头 CSV」，须为可判定的错误信封（10015 + 文案）")
    void exportWithoutDataIsNotHeaderOnlyCsv() {
        seedChangeLogs();
        ProductExportHandler handler = new ProductExportHandler(service);

        // 无命中筛选：编码关键字在库内不存在
        BizException e = assertThrows(BizException.class,
                () -> handler.handle(ctx("GET", "/product/export", "productCode=NOSUCHCODE", null)),
                "无数据导出不得返回 200 + 仅表头");
        assertEquals(ErrorCode.DATA_INSUFFICIENT, e.errorCode(),
                "须为可判定的错误码（不是 200 空 CSV）");
        assertTrue(e.getMessage().contains("无可导出数据"),
                "FR-PRODUCT-8 输出列要求提示「无可导出数据」：实际=" + e.getMessage());

        // 对照：有数据时仍是 200 + CSV，且不止表头
        HttpResult ok = handler.handle(ctx("GET", "/product/export", null, null));
        assertEquals(200, ok.status);
        String body = new String(ok.body, StandardCharsets.UTF_8);
        assertTrue(body.startsWith(String.join(",", VarietyService.CSV_HEADER)),
                "首行须为表头：" + body);
        assertTrue(body.split("\r\n").length >= 2, "有数据时须含数据行，不得只有表头：" + body);
    }

    // ============================================================ 验收 7：change-log 筛选 + 倒序

    @Test
    @DisplayName("验收 7：时间范围 + 变更类型筛选下推 SQL（结果全在范围内且类型全匹配），输出按时间倒序")
    void changeLogFilterByTimeRangeAndType() {
        seedChangeLogs();

        VarietyService.ChangeLogFilter all = new VarietyService.ChangeLogFilter();
        List<ProductChangeLog> allRows = service.listChangeLogs(cuId, all);
        assertEquals(4, allRows.size(), "无筛选 ⇒ 4 条");
        assertEquals(Arrays.asList("ENABLE", "DISABLE", "UPDATE", "CREATE"), types(allRows),
                "无筛选亦须按 change_time 倒序（FR-PRODUCT-14 输出列）");

        VarietyService.ChangeLogFilter rangeAndType = new VarietyService.ChangeLogFilter();
        rangeAndType.fromTime = LocalDateTime.of(2026, 9, 5, 0, 0);
        rangeAndType.toTime = LocalDateTime.of(2026, 9, 30, 0, 0);
        rangeAndType.changeTypes = Collections.singletonList("DISABLE");
        List<ProductChangeLog> filtered = service.listChangeLogs(cuId, rangeAndType);
        assertEquals(1, filtered.size(), "范围内且类型匹配者仅 1 条（09-10 DISABLE）");
        assertEquals("DISABLE", filtered.get(0).changeType);
        for (ProductChangeLog row : filtered) {
            assertFalse(row.changeTime.isBefore(rangeAndType.fromTime), "结果须落在时间范围内：" + row.changeTime);
            assertFalse(row.changeTime.isAfter(rangeAndType.toTime), "结果须落在时间范围内：" + row.changeTime);
            assertTrue(rangeAndType.changeTypes.contains(row.changeType), "类型须全匹配：" + row.changeType);
        }

        VarietyService.ChangeLogFilter typesOnly = new VarietyService.ChangeLogFilter();
        typesOnly.changeTypes = Arrays.asList("ENABLE", "UPDATE");
        assertEquals(Arrays.asList("ENABLE", "UPDATE"), types(service.listChangeLogs(cuId, typesOnly)),
                "仅按类型筛（不设时间）⇒ ENABLE + UPDATE，仍按时间倒序");

        VarietyService.ChangeLogFilter rangeOnly = new VarietyService.ChangeLogFilter();
        rangeOnly.fromTime = LocalDateTime.of(2026, 9, 2, 0, 0);
        assertEquals(Arrays.asList("ENABLE", "DISABLE", "UPDATE"),
                types(service.listChangeLogs(cuId, rangeOnly)), "仅按下界筛（含边界当天）");
    }

    @Test
    @DisplayName("验收 7：handler 解析 fromTime/toTime/changeType —— 仅给日期时含当日；格式非法/倒置 ⇒ 10001")
    void changeLogHandlerParsesFilters() {
        seedChangeLogs();
        com.hedgeguard.web.handler.product.ProductChangeLogHandler handler =
                new com.hedgeguard.web.handler.product.ProductChangeLogHandler(service);

        HttpResult result = handler.handle(ctx("GET", "/product/change-log",
                "productId=" + cuId + "&fromTime=2026-09-05&toTime=2026-09-30&changeType=disable",
                null));
        Map<?, ?> body = JsonCodec.fromJson(new String(result.body, StandardCharsets.UTF_8), Map.class);
        List<?> data = (List<?>) body.get("data");
        assertEquals(1, data.size(), "日期边界须含当日 + changeType 大小写不敏感：" + data);
        assertEquals("DISABLE", ((Map<?, ?>) data.get(0)).get("changeType"));

        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> handler.handle(ctx("GET", "/product/change-log",
                        "productId=" + cuId + "&fromTime=not-a-date", null))).errorCode(),
                "时间格式非法须明确报错");
        assertEquals(ErrorCode.PARAM_INVALID, assertThrows(BizException.class,
                () -> handler.handle(ctx("GET", "/product/change-log",
                        "productId=" + cuId + "&fromTime=2026-09-30&toTime=2026-09-01", null))).errorCode(),
                "起止倒置须明确报错");
    }

    // ============================================================ 验收 3/4③：输出两列

    @Test
    @DisplayName("验收 3/4③：列表输出含「子账户」与「最近变更时间」两列（子账户 = 在册白名单；时间为 updated_time 口径）")
    void listViewCarriesNewColumns() {
        ProductHandler handler = new ProductHandler(service);
        HttpResult result = handler.handle(ctx("GET", "/product", null, null));

        Map<?, ?> body = JsonCodec.fromJson(new String(result.body, StandardCharsets.UTF_8), Map.class);
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        List<?> items = (List<?>) data.get("items");
        assertEquals(5, items.size(), "5 条品种");

        // 先断言「两列存在」（删除列即红，且红得可读——不靠后续取值 NPE 间接暴露）
        for (Object row : items) {
            Map<?, ?> item = (Map<?, ?>) row;
            assertTrue(item.containsKey("subAccountIds"), "输出列须含子账户：" + item);
            assertTrue(item.containsKey("lastModifiedTime"), "输出列须含最近变更时间：" + item);
            assertNotNull(item.get("subAccountIds"), "子账户列不得为 null（无在册行应为空数组）：" + item);
            assertNotNull(item.get("lastModifiedTime"),
                    "最近变更时间（updated_time 为空则回退 created_time）不得为 null：" + item);
        }

        Map<?, ?> cu = itemOf(items, "CU");
        assertEquals(Arrays.asList(sa1), ints(cu.get("subAccountIds")),
                "CU 的在册子账户 = [" + sa1 + "]（AL 的 DISABLED 白名单行不算在册）：" + cu);
        Map<?, ?> al = itemOf(items, "AL");
        assertEquals(Collections.emptyList(), ints(al.get("subAccountIds")),
                "AL 的白名单行已 DISABLED ⇒ 在册子账户为空数组（不为 null）：" + al);
        Map<?, ?> rb = itemOf(items, "RB");
        assertEquals(Arrays.asList(sa2), ints(rb.get("subAccountIds")));

        // 口径可判定（二选一，本实现取 updated_time）：
        //   ① 留痕最新一条是 2026-09-20（seedChangeLogs）——若口径取 change_log，此处必然等于它；
        //   ② 口径取 updated_time ⇒ 修改品种后 updated_time 推进，输出跟着变，且等于库内 updated_time。
        seedChangeLogs();
        Product cuRow = productDao.findById(cuId);
        cuRow.remark = "T-116 口径探针";
        assertEquals(1, productDao.updateById(cuRow, 1L));
        LocalDateTime storedUpdatedTime = productDao.findById(cuId).updatedTime;
        HttpResult after = handler.handle(ctx("GET", "/product", null, null));
        Map<?, ?> bodyAfter = JsonCodec.fromJson(new String(after.body, StandardCharsets.UTF_8), Map.class);
        List<?> itemsAfter = (List<?>) ((Map<?, ?>) bodyAfter.get("data")).get("items");
        Object lastModified = itemOf(itemsAfter, "CU").get("lastModifiedTime");
        assertEquals(storedUpdatedTime.toString(), String.valueOf(lastModified),
                "最近变更时间须取自 t_product.updated_time：实际=" + lastModified);
        assertFalse("2026-09-20T12:00".equals(String.valueOf(lastModified)),
                "口径**不是** product_change_log 最新一条（留痕最新一条为 2026-09-20T12:00）");
    }

    // ------------------------------------------------------------ 夹具

    private PageQuery firstPage() {
        return PageQuery.of("1", "20", "created_time,desc", VarietyService.DEFAULT_SORT_FIELD);
    }

    private static List<String> codes(PageResult<Product> page) {
        List<String> out = new ArrayList<>();
        for (Product row : page.items()) {
            out.add(row.productCode);
        }
        return out;
    }

    private static List<String> csvCodes(List<String> csv) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < csv.size(); i++) {
            out.add(csv.get(i).split(",", -1)[0]);
        }
        return out;
    }

    private static List<String> sorted(List<String> values) {
        List<String> out = new ArrayList<>(values);
        Collections.sort(out);
        return out;
    }

    /** JSON 数字解出来的可能是 {@code Double}（gson 无类型信息）⇒ 统一按整数取。 */
    private static List<Integer> ints(Object jsonArray) {
        List<Integer> out = new ArrayList<>();
        for (Object value : (List<?>) jsonArray) {
            out.add(((Number) value).intValue());
        }
        return out;
    }

    private static List<String> types(List<ProductChangeLog> rows) {
        List<String> out = new ArrayList<>();
        for (ProductChangeLog row : rows) {
            out.add(row.changeType);
        }
        return out;
    }

    private static Map<?, ?> itemOf(List<?> items, String productCode) {
        for (Object row : items) {
            Map<?, ?> item = (Map<?, ?>) row;
            if (productCode.equals(item.get("productCode"))) {
                return item;
            }
        }
        throw new AssertionError("列表未包含品种 " + productCode + "；实际=" + items);
    }

    private static RequestContext ctx(String method, String path, String query, String body) {
        return new RequestContext(method, ApiRoutes.full(path), query, Collections.emptyMap(),
                body == null ? "" : body, "HGT116", "127.0.0.1");
    }

    private long insertProduct(String code, String name, String category, String status) {
        Product product = new Product();
        product.productCode = code;
        product.productName = name;
        product.category = category;
        product.categoryManaged = "Y";
        product.status = status;
        product.isDomesticExchange = "Y";
        product.currency = "CNY";
        if ("DISABLED".equals(status)) {
            product.disabledReason = "T-116 夹具停用";
        }
        assertEquals(1, productDao.insert(product, 1L), "品种写入失败：" + code);
        return product.id;
    }

    private void insertWhitelist(int subAccountId, long productId, String status) {
        SubProductWhitelist row = new SubProductWhitelist();
        row.subAccountId = subAccountId;
        row.productId = productId;
        row.status = status;
        row.dispatchStatus = "PENDING";
        if ("DISABLED".equals(status)) {
            row.disabledReason = "移出白名单";
        }
        assertEquals(1, whitelistDao.insert(row, 1L), "白名单写入失败：" + subAccountId + "/" + productId);
    }

    /** CU 的四条留痕：CREATE(09-01) / UPDATE(09-02) / DISABLE(09-10) / ENABLE(09-20)。 */
    private void seedChangeLogs() {
        insertChangeLog("CREATE", null, LocalDateTime.of(2026, 9, 1, 9, 0));
        insertChangeLog("UPDATE", "product_name", LocalDateTime.of(2026, 9, 2, 10, 0));
        insertChangeLog("DISABLE", null, LocalDateTime.of(2026, 9, 10, 11, 0));
        insertChangeLog("ENABLE", null, LocalDateTime.of(2026, 9, 20, 12, 0));
    }

    private void insertChangeLog(String type, String field, LocalDateTime time) {
        ProductChangeLog log = new ProductChangeLog();
        log.productId = cuId;
        log.changeType = type;
        log.changedField = field;
        log.operatorId = 1L;
        log.changeTime = time;
        log.changeReason = "T-116 夹具";
        assertEquals(1, changeLogDao.insert(log, 1L), "留痕写入失败：" + type);
    }

    /**
     * 建子账户（走真实 {@code DmSubAccountDao}；返回自增主键）。
     *
     * <p>不用裸 SQL：{@code E5}「dao 之外不得出现 SQL」对本包生效（{@code tools/arch-check}）。
     *
     * @param seq 序号（用于编码/名称）
     * @return 子账户 ID
     */
    private int seedSubAccount(int seq) {
        SubAccount row = new SubAccount();
        row.subAccountCode = "SA-" + seq;
        row.subAccountName = "子账户" + seq;
        row.status = "ENABLED";
        row.category = "COMMODITY";
        row.isDefault = "N";
        assertEquals(1, subAccountDao.insert(row, 1L), "子账户写入失败：" + seq);
        return row.id;
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
