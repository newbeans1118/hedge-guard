package com.hedgeguard.bridge;

/**
 * 资金视图（镜像状态的资金分量，{@code ARCHITECTURE.md} §3.11
 * 指标③保证金占用率④风险度⑤资金可用量）。
 *
 * <p>金额一律字符串化十进制（{@code §4.1.1}）。
 */
public final class FundView {

    /** 子账户（行级归属）。 */
    public int subAccountId;

    /** 资金余额。 */
    public String balance;

    /** 可用资金。 */
    public String available;

    /** 保证金占用。 */
    public String marginOccupied;

    /** 风险度（占用 / 权益）。 */
    public String riskRatio;

    /** 无参构造（供 Gson 与事件流构建）。 */
    public FundView() {
    }
}
