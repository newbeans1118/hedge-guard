package com.hedgeguard.web.handler.config;

import com.hedgeguard.dao.entity.ConfigItem;
import com.hedgeguard.service.ConfigService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置项 {@code GET|PUT /api/v1/config/item}（{@code T-009}）。
 *
 * <p>{@code GET ?key=…} 取单项；{@code GET ?category=…} 取分类列表；{@code PUT} 变更取值
 * （敏感项一律 {@code 10020}；绑定键非回环 {@code 10021}；须双人复核者须带 {@code reviewerId}）。
 *
 * <p><b>零落盘</b>：响应中的敏感项<b>恒</b>只回 {@code sensitiveState}，<b>不</b>回取值
 * （{@code NFR-SEC-1} / {@code R-CONFIG-2}）。
 */
public final class ConfigItemHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/item");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public ConfigItemHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            String key = ctx.queryParam("key");
            if (key != null && !key.isEmpty()) {
                ConfigItem item = configService.getConfig(key);
                if (item == null) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "配置项不存在：config_key=" + key + "；复现=核对键名或先新增该配置项");
                }
                return HttpResult.json(200, ApiResponse.ok(ConfigItemView.of(item), ctx.traceId()));
            }
            String category = ctx.queryParam("category");
            if (category == null || category.isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "须给出 key 或 category 之一：复现=GET /config/item?key=… 或 ?category=…");
            }
            List<ConfigItemView> views = new ArrayList<>();
            for (ConfigItem item : configService.listByCategory(category)) {
                views.add(ConfigItemView.of(item));
            }
            return HttpResult.json(200, ApiResponse.ok(views, ctx.traceId()));
        }
        ChangeRequest request = JsonCodec.fromJson(ctx.body(), ChangeRequest.class);
        if (request == null || request.configKey == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "变更须提交 {configKey, newValue, reviewerId?, reason}：复现=按 §4.1.1 提交");
        }
        configService.changeConfigValue(request.configKey, request.newValue, ctx.currentUserId(),
                request.reviewerId, request.reason);
        return HttpResult.json(200, ApiResponse.ok(
                ConfigItemView.of(configService.getConfig(request.configKey)), ctx.traceId()));
    }

    /** 变更请求体。 */
    public static final class ChangeRequest {

        /** 配置键。 */
        public String configKey;

        /** 新取值（敏感项必须为 {@code null}）。 */
        public String newValue;

        /** 复核人（须双人复核项必填且不得等于操作人）。 */
        public Long reviewerId;

        /** 变更原因。 */
        public String reason;
    }

    /** 配置项视图（敏感项不回取值）。 */
    public static final class ConfigItemView {

        /** 配置键。 */
        public String configKey;

        /** 分类。 */
        public String configCategory;

        /** 取值（敏感项恒为 {@code null}）。 */
        public String configValue;

        /** 值类型。 */
        public String valueType;

        /** 取值域。 */
        public String enumScope;

        /** 是否敏感。 */
        public String isSensitive;

        /** 敏感状态（{@code UNSET}/{@code SET}）。 */
        public String sensitiveState;

        /** 持久化属性。 */
        public String persistScope;

        /** 是否需下发组件。 */
        public String dispatchToCtp;

        /** 生效版本。 */
        public long effectiveVersion;

        /** 说明。 */
        public String description;

        static ConfigItemView of(ConfigItem item) {
            ConfigItemView view = new ConfigItemView();
            view.configKey = item.configKey;
            view.configCategory = item.configCategory;
            // 敏感项结构性无取值可回（NFR-SEC-1）
            view.configValue = "Y".equals(item.isSensitive) ? null : item.configValue;
            view.valueType = item.valueType;
            view.enumScope = item.enumScope;
            view.isSensitive = item.isSensitive;
            view.sensitiveState = item.sensitiveState;
            view.persistScope = item.persistScope;
            view.dispatchToCtp = item.dispatchToCtp;
            view.effectiveVersion = item.effectiveVersion;
            view.description = item.description;
            return view;
        }
    }
}
