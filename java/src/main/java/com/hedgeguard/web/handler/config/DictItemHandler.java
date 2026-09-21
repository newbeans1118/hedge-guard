package com.hedgeguard.web.handler.config;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.DictItem;
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
 * 枚举字典 {@code GET|POST /api/v1/config/dict}（{@code T-109}；{@code FR-CONFIG-14}）。
 *
 * <p>{@code GET ?dictType=}：按字典类型列出枚举项。{@code POST}：新增 / 停用（{@code id > 0} 更新）。
 * <b>无删除入口</b>——已被历史数据引用的枚举项只能停用（{@code FR-CONFIG-14} 验收 2）。
 */
public final class DictItemHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/config/dict");

    private final ConfigService configService;

    /**
     * 构造。
     *
     * @param configService 配置服务
     */
    public DictItemHandler(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                    ctx.queryParam("sort"), "id");
            PageResult<DictItem> page = configService.listDictItems(ctx.queryParam("dictType"), query);
            List<ItemView> items = new ArrayList<>();
            for (DictItem item : page.items()) {
                items.add(ItemView.of(item));
            }
            PageData<ItemView> data = new PageData<>(items, page.total(), page.page(), page.size());
            return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
        }
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须提交 {dictType, itemCode, itemName, status, ...}：复现=按 FR-CONFIG-14 提交");
        }
        DictItem item = new DictItem();
        item.id = request.id;
        item.dictType = request.dictType;
        item.itemCode = request.itemCode;
        item.itemName = request.itemName;
        item.itemDesc = request.itemDesc;
        item.sortNo = request.sortNo;
        item.status = request.status;
        item.isSystem = request.isSystem;
        long id = configService.saveDictItem(item, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 写入请求体（{@code id > 0} 走更新 / 停用）。 */
    public static final class Request {

        /** 主键（{@code > 0} 更新）。 */
        public long id;

        /** 字典类型（必填）。 */
        public String dictType;

        /** 枚举编码（必填，与字典类型联合唯一）。 */
        public String itemCode;

        /** 枚举名称（必填）。 */
        public String itemName;

        /** 业务含义说明。 */
        public String itemDesc;

        /** 排序号。 */
        public int sortNo;

        /** 状态（{@code ENABLED} / {@code DISABLED}）。 */
        public String status;

        /** 是否系统内置（{@code Y} / {@code N}）。 */
        public String isSystem;
    }

    /** 字典项视图。 */
    public static final class ItemView {

        /** 主键。 */
        public long id;

        /** 字典类型。 */
        public String dictType;

        /** 枚举编码。 */
        public String itemCode;

        /** 枚举名称。 */
        public String itemName;

        /** 业务含义说明。 */
        public String itemDesc;

        /** 排序号。 */
        public int sortNo;

        /** 状态。 */
        public String status;

        /** 是否系统内置。 */
        public String isSystem;

        static ItemView of(DictItem item) {
            ItemView view = new ItemView();
            view.id = item.id;
            view.dictType = item.dictType;
            view.itemCode = item.itemCode;
            view.itemName = item.itemName;
            view.itemDesc = item.itemDesc;
            view.sortNo = item.sortNo;
            view.status = item.status;
            view.isSystem = item.isSystem;
            return view;
        }
    }
}
