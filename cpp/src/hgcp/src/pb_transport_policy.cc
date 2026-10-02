// `TransportPolicy` 的 protobuf 实现（CH-013 / §4.2.8）。
#include "hg/hgcp/pb_transport_policy.h"

#include <string>

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

std::string PbTransportPolicy::hello_auth_token(
    const std::vector<std::uint8_t>& hello_body) const {
  pbv1::MsgHello m;
  if (!PbPayloadCodec::decode(hello_body, &m)) {
    return std::string();  // 不可解析 ⇒ 空串（由鉴权判 kMissingToken 拒绝）
  }
  return m.auth_token();
}

std::vector<std::uint8_t> PbTransportPolicy::auth_fail_body(
    std::uint32_t reason_code) const {
  // §4.2.8.2 `MsgAuthFail{reason_code(必), detail(可)}`；reason_code = §4.3.2 0x02xx。
  // detail 用**固定文案**：不得含令牌本体/长度/片段（NFR-SEC-1 / §4.2.6）。
  pbv1::MsgAuthFail m;
  m.set_reason_code(reason_code);
  m.set_detail("authentication failed; connection closed (FR-CONFIG-9 acceptance 4)");
  std::vector<std::uint8_t> body;
  if (!PbPayloadCodec::encode(m, &body)) {
    return empty();
  }
  return body;
}

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

std::vector<std::uint8_t> PbTransportPolicy::resume_data_body(
    std::uint64_t from_seq, std::uint64_t to_seq, bool is_last,
    const std::vector<std::vector<std::uint8_t> >& frames) const {
  // `MsgResumeData`（§4.2.8.2）：`from_seq`/`to_seq`/`is_last` + `repeated bytes frames`。
  //   `frames[k]` = 本批第 k 帧的**原始帧字节**（逐字节回放，线路侧不得过滤，§4.2.6）。
  pbv1::MsgResumeData m;
  m.set_from_seq(from_seq);
  m.set_to_seq(to_seq);
  m.set_is_last(is_last);
  for (std::size_t k = 0; k < frames.size(); ++k) {
    // 空帧合法（proto `bytes` 允许零长度）⇒ 显式构造空串，避免以空迭代器构串。
    const std::string bytes = frames[k].empty()
                                  ? std::string()
                                  : std::string(frames[k].begin(), frames[k].end());
    m.add_frames(bytes);
  }
  std::vector<std::uint8_t> body;
  if (!PbPayloadCodec::encode(m, &body)) {
    return empty();
  }
  return body;
}

}  // namespace hgcp
}  // namespace hg
