#ifndef HG_HGCP_SESSION_H
#define HG_HGCP_SESSION_H
// =============================================================================
// HGCP 连接会话（**schema-free**）—— ARCHITECTURE.md §4.2.6 / §4.2.1
//
// 承载：帧级切分（半包/粘包）/ 通道级 SEQ 分配与缺口检测 / 心跳 1000ms·3 次 /
//       断线指数退避 500ms→8s 无限 / 补数装配（65536·批次 ≤500）/ 单连接治理。
//
// ⚠️ 范围：**不含**鉴权握手与消息字段解析 —— 它们需要消息字段级 schema
//    （`ARCHITECTURE.md §4.2` 未定义），见 `T032_GAP_ANALYSIS.md` S-2/S-3。
//    本层只处理「类型码 + 不透明 body」，故 **不依赖 schema**、可确定性单测。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <vector>

#include "hg/hgcp/endpoint.h"
#include "hg/hgcp/frame.h"
#include "hg/hgcp/resume_buffer.h"
#include "hg/hgcp/timing.h"

namespace hg {
namespace hgcp {

// 控制帧 body 注入策略（**出站 body 的单一注入接缝**）。
// schema 已由 `CH-013` / §4.2.8 定义（`proto/hgcp.proto`）⇒ 出站 body 的字段级编码
// 由**实现方**（如 `PbTransportPolicy`，基于 `PbPayloadCodec`）完成；本接缝**只传字节**，
// 状态机（本层）不解释字段。详见 `pb_transport_policy.h`。
class TransportPolicy {
 public:
  virtual ~TransportPolicy();
  // `MSG_HEARTBEAT` 的 body（§4.2.8.2：`sent_ts_ms`/`last_received_seq`/`component_state`）
  virtual std::vector<std::uint8_t> heartbeat_body(std::int64_t now_ms,
                                                   std::uint64_t last_received_seq) const = 0;
  // 重复连接（`0x0302`）时回发的 `MSG_ERROR` body
  virtual std::vector<std::uint8_t> duplicate_connection_body() const = 0;
  // 心跳超时（`0x0301`）时的告警帧 body
  virtual std::vector<std::uint8_t> heartbeat_timeout_body() const = 0;
  // 帧超限（`0x0308 ERR_FRAME_TOO_LARGE`）时的告警帧 body
  // —— §4.2.8.8 `I-2` 行为面（2026-09-22 技术负责人裁定）：`err_code = 0x0308`，
  //    「提示使用分片」经 `err_msg` 承载。
  virtual std::vector<std::uint8_t> frame_too_large_body() const = 0;
  // 通用 `MSG_ERROR` 的 body（携带 `err_code`）—— §4.2.8.2 `MsgError.err_code`。
  // 用于握手门禁拒绝（`0x0107`）等「不需回执的失败」场景。
  virtual std::vector<std::uint8_t> error_body(std::uint32_t err_code) const = 0;
};

// 占位策略：全部返回空 body（**仅测试/无 schema 场景**使用；生产缺省见 `server_loop.h`）。
class EmptyTransportPolicy : public TransportPolicy {
 public:
  virtual std::vector<std::uint8_t> heartbeat_body(std::int64_t /*now_ms*/,
                                                   std::uint64_t /*last_received_seq*/) const;
  virtual std::vector<std::uint8_t> duplicate_connection_body() const;
  virtual std::vector<std::uint8_t> heartbeat_timeout_body() const;
  virtual std::vector<std::uint8_t> frame_too_large_body() const;
  virtual std::vector<std::uint8_t> error_body(std::uint32_t /*err_code*/) const;
};

// 连接会话：帧级 / 序号 / 心跳 / 退避 / 补数 / 单连接。
class HgcpSession {
 public:
  HgcpSession();

  void configure(std::int64_t heartbeat_interval_ms, int heartbeat_miss,
                 std::size_t resume_capacity);

  // --- 连接生命周期 ---
  void on_connected(std::int64_t now_ms);
  void on_disconnected();

  // --- 读侧：原始 TCP 字节 → 完整帧（半包保留，粘包逐个取出）---
  // 返回 kOk（已消费）/ kNeedMore（半包）/ 其它=帧内错误（调用方应断连）。
  FrameStatus feed(const std::uint8_t* data, std::size_t n, std::vector<Frame>* inbound);

  // --- 写侧：分配通道级 SEQ（从 1 单调递增）与 MSG_ID，组帧并入补数缓冲 ---
  FrameStatus make_outbound(std::uint16_t type, std::uint16_t flags,
                            const std::vector<std::uint8_t>& body, Frame* out);

  // --- 定时推进：到点产出心跳帧；返回 false = 心跳判死（应关连接）---
  bool on_tick(std::int64_t now_ms, const TransportPolicy& policy,
               std::vector<Frame>* outbound);

  void on_heartbeat_ack(std::int64_t now_ms);
  bool heartbeat_timed_out(std::int64_t now_ms) const;
  int heartbeat_missed(std::int64_t now_ms) const;

  // --- 序号追踪 / 缺口 ---
  void note_received_seq(std::uint64_t seq);
  std::uint64_t last_received_seq() const { return last_received_seq_; }
  void set_last_received_seq(std::uint64_t s) { last_received_seq_ = s; }
  bool seq_gap_pending() const { return seq_gap_; }
  void clear_seq_gap() { seq_gap_ = false; }
  std::uint64_t last_sent_seq() const { return last_sent_seq_; }

  // --- 补数装配 ---
  ReplayStatus build_resume(std::uint64_t from_seq,
                            std::vector<std::vector<BufferedFrame> >* batches) const;
  std::size_t resume_buffered() const { return resume_.size(); }

  // --- 单连接治理 ---
  ConnectionDecision on_new_connection();
  void on_closed();
  bool connection_active() const { return conn_.active(); }

  // --- 断线重连退避 ---
  std::int64_t next_reconnect_delay_ms() { return backoff_.next_delay_ms(); }
  int reconnect_attempts() const { return backoff_.attempts(); }
  bool reconnect_severe() const { return backoff_.is_severe(); }
  void reset_reconnect() { backoff_.reset(); }

 private:
  HgcpSession(const HgcpSession&);
  HgcpSession& operator=(const HgcpSession&);

  FrameStreamReader reader_;
  HeartbeatMonitor hb_;
  ResumeBuffer resume_;
  ReconnectBackoff backoff_;
  SingleConnectionGuard conn_;
  std::uint64_t last_received_seq_;
  std::uint64_t last_sent_seq_;
  std::uint64_t next_msg_id_;
  bool seq_gap_;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_SESSION_H
