#ifndef HG_HGCP_FRAME_H
#define HG_HGCP_FRAME_H
// =============================================================================
// HGCP 帧编解码（ARCHITECTURE.md §4.2.2，`:759`–`:786`）
//
//   布局：MAGIC(4) | VER(2) | TYPE(2) | FLAGS(2) | RESERVED(2) | SEQ(8)
//         | MSG_ID(8) | LEN(4) | PAYLOAD(LEN) | CRC32(4)      —— 全部大端
//   帧头固定 32 字节；CRC32 覆盖 VER..PAYLOAD（不含 MAGIC 与 CRC 自身）。
//
// 本层**只**处理帧（无消息语义、无 protobuf 依赖）→ 可独立单测。
// 消息 payload 的字段级 schema 属 T-032 缺口（§4.2 未给出字段号/类型），见回执。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <vector>

#include "hg/hgcp/protocol.h"

namespace hg {
namespace hgcp {

// 帧头（§4.2.2 `:770`–`:781`）。`reserved` 恒 0（接收方忽略但仍校验）。
struct FrameHeader {
  std::uint16_t ver;       // 线格式版本，大端；本版 = 0x0100
  std::uint16_t type;      // 消息类型码（§4.2.3）
  std::uint16_t flags;     // bitmask（§4.2.2 FLAGS）
  std::uint16_t reserved;  // 恒 0
  std::uint64_t seq;       // 通道级单调递增，从 1 开始
  std::uint64_t msg_id;    // 消息唯一 id（请求-回执关联）
  std::uint32_t len;       // payload 字节数
};

// 帧层解码/校验结果 → 对应 §4.3.2 组件错误码
enum class FrameStatus {
  kOk = 0,             // 成功
  kNeedMore = 1,       // 流式：缓冲不足，需继续读（非错误）
  kBadMagic = 2,       // 0x0102 ERR_BAD_MAGIC
  kBadVersion = 3,     // 0x0101 ERR_VERSION_MISMATCH
  kBadReserved = 4,    // RESERVED ≠ 0（协议不变量违规）
  kFrameTooLarge = 5,  // ⚠️ 取 §4.3.2 `:1122` 的 `0x0308 ERR_FRAME_TOO_LARGE`
                       //    —— 与 §4.2.2 `:779` 的 `0x0104` 冲突，**待 `CH-013` 裁定**
  kCrcMismatch = 6     // 0x0103 ERR_CRC_MISMATCH
};

const char* to_string(FrameStatus s);

// FrameStatus → §4.3.2 组件错误码（kOk/kNeedMore 返回 ErrorCode::kOk）。
// ⚠️ 冲突项（**待 `CH-013` 裁定**，勿视为权威）：帧超限在 §4.2.2 `:779` 记 `0x0104`、
//    在 §4.3.2 `:1122` 记 `0x0308 ERR_FRAME_TOO_LARGE`。此处**取更具体者 `0x0308`**。
ErrorCode error_code_for(FrameStatus s);

// ---- 大端编解码（网络序）----
void write_u16_be(std::uint8_t* out, std::uint16_t v);
void write_u32_be(std::uint8_t* out, std::uint32_t v);
void write_u64_be(std::uint8_t* out, std::uint64_t v);
std::uint16_t read_u16_be(const std::uint8_t* in);
std::uint32_t read_u32_be(const std::uint8_t* in);
std::uint64_t read_u64_be(const std::uint8_t* in);

// 标准 CRC-32（IEEE 802.3 多项式 0xEDB88320；init/final 0xFFFFFFFF）。
// 自测向量：crc32("123456789", 9) == 0xCBF43926。
std::uint32_t crc32(const std::uint8_t* data, std::size_t len);

// 编码 32 字节帧头（MAGIC 为常量，恒定写入）。
void encode_header(const FrameHeader& h, std::uint8_t out[kHeaderSize]);

// 解码并校验 32 字节帧头（校验 MAGIC / VER / RESERVED / LEN 上限）。
FrameStatus decode_header(const std::uint8_t in[kHeaderSize], FrameHeader* out);

// 编码整帧：HEADER(32) + PAYLOAD(len) + CRC32(4)。
// payload_len > kMaxPayloadSize ⇒ kFrameTooLarge；payload 可为 null（len=0）。
FrameStatus encode_frame(const FrameHeader& h, const std::uint8_t* payload,
                         std::size_t payload_len, std::vector<std::uint8_t>* out);

// 从缓冲解码一帧（流式）：
//   缓冲不足一帧（含不足 32B 头）⇒ kNeedMore（*consumed 不变）；
//   成功 ⇒ kOk，填充 *out / *payload，*consumed = 32 + len + 4。
FrameStatus decode_frame(const std::uint8_t* buf, std::size_t n,
                         FrameHeader* out, std::vector<std::uint8_t>* payload,
                         std::size_t* consumed);

// -----------------------------------------------------------------------------
// 帧信封：帧头 + **不透明** body
//
// ⚠️ body 的**语义 schema**（字段/类型/字段号）由**架构席裁定**（T-032 缺口，
//    见 `cpp/src/hgcp/T032_GAP_ANALYSIS.md`）。**帧层不解释 body**——只保证
//    LEN / MSG_ID / SEQ / 类型码 / CRC / 大端正确。这是「schema 到位即可平移」的接缝：
//    schema 落地后仅新增 payload 编解码实现（见 `payload_codec.h`），帧层零改动。
// -----------------------------------------------------------------------------
struct Frame {
  FrameHeader header;                 // 帧头（其中 header.len = body.size()）
  std::vector<std::uint8_t> body;     // 不透明 payload 字节
};

// 便捷组帧：以不透明 body 生成 wire 字节（body 为空 ⇒ 无 payload 帧）。
FrameStatus make_frame(std::uint16_t type, std::uint16_t flags, std::uint64_t seq,
                       std::uint64_t msg_id, const std::vector<std::uint8_t>& body,
                       std::vector<std::uint8_t>* wire);

// 便捷解帧：wire → Frame（body 原样透传）。语义同 decode_frame。
FrameStatus parse_frame(const std::uint8_t* buf, std::size_t n, Frame* out,
                        std::size_t* consumed);

// 流式帧读取器：累积 TCP 字节并逐帧取出（供状态机读者循环）。
// 只做帧级切分；错误（坏 MAGIC/CRC/超限…）原样透出，由调用方决定断连。
class FrameStreamReader {
 public:
  FrameStreamReader();

  void feed(const std::uint8_t* data, std::size_t n);

  // 取下一完整帧；缓冲不足整帧 ⇒ kNeedMore（不消费，可继续 feed）。
  FrameStatus next(Frame* out);

  // 尚未消费的字节数。
  std::size_t buffered() const { return buf_.size() - off_; }

 private:
  void compact();

  std::vector<std::uint8_t> buf_;
  std::size_t off_;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_FRAME_H
