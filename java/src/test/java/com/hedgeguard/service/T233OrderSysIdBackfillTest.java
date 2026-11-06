package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.TradeOrderDao;
import com.hedgeguard.dao.impl.DmTradeCommandFlowDao;
import com.hedgeguard.dao.impl.DmTradeFillDao;
import com.hedgeguard.dao.impl.DmTradeOrderDao;
import com.hedgeguard.dao.impl.DmTradeOrderReportDao;
import com.hedgeguard.dao.impl.DmTradePositionDao;
import com.hedgeguard.dao.impl.DmTradePreTradeRejectDao;
import com.hedgeguard.dao.impl.DmTradeReasonDao;
import com.hedgeguard.dao.impl.DmTradePlanDao;
import com.hedgeguard.dao.impl.DmExposureDao;
import com.hedgeguard.dao.entity.TradeOrder;
import com.hedgeguard.dao.pool.DataSourcePool;
import com.hedgeguard.dao.pool.PoolConfig;
import com.hedgeguard.dao.testsupport.InMemoryDmEngine;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-233}（{@code client_order_ref} 16 字符 vs CTP {@code OrderRef} 12 字节根因收口·方案 B）
 * <b>Java 回填面</b>验收：{@code t_trade_order.order_sys_id} 由 {@code EVT_ORDER_REPORT}
 * （{@code OnRtnOrder} / {@code OnRspOrderInsert} 回报面）按 {@code client_order_ref} 关联回填。
 *
 * <p>形态：<b>真实 DDL + 真实 DAO + 内存假 JDBC 引擎</b>（{@link InMemoryDmEngine}；禁假 DAO 覆盖接线面），
 * 行断言直读 {@code engine.table("t_trade_order").rows}。
 *
 * <p>覆盖（工单验收 2 + 数据字典 §2.6 TB-TRADE-1 备注）：
 * <ul>
 *   <li><b>正常回填</b>：首次回报携带 {@code orderSysId} ⇒ 写入该列（其余列不变）；</li>
 *   <li><b>幂等</b>：后续回报携带同一 {@code orderSysId} ⇒ 不重复更新（{@code row_version} 不变）；</li>
 *   <li><b>异值不覆盖</b>：交易所报单号不可变 ⇒ 保留既有值 + 结构化告警（LOG + {@code residualEvents}）；</li>
 *   <li><b>无源不臆造</b>：空白 {@code orderSysId} ⇒ 该列保持 {@code NULL}；</li>
 *   <li><b>回填失败不静默</b>：更新影响行数 ≠ 1 ⇒ 抛 {@link BizException}（{@code REFERENCE_CONFLICT}）；</li>
 *   <li><b>父指令缺失</b>：{@code orderId} 在 {@code t_trade_order} 无对应行 ⇒ 抛错（不静默跳过）。</li>
 * </ul>
 */
class T233OrderSysIdBackfillTest {

    private static final LocalDate BASE_DAY = LocalDate.of(2026, 11, 5);
    private static final String DDL = "docs/DEPLOY/schema/m2_trade.sql";
    private static final int SUB_A = 101;
    private static final long BUSINESS_ORDER_ID = 9001L;
    /** 生产形态 ref：`BizNoGenerator.orderRef()` = "HG" + yyyyMMdd + 6 位 = 16 字符。 */
    private static final String REF_16 = "HG20261105000001";

    private InMemoryDmEngine engine;
    private TxTemplate tx;
    private TradeService service;
    private DmTradeOrderDao realOrderDao;

    private final List<LogRecord> logs = new ArrayList<>();
    private Handler logHandler;

    @BeforeEach
    void setUp() {
        Clocks.set(Clock.fixed(BASE_DAY.atStartOfDay(Clocks.ZONE).toInstant(), Clocks.ZONE));
        engine = new InMemoryDmEngine();
        loadSchema();
        tx = new TxTemplate(new DataSourcePool(engine, new PoolConfig(0, 4, 500, 2)));
        realOrderDao = new DmTradeOrderDao(tx);
        service = buildService(realOrderDao);

        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
                // 无缓冲
            }

            @Override
            public void close() {
                // 无资源
            }
        };
        Logger.getLogger(TradeService.class.getName()).addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger(TradeService.class.getName()).removeHandler(logHandler);
        Clocks.reset();
    }

    // ================================================================ 验收 2：正常回填

    @Test
    @DisplayName("首次回报携带 OrderSysID ⇒ 按 client_order_ref 关联回填 t_trade_order.order_sys_id")
    void firstReportBackfillsOrderSysId() {
        seedOrder();
        assertNull(orderRow().get("order_sys_id"), "前置：未回报前应为 NULL");

        service.onOrderReport(report("S-9001", "SUBMITTED", 0, 0, 1));

        assertEquals("S-9001", orderRow().get("order_sys_id"), "首次回报应回填 CTP 报单编号");
        assertEquals(REF_16, orderRow().get("client_order_ref"), "幂等键列不得被回填改写");
        assertEquals(1, reportRows().size(), "回报行应落库 1 行");
        assertEquals(2L, ((Number) orderRow().get("row_version")).longValue(),
                "回填本身是一次更新：row_version 1 → 2");
    }

    @Test
    @DisplayName("回报状态映射同时生效：order_sys_id 回填 + status 更新一次落库（row_version 1 → 2）")
    void backfillAndStatusUpdateShareOneWrite() {
        seedOrder();

        TradeService.OrderReportEvent e = report("S-9001", "ALL_FILLED", 1, 0, 1);
        e.mappedOrderStatus = "ALL_FILLED";
        service.onOrderReport(e);

        assertEquals("S-9001", orderRow().get("order_sys_id"));
        assertEquals("ALL_FILLED", orderRow().get("status"));
        assertEquals(2L, ((Number) orderRow().get("row_version")).longValue(), "一次更新推进一个版本");
    }

    @Test
    @DisplayName("同一 OrderSysID 的后续回报幂等：不重复更新主表（row_version 不变）")
    void repeatedSameSysIdIsIdempotent() {
        seedOrder();
        service.onOrderReport(report("S-9001", "SUBMITTED", 0, 0, 1));
        assertEquals(2L, ((Number) orderRow().get("row_version")).longValue());

        // 第二次回报：不同 (status, volume_traded) ⇒ 回报表新行；但 sys_id 同值 ⇒ 主表不得再写
        service.onOrderReport(report("S-9001", "PARTIAL_FILLED", 1, 0, 2));

        assertEquals(2, reportRows().size(), "异状态回报应落 2 行（幂等键三元组不同）");
        assertEquals("S-9001", orderRow().get("order_sys_id"));
        assertEquals(2L, ((Number) orderRow().get("row_version")).longValue(),
                "同值 sys_id 不得触发主表更新（幂等）");
        assertTrue(conflictLogs().isEmpty(), "无冲突时不应有告警");
    }

    @Test
    @DisplayName("异值 OrderSysID ⇒ 不覆盖既有值 + 结构化告警（LOG + residualEvents），回报仍落库")
    void conflictingSysIdIsNotOverwrittenAndIsLogged() {
        seedOrder();
        service.onOrderReport(report("S-9001", "SUBMITTED", 0, 0, 1));

        service.onOrderReport(report("S-6666", "PARTIAL_FILLED", 1, 0, 2));

        assertEquals("S-9001", orderRow().get("order_sys_id"),
                "交易所报单号不可变：异值不得覆盖（须人工核验）");
        assertEquals(2, reportRows().size(), "冲突不影响回报行自身落库");
        assertTrue(!conflictLogs().isEmpty(), "冲突必须留痕（LOG），不得静默");
        assertTrue(residual().stream().anyMatch(m -> m.contains("T-233") && m.contains("S-6666")),
                "冲突须进 residualEvents 诊断面：" + residual());
    }

    @Test
    @DisplayName("空白 OrderSysID ⇒ 视为无源，不写入（不臆造）")
    void blankSysIdIsIgnored() {
        seedOrder();

        service.onOrderReport(report("   ", "SUBMITTED", 0, 0, 1));

        assertNull(orderRow().get("order_sys_id"), "空白 OrderSysID 不得写入");
        assertEquals(1, reportRows().size());
    }

    @Test
    @DisplayName("回填失败不静默：更新影响行数 ≠ 1 ⇒ BizException（REFERENCE_CONFLICT）")
    void backfillFailureIsNotSilent() {
        seedOrder();
        // 装饰器：读路径透传真实 DAO，仅让 updateById 返回 0（模拟行不存在/并发版本冲突）
        TradeOrderDao failing = failingUpdateDao(realOrderDao);
        TradeService guarded = buildService(failing);

        BizException e = assertThrows(BizException.class,
                () -> guarded.onOrderReport(report("S-9001", "SUBMITTED", 0, 0, 1)));

        assertEquals(ErrorCode.REFERENCE_CONFLICT, e.errorCode());
        assertTrue(String.valueOf(e.getMessage()).contains("order_sys_id")
                        || String.valueOf(e.getMessage()).contains("回填"),
                "错误信息须指出回填失败：" + e.getMessage());
        // ⚠️ InMemoryDmEngine 的 rollback 是 no-op（工程指南陷阱 6）⇒ 此处只断言「主表未被写」，
        //    事务回滚语义不在本用例断言范围（真实 DM 由部署方复跑）。
        assertNull(orderRow().get("order_sys_id"), "更新失败时主表不得留下回填值");
    }

    @Test
    @DisplayName("父指令无法解析 ⇒ BizException（不得静默跳过回填）")
    void unknownParentOrderIsRejected() {
        // 不 seed ⇒ t_trade_order 无对应行
        TradeService.OrderReportEvent e = report("S-9001", "SUBMITTED", 0, 0, 1);
        e.orderId = 424242L;

        BizException ex = assertThrows(BizException.class, () -> service.onOrderReport(e));
        assertEquals(ErrorCode.REFERENCE_CONFLICT, ex.errorCode());
    }

    // ================================================================ 夹具

    private TradeService buildService(TradeOrderDao orderDao) {
        return new TradeService(tx, null, null,
                new DmTradePlanDao(tx), new DmExposureDao(tx), orderDao,
                new DmTradeReasonDao(tx), new DmTradeOrderReportDao(tx), new DmTradeFillDao(tx),
                new DmTradePositionDao(tx), new DmTradePreTradeRejectDao(tx),
                new DmTradeCommandFlowDao(tx));
    }

    /** 装饰器：仅 {@code updateById} 返回 0，其余方法透传给真实 DAO（无 Mockito，NFR-TEST-2）。 */
    private static TradeOrderDao failingUpdateDao(TradeOrderDao real) {
        return (TradeOrderDao) Proxy.newProxyInstance(
                TradeOrderDao.class.getClassLoader(),
                new Class<?>[]{TradeOrderDao.class},
                (proxy, method, args) -> {
                    if ("updateById".equals(method.getName())) {
                        return 0;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException ite) {
                        throw ite.getCause();
                    }
                });
    }

    private void loadSchema() {
        try {
            engine.executeScript(Files.readString(repoRoot().resolve(DDL), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("建表脚本执行失败：" + DDL, e);
        }
    }

    /** 播种一条 {@code t_trade_order} 行（真实 DAO，保证与 DDL 列集对齐）。 */
    private void seedOrder() {
        TradeOrder o = new TradeOrder();
        o.subAccountId = SUB_A;
        o.clientOrderRef = REF_16;
        o.orderId = BUSINESS_ORDER_ID;
        o.orderSysId = null;
        o.tradePlanId = 5001L;
        o.hedgePlanId = 6001L;
        o.exposureId = 7001L;
        o.productId = 8001L;
        o.instrumentId = "CU2612";
        o.instrumentType = "FUTURES";
        o.direction = "BUY";
        o.offsetFlag = "OPEN";
        o.priceType = "LIMIT";
        o.limitPrice = new BigDecimal("70000.000000");
        o.volume = 2;
        o.operatorId = 1L;
        o.status = "SENT_CTP";
        o.submitTime = Clocks.now();
        o.clientTsMs = Clocks.epochMilli();
        assertEquals(1, realOrderDao.insert(o, 0L), "前置：指令行应落库");
    }

    private TradeService.OrderReportEvent report(String sysId, String status, int traded, int left,
                                                 long eventSeq) {
        TradeService.OrderReportEvent e = new TradeService.OrderReportEvent();
        e.subAccountId = SUB_A;
        e.orderId = BUSINESS_ORDER_ID;
        e.orderSysId = sysId;
        e.orderLocalId = "L-1";
        e.exchangeId = "SHFE";
        e.instrumentId = "CU2612";
        e.direction = "BUY";
        e.offsetFlag = "OPEN";
        e.orderStatus = status;
        e.volumeTotal = 2;
        e.volumeTraded = traded;
        e.volumeLeft = left;
        e.reportTsMs = 1767225600000L + eventSeq;
        e.eventSeq = eventSeq;
        e.mappedOrderStatus = null;  // 默认不更新状态，聚焦回填面
        return e;
    }

    private Map<String, Object> orderRow() {
        InMemoryDmEngine.Table t = engine.table("t_trade_order");
        assertNotNull(t, "t_trade_order 应已建表");
        assertEquals(1, t.rows.size(), "应恰有 1 行指令：" + t.rows);
        return t.rows.get(0);
    }

    private List<Map<String, Object>> reportRows() {
        InMemoryDmEngine.Table t = engine.table("t_trade_order_report");
        assertNotNull(t, "t_trade_order_report 应已建表");
        return t.rows;
    }

    private List<String> conflictLogs() {
        List<String> out = new ArrayList<>();
        for (LogRecord r : logs) {
            String msg = String.valueOf(r.getMessage());
            if (msg.contains("T-233")) {
                out.add(msg);
            }
        }
        return out;
    }

    private List<String> residual() {
        return service.residualEvents();
    }

    private static Path repoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("docs/DATA_MODEL.md"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未找到仓库根（应含 docs/DATA_MODEL.md），当前目录="
                + Paths.get("").toAbsolutePath() + "；复现=在 java/ 目录下运行 mvn test");
    }
}
