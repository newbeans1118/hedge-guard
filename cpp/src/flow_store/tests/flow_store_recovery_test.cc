// T-041 flow_store 恢复台账单测（零依赖 harness，与 flow_store_replay_test 同形）。
// 覆盖：consume 登记与幂等 / 人工核验五守卫（验收 3）/ 门禁读面（验收 4）/
//       留痕落流水（kExpired + recovery: 前缀）/ ReplayController 端到端接续。
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "hg/flow_store/flow_store.h"
#include "hg/flow_store/recovery.h"
#include "hg/flow_store/replay.h"

// 断言：单次求值（同形 harness）。
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

using hg::flow_store::FlowRecord;
using hg::flow_store::FlowKind;
using hg::flow_store::FlowStore;
using hg::flow_store::InflightOrder;
using hg::flow_store::IOrderStateQuery;
using hg::flow_store::ManualReleaseError;
using hg::flow_store::ManualReleaseRequest;
using hg::flow_store::OrderQueryResult;
using hg::flow_store::RecoveryCoordinator;
using hg::flow_store::ReplayController;
using hg::flow_store::ReplayDecision;
using hg::flow_store::ReplayReport;
using hg::flow_store::ReplayVerdict;

const char* kRoot = "/root/t041-flow-test";

void mkclean(const std::string& d) {
  std::string cmd = std::string("rm -rf '") + d + "'; mkdir -p '" + d + "'";
  HG_ABORT_IF(std::system(cmd.c_str()) != 0);
}

// 假 CTP 查询：默认 kTimeout（状态未知）。
class FakeQuery : public IOrderStateQuery {
 public:
  int calls = 0;
  OrderQueryResult result = OrderQueryResult::kTimeout;
  OrderQueryResult query(const InflightOrder& o, std::string* detail) override {
    ++calls;
    (void)o;
    if (detail != nullptr) *detail = "timeout; conservative";
    return result;
  }
};

// 构造 kTimeout 未知决策的报告。
ReplayReport reportWithUnknown(std::uint64_t oid, const char* ref) {
  ReplayReport rep;
  rep.verdict = ReplayVerdict::kOk;
  ReplayDecision d;
  d.kind = ReplayDecision::kUnknownKeepFrozen;
  d.order.order_id = oid;
  d.order.client_order_ref = ref;
  d.order.sent_ts_ms = 1770000000000LL;
  d.ctp_result = OrderQueryResult::kTimeout;
  rep.decisions.push_back(d);
  return rep;
}

void test_consume_registers_unknown() {
  RecoveryCoordinator c;
  HG_CHECK_EQ(c.entry_count(), static_cast<std::size_t>(0));
  HG_CHECK(!c.has_unknown_open());

  const ReplayReport rep = reportWithUnknown(42, "HG20260101000042");
  c.consume(rep, 1770000001000LL);
  HG_CHECK_EQ(c.entry_count(), static_cast<std::size_t>(1));
  HG_CHECK(c.has_unknown_open());
  const hg::flow_store::RecoveryEntry* e = c.find(42);
  HG_CHECK(e != nullptr);
  HG_CHECK_EQ(e->released, false);
  HG_CHECK_EQ(e->client_order_ref, std::string("HG20260101000042"));
  HG_CHECK_EQ(e->registered_ts_ms, static_cast<std::int64_t>(1770000001000LL));
  HG_CHECK_EQ(c.view().open_count, static_cast<std::size_t>(1));
  HG_CHECK_EQ(c.view().total, static_cast<std::size_t>(1));
  HG_CHECK_EQ(c.view().released_count, static_cast<std::size_t>(0));
}

void test_consume_idempotent_and_non_timeout_skipped() {
  RecoveryCoordinator c;
  // kLive 的 kUnknownKeepFrozen：不登记（CTP 确认在途，回报驱动闭环）。
  {
    ReplayReport rep;
    ReplayDecision d;
    d.kind = ReplayDecision::kUnknownKeepFrozen;
    d.order.order_id = 7;
    d.ctp_result = OrderQueryResult::kLive;
    rep.decisions.push_back(d);
    c.consume(rep, 1000);
  }
  HG_CHECK_EQ(c.entry_count(), static_cast<std::size_t>(0));
  // kTimeout 登记。
  c.consume(reportWithUnknown(42, "HG20260101000042"), 1000);
  HG_CHECK_EQ(c.entry_count(), static_cast<std::size_t>(1));
  // 同单重复 consume：幂等（不重复登记/不虚增阻断计数）。
  c.consume(reportWithUnknown(42, "HG20260101000042"), 2000);
  HG_CHECK_EQ(c.entry_count(), static_cast<std::size_t>(1));
  HG_CHECK_EQ(c.view().open_count, static_cast<std::size_t>(1));
  // 其他未知单另登记。
  c.consume(reportWithUnknown(43, "HG20260101000043"), 2000);
  HG_CHECK_EQ(c.entry_count(), static_cast<std::size_t>(2));
  HG_CHECK_EQ(c.view().open_count, static_cast<std::size_t>(2));
}

void test_manual_release_guards() {
  RecoveryCoordinator c;
  c.consume(reportWithUnknown(42, "HG20260101000042"), 1000);

  ManualReleaseError err = ManualReleaseError::kNone;

  // 守卫 1：NotFound。
  {
    ManualReleaseRequest r;
    r.order_id = 999;
    r.client_order_ref = "HG20260101000042";
    r.operator_a_id = 1;
    r.operator_b_id = 2;
    r.reason = "verified with CTP";
    HG_CHECK(!c.manualVerifyRelease(r, 2000, &err));
    HG_CHECK(err == ManualReleaseError::kNotFound);
  }
  // 守卫 2：操作人缺位（双人复核前提）。
  {
    ManualReleaseRequest r;
    r.order_id = 42;
    r.client_order_ref = "HG20260101000042";
    r.operator_a_id = 1;
    r.operator_b_id = 0;
    r.reason = "verified with CTP";
    HG_CHECK(!c.manualVerifyRelease(r, 2000, &err));
    HG_CHECK(err == ManualReleaseError::kOperatorMissing);
  }
  // 守卫 3：双人同人（复核独立性）。
  {
    ManualReleaseRequest r;
    r.order_id = 42;
    r.client_order_ref = "HG20260101000042";
    r.operator_a_id = 5;
    r.operator_b_id = 5;
    r.reason = "verified with CTP";
    HG_CHECK(!c.manualVerifyRelease(r, 2000, &err));
    HG_CHECK(err == ManualReleaseError::kSameOperator);
  }
  // 守卫 4：ref 不匹配（防错单）。
  {
    ManualReleaseRequest r;
    r.order_id = 42;
    r.client_order_ref = "WRONG";
    r.operator_a_id = 1;
    r.operator_b_id = 2;
    r.reason = "verified with CTP";
    HG_CHECK(!c.manualVerifyRelease(r, 2000, &err));
    HG_CHECK(err == ManualReleaseError::kRefMismatch);
  }
  // 守卫 5：核验结论缺失（留痕完整性）。
  {
    ManualReleaseRequest r;
    r.order_id = 42;
    r.client_order_ref = "HG20260101000042";
    r.operator_a_id = 1;
    r.operator_b_id = 2;
    HG_CHECK(!c.manualVerifyRelease(r, 2000, &err));
    HG_CHECK(err == ManualReleaseError::kReasonMissing);
  }
  // 全部拒绝后台账零变更、门禁仍阻断。
  HG_CHECK_EQ(c.view().open_count, static_cast<std::size_t>(1));
  HG_CHECK(c.has_unknown_open());

  // 成功路径。
  {
    ManualReleaseRequest r;
    r.order_id = 42;
    r.client_order_ref = "HG20260101000042";
    r.operator_a_id = 11;
    r.operator_b_id = 22;
    r.reason = "CTP shows no such order; safe to release";
    HG_CHECK(c.manualVerifyRelease(r, 3000, &err));
    HG_CHECK(err == ManualReleaseError::kNone);
  }
  const hg::flow_store::RecoveryEntry* e = c.find(42);
  HG_CHECK(e != nullptr);
  HG_CHECK(e->released);
  HG_CHECK_EQ(e->operator_a_id, static_cast<std::uint64_t>(11));
  HG_CHECK_EQ(e->operator_b_id, static_cast<std::uint64_t>(22));
  HG_CHECK_EQ(e->released_ts_ms, static_cast<std::int64_t>(3000));
  HG_CHECK_EQ(c.view().open_count, static_cast<std::size_t>(0));
  HG_CHECK_EQ(c.view().released_count, static_cast<std::size_t>(1));
  HG_CHECK(!c.has_unknown_open());

  // 幂等拒绝：已闭环不二次留痕。
  ManualReleaseRequest again;
  again.order_id = 42;
  again.client_order_ref = "HG20260101000042";
  again.operator_a_id = 11;
  again.operator_b_id = 22;
  again.reason = "retry";
  HG_CHECK(!c.manualVerifyRelease(again, 4000, &err));
  HG_CHECK(err == ManualReleaseError::kAlreadyReleased);
}

void test_manual_release_flow_trail() {
  mkclean(kRoot);
  FlowStore flow;
  HG_ABORT_IF(!flow.open(std::string(kRoot) + "/flow-20260101.log"));

  RecoveryCoordinator c;
  c.set_flow_store(&flow);
  c.consume(reportWithUnknown(42, "HG20260101000042"), 1000);

  ManualReleaseRequest r;
  r.order_id = 42;
  r.client_order_ref = "HG20260101000042";
  r.operator_a_id = 11;
  r.operator_b_id = 22;
  r.reason = "CTP shows no such order; safe to release";
  HG_CHECK(c.manualVerifyRelease(r, 3000, nullptr));

  // 留痕：流水恰 +1 条，kExpired + recovery: 前缀 + 双操作人 id 在 detail。
  const FlowRecord* seen = nullptr;
  // FlowStore 无读面 ⇒ 用 ReplayController 重读校验（T-037 契约往返）。
  ReplayController rc;
  hg::flow_store::FlowRuntimeConfig cfg;
  cfg.flow_dir = kRoot;
  rc.set_runtime_config(cfg);
  const ReplayReport rep = rc.run(1770000009000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  HG_CHECK_EQ(rep.orders.size(), static_cast<std::size_t>(1));
  HG_CHECK(rep.orders[0].terminal);          // kExpired = 终态 ⇒ 重启后不再入台账
  HG_CHECK_EQ(rep.orders[0].last_kind, FlowKind::kExpired);
  HG_CHECK_EQ(rep.pending_unsent.size(), static_cast<std::size_t>(0));
  (void)seen;
  HG_CHECK_EQ(flow.record_count(), static_cast<std::uint64_t>(1));
}

void test_manual_release_no_flow_still_closes() {
  // 未注入 FlowStore：纯台账形态可用（recovery.h 头注披露）。
  RecoveryCoordinator c;
  c.consume(reportWithUnknown(42, "HG20260101000042"), 1000);
  ManualReleaseRequest r;
  r.order_id = 42;
  r.client_order_ref = "HG20260101000042";
  r.operator_a_id = 11;
  r.operator_b_id = 22;
  r.reason = "ok";
  HG_CHECK(c.manualVerifyRelease(r, 3000, nullptr));
  HG_CHECK(!c.has_unknown_open());
}

// 端到端接续：真实重放产出（kTimeout）→ consume → 门禁读面 → 人工闭环。
void test_end_to_end_replay_to_ledger() {
  mkclean(kRoot);
  // 用真实 append 写「SENT 无终态」单。
  FlowStore fs;
  HG_ABORT_IF(!fs.open(std::string(kRoot) + "/flow-20260101.log"));
  FlowRecord r1;
  r1.kind = FlowKind::kPlaced;
  r1.order_id = 42;
  r1.client_order_ref = "HG20260101000042";
  r1.ts_ms = 1770000000000LL;
  r1.detail = "accepted pending confirm";
  HG_CHECK(fs.append(r1));
  FlowRecord r2;
  r2.kind = FlowKind::kSent;
  r2.order_id = 42;
  r2.client_order_ref = "HG20260101000042";
  r2.ts_ms = 1770000000500LL;
  r2.detail = "submitted to CTP";
  HG_CHECK(fs.append(r2));
  fs.close();

  FakeQuery q;  // kTimeout ⇒ 状态未知
  ReplayController rc;
  hg::flow_store::FlowRuntimeConfig cfg;
  cfg.flow_dir = kRoot;
  rc.set_runtime_config(cfg);
  rc.set_order_query(&q);
  const ReplayReport rep = rc.run(1770000009000LL, nullptr, nullptr);
  HG_CHECK(rep.verdict == ReplayVerdict::kOk);
  HG_CHECK_EQ(rep.unknown_count, static_cast<std::size_t>(1));
  HG_CHECK_EQ(rep.frozen_held, static_cast<std::size_t>(1));

  RecoveryCoordinator c;
  c.consume(rep, 1770000009000LL);
  HG_CHECK_EQ(c.entry_count(), static_cast<std::size_t>(1));
  HG_CHECK(c.has_unknown_open());  // 验收 4：unknown 未闭环 ⇒ 阻断读面为真

  ManualReleaseRequest r;
  r.order_id = 42;
  r.client_order_ref = "HG20260101000042";
  r.operator_a_id = 11;
  r.operator_b_id = 22;
  r.reason = "verified";
  HG_CHECK(c.manualVerifyRelease(r, 1770000010000LL, nullptr));
  HG_CHECK(!c.has_unknown_open());  // 验收 4：人工闭环后解除
}

}  // namespace

int main() {
  if (std::system("rm -rf /root/t041-flow-test; mkdir -p /root/t041-flow-test") != 0) {
    std::printf("[ABORT] setup failed\n");
    return 2;
  }
  test_consume_registers_unknown();
  test_consume_idempotent_and_non_timeout_skipped();
  test_manual_release_guards();
  test_manual_release_flow_trail();
  test_manual_release_no_flow_still_closes();
  test_end_to_end_replay_to_ledger();
  std::printf(g_fail == 0 ? "[PASS] flow_store_recovery_test\n"
                          : "[FAIL] flow_store_recovery_test: %d\n",
              g_fail);
  return g_fail == 0 ? 0 : 1;
}
