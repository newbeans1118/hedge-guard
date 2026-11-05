// 单测：ctp_adapter 报单状态查询 + 装配桥接（ARCHITECTURE.md §4.5.3 ⑤⑥；T-212）。
//
// 承重项（把「重放·在途对账」从「无生产实现 ⇒ 必然 kTimeout ⇒ 必然 BLOCKED」
// 推进为「向 CTP 查询并按其结果修正」）：
//   ① CTP `OrderStatus` → 归一化状态（`ctOrderStateFromStatus`）逐值正确；
//   ② `CtAdapter::queryOrder` 的「发起 → 有界等待回调 → 按 OrderRef/OrderSysID 选行」
//      把异步 SPI 桥接为同步结论（found/live/filled/cancelled/notfound/timeout/unavailable）；
//   ③ 装配桥接件 `CtOrderStateQueryBridge`（`IOrderStateQuery` 生产实现）把结论映射为
//      重放接缝语义（kFilled/kLive/kCancelled/kNotFound/kTimeout），与 §4.5.3 ⑤ 对齐。
//
// 两轨共用（注入 `FakeTradeGateway` 合成异步回调；stub 轨同样可跑）。
#include <string>

#include "ctp_adapter_test_util.h"
#include "fake_gateway.h"
#include "hg/ctp_adapter/ct_adapter.h"
#include "hg/flow_store/replay.h"
#include "order_state_query_bridge.h"

using hg::ctp_adapter::CtAdapter;
using hg::ctp_adapter::CtCode;
using hg::ctp_adapter::CtConfig;
using hg::ctp_adapter::CtOrderQueryOutcome;
using hg::ctp_adapter::CtOrderQueryReply;
using hg::ctp_adapter::CtOrderQueryReq;
using hg::ctp_adapter::CtOrderState;
using hg::ctp_adapter::kCtNotConnected;
using hg::ctp_adapter::kCtOk;
using hg::ctp_adapter::kCtUnsupportedOperation;
using hg::flow_store::InflightOrder;
using hg::flow_store::OrderQueryResult;

namespace {

// 走完「交易登录成功」的异步回调路径（回调侧仅置状态位，T2 语义）。
void bringOnline(CtAdapter* adapter) {
  adapter->onFrontConnected(false);
  adapter->onLoginReply(false, true, "20261104", 0, "");
}

InflightOrder inflight(const std::string& ref) {
  InflightOrder io;
  io.order_id = 1001;
  io.client_order_ref = ref;
  io.sent_ts_ms = 1767225600000LL;
  return io;
}

// ① CTP OrderStatus 逐值映射（保守：非终态均 kLive；未知 kUnknown）。
void order_status_mapping() {
  HG_CHECK(hg::ctp_adapter::ctOrderStateFromStatus('0') == CtOrderState::kFilled);
  HG_CHECK(hg::ctp_adapter::ctOrderStateFromStatus('5') == CtOrderState::kCancelled);
  HG_CHECK(hg::ctp_adapter::ctOrderStateFromStatus('1') == CtOrderState::kLive);
  HG_CHECK(hg::ctp_adapter::ctOrderStateFromStatus('2') == CtOrderState::kLive);
  HG_CHECK(hg::ctp_adapter::ctOrderStateFromStatus('3') == CtOrderState::kLive);
  HG_CHECK(hg::ctp_adapter::ctOrderStateFromStatus('4') == CtOrderState::kLive);
  HG_CHECK(hg::ctp_adapter::ctOrderStateFromStatus('a') == CtOrderState::kUnknown);
  HG_CHECK(hg::ctp_adapter::ctOrderStateFromStatus('\0') == CtOrderState::kUnknown);
}

// ② 适配层查询：全部成交 ⇒ kFound + kFilled（含订单编号与数量）。
void adapter_query_reports_filled() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);

  trade.query_status = '0';
  trade.query_order_sys_id = "S-9001";
  trade.query_total = 2;
  trade.query_traded = 2;

  CtOrderQueryReq req;
  req.client_order_ref = "REF-9001";
  const CtOrderQueryReply r = adapter.queryOrder(req, 50);

  HG_CHECK(r.outcome == CtOrderQueryOutcome::kFound);
  HG_CHECK(r.state == CtOrderState::kFilled);
  HG_CHECK_EQ(r.order_sys_id, std::string("S-9001"));
  HG_CHECK_EQ(r.volume_total, 2);
  HG_CHECK_EQ(r.volume_traded, 2);
  HG_CHECK_EQ(trade.queries, 1);
  HG_CHECK_EQ(trade.last_query.client_order_ref, std::string("REF-9001"));
}

// ② 仍在途 ⇒ kFound + kLive（冻结保留、继续跟踪）。
void adapter_query_reports_live() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_status = '3';

  CtOrderQueryReq req;
  req.client_order_ref = "REF-LIVE";
  const CtOrderQueryReply r = adapter.queryOrder(req, 50);
  HG_CHECK(r.outcome == CtOrderQueryOutcome::kFound);
  HG_CHECK(r.state == CtOrderState::kLive);
}

// ② CTP 无此报单（空结果集）⇒ kNotFound。
void adapter_query_reports_not_found() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_found = false;

  CtOrderQueryReq req;
  req.client_order_ref = "REF-MISS";
  const CtOrderQueryReply r = adapter.queryOrder(req, 50);
  HG_CHECK(r.outcome == CtOrderQueryOutcome::kNotFound);
}

// ② 有界等待超时（回调始终不到）⇒ kTimeout（保守）。
void adapter_query_times_out_without_reply() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_suppress = true;

  CtOrderQueryReq req;
  req.client_order_ref = "REF-SLOW";
  const CtOrderQueryReply r = adapter.queryOrder(req, 20);
  HG_CHECK(r.outcome == CtOrderQueryOutcome::kTimeout);
}

// ② 网关拒收（网络/队列失败）⇒ kUnavailable。
void adapter_query_unavailable_on_gateway_failure() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_rc = -1;  // 连接失败

  CtOrderQueryReq req;
  req.client_order_ref = "REF-NET";
  const CtOrderQueryReply r = adapter.queryOrder(req, 20);
  HG_CHECK(r.outcome == CtOrderQueryOutcome::kUnavailable);
}

// ② 未登录 ⇒ 不发起、直接 kUnavailable；requestOrderQuery 回 kCtNotConnected。
void adapter_query_requires_login() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  // 未调用 onLoginReply ⇒ 未登录。

  CtOrderQueryReq req;
  req.client_order_ref = "REF-NOLOGIN";
  int rid = 0;
  const CtCode code = adapter.requestOrderQuery(req, &rid);
  HG_CHECK(code == kCtNotConnected);
  HG_CHECK_EQ(trade.queries, 0);  // 未登录 ⇒ 不触达网关

  const CtOrderQueryReply r = adapter.queryOrder(req, 20);
  HG_CHECK(r.outcome == CtOrderQueryOutcome::kUnavailable);
}

// ② 空请求（既无 ref 又无 sys_id）⇒ 入参非法。
void adapter_query_rejects_empty_request() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);

  CtOrderQueryReq req;  // 全空
  int rid = 0;
  HG_CHECK(adapter.requestOrderQuery(req, &rid) == kCtUnsupportedOperation);
  const CtOrderQueryReply r = adapter.queryOrder(req, 20);
  HG_CHECK(r.outcome == CtOrderQueryOutcome::kUnavailable);
}

// ③ 桥接件（IOrderStateQuery 生产实现）：CTP 全部成交 ⇒ 重放接缝 kFilled。
void bridge_maps_filled() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_status = '0';
  trade.query_order_sys_id = "S-777";
  trade.query_total = 3;
  trade.query_traded = 3;

  hg::app::CtOrderStateQueryBridge bridge(&adapter, 50);
  std::string detail;
  const OrderQueryResult qr = bridge.query(inflight("REF-777"), &detail);
  HG_CHECK(qr == OrderQueryResult::kFilled);
  HG_CHECK(detail.find("FILLED") != std::string::npos);
  HG_CHECK(detail.find("S-777") != std::string::npos);
}

// ③ 桥接件：仍在途 ⇒ kLive；已撤 ⇒ kCancelled；无此单 ⇒ kNotFound。
void bridge_maps_live_cancelled_notfound() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  hg::app::CtOrderStateQueryBridge bridge(&adapter, 50);
  std::string detail;

  trade.query_status = '3';
  HG_CHECK(bridge.query(inflight("R1"), &detail) == OrderQueryResult::kLive);

  trade.query_status = '5';
  HG_CHECK(bridge.query(inflight("R2"), &detail) == OrderQueryResult::kCancelled);

  trade.query_found = false;
  HG_CHECK(bridge.query(inflight("R3"), &detail) == OrderQueryResult::kNotFound);
}

// ③ 桥接件：未登录 / 超时 ⇒ kTimeout（状态未知，保守冻结不释放）。
void bridge_is_conservative_on_unknown() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  hg::app::CtOrderStateQueryBridge bridge(&adapter, 20);
  std::string detail;

  // 未登录 ⇒ kUnavailable ⇒ kTimeout。
  HG_CHECK(bridge.query(inflight("R-NOLOGIN"), &detail) == OrderQueryResult::kTimeout);

  // 登录但回调不到 ⇒ 有界等待超时 ⇒ kTimeout。
  bringOnline(&adapter);
  trade.query_suppress = true;
  HG_CHECK(bridge.query(inflight("R-SLOW"), &detail) == OrderQueryResult::kTimeout);

  // 错误应答终态 ⇒ kUnavailable ⇒ kTimeout（**不得**被当作 kNotFound）。
  trade.query_suppress = false;
  trade.query_error = true;
  HG_CHECK(bridge.query(inflight("R-ERR"), &detail) == OrderQueryResult::kTimeout);
}

// ② 错误应答终态 ⇒ kUnavailable（**不得**误判为未找到而释放/补发 EXPIRED）。
void adapter_query_error_terminal_is_unavailable() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_error = true;

  CtOrderQueryReq req;
  req.client_order_ref = "REF-ERR";
  const CtOrderQueryReply r = adapter.queryOrder(req, 50);
  HG_CHECK(r.outcome == CtOrderQueryOutcome::kUnavailable);
  HG_CHECK_EQ(r.ctp_error_id, 15);
}

// ② 结果集含非目标行时按 client_order_ref 选行（不误取第一条）。
void adapter_query_selects_matching_row() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_status = '0';
  trade.query_extra_row = true;

  CtOrderQueryReq req;
  req.client_order_ref = "REF-TARGET";
  const CtOrderQueryReply r = adapter.queryOrder(req, 50);
  HG_CHECK(r.outcome == CtOrderQueryOutcome::kFound);
  HG_CHECK_EQ(r.client_order_ref, std::string("REF-TARGET"));
  HG_CHECK(r.state == CtOrderState::kFilled);
}

}  // namespace

int main() {
  order_status_mapping();
  adapter_query_reports_filled();
  adapter_query_reports_live();
  adapter_query_reports_not_found();
  adapter_query_times_out_without_reply();
  adapter_query_unavailable_on_gateway_failure();
  adapter_query_requires_login();
  adapter_query_rejects_empty_request();
  adapter_query_error_terminal_is_unavailable();
  adapter_query_selects_matching_row();
  bridge_maps_filled();
  bridge_maps_live_cancelled_notfound();
  bridge_is_conservative_on_unknown();
  return hg::ctp_adapter::testutil::report("ct_order_query_test");
}
