package com.hedgeguard.dao.testsupport;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * {@code BUG-006} 现货价默认价格单位验收种子（仅 {@code src/test}）。
 *
 * <p>承载 {@code SpotBug006PriceUnitTest} 的建数 SQL（{@code E5}：SQL 只允许出现在 {@code dao} 包）。
 * 与 {@link T066RiskRecomputeSeed#productUnit} 的差别：本类把 {@code spot_unit} / {@code status} /
 * {@code effective_from} 全部<b>参数化</b>——默认单位取值链（按价格日期取 / 回退当日取）、
 * 无生效换算关系、以及「spot_unit 长标签使 {@code currency + "/" + spot_unit} 超列宽」三类用例
 * 都要靠不同取值区分，硬编码不足以支撑。
 *
 * <p>纪律（沿用 {@link T133SpotGuardSeed}）：
 * <ul>
 *   <li>列一律<b>显式给全</b>——{@code InMemoryDmEngine} 不执行 DDL {@code DEFAULT}；</li>
 *   <li>{@code row_version} 显式给 {@code 1}（引擎要求 {@code >= 1}）；</li>
 *   <li>可空列走 {@code ?} + {@code null} 绑定，<b>禁用字面量 {@code NULL}</b>
 *       （引擎 {@code parseLiteral} 不识别 {@code NULL}）。</li>
 * </ul>
 */
public final class Bug006SpotPriceUnitSeed {

    private Bug006SpotPriceUnitSeed() {
    }

    /**
     * 在引擎上执行一条写入语句。
     *
     * @param engine 内存假 JDBC 引擎
     * @param sql    SQL（占位符）
     * @param args   参数
     * @throws SQLException 写入失败
     */
    public static void exec(InMemoryDmEngine engine, String sql, Object... args)
            throws SQLException {
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /**
     * 计量单位换算关系（{@code t_product_unit}；{@code uk_product_unit_eff} = 同品种同生效日唯一）。
     *
     * @param engine        内存假 JDBC 引擎
     * @param id            主键（显式；{@code IDENTITY} 在引擎内不生成）
     * @param productId     品种
     * @param spotUnit      现货计量单位（{@code TON} / 自定义标签，如 {@code SHEET_PER_ORDER}）
     * @param futuresUnit   期货交易单位
     * @param convertFactor 换算系数（{@code > 0}）
     * @param status        {@code ENABLED} / {@code DISABLED}
     * @param effectiveFrom 生效起始日
     * @throws SQLException 写入失败
     */
    public static void productUnit(InMemoryDmEngine engine, long id, long productId, String spotUnit,
                                   String futuresUnit, String convertFactor, String status,
                                   LocalDate effectiveFrom) throws SQLException {
        exec(engine, "INSERT INTO t_product_unit (id, product_id, spot_unit, futures_unit,"
                        + " convert_factor, convert_desc, status, effective_from, created_by,"
                        + " row_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 1)",
                id, productId, spotUnit, futuresUnit, new BigDecimal(convertFactor), null, status,
                effectiveFrom);
    }
}
