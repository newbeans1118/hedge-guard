#ifndef HG_ORDER_ENGINE_TRADE_GATEWAY_H
#define HG_ORDER_ENGINE_TRADE_GATEWAY_H
// =============================================================================
// order_engine → ctp_adapter 发送面接缝（T-036）。
//
// 为什么经接口而非直持 `CtAdapter*`：§3.2 依赖清单里 order_engine 依赖 ctp_adapter，
// 但 T4 判定纪律（E3/E4）要求 order_engine 单测可以在**无 CTP、无真实适配器**下驱动
// 状态机（NFR-TEST 精神）；接口 + 薄桥（`CtGatewayBridge`）使「受理 → 落流水 → 发单」
// 的顺序不变量可被假件观测（记录调用次序），生产装配注入 CtAdapter 桥。
//
// ⚠️ 命名纪律：本目录属 `E4` 扫描根 —— **不得出现/命名** `send`/`write`/`read`/
//    `recv` 等同步 I/O 形态词（派发至 ctp_adapter 的动作一律叫 `submit*`/`req*`）。
// =============================================================================

#include "hg/ctp_adapter/ct_types.h"

namespace hg {
namespace ctp_adapter {
class CtAdapter;  // 前置声明（桥的持有仅为指针；完整类型仅实现在 .cc 引入）
}  // namespace ctp_adapter
namespace order_engine {

// 发送面接缝（异步受理语义与 CtAdapter 一致：返回只表示「已交给 CTP / 被退回」）。
struct SubmitAck {
  int code = 0;                 // CtCode（0 = kCtOk）
  std::string client_order_ref;
  std::string order_local_id;
  bool retained = false;        // CTP 未连接 ⇒ 退回保留（FR-TRADE-2 验收 4）

  SubmitAck() {}
};

class ITradeGateway {
 public:
  virtual ~ITradeGateway() {}
  // 提交报单（映射 CtAdapter::sendOrder）。
  virtual SubmitAck submitOrder(const hg::ctp_adapter::CtOrderReq& req) = 0;
  // 请求撤单（映射 CtAdapter::cancelOrder）。返回 0 = 已受理请求。
  virtual int reqCancel(const hg::ctp_adapter::CtCancelReq& req) = 0;
};

// 生产桥：包一层 CtAdapter（不取得所有权；存活期由调用方保证）。
// 注：方法名避开 E4 禁词；对 CtAdapter 的调用发生在 T4，属其设计归属（§2.4.2）。
class CtGatewayBridge : public ITradeGateway {
 public:
  explicit CtGatewayBridge(hg::ctp_adapter::CtAdapter* adapter) : adapter_(adapter) {}

  SubmitAck submitOrder(const hg::ctp_adapter::CtOrderReq& req) override;
  int reqCancel(const hg::ctp_adapter::CtCancelReq& req) override;

 private:
  hg::ctp_adapter::CtAdapter* adapter_;
};

}  // namespace order_engine
}  // namespace hg

#endif  // HG_ORDER_ENGINE_TRADE_GATEWAY_H
