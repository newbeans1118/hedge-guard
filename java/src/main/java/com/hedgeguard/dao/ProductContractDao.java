package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.ProductContract;
import java.util.List;

/**
 * {@code TB-PRODUCT-3 t_product_contract} 数据访问（{@code DATA_MODEL.md} §2.3）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface ProductContractDao extends BaseDao<ProductContract, Long> {

    /**
     * 按品种取全部合约关联（{@code FR-PRODUCT-9}：下单合约下拉）。
     *
     * @param productId 品种
     * @return 列表（{@code instrument_type} 升序、{@code instrument_id} 升序）
     */
    List<ProductContract> findByProduct(long productId);

    /**
     * 按合约代码查合约关联（uk_contract_instrument 全局唯一；0x1002 合约-品种校验用）。
     *
     * @param instrumentId 查询条件
     * @return 命中的实体；不存在返回 {@code null}
     */
    ProductContract findByInstrumentId(String instrumentId);
}
