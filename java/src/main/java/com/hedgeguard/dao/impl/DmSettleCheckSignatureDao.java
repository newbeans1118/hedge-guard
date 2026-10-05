package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleCheckSignatureDao;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.tx.TxTemplate;

import java.time.LocalDateTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_check_signature} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-15）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleCheckSignatureDao extends AbstractDmDao<SettleCheckSignature, Long> implements SettleCheckSignatureDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "check_task_id", "sign_role", "signer_id", "signer_role_code", "sign_time", "sign_ip", "snapshot_hash", "sign_result", "sign_opinion", "proxy_rejected", "manage_role_trade_blocked"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleCheckSignatureDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_check_signature";
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
    protected void bindBusiness(PreparedStatement ps, SettleCheckSignature e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.checkTaskId);
        bind(ps, i++, e.signRole);
        bind(ps, i++, e.signerId);
        bind(ps, i++, e.signerRoleCode);
        bind(ps, i++, e.signTime);
        bind(ps, i++, e.signIp);
        bind(ps, i++, e.snapshotHash);
        bind(ps, i++, e.signResult);
        bind(ps, i++, e.signOpinion);
        bind(ps, i++, e.proxyRejected);
        bind(ps, i++, e.manageRoleTradeBlocked);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleCheckSignature e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.checkTaskId = nullableLong(rs, "check_task_id");
        e.signRole = string(rs, "sign_role");
        e.signerId = nullableLong(rs, "signer_id");
        e.signerRoleCode = string(rs, "signer_role_code");
        e.signTime = localDateTime(rs, "sign_time");
        e.signIp = string(rs, "sign_ip");
        e.snapshotHash = string(rs, "snapshot_hash");
        e.signResult = string(rs, "sign_result");
        e.signOpinion = string(rs, "sign_opinion");
        e.proxyRejected = string(rs, "proxy_rejected");
        e.manageRoleTradeBlocked = string(rs, "manage_role_trade_blocked");
    }

    @Override
    protected Long idOf(SettleCheckSignature entity) {
        return entity.id;
    }

    @Override
    protected SettleCheckSignature newEntity() {
        return new SettleCheckSignature();
    }

    @Override
    protected void applyGeneratedId(SettleCheckSignature entity, long generated) {
        entity.id = generated;
    }

    @Override
    public SettleCheckSignature findByTaskAndRole(long checkTaskId, String signRole) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE check_task_id = ? AND sign_role = ?";
        return queryOne(sql, Arrays.asList(checkTaskId, signRole), this::mapRow);
    }

    @Override
    public List<SettleCheckSignature> findByTask(long checkTaskId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE check_task_id = ? ORDER BY id ASC";
        return queryList(sql, Arrays.asList(checkTaskId), this::mapRow);
    }
}
