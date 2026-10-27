package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-TRADE-10 t_trade_l3_reject} L3 护栏/紧急停止拒绝事件（合规证据，只增不改不删）
 * （{@code DATA_MODEL.md} §2.6；v1.3.10，{@code DC-047}）。
 *
 * <p>承载 {@code cr-t088} {@code R4-002} 失效场景的持久化留痕：L3 实盘护栏（{@code L3-FUND-CAP}，G-6
 * 资金上限）与紧急停止门禁（{@code L3-EMERGENCY-STOP}，G-7）在 Java 提交路径拒绝下单时落行
 * （此前仅 LOG 结构化告警 + 响应载荷）。与 {@code t_fund_quota_alert}（{@code R-FUND-7}
 * 「告警不阻断」，{@code blocked_operation} CHECK 恒 {@code 'NONE'} 冻结）相互独立。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class TradeL3Reject extends AuditedEntity {

    /** 主键。 */
    public long id;

    /**
     * 触发提交的指令子账户（账户级维度命中时为<b>提交方</b>子账户，占用/阈值为账户级汇总值）。
     */
    public Integer subAccountId;

    /** 护栏规则编号（{@code L3-FUND-CAP} / {@code L3-EMERGENCY-STOP}；与响应载荷 ruleId 逐字同源）。 */
    public String ruleId;

    /** 事件触发来源标识（现值域同 {@code ruleId}；为「一规则多来源」预留分化位）。 */
    public String triggerSource;

    /** 资金上限命中的维度（{@code SUB_ACCOUNT} / {@code ACCOUNT}；紧急停止拒绝为 {@code null}）。 */
    public String quotaScope;

    /** 品种（请求携带时记录；拒绝点位于计划一致性校验之前且请求未携带时为 {@code null}）。 */
    public Long productId;

    /** 当前值（响应载荷 current：资金占用值 / {@code emergency_stop=Y}）。 */
    public String currentValue;

    /** 阈值（响应载荷 threshold：资金上限值 / {@code 新提交=全部拒绝}）。 */
    public String thresholdValue;

    /** 被阻断的操作（现唯一取值 {@code PLACE_ORDER} 下单提交）。 */
    public String blockedOperation;

    /** 操作人（提交人，留痕）。 */
    public Long operatorId;

    /** 事件时间（拒绝判定时点，业务时钟）。 */
    public LocalDateTime eventTime;
}
