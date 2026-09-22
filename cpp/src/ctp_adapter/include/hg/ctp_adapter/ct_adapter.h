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
//   4. **期货与期权**共用同一 UserApi 与同一报单结构（`PROJECT.md` §10-10 /
//      NFR-COMPAT-6）；`CtOrderReq::market` 显式标注类别并被原样传递。
//
// 线程归属（ARCHITECTURE.md §2.4.2）：
//   `sendOrder` 系 T4 可达面 ⇒ **本类在发送/退回路径上不使用锁**（退回存储仅 T4 访问，
//   文档化单线程访问；队列为 SPSC 无锁）。`quoteQueue` 由 T3 写 / T8 读，不在 T4 路径。
// =============================================================================

#include <cstddef>
#include <deque>
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

  // ---- SPI 回调侧（CtCallbackSink 实现；只做序列化 + 入队 + 轻量状态位）-------
  virtual void onFrontConnected(bool market);
  virtual void onFrontDisconnected(bool market, int reason);
  virtual void onLoginReply(bool market, bool ok, const std::string& trading_day,
                            int error_id, const std::string& error_msg);
  virtual void onOrderEvent(const CtOrderEvent& event);
  virtual void onQuoteEvent(const CtQuoteEvent& event);

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
  std::size_t order_event_overflow_;
  CtConfig cfg_;
};

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_ADAPTER_CT_ADAPTER_H
