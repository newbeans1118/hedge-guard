// payload 编解码接缝 —— 占位实现（Opaque）。
#include "hg/hgcp/payload_codec.h"

namespace hg {
namespace hgcp {

PayloadCodec::~PayloadCodec() {}

bool OpaquePayloadCodec::validate(std::uint16_t /*type*/,
                                  const std::vector<std::uint8_t>& /*body*/) const {
  // schema 未定 ⇒ 不做字段级校验，任何 body 均透传。
  return true;
}

const char* OpaquePayloadCodec::name() const { return "opaque"; }

}  // namespace hgcp
}  // namespace hg
