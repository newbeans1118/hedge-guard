package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleStressTestDao;
import com.hedgeguard.dao.entity.SettleStressTest;
import com.hedgeguard.dao.tx.TxTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_stress_test} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-14）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleStressTestDao extends AbstractDmDao<SettleStressTest, Long> implements SettleStressTestDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "test_date", "scenario_json", "margin_gap", "additional_requirement", "equity_after", "risk_ratio_after", "operator_id", "test_time"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleStressTestDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_stress_test";
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
    protected void bindBusiness(PreparedStatement ps, SettleStressTest e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.testDate);
        bindClob(ps, i++, e.scenarioJson);
        bind(ps, i++, e.marginGap);
        bind(ps, i++, e.additionalRequirement);
        bind(ps, i++, e.equityAfter);
        bind(ps, i++, e.riskRatioAfter);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.testTime);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleStressTest e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.testDate = localDate(rs, "test_date");
        e.scenarioJson = clob(rs, "scenario_json");
        e.marginGap = decimal(rs, "margin_gap");
        e.additionalRequirement = decimal(rs, "additional_requirement");
        e.equityAfter = decimal(rs, "equity_after");
        e.riskRatioAfter = decimal(rs, "risk_ratio_after");
        e.operatorId = nullableLong(rs, "operator_id");
        e.testTime = localDateTime(rs, "test_time");
    }

    @Override
    protected Long idOf(SettleStressTest entity) {
        return entity.id;
    }

    @Override
    protected SettleStressTest newEntity() {
        return new SettleStressTest();
    }

    @Override
    protected void applyGeneratedId(SettleStressTest entity, long generated) {
        entity.id = generated;
    }
}
