package com.hedgeguard.dao.impl;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ParamDispatchDao;
import com.hedgeguard.dao.entity.ParamDispatch;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_param_dispatch} 的达梦实现（{@code DATA_MODEL.md} §2.8 {@code TB-RISK-2}；{@code T-061}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置；审计字段由 {@link AbstractDmDao} 自动填充；
 * 无 DM 方言语法（方言集中在 {@code DmDialect}）。
 */
public final class DmParamDispatchDao extends AbstractDmDao<ParamDispatch, Long>
        implements ParamDispatchDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "param_version", "dispatch_type", "sub_account_id", "batch_index", "batch_total",
            "payload_digest", "record_count", "dispatch_status", "ack_time", "ack_detail",
            "retry_count", "operator_id", "dispatch_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmParamDispatchDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_param_dispatch";
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
    protected void bindBusiness(PreparedStatement ps, ParamDispatch e, int start) throws SQLException {
        int i = start;
        ps.setLong(i++, e.paramVersion);
        bind(ps, i++, e.dispatchType);
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.batchIndex);
        bind(ps, i++, e.batchTotal);
        bind(ps, i++, e.payloadDigest);
        ps.setInt(i++, e.recordCount);
        bind(ps, i++, e.dispatchStatus);
        bind(ps, i++, e.ackTime);
        bind(ps, i++, e.ackDetail);
        ps.setInt(i++, e.retryCount);
        ps.setLong(i++, e.operatorId);
        bind(ps, i, e.dispatchTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, ParamDispatch e) throws SQLException {
        e.id = rs.getLong("id");
        e.paramVersion = rs.getLong("param_version");
        e.dispatchType = string(rs, "dispatch_type");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.batchIndex = nullableInt(rs, "batch_index");
        e.batchTotal = nullableInt(rs, "batch_total");
        e.payloadDigest = string(rs, "payload_digest");
        e.recordCount = rs.getInt("record_count");
        e.dispatchStatus = string(rs, "dispatch_status");
        e.ackTime = localDateTime(rs, "ack_time");
        e.ackDetail = string(rs, "ack_detail");
        e.retryCount = rs.getInt("retry_count");
        e.operatorId = rs.getLong("operator_id");
        e.dispatchTime = localDateTime(rs, "dispatch_time");
    }

    @Override
    protected ParamDispatch newEntity() {
        return new ParamDispatch();
    }

    @Override
    protected Long idOf(ParamDispatch entity) {
        return entity.id;
    }

    @Override
    protected void applyGeneratedId(ParamDispatch entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<ParamDispatch> findByVersion(long paramVersion, String dispatchType) {
        // COALESCE 为标准 SQL，避免依赖 DM 对 ORDER BY ... NULL 的默认排序位（换库友好）
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE param_version = ? AND dispatch_type = ?"
                + " ORDER BY COALESCE(batch_index, 0) ASC, id ASC";
        return queryList(sql, Arrays.asList(paramVersion, dispatchType), this::mapRow);
    }

    @Override
    public long maxParamVersion() {
        String sql = "SELECT COALESCE(MAX(param_version), 0) AS max_ver FROM " + tableName();
        Long v = queryOne(sql, Collections.emptyList(), rs -> rs.getLong("max_ver"));
        return v == null ? 0L : v;
    }

    @Override
    public boolean isBatchComplete(long paramVersion, String dispatchType, int batchTotal) {
        String sql = "SELECT COUNT(*) AS total_cnt,"
                + " COALESCE(SUM(CASE WHEN dispatch_status = ? THEN 1 ELSE 0 END), 0) AS ok_cnt,"
                + " COUNT(DISTINCT batch_index) AS batch_cnt"
                + " FROM " + tableName() + " WHERE param_version = ? AND dispatch_type = ?";
        int[] s = queryOne(sql, Arrays.asList("SUCCESS", paramVersion, dispatchType),
                rs -> new int[]{rs.getInt("total_cnt"), rs.getInt("ok_cnt"), rs.getInt("batch_cnt")});
        if (s == null || batchTotal < 1) {
            return false;
        }
        // 判据：本版本+类型下的记录全为 SUCCESS，且各批次下标齐备（FR-RISK-9 验收 3）
        return s[0] > 0 && s[0] == s[1] && s[2] == batchTotal;
    }

    @Override
    public List<ParamDispatch> findProblems() {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE dispatch_status IN (?, ?) ORDER BY dispatch_time DESC, id DESC";
        return queryList(sql, Arrays.asList("FAIL", "UNCONFIRMED"), this::mapRow);
    }

    @Override
    public List<ParamDispatch> findBySubAccount(int subAccountId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_id = ? ORDER BY dispatch_time DESC, id DESC";
        return queryList(sql, Collections.singletonList(subAccountId), this::mapRow);
    }

    @Override
    public int updateAck(long id, String dispatchStatus, LocalDateTime ackTime, String ackDetail,
                         int retryCount) {
        String sql = "UPDATE " + tableName()
                + " SET dispatch_status = ?, ack_time = ?, ack_detail = ?, retry_count = ?,"
                // 回执是系统动作：推进 updated_time 与 row_version，但不覆盖 updated_by（保留最后一次人工修改人）
                + " updated_time = ?, row_version = row_version + 1 WHERE id = ?";
        // 显式 List<Object>：参数类型异构（String / LocalDateTime / Integer / Long），避免 Arrays.asList 的
        // 类型推断产生非 List<Object>（LocalDateTime 与 null 由 bindArgs 统一转 setTimestamp / setNull）
        List<Object> args = new java.util.ArrayList<>(6);
        args.add(dispatchStatus);
        args.add(ackTime);
        args.add(ackDetail);
        args.add(retryCount);
        args.add(Clocks.now());
        args.add(id);
        return executeUpdateSql(sql, args);
    }
}
