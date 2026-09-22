#ifndef HG_HGCP_PAYLOAD_CODEC_H
#define HG_HGCP_PAYLOAD_CODEC_H
// =============================================================================
// payload 编解码**接缝**（`T-032`）
//
// 分层约定：
//   帧层（frame.h）      ：只认「类型码 + 不透明 body 字节」，不解释字段；
//   payload 层（本文件）：**唯一**把 body ↔ 消息对象互转的地方。
//
// schema 现状：**已定义** —— `ARCHITECTURE.md` §4.2.8「字段级 schema（线格式表示的定义）」
//   （`CH-013` 定版，2026-09-22，用户批准）；唯一定义 = `proto/hgcp.proto`
//   （由 §4.2.8 逐条转录，C++(`T-032`) / Java(`T-042`) 同源生成）。
//
// 本文件提供 **`PbPayloadCodec`**（基于 `proto/hgcp.proto` 生成代码，
//   替换先前的 `OpaquePayloadCodec` 占位实现），并保留 `PayloadCodec` 接缝。
//   ⇒ 帧层 / 承载层（`tcp`/`session`）**零改动**；装配处（`TransportPolicy` 实现）换实现即可。
//
// ⚠️ 本头文件 `#include "hgcp.pb.h"`（生成代码）——仅 payload 层与注入策略依赖 schema；
//    `session`/`server_loop` 的**状态机**仍只处理「类型码 + body」，不解释字段。
// =============================================================================

#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

#include "hgcp.pb.h"  // protoc 生成（源 = proto/hgcp.proto，CH-013 / §4.2.8）

namespace hg {
namespace hgcp {

// payload 编解码接口。实现须**无副作用**且线程内可重入。
class PayloadCodec {
 public:
  virtual ~PayloadCodec();

  // 校验 body 是否为该 type 的合法 payload（长度 / 可解析 / 取值域）。
  // 返回 false ⇒ 帧层应回 `0x0104 ERR_PAYLOAD_INVALID`。
  // 注：`type` 不在 §4.2.3 清单内属 `0x0105 ERR_UNKNOWN_TYPE`（由 `is_known_message_type` 判），
  //     本方法对未知 type 返回 false。
  virtual bool validate(std::uint16_t type,
                        const std::vector<std::uint8_t>& body) const = 0;

  // 实现名（供日志/自检；不涉敏感信息）。
  virtual const char* name() const = 0;
};

// 基于 `proto/hgcp.proto`（`CH-013` / §4.2.8）生成代码的编解码实现。
// 覆盖 §4.2.8 定义的全部 40 条消息（含元协议 9 条）。
class PbPayloadCodec : public PayloadCodec {
 public:
  virtual bool validate(std::uint16_t type,
                        const std::vector<std::uint8_t>& body) const;
  virtual const char* name() const;

  // 消息对象 → body（protobuf 序列化）。`out` 不得为空指针；失败返回 false。
  template <typename M>
  static bool encode(const M& msg, std::vector<std::uint8_t>* out) {
    if (out == 0) {
      return false;
    }
    const std::size_t n = static_cast<std::size_t>(msg.ByteSizeLong());
    if (n > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
      return false;
    }
    out->assign(n, 0u);
    if (n == 0u) {
      return true;  // 空消息（全默认值）⇒ 零长度 body
    }
    return msg.SerializeToArray(&(*out)[0], static_cast<int>(n));
  }

  // body → 消息对象（protobuf 反序列化）。`out` 不得为空指针；解析失败返回 false。
  // 空 body 合法（proto3 全默认值）⇒ *out 清空后返回 true。
  template <typename M>
  static bool decode(const std::vector<std::uint8_t>& body, M* out) {
    if (out == 0) {
      return false;
    }
    out->Clear();
    if (body.empty()) {
      return true;
    }
    if (body.size() > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
      return false;
    }
    return out->ParseFromArray(&body[0], static_cast<int>(body.size()));
  }
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_PAYLOAD_CODEC_H
