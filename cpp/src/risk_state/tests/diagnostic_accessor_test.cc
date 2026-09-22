// T-124 诊断访问器并发读单测（ARCHITECTURE.md §4.4.3）
//   · `last_result()`：任意线程读取须取得**自洽快照**（无撕裂）——
//     不变量「成功 ⇔ code == kRebuildOk」在每次发布中都成立，撕裂（ok 与 code
//     取自不同次写入）即违例。修复前 `last_` 为非原子结构 ⇒ 数据竞争/撕裂。
//   · `stats()`：任意线程读取（含 `retired_pending`）须不与写者的 `retired_`
//     变更并发（修复前读 `retired_.size()` 与 `push_back` / `swap` 竞争）。
//
// 说明：本可执行在**普通构建**下验证「功能不崩 + 每次读取自洽」；其**数据竞争**
//   判据须在 `-fsanitize=thread`（TSAN）构建下运行（见 `T-124` 交付证据：修复后
//   TSAN 干净，移除修复后 TSAN 必报 `last_` / `retired_` 竞争）。
#include "hg/risk_state/risk_state.h"

#include <atomic>
#include <cstdint>
#include <thread>
#include <vector>

#include "risk_state_test_util.h"

namespace {

using hg::risk_state::LoadBatch;
using hg::risk_state::ProductStatus;
using hg::risk_state::QuotaEntry;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RebuildResult;
using hg::risk_state::RiskState;
using hg::risk_state::SnapshotStats;
using hg::risk_state::VarietyMeta;
using hg::risk_state::kRebuildOk;
using hg::risk_state::qtyFromWhole;

// 合法批次：1 品种 + 1 子品种额度 ⇒ 不变量校验通过。
LoadBatch validBatch(std::uint64_t ver) {
  LoadBatch b;
  b.set_replace_all(true);
  b.set_param_version(ver);
  VarietyMeta v;
  v.product_id = 1;
  v.status = ProductStatus::kEnabled;
  v.convert_factor = qtyFromWhole(5);
  b.putVariety(v);
  QuotaEntry q;
  q.limit = qtyFromWhole(10);
  q.unit = "LOT";
  b.putQuota(QuotaLayer::kSubProduct, 1, 1, 0, q);
  return b;
}

// 非法批次：负额度 ⇒ 不变量校验失败、不发布（ok == false）。
LoadBatch invalidBatch(std::uint64_t ver) {
  LoadBatch b = validBatch(ver);
  QuotaEntry bad;
  bad.limit = -qtyFromWhole(1);
  bad.unit = "LOT";
  b.putQuota(QuotaLayer::kSubProduct, 1, 1, 0, bad);
  return b;
}

void test_concurrent_diagnostics_consistent() {
  RiskState st;
  // 先建立一致的初始态：缺省 `last_` = {ok=false, code=kRebuildOk}（code 0 == OK）
  // 与该不变量不符，故首步发布一次成功结果。
  HG_CHECK(st.rebuild(validBatch(1)).ok);

  std::atomic<bool> stop(false);
  std::atomic<long> reads(0);
  std::atomic<long> torn(0);
  std::atomic<long> stats_regress(0);

  const int kReaders = 3;
  std::vector<std::thread> readers;
  readers.reserve(static_cast<std::size_t>(kReaders));
  for (int i = 0; i < kReaders; ++i) {
    readers.push_back(std::thread([&st, &stop, &reads, &torn, &stats_regress]() {
      std::uint64_t seen_publish = 0;
      while (!stop.load(std::memory_order_relaxed)) {
        const RebuildResult r = st.last_result();
        // 自洽性：成功 ⇔ code == kRebuildOk（撕裂则违例）。
        if (r.ok != (r.code == kRebuildOk)) torn.fetch_add(1, std::memory_order_relaxed);

        const SnapshotStats s = st.stats();
        // publish_count 单调不减 —— 原子计数被撕裂/读脏则回退。
        if (s.publish_count < seen_publish) stats_regress.fetch_add(1, std::memory_order_relaxed);
        seen_publish = s.publish_count;

        reads.fetch_add(1, std::memory_order_relaxed);
        std::this_thread::yield();
      }
    }));
  }

  // 写者：交错发布「成功 / 不变量失败」两类结果，令 ok 翻转（利于暴露撕裂）。
  for (std::uint64_t v = 2; v <= 2000; ++v) {
    if ((v & 1U) == 0U) {
      HG_CHECK(st.rebuild(validBatch(v)).ok);
    } else {
      const RebuildResult r = st.rebuild(invalidBatch(v));
      HG_CHECK(!r.ok);
    }
    std::this_thread::yield();
  }
  stop.store(true, std::memory_order_relaxed);
  for (std::size_t i = 0; i < readers.size(); ++i) readers[i].join();

  HG_CHECK(reads.load() > 0);        // 读者确已并发观察
  HG_CHECK_EQ(torn.load(), 0L);      // 零撕裂：每次 last_result() 自洽
  HG_CHECK_EQ(stats_regress.load(), 0L);
}

}  // namespace

int main() {
  test_concurrent_diagnostics_consistent();
  return hg::risk_state::testutil::report("diagnostic_accessor_test");
}
