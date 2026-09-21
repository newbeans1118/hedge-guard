#ifndef HG_FLOW_STORE_MODULE_H
#define HG_FLOW_STORE_MODULE_H
// 指令流水与重放（flow_store）：模块标识（骨架期占位）。
// 真实对外接口见 docs/ARCHITECTURE.md §3.5。

namespace hg {
namespace flow_store {

// 返回本模块名（骨架期用于构建/自检，可稳定断言）。
const char* module_name();

}  // namespace flow_store
}  // namespace hg

#endif  // HG_FLOW_STORE_MODULE_H
