package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.DictItemDao;
import com.hedgeguard.dao.entity.DictItem;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_dict_item} 的达梦实现（{@code DATA_MODEL.md} §2.1 {@code TB-CONFIG-4}；{@code T-109} 补位）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 *
 * <p>列清单与建表脚本 {@code docs/DEPLOY/schema/m1_config_auth.sql} 逐列一致
 * （由 {@code DaoColumnAlignmentTest} 离线校验）。
 */
public final class DmDictItemDao extends AbstractDmDao<DictItem, Long> implements DictItemDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "dict_type", "item_code", "item_name", "item_desc", "sort_no", "status", "is_system"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmDictItemDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_dict_item";
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
    protected void bindBusiness(PreparedStatement ps, DictItem e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.dictType);
        bind(ps, i++, e.itemCode);
        bind(ps, i++, e.itemName);
        bind(ps, i++, e.itemDesc);
        ps.setInt(i++, e.sortNo);
        bind(ps, i++, e.status);
        bind(ps, i++, e.isSystem);
    }

    @Override
    protected void readBusiness(ResultSet rs, DictItem e) throws SQLException {
        e.id = rs.getLong("id");
        e.dictType = string(rs, "dict_type");
        e.itemCode = string(rs, "item_code");
        e.itemName = string(rs, "item_name");
        e.itemDesc = string(rs, "item_desc");
        e.sortNo = rs.getInt("sort_no");
        e.status = string(rs, "status");
        e.isSystem = string(rs, "is_system");
    }

    @Override
    protected Long idOf(DictItem entity) {
        return entity.id;
    }

    @Override
    protected DictItem newEntity() {
        return new DictItem();
    }

    @Override
    protected void applyGeneratedId(DictItem entity, long generated) {
        entity.id = generated;
    }

    @Override
    public DictItem findByTypeAndCode(String dictType, String itemCode) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE dict_type = ? AND item_code = ?";
        return queryOne(sql, Arrays.asList(dictType, itemCode), this::mapRow);
    }

    @Override
    public List<DictItem> findEnabledByType(String dictType) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE dict_type = ? AND status = ? ORDER BY sort_no ASC, id ASC";
        return queryList(sql, Arrays.asList(dictType, "ENABLED"), this::mapRow);
    }

    @Override
    public int deleteById(Long id) {
        // 物理删除仅供"未被引用"的字典项；引用校验在服务层完成（FR-CONFIG-14 验收 2）
        return executeUpdateSql("DELETE FROM " + tableName() + " WHERE id = ?",
                Collections.singletonList(id));
    }
}
