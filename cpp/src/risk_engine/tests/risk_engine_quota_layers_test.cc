// T-035 R-RISK-2 三层限额单测（FR-RISK-2 / ARCHITECTURE.md §4.4.1 脚注「全序 + 并列取一」）
//   用例 ① ~ ⑤ / ⑦：错误码与命中层级一一对应，单笔按「最细命中者」；entries[] 全命中项。
#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::LimitLayer;
using hg::risk_engine::OrderRequest;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::RuleId;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_engine::testutil::quota;
using hg::risk_state::LoadBatch;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RiskState;

// 以 happyBatch 为基，按层级设限额
LoadBatch quotaBatch(std::uint64_t ver, hg::risk_state::Fixed l1, hg::risk_state::Fixed l2,
                     hg::risk_state::Fixed l3) {
  LoadBatch b = happyBatch(ver);
  b.putQuota(QuotaLayer::kSubProduct, 1, 10, 0, quota(l1, 0, 0, "LOT"));
  b.putQuota(QuotaLayer::kSubInstrument, 1, 10, 100, quota(l2, 0, 0, "LOT"));
  b.putQuota(QuotaLayer::kAccountProduct, 0, 10, 0, quota(l3, 0, 0, "LOT"));
  return b;
}

const hg::risk_state::Fixed BIG = 1000000;
const hg::risk_state::Fixed TINY = 1;  // 0 + 10000 > 1 ⇒ 超限

void test_only_layer1() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, quotaBatch(1, TINY, BIG, BIG)));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kQuota);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1004));
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kSubProduct);
  HG_CHECK(r.has_limit_layer);
  HG_CHECK_EQ(r.entry_count, 1);
}

void test_only_layer2() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, quotaBatch(1, BIG, TINY, BIG)));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1017));
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kSubInstrument);
  HG_CHECK_EQ(r.entry_count, 1);
}

void test_only_layer3() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, quotaBatch(1, BIG, BIG, TINY)));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1005));
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kAccountProduct);
  HG_CHECK_EQ(r.entry_count, 1);
}

void test_all_three_must_return_layer2() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, quotaBatch(1, TINY, TINY, TINY)));
  const RiskResult r = eng.check(baseOrder());
  // ④ 三层同时超 → 必回层②（SUB_INSTRUMENT / 0x1017）
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1017));
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kSubInstrument);
  // entries[] 携带全部命中项（三层各一条）
  HG_CHECK_EQ(r.entry_count, 3);
}

void test_layer1_and_layer3_returns_layer1() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, quotaBatch(1, TINY, BIG, TINY)));
  const RiskResult r = eng.check(baseOrder());
  // ⑤ 层①+层③ → 层①（层③不参与最细选择）
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1004));
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kSubProduct);
  HG_CHECK_EQ(r.entry_count, 2);  // 层① + 层③ 均入 entries[]
}

void test_layer_codes_all_match_layers() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, quotaBatch(1, TINY, TINY, TINY)));
  const RiskResult r = eng.check(baseOrder());
  // ⑦ 每条 entry 的 code 与其 layer 一一对应
  for (int i = 0; i < r.entry_count; ++i) {
    if (r.entries[i].layer == LimitLayer::kSubProduct) {
      HG_CHECK_EQ(r.entries[i].code, static_cast<std::uint32_t>(0x1004));
    } else if (r.entries[i].layer == LimitLayer::kSubInstrument) {
      HG_CHECK_EQ(r.entries[i].code, static_cast<std::uint32_t>(0x1017));
    } else if (r.entries[i].layer == LimitLayer::kAccountProduct) {
      HG_CHECK_EQ(r.entries[i].code, static_cast<std::uint32_t>(0x1005));
    }
  }
}

void test_account_level_is_shared_across_sub_accounts() {
  // 层③为「账户 + 品种」兜底：不同子账户共用同一账户级额度
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  b.putQuota(QuotaLayer::kAccountProduct, 0, 10, 0, quota(TINY, 0, 0, "LOT"));
  b.addWhitelist(2, 10);  // 让子账户 2 通过白名单
  HG_CHECK(bind(&st, &eng, b));
  OrderRequest q = baseOrder();
  q.sub_account_id = 2;  // 层①/② 无该子账户额度；仅层③命中
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x1005));
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kAccountProduct);
}

}  // namespace

int main() {
  test_only_layer1();
  test_only_layer2();
  test_only_layer3();
  test_all_three_must_return_layer2();
  test_layer1_and_layer3_returns_layer1();
  test_layer_codes_all_match_layers();
  test_account_level_is_shared_across_sub_accounts();
  return hg::risk_engine::testutil::report("risk_engine_quota_layers_test");
}
