package com.hedgeguard.web.handler.fund;

import com.hedgeguard.service.FundService;
import com.hedgeguard.web.Router;

/**
 * {@code FUND} 模块路由注册（{@code T-049}；路径逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。
 *
 * <pre>
 *   POST /api/v1/fund/ledger                 建立子账户资金台账（FR-FUND-1）
 *   GET  /api/v1/fund/ledger                 资金台账查询（FR-FUND-3）
 *   GET  /api/v1/fund/flow                   资金流水查询（FR-FUND-3/16）
 *   POST /api/v1/fund/deposit                发起入金（FR-FUND-5）
 *   POST /api/v1/fund/withdraw               发起出金（FR-FUND-6）
 *   POST /api/v1/fund/{id}/review            出入金双人复核（FR-FUND-7）
 *   POST /api/v1/fund/receipt                处理 CTP 回执（FR-FUND-8）
 *   GET  /api/v1/fund/transfer               划拨记录查询（FR-FUND-16）
 *   POST /api/v1/fund/transfer               发起划拨（FR-FUND-10）
 *   POST /api/v1/fund/transfer/{id}/approve  划拨审批 + 记账 + 加总校验（FR-FUND-11/12）
 *   GET  /api/v1/fund/quota                  额度查询（FR-FUND-13）
 *   POST /api/v1/fund/quota                  额度设定（FR-FUND-13）
 *   GET  /api/v1/fund/margin                 保证金占用视图（FR-FUND-15）
 *   POST /api/v1/fund/reconcile              每日资金对账（FR-FUND-17）
 *   GET  /api/v1/fund/export                 资金对账导出（FR-FUND-4）
 * </pre>
 *
 * <p><b>注册序约束（{@code BUG-004}）</b>：{@code Router.match} 按注册序首次命中。
 * 本模块占位符路由 {@code /fund/{id}/review}（3 段）与 {@code /fund/transfer/{id}/approve}（4 段）
 * 与静态 2 段路由段数不同，无遮蔽关系；仍按「先静态、后占位符」书写。
 */
public final class FundRoutes {

    private FundRoutes() {
    }

    /**
     * 注册 {@code FUND} 模块路由。
     *
     * @param router      路由
     * @param fundService 资金服务
     */
    public static void register(Router router, FundService fundService) {
        // 先静态（2 段）
        router.register("GET", FundLedgerHandler.PATH, new FundLedgerHandler(fundService));
        router.register("POST", FundLedgerHandler.PATH, new FundLedgerHandler(fundService));
        router.register("GET", FundFlowHandler.PATH, new FundFlowHandler(fundService));
        router.register("GET", FundTransferHandler.PATH, new FundTransferHandler(fundService));
        router.register("POST", FundTransferHandler.PATH, new FundTransferHandler(fundService));
        router.register("GET", FundQuotaHandler.PATH, new FundQuotaHandler(fundService));
        router.register("POST", FundQuotaHandler.PATH, new FundQuotaHandler(fundService));
        router.register("GET", FundMarginHandler.PATH, new FundMarginHandler(fundService));
        router.register("POST", FundReconcileHandler.PATH, new FundReconcileHandler(fundService));
        router.register("GET", FundExportHandler.PATH, new FundExportHandler(fundService));
        for (FundApplyHandler.Action action : FundApplyHandler.Action.values()) {
            router.register("POST", action.path(), new FundApplyHandler(fundService, action));
        }
        router.register("POST", FundReceiptHandler.PATH, new FundReceiptHandler(fundService));
        // 后占位符（3 段 / 4 段，不与静态 2 段冲突）
        router.register("POST", FundReviewHandler.PATH, new FundReviewHandler(fundService));
        router.register("POST", FundTransferApproveHandler.PATH,
                new FundTransferApproveHandler(fundService));
    }
}
