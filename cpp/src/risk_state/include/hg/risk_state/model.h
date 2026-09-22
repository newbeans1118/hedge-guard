#ifndef HG_RISK_STATE_MODEL_H
#define HG_RISK_STATE_MODEL_H
// =============================================================================
// 风控内存状态 —— 常驻内存数据模型（ARCHITECTURE.md §4.4.1 / §4.4.2）
//
// 本头文件定义「不可变快照」所承载的全部数据集的**取值类型与键编码**：
//   ① 品种档案 VarietyMeta        ② 合约档案 InstrumentMeta
//   ③ 品种白名单 SubVarietyWhitelist ④ 三层额度 QuotaState
//   ⑤ 持仓 PositionState          ⑥ 持仓上限 PositionLimit
//   ⑦ 实货敞口 ExposureState（含三分量 + snapshot_time） ⑧ 年度规模基数 AnnualScaleFactor
//   ⑨ 子账户分类 SubAccountCategory ⑩ 权限 PermissionSnapshot
//   ⑪ 阈值 Thresholds             ⑫ 总账户限额 TotalLimits
//   ⑬ 计划判定子集 PlanState
//
// 口径来源：
//   · 数据字典  docs/DATA_MODEL.md（v1.3.5）TB-PRODUCT-1/2/3/5/6/8、TB-TRADE-5、
//     TB-SPOT-8、TB-AUTH-7/8、TB-RISK-1/11、TB-PLAN-1、TB-CONFIG-1
//   · 内存模型  docs/ARCHITECTURE.md §4.4.1（数据集清单）/ §4.4.2（数据结构选型）
//
// 定点纪律（§4.4.2）：金额 / 价格 / 数量**禁止 double**，一律定点 int64。
//   数量与额度：scale = 1e4（DATA_MODEL `DECIMAL(20,4)`）—— 见 `kQtyScale`
//   金额与价格：scale = 1e6（ARCH §4.4.2「金额/价格 定点 int64（scale = 1e6）」）
//   比例（红线 0.9/0.8、价格偏离 2%/5%）：scale = 1e6 —— 见 `kRatioScale`
//
// 本文件为**纯数据 + 纯函数**（无 I/O、无锁、无堆分配），任何 TU 可安全包含。
// =============================================================================

#include <cstdint>
#include <string>
#include <unordered_map>
#include <unordered_set>

namespace hg {
namespace risk_state {

// -----------------------------------------------------------------------------
// 定点标度与基础类型（ARCHITECTURE.md §4.4.2）
// -----------------------------------------------------------------------------
typedef std::int64_t Fixed;  // 定点整数：具体标度由上下文（数量 / 金额 / 比例）决定

constexpr std::int64_t kQtyScale = 10000;      // 数量 / 额度：1 单位 = 1e4
constexpr std::int64_t kAmountScale = 1000000;  // 金额 / 价格：1 单位 = 1e6
constexpr std::int64_t kRatioScale = 1000000;   // 比例：1.0 = 1e6

// 数量：把整数单位换算为定点（如 qtyFromWhole(3) = 30000 = 3.0000）
inline Fixed qtyFromWhole(std::int64_t whole) { return whole * kQtyScale; }
// 比例：把万分比分子/分母换算为定点（如 ratioFromPpm(900000) = 0.900000 的定点值）
inline Fixed ratioFromPpm(std::int64_t ppm) { return ppm; }  // ppm 与 kRatioScale 同标度

// -----------------------------------------------------------------------------
// 枚举（取值域对齐 DATA_MODEL 各表 CHECK 列）
// -----------------------------------------------------------------------------
enum class Category : std::uint8_t {
  kUnknown = 0,
  kCommodity = 1,  // 商品类 → 90% 红线
  kTrade = 2       // 贸易类 → 80% 红线
};

enum class ProductStatus : std::uint8_t { kDisabled = 0, kEnabled = 1 };

enum class InstrumentType : std::uint8_t { kFutures = 1, kOption = 2 };

// 敞口方向（DATA_MODEL TB-SPOT-8 `direction`：BUY / SELL）
enum class ExposureDirection : std::uint8_t { kBuy = 1, kSell = 2 };

// 持仓方向（DATA_MODEL TB-TRADE-5 `direction`：LONG / SHORT）
enum class PositionDirection : std::uint8_t { kLong = 1, kShort = 2 };

// 仓型（DATA_MODEL TB-TRADE-5 `position_type`：TODAY / YESTERDAY）
enum class PositionType : std::uint8_t { kToday = 1, kYesterday = 2 };

// 三层限额（ARCH §4.4.1 `QuotaState`，Q-16 已确认三层）
enum class QuotaLayer : std::uint8_t {
  kSubProduct = 1,      // 层①：子账户 + 品种（主维度）
  kSubInstrument = 2,   // 层②：子账户 + 合约（可选层）
  kAccountProduct = 3   // 层③：账户 + 品种（总账户兜底；不参与「最细命中」选择）
};

// 敞口计算口径（DATA_MODEL TB-SPOT-8 `calc_mode`，Q-17 默认组合口径）
enum class CalcMode : std::uint8_t {
  kCombined = 1,
  kContractUnexecuted = 2,
  kInventoryHeld = 3,
  kProductionPlan = 4
};

// 计划状态（ARCH §4.4.1 `PlanState`：生效 / 冻结 / 终止）
enum class PlanStatus : std::uint8_t {
  kDraft = 0,
  kActive = 1,      // 生效
  kFrozen = 2,      // 冻结
  kTerminated = 3   // 终止
};

// 规模基数口径（DATA_MODEL TB-RISK-11 `scope_type`，Q-1 双算取严者）
enum class ScaleScope : std::uint8_t { kSubAccount = 1, kTotalAccount = 2 };

// 子账户数据权限类型（DATA_MODEL TB-AUTH-8 `perm_type`）
enum class PermitType : std::uint8_t { kLimited = 1, kCrossReadonly = 2 };

// 阈值取值类型（DATA_MODEL TB-RISK-1 `value_type`）
enum class ThresholdValueType : std::uint8_t {
  kDecimal = 1,
  kInt = 2,
  kBool = 3,
  kList = 4
};

// 快照构建 / 校验结果码（rebuild 与单测共用）。
// 0 = 通过；1 = 版本非单调（后发不得先至，FR-RISK-10 验收 3）；
// 2~5 = 数据不变量违规（构建校验失败 ⇒ **不得发布**，见 §4.4.3「含校验和不变量检查」）。
enum RebuildCode {
  kRebuildOk = 0,
  kRebuildVersionNotMonotonic = 1,
  kRebuildNegativeValue = 2,
  kRebuildPositionInconsistent = 3,
  kRebuildInvalidRatio = 4,
  kRebuildMissingConvertFactor = 5
};

// -----------------------------------------------------------------------------
// 复合键编码（ARCH §4.4.2「复合键映射 → 紧凑打包 uint64，避免嵌套 map」）
//
// 规模上限（DATA_MODEL §1.3 量级）：子账户 ≤ 10（< 2^16）、品种 ≤ 200（< 2^16）、
// 合约 ≤ 2000（< 2^32）、方向 < 2^4、年度 < 2^16。位宽见各 pack 函数。
// -----------------------------------------------------------------------------
// key2：a 占高 32 位，b 占低 32 位
inline std::uint64_t packKey2(std::uint32_t a, std::uint32_t b) {
  return (static_cast<std::uint64_t>(a) << 32) | static_cast<std::uint64_t>(b);
}
// key3：a 高 16 位、b 中 16 位、c 低 32 位（a/b 须 < 2^16）
inline std::uint64_t packKey3(std::uint32_t a, std::uint32_t b, std::uint32_t c) {
  return (static_cast<std::uint64_t>(a) << 48) |
         (static_cast<std::uint64_t>(b) << 32) | static_cast<std::uint64_t>(c);
}
// 规模基数键：scope(8) | sub(16) | product(16) | year(16)
inline std::uint64_t packAnnualKey(ScaleScope scope, std::uint32_t sub,
                                   std::uint32_t product, std::uint32_t year) {
  return (static_cast<std::uint64_t>(static_cast<std::uint8_t>(scope)) << 56) |
         (static_cast<std::uint64_t>(sub & 0xFFFFu) << 40) |
         (static_cast<std::uint64_t>(product & 0xFFFFu) << 16) |
         static_cast<std::uint64_t>(year & 0xFFFFu);
}

// -----------------------------------------------------------------------------
// ① 品种档案（DATA_MODEL TB-PRODUCT-1 / TB-PRODUCT-2）
// -----------------------------------------------------------------------------
struct VarietyMeta {
  std::uint32_t product_id = 0;
  std::string code;                        // product_code（协议边界用字符串，内存内用 id）
  std::string name;
  Category category = Category::kUnknown;   // COMMODITY / TRADE
  bool category_managed = true;            // category_managed
  ProductStatus status = ProductStatus::kDisabled;
  Fixed convert_factor = 0;                 // t_product_unit.convert_factor（1 期货单位 = x 现货单位）
  std::string convert_desc;                 // 换算关系文字说明
};

// ② 合约档案（DATA_MODEL TB-PRODUCT-3）
struct InstrumentMeta {
  std::uint32_t instrument_id = 0;  // 内部整数 ID（协议边界为 instrument_id 字符串）
  std::uint32_t product_id = 0;
  std::string instrument_code;
  InstrumentType type = InstrumentType::kFutures;  // FUTURES / OPTION
  std::string exchange;                            // SHFE / DCE / CZCE / CFFEX / INE / GFEX
  Fixed contract_multiplier = 0;                   // 每手对应标的数量
  std::int64_t expiry_date = 0;                    // 到期日（期限红线输入）；yyyymmdd
  bool is_option = false;                          // 便捷标记（type==kOption）
};

// ③ 品种白名单（DATA_MODEL TB-PRODUCT-5）—— 成员判定用集合 + 品种级分类覆盖
//    成员集合键 = packKey2(sub_account_id, product_id)

// ④ 三层额度单元（DATA_MODEL TB-PRODUCT-6 层①/层③、TB-PRODUCT-8 层②）
struct QuotaEntry {
  Fixed limit = 0;    // quota_limit（上限）
  Fixed used = 0;     // used_qty（已占用；权威源为内存，T4 独占维护）
  Fixed frozen = 0;   // frozen_qty（已冻结）
  std::string unit;   // LOT / TON
};

// ⑤ 持仓（DATA_MODEL TB-TRADE-5）
struct PositionEntry {
  Fixed position_qty = 0;
  Fixed available_qty = 0;
  Fixed frozen_qty = 0;
  std::int64_t as_of_time_ms = 0;
};

// ⑥ 持仓上限（ARCH §4.4.1 `PositionLimit`：子账户, 品种, 合约 → 上限）
struct PositionLimitEntry {
  Fixed limit = 0;
  std::string unit;
};

// ⑦ 实货敞口（DATA_MODEL TB-SPOT-8；Q-17：快照必须存三类分量）
struct ExposureEntry {
  Fixed contract_unexecuted_qty = 0;  // 分量① 已签合同未执行量
  Fixed inventory_qty = 0;            // 分量② 库存持有量
  Fixed production_plan_qty = 0;      // 分量③ 生产计划需求量
  Fixed exposure_qty = 0;             // 合计（同口径换算后）
  Fixed unit_convert_factor = 0;      // 单位换算系数（敞口与持仓同口径比较）
  std::int64_t snapshot_time_ms = 0;  // 快照时点 snapshot_time（Q-3）
  std::string snapshot_id;            // 快照标识 snapshot_id
  CalcMode calc_mode = CalcMode::kCombined;
  std::int64_t as_of_date = 0;        // 计算基准日期（审计复现口径）；yyyymmdd
  Fixed covered_qty = 0;              // 已被已批准计划引用的数量
};

// ⑧ 年度规模基数（DATA_MODEL TB-RISK-11；Q-1 双算取严者）
struct AnnualScaleEntry {
  Fixed expected_spot_business_qty = 0;  // 分母：年度预计实物经营量
  Fixed accumulated_hedge_qty = 0;       // 分子：年度累计新开仓保值量（实物量）
  Category category = Category::kUnknown;
  Fixed scale_ratio_limit = 0;           // 适用红线比例（0.9 / 0.8，定点 kRatioScale）
  ScaleScope scope = ScaleScope::kSubAccount;
};

// ⑩ 权限（DATA_MODEL TB-AUTH-7 授权 + TB-AUTH-8 子账户数据权限）
struct PermissionEntry {
  // 可交易子账户集合；空集合 + allow_all_sub_accounts=true 表示全账户（哨兵语义对齐
  // 项目级 `sub_account_id=0` 约定：不写死，由下发数据显式承载）。
  std::unordered_set<std::uint32_t> sub_accounts;
  bool allow_all_sub_accounts = false;
  // 授权品种集合；allow_all_products=true 表示不限品种。
  std::unordered_set<std::uint32_t> products;
  bool allow_all_products = false;
  Fixed individual_quota = 0;   // 个人额度
  bool has_trading_post = false;     // 岗位标记：交易岗
  bool has_risk_post = false;        // 岗位标记：风控岗
  // 岗位 / 角色维度补齐（T-126；字段名直采协议名，依据 `ARCHITECTURE.md` §4.2.3 `:818`
  //   `CMD_UPDATE_PERMISSION` 字段列 + §4.2.8（`CH-013` 定版字段级 schema：均为 `bool`·必填）。
  //   消费见 `risk_engine.cc` 的 `evalPermission`：
  //     · `has_settle_role` 与 `has_risk_post` **析取** ⇒ `0x100E`（岗位冲突，前中后台分离）；
  //     · `is_management_role` 与 `is_responsible` **析取** ⇒ `0x100F`（`CH-010` 析取）。
  //   ⚠️ 生产加载点由 `T-038`（`CMD_UPDATE_PERMISSION` 逐字段映射）承接 ⇒ 生产路径下
  //      两字段恒为缺省 `false`；本承载仅为引擎侧消费提供依据，勿表述为「生产已生效」。
  bool has_settle_role = false;      // 岗位标记：结算岗（协议同名直映）
  bool is_management_role = false;   // 角色标记：管理层 / 审批人（协议同名直映）
  bool is_responsible = false;       // 负责人标记（具此标记 → 禁授交易岗，C++ 侧兜底）
  PermitType permit_type = PermitType::kLimited;  // LIMITED / CROSS_READONLY
};

// ⑪ 阈值（DATA_MODEL TB-RISK-1）：**不得硬编码**，以 key → 原始取值承载，
//    并提供带类型的读取器（`RiskSnapshot::thresholdFixed/Int/Bool/List`）。

// ⑫ 总账户限额（ARCH §4.4.1 `TotalLimits`：标量 + 按品种汇总）
//    复用 `QuotaEntry`（limit/used/frozen）。

// ⑬ 计划判定子集（ARCH §4.4.1 `PlanState` / DATA_MODEL TB-PLAN-1）
struct PlanEntry {
  std::uint32_t sub_account_id = 0;
  std::uint32_t product_id = 0;
  PlanStatus status = PlanStatus::kDraft;
  std::int64_t term_start_date = 0;  // 期限起（yyyymmdd）
  std::int64_t term_end_date = 0;    // 期限止（yyyymmdd）
  Fixed plan_qty = 0;                // 计划数量
  Fixed ordered_qty = 0;             // 已下单量
};

// 品种级分类覆盖（DATA_MODEL TB-PRODUCT-5 `category_override`）
//   键 = packKey2(sub_account_id, product_id)

// -----------------------------------------------------------------------------
// 判定辅助（纯函数，供风控引擎与 RSK-04 保守方向自证使用；非完整规则引擎）
// -----------------------------------------------------------------------------
// 是否超限：已用 + 本次请求 > 上限 ⇒ 超限（拒绝）
inline bool exceedsLimit(Fixed limit, Fixed used, Fixed request) {
  return used + request > limit;
}

// 分类 → 名称（日志 / 测试断言可读）
inline const char* categoryName(Category c) {
  switch (c) {
    case Category::kCommodity: return "COMMODITY";
    case Category::kTrade: return "TRADE";
    default: return "UNKNOWN";
  }
}

inline const char* quotaLayerName(QuotaLayer l) {
  switch (l) {
    case QuotaLayer::kSubProduct: return "SUB_PRODUCT";
    case QuotaLayer::kSubInstrument: return "SUB_INSTRUMENT";
    case QuotaLayer::kAccountProduct: return "ACCOUNT_PRODUCT";
    default: return "UNKNOWN";
  }
}

}  // namespace risk_state
}  // namespace hg

#endif  // HG_RISK_STATE_MODEL_H
