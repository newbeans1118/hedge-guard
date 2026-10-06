package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.AuditLogChecksumDao;
import com.hedgeguard.dao.entity.AuditLogChecksum;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_audit_log_checksum} 的达梦实现（{@code DATA_MODEL.md} §2.9 TB-AUDIT-3；{@code FR-AUDIT-10}）。
 *
 * <p><b>不可改删</b>：{@link #updateById} 显式拒绝（{@code CONTRACTS.md} §2.3）。
 * 本类是唯一出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmAuditLogChecksumDao extends AbstractDmDao<AuditLogChecksum, Long>
        implements AuditLogChecksumDao {

    /** 业务列（不含主键与通用审计字段；created_time 为通用审计字段，由基类写入）。 */
    private static final String[] COLUMNS = {
            "batch_no", "batch_start_seq", "batch_end_seq", "batch_head_hash",
            "batch_tail_hash", "batch_checksum", "record_count"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmAuditLogChecksumDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_audit_log_checksum";
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
    protected void bindBusiness(PreparedStatement ps, AuditLogChecksum e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.batchNo);
        bind(ps, i++, e.batchStartSeq);
        bind(ps, i++, e.batchEndSeq);
        bind(ps, i++, e.batchHeadHash);
        bind(ps, i++, e.batchTailHash);
        bind(ps, i++, e.batchChecksum);
        bind(ps, i, e.recordCount);
    }

    @Override
    protected void readBusiness(ResultSet rs, AuditLogChecksum e) throws SQLException {
        e.id = rs.getLong("id");
        e.batchNo = rs.getLong("batch_no");
        e.batchStartSeq = rs.getLong("batch_start_seq");
        e.batchEndSeq = rs.getLong("batch_end_seq");
        e.batchHeadHash = string(rs, "batch_head_hash");
        e.batchTailHash = string(rs, "batch_tail_hash");
        e.batchChecksum = string(rs, "batch_checksum");
        e.recordCount = rs.getInt("record_count");
    }

    @Override
    protected Long idOf(AuditLogChecksum entity) {
        return entity.id;
    }

    @Override
    protected AuditLogChecksum newEntity() {
        return new AuditLogChecksum();
    }

    @Override
    protected void applyGeneratedId(AuditLogChecksum entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(AuditLogChecksum entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_audit_log_checksum 不可改删（DATA_MODEL §2.9 / §5.3 增量校验基准）；"
                        + "复现=检查调用点 AuditLogChecksumDao.updateById");
    }

    @Override
    public AuditLogChecksum findByBatchNo(long batchNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE batch_no = ? ORDER BY id ASC";
        return queryOne(sql, Arrays.<Object>asList(batchNo), this::mapRow);
    }
}
