// 单测：ctp_adapter 真实 CTP SPI 回调（仅 CTP SDK 轨）。
//   承重 ②：`CtSpiImpl` / `CtMdSpiImpl` 的每个回调**只做**「序列化 + 投递队列」——
//   以「调用回调后队列长度 +1、字段与 CTP 结构逐项一致、且不触发任何 CTP 请求」为证。
#include "ctp_adapter_test_util.h"
#include "fake_gateway.h"
#include "hg/ctp_adapter/ct_adapter.h"
#include "hg/ctp_adapter/ct_mapping.h"
#include "hg/ctp_adapter/ct_spi_impl.h"

#include <cstring>
#include <string>

#include <ThostFtdcMdApi.h>
#include <ThostFtdcUserApi.h>

using hg::ctp_adapter::CtAdapter;
using hg::ctp_adapter::CtMdSpiImpl;
using hg::ctp_adapter::CtOrderEvent;
using hg::ctp_adapter::CtOrderEventKind;
using hg::ctp_adapter::CtQuoteEvent;
using hg::ctp_adapter::CtSpiImpl;
using hg::ctp_adapter::mapping::setField;
using hg::ctp_adapter::testutil::FakeMdGateway;
using hg::ctp_adapter::testutil::FakeTradeGateway;

namespace {

void trade_spi_enqueues_on_rtn_trade() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  CtSpiImpl spi(&adapter);

  CThostFtdcTradeField f;
  std::memset(&f, 0, sizeof(f));
  setField(f.TradeID, sizeof(f.TradeID), "T-100");
  setField(f.OrderRef, sizeof(f.OrderRef), "REF-100");
  setField(f.OrderLocalID, sizeof(f.OrderLocalID), "L-100");
  setField(f.OrderSysID, sizeof(f.OrderSysID), "S-100");
  setField(f.InstrumentID, sizeof(f.InstrumentID), "rb2601");
  f.Price = 3512.0;
  f.Volume = 2;
  f.Direction = THOST_FTDC_D_Buy;
  f.OffsetFlag = THOST_FTDC_OF_Open;

  HG_CHECK_EQ(adapter.orderEventQueue()->size(), std::size_t(0));
  spi.OnRtnTrade(&f);
  HG_CHECK_EQ(adapter.orderEventQueue()->size(), std::size_t(1));

  CtOrderEvent out;
  HG_CHECK(adapter.pollOrderEvent(&out));
  HG_CHECK(out.kind == CtOrderEventKind::kTradeReturn);
  HG_CHECK_EQ(out.trade_id, std::string("T-100"));
  HG_CHECK_EQ(out.client_order_ref, std::string("REF-100"));
  HG_CHECK_EQ(out.order_local_id, std::string("L-100"));
  HG_CHECK_EQ(out.instrument_id, std::string("rb2601"));
  HG_CHECK(out.price == 3512.0);
  HG_CHECK_EQ(out.trade_volume, 2);
  // 回调未触发任何 CTP 请求（未把业务逻辑塞进回调）
  HG_CHECK_EQ(trade.inserts, 0);
  HG_CHECK_EQ(trade.transfers, 0);
}

void trade_spi_enqueues_on_rtn_order() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  CtSpiImpl spi(&adapter);

  CThostFtdcOrderField f;
  std::memset(&f, 0, sizeof(f));
  setField(f.OrderRef, sizeof(f.OrderRef), "REF-200");
  setField(f.OrderLocalID, sizeof(f.OrderLocalID), "L-200");
  setField(f.InstrumentID, sizeof(f.InstrumentID), "IO2603C4000");
  f.OrderStatus = THOST_FTDC_OST_AllTraded;
  f.VolumeTotal = 3;
  f.VolumeTraded = 3;

  spi.OnRtnOrder(&f);
  HG_CHECK_EQ(adapter.orderEventQueue()->size(), std::size_t(1));
  CtOrderEvent out;
  HG_CHECK(adapter.pollOrderEvent(&out));
  HG_CHECK(out.kind == CtOrderEventKind::kOrderReturn);
  HG_CHECK_EQ(out.instrument_id, std::string("IO2603C4000"));
  HG_CHECK_EQ(out.status, THOST_FTDC_OST_AllTraded);
  HG_CHECK_EQ(out.volume_left, 0);
}

void trade_spi_reports_order_error() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  CtSpiImpl spi(&adapter);

  CThostFtdcInputOrderField in;
  std::memset(&in, 0, sizeof(in));
  setField(in.OrderRef, sizeof(in.OrderRef), "REF-ERR");
  setField(in.InstrumentID, sizeof(in.InstrumentID), "rb2601");
  CThostFtdcRspInfoField info;
  std::memset(&info, 0, sizeof(info));
  info.ErrorID = 31;
  setField(info.ErrorMsg, sizeof(info.ErrorMsg), "CTP:No trading right");

  spi.OnErrRtnOrderInsert(&in, &info);
  HG_CHECK_EQ(adapter.orderEventQueue()->size(), std::size_t(1));
  CtOrderEvent out;
  HG_CHECK(adapter.pollOrderEvent(&out));
  HG_CHECK(out.kind == CtOrderEventKind::kOrderError);
  HG_CHECK_EQ(out.client_order_ref, std::string("REF-ERR"));
  HG_CHECK_EQ(out.ctp_error_id, 31);
  HG_CHECK_EQ(out.ctp_error_msg, std::string("CTP:No trading right"));
}

void trade_spi_enqueues_fund_receipt() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  CtSpiImpl spi(&adapter);

  CThostFtdcReqTransferField f;
  std::memset(&f, 0, sizeof(f));
  setField(f.AccountID, sizeof(f.AccountID), "ACC-001");
  setField(f.CurrencyID, sizeof(f.CurrencyID), "CNY");
  f.TradeAmount = 100000.0;
  CThostFtdcRspInfoField info;
  std::memset(&info, 0, sizeof(info));
  info.ErrorID = 0;

  spi.OnRspFromBankToFutureByFuture(&f, &info, 7, true);
  HG_CHECK_EQ(adapter.orderEventQueue()->size(), std::size_t(1));
  CtOrderEvent out;
  HG_CHECK(adapter.pollOrderEvent(&out));
  HG_CHECK(out.kind == CtOrderEventKind::kFundReceipt);
  HG_CHECK(out.fund_amount == 100000.0);
  HG_CHECK_EQ(out.currency_id, std::string("CNY"));
  HG_CHECK_EQ(out.ctp_error_id, 0);
}

void trade_spi_login_and_front_state() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  CtSpiImpl spi(&adapter);
  HG_CHECK(!adapter.isConnected());
  spi.OnFrontConnected();
  HG_CHECK(adapter.isConnected());

  CThostFtdcRspUserLoginField login;
  std::memset(&login, 0, sizeof(login));
  setField(login.TradingDay, sizeof(login.TradingDay), "20260922");
  CThostFtdcRspInfoField info;
  std::memset(&info, 0, sizeof(info));
  info.ErrorID = 0;
  HG_CHECK(!adapter.isLoggedIn());
  spi.OnRspUserLogin(&login, &info, 1, true);
  HG_CHECK(adapter.isLoggedIn());

  spi.OnFrontDisconnected(0x2001);  // 心跳超时
  HG_CHECK(!adapter.isConnected());
  HG_CHECK(!adapter.isLoggedIn());
}

void md_spi_enqueues_quote() {
  FakeTradeGateway trade;
  FakeMdGateway md;
  CtAdapter adapter(&trade, &md, false);
  CtMdSpiImpl spi(&adapter);

  CThostFtdcDepthMarketDataField f;
  std::memset(&f, 0, sizeof(f));
  setField(f.InstrumentID, sizeof(f.InstrumentID), "rb2601");
  setField(f.ExchangeID, sizeof(f.ExchangeID), "SHFE");
  setField(f.UpdateTime, sizeof(f.UpdateTime), "10:30:00");
  f.LastPrice = 3512.5;
  f.BidPrice1 = 3512.0;
  f.AskPrice1 = 3513.0;
  f.UpdateMillisec = 500;

  HG_CHECK_EQ(adapter.quoteQueue()->size(), std::size_t(0));
  spi.OnRtnDepthMarketData(&f);
  HG_CHECK_EQ(adapter.quoteQueue()->size(), std::size_t(1));
  CtQuoteEvent out;
  HG_CHECK(adapter.pollQuoteEvent(&out));
  HG_CHECK_EQ(out.instrument_id, std::string("rb2601"));
  HG_CHECK(out.last_price == 3512.5);
  HG_CHECK_EQ(out.update_millisec, 500);

  spi.OnFrontConnected();
  HG_CHECK(adapter.isMdConnected());
}

// 真实 SDK 链接证明：静态版本接口来自实际 .so（非头文件内联）。
void real_sdk_version_is_linked() {
  const std::string user_version = CThostFtdcUserApi::GetApiVersion();
  const std::string md_version = CThostFtdcMdApi::GetApiVersion();
  HG_CHECK(!user_version.empty());
  HG_CHECK(!md_version.empty());
}

}  // namespace

int main() {
  trade_spi_enqueues_on_rtn_trade();
  trade_spi_enqueues_on_rtn_order();
  trade_spi_reports_order_error();
  trade_spi_enqueues_fund_receipt();
  trade_spi_login_and_front_state();
  md_spi_enqueues_quote();
  real_sdk_version_is_linked();
  return hg::ctp_adapter::testutil::report("ct_spi_test");
}
