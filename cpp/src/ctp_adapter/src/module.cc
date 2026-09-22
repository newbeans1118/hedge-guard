// CTP 适配层（ctp_adapter） —— 骨架期实现（ARCHITECTURE.md §3.1）。
#include "hg/ctp_adapter/module.h"

#if HG_CTP_FOUND
// 官方分离式 CTP 交易头（交付基线 = 官方 CTP v6.7.13）。
// 以 SYSTEM 头引入（见本模块 CMakeLists.txt），抑制第三方头 -Wunused-parameter。
#include <ThostFtdcTraderApi.h>
#endif

namespace hg {
namespace ctp_adapter {

const char* module_name() { return "ctp_adapter"; }

const char* backend() {
#if HG_CTP_FOUND
  return "ctp-sdk";
#else
  return "stub";
#endif
}

}  // namespace ctp_adapter
}  // namespace hg
