#ifndef HG_HGCP_ENDPOINT_H
#define HG_HGCP_ENDPOINT_H
// =============================================================================
// HGCP 监听端点与单连接约束（ARCHITECTURE.md §4.2.1 `:736`–`:740`）
//
//   · 仅允许 IPv4 回环；绑定地址取值**白名单仅 `127.0.0.1`**；
//     配置为其它值时系统**拒绝启动并告警**（`FR-CONFIG-9`）；
//   · 端口来自配置，**不得硬编码**；未配置时默认 `HG_TCP_PORT=27400`；
//   · 单条连接同时只允许 **1 条**业务连接；重复连接被拒 **`0x0302`**。
//
// 本层为**纯校验/判定**（不建 socket）⇒ 可独立单测。socket 绑定在 tcp.cc 落地。
// =============================================================================

#include <cstdint>
#include <string>

#include "hg/hgcp/protocol.h"

namespace hg {
namespace hgcp {

// 回环白名单（§4.2.1 `:737`：取值白名单仅 127.0.0.1）
extern const char kLoopbackBindAddress[];
// 默认端口 `HG_TCP_PORT`（§4.2.1 `:740`）
const std::uint16_t kDefaultHgTcpPort = 27400u;

// HGCP 组件通道监听配置。
struct TcpConfig {
  std::string bind_address;  // 白名单：仅 "127.0.0.1"
  std::uint16_t port;        // 来自配置；未配置取 kDefaultHgTcpPort
};

TcpConfig default_tcp_config();

enum class BindStatus {
  kOk = 0,
  kNonLoopbackRefused = 1,  // 非回环 ⇒ 拒绝启动（FR-CONFIG-9 验收）
  kInvalidPort = 2
};

const char* to_string(BindStatus s);

// 精确白名单判定：仅 "127.0.0.1" 为 true（"localhost"/"0.0.0.0"/"::1"/"127.0.0.2" 均 false）。
bool is_loopback_bind(const std::string& addr);

// 启动前校验；非 kOk ⇒ 调用方须拒绝启动并告警。
BindStatus validate_bind(const TcpConfig& cfg);

// 通道 A 单连接策略（§4.2.1 `:738`）：已有活跃业务连接时，新连接被拒 0x0302。
enum class ConnectionDecision {
  kAccept = 0,
  kRejectDuplicate = 1
};

class SingleConnectionGuard {
 public:
  SingleConnectionGuard();
  // 新连接到达：无活跃连接 ⇒ kAccept（并占用）；已有活跃 ⇒ kRejectDuplicate（不抢占）。
  ConnectionDecision on_new_connection();
  // 活跃连接关闭，释放名额。
  void on_closed();
  bool active() const { return active_; }

 private:
  bool active_;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_ENDPOINT_H
