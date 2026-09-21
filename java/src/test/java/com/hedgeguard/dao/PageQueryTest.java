package com.hedgeguard.dao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
