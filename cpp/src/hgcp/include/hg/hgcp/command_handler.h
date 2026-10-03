#ifndef HG_HGCP_COMMAND_HANDLER_H
#define HG_HGCP_COMMAND_HANDLER_H
// =============================================================================
// 控制指令消费与参数生效（T-038，ARCHITECTURE.md §4.2.3B / §6.3）
//
// 职责：HGCP 控制指令（17 种）的消费入口 ——
//   · 解析（PbPayloadCodec，CH-013 / §4.2.8）→ **装填** LoadBatch → 经
//     hg::risk_state::ParamRebuildSession 生效（版本门卫 / 分批，见 rebuild.h）→
//     回执（MSG_ACK / MSG_ERROR，§4.3.2 冻结错误码）。
//   · 本类是 **schema 侧的唯一映射点**（pb 消息 → LoadBatch）；生效会话本身的
//     流程语义在 hg::risk_state::ParamRebuildSession（零 protobuf 依赖）。
//
// ⚠️ 范围边界（诚实声明）：
//   · **参数生效面**（CMD_LOAD_RISK_PARAMS / CMD_UPDATE_* / CMD_SET_TRADING_SUSPEND）
//     = 本类主链：解析→装填→生效→回执全通。
//   · **交易路径面**（CMD_PLACE_ORDER / CONFIRM / CANCEL / FUND_TRANSFER /
//     QUERY_FUND_RESULT）：其「生效」主体是 order_engine / flow_store（T-036/T-037
//     未开工的骨架）。本类只做**受理消费**：可解析性校验 + 就绪门卫（未就绪回
//     0x0401，§4.2.6 :1549）+ 回执；**不伪造生效**，不产出订单生命周期事件
//     （EVT_ORDER_* 归 T-036/T-039）。
//   · **状态指令面**（CMD_SET_READY_STATE / CMD_RELOAD / CMD_TRIGGER_REPLAY /
//     CMD_QUERY_STATUS）：回执型消费 + 状态回执（RELOAD/REPLAY 的实际动作依赖
//     flow_store 重放，T-037 未开工 ⇒ 受理并回执，事件由 T-039 承接）。
//
// =============================================================================
// **CMD_UPDATE_PERMISSION 五布尔逐字段映射表（T-038 验收 5；CH-019 传导）**
//
// | # | 协议字段（pbv1::PermissionEntry） | 字段号 | → 模型字段（PermissionEntry） | 语义方向 | 引擎消费点 |
// |---|----------------------------------|--------|-------------------------------|----------|------------|
// | 1 | is_enterprise_leader             | 5      | is_responsible                | 禁止类（命中即拒 0x100F，CH-010 析取） | risk_engine.cc evalPermission 分支② / risk_snapshot.cc canTrade |
// | 2 | is_management_role               | 6      | is_management_role（同名直映） | 禁止类（0x100F，CH-010 析取）          | 同上（共用 permissionLeaderForbidden） |
// | 3 | has_risk_role                    | 7      | has_risk_post                 | 禁止类（0x100E 前中后台分离）          | 同上（共用 permissionPostConflict）    |
// | 4 | has_settle_role                  | 8      | has_settle_role（同名直映）    | 禁止类（0x100E）                       | 同上                                  |
// | 5 | has_trading_post                 | 9      | has_trading_post（同名直映）   | **准入类**（不命中即拒 0x100B）        | risk_engine.cc:420 第四分支（CH-019） |
//
// ⚠️ 方向纪律：#5 是**准入类**，与 #2/#3/#4 三个**禁止类**方向相反 ⇒
//   **不得**并入 permissionPostConflict / permissionLeaderForbidden 判定组
//   （四布尔全过、仅 has_trading_post=false ⇒ 仍拒 0x100B，第四分支精确形态）。
// ⚠️ 单键覆盖语义：LoadBatch::putPermission 以 operator_id 为单键 map，
//   同 operator_id 的后条覆盖前条（快照每用户恰一 PermissionEntry）。
//   Java 侧（T-168）已按「每用户恰 1 entry」聚合下发 ⇒ C++ 直收即可；
//   本映射**保持覆盖语义**（防误改为追加），测试显式断言。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "hg/risk_state/rebuild.h"
#include "hg/risk_state/risk_state.h"

namespace hg {
namespace order_engine {
class OrderEngine;  // 前置声明（T-036 交易路径消费接缝；可空持有）
}  // namespace order_engine
namespace hgcp {

// 一条回执帧：类型 + FLAGS + 编码好的 body。
struct ReplyFrame {
  std::uint16_t type = 0;
  std::uint16_t flags = 0;
  std::vector<std::uint8_t> body;
};

// 指令消费结果：回执（ACK / ERROR）+ 可选事件帧（EVT_SYSTEM_STATE 等）。
struct CommandOutcome {
  bool consumed = false;   // 是否按控制指令消费（未知类型 = false）
  ReplyFrame reply;        // 回执帧（恒恰一条）
  std::vector<ReplyFrame> events;  // 附加事件帧（可为空）
};

// 控制指令消费器（单线程消费；其内部分别持有生效会话与 RiskState 引用）。
class CommandHandler {
 public:
  // 不取得 state 所有权；须由调用方保证存活期覆盖本对象。
  explicit CommandHandler(hg::risk_state::RiskState* state);

  // 消费一条入站控制指令帧。
  //   type     ：线上类型码（kCmd*）；非控制指令类型 ⇒ consumed=false。
  //   body     ：帧 body（protobuf）。
  //   ack_msg_id：回执 MSG_ACK.ack_msg_id（= 入站帧 msg_id）。
  //   now_ms   ：判定时点（EVT_SYSTEM_STATE.ts_ms）。
  CommandOutcome handle(std::uint16_t type, const std::vector<std::uint8_t>& body,
                        std::uint64_t ack_msg_id, std::int64_t now_ms);

  // 就绪观测（CMD_QUERY_STATUS 用；= RiskState::ready）。
  bool system_ready() const;

  // ---- 交易路径消费接缝（T-036）----------------------------------------------
  // 注入指令生命周期引擎。可空持有（调用方所有、不析构）；**缺省 null = 维持
  // T-038 受理形态**（可解析性 + 就绪门卫 + "accepted (path pending T-036)" 回执）。
  // 注入后：CMD_PLACE_ORDER / CONFIRM / CANCEL 交由引擎生效（风控前置 → 冻结 →
  // 待确认 → 确认再校验 → 落流水 → 发 CTP），并产出 EVT_ORDER_* 事件帧。
  // 其余两笔受理型（FUND_TRANSFER / QUERY_FUND_RESULT）仍为受理回执（生效主体归
  // T-033 银期轨 / T-039，不在本接缝）。
  void set_order_engine(hg::order_engine::OrderEngine* engine);

 private:
  hg::risk_state::RiskState* state_;
  hg::risk_state::ParamRebuildSession session_;
  hg::order_engine::OrderEngine* engine_ = nullptr;

  // 交易路径三指令的消费体（T-036；engine_ 注入后由 handle() 分派）。
  CommandOutcome handleTradePath(std::uint16_t type, const std::vector<std::uint8_t>& body,
                                 std::uint64_t ack_msg_id, std::int64_t now_ms);

  CommandHandler(const CommandHandler&);
  CommandHandler& operator=(const CommandHandler&);
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_COMMAND_HANDLER_H
