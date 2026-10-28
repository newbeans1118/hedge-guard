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
//   · `MSG_RESUME_DATA`    ← `MsgResumeData{from_seq, to_seq, is_last, frames}`
//                            （补数批次；`frames` = 本批各帧原始帧字节）
//   · `MSG_HELLO_ACK`      ← `MsgHelloAck{accepted, server_proto_version, session_id,
//                            server_state, param_version, reason_code}`（T-208：六字段
//                            逐项编码；空白 body 会解出全默认值 ⇒ 消费侧判握手失败）
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
  virtual std::vector<std::uint8_t> resume_data_body(
      std::uint64_t from_seq, std::uint64_t to_seq, bool is_last,
      const std::vector<std::vector<std::uint8_t> >& frames) const;
  // T-208：出站 `MsgHelloAck` 逐字段编码（§4.2.8.2；六字段，入参逐项对齐）——
  //   修复「空 body ⇒ accepted=false ⇒ 合法握手被判 AUTH_FAILED」（真联阻断级）。
  virtual std::vector<std::uint8_t> hello_ack_body(
      bool accepted, std::uint32_t server_proto_version, const std::string& session_id,
      std::int32_t server_state, std::uint64_t param_version,
      std::uint32_t reason_code) const;
  // T-171：入站 `MsgHello.auth_token` 解析（§4.2.8.2）；不可解析 ⇒ 空串。
  virtual std::string hello_auth_token(
      const std::vector<std::uint8_t>& hello_body) const;
  // T-175：入站 `MsgHello.token_version` 解析（§4.2.8.2）；不可解析 ⇒ 0。
  virtual std::uint32_t hello_token_version(
      const std::vector<std::uint8_t>& hello_body) const;
  // T-175：入站 `MsgHello.client_boot_id` 解析（§4.2.8.2）；不可解析 ⇒ 空串。
  virtual std::string hello_client_boot_id(
      const std::vector<std::uint8_t>& hello_body) const;
  // T-171：`MsgAuthFail{reason_code}` 编码（§4.2.8.2）；detail 不承载令牌信息。
  virtual std::vector<std::uint8_t> auth_fail_body(std::uint32_t reason_code) const;
  // T-039：入站 `MsgResumeReq.last_received_seq` 解析（§4.2.8.2）；不可解析 ⇒ false。
  virtual bool resume_req_last_seq(const std::vector<std::uint8_t>& body,
                                   std::uint64_t* last_received_seq) const;

 private:
  PbTransportPolicy(const PbTransportPolicy&);
  PbTransportPolicy& operator=(const PbTransportPolicy&);

  PbPayloadCodec codec_;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_PB_TRANSPORT_POLICY_H
