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

void test_no_trading_post() {
  RiskState st;
  FakeClock clk(20260101);
  RiskEngine eng(&clk);
  LoadBatch b = happyBatch(1);
  PermissionEntry p = trading();
  p.has_trading_post = false;
  b.putPermission(7, p);
  HG_CHECK(bind(&st, &eng, b));
  HG_CHECK_EQ(eng.check(baseOrder()).code, static_cast<std::uint32_t>(0x100B));
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
  test_no_trading_post();
  test_sub_account_not_authorized();
  test_product_not_authorized();
  test_individual_quota_exceeded();
  return hg::risk_engine::testutil::report("risk_engine_permission_test");
}
