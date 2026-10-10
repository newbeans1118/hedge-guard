package com.hedgeguard.service;

import com.hedgeguard.dao.impl.DmAnnualScaleDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.impl.DmHedgePlanDao;
import com.hedgeguard.dao.impl.DmLimitUsageStatDao;
import com.hedgeguard.dao.impl.DmProductContractDao;
import com.hedgeguard.dao.impl.DmProductUnitDao;
import com.hedgeguard.dao.impl.DmPurchaseContractDao;
import com.hedgeguard.dao.impl.DmRiskThresholdDao;
import com.hedgeguard.dao.impl.DmSalesContractDao;
import com.hedgeguard.dao.impl.DmTradePositionDao;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.testsupport.M1E2eSeed;
import com.hedgeguard.dao.testsupport.M2E2eSeed;
import com.hedgeguard.dao.testsupport.T066RiskRecomputeSeed;
import com.hedgeguard.service.impl.RiskRecomputeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-186}（{@code TE-T066-001} 修复）：Java 复算 ↔ C++ 事前判定<b>跨栈对拍</b>（Java 侧）。
 *
 * <p>与 C++ 侧 {@code risk_engine_parity_golden_test}（{@code cpp/src/risk_engine/tests/}）
 * 共享同一组 golden 向量文件（{@code cpp/src/risk_engine/tests/golden/*.tsv}，单一事实源）：
 * 同一输入向量分别驱动两侧判定，对 {@code verdict} + 理由码 + 阈值逐字段断言。
 * 对拍口径与<b>刻意差异量化披露</b>见各 golden 文件头注（C++ {@code evalScale} /
 * {@code evalNetPosition} / {@code evalTerm} @ {@code cpp/src/risk_engine/src/risk_engine.cc}）。
 *
 * <p>对拍语义：C++ 为「事前判定」（状态 + 本单增量 {@code inc} 投影），Java 为「复算镜面」
 * （播种<b>事后状态</b> {@code accumulated_pre + inc} / 事后净持仓）。两侧判据为同一谓词
 * （精确交叉相乘 / 严格 {@code >}），本测试逐向量验证该同一性。
 */
class T186RiskParityGoldenTest {

    private static final List<String> SCRIPTS = Arrays.asList(
            "docs/DEPLOY/schema/m1_config_auth.sql",
            "docs/DEPLOY/schema/m1_product.sql",
            "docs/DEPLOY/schema/m1_spot.sql",
            "docs/DEPLOY/schema/m1_plan.sql",
            "docs/DEPLOY/schema/m2_trade.sql",
            "docs/DEPLOY/schema/m2_settle.sql",
            "docs/DEPLOY/schema/m2_fund.sql",
            "docs/DEPLOY/schema/m3_risk.sql");

    private static final int SUB = 1;
    private static final long PRODUCT = 5001L;

    private InMemoryDmEngine engine;
    private RiskRecomputeServiceImpl service;
    private long nextId;

    @BeforeEach
    void setUp() throws Exception {
        fresh();
    }

    /** 每个向量独立引擎（golden 多向量共用同一唯一键：scope/sub/product/year 等）。 */
    private void fresh() throws Exception {
        engine = new InMemoryDmEngine();
        for (String file : SCRIPTS) {
            engine.executeScript(new String(Files.readAllBytes(resolve(file)), StandardCharsets.UTF_8));
        }
        M1E2eSeed.reservedSubAccountZero(engine);
        M2E2eSeed.subAccount(engine, SUB, "SUB1", "子账户1");
        M2E2eSeed.product(engine, PRODUCT, "CU", "铜");

        com.hedgeguard.dao.pool.PoolConfig pool =
                new com.hedgeguard.dao.pool.PoolConfig(0, 4, 500, 2);
        com.hedgeguard.dao.tx.TxTemplate tx = new com.hedgeguard.dao.tx.TxTemplate(
                new com.hedgeguard.dao.pool.DataSourcePool(engine, pool));
        service = new RiskRecomputeServiceImpl(tx,
                new DmAnnualScaleDao(tx), new DmExposureDao(tx), new DmTradePositionDao(tx),
                new DmProductContractDao(tx), new DmProductUnitDao(tx), new DmHedgePlanDao(tx),
                new DmPurchaseContractDao(tx), new DmSalesContractDao(tx),
                new DmLimitUsageStatDao(tx),
                new DmRiskThresholdDao(tx),
                userId -> Collections.singletonList(SUB),
                LimitUsageThresholds.defaults());
        nextId = 1000L;
    }

    // ================================================================ R-RISK-4 规模红线

    @Test
    @DisplayName("跨栈对拍·规模红线：5 组向量（恰 90/80、双算分歧取严、双分支）verdict+码+阈值逐字段一致")
    void parityScale() throws Exception {
        List<String[]> rows = golden("cpp/src/risk_engine/tests/golden/parity_scale_golden.tsv");
        assertTrue(rows.size() >= 3, "规模红线向量须 ≥3 组，实测 " + rows.size());
        for (String[] f : rows) {
            // 列：id category subExpected subAccumPre totalExpected totalAccumPre inc verdict code threshold
            fresh();
            String id = f[0];
            BigDecimal inc = qty(f[6]);
            // Java 复算镜面：播种事后状态 accumulated = accumulated_pre + inc（先换算后相加）
            T066RiskRecomputeSeed.annualScale(engine, nextId++, SUB, "SUB_ACCOUNT", PRODUCT, 2026,
                    dec(f[2]), dec(qty(f[3]).add(inc)), f[1], "0");
            if (Long.parseLong(f[4]) >= 0) {
                T066RiskRecomputeSeed.annualScale(engine, nextId++, 0, "TOTAL_ACCOUNT", PRODUCT,
                        2026, dec(f[4]), dec(qty(f[5]).add(inc)), f[1], "0");
            }
            RiskRecomputeService.ScaleResult r = service.recomputeScale(SUB, PRODUCT, 2026);
            assertEquals(f[7], r.verdict, "向量 " + id + " verdict");
            if ("REJECT".equals(f[7])) {
                assertEquals(f[8], r.code, "向量 " + id + " 理由码");
                assertDecimalEquals(id + " 阈值", ratio(f[9]), r.threshold);
            } else {
                assertNull(r.code, "向量 " + id + " PASS 不应有理由码");
            }
        }
    }

    // ================================================================ R-RISK-5 时点净持仓

    @Test
    @DisplayName("跨栈对拍·时点净持仓：4 组向量（恰等不越、越买向、减仓豁免、双敞口分支）一致")
    void parityNetPosition() throws Exception {
        List<String[]> rows = golden("cpp/src/risk_engine/tests/golden/parity_netpos_golden.tsv");
        assertTrue(rows.size() >= 5, "净持仓向量须 ≥5 组，实测 " + rows.size());
        for (String[] f : rows) {
            // ⚠️ N4（净空头越卖向敞口）不在本用例：t_position.direction 的 DDL 域为
            //   LONG/SHORT（m2_trade.sql:320 ck_position_direction），该域下 Java 复算
            //   （Impl 以 DIRECTION_SELL="SELL" 比对方向取负号）结构性无法表达负净持仓，
            //   而线上 0x0205 事件词汇为 BUY/SELL——系既有栈间词汇分歧（非本单引入），
            //   已报权威席裁定（T-186 报告披露）；N4 见下方挂起用例。
            if ("N4".equals(f[0])) {
                continue;
            }
            assertNetPositionVector(f);
        }
    }

    /**
     * {@code N4}（净空头越卖向敞口 ⇒ REJECT 0x1009）：C++ 侧已绿（见 golden 与
     * {@code risk_engine_parity_golden_test}）；Java 侧因上述<b>既有栈间方向词汇分歧</b>
     * （DDL 域 LONG/SHORT vs 事件链词汇 BUY/SELL vs Impl 以 "SELL" 取负）无法在不违反
     * DDL 的前提下表达负净持仓，挂起待权威席裁定后解除。
     */
    @Test
    @Disabled("N4 依赖方向词汇裁定（t_position.direction：DDL LONG/SHORT vs 事件链 BUY/SELL）；"
            + "裁定期挂起，见 T-186 报告披露")
    void parityNetPositionShortSideRejected() throws Exception {
        for (String[] f : golden("cpp/src/risk_engine/tests/golden/parity_netpos_golden.tsv")) {
            if ("N4".equals(f[0])) {
                assertNetPositionVector(f);
                return;
            }
        }
        throw new AssertionError("golden 缺 N4 向量");
    }

    private void assertNetPositionVector(String[] f) throws Exception {
        // 列：id direction preLong preShort inc buyExposure sellExposure verdict code threshold
        fresh();
        String id = f[0];
        // 换算关系两侧同设：合约乘数 = 换算系数 = 1（1 手 = 1.0 实物量）
        T066RiskRecomputeSeed.productContract(engine, nextId++, PRODUCT, "CU2606", "1",
                LocalDate.of(2027, 6, 30));
        T066RiskRecomputeSeed.productUnit(engine, nextId++, PRODUCT, "1",
                LocalDate.of(2026, 1, 1));
        // Java 复算镜面：播种事后净持仓（买量为正）
        //   C++ 投影：BUY ⇒ net_pre + inc；SELL ⇒ -(net_pre) + inc，即事后 net = net_pre - inc
        BigDecimal net = qty(f[2]).subtract(qty(f[3]));
        net = "SELL".equals(f[1]) ? net.subtract(qty(f[4])) : net.add(qty(f[4]));
        // 播种词汇取 DDL 域 LONG/SHORT（ck_position_direction 取值域）；正净持仓播 LONG。
        //   Impl :250 仅对 "SELL" 取负 ⇒ LONG/SHORT 一律按正向累加——正是 N4 挂起项所披露的
        //   「DDL 域下负净持仓无法被 Impl 表达」分歧；正净持仓向量判定不受影响（C++ 同正向）。
        if (net.signum() > 0) {
            T066RiskRecomputeSeed.position(engine, nextId++, SUB, "CU2606", PRODUCT,
                    "LONG", dec(net));
        }
        if (Long.parseLong(f[5]) >= 0) {
            T066RiskRecomputeSeed.exposure(engine, nextId++, SUB, PRODUCT, "BUY", dec(f[5]),
                    LocalDateTime.of(2026, 10, 7, 9, 30));
        }
        if (Long.parseLong(f[6]) >= 0) {
            T066RiskRecomputeSeed.exposure(engine, nextId++, SUB, PRODUCT, "SELL", dec(f[6]),
                    LocalDateTime.of(2026, 10, 7, 9, 30));
        }
        RiskRecomputeService.NetPositionResult r = service.recomputeNetPosition(SUB, PRODUCT);
        assertEquals(f[7], r.verdict, "向量 " + id + " verdict");
        if ("REJECT".equals(f[7])) {
            assertEquals(f[8], r.code, "向量 " + id + " 理由码");
            assertDecimalEquals(id + " 阈值", qty(f[9]), r.threshold);
        } else {
            assertNull(r.code, "向量 " + id + " PASS 不应有理由码");
        }
    }

    // ================================================================ R-RISK-6 期限红线

    @Test
    @DisplayName("跨栈对拍·期限红线：5 组向量（超期、合同+宽限取较早、剩余≤宽限 WARN、恰等、展期已批）一致")
    void parityTerm() throws Exception {
        List<String[]> rows = golden("cpp/src/risk_engine/tests/golden/parity_term_golden.tsv");
        assertTrue(rows.size() >= 3, "期限红线向量须 ≥3 组，实测 " + rows.size());
        for (String[] f : rows) {
            // 列：id today openDate termEnd contractExpiry extApproved verdict code threshold
            fresh();
            String id = f[0];
            LocalDate today = ymd(f[1]);
            LocalDate open = ymd(f[2]);
            LocalDate termEnd = ymd(f[3]);
            long expiry = Long.parseLong(f[4]);
            boolean extApproved = !"0".equals(f[5]);
            // 合同侧方向约定：有合同 ⇒ BUY_HEDGE（对冲销售合同）；无合同 ⇒ SELL_HEDGE
            String direction = expiry >= 0 ? "BUY_HEDGE" : "SELL_HEDGE";
            long planId = nextId++;
            T066RiskRecomputeSeed.hedgePlan(engine, planId, SUB, PRODUCT, "P" + id, direction,
                    open, termEnd, "APPROVED", extApproved ? "实货合同展期依据" : null);
            if (expiry >= 0) {
                if ("BUY_HEDGE".equals(direction)) {
                    T066RiskRecomputeSeed.salesContract(engine, nextId++, SUB, PRODUCT, "S" + id,
                            open, ymd(f[4]));
                } else {
                    T066RiskRecomputeSeed.purchaseContract(engine, nextId++, SUB, PRODUCT,
                            "B" + id, open, ymd(f[4]));
                }
            }
            RiskRecomputeService.TermResult r = service.recomputeTerm(planId, today);
            assertEquals(f[6], r.verdict, "向量 " + id + " verdict");
            if ("REJECT".equals(f[6])) {
                assertEquals(f[7], r.code, "向量 " + id + " 理由码");
                assertEquals(ymd(f[8]), r.allowedEnd, "向量 " + id + " 阈值（allowed 取较早者）");
            } else if ("WARN".equals(f[6])) {
                assertNull(r.code, "向量 " + id + " WARN 无理由码");
                assertEquals(f[8], String.valueOf(r.threshold), "向量 " + id + " 阈值（宽限天数）");
            } else {
                assertNull(r.code, "向量 " + id + " PASS 不应有理由码");
            }
        }
    }

    // ================================================================ 工具

    /** 定点数量（C++ kQtyScale = 1e4）⇒ BigDecimal 实物量。 */
    private static BigDecimal qty(String fixed) {
        return new BigDecimal(fixed).movePointLeft(4);
    }

    /** 定点比例（C++ kRatioScale = 1e6）⇒ BigDecimal 比例。 */
    private static BigDecimal ratio(String fixed) {
        return new BigDecimal(fixed).movePointLeft(6);
    }

    /** 定点整数 ⇒ 换算实物量后的去尾零十进制串（供播种 DECIMAL 列）。 */
    private static String dec(String fixed) {
        return dec(qty(fixed));
    }

    private static String dec(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static LocalDate ymd(String v) {
        long n = Long.parseLong(v);
        return LocalDate.of((int) (n / 10000), (int) (n / 100) % 100, (int) (n % 100));
    }

    private static void assertDecimalEquals(String what, BigDecimal expected, Object actual) {
        assertTrue(actual != null, what + "：期望 " + expected + " 但实际为 null");
        assertEquals(0, expected.compareTo(new BigDecimal(String.valueOf(actual))),
                what + "：期望=" + expected + " 实际=" + actual);
    }

    /** 读取 golden 向量文件（与 C++ 侧共享；# 注释行与空行跳过；列以 \t 分割）。 */
    private static List<String[]> golden(String relative) throws Exception {
        List<String> lines = Files.readAllLines(resolve(relative), StandardCharsets.UTF_8);
        List<String[]> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            rows.add(line.split("\t", -1));
        }
        return rows;
    }

    private static Path resolve(String relative) {
        Path path = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && path != null; i++) {
            if (Files.isRegularFile(path.resolve("docs/DATA_MODEL.md"))) {
                return path.resolve(relative);
            }
            path = path.getParent();
        }
        throw new IllegalStateException("未找到仓库根；复现=在 java/ 目录下运行 mvn test");
    }
}
