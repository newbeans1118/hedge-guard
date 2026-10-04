package com.hedgeguard.dao.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code TB-SETTLE-13 t_check_task} 核对任务（月度/季度/年度；需指定角色签署）（{@code DATA_MODEL.md} §2.7）。
 *
 * <p>实体采用 public 字段、不写 getter/setter（{@code java/CONTRACTS.md} §2.3 冻结约定）；
 * 通用审计字段（{@code §0.3}）经 {@link AuditedEntity} 复用，不在本类重复声明。
 */
public final class SettleCheckTask extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 核对类型。 */
    public String checkType;

    /** 期间键。 */
    public String periodKey;

    /** 涉及子账户（跨子账户取哨兵值 0）。 */
    public Integer subAccountId;

    /** 应签角色清单。 */
    public String requiredSignRoles;

    /** 已签角色。 */
    public String signedRoles;

    /** 任务状态。 */
    public String status;

    /** 核对对象快照哈希。 */
    public String snapshotHash;

    /** 快照内容。 */
    public String snapshotJson;

    /** 核对项。 */
    public String checkItems;

    /** 核对结论与说明。 */
    public String checkConclusion;

    /** 季度报告内容。 */
    public String contentJson;

    /** 提交/签收对象。 */
    public String submittedTo;

    /** 签署截止日。 */
    public LocalDate deadlineDate;

    /** 逾期是否已告警。 */
    public String overdueAlerted;

    /** 任务生成时间。 */
    public LocalDateTime generatedTime;

    /** 全部签署完成时间。 */
    public LocalDateTime completedTime;
}
