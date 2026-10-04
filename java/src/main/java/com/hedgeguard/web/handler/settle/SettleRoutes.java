package com.hedgeguard.web.handler.settle;

import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.Router;

/**
 * {@code SETTLE} 模块路由注册（{@code T-047}；路径逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。
 *
 * <pre>
 *   POST /api/v1/settle/ctp-data                CTP 日终数据接收（FR-SETTLE-1）
 *   GET  /api/v1/settle/cycle                   历史结算记录查询（FR-SETTLE-15）
 *   POST /api/v1/settle/cycle                   子账户拆分 + 独立结算 + 三类核对（FR-SETTLE-2/3/4/5/6/7）
 *   POST /api/v1/settle/cycle/{id}/reconcile    重跑三类核对（FR-SETTLE-5/6/7）
 *   POST /api/v1/settle/cycle/{id}/aggregate    加总对账（FR-SETTLE-8）
 *   POST /api/v1/settle/cycle/{id}/close        关账门禁（R-SETTLE-5）
 *   PUT  /api/v1/settle/difference              差异处理（FR-SETTLE-9）
 *   GET  /api/v1/settle/report                  每日结算报告 / 风控日报（FR-SETTLE-10/12）
 *   GET  /api/v1/settle/margin                  保证金与风险度视图（FR-SETTLE-11）
 *   POST /api/v1/settle/stress-test             场景化压力测试（FR-SETTLE-11）
 *   GET  /api/v1/settle/export                  导出结算报表（FR-SETTLE-16）
 * </pre>
 *
 * <p><b>最小注册面</b>：{@code /settle/monthly-check}、{@code /settle/quarterly-report} 属核对任务与
 * 电子签署（{@code FR-SETTLE-13/14/17}），归 {@code T-048}，本任务不注册（保持"已声明未注册"）。
 *
 * <p><b>注册序约束（{@code BUG-004}）</b>：{@code Router.match} 按注册序首次命中。本模块占位符路由
 * {@code /settle/cycle/{id}/...}（6 段）与静态 {@code /settle/cycle}（3 段）、其它静态 3 段路由段数不同，
 * 无遮蔽关系；仍按「先静态、后占位符」书写。
 */
public final class SettleRoutes {

    private SettleRoutes() {
    }

    /**
     * 注册 {@code SETTLE} 模块路由。
     *
     * @param router        路由
     * @param settleService 结算服务
     */
    public static void register(Router router, SettleService settleService) {
        // 先静态
        router.register("POST", SettleCtpDataHandler.PATH, new SettleCtpDataHandler(settleService));
        router.register("GET", SettleCycleHandler.PATH, new SettleCycleHandler(settleService));
        router.register("POST", SettleCycleHandler.PATH, new SettleCycleHandler(settleService));
        router.register("PUT", SettleDifferenceHandler.PATH, new SettleDifferenceHandler(settleService));
        router.register("GET", SettleReportHandler.PATH, new SettleReportHandler(settleService));
        router.register("GET", SettleMarginHandler.PATH, new SettleMarginHandler(settleService));
        router.register("POST", SettleStressTestHandler.PATH, new SettleStressTestHandler(settleService));
        router.register("GET", SettleExportHandler.PATH, new SettleExportHandler(settleService));
        // 后占位符（6 段，不与静态同段数冲突）
        for (SettleCycleActionHandler.Action action : SettleCycleActionHandler.Action.values()) {
            router.register("POST", action.path(), new SettleCycleActionHandler(settleService, action));
        }
    }
}
