package com.hedgeguard.bridge;

/**
 * HGCP 消息类型码与时序常量（{@code ARCHITECTURE.md} §4.2.3 / §11.2 {@code CFG-28}；
 * 与 C++ 侧 {@code cpp/src/hgcp/include/hg/hgcp/protocol.h}、{@code timing.h}、{@code resume_buffer.h}
 * <b>逐值同源</b>，不得单侧改动）。
 *
 * <p>类型码与 {@code proto/hgcp.proto} 的 {@code enum MsgType} 逐值一致；
 * 后者仅供代码可读性、<b>非线格式</b>——线格式的类型码由 32B 帧头的 {@code uint16} 承载。
 */
public final class HgcpConstants {

    // ---- 元协议（0x0001–0x0009）----
    /** MSG_HELLO。 */
    public static final int MSG_HELLO = 0x0001;
    /** MSG_HELLO_ACK。 */
    public static final int MSG_HELLO_ACK = 0x0002;
    /** MSG_AUTH_FAIL。 */
    public static final int MSG_AUTH_FAIL = 0x0003;
    /** MSG_HEARTBEAT。 */
    public static final int MSG_HEARTBEAT = 0x0004;
    /** MSG_HEARTBEAT_ACK。 */
    public static final int MSG_HEARTBEAT_ACK = 0x0005;
    /** MSG_ACK。 */
    public static final int MSG_ACK = 0x0006;
    /** MSG_ERROR。 */
    public static final int MSG_ERROR = 0x0007;
    /** MSG_RESUME_REQ。 */
    public static final int MSG_RESUME_REQ = 0x0008;
    /** MSG_RESUME_DATA。 */
    public static final int MSG_RESUME_DATA = 0x0009;

    // ---- 控制指令（Java → C++）----
    /** CMD_PLACE_ORDER。 */
    public static final int CMD_PLACE_ORDER = 0x0101;
    /** CMD_CONFIRM_ORDER。 */
    public static final int CMD_CONFIRM_ORDER = 0x0102;
    /** CMD_CANCEL_ORDER。 */
    public static final int CMD_CANCEL_ORDER = 0x0103;
    /** CMD_LOAD_RISK_PARAMS。 */
    public static final int CMD_LOAD_RISK_PARAMS = 0x0111;
    /** CMD_UPDATE_WHITELIST。 */
    public static final int CMD_UPDATE_WHITELIST = 0x0112;
    /** CMD_UPDATE_QUOTA。 */
    public static final int CMD_UPDATE_QUOTA = 0x0113;
    /** CMD_UPDATE_POSITION_LIMIT。 */
    public static final int CMD_UPDATE_POSITION_LIMIT = 0x0114;
    /** CMD_UPDATE_RISK_THRESHOLD。 */
    public static final int CMD_UPDATE_RISK_THRESHOLD = 0x0115;
    /** CMD_UPDATE_PERMISSION。 */
    public static final int CMD_UPDATE_PERMISSION = 0x0116;
    /** CMD_UPDATE_EXPOSURE。 */
    public static final int CMD_UPDATE_EXPOSURE = 0x0117;
    /** CMD_SET_TRADING_SUSPEND。 */
    public static final int CMD_SET_TRADING_SUSPEND = 0x0118;
    /** CMD_SET_READY_STATE。 */
    public static final int CMD_SET_READY_STATE = 0x0119;
    /** CMD_RELOAD。 */
    public static final int CMD_RELOAD = 0x011A;
    /** CMD_TRIGGER_REPLAY。 */
    public static final int CMD_TRIGGER_REPLAY = 0x011B;
    /** CMD_QUERY_STATUS。 */
    public static final int CMD_QUERY_STATUS = 0x011C;
    /** CMD_FUND_TRANSFER。 */
    public static final int CMD_FUND_TRANSFER = 0x011D;
    /** CMD_QUERY_FUND_RESULT。 */
    public static final int CMD_QUERY_FUND_RESULT = 0x011E;

    // ---- 事件流（C++ → Java）----
    /** EVT_ORDER_ACCEPTED。 */
    public static final int EVT_ORDER_ACCEPTED = 0x0201;
    /** EVT_PRE_TRADE_REJECT。 */
    public static final int EVT_PRE_TRADE_REJECT = 0x0202;
    /** EVT_ORDER_REPORT。 */
    public static final int EVT_ORDER_REPORT = 0x0203;
    /** EVT_TRADE_FILL。 */
    public static final int EVT_TRADE_FILL = 0x0204;
    /** EVT_POSITION_CHANGE。 */
    public static final int EVT_POSITION_CHANGE = 0x0205;
    /** EVT_ORDER_CANCEL_RESULT。 */
    public static final int EVT_ORDER_CANCEL_RESULT = 0x0206;
    /** EVT_ORDER_EXPIRED。 */
    public static final int EVT_ORDER_EXPIRED = 0x0207;
    /** EVT_COMMAND_FLOW。 */
    public static final int EVT_COMMAND_FLOW = 0x0208;
    /** EVT_REPLAY_PROGRESS。 */
    public static final int EVT_REPLAY_PROGRESS = 0x0209;
    /** EVT_SYSTEM_STATE。 */
    public static final int EVT_SYSTEM_STATE = 0x020A;
    /** EVT_FUND_RECEIPT。 */
    public static final int EVT_FUND_RECEIPT = 0x020B;
    /** EVT_MARKET_QUOTE（可丢，不占 SEQ）。 */
    public static final int EVT_MARKET_QUOTE = 0x020C;
    /** EVT_CTP_STATE。 */
    public static final int EVT_CTP_STATE = 0x020D;
    /** EVT_TRADING_ALERT。 */
    public static final int EVT_TRADING_ALERT = 0x020E;

    /** 消息类型总数（冻结不变量）。 */
    public static final int MESSAGE_TYPE_COUNT = 40;

    // ---- 时序（CFG-28 / timing.h / resume_buffer.h）----
    /** 心跳间隔 1000 ms。 */
    public static final long HEARTBEAT_INTERVAL_MS = 1000L;
    /** 心跳丢失阈值 3 次。 */
    public static final int HEARTBEAT_MISS_THRESHOLD = 3;
    /** 心跳检测上限 5000 ms。 */
    public static final long HEARTBEAT_DETECT_MAX_MS = 5000L;
    /** 重连退避基数 500 ms。 */
    public static final long RECONNECT_BASE_MS = 500L;
    /** 重连退避封顶 8000 ms。 */
    public static final long RECONNECT_CAP_MS = 8000L;
    /** 补数环形缓冲容量（接收侧水位保护，与 C++ 侧同量）。 */
    public static final int RESUME_BUFFER_CAPACITY = 65536;
    /** 补数单批最大帧数。 */
    public static final int RESUME_MAX_BATCH_FRAMES = 500;
    /** 命令回执超时 3000 ms（{@code CFG-28 hg.cmd_timeout_ms}；验收 5）。 */
    public static final long COMMAND_TIMEOUT_MS = 3000L;

    // ---- 就绪状态（0x0401–0x0405）----
    /** ERR_SYSTEM_NOT_READY。 */
    public static final int ERR_SYSTEM_NOT_READY = 0x0401;

    private HgcpConstants() {
    }

    /**
     * 是否为 §4.2.3 清单内的消息类型（否则 ⇒ {@code 0x0105 ERR_UNKNOWN_TYPE}）。
     *
     * @param type 类型码
     * @return 是否已知
     */
    public static boolean isKnownMessageType(int type) {
        if (type >= MSG_HELLO && type <= MSG_RESUME_DATA) {
            return true;
        }
        if (type >= CMD_PLACE_ORDER && type <= CMD_CANCEL_ORDER) {
            return true;
        }
        if (type >= CMD_LOAD_RISK_PARAMS && type <= CMD_QUERY_FUND_RESULT) {
            return true;
        }
        return type >= EVT_ORDER_ACCEPTED && type <= EVT_TRADING_ALERT;
    }
}
