package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.RolePermissionDao;
import com.hedgeguard.dao.entity.RolePermission;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_role_permission} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-3}）。
 *
 * <p>本表是 {@code ARCHITECTURE.md} §8.2③「两阶段口径」中 <b>M2 = 功能权限</b>判据的落点
 * （{@code AuthService.hasFunctionPermission}）；{@code perm_category} 取值域
 * {@code FUNCTION} / {@code DATA} / {@code QUOTA} 由 {@code ck_role_perm_category} 强制。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmRolePermissionDao extends AbstractDmDao<RolePermission, Long>
        implements RolePermissionDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {"role_id", "perm_code", "perm_category"};

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmRolePermissionDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_role_permission";
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
    protected void bindBusiness(PreparedStatement ps, RolePermission e, int start)
            throws SQLException {
        int i = start;
        ps.setLong(i++, e.roleId);
        bind(ps, i++, e.permCode);
        bind(ps, i, e.permCategory);
    }

    @Override
    protected void readBusiness(ResultSet rs, RolePermission e) throws SQLException {
        e.id = rs.getLong("id");
        e.roleId = rs.getLong("role_id");
        e.permCode = string(rs, "perm_code");
        e.permCategory = string(rs, "perm_category");
    }

    @Override
    protected Long idOf(RolePermission entity) {
        return entity.id;
    }

    @Override
    protected RolePermission newEntity() {
        return new RolePermission();
    }

    @Override
    protected void applyGeneratedId(RolePermission entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<RolePermission> findByRole(long roleId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE role_id = ? ORDER BY perm_code ASC";
        return queryList(sql, Collections.singletonList(roleId), this::mapRow);
    }

    @Override
    public List<RolePermission> findByPermCode(String permCode) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE perm_code = ? ORDER BY role_id ASC";
        return queryList(sql, Collections.singletonList(permCode), this::mapRow);
    }
}
