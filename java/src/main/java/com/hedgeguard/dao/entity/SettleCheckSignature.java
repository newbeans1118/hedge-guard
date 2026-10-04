package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-SETTLE-15 t_check_signature} 核对签署记录（不可代签；uk_check_signature_task_role 下沉唯一约束）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleCheckSignature extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 涉及子账户（随任务头；跨子账户取哨兵值 0）。 */
    public Integer subAccountId;

    /** 所属核对任务。 */
    public Long checkTaskId;

    /** 签署角色。 */
    public String signRole;

    /** 签署人账号。 */
    public Long signerId;

    /** 签署人实际角色编码。 */
    public String signerRoleCode;

    /** 签署时间。 */
    public LocalDateTime signTime;

    /** 签署 IP。 */
    public String signIp;

    /** 签署时快照哈希。 */
    public String snapshotHash;

    /** 签署结果。 */
    public String signResult;

    /** 签署意见/异议说明。 */
    public String signOpinion;

    /** 是否因代签被拒。 */
    public String proxyRejected;

    /** 管理层签署权与交易权互斥核验留痕。 */
    public String manageRoleTradeBlocked;
}
