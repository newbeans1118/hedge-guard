// HGCP 协议常量表实现（转录自 docs/ARCHITECTURE.md §4.2.3 / §4.3.2）。
#include "hg/hgcp/protocol.h"

namespace hg {
namespace hgcp {

bool is_known_message_type(std::uint16_t type) {
  switch (type) {
    case 0x0001: case 0x0002: case 0x0003: case 0x0004: case 0x0005:
    case 0x0006: case 0x0007: case 0x0008: case 0x0009:
    case 0x0101: case 0x0102: case 0x0103:
    case 0x0111: case 0x0112: case 0x0113: case 0x0114: case 0x0115:
    case 0x0116: case 0x0117: case 0x0118: case 0x0119: case 0x011A:
    case 0x011B: case 0x011C: case 0x011D: case 0x011E:
    case 0x0201: case 0x0202: case 0x0203: case 0x0204: case 0x0205:
    case 0x0206: case 0x0207: case 0x0208: case 0x0209: case 0x020A:
    case 0x020B: case 0x020C: case 0x020D: case 0x020E:
      return true;
    default:
      return false;
  }
}

const char* to_string(MessageType type) {
  switch (type) {
    case MessageType::kMsgHello: return "MSG_HELLO";
    case MessageType::kMsgHelloAck: return "MSG_HELLO_ACK";
    case MessageType::kMsgAuthFail: return "MSG_AUTH_FAIL";
    case MessageType::kMsgHeartbeat: return "MSG_HEARTBEAT";
    case MessageType::kMsgHeartbeatAck: return "MSG_HEARTBEAT_ACK";
    case MessageType::kMsgAck: return "MSG_ACK";
    case MessageType::kMsgError: return "MSG_ERROR";
    case MessageType::kMsgResumeReq: return "MSG_RESUME_REQ";
    case MessageType::kMsgResumeData: return "MSG_RESUME_DATA";
    case MessageType::kCmdPlaceOrder: return "CMD_PLACE_ORDER";
    case MessageType::kCmdConfirmOrder: return "CMD_CONFIRM_ORDER";
    case MessageType::kCmdCancelOrder: return "CMD_CANCEL_ORDER";
    case MessageType::kCmdLoadRiskParams: return "CMD_LOAD_RISK_PARAMS";
    case MessageType::kCmdUpdateWhitelist: return "CMD_UPDATE_WHITELIST";
    case MessageType::kCmdUpdateQuota: return "CMD_UPDATE_QUOTA";
    case MessageType::kCmdUpdatePositionLimit: return "CMD_UPDATE_POSITION_LIMIT";
    case MessageType::kCmdUpdateRiskThreshold: return "CMD_UPDATE_RISK_THRESHOLD";
    case MessageType::kCmdUpdatePermission: return "CMD_UPDATE_PERMISSION";
    case MessageType::kCmdUpdateExposure: return "CMD_UPDATE_EXPOSURE";
    case MessageType::kCmdSetTradingSuspend: return "CMD_SET_TRADING_SUSPEND";
    case MessageType::kCmdSetReadyState: return "CMD_SET_READY_STATE";
    case MessageType::kCmdReload: return "CMD_RELOAD";
    case MessageType::kCmdTriggerReplay: return "CMD_TRIGGER_REPLAY";
    case MessageType::kCmdQueryStatus: return "CMD_QUERY_STATUS";
    case MessageType::kCmdFundTransfer: return "CMD_FUND_TRANSFER";
    case MessageType::kCmdQueryFundResult: return "CMD_QUERY_FUND_RESULT";
    case MessageType::kEvtOrderAccepted: return "EVT_ORDER_ACCEPTED";
    case MessageType::kEvtPreTradeReject: return "EVT_PRE_TRADE_REJECT";
    case MessageType::kEvtOrderReport: return "EVT_ORDER_REPORT";
    case MessageType::kEvtTradeFill: return "EVT_TRADE_FILL";
    case MessageType::kEvtPositionChange: return "EVT_POSITION_CHANGE";
    case MessageType::kEvtOrderCancelResult: return "EVT_ORDER_CANCEL_RESULT";
    case MessageType::kEvtOrderExpired: return "EVT_ORDER_EXPIRED";
    case MessageType::kEvtCommandFlow: return "EVT_COMMAND_FLOW";
    case MessageType::kEvtReplayProgress: return "EVT_REPLAY_PROGRESS";
    case MessageType::kEvtSystemState: return "EVT_SYSTEM_STATE";
    case MessageType::kEvtFundReceipt: return "EVT_FUND_RECEIPT";
    case MessageType::kEvtMarketQuote: return "EVT_MARKET_QUOTE";
    case MessageType::kEvtCtpState: return "EVT_CTP_STATE";
    case MessageType::kEvtTradingAlert: return "EVT_TRADING_ALERT";
  }
  return "?";
}

const char* to_string(ErrorCode code) {
  switch (code) {
    case ErrorCode::kOk: return "OK";
    case ErrorCode::kErrVersionMismatch: return "ERR_VERSION_MISMATCH";
    case ErrorCode::kErrBadMagic: return "ERR_BAD_MAGIC";
    case ErrorCode::kErrCrcMismatch: return "ERR_CRC_MISMATCH";
    case ErrorCode::kErrPayloadInvalid: return "ERR_PAYLOAD_INVALID";
    case ErrorCode::kErrUnknownType: return "ERR_UNKNOWN_TYPE";
    case ErrorCode::kErrSeqOutOfOrder: return "ERR_SEQ_OUT_OF_ORDER";
    case ErrorCode::kErrNotHandshaken: return "ERR_NOT_HANDSHAKEN";
    case ErrorCode::kErrAuthFailed: return "ERR_AUTH_FAILED";
    case ErrorCode::kErrAuthTokenMissing: return "ERR_AUTH_TOKEN_MISSING";
    case ErrorCode::kErrAuthTokenVersion: return "ERR_AUTH_TOKEN_VERSION";
    case ErrorCode::kErrAuthReplay: return "ERR_AUTH_REPLAY";
    case ErrorCode::kErrHeartbeatTimeout: return "ERR_HEARTBEAT_TIMEOUT";
    case ErrorCode::kErrDuplicateConnection: return "ERR_DUPLICATE_CONNECTION";
    case ErrorCode::kErrReconnectLimit: return "ERR_RECONNECT_LIMIT";
    case ErrorCode::kErrBackpressure: return "ERR_BACKPRESSURE";
    case ErrorCode::kErrOperatorMismatch: return "ERR_OPERATOR_MISMATCH";
    case ErrorCode::kErrResumeGapTooLarge: return "ERR_RESUME_GAP_TOO_LARGE";
    case ErrorCode::kErrConfirmTokenInvalid: return "ERR_CONFIRM_TOKEN_INVALID";
    case ErrorCode::kErrFrameTooLarge: return "ERR_FRAME_TOO_LARGE";
    case ErrorCode::kErrSystemNotReady: return "ERR_SYSTEM_NOT_READY";
    case ErrorCode::kErrParamLoadIncomplete: return "ERR_PARAM_LOAD_INCOMPLETE";
    case ErrorCode::kErrParamVersionStale: return "ERR_PARAM_VERSION_STALE";
    case ErrorCode::kErrStopping: return "ERR_STOPPING";
    case ErrorCode::kErrReplayMismatch: return "ERR_REPLAY_MISMATCH";
    case ErrorCode::kRiskProductNotWhitelisted: return "RISK_PRODUCT_NOT_WHITELISTED";
    case ErrorCode::kRiskInstrumentProductMismatch: return "RISK_INSTRUMENT_PRODUCT_MISMATCH";
    case ErrorCode::kRiskInstrumentNotDomesticExch: return "RISK_INSTRUMENT_NOT_DOMESTIC_EXCHANGE";
    case ErrorCode::kRiskSubProductQuotaInsufficient: return "RISK_SUB_PRODUCT_QUOTA_INSUFFICIENT";
    case ErrorCode::kRiskAcctProductQuotaInsufficient: return "RISK_ACCOUNT_PRODUCT_QUOTA_INSUFFICIENT";
    case ErrorCode::kRiskPositionLimitExceeded: return "RISK_POSITION_LIMIT_EXCEEDED";
    case ErrorCode::kRiskScaleRedlineCommodity: return "RISK_SCALE_REDLINE_COMMODITY";
    case ErrorCode::kRiskScaleRedlineTrade: return "RISK_SCALE_REDLINE_TRADE";
    case ErrorCode::kRiskNetPositionOverExposure: return "RISK_NET_POSITION_OVER_EXPOSURE";
    case ErrorCode::kRiskTermRedlineExceeded: return "RISK_TERM_REDLINE_EXCEEDED";
    case ErrorCode::kRiskNoTradingAuthorization: return "RISK_NO_TRADING_AUTHORIZATION";
    case ErrorCode::kRiskProductNotAuthorized: return "RISK_PRODUCT_NOT_AUTHORIZED";
    case ErrorCode::kRiskAuthorizedQuotaExceeded: return "RISK_AUTHORIZED_QUOTA_EXCEEDED";
    case ErrorCode::kRiskPositionConflict: return "RISK_POSITION_CONFLICT";
    case ErrorCode::kRiskEnterpriseLeaderForbidden: return "RISK_ENTERPRISE_LEADER_FORBIDDEN";
    case ErrorCode::kRiskPriceDeviationBlock: return "RISK_PRICE_DEVIATION_BLOCK";
    case ErrorCode::kRiskTradingSuspended: return "RISK_TRADING_SUSPENDED";
    case ErrorCode::kRiskCloseVolumeExceeded: return "RISK_CLOSE_VOLUME_EXCEEDED";
    case ErrorCode::kRiskPlanFrozen: return "RISK_PLAN_FROZEN";
    case ErrorCode::kRiskOrderPlanMismatch: return "RISK_ORDER_PLAN_MISMATCH";
    case ErrorCode::kRiskMarketPriceNotAvailable: return "RISK_MARKET_PRICE_NOT_AVAILABLE";
    case ErrorCode::kRiskMarketOrderDeviationNa: return "RISK_MARKET_ORDER_DEVIATION_NA";
    case ErrorCode::kRiskSubInstrumentQuotaInsufficient: return "RISK_SUB_INSTRUMENT_QUOTA_INSUFFICIENT";
    case ErrorCode::kRiskPriceDeviationWarn: return "RISK_PRICE_DEVIATION_WARN";
    case ErrorCode::kRiskRelatedObjectSubMismatch: return "RISK_RELATED_OBJECT_SUB_MISMATCH";
    case ErrorCode::kOrderNotFound: return "ORDER_NOT_FOUND";
    case ErrorCode::kOrderStateNotAllowed: return "ORDER_STATE_NOT_ALLOWED";
    case ErrorCode::kOrderConfirmTimeout: return "ORDER_CONFIRM_TIMEOUT";
    case ErrorCode::kCtpNotConnected: return "CTP_NOT_CONNECTED";
    case ErrorCode::kCtpOrderRejected: return "CTP_ORDER_REJECTED";
    case ErrorCode::kCancelNoVolume: return "CANCEL_NO_VOLUME";
    case ErrorCode::kOrderDuplicate: return "ORDER_DUPLICATE";
    case ErrorCode::kFundTransferDuplicate: return "FUND_TRANSFER_DUPLICATE";
    case ErrorCode::kFundInsufficientAvailable: return "FUND_INSUFFICIENT_AVAILABLE";
    case ErrorCode::kFlowPersistFailed: return "FLOW_PERSIST_FAILED";
    case ErrorCode::kMemoryStateCorrupted: return "MEMORY_STATE_CORRUPTED";
    case ErrorCode::kUnsupportedOperation: return "UNSUPPORTED_OPERATION";
  }
  return "?";
}

}  // namespace hgcp
}  // namespace hg
