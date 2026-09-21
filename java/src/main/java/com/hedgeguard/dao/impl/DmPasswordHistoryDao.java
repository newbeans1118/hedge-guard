package com.hedgeguard.dao.impl;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PasswordHistoryDao;
import com.hedgeguard.dao.entity.PasswordHistory;
import com.hedgeguard.dao.tx.TxTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code t_password_history} 的达梦实现（{@code DATA_MODEL.md} §2.2 {@code TB-AUTH-11}）。
 *
 * <p><b>只增不改</b>（口令哈希不可改写）——{@link #updateById} 显式拒绝。
 *
 * <p>⚠️ **一处被契约显式允许的例外**：{@link #shiftSeqNo} 会整体后移 {@code seq_no}（表义要求
 * "{@code seq_no = 1} 为最新"），它**只改排序列**，**永不触及** {@code password_hash}；
 * {@link #deleteBeyond} 亦按表义清理冗余行（**非业务留痕**）。二者均属 {@code TB-AUTH-11} 备注明示的行为。
 */
public final class DmPasswordHistoryDao extends AbstractDmDao<PasswordHistory, Long>
        implements PasswordHistoryDao {

    /** 业务列（不含主键与通用审计字段）。 */
    private static final String[] COLUMNS = {
            "user_id", "password_hash", "changed_time", "seq_no"
    };

    /**
     * 构造。
     *
     * @param tx 事务模板
     */
    public DmPasswordHistoryDao(TxTemplate tx) {
        super(tx);
    }

    @Override
    protected String tableName() {
        return "t_password_history";
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
    protected void bindBusiness(PreparedStatement ps, PasswordHistory e, int start)
            throws SQLException {
        int i = start;
        ps.setLong(i++, e.userId);
        bind(ps, i++, e.passwordHash);
        bind(ps, i++, e.changedTime);
        ps.setInt(i, e.seqNo);
    }

    @Override
    protected void readBusiness(ResultSet rs, PasswordHistory e) throws SQLException {
        e.id = rs.getLong("id");
        e.userId = rs.getLong("user_id");
        e.passwordHash = string(rs, "password_hash");
        e.changedTime = localDateTime(rs, "changed_time");
        e.seqNo = rs.getInt("seq_no");
    }

    @Override
    protected Long idOf(PasswordHistory entity) {
        return entity.id;
    }

    @Override
    protected PasswordHistory newEntity() {
        return new PasswordHistory();
    }

    @Override
    protected void applyGeneratedId(PasswordHistory entity, long generated) {
        entity.id = generated;
    }

    @Override
    public int updateById(PasswordHistory entity, long operatorId) {
        throw new UnsupportedOperationException(
                "t_password_history 只增不改（口令哈希不可改写）；排序前移请用 shiftSeqNo，"
                        + "禁止原地改写 password_hash；复现=检查调用点 PasswordHistoryDao.updateById");
    }

    @Override
    public List<PasswordHistory> findRecent(long userId, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        // 不用 PageQuery：其 size 取值域被 §4.1.1 冻结为 {10,20,50,100}，无法表达 limit=5（CFG 默认）；
        // 每用户历史行数极少（≤ password_history_size，上限 24），取回后截断的代价可忽略且换库无关。
        String sql = "SELECT " + selectList() + " FROM " + tableName()
                + " WHERE user_id = ? ORDER BY seq_no ASC";
        List<PasswordHistory> all = queryList(sql, Collections.singletonList(userId), this::mapRow);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    @Override
    public int shiftSeqNo(long userId) {
        String sql = "UPDATE " + tableName()
                + " SET seq_no = seq_no + 1, updated_time = ?, row_version = row_version + 1"
                + " WHERE user_id = ?";
        return executeUpdateSql(sql, Arrays.asList(Clocks.now(), userId));
    }

    @Override
    public int deleteBeyond(long userId, int keep) {
        // seq_no 从 1 起递增且 1 = 最新 ⇒ 保留 seq_no ≤ keep
        String sql = "DELETE FROM " + tableName() + " WHERE user_id = ? AND seq_no > ?";
        return executeUpdateSql(sql, Arrays.asList(userId, Math.max(keep, 0)));
    }
}
