package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ConfigChangeLogDao;
import com.hedgeguard.dao.ConfigItemDao;
import com.hedgeguard.dao.LoginLogDao;
import com.hedgeguard.dao.LoginSessionDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PasswordHistoryDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.SubAccountDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.ConfigChangeLog;
import com.hedgeguard.dao.entity.LoginLog;
import com.hedgeguard.dao.entity.PasswordHistory;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CONFIG/AUTH 表族 DAO 实现的结构性契约校验（`T-004` 余额；**完全离线**，不接触数据库）。
 *
 * <p>覆盖三条**机器可查**的契约：
 * <ol>
 *   <li><b>只增不改</b>（`CONTRACTS.md` §2.3）：4 张留痕表的 {@code updateById} 必须**显式拒绝**；
 *       且拒绝必须发生在**触碰事务之前**（以 {@code tx == null} 构造即可证明）——这样将来若有人"顺手"去掉拒绝，
 *       用例会在**不连库**的情况下立刻红。</li>
 *   <li><b>`DmConfigChangeLogDao` 的设计偏离是刻意的</b>：它**不得**继承 `AbstractDmDao`（泛型上界 + 硬写
 *       `updated_*`/`row_version` 会写出本表不存在的列）；其写入列清单**不含**这三列。</li>
 *   <li><b>接口—实现闭合</b>：7 个接口均有唯一 `Dm*` 实现且可空事务构造（支撑"受配置保护装配"）。</li>
 * </ol>
 *
 * <p>⚠️ **尚未覆盖**（诚实声明，下一单元补）：需要 {@code ResultSet} 级假连接的 SQL 行为用例
 * （如 {@code findRecent} 的取回后截断）——
 * 现有测试支撑仅有 {@code UnsupportedBaseDao}，**没有** JDBC 级假件；将与 `TxTemplateTest` 的假连接手法
 * 统一后补齐，避免在此用未经证实的桩制造"看起来覆盖了"的假象。
 * （{@code scopeOf} **已删除**〔`T-099`，2026-09-21：不读 {@code perm_type}，不可作取数入口；
 * 因「以接口类型注入调用」对 {@code @Deprecated} 零防护，故删而非弃用〕，已**无从**为其补行为用例；
 * 见 {@code T099ScopeOfDeprecationTest#scopeOfMustNotBeRedeclared} 的防复活守卫。）
 */
class M1AuthDaoImplTest {

    @Test
    @DisplayName("🔴 只增不改：4 张留痕表的 updateById 必须在触碰事务之前显式拒绝（tx=null 即可证明）")
    void appendOnlyDaosRefuseUpdateBeforeTouchingTransaction() {
        assertRefusedBeforeTx("t_login_log", () ->
                new DmLoginLogDao(null).updateById(new LoginLog(), 1L));
        assertRefusedBeforeTx("t_password_history", () ->
                new DmPasswordHistoryDao(null).updateById(new PasswordHistory(), 1L));
        assertRefusedBeforeTx("t_unauthorized_attempt", () ->
                new DmUnauthorizedAttemptDao(null).updateById(new UnauthorizedAttempt(), 1L));
        assertRefusedBeforeTx("t_config_change_log", () ->
                new DmConfigChangeLogDao(null).updateById(new ConfigChangeLog(), 1L));
    }

    @Test
    @DisplayName("DmConfigChangeLogDao：泛型上界使其不可继承 AbstractDmDao（刻意的设计偏离）")
    void configChangeLogDaoDeviatesByDesign() {
        assertFalse(AbstractDmDao.class.isAssignableFrom(DmConfigChangeLogDao.class),
                "t_config_change_log 无 updated_*/row_version，继承 AbstractDmDao 会写出不存在的列");
        assertTrue(ConfigChangeLogDao.class.isAssignableFrom(DmConfigChangeLogDao.class));
    }

    @Test
    @DisplayName("DmConfigChangeLogDao：写入列清单不含 updated_by/updated_time/row_version（防写出不存在的列）")
    void configChangeLogWrittenColumnsExcludeNonExistentColumns() {
        List<String> cols = DmConfigChangeLogDao.writtenColumns();
        assertTrue(cols.contains("config_key") && cols.contains("created_by")
                && cols.contains("created_time"), "应含业务列与既有审计列，实际=" + cols);
        for (String forbidden : new String[]{"updated_by", "updated_time", "row_version"}) {
            assertFalse(cols.contains(forbidden),
                    "本表无该列，绝不可写入：" + forbidden + "；实际列=" + cols);
        }
    }

    @Test
    @DisplayName("DmConfigChangeLogDao：通用分页已按 T-109 实现（不再显式拒绝；凭据 = GET /config/change-log）")
    void configChangeLogDaoPagingImplementedByT109() {
        // T-109 为 GET /config/change-log（FR-CONFIG-12：按配置分类 + 时间范围筛选）实现通用分页
        // （PageQuery + DmDialect.paginate）。以 tx=null 调用会在**触碰事务**时失败（NPE），
        // 而**不再**抛 UnsupportedOperationException —— 若有人回退为"显式拒绝"，本断言即红。
        PageQuery query = PageQuery.of("1", "20", null, "change_time");
        assertThrows(NullPointerException.class,
                () -> new DmConfigChangeLogDao(null).findPage(query, null, null),
                "findPage 已实现（T-109）：应因缺事务而失败，而非显式拒绝");
    }

    @Test
    @DisplayName("接口—实现闭合：7 个 CONFIG/AUTH 接口均有实现，且可空事务构造（支撑受配置保护装配）")
    void allAuthDaosHaveImplAndConstructWithNullTx() {
        assertDoesNotThrow(() -> new DmRoleDao(null));
        assertDoesNotThrow(() -> new DmLoginSessionDao(null));
        assertDoesNotThrow(() -> new DmLoginLogDao(null));
        assertDoesNotThrow(() -> new DmPasswordHistoryDao(null));
        assertDoesNotThrow(() -> new DmUnauthorizedAttemptDao(null));
        assertDoesNotThrow(() -> new DmUserSubAccountPermDao(null));
        assertDoesNotThrow(() -> new DmConfigChangeLogDao(null));

        assertTrue(RoleDao.class.isAssignableFrom(DmRoleDao.class));
        assertTrue(LoginSessionDao.class.isAssignableFrom(DmLoginSessionDao.class));
        assertTrue(LoginLogDao.class.isAssignableFrom(DmLoginLogDao.class));
        assertTrue(PasswordHistoryDao.class.isAssignableFrom(DmPasswordHistoryDao.class));
        assertTrue(UnauthorizedAttemptDao.class.isAssignableFrom(DmUnauthorizedAttemptDao.class));
        assertTrue(UserSubAccountPermDao.class.isAssignableFrom(DmUserSubAccountPermDao.class));
        // 既有实现（T-004 首批）与 CONFIG 族亦须在册，防止未来被误删
        assertTrue(SubAccountDao.class.isAssignableFrom(DmSubAccountDao.class));
        assertTrue(ConfigItemDao.class.isAssignableFrom(DmConfigItemDao.class));
        assertTrue(UserAccountDao.class.isAssignableFrom(DmUserAccountDao.class));
    }

    private static void assertRefusedBeforeTx(String table, Runnable call) {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                call::run, table + " 必须显式拒绝更新");
        assertTrue(e.getMessage().contains(table),
                "拒绝信息应含表名 " + table + "，实际=" + e.getMessage());
    }
}
