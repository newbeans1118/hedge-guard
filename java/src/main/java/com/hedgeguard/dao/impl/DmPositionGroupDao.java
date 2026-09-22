package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PositionGroupDao;
import com.hedgeguard.dao.entity.PositionGroup;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_position_group} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-5}）。
 *
 * <p>岗位组是<b>岗位分离互斥判定的单位</b>（{@code R-AUTH-3}）；{@code is_conflict_enforced} 恒为
 * {@code 'Y'}（数据结构层已禁止关闭互斥，{@code FR-AUTH-7} 验收 2）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。
 */
public final class DmPositionGroupDao extends AbstractDmDao<PositionGroup, Integer>
        implements PositionGroupDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "group_code", "group_name", "front_mid_back", "is_conflict_enforced", "status"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmPositionGroupDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_position_group";
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
    protected void bindBusiness(PreparedStatement ps, PositionGroup e, int start)
            throws SQLException {
        int i = start;
        bind(ps, i++, e.groupCode);
        bind(ps, i++, e.groupName);
        bind(ps, i++, e.frontMidBack);
        bind(ps, i++, e.isConflictEnforced);
        bind(ps, i, e.status);
    }

    @Override
    protected void readBusiness(ResultSet rs, PositionGroup e) throws SQLException {
        e.id = rs.getInt("id");
        e.groupCode = string(rs, "group_code");
        e.groupName = string(rs, "group_name");
        e.frontMidBack = string(rs, "front_mid_back");
        e.isConflictEnforced = string(rs, "is_conflict_enforced");
        e.status = string(rs, "status");
    }

    @Override
    protected Integer idOf(PositionGroup entity) {
        return (int) entity.id;
    }

    @Override
    protected PositionGroup newEntity() {
        return new PositionGroup();
    }

    @Override
    protected void applyGeneratedId(PositionGroup entity, long generated) {
        entity.id = generated;
    }

    @Override
    public PositionGroup findByCode(String groupCode) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE group_code = ?";
        return queryOne(sql, Collections.singletonList(groupCode), this::mapRow);
    }

    @Override
    public List<PositionGroup> findEnabled() {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE status = ? ORDER BY group_code ASC";
        return queryList(sql, Collections.singletonList("ENABLED"), this::mapRow);
    }
}
