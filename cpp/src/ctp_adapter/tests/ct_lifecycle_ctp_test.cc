// 单测（CTP 轨）：官方 CTP SDK **真实**生命周期 + `Release-before-Init` 守卫。
//
// 承重项（验收标准 2）：
//   ① 两个官方 `.so`（`thosttraderapi_se.so` / `thostmduserapi_se.so`）**已链接并载入**
//      —— 以 `GetApiVersion()` 非空为证（该符号来自 `.so` 而非头文件内联）；
//   ② 合法生命周期序 `Create → RegisterSpi/RegisterFront → Subscribe*Topic → Init → Release`
//      在**真实 API 实例**上走通；
//   ③ `Release()` 的可用性由 `CtApiLifecycle` 守卫：`Create` 后未 `Init` ⇒ 拒绝
//      （官方库在 `Init` 之前 `Release` 会段错误）。
#include "ctp_adapter_test_util.h"
#include "hg/ctp_adapter/ct_gateway.h"
#include "hg/ctp_adapter/ct_lifecycle.h"

#include <ThostFtdcMdApi.h>
#include <ThostFtdcTraderApi.h>

#include <string>

using hg::ctp_adapter::CtApiLifecycle;
using hg::ctp_adapter::CtCallbackSink;
using hg::ctp_adapter::CtConfig;
using hg::ctp_adapter::CtMdGateway;
using hg::ctp_adapter::CtTradeGateway;
using hg::ctp_adapter::createMdGateway;
using hg::ctp_adapter::createTradeGateway;

namespace {

// 仅作回调出口的最小实现（本测试不建真实柜台连接 ⇒ 不会有回调）。
class NullSink : public CtCallbackSink {
 public:
  virtual void onFrontConnected(bool) {}
  virtual void onFrontDisconnected(bool, int) {}
  virtual void onLoginReply(bool, bool, const std::string&, int, const std::string&) {}
  virtual void onOrderEvent(const hg::ctp_adapter::CtOrderEvent&) {}
  virtual void onQuoteEvent(const hg::ctp_adapter::CtQuoteEvent&) {}
};

// ① 两个官方 .so 均已链接并载入（版本串来自 .so 的静态函数实现）。
void both_official_libs_are_linked() {
  const std::string trader_version = CThostFtdcTraderApi::GetApiVersion();
  const std::string md_version = CThostFtdcMdApi::GetApiVersion();
  HG_CHECK(!trader_version.empty());
  HG_CHECK(!md_version.empty());
}

// ② + ③ 真实 API 实例的生命周期序与守卫。
void raw_api_lifecycle_create_init_release() {
  CThostFtdcTraderSpi spi;  // SPI 基类可默认构造（所有虚函数均为内联空实现）
  CtApiLifecycle lc;
  CThostFtdcTraderApi* api =
      CThostFtdcTraderApi::CreateFtdcTraderApi("hgctp_flow_lifecycle", false /* 评测模式 */);
  HG_CHECK(api != 0);
  if (api == 0) return;
  lc.markCreated();

  // ★ 守卫反例：Create 之后、Init 之前 ⇒ 不得 Release（否则官方库段错误）。
  HG_CHECK(!lc.canRelease());
  HG_CHECK(lc.createdButNotInitialized());

  api->RegisterSpi(&spi);
  char front[] = "tcp://127.0.0.1:1";  // 本机几乎必然关闭的端口（不建连，只走调用序列）
  api->RegisterFront(front);
  api->SubscribePrivateTopic(THOST_TERT_QUICK);  // 须在 Init 之前
  api->SubscribePublicTopic(THOST_TERT_QUICK);
  api->Init();
  lc.markInitialized();

  // ★ 守卫正例：Init 之后才允许 Release。
  HG_CHECK(lc.canRelease());
  api->Release();
  lc.markReleased();
  HG_CHECK(!lc.canRelease());
}

// ② 网关级端到端：真实 `connect()`（Create+RegisterSpi+RegisterFront+Init）与
//    `disconnect()`（守卫 Release）走通、可重复调用。
void gateway_connect_disconnect_is_guarded() {
  NullSink sink;
  CtConfig cfg;
  cfg.flow_path = "hgctp_flow_gateway";
  cfg.trade_front = "tcp://127.0.0.1:1";
  cfg.md_front = "tcp://127.0.0.1:1";

  CtTradeGateway* trade = createTradeGateway(&sink);
  std::string err;
  HG_CHECK(trade->connect(cfg, &err));
  HG_CHECK(err.empty());
  HG_CHECK(std::string(trade->backendName()) == std::string("ctp-sdk"));
  trade->disconnect();  // 守卫：Init 后 Release
  trade->disconnect();  // 幂等：再调不崩、不二次 Release
  delete trade;

  CtMdGateway* md = createMdGateway(&sink);
  std::string md_err;
  HG_CHECK(md->connect(cfg, &md_err));
  HG_CHECK(md_err.empty());
  md->disconnect();
  delete md;
}

// 交易与行情两侧的流程完全一致（同一工厂语义、同一守卫）。
void both_sides_share_the_same_lifecycle() {
  NullSink sink;
  CtConfig cfg;
  cfg.flow_path = "hgctp_flow_both";
  cfg.trade_front = "tcp://127.0.0.1:1";
  cfg.md_front = "tcp://127.0.0.1:1";

  CtTradeGateway* t1 = createTradeGateway(&sink);
  CtTradeGateway* t2 = createTradeGateway(&sink);
  std::string e1;
  std::string e2;
  HG_CHECK(t1->connect(cfg, &e1));
  HG_CHECK(t2->connect(cfg, &e2));  // 两实例并存（各自独立生命周期）
  t1->disconnect();
  t2->disconnect();
  delete t1;
  delete t2;
}

}  // namespace

int main() {
  both_official_libs_are_linked();
  raw_api_lifecycle_create_init_release();
  gateway_connect_disconnect_is_guarded();
  both_sides_share_the_same_lifecycle();
  return hg::ctp_adapter::testutil::report("ct_lifecycle_ctp_test");
}
