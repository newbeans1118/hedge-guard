// T-035 三层限额「最细命中者」全序 + 并列取一 单测
//   （ARCHITECTURE.md §4.4.1 脚注；用例 ⑥：同层两条命中 → 占用率高者 → 再并列 id 升序）
#include "risk_engine_test_util.h"

#include "hg/risk_engine/quota_selection.h"

namespace {
using hg::risk_engine::LimitLayer;
using hg::risk_engine::QuotaHit;
using hg::risk_engine::quotaHitBetter;
using hg::risk_engine::selectFinestQuotaHit;

QuotaHit hit(LimitLayer layer, hg::risk_state::Fixed limit, hg::risk_state::Fixed used,
             std::uint64_t id) {
  QuotaHit h;
  h.layer = layer;
  h.limit = limit;
  h.used = used;
  h.frozen = 0;
  h.id = id;
  return h;
}

void test_empty() {
  HG_CHECK_EQ(selectFinestQuotaHit(nullptr, 0), -1);
  QuotaHit one[1];
  HG_CHECK_EQ(selectFinestQuotaHit(one, 0), -1);
}

void test_finest_order() {
  // 层② > 层① > 层③
  {
    QuotaHit h[2] = {hit(LimitLayer::kSubProduct, 100, 10, 0),
                     hit(LimitLayer::kSubInstrument, 100, 1, 0)};
    HG_CHECK_EQ(selectFinestQuotaHit(h, 2), 1);  // 层② 胜（即便占用率更低）
  }
  {
    QuotaHit h[2] = {hit(LimitLayer::kAccountProduct, 100, 99, 0),
                     hit(LimitLayer::kSubProduct, 100, 1, 0)};
    HG_CHECK_EQ(selectFinestQuotaHit(h, 2), 1);  // 层① 胜（层③ 不参与最细）
  }
  {
    QuotaHit h[1] = {hit(LimitLayer::kAccountProduct, 100, 99, 0)};
    HG_CHECK_EQ(selectFinestQuotaHit(h, 1), 0);  // 仅层③ ⇒ 兜底选中
  }
  {
    QuotaHit h[3] = {hit(LimitLayer::kAccountProduct, 100, 99, 0),
                     hit(LimitLayer::kSubProduct, 100, 1, 0),
                     hit(LimitLayer::kSubInstrument, 100, 1, 0)};
    HG_CHECK_EQ(selectFinestQuotaHit(h, 3), 2);  // 三层齐备 ⇒ 层②
  }
}

void test_same_layer_occupancy_then_id() {
  // 同层两条：占用率高者胜
  {
    QuotaHit h[2] = {hit(LimitLayer::kSubProduct, 100, 10, 1),   // 0.10
                     hit(LimitLayer::kSubProduct, 100, 50, 2)};  // 0.50
    HG_CHECK_EQ(selectFinestQuotaHit(h, 2), 1);
  }
  // 交叉相乘（分母不同）：50/100=0.50 vs 80/200=0.40 ⇒ 前者胜
  {
    QuotaHit h[2] = {hit(LimitLayer::kSubProduct, 200, 80, 1),   // 0.40
                     hit(LimitLayer::kSubProduct, 100, 50, 2)};  // 0.50
    HG_CHECK_EQ(selectFinestQuotaHit(h, 2), 1);
  }
  // 占用率并列 → id 升序
  {
    QuotaHit h[2] = {hit(LimitLayer::kSubProduct, 100, 10, 5),
                     hit(LimitLayer::kSubProduct, 100, 10, 3)};
    HG_CHECK_EQ(selectFinestQuotaHit(h, 2), 1);  // id=3 胜
  }
  // frozen 计入占用率
  {
    QuotaHit a = hit(LimitLayer::kSubProduct, 100, 0, 1);
    a.frozen = 5;  // 占用 5
    QuotaHit h[2] = {a, hit(LimitLayer::kSubProduct, 100, 10, 2)};  // 10 胜
    HG_CHECK_EQ(selectFinestQuotaHit(h, 2), 1);
  }
}

void test_ignores_non_quota_layers() {
  QuotaHit h[2] = {hit(LimitLayer::kAuthQuota, 100, 99, 0),
                   hit(LimitLayer::kAccountProduct, 100, 1, 0)};
  HG_CHECK_EQ(selectFinestQuotaHit(h, 2), 1);
}

void test_hit_better_direct() {
  HG_CHECK(quotaHitBetter(hit(LimitLayer::kSubProduct, 100, 50, 9),
                          hit(LimitLayer::kSubProduct, 100, 10, 1)));
  HG_CHECK(!quotaHitBetter(hit(LimitLayer::kSubProduct, 100, 10, 1),
                           hit(LimitLayer::kSubProduct, 100, 50, 9)));
  HG_CHECK(quotaHitBetter(hit(LimitLayer::kSubProduct, 100, 10, 1),
                          hit(LimitLayer::kSubProduct, 100, 10, 2)));  // id 小者胜
}

}  // namespace

int main() {
  test_empty();
  test_finest_order();
  test_same_layer_occupancy_then_id();
  test_ignores_non_quota_layers();
  test_hit_better_direct();
  return hg::risk_engine::testutil::report("risk_engine_selector_test");
}
