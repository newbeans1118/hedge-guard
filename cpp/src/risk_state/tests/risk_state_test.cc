// T-034 重建与校验单测（ARCHITECTURE.md §3.4 / §4.4.3）
//   · rebuild() 构建 → 校验 → 原子替换；校验失败 / 版本倒退 ⇒ 不发布
//   · 全量（replace_all）与增量（基于旧快照深拷贝）语义
//   · FR-RISK-9 就绪判定与加载条数 / 版本标识
#include "hg/risk_state/risk_state.h"

#include "risk_state_test_util.h"

namespace {

using hg::risk_state::AnnualScaleEntry;
using hg::risk_state::Category;
using hg::risk_state::LoadBatch;
using hg::risk_state::PlanStatus;
using hg::risk_state::PositionDirection;
using hg::risk_state::PositionEntry;
using hg::risk_state::PositionType;
using hg::risk_state::ProductStatus;
using hg::risk_state::QuotaEntry;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RiskState;
using hg::risk_state::ScaleScope;
using hg::risk_state::VarietyMeta;
using hg::risk_state::qtyFromWhole;
using hg::risk_state::ratioFromPpm;

VarietyMeta variety(std::uint32_t id, ProductStatus st, hg::risk_state::Fixed factor) {
  VarietyMeta v;
  v.product_id = id;
  v.status = st;
  v.convert_factor = factor;
  v.category = Category::kCommodity;
  return v;
}

LoadBatch fullBatchWithProduct(std::uint64_t ver, std::uint32_t product_id) {
  LoadBatch b;
  b.set_replace_all(true);
  b.set_param_version(ver);
  b.putVariety(variety(product_id, ProductStatus::kEnabled, qtyFromWhole(5)));
  QuotaEntry q;
  q.limit = qtyFromWhole(10);
  q.unit = "LOT";
  b.putQuota(QuotaLayer::kSubProduct, 1, product_id, 0, q);
  return b;
}

void test_ready_and_record_count() {
  RiskState st;
  HG_CHECK(!st.ready());
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(0));

  const hg::risk_state::RebuildResult r = st.rebuild(fullBatchWithProduct(1, 1));
  HG_CHECK(r.ok);
  HG_CHECK(st.ready());
  HG_CHECK(r.record_count >= 2);  // 至少 1 品种 + 1 额度
  HG_CHECK_EQ(st.snapshot().record_count(), r.record_count);
}

void test_version_monotonic_rejected() {
  RiskState st;
  HG_CHECK(st.rebuild(fullBatchWithProduct(5, 1)).ok);

  // 版本倒退 ⇒ 拒绝、不发布（后发不得先至，FR-RISK-10 验收 3）
  const hg::risk_state::RebuildResult r = st.rebuild(fullBatchWithProduct(3, 2));
  HG_CHECK(!r.ok);
  HG_CHECK_EQ(r.code, hg::risk_state::kRebuildVersionNotMonotonic);
  HG_CHECK_EQ(st.snapshot().param_version(), static_cast<std::uint64_t>(5));  // 未变
  HG_CHECK(st.snapshot().findVariety(2) == nullptr);                          // product 2 未落地

  // 同版本幂等重放 ⇒ 允许（>= 语义）
  HG_CHECK(st.rebuild(fullBatchWithProduct(5, 1)).ok);
}

void test_invariant_rejected_not_published() {
  RiskState st;
  HG_CHECK(st.rebuild(fullBatchWithProduct(1, 1)).ok);
  const std::size_t before = st.snapshot().record_count();

  // ① 负额度
  {
    LoadBatch b = fullBatchWithProduct(2, 1);
    QuotaEntry bad;
    bad.limit = -qtyFromWhole(1);
    b.putQuota(QuotaLayer::kSubProduct, 1, 1, 0, bad);
    const hg::risk_state::RebuildResult r = st.rebuild(b);
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, hg::risk_state::kRebuildNegativeValue);
  }
  // ② 持仓不自洽（可用量 > 持仓量）
  {
    LoadBatch b = fullBatchWithProduct(2, 1);
    PositionEntry p;
    p.position_qty = qtyFromWhole(10);
    p.available_qty = qtyFromWhole(11);
    b.putPosition(1, 100, PositionDirection::kLong, PositionType::kToday, p);
    const hg::risk_state::RebuildResult r = st.rebuild(b);
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, hg::risk_state::kRebuildPositionInconsistent);
  }
  // ③ 红线比例越界
  {
    LoadBatch b = fullBatchWithProduct(2, 1);
    AnnualScaleEntry a;
    a.scale_ratio_limit = ratioFromPpm(1200000);  // 1.2 > 1
    b.putAnnualScale(ScaleScope::kSubAccount, 1, 1, 2026, a);
    const hg::risk_state::RebuildResult r = st.rebuild(b);
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, hg::risk_state::kRebuildInvalidRatio);
  }
  // ④ 启用品种缺换算系数
  {
    LoadBatch b;
    b.set_replace_all(true);
    b.set_param_version(2);
    b.putVariety(variety(1, ProductStatus::kEnabled, 0));  // factor = 0
    const hg::risk_state::RebuildResult r = st.rebuild(b);
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, hg::risk_state::kRebuildMissingConvertFactor);
  }

  // 四次失败均未发布：版本与内容保持 v1
  HG_CHECK_EQ(st.snapshot().param_version(), static_cast<std::uint64_t>(1));
  HG_CHECK_EQ(st.snapshot().record_count(), before);
  HG_CHECK(!st.last_result().ok);
  HG_CHECK_EQ(st.last_result().code, hg::risk_state::kRebuildMissingConvertFactor);
}

void test_incremental_merge_and_replace_all() {
  RiskState st;
  HG_CHECK(st.rebuild(fullBatchWithProduct(1, 1)).ok);

  // 增量：不设 replace_all ⇒ 以旧快照深拷贝为基底 + 覆盖/新增
  LoadBatch inc;
  inc.set_param_version(2);
  inc.putVariety(variety(2, ProductStatus::kEnabled, qtyFromWhole(5)));
  QuotaEntry q;
  q.limit = qtyFromWhole(20);
  q.unit = "LOT";
  inc.putQuota(QuotaLayer::kSubProduct, 1, 2, 0, q);
  HG_CHECK(st.rebuild(inc).ok);
  HG_CHECK(st.snapshot().findVariety(1) != nullptr);  // 旧数据保留
  HG_CHECK(st.snapshot().findVariety(2) != nullptr);  // 新数据合入
  HG_CHECK_EQ(st.snapshot().findQuota(QuotaLayer::kSubProduct, 1, 2, 0)->limit, qtyFromWhole(20));

  // 全量：replace_all ⇒ 清空旧数据，仅保留本批
  HG_CHECK(st.rebuild(fullBatchWithProduct(3, 3)).ok);
  HG_CHECK(st.snapshot().findVariety(1) == nullptr);  // 旧品种被清
  HG_CHECK(st.snapshot().findVariety(2) == nullptr);
  HG_CHECK(st.snapshot().findVariety(3) != nullptr);
}

void test_rebuild_code_names() {
  HG_CHECK_EQ(std::string(hg::risk_state::rebuildCodeName(hg::risk_state::kRebuildOk)),
              std::string("OK"));
  HG_CHECK(hg::risk_state::rebuildCodeName(hg::risk_state::kRebuildVersionNotMonotonic) != nullptr);
}

void test_stats_after_rebuilds() {
  RiskState st;
  st.rebuild(fullBatchWithProduct(1, 1));
  st.rebuild(fullBatchWithProduct(2, 1));
  st.rebuild(fullBatchWithProduct(3, 1));
  HG_CHECK(st.stats().publish_count >= 3);
  HG_CHECK(st.ready());
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(3));
}

}  // namespace

int main() {
  test_ready_and_record_count();
  test_version_monotonic_rejected();
  test_invariant_rejected_not_published();
  test_incremental_merge_and_replace_all();
  test_rebuild_code_names();
  test_stats_after_rebuilds();
  return hg::risk_state::testutil::report("risk_state_test");
}
