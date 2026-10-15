package com.hedgeguard.web.handler.acct;

import com.hedgeguard.service.AcctService;
import com.hedgeguard.web.Router;

/**
 * {@code ACCT} 模块路由注册（{@code T-077} 建键 4 对；{@code T-078} 增再平衡 2 对；
 * {@code T-079} 增凭证/期间 7 对；路径逐字取自 {@code ApiRoutes.byModule().get("ACCT")}，
 * 其余 4 条路径（effect/dual-pnl/selfcheck/export）归 T-080 交付）。
 *
 * <pre>
 *   GET  /api/v1/acct/hedge-relation        套期关系分页查询（FR-ACCT-1）
 *   POST /api/v1/acct/hedge-relation        生成套期关系（正式指定；三要素门禁 R-ACCT-1）
 *   GET  /api/v1/acct/effectiveness         评估记录分页 / 监测复核清单 / 未评估提醒（FR-ACCT-2/4）
 *   POST /api/v1/acct/effectiveness         提交有效性评估（三项定性观察点，R-ACCT-2/Q-7）
 *   GET  /api/v1/acct/rebalance             再平衡史（按套期关系；idx_rebalance_relation_time，FR-ACCT-3）
 *   POST /api/v1/acct/rebalance             执行再平衡（四类触发 Q-8；重新指定 R-ACCT-3）
 *   GET  /api/v1/acct/voucher               凭证分页列表 / 详情（id 参数；FR-ACCT-8）
 *   POST /api/v1/acct/voucher               生成凭证草稿（DRAFT_ONLY 不做总账过账，CFG-09）/ 草稿删除
 *   POST /api/v1/acct/voucher/{id}/review   凭证审核（岗位分离 R-ACCT-6；FR-ACCT-7）
 *   POST /api/v1/acct/voucher/{id}/reverse  凭证冲销（反向凭证 + 双人复核 DC-032；FR-ACCT-13）
 *   GET  /api/v1/acct/period                期间分页 / 关账历史（periodKey 参数）
 *   POST /api/v1/acct/period                创建开放期间（关账动作载体）
 *   POST /api/v1/acct/period/{id}/close     关账/撤销关账（三前置 + 双人复核 R-ACCT-8；FR-ACCT-12）
 * </pre>
 *
 * <p><b>注册序约束（{@code BUG-004}）</b>：占位符路由（voucher/{id}/review 等）在
 * 静态段之后注册，静态段优先匹配不受影响（Router 语义：静态 &gt; 占位符）。
 */
public final class AcctRoutes {

    private AcctRoutes() {
    }

    /**
     * 注册 {@code ACCT} 模块路由（T-077 的 4 对 + T-078 再平衡 2 对 + T-079 凭证/期间 7 对）。
     *
     * @param router      路由
     * @param acctService 套期会计服务
     */
    public static void register(Router router, AcctService acctService) {
        router.register("GET", HedgeRelationHandler.PATH, new HedgeRelationHandler(acctService));
        router.register("POST", HedgeRelationHandler.PATH, new HedgeRelationHandler(acctService));
        router.register("GET", EffectivenessHandler.PATH, new EffectivenessHandler(acctService));
        router.register("POST", EffectivenessHandler.PATH, new EffectivenessHandler(acctService));
        router.register("GET", RebalanceHandler.PATH, new RebalanceHandler(acctService));
        router.register("POST", RebalanceHandler.PATH, new RebalanceHandler(acctService));
        // T-079：凭证与期间面（7 对；先静态后占位符，注册序约束 BUG-004）。
        router.register("GET", VoucherHandler.PATH, new VoucherHandler(acctService));
        router.register("POST", VoucherHandler.PATH, new VoucherHandler(acctService));
        router.register("POST", VoucherReviewActionHandler.PATH,
                new VoucherReviewActionHandler(acctService));
        router.register("POST", VoucherReverseHandler.PATH, new VoucherReverseHandler(acctService));
        router.register("GET", VoucherPeriodHandler.PATH, new VoucherPeriodHandler(acctService));
        router.register("POST", VoucherPeriodHandler.PATH, new VoucherPeriodHandler(acctService));
        router.register("POST", PeriodCloseHandler.PATH, new PeriodCloseHandler(acctService));
    }
}
