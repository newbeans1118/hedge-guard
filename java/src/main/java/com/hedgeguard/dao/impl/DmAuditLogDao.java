package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.AuditLogDao;
import com.hedgeguard.dao.entity.AuditLog;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_audit_log} 的达梦实现（{@code DATA_MODEL.md} §2.9 TB-AUDIT-2；{@code FR-AUDIT-9}/10）。
 *
 * <p><b>不可篡改</b>：{@link #updateById} 显式拒绝（{@code CONTRACTS.md} §2.3）。
 * 本类是唯一出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmAuditLogDao extends AbstractDmDao<AuditLog, Long> implements AuditLogDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "audit_user_id", "action_type", "query_condition", "result_count",
            "export_file_id", "chain_integrity", "action_time", "prev_hash", "row_hash", "seq_in_chain"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmAuditLogDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_audit_log";
    }

    @Override
    protected String idColumn() {
        return "id";
    }

    @Override
    protected String[] businessColumns() {
        return COLUMNS.clone();
    }

    @Override
    protected void bindBusiness(PreparedStatement ps, AuditLog e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.auditUserId);
        bind(ps, i++, e.actionType);
        bind(ps, i++, e.queryCondition);
        bind(ps, i++, e.resultCount);
        bind(ps, i++, e.exportFileId);
        bind(ps, i++, e.chainIntegrity);
        bind(ps, i++, e.actionTime);
        bind(ps, i++, e.prevHash);
        bind(ps, i++, e.rowHash);
        bind(ps, i, e.seqInChain);
    }

    @Override
    protected void readBusiness(ResultSet rs, AuditLog e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.auditUserId = rs.getLong("audit_user_id");
        e.actionType = string(rs, "action_type");
        e.queryCondition = string(rs, "query_condition");
        e.resultCount = rs.getInt("result_count");
        e.exportFileId = string(rs, "export_file_id");
        e.chainIntegrity = string(rs, "chain_integrity");
        e.actionTime = localDateTime(rs, "action_time");
        e.prevHash = string(rs, "prev_hash");
        e.rowHash = string(rs, "row_hash");
        e.seqInChain = rs.getLong("seq_in_chain");
    }

    @Override
    protected Long idOf(AuditLog entity) {
        return entity.id;
    }

    @Override
    protected AuditLog newEntity() {
        return new AuditLog();
    }

    @Override
    protected void applyGeneratedId(AuditLog entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(AuditLog entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_audit_log 不可篡改（DATA_MODEL §2.9 / FR-AUDIT-10 链式防篡改）；"
                        + "复现=检查调用点 AuditLogDao.updateById");
    }

    @Override
    public AuditLog findBySeqInChain(long seqInChain) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE seq_in_chain = ? ORDER BY id ASC";
        return queryOne(sql, Arrays.<Object>asList(seqInChain), this::mapRow);
    }
}
