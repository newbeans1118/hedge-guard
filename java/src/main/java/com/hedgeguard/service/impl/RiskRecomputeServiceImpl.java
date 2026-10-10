package com.hedgeguard.service.impl;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AnnualScaleDao;
import com.hedgeguard.dao.BaseDao;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.LimitUsageStatDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.ProductContractDao;
import com.hedgeguard.dao.ProductUnitDao;
import com.hedgeguard.dao.PurchaseContractDao;
import com.hedgeguard.dao.RiskThresholdDao;
import com.hedgeguard.dao.SalesContractDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.TradePositionDao;
import com.hedgeguard.dao.entity.AnnualScale;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.LimitUsageStat;
import com.hedgeguard.dao.entity.ProductContract;
import com.hedgeguard.dao.entity.ProductUnit;
import com.hedgeguard.dao.entity.PurchaseContract;
import com.hedgeguard.dao.entity.RiskThreshold;
import com.hedgeguard.dao.entity.SalesContract;
import com.hedgeguard.dao.entity.TradePosition;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.service.LimitUsageThresholds;
import com.hedgeguard.service.RiskRecomputeService;
import com.hedgeguard.service.RiskThresholdSpec;
import com.hedgeguard.service.SubAccountVisibility;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RiskRecomputeService} 实现（{@code T-066}）。
 *
 * <p><b>复算 = Java 侧对 C++ 事前判定的镜像</b>（{@code risk_engine.cc evalScale/evalNetPosition/evalTerm}）。
 * 读取与 C++ 快照同源的库内投影（{@code t_annual_scale} / {@code t_position} / {@code t_exposure} /
 * {@code t_hedge_plan} + 合同/单位表），阈值取 {@code t_risk_threshold}（缺省回落
 * {@code RiskThresholdSpec} 默认，{@code R-RISK-14} 不得硬编码）。判定边界逐条对齐 C++：
 * 规模红线 {@code 值/基数 >= 比例}（{@code >=}）、时点净持仓 {@code 值 > 敞口}（{@code >}）、
 * 期限取较早者。
 *
 * <p><b>统计落库</b>：{@code t_limit_usage_stat} 的 {@code SCALE_REDLINE}（含全账户哨兵 0 行）与
 * {@code POSITION_LIMIT} 行；幂等（同 {@code stat_date}+子账户+品种+类型 已存在则跳过）。
 *
 * <p><b>诚实披露（与 C++ 的刻意差异，T-186 跨栈对拍量化）</b>：对拍 golden 向量与两侧断言见
 * {@code cpp/src/risk_engine/tests/golden/*.tsv}（单一事实源）。差异逐条：
 * ① C++ {@code evalScale} 对**基数条目缺失** {@code continue}（不阻断）；本实现对**分母缺失**
 * （行缺失或 {@code expected_spot_business_qty <= 0}）判 {@code UNKNOWN}（**不静默兜底**，
 * {@code DATA_MODEL.md} §2.8 {@code TB-RISK-11} 备注）。② C++ 对换算/合约乘数缺失一律跳过规则；
 * 本判 {@code UNKNOWN}（R-PRODUCT-6 不按 1:1 降级）。③ 展期审批与合同到期日**来源**不同
 * （C++ 取指令字段；本实现由计划表/合同表推导），判定谓词一致。上述差异均仅在
 * 「无数据 / 输入承载不同」边界发生，正常数据下判定一致（{@code T-186} 15 组向量对拍，
 * Java 侧 {@code T186RiskParityGoldenTest}）。
 */
public final class RiskRecomputeServiceImpl implements RiskRecomputeService {

    /** 分页读页大小（{@code PageQuery} 白名单最大值）。 */
    private static final int PAGE_SIZE = 100;

    /** 数量标度（{@code DECIMAL(20,4)}）。 */
    private static final int QTY_SCALE = 4;

    /** 比例标度（{@code DECIMAL(9,6)}）。 */
    private static final int RATIO_SCALE = 6;

    private static final String SCOPE_SUB = "SUB_ACCOUNT";
    private static final String SCOPE_TOTAL = "TOTAL_ACCOUNT";
    private static final String GLOBAL_SCOPE = "GLOBAL";

    private final TxTemplate tx;
    private final AnnualScaleDao annualScaleDao;
    private final ExposureDao exposureDao;
    private final TradePositionDao positionDao;
    private final ProductContractDao productContractDao;
    private final ProductUnitDao productUnitDao;
    private final HedgePlanDao hedgePlanDao;
    private final PurchaseContractDao purchaseContractDao;
    private final SalesContractDao salesContractDao;
    private final LimitUsageStatDao limitUsageDao;
    private final RiskThresholdDao thresholdDao;
    private final SubAccountVisibility visibility;
    private final LimitUsageThresholds limitThresholds;

    /**
     * 构造。
     *
     * @param tx                事务模板
     * @param annualScaleDao    年度规模基数 DAO（{@code TB-RISK-11}）
     * @param exposureDao       实货敞口 DAO（{@code TB-SPOT-8}）
     * @param positionDao       持仓 DAO（{@code TB-TRADE-5}）
     * @param productContractDao 合约 DAO（取合约乘数）
     * @param productUnitDao    计量单位 DAO（取换算系数）
     * @param hedgePlanDao      套保计划 DAO（期限红线）
     * @param purchaseContractDao 采购合同 DAO（期限依据）
     * @param salesContractDao  销售合同 DAO（期限依据）
     * @param limitUsageDao     限额使用统计 DAO（{@code TB-RISK-9}）
     * @param thresholdDao      风控阈值 DAO（取生效参数，缺省回落）
     * @param visibility        调用者子账户可见性（读取面隔离；可空 ⇒ fail-closed）
     * @param limitThresholds   限额使用率提示/警告线（{@code CFG-21b}）
     */
    public RiskRecomputeServiceImpl(TxTemplate tx, AnnualScaleDao annualScaleDao,
                                    ExposureDao exposureDao, TradePositionDao positionDao,
                                    ProductContractDao productContractDao,
                                    ProductUnitDao productUnitDao, HedgePlanDao hedgePlanDao,
                                    PurchaseContractDao purchaseContractDao,
                                    SalesContractDao salesContractDao,
                                    LimitUsageStatDao limitUsageDao, RiskThresholdDao thresholdDao,
                                    SubAccountVisibility visibility,
                                    LimitUsageThresholds limitThresholds) {
        this.tx = tx;
        this.annualScaleDao = annualScaleDao;
        this.exposureDao = exposureDao;
        this.positionDao = positionDao;
        this.productContractDao = productContractDao;
        this.productUnitDao = productUnitDao;
        this.hedgePlanDao = hedgePlanDao;
        this.purchaseContractDao = purchaseContractDao;
        this.salesContractDao = salesContractDao;
        this.limitUsageDao = limitUsageDao;
        this.thresholdDao = thresholdDao;
        this.visibility = visibility;
        this.limitThresholds = limitThresholds == null ? LimitUsageThresholds.defaults() : limitThresholds;
    }

    // ================================================================== 规模红线

    @Override
    public ScaleResult recomputeScale(int subAccountId, long productId, int scaleYear) {
        ScaleResult r = new ScaleResult();
        r.ruleId = RULE_SCALE;
        r.subAccountId = subAccountId;
        r.productId = productId;
        r.scaleYear = scaleYear;

        AnnualScale sub = annualScaleDao.findByScopeAndProduct(
                SCOPE_SUB, subAccountId, productId, scaleYear);
        AnnualScale total = annualScaleDao.findByScopeAndProduct(
                SCOPE_TOTAL, ALL_ACCOUNT_SENTINEL, productId, scaleYear);
        if (sub == null && total == null) {
            r.verdict = UNKNOWN;
            r.detail = "无规模基数行（子账户与全账户均缺）⇒ 规模红线无法判定（不得默认为 0，"
                    + "DATA_MODEL.md §2.8 TB-RISK-11 备注）";
            return r;
        }
        r.category = sub != null ? sub.category : total.category;
        BigDecimal base = thresholdDecimal(RiskThresholdSpec.SCALE_RATIO_COMMODITY.configKey(),
                new BigDecimal("0.90"));
        if (CATEGORY_TRADE.equals(r.category)) {
            base = thresholdDecimal(RiskThresholdSpec.SCALE_RATIO_TRADE.configKey(),
                    new BigDecimal("0.80"));
        }
        r.ratioLimit = base;
        String code = CATEGORY_TRADE.equals(r.category) ? CODE_SCALE_TRADE : CODE_SCALE_COMMODITY;

        boolean missing = false;
        BigDecimal ratioSub = null;
        BigDecimal ratioTotal = null;
        if (sub != null) {
            if (denominatorInvalid(sub)) {
                missing = true;
            } else {
                ratioSub = usageOf(sub);
            }
        }
        if (total != null) {
            if (denominatorInvalid(total)) {
                missing = true;
            } else {
                ratioTotal = usageOf(total);
            }
        }
        r.subAccountUsageRatio = ratioSub;
        r.totalAccountUsageRatio = ratioTotal;
        r.denominatorMissing = missing;

        BigDecimal effSub = effectiveRatio(base, sub);
        BigDecimal effTotal = effectiveRatio(base, total);
        boolean breachSub = !missing && sub != null && !denominatorInvalid(sub)
                && reaches(sub, effSub);
        boolean breachTotal = !missing && total != null && !denominatorInvalid(total)
                && reaches(total, effTotal);
        r.effectiveUsageRatio = maxNonNull(ratioSub, ratioTotal);

        if (missing) {
            r.verdict = UNKNOWN;
            r.detail = "规模基数分母缺失（expected_spot_business_qty <= 0 或行缺失）⇒ 无法判定，"
                    + "拒绝静默按 0 处理（DATA_MODEL.md §2.8 TB-RISK-11 备注）";
            return r;
        }
        if (breachSub || breachTotal) {
            r.verdict = REJECT;
            r.code = code;
            BigDecimal bound = breachSub ? effSub : effTotal;
            r.threshold = bound.toPlainString();
            r.current = (breachSub ? ratioSub : ratioTotal).toPlainString();
            r.detail = "年度保值规模使用率触及红线（" + (breachSub ? "子账户" : "全账户")
                    + "口径，分类 " + r.category + "，阈值 " + bound.toPlainString()
                    + "）⇒ C++ 同口径阻断新开仓（平仓不受限，R-RISK-4）";
            return r;
        }
        r.verdict = PASS;
        r.current = r.effectiveUsageRatio == null ? null : r.effectiveUsageRatio.toPlainString();
        r.threshold = base.toPlainString();
        r.detail = "双算取严后使用率 " + (r.effectiveUsageRatio == null ? "-"
                : r.effectiveUsageRatio.toPlainString()) + " < 分类比例 " + base.toPlainString();
        return r;
    }

    // ================================================================== 时点净持仓

    @Override
    public NetPositionResult recomputeNetPosition(int subAccountId, long productId) {
        NetPositionResult r = new NetPositionResult();
        r.ruleId = RULE_NET_POSITION;
        r.subAccountId = subAccountId;
        r.productId = productId;

        ProductUnit unit = productUnitDao.findEffective(productId, Clocks.today());
        if (unit == null || unit.convertFactor == null || unit.convertFactor.signum() <= 0) {
            r.conversionMissing = true;
            r.verdict = UNKNOWN;
            r.detail = "品种无生效换算关系（t_product_unit）⇒ 无法折算同口径，拒绝静默按 1:1 处理"
                    + "（R-PRODUCT-6）：productId=" + productId;
            return r;
        }
        BigDecimal cf = unit.convertFactor;

        BigDecimal netSigned = BigDecimal.ZERO;
        for (TradePosition p : positionDao.findBySubAccount(subAccountId)) {
            if (p.productId == null || p.productId != productId) {
                continue;
            }
            ProductContract pc = productContractDao.findByInstrumentId(p.instrumentId);
            if (pc == null || pc.contractMultiplier == null || pc.contractMultiplier.signum() <= 0) {
                // C++ evalNetPosition：合约乘数缺失/非正 ⇒ 跳过该合约（continue）
                continue;
            }
            BigDecimal qty = nz(p.positionQty);
            // T-187 方案 A：t_position.direction 为 DDL 冻结域 LONG/SHORT（ck_position_direction），
            // SHORT（空头）取负号参与净持仓合成；线上 BUY/SELL 词汇已在摄取侧
            // （TradeService.mapPositionDirection）映射为 LONG/SHORT 后落库。
            BigDecimal signed = "SHORT".equalsIgnoreCase(p.direction)
                    ? qty.negate() : qty;
            BigDecimal real = signed.multiply(pc.contractMultiplier).multiply(cf)
                    .setScale(QTY_SCALE, RoundingMode.HALF_UP);
            netSigned = netSigned.add(real);
        }
        r.netPosition = netSigned.setScale(QTY_SCALE, RoundingMode.HALF_UP);

        Exposure buy = latestExposure(subAccountId, productId, DIRECTION_BUY);
        Exposure sell = latestExposure(subAccountId, productId, DIRECTION_SELL);
        r.buyExposure = buy == null ? null : nz(buy.exposureQty);
        r.sellExposure = sell == null ? null : nz(sell.exposureQty);
        r.exposureSnapshotTime = maxSnapshotTime(buy, sell);

        // 开仓方向：BUY ⇒ 与买入敞口比较（净多）；SELL ⇒ 与卖出敞口比较（净空）。
        // 减仓方向：净多时的 SELL（-净持仓 < 0）天然不越限（R-RISK-5「减仓不受限」）。
        BigDecimal buyProj = r.netPosition;
        BigDecimal sellProj = r.netPosition.negate();
        boolean breachBuy = buy != null && buyProj.compareTo(r.buyExposure) > 0;
        boolean breachSell = sell != null && sellProj.compareTo(r.sellExposure) > 0;
        if (breachBuy || breachSell) {
            BigDecimal buyExcess = breachBuy ? buyProj.subtract(r.buyExposure) : null;
            BigDecimal sellExcess = breachSell ? sellProj.subtract(r.sellExposure) : null;
            boolean useBuy = sellExcess == null
                    || (buyExcess != null && buyExcess.compareTo(sellExcess) >= 0);
            r.verdict = REJECT;
            r.code = CODE_NET_POSITION;
            r.breachDirection = useBuy ? DIRECTION_BUY : DIRECTION_SELL;
            r.current = (useBuy ? buyProj : sellProj).toPlainString();
            r.threshold = (useBuy ? r.buyExposure : r.sellExposure).toPlainString();
            r.detail = "时点净持仓（折算实物量）超对应实货风险敞口（" + r.breachDirection
                    + " 方向）⇒ C++ 同口径阻断；减仓方向不受限（R-RISK-5）"
                    + (r.exposureSnapshotTime == null ? ""
                    : "；敞口快照时点=" + r.exposureSnapshotTime);
            return r;
        }
        r.verdict = PASS;
        r.current = r.netPosition.toPlainString();
        r.threshold = buy != null ? r.buyExposure.toPlainString()
                : (sell != null ? r.sellExposure.toPlainString() : null);
        r.detail = "净持仓 " + r.netPosition.toPlainString() + " 未超敞口"
                + (r.exposureSnapshotTime == null ? "（无敞口行 ⇒ C++ 语义跳过）"
                : "；敞口快照时点=" + r.exposureSnapshotTime);
        return r;
    }

    // ================================================================== 期限红线

    @Override
    public TermResult recomputeTerm(long planId, LocalDate today) {
        TermResult r = new TermResult();
        r.ruleId = RULE_TERM;
        r.planId = planId;
        LocalDate bizDate = today == null ? Clocks.today() : today;
        HedgePlan plan = hedgePlanDao.findById(planId);
        if (plan == null) {
            r.verdict = UNKNOWN;
            r.detail = "计划不存在：planId=" + planId;
            return r;
        }
        r.subAccountId = plan.subAccountId == null ? 0 : plan.subAccountId;
        r.productId = plan.productId == null ? 0L : plan.productId;
        LocalDate open = plan.termStartDate != null ? plan.termStartDate : bizDate;
        r.openDate = open;
        r.termEndDate = plan.termEndDate;

        int maxMonths = thresholdInt(RiskThresholdSpec.TERM_MAX_MONTHS.configKey(), 12);
        int graceDays = thresholdInt(RiskThresholdSpec.TERM_GRACE_DAYS.configKey(), 30);

        LocalDate byMonths = open.plusMonths(maxMonths);
        LocalDate expiry = matchingContractExpiry(r.subAccountId, r.productId, plan.hedgeDirection);
        r.contractExpiry = expiry;
        LocalDate allowed = byMonths;
        String basis = "OPEN_PLUS_MONTHS";
        if (expiry != null) {
            LocalDate withGrace = expiry.plusDays(graceDays);
            if (withGrace.isBefore(allowed)) {
                allowed = withGrace;
                basis = "CONTRACT_PLUS_GRACE";
            }
        }
        r.allowedEnd = allowed;
        r.basis = basis;
        r.remainingDays = ChronoUnit.DAYS.between(bizDate, allowed);

        boolean extApproved = plan.extendBasis != null && !plan.extendBasis.trim().isEmpty()
                && ("APPROVED".equals(plan.status) || "FROZEN".equals(plan.status));
        r.extensionApproved = extApproved;

        if (plan.termEndDate != null && plan.termEndDate.isAfter(allowed) && !extApproved) {
            r.verdict = REJECT;
            r.code = CODE_TERM_REDLINE;
            r.current = plan.termEndDate.toString();
            r.threshold = allowed.toString();
            r.detail = "计划期限止 " + plan.termEndDate + " 超生效上限 " + allowed + "（依据 " + basis
                    + "）且展期未审批 ⇒ 阻断新开仓；平仓与展期置换不受限（FR-RISK-6 验收 3）";
        } else if (r.remainingDays <= graceDays) {
            r.verdict = WARN;
            r.current = String.valueOf(r.remainingDays);
            r.threshold = String.valueOf(graceDays);
            r.detail = "剩余期限 " + r.remainingDays + " 天 ≤ 宽限 " + graceDays
                    + " 天 ⇒ 告警（WARN_THEN_BLOCK，处置三级第二级仍可开仓至超期）";
        } else {
            r.verdict = PASS;
            r.current = String.valueOf(r.remainingDays);
            r.threshold = String.valueOf(graceDays);
            r.detail = "剩余期限 " + r.remainingDays + " 天 > 宽限 " + graceDays + " 天";
        }
        return r;
    }

    // ================================================================== 复算并落库

    @Override
    public RecomputeSummary recomputeAndPersist(LocalDate statDate, long operatorId) {
        final LocalDate date = statDate == null ? Clocks.today() : statDate;
        final RecomputeSummary summary = new RecomputeSummary();
        summary.statDate = date;
        summary.hits = new ArrayList<>();
        tx.inTransaction(conn -> {
            int year = date.getYear();
            // ① 规模红线统计（SCALE_REDLINE）：逐行持久化，分母缺失不落库并计入未判定。
            List<AnnualScale> scales = findAll(annualScaleDao, "scale_year = ?",
                    Collections.singletonList((Object) year));
            for (AnnualScale row : scales) {
                if (row.productId == null) {
                    continue;
                }
                int sub = row.subAccountId == null ? ALL_ACCOUNT_SENTINEL : row.subAccountId;
                if (denominatorInvalid(row)) {
                    summary.scaleUnjudgedCount++;
                    continue;
                }
                BigDecimal base = CATEGORY_TRADE.equals(row.category)
                        ? thresholdDecimal(RiskThresholdSpec.SCALE_RATIO_TRADE.configKey(),
                                new BigDecimal("0.80"))
                        : thresholdDecimal(RiskThresholdSpec.SCALE_RATIO_COMMODITY.configKey(),
                                new BigDecimal("0.90"));
                BigDecimal eff = effectiveRatio(base, row);
                BigDecimal expected = nz(row.expectedSpotBusinessQty);
                BigDecimal used = nz(row.accumulatedHedgeQty).setScale(QTY_SCALE, RoundingMode.HALF_UP);
                BigDecimal limit = expected.multiply(eff).setScale(QTY_SCALE, RoundingMode.HALF_UP);
                boolean breach = used.compareTo(limit) >= 0;
                if (!persisted(date, sub, row.productId, LIMIT_TYPE_SCALE_REDLINE)) {
                    BigDecimal ratio = limit.signum() <= 0 ? BigDecimal.ZERO
                            : used.divide(limit, RATIO_SCALE, RoundingMode.HALF_UP);
                    LimitUsageStat u = new LimitUsageStat();
                    u.subAccountId = sub;
                    u.productId = row.productId;
                    u.statDate = date;
                    u.limitType = LIMIT_TYPE_SCALE_REDLINE;
                    u.limitValue = limit;
                    u.usedValue = used;
                    u.usageRatio = ratio;
                    u.hintLevel = limitThresholds.level(ratio);
                    u.hintThreshold1 = limitThresholds.hint;
                    u.hintThreshold2 = limitThresholds.warn;
                    limitUsageDao.insert(u, operatorId);
                    summary.scaleRows++;
                }
                if (breach) {
                    summary.scaleBreachCount++;
                    ScaleResult hit = new ScaleResult();
                    hit.ruleId = RULE_SCALE;
                    hit.code = CATEGORY_TRADE.equals(row.category) ? CODE_SCALE_TRADE
                            : CODE_SCALE_COMMODITY;
                    hit.verdict = REJECT;
                    hit.subAccountId = sub;
                    hit.productId = row.productId;
                    hit.scaleYear = year;
                    hit.category = row.category;
                    hit.current = used.toPlainString();
                    hit.threshold = limit.toPlainString();
                    hit.detail = "规模红线统计：已用 " + used.toPlainString() + " >= 红线值 "
                            + limit.toPlainString() + "（基数 " + expected.toPlainString()
                            + " × 比例 " + eff.toPlainString() + "）";
                    summary.hits.add(hit);
                }
            }
            // ② 时点净持仓统计（POSITION_LIMIT）：按（子账户 × 品种）取更严方向。
            List<TradePosition> positions = findAll(positionDao, "sub_account_id > ?",
                    Collections.singletonList((Object) ALL_ACCOUNT_SENTINEL));
            Map<String, int[]> pairs = new LinkedHashMap<>();
            for (TradePosition p : positions) {
                if (p.subAccountId == null || p.productId == null) {
                    continue;
                }
                pairs.putIfAbsent(p.subAccountId + "|" + p.productId,
                        new int[]{p.subAccountId, p.productId.intValue()});
            }
            for (int[] pair : pairs.values()) {
                NetPositionResult np = recomputeNetPosition(pair[0], (long) pair[1]);
                if (np.conversionMissing) {
                    continue;
                }
                BigDecimal buyLimit = np.buyExposure;
                BigDecimal sellLimit = np.sellExposure;
                BigDecimal buyUsed = np.netPosition;
                BigDecimal sellUsed = np.netPosition.negate();
                BigDecimal bestLimit = null;
                BigDecimal bestUsed = null;
                BigDecimal bestRatio = null;
                for (int i = 0; i < 2; i++) {
                    BigDecimal lim = i == 0 ? buyLimit : sellLimit;
                    BigDecimal use = i == 0 ? buyUsed : sellUsed;
                    if (lim == null || lim.signum() <= 0 || use.signum() <= 0) {
                        continue;
                    }
                    BigDecimal ratio = use.divide(lim, RATIO_SCALE, RoundingMode.HALF_UP);
                    if (bestRatio == null || ratio.compareTo(bestRatio) > 0) {
                        bestRatio = ratio;
                        bestLimit = lim;
                        bestUsed = use;
                    }
                }
                if (bestRatio == null) {
                    continue;
                }
                if (!persisted(date, pair[0], (long) pair[1], LIMIT_TYPE_POSITION_LIMIT)) {
                    LimitUsageStat u = new LimitUsageStat();
                    u.subAccountId = pair[0];
                    u.productId = (long) pair[1];
                    u.statDate = date;
                    u.limitType = LIMIT_TYPE_POSITION_LIMIT;
                    u.limitValue = bestLimit.setScale(QTY_SCALE, RoundingMode.HALF_UP);
                    u.usedValue = bestUsed.setScale(QTY_SCALE, RoundingMode.HALF_UP);
                    u.usageRatio = bestRatio;
                    u.hintLevel = limitThresholds.level(bestRatio);
                    u.hintThreshold1 = limitThresholds.hint;
                    u.hintThreshold2 = limitThresholds.warn;
                    limitUsageDao.insert(u, operatorId);
                    summary.positionRows++;
                }
                if (np.verdict != null && np.verdict.equals(REJECT)) {
                    summary.positionBreachCount++;
                    summary.hits.add(np);
                }
            }
            // ③ 期限红线复算（不落统计表；结果经返回项展示）。
            List<HedgePlan> plans = new ArrayList<>();
            plans.addAll(findAll(hedgePlanDao, "status = ?", Collections.singletonList((Object) "APPROVED")));
            plans.addAll(findAll(hedgePlanDao, "status = ?", Collections.singletonList((Object) "FROZEN")));
            for (HedgePlan plan : plans) {
                TermResult tr = recomputeTerm(plan.id, date);
                summary.termChecked++;
                if (WARN.equals(tr.verdict)) {
                    summary.termWarnCount++;
                    summary.hits.add(tr);
                } else if (REJECT.equals(tr.verdict)) {
                    summary.termBlockCount++;
                    summary.hits.add(tr);
                }
            }
            return null;
        });
        return summary;
    }

    // ================================================================== 统计查询

    @Override
    public UsagePage queryUsage(UsageQuery query, PageQuery page, long userId) {
        if (query == null || query.statDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "statDate 必填（yyyy-MM-dd）：复现=按统计日期查询（FR-RISK-18）");
        }
        List<Integer> allowed = visibility == null ? null : visibility.visibleSubAccountIds(userId);
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        append(where, args, "stat_date = ?", query.statDate);
        if (query.subAccountId != null) {
            append(where, args, "sub_account_id = ?", query.subAccountId);
        }
        if (query.limitType != null && !query.limitType.isEmpty()) {
            append(where, args, "limit_type = ?", query.limitType);
        }
        if (query.hintLevel != null && !query.hintLevel.isEmpty()) {
            append(where, args, "hint_level = ?", query.hintLevel);
        }
        String w = where.length() == 0 ? null : where.toString();
        boolean crossAccount = allowed == null || allowed.contains(ALL_ACCOUNT_SENTINEL);
        PageResult<LimitUsageStat> result = crossAccount
                ? limitUsageDao.findPage(page, w, args)
                : limitUsageDao.findPageScoped(page,
                        SubAccountScope.of("sub_account_id", allowed), w, args);
        UsagePage out = new UsagePage();
        out.items = new ArrayList<>();
        for (LimitUsageStat row : result.items()) {
            out.items.add(toView(row));
        }
        out.total = result.total();
        out.page = result.page();
        out.size = result.size();
        return out;
    }

    // ================================================================== 工具

    private static UsageRow toView(LimitUsageStat row) {
        UsageRow v = new UsageRow();
        v.id = row.id;
        v.subAccountId = row.subAccountId;
        v.productId = row.productId;
        v.statDate = row.statDate;
        v.limitType = row.limitType;
        v.limitValue = str(row.limitValue);
        v.usedValue = str(row.usedValue);
        v.usageRatio = str(row.usageRatio);
        v.hintLevel = row.hintLevel;
        v.hintThreshold1 = str(row.hintThreshold1);
        v.hintThreshold2 = str(row.hintThreshold2);
        return v;
    }

    private boolean persisted(LocalDate date, int subAccountId, long productId, String limitType) {
        return limitUsageDao.count(
                "stat_date = ? AND sub_account_id = ? AND product_id = ? AND limit_type = ?",
                Arrays.asList(date, subAccountId, productId, limitType)) > 0;
    }

    /** 最新敞口快照（当日子账户 × 品种 × 方向；{@code snapshot_time} 降序、{@code id} 降序收官）。 */
    private Exposure latestExposure(int subAccountId, long productId, String direction) {
        List<Exposure> rows = findAll(exposureDao,
                "sub_account_id = ? AND product_id = ? AND direction = ?",
                Arrays.asList(subAccountId, productId, direction));
        Exposure best = null;
        for (Exposure e : rows) {
            if (best == null || isNewer(e, best)) {
                best = e;
            }
        }
        return best;
    }

    private static boolean isNewer(Exposure a, Exposure b) {
        if (a.snapshotTime != null && b.snapshotTime != null) {
            int c = a.snapshotTime.compareTo(b.snapshotTime);
            if (c != 0) {
                return c > 0;
            }
        } else if (a.snapshotTime != null) {
            return true;
        } else if (b.snapshotTime != null) {
            return false;
        }
        return a.id > b.id;
    }

    private static LocalDateTime maxSnapshotTime(Exposure a, Exposure b) {
        LocalDateTime x = a == null ? null : a.snapshotTime;
        LocalDateTime y = b == null ? null : b.snapshotTime;
        if (x == null) {
            return y;
        }
        if (y == null) {
            return x;
        }
        return x.isAfter(y) ? x : y;
    }

    /**
     * 对应实货合同到期日：{@code BUY_HEDGE} 对冲卖出敞口（销售合同）；{@code SELL_HEDGE} 对冲买入敞口
     * （采购合同）。取同子账户同品种 **ACTIVE** 合同的**最早**到期日（保守，工程口径）。
     */
    private LocalDate matchingContractExpiry(int subAccountId, long productId, String hedgeDirection) {
        if (productId <= 0) {
            return null;
        }
        LocalDate best = null;
        if ("BUY_HEDGE".equalsIgnoreCase(hedgeDirection)) {
            for (SalesContract c : findAll(salesContractDao,
                    "sub_account_id = ? AND product_id = ? AND status = ?",
                    Arrays.asList(subAccountId, productId, "ACTIVE"))) {
                if (c.contractEndDate != null && (best == null || c.contractEndDate.isBefore(best))) {
                    best = c.contractEndDate;
                }
            }
        } else {
            for (PurchaseContract c : findAll(purchaseContractDao,
                    "sub_account_id = ? AND product_id = ? AND status = ?",
                    Arrays.asList(subAccountId, productId, "ACTIVE"))) {
                if (c.contractEndDate != null && (best == null || c.contractEndDate.isBefore(best))) {
                    best = c.contractEndDate;
                }
            }
        }
        return best;
    }

    private int thresholdInt(String key, int fallback) {
        RiskThreshold row = thresholdDao == null
                ? null : thresholdDao.findByKey(key, GLOBAL_SCOPE, ALL_ACCOUNT_SENTINEL);
        if (row == null || row.thresholdValue == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(row.thresholdValue.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private BigDecimal thresholdDecimal(String key, BigDecimal fallback) {
        RiskThreshold row = thresholdDao == null
                ? null : thresholdDao.findByKey(key, GLOBAL_SCOPE, ALL_ACCOUNT_SENTINEL);
        if (row == null || row.thresholdValue == null) {
            return fallback;
        }
        try {
            return new BigDecimal(row.thresholdValue.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean denominatorInvalid(AnnualScale row) {
        return row == null || row.expectedSpotBusinessQty == null
                || row.expectedSpotBusinessQty.signum() <= 0;
    }

    private static BigDecimal usageOf(AnnualScale row) {
        if (denominatorInvalid(row)) {
            return null;
        }
        return nz(row.accumulatedHedgeQty)
                .divide(row.expectedSpotBusinessQty, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal effectiveRatio(BigDecimal base, AnnualScale row) {
        if (row != null && row.scaleRatioLimit != null && row.scaleRatioLimit.signum() > 0) {
            return row.scaleRatioLimit.min(base);
        }
        return base;
    }

    /** 是否触及红线：{@code accumulated / expected >= eff}（{@code fixed_math.h ratioReaches}）。 */
    private static boolean reaches(AnnualScale row, BigDecimal eff) {
        BigDecimal used = nz(row.accumulatedHedgeQty);
        return used.compareTo(row.expectedSpotBusinessQty.multiply(eff)) >= 0;
    }

    private static BigDecimal maxNonNull(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.max(b);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static void append(StringBuilder sb, List<Object> args, String clause, Object arg) {
        if (sb.length() > 0) {
            sb.append(" AND ");
        }
        sb.append(clause);
        args.add(arg);
    }

    private static <T> List<T> findAll(BaseDao<T, ?> dao, String where, List<Object> args) {
        List<T> all = new ArrayList<>();
        int page = 1;
        while (true) {
            PageQuery q = PageQuery.of(String.valueOf(page), String.valueOf(PAGE_SIZE), "id,asc", "id");
            PageResult<T> res = dao.findPage(q, where, args);
            all.addAll(res.items());
            if (res.items().size() < PAGE_SIZE) {
                return all;
            }
            page++;
        }
    }
}
