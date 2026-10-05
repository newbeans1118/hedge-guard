package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-RISK-5 t_risk_disposal} 风险处置记录（{@code DATA_MODEL.md} §2.8；{@code FR-RISK-15}/{@code FR-RISK-16}）。
 *
 * <p>{@code scanScope} 恒为 {@code OPEN_ONLY}（只限开仓、不限平仓，{@code R-RISK-5}），由具名 CHECK 强制。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class RiskDisposal extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户。 */
    public Integer subAccountId;

    /** 是否全账户范围：{@code Y} / {@code N}。 */
    public String isGlobal;

    /** 处置类型：{@code ALERT_ONLY} / {@code SUSPEND_OPEN} / {@code FORCE_CLOSE_HINT} / {@code RELEASE_SUSPEND}。 */
    public String disposalType;

    /** 限制范围（恒为 {@code OPEN_ONLY}）。 */
    public String scanScope;

    /** 处置理由。 */
    public String reason;

    /** 强制平仓提示的建议合约（{@code FR-RISK-16} 验收 2）。 */
    public String hintInstrumentId;

    /** 建议方向。 */
    public String hintDirection;

    /** 建议数量。 */
    public Integer hintVolume;

    /** 是否接受提示：{@code Y} / {@code N}（{@code N} 时须填 {@code rejectReason}）。 */
    public String hintAccepted;

    /** 不接受提示的理由（必填）。 */
    public String rejectReason;

    /** 处置人。 */
    public long operatorId;

    /** 处置时间。 */
    public LocalDateTime disposalTime;

    /** 下发 C++ 结果。 */
    public String dispatchStatus;

    /** 下发 {@code param_version}。 */
    public Long dispatchVersion;

    /** 解除时间。 */
    public LocalDateTime releasedTime;
}
