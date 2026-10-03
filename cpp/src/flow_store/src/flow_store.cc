// 指令流水 append 原语（T-036 最小承载，ARCHITECTURE.md §3.5）。
// 本目录不在 E4 扫描根（v1.5.12）：POSIX append + fsync 合法。
#include "hg/flow_store/flow_store.h"

#include <fcntl.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>

namespace hg {
namespace flow_store {

namespace {

// detail/ref 消毒：一行一记录、五字段定列（见 flow_store.h 行格式契约）。
std::string sanitize(const std::string& in) {
  std::string out;
  out.reserve(in.size());
  for (std::size_t k = 0; k < in.size(); ++k) {
    const char c = in[k];
    if (c == '|') {
      out.push_back('/');
    } else if (c == '\n' || c == '\r') {
      out.push_back(' ');
    } else {
      out.push_back(c);
    }
  }
  return out;
}

}  // namespace

const char* flowKindName(FlowKind k) {
  switch (k) {
    case FlowKind::kPlaced: return "PLACED";
    case FlowKind::kSent: return "SENT";
    case FlowKind::kReported: return "REPORTED";
    case FlowKind::kFilled: return "FILLED";
    case FlowKind::kRejected: return "REJECTED";
    case FlowKind::kExpired: return "EXPIRED";
    case FlowKind::kCancelled: return "CANCELLED";
    default: return "UNKNOWN";
  }
}

const char* flowStateName(FlowState s) {
  switch (s) {
    case FlowState::kNotReady: return "NOT_READY";
    case FlowState::kReplaying: return "REPLAYING";
    case FlowState::kReady: return "READY";
    default: return "UNKNOWN";
  }
}

FlowStore::FlowStore() : fd_(-1), state_(FlowState::kNotReady), count_(0) {}

FlowStore::~FlowStore() { close(); }

bool FlowStore::open(const std::string& path) {
  if (fd_ >= 0) close();
  // O_APPEND：追加写语义（R-TRADE-15 只增不改）；fsync 逐条落盘（FR-TRADE-15）。
  const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND, 0644);
  if (fd < 0) {
    state_ = FlowState::kNotReady;
    return false;
  }
  fd_ = fd;
  path_ = path;
  count_ = 0;
  state_ = FlowState::kReady;
  return true;
}

void FlowStore::close() {
  if (fd_ >= 0) {
    ::close(fd_);
    fd_ = -1;
  }
  state_ = FlowState::kNotReady;
}

bool FlowStore::append(const FlowRecord& rec) {
  if (fd_ < 0) return false;
  char head[96];
  const int hn = std::snprintf(head, sizeof(head), "%lld|%s|%llu|%s|",
                               static_cast<long long>(rec.ts_ms), flowKindName(rec.kind),
                               static_cast<unsigned long long>(rec.order_id),
                               sanitize(rec.client_order_ref).c_str());
  if (hn <= 0 || static_cast<std::size_t>(hn) >= sizeof(head)) return false;
  const std::string tail = sanitize(rec.detail) + "\n";
  const std::size_t total = static_cast<std::size_t>(hn) + tail.size();
  // 单条记录一次性落盘：先拼缓冲，再单次 ::write（O_APPEND 下写原子性以单次调用保证）。
  // 缓冲用 std::string（写路径允许分配；判定热路径的零分配纪律不约束 T7 流水）。
  std::string line;
  line.reserve(total);
  line.append(head, static_cast<std::size_t>(hn));
  line.append(tail);
  std::size_t off = 0;
  while (off < line.size()) {
    const ssize_t n = ::write(fd_, line.data() + off, line.size() - off);
    if (n <= 0) return false;
    off += static_cast<std::size_t>(n);
  }
  if (::fsync(fd_) != 0) return false;
  ++count_;
  return true;
}

}  // namespace flow_store
}  // namespace hg
