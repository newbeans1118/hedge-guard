package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SettleCheckSignatureDao;
import com.hedgeguard.dao.SettleCheckTaskDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.UnauthorizedAttemptDao;
import com.hedgeguard.dao.entity.SettleCheckSignature;
import com.hedgeguard.dao.entity.SettleCheckTask;
import com.hedgeguard.dao.entity.UnauthorizedAttempt;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * 核对任务与电子签署服务（{@code ARCHITECTURE.md} §6.14；{@code T-048}；
 * {@code FR-SETTLE-13/14/17}、{@code R-SETTLE-11}、{@code CFG-14}、{@code DC-022}）。
 *
 * <p><b>为何是 {@code SettleService} 的伴生类（最小侵入，据实披露）</b>：本任务产出面写为
 * 「{@code java/service/SettleService.java}（核对与签署部分）」。核对/签署与结算主链依赖的数据基座
 * 不同（前者只需 {@code t_check_task}/{@code t_check_signature} 两表 + 角色/配置取数，后者持有 16 个
 * 结算 DAO）；若就地扩展 {@code SettleService}，须改动其 <b>16 参构造签名</b>并连锁改动
 * {@code Bootstrap} 与两处既有测试。为把改动面收敛到本任务范围，本类以<b>独立服务</b>承载该功能，
 * {@code SettleService} 本体 0 改动；两者在 {@code Bootstrap} 同点装配、由各自 {@code *Routes} 注册。
 *
 * <p><b>三条结构性保证</b>：
 * <ol>
 *   <li><b>周期自动生成 + 角色指派</b>（{@code R-SETTLE-11}）：月度应签 = 配置 {@code settle.sign.roles}
 *       （默认 {@code RISK,TRADE,FINANCE}）；季度 = {@code RISK,TRADE,MANAGE}；年度 = {@code AUDITOR}。
 *       同一（类型, 期间, 子账户）唯一（{@code uk_check_task_period}），重复生成幂等返回既有任务。</li>
 *   <li><b>不可代签（角色级判据，写死，{@code DC-022}）</b>：签署时须同时满足 ① 用户确实持有「应签角色」
 *       对应的实际岗位角色；② 所选 {@code sign_role} ∈ 任务 {@code required_sign_roles}；③ 所选角色与
 *       实际持有角色一致（经 {@link #SIGN_ROLE_ALIAS} 规范映射）。任一不满足 ⇒ 拒绝 + 记
 *       {@code t_unauthorized_attempt} + 落 {@code proxy_rejected='Y'} 留痕；<b>多角色账号不猜测、不默认</b>
 *       ——{@code sign_role} 必须由签署动作显式提交。</li>
 *   <li><b>快照绑定</b>（{@code FR-SETTLE-17} 验收 4）：签名对象为核对时点的快照哈希；签署时若当前快照
 *       与任务基线不一致 ⇒ 任务回到 {@code PENDING_SIGN}、以新快照为新基线并要求重签（旧签署不再计入）。</li>
 * </ol>
 *
 * <p><b>角色词表映射（据实披露：冻结文档与库种子存在词表落差）</b>：{@code t_check_signature.sign_role}
 * 的 DDL 取值域为 {@code RISK/TRADE/FINANCE/MANAGE/AUDITOR}（{@code m2_settle.sql}），而 {@code t_role}
 * 预置业务角色编码为 {@code FIN_RISK/TRADER/SETTLER/CASHIER/APPROVER/AUDITOR}。二者以
 * {@link #SIGN_ROLE_ALIAS} 关联（依据 {@code t_report_period_config} 的
 * {@code MONTHLY_CHECK=FIN_RISK,TRADER,SETTLER} / {@code QUARTERLY_REPORT=FIN_RISK,TRADER,APPROVER}
 * / {@code ANNUAL_REPORT=AUDITOR} 与 {@code AUTH.md:40} 应签角色说明）。{@code signer_role_code} 落
 * <b>实际岗位角色编码</b>（列语义），{@code sign_role} 落<b>应签角色域码</b>（DDL 取值域）。
 *
 * <p><b>快照计算接缝（据实披露）</b>：{@code ARCHITECTURE.md} §6.14 规定 {@code snapshot_hash} 为对六段
 * 核对对象的 SHA-256。六段跨 {@code SETTLE/ACCT/SPOT} 三模块，且部分数据源在 M2 尚未接线（{@code T-172}）。
 * 故本类以 {@link SnapshotProvider} 接缝承接「取当前快照哈希」；未装配时以请求携带的
 * {@code snapshotHash} 为准（快照变更检测仍可判定）。<b>不</b>在本类内臆算缺失分量。
 *
 * <p>分层纪律（{@code §3.8}）：不出现 SQL；不依赖 HTTP 类型；只经 DAO 接口访问数据。
 */
public final class SettleCheckService {

    private static final Logger LOG = Logger.getLogger(SettleCheckService.class.getName());

    /** 核对类型：月度三方核对。 */
    public static final String CHECK_MONTHLY = "MONTHLY";
    /** 核对类型：季度报告。 */
    public static final String CHECK_QUARTERLY = "QUARTERLY";
    /** 核对类型：年度审计覆盖。 */
    public static final String CHECK_ANNUAL = "ANNUAL";

    /** 任务状态：待签署。 */
    public static final String STATUS_PENDING_SIGN = "PENDING_SIGN";
    /** 任务状态：部分签署。 */
    public static final String STATUS_PARTIAL_SIGNED = "PARTIAL_SIGNED";
    /** 任务状态：已完成。 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 任务状态：已逾期。 */
    public static final String STATUS_OVERDUE = "OVERDUE";

    /** 签署角色：风控岗。 */
    public static final String ROLE_RISK = "RISK";
    /** 签署角色：交易岗。 */
    public static final String ROLE_TRADE = "TRADE";
    /** 签署角色：财务岗。 */
    public static final String ROLE_FINANCE = "FINANCE";
    /** 签署角色：管理层（签收）。 */
    public static final String ROLE_MANAGE = "MANAGE";
    /** 签署角色：审计岗。 */
    public static final String ROLE_AUDITOR = "AUDITOR";

    /** 签署结果：已签署。 */
    public static final String SIGN_SIGNED = "SIGNED";
    /** 签署结果：异议/被拒。 */
    public static final String SIGN_REJECTED = "REJECTED";

    /** 配置键：应签角色清单（月度）。 */
    public static final String KEY_SIGN_ROLES = "settle.sign.roles";
    /** 配置键：签署截止天数。 */
    public static final String KEY_DEADLINE_DAYS = "settle.sign.deadline_days";

    /** 截止天数缺省（{@code CFG-14}）。 */
    public static final int DEFAULT_DEADLINE_DAYS = 30;
    /** 截止天数取值下界（{@code CFG-14}：1–120）。 */
    public static final int MIN_DEADLINE_DAYS = 1;
    /** 截止天数取值上界（{@code CFG-14}：1–120）。 */
    public static final int MAX_DEADLINE_DAYS = 120;

    /** 越权尝试类型（{@code t_unauthorized_attempt.attempt_type} 取值域内的「功能权限不足」）。 */
    public static final String ATTEMPT_PERMISSION_DENIED = "PERMISSION_DENIED";

    /** 应签角色域码集合（{@code t_check_signature.sign_role} 的 DDL 取值域）。 */
    public static final Set<String> SIGN_ROLES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(ROLE_RISK, ROLE_TRADE, ROLE_FINANCE, ROLE_MANAGE,
                    ROLE_AUDITOR)));

    /** 应签角色域码 → 实际岗位角色编码（{@code t_role.role_code}）。 */
    public static final Map<String, String> SIGN_ROLE_ALIAS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(ROLE_RISK, "FIN_RISK");
        m.put(ROLE_TRADE, "TRADER");
        m.put(ROLE_FINANCE, "SETTLER");
        m.put(ROLE_MANAGE, "APPROVER");
        m.put(ROLE_AUDITOR, "AUDITOR");
        SIGN_ROLE_ALIAS = Collections.unmodifiableMap(m);
    }

    /** 月度核对项（{@code FR-SETTLE-13}）。 */
    public static final String ITEMS_MONTHLY = "持仓/资金/盈亏/额度";

    /** 期间解析错误提示。 */
    private static final String PERIOD_HINT = "期间键格式：月度 yyyy-MM / 季度 yyyy-Qn / 年度 yyyy";

    private final TxTemplate tx;
    private final SubAccountVisibility visibility;
    private final RoleLookup roles;
    private final TradingRoleChecker tradingRoleChecker;
    private final ConfigReader config;
    private final SnapshotProvider snapshotProvider;
    private final SettleCheckTaskDao taskDao;
    private final SettleCheckSignatureDao signatureDao;
    private final UnauthorizedAttemptDao attemptDao;

    /**
     * 构造。
     *
     * @param tx                  事务模板
     * @param visibility          子账户可见性（只读查询面断言；生产为 {@code AuthService}）
     * @param roles               用户角色编码取数入口（生产为 {@code AuthService::roleCodesOf}）
     * @param tradingRoleChecker  交易岗能力取数入口（管理层签署权与交易权互斥核验）
     * @param config              配置读取接缝（{@code settle.sign.roles} / {@code settle.sign.deadline_days}）
     * @param snapshotProvider    快照哈希接缝；{@code null} 时以请求携带的快照为准
     * @param taskDao             核对任务 DAO
     * @param signatureDao        签署记录 DAO
     * @param attemptDao          越权尝试 DAO（代签留痕）
     */
    public SettleCheckService(TxTemplate tx, SubAccountVisibility visibility, RoleLookup roles,
                              TradingRoleChecker tradingRoleChecker, ConfigReader config,
                              SnapshotProvider snapshotProvider, SettleCheckTaskDao taskDao,
                              SettleCheckSignatureDao signatureDao, UnauthorizedAttemptDao attemptDao) {
        this.tx = tx;
        this.visibility = visibility;
        this.roles = roles;
        this.tradingRoleChecker = tradingRoleChecker;
        this.config = config;
        this.snapshotProvider = snapshotProvider;
        this.taskDao = taskDao;
        this.signatureDao = signatureDao;
        this.attemptDao = attemptDao;
    }

    /** 用户角色编码取数入口。 */
    @FunctionalInterface
    public interface RoleLookup {
        /**
         * @param userId 用户 ID
         * @return 角色编码列表（无角色返回空列表，不得返回 {@code null}）
         */
        List<String> roleCodesOf(long userId);
    }

    /** 配置读取接缝（避免直接依赖 CONFIG 模块 DAO）。 */
    @FunctionalInterface
    public interface ConfigReader {
        /**
         * @param key 配置键
         * @return 配置值；不存在返回 {@code null}
         */
        String get(String key);
    }

    /** 核对对象快照哈希接缝。 */
    @FunctionalInterface
    public interface SnapshotProvider {
        /**
         * @param checkType    核对类型
         * @param periodKey    期间键
         * @param subAccountId 子账户（跨账户哨兵 0）
         * @return 当前快照哈希；无法计算返回 {@code null}
         */
        String snapshotHash(String checkType, String periodKey, int subAccountId);
    }

    // ================================================================ 一、生成核对任务

    /** 生成（或幂等返回既有）核对任务入参。 */
    public static final class GenerateCheckInput {
        /** 核对类型（{@code MONTHLY}/{@code QUARTERLY}/{@code ANNUAL}）。 */
        public String checkType;
        /** 期间键。 */
        public String periodKey;
        /** 子账户（缺省哨兵 0 = 跨子账户核对）。 */
        public Integer subAccountId;
        /** 核对对象快照哈希（未装配 {@link SnapshotProvider} 时必填）。 */
        public String snapshotHash;
        /** 快照内容（供审计复现）。 */
        public String snapshotJson;
        /** 季度报告内容（保值规模/双侧盈亏/有效性/违规事项）。 */
        public String contentJson;
    }

    /**
     * 生成核对任务（{@code FR-SETTLE-17} 验收 1）。
     *
     * <p>幂等：同一（{@code check_type}, {@code period_key}, {@code sub_account_id}）已存在 ⇒ 直接返回
     * 既有任务（不重复插入，{@code uk_check_task_period}）。
     *
     * @param in         入参
     * @param operatorId 操作人
     * @return 核对任务
     */
    public SettleCheckTask generateCheckTask(GenerateCheckInput in, long operatorId) {
        if (in == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求体不得为空（FR-SETTLE-17）");
        }
        String checkType = requireCheckType(in.checkType);
        String periodKey = requirePeriodKey(in.periodKey, checkType);
        int subAccountId = in.subAccountId == null ? SubAccountScope.ALL_ACCOUNT_SENTINEL : in.subAccountId;
        if (subAccountId < 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "subAccountId 不得为负：" + subAccountId);
        }
        return tx.inTransaction(conn -> generateInTx(checkType, periodKey, subAccountId, in, operatorId));
    }

    /** 生成核对任务（调用方须已置于事务内）。 */
    private SettleCheckTask generateInTx(String checkType, String periodKey, int subAccountId,
                                         GenerateCheckInput in, long operatorId) {
        SettleCheckTask existing = taskDao.findByPeriod(checkType, periodKey, subAccountId);
        if (existing != null) {
            return existing;
        }
        String snapshotHash = resolveSnapshotHash(checkType, periodKey, subAccountId, in.snapshotHash);
        SettleCheckTask task = new SettleCheckTask();
        task.checkType = checkType;
        task.periodKey = periodKey;
        task.subAccountId = subAccountId;
        task.requiredSignRoles = requiredRolesFor(checkType);
        task.signedRoles = "";
        task.status = STATUS_PENDING_SIGN;
        task.snapshotHash = snapshotHash;
        task.snapshotJson = in.snapshotJson;
        task.checkItems = CHECK_MONTHLY.equals(checkType) ? ITEMS_MONTHLY : checkType + " 核对";
        task.contentJson = CHECK_QUARTERLY.equals(checkType) ? in.contentJson : null;
        task.deadlineDate = deadlineDate(checkType, periodKey);
        task.overdueAlerted = "N";
        task.generatedTime = Clocks.now();
        taskDao.insert(task, operatorId);
        return task;
    }

    // ================================================================ 二、电子签署

    /** 签署入参（{@code taskId} 为空时按 {@code checkType}+{@code periodKey} 定位并在缺失时生成）。 */
    public static final class SignCheckInput {
        /** 任务主键（可空）。 */
        public Long taskId;
        /** 核对类型（{@code taskId} 为空时用于定位/生成）。 */
        public String checkType;
        /** 期间键（{@code taskId} 为空时用于定位/生成）。 */
        public String periodKey;
        /** 子账户（缺省哨兵 0）。 */
        public Integer subAccountId;
        /** 所选应签角色（{@code sign_role} 域码；<b>必须显式提交，不猜测不默认</b>）。 */
        public String signRole;
        /** 签署意见/异议说明。 */
        public String signOpinion;
        /** 签署时的当前快照哈希（未装配 {@link SnapshotProvider} 时用于变更检测）。 */
        public String snapshotHash;
        /** 生成时快照内容（可空）。 */
        public String snapshotJson;
        /** 生成时季度报告内容（可空）。 */
        public String contentJson;
    }

    /** 签署结果。 */
    public static final class SignResult {
        /** 任务主键。 */
        public long taskId;
        /** 任务状态。 */
        public String status;
        /** 应签角色清单。 */
        public String requiredSignRoles;
        /** 已签角色清单（仅计入与当前快照一致的签署）。 */
        public String signedRoles;
        /** 本次签署角色。 */
        public String signRole;
        /** 签署人。 */
        public long signerId;
    }

    /**
     * 电子签署（{@code FR-SETTLE-17} 验收 2/3；{@code DC-022} 角色级判据）。
     *
     * @param in         入参
     * @param userId     签署人
     * @param clientIp   客户端 IP（留痕）
     * @return 签署结果
     */
    public SignResult sign(SignCheckInput in, long userId, String clientIp) {
        if (in == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求体不得为空（FR-SETTLE-17）");
        }
        if (in.taskId == null && (in.periodKey == null || in.periodKey.trim().isEmpty())) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "taskId 与 periodKey 不可同时为空（FR-SETTLE-17）");
        }
        String signRole = requireText(in.signRole, "signRole（应签角色，须显式提交，不猜测不默认，DC-022）");
        if (!SIGN_ROLES.contains(signRole)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "signRole 取值非法：" + signRole + "；合法域=" + SIGN_ROLES + "（" + PERIOD_HINT + "）");
        }
        // 事务边界三段式（TE-T048-001/002）：定位/生成任务、前置校验（越权留痕 / 快照变更重置）
        // 各自以独立事务提交，最后才开启签署写入事务——避免「写后抛异常」被同一事务回滚吞掉。
        SettleCheckTask task = tx.inTransaction(conn -> resolveTaskForSign(in, userId));
        String current = precheckSign(task, in, signRole, userId, clientIp);
        return tx.inTransaction(conn -> signInTx(task, in, signRole, userId, clientIp, current));
    }

    /** 定位待签任务；{@code taskId} 为空时按（类型, 期间, 子账户）定位，缺失则生成（POST = 发起并签署）。 */
    private SettleCheckTask resolveTaskForSign(SignCheckInput in, long userId) {
        if (in.taskId != null) {
            SettleCheckTask task = taskDao.findById(in.taskId);
            if (task == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "核对任务不存在：id=" + in.taskId);
            }
            return task;
        }
        String checkType = requireCheckType(in.checkType);
        String periodKey = requirePeriodKey(in.periodKey, checkType);
        int subAccountId = in.subAccountId == null ? SubAccountScope.ALL_ACCOUNT_SENTINEL : in.subAccountId;
        GenerateCheckInput gen = new GenerateCheckInput();
        gen.checkType = checkType;
        gen.periodKey = periodKey;
        gen.subAccountId = subAccountId;
        gen.snapshotHash = in.snapshotHash;
        gen.snapshotJson = in.snapshotJson;
        gen.contentJson = in.contentJson;
        return generateInTx(checkType, periodKey, subAccountId, gen, userId);
    }

    /**
     * 签署前置校验（{@code FR-SETTLE-17} 验收 2/4；{@code DC-022}）。
     *
     * <p><b>事务边界（{@code TE-T048-001/002}）</b>：本方法在<b>签署写入事务之外</b>调用——越权留痕
     * （{@link #rejectProxy}）与快照变更重置各自以<b>独立事务</b>提交后再抛异常，不随写入事务回滚。
     * 照 {@code AuthService.recordAttempt} 先例（留痕须提交，不因拒绝而丢失）。
     *
     * @return 当前快照哈希（供写入事务复用，使判定与落库同源）
     */
    private String precheckSign(SettleCheckTask task, SignCheckInput in, String signRole, long userId,
                                String clientIp) {
        if (STATUS_COMPLETED.equals(task.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "任务已完成，不可再签：" + task.periodKey + "（FR-SETTLE-17 验收 1）");
        }
        // ① 快照绑定：当前快照与任务基线不一致 ⇒ 任务回到待签署，以新快照为新基线（验收 4）
        String current = resolveCurrentSnapshot(task, in.snapshotHash);
        if (!current.equals(task.snapshotHash)) {
            // TE-T048-002：重置须独立事务提交，否则回滚致任务在该快照下永久无法重签
            tx.inTransaction(conn -> {
                task.status = STATUS_PENDING_SIGN;
                task.signedRoles = "";
                task.snapshotHash = current;
                taskDao.updateById(task, userId);
                return null;
            });
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "核对对象快照已变更，任务回到待签署，须以新快照重新签署：task=" + task.id
                            + "；原基线=" + in.snapshotHash + " 现值=" + current
                            + "（FR-SETTLE-17 验收 4 / R-SETTLE-11）");
        }
        // ② 不可代签（角色级判据，DC-022）：应签集合成员 + 用户持有该角色 + 显式选择一致
        Set<String> required = splitRoles(task.requiredSignRoles);
        if (!required.contains(signRole)) {
            rejectProxy(task, signRole, userId, clientIp, signRole,
                    ErrorCode.FUNCTION_PERMISSION_DENIED,
                    "所选签署角色不在任务应签清单：" + signRole + " ∉ " + task.requiredSignRoles
                            + "（DC-022 判据②；多角色账号不猜测不默认）");
        }
        String actualRoleCode = SIGN_ROLE_ALIAS.get(signRole);
        List<String> held = roles.roleCodesOf(userId);
        if (held == null || !held.contains(actualRoleCode)) {
            rejectProxy(task, signRole, userId, clientIp, actualRoleCode,
                    ErrorCode.FUNCTION_PERMISSION_DENIED,
                    "签署人未持有应签岗位角色 " + actualRoleCode + "（sign_role=" + signRole
                            + "）：userId=" + userId + "（DC-022 判据①/③，不可代签）");
        }
        boolean manage = ROLE_MANAGE.equals(signRole);
        if (manage && tradingRoleChecker.hasTradingRole(userId)) {
            rejectProxy(task, signRole, userId, clientIp, actualRoleCode,
                    ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "管理层签署权与交易权互斥：签署人仍具交易岗能力，只能签收、不得操盘：userId=" + userId
                            + "（R-AUTH-13 / FR-SETTLE-17 验收 6）");
        }
        // 季度：管理层签收须在风控编制 + 交易会签之后（FR-SETTLE-14「RISK 编制 + TRADE 会签 → MANAGE 签收」）
        if (manage && CHECK_QUARTERLY.equals(task.checkType)) {
            Set<String> signed = signedRolesOf(task);
            if (!signed.contains(ROLE_RISK) || !signed.contains(ROLE_TRADE)) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "管理层签收须在风控编制 + 交易会签之后：已签=" + signed + "（FR-SETTLE-14）");
            }
        }
        // 岗位分离：同一账号不得在同一任务中充当多个参与方（FR-SETTLE-13 验收 2 / FR-AUTH-8）
        for (SettleCheckSignature prev : signatureDao.findByTask(task.id)) {
            if (SIGN_SIGNED.equals(prev.signResult) && current.equals(prev.snapshotHash)
                    && prev.signerId != null && prev.signerId == userId
                    && !signRole.equals(prev.signRole)) {
                rejectProxy(task, signRole, userId, clientIp, actualRoleCode,
                        ErrorCode.POSITION_SEPARATION_CONFLICT,
                        "同一账号不得充当多个核对参与方：userId=" + userId + " 已签角色=" + prev.signRole
                                + " 本次角色=" + signRole + "（岗位分离，FR-SETTLE-13 验收 2 / FR-AUTH-8）");
            }
        }
        // ③ 落签署记录（同任务同角色二次签署被 uk_check_signature_task_role 拒绝）
        SettleCheckSignature existing = signatureDao.findByTaskAndRole(task.id, signRole);
        if (existing != null && SIGN_SIGNED.equals(existing.signResult)
                && current.equals(existing.snapshotHash)) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "同任务同角色不可重复签署：task=" + task.id + " role=" + signRole
                            + "（uk_check_signature_task_role / FR-SETTLE-17 验收 1）");
        }
        return current;
    }

    /** 签署写入（调用方须已置于事务内；前置判据已由 {@link #precheckSign} 通过）。 */
    private SignResult signInTx(SettleCheckTask task, SignCheckInput in, String signRole, long userId,
                                String clientIp, String current) {
        boolean manage = ROLE_MANAGE.equals(signRole);
        String actualRoleCode = SIGN_ROLE_ALIAS.get(signRole);
        SettleCheckSignature existing = signatureDao.findByTaskAndRole(task.id, signRole);
        SettleCheckSignature sig = existing == null ? new SettleCheckSignature() : existing;
        sig.subAccountId = task.subAccountId;
        sig.checkTaskId = task.id;
        sig.signRole = signRole;
        sig.signerId = userId;
        sig.signerRoleCode = actualRoleCode;
        sig.signTime = Clocks.now();
        sig.signIp = clientIp == null ? "" : clientIp;
        sig.snapshotHash = current;
        sig.signResult = SIGN_SIGNED;
        sig.signOpinion = in.signOpinion;
        sig.proxyRejected = "N";
        sig.manageRoleTradeBlocked = manage ? "Y" : "N";
        if (existing == null) {
            signatureDao.insert(sig, userId);
        } else {
            signatureDao.updateById(sig, userId);
        }
        if (manage) {
            task.submittedTo = actualRoleCode;
        }
        updateTaskStatus(task, userId);
        LOG.info("核对签署成功：task=" + task.id + " period=" + task.periodKey + " role=" + signRole
                + " signer=" + userId + " manageTradeBlocked=" + (manage ? "Y" : "N"));
        SignResult out = new SignResult();
        out.taskId = task.id;
        out.status = task.status;
        out.requiredSignRoles = task.requiredSignRoles;
        out.signedRoles = task.signedRoles;
        out.signRole = signRole;
        out.signerId = userId;
        return out;
    }

    // ================================================================ 三、逾期巡检

    /**
     * 逾期巡检（{@code R-SETTLE-11}：逾期未签须告警，<b>不得静默缺失</b>）。
     *
     * @param businessDate 业务日（注入时钟，便于测试）
     * @param operatorId   操作人
     * @return 本次新置为逾期的任务清单
     */
    public List<SettleCheckTask> scanOverdue(LocalDate businessDate, long operatorId) {
        if (businessDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "businessDate 必填");
        }
        List<SettleCheckTask> newly = new ArrayList<>();
        tx.inTransaction(conn -> {
            for (SettleCheckTask t : taskDao.findOverdue(businessDate)) {
                if ("Y".equals(t.overdueAlerted)) {
                    continue;
                }
                t.status = STATUS_OVERDUE;
                t.overdueAlerted = "Y";
                taskDao.updateById(t, operatorId);
                newly.add(t);
                LOG.warning("核对任务逾期未签（告警，不得静默缺失）：task=" + t.id + " type=" + t.checkType
                        + " period=" + t.periodKey + " deadline=" + t.deadlineDate
                        + " status=" + t.status + "（R-SETTLE-11）");
            }
            return null;
        });
        return newly;
    }

    // ================================================================ 四、只读查询与导出

    /** 核对任务视图（含签署记录）。 */
    public static final class CheckTaskView {
        /** 任务。 */
        public SettleCheckTask task;
        /** 签署记录。 */
        public List<SettleCheckSignature> signatures = new ArrayList<>();
    }

    /** 查询入参。 */
    public static final class QueryCheckInput {
        /** 核对类型（可空=不限）。 */
        public String checkType;
        /** 期间键（可空=不限）。 */
        public String periodKey;
        /** 子账户（可空=按可见集取数）。 */
        public Integer subAccountId;
    }

    /**
     * 只读查询核对任务与签署记录（{@code FR-AUDIT-13}）。
     *
     * <p>可见性：以 {@link SubAccountVisibility#visibleSubAccountIds(long)} 取可见集——
     * 含哨兵 0（跨账户只读）⇒ 不限子账户；否则限于集合内子账户；空集 ⇒ 返回空（fail-closed）。
     *
     * @param in        查询条件
     * @param query     分页
     * @param userId    调用者
     * @return 分页结果
     */
    public PageResult<CheckTaskView> query(QueryCheckInput in, PageQuery query, long userId) {
        QueryCheckInput q = in == null ? new QueryCheckInput() : in;
        String checkType = q.checkType == null || q.checkType.trim().isEmpty() ? null
                : requireCheckType(q.checkType);
        String periodKey = q.periodKey == null || q.periodKey.trim().isEmpty() ? null : q.periodKey.trim();
        List<Integer> visible = visibility.visibleSubAccountIds(userId);
        if (visible == null || visible.isEmpty()) {
            return new PageResult<>(new ArrayList<>(), 0, query.page(), query.size());
        }
        boolean cross = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        if (q.subAccountId != null) {
            visibility.assertSubAccountVisible(userId, q.subAccountId);
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (checkType != null) {
            appendAnd(where, "check_type = ?");
            args.add(checkType);
        }
        if (periodKey != null) {
            appendAnd(where, "period_key = ?");
            args.add(periodKey);
        }
        if (q.subAccountId != null) {
            appendAnd(where, "sub_account_id = ?");
            args.add(q.subAccountId);
        } else if (!cross) {
            appendAnd(where, "sub_account_id IN (" + placeholders(visible.size()) + ")");
            args.addAll(visible);
        }
        PageResult<SettleCheckTask> page = taskDao.findPage(query,
                where.length() == 0 ? null : where.toString(), args);
        List<CheckTaskView> views = new ArrayList<>();
        for (SettleCheckTask t : page.items()) {
            CheckTaskView v = new CheckTaskView();
            v.task = t;
            v.signatures = signatureDao.findByTask(t.id);
            views.add(v);
        }
        return new PageResult<>(views, page.total(), page.page(), page.size());
    }

    /** 导出结果（{@code FR-SETTLE-13} 验收 5 / {@code FR-SETTLE-14} 验收 4）。 */
    public static final class CheckExport {
        /** 列名。 */
        public List<String> columns = new ArrayList<>();
        /** 数据行。 */
        public List<List<String>> rows = new ArrayList<>();
        /** 导出时间。 */
        public LocalDateTime exportTime;
        /** 导出人。 */
        public long exportedBy;

        /** CSV 文本（含表头；无数据仅表头）。 */
        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            sb.append(toCsvLine(columns)).append('\n');
            for (List<String> row : rows) {
                sb.append(toCsvLine(row)).append('\n');
            }
            return sb.toString();
        }

        private static String toCsvLine(List<String> cells) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String c = cells.get(i) == null ? "" : cells.get(i);
                if (c.contains(",") || c.contains("\"") || c.contains("\n")) {
                    sb.append('"').append(c.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    /**
     * 导出核对记录与签署记录（合规证据；一行一条签署，无签署的任务补空签署列）。
     *
     * @param in        查询条件（同 {@link #query}）
     * @param userId    调用者
     * @return 导出结果
     */
    public CheckExport export(QueryCheckInput in, long userId) {
        CheckExport out = new CheckExport();
        out.exportTime = Clocks.now();
        out.exportedBy = userId;
        out.columns = Arrays.asList("checkType", "periodKey", "subAccountId", "status",
                "requiredSignRoles", "signedRoles", "deadlineDate", "taskSnapshotHash",
                "signRole", "signerId", "signerRoleCode", "signTime", "signIp",
                "signatureSnapshotHash", "signResult", "proxyRejected", "manageRoleTradeBlocked");
        // TE-T048-003：全量分页迭代取尽（原硬编码 size=100 仅第 1 页 ⇒ >100 静默截断）。
        // 先例：FundService.exportReconcile 的同形循环（逐页追加，终止条件 items.size() < size）。
        int pageNo = 1;
        int size = PageQuery.ALLOWED_SIZES.get(PageQuery.ALLOWED_SIZES.size() - 1); // 100（页大小上限）
        int tasksSeen = 0;
        while (true) {
            PageQuery q = PageQuery.of(String.valueOf(pageNo), String.valueOf(size), null,
                    "generated_time", "desc");
            PageResult<CheckTaskView> page = query(in, q, userId);
            for (CheckTaskView v : page.items()) {
                tasksSeen++;
                if (v.signatures.isEmpty()) {
                    out.rows.add(row(v.task, null));
                } else {
                    for (SettleCheckSignature s : v.signatures) {
                        out.rows.add(row(v.task, s));
                    }
                }
            }
            if (page.items().size() < size || tasksSeen >= page.total()) {
                break;
            }
            pageNo++;
        }
        return out;
    }

    private static List<String> row(SettleCheckTask t, SettleCheckSignature s) {
        return Arrays.asList(
                t.checkType, t.periodKey, String.valueOf(t.subAccountId), t.status,
                t.requiredSignRoles, t.signedRoles, str(t.deadlineDate), t.snapshotHash,
                s == null ? "" : s.signRole,
                s == null ? "" : String.valueOf(s.signerId),
                s == null ? "" : s.signerRoleCode,
                s == null ? "" : str(s.signTime),
                s == null ? "" : s.signIp,
                s == null ? "" : s.snapshotHash,
                s == null ? "" : s.signResult,
                s == null ? "" : s.proxyRejected,
                s == null ? "" : s.manageRoleTradeBlocked);
    }

    // ================================================================ 五、内部

    /** 代签/越权拒绝：记越权尝试 + 落 {@code proxy_rejected='Y'} 留痕 + 抛拒绝。 */
    private void rejectProxy(SettleCheckTask task, String signRole, long userId, String clientIp,
                             String actualRoleCode, ErrorCode code, String reason) {
        UnauthorizedAttempt attempt = new UnauthorizedAttempt();
        attempt.operatorId = userId;
        attempt.attemptType = ATTEMPT_PERMISSION_DENIED;
        attempt.attemptDesc = reason;
        attempt.targetResource = "/settle/" + (CHECK_QUARTERLY.equals(task.checkType)
                ? "quarterly-report" : "monthly-check");
        attempt.subAccountId = task.subAccountId;
        attempt.rejectReason = reason;
        attempt.rejectCode = String.valueOf(code.code());
        attempt.attemptTime = Clocks.now();
        attempt.clientIp = clientIp;
        // TE-T048-001：留痕经独立事务提交后再抛（照 AuthService.recordAttempt 先例），
        // 不随签署写入事务回滚——越权/代签证据不得静默丢失（DC-022 / R-AUTH-8 / FR-SETTLE-17 验收2）。
        tx.inTransaction(conn -> {
            attemptDao.insert(attempt, userId);
            // 代签留痕：仅在尚无同（task, role）记录时落一行 REJECTED（避免占用 uk 槽位阻塞应签本人）
            SettleCheckSignature existing = signatureDao.findByTaskAndRole(task.id, signRole);
            if (existing == null) {
                SettleCheckSignature sig = new SettleCheckSignature();
                sig.subAccountId = task.subAccountId;
                sig.checkTaskId = task.id;
                sig.signRole = signRole;
                sig.signerId = userId;
                sig.signerRoleCode = actualRoleCode;
                sig.signTime = Clocks.now();
                sig.signIp = clientIp == null ? "" : clientIp;
                sig.snapshotHash = task.snapshotHash;
                sig.signResult = SIGN_REJECTED;
                sig.signOpinion = reason;
                sig.proxyRejected = "Y";
                sig.manageRoleTradeBlocked = ROLE_MANAGE.equals(signRole) ? "Y" : "N";
                signatureDao.insert(sig, userId);
            }
            return null;
        });
        LOG.warning("代签/越权签署被拒：task=" + task.id + " role=" + signRole + " signer=" + userId
                + " reason=" + reason);
        throw BizException.of(code, reason);
    }

    /** 依据「与当前快照一致的有效签署」重算任务状态。 */
    private void updateTaskStatus(SettleCheckTask task, long operatorId) {
        Set<String> signed = signedRolesOf(task);
        task.signedRoles = joinRoles(task.requiredSignRoles, signed);
        Set<String> required = splitRoles(task.requiredSignRoles);
        if (signed.containsAll(required)) {
            task.status = STATUS_COMPLETED;
            task.completedTime = Clocks.now();
        } else if (signed.isEmpty()) {
            task.status = STATUS_PENDING_SIGN;
            task.completedTime = null;
        } else {
            task.status = STATUS_PARTIAL_SIGNED;
            task.completedTime = null;
        }
        taskDao.updateById(task, operatorId);
    }

    /** 任务上与当前快照一致的已签角色集合。 */
    private Set<String> signedRolesOf(SettleCheckTask task) {
        Set<String> signed = new LinkedHashSet<>();
        for (SettleCheckSignature s : signatureDao.findByTask(task.id)) {
            if (SIGN_SIGNED.equals(s.signResult) && task.snapshotHash.equals(s.snapshotHash)) {
                signed.add(s.signRole);
            }
        }
        return signed;
    }

    private String requiredRolesFor(String checkType) {
        if (CHECK_QUARTERLY.equals(checkType)) {
            return ROLE_RISK + "," + ROLE_TRADE + "," + ROLE_MANAGE;
        }
        if (CHECK_ANNUAL.equals(checkType)) {
            return ROLE_AUDITOR;
        }
        String configured = config == null ? null : config.get(KEY_SIGN_ROLES);
        String value = configured == null || configured.trim().isEmpty()
                ? ROLE_RISK + "," + ROLE_TRADE + "," + ROLE_FINANCE : configured.trim();
        Set<String> set = splitRoles(value);
        if (set.size() < 2) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "月度应签角色须 ≥2（CFG-14）：" + value + "；复现=修正配置 " + KEY_SIGN_ROLES);
        }
        for (String r : set) {
            if (!SIGN_ROLES.contains(r)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "应签角色取值非法：" + r + "；合法域=" + SIGN_ROLES + "（" + KEY_SIGN_ROLES + "）");
            }
        }
        return String.join(",", set);
    }

    private int deadlineDays() {
        String raw = config == null ? null : config.get(KEY_DEADLINE_DAYS);
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_DEADLINE_DAYS;
        }
        int days;
        try {
            days = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "签署截止天数非法（CFG-14）：" + raw);
        }
        if (days < MIN_DEADLINE_DAYS || days > MAX_DEADLINE_DAYS) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "签署截止天数须在 " + MIN_DEADLINE_DAYS + "–" + MAX_DEADLINE_DAYS + " 天（CFG-14）：" + days);
        }
        return days;
    }

    /** 截止日 = 期间结束日 + {@code settle.sign.deadline_days}（默认 30）。 */
    public LocalDate deadlineDate(String checkType, String periodKey) {
        return periodEnd(checkType, periodKey).plusDays(deadlineDays());
    }

    static LocalDate periodEnd(String checkType, String periodKey) {
        try {
            if (CHECK_MONTHLY.equals(checkType)) {
                return YearMonth.parse(periodKey).atEndOfMonth();
            }
            if (CHECK_ANNUAL.equals(checkType)) {
                return LocalDate.of(Integer.parseInt(periodKey.trim()), 12, 31);
            }
            // QUARTERLY: yyyy-Qn
            String[] parts = periodKey.split("-");
            if (parts.length != 2 || !parts[1].toUpperCase(java.util.Locale.ROOT).startsWith("Q")) {
                throw new IllegalArgumentException(periodKey);
            }
            int year = Integer.parseInt(parts[0].trim());
            int q = Integer.parseInt(parts[1].substring(1).trim());
            if (q < 1 || q > 4) {
                throw new IllegalArgumentException(periodKey);
            }
            return YearMonth.of(year, q * 3).atEndOfMonth();
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "期间键无法解析：" + periodKey + "；" + PERIOD_HINT);
        }
    }

    private static String requireCheckType(String checkType) {
        if (checkType == null || checkType.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "checkType 必填（MONTHLY/QUARTERLY/ANNUAL）");
        }
        String v = checkType.trim().toUpperCase(java.util.Locale.ROOT);
        if (!CHECK_MONTHLY.equals(v) && !CHECK_QUARTERLY.equals(v) && !CHECK_ANNUAL.equals(v)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "checkType 取值非法：" + checkType + "（MONTHLY/QUARTERLY/ANNUAL）");
        }
        return v;
    }

    private static String requirePeriodKey(String periodKey, String checkType) {
        String v = requireText(periodKey, "periodKey（" + PERIOD_HINT + "）");
        periodEnd(checkType, v);
        return v;
    }

    private String resolveSnapshotHash(String checkType, String periodKey, int subAccountId,
                                       String provided) {
        if (snapshotProvider != null) {
            String computed = snapshotProvider.snapshotHash(checkType, periodKey, subAccountId);
            if (computed != null && !computed.isEmpty()) {
                return computed;
            }
        }
        if (provided != null && !provided.isEmpty()) {
            return provided;
        }
        // 无法取快照 ⇒ 拒绝生成（不得以空哈希或推算值入库，R-SETTLE-3 精神）
        throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                "核对对象快照哈希不可得：未装配 SnapshotProvider 且请求未携带 snapshotHash"
                        + "；复现=装配快照接缝或提交 snapshotHash（ARCHITECTURE.md §6.14）");
    }

    private String resolveCurrentSnapshot(SettleCheckTask task, String provided) {
        if (snapshotProvider != null) {
            String computed = snapshotProvider.snapshotHash(task.checkType, task.periodKey,
                    task.subAccountId == null ? 0 : task.subAccountId);
            if (computed != null && !computed.isEmpty()) {
                return computed;
            }
        }
        return provided == null || provided.isEmpty() ? task.snapshotHash : provided;
    }

    private static Set<String> splitRoles(String roles) {
        Set<String> set = new LinkedHashSet<>();
        if (roles == null) {
            return set;
        }
        for (String r : roles.split(",")) {
            if (!r.trim().isEmpty()) {
                set.add(r.trim());
            }
        }
        return set;
    }

    private static String joinRoles(String requiredOrder, Set<String> signed) {
        StringBuilder sb = new StringBuilder();
        for (String r : splitRoles(requiredOrder)) {
            if (signed.contains(r)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(r);
            }
        }
        // 非应签集合内的签署（一般不会出现）按字典序追加，保证确定性
        TreeMap<String, Boolean> extra = new TreeMap<>();
        for (String r : signed) {
            if (!requiredOrder.contains(r)) {
                extra.put(r, Boolean.TRUE);
            }
        }
        for (String r : extra.keySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(r);
        }
        return sb.toString();
    }

    private static void appendAnd(StringBuilder where, String clause) {
        if (where.length() > 0) {
            where.append(" AND ");
        }
        where.append(clause);
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 不得为空");
        }
        return value.trim();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
