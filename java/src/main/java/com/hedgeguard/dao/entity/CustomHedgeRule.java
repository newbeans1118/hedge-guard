package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-PLAN-8 t_custom_hedge_rule} 自定义套保比例规则（比例上限 + 适用条件，无表达式引擎）（{@code DATA_MODEL.md} §2.5）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class CustomHedgeRule extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 规则编号（业务编号，应用层生成，R-CONFIG-4 口径；书面文件引用用）。 */
    public String ruleCode;

    /** 规则名称（如"贸易类内控上限 80%"）。 */
    public String ruleName;

    /** 子账户（规则按子账户配置）。 */
    public Integer subAccountId;

    /** 品种级；NULL = 全品种适用（"品种列表"由多条规则行表达：每个品种一行）。 */
    public Long productId;

    /** 方向：BUY（买入套保）/ SELL（卖出套保）/ BOTH（不限）。 */
    public String direction;

    /** 比例基数（与 Q-17 三分量口径对齐）：EXPOSURE_QTY（实货敞口实物量）/ CONTRACT_QTY（合同量）/ INVENTORY_QTY（库存量）。 */
    public String basis;

    /** 比例上限（小数存储，如 0.8000 = 不超过 80%）；必须 ∈ (0, 1]。 */
    public BigDecimal ratioCap;

    /** 优先级（数值小者优先；并列时的第二次序）。 */
    public Integer priority;

    /** 生效起始日。 */
    public LocalDate effectiveFrom;

    /** 生效截止日（NULL = 长期有效）；CHECK (effective_to IS NULL OR effective_to >= effective_from)。 */
    public LocalDate effectiveTo;

    /** DRAFT（草稿）/ EFFECTIVE（生效）/ EXPIRED（已过期）。 */
    public String status;

    /** 规则说明（不承载规则本体，本体已结构化；FR-PLAN-8 验收 2）。 */
    public String ruleDesc;

    /** 审批人（内控红线类规则的放开须双人复核并留痕，R-CONFIG-8 口径）。 */
    public Long approvedBy;

    /** 审批时间。 */
    public LocalDateTime approvedTime;
}
