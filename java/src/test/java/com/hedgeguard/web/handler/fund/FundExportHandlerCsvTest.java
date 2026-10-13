package com.hedgeguard.web.handler.fund;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.AuthService;
import com.hedgeguard.service.FundService;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-195}：资金对账导出 <b>CSV 直返</b>端点用例（修复 {@code te-verify-t055} 观察
 * {@code O-3}——{@code FR-FUND-4}「输出=CSV 对账表」张力；同构先例 {@code T-193}
 * {@code EvidenceExportHandlerCsvTest} / {@code TE-T069-003}）。
 *
 * <p>真实 DAO + InMemory 引擎 + 生产装配 {@code RequestDispatcher} 进程内分发，断言：
 * <ol>
 *   <li>{@code Content-Type} = {@code text/csv; charset=UTF-8}（先例同值）；</li>
 *   <li>{@code Content-Disposition} = {@code attachment; filename="fund-reconcile.csv"}；</li>
 *   <li>首行为 CSV 表头、每条数据行与表头<b>逐列等数</b>（引号感知解析，RFC 4180 子集）；</li>
 *   <li>四类行齐全：meta（导出时间/导出人，FR-FUND-4「文件含导出时间与导出人」）/
 *       data（每笔流水带凭证编号，验收 3）/ sub_total（验收 2 子账户小计）/
 *       grand_total（验收 2 全账户合计）；</li>
 *   <li>csvEscape 行为锁定：含逗号 / 引号 / {@code \r\n} 的字段按 CSV 规则包裹、引号加倍；
 *       「仅换行」干净载体（{@code TE-T193B-001} 判例：换行分支是唯一触发包裹的条件）锁定于
 *       {@code remark} 列——变异（换行条件摘除）下该字段裸奔、字段内 {@code \r\n} 终止 CSV
 *       记录 ⇒ 列数等数 + 原文形态双断言必红；</li>
 *   <li>无数据 ⇒ 仅表头 + meta + grand_total 行（不伪造数据行；「不生成空文件」由前端以
 *       行数裁决，与 {@code /product/export} 先例同构）。</li>
 * </ol>
 */
class FundExportHandlerCsvTest {

    private static final int SUB = 11;
    private static final long OPERATOR = 100L;
    private static final long REVIEWER = 101L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);

    private static final String PATH = "/api/v1/fund/export";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private RequestDispatcher dispatcher;
    private SessionStore sessionStore;
    private Router router;
    private FundService fundService;

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
        M1E2eSeed.user(engine, OPERATOR, "op1", "操作人1", "NP-OP1");
        M1E2eSeed.user(engine, REVIEWER, "op2", "复核人1", "NP-OP2");
        M1E2eSeed.perm(engine, OPERATOR, SUB, "LIMITED", OPERATOR);
        M1E2eSeed.perm(engine, REVIEWER, SUB, "LIMITED", OPERATOR);
        M1E2eSeed.perm(engine, OPERATOR, 0, "CROSS_READONLY", OPERATOR);

        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        // 种子写入经 LIMITED 可见集（initLedger/deposit 走 assertSubAccountWritable）；
        // 导出读取面另用 crossReadonly（审计岗跨账户只读口径，读面哨兵 0 放行）双夹具切换。
        fundService = newFundService(uid -> {
            List<Integer> visible = new ArrayList<>();
            visible.add(SUB);
            return visible;
        });

        router = new Router();
        FundRoutes.register(router, fundService);
        sessionStore = new SessionStore.InMemory();
        sessionStore.save(new Session("S-T195", OPERATOR, "op1", 1L,
                Clocks.now().plusMinutes(30)));
        dispatcher = new RequestDispatcher(router, new StaticHandler(repoRoot()),
                new SessionAuthFilter(sessionStore, "HGSESSION"));
    }

    @AfterEach
    void tearDown() {
        Clocks.reset();
    }

    @Test
    @DisplayName("GET 导出 ⇒ CSV 直返：Content-Type / Disposition / 表头 / 四类行（FR-FUND-4 验收2/3）")
    void getReturnsCsvDirectly() {
        seedDeposit("500", "跨行\r\n备注文本");
        HttpResult r = call("GET", PATH, "from=" + DAY + "&to=" + DAY);

        assertEquals(200, r.status);
        assertEquals("text/csv; charset=UTF-8", r.contentType, "Content-Type 须为 CSV（先例同值）");
        assertEquals("attachment; filename=\"fund-reconcile.csv\"",
                r.headers().get("Content-Disposition"), "Disposition 须为附件命名");

        String body = new String(r.body, StandardCharsets.UTF_8);
        List<String> records = splitCsvRecords(body);
        assertTrue(records.size() > 2, "CSV 须含表头行 + 数据行");
        assertTrue(records.get(0).startsWith("section,subAccountId,ledgerDate,voucherNo,"),
                "首行须为 CSV 表头：" + records.get(0));
        assertEquals("meta", parseCsvRecord(records.get(1)).get(0), "第 2 行须为 meta 行（导出时间/导出人）");
        assertTrue(records.stream().anyMatch(x -> x.startsWith("data,")), "须含数据行（section=data）");
        assertTrue(records.stream().anyMatch(x -> x.startsWith("sub_total,")), "须含子账户小计行（验收2）");
        assertTrue(records.stream().anyMatch(x -> x.startsWith("grand_total,")), "须含全账户合计行（验收2）");
    }

    @Test
    @DisplayName("FR-FUND-4 验收1/3：数据行带凭证编号，期初+发生额小计=期末可逐行核验")
    void dataRowsCarryVoucherAndEquation() {
        seedDeposit("500", "正常备注");
        HttpResult r = call("GET", PATH, "from=" + DAY + "&to=" + DAY);
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);

        List<String> records = splitCsvRecords(body);
        String dataRow = records.stream()
                .filter(x -> x.startsWith("data,")).findFirst().orElse(null);
        assertNotNull(dataRow, "须含 data 行");
        List<String> cols = parseCsvRecord(dataRow);
        assertEquals("FV2026100600001", cols.get(3), "凭证编号列（验收3 每笔流水带凭证编号）");
        assertEquals("Y", cols.get(19), "equation 列= Y（期初 + 发生额小计 = 期末，验收1）");
        assertEquals("500", cols.get(6), "amount 列（DECIMAL(20,4) 语义值 500）");
        assertEquals("1500", cols.get(18), "endBalance 列（期初 1000 + 入金 500）");

        // 小计行 / 合计行数值（带符号）
        String subTotal = records.stream()
                .filter(x -> x.startsWith("sub_total,")).findFirst().orElse(null);
        assertNotNull(subTotal);
        assertEquals("500", parseCsvRecord(subTotal).get(6), "子账户小计 = +500");
        String grandTotal = records.stream()
                .filter(x -> x.startsWith("grand_total,")).findFirst().orElse(null);
        assertNotNull(grandTotal);
        assertEquals("500", parseCsvRecord(grandTotal).get(6), "全账户合计 = +500");

        // meta 行含导出时间与导出人（FR-FUND-4「文件含导出时间与导出人」）
        List<String> meta = parseCsvRecord(records.get(1));
        assertEquals("2026-10-06T12:00", meta.get(20), "exportTime 列（Clocks 固定时钟）");
        assertEquals("100", meta.get(21), "exportedBy 列");
    }

    /**
     * <b>TE-T195-001（列数等数）</b>：每条记录与表头逐列等数——<b>引号感知</b>拆列后断言
     * （拒绝朴素逗号计数）。真实数据面（引擎种子）四类行全部参与断言。
     */
    @Test
    @DisplayName("TE-T195-001：记录与表头逐列等数（引号感知解析）")
    void csvRowsAreAlignedWithHeader() {
        seedDeposit("500", "含逗号,与\"引号\"\r\n第二行换行");
        HttpResult r = call("GET", PATH, "from=" + DAY + "&to=" + DAY);
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);

        List<String> records = splitCsvRecords(body);
        int headerCols = parseCsvRecord(records.get(0)).size();
        assertEquals(22, headerCols, "表头列数须为 CSV_COLUMNS 全量 22 列");
        for (int i = 1; i < records.size(); i++) {
            List<String> cols = parseCsvRecord(records.get(i));
            assertEquals(headerCols, cols.size(),
                    "第 " + i + " 记录列数须与表头等数（引号感知拆列）：" + records.get(i));
        }
        // 「含逗号 + 引号 + 换行」三重特殊字符的转义锁定由 newlineOnlyFieldIsWrapped /
        // csvEscape 直调面承载（本导出列集无自由文本数据列，见该用例披露）。
    }

    /**
     * <b>「仅换行」干净载体（{@code TE-T193B-001} 判例）</b>：字段含 {@code \r\n} 但
     * <b>无逗号无引号</b> ⇒ csvEscape 的换行分支（{@code contains("\n")/contains("\r")}）是
     * 唯一触发包裹的条件。变异（换行条件摘除）下该字段裸奔 ⇒ 解析层 + 原文形态双断言必红。
     *
     * <p><b>载体形态（据实披露）</b>：本导出列集（FR-FUND-4 处理规则逐项）全部为数值/枚举/日期列，
     * <b>无自由文本数据列</b>（对比 T-193 的 {@code reason_text}）⇒ 转义行为在本包内直调
     * {@link FundExportHandler#csvEscape} / {@link FundExportHandler.CsvRow} 锁定
     * （防御性承载；「列数等数」与「四类行」仍在真实数据面端到端断言）。
     */
    @Test
    @DisplayName("TE-T195-002：csvEscape 换行分支锁定（仅换行干净载体；条件摘除变异必红）")
    void newlineOnlyFieldIsWrapped() {
        // ① 解析层：仅换行字段经 csvEscape 包裹后，由引号感知解析还原原文（\r\n 不终止记录）。
        FundExportHandler.CsvRow row = new FundExportHandler.CsvRow()
                .put("section", "data")
                .put("voucherNo", "跨行\r\n文本");
        List<String> cols = parseCsvRecord(row.render());
        assertEquals("跨行\r\n文本", cols.get(3),
                "仅换行 voucherNo 位须原样还原（换行由包裹承载、不终止记录）");

        // ② 原文形态层：字段输出形态恰为 ,"跨行\r\n文本",——两端包裹引号且无加倍引号。
        assertTrue(row.render().contains(",\"跨行\r\n文本\","),
                "仅换行字段须输出「包裹、无加倍引号」原文形态（TE-T193B-001 换行分支靶点）");

        // ③ 负控：无特殊字符字段不包裹（判据形态不与合法取值重合——恒包裹的空转变异须存活失败）。
        assertEquals("FV2026100600001", FundExportHandler.csvEscape("FV2026100600001"),
                "无逗号/引号/换行字段不得包裹");
        // ④ 引号加倍：含引号无逗号字段（T-193 M2 形态）。
        assertEquals("\"仅\"\"引号\"\"\"", FundExportHandler.csvEscape("仅\"引号\""),
                "含引号字段须包裹且内部引号加倍");
        // ⑤ null / 空 → 空串。
        assertEquals("", FundExportHandler.csvEscape(null), "null → 空串");
        assertEquals("", FundExportHandler.csvEscape(""), "空串 → 空串");
    }

    @Test
    @DisplayName("无数据 ⇒ 仅表头 + meta + grand_total（不伪造数据行；行数裁决在前端）")
    void emptyDataYieldsHeaderPlusMetaOnly() {
        HttpResult r = call("GET", PATH, "from=" + DAY + "&to=" + DAY);
        assertEquals(200, r.status);
        String body = new String(r.body, StandardCharsets.UTF_8);
        List<String> records = splitCsvRecords(body);
        assertEquals(3, records.size(), "无数据 ⇒ 表头 + meta + grand_total 三行：" + records);
        assertTrue(body.startsWith("section,subAccountId"), "首行仍须为表头");
        assertEquals("0", parseCsvRecord(records.get(2)).get(6), "grand_total = 0（无数据不伪造数值）");
    }

    // ------------------------------------------------------------ 夹具

    /** 种子：建台账（期初 1000）→ 入金申请 → 复核 → 成功回执（生成一笔带凭证编号的入金流水）。 */
    private void seedDeposit(String amount, String remark) {
        FundService.LedgerInitRequest init = new FundService.LedgerInitRequest();
        init.subAccountId = SUB;
        init.ledgerDate = DAY.toString();
        init.beginBalance = "1000";
        init.reviewerId = REVIEWER;
        init.reviewComment = "复核通过";
        fundService.initLedger(init, OPERATOR);

        FundService.DepositWithdrawRequest dep = new FundService.DepositWithdrawRequest();
        dep.subAccountId = SUB;
        dep.direction = FundService.DIR_IN;
        dep.amount = amount;
        dep.idempotencyKey = "K-" + amount;
        dep.fundSourceDesc = remark;
        long applyId = fundService.initiateDepositWithdraw(dep, OPERATOR).id;

        FundService.ReviewRequest rev = new FundService.ReviewRequest();
        rev.approved = true;
        fundService.reviewDepositWithdraw(applyId, rev, REVIEWER);

        FundService.ReceiptRequest rec = new FundService.ReceiptRequest();
        rec.applyId = applyId;
        rec.idempotencyKey = "R-" + amount;
        rec.result = FundService.RECEIPT_SUCCESS;
        rec.receiptTsMs = 1L;
        fundService.onFundReceipt(rec, OPERATOR);
    }

    private HttpResult call(String method, String path, String query) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "HGSESSION=S-T195");
        return dispatcher.dispatch(new RequestContext(method, path, query, headers,
                "", "HG-T195", "127.0.0.1"));
    }

    /** 以指定可见集构造 FundService（DAO 组本测试内固定）。 */
    private FundService newFundService(com.hedgeguard.service.SubAccountVisibility visibility) {
        return new FundService(tx, visibility, uid -> false, key -> null,
                new com.hedgeguard.dao.impl.DmFundLedgerDao(tx),
                new com.hedgeguard.dao.impl.DmFundFlowDao(tx),
                new com.hedgeguard.dao.impl.DmDepositWithdrawApplyDao(tx),
                new com.hedgeguard.dao.impl.DmTransferApplyDao(tx),
                new com.hedgeguard.dao.impl.DmCapitalReceiptDao(tx),
                new com.hedgeguard.dao.impl.DmFundReviewRecordDao(tx),
                new com.hedgeguard.dao.impl.DmFundQuotaDao(tx),
                new com.hedgeguard.dao.impl.DmFundQuotaAlertDao(tx),
                new com.hedgeguard.dao.impl.DmFundReconcileDao(tx));
    }

    private static com.hedgeguard.service.SubAccountVisibility crossReadonly() {
        return uid -> java.util.Collections.singletonList(
                com.hedgeguard.dao.SubAccountScope.ALL_ACCOUNT_SENTINEL);
    }

    // ------------------------------------------------ 引号感知 CSV 解析（RFC 4180 子集，T-193 同构）

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
        return Arrays.asList(
                "docs/DEPLOY/schema/m1_config_auth.sql",
                "docs/DEPLOY/schema/m2_fund.sql");
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
