#ifndef HG_HGCP_PB_TRANSPORT_POLICY_H
#define HG_HGCP_PB_TRANSPORT_POLICY_H
// =============================================================================
// `TransportPolicy`（**单一出站 body 注入接缝**）的 protobuf 实现 —— `T-032`
//
// 依据：`ARCHITECTURE.md` §4.2.8（`CH-013` 定版）与 §4.2.6 时序；
//       唯一定义 = `proto/hgcp.proto`（本实现经 `PbPayloadCodec` 编码）。
//
// 本类把「出站控制/告警帧的 body」编码为**真实 protobuf 消息**：
//   · `MSG_HEARTBEAT`      ← `MsgHeartbeat`（`sent_ts_ms` / `last_received_seq` / `component_state`）
//   · `MSG_ERROR`(0x0302)  ← `MsgError{err_code}`（重复连接）
//   · `MSG_ERROR`(0x0301)  ← `MsgError{err_code}`（心跳超时告警）
//   · `MSG_ERROR`(0x0308)  ← `MsgError{err_code, err_msg}`（帧超限；§4.2.8.8 `I-2` 行为面，
//                            「提示使用分片」经 `err_msg` 承载）
//   · `MSG_ERROR`(err_code) ← `MsgError{err_code}`（握手门禁 `0x0107` 等）
//
// 接缝不变：仍是 `session.h` 的 `TransportPolicy` **单一符号**——
//   本类只是其一个实现；帧层与承载层（`tcp`/`session`）**零改动**。
// =============================================================================

#include <cstdint>
#include <vector>

#include "hg/hgcp/payload_codec.h"
#include "hg/hgcp/session.h"

namespace hg {
namespace hgcp {

class PbTransportPolicy : public TransportPolicy {
 public:
  PbTransportPolicy();
  virtual std::vector<std::uint8_t> heartbeat_body(std::int64_t now_ms,
                                                   std::uint64_t last_received_seq) const;
  virtual std::vector<std::uint8_t> duplicate_connection_body() const;
  virtual std::vector<std::uint8_t> heartbeat_timeout_body() const;
  virtual std::vector<std::uint8_t> frame_too_large_body() const;
  virtual std::vector<std::uint8_t> error_body(std::uint32_t err_code) const;

 private:
  PbTransportPolicy(const PbTransportPolicy&);
  PbTransportPolicy& operator=(const PbTransportPolicy&);

  PbPayloadCodec codec_;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_PB_TRANSPORT_POLICY_H
