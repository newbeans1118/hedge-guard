package com.hedgeguard.service;

import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.handler.spot.SpotExposureHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/v1/spot/exposure}（列表）与 {@code GET /api/v1/spot/exposure/{id}}（详情）
 * 的<b>跨子账户数据隔离</b>（{@code FR-SPOT-15} 处理规则「仅返回有权限的子账户范围」/ 验收 3 /
 * {@code NFR-SEC-7} / {@code FR-AUTH-13} 验收 1；{@code T-107}）。
 *
 * <p><b>为何要这组用例</b>：{@code SpotService.listPendingHedge}/{@code getExposure} 此前无用户级 scope，
 * 可见集由调用方传入的 {@code subAccountId} 决定（{@code SpotExposureHandler.java:73}）
 * ⇒ <b>任意调用者可读取他人子账户敞口</b>。本组把断言落在<b>服务层施加的过滤路径与可见集合</b>上，
 * 覆盖四类 + 反向（防误拒）+ 详情面 + 端点接线：
 * <ul>
 *   <li><b>有权限（LIMITED）</b>：列表只见自己子账户的敞口；</li>
 *   <li><b>无任何授权（空集）</b>：<b>返回空，绝不返回全部</b>（最易实现错、最危险的一条）；</li>
 *   <li><b>跨账户只读（含哨兵 {@code 0}）</b>：不加过滤，可见全部（读面放行）；</li>
 *   <li><b>跨子账户</b>：他人子账户的敞口不可见（列表与详情<b>均</b>如此）；</li>
 *   <li><b>取数入口缺位（{@code null}）</b>：按空集 fail-closed。</li>
 * </ul>
 *
 * <p>假 DAO 忠实模拟带 scope 的 SQL 语义（{@code sub_account_id IN (...)}；空集 ⇒ 恒假），
 * 该语义本身另由 {@code DmExposureDaoScopedSqlTest} 以<b>真实 DAO</b> 覆盖。
 * 完全离线（{@code NFR-TEST-2}）。
 */
class SpotExposureIsolationTest {

    private static final PageQuery QUERY =
            PageQuery.of("1", "20", "exposure_qty,desc", "exposure_qty");

    // ------------------------------------------------------------ 四类隔离（列表面）

    @Test
    @DisplayName("隔离①有权限（LIMITED）：列表只见自己子账户敞口，且走 scoped 路径、scope 为自己子账户")
    void limitedUserSeesOnlyOwnSubAccount() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(1L, 7, "100"));
        dao.rows.add(exposure(2L, 8, "200"));

        PageResult<Exposure> page = service(dao, uid -> Arrays.asList(7)).listPendingHedge(1L, QUERY, null);

        assertEquals(1, page.items().size(), "仅可见子账户 7 的敞口");
        assertEquals(1L, page.items().get(0).id);
        assertEquals(1L, page.total(), "total 亦按过滤后计");
        assertTrue(dao.lastListScoped, "有权限用户须走 scoped 路径");
        assertEquals(Arrays.asList(7), dao.lastScope.allowed(), "scope 集合 = 可见子账户");
    }

    @Test
    @DisplayName("隔离④跨子账户：他人子账户敞口不可见（列表）")
    void otherSubAccountInvisibleInList() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(9L, 8, "300"));   // 归属子账户 8

        PageResult<Exposure> page = service(dao, uid -> Arrays.asList(7)).listPendingHedge(1L, QUERY, null);

        assertTrue(page.items().isEmpty(), "子账户 7 不可见子账户 8 的敞口");
        assertEquals(0L, page.total());
    }

    @Test
    @DisplayName("隔离②无任何授权（空集）：返回空，绝不返回全部（安全默认）")
    void emptyGrantSeesNothingNotAll() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(1L, 7, "100"));
        dao.rows.add(exposure(2L, 8, "200"));

        PageResult<Exposure> page =
                service(dao, uid -> Collections.emptyList()).listPendingHedge(1L, QUERY, null);

        assertTrue(page.items().isEmpty(), "无授权 ⇒ 空集（严禁退化为不过滤 = 全可见）");
        assertEquals(0L, page.total(), "总数也须为 0");
        assertTrue(dao.lastListScoped, "空集须走 scoped 路径（恒假条件），不得走不加过滤路径");
        assertTrue(dao.lastScope.isEmpty(), "scope 须为空集（恒假）");

        // 反退化对照：同一 DAO 的"不加过滤"路径确实返回全部 —— 证明上面的空集并非数据集本身为空
        assertEquals(2, dao.findPage(QUERY, null, Collections.emptyList()).items().size(),
                "对照基线：不加过滤时数据集含 2 条");
    }

    @Test
    @DisplayName("隔离③跨账户只读（含哨兵 0）：不加过滤、可见全部（读面放行）")
    void crossReadonlySeesAllWithoutScope() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(1L, 7, "100"));
        dao.rows.add(exposure(2L, 8, "200"));

        PageResult<Exposure> page = service(dao,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL)).listPendingHedge(1L, QUERY, null);

        assertEquals(2, page.items().size(), "跨账户只读可见全部子账户");
        assertFalse(dao.lastListScoped, "跨账户只读 ⇒ 不加过滤（走 findPage）");
        assertNull(dao.lastScope, "跨账户只读不得构造 scope（哨兵 0 不可进 IN 列表）");
    }

    @Test
    @DisplayName("反向（防误拒）：合法用户对自己可见子账户的敞口照常可见")
    void legitUserSeesOwnExposures() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(1L, 7, "100"));
        dao.rows.add(exposure(2L, 7, "200"));

        PageResult<Exposure> page = service(dao, uid -> Arrays.asList(7)).listPendingHedge(1L, QUERY, null);

        assertEquals(2, page.items().size(), "本子账户敞口须全部可见（不得误拒）");
    }

    @Test
    @DisplayName("收窄过滤：subAccountId 参数仅在可见集内收窄，不能扩大可见集")
    void subAccountFilterNarrowsWithinVisibleSetOnly() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(1L, 7, "100"));
        dao.rows.add(exposure(2L, 8, "200"));

        // 可见集 [7,8]，收窄到 8 ⇒ 只剩 8
        PageResult<Exposure> narrowed =
                service(dao, uid -> Arrays.asList(7, 8)).listPendingHedge(1L, QUERY, 8);
        assertEquals(1, narrowed.items().size());
        assertEquals(8, narrowed.items().get(0).subAccountId);

        // 可见集 [7]，收窄到 8（越权取值）⇒ 与 scope 取交集 ⇒ 空
        PageResult<Exposure> denied =
                service(dao, uid -> Arrays.asList(7)).listPendingHedge(1L, QUERY, 8);
        assertTrue(denied.items().isEmpty(), "越权收窄不得借参数扩大可见集");
    }

    // ------------------------------------------------------------ 详情面（验收 3 / 4）

    @Test
    @DisplayName("隔离④跨子账户（详情）：getExposure 对他人子账户敞口不可见 ⇒ 同一返回 null")
    void otherSubAccountInvisibleInDetail() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(9L, 8, "300"));

        assertNull(service(dao, uid -> Arrays.asList(7)).getExposure(9L, 1L),
                "子账户 7 不可见子账户 8 的敞口详情（不可见 ≡ 不存在）");
        // 有权限身份则可见
        assertNotNull(service(dao, uid -> Arrays.asList(8)).getExposure(9L, 1L),
                "子账户 8 可见自己的敞口详情（防误拒）");
    }

    @Test
    @DisplayName("隔离③详情：跨账户只读（哨兵 0）可见任意子账户敞口详情")
    void crossReadonlySeesAnyDetail() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(9L, 8, "300"));

        assertNotNull(service(dao,
                uid -> Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL)).getExposure(9L, 1L));
    }

    @Test
    @DisplayName("隔离②详情：空授权 ⇒ getExposure 恒 null（不得返回任意敞口）")
    void emptyGrantDetailIsNull() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(1L, 7, "100"));

        assertNull(service(dao, uid -> Collections.emptyList()).getExposure(1L, 1L));
    }

    // ------------------------------------------------------------ fail-closed / 端点接线

    @Test
    @DisplayName("fail-closed：取数入口缺位（null）时列表与详情皆空/皆 null")
    void nullVisibilityFailsClosed() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(1L, 7, "100"));

        assertTrue(service(dao, null).listPendingHedge(1L, QUERY, null).items().isEmpty(),
                "入口缺位 ⇒ 列表空");
        assertNull(service(dao, null).getExposure(1L, 1L), "入口缺位 ⇒ 详情 null");
    }

    @Test
    @DisplayName("端点接线：handler 用会话身份解析隔离，响应 data 为 PageData 分页信封（非裸数组）")
    void handlerThreadsSessionIdentityAndEmitsPageEnvelope() {
        ScopedExposureDao dao = new ScopedExposureDao();
        dao.rows.add(exposure(1L, 7, "100"));
        dao.rows.add(exposure(2L, 8, "200"));

        // 身份 42 ⇒ 仅子账户 7；其余身份 ⇒ 哨兵（可见全部）
        SubAccountVisibility visibility = uid -> uid == 42L
                ? Arrays.asList(7) : Arrays.asList(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        SpotExposureHandler handler = new SpotExposureHandler(service(dao, visibility));

        RequestContext ctx = new RequestContext("GET", ApiRoutes.full("/spot/exposure"),
                "page=1&size=20", Collections.emptyMap(), "", "HGISO", "127.0.0.1");
        ctx.bindSession(new Session("s-42", 42L, "u42", 1L, null));

        HttpResult result = handler.handle(ctx);

        assertEquals(200, result.status);
        assertTrue(dao.lastListScoped, "身份 42 应触发 scoped 过滤");
        assertEquals(Arrays.asList(7), dao.lastScope.allowed(), "过滤集合须来自会话身份（而非请求参数）");

        Map<?, ?> body = JsonCodec.fromJson(new String(result.body, StandardCharsets.UTF_8), Map.class);
        Object data = body.get("data");
        assertTrue(data instanceof Map, "响应 data 须为分页信封对象（非裸数组），实际=" + data);
        Map<?, ?> page = (Map<?, ?>) data;
        assertTrue(page.containsKey("items"), "信封须含 items：" + page);
        assertTrue(page.containsKey("total"), "信封须含 total：" + page);
        assertTrue(page.containsKey("page"), "信封须含 page：" + page);
        assertTrue(page.containsKey("size"), "信封须含 size：" + page);
    }

    // ------------------------------------------------------------ 工具

    private static SpotService service(ExposureDao dao, SubAccountVisibility visibility) {
        // listPendingHedge/getExposure 只用 exposureDao 与可见性入口；其余依赖构造仅赋值（可传 null）。
        return new SpotService(null, null, null, null, null, null, null, dao, null, null,
                null, null, null, null, visibility, null);
    }

    private static Exposure exposure(long id, int subAccountId, String qty) {
        Exposure e = new Exposure();
        e.id = id;
        e.subAccountId = subAccountId;
        e.productId = 1L;
        e.spotCategoryCode = "CU";
        e.direction = "BUY";
        e.calcMode = "COMBINED";
        e.exposureQty = new BigDecimal(qty);
        e.status = "PENDING_HEDGE";
        return e;
    }

    /**
     * 内存假 DAO：忠实模拟 {@code DmExposureDao} 的 scoped 语义
     * （{@code sub_account_id IN (...)}；空集 ⇒ 恒假），并记录最近一次列表调用所走的路径。
     *
     * <p>{@code findPage}（不加过滤）= 仅按 {@code extraWhere} 的 {@code status/sub_account_id}
     * 过滤；{@code findPageScoped} = 先叠 scope 再叠 {@code extraWhere}；{@code findByIdScoped}
     * = {@code id = ? AND scope}（不可见 ≡ 不存在）。
     */
    private static final class ScopedExposureDao extends UnsupportedBaseDao<Exposure, Long>
            implements ExposureDao {

        private final List<Exposure> rows = new ArrayList<>();
        private boolean lastListScoped;
        private SubAccountScope lastScope;

        private List<Exposure> select(SubAccountScope scope, List<Object> extraArgs) {
            Object status = (extraArgs != null && !extraArgs.isEmpty()) ? extraArgs.get(0) : "PENDING_HEDGE";
            Object subFilter = (extraArgs != null && extraArgs.size() > 1) ? extraArgs.get(1) : null;
            List<Exposure> hit = new ArrayList<>();
            for (Exposure r : rows) {
                if (scope != null) {
                    if (scope.isEmpty() || !scope.allowed().contains(r.subAccountId)) {
                        continue;
                    }
                }
                if (status != null && !status.equals(r.status)) {
                    continue;
                }
                if (subFilter != null && !subFilter.equals(r.subAccountId)) {
                    continue;
                }
                hit.add(r);
            }
            return hit;
        }

        @Override
        public PageResult<Exposure> findPage(PageQuery query, String where, List<Object> whereArgs) {
            this.lastListScoped = false;
            this.lastScope = null;
            List<Exposure> hit = select(null, whereArgs);
            return new PageResult<>(hit, hit.size(), query.page(), query.size());
        }

        @Override
        public PageResult<Exposure> findPageScoped(PageQuery query, SubAccountScope scope,
                                                   String extraWhere, List<Object> extraArgs) {
            this.lastListScoped = true;
            this.lastScope = scope;
            List<Exposure> hit = select(scope, extraArgs);
            return new PageResult<>(hit, hit.size(), query.page(), query.size());
        }

        @Override
        public Exposure findById(Long id) {
            for (Exposure row : rows) {
                if (row.id == id) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public Exposure findByIdScoped(long id, SubAccountScope scope) {
            if (scope == null || scope.isEmpty()) {
                return null;
            }
            for (Exposure row : rows) {
                if (row.id == id && scope.allowed().contains(row.subAccountId)) {
                    return row;
                }
            }
            return null;
        }
    }
}
