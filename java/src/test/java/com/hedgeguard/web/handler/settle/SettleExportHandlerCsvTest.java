package com.hedgeguard.web.handler.settle;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.impl.DmSettleAggregationCheckDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.T204SettleExportSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.SettleService;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-204}：结算报表导出 <b>CSV 直返</b>端点用例（修复 {@code T-054} 披露①·用户裁定 A 案——
 * {@code FR-SETTLE-16}「输出 = CSV/报表文件」张力；同构先例 {@code T-195}
 * {@code FundExportHandlerCsvTest} / {@code T-193} {@code EvidenceExportHandlerCsvTest}）。
 *
 * <p>真实 DAO + InMemory 引擎 + 生产装配 {@code RequestDispatcher} 进程内分发，断言：
 * <ol>
 *   <li>{@code Content-Type} = {@code text/csv; charset=UTF-8}（先例同值）；</li>
 *   <li>{@code Content-Disposition} = {@code attachment; filename="settle-report.csv"}；</li>
 *   <li>首行为 CSV 表头、每条数据行与表头<b>逐列等数</b>（引号感知解析，RFC 4180 子集）；</li>
 *   <li>行形态：meta（导出时间/导出人，FR-SETTLE-16 验收 2）+ data（含加总对账结论列，验收 1）；
 *       列序 = {@code ExportResult.columns} 口径逐序不变（T-204 语义零变更）；</li>
 *   <li>可见性强制不回归（验收 4）：仅导出有权限子账户范围的行；越权 subAccountId 筛选被拒；</li>
 *   <li>无数据 ⇒ 仅表头一行（前端以「行数 ≤ 1」判空、不生成文件——验收 3；先例同构）；</li>
 *   <li>csvEscape 行为锁定：含逗号 / 引号 / {@code \r\n} 的字段按 CSV 规则包裹、引号加倍
 *       （防御性承载——本导出列集无自由文本列，{@code T-195} TE-T195-002 判例同构）。</li>
 * </ol>
 *
 * <p><b>筛选形态披露（据实，InMemoryDmEngine 限制）</b>：{@code SettleService.listCycles} 在
 * 带 from/to/subAccountId 筛选时以括号包裹多原子 WHERE（{@code (is_aggregate = ? AND trade_date >= ? …)
 * AND scope}）——生产 SQL 对 DM8 合法且语义正确，但引擎 {@code parseWhere} 对「括号包裹多原子
 * AND 组」不可求值（{@code listFlow}「1=1 改写」同族限制，判例见 {@code feedback} 台账）。
 * 本单 Service 取数路径零变更 ⇒ 本测试以<b>无筛选形态</b>承载空态/数据态/可见性/列数等数
 * （无筛选 WHERE = 单原子括号包裹，引擎可求值）；越权 subAccountId 筛选在查询前即被
 * {@code assertSubAccountVisible} 拒绝（不经引擎），可端到端断言。带筛选的日期过滤语义
 * 归 DM8 真库执行面（诚实披露，不冒充已测）。
 */
class SettleExportHandlerCsvTest {

    private static final int SUB = 11;
    /** 第二子账户（越权面载体：操作员不可见 ⇒ 不入导出行；显式筛选须被拒）。 */
    private static final int SUB2 = 12;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime TS = DAY.atTime(10, 0);

    private static final String PATH = "/api/v1/settle/export";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private RequestDispatcher dispatcher;
    private SessionStore sessionStore;
    private Router router;

    @BeforeEach
    void buildWorld() throws Exception {
        Clocks.set(Clock.fixed(DAY.atTime(12, 0).atZone(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        for (String rel : schemaScripts()) {
            engine.executeScript(new String(
                    Files.readAllBytes(repoRoot().resolve(rel)), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SA-A", "商品子账户A");
        M2E2eSeed.subAccount(engine, SUB2, "SA-B", "商品子账户B");

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        SettleService service = new SettleService(tx,
                uid -> Collections.singletonList(SUB),
                null, null, null, null,
                new DmSettleCycleDao(tx), null, null, null, null, null,
                new DmSettleAggregationCheckDao(tx), null, null, null);

        router = new Router();
        SettleRoutes.register(router, service);
        sessionStore = new SessionStore.InMemory();
        sessionStore.save(new Session("S-T204", OPERATOR, "op1", 1L,
                Clocks.now().plusMinutes(30)));
        dispatcher = new RequestDispatcher(router, new StaticHandler(repoRoot()),
                new SessionAuthFilter(sessionStore, "HGSESSION"));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("GET 导出 ⇒ CSV 直返：Content-Type / Disposition / 表头 / meta+data 行（FR-SETTLE-16 验收1/2）")
    void getReturnsCsvDirectly() throws Exception {
        seedData();
        HttpResult r = call("GET", PATH, "");
        assertEquals(200, r.status);
        assertEquals("text/csv; charset=UTF-8", r.contentType, "Content-Type 须为 CSV（先例同值）");
        assertEquals("attachment; filename=\"settle-report.csv\"",
                r.headers().get("Content-Disposition"), "Disposition 须为附件命名");

        String body = new String(r.body, StandardCharsets.UTF_8);
        List<String> records = splitCsvRecords(body);
        assertEquals("section,settleNo,tradeDate,subAccountId,status,equity,aggResult,exportTime,exportedBy",
                records.get(0), "首行须为 CSV 表头（列序 = ExportResult.columns 口径逐序）");
        assertEquals("meta", parseCsvRecord(records.get(1)).get(0), "第 2 行须为 meta 行（导出时间/导出人）");
        List<String> meta = parseCsvRecord(records.get(1));
        assertEquals("2026-10-06T12:00", meta.get(7), "exportTime 列（Clocks 固定时钟）");
        assertEquals("100", meta.get(8), "exportedBy 列");
        assertTrue(records.stream().anyMatch(x -> x.startsWith("data,")), "须含数据行（section=data）");

        // 数据行语义：可见子账户的结算单逐列承载（含加总对账结论列，验收 1）
        List<String> data = parseCsvRecord(records.get(2));
        assertEquals("data", data.get(0));
        assertEquals("ST-20261006-001", data.get(1), "settleNo 列");
        assertEquals("2026-10-06", data.get(2), "tradeDate 列");
        assertEquals("11", data.get(3), "subAccountId 列");
        assertEquals("RECONCILED", data.get(4), "status 列");
        assertEquals(0, new BigDecimal(data.get(5)).compareTo(new BigDecimal("12345.5")),
                "equity 列（DECIMAL(20,4) 语义值）");
        assertEquals("CONSISTENT", data.get(6), "aggResult 加总对账结论列（验收 1）");
    }

    /**
     * <b>列数等数</b>：每条记录与表头逐列等数——<b>引号感知</b>拆列后断言
     * （拒绝朴素逗号计数；T-193/T-195 同构判据）。
     */
    @Test
    @DisplayName("记录与表头逐列等数（引号感知解析）")
    void csvRowsAreAlignedWithHeader() throws Exception {
        seedData();
        HttpResult r = call("GET", PATH, "");
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);

        List<String> records = splitCsvRecords(body);
        int headerCols = parseCsvRecord(records.get(0)).size();
        assertEquals(9, headerCols, "表头列数须为 CSV_COLUMNS 全量 9 列");
        for (int i = 1; i < records.size(); i++) {
            List<String> cols = parseCsvRecord(records.get(i));
            assertEquals(headerCols, cols.size(),
                    "第 " + i + " 记录列数须与表头等数（引号感知拆列）：" + records.get(i));
        }
    }

    @Test
    @DisplayName("可见性强制不回归（验收 4）：仅导出有权限子账户范围的行；越权筛选被拒")
    void visibilityScopedToPermittedSubAccounts() throws Exception {
        seedData();
        HttpResult r = call("GET", PATH, "");
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);
        List<String> records = splitCsvRecords(body);
        assertEquals(3, records.size(), "可见范围仅 SUB=11 ⇒ 表头 + meta + 1 数据行：" + records);
        assertTrue(records.get(2).contains(",11,"), "数据行须为可见子账户 11：" + records.get(2));
        assertTrue(!body.contains("ST-20261006-002"),
                "不可见子账户 12 的结算单不得入导出（FR-SETTLE-16 验收 4）");

        // 越权筛选：显式指定不可见子账户 ⇒ assertSubAccountVisible 拒绝（JSON 错误信封，非 200）
        HttpResult denied = call("GET", PATH, "subAccountId=" + SUB2);
        assertNotEquals(200, denied.status, "越权 subAccountId 筛选须被拒绝");
        assertTrue(denied.contentType.contains("json"), "拒绝面为 JSON 错误信封（非 CSV）");
    }

    @Test
    @DisplayName("无数据 ⇒ 仅表头一行（不伪造 meta/数据行；前端行数≤1 判空——验收 3）")
    void emptyDataYieldsHeaderOnly() {
        // 无种子 ⇒ 无数据（引擎 parseWhere 限制见类注「筛选形态披露」，空态以无筛选形态承载）
        HttpResult r = call("GET", PATH, "");
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);
        List<String> records = splitCsvRecords(body);
        assertEquals(1, records.size(), "无数据 ⇒ 仅表头一行：" + records);
        assertTrue(body.startsWith("section,settleNo"), "首行仍须为表头");
    }

    /**
     * <b>csvEscape 行为锁定（{@code T-195} TE-T195-002 判例同构）</b>：本导出列集
     * （settleNo/tradeDate/subAccountId/status/equity/aggResult）全部为编号/日期/枚举/数值列，
     * <b>无自由文本列</b> ⇒ 转义分支在真实数据面不可由种子自然触发，属防御性承载；
     * 行为本包内直调锁定（变异击杀面不变）。
     */
    @Test
    @DisplayName("csvEscape 转义行为锁定（含逗号/引号/换行包裹、引号加倍；负控不包裹）")
    void csvEscapeBehaviorIsLocked() {
        // ① 仅换行干净载体（TE-T193B-001 判例）：换行分支是唯一触发包裹的条件。
        SettleExportHandler.CsvRow row = new SettleExportHandler.CsvRow()
                .put("section", "data")
                .put("status", "跨行\r\n文本");
        List<String> cols = parseCsvRecord(row.render());
        assertEquals("跨行\r\n文本", cols.get(4),
                "仅换行 status 位须原样还原（换行由包裹承载、不终止记录）");
        assertTrue(row.render().contains(",\"跨行\r\n文本\","),
                "仅换行字段须输出「包裹、无加倍引号」原文形态（换行分支靶点）");

        // ② 引号加倍：含引号无逗号字段（T-193 M2 形态）。
        assertEquals("\"仅\"\"引号\"\"\"", SettleExportHandler.csvEscape("仅\"引号\""),
                "含引号字段须包裹且内部引号加倍");
        // ③ 含逗号字段包裹。
        assertEquals("\"a,b\"", SettleExportHandler.csvEscape("a,b"), "含逗号字段须包裹");
        // ④ 负控：无特殊字符字段不包裹（判据形态不与合法取值重合）。
        assertEquals("ST-20261006-001", SettleExportHandler.csvEscape("ST-20261006-001"),
                "无逗号/引号/换行字段不得包裹");
        // ⑤ null / 空 → 空串。
        assertEquals("", SettleExportHandler.csvEscape(null), "null → 空串");
        assertEquals("", SettleExportHandler.csvEscape(""), "空串 → 空串");
    }

    // ------------------------------------------------------------ 夹具

    /**
     * 种子：两笔结算单（可见 11 + 越权 12）+ 加总对账结论（aggResult 列载体）。
     * 供数据态/可见性/列数等数用例；空态用例不调用（无种子 ⇒ 无数据）。
     */
    private void seedData() throws Exception {
        T204SettleExportSeed.settleCycle(engine, 5001L, SUB, "ST-20261006-001", DAY,
                "RECONCILED", "12345.5000", TS, OPERATOR);
        T204SettleExportSeed.settleCycle(engine, 5002L, SUB2, "ST-20261006-002", DAY,
                "CONFIRMED", "87654.2500", TS, OPERATOR);
        T204SettleExportSeed.aggregationCheck(engine, 6001L, DAY, "CONSISTENT", TS, OPERATOR);
    }

    private HttpResult call(String method, String path, String query) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T204");
        return dispatcher.dispatch(new RequestContext(method, path,
                query == null || query.isEmpty() ? null : query, headers,
                "", "HG-T204", "127.0.0.1"));
    }

    // ------------------------------------------------ 引号感知 CSV 解析（RFC 4180 子集，T-193/T-195 同构）

    /** 按引号感知语义把 CSV 文本拆为记录（{@code \r\n} 记录分隔；引号内 {@code \r\n} 不分隔）。 */
    private static List<String> splitCsvRecords(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuotes) {
                cur.append(c);
                if (c == '"') {
                    if (i + 1 < body.length() && body.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                }
            } else if (c == '"') {
                inQuotes = true;
                cur.append(c);
            } else if (c == '\r' && i + 1 < body.length() && body.charAt(i + 1) == '\n') {
                out.add(cur.toString());
                cur.setLength(0);
                i++;
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 引号感知拆列：返回字段值列表（去包裹引号、引号加倍还原）。 */
    private static List<String> parseCsvRecord(String record) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < record.length(); i++) {
            char c = record.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static List<String> schemaScripts() {
        return java.util.Arrays.asList(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m2_settle.sql");
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/DEPLOY/schema"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("无法定位仓库根（docs/DEPLOY/schema）");
        }
        return p;
    }
}
