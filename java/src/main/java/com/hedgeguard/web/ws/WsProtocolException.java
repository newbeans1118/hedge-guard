package com.hedgeguard.web.ws;

/**
 * WebSocket 协议/策略错误——**携带应回给对端的关闭码**。
 *
 * <p>由 {@link WsFrameCodec}（帧级）与 {@link WsAuthorizer}（策略级）抛出，
 * 由 {@link WsConnection} 统一转为 {@code close(code, reason)}（`CONTRACTS.md` §4.1.2 `W-4`）。
 */
public class WsProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int closeCode;

    /**
     * 构造。
     *
     * @param closeCode RFC 6455 关闭码（见 {@link WsCloseCode}）
     * @param reason    关闭原因（**不得含敏感信息**，会回给对端）
     */
    public WsProtocolException(int closeCode, String reason) {
        super(reason);
        this.closeCode = closeCode;
    }

    /** 应回给对端的关闭码。 */
    public int closeCode() {
        return closeCode;
    }
}
