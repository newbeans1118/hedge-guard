package com.hedgeguard.testsupport;

import com.hedgeguard.dao.ProductUnitDao;
import com.hedgeguard.dao.entity.ProductUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存假计量单位 DAO（{@code BUG-006} 供 {@code SpotService} 导入路径的单测使用）。
 *
 * <p>只实现被用例真正驱动的 {@link ProductUnitDao#findEffective(long, LocalDate)}
 * （语义对齐 {@code DmProductUnitDao}：{@code status='ENABLED'}、{@code effective_from <= 基准日}，
 * 同日多份取 {@code effective_from} 最大者）；其余通用方法经 {@link UnsupportedBaseDao}
 * <b>显式失败</b>，避免桩静默返回 {@code null} 把缺陷掩盖成通过。
 *
 * <p><b>局限（必读）</b>：本类不执行任何 DDL 约束 ⇒ 不能作为 {@code price_unit NOT NULL}
 * 的验收依据（{@code BUG-006} 正是「假 DAO 掩盖真实 DDL 约束」形成的假绿）。
 * 落库侧的真实验收见 {@code SpotBug006PriceUnitTest}（真实 {@code Dm*Dao} + 内存引擎真实 DDL）。
 */
public final class FakeProductUnitDao extends UnsupportedBaseDao<ProductUnit, Long>
        implements ProductUnitDao {

    private final List<ProductUnit> rows = new ArrayList<>();

    private long nextId = 1;

    /**
     * 造一份生效换算关系（{@code status='ENABLED'}，{@code futures_unit='LOT'}）。
     *
     * @param productId     品种
     * @param spotUnit      现货计量单位（如 {@code TON}）
     * @param effectiveFrom 生效起始日
     * @return 本对象（链式构造）
     */
    public FakeProductUnitDao withUnit(long productId, String spotUnit, LocalDate effectiveFrom) {
        ProductUnit unit = new ProductUnit();
        unit.id = nextId++;
        unit.productId = productId;
        unit.spotUnit = spotUnit;
        unit.futuresUnit = "LOT";
        unit.convertFactor = BigDecimal.ONE;
        unit.status = "ENABLED";
        unit.effectiveFrom = effectiveFrom;
        rows.add(unit);
        return this;
    }

    @Override
    public ProductUnit findEffective(long productId, LocalDate bizDate) {
        ProductUnit best = null;
        for (ProductUnit row : rows) {
            if (row.productId == null || row.productId != productId
                    || !"ENABLED".equals(row.status)) {
                continue;
            }
            if (row.effectiveFrom == null || row.effectiveFrom.isAfter(bizDate)) {
                continue;
            }
            if (best == null || row.effectiveFrom.isAfter(best.effectiveFrom)) {
                best = row;
            }
        }
        return best;
    }
}
