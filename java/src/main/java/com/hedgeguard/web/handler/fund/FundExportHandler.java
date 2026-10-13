package com.hedgeguard.web.handler.fund;

import com.hedgeguard.service.FundService;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出资金对账表 {@code GET /api/v1/fund/export}（{@code FR-FUND-4}）。
 *
 * <p>含期初 / 各类型发生额小计 / 期末 / 凭证编号；含子账户小计与全账户合计；
 * 无数据返回空表体（仅表头，{@code FR-FUND-4} 验收 4「无数据时不生成文件」由前端拦截——
 * 与 {@code /product/export}、{@code /config/export} 先例同构：服务端恒回 CSV 表头行，
 * 前端以「行数 ≤ 1」判空并提示）。
 *
 * <p><b>输出形态（{@code T-195} 修复 {@code te-verify-t055} 观察 {@code O-3}）</b>：
 * 需求「输出 = <b>CSV 对账表</b>」——原 JSON 信封形态（columns/rows/subTotals/grandTotal）
 * 与验收 2「导出含子账户小计与全账户合计」的可归档形态不符。改为 <b>CSV 直返</b>
 * （{@code text/csv; charset=UTF-8} + {@code Content-Disposition: attachment}），
 * 形态对齐 {@code T-193}（{@code /audit/evidence/export}，{@code TE-T069-003} 同构先例）与
 * {@code /spot/price/export}、{@code /product/export} 导出先例。
 *
 * <p><b>CSV 列集（取自 {@code FR-FUND-4} 处理规则原文逐项承载）</b>：
 * 明细行 19 列（{@code FundService.ExportResult.columns}——subAccountId / ledgerDate / voucherNo /
 * flowType / direction / amount / balanceAfter / 期初 beginBalance / 各类型发生额小计
 * amountIn~differenceAdjust / occurrenceTotal / 期末 endBalance / equation）；
 * 另含<b>元信息行</b>（导出时间 / 导出人，{@code FR-FUND-4}「文件含导出时间与导出人」）、
 * <b>子账户小计行</b>与<b>全账户合计行</b>（验收 2「便于加总对账」）——四类行共用单表宽列 schema、
 * 以 {@code section} 列区分（{@code T-193} 先例同构：data/meta/sub_total/grand_total）。
 *
 * <p><b>「流式」实现裁量（据实披露，同 {@code T-193}）</b>：HTTP 载体 {@link HttpResult} 为同步
 * 字节体（无 chunked / 流式接缝），故按先例口径实现为<b>服务端同步拼装 + CSV 直返</b>
 * （不落盘、不经 JSON 内嵌）。
 */
public final class FundExportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("FUND")}）。 */
    public static final String PATH = "/api/v1/fund/export";

    /** CSV 元信息段列名（四类行共用单表宽列 schema，{@code section} 列区分；T-193 先例）。 */
    private static final String[] CSV_COLUMNS = {
            "section", "subAccountId", "ledgerDate", "voucherNo", "flowType",
            "direction", "amount", "balanceAfter",
            "beginBalance", "amountIn", "amountOut", "transferIn", "transferOut",
            "pnlCarryover", "commission", "marginChange", "differenceAdjust",
            "occurrenceTotal", "endBalance", "equation", "exportTime", "exportedBy"};

    /** CSV 表头行（由 {@link #CSV_COLUMNS} 派生，保证行拼装与列集同源）。 */
    private static final String CSV_HEADER = String.join(",", CSV_COLUMNS);

    private final FundService fundService;

    /**
     * 构造。
     *
     * @param fundService 资金服务
     */
    public FundExportHandler(FundService fundService) {
        this.fundService = fundService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        FundService.ExportResult r = fundService.exportReconcile(
                FundParams.dateOrNull(ctx.queryParam("from"), "from"),
                FundParams.dateOrNull(ctx.queryParam("to"), "to"),
                FundParams.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                ctx.currentUserId());
        return HttpResult.bytes(200, "text/csv; charset=UTF-8",
                        render(r).getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Disposition", "attachment; filename=\"fund-reconcile.csv\"");
    }

    /**
     * 渲染 CSV（表头行 + 元信息行 + 数据行 + 子账户小计行 + 全账户合计行）。
     *
     * <p>数据行与表头<b>逐列等数</b>（具名列填充、未填列归空串，{@code T-193} {@code CsvRow} 先例）；
     * 转义经 {@link #csvEscape}（含逗号 / 引号 / 换行字段包裹、引号加倍——「仅换行」分支为
     * {@code TE-T193B-001} 判例的干净载体触发条件）。
     *
     * @param r 导出结果
     * @return CSV 文本（每记录以 {@code \r\n} 结尾）
     */
    private static String render(FundService.ExportResult r) {
        List<String> lines = new ArrayList<>();
        lines.add(CSV_HEADER);
        // 元信息行（FR-FUND-4「文件含导出时间与导出人」）。
        lines.add(new CsvRow()
                .put("section", "meta")
                .put("exportTime", r.exportTime == null ? "" : r.exportTime.toString())
                .put("exportedBy", String.valueOf(r.exportedBy))
                .render());
        // 数据行（每笔流水带凭证编号，验收 3；列集 = ExportResult.columns 全量）。
        for (Map<String, Object> row : r.rows) {
            CsvRow csv = new CsvRow().put("section", "data");
            for (String col : r.columns) {
                Object v = row.get(col);
                csv.put(col, v == null ? "" : String.valueOf(v));
            }
            lines.add(csv.render());
        }
        // 子账户小计行（验收 2；带符号金额，键 = subAccountId）。
        for (Map.Entry<Integer, BigDecimal> e : r.subTotals.entrySet()) {
            lines.add(new CsvRow()
                    .put("section", "sub_total")
                    .put("subAccountId", String.valueOf(e.getKey()))
                    .put("amount", e.getValue().toPlainString())
                    .render());
        }
        // 全账户合计行（验收 2「便于加总对账」）。
        lines.add(new CsvRow()
                .put("section", "grand_total")
                .put("amount", r.grandTotal == null ? "" : r.grandTotal.toPlainString())
                .render());
        return String.join("\r\n", lines) + "\r\n";
    }

    /**
     * CSV 行构造器（具名列填充，{@code T-193} {@code CsvRow} 同构）：按 {@link #CSV_COLUMNS}
     * 顺序渲染、未填列归空串，保证<b>数据行与表头逐列对齐</b>（列语义不漂移）。
     *
     * <p>包私有可视性（{@code T-195}）：本导出列集（FR-FUND-4 处理规则逐项）全部为
     * 数值/枚举/日期列，<b>无自由文本列</b> ⇒ 转义分支（逗号/引号/换行）在真实数据面不可由
     * 引擎种子自然触发；转义行为（含「仅换行」干净载体）由 {@code FundExportHandlerCsvTest}
     * 在本包内直调锁定（变异击杀面不变）。
     */
    static final class CsvRow {
        private final Map<String, String> cells = new LinkedHashMap<>();

        CsvRow put(String column, String value) {
            cells.put(column, value == null ? "" : value);
            return this;
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < CSV_COLUMNS.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(csvEscape(cells.getOrDefault(CSV_COLUMNS[i], "")));
            }
            return sb.toString();
        }
    }

    /**
     * CSV 字段转义（对齐 {@code VarietyService} / {@code EvidenceExportService} 先例；
     * 额外覆盖换行符）：含逗号 / 引号 / {@code \r} / {@code \n} 的字段以引号包裹、内部引号加倍。
     *
     * <p>包私有可视性（{@code T-195}）：同 {@link CsvRow}——本导出列集无自由文本列，转义为
     * 防御性承载；行为锁定由本包内测试直调（「仅换行」分支为唯一触发包裹的形态，
     * {@code TE-T193B-001} 判例同源）。
     *
     * @param value 原始值
     * @return 转义后的 CSV 字段
     */
    static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\"", "\"\"");
        boolean quoted = text.contains(",") || text.contains("\"")
                || text.contains("\n") || text.contains("\r");
        return quoted ? "\"" + text + "\"" : text;
    }
}
