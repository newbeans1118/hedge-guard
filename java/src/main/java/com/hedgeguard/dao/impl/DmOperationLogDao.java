package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.OperationLogDao;
import com.hedgeguard.dao.entity.OperationLog;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_operation_log} 的达梦实现（{@code DATA_MODEL.md} §2.9 TB-AUDIT-1；{@code FR-AUDIT-11}）。
 *
 * <p><b>只增不改不删</b>：{@link #updateById} 显式拒绝（{@code CONTRACTS.md} §2.3）。
 * 本类是唯一出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）。
 */
public final class DmOperationLogDao extends AbstractDmDao<OperationLog, Long>
        implements OperationLogDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "operator_id", "operation_type", "object_type", "object_id",
            "object_no", "action_result", "result_detail", "operation_time", "client_ip", "trace_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmOperationLogDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_operation_log";
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
    protected void bindBusiness(PreparedStatement ps, OperationLog e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.operationType);
        bind(ps, i++, e.objectType);
        bind(ps, i++, e.objectId);
        bind(ps, i++, e.objectNo);
        bind(ps, i++, e.actionResult);
        bind(ps, i++, e.resultDetail);
        bind(ps, i++, e.operationTime);
        bind(ps, i++, e.clientIp);
        bind(ps, i, e.traceId);
    }

    @Override
    protected void readBusiness(ResultSet rs, OperationLog e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.operatorId = rs.getLong("operator_id");
        e.operationType = string(rs, "operation_type");
        e.objectType = string(rs, "object_type");
        e.objectId = nullableLong(rs, "object_id");
        e.objectNo = string(rs, "object_no");
        e.actionResult = string(rs, "action_result");
        e.resultDetail = string(rs, "result_detail");
        e.operationTime = localDateTime(rs, "operation_time");
        e.clientIp = string(rs, "client_ip");
        e.traceId = string(rs, "trace_id");
    }

    @Override
    protected Long idOf(OperationLog entity) {
        return entity.id;
    }

    @Override
    protected OperationLog newEntity() {
        return new OperationLog();
    }

    @Override
    protected void applyGeneratedId(OperationLog entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(OperationLog entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_operation_log 只增不改不删（DATA_MODEL §2.9 / R-AUDIT-3 留痕不可篡改）；"
                        + "复现=检查调用点 OperationLogDao.updateById");
    }

    @Override
    public List<OperationLog> findByObject(String objectType, long objectId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE object_type = ? AND object_id = ?"
                + " ORDER BY operation_time ASC, id ASC";
        return queryList(sql, Arrays.<Object>asList(objectType, objectId), this::mapRow);
    }
}
