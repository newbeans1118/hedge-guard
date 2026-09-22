#ifndef HG_HGCP_TEST_UTIL_H
#define HG_HGCP_TEST_UTIL_H
// 极简 C++11 断言harness（零依赖；与 PROJECT.md §5.1 技术栈一致，不引入 gtest）。
// 每个测试为一个独立可执行，返回 0=PASS / 1=FAIL，供 ctest 判读。

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>

namespace hg {
namespace hgcp {
namespace testutil {

inline int& failures() { static int f = 0; return f; }
inline int& checks() { static int c = 0; return c; }

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

inline std::string to_hex(const std::uint8_t* p, std::size_t n) {
  static const char* d = "0123456789abcdef";
  std::string s;
  for (std::size_t i = 0; i < n; ++i) {
    s.push_back(d[(p[i] >> 4) & 0x0Fu]);
    s.push_back(d[p[i] & 0x0Fu]);
  }
  return s;
}

}  // namespace testutil
}  // namespace hgcp
}  // namespace hg

#define HG_CHECK(cond) \
  ::hg::hgcp::testutil::check_impl((cond), #cond, __FILE__, __LINE__)
#define HG_CHECK_EQ(a, b) \
  ::hg::hgcp::testutil::check_impl((a) == (b), #a " == " #b, __FILE__, __LINE__)

#endif  // HG_HGCP_TEST_UTIL_H
