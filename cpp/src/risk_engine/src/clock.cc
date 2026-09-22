// 事前风控引擎 —— 可注入时钟（NFR-TEST-4）
#include "hg/risk_engine/clock.h"

#include <ctime>

namespace hg {
namespace risk_engine {

std::int64_t SystemClock::todayYmd() const {
  const std::time_t t = std::time(nullptr);
  std::tm tmv;
#if defined(_WIN32)
  localtime_s(&tmv, &t);
#else
  localtime_r(&t, &tmv);
#endif
  return static_cast<std::int64_t>(tmv.tm_year + 1900) * 10000 +
         static_cast<std::int64_t>(tmv.tm_mon + 1) * 100 +
         static_cast<std::int64_t>(tmv.tm_mday);
}

}  // namespace risk_engine
}  // namespace hg
