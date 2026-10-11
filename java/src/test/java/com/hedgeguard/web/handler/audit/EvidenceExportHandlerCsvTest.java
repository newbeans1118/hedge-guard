package com.hedgeguard.web.handler.audit;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.EvidenceExportDao;
import com.hedgeguard.dao.impl.DmAuditLogChecksumDao;
import com.hedgeguard.dao.impl.DmAuditLogDao;
import com.hedgeguard.dao.impl.DmEvidenceExportDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmHedgePlanExposureDao;
import com.hedgeguard.dao.impl.DmOperationLogDao;
import com.hedgeguard.dao.impl.DmPlanApprovalDao;
import com.hedgeguard.dao.impl.DmSettleCycleDao;
import com.hedgeguard.dao.impl.DmTradeOrderDao;
import com.hedgeguard.dao.impl.DmTradeOrderReportDao;
import com.hedgeguard.dao.impl.DmTradePlanDao;
import com.hedgeguard.dao.impl.DmTradeReasonDao;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.M3AuditTraceSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AuditService;
import com.hedgeguard.service.AuditLogService;
import com.hedgeguard.service.EvidenceExportService;
import com.hedgeguard.service.SubAccountVisibility;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RequestDispatcher;
import com.hedgeguard.web.Router;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionAuthFilter;
import com.hedgeguard.web.SessionStore;
import com.hedgeguard.web.StaticHandler;
import com.hedgeguard.web.handler.audit.EvidenceExportHandler;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-193}：证据链导出 POST <b>CSV 直返</b>端点用例（修复 {@code TE-T069-003}）。
 *
 * <p>与 {@code /spot/price/export}、{@code /product/export} 先例端点用例同构——真实 DAO +
 * InMemory 引擎 + 生产装配 {@code RequestDispatcher} 进程内分发，断言：
 * <ol>
 *   <li>{@code Content-Type} = {@code text/csv; charset=UTF-8}（先例同值）；</li>
 *   <li>{@code Content-Disposition} = {@code attachment; filename="evidence-<exportNo>.csv"}；</li>
 *   <li>首行为 CSV 表头、数据行与表头逐列等数（引号感知解析，见 {@link #csvRowsAreAlignedWithHeader}）；</li>
 *   <li>csvEscape 行为锁定：含逗号/引号/换行字段按 CSV 规则包裹、引号加倍（见
 *       {@link #csvEscapeQuotesFieldsWithCommaQuoteNewline}）；</li>
 *   <li>exportNo 仍落库（GET 记录面可见）；</li>
 *   <li>GET 面不受影响（仍 JSON）。</li>
 * </ol>
 *
 * <p><b>TE-T193-001 修复</b>：上述第 3、4 项断言为本轮补齐——复核变异 M2（引号条件摘除）/
 * M2b（列渲染少一列）曾双双存活，证明「逐列等数」「转义体例」两声称无用例锁定。解析用
 * <b>引号感知</b>拆列（RFC 4180 子集：字段内 {@code ,} {@code "} {@code \r\n} 均包裹处理），
 * 不得用朴素逗号计数（会被引号内逗号欺骗）。
 */
class EvidenceExportHandlerCsvTest {

    private static final int SUB = 11;
    private static final long PRODUCT = 7001L;
    private static final long EXP = 2001L;
    /** 第二敞口（无链路绑定 ⇒ 断点行），多起点请求的逗号拼接载体（TE-T193-001）。 */
    private static final long EXP2 = 2002L;
    private static final long PLAN = 1001L;
    private static final long TRADE_PLAN = 3001L;
    private static final long ORDER = 4001L;
    private static final long TRADE_PLAN2 = 3002L;
    private static final long ORDER2 = 4002L;
    /** 第三指令（TE-T193B-001 换行专项载体）：复用 TRADE_PLAN/EXP（t_trade_order 无 trade_plan_id 唯一约束）。 */
    private static final long ORDER3 = 4003L;
    private static final long SETTLE = 5001L;
    private static final long OPERATOR = 100L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final LocalDateTime TS = DAY.atTime(10, 0);

    private static final String PATH = "/api/v1/audit/evidence/export";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private EvidenceExportDao exportDao;
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
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");
        M2E2eSeed.exposure(engine, EXP, SUB, PRODUCT, "100", DAY);
        // 第二敞口：不绑链路 ⇒ walkFromExposure 记断点行（breaks 载体）；多起点 ⇒ meta.start_id 逗号拼接。
        // as_of_date 错开一日：uk_exposure_key(sub,product,category,direction,calc_mode,as_of_date) 防撞键；
        // 追溯链不按日期过滤（dateFrom/dateTo 仅入审计日志 queryCondition）⇒ 不影响断点产出。
        M2E2eSeed.exposure(engine, EXP2, SUB, PRODUCT, "50", DAY.minusDays(1));
        M2E2eSeed.hedgePlan(engine, PLAN, SUB, PRODUCT, "HP202610060001", "100", DAY, DAY.plusMonths(3));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN, SUB, PLAN, EXP, PRODUCT, "TP-1", "cu2612", "10",
                DAY.plusDays(30));
        M2E2eSeed.tradePlan(engine, TRADE_PLAN2, SUB, PLAN, EXP, PRODUCT, "TP-2", "cu2612", "10",
                DAY.plusDays(30));
        M3AuditTraceSeed.hedgePlanExposure(engine, 6001L, SUB, PLAN, EXP, "100", TS);
        M3AuditTraceSeed.planApproval(engine, 6101L, SUB, PLAN, 1, TS);
        M3AuditTraceSeed.tradeOrder(engine, ORDER, SUB, "HP202610060001-C1", 9001L, TRADE_PLAN, PLAN,
                EXP, PRODUCT, "cu2612", "ALL_FILLED", TS);
        M3AuditTraceSeed.tradeOrder(engine, ORDER2, SUB, "HP202610060001-C2", 9002L, TRADE_PLAN2,
                PLAN, EXP, PRODUCT, "cu2612", "ALL_FILLED", TS);
        M3AuditTraceSeed.tradeOrderReport(engine, 6201L, SUB, "SYS-1", ORDER, "cu2612",
                "ALL_TRADED", 10, 10, TS);
        M3AuditTraceSeed.tradeFill(engine, 6301L, SUB, "TF-1", ORDER, TRADE_PLAN, PLAN, EXP,
                PRODUCT, "cu2612", "70000", 10, DAY, TS);
        M3AuditTraceSeed.settleCycleConfirmed(engine, SETTLE, SUB, "ST-1", DAY, TS);

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        exportDao = new DmEvidenceExportDao(tx);
        TradeReasonSeed.seed(tx, ORDER, "PRICE_TRIGGERED", "套保执行,含逗号与\"引号\"\r\n第二行换行文本");
        // 路径 2（M2 击杀专用）：仅引号、无逗号/换行 ⇒ 变异 M2（引号条件摘除）后该行引号裸奔、断言必红。
        // time_desc 取值域受 ck_trade_reason_time_desc 约束（CHECK 枚举）⇒ 引号专项载体改放 related_type：
        // related_type 同受 CHECK（8 枚举），故引号专项载体落 reason_text（reasonText2 = 仅引号无逗号）。
        TradeReasonSeed.seed(tx, ORDER2, "CONTRACT_SIGNED", "仅\"引号\"无逗号");
        // 路径 3（TE-T193B-001 换行专项）：仅 \r\n 换行、无逗号无引号 ⇒ csvEscape 换行分支
        //（contains("\n")/contains("\r")）是唯一触发包裹的条件——变异（换行条件摘除）下该字段
        // 裸奔、字段内 \r\n 终止 CSV 记录，本类换行专项用例必红。time_desc 取值域受
        // ck_trade_reason_time_desc 约束 ⇒ 沿用枚举值 PRICE_TRIGGERED；uk_trade_reason_order
        // 一单一理由 ⇒ 换行专项载体须独立订单（ORDER3，复用 TRADE_PLAN/EXP，无唯一约束冲突）。
        M3AuditTraceSeed.tradeOrder(engine, ORDER3, SUB, "HP202610060001-C3", 9003L, TRADE_PLAN,
                PLAN, EXP, PRODUCT, "cu2612", "ALL_FILLED", TS);
        TradeReasonSeed.seed(tx, ORDER3, "PRICE_TRIGGERED", "跨行\r\n说明文本");

        EvidenceExportService service = new EvidenceExportService(
                new AuditService(new DmExposureDao(tx), new DmHedgePlanExposureDao(tx),
                        new DmHedgePlanDao(tx), new DmPlanApprovalDao(tx), new DmTradePlanDao(tx),
                        new DmTradeOrderDao(tx), new DmTradeOrderReportDao(tx),
                        new com.hedgeguard.dao.impl.DmTradeFillDao(tx), new DmTradeReasonDao(tx),
                        new DmSettleCycleDao(tx), new DmOperationLogDao(tx),
                        crossReadonly(), voucherStub(), () -> 1, AuditService.endOfTradeDay()),
                new AuditLogService(new DmAuditLogDao(tx), new DmAuditLogChecksumDao(tx),
                        crossReadonly(), AuditLogService.TamperAlarm.NOOP, tx),
                exportDao, new DmTradeReasonDao(tx), dualPnlStub(), crossReadonly(), tx);

        router = new Router();
        EvidenceExportRoutes.register(router, service);
        sessionStore = new SessionStore.InMemory();
        sessionStore.save(new Session("S-T193", OPERATOR, "op1", 1L,
                Clocks.now().plusMinutes(30)));
        dispatcher = new RequestDispatcher(router, new StaticHandler(repoRoot()),
                new SessionAuthFilter(sessionStore, "HGSESSION"));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("POST 导出 ⇒ CSV 直返：Content-Type / Disposition / 表头 / 数据行（TE-T069-003）")
    void postReturnsCsvDirectly() {
        HttpResult r = call("POST", PATH, "{\"startPointType\":\"EXPOSURE\","
                + "\"startPointIds\":[\"" + EXP + "\"],"
                + "\"dateFrom\":\"2026-10-06\",\"dateTo\":\"2026-10-06\"}");

        assertEquals(200, r.status);
        assertEquals("text/csv; charset=UTF-8", r.contentType, "Content-Type 须为 CSV（先例同值）");
        assertEquals("attachment; filename=\"evidence-EV20261006120000000.csv\"",
                r.headers().get("Content-Disposition"),
                "Disposition 须含 exportNo 命名（Clocks 固定时钟 ⇒ exportNo 确定性）");
        String body = new String(r.body, StandardCharsets.UTF_8);
        String[] lines = body.split("\r\n");
        assertTrue(lines.length > 1, "CSV 须含表头行 + 数据行");
        assertTrue(lines[0].startsWith("section,start_type,start_id,"),
                "首行须为 CSV 表头：" + lines[0]);
        assertEquals("meta", lines[1].split(",", -1)[0], "首个数据行须为 meta 行");
        assertTrue(body.contains("node,"), "须含链节点行");
        assertTrue(body.contains("dual_pnl,"), "须含双侧盈亏行");
        assertTrue(body.contains(EvidenceExportService.MARK_COMPLETE), "须含完整性标注");

        // exportNo 仍落库（GET 记录面可见）
        assertNotNull(exportDao.findByExportNo("EV20261006120000000"), "导出记录须落库");
    }

    @Test
    @DisplayName("GET 记录面不受影响：仍 JSON 信封且新导出记录可查")
    void getRecordUnchanged() {
        call("POST", PATH, "{\"startPointType\":\"EXPOSURE\","
                + "\"startPointIds\":[\"" + EXP + "\"]}");
        HttpResult r = call("GET", PATH, "exportNo=EV20261006120000000");
        assertEquals(200, r.status);
        assertEquals("application/json; charset=UTF-8", r.contentType, "GET 面 JSON 形态不变");
        String body = new String(r.body, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"exportNo\":\"EV20261006120000000\""), "记录须可查：" + body);
    }

    /**
     * <b>TE-T193-001 用例①（列数等数）</b>：每条数据行与表头逐列等数——<b>引号感知</b>拆列后断言，
     * 锁定 {@code CsvRow.render()} 按 {@code CSV_COLUMNS} 逐列渲染的行为（复核变异 M2b：
     * 列渲染少一列 ⇒ 本用例必红）。
     *
     * <p>数据形态覆盖三类真实风险面（拒绝朴素逗号计数的动因）：
     * <ol>
     *   <li>多起点 {@code start_id} 逗号拼接串（meta 行，{@code "2001,2002"}）；</li>
     *   <li>{@code reason_text} 自由文本含逗号 + 引号 + {@code \r\n} 换行（reason 行）；</li>
     *   <li>{@code node.detail} map 字面量自带逗号（node 行）。</li>
     * </ol>
     * 注意 {@code body.split("\r\n")} 本身即引号感知之外的合法拆行前提：单字段内换行被
     * {@code csvEscape} 包裹在引号内、且被加倍引号包裹的 {@code \r\n} 不会终止 CSV 记录——
     * 因此先按 RFC 4180 语义逐字符扫描拆行，再逐字段拆列。
     */
    @Test
    @DisplayName("TE-T193-001：数据行与表头逐列等数（引号感知解析；M2b 击杀）")
    void csvRowsAreAlignedWithHeader() {
        HttpResult r = call("POST", PATH, "{\"startPointType\":\"EXPOSURE\","
                + "\"startPointIds\":[\"" + EXP + "\",\"" + EXP2 + "\"]}");
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);

        List<String> records = splitCsvRecords(body);
        assertTrue(records.size() > 1, "CSV 须含表头 + 数据行");
        int headerCols = parseCsvRecord(records.get(0)).size();
        assertEquals(27, headerCols, "表头列数须为 CSV_COLUMNS 全量 27 列");
        assertEquals("section,start_type,start_id", records.get(0).substring(0,
                        "section,start_type,start_id".length()),
                "表头前三列须为 section/start_type/start_id");

        // 逐记录断言列数等数（含 meta 逗号拼接行 / node detail 逗号行 / reason 三重特殊字符行）。
        for (int i = 1; i < records.size(); i++) {
            List<String> cols = parseCsvRecord(records.get(i));
            assertEquals(headerCols, cols.size(),
                    "第 " + i + " 数据行列数须与表头等数（引号感知拆列）：" + records.get(i));
        }

        // 语义抽查：meta 行 start_id 列必须是完整拼接串「2001,2002」而非被引号内逗号切断。
        List<String> meta = parseCsvRecord(records.get(1));
        assertEquals("meta", meta.get(0), "首数据行 section=meta");
        assertEquals("2001,2002", meta.get(2), "start_id 须为多起点拼接串（引号包裹不切断）");

        // reason 行三重特殊字符完整透传（未因逗号/引号/换行丢列或丢字符）。
        String reasonRow = records.stream()
                .filter(x -> x.startsWith("reason,") || x.startsWith("reason,\""))
                .findFirst().orElse(null);
        assertNotNull(reasonRow, "须含 reason 行");
        List<String> reasonCols = parseCsvRecord(reasonRow);
        assertEquals("reason", reasonCols.get(0));
        assertEquals("套保执行,含逗号与\"引号\"\r\n第二行换行文本", reasonCols.get(24),
                "reason_text 列（第 25 列）须原样透传逗号/引号/换行");

        // 断点行（EXP2 无绑定链路 ⇒ break 显式落行，R-AUDIT-2）。
        assertTrue(records.stream().anyMatch(x -> x.startsWith("break,")), "EXP2 无链路 ⇒ 须含 break 行");
    }

    /**
     * <b>TE-T193-001 用例②（csvEscape 行为锁定）</b>：构造含逗号 / 引号 / {@code \r\n} 的数据行，
     * 按实现实际行为断言输出原文形态（行为本身不改）——引号包裹、引号加倍、换行原样保留。
     * 变异 M2（csvEscape 引号条件摘除）⇒ 本用例必红；M1（表头摘除）由既有
     * {@code postReturnsCsvDirectly} 承载。
     */
    @Test
    @DisplayName("TE-T193-001：csvEscape 转义行为锁定（M2 击杀）")
    void csvEscapeQuotesFieldsWithCommaQuoteNewline() {
        HttpResult r = call("POST", PATH, "{\"startPointType\":\"EXPOSURE\","
                + "\"startPointIds\":[\"" + EXP + "\",\"" + EXP2 + "\"]}");
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);

        // ① 路径 2 reason 行（reason_text = 仅引号无逗号）：M2 专门形态——
        //    含引号但无逗号/换行；引号条件摘除后该字段引号裸奔、字段内容失真。
        List<String> reasonRow2 = splitCsvRecords(body).stream()
                .map(EvidenceExportHandlerCsvTest::parseCsvRecord)
                .filter(x -> "reason".equals(x.get(0)) && "4002".equals(x.get(19)))
                .findFirst().orElse(null);
        assertNotNull(reasonRow2, "须含 order2 的 reason 行（引号专项载体）");
        assertEquals("仅\"引号\"无逗号", reasonRow2.get(24),
                "reason_text 仅引号须原样还原（引号加倍可逆）");

        // ② 原文形态抽查：引号包裹 + 引号加倍在原始字节层可见（非仅解析层等价）。
        //    reason_text = 仅"引号"无逗号 ⇒ csvEscape 输出 "仅""引号""无逗号"（包裹 + 加倍）。
        //    该字段无逗号/换行 ⇒ 只因引号触发包裹，正是 M2 摘除「contains("\"")」条件的靶点。
        assertTrue(body.contains("\"仅\"\"引号\"\"无逗号\""),
                "仅引号字段须输出「包裹 + 引号加倍」原文形态（M2 靶点）：" + body);
        //    reason_text 含逗号 ⇒ 包裹；内部引号加倍 ⇒ 原文形态 "...""引号""..."。
        assertTrue(body.contains(",\"套保执行,含逗号与\"\"引号\"\"\r\n第二行换行文本\","),
                "含逗号+引号+换行字段须输出包裹+加倍原文形态");
    }

    /**
     * <b>TE-T193B-001 用例③（换行分支独立锁定）</b>：构造<b>仅含 {@code \r\n}、无逗号无引号</b>
     * 的 {@code reason_text}（order3 载体）——此时 csvEscape 的换行分支
     * {@code contains("\n")/contains("\r")} 是包裹的<b>唯一</b>触发条件。
     *
     * <p><b>TE-T193B-001 缺口回填</b>：既有三类载体（start_id 逗号拼接 / node.detail 逗号 /
     * reason_text 逗号+引号+换行）均同时含逗号或引号 ⇒ 复核变异「换行条件摘除」无法被任何
     * 现有用例击杀（换行独立触发分支零用例锁定）。本用例以干净载体补齐：
     * <ul>
     *   <li>解析层：order3 的 reason_text 原样还原（含 {@code \r\n}）——引号感知拆行/拆列下
     *       换行须被包裹承载、不得终止 CSV 记录；</li>
     *   <li>原文形态层：包裹引号恰出现在 order3 reason 行的 reason_text 字段两端
     *       （无逗号/引号 ⇒ 无加倍引号，包裹只能由换行触发——变异必红）。</li>
     * </ul>
     */
    @Test
    @DisplayName("TE-T193B-001：仅换行字段由 csvEscape 换行分支独立触发包裹（干净载体）")
    void csvEscapeWrapsNewlineOnlyField() {
        HttpResult r = call("POST", PATH, "{\"startPointType\":\"EXPOSURE\","
                + "\"startPointIds\":[\"" + EXP + "\"]}");
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);

        // 解析层：order3 的 reason 行存在且 reason_text 原样还原（\r\n 在引号包裹内被承载）。
        List<String> reasonRow3 = splitCsvRecords(body).stream()
                .map(EvidenceExportHandlerCsvTest::parseCsvRecord)
                .filter(x -> "reason".equals(x.get(0)) && "4003".equals(x.get(19)))
                .findFirst().orElse(null);
        assertNotNull(reasonRow3, "须含 order3 的 reason 行（换行专项载体）");
        assertEquals(27, reasonRow3.size(), "换行专项行亦须与表头 27 列等数（\r\n 不终止记录）");
        assertEquals("跨行\r\n说明文本", reasonRow3.get(24),
                "仅换行 reason_text 须原样还原（换行由包裹承载、不终止记录）");

        // 原文形态层：字段输出形态恰为 ,"跨行\r\n说明文本",——两端包裹引号且无加倍引号。
        // 该字段无逗号/引号 ⇒ 若换行条件摘除（变异），字段裸奔、内部 \r\n 终止 CSV 记录，
        // 此处 contains 必不命中 ⇒ 本用例必红。
        assertTrue(body.contains(",\"跨行\r\n说明文本\","),
                "仅换行字段须输出「包裹、无加倍引号」原文形态（TE-T193B-001 换行分支靶点）：" + body);
    }

    // ------------------------------------------------------------ 夹具

    private HttpResult call(String method, String path, String body) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T193");
        return dispatcher.dispatch(new RequestContext(method, path, null, headers,
                body == null ? "" : body, "HG-T193", "127.0.0.1"));
    }

    private static SubAccountVisibility crossReadonly() {
        return uid -> Collections.singletonList(
                com.hedgeguard.dao.SubAccountScope.ALL_ACCOUNT_SENTINEL);
    }

    private static EvidenceExportService.DualPnlProvider dualPnlStub() {
        return (subAccountId, from, to) -> {
            EvidenceExportService.DualPnl dp = new EvidenceExportService.DualPnl();
            dp.physicalPnl = new BigDecimal("800");
            dp.derivativePnl = new BigDecimal("1200");
            return dp;
        };
    }

    private static AuditService.VoucherLookup voucherStub() {
        return new AuditService.VoucherLookup() {
            private AuditService.VoucherRef ref() {
                AuditService.VoucherRef v = new AuditService.VoucherRef();
                v.id = 90001L;
                v.voucherNo = "V-1";
                v.voucherType = "CLOSE_PNL";
                v.subAccountId = SUB;
                v.hedgePlanId = PLAN;
                v.exposureId = EXP;
                v.settleCycleId = SETTLE;
                v.status = "POSTED";
                return v;
            }

            @Override
            public java.util.List<AuditService.VoucherRef> findByExposure(long exposureId) {
                return exposureId == EXP ? Collections.singletonList(ref()) : Collections.emptyList();
            }

            @Override
            public java.util.List<AuditService.VoucherRef> findByHedgePlan(long hedgePlanId) {
                return hedgePlanId == PLAN ? Collections.singletonList(ref()) : Collections.emptyList();
            }

            @Override
            public java.util.List<AuditService.VoucherRef> findBySettleCycle(long settleCycleId) {
                return settleCycleId == SETTLE ? Collections.singletonList(ref())
                        : Collections.emptyList();
            }

            @Override
            public AuditService.VoucherRef findByVoucher(String voucherIdOrNo) {
                return ("V-1".equals(voucherIdOrNo) || "90001".equals(voucherIdOrNo)) ? ref() : null;
            }
        };
    }

    /** 交易理由种子（沿用 {@code EvidenceExportServiceTest#seedReason} 同构，独立成静态夹具）。 */
    private static final class TradeReasonSeed {
        static void seed(TxTemplate tx, long tradeOrderId, String timeDesc, String reasonText) {
            com.hedgeguard.dao.entity.TradeReason reason = new com.hedgeguard.dao.entity.TradeReason();
            reason.subAccountId = SUB;
            reason.tradeOrderId = tradeOrderId;
            reason.relatedType = "PLAN_EXECUTION";
            reason.relatedPlanId = PLAN;
            reason.relatedExposureId = EXP;
            reason.timeDesc = timeDesc;
            reason.reasonText = reasonText;
            reason.forcedByFreqAlert = "N";
            reason.relatedObjectMissingFlag = "N";
            reason.reasonMissingFlag = "N";
            reason.isModified = "N";
            reason.operatorId = OPERATOR;
            reason.fillTime = TS;
            tx.inTransaction(connection -> new DmTradeReasonDao(tx).insert(reason, OPERATOR));
        }
    }

    // ------------------------------------------------ 引号感知 CSV 解析（RFC 4180 子集，TE-T193-001）

    /**
     * 按引号感知语义把 CSV 文本拆为记录（{@code \r\n} 记录分隔；引号内 {@code \r\n} 不分隔）。
     * 输入由实现 {@code render()} 产出：每记录以 {@code \r\n} 结尾（含末行）。
     */
    private static List<String> splitCsvRecords(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inQuotes) {
                cur.append(c);
                if (c == '"') {
                    // 加倍引号 = 字段内字面引号；单引号 = 闭合。
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

    private static java.util.List<String> schemaScripts() {
        return java.util.Arrays.asList(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m1_product.sql",
                "docs/DEPLOY/schema/m1_spot.sql",
                "docs/DEPLOY/schema/m1_plan.sql",
                "docs/DEPLOY/schema/m2_trade.sql",
                "docs/DEPLOY/schema/m2_settle.sql",
                "docs/DEPLOY/schema/m2_fund.sql",
                "docs/DEPLOY/schema/m3_risk.sql",
                "docs/DEPLOY/schema/m3_audit.sql");
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
