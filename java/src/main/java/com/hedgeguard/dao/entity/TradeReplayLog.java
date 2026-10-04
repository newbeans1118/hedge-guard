package com.hedgeguard.dao.entity;

/**
 * {@code TB-TRADE-9 t_replay_log} 重放记录（崩溃恢复的启动重放过程与结果）（{@code DATA_MODEL.md} §2.6）。
 *
 * <p>例外 E3：组件级重放日志，无 {@code sub_account_id} 列（{@code DATA_MODEL.md} §4.6.3 ③）。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class TradeReplayLog extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 组件标识（hg-trading）。 */
    public String componentId;

    /** 该组件第几次重放。 */
    public Integer replaySeq;

    /** STARTUP / MANUAL。 */
    public String triggerSource;

    /** NOT_READY / REPLAYING / READY / FAILED。 */
    public String state;

    /** 已重放记录数。 */
    public Long replayedRecords;

    /** 总记录数。 */
    public Long totalRecords;

    /** 在途指令数（已发 CTP 未收回报）。 */
    public Integer inFlightOrders;

    /** 与 CTP 侧对账匹配数。 */
    public Integer ctpMatchedCount;

    /** 与 CTP 侧对账不一致数。 */
    public Integer ctpMismatchCount;

    /** 不一致明细（CLOB）。 */
    public String mismatchDetail;

    /** 开始时间。 */
    public Long startedTsMs;

    /** 结束时间。 */
    public Long finishedTsMs;

    /** 耗时（NFR-REL-1：&le; 60 s）。 */
    public Long durationMs;

    /** OK / MISMATCH / ABORTED。 */
    public String result;

    /** 手工触发时的操作人。 */
    public Long operatorId;
}
