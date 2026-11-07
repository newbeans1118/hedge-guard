package com.hedgeguard.job;

import com.hedgeguard.common.Clocks;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 调度层时钟（{@code NFR-TEST-4}「关键外部依赖可注入」）。
 *
 * <p><b>为什么需要这一层</b>：调度任务的「是否到点 / 业务日期 / 幂等时间片」全部取决于当前时刻。
 * 若业务代码直取墙钟，单测既无法推进时间、也无法重复。故调度框架只认本接口，由调用方注入：
 * <ul>
 *   <li><b>生产</b>：{@link #system()} → 委托 {@link Clocks}（与 {@code package-info} 的
 *       「一律经 com.hedgeguard.common.Clocks」纪律一致，且仍可被 {@code Clocks.set(...)} 替换）；</li>
 *   <li><b>测试</b>：注入可变时钟，逐帧推进业务日期/时间片，全程不读墙钟。</li>
 * </ul>
 *
 * <p>注意：定时器本身（何时调用 {@code tick()}）用真实节拍器即可，那是「驱动」；
 * 本接口约束的是「任务到点判定与业务日期」这一<b>业务时间</b>，两者刻意解耦。
 */
@FunctionalInterface
public interface JobClock {

    /** 当前本地时间（业务时间，非墙钟直取）。 */
    LocalDateTime now();

    /** 当前业务日期。 */
    default LocalDate today() {
        return now().toLocalDate();
    }

    /**
     * 生产时钟：经 {@link Clocks#now()} 取时，不直接读系统时间。
     *
     * @return 委托项目统一时钟的时钟实例
     */
    static JobClock system() {
        return Clocks::now;
    }
}
