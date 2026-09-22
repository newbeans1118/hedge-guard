#ifndef HG_CTP_ADAPTER_CT_MAPPING_H
#define HG_CTP_ADAPTER_CT_MAPPING_H
// =============================================================================
// CTP 适配层 —— `Ct*` ↔ `CThostFtdc*` 字段映射（**仅 HG_CTP_FOUND=ON 编译**）
//
// 承载：docs/ARCHITECTURE.md §3.1（封装合并版 UserApi）、§7.2.1（依赖清单）；
//       docs/REQUIREMENTS/TRADE.md FR-TRADE-2/4/5/6/12；FUND.md FR-FUND-5/6/8；
//       NONFUNCTIONAL.md NFR-COMPAT-6（期货与期权）。
//
// 说明（**实测口径，非文档旧文**）：CTP 合并版 SDK 实测存在
//   · `<ThostFtdcUserApi.h>`：交易 SPI `CThostFtdcUserSpi` / 交易 API `CThostFtdcUserApi`；
//   · `<ThostFtdcMdApi.h>`  ：行情 SPI `CThostFtdcMdSpi` / 行情 API `CThostFtdcMdApi`。
//   **不存在** `<ThostFtdcTraderApi.h>` / `CThostFtdcTraderSpi`。
//   期货与期权**共用** `CThostFtdcInputOrderField` 报单结构，类别由 `InstrumentID` 区分
//   （`PROJECT.md` §10-10：仅境内场内期货与期权；`NFR-COMPAT-6`）。
// =============================================================================

#include <cstdint>
#include <string>

#include "hg/ctp_adapter/ct_types.h"

#ifndef HG_CTP_FOUND
#define HG_CTP_FOUND 0
#endif

#if HG_CTP_FOUND
#include <ThostFtdcUserApiStruct.h>

namespace hg {
namespace ctp_adapter {
namespace mapping {

// CTP 定长字符数组 → std::string（截至首个 NUL）。
template <std::size_t N>
inline std::string field(const char (&arr)[N]) {
  std::size_t n = 0;
  while (n < N && arr[n] != '\0') ++n;
  return std::string(arr, n);
}

// std::string → CTP 定长字符数组（截断到 n-1 并补 NUL；超长不静默——由调用方保证长度）。
void setField(char* dst, std::size_t n, const std::string& value);

// ---- 请求映射（Ct* → CThostFtdc*）-----------------------------------------
void toReqUserLogin(const CtConfig& cfg, CThostFtdcReqUserLoginField* dst);
void toInputOrder(const CtConfig& cfg, const CtOrderReq& src,
                  CThostFtdcInputOrderField* dst);
void toInputOrderAction(const CtCancelReq& src, CThostFtdcInputOrderActionField* dst);
void toReqTransfer(const CtConfig& cfg, const CtFundReq& src,
                   CThostFtdcReqTransferField* dst);

// ---- 回调映射（CThostFtdc* → 归一化事件）----------------------------------
CtOrderEvent fromOrder(const CThostFtdcOrderField& src, std::int64_t now_ms);
CtOrderEvent fromTrade(const CThostFtdcTradeField& src, std::int64_t now_ms);
CtOrderEvent fromOrderError(const CtOrderEvent& base, const CThostFtdcRspInfoField& info);
CtQuoteEvent fromDepth(const CThostFtdcDepthMarketDataField& src, std::int64_t now_ms);

}  // namespace mapping
}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_FOUND
#endif  // HG_CTP_ADAPTER_CT_MAPPING_H
