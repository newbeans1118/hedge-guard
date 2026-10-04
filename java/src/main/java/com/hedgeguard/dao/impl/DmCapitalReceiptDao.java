package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.CapitalReceiptDao;
import com.hedgeguard.dao.entity.CapitalReceipt;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_capital_receipt} 的达梦实现（{@code DATA_MODEL.md} §2.11 TB-FUND-5）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}。
 */
public final class DmCapitalReceiptDao extends AbstractDmDao<CapitalReceipt, Long> implements CapitalReceiptDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "idempotency_key", "apply_id", "result", "amount", "ctp_error_id",
            "ctp_error_msg", "receipt_ts_ms", "receipt_time", "booked", "query_confirmed"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmCapitalReceiptDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_capital_receipt";
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
    protected void bindBusiness(PreparedStatement ps, CapitalReceipt e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.idempotencyKey);
        bind(ps, i++, e.applyId);
        bind(ps, i++, e.result);
        bind(ps, i++, e.amount);
        bind(ps, i++, e.ctpErrorId);
        bind(ps, i++, e.ctpErrorMsg);
        bind(ps, i++, e.receiptTsMs);
        bind(ps, i++, e.receiptTime);
        bind(ps, i++, e.booked);
        bind(ps, i, e.queryConfirmed);
    }

    @Override
    protected void readBusiness(ResultSet rs, CapitalReceipt e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.idempotencyKey = string(rs, "idempotency_key");
        e.applyId = nullableLong(rs, "apply_id");
        e.result = string(rs, "result");
        e.amount = decimal(rs, "amount");
        e.ctpErrorId = nullableInt(rs, "ctp_error_id");
        e.ctpErrorMsg = string(rs, "ctp_error_msg");
        e.receiptTsMs = nullableLong(rs, "receipt_ts_ms");
        e.receiptTime = localDateTime(rs, "receipt_time");
        e.booked = string(rs, "booked");
        e.queryConfirmed = string(rs, "query_confirmed");
    }

    @Override
    protected Long idOf(CapitalReceipt entity) {
        return entity.id;
    }

    @Override
    protected CapitalReceipt newEntity() {
        return new CapitalReceipt();
    }

    @Override
    protected void applyGeneratedId(CapitalReceipt entity, long generated) {
        entity.id = generated;
    }

    @Override
    public CapitalReceipt findByIdem(String idempotencyKey, String result, long receiptTsMs) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE idempotency_key = ? AND result = ? AND receipt_ts_ms = ?";
        return queryOne(sql, Arrays.asList(idempotencyKey, result, receiptTsMs), this::mapRow);
    }

    @Override
    public List<CapitalReceipt> findByApplyId(long applyId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE apply_id = ? ORDER BY receipt_time ASC, id ASC";
        return queryList(sql, Arrays.asList(applyId), this::mapRow);
    }
}
