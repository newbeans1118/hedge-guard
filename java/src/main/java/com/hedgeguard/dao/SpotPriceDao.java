package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.SpotPrice;

import java.time.LocalDate;

/**
 * {@code TB-SPOT-7 t_spot_price} 数据访问（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface SpotPriceDao extends BaseDao<SpotPrice, Long> {

    /**
     * 取<b>不晚于</b>指定日期的最新共享现货价（缺价沿用的唯一来源，{@code CFG-12/12b}）。
     *
     * <p>估值/展示用途可沿用并在结果上标注 {@code price_as_of}；**套保比率计算用途不得沿用**
     * （{@code R-SPOT-4}：比率用途缺价一律拒绝）。
     *
     * @param productId 品种
     * @param priceDate 截止日期（含）
     * @return 最新共享价；无则返回 {@code null}
     */
    SpotPrice findLatestOnOrBefore(long productId, java.time.LocalDate priceDate);

    /**
     * 取 GLOBAL 口径的共享现货价（uk_spot_price_global 唯一口径）。
     *
     * @param productId 查询条件
     * @param priceDate 查询条件
     * @return 命中的实体；不存在返回 {@code null}
     */
    SpotPrice findGlobalPrice(long productId, LocalDate priceDate);
}
