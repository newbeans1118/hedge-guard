package com.hedgeguard.web;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 「关闭系统」状态协调器（{@code ARCHITECTURE.md §4.1.1 SYSTEM / POST /api/v1/system/shutdown}）。
 *
 * <p>职责边界：只持有<b>停机状态机</b>与<b>排空调度</b>，不碰 HTTP、不碰数据库、不碰 CTP。
 * 停机动作（{@link #onShutdown(Runnable)}）与调度器（{@link ShutdownScheduler}）均为
 * <b>可注入端口</b>（{@code NFR-TEST-4}）——生产装配在 {@code Bootstrap.main} 注入
 * {@code System.exit(0)}（触发既有 {@code hg-shutdown} 钩子释放资源），单测注入记录型实现，
 * <b>绝不触发真实进程退出</b>。
 *
 * <p>状态机（原子、单向）：{@code RUNNING → STOPPING → STOPPED}。
 * {@code beginStopping} 用 CAS 保证<b>并发关闭只受理一次</b>；{@code STOPPING} 期间
 * {@link StoppingGuardFilter} 拒绝一切新业务请求，已受理的在途请求在排空窗口内自然完成。
 * 关闭接口的响应在调度停机动作<b>之前</b>返回，避免响应写回前进程已退出。
 *
 * <p>为何需要排空窗口：{@code HttpServerLauncher.stop(0)} 会立即停止接收并中断工作线程池；
 * 若在同一请求线程内同步触发，本次响应可能写不回客户端，运维侧表现为「点了关闭但没回执」。
 * 故固定延迟（默认 {@value #DEFAULT_DRAIN_MILLIS} ms）由守护线程执行停机动作。
 */
public final class ShutdownCoordinator {

    private static final Logger LOG = Logger.getLogger(ShutdownCoordinator.class.getName());

    /** 默认排空窗口（毫秒）：留足在途请求完成 + 关闭接口响应写回的时间。 */
    public static final long DEFAULT_DRAIN_MILLIS = 3000L;

    /** 停机状态（单向）。 */
    public enum State {

        /** 正常运行。 */
        RUNNING,

        /** 已受理关闭：拒新请求、已下发 C++ STOPPING、正在排空。 */
        STOPPING,

        /** 停机动作已触发。 */
        STOPPED
    }

    /**
     * 延迟执行端口：生产为守护线程，单测可注入「立即执行 / 仅记录」实现。
     *
     * <p>与 {@code JobScheduler} 的 {@code JobClock} 同族——把「时间」从被测逻辑中剥离。
     */
    @FunctionalInterface
    public interface ShutdownScheduler {

        /**
         * 安排一次延迟执行。
         *
         * @param delayMillis 延迟毫秒数
         * @param action      到期执行的动作
         */
        void schedule(long delayMillis, Runnable action);
    }

    private final long drainMillis;
    private final ShutdownScheduler scheduler;
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    private final AtomicReference<Runnable> shutdownAction = new AtomicReference<>(() -> { });
    private volatile long operatorId;
    private volatile String operator = "";
    private volatile String reason = "";

    /** 生产构造：默认排空窗口 + 守护线程调度。 */
    public ShutdownCoordinator() {
        this(DEFAULT_DRAIN_MILLIS, daemonScheduler());
    }

    /**
     * 可注入构造（测试 / 特殊部署）。
     *
     * @param drainMillis 排空窗口（毫秒，须 ≥ 0）
     * @param scheduler   延迟执行端口（不得为 {@code null}）
     */
    public ShutdownCoordinator(long drainMillis, ShutdownScheduler scheduler) {
        if (drainMillis < 0) {
            throw new IllegalArgumentException("排空窗口不得为负：" + drainMillis);
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("ShutdownScheduler 不得为 null");
        }
        this.drainMillis = drainMillis;
        this.scheduler = scheduler;
    }

    /**
     * 受理一次关闭（CAS，仅 {@code RUNNING} 可进入 {@code STOPPING}）。
     *
     * @param operatorId 操作人用户 ID
     * @param operator   操作人登录名（留痕）
     * @param reason     停机原因（留痕）
     * @return 本次成功受理返回 {@code true}；已处于停机流程返回 {@code false}
     */
    public boolean beginStopping(long operatorId, String operator, String reason) {
        if (!state.compareAndSet(State.RUNNING, State.STOPPING)) {
            return false;
        }
        this.operatorId = operatorId;
        this.operator = operator == null ? "" : operator;
        this.reason = reason == null ? "" : reason;
        return true;
    }

    /** 当前状态。 */
    public State state() {
        return state.get();
    }

    /** 是否已进入停机流程（{@code STOPPING} 或 {@code STOPPED}）。 */
    public boolean isStopping() {
        return state.get() != State.RUNNING;
    }

    /** 操作人用户 ID。 */
    public long operatorId() {
        return operatorId;
    }

    /** 操作人登录名。 */
    public String operator() {
        return operator;
    }

    /** 停机原因。 */
    public String reason() {
        return reason;
    }

    /** 排空窗口（毫秒）。 */
    public long drainMillis() {
        return drainMillis;
    }

    /**
     * 注入真实停机动作（生产 = {@code System.exit(0)}；缺省为空操作）。
     *
     * @param action 停机动作（不得为 {@code null}）
     */
    public void onShutdown(Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException("停机动作不得为 null");
        }
        shutdownAction.set(action);
    }

    /** 安排停机（由 {@code SystemShutdownHandler} 在响应写回前调用，动作在排空窗口后执行）。 */
    public void scheduleShutdown() {
        scheduler.schedule(drainMillis, this::runShutdown);
    }

    /** 排空到期：置 {@code STOPPED} 并执行可注入停机动作。包内可见，供单测直接驱动。 */
    void runShutdown() {
        state.set(State.STOPPED);
        try {
            shutdownAction.get().run();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "停机动作执行失败（状态已置 STOPPED，不回滚状态机）", e);
        }
    }

    /** 生产调度器：一次性守护线程，不阻塞 JVM 退出。 */
    private static ShutdownScheduler daemonScheduler() {
        return (delayMillis, action) -> {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                action.run();
            }, "hg-system-shutdown");
            t.setDaemon(true);
            t.start();
        };
    }
}
