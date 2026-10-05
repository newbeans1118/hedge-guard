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
    case AuthResult::kTokenVersionMismatch: return "TOKEN_VERSION_MISMATCH";
    case AuthResult::kReplay: return "REPLAY";
  }
  return "?";
}

std::uint32_t error_code_for(AuthResult r) {
  switch (r) {
    case AuthResult::kAccepted: return 0x0000u;            // OK
    case AuthResult::kMissingToken: return 0x0202u;        // ERR_AUTH_TOKEN_MISSING
    case AuthResult::kBadToken: return 0x0201u;            // ERR_AUTH_FAILED
    case AuthResult::kTokenVersionMismatch: return 0x0203u;  // ERR_AUTH_TOKEN_VERSION
    case AuthResult::kReplay: return 0x0204u;              // ERR_AUTH_REPLAY
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

AuthGuard::AuthGuard()
    : verifier_(0),
      has_version_(false),
      expected_version_(0),
      replay_window_ms_(kDefaultAuthReplayWindowMs) {}

void AuthGuard::set_expected_token_version(std::uint32_t v) {
  has_version_ = true;
  expected_version_ = v;
}

AuthResult AuthGuard::verify(const std::string& presented_token,
                             std::uint32_t token_version,
                             const std::string& client_boot_id,
                             AuthAuditSink* sink, const std::string& peer,
                             std::int64_t now_ms) {
  if (verifier_ == 0) {
    return AuthResult::kAccepted;  // 鉴权未启用（向后兼容）
  }
  AuthResult r = verifier_->verify(presented_token);
  // 版本判定（0x0203）：仅令牌通过后判，避免坏令牌触发版本码。
  if (r == AuthResult::kAccepted && has_version_ && token_version != expected_version_) {
    r = AuthResult::kTokenVersionMismatch;
  }
  // 重放判定（0x0204）：同一 client_boot_id 在窗口内重复建连。
  if (r == AuthResult::kAccepted && replay_window_ms_ > 0 && !client_boot_id.empty()) {
    const std::map<std::string, std::int64_t>::iterator it = boot_seen_.find(client_boot_id);
    if (it != boot_seen_.end() && now_ms - it->second < replay_window_ms_) {
      r = AuthResult::kReplay;
    } else {
      boot_seen_[client_boot_id] = now_ms;
    }
  }
  if (r != AuthResult::kAccepted && sink != 0) {
    sink->on_auth_result(r, peer, now_ms);  // 只传结果/对端/时刻
  }
  return r;
}

}  // namespace hgcp
}  // namespace hg
