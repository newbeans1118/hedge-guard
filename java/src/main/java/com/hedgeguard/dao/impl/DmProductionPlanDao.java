package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.ProductionPlanDao;
import com.hedgeguard.dao.entity.ProductionPlan;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code t_production_plan} 的达梦实现（{@code DATA_MODEL.md} §2.4 TB-SPOT-3）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmProductionPlanDao extends AbstractDmDao<ProductionPlan, Long> implements ProductionPlanDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "plan_no", "plan_period_start", "plan_period_end", "status",
            "overlap_flag", "closed_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmProductionPlanDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_production_plan";
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
    protected void bindBusiness(PreparedStatement ps, ProductionPlan e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.planNo);
        bind(ps, i++, e.planPeriodStart);
        bind(ps, i++, e.planPeriodEnd);
        bind(ps, i++, e.status);
        bind(ps, i++, e.overlapFlag);
        bind(ps, i, e.closedTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, ProductionPlan e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.planNo = string(rs, "plan_no");
        e.planPeriodStart = localDate(rs, "plan_period_start");
        e.planPeriodEnd = localDate(rs, "plan_period_end");
        e.status = string(rs, "status");
        e.overlapFlag = string(rs, "overlap_flag");
        e.closedTime = localDateTime(rs, "closed_time");
    }

    @Override
    protected Long idOf(ProductionPlan entity) {
        return entity.id;
    }

    @Override
    protected ProductionPlan newEntity() {
        return new ProductionPlan();
    }

    @Override
    protected void applyGeneratedId(ProductionPlan entity, long generated) {
        entity.id = generated;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>物理删除仅限例外态</b>（{@code DATA_MODEL.md} §0.3）：{@code t_production_plan} 仅 DRAFT 态生产计划 可删；
     * 由服务层在删除前校验状态，本层不做状态判断（保持 SQL 单一职责）。
     */
    @Override
    public int deleteById(Long id) {
        String sql = "DELETE FROM " + tableName() + " WHERE " + idColumn() + " = ?";
        return executeUpdateSql(sql, java.util.Collections.singletonList(id));
    }
}
