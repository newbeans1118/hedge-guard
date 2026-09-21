package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ExposureDetail;
import java.util.List;

/**
 * {@code TB-SPOT-9 t_exposure_detail} 数据访问（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface ExposureDetailDao extends BaseDao<ExposureDetail, Long> {

    /**
     * 按敞口取构成明细（{@code FR-SPOT-15} 验收 1；分量与合计互校）。
     *
     * @param exposureId 敞口
     * @return 明细列表
     */
    List<ExposureDetail> findByExposure(long exposureId);

    /**
     * 反查"某原始单据贡献了哪些敞口"（{@code FR-SPOT-3} 作废前置校验、
     * {@code FR-SPOT-16} 归属变更校验）。
     *
     * @param sourceType 来源单据类型
     * @param sourceId   来源单据 ID
     * @return 明细列表
     */
    List<ExposureDetail> findBySource(String sourceType, long sourceId);
}
