// T-035 事前风控基准（ARCHITECTURE.md §8.1.2「≥ 10 万次调用统计 P99/P99.9」）
//   验收断言：**P99 ≤ 5 ms**（NFR-PERF-1 / §4.4.4 预算分解：P3 事前风控 ≤ 2.0 ms，总 ≤ 3.7 ms）。
//   规模按 §4.4.1：10 子账户 / 200 品种 / 2000 合约。
//
// ⚠️ 本目录属 E4 扫描根：不得出现锁原语 / 同步 I/O 调用形态（本文件仅计时与统计）。
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::OrderRequest;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_state::Category;
using hg::risk_state::ExposureDirection;
using hg::risk_state::Fixed;
using hg::risk_state::LoadBatch;
using hg::risk_state::PermissionEntry;
using hg::risk_state::PlanEntry;
using hg::risk_state::PlanStatus;
using hg::risk_state::PositionLimitEntry;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RiskState;

const int kSubAccounts = 10;
const int kProducts = 200;
const int kInstruments = 2000;
const int kWhitelistPerSub = 20;
const int kIterations = 100000;

LoadBatch realisticBatch() {
  LoadBatch b;
  b.set_replace_all(true);
  b.set_param_version(1);
  b.set_build_time_ms(1);

  for (int p = 1; p <= kProducts; ++p) {
    hg::risk_state::VarietyMeta v;
    v.product_id = static_cast<std::uint32_t>(p);
    v.category = (p % 2 == 0) ? Category::kTrade : Category::kCommodity;
    v.status = hg::risk_state::ProductStatus::kEnabled;
    v.convert_factor = 10000;
    b.putVariety(v);
  }
  for (int i = 1; i <= kInstruments; ++i) {
    hg::risk_state::InstrumentMeta m;
    m.instrument_id = static_cast<std::uint32_t>(i);
    m.product_id = static_cast<std::uint32_t>(((i - 1) % kProducts) + 1);
    m.contract_multiplier = 10000;
    m.exchange = "SHFE";
    m.expiry_date = 20261231;
    b.putInstrument(m);
  }
  for (int s = 1; s <= kSubAccounts; ++s) {
    for (int p = 1; p <= kWhitelistPerSub; ++p) {
      b.addWhitelist(static_cast<std::uint32_t>(s), static_cast<std::uint32_t>(p));
      hg::risk_state::QuotaEntry q;
      q.limit = 1000000000;
      q.unit = "LOT";
      b.putQuota(QuotaLayer::kSubProduct, static_cast<std::uint32_t>(s),
                 static_cast<std::uint32_t>(p), 0, q);
    }
  }
  for (int s = 1; s <= kSubAccounts; ++s) {
    for (int i = 1; i <= 100; ++i) {
      hg::risk_state::QuotaEntry q;
      q.limit = 1000000000;
      q.unit = "LOT";
      b.putQuota(QuotaLayer::kSubInstrument, static_cast<std::uint32_t>(s),
                 static_cast<std::uint32_t>(((i - 1) % kProducts) + 1),
                 static_cast<std::uint32_t>(i), q);
    }
  }
  for (int p = 1; p <= kProducts; ++p) {
    hg::risk_state::QuotaEntry q;
    q.limit = 1000000000;
    q.unit = "LOT";
    b.putQuota(QuotaLayer::kAccountProduct, 0, static_cast<std::uint32_t>(p), 0, q);
  }
  for (int s = 1; s <= kSubAccounts; ++s) {
    for (int i = 1; i <= kInstruments; ++i) {
      PositionLimitEntry pl;
      pl.limit = 1000000000;
      pl.unit = "LOT";
      b.putPositionLimit(static_cast<std::uint32_t>(s),
                         static_cast<std::uint32_t>(((i - 1) % kProducts) + 1),
                         static_cast<std::uint32_t>(i), pl);
    }
  }
  for (int s = 1; s <= kSubAccounts; ++s) {
    for (int p = 1; p <= kWhitelistPerSub; ++p) {
      hg::risk_state::ExposureEntry e;
      e.exposure_qty = 1000000000;
      e.contract_unexecuted_qty = 1000000000;
      e.snapshot_time_ms = 123456;
      e.unit_convert_factor = 10000;
      b.putExposure(static_cast<std::uint32_t>(s), static_cast<std::uint32_t>(p),
                    ExposureDirection::kBuy, e);
      b.putExposure(static_cast<std::uint32_t>(s), static_cast<std::uint32_t>(p),
                    ExposureDirection::kSell, e);
    }
  }
  for (int s = 1; s <= kSubAccounts; ++s) {
    PermissionEntry p;
    p.allow_all_sub_accounts = true;
    p.allow_all_products = true;
    p.has_trading_post = true;
    b.putPermission(static_cast<std::uint32_t>(100 + s), p);
  }
  PlanEntry plan;
  plan.sub_account_id = 1;
  plan.product_id = 1;
  plan.status = PlanStatus::kActive;
  plan.term_start_date = 20260101;
  plan.term_end_date = 20260601;
  b.putPlan(1000, plan);
  return b;
}

}  // namespace

int main() {
  RiskState st;
  hg::risk_engine::testutil::FakeClock clk(20260101);
  RiskEngine eng(&clk);
  const hg::risk_state::RebuildResult rr = st.rebuild(realisticBatch());
  if (!rr.ok) {
    std::printf("bench_risk_check: rebuild FAILED code=%d\n", rr.code);
    return 1;
  }
  eng.applySnapshot(st);  // acquire() 强保护

  OrderRequest q;
  q.client_order_ref = 1;
  q.trade_plan_id = 1000;
  q.related_plan_id = 1000;        // T-173：关联对象（plan 1000 @ sub=1）
  q.related_exposure_id = 5;
  q.sub_account_id = 1;
  q.product_id = 1;
  q.instrument_id = 1;
  q.direction = hg::risk_engine::OrderDirection::kBuy;
  q.offset = hg::risk_engine::OffsetFlag::kOpen;
  q.price_type = hg::risk_engine::PriceType::kLimit;
  q.limit_price = 1000000;
  q.prev_settlement_price = 1000000;
  q.volume = 10000;
  q.operator_id = 101;
  q.open_date = 20260101;
  q.term_end_date = 20260601;
  q.year = 2026;

  // 预热
  for (int i = 0; i < 1000; ++i) {
    const RiskResult w = eng.check(q);
    if (w.decision != RiskDecision::kPass) {
      std::printf("bench_risk_check: unexpected decision in warmup\n");
      return 1;
    }
  }

  std::vector<long long> ns;
  ns.reserve(kIterations);
  for (int i = 0; i < kIterations; ++i) {
    const std::chrono::steady_clock::time_point t0 = std::chrono::steady_clock::now();
    const RiskResult r = eng.check(q);
    const std::chrono::steady_clock::time_point t1 = std::chrono::steady_clock::now();
    if (r.decision != RiskDecision::kPass) {
      std::printf("bench_risk_check: unexpected decision at i=%d\n", i);
      return 1;
    }
    ns.push_back(std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count());
  }
  std::sort(ns.begin(), ns.end());
  const std::size_t n = ns.size();
  const long long p50 = ns[(n * 50) / 100];
  const long long p99 = ns[(n * 99) / 100];
  const long long p999 = ns[(n * 999) / 1000];
  const long long worst = ns[n - 1];
  std::printf(
      "bench_risk_check: iters=%d  P50=%.3f us  P99=%.3f us  P99.9=%.3f us  max=%.3f us\n",
      kIterations, static_cast<double>(p50) / 1000.0, static_cast<double>(p99) / 1000.0,
      static_cast<double>(p999) / 1000.0, static_cast<double>(worst) / 1000.0);

  const long long budget_ns = 5LL * 1000LL * 1000LL;  // 5 ms
  if (p99 > budget_ns) {
    std::printf("bench_risk_check: FAIL P99=%.3f ms > 5 ms\n",
                static_cast<double>(p99) / 1000000.0);
    return 1;
  }
  std::printf("bench_risk_check: PASS P99 <= 5 ms\n");
  return 0;
}
