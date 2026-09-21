package com.hedgeguard.dao.dialect;

import com.hedgeguard.dao.PageQuery;

import java.util.List;

/**
 * 达梦 DM8 方言（{@code ARCHITECTURE.md} §3.9「DM 方言唯一集中点」）。
 *
 * <p>本类是<b>唯一</b>允许出现 DM 特有语法的 Java 文件；每条 DM 特性均带
 * {@code // DM-SPECIFIC: <特性名>} 注释（{@code NFR-COMPAT-5}，附录 B 红线 2 的配套要求）。
 *
 * <p>换库时须改动的条目见 {@code DATA_MODEL.md} §6.1（{@code D3}/{@code D4}/{@code D1} 等）。
 */
public final class DmDialect implements Dialect {

    @Override
    public String paginate(String sql, PageQuery query) {
        // DM-SPECIFIC: 分页语法 LIMIT ? OFFSET ?（DM 兼容 MySQL 风格；DATA_MODEL.md §6.1 D3 非标准）
        // 参数顺序：LIMIT（= size）在前、OFFSET 在后，调用方须按此顺序绑定。
        return sql + " LIMIT ? OFFSET ?";
    }

    @Override
    public String countSql(String sql) {
        return "SELECT COUNT(*) AS cnt FROM (" + sql + ") hg_cnt";
    }

    @Override
    public String currentTimestamp() {
        // DM-SPECIFIC: CURRENT_TIMESTAMP（SQL:2003 半标准；DATA_MODEL.md §6.1 D2）
        return "CURRENT_TIMESTAMP";
    }

    @Override
    public String sysDate() {
        // DM-SPECIFIC: SYSDATE（Oracle 风格日期函数；DATA_MODEL.md §6.1 D4 非标准）
        return "SYSDATE";
    }

    @Override
    public String toDate(String placeholder, String pattern) {
        // DM-SPECIFIC: TO_DATE(expr, fmt)（Oracle 风格；DATA_MODEL.md §6.1 D4 非标准）
        return "TO_DATE(" + placeholder + ", '" + pattern + "')";
    }

    @Override
    public String truncDay(String expr) {
        // DM-SPECIFIC: TRUNC(expr)（Oracle 风格；DATA_MODEL.md §6.1 D4 非标准）
        return "TRUNC(" + expr + ")";
    }

    @Override
    public String addMonths(String expr, int months) {
        // DM-SPECIFIC: ADD_MONTHS(expr, n)（Oracle 风格；DATA_MODEL.md §6.1 D4 非标准）
        return "ADD_MONTHS(" + expr + ", " + months + ")";
    }

    @Override
    public String monthsBetween(String fromExpr, String toExpr) {
        // DM-SPECIFIC: MONTHS_BETWEEN(a, b)（Oracle 风格；DATA_MODEL.md §6.1 D4 非标准）
        return "MONTHS_BETWEEN(" + toExpr + ", " + fromExpr + ")";
    }

    @Override
    public String concat(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("拼接片段不得为空；复现=检查调用点");
        }
        // `||` 为 SQL:2008 标准拼接运算（DATA_MODEL.md §6.1 D5 标准项，无需换库改写）
        return String.join(" || ", parts);
    }

    @Override
    public String identityInsertOn(String table) {
        // DM-SPECIFIC: SET IDENTITY_INSERT（DM 特有，允许向 IDENTITY 列显式插入；DATA_MODEL.md §6.1 D1）
        return "SET IDENTITY_INSERT " + table + " ON";
    }

    @Override
    public String identityInsertOff(String table) {
        // DM-SPECIFIC: SET IDENTITY_INSERT（DM 特有；DATA_MODEL.md §6.1 D1）
        return "SET IDENTITY_INSERT " + table + " OFF";
    }

    @Override
    public String name() {
        return "DM8";
    }
}
