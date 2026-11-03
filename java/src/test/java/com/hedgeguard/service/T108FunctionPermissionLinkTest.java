package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.entity.PositionGroup;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.RolePermission;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.impl.DmAuthorizationDao;
import com.hedgeguard.dao.impl.DmLoginLogDao;
import com.hedgeguard.dao.impl.DmLoginSessionDao;
import com.hedgeguard.dao.impl.DmPermissionChangeLogDao;
import com.hedgeguard.dao.impl.DmPositionConflictDao;
import com.hedgeguard.dao.impl.DmPositionGroupDao;
import com.hedgeguard.dao.impl.DmRoleDao;
import com.hedgeguard.dao.impl.DmRolePermissionDao;
import com.hedgeguard.dao.impl.DmUnauthorizedAttemptDao;
import com.hedgeguard.dao.impl.DmUserAccountDao;
import com.hedgeguard.dao.impl.DmUserRoleDao;
import com.hedgeguard.dao.impl.DmUserSubAccountPermDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.T108FunctionPermissionSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-108} 功能权限链路（{@code ARCHITECTURE.md} §8.2③「M2 = 功能权限」）的
 * <b>真实 DAO + 真实播种</b>回归守卫。
 *
 * <p><b>为何必须走真实 DAO + 真实播种文本</b>：本单的真实内容 = 写面（{@code defineRole} 落
 * {@code t_role_permission}）+ 读面（{@code hasFunctionPermission}）+ <b>播种</b>。
 * 若仅用假 DAO、或把权限行硬编码进用例，"播种被删"这一失效（即 {@code ARCHITECTURE.md} §8.2③
 * 「M2 上线即误拒」的成因）<b>不会被任何断言捕获</b>。故本用例：
 * <ol>
 *   <li>从 {@code docs/DEPLOY/schema/data-init.sql} 第 ③-4 段<b>解析源行</b>
 *       （删该段 / 删该行 ⇒ 解析断言即红）；</li>
 *   <li>把解析出的「角色→权限」行经<b>真实</b> {@link DmRolePermissionDao} 落库；</li>
 *   <li>以<b>真实</b> {@link DmRoleDao}/{@link DmUserRoleDao} 装配角色/用户绑定；</li>
 *   <li>断言 {@link AuthService#hasFunctionPermission} 经 {@code t_user_role → t_role_permission} 返真/假。</li>
 * </ol>
 *
 * <p><b>判据形状（本单要求）</b>：角色 {@code FIN_RISK} 播种含 {@code PRODUCT:EDIT}
 * ⇒ {@code hasFunctionPermission(u,"PRODUCT:EDIT")} 返 {@code true}；该行<b>不生效</b> ⇒ 返 {@code false}。
 *
 * <p><b>反退化（变异有牙；引擎陷阱 #1 已规避）</b>：{@link InMemoryDmEngine} 的
 * {@code DELETE ... WHERE} 带绑定参数 affected=0 静默不删 ⇒ 本用例以真实 DAO
 * {@code updateById}（改行）+「未播种该行」两种形态构造"行不生效"，均落在 {@code UPDATE}/种子上，
 * 不放任 {@code DELETE} 静默。
 */
class T108FunctionPermissionLinkTest {

    /** 固定业务时刻（可注入时钟，{@code NFR-TEST-4}）。 */
    private static final Instant T0 = Instant.parse("2026-11-03T02:00:00Z");

    private static final String SCHEMA = "docs/DEPLOY/schema/m1_config_auth.sql";

    /** 判据权限编码（{@code data-init.sql} ③-4 播种予 {@code FIN_RISK}）。 */
    private static final String PERM_PRODUCT_EDIT = "PRODUCT:EDIT";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private AuthService service;
    private DmRoleDao roleDao;
    private DmPositionGroupDao groupDao;
    private DmRolePermissionDao rolePermissionDao;

    @BeforeEach
    void setUp() throws Exception {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        engine = new InMemoryDmEngine();
        Path schema = repoRoot().resolve(SCHEMA);
        assertTrue(Files.isRegularFile(schema), "缺少建表脚本：" + schema);
        engine.executeScript(new String(Files.readAllBytes(schema), StandardCharsets.UTF_8));

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        roleDao = new DmRoleDao(tx);
        groupDao = new DmPositionGroupDao(tx);
        rolePermissionDao = new DmRolePermissionDao(tx);
        service = new AuthService(tx, new DmUserAccountDao(tx), roleDao, new DmUserRoleDao(tx),
                new DmPositionConflictDao(tx), new DmLoginSessionDao(tx), new DmLoginLogDao(tx),
                null, new DmUserSubAccountPermDao(tx), new DmUnauthorizedAttemptDao(tx),
                new SessionStore.InMemory(), new DmAuthorizationDao(tx),
                new DmPermissionChangeLogDao(tx), groupDao, rolePermissionDao, Config.empty());
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    // ------------------------------------------------------------ 播种段结构（删行即红）

    @Test
    @DisplayName("播种段存在且幂等：③-4 段含 FIN_RISK→PRODUCT:EDIT，按 NOT EXISTS 去重")
    void seedSectionExistsAndIsIdempotent() throws Exception {
        String block = T108FunctionPermissionSeed.seedBlock();
        assertTrue(block.contains(T108FunctionPermissionSeed.SEED_ANCHOR),
                "脚本缺第 ③-4 段（T-108 承接段）");
        assertTrue(block.contains("NOT EXISTS"),
                "③-4 播种须幂等（NOT EXISTS 按 uk_role_perm 去重）");
        List<String[]> rows = T108FunctionPermissionSeed.seedRows();
        assertFalse(rows.isEmpty(), "③-4 段源行不得为空");
        for (String[] row : rows) {
            assertEquals(T108FunctionPermissionSeed.CATEGORY_FUNCTION, row[2],
                    "perm_category 须为 FUNCTION：" + Arrays.toString(row));
        }
        assertTrue(rows.stream().anyMatch(r -> "FIN_RISK".equals(r[0])
                        && PERM_PRODUCT_EDIT.equals(r[1])),
                "解析出的源行须含 FIN_RISK → " + PERM_PRODUCT_EDIT
                        + "（删该播实行本用例即红）");
    }

    // ------------------------------------------------------------ 读面（正判据 + 翻转为假）

    @Test
    @DisplayName("读面正判据：FIN_RISK 播种含 PRODUCT:EDIT ⇒ hasFunctionPermission(u,PRODUCT:EDIT)=true")
    void seededPermissionGrantsAccess() throws Exception {
        long groupId = seedGroup("FIN_RISK");
        long roleId = seedRole("FIN_RISK", groupId);
        long userId = seedUser("lisi", "NP-001");
        seedUserRole(userId, roleId);
        int seeded = T108FunctionPermissionSeed.seedPermissionsFor(rolePermissionDao, roleId,
                "FIN_RISK", 0L);
        assertTrue(seeded >= 1, "③-4 段须为 FIN_RISK 播种至少 1 条功能权限");

        assertTrue(service.hasFunctionPermission(userId, PERM_PRODUCT_EDIT),
                "播种含 PRODUCT:EDIT ⇒ 判据须为 true（经 t_user_role → t_role_permission）");
        assertFalse(service.hasFunctionPermission(userId, "NO_SUCH:PERM"),
                "未播种的权限编码须为 false（安全默认）");
    }

    @Test
    @DisplayName("读面翻转为假：该行不生效（真实 DAO 改写 perm_code）⇒ hasFunctionPermission=false")
    void invalidatedPermissionRowFlipsToFalse() throws Exception {
        long groupId = seedGroup("FIN_RISK");
        long roleId = seedRole("FIN_RISK", groupId);
        long userId = seedUser("lisi", "NP-001");
        seedUserRole(userId, roleId);
        T108FunctionPermissionSeed.seedPermissionsFor(rolePermissionDao, roleId, "FIN_RISK", 0L);
        assertTrue(service.hasFunctionPermission(userId, PERM_PRODUCT_EDIT), "改前须为 true");

        // 引擎陷阱 #1：DELETE ... WHERE 带绑定参数 affected=0 静默不删 ⇒ 用真实 DAO updateById 改行
        RolePermission row = rolePermissionDao.findByRole(roleId).stream()
                .filter(p -> PERM_PRODUCT_EDIT.equals(p.permCode)).findFirst().orElse(null);
        assertNotNull(row, "须先读到播种行");
        row.permCode = "PRODUCT:EDIT_RENAMED";
        assertEquals(1, rolePermissionDao.updateById(row, 9L), "改行须命中（乐观锁）");

        assertFalse(service.hasFunctionPermission(userId, PERM_PRODUCT_EDIT),
                "该行不再匹配 PRODUCT:EDIT ⇒ 判据须翻转为 false（断言翻转即红；（正）判据有牙）");
        assertTrue(service.hasFunctionPermission(userId, "PRODUCT:EDIT_RENAMED"),
                "改写后的编码须生效（证明翻转来自数据、非缓存/短路）");
    }

    @Test
    @DisplayName("读面安全默认：未播种该权限行 ⇒ false；禁用角色 ⇒ false；空编码 ⇒ false")
    void unseededOrDisabledRoleYieldsFalse() throws Exception {
        long groupId = seedGroup("FIN_RISK");
        long roleId = seedRole("FIN_RISK", groupId);
        long userId = seedUser("lisi", "NP-001");
        seedUserRole(userId, roleId);
        // 不播种任何功能权限行
        assertFalse(service.hasFunctionPermission(userId, PERM_PRODUCT_EDIT),
                "未播种该行 ⇒ false（M2 上线即误拒的成因；此处须为安全默认拒绝）");

        // 播种后再禁用角色 ⇒ 仍 false（只认启用角色）
        T108FunctionPermissionSeed.seedPermissionsFor(rolePermissionDao, roleId, "FIN_RISK", 0L);
        assertTrue(service.hasFunctionPermission(userId, PERM_PRODUCT_EDIT));
        Role role = roleDao.findById(roleId);
        role.status = "DISABLED";
        assertEquals(1, roleDao.updateById(role, 9L), "禁用角色须命中");
        assertFalse(service.hasFunctionPermission(userId, PERM_PRODUCT_EDIT),
                "禁用角色的功能权限不生效");

        assertFalse(service.hasFunctionPermission(userId, "  "), "空编码 ⇒ false");
        assertFalse(service.hasFunctionPermission(999L, PERM_PRODUCT_EDIT), "无角色用户 ⇒ false");
    }

    @Test
    @DisplayName("未装配 RolePermissionDao（null）⇒ 恒 false（宁可拒绝，不得放行）")
    void nullDaoFailsClosed() {
        AuthService noDao = new AuthService(FakeTx.newTemplate(), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, Config.empty());
        assertFalse(noDao.hasFunctionPermission(1L, PERM_PRODUCT_EDIT),
                "装配缺失时读面须安全默认拒绝（不得放行）");
    }

    // ------------------------------------------------------------ 写面（defineRole 落 t_role_permission）

    @Test
    @DisplayName("写面：defineRole 收功能权限集合 ⇒ 落 t_role_permission（FUNCTION），去重生效")
    void defineRolePersistsFunctionPermissions() {
        long groupId = seedGroup("FIN_RISK");
        long roleId = service.defineRole("AUDITOR", "审计岗", (int) groupId, "N", 9L, 8L,
                Arrays.asList(PERM_PRODUCT_EDIT, PERM_PRODUCT_EDIT, "CONFIG:EDIT"));

        List<RolePermission> rows = rolePermissionDao.findByRole(roleId);
        assertEquals(2, rows.size(), "去重后应落 2 行（PRODUCT:EDIT / CONFIG:EDIT）");
        assertTrue(rows.stream().allMatch(r -> "FUNCTION".equals(r.permCategory)),
                "perm_category 须恒为 FUNCTION");
        assertTrue(rows.stream().anyMatch(r -> PERM_PRODUCT_EDIT.equals(r.permCode)));
        assertTrue(rows.stream().anyMatch(r -> "CONFIG:EDIT".equals(r.permCode)));

        // 端到端：经用户绑定后读面可见（写面与读面同批闭环）
        long userId = seedUser("lisi", "NP-001");
        seedUserRole(userId, roleId);
        assertTrue(service.hasFunctionPermission(userId, PERM_PRODUCT_EDIT),
                "写面落库的功能权限须可被读面取到（同批闭环）");
    }

    @Test
    @DisplayName("写面兼容：functionPermissions 省略/空 ⇒ 不落 t_role_permission 行；既有 6 参签名不变")
    void defineRoleWithoutPermissionsWritesNoRows() {
        long groupId = seedGroup("FIN_RISK");
        long legacy = service.defineRole("AUDITOR", "审计岗", (int) groupId, "N", 9L, 8L);
        assertTrue(rolePermissionDao.findByRole(legacy).isEmpty(), "省略 ⇒ 不落功能权限行");

        long empty = service.defineRole("SETTLER", "结算岗", (int) groupId, "N", 9L, 8L,
                Collections.emptyList());
        assertTrue(rolePermissionDao.findByRole(empty).isEmpty(), "空集合 ⇒ 不落功能权限行");
    }

    @Test
    @DisplayName("写面禁越界：系统管理员含 TRADE:PLACE_ORDER 功能权限被拒（R-AUTH-10 / FR-AUTH-6 验收 2）")
    void defineRoleRejectsSysAdminTradePermission() {
        long groupId = seedGroup("SYS_ADMIN");
        assertEquals(ErrorCode.FUNCTION_PERMISSION_DENIED,
                assertThrows(BizException.class, () -> service.defineRole(AuthService.ROLE_SYS_ADMIN,
                        "系统管理员", (int) groupId, "N", 9L, 8L,
                        Collections.singletonList(AuthService.PERM_TRADE_PLACE_ORDER))).errorCode(),
                "系统管理员含交易下单功能权限须拒（不得一键通办）");
        assertTrue(roleDao.findByCode(AuthService.ROLE_SYS_ADMIN) == null,
                "校验失败不得留下角色半态（CONTRACTS §5.3）");
    }

    @Test
    @DisplayName("写面校验先于落库：空白权限编码 ⇒ 10001 且无角色半态")
    void defineRoleRejectsBlankPermissionBeforeInsert() {
        long groupId = seedGroup("FIN_RISK");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.defineRole("AUDITOR", "审计岗",
                        (int) groupId, "N", 9L, 8L, Collections.singletonList("  "))).errorCode(),
                "空白功能权限编码须拒");
        assertTrue(roleDao.findByCode("AUDITOR") == null, "校验失败不得留下角色半态");
    }

    // ------------------------------------------------------------ 种子构造（真实 DAO）

    private long seedGroup(String code) {
        PositionGroup g = new PositionGroup();
        g.groupCode = code;
        g.groupName = code;
        g.frontMidBack = "MID";
        g.isConflictEnforced = "Y";
        g.status = "ENABLED";
        groupDao.insert(g, 0L);
        return g.id;
    }

    private long seedRole(String code, long groupId) {
        Role r = new Role();
        r.roleCode = code;
        r.roleName = code;
        r.positionGroupId = (int) groupId;
        r.isPreset = "N";
        r.isBusinessRole = AuthService.ROLE_SYS_ADMIN.equals(code) ? "N" : "Y";
        r.hasTradingOperation = "N";
        r.status = "ENABLED";
        roleDao.insert(r, 0L);
        return r.id;
    }

    private long seedUser(String username, String npKey) {
        UserAccount u = new UserAccount();
        u.username = username;
        u.realName = username;
        u.naturalPersonKey = npKey;
        u.isEnterpriseLeader = "N";
        u.status = "ENABLED";
        u.passwordHash = "pbkdf2$1$x$y";
        u.mustChangePassword = "N";
        u.failCount = 0;
        new DmUserAccountDao(tx).insert(u, 0L);
        return u.id;
    }

    private void seedUserRole(long userId, long roleId) {
        UserRole ur = new UserRole();
        ur.userId = userId;
        ur.roleId = roleId;
        ur.grantedBy = 0L;
        ur.grantedTime = Clocks.now();
        new DmUserRoleDao(tx).insert(ur, 0L);
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
