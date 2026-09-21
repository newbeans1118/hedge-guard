package com.hedgeguard.dao;

import java.util.Arrays;
import java.util.List;

/**
 * 分页查询参数（{@code ARCHITECTURE.md} §4.1.1「分页」）。
 *
 * <p>{@code size ∈ {10,20,50,100}}，默认 20；{@code sort} 形式 {@code field,asc}。
 * 越界取值<b>明确报错</b>，不静默截断（{@code PROJECT.md} §6「数据不足时明确报错，不静默降级」的同源纪律）。
 */
public final class PageQuery {

    /** 允许的每页条数（冻结，{@code §4.1.1}）。 */
    public static final List<Integer> ALLOWED_SIZES = java.util.Collections.unmodifiableList(
            Arrays.asList(10, 20, 50, 100));

    /** 默认每页条数。 */
    public static final int DEFAULT_SIZE = 20;

    /** 默认页码。 */
    public static final int DEFAULT_PAGE = 1;

    private final int page;
    private final int size;
    private final String sortField;
    private final boolean ascending;

    private PageQuery(int page, int size, String sortField, boolean ascending) {
        this.page = page;
        this.size = size;
        this.sortField = sortField;
        this.ascending = ascending;
    }

    /**
     * 解析查询参数。
     *
     * @param page      页码（可为 {@code null}，默认 1）
     * @param size      每页条数（可为 {@code null}，默认 20）
     * @param sort      {@code field,asc|desc}（可为 {@code null}）
     * @param defaultSortField 未指定排序时的默认字段（须由调用方给出白名单字段）
     * @return 分页参数
     */
    public static PageQuery of(String page, String size, String sort, String defaultSortField) {
        int p = DEFAULT_PAGE;
        if (page != null && !page.isEmpty()) {
            p = parseInt(page, "page");
            if (p < 1) {
                throw new IllegalArgumentException("page 须 >= 1：值=" + page + "；复现=修正查询参数");
            }
        }
        int s = DEFAULT_SIZE;
        if (size != null && !size.isEmpty()) {
            s = parseInt(size, "size");
            if (!ALLOWED_SIZES.contains(s)) {
                throw new IllegalArgumentException("size 取值须为 " + ALLOWED_SIZES + "：值=" + size
                        + "；复现=改用允许的每页条数");
            }
        }
        String field = defaultSortField;
        boolean asc = true;
        if (sort != null && !sort.isEmpty()) {
            String[] parts = sort.split(",");
            field = parts[0].trim();
            if (field.isEmpty() || !field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("sort 字段名非法：值=" + sort
                        + "；复现=使用形如 create_time,desc 的形式（字段名限字母数字下划线）");
            }
            if (parts.length > 1) {
                String dir = parts[1].trim().toLowerCase(java.util.Locale.ROOT);
                if (!"asc".equals(dir) && !"desc".equals(dir)) {
                    throw new IllegalArgumentException("sort 方向须为 asc 或 desc：值=" + sort
                            + "；复现=形如 create_time,desc");
                }
                asc = "asc".equals(dir);
            }
        }
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("排序字段未指定且未给出默认字段；复现=调用方传入 defaultSortField");
        }
        return new PageQuery(p, s, field, asc);
    }

    private static int parseInt(String raw, String name) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("分页参数 " + name + " 非整数：值=" + raw
                    + "；复现=修正查询参数", e);
        }
    }

    /** 页码（从 1 起）。 */
    public int page() {
        return page;
    }

    /** 每页条数。 */
    public int size() {
        return size;
    }

    /** 排序字段（已校验为合法标识符）。 */
    public String sortField() {
        return sortField;
    }

    /** 是否升序。 */
    public boolean ascending() {
        return ascending;
    }

    /** SQL 偏移量。 */
    public int offset() {
        return (page - 1) * size;
    }

    /** 排序方向关键字（{@code ASC} / {@code DESC}）。 */
    public String direction() {
        return ascending ? "ASC" : "DESC";
    }
}
