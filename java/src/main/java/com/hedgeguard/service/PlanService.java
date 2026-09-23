package com.hedgeguard.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AuthorizationDao;
import com.hedgeguard.dao.BizNoGenerator;
import com.hedgeguard.dao.CustomHedgeRuleDao;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.ExposureDetailDao;
import com.hedgeguard.dao.HedgeDocDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgePlanExposureDao;
import com.hedgeguard.dao.HedgeRatioCalcDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.PlanApprovalDao;
import com.hedgeguard.dao.PlanStateLogDao;
import com.hedgeguard.dao.ProductContractDao;
import com.hedgeguard.dao.PurchaseContractDao;
import com.hedgeguard.dao.SalesContractDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.SubProductWhitelistDao;
import com.hedgeguard.dao.TradePlanDao;
import com.hedgeguard.dao.entity.Authorization;
import com.hedgeguard.dao.entity.CustomHedgeRule;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.ExposureDetail;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgePlanExposure;
import com.hedgeguard.dao.entity.HedgeRatioCalc;
import com.hedgeguard.dao.entity.PlanApproval;
import com.hedgeguard.dao.entity.PlanStateLog;
import com.hedgeguard.dao.entity.ProductContract;
import com.hedgeguard.dao.entity.PurchaseContract;
import com.hedgeguard.dao.entity.SalesContract;
import com.hedgeguard.dao.entity.SubProductWhitelist;
import com.hedgeguard.dao.entity.TradePlan;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.SQLException;
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

/**
 * 套保比率与套保计划服务（{@code T-015} 比率三档、{@code T-016} 计划编制 / 门禁 / 审批链 / 状态机）。
 *
 * <p>依据：{@code FR-PLAN-1~20}、{@code R-PLAN-1/2/4/5/6/7/8/11}、{@code CFG-03/06/06b}、
 * {@code Q-6}、{@code DATA_MODEL.md} §2.5（{@code TB-PLAN-1~8}）、{@code ARCHITECTURE.md} §6.4。
 *
 * <p>六条硬纪律：
 * <ol>
 *   <li><b>计划必须从实货敞口出发且方向相反</b>（{@code R-PLAN-1}/{@code R-PLAN-7}）：
 *       绑定敞口须与计划同子账户，且 {@code hedge_direction} 与敞口 {@code direction} 相反；</li>
 *   <li><b>发起人 ≠ 审批人</b>（{@code R-PLAN-2}）：同一账号自审自批一律 {@code 10005}；</li>
 *   <li><b>书面文件三要素门禁</b>（{@code R-PLAN-8} / CAS 24）：无当前版本书面文件
 *       <b>不得</b>提交审批（{@code 10012}，四项齐备）；</li>
 *   <li><b>额度随状态占用 / 释放</b>（{@code R-PLAN-5}）：仅 {@code APPROVED}/{@code FROZEN} 占额，
 *       终止释放；每次动作落 {@code t_plan_state_log}（含 {@code OCCUPY}/{@code RELEASE}）；</li>
 *   <li><b>比率计算须可复算</b>（{@code R-PLAN-4}）：最小方差法样本不足
 *       （&lt; {@value #MIN_VARIANCE_MIN_SAMPLES}，{@code CFG-06b}）<b>拒绝</b>且不返回比率；
 *       自定义规则法按 {@code ratio_cap 升序 → priority 升序 → id 升序} 确定性取一（{@code Q-6}）；</li>
 *   <li><b>交易计划合计 ≤ 套保计划数量</b>（{@code FR-PLAN-14} 验收 2）：分解时校验累计量。</li>
 * </ol>
 */
public final class PlanService {

    /** 最小方差法最少样本数（{@code CFG-06b}：≥ 30 个交易日）。 */
    public static final int MIN_VARIANCE_MIN_SAMPLES = 30;

    /** 计划期限红线：不得超过起始日 + 12 个月（{@code R-RISK-6} / {@code CFG-03}）。 */
    public static final int TERM_MAX_MONTHS = 12;

    /** 期限红线宽限：实货合同到期日 + {@value} 天（{@code CFG-03} 冻结默认 {@code 30}；{@code Q-4}）。 */
    public static final int CONTRACT_GRACE_DAYS = 30;

    /** 业务编号唯一冲突重试上限（{@code DATA_MODEL.md} §3.5：最多 3 次）。 */
    private static final int RETRY_MAX_ATTEMPTS = 3;

    // ------------------------------------------------------------ R-PLAN-3 合规预校验项编号
    //
    // 编号与取值域以 docs/REQUIREMENTS/PLAN.md FR-PLAN-10 / R-PLAN-3 的**校验项清单**为准
    // （与 ARCHITECTURE.md §6.4「服务端强制 6 项」清单一致）：
    //   ① 操作授权（子账户+品种） ② 品种在子账户白名单内 ③ 规模红线 ④ 期限红线
    //   ⑤ 计划数量 ≤ 未覆盖量      ⑥ 书面文件三要素齐备     ⑦ 发起人不得同时具备交易岗权限
    // M1 服务端强制 **①②④⑤⑥⑦ 六项**；③ 规模红线归 M3（分母 ∈ t_annual_scale，M1 不存在）
    // ⇒ 恒不可算，以 unverified 显式标注、**不因 ③ 单项拒单**（ARCHITECTURE.md §6.4 过渡口径）。
    // 注：ARCHITECTURE.md:1472 的「判定顺序」行用的是**执行次序位号**（①status②书面文件③岗位分离…），
    //     与上述**规则编号**同名不同义；代码里一律用规则编号，执行次序见 submitForApproval。

    /** {@code R-PLAN-3/①} 操作授权（子账户维度 10004 / 品种维度 10003）。 */
    public static final String RULE_AUTH = "R-PLAN-3/①";

    /** {@code R-PLAN-3/②} 品种在子账户白名单内（{@code 10003}）。 */
    public static final String RULE_WHITELIST = "R-PLAN-3/②";

    /** {@code R-PLAN-3/③} 规模红线（M3 承接，M1 不可算 ⇒ 恒列 {@code unverified}）。 */
    public static final String RULE_SCALE = "R-PLAN-3/③";

    /** {@code R-PLAN-3/④} 期限红线（{@code 10012}）。 */
    public static final String RULE_TERM = "R-PLAN-3/④";

    /** {@code R-PLAN-3/⑤} 计划数量 ≤ 绑定敞口未覆盖量（{@code 10012}）。 */
    public static final String RULE_QTY = "R-PLAN-3/⑤";

    /** {@code R-PLAN-3/⑥} 书面文件三要素齐备（{@code 10012}）。 */
    public static final String RULE_DOC = "R-PLAN-3/⑥";

    /** {@code R-PLAN-3/⑦} 发起人不得同时具备交易岗权限（岗位分离，{@code 10005}）。 */
    public static final String RULE_POSITION_SEPARATION = "R-PLAN-3/⑦";

    /** M1 期间 {@code unverified} 恒含的未校验项（③ 规模红线，归 M3）。 */
    public static final List<String> M1_UNVERIFIED = Collections.singletonList(RULE_SCALE);

    /** 套保方向与敞口方向的相反映射（{@code R-PLAN-1}）。 */
    private static final Map<String, String> HEDGE_TO_EXPOSURE_OPPOSITE = oppositeMap();

    /** 计划状态取值域。 */
    public static final Set<String> PLAN_STATUSES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("DRAFT", "PENDING_APPROVAL", "APPROVED", "FROZEN", "COMPLETED", "TERMINATED")));

    /** 计划类型取值域（{@code ck_hedge_plan_type}）。 */
    public static final Set<String> PLAN_TYPES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("COMMODITY", "TRADE")));

    /**
     * 计划列表允许的排序字段（列名白名单；默认 {@code created_time} 倒序，{@code FR-PLAN-4}）。
     *
     * <p>白名单同时挡两类问题：① 排序字段直接进 {@code ORDER BY}（由 DAO 拼 SQL），故须限列名；
     * ② 非法列名会在实机报"无效的列名"——提前拒绝可给出可诊断的 {@code 10001}。
     */
    public static final Set<String> LIST_SORT_FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("created_time", "id", "plan_no", "status", "plan_type",
                    "term_start_date", "term_end_date", "plan_qty")));

    /** 列表默认排序字段（{@code FR-PLAN-4}：默认按创建时间倒序）。 */
    public static final String DEFAULT_SORT_FIELD = "created_time";

    /** 交易计划状态取值域（{@code ck_trade_plan_status}，{@code DATA_MODEL.md} §2.5 TB-PLAN-6）。 */
    public static final Set<String> TRADE_PLAN_STATUSES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("PENDING", "EXECUTING", "COMPLETED", "CANCELLED")));

    /**
     * 交易计划列表允许的排序字段（列名白名单；默认 {@code exec_deadline} 升序，{@code FR-PLAN-15}）。
     *
     * <p>白名单同时挡两类问题：① 排序字段直接进 {@code ORDER BY}（由 DAO 拼 SQL），故须限列名；
     * ② 非法列名会在实机报"无效的列名"——提前拒绝可给出可诊断的 {@code 10001}。
     */
    public static final Set<String> TRADE_PLAN_SORT_FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("exec_deadline", "id", "trade_plan_no", "instrument_id", "status",
                    "plan_qty", "created_time")));

    /** 交易计划列表默认排序字段（{@code FR-PLAN-15}「处理规则」：默认按执行期限升序）。 */
    public static final String TRADE_PLAN_DEFAULT_SORT_FIELD = "exec_deadline";

    private static final Gson GSON = new Gson();

    private final TxTemplate tx;
    private final HedgePlanDao hedgePlanDao;
    private final HedgePlanExposureDao hedgePlanExposureDao;
    private final HedgeRatioCalcDao hedgeRatioCalcDao;
    private final CustomHedgeRuleDao customHedgeRuleDao;
    private final HedgeDocDao hedgeDocDao;
    private final PlanApprovalDao planApprovalDao;
    private final PlanStateLogDao planStateLogDao;
    private final TradePlanDao tradePlanDao;
    private final ExposureDao exposureDao;
    private final ProductContractDao productContractDao;
    private final SubAccountVisibility subAccountVisibility;
    private final AuthorizationDao authorizationDao;
    private final SubProductWhitelistDao subProductWhitelistDao;
    private final ExposureDetailDao exposureDetailDao;
    private final PurchaseContractDao purchaseContractDao;
    private final SalesContractDao salesContractDao;
    private final TradingRoleChecker tradingRoleChecker;

    /**
     * 构造。
     *
     * @param tx                    事务模板
     * @param hedgePlanDao          套保计划 DAO
     * @param hedgePlanExposureDao  计划-敞口绑定 DAO
     * @param hedgeRatioCalcDao     比率计算留痕 DAO
     * @param customHedgeRuleDao    自定义规则 DAO
     * @param hedgeDocDao           书面文件 DAO
     * @param planApprovalDao       审批链 DAO
     * @param planStateLogDao       状态流转留痕 DAO
     * @param tradePlanDao          交易计划 DAO
     * @param exposureDao           敞口 DAO
     * @param productContractDao    合约关联 DAO（校验合约属于该品种）
     * @param subAccountVisibility  调用者可见子账户取数入口 + 写面可见性断言（{@code NFR-SEC-7} 读写面强制点）
     * @param authorizationDao      操作授权 DAO（{@code R-PLAN-3/①}；{@code FR-AUTH-10}）
     * @param subProductWhitelistDao 子账户-品种白名单 DAO（{@code R-PLAN-3/②}）
     * @param exposureDetailDao     敞口构成明细 DAO（{@code R-PLAN-3/④} 追溯实货合同到期日）
     * @param purchaseContractDao   采购合同 DAO（{@code R-PLAN-3/④} 合同到期日）
     * @param salesContractDao      销售合同 DAO（{@code R-PLAN-3/④} 合同到期日）
     * @param tradingRoleChecker    交易岗权限取数入口（{@code R-PLAN-3/⑦} 岗位分离）
     */
    public PlanService(TxTemplate tx, HedgePlanDao hedgePlanDao,
                       HedgePlanExposureDao hedgePlanExposureDao, HedgeRatioCalcDao hedgeRatioCalcDao,
                       CustomHedgeRuleDao customHedgeRuleDao, HedgeDocDao hedgeDocDao,
                       PlanApprovalDao planApprovalDao, PlanStateLogDao planStateLogDao,
                       TradePlanDao tradePlanDao, ExposureDao exposureDao,
                       ProductContractDao productContractDao,
                       SubAccountVisibility subAccountVisibility,
                       AuthorizationDao authorizationDao,
                       SubProductWhitelistDao subProductWhitelistDao,
                       ExposureDetailDao exposureDetailDao,
                       PurchaseContractDao purchaseContractDao,
                       SalesContractDao salesContractDao,
                       TradingRoleChecker tradingRoleChecker) {
        this.tx = tx;
        this.hedgePlanDao = hedgePlanDao;
        this.hedgePlanExposureDao = hedgePlanExposureDao;
        this.hedgeRatioCalcDao = hedgeRatioCalcDao;
        this.customHedgeRuleDao = customHedgeRuleDao;
        this.hedgeDocDao = hedgeDocDao;
        this.planApprovalDao = planApprovalDao;
        this.planStateLogDao = planStateLogDao;
        this.tradePlanDao = tradePlanDao;
        this.exposureDao = exposureDao;
        this.productContractDao = productContractDao;
        this.subAccountVisibility = subAccountVisibility;
        this.authorizationDao = authorizationDao;
        this.subProductWhitelistDao = subProductWhitelistDao;
        this.exposureDetailDao = exposureDetailDao;
        this.purchaseContractDao = purchaseContractDao;
        this.salesContractDao = salesContractDao;
        this.tradingRoleChecker = tradingRoleChecker;
    }

    // ------------------------------------------------------------ T-015 比率三档

    /**
     * 简单比率法（{@code FR-PLAN-6}）：{@code h = 拟套保数量 / 敞口数量}。
     *
     * @param subAccountId 子账户
     * @param exposureId   敞口
     * @param planQty      拟套保数量
     * @param operatorId   操作人
     * @return 计算留痕行（含 {@code outputRatio} 与 {@code resultCode}）
     */
    public HedgeRatioCalc calcSimple(int subAccountId, long exposureId, BigDecimal planQty,
                                     long operatorId) {
        requireSubAccountWritable(operatorId, subAccountId);
        Exposure exposure = requireExposure(subAccountId, exposureId);
        HedgeRatioCalc calc = newCalc(subAccountId, exposureId, "SIMPLE", operatorId);
        calc.inputJson = GSON.toJson(payload("planQty", planQty, "exposureQty", exposure.exposureQty));
        BigDecimal ratio = divide(planQty, exposure.exposureQty);
        if (ratio.compareTo(BigDecimal.ZERO) <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            calc.outputRatio = BigDecimal.ZERO;
            calc.resultCode = "RATIO_OUT_OF_RANGE";
            calc.failDetail = "简单比率法结果须 ∈ (0,1]：拟套保=" + text(planQty) + " 敞口="
                    + text(exposure.exposureQty) + " 结果=" + text(ratio);
        } else {
            calc.outputRatio = ratio;
            calc.resultCode = "SUCCESS";
        }
        persist(calc, operatorId);
        return calc;
    }

    /**
     * 最小方差法（{@code FR-PLAN-7}）：{@code h = ρ × σ_S / σ_F}，样本 ≥
     * {@value #MIN_VARIANCE_MIN_SAMPLES} 个交易日（{@code CFG-06b}）。
     *
     * <p><b>样本不足一律拒绝且不返回比率</b>（{@code outputRatio = 0} +
     * {@code result_code = INSUFFICIENT_SAMPLE}），并在 {@code fail_detail} 中列明实际样本数
     * ——保证"样本不足时不得返回任何比率结果"在数据上不可伪造。
     *
     * @param subAccountId   子账户
     * @param exposureId     敞口
     * @param spotSeries     现货价序列（与 {@code futuresSeries} 等长、按日对齐）
     * @param futuresSeries  期货价序列
     * @param operatorId     操作人
     * @return 计算留痕行
     */
    public HedgeRatioCalc calcMinVariance(int subAccountId, long exposureId,
                                          List<BigDecimal> spotSeries, List<BigDecimal> futuresSeries,
                                          long operatorId) {
        requireSubAccountWritable(operatorId, subAccountId);
        Exposure exposure = requireExposure(subAccountId, exposureId);
        HedgeRatioCalc calc = newCalc(subAccountId, exposureId, "MIN_VARIANCE", operatorId);
        int samples = spotSeries == null ? 0 : spotSeries.size();
        calc.sampleCount = samples;
        calc.inputJson = GSON.toJson(payload("samples", samples, "minSamples",
                MIN_VARIANCE_MIN_SAMPLES));
        if (spotSeries == null || futuresSeries == null || spotSeries.size() != futuresSeries.size()) {
            calc.outputRatio = BigDecimal.ZERO;
            calc.resultCode = "INSUFFICIENT_SAMPLE";
            calc.failDetail = "现货/期货序列缺失或长度不一致：spot=" + samples + " futures="
                    + (futuresSeries == null ? 0 : futuresSeries.size()) + "；不得静默跳过缺失日";
            persist(calc, operatorId);
            return calc;
        }
        if (samples < MIN_VARIANCE_MIN_SAMPLES) {
            calc.outputRatio = BigDecimal.ZERO;
            calc.resultCode = "INSUFFICIENT_SAMPLE";
            calc.failDetail = "样本不足：实际 " + samples + " 个交易日，要求 ≥ "
                    + MIN_VARIANCE_MIN_SAMPLES + "（CFG-06b HG.PLAN.MINVAR_MIN_SAMPLES）；"
                    + "不返回任何比率结果";
            persist(calc, operatorId);
            return calc;
        }
        BigDecimal rho = correlation(spotSeries, futuresSeries);
        BigDecimal sigmaSpot = stddev(spotSeries);
        BigDecimal sigmaFutures = stddev(futuresSeries);
        calc.rho = rho;
        calc.sigmaSpot = sigmaSpot;
        calc.sigmaFutures = sigmaFutures;
        if (sigmaFutures.compareTo(BigDecimal.ZERO) == 0) {
            calc.outputRatio = BigDecimal.ZERO;
            calc.resultCode = "SIGMA_FUTURES_ZERO";
            calc.failDetail = "期货价标准差为 0（样本无波动），无法计算 h = ρ×σ_S/σ_F";
            persist(calc, operatorId);
            return calc;
        }
        BigDecimal ratio = rho.multiply(sigmaSpot).divide(sigmaFutures, 6, RoundingMode.HALF_UP);
        if (ratio.compareTo(BigDecimal.ZERO) <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            calc.outputRatio = BigDecimal.ZERO;
            calc.resultCode = "RATIO_OUT_OF_RANGE";
            calc.failDetail = "最小方差法结果须 ∈ (0,1]：ρ=" + text(rho) + " σ_S=" + text(sigmaSpot)
                    + " σ_F=" + text(sigmaFutures) + " h=" + text(ratio);
        } else {
            calc.outputRatio = ratio;
            calc.resultCode = "SUCCESS";
        }
        persist(calc, operatorId);
        return calc;
    }

    /**
     * 自定义规则法（{@code FR-PLAN-8} / {@code Q-6}）：仅"固定比例上限 + 适用条件"，
     * 多套规则叠加 = <b>取最严（{@code min(ratio_cap)}）</b>，取一算法确定性写死为
     * {@code ratio_cap 升序 → priority 升序 → id 升序}。
     *
     * @param subAccountId 子账户
     * @param productId    品种（{@code null} 表示全品种规则参与）
     * @param direction    方向（{@code BUY} / {@code SELL}）
     * @param requestedRatio 计划拟用比率（可空；非空时校验是否超上限）
     * @param bizDate      业务日期（判定规则生效期）
     * @param operatorId   操作人
     * @return 计算留痕行
     */
    public HedgeRatioCalc calcCustomRule(int subAccountId, long productId, String direction,
                                         BigDecimal requestedRatio, LocalDate bizDate,
                                         long operatorId) {
        requireSubAccountWritable(operatorId, subAccountId);
        HedgeRatioCalc calc = newCalc(subAccountId, null, "CUSTOM_RULE", operatorId);
        calc.subAccountId = subAccountId;
        List<CustomHedgeRule> candidates =
                customHedgeRuleDao.findEffective(subAccountId, productId, direction, bizDate);
        calc.hitRuleCount = candidates.size();
        if (candidates.isEmpty()) {
            calc.outputRatio = BigDecimal.ZERO;
            calc.resultCode = "RULE_MISSING";
            calc.failDetail = "无生效的自定义规则（子账户=" + subAccountId + " 品种=" + productId
                    + " 方向=" + direction + " 业务日=" + bizDate + "）；不得默认放行任何比率";
            persist(calc, operatorId);
            return calc;
        }
        CustomHedgeRule hit = candidates.get(0);   // 已按 ratio_cap ↑ → priority ↑ → id ↑ 排序
        calc.customRuleId = hit.id;
        calc.appliedRuleId = hit.id;
        calc.appliedRatioCap = hit.ratioCap;
        calc.customRuleDesc = hit.ruleCode + "/" + hit.ruleName + "/" + hit.basis;
        calc.inputJson = GSON.toJson(payload("requestedRatio", requestedRatio, "ruleCode",
                hit.ruleCode, "hitRules", candidates.size()));
        if (requestedRatio != null && requestedRatio.compareTo(hit.ratioCap) > 0) {
            calc.outputRatio = BigDecimal.ZERO;
            calc.resultCode = "EXCEEDS_CUSTOM_CAP";
            calc.failDetail = "拟用比率超出自定义规则上限：requested=" + text(requestedRatio)
                    + " cap=" + hit.ratioCap.toPlainString() + " 规则=" + hit.ruleCode
                    + "；多套规则叠加取最严，不得相加或相乘";
            persist(calc, operatorId);
            return calc;
        }
        calc.outputRatio = hit.ratioCap.setScale(6, RoundingMode.HALF_UP);
        calc.resultCode = "SUCCESS";
        persist(calc, operatorId);
        return calc;
    }

    // ------------------------------------------------------------ T-016 计划编制与门禁

    /** 计划-敞口绑定入参。 */
    public static final class BindSpec {

        /** 敞口 ID。 */
        public final long exposureId;

        /** 本计划对该敞口的覆盖数量（&gt; 0）。 */
        public final BigDecimal coveredQty;

        /**
         * 构造。
         *
         * @param exposureId 敞口 ID
         * @param coveredQty 覆盖数量
         */
        public BindSpec(long exposureId, BigDecimal coveredQty) {
            this.exposureId = exposureId;
            this.coveredQty = coveredQty;
        }
    }

    /**
     * 合规预校验快照（{@code java/CONTRACTS.md} §1.5 / {@code ARCHITECTURE.md} §6.4 唯一结构）。
     *
     * <p>JSON 形态：{@code {"items":[…],"unverified":[…],"generated_at":…,"generated_by":…}}；
     * 由 Gson 以 {@link com.google.gson.annotations.SerializedName} 落与冻结键名完全一致的键。
     * 成功信封 {@code data.precheckResult} 为该对象；{@code unverified} 亦随信封单独返回（三项可见性之 (a)）。
     */
    public static final class PrecheckResult {

        /** 逐项判定（已校验项，{@code verdict} 成功路径恒 {@code PASS}）。 */
        @SerializedName("items")
        public final List<PrecheckItem> items;

        /** 未校验项（M1 恒含 {@code "R-PLAN-3/③"}：规模红线归 M3、分母表 {@code t_annual_scale} 不存在）。 */
        @SerializedName("unverified")
        public final List<String> unverified;

        /** 生成时间（服务端时钟）。 */
        @SerializedName("generated_at")
        public final String generatedAt;

        /** 生成人（发起人 ID）。 */
        @SerializedName("generated_by")
        public final long generatedBy;

        PrecheckResult(List<PrecheckItem> items, List<String> unverified, long generatedBy) {
            this.items = items;
            this.unverified = unverified;
            this.generatedAt = String.valueOf(Clocks.now());
            this.generatedBy = generatedBy;
        }
    }

    /** 合规预校验单项（{@code rule/current/threshold/verdict/source}）。 */
    public static final class PrecheckItem {

        /** 规则编号（{@code R-PLAN-3/①…/⑦}）。 */
        @SerializedName("rule")
        public final String rule;

        /** 实测当前值。 */
        @SerializedName("current")
        public final String current;

        /** 该项阈值 / 上限。 */
        @SerializedName("threshold")
        public final String threshold;

        /** 判定（{@code PASS} / {@code FAIL}）。 */
        @SerializedName("verdict")
        public final String verdict;

        /** 判定来源（表名 / 服务）。 */
        @SerializedName("source")
        public final String source;

        PrecheckItem(String rule, String current, String threshold, String verdict, String source) {
            this.rule = rule;
            this.current = current;
            this.threshold = threshold;
            this.verdict = verdict;
            this.source = source;
        }
    }

    /**
     * 编制套保计划（{@code FR-PLAN-1~5}、{@code FR-PLAN-19}；草稿态，不占额度）。
     *
     * <p><b>写面子账户可见性</b>（{@code NFR-SEC-7} / {@code T-112}）：新建计划无既有 {@code planId}
     * 可走 {@link #requireWritablePlan}，故对请求携带的 {@code plan.subAccountId}（该表的<b>行级业务
     * 归属列</b>）直接断言可见性——与其余计划写面（既有计划统一经 {@code requireWritablePlan}）口径一致，
     * 越权 {@code 10004}（纵深防御：读面已 scoped，本断言关闭"知悉他人 exposureId 即可建计划"的缺口）。
     *
     * @param plan       计划（子账户 / 品种 / 方向 / 数量 / 期限 / 比率 必填）
     * @param binds      绑定敞口（至少一条；方向须与计划相反）
     * @param operatorId 发起人
     * @return 新行主键
     */
    public long createPlan(HedgePlan plan, List<BindSpec> binds, long operatorId) {
        requireSubAccount(plan.subAccountId);
        requireSubAccountWritable(operatorId, plan.subAccountId);
        requireText(plan.planType, "plan_type");
        requireText(plan.hedgeDirection, "hedge_direction");
        requireText(plan.unit, "unit");
        requireText(plan.ratioMethod, "ratio_method");
        if (plan.productId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "品种必填；复现=补齐 product_id");
        }
        if (plan.planQty == null || plan.planQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划数量必须 > 0：实际=" + plan.planQty + "；复现=填写正数");
        }
        if (plan.hedgeRatio == null || plan.hedgeRatio.compareTo(BigDecimal.ZERO) <= 0
                || plan.hedgeRatio.compareTo(BigDecimal.ONE) > 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套保比率必须 ∈ (0,1]（ck_hedge_plan_ratio）：实际=" + plan.hedgeRatio
                            + "；复现=按小数填写（如 0.8）");
        }
        requireTerm(plan.termStartDate, plan.termEndDate);
        if (plan.calcBaseDate == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "比率计算基准日必填；复现=补齐 calc_base_date");
        }
        if (binds == null || binds.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划必须绑定至少一条实货敞口（R-PLAN-1：计划只能从敞口出发）；"
                            + "复现=先选定待套保敞口");
        }
        String expectedExposureDirection = HEDGE_TO_EXPOSURE_OPPOSITE.get(plan.hedgeDirection);
        if (expectedExposureDirection == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套保方向只允许 BUY_HEDGE / SELL_HEDGE：实际=" + plan.hedgeDirection
                            + "；复现=按 TB-PLAN-1 取值域提交");
        }
        List<Exposure> exposures = new ArrayList<>();
        BigDecimal boundTotal = BigDecimal.ZERO;
        for (BindSpec bind : binds) {
            Exposure exposure = requireExposure(plan.subAccountId, bind.exposureId);
            if (!expectedExposureDirection.equals(exposure.direction)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "套保方向必须与绑定敞口方向相反（R-PLAN-1）：计划方向=" + plan.hedgeDirection
                                + " 敞口方向=" + exposure.direction + " exposureId=" + bind.exposureId
                                + "；复现=改用相反方向的计划");
            }
            if (!exposure.productId.equals(plan.productId)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "绑定敞口品种与计划品种不一致（R-PLAN-7）：exposureId=" + bind.exposureId
                                + "；复现=仅绑定同品种敞口");
            }
            BigDecimal uncovered = nz(exposure.exposureQty).subtract(nz(exposure.coveredQty));
            if (bind.coveredQty == null || bind.coveredQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "绑定覆盖数量必须 > 0：exposureId=" + bind.exposureId + "；复现=填写正数");
            }
            if (bind.coveredQty.compareTo(uncovered) > 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "覆盖数量超过敞口未覆盖数量：exposureId=" + bind.exposureId + " 覆盖="
                                + bind.coveredQty.toPlainString() + " 未覆盖=" + uncovered.toPlainString()
                                + "；复现=减小覆盖数量或更换敞口（§4.4 服务层规则）");
            }
            exposures.add(exposure);
            boundTotal = boundTotal.add(bind.coveredQty);
        }
        if (boundTotal.compareTo(plan.planQty) > 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划数量不得超过绑定敞口覆盖量合计：计划=" + plan.planQty.toPlainString()
                            + " 绑定合计=" + boundTotal.toPlainString()
                            + "；复现=调整计划数量或绑定敞口（FR-PLAN-1）");
        }
        plan.status = "DRAFT";
        plan.quotaOccupied = "N";
        plan.executedQty = BigDecimal.ZERO;
        plan.orderedQty = BigDecimal.ZERO;
        plan.currentDocVersion = 0;
        plan.applicantId = operatorId;
        // 计划编号全局唯一（uk_hedge_plan_no；DATA_MODEL.md:1687「计划编号全局唯一」）：编号形态
        // HP<yyyyMMdd><seq(4)>（§3.5，不含子账户维度），故 seq 必须取【全局】序号 —— count 不得带
        // sub_account_id 过滤。原「按子账户计数」使两个子账户在同一业务日各建计划时取到同一 seq，
        // 撞 uk_hedge_plan_no（BUG-003 / T-137）。
        // 全局计数随行数单调递增 ⇒ 任一既有 plan_no 的 seq 均 ≤ 全局计数 < 全局计数 + 1 ⇒ 新号必不撞既有行。
        // 并发窗口：两笔并发读到同一全局计数 ⇒ 取到同一 seq ⇒ 其中一笔撞唯一约束；经
        // BizNoGenerator.retryOnUniqueConflict（§3.5「最多 3 次」）重新取号并重试整笔事务。冲突发生在
        // 首条 plan 插入处，其后绑定 / 敞口乐观锁尚未执行 ⇒ 重试无副作用（幂等）。
        BizNoGenerator.retryOnUniqueConflict(RETRY_MAX_ATTEMPTS, () -> {
            try {
                long seq = hedgePlanDao.count(null, null) + 1;
                plan.planNo = BizNoGenerator.hedgePlan(Clocks.today(), seq);
                tx.inTransaction(connection -> {
                    if (hedgePlanDao.insert(plan, operatorId) != 1) {
                        throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                                "计划编号冲突（uk_hedge_plan_no）：planNo=" + plan.planNo
                                        + "；复现=重试（编号由应用层按全局序号生成）");
                    }
                    for (int i = 0; i < binds.size(); i++) {
                        BindSpec bind = binds.get(i);
                        Exposure exposure = exposures.get(i);
                        HedgePlanExposure row = new HedgePlanExposure();
                        row.subAccountId = plan.subAccountId;
                        row.hedgePlanId = plan.id;
                        row.exposureId = bind.exposureId;
                        row.coveredQty = bind.coveredQty;
                        row.bindTime = Clocks.now();
                        row.status = "BOUND";
                        if (hedgePlanExposureDao.insert(row, operatorId) != 1) {
                            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                                    "计划-敞口绑定写入失败；复现=核对 uk_plan_exposure");
                        }
                        exposure.coveredQty = nz(exposure.coveredQty).add(bind.coveredQty);
                        exposure.status = exposure.coveredQty.compareTo(nz(exposure.exposureQty)) >= 0
                                ? "COVERED" : "PARTIALLY_COVERED";
                        if (exposureDao.updateById(exposure, operatorId) != 1) {
                            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                                    "敞口并发冲突（covered_qty 乐观锁）：exposureId=" + bind.exposureId
                                            + "；复现=重新读取后再提交");
                        }
                    }
                    writeStateLog(plan, null, "DRAFT", "NONE", null, operatorId, "编制计划（草稿）");
                    return null;
                });
                return true;
            } catch (IllegalStateException e) {
                // TxTemplate 把 SQLException 包装为 IllegalStateException（TxTemplate.java:147 附近）；
                // 仅当根因为唯一约束冲突（SQLState 23xx，DM 与主流库遵循）时解包为 SQLException 交重试器
                // 重试；其余原样抛出（不吞异常、不误重试）。
                SQLException unique = uniqueViolationCause(e);
                if (unique != null) {
                    throw unique;
                }
                throw e;
            }
        });
        return plan.id;
    }

    /**
     * 绑定实货敞口到<b>已存在</b>的套保计划（{@code FR-PLAN-19}）。
     *
     * <p>规则（与 {@link #createPlan} 的绑定段同口径）：绑定敞口须与计划<b>同子账户、同品种、
     * 方向相反</b>（{@code R-PLAN-7} / {@code R-PLAN-1}）；覆盖量之和不得超敞口未覆盖量；
     * 绑定覆盖量合计不得超计划数量。<b>仅草稿态可直接增删绑定</b>——已批准计划须走变更流程
     * （{@code FR-PLAN-19} 验收 4 / {@code R-PLAN-6}）。写路径经
     * {@link #requireWritablePlan} 强制子账户可见性（{@code NFR-SEC-7}/{@code T-102}）。
     *
     * @param planId     计划
     * @param binds      待绑定（至少一条）
     * @param operatorId 操作人（会话身份）
     * @return 新增绑定条数
     */
    public int bindExposure(long planId, List<BindSpec> binds, long operatorId) {
        HedgePlan plan = requireWritablePlan(planId, operatorId);
        if (!"DRAFT".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅草稿计划可直接增删绑定；已批准计划须走变更流程（FR-PLAN-19 验收 4 / R-PLAN-6）："
                            + "planId=" + planId + " status=" + plan.status
                            + "；复现=草稿态增删，或先终止后新建");
        }
        if (binds == null || binds.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "至少绑定一条实货敞口（R-PLAN-1）：复现=给出 binds");
        }
        String expectedDirection = HEDGE_TO_EXPOSURE_OPPOSITE.get(plan.hedgeDirection);
        if (expectedDirection == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划套保方向非法，无法判定绑定敞口方向：" + plan.hedgeDirection
                            + "；复现=核对 t_hedge_plan.hedge_direction 取值域");
        }
        List<Exposure> exposures = new ArrayList<>();
        BigDecimal newBound = BigDecimal.ZERO;
        for (BindSpec bind : binds) {
            Exposure exposure = requireExposure(plan.subAccountId, bind.exposureId);
            if (!expectedDirection.equals(exposure.direction)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "套保方向必须与绑定敞口方向相反（R-PLAN-1）：计划方向=" + plan.hedgeDirection
                                + " 敞口方向=" + exposure.direction + " exposureId=" + bind.exposureId
                                + "；复现=改用相反方向的计划");
            }
            if (plan.productId == null || !plan.productId.equals(exposure.productId)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "绑定敞口品种与计划品种不一致（R-PLAN-7）：exposureId=" + bind.exposureId
                                + "；复现=仅绑定同品种敞口");
            }
            BigDecimal uncovered = nz(exposure.exposureQty).subtract(nz(exposure.coveredQty));
            if (bind.coveredQty == null || bind.coveredQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "绑定覆盖数量必须 > 0：exposureId=" + bind.exposureId + "；复现=填写正数");
            }
            if (bind.coveredQty.compareTo(uncovered) > 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "覆盖数量超过敞口未覆盖数量（FR-PLAN-19 验收 3）：exposureId=" + bind.exposureId
                                + " 覆盖=" + bind.coveredQty.toPlainString() + " 未覆盖="
                                + uncovered.toPlainString() + "；复现=减小覆盖数量或更换敞口");
            }
            exposures.add(exposure);
            newBound = newBound.add(bind.coveredQty);
        }
        BigDecimal existingBound = BigDecimal.ZERO;
        for (HedgePlanExposure row : hedgePlanExposureDao.findByPlan(planId)) {
            if ("BOUND".equals(row.status)) {
                existingBound = existingBound.add(nz(row.coveredQty));
            }
        }
        BigDecimal total = existingBound.add(newBound);
        if (total.compareTo(nz(plan.planQty)) > 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "绑定覆盖量合计不得超过计划数量：已有=" + existingBound.toPlainString() + " 新增="
                            + newBound.toPlainString() + " 计划=" + nz(plan.planQty).toPlainString()
                            + "；复现=调整绑定数量或计划数量（FR-PLAN-1）");
        }
        return tx.inTransaction(connection -> {
            for (int i = 0; i < binds.size(); i++) {
                BindSpec bind = binds.get(i);
                Exposure exposure = exposures.get(i);
                HedgePlanExposure row = new HedgePlanExposure();
                row.subAccountId = plan.subAccountId;
                row.hedgePlanId = planId;
                row.exposureId = bind.exposureId;
                row.coveredQty = bind.coveredQty;
                row.bindTime = Clocks.now();
                row.status = "BOUND";
                if (hedgePlanExposureDao.insert(row, operatorId) != 1) {
                    throw BizException.of(ErrorCode.INTERNAL_ERROR,
                            "计划-敞口绑定写入失败；复现=核对 uk_plan_exposure");
                }
                exposure.coveredQty = nz(exposure.coveredQty).add(bind.coveredQty);
                exposure.status = exposure.coveredQty.compareTo(nz(exposure.exposureQty)) >= 0
                        ? "COVERED" : "PARTIALLY_COVERED";
                if (exposureDao.updateById(exposure, operatorId) != 1) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "敞口并发冲突（covered_qty 乐观锁）：exposureId=" + bind.exposureId
                                    + "；复现=重新读取后再提交");
                }
            }
            writeStateLog(plan, "DRAFT", "DRAFT", "NONE", null, operatorId,
                    "绑定实货敞口 " + binds.size() + " 条（FR-PLAN-19）");
            return binds.size();
        });
    }

    /**
     * 生成套期关系书面文件（{@code FR-PLAN-9}；CAS 24 三要素；{@code R-PLAN-8}）。
     *
     * <p>三要素<b>任一为空</b>即拒绝生成——"未含正式指定 / 有效性评估方法 / 再平衡机制不得提交审批"
     * 在服务层显式判定（数据库侧另有 {@code NOT NULL} + {@code pending_external_review='Y'} 兜底）。
     *
     * @param planId              计划
     * @param designation         要素①正式指定
     * @param effectivenessMethod 要素②有效性评估方法
     * @param rebalanceMechanism  要素③再平衡机制
     * @param instrument          套期工具（合约标识）
     * @param operatorId          生成人
     * @return 新版本号
     */
    public int generateHedgeDoc(long planId, String designation, String effectivenessMethod,
                                String rebalanceMechanism, String instrument, long operatorId) {
        List<String> missing = new ArrayList<>();
        if (isBlank(designation)) {
            missing.add("要素①正式指定");
        }
        if (isBlank(effectivenessMethod)) {
            missing.add("要素②有效性评估方法");
        }
        if (isBlank(rebalanceMechanism)) {
            missing.add("要素③再平衡机制");
        }
        HedgePlan plan = requireWritablePlan(planId, operatorId);
        if (!missing.isEmpty()) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "书面文件三要素不齐备，不得生成（CAS 24 法定留痕）：planNo=" + plan.planNo,
                    "R-PLAN-8",
                    "缺失：" + String.join("、", missing),
                    "正式指定 + 有效性评估方法 + 再平衡机制 三项均须非空",
                    "补齐缺失要素后重新生成（缺任一要素的结构上写不进库）");
        }
        if (isBlank(instrument)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期工具必填（合约/持仓标识）：planNo=" + plan.planNo + "；复现=补齐 hedging_instrument");
        }
        List<HedgePlanExposure> binds = hedgePlanExposureDao.findByPlan(planId);
        if (binds.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划未绑定敞口，无法生成书面文件（被套期项目无从指定）：planNo=" + plan.planNo
                            + "；复现=先绑定实货敞口");
        }
        long exposureId = binds.get(0).exposureId;
        int version = plan.currentDocVersion + 1;
        return tx.inTransaction(connection -> {
            HedgeDoc current = hedgeDocDao.findCurrent(planId);
            if (current != null) {
                current.isCurrent = "N";
                current.invalidatedTime = Clocks.now();
                hedgeDocDao.updateById(current, operatorId);
            }
            HedgeDoc doc = new HedgeDoc();
            doc.subAccountId = plan.subAccountId;
            doc.hedgePlanId = planId;
            doc.docNo = BizNoGenerator.hedgeDoc(plan.planNo, version);
            doc.docVersion = version;
            doc.isCurrent = "Y";
            doc.elemFormalDesignation = designation;
            doc.elemEffectivenessMethod = effectivenessMethod;
            doc.elemRebalanceMechanism = rebalanceMechanism;
            doc.hedgedItemExposureId = exposureId;
            doc.hedgingInstrument = instrument;
            doc.pendingExternalReview = "Y";
            doc.generatedBy = operatorId;
            doc.generatedTime = Clocks.now();
            if (hedgeDocDao.insert(doc, operatorId) != 1) {
                throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                        "书面文件编号冲突（uk_hedge_doc_no）：docNo=" + doc.docNo + "；复现=重试");
            }
            plan.currentDocVersion = version;
            if (hedgePlanDao.updateById(plan, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "计划并发冲突：planNo=" + plan.planNo + "；复现=重新读取后再提交");
            }
            return version;
        });
    }

    /**
     * 提交审批（{@code FR-PLAN-10}；{@code R-PLAN-3} 服务端强制**阻断式合规预校验**）。
     *
     * <p><b>{@code CR-002} 修复（严重）</b>：预校验一律由<b>服务端自行重算并阻断</b>，
     * <b>禁止</b>以客户端传入的 {@code precheckResult} 作为通过依据——服务端<b>忽略其内容</b>，
     * 且<b>不因其缺失/为空而拒单</b>（否则客户端字串即门禁、可伪造绕过，违反 8 号文「阻断违规操作」）。
     * {@code java/CONTRACTS.md} §1.5 冻结：该字段在<b>请求侧忽略、响应侧由服务端回填</b>。
     *
     * <p><b>M1 服务端强制 6 项</b>（任一不通过 ⇒ 阻断，输出四项载荷 {@code ruleId/current/threshold/nextStep}）。
     * 判定次序<b>短路式</b>（{@code ARCHITECTURE.md} §6.4「判定顺序」；前置状态与法定留痕先判、资源/额度类后判）：
     * <ol>
     *   <li>前置：{@code status = DRAFT} ⇒ 否则 {@code 10009}（状态机，<b>非</b> {@code R-PLAN-3} 编号项）；</li>
     *   <li>{@code R-PLAN-3/⑥} 书面文件三要素齐备 ⇒ {@code 10012}；</li>
     *   <li>{@code R-PLAN-3/⑦} 发起人不得同时具备交易岗权限（岗位分离）⇒ {@code 10005}；</li>
     *   <li>{@code R-PLAN-3/①} 操作授权（子账户维度 {@code 10004} / 品种维度 {@code 10003}）；</li>
     *   <li>{@code R-PLAN-3/②} 品种在子账户白名单内 ⇒ {@code 10003}；</li>
     *   <li>{@code R-PLAN-3/④} 期限红线（建仓日 + 12 个月 vs 实货合同到期日 + 30 天，取较早者）⇒ {@code 10012}；</li>
     *   <li>{@code R-PLAN-3/⑤} 计划数量 ≤ 绑定敞口未覆盖量 ⇒ {@code 10012}。</li>
     * </ol>
     * {@code R-PLAN-3/③} 规模红线归 M3（分母 ∈ {@code t_annual_scale}，M1 不存在）⇒ 本期客观不可算：
     * <b>不因 ③ 单项拒单</b>，但成功信封与落库快照的 {@code unverified} 恒含 {@code "R-PLAN-3/③"}（三项可见性之 (a)(b)）。
     *
     * @param planId               计划
     * @param clientPrecheckResult 客户端传入的预校验结果（<b>服务端一律忽略</b>；缺失 / 为空不予拒绝）
     * @param operatorId           发起人（会话身份，不接受请求体传入）
     * @return 服务端生成的预校验快照（成功信封 {@code data.precheckResult}；{@code unverified} 亦随信封返回）
     */
    public PrecheckResult submitForApproval(long planId, String clientPrecheckResult, long operatorId) {
        HedgePlan plan = requireWritablePlan(planId, operatorId);
        // 前置：状态机（非 R-PLAN-3 编号项）
        if (!"DRAFT".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅草稿态可提交审批：planNo=" + plan.planNo + " status=" + plan.status
                            + "；复现=核对计划状态机");
        }
        // 服务端自行重算 M1 强制 6 项（短路式；客户端串不参与判定）
        List<PrecheckItem> items = new ArrayList<>();
        requireDocElements(plan, items);              // ⑥
        requirePositionSeparation(plan, items);       // ⑦
        requireAuthorization(plan, items);            // ①
        requireWhitelist(plan, items);                // ②
        requireTermRedline(plan, items);              // ④
        requireQuantity(plan, items);                 // ⑤
        PrecheckResult snapshot = new PrecheckResult(items, M1_UNVERIFIED, operatorId);
        String snapshotJson = GSON.toJson(snapshot);
        tx.inTransaction(connection -> {
            String before = plan.status;
            plan.status = "PENDING_APPROVAL";
            if (hedgePlanDao.updateById(plan, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "计划并发冲突：planNo=" + plan.planNo + "；复现=重新读取后再提交");
            }
            approval(plan, plan.applicantId, plan.applicantId, "SUBMIT", null, null, snapshotJson);
            writeStateLog(plan, before, "PENDING_APPROVAL", "NONE", null, operatorId, "提交审批");
            return null;
        });
        return snapshot;
    }

    // ------------------------------------------------------------ R-PLAN-3 六项（服务端强制）

    /** {@code R-PLAN-3/⑥} 书面文件三要素齐备（{@code R-PLAN-8} / CAS 24）。 */
    private void requireDocElements(HedgePlan plan, List<PrecheckItem> items) {
        HedgeDoc doc = hedgeDocDao.findCurrent(plan.id);
        List<String> missing = new ArrayList<>();
        if (doc == null || plan.currentDocVersion == null || plan.currentDocVersion <= 0) {
            missing.add("当前版本书面文件");
        } else {
            if (isBlank(doc.elemFormalDesignation)) {
                missing.add("要素①正式指定");
            }
            if (isBlank(doc.elemEffectivenessMethod)) {
                missing.add("要素②有效性评估方法");
            }
            if (isBlank(doc.elemRebalanceMechanism)) {
                missing.add("要素③再平衡机制");
            }
        }
        if (!missing.isEmpty()) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "书面文件三要素不齐备，不得提交审批（R-PLAN-8 / CAS 24）：planNo=" + plan.planNo,
                    RULE_DOC,
                    "current_doc_version=" + plan.currentDocVersion
                            + "；缺失：" + String.join("、", missing),
                    "须存在 is_current='Y' 且三要素齐备的当前版本书面文件",
                    "先调用书面文件生成接口（正式指定 + 有效性评估方法 + 再平衡机制）");
        }
        items.add(new PrecheckItem(RULE_DOC, "current_doc_version=" + plan.currentDocVersion,
                "三要素齐备", "PASS", "t_hedge_doc"));
    }

    /** {@code R-PLAN-3/⑦} 发起人不得同时具备交易岗权限（岗位分离；{@code R-PLAN-2}③）。 */
    private void requirePositionSeparation(HedgePlan plan, List<PrecheckItem> items) {
        if (tradingRoleChecker == null) {
            throw BizException.blocking(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "岗位分离取数入口缺位，按安全默认拒绝提交（NFR-SEC-7 同源 fail-closed）：planNo="
                            + plan.planNo,
                    RULE_POSITION_SEPARATION,
                    "tradingRoleChecker=null",
                    "须能判定发起人是否具交易岗权限（R-PLAN-2）",
                    "核对装配注入 TradingRoleChecker（AuthService.hasTradingRole）");
        }
        if (tradingRoleChecker.hasTradingRole(plan.applicantId)) {
            throw BizException.blocking(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "发起人不得同时具备交易岗权限（R-PLAN-2 / FR-PLAN-10 ⑦ 前中后台岗位分离）：planNo="
                            + plan.planNo,
                    RULE_POSITION_SEPARATION,
                    "applicantId=" + plan.applicantId + "（具备交易岗权限）",
                    "发起人须为非交易岗（前中后台分离）",
                    "改由非交易岗账号发起计划");
        }
        items.add(new PrecheckItem(RULE_POSITION_SEPARATION,
                "applicantId=" + plan.applicantId, "非交易岗", "PASS",
                "t_role / t_user_role（AuthService）"));
    }

    /** {@code R-PLAN-3/①} 操作授权（子账户维度 {@code 10004} / 品种维度 {@code 10003}；{@code FR-AUTH-10}）。 */
    private void requireAuthorization(HedgePlan plan, List<PrecheckItem> items) {
        LocalDate bizDate = Clocks.today();
        List<Authorization> effective = authorizationDao == null
                ? Collections.emptyList() : authorizationDao.findEffective(plan.applicantId, bizDate);
        boolean subAccountGranted = false;
        boolean productGranted = false;
        for (Authorization a : effective) {
            if (a.subAccountId == plan.subAccountId) {
                subAccountGranted = true;
                if (a.productId == plan.productId) {
                    productGranted = true;
                    break;
                }
            }
        }
        if (!subAccountGranted) {
            throw BizException.blocking(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "发起人对该子账户无有效操作授权（R-PLAN-3/①；FR-AUTH-10）：planNo=" + plan.planNo,
                    RULE_AUTH,
                    "userId=" + plan.applicantId + " subAccountId=" + plan.subAccountId + "（无有效授权）",
                    "须存在 status='EFFECTIVE' 且子账户匹配的操作授权",
                    "由管理层 / 审计岗授予该子账户的操作授权后重试");
        }
        if (!productGranted) {
            throw BizException.blocking(ErrorCode.FUNCTION_PERMISSION_DENIED,
                    "发起人无该品种的操作授权（R-PLAN-3/① 品种维度；FR-AUTH-10）：planNo=" + plan.planNo,
                    RULE_AUTH,
                    "userId=" + plan.applicantId + " productId=" + plan.productId + "（无品种授权）",
                    "须存在该子账户 + 该品种的有效操作授权",
                    "补授该品种的操作授权后重试");
        }
        items.add(new PrecheckItem(RULE_AUTH,
                "userId=" + plan.applicantId + " subAccountId=" + plan.subAccountId
                        + " productId=" + plan.productId,
                "EFFECTIVE 授权齐备", "PASS", "t_authorization"));
    }

    /** {@code R-PLAN-3/②} 品种在子账户白名单内（{@code t_sub_product_whitelist}）。 */
    private void requireWhitelist(HedgePlan plan, List<PrecheckItem> items) {
        List<SubProductWhitelist> whitelist = subProductWhitelistDao == null
                ? Collections.emptyList() : subProductWhitelistDao.findBySubAccount(plan.subAccountId);
        boolean allowed = false;
        for (SubProductWhitelist w : whitelist) {
            if (w.productId != null && w.productId.equals(plan.productId)
                    && !"DISABLED".equals(w.status)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw BizException.blocking(ErrorCode.FUNCTION_PERMISSION_DENIED,
                    "品种不在该子账户白名单内（R-PLAN-3/②；FR-PLAN-10）：planNo=" + plan.planNo,
                    RULE_WHITELIST,
                    "subAccountId=" + plan.subAccountId + " productId=" + plan.productId + "（不在白名单）",
                    "品种须在 t_sub_product_whitelist 中且 status='ENABLED'",
                    "由管理层维护该子账户的品种白名单后重试");
        }
        items.add(new PrecheckItem(RULE_WHITELIST,
                "subAccountId=" + plan.subAccountId + " productId=" + plan.productId,
                "在白名单内且启用", "PASS", "t_sub_product_whitelist"));
    }

    /** {@code R-PLAN-3/④} 期限红线（建仓日 + 12 个月 vs 实货合同到期日 + 30 天，取较早者；{@code Q-4}）。 */
    private void requireTermRedline(HedgePlan plan, List<PrecheckItem> items) {
        LocalDate capByPlan = plan.termStartDate == null
                ? null : plan.termStartDate.plusMonths(TERM_MAX_MONTHS);
        LocalDate capByContract = contractHorizon(plan);
        LocalDate cap = capByContract;
        if (capByPlan != null && (cap == null || capByPlan.isBefore(cap))) {
            cap = capByPlan;
        }
        if (cap == null) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "期限红线无法判定（缺建仓日 / 实货合同期限依据）：planNo=" + plan.planNo,
                    RULE_TERM,
                    "term_start_date=" + plan.termStartDate + " contract_end_date=null",
                    "建仓日 + " + TERM_MAX_MONTHS + " 个月 与 实货合同到期日 + "
                            + CONTRACT_GRACE_DAYS + " 天 的较早者",
                    "补齐实货合同期限依据（t_purchase_contract / t_sales_contract）后重试");
        }
        if (plan.termEndDate != null && plan.termEndDate.isAfter(cap)) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "计划期限超过红线（R-PLAN-3/④ / R-RISK-6 / Q-4）：planNo=" + plan.planNo,
                    RULE_TERM,
                    "term_end_date=" + plan.termEndDate,
                    "上限=" + cap + "（建仓日+" + TERM_MAX_MONTHS + "个月 与 合同到期+"
                            + CONTRACT_GRACE_DAYS + "天 取较早者）",
                    "缩短计划期限；若确有实货依据走延期 / 变更流程（extend_basis 说明）");
        }
        items.add(new PrecheckItem(RULE_TERM, "term_end_date=" + plan.termEndDate, "上限=" + cap,
                "PASS", "t_hedge_plan / t_purchase_contract / t_sales_contract"));
    }

    /**
     * 实货合同期限上限（合同到期日 + {@value #CONTRACT_GRACE_DAYS} 天；无合同依据 ⇒ {@code null}）。
     *
     * <p>取绑定敞口所涉 {@code CONTRACT_UNEXECUTED} 明细来源合同的 {@code contract_end_date} <b>最大值</b>
     * （计划被套期实货头寸存续至最晚到期的合同）+ 宽限天数。
     */
    private LocalDate contractHorizon(HedgePlan plan) {
        if (exposureDetailDao == null) {
            return null;
        }
        LocalDate latest = null;
        for (HedgePlanExposure bind : hedgePlanExposureDao.findByPlan(plan.id)) {
            for (ExposureDetail detail : exposureDetailDao.findByExposure(bind.exposureId)) {
                if (!"CONTRACT_UNEXECUTED".equals(detail.componentType) || detail.sourceId == null) {
                    continue;
                }
                LocalDate end = null;
                if ("PURCHASE_CONTRACT".equals(detail.sourceType) && purchaseContractDao != null) {
                    PurchaseContract c = purchaseContractDao.findById(detail.sourceId);
                    end = c == null ? null : c.contractEndDate;
                } else if ("SALES_CONTRACT".equals(detail.sourceType) && salesContractDao != null) {
                    SalesContract c = salesContractDao.findById(detail.sourceId);
                    end = c == null ? null : c.contractEndDate;
                }
                if (end != null && (latest == null || end.isAfter(latest))) {
                    latest = end;
                }
            }
        }
        return latest == null ? null : latest.plusDays(CONTRACT_GRACE_DAYS);
    }

    /**
     * {@code R-PLAN-3/⑤} 计划数量 ≤ 绑定敞口未覆盖数量。
     *
     * <p>未覆盖量 = {@code t_exposure.exposure_qty − covered_qty}；<b>加回本计划自身已占</b>
     * （绑定在编制时已把本计划覆盖量计入 {@code covered_qty}，此处排除自身以免重复计数）。
     */
    private void requireQuantity(HedgePlan plan, List<PrecheckItem> items) {
        BigDecimal available = BigDecimal.ZERO;
        for (HedgePlanExposure bind : hedgePlanExposureDao.findByPlan(plan.id)) {
            Exposure exposure = exposureDao == null ? null : exposureDao.findById(bind.exposureId);
            if (exposure == null) {
                continue;
            }
            BigDecimal uncovered = nz(exposure.exposureQty).subtract(nz(exposure.coveredQty))
                    .add(nz(bind.coveredQty));
            if (uncovered.compareTo(BigDecimal.ZERO) > 0) {
                available = available.add(uncovered);
            }
        }
        if (plan.planQty == null || plan.planQty.compareTo(available) > 0) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "计划数量超过绑定敞口未覆盖数量（R-PLAN-3/⑤；FR-PLAN-10）：planNo=" + plan.planNo,
                    RULE_QTY,
                    "planQty=" + text(plan.planQty),
                    "未覆盖量=" + available.toPlainString(),
                    "缩减计划数量或改绑敞口（FR-PLAN-1）");
        }
        items.add(new PrecheckItem(RULE_QTY, "planQty=" + text(plan.planQty),
                "未覆盖量=" + available.toPlainString(), "PASS", "t_exposure / t_hedge_plan_exposure"));
    }

    /**
     * 取某计划最近一次落库的合规预校验快照（{@code t_plan_approval.precheck_result} 最新非空）。
     *
     * <p>供**审批接口响应 / 审批页面**展示（{@code ARCHITECTURE.md} §6.4 三项可见性之 (c)：
     * ③ 规模红线的 {@code unverified} 标记须对审批人可见）。
     *
     * <p>兼容性：{@code CR-002} 之前的历史行可能存的是客户端自由串（非本服务生成的 JSON）⇒
     * 解析失败时返回 {@code null}（该字段在响应中省略），**不**因此令详情接口 500。
     *
     * @param planId 计划
     * @return 快照（结构化 JSON 对象）；无 / 非 JSON 历史行返回 {@code null}
     */
    public JsonObject latestPrecheckSnapshot(long planId) {
        PageQuery query = PageQuery.of("1", "20", "node_seq,desc", "node_seq");
        PageResult<PlanApproval> page = planApprovalDao.findPage(query, "hedge_plan_id = ?",
                new ArrayList<>(Collections.singletonList(planId)));
        for (PlanApproval row : page.items()) {
            if (isBlank(row.precheckResult)) {
                continue;
            }
            try {
                return JsonParser.parseString(row.precheckResult).getAsJsonObject();
            } catch (RuntimeException legacyOrCorrupt) {
                // CR-002 之前的历史行可能为客户端自由串——降级为不展示，不影响详情其余字段
                return null;
            }
        }
        return null;
    }


    /**
     * 审批通过（{@code FR-PLAN-11}；{@code R-PLAN-2}：审批人不得为发起人；通过即占用额度）。
     *
     * @param planId     计划
     * @param approverId 审批人
     * @param comment    审批意见
     */
    public void approve(long planId, long approverId, String comment) {
        HedgePlan plan = requireWritablePlan(planId, approverId);
        if (!"PENDING_APPROVAL".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅待审批态可通过审批：planNo=" + plan.planNo + " status=" + plan.status
                            + "；复现=核对计划状态机");
        }
        if (approverId == plan.applicantId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "审批人不得为发起人（R-PLAN-2 双人复核）：planNo=" + plan.planNo
                            + " applicantId=" + plan.applicantId
                            + "；复现=换一名审批人（不得自审自批）");
        }
        tx.inTransaction(connection -> {
            String before = plan.status;
            plan.status = "APPROVED";
            plan.quotaOccupied = "Y";
            if (hedgePlanDao.updateById(plan, approverId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "计划并发冲突：planNo=" + plan.planNo + "；复现=重新读取后再提交");
            }
            approval(plan, plan.applicantId, approverId, "APPROVE", "APPROVED", comment, null);
            writeStateLog(plan, before, "APPROVED", "OCCUPY", plan.planQty, approverId,
                    "审批通过并占用额度");
            return null;
        });
    }

    /**
     * 驳回（{@code FR-PLAN-12}：驳回原因必填；驳回至草稿、不占额度）。
     *
     * @param planId     计划
     * @param approverId 审批人
     * @param reason     驳回原因
     */
    public void reject(long planId, long approverId, String reason) {
        requireText(reason, "reject_reason");
        HedgePlan plan = requireWritablePlan(planId, approverId);
        if (!"PENDING_APPROVAL".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅待审批态可驳回：planNo=" + plan.planNo + " status=" + plan.status
                            + "；复现=核对计划状态机");
        }
        if (approverId == plan.applicantId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "审批人不得为发起人（R-PLAN-2）：planNo=" + plan.planNo
                            + "；复现=换一名审批人");
        }
        tx.inTransaction(connection -> {
            String before = plan.status;
            plan.status = "DRAFT";
            plan.quotaOccupied = "N";
            hedgePlanDao.updateById(plan, approverId);
            approval(plan, plan.applicantId, approverId, "REJECT", "REJECTED", reason, null);
            writeStateLog(plan, before, "DRAFT", "NONE", null, approverId, reason);
            return null;
        });
    }

    /**
     * 撤销（{@code FR-PLAN-13}）：待审批 → 草稿。
     *
     * <p><b>实现口径（{@code T-102} ③ 订正）</b>：{@code FR-PLAN-13}（{@code REQUIREMENTS/PLAN.md:288-301}）
     * 的「描述 / 输入 / 处理规则 / 输出 / 验收标准」四节<b>均未限制操作人身份</b>（其描述主体为
     * 「财务/风控岗」；输出所列失败项为「状态不允许 / 原因缺失 / 无权限」，不含操作人身份限制）
     * ——故本 javadoc 据此订正、与实现一致（本方法只校验原因非空与「仅待审批态可撤销」，不比较
     * {@code operatorId} 与计划的发起人字段）。写面可见性由 {@link #requireWritablePlan}
     * （{@code NFR-SEC-7}，{@code T-102}）强制。
     *
     * @param planId     计划
     * @param operatorId 操作人（仅作审计留痕；{@code FR-PLAN-13} 未限定操作人身份）
     * @param reason     撤销原因（必填）
     */
    public void withdraw(long planId, long operatorId, String reason) {
        requireText(reason, "reason");
        HedgePlan plan = requireWritablePlan(planId, operatorId);
        if (!"PENDING_APPROVAL".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅待审批态可撤销：planNo=" + plan.planNo + " status=" + plan.status
                            + "；复现=核对计划状态机");
        }
        tx.inTransaction(connection -> {
            String before = plan.status;
            plan.status = "DRAFT";
            hedgePlanDao.updateById(plan, operatorId);
            approval(plan, plan.applicantId, null, "WITHDRAW", "WITHDRAWN", reason, null);
            writeStateLog(plan, before, "DRAFT", "NONE", null, operatorId, reason);
            return null;
        });
    }

    /**
     * 冻结（{@code FR-PLAN-16}）：已批准 → 冻结，<b>额度继续占用</b>（{@code R-PLAN-5}）。
     *
     * @param planId     计划
     * @param operatorId 操作人
     * @param reason     冻结原因（必填）
     */
    public void freeze(long planId, long operatorId, String reason) {
        requireText(reason, "freeze_reason");
        HedgePlan plan = requireWritablePlan(planId, operatorId);
        if (!"APPROVED".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅已批准计划可冻结：planNo=" + plan.planNo + " status=" + plan.status
                            + "；复现=核对计划状态机");
        }
        tx.inTransaction(connection -> {
            plan.status = "FROZEN";
            plan.freezeReason = reason;
            hedgePlanDao.updateById(plan, operatorId);
            writeStateLog(plan, "APPROVED", "FROZEN", "NONE", null, operatorId, reason);
            return null;
        });
    }

    /**
     * 解冻：冻结 → 已批准（额度本就占用，不重复占用）。
     *
     * @param planId     计划
     * @param operatorId 操作人
     */
    public void unfreeze(long planId, long operatorId) {
        HedgePlan plan = requireWritablePlan(planId, operatorId);
        if (!"FROZEN".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅冻结态可解冻：planNo=" + plan.planNo + " status=" + plan.status
                            + "；复现=核对计划状态机");
        }
        tx.inTransaction(connection -> {
            plan.status = "APPROVED";
            plan.freezeReason = null;
            hedgePlanDao.updateById(plan, operatorId);
            writeStateLog(plan, "FROZEN", "APPROVED", "NONE", null, operatorId, "解冻");
            return null;
        });
    }

    /**
     * 终止（{@code FR-PLAN-17}）：已批准/冻结 → 终止，<b>释放额度</b>（{@code R-PLAN-5}）。
     *
     * @param planId     计划
     * @param operatorId 操作人
     * @param reason     终止原因（必填）
     */
    public void terminate(long planId, long operatorId, String reason) {
        requireText(reason, "terminate_reason");
        HedgePlan plan = requireWritablePlan(planId, operatorId);
        if (!"APPROVED".equals(plan.status) && !"FROZEN".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅已批准/冻结计划可终止：planNo=" + plan.planNo + " status=" + plan.status
                            + "；复现=核对计划状态机");
        }
        tx.inTransaction(connection -> {
            String before = plan.status;
            BigDecimal release = nz(plan.planQty).subtract(nz(plan.executedQty));
            plan.status = "TERMINATED";
            plan.terminateReason = reason;
            plan.quotaOccupied = "N";
            hedgePlanDao.updateById(plan, operatorId);
            writeStateLog(plan, before, "TERMINATED", "RELEASE", release, operatorId, reason);
            return null;
        });
    }

    /**
     * 延期（{@code FR-PLAN-18} / {@code R-PLAN-6}）：延长计划期限，须给出实货合同期限依据。
     *
     * @param planId       计划
     * @param newEndDate   新期限止
     * @param extendBasis  展期依据（必填）
     * @param operatorId   操作人
     */
    public void extend(long planId, LocalDate newEndDate, String extendBasis, long operatorId) {
        requireText(extendBasis, "extend_basis");
        HedgePlan plan = requireWritablePlan(planId, operatorId);
        if (!"APPROVED".equals(plan.status) && !"FROZEN".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅已批准/冻结计划可延期：planNo=" + plan.planNo + " status=" + plan.status
                            + "；复现=核对计划状态机");
        }
        if (newEndDate == null || !newEndDate.isAfter(plan.termEndDate)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "新期限止须晚于原期限止：原=" + plan.termEndDate + " 新=" + newEndDate
                            + "；复现=调整新期限");
        }
        requireTerm(plan.termStartDate, newEndDate);
        tx.inTransaction(connection -> {
            String before = plan.termEndDate.toString();
            plan.termEndDate = newEndDate;
            plan.extendBasis = extendBasis;
            hedgePlanDao.updateById(plan, operatorId);
            approval(plan, plan.applicantId, null, "EXTEND", null, extendBasis, null);
            writeStateLog(plan, before, plan.termEndDate.toString(), "NONE", null, operatorId,
                    "延期：" + extendBasis);
            return null;
        });
    }

    /**
     * 分解交易计划（{@code FR-PLAN-14}）。
     *
     * <p>校验：计划须 {@code APPROVED}；合约须属于该品种；方向须与计划一致；
     * <b>累计交易计划数量 ≤ 套保计划数量</b>（{@code FR-PLAN-14} 验收 2）。
     *
     * <p><b>写面强制点（{@code T-102}；{@code ARCHITECTURE.md} §8.2）</b>：
     * <ol>
     *   <li><b>子账户可见性</b>（{@code NFR-SEC-7} / {@code R-PLAN-7}）：经 {@link #requireWritablePlan}
     *       在入口强制——受限身份对<b>他人子账户</b>的计划分解 ⇒ {@code 10004} 拒绝；</li>
     *   <li><b>「既有量」按调用者可见集合读取</b>：复用 {@link TradePlanDao#findByHedgePlanScoped} +
     *       {@link SubAccountScope}（DAO 层统一注入，<b>不自拼 {@code IN (...)}</b>）——含哨兵
     *       {ALL_ACCOUNT_SENTINEL}（跨账户只读）⇒ 不加过滤；否则 {@code sub_account_id IN (...)}，
     *       空集 ⇒ 恒假条件。⇒ <b>跨子账户既有交易计划不计入 {@code used}</b>。</li>
     * </ol>
     *
     * <p><b>不作为「操盘前置（{@code FR-AUTH-9}）强制点」</b>：{@code FR-PLAN-14}（{@code PLAN.md:303-316}）
     * 明列合法主体为「<b>财务/风控岗 或 期货交易岗</b>」，其「输出（失败项）」与「验收标准」均<b>不含权限项</b>
     * ⇒ 在此要求「交易岗能力」会误拒需求明列的合法主体。`assertCanTrade` 的服务端强制点为 <b>M2 下单写路径</b>
     * （{@code POST /api/v1/trade/order} → {@code TradingBridge.placeOrder} 前置，归 {@code T-046}）。
     *
     * @param planId     套保计划
     * @param tradePlans 交易计划（数量 / 合约 / 期限 由调用方给出）
     * @param operatorId 操作人（会话身份，不接受请求体传入）
     * @return 新增条数
     */
    public int decomposeTradePlans(long planId, List<TradePlan> tradePlans, long operatorId) {
        HedgePlan plan = requireWritablePlan(planId, operatorId);
        if (!"APPROVED".equals(plan.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅已批准计划可分解交易计划：planNo=" + plan.planNo + " status=" + plan.status
                            + "；复现=先完成审批");
        }
        if (tradePlans == null || tradePlans.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "未提供交易计划：planNo=" + plan.planNo + "；复现=给出至少一条交易计划");
        }
        // 「既有量」按调用者可见集合读取（跨子账户既有交易计划不计入 used）
        List<TradePlan> existing = existingTradePlansScoped(planId, operatorId);
        BigDecimal used = BigDecimal.ZERO;
        for (TradePlan row : existing) {
            used = used.add(nz(row.planQty));
        }
        BigDecimal total = used;
        List<String> problems = new ArrayList<>();
        String expectedDirection = "BUY_HEDGE".equals(plan.hedgeDirection) ? "BUY" : "SELL";
        for (TradePlan row : tradePlans) {
            if (row.planQty == null || row.planQty.compareTo(BigDecimal.ZERO) <= 0) {
                problems.add("交易计划数量必须 > 0：" + row.instrumentId);
                continue;
            }
            total = total.add(row.planQty);
            if (!expectedDirection.equals(row.direction)) {
                problems.add("方向与套保计划不一致（应为 " + expectedDirection + "）：" + row.instrumentId);
            }
            if (row.execDeadline == null || row.execDeadline.isAfter(plan.termEndDate)) {
                problems.add("执行期限超出计划期限（计划止 " + plan.termEndDate + "）：" + row.instrumentId);
            }
            ProductContract contract = row.instrumentId == null ? null
                    : productContractDao.findByInstrumentId(row.instrumentId);
            if (contract == null) {
                problems.add("合约未登记或不存在：" + row.instrumentId);
            } else if (!contract.productId.equals(plan.productId)) {
                problems.add("合约不属于该品种：" + row.instrumentId);
            }
        }
        if (total.compareTo(plan.planQty) > 0) {
            problems.add("累计交易计划数量超套保计划数量：累计=" + total.toPlainString()
                    + " 计划=" + plan.planQty.toPlainString());
        }
        if (!problems.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "交易计划分解校验失败（共 " + problems.size() + " 项）：\n  - "
                            + String.join("\n  - ", problems));
        }
        return tx.inTransaction(connection -> {
            int n = 0;
            for (TradePlan row : tradePlans) {
                row.subAccountId = plan.subAccountId;
                row.hedgePlanId = planId;
                row.productId = plan.productId;
                row.instrumentType = row.instrumentType == null ? "FUTURES" : row.instrumentType;
                row.executedQty = BigDecimal.ZERO;
                row.orderedQty = BigDecimal.ZERO;
                row.status = "PENDING";
                long seq = tradePlanDao.count("hedge_plan_id = ?", Arrays.asList(planId)) + n + 1;
                row.tradePlanNo = BizNoGenerator.tradePlan(Clocks.today(), seq);
                if (tradePlanDao.insert(row, operatorId) != 1) {
                    throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                            "交易计划编号冲突：tradePlanNo=" + row.tradePlanNo + "；复现=重试");
                }
                n++;
            }
            return n;
        });
    }

    /**
     * 查询交易计划（交易岗"待执行"列表入口，{@code FR-PLAN-15}）：筛选 + 分页 + 排序。
     *
     * <p><b>筛选（{@code FR-PLAN-15}「输入」列）</b>：子账户 / 套保计划（端点路径 {@code {id}}）/
     * 合约 / 状态 / 执行期限（闭区间）；各条件为空即不加该过滤。
     *
     * <p><b>排序</b>：默认按<b>执行期限升序</b>（{@code FR-PLAN-15}「处理规则」）；排序字段须在
     * {@link #TRADE_PLAN_SORT_FIELDS} 白名单内（防无效列名 / 防注入）。
     *
     * <p><b>子账户隔离（服务端强制点，{@code NFR-SEC-7}／{@code FR-PLAN-15} 验收 4／{@code R-PLAN-7}）</b>：
     * 由 {@code operatorId}（会话身份，不接受请求体传入）解析可见子账户集合后施加过滤
     * （{@code ARCHITECTURE.md} §8.2）：
     * <ul>
     *   <li>含哨兵 {@code 0}（跨账户只读）⇒ <b>不加过滤</b>（管理层 / 审计岗可跨子账户查看）；</li>
     *   <li>否则构造 {@code sub_account_id IN (...)}；<b>空集 ⇒ 恒假条件（返回空），不得退化为全可见</b>。</li>
     * </ul>
     * 强制点落在本服务层：即便调用方（handler / 前端）省略身份，也不得越权读取他子账户的交易计划。
     * 过滤列取交易计划自身的 {@code sub_account_id}（{@code FR-PLAN-14} 验收 4：交易计划显式携带子账户标识，
     * 且须与套保计划一致，{@code R-PLAN-7}）。
     *
     * <p><b>隔离与分页叠加</b>：{@code total} 与 {@code items} 均按<b>过滤后</b>结果计
     * （越权行既不入 {@code items}，也不计入 {@code total}）；空授权 ⇒ {@code total=0} 且 {@code items} 空。
     *
     * @param planId     套保计划（端点路径 {@code {id}}，即"套保计划"筛选条件）
     * @param operatorId 调用者（用于解析可见子账户；由会话决定，不接受请求体传入）
     * @param query      分页 / 排序
     * @param filter     附加筛选条件（{@code null} = 无附加筛选）
     * @return 分页结果（{@code items} 为 {@code t_trade_plan} 实体；空结果 {@code items} 为空、{@code total}=0）
     */
    public PageResult<TradePlan> listTradePlans(long planId, long operatorId,
                                                PageQuery query, TradePlanFilter filter) {
        requireSortFieldsAllowed(query, TRADE_PLAN_SORT_FIELDS);
        TradePlanFilter f = filter == null ? new TradePlanFilter() : filter;
        if (f.status != null && !TRADE_PLAN_STATUSES.contains(f.status)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "状态取值非法：" + f.status + "；允许=" + TRADE_PLAN_STATUSES + "；复现=按取值域提交");
        }
        StringBuilder where = new StringBuilder("hedge_plan_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(planId);
        appendCondition(where, args, "sub_account_id = ?", f.subAccountId);
        appendCondition(where, args, "instrument_id = ?", f.instrumentId);
        appendCondition(where, args, "status = ?", f.status);
        appendCondition(where, args, "exec_deadline >= ?", f.execDeadlineFrom);
        appendCondition(where, args, "exec_deadline <= ?", f.execDeadlineTo);
        List<Integer> visible = visibleSubAccountIds(operatorId);
        if (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            // 跨账户只读（管理层 / 审计岗）：不构造 scope、不加过滤
            return tradePlanDao.findPage(query, where.toString(), args);
        }
        // 其余：构造 scope（空集 ⇒ 恒假条件），DAO 层统一注入（分页 + 计数均带过滤）
        return tradePlanDao.findPageScoped(query,
                SubAccountScope.of("sub_account_id", visible), where.toString(), args);
    }

    /**
     * 交易计划列表筛选条件（{@code FR-PLAN-15}「输入」列）；各字段可空，为空即不加该条件。
     *
     * <p>字段为 {@code public}（与实体同一约定，{@code CONTRACTS.md} §2.3）。
     */
    public static final class TradePlanFilter {

        /** 子账户。 */
        public Integer subAccountId;

        /** 合约代码（{@code instrument_id}）。 */
        public String instrumentId;

        /** 状态（{@code TRADE_PLAN_STATUSES} 取值域）。 */
        public String status;

        /** 执行期限范围下界（含）。 */
        public LocalDate execDeadlineFrom;

        /** 执行期限范围上界（含）。 */
        public LocalDate execDeadlineTo;
    }

    // ------------------------------------------------------------ 内部

    private HedgeRatioCalc newCalc(int subAccountId, Long exposureId, String method, long operatorId) {
        HedgeRatioCalc calc = new HedgeRatioCalc();
        calc.subAccountId = subAccountId;
        calc.exposureId = exposureId;
        calc.ratioMethod = method;
        calc.outputRatio = BigDecimal.ZERO;
        calc.operatorId = operatorId;
        calc.calcTime = Clocks.now();
        return calc;
    }

    private void persist(HedgeRatioCalc calc, long operatorId) {
        tx.inTransaction(connection -> {
            if (hedgeRatioCalcDao.insert(calc, operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "比率计算留痕写入失败；复现=核对 t_hedge_ratio_calc 约束（result_code 取值域）");
            }
            return null;
        });
    }

    private void approval(HedgePlan plan, long applicantId, Long approverId, String nodeType,
                          String result, String comment, String precheckJson) {
        PlanApproval row = new PlanApproval();
        row.subAccountId = plan.subAccountId;
        row.hedgePlanId = plan.id;
        row.nodeSeq = (int) (planApprovalDao.count("hedge_plan_id = ?", Arrays.asList(plan.id)) + 1);
        row.nodeType = nodeType;
        row.applicantId = applicantId;
        row.approverId = approverId;
        row.approvalResult = result;
        row.approvalComment = comment;
        row.rejectReason = "REJECT".equals(nodeType) ? comment : null;
        row.precheckResult = precheckJson;
        row.actionTime = Clocks.now();
        planApprovalDao.insert(row, applicantId);
    }

    private void writeStateLog(HedgePlan plan, String fromStatus, String toStatus, String quotaAction,
                               BigDecimal quotaQty, long operatorId, String reason) {
        PlanStateLog log = new PlanStateLog();
        log.subAccountId = plan.subAccountId;
        log.hedgePlanId = plan.id;
        log.fromStatus = fromStatus;
        log.toStatus = toStatus;
        log.quotaAction = quotaAction;
        log.quotaQty = quotaQty;
        log.quotaProductId = plan.productId;
        log.dispatchStatus = "PENDING";
        log.operatorId = operatorId;
        log.reason = reason;
        log.actionTime = Clocks.now();
        planStateLogDao.insert(log, operatorId);
    }

    /**
     * 按主键取套保计划（详情/追溯下钻用）。
     *
     * <p><b>子账户隔离（服务端强制点，{@code NFR-SEC-7} / {@code FR-PLAN-5}）</b>：按调用者可见集合过滤；
     * <b>不可见的计划与不存在的计划返回同一结果（{@code null}）</b>，不泄露存在性，也不返回任何内容。
     *
     * @param planId     计划
     * @param operatorId 调用者（用于解析可见子账户；由会话决定，不接受请求体传入）
     * @return 可见计划；不存在或不可见均返回 {@code null}
     */
    public HedgePlan getPlan(long planId, long operatorId) {
        List<Integer> visible = visibleSubAccountIds(operatorId);
        if (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            // 跨账户只读（管理层 / 审计岗）：不构造 scope、不加过滤
            return hedgePlanDao.findById(planId);
        }
        return hedgePlanDao.findByIdScoped(planId,
                SubAccountScope.of("sub_account_id", visible));
    }

    /**
     * 按计划取绑定敞口（计划详情展示，{@code FR-PLAN-5} 验收 1）。
     *
     * @param planId 计划
     * @return 绑定行列表
     */
    public List<HedgePlanExposure> listBindings(long planId) {
        return hedgePlanExposureDao.findByPlan(planId);
    }

    /**
     * 查询套保计划列表（{@code FR-PLAN-4}）：条件筛选 + 分页 + 排序（默认 {@code created_time} 倒序）。
     *
     * <p>筛选条件参见 {@link PlanFilter}（子账户 / 品种 / 状态 / 计划类型 / 期限范围 / 创建时间范围 / 创建人）；
     * 分页与排序口径取自 {@link PageQuery}（{@code §4.1.1}：{@code size ∈ {10,20,50,100}}、默认 20）。
     * 排序字段须在 {@link #LIST_SORT_FIELDS} 白名单内（防无效列名 / 防注入）。
     *
     * <p><b>子账户隔离（服务端强制点，{@code NFR-SEC-7} / {@code FR-PLAN-4} 验收 4 / {@code FR-AUTH-13}）</b>：
     * 由 {@code operatorId} 解析可见子账户集合后施加过滤（{@code ARCHITECTURE.md} §8.2）：
     * <ul>
     *   <li>含哨兵 {@code 0}（跨账户只读）⇒ <b>不加过滤</b>（管理层 / 审计岗可跨子账户查看）；</li>
     *   <li>否则构造 {@code sub_account_id IN (...)}；<b>空集 ⇒ 恒假条件（返回空），不得退化为全可见</b>。</li>
     * </ul>
     * 强制点落在本服务层：即便调用方（handler / 前端）省略 {@code subAccountId} 筛选，也不得越权读取。
     *
     * @param operatorId 调用者（用于解析可见子账户；由会话决定，不接受请求体传入）
     * @param query      分页 / 排序
     * @param filter     筛选条件（{@code null} = 无筛选）
     * @return 分页结果（{@code items} 为 {@code t_hedge_plan} 实体）
     */
    public PageResult<HedgePlan> listPlans(long operatorId, PageQuery query, PlanFilter filter) {
        requireSortFieldsAllowed(query, LIST_SORT_FIELDS);
        PlanFilter f = filter == null ? new PlanFilter() : filter;
        if (f.status != null && !PLAN_STATUSES.contains(f.status)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "状态取值非法：" + f.status + "；允许=" + PLAN_STATUSES + "；复现=按取值域提交");
        }
        if (f.planType != null && !PLAN_TYPES.contains(f.planType)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划类型取值非法：" + f.planType + "；允许=" + PLAN_TYPES + "；复现=按取值域提交");
        }
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        appendCondition(where, args, "sub_account_id = ?", f.subAccountId);
        appendCondition(where, args, "product_id = ?", f.productId);
        appendCondition(where, args, "status = ?", f.status);
        appendCondition(where, args, "plan_type = ?", f.planType);
        appendCondition(where, args, "applicant_id = ?", f.applicantId);
        appendCondition(where, args, "term_start_date >= ?", f.termStartFrom);
        appendCondition(where, args, "term_start_date <= ?", f.termStartTo);
        // created_time 为 TIMESTAMP：按"日期闭区间"过滤 → [当日 00:00, 次日 00:00)
        appendCondition(where, args, "created_time >= ?",
                f.createdFrom == null ? null : f.createdFrom.atStartOfDay());
        appendCondition(where, args, "created_time < ?",
                f.createdTo == null ? null : f.createdTo.plusDays(1).atStartOfDay());
        String extraWhere = where.length() == 0 ? null : where.toString();
        List<Integer> visible = visibleSubAccountIds(operatorId);
        if (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            // 跨账户只读（管理层 / 审计岗）：不构造 scope、不加过滤
            return hedgePlanDao.findPage(query, extraWhere, args);
        }
        // 其余：构造 scope（空集 ⇒ 恒假条件），DAO 层统一注入
        return hedgePlanDao.findPageScoped(query,
                SubAccountScope.of("sub_account_id", visible), extraWhere, args);
    }

    /** 追加一个占位符条件（值非空才追加；列名为代码内字面量，取值一律占位符）。 */
    private static void appendCondition(StringBuilder where, List<Object> args, String clause, Object value) {
        if (value == null) {
            return;
        }
        if (where.length() > 0) {
            where.append(" AND ");
        }
        where.append(clause);
        args.add(value);
    }

    /**
     * 校验分页查询的<b>每一个</b>排序键均在白名单内（T-096：多键排序须逐键过白名单）。
     *
     * <p><b>为何逐键</b>：{@code PageQuery} 支持多键（{@code f1,dir1,f2,dir2}），而这些键会直接进
     * DAO 拼 {@code ORDER BY}；仅校验首键会让第 2+ 键绕开防注入校验（{@code evil_column,asc,id,asc}
     * 的首键合法、第 2 键可越域）。故对全部键逐一校验，任一越界即 {@code 10001}。
     *
     * @param query   分页查询
     * @param allowed 允许的排序字段（列名白名单）
     */
    private static void requireSortFieldsAllowed(PageQuery query, java.util.Set<String> allowed) {
        for (String field : query.sortFields()) {
            if (!allowed.contains(field)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "排序字段不在白名单：" + field + "；允许=" + allowed
                                + "；复现=改用允许的排序字段");
            }
        }
    }

    /**
     * 计划列表筛选条件（{@code FR-PLAN-4} 输入项）；各字段可空，为空即不加该条件。
     *
     * <p>字段为 {@code public}（与实体同一约定，{@code CONTRACTS.md} §2.3）。
     */
    public static final class PlanFilter {

        /** 子账户。 */
        public Integer subAccountId;

        /** 品种。 */
        public Long productId;

        /** 状态（{@code PLAN_STATUSES} 取值域）。 */
        public String status;

        /** 计划类型（{@code COMMODITY} / {@code TRADE}）。 */
        public String planType;

        /** 计划期限起范围下界（含）。 */
        public LocalDate termStartFrom;

        /** 计划期限起范围上界（含）。 */
        public LocalDate termStartTo;

        /** 创建时间范围下界（含当日）。 */
        public LocalDate createdFrom;

        /** 创建时间范围上界（含当日）。 */
        public LocalDate createdTo;

        /** 创建人 / 发起人（{@code applicant_id}）。 */
        public Long applicantId;
    }

    /**
     * 取调用者可见子账户集合（{@code NFR-SEC-7} 取数侧；{@code ARCHITECTURE.md} §8.2）。
     *
     * <p><b>fail-closed</b>：取数入口缺位或返回 {@code null} 时一律按<b>空集</b>处理（谁都不见），
     * 绝不退化为"不过滤 = 全可见"。
     *
     * @param operatorId 调用者
     * @return 可见子账户集合（含哨兵 0 = 跨账户只读；可能为空）
     */
    private List<Integer> visibleSubAccountIds(long operatorId) {
        List<Integer> ids = subAccountVisibility == null
                ? null : subAccountVisibility.visibleSubAccountIds(operatorId);
        return ids == null ? Collections.emptyList() : ids;
    }

    /**
     * 取某套保计划下的<b>既有交易计划</b>（用于分解时的「累计量」校验），按调用者可见集合过滤
     * （{@code NFR-SEC-7} 写路径读取面强制点，{@code T-102} ④）。
     *
     * <p>与 {@code listTradePlans} 同法：含哨兵 {@link SubAccountScope#ALL_ACCOUNT_SENTINEL}
     * （跨账户只读）⇒ 不加过滤（走 {@link TradePlanDao#findByHedgePlan}）；否则经
     * {@link TradePlanDao#findByHedgePlanScoped} 由 DAO 层注入 {@code sub_account_id IN (...)}
     * （空集 ⇒ 恒假条件）。<b>DAO 层不自拼 {@code IN (...)}</b>，由 {@link SubAccountScope} 集中构造。
     *
     * @param planId     套保计划
     * @param operatorId 调用者
     * @return 既有交易计划（可见范围内）
     */
    private List<TradePlan> existingTradePlansScoped(long planId, long operatorId) {
        List<Integer> visible = visibleSubAccountIds(operatorId);
        if (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            return tradePlanDao.findByHedgePlan(planId);
        }
        return tradePlanDao.findByHedgePlanScoped(planId,
                SubAccountScope.of("sub_account_id", visible));
    }

    private HedgePlan requirePlan(long planId) {
        HedgePlan plan = hedgePlanDao.findById(planId);
        if (plan == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套保计划不存在：id=" + planId + "；复现=按 plan_no 查询确认 ID");
        }
        return plan;
    }

    /**
     * <b>写路径统一入口</b>：取计划并强制调用者对该计划所属子账户的<b>写面可见性</b>
     * （{@code NFR-SEC-7} 写路径强制点，{@code T-102}；{@code ARCHITECTURE.md} §8.2）。
     *
     * <p>所有计划写动作（{@code submit}/{@code approve}/{@code reject}/{@code withdraw}/
     * {@code freeze}/{@code unfreeze}/{@code terminate}/{@code extend}/{@code doc}/{@code decompose}）
     * 一律经本方法取计划——把「写动作须对目标子账户可见」这一强制点<b>集中一处</b>，
     * 不由各动作各自复制（漏加 = 越权写）。判据由 {@link SubAccountVisibility#assertSubAccountWritable}
     * 给出（生产装配下即 {@link AuthService} 的带留痕实现）。
     *
     * <p><b>fail-closed</b>：取数入口缺位（{@code null}）时按<b>空集</b>处理（一律拒绝），
     * 绝不退化为"不过滤 = 放行"。
     *
     * @param planId     计划
     * @param operatorId 操作人（会话身份，不接受请求体传入）
     * @return 目标计划（调用者可见）
     */
    private HedgePlan requireWritablePlan(long planId, long operatorId) {
        HedgePlan plan = requirePlan(planId);
        if (plan.subAccountId == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "计划未携带子账户标识，拒绝写操作（R-PLAN-7 子账户一致性）：planId=" + planId
                            + "；复现=核对 t_hedge_plan.sub_account_id 非空");
        }
        if (subAccountVisibility == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "子账户可见性取数入口缺位，按安全默认拒绝写操作（NFR-SEC-7）：planId=" + planId
                            + "；复现=核对装配注入 SubAccountVisibility");
        }
        subAccountVisibility.assertSubAccountWritable(operatorId, plan.subAccountId);
        return plan;
    }

    /**
     * 写面<b>子账户写入</b>断言（{@code NFR-SEC-7}；{@code T-112}）。
     *
     * <p>用于「<b>新建 / 无既有计划可查</b>」的写入口（{@link #createPlan} 与比率三档计算
     * {@link #calcSimple}/{@link #calcMinVariance}/{@link #calcCustomRule}）——这些入口没有
     * {@code planId} 可走 {@link #requireWritablePlan}，故须对请求携带的 {@code subAccountId}
     * 直接断言；与 {@code requireWritablePlan} 互补、不重复。
     *
     * <p>判据由 {@link SubAccountVisibility#assertSubAccountWritable} 给出（{@code T-115}）：
     * <b>仅含哨兵（跨子账户只读 {@code CROSS_READONLY}）不得写</b>（{@code FR-AUTH-13} 验收 3）。
     *
     * <p><b>fail-closed</b>：取数入口缺位（{@code null}）或目标未携带子账户标识 ⇒ 一律 {@code 10004}，
     * 绝不退化为"不校验 = 放行"。
     *
     * @param operatorId   调用者（会话身份，不接受请求体传入）
     * @param subAccountId 目标子账户（{@code null} 视为不可见）
     */
    private void requireSubAccountWritable(long operatorId, Integer subAccountId) {
        if (subAccountVisibility == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "子账户可见性取数入口缺位，按安全默认拒绝写操作（NFR-SEC-7）：subAccountId=" + subAccountId
                            + "；复现=核对装配注入 SubAccountVisibility");
        }
        if (subAccountId == null) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "目标数据未携带子账户标识，按安全默认拒绝写操作（NFR-SEC-7 / R-PLAN-7）："
                            + "operatorId=" + operatorId + "；复现=核对请求 subAccountId 非空");
        }
        subAccountVisibility.assertSubAccountWritable(operatorId, subAccountId.intValue());
    }

    private Exposure requireExposure(int subAccountId, long exposureId) {
        Exposure exposure = exposureDao.findById(exposureId);
        if (exposure == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "实货敞口不存在：id=" + exposureId + "；复现=按 snapshot_id 查询确认 ID");
        }
        if (exposure.subAccountId == null || exposure.subAccountId != subAccountId) {
            throw BizException.of(ErrorCode.SUB_ACCOUNT_PERMISSION_DENIED,
                    "敞口与计划/计算请求的子账户不一致（R-PLAN-7 三者一致）：exposureId=" + exposureId
                            + " exposureSubAccount=" + exposure.subAccountId + " 请求子账户="
                            + subAccountId + "；复现=改用同子账户的敞口");
        }
        if ("Y".equals(exposure.unmappedFlag)) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "未映射敞口不参与计划绑定与比率计算（DC-041 语义边界）：exposureId=" + exposureId
                            + "；复现=先完成品种映射并重算敞口");
        }
        return exposure;
    }

    /** Pearson 相关系数 ρ（最小方差法输入之一）。 */
    private static BigDecimal correlation(List<BigDecimal> xs, List<BigDecimal> ys) {
        int n = xs.size();
        BigDecimal meanX = mean(xs);
        BigDecimal meanY = mean(ys);
        BigDecimal cov = BigDecimal.ZERO;
        BigDecimal varX = BigDecimal.ZERO;
        BigDecimal varY = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal dx = xs.get(i).subtract(meanX);
            BigDecimal dy = ys.get(i).subtract(meanY);
            cov = cov.add(dx.multiply(dy));
            varX = varX.add(dx.multiply(dx));
            varY = varY.add(dy.multiply(dy));
        }
        BigDecimal denom = sqrt(varX.multiply(varY));
        if (denom.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return cov.divide(denom, 6, RoundingMode.HALF_UP);
    }

    /** 样本标准差（总体口径，与 ρ 同分母）。 */
    private static BigDecimal stddev(List<BigDecimal> values) {
        BigDecimal mean = mean(values);
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            BigDecimal d = value.subtract(mean);
            sum = sum.add(d.multiply(d));
        }
        BigDecimal variance = sum.divide(new BigDecimal(values.size()), 12, RoundingMode.HALF_UP);
        return sqrt(variance);
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            sum = sum.add(value);
        }
        return sum.divide(new BigDecimal(values.size()), 12, RoundingMode.HALF_UP);
    }

    /** 牛顿迭代求平方根（避免 {@code Math.sqrt(double)} 引入二进制浮点误差）。 */
    private static BigDecimal sqrt(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal x = BigDecimal.valueOf(Math.sqrt(value.doubleValue()));
        for (int i = 0; i < 20; i++) {
            BigDecimal next = x.add(value.divide(x, MathContext.DECIMAL64))
                    .divide(new BigDecimal("2"), MathContext.DECIMAL64);
            if (next.subtract(x).abs().compareTo(new BigDecimal("1E-12")) < 0) {
                break;
            }
            x = next;
        }
        return x.setScale(6, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "比率计算的分母为空或为 0：分子=" + numerator + " 分母=" + denominator
                            + "；复现=先完成敞口计算（敞口数量为 0 时无套保意义）");
        }
        return numerator.divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private static void requireTerm(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划期限起止必填；复现=补齐 term_start_date / term_end_date");
        }
        if (end.isBefore(start)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划期限止不得早于起始日：start=" + start + " end=" + end + "；复现=调整期限");
        }
        if (end.isAfter(start.plusMonths(TERM_MAX_MONTHS))) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划期限不得超过起始日 + " + TERM_MAX_MONTHS + " 个月（R-RISK-6 / CFG-03）："
                            + start + " → " + end + "；复现=缩短期限或改走实货合同期限"
                            + "（须在 extend_basis 中说明依据）");
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String text(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void requireSubAccount(int subAccountId) {
        if (subAccountId <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "子账户必须为真实子账户（> 0）：实际=" + subAccountId + "；复现=传入真实子账户 ID");
        }
    }

    private static void requireText(String value, String field) {
        if (isBlank(value)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "必填字段为空：" + field + "；复现=补齐该字段后重新提交");
        }
    }

    /**
     * 从异常链中解出「唯一约束冲突」的 {@link SQLException}（编号重试判定用）。
     *
     * <p>{@code TxTemplate} 会把 DAO 抛出的 {@link SQLException} 包装为 {@link IllegalStateException}
     * （{@code TxTemplate.java:147} 附近）；本方法沿因果链查找唯一约束冲突（{@code SQLState 23xx}）的
     * 原始 {@link SQLException}，供 {@link BizNoGenerator#retryOnUniqueConflict} 判定并重试。
     *
     * @param error 异常（通常为包装后的 {@link IllegalStateException}）
     * @return 唯一约束冲突的 SQL 异常；非唯一冲突（或不可判定）返回 {@code null}
     */
    private static SQLException uniqueViolationCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException
                    && BizNoGenerator.isUniqueViolation((SQLException) cause)) {
                return (SQLException) cause;
            }
        }
        return null;
    }

    private static Map<String, String> oppositeMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("BUY_HEDGE", "SELL");
        map.put("SELL_HEDGE", "BUY");
        return Collections.unmodifiableMap(map);
    }
}
