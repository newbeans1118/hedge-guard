// HGCP 事件流产出与保序补数实现（T-039）。
#include "hg/hgcp/event_emitter.h"

#include "hg/risk_state/risk_snapshot.h"

namespace pbv1 = ::hgcp::v1;

namespace hg {
namespace hgcp {

namespace {

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }

// risk_state 定点数量（scale = 1e4，kQtyScale）→ int32 整数单位。
std::int32_t qty_whole(hg::risk_state::Fixed v) {
  const std::int64_t w = v / hg::risk_state::kQtyScale;
  return static_cast<std::int32_t>(w);
}

}  // namespace

// ============================ EventEmitter ==================================

EventEmitter::EventEmitter(HgcpServerLoop* loop)
    : loop_(loop),
      rebuilder_(0),
      queue_(),
      head_(0),
      alert_fn_(0),
      alert_user_(0),
      backpressure_count_(0),
      quotes_dropped_(0),
      flushed_count_(0),
      rebuild_count_(0),
      gap_count_(0) {}

void EventEmitter::attach() {
  if (loop_ != 0) {
    loop_->set_session_ready_sink(this);
    loop_->set_resume_gap_sink(this);
  }
}

void EventEmitter::detach() {
  if (loop_ != 0) {
    loop_->set_session_ready_sink(0);
    loop_->set_resume_gap_sink(0);
  }
}

EmitStatus EventEmitter::emit(std::uint16_t type,
                              const std::vector<std::uint8_t>& body) {
  if (type == mt(MessageType::kEvtMarketQuote)) {
    // 守卫：可靠出口误投行情 ⇒ 按可丢例外处置（不保留、不背压，CH-006）。
    return emit_quote(body);
  }
  return emit_reliable(type, body);
}

EmitStatus EventEmitter::emit_quote(const std::vector<std::uint8_t>& body) {
  if (loop_ == 0) {
    return EmitStatus::kNoLoop;
  }
  if (loop_->has_connection() &&
      loop_->send_frame(mt(MessageType::kEvtMarketQuote), 0u, body)) {
    return EmitStatus::kSent;
  }
  // 断连（或发送失败即已断连）⇒ 丢弃：不保留、不背压（§4.2.6「事件流缓冲」行例外）。
  ++quotes_dropped_;
  return EmitStatus::kDroppedQuote;
}

EmitStatus EventEmitter::emit_reliable(std::uint16_t type,
                                       const std::vector<std::uint8_t>& body) {
  if (loop_ == 0) {
    return EmitStatus::kNoLoop;
  }
  if (loop_->has_connection()) {
    if (loop_->send_frame(type, 0u, body)) {
      return EmitStatus::kSent;
    }
    // send_frame 失败 ⇒ 对端已断（send_frame_obj 内已关连接）⇒ 转保留。
  }
  if (queue_.size() - head_ >= kEventQueueCapacity) {
    // 保留队列满 ⇒ 背压 + 告警（§2.4.3「告警 + 背压 T4」）：事件**不丢**、
    // 留在生产者（T4 载体）侧重试。本装配 T4/T6 同泵线程 ⇒ 不用阻塞原语（防自锁）。
    ++backpressure_count_;
    notify_alert("event queue full: backpressure (reliable event retained by producer)");
    return EmitStatus::kBackpressured;
  }
  queue_.push_back(Pending(type, body));
  return EmitStatus::kRetained;
}

void EventEmitter::on_session_ready() {
  if (loop_ == 0) {
    return;
  }
  // 握手完成 ⇒ 保留队列按序补发（FIFO）。发送中途断连 ⇒ 停止、剩余留队
  //（下次握手完成继续）。补发帧占用新通道 SEQ，先于任何 RESUME_DATA 上线。
  while (head_ < queue_.size()) {
    const Pending& p = queue_[head_];
    if (!loop_->send_frame(p.type, 0u, p.body)) {
      break;
    }
    ++head_;
    ++flushed_count_;
  }
  if (head_ == queue_.size() && !queue_.empty()) {
    queue_.clear();
    head_ = 0;
  }
}

void EventEmitter::on_resume_gap() {
  ++gap_count_;
  if (rebuilder_ != 0) {
    rebuilder_->rebuild(*this);
    ++rebuild_count_;
  }
}

EmitStatus EventEmitter::emit_encoded(std::uint16_t type,
                                      const google::protobuf::MessageLite& msg,
                                      bool droppable) {
  std::vector<std::uint8_t> body;
  if (!PbPayloadCodec::encode(msg, &body)) {
    return droppable ? EmitStatus::kDroppedQuote : EmitStatus::kBackpressured;
  }
  return droppable ? emit_quote(body) : emit(type, body);
}

EmitStatus EventEmitter::emit_position_change(const pbv1::EvtPositionChange& e) {
  return emit_encoded(mt(MessageType::kEvtPositionChange), e, false);
}
EmitStatus EventEmitter::emit_command_flow(const pbv1::EvtCommandFlow& e) {
  return emit_encoded(mt(MessageType::kEvtCommandFlow), e, false);
}
EmitStatus EventEmitter::emit_fund_receipt(const pbv1::EvtFundReceipt& e) {
  return emit_encoded(mt(MessageType::kEvtFundReceipt), e, false);
}
EmitStatus EventEmitter::emit_market_quote(const pbv1::EvtMarketQuote& e) {
  return emit_encoded(mt(MessageType::kEvtMarketQuote), e, true);
}
EmitStatus EventEmitter::emit_ctp_state(const pbv1::EvtCtpState& e) {
  return emit_encoded(mt(MessageType::kEvtCtpState), e, false);
}
EmitStatus EventEmitter::emit_trading_alert(const pbv1::EvtTradingAlert& e) {
  return emit_encoded(mt(MessageType::kEvtTradingAlert), e, false);
}

void EventEmitter::notify_alert(const char* what) {
  if (alert_fn_ != 0) {
    alert_fn_(alert_user_, what);
  }
}

// ======================== RiskStateFullRebuilder ============================

FullStateRebuilder::~FullStateRebuilder() {}

RiskStateFullRebuilder::RiskStateFullRebuilder(const hg::risk_state::RiskState* state)
    : state_(state), now_ms_(0) {}

void RiskStateFullRebuilder::rebuild(EventEmitter& sink) {
  if (state_ == 0) {
    return;
  }
  // `positions()` 仅非 const 访问器 ⇒ 经 `clone()` 取可读副本（其文档明示
  // 「仅重建线程调用（允许堆分配）」⇒ 恢复路径堆分配有据；本类取得所有权并释放）。
  hg::risk_state::RiskSnapshot* snap = state_->snapshot().clone();
  if (snap == 0) {
    return;
  }
  // EVT_POSITION_CHANGE 全量（§4.2.6「补数不可达」行）。持仓键 =
  // packKey3(sub, (dir<<4)|type, instrument)：sub 高 16、dt 中 16、instrument 低 32。
  typedef std::unordered_map<std::uint64_t, hg::risk_state::PositionEntry> PosMap;
  const PosMap& positions = snap->positions();
  for (PosMap::const_iterator it = positions.begin(); it != positions.end(); ++it) {
    const std::uint32_t sub =
        static_cast<std::uint32_t>((it->first >> 48) & 0xFFFFu);
    const std::uint32_t dt =
        static_cast<std::uint32_t>((it->first >> 32) & 0xFFFFu);
    const std::uint32_t instrument =
        static_cast<std::uint32_t>(it->first & 0xFFFFFFFFu);
    const hg::risk_state::PositionDirection dir =
        static_cast<hg::risk_state::PositionDirection>(dt >> 4);
    const hg::risk_state::PositionType ptype =
        static_cast<hg::risk_state::PositionType>(dt & 0xFu);
    pbv1::EvtPositionChange e;
    e.set_sub_account_id(sub);
    // instrument_id 协议边界为字符串（InstrumentMeta.instrument_code）。
    const hg::risk_state::InstrumentMeta* meta = snap->findInstrument(instrument);
    e.set_instrument_id(meta != 0 ? meta->instrument_code : std::string());
    e.set_direction(dir == hg::risk_state::PositionDirection::kLong
                        ? pbv1::DIRECTION_BUY
                        : pbv1::DIRECTION_SELL);
    e.set_position_type(ptype == hg::risk_state::PositionType::kToday
                            ? pbv1::POSITION_TYPE_TODAY
                            : pbv1::POSITION_TYPE_YESTERDAY);
    e.set_position_qty(qty_whole(it->second.position_qty));
    e.set_available_qty(qty_whole(it->second.available_qty));
    e.set_frozen_qty(qty_whole(it->second.frozen_qty));
    e.set_as_of_ts_ms(now_ms_);
    sink.emit_position_change(e);
  }
  delete snap;
  // EVT_SYSTEM_STATE（当前 param_version；§4.2.6「EVT_SYSTEM_STATE（新 param_version）」）。
  pbv1::EvtSystemState s;
  s.set_state(pbv1::SERVER_STATE_READY);
  s.set_param_version_applied(state_->param_version());
  s.set_ts_ms(now_ms_);
  std::vector<std::uint8_t> body;
  if (PbPayloadCodec::encode(s, &body)) {
    sink.emit(mt(MessageType::kEvtSystemState), body);
  }
}

}  // namespace hgcp
}  // namespace hg
