// 单测：ctp_adapter 内部队列语义（ARCHITECTURE.md §2.4.3）。
//   ① RingBuffer（orderEventQueue 载体，SPSC 无锁）：FIFO / 定容 / 满不覆盖 / 有界自旋。
//   ② QuoteQueue（quoteQueue 载体）：满则丢弃最旧（保最新）、丢弃计数可观测。
#include "ctp_adapter_test_util.h"
#include "hg/ctp_adapter/ct_queues.h"

using hg::ctp_adapter::CtOrderEvent;
using hg::ctp_adapter::CtQuoteEvent;
using hg::ctp_adapter::QuoteQueue;
using hg::ctp_adapter::RingBuffer;

namespace {

void ring_buffer_fifo_and_bound() {
  RingBuffer<int, 4> ring;  // 可用槽位 = 3
  HG_CHECK_EQ(ring.capacity(), std::size_t(3));
  HG_CHECK(ring.empty());
  HG_CHECK(ring.tryPush(11));
  HG_CHECK(ring.tryPush(22));
  HG_CHECK(ring.tryPush(33));
  HG_CHECK_EQ(ring.size(), std::size_t(3));
  // 满：不得覆盖既有元素
  HG_CHECK(!ring.tryPush(44));
  HG_CHECK_EQ(ring.size(), std::size_t(3));

  int v = 0;
  HG_CHECK(ring.tryPop(&v));
  HG_CHECK_EQ(v, 11);
  HG_CHECK(ring.tryPop(&v));
  HG_CHECK_EQ(v, 22);
  // 出队一格后可再入队
  HG_CHECK(ring.tryPush(44));
  HG_CHECK(ring.tryPop(&v));
  HG_CHECK_EQ(v, 33);
  HG_CHECK(ring.tryPop(&v));
  HG_CHECK_EQ(v, 44);
  HG_CHECK(!ring.tryPop(&v));  // 空
}

void ring_buffer_spinning_is_bounded() {
  RingBuffer<int, 3> ring;  // 可用槽位 = 2
  HG_CHECK(ring.tryPush(1));
  HG_CHECK(ring.tryPush(2));
  // 满时静默自旋不得成功（有界自旋上界 = 0）
  HG_CHECK(!ring.pushSpinning(3, 0));
  int v = 0;
  HG_CHECK(ring.tryPop(&v));
  HG_CHECK_EQ(v, 1);
  // 有空间后自旋成功
  HG_CHECK(ring.pushSpinning(3, 8));
  HG_CHECK_EQ(ring.size(), std::size_t(2));
}

void quote_queue_drops_oldest_keeps_newest() {
  QuoteQueue queue(3);
  HG_CHECK_EQ(queue.capacity(), std::size_t(3));
  for (int i = 1; i <= 3; ++i) {
    CtQuoteEvent q;
    q.instrument_id = "IF" + std::to_string(i);  // 1..3
    HG_CHECK(!queue.pushDropOldest(q));          // 未满：无丢弃
  }
  HG_CHECK_EQ(queue.size(), std::size_t(3));
  HG_CHECK_EQ(queue.droppedCount(), std::size_t(0));

  CtQuoteEvent fourth;
  fourth.instrument_id = "IF4";
  HG_CHECK(queue.pushDropOldest(fourth));  // 满：丢弃最旧（IF1）
  HG_CHECK_EQ(queue.size(), std::size_t(3));
  HG_CHECK_EQ(queue.droppedCount(), std::size_t(1));

  CtQuoteEvent out;
  HG_CHECK(queue.tryPop(&out));
  HG_CHECK_EQ(out.instrument_id, std::string("IF2"));  // IF1 已被丢弃
  HG_CHECK(queue.tryPop(&out));
  HG_CHECK_EQ(out.instrument_id, std::string("IF3"));
  HG_CHECK(queue.tryPop(&out));
  HG_CHECK_EQ(out.instrument_id, std::string("IF4"));  // 最新保序保留
  HG_CHECK(queue.tryPop(&out) == false);
}

void ring_buffer_carries_normalized_events() {
  RingBuffer<CtOrderEvent, 4> ring;  // 可用槽位 = 3
  CtOrderEvent a;
  a.kind = hg::ctp_adapter::CtOrderEventKind::kTradeReturn;
  a.instrument_id = "rb2601";
  a.trade_volume = 7;
  HG_CHECK(ring.tryPush(a));
  CtOrderEvent out;
  HG_CHECK(ring.tryPop(&out));
  HG_CHECK(out.kind == hg::ctp_adapter::CtOrderEventKind::kTradeReturn);
  HG_CHECK_EQ(out.instrument_id, std::string("rb2601"));
  HG_CHECK_EQ(out.trade_volume, 7);
}

}  // namespace

int main() {
  ring_buffer_fifo_and_bound();
  ring_buffer_spinning_is_bounded();
  quote_queue_drops_oldest_keeps_newest();
  ring_buffer_carries_normalized_events();
  return hg::ctp_adapter::testutil::report("ct_queues_test");
}
