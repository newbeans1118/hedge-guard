package com.hedgeguard.dao;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 子账户数据权限过滤（{@code ARCHITECTURE.md} §8.2 {@code NFR-SEC-7} 「DAO 层统一注入」）。
 *
 * <p>存在意义：把 {@code sub_account_id IN (...)} 过滤器<b>集中在 DAO 层构造一次</b>，
 * 避免各模块各自拼条件而漏加（漏加 = 越权可见）。
 *
 * <p><b>安全默认（关键）</b>：无任何授权行时生成<b>恒假条件</b>（返回空集），
 * <b>绝不</b>退化为"不过滤"——"跳过过滤 = 全可见"正是 {@code NFR-SEC-7} 严禁的默认全可见。
 */
public final class SubAccountScope {

    /** 账户级/全账户口径的哨兵值（{@code DATA_MODEL.md} §0.2 / §4.3 {@code DC-020}）。 */
    public static final int ALL_ACCOUNT_SENTINEL = 0;

    /** 恒假条件的 SQL 片段（保证类型可比较：{@code sub_account_id IN (-1)}）。 */
    private static final String NEVER_TRUE = "%s IN (-1)";

    private final String column;
    private final List<Integer> allowed;

    private SubAccountScope(String column, List<Integer> allowed) {
        this.column = column;
        this.allowed = Collections.unmodifiableList(new ArrayList<>(allowed));
    }

    /**
     * 构造（列名限定为合法标识符，防注入）。
     *
     * @param column  子账户列名（如 {@code sub_account_id}）
     * @param allowed 可见子账户 ID 集合（含哨兵值 0 表示账户级行可见）
     * @return 过滤器
     */
    public static SubAccountScope of(String column, List<Integer> allowed) {
        if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw new IllegalArgumentException("子账户列名非法：" + column
                    + "；复现=传入形如 sub_account_id 的标识符");
        }
        return new SubAccountScope(column, allowed == null ? Collections.emptyList() : allowed);
    }

    /** 是否"无任何可见子账户"（安全默认命中）。 */
    public boolean isEmpty() {
        return allowed.isEmpty();
    }

    /** 可见子账户集合（只读）。 */
    public List<Integer> allowed() {
        return allowed;
    }

    /**
     * 生成 SQL 条件片段（形如 {@code sub_account_id IN (?,?)}）。
     *
     * @return SQL 片段；空集合时为恒假条件
     */
    public String sql() {
        if (allowed.isEmpty()) {
            return String.format(NEVER_TRUE, column);
        }
        StringBuilder sb = new StringBuilder(column).append(" IN (");
        for (int i = 0; i < allowed.size(); i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.append(')').toString();
    }

    /** 与 {@link #sql()} 对应的绑定参数（空集合时无参数）。 */
    public List<Integer> params() {
        return allowed;
    }

    /**
     * 追加到既有 WHERE 子句。
     *
     * @param whereClause 既有条件（{@code null} 或空表示无条件）
     * @return 合并后的条件
     */
    public String appendTo(String whereClause) {
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return sql();
        }
        return "(" + whereClause + ") AND " + sql();
    }
}
