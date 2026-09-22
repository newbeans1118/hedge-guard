// HGCP 补数缓冲实现（ARCHITECTURE.md §4.2.6）。
#include "hg/hgcp/resume_buffer.h"

#include <cstddef>

namespace hg {
namespace hgcp {

const char* to_string(ReplayStatus s) {
  switch (s) {
    case ReplayStatus::kOk: return "OK";
    case ReplayStatus::kNothingToReplay: return "NOTHING";
    case ReplayStatus::kGapTooLarge: return "GAP_TOO_LARGE";
  }
  return "?";
}

ResumeBuffer::ResumeBuffer(std::size_t capacity)
    : capacity_(capacity == 0u ? 1u : capacity), head_(0u), count_(0u), slots_(capacity_) {
  if (slots_.empty()) {
    slots_.resize(1u);
  }
}

const BufferedFrame& ResumeBuffer::at(std::size_t logical) const {
  return slots_[(head_ + logical) % capacity_];
}

void ResumeBuffer::push(std::uint64_t seq, const std::vector<std::uint8_t>& frame_bytes) {
  if (count_ == capacity_) {
    // 满 ⇒ 覆盖最旧
    slots_[head_].seq = seq;
    slots_[head_].bytes = frame_bytes;
    head_ = (head_ + 1u) % capacity_;
    return;
  }
  const std::size_t pos = (head_ + count_) % capacity_;
  slots_[pos].seq = seq;
  slots_[pos].bytes = frame_bytes;
  ++count_;
}

std::uint64_t ResumeBuffer::min_seq() const {
  return count_ == 0u ? 0u : slots_[head_].seq;
}

std::uint64_t ResumeBuffer::max_seq() const {
  if (count_ == 0u) {
    return 0u;
  }
  return at(count_ - 1u).seq;
}

ReplayStatus ResumeBuffer::replay_from(
    std::uint64_t last_received_seq, std::size_t max_batch,
    std::vector<std::vector<BufferedFrame> >* batches) const {
  batches->clear();
  if (max_batch == 0u) {
    max_batch = kResumeMaxBatchFrames;
  }
  if (count_ == 0u) {
    return ReplayStatus::kNothingToReplay;
  }
  // 缺口超出缓冲：需要从 last+1 起，但缓冲最小 SEQ 已大于 last+1 ⇒ 无法补齐。
  if (last_received_seq + 1u < min_seq()) {
    return ReplayStatus::kGapTooLarge;
  }
  if (last_received_seq >= max_seq()) {
    return ReplayStatus::kNothingToReplay;
  }

  std::vector<BufferedFrame> cur;
  for (std::size_t i = 0; i < count_; ++i) {
    const BufferedFrame& f = at(i);
    if (f.seq <= last_received_seq) {
      continue;
    }
    if (cur.size() == max_batch) {
      batches->push_back(cur);
      cur.clear();
    }
    cur.push_back(f);
  }
  if (!cur.empty()) {
    batches->push_back(cur);
  }
  if (batches->empty()) {
    return ReplayStatus::kNothingToReplay;
  }
  return ReplayStatus::kOk;
}

}  // namespace hgcp
}  // namespace hg
