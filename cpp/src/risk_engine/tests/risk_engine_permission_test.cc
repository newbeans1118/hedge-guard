// T-035 R-RISK-7 交易权限与授权单测（FR-RISK-7；未授权 / 超品种 / 超额度 / 岗位冲突 / 负责人禁操盘）
#include "risk_engine_test_util.h"

namespace {
using hg::risk_engine::LimitLayer;
using hg::risk_engine::OrderRequest;
using hg::risk_engine::RiskDecision;
using hg::risk_engine::RiskEngine;
using hg::risk_engine::RiskResult;
using hg::risk_engine::RuleId;
using hg::risk_engine::testutil::baseOrder;
using hg::risk_engine::testutil::bind;
using hg::risk_engine::testutil::FakeClock;
using hg::risk_engine::testutil::happyBatch;
using hg::risk_state::LoadBatch;
using hg::risk_state::PermissionEntry;
using hg::risk_state::RiskState;

PermissionEntry trading() {
  PermissionEntry p;
  p.allow_all_sub_accounts = true;
  p.allow_all_products = true;
  p.has_trading_post = true;
  return p;
}

void test_happy_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  HG_CHECK_EQ(eng.check(baseOrder()).decision, RiskDecision::kPass);
}

void test_no_permission_record() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  HG_CHECK(bind(&st, &eng, happyBatch(1)));
  OrderRequest q = baseOrder();
  q.operator_id = 8;  // 无权限记录
  const RiskResult r = eng.check(q);
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kPermission);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100B));
}

void test_enterprise_leader_forbidden() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.is_responsible = true;
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100F));
}

void test_risk_post_conflict() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.has_risk_post = true;  // 风控岗不得下单（前中后台分离）
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100E));
}

// T-126：「仅具结算岗」独立用例 —— 仅 `has_settle_role`（显式 `has_risk_post=false`）即命中
//   `0x100E`（`FR-RISK-7` 析取：具备风控岗 **或** 结算岗权限的账号不得下单）。
//   改前 `PermissionEntry` 无此字段 ⇒ 该账号被放行（缺陷态）。
void test_settle_role_conflict() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.has_risk_post = false;   // 显式排除风控岗 ⇒ 仅结算岗
  p.has_settle_role = true;  // 结算岗不得下单（前中后台分离）
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100E));
}

// T-126：「仅具风控岗」独立用例（与上互斥：`has_settle_role` 显式为 false）——
//   证明 `0x100E` 两支各自独立成立（析取），非依赖新字段。
void test_risk_post_only_conflict() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.has_settle_role = false;  // 显式排除结算岗 ⇒ 仅风控岗
  p.has_risk_post = true;
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100E));
}

// T-126：「仅具管理层（审批人）角色」独立用例 —— 仅 `is_management_role`
//   （显式 `is_responsible=false`）即命中 `0x100F`（`CH-010` 析取：企业负责人标记
//   **或** 管理层标记，任一独立成立即禁操盘）。改前该字段不存在 ⇒ 该账号被放行（缺陷态）。
void test_management_role_forbidden() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.is_responsible = false;     // 显式排除企业负责人标记 ⇒ 仅管理层
  p.is_management_role = true;  // 签署权与交易权互斥
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100F));
}

void test_no_trading_post() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.has_trading_post = false;
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kPermission);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100B));
}

// T-039 / CH-019：has_trading_post 的**缺陷精确形态** —— 该位位于 risk_engine.cc:420，
//   排在 0x100F（负责人 / 管理层）与 0x100E（风控岗 / 结算岗）**两关之后**，是**第四分支**。
//   ⇒ 必须构造「四冲突位全过（均显式 false）、仅 has_trading_post=false」的工况；
//   否则「因该位被拒(0x100B)」与「因岗位 / 角色冲突被拒(0x100E/0x100F)」在断言上不可区分。
//   本用例缺位即等于没测到本体。
void test_no_trading_post_after_four_bools_pass() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.has_trading_post = false;    // 唯一拒绝动因
  p.is_responsible = false;      // 四冲突位显式全过（不借缺省值）
  p.is_management_role = false;
  p.has_risk_post = false;
  p.has_settle_role = false;
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kReject);
  HG_CHECK_EQ(r.rule, RuleId::kPermission);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100B));
}

// T-039 / CH-019：同一位的**放行态** —— has_trading_post=true 且四冲突位全过 ⇒ 全链放行。
//   显式构造（不经 trading()），使「该位为 true 才放行」自成闭环；与上两条构成
//   同一位的三态（无记录 / 无交易岗 / 有交易岗）。
void test_trading_post_record_passes() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p;
  p.allow_all_sub_accounts = true;
  p.allow_all_products = true;
  p.has_trading_post = true;
  p.is_responsible = false;
  p.is_management_role = false;
  p.has_risk_post = false;
  p.has_settle_role = false;
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.decision, RiskDecision::kPass);
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0));
}

void test_sub_account_not_authorized() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.allow_all_sub_accounts = false;
  p.sub_accounts.insert(2);  // 仅授权子账户 2
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  HG_CHECK_EQ(eng.check(baseOrder()).code, static_cast<std::uint32_t>(0x100B));
}

void test_product_not_authorized() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.allow_all_products = false;
  p.products.insert(99);  // 仅授权品种 99
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100C));
}

void test_individual_quota_exceeded() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.individual_quota = 1;  // 1 手以内；指令 1 手 ⇒ 10000 > 1
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  const RiskResult r = eng.check(baseOrder());
  HG_CHECK_EQ(r.code, static_cast<std::uint32_t>(0x100D));
  HG_CHECK_EQ(r.limit_layer, LimitLayer::kAuthQuota);
  HG_CHECK(r.has_limit_layer);
}

}  // namespace

int main() {
  test_happy_passes();
  test_no_permission_record();
  test_enterprise_leader_forbidden();
  test_risk_post_conflict();
  test_settle_role_conflict();
  test_risk_post_only_conflict();
  test_management_role_forbidden();
  test_no_trading_post();
  test_no_trading_post_after_four_bools_pass();
  test_trading_post_record_passes();
  test_sub_account_not_authorized();
  test_product_not_authorized();
  test_individual_quota_exceeded();
  return hg::risk_engine::testutil::report("risk_engine_permission_test");
}
