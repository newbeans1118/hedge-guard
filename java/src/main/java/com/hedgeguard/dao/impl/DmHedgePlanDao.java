package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_hedge_plan} 的达梦实现（{@code DATA_MODEL.md} §2.5 TB-PLAN-1）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmHedgePlanDao extends AbstractDmDao<HedgePlan, Long> implements HedgePlanDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "plan_no", "plan_type", "product_id", "hedge_direction", "plan_qty",
            "unit", "term_start_date", "term_end_date", "hedge_ratio", "ratio_method",
            "calc_base_date", "status", "executed_qty", "ordered_qty", "applicant_id",
            "current_doc_version", "freeze_reason", "terminate_reason", "extend_basis",
            "quota_occupied"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmHedgePlanDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_hedge_plan";
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
    protected void bindBusiness(PreparedStatement ps, HedgePlan e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.planNo);
        bind(ps, i++, e.planType);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.hedgeDirection);
        bind(ps, i++, e.planQty);
        bind(ps, i++, e.unit);
        bind(ps, i++, e.termStartDate);
        bind(ps, i++, e.termEndDate);
        bind(ps, i++, e.hedgeRatio);
        bind(ps, i++, e.ratioMethod);
        bind(ps, i++, e.calcBaseDate);
        bind(ps, i++, e.status);
        bind(ps, i++, e.executedQty);
        bind(ps, i++, e.orderedQty);
        bind(ps, i++, e.applicantId);
        bind(ps, i++, e.currentDocVersion);
        bind(ps, i++, e.freezeReason);
        bind(ps, i++, e.terminateReason);
        bind(ps, i++, e.extendBasis);
        bind(ps, i, e.quotaOccupied);
    }

    @Override
    protected void readBusiness(ResultSet rs, HedgePlan e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.planNo = string(rs, "plan_no");
        e.planType = string(rs, "plan_type");
        e.productId = nullableLong(rs, "product_id");
        e.hedgeDirection = string(rs, "hedge_direction");
        e.planQty = decimal(rs, "plan_qty");
        e.unit = string(rs, "unit");
        e.termStartDate = localDate(rs, "term_start_date");
        e.termEndDate = localDate(rs, "term_end_date");
        e.hedgeRatio = decimal(rs, "hedge_ratio");
        e.ratioMethod = string(rs, "ratio_method");
        e.calcBaseDate = localDate(rs, "calc_base_date");
        e.status = string(rs, "status");
        e.executedQty = decimal(rs, "executed_qty");
        e.orderedQty = decimal(rs, "ordered_qty");
        e.applicantId = nullableLong(rs, "applicant_id");
        e.currentDocVersion = nullableInt(rs, "current_doc_version");
        e.freezeReason = string(rs, "freeze_reason");
        e.terminateReason = string(rs, "terminate_reason");
        e.extendBasis = string(rs, "extend_basis");
        e.quotaOccupied = string(rs, "quota_occupied");
    }

    @Override
    protected Long idOf(HedgePlan entity) {
        return entity.id;
    }

    @Override
    protected HedgePlan newEntity() {
        return new HedgePlan();
    }

    @Override
    protected void applyGeneratedId(HedgePlan entity, long generated) {
        entity.id = generated;
    }

    @Override
    public HedgePlan findByPlanNo(String planNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE plan_no = ?";
        return queryOne(sql, Arrays.asList(planNo), this::mapRow);
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接复用 {@link AbstractDmDao#findPageScoped}（{@code NFR-SEC-7} 统一注入点）；
     * 本类不新增 SQL 分支。
     */
    @Override
    public PageResult<HedgePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                String extraWhere, List<Object> extraArgs) {
        return super.findPageScoped(query, scope, extraWhere, extraArgs);
    }

    /**
     * {@inheritDoc}
     *
     * <p>条件形态 {@code WHERE id = ? AND <scope.sql()>}；scope 为空集时其 {@code sql()} 为恒假条件
     * （{@code sub_account_id IN (-1)}），与主键条件并列后必然无命中——即"不可见 = 不存在"。
     */
    @Override
    public HedgePlan findByIdScoped(long id, SubAccountScope scope) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE " + idColumn() + " = ? AND " + scope.sql();
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.addAll(scope.params());
        return queryOne(sql, args, this::mapRow);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>物理删除仅限例外态</b>（{@code DATA_MODEL.md} §0.3）：{@code t_hedge_plan} 仅 DRAFT 态套保计划 可删；
     * 由服务层在删除前校验状态，本层不做状态判断（保持 SQL 单一职责）。
     */
    @Override
    public int deleteById(Long id) {
        String sql = "DELETE FROM " + tableName() + " WHERE " + idColumn() + " = ?";
        return executeUpdateSql(sql, java.util.Collections.singletonList(id));
    }
}
