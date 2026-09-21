package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ConfigItem;
import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.dao.entity.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuditFields} 单元测试（{@code DATA_MODEL.md} §0.3 通用审计字段）。
 *
 * <p>覆盖验收项："通用审计字段与编号生成…自动填充"——在 3 张表（{@code t_sub_account} /
 * {@code t_config_item} / {@code t_user}）的实体上验证。
 */
class AuditFieldsTest {

    private static final long OPERATOR = 9001L;

    @AfterEach
    void restoreClock() {
        com.hedgeguard.common.Clocks.reset();
    }

    private static void fixClock() {
        com.hedgeguard.common.Clocks.set(Clock.fixed(
                Instant.parse("2026-09-20T02:30:00Z"), com.hedgeguard.common.Clocks.ZONE));
    }

    @Test
    @DisplayName("插入填充：created_by/created_time 必填，updated_* 置空，row_version 初始为 1（三张表一致）")
    void applyInsertFillsAuditFieldsOnThreeTables() {
        fixClock();
        LocalDateTime expectedTime = LocalDateTime.of(2026, 9, 20, 10, 30, 0);

        SubAccount sub = new SubAccount();
        ConfigItem cfg = new ConfigItem();
        UserAccount user = new UserAccount();

        AuditFields.applyInsert(sub, OPERATOR);
        AuditFields.applyInsert(cfg, OPERATOR);
        AuditFields.applyInsert(user, OPERATOR);

        for (Object e : new Object[]{sub, cfg, user}) {
            com.hedgeguard.dao.entity.AuditedEntity a = (com.hedgeguard.dao.entity.AuditedEntity) e;
            assertEquals(OPERATOR, a.createdBy);
            assertEquals(expectedTime, a.createdTime);
            assertNull(a.updatedBy);
            assertNull(a.updatedTime);
            assertEquals(1, a.rowVersion);
        }
    }

    @Test
    @DisplayName("插入时若应用层已显式给出 created_time 则不被覆盖（时钟可控）")
    void applyInsertRespectsExplicitTime() {
        fixClock();
        SubAccount sub = new SubAccount();
        LocalDateTime explicit = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
        sub.createdTime = explicit;
        AuditFields.applyInsert(sub, OPERATOR);
        assertEquals(explicit, sub.createdTime);
    }

    @Test
    @DisplayName("系统自动创建时操作人取系统账号 ID（0）")
    void systemAccountIdIsZero() {
        fixClock();
        ConfigItem cfg = new ConfigItem();
        AuditFields.applyInsert(cfg, AuditFields.SYSTEM_USER_ID);
        assertEquals(0L, cfg.createdBy);
        assertEquals(0L, AuditFields.SYSTEM_USER_ID);
    }

    @Test
    @DisplayName("更新填充：updated_by/updated_time 写入，created_* 不动")
    void applyUpdateFillsUpdatedFields() {
        fixClock();
        UserAccount user = new UserAccount();
        AuditFields.applyInsert(user, OPERATOR);

        com.hedgeguard.common.Clocks.set(Clock.fixed(
                Instant.parse("2026-09-21T02:30:00Z"), com.hedgeguard.common.Clocks.ZONE));
        AuditFields.applyUpdate(user, 7777L);

        assertEquals(OPERATOR, user.createdBy);
        assertEquals(LocalDateTime.of(2026, 9, 20, 10, 30, 0), user.createdTime);
        assertEquals(7777L, user.updatedBy);
        assertEquals(LocalDateTime.of(2026, 9, 21, 10, 30, 0), user.updatedTime);
        assertEquals(1, user.rowVersion);
    }

    @Test
    @DisplayName("乐观锁：版本推进与数据库 row_version + 1 保持一致")
    void advancesVersionOnSuccessfulUpdate() {
        fixClock();
        SubAccount sub = new SubAccount();
        AuditFields.applyInsert(sub, OPERATOR);
        AuditFields.applyUpdate(sub, OPERATOR);
        AuditFields.advanceVersion(sub);
        assertEquals(2, sub.rowVersion);
        AuditFields.applyUpdate(sub, OPERATOR);
        AuditFields.advanceVersion(sub);
        assertEquals(3, sub.rowVersion);
    }

    @Test
    @DisplayName("乐观锁：缺少基准版本时拒绝更新（先读后改，禁止盲写）")
    void rejectsUpdateWithoutBaselineVersion() {
        fixClock();
        ConfigItem cfg = new ConfigItem();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AuditFields.applyUpdate(cfg, OPERATOR));
        assertTrue(e.getMessage().contains("乐观锁"));
        assertTrue(e.getMessage().contains("findById"));
    }

    @Test
    @DisplayName("§0.3 字段规格：row_version 初始 0、updated_* 可空（声明层面即数据结构）")
    void fieldShapeMatchesSpec() {
        SubAccount sub = new SubAccount();
        assertEquals(0, sub.rowVersion);
        assertNull(sub.updatedBy);
        assertNull(sub.updatedTime);
        assertEquals(0L, sub.createdBy);
        assertNull(sub.createdTime, "未填充前 created_time 应为 null（不得取系统时间）");
    }
}
