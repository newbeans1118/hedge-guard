package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PlanStateLogDao;
import com.hedgeguard.dao.entity.PlanStateLog;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code t_plan_state_log} 的达梦实现（{@code DATA_MODEL.md} §2.5 TB-PLAN-7）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmPlanStateLogDao extends AbstractDmDao<PlanStateLog, Long> implements PlanStateLogDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "hedge_plan_id", "from_status", "to_status", "quota_action",
            "quota_qty", "quota_product_id", "dispatch_status", "dispatch_version", "operator_id",
            "reason", "action_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmPlanStateLogDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_plan_state_log";
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
    protected void bindBusiness(PreparedStatement ps, PlanStateLog e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.fromStatus);
        bind(ps, i++, e.toStatus);
        bind(ps, i++, e.quotaAction);
        bind(ps, i++, e.quotaQty);
        bind(ps, i++, e.quotaProductId);
        bind(ps, i++, e.dispatchStatus);
        bind(ps, i++, e.dispatchVersion);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.reason);
        bind(ps, i, e.actionTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, PlanStateLog e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.fromStatus = string(rs, "from_status");
        e.toStatus = string(rs, "to_status");
        e.quotaAction = string(rs, "quota_action");
        e.quotaQty = decimal(rs, "quota_qty");
        e.quotaProductId = nullableLong(rs, "quota_product_id");
        e.dispatchStatus = string(rs, "dispatch_status");
        e.dispatchVersion = nullableLong(rs, "dispatch_version");
        e.operatorId = nullableLong(rs, "operator_id");
        e.reason = string(rs, "reason");
        e.actionTime = localDateTime(rs, "action_time");
    }

    @Override
    protected Long idOf(PlanStateLog entity) {
        return entity.id;
    }

    @Override
    protected PlanStateLog newEntity() {
        return new PlanStateLog();
    }

    @Override
    protected void applyGeneratedId(PlanStateLog entity, long generated) {
        entity.id = generated;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code t_plan_state_log} 为<b>只增不改</b>表（{@code DATA_MODEL.md} §0.3）：本方法<b>显式拒绝</b>，
     * 使"留痕被改写"在编译期之后的第一次调用即暴露，而不是静默覆盖审计证据。
     */
    @Override
    public int updateById(PlanStateLog entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_plan_state_log 只增不改（DATA_MODEL.md §0.3），不允许更新；复现=改为新增一行留痕记录");
    }
}
