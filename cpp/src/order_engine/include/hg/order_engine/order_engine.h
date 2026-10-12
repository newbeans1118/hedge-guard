#ifndef HG_ORDER_ENGINE_ORDER_ENGINE_H
#define HG_ORDER_ENGINE_ORDER_ENGINE_H
// =============================================================================
// 指令生命周期管理（order_engine，M2）—— 对外接口（T-036；ARCHITECTURE.md §3.2）
//
// 状态机（工单验收 1 / §4.2.6(4) 时序）：
//   受理(R-TRADE-1 前置 + 事前风控) → kPendingConfirm(冻结额度 + 签发 confirm_token)
//   → 人工确认(操作人一致 0x0305 / token 0x0307 / **再跑一次风控**) → 落流水(append+fsync)
//   → 发 CTP → kSubmitted → 回报处理(部分成交/全成/拒单) → 终态；
//   超时(60s, CFG-28) → kExpired + 释放冻结 + EVT_ORDER_EXPIRED + **不发 CTP**；
//   撤单 → kCancelled + 释放冻结（R-TRADE-7）。
//
// 判定纪律（本目录属 E4 扫描根）：
//   · 零锁、零同步 I/O 调用形态、零 SQL；流水落盘经 flow_store（不在 E4 根，T7 合法）。
//   · 时间由调用方注入（`now_ms`）——无隐藏时钟（与 CommandHandler/ServerLoop 同形）。
//   · 本体**零 protobuf 依赖**：pb ↔ 本模块结构体的映射在 hgcp 侧（schema 唯一映射点）。
//
// 冻结额度账本（T4 独占、无锁）：快照不可变（T-034）⇒ 「下单冻结」不能就地改快照；
//   本类按三层额度键维护冻结增量账本，与快照 `used+frozen` 叠加参与 confirm 复核；
//   失效/撤单/终态时释放。口径与 risk_engine `evalQuota` 同式
//   （`used + frozen + req > limit` ⇒ 拒；`unit==LOT` 按手数、否则折实物量）。
//
// confirm_token（§4.2.4 :892「服务端签发、绑定 order_id + 有效期、防重放」）：
//   受理时由 (salt, order_id, accepted_ts_ms) FNV-1a 确定性派生（16 hex）；
//   绑定 order_id + 确认截止时刻；**确认成功即消费**（单次有效，防重放）；
//   发单被退回保留（0x2004）时**不消费**，可重试确认（FR-TRADE-2 验收 4）。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "hg/ctp_adapter/ct_types.h"
#include "hg/flow_store/flow_store.h"
#include "hg/flow_store/replay.h"  // T-041 还原接缝：ReplayOrderState 还原源
#include "hg/order_engine/trade_gateway.h"
#include "hg/risk_engine/order_request.h"
#include "hg/risk_engine/risk_engine.h"
#include "hg/risk_engine/risk_result.h"

namespace hg {
namespace order_engine {

// 指令状态（§4.2.3 OrderStatus 的引擎侧承载；映射到 pb 枚举在 hgcp 侧）。
enum class OrderStatus : std::uint8_t {
  kPendingConfirm = 1,  // 待确认（已冻结额度，未发 CTP）
  kSubmitted = 2,       // 已发 CTP，待回报
  kPartiallyFilled = 3, // 部分成交
  kFilled = 4,          // 全部成交（终态）
  kCancelled = 5,       // 已撤（终态）
  kExpired = 6,         // 确认超时失效（终态）
  kRejected = 7         // 确认期再校验拒绝 / CTP 拒单（终态）
};

const char* orderStatusName(OrderStatus s);

// 受理入参（hgcp 侧由 pbv1::CmdPlaceOrder 映射；字段语义见 §4.2.4）。
struct OrderInput {
  std::string client_order_ref;                 // 幂等键（HG<yyyyMMdd><seq>）
  std::uint64_t trade_plan_id = 0;              // R-TRADE-1：0 = 缺失拒绝
  std::uint64_t exposure_id = 0;                // R-TRADE-1：协议级必填
  std::uint32_t sub_account_id = 0;             // R-TRADE-1：0 = 缺失拒绝
  std::uint32_t product_id = 0;
  std::string instrument_id;                    // 线上字符串（数值串 → 内存 id 映射在 hgcp）
  std::uint32_t instrument_key = 0;             // 内存整数 id（hgcp 侧已解析）
  bool is_option = false;                       // 期权（NFR-COMPAT-6：与期货同链同额）
  risk_engine::OrderDirection direction = risk_engine::OrderDirection::kBuy;
  risk_engine::OffsetFlag offset = risk_engine::OffsetFlag::kOpen;
  risk_engine::PriceType price_type = risk_engine::PriceType::kLimit;
  hg::risk_state::Fixed limit_price = 0;        // 1e6 定点
  std::int32_t volume = 0;                      // 手数 > 0
  std::uint64_t operator_id = 0;
  std::uint64_t strategy_id = 0;                // 期权多腿（R-TRADE-12：同链同额，直通记录）
  std::int32_t leg_no = 0;
  std::uint64_t related_plan_id = 0;
  std::uint64_t related_exposure_id = 0;
  std::int64_t client_ts_ms = 0;

  OrderInput() {}
};

// 引擎配置（CFG-28）。
struct OrderEngineConfig {
  std::int64_t confirm_timeout_ms = 60000;      // HG.TRADE.CONFIRM_TIMEOUT_MS（CFG-28）
  std::uint64_t token_salt = 0x48473336ull;     // confirm_token 派生盐（生产经配置注入）

  OrderEngineConfig() {}
};

// 受理结果：EVT_ORDER_ACCEPTED / EVT_PRE_TRADE_REJECT 的载荷源。
struct AcceptResult {
  bool accepted = false;
  bool duplicate = false;                       // 0x2007：幂等重放既有受理（不重复冻结）
  bool warn = false;                            // 0x1018 价格偏离告警带（仍受理，强制二次确认）
  std::uint64_t order_id = 0;
  std::string confirm_token;
  std::int64_t effective_timeout_ms = 0;
  std::int64_t accepted_ts_ms = 0;
  risk_engine::RiskResult risk;                 // 拒绝 / 告警时承载 EVT_PRE_TRADE_REJECT 载荷

  AcceptResult() {}
};

// 确认结果（CMD_CONFIRM_ORDER → MSG_ACK 载荷源）。
struct ConfirmResult {
  bool sent = false;                            // true = 已落流水并提交 CTP
  std::uint32_t reason_code = 0;                // §4.3.2（0 = 无错误）
  bool retained = false;                        // 0x2004：CTP 未连接退回保留（可重试确认）
  std::string detail;
  risk_engine::RiskResult risk;                 // 确认期再校验拒绝时承载 EVT_PRE_TRADE_REJECT

  ConfirmResult() {}
};

// 撤单结果（CMD_CANCEL_ORDER → MSG_ACK 载荷源；EVT_ORDER_CANCEL_RESULT 由回报驱动）。
struct CancelResult {
  bool requested = false;                       // true = 撤单请求已发出（CTP 轨）
  bool cancelled = false;                       // true = 待确认单直接撤销成功（无 CTP 轨）
  std::uint32_t reason_code = 0;
  std::string detail;

  CancelResult() {}
};

// 引擎产出事件（onOrderEvent / expirePending / cancel 的载荷；hgcp 编码为 EVT_*）。
struct EngineEvent {
  enum class Kind : std::uint8_t {
    kNone = 0, kReport = 1, kFill = 2, kExpired = 3, kCancelResult = 4
  };

  Kind kind = Kind::kNone;

  // kReport → EVT_ORDER_REPORT
  struct Report {
    std::uint64_t order_id = 0;
    std::string order_sys_id;
    std::string order_local_id;
    OrderStatus status = OrderStatus::kSubmitted;
    std::int32_t volume_total = 0;
    std::int32_t volume_traded = 0;
    std::int32_t volume_left = 0;
    std::int32_t ctp_error_id = 0;
    std::string ctp_error_msg;
    std::int64_t report_ts_ms = 0;
  };
  // kFill → EVT_TRADE_FILL
  struct Fill {
    std::string trade_id;
    std::uint64_t order_id = 0;
    std::string order_sys_id;
    std::string instrument_id;
    bool is_sell = false;
    bool is_close = false;
    std::int64_t price = 0;                     // 1e6 定点（double → 定点换算在引擎）
    std::int32_t volume = 0;
    std::int64_t trade_ts_ms = 0;
    std::uint32_t sub_account_id = 0;
    std::uint64_t trade_plan_id = 0;
    std::uint64_t exposure_id = 0;
  };
  // kExpired → EVT_ORDER_EXPIRED
  struct Expired {
    std::uint64_t order_id = 0;
    std::string client_order_ref;
    std::int64_t expired_ts_ms = 0;
    std::int64_t timeout_ms = 0;
  };
  // kCancelResult → EVT_ORDER_CANCEL_RESULT
  struct CancelOut {
    std::uint64_t order_id = 0;
    std::int32_t cancelled_volume = 0;
    std::int32_t result = 0;                    // 0 = 成功；非 0 = reason_code
    std::uint32_t reason_code = 0;
  };

  Report report;
  Fill fill;
  Expired expired;
  CancelOut cancel_out;

  EngineEvent() {}
};

// 单笔指令档案（T4 独占；OrderMap/PendingConfirmMap 承载，§3.2 内部结构）。
struct OrderRecord {
  std::uint64_t order_id = 0;
  OrderStatus status = OrderStatus::kPendingConfirm;
  OrderInput input;
  std::string confirm_token;
  std::int64_t accepted_ts_ms = 0;
  std::int64_t confirm_deadline_ms = 0;
  std::string order_sys_id;
  std::string order_local_id;
  std::int32_t volume_total = 0;
  std::int32_t volume_traded = 0;
  std::int32_t volume_left = 0;
  bool submit_retained = false;                 // 最近一次发单被退回保留（可重试确认）
  // 冻结增量（kQtyScale 定点；三层同键账本的每单份额，终态释放）。
  hg::risk_state::Fixed frozen_l1 = 0;          // 层① (sub, product)
  hg::risk_state::Fixed frozen_l2 = 0;          // 层② (sub, instrument)
  hg::risk_state::Fixed frozen_l3 = 0;          // 层③ (账户, product)
  std::uint64_t submit_flow_count = 0;          // 已落 SENT 流水条数（幂等护栏）
  // 回报幂等锚（R-TRADE-4）。
  std::vector<std::string> fill_ids;            // 已消费成交回报（trade_id）
  std::string last_report_key;
  std::int32_t last_report_traded = -1;
  std::int32_t last_report_left = -1;
  char last_report_status = 0;

  OrderRecord() {}
};

// 指令生命周期引擎（T4 单线程独占；**非线程安全**，与 §2.4.2 一致）。
class OrderEngine {
 public:
  // 不取得所指对象所有权；存活期由调用方保证。
  OrderEngine(risk_engine::RiskEngine* risk, flow_store::FlowStore* flow,
              ITradeGateway* trade, const OrderEngineConfig& cfg = OrderEngineConfig());
  ~OrderEngine();

  // ---- 受理（CMD_PLACE_ORDER 的生效主体）-------------------------------------
  // R-TRADE-1 前置（计划/敞口/子账户缺失拒绝）→ 事前风控 8 规则 → 冻结额度 →
  // 置待确认 + 签发 confirm_token。拒绝时 risk 字段承载 EVT_PRE_TRADE_REJECT 载荷。
  AcceptResult accept(const OrderInput& in, std::int64_t now_ms);

  // ---- 人工确认（CMD_CONFIRM_ORDER）------------------------------------------
  // 状态/超时/操作人(0x0305)/token(0x0307) 门卫 → **再跑一次完整风控** →
  // 落流水(append+fsync，先于发单) → 发 CTP。发单被退回保留 ⇒ token 不消费、可重试。
  ConfirmResult confirm(std::uint64_t order_id, std::uint64_t operator_id,
                        const std::string& confirm_token, std::int64_t now_ms);

  // ---- 撤单（CMD_CANCEL_ORDER）-----------------------------------------------
  // 待确认单：直接撤销 + 释放冻结 + EVT_ORDER_CANCEL_RESULT（无 CTP 轨）。
  // 已发 CTP 单：发撤单请求；结果经 onOrderEvent 回报驱动。
  CancelResult cancel(std::uint64_t order_id, std::uint64_t operator_id,
                      const std::string& reason, std::int64_t now_ms, EngineEvent* out);

  // ---- CTP 回报消费（T4 从 orderEventQueue 取出后调用；§3.2 onOrderEvent）-----
  // 幂等：同 (order_sys_id, status, traded, left) / 同 trade_id 重复回报不重复变更。
  // 终态守卫（TE-T036-001）：已终态（FILLED/REJECTED/CANCELLED/EXPIRED）指令的委托
  // 回报只记流水——不迁移状态、不覆写快照、不调 releaseFor（阻断「成交回报计满 ⇒
  // 随附最终委托回报」二次释放与迟到回报回退累计成交）。
  // 撤单完成分类（TE-T036-002）：OrderStatus '5' = THOST_FTDC_OST_Canceled（官方
  // SDK 无 THOST_FTDC_OST_Rejected）⇒ kCancelled + EVT_ORDER_CANCEL_RESULT；
  // 伴 ErrorID 的拒单回报 ⇒ kRejected。
  // 返回 false = 无匹配指令 / 幂等跳过 / 终态后回报忽略（out 不产生事件）。
  bool onOrderEvent(const hg::ctp_adapter::CtOrderEvent& ev, EngineEvent* out);

  // ---- 超时失效扫描（R-TRADE-6 / CFG-28）-------------------------------------
  // 扫描待确认单，过期者置 kExpired + 释放冻结 + EVT_ORDER_EXPIRED（**不发 CTP**）。
  // 返回产出事件数。装配方（T4 循环）须周期驱动；CommandHandler 在消费交易指令前
  // 也会机会性驱动一次（惰性兜底）。
  std::size_t expirePending(std::int64_t now_ms, EngineEvent* out, std::size_t max_events);

  // ---- 昨结算价缓存（R-RISK-8 基准；行情面接线归 T-039）-----------------------
  // 缺位 ⇒ OrderRequest.prev_settlement_price=0 ⇒ 风控诚实拒 0x1015（无可用基准价）。
  void setPrevSettlement(const std::string& instrument_code, hg::risk_state::Fixed value);

  // ---- 崩溃还原接缝（T-041；启动重放后一次性调用，§4.5.3 ⑤）-------------------
  // 还原指令生命周期到 OrderMap（orders_/ref_index_/next_order_id_）。
  //   · st.terminal==true：还原为与流水末类对应终态（kFilled/kRejected/kExpired/
  //     kCancelled → 同名 OrderStatus；kReported 终态不可判 ⇒ 保守 kSubmitted）；
  //   · st.sent && !st.terminal：在途单（CTP 对账决策由 RecoveryHook 处置，
  //     本方法先还原为 kSubmitted；冻结份额按 hold 入账，见 restoreFrozenHold）；
  //   · !sent && !terminal（pending_unsent）：还原 kPendingConfirm（壳档案；
  //     confirm_deadline 不可还原 ⇒ 置 now_ms + cfg 现值，超时后由 expirePending
  //     正常失效——保守语义：不因还原而永久卡死，也不释放冻结）。
  // 幂等护栏：restore_done_ 一次性（二次调用 = 编排错误，返回 false 拒绝）；
  // next_order_id_ 推进到 max(见过的 order_id)+1（防还原后重号，工单 M4 打点）。
  // 冻结份额不可由 T-036 行格式重算（recovery_hook.h 头注披露）⇒ held 单须由
  // 调用方随后经 restoreFrozenHold 显式入账。
  // 返回实际还原条数；engine 存在终态前状态非空（先还原后使用的编排约束）。
  bool restoreFromReplay(const std::vector<hg::flow_store::ReplayOrderState>& orders,
                         std::int64_t now_ms);

  // 在途/未知单冻结份额入账（配合 restoreFromReplay；份额由调用方外源注入——
  // 装配方按快照+流水外档案推定，T-039 装配裁定；份额 0 ⇒ 不入账仅占档）。
  // 档案须已由 restoreFromReplay 建立；幂等：份额非零且档案冻结份额已非零 ⇒ 拒。
  bool restoreFrozenHold(std::uint64_t order_id, hg::risk_state::Fixed l1,
                         hg::risk_state::Fixed l2, hg::risk_state::Fixed l3);

  // 还原单终态化（T-041 决策执行接缝）：对还原档案执行 markTerminal（终态迁移
  // + 释放冻结 + 流水留痕）。守卫：档案存在且非终态 ⇒ 否则 false（幂等拒绝，
  // 防二次释放/二次留痕）。用途：ReplayDecision 三分类的终态修正（adopt/release
  // expired）与人工核验后的 unknown 单闭环。*detail 为留痕 detail（建议 recovery:
  // 前缀，区分于常规生命周期轨）。
  bool finalizeRecovered(std::uint64_t order_id, OrderStatus st, std::int64_t now_ms,
                         const char* detail);

  // 还原观测（测试/装配诊断；还原完成后 = true）。
  bool restore_done() const { return restore_done_; }

  // ---- 观测面（测试 / 装配诊断）----------------------------------------------
  std::size_t order_count() const { return orders_.size(); }
  std::size_t pending_count() const;
  std::uint64_t next_order_id() const { return next_order_id_; }
  const OrderRecord* find(std::uint64_t order_id) const;
  const OrderRecord* findByRef(const std::string& client_order_ref) const;

  // 冻结账本当前值（三层键；诊断/测试）。
  hg::risk_state::Fixed frozenOf(int layer, std::uint32_t sub, std::uint32_t key) const;

 private:
  OrderEngine(const OrderEngine&);
  OrderEngine& operator=(const OrderEngine&);

  static std::uint64_t packKey(std::uint32_t a, std::uint32_t b);

  // 由受理档案构建风控入参（prev_settlement 取缓存；期限/规模字段由快照/时钟推定）。
  risk_engine::OrderRequest buildRequest(const OrderRecord& rec) const;

  // 冻结 / 释放（三层键各一份；只动账本，不动快照）。
  // releaseFor 释放后**清零档案冻结份额**（剪枝；TE-T036-001：终态后回报若重复进入
  // 释放路径，`>0` 守卫使二次释放成为空操作，冻结账本不会被侵蚀为负）。
  void freezeFor(OrderRecord* rec);
  void releaseFor(OrderRecord* rec);

  // 冻结账本参与的超额守卫（与 evalQuota 同式：used+frozen+账本+本次 > limit ⇒ 拒）。
  // 快照不可变 ⇒ 待确认冻结不可见风控 ⇒ 由本守卫补账，防「同键并行受理超额冻结」。
  // `exclude` 非空时扣除该单自身冻结（confirm 复核时账本已含本单，防自计双算）。
  // 命中返回 true 并填充 *out（规则/码/层级/当前值/阈值，口径同 evalQuota）。
  bool quotaLedgerViolation(const OrderInput& in, risk_engine::RiskResult* out,
                            const OrderRecord* exclude = nullptr) const;

  // 记录消费后的流水（确认成功/终态迁移共用；失败返回 false，调用方决定阻断）。
  bool appendFlow(flow_store::FlowKind kind, const OrderRecord& rec, std::int64_t now_ms,
                  const std::string& detail);

  // 终态迁移 + 释放冻结 + 流水。
  void markTerminal(OrderRecord* rec, OrderStatus st, std::int64_t now_ms,
                    const char* detail);

  // T-041 还原态：一次性护栏（restoreFromReplay 已执行）。
  bool restore_done_ = false;

  risk_engine::RiskEngine* risk_;
  flow_store::FlowStore* flow_;
  ITradeGateway* trade_;
  OrderEngineConfig cfg_;

  std::uint64_t next_order_id_;
  std::unordered_map<std::uint64_t, OrderRecord> orders_;          // order_id → 档案
  std::unordered_map<std::string, std::uint64_t> ref_index_;       // client_order_ref → id
  std::unordered_map<std::uint64_t, hg::risk_state::Fixed> frozen_l1_;
  std::unordered_map<std::uint64_t, hg::risk_state::Fixed> frozen_l2_;
  std::unordered_map<std::uint64_t, hg::risk_state::Fixed> frozen_l3_;
  std::unordered_map<std::string, hg::risk_state::Fixed> prev_settlement_;
};

}  // namespace order_engine
}  // namespace hg

#endif  // HG_ORDER_ENGINE_ORDER_ENGINE_H
