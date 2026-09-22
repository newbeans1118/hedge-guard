#ifndef HG_RISK_STATE_LOAD_BATCH_H
#define HG_RISK_STATE_LOAD_BATCH_H
// =============================================================================
// 风控内存状态 —— 加载批次 LoadBatch（ARCHITECTURE.md §3.4 `rebuild(const LoadBatch&)`）
//
// LoadBatch = Java 侧经控制指令下发的**一批风控数据**（`CMD_LOAD_RISK_PARAMS` 全量 /
//   `CMD_UPDATE_*` 增量，FR-RISK-9 / FR-RISK-10）。它是「输入描述」，**不是权威状态**：
//   权威状态是不可变快照（`RiskSnapshot`）。`RiskState::rebuild` 以 LoadBatch 构建新快照。
//
// 语义：
//   · `replace_all()==true`  ⇒ 以空快照为基底（全量预加载 / CMD_LOAD_RISK_PARAMS）
//   · `replace_all()==false` ⇒ 以当前快照的**深拷贝为基底**，本批条目覆盖/新增（增量）
//   · 白名单支持显式移除（移出白名单 = 置 DISABLED，DATA_MODEL TB-PRODUCT-5）
//
// 本文件为纯数据容器（无 I/O、无锁）；构造可分配，但 `RiskState::rebuild` 之后即冻结。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <unordered_set>

#include "hg/risk_state/model.h"
#include "hg/risk_state/risk_snapshot.h"

namespace hg {
namespace risk_state {

class LoadBatch {
 public:
  LoadBatch() : replace_all_(false), param_version_(0), build_time_ms_(0) {}

  void set_param_version(std::uint64_t v) { param_version_ = v; }
  void set_replace_all(bool v) { replace_all_ = v; }
  void set_build_time_ms(std::int64_t t) { build_time_ms_ = t; }

  std::uint64_t param_version() const { return param_version_; }
  bool replace_all() const { return replace_all_; }
  std::int64_t build_time_ms() const { return build_time_ms_; }

  // ---- ① 品种档案（DATA_MODEL TB-PRODUCT-1/2）----
  void putVariety(const VarietyMeta& v) { varieties_[v.product_id] = v; }
  // ---- ② 合约档案（TB-PRODUCT-3）----
  void putInstrument(const InstrumentMeta& m) { instruments_[m.instrument_id] = m; }
  // ---- ③ 品种白名单（TB-PRODUCT-5）----
  void addWhitelist(std::uint32_t sub, std::uint32_t product,
                    Category override_cat = Category::kUnknown) {
    whitelist_.insert(packKey2(sub, product));
    if (override_cat != Category::kUnknown) {
      whitelist_category_override_[packKey2(sub, product)] = override_cat;
    }
  }
  void removeWhitelist(std::uint32_t sub, std::uint32_t product) {
    whitelist_remove_.insert(packKey2(sub, product));
  }
  // ---- ④ 三层额度（TB-PRODUCT-6 层①/层③、TB-PRODUCT-8 层②）----
  void putQuota(QuotaLayer layer, std::uint32_t sub, std::uint32_t product,
                std::uint32_t instrument, const QuotaEntry& q) {
    if (layer == QuotaLayer::kSubProduct) {
      quota_sub_product_[packKey2(sub, product)] = q;
    } else if (layer == QuotaLayer::kSubInstrument) {
      quota_sub_instrument_[packKey2(sub, instrument)] = q;
    } else {
      quota_account_product_[product] = q;  // 层③：账户级（哨兵 sub=0）
    }
  }
  // ---- ⑤ 持仓（TB-TRADE-5）----
  void putPosition(std::uint32_t sub, std::uint32_t instrument, PositionDirection dir,
                   PositionType type, const PositionEntry& p) {
    const std::uint32_t dt = (static_cast<std::uint32_t>(dir) << 4) |
                             static_cast<std::uint32_t>(type);
    positions_[packKey3(sub, dt, instrument)] = p;
  }
  // ---- ⑥ 持仓上限（ARCH §4.4.1 PositionLimit）----
  void putPositionLimit(std::uint32_t sub, std::uint32_t product, std::uint32_t instrument,
                        const PositionLimitEntry& l) {
    position_limits_[packKey3(sub, product, instrument)] = l;
  }
  // ---- ⑦ 实货敞口（TB-SPOT-8）----
  void putExposure(std::uint32_t sub, std::uint32_t product, ExposureDirection dir,
                   const ExposureEntry& e) {
    exposures_[packKey3(sub, product, static_cast<std::uint32_t>(dir))] = e;
  }
  // ---- ⑧ 年度规模基数（TB-RISK-11）----
  void putAnnualScale(ScaleScope scope, std::uint32_t sub, std::uint32_t product,
                      std::uint32_t year, const AnnualScaleEntry& a) {
    annual_scales_[packAnnualKey(scope, sub, product, year)] = a;
  }
  // ---- ⑨ 子账户 / 品种级分类（TB-CONFIG-1 / TB-PRODUCT-5 override）----
  void putSubAccountCategory(std::uint32_t sub, Category c) { sub_account_category_[sub] = c; }
  void putSubProductCategory(std::uint32_t sub, std::uint32_t product, Category c) {
    sub_product_category_[packKey2(sub, product)] = c;
  }
  // ---- ⑩ 权限（TB-AUTH-7/8）----
  void putPermission(std::uint32_t operator_id, const PermissionEntry& p) {
    permissions_[operator_id] = p;
  }
  // ---- ⑪ 阈值（TB-RISK-1）----
  void putThreshold(const std::string& key, const std::string& value) {
    thresholds_[key] = value;
  }
  // ---- ⑫ 总账户限额（ARCH §4.4.1 TotalLimits）----
  void putTotalLimit(std::uint32_t product, const QuotaEntry& q) {
    total_limit_by_product_[product] = q;
  }
  void setAccountTotalLimit(const QuotaEntry& q) { account_total_limit_ = q; }
  // ---- ⑬ 计划判定子集（TB-PLAN-1）----
  void putPlan(std::uint64_t plan_id, const PlanEntry& p) { plans_[plan_id] = p; }

  // 把本批条目覆盖/新增到目标快照（不做版本/时点等元信息写入——由 RiskState 负责）。
  void applyTo(RiskSnapshot& s) const;

  // 本批携带的业务条目数（不含元信息）
  std::size_t itemCount() const;

 private:
  bool replace_all_;
  std::uint64_t param_version_;
  std::int64_t build_time_ms_;

  std::unordered_map<std::uint32_t, VarietyMeta> varieties_;
  std::unordered_map<std::uint32_t, InstrumentMeta> instruments_;
  std::unordered_set<std::uint64_t> whitelist_;
  std::unordered_map<std::uint64_t, Category> whitelist_category_override_;
  std::unordered_set<std::uint64_t> whitelist_remove_;
  std::unordered_map<std::uint64_t, QuotaEntry> quota_sub_product_;
  std::unordered_map<std::uint64_t, QuotaEntry> quota_sub_instrument_;
  std::unordered_map<std::uint32_t, QuotaEntry> quota_account_product_;
  std::unordered_map<std::uint64_t, PositionEntry> positions_;
  std::unordered_map<std::uint64_t, PositionLimitEntry> position_limits_;
  std::unordered_map<std::uint64_t, ExposureEntry> exposures_;
  std::unordered_map<std::uint64_t, AnnualScaleEntry> annual_scales_;
  std::unordered_map<std::uint32_t, Category> sub_account_category_;
  std::unordered_map<std::uint64_t, Category> sub_product_category_;
  std::unordered_map<std::uint32_t, PermissionEntry> permissions_;
  std::unordered_map<std::string, std::string> thresholds_;
  std::unordered_map<std::uint32_t, QuotaEntry> total_limit_by_product_;
  QuotaEntry account_total_limit_;
  std::unordered_map<std::uint64_t, PlanEntry> plans_;
};

}  // namespace risk_state
}  // namespace hg

#endif  // HG_RISK_STATE_LOAD_BATCH_H
