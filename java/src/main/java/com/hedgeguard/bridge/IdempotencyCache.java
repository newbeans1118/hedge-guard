package com.hedgeguard.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件流幂等去重缓存（{@code ARCHITECTURE.md} §3.10「幂等」）。
 *
 * <p><b>键 = {@code business_id + '|' + event_seq}</b>。
 * {@code business_id} 取 {@code trade_id} / {@code order_sys_id} / {@code receipt_id}
 * （按事件类型各自的业务主键，见 §3.10）。
 *
 * <p><b>双保险</b>：本缓存是<b>第一道</b>（内存 LRU，防重复消费）；
 * <b>第二道</b>为落库唯一约束 —— 由 L3 服务层在写库时建，<b>不在本类范围</b>
 * （桥接层不写库，§3.10 纪律）。
 *
 * <p>容量满时按插入顺序淘汰最旧项（LRU 语义的近似：访问不提升顺序，
 * 与「补数窗口有限」的实际使用形态一致；如需严格 LRU 可改
 * {@code accessOrder=true}，但会引入写锁）。
 */
public final class IdempotencyCache {

    private final int capacity;
    private final Map<String, Boolean> seen;

    /**
     * 构造。
     *
     * @param capacity 容量（建议 ≥ 补数缓冲容量 {@value HgcpConstants#RESUME_BUFFER_CAPACITY}）
     */
    public IdempotencyCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 须为正：" + capacity);
        }
        this.capacity = capacity;
        this.seen = new LinkedHashMap<>(Math.min(capacity, 1024), 0.75f, true);
    }

    /**
     * 默认容量构造（{@value HgcpConstants#RESUME_BUFFER_CAPACITY}）。
     */
    public IdempotencyCache() {
        this(HgcpConstants.RESUME_BUFFER_CAPACITY);
    }

    /**
     * 构造幂等键。
     *
     * @param businessId 业务主键
     * @param eventSeq   事件序号
     * @return 键
     */
    public static String key(String businessId, long eventSeq) {
        return businessId + "|" + eventSeq;
    }

    /**
     * 判定并登记：首次见到返回 {@code true}（应消费），重复返回 {@code false}（应丢弃）。
     *
     * @param businessId 业务主键
     * @param eventSeq   事件序号
     * @return 是否首次
     */
    public boolean first(String businessId, long eventSeq) {
        return first(key(businessId, eventSeq));
    }

    /**
     * 判定并登记（直接给键）。
     *
     * @param key 幂等键
     * @return 是否首次
     */
    public boolean first(String key) {
        synchronized (seen) {
            if (seen.containsKey(key)) {
                return false;
            }
            if (seen.size() >= capacity) {
                // 淘汰最旧（LinkedHashMap accessOrder=true ⇒ 最久未访问）
                java.util.Iterator<String> it = seen.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            seen.put(key, Boolean.TRUE);
            return true;
        }
    }

    /** 当前条目数（诊断用）。 */
    public int size() {
        synchronized (seen) {
            return seen.size();
        }
    }

    /** 容量。 */
    public int capacity() {
        return capacity;
    }

    /** 清空（重连后重建时调用）。 */
    public void clear() {
        synchronized (seen) {
            seen.clear();
        }
    }
}
