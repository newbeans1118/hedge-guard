package com.hedgeguard.dao.testsupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * {@code T-069} 审计日志「篡改夹具」（仅测试；置于 {@code dao} 命名空间以满足 {@code E5}：
 * {@code dao} 之外不得出现 SQL）。
 *
 * <p>直接改写 {@code t_audit_log} 底层数据，用于验证防篡改校验（{@code FR-AUDIT-10} 验收 2）。
 * 不属产品代码。
 */
public final class T069AuditTamperSeed {

    private T069AuditTamperSeed() {
    }

    /** 篡改某链序号行的 {@code result_count}（模拟直接改底层日志）。 */
    public static void tamperAuditLogResultCount(InMemoryDmEngine engine, long seqInChain, int value)
            throws SQLException {
        exec(engine, "UPDATE t_audit_log SET result_count = ? WHERE seq_in_chain = ?", value, seqInChain);
    }

    /** 篡改某链序号行的 {@code row_hash}（模拟改哈希本身）。 */
    public static void tamperAuditLogRowHash(InMemoryDmEngine engine, long seqInChain, String rowHash)
            throws SQLException {
        exec(engine, "UPDATE t_audit_log SET row_hash = ? WHERE seq_in_chain = ?", rowHash, seqInChain);
    }

    /** 篡改某链序号行的 {@code action_type}（模拟改关键字段）。 */
    public static void tamperAuditLogActionType(InMemoryDmEngine engine, long seqInChain, String actionType)
            throws SQLException {
        exec(engine, "UPDATE t_audit_log SET action_type = ? WHERE seq_in_chain = ?", actionType, seqInChain);
    }

    private static void exec(InMemoryDmEngine engine, String sql, Object... args) throws SQLException {
        try (Connection conn = engine.create();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof Integer) {
                    ps.setInt(i + 1, (Integer) a);
                } else if (a instanceof Long) {
                    ps.setLong(i + 1, (Long) a);
                } else {
                    ps.setString(i + 1, String.valueOf(a));
                }
            }
            ps.executeUpdate();
        }
    }
}
