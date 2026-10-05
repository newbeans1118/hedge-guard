package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-RISK-3 t_mirror_watermark} 镜像状态水位（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-12}/{@code R-RISK-10}）。
 *
 * <p>Java 侧镜像状态的已消费事件序号；滞后超阈值须告警。实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class MirrorWatermark extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 组件标识（如 {@code hg-trading}）。 */
    public String componentId;

    /** 流类型：{@code EVENT}（事件流）/ {@code QUOTE}（行情流）。 */
    public String streamType;

    /** 已消费的最大 SEQ。 */
    public long lastConsumedSeq;

    /** 对端已发送的最大 SEQ（来自心跳载荷）。 */
    public long lastProducedSeq;

    /** 滞后量 = {@code lastProducedSeq − lastConsumedSeq}。 */
    public long lag;

    /** 滞后告警阈值（配置项）。 */
    public long lagThreshold;

    /** 当前是否告警：{@code Y} / {@code N}。 */
    public String lagAlarm;

    /** 最后更新时间。 */
    public LocalDateTime lastUpdateTime;

    /** 连接状态：{@code CONNECTED} / {@code DISCONNECTED} / {@code RESUMING}。 */
    public String connectionState;
}
