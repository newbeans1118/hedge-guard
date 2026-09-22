// 单测：CTP API 生命周期状态机（**SDK 无关**，两轨均注册）。
//
// 承重项（验收标准 2「不得引入 Release()-before-Init() 的段错误路径」的判据面）：
//   ① 合法序 `Create → Init → Release` 的谓词序列：只有 `markInitialized()` 之后才
//      `canRelease()==true`；
//   ② **反例**：`Create` 后未 `Init` ⇒ `canRelease()==false`
//      （守卫拒绝 Release ⇒ 不会触发官方库 Release-before-Init 的段错误）；
//   ③ 非法迁移被忽略（不得「跳过 Init 直接到已初始化/已释放」而蒙混过关）。
#include "ctp_adapter_test_util.h"
#include "hg/ctp_adapter/ct_lifecycle.h"

using hg::ctp_adapter::CtApiLifecycle;
using hg::ctp_adapter::CtApiState;

namespace {

void fresh_lifecycle_cannot_release() {
  CtApiLifecycle lc;
  HG_CHECK(lc.state() == CtApiState::kNone);
  HG_CHECK(!lc.canRelease());
  HG_CHECK(!lc.createdButNotInitialized());
}

// 反例（★ 守卫的核心）：Create 之后、Init 之前，Release 必须被拒。
void created_but_not_initialized_cannot_release() {
  CtApiLifecycle lc;
  lc.markCreated();
  HG_CHECK(lc.state() == CtApiState::kCreated);
  HG_CHECK(!lc.canRelease());  // ★ 若此处为 true ⇒ Release-before-Init ⇒ 官方库段错误
  HG_CHECK(lc.createdButNotInitialized());
}

void initialized_can_release_then_released_cannot() {
  CtApiLifecycle lc;
  lc.markCreated();
  lc.markInitialized();
  HG_CHECK(lc.state() == CtApiState::kInitialized);
  HG_CHECK(lc.canRelease());
  HG_CHECK(!lc.createdButNotInitialized());

  lc.markReleased();
  HG_CHECK(lc.state() == CtApiState::kReleased);
  HG_CHECK(!lc.canRelease());  // 已释放 ⇒ 不得再次 Release
}

// 非法迁移不得把状态「跳过中间态」推进。
void illegal_transitions_are_ignored() {
  {
    CtApiLifecycle lc;             // kNone
    lc.markInitialized();          // 无 Create ⇒ 忽略
    HG_CHECK(lc.state() == CtApiState::kNone);
    lc.markReleased();             // 无 Init ⇒ 忽略
    HG_CHECK(lc.state() == CtApiState::kNone);
    HG_CHECK(!lc.canRelease());
  }
  {
    CtApiLifecycle lc;             // kNone
    lc.markCreated();              // kCreated
    lc.markReleased();             // 未 Init ⇒ 忽略（仍 kCreated，仍不可 Release）
    HG_CHECK(lc.state() == CtApiState::kCreated);
    HG_CHECK(!lc.canRelease());
  }
}

// 幂等：重复标记不改变状态。
void repeated_marking_is_idempotent() {
  CtApiLifecycle lc;
  lc.markCreated();
  lc.markCreated();
  HG_CHECK(lc.state() == CtApiState::kCreated);
  lc.markInitialized();
  lc.markInitialized();
  HG_CHECK(lc.state() == CtApiState::kInitialized);
  lc.markReleased();
  lc.markReleased();
  HG_CHECK(lc.state() == CtApiState::kReleased);
}

// 两套独立实例互不影响（交易 / 行情各持一个）。
void independent_instances() {
  CtApiLifecycle trade;
  CtApiLifecycle md;
  trade.markCreated();
  trade.markInitialized();
  md.markCreated();
  HG_CHECK(trade.canRelease());
  HG_CHECK(!md.canRelease());
}

}  // namespace

int main() {
  fresh_lifecycle_cannot_release();
  created_but_not_initialized_cannot_release();
  initialized_can_release_then_released_cannot();
  illegal_transitions_are_ignored();
  repeated_marking_is_idempotent();
  independent_instances();
  return hg::ctp_adapter::testutil::report("ct_lifecycle_test");
}
