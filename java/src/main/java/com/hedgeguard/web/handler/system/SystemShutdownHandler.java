package com.hedgeguard.web.handler.system;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.web.ApiResponse;
import com.hedgeguard.web.ApiRoutes;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.HttpResult;
import com.hedgeguard.web.JsonCodec;
import com.hedgeguard.web.RequestContext;
import com.hedgeguard.web.RouteHandler;
import com.hedgeguard.web.ShutdownCoordinator;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code POST /api/v1/system/shutdown} —— 管理端「关闭系统」（{@code ARCHITECTURE.md §4.1.1 SYSTEM}）。
 *
 * <p><b>服务端强制点</b>（非仅前端提示）：本 handler 是鉴权、二次确认、状态机 CAS 与停机编排的
 * 唯一落点。执行顺序（冻结）：
 * <ol>
 *   <li>请求体校验：{@code {confirm:true, reason}}，缺一或原因空白/超长 ⇒ {@code 10001}；</li>
 *   <li>账户级配置权断言（注入端口，生产 = {@code AuthService.assertCanConfigureAccountLevel}），
 *       失败 ⇒ 沿用 {@code 10003} 并落越权尝试留痕；</li>
 *   <li>{@link ShutdownCoordinator#beginStopping} CAS：重复关闭 ⇒ {@code 10009}；</li>
 *   <li>经注入端口下发 {@code CMD_SET_READY_STATE(target_state=STOPPING)} 通知 C++ 组件
 *       （best-effort：组件桥未装配/回执失败时<b>显式记日志</b>，不静默假装生效，也不阻断本地停机）；</li>
 *   <li>写访问/停机事件日志 → 安排排空后停机 → 返回 {@code 200} 与停机回执。</li>
 * </ol>
 *
 * <p><b>为何响应先于停机</b>：停机动作经 {@code ShutdownCoordinator} 的排空窗口延迟执行，
 * 保证本次 HTTP 响应能写回客户端；同步 {@code System.exit} 会让运维侧「点了关闭却没有回执」。
 *
 * <p><b>依赖可注入（{@code NFR-TEST-4}）</b>：鉴权、C++ 通知、停机调度/动作全部为端口，
 * 单测无需数据库、无需 CTP、<b>绝不触发真实进程退出</b>。wire 字段一律 camelCase。
 */
public final class SystemShutdownHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(SystemShutdownHandler.class.getName());

    /** 停机原因最大长度（防滥用/超长落日志）。 */
    public static final int MAX_REASON_LENGTH = 200;

    /** 注册路径（逐字取自 {@link ApiRoutes#SYSTEM_SHUTDOWN}）。 */
    public static final String PATH = ApiRoutes.full(ApiRoutes.SYSTEM_SHUTDOWN);

    /** 关闭系统的权限断言端口（生产 = 账户级配置权）。 */
    @FunctionalInterface
    public interface ShutdownAuthorizer {

        /**
         * @param userId 操作人用户 ID
         * @throws BizException 无权时（{@code 10003}）
         */
        void authorize(long userId);
    }

    /** 向 C++ 组件下发 STOPPING 的端口（best-effort，由 handler 捕获异常）。 */
    @FunctionalInterface
    public interface StoppingNotifier {

        /**
         * @param operatorId 操作人用户 ID
         * @param reason     停机原因
         */
        void notifyStopping(long operatorId, String reason);
    }

    private final ShutdownCoordinator coordinator;
    private final ShutdownAuthorizer authorizer;
    private final StoppingNotifier notifier;

    /**
     * @param coordinator 停机状态协调器（不得为 {@code null}）
     * @param authorizer  权限断言端口（不得为 {@code null}）
     * @param notifier    C++ STOPPING 下发端口（不得为 {@code null}）
     */
    public SystemShutdownHandler(ShutdownCoordinator coordinator,
                                 ShutdownAuthorizer authorizer,
                                 StoppingNotifier notifier) {
        if (coordinator == null || authorizer == null || notifier == null) {
            throw new IllegalArgumentException("coordinator/authorizer/notifier 不得为 null");
        }
        this.coordinator = coordinator;
        this.authorizer = authorizer;
        this.notifier = notifier;
    }

    /**
     * 仅供<b>路由装配面</b>（{@code FullWebAssembly} / {@code ModuleRoutesConsistencyTest}）构造
     * 不可触发真实停机的实例：零排空窗口 + 空调度 + 空动作 + 放行鉴权 + 空通知。
     *
     * @return 装配专用实例
     */
    public static SystemShutdownHandler inert() {
        return new SystemShutdownHandler(
                new ShutdownCoordinator(0L, (delayMillis, action) -> { }),
                userId -> { },
                (operatorId, reason) -> { });
    }

    @Override
    public HttpResult handle(RequestContext ctx) {
        Request req = JsonCodec.fromJson(ctx.body(), Request.class);
        if (req == null || !Boolean.TRUE.equals(req.confirm)
                || req.reason == null || req.reason.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "关闭系统须提交 {confirm:true, reason}（二次确认 + 停机原因）："
                            + "复现=在确认框勾选并提交非空停机原因");
        }
        String reason = req.reason.trim();
        if (reason.length() > MAX_REASON_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "停机原因超长：" + reason.length() + " 字符 > 上限 " + MAX_REASON_LENGTH
                            + " 字符；复现=精简原因后重试");
        }
        long operatorId = ctx.currentUserId();
        authorizer.authorize(operatorId);

        String operator = ctx.session() == null ? "" : ctx.session().username;
        if (!coordinator.beginStopping(operatorId, operator, reason)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "系统已进入停机流程，拒绝重复关闭（10009）：state=" + coordinator.state()
                            + "；复现=重复提交关闭系统请求");
        }

        try {
            notifier.notifyStopping(operatorId, reason);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "CMD_SET_READY_STATE(STOPPING) 下发未成功（继续本地停机，"
                    + "不静默视为已生效）：" + e.getMessage(), e);
        }
        LOG.info("关闭系统已受理：state=STOPPING operatorId=" + operatorId
                + " operator=" + operator + " traceId=" + ctx.traceId()
                + " reasonLength=" + reason.length());

        coordinator.scheduleShutdown();

        View view = new View();
        view.state = coordinator.state().name();
        view.operatorId = operatorId;
        view.operator = operator;
        view.reason = reason;
        view.drainMillis = coordinator.drainMillis();
        view.requestedAt = Clocks.now().toString();
        return HttpResult.json(200, ApiResponse.ok(view, ctx.traceId()));
    }

    /** 请求体（字段 camelCase；{@code confirm} 用包装类型以区分「缺字段」与 {@code false}）。 */
    public static final class Request {

        /** 二次确认标记，必须为 {@code true}。 */
        public Boolean confirm;

        /** 停机原因（必填、非空、≤ 200 字符；上限见外层常量）。 */
        public String reason;
    }

    /** 停机回执视图（字段 camelCase）。 */
    public static final class View {

        /** 停机状态（固定 {@code STOPPING}）。 */
        public String state;

        /** 操作人用户 ID。 */
        public long operatorId;

        /** 操作人登录名。 */
        public String operator;

        /** 停机原因。 */
        public String reason;

        /** 排空窗口（毫秒），到点后触发停机。 */
        public long drainMillis;

        /** 受理时间（ISO-8601 本地时区）。 */
        public String requestedAt;
    }
}
