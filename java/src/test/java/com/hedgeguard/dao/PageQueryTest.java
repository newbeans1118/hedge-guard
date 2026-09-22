package com.hedgeguard.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PageQuery} 单元测试（{@code ARCHITECTURE.md} §4.1.1 分页约定）。 */
class PageQueryTest {

    @Test
    @DisplayName("默认值与 size 白名单（{10,20,50,100}，默认 20）")
    void defaultsAndWhitelist() {
        PageQuery q = PageQuery.of(null, null, null, "id");
        assertEquals(1, q.page());
        assertEquals(20, q.size());
        assertEquals(0, q.offset());
        assertEquals("id", q.sortField());
        assertTrue(q.ascending());

        assertEquals(50, PageQuery.of("2", "50", null, "id").size());
        assertEquals(100, PageQuery.of("1", "100", null, "id").size());
        assertEquals(10, PageQuery.of("1", "10", null, "id").size());
    }

    @Test
    @DisplayName("size 越界或页码非法即明确报错（不静默截断）")
    void rejectsOutOfRangeValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PageQuery.of("1", "25", null, "id"));
        assertTrue(e.getMessage().contains("size"));
        assertThrows(IllegalArgumentException.class, () -> PageQuery.of("0", "20", null, "id"));
        assertThrows(IllegalArgumentException.class, () -> PageQuery.of("abc", "20", null, "id"));
        assertThrows(IllegalArgumentException.class, () -> PageQuery.of("1", "x", null, "id"));
    }

    @Test
    @DisplayName("排序解析：field,asc / field,desc；字段名须为合法标识符（防注入）")
    void parsesSort() {
        assertEquals("create_time", PageQuery.of(null, null, "create_time,desc", "id").sortField());
        assertEquals("DESC", PageQuery.of(null, null, "create_time,desc", "id").direction());
        assertEquals("ASC", PageQuery.of(null, null, "create_time", "id").direction());
        assertEquals("ASC", PageQuery.of(null, null, "create_time,ASC", "id").direction());
        assertThrows(IllegalArgumentException.class,
                () -> PageQuery.of(null, null, "create_time; drop table x", "id"));
        assertThrows(IllegalArgumentException.class,
                () -> PageQuery.of(null, null, "create_time,sideways", "id"));
        assertThrows(IllegalArgumentException.class, () -> PageQuery.of(null, null, null, null));
    }

    @Test
    @DisplayName("偏移量按 (page-1)*size 计算")
    void computesOffset() {
        assertEquals(0, PageQuery.of("1", "20", null, "id").offset());
        assertEquals(40, PageQuery.of("3", "20", null, "id").offset());
        assertEquals(100, PageQuery.of("3", "50", null, "id").offset());
    }

    @Test
    @DisplayName("分页结果容器保持只读与元数据一致")
    void pageResultIsImmutable() {
        PageResult<String> r = new PageResult<>(java.util.Arrays.asList("a", "b"), 2L, 1, 20);
        assertEquals(2, r.items().size());
        assertEquals(2L, r.total());
        assertEquals(1, r.page());
        assertEquals(20, r.size());
        assertThrows(UnsupportedOperationException.class, () -> r.items().add("c"));
    }

    // ------------------------------------------------------------ T-096：缺省方向重载 + 稳定排序

    @Test
    @DisplayName("T-096 ①：5 参重载传 \"desc\" 且不传 sort ⇒ 方向为 DESC，字段为 defaultSortField")
    void fiveArgDefaultDirectionApplied() {
        PageQuery q = PageQuery.of(null, null, null, "created_time", "desc");
        assertEquals("created_time", q.sortField());
        assertEquals("DESC", q.direction());
        assertFalse(q.ascending());
    }

    @Test
    @DisplayName("T-096 ②：4 参重载不传 sort ⇒ 方向恒 ASC（回归护栏；等价 5 参传 \"asc\"）")
    void fourArgDefaultsToAsc() {
        PageQuery four = PageQuery.of(null, null, null, "id");
        assertEquals("ASC", four.direction());
        assertTrue(four.ascending());

        PageQuery five = PageQuery.of(null, null, null, "id", "asc");
        assertEquals(five.direction(), four.direction(), "4 参 ⟺ 5 参以 \"asc\" 传参");
        assertEquals(five.orderBy(), four.orderBy());

        // 只给字段名不给方向（4 参）⇒ 仍为 ASC
        assertEquals("ASC", PageQuery.of(null, null, "node_seq", "id").direction());
    }

    @Test
    @DisplayName("T-096：defaultDirection 大小写不敏感；非法值（含 null / 空）抛 IllegalArgumentException")
    void defaultDirectionCaseInsensitiveAndValidated() {
        assertEquals("DESC", PageQuery.of(null, null, null, "id", "DESC").direction());
        assertEquals("DESC", PageQuery.of(null, null, null, "id", "Desc").direction());
        assertEquals("DESC", PageQuery.of(null, null, null, "id", " desc ").direction());
        assertEquals("ASC", PageQuery.of(null, null, null, "id", "ASC").direction());

        for (String bad : new String[]{"sideways", "ascending", "", "  ", "1"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PageQuery.of(null, null, null, "id", bad), "非法缺省方向应报错：" + bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> PageQuery.of(null, null, null, "id", null));
    }

    @Test
    @DisplayName("T-096：sort 显式给方向 ⇒ 覆盖 defaultDirection；未给方向 ⇒ 用 defaultDirection")
    void explicitSortDirectionOverridesDefault() {
        assertEquals("ASC", PageQuery.of(null, null, "create_time,asc", "id", "desc").direction(),
                "sort 显式 asc 须覆盖缺省 desc");
        assertEquals("DESC", PageQuery.of(null, null, "create_time,desc", "id", "asc").direction(),
                "sort 显式 desc 须覆盖缺省 asc");
        assertEquals("DESC", PageQuery.of(null, null, "create_time", "id", "desc").direction(),
                "只给字段名不给方向 ⇒ 用缺省方向 desc");
        assertEquals("DESC", PageQuery.of(null, null, null, "created_time", "desc").direction(),
                "未传 sort ⇒ 用缺省方向 desc");
    }

    @Test
    @DisplayName("T-096：多键 orderBy 子句（末键唯一即全序）；sortField()/direction() 仍取首键（向后兼容）")
    void multiKeyOrderByClause() {
        PageQuery q = PageQuery.of(null, null, "exec_deadline,asc,id,asc", "exec_deadline");
        assertEquals("exec_deadline ASC, id ASC", q.orderBy());
        assertEquals("exec_deadline", q.sortField(), "首键（向后兼容）");
        assertEquals("ASC", q.direction(), "首键方向（向后兼容）");

        PageQuery mixed = PageQuery.of(null, null, "exec_deadline,desc,id,asc", "exec_deadline");
        assertEquals("exec_deadline DESC, id ASC", mixed.orderBy());
        assertEquals("exec_deadline", mixed.sortField());
        assertEquals("DESC", mixed.direction());

        // 单键 orderBy 与旧 4 参语义一致
        assertEquals("create_time DESC", PageQuery.of(null, null, "create_time,desc", "id").orderBy());
        assertEquals("id ASC", PageQuery.of(null, null, null, "id").orderBy());
    }

    @Test
    @DisplayName("T-096：畸形的多键 sort（奇数个 token / 空字段 / 非法方向）明确报错，不静默")
    void malformedMultiKeySortRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PageQuery.of(null, null, "exec_deadline,asc,id", "exec_deadline"));
        assertThrows(IllegalArgumentException.class,
                () -> PageQuery.of(null, null, ",asc", "exec_deadline"));
        assertThrows(IllegalArgumentException.class,
                () -> PageQuery.of(null, null, "exec_deadline,asc,id,sideways", "exec_deadline"));
    }

    @Test
    @DisplayName("T-096 ③：多键（末键唯一）⇒ 同排序值跨页不重复、不漏行（两页并集基数 = 总数）")
    void stableOrderByPreventsCrossPageDuplicates() {
        List<Row> data = rows();
        PageQuery p1 = PageQuery.of("1", "10", "exec_deadline,asc,id,asc", "exec_deadline");
        PageQuery p2 = PageQuery.of("2", "10", "exec_deadline,asc,id,asc", "exec_deadline");

        // 两次分页的「等值组内序」取相反方向（模拟真实 DB 对等值行次序不作保证）
        List<Long> union = new ArrayList<>();
        union.addAll(ids(page(p1, data, Row.BY_ID_ASC)));
        union.addAll(ids(page(p2, data, Row.BY_ID_DESC)));

        assertEquals(data.size(), union.size(), "两页各 10 行，并集应恰为 20 行（无重复）：" + union);
        assertEquals(data.size(), new HashSet<>(union).size(),
                "全序 ⇒ 两页并集须覆盖全部行且无重复：" + union);
    }

    @Test
    @DisplayName("T-096：orderByEnsuringUnique(唯一列) 把单键补成全序 ⇒ 两页并集无重复 / 漏行")
    void singleKeyCompletedToTotalOrder() {
        List<Row> data = rows();
        // 单键查询（默认路径的形状）：orderBy() 只有主排序键，本身不构成全序
        PageQuery p1 = PageQuery.of("1", "10", "exec_deadline,asc", "exec_deadline");
        PageQuery p2 = PageQuery.of("2", "10", "exec_deadline,asc", "exec_deadline");

        String clause1 = p1.orderByEnsuringUnique("id");
        String clause2 = p2.orderByEnsuringUnique("id");
        assertEquals("exec_deadline ASC, id ASC", clause1, "装配层须补主键 tie-break：" + clause1);
        assertEquals(clause1, clause2, "两页排序规范须一致");

        // 两次分页的「等值组内序」取相反方向（模拟真实 DB 对等值行次序不作保证）
        List<Long> union = new ArrayList<>();
        union.addAll(ids(page(clause1, p1, data, Row.BY_ID_ASC)));
        union.addAll(ids(page(clause2, p2, data, Row.BY_ID_DESC)));

        assertEquals(data.size(), union.size(), "全序 ⇒ 两页并集恰为 20 行（无重复）：" + union);
        assertEquals(data.size(), new HashSet<>(union).size(),
                "全序 ⇒ 两页并集覆盖全部行且无重复：" + union);
    }

    @Test
    @DisplayName("T-096：orderByEnsuringUnique —— 已含唯一列不重复追加；null / 空 唯一列 等价 orderBy()")
    void orderByEnsuringUniqueSemantics() {
        PageQuery multi = PageQuery.of(null, null, "exec_deadline,asc,id,asc", "exec_deadline");
        assertEquals("exec_deadline ASC, id ASC", multi.orderByEnsuringUnique("id"),
                "排序键已含 id ⇒ 不得重复追加");
        assertEquals("id ASC", PageQuery.of(null, null, null, "id").orderByEnsuringUnique("id"),
                "默认字段即 id ⇒ 不得重复追加");
        assertEquals("id DESC", PageQuery.of(null, null, "id,desc", "id").orderByEnsuringUnique("id"),
                "唯一列即使非末键亦视为已含 ⇒ 不追加");

        PageQuery single = PageQuery.of(null, null, "create_time,desc", "id");
        assertEquals("create_time DESC, id ASC", single.orderByEnsuringUnique("id"),
                "单键不含 id ⇒ 追加 id ASC");
        assertEquals(single.orderBy(), single.orderByEnsuringUnique(null),
                "唯一列为 null ⇒ 等价 orderBy()");
        assertEquals(single.orderBy(), single.orderByEnsuringUnique(""),
                "唯一列为空 ⇒ 等价 orderBy()");
    }

    @Test
    @DisplayName("T-096：sortFields() 返回全部排序键（多键逐键校验用）")
    void exposesAllSortFields() {
        assertEquals(java.util.Arrays.asList("exec_deadline", "id"),
                PageQuery.of(null, null, "exec_deadline,asc,id,asc", "exec_deadline").sortFields(),
                "多键须按优先级全部给出");
        assertEquals(java.util.Collections.singletonList("id"),
                PageQuery.of(null, null, null, "id").sortFields(),
                "未传 sort ⇒ 仅默认字段");
    }

    // ------------------------------------------------------------ 稳定排序假引擎（T-096）

    /** 一行：主排序值（exec_deadline，取值有重复）+ 唯一主键 id。 */
    private static final class Row {

        static final Comparator<Row> BY_ID_ASC = (a, b) -> Long.compare(a.id, b.id);
        static final Comparator<Row> BY_ID_DESC = (a, b) -> Long.compare(b.id, a.id);

        private final int key;
        private final long id;

        Row(int key, long id) {
            this.key = key;
            this.id = id;
        }
    }

    /** 数据集：exec_deadline=2 有 4 行（id 1..4），exec_deadline=3 有 16 行（id 5..20）；共 20 行。 */
    private static List<Row> rows() {
        List<Row> data = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            data.add(new Row(2, id));
        }
        for (long id = 5; id <= 20; id++) {
            data.add(new Row(3, id));
        }
        return data;
    }

    /**
     * 假分页引擎：按 {@link PageQuery#orderBy()} 逐键求值，再套 LIMIT/OFFSET。
     *
     * <p>未被 {@code orderBy} 完全区分的等值行，其相对次序交由 {@code tieOrder}（模拟真实 DB
     * 对「{@code ORDER BY} 未覆盖的键」不保证次序）；构成全序时 {@code tieOrder} 不被使用。
     */
    private static List<Row> page(PageQuery q, List<Row> data, Comparator<Row> tieOrder) {
        return page(q.orderBy(), q, data, tieOrder);
    }

    /** 按<b>显式</b> {@code ORDER BY} 子句分页（供 {@code orderByEnsuringUnique} 用例）。 */
    private static List<Row> page(String orderBy, PageQuery q, List<Row> data,
                                  Comparator<Row> tieOrder) {
        List<Comparator<Row>> keys = parseKeys(orderBy);
        List<Row> ordered = new ArrayList<>(data);
        ordered.sort((a, b) -> {
            for (Comparator<Row> k : keys) {
                int c = k.compare(a, b);
                if (c != 0) {
                    return c;
                }
            }
            return tieOrder.compare(a, b);
        });
        int from = q.offset();
        if (from >= ordered.size()) {
            return new ArrayList<>();
        }
        int to = Math.min(from + q.size(), ordered.size());
        return new ArrayList<>(ordered.subList(from, to));
    }

    /** 解析 {@code "f1 ASC, f2 DESC"} 为逐键比较器（支持有序字段 exec_deadline 与唯一键 id）。 */
    private static List<Comparator<Row>> parseKeys(String orderBy) {
        List<Comparator<Row>> keys = new ArrayList<>();
        for (String part : orderBy.split(",")) {
            String[] tok = part.trim().split("\\s+");
            boolean asc = "ASC".equalsIgnoreCase(tok[1]);
            Comparator<Row> base = "id".equals(tok[0])
                    ? (a, b) -> Long.compare(a.id, b.id)
                    : (a, b) -> Integer.compare(a.key, b.key);
            keys.add(asc ? base : base.reversed());
        }
        return keys;
    }

    private static List<Long> ids(List<Row> rows) {
        List<Long> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(r.id);
        }
        return out;
    }
}
