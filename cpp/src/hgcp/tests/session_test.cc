// T-032 连接会话单测（schema-free 承载层）：半包/粘包 / 心跳 / 退避 / 补数 / 单连接。
#include "hg/hgcp/session.h"

#include <cstdint>
#include <vector>

#include "hgcp_test_util.h"

namespace {

using hg::hgcp::BufferedFrame;
using hg::hgcp::ConnectionDecision;
using hg::hgcp::EmptyTransportPolicy;
using hg::hgcp::Frame;
using hg::hgcp::FrameStatus;
using hg::hgcp::HgcpSession;
using hg::hgcp::MessageType;
using hg::hgcp::ReplayStatus;

std::vector<std::uint8_t> wire_of(std::uint16_t type, std::uint16_t flags,
                                  std::uint64_t seq, std::uint64_t mid,
                                  const std::vector<std::uint8_t>& body) {
  std::vector<std::uint8_t> w;
  hg::hgcp::make_frame(type, flags, seq, mid, body, &w);
  return w;
}

void test_feed_half_and_sticky() {
  HgcpSession s;
  s.on_connected(0);
  const std::vector<std::uint8_t> b1 = {1, 2, 3};
  const std::vector<std::uint8_t> b2 = {9, 8};
  std::vector<std::uint8_t> w1 = wire_of(0x0004, 0, 1, 1, b1);
  std::vector<std::uint8_t> w2 = wire_of(0x0005, 0, 2, 2, b2);

  std::vector<Frame> got;
  // 半包：喂 w1 的前 20 字节 ⇒ kNeedMore，无帧
  HG_CHECK(s.feed(&w1[0], 20, &got) == FrameStatus::kNeedMore);
  HG_CHECK_EQ(got.size(), static_cast<std::size_t>(0));
  // 补 w1 其余 ⇒ 1 帧
  HG_CHECK(s.feed(&w1[20], w1.size() - 20, &got) == FrameStatus::kOk);
  HG_CHECK_EQ(got.size(), static_cast<std::size_t>(1));
  HG_CHECK(got[0].body == b1);
  HG_CHECK_EQ(got[0].header.seq, 1u);
  // 粘包：一次喂 w2 + w2（两帧同缓冲）⇒ 2 帧
  got.clear();
  std::vector<std::uint8_t> both = w2;
  both.insert(both.end(), w2.begin(), w2.end());
  HG_CHECK(s.feed(&both[0], both.size(), &got) == FrameStatus::kOk);
  HG_CHECK_EQ(got.size(), static_cast<std::size_t>(2));
  // 逐字节喂（最坏分片）应仍然正确
  HgcpSession s2;
  s2.on_connected(0);
  std::vector<Frame> got2;
  for (std::size_t i = 0; i < w1.size(); ++i) {
    s2.feed(&w1[i], 1, &got2);
  }
  HG_CHECK_EQ(got2.size(), static_cast<std::size_t>(1));
  HG_CHECK(got2[0].body == b1);
}

void test_seq_gap_detection() {
  HgcpSession s;
  s.on_connected(0);
  std::vector<Frame> got;
  const std::vector<std::uint8_t> e;
  std::vector<std::uint8_t> w1 = wire_of(0x0004, 0, 1, 1, e);
  std::vector<std::uint8_t> w3 = wire_of(0x0004, 0, 3, 3, e);  // 跳号（缺 2）
  s.feed(&w1[0], w1.size(), &got);
  HG_CHECK(!s.seq_gap_pending());
  HG_CHECK_EQ(s.last_received_seq(), 1u);
  s.feed(&w3[0], w3.size(), &got);
  HG_CHECK(s.seq_gap_pending());  // SEQ 3 > 1+1 ⇒ 缺口 ⇒ 触发补数
  HG_CHECK_EQ(s.last_received_seq(), 3u);
  s.clear_seq_gap();
  HG_CHECK(!s.seq_gap_pending());
}

void test_outbound_seq_and_resume() {
  HgcpSession s;
  s.on_connected(0);
  const std::vector<std::uint8_t> body = {7, 7};
  Frame f1, f2;
  HG_CHECK(s.make_outbound(0x0201, 0, body, &f1) == FrameStatus::kOk);
  HG_CHECK_EQ(f1.header.seq, 1u);  // 通道级 SEQ 从 1 起
  HG_CHECK(s.make_outbound(0x0203, 0, body, &f2) == FrameStatus::kOk);
  HG_CHECK_EQ(f2.header.seq, 2u);
  HG_CHECK(f1.header.msg_id != f2.header.msg_id);
  HG_CHECK_EQ(s.last_sent_seq(), 2u);
  HG_CHECK_EQ(s.resume_buffered(), static_cast<std::size_t>(2));

  std::vector<std::vector<BufferedFrame> > batches;
  HG_CHECK(s.build_resume(0, &batches) == ReplayStatus::kOk);
  HG_CHECK_EQ(batches.size(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(batches[0].size(), static_cast<std::size_t>(2));
  HG_CHECK_EQ(batches[0][0].seq, 1u);
  // 补齐
  HG_CHECK(s.build_resume(2, &batches) == ReplayStatus::kNothingToReplay);
}

void test_heartbeat_tick_and_death() {
  HgcpSession s;
  s.on_connected(1000);
  EmptyTransportPolicy pol;
  std::vector<Frame> out;
  // 未到点
  HG_CHECK(s.on_tick(1999, pol, &out));
  HG_CHECK_EQ(out.size(), static_cast<std::size_t>(0));
  // 到点 ⇒ 产出心跳帧（type=MSG_HEARTBEAT）
  HG_CHECK(s.on_tick(2000, pol, &out));
  HG_CHECK_EQ(out.size(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(out[0].header.type,
              static_cast<std::uint16_t>(MessageType::kMsgHeartbeat));
  HG_CHECK(out[0].body.empty());  // 空 body 占位（schema 待定）
  // 收 ACK ⇒ 复位
  s.on_heartbeat_ack(2000);
  HG_CHECK(!s.heartbeat_timed_out(4000));
  HG_CHECK_EQ(s.heartbeat_missed(4000), 2);
  // 连续 3 次未 ACK ⇒ 判死（1000ms × 3 = 3000ms）
  HG_CHECK(!s.heartbeat_timed_out(4999));
  HG_CHECK(s.heartbeat_timed_out(5000));
  std::vector<Frame> out2;
  HG_CHECK(!s.on_tick(5000, pol, &out2));  // 返回 false = 应关连接
}

void test_single_connection_and_backoff() {
  HgcpSession s;
  HG_CHECK(s.on_new_connection() == ConnectionDecision::kAccept);
  HG_CHECK(s.connection_active());
  HG_CHECK(s.on_new_connection() == ConnectionDecision::kRejectDuplicate);  // 0x0302
  s.on_closed();
  HG_CHECK(!s.connection_active());
  HG_CHECK(s.on_new_connection() == ConnectionDecision::kAccept);

  // 重连退避：500 → 1000 → 2000 → 4000 → 8000（上限）
  HgcpSession b;
  HG_CHECK_EQ(b.next_reconnect_delay_ms(), static_cast<std::int64_t>(500));
  HG_CHECK_EQ(b.next_reconnect_delay_ms(), static_cast<std::int64_t>(1000));
  HG_CHECK_EQ(b.next_reconnect_delay_ms(), static_cast<std::int64_t>(2000));
  HG_CHECK_EQ(b.next_reconnect_delay_ms(), static_cast<std::int64_t>(4000));
  HG_CHECK_EQ(b.next_reconnect_delay_ms(), static_cast<std::int64_t>(8000));
  HG_CHECK_EQ(b.next_reconnect_delay_ms(), static_cast<std::int64_t>(8000));
  for (int i = 0; i < 5; ++i) {
    b.next_reconnect_delay_ms();
  }
  HG_CHECK(b.reconnect_severe());  // ≥10 次 ⇒ 严重告警
  b.reset_reconnect();
  HG_CHECK_EQ(b.reconnect_attempts(), 0);
}

}  // namespace

int main() {
  test_feed_half_and_sticky();
  test_seq_gap_detection();
  test_outbound_seq_and_resume();
  test_heartbeat_tick_and_death();
  test_single_connection_and_backoff();
  return hg::hgcp::testutil::report("session_test");
}
