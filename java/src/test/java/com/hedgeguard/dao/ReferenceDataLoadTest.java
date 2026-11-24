package com.hedgeguard.dao;

import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.ReferenceSeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参考数据装载回归（{@code docs/DEPLOY/reference/} 的 CI 承载）。
 *
 * <p>本用例把 {@link ReferenceSeed}（由 {@code gen_reference.py --target java-fixture}
 * 生成的播种件）真正装入 {@link InMemoryDmEngine}，证明：
 * <ul>
 *   <li>{@code --groups master}（140 行）与 {@code --groups master,demo}（246 行）两种
 *       初始化均装载成功，且 NOT NULL / UNIQUE / FK / CHECK 四类拒绝计数全为 0；</li>
 *   <li>重复装载幂等：{@code load} 与「先 loadMaster 再 load」都不产生重复行、不产生拒绝；</li>
 *   <li>外键自然键值被解析成真实父行 id（含多态软外键 {@code source_id}）；</li>
 *   <li>科目模板覆盖全部 8 个 {@code biz_scene}，使凭证科目建议不会撞 10015；</li>
 *   <li>零落盘：用户行只含非凭证明文标记，且强制首次登录改密。</li>
 * </ul>
 *
 * <p>本环境无 DM8 实例；本用例是等价执行证据，<b>不替代</b> DM8 实机建表/播种验证
 * （{@code docs/DEPLOY/README.md} §8.1 的 U-1 承接）。DM8 / SQLite 装载脚本的
 * {@code WHERE NOT EXISTS} 幂等性由 {@code gen_reference.py --target sqlite} 冒烟覆盖。
 */
class ReferenceDataLoadTest {

    /** 与 docs/DEPLOY/README.md §7.1 一致的建表顺序（含后置外键回补脚本）。 */
    private static final String[] DDL_ORDER = {
            "m1_config_auth.sql", "m1_product.sql", "m1_spot.sql", "m1_plan.sql",
            "m2_trade.sql", "m2_settle.sql", "m2_fund.sql", "m3_risk.sql",
            "m3_audit.sql", "m4_acct.sql",
    };

    private static final int MASTER_ROWS = 140;
    private static final int FULL_ROWS = 246;
    private static final String PENDING_SETUP_MARKER = "__PENDING_SETUP__";

    /** surefire 工作目录通常是 java/；向上找含 docs/DATA_MODEL.md 的仓库根。 */
    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根（应含 docs/DATA_MODEL.md），当前目录="
                + Paths.get("").toAbsolutePath());
    }

    /** 全新引擎 + 按 §7.1 顺序建全部表。 */
    private static InMemoryDmEngine freshEngine() throws Exception {
        InMemoryDmEngine engine = new InMemoryDmEngine();
        Path schema = repoRoot().resolve("docs/DEPLOY/schema");
        for (String f : DDL_ORDER) {
            engine.executeScript(Files.readString(schema.resolve(f)));
        }
        return engine;
    }

    private static long totalRows(InMemoryDmEngine engine) {
        long n = 0;
        for (InMemoryDmEngine.Table t : engine.tables().values()) {
            n += t.rows.size();
        }
        return n;
    }

    private static void assertNoRejections(InMemoryDmEngine engine) {
        assertEquals(0, engine.rejectedByNotNull(), "不应有 NOT NULL 拒绝");
        assertEquals(0, engine.rejectedByUnique(), "不应有 UNIQUE 拒绝");
        assertEquals(0, engine.rejectedByForeignKey(), "不应有 FK 拒绝");
        assertEquals(0, engine.rejectedByCheck(), "不应有 CHECK 拒绝");
    }

    private static List<Map<String, Object>> rows(InMemoryDmEngine engine, String table) {
        InMemoryDmEngine.Table t = engine.tables().get(table);
        assertNotNull(t, "表应已建：" + table);
        return t.rows;
    }

    private static Map<String, Object> findRow(InMemoryDmEngine engine, String table,
                                                String col, Object value) {
        for (Map<String, Object> r : rows(engine, table)) {
            if (value.equals(r.get(col))) {
                return r;
            }
        }
        throw new AssertionError("未找到 " + table + "." + col + "=" + value);
    }

    private static long asLong(Object v) {
        assertNotNull(v, "数值列不应为空");
        assertTrue(v instanceof Number, "应为数值，实际 " + v.getClass());
        return ((Number) v).longValue();
    }

    @Test
    @DisplayName("--groups master：140 行、四类拒绝为 0")
    void masterOnlyLoadsCleanly() throws Exception {
        InMemoryDmEngine engine = freshEngine();
        ReferenceSeed.loadMaster(engine);
        assertEquals(MASTER_ROWS, totalRows(engine));
        assertNoRejections(engine);
    }

    @Test
    @DisplayName("--groups master,demo：246 行、四类拒绝为 0")
    void fullLoadIsComplete() throws Exception {
        InMemoryDmEngine engine = freshEngine();
        ReferenceSeed.load(engine);
        assertEquals(FULL_ROWS, totalRows(engine));
        assertNoRejections(engine);
    }

    @Test
    @DisplayName("重复 load 幂等：不产生重复行、不产生拒绝")
    void repeatedLoadIsIdempotent() throws Exception {
        InMemoryDmEngine engine = freshEngine();
        ReferenceSeed.load(engine);
        ReferenceSeed.load(engine);
        assertEquals(FULL_ROWS, totalRows(engine));
        assertNoRejections(engine);
    }

    @Test
    @DisplayName("先 loadMaster 再 load 增量补齐，不产生重复行")
    void masterThenFullIsIncremental() throws Exception {
        InMemoryDmEngine engine = freshEngine();
        ReferenceSeed.loadMaster(engine);
        ReferenceSeed.load(engine);
        assertEquals(FULL_ROWS, totalRows(engine));
        assertNoRejections(engine);
    }

    @Test
    @DisplayName("FK 自然键值解析为真实父行 id（含多态软外键）")
    void foreignKeysResolveToRealRows() throws Exception {
        InMemoryDmEngine engine = freshEngine();
        ReferenceSeed.load(engine);

        // product_id：符号值 CU 解析为真实品种 id（>0），且哨兵/品类双态成立
        Map<String, Object> exposure = findRow(engine, "t_exposure", "snapshot_id",
                "SNAP-2026-11-20-CU-001");
        assertTrue(asLong(exposure.get("product_id")) > 0, "已映射敞口 product_id 须 > 0");
        assertEquals("N", exposure.get("unmapped_flag"));
        assertEquals("__MAPPED__", exposure.get("spot_category_code"));

        // 层③ ACCOUNT_PRODUCT 的子账户须解析到哨兵行 id=0
        Map<String, Object> layer3 = findRow(engine, "t_product_quota", "limit_layer",
                "ACCOUNT_PRODUCT");
        assertEquals(0L, asLong(layer3.get("sub_account_id")), "层③须取作用域哨兵 0");

        // order_id -> t_trade_order.id
        Map<String, Object> fill = findRow(engine, "t_trade_fill", "trade_id",
                "FILL-2026-11-20-001");
        Map<String, Object> order = findRow(engine, "t_trade_order", "client_order_ref",
                "ORD-2026-11-20-001");
        assertEquals(asLong(order.get("id")), asLong(fill.get("order_id")));

        // 多态软外键 source_id -> t_inventory_ledger.id
        Map<String, Object> detail = findRow(engine, "t_exposure_detail", "source_type",
                "INVENTORY_LEDGER");
        Map<String, Object> inventory = rows(engine, "t_inventory_ledger").get(0);
        assertEquals(asLong(inventory.get("id")), asLong(detail.get("source_id")));

        // 透传自然键外键：凭证分录科目须存在于科目字典
        Map<String, Object> voucherLine = rows(engine, "t_voucher_line").get(0);
        assertNotNull(findRow(engine, "t_account_subject", "subject_code",
                voucherLine.get("subject_code")));
    }

    @Test
    @DisplayName("科目模板覆盖 8 个 biz_scene 且均为启用模板")
    void accountSubjectTemplateCoversAllScenes() throws Exception {
        InMemoryDmEngine engine = freshEngine();
        ReferenceSeed.load(engine);
        Set<String> scenes = new HashSet<>();
        for (Map<String, Object> r : rows(engine, "t_account_subject")) {
            assertEquals("Y", r.get("is_template_default"));
            assertEquals("Y", r.get("editable_by_enterprise"));
            assertEquals("ENABLED", r.get("status"));
            scenes.add(String.valueOf(r.get("biz_scene")));
        }
        assertEquals(8, scenes.size(), "应覆盖 ck_account_subject_biz_scene 全部 8 个场景");
        for (String s : new String[]{"OPEN_POSITION", "CLOSE_PNL", "COMMISSION", "MARGIN_CHANGE",
                "FLOAT_PNL", "PREMIUM", "HEDGE_EFFECT", "OTHER"}) {
            assertTrue(scenes.contains(s), "缺场景 " + s);
        }
    }

    @Test
    @DisplayName("零落盘：用户行只含非凭证明文标记且强制改密")
    void noCredentialsArePersisted() throws Exception {
        InMemoryDmEngine engine = freshEngine();
        ReferenceSeed.load(engine);
        List<Map<String, Object>> users = rows(engine, "t_user");
        assertEquals(7, users.size());
        for (Map<String, Object> u : users) {
            assertEquals(PENDING_SETUP_MARKER, u.get("password_hash"),
                    "不得写入任何口令/哈希：" + u.get("username"));
            assertEquals("Y", u.get("must_change_password"));
        }
    }
}
