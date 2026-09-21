#ifndef HG_RISK_ENGINE_MODULE_H
#define HG_RISK_ENGINE_MODULE_H
// 事前风控引擎（risk_engine）：模块标识（骨架期占位）。
// 真实对外接口见 docs/ARCHITECTURE.md §3.3。

namespace hg {
namespace risk_engine {

// 返回本模块名（骨架期用于构建/自检，可稳定断言）。
const char* module_name();

}  // namespace risk_engine
}  // namespace hg

#endif  // HG_RISK_ENGINE_MODULE_H
