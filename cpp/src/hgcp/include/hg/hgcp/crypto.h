#ifndef HG_HGCP_CRYPTO_H
#define HG_HGCP_CRYPTO_H
// =============================================================================
// SHA-256（FIPS 180-4）+ 常量时间比较 —— 供 HGCP 鉴权使用（§4.2.6 `:946`）。
//
// 依赖纪律：`ARCHITECTURE.md §7.2.1` 的 C++ 依赖清单**不含任何密码学库**
// （无 OpenSSL / libsodium）。故 SHA-256 按标准**自实现**（纯函数、零依赖），
// 并以 NIST 已知向量自测（见 tests/crypto_auth_test.cc）。
// =============================================================================

#include <cstddef>
#include <cstdint>

namespace hg {
namespace hgcp {

const std::size_t kSha256DigestSize = 32u;

// 增量式 SHA-256。
class Sha256 {
 public:
  Sha256();
  void update(const std::uint8_t* data, std::size_t len);
  // 收尾并写出 32 字节摘要；调用后本对象不可再 update（除非重新构造）。
  void final(std::uint8_t out[kSha256DigestSize]);

  // 一次性摘要。
  static void digest(const std::uint8_t* data, std::size_t len,
                     std::uint8_t out[kSha256DigestSize]);

 private:
  void process_block(const std::uint8_t block[64]);

  std::uint32_t state_[8];
  std::uint64_t bitlen_;
  std::uint8_t buffer_[64];
  std::size_t buflen_;
};

// 常量时间相等比较（防时序侧信道，§4.2.6 `:946`）：
//   · 无论首个不等字节出现在何处，均遍历全部 len 字节；
//   · 无早退；结果经 volatile 汇聚，防止被优化掉。
bool constant_time_equal(const std::uint8_t* a, const std::uint8_t* b,
                         std::size_t len);

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_CRYPTO_H
