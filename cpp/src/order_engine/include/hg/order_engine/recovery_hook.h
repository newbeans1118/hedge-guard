#ifndef HG_ORDER_ENGINE_RECOVERY_HOOK_H
#define HG_ORDER_ENGINE_RECOVERY_HOOK_H
// =============================================================================
// 崩溃恢复集成（order_engine 侧消费，T-041）—— 决策执行 + OrderMap 还原接缝
//
// 依据：ARCHITECTURE.md §4.5.3 ⑤⑥（重建 OrderMap / 按 CTP 结果修正 / 释放冻结
//       + 补发 EVT_ORDER_EXPIRED）；TASKS §2 T-041 产出 `recovery_hook.cc`；
//       replay.h 头注「决策消费执行主体 = T-041」。
//
// 交付形态（工单路径裁量，见回执）：落 `order_engine/src/recovery_hook.cc`，
//   与现行布局（src/ 内 .cc）一致。
//
// ---- 还原接缝设计（为何是引擎公开方法而非独立类）---------------------------
//   还原须直接落 OrderMap（orders_ / ref_index_ / next_order_id_）与冻结账本，
//   全部为 OrderEngine 私有态；独立类需 friend 或公开可变访问器（后者把写面
//   暴露给任意调用方，破坏 T4 单写者纪律）。⇒ 以**受限公开方法**承载：
//   `restoreFromReplay`（一次性的、启动重放后调用，幂等护栏防二次还原）+
//   `restoreFrozenHold`（unknown/live 单冻结份额还原）。二者不改既有状态机
//   语义（工单：「不动 order_engine.cc 既有状态机语义，只允许加接缝方法」）。
//
// ---- 数据边界（诚实披露）---------------------------------------------------
//   T-036 冻结行格式 `ts|KIND|order_id|ref|detail` **不承载指令要素**（风控
//   入参/冻结份额在档案内）。⇒ 还原精度受两级约束：
//     · 终态/在途单：状态与生命周期可精确还原（KIND 序），档案要素不可还原 ⇒
//       OrderRecord 以壳档案承载（ref/order_id/状态时间线，input 空壳）；
//       冻结份额**不可由流水重算** ⇒ 冻结还原交 unknown 单路径（见下）。
//     · unknown 单（kTimeout）：冻结份额同样不可重算 ⇒ restoreFrozenHold 由
//       **调用方**注入份额（装配方经风险快照+档案外源，T-039 装配裁定）；
//       接缝只负责「按份额入账 + 不重复入账」。
//   该披露不削弱验收 1（无重复无遗漏以**状态**与**账本非负/守恒**断言承载，
//   测试显式覆盖），不扩 T-036 行格式（CH 级，不开）。
// =============================================================================

#include <cstdint>
#include <string>
#include <vector>

#include "hg/flow_store/recovery.h"
#include "hg/flow_store/replay.h"
#include "hg/order_engine/order_engine.h"

namespace hg {
namespace order_engine {

// ---- 决策执行结果（recovery_hook 的回执载荷）--------------------------------

struct RecoveryExecOutcome {
  std::size_t adopted = 0;          // kAdoptTerminal 已按 CTP 终态修正
  std::size_t released = 0;         // kReleaseExpired 已终态化 + EVT_ORDER_EXPIRED 补发
  std::size_t unknown_held = 0;     // kUnknownKeepFrozen(kTimeout) 冻结保留 + 台账登记
  std::size_t skipped = 0;          // kLive / 无档案等保守跳过（计数，不硬错）
  std::vector<EngineEvent> events;  // 补发事件（kExpired；调用方经 emitter 发出）

  RecoveryExecOutcome() {}
};

// ---- RecoveryHook：决策消费执行主体（T4 单线程独占，非线程安全）--------------

// 执行 ReplayReport.decisions + pending_unsent 还原；持台账（人工核验/门禁读面）。
class RecoveryHook {
 public:
  // 不取得所指对象所有权；存活期由调用方保证。
  RecoveryHook(OrderEngine* engine, flow_store::RecoveryCoordinator* ledger);

  // 验收 1/2 主链：消费重放产出（须在 ReplayController.run 之后、READY 前）。
  //   · decisions 逐条执行（kAdoptTerminal / kReleaseExpired / kUnknownKeepFrozen）；
  //   · unknown(kTimeout) 决策登记台账（Coordinator::consume）。
  // 返回执行回执；重复调用幂等护栏（全局单次，防二次修正/二次释放/二次入账）。
  // held 纪律：unknown 单冻结份额**不预入账**（行格式不可重算，见头注）；人工
  // 核验释放时先经 restoreFrozenHold 入账再 finalizeRecovered 终态化——「未核验
  // ⇒ 份额不入账亦不入引擎冻结账本」与「已核验 ⇒ 释放」在账本侧等价（0 增量），
  // 引擎账本单调非负；核验留痕由 finalizeRecovered 走 markTerminal→appendFlow
  // （kExpired + recovery: 前缀，复用 T-036 契约轨，无第二留痕通道）。
  RecoveryExecOutcome execute(const flow_store::ReplayReport& report, std::int64_t now_ms);

  // 验收 3：人工核验并释放（双人复核机械守卫在 Coordinator::manualVerifyRelease）。
  // 成功 = 台账闭环 + 引擎终态化（kExpired，冻结释放 + 流水留痕）+ 事件补发
  // （out_events 可空）。失败 = false，台账与引擎零变更，*err 承载拒因。
  bool manualRelease(const flow_store::ManualReleaseRequest& req, std::int64_t now_ms,
                     flow_store::ManualReleaseError* err, EngineEvent* out_events);

  // 台账透传（0x0401 门禁读面 / 人工核验入口由 Coordinator 承载，见 recovery.h）。
  bool has_unknown_open() const;

 private:
  RecoveryHook(const RecoveryHook&);
  RecoveryHook& operator=(const RecoveryHook&);

  OrderEngine* engine_;
  flow_store::RecoveryCoordinator* ledger_;
  bool executed_ = false;  // 幂等护栏：execute 全局单次（还原不可重复）
};

}  // namespace order_engine
}  // namespace hg

#endif  // HG_ORDER_ENGINE_RECOVERY_HOOK_H
