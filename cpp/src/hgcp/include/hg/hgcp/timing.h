#ifndef HG_HGCP_TIMING_H
#define HG_HGCP_TIMING_H
// =============================================================================
// HGCP 心跳与重连退避（ARCHITECTURE.md §4.2.6 `:951`–`:966`）
//
//   心跳间隔 HEARTBEAT_INTERVAL = 1000 ms（`HG.TCP.HEARTBEAT_MS`）
//   丢失阈值 HEARTBEAT_MISS     = 连续 3 次未收 HEARTBEAT_ACK
//   断连检出 = 1000 × 3 = 3000 ms ≈ 3 s ≤ 5 s（NFR-REL-2）
//   重连退避 = 500ms → 1s → 2s → 4s → 8s，上限 8 s，无限重试；失败 ≥10 次严重告警
//
// 全部逻辑以**注入时刻**驱动（无真实时钟、无 sleep）⇒ 可确定性单测。
// =============================================================================

#include <cstdint>

namespace hg {
namespace hgcp {

// §4.2.6（2）：心跳参数（`ARCHITECTURE.md:955`–`:957`）
const std::int64_t kHeartbeatIntervalMs = 1000;
const int kHeartbeatMissThreshold = 3;
// NFR-REL-2 的检出时间上限（`:957`）
const std::int64_t kHeartbeatDetectMaxMs = 5000;

// 心跳监视器：判定「连续 N 次未收 ACK」→ 断连（`0x0301`）。
class HeartbeatMonitor {
 public:
  HeartbeatMonitor();
  HeartbeatMonitor(std::int64_t interval_ms, int miss_threshold);

  // 建连/复位基准时刻。
  void restart(std::int64_t now_ms);
  // 收到 HEARTBEAT_ACK。
  void on_ack(std::int64_t now_ms);

  // 自上次 ACK 起「已错过」的心跳次数（按间隔计）。
  int missed(std::int64_t now_ms) const;
  // 是否判定断连（missed ≥ 阈值）。
  bool timed_out(std::int64_t now_ms) const;

  // 是否到「该发下一次 HEARTBEAT」的时刻（发送节奏）。
  bool heartbeat_due(std::int64_t now_ms) const;
  void mark_sent(std::int64_t now_ms);

  // 静态口径：区间 × 次数（= 3000 ms）。
  static std::int64_t timeout_ms();

  std::int64_t interval_ms() const { return interval_ms_; }
  int miss_threshold() const { return miss_threshold_; }

 private:
  std::int64_t interval_ms_;
  int miss_threshold_;
  std::int64_t last_ack_ms_;
  std::int64_t last_sent_ms_;
  bool started_;
};

// 重连指数退避：500ms→8s 上限、**无限重试**、失败 ≥10 次标记严重告警。
class ReconnectBackoff {
 public:
  ReconnectBackoff();

  // 返回本次应等待的毫秒数并推进（不封顶重试次数）。
  std::int64_t next_delay_ms();

  int attempts() const { return attempts_; }
  // 累计失败 ≥ 10 ⇒ 严重告警（`0x0303`，§4.2.6 `:966`）。
  bool is_severe() const;
  void reset();

 private:
  int attempts_;
};

// §4.2.6（3）：重连退避参数
const std::int64_t kReconnectBaseMs = 500;
const std::int64_t kReconnectCapMs = 8000;
const int kReconnectSevereThreshold = 10;

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_TIMING_H
