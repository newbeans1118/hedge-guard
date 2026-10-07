package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.RiskAlertDao;
import com.hedgeguard.dao.RiskDisposalDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.entity.RiskDisposal;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.risk_intraday.TradingSuspendControl;
import com.hedgeguard.risk_intraday.TradingSuspendControl.DispatchOutcome;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 风险告警查询与处置服务（{@code T-065}；{@code FR-RISK-15}/{@code FR-RISK-16}/{@code FR-RISK-19}）。
 *
 * <h2>职责</h2>
 * <ol>
 *   <li>{@link #queryAlerts}：告警历史查询（{@code FR-RISK-19}）——筛选（级别/类型/子账户/时间/since/
 *       处置态）+ 默认时间倒序 + 主键兜底全序 + 分页 + 子账户可见性隔离 + 回填处置动作与处置人；</li>
 *   <li>{@link #dispose}：告警处置（{@code FR-RISK-15}/{@code FR-RISK-16}）——处置动作
 *       {@code ALERT_ONLY}/{@code SUSPEND_OPEN}/{@code FORCE_CLOSE_HINT}/{@code RELEASE_SUSPEND}，
 *       落 {@code t_risk_disposal}（{@code scan_scope} 恒 {@code OPEN_ONLY}，{@code R-RISK-5}），
 *       限制/解除经 {@link TradingSuspendControl}（{@code CMD_SET_TRADING_SUSPEND}）下发，
 *       <b>无回执不得视为已生效</b>（{@code R-CONFIG-3}/{@code FR-RISK-15}）。</li>
 * </ol>
 *
 * <h2>只读纪律</h2>
 * 告警<b>只增不改不删</b>（{@code R-RISK-11}）：本服务不提供删除入口；处置仅置 {@code disposed='Y'}
 * 与 {@code disposal_id}（不改告警内容）。系统<b>不自动交易</b>（{@code R-RISK-13}）：处置只做
 * 限制/提示，<b>无</b>下单路径。
 *
 * <h2>权限（{@code NFR-SEC-7}）</h2>
 * 读面按 {@link SubAccountVisibility#visibleSubAccountIds}：含哨兵 0（跨账户只读）不过滤；
 * 空集恒假（fail-closed）。写面（处置）对真实子账户用
 * {@link SubAccountVisibility#assertSubAccountWritable}（跨账户只读不得写，{@code FR-AUTH-13} 验收 3）；
 * 账户级（子账户 0）告警的处置要求调用者具跨账户可见（哨兵）。
 */
public final class RiskAlertService {

    /** 处置动作：仅告警。 */
    public static final String ACTION_ALERT_ONLY = "ALERT_ONLY";
    /** 处置动作：限制新开仓。 */
    public static final String ACTION_SUSPEND_OPEN = "SUSPEND_OPEN";
    /** 处置动作：强制平仓提示。 */
    public static final String ACTION_FORCE_CLOSE_HINT = "FORCE_CLOSE_HINT";
    /** 处置动作：解除限制。 */
    public static final String ACTION_RELEASE_SUSPEND = "RELEASE_SUSPEND";

    /** 限制范围（恒为只限开仓，{@code R-RISK-5}）。 */
    public static final String SCAN_SCOPE_OPEN_ONLY = "OPEN_ONLY";

    /** 全账户哨兵子账户。 */
    public static final int ALL_ACCOUNT_SENTINEL = SubAccountScope.ALL_ACCOUNT_SENTINEL;

    private static final Set<String> ACTIONS = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList(ACTION_ALERT_ONLY, ACTION_SUSPEND_OPEN,
                    ACTION_FORCE_CLOSE_HINT, ACTION_RELEASE_SUSPEND)));

    private final TxTemplate tx;
    private final RiskAlertDao alertDao;
    private final RiskDisposalDao disposalDao;
    private final SubAccountVisibility visibility;
    private final TradingSuspendControl suspendControl;

    /**
     * 构造。
     *
     * @param tx             事务模板
     * @param alertDao       告警 DAO
     * @param disposalDao    处置 DAO
     * @param visibility     子账户可见性（{@code null} 表示不启用隔离，仅测试装配用）
     * @param suspendControl 限制/解除下发端口（{@code null} ⇒ 限制类处置不可下发）
     */
    public RiskAlertService(TxTemplate tx, RiskAlertDao alertDao, RiskDisposalDao disposalDao,
                            SubAccountVisibility visibility, TradingSuspendControl suspendControl) {
        this.tx = tx;
        this.alertDao = alertDao;
        this.disposalDao = disposalDao;
        this.visibility = visibility;
        this.suspendControl = suspendControl;
    }

    // ------------------------------------------------------------ FR-RISK-19 查询

    /**
     * 查询告警历史（{@code FR-RISK-19}）。
     *
     * @param q          筛选条件（可空）
     * @param query      分页/排序（缺省方向须由调用方显式声明为 {@code desc}）
     * @param userId     调用者
     * @return 分页视图（每行含处置动作与处置人；未处置者 {@code disposed=N}）
     */
    public AlertPage queryAlerts(AlertQuery q, PageQuery query, long userId) {
        List<Integer> allowed = visibility == null ? null : visibility.visibleSubAccountIds(userId);
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (q != null) {
            if (q.subAccountId != null) {
                append(where, "sub_account_id = ?");
                args.add(q.subAccountId);
            }
            if (q.alertType != null) {
                append(where, "alert_type = ?");
                args.add(q.alertType);
            }
            if (q.alertLevel != null) {
                append(where, "alert_level = ?");
                args.add(q.alertLevel);
            }
            if (q.metricName != null) {
                append(where, "metric_name = ?");
                args.add(q.metricName);
            }
            if (q.disposed != null) {
                append(where, "disposed = ?");
                args.add(q.disposed);
            }
            if (q.fromTime != null) {
                append(where, "alert_time >= ?");
                args.add(q.fromTime);
            }
            if (q.toTime != null) {
                append(where, "alert_time <= ?");
                args.add(q.toTime);
            }
            if (q.sinceMs != null) {
                append(where, "alert_ts_ms > ?");
                args.add(q.sinceMs);
            }
        }
        String w = where.length() == 0 ? null : where.toString();
        PageResult<com.hedgeguard.dao.entity.RiskAlert> page;
        boolean crossAccount = allowed == null || allowed.contains(ALL_ACCOUNT_SENTINEL);
        if (crossAccount) {
            page = alertDao.findPage(query, w, args);
        } else {
            page = alertDao.findPageScoped(query, SubAccountScope.of("sub_account_id", allowed), w, args);
        }
        List<AlertView> items = new ArrayList<>();
        for (com.hedgeguard.dao.entity.RiskAlert row : page.items()) {
            items.add(toView(row));
        }
        return new AlertPage(items, page.total(), page.page(), page.size());
    }

    private AlertView toView(com.hedgeguard.dao.entity.RiskAlert row) {
        AlertView v = new AlertView();
        v.id = row.id;
        v.subAccountId = row.subAccountId;
        v.alertType = row.alertType;
        v.alertLevel = row.alertLevel;
        v.metricName = row.metricName;
        v.metricValue = row.metricValue;
        v.thresholdValue = row.thresholdValue;
        v.instrumentId = row.instrumentId;
        v.alertDetail = row.alertDetail;
        v.alertTsMs = row.alertTsMs;
        v.alertTime = row.alertTime == null ? null : row.alertTime.toString();
        v.disposed = row.disposed;
        v.disposalId = row.disposalId;
        if (row.disposalId != null) {
            RiskDisposal d = disposalDao.findById(row.disposalId);
            if (d != null) {
                v.disposalType = d.disposalType;
                v.disposalOperatorId = d.operatorId;
                v.disposalTime = d.disposalTime == null ? null : d.disposalTime.toString();
                v.dispatchStatus = d.dispatchStatus;
                v.released = d.releasedTime != null;
            }
        }
        return v;
    }

    // ------------------------------------------------------------ FR-RISK-15/16 处置

    /**
     * 处置告警（{@code FR-RISK-15}/{@code FR-RISK-16}）。
     *
     * @param alertId 告警主键
     * @param in      处置输入
     * @param userId  处置人
     * @return 处置视图
     * @throws BizException 告警不存在（{@code 10009}）/ 入参非法（{@code 10001}）/
     *                      无写权限（{@code 10004}）/ 下发未生效（{@code 10014}）/
     *                      下发端口未装配（{@code 10002}）
     */
    public DisposalView dispose(long alertId, DisposeInput in, long userId) {
        if (in == null || in.action == null || !ACTIONS.contains(in.action.trim())) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "处置动作非法（须为 " + ACTIONS + "）：action=" + (in == null ? null : in.action));
        }
        String action = in.action.trim();
        com.hedgeguard.dao.entity.RiskAlert alert = alertDao.findById(alertId);
        if (alert == null) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "告警记录不存在：id=" + alertId + "；复现=核对 t_risk_alert 主键");
        }
        assertWritable(userId, alert.subAccountId);
        String reason = in.reason == null ? null : in.reason.trim();
        if (reason == null || reason.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "处置理由必填（留痕，FR-RISK-15）");
        }
        if (ACTION_FORCE_CLOSE_HINT.equals(action)) {
            requireHintInput(in);
        }
        boolean needsDispatch = ACTION_SUSPEND_OPEN.equals(action) || ACTION_RELEASE_SUSPEND.equals(action);
        final int sub = alert.subAccountId == null ? 0 : alert.subAccountId;
        if (needsDispatch && sub <= 0) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "限制/解除须指定真实子账户（下发端口要求 subAccountId > 0）：id=" + alertId
                            + " subAccountId=" + sub + "；全账户限制当前不可经 0x0118 下发");
        }
        if (needsDispatch && suspendControl == null) {
            throw BizException.of(ErrorCode.COMPONENT_UNAVAILABLE,
                    "限制/解除下发端口未装配（组件桥不可用），拒绝处置（不得静默不生效）：id=" + alertId);
        }

        RiskDisposal row = new RiskDisposal();
        row.subAccountId = sub;
        row.isGlobal = sub <= 0 ? "Y" : "N";
        row.disposalType = action;
        row.scanScope = SCAN_SCOPE_OPEN_ONLY;
        row.reason = reason;
        row.operatorId = userId;
        row.disposalTime = Clocks.now();
        row.dispatchStatus = needsDispatch ? "PENDING" : null;
        if (ACTION_FORCE_CLOSE_HINT.equals(action)) {
            row.hintInstrumentId = trim(in.hintInstrumentId);
            row.hintDirection = trim(in.hintDirection);
            row.hintVolume = in.hintVolume;
            row.hintAccepted = in.hintAccepted.trim();
            row.rejectReason = "N".equals(row.hintAccepted) ? in.rejectReason.trim() : null;
        }
        final long disposalId = insertDisposal(row, userId);

        if (needsDispatch) {
            DispatchOutcome outcome = ACTION_SUSPEND_OPEN.equals(action)
                    ? suspendControl.suspendOpen(sub, reason, userId)
                    : suspendControl.releaseOpen(sub, reason, userId);
            boolean acked = outcome != null && outcome.acknowledged;
            // 限制：失败须告警且不视为生效；解除：无回执记 UNCONFIRMED（仍有其它挂起时组件本就不解冻，非失败）
            String status = ACTION_RELEASE_SUSPEND.equals(action)
                    ? (acked ? "SUCCESS" : "UNCONFIRMED")
                    : (acked ? "SUCCESS" : "FAIL");
            String dispatchMessage = outcome == null ? "空回执" : outcome.message;
            markDispatched(disposalId, status,
                    outcome == null ? null : outcome.paramVersion, userId);
            if (ACTION_SUSPEND_OPEN.equals(action) && !acked) {
                throw BizException.blocking(ErrorCode.DISPATCH_NOT_EFFECTIVE,
                        "限制新开仓下发未生效（不得视为已生效，FR-RISK-15）：subAccountId=" + sub
                                + "；组件回执=" + dispatchMessage,
                        "R-RISK-5", "PENDING", "已下发", "巡检 t_risk_disposal 的 PENDING/FAIL/UNCONFIRMED 行并重发");
            }
        }
        if (ACTION_RELEASE_SUSPEND.equals(action)) {
            releaseActiveSuspend(disposalId, sub, userId);
        }
        markAlertDisposed(alert, disposalId, userId);
        return toDisposalView(disposalId);
    }

    private void requireHintInput(DisposeInput in) {
        String accepted = in.hintAccepted == null ? null : in.hintAccepted.trim();
        if (!"Y".equals(accepted) && !"N".equals(accepted)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "强制平仓提示须显式接受或拒绝（hintAccepted=Y/N）：值=" + in.hintAccepted);
        }
        if ("N".equals(accepted) && (in.rejectReason == null || in.rejectReason.trim().isEmpty())) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "不接受强制平仓提示须填写理由（FR-RISK-16 验收 4）");
        }
    }

    private long insertDisposal(RiskDisposal row, long operatorId) {
        return tx.inTransaction(connection -> {
            if (disposalDao.insert(row, operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "处置记录写入失败；复现=核对 t_risk_disposal 约束");
            }
            return row.id;
        });
    }

    private void markDispatched(long disposalId, String status, Long version, long operatorId) {
        tx.inTransaction(connection -> {
            RiskDisposal row = disposalDao.findById(disposalId);
            if (row == null) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "处置记录回写失败（记录不存在）：id=" + disposalId);
            }
            row.dispatchStatus = status;
            if (version != null) {
                row.dispatchVersion = version;
            }
            if (disposalDao.updateById(row, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "处置记录下发结果回写冲突：id=" + disposalId);
            }
            return null;
        });
    }

    private void releaseActiveSuspend(long releaseDisposalId, int subAccountId, long operatorId) {
        List<RiskDisposal> active = disposalDao.findActiveBySubAccount(subAccountId);
        LocalDateTime now = Clocks.now();
        for (RiskDisposal d : active) {
            if (!ACTION_SUSPEND_OPEN.equals(d.disposalType) || d.id == releaseDisposalId) {
                continue;
            }
            d.releasedTime = now;
            tx.inTransaction(connection -> {
                if (disposalDao.updateById(d, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "解除限制回写冲突：disposalId=" + d.id);
                }
                return null;
            });
        }
    }

    private void markAlertDisposed(com.hedgeguard.dao.entity.RiskAlert alert, long disposalId,
                                   long operatorId) {
        alert.disposed = "Y";
        alert.disposalId = disposalId;
        tx.inTransaction(connection -> {
            if (alertDao.updateById(alert, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "告警处置状态回写冲突（row_version 不匹配）：id=" + alert.id);
            }
            return null;
        });
    }

    private DisposalView toDisposalView(long disposalId) {
        RiskDisposal d = disposalDao.findById(disposalId);
        DisposalView v = new DisposalView();
        if (d != null) {
            v.id = d.id;
            v.subAccountId = d.subAccountId;
            v.isGlobal = d.isGlobal;
            v.disposalType = d.disposalType;
            v.scanScope = d.scanScope;
            v.reason = d.reason;
            v.dispatchStatus = d.dispatchStatus;
            v.dispatchVersion = d.dispatchVersion;
            v.disposalTime = d.disposalTime == null ? null : d.disposalTime.toString();
            v.releasedTime = d.releasedTime == null ? null : d.releasedTime.toString();
        }
        return v;
    }

    private void assertWritable(long userId, Integer subAccountId) {
        if (visibility == null) {
            return;
        }
        if (subAccountId != null && subAccountId > ALL_ACCOUNT_SENTINEL) {
            visibility.assertSubAccountWritable(userId, subAccountId);
        } else {
            visibility.assertSubAccountVisible(userId, ALL_ACCOUNT_SENTINEL);
        }
    }

    private static void append(StringBuilder where, String clause) {
        where.append(where.length() == 0 ? "" : " AND ").append(clause);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ------------------------------------------------------------ 视图与输入

    /** 告警查询筛选条件。 */
    public static final class AlertQuery {
        /** 子账户（收窄过滤，不决定可见集）。 */
        public Integer subAccountId;
        /** 告警类型（{@code alert_type}）。 */
        public String alertType;
        /** 告警级别（{@code INFO}/{@code WARNING}/{@code CRITICAL}）。 */
        public String alertLevel;
        /** 指标名。 */
        public String metricName;
        /** 处置状态（{@code Y}/{@code N}）。 */
        public String disposed;
        /** 起始告警时间（含）。 */
        public LocalDateTime fromTime;
        /** 截止告警时间（含）。 */
        public LocalDateTime toTime;
        /** 断线补拉起点（{@code alert_ts_ms > since}）。 */
        public Long sinceMs;
    }

    /** 告警行视图（{@code FR-RISK-19} 验收 2：含处置动作与处置人）。 */
    public static final class AlertView {
        /** 主键。 */
        public long id;
        /** 子账户。 */
        public Integer subAccountId;
        /** 告警类型。 */
        public String alertType;
        /** 级别。 */
        public String alertLevel;
        /** 指标名。 */
        public String metricName;
        /** 指标值。 */
        public String metricValue;
        /** 阈值。 */
        public String thresholdValue;
        /** 涉及合约。 */
        public String instrumentId;
        /** 详情。 */
        public String alertDetail;
        /** 告警毫秒时间戳。 */
        public long alertTsMs;
        /** 落库时间（ISO-8601）。 */
        public String alertTime;
        /** 是否已处置：{@code Y}/{@code N}（{@code N} 即未处置）。 */
        public String disposed;
        /** 关联处置主键。 */
        public Long disposalId;
        /** 处置动作（未处置为 {@code null}）。 */
        public String disposalType;
        /** 处置人（未处置为 {@code null}）。 */
        public Long disposalOperatorId;
        /** 处置时间（未处置为 {@code null}）。 */
        public String disposalTime;
        /** 下发状态（未处置/无需下发为 {@code null}）。 */
        public String dispatchStatus;
        /** 限制是否已解除。 */
        public boolean released;
    }

    /** 告警分页视图。 */
    public static final class AlertPage {
        /** 当前页行。 */
        public final List<AlertView> items;
        /** 总行数。 */
        public final long total;
        /** 页码。 */
        public final int page;
        /** 每页条数。 */
        public final int size;

        /**
         * 构造。
         *
         * @param items 行
         * @param total 总行数
         * @param page  页码
         * @param size  每页条数
         */
        public AlertPage(List<AlertView> items, long total, int page, int size) {
            this.items = items;
            this.total = total;
            this.page = page;
            this.size = size;
        }
    }

    /** 处置输入（{@code POST /risk/alert/{id}/dispose} 请求体）。 */
    public static final class DisposeInput {
        /** 处置动作。 */
        public String action;
        /** 处置理由（必填）。 */
        public String reason;
        /** 建议平仓合约（{@code FORCE_CLOSE_HINT}）。 */
        public String hintInstrumentId;
        /** 建议方向（{@code FORCE_CLOSE_HINT}）。 */
        public String hintDirection;
        /** 建议数量（{@code FORCE_CLOSE_HINT}）。 */
        public Integer hintVolume;
        /** 是否接受提示（{@code FORCE_CLOSE_HINT}；Y/N）。 */
        public String hintAccepted;
        /** 不接受理由（{@code hintAccepted=N} 时必填）。 */
        public String rejectReason;
    }

    /** 处置视图。 */
    public static final class DisposalView {
        /** 处置主键。 */
        public long id;
        /** 子账户。 */
        public Integer subAccountId;
        /** 是否全账户。 */
        public String isGlobal;
        /** 处置类型。 */
        public String disposalType;
        /** 限制范围（恒 {@code OPEN_ONLY}）。 */
        public String scanScope;
        /** 理由。 */
        public String reason;
        /** 下发状态。 */
        public String dispatchStatus;
        /** 下发版本。 */
        public Long dispatchVersion;
        /** 处置时间。 */
        public String disposalTime;
        /** 解除时间。 */
        public String releasedTime;
    }
}
