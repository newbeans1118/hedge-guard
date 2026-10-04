// 启动重放 / 状态机 / CTP 对账 / 分片保留（T-037；ARCHITECTURE.md §3.5 / §4.5）。
// 本目录不在 E4 扫描根（v1.5.12）：POSIX 目录遍历与文件读合法。
#include "hg/flow_store/replay.h"

#include <dirent.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace hg {
namespace flow_store {

namespace {

// ---- 公历日期算术（保留期滚动清理用；Howard Hinnant days_from_civil 算法）----
std::int64_t daysFromCivil(int y, unsigned m, unsigned d) {
  y -= m <= 2;
  const int era = (y >= 0 ? y : y - 399) / 400;
  const unsigned yoe = static_cast<unsigned>(y - era * 400);
  const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return static_cast<std::int64_t>(era) * 146097 + static_cast<std::int64_t>(doe) - 719468;
}

void civilFromDays(std::int64_t z, int* y, unsigned* m, unsigned* d) {
  z += 719468;
  const int era = static_cast<int>((z >= 0 ? z : z - 146096) / 146097);
  const unsigned doe = static_cast<unsigned>(z - static_cast<std::int64_t>(era) * 146097);
  const unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
  const std::int64_t yy = static_cast<std::int64_t>(yoe) + era * 400;
  const unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
  const unsigned mp = (5 * doy + 2) / 153;
  *d = doy - (153 * mp + 2) / 5 + 1;
  *m = mp + (mp < 10 ? 3 : -9);
  *y = static_cast<int>(yy + (*m <= 2));
}

int daysToYmd(std::int64_t days) {
  int y = 0;
  unsigned m = 0, d = 0;
  civilFromDays(days, &y, &m, &d);
  return y * 10000 + static_cast<int>(m) * 100 + static_cast<int>(d);
}

std::int64_t ymdToDays(int ymd) {
  return daysFromCivil(ymd / 10000, (ymd / 100) % 100, ymd % 100);
}

bool allDigits(const char* s, std::size_t n) {
  if (n == 0) return false;
  for (std::size_t i = 0; i < n; ++i) {
    if (s[i] < '0' || s[i] > '9') return false;
  }
  return true;
}

bool parseUint64(const char* s, std::size_t n, std::uint64_t* out) {
  if (!allDigits(s, n)) return false;
  std::uint64_t v = 0;
  for (std::size_t i = 0; i < n; ++i) {
    const std::uint64_t d = static_cast<std::uint64_t>(s[i] - '0');
    if (v > (~v) / 10ULL) return false;  // 溢出守卫
    v = v * 10ULL + d;
  }
  *out = v;
  return true;
}

bool parseInt64(const char* s, std::size_t n, std::int64_t* out) {
  bool neg = false;
  if (n > 0 && s[0] == '-') {
    neg = true;
    ++s;
    --n;
  }
  std::uint64_t v = 0;
  if (!parseUint64(s, n, &v)) return false;
  if (neg) {
    *out = -static_cast<std::int64_t>(v);
  } else {
    *out = static_cast<std::int64_t>(v);
  }
  return true;
}

bool parseKind(const char* s, std::size_t n, FlowKind* out) {
  struct Entry { const char* name; FlowKind kind; };
  static const Entry kEntries[] = {
      {"PLACED", FlowKind::kPlaced},     {"SENT", FlowKind::kSent},
      {"REPORTED", FlowKind::kReported}, {"FILLED", FlowKind::kFilled},
      {"REJECTED", FlowKind::kRejected}, {"EXPIRED", FlowKind::kExpired},
      {"CANCELLED", FlowKind::kCancelled},
  };
  for (int i = 0; i < 7; ++i) {
    if (std::strlen(kEntries[i].name) == n &&
        std::memcmp(kEntries[i].name, s, n) == 0) {
      *out = kEntries[i].kind;
      return true;
    }
  }
  return false;
}

bool isTerminalKind(FlowKind k) {
  return k == FlowKind::kFilled || k == FlowKind::kRejected ||
         k == FlowKind::kExpired || k == FlowKind::kCancelled;
}

// shard 文件名 → 日期；不匹配返回 false。
bool shardDate(const std::string& name, int* ymd) {
  if (name.size() != 17) return false;  // flow-(5) + 8 + .log(4)
  if (std::memcmp(name.c_str(), "flow-", 5) != 0) return false;
  if (std::memcmp(name.c_str() + 13, ".log", 4) != 0) return false;
  std::uint64_t v = 0;
  if (!parseUint64(name.c_str() + 5, 8, &v)) return false;
  *ymd = static_cast<int>(v);
  return true;
}

struct ShardFile {
  std::string name;
  int ymd;
};

// 目录扫描：仅收集分片命名文件并按日期升序；其余计数跳过。目录不可读 ⇒ false。
bool listShards(const std::string& dir, std::vector<ShardFile>* out, std::size_t* skipped) {
  DIR* d = ::opendir(dir.c_str());
  if (d == nullptr) return false;
  struct dirent* e = nullptr;
  while ((e = ::readdir(d)) != nullptr) {
    const std::string name(e->d_name);
    if (name == "." || name == "..") continue;
    int ymd = 0;
    if (!shardDate(name, &ymd)) {
      ++(*skipped);
      continue;
    }
    ShardFile sf;
    sf.name = name;
    sf.ymd = ymd;
    out->push_back(sf);
  }
  ::closedir(d);
  std::sort(out->begin(), out->end(),
            [](const ShardFile& a, const ShardFile& b) { return a.ymd < b.ymd; });
  return true;
}

// ---- 结构校验解析（一行一记录；见 replay.h「结构完整性口径」块）-----------

// 校验一行（不含 \n）。合法 ⇒ 填充 rec 并返回 true；否则 false。
bool parseLine(const std::string& line, std::size_t line_no, const std::string& shard,
               FlowRecord* rec, std::string* err) {
  int pipes = 0;
  for (std::size_t i = 0; i < line.size(); ++i) {
    if (line[i] == '|') ++pipes;
  }
  if (pipes != 4) {
    *err = shard + ":" + std::to_string(line_no) + ": expect 5 fields, got " +
           std::to_string(pipes + 1);
    return false;
  }
  // 各字段起点：starts[0..4]（循环填 f1..f4 的起点）+ 终点 = line.size()。
  std::size_t starts[5];
  starts[0] = 0;
  int k = 1;
  for (std::size_t i = 0; i < line.size() && k < 5; ++i) {
    if (line[i] == '|') starts[k++] = i + 1;
  }
  const std::size_t len[4] = {starts[1] - 1, starts[2] - starts[1] - 1,
                              starts[3] - starts[2] - 1,
                              starts[4] - starts[3] - 1};
  if (!parseInt64(line.c_str(), len[0], &rec->ts_ms)) {
    *err = shard + ":" + std::to_string(line_no) + ": bad ts_ms";
    return false;
  }
  if (!parseKind(line.c_str() + starts[1], len[1], &rec->kind)) {
    *err = shard + ":" + std::to_string(line_no) + ": bad KIND";
    return false;
  }
  if (!parseUint64(line.c_str() + starts[2], len[2], &rec->order_id)) {
    *err = shard + ":" + std::to_string(line_no) + ": bad order_id";
    return false;
  }
  rec->client_order_ref.assign(line, starts[3], len[3]);
  rec->detail.assign(line, starts[4], std::string::npos);
  return true;
}

}  // namespace

// ---- 配置与工具 ------------------------------------------------------------

FlowFsyncMode parseFlowFsyncMode(const char* name, bool* ok) {
  if (ok != nullptr) *ok = true;
  if (std::strcmp(name, "EVERY_RECORD") == 0) return FlowFsyncMode::kEveryRecord;
  if (std::strcmp(name, "BATCH") == 0) return FlowFsyncMode::kBatch;
  if (std::strcmp(name, "ASYNC") == 0) return FlowFsyncMode::kAsync;
  if (ok != nullptr) *ok = false;
  return FlowFsyncMode::kEveryRecord;
}

const char* flowFsyncModeName(FlowFsyncMode m) {
  switch (m) {
    case FlowFsyncMode::kEveryRecord: return "EVERY_RECORD";
    case FlowFsyncMode::kBatch: return "BATCH";
    case FlowFsyncMode::kAsync: return "ASYNC";
    default: return "UNKNOWN";
  }
}

FlowRuntimeConfig defaultFlowRuntimeConfig() { return FlowRuntimeConfig(); }

std::string flowShardPath(const std::string& dir, int yyyymmdd) {
  char buf[32];
  std::snprintf(buf, sizeof(buf), "flow-%08d.log", yyyymmdd);
  if (!dir.empty() && dir[dir.size() - 1] == '/') return dir + buf;
  return dir + "/" + buf;
}

int sweepRetention(const std::string& dir, int retain_days, int today_yyyymmdd) {
  std::vector<ShardFile> shards;
  std::size_t skipped = 0;
  if (!listShards(dir, &shards, &skipped)) return -1;
  const std::int64_t cutoff = ymdToDays(today_yyyymmdd) - static_cast<std::int64_t>(retain_days);
  int removed = 0;
  for (std::size_t i = 0; i < shards.size(); ++i) {
    if (ymdToDays(shards[i].ymd) < cutoff) {
      if (::unlink((dir + "/" + shards[i].name).c_str()) == 0) ++removed;
    }
  }
  return removed;
}

const char* replayVerdictName(ReplayVerdict v) {
  switch (v) {
    case ReplayVerdict::kOk: return "OK";
    case ReplayVerdict::kCorrupt: return "CORRUPT";
    case ReplayVerdict::kMismatch: return "MISMATCH";
    case ReplayVerdict::kOpenFailed: return "OPEN_FAILED";
    default: return "UNKNOWN";
  }
}

// ---- 重放控制器 ------------------------------------------------------------

ReplayController::ReplayController()
    : query_(nullptr), alert_(nullptr), state_(FlowState::kNotReady), failed_(false) {}

void ReplayController::set_runtime_config(const FlowRuntimeConfig& cfg) { cfg_ = cfg; }
void ReplayController::set_order_query(IOrderStateQuery* query) { query_ = query; }
void ReplayController::set_alert_sink(IReplayAlertSink* alert) { alert_ = alert; }

namespace {

// 统计分片行数并检查末行终止符（撕裂尾行 = 结构损坏，见 replay.h 口径块）。
// 返回 false = 打不开或末行未终止。
bool countShardLines(const std::string& path, std::uint64_t* lines, bool* ends_with_nl) {
  FILE* f = std::fopen(path.c_str(), "rb");
  if (f == nullptr) return false;
  char buf[4096];
  std::size_t n = 0;
  std::uint64_t cnt = 0;
  char last = '\n';
  bool any = false;
  while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) {
    for (std::size_t i = 0; i < n; ++i) {
      if (buf[i] == '\n') ++cnt;
      last = buf[i];
      any = true;
    }
  }
  std::fclose(f);
  *lines = cnt;
  *ends_with_nl = !any || last == '\n';
  return true;
}

}  // namespace

ReplayReport ReplayController::run(std::int64_t now_ms, ProgressFn progress, void* user) {
  report_ = ReplayReport();
  report_.verdict = ReplayVerdict::kOk;  // 失败路径显式改判；成功路径零标记
  report_.started_ts_ms = now_ms;
  failed_ = false;

  // ① 保留期滚动清理（验收 1；清理失败不阻断重放——只影响磁盘占用，披露口径）。
  {
    const int today = daysToYmd(now_ms / 86400000LL);  // epoch ms → 公历日
    sweepRetention(cfg_.flow_dir, cfg_.retain_days, today);
  }

  // ② 目录扫描（§4.5.3 ②）：不可读 ⇒ 拒服务（0x3001 口径）。
  std::vector<ShardFile> shards;
  {
    std::size_t skipped = 0;
    if (!listShards(cfg_.flow_dir, &shards, &skipped)) {
      report_.verdict = ReplayVerdict::kOpenFailed;
      report_.detail = "flow dir not readable: " + cfg_.flow_dir;
      report_.finished_ts_ms = now_ms;
      state_ = FlowState::kNotReady;
      return report_;
    }
    report_.files_skipped = skipped;
  }
  report_.shards_read = shards.size();

  // 进度分母：预统计各分片行数 + 末行终止检查（撕裂尾 ⇒ kCorrupt）。
  std::vector<std::uint64_t> shard_lines(shards.size(), 0);
  for (std::size_t i = 0; i < shards.size(); ++i) {
    bool ends_nl = true;
    if (!countShardLines(cfg_.flow_dir + "/" + shards[i].name, &shard_lines[i], &ends_nl)) {
      report_.verdict = ReplayVerdict::kOpenFailed;
      report_.detail = "shard not readable: " + shards[i].name;
      report_.finished_ts_ms = now_ms;
      state_ = FlowState::kNotReady;
      return report_;
    }
    if (!ends_nl) {
      report_.verdict = ReplayVerdict::kCorrupt;
      report_.detail = "torn tail line (no LF): " + shards[i].name;
      report_.finished_ts_ms = now_ms;
      state_ = FlowState::kReplaying;
      failed_ = true;
      return report_;
    }
    report_.total_records += shard_lines[i];
  }

  // ⑤ state = REPLAYING；逐行解析 + 状态还原。
  state_ = FlowState::kReplaying;
  if (progress != nullptr) progress(user, state_, 0, report_.total_records);

  struct Agg {
    ReplayOrderState st;
  };
  std::vector<Agg> orders;  // 首现次序 = 产出次序（稳定）

  FILE* f = nullptr;
  std::string cur_shard;
  char buf[65536];
  std::string pending;
  std::uint64_t line_no = 0;
  std::uint64_t done = 0;
  const std::uint64_t every = cfg_.replay_progress_every == 0 ? 1 : cfg_.replay_progress_every;
  bool aborted = false;

  for (std::size_t si = 0; si < shards.size() && !aborted; ++si) {
    cur_shard = shards[si].name;
    f = std::fopen((cfg_.flow_dir + "/" + cur_shard).c_str(), "rb");
    if (f == nullptr) {
      report_.verdict = ReplayVerdict::kOpenFailed;
      report_.detail = "shard not readable: " + cur_shard;
      break;
    }
    std::size_t n = 0;
    bool shard_done = false;
    while (!aborted && !shard_done) {
      n = std::fread(buf, 1, sizeof(buf), f);
      if (n == 0) {
        if (pending.empty()) {
          shard_done = true;
          break;
        }
        report_.verdict = ReplayVerdict::kCorrupt;
        report_.detail = "torn tail line (no LF): " + cur_shard;
        break;
      }
      for (std::size_t i = 0; i < n && !aborted; ++i) {
        if (buf[i] != '\n') {
          pending.push_back(buf[i]);
          continue;
        }
        ++line_no;
        FlowRecord rec;
        std::string err;
        if (!parseLine(pending, static_cast<std::size_t>(line_no), cur_shard, &rec, &err)) {
          report_.verdict = ReplayVerdict::kCorrupt;
          report_.detail = err;
          aborted = true;
          pending.clear();
          break;
        }
        pending.clear();
        ++done;
        ++report_.replayed_records;

        Agg* a = nullptr;
        for (std::size_t k = 0; k < orders.size(); ++k) {
          if (orders[k].st.order_id == rec.order_id) {
            a = &orders[k];
            break;
          }
        }
        if (a == nullptr) {
          Agg na;
          na.st.order_id = rec.order_id;
          orders.push_back(na);
          a = &orders.back();
        }
        a->st.last_kind = rec.kind;
        a->st.last_ts_ms = rec.ts_ms;
        if (!rec.client_order_ref.empty()) a->st.client_order_ref = rec.client_order_ref;
        if (rec.kind == FlowKind::kSent) a->st.sent = true;
        if (isTerminalKind(rec.kind)) a->st.terminal = true;

        if (progress != nullptr && (done % every) == 0) {
          progress(user, state_, done, report_.total_records);
        }
      }
    }
    std::fclose(f);
    f = nullptr;
  }

  if (report_.verdict == ReplayVerdict::kCorrupt || report_.verdict == ReplayVerdict::kOpenFailed) {
    report_.finished_ts_ms = now_ms;
    if (report_.verdict == ReplayVerdict::kCorrupt) {
      state_ = FlowState::kReplaying;
      failed_ = true;
    } else {
      state_ = FlowState::kNotReady;
    }
    return report_;
  }

  // 产出还原序列（按首现次序）。
  for (std::size_t k = 0; k < orders.size(); ++k) {
    report_.orders.push_back(orders[k].st);
    if (!orders[k].st.sent && !orders[k].st.terminal) {
      report_.pending_unsent.push_back(InflightOrder());
      InflightOrder& io = report_.pending_unsent.back();
      io.order_id = orders[k].st.order_id;
      io.client_order_ref = orders[k].st.client_order_ref;
      io.sent_ts_ms = orders[k].st.last_ts_ms;
    }
  }

  // ⑤/⑥ 在途对账（sent && !terminal）。
  for (std::size_t k = 0; k < report_.orders.size() && !aborted; ++k) {
    const ReplayOrderState& st = report_.orders[k];
    if (!st.sent || st.terminal) continue;
    InflightOrder io;
    io.order_id = st.order_id;
    io.client_order_ref = st.client_order_ref;
    io.sent_ts_ms = st.last_ts_ms;

    std::string qdetail;
    OrderQueryResult qr = OrderQueryResult::kTimeout;
    if (query_ != nullptr) {
      qr = query_->query(io, &qdetail);
    }

    ReplayDecision d;
    d.order = io;
    d.ctp_result = qr;
    switch (qr) {
      case OrderQueryResult::kLive:
        report_.orders[k].live_ctp = true;
        ++report_.frozen_held;
        d.kind = ReplayDecision::kUnknownKeepFrozen;  // 保守：仍在途 ⇒ 冻结保留
        d.detail = "live at CTP; frozen held";
        break;
      case OrderQueryResult::kFilled:
      case OrderQueryResult::kCancelled:
      case OrderQueryResult::kRejected:
        d.kind = ReplayDecision::kAdoptTerminal;
        d.detail = qdetail;
        break;
      case OrderQueryResult::kNotFound:
        d.kind = ReplayDecision::kReleaseExpired;
        ++report_.frozen_released;
        d.detail = qdetail.empty() ? "not found at CTP; release frozen + EVT_ORDER_EXPIRED"
                                   : qdetail;
        break;
      case OrderQueryResult::kTimeout:
        d.kind = ReplayDecision::kUnknownKeepFrozen;
        ++report_.unknown_count;
        ++report_.frozen_held;
        d.detail = "query timeout; state unknown; frozen held (conservative)";
        if (alert_ != nullptr) alert_->onInflightUnknown(io);
        break;
      case OrderQueryResult::kConflict:
      default:
        report_.verdict = ReplayVerdict::kMismatch;
        report_.detail = "reconcile conflict on order_id=" + std::to_string(io.order_id) +
                         (qdetail.empty() ? "" : (": " + qdetail));
        aborted = true;
        break;
    }
    if (!aborted) report_.decisions.push_back(d);
  }

  report_.finished_ts_ms = now_ms;
  if (report_.verdict == ReplayVerdict::kMismatch) {
    state_ = FlowState::kReplaying;
    failed_ = true;  // §4.5.3 ⑥：拒绝一切服务（FAILED 态承载于 failed_，见头注）
    if (progress != nullptr) progress(user, state_, done, report_.total_records);
    return report_;
  }
  report_.verdict = ReplayVerdict::kOk;  // 对账无矛盾 ⇒ 终判 OK

  // ⑦ READY。
  state_ = FlowState::kReady;
  if (progress != nullptr) progress(user, state_, done, report_.total_records);
  return report_;
}

}  // namespace flow_store
}  // namespace hg
