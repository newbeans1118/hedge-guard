package com.hedgeguard.dao;

import com.hedgeguard.dao.entity.AuditLog;

/**
 * {@code TB-AUDIT-2 t_audit_log} 数据访问（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-9}/10）。
 *
 * <p><b>不可篡改</b>：实现类覆写 {@code updateById} 显式拒绝（{@code CONTRACTS.md} §2.3）。
 */
public interface AuditLogDao extends BaseDao<AuditLog, Long> {

    /**
     * 按链内序号取唯一一行（{@code uk_audit_log_chain_seq}；防篡改校验的顺序基准）。
     *
     * @param seqInChain 链内序号
     * @return 行；无则 {@code null}
     */
    AuditLog findBySeqInChain(long seqInChain);
}
