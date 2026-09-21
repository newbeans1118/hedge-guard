package com.hedgeguard.bridge;

/**
 * 持仓视图（镜像状态的持仓分量，{@code ARCHITECTURE.md} §3.11 指标①实时持仓②浮动盈亏）。
 *
 * <p>数量 / 金额一律字符串化十进制（{@code §4.1.1}）。
 */
public final class PositionView {

    /** 子账户（行级归属）。 */
    public int subAccountId;

    /** 合约代码。 */
    public String instrumentId;

    /** 品种 ID。 */
    public long productId;

    /** 持仓量（手）。 */
    public String positionQty;

    /** 可用量（可平量，手）。 */
    public String availableQty;

    /** 持仓均价。 */
    public String avgPrice;

    /** 浮动盈亏。 */
    public String floatPnl;

    /** 保证金占用。 */
    public String marginOccupied;

    /** 无参构造（供 Gson 与事件流构建）。 */
    public PositionView() {
    }
}
