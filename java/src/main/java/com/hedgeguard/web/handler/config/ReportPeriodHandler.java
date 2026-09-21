package com.hedgeguard.web.handler.config;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.ReportPeriodConfig;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告周期配置 {@code GET|POST /api/v1/config/report-period}（{@code T-109}；{@code FR-CONFIG-15}）。
 *
 * <p>{@code GET}：列出报告周期。{@code POST}：按 {@code reportType} 新增 / 更新。
 * <b>合规必需报告不得被关闭</b>（{@code isMandatory='Y'} ⇒ {@code isEnabled='Y'}，{@code R-CONFIG-7}）。
 */
public final class ReportPeriodHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/report-period");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public ReportPeriodHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                    ctx.queryParam("sort"), "id");
            PageResult<ReportPeriodConfig> page = configService.listReportPeriods(query);
            List<PeriodView> items = new ArrayList<>();
            for (ReportPeriodConfig cfg : page.items()) {
                items.add(PeriodView.of(cfg));
            }
            PageData<PeriodView> data = new PageData<>(items, page.total(), page.page(), page.size());
            return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
        }
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {reportType, periodUnit, periodValue, remindRoleCodes, ...}："
                            + "复现=按 FR-CONFIG-15 提交");
        }
        ReportPeriodConfig cfg = new ReportPeriodConfig();
        cfg.id = request.id;
        cfg.reportType = request.reportType;
        cfg.periodUnit = request.periodUnit;
        cfg.periodValue = request.periodValue;
        cfg.remindAdvanceDays = request.remindAdvanceDays;
        cfg.remindRoleCodes = request.remindRoleCodes;
        cfg.isMandatory = request.isMandatory;
        cfg.isEnabled = request.isEnabled;
        long id = configService.saveReportPeriod(cfg, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 写入请求体。 */
    public static final class Request {

        /** 主键（{@code > 0} 更新；否则按 {@code reportType} 新增）。 */
        public long id;

        /** 报告类型（必填）。 */
        public String reportType;

        /** 周期单位（{@code DAY}/{@code MONTH}/{@code QUARTER}/{@code YEAR}）。 */
        public String periodUnit;

        /** 周期数量。 */
        public int periodValue;

        /** 提前提醒天数。 */
        public int remindAdvanceDays;

        /** 提醒接收角色编码（逗号分隔）。 */
        public String remindRoleCodes;

        /** 是否合规必需。 */
        public String isMandatory;

        /** 是否启用。 */
        public String isEnabled;
    }

    /** 报告周期视图。 */
    public static final class PeriodView {

        /** 主键。 */
        public long id;

        /** 报告类型。 */
        public String reportType;

        /** 周期单位。 */
        public String periodUnit;

        /** 周期数量。 */
        public int periodValue;

        /** 提前提醒天数。 */
        public int remindAdvanceDays;

        /** 提醒接收角色编码。 */
        public String remindRoleCodes;

        /** 是否合规必需。 */
        public String isMandatory;

        /** 是否启用。 */
        public String isEnabled;

        static PeriodView of(ReportPeriodConfig cfg) {
            PeriodView view = new PeriodView();
            view.id = cfg.id;
            view.reportType = cfg.reportType;
            view.periodUnit = cfg.periodUnit;
            view.periodValue = cfg.periodValue;
            view.remindAdvanceDays = cfg.remindAdvanceDays;
            view.remindRoleCodes = cfg.remindRoleCodes;
            view.isMandatory = cfg.isMandatory;
            view.isEnabled = cfg.isEnabled;
            return view;
        }
    }
}
