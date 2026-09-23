package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_user_sub_account_perm} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-8}）。
 *
 * <p><b>数据权限的取数入口为 {@link com.hedgeguard.service.AuthService#visibleSubAccountIds(long)}</b>
 * （含 {@code perm_type} 语义；{@code ARCHITECTURE.md} §8.2②，v1.5.8 机制订正）：由调用方判定返回集合
 * 是否含哨兵 {@link SubAccountScope#ALL_ACCOUNT_SENTINEL}（{@code CROSS_READONLY} ⇒
 * <b>不构造 scope、不加过滤</b>），<b>空集 ⇒ 恒假条件</b>（{@code NFR-SEC-7} 安全默认，
 * <b>绝不</b>退化为"不过滤"）。本类只负责 {@code t_user_sub_account_perm} 的<b>原始行</b>读取
 * （{@link #findByUser}）。
 *
 * <p>⚠️ <b>历史入口 {@code scopeOf} 已删除</b>（{@code T-099}，2026-09-21）：它<b>不读</b>
 * {@code perm_type}，<b>不得</b>作取数入口；因「以接口类型注入调用」对 {@code @Deprecated} 零防护，故删除而非弃用。
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
    public List<Long> findUserIdsBySubAccount(int subAccountId) {
        // 反向索引：按 sub_account_id 枚举授权主体，供用户列表筛选下推 id IN (...)。
        String sql = "SELECT user_id FROM " + tableName()
                + " WHERE sub_account_id = ? ORDER BY user_id ASC";
        List<Long> rows = queryList(sql, Collections.singletonList(subAccountId),
                rs -> rs.getLong("user_id"));
        // 同一用户可能有多行（历史/多类型授权）⇒ 去重后保持一致顺序。
        List<Long> out = new ArrayList<>();
        for (Long id : rows) {
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
