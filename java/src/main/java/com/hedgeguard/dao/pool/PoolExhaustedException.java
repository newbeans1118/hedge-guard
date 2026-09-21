package com.hedgeguard.dao.pool;

/**
 * 连接池借出超时（{@code maxSize} 个连接全部被占用且等待超时）。
 *
 * <p>语义与 {@code 0x0304 ERR_BACKPRESSURE} 同源：<b>拒绝并让调用方知晓</b>，
 * <b>不得</b>假装成功、不得无限阻塞（{@code ARCHITECTURE.md} §8.5）。
 */
public class PoolExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param message 诊断信息（发生了什么 / 在哪 / 如何复现）
     */
    public PoolExhaustedException(String message) {
        super(message);
    }
}
