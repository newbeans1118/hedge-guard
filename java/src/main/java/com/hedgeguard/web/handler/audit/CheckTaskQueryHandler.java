package com.hedgeguard.web.handler.audit;

import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.service.CheckTaskQueryService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核对任务与签署记录查询 REST 面（{@code T-194}；{@code FR-AUDIT-13} 验收 1/2/5、{@code R-AUDIT-11}）。
 *
 * <p>承接 {@code te-verify-t071} 观察 {@code O-1}：{@code CheckTaskQueryService.query/overdue}
 * 服务方法实存且已在 {@code Bootstrap} 装配，但此前 {@code web/} 零 handler 调用 —— 月度核对
 * 任务查询无 REST 端点。本 handler 消费<b>既有</b>服务方法（服务本体零改动）：
 * <ul>
 *   <li>{@code GET /api/v1/audit/check-tasks} —— 分页查询核对任务 + 签署明细
 *       （账号 / 角色 / 时间 / IP / 核对对象快照哈希；验收 1）+ 逾期清单 + 完成率；
 *       筛选：{@code checkType}/{@code status}/{@code subAccountId}/{@code signRole}/
 *       {@code overdueOnly}/{@code asOf}/{@code fromPeriod}/{@code toPeriod}
 *       （{@code FR-AUDIT-13} 输入列；{@code overdueOnly=true} 即验收 2「逾期未签单独筛出」）；</li>
 *   <li>{@code GET /api/v1/audit/check-tasks/overdue} —— 逾期未签清单
 *       （消费 {@link CheckTaskQueryService#overdue}，与 {@code SettleCheckTaskDao.findOverdue}
 *       同口径，另按可见范围过滤）。</li>
 * </ul>
 *
 * <p><b>只读（{@code R-AUDIT-6}）</b>：仅注册 {@code GET}，无写入入口（验收 4）。
 * <b>子账户可见性（{@code R-AUDIT-9}/{@code NFR-SEC-7}）</b>：服务内部经
 * {@code SubAccountVisibility.visibleSubAccountIds} 解析（含哨兵 {@code 0} 跨子账户，验收 5），
 * 本 handler 不另行过滤、亦不放宽。
 *
 * <p><b>响应形状</b>：{@code data} 分页四键（{@code items}/{@code total}/{@code page}/{@code size}）
 * 与 {@code PageData} 逐键同形；另附 {@code overdue}/{@code coverage}/{@code crossSubAccount}/
 * {@code visibleSubAccounts} —— 为不丢弃 {@code CheckTaskQueryService.CheckTaskResult} 的
 * {@code FR-AUDIT-13} 输出列（逾期清单 + 完成率汇总）而扩键（据实披露：未直接复用 {@code PageData}
 * 类，因该载体仅有分页四字段）。
 */
public final class CheckTaskQueryHandler implements RouteHandler {

    /** 核对任务查询路径（{@code T-194} 新增声明，{@code ApiRoutes} 模块表 + 方法注册表同步）。 */
    public static final String PATH = "/api/v1/audit/check-tasks";

    /** 逾期未签清单路径（{@code T-194} 新增声明，{@code ApiRoutes} 模块表 + 方法注册表同步）。 */
    public static final String PATH_OVERDUE = "/api/v1/audit/check-tasks/overdue";

    private final CheckTaskQueryService service;

    /**
     * 构造。
     *
     * @param service 核对任务与签署记录查询服务（{@code Bootstrap} 既有装配实例）
     */
    public CheckTaskQueryHandler(CheckTaskQueryService service) {
        this.service = service;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "核对任务查询为只读端点（R-AUDIT-6），仅支持 GET");
        }
        return PATH_OVERDUE.equals(ctx.path()) ? overdue(ctx) : query(ctx);
    }

    private HttpResult query(RequestContext ctx) {
        CheckTaskQueryService.CheckTaskFilter f = new CheckTaskQueryService.CheckTaskFilter();
        f.checkType = text(ctx.queryParam("checkType"));
        f.status = text(ctx.queryParam("status"));
        f.subAccountId = intOrNull(ctx.queryParam("subAccountId"));
        f.signRole = text(ctx.queryParam("signRole"));
        f.overdueOnly = "true".equalsIgnoreCase(text(ctx.queryParam("overdueOnly")))
                ? Boolean.TRUE : null;
        f.asOf = date(text(ctx.queryParam("asOf")), "asOf");
        f.fromPeriod = text(ctx.queryParam("fromPeriod"));
        f.toPeriod = text(ctx.queryParam("toPeriod"));
        CheckTaskQueryService.CheckTaskResult r = service.query(
                f, ctx.queryParam("page"), ctx.queryParam("size"), ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (CheckTaskQueryService.CheckTaskView v : r.tasks) {
            items.add(taskView(v.task, v.signatures));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        // 分页四键与 PageData 逐键同形（items/total/page/size）
        data.put("items", items);
        data.put("total", r.total);
        data.put("page", r.page);
        data.put("size", r.size);
        // FR-AUDIT-13 输出列：逾期清单 + 完成率汇总 + 可见范围披露
        List<Map<String, Object>> overdue = new ArrayList<>();
        for (SettleCheckTask t : r.overdue) {
            overdue.add(taskView(t, new ArrayList<>()));
        }
        data.put("overdue", overdue);
        data.put("coverage", r.coverage);
        data.put("crossSubAccount", r.crossSubAccount);
        data.put("visibleSubAccounts", r.visibleSubAccounts);
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    private HttpResult overdue(RequestContext ctx) {
        LocalDate asOf = date(text(ctx.queryParam("asOf")), "asOf");
        List<SettleCheckTask> list = service.overdue(asOf, ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (SettleCheckTask t : list) {
            items.add(taskView(t, new ArrayList<>()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", items.size());
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    /** 任务视图（与 {@code SettleCheckHandler.taskView} 同形；签署明细含验收 1 五要素）。 */
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

    private static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Integer intOrNull(String s) {
        String t = text(s);
        if (t == null) {
            return null;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "整数参数非法：" + s);
        }
    }

    private static LocalDate date(String raw, String name) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        try {
            return LocalDate.parse(t);
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, name + " 须为 yyyy-MM-dd：" + raw);
        }
    }
}
