package com.hedgeguard.web;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.MirrorSnapshot;
import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.bridge.PlaceOrderCmd;
import com.hedgeguard.bridge.PlaceOrderResult;
import com.hedgeguard.bridge.ReplayProgress;
import com.hedgeguard.bridge.TradingBridge;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.ParamDispatchDao;
import com.hedgeguard.dao.entity.ParamDispatch;
import com.hedgeguard.service.FakeTx;
import com.hedgeguard.service.ParamDispatchService;
import com.hedgeguard.service.impl.ParamDispatchServiceImpl;
import com.hedgeguard.testsupport.UnsupportedBaseDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-042} S3：参数下发服务的<b>生产装配</b>取证（离线，不接触 DM8，不起 socket）。
 *
 * <p><b>为何需要本类</b>：{@code T-042} 的核心可交付之一是「{@code ParamDispatchService} 有生产装配点」，
 * 而该事实**无法**由 {@code BootstrapAssemblyTest} 的 {@code routeCount()==83} 覆盖——
 * 参数下发是<b>服务而不注册路由</b>（无 {@code ParamDispatchRoutes}），删掉装配行不会改变路由计数，
 * 属"改回退也全绿"的盲区。故此处直接对装配产物断言。
 *
 * <p>判据分三层：
 * <ol>
 *   <li>{@link Bootstrap#buildParamDispatchService} —— 生产装配**真正调用**的构造入口
 *       （非仅测试可达），桥不可用时返回 {@code null}（参数下发保持不可用，而非空桥 NPE）；</li>
 *   <li>桥可用时返回的是**真实** {@link ParamDispatchServiceImpl}，且回执超时取自
 *       {@code CFG-28 hg.cmd_timeout_ms}（默认 {@value ParamDispatchServiceImpl#ACK_TIMEOUT_MILLIS} ms）
 *       —— 以该键置 {@code 0} 作判别量，非零值即证「未被硬编码」；</li>
 *   <li>未就绪的桥被调 {@code dispatch} 时**显式拒绝**（{@code 10002}），不静默丢弃
 *       —— 即 {@code Bootstrap} 在 C++ 组件不可达时保留桥实例（而非降级为 null）的实际后果。</li>
 * </ol>
 */
class ParamDispatchAssemblyTest {

    private static final String TIMEOUT_KEY = ParamDispatchServiceImpl.KEY_CMD_TIMEOUT_MS;

    @Test
    @DisplayName("生产装配：桥不可用（凭据未配置）⇒ 返回 null，参数下发保持不可用")
    void withoutBridgeServiceIsNull() {
        try {
            assertNull(Bootstrap.buildParamDispatchService(FakeTx.newTemplate(),
                            new RecordingDao(), Config.empty(), null),
                    "无组件桥时不得构造参数下发服务（否则以空桥在首次调用时 NPE）");
        } finally {
            System.clearProperty(TIMEOUT_KEY);
        }
    }

    @Test
    @DisplayName("生产装配：桥可用 ⇒ 返回真实 ParamDispatchServiceImpl，且回执超时取自 CFG-28（非硬编码）")
    void withBridgeServiceIsRealAndHonoursCfg28() {
        System.setProperty(TIMEOUT_KEY, "200");
        try {
            RecordingDao dao = new RecordingDao();
            ParamDispatchService service = Bootstrap.buildParamDispatchService(FakeTx.newTemplate(),
                    dao, Config.empty(), new StubBridge(true));
            assertNotNull(service, "桥可用时必须构造参数下发服务");
            assertEquals(ParamDispatchServiceImpl.class, service.getClass(),
                    "装配产物须是生产实现类（接口无法表达超时语义）");

            // CFG-28 判别量：桥「接受下发但永不回执」⇒ 结果由 ackTimeout 决定。
            // settle() 的超时分支把**实际生效的毫秒数**写进 ackDetail（"回执超时（<n> ms，CFG-28）…"）
            // ⇒ 置 200 而读回 200，即证该键确实被装配采纳；
            // 若构造改回硬编码 ACK_TIMEOUT_MILLIS(3000)，则同断言读回 "3000 ms" 必红。
            long begin = System.nanoTime();
            AckResult ack = service.dispatch(
                    new ParamChange(ParamChange.Type.CMD_UPDATE_QUOTA, 0L, 7L)).join();
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;

            assertFalse(ack.acknowledged, "无回执不得视为已生效（R-CONFIG-3）");
            assertEquals("UNCONFIRMED", dao.lastAckStatus,
                    "超时须以 UNCONFIRMED 留痕（CONTRACTS §3.2.1）");
            assertNotNull(dao.lastAckDetail, "超时须写回执明细");
            assertTrue(dao.lastAckDetail.contains("200 ms"),
                    "回执超时应取自 CFG-28 hg.cmd_timeout_ms=200（实际生效毫秒数内嵌于明细），实际="
                            + dao.lastAckDetail);
            assertTrue(elapsedMs < ParamDispatchServiceImpl.ACK_TIMEOUT_MILLIS,
                    "实际等待须短于硬编码默认值 " + ParamDispatchServiceImpl.ACK_TIMEOUT_MILLIS
                            + " ms（否则说明未采纳 CFG-28），实际=" + elapsedMs + " ms");
        } finally {
            System.clearProperty(TIMEOUT_KEY);
        }
    }

    @Test
    @DisplayName("未就绪桥（C++ 不可达的稳态）：下发被显式拒绝 10002，不静默丢弃")
    void notReadyBridgeRejectsExplicitly() {
        System.setProperty(TIMEOUT_KEY, "50");
        try {
            RecordingDao dao = new RecordingDao();
            ParamDispatchService service = Bootstrap.buildParamDispatchService(FakeTx.newTemplate(),
                    dao, Config.empty(), new StubBridge(false));

            BizException e = assertThrows(BizException.class,
                    () -> service.dispatch(new ParamChange(ParamChange.Type.CMD_UPDATE_QUOTA, 0L, 7L)),
                    "组件未就绪时下发必须显式拒绝（不得静默丢弃）");
            assertEquals(ErrorCode.COMPONENT_UNAVAILABLE, e.errorCode(),
                    "拒绝码应为 10002（交易组件通信不可用）");
            assertTrue(dao.inserted.isEmpty(),
                    "未就绪时**不得**写入 PENDING 留痕（先落库再下发纪律的前提是确实发起下发）");
        } finally {
            System.clearProperty(TIMEOUT_KEY);
        }
    }

    @Test
    @DisplayName("装配产物可观测：凭据未配置时 Assembly.paramDispatchService()/hgcpClient() 均为 null")
    void assemblyExposesParamDispatchObservability() {
        // 本环境未设置 HG_HGCP_AUTH_TOKEN（Config.secret 只读进程环境，测试无法注入）⇒ 走「未装配」路。
        // 该断言的价值在于：装配结果**离开日志、成为可查询的装配产物字段**，
        // 与「已装配」路（由 buildParamDispatchService 直测覆盖）构成可区分的两条路。
        System.setProperty(Config.KEY_DB_URL, "jdbc:dm://127.0.0.1:5236/HG");
        System.setProperty(Config.KEY_DB_DRIVER, FakeJdbcDriver.class.getName());
        System.setProperty(Config.KEY_DB_USER, "hg_app");
        System.setProperty(Config.KEY_DB_POOL_INITIAL, "1");
        try {
            Bootstrap.Assembly assembly = Bootstrap.assembleWithResources(Config.empty());
            try {
                assertTrue(assembly.databaseAssembled(), "假驱动下应装配数据库层（本用例的离线前提）");
                assertNull(assembly.hgcpClient(),
                        "凭据未配置时不得装配 HGCP 组件桥");
                assertNull(assembly.paramDispatchService(),
                        "组件桥未装配时参数下发服务须为 null（可观测，不再只靠日志）");
            } finally {
                Bootstrap.close(assembly);
            }
        } finally {
            for (String key : new String[]{Config.KEY_DB_URL, Config.KEY_DB_DRIVER,
                    Config.KEY_DB_USER, Config.KEY_DB_POOL_INITIAL}) {
                System.clearProperty(key);
            }
        }
    }

    // ------------------------------------------------------------------ 桩

    /** 只记录 {@code insert} 的假 DAO；其余方法显式失败（避免桩静默返回掩盖缺陷）。 */
    private static final class RecordingDao extends UnsupportedBaseDao<ParamDispatch, Long>
            implements ParamDispatchDao {

        final List<ParamDispatch> inserted = new ArrayList<>();
        String lastAckStatus;
        String lastAckDetail;

        @Override
        public int insert(ParamDispatch entity, long operatorId) {
            inserted.add(entity);
            return 1;
        }

        @Override
        public long maxParamVersion() {
            return 0L;
        }

        @Override
        public List<ParamDispatch> findByVersion(long paramVersion, String dispatchType) {
            return new ArrayList<>();
        }

        @Override
        public boolean isBatchComplete(long paramVersion, String dispatchType, int batchTotal) {
            return false;
        }

        @Override
        public List<ParamDispatch> findProblems() {
            return new ArrayList<>();
        }

        @Override
        public List<ParamDispatch> findBySubAccount(int subAccountId) {
            return new ArrayList<>();
        }

        @Override
        public int updateAck(long id, String dispatchStatus, LocalDateTime ackTime, String ackDetail,
                             int retryCount) {
            lastAckStatus = dispatchStatus;
            lastAckDetail = ackDetail;
            return 1;
        }
    }

    /** 极简桥桩：{@code isReady} 可控；其余方法不被本类驱动。 */
    private static final class StubBridge implements TradingBridge {

        private final boolean ready;

        StubBridge(boolean ready) {
            this.ready = ready;
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public CompletableFuture<PlaceOrderResult> placeOrder(PlaceOrderCmd cmd) {
            throw new UnsupportedOperationException("本用例不驱动 placeOrder");
        }

        @Override
        public CompletableFuture<AckResult> confirmOrder(long orderId, long operatorId,
                                                         String confirmToken) {
            throw new UnsupportedOperationException("本用例不驱动 confirmOrder");
        }

        @Override
        public CompletableFuture<AckResult> cancelOrder(long orderId, String reason,
                                                        long operatorId) {
            throw new UnsupportedOperationException("本用例不驱动 cancelOrder");
        }

        @Override
        public CompletableFuture<AckResult> dispatch(ParamChange change) {
            if (!ready) {
                // 未就绪时 ParamDispatchServiceImpl 会在调用本方法**之前**就拒绝，故不应到达此处
                throw new UnsupportedOperationException("未就绪路径不应触及 bridge.dispatch");
            }
            // 接受下发但**永不回执**：把结果判定权交给 ParamDispatchServiceImpl 的 ackTimeout，
            // 从而让 CFG-28 成为可观测的判别量（超时文案内嵌实际生效的毫秒数）。
            return new CompletableFuture<>();
        }

        @Override
        public ReplayProgress replayProgress() {
            throw new UnsupportedOperationException("本用例不驱动 replayProgress");
        }

        @Override
        public MirrorSnapshot mirror() {
            throw new UnsupportedOperationException("本用例不驱动 mirror");
        }
    }
}
