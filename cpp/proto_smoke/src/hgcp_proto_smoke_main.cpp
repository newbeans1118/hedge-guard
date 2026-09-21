// ============================================================================
// T-026 / RSK-01 探路程序：protobuf + HGCP 空消息在 C++11 下的构通与收发验证
//
// 验收标准对应：
//   ① 在 -std=c++11 -Wall -Wextra -Werror 下编译 + 收发成功  → 本程序
//   ③ 记录实际可用版本与编译参数                             → --print-config
//   ④ 两侧 protobuf 大版本一致（3.x）                        → CMake 构建期校验
//                                                              + 本程序运行期打印
//
// 本程序只做探路取证，**不实现** HGCP 帧协议语义；帧头仅为"能收发"的最小载体。
// ============================================================================

// 硬性证明：本翻译单元**确实**以 -std=c++11 编译。
// （CMAKE_CXX_STANDARD 11 只是"请求"，__cplusplus 才是事实。）
#if !defined(__cplusplus) || (__cplusplus != 201103L)
#error "hgcp_proto_smoke 必须在 -std=c++11（__cplusplus == 201103L）下编译；"
#endif

#include "hgcp_proto_smoke_build_info.h"
#include "hgcp_smoke.pb.h"

#include <google/protobuf/stubs/common.h>

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#include <thread>
#include <vector>

namespace {

// §4.2.2 的同步字；此处仅借用 4 字节常量，不实现完整帧格式。
const char kMagic[4] = {'H', 'G', 'C', 'P'};

// §4.2.2：最大帧 1 MiB，超限即拒。
const std::size_t kMaxFrame = 1024u * 1024u;

const char* kBindAddress = "127.0.0.1";  // 红线：仅回环，禁止 0.0.0.0

// ---------------------------------------------------------------------------
// 全量读写小工具（短写 / EINTR 安全）
// ---------------------------------------------------------------------------

bool write_all(int fd, const void* data, std::size_t len) {
  const char* base = static_cast<const char*>(data);
  std::size_t sent = 0;
  while (sent < len) {
    const ssize_t n = ::send(fd, base + sent, len - sent, MSG_NOSIGNAL);
    if (n > 0) {
      sent += static_cast<std::size_t>(n);
      continue;
    }
    if (n < 0 && errno == EINTR) {
      continue;
    }
    return false;
  }
  return true;
}

bool read_all(int fd, void* data, std::size_t len) {
  char* base = static_cast<char*>(data);
  std::size_t got = 0;
  while (got < len) {
    const ssize_t n = ::recv(fd, base + got, len - got, 0);
    if (n > 0) {
      got += static_cast<std::size_t>(n);
      continue;
    }
    if (n < 0 && errno == EINTR) {
      continue;
    }
    return false;  // n == 0 → 对端关闭；n < 0 → 错误
  }
  return true;
}

// ---------------------------------------------------------------------------
// 最小帧：MAGIC(4) | LEN(4, 大端) | PAYLOAD(LEN)
// ---------------------------------------------------------------------------

bool send_frame(int fd, const std::string& payload) {
  if (payload.size() > kMaxFrame) {
    return false;
  }
  char header[8];
  std::memcpy(header, kMagic, 4);
  const uint32_t len_be = ::htonl(static_cast<uint32_t>(payload.size()));
  std::memcpy(header + 4, &len_be, 4);
  if (!write_all(fd, header, sizeof(header))) {
    return false;
  }
  return payload.empty() ? true : write_all(fd, payload.data(), payload.size());
}

bool recv_frame(int fd, std::string* payload) {
  char header[8];
  if (!read_all(fd, header, sizeof(header))) {
    return false;
  }
  if (std::memcmp(header, kMagic, 4) != 0) {
    return false;
  }
  uint32_t len_be = 0;
  std::memcpy(&len_be, header + 4, 4);
  const std::size_t len = static_cast<std::size_t>(::ntohl(len_be));
  if (len > kMaxFrame) {
    return false;
  }
  payload->assign(len, '\0');
  if (len == 0) {
    return true;
  }
  return read_all(fd, &(*payload)[0], len);
}

// ---------------------------------------------------------------------------
// 监听套接字：仅绑定 127.0.0.1，端口由内核分配（探路无需固定端口）
// ---------------------------------------------------------------------------

bool make_listener(int* out_fd, uint16_t* out_port) {
  const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) {
    return false;
  }
  const int on = 1;
  ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, sizeof(on));

  struct sockaddr_in addr;
  std::memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = 0;
  if (::inet_pton(AF_INET, kBindAddress, &addr.sin_addr) != 1) {
    ::close(fd);
    return false;
  }
  if (::bind(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
    ::close(fd);
    return false;
  }
  if (::listen(fd, 1) != 0) {
    ::close(fd);
    return false;
  }
  // 探路工程的防御：accept/recv 不得无限阻塞，超时即失败（避免 ctest 假死不报错）。
  struct timeval tv;
  tv.tv_sec = 5;
  tv.tv_usec = 0;
  ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  struct sockaddr_in bound;
  socklen_t blen = sizeof(bound);
  std::memset(&bound, 0, sizeof(bound));
  if (::getsockname(fd, reinterpret_cast<struct sockaddr*>(&bound), &blen) != 0) {
    ::close(fd);
    return false;
  }
  *out_fd = fd;
  *out_port = ::ntohs(bound.sin_port);
  return true;
}

// 成功返回已连接的 fd，失败返回 -1。
int connect_loopback(uint16_t port) {
  const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
  if (fd < 0) {
    return -1;
  }
  struct timeval tv;
  tv.tv_sec = 5;
  tv.tv_usec = 0;
  ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
  struct sockaddr_in addr;
  std::memset(&addr, 0, sizeof(addr));
  addr.sin_family = AF_INET;
  addr.sin_port = ::htons(port);
  if (::inet_pton(AF_INET, kBindAddress, &addr.sin_addr) != 1) {
    ::close(fd);
    return -1;
  }
  if (::connect(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) != 0) {
    ::close(fd);
    return -1;
  }
  return fd;
}

// ---------------------------------------------------------------------------
// 用例 ①：验收标准所指的「HGCP 空消息」收发（零长度 payload 边界路径）
// ---------------------------------------------------------------------------

bool run_empty_round_trip(std::string* detail) {
  std::string local;
  if (!hgcp::v1::HgcpEmpty().SerializeToString(&local)) {
    *detail = "本地序列化 HgcpEmpty 失败";
    return false;
  }
  if (!local.empty()) {
    *detail = "HgcpEmpty 序列化结果非空（长度 " + std::to_string(local.size()) + "）";
    return false;
  }

  int listen_fd = -1;
  uint16_t port = 0;
  if (!make_listener(&listen_fd, &port)) {
    *detail = std::string("监听 127.0.0.1 失败: ") + std::strerror(errno);
    return false;
  }

  std::string server_err;
  std::size_t server_payload_len = static_cast<std::size_t>(-1);

  std::thread server([&]() {
    const int conn = ::accept(listen_fd, NULL, NULL);
    if (conn < 0) {
      server_err = "accept 失败";
      return;
    }
    std::string in;
    if (!recv_frame(conn, &in)) {
      server_err = "服务端收帧失败";
      ::close(conn);
      return;
    }
    server_payload_len = in.size();
    hgcp::v1::HgcpEmpty req;
    if (!req.ParseFromString(in)) {
      server_err = "服务端解析 HgcpEmpty 失败";
      ::close(conn);
      return;
    }
    // 注意：protobuf 3.21 起 `MessageLite::ByteSize()` 已标记 deprecated，
    // 在 `-Wall -Wextra -Werror` 下会被 `-Werror=deprecated-declarations` 拦下。
    // 故此处一律使用 `ByteSizeLong()`（M2 业务代码同此纪律）。
    if (req.ByteSizeLong() != 0) {
      server_err = "服务端解析后 ByteSizeLong() != 0";
      ::close(conn);
      return;
    }
    std::string out;
    hgcp::v1::HgcpEmpty resp;
    if (!resp.SerializeToString(&out)) {
      server_err = "服务端序列化应答失败";
      ::close(conn);
      return;
    }
    if (!send_frame(conn, out)) {
      server_err = "服务端发帧失败";
    }
    ::close(conn);
  });

  int client_fd = -1;
  bool ok = true;
  std::string client_err;
  std::size_t client_payload_len = static_cast<std::size_t>(-1);

  for (int attempt = 0; attempt < 200 && client_fd < 0; ++attempt) {
    client_fd = connect_loopback(port);
    if (client_fd < 0) {
      std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
  }
  if (client_fd < 0) {
    client_err = "客户端连接 127.0.0.1 失败";
    ok = false;
  } else {
    if (!send_frame(client_fd, local)) {
      client_err = "客户端发帧失败";
      ok = false;
    } else {
      std::string in;
      if (!recv_frame(client_fd, &in)) {
        client_err = "客户端收帧失败";
        ok = false;
      } else {
        client_payload_len = in.size();
        hgcp::v1::HgcpEmpty resp;
        if (!resp.ParseFromString(in)) {
          client_err = "客户端解析应答失败";
          ok = false;
        }
      }
    }
    ::close(client_fd);
  }

  server.join();
  ::close(listen_fd);

  if (!ok) {
    *detail = client_err;
    return false;
  }
  if (!server_err.empty()) {
    *detail = server_err;
    return false;
  }
  if (server_payload_len != 0) {
    *detail = "服务端收到的 payload 长度应为 0，实为 " + std::to_string(server_payload_len);
    return false;
  }
  if (client_payload_len != 0) {
    *detail = "客户端收到的 payload 长度应为 0，实为 " + std::to_string(client_payload_len);
    return false;
  }
  *detail = "127.0.0.1:" + std::to_string(port) + " 空消息往返成功，双向 payload 均为 0 字节";
  return true;
}

// ---------------------------------------------------------------------------
// 用例 ②（加严补充）：含字段消息收发，覆盖 §4.2.4 的字段类型
// ---------------------------------------------------------------------------

hgcp::v1::HgcpSmokeFields build_sample() {
  hgcp::v1::HgcpSmokeFields m;
  m.set_client_order_ref("HG20260920-0001");
  m.set_sub_account_id(7);
  m.set_volume(42);
  m.set_client_ts_ms(static_cast<int64_t>(1758384000000LL));
  m.set_limit_price("70000.500000");
  m.set_direction(hgcp::v1::HGCP_DIRECTION_BUY);
  m.add_frames(std::string("\x01\x02\x03", 3));
  m.add_frames(std::string(""));
  return m;
}

bool validate_sample(const hgcp::v1::HgcpSmokeFields& m, std::string* why) {
  if (m.client_order_ref() != "HG20260920-0001") {
    *why = "client_order_ref 不符";
    return false;
  }
  if (m.sub_account_id() != 7u) {
    *why = "sub_account_id 不符";
    return false;
  }
  if (m.volume() != 42) {
    *why = "volume 不符";
    return false;
  }
  if (m.client_ts_ms() != static_cast<int64_t>(1758384000000LL)) {
    *why = "client_ts_ms 不符（int64 往返异常）";
    return false;
  }
  if (m.limit_price() != "70000.500000") {
    *why = "limit_price 不符（十进制字符串往返异常）";
    return false;
  }
  if (m.direction() != hgcp::v1::HGCP_DIRECTION_BUY) {
    *why = "direction 不符（enum 往返异常）";
    return false;
  }
  if (m.frames_size() != 2) {
    *why = "frames 数量不符（repeated bytes 往返异常）";
    return false;
  }
  if (m.frames(0) != std::string("\x01\x02\x03", 3)) {
    *why = "frames[0] 内容不符";
    return false;
  }
  if (!m.frames(1).empty()) {
    *why = "frames[1] 应为空字节串";
    return false;
  }
  return true;
}

bool run_fields_round_trip(std::string* detail) {
  const hgcp::v1::HgcpSmokeFields sample = build_sample();
  std::string payload;
  if (!sample.SerializeToString(&payload)) {
    *detail = "序列化 HgcpSmokeFields 失败";
    return false;
  }
  if (payload.size() > kMaxFrame) {
    *detail = "样例消息超过 1 MiB 上限";
    return false;
  }

  int listen_fd = -1;
  uint16_t port = 0;
  if (!make_listener(&listen_fd, &port)) {
    *detail = std::string("监听 127.0.0.1 失败: ") + std::strerror(errno);
    return false;
  }

  std::string server_err;
  std::size_t server_bytes = static_cast<std::size_t>(-1);

  std::thread server([&]() {
    const int conn = ::accept(listen_fd, NULL, NULL);
    if (conn < 0) {
      server_err = "accept 失败";
      return;
    }
    std::string in;
    if (!recv_frame(conn, &in)) {
      server_err = "服务端收帧失败";
      ::close(conn);
      return;
    }
    server_bytes = in.size();
    hgcp::v1::HgcpSmokeFields req;
    if (!req.ParseFromString(in)) {
      server_err = "服务端解析 HgcpSmokeFields 失败";
      ::close(conn);
      return;
    }
    std::string why;
    if (!validate_sample(req, &why)) {
      server_err = "服务端字段校验失败：" + why;
      ::close(conn);
      return;
    }
    std::string out;
    if (!req.SerializeToString(&out)) {
      server_err = "服务端回显序列化失败";
      ::close(conn);
      return;
    }
    if (!send_frame(conn, out)) {
      server_err = "服务端发帧失败";
    }
    ::close(conn);
  });

  int client_fd = -1;
  bool ok = true;
  std::string client_err;
  bool echoed = false;

  for (int attempt = 0; attempt < 200 && client_fd < 0; ++attempt) {
    client_fd = connect_loopback(port);
    if (client_fd < 0) {
      std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
  }
  if (client_fd < 0) {
    client_err = "客户端连接 127.0.0.1 失败";
    ok = false;
  } else {
    if (!send_frame(client_fd, payload)) {
      client_err = "客户端发帧失败";
      ok = false;
    } else {
      std::string in;
      if (!recv_frame(client_fd, &in)) {
        client_err = "客户端收帧失败";
        ok = false;
      } else {
        hgcp::v1::HgcpSmokeFields resp;
        if (!resp.ParseFromString(in)) {
          client_err = "客户端解析回显失败";
          ok = false;
        } else {
          echoed = (resp.SerializeAsString() == sample.SerializeAsString());
          if (!echoed) {
            client_err = "回显内容与请求逐字节不一致";
            ok = false;
          }
        }
      }
    }
    ::close(client_fd);
  }

  server.join();
  ::close(listen_fd);

  if (!ok) {
    *detail = client_err;
    return false;
  }
  if (!server_err.empty()) {
    *detail = server_err;
    return false;
  }
  *detail = "payload " + std::to_string(server_bytes) + " 字节，字段校验与逐字节回显一致";
  return true;
}

// ---------------------------------------------------------------------------
// 版本与编译参数记录
// ---------------------------------------------------------------------------

void print_config(std::ostream& os) {
  const std::string cxx_std = HGCP_SMOKE_CXX_STANDARD_VALUE;
  os << "---- HGCP proto smoke / RSK-01 构通记录 ----\n";
  os << "cxx.standard.requested      : c++" << cxx_std << "\n";
  os << "cxx.standard.__cplusplus    : " << static_cast<long>(__cplusplus) << "\n";
  os << "cxx.standard.is_cxx11       : " << ((__cplusplus == 201103L) ? "YES" : "NO") << "\n";
  os << "compiler                    : " << __VERSION__ << "\n";
  os << "protobuf.headers.version    : " << GOOGLE_PROTOBUF_VERSION << " -> "
     << google::protobuf::internal::VersionString(GOOGLE_PROTOBUF_VERSION) << "\n";
  os << "protobuf.headers.min_lib    : " << GOOGLE_PROTOBUF_MIN_LIBRARY_VERSION << "\n";
  os << "protobuf.cmake.found        : " << HGCP_SMOKE_CMAKE_PROTOBUF_VERSION << "\n";
  os << "protobuf.cmake.provider     : " << HGCP_SMOKE_PROTOBUF_PROVIDER << "\n";
  os << "protobuf.cmake.variant      : " << HGCP_SMOKE_PROTOBUF_VARIANT << "\n";
  os << "protobuf.cmake.major.required: " << HGCP_SMOKE_PROTOBUF_MAJOR_REQUIRED << "\n";
  os << "protoc.path                 : " << HGCP_SMOKE_PROTOC_PATH << "\n";
  os << "cxx.flags                   : " << HGCP_SMOKE_CXX_FLAGS << "\n";
  os << "build.type                  : " << HGCP_SMOKE_BUILD_TYPE << "\n";
  os << "schema.used                 : " << HGCP_SMOKE_SCHEMA_FILE << "\n";
  os << "schema.is.frozen.contract   : " << HGCP_SMOKE_SCHEMA_IS_FROZEN_CONTRACT << "\n";
  os << "bind.address (smoke)        : " << kBindAddress << "\n";
  os << "-------------------------------------------\n";
}

}  // namespace

int main(int argc, char** argv) {
  // 头文件 / 运行库版本一致性校验（不匹配即 abort）——对应"两侧同源、同大版本"纪律。
  GOOGLE_PROTOBUF_VERIFY_VERSION;

  std::string mode = (argc > 1) ? std::string(argv[1]) : std::string("--self-test");

  if (mode == "--print-config") {
    print_config(std::cout);
    std::cout << "RESULT: PASS (编译标准事实 = C++11)\n";
    return 0;
  }

  if (mode == "--empty-roundtrip") {
    std::string detail;
    const bool ok = run_empty_round_trip(&detail);
    std::cout << "[empty-roundtrip] " << detail << "\n";
    std::cout << "RESULT: " << (ok ? "PASS" : "FAIL") << "\n";
    return ok ? 0 : 1;
  }

  if (mode == "--fields-roundtrip") {
    std::string detail;
    const bool ok = run_fields_round_trip(&detail);
    std::cout << "[fields-roundtrip] " << detail << "\n";
    std::cout << "RESULT: " << (ok ? "PASS" : "FAIL") << "\n";
    return ok ? 0 : 1;
  }

  if (mode != "--self-test") {
    std::cerr << "未知参数: " << mode
              << "（可用：--self-test | --print-config | --empty-roundtrip | --fields-roundtrip）\n";
    return 2;
  }

  print_config(std::cout);

  std::string d1;
  const bool ok1 = run_empty_round_trip(&d1);
  std::cout << "[empty-roundtrip]  " << d1 << "\n";

  std::string d2;
  const bool ok2 = run_fields_round_trip(&d2);
  std::cout << "[fields-roundtrip] " << d2 << "\n";

  const bool all = ok1 && ok2;
  std::cout << "RESULT: " << (all ? "PASS" : "FAIL") << "\n";
  return all ? 0 : 1;
}
