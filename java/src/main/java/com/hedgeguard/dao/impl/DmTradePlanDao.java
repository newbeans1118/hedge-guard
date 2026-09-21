package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.entity.TradePlan;
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
 * {@code t_trade_plan} 的达梦实现（{@code DATA_MODEL.md} §2.5 TB-PLAN-6）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmTradePlanDao extends AbstractDmDao<TradePlan, Long> implements TradePlanDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "trade_plan_no", "hedge_plan_id", "exposure_id", "product_id",
            "instrument_id", "instrument_type", "direction", "plan_qty", "executed_qty",
            "ordered_qty", "exec_deadline", "status", "strategy_id"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmTradePlanDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_trade_plan";
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
    protected void bindBusiness(PreparedStatement ps, TradePlan e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.tradePlanNo);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.exposureId);
        bind(ps, i++, e.productId);
        bind(ps, i++, e.instrumentId);
        bind(ps, i++, e.instrumentType);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.planQty);
        bind(ps, i++, e.executedQty);
        bind(ps, i++, e.orderedQty);
        bind(ps, i++, e.execDeadline);
        bind(ps, i++, e.status);
        bind(ps, i, e.strategyId);
    }

    @Override
    protected void readBusiness(ResultSet rs, TradePlan e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.tradePlanNo = string(rs, "trade_plan_no");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.exposureId = nullableLong(rs, "exposure_id");
        e.productId = nullableLong(rs, "product_id");
        e.instrumentId = string(rs, "instrument_id");
        e.instrumentType = string(rs, "instrument_type");
        e.direction = string(rs, "direction");
        e.planQty = decimal(rs, "plan_qty");
        e.executedQty = decimal(rs, "executed_qty");
        e.orderedQty = decimal(rs, "ordered_qty");
        e.execDeadline = localDate(rs, "exec_deadline");
        e.status = string(rs, "status");
        e.strategyId = nullableLong(rs, "strategy_id");
    }

    @Override
    protected Long idOf(TradePlan entity) {
        return entity.id;
    }

    @Override
    protected TradePlan newEntity() {
        return new TradePlan();
    }

    @Override
    protected void applyGeneratedId(TradePlan entity, long generated) {
        entity.id = generated;
    }

    @Override
    public TradePlan findByTradePlanNo(String tradePlanNo) {
        String sql = "SELECT " + selectList() + " FROM " + tableName() + " WHERE trade_plan_no = ?";
        return queryOne(sql, Arrays.asList(tradePlanNo), this::mapRow);
    }

    @Override
    public List<TradePlan> findByHedgePlan(long hedgePlanId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE hedge_plan_id = ? ORDER BY exec_deadline ASC, id ASC";
        return queryList(sql, java.util.Arrays.asList(hedgePlanId), this::mapRow);
    }

    /**
     * {@inheritDoc}
     *
     * <p>条件形态 {@code (hedge_plan_id = ?) AND <scope.sql()>}；scope 为空集时其 {@code sql()} 为
     * 恒假条件（{@code sub_account_id IN (-1)}），与计划条件并列后必然无命中——即"越权读 = 空"。
     * 参数顺序与占位符一一对应：先 {@code hedge_plan_id}，后 scope 绑定值。
     */
    @Override
    public List<TradePlan> findByHedgePlanScoped(long hedgePlanId, SubAccountScope scope) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE " + scope.appendTo("hedge_plan_id = ?")
                + " ORDER BY exec_deadline ASC, id ASC";
        List<Object> args = new ArrayList<>();
        args.add(hedgePlanId);
        args.addAll(scope.params());
        return queryList(sql, args, this::mapRow);
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接复用 {@link AbstractDmDao#findPageScoped}（{@code NFR-SEC-7} 统一注入点）；
     * 本类不新增 SQL 分支。排序字段取自 {@link PageQuery}（由服务层白名单校验），
     * 可空附加条件 {@code extraWhere} 由服务层以占位符拼装（如 {@code hedge_plan_id = ?}
     * 与合约 / 状态 / 期限筛选），本层只负责与 scope 合并并计数。
     */
    @Override
    public PageResult<TradePlan> findPageScoped(PageQuery query, SubAccountScope scope,
                                                String extraWhere, List<Object> extraArgs) {
        return super.findPageScoped(query, scope, extraWhere, extraArgs);
    }
}
