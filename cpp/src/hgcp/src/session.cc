// HGCP 连接会话实现（schema-free 承载层）。
#include "hg/hgcp/session.h"

namespace hg {
namespace hgcp {

TransportPolicy::~TransportPolicy() {}

std::vector<std::uint8_t> EmptyTransportPolicy::heartbeat_body(
    std::int64_t /*now_ms*/, std::uint64_t /*last_received_seq*/) const {
  return std::vector<std::uint8_t>();  // 占位：见 pb_transport_policy.h（真实字段编码）
}
std::vector<std::uint8_t> EmptyTransportPolicy::duplicate_connection_body() const {
  return std::vector<std::uint8_t>();
}
std::vector<std::uint8_t> EmptyTransportPolicy::heartbeat_timeout_body() const {
  return std::vector<std::uint8_t>();
}
std::vector<std::uint8_t> EmptyTransportPolicy::frame_too_large_body() const {
  return std::vector<std::uint8_t>();
}
std::vector<std::uint8_t> EmptyTransportPolicy::error_body(
    std::uint32_t /*err_code*/) const {
  return std::vector<std::uint8_t>();
}
std::vector<std::uint8_t> EmptyTransportPolicy::resume_data_body(
    std::uint64_t /*from_seq*/, std::uint64_t /*to_seq*/, bool /*is_last*/,
    const std::vector<std::vector<std::uint8_t> >& /*frames*/) const {
  return std::vector<std::uint8_t>();  // 占位：见 pb_transport_policy.h（真实字段编码）
}
std::string EmptyTransportPolicy::hello_auth_token(
    const std::vector<std::uint8_t>& /*hello_body*/) const {
  return std::string();  // 占位：见 pb_transport_policy.h（真实字段解析）
}
std::uint32_t EmptyTransportPolicy::hello_token_version(
    const std::vector<std::uint8_t>& /*hello_body*/) const {
  return 0u;  // 占位：见 pb_transport_policy.h（真实字段解析）
}
std::string EmptyTransportPolicy::hello_client_boot_id(
    const std::vector<std::uint8_t>& /*hello_body*/) const {
  return std::string();  // 占位：见 pb_transport_policy.h（真实字段解析）
}
std::vector<std::uint8_t> EmptyTransportPolicy::auth_fail_body(
    std::uint32_t /*reason_code*/) const {
  return std::vector<std::uint8_t>();  // 占位：见 pb_transport_policy.h（真实字段编码）
}
bool EmptyTransportPolicy::resume_req_last_seq(
    const std::vector<std::uint8_t>& /*body*/,
    std::uint64_t* /*last_received_seq*/) const {
  return false;  // 占位：见 pb_transport_policy.h（真实字段解析）
}

HgcpSession::HgcpSession()
    : hb_(kHeartbeatIntervalMs, kHeartbeatMissThreshold),
      resume_(kResumeBufferDefaultCapacity),
      last_received_seq_(0),
      last_sent_seq_(0),
      next_msg_id_(0),
      seq_gap_(false),
      seq_out_of_order_(false) {}

void HgcpSession::configure(std::int64_t heartbeat_interval_ms, int heartbeat_miss,
                            std::size_t resume_capacity) {
  hb_ = HeartbeatMonitor(heartbeat_interval_ms, heartbeat_miss);
  resume_ = ResumeBuffer(resume_capacity);
}

void HgcpSession::on_connected(std::int64_t now_ms) {
  hb_.restart(now_ms);
  seq_gap_ = false;
  seq_out_of_order_ = false;
}

void HgcpSession::on_disconnected() {
  conn_.on_closed();
  reader_ = FrameStreamReader();  // 丢弃半包
}

FrameStatus HgcpSession::feed(const std::uint8_t* data, std::size_t n,
                              std::vector<Frame>* inbound) {
  reader_.feed(data, n);
  FrameStatus last = FrameStatus::kNeedMore;
  for (;;) {
    Frame f;
    const FrameStatus st = reader_.next(&f);
    if (st == FrameStatus::kNeedMore) {
      return (inbound != 0 && !inbound->empty()) ? FrameStatus::kOk : last;
    }
    if (st != FrameStatus::kOk) {
      return st;  // 帧内错误（坏 MAGIC/CRC/超限…）⇒ 调用方断连
    }
    note_received_seq(f.header.seq);
    if (inbound != 0) {
      inbound->push_back(f);
    }
    last = FrameStatus::kOk;
  }
}

void HgcpSession::note_received_seq(std::uint64_t seq) {
  // §4.3.2 `0x0106 ERR_SEQ_OUT_OF_ORDER`：触发条件 = 「`SEQ` ≤ 已收最大 **或** 出现缺口」。
  //   两分支**互斥**：回退/重复（≤ 已收最大）与前向跳号（> 已收最大 + 1）分开判定。
  if (last_received_seq_ != 0 && seq <= last_received_seq_) {
    seq_out_of_order_ = true;  // 回退 / 重复 ⇒ 装配层回 `0x0106`（不回退 last_received_seq_）
  } else if (last_received_seq_ != 0 && seq > last_received_seq_ + 1) {
    seq_gap_ = true;  // 前向跳号 ⇒ 触发补数（§4.2.6 `:972`）；既有机制，保持不变
  }
  if (seq > last_received_seq_) {
    last_received_seq_ = seq;
  }
}

FrameStatus HgcpSession::make_outbound(std::uint16_t type, std::uint16_t flags,
                                       const std::vector<std::uint8_t>& body,
                                       Frame* out) {
  const std::uint64_t seq = last_sent_seq_ + 1;
  const std::uint64_t mid = next_msg_id_++;
  std::vector<std::uint8_t> wire;
  const FrameStatus st = make_frame(type, flags, seq, mid, body, &wire);
  if (st != FrameStatus::kOk) {
    return st;
  }
  std::size_t consumed = 0;
  const FrameStatus pst = parse_frame(&wire[0], wire.size(), out, &consumed);
  if (pst != FrameStatus::kOk) {
    return pst;
  }
  resume_.push(seq, wire);  // 出站事件入补数缓冲（供对端补数）
  last_sent_seq_ = seq;
  return FrameStatus::kOk;
}

bool HgcpSession::on_tick(std::int64_t now_ms, const TransportPolicy& policy,
                          std::vector<Frame>* outbound) {
  if (hb_.timed_out(now_ms)) {
    return false;  // 连续 3 次未收 ACK ⇒ 判死（0x0301）
  }
  if (!hb_.heartbeat_due(now_ms)) {
    return true;
  }
  Frame hb;
  const std::vector<std::uint8_t> body = policy.heartbeat_body(now_ms, last_received_seq_);
  if (make_outbound(static_cast<std::uint16_t>(MessageType::kMsgHeartbeat), 0u, body,
                    &hb) == FrameStatus::kOk) {
    hb_.mark_sent(now_ms);
    if (outbound != 0) {
      outbound->push_back(hb);
    }
  }
  return true;
}

void HgcpSession::on_heartbeat_ack(std::int64_t now_ms) { hb_.on_ack(now_ms); }

bool HgcpSession::heartbeat_timed_out(std::int64_t now_ms) const {
  return hb_.timed_out(now_ms);
}
int HgcpSession::heartbeat_missed(std::int64_t now_ms) const { return hb_.missed(now_ms); }

ReplayStatus HgcpSession::build_resume(
    std::uint64_t from_seq,
    std::vector<std::vector<BufferedFrame> >* batches) const {
  return resume_.replay_from(from_seq, kResumeMaxBatchFrames, batches);
}

ConnectionDecision HgcpSession::on_new_connection() { return conn_.on_new_connection(); }

void HgcpSession::on_closed() { on_disconnected(); }

}  // namespace hgcp
}  // namespace hg
