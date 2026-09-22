// 单测：ctp_adapter 字段映射（仅 CTP SDK 轨）。
//   覆盖：期货 / 期权报单共用 `CThostFtdcInputOrderField`（类别由 InstrumentID 区分）；
//         撤单、资金划转（入金 / 出金）、登录、以及 CTP → 归一化事件。
#include "ctp_adapter_test_util.h"
#include "fake_gateway.h"
#include "hg/ctp_adapter/ct_mapping.h"

#include <cstring>
#include <string>

using hg::ctp_adapter::CtCancelReq;
using hg::ctp_adapter::CtConfig;
using hg::ctp_adapter::CtDirection;
using hg::ctp_adapter::CtFundDirection;
using hg::ctp_adapter::CtFundReq;
using hg::ctp_adapter::CtMarket;
using hg::ctp_adapter::CtOffset;
using hg::ctp_adapter::CtOrderEventKind;
using hg::ctp_adapter::CtOrderReq;
using hg::ctp_adapter::CtPriceType;
using hg::ctp_adapter::mapping::field;
using hg::ctp_adapter::mapping::fromDepth;
using hg::ctp_adapter::mapping::fromOrder;
using hg::ctp_adapter::mapping::fromTrade;
using hg::ctp_adapter::mapping::setField;
using hg::ctp_adapter::mapping::toInputOrder;
using hg::ctp_adapter::mapping::toInputOrderAction;
using hg::ctp_adapter::mapping::toReqTransfer;
using hg::ctp_adapter::mapping::toReqUserLogin;

namespace {

CtConfig loginCfg() {
  CtConfig cfg;
  cfg.broker_id = "9999";
  cfg.user_id = "investor01";
  cfg.password = "DUMMY_PASSWORD";
  return cfg;
}

void login_mapping() {
  CThostFtdcReqUserLoginField f;
  toReqUserLogin(loginCfg(), &f);
  HG_CHECK_EQ(field(f.BrokerID), std::string("9999"));
  HG_CHECK_EQ(field(f.UserID), std::string("investor01"));
  HG_CHECK_EQ(field(f.Password), std::string("DUMMY_PASSWORD"));  // 映射须携带口令（内存，不落盘）
}

void futures_order_mapping() {
  const CtOrderReq req =
      hg::ctp_adapter::testutil::makeOrder("REF-FUT", "rb2601", CtMarket::kFutures);
  CThostFtdcInputOrderField f;
  toInputOrder(loginCfg(), req, &f);

  HG_CHECK_EQ(field(f.InstrumentID), std::string("rb2601"));
  HG_CHECK_EQ(field(f.ExchangeID), std::string("SHFE"));
  HG_CHECK_EQ(field(f.BrokerID), std::string("9999"));
  HG_CHECK_EQ(field(f.InvestorID), std::string("investor01"));
  HG_CHECK_EQ(field(f.UserID), std::string("investor01"));
  HG_CHECK_EQ(field(f.OrderRef), std::string("REF-FUT"));
  HG_CHECK_EQ(f.OrderPriceType, THOST_FTDC_OPT_LimitPrice);
  HG_CHECK_EQ(f.Direction, THOST_FTDC_D_Buy);
  HG_CHECK_EQ(f.CombOffsetFlag[0], THOST_FTDC_OF_Open);
  HG_CHECK_EQ(f.CombHedgeFlag[0], THOST_FTDC_HF_Speculation);
  HG_CHECK_EQ(f.LimitPrice, 3500.0);
  HG_CHECK_EQ(f.VolumeTotalOriginal, 2);
  HG_CHECK_EQ(f.TimeCondition, THOST_FTDC_TC_GFD);
  HG_CHECK_EQ(f.VolumeCondition, THOST_FTDC_VC_AV);
  HG_CHECK_EQ(f.ContingentCondition, THOST_FTDC_CC_Immediately);
  HG_CHECK_EQ(f.ForceCloseReason, THOST_FTDC_FCC_NotForceClose);
}

void option_order_mapping_shares_same_structure() {
  CtOrderReq req =
      hg::ctp_adapter::testutil::makeOrder("REF-OPT", "rb2601C3600", CtMarket::kOption);
  req.direction = CtDirection::kSell;
  req.offset = CtOffset::kCloseToday;
  CThostFtdcInputOrderField f;
  toInputOrder(loginCfg(), req, &f);

  // 期权与期货**共用**同一报单结构；类别由 InstrumentID 区分（PROJECT.md §10-10）。
  HG_CHECK_EQ(field(f.InstrumentID), std::string("rb2601C3600"));
  HG_CHECK_EQ(f.Direction, THOST_FTDC_D_Sell);
  HG_CHECK_EQ(f.CombOffsetFlag[0], THOST_FTDC_OF_CloseToday);
  HG_CHECK_EQ(f.VolumeTotalOriginal, 2);
  HG_CHECK_EQ(f.OrderPriceType, THOST_FTDC_OPT_LimitPrice);
}

void market_order_mapping() {
  CtOrderReq req =
      hg::ctp_adapter::testutil::makeOrder("REF-MKT", "rb2601", CtMarket::kFutures);
  req.price_type = CtPriceType::kMarket;
  req.limit_price = 0.0;
  CThostFtdcInputOrderField f;
  toInputOrder(loginCfg(), req, &f);
  HG_CHECK_EQ(f.OrderPriceType, THOST_FTDC_OPT_AnyPrice);
  HG_CHECK_EQ(f.LimitPrice, 0.0);
}

void cancel_mapping() {
  CtCancelReq req = hg::ctp_adapter::testutil::makeCancel("SYS-77");
  req.instrument_id = "rb2601";
  CThostFtdcInputOrderActionField f;
  toInputOrderAction(req, &f);
  HG_CHECK_EQ(field(f.ExchangeID), std::string("SHFE"));
  HG_CHECK_EQ(field(f.OrderSysID), std::string("SYS-77"));
  HG_CHECK_EQ(field(f.InstrumentID), std::string("rb2601"));
  HG_CHECK_EQ(f.ActionFlag, THOST_FTDC_AF_Delete);
}

void fund_transfer_mapping_in_and_out_differ() {
  CtConfig cfg = loginCfg();
  CtFundReq in = hg::ctp_adapter::testutil::makeFund(CtFundDirection::kIn);
  CThostFtdcReqTransferField fin;
  toReqTransfer(cfg, in, &fin);
  HG_CHECK_EQ(field(fin.TradeCode), std::string("202001"));
  HG_CHECK_EQ(field(fin.AccountID), std::string("ACC-001"));
  HG_CHECK_EQ(field(fin.BankID), std::string("BOC"));
  HG_CHECK_EQ(field(fin.CurrencyID), std::string("CNY"));
  HG_CHECK_EQ(fin.TradeAmount, 100000.0);

  CtFundReq out = hg::ctp_adapter::testutil::makeFund(CtFundDirection::kOut);
  CThostFtdcReqTransferField fout;
  toReqTransfer(cfg, out, &fout);
  HG_CHECK_EQ(field(fout.TradeCode), std::string("202002"));
  HG_CHECK(field(fin.TradeCode) != field(fout.TradeCode));
}

void callback_mapping_normalizes_events() {
  CThostFtdcOrderField order;
  std::memset(&order, 0, sizeof(order));
  setField(order.OrderRef, sizeof(order.OrderRef), "REF-1");
  setField(order.OrderLocalID, sizeof(order.OrderLocalID), "L-1");
  setField(order.OrderSysID, sizeof(order.OrderSysID), "S-1");
  setField(order.InstrumentID, sizeof(order.InstrumentID), "rb2601");
  order.OrderStatus = THOST_FTDC_OST_PartTradedQueueing;
  order.VolumeTotal = 5;
  order.VolumeTraded = 2;
  const hg::ctp_adapter::CtOrderEvent oe = fromOrder(order, 1234);
  HG_CHECK(oe.kind == CtOrderEventKind::kOrderReturn);
  HG_CHECK_EQ(oe.client_order_ref, std::string("REF-1"));
  HG_CHECK_EQ(oe.order_local_id, std::string("L-1"));
  HG_CHECK_EQ(oe.order_sys_id, std::string("S-1"));
  HG_CHECK_EQ(oe.volume_total, 5);
  HG_CHECK_EQ(oe.volume_traded, 2);
  HG_CHECK_EQ(oe.volume_left, 3);
  HG_CHECK_EQ(oe.report_ts_ms, std::int64_t(1234));

  CThostFtdcTradeField trade;
  std::memset(&trade, 0, sizeof(trade));
  setField(trade.TradeID, sizeof(trade.TradeID), "T-9");
  setField(trade.InstrumentID, sizeof(trade.InstrumentID), "rb2601");
  trade.Price = 3512.0;
  trade.Volume = 2;
  trade.Direction = THOST_FTDC_D_Buy;
  trade.OffsetFlag = THOST_FTDC_OF_Open;
  const hg::ctp_adapter::CtOrderEvent te = fromTrade(trade, 4321);
  HG_CHECK(te.kind == CtOrderEventKind::kTradeReturn);
  HG_CHECK_EQ(te.trade_id, std::string("T-9"));
  HG_CHECK_EQ(te.price, 3512.0);
  HG_CHECK_EQ(te.trade_volume, 2);
  HG_CHECK_EQ(te.report_ts_ms, std::int64_t(4321));

  CThostFtdcDepthMarketDataField depth;
  std::memset(&depth, 0, sizeof(depth));
  setField(depth.InstrumentID, sizeof(depth.InstrumentID), "rb2601");
  setField(depth.ExchangeID, sizeof(depth.ExchangeID), "SHFE");
  setField(depth.UpdateTime, sizeof(depth.UpdateTime), "10:30:00");
  depth.LastPrice = 3512.5;
  depth.PreSettlementPrice = 3490.0;
  depth.BidPrice1 = 3512.0;
  depth.AskPrice1 = 3513.0;
  depth.BidVolume1 = 10;
  depth.AskVolume1 = 12;
  depth.UpdateMillisec = 500;
  const hg::ctp_adapter::CtQuoteEvent qe = fromDepth(depth, 999);
  HG_CHECK_EQ(qe.instrument_id, std::string("rb2601"));
  HG_CHECK_EQ(qe.exchange_id, std::string("SHFE"));
  HG_CHECK_EQ(qe.update_time, std::string("10:30:00"));
  HG_CHECK_EQ(qe.last_price, 3512.5);
  HG_CHECK_EQ(qe.bid_volume1, 10);
  HG_CHECK_EQ(qe.ask_volume1, 12);
  HG_CHECK_EQ(qe.local_recv_ts_ms, std::int64_t(999));
}

void fixed_field_helper_trims_nul_and_truncates() {
  char buf[8];
  std::memset(buf, 'x', sizeof(buf));
  setField(buf, sizeof(buf), "abc");
  HG_CHECK_EQ(field(buf), std::string("abc"));
  setField(buf, sizeof(buf), "abcdefghij");  // 超长 → 截断到 7 + NUL
  HG_CHECK_EQ(field(buf), std::string("abcdefg"));
}

}  // namespace

int main() {
  login_mapping();
  futures_order_mapping();
  option_order_mapping_shares_same_structure();
  market_order_mapping();
  cancel_mapping();
  fund_transfer_mapping_in_and_out_differ();
  callback_mapping_normalizes_events();
  fixed_field_helper_trims_nul_and_truncates();
  return hg::ctp_adapter::testutil::report("ct_mapping_test");
}
