#ifndef HG_CTP_ADAPTER_CT_LIFECYCLE_H
#define HG_CTP_ADAPTER_CT_LIFECYCLE_H
// =============================================================================
// CTP 适配层 —— CTP API 生命周期状态机（**SDK 无关**，两轨均可单测）
//
// 官方 CTP SDK 纪律（实测 2026-09-22、官方 v6.7.13）：
//   · 合法调用序：`Create*Api()` → `RegisterSpi` / `RegisterFront` /
//     `Subscribe*Topic` → `Init()` → `Release()`；
//   · **`Release()` 必须在 `Init()` 之后**：在 `Init()` 之前调用 `Release()`
//     会**段错误**（进程 rc=139，实测）。
//   · `SubscribePrivateTopic` / `SubscribePublicTopic` 的接口备注要求
//     「在 `Init` 方法前调用」。
//
// 故把「可否 Release」编码为**唯一谓词** `canRelease()`，任何调用点都不得绕过：
//   本类不依赖任何 SDK 类型 ⇒ 可在**两条轨**（stub / ctp）上直接单测，
//   其正确性不取决于是否拿到真实 SDK。
// =============================================================================

namespace hg {
namespace ctp_adapter {

// 生命周期阶段（单向推进）。
enum class CtApiState {
  kNone,         // 尚未创建
  kCreated,      // Create*Api() 已返回实例，但尚未 Init()  ← 此时**不得** Release
  kInitialized,  // Init() 已调用                              ← 唯一允许 Release 的阶段
  kReleased      // Release() 已返回
};

class CtApiLifecycle {
 public:
  CtApiLifecycle() : state_(CtApiState::kNone) {}

  // Create*Api() 返回非空实例后调用。
  void markCreated() {
    if (state_ == CtApiState::kNone) state_ = CtApiState::kCreated;
  }
  // Init() 调用返回后调用。
  void markInitialized() {
    if (state_ == CtApiState::kCreated) state_ = CtApiState::kInitialized;
  }
  // Release() 返回后调用。
  void markReleased() {
    if (state_ == CtApiState::kInitialized) state_ = CtApiState::kReleased;
  }

  // ★ 守卫：**仅** kInitialized 才允许 Release（防 Release-before-Init 段错误）。
  bool canRelease() const { return state_ == CtApiState::kInitialized; }

  // 已创建但尚未 Init —— 此时 Release 会段错误，调用方应**放弃释放**（宁可泄漏，
  // 不得崩溃）；本条是「不得引入 Release-before-Init 段错误路径」的判据面。
  bool createdButNotInitialized() const { return state_ == CtApiState::kCreated; }

  CtApiState state() const { return state_; }

 private:
  CtApiState state_;
};

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_ADAPTER_CT_LIFECYCLE_H
