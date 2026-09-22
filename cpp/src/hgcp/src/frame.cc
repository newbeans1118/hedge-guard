// HGCP 帧编解码实现（ARCHITECTURE.md §4.2.2）。
#include "hg/hgcp/frame.h"

#include <cstring>

namespace hg {
namespace hgcp {

// -----------------------------------------------------------------------------
// CRC-32（IEEE 802.3，反射多项式 0xEDB88320）
// 查表用函数局部 `static const`（C++11 保证线程安全初始化）。
// -----------------------------------------------------------------------------
namespace {

struct Crc32Table {
  std::uint32_t v[256];
  Crc32Table() {
    for (std::uint32_t i = 0; i < 256u; ++i) {
      std::uint32_t c = i;
      for (int k = 0; k < 8; ++k) {
        c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
      }
      v[i] = c;
    }
  }
};

const Crc32Table& crc32_table() {
  static const Crc32Table tbl;
  return tbl;
}

}  // namespace

std::uint32_t crc32(const std::uint8_t* data, std::size_t len) {
  const Crc32Table& t = crc32_table();
  std::uint32_t crc = 0xFFFFFFFFu;
  for (std::size_t i = 0; i < len; ++i) {
    crc = (crc >> 8) ^ t.v[(crc ^ data[i]) & 0xFFu];
  }
  return crc ^ 0xFFFFFFFFu;
}

// -----------------------------------------------------------------------------
// 大端（网络序）
// -----------------------------------------------------------------------------
void write_u16_be(std::uint8_t* out, std::uint16_t v) {
  out[0] = static_cast<std::uint8_t>((v >> 8) & 0xFFu);
  out[1] = static_cast<std::uint8_t>(v & 0xFFu);
}
void write_u32_be(std::uint8_t* out, std::uint32_t v) {
  out[0] = static_cast<std::uint8_t>((v >> 24) & 0xFFu);
  out[1] = static_cast<std::uint8_t>((v >> 16) & 0xFFu);
  out[2] = static_cast<std::uint8_t>((v >> 8) & 0xFFu);
  out[3] = static_cast<std::uint8_t>(v & 0xFFu);
}
void write_u64_be(std::uint8_t* out, std::uint64_t v) {
  for (int i = 0; i < 8; ++i) {
    out[i] = static_cast<std::uint8_t>((v >> ((7 - i) * 8)) & 0xFFu);
  }
}
std::uint16_t read_u16_be(const std::uint8_t* in) {
  return static_cast<std::uint16_t>((static_cast<std::uint16_t>(in[0]) << 8) |
                                    static_cast<std::uint16_t>(in[1]));
}
std::uint32_t read_u32_be(const std::uint8_t* in) {
  return (static_cast<std::uint32_t>(in[0]) << 24) |
         (static_cast<std::uint32_t>(in[1]) << 16) |
         (static_cast<std::uint32_t>(in[2]) << 8) |
         static_cast<std::uint32_t>(in[3]);
}
std::uint64_t read_u64_be(const std::uint8_t* in) {
  std::uint64_t v = 0;
  for (int i = 0; i < 8; ++i) {
    v = (v << 8) | static_cast<std::uint64_t>(in[i]);
  }
  return v;
}

// -----------------------------------------------------------------------------
const char* to_string(FrameStatus s) {
  switch (s) {
    case FrameStatus::kOk: return "OK";
    case FrameStatus::kNeedMore: return "NEED_MORE";
    case FrameStatus::kBadMagic: return "BAD_MAGIC";
    case FrameStatus::kBadVersion: return "BAD_VERSION";
    case FrameStatus::kBadReserved: return "BAD_RESERVED";
    case FrameStatus::kFrameTooLarge: return "FRAME_TOO_LARGE";
    case FrameStatus::kCrcMismatch: return "CRC_MISMATCH";
  }
  return "?";
}

ErrorCode error_code_for(FrameStatus s) {
  switch (s) {
    case FrameStatus::kOk:
    case FrameStatus::kNeedMore:
      return ErrorCode::kOk;
    case FrameStatus::kBadMagic:
      return ErrorCode::kErrBadMagic;           // 0x0102
    case FrameStatus::kBadVersion:
      return ErrorCode::kErrVersionMismatch;    // 0x0101
    case FrameStatus::kBadReserved:
      return ErrorCode::kErrPayloadInvalid;     // 0x0104（载格式不变量违规）
    case FrameStatus::kFrameTooLarge:
      // ⚠️ 冲突项（待 `CH-013` 裁定）：§4.3.2 `:1122` 记 0x0308 ERR_FRAME_TOO_LARGE，
      //    §4.2.2 `:779` 记 0x0104。此处**取更具体者 0x0308**；定案前不得视为权威。
      return ErrorCode::kErrFrameTooLarge;      // 0x0308（依 §4.3.2 `:1122`）
    case FrameStatus::kCrcMismatch:
      return ErrorCode::kErrCrcMismatch;        // 0x0103
  }
  return ErrorCode::kOk;
}

// -----------------------------------------------------------------------------
void encode_header(const FrameHeader& h, std::uint8_t out[kHeaderSize]) {
  std::memcpy(out, kMagic, kMagicSize);
  write_u16_be(out + 4, h.ver);
  write_u16_be(out + 6, h.type);
  write_u16_be(out + 8, h.flags);
  write_u16_be(out + 10, h.reserved);
  write_u64_be(out + 12, h.seq);
  write_u64_be(out + 20, h.msg_id);
  write_u32_be(out + 28, h.len);
}

FrameStatus decode_header(const std::uint8_t in[kHeaderSize], FrameHeader* out) {
  if (std::memcmp(in, kMagic, kMagicSize) != 0) {
    return FrameStatus::kBadMagic;
  }
  out->ver = read_u16_be(in + 4);
  out->type = read_u16_be(in + 6);
  out->flags = read_u16_be(in + 8);
  out->reserved = read_u16_be(in + 10);
  out->seq = read_u64_be(in + 12);
  out->msg_id = read_u64_be(in + 20);
  out->len = read_u32_be(in + 28);

  if (out->ver != kWireVersion) {
    return FrameStatus::kBadVersion;
  }
  if (out->reserved != 0u) {
    return FrameStatus::kBadReserved;
  }
  if (out->len > kMaxPayloadSize) {
    return FrameStatus::kFrameTooLarge;
  }
  return FrameStatus::kOk;
}

FrameStatus encode_frame(const FrameHeader& h, const std::uint8_t* payload,
                         std::size_t payload_len, std::vector<std::uint8_t>* out) {
  if (payload_len > static_cast<std::size_t>(kMaxPayloadSize)) {
    return FrameStatus::kFrameTooLarge;
  }
  out->assign(kHeaderSize + payload_len + kCrcSize, 0u);

  FrameHeader hdr = h;
  hdr.ver = kWireVersion;  // 线格式版本由编码器恒定写入
  hdr.reserved = 0u;       // 不变量：RESERVED 恒 0
  hdr.len = static_cast<std::uint32_t>(payload_len);
  encode_header(hdr, &(*out)[0]);

  if (payload_len > 0 && payload != 0) {
    std::memcpy(&(*out)[kHeaderSize], payload, payload_len);
  }

  // CRC32 覆盖 VER..PAYLOAD（不含 MAGIC 与 CRC 自身）
  const std::size_t crc_span = kHeaderSize + payload_len - kMagicSize;
  const std::uint32_t crc = crc32(&(*out)[kMagicSize], crc_span);
  write_u32_be(&(*out)[kHeaderSize + payload_len], crc);
  return FrameStatus::kOk;
}

FrameStatus decode_frame(const std::uint8_t* buf, std::size_t n,
                         FrameHeader* out, std::vector<std::uint8_t>* payload,
                         std::size_t* consumed) {
  if (n < kMagicSize || std::memcmp(buf, kMagic, kMagicSize) != 0) {
    // 缓冲连 MAGIC 都不足 ⇒ 需继续读；已足 4B 但不匹配 ⇒ BAD_MAGIC
    if (n < kMagicSize) {
      return FrameStatus::kNeedMore;
    }
    return FrameStatus::kBadMagic;
  }
  if (n < kHeaderSize) {
    return FrameStatus::kNeedMore;
  }
  FrameStatus hs = decode_header(buf, out);
  if (hs != FrameStatus::kOk) {
    return hs;
  }
  const std::size_t total =
      kHeaderSize + static_cast<std::size_t>(out->len) + kCrcSize;
  if (n < total) {
    return FrameStatus::kNeedMore;
  }
  const std::size_t crc_span = kHeaderSize + static_cast<std::size_t>(out->len) - kMagicSize;
  const std::uint32_t want = read_u32_be(buf + kHeaderSize + out->len);
  const std::uint32_t got = crc32(buf + kMagicSize, crc_span);
  if (want != got) {
    return FrameStatus::kCrcMismatch;
  }
  payload->assign(buf + kHeaderSize, buf + kHeaderSize + out->len);
  *consumed = total;
  return FrameStatus::kOk;
}

// -----------------------------------------------------------------------------
// 帧信封 / 流式读取
// -----------------------------------------------------------------------------
FrameStatus make_frame(std::uint16_t type, std::uint16_t flags, std::uint64_t seq,
                       std::uint64_t msg_id, const std::vector<std::uint8_t>& body,
                       std::vector<std::uint8_t>* wire) {
  FrameHeader h;
  h.ver = kWireVersion;
  h.type = type;
  h.flags = flags;
  h.reserved = 0u;
  h.seq = seq;
  h.msg_id = msg_id;
  h.len = static_cast<std::uint32_t>(body.size());
  const std::uint8_t* p = body.empty() ? 0 : &body[0];
  return encode_frame(h, p, body.size(), wire);
}

FrameStatus parse_frame(const std::uint8_t* buf, std::size_t n, Frame* out,
                        std::size_t* consumed) {
  return decode_frame(buf, n, &out->header, &out->body, consumed);
}

FrameStreamReader::FrameStreamReader() : off_(0) {}

void FrameStreamReader::feed(const std::uint8_t* data, std::size_t n) {
  if (n == 0) {
    return;
  }
  compact();
  buf_.insert(buf_.end(), data, data + n);
}

FrameStatus FrameStreamReader::next(Frame* out) {
  const std::size_t avail = buffered();
  if (avail == 0u) {
    return FrameStatus::kNeedMore;
  }
  std::size_t consumed = 0;
  const FrameStatus st = parse_frame(&buf_[off_], avail, out, &consumed);
  if (st != FrameStatus::kOk) {
    // 不足整帧 ⇒ kNeedMore（可继续 feed）；帧内错误 ⇒ 原样透出由调用方处理。
    return st;
  }
  off_ += consumed;
  return FrameStatus::kOk;
}

void FrameStreamReader::compact() {
  if (off_ == 0u) {
    return;
  }
  if (off_ == buf_.size()) {
    buf_.clear();
    off_ = 0u;
    return;
  }
  // 仅在剩余前缀较大时搬运，避免每帧 O(n) 拷贝。
  if (off_ >= 4096u) {
    buf_.erase(buf_.begin(),
               buf_.begin() + static_cast<std::vector<std::uint8_t>::difference_type>(off_));
    off_ = 0u;
  }
}

}  // namespace hgcp
}  // namespace hg
