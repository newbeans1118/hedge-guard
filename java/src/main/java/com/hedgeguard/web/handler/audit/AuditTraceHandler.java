package com.hedgeguard.web.handler.audit;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.OperationLog;
import com.hedgeguard.service.AuditService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计追溯 REST 面（T-067；FR-AUDIT-1/2/3/11、R-AUDIT-2/11、CFG-17c）。
 *
 * <p>四条已声明路径共用同一 handler（方法分派）：
 * <ul>
 *   <li>{@code GET|POST /api/v1/audit/trace/forward}   —— 正向追溯（敞口 - 凭证）；</li>
 *   <li>{@code GET|POST /api/v1/audit/trace/backward}  —— 反向追溯（凭证 或 交易 - 敞口）；</li>
 *   <li>{@code GET|POST /api/v1/audit/trace/integrity} —— 链条完整性校验 + 断点清单；</li>
 *   <li>{@code GET|POST /api/v1/audit/operation-log}   —— 操作留痕查询（FR-AUDIT-11）。</li>
 * </ul>
 *
 * <p>路径逐字取自 {@code ApiRoutes}（未新增未声明路径）。
 */
public final class AuditTraceHandler implements RouteHandler {

    /** 正向追溯模式。 */
    public static final String MODE_FORWARD = "FORWARD";
    /** 反向追溯模式。 */
    public static final String MODE_BACKWARD = "BACKWARD";
    /** 完整性校验模式。 */
    public static final String MODE_INTEGRITY = "INTEGRITY";
    /** 操作留痕模式。 */
    public static final String MODE_OPLOG = "OPLOG";

    /** 正向追溯路径（逐字取自 ApiRoutes）。 */
    public static final String PATH_FORWARD = "/api/v1/audit/trace/forward";
    /** 反向追溯路径（逐字取自 ApiRoutes）。 */
    public static final String PATH_BACKWARD = "/api/v1/audit/trace/backward";
    /** 完整性校验路径（逐字取自 ApiRoutes）。 */
    public static final String PATH_INTEGRITY = "/api/v1/audit/trace/integrity";
    /** 操作留痕路径（逐字取自 ApiRoutes）。 */
    public static final String PATH_OPLOG = "/api/v1/audit/operation-log";

    private final String mode;
    private final AuditService service;

    /**
     * 构造。
     *
     * @param mode    模式（FORWARD/BACKWARD/INTEGRITY/OPLOG）
     * @param service 审计服务
     */
    public AuditTraceHandler(String mode, AuditService service) {
        this.mode = mode;
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
        if (MODE_FORWARD.equals(mode)) {
            long exposureId = longArg(ctx.queryParam("exposureId"), "exposureId");
            return trace(ctx, service.traceForward(exposureId, text(ctx.queryParam("depth")),
                    ctx.currentUserId()));
        }
        if (MODE_BACKWARD.equals(mode)) {
            return trace(ctx, service.traceBackward(text(ctx.queryParam("startType")),
                    text(ctx.queryParam("startId")), text(ctx.queryParam("depth")), ctx.currentUserId()));
        }
        if (MODE_INTEGRITY.equals(mode)) {
            long exposureId = longArg(ctx.queryParam("exposureId"), "exposureId");
            return trace(ctx, service.checkIntegrity(exposureId, ctx.currentUserId()));
        }
        return oplog(ctx);
    }

    private HttpResult post(RequestContext ctx) {
        if (MODE_FORWARD.equals(mode)) {
            ForwardInput in = body(ctx, ForwardInput.class);
            if (in == null || in.exposureId == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "exposureId 必填");
            }
            return trace(ctx, service.traceForward(in.exposureId, in.depth, ctx.currentUserId()));
        }
        if (MODE_BACKWARD.equals(mode)) {
            BackwardInput in = body(ctx, BackwardInput.class);
            if (in == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "请求体必填");
            }
            return trace(ctx, service.traceBackward(in.startType, in.startId, in.depth, ctx.currentUserId()));
        }
        if (MODE_INTEGRITY.equals(mode)) {
            IntegrityInput in = body(ctx, IntegrityInput.class);
            if (in == null || in.exposureId == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "exposureId 必填");
            }
            return trace(ctx, service.checkIntegrity(in.exposureId, ctx.currentUserId()));
        }
        OpLogInput in = body(ctx, OpLogInput.class);
        if (in == null) {
            in = new OpLogInput();
        }
        return oplog(ctx, in);
    }

    private HttpResult trace(RequestContext ctx, AuditService.TraceResult result) {
        return HttpResult.json(200, ApiResponse.ok(result, ctx.traceId()));
    }

    private HttpResult oplog(RequestContext ctx) {
        OpLogInput in = new OpLogInput();
        in.fromTime = text(ctx.queryParam("fromTime"));
        in.toTime = text(ctx.queryParam("toTime"));
        in.operatorId = longOrNull(ctx.queryParam("operatorId"));
        in.operationType = text(ctx.queryParam("operationType"));
        in.objectType = text(ctx.queryParam("objectType"));
        in.subAccountId = intOrNull(ctx.queryParam("subAccountId"));
        in.page = text(ctx.queryParam("page"));
        in.size = text(ctx.queryParam("size"));
        in.sort = text(ctx.queryParam("sort"));
        return oplog(ctx, in);
    }

    private HttpResult oplog(RequestContext ctx, OpLogInput in) {
        AuditService.OperationLogQuery q = new AuditService.OperationLogQuery();
        q.fromTime = localDateTime(in.fromTime, "fromTime");
        q.toTime = localDateTime(in.toTime, "toTime");
        q.operatorId = in.operatorId;
        q.operationType = in.operationType;
        q.objectType = in.objectType;
        q.subAccountId = in.subAccountId;
        PageQuery query = PageQuery.of(in.page, in.size, in.sort, "operation_time", "desc");
        PageResult<OperationLog> page = service.queryOperationLog(q, query, ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (OperationLog log : page.items()) {
            items.add(logView(log));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    private static Map<String, Object> logView(OperationLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.id);
        m.put("subAccountId", log.subAccountId);
        m.put("operatorId", log.operatorId);
        m.put("operationType", log.operationType);
        m.put("objectType", log.objectType);
        m.put("objectId", log.objectId);
        m.put("objectNo", log.objectNo);
        m.put("actionResult", log.actionResult);
        m.put("resultDetail", log.resultDetail);
        m.put("operationTime", log.operationTime == null ? null : log.operationTime.toString());
        m.put("clientIp", log.clientIp);
        m.put("traceId", log.traceId);
        return m;
    }

    private static <T> T body(RequestContext ctx, Class<T> type) {
        String b = ctx.body();
        if (b == null || b.trim().isEmpty()) {
            return null;
        }
        return JsonCodec.fromJson(b, type);
    }

    private static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static long longArg(String s, String name) {
        Long v = longOrNull(s);
        if (v == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, name + " 必填且须为整数");
        }
        return v;
    }

    private static Long longOrNull(String s) {
        String t = text(s);
        if (t == null) {
            return null;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "整数参数非法：" + s);
        }
    }

    private static Integer intOrNull(String s) {
        Long v = longOrNull(s);
        return v == null ? null : v.intValue();
    }

    private static LocalDateTime localDateTime(String s, String name) {
        String t = text(s);
        if (t == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(t);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    name + " 须为 ISO-8601 日期时间（yyyy-MM-ddTHH:mm:ss）：" + s);
        }
    }

    /** 正向追溯输入。 */
    public static final class ForwardInput {
        /** 敞口主键。 */
        public Long exposureId;
        /** 追溯深度（段名，可空=全链）。 */
        public String depth;
    }

    /** 反向追溯输入。 */
    public static final class BackwardInput {
        /** 起点类型（VOUCHER/TRADE）。 */
        public String startType;
        /** 起点标识。 */
        public String startId;
        /** 追溯深度（段名，可空=全链）。 */
        public String depth;
    }

    /** 完整性校验输入。 */
    public static final class IntegrityInput {
        /** 敞口主键。 */
        public Long exposureId;
    }

    /** 操作留痕输入。 */
    public static final class OpLogInput {
        /** 起始时间。 */
        public String fromTime;
        /** 截止时间。 */
        public String toTime;
        /** 操作人。 */
        public Long operatorId;
        /** 操作类型。 */
        public String operationType;
        /** 对象类型。 */
        public String objectType;
        /** 子账户。 */
        public Integer subAccountId;
        /** 页码。 */
        public String page;
        /** 每页条数。 */
        public String size;
        /** 排序。 */
        public String sort;
    }
}
