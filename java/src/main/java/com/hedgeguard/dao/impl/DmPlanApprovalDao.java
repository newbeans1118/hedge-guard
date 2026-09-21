package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.PlanApprovalDao;
import com.hedgeguard.dao.entity.PlanApproval;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * {@code t_plan_approval} 的达梦实现（{@code DATA_MODEL.md} §2.5 TB-PLAN-5）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmPlanApprovalDao extends AbstractDmDao<PlanApproval, Long> implements PlanApprovalDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "hedge_plan_id", "node_seq", "node_type", "applicant_id",
            "approver_id", "approval_result", "approval_comment", "reject_reason",
            "precheck_result", "action_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmPlanApprovalDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_plan_approval";
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
    protected void bindBusiness(PreparedStatement ps, PlanApproval e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.nodeSeq);
        bind(ps, i++, e.nodeType);
        bind(ps, i++, e.applicantId);
        bind(ps, i++, e.approverId);
        bind(ps, i++, e.approvalResult);
        bind(ps, i++, e.approvalComment);
        bind(ps, i++, e.rejectReason);
        bindClob(ps, i++, e.precheckResult);
        bind(ps, i, e.actionTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, PlanApproval e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.nodeSeq = nullableInt(rs, "node_seq");
        e.nodeType = string(rs, "node_type");
        e.applicantId = nullableLong(rs, "applicant_id");
        e.approverId = nullableLong(rs, "approver_id");
        e.approvalResult = string(rs, "approval_result");
        e.approvalComment = string(rs, "approval_comment");
        e.rejectReason = string(rs, "reject_reason");
        e.precheckResult = clob(rs, "precheck_result");
        e.actionTime = localDateTime(rs, "action_time");
    }

    @Override
    protected Long idOf(PlanApproval entity) {
        return entity.id;
    }

    @Override
    protected PlanApproval newEntity() {
        return new PlanApproval();
    }

    @Override
    protected void applyGeneratedId(PlanApproval entity, long generated) {
        entity.id = generated;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code t_plan_approval} 为<b>只增不改</b>表（{@code DATA_MODEL.md} §0.3）：本方法<b>显式拒绝</b>，
     * 使"留痕被改写"在编译期之后的第一次调用即暴露，而不是静默覆盖审计证据。
     */
    @Override
    public int updateById(PlanApproval entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_plan_approval 只增不改（DATA_MODEL.md §0.3），不允许更新；复现=改为新增一行留痕记录");
    }
}
