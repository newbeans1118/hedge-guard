package com.hedgeguard.dao.impl;

import com.hedgeguard.dao.SpotChangeLogDao;
import com.hedgeguard.dao.entity.SpotChangeLog;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * {@code t_spot_change_log} 的达梦实现（{@code DATA_MODEL.md} §2.4 TB-SPOT-10）。
 *
 * <p>本类是<b>唯一</b>出现该表 SQL 的位置（{@code ARCHITECTURE.md} §3.9）；审计字段由
 * {@link AbstractDmDao} 自动填充；DM 方言特性集中在 {@link com.hedgeguard.dao.dialect.DmDialect}，
 * 本类不含方言语法。
 */
public final class DmSpotChangeLogDao extends AbstractDmDao<SpotChangeLog, Long> implements SpotChangeLogDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "source_type", "source_id", "change_type", "sub_account_before", "sub_account_after",
            "changed_field", "before_value", "after_value", "sub_account_id", "operator_id",
            "change_reason", "change_time", "recalc_triggered"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmSpotChangeLogDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_spot_change_log";
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
    protected void bindBusiness(PreparedStatement ps, SpotChangeLog e, int start) throws SQLException {
        int i = start;
        bind(ps, i++, e.sourceType);
        bind(ps, i++, e.sourceId);
        bind(ps, i++, e.changeType);
        bind(ps, i++, e.subAccountBefore);
        bind(ps, i++, e.subAccountAfter);
        bind(ps, i++, e.changedField);
        bind(ps, i++, e.beforeValue);
        bind(ps, i++, e.afterValue);
        bind(ps, i++, e.subAccountId);
        bind(ps, i++, e.operatorId);
        bind(ps, i++, e.changeReason);
        bind(ps, i++, e.changeTime);
        bind(ps, i, e.recalcTriggered);
    }

    @Override
    protected void readBusiness(ResultSet rs, SpotChangeLog e) throws SQLException {
        e.id = rs.getLong("id");
        e.sourceType = string(rs, "source_type");
        e.sourceId = nullableLong(rs, "source_id");
        e.changeType = string(rs, "change_type");
        e.subAccountBefore = nullableInt(rs, "sub_account_before");
        e.subAccountAfter = nullableInt(rs, "sub_account_after");
        e.changedField = string(rs, "changed_field");
        e.beforeValue = string(rs, "before_value");
        e.afterValue = string(rs, "after_value");
        e.subAccountId = nullableInt(rs, "sub_account_id");
        e.operatorId = nullableLong(rs, "operator_id");
        e.changeReason = string(rs, "change_reason");
        e.changeTime = localDateTime(rs, "change_time");
        e.recalcTriggered = string(rs, "recalc_triggered");
    }

    @Override
    protected Long idOf(SpotChangeLog entity) {
        return entity.id;
    }

    @Override
    protected SpotChangeLog newEntity() {
        return new SpotChangeLog();
    }

    @Override
    protected void applyGeneratedId(SpotChangeLog entity, long generated) {
        entity.id = generated;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code t_spot_change_log} 为<b>只增不改</b>表（{@code DATA_MODEL.md} §0.3）：本方法<b>显式拒绝</b>，
     * 使"留痕被改写"在编译期之后的第一次调用即暴露，而不是静默覆盖审计证据。
     */
    @Override
    public int updateById(SpotChangeLog entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_spot_change_log 只增不改（DATA_MODEL.md §0.3），不允许更新；复现=改为新增一行留痕记录");
    }
}
