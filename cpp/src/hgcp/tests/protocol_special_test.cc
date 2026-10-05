// T-057 协议专项补充单测（M2 / NFR-TEST-5）。
//
// 覆盖设计用例 docs/TEST_PLAN/M2.md 中在 C++ 侧无可复用落点的两条：
//   · TC-M2-06 未知消息类型（0x0105）：谓词 + payload 校验 + 消费侧忽略（丢弃、不消费）
//   · TC-M2-33 预加载 10 万条额度分片（50 批 × 2000）后全部生效
//
// ⚠️ TC-M2-06 的「不中断连接」为 server_loop 行为（未知类型帧入 inbound 后不再分派、
//    不 close）；本文件只能断言到**消费侧**（CommandHandler 不消费）+ **校验侧**
//    （is_known_message_type / PbPayloadCodec::validate），连接存活属代码级证据。
#include "hg/hgcp/command_handler.h"

#include <cstdint>
#include <string>
#include <vector>

#include "hg/hgcp/payload_codec.h"
#include "hg/hgcp/protocol.h"
#include "hgcp.pb.h"
#include "hgcp_test_util.h"

namespace {

namespace pbv1 = ::hgcp::v1;
using hg::hgcp::CommandHandler;
using hg::hgcp::CommandOutcome;
using hg::hgcp::MessageType;
using hg::hgcp::PbPayloadCodec;
using hg::risk_state::RiskState;

std::uint16_t mt(MessageType m) { return static_cast<std::uint16_t>(m); }

template <typename M>
std::vector<std::uint8_t> enc(const M& m) {
  std::vector<std::uint8_t> b;
  if (!PbPayloadCodec::encode(m, &b)) {
    std::printf("[FATAL] encode failed\n");
    std::exit(2);
  }
  return b;
}

bool is_ok_ack(const CommandOutcome& o) {
  if (o.reply.type != mt(MessageType::kMsgAck)) return false;
  pbv1::MsgAck a;
  if (!PbPayloadCodec::decode(o.reply.body, &a)) return false;
  return a.result() == 0;
}

std::uint32_t err_code_of(const CommandOutcome& o) {
  pbv1::MsgError e;
  if (!PbPayloadCodec::decode(o.reply.body, &e)) return 0xFFFFFFFFu;
  return e.err_code();
}

// ── TC-M2-07：已知类型 + 非法 protobuf 字节 ⇒ 0x0104（丢弃该帧，不消费业务语义）──
void test_invalid_payload_bytes_rejected() {
  std::vector<std::uint8_t> garbage(5, 0xFFu);  // 截断 varint ⇒ 不可解析
  PbPayloadCodec codec;
  HG_CHECK(!codec.validate(mt(MessageType::kCmdUpdatePermission), garbage));
  RiskState st;
  CommandHandler h(&st);
  const CommandOutcome o =
      h.handle(mt(MessageType::kCmdUpdatePermission), garbage, 1, 0);
  HG_CHECK_EQ(err_code_of(o), static_cast<std::uint32_t>(0x0104));
  HG_CHECK(st.snapshot().findPermission(1u) == 0);  // 未生效（丢弃）
}

// ── TC-M2-06：未知消息类型（0x9999）⇒ 谓词判非已知 + payload 校验拒 + 消费侧不消费 ──
void test_unknown_message_type() {
  // ① 错误码登记：0x0105 ERR_UNKNOWN_TYPE
  HG_CHECK_EQ(static_cast<std::uint32_t>(hg::hgcp::ErrorCode::kErrUnknownType), 0x0105u);
  // ② 谓词：清单外类型（含 0x9999 / 区间间隙 0x0104）均非已知
  HG_CHECK(!hg::hgcp::is_known_message_type(0x9999u));
  HG_CHECK(!hg::hgcp::is_known_message_type(0x0104u));
  // 清单内类型仍判已知（正控，防「一刀切 false」）
  HG_CHECK(hg::hgcp::is_known_message_type(mt(MessageType::kCmdPlaceOrder)));

  // ③ payload 校验：未知类型一律 false（帧层据此回 0x0105/0x0104）
  PbPayloadCodec codec;
  std::vector<std::uint8_t> body(3, 0x00);
  HG_CHECK(!codec.validate(0x9999u, body));

  // ④ 消费侧：未知类型不被消费（丢弃、不产生回执）——「不消费」判据
  RiskState st;
  CommandHandler h(&st);
  const CommandOutcome o = h.handle(0x9999u, body, 1, 1000);
  HG_CHECK(!o.consumed);
  HG_CHECK_EQ(o.reply.type, 0u);  // 无回执
}

// ── TC-M2-33：预加载 10 万条额度（50 批 × 2000）分片后全部生效 ──
//   前置：HG.LOAD.BATCH_SIZE = 2000 条/批（设计 §5.9）。
//   判据：50 批 ACK 后 RiskState.ready()=true，快照品种数 = 100000，首/末条目可查。
void test_preload_100k_fragments_all_effective() {
  const std::uint32_t kTotal = 100000u;
  const std::uint32_t kPerBatch = 2000u;
  const std::uint32_t kBatchTotal = kTotal / kPerBatch;  // 50

  RiskState st;
  CommandHandler h(&st);
  for (std::uint32_t bi = 0; bi < kBatchTotal; ++bi) {
    pbv1::CmdLoadRiskParams b;
    b.set_param_version(1u);
    b.set_batch_index(bi);
    b.set_batch_total(kBatchTotal);
    for (std::uint32_t k = 0; k < kPerBatch; ++k) {
      const std::uint32_t pid = bi * kPerBatch + k + 1u;  // 1..100000
      pbv1::VarietySpec* v = b.add_varieties();
      v->set_product_id(pid);
      v->set_product_code("P");
      v->set_category(pbv1::PRODUCT_CATEGORY_COMMODITY);
      v->set_status(pbv1::PRODUCT_STATUS_ENABLED);
      v->set_unit_factor("1.0000");
      pbv1::QuotaEntry* q = b.add_quotas();
      q->set_limit_layer(pbv1::LIMIT_LAYER_SUB_PRODUCT);
      q->set_sub_account_id(1u);
      q->set_product_id(pid);
      q->set_quota_limit("100.0000");
      q->set_used("0.0000");
      q->set_frozen("0.0000");
    }
    const CommandOutcome o =
        h.handle(mt(MessageType::kCmdLoadRiskParams), enc(b), bi + 1u, 1000);
    HG_CHECK(is_ok_ack(o));
    if (bi + 1u < kBatchTotal) {
      HG_CHECK(!st.ready());  // 未收齐不得就绪
    }
  }
  HG_CHECK(st.ready());
  HG_CHECK_EQ(st.snapshot().varieties().size(), static_cast<std::size_t>(kTotal));
  HG_CHECK(st.snapshot().findVariety(1u) != 0);
  HG_CHECK(st.snapshot().findVariety(kTotal) != 0);
  // 末条额度亦生效（层① 子账户-品种）
  HG_CHECK(st.snapshot().findQuota(hg::risk_state::QuotaLayer::kSubProduct, 1u, kTotal, 0) != 0);
}

}  // namespace

int main() {
  test_unknown_message_type();
  test_invalid_payload_bytes_rejected();
  test_preload_100k_fragments_all_effective();
  return hg::hgcp::testutil::report("protocol_special_test");
}
