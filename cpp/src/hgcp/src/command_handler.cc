// 控制指令消费与参数生效实现（T-038）。映射表与范围边界见 command_handler.h。
#include "hg/hgcp/command_handler.h"

#include <cstdio>

#include "hg/hgcp/protocol.h"
#include "hg/hgcp/payload_codec.h"
#include "hgcp.pb.h"

namespace hg {
namespace hgcp {

namespace pbv1 = ::hgcp::v1;

using hg::risk_state::LoadBatch;
using hg::risk_state::PermissionEntry;
using hg::risk_state::QuotaEntry;
using hg::risk_state::QuotaLayer;
using hg::risk_state::PositionLimitEntry;
using hg::risk_state::ExposureEntry;
using hg::risk_state::AnnualScaleEntry;
using hg::risk_state::Fixed;
using hg::risk_state::kQtyScale;
using hg::risk_state::kRatioScale;
using hg::risk_state::ratioFromPpm;
using hg::risk_state::Category;
using hg::risk_state::ProductStatus;
using hg::risk_state::ExposureDirection;
using hg::risk_state::ScaleScope;
using hg::risk_state::CalcMode;

namespace {

inline constexpr std::uint16_t mt(MessageType m) {
  return static_cast<std::uint16_t>(m);
}
std::uint16_t fl(FlagBits b) { return static_cast<std::uint16_t>(b); }

ReplyFrame make_ack(std::uint64_t ack_msg_id, std::uint64_t applied_version) {
  pbv1::MsgAck a;
  a.set_ack_msg_id(ack_msg_id);
  a.set_result(0);
  if (applied_version != 0u) {
    a.set_applied_version(applied_version);
  }
  ReplyFrame f;
  f.type = mt(MessageType::kMsgAck);
  f.flags = fl(kFlagIsReply);
  PbPayloadCodec::encode(a, &f.body);
  return f;
}

ReplyFrame make_ack_with_detail(std::uint64_t ack_msg_id, const std::string& detail) {
  pbv1::MsgAck a;
  a.set_ack_msg_id(ack_msg_id);
  a.set_result(0);
  a.set_detail(detail);
  ReplyFrame f;
  f.type = mt(MessageType::kMsgAck);
  f.flags = fl(kFlagIsReply);
  PbPayloadCodec::encode(a, &f.body);
  return f;
}

// 拒绝回执：err_code 必须取自 §4.3.2 冻结的 62 个错误码（不得新造）。
ReplyFrame make_error(std::uint64_t related_msg_id, std::uint32_t err_code,
                      const std::string& err_msg) {
  pbv1::MsgError e;
  e.set_err_code(err_code);
  if (!err_msg.empty()) {
    e.set_err_msg(err_msg);
  }
  e.set_related_msg_id(related_msg_id);
  ReplyFrame f;
  f.type = mt(MessageType::kMsgError);
  f.flags = fl(kFlagIsError);
  PbPayloadCodec::encode(e, &f.body);
  return f;
}

ReplyFrame invalid_payload(std::uint64_t ack_msg_id, const std::string& msg) {
  return make_error(ack_msg_id, static_cast<std::uint32_t>(ErrorCode::kErrPayloadInvalid), msg);
}

// 会话/重建失败 → 回执的统一映射。
ReplyFrame session_result_to_reply(const hg::risk_state::SessionResult& r,
                                   std::uint64_t ack_msg_id) {
  switch (r.code) {
    case hg::risk_state::kSessionVersionStale:
      return make_error(ack_msg_id,
                        static_cast<std::uint32_t>(ErrorCode::kErrParamVersionStale),
                        hg::risk_state::rebuildSessionCodeName(r.code));
    case hg::risk_state::kSessionBatchInconsistent:
    case hg::risk_state::kSessionBatchMissing:
      return make_error(ack_msg_id,
                        static_cast<std::uint32_t>(ErrorCode::kErrParamLoadIncomplete),
                        hg::risk_state::rebuildSessionCodeName(r.code));
    default:
      return invalid_payload(ack_msg_id, hg::risk_state::rebuildSessionCodeName(r.code));
  }
}

// 生效完成 → EVT_SYSTEM_STATE(READY, param_version)（验收 3 / §4.2.3 #13 回执列）。
ReplyFrame make_system_state_ready(std::uint64_t param_version, std::int64_t now_ms) {
  pbv1::EvtSystemState e;
  e.set_state(pbv1::SERVER_STATE_READY);
  e.set_param_version_applied(param_version);
  e.set_ts_ms(now_ms);
  ReplyFrame f;
  f.type = mt(MessageType::kEvtSystemState);
  f.flags = 0u;
  PbPayloadCodec::encode(e, &f.body);
  return f;
}

// ---------------------------------------------------------------------------
// pb → 模型 映射辅助（字段级映射点；仅 T-038 本处）
// ---------------------------------------------------------------------------

hg::risk_state::Category map_category(pbv1::ProductCategory c) {
  switch (c) {
    case pbv1::PRODUCT_CATEGORY_COMMODITY:
      return hg::risk_state::Category::kCommodity;
    case pbv1::PRODUCT_CATEGORY_TRADE:
      return hg::risk_state::Category::kTrade;
    default:
      return hg::risk_state::Category::kUnknown;
  }
}

hg::risk_state::ProductStatus map_status(pbv1::ProductStatus s) {
  return s == pbv1::PRODUCT_STATUS_ENABLED
             ? hg::risk_state::ProductStatus::kEnabled
             : hg::risk_state::ProductStatus::kDisabled;
}

// 定点换算：wire 字符串（十进制）→ 模型定点。数量 scale=1e4；比例 scale=1e6。
// 解析失败 ⇒ false（调用方按「必填取值非法」拒收：0x0104）。
bool parse_fixed_str(const std::string& s, std::int64_t scale,
                     hg::risk_state::Fixed* out) {
  if (s.empty()) return false;
  std::int64_t int_part = 0;
  std::int64_t frac = 0;
  std::size_t i = 0;
  bool neg = false;
  if (s[i] == 43 || s[i] == 45) {  // '+' / '-'
    neg = (s[i] == 45);
    ++i;
  }
  bool any = false;
  while (i < s.size() && s[i] >= 48 && s[i] <= 57) {
    int_part = int_part * 10 + (s[i] - 48);
    any = true;
    ++i;
  }
  if (i < s.size() && s[i] == 46) {  // decimal point
    ++i;
    std::int64_t fscale = scale;
    while (i < s.size() && s[i] >= 48 && s[i] <= 57) {
      if (fscale > 1) {
        frac = frac * 10 + (s[i] - 48);
        fscale /= 10;
      }
      ++i;
    }
    while (fscale > 1) {
      frac *= 10;
      fscale /= 10;
    }
  }
  if (i != s.size() || !any) return false;
  std::int64_t v = int_part * scale + frac;
  *out = neg ? -v : v;
  return true;
}

hg::risk_state::ExposureDirection map_direction(pbv1::Direction d) {
  switch (d) {
    case pbv1::DIRECTION_BUY:  return hg::risk_state::ExposureDirection::kBuy;
    case pbv1::DIRECTION_SELL: return hg::risk_state::ExposureDirection::kSell;
    default:                   return hg::risk_state::ExposureDirection::kBuy;
  }
}

hg::risk_state::ScaleScope map_scale_scope(pbv1::ScopeType t) {
  return t == pbv1::SCOPE_TYPE_TOTAL_ACCOUNT ? hg::risk_state::ScaleScope::kTotalAccount
                                             : hg::risk_state::ScaleScope::kSubAccount;
}

// 线上 instrument_id 字符串 → 内存整数 id（§4.4.1 对账映射约定；非数字串 ⇒ 拒收）。
bool parse_u32_str(const std::string& s, std::uint32_t* out) {
  if (s.empty()) return false;
  std::uint32_t v = 0;
  for (std::size_t k = 0; k < s.size(); ++k) {
    if (s[k] < 48 || s[k] > 57) return false;
    v = v * 10u + static_cast<std::uint32_t>(s[k] - 48);
  }
  *out = v;
  return true;
}

// pb QuotaEntry → (layer, 模型 QuotaEntry)。layer 越界 / 取值不可解析返回 false。
bool map_quota(const pbv1::QuotaEntry& in, hg::risk_state::QuotaLayer* layer,
               hg::risk_state::QuotaEntry* out) {
  switch (in.limit_layer()) {
    case pbv1::LIMIT_LAYER_SUB_PRODUCT:
      *layer = hg::risk_state::QuotaLayer::kSubProduct;
      break;
    case pbv1::LIMIT_LAYER_SUB_INSTRUMENT:
      *layer = hg::risk_state::QuotaLayer::kSubInstrument;
      break;
    case pbv1::LIMIT_LAYER_ACCOUNT_PRODUCT:
      *layer = hg::risk_state::QuotaLayer::kAccountProduct;
      break;
    default:
      return false;
  }
  if (!parse_fixed_str(in.quota_limit(), hg::risk_state::kQtyScale, &out->limit))
    return false;
  if (!parse_fixed_str(in.used(), hg::risk_state::kQtyScale, &out->used)) return false;
  if (!parse_fixed_str(in.frozen(), hg::risk_state::kQtyScale, &out->frozen))
    return false;
  return true;
}

// RiskThresholds（结构化）→ LoadBatch 阈值键值（键名 = 引擎 applySnapshot 消费键，
//   见 risk_engine.cc：hedge.scale.ratio_commodity / risk.price.warn_pct /
//   risk.price.block_pct / risk.term.max_months / risk.term.grace_days）。
// 返回 false = 必填取值不可解析 / 低于合规下限（R-CONFIG-8，验收 4）。
bool map_thresholds(const pbv1::RiskThresholds& th, LoadBatch* b,
                    std::string* reject_reason) {
  // R-CONFIG-8：规模红线默认值不得低于 INDEX.md §9.1 合规口径（0.90 / 0.80）——
  //   下限即默认值本身（下发值 < 默认值 = 放宽红线 ⇒ 拒绝）。
  hg::risk_state::Fixed rc = 0;
  if (!parse_fixed_str(th.scale_ratio_commodity(), hg::risk_state::kRatioScale, &rc)) {
    *reject_reason = "scale_ratio_commodity unparseable";
    return false;
  }
  if (rc < hg::risk_state::ratioFromPpm(900000)) {
    *reject_reason = "scale_ratio_commodity below R-CONFIG-8 floor (0.90)";
    return false;
  }
  hg::risk_state::Fixed rt = 0;
  if (!parse_fixed_str(th.scale_ratio_trade(), hg::risk_state::kRatioScale, &rt)) {
    *reject_reason = "scale_ratio_trade unparseable";
    return false;
  }
  if (rt < hg::risk_state::ratioFromPpm(800000)) {
    *reject_reason = "scale_ratio_trade below R-CONFIG-8 floor (0.80)";
    return false;
  }
  if (th.term_max_months() <= 0 || th.term_grace_days() < 0) {
    *reject_reason = "term thresholds invalid";
    return false;
  }
  hg::risk_state::Fixed pw = 0;
  hg::risk_state::Fixed pb = 0;
  if (!parse_fixed_str(th.price_warn_pct(), hg::risk_state::kRatioScale, &pw) ||
      !parse_fixed_str(th.price_block_pct(), hg::risk_state::kRatioScale, &pb) ||
      pw <= 0 || pb <= 0 || pb < pw) {
    *reject_reason = "price deviation thresholds invalid";
    return false;
  }
  if (th.freq_intraday_warn() <= 0 || th.freq_intraday_block() <= 0 ||
      th.freq_7d_rounds_warn() <= 0 || th.freq_min_hold_days() < 0) {
    *reject_reason = "frequency thresholds invalid";
    return false;
  }
  b->putThreshold("hedge.scale.ratio_commodity", th.scale_ratio_commodity());
  b->putThreshold("hedge.scale.ratio_trade", th.scale_ratio_trade());
  b->putThreshold("risk.term.max_months", std::to_string(th.term_max_months()));
  b->putThreshold("risk.term.grace_days", std::to_string(th.term_grace_days()));
  b->putThreshold("risk.price.warn_pct", th.price_warn_pct());
  b->putThreshold("risk.price.block_pct", th.price_block_pct());
  // freq_*（§11.1 CFG-05 命名）按参数面承载；引擎判定侧消费点归事中/事后。
  b->putThreshold("risk.freq.intraday_warn", std::to_string(th.freq_intraday_warn()));
  b->putThreshold("risk.freq.intraday_block", std::to_string(th.freq_intraday_block()));
  b->putThreshold("risk.freq.7d_rounds_warn", std::to_string(th.freq_7d_rounds_warn()));
  b->putThreshold("risk.freq.min_hold_days", std::to_string(th.freq_min_hold_days()));
  return true;
}

// 权限条目映射（五布尔逐字段；映射表见 command_handler.h 文件头，验收 5）。
bool map_permission_entry(const pbv1::PermissionEntry& e,
                          hg::risk_state::PermissionEntry* m,
                          std::string* reject_reason) {
  m->sub_accounts.insert(e.sub_account_id());
  for (int k = 0; k < e.product_ids_size(); ++k) {
    m->products.insert(e.product_ids(k));
  }
  if (!e.quota_limit().empty()) {
    if (!parse_fixed_str(e.quota_limit(), hg::risk_state::kQtyScale,
                         &m->individual_quota)) {
      *reject_reason = "permission quota_limit unparseable";
      return false;
    }
  }
  m->is_responsible = e.is_enterprise_leader();    // (5) → is_responsible
  m->is_management_role = e.is_management_role();  // (6) 同名直映
  m->has_risk_post = e.has_risk_role();            // (7) → has_risk_post
  m->has_settle_role = e.has_settle_role();        // (8) 同名直映
  m->has_trading_post = e.has_trading_post();      // (9) 同名直映（准入类）
  return true;
}

bool map_exposure_entry(const pbv1::ExposureEntry& e,
                        hg::risk_state::ExposureEntry* m,
                        std::string* reject_reason) {
  m->contract_unexecuted_qty = e.contract_unexecuted_qty();
  m->inventory_qty = e.inventory_qty();
  m->production_plan_qty = e.production_plan_qty();
  m->exposure_qty = e.total_qty();
  if (!parse_fixed_str(e.unit_factor(), hg::risk_state::kQtyScale,
                       &m->unit_convert_factor)) {
    *reject_reason = "exposure unit_factor unparseable";
    return false;
  }
  m->snapshot_time_ms = e.snapshot_time();
  m->snapshot_id = std::to_string(e.snapshot_id());  // uint64 → 十进制串（审计标识）
  m->calc_mode = hg::risk_state::CalcMode::kCombined;  // Q-17 默认组合口径
  return true;
}

// CMD_LOAD_RISK_PARAMS 单批 → LoadBatch。
bool map_load_batch(const pbv1::CmdLoadRiskParams& in, LoadBatch* b,
                    std::string* reject_reason) {
  b->set_param_version(in.param_version());
  // ① 品种
  for (int i = 0; i < in.varieties_size(); ++i) {
    const pbv1::VarietySpec& v = in.varieties(i);
    hg::risk_state::VarietyMeta m;
    m.product_id = v.product_id();
    m.code = v.product_code();
    m.category = map_category(v.category());
    m.status = map_status(v.status());
    if (!parse_fixed_str(v.unit_factor(), hg::risk_state::kQtyScale,
                         &m.convert_factor)) {
      *reject_reason = "variety unit_factor unparseable";
      return false;
    }
    b->putVariety(m);
  }
  // ② 白名单（条目 = sub + product 集合）
  for (int i = 0; i < in.whitelist_size(); ++i) {
    const pbv1::WhitelistEntry& w = in.whitelist(i);
    for (int k = 0; k < w.product_ids_size(); ++k) {
      b->addWhitelist(w.sub_account_id(), w.product_ids(k));
    }
  }
  // ③ 三层额度
  for (int i = 0; i < in.quotas_size(); ++i) {
    hg::risk_state::QuotaLayer layer;
    hg::risk_state::QuotaEntry q;
    if (!map_quota(in.quotas(i), &layer, &q)) {
      *reject_reason = "quota entry invalid";
      return false;
    }
    b->putQuota(layer, in.quotas(i).sub_account_id(), in.quotas(i).product_id(), 0, q);
  }
  // ④ 持仓上限
  for (int i = 0; i < in.position_limits_size(); ++i) {
    const pbv1::PositionLimitEntry& p = in.position_limits(i);
    hg::risk_state::PositionLimitEntry m;
    if (!parse_fixed_str(p.limit(), hg::risk_state::kQtyScale, &m.limit)) {
      *reject_reason = "position limit unparseable";
      return false;
    }
    m.unit = "LOT";
    std::uint32_t inst = 0;
    if (!parse_u32_str(p.instrument_id(), &inst)) {
      *reject_reason = "position limit instrument_id not numeric";
      return false;
    }
    b->putPositionLimit(p.sub_account_id(), p.product_id(), inst, m);
  }
  // ⑤ 敞口
  for (int i = 0; i < in.exposures_size(); ++i) {
    hg::risk_state::ExposureEntry m;
    if (!map_exposure_entry(in.exposures(i), &m, reject_reason)) return false;
    b->putExposure(in.exposures(i).sub_account_id(), in.exposures(i).product_id(),
                   map_direction(in.exposures(i).direction()), m);
  }
  // ⑥ 权限（五布尔逐字段 —— 验收 5）
  for (int i = 0; i < in.permissions_size(); ++i) {
    hg::risk_state::PermissionEntry m;
    if (!map_permission_entry(in.permissions(i), &m, reject_reason)) return false;
    b->putPermission(static_cast<std::uint32_t>(in.permissions(i).operator_id()), m);
  }
  // ⑦ 阈值
  if (in.has_thresholds()) {
    if (!map_thresholds(in.thresholds(), b, reject_reason)) return false;
  }
  // ⑧ 总账户限额
  if (in.has_total_limits()) {
    const pbv1::TotalLimits& t = in.total_limits();
    hg::risk_state::QuotaEntry tot;
    if (!parse_fixed_str(t.total_limit(), hg::risk_state::kQtyScale, &tot.limit) ||
        !parse_fixed_str(t.total_used(), hg::risk_state::kQtyScale, &tot.used) ||
        !parse_fixed_str(t.total_frozen(), hg::risk_state::kQtyScale, &tot.frozen)) {
      *reject_reason = "total limits unparseable";
      return false;
    }
    b->setAccountTotalLimit(tot);
    for (int i = 0; i < t.by_product_size(); ++i) {
      const pbv1::TotalLimitByProduct& tp = t.by_product(i);
      hg::risk_state::QuotaEntry q;
      if (!parse_fixed_str(tp.limit(), hg::risk_state::kQtyScale, &q.limit) ||
          !parse_fixed_str(tp.used(), hg::risk_state::kQtyScale, &q.used) ||
          !parse_fixed_str(tp.frozen(), hg::risk_state::kQtyScale, &q.frozen)) {
        *reject_reason = "total limit by product unparseable";
        return false;
      }
      b->putTotalLimit(tp.product_id(), q);
    }
  }
  // ⑨ 年度规模基数（category / scale_ratio_limit 协议无承载：占比红线由阈值键统一承载）
  for (int i = 0; i < in.annual_scale_factors_size(); ++i) {
    const pbv1::AnnualScaleFactor& a = in.annual_scale_factors(i);
    hg::risk_state::AnnualScaleEntry m;
    if (!parse_fixed_str(a.expected_spot_business_qty(), hg::risk_state::kQtyScale,
                         &m.expected_spot_business_qty) ||
        !parse_fixed_str(a.accumulated_hedge_qty(), hg::risk_state::kQtyScale,
                         &m.accumulated_hedge_qty)) {
      *reject_reason = "annual scale unparseable";
      return false;
    }
    m.scope = map_scale_scope(a.scope_type());
    b->putAnnualScale(m.scope, a.sub_account_id(), a.product_id(),
                      static_cast<std::uint32_t>(a.scale_year()), m);
  }
  return true;
}

bool map_update_permission(const pbv1::CmdUpdatePermission& in, LoadBatch* b,
                           std::string* reject_reason) {
  b->set_param_version(in.param_version());
  for (int i = 0; i < in.entries_size(); ++i) {
    hg::risk_state::PermissionEntry m;
    if (!map_permission_entry(in.entries(i), &m, reject_reason)) return false;
    // 单键覆盖：同 operator_id 后条覆盖前条（语义见 command_handler.h）。
    b->putPermission(static_cast<std::uint32_t>(in.entries(i).operator_id()), m);
  }
  return in.entries_size() > 0;  // entries 必填（§4.2.8.3）；空 ⇒ 拒
}

bool map_update_exposure(const pbv1::CmdUpdateExposure& in, LoadBatch* b,
                         std::string* reject_reason) {
  b->set_param_version(in.param_version());
  for (int i = 0; i < in.entries_size(); ++i) {
    hg::risk_state::ExposureEntry m;
    if (!map_exposure_entry(in.entries(i), &m, reject_reason)) return false;
    b->putExposure(in.entries(i).sub_account_id(), in.entries(i).product_id(),
                   map_direction(in.entries(i).direction()), m);
  }
  return in.entries_size() > 0;
}

bool map_update_whitelist(const pbv1::CmdUpdateWhitelist& in, LoadBatch* b) {
  b->set_param_version(in.param_version());
  for (int i = 0; i < in.add_product_ids_size(); ++i) {
    b->addWhitelist(in.sub_account_id(), in.add_product_ids(i));
  }
  for (int i = 0; i < in.remove_product_ids_size(); ++i) {
    b->removeWhitelist(in.sub_account_id(), in.remove_product_ids(i));
  }
  return true;
}

bool map_update_quota(const pbv1::CmdUpdateQuota& in, LoadBatch* b,
                      std::string* reject_reason) {
  b->set_param_version(in.param_version());
  for (int i = 0; i < in.entries_size(); ++i) {
    hg::risk_state::QuotaLayer layer;
    hg::risk_state::QuotaEntry q;
    if (!map_quota(in.entries(i), &layer, &q)) {
      *reject_reason = "quota entry invalid";
      return false;
    }
    b->putQuota(layer, in.entries(i).sub_account_id(), in.entries(i).product_id(), 0, q);
  }
  return in.entries_size() > 0;
}

bool map_update_position_limit(const pbv1::CmdUpdatePositionLimit& in, LoadBatch* b,
                               std::string* reject_reason) {
  b->set_param_version(in.param_version());
  for (int i = 0; i < in.entries_size(); ++i) {
    const pbv1::PositionLimitEntry& p = in.entries(i);
    hg::risk_state::PositionLimitEntry m;
    if (!parse_fixed_str(p.limit(), hg::risk_state::kQtyScale, &m.limit)) {
      *reject_reason = "position limit unparseable";
      return false;
    }
    m.unit = "LOT";
    std::uint32_t inst = 0;
    if (!parse_u32_str(p.instrument_id(), &inst)) {
      *reject_reason = "position limit instrument_id not numeric";
      return false;
    }
    b->putPositionLimit(p.sub_account_id(), p.product_id(), inst, m);
  }
  return in.entries_size() > 0;
}

// CMD_SET_TRADING_SUSPEND → LoadBatch 阈值键（sub=0 全账户键；判定侧消费点
//   0x1011 归 T-036 引擎接线 —— 本处置=参数面生效承载，诚实披露）。
void map_trading_suspend(const pbv1::CmdSetTradingSuspend& in, LoadBatch* b) {
  b->set_param_version(in.param_version());
  const std::string v = in.suspended() ? "true" : "false";
  if (in.sub_account_id() == 0u) {
    b->putThreshold("risk.trading.suspend", v);
  } else {
    b->putThreshold("risk.trading.suspend.sub." + std::to_string(in.sub_account_id()), v);
  }
}

}  // namespace

CommandHandler::CommandHandler(hg::risk_state::RiskState* state)
    : state_(state), session_(state) {}

bool CommandHandler::system_ready() const { return state_->ready(); }

CommandOutcome CommandHandler::handle(std::uint16_t type,
                                      const std::vector<std::uint8_t>& body,
                                      std::uint64_t ack_msg_id, std::int64_t now_ms) {
  CommandOutcome out;
  // 仅控制指令段（0x0101–0x011E）进入本消费器。
  if (type < mt(MessageType::kCmdPlaceOrder) ||
      type > mt(MessageType::kCmdQueryFundResult)) {
    return out;  // consumed = false
  }
  out.consumed = true;
  std::string reject_reason;
  switch (type) {
    // ---- 参数生效面主链（验收 1/2/3/4/5）----
    case mt(MessageType::kCmdLoadRiskParams): {
      pbv1::CmdLoadRiskParams msg;
      if (!PbPayloadCodec::decode(body, &msg) || msg.batch_total() == 0u ||
          msg.batch_index() >= msg.batch_total()) {
        out.reply = invalid_payload(ack_msg_id, "load params batch fields invalid");
        return out;
      }
      LoadBatch b;
      if (!map_load_batch(msg, &b, &reject_reason)) {
        out.reply = invalid_payload(ack_msg_id, reject_reason);
        return out;
      }
      const hg::risk_state::SessionResult r =
          session_.submitLoadBatch(b, msg.batch_index(), msg.batch_total());
      if (!r.ok) {
        out.reply = session_result_to_reply(r, ack_msg_id);
        return out;
      }
      out.reply = make_ack(ack_msg_id, r.applied ? r.version : 0u);
      if (r.applied) {
        out.events.push_back(make_system_state_ready(r.version, now_ms));
      }
      return out;
    }
    case mt(MessageType::kCmdUpdateWhitelist): {
      pbv1::CmdUpdateWhitelist msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      LoadBatch b;
      map_update_whitelist(msg, &b);
      const hg::risk_state::SessionResult r = session_.submitIncremental(b);
      if (!r.ok) {
        out.reply = session_result_to_reply(r, ack_msg_id);
        return out;
      }
      out.reply = make_ack(ack_msg_id, r.version);
      return out;
    }
    case mt(MessageType::kCmdUpdateQuota): {
      pbv1::CmdUpdateQuota msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      LoadBatch b;
      if (!map_update_quota(msg, &b, &reject_reason)) {
        out.reply = invalid_payload(ack_msg_id, reject_reason);
        return out;
      }
      const hg::risk_state::SessionResult r = session_.submitIncremental(b);
      if (!r.ok) {
        out.reply = session_result_to_reply(r, ack_msg_id);
        return out;
      }
      out.reply = make_ack(ack_msg_id, r.version);
      return out;
    }
    case mt(MessageType::kCmdUpdatePositionLimit): {
      pbv1::CmdUpdatePositionLimit msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      LoadBatch b;
      if (!map_update_position_limit(msg, &b, &reject_reason)) {
        out.reply = invalid_payload(ack_msg_id, reject_reason);
        return out;
      }
      const hg::risk_state::SessionResult r = session_.submitIncremental(b);
      if (!r.ok) {
        out.reply = session_result_to_reply(r, ack_msg_id);
        return out;
      }
      out.reply = make_ack(ack_msg_id, r.version);
      return out;
    }
    case mt(MessageType::kCmdUpdateRiskThreshold): {
      pbv1::CmdUpdateRiskThreshold msg;
      if (!PbPayloadCodec::decode(body, &msg) || !msg.has_thresholds()) {
        out.reply = invalid_payload(ack_msg_id, "thresholds required");
        return out;
      }
      LoadBatch b;
      if (!map_thresholds(msg.thresholds(), &b, &reject_reason)) {
        out.reply = invalid_payload(ack_msg_id, reject_reason);  // R-CONFIG-8（验收 4）
        return out;
      }
      b.set_param_version(msg.param_version());
      const hg::risk_state::SessionResult r = session_.submitIncremental(b);
      if (!r.ok) {
        out.reply = session_result_to_reply(r, ack_msg_id);
        return out;
      }
      out.reply = make_ack(ack_msg_id, r.version);
      return out;
    }
    case mt(MessageType::kCmdUpdatePermission): {
      pbv1::CmdUpdatePermission msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      LoadBatch b;
      if (!map_update_permission(msg, &b, &reject_reason)) {
        out.reply = invalid_payload(ack_msg_id,
                                    reject_reason.empty() ? "entries required"
                                                          : reject_reason);
        return out;
      }
      const hg::risk_state::SessionResult r = session_.submitIncremental(b);
      if (!r.ok) {
        out.reply = session_result_to_reply(r, ack_msg_id);
        return out;
      }
      out.reply = make_ack(ack_msg_id, r.version);
      return out;
    }
    case mt(MessageType::kCmdUpdateExposure): {
      pbv1::CmdUpdateExposure msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      LoadBatch b;
      if (!map_update_exposure(msg, &b, &reject_reason)) {
        out.reply = invalid_payload(ack_msg_id, reject_reason);
        return out;
      }
      const hg::risk_state::SessionResult r = session_.submitIncremental(b);
      if (!r.ok) {
        out.reply = session_result_to_reply(r, ack_msg_id);
        return out;
      }
      out.reply = make_ack(ack_msg_id, r.version);
      return out;
    }
    case mt(MessageType::kCmdSetTradingSuspend): {
      pbv1::CmdSetTradingSuspend msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      LoadBatch b;
      map_trading_suspend(msg, &b);
      const hg::risk_state::SessionResult r = session_.submitIncremental(b);
      if (!r.ok) {
        out.reply = session_result_to_reply(r, ack_msg_id);
        return out;
      }
      out.reply = make_ack(ack_msg_id, r.version);
      return out;
    }
    // ---- 状态指令面（回执型消费）----
    case mt(MessageType::kCmdSetReadyState): {
      pbv1::CmdSetReadyState msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      out.reply = make_ack_with_detail(
          ack_msg_id,
          std::string("target_state=") + std::to_string(static_cast<int>(msg.target_state())) +
              ";ready=" + (system_ready() ? "true" : "false"));
      return out;
    }
    case mt(MessageType::kCmdReload): {
      pbv1::CmdReload msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      // 受理回执；重载动作（CTP 重登）与 EVT_CTP_STATE 归 T-039/T-033 承接。
      out.reply = make_ack_with_detail(ack_msg_id, "reload accepted");
      return out;
    }
    case mt(MessageType::kCmdTriggerReplay): {
      pbv1::CmdTriggerReplay msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      // 受理回执；重放执行与 EVT_REPLAY_PROGRESS 归 T-037/T-039 承接。
      out.reply = make_ack_with_detail(ack_msg_id, "replay accepted");
      return out;
    }
    case mt(MessageType::kCmdQueryStatus): {
      pbv1::CmdQueryStatus msg;
      if (!PbPayloadCodec::decode(body, &msg)) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      char buf[80];
      std::snprintf(buf, sizeof(buf), "ready=%s;param_version=%llu;load=%u/%u",
                    system_ready() ? "true" : "false",
                    static_cast<unsigned long long>(state_->param_version()),
                    static_cast<unsigned>(session_.batches_received()),
                    static_cast<unsigned>(session_.batches_total()));
      out.reply = make_ack_with_detail(ack_msg_id, std::string(buf));
      return out;
    }
    // ---- 交易路径面（受理消费 + 就绪门卫；生效归 T-036/T-037）----
    case mt(MessageType::kCmdPlaceOrder):
    case mt(MessageType::kCmdConfirmOrder):
    case mt(MessageType::kCmdCancelOrder):
    case mt(MessageType::kCmdFundTransfer):
    case mt(MessageType::kCmdQueryFundResult): {
      bool parseable = false;
      switch (type) {
        case mt(MessageType::kCmdPlaceOrder): {
          pbv1::CmdPlaceOrder m;
          parseable = PbPayloadCodec::decode(body, &m);
          break;
        }
        case mt(MessageType::kCmdConfirmOrder): {
          pbv1::CmdConfirmOrder m;
          parseable = PbPayloadCodec::decode(body, &m);
          break;
        }
        case mt(MessageType::kCmdCancelOrder): {
          pbv1::CmdCancelOrder m;
          parseable = PbPayloadCodec::decode(body, &m);
          break;
        }
        case mt(MessageType::kCmdFundTransfer): {
          pbv1::CmdFundTransfer m;
          parseable = PbPayloadCodec::decode(body, &m);
          break;
        }
        default: {
          pbv1::CmdQueryFundResult m;
          parseable = PbPayloadCodec::decode(body, &m);
          break;
        }
      }
      if (!parseable) {
        out.reply = invalid_payload(ack_msg_id, "unparseable");
        return out;
      }
      // 就绪门卫（§4.2.6 :1549「未就绪拒一切新指令」0x0401）。
      if (!system_ready()) {
        out.reply = make_error(ack_msg_id,
                               static_cast<std::uint32_t>(ErrorCode::kErrSystemNotReady),
                               "system not ready");
        return out;
      }
      out.reply = make_ack_with_detail(ack_msg_id, "accepted (path pending T-036)");
      return out;
    }
    default:
      out.consumed = false;  // 控制指令段无空洞（17 种已全列）；守卫保留
      return out;
  }
}

}  // namespace hgcp
}  // namespace hg
