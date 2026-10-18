package com.hedgeguard.web.handler.acct;

import com.hedgeguard.service.AcctService;
import com.hedgeguard.service.Cas24SelfcheckService;
import com.hedgeguard.web.Router;

/**
 * {@code ACCT} 模块路由注册（{@code T-077} 建键 4 对；{@code T-078} 增再平衡 2 对；
 * {@code T-079} 增凭证/期间 7 对；{@code T-080} 增效果评估/双侧盈亏上报 4 对；
 * {@code T-197} 增 CAS 24 自检面（selfcheck GET/POST + export GET）3 对 ⇒ 共 20 对；
 * 路径逐字取自 {@code ApiRoutes.byModule().get("ACCT")}，至此 ACCT 12 条已声明路径
 * 全部注册完毕）。
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
 *   GET  /api/v1/acct/effect                套保效果视图（同屏双栏 + 汇总/明细；FR-ACCT-5）
 *   POST /api/v1/acct/effect                计算套保效果（双侧盈亏 + Q-12 三态；FR-ACCT-5）
 *   GET  /api/v1/acct/report/dual-pnl       双侧盈亏上报记录查询（留痕三件；FR-ACCT-10）
 *   POST /api/v1/acct/report/dual-pnl       提交双侧盈亏上报（两栏恒在 R-ACCT-4；FR-ACCT-10）
 *   GET  /api/v1/acct/selfcheck             自检清单分页 / 单批次详情（selfcheckNo 参数；FR-ACCT-14，T-197）
 *   POST /api/v1/acct/selfcheck             发起 CAS 24 自检（五条逐条判定 + 证据位置；缺再平衡记录 ⇒ 不通过）
 *   GET  /api/v1/acct/export                自检报告导出 CSV（含自检时间与执行人；FR-ACCT-14 验收 3/4，T-197）
 * </pre>
 *
 * <p><b>注册序约束（{@code BUG-004}）</b>：占位符路由（voucher/{id}/review 等）在
 * 静态段之后注册，静态段优先匹配不受影响（Router 语义：静态 &gt; 占位符）。
 * 本批 3 对均为纯静态段（selfcheck/export），追加于占位符路由之后不引入新遮蔽面。
 */
public final class AcctRoutes {

    private AcctRoutes() {
    }

    /**
     * 注册 {@code ACCT} 模块路由（T-077 的 4 对 + T-078 再平衡 2 对 + T-079 凭证/期间 7 对
     * + T-080 效果/上报 4 对 + T-197 自检面 3 对 = 20 对）。
     *
     * @param router              路由
     * @param acctService         套期会计服务
     * @param cas24SelfcheckService CAS 24 自检服务（T-197；可空 = 仅装配期注册不驱动业务）
     */
    public static void register(Router router, AcctService acctService,
                                Cas24SelfcheckService cas24SelfcheckService) {
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
        // T-080：效果评估与双侧盈亏上报面（4 对；GET|POST 各一路径，同一 handler 分流）。
        router.register("GET", EffectHandler.PATH, new EffectHandler(acctService));
        router.register("POST", EffectHandler.PATH, new EffectHandler(acctService));
        router.register("GET", DualPnlReportHandler.PATH, new DualPnlReportHandler(acctService));
        router.register("POST", DualPnlReportHandler.PATH, new DualPnlReportHandler(acctService));
        // T-197：CAS 24 自检面（3 对；纯静态段，注册于占位符路由之后——BUG-004 序约束不变）。
        router.register("GET", SelfcheckHandler.PATH, new SelfcheckHandler(cas24SelfcheckService));
        router.register("POST", SelfcheckHandler.PATH, new SelfcheckHandler(cas24SelfcheckService));
        router.register("GET", SelfcheckExportHandler.PATH,
                new SelfcheckExportHandler(cas24SelfcheckService));
    }
}
