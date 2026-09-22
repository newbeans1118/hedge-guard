#ifndef HG_RISK_ENGINE_RISK_RESULT_H
#define HG_RISK_ENGINE_RISK_RESULT_H
// =============================================================================
// 事前风控引擎 —— 判定结果 RiskResult（ARCHITECTURE.md §3.3 / §4.2.4）
//
// `RiskEngine::check(const OrderRequest&)` 的返回：三态（通过 / 告警二次确认 / 拒绝）
//  + 规则编号 + 命中限额层级 + 当前值 / 阈值，并**携带全部命中项** `entries[]`
//  （§4.4.1 全序脚注：`EVT_PRE_TRADE_REJECT.entries[]` 带全部命中项，便于审计复算）。
//
// 设计约束（交易路径纪律，§4.4.2 / §8.1.3）：
//   · **零动态分配**：`entries[]` 为**定长数组**；`detail` 为静态字符串字面量。
//   · 纯值类型：可栈上构造、可拷贝，**无堆**。
//
// ⚠️ 本目录属 `E4` 扫描根：本文件为纯数据 + 纯函数。
// =============================================================================

#include <cstddef>
#include <cstdint>

#include "hg/risk_state/model.h"

namespace hg {
namespace risk_engine {

using hg::risk_state::Fixed;

// 规则编号（与 `ARCHITECTURE.md` §3.3 规则集 / `RISK.md` §3 一一对应）
enum class RuleId : std::uint8_t {
  kNone = 0,
  kWhitelist = 1,       // R-RISK-1
  kQuota = 2,           // R-RISK-2（三层限额）
  kPositionLimit = 3,   // R-RISK-3
  kScaleRedline = 4,    // R-RISK-4
  kNetPosition = 5,     // R-RISK-5
  kTerm = 6,            // R-RISK-6
  kPermission = 7,      // R-RISK-7
  kPriceDeviation = 8,  // R-RISK-8
  kInput = 9            // 入参非法（`ARCHITECTURE.md` §3.3 备注「非法判定值一律拒绝」）
};

const char* ruleIdName(RuleId r);

// 判定三态
enum class RiskDecision : std::uint8_t {
  kPass = 0,    // 全部通过
  kWarn = 1,    // 告警 + 强制二次确认（价格偏离 (2%,5%]、期限剩余 ≤ 30 天）
  kReject = 2   // 拒绝，不发往 CTP
};

// 命中的限额层级（对齐 `§4.2.4` `limit_layer` 枚举；`kNone` = 非限额类）
enum class LimitLayer : std::uint8_t {
  kNone = 0,
  kSubProduct = 1,      // SUB_PRODUCT（层①）
  kSubInstrument = 2,   // SUB_INSTRUMENT（层②）
  kAccountProduct = 3,  // ACCOUNT_PRODUCT（层③）
  kAuthQuota = 4        // AUTH_QUOTA（超授权额度，R-RISK-7）
};

const char* limitLayerName(LimitLayer l);

// 取值的定点标度（供协议边界格式化为十进制字符串）
enum class ValueScale : std::uint8_t {
  kNone = 0,
  kQty = 1,     // 1e4
  kAmount = 2,  // 1e6（价格 / 金额）
  kRatio = 3,   // 1e6（比例）
  kCount = 4,   // 整数（天数 / 数量条数）
  kDate = 5     // yyyymmdd
};

const char* valueScaleName(ValueScale s);

// 组件错误码（`ARCHITECTURE.md` §4.3.2 / §4.2.4；`0` = 无）
const char* riskCodeName(std::uint32_t code);

// 单条命中项（`EVT_PRE_TRADE_REJECT.entries[]` 的元素）
struct RiskEntry {
  RuleId rule = RuleId::kNone;
  std::uint32_t code = 0;              // 0x10xx / 0x3003
  LimitLayer layer = LimitLayer::kNone;  // 非限额类 = kNone
  Fixed current = 0;                    // 当前值（按 current_scale 解读）
  ValueScale current_scale = ValueScale::kNone;
  Fixed threshold = 0;                  // 阈值（按 threshold_scale 解读）
  ValueScale threshold_scale = ValueScale::kNone;
  const char* detail = "";              // 静态字符串（原因），无分配
};

constexpr int kMaxRiskEntries = 32;

struct RiskResult {
  RiskDecision decision = RiskDecision::kPass;
  // 主（上报）规则与错误码：取**规范顺序中首个违规规则**（R-RISK-1 → R-RISK-8）
  RuleId rule = RuleId::kNone;
  std::uint32_t code = 0;
  // 限额类拒绝：命中的「全序最细者」层级
  LimitLayer limit_layer = LimitLayer::kNone;
  bool has_limit_layer = false;
  // R-RISK-8：市价单不适用偏离校验（显式标注）
  bool price_deviation_na = false;
  // R-RISK-5：所用实货敞口快照时点（Q-3 须标注快照时点）
  std::int64_t exposure_snapshot_time_ms = 0;
  bool has_exposure_snapshot_time = false;
  // 全部命中项
  int entry_count = 0;
  RiskEntry entries[kMaxRiskEntries];

  bool passed() const { return decision == RiskDecision::kPass; }
  bool warned() const { return decision == RiskDecision::kWarn; }
  bool rejected() const { return decision == RiskDecision::kReject; }

  // 追加一条命中项（超出容量则静默丢弃 = 定长上限；不分配）
  void addEntry(const RiskEntry& e) {
    if (entry_count < kMaxRiskEntries) {
      entries[entry_count] = e;
      ++entry_count;
    }
  }
};

}  // namespace risk_engine
}  // namespace hg

#endif  // HG_RISK_ENGINE_RISK_RESULT_H
