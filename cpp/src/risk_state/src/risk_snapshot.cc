// 风控内存状态 —— 不可变快照 RiskSnapshot 实现（ARCHITECTURE.md §3.4 / §4.4.1）
#include "hg/risk_state/risk_snapshot.h"

#include <cstdlib>

namespace hg {
namespace risk_state {
namespace {

// 把十进制字符串解析为定点整数（scale 由调用方给出）。
// 禁止 double：纯整数/字符解析，避免浮点误差（ARCH §4.4.2 定点纪律）。
bool parseFixed(const std::string& s, std::int64_t scale, Fixed* out) {
  if (out == nullptr) return false;
  std::size_t i = 0;
  const std::size_t n = s.size();
  if (n == 0) return false;
  bool neg = false;
  if (s[i] == '+' || s[i] == '-') {
    neg = (s[i] == '-');
    ++i;
  }
  std::int64_t int_part = 0;
  bool any = false;
  while (i < n && s[i] >= '0' && s[i] <= '9') {
    int_part = int_part * 10 + (s[i] - '0');
    any = true;
    ++i;
  }
  std::int64_t frac = 0;
  if (i < n && s[i] == '.') {
    ++i;
    std::int64_t fscale = scale;
    while (i < n && s[i] >= '0' && s[i] <= '9') {
      if (fscale > 1) {
        frac = frac * 10 + (s[i] - '0');
        fscale /= 10;
      }
      any = true;
      ++i;
    }
    // 若小数位少于 scale 位数，补零到 scale
    while (fscale > 1) {
      frac *= 10;
      fscale /= 10;
    }
  } else {
    frac = 0;
  }
  if (i != n || !any) return false;
  Fixed v = int_part * scale + frac;
  *out = neg ? -v : v;
  return true;
}

}  // namespace

RiskSnapshot::RiskSnapshot() {}

const VarietyMeta* RiskSnapshot::findVariety(std::uint32_t product_id) const {
  std::unordered_map<std::uint32_t, VarietyMeta>::const_iterator it = varieties_.find(product_id);
  return it == varieties_.end() ? nullptr : &it->second;
}

const InstrumentMeta* RiskSnapshot::findInstrument(std::uint32_t instrument_id) const {
  std::unordered_map<std::uint32_t, InstrumentMeta>::const_iterator it =
      instruments_.find(instrument_id);
  return it == instruments_.end() ? nullptr : &it->second;
}

bool RiskSnapshot::isWhitelisted(std::uint32_t sub_account_id, std::uint32_t product_id) const {
  return whitelist_.find(packKey2(sub_account_id, product_id)) != whitelist_.end();
}

Category RiskSnapshot::whitelistCategoryOverride(std::uint32_t sub_account_id,
                                                 std::uint32_t product_id) const {
  std::unordered_map<std::uint64_t, Category>::const_iterator it =
      whitelist_category_override_.find(packKey2(sub_account_id, product_id));
  return it == whitelist_category_override_.end() ? Category::kUnknown : it->second;
}

const QuotaEntry* RiskSnapshot::findQuota(QuotaLayer layer, std::uint32_t sub_account_id,
                                          std::uint32_t product_id,
                                          std::uint32_t instrument_id) const {
  if (layer == QuotaLayer::kSubProduct) {
    std::unordered_map<std::uint64_t, QuotaEntry>::const_iterator it =
        quota_sub_product_.find(packKey2(sub_account_id, product_id));
    return it == quota_sub_product_.end() ? nullptr : &it->second;
  }
  if (layer == QuotaLayer::kSubInstrument) {
    std::unordered_map<std::uint64_t, QuotaEntry>::const_iterator it =
        quota_sub_instrument_.find(packKey2(sub_account_id, instrument_id));
    return it == quota_sub_instrument_.end() ? nullptr : &it->second;
  }
  // 层③：账户 + 品种（哨兵 sub=0 的账户级行）
  std::unordered_map<std::uint32_t, QuotaEntry>::const_iterator it =
      quota_account_product_.find(product_id);
  return it == quota_account_product_.end() ? nullptr : &it->second;
}

const PositionEntry* RiskSnapshot::findPosition(std::uint32_t sub_account_id,
                                                std::uint32_t instrument_id,
                                                PositionDirection dir,
                                                PositionType type) const {
  const std::uint32_t dir_type = (static_cast<std::uint32_t>(dir) << 4) |
                                 static_cast<std::uint32_t>(type);
  std::unordered_map<std::uint64_t, PositionEntry>::const_iterator it =
      positions_.find(packKey3(sub_account_id, dir_type, instrument_id));
  return it == positions_.end() ? nullptr : &it->second;
}

const PositionLimitEntry* RiskSnapshot::findPositionLimit(std::uint32_t sub_account_id,
                                                          std::uint32_t product_id,
                                                          std::uint32_t instrument_id) const {
  std::unordered_map<std::uint64_t, PositionLimitEntry>::const_iterator it =
      position_limits_.find(packKey3(sub_account_id, product_id, instrument_id));
  return it == position_limits_.end() ? nullptr : &it->second;
}

const ExposureEntry* RiskSnapshot::findExposure(std::uint32_t sub_account_id,
                                                std::uint32_t product_id,
                                                ExposureDirection dir) const {
  std::unordered_map<std::uint64_t, ExposureEntry>::const_iterator it =
      exposures_.find(packKey3(sub_account_id, product_id, static_cast<std::uint32_t>(dir)));
  return it == exposures_.end() ? nullptr : &it->second;
}

const AnnualScaleEntry* RiskSnapshot::findAnnualScale(ScaleScope scope, std::uint32_t sub_account_id,
                                                      std::uint32_t product_id,
                                                      std::uint32_t year) const {
  std::unordered_map<std::uint64_t, AnnualScaleEntry>::const_iterator it =
      annual_scales_.find(packAnnualKey(scope, sub_account_id, product_id, year));
  return it == annual_scales_.end() ? nullptr : &it->second;
}

Category RiskSnapshot::effectiveCategory(std::uint32_t sub_account_id,
                                         std::uint32_t product_id) const {
  // 品种级覆盖 > 子账户分类 > 品种档案分类
  std::unordered_map<std::uint64_t, Category>::const_iterator over =
      sub_product_category_.find(packKey2(sub_account_id, product_id));
  if (over != sub_product_category_.end()) return over->second;
  std::unordered_map<std::uint32_t, Category>::const_iterator sub =
      sub_account_category_.find(sub_account_id);
  if (sub != sub_account_category_.end()) return sub->second;
  const VarietyMeta* v = findVariety(product_id);
  return v == nullptr ? Category::kUnknown : v->category;
}

const PermissionEntry* RiskSnapshot::findPermission(std::uint32_t operator_id) const {
  std::unordered_map<std::uint32_t, PermissionEntry>::const_iterator it =
      permissions_.find(operator_id);
  return it == permissions_.end() ? nullptr : &it->second;
}

bool RiskSnapshot::canTrade(std::uint32_t operator_id, std::uint32_t sub_account_id,
                            std::uint32_t product_id) const {
  const PermissionEntry* p = findPermission(operator_id);
  if (p == nullptr) return false;  // 安全默认：无授权 = 不可交易
  // 负责人标记 → 禁授交易岗（NFR-SEC-5 的 C++ 侧兜底）
  if (p->is_responsible) return false;
  if (!p->has_trading_post) return false;
  const bool sub_ok = p->allow_all_sub_accounts ||
                      p->sub_accounts.find(sub_account_id) != p->sub_accounts.end();
  if (!sub_ok) return false;
  const bool prod_ok = p->allow_all_products ||
                       p->products.find(product_id) != p->products.end();
  return prod_ok;
}

const QuotaEntry* RiskSnapshot::findTotalLimitByProduct(std::uint32_t product_id) const {
  std::unordered_map<std::uint32_t, QuotaEntry>::const_iterator it =
      total_limit_by_product_.find(product_id);
  return it == total_limit_by_product_.end() ? nullptr : &it->second;
}

bool RiskSnapshot::hasThreshold(const std::string& key) const {
  return thresholds_.find(key) != thresholds_.end();
}

std::string RiskSnapshot::thresholdRaw(const std::string& key,
                                       const std::string& fallback) const {
  std::unordered_map<std::string, std::string>::const_iterator it = thresholds_.find(key);
  return it == thresholds_.end() ? fallback : it->second;
}

Fixed RiskSnapshot::thresholdFixed(const std::string& key, Fixed fallback) const {
  std::unordered_map<std::string, std::string>::const_iterator it = thresholds_.find(key);
  if (it == thresholds_.end()) return fallback;
  Fixed v = 0;
  if (!parseFixed(it->second, kRatioScale, &v)) return fallback;
  return v;
}

std::int64_t RiskSnapshot::thresholdInt(const std::string& key, std::int64_t fallback) const {
  std::unordered_map<std::string, std::string>::const_iterator it = thresholds_.find(key);
  if (it == thresholds_.end()) return fallback;
  return std::strtoll(it->second.c_str(), nullptr, 10);
}

bool RiskSnapshot::thresholdBool(const std::string& key, bool fallback) const {
  std::unordered_map<std::string, std::string>::const_iterator it = thresholds_.find(key);
  if (it == thresholds_.end()) return fallback;
  const std::string& s = it->second;
  if (s == "true" || s == "TRUE" || s == "1" || s == "Y" || s == "YES") return true;
  if (s == "false" || s == "FALSE" || s == "0" || s == "N" || s == "NO") return false;
  return fallback;
}

std::size_t RiskSnapshot::countRecords() const {
  std::size_t n = 0;
  n += varieties_.size();
  n += instruments_.size();
  n += whitelist_.size();
  n += whitelist_category_override_.size();
  n += quota_sub_product_.size();
  n += quota_sub_instrument_.size();
  n += quota_account_product_.size();
  n += positions_.size();
  n += position_limits_.size();
  n += exposures_.size();
  n += annual_scales_.size();
  n += sub_account_category_.size();
  n += sub_product_category_.size();
  n += permissions_.size();
  n += thresholds_.size();
  n += total_limit_by_product_.size();
  n += plans_.size();
  return n;
}

RiskSnapshot* RiskSnapshot::clone() const {
  RiskSnapshot* c = new RiskSnapshot();
  c->param_version_ = param_version_;
  c->build_time_ms_ = build_time_ms_;
  c->record_count_ = record_count_;
  c->varieties_ = varieties_;
  c->instruments_ = instruments_;
  c->whitelist_ = whitelist_;
  c->whitelist_category_override_ = whitelist_category_override_;
  c->quota_sub_product_ = quota_sub_product_;
  c->quota_sub_instrument_ = quota_sub_instrument_;
  c->quota_account_product_ = quota_account_product_;
  c->positions_ = positions_;
  c->position_limits_ = position_limits_;
  c->exposures_ = exposures_;
  c->annual_scales_ = annual_scales_;
  c->sub_account_category_ = sub_account_category_;
  c->sub_product_category_ = sub_product_category_;
  c->permissions_ = permissions_;
  c->thresholds_ = thresholds_;
  c->total_limit_by_product_ = total_limit_by_product_;
  c->account_total_limit_ = account_total_limit_;
  c->plans_ = plans_;
  return c;
}

namespace {

// 额度单元非负（limit / used / frozen）
bool quotaNonNegative(const QuotaEntry& q) {
  return q.limit >= 0 && q.used >= 0 && q.frozen >= 0;
}

}  // namespace

int RiskSnapshot::checkInvariants() const {
  // ① 三层额度 + 总账户限额：非负
  for (std::unordered_map<std::uint64_t, QuotaEntry>::const_iterator it = quota_sub_product_.begin();
       it != quota_sub_product_.end(); ++it) {
    if (!quotaNonNegative(it->second)) return kRebuildNegativeValue;
  }
  for (std::unordered_map<std::uint64_t, QuotaEntry>::const_iterator it =
           quota_sub_instrument_.begin();
       it != quota_sub_instrument_.end(); ++it) {
    if (!quotaNonNegative(it->second)) return kRebuildNegativeValue;
  }
  for (std::unordered_map<std::uint32_t, QuotaEntry>::const_iterator it =
           quota_account_product_.begin();
       it != quota_account_product_.end(); ++it) {
    if (!quotaNonNegative(it->second)) return kRebuildNegativeValue;
  }
  for (std::unordered_map<std::uint32_t, QuotaEntry>::const_iterator it =
           total_limit_by_product_.begin();
       it != total_limit_by_product_.end(); ++it) {
    if (!quotaNonNegative(it->second)) return kRebuildNegativeValue;
  }
  if (!quotaNonNegative(account_total_limit_)) return kRebuildNegativeValue;

  // ⑥ 持仓上限：非负
  for (std::unordered_map<std::uint64_t, PositionLimitEntry>::const_iterator it =
           position_limits_.begin();
       it != position_limits_.end(); ++it) {
    if (it->second.limit < 0) return kRebuildNegativeValue;
  }

  // ⑤ 持仓：非负 + available/frozen ≤ position（TB-TRADE-5 `ck_position_qty_consistency`）
  for (std::unordered_map<std::uint64_t, PositionEntry>::const_iterator it = positions_.begin();
       it != positions_.end(); ++it) {
    const PositionEntry& p = it->second;
    if (p.position_qty < 0 || p.available_qty < 0 || p.frozen_qty < 0) {
      return kRebuildNegativeValue;
    }
    if (p.available_qty > p.position_qty || p.frozen_qty > p.position_qty) {
      return kRebuildPositionInconsistent;
    }
  }

  // ⑦ 敞口：三分量与合计非负
  for (std::unordered_map<std::uint64_t, ExposureEntry>::const_iterator it = exposures_.begin();
       it != exposures_.end(); ++it) {
    const ExposureEntry& e = it->second;
    if (e.contract_unexecuted_qty < 0 || e.inventory_qty < 0 || e.production_plan_qty < 0 ||
        e.exposure_qty < 0 || e.covered_qty < 0) {
      return kRebuildNegativeValue;
    }
  }

  // ⑧ 年度规模基数：分子/分母非负 + 红线比例 ∈ [0, 1]
  for (std::unordered_map<std::uint64_t, AnnualScaleEntry>::const_iterator it =
           annual_scales_.begin();
       it != annual_scales_.end(); ++it) {
    const AnnualScaleEntry& a = it->second;
    if (a.expected_spot_business_qty < 0 || a.accumulated_hedge_qty < 0) {
      return kRebuildNegativeValue;
    }
    if (a.scale_ratio_limit < 0 || a.scale_ratio_limit > kRatioScale) {
      return kRebuildInvalidRatio;
    }
  }

  // ④ 启用品种的换算系数 > 0（R-PRODUCT-6）
  for (std::unordered_map<std::uint32_t, VarietyMeta>::const_iterator it = varieties_.begin();
       it != varieties_.end(); ++it) {
    const VarietyMeta& v = it->second;
    if (v.status == ProductStatus::kEnabled && v.convert_factor <= 0) {
      return kRebuildMissingConvertFactor;
    }
  }

  return kRebuildOk;
}

}  // namespace risk_state
}  // namespace hg
