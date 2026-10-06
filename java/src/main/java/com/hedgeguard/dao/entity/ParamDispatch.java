package com.hedgeguard.dao.entity;

import java.time.LocalDateTime;

/**
 * {@code TB-RISK-2 t_param_dispatch} 参数下发记录（{@code DATA_MODEL.md} §2.8）。
 *
 * <p>用途：Java → C++ 的控制指令下发与回执（{@code FR-RISK-9}/{@code FR-RISK-10}/{@code FR-CONFIG-6}、
 * {@code R-CONFIG-3}）。**无回执或失败时该变更不得视为已生效**。
 *
 * <p>本表可变更（`dispatch_status` 由 {@code PENDING} → {@code SUCCESS}/{@code FAIL}/{@code UNCONFIRMED}，
 * `retry_count` 递增），故为**普通表**：含 §0.3 全部 6 项审计字段，非只增不改表。
 */
public final class ParamDispatch extends AuditedEntity {

    /** 主键。 */
    public long id;

    /** 单调递增的参数版本（{@code R-CONFIG-3} 顺序保证）。<b>分配须服务层串行化</b>（本表无 UNIQUE，见契约 `D-16`）。 */
    public long paramVersion;

    /** 下发类型：{@code LOAD_RISK_PARAMS}/{@code UPDATE_WHITELIST}/{@code UPDATE_QUOTA}/{@code UPDATE_POSITION_LIMIT}/{@code UPDATE_RISK_THRESHOLD}/{@code UPDATE_PERMISSION}/{@code UPDATE_EXPOSURE}/{@code SET_TRADING_SUSPEND}/{@code SET_READY_STATE}/{@code TRIGGER_REPLAY}/{@code RELOAD}（{@code CH-021} 扩域）。 */
    public String dispatchType;

    /** 涉及子账户（若为子账户级变更）；可空。 */
    public Integer subAccountId;

    /** 分批序号（全量预加载）。 */
    public Integer batchIndex;

    /** 分批总数（全量预加载；须收齐全部批次）。 */
    public Integer batchTotal;

    /** 下发内容摘要（SHA-256），用于核对两侧一致。 */
    public String payloadDigest;

    /** 本次下发的记录条数（加载条数可校验，{@code FR-RISK-9} 验收 3）。 */
    public int recordCount;

    /** 下发状态：{@code PENDING}/{@code SUCCESS}/{@code FAIL}/{@code UNCONFIRMED}（超时）。 */
    public String dispatchStatus;

    /** 回执时间。 */
    public LocalDateTime ackTime;

    /** 回执详情（或 {@code 0x0403} 等错误）。 */
    public String ackDetail;

    /** 重试次数。 */
    public int retryCount;

    /** 操作人。 */
    public long operatorId;

    /** 下发时间。 */
    public LocalDateTime dispatchTime;
}
