package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-1 t_hedge_relation} 套期关系（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>套期关系一经指定不得静默变更（{@code ACCT.md} §2 数据字典）：状态机限制 +
 * 要素变更须终止后新建（或在 {@code t_rebalance_record} 走"重新指定"并留痕）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class HedgeRelation extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 套期关系编号（全系统唯一）。 */
    public String relationNo;

    /** 套保计划。 */
    public Long hedgePlanId;

    /** 被套期项目（已识别的实货敞口）。 */
    public Long hedgedItemExposureId;

    /** 套期工具类型：{@code FUTURES} / {@code OPTION}（期权与期货同等对待）。 */
    public String hedgingInstrumentType;

    /** 套期工具描述（合约与持仓标识）。 */
    public String hedgingInstrumentDesc;

    /** 关联的套期关系书面文件（三要素来源）。 */
    public Long hedgeDocId;

    /** 状态：{@code DESIGNATED} / {@code EFFECTIVE} / {@code NEED_REBALANCE} / {@code REDESIGNATED} / {@code TERMINATED}。 */
    public String status;

    /** 正式指定时间。 */
    public LocalDateTime designatedTime;

    /** 终止时间。 */
    public LocalDateTime terminatedTime;
}
