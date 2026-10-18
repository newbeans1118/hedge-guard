package com.hedgeguard.web.handler.acct;

import com.hedgeguard.service.Cas24SelfcheckService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.nio.charset.StandardCharsets;

/**
 * 会计面报告导出 {@code /api/v1/acct/export}（{@code FR-ACCT-14} 验收 3/4；{@code T-197}）。
 *
 * <p><b>GET</b>：{@code selfcheckNo} 必填 ⇒ CAS 24 自检报告 <b>CSV 直返</b>
 * （{@code text/csv; charset=UTF-8} + {@code Content-Disposition: attachment;
 * filename="cas24-selfcheck-<selfcheckNo>.csv"}，形态对齐 {@code /audit/evidence/export}
 * （T-193）与 {@code /fund/export} 先例）。报告含自检时间与执行人（验收 4）+「待外部复核」
 * 标注与「自检不替代外部复核」显式说明（验收 3，R-ACCT-10）。
 *
 * <p><b>注入面</b>：批次号先经服务层字符白名单校验（{@code SC<yyyyMMdd><seq4>}）再进
 * {@code Content-Disposition}——非法字符（引号/换行等响应头注入载体）直接参数错误（T-107 先例）。
 */
public final class SelfcheckExportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("ACCT")}）。 */
    public static final String PATH = "/api/v1/acct/export";

    private final Cas24SelfcheckService selfcheckService;

    /**
     * 构造。
     *
     * @param selfcheckService CAS 24 自检服务
     */
    public SelfcheckExportHandler(Cas24SelfcheckService selfcheckService) {
        this.selfcheckService = selfcheckService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            return HttpResult.json(405, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "仅支持 GET（导出为只读动作）；复现=以 GET /acct/export?selfcheckNo=… 重试",
                    ctx.traceId()));
        }
        String selfcheckNo = AcctParams.text(ctx.queryParam("selfcheckNo"));
        if (selfcheckNo == null) {
            return HttpResult.json(400, ApiResponse.fail(ErrorCode.PARAM_INVALID,
                    "selfcheckNo 必填（自检批次号，如 SC202610130001）；复现=补齐查询参数后重试",
                    ctx.traceId()));
        }
        Cas24SelfcheckService.SelfcheckExportResult r =
                selfcheckService.exportReport(selfcheckNo, ctx.currentUserId());
        return HttpResult.bytes(200, "text/csv; charset=UTF-8",
                        r.content.getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Disposition",
                        "attachment; filename=\"" + r.fileName + "\"");
    }
}
