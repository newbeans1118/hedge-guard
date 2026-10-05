package com.hedgeguard.dao.entity;

import java.math.BigDecimal;

/**
 * {@code TB-RISK-1 t_risk_threshold} 风控阈值（{@code DATA_MODEL.md} §2.8；{@code FR-CONFIG-5}/{@code R-RISK-14}）。
 *
 * <p>集中配置的风控参数，<b>不得硬编码</b>。{@code GLOBAL} 行为 B 类（哨兵 {@code 0}），
 * 由 §4.3 具名 CHECK {@code ck_risk_threshold_scope_sentinel} 在 DB 级确定。
 * 合规下限的存储表达：{@code defaultValue} + {@code isComplianceRedline}（默认值不得低于 {@code INDEX.md} §9.1 口径）。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class RiskThreshold extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 阈值 Key（对应 {@code ARCHITECTURE.md} §11 的 {@code HG.RISK.*}）。 */
    public String thresholdKey;

    /** 阈值取值。 */
    public String thresholdValue;

    /** 取值类型：{@code DECIMAL} / {@code INT} / {@code BOOL} / {@code LIST}。 */
    public String valueType;

    /** 取值范围下界（输入越界被拒，{@code FR-CONFIG-5} 验收 3）。 */
    public BigDecimal minValue;

    /** 取值范围上界。 */
    public BigDecimal maxValue;

    /** 默认值（须与 {@code PROJECT.md} 合规红线一致，不得更宽松，{@code R-CONFIG-8}）。 */
    public String defaultValue;

    /** 是否为合规红线：{@code Y} 时放宽须双人复核并留痕。 */
    public String isComplianceRedline;

    /** 作用域类型：{@code GLOBAL}（哨兵 0）/ {@code SUB_ACCOUNT}（{@code > 0}，优先于全局）。 */
    public String scopeType;

    /** 子账户（{@code GLOBAL} 时哨兵 {@code 0}）。 */
    public Integer subAccountId;

    /** 关联待澄清编号（{@code Q-1}~{@code Q-17}）。 */
    public String relatedQuestionNo;

    /** 下发 C++ 状态：{@code PENDING} / {@code SUCCESS} / {@code FAIL} / {@code UNCONFIRMED}。 */
    public String dispatchStatus;

    /** 下发 {@code param_version}。 */
    public Long dispatchVersion;

    /** 说明（含影响提示）。 */
    public String description;
}
