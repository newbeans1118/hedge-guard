// T-034 数据面齐备单测（ARCHITECTURE.md §4.4.1 的 13 类常驻内存数据集）
#include "hg/risk_state/risk_state.h"

#include "risk_state_test_util.h"

namespace {

using hg::risk_state::AnnualScaleEntry;
using hg::risk_state::CalcMode;
using hg::risk_state::Category;
using hg::risk_state::ExposureDirection;
using hg::risk_state::ExposureEntry;
using hg::risk_state::InstrumentMeta;
using hg::risk_state::InstrumentType;
using hg::risk_state::LoadBatch;
using hg::risk_state::PermissionEntry;
using hg::risk_state::PlanEntry;
using hg::risk_state::PlanStatus;
using hg::risk_state::PositionDirection;
using hg::risk_state::PositionEntry;
using hg::risk_state::PositionLimitEntry;
using hg::risk_state::PositionType;
using hg::risk_state::ProductStatus;
using hg::risk_state::QuotaEntry;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RiskSnapshot;
using hg::risk_state::RiskState;
using hg::risk_state::ScaleScope;
using hg::risk_state::VarietyMeta;
using hg::risk_state::kQtyScale;
using hg::risk_state::qtyFromWhole;
using hg::risk_state::ratioFromPpm;

// 覆盖 §4.4.1 全部 13 类数据集的满载批次（全量：replace_all = true）。
LoadBatch makeFullBatch() {
  LoadBatch b;
  b.set_replace_all(true);
  b.set_param_version(10);
  b.set_build_time_ms(1000);

  // ① 品种档案（TB-PRODUCT-1/2）
  VarietyMeta v1;
  v1.product_id = 1;
  v1.code = "cu";
  v1.name = "铜";
  v1.category = Category::kCommodity;
  v1.category_managed = true;
  v1.status = ProductStatus::kEnabled;
  v1.convert_factor = qtyFromWhole(5);
  v1.convert_desc = "1 手 = 5 吨";
  b.putVariety(v1);
  VarietyMeta v2;
  v2.product_id = 2;
  v2.code = "al";
  v2.name = "铝";
  v2.category = Category::kTrade;
  v2.status = ProductStatus::kEnabled;
  v2.convert_factor = qtyFromWhole(5);
  b.putVariety(v2);

  // ② 合约档案（TB-PRODUCT-3）
  InstrumentMeta m;
  m.instrument_id = 101;
  m.product_id = 1;
  m.instrument_code = "cu2612";
  m.type = InstrumentType::kFutures;
  m.exchange = "SHFE";
  m.contract_multiplier = qtyFromWhole(5);
  m.expiry_date = 20261215;
  b.putInstrument(m);

  // ③ 品种白名单（TB-PRODUCT-5）+ 品种级分类覆盖
  b.addWhitelist(7, 1);
  b.addWhitelist(7, 2, Category::kTrade);

  // ④ 三层额度（TB-PRODUCT-6 层①/层③；TB-PRODUCT-8 层②）
  QuotaEntry q1;
  q1.limit = qtyFromWhole(100);
  q1.used = qtyFromWhole(30);
  q1.frozen = qtyFromWhole(5);
  q1.unit = "LOT";
  b.putQuota(QuotaLayer::kSubProduct, 7, 1, 0, q1);
  QuotaEntry q2 = q1;
  q2.limit = qtyFromWhole(50);
  b.putQuota(QuotaLayer::kSubInstrument, 7, 1, 101, q2);
  QuotaEntry q3;
  q3.limit = qtyFromWhole(1000);
  q3.used = qtyFromWhole(300);
  q3.unit = "LOT";
  b.putQuota(QuotaLayer::kAccountProduct, 0, 1, 0, q3);  // 层③：账户 + 品种

  // ⑤ 持仓（TB-TRADE-5）
  PositionEntry p;
  p.position_qty = qtyFromWhole(20);
  p.available_qty = qtyFromWhole(12);
  p.frozen_qty = qtyFromWhole(3);
  p.as_of_time_ms = 1000;
  b.putPosition(7, 101, PositionDirection::kLong, PositionType::kToday, p);

  // ⑥ 持仓上限（ARCH §4.4.1 PositionLimit）
  PositionLimitEntry pl;
  pl.limit = qtyFromWhole(200);
  pl.unit = "LOT";
  b.putPositionLimit(7, 1, 101, pl);

  // ⑦ 实货敞口（TB-SPOT-8）：三分量 + snapshot_time + snapshot_id
  ExposureEntry e;
  e.contract_unexecuted_qty = qtyFromWhole(40);
  e.inventory_qty = qtyFromWhole(25);
  e.production_plan_qty = qtyFromWhole(35);
  e.exposure_qty = qtyFromWhole(100);
  e.unit_convert_factor = qtyFromWhole(5);
  e.snapshot_time_ms = 123456789;
  e.snapshot_id = "EXPO-2026-0001";
  e.calc_mode = CalcMode::kCombined;
  e.as_of_date = 20260920;
  b.putExposure(7, 1, ExposureDirection::kBuy, e);

  // ⑧ 年度规模基数（TB-RISK-11）：子账户 + 全账户（Q-1 双算）
  AnnualScaleEntry a;
  a.expected_spot_business_qty = qtyFromWhole(10000);
  a.accumulated_hedge_qty = qtyFromWhole(7000);
  a.category = Category::kCommodity;
  a.scale_ratio_limit = ratioFromPpm(900000);
  a.scope = ScaleScope::kSubAccount;
  b.putAnnualScale(ScaleScope::kSubAccount, 7, 1, 2026, a);
  AnnualScaleEntry at = a;
  at.scope = ScaleScope::kTotalAccount;
  at.accumulated_hedge_qty = qtyFromWhole(8000);
  b.putAnnualScale(ScaleScope::kTotalAccount, 0, 1, 2026, at);

  // ⑨ 子账户 / 品种级分类
  b.putSubAccountCategory(7, Category::kCommodity);
  b.putSubProductCategory(7, 2, Category::kTrade);

  // ⑩ 权限（TB-AUTH-7/8）
  PermissionEntry perm;
  perm.sub_accounts.insert(7);
  perm.products.insert(1);
  perm.products.insert(2);
  perm.individual_quota = qtyFromWhole(500);
  perm.has_trading_post = true;
  b.putPermission(9001, perm);
  PermissionEntry resp;
  resp.is_responsible = true;  // 负责人标记 ⇒ 禁授交易岗（NFR-SEC-5 兜底）
  resp.has_trading_post = true;
  b.putPermission(9002, resp);

  // ⑪ 阈值（TB-RISK-1）
  b.putThreshold("risk.scale.ratio.commodity", "0.900000");
  b.putThreshold("risk.price.warn_pct", "0.020000");
  b.putThreshold("risk.price.block_pct", "0.050000");
  b.putThreshold("risk.term.months", "6");
  b.putThreshold("risk.trading.suspend", "false");

  // ⑫ 总账户限额（ARCH §4.4.1 TotalLimits）
  b.putTotalLimit(1, q3);
  QuotaEntry tot;
  tot.limit = qtyFromWhole(5000);
  tot.used = qtyFromWhole(1500);
  tot.unit = "LOT";
  b.setAccountTotalLimit(tot);

  // ⑬ 计划判定子集（TB-PLAN-1）
  PlanEntry plan;
  plan.sub_account_id = 7;
  plan.product_id = 1;
  plan.status = PlanStatus::kActive;
  plan.term_start_date = 20260101;
  plan.term_end_date = 20261231;
  plan.plan_qty = qtyFromWhole(80);
  plan.ordered_qty = qtyFromWhole(20);
  b.putPlan(5001, plan);

  return b;
}

void test_all_datasets_present() {
  RiskState st;
  HG_CHECK(!st.ready());  // 空快照 ⇒ 未就绪
  const hg::risk_state::RebuildResult r = st.rebuild(makeFullBatch());
  HG_CHECK(r.ok);
  HG_CHECK_EQ(r.code, hg::risk_state::kRebuildOk);
  HG_CHECK_EQ(r.version, static_cast<std::uint64_t>(10));
  HG_CHECK(st.ready());
  HG_CHECK(r.record_count > 0);

  const RiskSnapshot& s = st.snapshot();
  HG_CHECK_EQ(s.param_version(), static_cast<std::uint64_t>(10));
  HG_CHECK_EQ(s.build_time_ms(), static_cast<std::int64_t>(1000));
  HG_CHECK_EQ(s.record_count(), s.countRecords());

  // ① 品种
  const VarietyMeta* v = s.findVariety(1);
  HG_CHECK(v != nullptr);
  if (v != nullptr) {
    HG_CHECK_EQ(v->category, Category::kCommodity);
    HG_CHECK_EQ(v->status, ProductStatus::kEnabled);
    HG_CHECK_EQ(v->convert_factor, qtyFromWhole(5));
    HG_CHECK_EQ(v->code, std::string("cu"));
  }
  // ② 合约
  const InstrumentMeta* m = s.findInstrument(101);
  HG_CHECK(m != nullptr);
  if (m != nullptr) {
    HG_CHECK_EQ(m->product_id, static_cast<std::uint32_t>(1));
    HG_CHECK_EQ(m->type, InstrumentType::kFutures);
    HG_CHECK_EQ(m->contract_multiplier, qtyFromWhole(5));
  }
  // ③ 白名单
  HG_CHECK(s.isWhitelisted(7, 1));
  HG_CHECK(s.isWhitelisted(7, 2));
  HG_CHECK(!s.isWhitelisted(7, 3));
  HG_CHECK_EQ(s.whitelistCategoryOverride(7, 2), Category::kTrade);
  HG_CHECK_EQ(s.whitelistCategoryOverride(7, 1), Category::kUnknown);
  // ④ 三层额度
  const QuotaEntry* l1 = s.findQuota(QuotaLayer::kSubProduct, 7, 1, 0);
  HG_CHECK(l1 != nullptr);
  if (l1 != nullptr) {
    HG_CHECK_EQ(l1->limit, qtyFromWhole(100));
    HG_CHECK_EQ(l1->used, qtyFromWhole(30));
  }
  const QuotaEntry* l2 = s.findQuota(QuotaLayer::kSubInstrument, 7, 1, 101);
  HG_CHECK(l2 != nullptr);
  if (l2 != nullptr) HG_CHECK_EQ(l2->limit, qtyFromWhole(50));
  const QuotaEntry* l3 = s.findQuota(QuotaLayer::kAccountProduct, 0, 1, 0);
  HG_CHECK(l3 != nullptr);
  if (l3 != nullptr) HG_CHECK_EQ(l3->limit, qtyFromWhole(1000));
  // ⑤ 持仓
  const PositionEntry* pos = s.findPosition(7, 101, PositionDirection::kLong, PositionType::kToday);
  HG_CHECK(pos != nullptr);
  if (pos != nullptr) {
    HG_CHECK_EQ(pos->position_qty, qtyFromWhole(20));
    HG_CHECK_EQ(pos->available_qty, qtyFromWhole(12));
  }
  // ⑥ 持仓上限
  const PositionLimitEntry* lim = s.findPositionLimit(7, 1, 101);
  HG_CHECK(lim != nullptr);
  if (lim != nullptr) HG_CHECK_EQ(lim->limit, qtyFromWhole(200));
  // ⑦ 敞口（三分量 + snapshot_time）
  const ExposureEntry* e = s.findExposure(7, 1, ExposureDirection::kBuy);
  HG_CHECK(e != nullptr);
  if (e != nullptr) {
    HG_CHECK_EQ(e->contract_unexecuted_qty, qtyFromWhole(40));
    HG_CHECK_EQ(e->inventory_qty, qtyFromWhole(25));
    HG_CHECK_EQ(e->production_plan_qty, qtyFromWhole(35));
    HG_CHECK_EQ(e->exposure_qty, qtyFromWhole(100));
    HG_CHECK_EQ(e->snapshot_time_ms, static_cast<std::int64_t>(123456789));
    HG_CHECK_EQ(e->snapshot_id, std::string("EXPO-2026-0001"));
  }
  // ⑧ 年度规模基数（双算）
  const AnnualScaleEntry* a1 = s.findAnnualScale(ScaleScope::kSubAccount, 7, 1, 2026);
  HG_CHECK(a1 != nullptr);
  if (a1 != nullptr) {
    HG_CHECK_EQ(a1->expected_spot_business_qty, qtyFromWhole(10000));
    HG_CHECK_EQ(a1->accumulated_hedge_qty, qtyFromWhole(7000));
    HG_CHECK_EQ(a1->scale_ratio_limit, ratioFromPpm(900000));
  }
  const AnnualScaleEntry* a2 = s.findAnnualScale(ScaleScope::kTotalAccount, 0, 1, 2026);
  HG_CHECK(a2 != nullptr);
  if (a2 != nullptr) HG_CHECK_EQ(a2->accumulated_hedge_qty, qtyFromWhole(8000));
  // ⑨ 分类
  HG_CHECK_EQ(s.effectiveCategory(7, 1), Category::kCommodity);  // 子账户分类
  HG_CHECK_EQ(s.effectiveCategory(7, 2), Category::kTrade);      // 品种级覆盖
  // ⑩ 权限
  const PermissionEntry* perm = s.findPermission(9001);
  HG_CHECK(perm != nullptr);
  if (perm != nullptr) HG_CHECK_EQ(perm->individual_quota, qtyFromWhole(500));
  HG_CHECK(s.canTrade(9001, 7, 1));
  HG_CHECK(!s.canTrade(9001, 8, 1));   // 未授权子账户
  HG_CHECK(!s.canTrade(9001, 7, 3));   // 未授权品种
  HG_CHECK(!s.canTrade(9002, 7, 1));   // 负责人标记 ⇒ 禁交易
  HG_CHECK(!s.canTrade(9999, 7, 1));   // 无授权记录 ⇒ 安全默认不可交易
  // ⑪ 阈值
  HG_CHECK(s.hasThreshold("risk.price.block_pct"));
  HG_CHECK_EQ(s.thresholdFixed("risk.price.block_pct", 0), ratioFromPpm(50000));
  HG_CHECK_EQ(s.thresholdFixed("risk.price.warn_pct", 0), ratioFromPpm(20000));
  HG_CHECK_EQ(s.thresholdInt("risk.term.months", 0), static_cast<std::int64_t>(6));
  HG_CHECK_EQ(s.thresholdBool("risk.trading.suspend", true), false);
  HG_CHECK_EQ(s.thresholdInt("no.such.key", 42), static_cast<std::int64_t>(42));  // fallback
  // ⑫ 总账户限额
  HG_CHECK_EQ(s.accountTotalLimit().limit, qtyFromWhole(5000));
  const QuotaEntry* tp = s.findTotalLimitByProduct(1);
  HG_CHECK(tp != nullptr);
  if (tp != nullptr) HG_CHECK_EQ(tp->limit, qtyFromWhole(1000));
  // ⑬ 计划
  std::unordered_map<std::uint64_t, PlanEntry>::const_iterator it = s.plans().find(5001);
  HG_CHECK(it != s.plans().end());
  if (it != s.plans().end()) HG_CHECK_EQ(it->second.status, PlanStatus::kActive);
}

void test_qty_scale_sanity() {
  HG_CHECK_EQ(qtyFromWhole(1), static_cast<hg::risk_state::Fixed>(kQtyScale));
  HG_CHECK_EQ(ratioFromPpm(900000), static_cast<hg::risk_state::Fixed>(900000));
}

}  // namespace

int main() {
  test_qty_scale_sanity();
  test_all_datasets_present();
  return hg::risk_state::testutil::report("data_model_test");
}
