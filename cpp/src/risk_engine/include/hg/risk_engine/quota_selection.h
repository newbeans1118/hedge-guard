#ifndef HG_RISK_ENGINE_QUOTA_SELECTION_H
#define HG_RISK_ENGINE_QUOTA_SELECTION_H
// =============================================================================
// 事前风控引擎 —— 三层限额「最细命中者」全序选择（ARCHITECTURE.md §4.4.1 脚注 / §3.3 `R-RISK-2`）
//
// 全序（v1.2 写死，`M1 #13`）：**层②（`SUB_INSTRUMENT`）> 层①（`SUB_PRODUCT`）> 层③（`ACCOUNT_PRODUCT`）**；
//   **层③为总账户兜底，不参与「最细命中」的选择**（仅当层①/②均未命中时，层③命中才被选中上报）。
//
// 同层并列取一（确定性，保证用例可复现）：同一层级内命中多条时，取**占用率最高者**
//   （`(used+frozen)/quota_limit` 最大）→ 占用率再并列则按 **`id` 升序**取一。
//   `entries[]` 仍**携带全部命中项**（含未被选中的并列项）。
//
// 纯函数、无分配、无锁、无 I/O（`E4` 扫描根）。
// =============================================================================

#include <cstdint>

#include "hg/risk_engine/fixed_math.h"
#include "hg/risk_engine/risk_result.h"
#include "hg/risk_state/model.h"

namespace hg {
namespace risk_engine {

using hg::risk_state::Fixed;
using hg::risk_state::QuotaLayer;

// 一条候选命中（由引擎从快照各层查得；`id` = 额度行的确定性次序键）
struct QuotaHit {
  LimitLayer layer = LimitLayer::kNone;
  Fixed limit = 0;
  Fixed used = 0;
  Fixed frozen = 0;
  std::uint64_t id = 0;  // 并列时的次序键（升序取一）
};

// 层序号：层② = 0（最细）、层① = 1、层③ = 2（兜底，不参与最细选择）；
// 非 ①/②/③ 层级返回 -1（不参与选择）。
inline int quotaLayerRank(LimitLayer l) {
  if (l == LimitLayer::kSubInstrument) return 0;   // 层② 最细
  if (l == LimitLayer::kSubProduct) return 1;      // 层①
  if (l == LimitLayer::kAccountProduct) return 2;  // 层③ 兜底
  return -1;
}

// 层内「占用率高者优先、再按 id 升序」的比较：返回 true 表示 a 优于 b。
// 占用率：a.used_total / a.limit > b.used_total / b.limit ⇒ a 优（交叉相乘，避免除法误差）。
inline bool quotaHitBetter(const QuotaHit& a, const QuotaHit& b) {
  const Fixed a_used = a.used + a.frozen;
  const Fixed b_used = b.used + b.frozen;
  // 先比占用率（交叉相乘；limit 非正按 0 处理，视为占用率 0）
  const std::uint64_t a_lim = (a.limit > 0) ? static_cast<std::uint64_t>(a.limit) : 0ull;
  const std::uint64_t b_lim = (b.limit > 0) ? static_cast<std::uint64_t>(b.limit) : 0ull;
  const std::uint64_t a_u = (a_used > 0) ? static_cast<std::uint64_t>(a_used) : 0ull;
  const std::uint64_t b_u = (b_used > 0) ? static_cast<std::uint64_t>(b_used) : 0ull;
  // 比较 (a_u / a_lim) 与 (b_u / b_lim) ⇔ (a_u * b_lim) vs (b_u * a_lim)
  const int c = cmpMulU64(a_u, b_lim, b_u, a_lim);
  if (c != 0) return c > 0;
  return a.id < b.id;  // 占用率并列 ⇒ id 升序
}

// 从候选数组中选择「全序最细命中者」，返回其下标；数组为空返回 -1。
// 选择规则：
//   · 层② 命中 ⇒ 取层②内最优（占用率最高 → id 升序）
//   · 否则层① 命中 ⇒ 取层①内最优
//   · 否则层③ 命中 ⇒ 取层③内最优（兜底）
int selectFinestQuotaHit(const QuotaHit* hits, int n);

}  // namespace risk_engine
}  // namespace hg

#endif  // HG_RISK_ENGINE_QUOTA_SELECTION_H
