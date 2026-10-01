package com.hedgeguard.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * {@link HgcpTransport} 的本地 TCP 回环实现（生产形态）。
 *
 * <p>组件通道<b>仅监听 {@code 127.0.0.1}</b> 且须鉴权（{@code NFR-SEC-3}）：
 * 本类<b>只接受回环地址</b>，非回环直接抛 {@link IllegalArgumentException}
 * （与 C++ 侧 {@code endpoint.h} 的回环白名单同姿态）。
 */
public final class TcpHgcpTransport implements HgcpTransport {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int SO_TIMEOUT_MS = 1000;

    private final String host;
    private final int port;
    private Socket socket;
    private OutputStream out;
    private InputStream in;

    /**
     * 构造。
     *
     * @param host 主机（<b>须为回环</b>）
     * @param port 端口
     */
    public TcpHgcpTransport(String host, int port) {
        if (!isLoopback(host)) {
            throw new IllegalArgumentException(
                    "HGCP 组件通道仅允许回环地址（NFR-SEC-3）：" + host);
        }
        this.host = host;
        this.port = port;
    }

    /**
     * 回环判定（字面量白名单，与 C++ 侧精确匹配同口径）。
     *
     * @param host 主机
     * @return 是否回环
     */
    public static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "::1".equals(host) || "localhost".equals(host);
    }

    @Override
    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(SO_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        in = socket.getInputStream();
    }

    @Override
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void send(byte[] frame) throws IOException {
        ensureOpen();
        out.write(frame);
        out.flush();
    }

    @Override
    public int read(byte[] buf) throws IOException {
        ensureOpen();
        return in.read(buf);
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭失败不留痕：调用方只关心「已关闭」这一结果
            }
        }
        socket = null;
        out = null;
        in = null;
    }

    private void ensureOpen() throws IOException {
        if (!isConnected()) {
            throw new IOException("HGCP 连接未建立");
        }
    }
}
