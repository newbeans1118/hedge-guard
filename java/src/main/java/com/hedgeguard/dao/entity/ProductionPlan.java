package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SPOT-3 t_production_plan} 生产计划（计划期不得为空；关闭后不贡献新敞口）（{@code DATA_MODEL.md} §2.4）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class ProductionPlan extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户归属（必填）。 */
    public Integer subAccountId;

    /** 生产计划编号（同一子账户内唯一）。 */
    public String planNo;

    /** 计划期起（计划期不得为空）。 */
    public LocalDate planPeriodStart;

    /** 计划期止；必须 ≥ 起始日（ck_prod_plan_period）。 */
    public LocalDate planPeriodEnd;

    /** SCHEDULED（已排产）/ ADJUSTING（调整中）/ CLOSED（已关闭）。 */
    public String status;

    /** 计划期是否与既有计划重叠（提示重复，不阻断；FR-SPOT-8 验收 3）。 */
    public String overlapFlag;

    /** 关闭时间（关闭后不再贡献新敞口，历史留痕）。 */
    public LocalDateTime closedTime;
}
