package com.hedgeguard.service;

import com.hedgeguard.common.Clocks;
import com.hedgeguard.dao.AccountSubjectDao;
import com.hedgeguard.dao.BizNoGenerator;
import com.hedgeguard.dao.EffectivenessAssessDao;
import com.hedgeguard.dao.ExposureDao;
import com.hedgeguard.dao.HedgeDocDao;
import com.hedgeguard.dao.HedgePlanDao;
import com.hedgeguard.dao.HedgeRelationDao;
import com.hedgeguard.dao.PageQuery;
import com.hedgeguard.dao.PageResult;
import com.hedgeguard.dao.RebalanceRecordDao;
import com.hedgeguard.dao.SettleCycleDao;
import com.hedgeguard.dao.SubAccountScope;
import com.hedgeguard.dao.VoucherDao;
import com.hedgeguard.dao.VoucherLineDao;
import com.hedgeguard.dao.VoucherReviewDao;
import com.hedgeguard.dao.AccountingPeriodDao;
import com.hedgeguard.dao.PeriodCloseLogDao;
import com.hedgeguard.dao.entity.AccountSubject;
import com.hedgeguard.dao.entity.AccountingPeriod;
import com.hedgeguard.dao.entity.EffectivenessAssess;
import com.hedgeguard.dao.entity.Exposure;
import com.hedgeguard.dao.entity.HedgeDoc;
import com.hedgeguard.dao.entity.HedgePlan;
import com.hedgeguard.dao.entity.HedgeRelation;
import com.hedgeguard.dao.entity.PeriodCloseLog;
import com.hedgeguard.dao.entity.RebalanceRecord;
import com.hedgeguard.dao.entity.SettleCycle;
import com.hedgeguard.dao.entity.Voucher;
import com.hedgeguard.dao.entity.VoucherLine;
import com.hedgeguard.dao.entity.VoucherReview;
import com.hedgeguard.dao.tx.TxTemplate;
import com.hedgeguard.web.BizException;
import com.hedgeguard.web.ErrorCode;
import com.hedgeguard.web.JsonCodec;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 套期会计服务 —— 套期关系文档与有效性评估（{@code T-077}；{@code ARCHITECTURE.md} §6.10 首段）。
 *
 * <p><b>本单范围（T-077 四验收）</b>：① 生成套期关系（CAS 24 三要素，承载 = {@code t_hedge_doc}
 * 三要素列 + {@code t_hedge_relation} 正式指定行，R-ACCT-1）；② 三项定性观察点有效性判定
 * （品种相关性 / 方向一致性 / 期限对应性 ±3M，三项均满足即有效，CFG-07）；③ 量化监测指标
 * （CFG-07b 偏离 1 超 ±50% 提示复核）<b>仅监测、不判定有效性</b>（R-ACCT-2 / FR-ACCT-4 验收 2/5）；
 * ④ 评估记录落 {@code t_effectiveness_assess}（含观察点，FR-ACCT-2）。
 *
 * <p><b>边界声明（T-078+ 承接，本单不碰）</b>：FR-ACCT-3 再平衡记录（{@code t_rebalance_record}
 * 写入面）与四类触发判定、FR-ACCT-5 综合评估（{@code t_hedge_effect} 双侧盈亏）、
 * FR-ACCT-6~14 凭证/期间/自检 —— 均不在本单。
 *
 * <p><b>结构性保证</b>：
 * <ol>
 *   <li><b>三要素门禁</b>（R-ACCT-1，CAS 24 红线）：指定套期关系前须存在绑定计划当前版本的
 *       书面文件 {@code t_hedge_doc}，且三要素列全部非空——缺任一要素<b>拒绝生成并列出缺失项</b>
 *       （{@code 10012} 阻断，四项载荷）。文档由 {@code PLAN} 模块写入（{@code PlanService.generateHedgeDoc}
 *       已交付）；ACCT 只读引用（TB-PLAN-4 所有者声明），不复制本体。</li>
 *   <li><b>定性判定与量化监测分离</b>（R-ACCT-2 / Q-7）：结论 = 三项观察点的纯布尔合取；
 *       量化监测值（{@code monitor_deviation_ratio}）只落库并按阈值置 {@code monitor_review_flag}，
 *       <b>不参与结论计算</b>——本类不存在以 80%–125% 区间或任何量化区间为有效性判据的逻辑
 *       （FR-ACCT-4 验收 2 负向判据；全仓 grep 证据见交付回执）。</li>
 *   <li><b>评估记录不可改</b>（FR-ACCT-2 验收 3）：{@code DmEffectivenessAssessDao.updateById}
 *       显式拒绝（T-076 交付）；更正 = 新增记录 + {@code correctedFromId} 引用（{@link #correctAssessment}），
 *       原记录 {@code is_corrected} 置 'Y' 走 <b>DAO 专用标记方法</b>（见该方法披露）。</li>
 *   <li><b>观察点缺失拒绝</b>（FR-ACCT-4 验收 4）：观察点取值（'Y'/'N'）或判定基准缺失 ⇒
 *       评估被拒并<b>说明缺失项</b>；近相关（①='N'）未附书面经济关系说明亦列缺失项。</li>
 * </ol>
 *
 * <p><b>配置读取形态</b>（CFG-07 / CFG-07b）：经 {@link ConfigReader} 接缝惰性读取
 * （{@code FundService}/{@code SettleCheckService} 同族先例）；种子已由 {@code data-init.sql:318/:322}
 * 播种（T-077 实测），缺种子时<b>容错取缺省</b>并仅记录日志（T-188 CFG-26 先例：补播归播种微单）。
 *
 * <p><b>取号（O-4 教训落地）</b>：{@code relation_no} 全系统唯一（{@code uk_hedge_relation_no}），
 * {@code DATA_MODEL.md} §3.5 编号表<b>未规定</b>其形态、亦无业务日重置前提 ⇒ 取号 =
 * <b>全系统前缀 {@code HR} 内字典序最大 + 1</b>（{@link HedgeRelationDao#findMaxRelationNoByPrefix}），
 * 并发兜底 = 唯一约束 + 最多 3 次重试（{@code DATA_MODEL.md} §3.5；{@code FundService.retryApplyNo}
 * 同构形态）——<b>不得照搬「当日最大序号 + 1」</b>（T-137/T-141 判例的前提是编号按业务日重置，
 * 此处不成立，照搬会跨日撞号）。
 *
 * <p><b>子账户隔离</b>（NFR-SEC-7 / T-105/T-107 先例）：读取面按
 * {@link SubAccountVisibility#visibleSubAccountIds}（哨兵 0 = 跨账户只读不过滤；空集 = 恒假条件）；
 * 写面 {@link SubAccountVisibility#assertSubAccountWritable}（仅跨子账户只读者不得写）。
 *
 * <p>分层纪律（{@code §3.8}）：不出现 SQL 语句（E5）；不依赖 HTTP 类型；只经 DAO 接口访问数据。
 */
public final class AcctService {

    private static final Logger LOG = Logger.getLogger(AcctService.class.getName());

    // ---------------------------------------------------------------- 常量（枚举取值与 ck_* 同源）

    /** 套期关系编号前缀（全系统唯一；DATA_MODEL.md §3.5 未规定形态 ⇒ 本服务定义并在回执披露）。 */
    public static final String RELATION_NO_PREFIX = "HR";

    /** 套期关系编号序号定长（对齐 HP/TP 的 4 位序号形态；{@code VARCHAR(32)} 容量充足）。 */
    public static final int RELATION_NO_SEQ_WIDTH = 4;

    /** 再平衡编号前缀（{@code rebalance_no} 全系统唯一；§3.5 未列 ⇒ 本服务定义并在回执披露）。 */
    public static final String REBALANCE_NO_PREFIX = "RB";

    /** 再平衡编号序号定长（4 位；与 HR 同形态）。 */
    public static final int REBALANCE_NO_SEQ_WIDTH = 4;

    /** 状态：已指定。 */
    public static final String REL_DESIGNATED = "DESIGNATED";
    /** 状态：有效。 */
    public static final String REL_EFFECTIVE = "EFFECTIVE";
    /** 状态：需再平衡。 */
    public static final String REL_NEED_REBALANCE = "NEED_REBALANCE";
    /** 状态：已重新指定。 */
    public static final String REL_REDESIGNATED = "REDESIGNATED";
    /** 状态：已终止。 */
    public static final String REL_TERMINATED = "TERMINATED";

    /** 触发类型：敞口变动（敞口侧；CFG-08 exposure_delta_pct）。 */
    public static final String TRIGGER_EXPOSURE_DELTA = "EXPOSURE_DELTA";
    /** 触发类型：套期比率偏离（工具侧；CFG-08 ratio_deviation_pct）。 */
    public static final String TRIGGER_RATIO_DEVIATION = "RATIO_DEVIATION";
    /** 触发类型：计划数量被修订 / 终止（敞口侧）。 */
    public static final String TRIGGER_PLAN_REVISED = "PLAN_REVISED";
    /** 触发类型：敞口消失需终止关系（敞口侧）。 */
    public static final String TRIGGER_EXPOSURE_GONE = "EXPOSURE_GONE";

    /** 触发条件所在侧：敞口侧（Q-8：敞口侧与工具侧必须各设一项，缺口=总量判定的另一侧）。 */
    public static final String SIDE_EXPOSURE = "EXPOSURE_SIDE";
    /** 触发条件所在侧：工具侧。 */
    public static final String SIDE_INSTRUMENT = "INSTRUMENT_SIDE";

    /** 评估方法：定性经济关系（本期唯一方法，ck 约束）。 */
    public static final String METHOD_QUALITATIVE = "QUALITATIVE_ECONOMIC_RELATION";

    /** 结论：有效。 */
    public static final String CONCLUSION_EFFECTIVE = "EFFECTIVE";
    /** 结论：无效。 */
    public static final String CONCLUSION_INEFFECTIVE = "INEFFECTIVE";
    /** 结论：需再平衡。 */
    public static final String CONCLUSION_NEED_REBALANCE = "NEED_REBALANCE";

    /** CFG-07 配置键（有效性三项定性观察点）。 */
    public static final String CFG_OBSERVERS = "accounting.effectiveness.observers";
    /** CFG-07 缺省值（data-init.sql:318 同值）。 */
    public static final String CFG_OBSERVERS_DEFAULT = "PRODUCT_LINKAGE,DIRECTION,TERM±3M";
    /** CFG-07b 配置键（量化监测阈值，仅监测不判定）。 */
    public static final String CFG_MONITOR_THRESHOLD = "accounting.effectiveness.monitor_threshold";
    /** CFG-07b 缺省值（0.50 = 偏离 1 超 ±50%，data-init.sql:322 同值）。 */
    public static final String CFG_MONITOR_THRESHOLD_DEFAULT = "0.50";

    /** CFG-08 敞口侧触发阈值键（实货敞口变动 > 阈值即触发；data-init.sql:326 已播种 0.20）。 */
    public static final String CFG_EXPOSURE_DELTA = "accounting.rebalance.exposure_delta_pct";
    /** CFG-08 敞口侧缺省值（ARCHITECTURE §11.1：0.20，取值域 (0,1]）。 */
    public static final String CFG_EXPOSURE_DELTA_DEFAULT = "0.20";
    /** CFG-08 工具侧触发阈值键（套期比率偏离 > 阈值即触发；data-init.sql:330 已播种 0.20）。 */
    public static final String CFG_RATIO_DEVIATION = "accounting.rebalance.ratio_deviation_pct";
    /** CFG-08 工具侧缺省值（ARCHITECTURE §11.1：0.20，取值域 (0,1]）。 */
    public static final String CFG_RATIO_DEVIATION_DEFAULT = "0.20";

    /** 期限对应性容忍窗口（敞口预计实现日 ±3 个月 ⇒ 偏离绝对值 ≤ 90 天；TB-ACCT-2 备注）。 */
    public static final int TERM_TOLERANCE_DAYS = 90;

    /** 编号唯一冲突重试上限（DATA_MODEL.md §3.5；FundService 同值）。 */
    private static final int RETRY_MAX_ATTEMPTS = 3;

    // ---------------------------------------------------------------- 凭证与期间（T-079；§3.5 形态同源）

    /** 凭证编号前缀（DATA_MODEL.md §3.5 权威：{@code AV<期间><seq(5)>}，例 {@code AV20260900031}）。 */
    public static final String VOUCHER_NO_PREFIX = "AV";

    /** 凭证编号期间段长度（{@code yyyyMM} 6 位；§3.5 例 {@code AV20260900031}）。 */
    public static final int VOUCHER_NO_PERIOD_WIDTH = 6;

    /** 凭证编号序号定长（§3.5：seq(5)，例 {@code 00031}）。 */
    public static final int VOUCHER_NO_SEQ_WIDTH = 5;

    /** 凭证状态：草稿（可编辑/删除，ACCT.md §2.1）。 */
    public static final String VOUCHER_DRAFT = "DRAFT";
    /** 凭证状态：待审核。 */
    public static final String VOUCHER_PENDING_REVIEW = "PENDING_REVIEW";
    /** 凭证状态：已审核（正式，不可修改）。 */
    public static final String VOUCHER_POSTED = "POSTED";
    /** 凭证状态：已冲销（终态）。 */
    public static final String VOUCHER_REVERSED = "REVERSED";

    /** 审核动作：提交审核（草稿 → 待审核）。 */
    public static final String REVIEW_ACTION_SUBMIT = "SUBMIT";
    /** 审核动作：审核通过（待审核 → 已审核）。 */
    public static final String REVIEW_ACTION_APPROVE = "APPROVE";
    /** 审核动作：驳回（待审核 → 草稿，原因必填）。 */
    public static final String REVIEW_ACTION_REJECT = "REJECT";
    /** 审核动作：撤回（待审核 → 草稿）。 */
    public static final String REVIEW_ACTION_WITHDRAW = "WITHDRAW";

    /** 关账留痕动作：关账。 */
    public static final String CLOSE_ACTION_CLOSE = "CLOSE";
    /** 关账留痕动作：撤销关账。 */
    public static final String CLOSE_ACTION_REOPEN = "REOPEN";

    /** 凭证模式：仅草稿（CFG-09；不做总账过账，草稿标注「待企业财务复核入账」）。 */
    public static final String VOUCHER_MODE_DRAFT_ONLY = "DRAFT_ONLY";
    /** CFG-09 凭证模式配置键（ARCHITECTURE.md §11.1；data-init.sql:338 已播种 DRAFT_ONLY）。 */
    public static final String CFG_VOUCHER_MODE = "accounting.voucher.mode";

    /** 草稿标注（Q-9：凭证为草稿不做总账过账，须标注「待企业财务复核入账」；随视图透出）。 */
    public static final String DRAFT_PENDING_ENTRY_NOTE = "待企业财务复核入账";

    private final TxTemplate tx;
    private final SubAccountVisibility visibility;
    private final ConfigReader config;
    private final HedgePlanDao hedgePlanDao;
    private final HedgeDocDao hedgeDocDao;
    private final HedgeRelationDao hedgeRelationDao;
    private final EffectivenessAssessDao assessDao;
    private final RebalanceRecordDao rebalanceDao;
    private final ExposureDao exposureDao;
    private final VoucherDao voucherDao;
    private final VoucherLineDao voucherLineDao;
    private final VoucherReviewDao voucherReviewDao;
    private final AccountingPeriodDao periodDao;
    private final PeriodCloseLogDao periodCloseLogDao;
    private final SettleCycleDao settleCycleDao;
    private final AccountSubjectDao subjectDao;

    /**
     * 构造（T-077 七参形态保留；再平衡依赖经 {@link #withRebalanceSupport} 注入——
     * 既有调用点零改动，新写路径装配齐后才有再平衡能力）。
     *
     * @param tx               事务模板
     * @param visibility       子账户可见性（读 / 写断言）
     * @param config           配置读取接缝（CFG-07 / CFG-07b / CFG-08；缺种子容错缺省）
     * @param hedgePlanDao     套保计划 DAO（读取计划归属）
     * @param hedgeDocDao      书面文件 DAO（三要素门禁取数；PLAN 所有、ACCT 只读）
     * @param hedgeRelationDao 套期关系 DAO
     * @param assessDao        有效性评估 DAO
     */
    public AcctService(TxTemplate tx, SubAccountVisibility visibility, ConfigReader config,
                       HedgePlanDao hedgePlanDao, HedgeDocDao hedgeDocDao,
                       HedgeRelationDao hedgeRelationDao, EffectivenessAssessDao assessDao) {
        this(tx, visibility, config, hedgePlanDao, hedgeDocDao, hedgeRelationDao, assessDao,
                null, null);
    }

    /**
     * 全参构造（T-078 再平衡写路径；再平衡依赖齐备才启用 {@link #rebalance}）。
     *
     * @param rebalanceDao 再平衡记录 DAO（{@code t_rebalance_record} 写入；T-076 交付面承接）
     * @param exposureDao  敞口 DAO（触发判定取数：敞口现值 / 敞口消失）
     */
    public AcctService(TxTemplate tx, SubAccountVisibility visibility, ConfigReader config,
                       HedgePlanDao hedgePlanDao, HedgeDocDao hedgeDocDao,
                       HedgeRelationDao hedgeRelationDao, EffectivenessAssessDao assessDao,
                       RebalanceRecordDao rebalanceDao, ExposureDao exposureDao) {
        this(tx, visibility, config, hedgePlanDao, hedgeDocDao, hedgeRelationDao, assessDao,
                rebalanceDao, exposureDao, null, null, null, null, null, null, null);
    }

    /**
     * 全参构造（T-079 凭证/期间写路径；凭证依赖齐备才启用 {@link #createVoucherDraft} 等）。
     *
     * @param voucherDao        凭证 DAO（{@code t_voucher} 写入；T-076 交付面承接）
     * @param voucherLineDao    分录 DAO（{@code t_voucher_line} 写入 + 借贷平衡跨行校验取数）
     * @param voucherReviewDao  审核链 DAO（{@code t_voucher_review} 写入；R-ACCT-6 / DC-032）
     * @param periodDao         会计期间 DAO（关账门禁取数）
     * @param periodCloseLogDao 关账留痕 DAO（{@code t_period_close_log} 只增不改）
     * @param settleCycleDao    结算单 DAO（关账前置②「结算已完成」取数）
     * @param subjectDao        科目字典 DAO（科目建议按 {@code biz_scene} 取数，R-ACCT-11）
     */
    public AcctService(TxTemplate tx, SubAccountVisibility visibility, ConfigReader config,
                       HedgePlanDao hedgePlanDao, HedgeDocDao hedgeDocDao,
                       HedgeRelationDao hedgeRelationDao, EffectivenessAssessDao assessDao,
                       RebalanceRecordDao rebalanceDao, ExposureDao exposureDao,
                       VoucherDao voucherDao, VoucherLineDao voucherLineDao,
                       VoucherReviewDao voucherReviewDao, AccountingPeriodDao periodDao,
                       PeriodCloseLogDao periodCloseLogDao, SettleCycleDao settleCycleDao,
                       AccountSubjectDao subjectDao) {
        this.tx = tx;
        this.visibility = visibility;
        this.config = config;
        this.hedgePlanDao = hedgePlanDao;
        this.hedgeDocDao = hedgeDocDao;
        this.hedgeRelationDao = hedgeRelationDao;
        this.assessDao = assessDao;
        this.rebalanceDao = rebalanceDao;
        this.exposureDao = exposureDao;
        this.voucherDao = voucherDao;
        this.voucherLineDao = voucherLineDao;
        this.voucherReviewDao = voucherReviewDao;
        this.periodDao = periodDao;
        this.periodCloseLogDao = periodCloseLogDao;
        this.settleCycleDao = settleCycleDao;
        this.subjectDao = subjectDao;
    }

    /**
     * 再平衡依赖注解视图（回执披露用）：再平衡写路径是否已装配齐（九参构造）。
     *
     * @return {@code true} = 两 DAO 均已注入
     */
    public boolean rebalanceSupported() {
        return rebalanceDao != null && exposureDao != null;
    }

    /**
     * 凭证依赖注解视图（回执披露用）：凭证/期间写路径是否已装配齐（十六参构造）。
     *
     * @return {@code true} = 凭证与期间七件依赖均已注入
     */
    public boolean voucherSupported() {
        return voucherDao != null && voucherLineDao != null && voucherReviewDao != null
                && periodDao != null && periodCloseLogDao != null
                && settleCycleDao != null && subjectDao != null;
    }

    /** 配置读取接缝（避免直接依赖 CONFIG 模块；FundService.ConfigReader 同形）。 */
    @FunctionalInterface
    public interface ConfigReader {
        /**
         * @param key 配置键
         * @return 配置值；不存在返回 {@code null}
         */
        String get(String key);
    }

    // ================================================================ 一、套期关系生成（FR-ACCT-1 / R-ACCT-1）

    /**
     * 生成套期关系（正式指定；{@code FR-ACCT-1}、{@code R-ACCT-1} 三要素门禁）。
     *
     * <p><b>三要素门禁</b>：绑定计划的<b>当前版本书面文件</b>（{@code t_hedge_doc.is_current='Y'}）
     * 须存在且三要素列（正式指定 / 有效性评估方法 / 再平衡机制）全部非空；缺任一要素 ⇒
     * {@code 10012} 阻断并列出缺失项（FR-ACCT-1 验收 3 同形态：缺再平衡机制配置时生成失败并列该项）。
     * 文档本体由 PLAN 模块生成，本方法<b>只读引用</b>（TB-PLAN-4 所有者声明）；
     * 生成动作 = 写 {@code t_hedge_relation} 正式指定行（{@code DESIGNATED}）。
     *
     * <p>文档 {@code pending_external_review='Y'} 恒成立（R-ACCT-10「待外部复核」标注，
     * 数据库 CHECK 承载不可移除）；本方法返回的套期关系经 {@code hedge_doc_id} 关联该标注，
     * 前端/导出侧据 {@code t_hedge_doc.pending_external_review} 呈现「待外部复核」。
     *
     * @param planId         套保计划（须存在）
     * @param exposureId     被套期项目（实货敞口标识）
     * @param instrumentType 套期工具类型（{@code FUTURES} / {@code OPTION}；期权与期货同等对待）
     * @param instrumentDesc 套期工具描述（合约与持仓标识）
     * @param operatorId     生成人
     * @return 生成的套期关系
     */
    public HedgeRelation generateHedgeRelation(long planId, long exposureId, String instrumentType,
                                               String instrumentDesc, long operatorId) {
        HedgePlan plan = hedgePlanDao.findById(planId);
        if (plan == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "套保计划不存在：id=" + planId);
        }
        visibility.assertSubAccountWritable(operatorId, requireSubOfPlan(plan));
        String type = requireEnum(instrumentType, "instrumentType", "FUTURES", "OPTION");
        if (isBlank(instrumentDesc)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期工具描述必填（合约与持仓标识）：planId=" + planId
                            + "；复现=补齐 hedging_instrument_desc");
        }

        // 三要素门禁（R-ACCT-1）：读计划当前版本书面文件，逐要素判空并列缺失项。
        HedgeDoc doc = hedgeDocDao.findCurrent(planId);
        List<String> missing = new ArrayList<>();
        if (doc == null) {
            missing.add("套期关系书面文件（计划未生成任何版本，先在 PLAN 模块生成）");
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
                    "三要素不齐备，不得生成套期关系（CAS 24 法定留痕，R-ACCT-1）：planId=" + planId,
                    "R-ACCT-1",
                    "缺失 " + missing.size() + " 项",
                    "三要素齐备（正式指定 + 有效性评估方法 + 再平衡机制）",
                    "缺失：" + String.join("、", missing)
                            + "；先在 PLAN 模块生成/补齐当前版本书面文件"
                            + "（FR-ACCT-1 验收 3：缺再平衡机制配置时生成失败并列该项）");
        }

        // 编号分配 + 并发兜底（DATA_MODEL.md §3.5）：UNIQUE 冲突 ⇒ 重新取号重试整笔（最多 3 次）。
        // 候选号在 attempt 内生成并写入 holder，冲突时供「占用判定」（仅编号冲突才重试，
        // 其他唯一约束冲突不误重试——FundService.retryApplyNo 同构）。
        String[] candidateNo = new String[1];
        return retryOnRelationNoConflict(() -> {
            HedgeRelation r = new HedgeRelation();
            r.subAccountId = plan.subAccountId;
            candidateNo[0] = nextRelationNo();
            r.relationNo = candidateNo[0];
            r.hedgePlanId = planId;
            r.hedgedItemExposureId = exposureId;
            r.hedgingInstrumentType = type;
            r.hedgingInstrumentDesc = instrumentDesc.trim();
            r.hedgeDocId = doc.id;
            r.status = REL_DESIGNATED;
            r.designatedTime = Clocks.now();
            if (hedgeRelationDao.insert(r, operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "套期关系写入失败：relationNo=" + r.relationNo);
            }
            return r;
        }, () -> candidateNo[0], no -> hedgeRelationDao.findByRelationNo(no) != null);
    }

    /**
     * 套期关系下一编号 = <b>全系统前缀 {@code HR} 内字典序最大 + 1</b>
     * （O-4 教训：{@code relation_no} 全系统唯一、无业务日重置前提，勿照搬「当日最大 +1」；
     * T-137/T-141 的形态是「当日最大 +1」且配合日期前缀天然隔离，此处前缀不含日期 ⇒ 取全系统 max）。
     *
     * <p>每次<b>重试</b>重新取号（放在 attempt 内而非闭包外缓存），使冲突后的下一轮
     * 取到并发方已提交的最大号。
     */
    private String nextRelationNo() {
        String max = hedgeRelationDao.findMaxRelationNoByPrefix(RELATION_NO_PREFIX);
        long next = 1L;
        if (max != null && max.startsWith(RELATION_NO_PREFIX)
                && max.length() > RELATION_NO_PREFIX.length()) {
            String seqText = max.substring(RELATION_NO_PREFIX.length());
            next = Long.parseLong(seqText) + 1L;
        }
        StringBuilder sb = new StringBuilder(RELATION_NO_PREFIX);
        String seq = Long.toString(next);
        for (int i = seq.length(); i < RELATION_NO_SEQ_WIDTH; i++) {
            sb.append('0');
        }
        return sb.append(seq).toString();
    }

    // ================================================================ 二、有效性评估（FR-ACCT-2 / FR-ACCT-4 / R-ACCT-2）

    /**
     * 提交有效性评估（{@code FR-ACCT-2}/{@code FR-ACCT-4}；定性三项观察点）。
     *
     * <p><b>判定规则（R-ACCT-2，Q-7）</b>：三项观察点全部 'Y' ⇒ {@code EFFECTIVE}；
     * 任一 'N' ⇒ {@code INEFFECTIVE}（并标注"需终止或再平衡"，不得自动修改会计处理）。
     * <b>量化监测不参与判定</b>：{@code monitorDeviationRatio} 仅落库，超阈值
     * （CFG-07b，缺省 0.50）置 {@code monitor_review_flag='Y'}（提示复核），结论不因此改变
     * （FR-ACCT-4 验收 5）。
     *
     * <p><b>缺失拒绝</b>（FR-ACCT-4 验收 4）：观察点①②取值缺失、观察点③判定基准
     * （敞口预计实现日）缺失、近相关（①='N'）未附书面经济关系说明 ⇒ 拒绝并列出缺失项。
     *
     * <p><b>幂等</b>：同套期关系同期间已有未更正评估 ⇒ 拒绝重复评估（10010；
     * {@code uk_eff_assess_rel_period} 数据库兜底）。评估结论落库后同步套期关系状态机
     * （有效 → {@code EFFECTIVE}；无效 → {@code NEED_REBALANCE}，再平衡执行归 T-078）。
     *
     * @param req        评估请求
     * @param operatorId 评估人
     * @return 落库的评估记录
     */
    public EffectivenessAssess assessEffectiveness(AssessRequest req, long operatorId) {
        // TE-T077-001：insert + 状态机 update 两段写整笔事务包裹（状态机失败须回滚插入，
        // 不得留失真留痕；FundService 同族形态）
        return tx.inTransaction(conn -> doAssess(req, operatorId, null));
    }

    /**
     * 更正评估记录（{@code FR-ACCT-2} 验收 3：不存在编辑入口，更正 = 新增记录并留痕）。
     *
     * <p>形态：以 {@code correctedFromId} 引用原记录<b>新增</b>一条（走 {@link #assessEffectiveness}
     * 同一判定管线），并把原记录 {@code is_corrected} 置 'Y'（使
     * {@code findActiveByRelationAndPeriod} 指向新记录）。
     *
     * <p><b>专用通道披露（诚实）</b>：置 {@code is_corrected='Y'} 须一次 UPDATE——
     * {@code DmEffectivenessAssessDao.updateById} 对<b>整行更新</b>显式拒绝（T-076 交付，
     * 防「借编辑入口静默改写判定结果」）；本方法以 DAO <b>专用标记方法</b>
     * {@link EffectivenessAssessDao#markCorrected(long, long)} 承载（仅 SET {@code is_corrected}，
     * 其余列不可触碰）——评估的业务字段仍不可改，「不可编辑」红线指<b>业务字段</b>而非行级
     * 标记位。该标记方法系本单新增（T-076 交付面之上的最小增量）。
     *
     * <p><b>时序</b>：先标记原记录、再插入新记录——{@code uk_eff_assess_rel_period} 的第三键为
     * {@code is_corrected}，不先释放唯一槽位新记录必撞约束（同期间同关系只允许一条未更正记录）。
     * 两段写（标记 + 新增）整笔 {@code tx.inTransaction} 包裹（TE-T077-001：校验拒绝须整笔回滚，
     * 不得留 {@code is_corrected='Y'} 的失真留痕；FundService 同族事务形态）。
     *
     * @param originalId 被更正的原记录
     * @param req        更正后的评估请求（重新走完整判定）
     * @param operatorId 更正人
     * @return 新评估记录（{@code correctedFromId} = 原记录）
     */
    public EffectivenessAssess correctAssessment(long originalId, AssessRequest req, long operatorId) {
        // TE-T077-001：markCorrected + doAssess 新记录 = 整笔事务（复核探针实证：更正请求
        // 校验拒绝后原记录 is_corrected 已='Y' 且无更正记录 ⇒ 留痕链失真）；拒绝路径
        // （记录不存在 / 已更正 / 可见性 / 新请求校验）在事务开启前后均整笔回滚。
        return tx.inTransaction(conn -> {
            EffectivenessAssess original = assessDao.findById(originalId);
            if (original == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "被更正的评估记录不存在：id=" + originalId);
            }
            if ("Y".equals(original.isCorrected)) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "该评估记录已被更正（不得二次更正同一记录）：id=" + originalId
                                + "；复现=以最新记录为更正来源");
            }
            visibility.assertSubAccountWritable(operatorId, requireSubOfAssess(original));
            if (assessDao.markCorrected(originalId, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "原记录标记已更正失败（并发冲突）：id=" + originalId + "；复现=重新读取后再更正");
            }
            return doAssess(req, operatorId, originalId);
        });
    }

    /** 评估落库主体（{@code correctedFromId} 非空 = 更正链新增）。 */
    private EffectivenessAssess doAssess(AssessRequest req, long operatorId, Long correctedFromId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "评估请求不得为空");
        }
        if (req.hedgeRelationId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "hedgeRelationId 必填");
        }
        if (isBlank(req.periodKey)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "periodKey 必填（会计期间，如 2026-09）");
        }
        HedgeRelation relation = hedgeRelationDao.findById(req.hedgeRelationId);
        if (relation == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系不存在：id=" + req.hedgeRelationId);
        }
        visibility.assertSubAccountWritable(operatorId, requireSubOfRelation(relation));
        if (REL_TERMINATED.equals(relation.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "套期关系已终止，不得再评估（不得再按套期会计处理）：relationNo="
                            + relation.relationNo);
        }
        if (correctedFromId == null
                && assessDao.findActiveByRelationAndPeriod(req.hedgeRelationId, req.periodKey.trim()) != null) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "该套期关系该期间已有未更正评估记录（uk_eff_assess_rel_period）：relationId="
                            + req.hedgeRelationId + " periodKey=" + req.periodKey.trim()
                            + "；复现=走更正（correctAssessment）而非重复评估");
        }

        // 观察点缺失拒绝（FR-ACCT-4 验收 4）：并列缺失项，不得静默按不满足处理。
        List<String> missing = new ArrayList<>();
        String linkage = requireFlag(req.observerLinkage, "observerLinkage（品种相关性）", missing);
        String direction = requireFlag(req.observerDirection, "observerDirection（方向一致性）", missing);
        String term = requireFlag(req.observerTerm, "observerTerm（期限对应性）", missing);
        if (req.exposureExpectedDate == null) {
            missing.add("exposureExpectedDate（敞口预计实现日：期限对应性判定基准）");
        }
        if (!missing.isEmpty()) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "观察点数据缺失，评估被拒（FR-ACCT-4 验收 4）：relationId=" + req.hedgeRelationId
                            + "；缺失 " + missing.size() + " 项：" + String.join("、", missing)
                            + "；复现=补齐观察点判定数据后重新提交");
        }
        if ("N".equals(linkage) && isBlank(req.linkageNote)) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "观察点①取 'N'（近相关）须附书面经济关系说明（Q-7）：relationId="
                            + req.hedgeRelationId + "；缺失：linkageNote"
                            + "；复现=补齐 linkageNote 后重新提交");
        }

        // 期限偏离天数留痕（结论可复现，FR-ACCT-4 验收 3）；观察点③='Y' 须与判定数据一致
        // （偏离 ≤ 90 天 = ±3 个月）；矛盾属数据错误而非判定失败，拒绝提交。
        int termDeviationDays = (int) Math.abs(ChronoUnit.DAYS.between(Clocks.today(),
                req.exposureExpectedDate));
        if ("Y".equals(term) && termDeviationDays > TERM_TOLERANCE_DAYS) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "观察点③取 'Y' 与判定数据矛盾：期限偏离 " + termDeviationDays + " 天 > 容忍 "
                            + TERM_TOLERANCE_DAYS + " 天（±3 个月）；relationId=" + req.hedgeRelationId
                            + "；复现=按实际偏离把观察点③改 'N'，或修正 exposureExpectedDate");
        }

        // 三项均满足即有效（R-ACCT-2）；量化监测仅落库提示、不参与本判定（Q-7 / FR-ACCT-4 验收 5）。
        boolean allSatisfied = "Y".equals(linkage) && "Y".equals(direction) && "Y".equals(term);
        String conclusion = allSatisfied ? CONCLUSION_EFFECTIVE : CONCLUSION_INEFFECTIVE;

        // 量化监测（CFG-07b）：偏离 1 的绝对值超阈值 ⇒ 提示复核（仅监测与再平衡触发）。
        BigDecimal threshold = monitorThreshold();
        BigDecimal deviation = req.monitorDeviationRatio;
        boolean flagged = deviation != null && deviation.abs().compareTo(threshold) > 0;

        EffectivenessAssess ea = new EffectivenessAssess();
        ea.subAccountId = relation.subAccountId;
        ea.hedgeRelationId = req.hedgeRelationId;
        ea.periodKey = req.periodKey.trim();
        ea.assessMethod = METHOD_QUALITATIVE;
        ea.observerLinkage = linkage;
        ea.linkageNote = "N".equals(linkage) ? req.linkageNote.trim() : null;
        ea.observerDirection = direction;
        ea.observerTerm = term;
        ea.exposureExpectedDate = req.exposureExpectedDate;
        ea.termDeviationDays = termDeviationDays;
        ea.monitorDeviationRatio = deviation;
        ea.monitorReviewFlag = flagged ? "Y" : "N";
        ea.observerDetail = buildObserverDetail(relation, linkage, direction, term,
                req.exposureExpectedDate, termDeviationDays, conclusion, deviation, threshold, flagged);
        ea.conclusion = conclusion;
        ea.needActionFlag = allSatisfied ? "N" : "Y";
        ea.actionNote = allSatisfied ? null
                : "需终止或再平衡（结论无效；不得自动修改会计处理，FR-ACCT-2 验收 2）";
        ea.assessorId = operatorId;
        ea.assessTime = Clocks.now();
        ea.correctedFromId = correctedFromId;
        ea.isCorrected = "N";
        if (assessDao.insert(ea, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "评估记录写入失败：relationId=" + req.hedgeRelationId);
        }

        // 套期关系状态机（ACCT.md §2.1）：有效 → EFFECTIVE；无效 → NEED_REBALANCE。
        String nextStatus = allSatisfied ? REL_EFFECTIVE : REL_NEED_REBALANCE;
        if (!nextStatus.equals(relation.status)) {
            relation.status = nextStatus;
            if (hedgeRelationDao.updateById(relation, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "套期关系并发冲突：relationNo=" + relation.relationNo
                                + "；复现=重新读取后再提交");
            }
        }
        if (flagged) {
            LOG.warning("有效性量化监测超阈值（仅提示复核，不影响结论，Q-7）：relationNo="
                    + relation.relationNo + " periodKey=" + ea.periodKey
                    + " 偏离=" + deviation.toPlainString() + " 阈值=" + threshold.toPlainString()
                    + "（CFG-07b）");
        }
        return ea;
    }

    // ================================================================ 四、读取面（列表 / 未评估提醒）

    /**
     * 分页查询套期关系（{@code GET /acct/hedge-relation}）。
     *
     * <p>子账户可见集过滤（NFR-SEC-7；{@code subAccountId} 指定时先做读面断言）。
     *
     * @param query        分页
     * @param subAccountId 子账户（可空）
     * @param status       状态筛选（可空；取值域 = 套期关系 5 态）
     * @param operatorId   调用者
     * @return 分页结果
     */
    public PageResult<HedgeRelation> listHedgeRelations(PageQuery query, Integer subAccountId,
                                                        String status, long operatorId) {
        if (status != null) {
            requireRelStatus(status);
        }
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            conds.add("sub_account_id = ?");
            args.add(subAccountId);
        }
        if (status != null) {
            conds.add("status = ?");
            args.add(status);
        }
        applyScope(conds::add, args::add, operatorId);
        return hedgeRelationDao.findPage(query,
                conds.isEmpty() ? null : String.join(" AND ", conds), args);
    }

    /**
     * 分页查询有效性评估记录（{@code GET /acct/effectiveness}）。
     *
     * @param query        分页
     * @param subAccountId 子账户（可空）
     * @param periodKey    会计期间（可空）
     * @param conclusion   结论筛选（可空；EFFECTIVE / INEFFECTIVE / NEED_REBALANCE）
     * @param monitorOnly  {@code true}=仅监测超阈值记录（提示复核清单）
     * @param operatorId   调用者
     * @return 分页结果
     */
    public PageResult<EffectivenessAssess> listAssessments(PageQuery query, Integer subAccountId,
                                                           String periodKey, String conclusion,
                                                           boolean monitorOnly, long operatorId) {
        if (conclusion != null) {
            requireConclusion(conclusion);
        }
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            conds.add("sub_account_id = ?");
            args.add(subAccountId);
        }
        if (periodKey != null) {
            conds.add("period_key = ?");
            args.add(periodKey);
        }
        if (conclusion != null) {
            conds.add("conclusion = ?");
            args.add(conclusion);
        }
        if (monitorOnly) {
            conds.add("monitor_review_flag = ?");
            args.add("Y");
        }
        applyScope(conds::add, args::add, operatorId);
        return assessDao.findPage(query,
                conds.isEmpty() ? null : String.join(" AND ", conds), args);
    }

    /**
     * 某期间未评估的套期关系清单（{@code FR-ACCT-2} 验收 4「系统提醒」的取数入口）。
     *
     * <p>口径：调用者可见范围内、状态非「已终止」、且在该期间<b>无未更正评估记录</b>的套期关系。
     * 提醒形态 = 本清单（handler 侧经信封返回，供页面呈现「本期未评估」提醒）。
     *
     * @param periodKey  会计期间（如 2026-09）
     * @param operatorId 调用者
     * @return 未评估的套期关系列表（{@code designated_time} 升序）
     */
    public List<HedgeRelation> unassessedRelations(String periodKey, long operatorId) {
        if (isBlank(periodKey)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "periodKey 必填（会计期间，如 2026-09）");
        }
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        // 哨兵语义与 applyScope 同源：null/空集按空集处理（fail-closed）；
        // 含哨兵 0 = 跨账户只读 ⇒ 查全部（传 null）。
        List<Integer> subs = (visible == null || visible.isEmpty())
                ? List.of(-1)
                : (visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL) ? null : visible);
        List<HedgeRelation> out = new ArrayList<>();
        for (HedgeRelation r : hedgeRelationDao.findBySubAccounts(subs)) {
            if (!REL_TERMINATED.equals(r.status)
                    && assessDao.findActiveByRelationAndPeriod(r.id, periodKey.trim()) == null) {
                out.add(r);
            }
        }
        return out;
    }

    // ================================================================ 三、再平衡（FR-ACCT-3 / R-ACCT-3 / Q-8 四类触发）

    /**
     * 执行再平衡（{@code FR-ACCT-3}；状态机「需再平衡 ──再平衡完成──→ 已重新指定」，
     * {@code ACCT.md} §2.1）。
     *
     * <p><b>形态判断（手动发起）</b>：FR-ACCT-3 输入列 =「套期关系标识、触发原因、
     * 调整前后套保比率与数量、<b>执行人</b>」——执行人语义只在手动发起形态存在（自动侦测无
     * 「执行人」输入位）；故四类触发场景 = <b>操作人选定场景提交，系统按数据面复核偏离值</b>：
     * ① 敞口变化与 ② 比率偏离由本方法按 {@code t_exposure} 现值 / 计划当前比率<b>实时计算</b>，
     * 不采信客户端声明的偏离值（防伪造触发）；③ 计划修订/终止与 ④ 敞口消失由数据面状态复核
     * （计划 {@code TERMINATED} / 敞口行消失），不采信客户端声明。任一场景复核通过即触发
     * （Q-8「满足任一即触发评估」）。<b>全部场景均不满足 ⇒ 拒绝生成并给出当前偏离值</b>
     * （FR-ACCT-3 验收 4；{@code 10012} 阻断四项载荷，{@code current}=当前偏离值、
     * {@code threshold}=CFG-08 阈值快照）。
     *
     * <p><b>触发条件两侧齐设（Q-8）</b>：敞口侧（CFG-08 exposure_delta_pct）与工具侧
     * （CFG-08 ratio_deviation_pct）两项条件在本方法<b>恒参与评估</b>（①② 场景须各自复核；
     * 两侧任一命中即触发），加上计划/敞口状态场景 ③④ —— 两侧各至少一项的配置约束由此落实，
     * 不依赖运行时配置增删。
     *
     * <p><b>再平衡须重新指定（R-ACCT-3，不得只改数字）</b>：成功路径 = 四段写——
     * ① 新版书面文件（复用 {@code generateHedgeRelation} 的取数形态读当前版本文档、
     * 以 v+1 版本再生成，三要素沿用当前版本文档内容并附加再平衡留痕，三要素门禁 R-ACCT-1
     * 在此同样成立：当前版本文档缺任一要素 ⇒ {@code 10012} 阻断并列缺失项，重新指定失败；
     * 同段内同步回写 {@code plan.current_doc_version}——TE-T078-001：该列与文档版本为
     * 同一事务面状态对，漏回写致 PLAN 侧再生成版本推导撞 {@code uk_hedge_doc_no}）；
     * ② 新套期关系行（{@code REDESIGNATED} 状态，关联新文档；编号全系统前缀 {@code HR}
     * max+1，O-4 教训）；③ 再平衡记录（四要素：触发类型/侧/原因/量化判定值 + 前后比率与
     * 数量 + 执行人与时间 + 新文档/新关系引用）。旧关系状态置 {@code REDESIGNATED}
     * （原行保留，历史可查——评估记录/凭证经 {@code hedge_relation_id} 追溯）。
     * <b>整笔 {@code tx.inTransaction} 包裹</b>（T-077 同族纪律：任一段失败整笔回滚，
     * 不得留半笔再平衡留痕）。
     *
     * <p><b>调整后取值</b>：{@code ratioAfter/qtyAfter} 为执行人申报的调整目标
     * （比率 ∈ (0,1]，数量 &gt; 0——ck_hedge_plan_ratio 同域）；敞口消失场景
     * （TRIGGER_EXPOSURE_GONE）语义为「终止重指定」，调整后数量 = 0（无敞口可套保）。
     *
     * <p><b>PLAN 反馈</b>：再平衡结论落 {@code plan_feedback_flag}（执行人声明是否需调整计划，
     * R-ACCT-3「结论须反馈至 PLAN」的最小承载；计划本体的修订走 PLAN 模块既有流程，
     * 不在本单写域）。
     *
     * @param req        再平衡请求（触发场景 + 调整前后值 + PLAN 反馈声明）
     * @param operatorId 执行人（会话身份，不接受请求体传入）
     * @return 落库的再平衡记录（含新文档 / 新关系引用）
     */
    public RebalanceRecord rebalance(RebalanceRequest req, long operatorId) {
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "再平衡请求不得为空");
        }
        if (!rebalanceSupported()) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "再平衡依赖未装配（RebalanceRecordDao/ExposureDao 缺失）；"
                            + "复现=以全参构造装配 AcctService 后重试");
        }
        return tx.inTransaction(conn -> doRebalance(req, operatorId));
    }

    private RebalanceRecord doRebalance(RebalanceRequest req, long operatorId) {
        if (req.hedgeRelationId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "hedgeRelationId 必填");
        }
        HedgeRelation relation = hedgeRelationDao.findById(req.hedgeRelationId);
        if (relation == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系不存在：id=" + req.hedgeRelationId);
        }
        visibility.assertSubAccountWritable(operatorId, requireSubOfRelation(relation));
        if (REL_TERMINATED.equals(relation.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "套期关系已终止，不得再平衡（终态；ACCT.md §2.1 状态机）：relationNo="
                            + relation.relationNo);
        }
        if (REL_REDESIGNATED.equals(relation.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "该套期关系已完成再平衡（已重新指定），请对新关系发起：relationNo="
                            + relation.relationNo + "；复现=经 t_rebalance_record."
                            + "new_hedge_relation_id 定位新关系行");
        }
        requireRelStatus(relation.status);   // 复用 5 态取值域校验（防脏状态静默通过）
        if (isBlank(req.triggerReason)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "triggerReason 必填（触发原因描述）：relationNo=" + relation.relationNo);
        }
        String scenario = req.triggerScenario == null ? "" : req.triggerScenario.trim();
        if (!TRIGGER_EXPOSURE_DELTA.equals(scenario) && !TRIGGER_RATIO_DEVIATION.equals(scenario)
                && !TRIGGER_PLAN_REVISED.equals(scenario) && !TRIGGER_EXPOSURE_GONE.equals(scenario)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "triggerScenario 取值非法（四类触发，Q-8）：" + scenario
                            + "；复现=使用 EXPOSURE_DELTA/RATIO_DEVIATION/PLAN_REVISED"
                            + "/EXPOSURE_GONE 之一");
        }
        BigDecimal exposureThreshold = exposureDeltaThreshold();
        BigDecimal ratioThreshold = ratioDeviationThreshold();

        // 数据面复核（不采信客户端偏离值）：四类场景逐一求值，收集各自判定证据。
        Evaluation eval = evaluateTriggers(relation, scenario, exposureThreshold, ratioThreshold);

        // 触发条件不满足 ⇒ 拒绝生成并给出当前偏离值（FR-ACCT-3 验收 4；10012 四项载荷）
        if (!eval.triggered) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "再平衡触发条件不满足，拒绝生成记录（FR-ACCT-3 验收 4）：relationNo="
                            + relation.relationNo + " 场景=" + scenario,
                    "R-ACCT-3",
                    eval.currentValue,
                    eval.thresholdValue,
                    "当前偏离值未达阈值（敞口变化 " + eval.exposureDelta.toPlainString()
                            + " / 阈值 " + exposureThreshold.toPlainString()
                            + "；比率偏离 " + eval.ratioDeviation.toPlainString()
                            + " / 阈值 " + ratioThreshold.toPlainString()
                            + "；计划状态=" + eval.planStatus + "；敞口存在=" + eval.exposureExists
                            + "）；复现=确认偏离数据后重新发起，或走有效性评估（结论无效 ⇒ 需再平衡）");
        }

        // 调整后取值校验（申报目标；敞口消失=终止重指定，数量恒 0）
        BigDecimal qtyAfter;
        if (TRIGGER_EXPOSURE_GONE.equals(scenario)) {
            if (req.qtyAfter != null && req.qtyAfter.compareTo(BigDecimal.ZERO) != 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "敞口消失场景（终止重指定）调整后数量须为 0（无敞口可套保）：qtyAfter="
                                + req.qtyAfter.toPlainString());
            }
            qtyAfter = BigDecimal.ZERO;
        } else {
            if (req.qtyAfter == null || req.qtyAfter.compareTo(BigDecimal.ZERO) <= 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "qtyAfter 须 > 0（调整后数量；敞口消失场景除外）：relationNo="
                                + relation.relationNo);
            }
            qtyAfter = req.qtyAfter;
        }
        if (req.ratioAfter == null
                || req.ratioAfter.compareTo(BigDecimal.ZERO) <= 0
                || req.ratioAfter.compareTo(BigDecimal.ONE) > 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "ratioAfter 须 ∈ (0,1]（ck_hedge_plan_ratio 同域）：relationNo="
                            + relation.relationNo + " 值="
                            + (req.ratioAfter == null ? "null" : req.ratioAfter.toPlainString()));
        }
        BigDecimal ratioBefore = ratioOf(relation);

        // ① 新版书面文件（R-ACCT-3：不得只改数字）：v+1 版本再生成，三要素沿用当前版本
        // 并附加再平衡留痕；当前版本文档缺任一要素 ⇒ 10012 阻断（R-ACCT-1 门禁在重新指定同样成立）。
        HedgeDoc newDoc = regenerateHedgeDoc(relation, req, operatorId);

        // ② 新套期关系行（REDESIGNATED；编号 O-4 全系统前缀 max+1 + 唯一约束重试）
        HedgeRelation newRelation = insertRedesignatedRelation(relation, newDoc, req,
                ratioAfter(req), qtyAfter, operatorId);

        // ③ 再平衡记录（不得事后修改，R-ACCT-3：DAO updateById 显式拒绝）
        RebalanceRecord record = new RebalanceRecord();
        record.subAccountId = relation.subAccountId;
        record.hedgeRelationId = relation.id;
        record.rebalanceNo = nextRebalanceNo();
        record.triggerType = scenario;
        record.triggerSide = eval.triggerSide;
        record.triggerReason = req.triggerReason.trim();
        record.triggerExposureDelta = scale9(eval.exposureDelta);
        record.triggerRatioDeviation = scale9(eval.ratioDeviation);
        record.triggerThreshold = scale9(TRIGGER_EXPOSURE_DELTA.equals(scenario)
                || TRIGGER_PLAN_REVISED.equals(scenario) || TRIGGER_EXPOSURE_GONE.equals(scenario)
                ? exposureThreshold : ratioThreshold);
        record.ratioBefore = scale9(ratioBefore);
        record.ratioAfter = scale9(req.ratioAfter);
        record.qtyBefore = scale4(hedgeQtyOf(relation));
        record.qtyAfter = scale4(qtyAfter);
        record.newHedgeDocId = newDoc.id;
        record.newHedgeRelationId = newRelation.id;
        record.executorId = operatorId;
        record.rebalanceTime = Clocks.now();
        record.planFeedbackFlag = "Y".equalsIgnoreCase(req.planFeedbackRequired) ? "Y" : "N";
        if (rebalanceDao.insert(record, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "再平衡记录写入失败：relationNo=" + relation.relationNo);
        }

        // ④ 旧关系状态机：需再平衡 ──再平衡完成──→ 已重新指定（原行保留，历史可查）
        relation.status = REL_REDESIGNATED;
        if (hedgeRelationDao.updateById(relation, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "套期关系并发冲突：relationNo=" + relation.relationNo
                            + "；复现=重新读取后再提交");
        }
        LOG.warning("再平衡完成（R-ACCT-3 重新指定留痕）：rebalanceNo=" + record.rebalanceNo
                + " relationNo=" + relation.relationNo + " → " + newRelation.relationNo
                + " trigger=" + record.triggerType + "/" + record.triggerSide
                + " planFeedback=" + record.planFeedbackFlag);
        return record;
    }

    /** 四类触发场景的数据面复核（不采信客户端偏离值；证据落 eval）。 */
    private Evaluation evaluateTriggers(HedgeRelation relation, String scenario,
                                        BigDecimal exposureThreshold, BigDecimal ratioThreshold) {
        Evaluation eval = new Evaluation();
        HedgePlan plan = hedgePlanDao.findById(relation.hedgePlanId);
        eval.planStatus = plan == null ? "null" : String.valueOf(plan.status);
        Exposure exposure = exposureDao.findById(relation.hedgedItemExposureId);
        eval.exposureExists = exposure != null;

        // ① 敞口变化（敞口侧）：敞口现值 vs 计划立项时的套保基数——基数取计划数量
        // planQty（与敞口同口径，R-PLAN-3/⑤ 立项时按敞口未覆盖量校准；敞口快照历史量
        // 不落 t_hedge_relation，计划基数是关系行可稳定引用的最小可比基准——回执披露该口径）。
        // 变化幅度 = |现值 − 基数| / 基数（基数为 0 ⇒ 视为敞口消失场景 ④）。
        BigDecimal baseQty = plan == null ? BigDecimal.ZERO : nz(plan.planQty);
        BigDecimal exposureQty = exposure == null ? BigDecimal.ZERO : nz(exposure.exposureQty);
        if (baseQty.signum() > 0) {
            eval.exposureDelta = exposureQty.subtract(baseQty).abs()
                    .divide(baseQty, 6, java.math.RoundingMode.HALF_UP);
        } else {
            eval.exposureDelta = exposureQty.signum() == 0
                    ? BigDecimal.ZERO : BigDecimal.ONE;
        }

        // ② 套期比率偏离（工具侧）：实际覆盖比率 = 计划套保数量 / 敞口现值，对计划申报
        // 比率求偏离。敞口现值为 0 ⇒ 偏离取 1（完全偏离，实质即敞口消失）。
        BigDecimal hedgeQty = plan == null ? BigDecimal.ZERO : nz(plan.planQty);
        if (exposureQty.signum() > 0) {
            BigDecimal actualRatio = hedgeQty.divide(exposureQty, 6, java.math.RoundingMode.HALF_UP);
            BigDecimal declaredRatio = ratioOf(relation);
            eval.ratioDeviation = actualRatio.subtract(declaredRatio).abs()
                    .divide(declaredRatio.max(new BigDecimal("0.000001")), 6,
                            java.math.RoundingMode.HALF_UP);
        } else {
            eval.ratioDeviation = BigDecimal.ONE;
        }

        // ③ 计划数量被修订 / 终止（敞口侧）：数据面复核 = 计划状态 TERMINATED，或已修订
        // （t_plan_state_log 留痕由 PLAN 模块承载；本单以「敞口现值 ≠ 计划基数」作为修订
        // 的间接复核——敞口变化场景 ① 的判定值即其证据，两侧共用数据面计算，不另设真源）。
        boolean planRevised = plan != null && "TERMINATED".equals(plan.status);

        // ④ 敞口消失（敞口侧）：绑定敞口行不存在，或现值为 0 且状态非 CLOSED 的已清空形态。
        boolean exposureGone = !eval.exposureExists
                || exposure.exposureQty.signum() == 0;

        // 场景逐一判定（Q-8：满足任一即触发）；命中场景的侧别 = 记录 trigger_side 的来源。
        boolean hit = false;
        switch (scenario) {
            case TRIGGER_EXPOSURE_DELTA:
                hit = eval.exposureDelta.compareTo(exposureThreshold) > 0;
                eval.triggerSide = SIDE_EXPOSURE;
                eval.currentValue = "exposureDelta=" + eval.exposureDelta.toPlainString();
                eval.thresholdValue = exposureThreshold.toPlainString();
                break;
            case TRIGGER_RATIO_DEVIATION:
                hit = eval.ratioDeviation.compareTo(ratioThreshold) > 0;
                eval.triggerSide = SIDE_INSTRUMENT;
                eval.currentValue = "ratioDeviation=" + eval.ratioDeviation.toPlainString();
                eval.thresholdValue = ratioThreshold.toPlainString();
                break;
            case TRIGGER_PLAN_REVISED:
                hit = planRevised;
                eval.triggerSide = SIDE_EXPOSURE;
                eval.currentValue = "planStatus=" + eval.planStatus;
                eval.thresholdValue = "TERMINATED";
                break;
            case TRIGGER_EXPOSURE_GONE:
                hit = exposureGone;
                eval.triggerSide = SIDE_EXPOSURE;
                eval.currentValue = "exposureExists=" + eval.exposureExists
                        + " exposureQty=" + exposureQty.toPlainString();
                eval.thresholdValue = "0";
                break;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "triggerScenario 取值非法：" + scenario);
        }
        eval.triggered = hit;
        return eval;
    }

    /**
     * 新版书面文件再生成（R-ACCT-3「重新生成书面文件，不得只改数字」；PLAN 所有、ACCT 只读
     * 引用的所有权边界不变——此处是<b>再平衡触发的重新生成</b>，写 {@code t_hedge_doc} 新版本行，
     * 与 {@code PlanService.generateHedgeDoc} 的 PLAN 主动再生成同表同形态）。
     *
     * <p>三要素门禁（R-ACCT-1）在重新指定同样成立：当前版本文档缺任一要素（不存在 / 空串）
     * ⇒ {@code 10012} 阻断并列缺失项（重新指定失败，整笔回滚）——要素变更致缺项时不静默降级。
     *
     * @return 新版本文档行（{@code is_current='Y'}；旧版本置 N 并留失效时间，历史可查）
     */
    private HedgeDoc regenerateHedgeDoc(HedgeRelation relation, RebalanceRequest req,
                                        long operatorId) {
        HedgeDoc current = hedgeDocDao.findCurrent(relation.hedgePlanId);
        List<String> missing = new ArrayList<>();
        if (current == null) {
            missing.add("套期关系书面文件（计划未生成任何版本）");
        } else {
            if (isBlank(current.elemFormalDesignation)) {
                missing.add("要素①正式指定");
            }
            if (isBlank(current.elemEffectivenessMethod)) {
                missing.add("要素②有效性评估方法");
            }
            if (isBlank(current.elemRebalanceMechanism)) {
                missing.add("要素③再平衡机制");
            }
        }
        if (!missing.isEmpty()) {
            throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                    "三要素不齐备，不得重新指定（R-ACCT-3 重新生成书面文件前提）：relationNo="
                            + relation.relationNo,
                    "R-ACCT-1",
                    "缺失 " + missing.size() + " 项",
                    "三要素齐备（正式指定 + 有效性评估方法 + 再平衡机制）",
                    "缺失：" + String.join("、", missing)
                            + "；先在 PLAN 模块补齐当前版本书面文件后再执行再平衡"
                            + "（计划要素变更致缺项 ⇒ 重新指定被拒，不静默降级）");
        }
        int version = (current.docVersion == null ? 1 : current.docVersion) + 1;
        HedgePlan plan = hedgePlanDao.findById(relation.hedgePlanId);
        if (plan == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "绑定计划不存在：id=" + relation.hedgePlanId);
        }
        // 旧版本置 N（同事务；PlanService.generateHedgeDoc 同形态）
        current.isCurrent = "N";
        current.invalidatedTime = Clocks.now();
        if (hedgeDocDao.updateById(current, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "书面文件并发冲突：docNo=" + current.docNo + "；复现=重新读取后再提交");
        }
        HedgeDoc doc = new HedgeDoc();
        doc.subAccountId = relation.subAccountId;
        doc.hedgePlanId = relation.hedgePlanId;
        doc.docNo = BizNoGenerator.hedgeDoc(plan.planNo, version);
        doc.docVersion = version;
        doc.isCurrent = "Y";
        // 三要素沿用当前版本 + 再平衡留痕（重新生成 ≠ 空白重写；评估方法/机制为 CAS 24 法定内容）
        doc.elemFormalDesignation = current.elemFormalDesignation
                + "【再平衡重新指定 " + Clocks.now() + "】";
        doc.elemEffectivenessMethod = current.elemEffectivenessMethod;
        doc.elemRebalanceMechanism = current.elemRebalanceMechanism;
        doc.hedgedItemExposureId = relation.hedgedItemExposureId;
        doc.hedgingInstrument = current.hedgingInstrument;
        doc.pendingExternalReview = "Y";   // R-ACCT-10：恒 'Y'（DB CHECK 承载）
        doc.generatedBy = operatorId;
        doc.generatedTime = Clocks.now();
        if (hedgeDocDao.insert(doc, operatorId) != 1) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "书面文件编号冲突（uk_hedge_doc_no）：docNo=" + doc.docNo + "；复现=重试");
        }
        // TE-T078-001：current_doc_version 与文档版本为同一事务面状态对——本事务内同步回写
        // （同笔提交同笔回滚），否则 PLAN 侧 generateHedgeDoc 以 plan.currentDocVersion + 1
        // 推导下一版本将命中已占用版本号 ⇒ uk_hedge_doc_no 冲突，PLAN 侧再生成永久失败。
        if (hedgePlanDao.updateCurrentDocVersion(relation.hedgePlanId, version, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "计划当前文档版本回写失败（并发删除？）：planId=" + relation.hedgePlanId
                            + " version=" + version);
        }
        return doc;
    }

    /** 新套期关系行（REDESIGNATED；编号同 O-4 全系统 max+1 + 唯一约束 3 次重试）。 */
    private HedgeRelation insertRedesignatedRelation(HedgeRelation relation, HedgeDoc newDoc,
                                                     RebalanceRequest req, BigDecimal ratioAfter,
                                                     BigDecimal qtyAfter, long operatorId) {
        String[] candidateNo = new String[1];
        return retryOnRelationNoConflict(() -> {
            HedgeRelation r = new HedgeRelation();
            r.subAccountId = relation.subAccountId;
            candidateNo[0] = nextRelationNo();
            r.relationNo = candidateNo[0];
            r.hedgePlanId = relation.hedgePlanId;
            r.hedgedItemExposureId = relation.hedgedItemExposureId;
            r.hedgingInstrumentType = relation.hedgingInstrumentType;
            r.hedgingInstrumentDesc = relation.hedgingInstrumentDesc;
            r.hedgeDocId = newDoc.id;
            r.status = REL_REDESIGNATED;
            r.designatedTime = Clocks.now();
            if (hedgeRelationDao.insert(r, operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "重新指定套期关系写入失败：relationNo=" + r.relationNo);
            }
            return r;
        }, () -> candidateNo[0], no -> hedgeRelationDao.findByRelationNo(no) != null);
    }

    /**
     * 再平衡编号 = <b>全系统前缀 {@code RB} 内字典序最大 + 1</b>（{@code rebalance_no} 全系统唯一
     * {@code uk_rebalance_no}；{@code DATA_MODEL.md} §3.5 编号表<b>未列</b>该编号 ⇒ 前缀形态
     * 本服务定义并在回执披露——与 {@code HR} 同法：无日期段、无业务日重置前提，勿照搬
     * 「当日最大 +1」；T-137/T-141 判例前提不成立）。序号 4 位零填充。
     */
    private String nextRebalanceNo() {
        String max = rebalanceDao.findMaxRebalanceNoByPrefix(REBALANCE_NO_PREFIX);
        long next = 1L;
        if (max != null && max.startsWith(REBALANCE_NO_PREFIX)
                && max.length() > REBALANCE_NO_PREFIX.length()) {
            next = Long.parseLong(max.substring(REBALANCE_NO_PREFIX.length())) + 1L;
        }
        StringBuilder sb = new StringBuilder(REBALANCE_NO_PREFIX);
        String seq = Long.toString(next);
        for (int i = seq.length(); i < REBALANCE_NO_SEQ_WIDTH; i++) {
            sb.append('0');
        }
        return sb.append(seq).toString();
    }

    /** 调整前比率 = 关联计划的申报比率（比率留痕基准；计划缺失 ⇒ 参数错误）。 */
    private BigDecimal ratioOf(HedgeRelation relation) {
        HedgePlan plan = hedgePlanDao.findById(relation.hedgePlanId);
        if (plan == null || plan.hedgeRatio == null) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "绑定计划缺失或比率缺失，无法确定调整前比率：relationNo=" + relation.relationNo
                            + "；复现=核对 t_hedge_plan.hedge_ratio");
        }
        return plan.hedgeRatio;
    }

    /** 调整前数量 = 计划申报的套保数量（再平衡前工具侧数量基准；计划缺失 ⇒ 数据不足拒绝）。 */
    private BigDecimal hedgeQtyOf(HedgeRelation relation) {
        HedgePlan plan = hedgePlanDao.findById(relation.hedgePlanId);
        if (plan == null || plan.planQty == null) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "绑定计划缺失或数量缺失，无法确定调整前数量：relationNo=" + relation.relationNo
                            + "；复现=核对 t_hedge_plan.plan_qty");
        }
        return plan.planQty;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal scale9(BigDecimal v) {
        return v.setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal scale4(BigDecimal v) {
        return v.setScale(4, java.math.RoundingMode.HALF_UP);
    }

    /** ratioAfter 别名（可读性；与记录 ratioAfter 同源）。 */
    private static BigDecimal ratioAfter(RebalanceRequest req) {
        return req.ratioAfter;
    }

    /** CFG-08 敞口侧阈值（容错缺省 0.20；data-init.sql:326 已播种）。 */
    private BigDecimal exposureDeltaThreshold() {
        return decimalConfig(CFG_EXPOSURE_DELTA, CFG_EXPOSURE_DELTA_DEFAULT);
    }

    /** CFG-08 工具侧阈值（容错缺省 0.20；data-init.sql:330 已播种）。 */
    private BigDecimal ratioDeviationThreshold() {
        return decimalConfig(CFG_RATIO_DEVIATION, CFG_RATIO_DEVIATION_DEFAULT);
    }

    private BigDecimal decimalConfig(String key, String defaultValue) {
        String raw = config.get(key);
        if (isBlank(raw)) {
            LOG.warning("配置 " + key + " 未播种（CFG-08），容错取缺省 " + defaultValue
                    + "（种子补播归播种微单，T-188 CFG-26 先例）");
            return new BigDecimal(defaultValue);
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            if (v.signum() <= 0 || v.compareTo(BigDecimal.ONE) > 0) {
                LOG.warning("配置 " + key + " 取值超域 (0,1]（" + raw + "），容错取缺省 " + defaultValue);
                return new BigDecimal(defaultValue);
            }
            return v;
        } catch (NumberFormatException e) {
            LOG.warning("配置 " + key + " 取值非法（" + raw + "），容错取缺省 " + defaultValue);
            return new BigDecimal(defaultValue);
        }
    }

    /** 再平衡请求（四类触发场景 + 调整前后值；{@code T-078} 新增请求 DTO）。 */
    public static final class RebalanceRequest {
        /** 套期关系。 */
        public Long hedgeRelationId;
        /**
         * 触发场景（四类，Q-8）：{@code EXPOSURE_DELTA} / {@code RATIO_DEVIATION} /
         * {@code PLAN_REVISED} / {@code EXPOSURE_GONE}。偏离值由系统按数据面复核，
         * 不采信客户端声明（防伪造触发）。
         */
        public String triggerScenario;
        /** 触发原因描述（必填；落 trigger_reason）。 */
        public String triggerReason;
        /** 调整后套保比率（∈ (0,1]；敞口消失场景亦必填，记录申报目标）。 */
        public BigDecimal ratioAfter;
        /** 调整后数量（> 0；敞口消失场景须为 0）。 */
        public BigDecimal qtyAfter;
        /** 再平衡结论是否需反馈 PLAN 调整计划（Y/N；R-ACCT-3 最小承载）。 */
        public String planFeedbackRequired;
    }

    /** 四类触发的复核证据（拒绝时拼「当前偏离值」；内部载体）。 */
    private static final class Evaluation {
        boolean triggered;
        String triggerSide;
        BigDecimal exposureDelta = BigDecimal.ZERO;
        BigDecimal ratioDeviation = BigDecimal.ZERO;
        String planStatus = "null";
        boolean exposureExists;
        String currentValue;
        String thresholdValue;
    }

    // ================================================================ 请求 DTO

    /** 评估请求（观察点数据；{@code T-077} 新增请求 DTO）。 */
    public static final class AssessRequest {
        /** 套期关系。 */
        public Long hedgeRelationId;
        /** 会计期间（如 2026-09）。 */
        public String periodKey;
        /** 观察点①品种相关性（Y=同品种强 / N=近相关，N 须附 linkageNote）。 */
        public String observerLinkage;
        /** 近相关时的书面经济关系说明（Q-7）。 */
        public String linkageNote;
        /** 观察点②方向一致性（Y/N）。 */
        public String observerDirection;
        /** 观察点③期限对应性（Y/N；判定基准 = exposureExpectedDate）。 */
        public String observerTerm;
        /** 敞口预计实现日（期限对应性判定基准）。 */
        public LocalDate exposureExpectedDate;
        /** 量化监测指标（偏离 1 的值；可空；仅监测不判定）。 */
        public BigDecimal monitorDeviationRatio;
    }

    // ================================================================ 内部

    /**
     * 再平衡记录读取面：按套期关系取再平衡史（{@code idx_rebalance_relation_time}）。
     *
     * @param hedgeRelationId 套期关系
     * @param operatorId      调用者
     * @return 列表（{@code rebalance_time} 升序）
     */
    public List<RebalanceRecord> listRebalances(long hedgeRelationId, long operatorId) {
        if (!rebalanceSupported()) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "再平衡依赖未装配");
        }
        HedgeRelation relation = hedgeRelationDao.findById(hedgeRelationId);
        if (relation == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系不存在：id=" + hedgeRelationId);
        }
        visibility.assertSubAccountVisible(operatorId, requireSubOfRelation(relation));
        return rebalanceDao.findByHedgeRelation(hedgeRelationId);
    }

    /** 再平衡记录行视图字段透出（handler 复用；camelCase 契约形态）。 */
    public static Map<String, Object> rebalanceView(RebalanceRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("subAccountId", r.subAccountId);
        m.put("hedgeRelationId", r.hedgeRelationId);
        m.put("rebalanceNo", r.rebalanceNo);
        m.put("triggerType", r.triggerType);
        m.put("triggerSide", r.triggerSide);
        m.put("triggerReason", r.triggerReason);
        m.put("triggerExposureDelta", r.triggerExposureDelta == null ? null : r.triggerExposureDelta.toPlainString());
        m.put("triggerRatioDeviation", r.triggerRatioDeviation == null ? null : r.triggerRatioDeviation.toPlainString());
        m.put("triggerThreshold", r.triggerThreshold == null ? null : r.triggerThreshold.toPlainString());
        m.put("ratioBefore", r.ratioBefore == null ? null : r.ratioBefore.toPlainString());
        m.put("ratioAfter", r.ratioAfter == null ? null : r.ratioAfter.toPlainString());
        m.put("qtyBefore", r.qtyBefore == null ? null : r.qtyBefore.toPlainString());
        m.put("qtyAfter", r.qtyAfter == null ? null : r.qtyAfter.toPlainString());
        m.put("newHedgeDocId", r.newHedgeDocId);
        m.put("newHedgeRelationId", r.newHedgeRelationId);
        m.put("executorId", r.executorId);
        m.put("rebalanceTime", r.rebalanceTime == null ? null : r.rebalanceTime.toString());
        m.put("planFeedbackFlag", r.planFeedbackFlag);
        return m;
    }

    private String requireEnum(String value, String field, String... allowed) {
        if (value == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, field + " 必填");
        }
        for (String a : allowed) {
            if (a.equals(value)) {
                return value;
            }
        }
        throw BizException.of(ErrorCode.PARAM_INVALID,
                field + " 取值非法（合法：" + String.join("/", allowed) + "）：" + value);
    }

    /** 观察点取值解析：'Y'/'N' 缺失 ⇒ 记入缺失清单（由调用方拒绝）。 */
    private String requireFlag(String value, String field, List<String> missing) {
        if (isBlank(value)) {
            missing.add(field + "（取值 Y/N）");
            return null;
        }
        if (!"Y".equals(value.trim()) && !"N".equals(value.trim())) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    field + " 取值非法（Y/N）：" + value);
        }
        return value.trim();
    }

    /** CFG-07b 量化监测阈值（容错缺省；data-init.sql:322 已播种，缺种子时仅记录日志）。 */
    private BigDecimal monitorThreshold() {
        String raw = config.get(CFG_MONITOR_THRESHOLD);
        if (isBlank(raw)) {
            LOG.warning("配置 " + CFG_MONITOR_THRESHOLD + " 未播种（CFG-07b），容错取缺省 "
                    + CFG_MONITOR_THRESHOLD_DEFAULT + "（种子补播归播种微单，T-188 CFG-26 先例）");
            return new BigDecimal(CFG_MONITOR_THRESHOLD_DEFAULT);
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warning("配置 " + CFG_MONITOR_THRESHOLD + " 取值非法（" + raw + "），容错取缺省 "
                    + CFG_MONITOR_THRESHOLD_DEFAULT);
            return new BigDecimal(CFG_MONITOR_THRESHOLD_DEFAULT);
        }
    }

    /** CFG-07 观察点配置（容错缺省；本单判定固定三项，配置用于呈现与后续子集扩展的读取位）。 */
    public String observersConfig() {
        String raw = config.get(CFG_OBSERVERS);
        if (isBlank(raw)) {
            LOG.warning("配置 " + CFG_OBSERVERS + " 未播种（CFG-07），容错取缺省 "
                    + CFG_OBSERVERS_DEFAULT + "（种子补播归播种微单）");
            return CFG_OBSERVERS_DEFAULT;
        }
        return raw.trim();
    }

    /** 判定过程留痕（FR-ACCT-4 验收 3：第三方可复现——记录所用数据与判定路径）。 */
    private String buildObserverDetail(HedgeRelation relation, String linkage, String direction,
                                       String term, LocalDate expectedDate, int termDeviationDays,
                                       String conclusion, BigDecimal deviation,
                                       BigDecimal threshold, boolean flagged) {
        Map<String, Object> observers = new LinkedHashMap<>();
        observers.put("linkage（品种相关性）", linkage);
        observers.put("direction（方向一致性）", direction);
        observers.put("term（期限对应性）", term);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", METHOD_QUALITATIVE);
        m.put("relationNo", relation.relationNo);
        m.put("rule", "R-ACCT-2（Q-7）：三项观察点均满足即有效；量化监测仅提示复核不判定");
        m.put("observers", observers);
        m.put("termExpectedDate", expectedDate == null ? null : expectedDate.toString());
        m.put("termDeviationDays", termDeviationDays);
        m.put("termToleranceDays", TERM_TOLERANCE_DAYS);
        m.put("monitorDeviation", deviation == null ? null : deviation.toPlainString());
        m.put("monitorThreshold", threshold.toPlainString());
        m.put("monitorFlagged", flagged);
        m.put("monitorAffectsConclusion", false);
        m.put("conclusion", conclusion);
        m.put("decisionPath", "EFFECTIVE ⇔ linkage=='Y' && direction=='Y' && term=='Y'");
        m.put("assessedAt", Clocks.now().toString());
        return JsonCodec.toJson(m);
    }

    private void applyScope(java.util.function.Consumer<String> cond,
                            java.util.function.Consumer<Object> args, long operatorId) {
        List<Integer> visible = visibility.visibleSubAccountIds(operatorId);
        if (visible == null || visible.isEmpty()) {
            cond.accept("sub_account_id IN (-1)");
        } else if (!visible.contains(SubAccountScope.ALL_ACCOUNT_SENTINEL)) {
            SubAccountScope scope = SubAccountScope.of("sub_account_id", visible);
            cond.accept(scope.sql());
            for (Object p : scope.params()) {
                args.accept(p);
            }
        }
    }

    private void requireRelStatus(String status) {
        switch (status) {
            case REL_DESIGNATED:
            case REL_EFFECTIVE:
            case REL_NEED_REBALANCE:
            case REL_REDESIGNATED:
            case REL_TERMINATED:
                return;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "status 取值非法（套期关系 5 态）：" + status
                                + "；复现=使用 DESIGNATED/EFFECTIVE/NEED_REBALANCE/REDESIGNATED"
                                + "/TERMINATED 之一");
        }
    }

    private void requireConclusion(String conclusion) {
        switch (conclusion) {
            case CONCLUSION_EFFECTIVE:
            case CONCLUSION_INEFFECTIVE:
            case CONCLUSION_NEED_REBALANCE:
                return;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "conclusion 取值非法：" + conclusion
                                + "；复现=使用 EFFECTIVE/INEFFECTIVE/NEED_REBALANCE 之一");
        }
    }

    private Integer requireSubOfPlan(HedgePlan plan) {
        if (plan.subAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "计划子账户标识缺失（子账户贯穿字段规范，INDEX.md §2.1）；复现=核对计划归属列");
        }
        return plan.subAccountId;
    }

    private Integer requireSubOfRelation(HedgeRelation relation) {
        if (relation.subAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "套期关系子账户标识缺失（子账户贯穿字段规范）；复现=核对归属列");
        }
        return relation.subAccountId;
    }

    private Integer requireSubOfAssess(EffectivenessAssess assess) {
        if (assess.subAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "评估记录子账户标识缺失（子账户贯穿字段规范）；复现=核对归属列");
        }
        return assess.subAccountId;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 以「唯一约束冲突 ⇒ 重新取号并重试」执行套期关系编号分配
     * （{@code DATA_MODEL.md} §3.5；{@code FundService.retryApplyNo} 同构形态）。
     *
     * <p>仅当候选 {@code relation_no} 确实已被占用（= 编号冲突）时重试——本服务以
     * 「{@code findByRelationNo} 命中」判定占用；其他唯一约束冲突原样抛出，不误报为「编号冲突」。
     */
    private HedgeRelation retryOnRelationNoConflict(Supplier<HedgeRelation> attempt,
                                                    Supplier<String> candidateNo,
                                                    Predicate<String> occupied) {
        Object[] out = new Object[1];
        BizNoGenerator.retryOnUniqueConflict(RETRY_MAX_ATTEMPTS, () -> {
            try {
                out[0] = attempt.get();
                return true;
            } catch (IllegalStateException e) {
                SQLException unique = uniqueViolationCause(e);
                if (unique == null) {
                    throw e;
                }
                String no = candidateNo.get();
                if (no == null || !occupied.test(no)) {
                    throw new IllegalStateException(
                            "唯一约束冲突非套期关系编号（不重试）；复现=核对该事务其余唯一约束", e);
                }
                throw unique;
            }
        });
        return (HedgeRelation) out[0];
    }

    /**
     * 从异常链中解出「唯一约束冲突」的 {@link SQLException}（{@code SQLState 23xx}）；
     * {@code FundService.uniqueViolationCause} 同构（TxTemplate 包装形态一致）。
     */
    static SQLException uniqueViolationCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException
                    && BizNoGenerator.isUniqueViolation((SQLException) cause)) {
                return (SQLException) cause;
            }
        }
        return null;
    }

    // ================================================================ 五、会计凭证（FR-ACCT-6/7/13 / R-ACCT-5/6/11；T-079）

    /** 会计凭证查询适配（{@code AuditService.VoucherLookup} 接缝的生产实现；T-076 三遗留①承接）。 */
    private final class VoucherLookupAdapter implements AuditService.VoucherLookup {

        @Override
        public List<AuditService.VoucherRef> findByExposure(long exposureId) {
            return toRefs(voucherDao.findByExposure(exposureId));
        }

        @Override
        public List<AuditService.VoucherRef> findByHedgePlan(long hedgePlanId) {
            return toRefs(voucherDao.findByHedgePlan(hedgePlanId));
        }

        @Override
        public List<AuditService.VoucherRef> findBySettleCycle(long settleCycleId) {
            return toRefs(voucherDao.findBySettleCycle(settleCycleId));
        }

        /** 三路查询转投影：可见性由 AuditService 消费面按 Chain.allowed 承载（TE-T079B-001）。 */
        private List<AuditService.VoucherRef> toRefs(List<Voucher> list) {
            List<AuditService.VoucherRef> out = new ArrayList<>();
            for (Voucher v : list) {
                out.add(toRef(v));
            }
            return out;
        }

        @Override
        public AuditService.VoucherRef findByVoucher(String voucherIdOrNo) {
            Voucher v = findByVoucherIdOrNo(voucherIdOrNo);
            return v == null ? null : toRef(v);
        }

        /**
         * 转 VoucherRef 投影（<b>无可见性过滤</b>；TE-T079B-001 裁定形态）。
         *
         * <p><b>为何不再按 visibleSubAccountIds 过滤</b>：原实现以 {@code visibleSubAccountIds(-1)}
         * 取可见集——生产装配下 {@code user_id=-1} 是无权限行的哨兵（{@code t_user_sub_account_perm}
         * 零播种）⇒ 过滤集恒空 ⇒ 正向三路查询恒空 ⇒ 凭证段恒 UNKNOWN，「装配后转真数据」失效
         * （te-verify-t079 探针实证）。裁定：lookup 面的可见性语义由<b>上游</b>承载——
         * 正向链（collectVouchers 三路并集）消费面已按 {@code Chain.allowed}（operatorId 解析的
         * 可见集）过滤（AuditService {@code visible(v.subAccountId, c.allowed)}）；反向起点
         * （findByVoucher）的可见性由本适配器按调用方 operatorId 承载不可行（接缝无 operatorId
         * 参数，接口冻结），改为<b>service 层按 AuditService 传入的可见集过滤</b>——但 AuditService
         * 反向起点先经 {@code traceBackward} 的 {@code authService.visibleSubAccountIds(operatorId)}
         * 上下文，起点对象本身按「不可见 = 视同不存在」由 <b>VoucherLookup.findByVoucher 的
         * service 过滤形态</b>承接（TE-T079-001 裁定：service 层过滤，对齐 T-107/T-112 先例——
         * 本服务持有 visibility 接缝，lookup 由本服务构造，operatorId 上下文在 AuditService 侧，
         * 故 findByVoucher 按「AuditService 起点已授权」假设直通、三路并集按 AuditService 消费面
         * 过滤双落点披露）。
         */
        private AuditService.VoucherRef toRef(Voucher v) {
            AuditService.VoucherRef r = new AuditService.VoucherRef();
            r.id = v.id;
            r.voucherNo = v.voucherNo;
            r.voucherType = v.voucherType;
            r.subAccountId = v.subAccountId;
            r.hedgePlanId = v.hedgePlanId;
            r.exposureId = v.exposureId;
            r.settleCycleId = v.settleCycleId;
            r.status = v.status;
            return r;
        }
    }

    /**
     * {@code AuditService.VoucherLookup} 接缝的生产装配物（T-076 三遗留①承接）。
     *
     * <p>消费面 = AuditService 三段（:214 反向起点 / :457 正向终点 / collectVouchers 三路并集）；
     * 装配前该段判 UNKNOWN（诚实披露，R-AUDIT-2），装配后凭证段转真数据。
     *
     * @return 适配器；凭证依赖未装配齐（九参构造）返回 {@code null}（接缝维持未装配语义）
     */
    public AuditService.VoucherLookup voucherLookup() {
        if (voucherDao == null || visibility == null) {
            return null;
        }
        return new VoucherLookupAdapter();
    }

    /**
     * 生成会计凭证草稿（{@code FR-ACCT-6}；模式 {@code DRAFT_ONLY}、不做总账过账，
     * 草稿标注「待企业财务复核入账」——CFG-09）。
     *
     * <p><b>门禁链</b>（FR-ACCT-6 失败列全数落地）：
     * <ol>
     *   <li><b>子账户出账</b>（R-ACCT-5 红线）：{@code subAccountId} 缺失（null）⇒ 拒绝——
     *       数据库级 {@code sub_account_id NOT NULL} 兜底（T-076 交付），服务层前置同口径拒绝；</li>
     *   <li><b>期间关账</b>（FR-ACCT-6 验收 3）：期间行存在且 {@code CLOSED} ⇒ {@code 10016}
     *       拒绝并提示走下期调整（R-ACCT-8「关账后新凭证须通过下期调整」）；</li>
     *   <li><b>科目建议</b>（R-ACCT-11 不得硬编码）：按 {@code bizScene} 从 CONFIG 科目字典
     *       （{@code t_account_subject}）取启用科目；字典无可用科目 ⇒ {@code 10015} 拒绝
     *       （FR-ACCT-6 失败列「科目字典缺失」），<b>不</b>落任何臆造科目编码；</li>
     *   <li><b>幂等</b>（FR-ACCT-6 验收 4）：同子账户 + 同期间 + 同凭证类型已有凭证 ⇒
     *       {@code 10010} 明确提示已存在（不静默重复生成）。</li>
     * </ol>
     *
     * <p><b>DRAFT_ONLY 模式</b>（CFG-09）：读取 {@code accounting.voucher.mode}（缺种子容错
     * 缺省 {@code DRAFT_ONLY}，T-188 CFG-26 先例）；本方法<b>恒为草稿生成</b>（不执行任何总账
     * 过账动作——系统亦不存在过账通道），非 {@code DRAFT_ONLY} 取值 ⇒ 拒绝（取值域外即拒绝，
     * 不静默按草稿处理）。
     *
     * <p><b>分录行</b>：借贷成对 + 金额相等（服务层同事务校验 + {@code t_voucher.total_amount}
     * 冗余，§4.4 唯一例外）；科目建议 = 字典中该场景首个启用科目（含名称冗余）；摘要 = 调用方
     * 业务摘要。草稿（DRAFT）可删除（FR-ACCT-13「草稿可直接删除」经 {@link #deleteDraftVoucher}）。
     *
     * <p><b>取号</b>（O-4 教训 / T-078 RB 同法）：{@code voucher_no} 全系统唯一
     * （{@code uk_voucher_no}），形态 {@code AV<periodKey 紧排><seq(5)>}（DATA_MODEL §3.5 权威）；
     * 取号 = 该前缀内字典序最大 + 1（<b>非</b>「当日最大 +1」——前缀含期间段天然按期间隔离，
     * 但同一期间内仍取全前缀 max，防跳号复用）；并发兜底 = 唯一约束 + 最多 3 次重试。
     * <b>整笔 {@code tx.inTransaction} 包裹</b>（T-077-FIX TE-T077-001 判例）。
     *
     * @param req        草稿生成请求
     * @param operatorId 生成人（会话身份）
     * @return 落库的凭证（草稿态，含分录行经 {@code voucherLines} 回填）
     */
    public Voucher createVoucherDraft(VoucherDraftRequest req, long operatorId) {
        if (!voucherSupported()) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "凭证依赖未装配（VoucherDao/VoucherLineDao/VoucherReviewDao/AccountingPeriodDao"
                            + "/PeriodCloseLogDao/SettleCycleDao/AccountSubjectDao 缺失）；"
                            + "复现=以全参构造装配 AcctService 后重试");
        }
        if (req == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "凭证草稿请求不得为空");
        }
        String mode = voucherMode();
        if (!VOUCHER_MODE_DRAFT_ONLY.equals(mode)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "accounting.voucher.mode 取值非法（本系统仅支持 DRAFT_ONLY，Q-9 凭证不做总账"
                            + "过账）：" + mode + "；复现=订正 CFG-09 配置后重试");
        }
        // 门禁①：子账户出账（R-ACCT-5：子账户标识缺失时拒绝生成凭证）
        if (req.subAccountId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "subAccountId 必填（凭证必须按子账户出账，R-ACCT-5 / INDEX.md §2.1 凭证类红线；"
                            + "缺失时拒绝生成凭证）；复现=补齐子账户标识后重试");
        }
        visibility.assertSubAccountWritable(operatorId, req.subAccountId);
        if (isBlank(req.periodKey)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "periodKey 必填（会计期间，如 2026-10）");
        }
        String periodKey = req.periodKey.trim();
        String type = requireVoucherType(req.voucherType);
        if (isBlank(req.summary)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "summary 必填（业务摘要，FR-ACCT-6 凭证含摘要）");
        }
        if (req.amount == null || req.amount.signum() <= 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "amount 须 > 0（ck_t_voucher_line_amount_positive 同域）："
                            + (req.amount == null ? "null" : req.amount.toPlainString()));
        }
        // 门禁②：期间关账（FR-ACCT-6 验收 3；R-ACCT-8）
        AccountingPeriod period = periodDao.findByPeriodKey(periodKey);
        if (period != null && "CLOSED".equals(period.status)) {
            throw BizException.of(ErrorCode.PERIOD_CLOSED,
                    "该期间已关账，不得生成新凭证（须通过下期调整，FR-ACCT-6 验收 3 / R-ACCT-8）："
                            + "periodKey=" + periodKey + "；复现=改用下一开放期间提交调整凭证");
        }
        // 门禁③：科目建议（R-ACCT-11：科目来自 CONFIG 字典，不得硬编码；缺失 ⇒ 明确拒绝）
        AccountSubject subject = suggestSubject(type);
        // 门禁④：幂等（FR-ACCT-6 验收 4：重复生成不产生重复凭证，明确提示）
        if (!voucherDao.findBySubPeriodType(req.subAccountId, periodKey, type).isEmpty()) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "该子账户该期间该凭证类型已有凭证（FR-ACCT-6 验收 4 幂等约束）：subAccountId="
                            + req.subAccountId + " periodKey=" + periodKey + " voucherType=" + type
                            + "；复现=经 GET /acct/voucher 定位既有凭证，或改用其它凭证类型");
        }
        String[] candidateNo = new String[1];
        return tx.inTransaction(conn -> {
            Voucher v = new Voucher();
            v.subAccountId = req.subAccountId;
            candidateNo[0] = nextVoucherNo(periodKey);
            v.voucherNo = candidateNo[0];
            v.periodKey = periodKey;
            v.voucherDate = Clocks.today();
            v.voucherType = type;
            v.hedgePlanId = req.hedgePlanId;
            v.hedgeRelationId = req.hedgeRelationId;
            v.settleCycleId = req.settleCycleId;
            v.exposureId = req.exposureId;
            v.status = VOUCHER_DRAFT;
            v.totalAmount = req.amount;
            v.generatedBy = operatorId;
            v.generatedTime = Clocks.now();
            v.reversalOfId = null;
            v.reversedById = null;
            v.reversalReason = null;
            v.pendingExternalReview = "Y";   // R-ACCT-10：恒 'Y'（DB CHECK 承载）
            if (voucherDao.insert(v, operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "凭证写入失败：voucherNo=" + v.voucherNo);
            }
            insertBalancedLines(v, subject, req, operatorId);
            return v;
        });
    }

    /** 凭证类型取值域校验（与 {@code ck_t_voucher_type} 十值同源，含 DC-031 期权两值）。 */
    private String requireVoucherType(String type) {
        switch (type == null ? "" : type.trim()) {
            case "CLOSE_PNL":
            case "COMMISSION":
            case "MARGIN_CHANGE":
            case "FLOAT_PNL":
            case "PREMIUM":
            case "EXERCISE":
            case "EXPIRED_WORTHLESS":
            case "HEDGE_EFFECT":
            case "REVERSAL":
            case "OTHER":
                return type.trim();
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "voucherType 取值非法（ck_t_voucher_type 十值，含 DC-031 期权行权/作废）："
                                + type + "；复现=使用 CLOSE_PNL/COMMISSION/MARGIN_CHANGE/FLOAT_PNL"
                                + "/PREMIUM/EXERCISE/EXPIRED_WORTHLESS/HEDGE_EFFECT/REVERSAL/OTHER 之一");
        }
    }

    /**
     * 科目建议（R-ACCT-11：按凭证类型映射 {@code biz_scene}，从 CONFIG 字典取<b>启用</b>科目）。
     *
     * <p>映射（voucher_type → biz_scene，字典场景列 {@code ck_account_subject_biz_scene} 八值）：
     * CLOSE_PNL/HEDGE_EFFECT → CLOSE_PNL；COMMISSION → COMMISSION；MARGIN_CHANGE → MARGIN_CHANGE；
     * FLOAT_PNL → FLOAT_PNL；PREMIUM/EXERCISE/EXPIRED_WORTHLESS → PREMIUM；REVERSAL/OTHER → OTHER。
     * 字典无可用科目 ⇒ {@code 10015}（FR-ACCT-6 失败列「科目字典缺失」），不臆造科目编码。
     */
    private AccountSubject suggestSubject(String voucherType) {
        String scene;
        switch (voucherType) {
            case "CLOSE_PNL":
            case "HEDGE_EFFECT":
                scene = "CLOSE_PNL";
                break;
            case "COMMISSION":
                scene = "COMMISSION";
                break;
            case "MARGIN_CHANGE":
                scene = "MARGIN_CHANGE";
                break;
            case "FLOAT_PNL":
                scene = "FLOAT_PNL";
                break;
            case "PREMIUM":
            case "EXERCISE":
            case "EXPIRED_WORTHLESS":
                scene = "PREMIUM";
                break;
            default:
                scene = "OTHER";
                break;
        }
        List<AccountSubject> candidates = subjectDao.findEnabledByScene(scene);
        if (candidates.isEmpty()) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "科目字典缺失该业务场景的可用科目（R-ACCT-11 科目来自 CONFIG 字典，"
                            + "不得硬编码/臆造）：bizScene=" + scene + " voucherType=" + voucherType
                            + "；复现=在 CONFIG 模块维护 t_account_subject 后重试"
                            + "（FR-ACCT-6 失败列：科目字典缺失）");
        }
        return candidates.get(0);
    }

    /**
     * 分录行写入：借贷成对 + 金额相等（{@code §4.4} 跨行平衡的服务层同事务校验；
     * {@code amount > 0} 由 DB CHECK 兜底，方向由 {@code direction} 表达）。
     */
    private void insertBalancedLines(Voucher v, AccountSubject subject, VoucherDraftRequest req,
                                     long operatorId) {
        VoucherLine debit = new VoucherLine();
        debit.subAccountId = v.subAccountId;
        debit.voucherId = v.id;
        debit.lineNo = 1;
        debit.subjectCode = subject.subjectCode;
        debit.subjectName = subject.subjectName;
        debit.direction = "DEBIT";
        debit.amount = req.amount;
        debit.qty = req.qty;
        debit.tradeFillId = req.tradeFillId;
        debit.bizDesc = req.summary.trim();
        VoucherLine credit = new VoucherLine();
        credit.subAccountId = v.subAccountId;
        credit.voucherId = v.id;
        credit.lineNo = 2;
        credit.subjectCode = subject.subjectCode;
        credit.subjectName = subject.subjectName;
        credit.direction = "CREDIT";
        credit.amount = req.amount;
        credit.qty = req.qty;
        credit.tradeFillId = req.tradeFillId;
        credit.bizDesc = req.summary.trim();
        // 跨行平衡断言（§4.4：借贷合计相等；本形态借贷同额同科目，恒成立，留断言防未来扩展失衡）
        if (debit.amount.compareTo(credit.amount) != 0) {
            throw BizException.of(ErrorCode.AGGREGATION_MISMATCH,
                    "凭证借贷不平衡（§4.4 服务层校验）：借=" + debit.amount.toPlainString()
                            + " 贷=" + credit.amount.toPlainString());
        }
        if (voucherLineDao.insert(debit, operatorId) != 1
                || voucherLineDao.insert(credit, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "凭证分录写入失败：voucherNo=" + v.voucherNo);
        }
    }

    /** CFG-09 凭证模式（容错缺省 DRAFT_ONLY；data-init.sql:338 已播种，缺种子仅记录日志）。 */
    private String voucherMode() {
        String raw = config.get(CFG_VOUCHER_MODE);
        if (isBlank(raw)) {
            LOG.warning("配置 " + CFG_VOUCHER_MODE + " 未播种（CFG-09），容错取缺省 "
                    + VOUCHER_MODE_DRAFT_ONLY + "（种子补播归播种微单，T-188 CFG-26 先例）");
            return VOUCHER_MODE_DRAFT_ONLY;
        }
        return raw.trim();
    }

    /**
     * 凭证下一编号 = {@code AV<periodKey 紧排><seq(5)>}，序号 = <b>该前缀内字典序最大 + 1</b>
     * （O-4 教训：{@code uk_voucher_no} 全系统唯一；T-078 RB 同法。期间段 {@code yyyyMM}
     * 来自 DATA_MODEL §3.5 形态 {@code AV20260900031}）。
     */
    private String nextVoucherNo(String periodKey) {
        String periodText = periodKey.replace("-", "");
        if (periodText.length() != VOUCHER_NO_PERIOD_WIDTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "periodKey 须为 yyyy-MM 形态（凭证编号 AV<期间><seq(5)>，DATA_MODEL §3.5）："
                            + periodKey);
        }
        String prefix = VOUCHER_NO_PREFIX + periodText;
        String max = voucherDao.findMaxVoucherNoByPrefix(prefix);
        long next = 1L;
        if (max != null && max.length() > prefix.length()) {
            String seqText = max.substring(prefix.length());
            try {
                next = Long.parseLong(seqText) + 1L;
            } catch (NumberFormatException e) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "凭证编号序号段非法（uk_voucher_no 前缀 " + prefix + "）：" + max);
            }
        }
        StringBuilder sb = new StringBuilder(prefix);
        String seq = Long.toString(next);
        for (int i = seq.length(); i < VOUCHER_NO_SEQ_WIDTH; i++) {
            sb.append('0');
        }
        return sb.append(seq).toString();
    }

    /** 按编号或主键取凭证（{@code findByVoucherIdOrNo}；VoucherLookup.findByVoucher 同法）。 */
    private Voucher findByVoucherIdOrNo(String voucherIdOrNo) {
        if (voucherIdOrNo == null || voucherIdOrNo.trim().isEmpty()) {
            return null;
        }
        String text = voucherIdOrNo.trim();
        Voucher v = voucherDao.findByVoucherNo(text);
        if (v == null) {
            try {
                v = voucherDao.findById(Long.parseLong(text));
            } catch (NumberFormatException e) {
                return null;   // 非编号非主键 = 不存在（AuditService.addBreak 同语义）
            }
        }
        return v;
    }

    /**
     * 提交凭证审核（动作路由：{@code SUBMIT} / {@code APPROVE} / {@code REJECT} / {@code WITHDRAW}；
     * {@code FR-ACCT-7} + {@code R-ACCT-6}；状态机 {@code ACCT.md} §2.1）。
     *
     * <p><b>岗位分离</b>（R-ACCT-6）：APPROVE/REJECT 的审核人不得为生成人（{@code 10005}）；
     * 审核记录（人、时间、结论、意见）永久留痕于 {@code t_voucher_review}（只增链，逐节点
     * {@code node_seq = max + 1}）。
     *
     * <p><b>驳回</b>：原因必填；凭证回到 {@code DRAFT}（可修改）。<b>通过</b>：凭证 →
     * {@code POSTED}（正式，不可修改——DmVoucherDao 无删除入口 + 本服务无修改入口，
     * R-ACCT-5「系统不提供入口」）。
     *
     * <p><b>期间关账交互</b>：凭证所属期间已关账 ⇒ 拒绝一切状态变更（关账后凭证不可修改，
     * FR-ACCT-12 验收联动 / T-079 验收 4；冲销路径同门禁，见 {@link #reverseVoucher}）。
     * <b>整笔事务包裹</b>（TE-T077-001 判例）。
     *
     * @param voucherId  凭证
     * @param req        审核请求（action 必填；REJECT 时 rejectReason 必填）
     * @param operatorId 审核人（会话身份）
     * @return 审核记录
     */
    public VoucherReview reviewVoucher(long voucherId, VoucherReviewRequest req, long operatorId) {
        requireVoucherSupported();
        if (req == null || isBlank(req.action)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "action 必填（SUBMIT/APPROVE/REJECT/WITHDRAW）");
        }
        String action = req.action.trim();
        return tx.inTransaction(conn -> doReview(voucherId, action, req, operatorId));
    }

    private VoucherReview doReview(long voucherId, String action, VoucherReviewRequest req,
                                   long operatorId) {
        Voucher v = voucherDao.findById(voucherId);
        if (v == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "凭证不存在：id=" + voucherId);
        }
        visibility.assertSubAccountWritable(operatorId, v.subAccountId);
        requirePeriodOpen(v.periodKey, "凭证状态变更");
        VoucherReview node = new VoucherReview();
        node.subAccountId = v.subAccountId;
        node.voucherId = v.id;
        node.nodeSeq = voucherReviewDao.findMaxNodeSeq(v.id) + 1;
        node.action = action;
        node.generatorId = v.generatedBy;
        node.actionTime = Clocks.now();
        switch (action) {
            case REVIEW_ACTION_SUBMIT:
                if (!VOUCHER_DRAFT.equals(v.status)) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "仅草稿可提交审核：voucherNo=" + v.voucherNo + " status=" + v.status
                                    + "；复现=对 DRAFT 状态凭证提交");
                }
                v.status = VOUCHER_PENDING_REVIEW;
                node.reviewerId = operatorId;
                node.reviewResult = null;
                break;
            case REVIEW_ACTION_APPROVE:
                if (!VOUCHER_PENDING_REVIEW.equals(v.status)) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "仅待审核凭证可通过审核：voucherNo=" + v.voucherNo + " status="
                                    + v.status + "；复现=先提交（SUBMIT）再审核");
                }
                if (v.generatedBy != null && v.generatedBy == operatorId) {
                    // R-ACCT-6：草稿的生成人与审核人不得为同一人（岗位分离）
                    throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                            "凭证生成人不得审核自己生成的凭证（R-ACCT-6 岗位分离）：voucherNo="
                                    + v.voucherNo + " 生成人=" + v.generatedBy + " 审核人=" + operatorId
                                    + "；复现=由其它有审核权限的岗位审核");
                }
                v.status = VOUCHER_POSTED;
                node.reviewerId = operatorId;
                node.reviewResult = "APPROVED";
                node.reviewComment = req.reviewComment;
                break;
            case REVIEW_ACTION_REJECT:
                if (!VOUCHER_PENDING_REVIEW.equals(v.status)) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "仅待审核凭证可驳回：voucherNo=" + v.voucherNo + " status=" + v.status);
                }
                if (v.generatedBy != null && v.generatedBy == operatorId) {
                    throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                            "凭证生成人不得审核（驳回）自己生成的凭证（R-ACCT-6 岗位分离）：voucherNo="
                                    + v.voucherNo);
                }
                if (isBlank(req.rejectReason)) {
                    // FR-ACCT-7 验收 3：驳回时未填原因被拒绝
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "驳回原因必填（R-ACCT-6 / FR-ACCT-7 验收 3）：voucherNo=" + v.voucherNo
                                    + "；复现=补齐 rejectReason 后重新驳回");
                }
                v.status = VOUCHER_DRAFT;   // 驳回后回到草稿可修改
                node.reviewerId = operatorId;
                node.reviewResult = "REJECTED";
                node.reviewComment = req.reviewComment;
                node.rejectReason = req.rejectReason.trim();
                break;
            case REVIEW_ACTION_WITHDRAW:
                if (!VOUCHER_PENDING_REVIEW.equals(v.status)) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "仅待审核凭证可撤回：voucherNo=" + v.voucherNo + " status=" + v.status);
                }
                if (v.generatedBy != null && v.generatedBy != operatorId) {
                    throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                            "仅凭证生成人可撤回待审核凭证：voucherNo=" + v.voucherNo
                                    + " 生成人=" + v.generatedBy);
                }
                v.status = VOUCHER_DRAFT;
                node.reviewerId = operatorId;
                node.reviewResult = null;
                break;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "action 取值非法（SUBMIT/APPROVE/REJECT/WITHDRAW）：" + action);
        }
        if (voucherDao.updateById(v, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "凭证并发冲突：voucherNo=" + v.voucherNo + "；复现=重新读取后再提交");
        }
        if (voucherReviewDao.insert(node, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "审核留痕写入失败：voucherNo=" + v.voucherNo + " nodeSeq=" + node.nodeSeq);
        }
        return node;
    }

    /**
     * 冲销正式凭证（{@code FR-ACCT-13} + {@code R-ACCT-5}；生成反向凭证并关联原凭证）。
     *
     * <p><b>门禁链</b>：
     * <ol>
     *   <li>仅 {@code POSTED} 凭证可冲销（草稿走删除 {@link #deleteDraftVoucher}；
     *       已冲销/待审核不可冲销）；</li>
     *   <li>冲销原因必填（FR-ACCT-13）；</li>
     *   <li><b>双人复核</b>（R-ACCT-5 / PI-11/DC-032）：发起人（operatorId）≠ 生成人且
     *       ≠ 第二复核人 {@code coReviewerId}，且三人两两互异——任一相同 ⇒ {@code 10005}；</li>
     *   <li><b>已关账期间</b>：原凭证所属期间已关账 ⇒ 反向凭证落在<b>当期</b>
     *       （{@code Clocks.today()} 所属期间，FR-ACCT-13 验收 4「不回改已关账期间」；
     *       原凭证行零改动——它只被置 {@code REVERSED} 并回写 {@code reversed_by_id}）；</li>
     *   <li>当期（或原期间，若开放）本身已关账且无更后开放期间语义可用 ⇒ {@code 10016} 拒绝。</li>
     * </ol>
     *
     * <p><b>冲销形态</b>：反向凭证 = 借贷对调的同科目同金额分录（{@code voucher_type=REVERSAL}、
     * {@code reversal_of_id=原凭证}）；原凭证 {@code status=REVERSED} +
     * {@code reversed_by_id=反向凭证} + {@code reversal_reason} 留痕。<b>整笔事务包裹</b>；
     * 审核链补第二复核人节点（{@code co_reviewer_id} 生效落点，DC-032）。
     *
     * @param voucherId  被冲销的正式凭证
     * @param req        冲销请求（reason 必填、coReviewerId 必填且 ≠ 发起人）
     * @param operatorId 发起人（会话身份）
     * @return 反向凭证
     */
    public Voucher reverseVoucher(long voucherId, VoucherReverseRequest req, long operatorId) {
        requireVoucherSupported();
        if (req == null || isBlank(req.reason)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "冲销原因必填（FR-ACCT-13）：复现=补齐 reason 后重试");
        }
        if (req.coReviewerId == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "第二复核人必填（冲销双人复核，R-ACCT-5 / DC-032）：复现=补齐 coReviewerId");
        }
        return tx.inTransaction(conn -> doReverse(voucherId, req, operatorId));
    }

    private Voucher doReverse(long voucherId, VoucherReverseRequest req, long operatorId) {
        Voucher original = voucherDao.findById(voucherId);
        if (original == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "凭证不存在：id=" + voucherId);
        }
        visibility.assertSubAccountWritable(operatorId, original.subAccountId);
        if (!VOUCHER_POSTED.equals(original.status)) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "仅正式（POSTED）凭证可冲销：voucherNo=" + original.voucherNo + " status="
                            + original.status + "；复现=草稿走删除（deleteDraftVoucher），"
                            + "已冲销凭证不可重复冲销");
        }
        // 双人复核（R-ACCT-5 / DC-032）：发起人 ≠ 生成人，三人两两互异
        if (original.generatedBy != null && original.generatedBy == operatorId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "冲销发起人不得为凭证生成人（岗位分离，R-ACCT-6）：voucherNo="
                            + original.voucherNo + " 生成人=" + original.generatedBy);
        }
        if (req.coReviewerId == operatorId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "第二复核人不得与发起人同一人（冲销双人复核，FR-ACCT-13 验收 3）：voucherNo="
                            + original.voucherNo + " 发起人=" + operatorId);
        }
        if (original.generatedBy != null && original.generatedBy == req.coReviewerId) {
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "第二复核人不得与凭证生成人同一人（DC-032：co_reviewer_id ≠ generator_id）："
                            + "voucherNo=" + original.voucherNo);
        }
        // 反向凭证落账期间：原期间开放 → 原期间；原期间已关账 → 当期（下期调整语义，
        // FR-ACCT-13 验收 4「不回改已关账期间」——原凭证行零业务改动）
        String targetPeriod = original.periodKey;
        AccountingPeriod originalPeriod = periodDao.findByPeriodKey(original.periodKey);
        if (originalPeriod != null && "CLOSED".equals(originalPeriod.status)) {
            targetPeriod = currentPeriodKey();
            AccountingPeriod current = periodDao.findByPeriodKey(targetPeriod);
            if (current != null && "CLOSED".equals(current.status)) {
                throw BizException.of(ErrorCode.PERIOD_CLOSED,
                        "原期间已关账且当期亦已关账，冲销无法落账（FR-ACCT-13 验收 4）："
                                + "voucherNo=" + original.voucherNo + " 原期间=" + original.periodKey
                                + " 当期=" + targetPeriod + "；复现=先撤销当期关账再冲销");
            }
        } else {
            requirePeriodOpen(targetPeriod, "凭证冲销");
        }

        List<VoucherLine> originalLines = voucherLineDao.findByVoucher(original.id);
        if (originalLines.isEmpty()) {
            throw BizException.of(ErrorCode.DATA_INSUFFICIENT,
                    "原凭证无分录行，无法生成反向凭证：voucherNo=" + original.voucherNo);
        }
        // 反向凭证（voucher_type=REVERSAL；编号全系统前缀 max+1 + 唯一约束重试）
        String[] candidateNo = new String[1];
        String targetPeriodFinal = targetPeriod;
        Voucher reversal = new Voucher();
        reversal.subAccountId = original.subAccountId;
        candidateNo[0] = nextVoucherNo(targetPeriodFinal);
        reversal.voucherNo = candidateNo[0];
        reversal.periodKey = targetPeriodFinal;
        reversal.voucherDate = Clocks.today();
        reversal.voucherType = "REVERSAL";
        reversal.hedgePlanId = original.hedgePlanId;
        reversal.hedgeRelationId = original.hedgeRelationId;
        reversal.settleCycleId = original.settleCycleId;
        reversal.exposureId = original.exposureId;
        reversal.status = VOUCHER_POSTED;   // 反向凭证经双人复核即时正式（复核链留痕）
        reversal.totalAmount = original.totalAmount;
        reversal.generatedBy = operatorId;
        reversal.generatedTime = Clocks.now();
        reversal.reversalOfId = original.id;
        reversal.reversalReason = req.reason.trim();
        reversal.pendingExternalReview = "Y";
        if (voucherDao.insert(reversal, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "反向凭证写入失败：voucherNo=" + reversal.voucherNo);
        }
        // 反向分录：借贷对调（同科目同金额；lineNo 重排）
        int lineNo = 1;
        for (VoucherLine line : originalLines) {
            VoucherLine mirrored = new VoucherLine();
            mirrored.subAccountId = reversal.subAccountId;
            mirrored.voucherId = reversal.id;
            mirrored.lineNo = lineNo++;
            mirrored.subjectCode = line.subjectCode;
            mirrored.subjectName = line.subjectName;
            mirrored.direction = "DEBIT".equals(line.direction) ? "CREDIT" : "DEBIT";
            mirrored.amount = line.amount;
            mirrored.qty = line.qty;
            mirrored.tradeFillId = line.tradeFillId;
            mirrored.bizDesc = "冲销 " + original.voucherNo + "：" + line.bizDesc;
            if (voucherLineDao.insert(mirrored, operatorId) != 1) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR,
                        "反向分录写入失败：voucherNo=" + reversal.voucherNo);
            }
        }
        // 原凭证：REVERSED + reversed_by_id（业务字段级状态流转，非编辑——R-ACCT-5 载体；
        // reversal_reason 落原凭证留痕）
        original.status = VOUCHER_REVERSED;
        original.reversedById = reversal.id;
        original.reversalReason = req.reason.trim();
        if (voucherDao.updateById(original, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "原凭证并发冲突：voucherNo=" + original.voucherNo + "；复现=重新读取后再冲销");
        }
        // 双人复核留痕（DC-032 落点）：原凭证与反向凭证各补一条复核节点，co_reviewer_id 生效
        int baseSeq = voucherReviewDao.findMaxNodeSeq(original.id);
        insertCoReviewNode(original, operatorId, req.coReviewerId, baseSeq + 1, req.reason.trim());
        insertCoReviewNode(reversal, operatorId, req.coReviewerId, 1, req.reason.trim());
        return reversal;
    }

    /** 冲销复核节点（action=APPROVE、review_result=APPROVED、co_reviewer_id=第二复核人）。 */
    private void insertCoReviewNode(Voucher v, long operatorId, long coReviewerId, int nodeSeq,
                                    String reason) {
        VoucherReview node = new VoucherReview();
        node.subAccountId = v.subAccountId;
        node.voucherId = v.id;
        node.nodeSeq = nodeSeq;
        node.action = REVIEW_ACTION_APPROVE;
        node.generatorId = v.generatedBy;
        node.reviewerId = operatorId;
        node.coReviewerId = coReviewerId;
        node.reviewResult = "APPROVED";
        node.reviewComment = "冲销双人复核（DC-032）：发起=" + operatorId + " 第二复核=" + coReviewerId;
        node.rejectReason = null;
        node.actionTime = Clocks.now();
        if (voucherReviewDao.insert(node, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "冲销复核留痕写入失败：voucherNo=" + v.voucherNo);
        }
    }

    /**
     * 删除草稿凭证（{@code FR-ACCT-13}「草稿状态的凭证可直接删除（须留痕）」）。
     *
     * <p><b>留痕形态</b>：正式凭证不可物理删除（R-ACCT-5；{@code DmVoucherDao} 亦无
     * {@code deleteById}），草稿删除同样以<b>留痕</b>承载——本方法不物理删行，而是把草稿置
     * {@code REVERSED} 并写 {@code reversal_reason}（「草稿删除」）——保留 {@code voucher_no}
     * 审计可追溯性（FR-ACCT-8「凭证记录只读」）。物理删除能力不在本系统任何路径暴露。
     *
     * @param voucherId  草稿凭证
     * @param operatorId 操作人（须 ≠ 生成人为非必需——草稿删除无岗位分离要求，仅须写授权）
     * @return 处置后的凭证
     */
    public Voucher deleteDraftVoucher(long voucherId, long operatorId) {
        requireVoucherSupported();
        return tx.inTransaction(conn -> {
            Voucher v = voucherDao.findById(voucherId);
            if (v == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "凭证不存在：id=" + voucherId);
            }
            visibility.assertSubAccountWritable(operatorId, v.subAccountId);
            requirePeriodOpen(v.periodKey, "草稿删除");
            if (!VOUCHER_DRAFT.equals(v.status)) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "仅草稿可删除：voucherNo=" + v.voucherNo + " status=" + v.status
                                + "；复现=正式凭证走冲销（reverseVoucher）");
            }
            v.status = VOUCHER_REVERSED;
            v.reversalReason = "草稿删除（FR-ACCT-13：草稿可直接删除，留痕承载）";
            if (voucherDao.updateById(v, operatorId) != 1) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "凭证并发冲突：voucherNo=" + v.voucherNo);
            }
            return v;
        });
    }

    /**
     * 分页查询凭证（{@code FR-ACCT-8}；默认按凭证编号倒序——5 参 {@code PageQuery.of}
     * 显式缺省方向；子账户可见集过滤）。
     */
    public PageResult<Voucher> listVouchers(PageQuery query, Integer subAccountId, String periodKey,
                                            String status, long operatorId) {
        requireVoucherSupported();
        if (status != null) {
            requireVoucherStatus(status);
        }
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (subAccountId != null) {
            visibility.assertSubAccountVisible(operatorId, subAccountId);
            conds.add("sub_account_id = ?");
            args.add(subAccountId);
        }
        if (periodKey != null) {
            conds.add("period_key = ?");
            args.add(periodKey);
        }
        if (status != null) {
            conds.add("status = ?");
            args.add(status);
        }
        applyScope(conds::add, args::add, operatorId);
        return voucherDao.findPage(query,
                conds.isEmpty() ? null : String.join(" AND ", conds), args);
    }

    /** 凭证详情（含分录行；{@code FR-ACCT-8} 展示）。 */
    public VoucherDetail getVoucher(long voucherId, long operatorId) {
        requireVoucherSupported();
        Voucher v = voucherDao.findById(voucherId);
        if (v == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "凭证不存在：id=" + voucherId);
        }
        visibility.assertSubAccountVisible(operatorId, v.subAccountId);
        VoucherDetail d = new VoucherDetail();
        d.voucher = v;
        d.lines = voucherLineDao.findByVoucher(v.id);
        d.reviews = voucherReviewDao.findByVoucher(v.id);
        return d;
    }

    /** 凭证状态取值域校验（4 态）。 */
    private void requireVoucherStatus(String status) {
        switch (status) {
            case VOUCHER_DRAFT:
            case VOUCHER_PENDING_REVIEW:
            case VOUCHER_POSTED:
            case VOUCHER_REVERSED:
                return;
            default:
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "status 取值非法（凭证 4 态）：" + status
                                + "；复现=使用 DRAFT/PENDING_REVIEW/POSTED/REVERSED 之一");
        }
    }

    private void requireVoucherSupported() {
        if (!voucherSupported()) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "凭证依赖未装配");
        }
    }

    /** 期间未关账断言（关账后凭证不可修改/删除，FR-ACCT-12 验收 3 联动）。 */
    private void requirePeriodOpen(String periodKey, String action) {
        AccountingPeriod period = periodDao.findByPeriodKey(periodKey);
        if (period != null && "CLOSED".equals(period.status)) {
            throw BizException.of(ErrorCode.PERIOD_CLOSED,
                    "该期间已关账，" + action + "被拒绝（关账后凭证不可修改，FR-ACCT-12 验收 3）："
                            + "periodKey=" + periodKey + "；复现=先撤销关账（双人复核）或走下期调整");
        }
    }

    /** 当期期间键（{@code yyyy-MM}，Clocks.today() 派生；已关账期间冲销的「下期调整」落点）。 */
    private String currentPeriodKey() {
        LocalDate today = Clocks.today();
        return String.format("%04d-%02d", today.getYear(), today.getMonthValue());
    }

    // ================================================================ 六、会计期间关账（FR-ACCT-12 / R-ACCT-8；T-079）

    /**
     * 分页查询会计期间（全局期间定义，无子账户维度；{@code FR-ACCT-12} 列表入口）。
     */
    public PageResult<AccountingPeriod> listPeriods(PageQuery query) {
        requireVoucherSupported();
        return periodDao.findPage(query, null, null);
    }

    /**
     * 创建开放会计期间（期间关账的最小前置——期间行是关账动作的载体；
     * {@code uk_accounting_period_key} 幂等：期间键已存在 ⇒ {@code 10010}）。
     *
     * <p>全局期间定义（例外 E2，无子账户维度、无可见性过滤——创建动作的功能权限由
     * 会话鉴权层承载，本服务不做子账户维度断言）。
     *
     * @param periodKey  期间键（yyyy-MM；形态非法拒绝）
     * @param periodType {@code MONTH} / {@code QUARTER} / {@code YEAR}
     * @param periodStart 期间起
     * @param periodEnd   期间止
     * @param operatorId  操作人
     * @return 落库的期间行（OPEN）
     */
    public AccountingPeriod createPeriod(String periodKey, String periodType, LocalDate periodStart,
                                         LocalDate periodEnd, long operatorId) {
        requireVoucherSupported();
        if (isBlank(periodKey)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "periodKey 必填（yyyy-MM）");
        }
        String key = periodKey.trim();
        if (!key.matches("\\d{4}-\\d{2}")) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "periodKey 须为 yyyy-MM 形态：" + key);
        }
        if (!"MONTH".equals(periodType) && !"QUARTER".equals(periodType)
                && !"YEAR".equals(periodType)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "periodType 取值非法（ck_t_accounting_period_type）：" + periodType
                            + "；复现=使用 MONTH/QUARTER/YEAR 之一");
        }
        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "periodStart/periodEnd 必填且 periodEnd ≥ periodStart："
                            + periodStart + " ~ " + periodEnd);
        }
        if (periodDao.findByPeriodKey(key) != null) {
            throw BizException.of(ErrorCode.UNIQUE_CONFLICT,
                    "会计期间已存在（uk_accounting_period_key）：periodKey=" + key
                            + "；复现=经 GET /acct/period 定位既有期间");
        }
        AccountingPeriod p = new AccountingPeriod();
        p.periodKey = key;
        p.periodType = periodType;
        p.periodStart = periodStart;
        p.periodEnd = periodEnd;
        p.status = "OPEN";
        if (periodDao.insert(p, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "会计期间写入失败：periodKey=" + key);
        }
        return p;
    }

    /**
     * 关账 / 撤销关账（{@code FR-ACCT-12} + {@code R-ACCT-8}；双人复核并留痕）。
     *
     * <p><b>关账三前置</b>（任一不满足不得关账，{@code 10012} 阻断四项载荷）：
     * ① 该期间<b>全部凭证已审核或已冲销</b>（逐子账户统计非 POSTED/REVERSED 凭证并列出，
     * FR-ACCT-12 验收 2「列出该凭证」）；② 该期间<b>结算已完成</b>（期间止当日及以前的
     * 结算单全部 {@code CONFIRMED}/{@code RECONCILED}，AuditService.isSettled 同源口径）；
     * ③ 该期间<b>有效性评估已完成</b>（ACCT.md :276 字面③ =「该期间有效性评估已完成」——
     * 按 ACCT.md R-ACCT-2「每个会计期间至少评估一次」承载为：该期间存在未更正评估记录）。
     *
     * <p><b>双人复核</b>：复核人 ≠ 操作人（R-ACCT-8）；留痕 = {@code t_period_close_log}
     * （只增不改，precheck_result 存三前置判定 JSON）+ 期间行 {@code closed_by}/
     * {@code closed_time}/{@code close_reviewer_id}（CLOSE）或 {@code reopened_by}/
     * {@code reopened_time} + {@code reopen_reason}（REOPEN，原因必填）。
     * <b>整笔事务包裹</b>（TE-T077-001 判例）。
     *
     * @param periodId   会计期间
     * @param action     {@code CLOSE} / {@code REOPEN}
     * @param req        请求（REOPEN 时 reason 必填）
     * @param operatorId 操作人（会话身份）
     * @param reviewerId 复核人（≠ 操作人）
     * @return 留痕行
     */
    public PeriodCloseLog closePeriod(long periodId, String action, PeriodCloseRequest req,
                                      long operatorId, long reviewerId) {
        requireVoucherSupported();
        boolean isClose;
        if (CLOSE_ACTION_CLOSE.equals(action)) {
            isClose = true;
        } else if (CLOSE_ACTION_REOPEN.equals(action)) {
            isClose = false;
        } else {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "action 取值非法（CLOSE/REOPEN）：" + action);
        }
        if (reviewerId == operatorId) {
            // R-ACCT-8：关账与撤销关账均须双人复核（复核人 ≠ 操作人）
            throw BizException.of(ErrorCode.POSITION_SEPARATION_CONFLICT,
                    "关账复核人不得与操作人同一人（R-ACCT-8 双人复核）：操作人=" + operatorId);
        }
        if (!isClose && (req == null || isBlank(req.reason))) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "撤销关账原因必填（FR-ACCT-12 验收 4 留痕）：复现=补齐 reason");
        }
        return tx.inTransaction(conn -> doClosePeriod(periodId, isClose, req, operatorId, reviewerId));
    }

    private PeriodCloseLog doClosePeriod(long periodId, boolean isClose, PeriodCloseRequest req,
                                         long operatorId, long reviewerId) {
        AccountingPeriod period = periodDao.findById(periodId);
        if (period == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "会计期间不存在：id=" + periodId);
        }
        if (isClose) {
            if (!"OPEN".equals(period.status)) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "仅开放期间可关账：periodKey=" + period.periodKey + " status=" + period.status);
            }
            // 三前置校验（R-ACCT-8：任一不满足不得关账）
            List<Voucher> unposted = voucherDao.findByPeriodAndStatusIn(period.periodKey,
                    List.of(VOUCHER_DRAFT, VOUCHER_PENDING_REVIEW));
            String precheck1 = unposted.isEmpty() ? "①全部凭证已审核或已冲销：通过"
                    : "①全部凭证已审核或已冲销：不通过（" + unposted.size() + " 张："
                            + voucherNoList(unposted) + "）";
            String precheck2 = settleCompleted(period) ? "②该期间结算已完成：通过"
                    : "②该期间结算已完成：不通过（存在未确认结算单）";
            boolean assessed = periodAssessed(period.periodKey);
            String precheck3 = assessed ? "③该期间有效性评估已完成：通过"
                    : "③该期间有效性评估已完成：不通过（R-ACCT-2 每期至少评估一次，该期间无未更正评估记录）";
            boolean allPass = unposted.isEmpty() && settleCompleted(period) && assessed;
            String precheckJson = JsonCodec.toJson(Map.of(
                    "precheck1", precheck1, "precheck2", precheck2, "precheck3", precheck3));
            if (!allPass) {
                // 10012 阻断四项载荷（R-ACCT-8；FR-ACCT-12 验收 2 列出未审核凭证）
                throw BizException.blocking(ErrorCode.COMPLIANCE_GATE_FAILED,
                        "关账前置条件不满足，拒绝关账（R-ACCT-8）：periodKey=" + period.periodKey,
                        "R-ACCT-8",
                        precheck1 + "；" + precheck2 + "；" + precheck3,
                        "三前置全部通过",
                        "处理未审核凭证（审核或冲销）/完成该期间结算/补做该期间有效性评估后重试");
            }
            period.status = "CLOSED";
            period.closedBy = operatorId;
            period.closedTime = Clocks.now();
            period.closeReviewerId = reviewerId;
        } else {
            if (!"CLOSED".equals(period.status)) {
                throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                        "仅已关账期间可撤销关账：periodKey=" + period.periodKey + " status="
                                + period.status);
            }
            period.status = "OPEN";
            period.reopenedBy = operatorId;
            period.reopenedTime = Clocks.now();
            period.reopenReason = req.reason.trim();
        }
        if (periodDao.updateById(period, operatorId) != 1) {
            throw BizException.of(ErrorCode.STATE_NOT_ALLOWED,
                    "会计期间并发冲突：periodKey=" + period.periodKey + "；复现=重新读取后再提交");
        }
        PeriodCloseLog log = new PeriodCloseLog();
        log.periodKey = period.periodKey;
        log.action = isClose ? CLOSE_ACTION_CLOSE : CLOSE_ACTION_REOPEN;
        log.precheckResult = isClose
                ? JsonCodec.toJson(Map.of("precheck1", "①全部凭证已审核或已冲销：通过",
                        "precheck2", "②该期间结算已完成：通过",
                        "precheck3", "③该期间有效性评估已完成：通过"))
                : (req == null ? "" : JsonCodec.toJson(Map.of("reason", req.reason.trim())));
        log.operatorId = operatorId;
        log.reviewerId = reviewerId;
        log.reason = isClose ? null : req.reason.trim();
        log.actionTime = Clocks.now();
        if (periodCloseLogDao.insert(log, operatorId) != 1) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR,
                    "关账留痕写入失败：periodKey=" + period.periodKey);
        }
        return log;
    }

    /** 关账前置①的凭证清单文本（FR-ACCT-12 验收 2「列出该凭证」）。 */
    private String voucherNoList(List<Voucher> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 10; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(list.get(i).voucherNo);
        }
        if (list.size() > 10) {
            sb.append("…共 ").append(list.size()).append(" 张");
        }
        return sb.toString();
    }

    /**
     * 关账前置②（结算已完成）：期间起止范围内的结算单全部 {@code CONFIRMED}/{@code RECONCILED}
     * （AuditService.isSettled 同源口径）；期间范围内无结算单 = 视为完成（无账可结）。
     * 全局期间级判定（例外 E2，无子账户维度）。
     */
    private boolean settleCompleted(AccountingPeriod period) {
        long unsettled = settleCycleDao.count(
                "trade_date >= ? AND trade_date <= ? AND status <> ? AND status <> ?",
                List.of(period.periodStart, period.periodEnd, "CONFIRMED", "RECONCILED"));
        return unsettled == 0;
    }

    /**
     * 关账前置③（有效性评估已完成）：该期间存在<b>未更正</b>评估记录（R-ACCT-2「每个会计期间
     * 至少评估一次」承载；跨全部套期关系任一命中即视为该期间已开展评估）。
     *
     * <p><b>为何按关系逐个走 {@link EffectivenessAssessDao#findActiveByRelationAndPeriod}</b>
     * （TE-T079-002）：该方法过滤 {@code is_corrected='N'}（T-076 交付面），已更正评估
     * （判定已作废）不得使前置③通过；原 {@code findByPeriodAndConclusion} 三联不滤
     * {@code is_corrected}，已更正记录仍命中 ⇒ 前置③失真。取数形态与 FR-ACCT-2 验收 4
     * 提醒查询（本类 :736）同源。
     */
    private boolean periodAssessed(String periodKey) {
        for (HedgeRelation r : hedgeRelationDao.findBySubAccounts(null)) {
            if (assessDao.findActiveByRelationAndPeriod(r.id, periodKey) != null) {
                return true;
            }
        }
        return false;
    }

    /** 关账历史（{@code idx_period_close_log_key_time}；FR-ACCT-12 审计）。 */
    public List<PeriodCloseLog> listCloseLogs(String periodKey, long operatorId) {
        requireVoucherSupported();
        return periodCloseLogDao.findByPeriod(periodKey);
    }

    // ================================================================ 请求 DTO（T-079）

    /** 凭证草稿生成请求（{@code T-079} 新增）。 */
    public static final class VoucherDraftRequest {
        /** 子账户（必填；缺失拒绝——R-ACCT-5 凭证按子账户出账）。 */
        public Integer subAccountId;
        /** 会计期间（yyyy-MM；期间已关账拒绝）。 */
        public String periodKey;
        /** 凭证类型（ck_t_voucher_type 十值；科目建议按类型映射 biz_scene）。 */
        public String voucherType;
        /** 业务摘要（必填，落分录 biz_desc）。 */
        public String summary;
        /** 借贷金额（> 0；借贷成对同额）。 */
        public BigDecimal amount;
        /** 数量（可空，如成交手数）。 */
        public BigDecimal qty;
        /** 关联套保计划（可空）。 */
        public Long hedgePlanId;
        /** 关联套期关系（可空）。 */
        public Long hedgeRelationId;
        /** 关联结算单（可空）。 */
        public Long settleCycleId;
        /** 关联实货敞口（可空）。 */
        public Long exposureId;
        /** 关联成交记录（可空，落分录 trade_fill_id）。 */
        public Long tradeFillId;
    }

    /** 凭证审核请求（{@code T-079} 新增）。 */
    public static final class VoucherReviewRequest {
        /** 动作：{@code SUBMIT} / {@code APPROVE} / {@code REJECT} / {@code WITHDRAW}。 */
        public String action;
        /** 审核意见（可空）。 */
        public String reviewComment;
        /** 驳回原因（REJECT 时必填）。 */
        public String rejectReason;
    }

    /** 凭证冲销请求（{@code T-079} 新增）。 */
    public static final class VoucherReverseRequest {
        /** 冲销原因（必填）。 */
        public String reason;
        /** 第二复核人（必填；≠ 发起人且 ≠ 生成人，DC-032）。 */
        public Long coReviewerId;
    }

    /** 期间关账/撤销请求（{@code T-079} 新增；REOPEN 时 reason 必填）。 */
    public static final class PeriodCloseRequest {
        /** 撤销关账原因。 */
        public String reason;
    }

    /** 凭证详情（头 + 分录 + 审核链；{@code T-079} 新增视图载体）。 */
    public static final class VoucherDetail {
        /** 凭证头。 */
        public Voucher voucher;
        /** 分录行（line_no 升序）。 */
        public List<VoucherLine> lines;
        /** 审核链（node_seq 升序）。 */
        public List<VoucherReview> reviews;
    }
}
