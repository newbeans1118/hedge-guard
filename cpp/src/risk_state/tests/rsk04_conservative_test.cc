// T-034 RSK-04 保守方向单测（ARCHITECTURE.md §9 `RSK-04`）
//   快照（上限）与 T4 已用量之间的不一致窗口，方向必须**偏保守**：
//     · 上限**调低** ⇒ 更易被拒（不会超限放行）—— 含**反例**（旧上限放行 / 新上限拒绝）
//     · 上限**调高** ⇒ **不立即生效**（需等新快照发布），绝不提前放行
//   `used`（已用量）由 T4 独占维护、**不走快照**，故本单测把 used 视为不变量。
#include "hg/risk_state/risk_state.h"

#include "risk_state_test_util.h"

namespace {

using hg::risk_state::Fixed;
using hg::risk_state::LoadBatch;
using hg::risk_state::ProductStatus;
using hg::risk_state::QuotaEntry;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RiskSnapshot;
using hg::risk_state::RiskState;
using hg::risk_state::VarietyMeta;
using hg::risk_state::exceedsLimit;
using hg::risk_state::qtyFromWhole;

LoadBatch quotaBatch(std::uint64_t ver, Fixed limit) {
  LoadBatch b;
  b.set_replace_all(true);
  b.set_param_version(ver);
  VarietyMeta v;
  v.product_id = 1;
  v.status = ProductStatus::kEnabled;
  v.convert_factor = qtyFromWhole(1);
  b.putVariety(v);
  QuotaEntry q;
  q.limit = limit;
  q.used = 0;  // 快照内的 used 初值；T4 的实时 used 由本单测以外部常量模拟
  q.unit = "LOT";
  b.putQuota(QuotaLayer::kSubProduct, 1, 1, 0, q);
  return b;
}

Fixed currentLimit(const RiskSnapshot& s) {
  const QuotaEntry* q = s.findQuota(QuotaLayer::kSubProduct, 1, 1, 0);
  return q == nullptr ? 0 : q->limit;
}

// 上限调低 ⇒ 更易拒（含反例：同一 (used, req) 在旧上限放行、在新上限被拒）
void test_lower_limit_rejects_more() {
  RiskState st;
  HG_CHECK(st.rebuild(quotaBatch(1, qtyFromWhole(100))).ok);

  const Fixed used = qtyFromWhole(30);
  const Fixed req = qtyFromWhole(20);  // used+req = 50
  // 旧上限 100：放行
  HG_CHECK(!exceedsLimit(currentLimit(st.snapshot()), used, req));

  // 发布下调后的上限 40（模拟限额下调已下发）
  HG_CHECK(st.rebuild(quotaBatch(2, qtyFromWhole(40))).ok);
  // 新上限 40：同一请求 now 超限 ⇒ 更易拒（反例成立：100→放行 / 40→拒绝）
  HG_CHECK(exceedsLimit(currentLimit(st.snapshot()), used, req));
}

// 上限调高 ⇒ 不立即生效（旧快照仍拒），新快照发布后才放行 —— 绝不提前放行
void test_raise_limit_delayed_effect() {
  RiskState st;
  HG_CHECK(st.rebuild(quotaBatch(1, qtyFromWhole(40))).ok);

  const Fixed used = qtyFromWhole(30);
  const Fixed req = qtyFromWhole(20);
  HG_CHECK(exceedsLimit(currentLimit(st.snapshot()), used, req));  // 旧上限拒绝

  // 持旧快照句柄，然后发布「上限调高到 100」
  hg::risk_state::SnapshotManager::ReadGuard old_snap = st.acquire();
  HG_CHECK(st.rebuild(quotaBatch(2, qtyFromWhole(100))).ok);

  // 旧快照（调高前）仍拒绝 ⇒ 调高不得提前放行
  HG_CHECK(exceedsLimit(currentLimit(old_snap.get()), used, req));
  // 新快照（发布后）才放行 ⇒ 延迟生效
  HG_CHECK(!exceedsLimit(currentLimit(st.snapshot()), used, req));
}

// 「不会超限放行」：exceedsLimit 关于 limit 单调不增 ⇒ 不存在「调低后被放行」的反例
void test_monotonic_no_over_limit_pass() {
  const Fixed used = qtyFromWhole(30);
  const Fixed req = qtyFromWhole(25);  // used+req = 55
  const Fixed limits[] = {qtyFromWhole(100), qtyFromWhole(80), qtyFromWhole(56), qtyFromWhole(55),
                          qtyFromWhole(54), qtyFromWhole(40)};
  const std::size_t n = sizeof(limits) / sizeof(limits[0]);
  for (std::size_t i = 0; i < n; ++i) {
    for (std::size_t j = 0; j < n; ++j) {
      if (limits[j] <= limits[i]) {
        // 若较大上限已超限，则较小上限必超限（方向偏保守：调低只会更严）
        if (exceedsLimit(limits[i], used, req)) {
          HG_CHECK(exceedsLimit(limits[j], used, req));
        }
      }
    }
  }
  // 边界：恰好等于上限不放行（> 判定），低于上限放行
  HG_CHECK(!exceedsLimit(qtyFromWhole(60), used, req));  // 55 <= 60
  HG_CHECK(!exceedsLimit(qtyFromWhole(55), used, req));  // 55 == 55
  HG_CHECK(exceedsLimit(qtyFromWhole(54), used, req));   // 55 > 54
}

// 经真实快照替换的多轮下调：占用率单调升高（每轮拒绝面不小于上一轮）
void test_multi_round_tightening_is_monotone() {
  RiskState st;
  HG_CHECK(st.rebuild(quotaBatch(1, qtyFromWhole(100))).ok);
  const Fixed used = qtyFromWhole(30);
  const Fixed req = qtyFromWhole(60);  // used+req = 90

  int rejects = 0;
  const Fixed lowers[] = {qtyFromWhole(100), qtyFromWhole(90), qtyFromWhole(80),
                          qtyFromWhole(60)};
  std::uint64_t ver = 1;
  int prev_rejects = 0;
  for (std::size_t i = 0; i < sizeof(lowers) / sizeof(lowers[0]); ++i) {
    if (i > 0) {
      ++ver;
      HG_CHECK(st.rebuild(quotaBatch(ver, lowers[i])).ok);
    }
    const bool rej = exceedsLimit(currentLimit(st.snapshot()), used, req);
    if (rej) ++rejects;
    HG_CHECK(rejects >= prev_rejects);  // 拒绝面单调不减
    prev_rejects = rejects;
  }
  HG_CHECK_EQ(rejects, 2);  // used+req=90：100→否、90→否、80→是、60→是
}

}  // namespace

int main() {
  test_lower_limit_rejects_more();
  test_raise_limit_delayed_effect();
  test_monotonic_no_over_limit_pass();
  test_multi_round_tightening_is_monotone();
  return hg::risk_state::testutil::report("rsk04_conservative_test");
}
