#ifndef HG_RISK_ENGINE_FIXED_MATH_H
#define HG_RISK_ENGINE_FIXED_MATH_H
// =============================================================================
// 事前风控引擎 —— 定点运算（ARCHITECTURE.md §4.4.2「金额 / 价格 / 数量禁止 double」）
//
// 全部为**纯函数**（无 I/O、无锁、无分配）。乘除做**分块 64×64→128** 与
// **128÷64** 运算，避免 int64 溢出（额度 × 比例、数量 × 乘数 × 换算系数等场景）。
//
// 口径（`hg/risk_state/model.h`）：
//   · 数量 / 额度：scale = kQtyScale = 1e4
//   · 金额 / 价格：scale = kAmountScale = 1e6
//   · 比例：scale = kRatioScale = 1e6（1.0 == 1000000）
//
// ⚠️ 本目录属 `E4` 扫描根：仅算术与整数比较。
// =============================================================================

#include <cstdint>

namespace hg {
namespace risk_engine {

// -----------------------------------------------------------------------------
// 无符号 64×64 → 128（分块，避免依赖编译器扩展）
// -----------------------------------------------------------------------------
inline void mulU64ToU128(std::uint64_t a, std::uint64_t b, std::uint64_t* hi,
                         std::uint64_t* lo) {
  const std::uint64_t a_lo = a & 0xFFFFFFFFull;
  const std::uint64_t a_hi = a >> 32;
  const std::uint64_t b_lo = b & 0xFFFFFFFFull;
  const std::uint64_t b_hi = b >> 32;

  std::uint64_t t = a_lo * b_lo;
  const std::uint64_t w0 = t & 0xFFFFFFFFull;
  std::uint64_t k = t >> 32;

  t = a_hi * b_lo + k;
  const std::uint64_t w1 = t & 0xFFFFFFFFull;
  std::uint64_t w2 = t >> 32;

  t = a_lo * b_hi + w1;
  k = t >> 32;

  *lo = (t << 32) + w0;
  *hi = a_hi * b_hi + w2 + k;
}

// 128 ÷ 64：**前置条件** `d > 0` 且 `hi < d`（保证商可容于 64 位）。
// 返回商；余数写入 *rem（可为 nullptr）。逐位长除法，无溢出。
inline std::uint64_t divU128ByU64(std::uint64_t hi, std::uint64_t lo, std::uint64_t d,
                                  std::uint64_t* rem) {
  if (d == 0) {
    if (rem != nullptr) *rem = 0;
    return 0;  // 调用方保证 d > 0；此处仅防除零
  }
  std::uint64_t q = 0;
  std::uint64_t r_hi = 0;
  std::uint64_t r_lo = 0;
  for (int i = 127; i >= 0; --i) {
    const std::uint64_t bit =
        (i >= 64) ? ((hi >> (i - 64)) & 1ull) : ((lo >> i) & 1ull);
    // (r_hi, r_lo) = (r_hi, r_lo) * 2 + bit
    r_hi = (r_hi << 1) | (r_lo >> 63);
    r_lo = (r_lo << 1) | bit;
    // 与 (0, d) 比较：r ≥ d ?
    if (r_hi != 0 || r_lo >= d) {
      // r -= d（借位由 r_hi 承接；因 r < 2d 且 d < 2^64，r_hi ∈ {0,1}）
      const std::uint64_t borrow = (r_lo < d) ? 1ull : 0ull;
      r_lo = r_lo - d;
      r_hi = r_hi - borrow;
      q = (q << 1) | 1ull;
    } else {
      q = (q << 1);
    }
  }
  if (rem != nullptr) *rem = r_lo;
  return q;
}

// (a * b) / d 向下取整（a、b、d 非负，d > 0，商可容于 64 位）。
inline std::uint64_t mulDivFloorU64(std::uint64_t a, std::uint64_t b, std::uint64_t d) {
  std::uint64_t hi = 0;
  std::uint64_t lo = 0;
  mulU64ToU128(a, b, &hi, &lo);
  return divU128ByU64(hi, lo, d, nullptr);
}

// 有符号定点乘除：(a * b) / d 向下取整（a、b 可为负；d > 0）。
inline std::int64_t mulDivFloor(std::int64_t a, std::int64_t b, std::int64_t d) {
  if (d <= 0) return 0;  // 调用方保证；防除零
  const bool neg = (a < 0) != (b < 0);
  const std::uint64_t ua = (a < 0) ? (static_cast<std::uint64_t>(-(a + 1)) + 1ull)
                                   : static_cast<std::uint64_t>(a);
  const std::uint64_t ub = (b < 0) ? (static_cast<std::uint64_t>(-(b + 1)) + 1ull)
                                   : static_cast<std::uint64_t>(b);
  const std::uint64_t q = mulDivFloorU64(ua, ub, static_cast<std::uint64_t>(d));
  return neg ? -static_cast<std::int64_t>(q) : static_cast<std::int64_t>(q);
}

// 比较 a*b 与 c*d（四个因子均非负）：-1 / 0 / 1。溢出安全。
inline int cmpMulU64(std::uint64_t a, std::uint64_t b, std::uint64_t c, std::uint64_t d) {
  std::uint64_t h1 = 0, l1 = 0, h2 = 0, l2 = 0;
  mulU64ToU128(a, b, &h1, &l1);
  mulU64ToU128(c, d, &h2, &l2);
  if (h1 != h2) return h1 < h2 ? -1 : 1;
  if (l1 != l2) return l1 < l2 ? -1 : 1;
  return 0;
}

// 占用率（定点 kRatioScale）：(used + frozen) / limit（limit > 0）。
inline std::int64_t occupancyRatio(std::int64_t used, std::int64_t frozen, std::int64_t limit) {
  if (limit <= 0) return 0;
  const std::int64_t used_total = used + frozen;
  if (used_total <= 0) return 0;
  return mulDivFloor(used_total, 1000000, limit);
}

// 比例比较：value / base > ratio（ratio 为 kRatioScale 定点）
//   ⇔ value * kRatioScale > ratio * base；非负输入下溢出安全。
inline bool ratioExceeds(std::int64_t value, std::int64_t base, std::int64_t ratio) {
  if (base <= 0) return value > 0;  // 分母非正：任一正分子视为越限（保守）
  if (value <= 0) return false;
  return cmpMulU64(static_cast<std::uint64_t>(value), 1000000ull,
                   static_cast<std::uint64_t>(ratio), static_cast<std::uint64_t>(base)) > 0;
}

// 比例比较：value / base ≥ ratio（「触及红线即阻断」，如规模红线 90%/80%）。
inline bool ratioReaches(std::int64_t value, std::int64_t base, std::int64_t ratio) {
  if (base <= 0) return value > 0;
  if (value <= 0) return ratio <= 0;
  return cmpMulU64(static_cast<std::uint64_t>(value), 1000000ull,
                   static_cast<std::uint64_t>(ratio), static_cast<std::uint64_t>(base)) >= 0;
}

}  // namespace risk_engine
}  // namespace hg

#endif  // HG_RISK_ENGINE_FIXED_MATH_H
