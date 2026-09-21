package com.hedgeguard.web.handler.spot;

import com.hedgeguard.service.SpotService;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.util.List;
import java.util.Map;

/**
 * 现货价批量导入 {@code POST /api/v1/spot/price/import}
 * （{@code T-109}；{@code FR-SPOT-11} 批量粘贴 / {@code FR-SPOT-12} CSV 导入）。
 *
 * <p>请求体：{@code {"sourceType":"BATCH_PASTE|CSV_IMPORT","subAccountId":N,
 * "importFileName?":…,"overwriteConfirmed?":false,"lines":[…]}}。
 *
 * <p><b>输入为文本块（{@code TE-T109-001} 修复）</b>：列以<b>品种编码</b>为准，对齐
 * {@code FR-SPOT-11}（粘贴文本块）与 {@code FR-SPOT-12}（CSV 文件）的输入契约：
 * <ul>
 *   <li>{@code BATCH_PASTE}：{@code lines} 每行 = 品种编码、日期、现货价（逗号或制表符分隔），无表头；</li>
 *   <li>{@code CSV_IMPORT}：{@code lines[0]} 为表头（须含 {@link SpotService#CSV_HEADER} 全部列），
 *       其余为数据行；{@code importFileName} 必填。</li>
 * </ul>
 *
 * <p><b>全量预校验 → 一次性提交</b>：任一行不合法即返回 {@code 10019} 并给出逐行原因，
 * <b>不产生任何部分写入</b>；同品种同日已有价时未确认覆盖则整体拒绝（{@code R-SPOT-3}）。
 */
public final class SpotPriceImportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code §4.1.1}）。 */
    public static final String PATH = ApiRoutes.full("/spot/price/import");

    private final SpotService spotService;

    /**
     * 构造。
     *
     * @param spotService 现货服务
     */
    public SpotPriceImportHandler(SpotService spotService) {
        this.spotService = spotService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Request request = JsonCodec.fromJson(ctx.body(), Request.class);
        if (request == null || request.lines == null || request.lines.isEmpty()) {
            throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                    "导入须提交 {subAccountId, lines:[…]}：BATCH_PASTE 每行 品种编码,日期,现货价；"
                            + "CSV_IMPORT 首行为表头（须含 " + SpotService.CSV_HEADER + "）；"
                            + "复现=按 FR-SPOT-11/12 提交");
        }
        Map<String, Object> summary = spotService.importSpotPrices(request.lines, request.subAccountId,
                request.sourceType, request.importFileName,
                Boolean.TRUE.equals(request.overwriteConfirmed), ctx.currentUserId());
        return HttpResult.json(200, ApiResponse.ok(summary, ctx.traceId()));
    }

    /** 导入请求体。 */
    public static final class Request {

        /** 来源类型（{@code BATCH_PASTE} / {@code CSV_IMPORT}；缺省 {@code BATCH_PASTE}）。 */
        public String sourceType;

        /** 录入归属子账户（必填，&gt; 0）。 */
        public Integer subAccountId;

        /** CSV 导入文件名（{@code sourceType=CSV_IMPORT} 时必填）。 */
        public String importFileName;

        /** 是否确认覆盖既有同日价。 */
        public Boolean overwriteConfirmed;

        /** 文本行（{@code BATCH_PASTE} 全为数据行；{@code CSV_IMPORT} 首行为表头）。 */
        public List<String> lines;
    }
}
