#ifndef HG_HGCP_RESUME_BUFFER_H
#define HG_HGCP_RESUME_BUFFER_H
// =============================================================================
// HGCP 补数缓冲（ARCHITECTURE.md §4.2.6 `:962`–`:974`）
//
//   · 发送缓冲环形区，保留最近 `HG.RESUME_BUFFER_SIZE`（默认 **65536 帧**）；
//   · 对端请求补数时，从 `SEQ > last_received_seq` 起**全量回放**（线路侧不过滤行情帧）；
//   · **单批 ≤ 500 帧**（`is_last` 标识最后一批）；
//   · 缺口超出缓冲保留范围（`last_received_seq < 缓冲最小 SEQ - 1`）⇒ 不得伪造，
//     回 `MSG_ERROR{0x0306}` 并触发全量状态重建。
//
// 本类为**纯数据/判定**层（无 socket、无 protobuf）⇒ 可独立单测。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace hg {
namespace hgcp {

// `HG.RESUME_BUFFER_SIZE` 默认值（§4.2.6 `:969`）
const std::size_t kResumeBufferDefaultCapacity = 65536u;
// 单批上限（§4.2.6 `:969`）
const std::size_t kResumeMaxBatchFrames = 500u;

enum class ReplayStatus {
  kOk = 0,              // 产出 ≥1 批
  kNothingToReplay = 1, // 已齐（无 SEQ > last_received_seq）
  kGapTooLarge = 2      // 缺口超出缓冲 ⇒ 0x0306 ERR_RESUME_GAP_TOO_LARGE
};

const char* to_string(ReplayStatus s);

// 一帧 = SEQ + 原始帧字节（线路侧原样回放）。
struct BufferedFrame {
  std::uint64_t seq;
  std::vector<std::uint8_t> bytes;
};

class ResumeBuffer {
 public:
  explicit ResumeBuffer(std::size_t capacity = kResumeBufferDefaultCapacity);

  // 追加一帧（按 SEQ 单调递增语义）。缓冲满则覆盖最旧（保留最近 N 帧）。
  void push(std::uint64_t seq, const std::vector<std::uint8_t>& frame_bytes);

  std::size_t size() const { return count_; }
  std::size_t capacity() const { return capacity_; }
  bool empty() const { return count_ == 0u; }
  std::uint64_t min_seq() const;  // 空时返回 0
  std::uint64_t max_seq() const;  // 空时返回 0

  // 收集 SEQ > last_received_seq 的帧，按 ≤ max_batch 帧分批。
  // batches 被覆盖填入；成功时返回 kOk。
  ReplayStatus replay_from(std::uint64_t last_received_seq, std::size_t max_batch,
                           std::vector<std::vector<BufferedFrame> >* batches) const;

 private:
  const BufferedFrame& at(std::size_t logical) const;

  std::size_t capacity_;
  std::size_t head_;
  std::size_t count_;
  std::vector<BufferedFrame> slots_;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_RESUME_BUFFER_H
