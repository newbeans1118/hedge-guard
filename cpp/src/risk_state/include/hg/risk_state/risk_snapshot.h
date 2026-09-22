#ifndef HG_RISK_STATE_RISK_SNAPSHOT_H
#define HG_RISK_STATE_RISK_SNAPSHOT_H
// =============================================================================
// 风控内存状态 —— 不可变快照 RiskSnapshot（ARCHITECTURE.md §3.4 / §4.4.1 / §4.4.3）
//
// 「不可变快照」语义（§4.4.3）：
//   · 快照承载**配置类**数据（品种 / 白名单 / 限额上限 / 阈值 / 权限 / 敞口 / 规模基数）
//     与「已用量」的**初值**；「已用量」的高频增量由 T4 独占维护（不走快照）。
//   · 构造完成后**内容不可变** —— 所有读取接口均为 `const`，不修改任何状态。
//   · 读取接口**零分配、零锁、零 I/O**（只做哈希查找 / 值拷贝），可安全在交易路径调用。
//
// 每个 RiskSnapshot 内置一个**内部引用/读者计数**（仅由 SnapshotManager 维护），
// 用于 RCU 回收（见 `snapshot_manager.h`）。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <unordered_set>

#include "hg/risk_state/model.h"

namespace hg {
namespace risk_state {

class SnapshotManager;  // RCU 管理者（唯一可操作引用计数者）

class RiskSnapshot {
 public:
  // 空快照（系统未就绪态：全部数据集为空，参数版本 0）
  RiskSnapshot();

  // 参数版本与构建时点（由 rebuild 写入）
  std::uint64_t param_version() const { return param_version_; }
  void set_param_version(std::uint64_t v) { param_version_ = v; }
  std::int64_t build_time_ms() const { return build_time_ms_; }
  void set_build_time_ms(std::int64_t t) { build_time_ms_ = t; }
  // 加载条数（FR-RISK-9 验收 3：预加载结果含加载条数）
  std::size_t record_count() const { return record_count_; }
  void set_record_count(std::size_t n) { record_count_ = n; }
  // 是否为空快照（未就绪）
  bool empty() const { return record_count_ == 0; }

  // ---- 写入接口（仅供 SnapshotManager 在**构建期**调用；发布后不得再改）----
  std::unordered_map<std::uint32_t, VarietyMeta>& varieties() { return varieties_; }
  std::unordered_map<std::uint32_t, InstrumentMeta>& instruments() { return instruments_; }
  std::unordered_set<std::uint64_t>& whitelist() { return whitelist_; }
  std::unordered_map<std::uint64_t, Category>& whitelist_category_override() {
    return whitelist_category_override_;
  }
  std::unordered_map<std::uint64_t, QuotaEntry>& quota_sub_product() { return quota_sub_product_; }
  std::unordered_map<std::uint64_t, QuotaEntry>& quota_sub_instrument() {
    return quota_sub_instrument_;
  }
  std::unordered_map<std::uint32_t, QuotaEntry>& quota_account_product() {
    return quota_account_product_;
  }
  std::unordered_map<std::uint64_t, PositionEntry>& positions() { return positions_; }
  std::unordered_map<std::uint64_t, PositionLimitEntry>& position_limits() {
    return position_limits_;
  }
  std::unordered_map<std::uint64_t, ExposureEntry>& exposures() { return exposures_; }
  std::unordered_map<std::uint64_t, AnnualScaleEntry>& annual_scales() { return annual_scales_; }
  std::unordered_map<std::uint32_t, Category>& sub_account_category() {
    return sub_account_category_;
  }
  std::unordered_map<std::uint64_t, Category>& sub_product_category() {
    return sub_product_category_;
  }
  std::unordered_map<std::uint32_t, PermissionEntry>& permissions() { return permissions_; }
  std::unordered_map<std::string, std::string>& thresholds() { return thresholds_; }
  std::unordered_map<std::uint32_t, QuotaEntry>& total_limit_by_product() {
    return total_limit_by_product_;
  }
  QuotaEntry& account_total_limit() { return account_total_limit_; }
  std::unordered_map<std::uint64_t, PlanEntry>& plans() { return plans_; }

  // ---- 只读访问器（交易路径：零分配、零锁、零 I/O）----
  const std::unordered_map<std::uint32_t, VarietyMeta>& varieties() const { return varieties_; }
  const std::unordered_map<std::uint32_t, InstrumentMeta>& instruments() const {
    return instruments_;
  }
  const std::unordered_map<std::uint64_t, ExposureEntry>& exposures() const { return exposures_; }
  const std::unordered_map<std::uint64_t, PlanEntry>& plans() const { return plans_; }

  // ④⑤⑥ 判定热路径查找（返回指针，未命中 = nullptr；不分配）
  const VarietyMeta* findVariety(std::uint32_t product_id) const;
  const InstrumentMeta* findInstrument(std::uint32_t instrument_id) const;

  // ③ 白名单成员判定（packKey2(sub, product)）；命中返回 true
  bool isWhitelisted(std::uint32_t sub_account_id, std::uint32_t product_id) const;
  // 品种级分类覆盖；未覆盖返回 kUnknown
  Category whitelistCategoryOverride(std::uint32_t sub_account_id,
                                     std::uint32_t product_id) const;

  // ④ 三层额度查找（layer 指定层；未命中返回 nullptr）
  const QuotaEntry* findQuota(QuotaLayer layer, std::uint32_t sub_account_id,
                              std::uint32_t product_id, std::uint32_t instrument_id) const;

  // ⑤ 持仓查找：(sub, instrument, direction, type)
  const PositionEntry* findPosition(std::uint32_t sub_account_id, std::uint32_t instrument_id,
                                    PositionDirection dir, PositionType type) const;

  // ⑥ 持仓上限查找：(sub, product, instrument)
  const PositionLimitEntry* findPositionLimit(std::uint32_t sub_account_id,
                                              std::uint32_t product_id,
                                              std::uint32_t instrument_id) const;

  // ⑦ 敞口查找：(sub, product, direction)
  const ExposureEntry* findExposure(std::uint32_t sub_account_id, std::uint32_t product_id,
                                    ExposureDirection dir) const;

  // ⑧ 年度规模基数查找：(scope, sub, product, year)
  const AnnualScaleEntry* findAnnualScale(ScaleScope scope, std::uint32_t sub_account_id,
                                          std::uint32_t product_id, std::uint32_t year) const;

  // ⑨ 子账户分类（品种级覆盖优先）；无记录返回 kUnknown
  Category effectiveCategory(std::uint32_t sub_account_id, std::uint32_t product_id) const;

  // ⑩ 权限查找
  const PermissionEntry* findPermission(std::uint32_t operator_id) const;
  // 操作人是否可对 (sub, product) 交易（岗位/负责人连带校验）
  bool canTrade(std::uint32_t operator_id, std::uint32_t sub_account_id,
                std::uint32_t product_id) const;

  // ⑫ 总账户限额
  const QuotaEntry& accountTotalLimit() const { return account_total_limit_; }
  const QuotaEntry* findTotalLimitByProduct(std::uint32_t product_id) const;

  // ⑪ 阈值读取（键不存在 ⇒ 返回 fallback）
  bool hasThreshold(const std::string& key) const;
  std::string thresholdRaw(const std::string& key, const std::string& fallback) const;
  Fixed thresholdFixed(const std::string& key, Fixed fallback) const;
  std::int64_t thresholdInt(const std::string& key, std::int64_t fallback) const;
  bool thresholdBool(const std::string& key, bool fallback) const;

  // 统计：各数据集条目数合计（= record_count 的来源）
  std::size_t countRecords() const;

  // 不变量自检（构建校验，§4.4.3「含校验和不变量检查」）：
  //   ① 各层额度 / 总账户限额 / 持仓上限 / 持仓 / 敞口 / 规模基数：非负；
  //   ② 持仓：available_qty ≤ position_qty 且 frozen_qty ≤ position_qty（TB-TRADE-5 CHECK）；
  //   ③ 规模基数红线比例 ∈ [0, 1]（定点 kRatioScale）；
  //   ④ 启用品种的换算系数 > 0（R-PRODUCT-6：换算关系缺失的品种不得启用）。
  // 返回 `RebuildCode`（kRebuildOk 或 2~5 之一）。
  int checkInvariants() const;

  // 深拷贝（增量重建：以旧快照为基底 + 应用 LoadBatch 覆盖）。
  // 返回**新分配**对象（调用方取得所有权）；仅重建线程调用（允许堆分配）。
  RiskSnapshot* clone() const;

 private:

  std::uint64_t param_version_ = 0;
  std::int64_t build_time_ms_ = 0;
  std::size_t record_count_ = 0;

  std::unordered_map<std::uint32_t, VarietyMeta> varieties_;
  std::unordered_map<std::uint32_t, InstrumentMeta> instruments_;
  std::unordered_set<std::uint64_t> whitelist_;
  std::unordered_map<std::uint64_t, Category> whitelist_category_override_;
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

  RiskSnapshot(const RiskSnapshot&) = delete;
  RiskSnapshot& operator=(const RiskSnapshot&) = delete;
};

}  // namespace risk_state
}  // namespace hg

#endif  // HG_RISK_STATE_RISK_SNAPSHOT_H
