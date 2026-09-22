#ifndef HG_CTP_ADAPTER_TEST_UTIL_H
#define HG_CTP_ADAPTER_TEST_UTIL_H
// 极简 C++11 断言 harness（零依赖，不引入 gtest；与 PROJECT.md §5.1 技术栈一致）。
// 每个测试为独立可执行，返回 0=PASS / 1=FAIL，供 ctest 判读。
// 与 cpp/src/risk_state/tests/risk_state_test_util.h 同形：断言表达式**单次求值**
// （避免带副作用的参数被执行两次）。

#include <cstdint>
#include <cstdio>
#include <string>

namespace hg {
namespace ctp_adapter {
namespace testutil {

inline int& failures() {
  static int f = 0;
  return f;
}
inline int& checks() {
  static int c = 0;
  return c;
}

inline void check_impl(bool ok, const char* expr, const char* file, int line) {
  ++checks();
  if (!ok) {
    ++failures();
    std::printf("[FAIL] %s:%d: %s\n", file, line, expr);
  }
}

inline int report(const char* name) {
  const int f = failures();
  std::printf("%s: %d checks, %d failures -> %s\n", name, checks(), f,
              f == 0 ? "PASS" : "FAIL");
  return f == 0 ? 0 : 1;
}

}  // namespace testutil
}  // namespace ctp_adapter
}  // namespace hg

#define HG_CHECK(cond) \
  ::hg::ctp_adapter::testutil::check_impl((cond), #cond, __FILE__, __LINE__)
#define HG_CHECK_EQ(a, b) \
  ::hg::ctp_adapter::testutil::check_impl((a) == (b), #a " == " #b, __FILE__, __LINE__)

#endif  // HG_CTP_ADAPTER_TEST_UTIL_H
