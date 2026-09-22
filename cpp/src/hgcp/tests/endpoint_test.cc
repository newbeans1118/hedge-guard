// T-032 回环绑定与单连接约束单测（ARCHITECTURE.md §4.2.1 / FR-CONFIG-9）。
#include "hg/hgcp/endpoint.h"

#include <cstdint>
#include <string>

#include "hgcp_test_util.h"

namespace {

using hg::hgcp::BindStatus;
using hg::hgcp::ConnectionDecision;
using hg::hgcp::SingleConnectionGuard;
using hg::hgcp::TcpConfig;

void test_loopback_whitelist() {
  HG_CHECK(hg::hgcp::is_loopback_bind("127.0.0.1"));
  // 非回环 / 别名 / 回环变体 一律拒绝（白名单仅 127.0.0.1）
  HG_CHECK(!hg::hgcp::is_loopback_bind("0.0.0.0"));
  HG_CHECK(!hg::hgcp::is_loopback_bind("localhost"));
  HG_CHECK(!hg::hgcp::is_loopback_bind("::1"));
  HG_CHECK(!hg::hgcp::is_loopback_bind("127.0.0.2"));
  HG_CHECK(!hg::hgcp::is_loopback_bind("10.0.0.5"));
  HG_CHECK(!hg::hgcp::is_loopback_bind(""));
}

void test_validate_bind() {
  TcpConfig ok = hg::hgcp::default_tcp_config();
  HG_CHECK_EQ(ok.bind_address, std::string("127.0.0.1"));
  HG_CHECK_EQ(ok.port, static_cast<std::uint16_t>(27400));
  HG_CHECK(hg::hgcp::validate_bind(ok) == BindStatus::kOk);

  TcpConfig bad = ok;
  bad.bind_address = "0.0.0.0";
  HG_CHECK(hg::hgcp::validate_bind(bad) == BindStatus::kNonLoopbackRefused);

  TcpConfig port0 = ok;
  port0.port = 0;
  HG_CHECK(hg::hgcp::validate_bind(port0) == BindStatus::kInvalidPort);

  // 端口来自配置（非硬编码）：可改
  TcpConfig custom = ok;
  custom.port = 29999;
  HG_CHECK(hg::hgcp::validate_bind(custom) == BindStatus::kOk);
}

void test_single_connection() {
  SingleConnectionGuard g;
  HG_CHECK(!g.active());
  HG_CHECK(g.on_new_connection() == ConnectionDecision::kAccept);   // 首连
  HG_CHECK(g.active());
  HG_CHECK(g.on_new_connection() == ConnectionDecision::kRejectDuplicate);  // 重复 ⇒ 0x0302
  HG_CHECK(g.on_new_connection() == ConnectionDecision::kRejectDuplicate);
  g.on_closed();
  HG_CHECK(!g.active());
  HG_CHECK(g.on_new_connection() == ConnectionDecision::kAccept);   // 释放后可再连
}

void test_dup_connection_error_code() {
  // 重复连接错误码 = 0x0302（§4.3.2）
  HG_CHECK_EQ(static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kErrDuplicateConnection),
              0x0302u);
}

}  // namespace

int main() {
  test_loopback_whitelist();
  test_validate_bind();
  test_single_connection();
  test_dup_connection_error_code();
  return hg::hgcp::testutil::report("endpoint_test");
}
