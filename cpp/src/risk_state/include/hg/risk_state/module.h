#ifndef HG_RISK_STATE_MODULE_H
#define HG_RISK_STATE_MODULE_H
// 风控内存状态（risk_state）：模块标识（骨架期占位）。
// 真实对外接口见 docs/ARCHITECTURE.md §3.4。

namespace hg {
namespace risk_state {

// 返回本模块名（骨架期用于构建/自检，可稳定断言）。
const char* module_name();

}  // namespace risk_state
}  // namespace hg

#endif  // HG_RISK_STATE_MODULE_H
