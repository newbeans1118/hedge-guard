package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * 通用审计字段基类（{@code DATA_MODEL.md} §0.3「每张表都有」）。
 *
 * <p>字段集固定为 5 项，与 {@code §0.3} 一一对应：
 * <ul>
 *   <li>{@code createdBy} / {@code createdTime}：NOT NULL，由应用层写入（{@code §0.2} 强调时间戳可注入时钟）；</li>
 *   <li>{@code updatedBy} / {@code updatedTime}：可空；<b>只增不改的表恒为 {@code null}</b>；</li>
 *   <li>{@code rowVersion}：乐观锁版本号，{@code §0.3} 规定"只增不改的表不使用"，
 *       此类表由 {@link com.hedgeguard.dao.tx.TxTemplate} 之外的表专用 DAO 忽略。</li>
 * </ul>
 *
 * <p>设计取舍（冻结）：实体采用 <b>public 字段</b>，不写 getter/setter —— 全库 112 张表的（v1.3.10 起）
 * 样板方法会掩盖真正的业务约束，且本项目不使用反射式 ORM；映射由 DAO 显式完成。
 * 该约定写入 {@code java/CONTRACTS.md}，{@code #java2} 各模块遵循。
 */
public abstract class AuditedEntity {

    /** 创建人（用户 ID）；系统自动创建时取系统账号 ID。 */
    public long createdBy;

    /** 创建时间（应用层写入，时钟可注入）。 */
    public LocalDateTime createdTime;

    /** 最后修改人；只增不改的表恒为 {@code null}。 */
    public Long updatedBy;

    /** 最后修改时间；只增不改的表恒为 {@code null}。 */
    public LocalDateTime updatedTime;

    /** 乐观锁版本号（{@code §0.3}：只增不改的表不使用）。 */
    public int rowVersion;
}
