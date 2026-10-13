package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-11 t_cas24_selfcheck} CAS 24 条款自检（运行）（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>自检不替代外部复核，须显式说明（{@code notSubstituteNote}）；结论须导出为自检报告并标注
 * "待外部复核"（{@code pendingExternalReview} 恒 'Y' 的 CHECK 为结构保证，R-ACCT-10）。
 * {@code subAccountId} 可空 = 全账户自检（§4.6.1 #51 口径 ②）。
 */
public final class Cas24Selfcheck extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（null = 全账户自检）。 */
    public Integer subAccountId;

    /** 自检批次号。 */
    public String selfcheckNo;

    /** 自检范围类型：{@code HEDGE_RELATION}（按套期关系）/ {@code PERIOD}（按会计期间）。 */
    public String scopeType;

    /** 范围键（关系编号 / 期间键）。 */
    public String scopeKey;

    /** 通过条数。 */
    public Integer passCount;

    /** 不通过条数。 */
    public Integer failCount;

    /** 结论：{@code ALL_PASS} / {@code HAS_FAIL} / {@code CANNOT_SELFCHECK}（关键数据缺失）。 */
    public String conclusion;

    /** 无法自检的原因（conclusion = CANNOT_SELFCHECK 时必填）。 */
    public String cannotSelfcheckReason;

    /** 自检报告导出文件标识。 */
    public String reportFileId;

    /** 待外部复核标注（恒为 'Y'，R-ACCT-10）。 */
    public String pendingExternalReview;

    /** 须显式说明：自检不替代外部复核（R-ACCT-10）。 */
    public String notSubstituteNote;

    /** 执行人。 */
    public Long executorId;

    /** 自检时间。 */
    public LocalDateTime selfcheckTime;
}
