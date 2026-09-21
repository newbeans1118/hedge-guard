package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_role} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-2}）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由 {@link AbstractDmDao} 自动填充。
 */
public final class DmRoleDao extends AbstractDmDao<Role, Long> implements RoleDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "role_code", "role_name", "position_group_id", "is_preset", "is_business_role",
            "has_trading_operation", "status"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmRoleDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_role";
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
    protected void bindBusiness(PreparedStatement ps, Role e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.roleCode);
        bind(ps, i++, e.roleName);
        ps.setInt(i++, e.positionGroupId);
        bind(ps, i++, e.isPreset);
        bind(ps, i++, e.isBusinessRole);
        bind(ps, i++, e.hasTradingOperation);
        bind(ps, i, e.status);
    }

    @Override
    protected void readBusiness(ResultSet rs, Role e) throws SQLException {
        e.id = rs.getLong("id");
        e.roleCode = string(rs, "role_code");
        e.roleName = string(rs, "role_name");
        e.positionGroupId = rs.getInt("position_group_id");
        e.isPreset = string(rs, "is_preset");
        e.isBusinessRole = string(rs, "is_business_role");
        e.hasTradingOperation = string(rs, "has_trading_operation");
        e.status = string(rs, "status");
    }

    @Override
    protected Long idOf(Role entity) {
        return entity.id;
    }

    @Override
    protected Role newEntity() {
        return new Role();
    }

    @Override
    protected void applyGeneratedId(Role entity, long generated) {
        entity.id = generated;
    }

    @Override
    public Role findByCode(String roleCode) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE role_code = ?";
        return queryOne(sql, Collections.singletonList(roleCode), this::mapRow);
    }

    @Override
    public List<Role> findByPositionGroup(int positionGroupId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE position_group_id = ? ORDER BY role_code ASC";
        return queryList(sql, Collections.singletonList(positionGroupId), this::mapRow);
    }

    @Override
    public List<Role> findTradingRoles() {
        // 「是否可操盘」是岗位分离的判据来源（R-AUTH-13 的 is_management_role 禁止操盘，反向据此判定）
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE has_trading_operation = ? ORDER BY role_code ASC";
        return queryList(sql, Collections.singletonList("Y"), this::mapRow);
    }
}
