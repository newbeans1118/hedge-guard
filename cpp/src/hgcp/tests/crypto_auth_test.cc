// T-032 鉴权层单测：SHA-256(NIST 向量) + 常量时间比较 + TokenVerifier + 审计留痕。
#include "hg/hgcp/auth.h"
#include "hg/hgcp/crypto.h"

#include <cstdint>
#include <string>
#include <vector>

#include "hgcp_test_util.h"

namespace {

using hg::hgcp::AuthGuard;
using hg::hgcp::AuthResult;
using hg::hgcp::TokenVerifier;

std::string sha256_hex(const std::string& s) {
  std::uint8_t d[hg::hgcp::kSha256DigestSize];
  hg::hgcp::Sha256::digest(reinterpret_cast<const std::uint8_t*>(s.data()), s.size(), d);
  return hg::hgcp::testutil::to_hex(d, hg::hgcp::kSha256DigestSize);
}

void test_sha256_nist_vectors() {
  // NIST FIPS 180-4 示例
  HG_CHECK_EQ(sha256_hex(""),
              std::string("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
  HG_CHECK_EQ(sha256_hex("abc"),
              std::string("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
  const std::string v3 = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
  HG_CHECK_EQ(sha256_hex(v3),
              std::string("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"));
}

void test_sha256_incremental_equals_oneshot() {
  const std::string a = "the quick brown ";
  const std::string b = "fox jumps over the lazy dog";
  std::uint8_t got[hg::hgcp::kSha256DigestSize];
  hg::hgcp::Sha256 h;
  h.update(reinterpret_cast<const std::uint8_t*>(a.data()), a.size());
  h.update(reinterpret_cast<const std::uint8_t*>(b.data()), b.size());
  h.final(got);
  HG_CHECK_EQ(hg::hgcp::testutil::to_hex(got, hg::hgcp::kSha256DigestSize),
              sha256_hex(a + b));
}

void test_constant_time_equal() {
  const std::uint8_t a[4] = {1, 2, 3, 4};
  const std::uint8_t b[4] = {1, 2, 3, 4};
  const std::uint8_t c[4] = {9, 2, 3, 4};  // 首字节不同
  const std::uint8_t d[4] = {1, 2, 3, 9};  // 末字节不同
  HG_CHECK(hg::hgcp::constant_time_equal(a, b, 4));
  HG_CHECK(!hg::hgcp::constant_time_equal(a, c, 4));
  HG_CHECK(!hg::hgcp::constant_time_equal(a, d, 4));
}

// 记录审计事件的测试桩
class RecordingSink : public hg::hgcp::AuthAuditSink {
 public:
  int count;
  AuthResult last_result;
  std::string last_peer;
  std::int64_t last_ts;
  RecordingSink() : count(0), last_result(AuthResult::kAccepted), last_ts(0) {}
  virtual void on_auth_result(AuthResult r, const std::string& peer,
                              std::int64_t now_ms) {
    ++count;
    last_result = r;
    last_peer = peer;
    last_ts = now_ms;
  }
};

void test_token_verifier() {
  const std::string token = "s3cr3t-token-value";
  TokenVerifier v = TokenVerifier::from_token(token);
  HG_CHECK(v.has_token());
  HG_CHECK(v.verify(token) == AuthResult::kAccepted);           // 正确令牌
  HG_CHECK(v.verify("wrong-token") == AuthResult::kBadToken);   // 错误令牌
  HG_CHECK(v.verify("") == AuthResult::kMissingToken);          // 缺失令牌
  // 空令牌的 verifier 不得放行
  TokenVerifier none;
  HG_CHECK(!none.has_token());
  HG_CHECK(none.verify(token) == AuthResult::kBadToken);
  // 摘要与「令牌原始字节」不同（只持摘要）
  const std::uint8_t* dg = v.digest();
  HG_CHECK_EQ(hg::hgcp::testutil::to_hex(dg, hg::hgcp::kSha256DigestSize),
              sha256_hex(token));
  HG_CHECK(dg[0] != static_cast<std::uint8_t>(token[0]) || token.size() != 32u);
}

void test_audit_on_failure_only() {
  const std::string token = "audit-me-please";
  TokenVerifier v = TokenVerifier::from_token(token);
  RecordingSink sink;
  // 失败 ⇒ 留痕
  HG_CHECK(v.verify_audited("bad", &sink, "127.0.0.1:5000", 1111) ==
           AuthResult::kBadToken);
  HG_CHECK_EQ(sink.count, 1);
  HG_CHECK(sink.last_result == AuthResult::kBadToken);
  HG_CHECK_EQ(sink.last_peer, std::string("127.0.0.1:5000"));
  HG_CHECK_EQ(sink.last_ts, static_cast<std::int64_t>(1111));
  // 凭证不出现在留痕字段中
  HG_CHECK(sink.last_peer.find(token) == std::string::npos);
  // 缺失 ⇒ 留痕（0x0202）
  HG_CHECK(v.verify_audited("", &sink, "127.0.0.1:5001", 2222) ==
           AuthResult::kMissingToken);
  HG_CHECK_EQ(sink.count, 2);
  HG_CHECK(hg::hgcp::error_code_for(AuthResult::kMissingToken) == 0x0202u);
  // 成功 ⇒ 不留痕
  HG_CHECK(v.verify_audited(token, &sink, "127.0.0.1:5002", 3333) ==
           AuthResult::kAccepted);
  HG_CHECK_EQ(sink.count, 2);
}

void test_auth_error_codes() {
  HG_CHECK_EQ(hg::hgcp::error_code_for(AuthResult::kBadToken), 0x0201u);
  HG_CHECK_EQ(hg::hgcp::error_code_for(AuthResult::kAccepted), 0x0000u);
  HG_CHECK_EQ(std::string(hg::hgcp::to_string(AuthResult::kMissingToken)),
              std::string("MISSING_TOKEN"));
  // T-175：0x0203 / 0x0204 登记（§4.3.2 `:1347`/`:1348`）
  HG_CHECK_EQ(hg::hgcp::error_code_for(AuthResult::kTokenVersionMismatch), 0x0203u);
  HG_CHECK_EQ(hg::hgcp::error_code_for(AuthResult::kReplay), 0x0204u);
  HG_CHECK_EQ(std::string(hg::hgcp::to_string(AuthResult::kTokenVersionMismatch)),
              std::string("TOKEN_VERSION_MISMATCH"));
  HG_CHECK_EQ(std::string(hg::hgcp::to_string(AuthResult::kReplay)),
              std::string("REPLAY"));
}

// T-175 / TC-M2-10 & TC-M2-11：AuthGuard 版本判定（0x0203）与重放判定（0x0204）。
void test_auth_guard_version_and_replay() {
  const std::string token = "guard-token";
  TokenVerifier v = TokenVerifier::from_token(token);
  RecordingSink sink;
  AuthGuard g;
  g.set_token_verifier(&v);
  g.set_expected_token_version(3u);
  g.set_replay_window_ms(1000);

  // 版本不符 ⇒ 0x0203（TC-M2-10）；失败留痕、boot_id 不入台账
  HG_CHECK(g.verify(token, 2u, "boot-a", &sink, "peer", 1000) ==
           AuthResult::kTokenVersionMismatch);
  HG_CHECK_EQ(sink.count, 1);
  HG_CHECK(sink.last_result == AuthResult::kTokenVersionMismatch);

  // 正常（版本相符 + 新 boot_id）⇒ 通过；成功不留痕
  HG_CHECK(g.verify(token, 3u, "boot-a", &sink, "peer", 1000) ==
           AuthResult::kAccepted);
  HG_CHECK_EQ(sink.count, 1);

  // 同一 boot_id 窗口内重复（1500 - 1000 = 500 < 1000）⇒ 0x0204（TC-M2-11）
  HG_CHECK(g.verify(token, 3u, "boot-a", &sink, "peer", 1500) ==
           AuthResult::kReplay);
  HG_CHECK_EQ(sink.count, 2);
  HG_CHECK(sink.last_result == AuthResult::kReplay);

  // 超窗口后同一 boot_id（3000 - 1000 = 2000 > 1000）⇒ 通过
  HG_CHECK(g.verify(token, 3u, "boot-a", &sink, "peer", 3000) ==
           AuthResult::kAccepted);
  HG_CHECK_EQ(sink.count, 2);

  // 坏令牌优先于版本判定（不得因版本相符而放行）
  HG_CHECK(g.verify("bad", 3u, "boot-b", &sink, "peer", 1000) ==
           AuthResult::kBadToken);
  HG_CHECK_EQ(sink.count, 3);

  // 未设置期望版本 ⇒ 不做版本判定（向后兼容）
  AuthGuard g2;
  g2.set_token_verifier(&v);
  HG_CHECK(g2.verify(token, 99u, "boot-c", &sink, "peer", 1000) ==
           AuthResult::kAccepted);

  // verifier 为 null ⇒ 鉴权未启用（直接放行，不落痕）
  AuthGuard g3;
  HG_CHECK(g3.verify("", 0u, "", &sink, "peer", 1000) == AuthResult::kAccepted);
  HG_CHECK_EQ(sink.count, 3);
}

}  // namespace

int main() {
  test_sha256_nist_vectors();
  test_sha256_incremental_equals_oneshot();
  test_constant_time_equal();
  test_token_verifier();
  test_audit_on_failure_only();
  test_auth_error_codes();
  test_auth_guard_version_and_replay();
  return hg::hgcp::testutil::report("crypto_auth_test");
}
