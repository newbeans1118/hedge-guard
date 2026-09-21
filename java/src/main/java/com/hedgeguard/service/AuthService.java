package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.LoginLogDao;
import com.hedgeguard.dao.LoginSessionDao;
import com.hedgeguard.dao.PasswordHistoryDao;
import com.hedgeguard.dao.PositionConflictDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.LoginLog;
import com.hedgeguard.dao.entity.LoginSession;
import com.hedgeguard.dao.entity.PasswordHistory;
import com.hedgeguard.dao.entity.PositionConflict;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.entity.UserSubAccountPerm;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.Session;
import com.hedgeguard.web.SessionStore;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 用户与权限服务（{@code T-010}：登录 / 会话 / 岗位分离 / 负责人禁操盘 / 子账户软隔离）。
 *
 * <p>依据：{@code FR-AUTH-1~18}、{@code R-AUTH-13}、{@code NFR-SEC-2}、{@code NFR-SEC-7}、
 * {@code ARCHITECTURE.md} §7.2.2（PBKDF2）、§11.1 {@code CFG-23}（登录失败锁定 5 次 / 5 分钟）与
 * {@code CFG-23b}（会话空闲 30 min / 口令长度 12 / 历史重用 5）、{@code DATA_MODEL.md} §2.2。
 *
 * <p>五条纪律在本类中的落点：
 * <ol>
 *   <li><b>不区分"用户不存在"与"口令错误"</b>（{@code §4.3.1} {@code 10007} 备注：统一提示，防账号枚举）——
 *       两者返回同一 {@code 10007} 文案；</li>
 *   <li><b>明文口令零落盘零日志</b>：只经 {@link PasswordHasher} 派生后存哈希；异常信息不含口令；</li>
 *   <li><b>岗位互斥</b>（{@code DATA_MODEL.md} §2.2 {@code TRADER ↔ FIN_RISK/SETTLER/CASHIER}）：
 *       授权前按 {@code t_position_conflict} 校验，冲突则拒绝并记 {@code t_unauthorized_attempt}；</li>
 *   <li><b>负责人禁操盘</b>（{@code FR-AUTH-9} / {@code R-AUTH-13}）：账号 {@code is_enterprise_leader='Y'}
 *       <b>或</b>持管理层角色（{@code APPROVER}）——<b>析取</b>判据（{@code CH-010}）——者，
 *       {@link #assertCanTrade} 一律拒绝；</li>
 *   <li><b>子账户软隔离</b>（{@code NFR-SEC-7}）：默认互不可见，跨账户<b>查看</b>须
 *       {@code CROSS_READONLY} 授权（{@code FR-AUTH-13} 验收 1）；<b>写面</b>由
 *       {@link #assertSubAccountWritable} 在各写路径强制（{@code T-102}/{@code T-115}）——
 *       跨子账户只读（{@code CROSS_READONLY}）<b>不得写</b>（{@code FR-AUTH-13} 验收 3）。</li>
 * </ol>
 */
public final class AuthService implements SubAccountVisibility {

    private static final Logger LOG = Logger.getLogger(AuthService.class.getName());

    /** 管理层/审批人角色编码（{@code DATA_MODEL.md} §2.2 预置六类业务角色）。 */
    public static final String ROLE_APPROVER = "APPROVER";

    /** 交易岗角色编码。 */
    public static final String ROLE_TRADER = "TRADER";

    /** 登录失败锁定阈值（{@code CFG-23 hg.auth.login_fail_limit}）。 */
    public static final int LOGIN_FAIL_LIMIT = 5;

    /** 锁定时长（分钟，{@code CFG-23 hg.auth.login_lock_minutes}）。 */
    public static final int LOGIN_LOCK_MINUTES = 5;

    /** 会话空闲超时（分钟，{@code CFG-23b hg.auth.session_idle_minutes}）。 */
    public static final int SESSION_IDLE_MINUTES = 30;

    /** 口令最小长度（{@code CFG-23b hg.auth.password_min_length}）。 */
    public static final int PASSWORD_MIN_LENGTH = 12;

    /** 近期口令历史条数（{@code CFG-23b hg.auth.password_history_size}）。 */
    public static final int PASSWORD_HISTORY_SIZE = 5;

    /** 子账户授权的跨账户只读类型（{@code FR-AUTH-13}）。 */
    public static final String PERM_CROSS_READONLY = "CROSS_READONLY";

    /** 子账户授权的限定类型（默认最小权限）。 */
    public static final String PERM_LIMITED = "LIMITED";

    /** 越权尝试类型：管理层/负责人试图操盘（{@code R-AUTH-13}）。 */
    public static final String ATTEMPT_MANAGEMENT_TRADE_BLOCKED = "MANAGEMENT_TRADE_BLOCKED";

    /** 越权尝试类型：访问未授权子账户（{@code NFR-SEC-7}）。 */
    public static final String ATTEMPT_SUB_ACCOUNT_DENIED = "SUB_ACCOUNT_DENIED";

    /** 越权尝试类型：岗位互斥角色授权（{@code FR-AUTH-8}）。 */
    public static final String ATTEMPT_POSITION_CONFLICT = "POSITION_CONFLICT";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TxTemplate tx;
    private final UserAccountDao userAccountDao;
    private final RoleDao roleDao;
    private final UserRoleDao userRoleDao;
    private final PositionConflictDao positionConflictDao;
    private final LoginSessionDao loginSessionDao;
    private final LoginLogDao loginLogDao;
    private final PasswordHistoryDao passwordHistoryDao;
    private final UserSubAccountPermDao userSubAccountPermDao;
    private final UnauthorizedAttemptDao unauthorizedAttemptDao;
    private final SessionStore sessionStore;

    /**
     * 构造。
     *
     * @param tx                     事务模板
     * @param userAccountDao         用户 DAO
     * @param roleDao                角色 DAO
     * @param userRoleDao            用户角色 DAO
     * @param positionConflictDao    岗位互斥 DAO
     * @param loginSessionDao        登录会话 DAO
     * @param loginLogDao            登录日志 DAO
     * @param passwordHistoryDao     口令历史 DAO
     * @param userSubAccountPermDao  子账户数据权限 DAO
     * @param unauthorizedAttemptDao 越权尝试 DAO
     * @param sessionStore           会话存储（HTTP 层共用）
     */
    public AuthService(TxTemplate tx, UserAccountDao userAccountDao, RoleDao roleDao,
                       UserRoleDao userRoleDao, PositionConflictDao positionConflictDao,
                       LoginSessionDao loginSessionDao,
                       LoginLogDao loginLogDao, PasswordHistoryDao passwordHistoryDao,
                       UserSubAccountPermDao userSubAccountPermDao,
                       UnauthorizedAttemptDao unauthorizedAttemptDao, SessionStore sessionStore) {
        this.tx = tx;
        this.userAccountDao = userAccountDao;
        this.roleDao = roleDao;
        this.userRoleDao = userRoleDao;
        this.positionConflictDao = positionConflictDao;
        this.loginSessionDao = loginSessionDao;
        this.loginLogDao = loginLogDao;
        this.passwordHistoryDao = passwordHistoryDao;
        this.userSubAccountPermDao = userSubAccountPermDao;
        this.unauthorizedAttemptDao = unauthorizedAttemptDao;
        this.sessionStore = sessionStore;
    }

    // ------------------------------------------------------------ 登录 / 会话

    /**
     * 登录（{@code FR-AUTH-1}；会话空闲 {@value #SESSION_IDLE_MINUTES} 分钟）。
     *
     * <p>失败一律 {@code 10007}（与"用户不存在"同文案，防枚举）；连续 {@value #LOGIN_FAIL_LIMIT} 次
     * 失败后锁定 {@value #LOGIN_LOCK_MINUTES} 分钟（{@code 10008}）。
     *
     * @param username  登录名
     * @param password  明文口令（仅内存）
     * @param clientIp  客户端 IP
     * @param userAgent 客户端 UA
     * @return 会话
     */
    public Session login(String username, String password, String clientIp, String userAgent) {
        UserAccount user = userAccountDao.findByUsername(username);
        if (user == null) {
            writeLoginLog(null, username, "LOGIN_FAIL", null, clientIp, userAgent, "10007");
            throw authFailed();
        }
        if (user.lockedUntil != null && user.lockedUntil.isAfter(Clocks.now())) {
            writeLoginLog(user.id, username, "LOGIN_FAIL", user.failCount, clientIp, userAgent, "10008");
            throw BizException.of(ErrorCode.ACCOUNT_LOCKED,
                    "账号已锁定，解锁时间：" + user.lockedUntil + "；复现=等待锁定到期或由管理员解锁"
                            + "（CFG-23 失败 " + LOGIN_FAIL_LIMIT + " 次锁 "
                            + LOGIN_LOCK_MINUTES + " 分钟）");
        }
        boolean ok = "ENABLED".equals(user.status)
                && PasswordHasher.verify(password, user.passwordHash);
        if (!ok) {
            int fails = user.failCount + 1;
            LocalDateTime lockUntil = fails >= LOGIN_FAIL_LIMIT
                    ? Clocks.now().plusMinutes(LOGIN_LOCK_MINUTES) : null;
            tx.inTransaction(connection -> {
                userAccountDao.incrementFailCount(user.id);
                if (lockUntil != null) {
                    userAccountDao.lockUntil(user.id, lockUntil);
                }
                return null;
            });
            writeLoginLog(user.id, username, "LOGIN_FAIL", fails, clientIp, userAgent,
                    lockUntil != null ? "10008" : "10007");
            if (lockUntil != null) {
                throw BizException.of(ErrorCode.ACCOUNT_LOCKED,
                        "连续失败达 " + LOGIN_FAIL_LIMIT + " 次，账号锁定至 " + lockUntil
                                + "；复现=等待锁定到期或由管理员解锁");
            }
            throw authFailed();
        }

        LocalDateTime now = Clocks.now();
        LocalDateTime expire = now.plusMinutes(SESSION_IDLE_MINUTES);
        String sessionId = newSessionId();
        Session session = new Session(sessionId, user.id, user.username, permissionVersionOf(user.id),
                expire);
        tx.inTransaction(connection -> {
            userAccountDao.touchLastLogin(user.id, now);
            userAccountDao.unlock(user.id);
            LoginSession row = new LoginSession();
            row.sessionId = sessionId;
            row.userId = user.id;
            row.loginTime = now;
            row.lastActiveTime = now;
            row.idleTimeoutMinutes = SESSION_IDLE_MINUTES;
            row.expireTime = expire;
            row.clientIp = clientIp;
            row.userAgent = userAgent;
            row.status = "ACTIVE";
            row.permissionVersionSnapshot = session.permissionVersion;
            loginSessionDao.insert(row, user.id);
            return null;
        });
        sessionStore.save(session);
        writeLoginLog(user.id, username, "LOGIN_SUCCESS", 0, clientIp, userAgent, null);
        return session;
    }

    /**
     * 登出：作废内存会话与库内会话行。
     *
     * @param sessionId 会话 ID
     */
    public void logout(String sessionId) {
        Session session = sessionStore.find(sessionId);
        if (session == null) {
            return;
        }
        tx.inTransaction(connection -> {
            loginSessionDao.invalidateByUser(session.userId, "LOGGED_OUT", null,
                    session.permissionVersion);
            return null;
        });
        sessionStore.invalidate(sessionId);
    }

    /**
     * 续期并校验会话（空闲超时 = {@value #SESSION_IDLE_MINUTES} 分钟；{@code CFG-23b}）。
     *
     * <p>滑动过期：有效则把过期时间顺延；已过期则作废并抛 {@code 10006}。
     *
     * @param sessionId 会话 ID
     * @return 续期后的会话；会话不存在返回 {@code null}（由调用方据 {@code §4.1.2} 统一处理）
     */
    public Session touchSession(String sessionId) {
        Session session = sessionStore.find(sessionId);
        if (session == null) {
            return null;
        }
        LocalDateTime now = Clocks.now();
        if (!session.expireTime.isAfter(now)) {
            sessionStore.invalidate(sessionId);
            tx.inTransaction(connection -> {
                loginSessionDao.touch(sessionId, now);
                loginSessionDao.invalidateByUser(session.userId, "EXPIRED", null,
                        session.permissionVersion);
                return null;
            });
            throw BizException.of(ErrorCode.NOT_AUTHENTICATED,
                    "会话空闲超时（" + SESSION_IDLE_MINUTES + " 分钟）已失效；复现=重新登录"
                            + "（CFG-23b hg.auth.session_idle_minutes）");
        }
        Session renewed = new Session(session.sessionId, session.userId, session.username,
                session.permissionVersion, now.plusMinutes(SESSION_IDLE_MINUTES));
        sessionStore.save(renewed);
        tx.inTransaction(connection -> {
            loginSessionDao.touch(sessionId, now);
            return null;
        });
        return renewed;
    }

    /**
     * 口令变更（{@code NFR-SEC-2}：加盐哈希 + 强度策略 + 最近 {@value #PASSWORD_HISTORY_SIZE} 次禁重用）。
     *
     * @param userId      用户 ID
     * @param oldPassword 原口令
     * @param newPassword 新口令
     */
    public void changePassword(long userId, String oldPassword, String newPassword) {
        UserAccount user = userAccountDao.findById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "用户不存在：id=" + userId + "；复现=核对会话中的 userId");
        }
        if (!PasswordHasher.verify(oldPassword, user.passwordHash)) {
            throw authFailed();
        }
        PasswordHasher.assertStrength(newPassword, PASSWORD_MIN_LENGTH);
        if (PasswordHasher.verify(newPassword, user.passwordHash)) {
            throw reuseRejected();
        }
        for (PasswordHistory history : passwordHistoryDao.findRecent(userId, PASSWORD_HISTORY_SIZE)) {
            if (PasswordHasher.verify(newPassword, history.passwordHash)) {
                throw reuseRejected();
            }
        }
        String encoded = PasswordHasher.hash(newPassword);
        LocalDateTime now = Clocks.now();
        tx.inTransaction(connection -> {
            PasswordHistory history = new PasswordHistory();
            history.userId = userId;
            history.passwordHash = user.passwordHash;
            history.changedTime = now;
            history.seqNo = 1;
            passwordHistoryDao.shiftSeqNo(userId);
            passwordHistoryDao.insert(history, userId);
            passwordHistoryDao.deleteBeyond(userId, PASSWORD_HISTORY_SIZE);
            if (userAccountDao.updatePasswordHash(userId, encoded, now) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "口令更新影响行数异常：userId=" + userId + "；复现=核对 t_user 主键");
            }
            return null;
        });
    }

    /**
     * 管理员重置口令：生成一次性随机口令并标记"首次登录须修改"。
     *
     * <p>返回的一次性口令<b>只返回一次</b>、不入日志、不入库（{@code NFR-SEC-2}：重置不以明文展示）。
     *
     * @param userId    目标用户
     * @param operatorId 操作人
     * @return 一次性随机口令（调用方负责展示一次后丢弃）
     */
    public String resetPassword(long userId, long operatorId) {
        UserAccount user = userAccountDao.findById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "用户不存在：id=" + userId + "；复现=核对目标用户 ID");
        }
        String oneTime = PasswordHasher.randomPassword(PASSWORD_MIN_LENGTH + 4);
        String encoded = PasswordHasher.hash(oneTime);
        tx.inTransaction(connection -> {
            userAccountDao.updatePasswordHash(userId, encoded, Clocks.now());
            user.mustChangePassword = "Y";
            userAccountDao.updateById(user, operatorId);
            return null;
        });
        LOG.info("已重置口令并要求首次登录修改：targetUserId=" + userId + " operatorId=" + operatorId);
        return oneTime;
    }

    // ------------------------------------------------------------ 岗位分离与权限

    /**
     * 授予角色（{@code FR-AUTH-8}：岗位互斥；越权配置一律拒绝）。
     *
     * <p>互斥判据来自 {@code t_position_conflict}（{@code groupAId}/{@code groupBId}，
     * {@code DATA_MODEL.md} §2.2 备注给出 {@code TRADER ↔ FIN_RISK/SETTLER/CASHIER} 四对）。
     *
     * @param userId     目标用户
     * @param roleId     角色
     * @param operatorId 操作人
     * @param reviewerId 复核人（须 ≠ 操作人）
     */
    public void grantRole(long userId, long roleId, long operatorId, Long reviewerId) {
        if (reviewerId == null || reviewerId == operatorId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "角色授予须双人复核：reviewer_id 必填且不得等于 operator_id；复现=换复核人"
                            + "（ARCHITECTURE.md §11.1「子账户类/权限类变更须复核」）");
        }
        Role role = roleDao.findById(roleId);
        if (role == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "角色不存在：id=" + roleId + "；复现=按 role_code 查询角色表");
        }
        Set<Integer> existingGroups = positionGroupIdsOf(userId);
        for (Integer groupId : existingGroups) {
            if (groupId == role.positionGroupId) {
                continue;
            }
            if (conflicts(groupId, role.positionGroupId)) {
                recordAttempt(userId, ATTEMPT_POSITION_CONFLICT,
                        "岗位互斥：现有岗位组 " + groupId + " 与目标角色 " + role.roleCode
                                + "（岗位组 " + role.positionGroupId + "）互斥", "10005");
                throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                        "岗位互斥冲突：用户 " + userId + " 已具岗位组 " + groupId
                                + "，不能同时授予 " + role.roleCode + "（岗位组 "
                                + role.positionGroupId + "）；复现=先解除冲突岗位"
                                + "（DATA_MODEL.md §2.2 互斥矩阵）");
            }
        }
        tx.inTransaction(connection -> {
            UserRole userRole = new UserRole();
            userRole.userId = userId;
            userRole.roleId = roleId;
            userRole.grantedBy = operatorId;
            userRole.grantedTime = Clocks.now();
            userRole.reviewerId = reviewerId;
            if (userRoleDao.insert(userRole, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "该用户已持有该角色：userId=" + userId + " roleId=" + roleId
                                + "；复现=核对 t_user_role 唯一键");
            }
            // 权限变更须使相关会话立即失效（R-AUTH-9）
            loginSessionDao.invalidateByUser(userId, "PERMISSION_CHANGED", operatorId,
                    permissionVersionOf(userId) + 1);
            return null;
        });
    }

    /**
     * 操盘前置校验（{@code FR-AUTH-9} / {@code R-AUTH-13}：负责人 / 管理层禁操盘）。
     *
     * <p><b>判据为「析取」（{@code CH-010} 澄清，{@code FR-AUTH-9} 处理规则）</b>：账号满足下列
     * <b>任一</b>即<b>拒绝</b>操盘（并记 {@code t_unauthorized_attempt}，{@code R-AUTH-8}）——
     * <b>(a)</b> {@code is_enterprise_leader='Y'}（企业负责人）；<b>或</b> <b>(b)</b> 持管理层角色
     * （{@code APPROVER}）。<b>不得</b>要求二者<b>同时</b>满足（合取实现会两个方向都漏：
     * 「负责人但非管理层」与「管理层但非负责人」均被放行）。残余校验：不具交易岗能力者不得下单。
     * 拒绝时记 {@code t_unauthorized_attempt}（审计），并由 C++ 侧 {@code 0x100F} 分支兜底复核
     * （{@code ARCHITECTURE.md} §6.3）。
     *
     * <p><b>服务端强制点（{@code T-102} 裁定；`ARCHITECTURE.md` §1.2 通则）</b>：<b>M2 的 Java 下单写路径</b>
     * （{@code POST /api/v1/trade/order} → {@code TradingBridge.placeOrder} <b>前置</b>；错误码
     * {@code 10005}/{@code 10003}），归 {@code T-046}。M1 该端点不存在（前端 {@code web/js/pages/trade.js}
     * 明示「未调用 /trade/order」，{@code web/js/core/routes.js} 将其列为 `NOT_REGISTERED`）——
     * 而<b>不得</b>改落在计划写路径上（如 {@code decomposeTradePlans}）：{@code FR-PLAN-14}
     * （{@code PLAN.md:303-316}）明列分解主体为「财务/风控岗 或 期货交易岗」，输出/验收均不含权限项
     * ⇒ 会误拒需求明列的合法主体（控制须由需求要求，不得凭空加）。
     *
     * @param userId       用户 ID
     * @param subAccountId 拟交易的子账户（用于留痕定位，可为 {@code null}）
     */
    public void assertCanTrade(long userId, Integer subAccountId) {
        UserAccount user = userAccountDao.findById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "用户不存在：id=" + userId + "；复现=核对会话中的 userId");
        }
        boolean leader = "Y".equals(user.isEnterpriseLeader);
        boolean management = isManagementRole(userId);
        if (leader || management) {
            String hit = leader && management
                    ? "is_enterprise_leader=Y 且持 " + ROLE_APPROVER + " 角色"
                    : (leader ? "is_enterprise_leader=Y" : "持 " + ROLE_APPROVER + " 角色");
            recordAttempt(userId, ATTEMPT_MANAGEMENT_TRADE_BLOCKED,
                    "负责人 / 管理层禁止操盘（析取判据 CH-010：" + hit + "）", "10005");
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "负责人/管理层账号不得操盘（签署权与交易权互斥，FR-AUTH-9 析取判据）：userId=" + userId
                            + "；命中=" + hit + "；复现=改由交易岗执行下单"
                            + "（R-AUTH-13 / PROJECT.md §3 岗位分离）");
        }
        if (!hasTradingRole(userId)) {
            throw BizException.of(ErrorCode.FUNCTION_PERMISSION_DENIED,
                    "该用户不具交易岗权限：userId=" + userId
                            + "；复现=由管理员授予具 has_trading_operation='Y' 的角色");
        }
    }

    /**
     * 是否持管理层角色（{@code is_management_role} 的判定来源：{@code APPROVER} 角色）。
     *
     * @param userId 用户 ID
     * @return 持管理层角色返回 {@code true}
     */
    public boolean isManagementRole(long userId) {
        for (UserRole userRole : userRoleDao.findByUser(userId)) {
            Role role = roleDao.findById(userRole.roleId);
            if (role != null && ROLE_APPROVER.equals(role.roleCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否具交易岗能力（任一角色 {@code has_trading_operation='Y'} 且启用）。
     *
     * @param userId 用户 ID
     * @return 具交易岗能力返回 {@code true}
     */
    public boolean hasTradingRole(long userId) {
        for (UserRole userRole : userRoleDao.findByUser(userId)) {
            Role role = roleDao.findById(userRole.roleId);
            if (role != null && "Y".equals(role.hasTradingOperation)
                    && "ENABLED".equals(role.status)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>读面</b>子账户可见性断言（{@code NFR-SEC-7} 只读面；{@code FR-AUTH-13} 验收 1）。
     *
     * <p>覆盖 {@link SubAccountVisibility#assertSubAccountVisible(long, int)} 的 default 实现，
     * 追加 {@code t_unauthorized_attempt} 留痕（{@code R-AUTH-8}）。<b>读面语义</b>：持有
     * {@code CROSS_READONLY}（跨子账户只读）者可见全部子账户 ⇒ 放行（与
     * {@link #visibleSubAccountIds(long)} 返回哨兵 0 一致）。
     *
     * <p><b>写动作不得用本方法</b>：写面一律走 {@link #assertSubAccountWritable}——
     * 跨子账户只读在读面放行、写面必须拒绝（{@code T-115}）。本方法仅供「查看」类动作
     * （列表 / 详情 / 导出等）使用。
     *
     * @param userId       用户 ID
     * @param subAccountId 目标子账户
     */
    @Override
    public void assertSubAccountVisible(long userId, int subAccountId) {
        for (UserSubAccountPerm perm : userSubAccountPermDao.findByUser(userId)) {
            if (!inEffect(perm)) {
                continue;
            }
            if (perm.subAccountId == subAccountId
                    || PERM_CROSS_READONLY.equals(perm.permType)) {
                return;
            }
        }
        recordAttempt(userId, ATTEMPT_SUB_ACCOUNT_DENIED,
                "访问未授权子账户：subAccountId=" + subAccountId, "10004");
        throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                "无权访问该子账户数据：userId=" + userId + " subAccountId=" + subAccountId
                        + "；复现=由管理层/审计岗授予有限授权或跨账户只读授权（FR-AUTH-13）");
    }

    /**
     * <b>写面</b>子账户写入断言（{@code NFR-SEC-7} 写路径强制点；{@code FR-AUTH-13} 验收 3；
     * {@code T-115}）。
     *
     * <p>覆盖 {@link SubAccountVisibility#assertSubAccountWritable(long, int)} 的 default 实现，
     * 追加 {@code t_unauthorized_attempt} 留痕（{@code R-AUTH-8}）。
     *
     * <p><b>判据（与 {@link #visibleSubAccountIds(long)} 同源、但剔除跨账户只读）</b>：仅当存在
     * <b>生效中</b>且 {@code permType ≠ CROSS_READONLY}（即 {@code LIMITED}，明确可读写）而
     * {@code subAccountId} 等于目标子账户的授权行 ⇒ 放行；否则 ⇒ 拒绝 {@code 10004}。
     * <b>持有 {@code CROSS_READONLY}（跨子账户只读）者写任意子账户一律拒绝</b>——跨子账户权限默认只读，
     * 查看权限 ≠ 操作权限（{@code R-AUTH-5} / {@code FR-AUTH-13} 验收 3；{@code DATA_MODEL.md}：
     * 「{@code perm_type='CROSS_READONLY'} 的只读性由服务层保证（写操作前校验 {@code perm_type}）」）。
     * <b>fail-closed</b>：无任何授权 / 空集 / 仅哨兵 ⇒ 一律拒绝，绝不退化为"不过滤 = 放行"。
     *
     * @param userId       用户 ID
     * @param subAccountId 目标子账户（真实子账户，{@code > 0}）
     */
    @Override
    public void assertSubAccountWritable(long userId, int subAccountId) {
        for (UserSubAccountPerm perm : userSubAccountPermDao.findByUser(userId)) {
            if (!inEffect(perm)) {
                continue;
            }
            if (!PERM_CROSS_READONLY.equals(perm.permType) && perm.subAccountId == subAccountId) {
                return;
            }
        }
        recordAttempt(userId, ATTEMPT_SUB_ACCOUNT_DENIED,
                "跨子账户只读或未授权不得写：subAccountId=" + subAccountId, "10004");
        throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                "无权写入该子账户数据（跨子账户只读授权或未授权）：userId=" + userId
                        + " subAccountId=" + subAccountId
                        + "；复现=由管理层为该子账户授予限定（LIMITED）写授权"
                        + "（NFR-SEC-7 / FR-AUTH-13 验收 3 / DATA_MODEL.md t_user_sub_account_perm）");
    }

    /**
     * 取该用户可见子账户集合（供 DAO 层 {@code SubAccountScope} 注入）。
     *
     * <p>实现 {@link SubAccountVisibility}（{@code NFR-SEC-7} 取数侧最小抽象，供 {@code PlanService}
     * 等读取面注入）；语义见该接口 javadoc。
     *
     * @param userId 用户 ID
     * @return 可见子账户 ID 列表（含哨兵 0 = 跨账户只读；可能为空 = 无任何可见数据）
     */
    @Override
    public List<Integer> visibleSubAccountIds(long userId) {
        List<Integer> out = new ArrayList<>();
        boolean crossReadonly = false;
        for (UserSubAccountPerm perm : userSubAccountPermDao.findByUser(userId)) {
            if (!inEffect(perm)) {
                continue;
            }
            if (PERM_CROSS_READONLY.equals(perm.permType)) {
                crossReadonly = true;
            } else {
                out.add(perm.subAccountId);
            }
        }
        if (crossReadonly) {
            // 跨账户只读：显式返回哨兵值，由调用方转为"不过滤但只读"路径（FR-AUTH-13）
            out.add(com.hedgeguard.dao.SubAccountScope.ALL_ACCOUNT_SENTINEL);
        }
        return out;
    }

    // ------------------------------------------------------------ 内部

    private long permissionVersionOf(long userId) {
        return 1L + userRoleDao.findByUser(userId).size();
    }

    private Set<Integer> positionGroupIdsOf(long userId) {
        Set<Integer> groups = new HashSet<>();
        for (UserRole userRole : userRoleDao.findByUser(userId)) {
            Role role = roleDao.findById(userRole.roleId);
            if (role != null) {
                groups.add(role.positionGroupId);
            }
        }
        return groups;
    }

    private boolean conflicts(int groupA, int groupB) {
        for (PositionConflict conflict : positionConflictDao.findConflictsOf(groupA)) {
            int other = conflict.groupAId == groupA ? conflict.groupBId : conflict.groupAId;
            if (other == groupB) {
                return true;
            }
        }
        return false;
    }

    private static boolean inEffect(UserSubAccountPerm perm) {
        LocalDate today = Clocks.today();
        boolean fromOk = perm.effectiveFrom == null || !perm.effectiveFrom.isAfter(today);
        boolean toOk = perm.effectiveTo == null || !perm.effectiveTo.isBefore(today);
        return fromOk && toOk;
    }

    private void recordAttempt(long userId, String type, String desc, String rejectCode) {
        UnauthorizedAttempt attempt = new UnauthorizedAttempt();
        attempt.operatorId = userId;
        attempt.attemptType = type;
        attempt.attemptDesc = desc;
        attempt.targetResource = type;
        attempt.rejectReason = desc;
        attempt.rejectCode = rejectCode;
        attempt.attemptTime = Clocks.now();
        tx.inTransaction(connection -> {
            unauthorizedAttemptDao.insert(attempt, userId);
            return null;
        });
    }

    private void writeLoginLog(Long userId, String usernameInput, String eventType,
                              Integer failCountAfter, String clientIp, String userAgent,
                              String failCode) {
        LoginLog log = new LoginLog();
        log.userId = userId;
        log.usernameInput = usernameInput;
        log.eventType = eventType;
        log.failCountAfter = failCountAfter;
        log.clientIp = clientIp;
        log.userAgent = userAgent;
        log.failReasonCode = failCode;
        log.eventTime = Clocks.now();
        tx.inTransaction(connection -> {
            loginLogDao.insert(log, userId == null ? 0L : userId);
            return null;
        });
    }

    private static BizException authFailed() {
        // 统一文案：不区分"用户不存在"与"口令错误"（防账号枚举，§4.3.1 10007 备注）
        return BizException.of(ErrorCode.AUTH_FAILED,
                "用户名或口令不正确；复现=核对登录名与口令（连续失败 " + LOGIN_FAIL_LIMIT
                        + " 次将锁定 " + LOGIN_LOCK_MINUTES + " 分钟）");
    }

    private static BizException reuseRejected() {
        return BizException.of(ErrorCode.PARAM_INVALID,
                "新口令与最近 " + PASSWORD_HISTORY_SIZE + " 次使用过的口令重复；复现=换一个未用过的口令"
                        + "（CFG-23b hg.auth.password_history_size）");
    }

    private static String newSessionId() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
