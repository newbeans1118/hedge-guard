// HGCP 鉴权实现（ARCHITECTURE.md §4.2.6）。
#include "hg/hgcp/auth.h"

#include <cstring>

namespace hg {
namespace hgcp {

AuthAuditSink::~AuthAuditSink() {}

const char* to_string(AuthResult r) {
  switch (r) {
    case AuthResult::kAccepted: return "ACCEPTED";
    case AuthResult::kMissingToken: return "MISSING_TOKEN";
    case AuthResult::kBadToken: return "BAD_TOKEN";
  }
  return "?";
}

std::uint32_t error_code_for(AuthResult r) {
  switch (r) {
    case AuthResult::kAccepted: return 0x0000u;      // OK
    case AuthResult::kMissingToken: return 0x0202u;  // ERR_AUTH_TOKEN_MISSING
    case AuthResult::kBadToken: return 0x0201u;      // ERR_AUTH_FAILED
  }
  return 0x0201u;
}

TokenVerifier::TokenVerifier() : has_token_(false) {
  std::memset(digest_, 0, sizeof(digest_));
}

TokenVerifier TokenVerifier::from_token(const std::string& token) {
  TokenVerifier v;
  v.has_token_ = true;
  Sha256::digest(reinterpret_cast<const std::uint8_t*>(token.data()), token.size(),
                 v.digest_);
  return v;
}

TokenVerifier TokenVerifier::from_digest(const std::uint8_t digest[kSha256DigestSize]) {
  TokenVerifier v;
  v.has_token_ = true;
  std::memcpy(v.digest_, digest, kSha256DigestSize);
  return v;
}

AuthResult TokenVerifier::verify(const std::string& presented_token) const {
  if (presented_token.empty()) {
    return AuthResult::kMissingToken;
  }
  std::uint8_t got[kSha256DigestSize];
  Sha256::digest(reinterpret_cast<const std::uint8_t*>(presented_token.data()),
                 presented_token.size(), got);
  // 常量时间比较（§4.2.6 `:946`）。
  const bool eq = constant_time_equal(got, digest_, kSha256DigestSize);
  // 逐字节清零临时摘要（不驻留派生值）。
  volatile std::uint8_t* p = got;
  for (std::size_t i = 0; i < kSha256DigestSize; ++i) {
    p[i] = 0u;
  }
  if (!has_token_) {
    // 未配置令牌 ⇒ 视为鉴权失败（不得放行）。
    return AuthResult::kBadToken;
  }
  return eq ? AuthResult::kAccepted : AuthResult::kBadToken;
}

AuthResult TokenVerifier::verify_audited(const std::string& presented_token,
                                         AuthAuditSink* sink,
                                         const std::string& peer,
                                         std::int64_t now_ms) const {
  const AuthResult r = verify(presented_token);
  if (r != AuthResult::kAccepted && sink != 0) {
    // 只传「结果 / 对端 / 时刻」——绝不传令牌本身。
    sink->on_auth_result(r, peer, now_ms);
  }
  return r;
}

}  // namespace hgcp
}  // namespace hg
