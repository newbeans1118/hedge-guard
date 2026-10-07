package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.OperationLogDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PlanApprovalDao;
import com.hedgeguard.dao.SettleCycleDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradeFillDao;
import com.hedgeguard.dao.TradeOrderDao;
import com.hedgeguard.dao.TradeOrderReportDao;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.TradeReasonDao;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.OperationLog;
import com.hedgeguard.dao.entity.PlanApproval;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.TradeFill;
import com.hedgeguard.dao.entity.TradeOrder;
import com.hedgeguard.dao.entity.TradeOrderReport;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 审计服务（T-067：全链路追溯 + 操作留痕查询；ARCHITECTURE.md 6.11）。
 *
 * <p>本类当前交付<b>追溯部分</b>（FR-AUDIT-1/2/3/11、R-AUDIT-2/11、CFG-17c）：
 * <ul>
 *   <li>{@link #traceForward}：正向 实货敞口 - 套保计划 - 审批 - 交易（指令/委托/成交）- 结算 - 会计凭证；</li>
 *   <li>{@link #traceBackward}：反向（起点为会计凭证或交易）；</li>
 *   <li>{@link #checkIntegrity}：完整性校验 + 断点清单（四态 COMPLETE/HAS_BREAK/IN_FLIGHT/UNKNOWN）；</li>
 *   <li>{@link #queryOperationLog}：操作留痕查询（FR-AUDIT-11，只增不改不删）。</li>
 * </ul>
 *
 * <p><b>在途时窗（CFG-17c）</b>：已发 CTP 未回报（t_trade_order.status='SENT_CTP'）
 * 或已结算未出凭证的业务按两档时窗判定 —— 当日结算时点前 IN_FLIGHT（不计断点）→
 * 逾时 UNKNOWN+告警 → 再逾 audit.chain.inflight_max_cycles 个结算周期 HAS_BREAK；
 * 数据缺失一律 UNKNOWN，<b>不得</b>记为在途。
 *
 * <p><b>子账户维度（FR-AUDIT-1 验收 4 / NFR-SEC-7）</b>：可见范围经
 * {@link SubAccountVisibility#visibleSubAccountIds} 解析，跨账户只读（哨兵 0）不加过滤；越权起始对象
 * 返回 permissionLimited=true 并明示边界，不泄露出对象内容。
 *
 * <p><b>诚实披露（末端段数据源缺口）</b>：会计凭证 t_voucher 属 ACCT 模块（T-076~T-080，均 TODO），
 * 当前 Java 侧无 entity/DAO 且无建表脚本 ⇒ 本服务以 {@link VoucherLookup} 接缝承接（M1 未装配时该段判 UNKNOWN，
 * <b>不得</b>误标完整）。T-079 落地后经 Bootstrap 装入实现即可闭合，无需改本类遍历逻辑。
 */
public final class AuditService {

    /** 段名：实货敞口。 */
    public static final String SEG_EXPOSURE = "EXPOSURE";
    /** 段名：套保计划。 */
    public static final String SEG_HEDGE_PLAN = "HEDGE_PLAN";
    /** 段名：审批。 */
    public static final String SEG_APPROVAL = "APPROVAL";
    /** 段名：交易（指令/委托/成交）。 */
    public static final String SEG_TRADE = "TRADE";
    /** 段名：结算。 */
    public static final String SEG_SETTLE = "SETTLE";
    /** 段名：会计凭证。 */
    public static final String SEG_VOUCHER = "VOUCHER";

    /** 完整性：完整。 */
    public static final String COMPLETE = "COMPLETE";
    /** 完整性：存在断点。 */
    public static final String HAS_BREAK = "HAS_BREAK";
    /** 完整性：含在途（不计断点）。 */
    public static final String IN_FLIGHT = "IN_FLIGHT";
    /** 完整性：无法判定（数据缺失）。 */
    public static final String UNKNOWN = "UNKNOWN";

    /** 六段必需环节（R-AUDIT-1 顺序即遍历顺序）。 */
    private static final List<String> SEGMENTS = Collections.unmodifiableList(Arrays.asList(
            SEG_EXPOSURE, SEG_HEDGE_PLAN, SEG_APPROVAL, SEG_TRADE, SEG_SETTLE, SEG_VOUCHER));

    /** 在途定义：已发 CTP 且未收到回报（DATA_MODEL.md 4.4 判据 19）。 */
    private static final String STATUS_SENT_CTP = "SENT_CTP";
    /** 结算周期长度（天）；CFG-17c 的「再逾上限」按结算周期数计。 */
    private static final int CYCLE_DAYS = 1;

    private final ExposureDao exposureDao;
    private final HedgePlanExposureDao hedgePlanExposureDao;
    private final HedgePlanDao hedgePlanDao;
    private final PlanApprovalDao planApprovalDao;
    private final TradePlanDao tradePlanDao;
    private final TradeOrderDao tradeOrderDao;
    private final TradeOrderReportDao tradeOrderReportDao;
    private final TradeFillDao tradeFillDao;
    private final TradeReasonDao tradeReasonDao;
    private final SettleCycleDao settleCycleDao;
    private final OperationLogDao operationLogDao;
    private final SubAccountVisibility authService;
    private final VoucherLookup voucherLookup;
    private final java.util.function.IntSupplier inflightMaxCyclesSupplier;
    private final SettlementCutoff settlementCutoff;

    /**
     * 构造。
     *
     * @param exposureDao         实货敞口 DAO
     * @param hedgePlanExposureDao 计划-敞口绑定 DAO
     * @param hedgePlanDao        套保计划 DAO
     * @param planApprovalDao     审批链 DAO
     * @param tradePlanDao        交易计划 DAO
     * @param tradeOrderDao       交易指令 DAO
     * @param tradeOrderReportDao 委托回报 DAO
     * @param tradeFillDao        成交 DAO
     * @param tradeReasonDao      交易理由 DAO
     * @param settleCycleDao      结算单 DAO
     * @param operationLogDao     操作留痕 DAO
     * @param authService         可见范围取数（NFR-SEC-7 最小抽象；生产为 AuthService）
     * @param voucherLookup       会计凭证查询接缝（null ⇒ 该段判 UNKNOWN，见类注释）
     * @param inflightMaxCycles   在途时窗上限取数（结算周期数；CFG-17c，1-3）。
     *                            <b>惰性求值</b>：装配期不得触库（生产装配在假驱动下亦须可完成）
     *                            ⇒ 以 {@link java.util.function.IntSupplier} 承接，调用时读取并夹取 1-3。
     * @param settlementCutoff    当日结算时点解析（null ⇒ 缺省为交易日次日 00:00）
     */
    public AuditService(ExposureDao exposureDao,
                        HedgePlanExposureDao hedgePlanExposureDao,
                        HedgePlanDao hedgePlanDao,
                        PlanApprovalDao planApprovalDao,
                        TradePlanDao tradePlanDao,
                        TradeOrderDao tradeOrderDao,
                        TradeOrderReportDao tradeOrderReportDao,
                        TradeFillDao tradeFillDao,
                        TradeReasonDao tradeReasonDao,
                        SettleCycleDao settleCycleDao,
                        OperationLogDao operationLogDao,
                        SubAccountVisibility authService,
                        VoucherLookup voucherLookup,
                        java.util.function.IntSupplier inflightMaxCycles,
                        SettlementCutoff settlementCutoff) {
        this.exposureDao = exposureDao;
        this.hedgePlanExposureDao = hedgePlanExposureDao;
        this.hedgePlanDao = hedgePlanDao;
        this.planApprovalDao = planApprovalDao;
        this.tradePlanDao = tradePlanDao;
        this.tradeOrderDao = tradeOrderDao;
        this.tradeOrderReportDao = tradeOrderReportDao;
        this.tradeFillDao = tradeFillDao;
        this.tradeReasonDao = tradeReasonDao;
        this.settleCycleDao = settleCycleDao;
        this.operationLogDao = operationLogDao;
        this.authService = authService;
        this.voucherLookup = voucherLookup;
        this.inflightMaxCyclesSupplier = inflightMaxCycles == null ? () -> 1 : inflightMaxCycles;
        this.settlementCutoff = settlementCutoff == null ? endOfTradeDay() : settlementCutoff;
    }

    /** 缺省当日结算时点：交易日次日 00:00（日终结算）；可经构造注入覆盖。 */
    public static SettlementCutoff endOfTradeDay() {
        return tradeDate -> tradeDate.plusDays(1).atStartOfDay();
    }

    // ------------------------------------------------------------ 对外能力

    /**
     * 正向追溯（起点=实货敞口）。
     *
     * @param exposureId 敞口主键
     * @param toSegment  追溯深度（null/空 = 全链；否则为段名，含该段）
     * @param operatorId 操作人
     * @return 追溯结果
     */
    public TraceResult traceForward(long exposureId, String toSegment, long operatorId) {
        TraceResult r = new TraceResult();
        r.startType = SEG_EXPOSURE;
        r.startId = String.valueOf(exposureId);
        List<Integer> allowed = authService.visibleSubAccountIds(operatorId);
        r.visibleSubAccounts = new ArrayList<>(allowed);
        Chain c = new Chain(allowed);
        walkFromExposure(exposureId, c);
        finish(r, c, toSegment, false);
        return r;
    }

    /**
     * 反向追溯（起点=会计凭证 或 交易）。
     *
     * @param startType  起点类型（VOUCHER / TRADE）
     * @param startId    起点标识（凭证号或主键字符串 / 指令或成交主键字符串）
     * @param toSegment  追溯深度（null/空 = 全链）
     * @param operatorId 操作人
     * @return 追溯结果（节点按反向顺序）
     */
    public TraceResult traceBackward(String startType, String startId, String toSegment, long operatorId) {
        TraceResult r = new TraceResult();
        r.startType = startType == null ? "" : startType.toUpperCase(java.util.Locale.ROOT);
        r.startId = startId;
        List<Integer> allowed = authService.visibleSubAccountIds(operatorId);
        r.visibleSubAccounts = new ArrayList<>(allowed);
        Chain c = new Chain(allowed);
        Long exposureId = null;
        TraceNode startNode = null;
        if ("VOUCHER".equals(r.startType)) {
            if (voucherLookup == null) {
                c.addUnknown(SEG_VOUCHER, "ACCT 未交付（T-076~T-080）：会计凭证无数据源，无法反向定位");
            } else {
                VoucherRef v = voucherLookup.findByVoucher(startId);
                if (v == null) {
                    c.addBreak(SEG_VOUCHER, "t_voucher", startId, "凭证不存在或不可见");
                } else {
                    startNode = node(SEG_VOUCHER, "t_voucher", v.id, v.voucherNo, v.subAccountId, v.status,
                            detail("voucherType", v.voucherType, "hedgePlanId", v.hedgePlanId,
                                    "exposureId", v.exposureId, "settleCycleId", v.settleCycleId));
                    exposureId = resolveExposureOfVoucher(v);
                }
            }
        } else if ("TRADE".equals(r.startType)) {
            StartResolution sr = resolveExposureOfTrade(startId, c);
            exposureId = sr.exposureId;
            startNode = sr.node;
        } else {
            c.addBreak(SEG_TRADE, null, startId, "起点类型须为 VOUCHER 或 TRADE");
        }
        if (exposureId != null) {
            walkFromExposure(exposureId, c);
        }
        finish(r, c, toSegment, true);
        // 起点节点若未被正向遍历纳入（如凭证仅按编号可查），补于链首；否则不重复。
        if (startNode != null && !containsNode(r.nodes, startNode)) {
            r.nodes.add(0, startNode);
        }
        return r;
    }

    /**
     * 完整性校验（等价于正向追溯并返回校验结论；起点=敞口）。
     *
     * @param exposureId 敞口主键
     * @param operatorId 操作人
     * @return 追溯结果（含 chainIntegrity / breakList）
     */
    public TraceResult checkIntegrity(long exposureId, long operatorId) {
        return traceForward(exposureId, null, operatorId);
    }

    /**
     * 操作留痕查询（FR-AUDIT-11；只增不改不删，审计岗可跨子账户查询）。
     *
     * @param q          筛选条件
     * @param query      分页/排序（缺省方向须由调用方显式声明）
     * @param operatorId 操作人
     * @return 分页结果
     */
    public PageResult<OperationLog> queryOperationLog(OperationLogQuery q, PageQuery query, long operatorId) {
        List<Integer> allowed = authService.visibleSubAccountIds(operatorId);
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (q != null) {
            if (q.fromTime != null) {
                where.append(where.length() == 0 ? "" : " AND ").append("operation_time >= ?");
                args.add(q.fromTime);
            }
            if (q.toTime != null) {
                where.append(where.length() == 0 ? "" : " AND ").append("operation_time <= ?");
                args.add(q.toTime);
            }
            if (q.operatorId != null) {
                where.append(where.length() == 0 ? "" : " AND ").append("operator_id = ?");
                args.add(q.operatorId);
            }
            if (q.operationType != null && !q.operationType.trim().isEmpty()) {
                where.append(where.length() == 0 ? "" : " AND ").append("operation_type = ?");
                args.add(q.operationType);
            }
            if (q.objectType != null && !q.objectType.trim().isEmpty()) {
                where.append(where.length() == 0 ? "" : " AND ").append("object_type = ?");
                args.add(q.objectType);
            }
            if (q.subAccountId != null) {
                where.append(where.length() == 0 ? "" : " AND ").append("sub_account_id = ?");
                args.add(q.subAccountId);
            }
        }
        if (allowed == null || !allowed.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", allowed);
            where.append(where.length() == 0 ? "" : " AND ").append(scope.sql());
            args.addAll(scope.params());
        }
        String w = where.length() == 0 ? null : where.toString();
        return operationLogDao.findPage(query, w, args);
    }

    // ------------------------------------------------------------ 遍历实现

    private void walkFromExposure(long exposureId, Chain c) {
        Exposure ex = exposureDao.findById(exposureId);
        if (ex == null) {
            c.addBreak(SEG_EXPOSURE, "t_exposure", String.valueOf(exposureId), "追溯起点不存在");
            return;
        }
        if (!visible(ex.subAccountId, c.allowed)) {
            c.permissionLimited = true;
            c.addBreak(SEG_EXPOSURE, "t_exposure", String.valueOf(exposureId),
                    "起点子账户不在授权范围（权限边界）；仅可查看被授权子账户");
            return;
        }
        c.addNode(node(SEG_EXPOSURE, "t_exposure", ex.id, null, ex.subAccountId, ex.direction,
                detail("exposureQty", ex.exposureQty, "calcMode", ex.calcMode, "asOfDate", ex.asOfDate,
                        "snapshotId", ex.snapshotId)));

        List<HedgePlanExposure> links = filterVisible(hedgePlanExposureDao.findByExposure(exposureId),
                x -> x.subAccountId, c.allowed);
        if (links.isEmpty()) {
            c.addBreak(SEG_HEDGE_PLAN, "t_hedge_plan", null, "该敞口无绑定的套保计划（环节无记录）");
            return;
        }
        List<TradeOrder> allOrders = new ArrayList<>();
        for (HedgePlanExposure link : links) {
            HedgePlan plan = link.hedgePlanId == null ? null : hedgePlanDao.findById(link.hedgePlanId);
            if (plan == null) {
                c.addBreak(SEG_HEDGE_PLAN, "t_hedge_plan", String.valueOf(link.hedgePlanId), "绑定的套保计划不存在");
                continue;
            }
            if (!visible(plan.subAccountId, c.allowed)) {
                continue;
            }
            c.addNode(node(SEG_HEDGE_PLAN, "t_hedge_plan", plan.id, plan.planNo, plan.subAccountId, plan.status,
                    detail("planQty", plan.planQty, "executedQty", plan.executedQty, "orderedQty", plan.orderedQty,
                            "hedgeDirection", plan.hedgeDirection)));

            List<PlanApproval> approvals = filterVisible(planApprovalDao.findByPlan(plan.id),
                    x -> x.subAccountId, c.allowed);
            for (PlanApproval a : approvals) {
                c.addNode(node(SEG_APPROVAL, "t_plan_approval", a.id, null, a.subAccountId, a.approvalResult,
                        detail("nodeSeq", a.nodeSeq, "nodeType", a.nodeType, "approverId", a.approverId,
                                "actionTime", a.actionTime)));
            }
            if (approvals.isEmpty()) {
                c.addBreak(SEG_APPROVAL, "t_plan_approval", String.valueOf(plan.id), "计划无审批记录（环节无记录）");
            }

            List<TradeOrder> orders = filterVisible(tradeOrderDao.findByHedgePlan(plan.id),
                    x -> x.subAccountId, c.allowed);
            if (orders.isEmpty()) {
                c.addBreak(SEG_TRADE, "t_trade_order", String.valueOf(plan.id), "计划无交易指令（环节无记录）");
            }
            for (TradeOrder o : orders) {
                allOrders.add(o);
                c.addNode(node(SEG_TRADE, "t_trade_order", o.id, o.clientOrderRef, o.subAccountId, o.status,
                        detail("instrumentId", o.instrumentId, "direction", o.direction, "volume", o.volume,
                                "offsetFlag", o.offsetFlag, "submitTime", o.submitTime, "orderId", o.orderId)));
                // t_trade_order_report.order_id / t_trade_fill.order_id 外键指向 t_trade_order(id)（本地主键），
                // 非 C++ order_id（TE-T058-004 口径）⇒ 以 o.id 关联。
                for (TradeOrderReport rep : filterVisible(tradeOrderReportDao.findByOrder(o.id),
                        x -> x.subAccountId, c.allowed)) {
                    c.addNode(node(SEG_TRADE, "t_trade_order_report", rep.id, rep.orderSysId, rep.subAccountId,
                            rep.orderStatus, detail("volumeTotal", rep.volumeTotal,
                                    "volumeTraded", rep.volumeTraded, "reportTime", rep.reportTime)));
                }
                for (TradeFill f : filterVisible(tradeFillDao.findByOrder(o.id), x -> x.subAccountId, c.allowed)) {
                    c.addNode(node(SEG_TRADE, "t_trade_fill", f.id, f.tradeId, f.subAccountId, f.settledFlag,
                            detail("fillPrice", f.fillPrice, "fillVolume", f.fillVolume, "tradeDate", f.tradeDate,
                                    "fillTime", f.fillTime)));
                }
            }
        }
        walkSettleAndVoucher(allOrders, c);
    }

    private void walkSettleAndVoucher(List<TradeOrder> orders, Chain c) {
        List<TradeFill> fills = new ArrayList<>();
        boolean hasInFlightOrder = false;
        LocalDate inFlightOrderDay = null;
        for (TradeOrder o : orders) {
            if (STATUS_SENT_CTP.equals(o.status)) {
                hasInFlightOrder = true;
                if (inFlightOrderDay == null) {
                    inFlightOrderDay = orderTradeDay(o);
                }
            }
            // t_trade_fill.order_id 外键指向 t_trade_order(id)（本地主键）⇒ 以 o.id 关联（TE-T058-004）。
            fills.addAll(filterVisible(tradeFillDao.findByOrder(o.id), x -> x.subAccountId, c.allowed));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<SettleCycle> cycles = new ArrayList<>();
        LocalDate fillDay = null;
        boolean fillDayMissing = false;
        for (TradeFill f : fills) {
            if (f.subAccountId == null || f.tradeDate == null) {
                fillDayMissing = true;
                continue;
            }
            if (seen.add(f.subAccountId + "|" + f.tradeDate)) {
                cycles.addAll(filterVisible(settleCycleDao.findBySubAccountAndDate(f.subAccountId, f.tradeDate),
                        x -> x.subAccountId, c.allowed));
            }
            if (fillDay == null) {
                fillDay = f.tradeDate;
            }
        }

        boolean settled = false;
        for (SettleCycle cy : cycles) {
            boolean ok = isSettled(cy.status);
            settled = settled || ok;
            c.addNode(node(SEG_SETTLE, "t_settle_cycle", cy.id, cy.settleNo, cy.subAccountId, cy.status,
                    detail("tradeDate", cy.tradeDate, "confirmedTime", cy.confirmedTime, "equity", cy.equity)));
        }

        boolean settleInFlight = false;
        boolean settleBreak = false;
        if (hasInFlightOrder) {
            applyInFlight(c, SEG_SETTLE, inFlightOrderDay, "已发 CTP 未收到回报（已成交未结算）");
            settleInFlight = true;
        } else if (!fills.isEmpty() && !settled) {
            if (fillDayMissing && fillDay == null) {
                c.addUnknown(SEG_SETTLE, "成交记录交易日缺失，无法判定在途时窗");
            } else {
                applyInFlight(c, SEG_SETTLE, fillDay, "已成交未结算");
                settleInFlight = true;
            }
        } else if (fills.isEmpty() && orders.isEmpty()) {
            c.addBreak(SEG_SETTLE, "t_settle_cycle", null, "无交易，无法结算（环节无记录）");
            settleBreak = true;
        } else if (fills.isEmpty()) {
            c.addBreak(SEG_SETTLE, "t_settle_cycle", null, "无成交记录，无法结算（环节无记录）");
            settleBreak = true;
        }

        if (settleBreak) {
            c.addBreak(SEG_VOUCHER, "t_voucher", null, "前置结算段断裂，凭证不可判定");
            return;
        }
        if (settleInFlight) {
            c.addInFlight(SEG_VOUCHER, "前置结算在途，凭证尚未产生");
            return;
        }
        if (voucherLookup == null) {
            c.addUnknown(SEG_VOUCHER, "ACCT 未交付（T-076~T-080）：会计凭证无数据源，无法判定");
            return;
        }
        List<VoucherRef> vouchers = collectVouchers(c);
        if (!vouchers.isEmpty()) {
            for (VoucherRef v : vouchers) {
                c.addNode(node(SEG_VOUCHER, "t_voucher", v.id, v.voucherNo, v.subAccountId, v.status,
                        detail("voucherType", v.voucherType, "hedgePlanId", v.hedgePlanId,
                                "exposureId", v.exposureId, "settleCycleId", v.settleCycleId)));
            }
            return;
        }
        LocalDate settledDay = null;
        for (SettleCycle cy : cycles) {
            if (isSettled(cy.status)) {
                settledDay = cy.tradeDate;
                break;
            }
        }
        applyInFlight(c, SEG_VOUCHER, settledDay != null ? settledDay : fillDay, "已结算未出凭证");
    }

    /** 收集与本次链相关的凭证（按 敞口/计划/结算单 三路并集）。 */
    private List<VoucherRef> collectVouchers(Chain c) {
        Map<String, VoucherRef> byKey = new LinkedHashMap<>();
        for (TraceNode n : c.nodes) {
            Long id = parseLong(n.objectId);
            if (id == null) {
                continue;
            }
            List<VoucherRef> vs;
            if ("t_exposure".equals(n.objectType)) {
                vs = voucherLookup.findByExposure(id);
            } else if ("t_hedge_plan".equals(n.objectType)) {
                vs = voucherLookup.findByHedgePlan(id);
            } else if ("t_settle_cycle".equals(n.objectType)) {
                vs = voucherLookup.findBySettleCycle(id);
            } else {
                continue;
            }
            if (vs == null) {
                continue;
            }
            for (VoucherRef v : vs) {
                if (v != null && visible(v.subAccountId, c.allowed)) {
                    byKey.put(v.id + "#" + v.voucherNo, v);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private Long resolveExposureOfVoucher(VoucherRef v) {
        if (v.exposureId != null) {
            return v.exposureId;
        }
        return resolveExposureViaPlan(v.hedgePlanId);
    }

    private StartResolution resolveExposureOfTrade(String startId, Chain c) {
        StartResolution out = new StartResolution();
        Long id = parseLong(startId);
        if (id == null) {
            c.addBreak(SEG_TRADE, null, startId, "交易标识非法（须为主键整数）");
            return out;
        }
        TradeOrder o = tradeOrderDao.findById(id);
        if (o != null) {
            if (!visible(o.subAccountId, c.allowed)) {
                c.permissionLimited = true;
                c.addBreak(SEG_TRADE, "t_trade_order", startId, "交易不属于授权子账户范围（权限边界）");
                return out;
            }
            out.node = node(SEG_TRADE, "t_trade_order", o.id, o.clientOrderRef, o.subAccountId, o.status,
                    detail("instrumentId", o.instrumentId, "direction", o.direction, "volume", o.volume,
                            "orderId", o.orderId));
            out.exposureId = o.exposureId != null ? o.exposureId : resolveExposureViaPlan(o.hedgePlanId);
            return out;
        }
        TradeFill f = tradeFillDao.findById(id);
        if (f != null) {
            if (!visible(f.subAccountId, c.allowed)) {
                c.permissionLimited = true;
                c.addBreak(SEG_TRADE, "t_trade_fill", startId, "成交不属于授权子账户范围（权限边界）");
                return out;
            }
            out.node = node(SEG_TRADE, "t_trade_fill", f.id, f.tradeId, f.subAccountId, f.settledFlag,
                    detail("fillPrice", f.fillPrice, "fillVolume", f.fillVolume, "tradeDate", f.tradeDate));
            out.exposureId = f.exposureId != null ? f.exposureId : resolveExposureViaPlan(f.hedgePlanId);
            return out;
        }
        c.addBreak(SEG_TRADE, null, startId, "交易（指令/成交）不存在");
        return out;
    }

    private static boolean containsNode(List<TraceNode> nodes, TraceNode target) {
        for (TraceNode n : nodes) {
            if (n.segment.equals(target.segment) && n.objectType.equals(target.objectType)
                    && n.objectId.equals(target.objectId)) {
                return true;
            }
        }
        return false;
    }

    private Long resolveExposureViaPlan(Long hedgePlanId) {
        if (hedgePlanId == null) {
            return null;
        }
        List<HedgePlanExposure> links = hedgePlanExposureDao.findByPlan(hedgePlanId);
        if (links != null) {
            for (HedgePlanExposure l : links) {
                if (l.exposureId != null) {
                    return l.exposureId;
                }
            }
        }
        return null;
    }

    /** 在途时窗判定（CFG-17c）。 */
    private void applyInFlight(Chain c, String segment, LocalDate tradeDay, String reason) {
        if (tradeDay == null) {
            c.addUnknown(segment, reason + "：交易日缺失，无法判定在途时窗");
            return;
        }
        int cycles = inflightMaxCycles();
        LocalDateTime cutoff = settlementCutoff.cutoffFor(tradeDay);
        LocalDateTime now = Clocks.now();
        LocalDateTime warnUntil = cutoff.plusDays((long) cycles * CYCLE_DAYS);
        if (now.isBefore(cutoff)) {
            c.addInFlight(segment, reason + "：当日结算时点前，不计断点");
        } else if (now.isBefore(warnUntil)) {
            c.addUnknown(segment, reason + "：逾当日结算时点未回报，须告警（不得继续挂在途）");
            c.alerts.add("在途时窗告警（" + segment + "）：逾当日结算时点 " + cutoff + " 仍未回报，"
                    + "转 UNKNOWN；再逾 " + cycles + " 个结算周期将判 HAS_BREAK");
        } else {
            c.addBreak(segment, "t_settle_cycle", null,
                    reason + "：再逾 " + cycles + " 个结算周期（上限 " + warnUntil + "）");
        }
    }

    /** 在途时窗上限（CFG-17c，惰性读取并夹取到 1-3；缺省 1）。 */
    private int inflightMaxCycles() {
        int v;
        try {
            v = inflightMaxCyclesSupplier.getAsInt();
        } catch (RuntimeException e) {
            return 1;
        }
        return (v < 1 || v > 3) ? 1 : v;
    }

    private static LocalDate orderTradeDay(TradeOrder o) {
        if (o.submitTime != null) {
            return o.submitTime.toLocalDate();
        }
        if (o.acceptTime != null) {
            return o.acceptTime.toLocalDate();
        }
        return null;
    }

    private static boolean isSettled(String status) {
        return "CONFIRMED".equals(status) || "RECONCILED".equals(status);
    }

    // ------------------------------------------------------------ 组装

    private void finish(TraceResult r, Chain c, String toSegment, boolean reverse) {
        int maxIdx = indexOfSegment(toSegment);
        List<TraceNode> nodes = new ArrayList<>(c.nodes);
        List<BreakItem> breaks = new ArrayList<>(c.breakList);
        if (maxIdx >= 0) {
            nodes = truncateNodes(nodes, maxIdx);
            breaks = truncateBreaks(breaks, maxIdx);
        }
        if (reverse) {
            Collections.reverse(nodes);
        }
        r.nodes = nodes;
        r.breakList = breaks;
        r.alerts = new ArrayList<>(c.alerts);
        r.permissionLimited = c.permissionLimited;
        boolean hasBreak = false;
        boolean hasUnknown = false;
        for (BreakItem b : breaks) {
            if ("BREAK".equals(b.kind)) {
                hasBreak = true;
            } else if ("UNKNOWN".equals(b.kind)) {
                hasUnknown = true;
            }
        }
        boolean hasInFlight = c.hasInFlightWithin(maxIdx);
        if (hasBreak) {
            r.chainIntegrity = HAS_BREAK;
        } else if (hasUnknown) {
            r.chainIntegrity = UNKNOWN;
        } else if (hasInFlight) {
            r.chainIntegrity = IN_FLIGHT;
        } else {
            r.chainIntegrity = COMPLETE;
        }
        if (r.permissionLimited) {
            r.note = "越权边界：仅可追溯被授权子账户范围；可见子账户=" + r.visibleSubAccounts;
        }
    }

    private static List<TraceNode> truncateNodes(List<TraceNode> nodes, int maxIdx) {
        List<TraceNode> out = new ArrayList<>();
        for (TraceNode n : nodes) {
            if (SEGMENTS.indexOf(n.segment) <= maxIdx) {
                out.add(n);
            }
        }
        return out;
    }

    private static List<BreakItem> truncateBreaks(List<BreakItem> breaks, int maxIdx) {
        List<BreakItem> out = new ArrayList<>();
        for (BreakItem b : breaks) {
            if (SEGMENTS.indexOf(b.position) <= maxIdx) {
                out.add(b);
            }
        }
        return out;
    }

    private static int indexOfSegment(String segment) {
        if (segment == null || segment.trim().isEmpty()) {
            return -1;
        }
        return SEGMENTS.indexOf(segment.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static boolean visible(Integer subAccountId, List<Integer> allowed) {
        if (allowed == null) {
            return false;
        }
        if (allowed.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            return true;
        }
        return subAccountId != null && allowed.contains(subAccountId);
    }

    private static <T> List<T> filterVisible(List<T> src, Function<T, Integer> subOf, List<Integer> allowed) {
        List<T> out = new ArrayList<>();
        if (src == null) {
            return out;
        }
        for (T t : src) {
            if (visible(subOf.apply(t), allowed)) {
                out.add(t);
            }
        }
        return out;
    }

    private static Long parseLong(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static TraceNode node(String segment, String objectType, long objectId, String objectNo,
                                  Integer subAccountId, String status, Map<String, Object> detail) {
        TraceNode n = new TraceNode();
        n.segment = segment;
        n.objectType = objectType;
        n.objectId = String.valueOf(objectId);
        n.objectNo = objectNo;
        n.subAccountId = subAccountId;
        n.status = status;
        n.detail = detail;
        return n;
    }

    private static TraceNode node(String segment, String objectType, String objectId, String objectNo,
                                  Integer subAccountId, String status, Map<String, Object> detail) {
        TraceNode n = new TraceNode();
        n.segment = segment;
        n.objectType = objectType;
        n.objectId = objectId;
        n.objectNo = objectNo;
        n.subAccountId = subAccountId;
        n.status = status;
        n.detail = detail;
        return n;
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put(String.valueOf(kv[i]), v == null ? null : String.valueOf(v));
        }
        return m;
    }

    // ------------------------------------------------------------ 内部载体

    /** 反向追溯起点解析结果（暴露敞口 + 起点节点）。 */
    private static final class StartResolution {
        Long exposureId;
        TraceNode node;
    }

    /** 溯源链累积器。 */
    private static final class Chain {
        final List<Integer> allowed;
        final List<TraceNode> nodes = new ArrayList<>();
        final List<BreakItem> breakList = new ArrayList<>();
        final List<String> alerts = new ArrayList<>();
        final Set<String> unknownSegments = new LinkedHashSet<>();
        final Set<String> inFlightSegments = new LinkedHashSet<>();
        boolean permissionLimited;

        Chain(List<Integer> allowed) {
            this.allowed = allowed;
        }

        void addNode(TraceNode n) {
            nodes.add(n);
        }

        void addBreak(String position, String objectType, String objectId, String reason) {
            BreakItem b = new BreakItem();
            b.kind = "BREAK";
            b.position = position;
            b.objectType = objectType;
            b.objectId = objectId;
            b.reason = reason;
            breakList.add(b);
        }

        void addUnknown(String segment, String reason) {
            unknownSegments.add(segment);
            BreakItem b = new BreakItem();
            b.kind = "UNKNOWN";
            b.position = segment;
            b.reason = reason;
            breakList.add(b);
        }

        void addInFlight(String segment, String reason) {
            inFlightSegments.add(segment);
            alerts.add("在途（" + segment + "）：" + reason);
        }

        boolean hasInFlightWithin(int maxIdx) {
            if (maxIdx < 0) {
                return !inFlightSegments.isEmpty();
            }
            for (String s : inFlightSegments) {
                if (SEGMENTS.indexOf(s) <= maxIdx) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 当日结算时点解析（可注入，CFG-17c）。 */
    public interface SettlementCutoff {
        /**
         * 取某交易日的当日结算时点。
         *
         * @param tradeDate 交易日
         * @return 结算时点
         */
        LocalDateTime cutoffFor(LocalDate tradeDate);
    }

    /**
     * 会计凭证查询接缝（t_voucher，ACCT 模块 T-076+ 未交付）。
     *
     * <p>M1 未装配（null）；T-079 落地后由 Bootstrap 装入实现。
     */
    public interface VoucherLookup {
        /** 按敞口取凭证。 */
        List<VoucherRef> findByExposure(long exposureId);
        /** 按套保计划取凭证。 */
        List<VoucherRef> findByHedgePlan(long hedgePlanId);
        /** 按结算单取凭证。 */
        List<VoucherRef> findBySettleCycle(long settleCycleId);
        /** 按凭证号或主键取凭证。 */
        VoucherRef findByVoucher(String voucherIdOrNo);
    }

    /** 会计凭证引用（TB-ACCT-4 t_voucher 最小投影）。 */
    public static final class VoucherRef {
        /** 主键。 */
        public long id;
        /** 凭证编号。 */
        public String voucherNo;
        /** 凭证类型。 */
        public String voucherType;
        /** 子账户。 */
        public Integer subAccountId;
        /** 关联套保计划。 */
        public Long hedgePlanId;
        /** 关联实货敞口。 */
        public Long exposureId;
        /** 关联结算单。 */
        public Long settleCycleId;
        /** 状态。 */
        public String status;
    }

    /** 追溯链节点。 */
    public static final class TraceNode {
        /** 段名。 */
        public String segment;
        /** 对象类型（表名）。 */
        public String objectType;
        /** 对象主键。 */
        public String objectId;
        /** 对象编号（业务号，可空）。 */
        public String objectNo;
        /** 子账户标识（每段必带，FR-AUDIT-1 验收 2）。 */
        public Integer subAccountId;
        /** 状态/结果。 */
        public String status;
        /** 关键属性。 */
        public Map<String, Object> detail = new LinkedHashMap<>();
    }

    /** 断点项（位置 / 对象 / 可能原因）。 */
    public static final class BreakItem {
        /** 类别：{@code BREAK}（真断点）/ {@code UNKNOWN}（无法判定）。 */
        public String kind;
        /** 段位置。 */
        public String position;
        /** 对象类型。 */
        public String objectType;
        /** 对象标识。 */
        public String objectId;
        /** 可能原因。 */
        public String reason;
    }

    /** 追溯结果。 */
    public static final class TraceResult {
        /** 起点类型。 */
        public String startType;
        /** 起点标识。 */
        public String startId;
        /** 完整性结论：COMPLETE/HAS_BREAK/IN_FLIGHT/UNKNOWN。 */
        public String chainIntegrity;
        /** 链节点（反向追溯时为倒序）。 */
        public List<TraceNode> nodes = new ArrayList<>();
        /** 断点清单。 */
        public List<BreakItem> breakList = new ArrayList<>();
        /** 告警（在途逾时等）。 */
        public List<String> alerts = new ArrayList<>();
        /** 可见子账户集合。 */
        public List<Integer> visibleSubAccounts = new ArrayList<>();
        /** 是否触及权限边界。 */
        public boolean permissionLimited;
        /** 附加说明（权限边界等）。 */
        public String note;
    }

    /** 操作留痕筛选条件（FR-AUDIT-11 输入列）。 */
    public static final class OperationLogQuery {
        /** 起始时间。 */
        public LocalDateTime fromTime;
        /** 截止时间。 */
        public LocalDateTime toTime;
        /** 操作人。 */
        public Long operatorId;
        /** 操作类型。 */
        public String operationType;
        /** 对象类型。 */
        public String objectType;
        /** 子账户。 */
        public Integer subAccountId;
    }
}
