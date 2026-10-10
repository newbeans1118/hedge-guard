package com.hedgeguard.web.handler.audit;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.AuditLog;
import com.hedgeguard.dao.entity.AuditLogChecksum;
import com.hedgeguard.service.AuditLogService;
import com.hedgeguard.service.AuditLogService.AuditLogQuery;
import com.hedgeguard.service.AuditLogService.VerifyResult;
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
 * 审计日志 REST 面（{@code T-069}；{@code FR-AUDIT-9}/{@code FR-AUDIT-10}）。
 *
 * <p>两条预声明路径：
 * <ul>
 *   <li>{@code /audit/log}：查询审计日志（{@code FR-AUDIT-9}；查询本身也写入日志）；</li>
 *   <li>{@code /audit/log/verify}：防篡改校验 / 批次校验值生成（{@code FR-AUDIT-10}）。</li>
 * </ul>
 */
public final class AuditLogHandler implements RouteHandler {

    /** 审计日志查询路径。 */
    public static final String PATH_LOG = "/api/v1/audit/log";
    /** 审计日志防篡改校验路径。 */
    public static final String PATH_VERIFY = "/api/v1/audit/log/verify";

    /** 模式：日志查询。 */
    public static final String MODE_LOG = "LOG";
    /** 模式：防篡改校验。 */
    public static final String MODE_VERIFY = "VERIFY";

    private final AuditLogService service;
    private final String mode;

    /**
     * 构造。
     *
     * @param mode    模式
     * @param service 审计日志服务
     */
    public AuditLogHandler(String mode, AuditLogService service) {
        this.mode = mode;
        this.service = service;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        return MODE_VERIFY.equals(mode) ? verify(ctx) : query(ctx);
    }

    private HttpResult query(RequestContext ctx) {
        Input in = read(ctx);
        AuditLogQuery q = new AuditLogQuery();
        q.fromTime = dateTime(in.fromTime);
        q.toTime = dateTime(in.toTime);
        q.auditUserId = in.auditUserId;
        q.actionType = in.actionType;
        q.subAccountId = in.subAccountId;
        PageQuery query = PageQuery.of(in.page, in.size, in.sort, "action_time", "desc");
        PageResult<AuditLog> page = service.query(q, query, ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (AuditLog log : page.items()) {
            items.add(logView(log));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    private HttpResult verify(RequestContext ctx) {
        Input in = read(ctx);
        long from = toLong(in.fromSeq, 1L);
        long to = toLong(in.toSeq, 0L);
        Map<String, Object> data = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(in.generateBatch)) {
            long batchSize = toLong(in.batchSize, AuditLogService.DEFAULT_BATCH_SIZE);
            AuditLogChecksum batch = service.generateChecksumBatch(batchSize, ctx.currentUserId());
            data.put("checksumBatch", batchView(batch));
        }
        VerifyResult r = service.verify(from, to, ctx.currentUserId());
        data.put("passed", r.passed);
        data.put("fromSeq", r.fromSeq);
        data.put("toSeq", r.toSeq);
        data.put("checkedCount", r.checkedCount);
        data.put("verifiedBatches", r.verifiedBatches);
        data.put("mismatches", r.mismatches);
        data.put("verifiedTime", r.verifiedTime == null ? null : r.verifiedTime.toString());
        data.put("verifiedBy", r.verifiedBy);
        data.put("note", r.passed ? "防篡改校验通过" : "防篡改校验未通过：已告警并阻断后续导出");
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    private static Map<String, Object> batchView(AuditLogChecksum b) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (b == null) {
            return m;
        }
        m.put("batchNo", b.batchNo);
        m.put("batchStartSeq", b.batchStartSeq);
        m.put("batchEndSeq", b.batchEndSeq);
        m.put("batchHeadHash", b.batchHeadHash);
        m.put("batchTailHash", b.batchTailHash);
        m.put("batchChecksum", b.batchChecksum);
        m.put("recordCount", b.recordCount);
        return m;
    }

    private static Map<String, Object> logView(AuditLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.id);
        m.put("subAccountId", log.subAccountId);
        m.put("auditUserId", log.auditUserId);
        m.put("actionType", log.actionType);
        m.put("queryCondition", log.queryCondition);
        m.put("resultCount", log.resultCount);
        m.put("exportFileId", log.exportFileId);
        m.put("chainIntegrity", log.chainIntegrity);
        m.put("actionTime", log.actionTime == null ? null : log.actionTime.toString());
        m.put("seqInChain", log.seqInChain);
        return m;
    }

    private static Input read(RequestContext ctx) {
        Input in = null;
        String b = ctx.body();
        if (b != null && !b.trim().isEmpty()) {
            in = JsonCodec.fromJson(b, Input.class);
        }
        if (in == null) {
            in = new Input();
        }
        if (in.fromTime == null) {
            in.fromTime = text(ctx.queryParam("fromTime"));
        }
        if (in.toTime == null) {
            in.toTime = text(ctx.queryParam("toTime"));
        }
        if (in.auditUserId == null) {
            in.auditUserId = longOrNull(ctx.queryParam("auditUserId"));
        }
        if (in.actionType == null) {
            in.actionType = text(ctx.queryParam("actionType"));
        }
        if (in.subAccountId == null) {
            in.subAccountId = intOrNull(ctx.queryParam("subAccountId"));
        }
        if (in.fromSeq == null) {
            in.fromSeq = text(ctx.queryParam("fromSeq"));
        }
        if (in.toSeq == null) {
            in.toSeq = text(ctx.queryParam("toSeq"));
        }
        if (in.page == null) {
            in.page = text(ctx.queryParam("page"));
        }
        if (in.size == null) {
            in.size = text(ctx.queryParam("size"));
        }
        if (in.sort == null) {
            in.sort = text(ctx.queryParam("sort"));
        }
        if (in.generateBatch == null) {
            in.generateBatch = Boolean.valueOf("true".equalsIgnoreCase(text(ctx.queryParam("generateBatch"))));
        }
        return in;
    }

    private static LocalDateTime dateTime(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(t);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "时间须为 ISO-8601（如 2026-10-06T10:00:00）：" + raw);
        }
    }

    private static long toLong(String raw, long def) {
        String t = text(raw);
        if (t == null) {
            return def;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "整数参数非法：" + raw);
        }
    }

    private static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
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

    /** 查询/校验入参（GET 查询参数或 POST 请求体）。 */
    public static final class Input {
        /** 起始时间（ISO-8601）。 */
        public String fromTime;
        /** 截止时间（ISO-8601）。 */
        public String toTime;
        /** 查询人。 */
        public Long auditUserId;
        /** 动作类型。 */
        public String actionType;
        /** 子账户。 */
        public Integer subAccountId;
        /** 校验范围（起，链内序号）。 */
        public String fromSeq;
        /** 校验范围（止，链内序号）。 */
        public String toSeq;
        /** 是否生成批次校验值。 */
        public Boolean generateBatch;
        /** 批次行数。 */
        public String batchSize;
        /** 页码。 */
        public String page;
        /** 每页条数。 */
        public String size;
        /** 排序。 */
        public String sort;
    }
}
