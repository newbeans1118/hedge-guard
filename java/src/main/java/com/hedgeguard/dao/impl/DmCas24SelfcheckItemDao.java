package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.Cas24SelfcheckItemDao;
import com.hedgeguard.dao.entity.Cas24SelfcheckItem;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * {@code t_cas24_selfcheck_item} 的达梦实现（{@code DATA_MODEL.md} §2.10 TB-ACCT-12）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充。条目级随自检头确定子账户（§4.6.3 ③，无该列）。
 */
public final class DmCas24SelfcheckItemDao extends AbstractDmDao<Cas24SelfcheckItem, Long>
        implements Cas24SelfcheckItemDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "selfcheck_id", "item_seq", "item_code", "item_name", "result",
            "evidence_type", "evidence_id", "evidence_desc", "fail_reason"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmCas24SelfcheckItemDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_cas24_selfcheck_item";
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
    protected void bindBusiness(PreparedStatement ps, Cas24SelfcheckItem e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.selfcheckId);
        bind(ps, i++, e.itemSeq);
        bind(ps, i++, e.itemCode);
        bind(ps, i++, e.itemName);
        bind(ps, i++, e.result);
        bind(ps, i++, e.evidenceType);
        bind(ps, i++, e.evidenceId);
        bind(ps, i++, e.evidenceDesc);
        bind(ps, i, e.failReason);
    }

    @Override
    protected void readBusiness(ResultSet rs, Cas24SelfcheckItem e) throws SQLException {
        e.id = rs.getLong("id");
        e.selfcheckId = nullableLong(rs, "selfcheck_id");
        e.itemSeq = nullableInt(rs, "item_seq");
        e.itemCode = string(rs, "item_code");
        e.itemName = string(rs, "item_name");
        e.result = string(rs, "result");
        e.evidenceType = string(rs, "evidence_type");
        e.evidenceId = nullableLong(rs, "evidence_id");
        e.evidenceDesc = string(rs, "evidence_desc");
        e.failReason = string(rs, "fail_reason");
    }

    @Override
    protected Long idOf(Cas24SelfcheckItem entity) {
        return entity.id;
    }

    @Override
    protected Cas24SelfcheckItem newEntity() {
        return new Cas24SelfcheckItem();
    }

    @Override
    protected void applyGeneratedId(Cas24SelfcheckItem entity, long generated) {
        entity.id = generated;
    }

    @Override
    public List<Cas24SelfcheckItem> findBySelfcheck(long selfcheckId) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE selfcheck_id = ? ORDER BY item_seq ASC, id ASC";
        return queryList(sql, Arrays.asList(selfcheckId), this::mapRow);
    }

    @Override
    public List<Cas24SelfcheckItem> findByCodeAndResult(String itemCode, String result) {
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE item_code = ? AND result = ? ORDER BY selfcheck_id ASC, item_seq ASC, id ASC";
        return queryList(sql, Arrays.asList(itemCode, result), this::mapRow);
    }
}
