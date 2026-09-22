#ifndef HG_HGCP_AUTH_H
#define HG_HGCP_AUTH_H
// =============================================================================
// HGCP 鉴权（ARCHITECTURE.md §4.2.6 `:944`–`:949`）
//
//   · C++ 侧持有 `auth_token` 的 **SHA-256 摘要**，与 `HELLO.auth_token` 的摘要
//     做**常量时间比较**（防时序侧信道）；
//   · 失败 → `MSG_AUTH_FAIL` + **立即关闭连接** + **告警 + 审计留痕**（`FR-CONFIG-9` 验收4）；
//   · **禁止**在日志中打印 `auth_token`（含长度与部分片段）—— 见 NFR-SEC-1 / §4.2.6 `:949`。
//
// 本类是**纯决策 + 留痕**层，不依赖 socket / 帧 / 消息 schema ⇒ 可独立单测。
// 实际的 `MSG_AUTH_FAIL` 帧（含 payload）属 T-032 缺口（字段级 schema 未定），见回执。
// =============================================================================

#include <cstdint>
#include <string>

#include "hg/hgcp/crypto.h"

namespace hg {
namespace hgcp {

// 鉴权结果 → 失败码见 §4.3.2：kBadToken → 0x0201 ERR_AUTH_FAILED；
// kMissingToken → 0x0202 ERR_AUTH_TOKEN_MISSING。
enum class AuthResult {
  kAccepted = 0,
  kMissingToken = 1,
  kBadToken = 2
};

const char* to_string(AuthResult r);
std::uint32_t error_code_for(AuthResult r);

// 审计留痕接口（§4.2.6 `:947`）。实现方**不得**记录令牌或其长度/片段。
class AuthAuditSink {
 public:
  virtual ~AuthAuditSink();
  virtual void on_auth_result(AuthResult result, const std::string& peer,
                              std::int64_t now_ms) = 0;
};

// 令牌摘要校验器。**只持有摘要**，不持有明文令牌。
class TokenVerifier {
 public:
  TokenVerifier();
  // 由启动期手工录入的令牌派生摘要（明文随即丢弃；见 §4.2.6 `:945`「仅内存持有」）。
  static TokenVerifier from_token(const std::string& token);
  static TokenVerifier from_digest(const std::uint8_t digest[kSha256DigestSize]);

  bool has_token() const { return has_token_; }
  const std::uint8_t* digest() const { return digest_; }

  // 常量时间比较。空串 ⇒ kMissingToken；摘要不符 ⇒ kBadToken。
  AuthResult verify(const std::string& presented_token) const;

  // 校验并按失败结果留痕（成功不落痕）。sink 可为 null。
  AuthResult verify_audited(const std::string& presented_token,
                            AuthAuditSink* sink, const std::string& peer,
                            std::int64_t now_ms) const;

 private:
  bool has_token_;
  std::uint8_t digest_[kSha256DigestSize];
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_AUTH_H
