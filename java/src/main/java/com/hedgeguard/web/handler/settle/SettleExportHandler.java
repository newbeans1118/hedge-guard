package com.hedgeguard.web.handler.settle;

import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出结算报表 {@code GET /api/v1/settle/export}（{@code T-047}；{@code FR-SETTLE-16}；
 * {@code T-204} 起 <b>CSV 直返</b>）。
 *
 * <p><b>输出形态（{@code T-204} 修复 {@code T-054} 披露①·用户裁定 A 案）</b>：
 * 需求「输出 = <b>CSV/报表文件</b>」——原 JSON 信封形态（exportTime/exportedBy/columns/rows）
 * 与验收 1 的可归档文件形态存在张力（T-054 披露①），改为 <b>CSV 直返</b>
 * （{@code text/csv; charset=UTF-8} + {@code Content-Disposition: attachment}），
 * 形态对齐单表导出先例 {@code ConfigExportHandler} 与 {@code T-195} {@code FundExportHandler}、
 * {@code T-193} {@code EvidenceExportHandler}（TE-T069-003 同构）。
 *
 * <p><b>语义零变更</b>：Service 取数路径（{@code SettleService.exportReport}）不动——
 * 仅导出有权限的子账户范围（验收 4，服务端可见性强制）；含加总对账结论列（验收 1）；
 * 列序（{@code ExportResult.columns} 口径）不变；导出时间与导出人以 <b>meta 行</b>承载
 * （验收 2「文件含导出时间与导出人」；{@code T-195} 四类行先例的子集形态）。
 *
 * <p><b>CSV 列集</b>：{@code section}（行类型：meta/data，{@code T-193/T-195} 先例同构）+
 * {@code ExportResult.columns} 六列逐序（settleNo/tradeDate/subAccountId/status/equity/aggResult）+
 * {@code exportTime}/{@code exportedBy}（meta 行专用，宽列 schema 单表对齐）。
 *
 * <p><b>无数据契约（验收 3）</b>：服务端恒回 CSV <b>表头行</b>，无数据时仅表头一行（不伪造
 * meta/数据行）——前端以「行数 ≤ 1」判空、不触发下载并显式提示（{@code /product/export}、
 * {@code /config/export}、{@code /fund/export} 先例同构）。
 */
public final class SettleExportHandler implements RouteHandler {

    /** 注册路径（逐字取自 {@code ApiRoutes.byModule().get("SETTLE")}）。 */
    public static final String PATH = "/api/v1/settle/export";

    /** CSV 元信息段列名（{@code section} 前置 + ExportResult.columns 逐序 + 导出时间/导出人）。 */
    private static final String[] CSV_COLUMNS = {
            "section", "settleNo", "tradeDate", "subAccountId", "status", "equity", "aggResult",
            "exportTime", "exportedBy"};

    /** CSV 表头行（由 {@link #CSV_COLUMNS} 派生，保证行拼装与列集同源）。 */
    private static final String CSV_HEADER = String.join(",", CSV_COLUMNS);

    private final SettleService settleService;

    /**
     * 构造。
     *
     * @param settleService 结算服务
     */
    public SettleExportHandler(SettleService settleService) {
        this.settleService = settleService;
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        SettleService.ExportResult r = settleService.exportReport(
                SettleCycleHandler.dateOrNull(ctx.queryParam("from"), "from"),
                SettleCycleHandler.dateOrNull(ctx.queryParam("to"), "to"),
                SettleCycleHandler.intOrNull(ctx.queryParam("subAccountId"), "subAccountId"),
                ctx.currentUserId());
        return HttpResult.bytes(200, "text/csv; charset=UTF-8",
                        render(r).getBytes(StandardCharsets.UTF_8))
                .withHeader("Content-Disposition", "attachment; filename=\"settle-report.csv\"");
    }

    /**
     * 渲染 CSV（表头行 + 元信息行 + 数据行；无数据仅表头一行）。
     *
     * <p>数据行与表头<b>逐列等数</b>（具名列填充、未填列归空串，{@code T-195} {@code CsvRow} 先例）；
     * 转义经 {@link #csvEscape}（含逗号 / 引号 / 换行字段包裹、引号加倍——既有前端 csvCell 同构规则）。
     *
     * @param r 导出结果
     * @return CSV 文本（每记录以 {@code \r\n} 结尾；无数据 ⇒ 仅表头一行）
     */
    private static String render(SettleService.ExportResult r) {
        List<String> lines = new ArrayList<>();
        lines.add(CSV_HEADER);
        if (r.rows == null || r.rows.isEmpty()) {
            // 验收 3：无数据 ⇒ 仅表头一行（前端以「行数 ≤ 1」判空、不生成文件）。
            return String.join("\r\n", lines) + "\r\n";
        }
        // 元信息行（FR-SETTLE-16 验收 2「文件含导出时间与导出人」；T-195 meta 行先例）。
        lines.add(new CsvRow()
                .put("section", "meta")
                .put("exportTime", r.exportTime == null ? "" : r.exportTime.toString())
                .put("exportedBy", String.valueOf(r.exportedBy))
                .render());
        // 数据行（列集 = ExportResult.columns 全量、逐序不变）。
        for (Map<String, Object> row : r.rows) {
            CsvRow csv = new CsvRow().put("section", "data");
            for (String col : r.columns) {
                Object v = row.get(col);
                csv.put(col, v == null ? "" : String.valueOf(v));
            }
            lines.add(csv.render());
        }
        return String.join("\r\n", lines) + "\r\n";
    }

    /**
     * CSV 行构造器（具名列填充，{@code T-195} {@code CsvRow} 同构）：按 {@link #CSV_COLUMNS}
     * 顺序渲染、未填列归空串，保证<b>数据行与表头逐列对齐</b>（列语义不漂移）。
     *
     * <p>包私有可视性（{@code T-195} 判例同构）：本导出列集（{@code ExportResult.columns} 口径）
     * 全部为编号/日期/枚举/数值列，<b>无自由文本列</b> ⇒ 转义分支（逗号/引号/换行）在真实数据面
     * 不可由引擎种子自然触发；转义行为由 {@code SettleExportHandlerCsvTest} 在本包内直调锁定
     * （防御性承载；变异击杀面不变）。
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
     * CSV 字段转义（对齐 {@code ConfigService.csv} / {@code FundExportHandler.csvEscape} 先例；
     * 与既有前端 {@code csvCell} 规则同构：含逗号 / 引号 / {@code \r} / {@code \n} 的字段以引号
     * 包裹、内部引号加倍）。
     *
     * <p>包私有可视性：同 {@link CsvRow}——本导出列集无自由文本列，转义为防御性承载；
     * 行为锁定由本包内测试直调。
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
