package com.hedgeguard.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-223} 防篡改告警端口生产实现用例（{@code FR-AUDIT-10} / {@code R-AUDIT-4}「校验未通过须告警」）。
 *
 * <p>覆盖：告警落 <b>一条 {@code SEVERE}</b> 日志、文案含固定前缀 {@code AUDIT_TAMPER} / 原 {@code message} /
 * 差异项数；{@code null} 差异清单不抛异常且计数为 {@code 0}；告警通道故障<b>不向上抛</b>
 * （不得反向影响校验与 {@code 10018} 阻断主流程）；缺省构造接类名 logger（生产装配形态）。
 */
class LoggingTamperAlarmTest {

    /** 每条用例的唯一标记，用于在多条日志中精确定位本次告警。 */
    private static final String MARK = "T223-ALERT-MARK";

    private Logger logger;
    private CapturingHandler handler;

    @BeforeEach
    void setUp() {
        // 独立 logger 名（逐用例唯一）⇒ 不污染根 logger，也不受其它用例日志干扰。
        logger = Logger.getLogger(LoggingTamperAlarmTest.class.getName() + "." + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        handler = new CapturingHandler();
        logger.addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(handler);
        logger.setLevel(null);
        logger.setUseParentHandlers(true);
    }

    @Test
    @DisplayName("告警落一条 SEVERE：含固定前缀 AUDIT_TAMPER、原 message 与差异项数")
    void alertLogsSingleSevereRecord() {
        LoggingTamperAlarm alarm = new LoggingTamperAlarm(logger);

        alarm.alert(MARK + " 审计日志防篡改校验未通过：范围 [1,3]",
                List.of("行1 行哈希不符", "批次校验和不符：batchNo=1"));

        assertEquals(1, handler.records.size(), "一次告警只记一条日志");
        LogRecord rec = handler.records.get(0);
        assertEquals(Level.SEVERE, rec.getLevel(), "防篡改告警须为 SEVERE");
        String msg = rec.getMessage();
        assertNotNull(msg, "日志文案不得为空");
        assertTrue(msg.startsWith(LoggingTamperAlarm.PREFIX + " "),
                "文案须以固定前缀开头（运维检索锚点）：" + msg);
        assertTrue(msg.contains(MARK), "须原样带入入参 message：" + msg);
        assertTrue(msg.contains("差异项数=2"), "须含差异项数（2 项）：" + msg);
        assertTrue(msg.contains("行1 行哈希不符"), "须展开差异明细：" + msg);
    }

    @Test
    @DisplayName("mismatches 为 null：不抛异常且差异项数为 0")
    void nullMismatchesDoesNotThrow() {
        LoggingTamperAlarm alarm = new LoggingTamperAlarm(logger);

        assertDoesNotThrow(() -> alarm.alert(MARK + "-null 防篡改告警", null));

        assertEquals(1, handler.records.size());
        assertTrue(handler.records.get(0).getMessage().contains("差异项数=0"),
                "null 差异清单按 0 项记：" + handler.records.get(0).getMessage());
    }

    @Test
    @DisplayName("告警通道故障不外抛：不得影响校验/阻断主流程")
    void alertSwallowsSinkFailure() {
        Logger exploding = new ExplodingLogger();

        assertDoesNotThrow(() -> new LoggingTamperAlarm(exploding).alert(MARK + "-boom 防篡改告警",
                List.of("行1 行哈希不符")));
    }

    @Test
    @DisplayName("缺省构造接类名 logger（生产装配形态）")
    void defaultConstructorLogsToClassLogger() {
        Logger classLog = Logger.getLogger(LoggingTamperAlarm.class.getName());
        Level oldLevel = classLog.getLevel();
        boolean oldParent = classLog.getUseParentHandlers();
        CapturingHandler capture = new CapturingHandler();
        classLog.setUseParentHandlers(false);
        classLog.setLevel(Level.ALL);
        classLog.addHandler(capture);
        try {
            new LoggingTamperAlarm().alert(MARK + "-default 防篡改告警", List.of("a"));

            long hits = capture.records.stream()
                    .filter(r -> String.valueOf(r.getMessage()).contains(MARK + "-default"))
                    .count();
            assertEquals(1, hits, "缺省构造须把告警记到类名 logger（生产 sink）");
        } finally {
            classLog.removeHandler(capture);
            classLog.setLevel(oldLevel);
            classLog.setUseParentHandlers(oldParent);
        }
    }

    // ------------------------------------------------------------ 夹具

    /** 捕获 {@link LogRecord} 的 Handler 夹具。 */
    private static final class CapturingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // 内存夹具，无需刷新
        }

        @Override
        public void close() {
            // 内存夹具，无需释放
        }
    }

    /** 模拟告警通道故障（sink 不可用）的 logger 夹具。 */
    private static final class ExplodingLogger extends Logger {

        ExplodingLogger() {
            super("com.hedgeguard.test.ExplodingLogger", null);
            setUseParentHandlers(false);
        }

        @Override
        public void log(Level level, String msg) {
            throw new IllegalStateException("告警通道不可用（夹具）");
        }
    }
}
