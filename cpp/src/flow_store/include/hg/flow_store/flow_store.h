#ifndef HG_FLOW_STORE_FLOW_STORE_H
#define HG_FLOW_STORE_FLOW_STORE_H
// =============================================================================
// 指令流水与重放（flow_store）—— append 原语（T-036 最小承载，ARCHITECTURE.md §3.5）
//
// 职责边界（诚实声明）：
//   · 本单（T-036）只落 **append + fsync 原语与状态枚举** —— 供 order_engine 在
//     「确认通过 → 发 CTP」之前落流水（§4.2.6(4) 第⑨→⑩步的顺序不变量）；
//   · **启动重放 / CTP 对账 / REPLAYING 状态迁移归 T-037**（§3.5 / §6.2），
//     本头仅预留 `kReplaying` 枚举值（当前实现不产生该状态）；
//   · 同步原语形态：`append` 返回即已 `fsync`。§4.2.6(4) 第⑨步注「异步线程」为
//     性能措施；落盘先于发 CTP 的**顺序不变量**由「append 返回 true 后才 submit」
//     保证（T4 单线程下同步调用即满足；异步化卸载归 T-039/T-041 接线，本单不做）。
//
// ⚠️ 本目录**不在** `E4` 扫描根（v1.5.12 明文排除：`flow_store` 合法 `append + fsync`）；
//    本文件使用 POSIX `open/write/fsync`（`<fcntl.h>` / `<unistd.h>`）。
// =============================================================================

#include <cstdint>
#include <string>

namespace hg {
namespace flow_store {

// 流水记录类别（对齐 §4.2.3 `FlowType`：SENT/REPORTED/REJECTED/EXPIRED/CANCELLED；
// 另补生命周期两端 kPlaced/kFilled，T-037 重放时按类别还原状态机）。
enum class FlowKind : std::uint8_t {
  kPlaced = 1,     // 受理（风控通过、进入待确认）
  kSent = 2,       // 已落流水并发往 CTP
  kReported = 3,   // 委托回报
  kFilled = 4,     // 成交回报
  kRejected = 5,   // 事前风控拒绝 / CTP 拒单
  kExpired = 6,    // 确认超时失效
  kCancelled = 7   // 撤单
};

const char* flowKindName(FlowKind k);

// 流水状态（§3.5：NOT_READY / REPLAYING / READY）。
enum class FlowState : std::uint8_t { kNotReady = 0, kReplaying = 1, kReady = 2 };

const char* flowStateName(FlowState s);

// 一条流水记录（append-only，只增不改不删，R-TRADE-15）。
struct FlowRecord {
  FlowKind kind = FlowKind::kPlaced;
  std::uint64_t order_id = 0;
  std::string client_order_ref;
  std::int64_t ts_ms = 0;
  std::string detail;  // 生命周期明细 / 指令要素摘要

  FlowRecord() {}
};

// append-only 本地文件流水（§3.5「本地文件（非数据库）」，E1/E2 合规）。
// 单写者（T4 交易线程）；**非线程安全**（与「T4 独占维护」一致，§4.4.1）。
class FlowStore {
 public:
  FlowStore();
  ~FlowStore();

  // 打开（或创建）append-only 流水文件；成功 ⇒ `state()==kReady`。
  // 目录不存在 / 无写权限 ⇒ false（调用方须告警，不得静默继续）。
  bool open(const std::string& path);
  void close();
  bool is_open() const { return fd_ >= 0; }
  FlowState state() const { return state_; }

  // 追加一条记录并 fsync。落盘成功（含 fsync）才返回 true。
  // 行格式（T-037 重放契约）：`<ts_ms>|<KIND>|<order_id>|<ref>|<detail>\n`
  //   · `ref`/`detail` 内的 `|` 与换行符被替换（`|`→`/`、`\n`/`\r`→空格），
  //     保证一行一记录、五字段定列；
  //   · 编码 = UTF-8 透传（detail 来自指令要素摘要，ASCII 为主）。
  bool append(const FlowRecord& rec);

  // 已落盘记录数（自打开起累计；诊断用）。
  std::uint64_t record_count() const { return count_; }
  const std::string& path() const { return path_; }

 private:
  FlowStore(const FlowStore&);
  FlowStore& operator=(const FlowStore&);

  int fd_;
  FlowState state_;
  std::uint64_t count_;
  std::string path_;
};

}  // namespace flow_store
}  // namespace hg

#endif  // HG_FLOW_STORE_FLOW_STORE_H
