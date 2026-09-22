// T-032 帧层单测（ARCHITECTURE.md §4.2.2）：字节级 hexdump + 正反用例。
//
// 独立于实现计算的期望值由 python3(zlib.crc32) 预先算出并硬编码（见回执证据）。
#include "hg/hgcp/frame.h"

#include <cstring>
#include <string>
#include <vector>

#include "hgcp_test_util.h"

namespace {

using hg::hgcp::FrameHeader;
using hg::hgcp::FrameStatus;
using hg::hgcp::kHeaderSize;
using hg::hgcp::kMaxPayloadSize;

// --- 已知向量（python3 zlib 独立算出）---
// CRC-32("123456789") == 0xCBF43926
// 帧 {ver=0100,type=0001,flags=0,reserved=0,seq=1,msg_id=2,len=3,payload="abc"}
//   => 484743500100000100000000000000000000000100000000000000020000000361626352c618ae
const char* kFrameHex =
    "484743500100000100000000000000000000000100000000000000020000000361626352c618ae";
const char* kHeaderHex =
    "4847435001000001000000000000000000000001000000000000000200000003";

std::vector<std::uint8_t> from_hex(const std::string& h) {
  std::vector<std::uint8_t> v;
  for (std::size_t i = 0; i + 1 < h.size(); i += 2) {
    const std::string b = h.substr(i, 2);
    v.push_back(static_cast<std::uint8_t>(std::strtoul(b.c_str(), 0, 16)));
  }
  return v;
}

FrameHeader sample_header() {
  FrameHeader h;
  h.ver = 0x0100;
  h.type = 0x0001;
  h.flags = 0x0000;
  h.reserved = 0x0000;
  h.seq = 1;
  h.msg_id = 2;
  h.len = 3;
  return h;
}

void test_crc32_known_vectors() {
  const std::uint8_t s[] = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
  HG_CHECK_EQ(hg::hgcp::crc32(s, 9), 0xCBF43926u);
  HG_CHECK_EQ(hg::hgcp::crc32(s, 0), 0x00000000u);  // 空输入
}

void test_big_endian_layout() {
  std::uint8_t b[8];
  hg::hgcp::write_u16_be(b, 0x0100u);
  HG_CHECK_EQ(b[0], 0x01);
  HG_CHECK_EQ(b[1], 0x00);
  hg::hgcp::write_u32_be(b, 0x01020304u);
  HG_CHECK_EQ(b[0], 0x01);
  HG_CHECK_EQ(b[3], 0x04);
  hg::hgcp::write_u64_be(b, 0x0102030405060708ull);
  HG_CHECK_EQ(b[0], 0x01);
  HG_CHECK_EQ(b[7], 0x08);
  HG_CHECK_EQ(hg::hgcp::read_u16_be(b), 0x0102u);
  HG_CHECK_EQ(hg::hgcp::read_u32_be(b), 0x01020304u);
  HG_CHECK_EQ(hg::hgcp::read_u64_be(b), 0x0102030405060708ull);
}

void test_header_byte_exact() {
  std::uint8_t out[kHeaderSize];
  hg::hgcp::encode_header(sample_header(), out);
  HG_CHECK_EQ(hg::hgcp::testutil::to_hex(out, kHeaderSize), std::string(kHeaderHex));
  HG_CHECK_EQ(kHeaderSize, static_cast<std::size_t>(32));

  FrameHeader h;
  HG_CHECK(hg::hgcp::decode_header(out, &h) == FrameStatus::kOk);
  HG_CHECK_EQ(h.ver, 0x0100);
  HG_CHECK_EQ(h.type, 0x0001);
  HG_CHECK_EQ(h.seq, 1u);
  HG_CHECK_EQ(h.msg_id, 2u);
  HG_CHECK_EQ(h.len, 3u);
}

void test_frame_byte_exact_roundtrip() {
  const std::uint8_t payload[3] = {'a', 'b', 'c'};
  std::vector<std::uint8_t> buf;
  HG_CHECK(hg::hgcp::encode_frame(sample_header(), payload, 3, &buf) ==
           FrameStatus::kOk);
  // 字节级 hexdump 断言（含 CRC32 = 0x52c618ae）
  HG_CHECK_EQ(hg::hgcp::testutil::to_hex(&buf[0], buf.size()), std::string(kFrameHex));
  HG_CHECK_EQ(buf.size(), static_cast<std::size_t>(32 + 3 + 4));

  FrameHeader h;
  std::vector<std::uint8_t> got;
  std::size_t consumed = 0;
  HG_CHECK(hg::hgcp::decode_frame(&buf[0], buf.size(), &h, &got, &consumed) ==
           FrameStatus::kOk);
  HG_CHECK_EQ(consumed, buf.size());
  HG_CHECK_EQ(h.type, 0x0001);
  HG_CHECK_EQ(h.seq, 1u);
  HG_CHECK_EQ(got.size(), static_cast<std::size_t>(3));
  HG_CHECK_EQ(std::string(got.begin(), got.end()), std::string("abc"));
}

void test_zero_length_payload() {
  FrameHeader h = sample_header();
  std::vector<std::uint8_t> buf;
  HG_CHECK(hg::hgcp::encode_frame(h, 0, 0, &buf) == FrameStatus::kOk);
  HG_CHECK_EQ(buf.size(), static_cast<std::size_t>(32 + 4));
  FrameHeader out;
  std::vector<std::uint8_t> got;
  std::size_t consumed = 0;
  HG_CHECK(hg::hgcp::decode_frame(&buf[0], buf.size(), &out, &got, &consumed) ==
           FrameStatus::kOk);
  HG_CHECK_EQ(out.len, 0u);
  HG_CHECK(got.empty());
}

void test_reject_bad_magic() {
  std::vector<std::uint8_t> buf = from_hex(kFrameHex);
  buf[0] = 0x58;  // 'X'
  FrameHeader h;
  std::vector<std::uint8_t> got;
  std::size_t consumed = 0;
  HG_CHECK(hg::hgcp::decode_frame(&buf[0], buf.size(), &h, &got, &consumed) ==
           FrameStatus::kBadMagic);
  HG_CHECK(hg::hgcp::error_code_for(FrameStatus::kBadMagic) ==
           hg::hgcp::ErrorCode::kErrBadMagic);  // 0x0102
}

void test_reject_bad_version() {
  std::vector<std::uint8_t> buf = from_hex(kFrameHex);
  buf[4] = 0x02;  // VER -> 0x0200
  FrameHeader h;
  std::vector<std::uint8_t> got;
  std::size_t consumed = 0;
  HG_CHECK(hg::hgcp::decode_frame(&buf[0], buf.size(), &h, &got, &consumed) ==
           FrameStatus::kBadVersion);
  HG_CHECK(hg::hgcp::error_code_for(FrameStatus::kBadVersion) ==
           hg::hgcp::ErrorCode::kErrVersionMismatch);  // 0x0101
}

void test_reject_bad_reserved() {
  std::vector<std::uint8_t> buf = from_hex(kFrameHex);
  buf[10] = 0x01;  // RESERVED 非 0
  FrameHeader h;
  std::vector<std::uint8_t> got;
  std::size_t consumed = 0;
  HG_CHECK(hg::hgcp::decode_frame(&buf[0], buf.size(), &h, &got, &consumed) ==
           FrameStatus::kBadReserved);
}

void test_reject_crc_mismatch() {
  std::vector<std::uint8_t> buf = from_hex(kFrameHex);
  buf.back() ^= 0xFFu;  // 破坏 CRC 末字节
  FrameHeader h;
  std::vector<std::uint8_t> got;
  std::size_t consumed = 0;
  HG_CHECK(hg::hgcp::decode_frame(&buf[0], buf.size(), &h, &got, &consumed) ==
           FrameStatus::kCrcMismatch);
  HG_CHECK(hg::hgcp::error_code_for(FrameStatus::kCrcMismatch) ==
           hg::hgcp::ErrorCode::kErrCrcMismatch);  // 0x0103
  // 载荷被篡改（CRC 覆盖 VER..PAYLOAD）也应检出
  std::vector<std::uint8_t> buf2 = from_hex(kFrameHex);
  buf2[32] ^= 0x01u;  // payload 'a' -> '`'
  FrameHeader h2;
  std::vector<std::uint8_t> got2;
  std::size_t consumed2 = 0;
  HG_CHECK(hg::hgcp::decode_frame(&buf2[0], buf2.size(), &h2, &got2, &consumed2) ==
           FrameStatus::kCrcMismatch);
}

void test_reject_frame_too_large() {
  // 编码侧：payload > 1 MiB ⇒ 拒绝
  std::vector<std::uint8_t> big(static_cast<std::size_t>(kMaxPayloadSize) + 1u, 0u);
  std::vector<std::uint8_t> out;
  FrameHeader h = sample_header();
  HG_CHECK(hg::hgcp::encode_frame(h, &big[0], big.size(), &out) ==
           FrameStatus::kFrameTooLarge);
  // 解码侧：头里 LEN 超限 ⇒ 0x0308（立即判红，无需等满帧）
  std::uint8_t hdr[kHeaderSize];
  FrameHeader bad = sample_header();
  bad.len = kMaxPayloadSize + 1u;
  hg::hgcp::encode_header(bad, hdr);
  FrameHeader hh;
  HG_CHECK(hg::hgcp::decode_header(hdr, &hh) == FrameStatus::kFrameTooLarge);
  HG_CHECK(hg::hgcp::error_code_for(FrameStatus::kFrameTooLarge) ==
           hg::hgcp::ErrorCode::kErrFrameTooLarge);  // 0x0308
  // 边界：恰好 1 MiB 允许
  std::vector<std::uint8_t> exact(static_cast<std::size_t>(kMaxPayloadSize), 0u);
  std::vector<std::uint8_t> out2;
  HG_CHECK(hg::hgcp::encode_frame(h, &exact[0], exact.size(), &out2) ==
           FrameStatus::kOk);
  HG_CHECK_EQ(out2.size(), static_cast<std::size_t>(32) + kMaxPayloadSize + 4u);
}

void test_need_more() {
  std::vector<std::uint8_t> buf = from_hex(kFrameHex);
  FrameHeader h;
  std::vector<std::uint8_t> got;
  std::size_t consumed = 123;
  // 缓冲不足头部
  HG_CHECK(hg::hgcp::decode_frame(&buf[0], 10, &h, &got, &consumed) ==
           FrameStatus::kNeedMore);
  // 缓冲头部齐、整帧未齐
  HG_CHECK(hg::hgcp::decode_frame(&buf[0], 30, &h, &got, &consumed) ==
           FrameStatus::kNeedMore);
  HG_CHECK(hg::hgcp::decode_frame(&buf[0], buf.size() - 1, &h, &got, &consumed) ==
           FrameStatus::kNeedMore);
  HG_CHECK_EQ(consumed, static_cast<std::size_t>(123));  // 未消费
}

void test_message_type_table() {
  HG_CHECK_EQ(hg::hgcp::kMessageTypeCount, static_cast<std::size_t>(40));
  const std::uint16_t types[40] = {
      0x0001,0x0002,0x0003,0x0004,0x0005,0x0006,0x0007,0x0008,0x0009,
      0x0101,0x0102,0x0103,0x0111,0x0112,0x0113,0x0114,0x0115,0x0116,0x0117,
      0x0118,0x0119,0x011A,0x011B,0x011C,0x011D,0x011E,
      0x0201,0x0202,0x0203,0x0204,0x0205,0x0206,0x0207,0x0208,0x0209,0x020A,
      0x020B,0x020C,0x020D,0x020E};
  for (int i = 0; i < 40; ++i) {
    HG_CHECK(hg::hgcp::is_known_message_type(types[i]));
  }
  HG_CHECK(!hg::hgcp::is_known_message_type(0x0000));
  HG_CHECK(!hg::hgcp::is_known_message_type(0x0104));  // 未定义（间隙）
  HG_CHECK(!hg::hgcp::is_known_message_type(0xFFFF));
  HG_CHECK_EQ(std::string(hg::hgcp::to_string(hg::hgcp::MessageType::kMsgHello)),
              std::string("MSG_HELLO"));
  HG_CHECK_EQ(
      std::string(hg::hgcp::to_string(hg::hgcp::MessageType::kEvtMarketQuote)),
      std::string("EVT_MARKET_QUOTE"));
}

void test_error_code_table() {
  HG_CHECK_EQ(hg::hgcp::kErrorCodeCount, static_cast<std::size_t>(62));
  HG_CHECK_EQ(std::string(hg::hgcp::to_string(hg::hgcp::ErrorCode::kErrBadMagic)),
              std::string("ERR_BAD_MAGIC"));
  HG_CHECK_EQ(
      std::string(hg::hgcp::to_string(hg::hgcp::ErrorCode::kErrDuplicateConnection)),
      std::string("ERR_DUPLICATE_CONNECTION"));
  HG_CHECK_EQ(std::string(hg::hgcp::to_string(hg::hgcp::ErrorCode::kOk)),
              std::string("OK"));
}

}  // namespace

int main() {
  test_crc32_known_vectors();
  test_big_endian_layout();
  test_header_byte_exact();
  test_frame_byte_exact_roundtrip();
  test_zero_length_payload();
  test_reject_bad_magic();
  test_reject_bad_version();
  test_reject_bad_reserved();
  test_reject_crc_mismatch();
  test_reject_frame_too_large();
  test_need_more();
  test_message_type_table();
  test_error_code_table();
  return hg::hgcp::testutil::report("frame_test");
}
