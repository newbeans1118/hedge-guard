package com.hedgeguard.bridge;

import com.hedgeguard.service.RiskThresholdSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hgcp.v1.Hgcp;

/**
 * {@code CMD_LOAD_RISK_PARAMS}（0x0111）编码守卫：启动期全量预加载载荷。
 *
 * <p>立意 = <b>行为断言</b>：重解实际下发字节，逐字段核对版本 / 分批 / 阈值 / 品种。
 * 空载荷会使 C++ 侧 {@code RiskSnapshot.record_count == 0} ⇒ {@code ready()} 恒假、
 * 永不迁移 READY（实测结论），故品种条目是本指令的承重字段。
 */
class ParamChangeEncoderLoadTest {

    /** 与生产装配同形态：16 阈值基线 + 单批 0/1 + 1 条真实品种。 */
    private static ParamChange loadChange() {
        ParamChange c = new ParamChange(ParamChange.Type.CMD_LOAD_RISK_PARAMS, 7L, 0L);
        c.with("batch_index", "0").with("batch_total", "1");
        for (RiskThresholdSpec spec : RiskThresholdSpec.values()) {
            c.with(spec.wireField(), spec.defaultValue());
        }
        c.with("entries[0].product_id", "42")
                .with("entries[0].product_code", "CU")
                .with("entries[0].category", "COMMODITY")
                .with("entries[0].status", "ENABLED")
                .with("entries[0].unit_factor", "1.0000");
        return c;
    }

    @Test
    @DisplayName("LOAD 编码：版本/分批/阈值/品种逐字段可解析")
    void encodesLoadRiskParams() throws Exception {
        byte[] wire = ParamChangeEncoder.encode(loadChange());
        Hgcp.CmdLoadRiskParams m = Hgcp.CmdLoadRiskParams.parseFrom(wire);

        assertEquals(7L, m.getParamVersion(), "param_version 须取 ParamChange.paramVersion");
        assertEquals(0, m.getBatchIndex(), "单批会话 batch_index = 0");
        assertEquals(1, m.getBatchTotal(), "单批会话 batch_total = 1");
        assertEquals(1, m.getVarietiesCount(), "品种条目是快照非空的承重字段");

        Hgcp.VarietySpec v = m.getVarieties(0);
        assertEquals(42, v.getProductId());
        assertEquals("CU", v.getProductCode());
        assertEquals(Hgcp.ProductCategory.PRODUCT_CATEGORY_COMMODITY, v.getCategory());
        assertEquals(Hgcp.ProductStatus.PRODUCT_STATUS_ENABLED, v.getStatus());
        assertEquals("1.0000", v.getUnitFactor());

        assertTrue(m.hasThresholds(), "G-5 嵌套复用 RiskThresholds");
        assertFalse(m.getThresholds().getScaleRatioCommodity().isEmpty(),
                "阈值基线须随全量快照一并下发");
        assertEquals(0, m.getWhitelistCount(), "其余 repeated 字段本期留空由增量补齐");
    }

    @Test
    @DisplayName("阈值缺省值形态可编码（16 键与 §4.2.3 冻结字段逐一对上）")
    void thresholdDefaultsAreEncodable() {
        assertEquals(16, RiskThresholdSpec.values().length,
                "RiskThresholdSpec 须覆盖 §4.2.3 第 17 行的 16 个阈值字段");
        byte[] wire = ParamChangeEncoder.encode(loadChange());
        assertTrue(wire.length > 0);
    }

    @Test
    @DisplayName("重构后 CMD_UPDATE_RISK_THRESHOLD 行为不变（回归）")
    void updateRiskThresholdRegression() throws Exception {
        ParamChange c = new ParamChange(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, 9L, 0L);
        for (RiskThresholdSpec spec : RiskThresholdSpec.values()) {
            c.with(spec.wireField(), spec.defaultValue());
        }
        Hgcp.CmdUpdateRiskThreshold m =
                Hgcp.CmdUpdateRiskThreshold.parseFrom(ParamChangeEncoder.encode(c));
        assertEquals(9L, m.getParamVersion());
        assertTrue(m.hasThresholds());
    }

    @Test
    @DisplayName("负控：缺 batch_total 必抛（不得静默丢弃）")
    void missingBatchTotalRejected() {
        ParamChange c = loadChange();
        c.payload.remove("batch_total");
        assertThrows(IllegalArgumentException.class, () -> ParamChangeEncoder.encode(c));
    }

    @Test
    @DisplayName("负控：未知顶层键必抛（HGC-1.6）")
    void unknownTopLevelKeyRejected() {
        ParamChange c = loadChange();
        c.with("rogue_key", "1");
        assertThrows(IllegalArgumentException.class, () -> ParamChangeEncoder.encode(c));
    }

    @Test
    @DisplayName("负控：品种条目含协议外键名必抛")
    void unknownEntryKeyRejected() {
        ParamChange c = loadChange();
        c.with("entries[0].rogue", "x");
        assertThrows(IllegalArgumentException.class, () -> ParamChangeEncoder.encode(c));
    }

    @Test
    @DisplayName("空载荷可编码但品种数为 0（提示：C++ 侧将永不 READY）")
    void emptyVarietySnapshotIsEncodableButNotReady() throws Exception {
        ParamChange c = loadChange();
        c.payload.keySet().removeIf(k -> k.startsWith("entries["));
        Hgcp.CmdLoadRiskParams m =
                Hgcp.CmdLoadRiskParams.parseFrom(ParamChangeEncoder.encode(c));
        assertEquals(0, m.getVarietiesCount(),
                "空快照不使 RiskSnapshot.ready() 成立；生产装配须读真实品种");
    }
}
