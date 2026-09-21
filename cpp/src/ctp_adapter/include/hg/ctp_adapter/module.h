#ifndef HG_CTP_ADAPTER_MODULE_H
#define HG_CTP_ADAPTER_MODULE_H
// CTP 适配层（ctp_adapter）：模块标识（骨架期占位）。
// 真实对外接口见 docs/ARCHITECTURE.md §3.1。

namespace hg {
namespace ctp_adapter {

// 返回本模块名（骨架期用于构建/自检，可稳定断言）。
const char* module_name();

// CTP 后端：CTP_FOUND=ON → "ctp-sdk"；否则 → "stub"。
const char* backend();

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_ADAPTER_MODULE_H
