package com.hedgeguard.dao.impl;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_user_sub_account_perm} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-8}）。
 *
 * <p><b>本类是数据权限的唯一来源</b>（{@code NFR-SEC-7}）：{@link #scopeOf} 产出
 * {@link SubAccountScope} 供各模块拼 {@code sub_account_id IN (...)}；**无授权 ⇒ 空集 ⇒ 恒假条件**，
 * **绝不**退化为"不过滤"。
 *
 * <p><b>生效期过滤</b>：仅 {@code effective_from <= 今日 <= effective_to}（空值表示该侧不限）计入——
 * 已过期/未生效的授权**不得**放行数据。
 */
public final class DmUserSubAccountPermDao extends AbstractDmDao<UserSubAccountPerm, Long>
        implements UserSubAccountPermDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "user_id", "sub_account_id", "perm_type", "granted_by", "reviewer_id",
            "effective_from", "effective_to"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmUserSubAccountPermDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_user_sub_account_perm";
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
    protected void bindBusiness(PreparedStatement ps, UserSubAccountPerm e, int start)
            throws SQLException {
        int i = start;
        ps.setLong(i++, e.userId);
        ps.setInt(i++, e.subAccountId);
        bind(ps, i++, e.permType);
        ps.setLong(i++, e.grantedBy);
        bind(ps, i++, e.reviewerId);
        bind(ps, i++, e.effectiveFrom);
        bind(ps, i, e.effectiveTo);
    }

    @Override
    protected void readBusiness(ResultSet rs, UserSubAccountPerm e) throws SQLException {
        e.id = rs.getLong("id");
        e.userId = rs.getLong("user_id");
        e.subAccountId = rs.getInt("sub_account_id");
        e.permType = string(rs, "perm_type");
        e.grantedBy = rs.getLong("granted_by");
        e.reviewerId = nullableLong(rs, "reviewer_id");
        e.effectiveFrom = localDate(rs, "effective_from");
        e.effectiveTo = localDate(rs, "effective_to");
    }

    @Override
    protected Long idOf(UserSubAccountPerm entity) {
        return entity.id;
    }

    @Override
    protected UserSubAccountPerm newEntity() {
        return new UserSubAccountPerm();
    }

    @Override
    protected void applyGeneratedId(UserSubAccountPerm entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<UserSubAccountPerm> findByUser(long userId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE user_id = ? ORDER BY sub_account_id ASC";
        return queryList(sql, Collections.singletonList(userId), this::mapRow);
    }

    @Override
    public SubAccountScope scopeOf(long userId, String column) {
        LocalDate today = Clocks.today();
        List<Integer> allowed = new ArrayList<>();
        for (UserSubAccountPerm p : findByUser(userId)) {
            if (p.effectiveFrom != null && p.effectiveFrom.isAfter(today)) {
                continue;   // 尚未生效
            }
            if (p.effectiveTo != null && p.effectiveTo.isBefore(today)) {
                continue;   // 已过期
            }
            allowed.add(p.subAccountId);
        }
        // SubAccountScope.of 在 allowed 为空时产出恒假条件（column IN (-1)）——安全默认，不退化
        return SubAccountScope.of(column, allowed);
    }
}
