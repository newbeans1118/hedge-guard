package com.hedgeguard.web.handler.plan;

import com.hedgeguard.service.PlanService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计划状态机动作 {@code POST /api/v1/plan/{id}/{action}}（{@code T-016}；{@code FR-PLAN-10~12/17/18}）。
 *
 * <p>动作清单见 {@link Action}；每个动作**由会话身份**决定操作人/审批人
 * （{@code ctx.currentUserId()}），**不接受**请求体传入身份 —— 否则可绕过"发起人 ≠ 审批人"
 * （{@code R-PLAN-2}）。
 *
 * <p>额度随状态占用/释放，逐次落 {@code t_plan_state_log}
 * （{@code OCCUPY}：审批通过；{@code RELEASE}：终止，且额度 = 计划量 − 已成交量，{@code R-PLAN-5}）。
 */
public final class PlanActionHandler implements RouteHandler {

    /** 可执行的状态机动作。 */
    public enum Action {
        /** 提交审批（须存在当前版本书面文件）。 */
        SUBMIT("submit"),
        /** 审批通过（占额）。 */
        APPROVE("approve"),
        /** 驳回（原因必填）。 */
        REJECT("reject"),
        /** 撤销（待审批 → 草稿）。 */
        WITHDRAW("withdraw"),
        /** 冻结（额度继续占用）。 */
        FREEZE("freeze"),
        /** 解冻。 */
        UNFREEZE("unfreeze"),
        /** 终止（释放额度）。 */
        TERMINATE("terminate"),
        /** 延期（须给依据）。 */
        EXTEND("extend");

        private final String segment;

        Action(String segment) {
            this.segment = segment;
        }

        /** 注册路径（逐字取自 {@code §4.1.1}）。 */
        public String path() {
            return ApiRoutes.full("/plan/{id}/" + segment);
        }
    }

    private final PlanService planService;
    private final Action action;

    /**
     * 构造。
     *
     * @param planService 计划服务
     * @param action      动作
     */
    public PlanActionHandler(PlanService planService, Action action) {
        this.planService = planService;
        this.action = action;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        long planId = PlanRoutes.parseId(ctx.pathParam("id"), "id");
        long operatorId = ctx.currentUserId();
        Request request = JsonCodec.fromJson(safeBody(ctx), Request.class);
        String reason = request == null ? null : request.reason;

        Object data = null;
        switch (action) {
            case SUBMIT:
                // 客户端 precheckResult 一律由服务端忽略（CONTRACTS §1.5）；响应由服务端回填快照 + unverified
                PlanService.PrecheckResult precheck = planService.submitForApproval(planId,
                        request == null ? null : request.precheckResult, operatorId);
                data = submitData(precheck);
                break;
            case APPROVE:
                planService.approve(planId, operatorId, reason);
                break;
            case REJECT:
                planService.reject(planId, operatorId, requireReason(reason, "驳回原因"));
                break;
            case WITHDRAW:
                planService.withdraw(planId, operatorId, requireReason(reason, "撤销原因"));
                break;
            case FREEZE:
                planService.freeze(planId, operatorId, requireReason(reason, "冻结原因"));
                break;
            case UNFREEZE:
                planService.unfreeze(planId, operatorId);
                break;
            case TERMINATE:
                planService.terminate(planId, operatorId, requireReason(reason, "终止原因"));
                break;
            case EXTEND:
                if (request == null || request.newEndDate == null || request.newEndDate.isEmpty()) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "延期须给出 newEndDate（yyyy-MM-dd）：复现=补齐新期限止");
                }
                planService.extend(planId, PlanRoutes.date(request.newEndDate, "newEndDate"),
                        requireReason(reason, "展期依据"), operatorId);
                break;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "未知动作：" + action + "；复现=核对路由注册");
        }
        return HttpResult.json(200, ApiResponse.ok(data, ctx.traceId()));
    }

    /**
     * 提交审批成功响应 {@code data}（{@code java/CONTRACTS.md} §1.5）。
     *
     * <p>形状：{@code {"precheckResult":<服务端快照>,"unverified":["R-PLAN-3/③"]}} —— 服务端<b>自行生成</b>、
     * 逐项含 {@code rule/current/threshold/verdict/source}；{@code unverified} 显式标注 ③ 规模红线未校验
     * （{@code ARCHITECTURE.md} §6.4 三项可见性之 (a)）。
     *
     * @param precheck 服务端预校验快照
     * @return 响应数据
     */
    private static Map<String, Object> submitData(PlanService.PrecheckResult precheck) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("precheckResult", precheck);
        data.put("unverified", precheck.unverified);
        return data;
    }

    private static String safeBody(RequestContext ctx) {
        String body = ctx.body();
        return body == null || body.trim().isEmpty() ? "{}" : body;
    }

    private static String requireReason(String reason, String what) {
        if (reason == null || reason.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    what + "必填：复现=在请求体给出 reason 后重试");
        }
        return reason;
    }

    /** 动作请求体（按动作取用；无体动作可省略）。 */
    public static final class Request {

        /** 原因（驳回/撤销/冻结/终止/延期必填）。 */
        public String reason;

        /** 提交前合规预校验结果（**服务端一律忽略**；{@code CONTRACTS §1.5}：缺失/为空不予拒单）。 */
        public String precheckResult;

        /** 新期限止（EXTEND 必填，{@code yyyy-MM-dd}）。 */
        public String newEndDate;
    }
}
