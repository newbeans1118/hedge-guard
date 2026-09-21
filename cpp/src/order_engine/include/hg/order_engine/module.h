#ifndef HG_ORDER_ENGINE_MODULE_H
#define HG_ORDER_ENGINE_MODULE_H
// 指令生命周期管理（order_engine）：模块标识（骨架期占位）。
// 真实对外接口见 docs/ARCHITECTURE.md §3.2。

namespace hg {
namespace order_engine {

// 返回本模块名（骨架期用于构建/自检，可稳定断言）。
const char* module_name();

}  // namespace order_engine
}  // namespace hg

#endif  // HG_ORDER_ENGINE_MODULE_H
