// HGCP 回环服务端承载循环实现 —— `tcp` 原语 + `session` 状态机的装配（唯一归属）。
#include "hg/hgcp/server_loop.h"

#include <cstdio>
#include <string>

namespace hg {
namespace hgcp {

SessionReadySink::~SessionReadySink() {}
ResumeGapSink::~ResumeGapSink() {}
ReconnectAlarmSink::~ReconnectAlarmSink() {}

namespace {

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }
std::uint16_t fl(FlagBits b) { return static_cast<std::uint16_t>(b); }

// T-208：未注入组件状态源时的**保守**取值 = §4.2.8.7 `SERVER_STATE_NOT_READY`（值 `1`）。
//   本地常量（不新增公开符号、不引 app 层），仅用于 `component_state()` 的缺省分支。
const std::int32_t kFallbackServerStateNotReady = 1;

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
      auth_guard_(),
      auth_audit_sink_(0),
      session_ready_sink_(0),
      resume_gap_sink_(0),
      reconnect_alarm_sink_(0),
      component_state_fn_(0),
      component_state_user_(0),
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

void HgcpServerLoop::set_token_verifier(const TokenVerifier* v) {
  auth_guard_.set_token_verifier(v);
}

void HgcpServerLoop::set_expected_token_version(std::uint32_t v) {
  auth_guard_.set_expected_token_version(v);
}

void HgcpServerLoop::set_replay_window_ms(std::int64_t ms) {
  auth_guard_.set_replay_window_ms(ms);
}

void HgcpServerLoop::set_auth_audit_sink(AuthAuditSink* s) {
  auth_audit_sink_ = s;
}

// T-208：组件状态源取值（未注入 ⇒ 保守 = NOT_READY / 0）。
//   §4.2.8.7 `ServerState` 的整数常量（**本地**取值，不新增枚举值、不引 app 层符号）：
//     `SERVER_STATE_UNSET` / `NOT_READY` / `REPLAYING` / `READY` / `STOPPING` = 0/1/2/3/4。
//   ⚠️ 该值由**注入回调**提供；`hgcp` 不反向依赖 `TradingApp`（T-208 硬约束）。
void HgcpServerLoop::component_state(std::int32_t* server_state,
                                     std::uint64_t* param_version) const {
  if (component_state_fn_ != 0) {
    std::int32_t st = 0;
    std::uint64_t pv = 0;
    component_state_fn_(component_state_user_, &st, &pv);
    if (server_state != 0) *server_state = st;
    if (param_version != 0) *param_version = pv;
    return;
  }
  // 无权威来源 ⇒ **不得**报 READY（保守取 NOT_READY；见 server_loop.h 接缝契约）。
  if (server_state != 0) *server_state = kFallbackServerStateNotReady;
  if (param_version != 0) *param_version = 0;
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
          peer_ = peer;
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
        // 帧内错误 ⇒ 断连。**帧超限例外**：§4.2.8.8 `I-2`（行为面裁定，2026-09-22）
        //   —— `LEN > 1 MiB` 属「**帧边界不可信**」：
        //     ① 声明不可信（越限本身违反 §4.2.2「须分片」成帧契约）；
        //     ② 按声明排空不可负担（`uint32` ⇒ 最长 2³²−1 字节，而 `1 MiB` 上限正为界定
        //        接收侧资源而设 ⇒ 排空即取消该上限）；
        //     ③ 不排空必错位（TCP 字节流 ⇒ 下一「帧头」落在超大 payload 中部）。
        //   ⇒ 唯一同时「资源有界 + 可终止」的处置 = **尽力回 `0x0308`（提示分片经 `err_msg`）
        //     → 再关闭连接**（同 :138 心跳判死先例；关闭不丢业务数据，接 §4.2.6 重连补数）。
        //   注：`I-2`「不中断连接」一句仅适用于**帧边界可信**的成帧层错误（`0x0104`/`0x0105`），
        //       对 `LEN` 超限**不适用**；该修订拟立 `CH-014`（待用户批准），不影响本处置。
        if (st == FrameStatus::kFrameTooLarge) {
          send_frame(mt(MessageType::kMsgError), fl(kFlagIsError),
                     policy_->frame_too_large_body());
        }
        close_conn();
        break;
      }
      // SEQ 回退 / 重复（`SEQ` ≤ 已收最大）⇒ §4.3.2 `:1343` 触发条件（`0x0106`
      //   `ERR_SEQ_OUT_OF_ORDER`）：尽力回 `MSG_ERROR{0x0106}`（body 经 `TransportPolicy`
      //   接缝编码）；**连接保持**（处理建议 = 触发补数，非断连）。信号一次性，回码后即清。
      //   前向缺口（跳号）不由本分支处理：它走 `session` 既有 gap 机制（不在此回码）。
      if (session_.seq_out_of_order()) {
        session_.clear_seq_out_of_order();
        send_frame(mt(MessageType::kMsgError), fl(kFlagIsError),
                   policy_->error_body(
                       static_cast<std::uint32_t>(ErrorCode::kErrSeqOutOfOrder)));
      }
    }
  }

  // 3) 心跳定时器：到点发送；连续 3 次未 ACK ⇒ 判死（0x0301）。
  if (conn_fd_ >= 0) {
    std::vector<Frame> hb;
    if (!session_.on_tick(now_ms, *policy_, &hb)) {
      // 尽力回一帧诊断告警（body 经接缝编码为 `MsgError{0x0301}`；CH-013 / §4.2.8），再关连接。
      send_frame(mt(MessageType::kMsgError), fl(kFlagIsError),
                 policy_->heartbeat_timeout_body());
      close_conn();
    } else {
      for (std::size_t i = 0; i < hb.size(); ++i) {
        send_frame_obj(hb[i]);
      }
    }
  }
  // 4) 重连严重告警产生点（T-177，ARCH 4.2.6 :975 / 4.3.2 :1351）：累计失败跨入 >= 10 档
  //    （0x0303 ERR_RECONNECT_LIMIT）时**一次性**产生：
  //      (a) 运维可见告警（LOG.WARNING 级）—— 经 `ReconnectAlarmSink`；未注入则退化为 stderr 行；
  //      (b) 若有活连接，尽力回 `MSG_ERROR{0x0303}`（body 经 `TransportPolicy` 单一接缝编码）。
  //    承载形态：ARCH 对该码仅定「严重告警 + 提示运维介入」，**未**规定是否断连/是否须 ACK
  //      ⇒ 本实现不断连、不要求 ACK（不臆造）。一次性：session 侧跨档首置位，本层消费后清除
  //      ⇒ 同一严重档不重复刷屏；重连成功 `reset_reconnect()` 后可再次告警。
  if (session_.reconnect_severe_alarm_pending()) {
    session_.clear_reconnect_severe_alarm();
    const int attempts = session_.reconnect_attempts();
    if (reconnect_alarm_sink_ != 0) {
      reconnect_alarm_sink_->on_reconnect_severe(attempts);
    } else {
      std::fprintf(stderr,
                   "[HGCP][WARNING] reconnect severe: attempts=%d "
                   "(0x0303 ERR_RECONNECT_LIMIT); manual intervention required\n",
                   attempts);
    }
    if (conn_fd_ >= 0) {
      send_frame(mt(MessageType::kMsgError), fl(kFlagIsError),
                 policy_->error_body(
                     static_cast<std::uint32_t>(ErrorCode::kErrReconnectLimit)));
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
    // body 按 §4.2.8.2 `MsgResumeData{from_seq,to_seq,is_last,frames}` 组装，经
    //   `TransportPolicy`（**单一出站 body 接缝**）编码 —— 本层不再拼接原始帧字节。
    //   · `frames` = 批内各帧的**原始帧字节**（逐字节回放，线路侧不过滤，§4.2.6）；
    //   · `from_seq`/`to_seq` = 本批**首/末帧**的通道 SEQ（批内 `SEQ` 连续）；
    //   · `is_last` = 本批是否为**最后一批**（§4.2.6：单批 ≤ 500 帧、`is_last` 标识最后一批）。
    // `batches[i]` 恒非空（`ResumeBuffer::replay_from` 只入非空批）⇒ 首/末帧索引安全。
    std::vector<std::vector<std::uint8_t> > frames;
    frames.reserve(batches[i].size());
    for (std::size_t k = 0; k < batches[i].size(); ++k) {
      frames.push_back(batches[i][k].bytes);
    }
    const std::uint64_t from_seq = batches[i][0].seq;
    const std::uint64_t to_seq = batches[i][batches[i].size() - 1u].seq;
    const bool is_last = (i + 1u == batches.size());
    const std::vector<std::uint8_t> body =
        policy_->resume_data_body(from_seq, to_seq, is_last, frames);
    send_frame(mt(MessageType::kMsgResumeData), fl(kFlagIsResumeData), body);
  }
  return ReplayStatus::kOk;
}

void HgcpServerLoop::handle_inbound(const Frame& f, std::int64_t now_ms) {
  // 帧级握手门禁（§4.2.6）：握手前仅接受 MSG_HELLO，否则按 §4.3.2 0x0107 拒并断连。
  // 鉴权判定（T-171 / T-175 / FR-CONFIG-9 验收 4）：HELLO 到达且已注入 `TokenVerifier` ⇒
  //   经 `TransportPolicy` 接缝取 `MsgHello` 的 `auth_token` / `token_version` /
  //   `client_boot_id`（本层不解释字段）→ `AuthGuard::verify`（令牌 SHA-256 + 常量时间
  //   比较 + 版本判定 0x0203 + 重放判定 0x0204 + 失败留痕，均在该组件内）⇒
  //   失败回 `MSG_AUTH_FAIL`(0x0003，body 经接缝编码 `MsgAuthFail{reason_code}`，§4.2.8.2)
  //   + **立即关闭连接**（§4.2.6 `:956`）；verifier 为 null ⇒ 维持既有帧级门禁（不因
  //   未配置令牌而拒连，向后兼容）。本层**不**重复留痕、不记录令牌信息（NFR-SEC-1）。
  if (!handshaken_) {
    if (f.header.type == mt(MessageType::kMsgHello)) {
      if (auth_guard_.enabled()) {
        // T-175：经接缝取令牌 / 版本 / 启动标识（本层不解释字段），交 AuthGuard 综合判定：
        //   令牌摘要 → token_version（0x0203）→ client_boot_id 重放（0x0204）。
        const std::string token = policy_->hello_auth_token(f.body);
        const std::uint32_t token_version = policy_->hello_token_version(f.body);
        const std::string client_boot_id = policy_->hello_client_boot_id(f.body);
        const AuthResult ar = auth_guard_.verify(token, token_version, client_boot_id,
                                                 auth_audit_sink_, peer_, now_ms);
        if (ar != AuthResult::kAccepted) {
          send_frame(mt(MessageType::kMsgAuthFail), fl(kFlagIsError),
          policy_->auth_fail_body(error_code_for(ar)));
          close_conn();
          return;
        }
      }
      handshaken_ = true;
      // T-208：`MSG_HELLO_ACK` 的 body 经 `TransportPolicy` 接缝**逐字段编码**
      //   （§4.2.8.2 `MsgHelloAck` 六字段）。**不得**再回 `empty_body()`：proto3 下空 body
      //   解析出全默认值（`accepted=false` / `server_proto_version=0` /
      //   `server_state=SERVER_STATE_UNSET`）⇒ Java `HgcpClient` 以 `accepted` 判握手 ⇒
      //   **合法握手被判 `AUTH_FAILED`**，C++↔Java 真联不可达（本缺陷本体）。
      //   取值：`accepted=true`（走到本行必经鉴权通过，见 :275-288）；
      //         `server_proto_version = kWireVersion`（`protocol.h:31` = `0x0100`，与 Java
      //           `HgcpFrame.WIRE_VERSION` 对齐）；
      //         `server_state` / `param_version` = **注入的组件状态源**（见 component_state()）；
      //         `session_id` = 空串（架构未定义生成规则 ⇒ 现状；T-208 回执已披露）；
      //         `reason_code = 0`（`accepted=true` ⇒ 该字段非必填，§4.2.8.5）。
      std::int32_t st = 0;
      std::uint64_t pv = 0;
      component_state(&st, &pv);
      send_frame(mt(MessageType::kMsgHelloAck), fl(kFlagIsReply),
                 policy_->hello_ack_body(true, kWireVersion, std::string(), st, pv, 0u));
      // T-039：握手完成 ⇒ 通知事件层（保留队列按序补发；先于任何 RESUME_DATA 上线）。
      if (session_ready_sink_ != 0) {
        session_ready_sink_->on_session_ready();
      }
    } else {
      send_frame(mt(MessageType::kMsgError), fl(kFlagIsError),
      policy_->error_body(static_cast<std::uint32_t>(ErrorCode::kErrNotHandshaken)));
      close_conn();
    }
    return;
  }
  if (f.header.type == mt(MessageType::kMsgHeartbeatAck)) {
    session_.on_heartbeat_ack(now_ms);
    return;
  }
  // 补数请求（T-039 / 验收 4 / §4.2.6「补数请求」行）：重连握手完成后由对端发起。
  //   · `last_received_seq` 经接缝解析（本层不解释字段）；不可解析 ⇒ 按 0 = 从头补数；
  //   · `send_resume` 分批 ≤500 回放 `SEQ > last`（线路侧不过滤行情帧，CH-007）；
  //   · 缺口超缓冲 ⇒ **不得伪造**：尽力回 `MSG_ERROR{0x0306}`，再通知缺口接收方
  //     （上层全量状态重建的决定点；重建期间拒新指令仍归 `system_ready()` 单一门卫）。
  if (f.header.type == mt(MessageType::kMsgResumeReq)) {
    std::uint64_t last = 0;
    if (!policy_->resume_req_last_seq(f.body, &last)) {
      last = 0;
    }
    const ReplayStatus st = send_resume(last);
    if (st == ReplayStatus::kGapTooLarge) {
      send_frame(mt(MessageType::kMsgError), fl(kFlagIsError),
                 policy_->error_body(
                     static_cast<std::uint32_t>(ErrorCode::kErrResumeGapTooLarge)));
      if (resume_gap_sink_ != 0) {
        resume_gap_sink_->on_resume_gap();
      }
    }
    return;
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
