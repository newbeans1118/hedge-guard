package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-AUDIT-4 t_violation_screen_run} 违规筛查运行（{@code DATA_MODEL.md} §2.9；{@code FR-AUDIT-4}/{@code R-AUDIT-8}）。
 *
 * <p>每次违规筛查的条件、时间、执行人。<b>筛查结论须可复现</b>。
 * 实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3）。
 */
public final class ViolationScreenRun extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 筛查批次号。 */
    public String screenNo;

    /** 筛查时间范围（起）。 */
    public LocalDate dateFrom;

    /** 筛查时间范围（止）。 */
    public LocalDate dateTo;

    /** 子账户范围（可为「全部有权限」）。 */
    public String subAccountScope;

    /** 筛查维度（超品种/超规模/超期限/超授权/频繁开平仓/交易理由缺失）。 */
    public String screenDimensions;

    /** 所用规则口径版本（与 {@code RISK} 一致，{@code R-AUDIT-7}）。 */
    public String ruleVersion;

    /** 命中条数。 */
    public int hitCount;

    /** 执行人。 */
    public long operatorId;

    /** 筛查时间。 */
    public LocalDateTime screenTime;

    /** 结论；无命中时须明确「未发现违规」，不得空白（{@code FR-AUDIT-4} 验收 4）。 */
    public String conclusion;
}
