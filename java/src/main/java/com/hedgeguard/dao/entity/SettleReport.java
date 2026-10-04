package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SETTLE-12 t_settle_report} 结算报告 / 风控日报（不一致须显式标注，不得输出正常结论）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleReport extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（全账户汇总为 NULL）。 */
    public Integer subAccountId;

    /** 交易日。 */
    public LocalDate tradeDate;

    /** 报告类型。 */
    public String reportType;

    /** 所依据的加总对账结论。 */
    public String aggResult;

    /** 报告内容（结构化）。 */
    public String contentJson;

    /** 生成的文件路径。 */
    public String contentPath;

    /** 是否存在数据缺口。 */
    public String dataGapFlag;

    /** 缺口说明。 */
    public String gapDesc;

    /** 生成人。 */
    public Long generatedBy;

    /** 生成时间。 */
    public LocalDateTime generatedTime;

    /** 该日报告是否缺失。 */
    public String missingFlag;
}
