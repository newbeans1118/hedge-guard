// T-038 参数生效会话单测（risk_state 侧，零 protobuf 依赖）。
// 覆盖：版本门卫（验收 2 严格 >）/ 分批受理与收齐生效（验收 3）/ 批序与一致性守卫 /
//       影子预演失败不改快照。
#include "hg/risk_state/rebuild.h"

#include "hg/risk_state/model.h"
#include "risk_state_test_util.h"

namespace {

using hg::risk_state::kSessionBatchInconsistent;
using hg::risk_state::kSessionBatchMissing;
using hg::risk_state::kSessionOk;
using hg::risk_state::kSessionRebuildFailed;
using hg::risk_state::kSessionVersionStale;
using hg::risk_state::LoadBatch;
using hg::risk_state::ParamRebuildSession;
using hg::risk_state::QuotaEntry;
using hg::risk_state::QuotaLayer;
using hg::risk_state::SessionResult;
using hg::risk_state::VarietyMeta;
using hg::risk_state::qtyFromWhole;

// 最小合法品种批（含 1 品种 + 1 额度，可过不变量）。
LoadBatch varietyBatch(std::uint64_t ver, std::uint32_t product) {
  LoadBatch b;
  b.set_param_version(ver);
  VarietyMeta v;
  v.product_id = product;
  hg::risk_state::ProductStatus st = hg::risk_state::ProductStatus::kEnabled;
  v.status = st;
  v.convert_factor = qtyFromWhole(5);
  v.category = hg::risk_state::Category::kCommodity;
  b.putVariety(v);
  QuotaEntry q;
  q.limit = qtyFromWhole(10);
  q.unit = "LOT";
  b.putQuota(QuotaLayer::kSubProduct, 1, product, 0, q);
  return b;
}

// ── 验收 2：增量版本门卫（严格 >）──────────────────────────────
void test_incremental_version_guard() {
  hg::risk_state::RiskState st;
  ParamRebuildSession s(&st);

  // 空快照（current=0）⇒ 版本 0 拒（0 不 > 0），非 0 受理。
  {
    const SessionResult r = s.submitIncremental(varietyBatch(0, 1));
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, kSessionVersionStale);
  }
  HG_CHECK(s.submitIncremental(varietyBatch(1, 1)).ok);
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(1));

  // 严格单调：同版本（==）拒绝——T-038 收紧语义（RiskState 层 >= 不动）。
  {
    const SessionResult r = s.submitIncremental(varietyBatch(1, 2));
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, kSessionVersionStale);
    HG_CHECK(st.snapshot().findVariety(2) == nullptr);  // 已丢弃
  }
  // 倒退（<）拒绝。
  {
    const SessionResult r = s.submitIncremental(varietyBatch(0, 2));
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, kSessionVersionStale);
  }
  // 更新版本受理。
  HG_CHECK(s.submitIncremental(varietyBatch(2, 2)).ok);
  HG_CHECK(st.snapshot().findVariety(2) != nullptr);  // 增量合入
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(2));
}

// ── 验收 3：分批加载（3 批）──────────────────────────────────
void test_load_batch_session() {
  hg::risk_state::RiskState st;
  ParamRebuildSession s(&st);

  {  // 第 0 批：受理、不生效
    const SessionResult r = s.submitLoadBatch(varietyBatch(10, 1), 0, 3);
    HG_CHECK(r.ok);
    HG_CHECK(!r.applied);
    HG_CHECK_EQ(r.version, static_cast<std::uint64_t>(10));
    HG_CHECK(st.snapshot().findVariety(1) == nullptr);  // 未发布
  }
  {  // 第 1 批（品种 2）
    const SessionResult r = s.submitLoadBatch(varietyBatch(10, 2), 1, 3);
    HG_CHECK(r.ok && !r.applied);
  }
  {  // 第 2 批（品种 3）＝收齐 ⇒ 生效
    const SessionResult r = s.submitLoadBatch(varietyBatch(10, 3), 2, 3);
    HG_CHECK(r.ok);
    HG_CHECK(r.applied);
    HG_CHECK_EQ(r.version, static_cast<std::uint64_t>(10));
  }
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(10));
  HG_CHECK(st.snapshot().findVariety(1) != nullptr);
  HG_CHECK(st.snapshot().findVariety(2) != nullptr);
  HG_CHECK(st.snapshot().findVariety(3) != nullptr);
  HG_CHECK(!s.load_in_progress());
}

// 分批守卫：越界 / 版本漂移 / 缺批 / 单批直达。
void test_load_batch_guards() {
  hg::risk_state::RiskState st;
  HG_CHECK(st.rebuild(varietyBatch(5, 9)).ok);  // current = 5
  ParamRebuildSession s(&st);

  // batch_total = 0
  {
    const SessionResult r = s.submitLoadBatch(varietyBatch(6, 1), 0, 0);
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, kSessionBatchInconsistent);
  }
  // batch_index 越界
  {
    const SessionResult r = s.submitLoadBatch(varietyBatch(6, 1), 2, 2);
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, kSessionBatchInconsistent);
  }
  // 会话版本须 > current
  {
    const SessionResult r = s.submitLoadBatch(varietyBatch(5, 1), 0, 2);
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, kSessionVersionStale);
  }
  // 正常开 2 批会话
  HG_CHECK(s.submitLoadBatch(varietyBatch(6, 1), 0, 2).ok);
  // 批内版本漂移
  {
    const SessionResult r = s.submitLoadBatch(varietyBatch(7, 2), 1, 2);
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, kSessionBatchInconsistent);
  }
  // 缺批（跳批）
  {
    const SessionResult r = s.submitLoadBatch(varietyBatch(6, 2), 1, 2);
    HG_CHECK(r.ok && r.applied);  // 正确补上第 1 批 ⇒ 生效
  }
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(6));

  // 单批直达（batch_total = 1）
  ParamRebuildSession s2(&st);
  {
    const SessionResult r = s2.submitLoadBatch(varietyBatch(7, 4), 0, 1);
    HG_CHECK(r.ok && r.applied);
  }
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(7));
  HG_CHECK(st.snapshot().findVariety(4) != nullptr);
}

// 影子预演失败：不变量违规 ⇒ 整会话作废、快照不变。
void test_load_batch_invariant_failure() {
  hg::risk_state::RiskState st;
  HG_CHECK(st.rebuild(varietyBatch(1, 9)).ok);
  ParamRebuildSession s(&st);
  HG_CHECK(s.submitLoadBatch(varietyBatch(2, 1), 0, 2).ok);
  // 第 1 批携带负额度 ⇒ 影子 rebuild 失败 ⇒ 整会话作废。
  LoadBatch bad = varietyBatch(2, 2);
  QuotaEntry q;
  q.limit = -qtyFromWhole(1);
  bad.putQuota(QuotaLayer::kSubProduct, 1, 2, 0, q);
  {
    const SessionResult r = s.submitLoadBatch(bad, 1, 2);
    HG_CHECK(!r.ok);
    HG_CHECK_EQ(r.code, kSessionRebuildFailed);
    HG_CHECK_EQ(r.rebuild_code, hg::risk_state::kRebuildNegativeValue);
  }
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(1));  // 未发布
  HG_CHECK(st.snapshot().findVariety(1) == nullptr);               // 第 0 批也未发布
  HG_CHECK(!s.load_in_progress());  // 会话已复位
}

}  // namespace

int main() {
  test_incremental_version_guard();
  test_load_batch_session();
  test_load_batch_guards();
  test_load_batch_invariant_failure();
  return hg::risk_state::testutil::report("risk_state.rebuild_test");
}