#ifndef HG_CTP_ADAPTER_CT_ADAPTER_H
#define HG_CTP_ADAPTER_CT_ADAPTER_H
// =============================================================================
// CTP 适配层（ctp_adapter）—— 对外接口（M1）
//
// 承载（逐条对齐 docs/ARCHITECTURE.md §3.1 对外接口）：
//   · `login(CtConfig)` / `logout()` / `isConnected()`
//   · `sendOrder(const CtOrderReq&)` → `CtOrderAck`（承载 `OrderLocalId` + 组件错误码）
//   · `cancelOrder(const CtCancelReq&)`
//   · `subscribeMarket(const std::vector<std::string>&)`
//   · `reqFundTransfer(const CtFundReq&)`（银期划转，异步）
//
// 需求：FR-TRADE-2/4/5/6/12、FR-FUND-5/6/8、NFR-COMPAT-6。
//
// 语义要点：
//   1. **异步回调模型**：`login` / `sendOrder` / … 的返回只表示「请求已交给 CTP」
//      （或「未连接被退回」），**不假设同步返回**；真实结果经 SPI 回调入队。
//   2. **CTP 未连接（交易前置未登录）**：`sendOrder` 回 `0x2004`（`CTP_NOT_CONNECTED`），
//      指令**退回并保留**（`retained()==true`，可 `takeRetainedOrder` 取回重试），
//      **不得静默丢弃**（FR-TRADE-2 验收 4；§4.2.6 CTP 不可用行）。
//   3. **SPI 回调只做序列化并投递队列**：`onOrderEvent` / `onQuoteEvent` 仅入队
//      （orderEventQueue 满时有界自旋 + 告警计数；quoteQueue 满时丢弃最旧）。
//   4. **期货与期权**共用同一交易接口与同一报单结构（`PROJECT.md` §10-10 /
//      NFR-COMPAT-6）；`CtOrderReq::market` 显式标注类别并被原样传递。
//
// 线程归属（ARCHITECTURE.md §2.4.2）：
//   `sendOrder` 系 T4 可达面 ⇒ **本类在发送/退回路径上不使用锁**（退回存储仅 T4 访问，
//   文档化单线程访问；队列为 SPSC 无锁）。`quoteQueue` 由 T3 写 / T8 读，不在 T4 路径。
// =============================================================================

#include <condition_variable>
#include <cstddef>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include "hg/ctp_adapter/ct_gateway.h"
#include "hg/ctp_adapter/ct_queues.h"
#include "hg/ctp_adapter/ct_types.h"

namespace hg {
namespace ctp_adapter {

class CtAdapter : public CtCallbackSink {
 public:
  // 默认后端：按 `HG_CTP_FOUND` 选真实 SDK 网关 / stub（工厂）。
  CtAdapter();
  // 注入后端（单测 / 装配）；`own==true` 时析构释放后端。
  CtAdapter(CtTradeGateway* trade, CtMdGateway* md, bool own);
  virtual ~CtAdapter();

  // ---- 对外接口（§3.1）------------------------------------------------------
  CtLoginResult login(const CtConfig& cfg);
  void logout();
  bool isConnected() const;   // 交易前置已连通（OnFrontConnected）
  bool isLoggedIn() const;    // 交易前置已登录（OnRspUserLogin 成功）
  bool isMdConnected() const;
  bool isMdLoggedIn() const;

  CtOrderAck sendOrder(const CtOrderReq& req);
  CtCancelAck cancelOrder(const CtCancelReq& req);
  CtSubscribeAck subscribeMarket(const std::vector<std::string>& instruments);
  CtFundAck reqFundTransfer(const CtFundReq& req);

  // ---- 报单状态查询（§4.5.3 ⑤；T-212 生产实现）-----------------------------
  // 同步结论：发起 `ReqQryOrder` → **有界等待** `OnRspQryOrder` 回调 → 按
  //   `order_sys_id`（已知）/ `client_order_ref`（否则）选行 → 归一化结论。
  // 未登录 / 请求被拒 ⇒ outcome=kUnavailable；有界等待超时 ⇒ outcome=kTimeout
  //   （**保守**：状态未知，调用方须冻结不释放）。
  // ⚠️ D-1（安全失败）：`client_order_ref` 超过 `kCtOrderRefCapacity`（12 字节 =
  //   `TThostFtdcOrderRefType[13]` 的可用上限）且无 `order_sys_id` 时，**不发起**查询，
  //   直接 outcome=kUnavailable 并在 `ctp_error_msg` 说明 —— 因为 CTP 回填的 `OrderRef`
  //   必然是被 `setField` 静默截断的 12 字节值，与流水侧 16 字符 ref 不可比：按整串比 ⇒
  //   误判 kNotFound（释放冻结）；按截断比 ⇒ 同日 100 笔撞同一串（选行错配）。
  //   **禁止**新增「截断后比较」的调用方。
  // ⚠️ 性能（D-2 披露）：本方法是**同步有界等待**，重放期由装配线程**逐单串行**调用
  //   （**未并发化**，与 `ARCHITECTURE.md` §4.5.3「并发查询」的性能目标存在已知缺口）；
  //   每笔最坏耗时 = `timeout_ms`，累计上限由装配层 `CtOrderStateQueryBridge` 的
  //   **总预算**（`NFR-REL-1` 60 s）兜底，见 `order_state_query_bridge.h`。
  // 线程说明：由 T4/装配线程调用（重放启动期）；回调侧 T2 仅投递查询回复。
  CtOrderQueryReply queryOrder(const CtOrderQueryReq& req, std::int64_t timeout_ms);

  // 异步发起（回填 request_id）；非阻塞，供需要自行掌控等待节奏的调用方/测试。
  // 返回 kCtOk 表示请求已交 CTP；未登录 ⇒ kCtNotConnected；网关拒收 ⇒ 背压/未连接；
  // 超长 `client_order_ref`（> 12 字节 `OrderRef` 上限）⇒ kCtUnsupportedOperation（D-1）。
  CtCode requestOrderQuery(const CtOrderQueryReq& req, int* request_id);
  // 非阻塞取一条查询回复（诊断/测试；按到达序，**不**按 request_id 过滤）。
  bool pollOrderQueryReply(CtOrderQueryReply* out);

  // ---- SPI 回调侧（CtCallbackSink 实现；只做序列化 + 入队 + 轻量状态位）-------
  virtual void onFrontConnected(bool market);
  virtual void onFrontDisconnected(bool market, int reason);
  virtual void onLoginReply(bool market, bool ok, const std::string& trading_day,
                            int error_id, const std::string& error_msg);
  virtual void onOrderEvent(const CtOrderEvent& event);
  virtual void onQuoteEvent(const CtQuoteEvent& event);
  // T-212：查询回复只做「归一化 + 入队 + 唤醒等待方」（不做重活）。
  virtual void onQueryOrderReply(const CtOrderQueryReply& reply);

  // ---- 队列消费面（消费者：orderEventQueue → T4；quoteQueue → T8）-----------
  RingBuffer<CtOrderEvent, kOrderEventQueueCapacity + 1>* orderEventQueue();
  QuoteQueue* quoteQueue();
  bool pollOrderEvent(CtOrderEvent* out);
  bool pollQuoteEvent(CtQuoteEvent* out);
  std::size_t orderEventOverflowCount() const;  // 有界自旋仍未入队的告警计数

  // ---- 「指令退回、不静默丢弃」证据面（仅 T4 访问）--------------------------
  std::size_t retainedOrderCount() const;
  std::size_t retainedFundCount() const;
  bool takeRetainedOrder(CtOrderReq* out);  // FIFO：取回最早一条被退回指令
  bool takeRetainedFund(CtFundReq* out);

  const char* backendName() const;
  const char* apiVersion() const;
  bool ownsBackends() const;
  const CtConfig& config() const;  // 内存持有；口令不得外部落盘/打印（用 redacted()）

 private:
  CtAdapter(const CtAdapter&);
  CtAdapter& operator=(const CtAdapter&);

  int nextRequestId();
  void retainOrder(const CtOrderReq& req);
  void retainFund(const CtFundReq& req);
  // 查询回复是否命中本次请求（已知 order_sys_id 优先，否则按 client_order_ref）。
  static bool matchesQuery(const CtOrderQueryReq& req, const CtOrderQueryReply& reply);

  CtTradeGateway* trade_;
  CtMdGateway* md_;
  bool own_;
  bool trade_connected_;
  bool trade_logged_in_;
  bool md_connected_;
  bool md_logged_in_;
  int request_seq_;
  std::string trading_day_;
  RingBuffer<CtOrderEvent, kOrderEventQueueCapacity + 1>* order_events_;
  QuoteQueue* quote_events_;
  std::deque<CtOrderReq> retained_orders_;
  std::deque<CtFundReq> retained_funds_;
  // T-212：查询回复通道（T2 回调写 / T4 或装配线程读；ctp_adapter 允许互斥体）。
  std::mutex query_mutex_;
  std::condition_variable query_cv_;
  std::deque<CtOrderQueryReply> query_replies_;
  std::size_t order_event_overflow_;
  CtConfig cfg_;
};

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_ADAPTER_CT_ADAPTER_H
