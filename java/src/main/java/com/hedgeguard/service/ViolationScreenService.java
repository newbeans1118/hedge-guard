package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.BaseDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradeOrderDao;
import com.hedgeguard.dao.TradePreTradeRejectDao;
import com.hedgeguard.dao.TradeReasonDao;
import com.hedgeguard.dao.ViolationScreenItemDao;
import com.hedgeguard.dao.ViolationScreenRunDao;
import com.hedgeguard.dao.entity.TradeOrder;
import com.hedgeguard.dao.entity.TradePreTradeReject;
import com.hedgeguard.dao.entity.TradeReason;
import com.hedgeguard.dao.entity.ViolationScreenItem;
import com.hedgeguard.dao.entity.ViolationScreenRun;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 违规事项筛查服务（T-068；FR-AUDIT-4、R-AUDIT-7、R-AUDIT-8、R-AUDIT-9、Q-13/CH-004）。
 *
 * <p><b>为何独立成类而非扩 {@link AuditService}</b>：T-067 的 {@code AuditService} 构造签名已冻结
 * （15 参，被 {@code Bootstrap} 与 {@code AuditTraceServiceTest} 依赖），本服务另需 5 个 DAO
 * （拒绝记录 / 指令 / 交易理由 / 筛查运行 / 命中项）。为避免破坏冻结面与既有装配，筛查部分以本类承载；
 * 二者同属 {@code AUDIT} 域、同包（{@code com.hedgeguard.service}）。
 *
 * <h2>筛查维度与口径（R-AUDIT-7：复用 RISK，不在审计侧另立）</h2>
 * <ul>
 *   <li><b>超品种 / 超规模 / 超期限 / 超授权</b>：读<b>事前风控拒绝记录</b>
 *       {@code t_pre_trade_reject}（合规证据，{@code R-TRADE-14}）——它是 RISK 规则命中在
 *       Java 侧的<b>原始权威记录</b>，自带 {@code rule_id} / {@code current_value} / {@code threshold_value}；
 *       按 {@code R-RISK-1..7 → 违规类型} 映射（{@link #mapRuleToType}，与
 *       {@code PostRiskReportServiceImpl.mapRuleToType} 同口径，不复立阈值）；
 *       命中对象经 {@code client_order_ref} 解析到 {@code t_trade_order}（{@code TRADE_ORDER}）。</li>
 *   <li><b>频繁开平仓</b>：由 {@code t_trade_order} 按「同一子账户 + 同一品种 + 同一日」的
 *       开平指令合计次数判定，阈值复用 {@code Q-5} 口径（{@code >=6} 告警 / {@code >=10} 限制，
 *       取自 {@link RiskThresholdSpec} 的合规默认值）。</li>
 *   <li><b>理由缺失（{@code REASON_MISSING}）/ 关联缺失（{@code RELATED_MISSING}）</b>：<b>并列独立</b>
 *       （{@code Q-13}/{@code CH-004}），由 {@code t_trade_reason} 派生
 *       （{@code DATA_MODEL.md} §2.9：采用「筛查侧生成」）：
 *       <ul>
 *         <li>{@code REASON_MISSING} = 无 {@code t_trade_reason} 记录，或记录存在但
 *             {@code reason_missing_flag='Y'} / 理由内容为空；</li>
 *         <li>{@code RELATED_MISSING} = <b>理由已填（内容非空）</b>但
 *             {@code related_object_missing_flag='Y'}（两关联列未提供，由
 *             {@code ck_trade_reason_related_or_missing} 结构强制）。</li>
 *         <li>二者<b>互斥</b>：理由内容为空者判 {@code REASON_MISSING}、不计 {@code RELATED_MISSING}；
 *             {@code related_object_missing_flag='Y'} 但内容非空者判 {@code RELATED_MISSING}、
 *             不计 {@code REASON_MISSING}。</li>
 *       </ul></li>
 * </ul>
 *
 * <h2>严重级别（LOW / MEDIUM / HIGH 域）</h2>
 * 按违规类型映射（{@link #severityForType}）：超规模 / 超授权 = {@code HIGH}；超品种 = {@code LOW}；
 * 超期限 / 频繁开平仓 / 理由缺失 / 关联缺失 = {@code MEDIUM}（频繁开平仓达限制阈值 {@code >=10} 时升 {@code HIGH}）。
 *
 * <h2>可复现（R-AUDIT-8）</h2>
 * 每次筛查落 {@code t_violation_screen_run}：记录筛查条件（时间范围 / 子账户范围 / 维度 /
 * 规则口径版本）、执行人、筛查时间与结论；命中项落 {@code t_violation_screen_item}。
 * 无命中时结论为「未发现违规」，<b>不得空白</b>。
 *
 * <h2>子账户软隔离（R-AUDIT-9 / NFR-SEC-7）</h2>
 * 可见范围经 {@link SubAccountVisibility#visibleSubAccountIds} 解析；含哨兵 {@code 0} 者跨账户只读
 * （不加过滤）；否则仅覆盖授权子账户，结论附明示边界（不得据此输出误导性的「无违规」）。
 */
public final class ViolationScreenService {

    // ------------------------------------------------------------ 筛查维度
    /** 超品种。 */
    public static final String DIM_OVER_PRODUCT = "OVER_PRODUCT";
    /** 超规模。 */
    public static final String DIM_OVER_SCALE = "OVER_SCALE";
    /** 超期限。 */
    public static final String DIM_OVER_TERM = "OVER_TERM";
    /** 超授权。 */
    public static final String DIM_OVER_AUTH = "OVER_AUTH";
    /** 频繁开平仓。 */
    public static final String DIM_FREQUENT_TRADE = "FREQUENT_TRADE";
    /** 理由缺失（审计侧独立维度，Q-13）。 */
    public static final String DIM_REASON_MISSING = "REASON_MISSING";
    /** 关联对象缺失（审计侧独立维度，Q-13）。 */
    public static final String DIM_RELATED_MISSING = "RELATED_MISSING";

    /** 全部维度（规范顺序；用于缺省与结果集顺序）。 */
    public static final List<String> ALL_DIMENSIONS = Collections.unmodifiableList(Arrays.asList(
            DIM_OVER_PRODUCT, DIM_OVER_SCALE, DIM_OVER_TERM, DIM_OVER_AUTH, DIM_FREQUENT_TRADE,
            DIM_REASON_MISSING, DIM_RELATED_MISSING));

    /** 规则类维度（来自事前拒绝记录）。 */
    private static final List<String> OVER_DIMENSIONS = Collections.unmodifiableList(Arrays.asList(
            DIM_OVER_PRODUCT, DIM_OVER_SCALE, DIM_OVER_TERM, DIM_OVER_AUTH));

    /** {@code RISK} 空间乙规则编号 → 违规类型（与 {@code PostRiskReportServiceImpl} 同口径）。 */
    private static final List<String> OVER_RULE_IDS = Collections.unmodifiableList(Arrays.asList(
            "R-RISK-1", "R-RISK-2", "R-RISK-3", "R-RISK-4", "R-RISK-5", "R-RISK-6", "R-RISK-7"));

    /** 频繁开平仓的规则归属（与 {@code PostRiskReportServiceImpl.RULE_ABNORMAL_ORDER} 同口径）。 */
    private static final String RULE_ABNORMAL_ORDER = "FR-RISK-14";

    // ------------------------------------------------------------ 严重级别
    /** 低。 */
    public static final String SEV_LOW = "LOW";
    /** 中。 */
    public static final String SEV_MEDIUM = "MEDIUM";
    /** 高。 */
    public static final String SEV_HIGH = "HIGH";

    // ------------------------------------------------------------ 对象类型
    /** 对象类型：交易指令。 */
    public static final String OBJ_TRADE_ORDER = "TRADE_ORDER";

    // ------------------------------------------------------------ 结论
    /** 无命中结论（FR-AUDIT-4 验收 6：不得空白）。 */
    public static final String CONCLUSION_NO_VIOLATION = "未发现违规";
    /** 运行中占位（状态流转中间态；同一事务内落库后即被终态覆盖）。 */
    private static final String CONCLUSION_RUNNING = "筛查进行中";
    /** 全账户口径标记。 */
    private static final String SCOPE_ALL = "ALL";

    /** 分页读页大小（{@code PageQuery} 白名单最大值）。 */
    private static final int PAGE_SIZE = 100;

    private final TradePreTradeRejectDao rejectDao;
    private final TradeOrderDao tradeOrderDao;
    private final TradeReasonDao tradeReasonDao;
    private final ViolationScreenRunDao runDao;
    private final ViolationScreenItemDao itemDao;
    private final SubAccountVisibility authService;
    private final TxTemplate tx;

    /**
     * 构造。
     *
     * @param rejectDao     事前风控拒绝记录 DAO
     * @param tradeOrderDao 交易指令 DAO
     * @param tradeReasonDao 交易理由 DAO
     * @param runDao        筛查运行 DAO
     * @param itemDao       筛查命中项 DAO
     * @param authService   可见范围取数（{@code NFR-SEC-7}）
     * @param tx            事务模板
     */
    public ViolationScreenService(TradePreTradeRejectDao rejectDao,
                                  TradeOrderDao tradeOrderDao,
                                  TradeReasonDao tradeReasonDao,
                                  ViolationScreenRunDao runDao,
                                  ViolationScreenItemDao itemDao,
                                  SubAccountVisibility authService,
                                  TxTemplate tx) {
        this.rejectDao = rejectDao;
        this.tradeOrderDao = tradeOrderDao;
        this.tradeReasonDao = tradeReasonDao;
        this.runDao = runDao;
        this.itemDao = itemDao;
        this.authService = authService;
        this.tx = tx;
    }

    // ------------------------------------------------------------ 筛查入口

    /**
     * 执行一次违规事项筛查（FR-AUDIT-4）：启动运行 → 派生命中 → 落库 run + item → 运行终态。
     *
     * @param req        筛查条件（时间范围必填；子账户 / 维度可缺省）
     * @param operatorId 执行人
     * @return 筛查结果（运行记录 + 命中清单 + 权限边界说明）
     */
    public ViolationScreenResult screen(ViolationScreenRequest req, long operatorId) {
        if (req == null || req.dateFrom == null || req.dateTo == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "筛查时间范围必填（dateFrom/dateTo，FR-AUDIT-4）");
        }
        if (req.dateFrom.isAfter(req.dateTo)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "筛查时间范围非法：dateFrom 晚于 dateTo（FR-AUDIT-4）");
        }
        List<Integer> visible = normalize(authService.visibleSubAccountIds(operatorId));
        boolean crossReadonly = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        Scope scope = resolveScope(req.subAccountIds, visible, crossReadonly);
        List<String> dims = resolveDimensions(req.dimensions);

        List<ViolationScreenItem> items = gather(scope, dims, req.dateFrom, req.dateTo);
        sortItems(items);

        ViolationScreenRun run = new ViolationScreenRun();
        run.screenNo = uniqueScreenNo(req.screenNo);
        run.dateFrom = req.dateFrom;
        run.dateTo = req.dateTo;
        run.subAccountScope = scope.label();
        run.screenDimensions = String.join(",", dims);
        run.ruleVersion = req.ruleVersion;
        run.operatorId = operatorId;
        run.screenTime = Clocks.now();
        run.hitCount = 0;
        run.conclusion = CONCLUSION_RUNNING;

        ViolationScreenResult result = tx.inTransaction(connection -> {
            if (runDao.insert(run, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "筛查运行落库失败（screen_no 唯一约束）：screenNo=" + run.screenNo);
            }
            for (ViolationScreenItem item : items) {
                item.screenRunId = run.id;
                if (itemDao.insert(item, operatorId) != 1) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "筛查命中项落库失败：screenRunId=" + run.id);
                }
            }
            // 运行记录终态（状态流转：RUNNING → 终态；本表 T-062 分类为「可更新」）。
            run.hitCount = items.size();
            run.conclusion = items.isEmpty()
                    ? CONCLUSION_NO_VIOLATION
                    : "发现 " + items.size() + " 条违规";
            if (runDao.updateById(run, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "筛查运行终态更新失败（并发冲突）：screenNo=" + run.screenNo);
            }
            ViolationScreenResult r = new ViolationScreenResult();
            r.run = run;
            r.items = items;
            r.visibleSubAccounts = new ArrayList<>(visible);
            r.permissionLimited = scope.permissionLimited;
            r.note = scope.note;
            return r;
        });
        return result;
    }

    /**
     * 查询筛查命中项（FR-AUDIT-4 输出；支持按时间 / 子账户 / 规则（违规类型）/ 批次筛选）。
     *
     * <p>时间范围按命中项的 {@code occur_time} 半开区间 {@code [from 00:00, to+1 00:00)} 下推 SQL。
     *
     * @param q          筛选条件
     * @param page       分页/排序
     * @param operatorId 操作人
     * @return 分页结果
     */
    public PageResult<ViolationScreenItem> queryItems(ViolationScreenQuery q, PageQuery page,
                                                      long operatorId) {
        List<Integer> visible = normalize(authService.visibleSubAccountIds(operatorId));
        boolean crossReadonly = visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL);
        List<Integer> requested = q != null && q.subAccountId != null
                ? Collections.singletonList(q.subAccountId) : null;
        Scope scope = resolveScope(requested, visible, crossReadonly);

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (q != null) {
            if (q.screenRunId != null) {
                append(where, "screen_run_id = ?");
                args.add(q.screenRunId);
            }
            if (q.violationType != null && !q.violationType.trim().isEmpty()) {
                append(where, "violation_type = ?");
                args.add(q.violationType.trim());
            }
            if (q.subAccountId != null) {
                append(where, "sub_account_id = ?");
                args.add(q.subAccountId);
            }
        }
        if (q != null && q.dateFrom != null) {
            append(where, "occur_time >= ?");
            args.add(q.dateFrom.atStartOfDay());
        }
        if (q != null && q.dateTo != null) {
            append(where, "occur_time < ?");
            args.add(q.dateTo.plusDays(1).atStartOfDay());
        }
        if (scope.scope != null) {
            append(where, scope.scope.sql());
            args.addAll(scope.scope.params());
        }
        return itemDao.findPage(page, where.length() == 0 ? null : where.toString(), args);
    }

    /**
     * 按筛查批次号取运行记录（可复现核对；R-AUDIT-8）。
     *
     * @param screenNo   批次号
     * @param operatorId 操作人
     * @return 运行记录；不存在返回 {@code null}
     */
    public ViolationScreenRun findRun(String screenNo, long operatorId) {
        if (screenNo == null || screenNo.trim().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "screenNo 必填");
        }
        return runDao.findByScreenNo(screenNo.trim());
    }

    // ------------------------------------------------------------ 派生命中

    private List<ViolationScreenItem> gather(Scope scope, List<String> dims,
                                             LocalDate from, LocalDate to) {
        List<ViolationScreenItem> items = new ArrayList<>();
        boolean needOver = containsAny(dims, OVER_DIMENSIONS);
        boolean needFreq = dims.contains(DIM_FREQUENT_TRADE);
        boolean needReason = dims.contains(DIM_REASON_MISSING) || dims.contains(DIM_RELATED_MISSING);

        if (needOver) {
            collectOverDimHits(items, scope, dims, from, to);
        }
        List<TradeOrder> orders = null;
        List<TradeReason> reasons = null;
        if (needReason) {
            reasons = fetchReasons(scope, from, to);
        }
        if (needFreq || needReason) {
            orders = fetchOrders(scope, from, to);
        }
        if (needFreq) {
            collectFrequentTradeHits(items, dims, orders, from, to);
        }
        if (needReason) {
            collectReasonHits(items, dims, reasons, orders, from, to);
        }
        return items;
    }

    private void collectOverDimHits(List<ViolationScreenItem> items, Scope scope, List<String> dims,
                                    LocalDate from, LocalDate to) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        where.append("rule_id IN (");
        for (int i = 0; i < OVER_RULE_IDS.size(); i++) {
            where.append(i == 0 ? "?" : ",?");
            args.add(OVER_RULE_IDS.get(i));
        }
        where.append(") AND trade_date >= ? AND trade_date <= ?");
        args.add(from);
        args.add(to);
        if (scope.scope != null) {
            where.append(" AND ").append(scope.scope.sql());
            args.addAll(scope.scope.params());
        }
        Map<String, TradeOrder> orderCache = new LinkedHashMap<>();
        for (TradePreTradeReject r : findAll(rejectDao, where.toString(), args)) {
            String type = mapRuleToType(r.ruleId);
            if (type == null || !dims.contains(type) || !inRange(r.tradeDate, from, to)) {
                continue;
            }
            TradeOrder order = resolveOrder(r.clientOrderRef, orderCache);
            ViolationScreenItem item = new ViolationScreenItem();
            item.subAccountId = r.subAccountId == null ? 0 : r.subAccountId;
            item.violationType = type;
            item.severity = severityForType(type);
            item.ruleId = r.ruleId;
            item.objectType = OBJ_TRADE_ORDER;
            item.objectId = order == null ? 0L : order.id;
            item.occurTime = order != null && order.submitTime != null ? order.submitTime : r.rejectTime;
            item.violationDesc = label(type) + "：rule=" + nz(r.ruleId) + " code=" + nz(r.rejectCode)
                    + " current=" + nz(r.currentValue) + " threshold=" + nz(r.thresholdValue)
                    + (order == null ? "（未解析到指令行，objectId=0）" : "");
            item.tradeReasonId = order == null ? null : resolveReasonId(order);
            item.relatedObjectInfo = null;
            items.add(item);
        }
    }

    private void collectFrequentTradeHits(List<ViolationScreenItem> items, List<String> dims,
                                          List<TradeOrder> orders, LocalDate from, LocalDate to) {
        if (!dims.contains(DIM_FREQUENT_TRADE) || orders == null) {
            return;
        }
        int warn = intSpec(RiskThresholdSpec.FREQ_INTRADAY_WARN);
        int block = intSpec(RiskThresholdSpec.FREQ_INTRADAY_BLOCK);
        Map<String, List<TradeOrder>> groups = new LinkedHashMap<>();
        for (TradeOrder o : orders) {
            if (o.subAccountId == null || o.productId == null || o.submitTime == null
                    || !isOpenClose(o.offsetFlag) || !inRange(o.submitTime.toLocalDate(), from, to)) {
                continue;
            }
            String key = o.subAccountId + "|" + o.productId + "|" + o.submitTime.toLocalDate();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }
        for (List<TradeOrder> group : groups.values()) {
            if (group.size() < warn) {
                continue;
            }
            TradeOrder rep = group.get(0);
            for (TradeOrder o : group) {
                if (o.id > rep.id) {
                    rep = o;
                }
            }
            ViolationScreenItem item = new ViolationScreenItem();
            item.subAccountId = rep.subAccountId;
            item.violationType = DIM_FREQUENT_TRADE;
            item.severity = group.size() >= block ? SEV_HIGH : SEV_MEDIUM;
            item.ruleId = RULE_ABNORMAL_ORDER;
            item.objectType = OBJ_TRADE_ORDER;
            item.objectId = rep.id;
            item.occurTime = rep.submitTime;
            item.violationDesc = "频繁开平仓：同一子账户 " + rep.subAccountId + " 品种 " + rep.productId
                    + " 于 " + rep.submitTime.toLocalDate() + " 日内开平合计 " + group.size()
                    + " 次（阈值 告警" + warn + "/限制" + block + "，Q-5）";
            item.tradeReasonId = resolveReasonId(rep);
            item.relatedObjectInfo = null;
            items.add(item);
        }
    }

    private void collectReasonHits(List<ViolationScreenItem> items, List<String> dims,
                                   List<TradeReason> reasons, List<TradeOrder> orders,
                                   LocalDate from, LocalDate to) {
        boolean wantReason = dims.contains(DIM_REASON_MISSING);
        boolean wantRelated = dims.contains(DIM_RELATED_MISSING);
        Set<Long> orderWithReason = new LinkedHashSet<>();
        if (reasons != null) {
            for (TradeReason r : reasons) {
                if (r.tradeOrderId != null) {
                    orderWithReason.add(r.tradeOrderId);
                }
                if (r.fillTime == null || !inRange(r.fillTime.toLocalDate(), from, to)) {
                    continue;
                }
                boolean reasonMissing = isReasonMissing(r);
                if (wantReason && reasonMissing) {
                    items.add(reasonMissingItem(r));
                } else if (wantRelated && !reasonMissing && "Y".equals(r.relatedObjectMissingFlag)) {
                    items.add(relatedMissingItem(r));
                }
            }
        }
        // 「无理由记录」= 指令存在但无 t_trade_reason 行（CH-004：REASON_MISSING = 无理由记录）。
        if (wantReason && orders != null) {
            for (TradeOrder o : orders) {
                if (o.submitTime == null || !inRange(o.submitTime.toLocalDate(), from, to)
                        || orderWithReason.contains(o.id)) {
                    continue;
                }
                ViolationScreenItem item = new ViolationScreenItem();
                item.subAccountId = o.subAccountId == null ? 0 : o.subAccountId;
                item.violationType = DIM_REASON_MISSING;
                item.severity = SEV_MEDIUM;
                item.ruleId = null;
                item.objectType = OBJ_TRADE_ORDER;
                item.objectId = o.id;
                item.occurTime = o.submitTime;
                item.violationDesc = "交易理由缺失：无 t_trade_reason 记录（FR-AUDIT-4 / Q-13）";
                item.tradeReasonId = null;
                item.relatedObjectInfo = null;
                items.add(item);
            }
        }
    }

    private static ViolationScreenItem reasonMissingItem(TradeReason r) {
        ViolationScreenItem item = new ViolationScreenItem();
        item.subAccountId = r.subAccountId == null ? 0 : r.subAccountId;
        item.violationType = DIM_REASON_MISSING;
        item.severity = SEV_MEDIUM;
        item.ruleId = null;
        item.objectType = OBJ_TRADE_ORDER;
        item.objectId = r.tradeOrderId == null ? 0L : r.tradeOrderId;
        item.occurTime = r.fillTime;
        item.violationDesc = "交易理由缺失：理由记录存在但内容为空/标记缺失"
                + "（reason_missing_flag=" + nz(r.reasonMissingFlag) + "；FR-AUDIT-4 / Q-13）";
        item.tradeReasonId = r.id;
        item.relatedObjectInfo = null;
        return item;
    }

    private static ViolationScreenItem relatedMissingItem(TradeReason r) {
        ViolationScreenItem item = new ViolationScreenItem();
        item.subAccountId = r.subAccountId == null ? 0 : r.subAccountId;
        item.violationType = DIM_RELATED_MISSING;
        item.severity = SEV_MEDIUM;
        item.ruleId = null;
        item.objectType = OBJ_TRADE_ORDER;
        item.objectId = r.tradeOrderId == null ? 0L : r.tradeOrderId;
        item.occurTime = r.fillTime;
        item.violationDesc = "关联对象缺失：理由已填但关联对象未提供（Q-13 / CH-004）";
        item.tradeReasonId = r.id;
        item.relatedObjectInfo = missingDescriptor(r);
        return item;
    }

    // ------------------------------------------------------------ 取数与工具

    private List<TradeReason> fetchReasons(Scope scope, LocalDate from, LocalDate to) {
        return findAll(tradeReasonDao, timeWhere("fill_time", scope), timeArgs("fill_time", scope, from, to));
    }

    private List<TradeOrder> fetchOrders(Scope scope, LocalDate from, LocalDate to) {
        return findAll(tradeOrderDao, timeWhere("submit_time", scope),
                timeArgs("submit_time", scope, from, to));
    }

    /** 时间半开区间 {@code [from 00:00, to+1 00:00)} + 子账户范围的 WHERE 片段。 */
    private static String timeWhere(String column, Scope scope) {
        String w = column + " >= ? AND " + column + " < ?";
        return scope.scope == null ? w : w + " AND " + scope.scope.sql();
    }

    private static List<Object> timeArgs(String column, Scope scope, LocalDate from, LocalDate to) {
        List<Object> args = new ArrayList<>();
        args.add(from.atStartOfDay());
        args.add(to.plusDays(1).atStartOfDay());
        if (scope.scope != null) {
            args.addAll(scope.scope.params());
        }
        return args;
    }

    private TradeOrder resolveOrder(String clientOrderRef, Map<String, TradeOrder> cache) {
        if (clientOrderRef == null) {
            return null;
        }
        if (cache.containsKey(clientOrderRef)) {
            return cache.get(clientOrderRef);
        }
        TradeOrder order = tradeOrderDao.findByClientOrderRef(clientOrderRef);
        cache.put(clientOrderRef, order);
        return order;
    }

    private Long resolveReasonId(TradeOrder order) {
        if (order == null) {
            return null;
        }
        if (order.tradeReasonId != null) {
            return order.tradeReasonId;
        }
        TradeReason r = tradeReasonDao.findByTradeOrderId(order.id);
        return r == null ? null : r.id;
    }

    private static boolean isReasonMissing(TradeReason r) {
        if ("Y".equals(r.reasonMissingFlag)) {
            return true;
        }
        return r.reasonText == null || r.reasonText.trim().isEmpty();
    }

    private static String missingDescriptor(TradeReason r) {
        if (r.relatedPlanId == null && r.relatedExposureId == null) {
            return "缺失：计划ID、实货敞口ID";
        }
        if (r.relatedPlanId == null) {
            return "缺失：计划ID";
        }
        return "缺失：实货敞口ID";
    }

    /**
     * {@code RISK} 规则编号 → 违规类型（与 {@code PostRiskReportServiceImpl.mapRuleToType} 同口径；
     * 不复立阈值，仅复用编号归类）。{@code R-RISK-8}（价格偏离）不属六类，返回 {@code null}。
     */
    static String mapRuleToType(String ruleId) {
        if (ruleId == null) {
            return null;
        }
        switch (ruleId) {
            case "R-RISK-1":
                return DIM_OVER_PRODUCT;
            case "R-RISK-2":
            case "R-RISK-3":
            case "R-RISK-4":
            case "R-RISK-5":
                return DIM_OVER_SCALE;
            case "R-RISK-6":
                return DIM_OVER_TERM;
            case "R-RISK-7":
                return DIM_OVER_AUTH;
            default:
                return null;
        }
    }

    /** 违规类型 → 严重级别（LOW/MEDIUM/HIGH 域；频繁开平仓按次数另判，见 {@link #collectFrequentTradeHits}）。 */
    static String severityForType(String type) {
        if (DIM_OVER_PRODUCT.equals(type)) {
            return SEV_LOW;
        }
        if (DIM_OVER_SCALE.equals(type) || DIM_OVER_AUTH.equals(type)) {
            return SEV_HIGH;
        }
        return SEV_MEDIUM;
    }

    private static String label(String type) {
        switch (type) {
            case DIM_OVER_PRODUCT:
                return "超品种";
            case DIM_OVER_SCALE:
                return "超规模";
            case DIM_OVER_TERM:
                return "超期限";
            case DIM_OVER_AUTH:
                return "超授权";
            case DIM_FREQUENT_TRADE:
                return "频繁开平仓";
            case DIM_REASON_MISSING:
                return "理由缺失";
            case DIM_RELATED_MISSING:
                return "关联缺失";
            default:
                return type;
        }
    }

    private static boolean isOpenClose(String offsetFlag) {
        return "OPEN".equals(offsetFlag) || "CLOSE".equals(offsetFlag)
                || "CLOSE_TODAY".equals(offsetFlag);
    }

    private static int intSpec(RiskThresholdSpec spec) {
        return Integer.parseInt(spec.defaultValue());
    }

    private static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        if (d == null) {
            return false;
        }
        if (from != null && d.isBefore(from)) {
            return false;
        }
        return to == null || !d.isAfter(to);
    }

    private static boolean containsAny(List<String> dims, List<String> candidates) {
        for (String c : candidates) {
            if (dims.contains(c)) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<T> findAll(BaseDao<T, ?> dao, String where, List<Object> args) {
        List<T> all = new ArrayList<>();
        int page = 1;
        while (true) {
            PageQuery query = PageQuery.of(String.valueOf(page), String.valueOf(PAGE_SIZE), "id,asc", "id");
            PageResult<T> result = dao.findPage(query, where, args);
            all.addAll(result.items());
            if (result.items().size() < PAGE_SIZE) {
                return all;
            }
            page++;
        }
    }

    private static void append(StringBuilder where, String clause) {
        if (where.length() > 0) {
            where.append(" AND ");
        }
        where.append(clause);
    }

    private static void sortItems(List<ViolationScreenItem> items) {
        items.sort((a, b) -> {
            int c = Integer.compare(ALL_DIMENSIONS.indexOf(a.violationType),
                    ALL_DIMENSIONS.indexOf(b.violationType));
            if (c != 0) {
                return c;
            }
            c = Long.compare(a.objectId, b.objectId);
            if (c != 0) {
                return c;
            }
            return Long.compare(a.id, b.id);
        });
    }

    private List<Integer> normalize(List<Integer> raw) {
        List<Integer> out = new ArrayList<>();
        if (raw != null) {
            for (Integer id : raw) {
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static List<String> resolveDimensions(Set<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return new ArrayList<>(ALL_DIMENSIONS);
        }
        List<String> dims = new ArrayList<>();
        for (String d : requested) {
            if (d == null) {
                continue;
            }
            String t = d.trim().toUpperCase(java.util.Locale.ROOT);
            if (!ALL_DIMENSIONS.contains(t)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "未知筛查维度：" + d + "（合法值=" + ALL_DIMENSIONS + "）");
            }
            if (!dims.contains(t)) {
                dims.add(t);
            }
        }
        if (dims.isEmpty()) {
            return new ArrayList<>(ALL_DIMENSIONS);
        }
        dims.sort((a, b) -> Integer.compare(ALL_DIMENSIONS.indexOf(a), ALL_DIMENSIONS.indexOf(b)));
        return dims;
    }

    private Scope resolveScope(List<Integer> requested, List<Integer> visible, boolean crossReadonly) {
        List<Integer> effective;
        if (requested == null || requested.isEmpty()) {
            effective = crossReadonly ? null : new ArrayList<>(visible);
        } else {
            effective = new ArrayList<>();
            for (Integer id : requested) {
                if (id == null) {
                    continue;
                }
                if (!crossReadonly && !visible.contains(id)) {
                    throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                            "无权筛查该子账户：subAccountId=" + id
                                    + "（R-AUDIT-9 / NFR-SEC-7；复现=授予该子账户读权限或跨账户只读）");
                }
                effective.add(id);
            }
        }
        SubAccountScope scope = effective == null ? null
                : SubAccountScope.of("sub_account_id", effective);
        boolean permissionLimited = !crossReadonly;
        String note = permissionLimited
                ? "软隔离：结论仅覆盖授权子账户范围（" + visible + "），不代表全账户（R-AUDIT-9）"
                : null;
        return new Scope(effective, scope, permissionLimited, note);
    }

    private String uniqueScreenNo(String requested) {
        if (requested != null && !requested.trim().isEmpty()) {
            String no = requested.trim();
            if (runDao.findByScreenNo(no) != null) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT, "筛查批次号已存在：screenNo=" + no);
            }
            return no;
        }
        String base = "VS" + Clocks.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String candidate = base;
        int seq = 1;
        while (runDao.findByScreenNo(candidate) != null) {
            seq++;
            candidate = base + "-" + seq;
        }
        return candidate;
    }

    private static String nz(String v) {
        return v == null ? "-" : v;
    }

    // ------------------------------------------------------------ 参数/结果

    /** 筛查入参（FR-AUDIT-4 输入列）。 */
    public static final class ViolationScreenRequest {
        /** 筛查时间范围（起）。 */
        public LocalDate dateFrom;
        /** 筛查时间范围（止）。 */
        public LocalDate dateTo;
        /** 子账户范围（{@code null}/空 = 全部可见）。 */
        public List<Integer> subAccountIds;
        /** 筛查维度（{@code null}/空 = 全部维度）。 */
        public Set<String> dimensions;
        /** 所用规则口径版本（可空；{@code R-AUDIT-7}）。 */
        public String ruleVersion;
        /** 指定筛查批次号（可空；缺省自动生成）。 */
        public String screenNo;
    }

    /** 筛查查询条件（GET；FR-AUDIT-4 验收 4「按时间/子账户/规则筛选」）。 */
    public static final class ViolationScreenQuery {
        /** 命中发生时间范围（起）。 */
        public LocalDate dateFrom;
        /** 命中发生时间范围（止）。 */
        public LocalDate dateTo;
        /** 子账户。 */
        public Integer subAccountId;
        /** 违规类型（规则维度）。 */
        public String violationType;
        /** 筛查批次（{@code t_violation_screen_run.id}）。 */
        public Long screenRunId;
    }

    /** 筛查结果。 */
    public static final class ViolationScreenResult {
        /** 筛查运行记录。 */
        public ViolationScreenRun run;
        /** 命中清单。 */
        public List<ViolationScreenItem> items = new ArrayList<>();
        /** 可见子账户集合。 */
        public List<Integer> visibleSubAccounts = new ArrayList<>();
        /** 是否受软隔离限制（未含哨兵 0）。 */
        public boolean permissionLimited;
        /** 权限边界说明（{@code R-AUDIT-9}）。 */
        public String note;
    }

    /** 解析后的子账户范围。 */
    private static final class Scope {
        private final List<Integer> effective;
        private final SubAccountScope scope;
        private final boolean permissionLimited;
        private final String note;

        private Scope(List<Integer> effective, SubAccountScope scope, boolean permissionLimited,
                      String note) {
            this.effective = effective;
            this.scope = scope;
            this.permissionLimited = permissionLimited;
            this.note = note;
        }

        private String label() {
            if (effective == null) {
                return SCOPE_ALL;
            }
            StringBuilder sb = new StringBuilder();
            for (Integer id : effective) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
            return sb.length() == 0 ? "NONE" : sb.toString();
        }
    }
}
