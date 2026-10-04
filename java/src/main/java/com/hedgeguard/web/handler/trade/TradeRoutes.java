package com.hedgeguard.web.handler.trade;

import com.hedgeguard.service.TradeService;
import com.hedgeguard.web.Router;

/**
 * {@code TRADE} 模块路由注册（{@code T-046}；路径逐字取自 {@code ApiRoutes.byModule().get("TRADE")}）。
 *
 * <pre>
 *   POST /api/v1/trade/order                 提交交易指令（不落库，经桥接下 C++；FR-TRADE-1）
 *   POST /api/v1/trade/order/{id}/confirm    人工确认后发出（FR-TRADE-2）
 *   POST /api/v1/trade/order/{id}/cancel     撤单（FR-TRADE-6）
 * </pre>
 *
 * <p><b>最小注册面</b>：本任务（{@code T-046}）只注册 3 条「写 / 动作」路径（交易执行主链）；其余
 * {@code ApiRoutes} 已声明但尚未注册的 TRADE 读取面（{@code /trade/order-report}、{@code /trade/fill}、
 * {@code /trade/position}、{@code /trade/reason}、{@code /trade/reject-record}、{@code /trade/flow}、
 * {@code /trade/quote/{instrument}}、{@code /trade/strategy}、{@code /trade/export}）不在本任务范围，
 * 保持「已声明未注册」并由后续任务承接。
 *
 * <p><b>注册序约束（{@code BUG-004}）</b>：{@code Router.match} 按注册序首次命中，占位符路由须最后注册。
 * 本模块占位符路由为 {@code /trade/order/{id}/confirm|cancel}（5 段），与静态 {@code /trade/order}（4 段）
 * 段数不同，无遮蔽关系；此处仍按「先静态、后占位符」书写以防未来新增同段数静态路由。
 */
public final class TradeRoutes {

    private TradeRoutes() {
    }

    /**
     * 注册 {@code TRADE} 模块路由。
     *
     * @param router       路由
     * @param tradeService 交易服务
     */
    public static void register(Router router, TradeService tradeService) {
        // 先静态
        router.register("POST", TradeOrderHandler.PATH, new TradeOrderHandler(tradeService));
        // 后占位符（5 段，不与静态同段数冲突）
        router.register("POST", TradeOrderActionHandler.Action.CONFIRM.path(),
                new TradeOrderActionHandler(tradeService, TradeOrderActionHandler.Action.CONFIRM));
        router.register("POST", TradeOrderActionHandler.Action.CANCEL.path(),
                new TradeOrderActionHandler(tradeService, TradeOrderActionHandler.Action.CANCEL));
    }
}
