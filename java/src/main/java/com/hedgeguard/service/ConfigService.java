package com.hedgeguard.service;

import com.google.gson.Gson;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AccountSubjectDao;
import com.hedgeguard.dao.ConfigChangeLogDao;
import com.hedgeguard.dao.ConfigItemDao;
import com.hedgeguard.dao.CtpFrontConfigDao;
import com.hedgeguard.dao.DictItemDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ParamDispatchDao;
import com.hedgeguard.dao.ReportPeriodConfigDao;
import com.hedgeguard.dao.SubAccountDao;
import com.hedgeguard.dao.entity.AccountSubject;
import com.hedgeguard.dao.entity.ConfigChangeLog;
import com.hedgeguard.dao.entity.ConfigItem;
import com.hedgeguard.dao.entity.CtpFrontConfig;
import com.hedgeguard.dao.entity.DictItem;
import com.hedgeguard.dao.entity.ParamDispatch;
import com.hedgeguard.dao.entity.ReportPeriodConfig;
import com.hedgeguard.dao.entity.SubAccount;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置与子账户服务（{@code T-008} 子账户全生命周期 + {@code T-009} 系统配置与零落盘约束）。
 *
 * <p>依据：{@code DATA_MODEL.md} §2.1（{@code TB-CONFIG-1/2/3}）、
 * {@code ARCHITECTURE.md} §11.1（{@code CFG-02c} 子账户分类 / {@code CFG-10} 子账户生命周期纪律 /
 * {@code CFG-19/20/22} 绑定与零落盘）、{@code REQUIREMENTS/INDEX.md} §9.1。
 *
 * <p>三条硬约束在本类中的落点：
 * <ol>
 *   <li><b>敏感字段零落盘</b>（{@code NFR-SEC-1} / {@code R-CONFIG-2}）：本类<b>不</b>提供任何写入
 *       {@code password} / {@code auth_code} / {@code fund_password} / {@code auth_token} 取值的路径——
 *       命中即 {@code 10020}；敏感项只能变更 {@code sensitive_state}（{@code UNSET}/{@code SET}），
 *       取值由环境变量在运行期直接注入组件，永不经过本服务、永不入库。</li>
 *   <li><b>绑定地址仅回环</b>（{@code FR-CONFIG-9} / {@code CFG-20}）：{@link #requireLoopbackBind} 为
 *       启动期与配置变更期的同一校验入口，非 {@code 127.0.0.1} 即 {@code 10021}。</li>
 *   <li><b>子账户不可物理删除</b>（{@code CFG-10}）：本类<b>只有</b> {@link #disableSubAccount} 一个
 *       下线入口，条件为「资金 0 + 持仓 0 + 无在途指令 + 当日已结算」四条同时满足（{@code DISABLE_ONLY}）；
 *       停用后行仍在库中——历史数据只读且<b>仍参与加总对账</b>。</li>
 * </ol>
 *
 * <p>事务边界：本类在 L3 开启事务（{@code ARCHITECTURE.md} §3.8），"改值 + 留痕"必须同事务。
 * 时间一律经 {@link Clocks}（{@code NFR-TEST-4} 可注入时钟）。
 */
public final class ConfigService {

    /** 子账户分类取值域（{@code CFG-02c}）。 */
    public static final Set<String> SUB_ACCOUNT_CATEGORIES =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("COMMODITY", "TRADE")));

    /** 子账户生命周期留痕的伪 Key（对应 {@code CFG-10 sub_account.lifecycle}）。 */
    public static final String KEY_SUB_ACCOUNT_LIFECYCLE = "sub_account.lifecycle";

    /** 子账户分类留痕的伪 Key（对应 {@code CFG-02c sub_account.category}）。 */
    public static final String KEY_SUB_ACCOUNT_CATEGORY = "sub_account.category";

    /** 留痕分类（{@code t_config_change_log.config_category} 取值域之一）。 */
    public static final String CATEGORY_SUB_ACCOUNT = "SUB_ACCOUNT";

    /**
     * 敏感字段 Key 特征（{@code NFR-SEC-1}、{@code PROJECT.md} §10-4）。
     *
     * <p>命中即拒绝任何"存值"动作；本集合同时也是自检脚本的扫描清单
     * （{@code NFR-SEC-1} 全库零命中）的取值来源。
     */
    public static final Set<String> SENSITIVE_KEY_MARKERS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    "password", "auth_code", "fund_password", "auth_token")));

    /**
     * 必须为回环地址的<b>绑定键</b>（{@code FR-CONFIG-9} 红线 9）。
     *
     * <p>键名以 {@code ARCHITECTURE.md} §11.2 现行配置清单为唯一事实源（{@code HGC-1.3} 对齐）：
     * <ul>
     *   <li>{@code hg.tcp.bind}（{@code CFG-20}，HGCP 组件通道）；</li>
     *   <li>{@code hg.web.bind}（{@code CFG-33}，前端 HTTP 服务）。</li>
     * </ul>
     * 说明：已废止的 {@code hg.http.bind} 与不存在的 {@code hg.ws.bind} 均<b>不</b>列入。
     * WebSocket 无独立绑定键（{@code CFG-32} 仅 {@code hg.web.ws.port}/{@code _heartbeat_ms}/
     * {@code _max_connections}），其"仅回环"由 {@code §4.2.1} 正文约束 + 实现侧强制。
     */
    public static final Set<String> LOOPBACK_BIND_KEYS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    "hg.tcp.bind", "hg.web.bind")));

    /** 唯一允许的绑定地址。 */
    public static final String LOOPBACK_ONLY = "127.0.0.1";

    // ------------------------------------------------------------ T-109「已声明未注册」端点的取值域（FR 原文）

    /** CTP 环境类型取值域（{@code FR-CONFIG-1}/{@code FR-CONFIG-10}；{@code CFG-22}）。 */
    public static final Set<String> CTP_ENV_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("SIMULATION", "PRODUCTION")));

    /** CTP 前置类型取值域（{@code FR-CONFIG-10}：行情 / 交易可分别配置）。 */
    public static final Set<String> CTP_FRONT_TYPES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("TRADE", "MD")));

    /** 字典项状态取值域（{@code FR-CONFIG-14}：停用代替删除）。 */
    public static final Set<String> DICT_STATUSES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("ENABLED", "DISABLED")));

    /** 会计科目余额方向（{@code FR-CONFIG-14}；与 {@code ck_account_subject_direction} 一致）。 */
    public static final Set<String> SUBJECT_DIRECTIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("DEBIT", "CREDIT")));

    /** 会计科目适用业务场景（{@code FR-CONFIG-14}；与 {@code ck_account_subject_biz_scene} 一致）。 */
    public static final Set<String> SUBJECT_BIZ_SCENES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("OPEN_POSITION", "CLOSE_PNL", "COMMISSION",
                    "MARGIN_CHANGE", "FLOAT_PNL", "PREMIUM", "HEDGE_EFFECT", "OTHER")));

    /** 报告周期单位（{@code FR-CONFIG-15}；与 {@code ck_report_period_unit} 一致）。 */
    public static final Set<String> REPORT_PERIOD_UNITS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("DAY", "MONTH", "QUARTER", "YEAR")));

    /** 配置导入模式（{@code FR-CONFIG-13}：覆盖 / 仅新增）。 */
    public static final Set<String> IMPORT_MODES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("OVERWRITE", "INSERT_ONLY")));

    private static final Gson GSON = new Gson();

    private final TxTemplate tx;
    private final SubAccountDao subAccountDao;
    private final ConfigItemDao configItemDao;
    private final ConfigChangeLogDao configChangeLogDao;
    private final DictItemDao dictItemDao;
    private final AccountSubjectDao accountSubjectDao;
    private final ReportPeriodConfigDao reportPeriodConfigDao;
    private final CtpFrontConfigDao ctpFrontConfigDao;
    private final ParamDispatchDao paramDispatchDao;

    /**
     * 构造（{@code T-008}/{@code T-009} 既有签名，向后兼容——新增检索型 DAO 为 {@code null}）。
     *
     * @param tx                事务模板
     * @param subAccountDao     子账户 DAO
     * @param configItemDao     配置项 DAO
     * @param configChangeLogDao 配置变更留痕 DAO
     */
    public ConfigService(TxTemplate tx, SubAccountDao subAccountDao, ConfigItemDao configItemDao,
                         ConfigChangeLogDao configChangeLogDao) {
        this(tx, subAccountDao, configItemDao, configChangeLogDao, null, null, null, null, null);
    }

    /**
     * 构造（{@code T-109} 声明面补齐所需的全量依赖）。
     *
     * @param tx                     事务模板
     * @param subAccountDao          子账户 DAO
     * @param configItemDao          配置项 DAO
     * @param configChangeLogDao     配置变更留痕 DAO
     * @param dictItemDao            枚举字典 DAO
     * @param accountSubjectDao      会计科目字典 DAO
     * @param reportPeriodConfigDao  报告周期配置 DAO
     * @param ctpFrontConfigDao      CTP 前置配置 DAO
     * @param paramDispatchDao       参数下发记录 DAO
     */
    public ConfigService(TxTemplate tx, SubAccountDao subAccountDao, ConfigItemDao configItemDao,
                         ConfigChangeLogDao configChangeLogDao, DictItemDao dictItemDao,
                         AccountSubjectDao accountSubjectDao,
                         ReportPeriodConfigDao reportPeriodConfigDao,
                         CtpFrontConfigDao ctpFrontConfigDao, ParamDispatchDao paramDispatchDao) {
        this.tx = tx;
        this.subAccountDao = subAccountDao;
        this.configItemDao = configItemDao;
        this.configChangeLogDao = configChangeLogDao;
        this.dictItemDao = dictItemDao;
        this.accountSubjectDao = accountSubjectDao;
        this.reportPeriodConfigDao = reportPeriodConfigDao;
        this.ctpFrontConfigDao = ctpFrontConfigDao;
        this.paramDispatchDao = paramDispatchDao;
    }

    // ------------------------------------------------------------ T-009 系统配置

    /**
     * 新增配置项（{@code FR-CONFIG-11}：配置项可在界面维护，新增不需要改表结构）。
     *
     * @param item       配置项（{@code configKey} / {@code configCategory} / {@code valueType} 必填）
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long createConfigItem(ConfigItem item, long operatorId) {
        requireText(item.configKey, "config_key");
        requireText(item.configCategory, "config_category");
        requireText(item.valueType, "value_type");
        if (configItemDao.findByKey(item.configKey) != null) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "配置项已存在：config_key=" + item.configKey + "；复现=改用修改接口");
        }
        // 敏感项在结构上只能存状态：任何取值一律拒绝（NFR-SEC-1）
        if (isSensitiveKey(item.configKey) || "Y".equals(item.isSensitive)) {
            item.isSensitive = "Y";
            if (item.configValue != null) {
                throw sensitiveRejected(item.configKey);
            }
            if (item.sensitiveState == null) {
                item.sensitiveState = "UNSET";
            }
        }
        item.isSensitive = item.isSensitive == null ? "N" : item.isSensitive;
        item.dispatchToCtp = item.dispatchToCtp == null ? "N" : item.dispatchToCtp;
        item.doubleReviewRequired = item.doubleReviewRequired == null ? "N" : item.doubleReviewRequired;
        if (item.persistScope == null) {
            item.persistScope = "LOCAL_ADMIN";
        }
        int affected = tx.inTransaction(connection -> {
            int n = configItemDao.insert(item, operatorId);
            writeChangeLog(item, null, null, "CREATE", operatorId, null, "NOT_REQUIRED", null);
            return n;
        });
        if (affected != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "配置项新增影响行数异常：" + affected + "；复现=核对 t_config_item 约束");
        }
        return item.id;
    }

    /**
     * 读取配置项。
     *
     * @param configKey 配置键
     * @return 配置项；不存在返回 {@code null}
     */
    public ConfigItem getConfig(String configKey) {
        return configItemDao.findByKey(configKey);
    }

    /**
     * 按分类列出配置项（{@code FR-CONFIG-11} 分类浏览）。
     *
     * @param category 分类
     * @return 列表
     */
    public List<ConfigItem> listByCategory(String category) {
        return configItemDao.findByCategory(category);
    }

    /**
     * 列出全部需下发 C++ 的配置项（启动比对 / 全量重下发）。
     *
     * @return 列表
     */
    public List<ConfigItem> listDispatchable() {
        return configItemDao.findDispatchable();
    }

    /**
     * 变更配置取值（{@code FR-CONFIG-12}：变更留痕；{@code R-CONFIG-3}：下发未确认不得视为生效）。
     *
     * <p>处理顺序（任一失败即整笔回滚）：
     * ① 配置项必须存在；② 敏感项拒绝存值（{@code 10020}）；③ 绑定键回环校验（{@code 10021}）；
     * ④ 取值按 {@code value_type}/{@code enum_scope}/{@code min_value}/{@code max_value} 校验；
     * ⑤ 双人复核项须 {@code reviewerId} 且 {@code ≠ operatorId}；⑥ 推进 {@code effective_version} 并留痕。
     *
     * <p><b>下发结果口径（M1）</b>：M1 尚无 HGCP 控制通道（`T-042` 属 M2），故需下发的项一律记
     * {@code UNCONFIRMED}（未收到回执）——按 {@code R-CONFIG-3}，未确认<b>不得视为已生效</b>；
     * M2 由 {@code ParamDispatchServiceImpl} 回填 {@code SUCCESS}/{@code FAIL}。
     *
     * @param configKey  配置键
     * @param newValue   新取值（敏感项必须为 {@code null}）
     * @param operatorId 操作人
     * @param reviewerId 复核人（仅双人复核项需要）
     * @param reason     变更原因
     */
    public void changeConfigValue(String configKey, String newValue, long operatorId, Long reviewerId,
                                  String reason) {
        requireText(configKey, "config_key");
        ConfigItem item = configItemDao.findByKey(configKey);
        if (item == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "配置项不存在：config_key=" + configKey + "；复现=先在 /config 新增该配置项");
        }
        if ("Y".equals(item.isSensitive) || isSensitiveKey(configKey)) {
            if (newValue != null) {
                throw sensitiveRejected(configKey);
            }
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "敏感配置项不接受取值变更，只接受状态变更（UNSET/SET）：config_key=" + configKey
                            + "；复现=改调 sensitiveState 变更接口（设置项由环境变量注入）");
        }
        if (LOOPBACK_BIND_KEYS.contains(configKey)) {
            requireLoopbackBind(configKey, newValue);
        }
        validateValue(item, newValue);
        if ("Y".equals(item.doubleReviewRequired)) {
            if (reviewerId == null || reviewerId == operatorId) {
                throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                        "该配置项变更须双人复核：reviewer_id 必填且不得等于 operator_id；config_key="
                                + configKey + "；复现=换一名复核人后重新提交");
            }
        }
        String before = item.configValue;
        String dispatchResult = "Y".equals(item.dispatchToCtp) ? "UNCONFIRMED" : "NOT_REQUIRED";
        // param_version 由数据库侧单调推进（bumpEffectiveVersion），此处先算出本次生效版本供留痕
        long nextVersion = item.effectiveVersion + 1;
        tx.inTransaction(connection -> {
            item.configValue = newValue;
            if (configItemDao.updateById(item, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "配置项并发冲突（row_version 不匹配）：config_key=" + configKey
                                + "；复现=重新读取后再提交");
            }
            configItemDao.bumpEffectiveVersion(configKey);
            item.effectiveVersion = nextVersion;
            writeChangeLog(item, before, newValue, "UPDATE", operatorId, reviewerId, dispatchResult,
                    nextVersion);
            return null;
        });
    }

    /**
     * 变更敏感项的"设置状态"（{@code UNSET} / {@code SET}），<b>不</b>接收取值。
     *
     * <p>这是敏感项唯一可写路径：取值由环境变量在运行期注入，本系统只记录"是否已配置"。
     *
     * @param configKey      配置键
     * @param sensitiveState {@code UNSET} / {@code SET}
     * @param operatorId     操作人
     * @param reason         变更原因
     */
    public void changeSensitiveState(String configKey, String sensitiveState, long operatorId,
                                     String reason) {
        if (!"UNSET".equals(sensitiveState) && !"SET".equals(sensitiveState)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "敏感项状态只允许 UNSET/SET：实际=" + sensitiveState + "；复现=改正取值");
        }
        ConfigItem item = configItemDao.findByKey(configKey);
        if (item == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "配置项不存在：config_key=" + configKey + "；复现=先新增该配置项");
        }
        if (!"Y".equals(item.isSensitive) && !isSensitiveKey(configKey)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "非敏感项无敏感状态概念：config_key=" + configKey + "；复现=改用取值变更接口");
        }
        String beforeState = item.sensitiveState;
        tx.inTransaction(connection -> {
            item.sensitiveState = sensitiveState;
            if (configItemDao.updateById(item, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "配置项并发冲突：config_key=" + configKey + "；复现=重新读取后再提交");
            }
            ConfigChangeLog log = newLog(item, item.configKey, operatorId, null, reason);
            log.isSensitiveChange = "Y";
            log.sensitiveStateBefore = beforeState;
            log.sensitiveStateAfter = sensitiveState;
            log.dispatchResult = "NOT_REQUIRED";
            configChangeLogDao.insert(log, operatorId);
            return null;
        });
    }

    /**
     * 绑定地址回环白名单校验（{@code FR-CONFIG-9} / {@code CFG-20}）。
     *
     * <p>同一入口服务两个时点：<b>启动期</b>（装载配置后逐键校验，违规即拒绝启动并发告警）与
     * <b>变更期</b>（{@link #changeConfigValue} 内联调用）。
     *
     * @param configKey 配置键（须属 {@link #LOOPBACK_BIND_KEYS}，否则直接放行）
     * @param value     取值
     */
    public static void requireLoopbackBind(String configKey, String value) {
        if (!LOOPBACK_BIND_KEYS.contains(configKey)) {
            return;
        }
        if (!LOOPBACK_ONLY.equals(value)) {
            throw BizException.of(ErrorCode.SECURITY_CONFIG_REJECTED,
                    "绑定地址只允许回环：" + configKey + "=" + value
                            + "；复现=改为 127.0.0.1（服务仅监听本机，PROJECT.md §10 红线 9）");
        }
    }

    // ------------------------------------------------------------ T-008 子账户生命周期

    /**
     * 创建子账户（{@code T-008} 验收 1）。
     *
     * @param subAccount 子账户（{@code subAccountCode} / {@code subAccountName} / {@code category} 必填）
     * @param operatorId 操作人
     * @return 新行主键
     */
    public long createSubAccount(SubAccount subAccount, long operatorId) {
        requireText(subAccount.subAccountCode, "sub_account_code");
        requireText(subAccount.subAccountName, "sub_account_name");
        requireText(subAccount.category, "category");
        requireCategory(subAccount.category);
        if (subAccountDao.findByCode(subAccount.subAccountCode) != null) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "子账户编码已存在：" + subAccount.subAccountCode + "；复现=换编码或按编码查询现有行");
        }
        // 新建一律 ENABLED；停用是本服务唯一的下线入口（CFG-10 DISABLE_ONLY）
        subAccount.status = "ENABLED";
        subAccount.isDefault = subAccount.isDefault == null ? "N" : subAccount.isDefault;
        int affected = tx.inTransaction(connection -> {
            int n = subAccountDao.insert(subAccount, operatorId);
            ConfigChangeLog log = lifecycleLog(subAccount, operatorId, "CREATE",
                    null, subAccount.category, "新建子账户（分类=" + subAccount.category + "）");
            configChangeLogDao.insert(log, operatorId);
            return n;
        });
        if (affected != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "子账户新增影响行数异常：" + affected + "；复现=核对 t_sub_account 约束");
        }
        return subAccount.id;
    }

    /**
     * 停用子账户（{@code T-008} 验收 2/3；{@code CFG-10} {@code DISABLE_ONLY}）。
     *
     * <p>四条前置条件<b>同时</b>满足才允许停用：资金 0、持仓 0、无在途指令、当日已结算。
     * 不满足时抛 {@code 10012}（阻断类，四项齐备：规则编号 / 当前值 / 阈值 / 下一步），
     * 并把逐条判定结果写入 {@code disable_precheck_json} 供审计复现。
     *
     * <p>本服务<b>不提供</b>任何物理删除方法（{@code DATA_MODEL.md} §2.1 备注 + {@code CFG-10}）：
     * 停用后行保留，"历史数据永久只读且仍参与加总对账"；彻底清除仅提供"导出归档"。
     *
     * @param subAccountId 子账户 ID
     * @param reason       停用原因（必填，审计要求）
     * @param precheck     四条件判定端口（数据来自 FUND / TRADE / SETTLE 模块）
     * @param operatorId   操作人
     */
    public void disableSubAccount(int subAccountId, String reason, SubAccountDisablePrecheck precheck,
                                  long operatorId) {
        requireText(reason, "disabled_reason");
        SubAccount sa = subAccountDao.findById(subAccountId);
        if (sa == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "子账户不存在：id=" + subAccountId + "；复现=先按编码查询确认 ID");
        }
        if ("DISABLED".equals(sa.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "子账户已停用，无需重复操作：id=" + subAccountId + "；复现=查询状态列");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fundIsZero", precheck.fundIsZero(subAccountId));
        detail.put("positionsAreZero", precheck.positionsAreZero(subAccountId));
        detail.put("noInFlightOrders", precheck.hasNoInFlightOrders(subAccountId));
        detail.put("settledToday", precheck.settledToday(subAccountId));
        boolean pass = Boolean.TRUE.equals(detail.get("fundIsZero"))
                && Boolean.TRUE.equals(detail.get("positionsAreZero"))
                && Boolean.TRUE.equals(detail.get("noInFlightOrders"))
                && Boolean.TRUE.equals(detail.get("settledToday"));
        if (!pass) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "子账户停用前置条件不满足（DISABLE_ONLY）：id=" + subAccountId,
                    "CFG-10",
                    describePrecheck(detail),
                    "资金=0 且 持仓=0 且 无在途指令 且 当日已结算",
                    "先平掉持仓 / 撤销在途指令 / 完成当日结算 / 划出资金，再重试停用");
        }
        tx.inTransaction(connection -> {
            sa.status = "DISABLED";
            sa.disabledReason = reason;
            sa.disablePrecheckJson = GSON.toJson(detail);
            if (subAccountDao.updateById(sa, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "子账户并发冲突（row_version 不匹配）：id=" + subAccountId
                                + "；复现=重新读取后再提交");
            }
            ConfigChangeLog log = lifecycleLog(sa, operatorId, "DISABLE", "ENABLED", "DISABLED", reason);
            configChangeLogDao.insert(log, operatorId);
            return null;
        });
    }

    /**
     * 变更子账户分类（{@code T-008} 验收 4；{@code CFG-02c}：变更须留痕 + 双人复核）。
     *
     * <p>分类决定规模红线（商品类 90% / 贸易类 80%），故要求 {@code reviewerId} 必填且
     * {@code ≠ operatorId}（{@code ARCHITECTURE.md} §11.1 备注：变更由风控岗发起 + 财务负责人审批）。
     *
     * @param subAccountId 子账户 ID
     * @param newCategory  新分类（{@code COMMODITY} / {@code TRADE}）
     * @param operatorId   发起人
     * @param reviewerId   复核人（须 ≠ 发起人）
     * @param reason       变更原因
     */
    public void changeCategory(int subAccountId, String newCategory, long operatorId, long reviewerId,
                               String reason) {
        requireCategory(newCategory);
        if (reviewerId == operatorId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "子账户分类变更须双人复核：reviewer_id 不得等于 operator_id（id=" + subAccountId
                            + "）；复现=换一名复核人后重新提交");
        }
        SubAccount sa = subAccountDao.findById(subAccountId);
        if (sa == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "子账户不存在：id=" + subAccountId + "；复现=先按编码查询确认 ID");
        }
        if ("DISABLED".equals(sa.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "已停用子账户不得变更分类：id=" + subAccountId + "；复现=先恢复启用（若业务允许）");
        }
        String before = sa.category;
        if (newCategory.equals(before)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "新分类与当前分类相同，无需变更：category=" + before + "；复现=核对当前分类");
        }
        tx.inTransaction(connection -> {
            sa.category = newCategory;
            sa.categoryApprovedBy = reviewerId;
            if (subAccountDao.updateById(sa, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "子账户并发冲突：id=" + subAccountId + "；复现=重新读取后再提交");
            }
            ConfigChangeLog log = lifecycleLog(sa, operatorId, "UPDATE", before, newCategory, reason);
            log.configKey = KEY_SUB_ACCOUNT_CATEGORY;
            configChangeLogDao.insert(log, operatorId);
            return null;
        });
    }

    /**
     * 列出启用中的子账户（加总对账取全集 / 下拉框；不含保留系统行 {@code id=0}）。
     *
     * @return 列表
     */
    public List<SubAccount> listEnabled() {
        return subAccountDao.findEnabled();
    }

    /**
     * 按编码查子账户。
     *
     * @param code 子账户编码
     * @return 子账户；不存在返回 {@code null}
     */
    public SubAccount findSubAccountByCode(String code) {
        return subAccountDao.findByCode(code);
    }

    /**
     * 按 ID 查子账户（{@code T-135}；供停用端点回传停用后的视图）。
     *
     * @param id 子账户 ID
     * @return 子账户；不存在返回 {@code null}
     */
    public SubAccount findSubAccountById(int id) {
        return subAccountDao.findById(id);
    }

    // ------------------------------------------------------------ T-109 CONFIG 声明面补齐

    /**
     * 列出 CTP 前置配置（{@code FR-CONFIG-1}/{@code FR-CONFIG-10}）。
     *
     * <p><b>零落盘</b>：本表结构上无任何凭据列，故响应天然不含敏感字段。
     *
     * @param envType 环境类型（{@code SIMULATION}/{@code PRODUCTION}；{@code null} 表示全部）
     * @param query   分页参数
     * @return 分页结果
     */
    public PageResult<CtpFrontConfig> listCtpFronts(String envType, PageQuery query) {
        if (envType != null && !envType.isEmpty()) {
            requireIn(envType, CTP_ENV_TYPES, "envType");
            return ctpFrontConfigDao.findPage(query, "env_type = ?",
                    new ArrayList<Object>(Collections.singletonList(envType)));
        }
        return ctpFrontConfigDao.findPage(query, null, null);
    }

    /**
     * 新增 / 更新 CTP 前置配置（{@code FR-CONFIG-1}/{@code FR-CONFIG-10}）。
     *
     * <p>校验：环境类型、前置类型取值域；前置地址格式（{@code tcp://host:port}）；
     * 经纪商 / 用户代码必填。{@code id > 0} 走更新，否则新增。
     *
     * @param cfg        前置配置
     * @param operatorId 操作人
     * @return 主键
     */
    public long saveCtpFront(CtpFrontConfig cfg, long operatorId) {
        requireIn(cfg.envType, CTP_ENV_TYPES, "envType");
        requireIn(cfg.frontType, CTP_FRONT_TYPES, "frontType");
        requireText(cfg.frontAddr, "frontAddr");
        if (!cfg.frontAddr.matches("tcp://[^\\s/:]+:\\d{1,5}")) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "前置地址格式非法（须为 tcp://host:port）：frontAddr=" + cfg.frontAddr
                            + "；复现=改为 tcp://ip:port 后重试（FR-CONFIG-10 验收 1）");
        }
        requireText(cfg.brokerId, "brokerId");
        requireText(cfg.investorId, "investorId");
        cfg.isEnabled = cfg.isEnabled == null ? "Y" : cfg.isEnabled;
        if (!"Y".equals(cfg.isEnabled) && !"N".equals(cfg.isEnabled)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "isEnabled 只允许 Y/N：实际=" + cfg.isEnabled + "；复现=改正取值");
        }
        if (cfg.connectTimeoutMs <= 0) {
            cfg.connectTimeoutMs = 5000;
        }
        if (cfg.reconnectIntervalMs <= 0) {
            cfg.reconnectIntervalMs = 3000;
        }
        return tx.inTransaction(connection -> {
            if (cfg.id > 0) {
                CtpFrontConfig existing = ctpFrontConfigDao.findById(cfg.id);
                if (existing == null) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "CTP 前置配置不存在：id=" + cfg.id + "；复现=核对 id 或改为新增");
                }
                existing.envType = cfg.envType;
                existing.frontType = cfg.frontType;
                existing.frontAddr = cfg.frontAddr;
                existing.priority = cfg.priority;
                existing.brokerId = cfg.brokerId;
                existing.investorId = cfg.investorId;
                existing.connectTimeoutMs = cfg.connectTimeoutMs;
                existing.reconnectIntervalMs = cfg.reconnectIntervalMs;
                existing.flowPath = cfg.flowPath;
                existing.isEnabled = cfg.isEnabled;
                if (ctpFrontConfigDao.updateById(existing, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "CTP 前置配置并发冲突（row_version 不匹配）：id=" + cfg.id
                                    + "；复现=重新读取后再提交");
                }
                writeAuxChangeLog("ctp.front", "CTP_FRONT", "UPDATE:" + cfg.envType + "/"
                        + cfg.frontType, null, cfg.frontAddr, operatorId, "FR-CONFIG-10");
                return existing.id;
            }
            if (ctpFrontConfigDao.insert(cfg, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "CTP 前置配置唯一冲突（uk_ctp_front_env_type_addr）：env=" + cfg.envType
                                + " front=" + cfg.frontType + " addr=" + cfg.frontAddr
                                + "；复现=核对唯一约束");
            }
            writeAuxChangeLog("ctp.front", "CTP_FRONT", "CREATE:" + cfg.envType + "/" + cfg.frontType,
                    null, cfg.frontAddr, operatorId, "FR-CONFIG-10");
            return cfg.id;
        });
    }

    /**
     * CTP 连接测试（{@code FR-CONFIG-1} 验收 1）。
     *
     * <p><b>M1 的诚实口径</b>：连通性测试须经交易组件（C++ {@code ctp_adapter}）+ HGCP 桥接
     * （{@code T-042}，M2）方可执行。本方法先做<b>可离线完成的前置校验</b>（该环境下是否存在启用中的
     * 交易前置），随后以 {@code 10002}（交易组件通信不可用）明确失败并给出下一步 —— <b>不</b>伪报成功。
     *
     * @param envType 环境类型
     * @return 永不返回（失败路径）；M2 起返回测试结果
     */
    public Map<String, Object> testCtpConnection(String envType) {
        requireIn(envType, CTP_ENV_TYPES, "envType");
        List<CtpFrontConfig> fronts = ctpFrontConfigDao.findPage(PageQuery.of("1", "100", null, "id"),
                "env_type = ? AND front_type = ? AND is_enabled = ?",
                new ArrayList<Object>(Arrays.asList(envType, "TRADE", "Y"))).items();
        if (fronts.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "该环境无启用中的交易前置，无法发起连接测试：envType=" + envType
                            + "；复现=先配置并启用 TRADE 前置（FR-CONFIG-1/FR-CONFIG-10）");
        }
        throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                "CTP 连接测试须经交易组件（HGCP）执行，M1 未交付组件桥接（T-042/M2）：envType="
                        + envType + " 已配置启用中交易前置=" + fronts.size()
                        + "；复现=待组件桥接交付后重试（本接口已注册，非 404/405）");
    }

    /**
     * 列出枚举字典项（{@code FR-CONFIG-14}）。
     *
     * @param dictType 字典类型（{@code null} 表示全部）
     * @param query    分页参数
     * @return 分页结果
     */
    public PageResult<DictItem> listDictItems(String dictType, PageQuery query) {
        if (dictType != null && !dictType.isEmpty()) {
            return dictItemDao.findPage(query, "dict_type = ?", new ArrayList<Object>(
                    Collections.singletonList(dictType)));
        }
        return dictItemDao.findPage(query, null, null);
    }

    /**
     * 新增 / 停用枚举字典项（{@code FR-CONFIG-14}）。<b>不提供删除</b>——只能停用。
     *
     * @param item       字典项
     * @param operatorId 操作人
     * @return 主键
     */
    public long saveDictItem(DictItem item, long operatorId) {
        requireText(item.dictType, "dictType");
        requireText(item.itemCode, "itemCode");
        requireText(item.itemName, "itemName");
        requireIn(item.status, DICT_STATUSES, "status");
        item.isSystem = item.isSystem == null ? "N" : item.isSystem;
        return tx.inTransaction(connection -> {
            if (item.id > 0) {
                DictItem existing = dictItemDao.findById(item.id);
                if (existing == null) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "字典项不存在：id=" + item.id + "；复现=核对 id 或改为新增");
                }
                if ("Y".equals(existing.isSystem) && "DISABLED".equals(item.status)) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "系统内置字典项不得停用：itemCode=" + existing.itemCode
                                    + "；复现=保留启用状态（FR-CONFIG-14）");
                }
                existing.itemName = item.itemName;
                existing.itemDesc = item.itemDesc;
                existing.sortNo = item.sortNo;
                existing.status = item.status;
                if (dictItemDao.updateById(existing, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "字典项并发冲突：id=" + item.id + "；复现=重新读取后再提交");
                }
                writeAuxChangeLog("dict." + existing.dictType + "." + existing.itemCode, "DICT",
                        "UPDATE", null, item.status, operatorId, "FR-CONFIG-14");
                return existing.id;
            }
            if (dictItemDao.findByTypeAndCode(item.dictType, item.itemCode) != null) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "字典项已存在（uk_dict_item_type_code）：dictType=" + item.dictType
                                + " itemCode=" + item.itemCode + "；复现=改为更新既有项");
            }
            if (dictItemDao.insert(item, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "字典项唯一冲突（uk_dict_item_type_code）：dictType=" + item.dictType
                                + " itemCode=" + item.itemCode + "；复现=核对唯一约束");
            }
            writeAuxChangeLog("dict." + item.dictType + "." + item.itemCode, "DICT", "CREATE",
                    null, item.status, operatorId, "FR-CONFIG-14");
            return item.id;
        });
    }

    /**
     * 列出会计科目字典（{@code FR-CONFIG-14}）。
     *
     * @param bizScene 适用业务场景（{@code null} 表示全部）
     * @param query    分页参数
     * @return 分页结果
     */
    public PageResult<AccountSubject> listSubjects(String bizScene, PageQuery query) {
        if (bizScene != null && !bizScene.isEmpty()) {
            return accountSubjectDao.findPage(query, "biz_scene = ?", new ArrayList<Object>(
                    Collections.singletonList(bizScene)));
        }
        return accountSubjectDao.findPage(query, null, null);
    }

    /**
     * 新增 / 停用会计科目（{@code FR-CONFIG-14}）。会计科目由字典驱动，不得硬编码。
     *
     * @param subject    科目
     * @param operatorId 操作人
     * @return 主键
     */
    public long saveSubject(AccountSubject subject, long operatorId) {
        requireText(subject.subjectCode, "subjectCode");
        requireText(subject.subjectName, "subjectName");
        requireIn(subject.direction, SUBJECT_DIRECTIONS, "direction");
        requireIn(subject.bizScene, SUBJECT_BIZ_SCENES, "bizScene");
        if (subject.status == null || subject.status.isEmpty()) {
            subject.status = "ENABLED";
        }
        requireIn(subject.status, DICT_STATUSES, "status");
        subject.isTemplateDefault = subject.isTemplateDefault == null ? "N" : subject.isTemplateDefault;
        subject.editableByEnterprise = subject.editableByEnterprise == null
                ? "Y" : subject.editableByEnterprise;
        if (subject.subjectLevel <= 0) {
            subject.subjectLevel = subject.parentCode == null ? 1 : 2;
        }
        return tx.inTransaction(connection -> {
            if (subject.id > 0) {
                AccountSubject existing = accountSubjectDao.findById(subject.id);
                if (existing == null) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "会计科目不存在：id=" + subject.id + "；复现=核对 id 或改为新增");
                }
                existing.subjectName = subject.subjectName;
                existing.parentCode = subject.parentCode;
                existing.subjectLevel = subject.subjectLevel;
                existing.direction = subject.direction;
                existing.bizScene = subject.bizScene;
                existing.status = subject.status;
                if (accountSubjectDao.updateById(existing, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "会计科目并发冲突：id=" + subject.id + "；复现=重新读取后再提交");
                }
                writeAuxChangeLog("subject." + existing.subjectCode, "SUBJECT", "UPDATE",
                        null, subject.status, operatorId, "FR-CONFIG-14");
                return existing.id;
            }
            if (accountSubjectDao.findByCode(subject.subjectCode) != null) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "会计科目编码已存在（uk_account_subject_code）：subjectCode="
                                + subject.subjectCode + "；复现=改为更新既有科目");
            }
            if (accountSubjectDao.insert(subject, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "会计科目编码冲突（uk_account_subject_code）：subjectCode=" + subject.subjectCode
                                + "；复现=核对唯一约束");
            }
            writeAuxChangeLog("subject." + subject.subjectCode, "SUBJECT", "CREATE",
                    null, subject.status, operatorId, "FR-CONFIG-14");
            return subject.id;
        });
    }

    /**
     * 列出报告周期配置（{@code FR-CONFIG-15}）。
     *
     * @param query 分页参数
     * @return 分页结果
     */
    public PageResult<ReportPeriodConfig> listReportPeriods(PageQuery query) {
        return reportPeriodConfigDao.findPage(query, null, null);
    }

    /**
     * 新增 / 更新报告周期配置（{@code FR-CONFIG-15}；{@code R-CONFIG-7}）。
     *
     * <p><b>合规必需报告不得被关闭</b>：{@code isMandatory='Y'} 时 {@code isEnabled} 必须为
     * {@code 'Y'}，否则拒绝（与 {@code ck_report_period_mandatory_enabled} 同口径）。
     *
     * @param cfg        报告周期配置
     * @param operatorId 操作人
     * @return 主键
     */
    public long saveReportPeriod(ReportPeriodConfig cfg, long operatorId) {
        requireText(cfg.reportType, "reportType");
        requireIn(cfg.periodUnit, REPORT_PERIOD_UNITS, "periodUnit");
        requireText(cfg.remindRoleCodes, "remindRoleCodes");
        cfg.isMandatory = cfg.isMandatory == null ? "Y" : cfg.isMandatory;
        cfg.isEnabled = cfg.isEnabled == null ? "Y" : cfg.isEnabled;
        if (cfg.periodValue <= 0) {
            cfg.periodValue = 1;
        }
        if ("Y".equals(cfg.isMandatory) && !"Y".equals(cfg.isEnabled)) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "合规必需报告不得被配置为不生成（R-CONFIG-7）：reportType=" + cfg.reportType,
                    "R-CONFIG-7", "isEnabled=" + cfg.isEnabled, "isEnabled=Y",
                    "保留该报告的生成（每日/每月/每季度/每年为 8 号文要求）");
        }
        return tx.inTransaction(connection -> {
            ReportPeriodConfig existing = findReportPeriodByType(cfg.reportType);
            if (existing != null) {
                existing.periodUnit = cfg.periodUnit;
                existing.periodValue = cfg.periodValue;
                existing.remindAdvanceDays = cfg.remindAdvanceDays;
                existing.remindRoleCodes = cfg.remindRoleCodes;
                existing.isMandatory = cfg.isMandatory;
                existing.isEnabled = cfg.isEnabled;
                if (reportPeriodConfigDao.updateById(existing, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "报告周期配置并发冲突：reportType=" + cfg.reportType
                                    + "；复现=重新读取后再提交");
                }
                writeAuxChangeLog("report_period." + cfg.reportType, "REPORT_PERIOD", "UPDATE",
                        null, cfg.isEnabled, operatorId, "FR-CONFIG-15");
                return existing.id;
            }
            if (reportPeriodConfigDao.insert(cfg, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "报告周期配置唯一冲突（uk_report_period_type）：reportType=" + cfg.reportType
                                + "；复现=核对唯一约束");
            }
            writeAuxChangeLog("report_period." + cfg.reportType, "REPORT_PERIOD", "CREATE",
                    null, cfg.isEnabled, operatorId, "FR-CONFIG-15");
            return cfg.id;
        });
    }

    /**
     * 按主键取参数下发记录（{@code FR-CONFIG-6} / {@code R-CONFIG-3}：下发结果留痕查询）。
     *
     * @param id 主键
     * @return 记录；不存在返回 {@code null}
     */
    public ParamDispatch getDispatch(long id) {
        return paramDispatchDao.findById(id);
    }

    /**
     * 查询配置变更留痕（{@code FR-CONFIG-12}；{@code R-CONFIG-10}）。
     *
     * <p>筛选：配置分类、时间范围（闭区间）。记录<b>只增不改不删</b>，无删除入口。
     *
     * @param query    分页参数
     * @param category 配置分类（可空）
     * @param from     起始（{@code yyyy-MM-dd}，可空）
     * @param to       截止（{@code yyyy-MM-dd}，可空）
     * @return 分页结果
     */
    public PageResult<ConfigChangeLog> listChangeLogs(PageQuery query, String category,
                                                      String from, String to) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isEmpty()) {
            where.append("config_category = ?");
            args.add(category);
        }
        if (from != null && !from.isEmpty()) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("change_time >= ?");
            args.add(parseDay(from, "from").atStartOfDay());
        }
        if (to != null && !to.isEmpty()) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append("change_time <= ?");
            args.add(parseDay(to, "to").atTime(23, 59, 59));
        }
        return configChangeLogDao.findPage(query, where.length() == 0 ? null : where.toString(), args);
    }

    /**
     * 批量导入配置参数（{@code FR-CONFIG-13}）。
     *
     * <p><b>全量预校验 → 一次性提交</b>：逐项校验（必填、敏感项不得带值、批次内不重复、
     * 与既有项冲突时按 {@code mode} 处理）；任一项不合法即整体不导入并定位到该项。
     * 成功后逐项写变更留痕，并对需下发项标记 {@code UNCONFIRMED}（{@code R-CONFIG-3}）。
     *
     * @param items      待导入配置项（{@code configKey}/{@code configCategory}/{@code valueType} 必填）
     * @param mode       {@code OVERWRITE}（覆盖）/ {@code INSERT_ONLY}（仅新增）
     * @param operatorId 操作人
     * @return 实际写入条数
     */
    public int importConfigItems(List<ConfigItem> items, String mode, long operatorId) {
        if (mode == null || mode.isEmpty()) {
            mode = "INSERT_ONLY";
        }
        requireIn(mode, IMPORT_MODES, "mode");
        if (items == null || items.isEmpty()) {
            throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                    "导入项为空：复现=提供至少一项配置（FR-CONFIG-13）");
        }
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ConfigItem item = items.get(i);
            int line = i + 1;
            if (item == null) {
                problems.add("第 " + line + " 项为空");
                continue;
            }
            if (item.configKey == null || item.configKey.trim().isEmpty()) {
                problems.add("第 " + line + " 项 configKey 必填");
            } else if (!seen.add(item.configKey)) {
                problems.add("第 " + line + " 项 configKey 批次内重复：" + item.configKey);
            }
            if (item.configCategory == null || item.configCategory.trim().isEmpty()) {
                problems.add("第 " + line + " 项 configCategory 必填");
            }
            if (item.valueType == null || item.valueType.trim().isEmpty()) {
                problems.add("第 " + line + " 项 valueType 必填");
            }
            boolean sensitive = isSensitiveKey(item.configKey) || "Y".equals(item.isSensitive);
            if (sensitive && item.configValue != null) {
                problems.add("第 " + line + " 项敏感字段禁止携带取值（零落盘）：configKey="
                        + item.configKey);
            }
            ConfigItem stored = item.configKey == null ? null : configItemDao.findByKey(item.configKey);
            if (!sensitive && "INSERT_ONLY".equals(mode)
                    && item.configKey != null && stored != null) {
                problems.add("第 " + line + " 项已存在（仅新增模式）：configKey=" + item.configKey);
            }
            // 取值范围预校验（FR-CONFIG-13 验收 2；与 changeConfigValue 同源 validateValue）——
            // 任一越界项即整体不导入并定位到该项；缺省字段回退到既有项（防止缺省下界绕过）。
            if (!sensitive && item.configValue != null) {
                try {
                    validateValue(effectiveForValidation(item, stored), item.configValue);
                } catch (BizException e) {
                    problems.add("第 " + line + " 项取值不合法：" + e.getMessage());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw BizException.of(ErrorCode.IMPORT_VALIDATION_FAILED,
                    "导入全量预校验未通过（整体不导入，共 " + problems.size() + " 项）："
                            + String.join("；", problems));
        }
        int written = tx.inTransaction(connection -> {
            int count = 0;
            for (ConfigItem item : items) {
                boolean sensitive = isSensitiveKey(item.configKey) || "Y".equals(item.isSensitive);
                item.isSensitive = sensitive ? "Y" : (item.isSensitive == null ? "N" : item.isSensitive);
                item.dispatchToCtp = item.dispatchToCtp == null ? "N" : item.dispatchToCtp;
                item.doubleReviewRequired = item.doubleReviewRequired == null
                        ? "N" : item.doubleReviewRequired;
                if (item.persistScope == null) {
                    item.persistScope = "LOCAL_ADMIN";
                }
                if (sensitive) {
                    item.configValue = null;
                    if (item.sensitiveState == null) {
                        item.sensitiveState = "UNSET";
                    }
                }
                ConfigItem existing = configItemDao.findByKey(item.configKey);
                if (existing == null) {
                    if (configItemDao.insert(item, operatorId) != 1) {
                        throw BizException.of(ErrorCode.INTERNAL_ERROR,
                                "导入写入失败：configKey=" + item.configKey);
                    }
                } else {
                    String before = existing.configValue;
                    existing.configValue = sensitive ? null : item.configValue;
                    existing.configCategory = item.configCategory;
                    existing.valueType = item.valueType;
                    existing.enumScope = item.enumScope;
                    existing.minValue = item.minValue;
                    existing.maxValue = item.maxValue;
                    existing.persistScope = item.persistScope;
                    existing.dispatchToCtp = item.dispatchToCtp;
                    existing.doubleReviewRequired = item.doubleReviewRequired;
                    existing.sensitiveState = item.sensitiveState;
                    if (configItemDao.updateById(existing, operatorId) != 1) {
                        throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                                "导入并发冲突：configKey=" + item.configKey);
                    }
                    writeAuxChangeLog(item.configKey, item.configCategory, "IMPORT",
                            before, sensitive ? null : item.configValue, operatorId, "FR-CONFIG-13");
                }
                count++;
            }
            return count;
        });
        return written;
    }

    /**
     * 导出配置参数为 CSV 行（{@code FR-CONFIG-13} 验收 1）。
     *
     * <p><b>系统级强制排除敏感字段</b>：敏感项的取值列恒为空（不可通过选项包含）。
     *
     * @param category 配置分类（{@code null} 表示全部）
     * @return CSV 行（首行表头）
     */
    public List<String> exportConfigItemsCsv(String category) {
        List<ConfigItem> items = (category == null || category.isEmpty())
                ? collectAllConfigItems() : configItemDao.findByCategory(category);
        List<String> lines = new ArrayList<>();
        lines.add("config_key,config_category,config_value,value_type,enum_scope,persist_scope,"
                + "dispatch_to_ctp,sensitive_state");
        for (ConfigItem item : items) {
            boolean sensitive = isSensitiveKey(item.configKey) || "Y".equals(item.isSensitive);
            lines.add(String.join(",",
                    csv(item.configKey), csv(item.configCategory),
                    sensitive ? "" : csv(item.configValue),
                    csv(item.valueType), csv(item.enumScope), csv(item.persistScope),
                    csv(item.dispatchToCtp), csv(item.sensitiveState)));
        }
        return lines;
    }

    // ------------------------------------------------------------ 内部

    /**
     * 子账户停用四条件判定端口（{@code CFG-10}）。
     *
     * <p>为何是端口而非直接查 DAO：四项数据分属 {@code FUND}（资金）/ {@code TRADE}（持仓、在途指令）/
     * {@code SETTLE}（当日结算）三个模块，M1 尚未交付其表。以接口注入使本服务的判定逻辑可独立测试，
     * 且 M2 接线时本类<b>不需要</b>改动（{@code ARCHITECTURE.md} §3.8「核心逻辑不依赖具体 I/O」）。
     */
    public interface SubAccountDisablePrecheck {

        /**
         * 资金是否为 0。
         *
         * @param subAccountId 子账户 ID
         * @return 为 0 返回 {@code true}
         */
        boolean fundIsZero(int subAccountId);

        /**
         * 持仓是否为 0。
         *
         * @param subAccountId 子账户 ID
         * @return 为 0 返回 {@code true}
         */
        boolean positionsAreZero(int subAccountId);

        /**
         * 是否存在在途指令（已发 CTP 未收到回报）。
         *
         * @param subAccountId 子账户 ID
         * @return 无在途返回 {@code true}
         */
        boolean hasNoInFlightOrders(int subAccountId);

        /**
         * 当日是否已结算。
         *
         * @param subAccountId 子账户 ID
         * @return 已结算返回 {@code true}
         */
        boolean settledToday(int subAccountId);
    }

    private static String describePrecheck(Map<String, Object> detail) {
        return "资金清零=" + detail.get("fundIsZero")
                + ", 持仓清零=" + detail.get("positionsAreZero")
                + ", 无在途指令=" + detail.get("noInFlightOrders")
                + ", 当日已结算=" + detail.get("settledToday");
    }

    private static ConfigChangeLog lifecycleLog(SubAccount sa, long operatorId, String action,
                                                String before, String after, String reason) {
        ConfigChangeLog log = new ConfigChangeLog();
        log.configKey = KEY_SUB_ACCOUNT_LIFECYCLE;
        log.configCategory = CATEGORY_SUB_ACCOUNT;
        log.beforeValue = before;
        log.afterValue = after;
        log.isSensitiveChange = "N";
        log.operatorId = operatorId;
        log.changeReason = action + ":" + sa.subAccountCode + ":" + sa.id
                + (reason == null || reason.isEmpty() ? "" : ("，" + reason));
        log.dispatchResult = "NOT_REQUIRED";
        log.changeTime = Clocks.now();
        return log;
    }

    private void writeChangeLog(ConfigItem item, String before, String after, String action,
                                long operatorId, Long reviewerId, String dispatchResult,
                                Long paramVersion) {
        ConfigChangeLog log = newLog(item, item.configKey, operatorId, reviewerId,
                action + ":" + item.configKey);
        log.beforeValue = before;
        log.afterValue = after;
        log.dispatchResult = dispatchResult;
        log.dispatchParamVersion = paramVersion;
        configChangeLogDao.insert(log, operatorId);
    }

    private static ConfigChangeLog newLog(ConfigItem item, String configKey, long operatorId,
                                          Long reviewerId, String reason) {
        ConfigChangeLog log = new ConfigChangeLog();
        log.configKey = configKey;
        log.configCategory = item.configCategory;
        log.isSensitiveChange = "N";
        log.operatorId = operatorId;
        log.reviewerId = reviewerId;
        log.changeReason = reason;
        log.changeTime = Clocks.now();
        return log;
    }

    private static BizException sensitiveRejected(String configKey) {
        return BizException.of(ErrorCode.SENSITIVE_FIELD_REJECTED,
                "敏感字段禁止落库：config_key=" + configKey
                        + "；复现=改为设置 sensitive_state（取值由环境变量注入，NFR-SEC-1/R-CONFIG-2）");
    }

    /**
     * 构造导入取值校验的<b>有效口径</b>（{@code FR-CONFIG-13} 验收 2）。
     *
     * <p>导入项缺省字段（{@code valueType}/{@code minValue}/{@code maxValue}/{@code enumScope}）
     * 回退到既有项，防止以"缺省下界"绕过范围校验；既有项不存在时直接以导入项校验。
     */
    private static ConfigItem effectiveForValidation(ConfigItem incoming, ConfigItem stored) {
        if (stored == null) {
            return incoming;
        }
        ConfigItem eff = new ConfigItem();
        eff.configKey = incoming.configKey;
        eff.valueType = incoming.valueType != null ? incoming.valueType : stored.valueType;
        eff.minValue = incoming.minValue != null ? incoming.minValue : stored.minValue;
        eff.maxValue = incoming.maxValue != null ? incoming.maxValue : stored.maxValue;
        eff.enumScope = incoming.enumScope != null ? incoming.enumScope : stored.enumScope;
        return eff;
    }

    /**
     * 取值校验（{@code FR-CONFIG-11}：按 {@code value_type} 与上下界校验；{@code enum_scope} 取值域）。
     */
    private static void validateValue(ConfigItem item, String value) {
        if (value == null) {
            return;
        }
        String type = item.valueType == null ? "STRING" : item.valueType.toUpperCase(Locale.ROOT);
        switch (type) {
            case "INT":
            case "DECIMAL": {
                BigDecimal decimal = parseDecimal(item.configKey, value);
                if (item.minValue != null && decimal.compareTo(item.minValue) < 0) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "取值低于下界：config_key=" + item.configKey + " 值=" + value
                                    + " min=" + item.minValue + "；复现=按 min_value 调整");
                }
                if (item.maxValue != null && decimal.compareTo(item.maxValue) > 0) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "取值高于上界：config_key=" + item.configKey + " 值=" + value
                                    + " max=" + item.maxValue + "；复现=按 max_value 调整");
                }
                break;
            }
            case "BOOL": {
                if (!"Y".equals(value) && !"N".equals(value)) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "BOOL 型取值只允许 Y/N：config_key=" + item.configKey + " 值=" + value
                                    + "；复现=改正取值");
                }
                break;
            }
            case "ENUM":
            case "LIST": {
                if (item.enumScope == null || item.enumScope.isEmpty()) {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR,
                            "配置项未维护取值域（enum_scope）：config_key=" + item.configKey
                                    + "；复现=先补齐 enum_scope 再变更取值");
                }
                Set<String> scope = new LinkedHashSet<>();
                for (String part : item.enumScope.split(",")) {
                    scope.add(part.trim());
                }
                if (!scope.contains(value)) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "取值不在取值域内：config_key=" + item.configKey + " 值=" + value
                                    + " 取值域=" + scope + "；复现=按 enum_scope 取值");
                }
                break;
            }
            default:
                break;
        }
    }

    private static BigDecimal parseDecimal(String configKey, String value) {
        try {
            return new BigDecimal(value).setScale(6, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "数值型取值无法解析或精度超过 6 位小数：config_key=" + configKey + " 值=" + value
                            + "；复现=按 DECIMAL(20,6) 调整取值", e);
        }
    }

    /**
     * 敏感 Key 判定（大小写不敏感，按标记子串匹配）。
     *
     * @param configKey 配置键
     * @return 命中返回 {@code true}
     */
    public static boolean isSensitiveKey(String configKey) {
        if (configKey == null) {
            return false;
        }
        String lower = configKey.toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_KEY_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static void requireCategory(String category) {
        if (!SUB_ACCOUNT_CATEGORIES.contains(category)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "子账户分类只允许 " + SUB_ACCOUNT_CATEGORIES + "：实际=" + category
                            + "；复现=按 CFG-02c 取值域提交");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "必填字段为空：" + field + "；复现=补齐该字段后重新提交");
        }
    }

    /** 取值域校验（枚举取值须与 FR 原文一致）。 */
    private static void requireIn(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    field + " 取值须 ∈ " + allowed + "：实际=" + value + "；复现=按取值域提交");
        }
    }

    /** 解析 {@code yyyy-MM-dd}（查询参数）。 */
    private static LocalDate parseDay(String raw, String field) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "查询参数须为 yyyy-MM-dd：" + field + "=" + raw + "；复现=改正日期格式", e);
        }
    }

    /** 按 {@code report_type} 查报告周期配置（唯一键 {@code uk_report_period_type}）。 */
    private ReportPeriodConfig findReportPeriodByType(String reportType) {
        return reportPeriodConfigDao.findByType(reportType);
    }

    /** 逐页收集全部配置项（导出用；避免单页 {@code size} 上限造成静默截断）。 */
    private List<ConfigItem> collectAllConfigItems() {
        List<ConfigItem> all = new ArrayList<>();
        int page = 1;
        while (true) {
            PageResult<ConfigItem> result = configItemDao.findPage(
                    PageQuery.of(String.valueOf(page), "100", null, "id"), null, null);
            all.addAll(result.items());
            if (result.items().isEmpty() || all.size() >= result.total()) {
                break;
            }
            page++;
        }
        return all;
    }

    /** 写一条非配置项（字典 / 科目 / 前置 / 报告周期）的变更留痕（{@code R-CONFIG-10}）。 */
    private void writeAuxChangeLog(String configKey, String category, String action,
                                   String before, String after, long operatorId, String rule) {
        ConfigChangeLog log = new ConfigChangeLog();
        log.configKey = configKey;
        log.configCategory = category;
        log.beforeValue = before;
        log.afterValue = after;
        log.isSensitiveChange = "N";
        log.operatorId = operatorId;
        log.changeReason = action + "（" + rule + "）";
        log.dispatchResult = "NOT_REQUIRED";
        log.changeTime = Clocks.now();
        configChangeLogDao.insert(log, operatorId);
    }

    /** CSV 单元格转义（含逗号 / 引号 / 换行时加引号）。 */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
