package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.CustomHedgeRuleDao;
import com.hedgeguard.dao.entity.CustomHedgeRule;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_custom_hedge_rule} 的达梦实现（{@code DATA_MODEL.md} §2.5 TB-PLAN-8）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmCustomHedgeRuleDao extends AbstractDmDao<CustomHedgeRule, Long> implements CustomHedgeRuleDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "rule_code", "rule_name", "sub_account_id", "product_id", "direction", "basis",
            "ratio_cap", "priority", "effective_from", "effective_to", "status", "rule_desc",
            "approved_by", "approved_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmCustomHedgeRuleDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_custom_hedge_rule";
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
    protected void bindBusiness(PreparedStatement ps, CustomHedgeRule e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.ruleCode);
        bind(ps, i++, e.ruleName);
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.basis);
        bind(ps, i++, e.ratioCap);
        bind(ps, i++, e.priority);
        bind(ps, i++, e.effectiveFrom);
        bind(ps, i++, e.effectiveTo);
        bind(ps, i++, e.status);
        bind(ps, i++, e.ruleDesc);
        bind(ps, i++, e.approvedBy);
        bind(ps, i, e.approvedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, CustomHedgeRule e) throws SQLException {
        e.id = rs.getLong("id");
        e.ruleCode = string(rs, "rule_code");
        e.ruleName = string(rs, "rule_name");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.productId = nullableLong(rs, "product_id");
        e.direction = string(rs, "direction");
        e.basis = string(rs, "basis");
        e.ratioCap = decimal(rs, "ratio_cap");
        e.priority = nullableInt(rs, "priority");
        e.effectiveFrom = localDate(rs, "effective_from");
        e.effectiveTo = localDate(rs, "effective_to");
        e.status = string(rs, "status");
        e.ruleDesc = string(rs, "rule_desc");
        e.approvedBy = nullableLong(rs, "approved_by");
        e.approvedTime = localDateTime(rs, "approved_time");
    }

    @Override
    protected Long idOf(CustomHedgeRule entity) {
        return entity.id;
    }

    @Override
    protected CustomHedgeRule newEntity() {
        return new CustomHedgeRule();
    }

    @Override
    protected void applyGeneratedId(CustomHedgeRule entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<CustomHedgeRule> findEffective(int subAccountId, long productId, String direction, LocalDate bizDate) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE sub_account_id = ? AND (product_id IS NULL OR product_id = ?) AND (direction = ? OR direction = ?) AND status = ? AND effective_from <= ? AND (effective_to IS NULL OR effective_to >= ?)" + " ORDER BY ratio_cap ASC, priority ASC, id ASC";
        return queryList(sql, Arrays.asList(subAccountId, productId, direction, "BOTH", "EFFECTIVE", bizDate, bizDate), this::mapRow);
    }
}
