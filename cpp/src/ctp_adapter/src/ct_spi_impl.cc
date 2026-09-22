// CTP 适配层 —— 真实 CTP SPI 实现（仅 HG_CTP_FOUND=ON 编译）。
// 每个回调**只**做：归一化（序列化）+ 投递队列（或置轻量状态位）。
#include "hg/ctp_adapter/ct_spi_impl.h"

#if HG_CTP_FOUND

#include "hg/ctp_adapter/ct_mapping.h"

namespace hg {
namespace ctp_adapter {

namespace {

// 资金回执归一化（应答与通知共用）。
CtOrderEvent fundReceipt(const CThostFtdcReqTransferField* p, int error_id,
                         const std::string& error_msg) {
  CtOrderEvent event;
  event.kind = CtOrderEventKind::kFundReceipt;
  event.report_ts_ms = nowMillis();
  event.ctp_error_id = error_id;
  event.ctp_error_msg = error_msg;
  if (p != 0) {
    event.fund_amount = p->TradeAmount;
    event.currency_id = mapping::field(p->CurrencyID);
    event.order_local_id = mapping::field(p->AccountID);  // 回执账户标识（便于对账）
  }
  return event;
}

}  // namespace

// -----------------------------------------------------------------------------
// CtSpiImpl
// -----------------------------------------------------------------------------
CtSpiImpl::CtSpiImpl(CtCallbackSink* sink) : sink_(sink) {}

void CtSpiImpl::OnFrontConnected() {
  if (sink_) sink_->onFrontConnected(false);
}

void CtSpiImpl::OnFrontDisconnected(int nReason) {
  if (sink_) sink_->onFrontDisconnected(false, nReason);
}

void CtSpiImpl::OnRspUserLogin(CThostFtdcRspUserLoginField* pRspUserLogin,
                               CThostFtdcRspInfoField* pRspInfo, int /*nRequestID*/,
                               bool /*bIsLast*/) {
  if (!sink_) return;
  const bool ok = (pRspInfo == 0) || (pRspInfo->ErrorID == 0);
  const std::string trading_day = (pRspUserLogin != 0)
                                      ? mapping::field(pRspUserLogin->TradingDay)
                                      : std::string();
  const int error_id = (pRspInfo != 0) ? pRspInfo->ErrorID : 0;
  const std::string error_msg =
      (pRspInfo != 0) ? mapping::field(pRspInfo->ErrorMsg) : std::string();
  sink_->onLoginReply(false, ok, trading_day, error_id, error_msg);
}

void CtSpiImpl::OnRspOrderInsert(CThostFtdcInputOrderField* pInputOrder,
                                 CThostFtdcRspInfoField* pRspInfo, int /*nRequestID*/,
                                 bool /*bIsLast*/) {
  if (!sink_) return;
  if (pRspInfo == 0 || pRspInfo->ErrorID == 0) return;  // 成功受理以 OnRtnOrder 为准
  CtOrderEvent base;
  base.kind = CtOrderEventKind::kOrderReturn;
  base.report_ts_ms = nowMillis();
  if (pInputOrder != 0) {
    base.client_order_ref = mapping::field(pInputOrder->OrderRef);
    base.instrument_id = mapping::field(pInputOrder->InstrumentID);
  }
  sink_->onOrderEvent(mapping::fromOrderError(base, *pRspInfo));
}

void CtSpiImpl::OnRspOrderAction(CThostFtdcInputOrderActionField* pInputOrderAction,
                                 CThostFtdcRspInfoField* pRspInfo, int /*nRequestID*/,
                                 bool /*bIsLast*/) {
  if (!sink_) return;
  if (pRspInfo == 0 || pRspInfo->ErrorID == 0) return;  // 成功后以回报/查询为准
  CtOrderEvent base;
  base.kind = CtOrderEventKind::kOrderReturn;
  base.report_ts_ms = nowMillis();
  if (pInputOrderAction != 0) {
    base.order_sys_id = mapping::field(pInputOrderAction->OrderSysID);
    base.instrument_id = mapping::field(pInputOrderAction->InstrumentID);
  }
  sink_->onOrderEvent(mapping::fromOrderError(base, *pRspInfo));
}

void CtSpiImpl::OnRtnOrder(CThostFtdcOrderField* pOrder) {
  if (!sink_ || pOrder == 0) return;
  sink_->onOrderEvent(mapping::fromOrder(*pOrder, nowMillis()));
}

void CtSpiImpl::OnRtnTrade(CThostFtdcTradeField* pTrade) {
  if (!sink_ || pTrade == 0) return;
  sink_->onOrderEvent(mapping::fromTrade(*pTrade, nowMillis()));
}

void CtSpiImpl::OnErrRtnOrderInsert(CThostFtdcInputOrderField* pInputOrder,
                                    CThostFtdcRspInfoField* pRspInfo) {
  if (!sink_ || pRspInfo == 0) return;
  CtOrderEvent base;
  base.kind = CtOrderEventKind::kOrderReturn;
  base.report_ts_ms = nowMillis();
  if (pInputOrder != 0) {
    base.client_order_ref = mapping::field(pInputOrder->OrderRef);
    base.instrument_id = mapping::field(pInputOrder->InstrumentID);
  }
  sink_->onOrderEvent(mapping::fromOrderError(base, *pRspInfo));
}

void CtSpiImpl::OnErrRtnOrderAction(CThostFtdcOrderActionField* pOrderAction,
                                    CThostFtdcRspInfoField* pRspInfo) {
  if (!sink_ || pRspInfo == 0) return;
  CtOrderEvent base;
  base.kind = CtOrderEventKind::kOrderReturn;
  base.report_ts_ms = nowMillis();
  if (pOrderAction != 0) {
    base.order_sys_id = mapping::field(pOrderAction->OrderSysID);
    base.instrument_id = mapping::field(pOrderAction->InstrumentID);
  }
  sink_->onOrderEvent(mapping::fromOrderError(base, *pRspInfo));
}

void CtSpiImpl::OnRspError(CThostFtdcRspInfoField* pRspInfo, int /*nRequestID*/,
                           bool /*bIsLast*/) {
  if (!sink_ || pRspInfo == 0) return;
  CtOrderEvent base;
  base.kind = CtOrderEventKind::kOrderError;
  base.report_ts_ms = nowMillis();
  sink_->onOrderEvent(mapping::fromOrderError(base, *pRspInfo));
}

void CtSpiImpl::OnRspFromBankToFutureByFuture(CThostFtdcReqTransferField* pReqTransfer,
                                              CThostFtdcRspInfoField* pRspInfo,
                                              int /*nRequestID*/, bool /*bIsLast*/) {
  if (!sink_) return;
  const int error_id = (pRspInfo != 0) ? pRspInfo->ErrorID : 0;
  const std::string error_msg =
      (pRspInfo != 0) ? mapping::field(pRspInfo->ErrorMsg) : std::string();
  sink_->onOrderEvent(fundReceipt(pReqTransfer, error_id, error_msg));
}

void CtSpiImpl::OnRspFromFutureToBankByFuture(CThostFtdcReqTransferField* pReqTransfer,
                                              CThostFtdcRspInfoField* pRspInfo,
                                              int /*nRequestID*/, bool /*bIsLast*/) {
  if (!sink_) return;
  const int error_id = (pRspInfo != 0) ? pRspInfo->ErrorID : 0;
  const std::string error_msg =
      (pRspInfo != 0) ? mapping::field(pRspInfo->ErrorMsg) : std::string();
  sink_->onOrderEvent(fundReceipt(pReqTransfer, error_id, error_msg));
}

// -----------------------------------------------------------------------------
// CtMdSpiImpl
// -----------------------------------------------------------------------------
CtMdSpiImpl::CtMdSpiImpl(CtCallbackSink* sink) : sink_(sink) {}

void CtMdSpiImpl::OnFrontConnected() {
  if (sink_) sink_->onFrontConnected(true);
}

void CtMdSpiImpl::OnFrontDisconnected(int nReason) {
  if (sink_) sink_->onFrontDisconnected(true, nReason);
}

void CtMdSpiImpl::OnRspUserLogin(CThostFtdcRspUserLoginField* pRspUserLogin,
                                 CThostFtdcRspInfoField* pRspInfo, int /*nRequestID*/,
                                 bool /*bIsLast*/) {
  if (!sink_) return;
  const bool ok = (pRspInfo == 0) || (pRspInfo->ErrorID == 0);
  const std::string trading_day = (pRspUserLogin != 0)
                                      ? mapping::field(pRspUserLogin->TradingDay)
                                      : std::string();
  const int error_id = (pRspInfo != 0) ? pRspInfo->ErrorID : 0;
  const std::string error_msg =
      (pRspInfo != 0) ? mapping::field(pRspInfo->ErrorMsg) : std::string();
  sink_->onLoginReply(true, ok, trading_day, error_id, error_msg);
}

void CtMdSpiImpl::OnRtnDepthMarketData(
    CThostFtdcDepthMarketDataField* pDepthMarketData) {
  if (!sink_ || pDepthMarketData == 0) return;
  sink_->onQuoteEvent(mapping::fromDepth(*pDepthMarketData, nowMillis()));
}

void CtMdSpiImpl::OnRspError(CThostFtdcRspInfoField* /*pRspInfo*/,
                             int /*nRequestID*/, bool /*bIsLast*/) {
  // 行情侧错误不入业务事件队列（行情可缺、不做决策）；保留为调试位。
}

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_FOUND
