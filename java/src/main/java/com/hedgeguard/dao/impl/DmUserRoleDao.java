package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_user_role} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-4}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由 {@link AbstractDmDao} 自动填充。
 */
public final class DmUserRoleDao extends AbstractDmDao<UserRole, Long> implements UserRoleDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "user_id", "role_id", "granted_by", "granted_time", "reviewer_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmUserRoleDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_user_role";
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
    protected void bindBusiness(PreparedStatement ps, UserRole e, int start) throws SQLException {
        int i = start;
        ps.setLong(i++, e.userId);
        ps.setLong(i++, e.roleId);
        ps.setLong(i++, e.grantedBy);
        bind(ps, i++, e.grantedTime);
        bind(ps, i, e.reviewerId);
    }

    @Override
    protected void readBusiness(ResultSet rs, UserRole e) throws SQLException {
        e.id = rs.getLong("id");
        e.userId = rs.getLong("user_id");
        e.roleId = rs.getLong("role_id");
        e.grantedBy = rs.getLong("granted_by");
        e.grantedTime = localDateTime(rs, "granted_time");
        e.reviewerId = nullableLong(rs, "reviewer_id");
    }

    @Override
    protected Long idOf(UserRole entity) {
        return entity.id;
    }

    @Override
    protected UserRole newEntity() {
        return new UserRole();
    }

    @Override
    protected void applyGeneratedId(UserRole entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<UserRole> findByUser(long userId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE user_id = ? ORDER BY role_id ASC";
        return queryList(sql, Collections.singletonList(userId), this::mapRow);
    }

    @Override
    public List<UserRole> findByRole(long roleId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE role_id = ? ORDER BY user_id ASC";
        return queryList(sql, Collections.singletonList(roleId), this::mapRow);
    }
}
