// =============================================================================
// 事前风控引擎（M3）—— 实现（ARCHITECTURE.md §3.3 / §4.4）
//
// 8 条规则（R-RISK-1 ~ R-RISK-8）全内存判定；三层限额全序；子账户 / 总账户双层校验。
// 判定路径纪律（§4.4.3 / §8.1.3）：零 DB I/O、零锁、零同步 I/O、零动态分配、无异常抛出。
// ⚠️ 本目录属 `E4` 扫描根。
// =============================================================================

#include "hg/risk_engine/risk_engine.h"

#include <string>

#include "hg/risk_engine/date_util.h"
#include "hg/risk_engine/fixed_math.h"
#include "hg/risk_engine/quota_selection.h"
#include "hg/risk_state/model.h"
#include "hg/risk_state/risk_snapshot.h"

namespace hg {
namespace risk_engine {

using hg::risk_state::AnnualScaleEntry;
using hg::risk_state::Category;
using hg::risk_state::ExposureDirection;
using hg::risk_state::ExposureEntry;
using hg::risk_state::Fixed;
using hg::risk_state::InstrumentMeta;
using hg::risk_state::PermissionEntry;
using hg::risk_state::PlanEntry;
using hg::risk_state::PositionDirection;
using hg::risk_state::PositionEntry;
using hg::risk_state::PositionLimitEntry;
using hg::risk_state::PositionType;
using hg::risk_state::QuotaEntry;
using hg::risk_state::QuotaLayer;
using hg::risk_state::RiskSnapshot;
using hg::risk_state::ScaleScope;
using hg::risk_state::VarietyMeta;
using hg::risk_state::kQtyScale;
using hg::risk_state::kRatioScale;
// T-127：岗位 / 角色维度判定的**共用纯函数**（定义于 `risk_state/model.h`）——
//   `evalPermission` 与 `RiskSnapshot::canTrade` 共用，从根上消除两处再分叉。
using hg::risk_state::permissionLeaderForbidden;
using hg::risk_state::permissionPostConflict;

namespace {

// ---- 组件错误码（ARCHITECTURE.md §4.3.2）----
const std::uint32_t kErrNotReady = 0x0401;
const std::uint32_t kErrProductNotWhitelisted = 0x1001;
const std::uint32_t kErrInstrumentProductMismatch = 0x1002;
const std::uint32_t kErrInstrumentNotDomestic = 0x1003;
const std::uint32_t kErrSubProductQuota = 0x1004;
const std::uint32_t kErrAccountProductQuota = 0x1005;
const std::uint32_t kErrPositionLimit = 0x1006;
const std::uint32_t kErrScaleCommodity = 0x1007;
const std::uint32_t kErrScaleTrade = 0x1008;
const std::uint32_t kErrNetPosition = 0x1009;
const std::uint32_t kErrTermRedline = 0x100A;
const std::uint32_t kErrNoTradingAuth = 0x100B;
const std::uint32_t kErrProductNotAuthorized = 0x100C;
const std::uint32_t kErrAuthorizedQuota = 0x100D;
const std::uint32_t kErrPositionConflict = 0x100E;
const std::uint32_t kErrLeaderForbidden = 0x100F;
const std::uint32_t kErrPriceBlock = 0x1010;
const std::uint32_t kErrNoBasePrice = 0x1015;
const std::uint32_t kErrSubInstrumentQuota = 0x1017;
const std::uint32_t kErrPriceWarn = 0x1018;
const std::uint32_t kErrInvalidRequest = 0x3003;

// ---- 数值工具 ----
Fixed absFixed(Fixed v) { return v < 0 ? -v : v; }

// 定点乘法（标度相加）；用于「数量 × 乘数」等中间量
Fixed qtyMul(Fixed a, Fixed b) { return mulDivFloor(a, b, 1); }

// 折算实物量（kQtyScale）：volume(1e4) × contract_multiplier(1e4) × convert_factor(1e4) / 1e8
// volume 可为负（净持仓多空相抵后会得到有符号值）。
Fixed realQty(Fixed volume, Fixed cm, Fixed cf) {
  if (volume == 0 || cm <= 0 || cf <= 0) return 0;
  const Fixed t = qtyMul(volume, cm);  // 1e8 标度（可负）
  return mulDivFloor(t, cf, kQtyScale * kQtyScale);
}

bool isDomesticExchange(const std::string& ex) {
  return ex == "SHFE" || ex == "DCE" || ex == "CZCE" || ex == "CFFEX" || ex == "INE" ||
         ex == "GFEX";
}

LimitLayer limitLayerOf(QuotaLayer l) {
  if (l == QuotaLayer::kSubProduct) return LimitLayer::kSubProduct;
  if (l == QuotaLayer::kSubInstrument) return LimitLayer::kSubInstrument;
  return LimitLayer::kAccountProduct;
}

std::uint32_t quotaCodeOf(QuotaLayer l) {
  if (l == QuotaLayer::kSubProduct) return kErrSubProductQuota;
  if (l == QuotaLayer::kSubInstrument) return kErrSubInstrumentQuota;
  return kErrAccountProductQuota;
}

// ---- 结果装配 ----
void addEntry(RiskResult& r, RuleId rule, std::uint32_t code, LimitLayer layer, Fixed cur,
              ValueScale cur_scale, Fixed thr, ValueScale thr_scale, const char* detail) {
  RiskEntry e;
  e.rule = rule;
  e.code = code;
  e.layer = layer;
  e.current = cur;
  e.current_scale = cur_scale;
  e.threshold = thr;
  e.threshold_scale = thr_scale;
  e.detail = detail;
  r.addEntry(e);
}

// 记录一次拒绝：主规则 / 错误码仅在**首次拒绝**时写入（规范顺序先到者胜）。
void markReject(RiskResult& r, RuleId rule, std::uint32_t code) {
  if (r.decision != RiskDecision::kReject) {
    r.rule = rule;
    r.code = code;
    r.limit_layer = LimitLayer::kNone;
    r.has_limit_layer = false;
  }
  r.decision = RiskDecision::kReject;
}

// 记录一次「限额层」拒绝（额外带命中层级）。
void markRejectLayer(RiskResult& r, RuleId rule, std::uint32_t code, LimitLayer layer) {
  if (r.decision != RiskDecision::kReject) {
    r.rule = rule;
    r.code = code;
    r.limit_layer = layer;
    r.has_limit_layer = (layer != LimitLayer::kNone);
  }
  r.decision = RiskDecision::kReject;
}

// 记录一次告警（仅当尚无拒绝时生效）
void markWarn(RiskResult& r, RuleId rule, std::uint32_t code) {
  if (r.decision == RiskDecision::kPass) {
    r.decision = RiskDecision::kWarn;
    r.rule = rule;
    r.code = code;
  }
}

// ---- 领域小工具 ----
Fixed positionQty(const RiskSnapshot& s, std::uint32_t sub, std::uint32_t instrument,
                  PositionDirection dir) {
  Fixed v = 0;
  const PositionEntry* a = s.findPosition(sub, instrument, dir, PositionType::kToday);
  if (a != nullptr) v += a->position_qty;
  const PositionEntry* b = s.findPosition(sub, instrument, dir, PositionType::kYesterday);
  if (b != nullptr) v += b->position_qty;
  return v;
}

Fixed requestInQuotaUnit(const OrderRequest& req, Fixed cm, Fixed cf, const QuotaEntry& q) {
  if (q.unit == "LOT") return req.volume;
  return realQty(req.volume, cm, cf);  // 非 LOT（如 TON）⇒ 折算实物量
}

const PlanEntry* findPlan(const RiskSnapshot& s, std::uint64_t plan_id) {
  const std::unordered_map<std::uint64_t, PlanEntry>& m = s.plans();
  std::unordered_map<std::uint64_t, PlanEntry>::const_iterator it = m.find(plan_id);
  return it == m.end() ? nullptr : &it->second;
}

int64_t allowedTermEnd(int64_t open_date, int64_t hedge_expiry, int64_t max_months,
                       int64_t grace_days) {
  int64_t allowed = addMonths(open_date, max_months);
  if (hedge_expiry > 0) {
    const int64_t with_grace = addDays(hedge_expiry, grace_days);
    if (with_grace < allowed) allowed = with_grace;
  }
  return allowed;
}

// =============================================================================
// R-RISK-1 品种白名单 + 合约归属 + 境内场内（FR-RISK-1）
// =============================================================================
void evalWhitelist(const RiskSnapshot& s, const OrderRequest& req, const InstrumentMeta* inst,
                   RiskResult& r) {
  if (!s.isWhitelisted(req.sub_account_id, req.product_id)) {
    addEntry(r, RuleId::kWhitelist, kErrProductNotWhitelisted, LimitLayer::kNone, req.product_id,
             ValueScale::kCount, 0, ValueScale::kNone, "product not in sub-account whitelist");
    markReject(r, RuleId::kWhitelist, kErrProductNotWhitelisted);
  }
  if (inst == nullptr) {
    addEntry(r, RuleId::kWhitelist, kErrInstrumentProductMismatch, LimitLayer::kNone,
             req.instrument_id, ValueScale::kCount, req.product_id, ValueScale::kCount,
             "instrument unknown");
    markReject(r, RuleId::kWhitelist, kErrInstrumentProductMismatch);
    return;
  }
  if (inst->product_id != req.product_id) {
    addEntry(r, RuleId::kWhitelist, kErrInstrumentProductMismatch, LimitLayer::kNone,
             inst->product_id, ValueScale::kCount, req.product_id, ValueScale::kCount,
             "instrument/product mismatch");
    markReject(r, RuleId::kWhitelist, kErrInstrumentProductMismatch);
  }
  if (!isDomesticExchange(inst->exchange)) {
    addEntry(r, RuleId::kWhitelist, kErrInstrumentNotDomestic, LimitLayer::kNone,
             req.instrument_id, ValueScale::kCount, 0, ValueScale::kNone,
             "non-domestic / OTC contract");
    markReject(r, RuleId::kWhitelist, kErrInstrumentNotDomestic);
  }
}

// =============================================================================
// R-RISK-2 三层限额（FR-RISK-2；全序 + 并列取一 + entries[] 全命中项）
//   **平仓不受限**：`R-RISK-5` 明列「额度阻断」属限制类处置，一律不得阻断平仓
//   （否则风险无法释放）⇒ 与 `R-RISK-3/4/5/6` 的平仓豁免口径一致。
// =============================================================================
void evalQuota(const RiskSnapshot& s, const OrderRequest& req, Fixed cm, Fixed cf, bool is_open,
               RiskResult& r) {
  if (!is_open) return;  // 平仓 / 减仓不受额度限制（R-RISK-5）
  QuotaHit hits[3];
  Fixed hit_req[3] = {0, 0, 0};
  int nh = 0;

  const QuotaLayer order[3] = {QuotaLayer::kSubInstrument, QuotaLayer::kSubProduct,
                               QuotaLayer::kAccountProduct};
  for (int i = 0; i < 3; ++i) {
    const QuotaEntry* q =
        s.findQuota(order[i], req.sub_account_id, req.product_id, req.instrument_id);
    if (q == nullptr) continue;
    const Fixed reqq = requestInQuotaUnit(req, cm, cf, *q);
    const Fixed used_total = q->used + q->frozen;
    if (used_total + reqq > q->limit) {
      QuotaHit h;
      h.layer = limitLayerOf(order[i]);
      h.limit = q->limit;
      h.used = q->used;
      h.frozen = q->frozen;
      h.id = 0;  // 快照内每层单一候选（键唯一）；并列规则由 selectFinestQuotaHit 承载
      hits[nh] = h;
      hit_req[nh] = reqq;
      ++nh;
    }
  }
  if (nh == 0) return;

  // `entries[]` 携带**全部命中项**（便于审计复算）
  for (int i = 0; i < nh; ++i) {
    const QuotaLayer l = (hits[i].layer == LimitLayer::kSubInstrument)
                             ? QuotaLayer::kSubInstrument
                             : (hits[i].layer == LimitLayer::kSubProduct ? QuotaLayer::kSubProduct
                                                                         : QuotaLayer::kAccountProduct);
    addEntry(r, RuleId::kQuota, quotaCodeOf(l), hits[i].layer,
             hits[i].used + hits[i].frozen + hit_req[i], ValueScale::kQty, hits[i].limit,
             ValueScale::kQty, "quota insufficient");
  }
  // 单笔按「最细命中者」上报层级与错误码
  const int sel = selectFinestQuotaHit(hits, nh);
  const QuotaLayer sel_layer = (hits[sel].layer == LimitLayer::kSubInstrument)
                                   ? QuotaLayer::kSubInstrument
                                   : (hits[sel].layer == LimitLayer::kSubProduct
                                          ? QuotaLayer::kSubProduct
                                          : QuotaLayer::kAccountProduct);
  markRejectLayer(r, RuleId::kQuota, quotaCodeOf(sel_layer), hits[sel].layer);
}

// =============================================================================
// R-RISK-3 持仓上限（FR-RISK-3；**平仓不受限**）
// =============================================================================
void evalPositionLimit(const RiskSnapshot& s, const OrderRequest& req, bool is_open,
                       RiskResult& r) {
  if (!is_open) return;  // 平仓 / 减仓不受限
  const PositionLimitEntry* pl =
      s.findPositionLimit(req.sub_account_id, req.product_id, req.instrument_id);
  if (pl == nullptr) {
    pl = s.findPositionLimit(req.sub_account_id, req.product_id, 0);  // 品种级上限
  }
  if (pl == nullptr) return;
  const PositionDirection pdir = (req.direction == OrderDirection::kBuy)
                                     ? PositionDirection::kLong
                                     : PositionDirection::kShort;
  const Fixed cur = positionQty(s, req.sub_account_id, req.instrument_id, pdir);
  const Fixed expected = cur + req.volume;
  if (expected > pl->limit) {
    addEntry(r, RuleId::kPositionLimit, kErrPositionLimit, LimitLayer::kNone, expected,
             ValueScale::kQty, pl->limit, ValueScale::kQty, "open would exceed position limit");
    markReject(r, RuleId::kPositionLimit, kErrPositionLimit);
  }
}

// =============================================================================
// R-RISK-4 规模红线（FR-RISK-4；90%/80%；子账户 + 全账户双算取严者；**平仓不受限**）
// =============================================================================
void evalScale(const RiskSnapshot& s, const OrderRequest& req, const VarietyMeta* var,
               const InstrumentMeta* inst, const RiskThresholds& th, int64_t year,
               bool is_open, RiskResult& r) {
  if (!is_open) return;
  if (var == nullptr || inst == nullptr) return;
  if (inst->contract_multiplier <= 0 || var->convert_factor <= 0) return;
  const Fixed inc = realQty(req.volume, inst->contract_multiplier, var->convert_factor);
  const Category cat = s.effectiveCategory(req.sub_account_id, req.product_id);
  // 默认商品类（Q-2）；品种级覆盖经 effectiveCategory 生效
  Fixed ratio = (cat == Category::kTrade) ? th.scale_ratio_trade : th.scale_ratio_commodity;
  const std::uint32_t code =
      (cat == Category::kTrade) ? kErrScaleTrade : kErrScaleCommodity;

  const ScaleScope scopes[2] = {ScaleScope::kSubAccount, ScaleScope::kTotalAccount};
  for (int i = 0; i < 2; ++i) {
    // 键的子账户分量：子账户口径用请求子账户；总账户口径用哨兵 0（账户级）
    const std::uint32_t key_sub =
        (scopes[i] == ScaleScope::kTotalAccount) ? 0u : req.sub_account_id;
    const AnnualScaleEntry* e =
        s.findAnnualScale(scopes[i], key_sub, req.product_id, static_cast<std::uint32_t>(year));
    if (e == nullptr) continue;
    Fixed eff = ratio;
    if (e->scale_ratio_limit > 0 && e->scale_ratio_limit < eff) eff = e->scale_ratio_limit;
    const Fixed projected = e->accumulated_hedge_qty + inc;
    if (ratioReaches(projected, e->expected_spot_business_qty, eff)) {
      addEntry(r, RuleId::kScaleRedline, code, LimitLayer::kNone, projected, ValueScale::kQty,
               eff, ValueScale::kRatio, "annual hedge scale reaches redline");
      markReject(r, RuleId::kScaleRedline, code);
    }
  }
}

// =============================================================================
// R-RISK-5 时点净持仓（FR-RISK-5；同口径换算；**减仓方向不受限**；标注快照时点）
// =============================================================================
void evalNetPosition(const RiskSnapshot& s, const OrderRequest& req, const VarietyMeta* var,
                     const InstrumentMeta* inst, bool is_open, RiskResult& r) {
  if (!is_open) return;
  if (var == nullptr || inst == nullptr) return;
  const ExposureDirection edir = (req.direction == OrderDirection::kBuy)
                                     ? ExposureDirection::kBuy
                                     : ExposureDirection::kSell;
  const ExposureEntry* ex = s.findExposure(req.sub_account_id, req.product_id, edir);

  // 净持仓（多空相抵，折算实物量）= 遍历该品种全部合约
  Fixed net_signed = 0;
  const std::unordered_map<std::uint32_t, InstrumentMeta>& im = s.instruments();
  for (std::unordered_map<std::uint32_t, InstrumentMeta>::const_iterator it = im.begin();
       it != im.end(); ++it) {
    const InstrumentMeta& m = it->second;
    if (m.product_id != req.product_id) continue;
    if (m.contract_multiplier <= 0 || var->convert_factor <= 0) continue;
    const Fixed lg = positionQty(s, req.sub_account_id, m.instrument_id, PositionDirection::kLong);
    const Fixed sh =
        positionQty(s, req.sub_account_id, m.instrument_id, PositionDirection::kShort);
    net_signed += realQty(lg - sh, m.contract_multiplier, var->convert_factor);
  }
  const Fixed inc = realQty(req.volume, inst->contract_multiplier, var->convert_factor);
  const Fixed projected = (req.direction == OrderDirection::kBuy) ? (net_signed + inc)
                                                                  : (-net_signed + inc);

  if (ex != nullptr) {
    if (ex->snapshot_time_ms != 0) {
      r.has_exposure_snapshot_time = true;
      r.exposure_snapshot_time_ms = ex->snapshot_time_ms;
    }
    if (projected > ex->exposure_qty) {
      addEntry(r, RuleId::kNetPosition, kErrNetPosition, LimitLayer::kNone, projected,
               ValueScale::kQty, ex->exposure_qty, ValueScale::kQty,
               "point-in-time net position would exceed hedge exposure");
      markReject(r, RuleId::kNetPosition, kErrNetPosition);
    }
  }
}

// =============================================================================
// R-RISK-6 期限红线（FR-RISK-6；取较早者；三级处置；**平仓与展期不受阻断**）
// =============================================================================
void evalTerm(const RiskSnapshot& s, const OrderRequest& req, const RiskThresholds& th,
              int64_t today, bool is_open, RiskResult& r) {
  if (!is_open) return;  // 不阻断平仓与展期置换
  const PlanEntry* plan = (req.trade_plan_id != 0) ? findPlan(s, req.trade_plan_id) : nullptr;
  int64_t open_date = req.open_date;
  if (open_date == 0 && plan != nullptr) open_date = plan->term_start_date;
  if (open_date == 0) open_date = today;
  int64_t term_end = req.term_end_date;
  if (term_end == 0 && plan != nullptr) term_end = plan->term_end_date;

  const int64_t allowed =
      allowedTermEnd(open_date, req.hedge_contract_expiry, th.term_max_months, th.term_grace_days);
  const int64_t remaining = daysBetween(today, allowed);

  if (term_end > 0 && term_end > allowed && !req.term_extension_approved) {
    addEntry(r, RuleId::kTerm, kErrTermRedline, LimitLayer::kNone, term_end, ValueScale::kDate,
             allowed, ValueScale::kDate,
             "term exceeds earlier of (open+max_months) / (contract+grace)");
    markReject(r, RuleId::kTerm, kErrTermRedline);
  } else if (remaining <= th.term_grace_days) {
    addEntry(r, RuleId::kTerm, kErrTermRedline, LimitLayer::kNone, remaining, ValueScale::kCount,
             th.term_grace_days, ValueScale::kCount, "remaining term <= grace days (warn)");
    markWarn(r, RuleId::kTerm, kErrTermRedline);
  }
}

// =============================================================================
// R-RISK-7 交易权限与授权（FR-RISK-7；未授权 / 超品种 / 超额度 / 岗位冲突 / 负责人禁操盘）
// =============================================================================
void evalPermission(const RiskSnapshot& s, const OrderRequest& req, RiskResult& r) {
  const PermissionEntry* p = s.findPermission(req.operator_id);
  std::uint32_t code = 0;
  LimitLayer layer = LimitLayer::kNone;
  const char* detail = "";
  Fixed thr = 0;
  ValueScale thr_scale = ValueScale::kNone;

  if (p == nullptr) {
    code = kErrNoTradingAuth;
    detail = "operator has no permission record";
  } else if (permissionLeaderForbidden(*p)) {
    // `0x100F`（`CH-010` 析取）：被标记为企业负责人 **或** 持有管理层（审批人）角色，
    //   任一独立成立即禁操盘（签署权与交易权互斥）。判据 = 共用纯函数（`T-127`）。
    code = kErrLeaderForbidden;
    detail = "enterprise leader / management role is forbidden to trade";
  } else if (permissionPostConflict(*p)) {
    // `0x100E` 析取（`FR-RISK-7`）：具备风控岗 **或** 结算岗权限的账号不得下单
    //   （前中后台分离）。判据 = 共用纯函数（`T-127`）。
    code = kErrPositionConflict;
    detail = "risk/settlement post cannot place orders (front/mid/back separation)";
  } else if (!p->has_trading_post) {
    code = kErrNoTradingAuth;
    detail = "operator has no trading post";
  } else {
    const bool sub_ok =
        p->allow_all_sub_accounts ||
        (p->sub_accounts.find(req.sub_account_id) != p->sub_accounts.end());
    if (!sub_ok) {
      code = kErrNoTradingAuth;
      detail = "sub-account not authorized";
    } else {
      const bool prod_ok = p->allow_all_products ||
                           (p->products.find(req.product_id) != p->products.end());
      if (!prod_ok) {
        code = kErrProductNotAuthorized;
        detail = "product not authorized";
      } else if (p->individual_quota > 0 && req.volume > p->individual_quota) {
        code = kErrAuthorizedQuota;
        layer = LimitLayer::kAuthQuota;
        detail = "individual authorized quota exceeded";
        thr = p->individual_quota;
        thr_scale = ValueScale::kQty;
      }
    }
  }
  if (code != 0) {
    addEntry(r, RuleId::kPermission, code, layer, req.volume, ValueScale::kQty, thr, thr_scale,
             detail);
    markRejectLayer(r, RuleId::kPermission, code, layer);
  }
}

// =============================================================================
// R-RISK-8 价格偏离（FR-RISK-8；基准 = 昨结算价；>5% 阻断；(2%,5%] 告警；市价单不适用）
// =============================================================================
void evalPrice(const OrderRequest& req, const RiskThresholds& th, RiskResult& r) {
  if (req.price_type == PriceType::kMarket) {
    r.price_deviation_na = true;  // 显式标注：市价单不适用偏离校验
    return;
  }
  const Fixed base = req.prev_settlement_price;
  if (base <= 0) {
    addEntry(r, RuleId::kPriceDeviation, kErrNoBasePrice, LimitLayer::kNone, req.limit_price,
             ValueScale::kAmount, 0, ValueScale::kNone, "no previous settlement price");
    markReject(r, RuleId::kPriceDeviation, kErrNoBasePrice);
    return;
  }
  const Fixed dev = absFixed(req.limit_price - base);
  if (ratioExceeds(dev, base, th.price_block_pct)) {
    addEntry(r, RuleId::kPriceDeviation, kErrPriceBlock, LimitLayer::kNone, dev, ValueScale::kAmount,
             mulDivFloor(base, th.price_block_pct, kRatioScale), ValueScale::kAmount,
             "price deviation > block threshold");
    markReject(r, RuleId::kPriceDeviation, kErrPriceBlock);
  } else if (ratioExceeds(dev, base, th.price_warn_pct)) {
    addEntry(r, RuleId::kPriceDeviation, kErrPriceWarn, LimitLayer::kNone, dev, ValueScale::kAmount,
             mulDivFloor(base, th.price_warn_pct, kRatioScale), ValueScale::kAmount,
             "price deviation in warn band, second confirmation required");
    markWarn(r, RuleId::kPriceDeviation, kErrPriceWarn);
  }
}

}  // namespace

// =============================================================================
// RiskEngine
// =============================================================================
RiskEngine::RiskEngine(const IClock* clock) : clock_(clock != nullptr ? clock : &default_clock_) {
  snapshot_.store(nullptr, std::memory_order_release);
}

RiskEngine::~RiskEngine() {}

void RiskEngine::applySnapshot(const RiskSnapshot& s) {
  // 写侧：一次性解析阈值（允许分配；不在判定路径内）
  thresholds_.price_warn_pct = s.thresholdFixed("risk.price.warn_pct", 20000);
  thresholds_.price_block_pct = s.thresholdFixed("risk.price.block_pct", 50000);
  thresholds_.scale_ratio_commodity = s.thresholdFixed("hedge.scale.ratio_commodity", 900000);
  thresholds_.scale_ratio_trade = s.thresholdFixed("hedge.scale.ratio_trade", 800000);
  thresholds_.term_max_months = s.thresholdInt("risk.term.max_months", 12);
  thresholds_.term_grace_days = s.thresholdInt("risk.term.grace_days", 30);
  snapshot_.store(&s, std::memory_order_release);
}

void RiskEngine::applySnapshot(const hg::risk_state::RiskState& state) {
  // 经 acquire() 取 RCU 读句柄并由引擎持有：快照在句柄存活期内**必不被回收**。
  guard_ = state.acquire();
  const RiskSnapshot& s = guard_.get();
  thresholds_.price_warn_pct = s.thresholdFixed("risk.price.warn_pct", 20000);
  thresholds_.price_block_pct = s.thresholdFixed("risk.price.block_pct", 50000);
  thresholds_.scale_ratio_commodity = s.thresholdFixed("hedge.scale.ratio_commodity", 900000);
  thresholds_.scale_ratio_trade = s.thresholdFixed("hedge.scale.ratio_trade", 800000);
  thresholds_.term_max_months = s.thresholdInt("risk.term.max_months", 12);
  thresholds_.term_grace_days = s.thresholdInt("risk.term.grace_days", 30);
  snapshot_.store(&s, std::memory_order_release);
}

const RiskSnapshot* RiskEngine::currentSnapshot() const {
  return snapshot_.load(std::memory_order_acquire);
}

RiskResult RiskEngine::check(const OrderRequest& req) const {
  RiskResult r;
  const RiskSnapshot* s = snapshot_.load(std::memory_order_acquire);
  if (s == nullptr || s->empty()) {
    r.decision = RiskDecision::kReject;
    r.rule = RuleId::kInput;
    r.code = kErrNotReady;
    addEntry(r, RuleId::kInput, kErrNotReady, LimitLayer::kNone, 0, ValueScale::kNone, 0,
             ValueScale::kNone, "system not ready: risk snapshot absent or empty");
    return r;
  }
  const char* bad = orderRequestInvalidReason(req);
  if (bad != nullptr) {
    r.decision = RiskDecision::kReject;
    r.rule = RuleId::kInput;
    r.code = kErrInvalidRequest;
    addEntry(r, RuleId::kInput, kErrInvalidRequest, LimitLayer::kNone, 0, ValueScale::kNone, 0,
             ValueScale::kNone, bad);
    return r;
  }

  const VarietyMeta* var = s->findVariety(req.product_id);
  const InstrumentMeta* inst = s->findInstrument(req.instrument_id);
  const bool is_open = (req.offset == OffsetFlag::kOpen);

  const Fixed cm = (inst != nullptr) ? inst->contract_multiplier : 0;
  const Fixed cf = (var != nullptr) ? var->convert_factor : 0;

  const int64_t today = clock_->todayYmd();
  int64_t year = req.year;
  if (year == 0) {
    const PlanEntry* plan = (req.trade_plan_id != 0) ? findPlan(*s, req.trade_plan_id) : nullptr;
    int64_t open_date = req.open_date;
    if (open_date == 0 && plan != nullptr) open_date = plan->term_start_date;
    if (open_date == 0) open_date = today;
    year = yearOfYmd(open_date);
  }

  // 规范顺序：R-RISK-1 → R-RISK-8（首次拒绝者为上报规则；全部规则均求值以收集 entries[]）
  evalWhitelist(*s, req, inst, r);
  evalQuota(*s, req, cm, cf, is_open, r);
  evalPositionLimit(*s, req, is_open, r);
  evalScale(*s, req, var, inst, thresholds_, year, is_open, r);
  evalNetPosition(*s, req, var, inst, is_open, r);
  evalTerm(*s, req, thresholds_, today, is_open, r);
  evalPermission(*s, req, r);
  evalPrice(req, thresholds_, r);
  return r;
}

}  // namespace risk_engine
}  // namespace hg
