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
        connect_calls(0),
        logins(0),
        logouts(0),
        inserts(0),
        actions(0),
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
  virtual int reqFundTransfer(const CtFundReq& req, int /*request_id*/) {
    ++transfers;
    last_fund = req;
    return rc;
  }
  virtual const char* apiVersion() const { return "fake-trade"; }
  virtual const char* backendName() const { return "fake"; }

  // 可调开关 + 观测计数
  bool connect_ok;
  int rc;  // CTP Req* 返回值语义：0 受理 / >0 队列满 / <0 连接失败
  int connect_calls;
  int logins;
  int logouts;
  int inserts;
  int actions;
  int transfers;
  CtOrderReq last_order;
  CtCancelReq last_cancel;
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
