// T-032 心跳 / 重连退避单测（ARCHITECTURE.md §4.2.6 / NFR-REL-2）。
#include "hg/hgcp/timing.h"

#include "hgcp_test_util.h"

namespace {

using hg::hgcp::HeartbeatMonitor;
using hg::hgcp::ReconnectBackoff;

void test_heartbeat_params() {
  HG_CHECK_EQ(hg::hgcp::kHeartbeatIntervalMs, static_cast<std::int64_t>(1000));
  HG_CHECK_EQ(hg::hgcp::kHeartbeatMissThreshold, 3);
  HG_CHECK_EQ(HeartbeatMonitor::timeout_ms(), static_cast<std::int64_t>(3000));
  // NFR-REL-2：断连检出 ≤ 5 s
  HG_CHECK(HeartbeatMonitor::timeout_ms() <= hg::hgcp::kHeartbeatDetectMaxMs);
  HG_CHECK(HeartbeatMonitor::timeout_ms() >= 3000);
}

void test_heartbeat_detection() {
  HeartbeatMonitor m;
  m.restart(0);
  HG_CHECK_EQ(m.missed(0), 0);
  HG_CHECK(!m.timed_out(0));
  HG_CHECK_EQ(m.missed(999), 0);
  HG_CHECK(!m.timed_out(999));
  HG_CHECK_EQ(m.missed(1000), 1);
  HG_CHECK(!m.timed_out(1000));
  HG_CHECK_EQ(m.missed(2999), 2);
  HG_CHECK(!m.timed_out(2999));
  // 连续 3 次未收 ACK ⇒ 断连检出点 = 3000 ms
  HG_CHECK_EQ(m.missed(3000), 3);
  HG_CHECK(m.timed_out(3000));
  HG_CHECK(m.timed_out(3500));
  // ACK 复位
  m.on_ack(3500);
  HG_CHECK(!m.timed_out(3500));
  HG_CHECK_EQ(m.missed(3500), 0);
  HG_CHECK(!m.timed_out(6499));
  HG_CHECK(m.timed_out(6500));
}

void test_heartbeat_send_cadence() {
  HeartbeatMonitor m;
  m.restart(0);
  HG_CHECK(!m.heartbeat_due(999));
  HG_CHECK(m.heartbeat_due(1000));
  m.mark_sent(1000);
  HG_CHECK(!m.heartbeat_due(1999));
  HG_CHECK(m.heartbeat_due(2000));
}

void test_reconnect_backoff_sequence() {
  ReconnectBackoff b;
  HG_CHECK_EQ(b.attempts(), 0);
  HG_CHECK(!b.is_severe());
  // 500ms → 1s → 2s → 4s → 8s（上限）→ 8s ...
  HG_CHECK_EQ(b.next_delay_ms(), static_cast<std::int64_t>(500));
  HG_CHECK_EQ(b.next_delay_ms(), static_cast<std::int64_t>(1000));
  HG_CHECK_EQ(b.next_delay_ms(), static_cast<std::int64_t>(2000));
  HG_CHECK_EQ(b.next_delay_ms(), static_cast<std::int64_t>(4000));
  HG_CHECK_EQ(b.next_delay_ms(), static_cast<std::int64_t>(8000));
  HG_CHECK_EQ(b.next_delay_ms(), static_cast<std::int64_t>(8000));
  HG_CHECK_EQ(b.attempts(), 6);
  // 无限重试：50 次调用始终在 (0, 8000]，且不抛/不终止
  ReconnectBackoff c;
  std::int64_t last = 0;
  for (int i = 0; i < 50; ++i) {
    last = c.next_delay_ms();
    HG_CHECK(last > 0 && last <= hg::hgcp::kReconnectCapMs);
  }
  HG_CHECK_EQ(last, static_cast<std::int64_t>(8000));
  HG_CHECK_EQ(c.attempts(), 50);
  // 累计失败 ≥10 次 ⇒ 严重告警（0x0303）
  ReconnectBackoff d;
  for (int i = 0; i < 9; ++i) {
    d.next_delay_ms();
  }
  HG_CHECK(!d.is_severe());
  d.next_delay_ms();
  HG_CHECK_EQ(d.attempts(), 10);
  HG_CHECK(d.is_severe());
  d.reset();
  HG_CHECK_EQ(d.attempts(), 0);
  HG_CHECK(!d.is_severe());
}

}  // namespace

int main() {
  test_heartbeat_params();
  test_heartbeat_detection();
  test_heartbeat_send_cadence();
  test_reconnect_backoff_sequence();
  return hg::hgcp::testutil::report("timing_test");
}
