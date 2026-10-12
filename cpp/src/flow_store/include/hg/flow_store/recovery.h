#ifndef HG_FLOW_STORE_RECOVERY_H
#define HG_FLOW_STORE_RECOVERY_H
// =============================================================================
// 崩溃恢复集成（flow_store 侧台账，T-041）—— ARCHITECTURE.md §4.5.3 / §9 RSK-10
//
// 职责（TASKS §2 T-041 产出 `recovery.cc` 的承载头）：
//   · ReplayReport 决策的消费与「状态未知」在途台账（恢复台账，内存态）；
//   · kUnknownKeepFrozen（kTimeout）的「人工核验并释放」管理入口（验收 3）；
//   · 「unknown 未闭环 ⇒ 不 READY」台账读面，供 0x0401 单一门卫合取（验收 4）。
//
// 边界纪律（诚实声明）：
//   · flow_store.h（T-036 冻结行格式契约）与 replay.h（T-037 接缝）零改动；
//     本头只新增类型，不触碰上游接缝语义。
//   · 本目录不在 E4 扫描根（v1.5.12）：无 I/O 形态限制；但本类本体零 I/O
//     （纯内存台账）。留痕经 order_engine 侧 recovery_hook 走 FlowStore::append
//     （T-036 契约行格式，复用 `kExpired` 类别——CTP 查不可得后在恢复期判失效，
//     语义同向；detail 前缀 `recovery:` 区分于确认超时轨）。
//   · 留痕只增（T-036 append-only；R-TRADE-15）：人工核验留痕走流水而非事件流
//     —— 流水是恢复判据（append-only 权威），事件流是外部通知通道（T-039）。
//     披露：FlowState 行格式无「恢复释放」专属类别，复用 kExpired + `recovery:`
//     前缀为**偏置化承载**；重启重放后该单在还原态为 terminal（kExpired 终态）
//     ⇒ 不会再入 unknown 台账 ⇒ 不会重复冻结。这是 T-036 冻结契约下唯一
//     不扩协议的落痕形态（扩 FlowKind = 行格式实质变更，CH 级，本单不开）。
//   · 验收 3「双人复核 + 留痕」承载形态（工单③：入口留接缝 + 管理面归 Java/
//     运维侧，不扩协议）：本类提供**进程内管理入口**（manualVerifyRelease），
//     非线上协议入口；指令面 0x01xx 新命令 = 扩协议（CH 级），本单不开。
//     双人复核 = 调用方（Java/运维管理面，T-042/T-039 装配）调用前完成的流程
//     动作；C++ 侧机械承载 =「双操作人标识 + 双人非零 + 双人不得同人 + 理由
//     非空 + 引用一致性」五守卫 + 流水留痕（含双操作人 id）。
// =============================================================================

#include <cstdint>
#include <string>
#include <vector>

#include "hg/flow_store/flow_store.h"
#include "hg/flow_store/replay.h"

namespace hg {
namespace flow_store {

// ---- 人工核验入参（验收 3：双人复核 + 留痕）--------------------------------

// 人工核验并释放的入参（双人复核的机械承载；见头注）。
struct ManualReleaseRequest {
  std::uint64_t order_id = 0;
  std::string client_order_ref;      // 与台账一致性守卫（不匹配 ⇒ 拒绝）
  std::uint64_t operator_a_id = 0;   // 复核人 A（非零）
  std::uint64_t operator_b_id = 0;   // 审批人 B（非零；不得与 A 同人）
  std::string reason;                // 核验结论（留痕 detail；非空要求）

  ManualReleaseRequest() {}
};

// manualVerifyRelease 拒因（观测/回执载荷；kNone = 成功）。
enum class ManualReleaseError : std::uint8_t {
  kNone = 0,
  kNotFound = 1,        // 台账无此 order_id（含已按其它路径闭环）
  kAlreadyReleased = 2, // 已人工闭环（幂等拒绝，不二次留痕）
  kOperatorMissing = 3, // 双操作人存在非零（双人复核前提）
  kSameOperator = 4,    // 双人不得同人（复核独立性）
  kRefMismatch = 5,     // client_order_ref 与台账不一致（防错单）
  kReasonMissing = 6    // 核验结论缺失（留痕完整性）
};

const char* manualReleaseErrorName(ManualReleaseError e);

// ---- 恢复台账（验收 2/3/4；T4 单线程独占，非线程安全）-----------------------

// 台账条目：一条「状态未知」在途指令的处置态。
struct RecoveryEntry {
  std::uint64_t order_id = 0;
  std::string client_order_ref;
  std::int64_t sent_ts_ms = 0;                   // 在途流水时刻（重放还原）
  std::int64_t registered_ts_ms = 0;             // 台账登记时刻（replay now_ms）
  bool released = false;                         // 人工核验释放已执行
  std::uint64_t operator_a_id = 0;               // 复核人 A（释放后承载）
  std::uint64_t operator_b_id = 0;               // 审批人 B（释放后承载）
  std::int64_t released_ts_ms = 0;

  RecoveryEntry() {}
};

// 台账观测（装配方/Java 镜像的查询面；快照值拷贝）。
struct RecoveryLedgerView {
  std::size_t total = 0;           // 登记总数（含已释放）
  std::size_t open_count = 0;      // 未闭环（0x0401 阻断计数）
  std::size_t released_count = 0;  // 已人工闭环

  RecoveryLedgerView() {}
};

// ---- 恢复协调器（验收 2/3/4）-----------------------------------------------

// 恢复协调器：消费 ReplayReport，登记未知台账；承载人工核验入口与门禁读面。
// 单线程独占（T4）；**非线程安全**（与 ReplayController/OrderEngine 同形）。
class RecoveryCoordinator {
 public:
  RecoveryCoordinator();

  // 消费重放产出：从 ReplayReport 提取 kUnknownKeepFrozen(kTimeout) 决策，
  // 登记「未知台账」（验收 2「保守记为状态未知」的台账承载）。
  // 仅登记 kTimeout（kLive 为 CTP 侧确认在途、冻结保留继续跟踪，非人工核验
  // 对象——披露：若其后续自行终态，由 CTP 回报驱动 onOrderEvent 正常闭环）；
  // 同 order_id 重复登记幂等（重复重放防重）。
  void consume(const ReplayReport& report, std::int64_t now_ms);

  // 人工核验并释放（验收 3；入口接缝形态，见头注）。
  // 五守卫：双人非零 + 双人不得同人 + ref 匹配 + reason 非空 + 台账命中且未释放。
  // 成功 ⇒ 条目置 released + 双操作人/时刻回填 + 经 hook 留痕（FlowStore::append
  // kExpired + "recovery:manual release ..."）+ 阻断计数 -1。
  // 拒绝 ⇒ 返回 false，*err = 拒因（可空指针），台账不变。
  bool manualVerifyRelease(const ManualReleaseRequest& req, std::int64_t now_ms,
                           ManualReleaseError* err);

  // 验收 4 门禁读面：存在未闭环 unknown ⇒ true（system_ready 第三合取项）。
  bool has_unknown_open() const { return open_count_ > 0; }

  // 观测面。
  RecoveryLedgerView view() const;
  const RecoveryEntry* find(std::uint64_t order_id) const;
  std::size_t entry_count() const { return entries_.size(); }

  // 留痕接缝（装配方注入 FlowStore；非空时 manualVerifyRelease 成功才留痕）。
  // 注入点为 setter 而非构造参数：留痕通道依赖 order_engine 装配顺序（先引擎
  // 后恢复），且缺省 null = 仅台账不留痕（纯台账形态可用，披露）。
  void set_flow_store(FlowStore* flow) { flow_ = flow; }

 private:
  RecoveryCoordinator(const RecoveryCoordinator&);
  RecoveryCoordinator& operator=(const RecoveryCoordinator&);

  std::vector<RecoveryEntry> entries_;
  std::size_t open_count_ = 0;
  FlowStore* flow_ = nullptr;
};

}  // namespace flow_store
}  // namespace hg

#endif  // HG_FLOW_STORE_RECOVERY_H
