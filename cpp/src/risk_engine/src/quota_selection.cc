// 事前风控引擎 —— 三层限额「最细命中者」全序选择（ARCHITECTURE.md §4.4.1 脚注）
#include "hg/risk_engine/quota_selection.h"

namespace hg {
namespace risk_engine {

int selectFinestQuotaHit(const QuotaHit* hits, int n) {
  if (hits == nullptr || n <= 0) return -1;
  // best[0] = 层②、best[1] = 层①、best[2] = 层③
  int best[3] = {-1, -1, -1};
  for (int i = 0; i < n; ++i) {
    const int rank = quotaLayerRank(hits[i].layer);
    if (rank < 0) continue;
    if (best[rank] < 0 || quotaHitBetter(hits[i], hits[best[rank]])) {
      best[rank] = i;
    }
  }
  // 全序：层② > 层① > [层③ 兜底，仅当 ①/② 均未命中]
  if (best[0] >= 0) return best[0];
  if (best[1] >= 0) return best[1];
  return best[2];
}

}  // namespace risk_engine
}  // namespace hg
