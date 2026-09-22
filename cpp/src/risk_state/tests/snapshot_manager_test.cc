// T-034 RCU 语义单测（ARCHITECTURE.md §4.4.3）
//   ① snapshot() 原子读：并发读侧不阻塞、不撕裂
//   ② rebuild() 后原子替换；旧快照可被 ReadGuard 安全持有（无悬垂）
//   ③ 纪元宽限期回收
#include "hg/risk_state/risk_state.h"

#include <atomic>
#include <thread>

#include "risk_state_test_util.h"

namespace {

using hg::risk_state::LoadBatch;
using hg::risk_state::ProductStatus;
using hg::risk_state::RiskSnapshot;
using hg::risk_state::RiskState;
using hg::risk_state::SnapshotManager;
using hg::risk_state::VarietyMeta;
using hg::risk_state::qtyFromWhole;

// 版本 ver 与「marker」阈值始终成对写入 ⇒ 读取时任一侧不一致即证明发生撕裂。
LoadBatch makeBatch(std::uint64_t ver) {
  LoadBatch b;
  b.set_replace_all(true);
  b.set_param_version(ver);
  b.set_build_time_ms(static_cast<std::int64_t>(ver) * 1000);
  b.putThreshold("marker", std::to_string(static_cast<long long>(ver)));
  VarietyMeta v;
  v.product_id = 1;
  v.status = ProductStatus::kEnabled;
  v.convert_factor = qtyFromWhole(1);
  b.putVariety(v);
  return b;
}

void test_atomic_read_no_tearing() {
  RiskState st;
  st.rebuild(makeBatch(1));

  std::atomic<bool> stop(false);
  std::atomic<long> mismatches(0);
  std::atomic<long> reads(0);
  std::atomic<long> last_observed_version(0);

  std::thread reader([&st, &stop, &mismatches, &reads, &last_observed_version]() {
    while (!stop.load(std::memory_order_relaxed)) {
      // 注册式原子读：读者先登记再取指针（无锁、不等待写者）。
      SnapshotManager::ReadGuard g = st.acquire();
      const RiskSnapshot& s = g.get();
      const std::uint64_t ver = s.param_version();
      const std::int64_t marker = s.thresholdInt("marker", -1);
      reads.fetch_add(1, std::memory_order_relaxed);
      // 原子读 ⇒ 必须看到自洽快照：marker 与 version 必一致（撕裂则不等）。
      if (marker != static_cast<std::int64_t>(ver)) {
        mismatches.fetch_add(1, std::memory_order_relaxed);
      }
      last_observed_version.store(static_cast<long>(ver), std::memory_order_relaxed);
      std::this_thread::yield();
    }
  });

  // 确保读者线程已开始运行（至少完成一次读取），再启动并发写入，避免写者先跑完的抖动。
  for (long spins = 0; reads.load(std::memory_order_relaxed) == 0 && spins < 100000000L;
       ++spins) {
    std::this_thread::yield();
  }

  // 写者并发发布：版本 2..400（每次让出调度，使读者与之真正交错）
  for (std::uint64_t v = 2; v <= 400; ++v) {
    const hg::risk_state::RebuildResult r = st.rebuild(makeBatch(v));
    HG_CHECK(r.ok);
    std::this_thread::yield();
  }
  // 让读者再观察若干轮，然后停。
  for (int i = 0; i < 1000; ++i) {
    std::this_thread::yield();
  }
  stop.store(true, std::memory_order_relaxed);
  reader.join();

  HG_CHECK(reads.load() > 0);
  HG_CHECK_EQ(mismatches.load(), 0L);               // 零撕裂
  HG_CHECK(last_observed_version.load() >= 1L);     // 读者看到了发布
  HG_CHECK_EQ(st.snapshot().param_version(), static_cast<std::uint64_t>(400));
}

void test_readguard_holds_old_snapshot() {
  RiskState st;
  st.rebuild(makeBatch(1));

  // 读者取得句柄后，写者连续替换快照 —— 句柄持有的旧快照必须仍可安全访问。
  SnapshotManager::ReadGuard g = st.acquire();
  HG_CHECK(g.valid());
  HG_CHECK_EQ(g.get().param_version(), static_cast<std::uint64_t>(1));

  st.rebuild(makeBatch(2));
  st.rebuild(makeBatch(3));

  HG_CHECK_EQ(g.get().param_version(), static_cast<std::uint64_t>(1));  // 旧快照仍在
  HG_CHECK_EQ(g->thresholdInt("marker", -1), static_cast<std::int64_t>(1));
  HG_CHECK_EQ(st.snapshot().param_version(), static_cast<std::uint64_t>(3));  // 新快照已发布
  HG_CHECK(st.stats().active_readers >= 1);

  // 释放句柄 → 读者计数归零；再发布若干次 ⇒ 宽限期回收（旧快照被释放）。
  g = SnapshotManager::ReadGuard();
  HG_CHECK_EQ(st.stats().active_readers, static_cast<std::int64_t>(0));
  st.rebuild(makeBatch(4));
  st.rebuild(makeBatch(5));
  HG_CHECK(st.stats().reclaimed_count >= 1);  // 至少回收了 v1/v2
  HG_CHECK_EQ(st.snapshot().param_version(), static_cast<std::uint64_t>(5));
}

void test_epoch_grace_defers_reclaim() {
  RiskState st;
  st.rebuild(makeBatch(1));
  HG_CHECK_EQ(st.stats().reclaimed_count, static_cast<std::uint64_t>(0));

  // 发布一次：刚退役的快照处于宽限中（epoch == 当前纪元）⇒ 本轮不回收。
  st.rebuild(makeBatch(2));
  // 再发布一次：上一轮的退役快照跨过至少一次后续发布 ⇒ 可回收。
  st.rebuild(makeBatch(3));
  HG_CHECK(st.stats().reclaimed_count >= 1);
  HG_CHECK(st.stats().retired_pending <= 2);
}

void test_peek_equals_snapshot() {
  RiskState st;
  st.rebuild(makeBatch(7));
  HG_CHECK_EQ(st.snapshot().param_version(), static_cast<std::uint64_t>(7));
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(7));
  HG_CHECK(&st.snapshot() == &st.snapshot());  // 同一发布指针（未发布则不变）
}

}  // namespace

int main() {
  test_atomic_read_no_tearing();
  test_readguard_holds_old_snapshot();
  test_epoch_grace_defers_reclaim();
  test_peek_equals_snapshot();
  return hg::risk_state::testutil::report("snapshot_manager_test");
}
