#ifndef HG_HGCP_EVENT_EMITTER_H
#define HG_HGCP_EVENT_EMITTER_H
// =============================================================================
// HGCP 事件流产出与保序补数（T-039）—— ARCHITECTURE.md §4.2.6 / §2.4.2 T6 /
//                                       NFR-REL-2/5 / NFR-PERF-6
//
// 职责：**事件帧的发送 / 保序 / 队列管理**（编排层），不是第二套事件编码器——
//   · 已编码的 8 种事件（`EVT_ORDER_ACCEPTED`/`PRE_TRADE_REJECT`/`ORDER_REPORT`/
//     `TRADE_FILL`/`ORDER_EXPIRED`/`ORDER_CANCEL_RESULT`/`REPLAY_PROGRESS`/
//     `SYSTEM_STATE`，command_handler T-036/T-037/T-038 落）经 `emit()` 统一出口上线；
//   · 尚无产出源的 6 种事件（`POSITION_CHANGE`/`COMMAND_FLOW`/`FUND_RECEIPT`/
//     `MARKET_QUOTE`/`CTP_STATE`/`TRADING_ALERT`）由本类**补齐编码**（CH-013 schema）。
//   ⇒ 14 种事件可产出（验收 1）；其中须落库的 10 种（0x0202~0x020B）齐备。
//
// 保序（验收 2）：全部出站帧经 `HgcpServerLoop::send_frame` ⇒ `HgcpSession::make_outbound`
//   单调分配通道 SEQ（从 1 严格 +1、不跳号），事件帧与控制帧共用同一计数器——
//   本类**不**另设序号（跳号检测 / 补数触发由 session 与对端承载，§4.2.6「顺序保证」）。
//
// 断连保留模式（验收 3 / §2.4.3 eventQueue 行）：
//   · 容量 `kEventQueueCapacity = 16384`；断连期间可靠事件入保留队列（FIFO）**不丢**；
//   · **满 ⇒ 背压 + 告警**（`emit` 返回 `kBackpressured`、告警计数 + 可选告警接收方），
//     事件留在生产者（T4 载体）侧由其重试——§2.4.3 权威措辞即「告警 + 背压 T4」；
//     本装配 T4/T6 同泵线程（无内部线程），阻塞原语（condvar）会自锁 ⇒ 取背压形态；
//   · **行情例外**：`EVT_MARKET_QUOTE`（CH-006 后并入单一 HGCP 通道的可丢例外）断连或
//     满时直接丢弃、不保留、不背压——结构性承载：quote 路径永不入队（D4 裁定）。
//   · 重连握手完成（HELLO_ACK 后 `SessionReadySink` 回调）⇒ 保留队列**按序补发**；
//     补发帧先于任何 `MSG_RESUME_DATA` 上线（TCP 序）⇒ 对端补数不重不乱（D1 裁定）。
//
// 补数（验收 4 / §4.2.6「补数请求」行）：
//   · `MSG_RESUME_REQ` 入站消费 + `send_resume` 分批 ≤500 回放（server_loop 承载）；
//   · 缺口超缓冲 ⇒ 回 `MSG_ERROR{0x0306}` 后触发**全量状态重建**（`ResumeGapSink` →
//     `FullStateRebuilder` 接缝；缺省 `RiskStateFullRebuilder` = `EVT_POSITION_CHANGE`
//     全量 + `EVT_SYSTEM_STATE`）。重建经 `emit()` 上线 ⇒ 与主事件流同一 SEQ、同一
//     保序/保留路径。**0x0401 不立第二拒点**：重建同步执行于单线程消费路径，
//     拒新指令仍唯一由 `CommandHandler::system_ready()` 承载（D3 裁定）。
//
// 设计要点：单线程（T6「唯一写事件流」）＋时间无关（now_ms 由调用方注入 payload）＝
//   可确定性单测；`E4` 扫描根外（hgcp 合法 socket send）。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "hg/hgcp/payload_codec.h"  // PbPayloadCodec（6 种补齐编码）+ hgcp.pb.h
#include "hg/hgcp/server_loop.h"    // HgcpServerLoop + SessionReadySink/ResumeGapSink
#include "hg/risk_state/risk_state.h"  // RiskStateFullRebuilder 只读消费

namespace pbv1 = ::hgcp::v1;

namespace hg {
namespace hgcp {

// `eventQueue` 保留模式容量（§2.4.3；TASKS T-039 验收 3）。
const std::size_t kEventQueueCapacity = 16384u;

// 事件出站结果。
enum class EmitStatus {
  kSent = 0,          // 已上线（占用通道 SEQ 并入补数缓冲）
  kRetained = 1,      // 断连 ⇒ 入保留队列（不丢）
  kDroppedQuote = 2,  // 行情可丢例外 ⇒ 丢弃（不保留、不背压）
  kBackpressured = 3, // 保留队列满 ⇒ 背压（事件留在生产者侧，重试；不丢）
  kNoLoop = 4         // 未注入 server_loop（装配错误）
};

class EventEmitter;  // 前置声明（FullStateRebuilder::rebuild 以引用签名先行）

// 全量状态重建接缝（§4.2.6「补数不可达」行的 C++ 侧动作）。
// 实现经 `EventEmitter::emit()` 产出 `EVT_POSITION_CHANGE` 全量 + `EVT_SYSTEM_STATE`
// ⇒ 与主事件流同一 SEQ / 保序 / 保留路径。T4 单线程 ⇒ 重建同步执行，
// 期间不可能并发消费新指令（0x0401 仍归 `system_ready()` 单一门卫）。
class FullStateRebuilder {
 public:
  virtual ~FullStateRebuilder();
  virtual void rebuild(EventEmitter& sink) = 0;
};

// 事件出站编排器（单线程；loop 非拥有、须由调用方保证存活期覆盖本对象）。
class EventEmitter : public SessionReadySink, public ResumeGapSink {
 public:
  explicit EventEmitter(HgcpServerLoop* loop);

  // 注入 loop 的两个回调接缝（幂等；可 `detach()` 撤除）。
  void attach();
  void detach();

  // ---- 可靠事件统一出口（验收 1 的 14 种中除行情外的 13 种走此处）----
  // 已连接 ⇒ 直发（kSent）；断连 ⇒ 入保留队列（kRetained）；队列满 ⇒ kBackpressured
  // ＋告警（**不丢**：事件留在生产者侧）。误投行情类型 ⇒ 按可丢例外处置（守卫）。
  EmitStatus emit(std::uint16_t type, const std::vector<std::uint8_t>& body);

  // ---- 行情可丢例外（`EVT_MARKET_QUOTE` 0x020C）----
  // 已连接 ⇒ 直发；断连 ⇒ 丢弃（kDroppedQuote）。**永不入队、永不背压**（CH-006）。
  EmitStatus emit_quote(const std::vector<std::uint8_t>& body);

  // ---- 6 种补齐编码（CH-013 schema；8 种既有事件由 command_handler 编码后走 emit）----
  EmitStatus emit_position_change(const pbv1::EvtPositionChange& e);   // 0x0205（落库）
  EmitStatus emit_command_flow(const pbv1::EvtCommandFlow& e);         // 0x0208（落库）
  EmitStatus emit_fund_receipt(const pbv1::EvtFundReceipt& e);         // 0x020B（落库）
  EmitStatus emit_market_quote(const pbv1::EvtMarketQuote& e);         // 0x020C（可丢例外）
  EmitStatus emit_ctp_state(const pbv1::EvtCtpState& e);               // 0x020D
  EmitStatus emit_trading_alert(const pbv1::EvtTradingAlert& e);       // 0x020E

  // ---- SessionReadySink / ResumeGapSink（经 `attach()` 接线）----
  // 握手完成 ⇒ 保留队列按序补发（FIFO；发送中途断连 ⇒ 停止、剩余留队）。
  virtual void on_session_ready();
  // 0x0306 已由 server_loop 回发 ⇒ 触发全量状态重建（rebuilder 未注入 ⇒ 仅计数）。
  virtual void on_resume_gap();

  // 全量重建接缝（可空；缺省 null = 仅回 0x0306 不重建——诚实装配由上层注入）。
  void set_rebuilder(FullStateRebuilder* r) { rebuilder_ = r; }
  // 背压 / 丢弃告警接收方（可空；文案不含敏感信息）。签名沿用 ReplayController
  // 的 `ProgressFn` 形态（函数指针 + 用户指针，零依赖）。
  void set_alert_sink(void (*fn)(void* user, const char* what), void* user) {
    alert_fn_ = fn;
    alert_user_ = user;
  }

  // ---- 运行期观测 ----
  std::size_t retained() const { return queue_.size() - head_; }
  std::size_t capacity() const { return kEventQueueCapacity; }
  std::uint64_t backpressure_count() const { return backpressure_count_; }
  std::uint64_t quotes_dropped() const { return quotes_dropped_; }
  std::uint64_t flushed_count() const { return flushed_count_; }
  std::uint64_t rebuild_count() const { return rebuild_count_; }
  std::uint64_t gap_count() const { return gap_count_; }

 private:
  EventEmitter(const EventEmitter&);
  EventEmitter& operator=(const EventEmitter&);

  EmitStatus emit_reliable(std::uint16_t type, const std::vector<std::uint8_t>& body);
  EmitStatus emit_encoded(std::uint16_t type, const google::protobuf::MessageLite& msg,
                          bool droppable);
  void notify_alert(const char* what);

  HgcpServerLoop* loop_;
  FullStateRebuilder* rebuilder_;

  // 保留队列（FIFO；`head_` 消费游标，耗尽时整体收缩 ⇒ 均摊零拷贝）。
  struct Pending {
    std::uint16_t type;
    std::vector<std::uint8_t> body;
    Pending() : type(0) {}
    Pending(std::uint16_t t, const std::vector<std::uint8_t>& b) : type(t), body(b) {}
  };
  std::vector<Pending> queue_;
  std::size_t head_;

  void (*alert_fn_)(void* user, const char* what);
  void* alert_user_;

  std::uint64_t backpressure_count_;
  std::uint64_t quotes_dropped_;
  std::uint64_t flushed_count_;
  std::uint64_t rebuild_count_;
  std::uint64_t gap_count_;
};

// 缺省全量重建实现：消费 `RiskState`（只读快照）⇒ `EVT_POSITION_CHANGE` 全量 +
// `EVT_SYSTEM_STATE`（当前 param_version，state=READY，§4.2.6「补数不可达」行）。
// 持仓键解包 = `packKey3(sub, (dir<<4)|type, instrument)`（risk_state load_batch 形态）；
// 定点数量（scale=1e4）→ int32 整数单位；instrument_id 取 `InstrumentMeta.instrument_code`。
class RiskStateFullRebuilder : public FullStateRebuilder {
 public:
  // 不取得 state 所有权；须由调用方保证存活期覆盖本对象。
  explicit RiskStateFullRebuilder(const hg::risk_state::RiskState* state);

  // 判定时点（`EvtPositionChange.as_of_ts_ms` / `EvtSystemState.ts_ms`；由装配注入）。
  void set_now_ms(std::int64_t now_ms) { now_ms_ = now_ms; }

  virtual void rebuild(EventEmitter& sink);

 private:
  RiskStateFullRebuilder(const RiskStateFullRebuilder&);
  RiskStateFullRebuilder& operator=(const RiskStateFullRebuilder&);

  const hg::risk_state::RiskState* state_;
  std::int64_t now_ms_;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_EVENT_EMITTER_H
