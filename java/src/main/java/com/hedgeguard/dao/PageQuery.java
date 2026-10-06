package com.hedgeguard.dao;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 分页查询参数（{@code ARCHITECTURE.md} §4.1.1「分页」）。
 *
 * <p>{@code size ∈ {10,20,50,100}}，默认 20；{@code sort} 形式 {@code field,asc}。
 * 越界取值<b>明确报错</b>，不静默截断（{@code PROJECT.md} §6「数据不足时明确报错，不静默降级」的同源纪律）。
 *
 * <p><b>缺省排序方向（{@code §4.1.1}「分页默认排序方向」）</b>：4 参
 * {@link #of(String, String, String, String)} 在「未传 {@code sort}」或「只给字段名不给方向」时，
 * 方向<b>缺省恒为 {@code ASC}</b>（向后兼容，语义不变）；5 参
 * {@link #of(String, String, String, String, String)} 可显式指定缺省方向
 * （{@code asc} / {@code desc}，大小写不敏感），用于「默认最新在前」类端点（如 {@code FR-PLAN-4}）。
 * {@code sort} <b>显式</b>给出方向时，以 {@code sort} 为准，覆盖缺省方向。
 *
 * <p><b>稳定排序（{@code §4.1.1}，{@code FR-PLAN-15}「分页排序稳定性」）</b>：{@code sort} 支持<b>多键</b>
 * （形如 {@code f1,dir1,f2,dir2}）。调用方以<b>末键唯一</b>（如主键）收官，{@link #orderBy()}
 * 即为<b>全序</b>，可避免「同一排序值跨页重复 / 漏行」（仅单键且取值有重复时，数据库对等值行的
 * 相对次序不作保证）。{@link #orderBy()} 给出完整排序子句，供 DAO 拼 {@code ORDER BY}。
 *
 * <p><b>装配层兜底</b>：DAO 装配 {@code ORDER BY} 时须调 {@link #orderByEnsuringUnique(String)}
 * （传入本表唯一列），使<b>默认路径</b>（不传 {@code sort}）的单键查询亦自动补唯一列升序收官，
 * 从而恒为全序——不依赖调用方主动给出多键。
 */
public final class PageQuery {

    /** 允许的每页条数（冻结，{@code §4.1.1}）。 */
    public static final List<Integer> ALLOWED_SIZES = Collections.unmodifiableList(
            Arrays.asList(10, 20, 50, 100));

    /** 默认每页条数。 */
    public static final int DEFAULT_SIZE = 20;

    /** 默认页码。 */
    public static final int DEFAULT_PAGE = 1;

    private static final String ASC = "ASC";
    private static final String DESC = "DESC";

    private final int page;
    private final int size;
    /** 排序键字段（按优先级排列，至少 1 项）。 */
    private final List<String> sortFields;
    /** 与 {@link #sortFields} 平行的方向标记（{@code true} = 升序）。 */
    private final List<Boolean> sortAscending;

    private PageQuery(int page, int size, List<String> sortFields, List<Boolean> sortAscending) {
        this.page = page;
        this.size = size;
        this.sortFields = sortFields;
        this.sortAscending = sortAscending;
    }

    /**
     * 解析查询参数（4 参，向后兼容）。
     *
     * <p>缺省排序方向<b>恒为 {@code ASC}</b>；等价于 5 参以 {@code "asc"} 调用
     * （{@link #of(String, String, String, String, String) of(page, size, sort, defaultSortField, "asc")}）。
     *
     * @param page      页码（可为 {@code null}，默认 1）
     * @param size      每页条数（可为 {@code null}，默认 20）
     * @param sort      {@code field,asc|desc}（可为 {@code null}）
     * @param defaultSortField 未指定排序时的默认字段（须由调用方给出白名单字段）
     * @return 分页参数
     */
    public static PageQuery of(String page, String size, String sort, String defaultSortField) {
        return of(page, size, sort, defaultSortField, "asc");
    }

    /**
     * 解析查询参数（5 参，可指定缺省排序方向；稳定排序用多键）。
     *
     * @param page      页码（可为 {@code null}，默认 1）
     * @param size      每页条数（可为 {@code null}，默认 20）
     * @param sort      {@code field[,asc|desc][,field2,asc|desc]...}（可为 {@code null}）；
     *                  多键形态用于稳定排序（末键唯一即构成全序）
     * @param defaultSortField 未指定排序时的默认字段（须由调用方给出白名单字段）
     * @param defaultDirection 缺省方向：{@code "asc"} / {@code "desc"}（大小写不敏感）；
     *                  其它值抛 {@link IllegalArgumentException}
     * @return 分页参数
     */
    public static PageQuery of(String page, String size, String sort, String defaultSortField,
                               String defaultDirection) {
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
        boolean defaultAsc = parseDefaultDirection(defaultDirection);
        List<String> fields = new ArrayList<>();
        List<Boolean> ascending = new ArrayList<>();
        if (sort != null && !sort.isEmpty()) {
            String[] parts = sort.split(",");
            if (parts.length == 1) {
                // 只给字段名，不给方向 ⇒ 方向取缺省方向
                fields.add(validateField(parts[0], sort));
                ascending.add(defaultAsc);
            } else if (parts.length % 2 == 0) {
                // 成对的 field,dir（多键，末键唯一即全序）
                for (int i = 0; i < parts.length; i += 2) {
                    fields.add(validateField(parts[i], sort));
                    ascending.add(parseSortDirection(parts[i + 1], sort));
                }
            } else {
                throw new IllegalArgumentException("sort 形如 field,asc[,field,asc...]：值=" + sort
                        + "；复现=使用形如 create_time,desc 或 exec_deadline,asc,id,asc 的形式");
            }
        }
        if (fields.isEmpty()) {
            if (defaultSortField == null || defaultSortField.isEmpty()) {
                throw new IllegalArgumentException("排序字段未指定且未给出默认字段；复现=调用方传入 defaultSortField");
            }
            fields.add(defaultSortField);
            ascending.add(defaultAsc);
        }
        return new PageQuery(p, s,
                Collections.unmodifiableList(fields), Collections.unmodifiableList(ascending));
    }

    private static int parseInt(String raw, String name) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("分页参数 " + name + " 非整数：值=" + raw
                    + "；复现=修正查询参数", e);
        }
    }

    /** 校验并返回单个排序字段名（限字母数字下划线，防注入）。 */
    private static String validateField(String raw, String sort) {
        String field = raw.trim();
        if (field.isEmpty() || !field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("sort 字段名非法：值=" + sort
                    + "；复现=使用形如 create_time,desc 的形式（字段名限字母数字下划线）");
        }
        return field;
    }

    /** 解析 {@code sort} 中显式给出的方向（{@code asc} / {@code desc}，大小写不敏感）。 */
    private static boolean parseSortDirection(String raw, String sort) {
        String dir = raw.trim().toLowerCase(Locale.ROOT);
        if (!"asc".equals(dir) && !"desc".equals(dir)) {
            throw new IllegalArgumentException("sort 方向须为 asc 或 desc：值=" + sort
                    + "；复现=形如 create_time,desc");
        }
        return "asc".equals(dir);
    }

    /** 解析缺省排序方向（{@code asc} / {@code desc}，大小写不敏感）；非法值明确报错。 */
    private static boolean parseDefaultDirection(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("缺省排序方向不得为 null：复现=传 \"asc\" 或 \"desc\"");
        }
        String dir = raw.trim().toLowerCase(Locale.ROOT);
        if (!"asc".equals(dir) && !"desc".equals(dir)) {
            throw new IllegalArgumentException("缺省排序方向须为 asc 或 desc（大小写不敏感）：值=" + raw
                    + "；复现=传 \"asc\" 或 \"desc\"");
        }
        return "asc".equals(dir);
    }

    /** 页码（从 1 起）。 */
    public int page() {
        return page;
    }

    /** 每页条数。 */
    public int size() {
        return size;
    }

    /** 首排序字段（已校验为合法标识符）。多键时返回首键，向后兼容。 */
    public String sortField() {
        return sortFields.get(0);
    }

    /**
     * 全部排序键字段（只读，按优先级；至少 1 项）。
     *
     * <p>多键场景下供装配层与白名单<b>逐键</b>处理：服务层须对<b>每一个</b>键做白名单校验
     * （仅校验首键会让第 2+ 键绕开防注入校验），DAO 装配层据此判断是否已含唯一列。
     *
     * @return 排序键字段（不可变）
     */
    public List<String> sortFields() {
        return sortFields;
    }

    /** 是否升序（取首排序键的方向）。多键时为首键方向，向后兼容。 */
    public boolean ascending() {
        return sortAscending.get(0);
    }

    /** SQL 偏移量。 */
    public int offset() {
        return (page - 1) * size;
    }

    /** 排序方向关键字（{@code ASC} / {@code DESC}；取首排序键，向后兼容）。 */
    public String direction() {
        return ascending() ? ASC : DESC;
    }

    /**
     * 完整排序子句（多键，形如 {@code "exec_deadline ASC, id ASC"}），供 DAO 拼 {@code ORDER BY}。
     *
     * <p><b>稳定分页</b>：仅当调用方以<b>末键唯一</b>（如主键）给出多键时，本子句才构成<b>全序</b>，
     * 从而保证「同一排序值跨页不重复、不漏行」。单键（或末键非唯一）时数据库对等值行的次序不作保证。
     */
    public String orderBy() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sortFields.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(sortFields.get(i)).append(' ').append(sortAscending.get(i) ? ASC : DESC);
        }
        return sb.toString();
    }

    /**
     * 完整排序子句，并在排序键<b>不含</b>唯一列时追加该列升序收官，使 {@code ORDER BY} 构成<b>全序</b>。
     *
     * <p><b>为何由装配层兜底</b>（{@code ARCHITECTURE.md} §4.1.1／{@code FR-PLAN-15}「分页排序稳定性」）：
     * 客户端<b>默认路径</b>（不传 {@code sort}）只给出单键；若该键取值有重复，数据库对等值行的相对次序
     * <b>不作保证</b> ⇒ 跨页会「重复 / 漏行」。由 DAO 装配 {@code ORDER BY} 时以<b>本表主键</b>兜底，
     * 可保证「任意两条记录排序键不同」（主键唯一）即全序，从而稳定分页。
     *
     * <p><b>不写死列名</b>：唯一列由 DAO 自身给出（{@code AbstractDmDao.idColumn()}），各表各取各的；
     * 排序键已含该列时<b>不重复追加</b>（判定<b>大小写不敏感</b>，见 {@link #containsSortField(String)}）。
     *
     * @param uniqueColumn 唯一列（通常为主键列）；{@code null} / 空 ⇒ 等价 {@link #orderBy()}
     * @return 完整排序子句（含必要 tie-break）
     */
    public String orderByEnsuringUnique(String uniqueColumn) {
        String clause = orderBy();
        if (uniqueColumn == null || uniqueColumn.isEmpty() || containsSortField(uniqueColumn)) {
            return clause;
        }
        return clause + ", " + uniqueColumn + ' ' + ASC;
    }

    /**
     * 排序键中是否已含指定列（<b>大小写不敏感</b>）。
     *
     * <p><b>为何忽略大小写</b>（{@code T-125}／{@code T-096} 复核观察 {@code O-4}）：SQL 标识符大小写
     * 不敏感，{@code sort} 允许大小写混写（如 {@code ID}／{@code Id}）；若按大小写敏感判定，非白名单
     * 路径传入大写 {@code ID} 时会误判「不含」而追加冗余键 {@code ID ASC, id ASC}。故按
     * {@link String#equalsIgnoreCase} 比较。
     */
    private boolean containsSortField(String column) {
        for (String field : sortFields) {
            if (field.equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }
}
