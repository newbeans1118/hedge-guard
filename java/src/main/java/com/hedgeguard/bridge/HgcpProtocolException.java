package com.hedgeguard.bridge;

/**
 * HGCP 协议级异常（成帧失败 / 会话拒绝）。
 *
 * <p>携带 {@link HgcpFrame.Status} ⇒ 可映射为 §4.3.2 组件错误码。
 * 成帧失败后<b>帧边界不再可信</b>，调用方须关闭连接并重连补数
 * （与 C++ 侧 {@code CH-014} 裁定同姿态：不尝试「丢弃至下一 MAGIC」重同步）。
 */
public class HgcpProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient HgcpFrame.Status status;

    /**
     * 构造（协议状态）。
     *
     * @param status 帧状态
     */
    public HgcpProtocolException(HgcpFrame.Status status) {
        super("HGCP 协议错误：" + status);
        this.status = status;
    }

    /**
     * 构造（自由消息）。
     *
     * @param message 消息
     */
    public HgcpProtocolException(String message) {
        super(message);
        this.status = null;
    }

    /**
     * 帧状态（自由消息构造时为 {@code null}）。
     *
     * @return 状态
     */
    public HgcpFrame.Status status() {
        return status;
    }

    /**
     * §4.3.2 组件错误码（无状态时为 0）。
     *
     * @return 错误码
     */
    public int componentCode() {
        return (status == null) ? 0 : status.componentCode();
    }
}
