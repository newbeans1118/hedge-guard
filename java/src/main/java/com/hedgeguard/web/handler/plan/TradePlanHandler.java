package com.hedgeguard.web.handler.plan;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.TradePlan;
import com.hedgeguard.service.PlanService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易计划 {@code GET|POST /api/v1/plan/{id}/trade-plans}（{@code T-016}；{@code FR-PLAN-14/15}）。
 *
 * <p>{@code GET}：交易岗"待执行交易计划"清单（筛选 + 分页 + 排序；默认按执行期限升序）。
 * {@code POST}：把已批准计划**分解**为交易计划；校验累计量 ≤ 套保计划数量
 * （{@code FR-PLAN-14} 验收 2）、合约须属该品种、方向须与计划一致、执行期限不得超计划期限。
 */
public final class TradePlanHandler implements RouteHandler {

    /** 注册路径模板。 */
    public static final String PATH = ApiRoutes.full("/plan/{id}/trade-plans");

    private final PlanService planService;

    /**
     * 构造。
     *
     * @param planService 计划服务
     */
    public TradePlanHandler(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long planId = PlanRoutes.parseId(ctx.pathParam("id"), "id");
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return list(ctx, planId);
        }
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.plans == null || request.plans.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "分解须提交 {plans:[{instrumentId, instrumentType, direction, planQty, execDeadline}]}："
                            + "复现=至少给出一个交易计划");
        }
        List<TradePlan> rows = new ArrayList<>();
        for (Item item : request.plans) {
            TradePlan row = new TradePlan();
            row.instrumentId = item.instrumentId;
            row.instrumentType = item.instrumentType;
            row.direction = item.direction;
            row.planQty = PlanRoutes.decimal(item.planQty, "planQty");
            row.execDeadline = PlanRoutes.date(item.execDeadline, "execDeadline");
            rows.add(row);
        }
        int inserted = planService.decomposeTradePlans(planId, rows, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(inserted, ctx.traceId()));
    }

    /**
     * 交易计划查询（{@code GET}，{@code FR-PLAN-15}）：筛选 + 分页 + 排序。
     *
     * <p><b>筛选（「输入」列）</b>：子账户 {@code subAccountId} / 套保计划（端点路径 {@code {id}}）/
     * 合约 {@code instrumentId} / 状态 {@code status} / 执行期限 {@code execDeadlineFrom}~{@code execDeadlineTo}（闭区间）。
     *
     * <p>分页 / 排序按 {@code ARCHITECTURE.md} §4.1.1（{@code page/size/sort}；
     * {@code size ∈ {10,20,50,100}}，默认 20；{@code sort=field,asc|desc}）；默认排序
     * {@code exec_deadline} <b>升序</b>（{@code FR-PLAN-15}「处理规则」）。{@code PageQuery} 在 {@code sort}
     * 缺省时方向恒为 ASC（{@code PageQuery.java:62}），恰与本 FR 要求的升序一致，故此处<b>无需</b>像
     * {@link PlanHandler} 那样显式补方向。响应 {@code data} 为 {@code {items,total,page,size}}（分页信封）；
     * {@code total} 按<b>过滤后</b>总数计（含子账户隔离）。
     *
     * <p>子账户隔离（{@code NFR-SEC-7}）：调用者身份取自会话（{@code ctx.currentUserId()}），
     * 不可由请求体 / 查询参数伪造。
     */
    private HttpResult list(RequestContext ctx, long planId) {
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), PlanService.TRADE_PLAN_DEFAULT_SORT_FIELD);
        PlanService.TradePlanFilter filter = new PlanService.TradePlanFilter();
        filter.subAccountId = intOrNull(ctx.queryParam("subAccountId"), "subAccountId");
        filter.instrumentId = textOrNull(ctx.queryParam("instrumentId"));
        filter.status = textOrNull(ctx.queryParam("status"));
        filter.execDeadlineFrom = dateOrNull(ctx.queryParam("execDeadlineFrom"), "execDeadlineFrom");
        filter.execDeadlineTo = dateOrNull(ctx.queryParam("execDeadlineTo"), "execDeadlineTo");
        PageResult<TradePlan> page = planService.listTradePlans(planId, ctx.currentUserId(), query, filter);
        List<View> items = new ArrayList<>();
        for (TradePlan tradePlan : page.items()) {
            items.add(View.of(tradePlan));
        }
        PageData<View> data = new PageData<>(items, page.total(), page.page(), page.size());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    private static String textOrNull(String raw) {
        return (raw == null || raw.isEmpty()) ? null : raw;
    }

    private static Integer intOrNull(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数须为整数：" + field + "=" + raw + "；复现=修正查询参数", e);
        }
    }

    private static LocalDate dateOrNull(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数须为 yyyy-MM-dd：" + field + "=" + raw + "；复现=修正日期格式", e);
        }
    }

    /** 分解请求体。 */
    public static final class Request {

        /** 交易计划条目。 */
        public List<Item> plans;
    }

    /** 单条交易计划入参。 */
    public static final class Item {

        /** 合约代码（须属于该套保计划的品种）。 */
        public String instrumentId;

        /** 合约类型（{@code FUTURES} / {@code OPTION}）。 */
        public String instrumentType;

        /** 方向（须与套保计划一致：{@code BUY_HEDGE} → {@code BUY}）。 */
        public String direction;

        /** 数量（字符串化十进制，&gt; 0）。 */
        public String planQty;

        /** 执行期限（{@code yyyy-MM-dd}，不得超计划期限）。 */
        public String execDeadline;
    }

    /** 交易计划视图。 */
    public static final class View {

        /** 主键。 */
        public long id;

        /** 交易计划编号。 */
        public String tradePlanNo;

        /** 所属套保计划。 */
        public Long hedgePlanId;

        /** 敞口（随计划携带，供指令侧回溯）。 */
        public Long exposureId;

        /** 合约。 */
        public String instrumentId;

        /** 合约类型。 */
        public String instrumentType;

        /** 方向。 */
        public String direction;

        /** 计划数量。 */
        public String planQty;

        /** 已成交数量。 */
        public String executedQty;

        /** 已下达指令量。 */
        public String orderedQty;

        /** 执行期限。 */
        public String execDeadline;

        /** 状态。 */
        public String status;

        static View of(TradePlan plan) {
            View view = new View();
            view.id = plan.id;
            view.tradePlanNo = plan.tradePlanNo;
            view.hedgePlanId = plan.hedgePlanId;
            view.exposureId = plan.exposureId;
            view.instrumentId = plan.instrumentId;
            view.instrumentType = plan.instrumentType;
            view.direction = plan.direction;
            view.planQty = text(plan.planQty);
            view.executedQty = text(plan.executedQty);
            view.orderedQty = text(plan.orderedQty);
            view.execDeadline = plan.execDeadline == null ? null : plan.execDeadline.toString();
            view.status = plan.status;
            return view;
        }

        private static String text(BigDecimal value) {
            return value == null ? "0" : value.toPlainString();
        }
    }
}
