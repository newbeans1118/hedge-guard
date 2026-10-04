package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleReportDao;
import com.hedgeguard.dao.entity.SettleReport;
import com.hedgeguard.dao.tx.TxTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code t_settle_report} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-12）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleReportDao extends AbstractDmDao<SettleReport, Long> implements SettleReportDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "trade_date", "report_type", "agg_result", "content_json", "content_path", "data_gap_flag", "gap_desc", "generated_by", "generated_time", "missing_flag"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleReportDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_settle_report";
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
    protected void bindBusiness(PreparedStatement ps, SettleReport e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.reportType);
        bind(ps, i++, e.aggResult);
        bindClob(ps, i++, e.contentJson);
        bind(ps, i++, e.contentPath);
        bind(ps, i++, e.dataGapFlag);
        bind(ps, i++, e.gapDesc);
        bind(ps, i++, e.generatedBy);
        bind(ps, i++, e.generatedTime);
        bind(ps, i++, e.missingFlag);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleReport e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.tradeDate = localDate(rs, "trade_date");
        e.reportType = string(rs, "report_type");
        e.aggResult = string(rs, "agg_result");
        e.contentJson = clob(rs, "content_json");
        e.contentPath = string(rs, "content_path");
        e.dataGapFlag = string(rs, "data_gap_flag");
        e.gapDesc = string(rs, "gap_desc");
        e.generatedBy = nullableLong(rs, "generated_by");
        e.generatedTime = localDateTime(rs, "generated_time");
        e.missingFlag = string(rs, "missing_flag");
    }

    @Override
    protected Long idOf(SettleReport entity) {
        return entity.id;
    }

    @Override
    protected SettleReport newEntity() {
        return new SettleReport();
    }

    @Override
    protected void applyGeneratedId(SettleReport entity, long generated) {
        entity.id = generated;
    }
}
