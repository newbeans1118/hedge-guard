// 单测：ctp_adapter 适配逻辑（ARCHITECTURE.md §3.1；两轨共用）。
//   承重项：
//     ① CTP 未连接 ⇒ `sendOrder` 回 `0x2004` 且指令**退回保留、不静默丢弃**
//        （FR-TRADE-2 验收 4）。
//     ② SPI 回调侧**只做**序列化 + 入队（onOrderEvent / onQuoteEvent 不改后端、只动队列）。
//     ③ 期货与期权**均覆盖**（同一交易接口报单面，类别由 `CtOrderReq::market` 标注）。
#include <string>
#include <vector>

#include "ctp_adapter_test_util.h"
#include "fake_gateway.h"
#include "hg/ctp_adapter/ct_adapter.h"

using hg::ctp_adapter::CtAdapter;
using hg::ctp_adapter::CtConfig;
using hg::ctp_adapter::CtDirection;
using hg::ctp_adapter::CtFundDirection;
using hg::ctp_adapter::CtMarket;
using hg::ctp_adapter::CtOffset;
using hg::ctp_adapter::CtOrderEvent;
using hg::ctp_adapter::CtOrderEventKind;
using hg::ctp_adapter::CtOrderReq;
using hg::ctp_adapter::CtPriceType;
using hg::ctp_adapter::CtQuoteEvent;
using hg::ctp_adapter::kCtNotConnected;
using hg::ctp_adapter::kCtOk;
using hg::ctp_adapter::kCtUnsupportedOperation;
using hg::ctp_adapter::testutil::FakeMdGateway;
using hg::ctp_adapter::testutil::FakeTradeGateway;
using hg::ctp_adapter::testutil::makeCancel;
using hg::ctp_adapter::testutil::makeFund;
using hg::ctp_adapter::testutil::makeOrder;

namespace {

CtConfig makeConfig() {
  CtConfig cfg;
  cfg.trade_front = "tcp://127.0.0.1:17001";
  cfg.md_front = "tcp://127.0.0.1:17002";
  cfg.broker_id = "9999";
  cfg.user_id = "investor01";
  cfg.password = "DUMMY_PASSWORD";
  cfg.flow_path = "/root/hg-flow";
  return cfg;
}

// 走完「连通 + 交易/行情登录成功」的异步回调路径。
void bringOnline(CtAdapter* adapter) {
  adapter->onFrontConnected(false);
  adapter->onFrontConnected(true);
  adapter->onLoginReply(false, true, "20260922", 0, "");
  adapter->onLoginReply(true, true, "20260922", 0, "");
}

void default_backend_matches_build_track() {
  CtAdapter adapter;  // 默认工厂后端（不调用 login ⇒ 不触发真实连接）
#if HG_CTP_FOUND
  HG_CHECK_EQ(std::string(adapter.backendName()), std::string("ctp-sdk"));
#else
  HG_CHECK_EQ(std::string(adapter.backendName()), std::string("stub"));
#endif
  HG_CHECK(adapter.ownsBackends());
}

void login_connect_failure_returns_not_connected() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  trade.connect_ok = false;
  CtAdapter adapter(&trade, &md, false);
  const CtConfig cfg = makeConfig();
  const hg::ctp_adapter::CtLoginResult r = adapter.login(cfg);
  HG_CHECK_EQ(r.code, kCtNotConnected);
  HG_CHECK(!r.error_msg.empty());
  HG_CHECK_EQ(trade.logins, 0);  // 连接失败即不再发登录请求
  HG_CHECK(!adapter.isConnected());
  HG_CHECK(!adapter.isLoggedIn());
}

void login_success_is_async_state_driven() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  const CtConfig cfg = makeConfig();
  const hg::ctp_adapter::CtLoginResult r = adapter.login(cfg);
  HG_CHECK_EQ(r.code, kCtOk);          // 只是「请求已交给 CTP」
  HG_CHECK(!adapter.isConnected());    // 尚在未连接（异步）
  HG_CHECK(!adapter.isLoggedIn());
  HG_CHECK_EQ(trade.logins, 1);
  HG_CHECK_EQ(md.logins, 1);

  bringOnline(&adapter);
  HG_CHECK(adapter.isConnected());
  HG_CHECK(adapter.isLoggedIn());
  HG_CHECK(adapter.isMdConnected());
  HG_CHECK(adapter.isMdLoggedIn());

  adapter.logout();
  HG_CHECK(!adapter.isConnected());
  HG_CHECK(!adapter.isLoggedIn());
}

// 承重 ①：未连接 ⇒ 0x2004 + 退回保留（不静默丢弃）。
void disconnected_send_order_is_returned_not_dropped() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  const CtOrderReq req = makeOrder("REF-0001", "rb2601", CtMarket::kFutures);

  const hg::ctp_adapter::CtOrderAck ack = adapter.sendOrder(req);
  HG_CHECK_EQ(ack.code, kCtNotConnected);
  HG_CHECK_EQ(ack.client_order_ref, std::string("REF-0001"));
  HG_CHECK(ack.retained);
  HG_CHECK_EQ(adapter.retainedOrderCount(), std::size_t(1));
  HG_CHECK_EQ(trade.inserts, 0);  // 未发往 CTP

  // 指令可被取回重试（证明保留、未丢弃）
  CtOrderReq taken;
  HG_CHECK(adapter.takeRetainedOrder(&taken));
  HG_CHECK_EQ(taken.client_order_ref, std::string("REF-0001"));
  HG_CHECK_EQ(taken.instrument_id, std::string("rb2601"));
  HG_CHECK_EQ(adapter.retainedOrderCount(), std::size_t(0));
  HG_CHECK(!adapter.takeRetainedOrder(&taken));
}

void connected_send_order_is_forwarded() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  (void)adapter.login(makeConfig());
  bringOnline(&adapter);

  const CtOrderReq req = makeOrder("REF-0002", "rb2601", CtMarket::kFutures);
  const hg::ctp_adapter::CtOrderAck ack = adapter.sendOrder(req);
  HG_CHECK_EQ(ack.code, kCtOk);
  HG_CHECK(!ack.retained);
  HG_CHECK_EQ(trade.inserts, 1);
  HG_CHECK_EQ(trade.last_order.client_order_ref, std::string("REF-0002"));
  HG_CHECK_EQ(adapter.retainedOrderCount(), std::size_t(0));
}

// 承重 ③：期货与期权均覆盖。
void futures_and_options_are_both_covered() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  (void)adapter.login(makeConfig());
  bringOnline(&adapter);

  const CtOrderReq futures = makeOrder("REF-FUT", "rb2601", CtMarket::kFutures);
  CtOrderReq option = makeOrder("REF-OPT", "rb2601C3600", CtMarket::kOption);
  option.offset = CtOffset::kCloseToday;

  const hg::ctp_adapter::CtOrderAck a1 = adapter.sendOrder(futures);
  const hg::ctp_adapter::CtOrderAck a2 = adapter.sendOrder(option);
  HG_CHECK_EQ(a1.code, kCtOk);
  HG_CHECK_EQ(a2.code, kCtOk);
  HG_CHECK_EQ(trade.inserts, 2);
  HG_CHECK(trade.last_order.market == CtMarket::kOption);
  HG_CHECK_EQ(trade.last_order.instrument_id, std::string("rb2601C3600"));
  HG_CHECK(trade.last_order.offset == CtOffset::kCloseToday);
}

void invalid_order_is_rejected_without_retention() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  (void)adapter.login(makeConfig());
  bringOnline(&adapter);

  CtOrderReq bad = makeOrder("REF-BAD", "rb2601", CtMarket::kFutures);
  bad.volume = 0;
  const hg::ctp_adapter::CtOrderAck ack = adapter.sendOrder(bad);
  HG_CHECK_EQ(ack.code, kCtUnsupportedOperation);
  HG_CHECK_EQ(trade.inserts, 0);
  HG_CHECK_EQ(adapter.retainedOrderCount(), std::size_t(0));

  // 限价单缺价格
  CtOrderReq no_price = makeOrder("REF-NP", "rb2601", CtMarket::kFutures);
  no_price.price_type = CtPriceType::kLimit;
  no_price.limit_price = 0.0;
  HG_CHECK_EQ(adapter.sendOrder(no_price).code, kCtUnsupportedOperation);
}

void cancel_and_subscribe_require_login() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  // 未登录
  HG_CHECK_EQ(adapter.cancelOrder(makeCancel("SYS-1")).code, kCtNotConnected);
  std::vector<std::string> instruments;
  instruments.push_back("rb2601");
  HG_CHECK_EQ(adapter.subscribeMarket(instruments).code, kCtNotConnected);

  (void)adapter.login(makeConfig());
  bringOnline(&adapter);
  HG_CHECK_EQ(adapter.cancelOrder(makeCancel("SYS-1")).code, kCtOk);
  HG_CHECK_EQ(trade.actions, 1);
  const hg::ctp_adapter::CtSubscribeAck sub = adapter.subscribeMarket(instruments);
  HG_CHECK_EQ(sub.code, kCtOk);
  HG_CHECK_EQ(sub.accepted, 1);
  HG_CHECK_EQ(md.subscribes, 1);
  HG_CHECK_EQ(md.last_instruments.size(), std::size_t(1));
}

void fund_transfer_is_async_and_retained_when_disconnected() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);

  const hg::ctp_adapter::CtFundAck ack =
      adapter.reqFundTransfer(makeFund(CtFundDirection::kOut));
  HG_CHECK_EQ(ack.code, kCtNotConnected);
  HG_CHECK_EQ(adapter.retainedFundCount(), std::size_t(1));
  HG_CHECK_EQ(trade.transfers, 0);
  hg::ctp_adapter::CtFundReq taken;
  HG_CHECK(adapter.takeRetainedFund(&taken));
  HG_CHECK_EQ(taken.idempotency_key, std::string("IDEM-0001"));

  (void)adapter.login(makeConfig());
  bringOnline(&adapter);
  const hg::ctp_adapter::CtFundAck ok =
      adapter.reqFundTransfer(makeFund(CtFundDirection::kIn));
  HG_CHECK_EQ(ok.code, kCtOk);
  HG_CHECK_EQ(trade.transfers, 1);
  HG_CHECK(trade.last_fund.direction == CtFundDirection::kIn);
  // 非法（缺幂等键）
  hg::ctp_adapter::CtFundReq bad = makeFund(CtFundDirection::kIn);
  bad.idempotency_key.clear();
  HG_CHECK_EQ(adapter.reqFundTransfer(bad).code, kCtUnsupportedOperation);
}

// 承重 ②：SPI 回调侧只做「序列化 + 入队」——不动后端、不产生额外请求。
void sink_only_serializes_and_enqueues() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  (void)adapter.login(makeConfig());
  bringOnline(&adapter);
  const int inserts_before = trade.inserts;

  CtOrderEvent trade_event;
  trade_event.kind = CtOrderEventKind::kTradeReturn;
  trade_event.instrument_id = "rb2601";
  trade_event.trade_id = "T-1";
  trade_event.price = 3512.0;
  trade_event.trade_volume = 2;
  trade_event.direction = '0';
  trade_event.offset_flag = '0';
  adapter.onOrderEvent(trade_event);

  CtOrderEvent out;
  HG_CHECK_EQ(adapter.orderEventQueue()->size(), std::size_t(1));
  HG_CHECK(adapter.pollOrderEvent(&out));
  HG_CHECK(out.kind == CtOrderEventKind::kTradeReturn);
  HG_CHECK_EQ(out.trade_id, std::string("T-1"));
  HG_CHECK_EQ(out.trade_volume, 2);

  CtQuoteEvent quote;
  quote.instrument_id = "rb2601";
  quote.last_price = 3512.5;
  adapter.onQuoteEvent(quote);
  CtQuoteEvent qout;
  HG_CHECK(adapter.pollQuoteEvent(&qout));
  HG_CHECK_EQ(qout.instrument_id, std::string("rb2601"));

  // 回调未触发任何 CTP 请求（未把业务逻辑塞进回调）
  HG_CHECK_EQ(trade.inserts, inserts_before);
  HG_CHECK_EQ(trade.transfers, 0);
  HG_CHECK_EQ(adapter.orderEventOverflowCount(), std::size_t(0));
}

void order_event_queue_overflow_is_counted_not_silent() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  const std::size_t capacity = adapter.orderEventQueue()->capacity();
  HG_CHECK_EQ(capacity, hg::ctp_adapter::kOrderEventQueueCapacity);

  CtOrderEvent event;
  event.instrument_id = "rb2601";
  for (std::size_t i = 0; i < capacity; ++i) {
    adapter.onOrderEvent(event);
  }
  HG_CHECK_EQ(adapter.orderEventQueue()->size(), capacity);
  HG_CHECK_EQ(adapter.orderEventOverflowCount(), std::size_t(0));

  adapter.onOrderEvent(event);  // 满：有界自旋后仍失败 ⇒ 记告警（不静默）
  HG_CHECK_EQ(adapter.orderEventOverflowCount(), std::size_t(1));
  HG_CHECK_EQ(adapter.orderEventQueue()->size(), capacity);
}

void quote_queue_drop_is_observable() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  const std::size_t capacity = adapter.quoteQueue()->capacity();
  HG_CHECK_EQ(capacity, hg::ctp_adapter::kQuoteQueueCapacity);

  CtQuoteEvent quote;
  quote.instrument_id = "rb2601";
  for (std::size_t i = 0; i < capacity; ++i) adapter.onQuoteEvent(quote);
  adapter.onQuoteEvent(quote);  // 满：丢弃最旧
  HG_CHECK_EQ(adapter.quoteQueue()->size(), capacity);
  HG_CHECK_EQ(adapter.quoteQueue()->droppedCount(), std::size_t(1));
}

void credentials_are_never_echoed() {
  const CtConfig cfg = makeConfig();
  const std::string redacted = cfg.redacted();
  HG_CHECK(redacted.find("DUMMY_PASSWORD") == std::string::npos);  // 口令不得出现在日志呈现
  HG_CHECK(redacted.find("<redacted>") != std::string::npos);
  HG_CHECK(redacted.find("investor01") != std::string::npos);  // 非敏感字段保留
}

// U1：CtConfig::env_type ↔ 官方 `bIsProductionMode`（CFG-22 `hg.ctp.env_type`）。
void config_env_type_maps_to_production_mode() {
  CtConfig default_cfg;
  // 缺省 = kSimulation（**与架构已登记 CFG-22 的缺省逐字一致**；fail-safe：
  // 缺省指向评测前置 ⇒ 配错只「连不上/不交易」，不会误连实盘）。
  // ★ 行为变更（技术席裁定 2026-09-22）：改前「不配置 ⇒ 生产」，改后「不配置 ⇒ 评测」。
  HG_CHECK(default_cfg.env_type == hg::ctp_adapter::CtEnvType::kSimulation);
  HG_CHECK(!default_cfg.productionMode());  // 缺省 ⇒ bIsProductionMode=false
  HG_CHECK(default_cfg.redacted().find("env_type=SIMULATION") != std::string::npos);
  // 生效模式可观测（防缺省悄悄生效而不被看见）
  HG_CHECK(default_cfg.redacted().find("env_type=") != std::string::npos);

  CtConfig prod = makeConfig();
  prod.env_type = hg::ctp_adapter::CtEnvType::kProduction;
  HG_CHECK(prod.productionMode());
  HG_CHECK(prod.redacted().find("env_type=PRODUCTION") != std::string::npos);

  CtConfig sim = makeConfig();
  sim.env_type = hg::ctp_adapter::CtEnvType::kSimulation;
  HG_CHECK(!sim.productionMode());  // 评测版 API：bIsProductionMode=false
  HG_CHECK(sim.redacted().find("env_type=SIMULATION") != std::string::npos);
}

}  // namespace

int main() {
  default_backend_matches_build_track();
  login_connect_failure_returns_not_connected();
  login_success_is_async_state_driven();
  disconnected_send_order_is_returned_not_dropped();
  connected_send_order_is_forwarded();
  futures_and_options_are_both_covered();
  invalid_order_is_rejected_without_retention();
  cancel_and_subscribe_require_login();
  fund_transfer_is_async_and_retained_when_disconnected();
  sink_only_serializes_and_enqueues();
  order_event_queue_overflow_is_counted_not_silent();
  quote_queue_drop_is_observable();
  credentials_are_never_echoed();
  config_env_type_maps_to_production_mode();
  return hg::ctp_adapter::testutil::report("ct_adapter_test");
}
