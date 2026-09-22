// CTP 适配层（ctp_adapter）—— 适配逻辑实现（ARCHITECTURE.md §3.1）。
// SDK 无关：真实 SDK 细节全部封在 CtTradeGateway / CtMdGateway（见 ct_gateway*.cc）。
#include "hg/ctp_adapter/ct_adapter.h"

#include <chrono>

namespace hg {
namespace ctp_adapter {

namespace {

// orderEventQueue 满时的有界自旋上界（§2.4.3「告警 + 自旋等待」的有界化：
// 避免 CTP 回调线程被无限阻塞；超过上界由 order_event_overflow_ 记告警）。
const std::size_t kOrderEventSpinLimit = 1024;

bool validOrder(const CtOrderReq& req) {
  if (req.client_order_ref.empty()) return false;
  if (req.instrument_id.empty()) return false;
  if (req.volume <= 0) return false;
  if (req.price_type == CtPriceType::kLimit && req.limit_price <= 0.0) return false;
  return true;
}

bool validCancel(const CtCancelReq& req) {
  return !req.exchange_id.empty() && !req.order_sys_id.empty();
}

bool validFund(const CtFundReq& req) {
  if (req.idempotency_key.empty()) return false;
  if (req.account_id.empty()) return false;
  if (req.amount <= 0.0) return false;
  return true;
}

}  // namespace

std::int64_t nowMillis() {
  const std::chrono::system_clock::duration d =
      std::chrono::system_clock::now().time_since_epoch();
  return std::chrono::duration_cast<std::chrono::milliseconds>(d).count();
}

CtAdapter::CtAdapter()
    : trade_(createTradeGateway(this)),
      md_(createMdGateway(this)),
      own_(true),
      trade_connected_(false),
      trade_logged_in_(false),
      md_connected_(false),
      md_logged_in_(false),
      request_seq_(0),
      order_events_(new RingBuffer<CtOrderEvent, kOrderEventQueueCapacity + 1>()),
      quote_events_(new QuoteQueue(kQuoteQueueCapacity)),
      order_event_overflow_(0) {}

CtAdapter::CtAdapter(CtTradeGateway* trade, CtMdGateway* md, bool own)
    : trade_(trade),
      md_(md),
      own_(own),
      trade_connected_(false),
      trade_logged_in_(false),
      md_connected_(false),
      md_logged_in_(false),
      request_seq_(0),
      order_events_(new RingBuffer<CtOrderEvent, kOrderEventQueueCapacity + 1>()),
      quote_events_(new QuoteQueue(kQuoteQueueCapacity)),
      order_event_overflow_(0) {}

CtAdapter::~CtAdapter() {
  if (own_) {
    delete trade_;
    delete md_;
  }
  delete order_events_;
  delete quote_events_;
}

int CtAdapter::nextRequestId() {
  ++request_seq_;
  if (request_seq_ <= 0) request_seq_ = 1;  // 溢出保护（CTP RequestID 为正整数）
  return request_seq_;
}

void CtAdapter::retainOrder(const CtOrderReq& req) { retained_orders_.push_back(req); }

void CtAdapter::retainFund(const CtFundReq& req) { retained_funds_.push_back(req); }

// -----------------------------------------------------------------------------
// 对外接口（§3.1）
// -----------------------------------------------------------------------------
CtLoginResult CtAdapter::login(const CtConfig& cfg) {
  CtLoginResult result;
  cfg_ = cfg;  // 口令仅内存持有（不落盘、不入日志）；打印一律用 cfg_.redacted()
  std::string error;

  if (!trade_->connect(cfg, &error)) {
    result.code = kCtNotConnected;
    result.error_msg = error.empty() ? std::string("CTP 交易前置连接失败") : error;
    return result;
  }
  // 行情连接失败不阻断交易链路（行情可缺；FR-TRADE-12 验收 3）。
  std::string md_error;
  (void)md_->connect(cfg, &md_error);

  const int rc = trade_->reqLogin(cfg, nextRequestId());
  if (rc != 0) {
    result.code = (rc > 0) ? kCtBackpressure : kCtNotConnected;
    result.error_msg = "ReqUserLogin 未被 CTP 受理（异步模型：结果以回调为准）";
    return result;
  }
  (void)md_->reqLogin(cfg, nextRequestId());  // 行情登录失败仅影响行情
  result.code = kCtOk;
  return result;
}

void CtAdapter::logout() {
  if (trade_logged_in_) {
    (void)trade_->reqLogout(nextRequestId());
  }
  trade_->disconnect();
  md_->disconnect();
  trade_connected_ = false;
  trade_logged_in_ = false;
  md_connected_ = false;
  md_logged_in_ = false;
  trading_day_.clear();
}

bool CtAdapter::isConnected() const { return trade_connected_; }
bool CtAdapter::isLoggedIn() const { return trade_logged_in_; }
bool CtAdapter::isMdConnected() const { return md_connected_; }
bool CtAdapter::isMdLoggedIn() const { return md_logged_in_; }

CtOrderAck CtAdapter::sendOrder(const CtOrderReq& req) {
  CtOrderAck ack;
  ack.client_order_ref = req.client_order_ref;

  if (!validOrder(req)) {
    ack.code = kCtUnsupportedOperation;
    return ack;
  }
  if (!trade_logged_in_) {
    // 0x2004 CTP_NOT_CONNECTED：指令退回并保留，**不得静默丢弃**（FR-TRADE-2 验收 4）。
    ack.code = kCtNotConnected;
    retainOrder(req);
    ack.retained = true;
    return ack;
  }

  const int rc = trade_->reqOrderInsert(req, nextRequestId());
  ack.code = (rc == 0) ? kCtOk : ((rc > 0) ? kCtBackpressure : kCtNotConnected);
  if (ack.code != kCtOk) {
    retainOrder(req);  // 发送失败同样退回保留
    ack.retained = true;
  }
  return ack;
}

CtCancelAck CtAdapter::cancelOrder(const CtCancelReq& req) {
  CtCancelAck ack;
  if (!validCancel(req)) {
    ack.code = kCtUnsupportedOperation;
    ack.error_msg = "撤单需 exchange_id 与 order_sys_id";
    return ack;
  }
  if (!trade_logged_in_) {
    ack.code = kCtNotConnected;
    ack.error_msg = "CTP 交易前置未登录";
    return ack;
  }
  const int rc = trade_->reqOrderAction(req, nextRequestId());
  ack.code = (rc == 0) ? kCtOk : ((rc > 0) ? kCtBackpressure : kCtNotConnected);
  return ack;
}

CtSubscribeAck CtAdapter::subscribeMarket(const std::vector<std::string>& instruments) {
  CtSubscribeAck ack;
  if (instruments.empty()) {
    ack.code = kCtUnsupportedOperation;
    return ack;
  }
  if (!md_logged_in_) {
    ack.code = kCtNotConnected;
    return ack;
  }
  const int rc = md_->subscribeMarket(instruments, nextRequestId());
  ack.code = (rc == 0) ? kCtOk : ((rc > 0) ? kCtBackpressure : kCtNotConnected);
  if (ack.code == kCtOk) ack.accepted = static_cast<int>(instruments.size());
  return ack;
}

CtFundAck CtAdapter::reqFundTransfer(const CtFundReq& req) {
  CtFundAck ack;
  if (!validFund(req)) {
    ack.code = kCtUnsupportedOperation;
    ack.error_msg = "资金划转需幂等键 / 账户 / 正金额";
    return ack;
  }
  if (!trade_logged_in_) {
    ack.code = kCtNotConnected;
    ack.error_msg = "CTP 交易前置未登录";
    retainFund(req);  // 退回保留（幂等键原样带回，重试不产生第二笔）
    return ack;
  }
  const int rc = trade_->reqFundTransfer(req, nextRequestId());
  ack.code = (rc == 0) ? kCtOk : ((rc > 0) ? kCtBackpressure : kCtNotConnected);
  if (ack.code != kCtOk) retainFund(req);
  return ack;
}

// -----------------------------------------------------------------------------
// SPI 回调侧：**只做**序列化 + 入队 + 轻量状态位（§3.1）
// -----------------------------------------------------------------------------
void CtAdapter::onFrontConnected(bool market) {
  if (market) {
    md_connected_ = true;
  } else {
    trade_connected_ = true;
  }
}

void CtAdapter::onFrontDisconnected(bool market, int /*reason*/) {
  // nReason（0x1001 网络读失败 / 0x2001 心跳超时 …）当前仅用于日志/告警，本层不落盘。
  if (market) {
    md_connected_ = false;
    md_logged_in_ = false;
  } else {
    trade_connected_ = false;
    trade_logged_in_ = false;
  }
}

void CtAdapter::onLoginReply(bool market, bool ok, const std::string& trading_day,
                             int /*error_id*/, const std::string& /*error_msg*/) {
  if (market) {
    md_logged_in_ = ok;
    return;
  }
  trade_logged_in_ = ok;
  if (ok) trading_day_ = trading_day;
}

void CtAdapter::onOrderEvent(const CtOrderEvent& event) {
  // §2.4.3：orderEventQueue 满时「告警 + 自旋等待」，**不得丢回报**。
  if (!order_events_->pushSpinning(event, kOrderEventSpinLimit)) {
    ++order_event_overflow_;  // 超过有界自旋上界 → 告警计数（不静默）
  }
}

void CtAdapter::onQuoteEvent(const CtQuoteEvent& event) {
  // §2.4.3：quoteQueue 满时「丢弃最旧」（行情可丢）；丢弃次数可观测。
  (void)quote_events_->pushDropOldest(event);
}

// -----------------------------------------------------------------------------
// 队列消费面
// -----------------------------------------------------------------------------
RingBuffer<CtOrderEvent, kOrderEventQueueCapacity + 1>* CtAdapter::orderEventQueue() {
  return order_events_;
}

QuoteQueue* CtAdapter::quoteQueue() { return quote_events_; }

bool CtAdapter::pollOrderEvent(CtOrderEvent* out) { return order_events_->tryPop(out); }

bool CtAdapter::pollQuoteEvent(CtQuoteEvent* out) { return quote_events_->tryPop(out); }

std::size_t CtAdapter::orderEventOverflowCount() const { return order_event_overflow_; }

// -----------------------------------------------------------------------------
// 退回保留面
// -----------------------------------------------------------------------------
std::size_t CtAdapter::retainedOrderCount() const { return retained_orders_.size(); }

std::size_t CtAdapter::retainedFundCount() const { return retained_funds_.size(); }

bool CtAdapter::takeRetainedOrder(CtOrderReq* out) {
  if (retained_orders_.empty()) return false;
  *out = retained_orders_.front();
  retained_orders_.pop_front();
  return true;
}

bool CtAdapter::takeRetainedFund(CtFundReq* out) {
  if (retained_funds_.empty()) return false;
  *out = retained_funds_.front();
  retained_funds_.pop_front();
  return true;
}

const char* CtAdapter::backendName() const { return trade_->backendName(); }

const char* CtAdapter::apiVersion() const { return trade_->apiVersion(); }

bool CtAdapter::ownsBackends() const { return own_; }

const CtConfig& CtAdapter::config() const { return cfg_; }

}  // namespace ctp_adapter
}  // namespace hg
