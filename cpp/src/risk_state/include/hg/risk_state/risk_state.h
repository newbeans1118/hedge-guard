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
// ⚠️ 写者纪律：`rebuild` 为**单写者**（参数重建线程）；并发 `rebuild` 不受支持。
//
// 「诊断访问器」并发读（`last_result()` / `stats()`，`T-124`）：
//   二者均为**只读诊断**（非交易热路径、不参与判定），但**可由任意线程读取**，
//   与写者的发布并发。**不得**只依赖「写者静止后再读」这一仅注释级的约定
//   （`T-034` 复核 `O-1`/`O-3`：TSAN 证实 `last_` 与 `retired_.size()` 的跨线程读
//   与写者构成**数据竞争**）。实现改为**无锁发布**：
//     · `last_result()` —— 「全字段原子 + 版本戳」快照：写者在写入前后各递增一次
//       `seq_`（偶数 = 稳定、奇数 = 写入中），读者在前后 `seq_` 相等时才采信，
//       否则重读 ⇒ 无需锁即可取得自洽结果（零锁、零分配）。
//     · `stats().retired_pending` —— 与 `retired_` 并行维护的原子计数
//       （见 `snapshot_manager.h`）。
//
// ⚠️ 本目录属 `E4` 扫描根：不得出现锁原语 / 同步 I/O（见 `snapshot_manager.h` 说明）。
//    上述实现仅用 `std::atomic`，不触 `E4`。
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

  // 最近一次 rebuild 结果（**无锁发布**，任意线程可读；语义见文件头「诊断访问器」）。
  RebuildResult last_result() const;
  SnapshotStats stats() const { return mgr_.stats(); }

 private:
  // 诊断结果的无锁发布槽：全字段原子化 + 版本戳（写者写入前后各递增一次 `seq_`）。
  // 读者在前后 `seq_` 相等（且为偶数）时才采信，否则重读 —— 无需锁即得自洽快照。
  struct DiagnosticSlot {
    std::atomic<std::uint64_t> seq_{0};           // 偶数 = 稳定；奇数 = 写入中
    std::atomic<bool> ok_{false};
    std::atomic<int> code_{0};
    std::atomic<std::uint64_t> version_{0};
    std::atomic<std::size_t> record_count_{0};
  };

  void publishLastResult(const RebuildResult& r);  // 写侧（单写者）

  SnapshotManager mgr_;
  std::atomic<std::uint64_t> last_version_;
  DiagnosticSlot last_;

  RiskState(const RiskState&) = delete;
  RiskState& operator=(const RiskState&) = delete;
};

}  // namespace risk_state
}  // namespace hg

#endif  // HG_RISK_STATE_RISK_STATE_H
