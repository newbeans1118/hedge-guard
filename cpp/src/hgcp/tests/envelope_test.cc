// T-032 帧信封 / 不透明 payload 透传 / 流式切分 / payload 编解码 单测。
// 证明：帧层在**不解释字段**的前提下承载体（含无 body）消息；
// payload 层（`PbPayloadCodec`，基于 `proto/hgcp.proto` / CH-013 §4.2.8）可编解码。
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
using hg::hgcp::PbPayloadCodec;

// protoc 生成命名空间（proto `package hgcp.v1` ⇒ `::hgcp::v1`）
namespace pbv1 = ::hgcp::v1;

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
  PbPayloadCodec c;
  HG_CHECK_EQ(std::string(c.name()), std::string("protobuf"));
  // 可解析 body ⇒ 校验通过
  pbv1::MsgError e;
  e.set_err_code(0x0107u);
  std::vector<std::uint8_t> body;
  HG_CHECK(PbPayloadCodec::encode(e, &body));
  HG_CHECK(!body.empty());
  const std::uint16_t kErrType = static_cast<std::uint16_t>(MessageType::kMsgError);
  HG_CHECK(c.validate(kErrType, body));
  // 未知类型 ⇒ false（§4.3.2 0x0105 由 is_known_message_type 判）
  HG_CHECK(!c.validate(0xFFFFu, body));
  // 通过基类指针调用（平移点：替换实现即可，帧层/承载层零改动）
  hg::hgcp::PayloadCodec* base = &c;
  HG_CHECK(base->validate(kErrType, body));
}

// §4.2.8.2 元协议 9 条 —— encode → decode 往返（关键字段逐一比对）。
void test_meta_messages_roundtrip() {
  {  // MSG_HELLO
    pbv1::MsgHello m;
    m.set_proto_version(0x0100u);
    m.set_component_id("hg-biz");
    m.set_component_role(pbv1::ROLE_JAVA);
    m.set_auth_token("fixture-token");  // 测试夹具；非真实凭据，零落盘
    m.set_client_boot_id("boot-1");
    m.set_last_received_seq(0u);
    m.set_token_version(1u);
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(m, &b));
    pbv1::MsgHello d;
    HG_CHECK(PbPayloadCodec::decode(b, &d));
    HG_CHECK_EQ(d.proto_version(), 0x0100u);
    HG_CHECK_EQ(std::string(d.component_id()), std::string("hg-biz"));
    HG_CHECK(d.component_role() == pbv1::ROLE_JAVA);
    HG_CHECK_EQ(d.token_version(), 1u);
    HG_CHECK_EQ(std::string(d.auth_token()), std::string("fixture-token"));
  }
  {  // MSG_HELLO_ACK
    pbv1::MsgHelloAck m;
    m.set_accepted(true);
    m.set_server_proto_version(0x0100u);
    m.set_session_id("sess-1");
    m.set_server_state(pbv1::SERVER_STATE_READY);
    m.set_param_version(42u);
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(m, &b));
    pbv1::MsgHelloAck d;
    HG_CHECK(PbPayloadCodec::decode(b, &d));
    HG_CHECK(d.accepted());
    HG_CHECK(d.server_state() == pbv1::SERVER_STATE_READY);
    HG_CHECK_EQ(d.param_version(), 42u);
  }
  {  // MSG_AUTH_FAIL
    pbv1::MsgAuthFail m;
    m.set_reason_code(0x0201u);
    m.set_detail("bad token");
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(m, &b));
    pbv1::MsgAuthFail d;
    HG_CHECK(PbPayloadCodec::decode(b, &d));
    HG_CHECK_EQ(d.reason_code(), 0x0201u);
    HG_CHECK_EQ(std::string(d.detail()), std::string("bad token"));
  }
  {  // MSG_HEARTBEAT
    pbv1::MsgHeartbeat m;
    m.set_sent_ts_ms(1758384000000LL);
    m.set_last_received_seq(123u);
    m.set_component_state(pbv1::COMPONENT_STATE_READY);
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(m, &b));
    pbv1::MsgHeartbeat d;
    HG_CHECK(PbPayloadCodec::decode(b, &d));
    HG_CHECK_EQ(d.sent_ts_ms(), 1758384000000LL);
    HG_CHECK_EQ(d.last_received_seq(), 123u);
    HG_CHECK(d.component_state() == pbv1::COMPONENT_STATE_READY);
  }
  {  // MSG_HEARTBEAT_ACK
    pbv1::MsgHeartbeatAck m;
    m.set_echo_ts_ms(7);
    m.set_server_ts_ms(8);
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(m, &b));
    pbv1::MsgHeartbeatAck d;
    HG_CHECK(PbPayloadCodec::decode(b, &d));
    HG_CHECK_EQ(d.echo_ts_ms(), 7);
    HG_CHECK_EQ(d.server_ts_ms(), 8);
  }
  {  // MSG_ACK
    pbv1::MsgAck m;
    m.set_ack_msg_id(5u);
    m.set_result(0);
    m.set_applied_version(3u);
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(m, &b));
    pbv1::MsgAck d;
    HG_CHECK(PbPayloadCodec::decode(b, &d));
    HG_CHECK_EQ(d.ack_msg_id(), 5u);
    HG_CHECK_EQ(d.applied_version(), 3u);
  }
  {  // MSG_ERROR
    pbv1::MsgError m;
    m.set_err_code(0x0308u);
    m.set_err_msg("frame too large");
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(m, &b));
    pbv1::MsgError d;
    HG_CHECK(PbPayloadCodec::decode(b, &d));
    HG_CHECK_EQ(d.err_code(), 0x0308u);
    HG_CHECK_EQ(std::string(d.err_msg()), std::string("frame too large"));
  }
  {  // MSG_RESUME_REQ
    pbv1::MsgResumeReq m;
    m.set_last_received_seq(9u);
    m.set_session_id("sess-2");
    m.set_max_resume_count(500);
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(m, &b));
    pbv1::MsgResumeReq d;
    HG_CHECK(PbPayloadCodec::decode(b, &d));
    HG_CHECK_EQ(d.last_received_seq(), 9u);
    HG_CHECK_EQ(std::string(d.session_id()), std::string("sess-2"));
    HG_CHECK_EQ(d.max_resume_count(), 500);
  }
  {  // MSG_RESUME_DATA（repeated bytes，含空元素）
    pbv1::MsgResumeData m;
    m.set_from_seq(2u);
    m.set_to_seq(3u);
    m.set_is_last(false);
    m.add_frames(std::string("\x01\x02", 2));
    m.add_frames(std::string("", 0));
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(m, &b));
    pbv1::MsgResumeData d;
    HG_CHECK(PbPayloadCodec::decode(b, &d));
    HG_CHECK_EQ(d.from_seq(), 2u);
    HG_CHECK_EQ(d.to_seq(), 3u);
    HG_CHECK(!d.is_last());
    HG_CHECK_EQ(d.frames_size(), 2);
    HG_CHECK(d.frames(0) == std::string("\x01\x02", 2));
    HG_CHECK(d.frames(1).empty());
  }
}

// T-039 / CH-019：`PermissionEntry.has_trading_post` 的**字段号 wire 实证**——
//   `= 9` ⇒ tag = (9<<3)|0 = 72 = 0x48，varint 值 1 = 0x01；`false` 为 proto3 缺省值**不编码**。
//   本用例独立于「生成码里字段名叫什么」，直接钉住**线格式上的字段号**（跨语言同源的硬约束）。
void test_permission_entry_trading_post_wire_tag() {
  {
    pbv1::PermissionEntry e;
    e.set_has_trading_post(true);
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(e, &b));
    HG_CHECK_EQ(b.size(), static_cast<std::size_t>(2));
    if (b.size() == 2u) {
      HG_CHECK_EQ(b[0], static_cast<std::uint8_t>(0x48));  // field 9, varint
      HG_CHECK_EQ(b[1], static_cast<std::uint8_t>(0x01));
    }
  }
  {
    pbv1::PermissionEntry e;
    e.set_has_trading_post(false);  // proto3 缺省值 ⇒ 不编码 ⇒ 空 body
    std::vector<std::uint8_t> b;
    HG_CHECK(PbPayloadCodec::encode(e, &b));
    HG_CHECK(b.empty());
  }
}

// T-039 / CH-019：`CmdUpdatePermission`（0x0116）整体往返 —— 该位经**协议路径**逐条承载，
//   含 true / false 两态与其余 8 字段并存，且 `validate()` 认可为合法 payload。
void test_cmd_update_permission_roundtrip() {
  pbv1::CmdUpdatePermission m;
  m.set_param_version(42u);
  pbv1::PermissionEntry* e1 = m.add_entries();
  e1->set_operator_id(9001u);
  e1->set_sub_account_id(7u);
  e1->add_product_ids(1u);
  e1->add_product_ids(2u);
  e1->set_quota_limit("500");
  e1->set_is_enterprise_leader(false);
  e1->set_is_management_role(false);
  e1->set_has_risk_role(false);
  e1->set_has_settle_role(false);
  e1->set_has_trading_post(true);   // 交易岗
  pbv1::PermissionEntry* e2 = m.add_entries();
  e2->set_operator_id(9002u);
  e2->set_sub_account_id(7u);
  e2->add_product_ids(1u);
  e2->set_has_trading_post(false);  // 非交易岗
  std::vector<std::uint8_t> b;
  HG_CHECK(PbPayloadCodec::encode(m, &b));
  HG_CHECK(!b.empty());
  const std::uint16_t kT = static_cast<std::uint16_t>(MessageType::kCmdUpdatePermission);
  PbPayloadCodec c;
  HG_CHECK(c.validate(kT, b));
  pbv1::CmdUpdatePermission d;
  HG_CHECK(PbPayloadCodec::decode(b, &d));
  HG_CHECK_EQ(d.param_version(), 42u);
  HG_CHECK_EQ(d.entries_size(), 2);
  if (d.entries_size() == 2) {
    HG_CHECK_EQ(d.entries(0).operator_id(), 9001u);
    HG_CHECK_EQ(d.entries(0).product_ids_size(), 2);
    HG_CHECK(d.entries(0).has_trading_post());    // 经协议路径承 true
    HG_CHECK(!d.entries(1).has_trading_post());   // 经协议路径承 false
  }
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
  test_meta_messages_roundtrip();
  test_permission_entry_trading_post_wire_tag();
  test_cmd_update_permission_roundtrip();
  return hg::hgcp::testutil::report("envelope_test");
}
