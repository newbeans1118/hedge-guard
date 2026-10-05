package com.hedgeguard.web.handler.settle;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.service.SettleCheckService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核对任务与电子签署 REST 面（{@code T-048}；{@code FR-SETTLE-13/14/17}、{@code FR-AUDIT-13}）。
 *
 * <p>两条已声明路径共用同一 handler（方法分派）：
 * <ul>
 *   <li>{@code GET  /api/v1/settle/monthly-check}、{@code GET  /api/v1/settle/quarterly-report}
 *       —— 只读查询核对任务与签署记录（审计席 {@code FR-AUDIT-13}）；{@code format=csv} 时导出 CSV。</li>
 *   <li>{@code POST /api/v1/settle/monthly-check}、{@code POST /api/v1/settle/quarterly-report}
 *       —— {@code action=GENERATE} 生成任务；否则按 {@code signRole} 签署（任务缺失则先按期间生成，
 *       即「发起并签署」）。</li>
 * </ul>
 *
 * <p>路径由 {@code defaultCheckType} 决定缺省核对类型（月度 / 季度）；年度（{@code AUDITOR} 全覆盖）
 * 经 {@code checkType=ANNUAL} 显式提交，走同一服务能力（无独立已声明路径）。
 */
public final class SettleCheckHandler implements RouteHandler {

    /** 月度核对路径（逐字取自 {@code ApiRoutes}）。 */
    public static final String PATH_MONTHLY = "/api/v1/settle/monthly-check";
    /** 季度报告路径（逐字取自 {@code ApiRoutes}）。 */
    public static final String PATH_QUARTERLY = "/api/v1/settle/quarterly-report";

    private final String defaultCheckType;
    private final SettleCheckService service;

    /**
     * 构造。
     *
     * @param defaultCheckType 缺省核对类型
     * @param service          核对与签署服务
     */
    public SettleCheckHandler(String defaultCheckType, SettleCheckService service) {
        this.defaultCheckType = defaultCheckType;
        this.service = service;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if ("GET".equalsIgnoreCase(ctx.method())) {
            return get(ctx);
        }
        return post(ctx);
    }

    private HttpResult get(RequestContext ctx) {
        SettleCheckService.QueryCheckInput in = new SettleCheckService.QueryCheckInput();
        in.checkType = SettleCycleHandler.text(ctx.queryParam("checkType")) == null
                ? defaultCheckType : ctx.queryParam("checkType");
        in.periodKey = SettleCycleHandler.text(ctx.queryParam("periodKey"));
        in.subAccountId = SettleCycleHandler.intOrNull(ctx.queryParam("subAccountId"), "subAccountId");
        if ("csv".equalsIgnoreCase(SettleCycleHandler.text(ctx.queryParam("format")))) {
            SettleCheckService.CheckExport ex = service.export(in, ctx.currentUserId());
            return HttpResult.text(200, ex.toCsv());
        }
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "generated_time", "desc");
        PageResult<SettleCheckService.CheckTaskView> page = service.query(in, query, ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (SettleCheckService.CheckTaskView v : page.items()) {
            items.add(taskView(v.task, v.signatures));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    private HttpResult post(RequestContext ctx) {
        SettleCheckService.SignCheckInput in = JsonCodec.fromJson(
                ctx.body() == null || ctx.body().trim().isEmpty() ? "{}" : ctx.body(),
                SettleCheckService.SignCheckInput.class);
        String action = actionField(ctx);
        boolean generate = "GENERATE".equalsIgnoreCase(action)
                || (action == null && (in.signRole == null || in.signRole.trim().isEmpty()));
        if (generate) {
            SettleCheckService.GenerateCheckInput gen = new SettleCheckService.GenerateCheckInput();
            gen.checkType = in.checkType == null || in.checkType.trim().isEmpty()
                    ? defaultCheckType : in.checkType;
            gen.periodKey = in.periodKey;
            gen.subAccountId = in.subAccountId;
            gen.snapshotHash = in.snapshotHash;
            gen.snapshotJson = in.snapshotJson;
            gen.contentJson = in.contentJson;
            SettleCheckTask task = service.generateCheckTask(gen, ctx.currentUserId());
            return HttpResult.json(200, ApiResponse.ok(taskView(task, new ArrayList<>()), ctx.traceId()));
        }
        if (in.checkType == null || in.checkType.trim().isEmpty()) {
            in.checkType = defaultCheckType;
        }
        SettleCheckService.SignResult r = service.sign(in, ctx.currentUserId(), ctx.clientIp());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", r.taskId);
        data.put("status", r.status);
        data.put("requiredSignRoles", r.requiredSignRoles);
        data.put("signedRoles", r.signedRoles);
        data.put("signRole", r.signRole);
        data.put("signerId", r.signerId);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    /** 从原始请求体读 {@code action}（避免为单字段扩展 DTO）。 */
    private static String actionField(RequestContext ctx) {
        String body = ctx.body();
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        Map<?, ?> raw;
        try {
            raw = JsonCodec.fromJson(body, Map.class);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求体须为 JSON 对象：" + e.getMessage());
        }
        Object v = raw == null ? null : raw.get("action");
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, Object> taskView(SettleCheckTask t, List<SettleCheckSignature> sigs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("checkType", t.checkType);
        m.put("periodKey", t.periodKey);
        m.put("subAccountId", t.subAccountId);
        m.put("requiredSignRoles", t.requiredSignRoles);
        m.put("signedRoles", t.signedRoles);
        m.put("status", t.status);
        m.put("snapshotHash", t.snapshotHash);
        m.put("contentJson", t.contentJson);
        m.put("submittedTo", t.submittedTo);
        m.put("deadlineDate", t.deadlineDate == null ? null : t.deadlineDate.toString());
        m.put("overdueAlerted", t.overdueAlerted);
        m.put("generatedTime", t.generatedTime == null ? null : t.generatedTime.toString());
        m.put("completedTime", t.completedTime == null ? null : t.completedTime.toString());
        List<Map<String, Object>> sl = new ArrayList<>();
        for (SettleCheckSignature s : sigs) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("signRole", s.signRole);
            sm.put("signerId", s.signerId);
            sm.put("signerRoleCode", s.signerRoleCode);
            sm.put("signTime", s.signTime == null ? null : s.signTime.toString());
            sm.put("signIp", s.signIp);
            sm.put("snapshotHash", s.snapshotHash);
            sm.put("signResult", s.signResult);
            sm.put("signOpinion", s.signOpinion);
            sm.put("proxyRejected", s.proxyRejected);
            sm.put("manageRoleTradeBlocked", s.manageRoleTradeBlocked);
            sl.add(sm);
        }
        m.put("signatures", sl);
        return m;
    }
}
