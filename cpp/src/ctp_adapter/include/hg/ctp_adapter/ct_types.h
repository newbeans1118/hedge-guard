#ifndef HG_CTP_ADAPTER_CT_TYPES_H
#define HG_CTP_ADAPTER_CT_TYPES_H
// =============================================================================
// CTP 适配层（ctp_adapter）—— 公共领域类型与组件错误码（M1）
//
// 承载：docs/ARCHITECTURE.md §3.1（对外接口与返回形态）、§4.3.2（组件错误码）、
//       §2.4.2（T2/T3 回调线程）、§2.4.3（orderEventQueue / quoteQueue 满时策略）；
//       docs/REQUIREMENTS/TRADE.md FR-TRADE-2/4/5/6/12；FUND.md FR-FUND-5/6/8；
//       NONFUNCTIONAL.md NFR-COMPAT-6。
//
// 设计口径：
//   · **异步回调模型**：所有请求方法只表达「请求已交给 CTP（或未连接被退回）」，
//     **不假设同步返回**；真实结果经 SPI 回调序列化为 `CtOrderEvent` / `CtQuoteEvent`
//     投递到队列，由 T4 / T8 消费（NFR-COMPAT-6）。
//   · **零落盘红线**：口令 / 资金密码仅内存持有（`CtConfig` / `CtFundReq`），
//     不得写日志（提供 `redacted()`）、不得落盘（本模块不写任何配置文件）。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace hg {
namespace ctp_adapter {

// -----------------------------------------------------------------------------
// 组件错误码（ARCHITECTURE.md §4.3.2；仅列本模块当前使用的码，取值与 §4.3.2 逐字一致）
//   未连接 → 0x2004 CTP_NOT_CONNECTED（FR-TRADE-2 验收 4：指令退回、不静默丢弃）
// -----------------------------------------------------------------------------
typedef std::uint32_t CtCode;
static const CtCode kCtOk = 0x0000u;                     // OK
static const CtCode kCtBackpressure = 0x0304u;           // ERR_BACKPRESSURE（请求队列满）
static const CtCode kCtOrderNotFound = 0x2001u;          // ORDER_NOT_FOUND
static const CtCode kCtOrderStateNotAllowed = 0x2002u;   // ORDER_STATE_NOT_ALLOWED
static const CtCode kCtOrderConfirmTimeout = 0x2003u;    // ORDER_CONFIRM_TIMEOUT
static const CtCode kCtNotConnected = 0x2004u;           // CTP_NOT_CONNECTED
static const CtCode kCtOrderRejected = 0x2005u;          // CTP_ORDER_REJECTED
static const CtCode kCtCancelNoVolume = 0x2006u;         // CANCEL_NO_VOLUME
static const CtCode kCtOrderDuplicate = 0x2007u;         // ORDER_DUPLICATE
static const CtCode kCtFundTransferDuplicate = 0x2008u;  // FUND_TRANSFER_DUPLICATE
static const CtCode kCtFundInsufficientAvailable = 0x2009u;
static const CtCode kCtUnsupportedOperation = 0x3003u;   // UNSUPPORTED_OPERATION（入参非法）

// -----------------------------------------------------------------------------
// 领域枚举
// -----------------------------------------------------------------------------
// 标的类别：期货 / 期权（PROJECT.md §10-10；两者共用同一 UserApi 与同一套保额度）
enum class CtMarket { kFutures, kOption };
// 买卖方向（CTP Direction：'0'=买 / '1'=卖）
enum class CtDirection { kBuy, kSell };
// 开平标志（CTP CombOffsetFlag：'0'=开仓 / '1'=平仓 / '3'=平今）
enum class CtOffset { kOpen, kClose, kCloseToday };
// 报单价格条件（CTP OrderPriceType：'2'=限价 / '1'=市价）
enum class CtPriceType { kLimit, kMarket };
// 银期划转方向：入金（银行→期货）/ 出金（期货→银行）
enum class CtFundDirection { kIn, kOut };

// -----------------------------------------------------------------------------
// 登录配置（由 T1 装配；口令仅内存持有）
// -----------------------------------------------------------------------------
struct CtConfig {
  std::string trade_front;  // 交易前置：tcp://ip:port
  std::string md_front;     // 行情前置：tcp://ip:port
  std::string broker_id;    // 经纪公司代码
  std::string user_id;      // 投资者 / 用户代码
  std::string password;     // **敏感**：不得打印、不得落盘
  std::string flow_path;    // CTP 流文件目录
  std::string auth_code;    // 客户端认证码（可选；ReqAuthenticate）
  std::string app_id;       // 客户端认证 AppID（可选）

  CtConfig() {}
  // 供日志使用的安全呈现：口令一律替换为占位，**不得**返回明文（NFR-SEC-1 / R-FUND-9）。
  std::string redacted() const {
    std::string s;
    s += "trade_front=";
    s += trade_front;
    s += " md_front=";
    s += md_front;
    s += " broker_id=";
    s += broker_id;
    s += " user_id=";
    s += user_id;
    s += " password=";
    s += (password.empty() ? "<empty>" : "<redacted>");
    s += " flow_path=";
    s += flow_path;
    s += " auth_code=";
    s += (auth_code.empty() ? "<empty>" : "<redacted>");
    s += " app_id=";
    s += (app_id.empty() ? "<empty>" : "<redacted>");
    return s;
  }
};

// 当前时刻（Unix 毫秒）。回报 / 行情无统一时戳形态，适配层以本地接收时刻补齐，
// 供事件排序与行情过期判定（FR-TRADE-12 验收 2）使用。
std::int64_t nowMillis();

// -----------------------------------------------------------------------------
// 请求报文（T4 → 适配层）
// -----------------------------------------------------------------------------
struct CtOrderReq {
  std::string client_order_ref;       // 客户端报单引用（幂等键；CTP OrderRef）
  std::string exchange_id;
  std::string instrument_id;
  CtMarket market;                    // 期货 / 期权
  CtDirection direction;
  CtOffset offset;
  CtPriceType price_type;
  double limit_price;                 // price_type==kLimit 时必填且 > 0
  int volume;                         // 手数，必须 > 0

  CtOrderReq()
      : market(CtMarket::kFutures), direction(CtDirection::kBuy),
        offset(CtOffset::kOpen), price_type(CtPriceType::kLimit),
        limit_price(0.0), volume(0) {}
};

struct CtCancelReq {
  std::string exchange_id;
  std::string order_sys_id;  // 交易所报单编号（撤单主键）
  std::string order_ref;     // 可选
  std::string instrument_id; // 可选（部分柜台要求）
  CtCancelReq() {}
};

struct CtFundReq {
  std::string idempotency_key;   // 幂等标识（FR-FUND-8 / 0x2008）
  CtFundDirection direction;
  std::string account_id;        // 期货资金账户
  std::string bank_id;           // 银行代码
  std::string bank_account;      // 银行账号
  std::string bank_password;     // **敏感**：不得打印、不得落盘
  std::string currency_id;       // 币种（默认 CNY）
  double amount;                 // 金额，必须 > 0

  CtFundReq()
      : direction(CtFundDirection::kIn), currency_id("CNY"), amount(0.0) {}
};

// -----------------------------------------------------------------------------
// 请求回执（异步受理语义：**不表示业务结果**）
// -----------------------------------------------------------------------------
struct CtLoginResult {
  CtCode code;
  std::string trading_day;
  std::string error_msg;
  CtLoginResult() : code(kCtOk) {}
};

// sendOrder 回执。`retained==true` 表示指令**未被发出且被退回保留**（未静默丢弃），
// 由交易岗/order_engine 重试确认（FR-TRADE-2 验收 4）。
struct CtOrderAck {
  CtCode code;
  std::string client_order_ref;
  std::string order_local_id;  // 仅当 CTP 已受理（非零值由回报补齐）
  bool retained;
  CtOrderAck() : code(kCtOk), retained(false) {}
};

struct CtCancelAck {
  CtCode code;
  std::string error_msg;
  CtCancelAck() : code(kCtOk) {}
};

struct CtSubscribeAck {
  CtCode code;
  int accepted;  // 交给 CTP 的合约个数
  CtSubscribeAck() : code(kCtOk), accepted(0) {}
};

struct CtFundAck {
  CtCode code;
  std::string error_msg;
  CtFundAck() : code(kCtOk) {}
};

// -----------------------------------------------------------------------------
// 归一化事件（SPI 回调**只做**「序列化并投递」的目标形态，§3.1）
// -----------------------------------------------------------------------------
enum class CtOrderEventKind {
  kOrderReturn,  // 委托回报（OnRtnOrder / OnRspOrderInsert）
  kTradeReturn,  // 成交回报（OnRtnTrade）
  kOrderError,   // 报单错误回报（OnErrRtnOrderInsert / OnErrRtnOrderAction）
  kFundReceipt   // 资金划转回执（银期转帐应答 / 通知）
};

struct CtOrderEvent {
  CtOrderEventKind kind;
  std::string client_order_ref;  // CTP OrderRef
  std::string order_local_id;    // CTP OrderLocalID
  std::string order_sys_id;      // CTP OrderSysID
  std::string instrument_id;
  char status;                   // CTP OrderStatus（kOrderReturn）
  int volume_total;
  int volume_traded;
  int volume_left;
  int ctp_error_id;
  std::string ctp_error_msg;
  std::int64_t report_ts_ms;     // 本地接收时刻（回报无统一时间戳；用于审计排序）
  // ---- 成交（kTradeReturn）----
  std::string trade_id;
  double price;
  int trade_volume;
  char direction;                // CTP Direction
  char offset_flag;              // CTP OffsetFlag
  // ---- 资金回执（kFundReceipt）----
  std::string idempotency_key;   // 由 CtFundReq 携带并原样回带
  double fund_amount;
  std::string currency_id;

  CtOrderEvent()
      : kind(CtOrderEventKind::kOrderReturn), status(0), volume_total(0),
        volume_traded(0), volume_left(0), ctp_error_id(0), report_ts_ms(0),
        price(0.0), trade_volume(0), direction(0), offset_flag(0),
        fund_amount(0.0) {}
};

struct CtQuoteEvent {
  std::string instrument_id;
  std::string exchange_id;
  double last_price;
  double pre_settlement_price;
  double bid_price1;
  double ask_price1;
  int bid_volume1;
  int ask_volume1;
  std::string update_time;      // CTP UpdateTime（数据时间；FR-TRADE-12 验收 1）
  int update_millisec;          // CTP UpdateMillisec
  std::int64_t local_recv_ts_ms;  // 本地接收时刻（过期判定用）

  CtQuoteEvent()
      : last_price(0.0), pre_settlement_price(0.0), bid_price1(0.0),
        ask_price1(0.0), bid_volume1(0), ask_volume1(0), update_millisec(0),
        local_recv_ts_ms(0) {}
};

// -----------------------------------------------------------------------------
// 队列容量（ARCHITECTURE.md §2.4.3 固定值）
// -----------------------------------------------------------------------------
// orderEventQueue：生产者 T2 / 消费者 T4；满时策略 = 告警 + 自旋等待（不得丢回报）。
static const std::size_t kOrderEventQueueCapacity = 8192;
// quoteQueue：生产者 T3 / 消费者 T8；满时策略 = 丢弃最旧（行情可丢）。
static const std::size_t kQuoteQueueCapacity = 4096;

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_ADAPTER_CT_TYPES_H
