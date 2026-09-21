package com.hedgeguard.service;

import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.util.List;

/**
 * 调用者「可见子账户集合」的取数入口（{@code NFR-SEC-7} 取数侧的最小抽象）。
 *
 * <p><b>为何单独抽接口</b>：{@code PlanService} 的读取面需按调用者身份取 scope，但直接依赖整个
 * {@code AuthService} 会把"读取路径"与"认证/会话/岗位分离"耦合在一起，且令单测难以注入。
 * 本接口只暴露一个纯取数方法，由 {@link AuthService#visibleSubAccountIds(long)} 实现，
 * 亦可由测试以 lambda 注入——{@code PlanService} 不因此多依赖任何 I/O。
 *
 * <p><b>返回语义（与 {@code ARCHITECTURE.md} §8.2 取数规则一致，可判定）</b>：
 * <ul>
 *   <li>集合<b>含哨兵 {@link com.hedgeguard.dao.SubAccountScope#ALL_ACCOUNT_SENTINEL}（= 0）</b>
 *       ⇒ 调用者跨账户只读（管理层 / 审计岗）⇒ <b>不构造 scope、不加过滤</b>（可看全部，只读由写操作面另行强制）；</li>
 *   <li>集合<b>非空且不含哨兵</b> ⇒ 仅可见集合内那些子账户（{@code sub_account_id IN (...)}）；</li>
 *   <li>集合<b>为空</b> ⇒ <b>恒假条件</b>（什么都看不到；<b>严禁</b>退化为"不过滤 = 全可见"）。</li>
 * </ul>
 *
 * <p>实现方保证返回值可判定；调用方（{@code PlanService}）对 {@code null} 一律按空集处理（fail-closed）。
 *
 * <p><b>读面 / 写面两条断言必须区分</b>（{@code T-115}；{@code NFR-SEC-7} / {@code FR-AUTH-13} /
 * {@code DATA_MODEL.md} §{@code t_user_sub_account_perm} 备注）：
 * <ul>
 *   <li>{@link #assertSubAccountVisible(long, int)} 为 <b>读面</b>可见性断言 —— 集合含哨兵
 *       {@link SubAccountScope#ALL_ACCOUNT_SENTINEL}（跨子账户只读）或含目标子账户 ⇒ 放行；
 *       服务「查看/列表/详情/导出」等只读面（{@code FR-AUTH-13} 验收 1：跨子账户权限可查看全部子账户数据）。</li>
 *   <li>{@link #assertSubAccountWritable(long, int)} 为 <b>写面</b>断言 —— 仅当集合含<b>目标子账户</b>
 *       （{@code LIMITED} 明确可读写授权）⇒ 放行；<b>仅含哨兵（{@code CROSS_READONLY}）不得写</b>
 *       （{@code FR-AUTH-13} 验收 3 / {@code R-AUTH-5}：跨子账户权限默认只读、查看权限 ≠ 操作权限）。</li>
 * </ul>
 *
 * <p>两方法均为 <b>default</b>，与同一份可见性语义<b>共置</b>，使读面取数与写面断言不会各写一套而漂移；
 * <b>{@code @FunctionalInterface} 语义不变</b>（default 方法不计入单一抽象方法）⇒ 既有读面测试 lambda
 * 无需改动。生产装配下由 {@link AuthService}（{@code implements SubAccountVisibility}）以<b>同名方法覆盖</b>，
 * 附带 {@code t_unauthorized_attempt} 留痕（{@code R-AUTH-8}）。
 */
@FunctionalInterface
public interface SubAccountVisibility {

    /**
     * 取该用户可见的子账户集合。
     *
     * @param userId 用户 ID
     * @return 可见子账户 ID 列表（含哨兵 0 表示跨账户只读；空列表表示无任何可见数据）
     */
    List<Integer> visibleSubAccountIds(long userId);

    /**
     * <b>读面</b>子账户可见性断言（{@code NFR-SEC-7} 只读面；{@code FR-AUTH-13} 验收 1）。
     *
     * <p>判据与 {@link #visibleSubAccountIds(long)} 同源：集合含哨兵
     * {@link SubAccountScope#ALL_ACCOUNT_SENTINEL}（跨子账户只读）或含目标子账户 ⇒ 放行；
     * 否则（含空集 / 未授权）⇒ 拒绝 {@code 10004}（fail-closed，绝不退化为"不过滤 = 放行"）。
     *
     * <p><b>仅用于只读面</b>（列表/详情/导出等「查看」动作）。<b>写动作一律用</b>
     * {@link #assertSubAccountWritable(long, int)}——跨子账户只读（哨兵 0）在读面放行、写面必须拒绝
     * （{@code T-115}）。{@link AuthService} 以同名方法覆盖本 default 实现（追加越权尝试留痕）。
     *
     * @param userId       用户 ID
     * @param subAccountId 目标子账户
     * @throws BizException 不可见时 {@code 10004}
     */
    default void assertSubAccountVisible(long userId, int subAccountId) {
        List<Integer> visible = visibleSubAccountIds(userId);
        if (visible != null) {
            for (Integer id : visible) {
                if (id != null && (id == SubAccountScope.ALL_ACCOUNT_SENTINEL || id == subAccountId)) {
                    return;
                }
            }
        }
        throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                "无权访问该子账户数据：userId=" + userId + " subAccountId=" + subAccountId
                        + "；复现=由管理层/审计岗授予有限授权或跨账户只读授权（FR-AUTH-13）");
    }

    /**
     * <b>写面</b>子账户写入断言（{@code NFR-SEC-7} 写路径强制点；{@code FR-AUTH-13} 验收 3；
     * {@code T-115}）。
     *
     * <p>判据：集合含<b>目标子账户</b>（{@code LIMITED} 明确可读写授权）⇒ 放行；否则 ⇒ 拒绝
     * {@code 10004}（fail-closed）。<b>仅含哨兵 {@link SubAccountScope#ALL_ACCOUNT_SENTINEL}
     * （{@code CROSS_READONLY} 跨子账户只读）不得写</b>——跨子账户权限默认只读，查看权限不等于操作权限
     * （{@code R-AUTH-5} / {@code DATA_MODEL.md}：{@code perm_type='CROSS_READONLY'} 的只读性由服务层
     * 在写操作前校验）。
     *
     * <p>{@link AuthService} 以同名方法覆盖本 default 实现（追加越权尝试留痕）；本 default 供测试以
     * lambda 注入时仍获得<b>一致的 fail-closed 语义</b>。
     *
     * @param userId       用户 ID
     * @param subAccountId 目标子账户（真实子账户，{@code > 0}）
     * @throws BizException 不可写时 {@code 10004}
     */
    default void assertSubAccountWritable(long userId, int subAccountId) {
        List<Integer> visible = visibleSubAccountIds(userId);
        if (visible != null) {
            for (Integer id : visible) {
                if (id != null && id != SubAccountScope.ALL_ACCOUNT_SENTINEL && id == subAccountId) {
                    return;
                }
            }
        }
        throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                "无权写入该子账户数据（跨子账户只读授权或未授权）：userId=" + userId
                        + " subAccountId=" + subAccountId
                        + "；复现=由管理层为该子账户授予限定（LIMITED）写授权"
                        + "（NFR-SEC-7 / FR-AUTH-13 验收 3 / DATA_MODEL.md t_user_sub_account_perm）");
    }
}
