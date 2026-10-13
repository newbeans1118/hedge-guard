package com.hedgeguard.dao.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code TB-ACCT-9 t_dual_pnl_report} 双侧盈亏上报（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>实货盈亏与衍生品盈亏<b>同时上报</b>（FR-ACCT-10）；禁止只报衍生品盈亏；实货盈亏无法计算时
 * 保留栏位并标注原因，不得省略（{@code spot_pnl_column_present} 恒 'Y' 的 CHECK 为结构保证）。
 * {@code subAccountId} 可空 = 汇总上报行（§4.6.1 #49 口径 ②）。
 */
public final class DualPnlReport extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（上报按子账户 + 汇总；汇总行为 null）。 */
    public Integer subAccountId;

    /** 上报编号。 */
    public String reportNo;

    /** 报告期间。 */
    public String reportPeriod;

    /** 报告类型：{@code MONTHLY} / {@code QUARTERLY} / {@code ANNUAL}。 */
    public String reportType;

    /** 实货盈亏栏位是否存在（恒为 'Y'——禁止以缺数据为由省略栏位）。 */
    public String spotPnlColumnPresent;

    /** 实货盈亏值（无法计算时为 null）。 */
    public BigDecimal spotPnlValue;

    /** 无法计算的原因 + 缺失品种与日期（spotPnlValue 为 null 时必填）。 */
    public String spotPnlMissingReason;

    /** 衍生品盈亏值。 */
    public BigDecimal derivativePnlValue;

    /** 衍生品盈亏拆分（已实现）。 */
    public BigDecimal derivativePnlRealized;

    /** 衍生品盈亏拆分（未实现）。 */
    public BigDecimal derivativePnlUnrealized;

    /** 净效果。 */
    public BigDecimal netEffect;

    /** 数据完整性说明。 */
    public String dataCompletenessNote;

    /** 上报人。 */
    public Long submittedBy;

    /** 上报时间。 */
    public LocalDateTime submittedTime;

    /** 待外部复核标注。 */
    public String pendingExternalReview;
}
