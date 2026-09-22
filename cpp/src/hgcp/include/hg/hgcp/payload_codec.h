#ifndef HG_HGCP_PAYLOAD_CODEC_H
#define HG_HGCP_PAYLOAD_CODEC_H
// =============================================================================
// payload 编解码**接缝**（T-032 缺口：字段级 schema 待架构席裁定）
//
// 分层约定（「schema 到位即可平移」）：
//   帧层（frame.h）      ：只认「类型码 + 不透明 body 字节」，不解释字段；
//   payload 层（本文件）：**唯一**把 body ↔ 消息对象互转的地方。
//
// 现状：schema（字段名/类型/字段号/枚举值）在 `ARCHITECTURE.md §4.2` 中
//   **未定义**（§4.2.3 只给字段名、§4.2.4 只覆盖 3 条消息且无字段号）⇒ 仅提供
//   **Opaque** 实现（body 原样透传，不做字段解析）。
//
// 平移方式（schema 到位后）：新增 `PbPayloadCodec : PayloadCodec`（基于
//   `proto/hgcp.proto` 生成代码）并在装配处替换实现 —— 帧层与服务端**零改动**。
//   详见 `cpp/src/hgcp/T032_GAP_ANALYSIS.md`（**非权威建议稿**）。
// =============================================================================

#include <cstdint>
#include <vector>

namespace hg {
namespace hgcp {

// payload 编解码接口。实现须**无副作用**且线程内可重入。
class PayloadCodec {
 public:
  virtual ~PayloadCodec();

  // 校验 body 是否为该 type 的合法 payload（长度 / 必填 / 取值域）。
  // 返回 false ⇒ 帧层应回 `0x0104 ERR_PAYLOAD_INVALID`。
  virtual bool validate(std::uint16_t type,
                        const std::vector<std::uint8_t>& body) const = 0;

  // 实现名（供日志/自检；不涉敏感信息）。
  virtual const char* name() const = 0;
};

// 占位实现：body 原样透传，不做任何字段解析（schema 未定前使用）。
class OpaquePayloadCodec : public PayloadCodec {
 public:
  virtual bool validate(std::uint16_t /*type*/,
                        const std::vector<std::uint8_t>& /*body*/) const;
  virtual const char* name() const;
};

}  // namespace hgcp
}  // namespace hg

#endif  // HG_HGCP_PAYLOAD_CODEC_H
