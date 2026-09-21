#ifndef HG_HGCP_MODULE_H
#define HG_HGCP_MODULE_H
// HGCP 通信（hgcp）：模块标识（骨架期占位）。
// 真实对外接口见 docs/ARCHITECTURE.md §3.6。

namespace hg {
namespace hgcp {

// 返回本模块名（骨架期用于构建/自检，可稳定断言）。
const char* module_name();

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_MODULE_H
