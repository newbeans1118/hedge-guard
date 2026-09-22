package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.LoginLogDao;
import com.hedgeguard.dao.LoginSessionDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PasswordHistoryDao;
import com.hedgeguard.dao.PermissionChangeLogDao;
import com.hedgeguard.dao.PositionConflictDao;
import com.hedgeguard.dao.PositionGroupDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.UserSubAccountPermDao;
import com.hedgeguard.dao.entity.Authorization;
import com.hedgeguard.dao.entity.LoginLog;
import com.hedgeguard.dao.entity.LoginSession;
import com.hedgeguard.dao.entity.PasswordHistory;
import com.hedgeguard.dao.entity.PermissionChangeLog;
import com.hedgeguard.dao.entity.PositionConflict;
import com.hedgeguard.dao.entity.PositionGroup;
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

import java.math.BigDecimal;
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

    /** 系统管理员角色编码（{@code R-AUTH-10}：不得拥有业务角色）。 */
    public static final String ROLE_SYS_ADMIN = "SYS_ADMIN";

    /** 审计/合规岗角色编码（{@code FR-AUTH-13}：可被授予跨子账户查看）。 */
    public static final String ROLE_AUDITOR = "AUDITOR";

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

    /** 越权尝试类型：管理层/负责人试图操盘（{@code R-AUTH-13}；取值域 {@code ck_unauth_attempt_type}）。 */
    public static final String ATTEMPT_MANAGEMENT_TRADE_BLOCKED = "LEADER_TRADE";

    /** 越权尝试类型：访问/写入未授权子账户（{@code NFR-SEC-7}；取值域 {@code ck_unauth_attempt_type}）。 */
    public static final String ATTEMPT_SUB_ACCOUNT_DENIED = "PERMISSION_DENIED";

    /** 越权尝试类型：岗位互斥角色授权（{@code FR-AUTH-8}）。 */
    public static final String ATTEMPT_POSITION_CONFLICT = "POSITION_CONFLICT";

    /** 越权尝试类型：系统管理员试图持有业务角色（{@code R-AUTH-10}）。 */
    public static final String ATTEMPT_SYSADMIN_BIZ_OP = "SYSADMIN_BIZ_OP";

    /** 越权尝试类型：双人复核缺失/自审自批（{@code R-AUTH-4}）。 */
    public static final String ATTEMPT_DUAL_REVIEW_SELF = "DUAL_REVIEW_SELF";

    /** 权限变更留痕类型：用户属性（含企业负责人标记）。 */
    public static final String CHANGE_USER_ATTR = "USER_ATTR";

    /** 权限变更留痕类型：用户角色。 */
    public static final String CHANGE_USER_ROLE = "USER_ROLE";

    /** 权限变更留痕类型：授权。 */
    public static final String CHANGE_AUTHORIZATION = "AUTHORIZATION";

    /** 权限变更留痕类型：子账户数据权限。 */
    public static final String CHANGE_SUB_ACCOUNT_PERM = "SUB_ACCOUNT_PERM";

    /** 权限变更留痕类型：角色定义。 */
    public static final String CHANGE_ROLE_DEF = "ROLE_DEF";

    /** 权限变更留痕类型：岗位互斥配置。 */
    public static final String CHANGE_POSITION_CONFLICT = "POSITION_CONFLICT";

    /** 登录日志事件：锁定（{@code ck_login_log_event_type}）。 */
    public static final String EVENT_LOCKED = "LOCKED";

    /** 登录日志事件：解锁（{@code ck_login_log_event_type}）。 */
    public static final String EVENT_UNLOCKED = "UNLOCKED";

    /** 会话作废状态（权限变更 / 停用 ⇒ 强制下线；{@code ck_login_session_status}）。 */
    public static final String SESSION_KICKED = "KICKED";

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
    private final AuthorizationDao authorizationDao;
    private final PermissionChangeLogDao permissionChangeLogDao;
    private final PositionGroupDao positionGroupDao;
    /** 运行配置（{@code CFG-23}/{@code CFG-23b} 可调项来源）；{@code null} 时一律取编译期默认值。 */
    private final Config config;

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
     * @param authorizationDao       授权 DAO（{@code FR-AUTH-10}）
     * @param permissionChangeLogDao 权限变更留痕 DAO（{@code FR-AUTH-11}/{@code FR-AUTH-18}）
     * @param positionGroupDao       岗位组 DAO（{@code FR-AUTH-6}/{@code FR-AUTH-7}）
     * @param config                 运行配置（{@code CFG-23}/{@code CFG-23b}；{@code null} 取默认值）
     */
    public AuthService(TxTemplate tx, UserAccountDao userAccountDao, RoleDao roleDao,
                       UserRoleDao userRoleDao, PositionConflictDao positionConflictDao,
                       LoginSessionDao loginSessionDao,
                       LoginLogDao loginLogDao, PasswordHistoryDao passwordHistoryDao,
                       UserSubAccountPermDao userSubAccountPermDao,
                       UnauthorizedAttemptDao unauthorizedAttemptDao, SessionStore sessionStore,
                       AuthorizationDao authorizationDao,
                       PermissionChangeLogDao permissionChangeLogDao,
                       PositionGroupDao positionGroupDao,
                       Config config) {
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
        this.authorizationDao = authorizationDao;
        this.permissionChangeLogDao = permissionChangeLogDao;
        this.positionGroupDao = positionGroupDao;
        this.config = config;
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
                            + "（CFG-23 失败 " + loginFailLimit() + " 次锁 "
                            + loginLockMinutes() + " 分钟）");
        }
        boolean wasLocked = user.lockedUntil != null;
        boolean ok = "ENABLED".equals(user.status)
                && PasswordHasher.verify(password, user.passwordHash);
        if (!ok) {
            int fails = user.failCount + 1;
            LocalDateTime lockUntil = fails >= loginFailLimit()
                    ? Clocks.now().plusMinutes(loginLockMinutes()) : null;
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
                // 锁定事件独立留痕（FR-AUTH-17 验收 3：锁定与解锁事件均可审计查询）
                writeLoginLog(user.id, username, EVENT_LOCKED, fails, clientIp, userAgent, "10008");
                throw BizException.of(ErrorCode.ACCOUNT_LOCKED,
                        "连续失败达 " + loginFailLimit() + " 次，账号锁定至 " + lockUntil
                                + "；复现=等待锁定到期或由管理员解锁");
            }
            throw authFailed();
        }

        LocalDateTime now = Clocks.now();
        LocalDateTime expire = now.plusMinutes(sessionIdleMinutes());
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
            row.idleTimeoutMinutes = sessionIdleMinutes();
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
        if (wasLocked) {
            // 解锁事件留痕（FR-AUTH-17 验收 3）
            writeLoginLog(user.id, username, EVENT_UNLOCKED, 0, clientIp, userAgent, null);
        }
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
                    "会话空闲超时（" + sessionIdleMinutes() + " 分钟）已失效；复现=重新登录"
                            + "（CFG-23b hg.auth.session_idle_minutes）");
        }
        Session renewed = new Session(session.sessionId, session.userId, session.username,
                session.permissionVersion, now.plusMinutes(sessionIdleMinutes()));
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
        PasswordHasher.assertStrength(newPassword, passwordMinLength());
        if (PasswordHasher.verify(newPassword, user.passwordHash)) {
            throw reuseRejected();
        }
        for (PasswordHistory history : passwordHistoryDao.findRecent(userId, passwordHistorySize())) {
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
            passwordHistoryDao.deleteBeyond(userId, passwordHistorySize());
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
        String oneTime = PasswordHasher.randomPassword(passwordMinLength() + 4);
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
        UserAccount target = userAccountDao.findById(userId);
        if (target == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "用户不存在：id=" + userId + "；复现=核对目标用户 ID");
        }
        // R-AUTH-10：系统管理员本身不得拥有业务角色，业务用户亦不得被授予 SYS_ADMIN（禁"一键通办"后门）
        boolean targetIsSysAdmin = hasRoleCode(userId, ROLE_SYS_ADMIN);
        if (ROLE_SYS_ADMIN.equals(role.roleCode) && hasAnyBusinessRole(userId)) {
            recordAttempt(userId, ATTEMPT_SYSADMIN_BIZ_OP,
                    "系统管理员不得持有业务角色（R-AUTH-10）", "10005");
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "系统管理员本身不得拥有业务角色：userId=" + userId
                            + "；复现=为管理岗另建业务账号（不得提供一键通办后门，R-AUTH-10）");
        }
        if (!ROLE_SYS_ADMIN.equals(role.roleCode) && "Y".equals(role.isBusinessRole)
                && targetIsSysAdmin) {
            recordAttempt(userId, ATTEMPT_SYSADMIN_BIZ_OP,
                    "系统管理员不得持有业务角色（R-AUTH-10）：目标角色 " + role.roleCode, "10005");
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "该用户为系统管理员，不得持有业务角色 " + role.roleCode
                            + "（R-AUTH-10）；复现=为业务岗另建账号");
        }
        // FR-AUTH-9 验收 1：企业负责人不得被授予交易岗角色
        if ("Y".equals(target.isEnterpriseLeader) && "Y".equals(role.hasTradingOperation)) {
            recordAttempt(userId, ATTEMPT_MANAGEMENT_TRADE_BLOCKED,
                    "企业负责人禁止被授予交易岗：角色=" + role.roleCode, "10005");
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "企业负责人不得被授予交易岗角色 " + role.roleCode
                            + "；复现=改授管理/审计岗（FR-AUTH-9 验收 1 / R-AUTH-2）");
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
            loginSessionDao.invalidateByUser(userId, SESSION_KICKED, operatorId,
                    permissionVersionOf(userId) + 1);
            return null;
        });
        sessionStore.invalidateByUser(userId);
        recordChange(CHANGE_USER_ROLE, null, userId, null, "授予角色 " + role.roleCode,
                operatorId, reviewerId);
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

    // ------------------------------------------------------------ 用户管理（FR-AUTH-1~5）

    /**
     * 新增用户（{@code FR-AUTH-1}）。
     *
     * <p>用户名与自然人键全系统唯一（{@code R-AUTH-2}「一个自然人一个账号」）；初始口令<b>加盐哈希</b>存储
     * （{@code NFR-SEC-2}），{@code must_change_password='Y'}；<b>默认不授予任何子账户数据权限</b>（安全默认）。
     *
     * @param username           登录名
     * @param realName           姓名
     * @param naturalPersonKey   自然人唯一键
     * @param department         所属部门
     * @param isEnterpriseLeader 是否企业负责人（{@code 'Y'/'N'}）
     * @param initialPassword    初始明文口令（仅内存）
     * @param operatorId         操作人
     * @return 新用户 ID
     */
    public long createUser(String username, String realName, String naturalPersonKey,
                           String department, String isEnterpriseLeader, String initialPassword,
                           long operatorId) {
        requireNonBlank(username, "username");
        requireNonBlank(realName, "realName");
        requireNonBlank(naturalPersonKey, "natural_person_key");
        if (userAccountDao.findByUsername(username) != null) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "用户名已存在：" + username + "；复现=改用未占用的登录名（FR-AUTH-1 验收 2）");
        }
        if (userAccountDao.findByNaturalPersonKey(naturalPersonKey) != null) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "该自然人已存在账号：natural_person_key=" + naturalPersonKey
                            + "；复现=合并重复账号（R-AUTH-2「一个自然人一个账号」）");
        }
        PasswordHasher.assertStrength(initialPassword, passwordMinLength());
        UserAccount user = new UserAccount();
        user.username = username;
        user.realName = realName;
        user.naturalPersonKey = naturalPersonKey;
        user.department = department;
        user.isEnterpriseLeader = "Y".equals(isEnterpriseLeader) ? "Y" : "N";
        user.status = "ENABLED";
        user.mustChangePassword = "Y";
        user.passwordHash = PasswordHasher.hash(initialPassword);
        user.failCount = 0;
        userAccountDao.insert(user, operatorId);
        // 安全默认：不插入任何 t_user_sub_account_perm 行（FR-AUTH-1 验收 3 / R-AUTH-5）
        recordChange(CHANGE_USER_ATTR, user.id, user.id, null, "创建用户 " + username, operatorId, null);
        return user.id;
    }

    /**
     * 修改用户（{@code FR-AUTH-2}）。用户名<b>不可修改</b>；企业负责人标记变更须双人复核（{@code R-AUTH-2}）。
     *
     * @param userId             目标用户
     * @param realName           姓名
     * @param department         部门
     * @param isEnterpriseLeader 企业负责人标记
     * @param operatorId         操作人
     * @param reviewerId         复核人（标记变更时必填且 != 操作人）
     */
    public void updateUser(long userId, String realName, String department,
                           String isEnterpriseLeader, long operatorId, Long reviewerId) {
        UserAccount user = userAccountDao.findById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "用户不存在：id=" + userId + "；复现=核对目标用户 ID");
        }
        String leader = "Y".equals(isEnterpriseLeader) ? "Y" : "N";
        boolean leaderChanged = !leader.equals(user.isEnterpriseLeader);
        if (leaderChanged && (reviewerId == null || reviewerId == operatorId)) {
            recordAttempt(userId, ATTEMPT_DUAL_REVIEW_SELF,
                    "企业负责人标记变更须双人复核（R-AUTH-2）", "10005");
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "企业负责人标记变更须双人复核：reviewer_id 必填且 != operator_id；复现=换复核人"
                            + "（R-AUTH-2 / FR-AUTH-2 验收 3）");
        }
        String before = user.isEnterpriseLeader;
        user.realName = realName;
        user.department = department;
        user.isEnterpriseLeader = leader;
        if (leaderChanged) {
            user.leaderFlagReviewerId = reviewerId;
        }
        if (userAccountDao.updateById(user, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "用户更新失败（并发版本冲突或行不存在）：userId=" + userId + "；复现=重新读取后重试");
        }
        if (leaderChanged) {
            recordChange(CHANGE_USER_ATTR, userId, userId, null,
                    "企业负责人标记 " + before + "→" + leader, operatorId, reviewerId);
        }
    }

    /**
     * 停用用户（{@code FR-AUTH-3}）：须填停用原因；<b>立即失效其登录会话</b>（{@code R-AUTH-9}）。
     *
     * @param userId     目标用户
     * @param reason     停用原因（必填）
     * @param operatorId 操作人
     */
    public void disableUser(long userId, String reason, long operatorId) {
        requireNonBlank(reason, "disabled_reason");
        UserAccount user = userAccountDao.findById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "用户不存在：id=" + userId + "；复现=核对目标用户 ID");
        }
        user.status = "DISABLED";
        user.disabledReason = reason;
        if (userAccountDao.updateById(user, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "用户停用失败（并发版本冲突）：userId=" + userId + "；复现=重新读取后重试");
        }
        invalidateSessionsImmediately(user.id);
        writeLoginLog(user.id, user.username, "KICKED", null, null, null, "DISABLED");
        recordChange(CHANGE_USER_ATTR, userId, userId, null, "停用用户：" + reason, operatorId, null);
    }

    /**
     * 启用用户（{@code FR-AUTH-4}）：须<b>重新确认角色</b>（未配置角色时提示），启用后<b>强制重置口令</b>。
     *
     * @param userId     目标用户
     * @param operatorId 操作人
     * @param reviewerId 复核人（必填且 != 操作人）
     * @return 一次性口令（仅返回一次；{@code NFR-SEC-2}）
     */
    public String enableUser(long userId, long operatorId, Long reviewerId) {
        if (reviewerId == null || reviewerId == operatorId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "启用用户须双人复核：reviewer_id 必填且 != operator_id；复现=换复核人（R-AUTH-2）");
        }
        UserAccount user = userAccountDao.findById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "用户不存在：id=" + userId + "；复现=核对目标用户 ID");
        }
        if (userRoleDao.findByUser(userId).isEmpty()) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "未配置角色即启用：userId=" + userId + "；复现=先重新分配角色"
                            + "（FR-AUTH-4 验收 4：不得恢复原授权）");
        }
        String oneTime = PasswordHasher.randomPassword(passwordMinLength() + 4);
        user.status = "ENABLED";
        user.mustChangePassword = "Y";
        user.passwordHash = PasswordHasher.hash(oneTime);
        user.disabledReason = null;
        if (userAccountDao.updateById(user, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "用户启用失败（并发版本冲突）：userId=" + userId + "；复现=重新读取后重试");
        }
        recordChange(CHANGE_USER_ATTR, userId, userId, null, "启用用户（须强制改口令）",
                operatorId, reviewerId);
        return oneTime;
    }

    /**
     * 查询用户列表（{@code FR-AUTH-5}；默认按创建时间倒序；<b>不含任何口令字段</b>）。
     *
     * @param query      分页（默认排序字段 {@code created_time}，缺省倒序）
     * @param status     状态筛选（{@code ENABLED}/{@code DISABLED}；可为 {@code null}）
     * @param leaderFlag 企业负责人标记筛选（{@code Y}/{@code N}；可为 {@code null}）
     * @return 分页用户
     */
    public PageResult<UserAccount> listUsers(PageQuery query, String status, String leaderFlag) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            where.append("status = ?");
            args.add(status);
        }
        if (leaderFlag != null && !leaderFlag.isEmpty()) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("is_enterprise_leader = ?");
            args.add(leaderFlag);
        }
        return userAccountDao.findPage(query, where.length() == 0 ? null : where.toString(), args);
    }

    // ------------------------------------------------------------ 角色与岗位分离（FR-AUTH-6/7）

    /**
     * 查询角色（{@code FR-AUTH-6}）。
     *
     * @param query 分页（默认排序字段 {@code role_code}）
     * @return 分页角色
     */
    public PageResult<Role> listRoles(PageQuery query) {
        return roleDao.findPage(query, null, null);
    }

    /**
     * 定义角色（{@code FR-AUTH-6}）：须选岗位组；系统管理员角色<b>不得</b>含交易下单能力（{@code R-AUTH-10}）。
     *
     * @param roleCode             角色编码（唯一）
     * @param roleName             角色名称
     * @param positionGroupId      岗位组
     * @param hasTradingOperation  是否含交易下单能力（{@code 'Y'/'N'}）
     * @param operatorId           操作人
     * @param reviewerId           复核人（必填且 != 操作人）
     * @return 新角色 ID
     */
    public long defineRole(String roleCode, String roleName, int positionGroupId,
                           String hasTradingOperation, long operatorId, Long reviewerId) {
        requireNonBlank(roleCode, "role_code");
        requireNonBlank(roleName, "role_name");
        if (positionGroupId <= 0 || positionGroupDao.findById(positionGroupId) == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "角色须选择有效岗位组：positionGroupId=" + positionGroupId
                            + "；复现=先定义岗位组（FR-AUTH-6 验收 3）");
        }
        boolean sysAdmin = ROLE_SYS_ADMIN.equals(roleCode);
        boolean trading = "Y".equals(hasTradingOperation);
        if (sysAdmin && trading) {
            throw BizException.of(ErrorCode.FUNCTION_PERMISSION_DENIED,
                    "系统管理员角色不得含交易下单能力（R-AUTH-10「不得一键通办」）；"
                            + "复现=去除交易权限（FR-AUTH-6 验收 2）");
        }
        if (roleDao.findByCode(roleCode) != null) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "角色编码已存在：" + roleCode + "；复现=改用未占用的编码");
        }
        if (reviewerId == null || reviewerId == operatorId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "角色变更须双人复核：reviewer_id 必填且 != operator_id；复现=换复核人（FR-AUTH-6 验收 4）");
        }
        Role role = new Role();
        role.roleCode = roleCode;
        role.roleName = roleName;
        role.positionGroupId = positionGroupId;
        role.isPreset = "N";
        role.isBusinessRole = sysAdmin ? "N" : "Y";
        role.hasTradingOperation = trading ? "Y" : "N";
        role.status = "ENABLED";
        roleDao.insert(role, operatorId);
        recordChange(CHANGE_ROLE_DEF, role.id, null, null, "定义角色 " + roleCode + "（岗位组 "
                + positionGroupId + "）", operatorId, reviewerId);
        return role.id;
    }

    /** 查询启用的岗位组（{@code FR-AUTH-7} 的岗位组清单）。 */
    public List<PositionGroup> listPositionGroups() {
        return positionGroupDao.findEnabled();
    }

    /**
     * 查询岗位互斥矩阵（{@code FR-AUTH-7}）。
     *
     * @param query 分页（默认排序字段 {@code group_a_id}）
     * @return 分页互斥关系
     */
    public PageResult<PositionConflict> listPositionConflicts(PageQuery query) {
        return positionConflictDao.findPage(query, null, null);
    }

    /**
     * 配置岗位互斥（{@code FR-AUTH-7}）：矩阵按单向存储（{@code a < b}）；变更须双人复核（{@code R-AUTH-3}）。
     *
     * @param groupAId   岗位组 A
     * @param groupBId   岗位组 B
     * @param reason     互斥依据
     * @param operatorId 操作人
     * @param reviewerId 复核人（必填且 != 操作人）
     */
    public void definePositionConflict(int groupAId, int groupBId, String reason,
                                       long operatorId, Long reviewerId) {
        requireNonBlank(reason, "conflict_reason");
        if (groupAId == groupBId) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "互斥岗位组不得相同：groupId=" + groupAId + "；复现=选择两个不同岗位组");
        }
        PositionGroup ga = positionGroupDao.findById(groupAId);
        PositionGroup gb = positionGroupDao.findById(groupBId);
        if (ga == null || gb == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "岗位组不存在：A=" + groupAId + " B=" + groupBId + "；复现=先定义岗位组");
        }
        if (reviewerId == null || reviewerId == operatorId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "互斥配置变更须双人复核：reviewer_id 必填且 != operator_id（FR-AUTH-7 验收 3）");
        }
        int a = Math.min(groupAId, groupBId);
        int b = Math.max(groupAId, groupBId);
        PositionConflict conflict = new PositionConflict();
        conflict.groupAId = a;
        conflict.groupBId = b;
        conflict.conflictReason = reason;
        conflict.isMandatory = "Y";
        conflict.reviewerId = reviewerId;
        positionConflictDao.insert(conflict, operatorId);
        recordChange(CHANGE_POSITION_CONFLICT, conflict.id, null, null,
                "新增岗位互斥组 " + a + " ↔ " + b, operatorId, reviewerId);
    }

    // ------------------------------------------------------------ 授权与子账户数据权限（FR-AUTH-10/12/13）

    /**
     * 查询授权明细（{@code FR-AUTH-10}）。
     *
     * @param query        分页（默认排序字段 {@code id}）
     * @param targetUserId 按被授权人筛选（可为 {@code null}）
     * @return 分页授权
     */
    public PageResult<Authorization> listAuthorizations(PageQuery query, Long targetUserId) {
        String where = targetUserId == null ? null : "user_id = ?";
        List<Object> args = targetUserId == null ? new ArrayList<>() : args(targetUserId);
        return authorizationDao.findPage(query, where, args);
    }

    /**
     * 配置人员授权（{@code FR-AUTH-10}）：<b>人员 + 品种 + 额度三者齐备</b>（{@code R-AUTH-4}），
     * 额度须 {@code > 0}（缺额度即拒，{@code FR-AUTH-10} 验收 2）；变更须双人复核。
     *
     * @param userId      被授权人
     * @param subAccountId 子账户（行级归属，恒 {@code > 0}）
     * @param productId   品种
     * @param quota       额度
     * @param effectiveFrom 生效起始日
     * @param effectiveTo   生效截止日（{@code null} = 长期）
     * @param operatorId  操作人
     * @param reviewerId  复核人（必填且 != 操作人）
     * @return 新授权 ID
     */
    public long grantAuthorization(long userId, int subAccountId, long productId, BigDecimal quota,
                                   LocalDate effectiveFrom, LocalDate effectiveTo,
                                   long operatorId, Long reviewerId) {
        if (subAccountId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "授权须明确子账户（人员名单 + 品种 + 额度三者齐备）：subAccountId=" + subAccountId
                            + "；复现=选择行级子账户（R-AUTH-4）");
        }
        if (productId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "授权须明确品种：productId=" + productId + "；复现=选择授权品种（R-AUTH-4）");
        }
        if (quota == null || quota.signum() <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "授权须明确额度且 > 0：quota=" + quota + "；复现=补齐额度（FR-AUTH-10 验收 2）");
        }
        if (effectiveFrom == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "授权须给出生效起始日；复现=补 effectiveFrom");
        }
        if (reviewerId == null || reviewerId == operatorId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "授权变更须双人复核：reviewer_id 必填且 != operator_id（R-AUTH-4 / FR-AUTH-10 验收 3）");
        }
        UserAccount target = userAccountDao.findById(userId);
        if (target == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "被授权用户不存在：id=" + userId + "；复现=核对人员名单");
        }
        Authorization auth = new Authorization();
        auth.userId = userId;
        auth.subAccountId = subAccountId;
        auth.productId = productId;
        auth.quotaLimit = quota;
        auth.effectiveFrom = effectiveFrom;
        auth.effectiveTo = effectiveTo;
        auth.status = "EFFECTIVE";
        auth.applicantId = operatorId;
        auth.reviewerId = reviewerId;
        auth.dispatchStatus = "PENDING";
        authorizationDao.insert(auth, operatorId);
        recordChange(CHANGE_AUTHORIZATION, auth.id, userId, subAccountId,
                "授权 用户=" + userId + " 子账户=" + subAccountId + " 品种=" + productId
                        + " 额度=" + quota.toPlainString(), operatorId, reviewerId);
        return auth.id;
    }

    /**
     * 查询某用户的子账户数据权限（{@code FR-AUTH-12}/{@code FR-AUTH-13}）。
     *
     * @param userId 用户
     * @return 授权行（无授权 ⇒ 空集 = 看不到任何子账户，安全默认）
     */
    public List<UserSubAccountPerm> listSubAccountPerms(long userId) {
        return userSubAccountPermDao.findByUser(userId);
    }

    /**
     * 配置子账户数据权限（{@code FR-AUTH-12}）/ 授予跨子账户查看（{@code FR-AUTH-13}）。
     *
     * <p>跨子账户只读（{@code CROSS_READONLY}）<b>仅管理层与审计/合规岗可被授予</b>（{@code R-AUTH-5}）；
     * 变更须双人复核并留痕。
     *
     * @param userId       目标用户
     * @param subAccountId 子账户（{@code CROSS_READONLY} 时可传哨兵 {@code 0} 表示全部）
     * @param permType     {@code LIMITED} / {@code CROSS_READONLY}
     * @param effectiveFrom 生效起始日
     * @param effectiveTo   生效截止日（{@code null} = 长期）
     * @param operatorId   操作人
     * @param reviewerId   复核人（必填且 != 操作人）
     */
    public void assignSubAccountPerm(long userId, int subAccountId, String permType,
                                     LocalDate effectiveFrom, LocalDate effectiveTo,
                                     long operatorId, Long reviewerId) {
        if (!PERM_LIMITED.equals(permType) && !PERM_CROSS_READONLY.equals(permType)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "子账户权限类型须为 " + PERM_LIMITED + " 或 " + PERM_CROSS_READONLY
                            + "：实际=" + permType + "；复现=按 FR-AUTH-12/13 选择权限类型");
        }
        if (effectiveFrom == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "子账户权限须给出生效起始日；复现=补 effectiveFrom");
        }
        if (reviewerId == null || reviewerId == operatorId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "子账户权限变更须双人复核：reviewer_id 必填且 != operator_id"
                            + "（R-AUTH-5 / FR-AUTH-13 验收 4）");
        }
        UserAccount target = userAccountDao.findById(userId);
        if (target == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "目标用户不存在：id=" + userId + "；复现=核对人员名单");
        }
        if (PERM_CROSS_READONLY.equals(permType)
                && !hasRoleCode(userId, ROLE_APPROVER) && !hasRoleCode(userId, ROLE_AUDITOR)) {
            recordAttempt(userId, ATTEMPT_SUB_ACCOUNT_DENIED,
                    "跨子账户查看权限仅管理层与审计/合规岗可被授予（FR-AUTH-13 验收 2）", "10005");
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "跨子账户查看权限仅管理层（APPROVER）与审计/合规岗（AUDITOR）可被授予：userId="
                            + userId + "；复现=改授 LIMITED 或调整角色（FR-AUTH-13 验收 2）");
        }
        UserSubAccountPerm perm = new UserSubAccountPerm();
        perm.userId = userId;
        perm.subAccountId = subAccountId;
        perm.permType = permType;
        perm.grantedBy = operatorId;
        perm.reviewerId = reviewerId;
        perm.effectiveFrom = effectiveFrom;
        perm.effectiveTo = effectiveTo;
        userSubAccountPermDao.insert(perm, operatorId);
        recordChange(CHANGE_SUB_ACCOUNT_PERM, perm.id, userId, subAccountId,
                "子账户权限 " + permType + "（子账户 " + subAccountId + "）", operatorId, reviewerId);
    }

    // ------------------------------------------------------------ 留痕查询（FR-AUTH-11/18）

    /**
     * 查询权限变更留痕（{@code FR-AUTH-11}/{@code FR-AUTH-18}）：含前后值与复核人；<b>只增不改不删</b>。
     *
     * @param query        分页（默认排序字段 {@code change_time}，缺省倒序）
     * @param targetUserId 按受影响用户筛选（可为 {@code null}）
     * @return 分页留痕
     */
    public PageResult<PermissionChangeLog> listPermissionChangeLogs(PageQuery query,
                                                                    Long targetUserId) {
        String where = targetUserId == null ? null : "target_user_id = ?";
        List<Object> args = targetUserId == null ? new ArrayList<>() : args(targetUserId);
        return permissionChangeLogDao.findPage(query, where, args);
    }

    /**
     * 查询越权尝试记录（{@code FR-AUTH-18}）：含操作人、时间、尝试的操作、拒绝原因；只增不改不删。
     *
     * @param query      分页（默认排序字段 {@code attempt_time}，缺省倒序）
     * @param operatorId 按操作人筛选（可为 {@code null}）
     * @return 分页越权尝试
     */
    public PageResult<UnauthorizedAttempt> listUnauthorizedAttempts(PageQuery query,
                                                                    Long operatorId) {
        String where = operatorId == null ? null : "operator_id = ?";
        List<Object> args = operatorId == null ? new ArrayList<>() : args(operatorId);
        return unauthorizedAttemptDao.findPage(query, where, args);
    }

    // ------------------------------------------------------------ 会话管理（FR-AUTH-15④）

    /**
     * 管理员强制下线（{@code FR-AUTH-15} 验收 4）：立即失效该用户全部会话并留痕。
     *
     * @param userId     目标用户
     * @param operatorId 操作人
     * @return 实际失效的库内会话数
     */
    public int forceLogout(long userId, long operatorId) {
        UserAccount user = userAccountDao.findById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "用户不存在：id=" + userId + "；复现=核对目标用户 ID");
        }
        int n = tx.inTransaction(connection ->
                loginSessionDao.invalidateByUser(userId, SESSION_KICKED, operatorId,
                        permissionVersionOf(userId) + 1));
        sessionStore.invalidateByUser(userId);
        writeLoginLog(userId, user.username, "KICKED", null, null, null, "FORCED");
        recordChange(CHANGE_USER_ATTR, userId, userId, null, "管理员强制下线", operatorId, null);
        return n;
    }

    // ------------------------------------------------------------ 内部

    private int loginFailLimit() {
        return config == null ? LOGIN_FAIL_LIMIT
                : config.getInt(Config.KEY_LOGIN_FAIL_LIMIT, LOGIN_FAIL_LIMIT);
    }

    private int loginLockMinutes() {
        return config == null ? LOGIN_LOCK_MINUTES
                : config.getInt(Config.KEY_LOGIN_LOCK_MINUTES, LOGIN_LOCK_MINUTES);
    }

    private int sessionIdleMinutes() {
        return config == null ? SESSION_IDLE_MINUTES
                : config.getInt(Config.KEY_SESSION_IDLE_MINUTES, SESSION_IDLE_MINUTES);
    }

    private int passwordMinLength() {
        return config == null ? PASSWORD_MIN_LENGTH
                : config.getInt(Config.KEY_PASSWORD_MIN_LENGTH, PASSWORD_MIN_LENGTH);
    }

    private int passwordHistorySize() {
        return config == null ? PASSWORD_HISTORY_SIZE
                : config.getInt(Config.KEY_PASSWORD_HISTORY_SIZE, PASSWORD_HISTORY_SIZE);
    }

    /**
     * 取某用户的角色编码集合（{@code FR-AUTH-5} 验收 2：列表展示角色）。
     *
     * @param userId 用户
     * @return 角色编码列表（无角色 ⇒ 空集）
     */
    public List<String> roleCodesOf(long userId) {
        List<String> codes = new ArrayList<>();
        for (UserRole userRole : userRoleDao.findByUser(userId)) {
            Role role = roleDao.findById(userRole.roleId);
            if (role != null) {
                codes.add(role.roleCode);
            }
        }
        return codes;
    }

    private boolean hasRoleCode(long userId, String roleCode) {
        for (UserRole userRole : userRoleDao.findByUser(userId)) {
            Role role = roleDao.findById(userRole.roleId);
            if (role != null && roleCode.equals(role.roleCode)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyBusinessRole(long userId) {
        for (UserRole userRole : userRoleDao.findByUser(userId)) {
            Role role = roleDao.findById(userRole.roleId);
            if (role != null && "Y".equals(role.isBusinessRole)) {
                return true;
            }
        }
        return false;
    }

    private void invalidateSessionsImmediately(long userId) {
        tx.inTransaction(connection -> {
            loginSessionDao.invalidateByUser(userId, SESSION_KICKED, null, permissionVersionOf(userId) + 1);
            return null;
        });
        sessionStore.invalidateByUser(userId);
    }

    private void recordChange(String changeTarget, Long targetId, Long targetUserId,
                              Integer subAccountId, String afterValue, long operatorId,
                              Long reviewerId) {
        PermissionChangeLog log = new PermissionChangeLog();
        log.changeTarget = changeTarget;
        log.targetId = targetId;
        log.targetUserId = targetUserId;
        log.subAccountId = subAccountId;
        log.afterValue = afterValue;
        log.operatorId = operatorId;
        log.reviewerId = reviewerId;
        log.rejected = "N";
        log.changeTime = Clocks.now();
        log.dispatchStatus = "NOT_REQUIRED";
        tx.inTransaction(connection -> {
            permissionChangeLogDao.insert(log, operatorId);
            return null;
        });
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "字段必填：" + field + "；复现=补齐该字段（不得为空）");
        }
    }

    /** 可变参数转 {@code List<Object>}（供 DAO 条件参数）。 */
    private static List<Object> args(Object... values) {
        return new ArrayList<>(java.util.Arrays.asList(values));
    }

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

    private BizException authFailed() {
        // 统一文案：不区分"用户不存在"与"口令错误"（防账号枚举，§4.3.1 10007 备注）
        return BizException.of(ErrorCode.AUTH_FAILED,
                "用户名或口令不正确；复现=核对登录名与口令（连续失败 " + loginFailLimit()
                        + " 次将锁定 " + loginLockMinutes() + " 分钟）");
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
