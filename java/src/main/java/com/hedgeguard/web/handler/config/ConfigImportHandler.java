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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统参数导入 {@code POST /api/v1/config/import}（{@code T-109}；{@code FR-CONFIG-13}）。
 *
 * <p>请求体：{@code {"mode":"INSERT_ONLY|OVERWRITE","items":[{configKey,configCategory,valueType,...}]}}。
 *
 * <p><b>全量预校验 → 一次性提交</b>：任一项不合法即返回 {@code 10019} 并给出逐项原因，
 * <b>不产生任何部分写入</b>。<b>导入必须排除 / 拒绝敏感字段取值</b>（系统级强制）。
 */
public final class ConfigImportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/import");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public ConfigImportHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.items == null || request.items.isEmpty()) {
            throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                    "导入须提交 {items:[{configKey,configCategory,valueType,...}], mode?}："
                            + "复现=按 FR-CONFIG-13 提交");
        }
        List<ConfigItem> items = new ArrayList<>();
        for (Item raw : request.items) {
            if (raw == null) {
                items.add(null);
                continue;
            }
            ConfigItem item = new ConfigItem();
            item.configKey = raw.configKey;
            item.configCategory = raw.configCategory;
            item.configValue = raw.configValue;
            item.valueType = raw.valueType;
            item.enumScope = raw.enumScope;
            item.minValue = decimalOrNull(raw.minValue);
            item.maxValue = decimalOrNull(raw.maxValue);
            item.isSensitive = raw.isSensitive;
            item.persistScope = raw.persistScope;
            item.dispatchToCtp = raw.dispatchToCtp;
            item.doubleReviewRequired = raw.doubleReviewRequired;
            items.add(item);
        }
        int written = configService.importConfigItems(items, request.mode, ctx.currentUserId());
        ImportResult result = new ImportResult();
        result.written = written;
        return HttpResult.json(200, ApiResponse.ok(result, ctx.traceId()));
    }

    private static BigDecimal decimalOrNull(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                    "上下界须为十进制数：" + raw + "；复现=改正取值", e);
        }
    }

    /** 导入请求体。 */
    public static final class Request {

        /** 导入模式（{@code OVERWRITE} / {@code INSERT_ONLY}，缺省 {@code INSERT_ONLY}）。 */
        public String mode;

        /** 待导入配置项。 */
        public List<Item> items;
    }

    /** 单项入参（数值 / 数量以字符串化十进制承载）。 */
    public static final class Item {

        /** 配置键。 */
        public String configKey;

        /** 配置分类。 */
        public String configCategory;

        /** 配置值（敏感项必须为空）。 */
        public String configValue;

        /** 值类型。 */
        public String valueType;

        /** 取值域。 */
        public String enumScope;

        /** 数值下界。 */
        public String minValue;

        /** 数值上界。 */
        public String maxValue;

        /** 是否敏感。 */
        public String isSensitive;

        /** 持久化属性。 */
        public String persistScope;

        /** 是否需下发 C++。 */
        public String dispatchToCtp;

        /** 是否须双人复核。 */
        public String doubleReviewRequired;
    }

    /** 导入结果。 */
    public static final class ImportResult {

        /** 成功写入条数（全量校验通过才会 > 0）。 */
        public int written;
    }
}
