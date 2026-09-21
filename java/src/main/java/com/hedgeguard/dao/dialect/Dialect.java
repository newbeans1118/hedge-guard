package com.hedgeguard.dao.dialect;

import com.hedgeguard.dao.PageQuery;

import java.util.List;

/**
 * SQL 方言接口（{@code ARCHITECTURE.md} §3.9「{@code DmDialect.java} 方言集中点」）。
 *
 * <p>存在意义（{@code DATA_MODEL.md} §6.2）：把 DM 特有语法（分页 / 日期函数 / 序列 /
 * 显式 IDENTITY 插入）<b>全部集中在一处</b>，换库时只改实现类，业务代码与 DAO 零改动。
 *
 * <p>纪律：接口方法一律<b>返回 SQL 片段</b>，不拼接用户取值（取值一律用占位符 {@code ?}）。
 */
public interface Dialect {

    /**
     * 追加分页子句。
     *
     * @param sql   已含 {@code ORDER BY} 的查询语句
     * @param query 分页参数
     * @return 分页后的语句（末尾追加分页占位符；参数顺序为 {@code limit}、{@code offset}）
     */
    String paginate(String sql, PageQuery query);

    /**
     * 包裹为计数语句（{@code SELECT COUNT(*) FROM (<sql>) ...}）。
     *
     * @param sql 原查询语句（不含排序与分页）
     * @return 计数语句
     */
    String countSql(String sql);

    /** 当前时间函数（用于 {@code DEFAULT} 之外的显式取时；业务时间仍以应用层写入为准）。 */
    String currentTimestamp();

    /** 当前日期函数。 */
    String sysDate();

    /**
     * 字符串转日期。
     *
     * @param placeholder 占位符表达式（如 {@code ?}）
     * @param pattern     日期格式（如 {@code yyyy-MM-dd}）
     * @return 函数调用表达式
     */
    String toDate(String placeholder, String pattern);

    /**
     * 截断到日。
     *
     * @param expr 日期表达式
     * @return 函数调用表达式
     */
    String truncDay(String expr);

    /**
     * 日期加月。
     *
     * @param expr   日期表达式
     * @param months 月数
     * @return 函数调用表达式
     */
    String addMonths(String expr, int months);

    /**
     * 两个日期相差的月数。
     *
     * @param fromExpr 起始日期表达式
     * @param toExpr   结束日期表达式
     * @return 函数调用表达式
     */
    String monthsBetween(String fromExpr, String toExpr);

    /**
     * 字符串拼接。
     *
     * @param parts 片段（须为受控字面量或占位符）
     * @return 拼接表达式
     */
    String concat(List<String> parts);

    /**
     * 开启标识符自增列的显式插入（插入保留系统行如 {@code t_sub_account(id = 0)} 时需要）。
     *
     * @param table 表名
     * @return 语句
     */
    String identityInsertOn(String table);

    /**
     * 关闭标识符自增列的显式插入。
     *
     * @param table 表名
     * @return 语句
     */
    String identityInsertOff(String table);

    /** 方言名（用于日志与自检输出）。 */
    String name();
}
