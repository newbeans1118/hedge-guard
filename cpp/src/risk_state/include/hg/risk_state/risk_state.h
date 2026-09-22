#ifndef HG_RISK_STATE_RISK_STATE_H
#define HG_RISK_STATE_RISK_STATE_H
// =============================================================================
// 风控内存状态 —— 对外门面 RiskState（ARCHITECTURE.md §3.4 / §4.4.3）
//
// 对外接口（与 §3.4 逐字对齐）：
//   · `RiskState::snapshot()` → `const RiskSnapshot&`（**原子读**；交易路径零锁、零等待）
//   · `RiskState::rebuild(const LoadBatch&)`（构建新快照 → 校验 → **原子替换**）
//   · `RiskState::acquire()` → RCU 强保护读句柄（跨重建持有的读者用）
//
// 「内存为交易路径权威源」（FR-RISK-9 / R-RISK-3）：
//   · 全部判定数据常驻内存（LoadBatch 预加载 → 不可变快照）；
//   · 交易路径零 DB I/O（E1/E2）、零锁竞争（E4）、零同步 I/O（E4）。
//
// ⚠️ 写者纪律：`rebuild` 为**单写者**（参数重建线程）。并发 `rebuild` 不受支持；
//    `last_result()` 的读取应在写者静止后进行（测试在 join 后读）。
//
// ⚠️ 本目录属 `E4` 扫描根：不得出现锁原语 / 同步 I/O（见 `snapshot_manager.h` 说明）。
// =============================================================================

#include <atomic>
#include <cstddef>
#include <cstdint>

#include "hg/risk_state/load_batch.h"
#include "hg/risk_state/risk_snapshot.h"
#include "hg/risk_state/snapshot_manager.h"

namespace hg {
namespace risk_state {

// 重建结果（`ok==false` 时快照**未变更**，返回错误码）
struct RebuildResult {
  bool ok = false;
  int code = 0;                 // RebuildCode
  std::uint64_t version = 0;    // 目标参数版本
  std::size_t record_count = 0;  // 发布后快照的加载条数
};

// 错误码 → 可读名称（诊断：发生了什么）
const char* rebuildCodeName(int code);

class RiskState {
 public:
  RiskState();
  ~RiskState();

  // ---- 读侧（交易路径 T4）----
  // 原子读：返回当前已发布快照的只读引用。零锁、零等待；写者原子替换 ⇒ 永不撕裂。
  const RiskSnapshot& snapshot() const { return mgr_.peek(); }
  // RCU 强保护读句柄（跨多次 rebuild 持有仍安全）
  SnapshotManager::ReadGuard acquire() const { return mgr_.enter(); }
  // 就绪判定（FR-RISK-9 验收 2：预加载未完成 → 系统未就绪）
  bool ready() const { return !mgr_.peek().empty(); }
  // 当前参数版本（供对账：FR-RISK-9 验收 3）
  std::uint64_t param_version() const { return mgr_.peek().param_version(); }

  // ---- 写侧（参数重建线程，单写者）----
  // 构建新快照（replace_all ⇒ 全量；否则以当前快照深拷贝为基底增量）→ 不变量校验 →
  // 原子替换。校验失败 / 版本倒退 ⇒ **不发布**，返回错误码。
  RebuildResult rebuild(const LoadBatch& batch);

  RebuildResult last_result() const { return last_; }
  SnapshotStats stats() const { return mgr_.stats(); }

 private:
  SnapshotManager mgr_;
  std::atomic<std::uint64_t> last_version_;
  RebuildResult last_;

  RiskState(const RiskState&) = delete;
  RiskState& operator=(const RiskState&) = delete;
};

}  // namespace risk_state
}  // namespace hg

#endif  // HG_RISK_STATE_RISK_STATE_H
