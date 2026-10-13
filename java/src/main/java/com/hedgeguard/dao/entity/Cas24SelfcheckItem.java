package com.hedgeguard.dao.entity;

/**
 * {@code TB-ACCT-12 t_cas24_selfcheck_item} CAS 24 自检条目（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>逐条自检结论与证据位置（FR-ACCT-14）。条目级随自检头确定子账户（§4.6.3 ③，无该列）。
 */
public final class Cas24SelfcheckItem extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 所属自检批次。 */
    public Long selfcheckId;

    /** 条目序号。 */
    public Integer itemSeq;

    /** 条款代码：{@code FORMAL_DESIGNATION} / {@code WRITTEN_DOC} / {@code EFFECTIVENESS_RECORDS} / {@code REBALANCE_RECORDS} / {@code DUAL_PNL_PRESENTED}。 */
    public String itemCode;

    /** 条款名称。 */
    public String itemName;

    /** 条目结论：{@code PASS} / {@code FAIL} / {@code NOT_APPLICABLE}。 */
    public String result;

    /** 证据类型（表名 / 文档编号）。 */
    public String evidenceType;

    /** 证据对象 ID。 */
    public Long evidenceId;

    /** 证据位置说明（FR-ACCT-14 验收 1）。 */
    public String evidenceDesc;

    /** 不通过原因。 */
    public String failReason;
}
