// 事前风控引擎 —— 结果码 / 名称（诊断可读；ARCHITECTURE.md §4.3.2 / §4.2.4）
#include "hg/risk_engine/risk_result.h"

namespace hg {
namespace risk_engine {

const char* ruleIdName(RuleId r) {
  switch (r) {
    case RuleId::kWhitelist: return "R-RISK-1";
    case RuleId::kQuota: return "R-RISK-2";
    case RuleId::kPositionLimit: return "R-RISK-3";
    case RuleId::kScaleRedline: return "R-RISK-4";
    case RuleId::kNetPosition: return "R-RISK-5";
    case RuleId::kTerm: return "R-RISK-6";
    case RuleId::kPermission: return "R-RISK-7";
    case RuleId::kPriceDeviation: return "R-RISK-8";
    case RuleId::kRelatedObject: return "R-TRADE-9";
    // T-181：0x1011 处置原则为 `R-RISK-5 @ REQUIREMENTS/RISK.md §4`（空间甲·只限开仓）。
    //   ⚠️ 该编号与空间乙（`R-RISK-5` 时点净持仓，`kNetPosition`）**同号异义**——
    //   `RISK.md` §4 表头明文声明两空间，此处沿用 ARCH:1378 的显式引用，勿混用。
    case RuleId::kTradingSuspend: return "R-RISK-5";
    case RuleId::kInput: return "R-INPUT";
    case RuleId::kNone: default: return "NONE";
  }
}

const char* limitLayerName(LimitLayer l) {
  switch (l) {
    case LimitLayer::kSubProduct: return "SUB_PRODUCT";
    case LimitLayer::kSubInstrument: return "SUB_INSTRUMENT";
    case LimitLayer::kAccountProduct: return "ACCOUNT_PRODUCT";
    case LimitLayer::kAuthQuota: return "AUTH_QUOTA";
    case LimitLayer::kNone: default: return "NONE";
  }
}

const char* valueScaleName(ValueScale s) {
  switch (s) {
    case ValueScale::kQty: return "QTY_1E4";
    case ValueScale::kAmount: return "AMOUNT_1E6";
    case ValueScale::kRatio: return "RATIO_1E6";
    case ValueScale::kCount: return "COUNT";
    case ValueScale::kDate: return "DATE_YYYYMMDD";
    case ValueScale::kNone: default: return "NONE";
  }
}

const char* riskCodeName(std::uint32_t code) {
  switch (code) {
    case 0x0000: return "OK";
    case 0x0401: return "ERR_SYSTEM_NOT_READY";
    case 0x1001: return "RISK_PRODUCT_NOT_WHITELISTED";
    case 0x1002: return "RISK_INSTRUMENT_PRODUCT_MISMATCH";
    case 0x1003: return "RISK_INSTRUMENT_NOT_DOMESTIC_EXCHANGE";
    case 0x1004: return "RISK_SUB_PRODUCT_QUOTA_INSUFFICIENT";
    case 0x1005: return "RISK_ACCOUNT_PRODUCT_QUOTA_INSUFFICIENT";
    case 0x1006: return "RISK_POSITION_LIMIT_EXCEEDED";
    case 0x1007: return "RISK_SCALE_REDLINE_COMMODITY";
    case 0x1008: return "RISK_SCALE_REDLINE_TRADE";
    case 0x1009: return "RISK_NET_POSITION_OVER_EXPOSURE";
    case 0x100A: return "RISK_TERM_REDLINE_EXCEEDED";
    case 0x100B: return "RISK_NO_TRADING_AUTHORIZATION";
    case 0x100C: return "RISK_PRODUCT_NOT_AUTHORIZED";
    case 0x100D: return "RISK_AUTHORIZED_QUOTA_EXCEEDED";
    case 0x100E: return "RISK_POSITION_CONFLICT";
    case 0x100F: return "RISK_ENTERPRISE_LEADER_FORBIDDEN";
    case 0x1010: return "RISK_PRICE_DEVIATION_BLOCK";
    case 0x1011: return "RISK_TRADING_SUSPENDED";
    case 0x1015: return "RISK_MARKET_PRICE_NOT_AVAILABLE";
    case 0x1016: return "RISK_MARKET_ORDER_DEVIATION_NA";
    case 0x1017: return "RISK_SUB_INSTRUMENT_QUOTA_INSUFFICIENT";
    case 0x1018: return "RISK_PRICE_DEVIATION_WARN";
    case 0x1019: return "RISK_RELATED_OBJECT_SUB_MISMATCH";
    case 0x3003: return "UNSUPPORTED_OPERATION";
    default: return "UNKNOWN";
  }
}

}  // namespace risk_engine
}  // namespace hg
