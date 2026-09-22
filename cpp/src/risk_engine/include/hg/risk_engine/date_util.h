#ifndef HG_RISK_ENGINE_DATE_UTIL_H
#define HG_RISK_ENGINE_DATE_UTIL_H
// =============================================================================
// 事前风控引擎 —— 日期工具（`R-RISK-6` 期限红线：建仓日 / 合同到期日 / 剩余天数）
//
// 日期口径 = `yyyymmdd` 十进制整数（`DATA_MODEL` 各 `DATE` 列的整数表示），
// 纯整数运算（禁止 double，ARCHITECTURE.md §4.4.2）。
// 公历 ↔ 天数换算采用 Howard Hinnant 的 `days_from_civil` / `civil_from_days`
// （proleptic Gregorian，纯整数，无查表）。
//
// ⚠️ 本目录属 `E4` 扫描根：仅整数算术。
// =============================================================================

#include <cstdint>

namespace hg {
namespace risk_engine {

// yyyymmdd 合法性（年份 1900..9999，月 1..12，日 1..当月天数）
inline bool isValidYmd(std::int64_t ymd) {
  const std::int64_t y = ymd / 10000;
  const std::int64_t m = (ymd / 100) % 100;
  const std::int64_t d = ymd % 100;
  if (y < 1900 || y > 9999) return false;
  if (m < 1 || m > 12) return false;
  static const int kDays[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
  int dim = kDays[m - 1];
  const bool leap = (y % 4 == 0) && ((y % 100 != 0) || (y % 400 == 0));
  if (m == 2 && leap) dim = 29;
  return d >= 1 && d <= dim;
}

inline std::int64_t yearOfYmd(std::int64_t ymd) { return ymd / 10000; }
inline std::int64_t monthOfYmd(std::int64_t ymd) { return (ymd / 100) % 100; }
inline std::int64_t dayOfYmd(std::int64_t ymd) { return ymd % 100; }

// 公历 → 1970-01-01 起的天数
inline std::int64_t daysFromCivil(std::int64_t y, std::int64_t m, std::int64_t d) {
  y -= (m <= 2) ? 1 : 0;
  const std::int64_t era = ((y >= 0) ? y : (y - 399)) / 400;
  const std::int64_t yoe = y - era * 400;                              // [0, 399]
  const std::int64_t doy = (153 * (m + ((m > 2) ? -3 : 9)) + 2) / 5 + d - 1;  // [0, 365]
  const std::int64_t doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;      // [0, 146096]
  return era * 146097 + doe - 719468;
}

// 天数 → 公历（返回 yyyymmdd）
inline std::int64_t civilFromDays(std::int64_t z) {
  z += 719468;
  const std::int64_t era = ((z >= 0) ? z : (z - 146096)) / 146097;
  const std::int64_t doe = z - era * 146097;                                    // [0, 146096]
  const std::int64_t yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;  // [0, 399]
  const std::int64_t y = yoe + era * 400;
  const std::int64_t doy = doe - (365 * yoe + yoe / 4 - yoe / 100);             // [0, 365]
  const std::int64_t mp = (5 * doy + 2) / 153;                                  // [0, 11]
  const std::int64_t d = doy - (153 * mp + 2) / 5 + 1;                          // [1, 31]
  const std::int64_t m = mp + ((mp < 10) ? 3 : -9);                             // [1, 12]
  return (y + ((m <= 2) ? 1 : 0)) * 10000 + m * 100 + d;
}

inline std::int64_t ymdToDays(std::int64_t ymd) {
  return daysFromCivil(yearOfYmd(ymd), monthOfYmd(ymd), dayOfYmd(ymd));
}

inline std::int64_t daysToYmd(std::int64_t days) { return civilFromDays(days); }

// 加/减天数
inline std::int64_t addDays(std::int64_t ymd, std::int64_t days) {
  return daysToYmd(ymdToDays(ymd) + days);
}

// 加/减月数（日超界则截到当月最后一日，如 20260131 + 1 月 = 20260228）
inline std::int64_t addMonths(std::int64_t ymd, std::int64_t months) {
  const std::int64_t y0 = yearOfYmd(ymd);
  const std::int64_t m0 = monthOfYmd(ymd);
  const std::int64_t d0 = dayOfYmd(ymd);
  std::int64_t total = (y0 * 12 + (m0 - 1)) + months;
  std::int64_t y = total / 12;
  std::int64_t m = total % 12;
  if (m < 0) {
    m += 12;
    --y;
  }
  m += 1;
  // 该月天数
  static const int kDays[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
  int dim = kDays[m - 1];
  const bool leap = (y % 4 == 0) && ((y % 100 != 0) || (y % 400 == 0));
  if (m == 2 && leap) dim = 29;
  const std::int64_t d = (d0 < dim) ? d0 : dim;
  return y * 10000 + m * 100 + d;
}

// b - a（天；可为负）
inline std::int64_t daysBetween(std::int64_t a, std::int64_t b) {
  return ymdToDays(b) - ymdToDays(a);
}

inline std::int64_t minYmd(std::int64_t a, std::int64_t b) { return a < b ? a : b; }

}  // namespace risk_engine
}  // namespace hg

#endif  // HG_RISK_ENGINE_DATE_UTIL_H
