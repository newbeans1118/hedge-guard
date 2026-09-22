// T-032 帧信封 / 不透明 payload 透传 / 流式切分 / payload 接缝 单测。
// 证明：帧层在**不依赖消息字段 schema** 的前提下，可承载体（含无 body）消息，
// 并在 schema 到位后「平移」——见 payload_codec.h 与 T032_GAP_ANALYSIS.md。
#include "hg/hgcp/frame.h"
#include "hg/hgcp/payload_codec.h"

#include <cstdint>
#include <string>
#include <vector>

#include "hgcp_test_util.h"

namespace {

using hg::hgcp::Frame;
using hg::hgcp::FrameStatus;
using hg::hgcp::FrameStreamReader;
using hg::hgcp::MessageType;
using hg::hgcp::OpaquePayloadCodec;

std::vector<std::uint8_t> mk_body(std::size_t n, std::uint8_t seed) {
  std::vector<std::uint8_t> v(n);
  for (std::size_t i = 0; i < n; ++i) {
    v[i] = static_cast<std::uint8_t>((seed + i) & 0xFFu);
  }
  return v;
}

void test_opaque_body_passthrough() {
  const std::vector<std::uint8_t> body = mk_body(5, 0xA0);
  std::vector<std::uint8_t> wire;
  HG_CHECK(hg::hgcp::make_frame(0x0101, 0x0002, 42, 4242, body, &wire) ==
           FrameStatus::kOk);
  Frame f;
  std::size_t consumed = 0;
  HG_CHECK(hg::hgcp::parse_frame(&wire[0], wire.size(), &f, &consumed) ==
           FrameStatus::kOk);
  HG_CHECK_EQ(consumed, wire.size());
  HG_CHECK_EQ(f.header.type, 0x0101);
  HG_CHECK_EQ(f.header.flags, 0x0002);
  HG_CHECK_EQ(f.header.seq, 42u);
  HG_CHECK_EQ(f.header.msg_id, 4242u);
  HG_CHECK_EQ(f.header.len, static_cast<std::uint32_t>(5));
  HG_CHECK(f.body == body);  // 不透明透传，逐字节相等
}

void test_empty_body_meta_messages() {
  // 无 body 的元协议消息（HEARTBEAT/HEARTBEAT_ACK/AUTH_FAIL 的**帧层**载体）
  const std::uint16_t types[3] = {static_cast<std::uint16_t>(MessageType::kMsgHeartbeat),
                                  static_cast<std::uint16_t>(MessageType::kMsgHeartbeatAck),
                                  static_cast<std::uint16_t>(MessageType::kMsgAuthFail)};
  for (int i = 0; i < 3; ++i) {
    std::vector<std::uint8_t> wire;
    HG_CHECK(hg::hgcp::make_frame(types[i], 0, static_cast<std::uint64_t>(i + 1), 0,
                                  std::vector<std::uint8_t>(), &wire) ==
             FrameStatus::kOk);
    HG_CHECK_EQ(wire.size(), static_cast<std::size_t>(32 + 4));  // 头 + 空 body + CRC
    Frame f;
    std::size_t consumed = 0;
    HG_CHECK(hg::hgcp::parse_frame(&wire[0], wire.size(), &f, &consumed) ==
             FrameStatus::kOk);
    HG_CHECK_EQ(f.header.type, types[i]);
    HG_CHECK(f.body.empty());
  }
}

void test_body_with_extreme_bytes() {
  std::vector<std::uint8_t> body;
  body.push_back(0x00u);
  body.push_back(0xFFu);
  body.push_back(0x00u);
  std::vector<std::uint8_t> wire;
  HG_CHECK(hg::hgcp::make_frame(0x0007, 0x0004, 1, 1, body, &wire) ==
           FrameStatus::kOk);
  Frame f;
  std::size_t consumed = 0;
  HG_CHECK(hg::hgcp::parse_frame(&wire[0], wire.size(), &f, &consumed) ==
           FrameStatus::kOk);
  HG_CHECK(f.body == body);
}

void test_oversize_body_rejected() {
  const std::vector<std::uint8_t> big(
      static_cast<std::size_t>(hg::hgcp::kMaxPayloadSize) + 1u, 0u);
  std::vector<std::uint8_t> wire;
  HG_CHECK(hg::hgcp::make_frame(0x0101, 0, 1, 1, big, &wire) ==
           FrameStatus::kFrameTooLarge);
}

void test_stream_reader_two_frames() {
  std::vector<std::uint8_t> w1, w2, all;
  HG_CHECK(hg::hgcp::make_frame(0x0004, 0, 1, 11, mk_body(3, 1), &w1) ==
           FrameStatus::kOk);
  HG_CHECK(hg::hgcp::make_frame(0x0005, 0, 2, 12, std::vector<std::uint8_t>(), &w2) ==
           FrameStatus::kOk);
  all.insert(all.end(), w1.begin(), w1.end());
  all.insert(all.end(), w2.begin(), w2.end());

  FrameStreamReader r;
  r.feed(&all[0], all.size());
  HG_CHECK_EQ(r.buffered(), all.size());
  Frame f1;
  HG_CHECK(r.next(&f1) == FrameStatus::kOk);
  HG_CHECK_EQ(f1.header.seq, 1u);
  HG_CHECK_EQ(f1.header.msg_id, 11u);
  Frame f2;
  HG_CHECK(r.next(&f2) == FrameStatus::kOk);
  HG_CHECK_EQ(f2.header.seq, 2u);
  HG_CHECK(f2.body.empty());
  Frame f3;
  HG_CHECK(r.next(&f3) == FrameStatus::kNeedMore);  // 已取完
  HG_CHECK_EQ(r.buffered(), static_cast<std::size_t>(0));
}

void test_stream_reader_split_feed() {
  std::vector<std::uint8_t> w;
  const std::vector<std::uint8_t> body = mk_body(10, 5);
  HG_CHECK(hg::hgcp::make_frame(0x0117, 0, 7, 70, body, &w) == FrameStatus::kOk);

  FrameStreamReader r;
  r.feed(&w[0], 10);  // 半截头部
  Frame f;
  HG_CHECK(r.next(&f) == FrameStatus::kNeedMore);  // 未消费，可续 feed
  r.feed(&w[10], w.size() - 10);
  HG_CHECK(r.next(&f) == FrameStatus::kOk);
  HG_CHECK_EQ(f.header.msg_id, 70u);
  HG_CHECK(f.body == body);
}

void test_stream_reader_frame_boundary() {
  std::vector<std::uint8_t> w1, w2, all;
  HG_CHECK(hg::hgcp::make_frame(0x0001, 0, 1, 1, mk_body(4, 9), &w1) ==
           FrameStatus::kOk);
  HG_CHECK(hg::hgcp::make_frame(0x0002, 0, 2, 2, mk_body(6, 3), &w2) ==
           FrameStatus::kOk);
  all.insert(all.end(), w1.begin(), w1.end());
  all.insert(all.end(), w2.begin(), w2.end());
  // 第一次只喂「帧1 + 帧2 前 5 字节」
  FrameStreamReader r;
  r.feed(&all[0], w1.size() + 5);
  Frame f;
  HG_CHECK(r.next(&f) == FrameStatus::kOk);
  HG_CHECK_EQ(f.header.seq, 1u);
  HG_CHECK(r.next(&f) == FrameStatus::kNeedMore);
  // 补齐余下
  r.feed(&all[w1.size() + 5], all.size() - (w1.size() + 5));
  HG_CHECK(r.next(&f) == FrameStatus::kOk);
  HG_CHECK_EQ(f.header.seq, 2u);
  HG_CHECK_EQ(f.body.size(), static_cast<std::size_t>(6));
}

void test_payload_codec_seam() {
  OpaquePayloadCodec c;
  HG_CHECK_EQ(std::string(c.name()), std::string("opaque"));
  // Opaque：任意 type / body 均透传（校验恒真）
  std::vector<std::uint8_t> any = mk_body(7, 0x10);
  HG_CHECK(c.validate(0x0004, std::vector<std::uint8_t>()));
  HG_CHECK(c.validate(0x0001, any));
  HG_CHECK(c.validate(0xFFFF, any));
  // 通过基类指针调用（平移点：替换实现即可，帧层/服务端零改动）
  hg::hgcp::PayloadCodec* base = &c;
  HG_CHECK(base->validate(0x0002, any));
}

}  // namespace

int main() {
  test_opaque_body_passthrough();
  test_empty_body_meta_messages();
  test_body_with_extreme_bytes();
  test_oversize_body_rejected();
  test_stream_reader_two_frames();
  test_stream_reader_split_feed();
  test_stream_reader_frame_boundary();
  test_payload_codec_seam();
  return hg::hgcp::testutil::report("envelope_test");
}
