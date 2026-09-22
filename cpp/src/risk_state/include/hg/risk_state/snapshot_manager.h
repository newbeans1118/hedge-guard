#ifndef HG_RISK_STATE_SNAPSHOT_MANAGER_H
#define HG_RISK_STATE_SNAPSHOT_MANAGER_H
// =============================================================================
// 风控内存状态 —— 不可变快照的**原子发布与安全回收**（RCU 风格）
//   docs/ARCHITECTURE.md §4.4.3「并发策略：不可变快照 + 原子指针替换（RCU 风格）」
//
// 设计（与 §4.4.3 逐条对应）：
//   · 读路径：`published_.load(acquire)` 取指针 → 全程只读 const 数据（零锁、零等待）。
//     并发安全以「**读者先在注册计数上登记、再取指针**」保证（见下）。
//   · 写路径：重建线程构建**新快照** → 校验 → `published_.exchange(seq_cst)` 原子替换。
//     读侧永远看不到半更新数据（发布是单个指针写）。
//   · 旧快照回收：**自实现的「原子引用计数 + 宽限期（纪元 grace）」**
//     （§4.4.3：C++11 没有 shared_ptr 的原子操作，故自实现）。
//       ① 活跃读者计数 `active_`（`std::atomic<int64_t>`）：`enter()` 登记、`leave()` 注销；
//       ② 每张旧快照带「退役纪元」`epoch`；仅当 `epoch < 当前发布纪元`（跨越至少一次
//          后续发布）**且** `active_ == 0`（无活跃读者）时，方可释放 —— 二者缺一推迟。
//
// 并发安全论证（读者登记在取指针**之前**）：
//   R: `active_.fetch_add(1, seq_cst)` → `s = published_.load(acquire)`
//   W: `old = published_.exchange(fresh, seq_cst)` → `active_.load(seq_cst)`
//   若 W 的读得 0，则 seq_cst 全序中 W 的读 < R 的加；又 R 的加（seq_cst RMW，全屏障）
//   前于 R 的取，故 W 的换 < R 的取 ⇒ R 只能取到 `fresh`（而非 `old`）。逆否：R 若
//   取到 `old`，则 W 的读必 ≥ 1 ⇒ 回收推迟。故**无 use-after-free**。
//
// ⚠️ 本文件所在目录 `cpp/src/risk_state/**` 属 `E4` 扫描根：**不得**出现锁原语或
//    同步 I/O 的**调用形态**。本实现仅用 `std::atomic`（无锁）与纪元 grace。
// =============================================================================

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

#include "hg/risk_state/risk_snapshot.h"

namespace hg {
namespace risk_state {

// 运行期统计（可观测性；不参与判定）
struct SnapshotStats {
  std::uint64_t publish_count = 0;    // 累计发布次数
  std::uint64_t reclaimed_count = 0;  // 累计回收快照数
  std::size_t retired_pending = 0;    // 待回收（宽限中）快照数
  std::int64_t active_readers = 0;    // 当前活跃读者数
};

class SnapshotManager {
 public:
  // initial：初始快照（**取得所有权**，须非空指针）。
  explicit SnapshotManager(RiskSnapshot* initial);
  ~SnapshotManager();

  // ---------------------------------------------------------------- 读侧 ----
  // 无注册的原子读（ARCH §3.4 literal：`snapshot()` → `const RiskSnapshot&`）。
  // 语义：返回当前已发布快照的只读引用；调用方须在「下一个消息边界」内用完 ——
  //   写者在发布后至少跨越一次后续发布、且无活跃读者时才回收旧快照（见类注释）。
  const RiskSnapshot& peek() const {
    return *published_.load(std::memory_order_acquire);
  }

  // 注册式读句柄（RCU 强保护）：句柄存活期间，其持有的快照**必不被回收**，
  // 即使其间发生任意多次 publish。适用于需跨重建持有的读者（T5/T9）。
  class ReadGuard {
   public:
    ReadGuard() : mgr_(nullptr), snap_(nullptr) {}
    ReadGuard(const SnapshotManager* mgr, RiskSnapshot* snap) : mgr_(mgr), snap_(snap) {}

    ReadGuard(ReadGuard&& other) : mgr_(other.mgr_), snap_(other.snap_) {
      other.mgr_ = nullptr;
      other.snap_ = nullptr;
    }
    ReadGuard& operator=(ReadGuard&& other) {
      if (this != &other) {
        release();
        mgr_ = other.mgr_;
        snap_ = other.snap_;
        other.mgr_ = nullptr;
        other.snap_ = nullptr;
      }
      return *this;
    }

    ~ReadGuard() { release(); }

    const RiskSnapshot& get() const { return *snap_; }
    const RiskSnapshot* operator->() const { return snap_; }
    const RiskSnapshot& operator*() const { return *snap_; }
    bool valid() const { return snap_ != nullptr; }

   private:
    void release() {
      if (mgr_ != nullptr) {
        mgr_->leave();
        mgr_ = nullptr;
        snap_ = nullptr;
      }
    }
    const SnapshotManager* mgr_;
    RiskSnapshot* snap_;
    ReadGuard(const ReadGuard&);             // 不可拷贝（句柄唯一）
    ReadGuard& operator=(const ReadGuard&);  // 不可拷贝
  };

  // 登记一名读者并返回其句柄（句柄析构即注销）。
  ReadGuard enter() const;

  // ---------------------------------------------------------------- 写侧 ----
  // 原子替换（**单写者**：参数重建线程）。fresh 取得所有权（须非空）。
  // 旧快照进入退役表（带本次发布纪元），随后尝试宽限期回收。
  void publish(RiskSnapshot* fresh);

  // 宽限期回收：释放满足「退出纪元 < 当前发布纪元」且「无活跃读者」的旧快照。
  // 返回本次释放个数。写者可随时调用（幂等、无副作用于活跃读者）。
  std::size_t reclaim();

  // ------------------------------------------------------------- 观测 ---------
  std::uint64_t publish_count() const {
    return publish_count_.load(std::memory_order_relaxed);
  }
  std::uint64_t reclaimed_count() const {
    return reclaimed_count_.load(std::memory_order_relaxed);
  }
  std::size_t retired_pending() const { return retired_.size(); }
  std::int64_t active_readers() const { return active_.load(std::memory_order_seq_cst); }
  SnapshotStats stats() const;

 private:
  void leave() const;

  struct Retired {
    RiskSnapshot* snap;
    std::uint64_t epoch;  // 退役时的发布纪元（该快照「最后一个可被取到的纪元」）
  };

  mutable std::atomic<std::int64_t> active_;
  std::atomic<RiskSnapshot*> published_;
  std::vector<Retired> retired_;  // **写者独占**（单写者，无需同步）
  std::atomic<std::uint64_t> publish_count_;
  std::atomic<std::uint64_t> reclaimed_count_;

  SnapshotManager(const SnapshotManager&) = delete;
  SnapshotManager& operator=(const SnapshotManager&) = delete;
};

}  // namespace risk_state
}  // namespace hg

#endif  // HG_RISK_STATE_SNAPSHOT_MANAGER_H
