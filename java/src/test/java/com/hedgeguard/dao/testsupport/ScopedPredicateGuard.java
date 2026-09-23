package com.hedgeguard.dao.testsupport;

import java.util.regex.Pattern;

/**
 * 子账户 scoped SQL 的「<b>谓词仍生效</b>」静态守卫（{@code T-152}；{@code NFR-SEC-7} 隔离须真生效 /
 * {@code NFR-MAINT-1} 断言须有守卫力）。
 *
 * <p><b>为何存在</b>：既有 scoped SQL 用例用 {@code contains("sub_account_id IN (?,?)")} 之类<b>形状断言</b>，
 * 只证「谓词写了 + 值绑了」，<b>不证「谓词生效」</b>。可在 scope 子句尾部追加 {@code OR 1=1} 把谓词<b>中和</b>
 * 为恒真（谓词文本仍在、绑定参数不变）⇒ 形状断言全绿而隔离已被旁路（复核席 {@code MUT-5} / {@code MUT-C2}
 * 已独立复现：对 {@code T-106} 的 612 用例、对 {@code findPageScoped} 的形状断言组均全绿）。
 *
 * <p><b>判据（与形状无关的机械判据）</b>：
 * <ol>
 *   <li>{@link #containsNeutralizingOr(String)}：剔注释 / 字符串字面量后，SQL 中<b>不存在</b> {@code OR} 记号
 *       （{@code \bOR\b}，大小写不敏感、词边界）。</li>
 *   <li>{@link #placeholderCount(String)}：{@code ?} 占位符数（同样剔注释 / 字面量）。</li>
 * </ol>
 *
 * <p><b>★为何是「任意括号深度」而非「仅顶层」</b>：白名单载体（{@code t_product}）的 scope 谓词落在
 * <b>子查询内</b>（{@code id IN (SELECT product_id FROM t_sub_product_whitelist WHERE <scope>)}）；
 * 对 {@code SubAccountScope.sql()} 追加 {@code OR 1=1} 的「谓词中和」注入会落在<b>子查询的 WHERE</b>里
 * （括号深度 &gt; 0），<b>仅</b>断言「顶层无 {@code OR}」会<b>漏检</b>该注入。故本守卫取「任意深度」（顶层
 * 的超集）——这是对「顶层不含 {@code OR}」的有依据收严，理由即此白名单子查询载体。
 *
 * <p><b>可复用的反中和自证</b>：{@link #containsNeutralizingOr 非空转自测}见
 * {@code ScopedSqlPredicateEffectTest}（合成一条被中和的 SQL ⇒ 判据必报；合成良性 SQL ⇒ 不报）。
 */
public final class ScopedPredicateGuard {

    /** {@code OR} 记号（词边界、大小写不敏感；不匹配 {@code ORDER} / {@code priority} / {@code foo_or_bar}）。 */
    private static final Pattern OR_TOKEN = Pattern.compile("\\bOR\\b", Pattern.CASE_INSENSITIVE);

    private ScopedPredicateGuard() {
    }

    /**
     * 剔除单引号字符串字面量（含 {@code ''} 转义）与 SQL 注释（{@code --} 行注释、{@code /* *}{@code /} 块注释）。
     *
     * <p>目的：避免字面量/注释里的内容（如 {@code 'A OR B'}）造成守卫误报，也避免它们<b>掩盖</b>真实记号。
     * 字面量替换为单个空格，防止相邻记号粘连。
     *
     * @param sql 原始 SQL
     * @return 预处理后的 SQL
     */
    public static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append(' ');
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * SQL（剔注释 / 字面量后）是否含 {@code OR} 记号。
     *
     * @param sql 原始 SQL
     * @return 含 {@code OR} 记号则 {@code true}
     */
    public static boolean containsNeutralizingOr(String sql) {
        return OR_TOKEN.matcher(stripLiteralsAndComments(sql)).find();
    }

    /**
     * 统计 SQL 中 {@code ?} 占位符个数（剔注释 / 字面量后）。
     *
     * @param sql 原始 SQL
     * @return 占位符个数
     */
    public static int placeholderCount(String sql) {
        String s = stripLiteralsAndComments(sql);
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }
}
