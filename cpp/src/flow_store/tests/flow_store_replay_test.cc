// T-037 flow_store 重放 / 状态机 / 对账 / 保留清理 单测（零依赖 harness，同仓同形）。
// 覆盖：验收 1（CFG-29 配置承载 + fsync 裁定 + 分片命名 + 保留清理）/
//       验收 2（NOT_READY→REPLAYING→READY 可见 + 进度回调）/
//       验收 3（结构损坏整文件拒 / 对账 MISMATCH 拒服务）/
//       验收 4（在途未达释放 + 超时状态未知冻结保留 + 告警）。
#include <sys/stat.h>
#include <sys/types.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "hg/flow_store/flow_store.h"
#include "hg/flow_store/replay.h"

// 断言：单次求值（与 risk_engine testutil::check_impl 同形，本地零依赖）。
namespace {
int g_fail = 0;
bool check_impl(bool ok, const char* expr, const char* file, int line) {
  if (!ok) {
    std::printf("[CHECK-FAIL] %s:%d: %s\n", file, line, expr);
    ++g_fail;
  }
  return ok;
}
}  // namespace
#define HG_CHECK(cond) ::check_impl((cond), #cond, __FILE__, __LINE__)
#define HG_CHECK_EQ(a, b) ::check_impl((a) == (b), #a " == " #b, __FILE__, __LINE__)
#define HG_ABORT_IF(cond)                                                  \
  do {                                                                     \
    if (cond) {                                                            \
      std::printf("[ABORT] %s:%d\n", __FILE__, __LINE__);                  \
      std::exit(2);                                                        \
    }                                                                      \
  } while (0)

namespace {

using hg::flow_store::FlowKind;
using hg::flow_store::FlowRecord;
using hg::flow_store::FlowRuntimeConfig;
using hg::flow_store::FlowState;
using hg::flow_store::FlowStore;
using hg::flow_store::FlowFsyncMode;
using hg::flow_store::InflightOrder;
using hg::flow_store::IOrderStateQuery;
using hg::flow_store::IReplayAlertSink;
using hg::flow_store::OrderQueryResult;
using hg::flow_store::ReplayController;
using hg::flow_store::ReplayDecision;
using hg::flow_store::ReplayReport;
using hg::flow_store::ReplayVerdict;

const char* kRoot = "/root/t037-flow-test";

std::string dirOf(const char* name) { return std::string(kRoot) + "/" + name; }

void mkclean(const std::string& d) {
  std::string cmd = std::string("rm -rf '") + d + "'; mkdir -p '" + d + "'";
  HG_ABORT_IF(std::system(cmd.c_str()) != 0);
}

// 真实写者（FlowStore 契约行格式）写一条。
void writeRec(FlowStore* fs, FlowKind kind, std::uint64_t oid, const char* ref,
              std::int64_t ts, const char* detail) {
  FlowRecord r;
  r.kind = kind;
  r.order_id = oid;
  r.client_order_ref = ref;
  r.ts_ms = ts;
  r.detail = detail;
  HG_CHECK(fs->append(r));
}

void writeRaw(const std::string& path, const std::string& content) {
  FILE* f = std::fopen(path.c_str(), "wb");
  HG_ABORT_IF(f == nullptr);
  HG_ABORT_IF(content.empty() ? false : std::fwrite(content.data(), 1, content.size(), f) != content.size());
  std::fclose(f);
}

// 假 CTP 查询：按 order_id 查表；未命中默认 kLive。统计调用数。
class FakeQuery : public IOrderStateQuery {
 public:
  int calls = 0;
  std::vector<std::pair<std::uint64_t, OrderQueryResult> > table;
  std::string detail_out;

  int callsFor(std::uint64_t oid) const {
    // 本假件单次 run 只查在途；calls 即总查询数（调用序由测试断言）。
    (void)oid;
    return calls;
  }
  OrderQueryResult query(const InflightOrder& o, std::string* detail) override {
    ++calls;
    for (std::size_t i = 0; i < table.size(); ++i) {
      if (table[i].first == o.order_id) {
        if (detail != nullptr) *detail = detail_out;
        return table[i].second;
      }
    }
    return OrderQueryResult::kLive;
  }
};

class AlertLog : public IReplayAlertSink {
 public:
  std::vector<std::uint64_t> hits;
  void onInflightUnknown(const InflightOrder& o) override { hits.push_back(o.order_id); }
};

struct ProgressLog {
  std::vector<FlowState> states;
  std::vector<std::uint64_t> done;
  std::vector<std::uint64_t> total;
};

void progress_rec(void* user, FlowState st, std::uint64_t done, std::uint64_t total) {
  ProgressLog* p = static_cast<ProgressLog*>(user);
  p->states.push_back(st);
  p->done.push_back(done);
  p->total.push_back(total);
}

// ---- 1. 配置承载（验收 1 / CFG-29 / fsync 裁定）---------------------------
void test_default_config_and_fsync() {
  FlowRuntimeConfig c = hg::flow_store::defaultFlowRuntimeConfig();
  HG_CHECK(c.flow_dir == "./data/flow");
  HG_CHECK(c.fsync_requested == FlowFsyncMode::kBatch);       // CFG-29 缺省 BATCH
  HG_CHECK(c.fsync_effective == FlowFsyncMode::kEveryRecord);  // 本单裁定（头注）
  HG_CHECK(c.fsync_clamped);
  HG_CHECK_EQ(c.retain_days, 90);
  HG_CHECK_EQ(c.replay_query_timeout_ms, static_cast<std::int64_t>(20000));
  HG_CHECK_EQ(c.replay_progress_every, static_cast<std::size_t>(1000));

  bool ok = false;
  HG_CHECK(hg::flow_store::parseFlowFsyncMode("EVERY_RECORD", &ok) ==
           FlowFsyncMode::kEveryRecord);
  HG_CHECK(ok);
  HG_CHECK(hg::flow_store::parseFlowFsyncMode("BATCH", &ok) == FlowFsyncMode::kBatch);
  HG_CHECK(ok);
  HG_CHECK(hg::flow_store::parseFlowFsyncMode("ASYNC", &ok) == FlowFsyncMode::kAsync);
  HG_CHECK(ok);
  hg::flow_store::parseFlowFsyncMode("NOPE", &ok);
  HG_CHECK(!ok);
}

// ---- 2. 分片命名 + 保留清理（验收 1「保留 90 天」）-------------------------
void test_shard_path_and_sweep() {
  const std::string d = dirOf("sweep");
  mkclean(d);
  HG_CHECK(hg::flow_store::flowShardPath("./data/flow", 20261004) ==
           std::string("./data/flow/flow-20261004.log"));
  HG_CHECK(hg::flow_store::flowShardPath("./data/flow/", 20261004) ==
           std::string("./data/flow/flow-20261004.log"));  // 尾斜杠不双写

  writeRaw(d + "/flow-20260101.log", "1|PLACED|1|r|d\n");   // 老分片（将删）
  writeRaw(d + "/flow-20260701.log", "1|PLACED|2|r|d\n");   // 保留期内
  writeRaw(d + "/flow-20260901.log", "1|PLACED|3|r|d\n");   // 近期
  writeRaw(d + "/other.log", "x\n");                        // 非分片命名：不触碰
  writeRaw(d + "/flow-badcd.log", "x\n");                   // 非法日期：不触碰
  const int removed = hg::flow_store::sweepRetention(d, 90, 20260910);
  HG_CHECK_EQ(removed, 1);  // 仅 20260101（today-90 = 20260612 之前）
  struct stat st;
  HG_CHECK(stat((d + "/flow-20260101.log").c_str(), &st) != 0);
  HG_CHECK(stat((d + "/flow-20260701.log").c_str(), &st) == 0);
  HG_CHECK(stat((d + "/flow-20260901.log").c_str(), &st) == 0);
  HG_CHECK(stat((d + "/other.log").c_str(), &st) == 0);
  HG_CHECK(stat((d + "/flow-badcd.log").c_str(), &st) == 0);
  HG_CHECK_EQ(hg::flow_store::sweepRetention((d + "/nope"), 90, 20260910), -1);
}

// ---- 3. 重放主链：READY + 状态机可见 + 进度（验收 2）-----------------------
void test_replay_ready_progress_states() {
  const std::string d = dirOf("ready");
  mkclean(d);
  FlowStore fs;
  HG_CHECK(fs.open(hg::flow_store::flowShardPath(d, 20261001)));
  // 指令 1：完整生命周期；指令 2：待确认崩溃（PLACED 无 SENT）；指令 3：在途。
  writeRec(&fs, FlowKind::kPlaced, 1, "HG1", 100, "accepted pending confirm");
  writeRec(&fs, FlowKind::kSent, 1, "HG1", 200, "submitted to CTP");
  writeRec(&fs, FlowKind::kFilled, 1, "HG1", 300, "filled");
  writeRec(&fs, FlowKind::kPlaced, 2, "HG2", 110, "accepted pending confirm");
  writeRec(&fs, FlowKind::kPlaced, 3, "HG3", 120, "accepted pending confirm");
  writeRec(&fs, FlowKind::kSent, 3, "HG3", 220, "submitted to CTP");
  fs.close();

  ReplayController rc;
  FlowRuntimeConfig cfg;
  cfg.flow_dir = d;
  cfg.replay_progress_every = 2;  // 6 条 ⇒ 0/2/4/6 + 终态共 5 次回调
  rc.set_runtime_config(cfg);
  FakeQuery q;
  q.table.push_back(std::make_pair<std::uint64_t, OrderQueryResult>(3, OrderQueryResult::kLive));
  rc.set_order_query(&q);
  ProgressLog p;

  const ReplayReport rep = rc.run(1770000000000LL, &progress_rec, &p);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  HG_CHECK(rc.state() == FlowState::kReady);
  HG_CHECK(rc.ready());
  HG_CHECK(!rc.failed());
  HG_CHECK_EQ(rep.total_records, static_cast<std::uint64_t>(6));
  HG_CHECK_EQ(rep.replayed_records, static_cast<std::uint64_t>(6));
  HG_CHECK_EQ(rep.shards_read, static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.orders.size(), static_cast<std::size_t>(3));
  // 还原态：1=终态、2=未发（pending_unsent）、3=CTP 在途（冻结保留）。
  HG_CHECK(rep.orders[0].terminal && rep.orders[0].sent);
  HG_CHECK_EQ(rep.pending_unsent.size(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.pending_unsent[0].order_id, static_cast<std::uint64_t>(2));
  HG_CHECK(rep.orders[2].live_ctp);
  HG_CHECK_EQ(rep.frozen_held, static_cast<std::size_t>(1));
  HG_CHECK_EQ(q.calls, 1);  // 只查在途（指令 3）
  // 状态机对外可见：进度回调承载 NOT_READY→REPLAYING→READY。
  HG_CHECK_EQ(p.states.size(), static_cast<std::size_t>(5));
  HG_CHECK(p.states[0] == FlowState::kReplaying);
  HG_CHECK_EQ(p.done[0], static_cast<std::uint64_t>(0));
  HG_CHECK_EQ(p.done[1], static_cast<std::uint64_t>(2));
  HG_CHECK_EQ(p.done[2], static_cast<std::uint64_t>(4));
  HG_CHECK(p.states.back() == FlowState::kReady);
  HG_CHECK_EQ(p.total[0], static_cast<std::uint64_t>(6));
}

// ---- 4. 在途未达 ⇒ 释放 + EVT_ORDER_EXPIRED 承载（验收 4 / §4.5.3 ⑤）------
void test_inflight_not_found_release() {
  const std::string d = dirOf("notfound");
  mkclean(d);
  FlowStore fs;
  HG_CHECK(fs.open(hg::flow_store::flowShardPath(d, 20261001)));
  writeRec(&fs, FlowKind::kPlaced, 7, "HG7", 100, "accepted pending confirm");
  writeRec(&fs, FlowKind::kSent, 7, "HG7", 200, "submitted to CTP");
  fs.close();

  ReplayController rc;
  FlowRuntimeConfig cfg;
  cfg.flow_dir = d;
  rc.set_runtime_config(cfg);
  FakeQuery q;
  q.table.push_back(std::make_pair<std::uint64_t, OrderQueryResult>(
      7, OrderQueryResult::kNotFound));
  q.detail_out = "no such order at CTP";
  rc.set_order_query(&q);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  HG_CHECK(rc.ready());
  HG_CHECK_EQ(rep.decisions.size(), static_cast<std::size_t>(1));
  HG_CHECK(rep.decisions[0].kind == ReplayDecision::kReleaseExpired);
  HG_CHECK(rep.decisions[0].ctp_result == OrderQueryResult::kNotFound);
  HG_CHECK(rep.decisions[0].detail.find("no such order at CTP") != std::string::npos);
  HG_CHECK_EQ(rep.frozen_released, static_cast<std::size_t>(1));
}

// ---- 5. 查询超时/未接线 ⇒ 状态未知 + 冻结保留 + 告警（验收 4）--------------
void test_inflight_timeout_unknown_alert() {
  const std::string d = dirOf("timeout");
  mkclean(d);
  FlowStore fs;
  HG_CHECK(fs.open(hg::flow_store::flowShardPath(d, 20261001)));
  writeRec(&fs, FlowKind::kPlaced, 9, "HG9", 100, "accepted pending confirm");
  writeRec(&fs, FlowKind::kSent, 9, "HG9", 200, "submitted to CTP");
  fs.close();

  ReplayController rc;
  FlowRuntimeConfig cfg;
  cfg.flow_dir = d;
  rc.set_runtime_config(cfg);
  FakeQuery q;  // 返回 kTimeout
  q.table.push_back(std::make_pair<std::uint64_t, OrderQueryResult>(
      9, OrderQueryResult::kTimeout));
  rc.set_order_query(&q);
  AlertLog alert;
  rc.set_alert_sink(&alert);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  HG_CHECK(rc.ready());  // 保守放行交易，但在途单冻结不释放（§4.5.3 性能注）
  HG_CHECK_EQ(rep.unknown_count, static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.frozen_held, static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.frozen_released, static_cast<std::size_t>(0));
  HG_CHECK_EQ(alert.hits.size(), static_cast<std::size_t>(1));
  if (!alert.hits.empty()) HG_CHECK_EQ(alert.hits[0], static_cast<std::uint64_t>(9));  // 变异下防越界
  HG_CHECK(rep.decisions[0].kind == ReplayDecision::kUnknownKeepFrozen);

  // 查询未接线（生产装配前）⇒ 同保守路径。
  ReplayController rc2;
  rc2.set_runtime_config(cfg);  // query_ = null ⇒ 一律按超时保守
  rc2.set_alert_sink(&alert);
  const ReplayReport rep2 = rc2.run(1770000000001LL, nullptr, nullptr);
  HG_CHECK_EQ(rep2.unknown_count, static_cast<std::size_t>(1));
  HG_CHECK_EQ(alert.hits.size(), static_cast<std::size_t>(2));
}

// ---- 6. CTP 终态 ⇒ 按 CTP 修正（验收 3 / §4.5.3 ⑤）------------------------
void test_inflight_adopt_terminal() {
  const std::string d = dirOf("adopt");
  mkclean(d);
  FlowStore fs;
  HG_CHECK(fs.open(hg::flow_store::flowShardPath(d, 20261001)));
  writeRec(&fs, FlowKind::kPlaced, 5, "HG5", 100, "accepted pending confirm");
  writeRec(&fs, FlowKind::kSent, 5, "HG5", 200, "submitted to CTP");
  fs.close();

  ReplayController rc;
  FlowRuntimeConfig cfg;
  cfg.flow_dir = d;
  rc.set_runtime_config(cfg);
  FakeQuery q;
  q.table.push_back(std::make_pair<std::uint64_t, OrderQueryResult>(
      5, OrderQueryResult::kFilled));
  q.detail_out = "trade_id=T99";
  rc.set_order_query(&q);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  HG_CHECK_EQ(rep.decisions.size(), static_cast<std::size_t>(1));
  HG_CHECK(rep.decisions[0].kind == ReplayDecision::kAdoptTerminal);
  HG_CHECK(rep.decisions[0].ctp_result == OrderQueryResult::kFilled);
  HG_CHECK(rep.decisions[0].detail.find("trade_id=T99") != std::string::npos);
}

// ---- 7. 结构损坏 ⇒ 整文件拒（验收 3 / §4.5.1 不得带病运行）----------------
void test_corrupt_variants_reject() {
  const char* cases[] = {
      "100|BOGUS|1|r|d\n",               // 非法 KIND
      "abc|PLACED|1|r|d\n",              // 非法 ts
      "100|PLACED|x|r|d\n",              // 非法 order_id
      "100|PLACED|1|r\n",                // 字段数不足（4 字段）
      "100|PLACED|1|r|d|extra\n",        // 字段超量（7 字段）
      "\n",                              // 空行
      "100|PLACED|1|r|d\n\n",            // 中部空行
  };
  for (int i = 0; i < static_cast<int>(sizeof(cases) / sizeof(cases[0])); ++i) {
    const std::string d = dirOf("corrupt");
    mkclean(d);
    FlowStore fs;
    HG_CHECK(fs.open(hg::flow_store::flowShardPath(d, 20261001)));
    writeRec(&fs, FlowKind::kPlaced, 1, "HG1", 100, "accepted pending confirm");
    fs.close();
    FILE* f = std::fopen(hg::flow_store::flowShardPath(d, 20261001).c_str(), "ab");
    HG_ABORT_IF(f == nullptr);
    std::fputs(cases[i], f);
    std::fclose(f);

    ReplayController rc;
    FlowRuntimeConfig cfg;
    cfg.flow_dir = d;
    rc.set_runtime_config(cfg);
    const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
    char tag[64];
    std::snprintf(tag, sizeof(tag), "corrupt case %d", i);
    if (!check_impl(rep.verdict == ReplayVerdict::kCorrupt, tag, __FILE__, __LINE__)) continue;
    HG_CHECK(!rc.ready());
    HG_CHECK(rc.failed());
    HG_CHECK(rc.state() == FlowState::kReplaying);  // FAILED 承载于 failed_（头注）
    HG_CHECK(!rep.detail.empty());
  }
}

// ---- 8. 撕裂尾行（崩溃中断写）⇒ 拒服务 ------------------------------------
void test_torn_tail_rejects() {
  const std::string d = dirOf("torn");
  mkclean(d);
  writeRaw(hg::flow_store::flowShardPath(d, 20261001),
           "100|PLACED|1|HG1|accepted\n100|PLACED|2|HG2|acce");  // 末行无 \n
  ReplayController rc;
  FlowRuntimeConfig cfg;
  cfg.flow_dir = d;
  rc.set_runtime_config(cfg);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kCorrupt);
  HG_CHECK(rep.detail.find("torn tail") != std::string::npos);
  HG_CHECK(!rc.ready());
}

// ---- 9. 对账矛盾 ⇒ MISMATCH 拒服务（验收 3 / §4.5.3 ⑥ → 0x0405）----------
void test_reconcile_conflict_mismatch() {
  const std::string d = dirOf("conflict");
  mkclean(d);
  FlowStore fs;
  HG_CHECK(fs.open(hg::flow_store::flowShardPath(d, 20261001)));
  writeRec(&fs, FlowKind::kPlaced, 4, "HG4", 100, "accepted pending confirm");
  writeRec(&fs, FlowKind::kSent, 4, "HG4", 200, "submitted to CTP");
  fs.close();

  ReplayController rc;
  FlowRuntimeConfig cfg;
  cfg.flow_dir = d;
  rc.set_runtime_config(cfg);
  FakeQuery q;
  q.table.push_back(std::make_pair<std::uint64_t, OrderQueryResult>(
      4, OrderQueryResult::kConflict));
  rc.set_order_query(&q);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kMismatch);
  HG_CHECK(rep.detail.find("order_id=4") != std::string::npos);
  HG_CHECK(!rc.ready());
  HG_CHECK(rc.failed());
}

// ---- 10. 目录不可读 ⇒ OPEN_FAILED（0x3001 口径）；空目录 ⇒ READY ----------
void test_open_failed_and_fresh_dir() {
  ReplayController rc;
  FlowRuntimeConfig cfg;
  cfg.flow_dir = "/root/t037-flow-test/definitely-missing";
  rc.set_runtime_config(cfg);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOpenFailed);
  HG_CHECK(rc.state() == FlowState::kNotReady);
  HG_CHECK(!rc.ready());

  const std::string d = dirOf("fresh");
  mkclean(d);
  ReplayController rc2;
  cfg.flow_dir = d;
  rc2.set_runtime_config(cfg);
  const ReplayReport rep2 = rc2.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep2.verdict == ReplayVerdict::kOk);  // 首次启动无流水 ⇒ 直接 READY
  HG_CHECK_EQ(rep2.total_records, static_cast<std::uint64_t>(0));
  HG_CHECK(rc2.ready());
}

// ---- 11. 按日多分片升序重放 + files_skipped 计数 ----------------------------
void test_multi_shard_order_and_skip() {
  const std::string d = dirOf("multi");
  mkclean(d);
  {
    FlowStore f1;
    HG_CHECK(f1.open(hg::flow_store::flowShardPath(d, 20261001)));
    writeRec(&f1, FlowKind::kPlaced, 1, "R1", 100, "day1");
    f1.close();
  }
  {
    FlowStore f2;
    HG_CHECK(f2.open(hg::flow_store::flowShardPath(d, 20261002)));
    writeRec(&f2, FlowKind::kSent, 1, "R1", 200, "day2");
    writeRec(&f2, FlowKind::kFilled, 1, "R1", 300, "day2");
    f2.close();
  }
  writeRaw(d + "/notes.txt", "not a shard\n");

  ReplayController rc;
  FlowRuntimeConfig cfg;
  cfg.flow_dir = d;
  rc.set_runtime_config(cfg);
  const ReplayReport rep = rc.run(1770000000000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  HG_CHECK_EQ(rep.shards_read, static_cast<std::size_t>(2));
  HG_CHECK_EQ(rep.files_skipped, static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.total_records, static_cast<std::uint64_t>(3));
  HG_CHECK_EQ(rep.orders.size(), static_cast<std::size_t>(1));
  HG_CHECK(rep.orders[0].terminal);  // 跨分片生命周期闭合
  HG_CHECK_EQ(rep.pending_unsent.size(), static_cast<std::size_t>(0));
}

}  // namespace

int main() {
  if (std::system("rm -rf /root/t037-flow-test; mkdir -p /root/t037-flow-test") != 0) {
    std::printf("[ABORT] setup failed\n");
    return 2;
  }
  test_default_config_and_fsync();
  test_shard_path_and_sweep();
  test_replay_ready_progress_states();
  test_inflight_not_found_release();
  test_inflight_timeout_unknown_alert();
  test_inflight_adopt_terminal();
  test_corrupt_variants_reject();
  test_torn_tail_rejects();
  test_reconcile_conflict_mismatch();
  test_open_failed_and_fresh_dir();
  test_multi_shard_order_and_skip();
  std::printf(g_fail == 0 ? "[PASS] flow_store_replay_test\n"
                          : "[FAIL] flow_store_replay_test: %d\n",
              g_fail);
  return g_fail == 0 ? 0 : 1;
}
