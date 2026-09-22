// HGCP 回环服务端承载循环实现 —— `tcp` 原语 + `session` 状态机的装配（唯一归属）。
#include "hg/hgcp/server_loop.h"

#include <string>

namespace hg {
namespace hgcp {

namespace {

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }
std::uint16_t fl(FlagBits b) { return static_cast<std::uint16_t>(b); }

std::vector<std::uint8_t> empty_body() { return std::vector<std::uint8_t>(); }

// 有界轮询：预算内等待可读；已可读则立即返回（不空等）。
// 分片 5ms 以避免「数据已就绪却空等整段预算」。
bool wait_readable(int fd, int budget_ms) {
  if (budget_ms <= 0) {
    return poll_readable(fd, 0) == 1;
  }
  int waited = 0;
  while (waited < budget_ms) {
    const int slice = 5;
    if (poll_readable(fd, slice) == 1) {
      return true;
    }
    waited += slice;
  }
  return false;
}

}  // namespace

HgcpServerLoop::HgcpServerLoop(const TcpConfig& cfg)
    : cfg_(cfg),
      conn_fd_(-1),
      policy_(&default_policy_),
      accepted_(0),
      duplicates_(0),
      frames_in_(0),
      disconnects_(0),
      handshaken_(false),
      reject_seq_(1),
      reject_mid_(1) {}

HgcpServerLoop::~HgcpServerLoop() { stop(); }

BindStatus HgcpServerLoop::start() { return listener_.start(cfg_); }

void HgcpServerLoop::stop() {
  if (conn_fd_ >= 0) {
    close_fd(conn_fd_);
    conn_fd_ = -1;
  }
  listener_.stop();
}

void HgcpServerLoop::set_policy(const TransportPolicy* p) {
  policy_ = (p != 0) ? p : &default_policy_;
}

void HgcpServerLoop::configure(std::int64_t heartbeat_interval_ms, int heartbeat_miss,
                               std::size_t resume_capacity) {
  session_.configure(heartbeat_interval_ms, heartbeat_miss, resume_capacity);
}

int HgcpServerLoop::pump(int timeout_ms, std::int64_t now_ms, std::vector<Frame>* inbound) {
  int delivered = 0;
  bool accepted_any = false;

  // 1) accept：单连接治理（已有活跃连接 ⇒ 新连接拒 0x0302 后关闭）。
  std::string peer;
  for (;;) {
    const int c = listener_.accept_one(&peer);
    if (c < 0) {
      break;
    }
    accepted_any = true;
    if (session_.on_new_connection() == ConnectionDecision::kRejectDuplicate) {
      ++duplicates_;
      raw_send(c, mt(MessageType::kMsgError), fl(kFlagIsError), reject_seq_++, reject_mid_++,
               policy_->duplicate_connection_body());
      close_fd(c);
    } else {
      conn_fd_ = c;
      session_.on_connected(now_ms);
      handshaken_ = false;
      ++accepted_;
    }
  }

  // 2) 读循环：增量切帧（半包保留 / 粘包逐取）+ 帧级握手门禁。
  if (conn_fd_ >= 0) {
    int budget = accepted_any ? 0 : timeout_ms;  // 刚 accept ⇒ 不在读上等待
    for (;;) {
      if (!wait_readable(conn_fd_, budget)) {
        break;
      }
      budget = 0;  // 首帧后不再等待
      std::uint8_t buf[512];
      const int r = recv_some(conn_fd_, buf, sizeof(buf));
      if (r == -2) {
        break;  // EAGAIN
      }
      if (r <= 0) {
        close_conn();  // 0 = 对端关闭 / -1 = 错误
        break;
      }
      std::vector<Frame> fs;
      const FrameStatus st = session_.feed(buf, static_cast<std::size_t>(r), &fs);
      for (std::size_t i = 0; i < fs.size(); ++i) {
        ++frames_in_;
        ++delivered;
        if (inbound != 0) {
          inbound->push_back(fs[i]);
        }
        handle_inbound(fs[i], now_ms);
        if (conn_fd_ < 0) {
          break;
        }
      }
      if (conn_fd_ < 0) {
        break;
      }
      if (st != FrameStatus::kOk && st != FrameStatus::kNeedMore) {
        close_conn();  // 帧内错误（坏 MAGIC/CRC/超限…）⇒ 断连
        break;
      }
    }
  }

  // 3) 心跳定时器：到点发送；连续 3 次未 ACK ⇒ 判死（0x0301）。
  if (conn_fd_ >= 0) {
    std::vector<Frame> hb;
    if (!session_.on_tick(now_ms, *policy_, &hb)) {
      // 尽力回一帧诊断告警（body 由接缝注入；字段级布局待 CH-013），再关连接。
      send_frame(mt(MessageType::kMsgError), fl(kFlagIsError),
                 policy_->heartbeat_timeout_body());
      close_conn();
    } else {
      for (std::size_t i = 0; i < hb.size(); ++i) {
        send_frame_obj(hb[i]);
      }
    }
  }
  return delivered;
}

bool HgcpServerLoop::send_frame(std::uint16_t type, std::uint16_t flags,
                                const std::vector<std::uint8_t>& body) {
  Frame f;
  if (session_.make_outbound(type, flags, body, &f) != FrameStatus::kOk) {
    return false;
  }
  return send_frame_obj(f);
}

ReplayStatus HgcpServerLoop::send_resume(std::uint64_t last_received_seq) {
  std::vector<std::vector<BufferedFrame> > batches;
  const ReplayStatus st = session_.build_resume(last_received_seq, &batches);
  if (st != ReplayStatus::kOk) {
    return st;  // kNothingToReplay / kGapTooLarge（0x0306 由上层的全量重建决定）
  }
  for (std::size_t i = 0; i < batches.size(); ++i) {
    // ⚠️ body = 批内原始帧字节**顺序拼接**（不透明回放）。`from_seq`/`to_seq`/`is_last`
    //    属 MSG_RESUME_DATA 的字段级 schema（待 CH-013）⇒ 暂不写入 body。
    std::vector<std::uint8_t> body;
    for (std::size_t k = 0; k < batches[i].size(); ++k) {
      body.insert(body.end(), batches[i][k].bytes.begin(), batches[i][k].bytes.end());
    }
    send_frame(mt(MessageType::kMsgResumeData), fl(kFlagIsResumeData), body);
  }
  return ReplayStatus::kOk;
}

void HgcpServerLoop::handle_inbound(const Frame& f, std::int64_t now_ms) {
  // 帧级握手门禁（§4.2.6）：握手前仅接受 MSG_HELLO，否则按 §4.3.2 0x0107 拒并断连。
  // ⚠️ 令牌校验（HELLO.auth_token）属 S-3（字段级 schema 待 CH-013）——此处仅帧级。
  if (!handshaken_) {
    if (f.header.type == mt(MessageType::kMsgHello)) {
      handshaken_ = true;
      send_frame(mt(MessageType::kMsgHelloAck), fl(kFlagIsReply), empty_body());
    } else {
      send_frame(mt(MessageType::kMsgError), fl(kFlagIsError), empty_body());
      close_conn();
    }
    return;
  }
  if (f.header.type == mt(MessageType::kMsgHeartbeatAck)) {
    session_.on_heartbeat_ack(now_ms);
  }
}

bool HgcpServerLoop::send_frame_obj(const Frame& f) {
  if (conn_fd_ < 0) {
    return false;
  }
  std::vector<std::uint8_t> wire;
  if (make_frame(f.header.type, f.header.flags, f.header.seq, f.header.msg_id, f.body, &wire) !=
      FrameStatus::kOk) {
    return false;
  }
  if (!send_all(conn_fd_, &wire[0], wire.size())) {
    close_conn();
    return false;
  }
  return true;
}

void HgcpServerLoop::raw_send(int fd, std::uint16_t type, std::uint16_t flags,
                              std::uint64_t seq, std::uint64_t mid,
                              const std::vector<std::uint8_t>& body) {
  std::vector<std::uint8_t> wire;
  if (make_frame(type, flags, seq, mid, body, &wire) != FrameStatus::kOk) {
    return;
  }
  if (!wire.empty()) {
    send_all(fd, &wire[0], wire.size());
  }
}

void HgcpServerLoop::close_conn() {
  if (conn_fd_ >= 0) {
    close_fd(conn_fd_);
    conn_fd_ = -1;
    ++disconnects_;
  }
  session_.on_closed();
}

}  // namespace hgcp
}  // namespace hg
