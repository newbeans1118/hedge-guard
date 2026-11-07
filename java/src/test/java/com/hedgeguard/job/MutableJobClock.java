package com.hedgeguard.job;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控推进的业务时钟（{@code NFR-TEST-4}）：单测用 {@link #set} / {@link #advanceDays} 推进时间，
 * 全程不读真实墙钟，故用例可重复、可精确到分钟片。
 */
final class MutableJobClock {

    private final AtomicReference<LocalDateTime> now = new AtomicReference<>();

    MutableJobClock(LocalDateTime initial) {
        now.set(initial);
    }

    /** 作为 {@link JobClock} 注入调度器；每次取当前逻辑时刻。 */
    JobClock asJobClock() {
        return now::get;
    }

    void set(LocalDateTime value) {
        now.set(value);
    }

    void advanceDays(long days) {
        now.updateAndGet(t -> t.plusDays(days));
    }

    void advanceMinutes(long minutes) {
        now.updateAndGet(t -> t.plusMinutes(minutes));
    }

    LocalDateTime now() {
        return now.get();
    }
}
