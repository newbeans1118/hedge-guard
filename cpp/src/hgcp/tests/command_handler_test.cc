// T-038 控制指令消费单测（hgcp 侧，真实 protobuf 编解码）。
// 覆盖：17 指令消费盘点 / CMD_UPDATE_PERMISSION 五布尔映射与生效主链（验收 5）/
//       param_version 单调 0x0403（验收 2）/ 分批与 EVT_SYSTEM_STATE（验收 3）/
//       R-CONFIG-8 下限拒绝（验收 4）/ 单键覆盖语义 / 四布尔过仍因 has_trading_post 拒。
#include "hg/hgcp/command_handler.h"

#include <cstring>

#include "hg/hgcp/protocol.h"
#include "hgcp.pb.h"
#include "hgcp_test_util.h"
#include "hg/hgcp/payload_codec.h"
#include "hg/risk_engine/order_request.h"
#include "hg/risk_engine/risk_engine.h"

// 前置断言：失败即记 FAIL 并从用例返回（表达式单次求值）。
#define HG_REQUIRE(cond)                                                        \
  do {                                                                          \
    const bool _hg_ok = static_cast<bool>(cond);                                \
    ::hg::hgcp::testutil::check_impl(_hg_ok, #cond, __FILE__, __LINE__);        \
    if (!_hg_ok) {                                                              \
      return;                                                                   \
    }                                                                           \
  } while (0)
#define HG_REQUIRE_EQ(a, b)                                                     \
  do {                                                                          \
    const long long _hg_a = static_cast<long long>(a);                          \
    const long long _hg_b = static_cast<long long>(b);                          \
    ::hg::hgcp::testutil::check_impl(_hg_a == _hg_b, #a " == " #b, __FILE__,    \
                                     __LINE__);                                 \
    if (_hg_a != _hg_b) {                                                       \
      return;                                                                   \
    }                                                                           \
  } while (0)

namespace {

namespace pbv1 = ::hgcp::v1;
using hg::hgcp::CommandHandler;
using hg::hgcp::CommandOutcome;
using hg::hgcp::MessageType;
using hg::hgcp::PbPayloadCodec;
using hg::risk_state::RiskState;

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }

template <typename M>
std::vector<std::uint8_t> enc(const M& m) {
  std::vector<std::uint8_t> b;
  if (!PbPayloadCodec::encode(m, &b)) {
    std::printf("[FATAL] encode failed\n");
    std::exit(2);
  }
  return b;
}

// 解码回执 body。
bool decode_ack(const std::vector<std::uint8_t>& body, pbv1::MsgAck* a) {
  return PbPayloadCodec::decode(body, a);
}
bool decode_err(const std::vector<std::uint8_t>& body, pbv1::MsgError* e) {
  return PbPayloadCodec::decode(body, e);
}

// 最小合法全量批（品种 10，可过不变量）。
pbv1::CmdLoadRiskParams make_load_batch(std::uint64_t ver, std::uint32_t batch_index,
                                        std::uint32_t batch_total) {
  pbv1::CmdLoadRiskParams b;
  b.set_param_version(ver);
  b.set_batch_index(batch_index);
  b.set_batch_total(batch_total);
  pbv1::VarietySpec* v = b.add_varieties();
  v->set_product_id(10);
  v->set_product_code("cu");
  v->set_category(pbv1::PRODUCT_CATEGORY_COMMODITY);
  v->set_status(pbv1::PRODUCT_STATUS_ENABLED);
  v->set_unit_factor("1.0000");
  pbv1::WhitelistEntry* w = b.add_whitelist();
  w->set_sub_account_id(1);
  w->add_product_ids(10);
  pbv1::QuotaEntry* q = b.add_quotas();
  q->set_limit_layer(pbv1::LIMIT_LAYER_SUB_PRODUCT);
  q->set_sub_account_id(1);
  q->set_product_id(10);
  q->set_quota_limit("100.0000");
  q->set_used("0.0000");
  q->set_frozen("0.0000");
  pbv1::PermissionEntry* p = b.add_permissions();
  p->set_operator_id(7);
  p->set_sub_account_id(1);
  p->add_product_ids(10);
  p->set_is_enterprise_leader(false);
  p->set_is_management_role(false);
  p->set_has_risk_role(false);
  p->set_has_settle_role(false);
  p->set_has_trading_post(true);
  return b;
}

// CMD_UPDATE_PERMISSION 单条。
pbv1::CmdUpdatePermission make_perm_cmd(std::uint64_t ver, std::uint64_t op) {
  pbv1::CmdUpdatePermission c;
  c.set_param_version(ver);
  pbv1::PermissionEntry* e = c.add_entries();
  e->set_operator_id(op);
  e->set_sub_account_id(1);
  e->add_product_ids(10);
  e->set_is_enterprise_leader(false);
  e->set_is_management_role(false);
  e->set_has_risk_role(false);
  e->set_has_settle_role(false);
  e->set_has_trading_post(true);
  e->set_quota_limit("50.0000");
  return c;
}

// 断言辅助：ACK result=0。
bool is_ok_ack(const CommandOutcome& o) {
  if (o.reply.type != mt(MessageType::kMsgAck)) return false;
  pbv1::MsgAck a;
  if (!decode_ack(o.reply.body, &a)) return false;
  return a.result() == 0;
}
std::uint32_t err_code_of(const CommandOutcome& o) {
  pbv1::MsgError e;
  if (!decode_err(o.reply.body, &e)) return 0xFFFFFFFFu;
  return e.err_code();
}

// ── 1. 盘点：17 种控制指令全部可消费（consumed=true 且有回执）─────────────
void test_all_17_consumed() {
  RiskState st;
  CommandHandler h(&st);
  const std::uint16_t cmds[] = {
      mt(MessageType::kCmdPlaceOrder),           mt(MessageType::kCmdConfirmOrder),
      mt(MessageType::kCmdCancelOrder),          mt(MessageType::kCmdLoadRiskParams),
      mt(MessageType::kCmdUpdateWhitelist),      mt(MessageType::kCmdUpdateQuota),
      mt(MessageType::kCmdUpdatePositionLimit),  mt(MessageType::kCmdUpdateRiskThreshold),
      mt(MessageType::kCmdUpdatePermission),     mt(MessageType::kCmdUpdateExposure),
      mt(MessageType::kCmdSetTradingSuspend),    mt(MessageType::kCmdSetReadyState),
      mt(MessageType::kCmdReload),               mt(MessageType::kCmdTriggerReplay),
      mt(MessageType::kCmdQueryStatus),          mt(MessageType::kCmdFundTransfer),
      mt(MessageType::kCmdQueryFundResult),
  };
  for (int i = 0; i < 17; ++i) {
    // 分批加载指令单独构造（其余用空 body：proto3 全默认值合法）。
    std::vector<std::uint8_t> body;
    if (cmds[i] == mt(MessageType::kCmdLoadRiskParams)) {
      body = enc(make_load_batch(1, 0, 1));
    }
    const CommandOutcome o = h.handle(cmds[i], body, 100 + i, 1000);
    if (!o.consumed) {
      std::printf("[FAIL] cmd 0x%04X not consumed\n", cmds[i]);
    }
    HG_CHECK(o.consumed);
    HG_CHECK(!o.reply.body.empty() || o.reply.type != 0u);
  }
  // 非控制指令类型不消费。
  HG_CHECK(!h.handle(mt(MessageType::kMsgHello), {}, 1, 0).consumed);
  HG_CHECK(!h.handle(mt(MessageType::kEvtSystemState), {}, 1, 0).consumed);
}


// ── 2. PERMISSION 主链（验收 5）：解析 → 装填 → 生效 → 快照可查 ─────────────
void test_permission_mapping_and_effect() {
  RiskState st;
  CommandHandler h(&st);
  // 先全量加载（版本 1）。
  const CommandOutcome o1 = h.handle(mt(MessageType::kCmdLoadRiskParams),
                                     enc(make_load_batch(1, 0, 1)), 1, 1000);
  HG_CHECK(is_ok_ack(o1));
  HG_CHECK_EQ(o1.events.size(), static_cast<std::size_t>(1));  // EVT_SYSTEM_STATE
  HG_CHECK(st.ready());

  // CMD_UPDATE_PERMISSION（版本 2）：交易岗用户 77。
  pbv1::CmdUpdatePermission c = make_perm_cmd(2, 77);
  const CommandOutcome o2 = h.handle(mt(MessageType::kCmdUpdatePermission), enc(c), 2, 1000);
  HG_CHECK(is_ok_ack(o2));
  pbv1::MsgAck a2;
  HG_CHECK(decode_ack(o2.reply.body, &a2));
  HG_CHECK_EQ(a2.applied_version(), static_cast<std::uint64_t>(2));

  // 逐字段映射断言（快照侧）。
  const hg::risk_state::PermissionEntry* p = st.snapshot().findPermission(77);
  HG_REQUIRE(p != nullptr);
  HG_CHECK(p->has_trading_post);        // (9) 准入类：true ⇒ 后续 canTrade 可过
  HG_CHECK(!p->is_responsible);         // (5) is_enterprise_leader → is_responsible
  HG_CHECK(!p->is_management_role);     // (6)
  HG_CHECK(!p->has_risk_post);          // (7) has_risk_role → has_risk_post
  HG_CHECK(!p->has_settle_role);        // (8)
  HG_CHECK_EQ(p->sub_accounts.size(), static_cast<std::size_t>(1));
  HG_CHECK(p->sub_accounts.find(1u) != p->sub_accounts.end());
  HG_CHECK(p->products.find(10u) != p->products.end());
  HG_CHECK_EQ(p->individual_quota, static_cast<hg::risk_state::Fixed>(500000));  // 50 * 1e4
}

// ── 3. 单键覆盖语义：同 operator_id 第二条覆盖第一条 ─────────────────────
void test_permission_single_key_override() {
  RiskState st;
  CommandHandler h(&st);
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdLoadRiskParams), enc(make_load_batch(1, 0, 1)), 1, 0)));
  // 第一条：品种 10。
  pbv1::CmdUpdatePermission c1 = make_perm_cmd(2, 88);
  HG_CHECK(is_ok_ack(h.handle(mt(MessageType::kCmdUpdatePermission), enc(c1), 2, 0)));
  // 第二条（同 op=88）：品种 20 ⇒ 覆盖（非追加）。
  pbv1::CmdUpdatePermission c2 = make_perm_cmd(3, 88);
  c2.mutable_entries(0)->clear_product_ids();
  c2.mutable_entries(0)->add_product_ids(20);
  HG_CHECK(is_ok_ack(h.handle(mt(MessageType::kCmdUpdatePermission), enc(c2), 3, 0)));

  const hg::risk_state::PermissionEntry* p = st.snapshot().findPermission(88);
  HG_REQUIRE(p != nullptr);
  HG_CHECK_EQ(p->products.size(), static_cast<std::size_t>(1));  // 覆盖后恰 1 条
  HG_CHECK(p->products.find(20u) != p->products.end());
  HG_CHECK(p->products.find(10u) == p->products.end());  // 旧品种被覆盖清除
}

// ── 4. 版本单调（验收 2）：== 与 < 均 0x0403 并丢弃 ─────────────────────
void test_param_version_monotonic() {
  RiskState st;
  CommandHandler h(&st);
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdLoadRiskParams), enc(make_load_batch(5, 0, 1)), 1, 0)));
  // 同版本（==）⇒ 0x0403。
  {
    const CommandOutcome o =
        h.handle(mt(MessageType::kCmdUpdatePermission), enc(make_perm_cmd(5, 66)), 2, 0);
    HG_CHECK(!is_ok_ack(o));
    HG_CHECK_EQ(err_code_of(o), static_cast<std::uint32_t>(0x0403));
    HG_CHECK(st.snapshot().findPermission(66) == nullptr);  // 已丢弃
  }
  // 倒退（<）⇒ 0x0403。
  {
    const CommandOutcome o =
        h.handle(mt(MessageType::kCmdUpdatePermission), enc(make_perm_cmd(4, 66)), 3, 0);
    HG_CHECK(!is_ok_ack(o));
    HG_CHECK_EQ(err_code_of(o), static_cast<std::uint32_t>(0x0403));
  }
  // 更新版本 ⇒ 过。
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdUpdatePermission), enc(make_perm_cmd(6, 66)), 4, 0)));
  HG_CHECK(st.snapshot().findPermission(66) != nullptr);
}

// ── 5. 分批（验收 3）：3 批、每批 ACK、完成发 EVT_SYSTEM_STATE(READY, ver) ──
void test_load_batching_and_ready_event() {
  RiskState st;
  CommandHandler h(&st);
  // 批 0（品种 10）。
  CommandOutcome o = h.handle(mt(MessageType::kCmdLoadRiskParams),
                              enc(make_load_batch(10, 0, 3)), 1, 1000);
  HG_CHECK(is_ok_ack(o));
  HG_CHECK_EQ(o.events.size(), static_cast<std::size_t>(0));  // 未完成无事件
  HG_CHECK(!st.ready());
  // 批 1（品种 11）。
  {
    pbv1::CmdLoadRiskParams b = make_load_batch(10, 1, 3);
    b.mutable_varieties(0)->set_product_id(11);
    b.mutable_quotas(0)->set_product_id(11);
    o = h.handle(mt(MessageType::kCmdLoadRiskParams), enc(b), 2, 1000);
    HG_CHECK(is_ok_ack(o));
  }
  // 批 2（品种 12）＝收齐 ⇒ 生效 + 事件。
  {
    pbv1::CmdLoadRiskParams b = make_load_batch(10, 2, 3);
    b.mutable_varieties(0)->set_product_id(12);
    b.mutable_quotas(0)->set_product_id(12);
    o = h.handle(mt(MessageType::kCmdLoadRiskParams), enc(b), 3, 1234);
    HG_CHECK(is_ok_ack(o));
    HG_REQUIRE_EQ(o.events.size(), static_cast<std::size_t>(1));
    pbv1::EvtSystemState ev;
    HG_CHECK(PbPayloadCodec::decode(o.events[0].body, &ev));
    HG_CHECK_EQ(ev.state(), pbv1::SERVER_STATE_READY);
    HG_CHECK_EQ(ev.param_version_applied(), static_cast<std::uint64_t>(10));
    HG_CHECK_EQ(ev.ts_ms(), static_cast<std::int64_t>(1234));
  }
  HG_CHECK(st.ready());
  HG_CHECK_EQ(st.param_version(), static_cast<std::uint64_t>(10));
  HG_CHECK(st.snapshot().findVariety(10) != nullptr);
  HG_CHECK(st.snapshot().findVariety(11) != nullptr);
  HG_CHECK(st.snapshot().findVariety(12) != nullptr);
  // 全部权限（批 0 的 op=7）随批发布。
  HG_CHECK(st.snapshot().findPermission(7) != nullptr);
}



// ── 6. R-CONFIG-8（验收 4）：阈值低于合规下限 ⇒ 拒绝 ──────────────────────
void test_risk_threshold_floor() {
  RiskState st;
  CommandHandler h(&st);
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdLoadRiskParams), enc(make_load_batch(1, 0, 1)), 1, 0)));

  pbv1::CmdUpdateRiskThreshold c;
  c.set_param_version(2);
  pbv1::RiskThresholds* th = c.mutable_thresholds();
  th->set_scale_ratio_commodity("0.850000");  // < 0.90 下限
  th->set_scale_ratio_trade("0.800000");
  th->set_term_max_months(12);
  th->set_term_grace_days(30);
  th->set_price_warn_pct("0.020000");
  th->set_price_block_pct("0.050000");
  th->set_freq_intraday_warn(6);
  th->set_freq_intraday_block(10);
  th->set_freq_7d_rounds_warn(5);
  th->set_freq_min_hold_days(3);

  const CommandOutcome o = h.handle(mt(MessageType::kCmdUpdateRiskThreshold), enc(c), 2, 0);
  HG_CHECK(!is_ok_ack(o));
  HG_CHECK_EQ(err_code_of(o), static_cast<std::uint32_t>(0x0104));  // 取值域拒绝
  // trade 档同样受限。
  th->set_scale_ratio_commodity("0.900000");
  th->set_scale_ratio_trade("0.790000");
  {
    const CommandOutcome o2 = h.handle(mt(MessageType::kCmdUpdateRiskThreshold), enc(c), 3, 0);
    HG_CHECK(!is_ok_ack(o2));
    HG_CHECK_EQ(err_code_of(o2), static_cast<std::uint32_t>(0x0104));
  }
  // 恢复合规值 ⇒ 过，且阈值在快照中生效（引擎消费键名）。
  th->set_scale_ratio_trade("0.800000");
  HG_CHECK(is_ok_ack(h.handle(mt(MessageType::kCmdUpdateRiskThreshold), enc(c), 4, 0)));
  HG_CHECK_EQ(st.snapshot().thresholdFixed("hedge.scale.ratio_commodity", 0),
              static_cast<hg::risk_state::Fixed>(900000));
  HG_CHECK_EQ(st.snapshot().thresholdInt("risk.term.max_months", 0), 12);
  HG_CHECK_EQ(st.snapshot().thresholdFixed("risk.price.warn_pct", 0),
              static_cast<hg::risk_state::Fixed>(20000));
}

// ── 7. 六类增量指令各自生效（WHITELIST / QUOTA / POSLIMIT / EXPOSURE / SUSPEND）──
void test_incremental_cmds_effect() {
  RiskState st;
  CommandHandler h(&st);
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdLoadRiskParams), enc(make_load_batch(1, 0, 1)), 1, 0)));

  // CMD_UPDATE_WHITELIST：加品种 20（生效后快照含白名单成员；既有数据保留）。
  {
    pbv1::CmdUpdateWhitelist c;
    c.set_param_version(2);
    c.set_sub_account_id(1);
    c.add_add_product_ids(20);
    const CommandOutcome o = h.handle(mt(MessageType::kCmdUpdateWhitelist), enc(c), 2, 0);
    HG_CHECK(is_ok_ack(o));
    const hg::risk_state::QuotaEntry* q =
        st.snapshot().findQuota(hg::risk_state::QuotaLayer::kSubProduct, 1, 10, 0);
    HG_CHECK(q != nullptr);  // 增量语义：既有数据保留
  }
  // CMD_UPDATE_QUOTA：层① 调整上限。
  {
    pbv1::CmdUpdateQuota c;
    c.set_param_version(3);
    pbv1::QuotaEntry* q = c.add_entries();
    q->set_limit_layer(pbv1::LIMIT_LAYER_SUB_PRODUCT);
    q->set_sub_account_id(1);
    q->set_product_id(10);
    q->set_quota_limit("200.0000");
    q->set_used("5.0000");
    q->set_frozen("1.0000");
    const CommandOutcome o = h.handle(mt(MessageType::kCmdUpdateQuota), enc(c), 3, 0);
    HG_CHECK(is_ok_ack(o));
    const hg::risk_state::QuotaEntry* qq =
        st.snapshot().findQuota(hg::risk_state::QuotaLayer::kSubProduct, 1, 10, 0);
    HG_REQUIRE(qq != nullptr);
    HG_CHECK_EQ(qq->limit, static_cast<hg::risk_state::Fixed>(2000000));  // 200 * 1e4
    HG_CHECK_EQ(qq->used, static_cast<hg::risk_state::Fixed>(50000));
  }
  // CMD_UPDATE_POSITION_LIMIT。
  {
    pbv1::CmdUpdatePositionLimit c;
    c.set_param_version(4);
    pbv1::PositionLimitEntry* e = c.add_entries();
    e->set_sub_account_id(1);
    e->set_product_id(10);
    e->set_instrument_id("100");
    e->set_limit("300.0000");
    const CommandOutcome o = h.handle(mt(MessageType::kCmdUpdatePositionLimit), enc(c), 4, 0);
    HG_CHECK(is_ok_ack(o));
    const hg::risk_state::PositionLimitEntry* pl = st.snapshot().findPositionLimit(1, 10, 100);
    HG_REQUIRE(pl != nullptr);
    HG_CHECK_EQ(pl->limit, static_cast<hg::risk_state::Fixed>(3000000));
  }
  // CMD_UPDATE_EXPOSURE。
  {
    pbv1::CmdUpdateExposure c;
    c.set_param_version(5);
    pbv1::ExposureEntry* e = c.add_entries();
    e->set_sub_account_id(1);
    e->set_product_id(10);
    e->set_direction(pbv1::DIRECTION_BUY);
    e->set_contract_unexecuted_qty(400000);
    e->set_inventory_qty(300000);
    e->set_production_plan_qty(300000);
    e->set_total_qty(1000000);
    e->set_unit_factor("1.0000");
    e->set_snapshot_time(777);
    e->set_snapshot_id(99);
    const CommandOutcome o = h.handle(mt(MessageType::kCmdUpdateExposure), enc(c), 5, 0);
    HG_CHECK(is_ok_ack(o));
    const hg::risk_state::ExposureEntry* ex =
        st.snapshot().findExposure(1, 10, hg::risk_state::ExposureDirection::kBuy);
    HG_REQUIRE(ex != nullptr);
    HG_CHECK_EQ(ex->exposure_qty, static_cast<hg::risk_state::Fixed>(1000000));
    HG_CHECK_EQ(ex->snapshot_time_ms, static_cast<std::int64_t>(777));
    HG_CHECK_EQ(std::string(ex->snapshot_id), std::string("99"));
  }
  // CMD_SET_TRADING_SUSPEND（全账户）。
  {
    pbv1::CmdSetTradingSuspend c;
    c.set_param_version(6);
    c.set_sub_account_id(0);
    c.set_scan_scope(pbv1::SCAN_SCOPE_OPEN_ONLY);
    c.set_suspended(true);
    c.set_operator_id(9);
    const CommandOutcome o = h.handle(mt(MessageType::kCmdSetTradingSuspend), enc(c), 6, 0);
    HG_CHECK(is_ok_ack(o));
    HG_CHECK(st.snapshot().hasThreshold("risk.trading.suspend"));
    HG_CHECK(st.snapshot().thresholdBool("risk.trading.suspend", false));
  }
}

// ── 8. 就绪门卫 + 状态指令 ───────────────────────────────────────────────
void test_readiness_gate_and_status() {
  RiskState st;
  CommandHandler h(&st);
  // 未就绪 ⇒ 交易指令 0x0401。
  {
    const CommandOutcome o =
        h.handle(mt(MessageType::kCmdPlaceOrder), enc(pbv1::CmdPlaceOrder()), 1, 0);
    HG_CHECK(!is_ok_ack(o));
    HG_CHECK_EQ(err_code_of(o), static_cast<std::uint32_t>(0x0401));
  }
  // CMD_QUERY_STATUS：受理 + detail。
  {
    const CommandOutcome o =
        h.handle(mt(MessageType::kCmdQueryStatus), enc(pbv1::CmdQueryStatus()), 2, 0);
    HG_CHECK(is_ok_ack(o));
    pbv1::MsgAck a;
    HG_CHECK(decode_ack(o.reply.body, &a));
    HG_CHECK(a.detail().find("ready=false") != std::string::npos);
  }
  // 就绪后 ⇒ 交易指令受理（生效归 T-036，detail 诚实标注）。
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdLoadRiskParams), enc(make_load_batch(1, 0, 1)), 3, 0)));
  {
    const CommandOutcome o =
        h.handle(mt(MessageType::kCmdPlaceOrder), enc(pbv1::CmdPlaceOrder()), 4, 0);
    HG_CHECK(is_ok_ack(o));
    pbv1::MsgAck a;
    HG_CHECK(decode_ack(o.reply.body, &a));
    HG_CHECK(a.detail().find("pending T-036") != std::string::npos);
  }
  // CMD_RELOAD / CMD_TRIGGER_REPLAY / CMD_SET_READY_STATE / CONFIRM / CANCEL /
  // FUND / QUERY_FUND 均受理。
  HG_CHECK(is_ok_ack(h.handle(mt(MessageType::kCmdReload), enc(pbv1::CmdReload()), 5, 0)));
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdTriggerReplay), enc(pbv1::CmdTriggerReplay()), 6, 0)));
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdSetReadyState), enc(pbv1::CmdSetReadyState()), 7, 0)));
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdConfirmOrder), enc(pbv1::CmdConfirmOrder()), 8, 0)));
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdCancelOrder), enc(pbv1::CmdCancelOrder()), 9, 0)));
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdFundTransfer), enc(pbv1::CmdFundTransfer()), 10, 0)));
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdQueryFundResult), enc(pbv1::CmdQueryFundResult()), 11, 0)));
}
// ── 9. PERMISSION 生效 → 判定面联动（验收 6 补充判据）────────────────────
//   ★「四布尔全过、仍因 has_trading_post=false 被拒」的第四分支精确形态：
//     经 CMD_UPDATE_PERMISSION 装填进快照的权限，被判定面（canTrade —— 与引擎
//     evalPermission 共用同一组纯函数 permissionLeaderForbidden / permissionPostConflict，
//     并含 has_trading_post 准入判定）正确消费：禁类全 false + 无交易岗 ⇒ 拒；
//     有交易岗 ⇒ 放行。（引擎 0x100B 码值级断言已由 risk_engine_permission_test 承载。）
void test_permission_engine_integration() {
  RiskState st;
  CommandHandler h(&st);
  HG_CHECK(is_ok_ack(
      h.handle(mt(MessageType::kCmdLoadRiskParams), enc(make_load_batch(1, 0, 1)), 1, 0)));

  // op=55：四布尔禁类全过（全 false），但 has_trading_post=false ⇒ 判定面拒绝。
  {
    pbv1::CmdUpdatePermission c = make_perm_cmd(2, 55);
    c.mutable_entries(0)->set_has_trading_post(false);
    HG_CHECK(is_ok_ack(h.handle(mt(MessageType::kCmdUpdatePermission), enc(c), 2, 0)));
  }
  // op=66：has_trading_post=true ⇒ 放行。
  {
    pbv1::CmdUpdatePermission c = make_perm_cmd(3, 66);
    c.mutable_entries(0)->set_has_trading_post(true);
    HG_CHECK(is_ok_ack(h.handle(mt(MessageType::kCmdUpdatePermission), enc(c), 3, 0)));
  }
  // op=77：has_risk_role=true（禁止类）⇒ 拒（即使 has_trading_post=true）。
  {
    pbv1::CmdUpdatePermission c = make_perm_cmd(4, 77);
    c.mutable_entries(0)->set_has_risk_role(true);
    HG_CHECK(is_ok_ack(h.handle(mt(MessageType::kCmdUpdatePermission), enc(c), 4, 0)));
  }
  // op=88：is_management_role=true（禁止类，0x100F 组）⇒ 拒。
  {
    pbv1::CmdUpdatePermission c = make_perm_cmd(5, 88);
    c.mutable_entries(0)->set_is_management_role(true);
    HG_CHECK(is_ok_ack(h.handle(mt(MessageType::kCmdUpdatePermission), enc(c), 5, 0)));
  }

  // 判定面联动（canTrade 消费快照中的权限）。
  HG_CHECK(!st.snapshot().canTrade(55, 1, 10));  // 准入类缺失 ⇒ 拒（第四分支形态）
  HG_CHECK(st.snapshot().canTrade(66, 1, 10));   // 全过 ⇒ 放行
  HG_CHECK(!st.snapshot().canTrade(77, 1, 10));  // 风控岗 ⇒ 拒（0x100E 组）
  HG_CHECK(!st.snapshot().canTrade(88, 1, 10));  // 管理层 ⇒ 拒（0x100F 组）
  HG_CHECK(!st.snapshot().canTrade(999, 1, 10)); // 无权限记录 ⇒ 拒（安全默认）
}

// ── 10. 非法输入拒收 ─────────────────────────────────────────────────────
void test_invalid_payload_rejected() {
  RiskState st;
  CommandHandler h(&st);
  // CMD_UPDATE_PERMISSION 空 entries（必填）⇒ 0x0104。
  {
    pbv1::CmdUpdatePermission c;
    c.set_param_version(1);
    const CommandOutcome o = h.handle(mt(MessageType::kCmdUpdatePermission), enc(c), 1, 0);
    HG_CHECK(!is_ok_ack(o));
    HG_CHECK_EQ(err_code_of(o), static_cast<std::uint32_t>(0x0104));
  }
  // CMD_LOAD_RISK_PARAMS batch_total=0 ⇒ 0x0104。
  {
    pbv1::CmdLoadRiskParams b = make_load_batch(1, 0, 1);
    b.set_batch_total(0);
    const CommandOutcome o = h.handle(mt(MessageType::kCmdLoadRiskParams), enc(b), 2, 0);
    HG_CHECK(!is_ok_ack(o));
    HG_CHECK_EQ(err_code_of(o), static_cast<std::uint32_t>(0x0104));
  }
  // 品种 unit_factor 非数字 ⇒ 0x0104。
  {
    pbv1::CmdLoadRiskParams b = make_load_batch(1, 0, 1);
    b.mutable_varieties(0)->set_unit_factor("abc");
    const CommandOutcome o = h.handle(mt(MessageType::kCmdLoadRiskParams), enc(b), 3, 0);
    HG_CHECK(!is_ok_ack(o));
    HG_CHECK_EQ(err_code_of(o), static_cast<std::uint32_t>(0x0104));
  }
}

}  // namespace

int main() {
  test_all_17_consumed();
  test_permission_mapping_and_effect();
  test_permission_single_key_override();
  test_param_version_monotonic();
  test_load_batching_and_ready_event();
  test_risk_threshold_floor();
  test_incremental_cmds_effect();
  test_readiness_gate_and_status();
  test_permission_engine_integration();
  test_invalid_payload_rejected();
  return hg::hgcp::testutil::report("hgcp.command_handler_test");
}
