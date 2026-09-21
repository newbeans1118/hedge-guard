package com.hedgeguard.bridge;

/**
 * 通用回执（确认 / 撤单 / 参数下发）。
 *
 * <p>{@code dispatch} 的回执是"参数是否已生效"的唯一判据：{@code componentCode != 0} 或
 * {@code acknowledged == false} 时，业务侧<b>不得</b>视为已生效（{@code 10014}，{@code R-CONFIG-3}）。
 */
public final class AckResult {

    /** 是否收到成功回执。 */
    public boolean acknowledged;

    /** 组件错误码（{@code §4.3.2}）；{@code 0} 表示成功。 */
    public int componentCode;

    /** 提示语。 */
    public String message;

    /** 回执携带的 {@code param_version}（下发场景用于比对是否生效）。 */
    public long paramVersion;

    /** 回执时间（服务端写入，ISO-8601）。 */
    public String ackTime;

    /** 错误码可映射时附带的规则编号。 */
    public String ruleId;

    /** 当前值（字符串化十进制）。 */
    public String current;

    /** 阈值（字符串化十进制）。 */
    public String threshold;

    /** 下一步建议。 */
    public String nextStep;
}
