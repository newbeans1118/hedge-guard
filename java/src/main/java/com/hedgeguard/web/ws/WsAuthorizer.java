package com.hedgeguard.web.ws;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.UserSubAccountPerm;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WebSocket 推送的**子账户数据权限**裁决（`CONTRACTS.md` §4.1.2 `W-2`，**安全必做**）。
 *
 * <p>依据：`NFR-SEC-7`、业务码 `10004`；`ARCHITECTURE.md` §4.1.2（v1.5.3 补）明确
 * 「推送前必须按 `t_user_sub_account_perm` 过滤，**安全默认 = 空集**」。
 *
 * <p><b>三条硬规则</b>：
 * <ol>
 *   <li><b>不得只依赖客户端自报</b>：客户端 `subAccounts` 只是"申请集"，**必须**与授权集求交；</li>
 *   <li><b>安全默认</b>：无任何授权 ⇒ 有效集为**空** ⇒ **一条都不下发**（与 `SubAccountScope` 的恒假条件同款纪律）；</li>
 *   <li><b>越权即拒</b>：申请集中出现**未授权**子账户 ⇒ 记录越权留痕并以 `10004` 拒绝（关闭码 `1008`），
 *       **不得**静默"只取交集"放行（静默放行会让越权尝试不留痕）。</li>
 * </ol>
 */
public final class WsAuthorizer {

    private final SubAccountGrantReader grantReader;
    private final WsViolationSink violationSink;

    /**
     * 构造。
     *
     * @param grantReader   授权集读取器（生产实现见 {@link PermDaoGrantReader}）
     * @param violationSink 越权留痕（生产实现应落 {@code t_unauthorized_attempt}）
     */
    public WsAuthorizer(SubAccountGrantReader grantReader, WsViolationSink violationSink) {
        this.grantReader = grantReader;
        this.violationSink = violationSink;
    }

    /**
     * 裁决订阅申请。
     *
     * @param userId    会话用户
     * @param requested 客户端申请的子账户集（可为 {@code null}/空 ⇒ 语义为"全部已授权子账户"）
     * @param desc      越权留痕用的描述（如 {@code "订阅 /ws/alert"}）
     * @return **有效子账户集**（恒为授权集的子集；为空 ⇒ 不下发任何数据）
     * @throws WsProtocolException 申请集中含未授权子账户（`10004` ⇒ 关闭码 `1008`）
     */
    public Set<Integer> authorize(long userId, Collection<Integer> requested, String desc) {
        Set<Integer> granted = grantReader.grantedSubAccounts(userId);
        if (granted == null || granted.isEmpty()) {
            // 安全默认：无授权 ⇒ 空集（**不抛错**，因为"没授权"不等于"越权尝试"）
            return new LinkedHashSet<>();
        }
        if (requested == null || requested.isEmpty()) {
            // 未指定 ⇒ 使用其全部已授权子账户（仍严格限于授权集内）
            return new LinkedHashSet<>(granted);
        }
        Set<Integer> effective = new LinkedHashSet<>();
        for (Integer sa : requested) {
            if (sa == null) {
                continue;
            }
            if (!granted.contains(sa)) {
                // 越权：留痕 + 拒绝（不静默求交）
                recordViolation(userId, sa, desc);
                throw new WsProtocolException(WsCloseCode.POLICY_VIOLATION,
                        "10004 子账户权限不足，拒绝订阅");
            }
            effective.add(sa);
        }
        return effective;
    }

    /**
     * 写入越权留痕。
     *
     * @param userId     操作人
     * @param subAccount 涉及的子账户
     * @param desc       尝试描述
     */
    public void recordViolation(long userId, Integer subAccount, String desc) {
        violationSink.record(new WsViolation(userId, subAccount, desc, Clocks.now()));
    }

    /** 授权集读取器（把数据访问与裁决解耦，便于离线单测）。 */
    @FunctionalInterface
    public interface SubAccountGrantReader {

        /**
         * 读取用户**当前生效**的已授权子账户集合。
         *
         * @param userId 用户
         * @return 已授权子账户（无授权返回**空集**，不得返回 {@code null} 表示"全可见"）
         */
        Set<Integer> grantedSubAccounts(long userId);
    }

    /** 越权留痕出口（生产实现应落 `t_unauthorized_attempt`，`TB-AUTH-13`）。 */
    @FunctionalInterface
    public interface WsViolationSink {

        /**
         * 记录一次越权尝试。
         *
         * @param violation 越权事件
         */
        void record(WsViolation violation);
    }

    /** 越权事件（`WS_SUB_ACCOUNT` 类）。 */
    public static final class WsViolation {

        /** 操作人。 */
        public final long userId;

        /** 涉及的子账户（可为 {@code null}）。 */
        public final Integer subAccountId;

        /** 尝试描述。 */
        public final String desc;

        /** 尝试时间。 */
        public final java.time.LocalDateTime at;

        WsViolation(long userId, Integer subAccountId, String desc, java.time.LocalDateTime at) {
            this.userId = userId;
            this.subAccountId = subAccountId;
            this.desc = desc;
            this.at = at;
        }
    }

    /**
     * 生产实现：从 {@code t_user_sub_account_perm} 读取**当前生效**的授权子账户。
     *
     * <p>生效判据：{@code effective_from <= 今日} 且（{@code effective_to} 为空 或 {@code >= 今日}）。
     * 依据 `DATA_MODEL.md` §2.2 `TB-AUTH-8`。
     *
     * <p>⚠️ 依赖 {@code UserSubAccountPermDao} 的实现（{@code DmUserSubAccountPermDao} 尚未交付，
     * 见 `CONTRACTS.md` `D-3`）；本类可在其就绪后直接装配，**当前不接线到 Bootstrap**。
     */
    public static final class PermDaoGrantReader implements SubAccountGrantReader {

        private final UserSubAccountPermDao permDao;

        /**
         * 构造。
         *
         * @param permDao 子账户数据权限 DAO
         */
        public PermDaoGrantReader(UserSubAccountPermDao permDao) {
            this.permDao = permDao;
        }

        @Override
        public Set<Integer> grantedSubAccounts(long userId) {
            LocalDate today = Clocks.today();
            Set<Integer> granted = new LinkedHashSet<>();
            List<UserSubAccountPerm> perms = permDao.findByUser(userId);
            if (perms == null) {
                return granted;
            }
            for (UserSubAccountPerm p : perms) {
                if (p.effectiveFrom != null && p.effectiveFrom.isAfter(today)) {
                    continue;
                }
                if (p.effectiveTo != null && p.effectiveTo.isBefore(today)) {
                    continue;
                }
                granted.add(p.subAccountId);
            }
            return granted;
        }
    }
}
