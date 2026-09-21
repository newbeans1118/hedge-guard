package com.hedgeguard.web.handler.config;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.ConfigChangeLog;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置变更留痕 {@code GET /api/v1/config/change-log}（{@code T-109}；{@code FR-CONFIG-12}）。
 *
 * <p>筛选：配置分类 {@code category}、时间范围 {@code from}~{@code to}（{@code yyyy-MM-dd}，闭区间）；
 * 分页 / 排序按 {@code §4.1.1}（{@code size ∈ {10,20,50,100}}，默认 20）。记录<b>只增不改不删</b>，
 * 敏感字段的值恒不记录（仅 {@code sensitiveState}）。
 */
public final class ConfigChangeLogHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/change-log");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public ConfigChangeLogHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "change_time");
        PageResult<ConfigChangeLog> page = configService.listChangeLogs(query,
                ctx.queryParam("category"), ctx.queryParam("from"), ctx.queryParam("to"));
        List<LogView> items = new ArrayList<>();
        for (ConfigChangeLog log : page.items()) {
            items.add(LogView.of(log));
        }
        PageData<LogView> data = new PageData<>(items, page.total(), page.page(), page.size());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    /** 变更留痕视图（时间 ISO-8601；敏感字段无取值列）。 */
    public static final class LogView {

        /** 主键。 */
        public long id;

        /** 配置项 Key。 */
        public String configKey;

        /** 配置分类。 */
        public String configCategory;

        /** 变更前值（敏感字段恒为空）。 */
        public String beforeValue;

        /** 变更后值（敏感字段恒为空）。 */
        public String afterValue;

        /** 是否敏感字段变更。 */
        public String isSensitiveChange;

        /** 敏感字段变更前状态。 */
        public String sensitiveStateBefore;

        /** 敏感字段变更后状态。 */
        public String sensitiveStateAfter;

        /** 操作人。 */
        public long operatorId;

        /** 复核人。 */
        public Long reviewerId;

        /** 变更原因。 */
        public String changeReason;

        /** 下发结果。 */
        public String dispatchResult;

        /** 变更时间（ISO-8601）。 */
        public String changeTime;

        static LogView of(ConfigChangeLog log) {
            LogView view = new LogView();
            view.id = log.id;
            view.configKey = log.configKey;
            view.configCategory = log.configCategory;
            view.beforeValue = log.beforeValue;
            view.afterValue = log.afterValue;
            view.isSensitiveChange = log.isSensitiveChange;
            view.sensitiveStateBefore = log.sensitiveStateBefore;
            view.sensitiveStateAfter = log.sensitiveStateAfter;
            view.operatorId = log.operatorId;
            view.reviewerId = log.reviewerId;
            view.changeReason = log.changeReason;
            view.dispatchResult = log.dispatchResult;
            view.changeTime = log.changeTime == null ? null : log.changeTime.toString();
            return view;
        }
    }
}
