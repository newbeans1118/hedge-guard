// CTP 适配层（ctp_adapter） —— 骨架期实现（ARCHITECTURE.md §3.1）。
#include "hg/ctp_adapter/module.h"

#if HG_CTP_FOUND
// 合并版 CTP 交易 SPI 头（PROJECT.md §5.1：ThostFtdc 合并版 UserApi）。
// 以 SYSTEM 头引入（见本模块 CMakeLists.txt），抑制第三方头 -Wunused-parameter。
#include <ThostFtdcUserApi.h>
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
