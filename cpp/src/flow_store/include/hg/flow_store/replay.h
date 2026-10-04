#ifndef HG_FLOW_STORE_REPLAY_H
#define HG_FLOW_STORE_REPLAY_H
// =============================================================================
// 指令流水与重放（flow_store）—— 启动重放 / 状态机 / CTP 对账 / 分片保留（T-037）
//
// 依据：ARCHITECTURE.md §3.5 / §4.5；工单 T-037 验收 1–4。
// 边界纪律：`flow_store.h`（T-036 append+fsync 原语 / FlowKind / FlowState /
//   行格式契约）**零改动** —— 本文件的解析器按 T-036 行格式契约读：
//     `<ts_ms>|<KIND>|<order_id>|<ref>|<detail>\n`（`|`/换行已消毒替换）。
//
// ---- 状态机（验收 2 / §4.5.3）----------------------------------------------
//   NOT_READY → REPLAYING → READY；重放失败（结构损坏 / 对账不一致 / 目录不可读）
//   ⇒ **永不 READY**。§4.5.3 的 `FAILED` 态在 FlowState（T-036 冻结 3 值枚举）
//   中无承载 ⇒ 以 `failed()` 承载（state 停在 REPLAYING 且 failed()=true，
//   `ready()`=false ⇒ 0x0401 门卫成立，拒绝一切交易指令——与「拒绝一切服务」
//   同效）。诚实披露：`CMD_QUERY_STATUS` 的 ready=false 语义等价，状态名层面
//   的独立 FAILED 值须扩 FlowState 枚举（CH 级），本单不扩。
//
// ---- fsync_mode 裁定（验收 1 / CFG-29 / §4.5.2）---------------------------
//   CFG-29 缺省 `BATCH`（§4.5.2：每 100 条或 20 ms 一次）。T-036 的同步形态
//   「append 返回 true ⇒ 已 fsync」是 §4.2.6(4) 第⑨→⑩步「落盘先于发 CTP」
//   顺序不变量的**唯一承载**；BATCH/ASYNC 要求在发单调用点（order_engine
//   confirm 路径，T-036 已验收逻辑）插入显式 barrier —— 不在本单写域。
//   ⇒ 本单裁定：配置值**解析并承载**（`fsync_requested`），**生效值恒
//   EVERY_RECORD**（`fsync_effective`），降级以 `fsync_clamped=true` 显式披露
//   并告警（保守优于冒进，§4.5.3 性能注）。BATCH 真生效（异步批量 + barrier
//   接线）归 T-039/T-041 装配轮，须与其接缝一并裁定。
//
// ---- 结构完整性口径（§4.5.1「损坏 ⇒ 拒绝服务，不得带病运行」）--------------
//   T-036 文本行格式**无 CRC32**（§4.5.2 二进制记录格式未由 T-036 承载，见下）
//   ⇒ 本解析器的完整性守卫 = **结构校验**：恰 5 字段 / KIND ∈ 7 类 / ts_ms 与
//   order_id 为纯数字 / 行以 `\n` 终止（含末行）。任一不满足 ⇒ **整文件拒**
//   （verdict=kCorrupt，永不 READY，映射 0x3002）——不做「跳过坏行」（跳过即
//   带病运行，§4.5.1 明文禁止）。诚实披露：
//     · §4.5.2 的二进制格式（LEN/SEQ/TYPE/CRC32/PAYLOAD）与 T-036 文本行契约
//       不一致；T-036 契约为本单**唯一可读契约**（工单明示「零改动、按此契约
//       读」）⇒ CRC 守卫缺位，以结构校验替代；补 CRC 属行格式实质变更（CH 级）。
//     · 生命周期语义（如终态后再现 REPORTED=T-036 终态守卫下的合法「只记流水」、
//       0x2004 退回保留重试 ⇒ 合法重复 SENT）**不做**跨行时序判定 —— 解析器
//       只守结构，时序还原交消费方。
//
// ---- 对账与在途（验收 3/4 / §4.5.3 ⑤⑥）-----------------------------------
//   「在途」= 存在 SENT 且无任何终态（FILLED/REJECTED/EXPIRED/CANCELLED）的指令
//   （比 §4.5.3 ⑤「SENT 且无 REPORTED」宽——有 REPORTED 无终态者 CTP 侧仍可能
//   在途，保守纳入，披露）。「未发即崩」（PLACED 无 SENT）⇒ 冻结保留、不查 CTP、
//   待确认超时/还原归 T-041。对账经 `IOrderStateQuery` 接缝（生产侧 CTP 查询
//   归 T-041/T-039 装配）。
// =============================================================================

#include <cstdint>
#include <string>
#include <vector>

#include "hg/flow_store/flow_store.h"

namespace hg {
namespace flow_store {

// ---- 配置承载（CFG-29 / §4.5.2；验收 1）------------------------------------

// fsync 模式（§4.5.2：EVERY_RECORD / BATCH / ASYNC；CFG-29 缺省 BATCH）。
// 生效语义见本头「fsync_mode 裁定」块。
enum class FlowFsyncMode : std::uint8_t {
  kEveryRecord = 0,
  kBatch = 1,
  kAsync = 2
};

// 解析 §4.5.2 模式名（EVERY_RECORD/BATCH/ASYNC，大小写敏感）。未知名 ⇒ ok=false。
FlowFsyncMode parseFlowFsyncMode(const char* name, bool* ok);
const char* flowFsyncModeName(FlowFsyncMode m);

// 流水运行时配置（CFG-29：hg.flow.dir / hg.flow.fsync_mode / hg.flow.retain_days；
// 另含 §4.5.3 性能注的 HG.REPLAY.QUERY_TIMEOUT_MS 与进度步长）。
struct FlowRuntimeConfig {
  std::string flow_dir;               // hg.flow.dir；缺省 "./data/flow"
  FlowFsyncMode fsync_requested = FlowFsyncMode::kBatch;  // CFG-29 缺省 BATCH
  FlowFsyncMode fsync_effective = FlowFsyncMode::kEveryRecord;  // 本单裁定（见头注）
  bool fsync_clamped = true;          // requested != effective ⇒ 显式披露
  int retain_days = 90;               // hg.flow.retain_days 缺省 90
  std::int64_t replay_query_timeout_ms = 20000;  // HG.REPLAY.QUERY_TIMEOUT_MS
  std::size_t replay_progress_every = 1000;      // 每完成 N 条发一次进度（§4.5.3 ⑤）

  FlowRuntimeConfig() : flow_dir("./data/flow") {}
};

// 缺省配置：dir=`./data/flow`，requested=BATCH，effective=EVERY_RECORD，90 天。
FlowRuntimeConfig defaultFlowRuntimeConfig();

// 按日分片文件名（§4.5.2：`flow-YYYYMMDD.log`）。
std::string flowShardPath(const std::string& dir, int yyyymmdd);

// 保留期滚动清理（验收 1「保留 90 天」）。删除 dir 下早于（today - retain_days）
// 的 `flow-YYYYMMDD.log` 分片；非分片命名文件不触碰。返回删除数；目录不可读 ⇒ -1。
int sweepRetention(const std::string& dir, int retain_days, int today_yyyymmdd);

// ---- CTP 侧对账查询接缝（验收 3 / §4.5.3 ⑤；生产侧绑定归 T-041）-----------

struct InflightOrder {
  std::uint64_t order_id = 0;
  std::string client_order_ref;
  std::int64_t sent_ts_ms = 0;

  InflightOrder() {}
};

// 查询结论。kLive=CTP 侧仍在途（冻结保留、继续跟踪）；kFilled/kCancelled/kRejected
// = CTP 已终态（按 CTP 结果修正，§4.5.3 ⑤）；kNotFound=未实际到达（释放冻结 +
// 补发 EVT_ORDER_EXPIRED）；kTimeout=查询超时/不可用（保守「状态未知」）；
// kConflict=CTP 结果与流水矛盾（verdict=MISMATCH，§4.5.3 ⑥）。
enum class OrderQueryResult : std::uint8_t {
  kLive = 0,
  kFilled = 1,
  kCancelled = 2,
  kRejected = 3,
  kNotFound = 4,
  kTimeout = 5,
  kConflict = 6
};

class IOrderStateQuery {
 public:
  virtual ~IOrderStateQuery() {}
  // detail 承载 CTP 侧要素摘要（如 order_sys_id / trade_id），可为空指针。
  virtual OrderQueryResult query(const InflightOrder& order, std::string* detail) = 0;
};

// 告警接缝（验收 4：状态未知 ⇒ 告警；§4.5.3 性能注「要求人工介入」）。
class IReplayAlertSink {
 public:
  virtual ~IReplayAlertSink() {}
  virtual void onInflightUnknown(const InflightOrder& order) = 0;
};

// ---- 重放产出 --------------------------------------------------------------

enum class ReplayVerdict : std::uint8_t {
  kOk = 0,          // READY
  kCorrupt = 1,     // 结构损坏 ⇒ 永不 READY（0x3002）
  kMismatch = 2,    // 对账不一致 ⇒ 永不 READY（0x0405）
  kOpenFailed = 3   // 目录不可读/打开失败 ⇒ 永不 READY（0x3001 口径）
};

const char* replayVerdictName(ReplayVerdict v);

// 单指令还原态（消费方 order_engine/T-041 的 OrderMap 还原源；验收 2）。
struct ReplayOrderState {
  std::uint64_t order_id = 0;
  std::string client_order_ref;
  FlowKind last_kind = FlowKind::kPlaced;   // 流水末条类别
  bool sent = false;                        // 出现过 SENT
  bool terminal = false;                    // 出现过终态类别
  bool live_ctp = false;                    // 对账后 CTP 侧确认仍在途（冻结保留）
  std::int64_t last_ts_ms = 0;

  ReplayOrderState() {}
};

// 对账处置（§4.5.3 ⑤；事件补发的执行主体 = T-041/T-039 装配，本单产出决策）。
struct ReplayDecision {
  enum Kind : std::uint8_t {
    kAdoptTerminal = 0,     // 按 CTP 终态修正（kFilled/kCancelled/kRejected）
    kReleaseExpired = 1,    // CTP 无此单 ⇒ 释放冻结 + 补发 EVT_ORDER_EXPIRED
    kUnknownKeepFrozen = 2  // 超时/不可用 ⇒ 状态未知、冻结不释放 + 告警
  };
  Kind kind = kAdoptTerminal;
  InflightOrder order;
  OrderQueryResult ctp_result = OrderQueryResult::kLive;
  std::string detail;

  ReplayDecision() {}
};

struct ReplayReport {
  ReplayVerdict verdict = ReplayVerdict::kOpenFailed;
  std::string detail;                 // verdict != kOk 时的人读原因
  std::int64_t started_ts_ms = 0;
  std::int64_t finished_ts_ms = 0;
  std::uint64_t total_records = 0;    // 分片内总行数（进度分母）
  std::uint64_t replayed_records = 0; // 实际处理行数
  std::size_t shards_read = 0;
  std::size_t files_skipped = 0;      // 非 flow-YYYYMMDD.log 命名（不触碰、仅计数）
  std::vector<ReplayOrderState> orders;        // 全部还原指令（含终态）
  std::vector<InflightOrder> pending_unsent;   // PLACED 无 SENT：冻结保留（T-041 还原）
  std::vector<ReplayDecision> decisions;       // 在途对账处置
  std::size_t frozen_held = 0;        // 冻结保留（pending_unsent + live + unknown）
  std::size_t frozen_released = 0;    // 判定释放（kNotFound；CTP 已终态者由 T-041 落账）
  std::size_t unknown_count = 0;      // 状态未知（已告警）

  ReplayReport() {}
};

// ---- 重放控制器（验收 2/3；T4 单线程独占，非线程安全）----------------------

class ReplayController {
 public:
  ReplayController();

  // 运行时配置（run 前注入；缺省 defaultFlowRuntimeConfig()）。
  void set_runtime_config(const FlowRuntimeConfig& cfg);
  // 对账查询/告警接缝（可空持有；缺省 null ⇒ 在途一律按 kTimeout 保守处理）。
  void set_order_query(IOrderStateQuery* query);
  void set_alert_sink(IReplayAlertSink* alert);

  // 执行启动重放（§4.5.3 ②→⑦）：清理保留期 → 逐日分片排序 → 结构校验逐行解析
  // → 状态还原 → 在途对账。进度回调（可空）在状态迁移与每 progress_every 条时
  // 触发（`EVT_REPLAY_PROGRESS` 的载荷源）。
  typedef void (*ProgressFn)(void* user, FlowState state, std::uint64_t done,
                             std::uint64_t total);
  ReplayReport run(std::int64_t now_ms, ProgressFn progress, void* user);

  FlowState state() const { return state_; }
  bool failed() const { return failed_; }  // 损坏/不一致 ⇒ 拒绝一切服务（§4.5.3 ⑥）
  bool ready() const { return state_ == FlowState::kReady && !failed_; }
  const ReplayReport& last_report() const { return report_; }
  const FlowRuntimeConfig& config() const { return cfg_; }

 private:
  ReplayController(const ReplayController&);
  ReplayController& operator=(const ReplayController&);

  FlowRuntimeConfig cfg_;
  IOrderStateQuery* query_;
  IReplayAlertSink* alert_;
  FlowState state_;
  bool failed_;
  ReplayReport report_;
};

}  // namespace flow_store
}  // namespace hg

#endif  // HG_FLOW_STORE_REPLAY_H
