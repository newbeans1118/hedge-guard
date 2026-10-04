package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-TRADE-7 t_command_flow} 指令流水镜像（生命周期轨迹；权威源为 C++ append-only 流水文件）（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class TradeCommandFlow extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** C++ 指令号（未受理为 null）。 */
    public Long orderId;

    /** 幂等键（未受理也能关联）。 */
    public String clientOrderRef;

    /** 流水阶段类型（10 值）。 */
    public String flowType;

    /** 该阶段详情。 */
    public String flowDetail;

    /** 事件毫秒时间戳。 */
    public Long flowTsMs;

    /** 落库时间。 */
    public LocalDateTime flowTime;

    /** 事件流序号。 */
    public Long eventSeq;

    /** 是否来自重放：LIVE / REPLAY。 */
    public String replayMarker;
}
