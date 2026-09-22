#ifndef HG_RISK_ENGINE_TEST_UTIL_H
#define HG_RISK_ENGINE_TEST_UTIL_H
// 极简 C++11 断言 harness（零依赖；不引入 gtest）+ 假时钟 + 数据构造器。
// 每个测试为独立可执行，返回 0=PASS / 1=FAIL，供 ctest 判读。
// 断言表达式**单次求值**（避免副作用双执行）。
//
// ⚠️ 本目录属 E4 扫描根：不得出现锁原语 / 同步 I/O 调用形态。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>

#include "hg/risk_engine/clock.h"
#include "hg/risk_engine/order_request.h"
#include "hg/risk_engine/risk_engine.h"
#include "hg/risk_state/load_batch.h"
#include "hg/risk_state/model.h"
#include "hg/risk_state/risk_state.h"

namespace hg {
namespace risk_engine {
namespace testutil {

inline int& failures() {
  static int f = 0;
  return f;
}
inline int& checks() {
  static int c = 0;
  return c;
}

inline void check_impl(bool ok, const char* expr, const char* file, int line) {
  ++checks();
  if (!ok) {
    ++failures();
    std::printf("[FAIL] %s:%d: %s\n", file, line, expr);
  }
}

inline int report(const char* name) {
  const int f = failures();
  std::printf("%s: %d checks, %d failures -> %s\n", name, checks(), f, f == 0 ? "PASS" : "FAIL");
  return f == 0 ? 0 : 1;
}

// -----------------------------------------------------------------------------
// 假时钟（可注入；NFR-TEST-4）
// -----------------------------------------------------------------------------
class FakeClock : public hg::risk_engine::IClock {
 public:
  explicit FakeClock(std::int64_t today = 20260101, std::int64_t now_ms = 1000)
      : today_(today), now_ms_(now_ms) {}
  std::int64_t nowMs() const { return now_ms_; }
  std::int64_t todayYmd() const { return today_; }
  void setToday(std::int64_t d) { today_ = d; }
  void setNowMs(std::int64_t n) { now_ms_ = n; }

 private:
  std::int64_t today_;
  std::int64_t now_ms_;
};

// -----------------------------------------------------------------------------
// 数据构造器
// -----------------------------------------------------------------------------
using hg::risk_state::AnnualScaleEntry;
using hg::risk_state::Category;
using hg::risk_state::ExposureDirection;
using hg::risk_state::ExposureEntry;
using hg::risk_state::Fixed;
using hg::risk_state::InstrumentMeta;
using hg::risk_state::InstrumentType;
using hg::risk_state::LoadBatch;
using hg::risk_state::PermissionEntry;
using hg::risk_state::PermitType;
using hg::risk_state::PlanEntry;
using hg::risk_state::PlanStatus;
using hg::risk_state::PositionDirection;
using hg::risk_state::PositionEntry;
using hg::risk_state::PositionLimitEntry;
using hg::risk_state::PositionType;
using hg::risk_state::ProductStatus;
using hg::risk_state::QuotaEntry;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RiskState;
using hg::risk_state::ScaleScope;
using hg::risk_state::VarietyMeta;

inline VarietyMeta variety(std::uint32_t id, Category cat, Fixed convert_factor) {
  VarietyMeta v;
  v.product_id = id;
  v.category = cat;
  v.status = ProductStatus::kEnabled;
  v.convert_factor = convert_factor;
  return v;
}

inline InstrumentMeta instrument(std::uint32_t id, std::uint32_t product, Fixed cm,
                                 const std::string& exch) {
  InstrumentMeta m;
  m.instrument_id = id;
  m.product_id = product;
  m.contract_multiplier = cm;
  m.exchange = exch;
  m.type = InstrumentType::kFutures;
  m.expiry_date = 20261231;
  return m;
}

inline QuotaEntry quota(Fixed limit, Fixed used, Fixed frozen, const std::string& unit) {
  QuotaEntry q;
  q.limit = limit;
  q.used = used;
  q.frozen = frozen;
  q.unit = unit;
  return q;
}

inline PositionLimitEntry posLimit(Fixed limit) {
  PositionLimitEntry p;
  p.limit = limit;
  p.unit = "LOT";
  return p;
}

inline ExposureEntry exposure(Fixed qty, std::int64_t snapshot_time_ms) {
  ExposureEntry e;
  e.exposure_qty = qty;
  e.contract_unexecuted_qty = qty;
  e.snapshot_time_ms = snapshot_time_ms;
  e.unit_convert_factor = 10000;
  e.calc_mode = hg::risk_state::CalcMode::kCombined;
  return e;
}

// 基础合法批次：sub=1 / product=10 / instrument=100 / operator=7 / plan=1000
inline LoadBatch happyBatch(std::uint64_t ver) {
  LoadBatch b;
  b.set_replace_all(true);
  b.set_param_version(ver);
  b.set_build_time_ms(1000);
  b.putVariety(variety(10, Category::kCommodity, 10000 /* 1.0 */));
  b.putInstrument(instrument(100, 10, 10000 /* 1.0 */, "SHFE"));
  b.addWhitelist(1, 10);
  b.putQuota(QuotaLayer::kSubProduct, 1, 10, 0, quota(1000000, 0, 0, "LOT"));
  b.putQuota(QuotaLayer::kSubInstrument, 1, 10, 100, quota(1000000, 0, 0, "LOT"));
  b.putQuota(QuotaLayer::kAccountProduct, 0, 10, 0, quota(1000000, 0, 0, "LOT"));
  b.putPositionLimit(1, 10, 100, posLimit(1000000));
  b.putExposure(1, 10, ExposureDirection::kBuy, exposure(1000000, 555));
  b.putExposure(1, 10, ExposureDirection::kSell, exposure(1000000, 555));
  PermissionEntry p;
  p.allow_all_sub_accounts = true;
  p.allow_all_products = true;
  p.has_trading_post = true;
  b.putPermission(7, p);
  PlanEntry plan;
  plan.sub_account_id = 1;
  plan.product_id = 10;
  plan.status = PlanStatus::kActive;
  plan.term_start_date = 20260101;
  plan.term_end_date = 20260601;
  b.putPlan(1000, plan);
  return b;
}

// 基础买入开仓指令（与 happyBatch 一致，应全部通过）
inline OrderRequest baseOrder() {
  OrderRequest r;
  r.client_order_ref = 1;
  r.trade_plan_id = 1000;
  r.exposure_id = 5;
  r.sub_account_id = 1;
  r.product_id = 10;
  r.instrument_id = 100;
  r.direction = OrderDirection::kBuy;
  r.offset = OffsetFlag::kOpen;
  r.price_type = PriceType::kLimit;
  r.limit_price = 1000000;          // 1.0（kAmountScale）
  r.prev_settlement_price = 1000000;  // 与限价一致 ⇒ 偏离 0
  r.volume = 10000;                 // 1 手（kQtyScale）
  r.operator_id = 7;
  r.open_date = 20260101;
  r.term_end_date = 20260601;
  r.hedge_contract_expiry = 0;
  r.year = 2026;
  return r;
}

// 用 RiskState 构建 → 绑定引擎（保持 RiskState 存活；本函数不取得所有权）
inline bool bind(RiskState* st, RiskEngine* eng, const LoadBatch& batch) {
  const hg::risk_state::RebuildResult rr = st->rebuild(batch);
  if (!rr.ok) return false;
  eng->applySnapshot(st->snapshot());
  return true;
}

}  // namespace testutil
}  // namespace risk_engine
}  // namespace hg

#define HG_CHECK(cond) ::hg::risk_engine::testutil::check_impl((cond), #cond, __FILE__, __LINE__)
#define HG_CHECK_EQ(a, b) \
  ::hg::risk_engine::testutil::check_impl((a) == (b), #a " == " #b, __FILE__, __LINE__)

#endif  // HG_RISK_ENGINE_TEST_UTIL_H
