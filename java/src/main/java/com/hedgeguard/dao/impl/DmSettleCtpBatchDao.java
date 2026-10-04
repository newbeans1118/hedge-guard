package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SettleCtpBatchDao;
import com.hedgeguard.dao.entity.SettleCtpBatch;
import com.hedgeguard.dao.tx.TxTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * {@code t_ctp_settle_batch} 的达梦实现（{@code DATA_MODEL.md} §2.7 TB-SETTLE-1）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSettleCtpBatchDao extends AbstractDmDao<SettleCtpBatch, Long> implements SettleCtpBatchDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "trade_date", "source_identifier", "received_time", "position_count", "fund_count", "trade_count", "parse_status", "parse_error", "retry_count", "last_retry_time", "is_confirmed"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSettleCtpBatchDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_ctp_settle_batch";
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
    protected void bindBusiness(PreparedStatement ps, SettleCtpBatch e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.tradeDate);
        bind(ps, i++, e.sourceIdentifier);
        bind(ps, i++, e.receivedTime);
        bind(ps, i++, e.positionCount);
        bind(ps, i++, e.fundCount);
        bind(ps, i++, e.tradeCount);
        bind(ps, i++, e.parseStatus);
        bind(ps, i++, e.parseError);
        bind(ps, i++, e.retryCount);
        bind(ps, i++, e.lastRetryTime);
        bind(ps, i++, e.isConfirmed);
    }

    @Override
    protected void readBusiness(ResultSet rs, SettleCtpBatch e) throws SQLException {
        e.id = rs.getLong("id");
        e.tradeDate = localDate(rs, "trade_date");
        e.sourceIdentifier = string(rs, "source_identifier");
        e.receivedTime = localDateTime(rs, "received_time");
        e.positionCount = nullableInt(rs, "position_count");
        e.fundCount = nullableInt(rs, "fund_count");
        e.tradeCount = nullableInt(rs, "trade_count");
        e.parseStatus = string(rs, "parse_status");
        e.parseError = string(rs, "parse_error");
        e.retryCount = nullableInt(rs, "retry_count");
        e.lastRetryTime = localDateTime(rs, "last_retry_time");
        e.isConfirmed = string(rs, "is_confirmed");
    }

    @Override
    protected Long idOf(SettleCtpBatch entity) {
        return entity.id;
    }

    @Override
    protected SettleCtpBatch newEntity() {
        return new SettleCtpBatch();
    }

    @Override
    protected void applyGeneratedId(SettleCtpBatch entity, long generated) {
        entity.id = generated;
    }

    @Override
    public SettleCtpBatch findByTradeDateAndSource(LocalDate tradeDate, String sourceIdentifier) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE trade_date = ? AND source_identifier = ?";
        return queryOne(sql, Arrays.asList(tradeDate, sourceIdentifier), this::mapRow);
    }
}
