package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PositionConflictDao;
import com.hedgeguard.dao.entity.PositionConflict;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code t_position_conflict} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-6}）。
 *
 * <p>矩阵按单向存储（仅存 {@code a < b}），故 {@link #findConflictsOf} 必须<b>双向匹配</b>
 * （{@code group_a_id = ? OR group_b_id = ?}）；这是{@code R-AUTH-3} 岗位互斥校验的取数入口。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由 {@link AbstractDmDao} 自动填充。
 */
public final class DmPositionConflictDao extends AbstractDmDao<PositionConflict, Long>
        implements PositionConflictDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "group_a_id", "group_b_id", "conflict_reason", "is_mandatory", "reviewer_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmPositionConflictDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_position_conflict";
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
    protected void bindBusiness(PreparedStatement ps, PositionConflict e, int start) throws SQLException {
        int i = start;
        ps.setInt(i++, e.groupAId);
        ps.setInt(i++, e.groupBId);
        bind(ps, i++, e.conflictReason);
        bind(ps, i++, e.isMandatory);
        bind(ps, i, e.reviewerId);
    }

    @Override
    protected void readBusiness(ResultSet rs, PositionConflict e) throws SQLException {
        e.id = rs.getLong("id");
        e.groupAId = rs.getInt("group_a_id");
        e.groupBId = rs.getInt("group_b_id");
        e.conflictReason = string(rs, "conflict_reason");
        e.isMandatory = string(rs, "is_mandatory");
        e.reviewerId = nullableLong(rs, "reviewer_id");
    }

    @Override
    protected Long idOf(PositionConflict entity) {
        return entity.id;
    }

    @Override
    protected PositionConflict newEntity() {
        return new PositionConflict();
    }

    @Override
    protected void applyGeneratedId(PositionConflict entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<PositionConflict> findConflictsOf(int groupId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE group_a_id = ? OR group_b_id = ?"
                + " ORDER BY group_a_id ASC, group_b_id ASC";
        return queryList(sql, args(groupId, groupId), this::mapRow);
    }
}
