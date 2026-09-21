package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-PLAN-4 t_hedge_doc} 套期关系书面文件（多版本；三要素齐备方可生成）（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class HedgeDoc extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 关联计划。 */
    public Long hedgePlanId;

    /** 文件编号（全系统唯一）。 */
    public String docNo;

    /** 版本号（计划要素变更后重新生成并保留历史版本）。 */
    public Integer docVersion;

    /** 是否当前版本（同一计划至多一个 'Y'，应用层 + 条件唯一索引保证）。 */
    public String isCurrent;

    /** 要素①正式指定（被套期项目 = 实货敞口标识；套期工具 = 期货/期权合约）。 */
    public String elemFormalDesignation;

    /** 要素②有效性评估方法。 */
    public String elemEffectivenessMethod;

    /** 要素③再平衡机制。 */
    public String elemRebalanceMechanism;

    /** 被套期项目（实货敞口）。 */
    public Long hedgedItemExposureId;

    /** 套期工具（合约 / 持仓标识）。 */
    public String hedgingInstrument;

    /** 书面文件所依据的自定义规则（CAS 24「书面文件载明有效性评估方法」；v1.2 DC-025）：ratio_method='CUSTOM_RULE' 时非空，使书面文件可打印、可追溯到具体规则行（规则编号 + 基数 + 比例上限 + 方向 + 生效期间 + 品种 + 子账户）；非自定义规则法为 NULL。 */
    public Long customRuleId;

    /** "待外部复核"标注（必须恒为 'Y'），R-ACCT-10。 */
    public String pendingExternalReview;

    /** 生成人。 */
    public Long generatedBy;

    /** 生成时间。 */
    public LocalDateTime generatedTime;

    /** 失效时间（被新版本取代）。 */
    public LocalDateTime invalidatedTime;
}
