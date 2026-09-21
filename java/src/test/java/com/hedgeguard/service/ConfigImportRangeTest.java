package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ConfigChangeLogDao;
import com.hedgeguard.dao.ConfigItemDao;
import com.hedgeguard.dao.entity.ConfigChangeLog;
import com.hedgeguard.dao.entity.ConfigItem;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FR-CONFIG-13} 验收 2 导入取值范围预校验的修复验收（{@code TE-T109-004}）。
 *
 * <p>导入路径须复用 {@code changeConfigValue} 的同一 {@code validateValue}：任一项越界即
 * <b>整体不导入</b>并<b>定位到该项</b>；缺省上下界回退到既有项，防"缺省下界"绕过。
 */
class ConfigImportRangeTest {

    private static final Instant T0 = Instant.parse("2026-09-20T02:00:00Z");

    private FakeConfigItemDao itemDao;
    private FakeConfigChangeLogDao logDao;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(T0, Clocks.ZONE));
        itemDao = new FakeConfigItemDao();
        logDao = new FakeConfigChangeLogDao();
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("FR-CONFIG-13 验收 2：越界取值 → 整体不导入并定位到该项")
    void importOutOfRangeRejected() {
        ConfigItem item = item("risk.daily.limit", "INT", "10", "20", "99");

        BizException e = assertThrows(BizException.class,
                () -> service().importConfigItems(Arrays.asList(item), "OVERWRITE", 7L));
        assertEquals(ErrorCode.IMPORT_VALIDATION_FAILED, e.errorCode());
        assertTrue(e.getMessage().contains("第 1 项"), "须定位到第 1 项：" + e.getMessage());
        assertTrue(e.getMessage().contains("上界"), "须给出越界原因：" + e.getMessage());
        assertTrue(itemDao.rows.isEmpty(), "整体不导入，库内不变");
    }

    @Test
    @DisplayName("FR-CONFIG-13：范围内取值 → 正常导入（对照）")
    void importInRangeAccepted() {
        int written = service().importConfigItems(
                Arrays.asList(item("risk.daily.limit", "INT", "10", "20", "15")), "OVERWRITE", 7L);
        assertEquals(1, written);
        assertEquals(1, itemDao.rows.size());
    }

    @Test
    @DisplayName("FR-CONFIG-13：批次内一项越界 → 全或无（合法项亦不落库）")
    void importAllOrNothing() {
        ConfigItem ok = item("risk.daily.limit", "INT", "10", "20", "15");
        ConfigItem bad = item("risk.weekly.limit", "INT", "10", "20", "999");

        assertThrows(BizException.class,
                () -> service().importConfigItems(Arrays.asList(ok, bad), "OVERWRITE", 7L));
        assertTrue(itemDao.rows.isEmpty(), "全或无：任一不合法整体不导入");
    }

    @Test
    @DisplayName("FR-CONFIG-13：导入项缺省上下界回退既有项 ⇒ 仍拒越界（不可绕过）")
    void importBoundsFallbackToStored() {
        ConfigItem stored = item("risk.daily.limit", "INT", "10", "20", "12");
        itemDao.rows.add(stored);
        ConfigItem incoming = item("risk.daily.limit", "INT", null, null, "999");

        assertThrows(BizException.class,
                () -> service().importConfigItems(Arrays.asList(incoming), "OVERWRITE", 7L));
    }

    @Test
    @DisplayName("FR-CONFIG-13 验收 2：BOOL 越域取值 → 整体不导入")
    void importBoolOutOfDomainRejected() {
        assertThrows(BizException.class, () -> service().importConfigItems(
                Arrays.asList(item("risk.enabled", "BOOL", null, null, "TRUE")), "OVERWRITE", 7L));
    }

    // ------------------------------------------------------------ 辅助

    private ConfigService service() {
        return new ConfigService(FakeTx.newTemplate(), null, itemDao, logDao, null, null, null,
                null, null);
    }

    private static ConfigItem item(String key, String type, String min, String max, String value) {
        ConfigItem item = new ConfigItem();
        item.configKey = key;
        item.configCategory = "RISK";
        item.valueType = type;
        item.minValue = min == null ? null : new BigDecimal(min);
        item.maxValue = max == null ? null : new BigDecimal(max);
        item.configValue = value;
        return item;
    }

    private static final class FakeConfigItemDao extends UnsupportedBaseDao<ConfigItem, Long>
            implements ConfigItemDao {

        private final List<ConfigItem> rows = new ArrayList<>();
        private long nextId = 1;

        @Override
        public ConfigItem findByKey(String configKey) {
            for (ConfigItem row : rows) {
                if (row.configKey != null && row.configKey.equals(configKey)) {
                    return row;
                }
            }
            return null;
        }

        @Override
        public int insert(ConfigItem entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public int updateById(ConfigItem entity, long operatorId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id == entity.id) {
                    rows.set(i, entity);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public List<ConfigItem> findByCategory(String category) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByCategory");
        }

        @Override
        public List<ConfigItem> findDispatchable() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findDispatchable");
        }

        @Override
        public int bumpEffectiveVersion(String configKey) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("bumpEffectiveVersion");
        }
    }

    private static final class FakeConfigChangeLogDao
            extends UnsupportedBaseDao<ConfigChangeLog, Long> implements ConfigChangeLogDao {

        @Override
        public int insert(ConfigChangeLog entity, long operatorId) {
            return 1;
        }

        @Override
        public List<ConfigChangeLog> findByKeyBetween(String configKey, java.time.LocalDateTime from,
                                                      java.time.LocalDateTime to) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findByKeyBetween");
        }

        @Override
        public List<ConfigChangeLog> findDispatchProblems() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("findDispatchProblems");
        }
    }
}
