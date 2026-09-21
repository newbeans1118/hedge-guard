package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.AccountSubjectDao;
import com.hedgeguard.dao.entity.AccountSubject;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_account_subject} 的达梦实现（{@code DATA_MODEL.md} §2.1 {@code TB-CONFIG-5}；{@code T-109} 补位）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 *
 * <p>列清单与建表脚本 {@code docs/DEPLOY/schema/m1_config_auth.sql} 逐列一致
 * （由 {@code DaoColumnAlignmentTest} 离线校验）。
 */
public final class DmAccountSubjectDao extends AbstractDmDao<AccountSubject, Long>
        implements AccountSubjectDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "subject_code", "subject_name", "parent_code", "subject_level", "direction",
            "biz_scene", "is_template_default", "editable_by_enterprise", "status"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmAccountSubjectDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_account_subject";
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
    protected void bindBusiness(PreparedStatement ps, AccountSubject e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subjectCode);
        bind(ps, i++, e.subjectName);
        bind(ps, i++, e.parentCode);
        ps.setInt(i++, e.subjectLevel);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.bizScene);
        bind(ps, i++, e.isTemplateDefault);
        bind(ps, i++, e.editableByEnterprise);
        bind(ps, i, e.status);
    }

    @Override
    protected void readBusiness(ResultSet rs, AccountSubject e) throws SQLException {
        e.id = rs.getLong("id");
        e.subjectCode = string(rs, "subject_code");
        e.subjectName = string(rs, "subject_name");
        e.parentCode = string(rs, "parent_code");
        e.subjectLevel = rs.getInt("subject_level");
        e.direction = string(rs, "direction");
        e.bizScene = string(rs, "biz_scene");
        e.isTemplateDefault = string(rs, "is_template_default");
        e.editableByEnterprise = string(rs, "editable_by_enterprise");
        e.status = string(rs, "status");
    }

    @Override
    protected Long idOf(AccountSubject entity) {
        return entity.id;
    }

    @Override
    protected AccountSubject newEntity() {
        return new AccountSubject();
    }

    @Override
    protected void applyGeneratedId(AccountSubject entity, long generated) {
        entity.id = generated;
    }

    @Override
    public AccountSubject findByCode(String subjectCode) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE subject_code = ?";
        return queryOne(sql, Collections.singletonList(subjectCode), this::mapRow);
    }

    @Override
    public List<AccountSubject> findTemplateDefaults() {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE is_template_default = ? AND status = ? ORDER BY subject_code ASC, id ASC";
        return queryList(sql, Arrays.asList("Y", "ENABLED"), this::mapRow);
    }

    @Override
    public List<AccountSubject> findEnabledByScene(String bizScene) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE biz_scene = ? AND status = ? ORDER BY subject_code ASC, id ASC";
        return queryList(sql, Arrays.asList(bizScene, "ENABLED"), this::mapRow);
    }
}
