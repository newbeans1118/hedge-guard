#ifndef HG_RISK_ENGINE_RISK_ENGINE_H
#define HG_RISK_ENGINE_RISK_ENGINE_H
// =============================================================================
// 事前风控引擎（M3）—— 对外接口（ARCHITECTURE.md §3.3）
//
//   · `RiskResult RiskEngine::check(const OrderRequest&)`（**纯函数式：const 快照 +
//     入参，无副作用**）—— 8 条规则全内存判定 + 双层（子账户 / 总账户）校验。
//   · `RiskEngine::applySnapshot(const RiskSnapshot&)` —— 原子替换判定快照。
//
// 判定路径纪律（§4.4.3 / §8.1.3）：
//   · 零 DB I/O（`E1` / `E2`）、零锁 / 零同步 I/O（`E4`）、**零动态分配**
//     （`entries[]` 定长数组；`detail` 为静态字符串）、无异常抛出路径。
//   · 快照只读：`check` 为 `const` 方法，不修改任何状态（可对同一快照重复求值）。
//
// ⚠️ 本目录属 `E4` 扫描根：不得出现锁原语 / 同步 I/O 调用形态。
// =============================================================================

#include <atomic>
#include <cstdint>

#include "hg/risk_engine/clock.h"
#include "hg/risk_engine/order_request.h"
#include "hg/risk_engine/risk_result.h"
#include "hg/risk_state/risk_snapshot.h"
#include "hg/risk_state/risk_state.h"

namespace hg {
namespace risk_engine {

// 判定所需阈值（**在 `applySnapshot` 时从快照一次性解析为 POD**）。
// 目的：判定路径（`check`）**零分配** —— 快照的 `thresholdFixed/Int` 接口以
// `std::string` 为键，直接在 T4 内构造键串会产生堆分配（§4.4.2「交易路径禁止动态分配」），
// 故把解析动作前移到写侧（`applySnapshot`，允许分配），`check` 只读 POD 成员。
struct RiskThresholds {
  Fixed price_warn_pct = 20000;         // risk.price.warn_pct（1e6 定点）= 2%
  Fixed price_block_pct = 50000;        // risk.price.block_pct = 5%
  Fixed scale_ratio_commodity = 900000;  // hedge.scale.ratio_commodity = 90%
  Fixed scale_ratio_trade = 800000;      // hedge.scale.ratio_trade = 80%
  std::int64_t term_max_months = 12;     // risk.term.max_months
  std::int64_t term_grace_days = 30;     // risk.term.grace_days
};

class RiskEngine {
 public:
  // clock == nullptr ⇒ 使用内置 SystemClock（可注入假时钟，NFR-TEST-4）。
  explicit RiskEngine(const IClock* clock = nullptr);
  ~RiskEngine();

  // 原子替换判定快照（§3.3 literal）。传引用、**不取得所有权**；调用方须自证该快照
  // 在引擎使用期间存活（**推荐改用下方位保护重载**，见其说明）。
  void applySnapshot(const hg::risk_state::RiskSnapshot& s);

  // 强保护绑定（**推荐**）：经 `RiskState::acquire()` 取 RCU 读句柄并**由引擎持有**，
  // 从而跨任意多次 `rebuild`/`publish` 期间，本引擎所用的快照**必不被回收** —— 消除
  // 「裸指针未被读者计数、却可能被 RCU 宽限期回收」的 use-after-free（§4.4.3）。
  // 同时从该快照解析阈值。
  void applySnapshot(const hg::risk_state::RiskState& state);

  // 当前快照指针（未设置 ⇒ nullptr）。仅供观测 / 测试。
  const hg::risk_state::RiskSnapshot* currentSnapshot() const;

  // 当前解析后的阈值（仅供观测 / 测试）。
  const RiskThresholds& thresholds() const { return thresholds_; }

  // 判定（纯函数式：只读快照 + 入参；无副作用；可重复求值结果相同）。
  RiskResult check(const OrderRequest& req) const;

 private:
  const IClock* clock_;
  SystemClock default_clock_;
  std::atomic<const hg::risk_state::RiskSnapshot*> snapshot_;
  hg::risk_state::SnapshotManager::ReadGuard guard_;  // 推荐路径下持有的 RCU 读句柄
  RiskThresholds thresholds_;

  RiskEngine(const RiskEngine&) = delete;
  RiskEngine& operator=(const RiskEngine&) = delete;
};

}  // namespace risk_engine
}  // namespace hg

#endif  // HG_RISK_ENGINE_RISK_ENGINE_H
