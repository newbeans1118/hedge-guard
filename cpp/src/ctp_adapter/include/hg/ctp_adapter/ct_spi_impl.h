#ifndef HG_CTP_ADAPTER_CT_SPI_IMPL_H
#define HG_CTP_ADAPTER_CT_SPI_IMPL_H
// =============================================================================
// CTP 适配层 —— 真实 CTP SPI 实现（**仅 HG_CTP_FOUND=ON 编译**）
//
// 承载：docs/ARCHITECTURE.md §3.1「内部结构：`CtSpiImpl : CThostFtdcUserSpi` /
//       `CtMdSpiImpl : CThostFtdcMdSpi` → 回调**只做一件事**：序列化并投递到
//       `orderEventQueue` / `quoteQueue`」；§2.4.2（T2 / T3 回调线程）。
//
// 实测（本项目对 CTP 合并版名称的勘误复测，见 ARCHITECTURE.md §3.1 `v1.5.1` 勘误）：
//   · 交易 SPI 基类 = `CThostFtdcUserSpi`（头文件 `<ThostFtdcUserApi.h>`）；
//   · 行情 SPI 基类 = `CThostFtdcMdSpi`（头文件 `<ThostFtdcMdApi.h>`）；
//   · `CThostFtdcTraderSpi` / `<ThostFtdcTraderApi.h>` 在合并版**不存在**。
//
// ⚠️ 回调实现纪律：每个回调**只**调用 `mapping::*` 归一化 + `sink_->onOrderEvent`
//    / `onQuoteEvent`（或置轻量状态位）。**不得**在回调内做落盘 / 网络 / 计算重活。
// =============================================================================

#include "hg/ctp_adapter/ct_gateway.h"

#ifndef HG_CTP_FOUND
#define HG_CTP_FOUND 0
#endif

#if HG_CTP_FOUND
#include <ThostFtdcMdApi.h>
#include <ThostFtdcUserApi.h>

namespace hg {
namespace ctp_adapter {

// 交易 SPI：委托回报 / 成交回报 / 报单错误 / 资金划转回执 → 归一化事件入队。
class CtSpiImpl : public CThostFtdcUserSpi {
 public:
  explicit CtSpiImpl(CtCallbackSink* sink);

  virtual void OnFrontConnected() override;
  virtual void OnFrontDisconnected(int nReason) override;
  virtual void OnRspUserLogin(CThostFtdcRspUserLoginField* pRspUserLogin,
                              CThostFtdcRspInfoField* pRspInfo, int nRequestID,
                              bool bIsLast) override;
  virtual void OnRspOrderInsert(CThostFtdcInputOrderField* pInputOrder,
                                CThostFtdcRspInfoField* pRspInfo, int nRequestID,
                                bool bIsLast) override;
  virtual void OnRspOrderAction(CThostFtdcInputOrderActionField* pInputOrderAction,
                                CThostFtdcRspInfoField* pRspInfo, int nRequestID,
                                bool bIsLast) override;
  virtual void OnRtnOrder(CThostFtdcOrderField* pOrder) override;
  virtual void OnRtnTrade(CThostFtdcTradeField* pTrade) override;
  virtual void OnErrRtnOrderInsert(CThostFtdcInputOrderField* pInputOrder,
                                   CThostFtdcRspInfoField* pRspInfo) override;
  virtual void OnErrRtnOrderAction(CThostFtdcOrderActionField* pOrderAction,
                                   CThostFtdcRspInfoField* pRspInfo) override;
  virtual void OnRspError(CThostFtdcRspInfoField* pRspInfo, int nRequestID,
                          bool bIsLast) override;
  virtual void OnRspFromBankToFutureByFuture(CThostFtdcReqTransferField* pReqTransfer,
                                             CThostFtdcRspInfoField* pRspInfo,
                                             int nRequestID, bool bIsLast) override;
  virtual void OnRspFromFutureToBankByFuture(CThostFtdcReqTransferField* pReqTransfer,
                                             CThostFtdcRspInfoField* pRspInfo,
                                             int nRequestID, bool bIsLast) override;

 private:
  CtCallbackSink* sink_;
};

// 行情 SPI：深度行情 → 归一化行情事件入队。
class CtMdSpiImpl : public CThostFtdcMdSpi {
 public:
  explicit CtMdSpiImpl(CtCallbackSink* sink);

  virtual void OnFrontConnected() override;
  virtual void OnFrontDisconnected(int nReason) override;
  virtual void OnRspUserLogin(CThostFtdcRspUserLoginField* pRspUserLogin,
                              CThostFtdcRspInfoField* pRspInfo, int nRequestID,
                              bool bIsLast) override;
  virtual void OnRtnDepthMarketData(
      CThostFtdcDepthMarketDataField* pDepthMarketData) override;
  virtual void OnRspError(CThostFtdcRspInfoField* pRspInfo, int nRequestID,
                          bool bIsLast) override;

 private:
  CtCallbackSink* sink_;
};

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_FOUND
#endif  // HG_CTP_ADAPTER_CT_SPI_IMPL_H
