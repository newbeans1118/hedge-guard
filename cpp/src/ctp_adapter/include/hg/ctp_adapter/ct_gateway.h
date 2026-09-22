#ifndef HG_CTP_ADAPTER_CT_GATEWAY_H
#define HG_CTP_ADAPTER_CT_GATEWAY_H
// =============================================================================
// CTP 适配层（ctp_adapter）—— 网关抽象（M1）
//
// 承载：docs/ARCHITECTURE.md §3.1（内部结构：SPI 实现 → 事件队列）、§2.4.2（T2/T3）、
//       docs/REQUIREMENTS/NONFUNCTIONAL.md NFR-COMPAT-6（CTP 异步 SPI 回调模型）。
//
// 设计意图（可测试性 / 最小 I/O 依赖）：
//   · `CtCallbackSink`  —— SDK 回调的**唯一出口**；适配层用它把「CTP SDK 回调」与
//      「适配逻辑」解耦，使 SDK 轨与 stub 轨共用同一适配逻辑，并可在单测中注入假件。
//   · `CtTradeGateway` / `CtMdGateway` —— 对 CTP 的**唯一调用面**；真实实现封装
//      合并版 `CThostFtdcUserApi` / `CThostFtdcMdApi`（仅 `HG_CTP_FOUND` 时编译），
//      降级轨提供 stub（连接恒失败）。核心适配逻辑不直接依赖任何 SDK 类型。
// =============================================================================

#include <string>
#include <vector>

#include "hg/ctp_adapter/ct_types.h"

namespace hg {
namespace ctp_adapter {

// SPI 回调出口（CtAdapter 实现）。约定：实现**只做两件事**——
// 序列化为归一化事件 + 投递队列（以及置轻量连接/登录状态位）；**不得**做重活、阻塞 I/O。
class CtCallbackSink {
 public:
  virtual ~CtCallbackSink() {}
  virtual void onFrontConnected(bool market) = 0;
  virtual void onFrontDisconnected(bool market, int reason) = 0;
  virtual void onLoginReply(bool market, bool ok, const std::string& trading_day,
                            int error_id, const std::string& error_msg) = 0;
  virtual void onOrderEvent(const CtOrderEvent& event) = 0;
  virtual void onQuoteEvent(const CtQuoteEvent& event) = 0;
};

// 交易网关：封装合并版 `CThostFtdcUserApi`。
// 请求返回值语义与 CTP `Req*` 一致：0 = 已受理；>0 = 未处理请求队列满；<0 = 网络连接失败。
class CtTradeGateway {
 public:
  virtual ~CtTradeGateway() {}
  virtual bool connect(const CtConfig& cfg, std::string* error) = 0;
  virtual void disconnect() = 0;
  virtual int reqLogin(const CtConfig& cfg, int request_id) = 0;
  virtual int reqLogout(int request_id) = 0;
  virtual int reqOrderInsert(const CtOrderReq& req, int request_id) = 0;
  virtual int reqOrderAction(const CtCancelReq& req, int request_id) = 0;
  virtual int reqFundTransfer(const CtFundReq& req, int request_id) = 0;
  virtual const char* apiVersion() const = 0;
  virtual const char* backendName() const = 0;
};

// 行情网关：封装合并版行情接口 `CThostFtdcMdApi`。
class CtMdGateway {
 public:
  virtual ~CtMdGateway() {}
  virtual bool connect(const CtConfig& cfg, std::string* error) = 0;
  virtual void disconnect() = 0;
  virtual int reqLogin(const CtConfig& cfg, int request_id) = 0;
  virtual int subscribeMarket(const std::vector<std::string>& instruments,
                              int request_id) = 0;
  virtual const char* apiVersion() const = 0;
  virtual const char* backendName() const = 0;
};

// 工厂：按 `HG_CTP_FOUND` 返回真实 SDK 网关或 stub 网关（返回对象所有权归调用方）。
CtTradeGateway* createTradeGateway(CtCallbackSink* sink);
CtMdGateway* createMdGateway(CtCallbackSink* sink);

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_ADAPTER_CT_GATEWAY_H
