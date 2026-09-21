package com.hedgeguard.web.handler.plan;

import com.google.gson.JsonObject;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgePlanExposure;
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
 * 套保计划 {@code POST /api/v1/plan} 与 {@code GET /api/v1/plan/{id}}（{@code T-016}）。
 *
 * <p>{@code POST}：编制草稿（**必须**绑定至少一条实货敞口，且方向与计划相反、同子账户，{@code R-PLAN-1/7}）；
 * 项目数量一律**字符串化十进制**（{@code §4.1.1}）。
 *
 * <p>{@code GET /plan}：计划列表（筛选 + 分页，{@code FR-PLAN-4}）；{@code GET /plan/{id}}：计划详情 +
 * 绑定敞口清单（{@code FR-PLAN-5} 验收 1）。
 * 金额/数量字段在响应中均为字符串；**发起人**由会话决定（{@code ctx.currentUserId()}），
 * **不接受**请求体传入（防伪造）。
 */
public final class PlanHandler implements RouteHandler {

    /** 计划集合路径（{@code GET} 列表查询 / {@code POST} 新增）。 */
    public static final String PATH = ApiRoutes.full("/plan");

    /** 计划详情路径模板。 */
    public static final String PATH_BY_ID = ApiRoutes.full("/plan/{id}");

    /**
     * 列表**缺省**排序：{@code created_time} <b>倒序</b>（{@code FR-PLAN-4}，{@code PLAN.md:155}）。
     *
     * <p><b>为何必须带方向</b>：{@code PageQuery.of(..., defaultSortField)} 在 {@code sort} 缺省时
     * 方向<b>恒为 ASC</b>（{@code PageQuery.java:62}），故只传裸字段名会得到<b>升序</b>（见 {@code BUG-001}）。
     * 本常量仅本端点私有；公共件 {@code PageQuery} 的缺省方向问题另由技术负责人裁定，此处不动它。
     */
    private static final String DEFAULT_SORT = PlanService.DEFAULT_SORT_FIELD + ",desc";

    private final PlanService planService;

    /**
     * 构造。
     *
     * @param planService 计划服务
     */
    public PlanHandler(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            String rawId = ctx.pathParam("id");
            if (rawId != null && !rawId.isEmpty()) {
                return detail(ctx, rawId);
            }
            return list(ctx);
        }
        CreateRequest request = JsonCodec.fromJson(ctx.body(), CreateRequest.class);
        if (request == null || request.binds == null || request.binds.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "编制计划须提交 {planType, productId, hedgeDirection, planQty, unit, 期限, hedgeRatio,"
                            + " ratioMethod, calcBaseDate, binds[]}：复现=补齐 binds（至少一条敞口）");
        }
        HedgePlan plan = new HedgePlan();
        plan.subAccountId = request.subAccountId;
        plan.planType = request.planType;
        plan.productId = PlanRoutes.parseId(request.productId, "productId");
        plan.hedgeDirection = request.hedgeDirection;
        plan.planQty = PlanRoutes.decimal(request.planQty, "planQty");
        plan.unit = request.unit;
        plan.termStartDate = PlanRoutes.date(request.termStartDate, "termStartDate");
        plan.termEndDate = PlanRoutes.date(request.termEndDate, "termEndDate");
        plan.hedgeRatio = PlanRoutes.decimal(request.hedgeRatio, "hedgeRatio");
        plan.ratioMethod = request.ratioMethod;
        plan.calcBaseDate = PlanRoutes.date(request.calcBaseDate, "calcBaseDate");
        List<PlanService.BindSpec> binds = new ArrayList<>();
        for (BindRequest bind : request.binds) {
            binds.add(new PlanService.BindSpec(bind.exposureId,
                    PlanRoutes.decimal(bind.coveredQty, "coveredQty")));
        }
        long id = planService.createPlan(plan, binds, ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(id, ctx.traceId()));
    }

    /** 计划详情（{@code GET /plan/{id}}）：计划 + 绑定敞口清单（{@code FR-PLAN-5}）。
     *
     * <p>子账户隔离（{@code NFR-SEC-7}）：调用者身份取自会话（{@code ctx.currentUserId()}），
     * 不可见的计划与不存在返回同一错误（{@code FR-PLAN-5}「无权限或不存在时返回统一错误」）。
     */
    private HttpResult detail(RequestContext ctx, String rawId) {
        long planId = PlanRoutes.parseId(rawId, "id");
        HedgePlan plan = planService.getPlan(planId, ctx.currentUserId());
        if (plan == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划不存在：id=" + planId + "；复现=按 plan_no 查询确认 ID");
        }
        PlanView view = PlanView.of(plan);
        // 三项可见性之 (c)：审批人经详情接口即可见最近一次预校验快照（含 unverified ③ 标记）
        view.precheckResult = planService.latestPrecheckSnapshot(planId);
        for (HedgePlanExposure binding : planService.listBindings(planId)) {
            BindingView bindingView = new BindingView();
            bindingView.exposureId = binding.exposureId;
            bindingView.coveredQty = binding.coveredQty == null ? "0"
                    : binding.coveredQty.toPlainString();
            bindingView.status = binding.status;
            view.bindings.add(bindingView);
        }
        return HttpResult.json(200, ApiResponse.ok(view, ctx.traceId()));
    }

    /**
     * 计划列表（{@code GET /plan}，{@code FR-PLAN-4}）：筛选 + 分页 + 排序。
     *
     * <p>分页 / 排序按 {@code §4.1.1}（{@code page/size/sort}；{@code size ∈ {10,20,50,100}}，默认 20），
     * 默认排序 {@code created_time} 倒序。响应 {@code data} 为 {@code {items,total,page,size}}，
     * 每行字段与 {@link PlanView} 一致（含执行进度 {@code executedQty}/{@code orderedQty} 与
     * 占用额度 {@code quotaOccupied}，{@code FR-PLAN-4} 验收 2）；列表行不含 {@code bindings}。
     */
    private HttpResult list(RequestContext ctx) {
        // BUG-001 / FR-PLAN-4（REQUIREMENTS/PLAN.md:155）：缺省排序须为 created_time **倒序**。
        // PageQuery 在 sort 缺省时方向恒为 ASC，故缺省时必须显式带上 ",desc"（只传裸字段名会得到升序）。
        String sort = ctx.queryParam("sort");
        if (sort == null || sort.isEmpty()) {
            sort = DEFAULT_SORT;
        }
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                sort, PlanService.DEFAULT_SORT_FIELD);
        PlanService.PlanFilter filter = new PlanService.PlanFilter();
        filter.subAccountId = intOrNull(ctx.queryParam("subAccountId"), "subAccountId");
        filter.productId = longOrNull(ctx.queryParam("productId"), "productId");
        filter.status = textOrNull(ctx.queryParam("status"));
        filter.planType = textOrNull(ctx.queryParam("planType"));
        filter.termStartFrom = dateOrNull(ctx.queryParam("termStartFrom"), "termStartFrom");
        filter.termStartTo = dateOrNull(ctx.queryParam("termStartTo"), "termStartTo");
        filter.createdFrom = dateOrNull(ctx.queryParam("createdFrom"), "createdFrom");
        filter.createdTo = dateOrNull(ctx.queryParam("createdTo"), "createdTo");
        filter.applicantId = longOrNull(ctx.queryParam("applicantId"), "applicantId");

        PageResult<HedgePlan> page = planService.listPlans(ctx.currentUserId(), query, filter);
        List<PlanView> items = new ArrayList<>();
        for (HedgePlan plan : page.items()) {
            items.add(PlanView.of(plan));
        }
        PageData<PlanView> data = new PageData<>(items, page.total(), page.page(), page.size());
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

    private static Long longOrNull(String raw, String field) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
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

    /** 编制请求体。 */
    public static final class CreateRequest {

        /** 子账户（必填；计划、绑定敞口、交易计划三者须一致）。 */
        public int subAccountId;

        /** 计划类型（{@code COMMODITY} / {@code TRADE}）。 */
        public String planType;

        /** 品种 ID（字符串化整数，避免前端大整数精度问题）。 */
        public String productId;

        /** 套保方向（{@code BUY_HEDGE} / {@code SELL_HEDGE}；**须与绑定敞口方向相反**）。 */
        public String hedgeDirection;

        /** 计划数量（字符串化十进制，&gt; 0）。 */
        public String planQty;

        /** 单位。 */
        public String unit;

        /** 计划期限起（{@code yyyy-MM-dd}）。 */
        public String termStartDate;

        /** 计划期限止（{@code yyyy-MM-dd}；≤ 起始日 + 12 个月）。 */
        public String termEndDate;

        /** 套保比率（字符串化小数，∈ (0,1]）。 */
        public String hedgeRatio;

        /** 比率方法（{@code SIMPLE} / {@code MIN_VARIANCE} / {@code CUSTOM_RULE}）。 */
        public String ratioMethod;

        /** 比率计算基准日（{@code yyyy-MM-dd}）。 */
        public String calcBaseDate;

        /** 绑定敞口（至少一条）。 */
        public List<BindRequest> binds;
    }

    /** 绑定时段入参。 */
    public static final class BindRequest {

        /** 敞口 ID。 */
        public long exposureId;

        /** 覆盖数量（字符串化十进制，&gt; 0 且 ≤ 敞口未覆盖量）。 */
        public String coveredQty;
    }

    /** 计划视图。 */
    public static final class PlanView {

        /** 主键。 */
        public long id;

        /** 计划编号（全系统唯一，一经分配不得修改）。 */
        public String planNo;

        /** 子账户。 */
        public Integer subAccountId;

        /** 计划类型。 */
        public String planType;

        /** 品种。 */
        public Long productId;

        /** 套保方向。 */
        public String hedgeDirection;

        /** 计划数量。 */
        public String planQty;

        /** 单位。 */
        public String unit;

        /** 期限起。 */
        public String termStartDate;

        /** 期限止。 */
        public String termEndDate;

        /** 套保比率。 */
        public String hedgeRatio;

        /** 比率方法。 */
        public String ratioMethod;

        /** 状态。 */
        public String status;

        /** 是否占用额度。 */
        public String quotaOccupied;

        /** 当前书面文件版本。 */
        public int currentDocVersion;

        /** 已成交数量。 */
        public String executedQty;

        /** 已下达指令量。 */
        public String orderedQty;

        /** 绑定敞口清单。 */
        public List<BindingView> bindings = new ArrayList<>();

        /**
         * 最近一次合规预校验快照（服务端生成，{@code Java/CONTRACTS.md} §1.5 结构；
         * 含 {@code unverified} 未校验标记，供审批页面展示；无历史快照时省略）。
         */
        public JsonObject precheckResult;

        static PlanView of(HedgePlan plan) {
            PlanView view = new PlanView();
            view.id = plan.id;
            view.planNo = plan.planNo;
            view.subAccountId = plan.subAccountId;
            view.planType = plan.planType;
            view.productId = plan.productId;
            view.hedgeDirection = plan.hedgeDirection;
            view.planQty = text(plan.planQty);
            view.unit = plan.unit;
            view.termStartDate = plan.termStartDate == null ? null : plan.termStartDate.toString();
            view.termEndDate = plan.termEndDate == null ? null : plan.termEndDate.toString();
            view.hedgeRatio = text(plan.hedgeRatio);
            view.ratioMethod = plan.ratioMethod;
            view.status = plan.status;
            view.quotaOccupied = plan.quotaOccupied;
            view.currentDocVersion = plan.currentDocVersion;
            view.executedQty = text(plan.executedQty);
            view.orderedQty = text(plan.orderedQty);
            return view;
        }

        private static String text(BigDecimal value) {
            return value == null ? "0" : value.toPlainString();
        }
    }

    /** 绑定视图。 */
    public static final class BindingView {

        /** 敞口 ID。 */
        public long exposureId;

        /** 覆盖数量。 */
        public String coveredQty;

        /** 绑定状态（{@code BOUND} / {@code UNBOUND}）。 */
        public String status;
    }
}
