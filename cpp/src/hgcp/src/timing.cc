// HGCP 心跳与重连退避实现（ARCHITECTURE.md §4.2.6）。
#include "hg/hgcp/timing.h"

namespace hg {
namespace hgcp {

// -----------------------------------------------------------------------------
// HeartbeatMonitor
// -----------------------------------------------------------------------------
HeartbeatMonitor::HeartbeatMonitor()
    : interval_ms_(kHeartbeatIntervalMs),
      miss_threshold_(kHeartbeatMissThreshold),
      last_ack_ms_(0),
      last_sent_ms_(0),
      started_(false) {}

HeartbeatMonitor::HeartbeatMonitor(std::int64_t interval_ms, int miss_threshold)
    : interval_ms_(interval_ms),
      miss_threshold_(miss_threshold),
      last_ack_ms_(0),
      last_sent_ms_(0),
      started_(false) {}

void HeartbeatMonitor::restart(std::int64_t now_ms) {
  last_ack_ms_ = now_ms;
  last_sent_ms_ = now_ms;
  started_ = true;
}

void HeartbeatMonitor::on_ack(std::int64_t now_ms) {
  last_ack_ms_ = now_ms;
  if (!started_) {
    started_ = true;
  }
}

int HeartbeatMonitor::missed(std::int64_t now_ms) const {
  if (!started_ || interval_ms_ <= 0) {
    return 0;
  }
  const std::int64_t elapsed = now_ms - last_ack_ms_;
  if (elapsed < interval_ms_) {
    return 0;
  }
  return static_cast<int>(elapsed / interval_ms_);
}

bool HeartbeatMonitor::timed_out(std::int64_t now_ms) const {
  return missed(now_ms) >= miss_threshold_;
}

bool HeartbeatMonitor::heartbeat_due(std::int64_t now_ms) const {
  if (!started_) {
    return true;
  }
  return (now_ms - last_sent_ms_) >= interval_ms_;
}

void HeartbeatMonitor::mark_sent(std::int64_t now_ms) {
  last_sent_ms_ = now_ms;
  if (!started_) {
    started_ = true;
  }
}

std::int64_t HeartbeatMonitor::timeout_ms() {
  return kHeartbeatIntervalMs * kHeartbeatMissThreshold;  // 1000 × 3 = 3000
}

// -----------------------------------------------------------------------------
// ReconnectBackoff
// -----------------------------------------------------------------------------
ReconnectBackoff::ReconnectBackoff() : attempts_(0) {}

std::int64_t ReconnectBackoff::next_delay_ms() {
  // delay = min(base << attempts, cap)；attempts 递增，**不设上限**（无限重试）。
  const int shift = (attempts_ < 8) ? attempts_ : 8;
  std::int64_t d = kReconnectBaseMs * (static_cast<std::int64_t>(1) << shift);
  if (d > kReconnectCapMs) {
    d = kReconnectCapMs;
  }
  ++attempts_;
  return d;
}

bool ReconnectBackoff::is_severe() const {
  return attempts_ >= kReconnectSevereThreshold;
}

void ReconnectBackoff::reset() { attempts_ = 0; }

}  // namespace hgcp
}  // namespace hg
