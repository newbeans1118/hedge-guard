package com.hedgeguard.bridge;

import java.io.IOException;

/**
 * HGCP 传输接缝（{@code NFR-TEST-4}「外部依赖可注入」）。
 *
 * <p>生产实现 = 本地 TCP 回环 {@code Socket}；测试实现 = 内存回环/脚本化桩。
 * 抽出来的目的：<b>使 {@link HgcpClient} 的状态机（握手 / 心跳 / 重连 / 补数 / 回执关联）
 * 可在无 C++ 侧的环境下被真实驱动</b>。
 *
 * <p>⚠️ <b>诚实披露</b>：用内存实现驱动<b>不等于</b>跨侧联调通过 —— 它验证的是
 * <b>Java 侧状态机自身的正确性</b>，两侧字节级一致性另需 {@code T-057} 协议专项测试。
 */
public interface HgcpTransport extends AutoCloseable {

    /**
     * 建立连接。
     *
     * @throws IOException 连接失败
     */
    void connect() throws IOException;

    /** 是否已连接。 */
    boolean isConnected();

    /**
     * 发送整帧字节（一次 write）。
     *
     * @param frame 整帧（含 MAGIC 与 CRC）
     * @throws IOException 发送失败
     */
    void send(byte[] frame) throws IOException;

    /**
     * 读取一批字节（阻塞，直到有数据或连接关闭）。
     *
     * @param buf 目标缓冲
     * @return 读取字节数；{@code -1} = 对端关闭
     * @throws IOException 读取失败
     */
    int read(byte[] buf) throws IOException;

    /** 关闭。 */
    @Override
    void close();
}
