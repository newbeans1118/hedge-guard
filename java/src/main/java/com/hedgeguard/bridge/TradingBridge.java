package com.hedgeguard.bridge;

import java.util.concurrent.CompletableFuture;

/**
 * 组件桥接层对外接口（{@code ARCHITECTURE.md} §3.10，<b>签名逐字冻结</b>）。
 *
 * <p>纪律（附录 B 红线）：
 * <ul>
 *   <li><b>只提供</b> {@code placeOrder} / {@code confirmOrder} / {@code cancelOrder} / {@code dispatch}
 *       与状态查询；<b>不提供</b> {@code preCheck}——事前风控只在 C++ 组件内（{@code §3.0.3}）。</li>
 *   <li>本接口<b>不写库</b>；成交/委托/持仓等落库由事件流消费后经 DAO 完成（单一写库入口）。</li>
 *   <li>L3 只依赖本接口，测试时以 {@link com.hedgeguard.bridge.stub.InMemoryTradingBridge} 替换
 *       （{@code NFR-TEST-4}）。</li>
 *   <li><b>不得</b>以定时轮询同步两侧状态：{@link #mirror()} 为事件驱动构建的只读快照（{@code §3.10}）。</li>
 * </ul>
 *
 * <p>字段级协议对齐说明：HGCP 协议的字段级定义在 M2 门禁冻结（{@code §4.2}）。
 * 本接口与其 DTO 的<b>类型与方法签名</b>自 T-003 起冻结；DTO 字段集在本里程碑内变更须走 {@code CH-xxx}。
 */
public interface TradingBridge {

    /**
     * 下发交易指令至 C++ 组件（<b>本调用不写库</b>）。
     *
     * @param cmd 下单指令
     * @return 受理结果（含指令号与事前风控判定结果）
     */
    CompletableFuture<PlaceOrderResult> placeOrder(PlaceOrderCmd cmd);

    /**
     * 人工确认指令（人工确认是强制环节，{@code §2.2}）。
     *
     * @param orderId  C++ 指令号
     * @param operator 确认人（须与提交人一致，否则 {@code 0x0305}）
     * @return 回执
     */
    CompletableFuture<AckResult> confirmOrder(long orderId, String operator);

    /**
     * 撤单。
     *
     * @param orderId C++ 指令号
     * @param reason  撤单原因
     * @return 回执
     */
    CompletableFuture<AckResult> cancelOrder(long orderId, String reason);

    /**
     * 参数下发（改 Java 侧 → 下发 C++ → 等回执 → 留痕的统一入口，{@code §3.8 ParamDispatchService}）。
     *
     * @param change 参数变更
     * @return 回执（{@code 10014 参数下发未生效} 的判定依据）
     */
    CompletableFuture<AckResult> dispatch(ParamChange change);

    /**
     * C++ 组件是否 READY（启动重放完成、风控参数已加载）。
     *
     * @return 就绪返回 {@code true}；未就绪时业务侧须拒绝新指令（{@code 0x0401}）
     */
    boolean isReady();

    /**
     * 启动重放进度。
     *
     * @return 进度
     */
    ReplayProgress replayProgress();

    /**
     * 只读镜像状态（事中风控的数据来源；不可变快照）。
     *
     * @return 镜像快照
     */
    MirrorSnapshot mirror();
}
