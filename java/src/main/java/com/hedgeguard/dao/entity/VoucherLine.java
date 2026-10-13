package com.hedgeguard.dao.entity;

import java.math.BigDecimal;

/**
 * {@code TB-ACCT-5 t_voucher_line} 凭证分录行（{@code DATA_MODEL.md} §2.10）。
 *
 * <p>科目来自 CONFIG 科目字典（不得硬编码，{@code R-ACCT-11}）；金额必须 &gt; 0（方向由
 * {@code direction} 表达，§4.3）。借贷平衡为跨行聚合约束，不由 DB 保证（§4.4 唯一例外），
 * 由服务层同事务校验 + {@code t_voucher.total_amount} 冗余。
 */
public final class VoucherLine extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 子账户（冗余自凭证头，便于直接按子账户聚合金额）。 */
    public Integer subAccountId;

    /** 所属凭证。 */
    public Long voucherId;

    /** 分录序号。 */
    public Integer lineNo;

    /** 会计科目编码（来自字典）。 */
    public String subjectCode;

    /** 科目名称（冗余，便于导出）。 */
    public String subjectName;

    /** 记账方向：{@code DEBIT}（借）/ {@code CREDIT}（贷）。 */
    public String direction;

    /** 金额；必须 &gt; 0。 */
    public BigDecimal amount;

    /** 数量（如成交手数）。 */
    public BigDecimal qty;

    /** 关联成交记录（追溯链：凭证 → 交易）。 */
    public Long tradeFillId;

    /** 业务摘要（凭证摘要）。 */
    public String bizDesc;
}
