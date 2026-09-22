// 风控内存状态 —— 加载批次 LoadBatch 实现（ARCHITECTURE.md §3.4）
#include "hg/risk_state/load_batch.h"

namespace hg {
namespace risk_state {

void LoadBatch::applyTo(RiskSnapshot& s) const {
  // ① 品种档案
  for (std::unordered_map<std::uint32_t, VarietyMeta>::const_iterator it = varieties_.begin();
       it != varieties_.end(); ++it) {
    s.varieties()[it->first] = it->second;
  }
  // ② 合约档案
  for (std::unordered_map<std::uint32_t, InstrumentMeta>::const_iterator it =
           instruments_.begin();
       it != instruments_.end(); ++it) {
    s.instruments()[it->first] = it->second;
  }
  // ③ 白名单：先移除、后新增（同一批内新增优先）
  for (std::unordered_set<std::uint64_t>::const_iterator it = whitelist_remove_.begin();
       it != whitelist_remove_.end(); ++it) {
    s.whitelist().erase(*it);
    s.whitelist_category_override().erase(*it);
  }
  for (std::unordered_set<std::uint64_t>::const_iterator it = whitelist_.begin();
       it != whitelist_.end(); ++it) {
    s.whitelist().insert(*it);
  }
  for (std::unordered_map<std::uint64_t, Category>::const_iterator it =
           whitelist_category_override_.begin();
       it != whitelist_category_override_.end(); ++it) {
    s.whitelist_category_override()[it->first] = it->second;
  }
  // ④ 三层额度
  for (std::unordered_map<std::uint64_t, QuotaEntry>::const_iterator it =
           quota_sub_product_.begin();
       it != quota_sub_product_.end(); ++it) {
    s.quota_sub_product()[it->first] = it->second;
  }
  for (std::unordered_map<std::uint64_t, QuotaEntry>::const_iterator it =
           quota_sub_instrument_.begin();
       it != quota_sub_instrument_.end(); ++it) {
    s.quota_sub_instrument()[it->first] = it->second;
  }
  for (std::unordered_map<std::uint32_t, QuotaEntry>::const_iterator it =
           quota_account_product_.begin();
       it != quota_account_product_.end(); ++it) {
    s.quota_account_product()[it->first] = it->second;
  }
  // ⑤ 持仓
  for (std::unordered_map<std::uint64_t, PositionEntry>::const_iterator it = positions_.begin();
       it != positions_.end(); ++it) {
    s.positions()[it->first] = it->second;
  }
  // ⑥ 持仓上限
  for (std::unordered_map<std::uint64_t, PositionLimitEntry>::const_iterator it =
           position_limits_.begin();
       it != position_limits_.end(); ++it) {
    s.position_limits()[it->first] = it->second;
  }
  // ⑦ 敞口
  for (std::unordered_map<std::uint64_t, ExposureEntry>::const_iterator it = exposures_.begin();
       it != exposures_.end(); ++it) {
    s.exposures()[it->first] = it->second;
  }
  // ⑧ 年度规模基数
  for (std::unordered_map<std::uint64_t, AnnualScaleEntry>::const_iterator it =
           annual_scales_.begin();
       it != annual_scales_.end(); ++it) {
    s.annual_scales()[it->first] = it->second;
  }
  // ⑨ 分类
  for (std::unordered_map<std::uint32_t, Category>::const_iterator it =
           sub_account_category_.begin();
       it != sub_account_category_.end(); ++it) {
    s.sub_account_category()[it->first] = it->second;
  }
  for (std::unordered_map<std::uint64_t, Category>::const_iterator it =
           sub_product_category_.begin();
       it != sub_product_category_.end(); ++it) {
    s.sub_product_category()[it->first] = it->second;
  }
  // ⑩ 权限
  for (std::unordered_map<std::uint32_t, PermissionEntry>::const_iterator it =
           permissions_.begin();
       it != permissions_.end(); ++it) {
    s.permissions()[it->first] = it->second;
  }
  // ⑪ 阈值
  for (std::unordered_map<std::string, std::string>::const_iterator it = thresholds_.begin();
       it != thresholds_.end(); ++it) {
    s.thresholds()[it->first] = it->second;
  }
  // ⑫ 总账户限额
  for (std::unordered_map<std::uint32_t, QuotaEntry>::const_iterator it =
           total_limit_by_product_.begin();
       it != total_limit_by_product_.end(); ++it) {
    s.total_limit_by_product()[it->first] = it->second;
  }
  s.account_total_limit() = account_total_limit_;
  // ⑬ 计划
  for (std::unordered_map<std::uint64_t, PlanEntry>::const_iterator it = plans_.begin();
       it != plans_.end(); ++it) {
    s.plans()[it->first] = it->second;
  }
}

std::size_t LoadBatch::itemCount() const {
  std::size_t n = 0;
  n += varieties_.size();
  n += instruments_.size();
  n += whitelist_.size();
  n += whitelist_remove_.size();
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

}  // namespace risk_state
}  // namespace hg
