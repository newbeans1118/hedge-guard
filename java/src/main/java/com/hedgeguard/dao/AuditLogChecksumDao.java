package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.AuditLogChecksum;

/**
 * {@code TB-AUDIT-3 t_audit_log_checksum} 数据访问（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-10}）。
 *
 * <p><b>不可改删</b>：实现类覆写 {@code updateById} 显式拒绝（{@code CONTRACTS.md} §2.3）。
 */
public interface AuditLogChecksumDao extends BaseDao<AuditLogChecksum, Long> {

    /**
     * 按批次号取唯一一行（{@code uk_audit_log_checksum_batch}）。
     *
     * @param batchNo 批次号
     * @return 行；无则 {@code null}
     */
    AuditLogChecksum findByBatchNo(long batchNo);
}
