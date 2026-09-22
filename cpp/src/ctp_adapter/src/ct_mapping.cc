// CTP 适配层 —— 字段映射实现（仅 HG_CTP_FOUND=ON 编译）。
#include "hg/ctp_adapter/ct_mapping.h"

#if HG_CTP_FOUND

#include <cstring>

namespace hg {
namespace ctp_adapter {
namespace mapping {

namespace {

// 银期转帐交易代码（CTP 约定值；如柜台另有约定须按柜台调整——单列披露）。
const char* const kTradeCodeBankToFuture = "202001";  // 银行 → 期货（入金）
const char* const kTradeCodeFutureToBank = "202002";  // 期货 → 银行（出金）

}  // namespace

void setField(char* dst, std::size_t n, const std::string& value) {
  if (n == 0) return;
  std::memset(dst, 0, n);
  const std::size_t copy = (value.size() < n - 1) ? value.size() : (n - 1);
  if (copy > 0) std::memcpy(dst, value.data(), copy);
}

void toReqUserLogin(const CtConfig& cfg, CThostFtdcReqUserLoginField* dst) {
  std::memset(dst, 0, sizeof(*dst));
  setField(dst->BrokerID, sizeof(dst->BrokerID), cfg.broker_id);
  setField(dst->UserID, sizeof(dst->UserID), cfg.user_id);
  setField(dst->Password, sizeof(dst->Password), cfg.password);
  setField(dst->UserProductInfo, sizeof(dst->UserProductInfo), cfg.app_id);
}

void toInputOrder(const CtConfig& cfg, const CtOrderReq& src,
                  CThostFtdcInputOrderField* dst) {
  // 期货与期权共用同一报单结构：类别由 InstrumentID 区分；方向 / 开平 / 价格条件一致映射。
  std::memset(dst, 0, sizeof(*dst));
  setField(dst->BrokerID, sizeof(dst->BrokerID), cfg.broker_id);
  setField(dst->InvestorID, sizeof(dst->InvestorID), cfg.user_id);
  setField(dst->UserID, sizeof(dst->UserID), cfg.user_id);
  setField(dst->InstrumentID, sizeof(dst->InstrumentID), src.instrument_id);
  setField(dst->ExchangeID, sizeof(dst->ExchangeID), src.exchange_id);
  setField(dst->OrderRef, sizeof(dst->OrderRef), src.client_order_ref);

  dst->OrderPriceType = (src.price_type == CtPriceType::kLimit) ? THOST_FTDC_OPT_LimitPrice
                                                                : THOST_FTDC_OPT_AnyPrice;
  dst->Direction = (src.direction == CtDirection::kBuy) ? THOST_FTDC_D_Buy
                                                        : THOST_FTDC_D_Sell;
  switch (src.offset) {
    case CtOffset::kOpen:
      dst->CombOffsetFlag[0] = THOST_FTDC_OF_Open;
      break;
    case CtOffset::kClose:
      dst->CombOffsetFlag[0] = THOST_FTDC_OF_Close;
      break;
    case CtOffset::kCloseToday:
      dst->CombOffsetFlag[0] = THOST_FTDC_OF_CloseToday;
      break;
  }
  dst->CombHedgeFlag[0] = THOST_FTDC_HF_Speculation;
  dst->LimitPrice = (src.price_type == CtPriceType::kLimit) ? src.limit_price : 0.0;
  dst->VolumeTotalOriginal = src.volume;
  dst->TimeCondition = THOST_FTDC_TC_GFD;
  dst->VolumeCondition = THOST_FTDC_VC_AV;
  dst->MinVolume = 1;
  dst->ContingentCondition = THOST_FTDC_CC_Immediately;
  dst->StopPrice = 0.0;
  dst->ForceCloseReason = THOST_FTDC_FCC_NotForceClose;
  dst->IsAutoSuspend = 0;
  dst->UserForceClose = 0;
  dst->IsSwapOrder = 0;
}

void toInputOrderAction(const CtCancelReq& src, CThostFtdcInputOrderActionField* dst) {
  std::memset(dst, 0, sizeof(*dst));
  setField(dst->ExchangeID, sizeof(dst->ExchangeID), src.exchange_id);
  setField(dst->OrderSysID, sizeof(dst->OrderSysID), src.order_sys_id);
  setField(dst->OrderRef, sizeof(dst->OrderRef), src.order_ref);
  setField(dst->InstrumentID, sizeof(dst->InstrumentID), src.instrument_id);
  dst->ActionFlag = THOST_FTDC_AF_Delete;  // 撤单
}

void toReqTransfer(const CtConfig& cfg, const CtFundReq& src,
                   CThostFtdcReqTransferField* dst) {
  std::memset(dst, 0, sizeof(*dst));
  const char* const code = (src.direction == CtFundDirection::kIn)
                               ? kTradeCodeBankToFuture
                               : kTradeCodeFutureToBank;
  setField(dst->TradeCode, sizeof(dst->TradeCode), code);
  setField(dst->BrokerID, sizeof(dst->BrokerID), cfg.broker_id);
  setField(dst->UserID, sizeof(dst->UserID), cfg.user_id);
  setField(dst->AccountID, sizeof(dst->AccountID), src.account_id);
  setField(dst->BankID, sizeof(dst->BankID), src.bank_id);
  setField(dst->BankAccount, sizeof(dst->BankAccount), src.bank_account);
  setField(dst->BankPassWord, sizeof(dst->BankPassWord), src.bank_password);
  setField(dst->Password, sizeof(dst->Password), cfg.password);
  setField(dst->CurrencyID, sizeof(dst->CurrencyID), src.currency_id);
  dst->TradeAmount = src.amount;
}

CtOrderEvent fromOrder(const CThostFtdcOrderField& src, std::int64_t now_ms) {
  CtOrderEvent event;
  event.kind = CtOrderEventKind::kOrderReturn;
  event.client_order_ref = field(src.OrderRef);
  event.order_local_id = field(src.OrderLocalID);
  event.order_sys_id = field(src.OrderSysID);
  event.instrument_id = field(src.InstrumentID);
  event.status = src.OrderStatus;
  event.volume_total = src.VolumeTotal;
  event.volume_traded = src.VolumeTraded;
  event.volume_left = src.VolumeTotal - src.VolumeTraded;
  event.report_ts_ms = now_ms;
  return event;
}

CtOrderEvent fromTrade(const CThostFtdcTradeField& src, std::int64_t now_ms) {
  CtOrderEvent event;
  event.kind = CtOrderEventKind::kTradeReturn;
  event.trade_id = field(src.TradeID);
  event.client_order_ref = field(src.OrderRef);
  event.order_local_id = field(src.OrderLocalID);
  event.order_sys_id = field(src.OrderSysID);
  event.instrument_id = field(src.InstrumentID);
  event.price = src.Price;
  event.trade_volume = src.Volume;
  event.direction = src.Direction;
  event.offset_flag = src.OffsetFlag;
  event.report_ts_ms = now_ms;
  return event;
}

CtOrderEvent fromOrderError(const CtOrderEvent& base, const CThostFtdcRspInfoField& info) {
  CtOrderEvent event = base;
  event.kind = CtOrderEventKind::kOrderError;
  event.ctp_error_id = info.ErrorID;
  event.ctp_error_msg = field(info.ErrorMsg);
  return event;
}

CtQuoteEvent fromDepth(const CThostFtdcDepthMarketDataField& src, std::int64_t now_ms) {
  CtQuoteEvent event;
  event.instrument_id = field(src.InstrumentID);
  event.exchange_id = field(src.ExchangeID);
  event.last_price = src.LastPrice;
  event.pre_settlement_price = src.PreSettlementPrice;
  event.bid_price1 = src.BidPrice1;
  event.ask_price1 = src.AskPrice1;
  event.bid_volume1 = src.BidVolume1;
  event.ask_volume1 = src.AskVolume1;
  event.update_time = field(src.UpdateTime);
  event.update_millisec = src.UpdateMillisec;
  event.local_recv_ts_ms = now_ms;
  return event;
}

}  // namespace mapping
}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_FOUND
