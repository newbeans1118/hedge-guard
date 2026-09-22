// HGCP 回环 TCP 承载原语实现。
#include "hg/hgcp/tcp.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstring>

namespace hg {
namespace hgcp {

void close_fd(int fd) {
  if (fd >= 0) {
    ::close(fd);
  }
}

bool set_nonblocking(int fd, bool on) {
  const int flags = ::fcntl(fd, F_GETFL, 0);
  if (flags < 0) {
    return false;
  }
  const int nf = on ? (flags | O_NONBLOCK) : (flags & ~O_NONBLOCK);
  return ::fcntl(fd, F_SETFL, nf) == 0;
}

bool set_tcp_nodelay(int fd) {
  int one = 1;
  return ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one)) == 0;
}

bool set_keepalive(int fd, bool on) {
  int v = on ? 1 : 0;
  return ::setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &v, sizeof(v)) == 0;
}

bool send_all(int fd, const std::uint8_t* data, std::size_t n) {
  std::size_t sent = 0;
  while (sent < n) {
    const ssize_t k = ::send(fd, data + sent, n - sent, MSG_NOSIGNAL);
    if (k > 0) {
      sent += static_cast<std::size_t>(k);
      continue;
    }
    if (k < 0 && errno == EINTR) {
      continue;
    }
    if (k < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
      if (poll_writable(fd, 100) == 1) {
        continue;
      }
      return false;
    }
    return false;
  }
  return true;
}

int recv_some(int fd, std::uint8_t* buf, std::size_t cap) {
  for (;;) {
    const ssize_t k = ::recv(fd, buf, cap, 0);
    if (k > 0) {
      return static_cast<int>(k);
    }
    if (k == 0) {
      return 0;  // 对端关闭
    }
    if (errno == EINTR) {
      continue;
    }
    if (errno == EAGAIN || errno == EWOULDBLOCK) {
      return -2;
    }
    return -1;
  }
}

namespace {

int poll_one(int fd, short events, int timeout_ms) {
  struct pollfd p;
  p.fd = fd;
  p.events = events;
  p.revents = 0;
  int r;
  do {
    r = ::poll(&p, 1, timeout_ms);
  } while (r < 0 && errno == EINTR);
  if (r < 0) {
    return -1;
  }
  if (r == 0) {
    return 0;
  }
  return 1;
}

}  // namespace

int poll_readable(int fd, int timeout_ms) { return poll_one(fd, POLLIN, timeout_ms); }
int poll_writable(int fd, int timeout_ms) { return poll_one(fd, POLLOUT, timeout_ms); }

TcpListener::TcpListener() : fd_(-1), bound_port_(0) {}

TcpListener::~TcpListener() { stop(); }

BindStatus TcpListener::start(const TcpConfig& cfg) {
  stop();
  // 地址白名单（§4.2.1）：非回环 ⇒ 拒绝启动（FR-CONFIG-9）。
  if (!is_loopback_bind(cfg.bind_address)) {
    return BindStatus::kNonLoopbackRefused;
  }
  // 端口语义（与 tcp.h 契约一致）：0 ⇒ 由内核分配（测试用，实际端口见
  // bound_port()）；非 0 ⇒ 走配置级校验（`validate_bind` 视端口 0 为非法，
  // 故此处**仅对非 0 端口**委派，避免把「内核分配」误判为「端口非法」）。
  if (cfg.port != 0u) {
    const BindStatus vs = validate_bind(cfg);
    if (vs != BindStatus::kOk) {
      return vs;
    }
  }

  const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) {
    return BindStatus::kInvalidPort;  // 无可用 fd（等价于无法启动）
  }
  int one = 1;
  ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

  struct sockaddr_in addr;
  std::memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = htons(cfg.port);
  if (::inet_pton(AF_INET, cfg.bind_address.c_str(), &addr.sin_addr) != 1) {
    close_fd(fd);
    return BindStatus::kNonLoopbackRefused;
  }

  if (::bind(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
    close_fd(fd);
    return BindStatus::kInvalidPort;
  }
  if (::listen(fd, 4) != 0) {
    close_fd(fd);
    return BindStatus::kInvalidPort;
  }

  struct sockaddr_in got;
  socklen_t glen = sizeof(got);
  std::uint16_t actual = cfg.port;
  if (::getsockname(fd, reinterpret_cast<struct sockaddr*>(&got), &glen) == 0) {
    actual = ntohs(got.sin_port);
  }
  set_nonblocking(fd, true);

  fd_ = fd;
  bound_port_ = actual;
  return BindStatus::kOk;
}

void TcpListener::stop() {
  close_fd(fd_);
  fd_ = -1;
  bound_port_ = 0;
}

int TcpListener::accept_one(std::string* peer) {
  if (fd_ < 0) {
    return -1;
  }
  struct sockaddr_in cli;
  socklen_t clen = sizeof(cli);
  const int c = ::accept(fd_, reinterpret_cast<struct sockaddr*>(&cli), &clen);
  if (c < 0) {
    return -1;
  }
  set_nonblocking(c, true);
  set_tcp_nodelay(c);      // §4.2.1 `:739`：降低小包延迟
  set_keepalive(c, false); // §4.2.1 `:739`：由应用层心跳替代
  if (peer != 0) {
    char ip[INET_ADDRSTRLEN] = {0};
    ::inet_ntop(AF_INET, &cli.sin_addr, ip, sizeof(ip));
    *peer = std::string(ip) + ":" + std::to_string(static_cast<unsigned>(ntohs(cli.sin_port)));
  }
  return c;
}

}  // namespace hgcp
}  // namespace hg
