package com.hedgeguard.web.handler.config;

import com.hedgeguard.dao.entity.ParamDispatch;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

/**
 * 参数下发记录 {@code GET /api/v1/config/dispatch/{id}}（{@code T-109}；{@code FR-CONFIG-6} / {@code R-CONFIG-3}）。
 *
 * <p>只读：查询一次下发的留痕（下发类型、参数版本、状态 {@code PENDING/SUCCESS/FAIL/UNCONFIRMED}、
 * 回执详情、重试次数）。{@code R-CONFIG-3}：无回执或失败<b>不得视为已生效</b>，故状态字段即该口径载体。
 */
public final class ParamDispatchHandler implements RouteHandler {

    /** 注册路径模板（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/dispatch/{id}");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public ParamDispatchHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long id = parseId(ctx.pathParam("id"), "id");
        ParamDispatch dispatch = configService.getDispatch(id);
        if (dispatch == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "下发记录不存在：id=" + id + "；复现=核对 t_param_dispatch 主键");
        }
        return HttpResult.json(200, ApiResponse.ok(DispatchView.of(dispatch), ctx.traceId()));
    }

    /** 解析十进制整数路径参数。 */
    private static long parseId(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "参数必填：" + field + "；复现=补齐路径参数");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "参数须为十进制整数：" + field + "=" + raw + "；复现=改正取值", e);
        }
    }

    /** 下发记录视图（时间 ISO-8601）。 */
    public static final class DispatchView {

        /** 主键。 */
        public long id;

        /** 参数版本。 */
        public long paramVersion;

        /** 下发类型。 */
        public String dispatchType;

        /** 涉及子账户（可空）。 */
        public Integer subAccountId;

        /** 分批序号（可空）。 */
        public Integer batchIndex;

        /** 分批总数（可空）。 */
        public Integer batchTotal;

        /** 下发内容摘要。 */
        public String payloadDigest;

        /** 记录条数。 */
        public int recordCount;

        /** 下发状态（{@code PENDING}/{@code SUCCESS}/{@code FAIL}/{@code UNCONFIRMED}）。 */
        public String dispatchStatus;

        /** 回执时间。 */
        public String ackTime;

        /** 回执详情。 */
        public String ackDetail;

        /** 重试次数。 */
        public int retryCount;

        /** 操作人。 */
        public long operatorId;

        /** 下发时间。 */
        public String dispatchTime;

        static DispatchView of(ParamDispatch dispatch) {
            DispatchView view = new DispatchView();
            view.id = dispatch.id;
            view.paramVersion = dispatch.paramVersion;
            view.dispatchType = dispatch.dispatchType;
            view.subAccountId = dispatch.subAccountId;
            view.batchIndex = dispatch.batchIndex;
            view.batchTotal = dispatch.batchTotal;
            view.payloadDigest = dispatch.payloadDigest;
            view.recordCount = dispatch.recordCount;
            view.dispatchStatus = dispatch.dispatchStatus;
            view.ackTime = dispatch.ackTime == null ? null : dispatch.ackTime.toString();
            view.ackDetail = dispatch.ackDetail;
            view.retryCount = dispatch.retryCount;
            view.operatorId = dispatch.operatorId;
            view.dispatchTime = dispatch.dispatchTime == null ? null : dispatch.dispatchTime.toString();
            return view;
        }
    }
}
