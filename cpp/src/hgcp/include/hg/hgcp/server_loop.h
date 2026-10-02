#ifndef HG_HGCP_SERVER_LOOP_H
#define HG_HGCP_SERVER_LOOP_H
// =============================================================================
// HGCP 回环服务端**承载循环**（schema-free）—— ARCHITECTURE.md §4.2.1 / §4.2.6
//
// 单一职责：把「回环 socket 原语」（`tcp`）与「连接会话状态机」（`session`）**装配**为
//   一个可驱动的服务端事件循环（本文件是这套装配的**唯一归属**）：
//     · **accept**（单连接治理：重复连接拒 `0x0302` 后关新连接）
//     · **读循环**（`FrameStreamReader` 增量切帧：半包保留 / 粘包逐取）
//     · **帧级握手门禁**（握手前仅接受 `MSG_HELLO`，否则 `0x0107` 拒并断连）
//     · **心跳定时器**（到点发 `MSG_HEARTBEAT`；连续 3 次未 ACK ⇒ 判死 `0x0301`）
//     · **断线退避装配**（退避序列由 `HgcpSession` 持有，经本类暴露给上层重连）
//     · **补数装配**（`MSG_RESUME_DATA` 按 ≤500 帧分批回放）
//
// ⚠️ 全程**不解释消息字段**（payload 不透明）：握手/控制帧/补数批的 body 均由
//    `TransportPolicy` 接缝注入（**单一接缝，不新增第二套符号**；入站侧的
//    `hello_auth_token` 同属该接缝，T-171）。
//    令牌**决策**（SHA-256 + 常量时间比较）由 `TokenVerifier`（auth.h，已验收组件）完成；
//    本类只做**接线**：HELLO 到达且已注入 verifier ⇒ 经接缝取 token → `verify_audited`
//    ⇒ 失败回 `MSG_AUTH_FAIL`(0x0003，body 经接缝编码) + 断连（FR-CONFIG-9 验收 4）。
//    verifier 为 null（未配置令牌）⇒ 维持帧级门禁行为（向后兼容，不因未配置而拒连）。
//
// 设计要点：
//   · `pump()` 为**单步**事件循环，由调用方按自身节拍驱动 ⇒ 可确定性单测（真实回环）。
//   · 时间由调用方**注入**（`now_ms`）——**无隐藏时钟、无内部线程**。
//   · 绑定校验在 `TcpListener`（原语层）完成；端口 0 = 内核分配（见 `tcp.h`）。
//   · 本文件位于 `cpp/src/hgcp/**`，**不在** `E4` 扫描根内 ⇒ 此处 socket I/O 合法。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "hg/hgcp/auth.h"
#include "hg/hgcp/endpoint.h"
#include "hg/hgcp/frame.h"
#include "hg/hgcp/pb_transport_policy.h"
#include "hg/hgcp/resume_buffer.h"
#include "hg/hgcp/session.h"
#include "hg/hgcp/tcp.h"

namespace hg {
namespace hgcp {

class HgcpServerLoop {
 public:
  explicit HgcpServerLoop(const TcpConfig& cfg = default_tcp_config());
  ~HgcpServerLoop();

  // 依配置建监听（端口 0 ⇒ 内核分配，实际端口见 bound_port()）。
  BindStatus start();
  void stop();

  bool listening() const { return listener_.listening(); }
  std::uint16_t bound_port() const { return listener_.bound_port(); }

  // 控制帧 body 注入接缝（缺省 = `PbTransportPolicy`，基于 `PbPayloadCodec` / `CH-013`）。
  void set_policy(const TransportPolicy* p);
  // 会话参数（心跳间隔/丢失阈值/补数缓冲容量）。
  void configure(std::int64_t heartbeat_interval_ms, int heartbeat_miss,
                 std::size_t resume_capacity);

  // 令牌校验器（T-171 / FR-CONFIG-9 验收 4）。可空持有；**缺省 null = 不启用**，
  // 维持既有帧级握手门禁（向后兼容：无令牌部署形态仍可用）。
  // 生命周期由调用方管理（本类不拥有、不析构）；须先于 start() 注入。
  void set_token_verifier(const TokenVerifier* v);
  // 审计留痕接收方（§4.2.6 `:947`）。可空；留痕由 `TokenVerifier::verify_audited`
  // 完成（成功不落痕），本类**不**重复留痕。
  void set_auth_audit_sink(AuthAuditSink* s);

  // 单步事件循环：accept（单连接治理）→ 读帧（粘/半包 + 握手门禁）→ 心跳。
  // 返回本次交付的**完整入站帧数**；入站帧追加到 *inbound（可空）。
  int pump(int timeout_ms, std::int64_t now_ms, std::vector<Frame>* inbound);

  // 出站：通道 SEQ/MSG_ID 由 `HgcpSession` 分配，并登记进补数缓冲。未连接返回 false。
  bool send_frame(std::uint16_t type, std::uint16_t flags,
                  const std::vector<std::uint8_t>& body);

  // 补数装配：按对端 `last_received_seq` 回放 `MSG_RESUME_DATA`（分批 ≤500）。
  ReplayStatus send_resume(std::uint64_t last_received_seq);

  // --- 运行期观测 ---
  bool has_connection() const { return conn_fd_ >= 0; }
  int accepted_connections() const { return accepted_; }
  int rejected_duplicates() const { return duplicates_; }
  int frames_in() const { return frames_in_; }
  int disconnects() const { return disconnects_; }

  // --- 断线退避装配（断线后由上层读取；重连成功时调用 reset_reconnect）---
  std::int64_t next_reconnect_delay_ms() { return session_.next_reconnect_delay_ms(); }
  int reconnect_attempts() const { return session_.reconnect_attempts(); }
  bool reconnect_severe() const { return session_.reconnect_severe(); }
  void reset_reconnect() { session_.reset_reconnect(); }

  HgcpSession& session() { return session_; }

 private:
  HgcpServerLoop(const HgcpServerLoop&);
  HgcpServerLoop& operator=(const HgcpServerLoop&);

  void handle_inbound(const Frame& f, std::int64_t now_ms);
  bool send_frame_obj(const Frame& f);
  void raw_send(int fd, std::uint16_t type, std::uint16_t flags, std::uint64_t seq,
                std::uint64_t mid, const std::vector<std::uint8_t>& body);
  void close_conn();

  TcpConfig cfg_;
  TcpListener listener_;
  int conn_fd_;
  PbTransportPolicy default_policy_;  // 缺省 = protobuf 实现（CH-013 / §4.2.8）
  const TransportPolicy* policy_;
  HgcpSession session_;
  const TokenVerifier* token_verifier_;  // 可空；null = 不启用令牌校验
  AuthAuditSink* auth_audit_sink_;       // 可空；经 verify_audited 消费
  std::string peer_;                     // 最近 accept 的对端描述（审计用；无敏感信息）
  int accepted_;
  int duplicates_;
  int frames_in_;
  int disconnects_;
  bool handshaken_;
  std::uint64_t reject_seq_;  // 被拒连接上的裸发帧序号（不占主通道 SEQ）
  std::uint64_t reject_mid_;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_SERVER_LOOP_H
