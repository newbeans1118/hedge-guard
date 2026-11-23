package com.hedgeguard.bridge;

/**
 * 组件监控告警（阶段 3）。
 *
 * <p><b>承载口径</b>：经既有冻结通道 {@code /ws/alert} 的
 * {@code {"type":"alert","level","metric","value","threshold","subAccount","ts"}} 帧下发
 * （{@code java/CONTRACTS.md §4.1}）——<b>不新增 WS 端点、不新增通道、不改线格式</b>。
 * 组件告警一律取账户级哨兵 {@code subAccountId=0}，由 {@code WsAuthorizer} 按
 * {@code t_user_sub_account_perm} 授权集过滤（{@code W-2} 安全默认空集语义不变）。
 *
 * <p>不可变值对象；{@code code} 为去重键（同一条件持续成立期间只发一次，解除后可再次触发）。
 */
public final class ComponentAlert {

    /** 去重键，如 {@code component.state}。 */
    public final String code;

    /** {@code INFO / WARNING / CRITICAL}（与 {@code t_risk_alert.ck_risk_alert_level} 同值域）。 */
    public final String level;

    /** 指标名（前端可按 {@code component.} 前缀过滤）。 */
    public final String metric;

    /** 当前值（字符串化，与 WS 数值格式口径一致）。 */
    public final String value;

    /** 阈值（字符串化）。 */
    public final String threshold;

    /** 人读说明。 */
    public final String detail;

    /** 触发时点（epoch ms）。 */
    public final long tsMs;

    /** 触发时点（ISO-8601）。 */
    public final String ts;

    /**
     * @param code      去重键
     * @param level     级别
     * @param metric    指标名
     * @param value     当前值
     * @param threshold 阈值
     * @param detail    说明
     * @param tsMs      触发时点（epoch ms）
     * @param ts        触发时点（ISO-8601）
     */
    public ComponentAlert(String code, String level, String metric, String value,
                          String threshold, String detail, long tsMs, String ts) {
        this.code = code;
        this.level = level;
        this.metric = metric;
        this.value = value;
        this.threshold = threshold;
        this.detail = detail;
        this.tsMs = tsMs;
        this.ts = ts;
    }
}
