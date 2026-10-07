package com.hedgeguard.dao.testsupport;

import com.hedgeguard.dao.pool.ConnectionFactory;

import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * **测试用「按 SQL 语义求值」的内存假 JDBC 引擎**（仅 {@code src/test}；零依赖）。
 *
 * <p><b>为何存在</b>：本环境无可用的 DM8 实例与驱动（{@code DmJdbcDriver*.jar} 全盘 0 命中、
 * {@code java/lib} 不存在、5236 端口无监听）。{@code T-023} 验收标准 2 要求「集成测试跑通
 * 建表→读写→查询」，本机不可达 ⇒ 依编排者裁定 (a) 改以**等价执行**：把三个 M1 建表脚本
 * 的 {@code CREATE TABLE} 真喂给本引擎建表，再由**真实 DAO**（如 {@link com.hedgeguard.dao.impl.DmProductDao}）
 * 生成 {@code INSERT}/{@code SELECT} 并真正执行、按 SQL 语义求值返回行——**比"仅断言 SQL 字符串"
 * 更强**（可捕获"SQL 拼对但语义错"）。
 *
 * <p><b>求值覆盖面</b>（本引擎实现）：
 * <ul>
 *   <li>DDL：{@code CREATE TABLE}（列/类型/NOT NULL/IDENTITY/DEFAULT、表级 PRIMARY KEY / UNIQUE /
 *       FOREIGN KEY / CHECK）、{@code CREATE UNIQUE INDEX}（登记，条件唯一索引标 conditional）、
 *       {@code ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY}、{@code CREATE INDEX} /
 *       {@code COMMENT ON}（忽略）。</li>
 *   <li>DML：{@code INSERT INTO ... VALUES (...)}、{@code UPDATE ... SET ... WHERE ...}、
 *       {@code DELETE FROM ... WHERE ...}。</li>
 *   <li>约束强制：<b>NOT NULL</b>、<b>主键 / 唯一</b>（表级 + 唯一索引）、<b>外键</b>、
 *       <b>单列 {@code IN (...)} / {@code = '字面量'} 形态的 CHECK</b>；其余复杂 {@code CHECK}
 *       仅登记不求值（见 {@link #uncheckedCheckCount()}，测试显式披露）。</li>
 *   <li>查询：{@code SELECT <cols|*> FROM <t> [WHERE <col = ? | col IN (?,..) | col IS NULL |
 *       col LIKE ?>] [ORDER BY ...] [LIMIT ? OFFSET ?]}，以及
 *       {@code SELECT COUNT(*) AS cnt FROM (<inner>) hg_cnt}（{@code DmDialect.countSql} 形态）。</li>
 *   <li>{@code LIKE ?}：绑定值为模式串，支持 {@code %}（任意长度）与 {@code _}（单字符）通配；
 *       仅支持**整原子**形态 {@code <col> LIKE ?}（不解析 {@code OR}/{@code NOT} 组合）——
 *       {@code (a LIKE ? OR b LIKE ?)} 等复合形态仍抛"无法求值的 WHERE 原子"（见不覆盖项）。</li>
 * </ul>
 *
 * <p><b>明确不覆盖</b>（诚实披露）：条件唯一索引的 {@code WHERE} 谓词、JOIN、聚合（除 count 包裹）、
 * 子查询、多列外键、DM 专有函数、事务隔离/回滚（连接为自动提交；回滚不还原本引擎的行集）。
 *
 * <p><b>上述不覆盖项的失败形态并不相同，须分开表述</b>（{@code T-023} 补测复核探针实测；<b>子查询一项经
 * {@code T-152} 探针实测<u>更正</u></b>——原文「两种形态都 loud 抛」<b>不成立</b>，勿再沿用）：
 * <ul>
 *   <li><b>JOIN / 聚合 → 静默返回错值</b>：SELECT 解析只取首个表名
 *       （{@code ^SELECT … FROM (\w+) …}），{@code JOIN}/聚合后缀落入未解析片段而被忽略，<b>不抛异常</b>，
 *       只是返回错误行集。实测（2 行基表）：{@code SELECT a.id FROM a JOIN b ON …} 与
 *       {@code SELECT MAX(id) FROM a} 均返回 <b>2 行</b>（正确应为 <b>1 行</b>）。
 *       此类最易"假绿"——用例若只断言"不报错"或不断言行数即会放过；</li>
 *   <li><b>子查询 → 不解析；<u>多数形态静默错值</u>，仅少数形态 loud 抛</b>（{@code T-152} 探针实测）：
 *       <ul>
 *         <li><b>裸列名 {@code WHERE id IN (SELECT …)} → 静默错值</b>（<b>不抛异常</b>）：子查询被误当作
 *             {@code IN} 的取值列表，按子查询内逗号切分后<b>消费绑定参数</b>，即把<b>外层列</b>
 *             （如 {@code t_product.id}）与绑定的<b>子查询列值</b>（如 {@code sub_account_id}）相比。
 *             实测：{@code DmProductDao.findByIdScoped(7, scope=[7])} 对白名单仅含子账户 1 的品种 7
 *             <b>返回了该行</b>（假阳·越权可见）；scope=[1] 时<b>返回 null</b>（假阴）。
 *             <b>危险向 = 可掩盖越权可见。</b></li>
 *         <li><b>裸列名且子查询逗号数 &gt; 绑定参数数 → loud 抛</b> {@code IndexOutOfBoundsException}
 *             （占位符取值越界）—— 这是原文误记为「裸列名一律抛」的那一半；</li>
 *         <li><b>带点号限定 {@code WHERE a.id IN (SELECT …)} → loud 抛</b>
 *             {@code UnsupportedOperationException("无法求值的 WHERE 原子…")}。</li>
 *       </ul>
 *       故子查询面<b>不得依赖「引擎 loud 抛」自证</b>；须改用记录型假 JDBC 断言 SQL 形状
 *       （见 {@code DmProductDaoScopedSqlTest} 与 {@code ScopedSqlPredicateEffectTest}）。</li>
 * </ul>
 * 以上均不影响本用例所断言的建表→读写→查询与三类约束路径。
 */
public final class InMemoryDmEngine implements ConnectionFactory {

    // ------------------------------------------------------------------ 模型

    /** 一列。 */
    public static final class Column {
        /** 列名。 */
        public final String name;
        /** 基础类型（如 {@code BIGINT} / {@code VARCHAR(32)}）。 */
        public String type = "";
        /** 是否 NOT NULL。 */
        public boolean notNull;
        /** 是否 IDENTITY 自增。 */
        public boolean identity;
        /** DEFAULT 字面量（{@code 'Y'} / 数字）或 {@code CURRENT_TIMESTAMP}；无则 {@code null}。 */
        public String defaultLiteral;

        Column(String name) {
            this.name = name;
        }
    }

    /** 唯一约束（含主键）。 */
    public static final class Unique {
        /** 约束/索引名。 */
        public final String name;
        /** 列清单。 */
        public final List<String> columns;
        /** 是否为条件唯一索引（{@code WHERE} 谓词本次不求值 ⇒ 不参与强制）。 */
        public final boolean conditional;

        Unique(String name, List<String> columns, boolean conditional) {
            this.name = name;
            this.columns = columns;
            this.conditional = conditional;
        }
    }

    /** 外键。 */
    public static final class ForeignKey {
        /** 约束名。 */
        public final String name;
        /** 本表列。 */
        public final String column;
        /** 引用表。 */
        public final String refTable;
        /** 引用列。 */
        public final String refColumn;

        ForeignKey(String name, String column, String refTable, String refColumn) {
            this.name = name;
            this.column = column;
            this.refTable = refTable;
            this.refColumn = refColumn;
        }
    }

    /** CHECK 约束（可求值的单列 IN / = 字面量形态）。 */
    public static final class Check {
        /** 约束名。 */
        public final String name;
        /** 被约束列；不可求值时为 {@code null}。 */
        public final String column;
        /** {@code IN} 取值域；不适用时为 {@code null}。 */
        public final Set<String> inValues;
        /** {@code = '字面量'} 的期望值；不适用时为 {@code null}。 */
        public final String equalsValue;

        Check(String name, String column, Set<String> inValues, String equalsValue) {
            this.name = name;
            this.column = column;
            this.inValues = inValues;
            this.equalsValue = equalsValue;
        }

        /** 是否本次可求值。 */
        boolean enforceable() {
            return column != null;
        }
    }

    /** 一张表。 */
    public static final class Table {
        /** 表名。 */
        public final String name;
        /** 列（声明顺序）。 */
        public final LinkedHashMap<String, Column> columns = new LinkedHashMap<>();
        /** 主键列。 */
        public final List<String> pk = new ArrayList<>();
        /** 唯一约束。 */
        public final List<Unique> uniques = new ArrayList<>();
        /** 外键。 */
        public final List<ForeignKey> fks = new ArrayList<>();
        /** CHECK 约束。 */
        public final List<Check> checks = new ArrayList<>();
        /** 行集（列名 → 值；值为 {@code String}/{@code Long}/{@code Integer}/{@code BigDecimal}/
         *  {@code LocalDate}/{@code LocalDateTime}）。 */
        public final List<Map<String, Object>> rows = new ArrayList<>();
        /** 自增游标。 */
        long nextId = 1;

        Table(String name) {
            this.name = name;
        }

        /** 自增列名（无则 {@code null}）。 */
        public String identityColumn() {
            for (Column c : columns.values()) {
                if (c.identity) {
                    return c.name;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ 引擎状态

    private final Map<String, Table> tables = new LinkedHashMap<>();
    private long lastGeneratedId;
    private boolean lastWasNull;
    private long rejectedByNotNull;
    private long rejectedByUnique;
    private long rejectedByForeignKey;
    private long rejectedByCheck;

    /** 已登记的表（表名 → 表）。 */
    public Map<String, Table> tables() {
        return Collections.unmodifiableMap(tables);
    }

    /** 已建表数。 */
    public int tableCount() {
        return tables.size();
    }

    /** 取表（不存在返回 {@code null}）。 */
    public Table table(String name) {
        return tables.get(name);
    }

    /** 累计被拒写入数（分约束类），供报告引用。 */
    public long rejectedByNotNull() {
        return rejectedByNotNull;
    }

    /** 见 {@link #rejectedByNotNull()}。 */
    public long rejectedByUnique() {
        return rejectedByUnique;
    }

    /** 见 {@link #rejectedByNotNull()}。 */
    public long rejectedByForeignKey() {
        return rejectedByForeignKey;
    }

    /** 见 {@link #rejectedByNotNull()}。 */
    public long rejectedByCheck() {
        return rejectedByCheck;
    }

    /** 登记但因形态复杂而**未求值**的 CHECK 数（诚实披露）。 */
    public long uncheckedCheckCount() {
        long n = 0;
        for (Table t : tables.values()) {
            for (Check c : t.checks) {
                if (!c.enforceable()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 已求值的 CHECK 数。 */
    public long enforcedCheckCount() {
        long n = 0;
        for (Table t : tables.values()) {
            for (Check c : t.checks) {
                if (c.enforceable()) {
                    n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 执行入口

    /** 在一条连接上按 {@code ;} 逐条执行脚本（DDL/DML 混排亦可）。 */
    public void executeScript(String scriptText) throws SQLException {
        try (Connection conn = create(); Statement st = conn.createStatement()) {
            for (String stmt : splitStatements(scriptText)) {
                st.execute(stmt);
            }
        }
    }

    /** 由 JDBC 路径执行一条语句（{@code Statement.execute} / {@code PreparedStatement} 共用）。 */
    private void executeStatement(String sql) throws SQLException {
        String s = normalize(sql);
        if (s.isEmpty()) {
            return;
        }
        String up = s.toUpperCase(Locale.ROOT);
        if (up.startsWith("CREATE TABLE")) {
            createTable(s);
        } else if (up.startsWith("CREATE UNIQUE INDEX")) {
            createUniqueIndex(s);
        } else if (up.startsWith("CREATE INDEX")) {
            // 普通索引无求值语义
        } else if (up.startsWith("ALTER TABLE")) {
            alterTableAddForeignKey(s);
        } else if (up.startsWith("COMMENT ON")) {
            // 注释无求值语义
        } else if (up.startsWith("INSERT")) {
            executeUpdate(s, Collections.emptyList());
        } else {
            throw new UnsupportedOperationException("内存引擎不支持的语句：" + firstLine(s));
        }
    }

    // ------------------------------------------------------------------ DDL

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?is)^CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)\\s*$");

    private void createTable(String sql) {
        Matcher m = CREATE_TABLE.matcher(sql);
        if (!m.matches()) {
            throw new UnsupportedOperationException("无法解析 CREATE TABLE：" + firstLine(sql));
        }
        Table table = new Table(m.group(1));
        for (String item : splitTopLevel(m.group(2))) {
            String def = collapse(item);
            if (def.isEmpty()) {
                continue;
            }
            String upper = def.toUpperCase(Locale.ROOT);
            if (upper.startsWith("CONSTRAINT ")) {
                parseTableConstraint(table, def);
            } else if (upper.startsWith("PRIMARY KEY")) {
                table.pk.addAll(parseColumnsInParens(def));
            } else if (upper.startsWith("UNIQUE")) {
                table.uniques.add(new Unique("(inline)", parseColumnsInParens(def), false));
            } else if (upper.startsWith("FOREIGN KEY")) {
                parseForeignKey(table, "(inline)", def);
            } else if (upper.startsWith("CHECK")) {
                table.checks.add(parseCheck("(inline)", def));
            } else {
                parseColumn(table, def);
            }
        }
        if (!table.pk.isEmpty()) {
            table.uniques.add(new Unique("pk_" + table.name, new ArrayList<>(table.pk), false));
        }
        tables.put(table.name, table);
    }

    private void parseTableConstraint(Table table, String def) {
        Matcher m = Pattern.compile("(?is)^CONSTRAINT\\s+(\\w+)\\s+(.*)$").matcher(def);
        if (!m.matches()) {
            throw new UnsupportedOperationException("无法解析约束：" + def);
        }
        String name = m.group(1);
        String body = m.group(2).trim();
        String upper = body.toUpperCase(Locale.ROOT);
        if (upper.startsWith("PRIMARY KEY")) {
            table.pk.addAll(parseColumnsInParens(body));
        } else if (upper.startsWith("UNIQUE")) {
            table.uniques.add(new Unique(name, parseColumnsInParens(body), false));
        } else if (upper.startsWith("FOREIGN KEY")) {
            parseForeignKey(table, name, body);
        } else if (upper.startsWith("CHECK")) {
            table.checks.add(parseCheck(name, body));
        }
    }

    private void parseForeignKey(Table table, String name, String body) {
        Matcher m = Pattern.compile(
                "(?is)FOREIGN\\s+KEY\\s*\\(\\s*(\\w+)\\s*\\)\\s*REFERENCES\\s+(\\w+)\\s*\\(\\s*(\\w+)\\s*\\)")
                .matcher(body);
        if (!m.find()) {
            throw new UnsupportedOperationException("无法解析外键：" + body);
        }
        table.fks.add(new ForeignKey(name, m.group(1), m.group(2), m.group(3)));
    }

    private static Check parseCheck(String name, String body) {
        String expr = body.replaceFirst("(?is)^CHECK\\s*\\(", "").trim();
        if (expr.endsWith(")")) {
            expr = expr.substring(0, expr.length() - 1).trim();
        }
        Matcher in = Pattern.compile("(?is)^\\(?\\s*(\\w+)\\s+IN\\s*\\((.*)\\)\\s*\\)?$").matcher(expr);
        if (in.matches()) {
            Set<String> values = new LinkedHashSet<>();
            for (String v : in.group(2).split(",")) {
                values.add(unquote(v.trim()));
            }
            return new Check(name, in.group(1), values, null);
        }
        Matcher eq = Pattern.compile("(?is)^\\(?\\s*(\\w+)\\s*=\\s*'([^']*)'\\s*\\)?$").matcher(expr);
        if (eq.matches()) {
            return new Check(name, eq.group(1), null, eq.group(2));
        }
        return new Check(name, null, null, null);   // 复杂形态：登记不求值
    }

    private void parseColumn(Table table, String def) {
        Matcher m = Pattern.compile("^(\\w+)\\s+(.*)$", Pattern.DOTALL).matcher(def);
        if (!m.matches()) {
            throw new UnsupportedOperationException("无法解析列定义：" + def);
        }
        Column column = new Column(m.group(1));
        String rest = m.group(2).trim();
        Matcher type = Pattern.compile("^([A-Za-z][A-Za-z0-9]*(?:\\s*\\([0-9,\\s]*\\))?)").matcher(rest);
        if (type.find()) {
            column.type = type.group(1).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        }
        column.notNull = rest.toUpperCase(Locale.ROOT).matches("(?s).*\\bNOT\\s+NULL\\b.*");
        column.identity = rest.toUpperCase(Locale.ROOT).contains("IDENTITY(");
        Matcher def2 = Pattern.compile("(?is)\\bDEFAULT\\s+('(?:[^']*)'|[A-Za-z_][\\w]*|-?[0-9]+(?:\\.[0-9]+)?)")
                .matcher(rest);
        if (def2.find()) {
            column.defaultLiteral = def2.group(1);
        }
        table.columns.put(column.name, column);
    }

    private void createUniqueIndex(String sql) throws SQLException {
        Matcher m = Pattern.compile(
                "(?is)^CREATE\\s+UNIQUE\\s+INDEX\\s+(\\w+)\\s+ON\\s+(\\w+)\\s*\\(([^)]*)\\)(.*)$").matcher(sql);
        if (!m.matches()) {
            throw new UnsupportedOperationException("无法解析 CREATE UNIQUE INDEX：" + firstLine(sql));
        }
        Table table = requireTable(m.group(2));
        boolean conditional = m.group(4).toUpperCase(Locale.ROOT).contains("WHERE");
        table.uniques.add(new Unique(m.group(1), splitColumnList(m.group(3)), conditional));
    }

    private void alterTableAddForeignKey(String sql) throws SQLException {
        Matcher m = Pattern.compile(
                "(?is)^ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+CONSTRAINT\\s+(\\w+)\\s+(FOREIGN\\s+KEY.*)$")
                .matcher(sql);
        if (!m.matches()) {
            return;   // 其它 ALTER（本 M1 脚本未用）忽略
        }
        parseForeignKey(requireTable(m.group(1)), m.group(2), m.group(3));
    }

    // ------------------------------------------------------------------ DML

    private static final Pattern INSERT =
            Pattern.compile("(?is)^INSERT\\s+INTO\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*VALUES\\s*\\(([^)]*)\\)\\s*$");
    private static final Pattern UPDATE =
            Pattern.compile("(?is)^UPDATE\\s+(\\w+)\\s+SET\\s+(.*?)\\s+WHERE\\s+(.*)$");
    private static final Pattern DELETE =
            Pattern.compile("(?is)^DELETE\\s+FROM\\s+(\\w+)\\s+WHERE\\s+(.*)$");

    private int executeUpdate(String sql, List<Object> args) throws SQLException {
        String s = normalize(sql);
        String up = s.toUpperCase(Locale.ROOT);
        if (up.startsWith("INSERT")) {
            return doInsert(s, args);
        }
        if (up.startsWith("UPDATE")) {
            return doUpdate(s, args);
        }
        if (up.startsWith("DELETE")) {
            return doDelete(s, args);
        }
        executeStatement(s);
        return 0;
    }

    private int doInsert(String sql, List<Object> args) throws SQLException {
        Matcher m = INSERT.matcher(sql);
        if (!m.matches()) {
            throw new UnsupportedOperationException("无法解析 INSERT：" + firstLine(sql));
        }
        Table table = requireTable(m.group(1));
        List<String> cols = splitColumnList(m.group(2));
        List<String> marks = splitTopLevel(m.group(3));
        if (cols.size() != marks.size()) {
            throw new SQLException("INSERT 列数与取值数不一致：" + cols.size() + "/" + marks.size()
                    + "；SQL=" + sql);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        int argIndex = 0;
        for (int i = 0; i < cols.size(); i++) {
            String mark = marks.get(i).trim();
            if ("?".equals(mark)) {
                if (argIndex >= args.size()) {
                    throw new SQLException("INSERT 占位符多于绑定参数：SQL=" + sql);
                }
                row.put(cols.get(i), args.get(argIndex++));
            } else {
                row.put(cols.get(i), parseLiteral(mark));   // 字面量 VALUES（供脚本/种子数据）
            }
        }
        applyDefaults(table, row);
        assignIdentity(table, row);
        validate(table, row);
        table.rows.add(row);
        return 1;
    }

    private int doUpdate(String sql, List<Object> args) throws SQLException {
        Matcher m = UPDATE.matcher(sql);
        if (!m.matches()) {
            throw new UnsupportedOperationException("无法解析 UPDATE：" + firstLine(sql));
        }
        Table table = requireTable(m.group(1));
        int[] argIndex = {0};
        List<String> setCols = new ArrayList<>();
        List<Object> setValues = new ArrayList<>();
        List<Boolean> bump = new ArrayList<>();
        for (String assign : splitTopLevel(m.group(2))) {
            Matcher a = Pattern.compile("(?is)^(\\w+)\\s*=\\s*(.*)$").matcher(collapse(assign));
            if (!a.matches()) {
                throw new UnsupportedOperationException("无法解析 SET 子句：" + assign);
            }
            setCols.add(a.group(1));
            String rhs = a.group(2).trim();
            if ("?".equals(rhs)) {
                setValues.add(args.get(argIndex[0]++));
                bump.add(false);
            } else {
                setValues.add(null);
                bump.add(true);   // 形如 row_version = row_version + 1
            }
        }
        Predicate where = parseWhere(collapse(m.group(3)), args, argIndex);
        int affected = 0;
        for (Map<String, Object> row : table.rows) {
            if (!where.test(row)) {
                continue;
            }
            for (int i = 0; i < setCols.size(); i++) {
                if (bump.get(i)) {
                    Object cur = row.get(setCols.get(i));
                    long v = cur instanceof Number ? ((Number) cur).longValue() : 0L;
                    row.put(setCols.get(i), (int) (v + 1));
                } else {
                    row.put(setCols.get(i), setValues.get(i));
                }
            }
            affected++;
        }
        return affected;
    }

    private int doDelete(String sql, List<Object> args) throws SQLException {
        Matcher m = DELETE.matcher(sql);
        if (!m.matches()) {
            throw new UnsupportedOperationException("无法解析 DELETE：" + firstLine(sql));
        }
        Table table = requireTable(m.group(1));
        int[] argIndex = {0};
        Predicate where = parseWhere(collapse(m.group(2)), args, argIndex);
        int before = table.rows.size();
        table.rows.removeIf(row -> !where.test(row));
        return before - table.rows.size();
    }

    // ------------------------------------------------------------------ 约束强制

    private void applyDefaults(Table table, Map<String, Object> row) {
        for (Column c : table.columns.values()) {
            if (!row.containsKey(c.name) && c.defaultLiteral != null) {
                row.put(c.name, parseLiteral(c.defaultLiteral));
            }
        }
    }

    private void assignIdentity(Table table, Map<String, Object> row) {
        String ident = table.identityColumn();
        if (ident == null) {
            return;
        }
        Object current = row.get(ident);
        long value;
        if (current == null) {
            value = table.nextId++;
        } else {
            value = current instanceof Number ? ((Number) current).longValue()
                    : Long.parseLong(String.valueOf(current));
            table.nextId = Math.max(table.nextId, value + 1);
        }
        row.put(ident, value);
        lastGeneratedId = value;
    }

    private void validate(Table table, Map<String, Object> row) throws SQLException {
        for (Column c : table.columns.values()) {
            if (c.notNull && row.get(c.name) == null) {
                rejectedByNotNull++;
                throw new SQLException("违反 NOT NULL 约束：" + table.name + "." + c.name
                        + "（内存引擎按 DDL 语义判定）");
            }
        }
        for (Unique u : table.uniques) {
            if (u.conditional) {
                continue;   // 条件唯一索引的 WHERE 谓词本次不求值（已披露）
            }
            if (containsNull(row, u.columns)) {
                continue;   // SQL 唯一约束对含 NULL 的行不强制
            }
            for (Map<String, Object> existing : table.rows) {
                if (sameKey(existing, row, u.columns)) {
                    rejectedByUnique++;
                    throw new SQLException("违反唯一约束 " + u.name + "：" + table.name + "("
                            + String.join(",", u.columns) + ") 已存在相同取值，拒绝重复插入");
                }
            }
        }
        for (ForeignKey fk : table.fks) {
            Object value = row.get(fk.column);
            if (value == null) {
                continue;
            }
            Table ref = tables.get(fk.refTable);
            if (ref == null) {
                continue;
            }
            boolean found = ref.rows.stream().anyMatch(r -> sameValue(r.get(fk.refColumn), value));
            if (!found) {
                rejectedByForeignKey++;
                throw new SQLException("违反外键 " + fk.name + "：" + table.name + "." + fk.column
                        + "=" + value + " 在 " + fk.refTable + "." + fk.refColumn
                        + " 无对应行（孤儿数据），拒绝写入");
            }
        }
        for (Check check : table.checks) {
            if (!check.enforceable()) {
                continue;
            }
            Object value = row.get(check.column);
            if (value == null) {
                continue;   // CHECK 对 NULL 求值为 UNKNOWN ⇒ 通过
            }
            String text = String.valueOf(value);
            boolean ok = check.inValues != null
                    ? check.inValues.contains(text)
                    : check.equalsValue != null && check.equalsValue.equals(text);
            if (!ok) {
                rejectedByCheck++;
                throw new SQLException("违反 CHECK " + check.name + "：" + table.name + "." + check.column
                        + "=" + value + " 超出取值域");
            }
        }
    }

    // ------------------------------------------------------------------ 查询

    private static final Pattern COUNT_WRAPPER =
            Pattern.compile("(?is)^SELECT\\s+COUNT\\(\\*\\)\\s+AS\\s+cnt\\s+FROM\\s*\\((.*)\\)\\s+hg_cnt\\s*$");
    private static final Pattern SELECT =
            Pattern.compile("(?is)^SELECT\\s+(.*?)\\s+FROM\\s+(\\w+)(.*)$");

    private ResultRows executeQuery(String sql, List<Object> args) throws SQLException {
        String s = normalize(sql);
        Matcher count = COUNT_WRAPPER.matcher(s);
        if (count.matches()) {
            List<Map<String, Object>> inner = selectRows(count.group(1), args);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cnt", (long) inner.size());
            return new ResultRows(Collections.singletonList("cnt"), Collections.singletonList(row));
        }
        List<Map<String, Object>> rows = selectRows(s, args);
        Matcher sel = SELECT.matcher(s);
        if (!sel.matches()) {
            throw new UnsupportedOperationException("无法解析 SELECT：" + firstLine(s));
        }
        List<String> projection = projectionFor(sel.group(1), rows);
        return new ResultRows(projection, project(rows, projection));
    }

    private List<Map<String, Object>> selectRows(String sql, List<Object> args) throws SQLException {
        Matcher sel = SELECT.matcher(normalize(sql));
        if (!sel.matches()) {
            throw new UnsupportedOperationException("无法解析 SELECT：" + firstLine(sql));
        }
        Table table = requireTable(sel.group(2));
        String rest = sel.group(3);
        boolean paginated = false;
        Matcher limit = Pattern.compile("(?is)\\s+LIMIT\\s+\\?\\s+OFFSET\\s+\\?\\s*$").matcher(rest);
        if (limit.find()) {
            paginated = true;
            rest = rest.substring(0, limit.start());
        }
        String orderBy = null;
        Matcher order = Pattern.compile("(?is)\\s+ORDER\\s+BY\\s+(.*)$").matcher(rest);
        if (order.find()) {
            orderBy = order.group(1).trim();
            rest = rest.substring(0, order.start());
        }
        String whereClause = null;
        Matcher where = Pattern.compile("(?is)\\s+WHERE\\s+(.*)$").matcher(rest);
        if (where.find()) {
            whereClause = where.group(1).trim();
        }
        int[] argIndex = {0};
        Predicate predicate = whereClause == null
                ? row -> true
                : parseWhere(whereClause, args, argIndex);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : table.rows) {
            if (predicate.test(row)) {
                out.add(row);
            }
        }
        if (orderBy != null) {
            applyOrderBy(out, orderBy);
        }
        if (paginated) {
            int size = intArg(args.get(args.size() - 2));
            int offset = intArg(args.get(args.size() - 1));
            List<Map<String, Object>> page = new ArrayList<>();
            for (int i = offset; i < out.size() && page.size() < size; i++) {
                page.add(out.get(i));
            }
            return page;
        }
        return out;
    }

    private static Predicate parseWhere(String clause, List<Object> args, int[] argIndex) {
        String text = stripOuterParens(collapse(clause));
        List<String> atoms = new ArrayList<>();
        int depth = 0;
        boolean quote = false;
        StringBuilder cur = new StringBuilder();
        String upper = text.toUpperCase(Locale.ROOT);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                quote = !quote;
            } else if (!quote && ch == '(') {
                depth++;
            } else if (!quote && ch == ')') {
                depth--;
            }
            if (!quote && depth == 0 && upper.startsWith(" AND ", i)) {
                atoms.add(cur.toString().trim());
                cur.setLength(0);
                i += 4;
                continue;
            }
            cur.append(ch);
        }
        if (cur.length() > 0) {
            atoms.add(cur.toString().trim());
        }
        List<Predicate> predicates = new ArrayList<>();
        for (String atomRaw : atoms) {
            String atom = stripOuterParens(collapse(atomRaw));
            Matcher in = Pattern.compile("(?is)^(\\w+)\\s+IN\\s*\\((.*)\\)$").matcher(atom);
            // 等值 / 比较符（'>=' '>' '<=' '<' '=' '<>' '!='）；T-065 增补 > 与 >=（since 补拉等区间谓词）
            Matcher cmp = Pattern.compile("(?is)^(\\w+)\\s*(>=|<=|<>|!=|>|<|=)\\s*\\?$").matcher(atom);
            Matcher isNull = Pattern.compile("(?is)^(\\w+)\\s+IS\\s+NULL$").matcher(atom);
            Matcher like = Pattern.compile("(?is)^(\\w+)\\s+LIKE\\s+\\?$").matcher(atom);
            if (cmp.matches()) {
                String col = cmp.group(1);
                String op = cmp.group(2);
                Object value = args.get(argIndex[0]++);
                predicates.add(row -> compareOp(row.get(col), value, op));
            } else if (in.matches()) {
                String col = in.group(1);
                List<Object> values = new ArrayList<>();
                for (String token : in.group(2).split(",")) {
                    String tokenText = token.trim();
                    // 占位符用实参；字面量（如恒假条件 IN (-1)）直接求值
                    values.add("?".equals(tokenText) ? args.get(argIndex[0]++) : sqlLiteral(tokenText));
                }
                predicates.add(row -> values.stream().anyMatch(v -> sameValue(row.get(col), v)));
            } else if (isNull.matches()) {
                String col = isNull.group(1);
                predicates.add(row -> row.get(col) == null);
            } else if (like.matches()) {
                String col = like.group(1);
                Object value = args.get(argIndex[0]++);
                String pattern = value == null ? null : String.valueOf(value);
                predicates.add(row -> matchesLike(row.get(col), pattern));
            } else {
                throw new UnsupportedOperationException("无法求值的 WHERE 原子：" + atom);
            }
        }
        return row -> {
            for (Predicate p : predicates) {
                if (!p.test(row)) {
                    return false;
                }
            }
            return true;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean compareOp(Object a, Object b, String op) {
        if (a == null || b == null) {
            return false;
        }
        int cmp;
        if (a instanceof Number && b instanceof Number) {
            cmp = new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()));
        } else if (a instanceof Comparable && a.getClass().equals(b.getClass())) {
            cmp = ((Comparable) a).compareTo(b);
        } else {
            cmp = String.valueOf(a).compareTo(String.valueOf(b));
        }
        switch (op) {
            case ">": return cmp > 0;
            case ">=": return cmp >= 0;
            case "<": return cmp < 0;
            case "<=": return cmp <= 0;
            case "=": return cmp == 0;
            case "<>":
            case "!=": return cmp != 0;
            default: throw new UnsupportedOperationException("未知比较符：" + op);
        }
    }

    /** 解析 IN(...) 中的字面量（整数 / 单引号字符串）。 */
    private static Object sqlLiteral(String token) {
        String t = token.trim();
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length() - 1);
        }
        if (t.matches("-?\\d+")) {
            return Long.valueOf(t);
        }
        if (t.matches("-?\\d+\\.\\d+")) {
            return new java.math.BigDecimal(t);
        }
        return t;
    }

    private static void applyOrderBy(List<Map<String, Object>> rows, String orderBy) {
        List<String[]> keys = new ArrayList<>();
        for (String key : orderBy.split(",")) {
            String[] parts = key.trim().split("\\s+");
            keys.add(new String[]{parts[0], parts.length > 1 ? parts[1].toUpperCase(Locale.ROOT) : "ASC"});
        }
        rows.sort((a, b) -> {
            for (String[] key : keys) {
                int cmp = compareValues(a.get(key[0]), b.get(key[0]));
                if (cmp != 0) {
                    return "DESC".equals(key[1]) ? -cmp : cmp;
                }
            }
            return 0;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Comparable && a.getClass().equals(b.getClass())) {
            return ((Comparable) a).compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static List<String> projectionFor(String selectList, List<Map<String, Object>> rows) {
        String trimmed = selectList.trim();
        if ("*".equals(trimmed)) {
            return rows.isEmpty() ? Collections.emptyList() : new ArrayList<>(rows.get(0).keySet());
        }
        return splitColumnList(trimmed);
    }

    private static List<Map<String, Object>> project(List<Map<String, Object>> rows, List<String> projection) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> p = new LinkedHashMap<>();
            for (String col : projection) {
                p.put(col, row.get(col));
            }
            out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ 结果集 / JDBC 代理

    /** 查询结果（列清单 + 行）。 */
    private static final class ResultRows {
        final List<String> columns;
        final List<Map<String, Object>> rows;

        ResultRows(List<String> columns, List<Map<String, Object>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    @Override
    public Connection create() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement":
                    String sql = (String) args[0];
                    return preparedStatement(sql);
                case "createStatement":
                    return statement();
                case "setAutoCommit":
                case "commit":
                case "rollback":
                    return null;
                case "getAutoCommit":
                    return true;
                case "isClosed":
                    return false;
                case "isValid":
                    return true;
                case "close":
                    return null;
                case "toString":
                    return "InMemoryDmConnection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    private Statement statement() {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "execute":
                    executeStatement((String) args[0]);
                    return false;
                case "executeUpdate":
                    return executeUpdate((String) args[0], Collections.emptyList());
                case "close":
                    return null;
                case "toString":
                    return "InMemoryDmStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Statement.class}, handler);
    }

    private PreparedStatement preparedStatement(String sql) {
        List<Object> bound = new ArrayList<>();
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "setString":
                    bind(bound, (Integer) args[0], args[1]);
                    return null;
                case "setLong":
                    bind(bound, (Integer) args[0], ((Number) args[1]).longValue());
                    return null;
                case "setInt":
                    bind(bound, (Integer) args[0], ((Number) args[1]).intValue());
                    return null;
                case "setBigDecimal":
                    bind(bound, (Integer) args[0], args[1]);
                    return null;
                case "setObject":
                    bind(bound, (Integer) args[0], args[1]);
                    return null;
                case "setNull":
                    bind(bound, (Integer) args[0], null);
                    return null;
                case "setDate":
                    bind(bound, (Integer) args[0], ((Date) args[1]).toLocalDate());
                    return null;
                case "setTimestamp":
                    bind(bound, (Integer) args[0], ((Timestamp) args[1]).toLocalDateTime());
                    return null;
                case "setCharacterStream":
                    bind(bound, (Integer) args[0], readReader((Reader) args[1]));
                    return null;
                case "clearParameters":
                    bound.clear();
                    return null;
                case "executeUpdate":
                    return executeUpdate(sql, new ArrayList<>(bound));
                case "executeQuery":
                    return resultSet(executeQuery(sql, new ArrayList<>(bound)));
                case "execute":
                    executeUpdate(sql, new ArrayList<>(bound));
                    return false;
                case "getGeneratedKeys":
                    return generatedKeys();
                case "close":
                    return null;
                case "toString":
                    return "InMemoryDmPreparedStatement";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, handler);
    }

    private ResultSet generatedKeys() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", lastGeneratedId);
        ResultRows rows = new ResultRows(Collections.singletonList("id"), Collections.singletonList(row));
        return resultSet(rows);
    }

    private ResultSet resultSet(ResultRows data) {
        int[] cursor = {-1};
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    cursor[0]++;
                    return cursor[0] < data.rows.size();
                case "getLong":
                    Object lv = read(data, cursor[0], args);
                    return lv == null ? 0L : ((Number) lv).longValue();
                case "getInt":
                    Object iv = read(data, cursor[0], args);
                    return iv == null ? 0 : ((Number) iv).intValue();
                case "getString":
                    Object v = read(data, cursor[0], args);
                    return v == null ? null : String.valueOf(v);
                case "getBigDecimal":
                    Object d = read(data, cursor[0], args);
                    return d == null ? null : new BigDecimal(String.valueOf(d));
                case "getDate":
                    Object dt = read(data, cursor[0], args);
                    return dt == null ? null : Date.valueOf((LocalDate) dt);
                case "getTimestamp":
                    Object ts = read(data, cursor[0], args);
                    return ts == null ? null : Timestamp.valueOf((LocalDateTime) ts);
                case "getCharacterStream":
                    Object cs = read(data, cursor[0], args);
                    return cs == null ? null : new java.io.StringReader(String.valueOf(cs));
                case "wasNull":
                    return lastWasNull;
                case "close":
                    return null;
                case "toString":
                    return "InMemoryDmResultSet";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        };
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class}, handler);
    }

    private Object read(ResultRows data, int rowIndex, Object[] args) throws SQLException {
        if (rowIndex < 0 || rowIndex >= data.rows.size()) {
            throw new SQLException("ResultSet 未定位到有效行（先调 next()）");
        }
        Object key = args[0];
        String column;
        if (key instanceof Integer) {
            int index = (Integer) key;
            if (index < 1 || index > data.columns.size()) {
                throw new SQLException("结果集列序号越界：" + index);
            }
            column = data.columns.get(index - 1);
        } else {
            column = String.valueOf(key);
        }
        Map<String, Object> row = data.rows.get(rowIndex);
        if (!row.containsKey(column)) {
            throw new SQLException("结果集无列：" + column);
        }
        Object value = row.get(column);
        lastWasNull = value == null;
        // 返回原值（含 null）；各 getter 自行处理 null（与 JDBC 语义一致：
        // getLong/getInt 遇 NULL 返回 0 + wasNull()=true；getTimestamp/getDate 遇 NULL 返回 null）
        return value;
    }

    // ------------------------------------------------------------------ 工具

    private Table requireTable(String name) throws SQLException {
        Table table = tables.get(name.toLowerCase(Locale.ROOT));
        if (table == null) {
            table = tables.get(name);
        }
        if (table == null) {
            throw new SQLException("表不存在（尚未建表）：" + name);
        }
        return table;
    }

    private static void bind(List<Object> bound, int index, Object value) {
        while (bound.size() < index) {
            bound.add(null);
        }
        bound.set(index - 1, value);
    }

    private static String readReader(Reader reader) {
        try (Reader r = reader) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取字符流失败：" + e.getMessage(), e);
        }
    }

    private static boolean containsNull(Map<String, Object> row, List<String> cols) {
        for (String col : cols) {
            if (row.get(col) == null) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameKey(Map<String, Object> a, Map<String, Object> b, List<String> cols) {
        for (String col : cols) {
            if (!sameValue(a.get(col), b.get(col))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return a.equals(b) || String.valueOf(a).equals(String.valueOf(b));
    }

    /**
     * SQL {@code LIKE} 求值（{@code %} = 任意长度；{@code _} = 单字符；其余为字面量，大小写敏感）。
     *
     * <p>SQL 语义：任一侧为 {@code NULL} ⇒ 结果为 {@code UNKNOWN} ⇒ {@code WHERE} 不命中。
     *
     * @param value   列值
     * @param pattern 模式串（绑定参数；可为 {@code null}）
     * @return 命中返回 {@code true}
     */
    private static boolean matchesLike(Object value, String pattern) {
        if (value == null || pattern == null) {
            return false;
        }
        return likeToRegex(pattern).matcher(String.valueOf(value)).matches();
    }

    private static Pattern likeToRegex(String like) {
        StringBuilder re = new StringBuilder("^");
        for (int i = 0; i < like.length(); i++) {
            char c = like.charAt(i);
            if (c == '%') {
                re.append(".*");
            } else if (c == '_') {
                re.append('.');
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        re.append('$');
        return Pattern.compile(re.toString(), Pattern.DOTALL);
    }

    private static int intArg(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static Object parseLiteral(String literal) {
        if (literal == null) {
            return null;
        }
        if (literal.startsWith("'")) {
            return unquote(literal);
        }
        if ("CURRENT_TIMESTAMP".equalsIgnoreCase(literal)) {
            return LocalDateTime.now();
        }
        if (literal.matches("-?[0-9]+")) {
            return Long.parseLong(literal);
        }
        if (literal.matches("-?[0-9]+\\.[0-9]+")) {
            return new BigDecimal(literal);
        }
        return literal;
    }

    private static String unquote(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 去 {@code --} 行注释并按深度 0 的分号切分语句。 */
    private static List<String> splitStatements(String script) {
        StringBuilder noComments = new StringBuilder();
        for (String line : script.split("\n")) {
            int idx = line.indexOf("--");
            noComments.append(idx < 0 ? line : line.substring(0, idx)).append('\n');
        }
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quote = false;
        StringBuilder cur = new StringBuilder();
        String text = noComments.toString();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                quote = !quote;
            } else if (!quote && ch == '(') {
                depth++;
            } else if (!quote && ch == ')') {
                depth--;
            }
            if (!quote && depth == 0 && ch == ';') {
                if (!cur.toString().trim().isEmpty()) {
                    out.add(cur.toString().trim());
                }
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        if (!cur.toString().trim().isEmpty()) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    /** 按深度 0 的逗号切分（尊重括号与引号）。 */
    private static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                quote = !quote;
            } else if (!quote && ch == '(') {
                depth++;
            } else if (!quote && ch == ')') {
                depth--;
            }
            if (!quote && depth == 0 && ch == ',') {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
        }
        if (!cur.toString().trim().isEmpty()) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    private static List<String> parseColumnsInParens(String clause) {
        Matcher m = Pattern.compile("(?is)\\((.*)\\)").matcher(clause);
        if (!m.find()) {
            throw new UnsupportedOperationException("缺少列清单括号：" + clause);
        }
        return splitColumnList(m.group(1));
    }

    private static List<String> splitColumnList(String raw) {
        List<String> cols = new ArrayList<>();
        for (String col : raw.split(",")) {
            String c = col.trim();
            if (!c.isEmpty()) {
                cols.add(c);
            }
        }
        return cols;
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return collapse(s);
    }

    private static String stripOuterParens(String text) {
        String s = text.trim();
        while (s.startsWith("(") && s.endsWith(")") && balanced(s.substring(1, s.length() - 1))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static boolean balanced(String text) {
        int depth = 0;
        boolean quote = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                quote = !quote;
            } else if (!quote && ch == '(') {
                depth++;
            } else if (!quote && ch == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static String firstLine(String sql) {
        int idx = sql.indexOf('\n');
        String s = idx < 0 ? sql : sql.substring(0, idx);
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }

    /** 行谓词。 */
    @FunctionalInterface
    private interface Predicate {
        boolean test(Map<String, Object> row);
    }
}
