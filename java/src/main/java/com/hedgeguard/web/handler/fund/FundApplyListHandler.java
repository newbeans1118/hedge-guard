package com.hedgeguard.web.handler.fund;

import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.entity.DepositWithdrawApply;
import com.hedgeguard.service.FundService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.PageData;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出入金申请列表 {@code GET /api/v1/fund/apply-list}（{@code FR-FUND-8} 验收 3；
 * {@code T-195}，{@code te-verify-t055} 披露①承接）。
 *
 * <p><b>为何补此只读端点</b>：验收 3「未收到回执时状态为『超时待查』，系统可通过<b>查询接口</b>
 * 确认最终结果」——此前 {@code FUND} 模块无「出入金申请列表」只读端点，超时态资金岗无法定位
 * 申请 ID（fund.js 页面此前以提示条如实披露「写后经流水可见」形态）。本端点为<b>纯只读</b>
 * （仅 GET；POST 分发由 {@code RequestDispatcher} 405 兜底 + 本 handler 不消费 body）。
 *
 * <p>查询参数（全可空）：{@code from}/{@code to}（申请创建时间 yyyy-MM-dd，含当日）、
 * {@code subAccountId}（读面可见性断言 + 可见集过滤）、{@code status}（取值域 {@code ck_dw_status}
 * 7 值，含 {@code TIMEOUT_UNKNOWN}；非法值明确报错）、{@code page}/{@code size}/{@code sort}。
 * 缺省排序 {@code id,desc}（最新申请在前，{@code PageQuery} 5 参缺省方向先例）。
 */
public final class FundApplyListHandler implements RouteHandler {

    /** 注册路径（{@code T-195} 新增声明路径，{@code ApiRoutes} FUND 模块表同步）。 */
    public static final String PATH = "/api/v1/fund/apply-list";

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundApplyListHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        // 缺省排序 id,desc（最新在前）；sort 白名单由 PageQuery 标识符校验兜底（防注入），
        // 语义面（非本表列名）交 DAO 实际列解析报错——与 FundFlowHandler 同姿态。
        PageQuery q = PageQuery.of(ctx.queryParam("page"), ctx.queryParam("size"),
                ctx.queryParam("sort"), "id", "desc");
        PageResult<DepositWithdrawApply> page = fundService.listDepositWithdrawApplies(q,
                FundParams.dateTimeEndOfDayOrNull(ctx.queryParam("from"), true),
                FundParams.dateTimeEndOfDayOrNull(ctx.queryParam("to"), false),
                FundParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                FundParams.text(ctx.queryParam("status")),
                ctx.currentUserId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (DepositWithdrawApply a : page.items()) {
            items.add(view(a));
        }
        return HttpResult.json(200, ApiResponse.ok(
                new PageData<>(items, page.total(), page.page(), page.size()), ctx.traceId()));
    }

    /** 列表行视图（字段与 {@code FundApplyHandler.view} 同源；追加 rejectReason 定位驳回原因）。 */
    static Map<String, Object> view(DepositWithdrawApply a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("subAccountId", a.subAccountId);
        m.put("applyNo", a.applyNo);
        m.put("direction", a.direction);
        m.put("amount", a.amount == null ? null : a.amount.toPlainString());
        m.put("status", a.status);
        m.put("applicantId", a.applicantId);
        m.put("reviewerId", a.reviewerId);
        m.put("reviewTime", a.reviewTime == null ? null : a.reviewTime.toString());
        m.put("submitTime", a.submitTime == null ? null : a.submitTime.toString());
        m.put("rejectReason", a.rejectReason);
        m.put("retryOfId", a.retryOfId);
        m.put("originalConfirmed", a.originalConfirmed);
        m.put("createdTime", a.createdTime == null ? null : a.createdTime.toString());
        return m;
    }
}
