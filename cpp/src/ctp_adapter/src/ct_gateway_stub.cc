// CTP 适配层 —— 无 SDK 降级轨后端（HG_CTP_FOUND=OFF）。
//
// 承载：docs/ARCHITECTURE.md §3.1 依赖行（CTP SDK 外部提供，`CTP_FOUND` 宏控制双轨
//       构建）；`cpp/cmake/HgDependencies.cmake` 双轨口径。
//
// 降级语义（**不得伪造 SDK**）：连接恒失败 ⇒ `CtAdapter::login` 返回 `0x2004`，
// `sendOrder` 亦回 `0x2004` 并退回保留；组件仍可完整构建与测试（无 SDK 环境 / CI）。
#include "hg/ctp_adapter/ct_gateway.h"

#if !HG_CTP_FOUND

namespace hg {
namespace ctp_adapter {
namespace {

const char* const kStubVersion = "stub";

class StubTradeGateway : public CtTradeGateway {
 public:
  explicit StubTradeGateway(CtCallbackSink* /*sink*/) {}
  virtual ~StubTradeGateway() {}

  virtual bool connect(const CtConfig& /*cfg*/, std::string* error) {
    if (error) *error = "CTP SDK 不可用（CTP_FOUND=OFF，stub 降级轨）：交易前置未建立";
    return false;
  }
  virtual void disconnect() {}
  virtual int reqLogin(const CtConfig& /*cfg*/, int /*request_id*/) { return -1; }
  virtual int reqLogout(int /*request_id*/) { return -1; }
  virtual int reqOrderInsert(const CtOrderReq& /*req*/, int /*request_id*/) { return -1; }
  virtual int reqOrderAction(const CtCancelReq& /*req*/, int /*request_id*/) { return -1; }
  virtual int reqFundTransfer(const CtFundReq& /*req*/, int /*request_id*/) { return -1; }
  virtual const char* apiVersion() const { return kStubVersion; }
  virtual const char* backendName() const { return kStubVersion; }
};

class StubMdGateway : public CtMdGateway {
 public:
  explicit StubMdGateway(CtCallbackSink* /*sink*/) {}
  virtual ~StubMdGateway() {}

  virtual bool connect(const CtConfig& /*cfg*/, std::string* error) {
    if (error) *error = "CTP SDK 不可用（CTP_FOUND=OFF，stub 降级轨）：行情前置未建立";
    return false;
  }
  virtual void disconnect() {}
  virtual int reqLogin(const CtConfig& /*cfg*/, int /*request_id*/) { return -1; }
  virtual int subscribeMarket(const std::vector<std::string>& /*instruments*/,
                              int /*request_id*/) {
    return -1;
  }
  virtual const char* apiVersion() const { return kStubVersion; }
  virtual const char* backendName() const { return kStubVersion; }
};

}  // namespace

CtTradeGateway* createTradeGateway(CtCallbackSink* sink) {
  return new StubTradeGateway(sink);
}

CtMdGateway* createMdGateway(CtCallbackSink* sink) { return new StubMdGateway(sink); }

}  // namespace ctp_adapter
}  // namespace hg

#endif  // !HG_CTP_FOUND
