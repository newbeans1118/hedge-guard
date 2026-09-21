package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SubAccountDao;
import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_sub_account} 的达梦实现（{@code DATA_MODEL.md} §2.1 TB-CONFIG-1）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置。审计字段由 {@link AbstractDmDao} 自动处理。
 */
public final class DmSubAccountDao extends AbstractDmDao<SubAccount, Integer> implements SubAccountDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_code", "sub_account_name", "business_line_desc", "status", "category",
            "category_approved_by", "is_default", "initial_fund", "ledger_start_date", "remark",
            "disabled_reason", "disable_precheck_json", "archive_export_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSubAccountDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_sub_account";
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
    protected void bindBusiness(PreparedStatement ps, SubAccount e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountCode);
        bind(ps, i++, e.subAccountName);
        bind(ps, i++, e.businessLineDesc);
        bind(ps, i++, e.status);
        bind(ps, i++, e.category);
        bind(ps, i++, e.categoryApprovedBy);
        bind(ps, i++, e.isDefault);
        bind(ps, i++, e.initialFund);
        bind(ps, i++, e.ledgerStartDate);
        bind(ps, i++, e.remark);
        bind(ps, i++, e.disabledReason);
        bindClob(ps, i++, e.disablePrecheckJson);
        bind(ps, i, e.archiveExportId);
    }

    @Override
    protected void readBusiness(ResultSet rs, SubAccount e) throws SQLException {
        e.id = rs.getInt("id");
        e.subAccountCode = string(rs, "sub_account_code");
        e.subAccountName = string(rs, "sub_account_name");
        e.businessLineDesc = string(rs, "business_line_desc");
        e.status = string(rs, "status");
        e.category = string(rs, "category");
        e.categoryApprovedBy = nullableLong(rs, "category_approved_by");
        e.isDefault = string(rs, "is_default");
        e.initialFund = decimal(rs, "initial_fund");
        e.ledgerStartDate = localDate(rs, "ledger_start_date");
        e.remark = string(rs, "remark");
        e.disabledReason = string(rs, "disabled_reason");
        e.disablePrecheckJson = clob(rs, "disable_precheck_json");
        e.archiveExportId = string(rs, "archive_export_id");
    }

    @Override
    protected Integer idOf(SubAccount entity) {
        return entity.id;
    }

    @Override
    protected SubAccount newEntity() {
        return new SubAccount();
    }

    @Override
    protected void applyGeneratedId(SubAccount entity, long generated) {
        entity.id = (int) generated;
    }

    @Override
    public SubAccount findByCode(String code) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE sub_account_code = ?";
        return queryOne(sql, Collections.singletonList(code), this::mapRow);
    }

    @Override
    public List<SubAccount> findEnabled() {
        // 保留系统行 id = 0 的 status = 'DISABLED'，天然被本查询排除（DATA_MODEL.md §2.1 备注）
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE status = ? ORDER BY sub_account_code ASC";
        return queryList(sql, Collections.singletonList("ENABLED"), this::mapRow);
    }
}
