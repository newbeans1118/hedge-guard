#ifndef HG_HGCP_PROTOCOL_H
#define HG_HGCP_PROTOCOL_H
// =============================================================================
// HGCP 协议常量表 —— **逐字转录**自 docs/ARCHITECTURE.md（HGCP v1.0.1, FROZEN）
//
//   帧格式常量      : §4.2.2（`ARCHITECTURE.md:759`–`:786`）
//   消息类型（40）  : §4.2.3（`:788`–`:846`）
//   组件错误码（62）: §4.3.2（`:1097`–`:1178`）
//
// ⚠️ 边界（T-032 缺口，见回执「缺口清单」）：
//   本文件**不定义**任何消息 payload 的字段级 schema（字段类型 / 字段号 / 必填性）。
//   `ARCHITECTURE.md` §4.2.3 仅给出「关键字段」**名**，§4.2.4 仅对 3 条消息给出完整
//   字段表且**全篇无字段号**。故 `proto/hgcp.proto` 的字段级定义**尚不能落笔**——
//   须经编排者/架构席裁定（可能 `CH-xxx`）。本文件只承载**已冻结且无歧义**的常量。
// =============================================================================

#include <cstddef>
#include <cstdint>

namespace hg {
namespace hgcp {

// -----------------------------------------------------------------------------
// §4.2.2 帧格式常量
// -----------------------------------------------------------------------------
// 同步字 `"HGCP"` = 0x48 0x47 0x43 0x50（§4.2.2 `:772`）
const std::uint8_t kMagic[4] = {0x48u, 0x47u, 0x43u, 0x50u};
const std::size_t kMagicSize = 4u;

// 线格式版本（§4.2.2 `:773`）：本版 = 0x0100；标称冻结版本 v1.0.1 为澄清性修订，不改线格式。
const std::uint16_t kWireVersion = 0x0100u;

// 帧头固定 32 字节，PAYLOAD 紧随其后，CRC32 在帧尾（§4.2.2 `:783`）
const std::size_t kHeaderSize = 32u;
const std::size_t kCrcSize = 4u;

// `LEN` 上限 = `HG.MAX_FRAME` = 1 MiB（§4.2.2 `:779`/`:784`）
const std::uint32_t kMaxPayloadSize = 1024u * 1024u;

// `FLAGS`（uint16 bitmask，§4.2.2 `:775`）：bit0..bit4，其余保留
enum FlagBits : std::uint16_t {
  kFlagCompressed   = 1u << 0,  // bit0 COMPRESSED（保留，恒 0）
  kFlagIsReply      = 1u << 1,  // bit1 IS_REPLY
  kFlagIsError      = 1u << 2,  // bit2 IS_ERROR
  kFlagIsResumeData = 1u << 3,  // bit3 IS_RESUME_DATA
  kFlagNeedAck      = 1u << 4   // bit4 NEED_ACK
};

// -----------------------------------------------------------------------------
// §4.2.3 消息类型总表（共 40 种：元协议 9 + 控制指令 17 + 事件 14）
// -----------------------------------------------------------------------------
enum class MessageType : std::uint16_t {
  // A. 会话与元协议（9 种，双向）
  kMsgHello        = 0x0001,  // MSG_HELLO
  kMsgHelloAck     = 0x0002,  // MSG_HELLO_ACK
  kMsgAuthFail     = 0x0003,  // MSG_AUTH_FAIL
  kMsgHeartbeat    = 0x0004,  // MSG_HEARTBEAT
  kMsgHeartbeatAck = 0x0005,  // MSG_HEARTBEAT_ACK
  kMsgAck          = 0x0006,  // MSG_ACK
  kMsgError        = 0x0007,  // MSG_ERROR
  kMsgResumeReq    = 0x0008,  // MSG_RESUME_REQ
  kMsgResumeData   = 0x0009,  // MSG_RESUME_DATA

  // B. Java → C++ 控制指令（17 种）
  kCmdPlaceOrder          = 0x0101,  // CMD_PLACE_ORDER
  kCmdConfirmOrder        = 0x0102,  // CMD_CONFIRM_ORDER
  kCmdCancelOrder         = 0x0103,  // CMD_CANCEL_ORDER
  kCmdLoadRiskParams      = 0x0111,  // CMD_LOAD_RISK_PARAMS
  kCmdUpdateWhitelist     = 0x0112,  // CMD_UPDATE_WHITELIST
  kCmdUpdateQuota         = 0x0113,  // CMD_UPDATE_QUOTA
  kCmdUpdatePositionLimit = 0x0114,  // CMD_UPDATE_POSITION_LIMIT
  kCmdUpdateRiskThreshold = 0x0115,  // CMD_UPDATE_RISK_THRESHOLD
  kCmdUpdatePermission    = 0x0116,  // CMD_UPDATE_PERMISSION
  kCmdUpdateExposure      = 0x0117,  // CMD_UPDATE_EXPOSURE
  kCmdSetTradingSuspend   = 0x0118,  // CMD_SET_TRADING_SUSPEND
  kCmdSetReadyState       = 0x0119,  // CMD_SET_READY_STATE
  kCmdReload              = 0x011A,  // CMD_RELOAD
  kCmdTriggerReplay       = 0x011B,  // CMD_TRIGGER_REPLAY
  kCmdQueryStatus         = 0x011C,  // CMD_QUERY_STATUS
  kCmdFundTransfer        = 0x011D,  // CMD_FUND_TRANSFER
  kCmdQueryFundResult     = 0x011E,  // CMD_QUERY_FUND_RESULT

  // C. C++ → Java 事件流（14 种）
  kEvtOrderAccepted     = 0x0201,  // EVT_ORDER_ACCEPTED
  kEvtPreTradeReject    = 0x0202,  // EVT_PRE_TRADE_REJECT
  kEvtOrderReport       = 0x0203,  // EVT_ORDER_REPORT
  kEvtTradeFill         = 0x0204,  // EVT_TRADE_FILL
  kEvtPositionChange    = 0x0205,  // EVT_POSITION_CHANGE
  kEvtOrderCancelResult = 0x0206,  // EVT_ORDER_CANCEL_RESULT
  kEvtOrderExpired      = 0x0207,  // EVT_ORDER_EXPIRED
  kEvtCommandFlow       = 0x0208,  // EVT_COMMAND_FLOW
  kEvtReplayProgress    = 0x0209,  // EVT_REPLAY_PROGRESS
  kEvtSystemState       = 0x020A,  // EVT_SYSTEM_STATE
  kEvtFundReceipt       = 0x020B,  // EVT_FUND_RECEIPT
  kEvtMarketQuote       = 0x020C,  // EVT_MARKET_QUOTE
  kEvtCtpState          = 0x020D,  // EVT_CTP_STATE
  kEvtTradingAlert      = 0x020E   // EVT_TRADING_ALERT
};

const std::size_t kMessageTypeCount = 40u;

// 是否为 §4.2.3 清单内的消息类型（否则 = `ERR_UNKNOWN_TYPE` 0x0105）
bool is_known_message_type(std::uint16_t type);
const char* to_string(MessageType type);

// -----------------------------------------------------------------------------
// §4.3.2 组件错误码（共 62 个；分段见 §4.3.2 `:1166`）
//   注：§4.3.2 标题行（`:1097`）文字作「共 50 个」，但同节统计表（`:1178`）与
//       实际表体（`:1103`–`:1164`）均为 **62**，且 `:1182` 明示 50/59 系笔误。
//       本文件以 **62** 为准（与冻结口径一致）。
// -----------------------------------------------------------------------------
enum class ErrorCode : std::uint32_t {
  kOk                          = 0x0000,  // OK
  // 协议（0x0101–0x0107）
  kErrVersionMismatch          = 0x0101,  // ERR_VERSION_MISMATCH
  kErrBadMagic                 = 0x0102,  // ERR_BAD_MAGIC
  kErrCrcMismatch              = 0x0103,  // ERR_CRC_MISMATCH
  kErrPayloadInvalid           = 0x0104,  // ERR_PAYLOAD_INVALID
  kErrUnknownType              = 0x0105,  // ERR_UNKNOWN_TYPE
  kErrSeqOutOfOrder            = 0x0106,  // ERR_SEQ_OUT_OF_ORDER
  kErrNotHandshaken            = 0x0107,  // ERR_NOT_HANDSHAKEN
  // 鉴权（0x0201–0x0204）
  kErrAuthFailed               = 0x0201,  // ERR_AUTH_FAILED
  kErrAuthTokenMissing         = 0x0202,  // ERR_AUTH_TOKEN_MISSING
  kErrAuthTokenVersion         = 0x0203,  // ERR_AUTH_TOKEN_VERSION
  kErrAuthReplay               = 0x0204,  // ERR_AUTH_REPLAY
  // 会话与链路（0x0301–0x0308）
  kErrHeartbeatTimeout         = 0x0301,  // ERR_HEARTBEAT_TIMEOUT
  kErrDuplicateConnection      = 0x0302,  // ERR_DUPLICATE_CONNECTION
  kErrReconnectLimit           = 0x0303,  // ERR_RECONNECT_LIMIT
  kErrBackpressure             = 0x0304,  // ERR_BACKPRESSURE
  kErrOperatorMismatch         = 0x0305,  // ERR_OPERATOR_MISMATCH
  kErrResumeGapTooLarge        = 0x0306,  // ERR_RESUME_GAP_TOO_LARGE
  kErrConfirmTokenInvalid      = 0x0307,  // ERR_CONFIRM_TOKEN_INVALID
  kErrFrameTooLarge            = 0x0308,  // ERR_FRAME_TOO_LARGE
  // 就绪状态（0x0401–0x0405）
  kErrSystemNotReady           = 0x0401,  // ERR_SYSTEM_NOT_READY
  kErrParamLoadIncomplete      = 0x0402,  // ERR_PARAM_LOAD_INCOMPLETE
  kErrParamVersionStale        = 0x0403,  // ERR_PARAM_VERSION_STALE
  kErrStopping                 = 0x0404,  // ERR_STOPPING
  kErrReplayMismatch           = 0x0405,  // ERR_REPLAY_MISMATCH
  // 事前风控（0x1001–0x1019）
  kRiskProductNotWhitelisted       = 0x1001,  // RISK_PRODUCT_NOT_WHITELISTED
  kRiskInstrumentProductMismatch   = 0x1002,  // RISK_INSTRUMENT_PRODUCT_MISMATCH
  kRiskInstrumentNotDomesticExch   = 0x1003,  // RISK_INSTRUMENT_NOT_DOMESTIC_EXCHANGE
  kRiskSubProductQuotaInsufficient = 0x1004,  // RISK_SUB_PRODUCT_QUOTA_INSUFFICIENT
  kRiskAcctProductQuotaInsufficient= 0x1005,  // RISK_ACCOUNT_PRODUCT_QUOTA_INSUFFICIENT
  kRiskPositionLimitExceeded       = 0x1006,  // RISK_POSITION_LIMIT_EXCEEDED
  kRiskScaleRedlineCommodity       = 0x1007,  // RISK_SCALE_REDLINE_COMMODITY
  kRiskScaleRedlineTrade           = 0x1008,  // RISK_SCALE_REDLINE_TRADE
  kRiskNetPositionOverExposure     = 0x1009,  // RISK_NET_POSITION_OVER_EXPOSURE
  kRiskTermRedlineExceeded         = 0x100A,  // RISK_TERM_REDLINE_EXCEEDED
  kRiskNoTradingAuthorization      = 0x100B,  // RISK_NO_TRADING_AUTHORIZATION
  kRiskProductNotAuthorized        = 0x100C,  // RISK_PRODUCT_NOT_AUTHORIZED
  kRiskAuthorizedQuotaExceeded     = 0x100D,  // RISK_AUTHORIZED_QUOTA_EXCEEDED
  kRiskPositionConflict            = 0x100E,  // RISK_POSITION_CONFLICT
  kRiskEnterpriseLeaderForbidden   = 0x100F,  // RISK_ENTERPRISE_LEADER_FORBIDDEN
  kRiskPriceDeviationBlock         = 0x1010,  // RISK_PRICE_DEVIATION_BLOCK
  kRiskTradingSuspended            = 0x1011,  // RISK_TRADING_SUSPENDED
  kRiskCloseVolumeExceeded         = 0x1012,  // RISK_CLOSE_VOLUME_EXCEEDED
  kRiskPlanFrozen                  = 0x1013,  // RISK_PLAN_FROZEN
  kRiskOrderPlanMismatch           = 0x1014,  // RISK_ORDER_PLAN_MISMATCH
  kRiskMarketPriceNotAvailable     = 0x1015,  // RISK_MARKET_PRICE_NOT_AVAILABLE
  kRiskMarketOrderDeviationNa      = 0x1016,  // RISK_MARKET_ORDER_DEVIATION_NA
  kRiskSubInstrumentQuotaInsufficient = 0x1017,  // RISK_SUB_INSTRUMENT_QUOTA_INSUFFICIENT
  kRiskPriceDeviationWarn          = 0x1018,  // RISK_PRICE_DEVIATION_WARN
  kRiskRelatedObjectSubMismatch    = 0x1019,  // RISK_RELATED_OBJECT_SUB_MISMATCH
  // 指令与 CTP（0x2001–0x2009）
  kOrderNotFound               = 0x2001,  // ORDER_NOT_FOUND
  kOrderStateNotAllowed        = 0x2002,  // ORDER_STATE_NOT_ALLOWED
  kOrderConfirmTimeout         = 0x2003,  // ORDER_CONFIRM_TIMEOUT
  kCtpNotConnected             = 0x2004,  // CTP_NOT_CONNECTED
  kCtpOrderRejected            = 0x2005,  // CTP_ORDER_REJECTED
  kCancelNoVolume              = 0x2006,  // CANCEL_NO_VOLUME
  kOrderDuplicate              = 0x2007,  // ORDER_DUPLICATE
  kFundTransferDuplicate       = 0x2008,  // FUND_TRANSFER_DUPLICATE
  kFundInsufficientAvailable   = 0x2009,  // FUND_INSUFFICIENT_AVAILABLE
  // 组件内部（0x3001–0x3003）
  kFlowPersistFailed           = 0x3001,  // FLOW_PERSIST_FAILED
  kMemoryStateCorrupted        = 0x3002,  // MEMORY_STATE_CORRUPTED
  kUnsupportedOperation        = 0x3003   // UNSUPPORTED_OPERATION
};

const std::size_t kErrorCodeCount = 62u;

const char* to_string(ErrorCode code);

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_PROTOCOL_H
