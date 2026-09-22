// `TransportPolicy` 的 protobuf 实现（CH-013 / §4.2.8）。
#include "hg/hgcp/pb_transport_policy.h"

namespace hg {
namespace hgcp {

// protoc 生成命名空间别名（proto `package hgcp.v1` ⇒ C++ `::hgcp::v1`）
namespace pbv1 = ::hgcp::v1;

namespace {

std::vector<std::uint8_t> empty() { return std::vector<std::uint8_t>(); }

// `MsgError{err_code}` —— §4.3.2 组件错误码。
std::vector<std::uint8_t> encode_error(std::uint32_t err_code) {
  pbv1::MsgError m;
  m.set_err_code(err_code);
  std::vector<std::uint8_t> body;
  if (!PbPayloadCodec::encode(m, &body)) {
    return empty();
  }
  return body;
}

}  // namespace

PbTransportPolicy::PbTransportPolicy() {}

std::vector<std::uint8_t> PbTransportPolicy::heartbeat_body(
    std::int64_t now_ms, std::uint64_t last_received_seq) const {
  pbv1::MsgHeartbeat m;
  m.set_sent_ts_ms(now_ms);
  m.set_last_received_seq(last_received_seq);
  m.set_component_state(pbv1::COMPONENT_STATE_READY);  // §4.2.8.8 `I-4`：独立 5 值枚举
  std::vector<std::uint8_t> body;
  if (!PbPayloadCodec::encode(m, &body)) {
    return empty();
  }
  return body;
}

std::vector<std::uint8_t> PbTransportPolicy::duplicate_connection_body() const {
  return encode_error(0x0302u);  // ERR_DUPLICATE_CONNECTION
}

std::vector<std::uint8_t> PbTransportPolicy::heartbeat_timeout_body() const {
  return encode_error(0x0301u);  // ERR_HEARTBEAT_TIMEOUT
}

std::vector<std::uint8_t> PbTransportPolicy::frame_too_large_body() const {
  // §4.2.8.8 `I-2`（行为面裁定）：帧超限 ⇒ 尽力回 `0x0308`；
  //   「提示使用分片」经 `err_msg` 承载（不新增字段）。
  pbv1::MsgError m;
  m.set_err_code(0x0308u);  // ERR_FRAME_TOO_LARGE
  m.set_err_msg("frame too large (LEN > 1 MiB); use fragmentation (ARCHITECTURE.md 4.2.2)");
  std::vector<std::uint8_t> body;
  if (!PbPayloadCodec::encode(m, &body)) {
    return empty();
  }
  return body;
}

std::vector<std::uint8_t> PbTransportPolicy::error_body(std::uint32_t err_code) const {
  return encode_error(err_code);
}

}  // namespace hgcp
}  // namespace hg
