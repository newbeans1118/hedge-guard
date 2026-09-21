package com.hedgeguard.dao.entity;

/** {@code TB-CONFIG-5 t_account_subject} 会计科目字典（{@code DATA_MODEL.md} §2.1）。科目不得在代码中硬编码。 */
public final class AccountSubject extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 科目编码。 */
    public String subjectCode;

    /** 科目名称。 */
    public String subjectName;

    /** 上级科目编码（{@code null} = 一级科目）。 */
    public String parentCode;

    /** 科目级次。 */
    public int subjectLevel;

    /** 余额方向：{@code DEBIT} / {@code CREDIT}。 */
    public String direction;

    /** 适用业务场景。 */
    public String bizScene;

    /** 是否系统预置模板项。 */
    public String isTemplateDefault;

    /** 企业是否可修改。 */
    public String editableByEnterprise;

    /** {@code ENABLED} / {@code DISABLED}。 */
    public String status;
}
