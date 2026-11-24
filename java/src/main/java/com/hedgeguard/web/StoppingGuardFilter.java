package com.hedgeguard.web;

/**
 * 停机态「拒新请求」过滤器（{@code ARCHITECTURE.md §4.1.1 SYSTEM}）。
 *
 * <p>包装既有 {@link AuthFilter}（通常是 {@link SessionAuthFilter}），<b>不改变</b>其公开路径与
 * 认证语义：先委托认证（保证未登录者仍得 {@code 10006}，不向未认证方泄露「系统正在停机」这一状态），
 * 认证通过后若 {@link ShutdownCoordinator} 已非 {@code RUNNING}，则对<b>业务请求</b>抛
 * {@code 10009 STATE_NOT_ALLOWED}（{@link ErrorCode#STATE_NOT_ALLOWED}，沿用既有码、不新造）。
 *
 * <p>放行例外（冻结）：① 关闭端点自身（让重复关闭由 handler 给出 {@code 10009} 与当前状态，
 * 而非被过滤器笼统拒绝）；② {@code /ping}（存活探针，供运维/健康检查观察停机进度）；
 * ③ {@code /auth/logout}（允许用户主动登出）。前端静态资源由 {@code RequestDispatcher} 在认证
 * 之前直接处理，不经过本过滤器；{@code /auth/login} 属公开白名单，同样不经过本过滤器——
 * 停机排空窗口仅数秒，且此时进程即将退出，不构成业务风险。
 *
 * <p>为何做成 {@code AuthFilter} 装饰器而非改 {@code RequestDispatcher}：后者构造签名是大量单测
 * 的共用入口，加字段会引发连锁改动；装饰器保持「分发顺序（冻结）」与既有构造形态不变。
 */
public final class StoppingGuardFilter implements AuthFilter {

    private final AuthFilter delegate;
    private final ShutdownCoordinator coordinator;

    /**
     * @param delegate    被包装的认证过滤器（不得为 {@code null}）
     * @param coordinator 停机状态协调器（不得为 {@code null}）
     */
    public StoppingGuardFilter(AuthFilter delegate, ShutdownCoordinator coordinator) {
        if (delegate == null || coordinator == null) {
            throw new IllegalArgumentException("delegate/coordinator 不得为 null");
        }
        this.delegate = delegate;
        this.coordinator = coordinator;
    }

    @Override
    public boolean isPublic(RequestContext ctx) {
        return delegate.isPublic(ctx);
    }

    @Override
    public void authenticate(RequestContext ctx) {
        delegate.authenticate(ctx);
        if (!coordinator.isStopping() || isAllowed(ctx)) {
            return;
        }
        throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                "系统正在停机，拒绝新请求（10009）：state=" + coordinator.state()
                        + "；复现=等待停机完成后由运维按 scripts/deploy/start-all.sh 重新启动；"
                        + "关闭系统入口=" + ApiRoutes.full(ApiRoutes.SYSTEM_SHUTDOWN));
    }

    /** 停机期间仍放行的路径（见类 javadoc 的「放行例外」）。 */
    private static boolean isAllowed(RequestContext ctx) {
        String path = ctx.path();
        return path.equals(ApiRoutes.full(ApiRoutes.SYSTEM_SHUTDOWN))
                || path.equals(ApiRoutes.full(ApiRoutes.PING))
                || path.equals(ApiRoutes.full("/auth/logout"));
    }
}
