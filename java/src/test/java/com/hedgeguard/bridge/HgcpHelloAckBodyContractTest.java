package com.hedgeguard.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code T-208} —— {@code MSG_HELLO_ACK} body 恒空致合法握手被判 {@code AUTH_FAILED}
 * （**真联阻断级**）的 Java 侧**击杀器**。
 *
 * <p><b>缺陷本体</b>：C++ {@code server_loop.cc} 回发 {@code MSG_HELLO_ACK} 时用
 * {@code empty_body()} ⇒ proto3 空 body 解出<b>默认值</b>（{@code accepted=false} /
 * {@code server_proto_version=0} / {@code server_state=SERVER_STATE_UNSET}）⇒
 * {@link HgcpClient} 以 {@code ack.getAccepted()} 判握手 ⇒ <b>合法握手被判
 * {@code AUTH_FAILED}</b>（{@code HgcpClient.java:301/:309}），连接不可用。
 *
 * <p><b>为何长期静默（判别力缺口）</b>：Java 侧既有用例
 * （{@code HgcpClientGuardTest} / {@code HgcpClientTimeoutTest} /
 * {@code ParamChangeEncoderPermissionRouteTest}）<b>各自用
 * {@code MsgHelloAck.newBuilder().setAccepted(true)} 自建报文</b>，
 * <b>从不消费真实 C++ 产出</b> ⇒ 即便 C++ 侧恒发空 body，本侧仍全绿。
 *
 * <p><b>本类立意的关键</b>：夹具来自 <b>C++ {@code PbTransportPolicy::hello_ack_body}
 * 的真实逐字节输出</b>（跨侧共享 golden 文件
 * {@code cpp/src/hgcp/tests/golden/hello_ack_bodies.tsv}，单一事实源）
 * —— 把「区块的 body 形态」从 C++ 侧<b>搬</b>到本侧消费。若 C++ 侧退回
 * {@code empty_body()}（变异①）或把 {@code accepted} 恒置 {@code false}（变异②），
 * 本类夹具必须随之更新方能解释，从而<b>不可能再两侧各自全绿</b>。
 *
 * <p><b>诚实披露</b>：本类经<b>公开接缝</b>（{@link HgcpClient#onFrame} + 记录型
 * {@link HgcpTransport}）驱动，<b>未改生产码一行</b>；夹具来源为仓库内共享文件
 * （读文件 + hex 解码），非本地自建 {@code MsgHelloAck}。
 */
class HgcpHelloAckBodyContractTest {

    /** golden 文件相对仓库根路径（与 C++ 侧 {@code CMakeLists.txt} 指向同一文件）。 */
    private static final String GOLDEN =
            "cpp/src/hgcp/tests/golden/hello_ack_bodies.tsv";

    /** 记录出站整帧的内存传输桩（只收不发；不启读线程 ⇒ 无后台线程）。 */
    private static final class RecordingTransport implements HgcpTransport {
        final List<byte[]> sent = new ArrayList<>();

        @Override
        public void connect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            sent.add(frame.clone());
        }

        @Override
        public int read(byte[] buf) throws IOException {
            throw new IOException("记录桩不读取");
        }

        @Override
        public void close() {
        }
    }

    private static HgcpClient newClient(HgcpTransport transport) {
        return new HgcpClient("java-test", "token-not-logged", "boot-1", 1, () -> transport);
    }

    /** 注入传输接缝（同 {@code HgcpClientGuardTest} 先例：反射写私有字段，不建连）。 */
    private static void injectTransport(HgcpClient client, HgcpTransport transport) {
        try {
            java.lang.reflect.Field f = HgcpClient.class.getDeclaredField("transport");
            f.setAccessible(true);
            f.set(client, transport);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入传输接缝 transport（字段被改名？）", e);
        }
    }

    /** 一行 golden 向量（对应 TSV 一行的 8 列）。 */
    private static final class Vec {
        final String label;
        final boolean accepted;
        final int serverProtoVersion;
        final String sessionId;
        final int serverState;
        final long paramVersion;
        final int reasonCode;
        final byte[] body;

        Vec(String label, boolean accepted, int serverProtoVersion, String sessionId,
            int serverState, long paramVersion, int reasonCode, byte[] body) {
            this.label = label;
            this.accepted = accepted;
            this.serverProtoVersion = serverProtoVersion;
            this.sessionId = sessionId;
            this.serverState = serverState;
            this.paramVersion = paramVersion;
            this.reasonCode = reasonCode;
            this.body = body;
        }
    }

    /** 读取跨侧共享 golden（# / 空行跳过；列以 \t 分割；`<empty>` ⇒ 空串）。 */
    private static List<Vec> loadGolden() throws IOException {
        Path path = resolve(GOLDEN);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Vec> out = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split("\t", -1);
            if (c.length > 0 && "label".equals(c[0])) {
                continue;  // 表头行
            }
            assertEquals(8, c.length, "golden 行列数须为 8（T-208）：" + line);
            out.add(new Vec(c[0], Boolean.parseBoolean(c[1]), Integer.parseInt(c[2]),
                    "<empty>".equals(c[3]) ? "" : c[3], Integer.parseInt(c[4]),
                    Long.parseLong(c[5]), Integer.parseInt(c[6]), hexToBytes(c[7])));
        }
        assertTrue(out.size() >= 2, "golden 须含 ≥2 向量（READY / NOT_READY）");
        return out;
    }

    private static byte[] hexToBytes(String hex) {
        assertEquals(0, hex.length() % 2, "hex 长度须为偶数：" + hex);
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    /** 仓库根定位（{@code java/} 下运行 mvn test；向上找到含 docs/ 的根）。 */
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

    /** 把一条 body 作为 {@code MSG_HELLO_ACK} 帧喂给客户端（公开接缝）。 */
    private static void feedHelloAck(HgcpClient client, byte[] body) {
        HgcpFrame.Header h = new HgcpFrame.Header();
        h.type = HgcpConstants.MSG_HELLO_ACK;
        h.flags = HgcpFrame.FLAG_IS_REPLY;
        h.seq = 1L;
        h.msgId = 1L;
        byte[] frame = HgcpFrame.encode(h, body);
        HgcpFrame.Decoded d = HgcpFrame.decode(frame, 0, frame.length);
        assertEquals(HgcpFrame.Status.OK, d.status, "夹具自身须能解出 OK 帧");
        client.onFrame(d);
    }

    /**
     * 验收⑤核心断言：<b>消费真实 C++ body 形态</b>的 {@code MSG_HELLO_ACK} 须判
     * {@code AUTHENTICATED}，且镜像状态 / 参数版本与 body 一致。
     *
     * <p><b>变异②击杀器</b>：C++ 侧若把 {@code accepted} 恒置 {@code false}，
     * 则 golden 的 {@code body_hex} 必随产出方更新（C++ 侧
     * {@code test_hello_ack_body_schema} 逐行断言向量 == 产出）⇒ 本条断言
     * {@code AUTHENTICATED} 必红。
     */
    @Test
    @DisplayName("T-208：消费 C++ 真实 MsgHelloAck body ⇒ AUTHENTICATED（非自建报文）")
    void consumesRealCppHelloAckBody() throws Exception {
        List<Vec> vectors = loadGolden();
        for (Vec v : vectors) {
            RecordingTransport t = new RecordingTransport();
            HgcpClient c = newClient(t);
            injectTransport(c, t);

            // ⚠️ 前置：夹具 body **非空**（空 = 本缺陷本体 ⇒ 消费侧必判 AUTH_FAILED）。
            assertTrue(v.body.length > 0,
                    "[" + v.label + "] 夹具 body 不得为空（空 body 即本缺陷：proto3 解出全默认值）");

            feedHelloAck(c, v.body);

            assertEquals(HgcpClient.AuthState.AUTHENTICATED, c.authState(),
                    "[" + v.label + "] 真实 MsgHelloAck body（accepted=true）须判 AUTHENTICATED");
            assertEquals(v.sessionId, c.sessionId(),
                    "[" + v.label + "] session_id 须与 body 逐字一致");
            if (v.paramVersion > 0) {
                assertEquals(v.paramVersion, c.mirror().paramVersion,
                        "[" + v.label + "] param_version > 0 时须写入镜像");
            }
            // server_state 经 mapServerState 落入镜像就绪态（NOT_READY / REPLAYING / READY）
            ReplayProgress.State expect = v.serverState == 3
                    ? ReplayProgress.State.READY
                    : (v.serverState == 2 ? ReplayProgress.State.REPLAYING
                    : ReplayProgress.State.NOT_READY);
            assertEquals(expect, c.mirror().readyState,
                    "[" + v.label + "] server_state=" + v.serverState + " 须映射为 " + expect);
        }
    }

    /**
     * 反证（本缺陷形状的负控）：<b>空 body</b>（= {@code empty_body()} 的线上形态）喂入后
     * 必判 {@code AUTH_FAILED} —— 以实例证明「夹具非空」这一前置断言确有判别力，
     * 而非同义反复（T-208 缺陷本体即此形状）。
     */
    @Test
    @DisplayName("T-208：空 body（缺陷形状）必判 AUTH_FAILED（负控）")
    void emptyBodyIsRejected() {
        RecordingTransport t = new RecordingTransport();
        HgcpClient c = newClient(t);
        injectTransport(c, t);

        feedHelloAck(c, new byte[0]);

        assertEquals(HgcpClient.AuthState.AUTH_FAILED, c.authState(),
                "空 body（empty_body()）解析出的 accepted=false ⇒ 必判 AUTH_FAILED（本缺陷本体）");
    }

    /** golden 文件存在且可解析（防路径/格式静默漂移）。 */
    @Test
    @DisplayName("T-208：跨侧共享 golden 夹具存在且格式自洽")
    void goldenFixtureIsWellFormed() throws Exception {
        List<Vec> vectors = loadGolden();
        assertNotNull(vectors);
        boolean sawReady = false;
        for (Vec v : vectors) {
            assertEquals(0x0100, v.serverProtoVersion,
                    "[" + v.label + "] server_proto_version 须 = 0x0100（与 kWireVersion 对齐）");
            assertTrue(v.accepted, "[" + v.label + "] 本夹具为合法握手向量 ⇒ accepted 须为 true");
            if (v.serverState == 3) {
                sawReady = true;
            }
        }
        assertTrue(sawReady, "golden 须含至少一条 server_state=READY 向量");
    }
}
