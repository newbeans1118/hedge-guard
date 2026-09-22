#ifndef HG_HGCP_TCP_H
#define HG_HGCP_TCP_H
// =============================================================================
// HGCP 回环 TCP 承载原语（ARCHITECTURE.md §4.2.1 `:736`–`:740`）
//
//   · 仅 IPv4 回环；绑定地址白名单仅 `127.0.0.1`（非回环 ⇒ 拒绝启动并告警，FR-CONFIG-9）
//   · 端口来自配置（默认 27400），不硬编码
//   · `TCP_NODELAY` 开启；`SO_KEEPALIVE` 关闭（由应用层心跳替代）
//
// ⚠️ 本文件是 `hgcp` 模块**唯一**直接使用 socket 的地方。`cpp/src/hgcp/**` **不在**
//    `E4`（无锁/无同步 I/O）扫描根内（`E4` 现扫 `cpp/src/{order_engine,risk_engine,risk_state}`
//    三目录并集）⇒ 此处 socket I/O 合法。**严禁**把网络/锁代码写进上述三目录。
//
// ---- 校验层级（勿混用）----
//   · `validate_bind(cfg)`（见 `endpoint.h`）= **配置级**入口校验：拒非回环 / 非法端口，
//     用于**配置**口径（端口 0 在配置里非法）。
//   · `TcpListener::start(cfg)` = **原语层**：允许 `port == 0` 由**内核分配**（测试 / 临时端口），
//     实际端口经 `bound_port()` 回填。二者**故意不同口径**，勿以 `validate_bind` 取代本语义。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>

#include "hg/hgcp/endpoint.h"

namespace hg {
namespace hgcp {

// ---- fd 级工具 ----
void close_fd(int fd);
bool set_nonblocking(int fd, bool on);
bool set_tcp_nodelay(int fd);
bool set_keepalive(int fd, bool on);

// 全量写（短写/EINTR 安全）。返回 false = 对端关闭或错误。
bool send_all(int fd, const std::uint8_t* data, std::size_t n);

// 读一次。返回 >0=字节数；0=对端关闭；-1=错误；-2=EAGAIN（暂无数据）。
int recv_some(int fd, std::uint8_t* buf, std::size_t cap);

// 就绪等待。返回 1=就绪 / 0=超时 / -1=错误。
int poll_readable(int fd, int timeout_ms);
int poll_writable(int fd, int timeout_ms);

// 回环 TCP 监听套接字。
class TcpListener {
 public:
  TcpListener();
  ~TcpListener();

  // 依配置绑定监听。非回环地址 ⇒ 拒绝；非 0 端口走配置校验。
  // **端口 0 = 由内核分配**（原语层允许，见文件头「校验层级」），实际端口见 bound_port()。
  BindStatus start(const TcpConfig& cfg);
  void stop();
  bool listening() const { return fd_ >= 0; }
  int fd() const { return fd_; }
  std::uint16_t bound_port() const { return bound_port_; }

  // 非阻塞接受一个连接；无待连 ⇒ -1（peer 可空）。
  int accept_one(std::string* peer);

 private:
  TcpListener(const TcpListener&);
  TcpListener& operator=(const TcpListener&);

  int fd_;
  std::uint16_t bound_port_;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_TCP_H
