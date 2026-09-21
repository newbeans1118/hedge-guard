package com.hedgeguard.dao;

import java.util.Collections;
import java.util.List;

/**
 * 分页结果（DAO 层返回类型）。
 *
 * <p>刻意<b>不</b>复用 {@code web.PageData}：依赖方向为 {@code web → service → dao}，
 * DAO 不得反向依赖接口层（{@code ARCHITECTURE.md} §2.3）。
 *
 * @param <T> 实体类型
 */
public final class PageResult<T> {

    private final List<T> items;
    private final long total;
    private final int page;
    private final int size;

    /**
     * 构造。
     *
     * @param items 当前页行
     * @param total 总行数
     * @param page  页码
     * @param size  每页条数
     */
    public PageResult(List<T> items, long total, int page, int size) {
        this.items = Collections.unmodifiableList(new java.util.ArrayList<>(items));
        this.total = total;
        this.page = page;
        this.size = size;
    }

    /** 当前页行（只读）。 */
    public List<T> items() {
        return items;
    }

    /** 总行数。 */
    public long total() {
        return total;
    }

    /** 页码。 */
    public int page() {
        return page;
    }

    /** 每页条数。 */
    public int size() {
        return size;
    }
}
