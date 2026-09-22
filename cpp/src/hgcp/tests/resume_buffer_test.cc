// T-032 补数缓冲单测（ARCHITECTURE.md §4.2.6）：环形保留 / 分批 ≤500 / 缺口判定。
#include "hg/hgcp/resume_buffer.h"

#include <cstdint>
#include <vector>

#include "hgcp_test_util.h"

namespace {

using hg::hgcp::BufferedFrame;
using hg::hgcp::ReplayStatus;
using hg::hgcp::ResumeBuffer;

std::vector<std::uint8_t> bytes_of(std::uint64_t seq) {
  std::vector<std::uint8_t> v(4);
  v[0] = static_cast<std::uint8_t>(seq & 0xFFu);
  v[1] = static_cast<std::uint8_t>((seq >> 8) & 0xFFu);
  return v;
}

void test_constants() {
  HG_CHECK_EQ(hg::hgcp::kResumeBufferDefaultCapacity, static_cast<std::size_t>(65536));
  HG_CHECK_EQ(hg::hgcp::kResumeMaxBatchFrames, static_cast<std::size_t>(500));
  ResumeBuffer b;
  HG_CHECK_EQ(b.capacity(), static_cast<std::size_t>(65536));
  HG_CHECK(b.empty());
  HG_CHECK_EQ(b.min_seq(), 0u);
  HG_CHECK_EQ(b.max_seq(), 0u);
}

void test_push_and_replay() {
  ResumeBuffer b(8);
  for (std::uint64_t s = 1; s <= 3; ++s) {
    b.push(s, bytes_of(s));
  }
  HG_CHECK_EQ(b.size(), static_cast<std::size_t>(3));
  HG_CHECK_EQ(b.min_seq(), 1u);
  HG_CHECK_EQ(b.max_seq(), 3u);

  std::vector<std::vector<BufferedFrame> > batches;
  HG_CHECK(b.replay_from(0, 500, &batches) == ReplayStatus::kOk);
  HG_CHECK_EQ(batches.size(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(batches[0].size(), static_cast<std::size_t>(3));
  HG_CHECK_EQ(batches[0][0].seq, 1u);
  HG_CHECK_EQ(batches[0][2].seq, 3u);
  HG_CHECK_EQ(batches[0][2].bytes.size(), static_cast<std::size_t>(4));

  // 已齐 ⇒ 无补数
  HG_CHECK(b.replay_from(3, 500, &batches) == ReplayStatus::kNothingToReplay);
  // 部分
  HG_CHECK(b.replay_from(1, 500, &batches) == ReplayStatus::kOk);
  HG_CHECK_EQ(batches[0].size(), static_cast<std::size_t>(2));
  HG_CHECK_EQ(batches[0][0].seq, 2u);
}

void test_batch_split() {
  ResumeBuffer b(2048);
  for (std::uint64_t s = 1; s <= 1200; ++s) {
    b.push(s, bytes_of(s));
  }
  std::vector<std::vector<BufferedFrame> > batches;
  HG_CHECK(b.replay_from(0, 500, &batches) == ReplayStatus::kOk);
  HG_CHECK_EQ(batches.size(), static_cast<std::size_t>(3));     // 500+500+200
  HG_CHECK_EQ(batches[0].size(), static_cast<std::size_t>(500));
  HG_CHECK_EQ(batches[1].size(), static_cast<std::size_t>(500));
  HG_CHECK_EQ(batches[2].size(), static_cast<std::size_t>(200));
  // 每批都 ≤ 500
  for (std::size_t i = 0; i < batches.size(); ++i) {
    HG_CHECK(batches[i].size() <= hg::hgcp::kResumeMaxBatchFrames);
  }
}

void test_ring_overwrite_and_gap() {
  ResumeBuffer b(4);
  for (std::uint64_t s = 1; s <= 4; ++s) {
    b.push(s, bytes_of(s));
  }
  HG_CHECK_EQ(b.min_seq(), 1u);
  HG_CHECK_EQ(b.max_seq(), 4u);
  // 覆盖最旧
  for (std::uint64_t s = 5; s <= 8; ++s) {
    b.push(s, bytes_of(s));
  }
  HG_CHECK_EQ(b.size(), static_cast<std::size_t>(4));
  HG_CHECK_EQ(b.min_seq(), 5u);
  HG_CHECK_EQ(b.max_seq(), 8u);

  std::vector<std::vector<BufferedFrame> > batches;
  // last=0 ⇒ 需要 SEQ 1..，但缓冲最小 5 ⇒ 缺口超出 ⇒ 0x0306
  HG_CHECK(b.replay_from(0, 500, &batches) == ReplayStatus::kGapTooLarge);
  // last=4 ⇒ 需要 5.. ⇒ 可补
  HG_CHECK(b.replay_from(4, 500, &batches) == ReplayStatus::kOk);
  HG_CHECK_EQ(batches[0].size(), static_cast<std::size_t>(4));
  HG_CHECK_EQ(batches[0][0].seq, 5u);
  // last=5 ⇒ 需要 6..8
  HG_CHECK(b.replay_from(5, 500, &batches) == ReplayStatus::kOk);
  HG_CHECK_EQ(batches[0][0].seq, 6u);
  HG_CHECK_EQ(std::string(hg::hgcp::to_string(ReplayStatus::kGapTooLarge)),
              std::string("GAP_TOO_LARGE"));
}

void test_empty_replay() {
  ResumeBuffer b(4);
  std::vector<std::vector<BufferedFrame> > batches;
  HG_CHECK(b.replay_from(0, 500, &batches) == ReplayStatus::kNothingToReplay);
  b.push(7, bytes_of(7));
  HG_CHECK(b.replay_from(7, 500, &batches) == ReplayStatus::kNothingToReplay);
  HG_CHECK(b.replay_from(6, 500, &batches) == ReplayStatus::kOk);
  HG_CHECK_EQ(batches[0][0].seq, 7u);
}

}  // namespace

int main() {
  test_constants();
  test_push_and_replay();
  test_batch_split();
  test_ring_overwrite_and_gap();
  test_empty_replay();
  return hg::hgcp::testutil::report("resume_buffer_test");
}
