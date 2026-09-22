#ifndef HG_CTP_ADAPTER_CT_QUEUES_H
#define HG_CTP_ADAPTER_CT_QUEUES_H
// =============================================================================
// CTP 适配层（ctp_adapter）—— 内部队列（M1）
//
// 承载：docs/ARCHITECTURE.md §2.4.2（T2/T3 回调线程 → 队列）、§2.4.3（队列容量与
//       满时策略）、§3.1（SPI 回调「序列化并投递到 orderEventQueue / quoteQueue」）。
//
//   · orderEventQueue：T2 → T4，容量 8192，满时「告警 + 自旋等待」（**不得丢回报**）
//     ⇒ `RingBuffer::pushSpinning`（SPSC 无锁环形缓冲）。
//   · quoteQueue     ：T3 → T8，容量 4096，满时「丢弃最旧」（行情可丢）
//     ⇒ `QuoteQueue::pushDropOldest`。
//
// ⚠️ `ctp_adapter` **不在** `E4` 扫描根内（`E4` = order_engine / risk_engine /
//    risk_state 三目录并集，见 §3.0.2 `E4` 脚注）⇒ 本模块**允许**使用互斥体。
//    但本文件仍遵守「勿以 read/write/send/recv/sleep 命名」的通用纪律：
//    入队/出队一律用 push / pop 语义名。
// =============================================================================

#include <atomic>
#include <cstddef>
#include <deque>
#include <mutex>
#include <thread>

#include "hg/ctp_adapter/ct_types.h"

namespace hg {
namespace ctp_adapter {

// -----------------------------------------------------------------------------
// RingBuffer —— 单生产者 / 单消费者定容环形缓冲（无锁）。
// 真实可用槽位 = Capacity - 1（空出一格以区分「空」与「满」）。
// -----------------------------------------------------------------------------
template <typename T, std::size_t Capacity>
class RingBuffer {
 public:
  RingBuffer() : head_(0), tail_(0) {
    static_assert(Capacity >= 2u, "RingBuffer 容量至少为 2");
  }

  // 非阻塞入队；满则返回 false（不覆盖、不丢失既有元素）。
  bool tryPush(const T& value) {
    const std::size_t head = head_.load(std::memory_order_relaxed);
    const std::size_t next = (head + 1u) % Capacity;
    if (next == tail_.load(std::memory_order_acquire)) return false;
    slot_[head] = value;
    head_.store(next, std::memory_order_release);
    return true;
  }

  // 有界自旋入队（满时让出 CPU 重试至多 max_spins 次）。
  // 用于 orderEventQueue：§2.4.3「满时告警 + 自旋等待（不得丢回报）」。
  // 有界是为了避免 CTP 回调线程被无限阻塞；超过上界返回 false，由调用方告警。
  bool pushSpinning(const T& value, std::size_t max_spins) {
    if (tryPush(value)) return true;
    for (std::size_t i = 0; i < max_spins; ++i) {
      std::this_thread::yield();
      if (tryPush(value)) return true;
    }
    return false;
  }

  bool tryPop(T* out) {
    const std::size_t tail = tail_.load(std::memory_order_relaxed);
    if (tail == head_.load(std::memory_order_acquire)) return false;  // 空
    *out = slot_[tail];
    tail_.store((tail + 1u) % Capacity, std::memory_order_release);
    return true;
  }

  bool empty() const { return size() == 0u; }

  std::size_t size() const {
    const std::size_t head = head_.load(std::memory_order_acquire);
    const std::size_t tail = tail_.load(std::memory_order_acquire);
    return (head + Capacity - tail) % Capacity;
  }

  static std::size_t capacity() { return Capacity - 1u; }

 private:
  RingBuffer(const RingBuffer&);
  RingBuffer& operator=(const RingBuffer&);

  T slot_[Capacity];
  std::atomic<std::size_t> head_;
  std::atomic<std::size_t> tail_;
};

// -----------------------------------------------------------------------------
// QuoteQueue —— 单生产者 / 单消费者定容行情队列，**满则丢弃最旧**（§2.4.3）。
// 「丢弃最旧」要求生产者前移消费位，与「无锁 SPSC」在形式上不相容；因该路径不属
// T4 交易路径（生产者 T3 / 消费者 T8），此处以互斥体保证正确性（已在上方头注披露）。
// -----------------------------------------------------------------------------
class QuoteQueue {
 public:
  explicit QuoteQueue(std::size_t capacity) : capacity_(capacity), dropped_(0) {}
  ~QuoteQueue() {}

  // 入队；队列已满时先丢弃最旧一条再入队。返回是否发生了丢弃（供告警计数）。
  bool pushDropOldest(const CtQuoteEvent& value) {
    std::lock_guard<std::mutex> guard(mutex_);
    bool dropped = false;
    if (queue_.size() >= capacity_) {
      queue_.pop_front();
      ++dropped_;
      dropped = true;
    }
    queue_.push_back(value);
    return dropped;
  }

  bool tryPop(CtQuoteEvent* out) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (queue_.empty()) return false;
    *out = queue_.front();
    queue_.pop_front();
    return true;
  }

  std::size_t size() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return queue_.size();
  }

  std::size_t capacity() const { return capacity_; }

  std::size_t droppedCount() const {
    std::lock_guard<std::mutex> guard(mutex_);
    return dropped_;
  }

 private:
  QuoteQueue(const QuoteQueue&);
  QuoteQueue& operator=(const QuoteQueue&);

  mutable std::mutex mutex_;
  std::deque<CtQuoteEvent> queue_;
  std::size_t capacity_;
  std::size_t dropped_;
};

}  // namespace ctp_adapter
}  // namespace hg

#endif  // HG_CTP_ADAPTER_CT_QUEUES_H
