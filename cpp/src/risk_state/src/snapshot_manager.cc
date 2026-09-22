// 风控内存状态 —— 快照原子发布与安全回收（RCU）（ARCHITECTURE.md §4.4.3）
#include "hg/risk_state/snapshot_manager.h"

namespace hg {
namespace risk_state {

SnapshotManager::SnapshotManager(RiskSnapshot* initial)
    : active_(0),
      published_(initial),
      retired_count_(0),
      publish_count_(0),
      reclaimed_count_(0) {}

SnapshotManager::~SnapshotManager() {
  // 释放当前快照与全部待回收快照（析构期无并发读者）。
  RiskSnapshot* cur = published_.load(std::memory_order_acquire);
  if (cur != nullptr) delete cur;
  published_.store(nullptr, std::memory_order_release);
  for (std::size_t i = 0; i < retired_.size(); ++i) {
    delete retired_[i].snap;
  }
  retired_.clear();
  retired_count_.store(0, std::memory_order_relaxed);
}

SnapshotManager::ReadGuard SnapshotManager::enter() const {
  // 读者登记（seq_cst RMW = 全屏障）：必须**先于**取指针，以关闭 use-after-free 窗口。
  active_.fetch_add(1, std::memory_order_seq_cst);
  RiskSnapshot* s = published_.load(std::memory_order_acquire);
  return ReadGuard(this, s);
}

void SnapshotManager::leave() const {
  active_.fetch_sub(1, std::memory_order_seq_cst);
}

void SnapshotManager::publish(RiskSnapshot* fresh) {
  // 原子替换：单个指针写 ⇒ 读侧永不看到半更新数据。
  RiskSnapshot* old = published_.exchange(fresh, std::memory_order_seq_cst);
  // 本次发布纪元（从 1 开始）。
  const std::uint64_t epoch = publish_count_.fetch_add(1, std::memory_order_seq_cst) + 1;
  if (old != nullptr) {
    Retired r;
    r.snap = old;
    r.epoch = epoch;  // 旧快照「最后一个可被取到的纪元」
    retired_.push_back(r);
    // 无锁镜像：与 retired_ 的写入并行更新，供诊断访问器跨线程读。
    retired_count_.store(retired_.size(), std::memory_order_relaxed);
  }
  reclaim();
}

std::size_t SnapshotManager::reclaim() {
  // 读者登记在取指针之前 ⇒ 若此刻无活跃读者，则任何已取到旧快照的读者必已注销 ⇒ 可回收。
  if (active_.load(std::memory_order_seq_cst) != 0) return 0;
  const std::uint64_t cur = publish_count_.load(std::memory_order_seq_cst);
  std::size_t freed = 0;
  std::vector<Retired> keep;
  keep.reserve(retired_.size());
  for (std::size_t i = 0; i < retired_.size(); ++i) {
    // 宽限期：须已跨越「至少一次后续发布」（epoch < 当前纪元）才可释放。
    if (retired_[i].epoch < cur) {
      delete retired_[i].snap;
      ++freed;
    } else {
      keep.push_back(retired_[i]);
    }
  }
  retired_.swap(keep);
  retired_count_.store(retired_.size(), std::memory_order_relaxed);
  if (freed != 0) reclaimed_count_.fetch_add(freed, std::memory_order_relaxed);
  return freed;
}

SnapshotStats SnapshotManager::stats() const {
  SnapshotStats s;
  s.publish_count = publish_count_.load(std::memory_order_relaxed);
  s.reclaimed_count = reclaimed_count_.load(std::memory_order_relaxed);
  s.retired_pending = retired_count_.load(std::memory_order_relaxed);
  s.active_readers = active_.load(std::memory_order_seq_cst);
  return s;
}

}  // namespace risk_state
}  // namespace hg
