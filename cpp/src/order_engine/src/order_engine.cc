// 指令生命周期引擎（T-036）—— 状态机实现（ARCHITECTURE.md §3.2 / §4.2.6(4)）。
#include "hg/order_engine/order_engine.h"

#include <cmath>

#include "hg/risk_engine/fixed_math.h"

namespace hg {
namespace order_engine {

namespace {

using hg::risk_state::Fixed;
using risk_engine::LimitLayer;
using risk_engine::OrderRequest;
using risk_engine::RiskResult;

constexpr std::uint32_t kAccountKey = 0u;  // 层③键：总账户以 0 入键（哨兵，账户级维度）

// FNV-1a 64（confirm_token 派生；确定性、无分配）。
std::uint64_t fnv1a64(const std::uint64_t* parts, std::size_t n) {
  std::uint64_t h = 1469598103934665603ull;
  for (std::size_t k = 0; k < n; ++k) {
    const std::uint64_t v = parts[k];
    for (int b = 0; b < 8; ++b) {
      h ^= (v >> (8 * b)) & 0xFFull;
      h *= 1099511628211ull;
    }
  }
  return h;
}

std::string tokenHex(std::uint64_t v) {
  static const char* kHex = "0123456789abcdef";
  std::string s;
  s.reserve(16);
  for (int i = 15; i >= 0; --i) {
    s.push_back(kHex[(v >> (4 * i)) & 0xF]);
  }
  return s;
}

// requestInQuotaUnit 的镜像（risk_engine.cc 同式；额度换算）。
Fixed requestInQuotaUnit(const OrderInput& in, Fixed cm, Fixed cf, const hg::risk_state::QuotaEntry& q) {
  const Fixed volume = static_cast<Fixed>(in.volume) * hg::risk_state::kQtyScale;
  if (q.unit == "LOT") return volume;
  // 非 LOT（如 TON）⇒ 折实物量：volume × 合约乘数 × 单位换算系数（realQty 同式）。
  if (volume == 0 || cm <= 0 || cf <= 0) return 0;
  const Fixed t = volume * cm;  // 1e8 标度
  return risk_engine::mulDivFloor(t, cf, hg::risk_state::kQtyScale * hg::risk_state::kQtyScale);
}

// 终态 → 流水类别（markTerminal 用）。
flow_store::FlowKind flowKindOfStatus(order_engine::OrderStatus st) {
  switch (st) {
    case order_engine::OrderStatus::kCancelled: return flow_store::FlowKind::kCancelled;
    case order_engine::OrderStatus::kExpired: return flow_store::FlowKind::kExpired;
    case order_engine::OrderStatus::kRejected: return flow_store::FlowKind::kRejected;
    default: return flow_store::FlowKind::kReported;
  }
}

// 终态判定（TE-T036-001 终态守卫）。
bool isTerminalStatus(order_engine::OrderStatus s) {
  return s == order_engine::OrderStatus::kFilled ||
         s == order_engine::OrderStatus::kRejected ||
         s == order_engine::OrderStatus::kCancelled ||
         s == order_engine::OrderStatus::kExpired;
}

}  // namespace

const char* orderStatusName(OrderStatus s) {
  switch (s) {
    case OrderStatus::kPendingConfirm: return "PENDING_CONFIRM";
    case OrderStatus::kSubmitted: return "SUBMITTED";
    case OrderStatus::kPartiallyFilled: return "PARTIALLY_FILLED";
    case OrderStatus::kFilled: return "FILLED";
    case OrderStatus::kCancelled: return "CANCELLED";
    case OrderStatus::kExpired: return "EXPIRED";
    case OrderStatus::kRejected: return "REJECTED";
    default: return "UNKNOWN";
  }
}

OrderEngine::OrderEngine(risk_engine::RiskEngine* risk, flow_store::FlowStore* flow,
                         ITradeGateway* trade, const OrderEngineConfig& cfg)
    : risk_(risk), flow_(flow), trade_(trade), cfg_(cfg), next_order_id_(1) {}

OrderEngine::~OrderEngine() {}

std::uint64_t OrderEngine::packKey(std::uint32_t a, std::uint32_t b) {
  return (static_cast<std::uint64_t>(a) << 32) | static_cast<std::uint64_t>(b);
}

hg::risk_state::Fixed OrderEngine::frozenOf(int layer, std::uint32_t sub, std::uint32_t key) const {
  const std::uint64_t k = packKey(sub, key);
  switch (layer) {
    case 1: {
      const std::unordered_map<std::uint64_t, Fixed>::const_iterator it = frozen_l1_.find(k);
      return it == frozen_l1_.end() ? 0 : it->second;
    }
    case 2: {
      const std::unordered_map<std::uint64_t, Fixed>::const_iterator it = frozen_l2_.find(k);
      return it == frozen_l2_.end() ? 0 : it->second;
    }
    default: {
      const std::unordered_map<std::uint64_t, Fixed>::const_iterator it = frozen_l3_.find(k);
      return it == frozen_l3_.end() ? 0 : it->second;
    }
  }
}

std::size_t OrderEngine::pending_count() const {
  std::size_t n = 0;
  for (std::unordered_map<std::uint64_t, OrderRecord>::const_iterator it = orders_.begin();
       it != orders_.end(); ++it) {
    if (it->second.status == OrderStatus::kPendingConfirm) ++n;
  }
  return n;
}

const OrderRecord* OrderEngine::find(std::uint64_t order_id) const {
  const std::unordered_map<std::uint64_t, OrderRecord>::const_iterator it = orders_.find(order_id);
  return it == orders_.end() ? nullptr : &it->second;
}

const OrderRecord* OrderEngine::findByRef(const std::string& client_order_ref) const {
  const std::unordered_map<std::string, std::uint64_t>::const_iterator it =
      ref_index_.find(client_order_ref);
  return it == ref_index_.end() ? nullptr : find(it->second);
}

void OrderEngine::setPrevSettlement(const std::string& instrument_code, Fixed value) {
  prev_settlement_[instrument_code] = value;
}

OrderRequest OrderEngine::buildRequest(const OrderRecord& rec) const {
  OrderRequest req;
  req.client_order_ref = rec.order_id;  // 数值域引用（真实字符串引用在档案）
  req.trade_plan_id = rec.input.trade_plan_id;
  req.exposure_id = rec.input.exposure_id;
  // T-173：交易理由关联对象透传至风控入参（0x1019 兜底复核所需）。
  req.related_plan_id = rec.input.related_plan_id;
  req.related_exposure_id = rec.input.related_exposure_id;
  req.sub_account_id = rec.input.sub_account_id;
  req.product_id = rec.input.product_id;
  req.instrument_id = rec.input.instrument_key;
  req.direction = rec.input.direction;
  req.offset = rec.input.offset;
  req.price_type = rec.input.price_type;
  req.limit_price = rec.input.limit_price;
  req.volume = static_cast<Fixed>(rec.input.volume) * hg::risk_state::kQtyScale;
  req.operator_id = rec.input.operator_id;
  {
    const std::unordered_map<std::string, Fixed>::const_iterator it =
        prev_settlement_.find(rec.input.instrument_id);
    req.prev_settlement_price = it == prev_settlement_.end() ? 0 : it->second;
  }
  // 判定时点：受理/确认时即当前 now_ms；期限与年度键由风控侧自快照/时钟推定（0 = 推定）。
  req.now_ms = 0;
  return req;
}

void OrderEngine::freezeFor(OrderRecord* rec) {
  const OrderInput& in = rec->input;
  const hg::risk_state::RiskSnapshot* s = risk_->currentSnapshot();
  if (s == nullptr) {
    rec->frozen_l1 = 0;
    rec->frozen_l2 = 0;
    rec->frozen_l3 = 0;
    return;  // 无快照 ⇒ 无额度单元可挂账（该形态在就绪门卫下不可达，防御保留）
  }
  const hg::risk_state::InstrumentMeta* inst = s->findInstrument(in.instrument_key);
  const hg::risk_state::VarietyMeta* var = s->findVariety(in.product_id);
  const Fixed cm = inst != nullptr ? inst->contract_multiplier : 0;
  const Fixed cf = var != nullptr ? var->convert_factor : 0;

  const std::uint64_t k1 = packKey(in.sub_account_id, in.product_id);
  const std::uint64_t k2 = packKey(in.sub_account_id, in.instrument_key);
  const std::uint64_t k3 = packKey(kAccountKey, in.product_id);

  const hg::risk_state::QuotaEntry* q1 = s->findQuota(hg::risk_state::QuotaLayer::kSubProduct,
                                                      in.sub_account_id, in.product_id,
                                                      in.instrument_key);
  const hg::risk_state::QuotaEntry* q2 = s->findQuota(hg::risk_state::QuotaLayer::kSubInstrument,
                                                      in.sub_account_id, in.product_id,
                                                      in.instrument_key);
  const hg::risk_state::QuotaEntry* q3 = s->findQuota(hg::risk_state::QuotaLayer::kAccountProduct,
                                                      in.sub_account_id, in.product_id,
                                                      in.instrument_key);
  rec->frozen_l1 = q1 != nullptr ? requestInQuotaUnit(in, cm, cf, *q1) : 0;
  rec->frozen_l2 = q2 != nullptr ? requestInQuotaUnit(in, cm, cf, *q2) : 0;
  rec->frozen_l3 = q3 != nullptr ? requestInQuotaUnit(in, cm, cf, *q3) : 0;
  if (rec->frozen_l1 > 0) frozen_l1_[k1] += rec->frozen_l1;
  if (rec->frozen_l2 > 0) frozen_l2_[k2] += rec->frozen_l2;
  if (rec->frozen_l3 > 0) frozen_l3_[k3] += rec->frozen_l3;
}

void OrderEngine::releaseFor(OrderRecord* rec) {
  const OrderInput& in = rec->input;
  // 释放后清零档案冻结份额（剪枝；TE-T036-001）：即便终态后回报重复进入释放路径，
  // `>0` 守卫亦使其成为空操作，冻结账本不会转负。
  if (rec->frozen_l1 > 0) {
    frozen_l1_[packKey(in.sub_account_id, in.product_id)] -= rec->frozen_l1;
    rec->frozen_l1 = 0;
  }
  if (rec->frozen_l2 > 0) {
    frozen_l2_[packKey(in.sub_account_id, in.instrument_key)] -= rec->frozen_l2;
    rec->frozen_l2 = 0;
  }
  if (rec->frozen_l3 > 0) {
    frozen_l3_[packKey(kAccountKey, in.product_id)] -= rec->frozen_l3;
    rec->frozen_l3 = 0;
  }
}

bool OrderEngine::quotaLedgerViolation(const OrderInput& in, risk_engine::RiskResult* out,
                                       const OrderRecord* exclude) const {
  const hg::risk_state::RiskSnapshot* s = risk_->currentSnapshot();
  if (s == nullptr) return false;
  const hg::risk_state::InstrumentMeta* inst = s->findInstrument(in.instrument_key);
  const hg::risk_state::VarietyMeta* var = s->findVariety(in.product_id);
  const Fixed cm = inst != nullptr ? inst->contract_multiplier : 0;
  const Fixed cf = var != nullptr ? var->convert_factor : 0;

  // 最细优先上报（层② > 层① > 层③，§4.4.1 全序；层③兜底不参与选择但须阻断）。
  const hg::risk_state::QuotaLayer order[3] = {hg::risk_state::QuotaLayer::kSubInstrument,
                                               hg::risk_state::QuotaLayer::kSubProduct,
                                               hg::risk_state::QuotaLayer::kAccountProduct};
  const int layer_tag[3] = {2, 1, 3};
  for (int i = 0; i < 3; ++i) {
    const hg::risk_state::QuotaEntry* q =
        s->findQuota(order[i], in.sub_account_id, in.product_id, in.instrument_key);
    if (q == nullptr) continue;
    const Fixed reqq = requestInQuotaUnit(in, cm, cf, *q);
    const std::uint32_t key_b =
        order[i] == hg::risk_state::QuotaLayer::kSubInstrument
            ? in.instrument_key
            : (order[i] == hg::risk_state::QuotaLayer::kSubProduct ? in.product_id
                                                                   : in.product_id);
    const std::uint32_t key_a = order[i] == hg::risk_state::QuotaLayer::kAccountProduct
                                    ? kAccountKey
                                    : in.sub_account_id;
    const Fixed led_raw = frozenOf(layer_tag[i], key_a, key_b);
    Fixed self = 0;
    if (exclude != nullptr) {
      if (order[i] == hg::risk_state::QuotaLayer::kSubInstrument) self = exclude->frozen_l2;
      else if (order[i] == hg::risk_state::QuotaLayer::kSubProduct) self = exclude->frozen_l1;
      else self = exclude->frozen_l3;
    }
    const Fixed led = led_raw - self;
    const Fixed used_total = q->used + q->frozen;
    if (used_total + led + reqq > q->limit) {
      out->decision = risk_engine::RiskDecision::kReject;
      out->rule = risk_engine::RuleId::kQuota;
      out->code = order[i] == hg::risk_state::QuotaLayer::kSubInstrument
                      ? 0x1017
                      : (order[i] == hg::risk_state::QuotaLayer::kSubProduct ? 0x1004 : 0x1005);
      out->limit_layer = order[i] == hg::risk_state::QuotaLayer::kSubInstrument
                             ? LimitLayer::kSubInstrument
                             : (order[i] == hg::risk_state::QuotaLayer::kSubProduct
                                    ? LimitLayer::kSubProduct
                                    : LimitLayer::kAccountProduct);
      out->has_limit_layer = true;
      out->entry_count = 1;
      out->entries[0].rule = risk_engine::RuleId::kQuota;
      out->entries[0].code = out->code;
      out->entries[0].layer = out->limit_layer;
      out->entries[0].current = used_total + led + reqq;
      out->entries[0].current_scale = risk_engine::ValueScale::kQty;
      out->entries[0].threshold = q->limit;
      out->entries[0].threshold_scale = risk_engine::ValueScale::kQty;
      out->entries[0].detail = "quota insufficient (including engine freeze ledger)";
      return true;
    }
  }
  return false;
}

bool OrderEngine::appendFlow(flow_store::FlowKind kind, const OrderRecord& rec,
                             std::int64_t now_ms, const std::string& detail) {
  if (flow_ == nullptr) return false;
  flow_store::FlowRecord fr;
  fr.kind = kind;
  fr.order_id = rec.order_id;
  fr.client_order_ref = rec.input.client_order_ref;
  fr.ts_ms = now_ms;
  fr.detail = detail;
  return flow_->append(fr);
}

void OrderEngine::markTerminal(OrderRecord* rec, OrderStatus st, std::int64_t now_ms,
                               const char* detail) {
  rec->status = st;
  releaseFor(rec);
  appendFlow(flowKindOfStatus(st), *rec, now_ms, detail != nullptr ? detail : "");
}

AcceptResult OrderEngine::accept(const OrderInput& in, std::int64_t now_ms) {
  AcceptResult out;
  out.accepted_ts_ms = now_ms;
  out.effective_timeout_ms = cfg_.confirm_timeout_ms;

  // R-TRADE-1（验收 4）：计划/敞口/子账户缺失一律拒绝；引用缺失同拒（幂等键）。
  if (in.trade_plan_id == 0 || in.sub_account_id == 0 || in.exposure_id == 0 ||
      in.client_order_ref.empty()) {
    out.risk.decision = risk_engine::RiskDecision::kReject;
    out.risk.rule = risk_engine::RuleId::kInput;
    out.risk.code = 0x3003;  // 入参非法沿用 risk_engine 既有约定（0x1020 未注册，见交付报告）
    out.risk.entry_count = 1;
    out.risk.entries[0].rule = risk_engine::RuleId::kInput;
    out.risk.entries[0].code = 0x3003;
    out.risk.entries[0].detail =
        in.trade_plan_id == 0 ? "trade_plan_id is required (!=0)"
                              : (in.sub_account_id == 0 ? "sub_account_id is required (!=0)"
                                                        : "exposure_id is required (!=0)");
    return out;
  }

  // 0x2007 幂等：同 client_order_ref 重放 ⇒ 幂等返回既有受理（不重复冻结）。
  const OrderRecord* prev = findByRef(in.client_order_ref);
  if (prev != nullptr) {
    out.duplicate = true;
    out.accepted = prev->status == OrderStatus::kPendingConfirm;
    out.order_id = prev->order_id;
    out.confirm_token = prev->confirm_token;
    out.effective_timeout_ms = cfg_.confirm_timeout_ms;
    return out;
  }

  // 组档案（先不入表；风控拒绝则丢弃，不产生 order_id 占号）。
  OrderRecord rec;
  rec.input = in;
  rec.volume_total = in.volume;
  rec.volume_left = in.volume;

  // 事前风控（8 规则纯内存；prev_settlement 缺位 ⇒ 0x1015 诚实拒绝）。
  const OrderRequest req = buildRequest(rec);
  const RiskResult rr = risk_->check(req);
  out.risk = rr;
  if (rr.decision == risk_engine::RiskDecision::kReject) {
    return out;  // EVT_PRE_TRADE_REJECT 载荷 = out.risk
  }
  // 冻结账本参与的超额守卫（快照冻结盲区的补账；含「并行待确认单」累计）。
  {
    risk_engine::RiskResult guard;
    if (quotaLedgerViolation(in, &guard)) {
      out.risk = guard;
      return out;
    }
  }
  out.warn = rr.decision == risk_engine::RiskDecision::kWarn;

  // 冻结额度账本（快照不可变 ⇒ 引擎自持三层键增量）+ 占号入表。
  const std::uint64_t id = next_order_id_++;
  rec.order_id = id;
  rec.status = OrderStatus::kPendingConfirm;
  rec.accepted_ts_ms = now_ms;
  rec.confirm_deadline_ms = now_ms + cfg_.confirm_timeout_ms;
  {
    const std::uint64_t parts[3] = {cfg_.token_salt, id, static_cast<std::uint64_t>(now_ms)};
    rec.confirm_token = tokenHex(fnv1a64(parts, 3));
  }
  freezeFor(&rec);
  orders_[id] = rec;
  ref_index_[in.client_order_ref] = id;
  appendFlow(flow_store::FlowKind::kPlaced, rec, now_ms, "accepted pending confirm");

  out.accepted = true;
  out.order_id = id;
  out.confirm_token = rec.confirm_token;
  return out;
}

ConfirmResult OrderEngine::confirm(std::uint64_t order_id, std::uint64_t operator_id,
                                   const std::string& confirm_token, std::int64_t now_ms) {
  ConfirmResult out;
  OrderRecord* rec = [&]() -> OrderRecord* {
    const std::unordered_map<std::uint64_t, OrderRecord>::iterator it = orders_.find(order_id);
    return it == orders_.end() ? nullptr : &it->second;
  }();
  if (rec == nullptr) {
    out.reason_code = 0x2001;
    out.detail = "order not found";
    return out;
  }
  if (rec->status != OrderStatus::kPendingConfirm) {
    out.reason_code = 0x2002;
    out.detail = std::string("state not allowed: ") + orderStatusName(rec->status);
    return out;
  }
  // 验收 3：确认超时 → 失效 + 释放 + EVT_ORDER_EXPIRED + 不发 CTP。
  if (now_ms > rec->confirm_deadline_ms) {
    markTerminal(rec, OrderStatus::kExpired, now_ms, "confirm timeout");
    out.reason_code = 0x2003;
    out.detail = "confirm timeout";
    return out;
  }
  // 验收 2：确认人 = 提交人（防代确认，0x0305）。
  if (operator_id != rec->input.operator_id || operator_id == 0) {
    out.reason_code = 0x0305;
    out.detail = "operator mismatch (anti proxy-confirm)";
    return out;
  }
  // 验收 2：confirm_token（防重放；绑定 order_id + 有效期 + 单次消费）。
  if (confirm_token.empty() || confirm_token != rec->confirm_token) {
    out.reason_code = 0x0307;
    out.detail = "confirm token invalid";
    return out;
  }
  // 验收 2：确认时**再跑一次完整风控**（防确认期状态变化）。
  const RiskResult rr = risk_->check(buildRequest(*rec));
  if (rr.decision == risk_engine::RiskDecision::kReject) {
    markTerminal(rec, OrderStatus::kRejected, now_ms, "re-check rejected on confirm");
    out.reason_code = rr.code;
    out.detail = "pre-trade re-check rejected";
    out.risk = rr;
    return out;
  }
  {
    risk_engine::RiskResult guard;
    if (quotaLedgerViolation(rec->input, &guard, rec)) {
      markTerminal(rec, OrderStatus::kRejected, now_ms, "re-check rejected on confirm");
      out.reason_code = guard.code;
      out.detail = "pre-trade re-check rejected (freeze ledger)";
      out.risk = guard;
      return out;
    }
  }
  if (flow_ != nullptr && !flow_->is_open()) {
    // 流水未就绪 ⇒ 不发 CTP（FR-TRADE-15 落盘先行的前提不满足，宁可不出手）。
    out.reason_code = 0x0401;
    out.detail = "flow store not open; order not submitted";
    return out;
  }
  // §4.2.6(4) 第⑨步：先落流水（append+fsync）。失败 ⇒ 不发单（不得无流水发单）。
  if (!appendFlow(flow_store::FlowKind::kSent, *rec, now_ms, "submitted to CTP")) {
    out.reason_code = 0x0304;  // 交付受阻：不得假装成功（§4.3.2 0x0304 处理建议）
    out.detail = "flow append failed; CTP not submitted";
    return out;
  }
  ++rec->submit_flow_count;

  // 第⑩步：发 CTP。
  hg::ctp_adapter::CtOrderReq creq;
  creq.client_order_ref = rec->input.client_order_ref;
  creq.instrument_id = rec->input.instrument_id;
  creq.market = rec->input.is_option ? hg::ctp_adapter::CtMarket::kOption
                                     : hg::ctp_adapter::CtMarket::kFutures;
  creq.direction = rec->input.direction == risk_engine::OrderDirection::kSell
                       ? hg::ctp_adapter::CtDirection::kSell
                       : hg::ctp_adapter::CtDirection::kBuy;
  creq.offset = rec->input.offset == risk_engine::OffsetFlag::kClose
                    ? hg::ctp_adapter::CtOffset::kClose
                    : hg::ctp_adapter::CtOffset::kOpen;
  creq.price_type = rec->input.price_type == risk_engine::PriceType::kMarket
                        ? hg::ctp_adapter::CtPriceType::kMarket
                        : hg::ctp_adapter::CtPriceType::kLimit;
  creq.limit_price = static_cast<double>(rec->input.limit_price) / 1e6;
  creq.volume = rec->input.volume;
  const SubmitAck ack = trade_->submitOrder(creq);
  if (ack.retained) {
    // FR-TRADE-2 验收 4：退回保留，不静默丢弃；token 不消费，可重试确认。
    rec->submit_retained = true;
    out.reason_code = 0x2004;
    out.retained = true;
    out.detail = "CTP not connected; order retained for retry";
    return out;
  }
  if (ack.code != 0) {
    out.reason_code = 0x2005;
    out.detail = "CTP submit failed";
    return out;
  }

  // 成功：消费 token（单次有效）+ 置已提交。
  rec->confirm_token.clear();
  rec->submit_retained = false;
  rec->status = OrderStatus::kSubmitted;
  rec->order_local_id = ack.order_local_id;
  out.sent = true;
  return out;
}

CancelResult OrderEngine::cancel(std::uint64_t order_id, std::uint64_t operator_id,
                                 const std::string& reason, std::int64_t now_ms,
                                 EngineEvent* out) {
  CancelResult res;
  const std::unordered_map<std::uint64_t, OrderRecord>::iterator it = orders_.find(order_id);
  if (it == orders_.end()) {
    res.reason_code = 0x2001;
    res.detail = "order not found";
    return res;
  }
  OrderRecord& rec = it->second;
  if (rec.status != OrderStatus::kPendingConfirm && rec.status != OrderStatus::kSubmitted &&
      rec.status != OrderStatus::kPartiallyFilled) {
    res.reason_code = 0x2002;
    res.detail = std::string("state not allowed: ") + orderStatusName(rec.status);
    return res;
  }
  if (operator_id == 0) {
    res.reason_code = 0x0305;
    res.detail = "operator required";
    return res;
  }
  const std::string detail =
      reason.empty() ? std::string("cancel by operator ") : ("cancel: " + reason);

  if (rec.status == OrderStatus::kPendingConfirm) {
    // 无 CTP 轨：直接撤销 + 释放冻结（R-TRADE-7 撤单口径的待确认形态）。
    const std::int32_t left = rec.volume_left;
    markTerminal(&rec, OrderStatus::kCancelled, now_ms, detail.c_str());
    res.cancelled = true;
    if (out != nullptr) {
      out->kind = EngineEvent::Kind::kCancelResult;
      out->cancel_out.order_id = order_id;
      out->cancel_out.cancelled_volume = left;
      out->cancel_out.result = 0;
    }
    return res;
  }

  // CTP 轨：发撤单请求；结果由回报驱动（onOrderEvent）。
  hg::ctp_adapter::CtCancelReq creq;
  creq.order_sys_id = rec.order_sys_id;
  creq.order_ref = rec.input.client_order_ref;
  creq.instrument_id = rec.input.instrument_id;
  const int rc = trade_->reqCancel(creq);
  if (rc != 0) {
    res.reason_code = static_cast<std::uint32_t>(rc);
    res.detail = "cancel request rejected by gateway";
    return res;
  }
  res.requested = true;
  return res;
}

bool OrderEngine::onOrderEvent(const hg::ctp_adapter::CtOrderEvent& ev, EngineEvent* out) {
  if (out == nullptr) return false;
  out->kind = EngineEvent::Kind::kNone;
  const OrderRecord* byref = findByRef(ev.client_order_ref);
  if (byref == nullptr) return false;
  OrderRecord* rec = nullptr;
  {
    const std::unordered_map<std::uint64_t, OrderRecord>::iterator it = orders_.find(byref->order_id);
    if (it == orders_.end()) return false;
    rec = &it->second;
  }

  if (ev.kind == hg::ctp_adapter::CtOrderEventKind::kTradeReturn) {
    // 成交回报（幂等键 trade_id，R-TRADE-4）。
    if (ev.trade_id.empty()) return false;
    for (std::size_t k = 0; k < rec->fill_ids.size(); ++k) {
      if (rec->fill_ids[k] == ev.trade_id) return false;  // 重复回报：不重复计入
    }
    rec->fill_ids.push_back(ev.trade_id);
    rec->volume_traded += ev.trade_volume;
    if (rec->volume_traded > rec->volume_total) rec->volume_traded = rec->volume_total;
    rec->volume_left = rec->volume_total - rec->volume_traded;
    if (rec->volume_left <= 0) {
      rec->status = OrderStatus::kFilled;
      releaseFor(rec);  // 全成：冻结转已用（账本释放；快照侧经 CMD_UPDATE_QUOTA 回路）
    } else {
      rec->status = OrderStatus::kPartiallyFilled;
    }
    if (flow_ != nullptr) {
      appendFlow(flow_store::FlowKind::kFilled, *rec, ev.report_ts_ms,
                 "trade " + ev.trade_id);
    }
    out->kind = EngineEvent::Kind::kFill;
    out->fill.trade_id = ev.trade_id;
    out->fill.order_id = rec->order_id;
    out->fill.order_sys_id = rec->order_sys_id;
    out->fill.instrument_id = rec->input.instrument_id;
    out->fill.is_sell = rec->input.direction == risk_engine::OrderDirection::kSell;
    out->fill.is_close = rec->input.offset == risk_engine::OffsetFlag::kClose;
    out->fill.price = static_cast<std::int64_t>(std::lround(ev.price * 1e6));
    out->fill.volume = ev.trade_volume;
    out->fill.trade_ts_ms = ev.report_ts_ms;
    out->fill.sub_account_id = rec->input.sub_account_id;
    out->fill.trade_plan_id = rec->input.trade_plan_id;
    out->fill.exposure_id = rec->input.exposure_id;
    return true;
  }

  if (ev.kind == hg::ctp_adapter::CtOrderEventKind::kOrderReturn) {
    // 终态守卫（TE-T036-001）：已终态指令的委托回报只记流水——不迁移状态、不覆写
    // 快照、不调 releaseFor。阻断「成交回报计满 ⇒ 随附最终委托回报（AllTraded '0'）
    // 二次 releaseFor 侵蚀冻结账本」与迟到/乱序回报回退累计成交两条缺陷链。
    if (isTerminalStatus(rec->status)) {
      if (flow_ != nullptr) {
        appendFlow(flow_store::FlowKind::kReported, *rec, ev.report_ts_ms,
                   "post-terminal order report ignored");
      }
      return false;  // 不产事件（与幂等跳过同形）
    }
    // 委托回报（幂等键 order_sys_id + status，§4.2.3；同形重复不重复变更）。
    if (!ev.order_sys_id.empty()) rec->order_sys_id = ev.order_sys_id;
    if (!ev.order_local_id.empty()) rec->order_local_id = ev.order_local_id;
    if (rec->last_report_key == ev.order_sys_id && rec->last_report_traded == ev.volume_traded &&
        rec->last_report_left == ev.volume_left && rec->last_report_status == ev.status) {
      return false;
    }
    rec->last_report_key = ev.order_sys_id;
    rec->last_report_traded = ev.volume_traded;
    rec->last_report_left = ev.volume_left;
    rec->last_report_status = ev.status;

    rec->volume_total = ev.volume_total > 0 ? ev.volume_total : rec->volume_total;
    // 累计成交单调守卫（TE-T036-001 / 复核 V-1 同根）：CTP 累计成交非降，
    // 迟到回报不得回退；volume_left 与单调后的 traded 保持自洽。
    if (ev.volume_traded > rec->volume_traded) rec->volume_traded = ev.volume_traded;
    rec->volume_left = rec->volume_total - rec->volume_traded;

    // CTP 拒单：伴 ErrorID 的回报（OnRspOrderInsert / OnErrRtnOrderInsert）。
    if (ev.ctp_error_id != 0) {
      rec->status = OrderStatus::kRejected;
      releaseFor(rec);
      if (flow_ != nullptr) {
        appendFlow(flow_store::FlowKind::kRejected, *rec, ev.report_ts_ms, ev.ctp_error_msg);
      }
    } else if (ev.status == '5') {
      // 撤单完成（TE-T036-002：'5' = THOST_FTDC_OST_Canceled，官方 SDK
      // ThostFtdcUserApiDataType.h:587；原注释所引 THOST_FTDC_OST_Rejected 不存在）。
      // 释放冻结保留（复核 V-3：修正分类不得丢释放）。
      rec->status = OrderStatus::kCancelled;
      releaseFor(rec);
      if (flow_ != nullptr) {
        appendFlow(flow_store::FlowKind::kCancelled, *rec, ev.report_ts_ms,
                   "cancelled (CTP OST_Canceled)");
      }
      out->kind = EngineEvent::Kind::kCancelResult;  // EVT_ORDER_CANCEL_RESULT
      out->cancel_out.order_id = rec->order_id;
      out->cancel_out.cancelled_volume = rec->volume_left;
      out->cancel_out.result = 0;
      out->cancel_out.reason_code = 0;
      return true;
    } else if (rec->volume_left <= 0 && rec->volume_traded > 0) {
      rec->status = OrderStatus::kFilled;
      releaseFor(rec);
    } else if (rec->volume_traded > 0) {
      rec->status = OrderStatus::kPartiallyFilled;
    } else {
      rec->status = OrderStatus::kSubmitted;
    }
    if (flow_ != nullptr && rec->status != OrderStatus::kRejected) {
      appendFlow(flow_store::FlowKind::kReported, *rec, ev.report_ts_ms, "order report");
    }
    out->kind = EngineEvent::Kind::kReport;
    out->report.order_id = rec->order_id;
    out->report.order_sys_id = rec->order_sys_id;
    out->report.order_local_id = rec->order_local_id;
    out->report.status = rec->status;
    out->report.volume_total = rec->volume_total;
    out->report.volume_traded = rec->volume_traded;
    out->report.volume_left = rec->volume_left;
    out->report.ctp_error_id = ev.ctp_error_id;
    out->report.ctp_error_msg = ev.ctp_error_msg;
    out->report.report_ts_ms = ev.report_ts_ms;
    return true;
  }

  return false;
}

std::size_t OrderEngine::expirePending(std::int64_t now_ms, EngineEvent* out,
                                       std::size_t max_events) {
  std::size_t n = 0;
  for (std::unordered_map<std::uint64_t, OrderRecord>::iterator it = orders_.begin();
       it != orders_.end(); ++it) {
    OrderRecord& rec = it->second;
    if (rec.status != OrderStatus::kPendingConfirm) continue;
    if (now_ms <= rec.confirm_deadline_ms) continue;
    markTerminal(&rec, OrderStatus::kExpired, now_ms, "confirm timeout");
    if (out != nullptr && n < max_events) {
      EngineEvent& e = out[n];
      e.kind = EngineEvent::Kind::kExpired;
      e.expired.order_id = rec.order_id;
      e.expired.client_order_ref = rec.input.client_order_ref;
      e.expired.expired_ts_ms = now_ms;
      e.expired.timeout_ms = cfg_.confirm_timeout_ms;
    }
    ++n;
  }
  return n;
}

}  // namespace order_engine
}  // namespace hg
