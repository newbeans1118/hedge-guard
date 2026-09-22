// ============================================================================
// T-027 / RSK-02 探路程序：CTP SDK 头文件在 C++11 边界下的编译 / 链接兼容性
//
// 验收标准对应：
//   ① 最小程序 #include CTP 交易头文件，在 -std=c++11 -Wall -Wextra -Werror 下编译通过
//   ② 记录 CTP SDK 版本与编译参数
//   ③ 若冲突 → 提 CH-xxx（L1/L2）上报用户；**不得自行放宽 C++11 边界**
//
// 本程序**不建立任何柜台连接**、**不创建 API 实例**（避免产生流文件/线程等副作用）：
// 只用"头文件可解析 + 类型系统可用 + 静态函数可取"三项客观事实判定兼容性。
// ============================================================================

#if !defined(__cplusplus) || (__cplusplus != 201103L)
#error "hgcp_ctp_probe 必须在 -std=c++11（__cplusplus == 201103L）下编译；不得放宽 C++11 边界"
#endif

#include "hgcp_ctp_headers.h"
#include "hgcp_ctp_probe_build_info.h"

#include <iostream>
#include <string>
#include <type_traits>

namespace {

// 把宏标识符转成字符串（用于打印实际选中的类名，避免"打印常量名字符串"的假证据）
#define HGCP_STR_IMPL(x) #x
#define HGCP_STR(x) HGCP_STR_IMPL(x)

// ---------------------------------------------------------------------------
// 1) 接口形态静态断言：证明头文件里的抽象接口类被完整、正确地解析
// ---------------------------------------------------------------------------
static_assert(std::is_abstract<HGCP_CTP_TRADER_API_CLASS>::value,
              "CTP 交易 Api 应为抽象接口类（含纯虚函数）");
static_assert(std::is_polymorphic<HGCP_CTP_TRADER_API_CLASS>::value,
              "CTP 交易 Api 应为多态类");
static_assert(std::is_abstract<HGCP_CTP_MD_API_CLASS>::value,
              "CTP 行情 Api 应为抽象接口类");
static_assert(std::is_polymorphic<HGCP_CTP_TRADER_SPI_CLASS>::value,
              "CTP 交易 SPI 应为多态回调基类");
static_assert(!std::is_abstract<HGCP_CTP_TRADER_SPI_CLASS>::value,
              "CTP 交易 SPI 不应含纯虚函数（否则无法默认构造）");
static_assert(!std::is_abstract<HGCP_CTP_MD_SPI_CLASS>::value,
              "CTP 行情 SPI 不应含纯虚函数");

// ---------------------------------------------------------------------------
// 2) SPI 派生实现：这是 §3.1 `CtSpiImpl : CThostFtdcTraderSpi` 的最小等价物。
//    官方 CTP v6.7.13（**交付基线**）的交易 SPI 基类为 `CThostFtdcTraderSpi`
//    （头文件 `<ThostFtdcTraderApi.h>`）/ 行情基类为 `CThostFtdcMdSpi`。
//    ⚠️ 历史沿革：`ARCHITECTURE.md` v1.5.1 曾按 `CTP Mini` **合并版**把交易 SPI 基类
//       勘误为 `CThostFtdcUserSpi`（头文件 `<ThostFtdcUserApi.h>`）；官方 SDK
//       **不存在**该头/类 ⇒ 该勘误**对交付基线不成立**（`CH-015`，本件已重做）。
//    使用 `override`（C++11）——签名一旦漂移即编译失败，正是我们要的"契约级"探针。
// ---------------------------------------------------------------------------
class CtProbeSpi : public HGCP_CTP_TRADER_SPI_CLASS {
 public:
  CtProbeSpi() : connected_(false) {}
  virtual ~CtProbeSpi() {}

  virtual void OnFrontConnected() override { connected_ = true; }
  virtual void OnFrontDisconnected(int nReason) override {
    connected_ = false;
    last_reason_ = nReason;
  }

  bool connected() const { return connected_; }
  int last_reason() const { return last_reason_; }

 private:
  bool connected_;
  int last_reason_;
};

class CtMdProbeSpi : public HGCP_CTP_MD_SPI_CLASS {
 public:
  CtMdProbeSpi() : connected_(false) {}
  virtual ~CtMdProbeSpi() {}

  virtual void OnFrontConnected() override { connected_ = true; }
  virtual void OnFrontDisconnected(int nReason) override {
    connected_ = false;
    last_reason_ = nReason;
  }

  bool connected() const { return connected_; }
  int last_reason() const { return last_reason_; }

 private:
  bool connected_;
  int last_reason_;
};

// ---------------------------------------------------------------------------
// 3) 成员函数指针：证明 §3.1 所需的交易/资金划转能力在本 SDK 上真实存在，
//    且签名与架构契约一致（签名不符 → 类型不匹配 → 编译失败）。
// ---------------------------------------------------------------------------
typedef int (HGCP_CTP_TRADER_API_CLASS::*ReqUserLoginFn)(CThostFtdcReqUserLoginField*, int);
typedef int (HGCP_CTP_TRADER_API_CLASS::*ReqOrderInsertFn)(CThostFtdcInputOrderField*, int);
typedef int (HGCP_CTP_TRADER_API_CLASS::*ReqOrderActionFn)(CThostFtdcInputOrderActionField*, int);
// `SubscribePrivateTopic` 的参数元数随 SDK 形态不同（见 hgcp_ctp_headers.h 生成定义）：
//   官方分离式 = `(THOST_TE_RESUME_TYPE, int nSeqNo=1)`；`CTP Mini` 合并版 = `(THOST_TE_RESUME_TYPE)`。
#if HGCP_CTP_PRIVATE_TOPIC_ARITY == 2
typedef void (HGCP_CTP_TRADER_API_CLASS::*SubscribePrivateTopicFn)(THOST_TE_RESUME_TYPE, int);
#else
typedef void (HGCP_CTP_TRADER_API_CLASS::*SubscribePrivateTopicFn)(THOST_TE_RESUME_TYPE);
#endif
typedef void (HGCP_CTP_TRADER_API_CLASS::*RegisterSpiFn)(HGCP_CTP_TRADER_SPI_CLASS*);

const ReqUserLoginFn        kReqUserLogin        = &HGCP_CTP_TRADER_API_CLASS::ReqUserLogin;
const ReqOrderInsertFn      kReqOrderInsert      = &HGCP_CTP_TRADER_API_CLASS::ReqOrderInsert;
const ReqOrderActionFn      kReqOrderAction      = &HGCP_CTP_TRADER_API_CLASS::ReqOrderAction;
const SubscribePrivateTopicFn kSubscribePrivate  = &HGCP_CTP_TRADER_API_CLASS::SubscribePrivateTopic;
const RegisterSpiFn         kRegisterSpi         = &HGCP_CTP_TRADER_API_CLASS::RegisterSpi;

typedef int (HGCP_CTP_MD_API_CLASS::*MdSubscribeFn)(char*[], int);
const MdSubscribeFn kMdSubscribe = &HGCP_CTP_MD_API_CLASS::SubscribeMarketData;

// 成员函数指针的"绑定成功"由**编译期**证明（类型不匹配即编译失败）。
// 运行期只把它们喂给 sink 以避免 -Wunused-variable；**不得**与 NULL 比较
// （`&Class::method` 恒非空，GCC 会以 -Werror=address 拒绝——本项目实测结论）。
template <typename T>
void sink_ptr(T) {}

}  // namespace

int main() {
  const CtProbeSpi spi;
  const CtMdProbeSpi md_spi;

  std::cout << "---- HGCP CTP probe / RSK-02 兼容性记录 ----\n";
  std::cout << "cxx.standard.requested     : c++" << HGCP_CTP_PROBE_CXX_STANDARD << "\n";
  std::cout << "cxx.standard.__cplusplus   : " << static_cast<long>(__cplusplus) << "\n";
  std::cout << "cxx.standard.is_cxx11      : " << ((__cplusplus == 201103L) ? "YES" : "NO") << "\n";
  std::cout << "compiler                   : " << __VERSION__ << "\n";
  std::cout << "cxx.flags                  : " << HGCP_CTP_PROBE_CXX_FLAGS << "\n";
  std::cout << "build.type                 : " << HGCP_CTP_PROBE_BUILD_TYPE << "\n";
  std::cout << "sdk.root                   : " << HGCP_CTP_PROBE_SDK_ROOT << "\n";
  std::cout << "sdk.root.source            : " << HGCP_CTP_PROBE_SDK_ROOT_SOURCE << "\n";
  std::cout << "sdk.header.style           : " << HGCP_CTP_HEADER_STYLE << "\n";
  std::cout << "sdk.trader.header          : " << HGCP_CTP_TRADER_HEADER << "\n";
  std::cout << "sdk.md.header              : " << HGCP_CTP_MD_HEADER << "\n";
  std::cout << "sdk.trader.api.class       : " << HGCP_STR(HGCP_CTP_TRADER_API_CLASS) << "\n";
  std::cout << "sdk.trader.spi.class       : " << HGCP_STR(HGCP_CTP_TRADER_SPI_CLASS) << "\n";
  std::cout << "sdk.linked                 : " << HGCP_CTP_PROBE_LINKED << "\n";
  std::cout << "sdk.libs                   : " << HGCP_CTP_PROBE_LIBS << "\n";

  // 关键类型尺寸：证明数据类型头（`ThostFtdcUserApiStruct.h` 的 struct / enum）被完整解析
  std::cout << "sizeof.InputOrderField     : " << sizeof(CThostFtdcInputOrderField) << "\n";
  std::cout << "sizeof.OrderField          : " << sizeof(CThostFtdcOrderField) << "\n";
  std::cout << "sizeof.TradeField          : " << sizeof(CThostFtdcTradeField) << "\n";
  std::cout << "sizeof.DepthMarketDataField: " << sizeof(CThostFtdcDepthMarketDataField) << "\n";
  std::cout << "sizeof.TradingAccountField : " << sizeof(CThostFtdcTradingAccountField) << "\n";
  std::cout << "sizeof.ReqTransferField    : " << sizeof(CThostFtdcReqTransferField) << "\n";

  // SPI 实例可构造/析构（证明回调基类完整可用，无需为每个回调提供实现）
  // 注：`connected()` 反映的是"是否已连柜台"，探路不建连，故恒为 false；
  //     此处打印的是"默认构造 + 析构成功"这一事实（能走到本行即成立）。
  std::cout << "spi.default_constructible  : YES (CtProbeSpi / CtMdProbeSpi 均已构造与析构)\n";
  std::cout << "spi.connected              : " << (spi.connected() ? "true" : "false")
            << " (探路不建柜台连接，预期 false)\n";
  std::cout << "md.spi.connected           : " << (md_spi.connected() ? "true" : "false")
            << " (探路不建柜台连接，预期 false)\n";

  // 成员函数指针"已绑定"：由上面的 typedef + 取地址在**编译期**证明。
  sink_ptr(kReqUserLogin);
  sink_ptr(kReqOrderInsert);
  sink_ptr(kReqOrderAction);
  sink_ptr(kSubscribePrivate);
  sink_ptr(kRegisterSpi);
  sink_ptr(kMdSubscribe);
  std::cout << "api.surface.bound          : YES (编译期绑定：ReqUserLogin/ReqOrderInsert/"
               "ReqOrderAction/SubscribePrivateTopic/RegisterSpi/SubscribeMarketData)\n";

#if HGCP_CTP_PROBE_LINKED_NUM
  // 仅在链接到真实 SDK 动态库时执行：读取 SDK 自报版本（不含任何凭证信息）
  std::cout << "sdk.api.version.trader     : " << HGCP_CTP_TRADER_API_CLASS::GetApiVersion() << "\n";
  std::cout << "sdk.api.version.md         : " << HGCP_CTP_MD_API_CLASS::GetApiVersion() << "\n";
#else
  std::cout << "sdk.api.version.trader     : (未链接 SDK 动态库，未取值)\n";
  std::cout << "sdk.api.version.md         : (未链接 SDK 动态库，未取值)\n";
#endif
  std::cout << "-------------------------------------------\n";

  std::cout << "RESULT: PASS (C++11 下 CTP 头文件编译通过；接口形态与签名符合架构契约)\n";
  return 0;
}
