package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.WipRecord;

/**
 * {@code TB-SPOT-5 t_wip_record} 数据访问（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>接口签名冻结于 {@code java/CONTRACTS.md}（{@code HGC-1.0}）：通用增删改查经 {@link BaseDao}；
 * 子账户数据权限由服务层构造 {@link SubAccountScope} 后注入（{@code NFR-SEC-7}）。
 */
public interface WipRecordDao extends BaseDao<WipRecord, Long> {
}
