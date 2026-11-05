#ifndef HG_CTP_ADAPTER_FAKE_GATEWAY_H
#define HG_CTP_ADAPTER_FAKE_GATEWAY_H
// 单测假网关（两轨共用）：把「CTP 请求面」与「回调面」都变成可断言的内存假件，
// 使适配层逻辑（连接状态 / 0x2004 退回 / 期货与期权 / 队列）无需真实 CTP 前置即可验证。

#include <string>
#include <vector>

#include "hg/ctp_adapter/ct_gateway.h"

namespace hg {
namespace ctp_adapter {
namespace testutil {

class FakeTradeGateway : public CtTradeGateway {
 public:
  FakeTradeGateway()
      : connect_ok(true),
        rc(0),
        sink_(0),
        query_rc(0),
        query_suppress(false),
        query_found(true),
        query_error(false),
        query_extra_row(false),
        query_status('3'),
        query_total(2),
        query_traded(0),
        connect_calls(0),
        logins(0),
        logouts(0),
        inserts(0),
        actions(0),
        queries(0),
        transfers(0) {}

  virtual bool connect(const CtConfig& /*cfg*/, std::string* error) {
    ++connect_calls;
    if (!connect_ok) {
      if (error) *error = "fake: 交易前置连接被拒";
      return false;
    }
    return true;
  }
  virtual void disconnect() {}
  virtual int reqLogin(const CtConfig& /*cfg*/, int /*request_id*/) {
    ++logins;
    return rc;
  }
  virtual int reqLogout(int /*request_id*/) {
    ++logouts;
    return 0;
  }
  virtual int reqOrderInsert(const CtOrderReq& req, int /*request_id*/) {
    ++inserts;
    last_order = req;
    return rc;
  }
  virtual int reqOrderAction(const CtCancelReq& req, int /*request_id*/) {
    ++actions;
    last_cancel = req;
    return rc;
  }
  // 报单查询：把「异步 SPI 回调」在请求内同步合成（模拟 CTP 在 OnRspQryOrder 回填），
  // 使适配层「发起 + 有界等待 + 选行」可被单测断言（T-212）。
  virtual int reqQueryOrder(const CtOrderQueryReq& req, int request_id) {
    ++queries;
    last_query = req;
    if (query_rc != 0) return query_rc;
    if (sink_ == 0 || query_suppress) return 0;  // 静默：用于「有界等待超时」用例
    if (query_error) {  // 错误应答（流结束但状态未知 ⇒ 保守 kUnavailable）
      CtOrderQueryReply er;
      er.request_id = request_id;
      er.outcome = CtOrderQueryOutcome::kUnavailable;
      er.stream_end = true;
      er.ctp_error_id = 15;
      er.ctp_error_msg = "fake query error";
      er.client_order_ref = req.client_order_ref;
      sink_->onQueryOrderReply(er);
      return 0;
    }
    if (!query_found) {  // CTP 无此报单（空结果集，bIsLast 到达）
      CtOrderQueryReply end;
      end.request_id = request_id;
      end.outcome = CtOrderQueryOutcome::kNotFound;
      end.stream_end = true;
      end.client_order_ref = req.client_order_ref;
      end.order_sys_id = req.order_sys_id;
      sink_->onQueryOrderReply(end);
      return 0;
    }
    CtOrderQueryReply row;
    row.request_id = request_id;
    row.outcome = CtOrderQueryOutcome::kFound;
    row.state = ctOrderStateFromStatus(query_status);
    row.stream_end = true;  // 单行结果集（末行即流结束）
    row.client_order_ref = req.client_order_ref;
    row.order_sys_id = query_order_sys_id;
    row.instrument_id = req.instrument_id;
    row.volume_total = query_total;
    row.volume_traded = query_traded;
    row.volume_left = query_total - query_traded;
    if (query_extra_row) {  // 先投一条「非目标」行（stream_end=false），验证按 ref 选行
      CtOrderQueryReply other = row;
      other.client_order_ref = "OTHER-REF";
      other.stream_end = false;
      sink_->onQueryOrderReply(other);
    }
    sink_->onQueryOrderReply(row);
    return 0;
  }
  virtual int reqFundTransfer(const CtFundReq& req, int /*request_id*/) {
    ++transfers;
    last_fund = req;
    return rc;
  }
  virtual const char* apiVersion() const { return "fake-trade"; }
  virtual const char* backendName() const { return "fake"; }

  // 绑定回调出口（合成查询回复用；测试在构造 CtAdapter 后调用）。
  void set_sink(CtCallbackSink* sink) { sink_ = sink; }

  // 可调开关 + 观测计数
  bool connect_ok;
  int rc;  // CTP Req* 返回值语义：0 受理 / >0 队列满 / <0 连接失败
  CtCallbackSink* sink_;  // 查询回复回调出口（非拥有）
  int query_rc;          // 查询请求返回值（0 受理 / <0 失败）
  bool query_suppress;   // true ⇒ 不合成回复（驱动「有界等待超时」）
  bool query_found;      // false ⇒ 合成「未找到」终态
  bool query_error;      // true ⇒ 合成「错误应答」终态（kUnavailable）
  bool query_extra_row;  // true ⇒ 先投一条非目标行（验证选行）
  char query_status;     // 合成行的 CTP OrderStatus（默认 '3'=未成交在队列 ⇒ kLive）
  int query_total;       // 合成行原始数量
  int query_traded;      // 合成行已成交数量
  std::string query_order_sys_id;  // 合成行 OrderSysID
  int connect_calls;
  int logins;
  int logouts;
  int inserts;
  int actions;
  int queries;
  int transfers;
  CtOrderReq last_order;
  CtCancelReq last_cancel;
  CtOrderQueryReq last_query;
  CtFundReq last_fund;
};

class FakeMdGateway : public CtMdGateway {
 public:
  FakeMdGateway()
      : connect_ok(true), rc(0), connect_calls(0), logins(0), subscribes(0) {}

  virtual bool connect(const CtConfig& /*cfg*/, std::string* error) {
    ++connect_calls;
    if (!connect_ok) {
      if (error) *error = "fake: 行情前置连接被拒";
      return false;
    }
    return true;
  }
  virtual void disconnect() {}
  virtual int reqLogin(const CtConfig& /*cfg*/, int /*request_id*/) {
    ++logins;
    return rc;
  }
  virtual int subscribeMarket(const std::vector<std::string>& instruments,
                              int /*request_id*/) {
    ++subscribes;
    last_instruments = instruments;
    return rc;
  }
  virtual const char* apiVersion() const { return "fake-md"; }
  virtual const char* backendName() const { return "fake"; }

  bool connect_ok;
  int rc;
  int connect_calls;
  int logins;
  int subscribes;
  std::vector<std::string> last_instruments;
};

// 便捷构造：合法期货报单 / 合法期权报单 / 合法撤单 / 合法资金划转。
inline CtOrderReq makeOrder(const std::string& ref, const std::string& instrument,
                            CtMarket market) {
  CtOrderReq req;
  req.client_order_ref = ref;
  req.exchange_id = "SHFE";
  req.instrument_id = instrument;
  req.market = market;
  req.direction = CtDirection::kBuy;
  req.offset = CtOffset::kOpen;
  req.price_type = CtPriceType::kLimit;
  req.limit_price = 3500.0;
  req.volume = 2;
  return req;
}

inline CtCancelReq makeCancel(const std::string& sys_id) {
  CtCancelReq req;
  req.exchange_id = "SHFE";
  req.order_sys_id = sys_id;
  return req;
}

inline CtFundReq makeFund(CtFundDirection dir) {
  CtFundReq req;
  req.idempotency_key = "IDEM-0001";
  req.direction = dir;
  req.account_id = "ACC-001";
  req.bank_id = "BOC";
  req.bank_account = "6222";
  req.bank_password = "DUMMY_BANK_PASSWORD";
  req.currency_id = "CNY";
  req.amount = 100000.0;
  return req;
}

}  // namespace testutil
}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_ADAPTER_FAKE_GATEWAY_H
