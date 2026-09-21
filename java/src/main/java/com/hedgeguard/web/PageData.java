package com.hedgeguard.web;

import java.util.List;

/**
 * 分页数据体（{@code ARCHITECTURE.md} §4.1.1「分页」）。
 *
 * <p>响应形状：{@code data.items} + {@code data.total} + {@code data.page} + {@code data.size}。
 *
 * @param <T> 行类型
 */
public final class PageData<T> {

    /** 当前页数据行。 */
    public List<T> items;

    /** 满足条件的总行数。 */
    public long total;

    /** 当前页码（从 1 起）。 */
    public int page;

    /** 每页条数（{@code size ∈ {10,20,50,100}}，默认 20）。 */
    public int size;

    /** 无参构造（供 Gson）。 */
    public PageData() {
    }

    /**
     * 构造分页数据体。
     *
     * @param items 当前页行
     * @param total 总行数
     * @param page  页码
     * @param size  每页条数
     */
    public PageData(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}
