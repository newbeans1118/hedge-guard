// payload 编解码实现 —— 基于 `proto/hgcp.proto`（CH-013 / §4.2.8）生成代码。
#include "hg/hgcp/payload_codec.h"

namespace hg {
namespace hgcp {

// protoc 生成命名空间别名（proto `package hgcp.v1` ⇒ C++ `::hgcp::v1`，
// **非**本模块的 `hg::hgcp`）—— 便于在本命名空间内短引用。
namespace pbv1 = ::hgcp::v1;

namespace {

// body 能否按 message M 反序列化（空 body = proto3 全默认值 ⇒ 合法）。
// 注：§4.2.8 的「必填性」为**接收侧语义校验**（§4.2.5 / §4.2.8.1），
//     本方法只做**线格式可解析性**判定，不代接收侧做业务必填校验。
template <typename M>
bool parses(const std::vector<std::uint8_t>& body) {
  M m;
  if (body.empty()) {
    return true;
  }
  return m.ParseFromArray(&body[0], static_cast<int>(body.size()));
}

}  // namespace

PayloadCodec::~PayloadCodec() {}

bool PbPayloadCodec::validate(std::uint16_t type,
                              const std::vector<std::uint8_t>& body) const {
  switch (type) {
    // A. 会话与元协议（9）
    case pbv1::MSG_HELLO:         return parses<pbv1::MsgHello>(body);
    case pbv1::MSG_HELLO_ACK:     return parses<pbv1::MsgHelloAck>(body);
    case pbv1::MSG_AUTH_FAIL:     return parses<pbv1::MsgAuthFail>(body);
    case pbv1::MSG_HEARTBEAT:     return parses<pbv1::MsgHeartbeat>(body);
    case pbv1::MSG_HEARTBEAT_ACK: return parses<pbv1::MsgHeartbeatAck>(body);
    case pbv1::MSG_ACK:           return parses<pbv1::MsgAck>(body);
    case pbv1::MSG_ERROR:         return parses<pbv1::MsgError>(body);
    case pbv1::MSG_RESUME_REQ:    return parses<pbv1::MsgResumeReq>(body);
    case pbv1::MSG_RESUME_DATA:   return parses<pbv1::MsgResumeData>(body);
    // B. 控制指令（17）
    case pbv1::CMD_PLACE_ORDER:           return parses<pbv1::CmdPlaceOrder>(body);
    case pbv1::CMD_CONFIRM_ORDER:         return parses<pbv1::CmdConfirmOrder>(body);
    case pbv1::CMD_CANCEL_ORDER:          return parses<pbv1::CmdCancelOrder>(body);
    case pbv1::CMD_LOAD_RISK_PARAMS:      return parses<pbv1::CmdLoadRiskParams>(body);
    case pbv1::CMD_UPDATE_WHITELIST:      return parses<pbv1::CmdUpdateWhitelist>(body);
    case pbv1::CMD_UPDATE_QUOTA:          return parses<pbv1::CmdUpdateQuota>(body);
    case pbv1::CMD_UPDATE_POSITION_LIMIT: return parses<pbv1::CmdUpdatePositionLimit>(body);
    case pbv1::CMD_UPDATE_RISK_THRESHOLD: return parses<pbv1::CmdUpdateRiskThreshold>(body);
    case pbv1::CMD_UPDATE_PERMISSION:     return parses<pbv1::CmdUpdatePermission>(body);
    case pbv1::CMD_UPDATE_EXPOSURE:       return parses<pbv1::CmdUpdateExposure>(body);
    case pbv1::CMD_SET_TRADING_SUSPEND:   return parses<pbv1::CmdSetTradingSuspend>(body);
    case pbv1::CMD_SET_READY_STATE:       return parses<pbv1::CmdSetReadyState>(body);
    case pbv1::CMD_RELOAD:                return parses<pbv1::CmdReload>(body);
    case pbv1::CMD_TRIGGER_REPLAY:        return parses<pbv1::CmdTriggerReplay>(body);
    case pbv1::CMD_QUERY_STATUS:          return parses<pbv1::CmdQueryStatus>(body);
    case pbv1::CMD_FUND_TRANSFER:         return parses<pbv1::CmdFundTransfer>(body);
    case pbv1::CMD_QUERY_FUND_RESULT:     return parses<pbv1::CmdQueryFundResult>(body);
    // C. 事件流（14）
    case pbv1::EVT_ORDER_ACCEPTED:      return parses<pbv1::EvtOrderAccepted>(body);
    case pbv1::EVT_PRE_TRADE_REJECT:    return parses<pbv1::EvtPreTradeReject>(body);
    case pbv1::EVT_ORDER_REPORT:        return parses<pbv1::EvtOrderReport>(body);
    case pbv1::EVT_TRADE_FILL:          return parses<pbv1::EvtTradeFill>(body);
    case pbv1::EVT_POSITION_CHANGE:     return parses<pbv1::EvtPositionChange>(body);
    case pbv1::EVT_ORDER_CANCEL_RESULT: return parses<pbv1::EvtOrderCancelResult>(body);
    case pbv1::EVT_ORDER_EXPIRED:       return parses<pbv1::EvtOrderExpired>(body);
    case pbv1::EVT_COMMAND_FLOW:        return parses<pbv1::EvtCommandFlow>(body);
    case pbv1::EVT_REPLAY_PROGRESS:     return parses<pbv1::EvtReplayProgress>(body);
    case pbv1::EVT_SYSTEM_STATE:        return parses<pbv1::EvtSystemState>(body);
    case pbv1::EVT_FUND_RECEIPT:        return parses<pbv1::EvtFundReceipt>(body);
    case pbv1::EVT_MARKET_QUOTE:        return parses<pbv1::EvtMarketQuote>(body);
    case pbv1::EVT_CTP_STATE:           return parses<pbv1::EvtCtpState>(body);
    case pbv1::EVT_TRADING_ALERT:       return parses<pbv1::EvtTradingAlert>(body);
    default:
      return false;  // 未知类型（§4.3.2 0x0105，由 is_known_message_type 判）
  }
}

const char* PbPayloadCodec::name() const { return "protobuf"; }

}  // namespace hgcp
}  // namespace hg
