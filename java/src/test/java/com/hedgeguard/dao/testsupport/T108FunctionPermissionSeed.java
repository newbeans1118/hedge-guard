package com.hedgeguard.dao.testsupport;

import com.hedgeguard.dao.RolePermissionDao;
import com.hedgeguard.dao.entity.RolePermission;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code T-108} 功能权限链路验收用的<b>播种解析件</b>（建在 {@code dao} 命名空间内）。
 *
 * <p><b>为何单独成类、且置于 {@code dao} 目录</b>：{@code T-108} 的判据要求「播种被删即红」——
 * 故用例须从 {@code docs/DEPLOY/schema/data-init.sql} 第 ③-4 段<b>解析</b>「角色 → 权限」源行。
 * 解析文本中含 SQL 形态字面量，而 E5（{@code dao} 之外不得出现 SQL）对 {@code src/test} 同规则 ⇒
 * 依 {@link M1E2eSeed} 先例，本类置于 {@code com.hedgeguard.dao.testsupport}（该目录被 E5 排除），
 * 满足 E5 分域。
 *
 * <p>本类<b>不</b>属于产品代码，仅测试夹具；不改变任何 {@code src/main} 行为。
 */
public final class T108FunctionPermissionSeed {

    /** 播种段锚点（{@code data-init.sql} 第 ③-4 段）。 */
    public static final String SEED_ANCHOR = "③-4 角色功能权限 t_role_permission";

    /** 功能权限类别（本段恒取 {@code 'FUNCTION'}）。 */
    public static final String CATEGORY_FUNCTION = "FUNCTION";

    /**
     * {@code ck_role_perm_category} 的 CHECK 取值域（值域来源：
     * {@code docs/DEPLOY/schema/m1_config_auth.sql} 的
     * {@code CHECK (perm_category IN ('FUNCTION','DATA','QUOTA'))}）。
     *
     * <p><b>为何需要</b>：{@code BUG-T108-01} 的靶点 = ③-4 段 {@code perm_category} 字面量若被改成
     * 域外值（如 {@code 'BOGUS_CATEGORY'}），上线将因 CHECK 违例失败。用例须断言「解析出的类别
     * <b>∈ 本域</b>」才具守护力（{@link #categoryLiteral()} 从 SQL 真解析，非硬编码副本）。
     */
    public static final List<String> CATEGORY_CHECK_DOMAIN =
            List.of("FUNCTION", "DATA", "QUOTA");

    private T108FunctionPermissionSeed() {
    }

    /**
     * 取 {@code data-init.sql} 第 ③-4 段文本（自锚点至首个 {@code ';'}）。
     *
     * @return 段文本
     * @throws Exception 读取失败
     */
    public static String seedBlock() throws Exception {
        Path file = repoRoot().resolve("docs/DEPLOY/schema/data-init.sql");
        String sql = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int at = sql.indexOf(SEED_ANCHOR);
        if (at < 0) {
            throw new AssertionError("脚本缺第 ③-4 段（T-108 承接段；删该段即本判据红）");
        }
        int end = sql.indexOf(';', at);
        return sql.substring(at, end < 0 ? sql.length() : end);
    }

    /**
     * 解析 ③-4 段的「角色 → 权限」源行。
     *
     * <p>口径：源行以 {@code SELECT '} / {@code UNION ALL SELECT '} 起；取该行前两个单引号字面量
     * （角色编码、权限编码）；类别取<b>该段 {@code SELECT} 骨架第三列的字面量</b>
     * （形如 {@code SELECT r.id, src.perm, 'FUNCTION', 0, …}），<b>真解析 SQL</b>、不再硬编码 —
     * 若把播种类别改成域外值，本方法解析出的即为域外值，守护断言（{@link #CATEGORY_CHECK_DOMAIN}）
     * 随之转红（{@code BUG-T108-01} 靶点）。
     *
     * @return 每行 {@code [roleCode, permCode, permCategory]}
     * @throws Exception 读取失败
     */
    public static List<String[]> seedRows() throws Exception {
        String category = categoryLiteral();
        List<String[]> rows = new ArrayList<>();
        for (String rawLine : seedBlock().split("\n")) {
            String line = rawLine.trim();
            int at = selectQuoteAt(line);
            if (at < 0) {
                continue;
            }
            List<String> literals = quoteLiterals(line.substring(at));
            if (literals.size() < 2) {
                throw new AssertionError("③-4 源行须含角色编码与权限编码：" + line);
            }
            rows.add(new String[]{literals.get(0), literals.get(1), category});
        }
        return rows;
    }

    /**
     * 从 ③-4 段 {@code INSERT … SELECT} 骨架解析 {@code perm_category} 列字面量。
     *
     * <p>骨架为 {@code SELECT r.id, src.perm, '<CATEGORY>', 0, CURRENT_TIMESTAMP, 0 …}；本方法定位
     * 该 {@code SELECT r.id,} 行后取其单引号字面量（恰为第三列类别值）。<b>若脚本把类别改成域外值，
     * 本方法按原样返回该值</b>（不裁剪、不校正）⇒ 调用方据此可判越域。
     *
     * @return 段内 {@code perm_category} 字面量
     * @throws Exception 读取失败
     */
    public static String categoryLiteral() throws Exception {
        for (String rawLine : seedBlock().split("\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("SELECT r.id")) {
                continue;
            }
            List<String> literals = quoteLiterals(line);
            if (!literals.isEmpty()) {
                return literals.get(0);
            }
        }
        throw new AssertionError("③-4 段缺 SELECT r.id 骨架（无法解析 perm_category 字面量）");
    }

    /**
     * 把 ③-4 段中属于指定角色编码的功能权限行，经 {@link RolePermissionDao} 落库。
     *
     * @param dao        角色功能权限 DAO（真实实现）
     * @param roleId     角色主键
     * @param roleCode   角色编码（筛选 ③-4 段源行）
     * @param operatorId 操作人
     * @return 落库行数
     * @throws Exception 读取失败
     */
    public static int seedPermissionsFor(RolePermissionDao dao, long roleId, String roleCode,
                                         long operatorId) throws Exception {
        int count = 0;
        for (String[] row : seedRows()) {
            if (!roleCode.equals(row[0])) {
                continue;
            }
            RolePermission rp = new RolePermission();
            rp.roleId = roleId;
            rp.permCode = row[1];
            rp.permCategory = row[2];
            dao.insert(rp, operatorId);
            count++;
        }
        return count;
    }

    /** 源行起始下标（该行以 {@code SELECT '} / {@code UNION ALL SELECT '} 起）；否则 {@code -1}。 */
    private static int selectQuoteAt(String line) {
        if (line.startsWith("SELECT '")) {
            return 0;
        }
        String union = "UNION ALL ";
        if (line.startsWith(union + "SELECT '")) {
            return union.length();
        }
        return -1;
    }

    /** 提取文本中单引号字面量（去引号）；本段字面量不含转义单引号。 */
    private static List<String> quoteLiterals(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '\'') {
                i++;
                continue;
            }
            int end = text.indexOf('\'', i + 1);
            if (end < 0) {
                break;
            }
            out.add(text.substring(i + 1, end));
            i = end + 1;
        }
        return out;
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test，当前="
                + Paths.get("").toAbsolutePath());
    }
}
