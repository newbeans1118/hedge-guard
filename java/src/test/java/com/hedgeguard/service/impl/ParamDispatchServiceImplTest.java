package com.hedgeguard.service.impl;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.MirrorSnapshot;
import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.bridge.PlaceOrderCmd;
import com.hedgeguard.bridge.PlaceOrderResult;
import com.hedgeguard.bridge.ReplayProgress;
import com.hedgeguard.bridge.TradingBridge;
import com.hedgeguard.dao.ParamDispatchDao;
import com.hedgeguard.dao.entity.ParamDispatch;
import com.hedgeguard.service.FakeTx;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hedgeguard.common.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ParamDispatchServiceImpl} 单元测试（{@code T-011}）。
 *
 * <p>按**冻结契约**逐条取证（{@code CONTRACTS.md §3.2.1（HGC-1.2）/ §3.2.2}）：
 * ①键名为协议字段名（{@code sub_account_id} / {@code scan_scope} / {@code suspended}）；
 * ②无回执/失败**不得视为已生效**（{@code 10014}，{@code ruleId=R-CONFIG-3}）；
 * ③先落库再下发（{@code PENDING}）；④{@code param_version} **服务层串行化**分配；
 * ⑤两阶段配对撤销 = **未撤销挂起计数归零才下发** {@code suspended=false}（协议无 per-suspend 令牌）。
 */
class ParamDispatchServiceImplTest {

    private FakeParamDispatchDao dao;
    private StubBridge bridge;

    @BeforeEach
    void setUp() {
        dao = new FakeParamDispatchDao();
        bridge = new StubBridge();
    }

    private ParamDispatchServiceImpl service() {
        return new ParamDispatchServiceImpl(FakeTx.newTemplate(), dao, bridge, Duration.ofMillis(80));
    }

    @Test
    @DisplayName("下发成功：先落库 PENDING → 回执 SUCCESS；payload 摘要留痕；版本严格递增")
    void dispatchSuccess() {
        bridge.ack = ack(true, "OK");
        ParamDispatchServiceImpl service = service();

        ParamChange first = new ParamChange(ParamChange.Type.CMD_UPDATE_WHITELIST, 0L, 7L)
                .with(ParamDispatchServiceImpl.KEY_SUB_ACCOUNT_ID, "3");
        AckResult ack = service.dispatch(first).join();

        assertTrue(ack.acknowledged);
        assertEquals(1L, first.paramVersion, "首个版本由本类分配");
        assertEquals("SUCCESS", dao.rows.get(0).dispatchStatus);
        assertEquals(3, dao.rows.get(0).subAccountId, "payload 的子账户须落到留痕行");
        assertEquals(64, dao.rows.get(0).payloadDigest.length(), "SHA-256 十六进制 64 位");
        assertNotNull(dao.rows.get(0).ackTime, "回执时间须留痕");
        service.requireEffective(first, ack);

        ParamChange second = new ParamChange(ParamChange.Type.CMD_UPDATE_QUOTA, 0L, 7L)
                .with(ParamDispatchServiceImpl.KEY_SUB_ACCOUNT_ID, "3");
        service.dispatch(second).join();
        assertEquals(2L, second.paramVersion, "第二版须严格递增");
    }

    @Test
    @DisplayName("回执超时：记 UNCONFIRMED 且 requireEffective 抛 10014（ruleId=R-CONFIG-3，四项齐备）")
    void timeoutIsNotEffective() {
        bridge.neverCompletes = true;
        ParamDispatchServiceImpl service = service();
        ParamChange change = new ParamChange(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, 0L, 7L);

        AckResult ack = service.dispatch(change).join();

        assertFalse(ack.acknowledged, "超时不得视为已确认");
        assertEquals("UNCONFIRMED", dao.rows.get(0).dispatchStatus);
        assertTrue(dao.rows.get(0).ackDetail.contains("超时"), dao.rows.get(0).ackDetail);

        BizException e = assertThrows(BizException.class, () -> service.requireEffective(change, ack));
        assertEquals(ErrorCode.DISPATCH_NOT_EFFECTIVE, e.errorCode());
        assertEquals("R-CONFIG-3", e.ruleId(), "冻结契约指定的规则编号");
        assertNotNull(e.current());
        assertNotNull(e.threshold());
        assertNotNull(e.nextStep());
        assertTrue(e.current().contains("UNCONFIRMED"), e.current());
        assertTrue(e.threshold().contains(String.valueOf(change.paramVersion)), e.threshold());
    }

    @Test
    @DisplayName("回执失败：记 FAIL 且不得视为已生效")
    void failedAckIsNotEffective() {
        bridge.ack = ack(false, "校验未通过");
        ParamDispatchServiceImpl service = service();
        ParamChange change = new ParamChange(ParamChange.Type.CMD_UPDATE_EXPOSURE, 0L, 7L);

        AckResult ack = service.dispatch(change).join();

        assertFalse(ack.acknowledged);
        assertEquals("FAIL", dao.rows.get(0).dispatchStatus);
        assertEquals(ErrorCode.DISPATCH_NOT_EFFECTIVE,
                assertThrows(BizException.class, () -> service.requireEffective(change, ack)).errorCode());
    }

    @Test
    @DisplayName("组件不可用：拒绝下发（10002）且不产生 PENDING 留痕；下发抛异常则留痕 FAIL")
    void unavailableAndThrowingBridge() {
        bridge.ready = false;
        ParamDispatchServiceImpl service = service();
        assertEquals(ErrorCode.COMPONENT_UNAVAILABLE,
                assertThrows(BizException.class,
                        () -> service.dispatch(new ParamChange(ParamChange.Type.CMD_RELOAD, 0L, 7L)))
                        .errorCode());
        assertTrue(dao.rows.isEmpty(), "未下发不得留痕（避免巡检误重发）");

        bridge.ready = true;
        bridge.throwOnDispatch = true;
        assertEquals(ErrorCode.COMPONENT_UNAVAILABLE,
                assertThrows(BizException.class,
                        () -> service.dispatch(new ParamChange(ParamChange.Type.CMD_RELOAD, 0L, 7L)))
                        .errorCode());
        assertEquals("FAIL", dao.rows.get(0).dispatchStatus);
    }

    @Test
    @DisplayName("param_version 串行化分配：并发下发不产生同号（契约 §3.2.2，该表无 UNIQUE 兜底）")
    void versionAllocationIsSerialized() throws Exception {
        bridge.ack = ack(true, "OK");
        ParamDispatchServiceImpl service = service();
        int threads = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Long> versions = Collections.synchronizedSet(new HashSet<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    ParamChange change = new ParamChange(ParamChange.Type.CMD_RELOAD, 0L, 7L);
                    service.dispatch(change).join();
                    versions.add(change.paramVersion);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发下发应在超时前完成");

        assertTrue(errors.isEmpty(), "并发下发不得报错：" + errors);
        assertEquals(threads, versions.size(), "版本号必须两两不同（否则 C++ 侧 0x0403 会静默丢变更）");
        assertEquals(Long.valueOf(threads), Collections.max(versions),
                "版本应连续取到 1.." + threads + "，实际=" + versions);
    }

    @Test
    @DisplayName("配对撤销（契约 §3.2.1）：多个挂起来源下，计数归零才下发 suspended=false")
    void suspendPairingUsesOutstandingCount() {
        bridge.ack = ack(true, "OK");
        ParamDispatchServiceImpl service = service();

        service.suspendOpenOnly(3, "来源 A：限额下调", 7L);
        service.suspendOpenOnly(3, "来源 B：风控告警", 7L);
        assertEquals(2, service.outstandingSuspends(3), "两个未撤销挂起");
        assertEquals(2, dao.rows.size(), "两次挂起各一次下发");

        AckResult first = service.releaseOpenOnly(3, "来源 A 结束", 7L);
        assertFalse(first.acknowledged, "仍有挂起 → 本层不得下发解冻");
        assertTrue(first.message.contains("仍有 1 个"), first.message);
        assertEquals(2, dao.rows.size(), "未下发 → 不留痕");
        assertEquals(1, service.outstandingSuspends(3));

        AckResult second = service.releaseOpenOnly(3, "来源 B 结束", 7L);
        assertTrue(second.acknowledged, "计数归零 → 下发解冻并生效");
        assertEquals(3, dao.rows.size(), "此时才留痕第三条（suspended=false）");
        assertEquals(0, service.outstandingSuspends(3));

        assertEquals(ParamDispatchServiceImpl.SCAN_SCOPE_OPEN_ONLY,
                bridge.sent.get(0).payloadView().get(ParamDispatchServiceImpl.KEY_SCAN_SCOPE),
                "冻结须限定为仅新开仓（平仓放行，R-RISK-5）");
        assertEquals("true", bridge.sent.get(0).payloadView()
                .get(ParamDispatchServiceImpl.KEY_SUSPENDED));
        assertEquals("3", bridge.sent.get(0).payloadView()
                .get(ParamDispatchServiceImpl.KEY_SUB_ACCOUNT_ID));
        assertEquals("false", bridge.sent.get(2).payloadView()
                .get(ParamDispatchServiceImpl.KEY_SUSPENDED), "第三阶段为撤下挂起");
        // HGC-1.6：suspend_id 是 **Java 内部**关联标识，**禁止**进入 payload（协议 0x0118 无该字段）
        for (ParamChange sent : bridge.sent) {
            assertNull(sent.payloadView().get(ParamDispatchServiceImpl.KEY_SUSPEND_ID),
                    "payload 不得携带协议外字段 suspend_id：type=" + sent.type);
            assertNotNull(sent.payloadView().get(ParamDispatchServiceImpl.KEY_REASON),
                    "追溯信息须经 reason（协议内字段）承载");
        }
        // 协议内自由文本可**附带**内部标识便于运维，但**不作为**获取渠道（HGC-1.6）
        assertNotNull(bridge.sent.get(2).payloadView().get(ParamDispatchServiceImpl.KEY_REASON),
                "解冻须携带原因（协议内字段）");

        // 缺标识一律拒绝（不得退化为"解冻该子账户全部挂起"）：null 与空串均须 10009
        String heldId = "S-4-1";
        service.suspendOpenOnly(4, heldId, "来源 X", 7L);
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.releaseOpenOnly(4, null, "缺标识", 7L)).errorCode());
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.releaseOpenOnly(4, "", "缺标识", 7L)).errorCode());
        assertEquals(1, service.outstandingSuspends(4), "被拒后挂起不得被撤销");
        assertEquals(ParamDispatchServiceImpl.SCAN_SCOPE_OPEN_ONLY,
                bridge.sent.get(2).payloadView().get(ParamDispatchServiceImpl.KEY_SCAN_SCOPE),
                "解冻作用域须与冻结成对");
        assertTrue(bridge.sent.stream().noneMatch(change ->
                        ParamDispatchServiceImpl.ALL_ACCOUNT_SENTINEL.equals(
                                change.payloadView().get(ParamDispatchServiceImpl.KEY_SUB_ACCOUNT_ID))),
                "禁止用 sub_account_id=0 解冻（那是全账户解冻）");
    }

    @Test
    @DisplayName("两阶段：冻结 → 换额度（层③用哨兵 0）→ 撤下挂起；各阶段键名与取值逐项核对")
    void twoPhaseQuotaDecrease() {
        bridge.ack = ack(true, "OK");
        ParamDispatchServiceImpl service = service();

        List<AckResult> acks = service.decreaseQuotaTwoPhase(3, 5L, "SUB_PRODUCT", "50",
                "2026-10-01", 7L);

        assertEquals(3, acks.size(), "三阶段各一次下发");
        assertEquals("CMD_SET_TRADING_SUSPEND", dao.rows.get(0).dispatchType);
        assertEquals("CMD_UPDATE_QUOTA", dao.rows.get(1).dispatchType);
        assertEquals("CMD_SET_TRADING_SUSPEND", dao.rows.get(2).dispatchType);
        assertEquals("SUB_PRODUCT", bridge.sent.get(1).payloadView()
                .get(ParamDispatchServiceImpl.KEY_LIMIT_LAYER));
        assertEquals("3", bridge.sent.get(1).payloadView()
                .get(ParamDispatchServiceImpl.KEY_SUB_ACCOUNT_ID), "层① 用真实子账户");
        assertEquals("50", bridge.sent.get(1).payloadView()
                .get(ParamDispatchServiceImpl.KEY_QUOTA_LIMIT));
        assertEquals("2026-10-01", bridge.sent.get(1).payloadView()
                .get(ParamDispatchServiceImpl.KEY_EFFECTIVE_FROM));
        assertEquals(0, service.outstandingSuspends(3), "两阶段结束后挂起须归零");
        assertTrue(dao.rows.stream().allMatch(row -> "SUCCESS".equals(row.dispatchStatus)));

        service.decreaseQuotaTwoPhase(3, 5L, "ACCOUNT_PRODUCT", "500", "2026-10-01", 7L);
        assertEquals(ParamDispatchServiceImpl.ALL_ACCOUNT_SENTINEL, bridge.sent.get(4).payloadView()
                .get(ParamDispatchServiceImpl.KEY_SUB_ACCOUNT_ID), "层③ 用哨兵 0");
    }

    @Test
    @DisplayName("两阶段中断：额度替换未生效 → 撤下挂起 + 抛 10014（不留半生效状态）")
    void twoPhaseAbortsAndReleases() {
        bridge.ackQueue.add(ack(true, "冻结成功"));
        bridge.ackQueue.add(ack(false, "额度变更被拒"));
        bridge.ackQueue.add(ack(true, "已撤下挂起"));
        ParamDispatchServiceImpl service = service();

        BizException e = assertThrows(BizException.class,
                () -> service.decreaseQuotaTwoPhase(3, 5L, "SUB_PRODUCT", "50", "2026-10-01", 7L));

        assertEquals(ErrorCode.DISPATCH_NOT_EFFECTIVE, e.errorCode());
        assertEquals(3, dao.rows.size(), "冻结 / 额度 / 撤下挂起 各留痕一条");
        assertEquals("FAIL", dao.rows.get(1).dispatchStatus);
        assertEquals("false", bridge.sent.get(2).payloadView()
                .get(ParamDispatchServiceImpl.KEY_SUSPENDED), "额度失败后必须撤下挂起（不得长期误限）");
        assertEquals(0, service.outstandingSuspends(3), "中断后计数须归零（可重试）");
    }

    @Test
    @DisplayName("参数校验：缺 type / 子账户非整数 / 非法层级 / 哨兵子账户 各 10001")
    void invalidInput() {
        ParamDispatchServiceImpl service = service();
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.dispatch(null)).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.dispatch(new ParamChange(null, 0L, 7L)))
                        .errorCode());
        bridge.ack = ack(true, "OK");
        ParamChange bad = new ParamChange(ParamChange.Type.CMD_UPDATE_QUOTA, 0L, 7L)
                .with(ParamDispatchServiceImpl.KEY_SUB_ACCOUNT_ID, "abc");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class, () -> service.dispatch(bad)).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.decreaseQuotaTwoPhase(0, 5L, "SUB_PRODUCT", "50", "2026-10-01", 7L))
                        .errorCode(), "须指定真实子账户");
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(BizException.class,
                        () -> service.decreaseQuotaTwoPhase(3, 5L, "WRONG", "50", "2026-10-01", 7L))
                        .errorCode(), "层级越域");
    }

    @Test
    @DisplayName("巡检：未生效下发（PENDING/FAIL/UNCONFIRMED）可直接取用")
    void inspectProblemsDelegates() {
        ParamDispatchServiceImpl service = service();
        bridge.neverCompletes = true;
        service.dispatch(new ParamChange(ParamChange.Type.CMD_RELOAD, 0L, 7L)).join();
        assertEquals(1, dao.findProblems().size(), "UNCONFIRMED 行应进入巡检清单");
        assertEquals(1, service.inspectProblems().size());
    }

    @Test
    @DisplayName("回执超时取自配置 CFG-28 hg.cmd_timeout_ms（不得硬编码）；缺省 3000 ms")
    void ackTimeoutComesFromConfig() throws Exception {
        Path file = Files.createTempFile("hg-timeout", ".properties");
        try {
            Files.write(file, java.util.Arrays.asList("hg.cmd_timeout_ms=1234"));
            Config config = Config.load(file);
            ParamDispatchServiceImpl fromConfig =
                    new ParamDispatchServiceImpl(FakeTx.newTemplate(), dao, bridge, config);
            assertEquals(1234L, fromConfig.ackTimeoutMillis(), "超时须取自配置键（CFG-28）");
        } finally {
            Files.deleteIfExists(file);
        }
        ParamDispatchServiceImpl defaulted =
                new ParamDispatchServiceImpl(FakeTx.newTemplate(), dao, bridge);
        assertEquals(ParamDispatchServiceImpl.ACK_TIMEOUT_MILLIS, defaulted.ackTimeoutMillis(),
                "无配置时按 CFG-28 缺省 3000 ms");
    }

    @Test
    @DisplayName("超时/无回执：ackTime 必须为 null（未收到回执不得写回执时间，§3.2.1）")
    void timeoutLeavesAckTimeNull() {
        bridge.neverCompletes = true;
        ParamDispatchServiceImpl service = service();
        ParamChange change = new ParamChange(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, 0L, 7L);
        service.dispatch(change).join();

        assertEquals("UNCONFIRMED", dao.rows.get(0).dispatchStatus);
        assertNull(dao.rows.get(0).ackTime, "超时不得写回执时间（未确认 ≠ 已确认）");
        assertTrue(dao.rows.get(0).ackDetail.contains("超时"), dao.rows.get(0).ackDetail);
    }

    @Test
    @DisplayName("配对撤销：解冻必须回传同一 suspend_id（缺 id → 10009）；他人挂起不被误撤")
    void pairingRequiresSameSuspendId() {
        bridge.ack = ack(true, "OK");
        ParamDispatchServiceImpl service = service();

        // HGC-1.6：内部标识由**调用方持有**（不经报文/文本回读）
        String firstId = "S-3-1";
        service.suspendOpenOnly(3, firstId, "第一个来源", 7L);
        // HGC-1.6：suspend_id 是 Java 内部标识，**不得**进入 payload（协议 0x0118 无该字段）
        assertNull(bridge.sent.get(0).payloadView().get(ParamDispatchServiceImpl.KEY_SUSPEND_ID),
                "payload 不得携带协议外字段 suspend_id（协议 0x0118 无该字段）");
        assertEquals(ParamDispatchServiceImpl.SCAN_SCOPE_OPEN_ONLY,
                bridge.sent.get(0).payloadView().get(ParamDispatchServiceImpl.KEY_SCAN_SCOPE),
                "作用域键名以协议表为准（scan_scope）");

        String secondId = "S-3-2";
        service.suspendOpenOnly(3, secondId, "第二个来源", 8L);
        assertEquals(2, service.outstandingSuspends(3));

        int sentBefore = bridge.sent.size();
        AckResult firstRelease = service.releaseOpenOnly(3, firstId, "撤下第一个来源", 7L);
        assertFalse(firstRelease.acknowledged, "仍有其他挂起时不得下发解冻");
        assertTrue(firstRelease.message.contains("仍有"), firstRelease.message);
        assertEquals(sentBefore, bridge.sent.size(), "计数未归零 ⇒ 不得下发 suspended=false");

        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.releaseOpenOnly(3, null, "无标识", 7L)).errorCode(),
                "缺 suspend_id 即缺陷，须拒绝");

        AckResult last = service.releaseOpenOnly(3, secondId, "撤下第二个来源", 8L);
        assertTrue(last.acknowledged);
        ParamChange released = bridge.sent.get(bridge.sent.size() - 1);
        assertEquals("false", released.payloadView().get(ParamDispatchServiceImpl.KEY_SUSPENDED));
        // HGC-1.6：解冻**不得**把 suspend_id 写进 payload；配对由内部标识（reason 内可追溯）承载
        assertNull(released.payloadView().get(ParamDispatchServiceImpl.KEY_SUSPEND_ID),
                "解冻 payload 不得携带协议外字段 suspend_id");
        assertEquals("3", released.payloadView().get(ParamDispatchServiceImpl.KEY_SUB_ACCOUNT_ID),
                "解冻仍限定该子账户（禁止 sub_account_id=0 全局解冻）");
        assertEquals(0, service.outstandingSuspends(3), "撤销后计数归零");
    }

    @Test
    @DisplayName("配对撤销收紧：未知/重复标识一律 10009，且不得误动未撤销挂起计数")
    void releaseRejectsUnknownSuspendId() {
        bridge.ack = ack(true, "OK");
        ParamDispatchServiceImpl service = service();
        service.suspendOpenOnly(5, "S-A", "来源 A", 7L);

        int sentBefore = bridge.sent.size();
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.releaseOpenOnly(5, "不存在的标识", "擦", 7L)).errorCode(),
                "未知标识须拒绝（否则可能误放开他人挂起）");
        assertEquals(1, service.outstandingSuspends(5), "被拒后计数不得减少");
        assertEquals(sentBefore, bridge.sent.size(), "被拒后不得下发任何帧");

        service.releaseOpenOnly(5, "S-A", "正常撤销", 7L);
        assertEquals(0, service.outstandingSuspends(5));
        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.releaseOpenOnly(5, "S-A", "重复撤销", 7L)).errorCode(),
                "同一标识重复撤销须拒绝");
    }

    @Test
    @DisplayName("HGC-1.6：挂起标识须经 API 可见 —— 铸造器公开且唯一；4 参主入口用自持标识配对撤销")
    void suspendIdIsApiVisible() {
        bridge.ack = ack(true, "OK");
        ParamDispatchServiceImpl service = service();

        String minted = ParamDispatchServiceImpl.newSuspendId();
        assertNotNull(minted, "铸造器须公开可用（调用方自持标识，不解析报文文本）");
        assertFalse(minted.isEmpty());
        assertFalse(minted.equals(ParamDispatchServiceImpl.newSuspendId()), "标识须唯一");

        AckResult ack = service.suspendOpenOnly(5, minted, "自持标识挂起", 7L);
        assertTrue(ack.acknowledged);
        assertEquals(1, service.outstandingSuspends(5));
        assertEquals(minted, service.peekSuspendId(5), "API 可直接回读该标识（亦可由调用方自持）");

        AckResult release = service.releaseOpenOnly(5, minted, "用自持标识撤销", 7L);
        assertTrue(release.acknowledged, "归零后应下发解冻");
        assertEquals(0, service.outstandingSuspends(5));

        assertEquals(ErrorCode.STATE_NOT_ALLOWED,
                assertThrows(BizException.class,
                        () -> service.suspendOpenOnly(5, "", "空标识", 7L)).errorCode(),
                "空标识即缺陷（HGC-1.6：须由调用方传入）");
    }

    // ------------------------------------------------------------ 桩

    private static AckResult ack(boolean acknowledged, String message) {
        AckResult ack = new AckResult();
        ack.acknowledged = acknowledged;
        ack.message = message;
        ack.ackTime = "2026-09-20T10:00:00";
        return ack;
    }

    /** 可编程组件桥接桩（只实现下发相关三项，其余显式失败）。 */
    private static final class StubBridge implements TradingBridge {

        private volatile boolean ready = true;
        private volatile boolean neverCompletes;
        private volatile boolean throwOnDispatch;
        private volatile AckResult ack = ack(true, "OK");
        private final List<AckResult> ackQueue = Collections.synchronizedList(new ArrayList<>());
        private final List<ParamChange> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<AckResult> dispatch(ParamChange change) {
            if (throwOnDispatch) {
                throw new IllegalStateException("通道断开（桩构造）");
            }
            sent.add(change);
            if (neverCompletes) {
                return new CompletableFuture<>();
            }
            AckResult next = ackQueue.isEmpty() ? ack : ackQueue.remove(0);
            next.paramVersion = change.paramVersion;
            return CompletableFuture.completedFuture(next);
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public CompletableFuture<PlaceOrderResult> placeOrder(PlaceOrderCmd cmd) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("placeOrder");
        }

        @Override
        public CompletableFuture<AckResult> confirmOrder(long orderId, long operatorId, String confirmToken) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("confirmOrder");
        }

        @Override
        public CompletableFuture<AckResult> cancelOrder(long orderId, String reason, long operatorId) {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("cancelOrder");
        }

        @Override
        public ReplayProgress replayProgress() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("replayProgress");
        }

        @Override
        public MirrorSnapshot mirror() {
            throw com.hedgeguard.testsupport.FakeDao.unsupported("mirror");
        }
    }

    /** 内存假下发留痕 DAO（{@code count}/{@code insert} 加锁，模拟 DB 的原子性）。 */
    private static final class FakeParamDispatchDao extends UnsupportedBaseDao<ParamDispatch, Long>
            implements ParamDispatchDao {

        private final List<ParamDispatch> rows = Collections.synchronizedList(new ArrayList<>());
        private long nextId = 1;

        @Override
        public synchronized int insert(ParamDispatch entity, long operatorId) {
            entity.id = nextId++;
            rows.add(entity);
            return 1;
        }

        @Override
        public synchronized long count(String where, List<Object> whereArgs) {
            return rows.size();
        }

        @Override
        public synchronized int updateAck(long id, String dispatchStatus,
                                          java.time.LocalDateTime ackTime, String ackDetail,
                                          int retryCount) {
            for (ParamDispatch row : rows) {
                if (row.id == id) {
                    row.dispatchStatus = dispatchStatus;
                    row.ackTime = ackTime;
                    row.ackDetail = ackDetail;
                    row.retryCount = retryCount;
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public List<ParamDispatch> findByVersion(long paramVersion, String dispatchType) {
            List<ParamDispatch> out = new ArrayList<>();
            for (ParamDispatch row : rows) {
                if (row.paramVersion == paramVersion) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public boolean isBatchComplete(long paramVersion, String dispatchType, int batchTotal) {
            return findByVersion(paramVersion, dispatchType).size() >= batchTotal;
        }

        @Override
        public List<ParamDispatch> findProblems() {
            List<ParamDispatch> out = new ArrayList<>();
            for (ParamDispatch row : rows) {
                if ("PENDING".equals(row.dispatchStatus) || "FAIL".equals(row.dispatchStatus)
                        || "UNCONFIRMED".equals(row.dispatchStatus)) {
                    out.add(row);
                }
            }
            return out;
        }

        @Override
        public synchronized long maxParamVersion() {
            long max = 0L;
            for (ParamDispatch row : rows) {
                max = Math.max(max, row.paramVersion);
            }
            return max;
        }

        @Override
        public List<ParamDispatch> findBySubAccount(int subAccountId) {
            List<ParamDispatch> out = new ArrayList<>();
            for (ParamDispatch row : rows) {
                if (row.subAccountId != null && row.subAccountId == subAccountId) {
                    out.add(row);
                }
            }
            return out;
        }
    }
}
