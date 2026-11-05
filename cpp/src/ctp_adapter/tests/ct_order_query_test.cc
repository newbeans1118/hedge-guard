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
//
// T-212 补正（复核退回 D-1/D-2 + O-1/O-2）：
//   ④ **D-1**：`client_order_ref` 超 CTP `OrderRef` 12 字节上限 ⇒ **安全失败**
//      （kUnavailable，**不得** kNotFound）且**不发起**查询；12 字节内正常发起。
//   ⑤ **D-2**：桥接件总预算（NFR-REL-1 60 s）生效 —— 预算耗尽后剩余单不再发起查询。
//   ⑥ **O-1**：回调**到达即唤醒**（不得等满超时预算）；删 `query_cv_.notify_all()` 必红。
//   ⑦ **端到端**：重放收到 kTimeout ⇒ `unknown_count++` 且 `frozen_released == 0`
//      （16 字符生产形态 ref 下，绝不把可能仍在途的报单判为「未实际到达」而释放冻结）。
#include <chrono>
#include <cstdlib>
#include <string>
#include <thread>

#include "ctp_adapter_test_util.h"
#include "fake_gateway.h"
#include "hg/ctp_adapter/ct_adapter.h"
#include "hg/flow_store/flow_store.h"
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

// 生产形态的流水侧 ref：`BizNoGenerator.orderRef()` = "HG" + yyyyMMdd + 6 位 = 16 字符。
const char* kProdRef16 = "HG20261105000001";
// 其被 `setField` 静默截断（12 字节）后的 CTP 侧形态（仅用于说明，**不得**用于比较）。
const char* kCtpTruncatedRef12 = "HG2026110500";

std::int64_t elapsedMs(const std::chrono::steady_clock::time_point& since) {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now() - since)
      .count();
}

// 延迟应答网关（O-1 专用）：`reqQueryOrder` 立即返回，另起线程在 `delay_ms` 后投递一行。
//   · 与 `FakeTradeGateway` 的**同步**合成不同，本件让「等待中到达」真实发生 ⇒
//     `query_cv_.notify_all()` 缺失时 queryOrder 只能退化到 deadline 兜底（吃掉整个超时）。
class DelayedTradeGateway : public hg::ctp_adapter::testutil::FakeTradeGateway {
 public:
  DelayedTradeGateway() : delay_ms(300), worker_() {}
  virtual ~DelayedTradeGateway() { join(); }

  virtual int reqQueryOrder(const CtOrderQueryReq& req, int request_id) {
    ++queries;
    last_query = req;
    if (query_rc != 0) return query_rc;
    join();  // 上一笔的投递线程必须收口（串行假设）
    worker_ = std::thread(&DelayedTradeGateway::deliver, this, req, request_id);
    return 0;
  }

  void join() {
    if (worker_.joinable()) worker_.join();
  }

  int delay_ms;

 private:
  void deliver(CtOrderQueryReq req, int request_id) {
    std::this_thread::sleep_for(std::chrono::milliseconds(delay_ms));
    if (sink_ == 0) return;
    CtOrderQueryReply row;
    row.request_id = request_id;
    row.outcome = CtOrderQueryOutcome::kFound;
    row.state = CtOrderState::kLive;
    row.stream_end = true;
    row.client_order_ref = req.client_order_ref;
    row.order_sys_id = "S-ASYNC";
    sink_->onQueryOrderReply(row);
  }

  std::thread worker_;
};

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

// ④ D-1：ref 超 CTP `OrderRef` 12 字节上限 ⇒ **安全失败**（kUnavailable、不发起查询）。
//   根因：流水侧 ref = 16 字符（`HG<yyyyMMdd><seq6>`），CTP `OrderRef` = `char[13]`（12 可用）
//   ⇒ `setField` 静默截断；按整串比较必不命中 ⇒ 误判 kNotFound ⇒ 释放冻结（危险方向）。
//   本用例同时钉住「**不得**按截断口径比较」的反向约束（12 字符值不得被当作同一笔）。
void adapter_refuses_ref_over_ctp_capacity() {
  // 前置事实：生产形态 16 字符 > 12 字节上限（常量来源 `TThostFtdcOrderRefType[13]`）。
  HG_CHECK_EQ(std::string(kProdRef16).size(), static_cast<std::size_t>(16));
  HG_CHECK_EQ(std::string(kCtpTruncatedRef12).size(), static_cast<std::size_t>(12));
  HG_CHECK_EQ(hg::ctp_adapter::kCtOrderRefCapacity, static_cast<std::size_t>(12));

  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_status = '0';
  trade.query_order_sys_id = "S-16";

  CtOrderQueryReq req;
  req.client_order_ref = kProdRef16;  // 16 字符（生产形态）
  const CtOrderQueryReply r = adapter.queryOrder(req, 50);

  // 核心：**不是** kNotFound（否则重放会释放冻结 + 补发 EVT_ORDER_EXPIRED）。
  HG_CHECK(r.outcome == CtOrderQueryOutcome::kUnavailable);
  HG_CHECK(r.outcome != CtOrderQueryOutcome::kNotFound);
  HG_CHECK(r.ctp_error_msg.find("12 bytes") != std::string::npos);
  HG_CHECK(r.ctp_error_msg.find("T-212 D-1") != std::string::npos);
  // **未发起**（安全失败的物理证据；变异去掉长度校验 ⇒ 此处必红）。
  HG_CHECK_EQ(trade.queries, 0);

  // 异步发起面同样拒绝（避免调用方绕过 queryOrder 的前置校验）。
  int rid = 0;
  HG_CHECK(adapter.requestOrderQuery(req, &rid) == kCtUnsupportedOperation);
  HG_CHECK_EQ(trade.queries, 0);

  // 负例边界：12 字符（= CTP 承载上限）**正常发起**并命中。
  CtOrderQueryReq ok;
  ok.client_order_ref = kCtpTruncatedRef12;
  const CtOrderQueryReply r2 = adapter.queryOrder(ok, 50);
  HG_CHECK(r2.outcome == CtOrderQueryOutcome::kFound);
  HG_CHECK_EQ(trade.queries, 1);
  HG_CHECK_EQ(trade.last_query.client_order_ref, std::string(kCtpTruncatedRef12));
}

// ④ D-1（桥接层）：16 字符 ref ⇒ `OrderQueryResult::kTimeout`（保守冻结，**释放 0 笔**）。
void bridge_overlong_ref_is_conservative_timeout() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_found = false;  // 即便 CTP 侧「看起来没有此单」，也不得判 kNotFound

  hg::app::CtOrderStateQueryBridge bridge(&adapter, 50);
  std::string detail;
  const OrderQueryResult qr = bridge.query(inflight(kProdRef16), &detail);
  HG_CHECK(qr == OrderQueryResult::kTimeout);
  HG_CHECK(qr != OrderQueryResult::kNotFound);
  HG_CHECK(detail.find("UNAVAILABLE") != std::string::npos);
  HG_CHECK(detail.find("OrderRef 12 bytes") != std::string::npos);  // 拒因随 detail 留痕
  HG_CHECK_EQ(trade.queries, 0);  // 未发起
}

// ⑥ O-1：回复在**等待中**到达必须唤醒（而非等满 timeout）。
//   变异（删 `CtAdapter::onQueryOrderReply` 里的 `query_cv_.notify_all()`）⇒ 本用例必红：
//   outcome 退化为 kTimeout、且耗时 ≈ timeout（5000ms）。
void adapter_wakes_up_on_reply_arrival() {
  DelayedTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.delay_ms = 300;  // 回复在 300ms 后到达（远小于 5000ms 超时）

  CtOrderQueryReq req;
  req.client_order_ref = "REF-ASYNC";
  const std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
  const CtOrderQueryReply r = adapter.queryOrder(req, 5000);
  const std::int64_t elapsed = elapsedMs(start);

  HG_CHECK(r.outcome == CtOrderQueryOutcome::kFound);
  HG_CHECK(r.state == CtOrderState::kLive);
  HG_CHECK(elapsed >= 250);   // 确实等到了延迟回复（不是提前返回）
  HG_CHECK(elapsed < 2000);   // 未唤醒 ⇒ 退化为 deadline 兜底 ≈ 5000ms ⇒ 必红
}

// ⑤ D-2：总预算（NFR-REL-1 = 60 s 口径）生效 —— 每单 timeout = min(单笔, 剩余预算)，
//   预算耗尽后剩余单**不再发起查询**（直接保守 kTimeout）。
//   变异（去掉总预算，只用单笔上限）⇒ 4 笔 × 150ms = 600ms 且 queries == 4 ⇒ 双红。
void bridge_enforces_total_query_budget() {
  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_suppress = true;  // CTP 永不应答 ⇒ 每笔吃满自己的额度

  // 单笔上限 150ms / 总预算 200ms（真实常量是 60000ms，此处压缩仅为测试时长）。
  hg::app::CtOrderStateQueryBridge bridge(&adapter, 150, 200);
  HG_CHECK_EQ(bridge.totalBudgetMs(), static_cast<std::int64_t>(200));
  HG_CHECK_EQ(bridge.perOrderTimeoutMs(), static_cast<std::int64_t>(150));

  const std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
  std::string detail;
  const OrderQueryResult q1 = bridge.query(inflight("R-BUDGET-1"), &detail);
  const OrderQueryResult q2 = bridge.query(inflight("R-BUDGET-2"), &detail);
  const OrderQueryResult q3 = bridge.query(inflight("R-BUDGET-3"), &detail);
  const OrderQueryResult q4 = bridge.query(inflight("R-BUDGET-4"), &detail);
  const std::int64_t elapsed = elapsedMs(start);

  HG_CHECK(q1 == OrderQueryResult::kTimeout);
  HG_CHECK(q2 == OrderQueryResult::kTimeout);
  HG_CHECK(q3 == OrderQueryResult::kTimeout);
  HG_CHECK(q4 == OrderQueryResult::kTimeout);
  HG_CHECK(detail.find("budget") != std::string::npos);  // 预算耗尽的显式披露
  HG_CHECK_EQ(bridge.remainingBudgetMs(), static_cast<std::int64_t>(0));
  // 总预算有牙：无总预算 ⇒ 4 × 150 = 600ms；有预算 ⇒ ≈ 200ms（留足调度余量）。
  HG_CHECK(elapsed < 450);
  // 预算耗尽后**不再发起**查询（网关计数 ≤ 2：第一笔 150ms + 第二笔剩余 ≈50ms）。
  HG_CHECK(trade.queries <= 2);
  HG_CHECK(trade.queries >= 1);
}

// ⑦ 端到端：16 字符生产形态 ref ⇒ 重放得到 kTimeout ⇒ unknown_count++ 且 frozen_released==0。
//   （修复前行为：按整串比较必不命中 ⇒ kNotFound ⇒ frozen_released==1 ⇒ 重复下单风险。）
void replay_overlong_ref_is_unknown_not_released() {
  const std::string d = "/tmp/t212-e2e-flow";
  const std::string cmd = std::string("rm -rf '") + d + "'; mkdir -p '" + d + "'";
  HG_CHECK(std::system(cmd.c_str()) == 0);

  {
    hg::flow_store::FlowStore fs;
    HG_CHECK(fs.open(hg::flow_store::flowShardPath(d, 20261105)));
    hg::flow_store::FlowRecord placed;
    placed.kind = hg::flow_store::FlowKind::kPlaced;
    placed.order_id = 4242;
    placed.client_order_ref = kProdRef16;
    placed.ts_ms = 1767225600000LL;
    placed.detail = "accepted pending confirm";
    HG_CHECK(fs.append(placed));
    hg::flow_store::FlowRecord sent;
    sent.kind = hg::flow_store::FlowKind::kSent;
    sent.order_id = 4242;
    sent.client_order_ref = kProdRef16;
    sent.ts_ms = 1767225600001LL;
    sent.detail = "submitted to CTP";
    HG_CHECK(fs.append(sent));
    fs.close();
  }

  hg::ctp_adapter::testutil::FakeTradeGateway trade;
  hg::ctp_adapter::testutil::FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  trade.set_sink(&adapter);
  bringOnline(&adapter);
  trade.query_found = false;  // 即便空结果集也不得判「未实际到达」

  hg::app::CtOrderStateQueryBridge bridge(&adapter, 50);
  hg::flow_store::ReplayController rc;
  hg::flow_store::FlowRuntimeConfig cfg;
  cfg.flow_dir = d;
  rc.set_runtime_config(cfg);
  rc.set_order_query(&bridge);

  const hg::flow_store::ReplayReport rep = rc.run(1767225601000LL, 0, 0);
  HG_CHECK(rep.verdict == hg::flow_store::ReplayVerdict::kOk);
  HG_CHECK_EQ(rep.unknown_count, static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.frozen_released, static_cast<std::size_t>(0));  // 核心：不误释冻结
  HG_CHECK_EQ(rep.frozen_held, static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.decisions.size(), static_cast<std::size_t>(1));
  if (!rep.decisions.empty()) {
    HG_CHECK(rep.decisions[0].ctp_result == OrderQueryResult::kTimeout);
    HG_CHECK(rep.decisions[0].kind == hg::flow_store::ReplayDecision::kUnknownKeepFrozen);
  }
  HG_CHECK_EQ(trade.queries, 0);  // 安全失败：未触达 CTP 查询面
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
  // T-212 补正（复核退回 D-1 / D-2 + O-1）
  adapter_refuses_ref_over_ctp_capacity();
  bridge_overlong_ref_is_conservative_timeout();
  adapter_wakes_up_on_reply_arrival();
  bridge_enforces_total_query_budget();
  replay_overlong_ref_is_unknown_not_released();
  return hg::ctp_adapter::testutil::report("ct_order_query_test");
}
