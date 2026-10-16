package com.hedgeguard.dao.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-196} 回归：等价引擎 {@code DELETE} 语义（测试基建）。
 *
 * <p><b>缺陷背景（T-196）</b>：{@code InMemoryDmEngine.doDelete} 原实现为
 * {@code removeIf(row -> !where.test(row))} —— 谓词取反 ⇒ <b>保留命中行、删除其余</b>，
 * 与 SQL {@code DELETE FROM t WHERE cond}（删命中行）相反（编排者 + te-verify-t080b
 * 双独立实证：{@code deleteById(id=3)} affected=3 且保留 id=3）。本类按 SQL 语义
 * 锁定订正后行为：
 * <ol>
 *   <li>命中行被删、未命中行保留；</li>
 *   <li>affected == 命中数；</li>
 *   <li>绑定参数形态（{@code WHERE col = ?} / {@code IN (?, ?)}）与字面量形态
 *       （{@code IN (字面量)}）同验；</li>
 *   <li>无条件 {@code DELETE FROM t}（无 WHERE）= 全表删除（SQL 语义）。</li>
 * </ol>
 *
 * <p>行集读取直用 {@code engine.table(name).rows}（te-verify-t080b 判例：探针读行
 * 直读内存行，不经 SELECT 面）。本类属测试基建自检，不改变任何 {@code src/main} 行为。
 */
class InMemoryDmEngineDeleteSemanticsTest {

    private static final String TABLE = "t_del_probe";
    private static final String DDL =
            "CREATE TABLE t_del_probe (id BIGINT NOT NULL, tag VARCHAR(16) NOT NULL,"
                    + " CONSTRAINT pk_t_del_probe PRIMARY KEY (id))";

    private InMemoryDmEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new InMemoryDmEngine();
        engine.executeScript(DDL);
        insert(1L, "a");
        insert(2L, "b");
        insert(3L, "c");
        assertEquals(3, engine.table(TABLE).rows.size(), "前置：3 行种子");
    }

    @Test
    @DisplayName("命中行被删：DELETE WHERE id = ? 删 id=2、保留 1/3、affected=1")
    void deletesMatchedRowOnly() {
        int affected = exec("DELETE FROM t_del_probe WHERE id = ?", 2L);
        assertEquals(1, affected, "affected = 命中数（1）");
        List<Long> ids = ids();
        assertTrue(ids.contains(1L) && ids.contains(3L), "未命中行保留：" + ids);
        assertFalse(ids.contains(2L), "命中行被删（T-196 订正：原反演语义保留 id=2）");
    }

    @Test
    @DisplayName("affected == 命中数：WHERE tag = ? 命中 2 行 ⇒ affected=2")
    void affectedEqualsMatchCount() {
        insert(4L, "a");
        int affected = exec("DELETE FROM t_del_probe WHERE tag = ?", "a");
        assertEquals(2, affected, "命中 id=1 与 id=4 ⇒ affected=2");
        List<Long> ids = ids();
        assertTrue(ids.contains(2L) && ids.contains(3L), "未命中行保留：" + ids);
        assertFalse(ids.contains(1L) || ids.contains(4L), "命中行全删：" + ids);
        assertEquals(2, engine.table(TABLE).rows.size());
    }

    @Test
    @DisplayName("未命中：WHERE 无匹配行 ⇒ affected=0 且全表保留")
    void noMatchDeletesNothing() {
        int affected = exec("DELETE FROM t_del_probe WHERE id = ?", 99L);
        assertEquals(0, affected, "零命中 ⇒ affected=0（T-196 订正：原反演语义误删其余 2 行）");
        assertEquals(3, engine.table(TABLE).rows.size(), "全表保留");
    }

    @Test
    @DisplayName("绑定参数 IN 形态：WHERE id IN (?, ?) ⇒ affected=2 且命中行被删")
    void boundParameterInListForm() {
        int affected = exec("DELETE FROM t_del_probe WHERE id IN (?, ?)", 1L, 3L);
        assertEquals(2, affected);
        List<Long> ids = ids();
        assertTrue(ids.contains(2L), "未命中行保留：" + ids);
        assertFalse(ids.contains(1L) || ids.contains(3L), "命中行全删：" + ids);
    }

    @Test
    @DisplayName("字面量 IN 形态：WHERE id IN (2) 删 id=2；恒假 IN (-1) affected=0")
    void literalInForm() {
        int affected = exec("DELETE FROM t_del_probe WHERE id IN (2)");
        assertEquals(1, affected, "字面量命中 ⇒ affected=1");
        assertFalse(ids().contains(2L), "命中行被删");
        assertEquals(2, engine.table(TABLE).rows.size());

        int none = exec("DELETE FROM t_del_probe WHERE id IN (-1)");
        assertEquals(0, none, "恒假字面量 ⇒ affected=0");
        assertEquals(2, engine.table(TABLE).rows.size(), "恒假不删行");
    }

    @Test
    @DisplayName("无条件 DELETE FROM t（无 WHERE）= 全表删除（SQL 语义）")
    void unconditionalDeleteRemovesAllRows() {
        int affected = exec("DELETE FROM t_del_probe");
        assertEquals(3, affected, "全删 ⇒ affected = 表行数");
        assertTrue(engine.table(TABLE).rows.isEmpty(), "表清空");
    }

    // ------------------------------------------------------------ 工具

    private void insert(long id, String tag) {
        exec("INSERT INTO t_del_probe (id, tag) VALUES (?, ?)", id, tag);
    }

    private int exec(String sql, Object... args) {
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("DELETE 探针执行失败：" + e.getMessage(), e);
        }
    }

    private List<Long> ids() {
        return engine.table(TABLE).rows.stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .collect(Collectors.toList());
    }
}
