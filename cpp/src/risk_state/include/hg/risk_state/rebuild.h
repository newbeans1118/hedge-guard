#ifndef HG_RISK_STATE_REBUILD_H
#define HG_RISK_STATE_REBUILD_H
// =============================================================================
// 控制指令的参数生效会话（T-038，ARCHITECTURE.md §4.2.3B / §6.3）
//
// 职责：把「Java 下发的控制指令 ⇒ 消费 ⇒ 生效（RiskState::rebuild）」的**生效侧流程**
//   收敛在本文件（risk_state，纯 LoadBatch 进出、**零 protobuf 依赖**）；
//   pb 消息 → LoadBatch 的字段级映射在 hgcp/command_handler（T-038 另一半）。
//   两层拆分的依据：risk_state 属 E4 扫描根（纯内存数据），不得引入 schema 生成码；
//   而版本门卫 / 分批累积语义须可独立于 protobuf 单测。
//
// 语义（对应 T-038 验收 2 / 3）：
//   · **版本门卫（验收 2）**：增量生效只接受 param_version > current（**严格大于**），
//     否则拒绝并丢弃（回 0x0403 由调用方承担）——「后发不会先至覆盖新值」
//     （FR-RISK-10 验收 3 / §4.2.6 :1048）。⚠️ 注意：底层 RiskState::rebuild 自身的
//     既有门卫是 >=（同版本幂等重放允许，T-034 已验收资产）——本会话在其上**收紧**
//     为 >，两语义分层、互不改动。
//   · **分批会话（验收 3）**：CMD_LOAD_RISK_PARAMS 按 batch_index/batch_total 分批到达；
//     每批先入会话累积（同 param_version），收齐后一次性生效（shadow 预演全绿后
//     对生产快照重放同序列）；会话内：首批锁定版本；后续批版本须与会话一致；
//     batch_index 须连续；完成时会话版本须 > current（否则整体丢弃、快照不变）。
//   · 单写者纪律：与 RiskState::rebuild 相同（参数重建线程独占）。
//
// 本文件为纯流程编排（无 I/O、无锁、无线程）；内存分配仅在 rebuild 路径
// （与 RiskState::rebuild 同级，不在 E4 判定热路径内）。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <vector>

#include "hg/risk_state/load_batch.h"
#include "hg/risk_state/risk_state.h"

namespace hg {
namespace risk_state {

// 生效会话结果码（对调用方 = HGCP MSG_ACK/MSG_ERROR 的映射依据）。
// 值域独立于 RebuildCode（后者是快照校验码，经 rebuild_code 透传）。
enum RebuildSessionCode {
  kSessionOk = 0,                 // 本批已受理（分批中间批）或已生效（完成批）
  kSessionVersionStale = 1,       // 版本门卫拒绝（0x0403）：版本 <= current
  kSessionBatchInconsistent = 2,  // 分批不一致：批内版本漂移 / batch_index 越界
  kSessionBatchMissing = 3,       // 分批缺批：batch_index 不连续
  kSessionRebuildFailed = 4       // 快照校验失败（rebuild_code = RebuildCode 透传）
};

// 会话动作结果（result.ok 且 result.applied 为真 ⇒ 调用方须发 EVT_SYSTEM_STATE）。
struct SessionResult {
  bool ok = false;               // 受理 / 生效成功
  int code = 0;                  // RebuildSessionCode（失败时）
  int rebuild_code = 0;          // RebuildCode（kSessionRebuildFailed 时有效）
  bool applied = false;          // 本调用触发了快照发布
  std::uint64_t version = 0;     // 相关参数版本（受理批 = 会话版本；生效 = 发布版本）
  bool replace_all = false;      // 本批是否触发全量语义（分批会话 = true）
};

// 控制指令参数生效会话（单写者；一个 Java 侧参数通道对应一个会话实例）。
//
// 用法：
//   · CMD_LOAD_RISK_PARAMS 每批 => submitLoadBatch(batch, batch_index, batch_total)；
//   · CMD_UPDATE_* 增量 => submitIncremental(batch)（batch 须 replace_all()==false）。
class ParamRebuildSession {
 public:
  explicit ParamRebuildSession(RiskState* state);

  // 分批全量加载（验收 3）：batch_index ∈ [0, batch_total)、batch_total >= 1。
  // 每批独立受理；收齐最后一批时一次性生效并置 applied=true。
  // batch.replace_all() 由本方法强制置位（分批全量语义），调用方无须预设。
  SessionResult submitLoadBatch(LoadBatch batch, std::uint32_t batch_index,
                                std::uint32_t batch_total);

  // 增量生效（CMD_UPDATE_*）：param_version 须 > current，否则拒绝（验收 2）。
  // batch.replace_all() 由本方法强制清位（增量语义）。
  SessionResult submitIncremental(LoadBatch batch);

  // 会话观测（CMD_QUERY_STATUS 的 detail 来源之一）。
  std::uint64_t session_version() const { return load_version_; }
  std::uint32_t batches_received() const { return received_; }
  std::uint32_t batches_total() const { return total_; }
  bool load_in_progress() const { return in_progress_; }

 private:
  RiskState* state_;
  bool in_progress_;
  std::uint64_t load_version_;
  std::uint32_t total_;
  std::uint32_t received_;
  std::uint32_t next_index_;               // 期望的下一批 index（连续性守卫）
  std::vector<LoadBatch> session_batches_;  // 会话暂存（首批评 replace_all=true）

  ParamRebuildSession(const ParamRebuildSession&);
  ParamRebuildSession& operator=(const ParamRebuildSession&);
};

// 会话码 → 可读名称（诊断）。
const char* rebuildSessionCodeName(int code);

}  // namespace risk_state
}  // namespace hg

#endif  // HG_RISK_STATE_REBUILD_H
