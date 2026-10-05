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
#include <map>
#include <string>

#include "hg/hgcp/crypto.h"

namespace hg {
namespace hgcp {

// 鉴权结果 → 失败码见 §4.3.2：kBadToken → 0x0201 ERR_AUTH_FAILED；
// kMissingToken → 0x0202 ERR_AUTH_TOKEN_MISSING；
// kTokenVersionMismatch → 0x0203 ERR_AUTH_TOKEN_VERSION；
// kReplay → 0x0204 ERR_AUTH_REPLAY。
enum class AuthResult {
  kAccepted = 0,
  kMissingToken = 1,
  kBadToken = 2,
  kTokenVersionMismatch = 3,
  kReplay = 4
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

// 重放判定窗口（§4.3.2 `:1348`「短窗口」）：同一 client_boot_id + 令牌在窗口内
//   重复建连 ⇒ kReplay（0x0204）。⚠️ ARCH 仅述「短窗口」、未规定数值 ⇒ 此处取
//   与心跳间隔同量级的 1000ms（降低「合法重连被误判」概率），可由
//   set_replay_window_ms 覆写；该数值系工程取值、非冻结契约。
const std::int64_t kDefaultAuthReplayWindowMs = 1000;

// HELLO 鉴权门卫：在 `TokenVerifier`（令牌摘要）之上叠加 ARCH §4.3.2 的两条判定：
//   · kTokenVersionMismatch（0x0203）：`token_version` ≠ 期望版本。期望版本随令牌一并
//     手工录入（set_expected_token_version）；**未设置 ⇒ 不做版本判定**（保持 T-171 既有行为）；
//   · kReplay（0x0204）：同一 `client_boot_id` 在窗口内重复建连（令牌须已通过校验）。
// 判定顺序：令牌摘要 → 版本 → 重放（版本/重放判定只在令牌通过后进行，避免坏令牌
//   污染重放台账）。失败经 sink 留痕（成功不留痕，对齐 `verify_audited` 契约）；
//   留痕只承载「结果 / 对端 / 时刻」，**不**记录令牌或其长度/片段（NFR-SEC-1）。
// `client_boot_id` 为空 ⇒ 跳过重放判定（无身份可锚，不作误判；HELLO schema 要求其必填）。
class AuthGuard {
 public:
  AuthGuard();

  // verifier 生命周期由调用方管理（本类不拥有）；null = 鉴权未启用。
  void set_token_verifier(const TokenVerifier* v) { verifier_ = v; }
  const TokenVerifier* token_verifier() const { return verifier_; }
  bool enabled() const { return verifier_ != 0; }

  // 期望令牌版本（§4.2.6 `:948` 令牌轮换；两侧不一致 ⇒ 0x0203）。设置后即启用版本判定。
  void set_expected_token_version(std::uint32_t v);
  bool has_expected_token_version() const { return has_version_; }

  void set_replay_window_ms(std::int64_t ms) { replay_window_ms_ = ms; }
  std::int64_t replay_window_ms() const { return replay_window_ms_; }

  // 综合判定（令牌 → 版本 → 重放）。失败经 sink 留痕（可为 null）；成功不留痕。
  AuthResult verify(const std::string& presented_token, std::uint32_t token_version,
                    const std::string& client_boot_id, AuthAuditSink* sink,
                    const std::string& peer, std::int64_t now_ms);

 private:
  const TokenVerifier* verifier_;
  bool has_version_;
  std::uint32_t expected_version_;
  std::int64_t replay_window_ms_;
  std::map<std::string, std::int64_t> boot_seen_;  // client_boot_id → 最近通过时刻
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_AUTH_H
