// HGCP 监听端点与单连接约束实现（ARCHITECTURE.md §4.2.1）。
#include "hg/hgcp/endpoint.h"

#include <cstring>

namespace hg {
namespace hgcp {

const char kLoopbackBindAddress[] = "127.0.0.1";

TcpConfig default_tcp_config() {
  TcpConfig c;
  c.bind_address = kLoopbackBindAddress;
  c.port = kDefaultHgTcpPort;
  return c;
}

const char* to_string(BindStatus s) {
  switch (s) {
    case BindStatus::kOk: return "OK";
    case BindStatus::kNonLoopbackRefused: return "NON_LOOPBACK_REFUSED";
    case BindStatus::kInvalidPort: return "INVALID_PORT";
  }
  return "?";
}

bool is_loopback_bind(const std::string& addr) {
  // 精确白名单：仅 "127.0.0.1"（§4.2.1 `:737`）。禁 0.0.0.0 / ::1 / localhost / 其它 127.x。
  return addr == kLoopbackBindAddress;
}

BindStatus validate_bind(const TcpConfig& cfg) {
  if (!is_loopback_bind(cfg.bind_address)) {
    return BindStatus::kNonLoopbackRefused;
  }
  if (cfg.port == 0u) {
    return BindStatus::kInvalidPort;
  }
  return BindStatus::kOk;
}

SingleConnectionGuard::SingleConnectionGuard() : active_(false) {}

ConnectionDecision SingleConnectionGuard::on_new_connection() {
  if (active_) {
    return ConnectionDecision::kRejectDuplicate;
  }
  active_ = true;
  return ConnectionDecision::kAccept;
}

void SingleConnectionGuard::on_closed() { active_ = false; }

}  // namespace hgcp
}  // namespace hg
