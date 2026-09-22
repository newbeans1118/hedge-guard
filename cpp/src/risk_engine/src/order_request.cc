// 事前风控引擎 —— 判定入参自检（ARCHITECTURE.md §3.3「非法判定值一律拒绝」）
#include "hg/risk_engine/order_request.h"

namespace hg {
namespace risk_engine {

const char* orderRequestInvalidReason(const OrderRequest& req) {
  const std::uint8_t dir = static_cast<std::uint8_t>(req.direction);
  if (dir != static_cast<std::uint8_t>(OrderDirection::kBuy) &&
      dir != static_cast<std::uint8_t>(OrderDirection::kSell)) {
    return "direction not BUY/SELL";
  }
  const std::uint8_t off = static_cast<std::uint8_t>(req.offset);
  if (off != static_cast<std::uint8_t>(OffsetFlag::kOpen) &&
      off != static_cast<std::uint8_t>(OffsetFlag::kClose)) {
    return "offset not OPEN/CLOSE";
  }
  const std::uint8_t pt = static_cast<std::uint8_t>(req.price_type);
  if (pt != static_cast<std::uint8_t>(PriceType::kLimit) &&
      pt != static_cast<std::uint8_t>(PriceType::kMarket)) {
    return "price_type not LIMIT/MARKET";
  }
  if (req.sub_account_id == 0) return "sub_account_id is required (!=0)";
  if (req.product_id == 0) return "product_id is required (!=0)";
  if (req.instrument_id == 0) return "instrument_id is required (!=0)";
  if (req.operator_id == 0) return "operator_id is required (!=0)";
  if (req.trade_plan_id == 0) return "trade_plan_id is required (!=0)";
  if (req.volume <= 0) return "volume must be positive";
  if (req.price_type == PriceType::kLimit && req.limit_price <= 0) {
    return "limit order requires positive limit_price";
  }
  return nullptr;
}

}  // namespace risk_engine
}  // namespace hg
