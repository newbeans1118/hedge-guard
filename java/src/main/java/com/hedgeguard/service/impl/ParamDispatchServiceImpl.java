package com.hedgeguard.service.impl;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.bridge.TradingBridge;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.common.Config;
import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.ParamDispatchDao;
import com.hedgeguard.dao.RoleDao;
import com.hedgeguard.dao.UserAccountDao;
import com.hedgeguard.dao.UserRoleDao;
import com.hedgeguard.dao.entity.Authorization;
import com.hedgeguard.dao.entity.ParamDispatch;
import com.hedgeguard.dao.entity.Role;
import com.hedgeguard.dao.entity.UserAccount;
import com.hedgeguard.dao.entity.UserRole;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.ParamDispatchService;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.LocalDateTime;

/**
 * 参数下发编排实现（{@code T-011}；{@code ARCHITECTURE.md} §3.8「跨模块共用能力」）。
 *
 * <p>统一「**改 Java 侧 → 下发 C++ → 等回执 → 留痕**」四步，供
 * {@code Variety / Plan / Risk / Auth / Config} 共用（避免 5 处各写一套）。
 *
 * <h2>口径来源（以冻结契约为唯一事实源）</h2>
 * <ul>
 *   <li>payload 键名：{@code java/CONTRACTS.md} <b>§3.2.1（`HGC-1.2`）</b> —— 键名**直接采用
 *       {@code ARCHITECTURE.md} §4.2.3 的协议字段名**：{@code sub_account_id} / {@code scan_scope}
 *       / {@code suspended} / {@code reason} / {@code operator_id}。
 *       ⚠️ 注意：正文 §3.11 写作 {@code scope}，**协议表写 {@code scan_scope}**，
 *       契约明示"不一致时以协议表为准"（登记 `D-15`），故本类用 {@code scan_scope}。</li>
 *   <li>版本分配与超时：{@code CONTRACTS.md} <b>§3.2.2</b>（分配方 = 服务层；计时方 = 服务层）。</li>
 *   <li>两阶段配对：{@code CONTRACTS.md} §3.2.1 —— **协议无 per-suspend 令牌字段**，
 *       故"撤下我这次下的挂起"**只能**靠 {@code sub_account_id} 定位 + **本类维护的未撤销挂起计数**，
 *       <b>仅当计数归零</b>才下发 {@code suspended="false"}（否则 A 的挂起会被 B 的解冻误撤）。</li>
 * </ul>
 *
 * <h2>三条硬纪律</h2>
 * <ol>
 *   <li><b>无回执或回执失败不得视为已生效</b>（{@code R-CONFIG-3}）：超时（{@code CFG-28}
 *       {@code hg.cmd_timeout_ms} = {@value #ACK_TIMEOUT_MILLIS} ms）→ 置 {@code UNCONFIRMED}，
 *       {@link #requireEffective} 抛 {@code 10014}（阻断四项齐备，{@code ruleId = R-CONFIG-3}）；</li>
 *   <li><b>先落库再下发</b>：下发前写 {@code PENDING} 行，使"下发了但没记账"不可能发生；
 *       巡检凭 {@code PENDING}/{@code FAIL}/{@code UNCONFIRMED} 重发；</li>
 *   <li><b>限额下调两阶段</b>（{@code §9 RSK-04}）：冻结（{@code scan_scope=OPEN_ONLY} + 该子账户）
 *       → 替换快照 → **撤下本次挂起**；任一步未生效即中止，且额度步失败时**立即撤下挂起**，不留半生效。</li>
 * </ol>
 *
 * <h2>⚠️ 已知偏离（诚实登记，待 `#java` 侧 DAO 补方法后收口）</h2>
 * 契约 §3.2.2 要求版本取值 = {@code SELECT MAX(param_version) FROM t_param_dispatch} + 1。
 * 但 {@code ParamDispatchDao}（{@code #java} 写域，{@code T-061}）**未提供 max 查询**；
 * 本类以**进程内单调计数器**（{@code versionCounter}，首次按 {@code count + 1} 播种）为唯一分配源，
 * 从而在**服务层**保证"两两不同 + 严格单调"（并发用例实证：12 线程取到 1..12）。
 * 与原口径的差异：@code{S}MAX+1@code{S} 每次向库取数，本类只在**首次**取数（避免"读-写"跨临界区导致同号）。
 * 已投 `question` 请 `#java` 增 {@code long maxParamVersion()} 以逐字对齐契约。
 */
public final class ParamDispatchServiceImpl implements ParamDispatchService {

    private static final Logger LOG = Logger.getLogger(ParamDispatchServiceImpl.class.getName());

    /** 回执超时的配置键（{@code CFG-28}）。 */
    public static final String KEY_CMD_TIMEOUT_MS = "hg.cmd_timeout_ms";

    /** 回执超时**缺省值**（毫秒；{@code CFG-28}）。生产取值经本键从配置读取，**不硬编码**。 */
    public static final long ACK_TIMEOUT_MILLIS = 3000L;

    /** payload 键：**本次挂起的唯一标识**（配对撤销的判据；缺失即缺陷，{@code CONTRACTS §3.2.1}）。 */
    /**
     * 挂起标识的**内部键名**（{@code HGC-1.6} 裁定：**仅 Java 内部关联标识，禁止进入 payload**）。
     *
     * <p>原因：{@code §4.2.3} 的 {@code CMD_SET_TRADING_SUSPEND}（{@code 0x0118}）协议字段**只有**
     * {@code param_version} / {@code sub_account_id} / {@code scan_scope} / {@code suspended} /
     * {@code reason} / {@code operator_id}；额外键会被 {@code bridge} 映射时**报错或被静默丢弃**。
     * 追溯需求走 {@code reason} 文本与 {@code t_param_dispatch.ack_detail}（不新增协议字段）。
     */
    public static final String KEY_SUSPEND_ID = "suspend_id";

    /** payload 键：子账户 ID（{@code "0"} = 全账户哨兵）。 */
    public static final String KEY_SUB_ACCOUNT_ID = "sub_account_id";

    /** payload 键：扫描作用域（协议字段名，{@code §4.2.3}；正文 §3.11 的 {@code scope} 已作废）。 */
    public static final String KEY_SCAN_SCOPE = "scan_scope";

    /** payload 键：挂起开关（{@code "true"} = 挂起；{@code "false"} = **撤下**）。 */
    public static final String KEY_SUSPENDED = "suspended";

    /** payload 键：原因（审计）。 */
    public static final String KEY_REASON = "reason";

    /** payload 键：操作人（与 {@code ParamChange.operatorId} 同源，供协议侧留痕）。 */
    public static final String KEY_OPERATOR_ID = "operator_id";

    /** payload 键：限额层级（{@code CMD_UPDATE_QUOTA}）。 */
    public static final String KEY_LIMIT_LAYER = "limit_layer";

    /** payload 键：品种 ID。 */
    public static final String KEY_PRODUCT_ID = "product_id";

    /** payload 键：合约代码（层②）。 */
    public static final String KEY_INSTRUMENT_ID = "instrument_id";

    /** payload 键：额度上限。 */
    public static final String KEY_QUOTA_LIMIT = "quota_limit";

    /** payload 键：生效起始日（{@code yyyy-MM-dd}）。 */
    public static final String KEY_EFFECTIVE_FROM = "effective_from";

    /** 扫描作用域取值：仅限新开仓（平仓/减仓放行，{@code R-RISK-5}）。 */
    public static final String SCAN_SCOPE_OPEN_ONLY = "OPEN_ONLY";

    /** 全账户哨兵值（{@code sub_account_id="0"}）。 */
    public static final String ALL_ACCOUNT_SENTINEL = "0";

    /** 三层限额层级取值域。 */
    public static final List<String> LIMIT_LAYERS =
            List.of("SUB_PRODUCT", "SUB_INSTRUMENT", "ACCOUNT_PRODUCT");

    private final TxTemplate tx;
    private final ParamDispatchDao paramDispatchDao;
    private final TradingBridge bridge;
    private final Duration ackTimeout;

    /** 权限快照派生的读库依赖（{@code T-168}）；未装配时 {@code dispatchPermissionSnapshot} 显式拒绝。 */
    private final UserAccountDao userAccountDao;
    private final RoleDao roleDao;
    private final UserRoleDao userRoleDao;
    private final AuthorizationDao authorizationDao;

    /** 版本分配锁（仅用于**首次播种**；契约 §3.2.2：**必须由服务层串行化**，该表无 {@code UNIQUE(param_version)}）。 */
    private final Object versionLock = new Object();

    /**
     * 单调版本计数器（{@code -1} = 未播种）。
     *
     * <p>为何用进程内计数器而非每次 {@code MAX+1}：分配与落库**不在同一个临界区**，
     * 若各线程各自"读计数→落库"，两个线程会读到同一计数并**取到同号**
     * （本类的并发用例已实证：12 线程仅得 4 个不同版本）。故以**单一计数器**为唯一分配源，
     * 首次访问时按 {@code count+1} 播种（{@code count ≥ max} ⇒ 播种值 &gt; 现有 max），
     * 之后仅靠原子自增 —— 单写者进程内保证**两两不同且严格单调**。
     */
    private final java.util.concurrent.atomic.AtomicLong versionCounter =
            new java.util.concurrent.atomic.AtomicLong(-1L);

    /** 各子账户"未撤销挂起"计数（配对撤销靠"计数归零"才下发解冻，§3.2.1）。 */
    private final Map<Integer, AtomicInteger> outstandingSuspends = new ConcurrentHashMap<>();

    /** 各子账户的挂起标识栈（LIFO）：解冻必须**回传同一 {@code suspend_id}**，不得解冻全部挂起。 */
    private final Map<Integer, java.util.Deque<String>> activeSuspendIds = new ConcurrentHashMap<>();

    /**
     * 构造（默认超时 {@value #ACK_TIMEOUT_MILLIS} ms）。
     *
     * @param tx               事务模板
     * @param paramDispatchDao 下发留痕 DAO（{@code T-061} 交付）
     * @param bridge           组件桥接
     */
    public ParamDispatchServiceImpl(TxTemplate tx, ParamDispatchDao paramDispatchDao,
                                    TradingBridge bridge) {
        this(tx, paramDispatchDao, bridge, Duration.ofMillis(ACK_TIMEOUT_MILLIS));
    }

    /**
     * 构造（可注入超时，供单测压缩等待；生产恒用 {@value #ACK_TIMEOUT_MILLIS} ms）。
     *
     * @param tx               事务模板
     * @param paramDispatchDao 下发留痕 DAO
     * @param bridge           组件桥接
     * @param ackTimeout       回执超时
     */
    public ParamDispatchServiceImpl(TxTemplate tx, ParamDispatchDao paramDispatchDao,
                                    TradingBridge bridge, Duration ackTimeout) {
        this(tx, paramDispatchDao, bridge, ackTimeout, null, null, null, null);
    }

    /**
     * 构造（**生产口径**：回执超时取自 {@code CFG-28 hg.cmd_timeout_ms}，不得硬编码）。
     *
     * @param tx               事务模板
     * @param paramDispatchDao 下发留痕 DAO
     * @param bridge           组件桥接
     * @param config           配置（{@code null} 时按 {@value #ACK_TIMEOUT_MILLIS} ms 兜底）
     */
    public ParamDispatchServiceImpl(TxTemplate tx, ParamDispatchDao paramDispatchDao,
                                    TradingBridge bridge, Config config) {
        this(tx, paramDispatchDao, bridge, config, null, null, null, null);
    }

    /**
     * 构造（**全量**：含权限快照派生的读库依赖，{@code T-168}；生产装配使用本形态）。
     *
     * <p>四个权限 DAO 允许为 {@code null}（既有构造形态不接它们）：此时
     * {@link #dispatchPermissionSnapshot} 以 {@code 10002} 显式拒绝（不 NPE、不静默发空快照）。
     *
     * @param tx               事务模板
     * @param paramDispatchDao 下发留痕 DAO
     * @param bridge           组件桥接
     * @param ackTimeout       回执超时
     * @param userAccountDao   {@code t_user}（{@code is_enterprise_leader} 来源）
     * @param roleDao          {@code t_role}（角色编码 / {@code has_trading_operation} 来源）
     * @param userRoleDao      {@code t_user_role}（用户→角色关联）
     * @param authorizationDao {@code t_authorization}（授权品种集合来源）
     */
    public ParamDispatchServiceImpl(TxTemplate tx, ParamDispatchDao paramDispatchDao,
                                    TradingBridge bridge, Duration ackTimeout,
                                    UserAccountDao userAccountDao, RoleDao roleDao,
                                    UserRoleDao userRoleDao, AuthorizationDao authorizationDao) {
        this.tx = tx;
        this.paramDispatchDao = paramDispatchDao;
        this.bridge = bridge;
        this.ackTimeout = ackTimeout;
        this.userAccountDao = userAccountDao;
        this.roleDao = roleDao;
        this.userRoleDao = userRoleDao;
        this.authorizationDao = authorizationDao;
    }

    /**
     * 构造（**全量 + 配置口径**：权限派生依赖 + 回执超时取 {@code CFG-28}）。
     *
     * @param tx               事务模板
     * @param paramDispatchDao 下发留痕 DAO
     * @param bridge           组件桥接
     * @param config           配置（{@code null} 时按 {@value #ACK_TIMEOUT_MILLIS} ms 兜底）
     * @param userAccountDao   {@code t_user}
     * @param roleDao          {@code t_role}
     * @param userRoleDao      {@code t_user_role}
     * @param authorizationDao {@code t_authorization}
     */
    public ParamDispatchServiceImpl(TxTemplate tx, ParamDispatchDao paramDispatchDao,
                                    TradingBridge bridge, Config config,
                                    UserAccountDao userAccountDao, RoleDao roleDao,
                                    UserRoleDao userRoleDao, AuthorizationDao authorizationDao) {
        this(tx, paramDispatchDao, bridge,
                Duration.ofMillis(config == null ? ACK_TIMEOUT_MILLIS
                        : config.getInt(KEY_CMD_TIMEOUT_MS, (int) ACK_TIMEOUT_MILLIS)),
                userAccountDao, roleDao, userRoleDao, authorizationDao);
    }

    /** 当前生效的回执超时（毫秒；供自检与用例断言"来自 CFG-28 配置"。 */
    public long ackTimeoutMillis() {
        return ackTimeout.toMillis();
    }

    @Override
    public CompletableFuture<AckResult> dispatch(ParamChange change) {
        if (change == null || change.type == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "参数变更须显式给出 type：复现=构造 ParamChange(Type, ...) 后下发");
        }
        if (!bridge.isReady()) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "交易组件不可用（isReady=false），拒绝下发（不得静默丢弃）：type=" + change.type
                            + "；复现=检查组件进程与 HGCP 通道（NFR-DEPLOY-5）");
        }
        long version = change.paramVersion > 0 ? change.paramVersion : allocateParamVersion();
        change.paramVersion = version;
        ParamDispatch row = persistPending(change, version);

        CompletableFuture<AckResult> future;
        try {
            future = bridge.dispatch(change);
        } catch (RuntimeException e) {
            complete(row, "FAIL", "下发调用抛异常：" + e.getClass().getSimpleName() + "：" + e.getMessage(),
                    Clocks.now());
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "参数下发失败（组件调用异常）：type=" + change.type + " paramVersion=" + version
                            + "；原因=" + e.getMessage()
                            + "；复现=核对 TradingBridge.dispatch 与组件通道", e);
        }
        return future.orTimeout(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((ack, error) -> settle(row, change, version, ack, error));
    }

    @Override
    public void requireEffective(ParamChange change, AckResult ack) {
        if (ack != null && ack.acknowledged) {
            return;
        }
        long version = change == null ? -1L : change.paramVersion;
        String type = change == null || change.type == null ? "UNKNOWN" : change.type.name();
        throw BizException.blocking(ErrorCode.DISPATCH_NOT_EFFECTIVE,
                "参数下发未生效（不得视为已生效，R-CONFIG-3）：type=" + type + " paramVersion=" + version
                        + (ack == null || ack.message == null ? "" : ("；组件回执=" + ack.message)),
                "R-CONFIG-3",
                ack == null ? "UNCONFIRMED（无回执）" : "UNCONFIRMED：" + ack.message,
                "ACK（期望 param_version=" + version + "）",
                "巡检 t_param_dispatch 的 PENDING/FAIL/UNCONFIRMED 行并重发；"
                        + "同时确认组件运行状态与 HGCP 通道（NFR-DEPLOY-5）");
    }

    /**
     * 挂起该子账户的**新开仓**（{@code scan_scope=OPEN_ONLY}；平仓放行）。
     *
     * <p>本方法使"未撤销挂起计数"+1；每次调用都必须与一次 {@link #releaseOpenOnly} 配对。
     *
     * <p><b>限制（{@code HGC-1.6}）</b>：标识由本方法内部铸造，调用方**不持有**
     * —— 只能经 {@link #peekSuspendId} 回读（并发下存在竞态）。
     * 因此**凡需要"按标识精确撤销某一次挂起"的场景，必须改用 4 参主入口**
     * （先 {@link #newSuspendId()} 铸造并自持，再传入）。本重载仅适用于"不需要精确配对"的场合。
     *
     * @param subAccountId 子账户（&gt; 0）
     * @param reason       原因（审计）
     * @param operatorId   操作人
     * @return 下发回执
     */
    public AckResult suspendOpenOnly(int subAccountId, String reason, long operatorId) {
        return suspendOpenOnly(subAccountId, newSuspendId(), reason, operatorId);
    }

    /**
     * 挂起该子账户的**新开仓**，由**调用方提供**挂起标识（主入口）。
     *
     * <p>为何要求调用方传入：{@code HGC-1.6} 裁定「内部关联标识必须经 Java API 可见」
     * （传入**或**返回），<b>禁止</b>要求调用方解析 {@code reason} 文本去获得
     * —— 否则"撤销某一次特定挂起"在 API 上不可达（本类原设计仅有 3 参重载，即是此缺陷）。
     *
     * @param subAccountId 子账户（&gt; 0）
     * @param suspendId    调用方生成的唯一标识（如 {@code UUID}；空值即缺陷）
     * @param reason       原因（审计；会随 {@code reason} 字段上线路由，便于运维追溯）
     * @param operatorId   操作人
     * @return 下发回执
     */
    public AckResult suspendOpenOnly(int subAccountId, String suspendId, String reason,
                                     long operatorId) {
        requireSubAccount(subAccountId);
        if (suspendId == null || suspendId.isEmpty()) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "挂起标识不得为空（HGC-1.6：内部标识须由调用方传入）：subAccountId=" + subAccountId
                            + "；复现=调用方生成唯一标识（如 UUID）后传入");
        }
        ParamChange change = new ParamChange(ParamChange.Type.CMD_SET_TRADING_SUSPEND, 0L, operatorId)
                .with(KEY_SUB_ACCOUNT_ID, String.valueOf(subAccountId))
                .with(KEY_SCAN_SCOPE, SCAN_SCOPE_OPEN_ONLY)
                .with(KEY_SUSPENDED, "true")
                .with(KEY_REASON, (reason == null ? "" : reason)
                        + "（Java 内部挂起标识 suspend_id=" + suspendId + "，按 HGC-1.6 不下发）")
                .with(KEY_OPERATOR_ID, String.valueOf(operatorId));
        outstandingSuspends.computeIfAbsent(subAccountId, key -> new AtomicInteger()).incrementAndGet();
        activeSuspendIds.computeIfAbsent(subAccountId,
                key -> new java.util.concurrent.ConcurrentLinkedDeque<>()).push(suspendId);
        AckResult ack;
        try {
            ack = dispatch(change).join();
        } catch (RuntimeException e) {
            decrementSuspends(subAccountId);
            popSuspendId(subAccountId);
            throw e;
        }
        if (!ack.acknowledged) {
            decrementSuspends(subAccountId);
            popSuspendId(subAccountId);
            requireEffective(change, ack);
        }
        return ack;
    }

    /**
     * 撤下**本次**挂起（配对语义，{@code §3.2.1} 冻结）。
     *
     * <p>关键行为：**仅当该子账户"未撤销挂起"计数归零时才下发** {@code suspended="false"}；
     * 若仍有其他挂起来源（计数 &gt; 0），本方法**不下发解冻**并返回一个"未下发"的合成回执
     * —— 否则会误放行他人挂起（{@code R-RISK-5} 语义被破坏）。
     *
     * @param subAccountId 子账户（&gt; 0）
     * @param reason       原因（审计）
     * @param operatorId   操作人
     * @return 实际下发时的组件回执；未下发时为合成回执（{@code acknowledged=false} 且
     *         {@code message} 说明"仍在挂起中，未下发解冻"）
     */
    public AckResult releaseOpenOnly(int subAccountId, String reason, long operatorId) {
        return releaseOpenOnly(subAccountId, peekSuspendId(subAccountId), reason, operatorId);
    }

    /**
     * 按**指定挂起标识**撤下挂起（配对撤销；{@code CONTRACTS §3.2.1} 冻结要求）。
     *
     * @param subAccountId 子账户（&gt; 0；**禁止传 "0" 解冻**——那是全账户解冻）
     * @param suspendId    本次要撤下的挂起标识（须为 {@link #suspendOpenOnly} 返回/记录的那一个）
     * @param reason       原因（审计）
     * @param operatorId   操作人
     * @return 实际下发时的组件回执；仍有其他挂起时返回"未下发"合成回执
     */
    public AckResult releaseOpenOnly(int subAccountId, String suspendId, String reason,
                                     long operatorId) {
        requireSubAccount(subAccountId);
        if (suspendId == null || suspendId.isEmpty()) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "解冻必须回传本次挂起标识（suspend_id 缺失即缺陷）：subAccountId=" + subAccountId
                            + "；复现=先 suspendOpenOnly 取得标识，再按该标识解冻"
                            + "（不得解冻该子账户全部挂起，CONTRACTS §3.2.1）");
        }
        if (!popSuspendId(subAccountId, suspendId)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "该挂起标识不在未撤销集合中（未知 / 已被撤销 / 不属于本子账户），拒绝解冻："
                            + "subAccountId=" + subAccountId + " suspendId=" + suspendId
                            + "；复现=用 suspendOpenOnly 返回/记录的那个标识，且不得重复撤销"
                            + "（HGC-1.6：配对撤销须精确定位，禁止退化为解冻全部挂起）");
        }
        int remaining = decrementSuspends(subAccountId);
        if (remaining > 0) {
            return syntheticNotSent("该子账户仍有 " + remaining + " 个未撤销挂起，本次未下发解冻"
                    + "（suspended=false 仅在所有挂起撤销后发出，CONTRACTS §3.2.1）");
        }
        ParamChange change = new ParamChange(ParamChange.Type.CMD_SET_TRADING_SUSPEND, 0L, operatorId)
                .with(KEY_SUB_ACCOUNT_ID, String.valueOf(subAccountId))
                .with(KEY_SCAN_SCOPE, SCAN_SCOPE_OPEN_ONLY)
                .with(KEY_SUSPENDED, "false")
                .with(KEY_REASON, (reason == null ? "" : reason)
                        + "（配对撤销 Java 内部挂起标识 suspend_id=" + suspendId
                        + "，按 HGC-1.6 不下发）")
                .with(KEY_OPERATOR_ID, String.valueOf(operatorId));
        AckResult ack = dispatch(change).join();
        requireEffective(change, ack);
        return ack;
    }

    /**
     * 该子账户当前"未撤销挂起"计数（可观测性；协议无令牌字段，故计数即状态）。
     *
     * @param subAccountId 子账户
     * @return 未撤销挂起数
     */
    public int outstandingSuspends(int subAccountId) {
        AtomicInteger counter = outstandingSuspends.get(subAccountId);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 限额下调两阶段（{@code §9 RSK-04}；{@code CONTRACTS §3.2.1} 配对语义）。
     *
     * <pre>
     * ① 冻结  CMD_SET_TRADING_SUSPEND  suspended=true   scan_scope=OPEN_ONLY  sub_account_id=X
     * ② 换快照 CMD_UPDATE_QUOTA        limit_layer=…   sub_account_id=X（层③ 用 "0"）  quota_limit=新值
     * ③ 解冻  CMD_SET_TRADING_SUSPEND  suspended=false  scan_scope=OPEN_ONLY  sub_account_id=X  ← 配对撤销
     * </pre>
     *
     * @param subAccountId 子账户（层③传 0 表示账户级；本方法传真实子账户）
     * @param productId    品种
     * @param limitLayer   层级（{@code SUB_PRODUCT} / {@code SUB_INSTRUMENT} / {@code ACCOUNT_PRODUCT}）
     * @param newLimit     新额度上限（字符串化十进制）
     * @param effectiveFrom 生效起始日（{@code yyyy-MM-dd}）
     * @param operatorId   操作人
     * @return 三个阶段实际下发的回执（顺序固定）
     */
    public List<AckResult> decreaseQuotaTwoPhase(int subAccountId, long productId, String limitLayer,
                                                 String newLimit, String effectiveFrom,
                                                 long operatorId) {
        requireSubAccount(subAccountId);
        if (!LIMIT_LAYERS.contains(limitLayer)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "限额层级只允许 " + LIMIT_LAYERS + "：实际=" + limitLayer
                            + "；复现=按 CFG-16 三层口径提交");
        }
        List<AckResult> acks = new ArrayList<>();
        acks.add(suspendOpenOnly(subAccountId, "限额下调两阶段：冻结新开仓", operatorId));
        String suspendId = peekSuspendId(subAccountId);   // ③ 必须回传同一标识（配对撤销）

        ParamChange quota = new ParamChange(ParamChange.Type.CMD_UPDATE_QUOTA, 0L, operatorId)
                .with(KEY_LIMIT_LAYER, limitLayer)
                .with(KEY_SUB_ACCOUNT_ID, "ACCOUNT_PRODUCT".equals(limitLayer)
                        ? ALL_ACCOUNT_SENTINEL : String.valueOf(subAccountId))
                .with(KEY_PRODUCT_ID, String.valueOf(productId))
                .with(KEY_QUOTA_LIMIT, newLimit)
                .with(KEY_EFFECTIVE_FROM, effectiveFrom)
                .with(KEY_OPERATOR_ID, String.valueOf(operatorId));
        AckResult quotaAck = dispatch(quota).join();
        acks.add(quotaAck);
        if (!quotaAck.acknowledged) {
            try {
                acks.add(releaseOpenOnly(subAccountId, suspendId, "额度变更未生效，撤下本次挂起",
                        operatorId));
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "额度变更失败后撤下挂起失败（须人工介入）：subAccountId="
                        + subAccountId + "；原因=" + e.getMessage(), e);
            }
            requireEffective(quota, quotaAck);   // 抛出（附未生效口径）
        }

        AckResult releaseAck = releaseOpenOnly(subAccountId, suspendId, "限额下调两阶段：撤下挂起",
                operatorId);
        acks.add(releaseAck);
        return acks;
    }

    /**
     * 巡检未生效的下发（{@code dispatch_status ∈ {PENDING, FAIL, UNCONFIRMED}}）。
     *
     * @return 待重发列表
     */
    public List<ParamDispatch> inspectProblems() {
        return paramDispatchDao.findProblems();
    }

    /**
     * 下发指定用户的权限快照（{@code CMD_UPDATE_PERMISSION}；{@code T-168}）。
     *
     * <p><b>派生规则（派单权威表，逐键）</b>：
     * <ul>
     *   <li>{@code is_enterprise_leader} ← {@code t_user.is_enterprise_leader}（{@code 'Y'}）；</li>
     *   <li>{@code is_management_role} ← {@code t_user_role → t_role.role_code = 'APPROVER'}（禁止类）；</li>
     *   <li>{@code has_risk_role} ← {@code role_code = 'FIN_RISK'}（禁止类）；</li>
     *   <li>{@code has_settle_role} ← {@code role_code = 'SETTLER'}（禁止类）；</li>
     *   <li>{@code has_trading_post} ← 任一启用角色 {@code has_trading_operation ∈ {'Y','1'}}
     *       （<b>准入类</b>，方向与上三者相反，{@code CH-019}；状态口径与
     *       {@code AuthService.hasTradingRole} 同形）。</li>
     * </ul>
     *
     * <p><b>条目粒度裁定（本单核心，{@code CONTRACTS §3.2.1} ⚠️ 块）</b>：
     * <b>按 {@code operator_id} 聚合为一个 entry，用户跨多子账户的授权行合并进同一 entry</b>。
     * 依据：① 契约 ⚠️ 块明文「同一 {@code operator_id} 跨多个 {@code sub_account_id}
     * 必须落在同一 entry 内，L3 组装方必须按 {@code operator_id} 聚合后再发」；
     * ② 引擎侧 {@code PermissionSnapshot} 为单键 {@code unordered_map<uint32_t, PermissionEntry>}
     * （键 = {@code operator_id}，后写覆盖先写，{@code load_batch.h:96} /
     * {@code risk_snapshot.cc:87}）——按「一行一 entry」发多行会<b>静默只剩最后一行</b>，
     * 其余子账户授权被静默丢弃；③ 协议 {@code PermissionEntry.sub_account_id}（字段 2）为
     * <b>单值 uint32</b>（{@code hgcp.proto:719}），跨子账户的「多值」无法在单 entry 内承载。
     * ⇒ <b>每用户恰发一个 entry</b>：{@code sub_account_id} 承载该用户<b>授权行的最小子账户 ID</b>
     * （协议必填字段须取值；引擎侧 {@code T-038} 装填时真正的子账户全集须经
     * {@code putPermission} 之外的跨侧承载解决——该缺口已在回报中披露，属 {@code T-038} 域），
     * {@code product_ids} 承载该用户<b>全部授权品种</b>的并集（按 ID 升序、去重）。
     * {@code quota_limit}：授权行额度在本协议中无逐品种承载位，且 {@code PermissionEntry.quota_limit}
     * 语义为单值个人额度——各行额度不一致时发任意一行值都会歪曲语义，故<b>不发该键</b>（选填），
     * 个人额度仍由 {@code t_authorization} 直查校验（既有 {@code evalPermission}
     * {@code individual_quota} 路径在 {@code T-038} 装填前本就不经本指令生效）。
     *
     * <p>无任何 {@code EFFECTIVE} 授权行时抛 {@code 10001}（拒绝发「空授权」快照——
     * 空品种集合语义歧义且会覆盖引擎侧既有权限，安全默认 = 不发）。
     *
     * @param userId     被授权用户（快照主体）
     * @param operatorId 操作人（留痕）
     * @return 回执
     */
    @Override
    public CompletableFuture<AckResult> dispatchPermissionSnapshot(long userId, long operatorId) {
        if (userAccountDao == null || roleDao == null || userRoleDao == null
                || authorizationDao == null) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "权限快照派生的读库依赖未装配（构造未接四个权限 DAO），拒绝下发"
                            + "（不得静默发空快照）：userId=" + userId
                            + "；复现=以全量构造装配 ParamDispatchServiceImpl（T-168）");
        }
        UserAccount user = userAccountDao.findById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "被授权用户不存在：userId=" + userId
                            + "；复现=核对 t_user 主键（快照主体须真实存在）");
        }
        boolean enterpriseLeader = "Y".equals(user.isEnterpriseLeader);

        boolean managementRole = false;
        boolean riskRole = false;
        boolean settleRole = false;
        boolean tradingPost = false;
        for (UserRole userRole : userRoleDao.findByUser(userId)) {
            Role role = roleDao.findById(userRole.roleId);
            if (role == null) {
                continue;
            }
            String code = role.roleCode == null ? "" : role.roleCode.trim();
            if ("APPROVER".equals(code)) {
                managementRole = true;
            } else if ("FIN_RISK".equals(code)) {
                riskRole = true;
            } else if ("SETTLER".equals(code)) {
                settleRole = true;
            }
            if ("ENABLED".equals(role.status)
                    && ("Y".equals(role.hasTradingOperation) || "1".equals(role.hasTradingOperation))) {
                tradingPost = true;
            }
        }

        List<Authorization> rows = authorizationDao.findEffective(userId, Clocks.today());
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "该用户无任何 EFFECTIVE 授权行，拒绝发空权限快照（空集合语义歧义且会覆盖"
                            + "引擎侧既有权限，安全默认 = 不发）：userId=" + userId
                            + "；复现=先在 t_authorization 配置有效授权（T-168 派生前提）");
        }
        // 条目粒度：按 operator_id 聚合 —— 单 entry；sub_account_id 取最小授权子账户
        // （协议字段 2 单值必填），product_ids 取全部授权品种并集（升序去重）。
        String entryPrefix = "entries[0].";
        Map<String, String> entry = permissionEntryPayload(userId, rows, enterpriseLeader,
                managementRole, riskRole, settleRole, tradingPost);
        ParamChange change = new ParamChange(ParamChange.Type.CMD_UPDATE_PERMISSION, 0L, operatorId);
        for (Map.Entry<String, String> kv : entry.entrySet()) {
            change.payload.put(entryPrefix + kv.getKey(), kv.getValue());
        }
        return dispatch(change);
    }

    /**
     * 单个权限条目的 payload（{@code CONTRACTS §3.2.1} 冻结 9 键；8 必填 + {@code quota_limit}
     * 选填不发）。5 布尔在此逐键落位——{@code TE-CH019-001} 承接的<b>派生侧守卫</b>断言即锚此处。
     */
    private static Map<String, String> permissionEntryPayload(long operatorId,
                                                              List<Authorization> rows,
                                                              boolean enterpriseLeader,
                                                              boolean managementRole,
                                                              boolean riskRole,
                                                              boolean settleRole,
                                                              boolean tradingPost) {
        int minSubAccount = Integer.MAX_VALUE;
        java.util.Set<Long> products = new LinkedHashSet<>();
        for (Authorization row : rows) {
            minSubAccount = Math.min(minSubAccount, row.subAccountId);
            products.add(row.productId);
        }
        StringBuilder productIds = new StringBuilder();
        java.util.List<Long> sorted = new ArrayList<>(products);
        java.util.Collections.sort(sorted);
        for (Long pid : sorted) {
            if (productIds.length() > 0) {
                productIds.append(',');
            }
            productIds.append(pid);
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("operator_id", String.valueOf(operatorId));
        m.put("sub_account_id", String.valueOf(minSubAccount));
        m.put("product_ids", productIds.toString());
        m.put("is_enterprise_leader", String.valueOf(enterpriseLeader));
        m.put("is_management_role", String.valueOf(managementRole));
        m.put("has_risk_role", String.valueOf(riskRole));
        m.put("has_settle_role", String.valueOf(settleRole));
        m.put("has_trading_post", String.valueOf(tradingPost));
        return m;
    }

    // ------------------------------------------------------------ 内部

    /**
     * 分配 {@code param_version}（契约 §3.2.2：<b>必须由服务层串行化</b>）。
     *
     * @return 单调递增的版本号
     */
    private long allocateParamVersion() {
        if (versionCounter.get() < 0L) {
            synchronized (versionLock) {
                if (versionCounter.get() < 0L) {
                    versionCounter.set(paramDispatchDao.maxParamVersion() + 1L);
                }
            }
        }
        return versionCounter.getAndIncrement();
    }

    /**
     * 生成一个挂起标识（{@code HGC-1.6}：内部关联标识的**唯一推荐铸造入口**）。
     *
     * <p>调用方先铸造、再经 {@link #suspendOpenOnly(int, String, String, long)} 传入并**自持**，
     * 之后即可用同一标识精确撤销本次挂起 —— 无需解析任何报文文本、也不存在
     * {@link #peekSuspendId} 的并发竞态。
     *
     * @return 全局唯一标识（UUID）
     */
    public static String newSuspendId() {
        return java.util.UUID.randomUUID().toString();
    }

    /** 取该子账户最近一次未撤销的挂起标识（LIFO）；无则 {@code null}。 */
    public String peekSuspendId(int subAccountId) {
        java.util.Deque<String> stack = activeSuspendIds.get(subAccountId);
        return stack == null ? null : stack.peekFirst();
    }

    private void popSuspendId(int subAccountId) {
        java.util.Deque<String> stack = activeSuspendIds.get(subAccountId);
        if (stack != null) {
            stack.pollFirst();
        }
    }

    /**
     * 从"未撤销挂起"集合中**摘除**指定标识。
     *
     * <p>语义收紧（{@code HGC-1.6} 推论）：标识**不在此集合中**（未知 / 已被撤销 / 属他人）
     * 时**不得静默通过** —— 否则会误减计数并可能放开他人挂起（{@code R-RISK-5}）。
     *
     * @param subAccountId 子账户
     * @param suspendId    挂起标识
     * @return 确实摘除返回 {@code true}；标识不存在返回 {@code false}
     */
    private boolean popSuspendId(int subAccountId, String suspendId) {
        java.util.Deque<String> stack = activeSuspendIds.get(subAccountId);
        return stack != null && stack.removeFirstOccurrence(suspendId);
    }

    private int decrementSuspends(int subAccountId) {
        AtomicInteger counter = outstandingSuspends.computeIfAbsent(subAccountId,
                key -> new AtomicInteger());
        return counter.updateAndGet(value -> value > 0 ? value - 1 : 0);
    }

    /**
     * 参数下发类型的<b>冻结域</b>取值（{@code t_param_dispatch.dispatch_type}；
     * {@code m3_risk.sql:53-55} / {@code DATA_MODEL.md} TB-RISK-2）。
     *
     * <p>域内 8 值<b>无</b> {@code CMD_} 前缀，而枚举名为线格式控制指令名（带前缀）；
     * 故持久化统一去前缀映射（{@code TE-T058-002}：全仓唯一 {@code .name()} 持久化点）。
     * 说明：{@code CMD_SET_READY_STATE}/{@code CMD_TRIGGER_REPLAY}/{@code CMD_RELOAD}
     * 三值在冻结域内<b>无</b>对应取值（域缺口，生产未构造，另行登记，不在本单范围）。
     */
    private static String dispatchTypeOf(ParamChange.Type type) {
        String name = type.name();
        return name.startsWith("CMD_") ? name.substring("CMD_".length()) : name;
    }

    private ParamDispatch persistPending(ParamChange change, long version) {
        ParamDispatch row = new ParamDispatch();
        row.paramVersion = version;
        row.dispatchType = dispatchTypeOf(change.type);
        row.subAccountId = subAccountIdOf(change);
        row.batchIndex = 1;
        row.batchTotal = 1;
        row.payloadDigest = digest(change.payload);
        row.recordCount = change.payload.size();
        row.dispatchStatus = "PENDING";
        row.retryCount = 0;
        row.operatorId = change.operatorId;
        row.dispatchTime = Clocks.now();
        tx.inTransaction(connection -> {
            if (paramDispatchDao.insert(row, change.operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "下发留痕写入失败（先落库再下发纪律）：paramVersion=" + version
                                + " type=" + change.type + "；复现=核对 t_param_dispatch 约束");
            }
            return null;
        });
        return row;
    }

    private AckResult settle(ParamDispatch row, ParamChange change, long version, AckResult ack,
                             Throwable error) {
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            boolean timeout = cause instanceof java.util.concurrent.TimeoutException;
            String detail = timeout
                    ? ("回执超时（" + ackTimeout.toMillis() + " ms，CFG-28）→ 未生效，须巡检重发")
                    : ("下发异常：" + cause.getClass().getSimpleName() + "：" + cause.getMessage());
            // 超时/无回执 ⇒ ackTime 为 null（未收到回执就不得写回执时间，CONTRACTS §3.2.1）
            complete(row, timeout ? "UNCONFIRMED" : "FAIL", detail, timeout ? null : Clocks.now());
            LOG.warning("参数下发未生效：type=" + change.type + " paramVersion=" + version + "；" + detail);
            return synthetic(change, version, timeout);
        }
        if (ack == null) {
            complete(row, "UNCONFIRMED", "组件返回空回执 → 未生效", null);
            return synthetic(change, version, false);
        }
        complete(row, ack.acknowledged ? "SUCCESS" : "FAIL", ack.message == null ? "" : ack.message,
                Clocks.now());
        return ack;
    }

    private void complete(ParamDispatch row, String status, String detail,
                          LocalDateTime ackTime) {
        try {
            tx.inTransaction(connection -> {
                paramDispatchDao.updateAck(row.id, status, ackTime, detail, row.retryCount);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "下发结果回写失败（留痕与内存不一致，须巡检）：id=" + row.id
                    + " status=" + status + "；原因=" + e.getMessage(), e);
        }
    }

    private static AckResult synthetic(ParamChange change, long version, boolean timeout) {
        AckResult ack = new AckResult();
        ack.acknowledged = false;
        ack.paramVersion = version;
        ack.message = timeout ? "回执超时（未生效）" : "回执缺失（未生效）";
        ack.ackTime = Clocks.now().toString();
        return ack;
    }

    private static AckResult syntheticNotSent(String message) {
        AckResult ack = new AckResult();
        ack.acknowledged = false;
        ack.message = message;
        return ack;
    }

    private static Integer subAccountIdOf(ParamChange change) {
        String raw = change.payload == null ? null : change.payload.get(KEY_SUB_ACCOUNT_ID);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "payload 的 " + KEY_SUB_ACCOUNT_ID + " 非整数：" + raw
                            + "；复现=按契约以十进制字符串携带子账户 ID", e);
        }
    }

    private static void requireSubAccount(int subAccountId) {
        if (subAccountId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "须指定真实子账户（> 0）：实际=" + subAccountId
                            + "；复现=传真实子账户 ID（哨兵值 0 表示全账户，仅由协议层语义承载）");
        }
    }

    /** payload 摘要（SHA-256 十六进制小写，{@code DATA_MODEL.md} §0.2 哈希/校验值口径）。 */
    private static String digest(Map<String, String> payload) {
        Map<String, String> sorted = new TreeMap<>(payload == null ? Map.of() : payload);
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            text.append(entry.getKey()).append('=').append(entry.getValue()).append('&');
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用（JDK 11 内置）：复现=核对 JDK 安装", e);
        }
    }
}
