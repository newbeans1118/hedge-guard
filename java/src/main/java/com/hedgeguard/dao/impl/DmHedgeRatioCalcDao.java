package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.HedgeRatioCalcDao;
import com.hedgeguard.dao.entity.HedgeRatioCalc;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code t_hedge_ratio_calc} 的达梦实现（{@code DATA_MODEL.md} §2.5 TB-PLAN-3）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmHedgeRatioCalcDao extends AbstractDmDao<HedgeRatioCalc, Long> implements HedgeRatioCalcDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "hedge_plan_id", "exposure_id", "ratio_method", "input_json",
            "output_ratio", "rho", "sigma_spot", "sigma_futures", "sample_count",
            "sample_start_date", "sample_end_date", "custom_rule_id", "custom_rule_desc",
            "applied_ratio_cap", "applied_rule_id", "hit_rule_count", "result_code", "fail_detail",
            "operator_id", "calc_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmHedgeRatioCalcDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_hedge_ratio_calc";
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
    protected void bindBusiness(PreparedStatement ps, HedgeRatioCalc e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.hedgePlanId);
        bind(ps, i++, e.exposureId);
        bind(ps, i++, e.ratioMethod);
        bindClob(ps, i++, e.inputJson);
        bind(ps, i++, e.outputRatio);
        bind(ps, i++, e.rho);
        bind(ps, i++, e.sigmaSpot);
        bind(ps, i++, e.sigmaFutures);
        bind(ps, i++, e.sampleCount);
        bind(ps, i++, e.sampleStartDate);
        bind(ps, i++, e.sampleEndDate);
        bind(ps, i++, e.customRuleId);
        bind(ps, i++, e.customRuleDesc);
        bind(ps, i++, e.appliedRatioCap);
        bind(ps, i++, e.appliedRuleId);
        bind(ps, i++, e.hitRuleCount);
        bind(ps, i++, e.resultCode);
        bind(ps, i++, e.failDetail);
        bind(ps, i++, e.operatorId);
        bind(ps, i, e.calcTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, HedgeRatioCalc e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.hedgePlanId = nullableLong(rs, "hedge_plan_id");
        e.exposureId = nullableLong(rs, "exposure_id");
        e.ratioMethod = string(rs, "ratio_method");
        e.inputJson = clob(rs, "input_json");
        e.outputRatio = decimal(rs, "output_ratio");
        e.rho = decimal(rs, "rho");
        e.sigmaSpot = decimal(rs, "sigma_spot");
        e.sigmaFutures = decimal(rs, "sigma_futures");
        e.sampleCount = nullableInt(rs, "sample_count");
        e.sampleStartDate = localDate(rs, "sample_start_date");
        e.sampleEndDate = localDate(rs, "sample_end_date");
        e.customRuleId = nullableLong(rs, "custom_rule_id");
        e.customRuleDesc = string(rs, "custom_rule_desc");
        e.appliedRatioCap = decimal(rs, "applied_ratio_cap");
        e.appliedRuleId = nullableLong(rs, "applied_rule_id");
        e.hitRuleCount = nullableInt(rs, "hit_rule_count");
        e.resultCode = string(rs, "result_code");
        e.failDetail = string(rs, "fail_detail");
        e.operatorId = nullableLong(rs, "operator_id");
        e.calcTime = localDateTime(rs, "calc_time");
    }

    @Override
    protected Long idOf(HedgeRatioCalc entity) {
        return entity.id;
    }

    @Override
    protected HedgeRatioCalc newEntity() {
        return new HedgeRatioCalc();
    }

    @Override
    protected void applyGeneratedId(HedgeRatioCalc entity, long generated) {
        entity.id = generated;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code t_hedge_ratio_calc} 为<b>只增不改</b>表（{@code DATA_MODEL.md} §0.3）：本方法<b>显式拒绝</b>，
     * 使"留痕被改写"在编译期之后的第一次调用即暴露，而不是静默覆盖审计证据。
     */
    @Override
    public int updateById(HedgeRatioCalc entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_hedge_ratio_calc 只增不改（DATA_MODEL.md §0.3），不允许更新；复现=改为新增一行留痕记录");
    }
}
