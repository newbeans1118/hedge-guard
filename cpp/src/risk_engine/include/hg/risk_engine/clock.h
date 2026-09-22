#ifndef HG_RISK_ENGINE_CLOCK_H
#define HG_RISK_ENGINE_CLOCK_H
// =============================================================================
// 事前风控引擎 —— 可注入时钟（NFR-TEST-4「关键依赖可注入」；ARCHITECTURE.md §3.3）
//
// `RiskEngine` 依赖 `IClock` 取「判定时点」与「当日」，**不直接调 `time()`**
// （§4.4.2「时间：`int64` 毫秒 + 可注入时钟接口」，放弃直接调 `time()`）。
//
// ⚠️ 本目录属 `E4` 扫描根：接口与系统实现**仅读时钟**，无锁、无同步 I/O
//    （`SystemClock` 使用 `std::chrono::system_clock`，非 `sleep`）。
// =============================================================================

#include <chrono>
#include <cstdint>

namespace hg {
namespace risk_engine {

class IClock {
 public:
  virtual ~IClock() {}
  // 当前时点（自 Unix 纪元起的毫秒）
  virtual std::int64_t nowMs() const = 0;
  // 当日（yyyymmdd，本地日历）
  virtual std::int64_t todayYmd() const = 0;
};

// 默认时钟（可被测试以假时钟替换）
class SystemClock : public IClock {
 public:
  std::int64_t nowMs() const {
    return static_cast<std::int64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch())
            .count());
  }
  std::int64_t todayYmd() const;
};

}  // namespace risk_engine
}  // namespace hg

#endif  // HG_RISK_ENGINE_CLOCK_H
