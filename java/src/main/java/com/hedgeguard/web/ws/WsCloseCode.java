package com.hedgeguard.web.ws;

/**
 * RFC 6455 §7.4.1 关闭码（本系统用到的子集）。
 *
 * <p>与 {@code ARCHITECTURE.md} §4.1.2 / `CONTRACTS.md` §4.1.2 `W-4` 的对应关系：
 * <ul>
 *   <li>{@link #POLICY_VIOLATION}（1008）：**升级后**鉴权/权限违规（含 `W-2` 子账户越权）、会话失效；</li>
 *   <li>{@link #TRY_AGAIN_LATER}（1013）：服务端过载（连接数达 {@code hg.web.ws.max_connections}）；</li>
 *   <li>{@link #PROTOCOL_ERROR}（1002）：**帧级**协议错误（RSV 置位 / 客户端帧未掩码 / 未知操作码 / 控制帧超长或分片 / 非终止数据帧）；</li>
 *   <li>{@link #INVALID_PAYLOAD}（1007）：文本帧载荷非合法 UTF-8；</li>
 *   <li>{@link #TOO_BIG}（1009）：单帧载荷超出上限。</li>
 * </ul>
 *
 * <p>⚠️ **与派发措辞的差异（已登记待裁定）**：派发单写「升级后协议错误用 close(1008)」；
 * 本实现按 RFC 6455 细分——**帧级协议错误用 `1002`、策略/鉴权违规用 `1008`**（两者语义不同，前端可据此区分"重连"与"跳登录"）。
 * 若需统一为 `1008`，请裁定，改一处常量即可。
 */
public final class WsCloseCode {

    /** 正常关闭（1000）。 */
    public static final int NORMAL = 1000;

    /** 对端离开（1001）。 */
    public static final int GOING_AWAY = 1001;

    /** 帧级协议错误（1002）。 */
    public static final int PROTOCOL_ERROR = 1002;

    /** 不支持的数据类型（1003，如客户端二进制数据帧或分片）。 */
    public static final int UNSUPPORTED_DATA = 1003;

    /** 载荷非法（1007，文本帧非合法 UTF-8）。 */
    public static final int INVALID_PAYLOAD = 1007;

    /** 策略违规（1008，鉴权/权限/会话失效）。 */
    public static final int POLICY_VIOLATION = 1008;

    /** 载荷过大（1009）。 */
    public static final int TOO_BIG = 1009;

    /** 服务端内部错误（1011）。 */
    public static final int INTERNAL_ERROR = 1011;

    /** 过载稍后重试（1013）。 */
    public static final int TRY_AGAIN_LATER = 1013;

    private WsCloseCode() {
    }
}
