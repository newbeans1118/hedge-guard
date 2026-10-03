// 发送面接缝的生产桥（T-036）：包装 CtAdapter（异步受理语义原样透传）。
#include "hg/order_engine/trade_gateway.h"

#include "hg/ctp_adapter/ct_adapter.h"

namespace hg {
namespace order_engine {

SubmitAck CtGatewayBridge::submitOrder(const hg::ctp_adapter::CtOrderReq& req) {
  const hg::ctp_adapter::CtOrderAck a = adapter_->sendOrder(req);
  SubmitAck out;
  out.code = static_cast<int>(a.code);
  out.client_order_ref = a.client_order_ref;
  out.order_local_id = a.order_local_id;
  out.retained = a.retained;
  return out;
}

int CtGatewayBridge::reqCancel(const hg::ctp_adapter::CtCancelReq& req) {
  const hg::ctp_adapter::CtCancelAck a = adapter_->cancelOrder(req);
  return static_cast<int>(a.code);
}

}  // namespace order_engine
}  // namespace hg
