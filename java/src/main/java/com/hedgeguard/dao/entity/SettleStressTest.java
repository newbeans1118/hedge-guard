package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SETTLE-14 t_stress_test} 压力测试结果（情景化保证金缺口估算）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleStressTest extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（NULL = 全账户）。 */
    public Integer subAccountId;

    /** 测试基准日。 */
    public LocalDate testDate;

    /** 情景参数。 */
    public String scenarioJson;

    /** 估算的保证金缺口。 */
    public BigDecimal marginGap;

    /** 追加需求。 */
    public BigDecimal additionalRequirement;

    /** 权益估算。 */
    public BigDecimal equityAfter;

    /** 情景后的风险度。 */
    public BigDecimal riskRatioAfter;

    /** 执行人。 */
    public Long operatorId;

    /** 执行时间。 */
    public LocalDateTime testTime;
}
