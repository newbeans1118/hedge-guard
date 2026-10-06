package com.hedgeguard.service;

import com.hedgeguard.bridge.AckResult;
import com.hedgeguard.bridge.ParamChange;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ConfigChangeLogDao;
import com.hedgeguard.dao.RiskThresholdDao;
import com.hedgeguard.dao.entity.ConfigChangeLog;
import com.hedgeguard.dao.entity.RiskThreshold;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 风控阈值配置与下发闭环（{@code T-063}；阈值部分）。
 *
 * <p>承载 {@code FR-CONFIG-5}（配置风险阈值）、{@code FR-CONFIG-6}/{@code FR-RISK-10}
 * （显式下发 C++ + 回执 + 留痕）、{@code R-CONFIG-8}（合规下限，不得静默放宽）。
 *
 * <h2>四段闭环</h2>
 * <ol>
 *   <li><b>配置校验</b>：取值按 {@link RiskThresholdSpec} 的类型 / 值域 / 范围校验
 *       （越界 → {@code 10001}，{@code FR-CONFIG-5} 验收 3）；</li>
 *   <li><b>合规下限</b>：候选值若相对 {@code INDEX.md} §9.1 默认值**放宽**，须显式双人复核
 *       （{@code reviewerId} 必填且 {@code != operatorId}），否则拒绝（{@code 10012} 阻断四项，
 *       {code R-CONFIG-8}）；未放宽则放行；</li>
 *   <li><b>落库</b>：写 {@code t_risk_threshold}（{@code GLOBAL} + 哨兵 {@code 0}），
 *       {@code dispatch_status=PENDING}，并写 {@code t_config_change_log} 留痕（含复核人 / 前后值）；</li>
 *   <li><b>下发</b>：组装**全量 16 字段** {@code CMD_UPDATE_RISK_THRESHOLD}
 *       （{@code ParamChange}）经 {@link ParamDispatchService} 下发（{@code param_version} 单调、
 *       {@code t_param_dispatch} 先落 {@code PENDING}）；回执写回 {@code dispatch_status}/{@code dispatch_version}；
 *       <b>无回执或失败不得视为已生效</b>（{@link ParamDispatchService#requireEffective} → {@code 10014}）。</li>
 * </ol>
 *
 * <h2>为何下发全量而非单键增量</h2>
 * {@code §4.2.8 RiskThresholds} 的 16 个字段全部为**必填**、且引擎以整快照替换阈值集 ⇒ 单键变更
 * 也须发**完整阈值快照**（否则未变更键会被缺省值覆盖）。快照 = 登记表默认值 ⊕ 库内当前值。
 *
 * <h2>边界（不属本类）</h2>
 * 事中镜像告警（{@code risk_intraday}，{@code T-050}）、事后报表（{@code T-180}）、C++ 引擎 11 规则
 * （{@code T-035} 等）均不在此重复实现；本类只做「配置 → 合规校验 → 下发 → 留痕/回执」。
 *
 * <h2>承载边界（诚实披露，{@code T-063}）</h2>
 * <ol>
 *   <li><b>无线格式字段的键</b>：{@code CFG-16}（{@code risk.limit.layers}）、{@code CFG-21} 之
 *       {@code _critical}、{@code CFG-21b} **在 {@code §4.2.8 RiskThresholds} 无字段** ⇒ 可经
 *       {@code t_risk_threshold} 配置/查看，但 <b>不可经 {@code CMD_UPDATE_RISK_THRESHOLD} 下发</b>
 *       （schema↔需求缺口，待 {@code CH}；不得自行扩协议）。</li>
 *   <li><b>Java 事中面不随本表刷新</b>：事中镜像阈值 {@code risk_intraday.RiskThresholds}
 *       取自<b>运行期 {@code Config}</b>（属性 / 环境变量，装配期一次绑定），非本表；
 *       {@code FR-RISK-9}/{@code FR-RISK-10}（{@code RISK.md:221}/{@code :236}）与
 *       {@code FR-CONFIG-5}/{@code R-CONFIG-8}（{@code CONFIG.md:164}/{@code :339}）的明文义务均为
 *       「<b>下发 C++ 内存</b> + 留痕 + 合规口径」，<b>未</b>要求 DB 阈值变更刷新 Java 事中镜像
 *       ⇒ 本类<b>不</b>做需求外的同流 {@code t_config_item} 更新（避免双写/需求外传播）；
 *       若后续要求打通，属跨 {@code T-050}/{@code T-065} 事中面改造，另立任务。</li>
 * </ol>
 */
public final class RiskService {

    /** 作用域：全局（哨兵子账户 {@code 0}）。 */
    public static final String SCOPE_GLOBAL = "GLOBAL";

    /** 全账户哨兵子账户 ID。 */
    public static final int ALL_ACCOUNT_SENTINEL = 0;

    /** 合规红线标记。 */
    public static final String REDLINE = "Y";

    /** 配置变更留痕分类（与 {@code data-init.sql} 的 {@code RISK_PARAM} 一致）。 */
    public static final String CATEGORY = "RISK_PARAM";

    private final TxTemplate tx;
    private final RiskThresholdDao thresholdDao;
    private final ParamDispatchService dispatchService;
    private final ConfigChangeLogDao changeLogDao;

    /**
     * 构造。
     *
     * @param tx             事务模板
     * @param thresholdDao   风控阈值 DAO（{@code T-094}）
     * @param dispatchService 参数下发编排（{@code T-011}）
     * @param changeLogDao   配置变更留痕 DAO（{@code TB-CONFIG-3}，承载双人复核留痕）
     */
    public RiskService(TxTemplate tx, RiskThresholdDao thresholdDao,
                       ParamDispatchService dispatchService, ConfigChangeLogDao changeLogDao) {
        this.tx = tx;
        this.thresholdDao = thresholdDao;
        this.dispatchService = dispatchService;
        this.changeLogDao = changeLogDao;
    }

    /**
     * 列出全部风控阈值（{@code FR-CONFIG-5} 验收 1 / 本单验收 4）。
     *
     * <p>库内未写入的键以 {@link RiskThresholdSpec#defaultValue()} 合成展示（{@code dispatchStatus}
     * 为 {@code null} 表示尚未落库下发），保证「规则清单」对 16 项完整可见。
     *
     * @return 阈值视图（按 {@link RiskThresholdSpec} 声明序）
     */
    public List<ThresholdView> listThresholds() {
        List<ThresholdView> views = new ArrayList<>();
        for (RiskThresholdSpec spec : RiskThresholdSpec.values()) {
            views.add(ThresholdView.of(spec,
                    thresholdDao.findByKey(spec.configKey(), SCOPE_GLOBAL, ALL_ACCOUNT_SENTINEL)));
        }
        return views;
    }

    /**
     * 变更一项风控阈值并下发（{@code FR-CONFIG-5}/{@code FR-CONFIG-6}）。
     *
     * @param configKey  配置键（须为 {@link RiskThresholdSpec} 已登记项）
     * @param newValue   新取值
     * @param operatorId 操作人
     * @param reviewerId 复核人（放宽合规红线时必填且不得等于操作人）
     * @param reason     变更原因
     * @return 变更后的阈值视图
     * @throws BizException 取值非法（{@code 10001}）/ 放宽未复核（{@code 10012}）/
     *                      并发冲突（{@code 10009}）/ 下发未生效（{@code 10014}）/
     *                      组件不可用（{@code 10002}）
     */
    public ThresholdView updateThreshold(String configKey, String newValue, long operatorId,
                                         Long reviewerId, String reason) {
        if (dispatchService == null) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "参数下发服务未装配（组件桥不可用），拒绝风控阈值变更（不得静默不生效）："
                            + "configKey=" + configKey
                            + "；复现=核 HGCP 桥装配（NFR-DEPLOY-5）");
        }
        final RiskThresholdSpec spec;
        try {
            spec = RiskThresholdSpec.byKey(configKey);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, e.getMessage());
        }
        try {
            spec.requireValid(newValue);
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "风控阈值取值非法（" + configKey + "）：" + e.getMessage()
                            + "；复现=按取值范围提交（FR-CONFIG-5 验收 3）");
        }
        String normalized = newValue.trim();
        boolean relaxed = spec.isRelaxed(normalized);
        if (relaxed && (reviewerId == null || reviewerId == operatorId)) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "放宽合规红线须显式双人复核（不得静默放宽，R-CONFIG-8）："
                            + configKey + " 由默认 " + spec.defaultValue() + " 放宽为 " + normalized
                            + (reviewerId == null ? "，未提供复核人"
                            : "，复核人不得为操作人本人"),
                    "R-CONFIG-8",
                    spec.defaultValue(),
                    normalized,
                    "提交 reviewerId（须为另一名有权限人员）；本次变更将记入合规风险留痕");
        }

        RiskThreshold existing =
                thresholdDao.findByKey(spec.configKey(), SCOPE_GLOBAL, ALL_ACCOUNT_SENTINEL);
        final String before = existing == null ? spec.defaultValue() : existing.thresholdValue;
        final RiskThreshold row = existing == null ? newRow(spec, operatorId) : existing;
        row.thresholdValue = normalized;
        row.dispatchStatus = "PENDING";
        final boolean isNew = existing == null;
        tx.inTransaction(connection -> {
            if (isNew) {
                if (thresholdDao.insert(row, operatorId) != 1) {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR,
                            "风控阈值写入失败：" + configKey + "；复现=核对 t_risk_threshold 约束");
                }
            } else if (thresholdDao.updateById(row, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "风控阈值并发冲突（row_version 不匹配）：" + configKey
                                + "；复现=重新读取后再提交");
            }
            return null;
        });

        ParamChange change = buildFullSnapshot(operatorId);
        AckResult ack;
        try {
            ack = dispatchService.dispatch(change).join();
        } catch (RuntimeException e) {
            markDispatch(spec, "FAIL", null, "下发调用失败：" + e.getMessage(), operatorId);
            throw e;
        }
        boolean effective = ack != null && ack.acknowledged;
        // 与 t_param_dispatch 的 settle 口径一致：显式失败 → FAIL；无回执 → UNCONFIRMED
        String status = effective ? "SUCCESS" : (ack == null ? "UNCONFIRMED" : "FAIL");
        String detail = ack == null ? "组件返回空回执 → 未生效" : ack.message;
        markDispatch(spec, status, ack == null ? null : ack.paramVersion, detail, operatorId);
        writeChangeLog(spec, before, normalized, operatorId, reviewerId, status,
                ack == null ? null : ack.paramVersion, reason, relaxed);
        // 无回执或失败不得视为已生效（R-CONFIG-3 / FR-CONFIG-6 验收 2）
        dispatchService.requireEffective(change, ack);

        return ThresholdView.of(spec,
                thresholdDao.findByKey(spec.configKey(), SCOPE_GLOBAL, ALL_ACCOUNT_SENTINEL));
    }

    /** 新行（默认值 / 合规红线 / 取值域均取自登记表）。 */
    private static RiskThreshold newRow(RiskThresholdSpec spec, long operatorId) {
        RiskThreshold row = new RiskThreshold();
        row.thresholdKey = spec.configKey();
        row.thresholdValue = spec.defaultValue();
        row.valueType = spec.valueType();
        row.minValue = spec.min();
        row.maxValue = spec.max();
        row.defaultValue = spec.defaultValue();
        row.isComplianceRedline = spec.complianceRedline() ? REDLINE : "N";
        row.scopeType = SCOPE_GLOBAL;
        row.subAccountId = ALL_ACCOUNT_SENTINEL;
        row.relatedQuestionNo = spec.relatedQuestionNo();
        row.dispatchStatus = "PENDING";
        row.dispatchVersion = null;
        row.description = "风控阈值配置（FR-CONFIG-5 / R-CONFIG-8；键 " + spec.configKey() + "）";
        return row;
    }

    /** 组装全量 16 字段的 {@code CMD_UPDATE_RISK_THRESHOLD}（默认值 ⊕ 库内当前值）。 */
    private ParamChange buildFullSnapshot(long operatorId) {
        ParamChange change =
                new ParamChange(ParamChange.Type.CMD_UPDATE_RISK_THRESHOLD, 0L, operatorId);
        for (RiskThresholdSpec spec : RiskThresholdSpec.values()) {
            RiskThreshold row =
                    thresholdDao.findByKey(spec.configKey(), SCOPE_GLOBAL, ALL_ACCOUNT_SENTINEL);
            change.with(spec.wireField(),
                    row == null ? spec.defaultValue() : row.thresholdValue);
        }
        return change;
    }

    /** 回写下发结果（{@code dispatch_status} / {@code dispatch_version}）。 */
    private void markDispatch(RiskThresholdSpec spec, String status, Long version,
                              String detail, long operatorId) {
        try {
            RiskThreshold row =
                    thresholdDao.findByKey(spec.configKey(), SCOPE_GLOBAL, ALL_ACCOUNT_SENTINEL);
            if (row == null) {
                return;
            }
            row.dispatchStatus = status;
            if (version != null) {
                row.dispatchVersion = version;
            }
            tx.inTransaction(connection -> {
                if (thresholdDao.updateById(row, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "风控阈值下发结果回写冲突：" + spec.configKey());
                }
                return null;
            });
        } catch (RuntimeException e) {
            // 回写失败不吞留痕纪律：抛出（调用方据实处理），但不得静默
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "风控阈值下发结果回写失败（" + spec.configKey() + "，status=" + status
                            + "）：" + e.getMessage() + "；复现=核对 t_risk_threshold 约束");
        }
    }

    /** 写配置变更留痕（含复核人 / 前后值 / 合规风险标注；{@code FR-CONFIG-12}）。 */
    private void writeChangeLog(RiskThresholdSpec spec, String before, String after,
                                long operatorId, Long reviewerId, String dispatchResult,
                                Long paramVersion, String reason, boolean relaxed) {
        ConfigChangeLog log = new ConfigChangeLog();
        log.configKey = spec.configKey();
        log.configCategory = CATEGORY;
        log.beforeValue = before;
        log.afterValue = after;
        log.isSensitiveChange = "N";
        log.operatorId = operatorId;
        log.reviewerId = reviewerId;
        log.changeReason = (reason == null || reason.isEmpty() ? "风控阈值变更" : reason)
                + (relaxed ? "（合规红线放宽，R-CONFIG-8，已双人复核）" : "");
        log.dispatchResult = dispatchResult;
        log.dispatchParamVersion = paramVersion;
        log.changeTime = Clocks.now();
        changeLogDao.insert(log, operatorId);
    }

    /**
     * 阈值视图（REST {@code GET|POST /risk/rule} 响应元素）。
     *
     * <p>{@code dispatchStatus} 为 {@code null} 表示该键尚未落库（值为登记表默认值）。
     */
    public static final class ThresholdView {

        /** 配置键（{@code §11.1}）。 */
        public String thresholdKey;

        /** 线格式字段名（{@code §4.2.8 RiskThresholds}）。 */
        public String wireField;

        /** 当前取值。 */
        public String thresholdValue;

        /** 默认值（{@code INDEX.md} §9.1）。 */
        public String defaultValue;

        /** 值类型。 */
        public String valueType;

        /** 是否合规红线（{@code Y}/{@code N}）。 */
        public String isComplianceRedline;

        /** 取值范围下界（可空）。 */
        public String minValue;

        /** 取值范围上界（可空）。 */
        public String maxValue;

        /** 值域（枚举型；逗号分隔，可空）。 */
        public String allowedValues;

        /** 下发状态（{@code PENDING}/{@code SUCCESS}/{@code FAIL}/{@code UNCONFIRMED}；未落库为 {@code null}）。 */
        public String dispatchStatus;

        /** 下发 {@code param_version}（可空）。 */
        public Long dispatchVersion;

        /** 关联待澄清编号（可空）。 */
        public String relatedQuestionNo;

        /** 说明。 */
        public String description;

        static ThresholdView of(RiskThresholdSpec spec, RiskThreshold row) {
            ThresholdView v = new ThresholdView();
            v.thresholdKey = spec.configKey();
            v.wireField = spec.wireField();
            v.thresholdValue = row == null ? spec.defaultValue() : row.thresholdValue;
            v.defaultValue = spec.defaultValue();
            v.valueType = spec.valueType();
            v.isComplianceRedline = spec.complianceRedline() ? REDLINE : "N";
            v.minValue = spec.min() == null ? null : spec.min().toPlainString();
            v.maxValue = spec.max() == null ? null : spec.max().toPlainString();
            v.allowedValues = spec.allowedValues().isEmpty()
                    ? null : String.join(",", spec.allowedValues());
            v.dispatchStatus = row == null ? null : row.dispatchStatus;
            v.dispatchVersion = row == null ? null : row.dispatchVersion;
            v.relatedQuestionNo = spec.relatedQuestionNo();
            v.description = row == null ? null : row.description;
            return v;
        }
    }
}
