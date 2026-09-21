package com.hedgeguard.dao;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.entity.AuditedEntity;

/**
 * 通用审计字段填充（{@code DATA_MODEL.md} §0.3）。
 *
 * <p>自动填充的字段：{@code created_by} / {@code created_time} / {@code updated_by} /
 * {@code updated_time} / {@code row_version}。
 *
 * <p>两条纪律：
 * <ul>
 *   <li><b>时间取 {@link Clocks}</b>，不取 {@code LocalDateTime.now()}——保证测试可注入时钟（{@code NFR-TEST-4}）；</li>
 *   <li><b>只增不改的表</b>不调用 {@link #applyUpdate}：其 {@code updated_*} 列恒为 {@code null}
 *       （{@code §0.3}）。此类表的 DAO 不得实现 {@code updateById}。</li>
 * </ul>
 */
public final class AuditFields {

    /** 系统账号 ID（系统自动创建记录时的 {@code created_by}）。 */
    public static final long SYSTEM_USER_ID = 0L;

    private AuditFields() {
    }

    /**
     * 插入前填充。
     *
     * @param entity     实体
     * @param operatorId 操作人（无操作人时为 {@link #SYSTEM_USER_ID}）
     */
    public static void applyInsert(AuditedEntity entity, long operatorId) {
        if (entity.createdTime == null) {
            entity.createdTime = Clocks.now();
        }
        entity.createdBy = operatorId;
        entity.updatedBy = null;
        entity.updatedTime = null;
        entity.rowVersion = 1;
    }

    /**
     * 更新前填充（乐观锁：调用方须保证 {@code entity.rowVersion} 为"读到的版本"，
     * SQL 以 {@code WHERE id = ? AND row_version = ?} 比对，成功时 {@code row_version = row_version + 1}）。
     *
     * @param entity     实体（其 {@code rowVersion} 为当前读到的版本）
     * @param operatorId 操作人
     */
    public static void applyUpdate(AuditedEntity entity, long operatorId) {
        if (entity.rowVersion <= 0) {
            throw new IllegalArgumentException(
                    "乐观锁更新缺少基准版本：rowVersion=" + entity.rowVersion
                            + "；原因=未先读取实体或读取后未回填版本；复现=先 findById 再 updateById");
        }
        entity.updatedBy = operatorId;
        entity.updatedTime = Clocks.now();
    }

    /**
     * 更新成功后推进内存版本号（与数据库 {@code row_version + 1} 保持一致）。
     *
     * @param entity 实体
     */
    public static void advanceVersion(AuditedEntity entity) {
        entity.rowVersion = entity.rowVersion + 1;
    }
}
