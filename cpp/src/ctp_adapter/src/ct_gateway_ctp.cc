// CTP 适配层 —— 真实 CTP SDK 网关（合并版 UserApi / MdApi，仅 HG_CTP_FOUND=ON 编译）。
//
// 承载：docs/ARCHITECTURE.md §3.1（封装 CTP 合并版 `ThostFtdc UserApi`：交易 + 行情 +
//       资金划转）；§7.2.1（依赖清单 = CTP SDK + protobuf）。
//
// 实测（2026-09-22，`/home/ctpmini/ctpmini_admin/ctp_sdk`）：
//   · `ThostFtdcUserApi.h`  → `CThostFtdcUserApi::CreateFtdcUserApi` / `CThostFtdcUserSpi`
//   · `ThostFtdcMdApi.h`    → `CThostFtdcMdApi::CreateFtdcMdApi`   / `CThostFtdcMdSpi`
//   · 资金划转走交易 UserApi：`ReqFromBankToFutureByFuture`（入金）/
//     `ReqFromFutureToBankByFuture`（出金）（FR-FUND-5/6；R-FUND-11「复用 CTP 通道」）。
#include "hg/ctp_adapter/ct_gateway.h"

#if HG_CTP_FOUND

#include <cstring>
#include <vector>

#include <ThostFtdcMdApi.h>
#include <ThostFtdcUserApi.h>

#include "hg/ctp_adapter/ct_mapping.h"
#include "hg/ctp_adapter/ct_spi_impl.h"

namespace hg {
namespace ctp_adapter {
namespace {

// char* 入参（CTP `RegisterFront` / `SubscribeMarketData`）需可写缓冲区，
// 由 std::vector<char> 持有并在调用期间有效（避免 const_cast 造成的 UB）。
std::vector<char> toMutable(const std::string& s) {
  std::vector<char> buf(s.begin(), s.end());
  buf.push_back('\0');
  return buf;
}

class CtpTradeGateway : public CtTradeGateway {
 public:
  explicit CtpTradeGateway(CtCallbackSink* sink) : spi_(sink), api_(0) {}
  virtual ~CtpTradeGateway() { disconnect(); }

  virtual bool connect(const CtConfig& cfg, std::string* error) {
    cfg_ = cfg;
    if (api_ != 0) return true;
    api_ = CThostFtdcUserApi::CreateFtdcUserApi(cfg_.flow_path.c_str());
    if (api_ == 0) {
      if (error) *error = "CThostFtdcUserApi::CreateFtdcUserApi 返回空";
      return false;
    }
    api_->RegisterSpi(&spi_);
    std::vector<char> front = toMutable(cfg_.trade_front);
    api_->RegisterFront(&front[0]);
    api_->SubscribePrivateTopic(THOST_TERT_QUICK);
    api_->SubscribePublicTopic(THOST_TERT_QUICK);
    api_->Init();
    return true;
  }

  virtual void disconnect() {
    if (api_ != 0) {
      api_->Release();
      api_ = 0;
    }
  }

  virtual int reqLogin(const CtConfig& cfg, int request_id) {
    if (api_ == 0) return -1;
    cfg_ = cfg;
    // 客户端认证（若配置了 AppID / 认证码）——ReqAuthenticate 须先于 ReqUserLogin。
    if (!cfg_.app_id.empty() || !cfg_.auth_code.empty()) {
      CThostFtdcReqAuthenticateField auth;
      std::memset(&auth, 0, sizeof(auth));
      mapping::setField(auth.BrokerID, sizeof(auth.BrokerID), cfg_.broker_id);
      mapping::setField(auth.UserID, sizeof(auth.UserID), cfg_.user_id);
      mapping::setField(auth.AppID, sizeof(auth.AppID), cfg_.app_id);
      mapping::setField(auth.AuthCode, sizeof(auth.AuthCode), cfg_.auth_code);
      (void)api_->ReqAuthenticate(&auth, request_id);
    }
    CThostFtdcReqUserLoginField login;
    mapping::toReqUserLogin(cfg_, &login);
    return api_->ReqUserLogin(&login, request_id);
  }

  virtual int reqLogout(int request_id) {
    if (api_ == 0) return -1;
    CThostFtdcUserLogoutField logout;
    std::memset(&logout, 0, sizeof(logout));
    mapping::setField(logout.BrokerID, sizeof(logout.BrokerID), cfg_.broker_id);
    mapping::setField(logout.UserID, sizeof(logout.UserID), cfg_.user_id);
    return api_->ReqUserLogout(&logout, request_id);
  }

  virtual int reqOrderInsert(const CtOrderReq& req, int request_id) {
    if (api_ == 0) return -1;
    CThostFtdcInputOrderField order;
    mapping::toInputOrder(cfg_, req, &order);
    order.RequestID = request_id;
    return api_->ReqOrderInsert(&order, request_id);
  }

  virtual int reqOrderAction(const CtCancelReq& req, int request_id) {
    if (api_ == 0) return -1;
    CThostFtdcInputOrderActionField action;
    mapping::toInputOrderAction(req, &action);
    action.RequestID = request_id;
    return api_->ReqOrderAction(&action, request_id);
  }

  virtual int reqFundTransfer(const CtFundReq& req, int request_id) {
    if (api_ == 0) return -1;
    CThostFtdcReqTransferField transfer;
    mapping::toReqTransfer(cfg_, req, &transfer);
    transfer.RequestID = request_id;
    if (req.direction == CtFundDirection::kIn) {
      return api_->ReqFromBankToFutureByFuture(&transfer, request_id);
    }
    return api_->ReqFromFutureToBankByFuture(&transfer, request_id);
  }

  virtual const char* apiVersion() const { return CThostFtdcUserApi::GetApiVersion(); }
  virtual const char* backendName() const { return "ctp-sdk"; }

 private:
  CtSpiImpl spi_;  // 派生自 CThostFtdcUserSpi；基类无虚析构 ⇒ 不得经基类指针释放。
  CThostFtdcUserApi* api_;
  CtConfig cfg_;
};

class CtpMdGateway : public CtMdGateway {
 public:
  explicit CtpMdGateway(CtCallbackSink* sink) : spi_(sink), api_(0) {}
  virtual ~CtpMdGateway() { disconnect(); }

  virtual bool connect(const CtConfig& cfg, std::string* error) {
    cfg_ = cfg;
    if (api_ != 0) return true;
    api_ = CThostFtdcMdApi::CreateFtdcMdApi(cfg_.flow_path.c_str(), false, false);
    if (api_ == 0) {
      if (error) *error = "CThostFtdcMdApi::CreateFtdcMdApi 返回空";
      return false;
    }
    api_->RegisterSpi(&spi_);
    std::vector<char> front = toMutable(cfg_.md_front);
    api_->RegisterFront(&front[0]);
    api_->Init();
    return true;
  }

  virtual void disconnect() {
    if (api_ != 0) {
      api_->Release();
      api_ = 0;
    }
  }

  virtual int reqLogin(const CtConfig& cfg, int request_id) {
    if (api_ == 0) return -1;
    cfg_ = cfg;
    CThostFtdcReqUserLoginField login;
    mapping::toReqUserLogin(cfg_, &login);
    return api_->ReqUserLogin(&login, request_id);
  }

  virtual int subscribeMarket(const std::vector<std::string>& instruments,
                              int /*request_id*/) {
    if (api_ == 0) return -1;
    if (instruments.empty()) return 0;
    std::vector<std::vector<char> > buffers;
    buffers.reserve(instruments.size());
    for (std::size_t i = 0; i < instruments.size(); ++i) {
      buffers.push_back(toMutable(instruments[i]));
    }
    std::vector<char*> pointers;
    pointers.reserve(buffers.size());
    for (std::size_t i = 0; i < buffers.size(); ++i) {
      pointers.push_back(&buffers[i][0]);
    }
    return api_->SubscribeMarketData(&pointers[0], static_cast<int>(pointers.size()));
  }

  virtual const char* apiVersion() const { return CThostFtdcMdApi::GetApiVersion(); }
  virtual const char* backendName() const { return "ctp-md"; }

 private:
  CtMdSpiImpl spi_;
  CThostFtdcMdApi* api_;
  CtConfig cfg_;
};

}  // namespace

CtTradeGateway* createTradeGateway(CtCallbackSink* sink) {
  return new CtpTradeGateway(sink);
}

CtMdGateway* createMdGateway(CtCallbackSink* sink) { return new CtpMdGateway(sink); }

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_FOUND
