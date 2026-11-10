package com.hedgeguard.web.handler.plan;

import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.service.PlanService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 读取当前生效书面文件<strong>正文</strong> {@code GET /api/v1/plan/{id}/doc}
 * （补齐 {@code FR-PLAN-5} 详情页「正文读取缺端点」登记）。
 *
 * <p>路径逐字取自 {@code ARCHITECTURE.md §4.1.1} PLAN 行（已声明 {@code /plan/{id}/doc}），
 * 与 {@code POST /api/v1/plan/{id}/doc}（{@link PlanDocHandler} 生成）同路径不同方法。
 *
 * <p>响应 {@code data} 为 {@link DocView}（camelCase wire 字段）；尚无当前版本书面文件时
 * {@code data} 省略（{@code null}），前端按「未生成」展示——这不是错误，不抛异常。
 * 时间字段 {@code generatedTime} 由 {@code JsonCodec} 的 {@code LocalDateTime} 适配器统一
 * 序列化为 {@code yyyy-MM-ddTHH:mm:ss[.fraction]}（{@code T-235} 秒恒下发口径）。
 *
 * <p>子账户隔离由 {@link PlanService#currentDoc} 读面 scope 承载，不可见与不存在返回同一错误。
 */
public final class PlanDocReadHandler implements RouteHandler {

    /** 注册路径模板（{@code {id}} 独占整段）。 */
    public static final String PATH = ApiRoutes.full("/plan/{id}/doc");

    private final PlanService planService;

    /**
     * 构造。
     *
     * @param planService 计划服务
     */
    public PlanDocReadHandler(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long planId = PlanRoutes.parseId(ctx.pathParam("id"), "id");
        HedgeDoc doc = planService.currentDoc(planId, ctx.currentUserId());
        return HttpResult.json(200,
                ApiResponse.ok(doc == null ? null : DocView.of(doc), ctx.traceId()));
    }

    /** 书面文件正文视图（camelCase wire 字段）。 */
    public static final class DocView {

        /** 文件编号（全系统唯一）。 */
        public String docNo;

        /** 版本号。 */
        public Integer docVersion;

        /** 是否当前版本（{@code Y}/{@code N}）。 */
        public String isCurrent;

        /** 要素①正式指定（CLOB 正文）。 */
        public String elemFormalDesignation;

        /** 要素②有效性评估方法（CLOB 正文）。 */
        public String elemEffectivenessMethod;

        /** 要素③再平衡机制（CLOB 正文）。 */
        public String elemRebalanceMechanism;

        /** 被套期项目（实货敞口 ID）。 */
        public Long hedgedItemExposureId;

        /** 套期工具（合约 / 持仓标识）。 */
        public String hedgingInstrument;

        /** "待外部复核"标注（{@code R-ACCT-10}，恒 {@code Y}）。 */
        public String pendingExternalReview;

        /** 生成时间（{@code JsonCodec} 适配器序列化）。 */
        public java.time.LocalDateTime generatedTime;

        static DocView of(HedgeDoc doc) {
            DocView view = new DocView();
            view.docNo = doc.docNo;
            view.docVersion = doc.docVersion;
            view.isCurrent = doc.isCurrent;
            view.elemFormalDesignation = doc.elemFormalDesignation;
            view.elemEffectivenessMethod = doc.elemEffectivenessMethod;
            view.elemRebalanceMechanism = doc.elemRebalanceMechanism;
            view.hedgedItemExposureId = doc.hedgedItemExposureId;
            view.hedgingInstrument = doc.hedgingInstrument;
            view.pendingExternalReview = doc.pendingExternalReview;
            view.generatedTime = doc.generatedTime;
            return view;
        }
    }
}
