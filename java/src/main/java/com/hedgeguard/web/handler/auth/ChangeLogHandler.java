package com.hedgeguard.web.handler.auth;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.PermissionChangeLog;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 权限变更留痕 {@code GET /api/v1/auth/change-log}（{@code FR-AUTH-11} / {@code FR-AUTH-18}）。
 *
 * <p>返回授权/角色/子账户权限/用户属性等的变更历史（含前后值与复核人）；<b>只增不改不删</b>，
 * <b>无</b>编辑或删除入口（{@code FR-AUTH-11} 验收 2）。默认按变更时间倒序。
 */
public final class ChangeLogHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/auth/change-log");

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务
     */
    public ChangeLogHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        PageQuery query = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "change_time", "desc");
        // T-135：补齐 FR-AUTH-11 验收 3（用户 + 时间范围）+ 输入列（变更类型 / 操作人）。
        Long target = parseOptionalLong(ctx.queryParam("userId"), "userId");
        Long operator = parseOptionalLong(ctx.queryParam("operatorId"), "operatorId");
        String changeTarget = ctx.queryParam("changeTarget");
        LocalDateTime from = parseOptionalDateTime(ctx.queryParam("from"), "from", false);
        LocalDateTime to = parseOptionalDateTime(ctx.queryParam("to"), "to", true);
        PageResult<PermissionChangeLog> page = authService.listPermissionChangeLogs(query, target,
                changeTarget, operator, from, to);
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(page.items(), page.total(), page.page(), page.size()), ctx.traceId()));
    }

    /** 解析可选长整型查询参数；非数字 ⇒ {@code 10001}（不静默忽略）。 */
    private static Long parseOptionalLong(String raw, String name) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数 " + name + " 须为十进制整数：" + raw + "；复现=改正取值", e);
        }
    }

    /**
     * 解析可选时间筛选参数：接受 {@code yyyy-MM-dd'T'HH:mm:ss} 或纯日期 {@code yyyy-MM-dd}。
     * 纯日期用于上界（{@code to}）时取当日 {@code 23:59:59}（含当日），用于下界（{@code from}）时取 {@code 00:00:00}。
     *
     * @param raw        原值（可为 {@code null}/空）
     * @param name       参数名（错误提示用）
     * @param endOfDay   纯日期时是否取当日末刻（{@code to} 传 {@code true}）
     * @return 时间；未提供返回 {@code null}
     */
    private static LocalDateTime parseOptionalDateTime(String raw, String name, boolean endOfDay) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String v = raw.trim();
        try {
            if (v.length() == 10) {
                LocalDate d = LocalDate.parse(v);
                return endOfDay ? d.atTime(23, 59, 59) : d.atStartOfDay();
            }
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数 " + name + " 须为 yyyy-MM-dd 或 yyyy-MM-ddTHH:mm:ss：" + raw
                            + "；复现=改正取值", e);
        }
    }
}
