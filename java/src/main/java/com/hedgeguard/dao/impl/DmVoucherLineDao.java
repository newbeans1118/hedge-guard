package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.VoucherLineDao;
import com.hedgeguard.dao.entity.VoucherLine;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_voucher_line} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-5）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。借贷平衡由服务层同事务校验（§4.4 唯一例外），本层不做。
 */
public final class DmVoucherLineDao extends AbstractDmDao<VoucherLine, Long> implements VoucherLineDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "sub_account_id", "voucher_id", "line_no", "subject_code", "subject_name",
            "direction", "amount", "qty", "trade_fill_id", "biz_desc"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmVoucherLineDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_voucher_line";
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
    protected void bindBusiness(PreparedStatement ps, VoucherLine e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.voucherId);
        bind(ps, i++, e.lineNo);
        bind(ps, i++, e.subjectCode);
        bind(ps, i++, e.subjectName);
        bind(ps, i++, e.direction);
        bind(ps, i++, e.amount);
        bind(ps, i++, e.qty);
        bind(ps, i++, e.tradeFillId);
        bind(ps, i, e.bizDesc);
    }

    @Override
    protected void readBusiness(ResultSet rs, VoucherLine e) throws SQLException {
        e.id = rs.getLong("id");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.voucherId = nullableLong(rs, "voucher_id");
        e.lineNo = nullableInt(rs, "line_no");
        e.subjectCode = string(rs, "subject_code");
        e.subjectName = string(rs, "subject_name");
        e.direction = string(rs, "direction");
        e.amount = decimal(rs, "amount");
        e.qty = decimal(rs, "qty");
        e.tradeFillId = nullableLong(rs, "trade_fill_id");
        e.bizDesc = string(rs, "biz_desc");
    }

    @Override
    protected Long idOf(VoucherLine entity) {
        return entity.id;
    }

    @Override
    protected VoucherLine newEntity() {
        return new VoucherLine();
    }

    @Override
    protected void applyGeneratedId(VoucherLine entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<VoucherLine> findByVoucher(long voucherId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE voucher_id = ? ORDER BY line_no ASC, id ASC";
        return queryList(sql, Arrays.asList(voucherId), this::mapRow);
    }

    @Override
    public List<VoucherLine> findByTradeFill(long tradeFillId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE trade_fill_id = ? ORDER BY line_no ASC, id ASC";
        return queryList(sql, Arrays.asList(tradeFillId), this::mapRow);
    }
}
